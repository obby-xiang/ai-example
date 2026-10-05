package com.example.quickstart.dto;

import java.util.List;

public record ConfigDefDto(
        String code,
        String name,
        String level,
        String description,
        List<FieldDef> fields,
        List<String> dependsOn,
        Long rowCount) {
}
