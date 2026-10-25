package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SecurityEvents;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
 * 复核 Critical-1 的<b>对外可见面</b>：{@code POST /api/ai/runs/{runId}/resume} 必须把
 * "确定不可用"（{@code PROVIDER_GONE}）与"此刻不可知"（{@code STATE_UNKNOWN}）区分成
 * <b>409 / 503</b>，否则前端只会看到一个笼统的失败码。
 *
 * <p>
 * 这一条不是新语义（{@code AiController} 的 switch 早已把 {@code STATE_UNKNOWN} 映射成 503，
 * 与"待决条目读失败"、"取消状态未知"同口径），本类锁的是"B2 修正之后这个区分真的会被用到"：
 * {@link ResumeService} 在刷新失败 / key 不可用时返回 {@code STATE_UNKNOWN}，调用方据此拿到
 * 可重试的 503，而不是被判死的 409。
 */
class AiControllerResumeOutcomeMappingTest {

	private static final String RUN_ID = "run-resume-mapping";

	private final AiProperties properties = new AiProperties();

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private AiController controller;

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.describe()).thenReturn(Map.of("ping", "ok"));
		this.controller = controller();
	}

	@AfterEach
	void tearDown() {
		AiTrace.clear();
	}

	@Test
	void unknownConfigMapsToRetryable503() {
		when(this.resumeService.resume(any(), any())).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.STATE_UNKNOWN,
				Map.of("runId", RUN_ID, "resumed", false, "reason", "STATE_UNKNOWN",
						"code", com.example.configmgr.common.ErrorCodes.AI_REDIS_UNAVAILABLE)));

		ResponseEntity<?> response = this.controller.resume(RUN_ID);

		assertThat(response.getStatusCode()).as("不可知 ⇒ 503（可重试），不是 409（判死）")
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(String.valueOf(response.getBody())).contains("STATE_UNKNOWN");
	}

	@Test
	void confirmedMissingProviderMapsTo409() {
		when(this.resumeService.resume(any(), any())).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.PROVIDER_GONE,
				Map.of("resumed", false, "reason", "PROVIDER_GONE")));

		ResponseEntity<?> response = this.controller.resume(RUN_ID);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(String.valueOf(response.getBody())).contains("PROVIDER_GONE");
	}

	// ── 装配（与 AiControllerDeniedLogTraceTest 同形，只保留本类需要的几项） ─────────

	private AiController controller() {
		AiController built = new AiController(this.availability, providerOf(null),
				providerOf(this.resumeService), providerOf((StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), mock(RunStore.class),
				this.runRegistry, mock(CancellationRegistry.class), this.redisAvailability, executor(),
				new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "contextFieldGate", new ContextFieldGate());
		ReflectionTestUtils.setField(built, "securityEvents", new SecurityEvents());
		return built;
	}

	private static org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor() {
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
				new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(16);
		executor.initialize();
		return executor;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
