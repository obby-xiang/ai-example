package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolSchemaNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守卫用例：<b>每个工具 inputSchema 里的 {@code $ref} 都必须能在文档根解析</b>。
 *
 * <p>
 * 回归背景（2026-10-22，GF 批）：victools 生成 schema 时，被同一 record 引用 ≥2 次的类型
 * （{@code AiTools.FormCondition} 被 {@code FormField} 的 visibleWhen/requiredWhen/disabledWhen
 * 引用 3 次）会被<strong>提级</strong>成 {@code $defs} 条目，但落点是<b>参数级子 schema</b>
 * （{@code properties.form.$defs}），而 {@code $ref} 写的是从<b>文档根</b>解析的
 * {@code #/$defs/FormCondition} —— 根上没有该定义。后端自身不校验（Spring AI 原样透传），
 * 直到上游模型接口回 400「Invalid schema for function 'generative_form'」，表现为
 * "披露 GF 的页面整轮失败、所有 HITL 卡不可达"。
 *
 * <p>
 * 故这里把"根可解析"抬成<b>全量登记工具的硬不变量</b>：遍历 {@link ToolRegistry#allCallbacks()}
 * （不是披露子集 —— 该不变量与页面/任务上下文无关），对每一条 {@code #/...} 形态的 {@code $ref}
 * 按 JSON Pointer 在整份文档里解析，缺一个就红。修复走
 * {@code ToolSchemaNormalizer}（参数级 {@code $defs} 提升到文档根），不在受害者工具里做症状压制。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:toolschema-ref-integrity;DB_CLOSE_DELAY=-1"
})
class ToolSchemaRefIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void everyRefResolvesAgainstDocumentRootForAllRegisteredTools() throws Exception {
        List<ToolCallback> callbacks = toolRegistry.allCallbacks();
        assertThat(callbacks)
                .as("全量登记工具（遍历口径必须与 registeredCount 一致，防止空跑假绿）")
                .hasSize(toolRegistry.registeredCount())
                .isNotEmpty();

        Map<String, List<String>> unresolvedByTool = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            String schema = callback.getToolDefinition().inputSchema();
            List<String> unresolved = unresolvedRefs(name, schema);
            if (!unresolved.isEmpty()) {
                unresolvedByTool.put(name, unresolved);
            }
        }

        assertThat(unresolvedByTool)
                .as("工具 inputSchema 存在无法从文档根解析的 $ref（上游会以 400 拒绝整轮）；"
                        + "修复方向：把参数级 $defs 提升到根，而不是删掉 $ref")
                .isEmpty();
    }

    /**
     * 回归锚点：{@code generative_form} 的 {@code FormCondition} 必须落在<b>文档根</b> {@code $defs}，
     * 且参数级 {@code properties.form.$defs} 不再留存死副本。
     *
     * <p>
     * 只断言"没有悬空 ref"还不够：把 {@code $ref} 删掉（症状压制）也能让上一条绿。这里把
     * "<b>类型定义被提升并且还被引用</b>"钉成结构事实 —— 三个联动属性仍指向同一份根定义。
     */
    @Test
    void generativeFormDefsAreHoistedToDocumentRoot() throws Exception {
        ToolCallback callback = toolRegistry.getCallback("generative_form");
        assertThat(callback).as("generative_form 必须已登记").isNotNull();

        JsonNode document = MAPPER.readTree(callback.getToolDefinition().inputSchema());
        JsonNode formCondition = document.path("$defs").path("FormCondition");
        assertThat(formCondition.isMissingNode())
                .as("FormCondition 必须提升到文档根 $defs（否则 #/$defs/FormCondition 无解）")
                .isFalse();
        assertThat(fieldNamesOf(formCondition.path("properties")))
                .as("提升后的定义必须保留完整字段结构，不能退化成空壳")
                .contains("key", "op", "value", "values");

        assertThat(document.at("/properties/form/$defs").isMissingNode())
                .as("参数级 $defs 已提升，不应在子 schema 里留死副本")
                .isTrue();

        for (String property : List.of("visibleWhen", "requiredWhen", "disabledWhen")) {
            assertThat(document.at("/properties/form/properties/fields/items/properties/" + property).path("$ref")
                    .asText())
                    .as("%s 仍必须引用同一份根定义（不是删 ref 的症状压制）", property)
                    .isEqualTo("#/$defs/FormCondition");
        }
    }

    private static List<String> fieldNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ------------------------------------------------------------------
    // 归一化器自身的口径（纯函数行为锁）
    // ------------------------------------------------------------------

    @Test
    void normalizerHoistsNestedDefsAndIsIdempotent() throws Exception {
        String broken = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"object\","
                + "\"$defs\":{\"X\":{\"type\":\"string\"}},"
                + "\"properties\":{\"r\":{\"$ref\":\"#/$defs/X\"},\"s\":{\"$ref\":\"#/$defs/X\"}}}}}";

        String normalized = ToolSchemaNormalizer.normalize("sample", broken);
        JsonNode document = MAPPER.readTree(normalized);

        assertThat(document.at("/$defs/X/type").asText()).isEqualTo("string");
        assertThat(document.at("/properties/a/$defs").isMissingNode()).isTrue();
        assertThat(unresolvedRefs("sample", normalized)).isEmpty();
        assertThat(ToolSchemaNormalizer.normalize("sample", normalized))
                .as("幂等：已归一化的 schema 第二次不应再变化")
                .isEqualTo(normalized);
    }

    @Test
    void identicalDuplicateDefinitionsAreDedupedInsteadOfFailing() throws Exception {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"type\":\"object\",\"$defs\":{\"X\":{\"type\":\"string\"}},\"properties\":{\"r\":{\"$ref\":\"#/$defs/X\"}}},"
                + "\"b\":{\"type\":\"object\",\"$defs\":{\"X\":{\"type\":\"string\"}},\"properties\":{\"r\":{\"$ref\":\"#/$defs/X\"}}}}}";

        JsonNode document = MAPPER.readTree(ToolSchemaNormalizer.normalize("sample", schema));

        assertThat(fieldNamesOf(document.path("$defs"))).containsExactly("X");
        assertThat(document.at("/properties/a/$defs").isMissingNode()).isTrue();
        assertThat(document.at("/properties/b/$defs").isMissingNode()).isTrue();
    }

    @Test
    void conflictingDefinitionsFailFast() {
        String schema = "{\"type\":\"object\",\"$defs\":{\"X\":{\"type\":\"number\"}},\"properties\":{"
                + "\"a\":{\"type\":\"object\",\"$defs\":{\"X\":{\"type\":\"string\"}},\"properties\":{\"r\":{\"$ref\":\"#/$defs/X\"}}}}}";

        assertThatThrownBy(() -> ToolSchemaNormalizer.normalize("sample", schema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同名冲突")
                .hasMessageContaining("X");
    }

    @Test
    void schemaWithoutNestedDefsIsReturnedUntouched() {
        String plain = "{ \"type\" : \"object\", \"properties\" : { \"x\" : { \"type\" : \"string\" } } }";

        assertThat(ToolSchemaNormalizer.normalize("sample", plain))
                .as("不重排、不重格式化：其余工具 schema 的字节必须稳定（prompt 缓存）")
                .isSameAs(plain);
    }

    /** 收集一个 schema 里所有 {@code #/...} 形态的 {@code $ref} 中解析失败的那些。 */
    private static List<String> unresolvedRefs(String toolName, String schema) throws Exception {
        List<String> unresolved = new ArrayList<>();
        if (schema == null || schema.isBlank()) {
            return unresolved;
        }
        JsonNode document = MAPPER.readTree(schema);
        collectRefs(document, "", (path, ref) -> {
            if (ref.startsWith("#/") && resolvePointer(document, ref.substring(1)) == null) {
                unresolved.add(path + " -> " + ref);
            }
        });
        return unresolved;
    }

    private interface RefVisitor {
        void visit(String path, String ref);
    }

    private static void collectRefs(JsonNode node, String path, RefVisitor visitor) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                visitor.visit(path, ref.asText());
            }
            node.fields().forEachRemaining(e -> collectRefs(e.getValue(), path + "/" + e.getKey(), visitor));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectRefs(node.get(i), path + "/" + i, visitor);
            }
        }
    }

    /**
     * 按 RFC 6901 解析 JSON Pointer（{@code /a/b/0} 形态，不含前导 {@code #}）。
     *
     * @return 命中的节点；任何一段不存在则返回 null
     */
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
}
