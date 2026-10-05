package com.example.quickstart.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record QueryRequest(
        @NotBlank String code,
        List<Condition> conditions,
        Integer page,
        Integer size) {
}
