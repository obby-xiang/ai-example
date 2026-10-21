package com.example.configmgr.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML 定义源（阶段 A）：`classpath:ai/tools.yaml`。
 *
 * <p>
 * 三条实现口径：
 * <ol>
 * <li><b>文件缺失 = 空快照</b>（不是错误）：契约是"注解缺省、YAML 可选覆盖"，
 * 因此新环境/测试切片没有 YAML 时全部走注解，与迁移前等价；</li>
 * <li><b>解析失败 = 启动失败</b>：写了却写错必须炸（否则覆盖项静默失效）；</li>
 * <li><b>toolKey 唯一</b>：重复即启动失败（否则"哪一条生效"取决于 map 覆盖顺序）。</li>
 * </ol>
 */
@Slf4j
@Component
public class YamlToolDefinitionSource implements ToolDefinitionSource {

    /** 默认路径（与设计稿一致）。 */
    public static final String DEFAULT_LOCATION = "ai/tools.yaml";

    private final Resource resource;
    private final ToolSnapshot snapshot;

    public YamlToolDefinitionSource() {
        this(new ClassPathResource(DEFAULT_LOCATION));
    }

    public YamlToolDefinitionSource(Resource resource) {
        this.resource = resource;
        this.snapshot = parse();
    }

    @Override
    public ToolSnapshot load() {
        return snapshot;
    }

    @Override
    public String version() {
        return snapshot.version();
    }

    @SuppressWarnings("unchecked")
    private ToolSnapshot parse() {
        if (resource == null || !resource.exists()) {
            log.info("工具定义覆盖：未发现 {}，全部使用注解缺省", DEFAULT_LOCATION);
            return ToolSnapshot.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map<?, ?> root)) {
                throw new IllegalStateException("工具定义 YAML 顶层必须是映射：" + resource);
            }
            String version = root.get("version") == null ? "unknown" : String.valueOf(root.get("version"));
            String lang = root.get("lang") == null ? null : String.valueOf(root.get("lang"));
            Map<String, ToolDefinitionOverride> byKey = new LinkedHashMap<>();
            Object tools = root.get("tools");
            if (tools instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> entry)) {
                        throw new IllegalStateException("工具定义条目必须是映射：" + item);
                    }
                    ToolDefinitionOverride override = toOverride((Map<String, Object>) entry);
                    if (byKey.put(override.toolKey(), override) != null) {
                        throw new IllegalStateException("工具定义 toolKey 重复：" + override.toolKey());
                    }
                }
            }
            log.info("工具定义覆盖：{} 条，version={}，lang={}", byKey.size(), version, lang);
            return new ToolSnapshot(byKey, version, lang);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("工具定义 YAML 解析失败：" + resource, e);
        }
    }

    private static ToolDefinitionOverride toOverride(Map<String, Object> entry) {
        String toolKey = text(entry, "toolKey");
        if (toolKey == null) {
            throw new IllegalStateException("工具定义条目缺少 toolKey：" + entry);
        }
        return new ToolDefinitionOverride(
                toolKey,
                text(entry, "modelName"),
                text(entry, "description"),
                text(entry, "inputSchema"),
                text(entry, "title"),
                text(entry, "titleKey"),
                text(entry, "lang"),
                text(entry, "status"));
    }

    private static String text(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /** 解析结果里的 toolKey 列表（诊断/测试用）。 */
    public List<String> toolKeys() {
        return new ArrayList<>(snapshot.byToolKey().keySet());
    }
}
