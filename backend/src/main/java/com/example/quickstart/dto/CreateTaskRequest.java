package com.example.quickstart.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTaskRequest(
        @NotBlank String type,
        @NotBlank String name) {
}
