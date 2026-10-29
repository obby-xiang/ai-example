package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * durable pending 台账：发现面、owner CAS、完成清理。
 *
 * <p>证红：去掉 SCAN 兜底/队列 → 恢复用例红；去掉 purgeId 条件 → 旧重试用例红。
 */
class SessionPurgePendingStoreTest {

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final SessionPurgePendingStore store = new SessionPurgePendingStore(this.fake.redis,
			new ObjectMapper(), new AiProperties());

	@Test
	void scanRecoveryFindsPendingWhoseQueueEntryWasLost() {
		this.store.register("s-1", "purge-1", "tester", 1_000L);
		// 模拟“pending 已落、队列项丢失”（进程在两条命令之间崩溃/索引被清）。
		this.fake.lists.remove(SessionPurgePendingStore.QUEUE_KEY);

		int recovered = this.store.recoverOrphans(20);

		assertThat(recovered).isEqualTo(1);
		List<SessionPurgePendingStore.Pending> due = this.store.listDue(1_000L, 10);
		assertThat(due).extracting(SessionPurgePendingStore.Pending::sessionId).containsExactly("s-1");
	}

	@Test
	void scanRecoveryRestoresTheCompositeIdentityFromTheRecord() {
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(1L, 101L)) {
			this.store.register("s-1", "purge-a", "tester", 1_000L);
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(2L, 201L)) {
			this.store.register("s-1", "purge-b", "tester", 1_000L);
		}
		// 队列成员是 pending 键（含复合身份）：同 sessionId 的两条台账各占一项。
		assertThat(this.fake.lists.get(SessionPurgePendingStore.QUEUE_KEY)).containsExactly(
				SessionPurgePendingStore.pendingKey("s-1", 1L, 101L),
				SessionPurgePendingStore.pendingKey("s-1", 2L, 201L));

		// 队列丢失后只做 SCAN：身份只能来自记录（不扫租户列表、不从键名猜）。
		this.fake.lists.remove(SessionPurgePendingStore.QUEUE_KEY);
		int recovered = this.store.recoverOrphans(20);
		assertThat(recovered).isEqualTo(2);

		List<SessionPurgePendingStore.Pending> due = this.store.listDue(1_000L, 10);
		assertThat(due).extracting(SessionPurgePendingStore.Pending::sessionId).containsOnly("s-1");
		assertThat(due).extracting(pending -> pending.tenantId() + ":" + pending.userId())
			.as("恢复出的每条台账都带着自己的复合身份")
			.containsExactlyInAnyOrder("1:101", "2:201");
	}

	@Test
	void oldPurgeIdCannotUpdateOrCompleteNewPending() {
		this.store.register("s-1", "purge-new", "tester", 1_000L);

		assertThat(this.store.recordFailure("s-1", "purge-old", "stale failure")).isFalse();
		assertThat(this.store.complete("s-1", "purge-old")).isFalse();
		assertThat(this.store.current("s-1").purgeId()).isEqualTo("purge-new");

		assertThat(this.store.complete("s-1", "purge-new")).isTrue();
		assertThat(this.store.current("s-1")).isNull();
		// 队列成员 = pending 键（含身份段），完成时按同一成员摘除。
		assertThat(this.fake.lists.getOrDefault(SessionPurgePendingStore.QUEUE_KEY, List.of()))
			.doesNotContain(this.store.pendingKey("s-1"));
	}

	@Test
	void queueWriteFailureKeepsPendingAndGuardArmed() {
		org.springframework.data.redis.core.ListOperations<String, String> listOps = this.fake.redis.opsForList();
		org.mockito.Mockito.doThrow(new IllegalStateException("队列不可用"))
			.when(listOps).remove(SessionPurgePendingStore.QUEUE_KEY, 0, this.store.pendingKey("s-1"));

		org.assertj.core.api.Assertions.assertThatThrownBy(
			() -> this.store.register("s-1", "purge-1", "tester", 1_000L))
			.isInstanceOf(com.example.configmgr.common.AiStateUnavailableException.class);
		assertThat(this.fake.strings).containsKey(this.store.pendingKey("s-1"));
		assertThat(new SessionPurgeGuard(this.fake.redis).isArmed("s-1")).isTrue();
	}

	@Test
	void deferKeepsAttemptsAndMovesQueueEntryToTail() {
		this.store.register("s-1", "purge-1", "tester", 1_000L);
		this.store.register("s-2", "purge-2", "tester", 1_000L);
		assertThat(this.store.recordFailure("s-1", "purge-1", "boom")).isTrue();
		this.store.register("s-3", "purge-3", "tester", 1_000L);
		assertThat(this.fake.lists.get(SessionPurgePendingStore.QUEUE_KEY)).containsExactly(
				this.store.pendingKey("s-2"), this.store.pendingKey("s-1"), this.store.pendingKey("s-3"));

		assertThat(this.store.defer("s-1", "purge-1", 900_000L)).isTrue();

		SessionPurgePendingStore.Pending pending = this.store.current("s-1");
		assertThat(pending.attempts()).as("defer 不得重置 attempts（不得强行第 11 次）").isEqualTo(1);
		assertThat(pending.nextAtMs()).as("nextAt 必须推到有界未来").isEqualTo(900_000L);
		assertThat(pending.lastError()).isEqualTo("boom");
		assertThat(this.fake.lists.get(SessionPurgePendingStore.QUEUE_KEY)).as("耗尽项必须移到队尾，不占队头窗口")
			.containsExactly(this.store.pendingKey("s-2"), this.store.pendingKey("s-3"),
					this.store.pendingKey("s-1"));

		assertThat(this.store.defer("s-1", "purge-old", 1L)).as("旧 purgeId 不得改新台账").isFalse();
		assertThat(this.store.current("s-1").nextAtMs()).isEqualTo(900_000L);
	}

	@Test
	void recordFailureBackoffIsBoundedAndQueueStaysDiscoverable() {
		this.store.register("s-1", "purge-1", "tester", 1_000L);

		assertThat(this.store.recordFailure("s-1", "purge-1", "boom")).isTrue();
		SessionPurgePendingStore.Pending pending = this.store.current("s-1");

		assertThat(pending.attempts()).isEqualTo(1);
		assertThat(pending.nextAtMs()).isGreaterThan(1_000L);
		assertThat(pending.lastError()).isEqualTo("boom");
		assertThat(this.store.listDue(Long.MAX_VALUE - 1, 10)).extracting(
				SessionPurgePendingStore.Pending::sessionId).contains("s-1");
	}

}
