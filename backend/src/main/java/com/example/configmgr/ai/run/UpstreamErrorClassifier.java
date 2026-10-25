package com.example.configmgr.ai.run;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import com.example.configmgr.ai.context.ContextOverflow;
import com.example.configmgr.common.ErrorCodes;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 上游错误的<b>唯一分类点</b>（第 10 项）：先看异常类型与 HTTP 状态，最后才看 finishReason。
 *
 * <h2>为什么不再靠消息子串</h2>
 * 旧实现把 {@code reset} / {@code closed} / {@code premature} 之类的字面量当成"断流"判据 ——
 * 上游换个措辞就漏判，而一句无关的报错里碰巧含 {@code closed} 又会被误判成可重试。
 * 现在：<b>HTTP 状态优先于异常类型，异常类型优先于消息文本</b>；只有两处保留文本判据，
 * 且都是产品语义本身需要措辞识别的特例（上下文越窗、空流聚合异常）。
 *
 * <h2>重试边界（只有一层）</h2>
 * 流式调用链（{@code OpenAiChatModel#stream}）<b>本身不重试</b>：{@code RetryTemplate}
 * 只包住非流式的 {@code internalCall}。因此本分类器只服务 {@code runAttempts} 这一层有界重试，
 * 不引入第二个重试环：
 * <ul>
 * <li>{@code 400/401/403/404/409/422}（以及其它未列 4xx）⇒ 不重试：同一份请求再发一次只会再被拒；</li>
 * <li>{@code 408/429/5xx} 与网络层（{@link IOException}/{@link ResourceAccessException}/
 * {@code WebClientRequestException}/{@link TimeoutException}）⇒ 可重试，次数由
 * {@code app.ai.resilience.max-attempts} 封顶；</li>
 * <li>{@code 429} 额外携带 {@code Retry-After}（秒或 HTTP-date），由退避层取
 * max(指数退避, Retry-After)，并按"未产出 / 无副作用 / 剩余预算"裁剪（见 {@link RetryBackoff}）；</li>
 * <li>类型未知的异常 ⇒ 不重试（未证明是瞬时故障，重试只会拖长用户等待）。</li>
 * </ul>
 *
 * <h2>200 里的错误</h2>
 * 网关/兼容层可能用 HTTP 200 + 错误体返回错误。这类异常带 2xx 状态 ⇒ 归
 * {@link ErrorCodes#UPSTREAM_STREAM_ERROR} 且不重试（协议异常，不是瞬时故障）。
 */
public final class UpstreamErrorClassifier {

	private UpstreamErrorClassifier() {
	}

	/**
	 * 分类结论。
	 *
	 * @param code 终帧/重试帧使用的已登记错误码
	 * @param retryable 是否属于"可重试类别"（还须再过未产出/无副作用/有下次/预算四关）
	 * @param retryAfterMillis 上游 {@code Retry-After}（仅 429 可能非空；null = 没给或不可解析）
	 * @param httpStatus 响应状态（仅状态类异常非空；便于日志/诊断）
	 */
	public record Outcome(String code, boolean retryable, Long retryAfterMillis, Integer httpStatus) {
	}

	/** finishReason 的四种语义（{@code null} = 本轮没收到任何 finishReason）。 */
	public enum Finish {

		/** 正常收尾（含 {@code stop} 与无法识别但上游已声明终态的值）。 */
		STOP,

		/** 达到输出上限：已有正文保留，但必须标记 truncated。 */
		LENGTH,

		/** 被安全策略拦截：明确失败。 */
		CONTENT_FILTER,

		/** 模型要调工具：若这是<b>最后</b>一个 finishReason，说明流没走完。 */
		TOOL_CALLS,

		/** 上游声明了终态，但值不在已知集合内：仍按完成处理（不重试、不截断）。 */
		UNKNOWN;

		/** 是否可作为"本轮完整结束"的终帧。 */
		public boolean terminal() {
			return this != TOOL_CALLS;
		}

		/** 是否应给已有正文打 truncated 标记。 */
		public boolean truncated() {
			return this == LENGTH;
		}

	}

	/**
	 * 把任意异常归类成 {@link Outcome}。
	 *
	 * @param error 上游/管道抛出的异常
	 * @param upstreamEvents 本次尝试已收到的上游事件数（仅用于"空流聚合异常"这一特例）
	 */
	public static Outcome classify(Throwable error, int upstreamEvents) {
		if (error instanceof StreamViolationException violation) {
			return new Outcome(violation.getCode(), violation.brokenStream(), null, null);
		}
		Integer status = httpStatus(error);
		if (status != null) {
			// 越窗是 400 的一个产品语义子类：必须排在"其它 4xx 一律不重试"之前
			if (status == 400 && ContextOverflow.matches(responseBody(error))) {
				return new Outcome(ErrorCodes.CONTEXT_OVERFLOW, false, null, status);
			}
			return byStatus(status, retryAfterMillis(error, status));
		}
		if (ContextOverflow.matches(error.getMessage(), causeMessage(error))) {
			return new Outcome(ErrorCodes.CONTEXT_OVERFLOW, false, null, null);
		}
		if (isNetworkFailure(error)) {
			return new Outcome(ErrorCodes.UPSTREAM_CONNECTION_BROKEN, true, null, null);
		}
		if (hasCause(error, TransientAiException.class)) {
			return new Outcome(ErrorCodes.UPSTREAM_UNAVAILABLE, true, null, null);
		}
		if (hasCause(error, NonTransientAiException.class)) {
			return new Outcome(ErrorCodes.UPSTREAM_REJECTED, false, null, null);
		}
		// 空流特例：一个事件都没给就结束，Spring AI 的流式聚合会以 "conversationId cannot be null"
		// 这类客户端侧异常暴露 —— 它本质就是"缺终帧"（[DONE] 但没有任何 finishReason）。
		if (upstreamEvents == 0 && messageContains(error, "conversationId cannot be null")) {
			return new Outcome(ErrorCodes.UPSTREAM_STREAM_INCOMPLETE, true, null, null);
		}
		return new Outcome(ErrorCodes.UPSTREAM_STREAM_ERROR, false, null, null);
	}

	/** finishReason 归一：大小写/连字符不敏感；空白 = 本轮没收到终帧。 */
	public static Finish finishOf(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		return switch (value) {
			case "stop" -> Finish.STOP;
			case "length" -> Finish.LENGTH;
			case "content_filter" -> Finish.CONTENT_FILTER;
			case "tool_calls", "tool_call" -> Finish.TOOL_CALLS;
			default -> Finish.UNKNOWN;
		};
	}

	// ── 状态 / 头 / 体 ──────────────────────────────────────────────────────

	private static Outcome byStatus(int status, Long retryAfterMillis) {
		if (status == 429) {
			return new Outcome(ErrorCodes.UPSTREAM_RATE_LIMITED, true, retryAfterMillis, status);
		}
		if (status == 408 || (status >= 500 && status <= 599)) {
			return new Outcome(ErrorCodes.UPSTREAM_UNAVAILABLE, true, null, status);
		}
		if (status == 401 || status == 403) {
			return new Outcome(ErrorCodes.UPSTREAM_AUTH_FAILED, false, null, status);
		}
		if (status >= 400 && status <= 499) {
			return new Outcome(ErrorCodes.UPSTREAM_REJECTED, false, null, status);
		}
		// 1xx/2xx/3xx 却以异常形态出现：200-error / 协议异常，不是瞬时故障
		return new Outcome(ErrorCodes.UPSTREAM_STREAM_ERROR, false, null, status);
	}

	/** 异常链里的 HTTP 状态（响应式 {@code WebClient} 与阻塞式 {@code RestClient} 同口径）。 */
	static Integer httpStatus(Throwable error) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			if (cause instanceof WebClientResponseException web) {
				return web.getStatusCode().value();
			}
			if (cause instanceof RestClientResponseException rest) {
				return rest.getStatusCode().value();
			}
		}
		return null;
	}

	/** {@code Retry-After} 只对 429 有意义（本轮范围），值交给退避层做预算裁剪。 */
	static Long retryAfterMillis(Throwable error, int status) {
		if (status != 429) {
			return null;
		}
		return parseRetryAfter(headerFirst(error, HttpHeaders.RETRY_AFTER), Instant.now());
	}

	/**
	 * {@code Retry-After} 的两种合法形态：非负整数秒、或 HTTP-date。
	 *
	 * @return 毫秒；缺失/非法 = null；过去时刻 = 0（立即重试，不再额外等待）
	 */
	static Long parseRetryAfter(String raw, Instant now) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim();
		try {
			long seconds = Long.parseLong(value);
			return seconds < 0 ? null : Math.multiplyExact(seconds, 1_000L);
		}
		catch (NumberFormatException | ArithmeticException ignored) {
			// 不是秒数：再试 HTTP-date
		}
		try {
			ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
			long millis = at.toInstant().toEpochMilli() - now.toEpochMilli();
			return Math.max(0L, millis);
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static String headerFirst(Throwable error, String name) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			HttpHeaders headers = headers(cause);
			if (headers != null) {
				String value = headers.getFirst(name);
				if (value != null) {
					return value;
				}
			}
		}
		return null;
	}

	private static HttpHeaders headers(Throwable error) {
		if (error instanceof WebClientResponseException web) {
			return web.getHeaders();
		}
		if (error instanceof RestClientResponseException rest) {
			return rest.getResponseHeaders();
		}
		return null;
	}

	private static String responseBody(Throwable error) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			if (cause instanceof WebClientResponseException web) {
				return web.getResponseBodyAsString();
			}
			if (cause instanceof RestClientResponseException rest) {
				return rest.getResponseBodyAsString();
			}
		}
		return null;
	}

	// ── 异常类型判定 ────────────────────────────────────────────────────────

	/**
	 * 网络层失败 = {@link IOException} 族（含 {@code SocketException}/{@code EOFException}/
	 * reactor {@code PrematureCloseException}）、Spring 的 {@link ResourceAccessException}、
	 * 反应式 {@code WebClientRequestException}（本模块不直接依赖 webflux，按类名登记）、
	 * 以及 {@link TimeoutException}。
	 *
	 * <p>按<b>类型/因果链</b>判定，不看消息文本。
	 */
	static boolean isNetworkFailure(Throwable error) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			if (cause instanceof IOException || cause instanceof ResourceAccessException
					|| cause instanceof TimeoutException) {
				return true;
			}
			if (isWebClientRequestException(cause)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isWebClientRequestException(Throwable error) {
		for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
			if ("org.springframework.web.reactive.function.client.WebClientRequestException".equals(type.getName())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
	}

	private static boolean messageContains(Throwable error, String needle) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			String message = cause.getMessage();
			if (message != null && message.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private static String causeMessage(Throwable error) {
		for (Throwable cause = error; cause != null; cause = next(cause)) {
			if (cause.getMessage() != null) {
				return cause.getMessage();
			}
		}
		return null;
	}

	/** 防自环：异常链理论上不应成环，但自定义异常可能把 cause 指向自己。 */
	private static Throwable next(Throwable cause) {
		Throwable parent = cause.getCause();
		return parent == cause ? null : parent;
	}

}
