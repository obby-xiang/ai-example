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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * <b>Redis 不可用且无法登记 ⇒ 必须返回不可用，不得 200 返回未登记会话</b>。
 *
 * <h2>缺陷形态</h2>
 * 会话登记失败时服务端只记一条 WARN 并回 {@code false}（与"首次登记成功"同义），
 * 发号入口据此回 <b>200</b> + 一个新号。可那个号<b>没有登记</b>：会话归属不存在、
 * 记忆窗口与会话门都在空处，前端却拿着它当有效会话继续对话 —— 所有"按 sessionId 隔离/串行化"
 * 的前提当场失效。
 *
 * <p>本用例只钉一条出口：登记结局为 {@link Registration#UNAVAILABLE} 时，响应必须是
 * <b>503</b>（既有 {@code SESSION_ID_UNAVAILABLE} 码，可重试），且响应体里<b>不得</b>出现任何
 * "看起来像成功"的会话号。
 */
class AiSessionControllerUnavailableTest {

	private static final long TENANT = 2L;

	private static final long USER = 202L;

	@Test
	void anUnregisteredSessionIsNeverReportedAsCreated() {
		IdGenerator generator = mock(IdGenerator.class);
		when(generator.nextId()).thenReturn(1_700_000_000_123L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.register("1700000000123", TENANT, USER)).thenReturn(Registration.UNAVAILABLE);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			response = controller.create();
		}

		assertThat(response.getStatusCode()).as("会话没登记 ⇒ 不得 200").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getCode()).isEqualTo(AiSessionController.SESSION_ID_UNAVAILABLE);
		assertThat(response.getBody().getData()).as("不得返回任何看起来已登记的会话号").isNull();
		verify(sessions).register("1700000000123", TENANT, USER);
		verifyNoMoreInteractions(sessions);
	}

}
