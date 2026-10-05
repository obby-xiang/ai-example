package com.example.quickstart.dto;

import java.util.List;

public record JobItemDto(
        String defCode,
        Integer seq,
        String status,
        Integer totalRows,
        Integer okRows,
        Integer errorRows,
        String message,
        List<RowError> detail) {
}
