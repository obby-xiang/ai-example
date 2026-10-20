package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.run.PendingToolCall;

import java.util.Set;

/**
 * 工具结果的结局（统一信封 {@link ToolResultEnvelope} 的 {@code outcome} 维度）。
 *
 * <p>
 * 前后端与台账共用同一套字面量（{@link #wire()}）；{@code ok} 不再是独立判据，
 * 而是 {@code outcome == ok} 的派生值 —— 这样"ok=true 但文案写失败"的自相矛盾在结构上不可表达。
 *
 * <p>
 * {@code retryable} 同样由结局与错误码推导（见 {@link #retryable(String)}）：
 * <ul>
 * <li>{@link #REJECTED}/{@link #BLOCKED}/{@link #TIMEOUT}/{@link #CANCELLED}：重试不会改变结论；</li>
 * <li>{@link #FAILED}：只有"改数据后可重试"的码（预检查未通过/过期）为 true，
 * 其余（作业在途、任务终态、结果缺失、参数非法等）为 false；</li>
 * <li>{@link #OK}/{@link #UNKNOWN}：false（成功无须重试，未知须先对账）。</li>
 * </ul>
 */
public enum ToolOutcome {

	OK("ok"),

	FAILED("failed"),

	REJECTED("rejected"),

	BLOCKED("blocked"),

	TIMEOUT("timeout"),

	CANCELLED("cancelled"),

	UNKNOWN("unknown");

	/** 工具抛异常且异常不携带机器可读码时的兜底码。 */
	public static final String CODE_TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";

	/** 官方执行器没有回填该 toolCallId 的结果（{@code orElse(null)} 出口）。 */
	public static final String CODE_TOOL_RESULT_MISSING = "TOOL_RESULT_MISSING";

	/** 业务对象不存在（{@code ResourceNotFoundException} 的默认码）。 */
	public static final String CODE_RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

	/** 参数非法（工具入参的类型/取值不合法）。 */
	public static final String CODE_ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";

	/** 他实例已认领该调用，本实例不再执行（避免重复副作用）。 */
	public static final String CODE_ALREADY_CLAIMED = "TOOL_ALREADY_CLAIMED";

	/** 越 scope（防线③）。 */
	public static final String CODE_SCOPE_NOT_DISCLOSED = "SCOPE_NOT_DISCLOSED";

	public static final String CODE_CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT";

	public static final String CODE_FRONTEND_TIMEOUT = "FRONTEND_TIMEOUT";

	public static final String CODE_RUN_CANCELLED = "RUN_CANCELLED";

	public static final String CODE_FRONTEND_CANCELLED = "FRONTEND_CANCELLED";

	/** "改数据后可重试"的失败码（预检查未通过/过期；其余失败码一律不可重试）。 */
	private static final Set<String> RETRYABLE_FAILURE_CODES = Set.of("PRECHECK_NOT_PASSED", "PRECHECK_STALE");

	private final String wire;

	ToolOutcome(String wire) {
		this.wire = wire;
	}

	/** 线上/JSON 字面量。 */
	public String wire() {
		return this.wire;
	}

	/** 契约里的 {@code ok} 判据：只有 {@link #OK} 为真。 */
	public boolean ok() {
		return this == OK;
	}

	/**
	 * 是否可重试。
	 *
	 * @param code 失败码（仅 {@link #FAILED} 分支使用；可为 null）
	 */
	public boolean retryable(String code) {
		if (this != FAILED || code == null) {
			return false;
		}
		return RETRYABLE_FAILURE_CODES.contains(code);
	}

	/** 字面量 → 结局；未知字面量按 {@link #UNKNOWN}（未知不等于失败）。 */
	public static ToolOutcome fromWire(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (ToolOutcome outcome : values()) {
			if (outcome.wire.equalsIgnoreCase(value)) {
				return outcome;
			}
		}
		return UNKNOWN;
	}

	/** 待决条目状态 → 结局（旧数据没有持久化 outcome 时的推导口径）。 */
	public static ToolOutcome fromStatus(String status) {
		if (status == null) {
			return UNKNOWN;
		}
		return switch (status) {
			case PendingToolCall.EXECUTED, PendingToolCall.FRONTEND_RESULT, "DONE" -> OK;
			case PendingToolCall.REJECTED, PendingToolCall.REJECTED_ARGUMENTS -> REJECTED;
			case PendingToolCall.BLOCKED -> BLOCKED;
			case PendingToolCall.TIMEOUT -> TIMEOUT;
			case PendingToolCall.CANCELLED, PendingToolCall.FRONTEND_CANCELLED -> CANCELLED;
			default -> UNKNOWN;
		};
	}

}
