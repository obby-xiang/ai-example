package com.example.quickstart.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record TaskDto(
        Long id,
        String taskNo,
        String type,
        String name,
        String status,
        Integer currentStep,
        JsonNode stepData,
        Integer progress,
        String message,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
