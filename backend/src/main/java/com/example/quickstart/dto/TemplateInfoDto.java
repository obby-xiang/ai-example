package com.example.quickstart.dto;

import java.util.List;

public record TemplateInfoDto(String code, String fileName, List<FieldDef> fields) {
}
