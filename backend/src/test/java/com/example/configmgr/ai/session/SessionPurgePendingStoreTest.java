package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * durable pending 台账：身份键、到期 ZSET 发现面、owner/epoch 执行租约、耗尽退役、完成清理。
 *
 * <p>证红：去掉 due 成员（只写台账）⇒ 发现用例红；去掉 purgeId/owner/epoch 条件 ⇒ 旧执行体用例红；
 * 耗尽项不摘发现项 ⇒ 退役用例红；退役时删台账 ⇒ durable/守卫断言红。
 */
class SessionPurgePendingStoreTest {

	private static final long NOW = 1_000L;

	private static final TenantUser ALICE = new TenantUser(1L, 101L);

	private static final TenantUser BOB = new TenantUser(2L, 201L);

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final SessionPurgePendingStore store = new SessionPurgePendingStore(this.fake.redis, new ObjectMapper(),
			new AiProperties());

	/** 执行租约/守卫这些"当前身份"接口要求显式身份作用域（与生产调用方一致）。 */
	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void bindIdentity() {
		this.identity = RequestContextUtils.useContext(ALICE.tenantId(), ALICE.userId());
	}

	@AfterEach
	void unbindIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void registrationWritesTheDueMemberAndListDueReturnsTheLedger() {
		SessionPurgePendingStore.Registration registration = this.store.register(ALICE, "s-1", "purge-1", "tester", NOW);

		assertThat(registration.created()).as("首次登记由本次调用拥有").isTrue();
		assertThat(registration.pending().purgeId()).isEqualTo("purge-1");
		assertThat(registration.pending().identity()).as("身份落进记录（不靠线程上下文）").isEqualTo(ALICE);
		String key = SessionPurgePendingStore.pendingKey("s-1", 1L, 101L);
		assertThat(this.fake.zsets.get(SessionPurgePendingStore.DUE_KEY))
			.as("台账与到期项同一次登记写出；due 是唯一发现面")
			.containsEntry(key, (double) NOW);

		assertThat(this.store.listDue(NOW, 10)).extracting(SessionPurgePendingStore.Pending::sessionId)
			.containsExactly("s-1");
		assertThat(this.store.listDue(NOW - 1L, 10)).as("未到点不得取到").isEmpty();
	}

	@Test
	void dueDiscoveryRestoresEachLedgersOwnCompositeIdentity() {
		this.store.register(ALICE, "s-1", "purge-a", "tester", NOW);
		this.store.register(BOB, "s-1", "purge-b", "tester", NOW);

		String aliceKey = SessionPurgePendingStore.pendingKey("s-1", 1L, 101L);
		String bobKey = SessionPurgePendingStore.pendingKey("s-1", 2L, 201L);
		assertThat(aliceKey).as("同 sessionId 不同身份必须落在不同键上").isNotEqualTo(bobKey);
		assertThat(this.fake.zsets.get(SessionPurgePendingStore.DUE_KEY)).containsKeys(aliceKey, bobKey);

		assertThat(this.store.listDue(NOW, 10)).extracting(
				pending -> pending.tenantId() + ":" + pending.userId())
			.as("身份只能来自记录（不扫租户列表、不从键名反解）")
			.containsExactlyInAnyOrder("1:101", "2:201");
		assertThat(this.store.listDue(NOW, 1)).as("候选数必须按调用方上限截断").hasSize(1);
	}

	@Test
	void dueWriteFailureAbortsRegistrationWithoutWritingAHalfLedger() {
		this.fake.failDueSchedule = true;

		assertThatThrownBy(() -> this.store.register(ALICE, "s-1", "purge-1", "tester", NOW))
			.as("到期分写不进去 = 登记失败（不得留下'有台账、无发现项'的持续 410）")
			.isInstanceOf(AiStateUnavailableException.class);

		assertThat(this.fake.strings).doesNotContainKey(SessionPurgePendingStore.pendingKey("s-1", 1L, 101L));
		assertThat(this.fake.zsets.getOrDefault(SessionPurgePendingStore.DUE_KEY, java.util.Map.of())).isEmpty();
		assertThat(new SessionPurgeGuard(this.fake.redis).isArmed("s-1", 1L, 101L))
			.as("没有台账就没有保护事实：不得伪装成已销毁").isFalse();
	}

	@Test
	void stalePurgeIdCannotUpdateOrCompleteTheNewLedger() {
		this.store.register(ALICE, "s-1", "purge-new", "tester", NOW);

		assertThat(this.store.recordFailure("s-1", "purge-old", "stale failure")).isFalse();
		assertThat(this.store.complete("s-1", "purge-old")).isFalse();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			assertThat(this.store.current("s-1").purgeId()).isEqualTo("purge-new");
		}
		assertThat(this.fake.zsets.get(SessionPurgePendingStore.DUE_KEY))
			.as("旧 purgeId 的操作不得摘掉新台账的发现项")
			.containsKey(SessionPurgePendingStore.pendingKey("s-1", 1L, 101L));

