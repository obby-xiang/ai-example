package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/ai/health} 的 SSE 投递观测面补口（T3-5 定值回灌，2026-10-09）。
 *
 * <p>
 * 两键的补口依据是压测证据文档 §S2.4 与 §【待裁决】6a：此前投递队列容量 256 与
 * 心跳 2s/5s 在 {@code /health} 均不可观测，
 * 排障只能靠现场推断。本用例只锚<b>存在性与定值</b>（观测面契约），不锚动态量（active/queueDepth）。
 */
class AiControllerHealthSseDeliveryTest {

	private final AiAvailability availability = mock(AiAvailability.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void healthExposesQueueCapacityAndHeartbeatUnderSseDelivery() throws Exception {
		mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sseDelivery.queueCapacity")
					.value(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY))
			.andExpect(jsonPath("$.sseDelivery.heartbeatIntervalMs.suspend").value(2000))
			.andExpect(jsonPath("$.sseDelivery.heartbeatIntervalMs.streaming").value(5000))
			// 既有四键不回归（动态量的值随执行顺序漂移，只断存在性）
			.andExpect(jsonPath("$.sseDelivery.poolSize").exists())
			.andExpect(jsonPath("$.sseDelivery.active").exists())
			.andExpect(jsonPath("$.sseDelivery.queueDepth").exists())
			.andExpect(jsonPath("$.sseDelivery.evicted").exists());
	}

	@Test
	void heartbeatKeysFollowTheirSingleSourceConstants() throws Exception {
		mockMvc.perform(get("/api/ai/health"))
			.andExpect(jsonPath("$.sseDelivery.heartbeatIntervalMs.suspend")
					.value(ConfirmGate.HEARTBEAT_SECONDS * 1000))
			.andExpect(jsonPath("$.sseDelivery.heartbeatIntervalMs.streaming")
					.value(ResilientChatService.STREAMING_HEARTBEAT_MILLIS));
	}

	/**
	 * 2026-10-20 裁决后的观测面：确认门不再超时，/health <b>不再</b>报告 {@code confirmTimeoutSeconds}；
	 * {@code frontendToolTimeoutSeconds} 仍在（只对即时执行类前端工具有意义，
	 * {@code generative_form} 豁免 —— 见 {@code ConfirmGate#suspendWaitSeconds}）。
	 * 值与 {@code application.yml} 现态一致（120）。
	 */
	@Test
	void healthExposesOnlyFrontendToolTimeoutAfterConfirmGateRetirement() throws Exception {
		mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmTimeoutSeconds").doesNotExist())
			.andExpect(jsonPath("$.frontendToolTimeoutSeconds").value(120));
	}

	/**
	 * 作业看门狗观测面（R-96 批 B）：tracked / judgedDeadTotal / judgedTimeoutTotal / lastScanAt
	 * 四键（值由桩钉死，断回显配对，与上面挂起键回显用例同姿态）。
	 */
	@Test
	void healthExposesJobWatchdogMetrics() throws Exception {
		mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobWatchdog.tracked").value(0))
			.andExpect(jsonPath("$.jobWatchdog.judgedDeadTotal").value(0))
			.andExpect(jsonPath("$.jobWatchdog.judgedTimeoutTotal").value(0))
			.andExpect(jsonPath("$.jobWatchdog.lastScanAt").value("2026-10-11T00:00:00Z"));
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求、不读 Redis）。 */
	private AiController controller() {
		when(this.availability.isAvailable()).thenReturn(true);
		ConfirmGate gate = mock(ConfirmGate.class);
		when(gate.frontendToolTimeoutSeconds()).thenReturn(120);
		JobWatchdog jobWatchdog = mock(JobWatchdog.class);
		when(jobWatchdog.metrics()).thenReturn(java.util.Map.of(
				"tracked", 0, "judgedDeadTotal", 0L,
				"judgedTimeoutTotal", 0L, "lastScanAt", "2026-10-11T00:00:00Z"));
		return new AiController(this.availability, provider(), provider(), provider(), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), mock(SessionGate.class), gate, mock(RunStore.class),
				mock(RunRegistry.class), mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				jobWatchdog);
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
