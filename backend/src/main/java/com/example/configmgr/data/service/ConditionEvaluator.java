package com.example.configmgr.data.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询条件求值器：对动态 JSON 行应用范围过滤与字段级过滤。
 * 导出作业与行数预估共用，保证两处口径一致。
 *
 * <p><b>未知操作符 = 异常，不再静默放行</b>（M1 收尾守卫③，S5.a 待裁决 #3 的裁决落地）：
 * 原实现对未登记的操作符走 {@code default → true}（等于忽略该条件），后果是<b>导出范围比用户预期更宽</b>
 * （数据面风险：用户以为筛掉了，实际全量导出/发布）。现改为抛 {@link IllegalArgumentException}：
 * <ul>
 * <li>列表/计数端点：{@link ConfigDataService#parseCondition} 先用 {@link #validate} 早失败 → 400；</li>
 * <li>导出作业：{@link com.example.configmgr.job.service.ExportJobRunner} 逐配置项捕获 → 该配置项与作业
 *     {@code FAILED}（不产出"更宽"的文件）。</li>
 * </ul>
 */
public final class ConditionEvaluator {

    /** 已登记的操作符（{@link #validate} 与 {@link #matchesOp} 同源，杜绝两处口径漂移）。 */
    private static final Set<String> OPERATORS = Set.of(
            "EMPTY", "NOT_EMPTY", "EQ", "NE", "CONTAINS", "LIKE", "STARTS_WITH", "IN", "GT", "GTE", "LT", "LTE");

    private ConditionEvaluator() {
    }

    /**
     * 操作符白名单校验（与行数据无关，故行数为 0 时同样能失败 —— 列表端点对空结果集也必须给 400，
     * 而不是"恰好没行所以没报错"）。
     *
     * @throws IllegalArgumentException 出现未登记的操作符
     */
    public static void validate(QueryCondition cond) {
        if (cond == null || cond.getFields() == null) {
            return;
        }
        for (QueryCondition.FieldCondition fc : cond.getFields()) {
            if (fc.getOperator() == null || fc.getOperator().isBlank()) {
                continue; // 无操作符 = 该字段不参与过滤（前端"用户未填即忽略"的既有口径）
            }
            String op = fc.getOperator().toUpperCase();
            if (!OPERATORS.contains(op)) {
                throw unknownOperator(op, fc.getFieldCode());
            }
        }
    }

    private static IllegalArgumentException unknownOperator(String op, String fieldCode) {
        return new IllegalArgumentException("未知的查询条件操作符: " + op
                + "（字段 " + fieldCode + "），已登记的操作符: " + OPERATORS);
    }

    public static boolean matches(Map<String, Object> row, QueryCondition cond) {
        if (cond == null) {
            return true;
        }

        // 1. 范围过滤（地区/项目）
        if (cond.getScopeKeys() != null && !cond.getScopeKeys().isEmpty()) {
            Object scopeVal = row.get("regionCode") != null ? row.get("regionCode") : row.get("projectCode");
            if (scopeVal == null || !cond.getScopeKeys().contains(scopeVal.toString())) {
                return false;
            }
        }

        // 2. 字段级过滤（AND）
        if (cond.getFields() != null) {
            for (QueryCondition.FieldCondition fc : cond.getFields()) {
                if (fc.getOperator() == null || fc.getOperator().isBlank()) {
                    continue;
                }
                Object val = row.get(fc.getFieldCode());
                if (!matchesOp(val, fc)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesOp(Object val, QueryCondition.FieldCondition fc) {
        String op = fc.getOperator().toUpperCase();
        String s = val == null ? null : val.toString();
        String expect = fc.getValue() == null ? null : fc.getValue().toString();

        switch (op) {
            case "EMPTY":
                return s == null || s.isBlank();
            case "NOT_EMPTY":
                return s != null && !s.isBlank();
            case "EQ":
                return s != null && s.equals(expect);
            case "NE":
                return s == null || !s.equals(expect);
            case "CONTAINS":
            case "LIKE": // 文本包含（S4.4b P2：以 LIKE 之名的文本模糊口径，与 CONTAINS 等价）
                return s != null && expect != null && s.contains(expect);
            case "STARTS_WITH":
                return s != null && expect != null && s.startsWith(expect);
            case "IN": {
                if (s == null || fc.getValue() == null) {
                    return false;
                }
                if (fc.getValue() instanceof List<?> list) {
                    return list.stream().anyMatch(x -> x != null && x.toString().equals(s));
                }
                return s.equals(expect);
            }
            case "GT":
            case "GTE":
            case "LT":
            case "LTE": {
                if (s == null || expect == null) {
                    return false;
                }
                int cmp = compareValues(s, expect);
                return switch (op) {
                    case "GT" -> cmp > 0;
                    case "GTE" -> cmp >= 0;
                    case "LT" -> cmp < 0;
                    default -> cmp <= 0;
                };
            }
            default:
                // M1 收尾守卫③：未知操作符抛异常（原为 return true = 静默放宽，属数据面风险）
                throw unknownOperator(op, fc.getFieldCode());
        }
    }

    /** 日期优先、数值次之、字符串兜底的比较 */
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
