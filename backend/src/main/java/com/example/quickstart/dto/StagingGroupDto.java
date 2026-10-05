package com.example.quickstart.dto;

import java.util.List;

public record StagingGroupDto(
        String defCode,
        int rowCount,
        List<RowDto> rows) {
}
