package com.example.quickstart.dto;

import java.time.LocalDateTime;
import java.util.List;

public record JobRunDto(
        Long id,
        Long taskId,
        String kind,
        String status,
        Integer total,
        Integer processed,
        String currentItem,
        String result,
        String error,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<JobItemDto> items) {
}
