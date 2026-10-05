package com.example.configadmin.dto;

/**
 * 单字段查询条件。
 * op: eq(等于) / contains(包含) / gt / lt / between / in(多选) / is_true / is_false
 * value 为标量；between/in 时为数组；value2 用于 between 上限。
 */
public record Cond(String op, Object value, Object value2) {
}
