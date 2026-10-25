package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorCodes;

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
 * 由 {@link UpstreamErrorClassifier} 先按异常类型/HTTP 状态归类，{@code ResilientChatService}
 * 再包成本类（携带 {@code UPSTREAM_CONNECTION_BROKEN} 等 code）。</li>
 * </ul>
 */
@Getter
public class StreamViolationException extends RuntimeException {

	/** 取消（用户/前端主动取消，终态为 {@code done{cancelled:true}}，永不重试）。 */
	public static final String CANCELLED = ErrorCodes.AI_RUN_CANCELLED;

	/** 首包静默超时（{@code app.ai.resilience.first-byte-timeout}）。 */
	public static final String FIRST_BYTE_TIMEOUT = ErrorCodes.UPSTREAM_FIRST_BYTE_TIMEOUT;

	/** 事件间静默超时（{@code app.ai.resilience.inter-event-timeout}）。 */
	public static final String SILENT_TIMEOUT = ErrorCodes.UPSTREAM_SILENT_TIMEOUT;

	/** 单轮总预算耗尽（{@code app.ai.resilience.total-budget}）。 */
	public static final String TOTAL_BUDGET = ErrorCodes.TOTAL_BUDGET_EXCEEDED;

	/** 缺终帧：流"正常"结束却从未出现带 finishReason 的终帧（TCP 静默 FIN 的典型形态）。 */
	public static final String INCOMPLETE = ErrorCodes.UPSTREAM_STREAM_INCOMPLETE;

	/** 连接被中断（premature close / reset / EOF）。 */
	public static final String CONNECTION_BROKEN = ErrorCodes.UPSTREAM_CONNECTION_BROKEN;

	/**
	 * 运行时基础设施状态未知（N-1）：典型是取消标志读不出来。它<b>不是</b>用户取消，
	 * 不得走 {@link #CANCELLED} 的取消收尾/ALREADY_CANCELLED；按普通可重试错误收尾。
	 */
	public static final String STATE_UNAVAILABLE = ErrorCodes.AI_REDIS_UNAVAILABLE;

	/** 其它上游流错误。 */
	public static final String STREAM_ERROR = ErrorCodes.UPSTREAM_STREAM_ERROR;

	/**
	 * 上游拒绝：上下文超出模型窗口（L3 丢到 pin 下限后仍超；映射依据 = 响应体措辞）。
	 *
	 * <p>刻意<b>不</b>进 {@link #brokenStream()}：同一份超大请求重发一次只会再超一次，
	 * 重试只会浪费预算并拖长用户等待；它需要的是人工/后续轮次的压缩，不是重连。
	 */
	public static final String CONTEXT_OVERFLOW = ErrorCodes.CONTEXT_OVERFLOW;

	private final String code;

	public StreamViolationException(String code, String message) {
		super(message);
		this.code = code;
	}

	public StreamViolationException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	/**
	 * 是否为"断流"类失败（可进入重试判定的那些）。
	 *
	 * <p>第 10 项起 {@link #STREAM_ERROR} <b>不再</b>算可重试：它是"未定性的异常"的兜底码，
	 * 而重试只给 408/429/5xx/网络与"已确认断流"的几类。真正的断流在
	 * {@code UpstreamErrorClassifier} 里按异常类型归到 {@link #CONNECTION_BROKEN}/
	 * {@link #INCOMPLETE}，不会落到这个兜底码上。
	 */
	public boolean brokenStream() {
		return FIRST_BYTE_TIMEOUT.equals(this.code) || SILENT_TIMEOUT.equals(this.code)
				|| INCOMPLETE.equals(this.code) || CONNECTION_BROKEN.equals(this.code)
				|| STATE_UNAVAILABLE.equals(this.code);
	}

}
