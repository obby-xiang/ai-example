package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认领侧的 fencing（B23L1 批②b 复审收口 I5）。
 *
 * <h2>为什么认领也要对 fence</h2>
 * 持租 ≠ 仍是主人：租约 TTL 到期被别人接管后，本进程的视图要等下一次续租失败才会清。
 * 这个窗口里若本进程去 {@code claimExecution}，就会在一个<b>自己已经没有续跑权</b>的轮上执行
 * 副作用（导入/发布这类工具不可撤销）。认领是"执行恰好一次"的唯一入口，必须对着 fence 复核：
 * {@code fence > epoch} ⇒ 拒绝认领（返回 false 的语义与"被他实例认领"一致：不得执行）。
 *
 * <p><b>E3 红线不破</b>：{@code epoch == 0}（无租约设施/未持租/降级）保持原样，不看 fence。
 */
class RunStoreClaimFenceTest {

	private static final String RUN_ID = "run-claim-fence";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService leaseA = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final LeaseService leaseB = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-b");

	private final RunStore storeA = new RunStore(this.fake.redis, this.mapper, this.properties, this.leaseA);

	private final RunStore storeB = new RunStore(this.fake.redis, this.mapper, this.properties, this.leaseB);

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachLogAppender() {
		this.appender = new ListAppender<>();
		this.appender.start();
		((Logger) LoggerFactory.getLogger(RunStore.class)).addAppender(this.appender);
		seedPending();
	}

	@AfterEach
	void detachLogAppender() {
		((Logger) LoggerFactory.getLogger(RunStore.class)).detachAppender(this.appender);
	}

