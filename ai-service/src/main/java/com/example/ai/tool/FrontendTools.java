package com.example.ai.tool;

import com.example.ai.dto.AiDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.*;

/**
 * 前端可执行工具的单一事实来源。
 *
 * 设计原则（响应经验 1107374 + 100029435 + 404338）：
 *   1. 协议强制前置：每个工具必须提供 name / 描述 / JSON 参数 Schema / 必选字段。
 *   2. 生产链路使用 {@link #RAW_TOOLS_AS_MAPS} 配合原生 RestClient 作为 OpenAI
 *      请求体中的 tools 数组直接序列化发送，完全掌控协议细节。
 *   3. 后端永远不执行前端工具；只做参数结构正确性校验、确认闸门默认值、影响范围摘要。
 *   4. 前端维护同名注册表并实际执行（路由跳转 / SpreadJS 单元格更新 / Pinia 状态变更）。
 *   5. 需要确认的工具（破坏性 / 批量）在 toolCalls 返回时就打上 needConfirm=true，前端强制弹框。
 *
 * 命名约束（DeepSeek / OpenAI 强制）：工具名只能含字母数字下划线/短横：^[a-zA-Z0-9_-]+$
 */
public final class FrontendTools {

    private static final ObjectMapper OM = new ObjectMapper();

    private FrontendTools() {}

    // ================= 工具分类（决定 loop 内是同步执行还是暂停回前端） =================

    /**
     * 工具执行位置：
     *   FRONTEND - 前端执行（路由跳转/SpreadJS/Pinia 状态），loop 必须暂停等回灌
     *   BACKEND  - 后端同步执行（查数据库/调外部 API），loop 内直接执行后继续
     *
     * 所有工具定义都集中在后端 FrontendTools（用户硬约束），
     * 前端只接收 toolName+args，不维护 schema。
     */
    public enum ToolKind { FRONTEND, BACKEND }

