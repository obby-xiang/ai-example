package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.common.ErrorCodes;

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
 * <li>{@link #REJECTED}/{@link #TIMEOUT}/{@link #CANCELLED}：重试不会改变结论；</li>
 * <li>{@link #BLOCKED}：只有 {@link #CODE_RESOURCE_LOCKED}（资源正被同类动作占用）为 true ——
 * 这是"现在不能执行"，等锁释放即可；越 scope 等其余 blocked 码仍为 false；</li>
 * <li>{@link #FAILED}：只有"改数据后可重试"的码（预检查未通过/过期/只读预检不通过）为 true，
 * 其余（作业在途、任务终态、结果缺失、参数非法、目标资源不成立等）为 false；</li>
 * <li>{@link #OK}/{@link #UNKNOWN}/{@link #SKIPPED}：false（成功无须重试，未知须先对账，跳过不得重试）。</li>
 * </ul>
 */
public enum ToolOutcome {

	OK("ok"),

	FAILED("failed"),

	REJECTED("rejected"),

	BLOCKED("blocked"),

	TIMEOUT("timeout"),

	CANCELLED("cancelled"),

	UNKNOWN("unknown"),

	/**
	 * 未执行：批次里上游 W/J/P 工具确定性失败后，本轮已规划但尚未执行的调用。
	 * 不是成功、不是空文本、也不是可重试失败（P1-10 批策略）。
	 */
	SKIPPED("skipped");

	/** 工具抛异常且异常不携带机器可读码时的兜底码。 */
	public static final String CODE_TOOL_EXECUTION_FAILED = ErrorCodes.TOOL_EXECUTION_FAILED;

	/** 官方执行器没有回填该 toolCallId 的结果（{@code orElse(null)} 出口）。 */
	public static final String CODE_TOOL_RESULT_MISSING = ErrorCodes.TOOL_RESULT_MISSING;

	/** 业务对象不存在（{@code ResourceNotFoundException} 的默认码）。 */
	public static final String CODE_RESOURCE_NOT_FOUND = ErrorCodes.RESOURCE_NOT_FOUND;

	/** 参数非法（工具入参的类型/取值不合法）。 */
	public static final String CODE_ILLEGAL_ARGUMENT = ErrorCodes.ILLEGAL_ARGUMENT;

	/** 他实例已认领该调用，本实例不再执行（避免重复副作用）。 */
	public static final String CODE_ALREADY_CLAIMED = ErrorCodes.TOOL_ALREADY_CLAIMED;

	/** 越 scope（防线③）。 */
	public static final String CODE_SCOPE_NOT_DISCLOSED = ErrorCodes.SCOPE_NOT_DISCLOSED;

	/**
	 * 表格工具被管理员关闸（{@code app.ai.sheet.*}）：<b>未执行、零副作用</b>，结局 {@link #FAILED}。
	 *
	 * <p>不可重试（{@link #retryable(String)} 恒 false）：开关是进程内一次绑定，重试同一个调用
	 * 只会得到同一个结论 —— 要变只能改配置并重启（方案 §2.4"不要重试"的模型侧提示）。
	 */
	public static final String CODE_SHEET_DISABLED = ErrorCodes.SHEET_DISABLED;

	public static final String CODE_CONFIRM_TIMEOUT = ErrorCodes.CONFIRM_TIMEOUT;

	public static final String CODE_FRONTEND_TIMEOUT = ErrorCodes.FRONTEND_TIMEOUT;

	/**
	 * 后端工具执行超时（{@code app.ai.tool.exec-timeout}）：到点未跑完，{@code executed=false}，
	 * 结局 {@link #TIMEOUT}（不可重试：重试同一个调用大概率同样卡住），轮继续。
	 */
	public static final String CODE_TOOL_TIMEOUT = ErrorCodes.TOOL_TIMEOUT;

	/** 工具调用步数到顶（{@code app.ai.resilience.max-tool-steps}）：未执行，结局 {@link #SKIPPED}。 */
	public static final String CODE_TOOL_STEP_LIMIT = ErrorCodes.TOOL_STEP_LIMIT;

	/**
	 * 轮内上下文预算耗尽（工具轮内上下文预算批）：本轮已进入收尾（armed）后模型仍请求工具，
	 * 调用未执行、结局 {@link #SKIPPED}。
	 */
	public static final String CODE_TOOL_BUDGET_EXHAUSTED = ErrorCodes.TOOL_BUDGET_EXHAUSTED;

	public static final String CODE_RUN_CANCELLED = ErrorCodes.RUN_CANCELLED;

	public static final String CODE_FRONTEND_CANCELLED = ErrorCodes.FRONTEND_CANCELLED;

	/** 未执行（批策略跳过）：上游工具 deterministic 失败后本调用没有执行。 */
	public static final String CODE_UPSTREAM_FAILED = ErrorCodes.UPSTREAM_FAILED;

	/**
	 * 只读预检未通过（F5 预检前移）：工具<b>未进入确认门</b>、未执行、不弹卡 ——
	 * 结构化失败回给模型，由模型向用户说明原因与建议后再发起。
	 */
	public static final String CODE_PRECHECK_FAILED = ErrorCodes.PRECHECK_FAILED;

	/**
	 * 预检基础设施不可用（F5 安全侧兜底）：{@code ToolPreflightService.inspect} 自身抛错时，
	 * <b>既不判通过也不判"预检未通过"</b> —— 工具同样不进确认门、不执行，结构化失败回给模型。
	 * 可重试（抖动恢复后可重来），措辞不得暗示"条件已满足"。
	 */
	public static final String CODE_PRECHECK_UNAVAILABLE = ErrorCodes.PRECHECK_UNAVAILABLE;

	/**
	 * 目标资源正被同类动作占用（B23L1 批③a / 裁决 4）：L1 资源互斥（{@code ai:lock:*}，见
	 * {@code LeaseKeys}）抢不到锁时的机器可读码，结局 {@link #BLOCKED}。
	 *
	 * <p>为什么是 {@code blocked} 而不是 {@code failed}：这不是"执行出错"，而是"现在不能执行"——
	 * 与前一个动作的成败无关，等对方收尾即可。因此它<b>可重试</b>
	 * （见 {@link #retryable(String)}）：锁的寿命由 TTL/续租界定，等一会儿再来是正确策略。
	 * 用 {@code failed} 表达会让"改数据后可重试"的表（预检类码）与"等锁"混在一起，
	 * 模型也就分不清"该改参数"还是"该等"。
	 */
	public static final String CODE_RESOURCE_LOCKED = ErrorCodes.RESOURCE_LOCKED;

	/**
	 * 工具参数里的目标资源不成立（B23L1 批③a / 裁决 5；设计稿 F2 {@code ToolTargetResolver}）：
	 * 如 taskId 不存在/已删除，<b>尚未</b>进入确认门、<b>未</b>执行，结局 {@link #BLOCKED}，
	 * <b>不可重试</b>（{@link #retryable(String)} 恒 false）—— 参数本身就是错的，重试同一个调用
	 * 只会得到同一个结论；换目标重发是模型/用户的动作。
	 *
	 * <p>与 {@link #CODE_RESOURCE_LOCKED} 的分工：两者都挂在 {@code blocked} 下、都不抛异常不断流，
	 * 但一个"等会儿再来就好"（可重试），一个"换目标再来"（不可重试）。
	 */
	public static final String CODE_TARGET_INVALID = ErrorCodes.TARGET_INVALID;

	/**
	 * 错入口批准（入口 kind 校验批）：{@code kind=FRONTEND} 的条目被写成 {@code APPROVED}
	 * （历史假批准 / 旧代码不校验入口时留下的数据）。<b>后端不执行前端工具的桩体、不写执行台账</b>，
	 * 结局 {@link #BLOCKED}、{@code executed=false}，不可重试（要重来得走前端回灌入口）。
	 *
	 * <p>为什么不是 {@code failed}：这不是"执行出错"，而是"这个调用根本不该由后端执行" ——
	 * 与 {@link #CODE_TARGET_INVALID} 同族（blocked 且不可重试），但语义更硬：连执行都不该发生。
	 */
	public static final String CODE_TOOL_KIND_MISMATCH = ErrorCodes.TOOL_KIND_MISMATCH;

	/** "改数据后可重试"的失败码（预检查未通过/过期/预检不通过/预检暂不可用；其余失败码一律不可重试）。 */
	private static final Set<String> RETRYABLE_FAILURE_CODES = Set.of(ErrorCodes.PRECHECK_NOT_PASSED, ErrorCodes.PRECHECK_STALE,
			CODE_PRECHECK_FAILED, CODE_PRECHECK_UNAVAILABLE);

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
	 * <p>表（B23L1 批③a 起）：{@link #BLOCKED} 只有 {@link #CODE_RESOURCE_LOCKED} 为 true
	 * （等锁 = 等前一个动作收尾，锁的寿命由 TTL 界定；{@link #CODE_TARGET_INVALID} 仍是 false ——
	 * 目标不成立时重试同一个调用结论不变）；
	 * {@link #FAILED} 只有"改数据后可重试"的码为 true；其余结局一律 false，语义与批②之前一致。
	 *
	 * @param code 失败码（仅 {@link #FAILED} / {@link #BLOCKED} 分支使用；可为 null）
	 */
	public boolean retryable(String code) {
		if (this == BLOCKED) {
			return CODE_RESOURCE_LOCKED.equals(code);
		}
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
			case PendingToolCall.SKIPPED -> SKIPPED;
			case PendingToolCall.PRECHECK_FAILED, PendingToolCall.SHEET_DISABLED -> FAILED;
			default -> UNKNOWN;
		};
	}

}
