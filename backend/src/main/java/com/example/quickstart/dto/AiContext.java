package com.example.quickstart.dto;

import java.util.List;

/** 请求时注入的工作区上下文（Agent Context） */
public record AiContext(
        String page,
        Integer step,
        Long taskId,
        List<String> selectedDefs,
        Long dataVersion) {
}
