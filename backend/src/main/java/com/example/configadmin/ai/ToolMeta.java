package com.example.configadmin.ai;

import java.util.List;
import java.util.Map;

/**
 * 工具元数据（业务侧配置，Spring AI 不提供）：渐进式披露页面白名单 + HITL 确认标记。
 * 工具本体为 @Tool 注解方法（见 AiTools），JSON Schema 由 Spring AI 自动生成。
 */
public final class ToolMeta {

    public record Meta(String name, List<String> pages, boolean confirm) {
        public boolean visibleOn(String page) {
            return pages == null || pages.isEmpty() || pages.contains(page) || pages.contains("all");
        }
    }

    public static final Map<String, Meta> ALL = Map.ofEntries(
            Map.entry("get_ui_state", new Meta("get_ui_state", List.of("all"), false)),
            Map.entry("navigate_to_page", new Meta("navigate_to_page", List.of("all"), false)),
            Map.entry("list_config_defs", new Meta("list_config_defs", List.of("all"), false)),
            Map.entry("describe_config_def", new Meta("describe_config_def", List.of("all"), false)),
            Map.entry("query_config_data", new Meta("query_config_data", List.of("all"), false)),
            Map.entry("create_config_def", new Meta("create_config_def", List.of("defs"), true)),
            Map.entry("update_config_def", new Meta("update_config_def", List.of("defs"), true)),
            Map.entry("add_config_rows", new Meta("add_config_rows", List.of("data"), true)),
            Map.entry("select_export_defs", new Meta("select_export_defs", List.of("export"), false)),
            Map.entry("set_export_conditions", new Meta("set_export_conditions", List.of("export"), false)),
            Map.entry("start_export", new Meta("start_export", List.of("export"), false)),
            Map.entry("get_export_progress", new Meta("get_export_progress", List.of("export"), false)),
            Map.entry("download_export_file", new Meta("download_export_file", List.of("export"), false)),
            Map.entry("package_export_files", new Meta("package_export_files", List.of("export"), false)),
            Map.entry("select_import_defs", new Meta("select_import_defs", List.of("import"), false)),
            Map.entry("download_templates", new Meta("download_templates", List.of("import"), false)),
            Map.entry("ensure_import_batch", new Meta("ensure_import_batch", List.of("import"), false)),
            Map.entry("start_import_check", new Meta("start_import_check", List.of("import"), false)),
            Map.entry("get_batch_status", new Meta("get_batch_status", List.of("import"), false)),
            Map.entry("start_import", new Meta("start_import", List.of("import"), true)),
            Map.entry("start_publish", new Meta("start_publish", List.of("import"), true)),
            Map.entry("summarize_import_errors", new Meta("summarize_import_errors", List.of("import"), false))
    );

    public static boolean confirmRequired(String toolName) {
        Meta m = ALL.get(toolName);
        return m != null && m.confirm();
    }

    private ToolMeta() {
    }
}
