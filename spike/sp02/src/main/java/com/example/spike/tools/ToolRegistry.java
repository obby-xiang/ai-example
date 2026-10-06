package com.example.spike.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具契约登记：本 PoC 的工具只有三类，分类决定官方循环内的处理方式。
 * 工具本体仍由 Spring AI @Tool 自动生成 JSON Schema（见 SpikeTools）。
 */
public final class ToolRegistry {

	public enum Kind {

		/** 后端直接执行的普通工具。 */
		BACKEND,
		/** 前端工具：后端只透出调用请求，结果由前端回灌（SP-01a）。 */
		FRONTEND,
		/** 敏感工具：执行前挂起等人工确认（SP-01b）。 */
		SENSITIVE

	}

	private static final Map<String, Kind> KINDS = Map.of("get_user_profile", Kind.FRONTEND, "delete_config",
			Kind.SENSITIVE);

	public static Kind kindOf(String toolName) {
		return KINDS.getOrDefault(toolName, Kind.BACKEND);
	}

	public static Map<String, String> all() {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("calculate", Kind.BACKEND.name());
		out.put("get_user_profile", Kind.FRONTEND.name());
		out.put("delete_config", Kind.SENSITIVE.name());
		return out;
	}

	public static boolean isFrontendTool(String toolName) {
		return kindOf(toolName) == Kind.FRONTEND;
	}

	private ToolRegistry() {
	}

}