    /**
     * 判定工具执行位置。当前 7 个工具均为前端工具。
     * 未来若新增后端只读工具（如 query_config_defs / count_rows），返回 BACKEND 即可。
     */
    public static ToolKind kindOf(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "navigate_step",
                 "select_definitions",
                 "run_flow",
                 "table_batch_set_field",
                 "table_delete_rows",
                 "table_replace_values",
                 "confirm_complete",
                 "collect_user_input",
                 "excel_import",
                 "excel_export" -> ToolKind.FRONTEND;
            // 预留：未来后端工具在此 case 命中并返回 BACKEND
            default -> ToolKind.FRONTEND;
        };
    }

    // ================= 7 个工具的 Schema 定义（唯一事实来源）=================
    // 【注意】ALL_SPECS 必须在 ALL / RAW_TOOLS_AS_MAPS 之前声明，否则类加载时静态字段尚未初始化导致 NPE。
    private static final List<ToolSpec> ALL_SPECS = List.of(
            new ToolSpec("navigate_step",
                    "跳转工作区向导到某个步骤，可选先切换场景。不要在内容里输出任何 JSON，必须用本工具返回。",
                    objectSchema(Map.of(
                            "step", stringEnumSchema(Arrays.asList(
                                    "SELECT_SCENARIO", "SELECT_DEFS", "VIEW_DEFS",
                                    "QUERY_COND", "PRECHECK", "REVIEW", "PUBLISH", "RESULT", "DASHBOARD"),
                                    "目标步骤代码"),
                            "scenario", stringEnumSchema(Arrays.asList("EXPORT", "IMPORT", "ADD", "MODIFY"),
                                    "若尚未选场景，需在此指定场景代码；已选场景则可省略")
                    ), List.of("step"))),

            new ToolSpec("select_definitions",
                    "批量选择或取消配置项。模式 add 为增量添加, remove 为移除, set 为覆盖式设置。",
                    objectSchema(Map.of(
                            "mode", stringEnumSchema(Arrays.asList("add", "remove", "set"), "操作模式"),
                            "defIds", arraySchema(integerSchema("配置定义 ID"), "要操作的配置定义 ID 数组，不能传 code/name，必须为整数 ID")
                    ), List.of("mode", "defIds"))),

            new ToolSpec("run_flow",
                    "一键走完一个业务流程（导出单个、导出全部等）。对于大批量/写操作请打上 needConfirm。",
                    objectSchema(Map.of(
                            "flowType", stringEnumSchema(Arrays.asList("export_single", "export_all"),
                                    "流程类型: export_single 导出指定的 1 个 defId；export_all 导出所有可用配置"),
                            "defIds", arraySchema(integerSchema("defId"), "export_single 也可用长度 1 的数组或 defId 单值"),
                            "defId", integerSchema("仅 export_single 时使用；优先 defId")
                    ), List.of("flowType"))),

            new ToolSpec("table_batch_set_field",
                    "在当前编辑的某张配置表里，按 where 条件匹配行，把 set 对象里的字段批量修改为新值。这是破坏性写操作，必须 needConfirm=true。",
                    objectSchema(Map.of(
                            "configDefId", integerSchema("要修改的配置定义 ID"),
                            "where", objectSchemaWithDesc(Map.of(
                                    "field", stringSchema("条件字段 key，必须与配置定义中的字段 key 完全一致"),
                                    "op", stringEnumSchema(Arrays.asList("eq", "ne", "lt", "le", "gt", "ge", "contains", "starts_with", "ends_with"), "比较操作符"),
                                    "value", rawSchema("与字段类型匹配的值（数字/字符串/布尔）")
                            ), List.of(), "匹配条件；为空或缺失表示全表更新（风险极高）"),
                            "set", Map.of("type", "object",
                                    "description", "要更新的 {字段: 新值}，字段必须存在于该配置定义中",
                                    "additionalProperties", true)
                    ), List.of("configDefId", "set"))),

            new ToolSpec("table_delete_rows",
                    "删除表格指定范围或满足条件的行。破坏性操作，必须 needConfirm=true。",
                    objectSchema(Map.of(
                            "configDefId", integerSchema("配置定义 ID"),
                            "fromRow", integerSchema("从第 fromRow 行开始删除（1 基，表头算第 0 行，数据首行=1）"),
                            "toRow", integerSchema("删除到第 toRow 行（含）；缺失表示删到末尾"),
                            "where", objectSchemaWithDesc(Map.of(
                                    "field", stringSchema("字段 key"),
                                    "op", stringEnumSchema(Arrays.asList("eq", "ne", "lt", "le", "gt", "ge"), "操作符"),
                                    "value", rawSchema("阈值")
                            ), List.of(), "当 fromRow/toRow 缺失时使用条件删除")
                    ), List.of("configDefId"))),

            new ToolSpec("table_replace_values",
                    "将某个字段中搜索到的值替换为新值（支持字面量 / 正则）。",
                    objectSchema(Map.of(
                            "configDefId", integerSchema("配置定义 ID"),
                            "field", stringSchema("字段 key"),
                            "search", stringSchema("搜索串"),
                            "replace", stringSchema("替换为（字面量或正则 $1 捕获组）"),
                            "regex", Map.of("type", "boolean",
                                    "description", "是否把 search 当作正则表达式",
                                    "default", false)
                    ), List.of("configDefId", "field", "search", "replace"))),

            new ToolSpec("confirm_complete",
                    "当前步骤所有工作完成，引导用户确认后结束流程或跳到下一步。",
                    objectSchema(Map.of(
                            "target", stringEnumSchema(Arrays.asList("FINISH_TASK", "NEXT_STEP"), "完成方式")
                    ), List.of("target"))),

            // ============ Excel 导入/导出类（浏览器内 ExcelIO，文件本体不进 LLM） ============
            new ToolSpec("excel_import",
                    "上传 Excel(.xlsx)文件,解析后灌入到指定配置定义。会暂停等待用户上传文件。导入前请确认已用「下载 Excel 模板」按钮下载模板填写,保证表头与配置字段一致。导入模式 append 追加(默认)或 merge 按 id 合并更新。",
                    objectSchema(Map.of(
                            "configDefId", integerSchema("要导入到的配置定义 ID(来自 list_config_defs 返回的 id)"),
                            "mode", stringEnumSchema(Arrays.asList("append", "merge"), "导入模式 append 追加(默认)或 merge 按 id 合并更新")
                    ), List.of("configDefId"))),

            new ToolSpec("excel_export",
                    "导出配置数据为 Excel(.xlsx)文件并下载。可指定单个或多个配置定义 ID,空则导出当前任务已选配置项(selectedDefIds),再空则导出全部启用配置项。多个配置项会合并为多 sheet 工作簿。",
                    objectSchema(Map.of(
                            "configDefIds", arraySchema(integerSchema("配置定义 ID"), "要导出的配置定义 ID 数组,空则导出当前任务 selectedDefIds 全部"),
                            "fileName", stringSchema("导出文件名(不含扩展名,自动追加 -时间戳.xlsx)")
                    ), List.of()))
    );

    // ================= 工具声明（同一套 Schema 派生两种对外格式） =================

    /**
     * Spring AI 内部 FunctionTool 格式（作对照参考，生产链路使用 RAW_TOOLS_AS_MAPS）。
     */
    @SuppressWarnings("unused")
    public static final List<OpenAiApi.FunctionTool> ALL = buildAllAsFunctionTools();

    /**
     * ====== 生产链路实际使用的原生 OpenAI 协议格式 ======
     *   [ { "type":"function",
     *       "function": { "name":"...", "description":"...", "parameters":{ JSON Schema 对象 } } }, ... ]
     * 注意 parameters 是 JSON 对象（不是 JSON 字符串），与 request body 其余部分一起整体序列化。
     */
    public static final List<Map<String, Object>> RAW_TOOLS_AS_MAPS = buildAllAsRawMaps();

    // --------- 两种格式各自的 Factory ---------

    private static List<OpenAiApi.FunctionTool> buildAllAsFunctionTools() {
        List<OpenAiApi.FunctionTool> list = new ArrayList<>();
        for (ToolSpec s : ALL_SPECS) {
            list.add(_buildFunctionTool(s.name, s.desc, s.parametersSchema));
        }
        return Collections.unmodifiableList(list);
    }

    private static List<Map<String, Object>> buildAllAsRawMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolSpec s : ALL_SPECS) {
            list.add(_buildRawTool(s.name, s.desc, s.parametersSchema));
        }
        return Collections.unmodifiableList(list);
    }

    private static OpenAiApi.FunctionTool _buildFunctionTool(String name, String desc,
                                                             Map<String, Object> parametersSchema) {
        String schemaJson;
        try {
            schemaJson = OM.writeValueAsString(parametersSchema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Schema JSON 序列化失败: tool=" + name, e);
        }
        OpenAiApi.FunctionTool.Function fn =
                new OpenAiApi.FunctionTool.Function(name, desc, schemaJson);
        return new OpenAiApi.FunctionTool(OpenAiApi.FunctionTool.Type.FUNCTION, fn);
    }

    private static Map<String, Object> _buildRawTool(String name, String desc,
                                                     Map<String, Object> parametersSchema) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", desc);
        fn.put("parameters", parametersSchema);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    private record ToolSpec(String name, String desc, Map<String, Object> parametersSchema) {}

    // ================= 确认闸门 / 影响摘要 / 参数整形 =================

    /** 该工具是否默认需要用户确认（仅前端可最终裁定，后端给默认值） */
    public static boolean defaultNeedConfirm(String toolName) {
        return switch (toolName) {
            case "navigate_step", "select_definitions", "confirm_complete", "collect_user_input",
                 "excel_import", "excel_export" -> false;
            case "run_flow", "table_batch_set_field", "table_delete_rows", "table_replace_values" -> true;
            default -> true;
        };
    }

    /** 基于参数生成人类可读的影响摘要，给前端确认弹窗使用 */
    public static String summarizeImpact(String toolName, Map<String, Object> args) {
        if (args == null) args = Collections.emptyMap();
        try {
            return switch (toolName) {
                case "navigate_step" -> String.format("将跳转到步骤 %s%s",
                        nullSafe(args.get("step"), "未指定"),
                        args.get("scenario") == null ? "" : "（场景切换为 " + args.get("scenario") + "）");
                case "select_definitions" -> String.format("将 %s 配置项共 %d 个",
                        modeLabel(nullSafe(args.get("mode"), "")),
                        arraySize(args.get("defIds")));
                case "run_flow" -> String.format("将执行流程 %s，涉及 %d 个配置项",
                        nullSafe(args.get("flowType"), ""),
                        args.get("defId") != null ? 1 : arraySize(args.get("defIds")));
                case "table_batch_set_field" -> {
                    String where = nullSafe(mapGet(args, "where", "op"), "") + " "
                            + nullSafe(mapGet(args, "where", "field"), "") + " = "
                            + nullSafe(mapGet(args, "where", "value"), "<无条件-全表>");
                    yield String.format("将对【配置%d】按条件(%s)批量更新 %d 个字段",
                            objInt(args.get("configDefId")),
                            where, mapSize(mapObject(args.get("set"))));
                }
                case "table_delete_rows" -> {
                    Object where = args.get("where");
                    if (where instanceof Map) {
                        yield String.format("将删除【配置%d】满足条件的行（非可逆）", objInt(args.get("configDefId")));
                    }
                    yield String.format("将删除【配置%d】第 %s 行 ~ %s 行（非可逆）",
                            objInt(args.get("configDefId")),
                            nullSafe(args.get("fromRow"), "1"),
                            args.get("toRow") == null ? "末尾" : args.get("toRow"));
                }
                case "table_replace_values" -> String.format("将在【配置%d】.%s 中把 '%s' 替换为 '%s'%s",
                        objInt(args.get("configDefId")),
                        nullSafe(args.get("field"), "?"),
                        nullSafe(args.get("search"), "?"),
                        nullSafe(args.get("replace"), ""),
                        Boolean.TRUE.equals(args.get("regex")) ? "（正则模式）" : "");
                case "confirm_complete" -> String.format("将结束流程（目标=%s）", nullSafe(args.get("target"), "NEXT_STEP"));
                case "collect_user_input" -> String.format("将向用户收集 %d 个表单字段", arraySize(args.get("fields")));
                case "excel_import" -> String.format("将上传 Excel 文件并解析灌入到配置 #%d%s",
                        objInt(args.get("configDefId")),
                        "merge".equals(args.get("mode")) ? "(merge 合并)" : "(append 追加)");
                case "excel_export" -> {
                    int n = arraySize(args.get("configDefIds"));
                    yield String.format("将导出 %s 个配置项为 xlsx 并下载", n > 0 ? String.valueOf(n) : "当前已选/全部");
                }
                default -> "未知工具，前端将阻断执行";
            };
        } catch (Exception e) {
            return "摘要生成失败，请人工复核参数: " + args;
        }
    }

    /** 对 LLM 返回的工具调用参数做最小协议整形 */
    public static Map<String, Object> normalizeArgs(String toolName, Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw != null) out.putAll(raw);
        return out;
    }

    /** 由 AiAction 老协议转为新的 toolCalls（降级兜底专用） */
    public static AiDTO.FrontendToolCall adaptFromLegacy(AiDTO.AiAction a) {
        Map<String, Object> args = new LinkedHashMap<>(a.getPayload() == null ? Collections.emptyMap() : a.getPayload());
        String tool = switch (a.getType() == null ? "" : a.getType()) {
            case "navigate" -> "navigate_step";
            case "select_defs" -> "select_definitions";
            case "flow" -> "run_flow";
            case "table_update" -> "table_batch_set_field";
            case "table_delete" -> "table_delete_rows";
            case "table_replace" -> "table_replace_values";
            case "confirm_complete" -> "confirm_complete";
            default -> "confirm_complete";
        };
        return AiDTO.FrontendToolCall.builder()
                .callId("legacy-" + (a.getId() == null ? UUID.randomUUID().toString().substring(0, 8) : a.getId()))
                .toolName(tool)
                .args(normalizeArgs(tool, args))
                .title(a.getTitle() == null ? tool : a.getTitle())
                .impact(a.getImpact() == null ? summarizeImpact(tool, args) : a.getImpact())
                .needConfirm(a.getNeedConfirm() == null ? defaultNeedConfirm(tool) : a.getNeedConfirm())
                .legacy(true)
                .build();
    }

    // ================= JSON Schema 构造辅助 =================

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return objectSchemaWithDesc(properties, required, null);
    }

    private static Map<String, Object> objectSchemaWithDesc(Map<String, Object> properties,
                                                            List<String> required, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (required != null && !required.isEmpty()) m.put("required", required);
        if (description != null && !description.isBlank()) m.put("description", description);
        m.put("additionalProperties", false);
        return m;
    }

    private static Map<String, Object> stringSchema(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        if (desc != null) m.put("description", desc);
        return m;
    }

    private static Map<String, Object> stringEnumSchema(List<String> values, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", values);
        if (desc != null) m.put("description", desc);
        return m;
    }

    private static Map<String, Object> integerSchema(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer");
        if (desc != null) m.put("description", desc);
        return m;
    }

    private static Map<String, Object> arraySchema(Object itemsSchema, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", itemsSchema);
        if (desc != null) m.put("description", desc);
        return m;
    }

    private static Map<String, Object> rawSchema(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (desc != null) m.put("description", desc);
        return m;
    }

    // ================= 杂项辅助 =================

    private static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "add" -> "添加";
            case "remove" -> "移除";
            case "set" -> "覆盖选择";
            default -> "操作";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapObject(Object o) { return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap(); }
    @SuppressWarnings("unchecked")
    private static Object mapGet(Object parent, String... path) {
        Object cur = parent;
        for (String p : path) {
            if (cur instanceof Map m) cur = m.get(p); else return null;
        }
        return cur;
    }
    private static int mapSize(Map<?,?> m) { return m == null ? 0 : m.size(); }
    private static int arraySize(Object o) {
        if (o == null) return 0;
        if (o instanceof Collection c) return c.size();
        if (o.getClass().isArray()) return java.lang.reflect.Array.getLength(o);
        return 1;
    }
    private static int objInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return -1; }
        return -1;
    }
    private static String nullSafe(Object o, String def) { return o == null ? def : String.valueOf(o); }
}
