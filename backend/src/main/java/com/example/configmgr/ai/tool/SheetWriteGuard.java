package com.example.configmgr.ai.tool;

import com.example.configmgr.data.service.ConditionOperators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * AI 表格写工具的下发前 / 回灌前复核（设计稿 §3.4，DC-15 扩展点实现）。
 *
 * <h2>为什么需要</h2>
 * 表格写工具与生成式表单同属 {@link ToolMeta.Channel#FRONTEND}：后端方法体是哨兵桩，
 * 模型给的参数一路直达前端。若后端不设闸门，非法入参（未知算子、超 5 列、commit=true 夹带
 * “猜业务事实”的规则、非法正则）会直接落到浏览器侧，前端只能事后拒绝 —— 模型也拿不到
 * “改哪一处才能过闸”的清单。本类按 {@link FrontendToolGuard} 的扩展点自认领，挂起机制一行不改。
 *
 * <h2>六项入参校验</h2>
 * <ol>
 * <li>{@code defCode} 非空；{@code scope} 在枚举内；</li>
 * <li>{@code predicate} 只用既有 11 个算子（`FieldQueryCondition`，不新造 DSL）；</li>
 * <li>{@code columnCodes} 1..5 列且每项非空；</li>
 * <li>{@code match} / {@code rules} 在枚举内，{@code wildcard} 与 {@code regex} 互斥，正则必须可编译；</li>
 * <li>{@code commit=true} 时 {@code rules ⊆ {trim,case,width}}（其余规则会猜业务事实，只能出建议）；</li>
 * <li>{@code preview} 在枚举内（count / sample-diff / full-diff）。</li>
 * </ol>
 *
 * <h2>回灌复核</h2>
 * 写回执是带 kind、text、实际 cells/rows 与计划 cells/rows 的 JSON；applied 的实际数量必须与计划一致，
 * failed 可携带真实的部分写入数，cancelled 实际计数必须为 0。kind 与 outcome 必须一致，
 * text 必填但只供阅读，不参与业务判定；commit=false 的归一化建议不是写回执。
 */
@Component
public class SheetWriteGuard implements FrontendToolGuard {

    /** 本复核器认领的写工具（撤销只有 defCode，仍按同一闸门走一遍；名字见 {@link AiToolNames}）。 */
    public static final Set<String> SUPPORTED = Set.of(AiToolNames.REPLACE_VALUES, AiToolNames.UPDATE_ROWS,
            AiToolNames.NORMALIZE_COLUMN, AiToolNames.UNDO_LAST_SHEET_EDIT);

    private static final Set<String> SCOPES = Set.of("selection", "sheet", "columns", "rows", "predicate");

    private static final Set<String> OPERATORS = Set.copyOf(ConditionOperators.NAMES);

    private static final Set<String> MATCH_MODES = Set.of("wholeCell", "contains", "prefix", "wildcard", "regex");

    private static final Set<String> PREVIEWS = Set.of("count", "sample-diff", "full-diff");

    private static final Set<String> WRITE_MODES = Set.of("literal", "fillDown", "fillSeries", "template");

    private static final Set<String> RULES = Set.of("trim", "case", "width", "space", "number", "date");

    private static final Set<String> EXECUTABLE_RULES = Set.of("trim", "case", "width");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String toolName) {
        return SUPPORTED.contains(toolName);
    }

    /**
     * 扩展点入口（框架传入真名，工具安全边界批起）：**按工具名声明的分支校验**，
     * 不再靠入参形状反推（少一个必填键会被"错校验"放行的窄缝由此关闭）。
     */
    @Override
    public Verdict inspectArguments(String toolName, String argumentsJson) {
        if (!supports(toolName)) {
            return Verdict.accept();
        }
        JsonNode root;
        try {
            root = mapper.readTree(StringUtils.defaultString(argumentsJson));
        }
        catch (Exception error) {
            return reject("入参不是合法 JSON。");
        }
        if (root == null || !root.isObject()) {
            return reject("入参必须是对象。");
        }
        return inspectFor(toolName, root);
    }

    private Verdict inspectFor(String toolName, JsonNode root) {
        List<String> reasons = new ArrayList<>();
        String defCode = text(root.get("defCode"));
        if (StringUtils.isBlank(defCode)) {
            reasons.add("defCode 必填（要操作哪个配置的表格）。");
        }
        if ("undo_last_sheet_edit".equals(toolName)) {
            return inspectUndo(root, reasons);
        }
        String scope = text(root.get("scope"));
        if (StringUtils.isNotBlank(scope) && !SCOPES.contains(scope)) {
            reasons.add("scope 只能是 selection/sheet/columns/rows/predicate，实际为 " + scope + "。");
        }
        checkPredicate(root.get("predicate"), reasons);
        if ("replace_values".equals(toolName)) {
            checkColumns(root.get("columnCodes"), reasons);
            checkMatch(root, reasons);
            String search = text(root.get("search"));
            if (StringUtils.isEmpty(search)) {
                reasons.add("search 必填且不能为空。");
            }
            checkPreview(root.get("preview"), reasons);
        }
        else if ("update_rows".equals(toolName)) {
            checkSet(root.get("set"), reasons);
            boolean predicateScope = "predicate".equals(text(root.get("scope")))
                    || StringUtils.isBlank(text(root.get("scope")));
            if (predicateScope && !hasFields(root.get("predicate"))) {
                reasons.add("scope=predicate（默认）时必须给 predicate（字段条件），不猜条件。");
            }
            checkPreview(root.get("preview"), reasons);
        }
        else if ("normalize_column".equals(toolName)) {
            checkColumns(root.get("columnCodes"), reasons);
            checkRules(root, reasons);
            checkPreview(root.get("preview"), reasons);
        }
        return reasons.isEmpty() ? Verdict.accept()
                : Verdict.reject("SHEET_ARGUMENTS_REJECTED", "表格写工具入参不合法，未下发前端、未挂起。", reasons);
    }

    private static Verdict inspectUndo(JsonNode root, List<String> reasons) {
        // 撤销只认 defCode：携带写工具参数说明工具名用错，不得静默放行。
        for (String key : List.of("set", "search", "rules", "columnCodes", "commit", "match", "predicate",
                "scope", "preview")) {
            if (root.has(key)) {
                reasons.add("undo_last_sheet_edit 只接受 defCode，不接受 `" + key
                        + "`（本次未下发；要改表格请用 replace_values / update_rows / normalize_column）。");
                break;
            }
        }
        return reasons.isEmpty() ? Verdict.accept()
                : Verdict.reject("SHEET_ARGUMENTS_REJECTED", "表格写工具入参不合法，未下发前端、未挂起。", reasons);
    }

    private static final Set<String> RESULT_KINDS = Set.of("applied", "failed", "cancelled");

    @Override
    public Verdict inspectResult(String argumentsJson, String resultJson) {
        return inspectResult(null, null, argumentsJson, resultJson, null, false);
    }

    @Override
    public Verdict inspectResult(String runId, String toolCallId, String argumentsJson, String resultJson,
                                 String outcome, boolean cancelled) {
        if (isNormalizeSuggestion(argumentsJson)) {
            return Verdict.accept();
        }
        JsonNode root;
        try {
            root = mapper.readTree(StringUtils.defaultString(resultJson));
        }
        catch (Exception error) {
            return rejectResult(List.of("result 必须是合法 JSON 对象。"));
        }
        if (root == null || !root.isObject()) {
            return rejectResult(List.of("result 必须是合法 JSON 对象。"));
        }
        List<String> reasons = new ArrayList<>();
        String kind = text(root.get("kind"));
        if (kind == null || !RESULT_KINDS.contains(kind)) {
            reasons.add("kind 只能是 applied/failed/cancelled。");
        }
        checkText(root.get("text"), reasons);
        checkOutcome(kind, outcome, cancelled, reasons);
        checkCounts(root, kind, reasons);
        return reasons.isEmpty() ? Verdict.accept() : rejectResult(reasons);
    }

    private static void checkText(JsonNode text, List<String> reasons) {
        if (text == null || !text.isTextual() || StringUtils.isBlank(text.textValue())) {
            reasons.add("text 必填且必须是非空字符串。");
        }
    }

    private static void checkOutcome(String kind, String outcome, boolean cancelled, List<String> reasons) {
        if ("cancelled".equals(kind)) {
            if (!cancelled) {
                reasons.add("kind=cancelled 必须走 cancelled=true 的真实取消语义。");
            }
            return;
        }
        if (cancelled) {
            reasons.add("cancelled=true 时 kind 必须为 cancelled。");
            return;
        }
        if ("applied".equals(kind) && !ToolOutcome.OK.wire().equals(outcome)) {
            reasons.add("kind=applied 时 outcome 必须为 ok。");
        }
        if ("failed".equals(kind) && !ToolOutcome.FAILED.wire().equals(outcome)
                && !ToolOutcome.UNKNOWN.wire().equals(outcome)) {
            reasons.add("kind=failed 时 outcome 必须为 failed；恢复不确定只允许明确 unknown。");
        }
    }

    private static void checkCounts(JsonNode root, String kind, List<String> reasons) {
        Integer cells = resultCount(root.get("cells"), "cells", reasons);
        Integer rows = resultCount(root.get("rows"), "rows", reasons);
        JsonNode plan = root.get("plan");
        if (plan == null || !plan.isObject()) {
            reasons.add("plan 必须是包含 cells/rows 的对象。");
            return;
        }
        Integer planCells = resultCount(plan.get("cells"), "plan.cells", reasons);
        Integer planRows = resultCount(plan.get("rows"), "plan.rows", reasons);
        if (!reasons.isEmpty()) {
            return;
        }
        checkCountPair(cells, rows, "cells/rows", reasons);
        checkCountPair(planCells, planRows, "plan.cells/plan.rows", reasons);
        checkNotAbovePlan(cells, planCells, "cells", reasons);
        checkNotAbovePlan(rows, planRows, "rows", reasons);
        if ("cancelled".equals(kind) && (cells > 0 || rows > 0)) {
            reasons.add("cancelled 的实际 cells/rows 必须为 0。");
        }
        if ("applied".equals(kind)) {
            checkAppliedCount(cells, planCells, "cells", reasons);
            checkAppliedCount(rows, planRows, "rows", reasons);
        }
    }

    private static void checkCountPair(int cells, int rows, String field, List<String> reasons) {
        if (cells < rows) {
            reasons.add(field + " 不可能满足 cells < rows（每个命中行至少一格）。");
        }
        if ((cells == 0) != (rows == 0)) {
            reasons.add(field + " 的 0 格与非 0 行（或非 0 格与 0 行）不可能同时成立。");
        }
    }

    private static void checkNotAbovePlan(int actual, int planned, String field, List<String> reasons) {
        if (actual > planned) {
            reasons.add("实际 " + field + " 不得超过计划（实际 " + actual + "，计划 " + planned + "）。");
        }
    }

    private boolean isNormalizeSuggestion(String argumentsJson) {
        try {
            JsonNode arguments = mapper.readTree(StringUtils.defaultString(argumentsJson));
            return arguments != null && arguments.isObject() && arguments.has("rules")
                    && !arguments.has("search") && !arguments.has("set")
                    && !arguments.path("commit").asBoolean(false);
        }
        catch (Exception error) {
            return false;
        }
    }

    private static Integer resultCount(JsonNode node, String field, List<String> reasons) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < 0) {
            reasons.add(field + " 必须是非负整数。");
            return null;
        }
        return node.asInt();
    }

    private static void checkAppliedCount(Integer actual, Integer planned, String field, List<String> reasons) {
        if (actual.intValue() != planned.intValue()) {
            reasons.add("applied 的 " + field + " 必须等于计划数量（实际 " + actual + "，计划 " + planned + "）。");
        }
    }

    private static Verdict rejectResult(List<String> reasons) {
        return Verdict.reject("SHEET_RESULT_REJECTED", "表格写回执结构或计数不合法，拒绝消费挂起。", reasons);
    }

    private void checkPredicate(JsonNode predicate, List<String> reasons) {
        if (predicate == null || predicate.isNull()) {
            return;
        }
        if (!predicate.isObject()) {
            reasons.add("predicate 必须是对象（FieldQueryCondition）。");
            return;
        }
        JsonNode fields = predicate.get("fields");
        if (fields == null || fields.isNull()) {
            return;
        }
        if (!fields.isArray()) {
            reasons.add("predicate.fields 必须是数组。");
            return;
        }
        int index = 0;
        for (JsonNode field : fields) {
            index += 1;
            if (!field.isObject()) {
                reasons.add("predicate.fields[" + index + "] 必须是对象。");
                continue;
            }
            String fieldCode = text(field.get("fieldCode"));
            if (StringUtils.isBlank(fieldCode)) {
                reasons.add("predicate.fields[" + index + "].fieldCode 必填。");
            }
            String operator = text(field.get("operator"));
            if (operator == null || !OPERATORS.contains(StringUtils.upperCase(operator))) {
                reasons.add("predicate.fields[" + index + "].operator 不在 11 个既有算子内（实际 " + operator + "）。");
                continue;
            }
            if ("IN".equalsIgnoreCase(operator) && field.has("value") && !field.get("value").isArray()) {
                reasons.add("predicate.fields[" + index + "].value 在 IN 时必须是数组。");
            }
        }
    }

    private void checkColumns(JsonNode columns, List<String> reasons) {
        if (columns == null || !columns.isArray() || columns.isEmpty()) {
            reasons.add("columnCodes 必填（1..5 个字段编码）。");
            return;
        }
        if (columns.size() > 5) {
            reasons.add("columnCodes 最多 5 列，实际 " + columns.size() + " 列。");
        }
        for (JsonNode column : columns) {
            if (StringUtils.isBlank(text(column))) {
                reasons.add("columnCodes 的每一项都必须是非空字段编码。");
                break;
            }
        }
    }

    private void checkMatch(JsonNode root, List<String> reasons) {
        JsonNode match = root.get("match");
        String value = text(match);
        boolean wildcard = false;
        boolean regex = false;
        if (match != null && match.isArray()) {
            for (JsonNode item : match) {
                String mode = text(item);
                wildcard |= "wildcard".equals(mode);
                regex |= "regex".equals(mode);
                if (mode != null && !MATCH_MODES.contains(mode)) {
                    reasons.add("match 只支持 wholeCell/contains/prefix/wildcard/regex，实际为 " + mode + "。");
                }
            }
            if (match.size() != 1) {
                reasons.add("match 只能给一个匹配方式（数组形态只允许单元素）。");
            }
        }
        else if (StringUtils.isNotBlank(value) && !MATCH_MODES.contains(value)) {
            reasons.add("match 只支持 wholeCell/contains/prefix/wildcard/regex，实际为 " + value + "。");
        }
        else {
            wildcard = "wildcard".equals(value);
            regex = "regex".equals(value);
        }
        if (wildcard && regex) {
            reasons.add("match 不能同时给 wildcard 与 regex（两者互斥，语义不确定）。");
        }
        if (regex) {
            String search = text(root.get("search"));
            if (StringUtils.isEmpty(search)) {
                reasons.add("match=regex 时 search（正则原文）必填。");
            }
            else {
                try {
                    Pattern.compile(search);
                }
                catch (PatternSyntaxException error) {
                    reasons.add("match=regex 的 search 不是合法正则：" + error.getDescription());
                }
            }
        }
    }

    private void checkSet(JsonNode set, List<String> reasons) {
        if (set == null || !set.isArray() || set.isEmpty()) {
            reasons.add("set 必填（1..5 条 columnCode + mode）。");
            return;
        }
        if (set.size() > 5) {
            reasons.add("set 最多 5 条，实际 " + set.size() + " 条。");
        }
        int index = 0;
        for (JsonNode item : set) {
            index += 1;
            if (!item.isObject()) {
                reasons.add("set[" + index + "] 必须是对象。");
                continue;
            }
            String columnCode = text(item.get("columnCode"));
            if (StringUtils.isBlank(columnCode)) {
                reasons.add("set[" + index + "].columnCode 必填。");
            }
            String mode = text(item.get("mode"));
            if (StringUtils.isNotBlank(mode) && !WRITE_MODES.contains(mode)) {
                reasons.add("set[" + index + "].mode 只能是 literal/fillDown/fillSeries/template，实际为 " + mode + "。");
            }
        }
    }

    private void checkRules(JsonNode root, List<String> reasons) {
        JsonNode rules = root.get("rules");
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            reasons.add("rules 必填（1..3 条）。");
            return;
        }
        if (rules.size() > 3) {
            reasons.add("rules 最多 3 条，实际 " + rules.size() + " 条。");
        }
        boolean hasCase = false;
        for (JsonNode ruleNode : rules) {
            String rule = text(ruleNode);
            if (rule == null || !RULES.contains(rule)) {
                reasons.add("规则 " + rule + " 不在支持范围（trim/case/width/space/number/date）。");
                continue;
            }
            hasCase |= "case".equals(rule);
        }
        boolean commit = root.path("commit").asBoolean(false);
        if (commit) {
            for (JsonNode ruleNode : rules) {
                String rule = text(ruleNode);
                if (rule != null && !EXECUTABLE_RULES.contains(rule)) {
                    reasons.add("commit=true 只允许 trim / case / width；" + rule + " 只能出建议（commit=false）。");
                }
            }
        }
        String caseMode = text(root.get("caseMode"));
        if (hasCase && caseMode != null && !"upper".equals(caseMode) && !"lower".equals(caseMode)) {
            reasons.add("rule=case 时 caseMode 只能是 upper 或 lower。");
        }
    }

    private void checkPreview(JsonNode preview, List<String> reasons) {
        String value = text(preview);
        if (StringUtils.isNotBlank(value) && !PREVIEWS.contains(value)) {
            reasons.add("preview 只能是 count/sample-diff/full-diff，实际为 " + value + "。");
        }
    }

    private static boolean hasFields(JsonNode predicate) {
        return predicate != null && predicate.isObject() && predicate.has("fields")
                && predicate.get("fields").isArray() && !predicate.get("fields").isEmpty();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Verdict reject(String message) {
        return Verdict.reject("SHEET_ARGUMENTS_REJECTED", message, List.of(message));
    }

}
