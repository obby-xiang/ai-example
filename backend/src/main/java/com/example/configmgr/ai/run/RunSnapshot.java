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

	/** 被用户取消（终态：不再续跑，终帧为 {@code done{cancelled:true}}）。 */
	public static final String CANCELLED = "CANCELLED";

	/**
	 * 终态串的<b>归一别名</b>（T3-7 / A13）：轮次状态本身没有 {@code REJECTED}
	 * （条目级的 {@code PendingToolCall.REJECTED} 与前端 {@code rejected} 语义<b>不动</b>），
	 * 但终端判定函数把该串归入"失败类终态" —— 于是任何来源传进来的 {@code REJECTED}
	 * 都不会被当成"还能续跑的活轮"。
	 */
	private static final String REJECTED_ALIAS = "REJECTED";

	/**
	 * 统一终态判定（T3-7 / A14）：DONE / FAILED / CANCELLED（+ {@code REJECTED} 归一）为终态。
	 *
	 * <h2>为什么必须唯一函数</h2>
	 * 此前 {@code DONE|FAILED|CANCELLED} 这一组字面量散落在三处
	 * （{@code AiController#isTerminal}、{@code RunStore#save} 的 session→current 索引分支、
	 * {@code ResumeService} 的早退比较与 {@code listRuns} 的 resumeable 组合）——
	 * 任何一处漏改都会造成"一边认为终态、一边还在续跑"的分叉（例如终态轮被重新发现并驱动）。
	 *
	 * @param status 轮次状态串（可为 null / 未知串 → false）
	 */
	public static boolean isTerminal(String status) {
		return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status)
				|| REJECTED_ALIAS.equals(status);
	}

	private String runId;

	private String sessionId;

	/** 轮次状态，取值见本类常量。 */
	private String status = RUNNING;

	/** 已完成的工具轮数（挂起即 +1，用于"轮数上限"类判定与取证）。 */
	private int round;

	/** 本轮的尝试次数（韧性棒：&gt;1 说明发生过断流重试）。 */
	private int attempts;

	/** 是否由用户取消（终态判据之一，与 {@link #CANCELLED} 同步设置）。 */
	private boolean cancelled;

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

	/**
	 * 本轮使用的 provider（模型配置专项批①；{@code ai_provider.id}）。
	 *
	 * <p>
	 * {@code null} = 旧快照（批① 之前写的）或 env 合成行（种子尚未落库）。批③ 的续跑将<b>按快照</b>
	 * 取模型：provider 已删/停用 ⇒ 409 {@code PROVIDER_GONE}，不静默换模型续跑（历史与
	 * {@code done.model} 都不许说谎）。
	 */
	private Long providerId;

	/** 本轮使用的模型名（请求体 {@code model} 的原值；{@code null} = 旧快照）。 */
	private String modelId;

	/**
	 * 写入本条快照的<b>租约 epoch</b>（B23L1 批②a）：0 = 无租约写入（单实例/降级路径/旧数据）。
	 *
	 * <p>它回答"这条状态是哪一任续跑权写的"：与 {@code ai:fence:run:<runId>} 对账即可判定
	 * 一次写回是否已被后来者超越（陈旧写不得覆盖新主人的状态）。旧 JSON 没有本字段 ⇒ 0。
	 */
	private long runEpoch;

	/** 取证视图（不含 messageJson 正文）。 */
	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", this.runId);
		out.put("sessionId", this.sessionId);
		out.put("status", this.status);
		out.put("round", this.round);
		out.put("attempts", this.attempts);
		out.put("cancelled", this.cancelled);
		out.put("messageCount", this.messageJson.size());
		out.put("inFlight", this.inFlightToolCalls.size());
		out.put("assistantContent", this.assistantContent);
		out.put("finalText", this.finalText);
		out.put("error", this.error);
		out.put("createdBy", this.createdBy);
		out.put("createdAtMs", this.createdAtMs);
		out.put("updatedAtMs", this.updatedAtMs);
		out.put("resumedBy", this.resumedBy);
		out.put("runEpoch", this.runEpoch);
		// 模型身份（模型配置专项批①，additive）：旧快照为 null，取证口径与 runEpoch 一致
		out.put("providerId", this.providerId);
		out.put("modelId", this.modelId);
		return out;
	}

}
