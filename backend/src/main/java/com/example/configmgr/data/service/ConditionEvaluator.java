package com.example.configmgr.data.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 查询条件求值器：对动态 JSON 行应用范围过滤与字段级过滤。
 * 导出作业与行数预估共用，保证两处口径一致。
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
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
                return true;
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
