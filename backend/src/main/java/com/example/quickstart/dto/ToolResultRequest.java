package com.example.quickstart.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ToolResultRequest(
        @NotBlank String sessionId,
        @NotBlank String callId,
        Map<String, Object> result,
        AiContext context) {
}
