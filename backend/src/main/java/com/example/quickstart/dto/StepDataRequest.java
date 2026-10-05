package com.example.quickstart.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record StepDataRequest(
        @NotNull Integer currentStep,
        JsonNode stepData) {
}
