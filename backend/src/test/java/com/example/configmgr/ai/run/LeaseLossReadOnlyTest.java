package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>丢租之后只读</b>（续租 fencing 收口批）。
 *
 * <h2>缺陷形态</h2>
 * 续租失败（Lua 返回 0 = 确定丢租）时旧实现只把进程内视图删掉，于是 {@code heldRunEpoch} 变
 * 回 0 ⇒ {@link RunStore#save} 走进"无租约 plain SET"分支，把<b>新持有者的终态覆盖掉</b>
 * （A 的 FAILED 盖掉 B 的 DONE）。这正是 epoch=0 不能当"可写"判据的原因：
 * <b>0 同时表示"从未持租"和"持过但已丢租"</b>，两种情况的可写性相反。
 *
 * <h2>本批口径（权威判定 {@link LeaseService#authority(String)}）</h2>
 * <ul>
 * <li>{@code HELD}：按 epoch 走 fence 写（既有语义）；</li>
 * <li>{@code LOST}：<b>确定</b>丢租（续租脚本返回 0 / verify 返回 0）⇒ 停快照写、停取号落档、
 *     停新工具认领、停终帧，只读恢复；</li>
 * <li>{@code UNAVAILABLE}：续租/校验抛异常（不知道租约还在不在）⇒ 同样只读不推进；恢复后续租
 *     成功即可回到 {@code HELD}（不是永久毒化）；</li>
 * <li>{@code NEVER_HELD}：本进程从未持有这一轮 ⇒ 才允许原普通写（且设施要能自证正常）。</li>
 * </ul>
 */
class LeaseLossReadOnlyTest {

	private static final String RUN_ID = "run-lease-lost";

	private static final String SESSION_ID = "s-lease-lost";

	private static final String LEASE_KEY = RunStore.runScopedKey(RunStore.LEASE_RUN_PREFIX, RUN_ID);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final LeaseService leaseA = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-a");

	private final LeaseService leaseB = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-b");

	private final RunStore storeA = new RunStore(this.fake.redis, this.mapper, this.properties, this.leaseA);

	private final RunStore storeB = new RunStore(this.fake.redis, this.mapper, this.properties, this.leaseB);

	private final RunStore noFacility = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void lostLeaseTurnsTheOldHolderReadOnly() {
		seedRun();
		LeaseService.Lease a = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();

		// A 的租约 TTL 到点，B 接管并写出终态 DONE
		this.fake.dropKey(LEASE_KEY);
		this.storeB.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot byB = this.storeB.get(RUN_ID);
		byB.setStatus(RunSnapshot.DONE);
		byB.setFinalText("by-b");
		this.storeB.save(byB);
		assertThat(this.storeA.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);

		// A 的续租下一拍拿到 0 = 确定丢租
		assertThat(this.leaseA.renewState(a, ttl())).isEqualTo(LeaseService.RenewOutcome.LOST);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).isEqualTo(LeaseService.LeaseAuthority.LOST);

		// ① 快照写：A 的 FAILED 不得覆盖 B 的 DONE
		RunSnapshot staleByA = this.storeA.get(RUN_ID);
		staleByA.setStatus(RunSnapshot.FAILED);
		staleByA.setFinalText("by-a");
		this.storeA.save(staleByA);
		assertThat(this.storeA.get(RUN_ID).getStatus()).as("丢租后不得写快照").isEqualTo(RunSnapshot.DONE);
		assertThat(this.storeA.get(RUN_ID).getFinalText()).isEqualTo("by-b");

		// ② 新工具：认领被拒（STALE_FENCE = 没有续跑权）
		RunStore.ClaimResult claim = this.storeA.tryClaimExecution(RUN_ID, "call-lost", "inst-a");
		assertThat(claim.outcome()).isEqualTo(RunStore.ClaimOutcome.STALE_FENCE);
		assertThat(this.storeA.claimedBy(RUN_ID, "call-lost")).isNull();

		// ③ 取号落档：终帧不落档、不占业务号
		SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.storeA, this.mapper);
		emitter.done(Map.of(), "test-model", Map.of());
		assertThat(this.storeA.events(RUN_ID)).as("丢租后不得再落任何业务帧").isEmpty();
		assertThat(this.storeA.lastIssuedSeq(RUN_ID)).as("丢租后不得再取业务号").isZero();

		// ④ 台账落档：不得追加（B 的台账才是判据）
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-lost");
		pending.setName("start_export");
		pending.setExecutedBy("inst-a");
		assertThat(this.storeA.ledger(RUN_ID)).isEmpty();
		this.storeA.appendLedger(RUN_ID, pending, "stale");
		assertThat(this.storeA.ledger(RUN_ID)).as("丢租后不得追加台账").isEmpty();
	}

	@Test
	void renewExceptionIsReadOnlyButReplayAndRecoveryStillWork() {
		seedRun();
		LeaseService.Lease a = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot running = this.storeA.get(RUN_ID);
		running.setStatus(RunSnapshot.RUNNING);
		this.storeA.save(running);

		this.fake.failRenew(true);
		assertThat(this.leaseA.renewState(a, ttl())).isEqualTo(LeaseService.RenewOutcome.UNAVAILABLE);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).isEqualTo(LeaseService.LeaseAuthority.UNAVAILABLE);

		// 读/回放仍可用（只读恢复）
		RunSnapshot replay = this.storeA.get(RUN_ID);
		assertThat(replay).isNotNull();
		assertThat(replay.getStatus()).isEqualTo(RunSnapshot.RUNNING);

		// 但不得推进：状态写被拦
		replay.setStatus(RunSnapshot.FAILED);
		replay.setFinalText("advanced-while-unknown");
		this.storeA.save(replay);
		assertThat(this.storeA.get(RUN_ID).getStatus()).as("续租异常 ⇒ 只读不推进")
			.isEqualTo(RunSnapshot.RUNNING);
		assertThat(this.storeA.get(RUN_ID).getFinalText()).isNull();

		// 设施恢复后续租成功 ⇒ 回到 HELD，写权限恢复（不是永久毒化）
		this.fake.failRenew(false);
		assertThat(this.leaseA.renewState(a, ttl())).isEqualTo(LeaseService.RenewOutcome.RENEWED);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).isEqualTo(LeaseService.LeaseAuthority.HELD);
		RunSnapshot recovered = this.storeA.get(RUN_ID);
		recovered.setFinalText("recovered");
		this.storeA.save(recovered);
		assertThat(this.storeA.get(RUN_ID).getFinalText()).isEqualTo("recovered");
	}

	@Test
	void neverHeldStillUsesPlainWritesButOnlyWhileTheFacilityIsHealthy() {
		this.storeA.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec,
				null, null);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).as("从未持租 ⇒ 原普通写")
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);
		RunSnapshot first = this.storeA.get(RUN_ID);
		first.setFinalText("plain");
		this.storeA.save(first);
		assertThat(this.storeA.get(RUN_ID).getFinalText()).isEqualTo("plain");

		// 设施掉线：一次失败的取租把"设施正常"证伪；从未持租也不能再凭 epoch=0 放行
		this.fake.goDown();
		assertThat(this.storeA.acquireRunLease(RUN_ID).outcome())
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).as("设施不可用 ⇒ 不确定 ⇒ 只读")
			.isEqualTo(LeaseService.LeaseAuthority.UNAVAILABLE);

		this.fake.heal();
		// 健康自证有 1s 节流（避免断连时每帧等一次 PING 超时）：等过节流窗口再断言"恢复即复工"。
		try {
			Thread.sleep(1_100L);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		assertThat(this.storeA.runLeaseAuthority(RUN_ID)).as("设施恢复自证后回到普通写")
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);
	}

	@Test
	void storeWithoutLeaseFacilityKeepsTheExistingPlainWritePath() {
		this.noFacility.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec,
				null, null);

		assertThat(this.noFacility.runLeaseAuthority(RUN_ID))
			.as("无租约设施（单测/降级装配）没有租约语义 ⇒ 普通写").isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);
		RunSnapshot snapshot = this.noFacility.get(RUN_ID);
		snapshot.setFinalText("no-facility");
		this.noFacility.save(snapshot);
		assertThat(this.noFacility.get(RUN_ID).getFinalText()).isEqualTo("no-facility");
	}

	/**
	 * 墓碑生命周期边界：{@code deleteRun} <b>不</b>清 LOST（删键后仍可能有陈旧写者，清掉等于重新开放
	 * plain SET）；只有进程退出（{@code shutdown}）才清空。这条用例把边界钉住，防止后续"顺手清理"
	 * 把 fencing 安全性改回去。
	 */
	@Test
	void lostTombstoneSurvivesRunDeletionAndDiesWithTheProcess() {
		seedRun();
		LeaseService.Lease a = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.fake.dropKey(LEASE_KEY);
		this.storeB.tryAcquireRunLease(RUN_ID).orElseThrow();
		assertThat(this.leaseA.renewState(a, ttl())).isEqualTo(LeaseService.RenewOutcome.LOST);

		this.storeA.deleteRun(RUN_ID);

		assertThat(this.storeA.runLeaseAuthority(RUN_ID))
			.as("deleteRun 只放仍持有的租约；丢租的墓碑必须保留（否则陈旧写者可 plain 写回来）")
			.isEqualTo(LeaseService.LeaseAuthority.LOST);

		this.leaseA.shutdown();

		assertThat(this.storeA.runLeaseAuthority(RUN_ID))
			.as("进程退出才清空墓碑；设施自证正常 ⇒ 之后按从未持租").isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);
	}

	private void seedRun() {
		this.storeA.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("hi")), this.codec, null,
				null);
	}

	private Duration ttl() {
		return this.properties.getLease().getTtl();
	}

}
