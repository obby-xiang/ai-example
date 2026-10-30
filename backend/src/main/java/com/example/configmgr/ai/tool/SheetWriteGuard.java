package com.example.configmgr.ai.tool;

import com.example.configmgr.data.service.ConditionOperators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 成功文本必须带“共 N”尾行（设计稿 §4.2 可断言约定）；取消 / 预览失效 / 硬上限拒绝三类终态
 * 原样放行 —— 它们本身就是如实结局，不能被当成“结果不合法”而无限重试。
 */
@Component
public class SheetWriteGuard implements FrontendToolGuard {

    /** 本复核器认领的写工具（撤销只有 defCode，仍按同一闸门走一遍；名字见 {@link AiToolNames}）。 */
    static final Set<String> SUPPORTED = Set.of(AiToolNames.REPLACE_VALUES, AiToolNames.UPDATE_ROWS,
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
            root = mapper.readTree(argumentsJson == null ? "" : argumentsJson);
        }
        catch (Exception error) {
            return reject("入参不是合法 JSON：" + error.getMessage());
        }
        if (root == null || !root.isObject()) {
            return reject("入参必须是对象。");
        }
        return inspectFor(toolName, root);
    }

    /**
     * 兼容入口（无工具名的旧调用方）：按入参形状推断细分工具后统一校验。
     *
     * <p>`search` ⇒ replace_values、`set` ⇒ update_rows、`rules` ⇒ normalize_column、其余 ⇒ 撤销
     * （只有 defCode）。形状推断是安全的：三者的必填字段互斥，且真正的事实源是后续的逐项校验。
     * <b>框架路径不走这里</b>（{@code SpToolCallingManager} 调带工具名的重载）。
     */
    @Override
    public Verdict inspectArguments(String argumentsJson) {
        JsonNode root;
        try {
            root = mapper.readTree(argumentsJson == null ? "" : argumentsJson);
        }
        catch (Exception error) {
            return reject("入参不是合法 JSON：" + error.getMessage());
        }
        if (root == null || !root.isObject()) {
            return reject("入参必须是对象。");
        }
        String toolName = root.has("search") ? "replace_values"
                : root.has("set") ? "update_rows"
                : root.has("rules") ? "normalize_column"
                : "undo_last_sheet_edit";
        return inspectFor(toolName, root);
    }

    /** 按工具名细分校验（测试与显式调用方使用；不接受未知工具名）。 */
    public Verdict inspectArgumentsFor(String toolName, String argumentsJson) {
        return inspectArguments(toolName, argumentsJson);
    }

    private Verdict inspectFor(String toolName, JsonNode root) {
        List<String> reasons = new ArrayList<>();
        String defCode = text(root.get("defCode"));
        if (defCode == null || defCode.isBlank()) {
            reasons.add("defCode 必填（要操作哪个配置的表格）。");
        }
        if ("undo_last_sheet_edit".equals(toolName)) {
            // 撤销只认 defCode：带了写工具的参数说明工具名用错（或模型在猜）——
            // 旧实现会把它当"撤销"静默放行，随后前端才拒，属"闸门放行非法入参"的窄缝。
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
        String scope = text(root.get("scope"));
        if (scope != null && !scope.isBlank() && !SCOPES.contains(scope)) {
            reasons.add("scope 只能是 selection/sheet/columns/rows/predicate，实际为 " + scope + "。");
        }
        checkPredicate(root.get("predicate"), reasons);
        if ("replace_values".equals(toolName)) {
            checkColumns(root.get("columnCodes"), reasons);
            checkMatch(root, reasons);
            String search = text(root.get("search"));
            if (search == null || search.isEmpty()) {
                reasons.add("search 必填且不能为空。");
            }
            checkPreview(root.get("preview"), reasons);
        }
        else if ("update_rows".equals(toolName)) {
            checkSet(root.get("set"), reasons);
            boolean predicateScope = "predicate".equals(text(root.get("scope")))
                    || text(root.get("scope")) == null || text(root.get("scope")).isBlank();
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

    @Override
    public Verdict inspectResult(String argumentsJson, String resultJson) {
        String result = resultJson == null ? "" : resultJson;
        if (result.isBlank()) {
            return Verdict.reject("SHEET_RESULT_REJECTED", "回灌结果为空，拒绝消费挂起。",
                    List.of("result 不能为空。"));
        }
        if (result.startsWith("已应用") && !result.contains("共 ")) {
            return Verdict.reject("SHEET_RESULT_REJECTED", "成功回灌缺少“共 N”尾行（尺寸口径不可核对）。",
                    List.of("已应用的回灌文本必须含“共 N 格”。"));
        }
        return Verdict.accept();
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
            if (fieldCode == null || fieldCode.isBlank()) {
                reasons.add("predicate.fields[" + index + "].fieldCode 必填。");
            }
            String operator = text(field.get("operator"));
            if (operator == null || !OPERATORS.contains(operator.toUpperCase())) {
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
            if (text(column) == null || text(column).isBlank()) {
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
        else if (value != null && !value.isBlank() && !MATCH_MODES.contains(value)) {
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
            if (search == null || search.isEmpty()) {
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
            if (columnCode == null || columnCode.isBlank()) {
                reasons.add("set[" + index + "].columnCode 必填。");
            }
            String mode = text(item.get("mode"));
            if (mode != null && !mode.isBlank() && !WRITE_MODES.contains(mode)) {
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
        if (value != null && !value.isBlank() && !PREVIEWS.contains(value)) {
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
