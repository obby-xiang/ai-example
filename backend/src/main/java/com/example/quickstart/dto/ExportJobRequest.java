package com.example.quickstart.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ExportJobRequest(@NotEmpty List<ExportItem> items) {

    public record ExportItem(String defCode, List<Condition> conditions) {
    }
}
