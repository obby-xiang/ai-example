package com.example.configadmin.ai;

import java.util.Map;

/** AI 工具接口：def() 声明元数据，run() 执行。 */
public interface AiTool {

    ToolDef def();

    ToolResult run(ToolContext ctx, Map<String, Object> args);
}
