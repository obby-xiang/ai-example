package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.SessionIdGenerator;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/ai/session} 的单点契约：<b>后端发号</b>、<b>十进制字符串为权威值</b>、
 * 并以<b>当前身份登记归属</b>；发号暂不可用映射 <b>503</b>。
 *
 * <p>
 * 前端不再生成 sessionId：切换租户/用户后调用本接口取新号，号本身是 long
 * （时间戳+实例位+序列）。因为量级超过 JS 安全整数，响应必须同时给
 * {@code sessionIdText}（十进制字符串，权威值）；{@code sessionId} 数字字段只为存量兼容，
 * 消费方禁止把它当 JSON number 直接使用。
 */
class AiSessionControllerTest {

	@Test
	void createsTheSessionNumberOnTheBackendAndRegistersTheCurrentIdentity() {
		long fixedNow = 1_700_000_000_000L;
		SessionIdGenerator generator = new SessionIdGenerator(() -> fixedNow, 7);
		AiSessionService sessions = mock(AiSessionService.class);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		long expectedId = fixedNow * 1_000_000L + 7L * 1_000L;
		ApiResponse<Map<String, Object>> body = response.getBody();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body).isNotNull();
		assertThat(body.isSuccess()).isTrue();
		assertThat(body.getData()).containsEntry("sessionId", expectedId);
		assertThat(body.getData()).containsEntry("sessionIdText", Long.toString(expectedId));
		assertThat(body.getData().get("sessionIdText")).isInstanceOf(String.class);
		assertThat(Long.parseLong((String) body.getData().get("sessionIdText")))
			.as("sessionIdText 必须与 long 值逐位一致（JSON number 不是权威值）")
			.isEqualTo(expectedId);
		verify(sessions).ensure(String.valueOf(expectedId), 1L, 101L);
	}

	@Test
	void aCollidingNumberIsRetriedOnceWithAFreshNumber() {
		SessionIdGenerator generator = mock(SessionIdGenerator.class);
		when(generator.next()).thenReturn(1_700_000_000_000_000_000L, 1_700_000_000_000_001_000L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.ensure("1700000000000000000", 1L, 101L)).thenReturn(true);
		when(sessions.ensure("1700000000000001000", 1L, 101L)).thenReturn(false);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isTrue();
		assertThat(response.getBody().getData()).containsEntry("sessionId", 1_700_000_000_000_001_000L);
		verify(sessions).ensure("1700000000000000000", 1L, 101L);
		verify(sessions).ensure("1700000000000001000", 1L, 101L);
	}

	@Test
	void aPersistentCollisionReturns503WithoutOverwritingTheExistingSession() {
		SessionIdGenerator generator = mock(SessionIdGenerator.class);
		when(generator.next()).thenReturn(1_700_000_000_000_000_000L, 1_700_000_000_000_001_000L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.ensure("1700000000000000000", 1L, 101L)).thenReturn(true);
		when(sessions.ensure("1700000000000001000", 1L, 101L)).thenReturn(true);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).as("连续撞号不得静默成功，必须 503 可重试")
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getCode()).isEqualTo(AiSessionController.SESSION_ID_UNAVAILABLE);
		verify(sessions).ensure("1700000000000000000", 1L, 101L);
		verify(sessions).ensure("1700000000000001000", 1L, 101L);
		verifyNoMoreInteractions(sessions);
	}

	@Test
	void unavailableNumberingIsMappedToServiceUnavailable() {
		SessionIdGenerator generator = mock(SessionIdGenerator.class);
		when(generator.next())
			.thenThrow(new SessionIdGenerator.UnavailableException("同一毫秒内会话发号容量已用尽", true));
		AiSessionService sessions = mock(AiSessionService.class);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).as("发号暂不可用必须是 503（可重试），不是 500")
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getCode()).isEqualTo("SESSION_ID_UNAVAILABLE");
		verifyNoInteractions(sessions);
	}

}
