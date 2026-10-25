package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.LeaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
			.startsWith(zombie(0), zombie(1));

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
			.endsWith(zombie(7), zombie(8), zombie(9));

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
