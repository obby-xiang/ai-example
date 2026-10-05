package com.example.configadmin.ai;

import java.util.List;
import java.util.Map;

/** 工具定义：名称、描述（供模型）、JSON Schema、可见页面（渐进式披露）、是否需要确认（HITL）。 */
public record ToolDef(
        String name,
        String description,
        Map<String, Object> jsonSchema,
        List<String> pages,   // 允许的页面集合；空 = 全部页面可见
        boolean confirm        // true：执行前需用户在 AI 栏确认（破坏性操作）
) {
    public static ToolDef of(String name, String description, Map<String, Object> schema,
                             List<String> pages, boolean confirm) {
        return new ToolDef(name, description, schema, pages, confirm);
    }

    public boolean visibleOn(String page) {
        return pages == null || pages.isEmpty() || pages.contains(page) || pages.contains("all");
    }
}
