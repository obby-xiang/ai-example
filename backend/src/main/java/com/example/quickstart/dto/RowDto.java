package com.example.quickstart.dto;

import java.util.Map;

public record RowDto(Integer rowNo, Map<String, Object> data) {
}
