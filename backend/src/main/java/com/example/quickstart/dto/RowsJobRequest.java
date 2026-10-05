package com.example.quickstart.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/** 检查/导入作业请求（结构相同）：逐配置项携带数据行 */
public record RowsJobRequest(@NotEmpty List<RowsItem> items) {

    public record RowsItem(String defCode, List<Map<String, Object>> rows) {
    }
}
