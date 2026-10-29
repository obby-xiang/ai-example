package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户/用户隔离：<b>同一个 sessionId 在不同身份下必须互不可见</b>（租户任务 2 会话/销毁组）。
 *
 * <p>本类只放"跨身份"的边界断言；单身份内的既有语义由各组件自己的用例覆盖。
 */
class AiSessionTenantIsolationTest {

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 201L;

	private static final String SESSION = "dup-session";

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final AiSessionService sessions = new AiSessionService(this.fake.redis, new ObjectMapper(),
			new AiProperties());

	private final SessionGateLeaseTokenTest.FakeSessionRedis gateRedis =
			new SessionGateLeaseTokenTest.FakeSessionRedis();

	private final SessionGate gate = new SessionGate(this.gateRedis.template, new AiProperties());

	private final SessionPurgeGuard guard = new SessionPurgeGuard(this.fake.redis);

	@Test
	void sameSessionIdUnderTwoTenantsDoesNotShareTheSessionRecord() {
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.sessions.ensure(SESSION)).as("A 首次登记").isFalse();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.sessions.exists(SESSION)).as("B 不得看到 A 的会话记录（同 sessionId）").isFalse();
			assertThat(this.sessions.ensure(SESSION)).as("B 是首次登记").isFalse();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.sessions.exists(SESSION)).as("A 的记录不得被 B 的登记覆盖").isTrue();
		}
	}

	/**
	 * 会话门按复合身份分桶：跨租户同 sessionId 不能互斥、不能互相诊断、不能互相释放。
	 */
	@Test
	void sameSessionIdUnderTwoIdentitiesHoldsTwoIndependentGates() {
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.gate.acquire(SESSION, "run-a")).as("A 取门成功").isNull();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.gate.acquire(SESSION, "run-b")).as("B 不得被 A 的门挡住（同 sessionId 不同身份）")
				.isNull();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.gate.currentRunId(SESSION)).isEqualTo("run-a");
			assertThat(this.gate.release(SESSION, "run-a", "run-a")).as("A 释放自己的门").isTrue();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.gate.currentRunId(SESSION)).as("A 的释放不得摘掉 B 的门").isEqualTo("run-b");
			assertThat(this.gate.release(SESSION, "run-b", "run-b")).isTrue();
		}
	}

	/**
	 * 锁 watchdog 跑在专用调度线程上（<b>没有请求身份</b>）：续期必须按 holder 记录的身份与锁键执行，
	 * 否则会续到 legacy 键上（续不上 ⇒ 误判丢锁，或者根本续不到别人的锁）。
	 */
	@Test
	void watchdogRenewsTheIdentityScopedLockFromTheHolderRecord() throws InterruptedException {
		AiProperties properties = new AiProperties();
		properties.getSession().getLock().setWatchdog(Duration.ofMillis(25));
		SessionGateLeaseTokenTest.FakeSessionRedis redis = new SessionGateLeaseTokenTest.FakeSessionRedis();
		SessionGate scopedGate = new SessionGate(redis.template, properties);

		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(scopedGate.acquire(SESSION, "run-w")).isNull();
		}
		long deadline = System.currentTimeMillis() + 2_000L;
		while (scopedGate.renewals() == 0L && System.currentTimeMillis() < deadline) {
			Thread.sleep(10L);
		}

		assertThat(scopedGate.renewals()).as("watchdog 必须按记录身份续期成功").isGreaterThan(0L);
		assertThat(scopedGate.lockLost()).as("不得把身份算错当成丢锁").isZero();
		assertThat(redis.values).as("锁只落在带身份的键上")
			.containsKey(SessionGate.gateKey(SESSION, TENANT_A, USER_A));
		assertThat(redis.values).doesNotContainKey(SessionGate.LOCK_PREFIX + SESSION);

		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(scopedGate.release(SESSION, "run-w", "run-w")).isTrue();
		}
	}

	/**
	 * 销毁墓碑（守卫）与未完成台账都按身份隔离：A 的销毁不能让 B 的写回被拒。
	 */
	@Test
	void purgeGuardAndPendingAreArmedOnlyForTheIdentityThatOwnsThem() {
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			this.guard.arm(SESSION, "purge-a", Duration.ofMinutes(10));
			assertThat(this.guard.isArmed(SESSION)).as("A 自己装的守卫对 A 有效").isTrue();
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.guard.isArmed(SESSION)).as("B 不得被 A 的销毁墓碑挡住（同 sessionId）").isFalse();
		}
		// 未完成的 durable pending 同样是"按身份"的守卫面
		SessionPurgePendingStore pendingStore = new SessionPurgePendingStore(this.fake.redis, new ObjectMapper(),
				new AiProperties());
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			pendingStore.register(SESSION, "purge-b", "tester", 1_000L);
			assertThat(this.guard.isArmed(SESSION)).as("B 自己的未完成台账让其守卫有效").isTrue();
		}
		assertThat(this.fake.strings).containsKeys(SessionPurgeGuard.keyFor(SESSION, TENANT_A, USER_A),
				SessionPurgePendingStore.pendingKey(SESSION, TENANT_B, USER_B));
	}

}
