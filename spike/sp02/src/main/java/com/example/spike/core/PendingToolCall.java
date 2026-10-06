package com.example.spike.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个工具调用的外置挂起条目：这是“挂起态 Redis 外置”的最小单元。
 *
 * <p>
 * 进程重启后，新实例只靠 Redis 里这一条记录就能知道：谁在等（toolCallId/name/args）、
 * 等到没有（status）、已经执行过没有（executed / executedBy / executedAtMs）、
 * 以及执行结果文本（resultText，用于重建 role:tool 消息）。
 */
public class PendingToolCall {

	/** 等待外部输入。 */
	public static final String PENDING = "PENDING";

	/** 前端工具结果已回灌。 */
	public static final String FRONTEND_RESULT = "FRONTEND_RESULT";

	/** 人工已放行（未必已执行）。 */
	public static final String APPROVED = "APPROVED";

	/** 人工已拒绝。 */
	public static final String REJECTED = "REJECTED";

	/** 确认门超时（未执行）。 */
	public static final String TIMEOUT = "TIMEOUT";

	public String toolCallId;

	public String name;

	/** {@code AssistantMessage.ToolCall.type()} 原文（OpenAI 协议下为 function）。 */
	public String type = "function";

	public String kind;

	public String arguments;

	public String status = PENDING;

	public String reason;

	public String resultText;

	public boolean executed;

	public String executedBy;

	public long executedAtMs;

	public long requestedAtMs;

	public long resolvedAtMs;

	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("toolCallId", this.toolCallId);
		out.put("name", this.name);
		out.put("type", this.type);
		out.put("kind", this.kind);
		out.put("arguments", this.arguments);
		out.put("status", this.status);
		out.put("reason", this.reason);
		out.put("resultText", this.resultText);
		out.put("executed", this.executed);
		out.put("executedBy", this.executedBy);
		out.put("executedAtMs", this.executedAtMs);
		out.put("requestedAtMs", this.requestedAtMs);
		out.put("resolvedAtMs", this.resolvedAtMs);
		return out;
	}

	public boolean resolved() {
		return !PENDING.equals(this.status);
	}

}
