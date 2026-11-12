package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.observe.LogSampling;
import com.example.configmgr.common.BlankText;
import com.example.configmgr.data.service.ConditionOperators;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 向导动作（{@code set_conditions} / {@code select_definitions}）的下发前复核。
 *
 * <p>两个动作都通过前端形成可见、可逆的草案，业务库由后续步骤提交。后端必须在挂起前复核，
 * 防止畸形参数静默放大成全量或落到错误目标。
 *
 * <p>{@code set_conditions} 只接受非空 {@code items} 数组，每项恰好包含 {@code defCode/conditions}，
 * defCode 不得重复。所有项的定义、字段、算子、取值和任务选中关系全部通过才整批下发；任一项失败，
 * 整批零写入。每项显式 {@code conditions={}} 表示该项明确不设筛选；缺失、null 或只有空数组结构均拒绝。
 *
 * <p>遵守 {@link FrontendToolGuard} 的挂起机制契约：拒绝 ⇒ 不挂起、不发 {@code frontend_tool_request}，
 * 只把<b>逐条原因清单</b>回填模型（模型据此改一版再试，而不是白等前端超时）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WizardActionGuard implements FrontendToolGuard {

    /** 本复核器认领的两个向导动作（按模型给出的工具名原文判定，不做形状推断）。 */
    static final Set<String> SUPPORTED = Set.of(AiToolNames.SET_CONDITIONS, AiToolNames.SELECT_DEFINITIONS);

    /** 拒绝码（与 {@code SHEET_ARGUMENTS_REJECTED} / {@code UI_PRIMITIVE_ARGUMENTS_REJECTED} 同族）。 */
    public static final String REJECTED_CODE = "WIZARD_ARGUMENTS_REJECTED";

    private static final Set<String> SELECT_KEYS = Set.of("codes", "defCodes", "mode");

    private static final Set<String> CONDITION_KEYS = Set.of("items");

    private static final Set<String> CONDITION_ITEM_KEYS = Set.of("defCode", "conditions");

    private static final Set<String> CONDITION_BODY_KEYS = Set.of("scopeKeys", "fields");

    private static final Set<String> FIELD_CONDITION_KEYS = Set.of("fieldCode", "operator", "value");

    private static final Set<String> MODES = Set.of("REPLACE", "ADD");

    /**
     * 字段算子白名单 = 前端 {@code ConditionOperator} 与后端 {@code ConditionEvaluator} 的交集
     * （唯一注册定义在 {@link ConditionOperators}，{@link SheetWriteGuard} 与本类共用，不新造 DSL）。
     */
    private static final Set<String> OPERATORS = Set.copyOf(ConditionOperators.NAMES);

    private final DefinitionService definitionService;

    /** 可选任务读口：批量条件必须全部落在本任务已勾选的条目上。 */
    private TaskService taskService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    public void setTaskService(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && SUPPORTED.contains(toolName);
    }

    @Override
    public Verdict inspectArguments(String toolName, String argumentsJson) {
        if (!supports(toolName)) {
            return Verdict.accept();
        }
        if (StringUtils.isBlank(argumentsJson)) {
            return reject("入参不能为空：至少要有 "
                    + (AiToolNames.SELECT_DEFINITIONS.equals(toolName) ? "codes" : "items") + "。");
        }
        JsonNode root = parse(argumentsJson);
        if (root == null) {
            return reject("入参必须是合法 JSON 对象（set_conditions 形如 {\"items\":[...]}）。");
        }
        return decide(toolName, root);
    }

    /** 单工具名复核（测试与显式调用方使用，与框架入口同一实现）。 */
    public Verdict inspectArgumentsFor(String toolName, String argumentsJson) {
        return inspectArguments(toolName, argumentsJson);
    }

    @Override
    public Verdict inspectArguments(String toolName, String argumentsJson, AiContext context) {
        Verdict verdict = inspectArguments(toolName, argumentsJson);
        if (!verdict.accepted() || !AiToolNames.SET_CONDITIONS.equals(toolName)) {
            return verdict;
        }
        return inspectTaskSelection(argumentsJson, context, verdict);
    }

    private Verdict inspectTaskSelection(String argumentsJson, AiContext context, Verdict accepted) {
        if (context == null || context.getTaskId() == null) {
            return taskSelectionFailure(null, "缺少任务上下文或任务绑定，无法证明批量配置项均已选中");
        }
        Long taskId = context.getTaskId();
        if (this.taskService == null) {
            return taskSelectionFailure(taskId, "任务服务不可用，无法证明批量配置项均已选中");
        }
        Task task;
        try {
            task = this.taskService.findById(taskId);
        }
        catch (ResourceNotFoundException ex) {
            return taskSelectionFailure(taskId, "任务不存在，无法证明批量配置项均已选中");
        }
        catch (RuntimeException ex) {
            if (LogSampling.canAcquire("wizard-task-selection-read")) {
                log.warn("could not verify selected task entries; set_conditions rejected the whole batch taskId={}", taskId, ex);
            }
            return taskSelectionFailure(taskId, "任务读取失败，无法证明批量配置项均已选中");
        }
        if (task == null || CollectionUtils.isEmpty(task.getItems())) {
            return taskSelectionFailure(taskId, "任务不存在或没有已选条目，无法证明批量配置项均已选中");
        }
        Set<String> selected = new LinkedHashSet<>();
        for (TaskItem item : task.getItems()) {
            if (item != null && StringUtils.isNotBlank(item.getDefCode())) {
                selected.add(item.getDefCode().trim());
            }
        }
        List<String> missing = missingSelections(parse(argumentsJson), selected);
        if (missing.isEmpty()) {
            return accepted;
        }
        List<String> reasons = missing.stream()
                .map(code -> "配置项 " + code + " 不在任务 #" + taskId + " 的已选条目里（未勾选/未绑定）："
                        + "先 select_definitions 或在向导里勾选后再调用 set_conditions；整批未下发、未写入。")
                .toList();
        return Verdict.reject(REJECTED_CODE, "向导动作入参不合法，未下发前端、未产生任何持久化写入。", reasons);
    }

    private static Verdict taskSelectionFailure(Long taskId, String reason) {
        return Verdict.reject(REJECTED_CODE, "无法证明批量配置项已选中，未下发前端、未产生任何持久化写入。",
                List.of(reason + "（任务 #" + taskId + "）；整批未下发、未写入。"));
    }

    private static List<String> missingSelections(JsonNode root, Set<String> selected) {
        if (root == null || !root.path("items").isArray()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String defCode = text(item.path("defCode"));
            if (defCode != null && !selected.contains(defCode.trim())) {
                missing.add(defCode.trim());
            }
        }
        return missing;
    }

    @Override
    public Verdict inspectResult(String argumentsJson, String resultJson) {
        // 这两个动作的回灌是自由文本（"已设置查询条件（CONFIG_CODE）"这类），没有可核对的数值口径
        // ⇒ 不新增"必须含共 N"之类的形式要求（那只会把一次成功判成失败）。
        return Verdict.accept();
    }

    // ── 分流 ────────────────────────────────────────────────────────────────

    private Verdict decide(String toolName, JsonNode root) {
        List<String> reasons = AiToolNames.SELECT_DEFINITIONS.equals(toolName) ? validateSelect(root)
                : validateCondition(root);
        if (reasons.isEmpty()) {
            return Verdict.accept();
        }
        return Verdict.reject(REJECTED_CODE, "向导动作入参不合法，未下发前端、未产生任何持久化写入。", reasons);
    }

    /** 解析入参原文；不是合法 JSON 对象时返回 {@code null}（调用方给固定文案）。 */
    private JsonNode parse(String argumentsJson) {
        try {
            JsonNode root = this.mapper.readTree(StringUtils.defaultString(argumentsJson));
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
        List<String> codes = validateSelectCodes(codesNode, reasons);
        if (root.hasNonNull("mode") && !MODES.contains(text(root.get("mode")))) {
            reasons.add("mode 只能是 REPLACE 或 ADD（大小写敏感；小写会被前端静默当成 REPLACE，故不接受）：实际 "
                    + root.get("mode") + "。");
        }
        if (reasons.isEmpty() && !codes.isEmpty()) {
            validateKnownDefinitions(codes, reasons);
        }
        return reasons;
    }

    private List<String> validateSelectCodes(JsonNode codesNode, List<String> reasons) {
        List<String> codes = new ArrayList<>();
        if (codesNode == null || codesNode.isNull()) {
            reasons.add("codes 必填（要勾选的配置定义编码列表，不能为空）。");
        }
        else if (!codesNode.isArray()) {
            reasons.add("codes 必须是配置定义编码的数组，例如 [\"CONFIG_CODE\"]。");
        }
        else {
            for (JsonNode item : codesNode) {
                String code = item != null && item.isTextual() ? item.asText() : null;
                if (StringUtils.isBlank(code)) {
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
        return codes;
    }

    private void validateKnownDefinitions(List<String> codes, List<String> reasons) {
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String code : codes) {
            if (isDefinitionPresent(code)) {
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

    // ── set_conditions ──────────────────────────────────────────────────────

    private List<String> validateCondition(JsonNode root) {
        List<String> reasons = new ArrayList<>();
        for (String key : fieldNames(root)) {
            if (!CONDITION_KEYS.contains(key)) {
                reasons.add("不认识的参数 `" + key + "`：set_conditions 只接受 items。");
            }
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            reasons.add("items 必填且必须是非空数组，每项只含 defCode / conditions。");
            return reasons;
        }
        if (items.isEmpty()) {
            reasons.add("items 不能为空数组；整批未下发、未写入。");
            return reasons;
        }
        Set<String> seenDefCodes = new LinkedHashSet<>();
        for (JsonNode item : items) {
            validateConditionItem(item, seenDefCodes, reasons);
        }
        return reasons;
    }

    private void validateConditionItem(JsonNode item, Set<String> seenDefCodes, List<String> reasons) {
        if (item == null || !item.isObject()) {
            reasons.add("items 的每一项都必须是对象，且只含 defCode / conditions。");
            return;
        }
        for (String key : fieldNames(item)) {
            if (!CONDITION_ITEM_KEYS.contains(key)) {
                reasons.add("items 项不认识的键 `" + key + "`：只接受 defCode / conditions。");
            }
        }
        String defCode = text(item.get("defCode"));
        ConfigDefinition definition = null;
        if (StringUtils.isBlank(defCode)) {
            reasons.add("defCode 必填（要给哪个配置定义设条件）。");
        }
        else {
            if (!seenDefCodes.add(defCode.trim())) {
                reasons.add("defCode 重复：" + defCode.trim() + "；整批未下发、未写入。");
            }
            definition = findDefinition(defCode.trim());
            if (definition == null) {
                reasons.add("配置定义不存在：" + defCode + "（整批未下发；可用 list_config_defs 查编码）。");
            }
        }
        validateConditionBody(item.get("conditions"), definition, reasons);
    }

    private void validateConditionBody(JsonNode conditions, ConfigDefinition definition, List<String> reasons) {
        if (conditions == null || conditions.isNull()) {
            reasons.add("conditions 必填（缺失或显式 null 不是“明确不设”；整批未下发）。明确不设请显式传 {}。");
            return;
        }
        if (!conditions.isObject()) {
            reasons.add("conditions 必须是对象（形如 {\"scopeKeys\":[...],\"fields\":[...]}，明确不设传 {}）。");
            return;
        }
        if (conditions.isEmpty()) {
            return;
        }
        for (String key : fieldNames(conditions)) {
            if (!CONDITION_BODY_KEYS.contains(key)) {
                reasons.add("conditions 不认识的键 `" + key + "`：只接受 scopeKeys / fields。");
            }
        }
        List<String> scopeKeys = validateScopeKeys(conditions.get("scopeKeys"), reasons);
        boolean hasFields = hasValidFields(conditions.get("fields"), definition, reasons);
        if (scopeKeys.isEmpty() && !hasFields) {
            reasons.add("条件为空：fields:[] / scopeKeys:[] 有结构但没有可提交的条件（压平后等于导出全部数据），"
                    + "不能当作“设置成功”；要表达明确不设筛选请恰好传空对象 {}。");
        }
    }

    private List<String> validateScopeKeys(JsonNode scopeKeys, List<String> reasons) {
        List<String> values = new ArrayList<>();
        if (scopeKeys == null || scopeKeys.isNull()) {
            return values;
        }
        if (!scopeKeys.isArray()) {
            reasons.add("conditions.scopeKeys 必须是字符串数组（例如 [\"REGION_CODE\"]）。");
            return values;
        }
        for (JsonNode item : scopeKeys) {
            String value = item != null && item.isTextual() ? item.asText() : null;
            if (BlankText.isBlank(value) || BlankText.hasBoundaryWhitespace(value)) {
                reasons.add("conditions.scopeKeys 的每一项都必须是非空字符串且首尾不能有空白（实际出现：" + item + "）。");
                continue;
            }
            values.add(value);
        }
        return values;
    }

    /** @return 是否至少有一条合法的字段条件（用于"空条件"判定） */
    private boolean hasValidFields(JsonNode fields, ConfigDefinition definition, List<String> reasons) {
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
            accepted |= validateField(field, index, fieldCodes, definition, seenFieldCodes, reasons);
        }
        return accepted;
    }

    private boolean validateField(JsonNode field, int index, Set<String> fieldCodes, ConfigDefinition definition,
            Set<String> seenFieldCodes, List<String> reasons) {
        if (field == null || !field.isObject()) {
            reasons.add("conditions.fields[" + index + "] 必须是对象。");
            return false;
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
        if (BlankText.isBlank(fieldCode) || BlankText.hasBoundaryWhitespace(fieldCode)) {
            reasons.add("conditions.fields[" + index + "].fieldCode 必填且首尾不能有空白。");
            fieldOk = false;
        }
        else if (!seenFieldCodes.add(fieldCode)) {
            reasons.add("conditions.fields[" + index + "].fieldCode 重复：" + fieldCode
                    + "（同一字段只能有一条条件；单字段区间用 GTE + LTE 两条，不接受重复字段）。");
            fieldOk = false;
        }
        else if (definition != null && !fieldCodes.contains(fieldCode)) {
            reasons.add("conditions.fields[" + index + "].fieldCode 不属于 " + definition.getCode()
                    + "：" + fieldCode + "（可用字段：" + String.join("、", fieldCodes) + "）。");
            fieldOk = false;
        }
        String operator = text(field.get("operator"));
        if (operator == null || BlankText.hasBoundaryWhitespace(operator)
                || !OPERATORS.contains(StringUtils.upperCase(operator))) {
            reasons.add("conditions.fields[" + index + "].operator 只能是 "
                    + "EQ/NE/CONTAINS/LIKE/STARTS_WITH/IN/EMPTY/NOT_EMPTY/GT/GTE/LT/LTE"
                    + "（实际 " + operator + "）。");
            fieldOk = false;
        }
        else if (!isOperatorValueValid(field.get("value"), StringUtils.upperCase(operator), index, reasons)) {
            fieldOk = false;
        }
        return fieldOk;
    }

    private boolean isOperatorValueValid(JsonNode value, String operator, int index, List<String> reasons) {
        if (!ConditionOperators.takesValue(operator)) {
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
                if (item == null || item.isNull() || (item.isTextual() && BlankText.isBlank(item.asText()))) {
                    reasons.add("conditions.fields[" + index + "].value 在 operator=IN 时每一项都必须是非空字符串"
                            + "（实际出现：" + item + "）。");
                    return false;
                }
            }
            return true;
        }
        if (value.isNumber() || value.isBoolean()) {
            return true;
        }
        String expect = text(value);
        if (BlankText.isBlank(expect)) {
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

    private boolean isDefinitionPresent(String code) {
        return findDefinition(code) != null;
    }

    private static Set<String> fieldCodesOf(ConfigDefinition definition) {
        Set<String> codes = new LinkedHashSet<>();
        List<ConfigField> fields = definition.getFields();
        if (fields != null) {
            for (ConfigField field : fields) {
                if (field != null && StringUtils.isNotBlank(field.getCode())) {
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
