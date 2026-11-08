package com.example.configmgr.data.service;

import com.example.configmgr.common.BlankText;
import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 查询条件求值器：对动态 JSON 行应用范围过滤与字段级过滤。
 * 导出作业与带条件的列表查询共用，保证两处口径一致。
 *
 * <p><b>未知操作符 = 异常，不再静默放行</b>（M1 收尾守卫③，S5.a 待裁决 #3 的裁决落地）：
 * 原实现对未登记的操作符走 {@code default → true}（等于忽略该条件），后果是<b>导出范围比用户预期更宽</b>
 * （数据面风险：用户以为筛掉了，实际全量导出/发布）。现改为抛 {@link IllegalArgumentException}：
 * <ul>
 * <li>列表端点：{@link ConfigDataService#parseCondition} 先用 {@link #validate} 早失败 → 400；</li>
 * <li>导出作业：{@link com.example.configmgr.job.service.ExportJobRunner} 逐配置项捕获 → 该配置项与作业
 *     {@code FAILED}（不产出"更宽"的文件）。</li>
 * </ul>
 */
public final class ConditionEvaluator {

    /**
     * 已登记的操作符（唯一注册定义在 {@link ConditionOperators}；{@link #validate} 与 {@link #isOperatorMatch} 同源）。
     */
    private static final Set<String> OPERATORS = Set.copyOf(ConditionOperators.NAMES);

    private ConditionEvaluator() {
    }

    /**
     * 在原始 JSON 上拒绝会被反序列化 coercion 或 trim 掩盖的非法形状。
     */
    public static void validateShape(JsonNode cond) {
        if (cond == null || cond.isNull() || !cond.isObject()) {
            throw invalidCondition();
        }
        var names = cond.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!"fields".equals(name) && !"scopeKeys".equals(name)) {
                throw invalidCondition();
            }
        }
        validateFieldNodes(cond.get("fields"));
        validateScopeKeyNodes(cond.get("scopeKeys"));
    }

    private static void validateFieldNodes(JsonNode fields) {
        if (fields == null) {
            return;
        }
        if (!fields.isArray() || fields.isEmpty()) {
            throw invalidCondition();
        }
        for (JsonNode field : fields) {
            if (field == null || !field.isObject() || isBlankText(field.get("fieldCode"))
                    || isBlankText(field.get("operator"))) {
                throw invalidCondition();
            }
            String fieldCode = field.get("fieldCode").asText();
            String operator = field.get("operator").asText();
            if (BlankText.hasBoundaryWhitespace(fieldCode) || BlankText.hasBoundaryWhitespace(operator)) {
                throw invalidCondition();
            }
            String op = operator.toUpperCase();
            if (!OPERATORS.contains(op)) {
                throw unknownOperator(op, fieldCode);
            }
            validateValueNode(field.get("value"), op);
        }
    }

    private static void validateValueNode(JsonNode value, String operator) {
        if (!ConditionOperators.takesValue(operator)) {
            return;
        }
        if (value == null || value.isNull()) {
            throw invalidCondition();
        }
        if ("IN".equals(operator)) {
            validateInValueNode(value);
            return;
        }
        if (value.isTextual() && BlankText.isBlank(value.asText())) {
            throw invalidCondition();
        }
    }

    private static void validateInValueNode(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw invalidCondition();
        }
        for (JsonNode item : value) {
            if (item == null || item.isNull() || item.isTextual() && BlankText.isBlank(item.asText())) {
                throw invalidCondition();
            }
        }
    }

    private static void validateScopeKeyNodes(JsonNode scopeKeys) {
        if (scopeKeys == null) {
            return;
        }
        if (!scopeKeys.isArray() || scopeKeys.isEmpty()) {
            throw invalidCondition();
        }
        for (JsonNode key : scopeKeys) {
            if (isBlankText(key) || BlankText.hasBoundaryWhitespace(key.asText())) {
                throw invalidCondition();
            }
        }
    }

    private static boolean isBlankText(JsonNode value) {
        return value == null || !value.isTextual() || BlankText.isBlank(value.asText());
    }

    /**
     * 操作符白名单校验（与行数据无关，故行数为 0 时同样能失败 —— 列表端点对空结果集也必须给 400，
     * 而不是"恰好没行所以没报错"）。
     *
     * @throws IllegalArgumentException 出现未登记的操作符或非法条件结构
     */
    public static void validate(QueryCondition cond) {
        if (cond == null) {
            throw invalidCondition();
        }
        validateFieldConditions(cond.getFields());
        validateScopeKeys(cond.getScopeKeys());
    }

    private static void validateFieldConditions(List<QueryCondition.FieldCondition> fields) {
        if (fields == null) {
            return;
        }
        if (fields.isEmpty()) {
            throw invalidCondition();
        }
        for (QueryCondition.FieldCondition fc : fields) {
            if (fc == null || BlankText.isBlank(fc.getFieldCode()) || BlankText.isBlank(fc.getOperator())
                    || BlankText.hasBoundaryWhitespace(fc.getFieldCode()) || BlankText.hasBoundaryWhitespace(fc.getOperator())) {
                throw invalidCondition();
            }
            String op = fc.getOperator().toUpperCase();
            if (!OPERATORS.contains(op)) {
                throw unknownOperator(op, fc.getFieldCode());
            }
            validateValue(fc.getValue(), op);
        }
    }

    private static void validateValue(Object value, String operator) {
        if (!ConditionOperators.takesValue(operator)) {
            return;
        }
        if (value == null) {
            throw invalidCondition();
        }
        if ("IN".equals(operator)) {
            validateInValue(value);
            return;
        }
        if (value instanceof String text && BlankText.isBlank(text)) {
            throw invalidCondition();
        }
    }

    private static void validateInValue(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            throw invalidCondition();
        }
        for (Object item : items) {
            if (item == null || item instanceof String text && BlankText.isBlank(text)) {
                throw invalidCondition();
            }
        }
    }

    private static void validateScopeKeys(List<String> scopeKeys) {
        if (scopeKeys == null) {
            return;
        }
        if (scopeKeys.isEmpty()) {
            throw invalidCondition();
        }
        for (String key : scopeKeys) {
            if (BlankText.isBlank(key) || BlankText.hasBoundaryWhitespace(key)) {
                throw invalidCondition();
            }
        }
    }

    private static CodedIllegalArgumentException invalidCondition() {
        return new CodedIllegalArgumentException(ErrorCodes.CONDITION_JSON_INVALID,
                "Invalid query condition structure");
    }

    private static IllegalArgumentException unknownOperator(String op, String fieldCode) {
        return new CodedIllegalArgumentException(ErrorCodes.CONDITION_OPERATOR_UNKNOWN, "Unknown query condition operator: " + op
                + " (field " + fieldCode + "); registered operators: " + OPERATORS);
    }

    public static boolean matches(Map<String, Object> row, QueryCondition cond) {
        if (cond == null) {
            return true;
        }

        // 1. 范围过滤（地区/项目）
        if (CollectionUtils.isNotEmpty(cond.getScopeKeys())) {
            Object scopeVal = ObjectUtils.getIfNull(
                    row.get("regionCode"), () -> row.get("projectCode"));
            if (scopeVal == null || !cond.getScopeKeys().contains(scopeVal.toString())) {
                return false;
            }
        }

        // 2. 字段级过滤（AND）
        if (cond.getFields() != null) {
            for (QueryCondition.FieldCondition fc : cond.getFields()) {
                if (StringUtils.isBlank(fc.getOperator())) {
                    continue;
                }
                Object val = row.get(fc.getFieldCode());
                if (!isOperatorMatch(val, fc)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isOperatorMatch(Object val, QueryCondition.FieldCondition fc) {
        String op = fc.getOperator().toUpperCase();
        String s = Objects.toString(val, null);
        String expect = Objects.toString(fc.getValue(), null);

        return switch (op) {
            case "EMPTY" -> StringUtils.isBlank(s);
            case "NOT_EMPTY" -> StringUtils.isNotBlank(s);
            case "EQ" -> s != null && s.equals(expect);
            case "NE" -> s == null || !s.equals(expect);
            case "CONTAINS", "LIKE" -> StringUtils.contains(s, expect);
            case "STARTS_WITH" -> StringUtils.startsWith(s, expect);
            case "IN" -> isInMatch(s, expect, fc.getValue());
            case "GT", "GTE", "LT", "LTE" -> isComparisonMatch(s, expect, op);
            default -> throw unknownOperator(op, fc.getFieldCode());
        };
    }

    private static boolean isInMatch(String actual, String expect, Object expectedValue) {
        if (actual == null || expectedValue == null) {
            return false;
        }
        if (expectedValue instanceof List<?> list) {
            return list.stream().anyMatch(x -> x != null && x.toString().equals(actual));
        }
        return actual.equals(expect);
    }

    private static boolean isComparisonMatch(String actual, String expect, String op) {
        if (actual == null || expect == null) {
            return false;
        }
        int cmp = compareValues(actual, expect);
        return switch (op) {
            case "GT" -> cmp > 0;
            case "GTE" -> cmp >= 0;
            case "LT" -> cmp < 0;
            default -> cmp <= 0;
        };
    }

    /**
     * 日期优先、数值次之、字符串兜底的比较
     */
    private static int compareValues(String a, String b) {
        try {
            return LocalDate.parse(a).compareTo(LocalDate.parse(b));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return a.compareTo(b);
    }
}
