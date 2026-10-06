package com.example.spike.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的完整外置状态（Redis 值对象）。字段划分即“续跑协议”的输入契约：
 *
 * <ul>
 * <li>{@code messageJson}：**已完成的对话上下文**（settled history）——每轮结束即整体回写；</li>
 * <li>{@code assistantContent} / {@code inFlightToolCalls}：**挂起中的那一轮**——即
 * {@code assistant(tool_calls)} 消息原文与它的每个工具调用条目的外置副本。
 * 崩溃发生在这一轮中间时，凭这两项 + hitl 条目即可重建 {@code assistant(tool_calls)} 与
 * {@code role:tool} 消息并续跑；</li>
 * <li>{@code status}：RUNNING / SUSPENDED / DONE / FAILED —— 供重启后的发现与幂等判定；</li>
 * <li>{@code emittedChars}：已向客户端产出的字符数（“不重复产出已发送内容”的对账口径）。</li>
 * </ul>
 */
public class RunSnapshot {

	public static final String RUNNING = "RUNNING";

	public static final String SUSPENDED = "SUSPENDED";

	public static final String DONE = "DONE";

	public static final String FAILED = "FAILED";

	public String runId;

	public String sessionId;

	public String mode;

	public String status = RUNNING;

	public int round;

	public String systemPrompt;

	/** 已完成上下文：每条消息的 JSON（MessageJsonCodec 格式）。 */
	public List<String> messageJson = new ArrayList<>();

	/** 挂起中的 assistant 消息文本正文。 */
	public String assistantContent;

	/** 挂起中的 assistant(tool_calls)（顺序即模型给出的顺序）。 */
	public List<PendingToolCall> inFlightToolCalls = new ArrayList<>();

	public String finalText;

	public String error;

	public int emittedChars;

	public String createdBy;

	public long createdAtMs;

	public long updatedAtMs;

	public List<String> resumedBy = new ArrayList<>();

	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", this.runId);
		out.put("sessionId", this.sessionId);
		out.put("mode", this.mode);
		out.put("status", this.status);
		out.put("round", this.round);
		out.put("messageCount", this.messageJson.size());
		out.put("inFlight", this.inFlightToolCalls.size());
		out.put("assistantContent", this.assistantContent);
		out.put("finalText", this.finalText);
		out.put("error", this.error);
		out.put("emittedChars", this.emittedChars);
		out.put("createdBy", this.createdBy);
		out.put("createdAtMs", this.createdAtMs);
		out.put("updatedAtMs", this.updatedAtMs);
		out.put("resumedBy", this.resumedBy);
		return out;
	}

}
