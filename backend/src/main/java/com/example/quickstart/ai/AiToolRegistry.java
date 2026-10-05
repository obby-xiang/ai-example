package com.example.quickstart.ai;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具注册表（唯一事实来源）：16 个工具 = 6 后端 + 10 前端。
 * 按 page + wizardStep 过滤（渐进式披露第一道防线）。
 */
@Component
public class AiToolRegistry {

    public enum Kind { BACKEND, FRONTEND }

    /**
     * @param pages 适用页面（TASKS/EXPORT/IMPORT）
     * @param steps 仅在这些向导步骤可用；null 表示该页面全步骤可用
     */
    public record AiTool(String name, String description, Map<String, Object> parameters,
                         Kind kind, boolean needConfirm,
                         Set<String> pages, Set<Integer> steps) {
    }

    private static final Set<String> ALL_PAGES = Set.of("TASKS", "EXPORT", "IMPORT");

    private final List<AiTool> tools = new ArrayList<>();
    private final Map<String, AiTool> byName = new LinkedHashMap<>();

    public AiToolRegistry() {
        // ==================== 后端工具（Loop 内执行） ====================
        register("list_config_defs", "获取配置项列表（编码/名称/层级/依赖/正式区行数），可按层级过滤",
                obj(Map.of("level", Map.of("type", "string", "enum", List.of("GLOBAL", "REGION", "PROJECT"),
                        "description", "按层级过滤，可选")), null),
                Kind.BACKEND, false, ALL_PAGES, null);
        register("get_config_def", "获取单个配置项的完整定义（含全部字段定义）",
                obj(Map.of("code", str("配置项编码，如 COUNTRY")), List.of("code")),
                Kind.BACKEND, false, ALL_PAGES, null);
        register("count_config_data", "按条件统计某配置项正式区数据行数",
                obj(Map.of("code", str("配置项编码"),
                        "conditions", conditionsSchema()), List.of("code")),
                Kind.BACKEND, false, ALL_PAGES, null);
        register("list_tasks", "任务列表（最多 20 条摘要），可按类型/状态过滤",
                obj(Map.of(
                        "type", Map.of("type", "string", "enum", List.of("EXPORT", "IMPORT"), "description", "任务类型，可选"),
                        "status", Map.of("type", "string",
                                "enum", List.of("DRAFT", "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED"),
                                "description", "任务状态，可选")), null),
                Kind.BACKEND, false, ALL_PAGES, null);
        register("get_task", "任务详情（含向导步骤数据）及最近作业状态",
                obj(Map.of("taskId", Map.of("type", "integer", "description", "任务 ID")), List.of("taskId")),
                Kind.BACKEND, false, ALL_PAGES, null);
        register("create_task", "创建导出/导入任务，返回 {taskId, taskNo}",
                obj(Map.of(
                        "type", Map.of("type", "string", "enum", List.of("EXPORT", "IMPORT"), "description", "任务类型"),
                        "name", str("任务名称")), List.of("type", "name")),
                Kind.BACKEND, false, ALL_PAGES, null);

        // ==================== 前端工具 — 自动执行（needConfirm=false） ====================
        register("navigate_to", "跳转到指定页面（EXPORT/IMPORT 需携带 taskId）",
                obj(Map.of(
                        "page", Map.of("type", "string", "enum", List.of("TASKS", "EXPORT", "IMPORT"), "description", "目标页面"),
                        "taskId", Map.of("type", "integer", "description", "目标任务 ID（跳转导出/导入向导时必填）")), List.of("page")),
                Kind.FRONTEND, false, ALL_PAGES, null);
        register("get_workspace_state", "获取当前页面/任务/步骤/已选配置项/查询条件的快照",
                obj(Map.of(), null),
                Kind.FRONTEND, false, ALL_PAGES, null);
        register("select_config_defs", "在向导第 1 步勾选配置项",
                obj(Map.of(
                        "codes", Map.of("type", "array", "items", Map.of("type", "string"), "description", "配置项编码列表"),
                        "mode", Map.of("type", "string", "enum", List.of("REPLACE", "ADD"), "description", "替换或追加")),
                        List.of("codes", "mode")),
                Kind.FRONTEND, false, Set.of("EXPORT", "IMPORT"), Set.of(1));
        register("set_query_conditions", "为某个配置项设置导出查询条件",
                obj(Map.of(
                        "defCode", str("配置项编码"),
                        "conditions", conditionsSchema()), List.of("defCode", "conditions")),
                Kind.FRONTEND, false, Set.of("EXPORT"), Set.of(2));
        register("download_templates", "下载导入模板（1 个配置项=xlsx，多个=zip）",
                obj(Map.of("codes", Map.of("type", "array", "items", Map.of("type", "string"),
                        "description", "配置项编码列表")), List.of("codes")),
                Kind.FRONTEND, false, Set.of("IMPORT"), Set.of(1));
        register("download_export_files", "下载导出文件（缺省全部；1 个=xlsx，多个=zip）",
                obj(Map.of("codes", Map.of("type", "array", "items", Map.of("type", "string"),
                        "description", "配置项编码列表，缺省为全部")), null),
                Kind.FRONTEND, false, Set.of("EXPORT"), Set.of(3));

        // ==================== 前端工具 — 需用户确认（needConfirm=true） ====================
        register("start_export", "用当前选择的配置项与查询条件启动导出作业",
                obj(Map.of(), null),
                Kind.FRONTEND, true, Set.of("EXPORT"), Set.of(2, 3));
        register("start_check", "启动预检查作业（数据来自当前编辑区）",
                obj(Map.of(), null),
                Kind.FRONTEND, true, Set.of("IMPORT"), Set.of(2));
        register("start_import", "启动导入作业（校验通过后写入暂存区）",
                obj(Map.of(), null),
                Kind.FRONTEND, true, Set.of("IMPORT"), Set.of(3));
        register("start_publish", "启动发布作业（暂存数据全量替换到正式区）",
                obj(Map.of(), null),
                Kind.FRONTEND, true, Set.of("IMPORT"), Set.of(4));
    }

