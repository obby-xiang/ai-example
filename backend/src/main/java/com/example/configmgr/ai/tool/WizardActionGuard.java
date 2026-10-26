package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 向导动作（{@code set_condition} / {@code select_definitions}）的下发前复核。
 *
 * <h2>为什么这两个工具需要后端闸（工具安全边界批，用户 2026-10-25 裁决）</h2>
 * 两者都是 {@link ToolMeta.Channel#FRONTEND}：后端方法体是哨兵桩，模型给的参数<b>一路直达浏览器</b>。
 * 与表格写工具不同的是，它们的后果不是"改错几格"，而是<b>改变后续动作的工作选择</b>：
 * <ul>
 * <li>{@code set_condition} → 前端 {@code export.setConditions} 直接 {@code PUT} 落库
 *     （{@code conditionJson} 是导出作业的唯一条件来源）；</li>
 * <li>{@code select_definitions} → 改任务项勾选态（handler 缺失时回落 {@code select_defs} 直接落库）。</li>
 * </ul>
 * 两者都<b>可逆、可见、且下游 {@code start_export}/{@code start_import}/{@code start_publish} 仍有确认门</b>，
 * 故风险档标 {@code WRITE} 而<b>不</b>进确认门（多一道卡只会变成无意义弹窗，用户裁决明确否掉）。
 * 但这不等于"参数可以随便"：凡是"畸形 = 静默落到全量/落错目标"的形态，必须在下发前拒掉。
 *
 * <h2>三条 fail-closed 口径</h2>
 * <ol>
 * <li><b>参数畸形</b>：非 JSON 对象、未知顶层键、空 {@code codes}、小写 {@code mode}
 *     （前端 {@code args.mode === 'ADD' ? 'ADD' : 'REPLACE'} 会把 {@code "add"} 静默降级成
 *     整表替换）、非数组字段条件、非无限定算子的空串/纯空白取值、{@code IN []}、重复
 *     {@code fieldCode} —— 一律拒绝（后三类经前端压平即"丢弃该字段"，正是静默放大成全量的入口；
 *     {@code EMPTY}/{@code NOT_EMPTY} 不需要取值，不受此限）；</li>
 * <li><b>未知目标</b>：{@code defCode} / 任一 code 不在定义注册表里 ⇒ 拒绝并点名（不做"静默成功"
 *     —— 那会让条件/勾选落在不存在的配置项上，用户看到的是"设置成功"）；</li>
 * <li><b>空条件不得静默放大成全量</b>：{@code conditions} 缺失或既无 {@code scopeKeys}
 *     又无 {@code fields} ⇒ 拒绝。前端 {@code normalizeActionArgs} 会把缺失补成 {@code {}}，
 *     而 {@code {}} 的语义正是"不设条件 = 导出全部" —— 这正是"工具调用成功、条件却是空的"的来源。
 *     真要导出全部，模型不该调 {@code set_condition}（不设条件本来就是全部）。</li>
 * </ol>
 *
 * <p>遵守 {@link FrontendToolGuard} 的挂起机制契约：拒绝 ⇒ 不挂起、不发 {@code frontend_tool_request}，
 * 只把<b>逐条原因清单</b>回填模型（模型据此改一版再试，而不是白等前端超时）。
 */
@Component
@RequiredArgsConstructor
public class WizardActionGuard implements FrontendToolGuard {

    /** 本复核器认领的两个向导动作（按模型给出的工具名原文判定，不做形状推断）。 */
    static final Set<String> SUPPORTED = Set.of("set_condition", "select_definitions");

    /** 拒绝码（与 {@code SHEET_ARGUMENTS_REJECTED} / {@code UI_PRIMITIVE_ARGUMENTS_REJECTED} 同族）。 */
    public static final String REJECTED_CODE = "WIZARD_ARGUMENTS_REJECTED";

    private static final Set<String> SELECT_KEYS = Set.of("codes", "defCodes", "mode");

    private static final Set<String> CONDITION_KEYS = Set.of("defCode", "conditions");

    private static final Set<String> CONDITION_BODY_KEYS = Set.of("scopeKeys", "fields");

    private static final Set<String> FIELD_CONDITION_KEYS = Set.of("fieldCode", "operator", "value");

    private static final Set<String> MODES = Set.of("REPLACE", "ADD");

    /**
     * 字段算子白名单 = 前端 {@code ConditionOperator} 与后端 {@code ConditionEvaluator} 的交集
     * （与 {@link SheetWriteGuard} 同一份 11 个，不新造 DSL）。
     */
    private static final Set<String> OPERATORS = Set.of(
            "EQ", "NE", "CONTAINS", "LIKE", "STARTS_WITH", "IN", "EMPTY", "NOT_EMPTY", "GT", "GTE", "LT", "LTE");

    /** 不带取值的算子（带值反而说明模型在猜语义）。 */
    private static final Set<String> VALUELESS_OPERATORS = Set.of("EMPTY", "NOT_EMPTY");

    private final DefinitionService definitionService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String toolName) {
        return toolName != null && SUPPORTED.contains(toolName);
    }

    /**
     * 无工具名入口（兼容直接调用方）：按形状分流 —— 两支的必填键互斥，形状只决定"报哪一条"。
     *
     * <p>框架路径走带工具名的重载（{@link #inspectArguments(String, String)}），不做形状推断。
     */
    @Override
    public Verdict inspectArguments(String argumentsJson) {
        JsonNode root = parse(argumentsJson);
        if (root == null) {
            return reject("入参必须是合法 JSON 对象（形如 {\"defCode\":\"CURRENCY\",\"conditions\":{...}}）。");
        }
        String tool = root.has("defCode") || root.has("conditions") ? "set_condition" : "select_definitions";
        return decide(tool, root);
    }

    /**
     * 带工具名的复核（框架唯一入口）：按<b>真名</b>选校验分支，不做形状推断
     * （形状推断正是 {@code SheetWriteGuard} 旧实现的窄缝：少一个必填键就会被"错校验"放行）。
     */
    @Override
    public Verdict inspectArguments(String toolName, String argumentsJson) {
        if (!supports(toolName)) {
            return Verdict.accept();
        }
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return reject("入参不能为空：至少要有 " + ("select_definitions".equals(toolName) ? "codes" : "defCode/conditions") + "。");
        }
        JsonNode root = parse(argumentsJson);
        if (root == null) {
            return reject("入参必须是合法 JSON 对象（形如 {\"defCode\":\"CURRENCY\",\"conditions\":{...}}）。");
        }
        return decide(toolName, root);
    }

    /** 单工具名复核（测试与显式调用方使用，与框架入口同一实现）。 */
    public Verdict inspectArgumentsFor(String toolName, String argumentsJson) {
        return inspectArguments(toolName, argumentsJson);
    }

    @Override
    public Verdict inspectResult(String argumentsJson, String resultJson) {
        // 这两个动作的回灌是自由文本（"已设置查询条件（CURRENCY）"这类），没有可核对的数值口径
        // ⇒ 不新增"必须含共 N"之类的形式要求（那只会把一次成功判成失败）。
        return Verdict.accept();
    }

    // ── 分流 ────────────────────────────────────────────────────────────────

    private Verdict decide(String toolName, JsonNode root) {
        List<String> reasons = "select_definitions".equals(toolName) ? validateSelect(root)
                : validateCondition(root);
        if (reasons.isEmpty()) {
            return Verdict.accept();
        }
        return Verdict.reject(REJECTED_CODE, "向导动作入参不合法，未下发前端、未产生任何持久化写入。", reasons);
    }

    /** 解析入参原文；不是合法 JSON 对象时返回 {@code null}（调用方给固定文案）。 */
    private JsonNode parse(String argumentsJson) {
        try {
            JsonNode root = this.mapper.readTree(argumentsJson == null ? "" : argumentsJson);
            return root != null && root.isObject() ? root : null;
        }
        catch (Exception error) {
            return null;
        }
    }

    // ── select_definitions ──────────────────────────────────────────────────

    private List<String> validateSelect(JsonNode root) {
        List<String> reasons = new ArrayList<>();
        for (String key : fieldNames(root)) {
            if (!SELECT_KEYS.contains(key)) {
                reasons.add("不认识的参数 `" + key + "`：select_definitions 只接受 codes / defCodes / mode。");
            }
        }
        JsonNode codesNode = root.has("codes") ? root.get("codes") : root.get("defCodes");
        List<String> codes = new ArrayList<>();
        if (codesNode == null || codesNode.isNull()) {
            reasons.add("codes 必填（要勾选的配置定义编码列表，不能为空）。");
        }
        else if (!codesNode.isArray()) {
            reasons.add("codes 必须是配置定义编码的数组，例如 [\"CURRENCY\"]。");
        }
        else {
            for (JsonNode item : codesNode) {
                String code = item != null && item.isTextual() ? item.asText() : null;
                if (code == null || code.isBlank()) {
                    reasons.add("codes 的每一项都必须是非空字符串（实际出现：" + item + "）。");
                    continue;
                }
                if (!codes.contains(code.trim())) {
                    codes.add(code.trim());
                }
            }
            if (codesNode.isEmpty()) {
                reasons.add("codes 不能为空数组（要勾选哪些配置项必须写明；清空选择请让用户手动操作）。");
            }
        }
        if (root.hasNonNull("mode") && !MODES.contains(text(root.get("mode")))) {
            reasons.add("mode 只能是 REPLACE 或 ADD（大小写敏感；小写会被前端静默当成 REPLACE，故不接受）：实际 "
                    + root.get("mode") + "。");
        }
        // 形状先判：codes 已经不合法时不再挨个查定义（也避免把"空选"解释成"全部有效"）
        if (reasons.isEmpty() && !codes.isEmpty()) {
            List<String> known = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String code : codes) {
                if (definitionExists(code)) {
                    known.add(code);
                }
                else {
                    unknown.add(code);
                }
            }
            if (!unknown.isEmpty()) {
                reasons.add("以下配置定义不存在（未下发、未勾选任何不存在的项）：" + String.join("、", unknown)
                        + (known.isEmpty() ? "" : "；本次可用的 codes：" + String.join("、", known))
                        + "。可用 list_config_defs 查编码。");
            }
        }
        return reasons;
    }

    // ── set_condition ───────────────────────────────────────────────────────

    private List<String> validateCondition(JsonNode root) {
        List<String> reasons = new ArrayList<>();
        for (String key : fieldNames(root)) {
            if (!CONDITION_KEYS.contains(key)) {
                reasons.add("不认识的参数 `" + key + "`：set_condition 只接受 defCode / conditions。");
            }
        }
        String defCode = text(root.get("defCode"));
        ConfigDefinition definition = null;
        if (defCode == null || defCode.isBlank()) {
            reasons.add("defCode 必填（要给哪个配置定义设条件）。");
        }
        else {
            definition = findDefinition(defCode.trim());
            if (definition == null) {
                reasons.add("配置定义不存在：" + defCode + "（未下发；可用 list_config_defs 查编码）。");
            }
        }
        JsonNode conditions = root.get("conditions");
        if (conditions == null || conditions.isNull()) {
            reasons.add("conditions 必填（缺失会被前端补成 {}，其语义是“不设条件 = 导出全部”，"
                    + "未下发；要导出全部请不要调用 set_condition）。");
            return reasons;
        }
        if (!conditions.isObject()) {
            reasons.add("conditions 必须是对象（形如 {\"scopeKeys\":[...],\"fields\":[...]}）。");
            return reasons;
        }
        for (String key : fieldNames(conditions)) {
            if (!CONDITION_BODY_KEYS.contains(key)) {
                reasons.add("conditions 不认识的键 `" + key + "`：只接受 scopeKeys / fields。");
            }
        }
        List<String> scopeKeys = validateScopeKeys(conditions.get("scopeKeys"), reasons);
        boolean hasFields = validateFields(conditions.get("fields"), definition, reasons);
        if (scopeKeys.isEmpty() && !hasFields) {
            reasons.add("条件为空：scopeKeys 与 fields 至少要给一项有效条件（空条件等于导出全部数据，"
                    + "不能当作“设置成功”；要导出全部请不要调用 set_condition）。");
        }
        return reasons;
    }

    private List<String> validateScopeKeys(JsonNode scopeKeys, List<String> reasons) {
        List<String> values = new ArrayList<>();
        if (scopeKeys == null || scopeKeys.isNull()) {
            return values;
        }
        if (!scopeKeys.isArray()) {
            reasons.add("conditions.scopeKeys 必须是字符串数组（例如 [\"XN\"]）。");
            return values;
        }
        for (JsonNode item : scopeKeys) {
            String value = item != null && item.isTextual() ? item.asText() : null;
            if (value == null || value.isBlank()) {
                reasons.add("conditions.scopeKeys 的每一项都必须是非空字符串（实际出现：" + item + "）。");
                continue;
            }
            values.add(value.trim());
        }
        return values;
    }

    /** @return 是否至少有一条合法的字段条件（用于"空条件"判定） */
    private boolean validateFields(JsonNode fields, ConfigDefinition definition, List<String> reasons) {
        if (fields == null || fields.isNull()) {
            return false;
        }
        if (!fields.isArray()) {
            reasons.add("conditions.fields 必须是数组（每条含 fieldCode / operator / value）。");
            return false;
        }
        Set<String> fieldCodes = definition == null ? Set.of() : fieldCodesOf(definition);
        Set<String> seenFieldCodes = new LinkedHashSet<>();
        boolean accepted = false;
        int index = 0;
        for (JsonNode field : fields) {
            index += 1;
            if (field == null || !field.isObject()) {
                reasons.add("conditions.fields[" + index + "] 必须是对象。");
                continue;
            }
            boolean fieldOk = true;
            for (String key : fieldNames(field)) {
                if (!FIELD_CONDITION_KEYS.contains(key)) {
                    reasons.add("conditions.fields[" + index + "] 不认识的键 `" + key
                            + "`：只接受 fieldCode / operator / value。");
                    fieldOk = false;
                }
            }
            String fieldCode = text(field.get("fieldCode"));
            if (fieldCode == null || fieldCode.isBlank()) {
                reasons.add("conditions.fields[" + index + "].fieldCode 必填。");
                fieldOk = false;
            }
            else if (!seenFieldCodes.add(fieldCode.trim())) {
                reasons.add("conditions.fields[" + index + "].fieldCode 重复：" + fieldCode.trim()
                        + "（同一字段只能有一条条件；单字段区间用 GTE + LTE 两条，不接受重复字段）。");
                fieldOk = false;
            }
            else if (definition != null && !fieldCodes.contains(fieldCode.trim())) {
                reasons.add("conditions.fields[" + index + "].fieldCode 不属于 " + definition.getCode()
                        + "：" + fieldCode + "（可用字段：" + String.join("、", fieldCodes) + "）。");
                fieldOk = false;
            }
            String operator = text(field.get("operator"));
            if (operator == null || !OPERATORS.contains(operator.trim().toUpperCase())) {
                reasons.add("conditions.fields[" + index + "].operator 只能是 "
                        + "EQ/NE/CONTAINS/LIKE/STARTS_WITH/IN/EMPTY/NOT_EMPTY/GT/GTE/LT/LTE"
                        + "（实际 " + operator + "）。");
                fieldOk = false;
            }
            else if (!validateOperatorValue(field.get("value"), operator.trim().toUpperCase(), index, reasons)) {
                fieldOk = false;
            }
            accepted |= fieldOk;
        }
        return accepted;
    }

    private boolean validateOperatorValue(JsonNode value, String operator, int index, List<String> reasons) {
        if (VALUELESS_OPERATORS.contains(operator)) {
            return true;
        }
        if (value == null || value.isNull()) {
            reasons.add("conditions.fields[" + index + "].value 必填（operator=" + operator + " 需要比较值；"
                    + "判断空值请用 EMPTY / NOT_EMPTY）。");
            return false;
        }
        if ("IN".equals(operator)) {
            if (!value.isArray()) {
                reasons.add("conditions.fields[" + index + "].value 在 operator=IN 时必须是数组。");
                return false;
            }
            if (value.isEmpty()) {
                reasons.add("conditions.fields[" + index + "].value 在 operator=IN 时不能是空数组"
                        + "（空数组在前端压平时会被丢弃，等于“无条件 = 导出全部”；没有可选项请改用 EMPTY / NOT_EMPTY"
                        + " 或不要设条件）。");
                return false;
            }
            for (JsonNode item : value) {
                if (item == null || item.isNull() || (item.isTextual() && item.asText().isBlank())) {
                    reasons.add("conditions.fields[" + index + "].value 在 operator=IN 时每一项都必须是非空字符串"
                            + "（实际出现：" + item + "）。");
                    return false;
                }
            }
            return true;
        }
        String expect = text(value);
        if (expect != null && expect.isBlank()) {
            reasons.add("conditions.fields[" + index + "].value 不能是空串或纯空白（operator=" + operator
                    + " 需要真实比较值；空值会被前端压平丢弃并放大成导出全部；判断空值请用 EMPTY / NOT_EMPTY）。");
            return false;
        }
        return true;
    }

    // ── 设备 ────────────────────────────────────────────────────────────────

    /**
     * 定义存在性（只读）：不存在返回 null。
     *
     * <p>刻意的"读一次注册表"而不是只看格式：未知 defCode 落到前端会表现为"没有这张卡片"
     * 或"设置成功但条件没落库"，两者都是静默失败。
     */
    private ConfigDefinition findDefinition(String code) {
        try {
            return this.definitionService.findByCode(code);
        }
        catch (ResourceNotFoundException ex) {
            return null;
        }
    }

    private boolean definitionExists(String code) {
        return findDefinition(code) != null;
    }

    private static Set<String> fieldCodesOf(ConfigDefinition definition) {
        Set<String> codes = new LinkedHashSet<>();
        List<ConfigField> fields = definition.getFields();
        if (fields != null) {
            for (ConfigField field : fields) {
                if (field != null && field.getCode() != null && !field.getCode().isBlank()) {
                    codes.add(field.getCode());
                }
            }
        }
        return codes;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null && node.isObject()) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
    }

    private static Verdict reject(String message) {
        return Verdict.reject(REJECTED_CODE, message, List.of(message));
    }

}
