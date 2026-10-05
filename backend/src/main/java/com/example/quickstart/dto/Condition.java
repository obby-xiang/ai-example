package com.example.quickstart.dto;

/** 查询条件。op: EQ/NE/LIKE/GT/GE/LT/LE/BETWEEN/IN；IN 的 value 为数组；BETWEEN 用 value+value2 */
public record Condition(String field, String op, Object value, Object value2) {
}