    private void register(String name, String description, Map<String, Object> parameters,
                          Kind kind, boolean needConfirm, Set<String> pages, Set<Integer> steps) {
        AiTool tool = new AiTool(name, description, parameters, kind, needConfirm, pages, steps);
        tools.add(tool);
        byName.put(name, tool);
    }

    /** 按当前页面/步骤过滤可用工具 */
    public List<AiTool> available(String page, Integer step) {
        String normalizedPage = (page == null || page.isBlank()) ? "TASKS" : page.toUpperCase();
        return tools.stream()
                .filter(t -> t.pages().contains(normalizedPage))
                .filter(t -> t.steps() == null || step == null || t.steps().contains(step))
                .toList();
    }

    public AiTool get(String name) {
        return byName.get(name);
    }

    /** 转为 OpenAI FunctionTool 列表（发给模型） */
    public List<OpenAiApi.FunctionTool> functionTools(String page, Integer step) {
        return available(page, step).stream()
                .map(t -> new OpenAiApi.FunctionTool(
                        new OpenAiApi.FunctionTool.Function(t.description(), t.name(), t.parameters(), null)))
                .toList();
    }

    // ================================ JSON Schema 辅助 ================================

    private static Map<String, Object> obj(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> conditionsSchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("field", Map.of("type", "string", "description", "字段名"));
        itemProps.put("op", Map.of("type", "string",
                "enum", List.of("EQ", "NE", "LIKE", "GT", "GE", "LT", "LE", "BETWEEN", "IN")));
        itemProps.put("value", Map.of("description", "条件值；IN 时为数组"));
        itemProps.put("value2", Map.of("description", "BETWEEN 的第二个值"));
        return Map.of("type", "array", "description", "查询条件列表",
                "items", obj(itemProps, List.of("field", "op", "value")));
    }
}
