package com.example.configmgr.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工具 inputSchema 的结构归一化（纯函数）：把<b>参数级子 schema 里的 {@code $defs}</b> 合并提升到
 * <b>文档根</b>，使 {@code #/$defs/X} 形态的 {@code $ref} 真正可解析。
 *
 * <h2>为什么需要它</h2>
 * victools 生成 schema 时，被同一类型引用多次的 record 会被提级成 {@code $defs} 条目，但落点是
 * <b>参数所在的子 schema</b>（例如 {@code properties.form.$defs}），而 {@code $ref} 写的是从
 * <b>文档根</b>解析的 {@code #/$defs/X} —— JSON Pointer 永远从根解析，于是 ref 指向不存在的定义。
 * 后端链路（Spring AI）原样透传，不校验；上游模型接口会以
 * 「Invalid schema for function ...」400 拒绝<b>整个请求</b>，表现为披露该工具的页面整轮失败。
 *
 * <h2>口径</h2>
 * <ul>
 * <li><b>只搬不删</b>：{@code $ref} 原样保留（删 ref / 内联类型都是症状压制，会丢字段描述）；</li>
 * <li><b>同名去重</b>：根上已有同名定义且内容<b>逐字段相同</b> ⇒ 丢弃重复副本（幂等）；</li>
 * <li><b>同名冲突即启动失败</b>：不同内容无法安全合并（{@code #/$defs/X} 只有一种解析结果，
 * 改名需要连带重写 ref，属另一件事），故抛 {@link IllegalStateException} 让问题在启动期暴露，
 * 而不是留给上游 400；</li>
 * <li><b>无嵌套即原样返回</b>：未命中任何参数级 {@code $defs} 时返回<b>输入原串</b>，
 * 不重排/不重格式化 —— 保住其余工具 schema 的字节稳定（{@code tools → system → messages}
 * 前缀缓存不该被这次修复击穿）；</li>
 * <li><b>搬完自检</b>：提升后仍解析不了的 {@code #/...} ref 直接抛错（归一化不应制造新的悬空 ref）。</li>
 * </ul>
 */
public final class ToolSchemaNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFS_KEY = "$defs";

    private ToolSchemaNormalizer() {
    }

    /**
     * 把参数级 {@code $defs} 合并提升到文档根。
     *
     * @param toolName    工具名（只用于报错定位）
     * @param inputSchema 工具入参 schema 原文
     * @return 归一化后的 schema；无嵌套 {@code $defs} 时返回入参原串（同一引用）
     */
    public static String normalize(String toolName, String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return inputSchema;
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(inputSchema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具 " + toolName + " 的 inputSchema 不是合法 JSON", e);
        }
        if (!(parsed instanceof ObjectNode document)) {
            return inputSchema;
        }

        List<DefsSite> sites = new ArrayList<>();
        collectSites(document, "", true, sites);
        if (sites.isEmpty()) {
            return inputSchema;
        }

        ObjectNode rootDefs = rootDefs(toolName, document);
        for (DefsSite site : sites) {
            hoistInto(toolName, rootDefs, site);
        }
        for (DefsSite site : sites) {
            site.container.remove(DEFS_KEY);
        }
        assertRefsResolvable(toolName, document);
        return write(toolName, document);
    }

    private static void collectSites(JsonNode node, String path, boolean root, List<DefsSite> sites) {
        if (node.isObject()) {
            if (!root && node.has(DEFS_KEY)) {
                sites.add(new DefsSite((ObjectNode) node, path));
            }
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                collectSites(entry.getValue(), path + "/" + entry.getKey(), false, sites);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectSites(node.get(i), path + "/" + i, false, sites);
            }
        }
    }

    private static ObjectNode rootDefs(String toolName, ObjectNode document) {
        JsonNode existing = document.get(DEFS_KEY);
        if (existing == null) {
            return document.putObject(DEFS_KEY);
        }
        if (!existing.isObject()) {
            throw new IllegalStateException("工具 " + toolName + " 的 inputSchema 根上 " + DEFS_KEY
                    + " 不是对象，无法合并参数级定义");
        }
        return (ObjectNode) existing;
    }

    private static void hoistInto(String toolName, ObjectNode rootDefs, DefsSite site) {
        JsonNode defs = site.container.get(DEFS_KEY);
        if (!defs.isObject()) {
            throw new IllegalStateException("工具 " + toolName + " 的 inputSchema 在 " + site.path
                    + " 下的 " + DEFS_KEY + " 不是对象");
        }
        Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode definition = entry.getValue();
            JsonNode atRoot = rootDefs.get(name);
            if (atRoot == null) {
                rootDefs.set(name, definition.deepCopy());
            } else if (!atRoot.equals(definition)) {
                throw new IllegalStateException("工具 " + toolName + " 的 inputSchema 里 " + DEFS_KEY
                        + " 同名冲突：" + name + "（参数级 " + site.path + " 与文档根/其他位置的定义内容不同）。"
                        + "同名不同义无法安全合并到根，请让生成方改名后重试");
            }
        }
    }

    private static void assertRefsResolvable(String toolName, JsonNode document) {
        assertRefsResolvable(toolName, document, document);
    }

    /**
     * @param root 文档根（{@code #/...} 永远从根解析，必须整条链路都传根，不能传当前子树）
     * @param node 当前遍历节点
     */
    private static void assertRefsResolvable(String toolName, JsonNode root, JsonNode node) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if ("$ref".equals(key) && value.isTextual()) {
                String ref = value.asText();
                if (ref.startsWith("#/") && resolvePointer(root, ref.substring(1)) == null) {
                    throw new IllegalStateException("工具 " + toolName + " 的 inputSchema 归一化后仍存在悬空 ref："
                            + ref + "（" + DEFS_KEY + " 提升未覆盖它）");
                }
            } else {
                assertRefsResolvable(toolName, root, value);
            }
        }
    }

    private static JsonNode resolvePointer(JsonNode document, String pointer) {
        if (pointer.isEmpty()) {
            return document;
        }
        if (!pointer.startsWith("/")) {
            return null;
        }
        JsonNode current = document;
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current == null || current.isMissingNode()) {
                return null;
            }
            if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return null;
                }
                current = index >= 0 && index < current.size() ? current.get(index) : null;
            } else {
                current = current.get(token);
            }
        }
        return current;
    }

    private static String write(String toolName, ObjectNode document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具 " + toolName + " 的 inputSchema 无法序列化", e);
        }
    }

    private record DefsSite(ObjectNode container, String path) {
    }
}