	@Test
	void claimIsRejectedWhenTheFenceHasMovedPastOurEpoch() {
		// A 持租并写一次快照（fence 推到 A 的 epoch）
		this.leaseA.tryAcquire(this.storeA.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		long epochA = this.storeA.heldRunEpoch(RUN_ID);
		this.storeA.save(snapshot(RunSnapshot.SUSPENDED));
		// 换主：A 的租约过期，B 接管并写入（fence 推进到更大的 epoch）
		this.fake.dropKey(this.storeA.runLeaseKey(RUN_ID));
		this.leaseB.tryAcquire(this.storeB.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		this.storeB.save(snapshot(RunSnapshot.SUSPENDED));
		assertThat(this.storeA.fenceEpoch(RUN_ID)).as("fence 已推进").isGreaterThan(epochA);

		boolean claimed = this.storeA.claimExecution(RUN_ID, "c-1", this.storeA.instanceId());

		assertThat(claimed).as("fence > 本实例 epoch ⇒ 不得执行").isFalse();
		assertThat(this.storeA.claimedBy(RUN_ID, "c-1")).as("拒绝认领不得留痕").isNull();
		assertThat(logged("STALE_EPOCH 拒绝认领")).as("拒绝要留痕（否则现场只会看到'认领失败'）").isTrue();
	}

	@Test
	void claimOutcomeDistinguishesWonLostAndStaleFence() {
		// A 持租并写一次快照：fence = A 的 epoch（本实例既不陈旧、也不越权）
		this.leaseA.tryAcquire(this.storeA.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		long epochA = this.storeA.heldRunEpoch(RUN_ID);
		this.storeA.save(snapshot(RunSnapshot.SUSPENDED));

		RunStore.ClaimResult won = this.storeA.tryClaimExecution(RUN_ID, "c-1", this.storeA.instanceId());
		RunStore.ClaimResult lost = this.storeA.tryClaimExecution(RUN_ID, "c-1", this.storeA.instanceId());

		// 换主：A 的租约过期、B 接管并写入（fence 推进到更大的 epoch）
		this.fake.dropKey(this.storeA.runLeaseKey(RUN_ID));
		this.leaseB.tryAcquire(this.storeB.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		this.storeB.save(snapshot(RunSnapshot.SUSPENDED));
		RunStore.ClaimResult stale = this.storeA.tryClaimExecution(RUN_ID, "c-2", this.storeA.instanceId());

		assertThat(won.outcome()).as("第一个认领者 ⇒ WON").isEqualTo(RunStore.ClaimOutcome.WON);
		assertThat(won.won()).isTrue();
		assertThat(won.epoch()).as("认领记录的 epoch = 本实例持租的 epoch").isEqualTo(epochA);
		assertThat(won.fence()).as("WON 时 fence ≤ epoch").isLessThanOrEqualTo(epochA);

		assertThat(lost.outcome()).as("同一调用第二次 ⇒ LOST（被他人认领）")
			.isEqualTo(RunStore.ClaimOutcome.LOST);
		assertThat(lost.won()).isFalse();

		assertThat(stale.outcome()).as("换主后 ⇒ STALE_FENCE（不是'被他人认领'）")
			.isEqualTo(RunStore.ClaimOutcome.STALE_FENCE);
		assertThat(stale.won()).isFalse();
		assertThat(stale.fence()).as("STALE_FENCE 带上拒绝依据（fence > epoch）").isGreaterThan(stale.epoch());
		assertThat(this.storeA.claimedBy(RUN_ID, "c-2")).as("stale 拒绝不得留痕").isNull();

		// 布尔旧口保留：两种失败在布尔面上都是 false（"不得执行"这一点相同）
		assertThat(this.storeA.claimExecution(RUN_ID, "c-1", this.storeA.instanceId())).as("LOST ⇒ false").isFalse();
		assertThat(this.storeA.claimExecution(RUN_ID, "c-3", this.storeA.instanceId())).as("STALE_FENCE ⇒ false")
			.isFalse();
	}

	@Test
	void claimWithoutALeaseKeepsThePreviousSemantics() {
		// epoch == 0（本实例未持租）：E3 红线 —— 不看 fence，照旧认领
		this.fake.strings.put(RunStore.FENCE_RUN_PREFIX + RUN_ID, "9");

		boolean claimed = this.storeB.claimExecution(RUN_ID, "c-1", this.storeB.instanceId());

		assertThat(claimed).as("无租约不拒认领（单实例/降级路径）").isTrue();
		assertThat(this.storeB.claimedBy(RUN_ID, "c-1")).isEqualTo(this.storeB.instanceId());
	}

	/**
	 * 台账的 {@code epoch} 字段（additive）：记录"这次执行发生在哪一任续跑权之下"，
	 * 换主后的对账（哪些执行是旧主人做的）因此不必靠猜。用进程内替身跑，
	 * 因为台账是 LIST 面（{@link FakeLeaseRedis} 只建模到脚本/HASH/STRING）。
	 */
	@Test
	void ledgerRecordsTheEpochTheExecutionRanUnder() {
		com.example.configmgr.ai.conformance.InMemoryRunStore inMemory =
				new com.example.configmgr.ai.conformance.InMemoryRunStore();
		inMemory.useLeaseFacility(this.leaseA);
		PendingToolCall entry = new PendingToolCall();
		entry.setToolCallId("c-1");
		entry.setName("start_import");
		entry.setKind(PendingToolCall.KIND_BACKEND);
		inMemory.seedPending(RUN_ID, entry);
		this.leaseA.tryAcquire(inMemory.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		long epoch = inMemory.heldRunEpoch(RUN_ID);
		assertThat(inMemory.claimExecution(RUN_ID, "c-1", "inst-a")).isTrue();
		PendingToolCall claimed = inMemory.pending(RUN_ID, "c-1");

		inMemory.appendLedger(RUN_ID, claimed, "已执行");

		assertThat(inMemory.ledger(RUN_ID)).hasSize(1);
		assertThat(inMemory.ledger(RUN_ID).get(0)).as("台账带执行时的 epoch（additive，旧记录可缺）")
			.containsEntry("epoch", epoch);
		assertThat(inMemory.claimedBy(RUN_ID, "c-1")).isEqualTo("inst-a");
	}

	// ───────────────────────── helpers ─────────────────────────

	private void seedPending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-1");
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		this.storeA.putPending(RUN_ID, pending);
	}

	private RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-claim-fence");
		snapshot.setStatus(status);
		return snapshot;
	}

	private boolean logged(String fragment) {
		return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains(fragment));
	}

}
