package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.SessionIdGenerator;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@code POST /api/ai/session} 的单点契约：<b>后端发号</b>并以<b>当前身份登记归属</b>。
 *
 * <p>
 * 前端不再生成 sessionId：切换租户/用户后调用本接口取新号，号本身是 long（时间戳+实例位+序列）。
 * 归属登记必须用请求身份（{@code RequestContextUtils}），不得由客户端自报。
 */
class AiSessionControllerTest {

	@Test
	void createsTheSessionNumberOnTheBackendAndRegistersTheCurrentIdentity() {
		long fixedNow = 1_700_000_000_000L;
		SessionIdGenerator generator = new SessionIdGenerator(() -> fixedNow, 7);
		AiSessionService sessions = mock(AiSessionService.class);
		AiSessionController controller = new AiSessionController(generator, sessions);

		ApiResponse<Map<String, Object>> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			response = controller.create();
		}

		long expectedId = fixedNow * 1_000_000L + 7L * 1_000L;
		assertThat(response.isSuccess()).isTrue();
		assertThat(response.getData()).containsEntry("sessionId", expectedId);
		assertThat(response.getData().get("sessionId")).isInstanceOf(Long.class);
		verify(sessions).ensure(String.valueOf(expectedId), 1L, 101L);
	}

}
