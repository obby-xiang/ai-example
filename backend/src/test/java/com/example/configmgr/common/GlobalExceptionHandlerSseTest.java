package com.example.configmgr.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>Q8② 回归</b>：全局异常处理器<b>不再对 SSE 响应二次写 JSON 体</b>。
 *
 * <p>
 * 缺陷形态（S5b §6.1 实测）：SSE 响应的内容类型在开流时已固定为 {@code text/event-stream}，
 * 客户端断开后写帧产生的 IOException 会被 Spring 作为异步请求的错误结果再次派发到
 * DispatcherServlet；处理器若照旧回 {@link ApiResponse}（JSON），必然抛
 * {@code HttpMessageNotWritableException: No converter for [... ApiResponse] with preset
 * Content-Type 'text/event-stream'} —— 每轮 E2E 刷出约 7 条日志噪音，且这一条根本送达不了
 * 已断开的客户端。
 *
 * <p>
 * kill：把 {@link GlobalExceptionHandler#handleGeneral} 的 {@code isStreamingResponse} 分支去掉
 * ⇒ 前两条用例拿到带体的响应，二次写 JSON 的窗口重新打开。
 */
class GlobalExceptionHandlerSseTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
			new ErrorBodyFactory(new org.springframework.beans.factory.support.DefaultListableBeanFactory()
					.getBeanProvider(org.springframework.context.MessageSource.class)));

	@Test
	void sseResponseIsNotRewrittenWithAJsonBody() {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getContentType()).thenReturn(MediaType.TEXT_EVENT_STREAM_VALUE);

		ResponseEntity<?> handled = this.handler.handleGeneral(
				new IOException("你的主机中的软件中止了一个已建立的连接。"), request(), response);

		assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handled.getBody()).as("SSE 响应上不得再写 JSON 体（否则触发二次写异常）").isNull();
	}

	@Test
	void committedResponseIsNotRewrittenWithAJsonBody() {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getContentType()).thenReturn(MediaType.APPLICATION_JSON_VALUE);
		when(response.isCommitted()).thenReturn(true);

		ResponseEntity<?> handled = this.handler.handleGeneral(new IllegalStateException("响应已提交"), request(),
				response);

		assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handled.getBody()).as("已提交的响应同样不能再写体").isNull();
	}

	@Test
	void plainRequestStillGetsTheApiResponseBody() {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getContentType()).thenReturn(null);

		ResponseEntity<?> handled = this.handler.handleGeneral(new IllegalStateException("数据库炸了"), request(),
				response);

		assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(handled.getBody()).isInstanceOf(ApiResponse.class);
		ApiResponse<?> body = (ApiResponse<?>) handled.getBody();
		assertThat(body.isSuccess()).isFalse();
		assertThat(body.getCode()).isEqualTo(ErrorCodes.INTERNAL_ERROR);
		assertThat(body.getMessage()).isEqualTo("服务器内部错误，请稍后重试。");
		assertThat(body.getMessage()).doesNotContain("数据库炸了");
	}

	/** A10 R-13：异常原文只进日志，不上用户 message。 */
	@Test
	void internalErrorLogsTheOriginalExceptionButDoesNotEchoIt() {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			HttpServletResponse response = mock(HttpServletResponse.class);
			when(response.getContentType()).thenReturn(null);

			ResponseEntity<?> handled = this.handler.handleGeneral(new IllegalStateException("数据库炸了"), request(),
					response);

			ApiResponse<?> body = (ApiResponse<?>) handled.getBody();
			assertThat(body.getMessage()).doesNotContain("数据库炸了");
			assertThat(appender.list)
					.as("异常原文必须仍可在日志里定位")
					.anySatisfy(event -> assertThat(event.getThrowableProxy().getMessage()).contains("数据库炸了"));
		} finally {
			logger.detachAppender(appender);
		}
	}


	@Test
	void aiStateUnavailableMapsTo503Not400() {
		ResponseEntity<?> handled = this.handler.handleAiStateUnavailable(
				new AiStateUnavailableException("Redis 读失败（内部细节）"), request());

		assertThat(handled.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		ApiResponse<?> body = (ApiResponse<?>) handled.getBody();
		assertThat(body.getCode()).isEqualTo(ErrorCodes.AI_REDIS_UNAVAILABLE);
		assertThat(body.getMessage()).as("不得把基础设施异常原文当用户面文案")
				.isEqualTo("AI 服务暂不可用，请稍后重试。");
	}

	private static HttpServletRequest request() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn("/api/ai/chat");
		return request;
	}

}
