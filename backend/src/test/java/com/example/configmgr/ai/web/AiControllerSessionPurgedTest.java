package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已销毁会话不能以同 id 静默开新轮：{@code /chat} 在创建 run 前查 purge guard。
 *
 * <p>三态：命中 → 410 {@code SESSION_PURGED}（提示换 sessionId）；未命中 → 原流程；
 * 守卫读失败 → 503（fail-closed，不能当"未销毁"放行）。
 */
class AiControllerSessionPurgedTest {

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final RunStore runStore = mock(RunStore.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	@Test
	void purgedSessionGets410AndDoesNotAcquireOrCreateRun() throws Exception {
		SessionPurgeGuard guard = mock(SessionPurgeGuard.class);
		when(guard.isArmed("s-1")).thenReturn(true);
		AiController controller = controller(guard);

		MockMvcBuilders.standaloneSetup(controller).build()
			.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sessionId\":\"s-1\",\"message\":\"你好\"}"))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("SESSION_PURGED"))
			.andExpect(jsonPath("$.sessionId").value("s-1"));

		verify(this.sessionGate, never()).acquire(anyString(), anyString());
		verify(this.runStore, never()).create(any(), any(), any(), any(), any());
		verify(this.service, never()).chat(any(), any(), any());
	}

	@Test
	void guardReadFailureIs503FailClosed() throws Exception {
		SessionPurgeGuard guard = mock(SessionPurgeGuard.class);
		when(guard.isArmed("s-1")).thenThrow(new AiStateUnavailableException("守卫不可读", null));
		AiController controller = controller(guard);

		MockMvcBuilders.standaloneSetup(controller).build()
			.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sessionId\":\"s-1\",\"message\":\"你好\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_REDIS_UNAVAILABLE"));

		verify(this.sessionGate, never()).acquire(anyString(), anyString());
	}

	@Test
	void notPurgedSessionIsNotBlockedByTheGuard() throws Exception {
		SessionPurgeGuard guard = mock(SessionPurgeGuard.class);
		when(guard.isArmed("s-1")).thenReturn(false);
		AiController controller = controller(guard);
		// 让后续既有闸门拒绝（限流），证明守卫放行后确实继续走了原流程。
		AiRateLimiter limiter = (AiRateLimiter) ReflectionTestUtils.getField(controller, "aiRateLimiter");
		when(limiter.check(anyString(), any())).thenReturn(
				new AiRateLimiter.Decision(false, "session", 21, 20, 1000L, false));

		MockMvcBuilders.standaloneSetup(controller).build()
			.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sessionId\":\"s-1\",\"message\":\"你好\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	private AiController controller(SessionPurgeGuard guard) {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.reason()).thenReturn("Redis 不可用");
		when(this.redisAvailability.describeForResponse()).thenReturn(java.util.Map.of("available", false));
		SessionGate gate = this.sessionGate;
		AiRateLimiter limiter = mock(AiRateLimiter.class);
		when(limiter.check(anyString(), any())).thenReturn(
				new AiRateLimiter.Decision(true, "session", 1, 20, 0L, false));
		AiController controller = new AiController(this.availability, providerOf(this.service),
				providerOf((ResumeService) null), providerOf((StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), gate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(controller, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(controller, "aiRateLimiter", limiter);
		ReflectionTestUtils.setField(controller, "sessionPurgeGuard", guard);
		return controller;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
