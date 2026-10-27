package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * N-3：{@code POST /confirm} 在待决条目读失败时必须 503（AI_REDIS_UNAVAILABLE），
 * 不是 500/400；同时证明 Spring 会选<b>最具体</b>的 {@code AiStateUnavailableException}
 * handler，而不是父类 {@code IllegalStateException → 400}。
 */
class AiControllerConfirmStateUnavailableTest {

	private static final String RUN_ID = "run-confirm";

	private static final String TOOL_CALL_ID = "call-confirm";

	@Test
	void pendingReadFailureReturns503AndSpringPicksTheSpecificHandler() throws Exception {
		RunStore store = mock(RunStore.class);
		when(store.pendingRaw(RUN_ID, TOOL_CALL_ID))
			.thenThrow(new AiStateUnavailableException("模拟 Redis 读待决失败"));
		RunRegistry registry = mock(RunRegistry.class);
		ConfirmGate gate = new ConfirmGate(store, registry, new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), List.of());
		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(org.springframework.ai.chat.memory.ChatMemory.class),
				mock(org.springframework.ai.chat.memory.ChatMemoryRepository.class), mock(SessionGate.class), gate,
				store, registry, mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		// N5：/confirm 的 reason 归一化复用与 /chat 同一个 InputSanitizer（字段注入，standalone 需手填）
		ReflectionTestUtils.setField(controller, "inputSanitizer", new InputSanitizer());
		GlobalExceptionHandler handler = new GlobalExceptionHandler(new ErrorBodyFactory(provider()));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();

		mvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
				.content("{\"runId\":\"" + RUN_ID + "\",\"toolCallId\":\"" + TOOL_CALL_ID
						+ "\",\"approved\":true}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_REDIS_UNAVAILABLE))
			.andExpect(jsonPath("$.message").value("AI 服务暂不可用，请稍后重试。"));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}
}
