package com.example.configadmin.ai;

import com.example.configadmin.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 工具执行所需的服务集合（经 Spring AI ToolContext 注入 @Tool 方法）。 */
public record Services(
        ConfigDefService defService,
        ConfigDataService dataService,
        ExportService exportService,
        ImportService importService,
        TaskRunner taskRunner,
        ObjectMapper mapper) {
}
