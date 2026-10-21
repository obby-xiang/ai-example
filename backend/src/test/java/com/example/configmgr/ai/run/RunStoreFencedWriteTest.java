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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunStore#save} 的 fence 写（B23L1 批②a / 设计稿 §2.2 · 方案第 9 条）。
 *
 * <h2>为什么要 fence</h2>
 * 续跑权主人换了以后，旧主人手里那条"我还在跑"的写回不能再落库 —— 否则新主人刚推进的状态
 * 会被旧主人用陈旧视图覆盖（Camunda INVALID_STATE 同型）。fence = {@code ai:fence:run:<runId>}
 * 记"已落库的最大 epoch"，写入要求 {@code fence ≤ 本次 epoch}，并在同一步把 fence 推到本次 epoch。
 *
 * <p><b>红线 E3</b>：<b>不持租</b>（epoch = 0）时保持 plain {@code SET} —— 单实例/降级路径的
 * 既有语义（TTL 24h、无 fence 键）一点不变；"无租约不拒写"是对"租约设施故障即全面停写"的拒绝。
 */
class RunStoreFencedWriteTest {

	private static final String RUN_ID = "run-fence";

	private static final String RUN_KEY = RunStore.RUN_PREFIX + RUN_ID;

	private static final String FENCE_KEY = RunStore.FENCE_RUN_PREFIX + RUN_ID;

	private static final String LEASE_KEY = RunStore.LEASE_RUN_PREFIX + RUN_ID;

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
		seedRunning();
	}

	@AfterEach
	void detachLogAppender() {
		((Logger) LoggerFactory.getLogger(RunStore.class)).detachAppender(this.appender);
	}

	/** E1：持租写成功，fence 推进到本次 epoch，fence 键 TTL 随快照。 */
	@Test
	void fencedWriteSucceedsAndPushesTheFence() {
		LeaseService.Lease lease = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot snapshot = load();
		snapshot.setStatus(RunSnapshot.SUSPENDED);

		this.storeA.save(snapshot);

		assertThat(this.fake.string(FENCE_KEY)).as("fence 推到本次 epoch").isEqualTo(String.valueOf(lease.epoch()));
		assertThat(load().getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(load().getRunEpoch()).as("快照记下写入者的 epoch").isEqualTo(lease.epoch());
		assertThat(this.fake.expiry(FENCE_KEY)).as("fence 键 TTL 随快照").isEqualTo(Duration.ofHours(24));
	}

	/** E2：陈旧 epoch 写被拒 —— Redis 原值不变，且留 STALE_EPOCH 日志。 */
	@Test
	void staleEpochWriteIsRejectedAndTheStoredValueIsUntouched() {
		// A 抢到第一任租约并写了一次（fence=1）
		this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot byA = load();
		byA.setStatus(RunSnapshot.SUSPENDED);
		this.storeA.save(byA);

		// 租约 TTL 到点（A 死了/停顿了），B 抢到第二任并推进：fence=2
		this.fake.dropKey(LEASE_KEY);
		LeaseService.Lease leaseB = this.storeB.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot byB = load();
		byB.setStatus(RunSnapshot.DONE);
		byB.setFinalText("by-b");
		this.storeB.save(byB);
		assertThat(this.fake.string(FENCE_KEY)).isEqualTo(String.valueOf(leaseB.epoch()));
		assertThat(this.storeA.heldRunEpoch(RUN_ID)).as("A 仍以为自己持第一任").isEqualTo(1L);

		// A 用陈旧 epoch 写回：必须被拒
		RunSnapshot staleByA = load();
		staleByA.setStatus(RunSnapshot.FAILED);
		staleByA.setFinalText("by-a");
		this.storeA.save(staleByA);

		RunSnapshot stored = load();
		assertThat(stored.getStatus()).as("陈旧写不得覆盖新主人的状态").isEqualTo(RunSnapshot.DONE);
		assertThat(stored.getFinalText()).isEqualTo("by-b");
		assertThat(this.fake.string(FENCE_KEY)).as("被拒的写不得推进 fence").isEqualTo(String.valueOf(leaseB.epoch()));
		assertThat(this.appender.list).as("拒绝必须可观测（STALE_EPOCH）")
				.anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("STALE_EPOCH"));
	}

	/** E3（红线）：不持租 ⇒ 保持 plain SET，无 fence 键、TTL 仍 24h。 */
	@Test
	void withoutALeaseSaveStaysAPlainSet() {
		assertThat(this.storeA.heldRunEpoch(RUN_ID)).isZero();
		this.fake.resetCounters();

		RunSnapshot snapshot = load();
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.storeA.save(snapshot);

		assertThat(this.fake.scriptCalls.get()).as("无租约 ⇒ 一次 Lua 都不进").isZero();
		assertThat(this.fake.string(FENCE_KEY)).as("无租约 ⇒ 不产生 fence 键").isNull();
		assertThat(this.fake.string(RUN_KEY)).isNotNull();
		assertThat(this.fake.expiry(RUN_KEY)).as("TTL 仍是 session-ttl（24h）").isEqualTo(Duration.ofHours(24));
		assertThat(load().getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
	}

	/** E4：终态写 best-effort 释放租约。 */
	@Test
	void terminalWriteReleasesTheLease() {
		LeaseService.Lease lease = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot snapshot = load();
		snapshot.setStatus(RunSnapshot.DONE);
		snapshot.setFinalText("done");

		this.storeA.save(snapshot);

		assertThat(this.fake.string(LEASE_KEY)).as("终态释放租约").isNull();
		assertThat(this.storeA.heldRunEpoch(RUN_ID)).isZero();
		assertThat(this.fake.string(FENCE_KEY)).as("fence 是进度标记，终态不删").isEqualTo(String.valueOf(lease.epoch()));
	}

	/** E6：{@code deleteRun} 清掉本批新增的四族键（否则同 id 重跑会继承前任的 fence 与决策）。 */
	@Test
	void deleteRunClearsTheNewKeyFamilies() {
		this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.storeA.markRequestEmitted(RUN_ID, "call-1", 1_000L);
		this.storeA.tryRecordDecision(RUN_ID, "call-2", PendingToolCall.APPROVED);
		RunSnapshot snapshot = load();
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.storeA.save(snapshot);
		assertThat(this.fake.string(FENCE_KEY)).isNotNull();

		this.storeA.deleteRun(RUN_ID);

		assertThat(this.storeA.heldRunEpoch(RUN_ID)).as("删除即放弃进程内租约视图").isZero();
		assertThat(this.fake.string(LEASE_KEY)).isNull();
		assertThat(this.fake.string(FENCE_KEY)).isNull();
		assertThat(this.fake.hashTables.get(this.storeA.reqKey(RUN_ID))).isNull();
		assertThat(this.fake.hashTables.get(this.storeA.decidedKey(RUN_ID))).isNull();
	}

	// ── 语料 ────────────────────────────────────────────────────────────────

	private void seedRunning() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-1");
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setCreatedBy("inst-seed");
		this.storeA.save(snapshot);
	}

	private RunSnapshot load() {
		try {
			return this.mapper.readValue(this.fake.string(RUN_KEY), RunSnapshot.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("读取快照失败", ex);
		}
	}

}
