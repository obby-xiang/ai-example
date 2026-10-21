package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批③ {@code GET /api/ai/health} 的两个 additive 键（矩阵 B3-17/18）。
 *
 * <p>
 * 面板的两条策略都由它们驱动：{@code allowedParams} 决定参数 popover 是否整组置灰（P3），
 * {@code defaultParams} 决定 popover 的初值（P2 的"恢复默认"回到这里给的三值）。
 *
 * <p>缺省口径与设置页同一把尺：{@code allow.userParams} <b>未设置 = 允许</b>
 * （设置页的 {@code allowUserParams !== false}），只有显式 {@code false} 才锁。
 */
class AiControllerHealthParamsTest {

	private final AiConfigService configService = mock(AiConfigService.class);

	@Test
	void healthCarriesAllowedParamsAndDefaults() {
		when(this.configService.defaults()).thenReturn(
				new AiConfigService.Defaults("deepseek-flash", 0.3d, 0.8d, 1024, Boolean.FALSE, 12L));

		Map<String, Object> body = health();

		assertThat(body).containsEntry("allowedParams", false);
		assertThat(body.get("defaultParams")).isInstanceOf(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> defaults = (Map<String, Object>) body.get("defaultParams");
		assertThat(defaults).containsEntry("temperature", 0.3d)
			.containsEntry("topP", 0.8d)
			.containsEntry("maxTokens", 1024);
	}

	@Test
	void allowedParamsDefaultsToTrueWhenUnset() {
		when(this.configService.defaults()).thenReturn(
				new AiConfigService.Defaults(null, null, null, null, null, 3L));

		Map<String, Object> body = health();

		assertThat(body).containsEntry("allowedParams", true);
		@SuppressWarnings("unchecked")
		Map<String, Object> defaults = (Map<String, Object>) body.get("defaultParams");
		assertThat(defaults).containsEntry("temperature", null)
			.containsEntry("topP", null)
			.containsEntry("maxTokens", null);
	}

	// ── 装配（照 AiControllerHealthSseDeliveryTest 的最小面） ──────────────────

	private Map<String, Object> health() {
		return controller().health().getBody();
	}

	private AiController controller() {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		ConfirmGate gate = mock(ConfirmGate.class);
		when(gate.frontendToolTimeoutSeconds()).thenReturn(120);
		JobWatchdog jobWatchdog = mock(JobWatchdog.class);
		when(jobWatchdog.metrics()).thenReturn(Map.of("tracked", 0));
		AiController built = new AiController(availability, provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), gate,
				mock(RunStore.class), mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), new ObjectMapper(),
				new AiProperties(), mock(Environment.class), jobWatchdog);
		ReflectionTestUtils.setField(built, "aiConfigService", this.configService);
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
