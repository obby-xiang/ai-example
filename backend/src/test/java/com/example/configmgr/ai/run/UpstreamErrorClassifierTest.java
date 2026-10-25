package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorCodes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游错误分类（第 10 项）的纯函数矩阵：<b>先看异常类型与 HTTP 状态</b>，不看
 * {@code closed}/{@code reset} 之类的消息子串。
 *
 * <p>三条判据各自独立：
 * <ul>
 * <li>{@code 400/401/403/404/409/422}（以及其它未列 4xx）不重试；{@code 408/429/5xx} 有界重试；</li>
 * <li>只有 429 读 {@code Retry-After}（秒或 HTTP-date），值原样交给退避层做预算裁剪；</li>
 * <li>finishReason 四类：{@code length} 可截断、{@code content_filter} 明确失败、
 * {@code tool_calls} 视为不完整、未知值按上游已声明终态处理。</li>
 * </ul>
 */
class UpstreamErrorClassifierTest {

	// ── HTTP 状态矩阵 ────────────────────────────────────────────────────────

	@ParameterizedTest(name = "HTTP {0} → {1}（retryable={2}）")
	@CsvSource({
			"400, UPSTREAM_REJECTED, false",
			"401, UPSTREAM_AUTH_FAILED, false",
			"403, UPSTREAM_AUTH_FAILED, false",
			"404, UPSTREAM_REJECTED, false",
			"409, UPSTREAM_REJECTED, false",
			"422, UPSTREAM_REJECTED, false",
			"408, UPSTREAM_UNAVAILABLE, true",
			"429, UPSTREAM_RATE_LIMITED, true",
			"500, UPSTREAM_UNAVAILABLE, true",
			"502, UPSTREAM_UNAVAILABLE, true",
			"503, UPSTREAM_UNAVAILABLE, true",
			"200, UPSTREAM_STREAM_ERROR, false"
	})
	void httpStatusDecidesRetryability(int status, String code, boolean retryable) {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(http(status), 0);

		assertThat(outcome.code()).isEqualTo(code);
		assertThat(outcome.retryable()).isEqualTo(retryable);
		assertThat(outcome.httpStatus()).isEqualTo(status);
	}

	@Test
	void contextOverflowStaysItsOwnCodeEvenThoughItIsAnHttp400() {
		WebClientResponseException error = WebClientResponseException.create(400, "Bad Request",
				HttpHeaders.EMPTY,
				"{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens\"}}"
					.getBytes(StandardCharsets.UTF_8),
				StandardCharsets.UTF_8);

		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(error, 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.CONTEXT_OVERFLOW);
		assertThat(outcome.retryable()).isFalse();
	}

	// ── Retry-After：只对 429 生效 ───────────────────────────────────────────

