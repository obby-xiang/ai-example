package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.LeaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 自动重试闭环的<b>耗尽项</b>语义（直接单测：替身 Redis + 真台账 + mock 删除主体，不拉 H2/真 RunStore）。
 *
 * <p>证红：把 {@code SessionPurgeRetryRunner#deferExhausted} 改成"原地 skip"（不推 nextAt、不重排），
 * 本例会红 —— 耗尽项永久 due 且压在队头窗口，正常候选永远进不了本轮候选预算。
 */
class SessionPurgeRetryRunnerTest {

	private static final String SESSION = "sess-real";

	private static final int MAX_ATTEMPTS = 10;

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final SessionPurgePendingStore store = new SessionPurgePendingStore(this.fake.redis,
			new ObjectMapper(), new AiProperties());

	@Test
	void exhaustedPendingsAreDeferredAndDoNotStarveDueSessions() {
		long now = 1_000L;
		for (int i = 0; i < 10; i++) {
			String zombie = zombie(i);
			this.store.register(zombie, "purge-zombie-" + i, "tester", now);
			for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
				assertThat(this.store.recordFailure(zombie, "purge-zombie-" + i, "boom")).isTrue();
			}
		}
		// 第 11 条是正常待重试；耗尽项排在它前面（模拟"僵尸占满队头窗口"）。
		this.store.register(SESSION, "purge-real", "tester", now);
		assertThat(this.fake.lists.get(SessionPurgePendingStore.QUEUE_KEY)).hasSize(11)
			.startsWith(this.store.pendingKey(zombie(0)), this.store.pendingKey(zombie(1)));

		AiSessionPurgeService purgeService = mock(AiSessionPurgeService.class);
		when(purgeService.retry(anyString(), anyString(), anyString())).thenReturn(completedPurge());
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.store, purgeService, grantedLease());
		long later = System.currentTimeMillis() + 3_600_000L;

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(later);

		assertThat(report.attempted()).as("耗尽项不得占满本轮候选预算").isEqualTo(1);
		assertThat(report.completed()).isEqualTo(1);
		assertThat(report.skipped()).isEqualTo(10);
		verify(purgeService).retry(SESSION, "purge-real", "purge-retry");
		verify(purgeService, never()).retry(startsWith("sess-zombie-"), anyString(), anyString());
		for (int i = 0; i < 10; i++) {
			SessionPurgePendingStore.Pending pending = this.store.current(zombie(i));
			assertThat(pending).as("耗尽项仍是有主的 durable 台账（手动 DELETE 可续）").isNotNull();
			assertThat(pending.attempts()).as("defer 不得重置 attempts（不得强行第 11 次）").isEqualTo(MAX_ATTEMPTS);
			assertThat(pending.nextAtMs()).as("nextAt 必须推到有界未来").isGreaterThan(later);
		}
		assertThat(this.fake.lists.get(SessionPurgePendingStore.QUEUE_KEY)).as("耗尽项必须移到队尾")
			.endsWith(this.store.pendingKey(zombie(7)), this.store.pendingKey(zombie(8)),
					this.store.pendingKey(zombie(9)));

		// 真实删除主体成功后会移除 pending（这里用台账 API 等价模拟已完成）。
		assertThat(this.store.complete(SESSION, "purge-real")).isTrue();
		SessionPurgeRetryRunner.RetryReport next = runner.runOnce(later + 1_000L);
		assertThat(next.attempted()).as("未到 nextAt 前不得再执行").isZero();
		assertThat(next.skipped()).as("耗尽项已不在 due 窗口里").isZero();
		verify(purgeService, never()).retry(startsWith("sess-zombie-"), anyString(), anyString());
	}

	private static String zombie(int index) {
		return "sess-zombie-" + index;
	}

	/**
	 * 重试线程没有请求身份：处理每条台账前必须按<b>记录</b>里的 {@code tenantId/userId}
	 * {@code useContext}，否则会去删 legacy 键（或干脆删不干净）。
	 */
	@Test
	void runOnceRestoresTheRecordedIdentityBeforeRetrying() {
		try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable scope =
				com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
			this.store.register(SESSION, "purge-tenant", "tester", 1_000L);
		}
		AiSessionPurgeService purgeService = mock(AiSessionPurgeService.class);
		java.util.concurrent.atomic.AtomicReference<com.example.configmgr.ai.tenant.TenantUser> seen =
				new java.util.concurrent.atomic.AtomicReference<>();
		when(purgeService.retry(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
			seen.set(com.example.configmgr.ai.tenant.RequestContextUtils.currentOrNull());
			return completedPurge();
		});
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.store, purgeService, grantedLease());

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(System.currentTimeMillis() + 3_600_000L);

		assertThat(report.completed()).isEqualTo(1);
		assertThat(seen.get()).as("删除主体必须在记录身份的作用域里执行")
			.isEqualTo(new com.example.configmgr.ai.tenant.TenantUser(1L, 101L));
		assertThat(com.example.configmgr.ai.tenant.RequestContextUtils.currentOrNull())
			.as("作用域关闭后不得把身份留在调度线程上")
			.isNull();
	}

	// ── 周期注册开关（app.ai.session.purge-retry.periodic-enabled）────────────────
	//
	// 背景：周期线程在 Spring 上下文缓存后跨测试类续跑，倒排录制按"测试类墙钟窗口"归因
	// （线程无关）⇒ 清理触达会被记到邻类窗口（nightly 40219310874，diff=58）。单测把周期注册
	// 关掉，只保留同步入口。下面三条锁住"关掉的只是周期注册"与"生产缺省仍是开"。
	//
	// 调度是否真的注册，只能看 `scheduler` 字段本身（首拍在 10s 之后，用例早已结束）——
	// 故用 ReflectionTestUtils 直接断言：关 ⇒ 未注册（null），开 ⇒ 真的注册（非 null）。

	@Test
	void periodicRegistrationIsOnByProductionDefaultAndBindableOff() {
		assertThat(new AiProperties().getSession().getPurgeRetry().isPeriodicEnabled())
			.as("生产缺省 = 行为原样（周期注册开）").isTrue();

		AiProperties bound = new Binder(new MapConfigurationPropertySource(
				Map.of("app.ai.session.purge-retry.periodic-enabled", "false")))
			.bind("app.ai", Bindable.of(AiProperties.class)).get();
		assertThat(bound.getSession().getPurgeRetry().isPeriodicEnabled())
			.as("键名/绑定口径：application.properties 的关闭必须落到这个开关上").isFalse();
	}

	@Test
	void periodicRegistrationOffStillRecoversOrphansOnReady() {
		SessionPurgePendingStore pending = mock(SessionPurgePendingStore.class);
		when(pending.recoverOrphans(SessionPurgeRetryRunner.MAX_RECOVERY_KEYS)).thenReturn(0);
		AiSessionPurgeService purgeService = mock(AiSessionPurgeService.class);
		AiProperties properties = new AiProperties();
		properties.getSession().getPurgeRetry().setPeriodicEnabled(false);
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(pending, purgeService, null, properties);

		runner.onReady();

		verify(pending).recoverOrphans(SessionPurgeRetryRunner.MAX_RECOVERY_KEYS);
		verifyNoInteractions(purgeService);
		assertThat(schedulerOf(runner)).as("关闭后调度器不得被注册（不只是日志/不打首拍）").isNull();
		runner.shutdown();
	}

	@Test
	void periodicRegistrationOnKeepsRecoveringOrphansOnReady() {
		SessionPurgePendingStore pending = mock(SessionPurgePendingStore.class);
		when(pending.recoverOrphans(SessionPurgeRetryRunner.MAX_RECOVERY_KEYS)).thenReturn(0);
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(pending,
				mock(AiSessionPurgeService.class), null, new AiProperties());

		runner.onReady();

		verify(pending).recoverOrphans(SessionPurgeRetryRunner.MAX_RECOVERY_KEYS);
		Object scheduler = schedulerOf(runner);
		assertThat(scheduler).as("缺省（开）必须真的注册调度器").isInstanceOf(ScheduledExecutorService.class);
		assertThat(((ScheduledExecutorService) scheduler).isShutdown())
			.as("缺省（开）：调度器已创建且仍在跑（周期确实注册了）").isFalse();
		runner.shutdown();
	}

	private static Object schedulerOf(SessionPurgeRetryRunner runner) {
		return ReflectionTestUtils.getField(runner, "scheduler");
	}

	private static AiSessionPurgeService.PurgeResult completedPurge() {
		return new AiSessionPurgeService.PurgeResult(SESSION, "purge-real", true, false, null, null, null,
				new AiSessionPurgeService.MemoryView(false, 0, true), "chat:mem:" + SESSION, List.of(), List.of(),
				new AiSessionPurgeService.ContextView(false, false, 0, false, 0),
				new AiSessionPurgeService.AttachmentView(0, 0, 0, List.of()),
				new AiSessionPurgeService.SessionView(false, false), false,
				new AiSessionPurgeService.ClientCleanup("mirror", "draft", List.of()), List.of(), List.of());
	}

	private static LeaseService grantedLease() {
		LeaseService leases = mock(LeaseService.class);
		when(leases.acquire(anyString(), any(Duration.class))).thenReturn(new LeaseService.AcquireResult(
				LeaseService.AcquireOutcome.ACQUIRED, new LeaseService.Lease("ai:purge:retry:lock", 1L, "test"),
				true));
		when(leases.release(any())).thenReturn(true);
		return leases;
	}

}
