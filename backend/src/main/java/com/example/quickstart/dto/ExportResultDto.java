package com.example.quickstart.dto;

import java.util.List;
import java.util.Map;

public record ExportResultDto(
        String defCode,
        String fileName,
        int rowCount,
        List<FieldDef> fields,
        List<Map<String, Object>> rows) {
}