		assertThat(this.store.complete("s-1", "purge-new")).isTrue();
		assertThat(this.fake.strings).doesNotContainKey(SessionPurgePendingStore.pendingKey("s-1", 1L, 101L));
		assertThat(this.fake.zsets.getOrDefault(SessionPurgePendingStore.DUE_KEY, java.util.Map.of()))
			.as("完成后到期成员必须一起摘除").isEmpty();
	}

	@Test
	void staleExecutionEpochCannotCompleteAfterATakeover() {
		long now = System.currentTimeMillis();
		this.store.register(ALICE, "s-1", "purge-1", "tester", now);
		SessionPurgePendingStore.Execution first = this.store.acquire("s-1", "purge-1", "owner-1", now, 10L)
			.execution();
		assertThat(first).isNotNull();
		SessionPurgePendingStore.Execution takeover = this.store.acquire("s-1", "purge-1", "owner-2", now + 20L,
				60_000L)
			.execution();
		assertThat(takeover).as("租约过期后允许接管").isNotNull();
		assertThat(takeover.epoch()).isGreaterThan(first.epoch());

		assertThat(this.store.complete(first)).as("旧 epoch 不得删除新一任的台账").isFalse();
		assertThat(this.store.isFailureRecorded(first, "stale")).isFalse();
		assertThat(this.fake.strings).containsKey(SessionPurgePendingStore.pendingKey("s-1", 1L, 101L));

		assertThat(this.store.complete(takeover)).isTrue();
		assertThat(this.fake.strings).doesNotContainKey(SessionPurgePendingStore.pendingKey("s-1", 1L, 101L));
	}

	@Test
	void exhaustedLedgerIsRetiredButKeptDurableAndManualReopenRestoresIt() {
		long now = System.currentTimeMillis();
		this.store.register(ALICE, "s-1", "purge-1", "tester", now);
		// 普通失败路径不需要执行租约（记录失败会释放 owner/lease），按 purgeId 条件推进 attempts。
		for (int attempt = 0; attempt < SessionPurgePendingStore.MAX_AUTO_ATTEMPTS; attempt++) {
			assertThat(this.store.isFailureRecorded("s-1", "purge-1", "boom")).isTrue();
		}
		String key = SessionPurgePendingStore.pendingKey("s-1", 1L, 101L);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			assertThat(this.store.current("s-1").attempts())
				.isEqualTo(SessionPurgePendingStore.MAX_AUTO_ATTEMPTS);
		}

		long future = now + 86_400_000L;
		assertThat(this.store.retireExhausted("s-1", "purge-1", future)).isTrue();
		assertThat(this.fake.zsets.getOrDefault(SessionPurgePendingStore.DUE_KEY, java.util.Map.of()))
			.as("耗尽项退役：不再占全局发现面")
			.doesNotContainKey(key);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			SessionPurgePendingStore.Pending retired = this.store.current("s-1");
			assertThat(retired).as("durable 台账必须保留（守卫/事实来源）").isNotNull();
			assertThat(retired.attempts()).as("退役不得重置 attempts").isEqualTo(SessionPurgePendingStore.MAX_AUTO_ATTEMPTS);
			assertThat(retired.nextAtMs()).isEqualTo(future);
		}
		assertThat(new SessionPurgeGuard(this.fake.redis).isArmed("s-1", 1L, 101L))
			.as("退役不是完成：守卫仍由台账 arm").isTrue();

		SessionPurgePendingStore.Registration reopened = this.store.register(ALICE, "s-1", "purge-2", "tester",
				now);
		assertThat(reopened.created()).as("手工 DELETE 复用既有 purgeId（不重开一轮）").isFalse();
		assertThat(reopened.pending().purgeId()).isEqualTo("purge-1");
		assertThat(reopened.pending().attempts()).as("手动重开把耗尽的 attempts 归零").isZero();
		assertThat(this.fake.zsets.get(SessionPurgePendingStore.DUE_KEY))
			.as("手动重开必须把到期项放回发现面").containsEntry(key, (double) now);
	}

	@Test
	void recordFailureKeepsBackoffBoundedAndTheDueEntryDiscoverable() {
		long now = System.currentTimeMillis();
		this.store.register(ALICE, "s-1", "purge-1", "tester", now);
		SessionPurgePendingStore.Execution execution = this.store.acquire("s-1", "purge-1", "owner-1", now,
				3_600_000L)
			.execution();
		long before = System.currentTimeMillis();

		assertThat(this.store.isFailureRecorded(execution, "boom")).isTrue();

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			SessionPurgePendingStore.Pending pending = this.store.current("s-1");
			assertThat(pending.attempts()).isEqualTo(1);
			assertThat(pending.lastError()).isEqualTo("boom");
			assertThat(pending.nextAtMs()).as("退避有界且不为 0")
				.isBetween(before + SessionPurgePendingStore.BASE_BACKOFF_MS,
						before + SessionPurgePendingStore.MAX_BACKOFF_MS);
		}
		assertThat(this.store.listDue(Long.MAX_VALUE - 1L, 10))
			.as("退避后仍可被有界 ZRANGEBYSCORE 发现").extracting(SessionPurgePendingStore.Pending::sessionId)
			.contains("s-1");
	}

}
