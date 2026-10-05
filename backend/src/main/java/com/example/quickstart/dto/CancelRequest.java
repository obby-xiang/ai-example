package com.example.quickstart.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelRequest(@NotBlank String sessionId) {
}
