package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.AiSessionService.Registration;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/ai/session} 的单点契约：<b>后端发号</b>、<b>十进制字符串为权威值</b>、
 * 并以<b>当前身份登记归属</b>；同身份毫秒撞号时<b>有界重取号</b>，重取到上界仍撞号则回 <b>503</b>
 * （不覆盖旧记录、不死循环）。
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
		when(sessions.register("1700000000123", 1L, 101L)).thenReturn(Registration.CREATED);
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
		verify(sessions).register(Long.toString(expectedId), 1L, 101L);
		verifyNoMoreInteractions(generator, sessions);
	}

	/** 同身份毫秒撞号 = 正常并发：重取一次号拿到新号即成功，旧记录不被覆盖（EXISTED 只续期）。 */
	@Test
	void aCollidingNumberIsRetriedWithinTheBoundAndTheFreshNumberIsReturned() {
		IdGenerator generator = mock(IdGenerator.class);
		when(generator.nextId()).thenReturn(1_700_000_000_123L, 1_700_000_000_124L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.register("1700000000123", 1L, 101L)).thenReturn(Registration.EXISTED);
		when(sessions.register("1700000000124", 1L, 101L)).thenReturn(Registration.CREATED);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).as("重取到的新号登记成功 ⇒ 200").isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).containsEntry("sessionId", 1_700_000_000_124L);
		assertThat(response.getBody().getData()).containsEntry("sessionIdText", "1700000000124");
		verify(generator, times(2)).nextId();
		verify(sessions).register("1700000000123", 1L, 101L);
		verify(sessions).register("1700000000124", 1L, 101L);
		verifyNoMoreInteractions(generator, sessions);
	}

	/** 连续撞号到上界仍不成功 ⇒ 503（有界：不得无限重取，也不得静默合并两条会话）。 */
	@Test
	void everyCollidingAttemptEndsIn503AfterTheBoundedNumberOfPulls() {
		IdGenerator generator = mock(IdGenerator.class);
		when(generator.nextId()).thenReturn(1_700_000_000_123L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.register("1700000000123", 1L, 101L)).thenReturn(Registration.EXISTED);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).as("重取到上界仍撞号 ⇒ 503")
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getCode()).isEqualTo(AiSessionController.SESSION_ID_UNAVAILABLE);
		assertThat(response.getBody().getData()).as("不得返回未登记的号").isNull();
		verify(generator, times(AiSessionController.SESSION_ID_ATTEMPTS)).nextId();
		verify(sessions, times(AiSessionController.SESSION_ID_ATTEMPTS)).register("1700000000123", 1L, 101L);
		verifyNoMoreInteractions(generator, sessions);
	}

}
