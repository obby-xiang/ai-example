package com.example.quickstart.dto;

import java.util.List;

/** 配置项字段定义。type: STRING/NUMBER/DATE/ENUM/BOOLEAN */
public record FieldDef(
        String name,
        String label,
        String type,
        boolean required,
        List<String> options,
        Integer maxLength,
        RefDef ref) {
}
