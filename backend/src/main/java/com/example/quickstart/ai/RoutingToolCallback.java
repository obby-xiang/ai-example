package com.example.quickstart.ai;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具回调：向 Spring AI 工具体系提供 ToolDefinition（name/description/inputSchema）。
 * 由于请求 options 设了 internalToolExecutionEnabled=false，框架不会真正执行 call()；
 * 兜底语义：BACKEND 工具路由到 BackendToolExecutor，FRONTEND 工具返回错误 JSON。
 */
public class RoutingToolCallback implements ToolCallback {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final AiToolRegistry.AiTool tool;

	private final ToolDefinition toolDefinition;

	private final BackendToolExecutor backendToolExecutor;

	private final ObjectMapper om;

	public RoutingToolCallback(AiToolRegistry.AiTool tool, String inputSchemaJson,
			BackendToolExecutor backendToolExecutor, ObjectMapper om) {
		this.tool = tool;
		this.toolDefinition = ToolDefinition.builder()
			.name(tool.name())
			.description(tool.description())
			.inputSchema(inputSchemaJson)
			.build();
		this.backendToolExecutor = backendToolExecutor;
		this.om = om;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.toolDefinition;
	}

	@Override
	public String call(String toolInput) {
		if (this.tool.kind() == AiToolRegistry.Kind.FRONTEND) {
			return "{\"success\":false,\"error\":\"前端工具须由前端执行，不应由框架调用\"}";
		}
		try {
			Map<String, Object> args = this.om.readValue(toolInput, MAP_TYPE);
			Object result = this.backendToolExecutor.execute(this.tool.name(), args);
			return this.om.writeValueAsString(result);
		}
		catch (Exception e) {
			return "{\"success\":false,\"error\":\"工具执行失败: " + e.getMessage() + "\"}";
		}
	}

}