	@Test
	void rateLimitCarriesRetryAfterSeconds() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(http(429, "2"), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_RATE_LIMITED);
		assertThat(outcome.retryable()).isTrue();
		assertThat(outcome.retryAfterMillis()).isEqualTo(2_000L);
	}

	@Test
	void rateLimitWithoutRetryAfterStillRetriesWithNormalBackoff() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(http(429), 0);

		assertThat(outcome.retryable()).isTrue();
		assertThat(outcome.retryAfterMillis()).isNull();
	}

	@Test
	void rateLimitWithUnparseableRetryAfterFallsBackToBackoff() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(http(429, "soon"), 0);

		assertThat(outcome.retryAfterMillis()).isNull();
	}

	@Test
	void retryAfterIsIgnoredForServerErrors() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(http(503, "2"), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE);
		assertThat(outcome.retryAfterMillis()).as("本轮只要求 429 遵守 Retry-After").isNull();
	}

	@Test
	void retryAfterParsesHttpDateAndClampsPastDatesToZero() {
		Instant now = Instant.parse("2026-10-25T00:00:00Z");
		String future = DateTimeFormatter.RFC_1123_DATE_TIME
			.format(ZonedDateTime.ofInstant(now.plusSeconds(7), ZoneOffset.UTC));
		String past = DateTimeFormatter.RFC_1123_DATE_TIME
			.format(ZonedDateTime.ofInstant(now.minusSeconds(30), ZoneOffset.UTC));

		assertThat(UpstreamErrorClassifier.parseRetryAfter(future, now)).isEqualTo(7_000L);
		assertThat(UpstreamErrorClassifier.parseRetryAfter(past, now)).isZero();
		assertThat(UpstreamErrorClassifier.parseRetryAfter(" 0 ", now)).isZero();
		assertThat(UpstreamErrorClassifier.parseRetryAfter("", now)).isNull();
		assertThat(UpstreamErrorClassifier.parseRetryAfter(null, now)).isNull();
	}

	// ── 异常类型矩阵（不靠消息子串） ─────────────────────────────────────────

	@Test
	void ioFailureIsNetworkAndRetriesEvenWithoutAnyKnownMessage() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier.classify(new IOException(), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_CONNECTION_BROKEN);
		assertThat(outcome.retryable()).isTrue();
	}

	@Test
	void socketTimeoutIsNetworkEvenThoughItIsAFlavourOfIo() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier
			.classify(new SocketTimeoutException("Read timed out"), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_CONNECTION_BROKEN);
		assertThat(outcome.retryable()).isTrue();
	}

	@Test
	void springClientResourceAccessIsNetwork() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier
			.classify(new ResourceAccessException("I/O error on POST"), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_CONNECTION_BROKEN);
		assertThat(outcome.retryable()).isTrue();
	}

	@Test
	void wrappedIoFailureIsStillNetwork() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier
			.classify(new IllegalStateException("stream aborted", new IOException("premature close")), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_CONNECTION_BROKEN);
		assertThat(outcome.retryable()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Connection reset by peer", "Response has been closed", "channel closed", "EOF" })
	void messageSubstringsAloneDoNotMakeAnErrorRetryable(String message) {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier
			.classify(new IllegalStateException(message), 0);

		assertThat(outcome.code()).as("未识别的异常类型不因 closed/reset 子串而被重试")
			.isEqualTo(ErrorCodes.UPSTREAM_STREAM_ERROR);
		assertThat(outcome.retryable()).isFalse();
	}

	@Test
	void springAiTransientAndNonTransientExceptionsUseTheirDeclaredSemantics() {
		UpstreamErrorClassifier.Outcome transientOutcome = UpstreamErrorClassifier
			.classify(new TransientAiException("upstream 503"), 0);
		UpstreamErrorClassifier.Outcome nonTransientOutcome = UpstreamErrorClassifier
			.classify(new NonTransientAiException("upstream 400"), 0);

		assertThat(transientOutcome.code()).isEqualTo(ErrorCodes.UPSTREAM_UNAVAILABLE);
		assertThat(transientOutcome.retryable()).isTrue();
		assertThat(nonTransientOutcome.code()).isEqualTo(ErrorCodes.UPSTREAM_REJECTED);
		assertThat(nonTransientOutcome.retryable()).isFalse();
	}

	@Test
	void unknownExceptionIsNotRetried() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier
			.classify(new IllegalStateException("upstream exploded"), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_STREAM_ERROR);
		assertThat(outcome.retryable()).as("只有 408/429/5xx/network 才有界重试").isFalse();
	}

	@Test
	void emptyStreamArtifactStillCountsAsIncomplete() {
		UpstreamErrorClassifier.Outcome outcome = UpstreamErrorClassifier
			.classify(new IllegalStateException("conversationId cannot be null"), 0);

		assertThat(outcome.code()).isEqualTo(ErrorCodes.UPSTREAM_STREAM_INCOMPLETE);
		assertThat(outcome.retryable()).isTrue();
	}

	@Test
	void explicitStreamViolationKeepsItsCodeAndRetryability() {
		StreamViolationException broken = new StreamViolationException(StreamViolationException.CONNECTION_BROKEN,
				"上游连接中断");
		StreamViolationException cancelled = new StreamViolationException(StreamViolationException.CANCELLED,
				"本轮已取消");
		StreamViolationException unknown = new StreamViolationException(StreamViolationException.STREAM_ERROR,
				"未知流错误");

		assertThat(UpstreamErrorClassifier.classify(broken, 3).retryable()).isTrue();
		assertThat(UpstreamErrorClassifier.classify(cancelled, 3).retryable()).isFalse();
		assertThat(UpstreamErrorClassifier.classify(unknown, 3).retryable())
			.as("未定性的 STREAM_ERROR 不再靠兜底码重试").isFalse();
	}

	// ── finishReason 矩阵 ────────────────────────────────────────────────────

	@ParameterizedTest(name = "finishReason={0} → {1}（terminal={2}, truncated={3}）")
	@CsvSource({
			"stop, STOP, true, false",
			"STOP, STOP, true, false",
			"length, LENGTH, true, true",
			"content_filter, CONTENT_FILTER, true, false",
			"content-filter, CONTENT_FILTER, true, false",
			"tool_calls, TOOL_CALLS, false, false",
			"tool_call, TOOL_CALLS, false, false",
			"end_turn, UNKNOWN, true, false",
			"something_new, UNKNOWN, true, false"
	})
	void finishReasonMatrix(String raw, UpstreamErrorClassifier.Finish expected, boolean terminal, boolean truncated) {
		UpstreamErrorClassifier.Finish finish = UpstreamErrorClassifier.finishOf(raw);

		assertThat(finish).isEqualTo(expected);
		assertThat(finish.terminal()).isEqualTo(terminal);
		assertThat(finish.truncated()).isEqualTo(truncated);
	}

	@Test
	void blankFinishReasonMeansNoTerminalWasSeen() {
		assertThat(UpstreamErrorClassifier.finishOf(null)).isNull();
		assertThat(UpstreamErrorClassifier.finishOf("")).isNull();
		assertThat(UpstreamErrorClassifier.finishOf("  ")).isNull();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static WebClientResponseException http(int status, String... retryAfter) {
		HttpHeaders headers = new HttpHeaders();
		if (retryAfter.length > 0) {
			headers.set(HttpHeaders.RETRY_AFTER, retryAfter[0]);
		}
		return WebClientResponseException.create(status, "status-" + status, headers,
				"{\"error\":{\"message\":\"upstream says no\"}}".getBytes(StandardCharsets.UTF_8),
				StandardCharsets.UTF_8);
	}

}
