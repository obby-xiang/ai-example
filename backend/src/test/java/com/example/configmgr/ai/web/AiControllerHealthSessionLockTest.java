package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/ai/health} 必须让<b>会话锁降级</b>事后可见（RT-34）。
 *
 * <h2>为什么需要这个用例</h2>
 * {@link SessionGate} 的类注承诺降级"不静默"，并声明 {@code /health} 的接线在
 * {@code AiController}；但 {@code /api/ai/health} 此前只报 {@code sessionLockRenewals}
 * （成功续期次数），<b>{@code lockLost()} 没有任何消费方</b> ⇒ 跨实例互斥已经失效，运维在
 * 聚合面上看到的仍是"一切正常"。
 *
 * <p>降级有<b>两</b>条来源，只看续期次数会漏掉前一条：
 * <ol>
 * <li><b>取门时 Redis 不可用 / 结果不可知</b>：只保留进程内门（{@code degraded=true}），
 * 此时 {@code lockLost()} 仍为 0（没走到续期）；</li>
 * <li><b>续期失败</b>（脚本返回 0 或异常）：除 holder 置降级外还累加 {@code lockLost()}。</li>
 * </ol>
 * 因此 {@code sessionLockDegraded} 必须由"累计丢锁 + 当前仍有降级持有者"共同决定。
 *
 * <p>kill：删掉 {@code body.put("sessionLockDegraded", ...)} 或只按 {@code lockLost() > 0} 计算
 * ⇒ 本类变红（前者两例红，后者 {@code acquireDegradationIsVisibleWhileNoRenewalWasLost} 红）。
 */
class AiControllerHealthSessionLockTest {

	private static final String SESSION = "s-lock-health";

	private final AiAvailability availability = mock(AiAvailability.class);

	/** 健康且从未降级：{@code sessionLockDegraded} 必须是 false（防"恒 true"式过度修复）。 */
	@Test
	void healthyGateReportsNotDegraded() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(new SessionGate(mock(StringRedisTemplate.class),
				new AiProperties()))).build();

		mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionLockDegraded").value(false))
			.andExpect(jsonPath("$.sessionLockLost").value(0))
			// 既有观测面不回归
			.andExpect(jsonPath("$.sessionLockRenewals").value(0))
			.andExpect(jsonPath("$.heldSessions").value(0));
	}

	/**
	 * 取门降级（Redis 异常 ⇒ 只剩进程内门）：{@code lockLost()} 仍是 0，但降级<b>已经发生</b>。
	 *
	 * <p>只看 {@code lockLost()} 的实现会在这里报 {@code false} —— 即"跨实例互斥已失效"这一事实
	 * 在健康面上继续被静默。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void acquireDegradationIsVisibleWhileNoRenewalWasLost() throws Exception {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenThrow(new RedisConnectionFailureException("会话锁服务不可用（模拟）"));
		SessionGate gate = new SessionGate(redis, new AiProperties());
		assertThat(gate.acquire(SESSION, "run-1")).as("降级形态：只保留进程内门").isNull();
		assertThat(gate.heldSessions()).as("降级仍然占住了进程内门").isEqualTo(1);
		assertThat(gate.lockLost()).as("取门降级不经过续期，故累计丢锁仍为 0").isZero();

		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(gate)).build();

		mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionLockDegraded").value(true))
			.andExpect(jsonPath("$.sessionLockLost").value(0))
			.andExpect(jsonPath("$.heldSessions").value(1));
	}

	/** 续期失败：累计丢锁必须如实回显（可聚合告警的那一半）。 */
	@Test
	void renewalLossIsVisibleAsADegradedCounter() throws Exception {
		SessionGate gate = mock(SessionGate.class);
		when(gate.lockLost()).thenReturn(2L);

		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(gate)).build();

		mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionLockDegraded").value(true))
			.andExpect(jsonPath("$.sessionLockLost").value(2));
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求）。 */
	private AiController controller(SessionGate gate) {
		when(this.availability.isAvailable()).thenReturn(true);
		return new AiController(this.availability, provider(), provider(), provider(), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), gate, mock(ConfirmGate.class), mock(RunStore.class),
				mock(RunRegistry.class), mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class),
				mock(JobWatchdog.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
