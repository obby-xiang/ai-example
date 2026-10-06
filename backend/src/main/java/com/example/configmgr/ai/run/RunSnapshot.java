package com.example.configmgr.ai.run;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮对话的完整外置状态（{@code ai:run:<runId>} 的 JSON 值对象）。
 *
 * <p>
 * 字段划分即"续跑协议"的输入契约（SP-02 §5，两条硬规范）：
 * <ul>
 * <li>{@link #messageJson}：<b>完整请求历史</b>——含 {@code system} 两条与 {@code user}，
 * 以及每一轮落定的 {@code assistant(tool_calls)} 与 {@code role:tool} 配对。
 * 硬规范②（续跑必须重建完整历史）之所以成立，靠的就是这里存的是"模型当时真正看到的
 * 那串消息"，而不是官方循环自己那份"只有 assistant+tool"的续跑请求；</li>
 * <li>{@link #assistantContent} / {@link #inFlightToolCalls}：<b>挂起中的那一轮</b>。
 * 崩溃发生在这一轮中间时，凭这两项 + {@code ai:pending:<runId>} 的条目即可重建
 * {@code assistant(tool_calls)} 与 {@code role:tool} 消息继续跑；</li>
 * <li>{@link #context}：披露上下文（页面/任务/步骤），续跑据此重建同一份工具子集；</li>
 * <li>{@link #status}：RUNNING / SUSPENDED / DONE / FAILED —— 供重启后的发现与幂等判定。</li>
 * </ul>
 */
@Data
public class RunSnapshot {

	public static final String RUNNING = "RUNNING";

	public static final String SUSPENDED = "SUSPENDED";

	public static final String DONE = "DONE";

	public static final String FAILED = "FAILED";

	private String runId;

	private String sessionId;

	/** 轮次状态，取值见本类常量。 */
	private String status = RUNNING;

	/** 已完成的工具轮数（挂起即 +1，用于"轮数上限"类判定与取证）。 */
	private int round;

	/** 完整请求历史（每条一个 {@code MessageJsonCodec} JSON 字符串）。 */
	private List<String> messageJson = new ArrayList<>();

	/** 挂起中那一轮的 assistant 正文（通常为空串：工具轮的 assistant 正文就是空的）。 */
	private String assistantContent;

	/** 挂起中那一轮的工具调用（顺序即模型给出的顺序）。 */
	private List<PendingToolCall> inFlightToolCalls = new ArrayList<>();

	/** 披露上下文快照（{@code AiContext.toMap()}）。 */
	private Map<String, Object> context = new LinkedHashMap<>();

	private String finalText;

	private String error;

	/** 本实例标识：证明某次执行发生在"重启后的新进程"里。 */
	private String createdBy;

	private long createdAtMs;

	private long updatedAtMs;

	/** 续跑过的实例标识（按时间顺序）。 */
	private List<String> resumedBy = new ArrayList<>();

	/** 取证视图（不含 messageJson 正文）。 */
	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", this.runId);
		out.put("sessionId", this.sessionId);
		out.put("status", this.status);
		out.put("round", this.round);
		out.put("messageCount", this.messageJson.size());
		out.put("inFlight", this.inFlightToolCalls.size());
		out.put("assistantContent", this.assistantContent);
		out.put("finalText", this.finalText);
		out.put("error", this.error);
		out.put("createdBy", this.createdBy);
		out.put("createdAtMs", this.createdAtMs);
		out.put("updatedAtMs", this.updatedAtMs);
		out.put("resumedBy", this.resumedBy);
		return out;
	}

}
