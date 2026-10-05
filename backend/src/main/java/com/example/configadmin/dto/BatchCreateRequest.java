package com.example.configadmin.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 创建导入批次请求。 */
public record BatchCreateRequest(String name, @NotEmpty(message = "请选择配置") List<String> defCodes) {
}
