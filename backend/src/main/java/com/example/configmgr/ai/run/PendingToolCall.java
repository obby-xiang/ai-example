package com.example.configmgr.ai.run;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个工具调用的外置待决条目（{@code ai:pending:<runId>} HASH 的 field 值）。
 *
 * <p>
 * 这是"挂起态 Redis 外置"的最小单元：进程重启后，新实例只靠 Redis 里这一条就能知道
 * 谁在等（toolCallId/name/arguments）、等到没有（status）、已经执行过没有
 * （executed/executedBy/executedAtMs —— 跨进程防重放的判据），以及结果文本
 * （resultText，用于重建 {@code role:tool} 消息）。
 */
@Data
public class PendingToolCall {

	/** 等待外部输入（人工确认或前端回灌）。 */
	public static final String PENDING = "PENDING";

	/** 人工已放行（未必已执行 —— 执行与标记的先后见 {@link RunStore#markExecuting}）。 */
	public static final String APPROVED = "APPROVED";

	/** 人工已拒绝。 */
	public static final String REJECTED = "REJECTED";

	/** 确认门超时（未执行）。 */
	public static final String TIMEOUT = "TIMEOUT";

	/** 前端工具结果已回灌。 */
	public static final String FRONTEND_RESULT = "FRONTEND_RESULT";

	/**
	 * 前端用户主动放弃（关闭/取消表单等）：<b>明确终态</b> —— 未获得任何数据，本轮循环继续
	 * （模型据"用户取消"收尾）。与 {@link #CANCELLED}（整轮被取消）分开记：前者只结束这一次
	 * 工具调用，后者终止整轮。
	 */
	public static final String FRONTEND_CANCELLED = "FRONTEND_CANCELLED";

	/**
	 * 前端工具的入参被后端安全闸拒绝（DC-15）：<b>未下发、未挂起、未执行</b>，
	 * 结论只回填模型（如生成式表单 schema 越白名单）。与 {@link #BLOCKED}（越 scope）分开记 ——
	 * "参数不合规"和"上下文不该用它"在排障时是两件事。
	 */
	public static final String REJECTED_ARGUMENTS = "REJECTED_ARGUMENTS";

	/** 本轮被用户取消（未执行；取消即落库，跨进程续跑不会把它当"待外部输入"）。 */
	public static final String CANCELLED = "CANCELLED";

	/** 后端工具已执行完成（无外部等待）。 */
	public static final String EXECUTED = "EXECUTED";

	/**
	 * 防线③拦截（DC-14 T1）：工具在当前上下文里本就不该被披露（越 scope），
	 * 因此<b>未执行</b>、也不会执行。与 {@link #REJECTED}（人拒绝）分开记 ——
	 * "模型越权"和"人拒绝"在排障时是两件事。
	 */
	public static final String BLOCKED = "BLOCKED";

	/** 挂起种类：确认门（DANGER 风险）。 */
	public static final String KIND_CONFIRM = "CONFIRM";

	/** 挂起种类：前端工具。 */
	public static final String KIND_FRONTEND = "FRONTEND";

	/** 挂起种类：后端直接执行（不挂起，仅留台账）。 */
	public static final String KIND_BACKEND = "BACKEND";

	private String toolCallId;

	private String name;

	/** {@code AssistantMessage.ToolCall.type()} 原文（OpenAI 协议下为 function）。 */
	private String type = "function";

	/** 挂起种类，取值见 {@link #KIND_CONFIRM} / {@link #KIND_FRONTEND} / {@link #KIND_BACKEND}。 */
	private String kind;

	private String arguments;

	private String status = PENDING;

	private String reason;

	private String resultText;

	private boolean executed;

	/** 执行者实例标识 —— 跨进程"重放与否"的直接判据。 */
	private String executedBy;

	/**
	 * 认领时刻（N5）：执行<b>之前</b>由 {@link RunStore#claimExecution} 写入，
	 * 与 {@link #executedAtMs}（执行完成时刻）分列，不再互相覆写。
	 */
	private long claimedAtMs;

	/** 执行完成时刻（认领先于执行，故 {@code claimedAtMs ≤ executedAtMs}）。 */
	private long executedAtMs;

	private long requestedAtMs;

	private long resolvedAtMs;

	public boolean resolved() {
		return !PENDING.equals(this.status);
	}

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
		out.put("claimedAtMs", this.claimedAtMs);
		out.put("executedAtMs", this.executedAtMs);
		out.put("requestedAtMs", this.requestedAtMs);
		out.put("resolvedAtMs", this.resolvedAtMs);
		return out;
	}

}
