package com.example.configmgr.ai.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具定义的**只读快照**（启动加载一次，请求路径零 IO）。
 *
 * <p>
 * 快照版本（{@code version()}）用于日志与缓存排查：工具描述是 prompt 前缀的第一段，
 * 改文案会让"tools → system → messages"整段缓存失效，故版本变化必须可观测。
 */
public record ToolSnapshot(Map<String, ToolDefinitionOverride> byToolKey, String version, String lang) {

    public ToolSnapshot {
        byToolKey = byToolKey == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(byToolKey));
    }

    public static ToolSnapshot empty() {
        return new ToolSnapshot(Map.of(), "none", null);
    }

    /** 按模型侧 name 查找覆盖项（YAML 的 toolKey 与 @Tool(name) 同名时即按名命中）。 */
    public ToolDefinitionOverride find(String modelName) {
        if (modelName == null) {
            return null;
        }
        for (ToolDefinitionOverride override : byToolKey.values()) {
            if (modelName.equals(override.effectiveModelName()) && override.published()) {
                return override;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return byToolKey.isEmpty();
    }
}
