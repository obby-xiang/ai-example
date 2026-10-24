package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-1：{@code GET /api/ai/events/{runId}} 在<b>快照读失败</b>时必须 503（{@code AI_REDIS_UNAVAILABLE}），不是 500。
 *
 * <p>这条用例走的是<b>真实 {@link RunStore}</b>：Redis 客户端换成一个"读就抛
 * {@link RedisConnectionFailureException}"的桩（真实 Lettuce 断连就是这个类型），
 * {@code get} 的实现照常执行、异常分类照常发生 —— 而不是把 {@code RunStore#get} 本身 mock 成抛。
 *
 * <p>为什么选 {@code /events}：它是 {@code runStore.get(runId)} 的<b>第一跳</b>（`AiController#events`），
 * 前面没有会吞异常的探活；{@code /runs/current} 的第一跳是会话索引读（该读自带降级返回 null），
 * 覆盖到的不是同一条路径。两者共用同一个 {@code get}，分类修好后同时受益。
 */
class AiControllerEventsStateUnavailableTest {

	private static final String RUN_ID = "run-events-down";

	@Test
	void snapshotReadFailureReturns503InsteadOf500() throws Exception {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("boom"));
		RunStore store = new RunStore(redis, new ObjectMapper(), new AiProperties());

		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), store, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), new ObjectMapper(),
				new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		GlobalExceptionHandler handler = new GlobalExceptionHandler(new ErrorBodyFactory(provider()));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();

		mvc.perform(get("/api/ai/events/" + RUN_ID))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_REDIS_UNAVAILABLE))
			.andExpect(jsonPath("$.message").value("AI 服务暂不可用，请稍后重试。"));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
