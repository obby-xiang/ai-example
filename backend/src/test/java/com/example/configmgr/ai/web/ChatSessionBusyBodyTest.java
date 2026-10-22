package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 409 {@code SESSION_BUSY} 的<b>响应体契约</b>（E2E 用例全集第 53 件 · D-08 的后端一半）。
 *
 * <p>蓝本判据：{@code 409 SESSION_BUSY} + **进行中轮的 {@code runId}** + **reattach 端点线索**。
 * 为什么必须新增：既有的 {@code AiControllerDeniedLogTraceTest} 只断言**日志行**里有 runId
 * （{@code turn_denied}），{@code SessionBusyFrameIsolationConformanceTest} 只断言帧隔离；
 * **HTTP 响应体本身**的 `runId` / `reattach` / `degraded` 三个键没有任何用例锁过 ——
 * 而它们正是前端/外部客户端"据此改走 /api/ai/events/{runId}"的唯一依据（ADR-5）。
 *
 * <p>kill：删掉 {@code AiController#chat} 里 409 分支的 {@code body.put("runId", ...)} 或
 * {@code body.put("reattach", ...)}，本类变红（前端拿不到接管线索就只能刷新）。
 */
class ChatSessionBusyBodyTest {

	private static final String SESSION = "s-busy-body";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final AiConfigService aiConfigService = mock(AiConfigService.class);

	private AiController controller;

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runRegistry.of(any())).thenReturn(this.out);
		when(this.sessionGate.acquire(any(), any())).thenReturn(null);
		this.controller = controller();
	}

	/**
	 * 判据：409 体里必须同时有 {@code code=SESSION_BUSY}、占位轮的 {@code runId}、
	 * 可直接使用的 {@code reattach} 路径与 {@code degraded} 事实位。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void busyResponseCarriesTheRunIdAndTheReattachPath() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(new SessionGate.Busy("run-holder", true));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).isNotNull();
		assertThat(body).containsEntry("code", "SESSION_BUSY")
				.containsEntry("sessionId", SESSION)
				// ADR-5：前端据此改走 /api/ai/events/{runId}（不是本次被拒的请求）
				.containsEntry("runId", "run-holder")
				.containsEntry("reattach", "/api/ai/events/run-holder")
				.containsEntry("degraded", true);
	}

	/**
	 * 判据（反例，防"一律带 runId"式过度修复）：不忙时不得出现这些键 —— 只有在被占时才有接管线索。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void nonBusyRequestCarriesNoBusyHints() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(null);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotInstanceOf(Map.class);
	}

	private AiController controller() {
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
				new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(256);
		executor.initialize();
		AiController built = new AiController(this.availability, providerOf(this.service),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), mock(RunStore.class),
				this.runRegistry, mock(CancellationRegistry.class), this.redisAvailability, executor,
				new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "inputDlp", new com.example.configmgr.ai.gate.InputDlp());
		ReflectionTestUtils.setField(built, "contextFieldGate", new com.example.configmgr.ai.gate.ContextFieldGate());
		ReflectionTestUtils.setField(built, "securityEvents", new com.example.configmgr.ai.run.SecurityEvents());
		ReflectionTestUtils.setField(built, "aiConfigService", this.aiConfigService);
		return built;
	}

	private static <T> ObjectProvider<T> providerOf(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getIfUnique() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}
}
