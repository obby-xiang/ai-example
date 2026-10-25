package com.example.configmgr.ai.run;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>租约设施"自证健康"的探针结论不得比最后一次观测更乐观</b>（写权限 fail-closed 收口）。
 *
 * <h2>缺陷形态</h2>
 * {@link LeaseService#authority(String)} 的"从未持租"分支要靠一次 {@code PING} 自证设施正常，
 * 探针按 1s 节流复用"上一拍结论"。旧实现把这份缓存初始化为 {@code true}，且设施失败时
 * 只清 {@code facilityHealthy}、<b>不清缓存</b>：
 * <ul>
 * <li><b>探针在飞</b>时另一个线程来问（抢不到节流令牌）⇒ 拿到初始的 {@code true}
 *     ⇒ {@link LeaseService.LeaseAuthority#NEVER_HELD} ⇒ {@code RunStore.save} 走普通写。
 *     此刻设施正是<b>已知不可用</b>的（否则不会走到探针）；</li>
 * <li>上一拍探针成功、随后设施失败（{@code facilityHealthy=false}）⇒ 1s 窗口内仍复用那个
 *     {@code true} ⇒ 同一放大。</li>
 * </ul>
 * 两者都把"设施状态不可知/已知异常"变成"从未持租 ⇒ 可写"，正是 fencing 要消灭的形态。
 *
 * <h2>本批口径</h2>
 * 缓存初值 = 未知（{@code false} ⇒ 只读），且<b>任何一次观测到设施失败都同时作废缓存</b>：
 * 节流窗口内复用的结论永远不比最后一次观测更乐观。探针成功仍立即回到 {@code NEVER_HELD}。
 */
class LeaseAuthorityProbeFailClosedTest {

	private static final String KEY = RunStore.runScopedKey(RunStore.LEASE_RUN_PREFIX, "run-probe");

	private final AiProperties properties = new AiProperties();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final LeaseService lease = new LeaseService(this.redis, this.properties, new FakeRenewScheduler(),
			"inst-probe");

	@Test
	void concurrentAuthorityDuringAFailingProbeStaysReadOnly() throws Exception {
		failAllScripts();
		assertThat(this.lease.acquire(KEY, Duration.ofSeconds(30)).outcome())
			.as("一次失败的抢租把设施证伪")
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);

		CountDownLatch probeEntered = new CountDownLatch(1);
		CountDownLatch releaseProbe = new CountDownLatch(1);
		when(this.redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
			probeEntered.countDown();
			if (!releaseProbe.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("探针未放行（用例自身超时）");
			}
			throw new RedisConnectionFailureException("PING 失败（模拟 Redis 断连）");
		});

		AtomicReference<LeaseService.LeaseAuthority> inFlight = new AtomicReference<>();
		Thread prober = new Thread(() -> inFlight.set(this.lease.authority(KEY)), "probe-holder");
		prober.start();
		try {
			assertThat(probeEntered.await(5, TimeUnit.SECONDS)).as("探针已开始").isTrue();

			assertThat(this.lease.authority(KEY).readOnly())
				.as("设施已知不可用且探针尚无结论 ⇒ 不得复用初始的“健康”结论进入可写")
				.isTrue();
		}
		finally {
			releaseProbe.countDown();
			prober.join(5_000L);
		}
		assertThat(inFlight.get()).isEqualTo(LeaseService.LeaseAuthority.UNAVAILABLE);
	}

	@Test
	void facilityFailureInvalidatesThePreviouslyProbedVerdict() {
		// ① 设施失败 → ② 探针成功自证 → ③ 设施再次失败：缓存的"健康"必须被这次失败作废。
		failAllScripts();
		assertThat(this.lease.acquire(KEY, Duration.ofSeconds(30)).outcome())
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);

		pingHealthy();
		assertThat(this.lease.authority(KEY)).as("探针成功 ⇒ 从未持租 ⇒ 普通写")
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);

		failAllScripts();
		assertThat(this.lease.acquire(KEY, Duration.ofSeconds(30)).outcome())
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		// 即便 1s 节流窗口已经过去，重探也只会失败：本用例不会因为机器慢而变成"假绿"。
		pingFailing();

		assertThat(this.lease.authority(KEY).readOnly())
			.as("刚观测到设施失败 ⇒ 不得复用上一拍的“健康”结论进入可写")
			.isTrue();
	}

	private void failAllScripts() {
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenThrow(new RedisConnectionFailureException("Redis 不可用（模拟）"));
	}

	private void pingHealthy() {
		RedisConnection connection = mock(RedisConnection.class);
		when(connection.ping()).thenReturn("PONG");
		when(this.redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
			RedisCallback<?> callback = invocation.getArgument(0);
			return callback.doInRedis(connection);
		});
	}

	private void pingFailing() {
		when(this.redis.execute(any(RedisCallback.class)))
			.thenThrow(new RedisConnectionFailureException("PING 失败（模拟 Redis 断连）"));
	}

}
