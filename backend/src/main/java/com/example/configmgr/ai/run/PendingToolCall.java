package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.ToolOutcome;
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

	/**
	 * 批策略跳过（P1-10）：同批次里上游 W/J/P 工具确定性失败（{@code outcome=failed 且
	 * retryable=false}），本调用<b>未执行</b>。这是有着落的终态 —— 有信封（outcome=skipped、
	 * code=UPSTREAM_FAILED）、有帧、有历史，模型侧每个 toolCallId 都有对应 ToolResponse。
	 */
	public static final String SKIPPED = "SKIPPED";

	/**
	 * 只读预检未通过（F5）：工具<b>未进入确认门、未弹卡、未执行</b>，结构化失败（outcome=failed、
	 * code={@code PRECHECK_FAILED}、retryable=true）回给模型由它向用户解释。
	 *
	 * <p>为什么不复用 {@link #BLOCKED}：前端对 BLOCKED 的卡面文案是写死的"不在披露范围内"
	 * （{@code frontend/src/utils/parts.ts} 的 tool_result 分支），预检失败挂到这个状态上会显示错误原因；
	 * 而本状态在 tool_result 帧上落到既有的 {@code outcome=failed} 分支（前端不 switch status），
	 * 因此<b>前端无需改动</b>即可正确显示"失败 + 完整原因文本"。
	 * 与 {@link #SKIPPED}（上游失败被跳过）分开记：两者都是"没做"，但一个是"不该轮到它"，
	 * 一个是"试过了、条件不满足、修好可重试"。
	 */
	public static final String PRECHECK_FAILED = "PRECHECK_FAILED";

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

	/** 结果信封的结局（{@code PendingToolCall} 的历史字段；null = 旧数据，按 status 推导）。 */
	private String outcome;

	/** 结果信封的机器可读码（null = 无码；旧数据按 status/reason 推导）。 */
	private String code;

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

	/**
	 * R-160 M3：挂起等待的绝对到期时刻（epoch 毫秒；只有有时限的前端工具才算定）。
	 * 包装类型：null = 无期限（确认门/表单/旧数据）。去超时裁决后前端不再据此倒计时或
	 * 渲染「等待人工确认」静态兜底，本字段只作对账/取证口径。
	 */
	private Long expiresAt;

	/** R-160 M3：算定 expiresAt 的构造时刻（同帧单点取值；仅作对账口径，前端不据此武装倒计时）。 */
	private Long atMs;

	/**
	 * L0（2026-10-21 用户直裁）：<b>请求帧是否真的发过</b>。
	 *
	 * <p>一批多调用挂起时，请求帧是<b>逐条</b>发的（{@code ConfirmGate#await} 逐个发自己那张卡
	 * 再断流）；{@code saveSuspended} 却把<b>整批</b>待决条目一起写进 Redis —— 于是"条目存在"
	 * 不等于"卡已送达"。刷新恢复若按条目存在就建卡，用户会拿到一张请求帧还没发出的卡并提前点击
	 * （决策早于帧，后端走到该条目时又补发一次请求帧 ⇒ 卡面瞬时复活再被收敛）。
	 *
	 * <p>取值 = {@code ConfirmGate#await} 在<b>发射请求帧之前</b>置位（{@link RunStore#markRequestEmitted}）；
	 * {@code null} = 旧数据（本字段引入前的条目）/ 从未发射。刷新恢复只认 {@code true}
	 * （{@code GET /api/ai/runs/current} 的 {@code req} 字段）。
	 */
	private Boolean req;

	/** {@link #req} 置位的时刻（服务端钟；0 = 未发射/旧数据）。 */
	private long reqAtMs;

	/**
	 * F5 只读预检报告（{@code {target, checks, impact, reportHash}}，见
	 * {@code gate/ToolPreflightService}）：进确认门的工具在挂起<b>之前</b>产出的那份结论，
	 * 随 {@code suspended} 帧的 {@code toolCalls[]} 与 {@code confirm_request} 帧下发。
	 *
	 * <p>缺省为 null（非确认门工具、旧数据）—— 契约上它是可选字段，
	 * 前端"没有就不显示预检区"，不改变既有卡面。
	 */
	private Map<String, Object> preflight;

	public boolean resolved() {
		return !PENDING.equals(this.status);
	}

	/**
	 * 条目当前结局：优先取持久化 outcome（新数据），否则按 {@link #status} 推导（旧数据/挂起中）。
	 * 未决条目推导为 {@code unknown} —— 未知不等于失败。
	 */
	public ToolOutcome effectiveOutcome() {
		ToolOutcome persisted = ToolOutcome.fromWire(this.outcome);
		return persisted == null ? ToolOutcome.fromStatus(this.status) : persisted;
	}

	/**
	 * 条目当前错误码：优先取持久化 code，否则按状态/原因推导
	 * （{@code BLOCKED→SCOPE_NOT_DISCLOSED}、{@code TIMEOUT→reason}、{@code CANCELLED→RUN_CANCELLED} 等）。
	 */
	public String effectiveCode() {
		if (this.code != null && !this.code.isBlank()) {
			return this.code;
		}
		return switch (this.status) {
			case BLOCKED -> ToolOutcome.CODE_SCOPE_NOT_DISCLOSED;
			case TIMEOUT -> this.reason == null || this.reason.isBlank() ? "TIMEOUT" : this.reason;
			case REJECTED_ARGUMENTS -> this.reason == null || this.reason.isBlank() ? "REJECTED_ARGUMENTS" : this.reason;
			case CANCELLED -> ToolOutcome.CODE_RUN_CANCELLED;
			case FRONTEND_CANCELLED -> ToolOutcome.CODE_FRONTEND_CANCELLED;
			case SKIPPED -> ToolOutcome.CODE_UPSTREAM_FAILED;
			case PRECHECK_FAILED -> ToolOutcome.CODE_PRECHECK_FAILED;
			default -> null;
		};
	}

	public Map<String, Object> asMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("toolCallId", this.toolCallId);
		out.put("name", this.name);
		out.put("type", this.type);
		out.put("kind", this.kind);
		out.put("arguments", this.arguments);
		out.put("status", this.status);
		out.put("outcome", effectiveOutcome().wire());
		out.put("code", effectiveCode());
		out.put("reason", this.reason);
		out.put("resultText", this.resultText);
		out.put("executed", this.executed);
		out.put("executedBy", this.executedBy);
		out.put("claimedAtMs", this.claimedAtMs);
		out.put("executedAtMs", this.executedAtMs);
		out.put("requestedAtMs", this.requestedAtMs);
		out.put("resolvedAtMs", this.resolvedAtMs);
		out.put("expiresAt", this.expiresAt);
		out.put("atMs", this.atMs);
		out.put("req", Boolean.TRUE.equals(this.req));
		out.put("reqAtMs", this.reqAtMs);
		out.put("preflight", this.preflight);
		return out;
	}

}
