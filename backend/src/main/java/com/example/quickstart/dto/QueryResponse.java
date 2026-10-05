package com.example.quickstart.dto;

import java.util.List;

public record QueryResponse(long total, List<RowDto> rows) {
}
