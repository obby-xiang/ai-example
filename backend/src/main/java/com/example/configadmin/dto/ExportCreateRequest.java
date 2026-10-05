package com.example.configadmin.dto;

import java.util.List;
import java.util.Map;

/** 创建导出任务请求。 */
public record ExportCreateRequest(List<String> defCodes,
                                  Map<String, Map<String, Cond>> conditions) {
}
