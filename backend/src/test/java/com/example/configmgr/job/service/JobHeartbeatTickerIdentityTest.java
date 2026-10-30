package com.example.configmgr.job.service;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局心跳 ticker（{@code job-heartbeat-ticker}）的身份契约。
 *
 * <p>
 * ticker 是<b>进程级</b>的，没有"单作业记录身份"可言：它承载的是所有在跑 runner 的共享心跳续期。
 * 但 TTL 是 {@code InheritableThreadLocal} 语义 —— 这条守护线程由"第一个 {@code beginRunner} 的
 * 调用线程"创建，会把该调用者的身份永久继承下来。于是没有任何业务语义的续期线程长期持有
 * "最近启动者"的租户/用户身份，任何在该线程上的按身份取值都落到别人头上。
 *
 * <p>
 * 本单点锁：ticker 真正跑一拍时，回调线程上 {@link RequestContextUtils#currentOrNull()} 必须是
 * {@code null}（tick 必须显式 denied 清理继承/残留身份）。
 */
class JobHeartbeatTickerIdentityTest {

	@Test
	void tickerThreadRunsTheTickWithoutInheritedIdentity() throws Exception {
		Clock clock = Clock.systemUTC();
		FakeJobRedis redis = new FakeJobRedis(clock);
		AtomicReference<TenantUser> identityOnTickerThread = new AtomicReference<>();
		AtomicReference<String> tickerThreadName = new AtomicReference<>();
		CountDownLatch ticked = new CountDownLatch(1);

		JobHeartbeatRegistry registry = new JobHeartbeatRegistry(clock, redis.redis, true,
				Duration.ofMinutes(35), Duration.ofMillis(300)) {
			@Override
			void tickOnce() {
				try {
					tickerThreadName.set(Thread.currentThread().getName());
					identityOnTickerThread.set(RequestContextUtils.currentOrNull());
				}
				finally {
					ticked.countDown();
				}
			}
		};
		try {
			// ticker 线程在 beginRunner 里创建：调用者带身份 ⇒ 线程会继承该身份（缺陷形态）。
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(7L, 707L)) {
				assertThat(registry.beginRunner(42L)).isTrue();
			}

			assertThat(ticked.await(10, TimeUnit.SECONDS)).as("ticker 必须在 10s 内跑出一拍").isTrue();
			assertThat(tickerThreadName.get()).as("这一拍必须跑在心跳 ticker 线程上").isEqualTo("job-heartbeat-ticker");
			assertThat(identityOnTickerThread.get())
				.as("全局心跳 ticker 没有单作业身份：不得继承/残留启动 runner 的调用者身份")
				.isNull();
		}
		finally {
			registry.stopTicker();
		}
	}

}
