package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/ai/session} 的单点契约：<b>后端发号</b>、<b>十进制字符串为权威值</b>、
 * 并以<b>当前身份登记归属</b>；撞上已有会话时返回 <b>503</b>，不换号重发、不覆盖旧记录。
 *
 * <p>
 * 前端不再生成 sessionId：切换租户/用户后调用本接口取新号。因为号值量级可能超过 JS
 * 安全整数，响应必须同时给 {@code sessionIdText}（十进制字符串，权威值）；{@code sessionId}
 * 数字字段只为存量兼容，消费方禁止把它当 JSON number 直接使用。
 */
class AiSessionControllerTest {

	@Test
	void createsTheSessionNumberOnTheBackendAndRegistersTheCurrentIdentity() {
		IdGenerator generator = mock(IdGenerator.class);
		when(generator.nextId()).thenReturn(1_700_000_000_123L);
		AiSessionService sessions = mock(AiSessionService.class);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		long expectedId = 1_700_000_000_123L;
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
		verify(generator).nextId();
		verify(sessions).ensure(Long.toString(expectedId), 1L, 101L);
		verifyNoMoreInteractions(generator, sessions);
	}

	@Test
	void aCollidingNumberReturns503WithoutRetryOrOverwrite() {
		IdGenerator generator = mock(IdGenerator.class);
		when(generator.nextId()).thenReturn(1_700_000_000_123L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.ensure("1700000000123", 1L, 101L)).thenReturn(true);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).as("撞号不得静默成功，按既有 503 返回")
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getCode()).isEqualTo(AiSessionController.SESSION_ID_UNAVAILABLE);
		verify(generator).nextId();
		verify(sessions).ensure("1700000000123", 1L, 101L);
		verifyNoMoreInteractions(generator, sessions);
	}

}
