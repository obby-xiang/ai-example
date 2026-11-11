package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
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
 * <p>持有者可知时返回进行中轮的 {@code runId}，调用方据此经既有 events 客户端重挂；
 * 不重复返回能够由 runId 推导的 reattach 路径。{@code degraded} 事实位保持不变。
 */
class ChatSessionBusyBodyTest {

	private static final String SESSION = "s-busy-body";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

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

	@Test
	@SuppressWarnings("unchecked")
	void busyResponseCarriesTheRunIdWithoutReattachPath() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(new SessionGate.Busy("run-holder", true));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).isNotNull();
		assertThat(body).containsEntry("code", "SESSION_BUSY")
				.containsEntry("sessionId", SESSION)
				.containsEntry("runId", "run-holder")
				.containsEntry("degraded", true)
				.doesNotContainKey("reattach");
	}

	@Test
	@SuppressWarnings("unchecked")
	void duplicateClientRequestCarriesRecoveryIdentityWithoutReattachPath() {
		com.example.configmgr.ai.run.ClientRequestRegistry clientRequests =
				mock(com.example.configmgr.ai.run.ClientRequestRegistry.class);
		ReflectionTestUtils.setField(this.controller, "clientRequests", clientRequests);
		when(clientRequests.boundRunId(SESSION, "req-1")).thenReturn("run-duplicate");

		ResponseEntity<?> response = this.controller.chat(
				new AiChatRequest(SESSION, "你好", null, null, null, null, "req-1"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).isNotNull();
		assertThat(body).containsEntry("code", "SESSION_BUSY")
				.containsEntry("runId", "run-duplicate")
				.containsEntry("duplicateRequest", true)
				.containsEntry("clientRequestId", "req-1")
				.doesNotContainKey("reattach");
	}

	/**
	 * 判据（反例，防"一律带 runId"式过度修复）：不忙时不得出现这些键 —— 只有在被占时才有接管线索。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void nonBusyRequestCarriesNoBusyHints() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(null);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null));

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
