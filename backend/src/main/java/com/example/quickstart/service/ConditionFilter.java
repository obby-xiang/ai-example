package com.example.quickstart.service;

import com.example.quickstart.dto.Condition;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 查询条件在 Java 内存中对 data 过滤（数据量小）。
 * 支持 EQ/NE/LIKE/GT/GE/LT/LE/BETWEEN/IN；数值可解析时按数值比较，否则按字符串比较。
 */
public final class ConditionFilter {

    private ConditionFilter() {
    }

    public static boolean matches(Map<String, Object> row, List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Condition c : conditions) {
            if (c == null || c.field() == null || c.op() == null) {
                continue;
            }
            if (!eval(row.get(c.field()), c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean eval(Object actual, Condition c) {
        String op = c.op().trim().toUpperCase();
        return switch (op) {
            case "EQ" -> equalsValue(actual, c.value());
            case "NE" -> !equalsValue(actual, c.value());
            case "LIKE" -> like(actual == null ? null : String.valueOf(actual),
                    c.value() == null ? "" : String.valueOf(c.value()));
            case "GT" -> actual != null && compareValues(actual, c.value()) > 0;
            case "GE" -> actual != null && compareValues(actual, c.value()) >= 0;
            case "LT" -> actual != null && compareValues(actual, c.value()) < 0;
            case "LE" -> actual != null && compareValues(actual, c.value()) <= 0;
            case "BETWEEN" -> actual != null
                    && compareValues(actual, c.value()) >= 0
                    && compareValues(actual, c.value2()) <= 0;
            case "IN" -> {
                if (!(c.value() instanceof Collection<?> coll)) {
                    yield equalsValue(actual, c.value());
                }
                boolean hit = false;
                for (Object v : coll) {
                    if (equalsValue(actual, v)) {
                        hit = true;
                        break;
                    }
                }
                yield hit;
            }
            default -> throw new IllegalArgumentException("不支持的查询操作符: " + c.op());
        };
    }

    static boolean equalsValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        BigDecimal na = toNumber(a);
        BigDecimal nb = toNumber(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /** 数值可解析时按数值比较，否则按字符串自然序（yyyy-MM-dd 日期字符串字典序即时间序） */
    static int compareValues(Object a, Object b) {
        if (b == null) {
            return 1;
        }
        BigDecimal na = toNumber(a);
        BigDecimal nb = toNumber(b);
        if (na != null && nb != null) {
            return na.compareTo(nb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** 支持 % 通配（SQL 风格）；无通配符时按包含匹配；忽略大小写 */
    private static boolean like(String actual, String pattern) {
        if (actual == null) {
            return false;
        }
        String a = actual.toLowerCase();
        String p = pattern.toLowerCase();
        if (!p.contains("%")) {
            return a.contains(p);
        }
        StringBuilder regex = new StringBuilder();
        for (char ch : p.toCharArray()) {
            if (ch == '%') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
            }
        }
        return a.matches(regex.toString());
    }

    static BigDecimal toNumber(Object o) {
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
