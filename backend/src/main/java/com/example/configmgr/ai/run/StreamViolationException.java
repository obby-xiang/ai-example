package com.example.configmgr.ai.run;

import lombok.Getter;

/**
 * 一条流被"判定为断流/取消/超预算"时抛出（S4.2 §2 韧性棒的统一失败载体）。
 *
 * <p>
 * 与普通异常的区别在于它带 <b>code</b>：重试三条件、终帧错误码、
 * "断流发生在工具副作用之后"等判定都以此为唯一判据，不靠异常消息文本匹配。
 * 两类来源：
 * <ul>
 * <li><b>看门狗判定</b>（{@link StreamWatchdog}）：首包超时 / 事件间静默 / 总预算 / 取消；</li>
 * <li><b>管道自身报错</b>（上游连接被重置、TCP 静默 FIN 后过早结束等）：
 * 由 {@code ResilientChatService} 归类成 {@code UPSTREAM_CONNECTION_BROKEN} 等 code 后再抛。</li>
 * </ul>
 */
@Getter
public class StreamViolationException extends RuntimeException {

	/** 取消（用户/前端主动取消，终态为 {@code done{cancelled:true}}，永不重试）。 */
	public static final String CANCELLED = "AI_RUN_CANCELLED";

	/** 首包静默超时（{@code app.ai.resilience.first-byte-timeout}）。 */
	public static final String FIRST_BYTE_TIMEOUT = "UPSTREAM_FIRST_BYTE_TIMEOUT";

	/** 事件间静默超时（{@code app.ai.resilience.inter-event-timeout}）。 */
	public static final String SILENT_TIMEOUT = "UPSTREAM_SILENT_TIMEOUT";

	/** 单轮总预算耗尽（{@code app.ai.resilience.total-budget}）。 */
	public static final String TOTAL_BUDGET = "TOTAL_BUDGET_EXCEEDED";

	/** 缺终帧：流"正常"结束却从未出现带 finishReason 的终帧（TCP 静默 FIN 的典型形态）。 */
	public static final String INCOMPLETE = "UPSTREAM_STREAM_INCOMPLETE";

	/** 连接被中断（premature close / reset / EOF）。 */
	public static final String CONNECTION_BROKEN = "UPSTREAM_CONNECTION_BROKEN";

	/** 其它上游流错误。 */
	public static final String STREAM_ERROR = "UPSTREAM_STREAM_ERROR";

	private final String code;

	public StreamViolationException(String code, String message) {
		super(message);
		this.code = code;
	}

	public StreamViolationException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	/** 是否为"断流"类失败（可进入重试判定的那些）。 */
	public boolean brokenStream() {
		return FIRST_BYTE_TIMEOUT.equals(this.code) || SILENT_TIMEOUT.equals(this.code)
				|| INCOMPLETE.equals(this.code) || CONNECTION_BROKEN.equals(this.code)
				|| STREAM_ERROR.equals(this.code);
	}

}
