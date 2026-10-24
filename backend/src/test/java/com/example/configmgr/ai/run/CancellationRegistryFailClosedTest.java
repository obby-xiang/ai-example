package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A5：取消标志读失败不得折成"未取消"。
 *
 * <p>跨实例取消的权威态只在 Redis。读不出来时，"未取消"不是已知事实而是<b>未知</b>：
 * 继续按未取消跑到预算是"取消丢失"（S7 A5）。本用例锁三件事：
 * <ol>
 * <li>读失败 ⇒ {@link CancellationRegistry.CancelState#UNKNOWN}，且 {@code isCancelled} 拒绝作答；</li>
 * <li>本进程已收到取消（local flag）时，Redis 故障不影响 {@code true}（保留加速/降级通道）；</li>
 * <li>降级计数可观测。</li>
 * </ol>
 */
class CancellationRegistryFailClosedTest {

	private static final String RUN_ID = "run-cancel-a5";

	private final AiProperties properties = new AiProperties();

	@Test
	void readFailureIsUnknownAndRefusesToAnswerNotCancelled() {
		CancellationRegistry registry = registryWithBrokenRedis();
		registry.register(RUN_ID);
		long before = RedisDegradation.count(RedisDegradation.A5_CANCEL_READ);

		assertThat(registry.cancelState(RUN_ID)).as("读不出来 = 未知，不是未取消")
			.isEqualTo(CancellationRegistry.CancelState.UNKNOWN);
		assertThatThrownBy(() -> registry.isCancelled(RUN_ID))
			.as("布尔口不得把未知折算成 false")
			.isInstanceOf(CancellationRegistry.CancellationUnknownException.class);
		assertThat(RedisDegradation.count(RedisDegradation.A5_CANCEL_READ)).isGreaterThan(before);
	}

	@Test
	void localCancellationStillWinsDuringARedisOutage() {
		// 用可用的 Redis 让 cancel() 正常置位 local flag，再把读取打断
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenReturn(null);
		CancellationRegistry registry = new CancellationRegistry(redis, this.properties);
		registry.register(RUN_ID);
		registry.cancel(RUN_ID, "inst-a");
		when(values.get(anyString())).thenThrow(new IllegalStateException("模拟 Redis 读失败"));

		assertThat(registry.cancelState(RUN_ID)).isEqualTo(CancellationRegistry.CancelState.CANCELLED);
		assertThat(registry.isCancelled(RUN_ID)).as("本地已取消是确定的，不依赖 Redis").isTrue();
	}

	@Test
	void successfulReadOfAnAbsentFlagIsNotCancelled() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenReturn(null);
		CancellationRegistry registry = new CancellationRegistry(redis, this.properties);
		registry.register(RUN_ID);

		assertThat(registry.cancelState(RUN_ID)).isEqualTo(CancellationRegistry.CancelState.NOT_CANCELLED);
		assertThat(registry.isCancelled(RUN_ID)).isFalse();
	}


	@Test
	void unknownKeepsTheOriginalCause() {
		IllegalStateException root = new IllegalStateException("连接超时（根因）");
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenThrow(root);
		CancellationRegistry registry = new CancellationRegistry(redis, this.properties);
		registry.register(RUN_ID);

		assertThatThrownBy(() -> registry.isCancelled(RUN_ID))
			.satisfies(ex -> assertThat(ex.getCause()).as("不得丢根因").isSameAs(root));
	}

	@Test
	void backoffReturnsUnknownWithoutPollingAgain() {
		CancellationRegistry registry = registryWithBrokenRedis();
		registry.register(RUN_ID);

		long unknownBefore = RedisDegradation.count(RedisDegradation.A5_CANCEL_UNKNOWN);
		assertThat(registry.cancelState(RUN_ID)).isEqualTo(CancellationRegistry.CancelState.UNKNOWN);
		long pollsAfterFirst = registry.polls();
		assertThat(registry.cancelState(RUN_ID)).as("5s 退避期内仍是未知，而不是未取消")
			.isEqualTo(CancellationRegistry.CancelState.UNKNOWN);
		assertThat(registry.polls()).as("退避期不访问 Redis（也不逐个等超时）").isEqualTo(pollsAfterFirst);
		assertThat(RedisDegradation.count(RedisDegradation.A5_CANCEL_UNKNOWN))
			.as("退避期返回 UNKNOWN 也必须计数").isGreaterThan(unknownBefore + 1);
	}

	@Test
	void cancelStateDoesNotCreateALocalFlagForAnUnregisteredRun() {
		FakeLeaseRedis fake = new FakeLeaseRedis();
		fake.strings.put(CancellationRegistry.CANCEL_PREFIX + RUN_ID, "inst-a");
		CancellationRegistry registry = new CancellationRegistry(fake.redis, this.properties);

		assertThat(registry.cancelState(RUN_ID)).isEqualTo(CancellationRegistry.CancelState.CANCELLED);
		assertThat(registry.registeredRuns()).as("清单/扫描观察到的轮不得建永久 sticky 本地 flag").isZero();
	}

	private CancellationRegistry registryWithBrokenRedis() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenThrow(new IllegalStateException("模拟 Redis 不可用"));
		return new CancellationRegistry(redis, this.properties);
	}

}
