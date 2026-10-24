package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 取号与占用<b>不可分</b>：per-run epoch 下的竞争语义（评审 I-1 / I-2）。
 *
 * <h2>要钉住的缺陷（I-1）</h2>
 * 上一版把取号外置成"客户端先 {@code INCR ai:lease:epoch}、再发单键 SET 脚本"。两步之间可以被
 * GC/调度/网络重排拉长到超过租约 TTL（30s），于是出现这条反例：
 * <ol>
 * <li>A {@code INCR=10} 后被挂起；</li>
 * <li>B {@code INCR=11}、{@code SET 11|B}、写 {@code fence=11}；B 崩，租约过期；</li>
 * <li>A 的脚本终于落地：键已空 ⇒ {@code SET 10|A} 成功。</li>
 * </ol>
 * A 手握 <b>10 &lt; fence 11</b> 的租约 ⇒ 每次 {@code save} 都被 {@code STALE_EPOCH} 拒（500），
 * 续租又一直钉着这把"写不进去的租约"，别人接不了管 —— fail-closed 变成<b>永久卡死</b>。
 *
 * <h2>本类怎么证明它不在了</h2>
 * 用 {@link FakeLeaseRedis#beforeAcquire} 把"B 的完整一轮 + B 崩 + 租约过期"确定性地插到
 * A 的脚本<b>之前</b>：
 * <ul>
 * <li>取号在脚本内（同槽 INCR+SET）⇒ A 的号一定大于 B 已发放的号 ⇒ 也一定大于 B 写下的 fence；</li>
 * <li>取号若在脚本外（旧口径）⇒ A 的号在插队前就已取定，落地时必然小于 fence ⇒ 本类第一条红。</li>
 * </ul>
 *
 * <p>Cluster 口径全程打开（{@link FakeLeaseRedis#enableClusterMode()}）：per-run 计数键必须与
 * 租约键同槽，否则脚本本身就是 CROSSSLOT。
 */
class LeaseEpochPerRunTest {

	private static final String RUN_ID = "run-epoch-race";

	private static final String OTHER_RUN = "run-epoch-other";

	private static final Duration TTL = Duration.ofSeconds(30);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService leasesA = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final LeaseService leasesB = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-b");

	private final RunStore storeA = new RunStore(this.fake.redis, this.mapper, this.properties, this.leasesA);

	private final RunStore storeB = new RunStore(this.fake.redis, this.mapper, this.properties, this.leasesB);

	@BeforeEach
	void clusterOn() {
		this.fake.enableClusterMode();
	}

	// ── I-1：迟到者不得用更小的号占租 ───────────────────────────────────────

	/**
	 * "延迟 A → B 已写更高 epoch → A 再到"：A 拿到的号必须<b>大于</b>已落库的 fence，
	 * 且随后的写回必须成功（不再永久 {@code STALE_EPOCH}）。
	 */
	@Test
	void delayedAcquireCannotLandBelowAnAlreadyAdvancedFence() {
		// ① A 持租并写 fence = e1
		LeaseService.Lease first = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.storeA.save(snapshot(RunSnapshot.RUNNING));
		assertThat(fence()).as("fence = A 的 epoch").isEqualTo(first.epoch());
		// ② A 掉线：租约过期（进程崩溃 / 网络分区，超过 30s TTL），本进程视图随之作废
		this.fake.expireKey(this.storeA.runLeaseKey(RUN_ID));
		assertThat(this.leasesA.releaseIfHeld(this.storeA.runLeaseKey(RUN_ID)))
			.as("租约已过期 ⇒ 条件删除不命中（只为清掉进程内视图，等价于进程重启后的空视图）").isFalse();

		// ③ A 的重试请求"还在路上"：脚本落地前，B 抢到更高号、写了 fence、随后也崩了（租约过期）
		this.fake.beforeAcquire = () -> {
			this.storeB.tryAcquireRunLease(RUN_ID).orElseThrow();
			this.storeB.save(snapshot(RunSnapshot.RUNNING));
			this.fake.expireKey(this.storeB.runLeaseKey(RUN_ID));
		};
		LeaseService.Lease late = this.storeA.acquireRunLease(RUN_ID).lease();

		assertThat(late).as("键已被 B 的租约占过且已过期 ⇒ A 仍能抢到").isNotNull();
		assertThat(late.epoch())
			.as("迟到者不得用小于已落库 fence（%d）的 epoch（%d）占租：那会让这一轮永久写不进去",
					fence(), late.epoch())
			.isGreaterThan(fence());

		// ④ 不再卡死：写回成功，fence 推进到 A 这一任
		this.storeA.save(snapshot(RunSnapshot.RUNNING));
		assertThat(fence()).as("fence 已推进到迟到者的 epoch").isEqualTo(late.epoch());
		assertThat(this.storeA.get(RUN_ID)).as("快照真的写进去了（不是 STALE_EPOCH 静默丢弃）").isNotNull();
	}

	/**
	 * 两实例争同一 run 租约（确定性交错）：<b>恰一个</b> {@code created==true}，
	 * 败方 {@code HELD_ELSEWHERE} 且<b>不释放胜方</b>，号只增不减。
	 */
	@Test
	void twoInstancesRacingForOneLeaseYieldExactlyOneCreator() {
		AtomicReference<LeaseService.AcquireResult> winner = new AtomicReference<>();

		// B 的完整一轮插在 A 的脚本之前（等价于"A 的包晚到、B 先落地"）
		this.fake.beforeAcquire = () -> winner.set(this.storeB.acquireRunLease(RUN_ID));

		LeaseService.AcquireResult a = this.storeA.acquireRunLease(RUN_ID);

		assertThat(winner.get()).as("注入点必须真的跑到（否则本用例是空断言）").isNotNull();
		assertThat(winner.get().created()).as("先落地的一方是创建者").isTrue();
		assertThat(a.outcome()).as("后到的一方看到现值 ⇒ 别人持有").isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);
		assertThat(a.created()).as("败方没有创建权").isFalse();

		// 败方不得释放胜方
		assertThat(this.leasesA.releaseIfHeld(this.storeA.runLeaseKey(RUN_ID)))
			.as("败方从未持有 ⇒ 不释放").isFalse();
		assertThat(this.fake.string(this.storeA.runLeaseKey(RUN_ID)))
			.as("胜方的租约仍在（败方 release 不得误删）")
			.isEqualTo(winner.get().lease().epoch() + "|inst-b");

		// 号只增不减：同一 run 下一次抢到的号严格更大
		this.fake.expireKey(this.storeA.runLeaseKey(RUN_ID));
		LeaseService.Lease next = this.storeA.acquireRunLease(RUN_ID).lease();
		assertThat(next.epoch()).as("同 run 的号只增不减").isGreaterThan(winner.get().lease().epoch());
	}

	// ── N-1：计数器比 fence 先死 / 陈旧租约 / 重入必须自愈 ───────────────────

	/**
	 * <b>计数器先于 fence 消失</b>（24h 无 READ、或被 maxmemory 淘汰）—— 必须自愈，不许卡死。
	 *
	 * <p>复现路径（评审 N-1 的第 1/2 条存活路径）：一轮跑满 24h，`fence` 被每笔 `save` 的
	 * {@code FENCED_SET} 反复续期、而计数器只有"取号/读侧"才被动到 ⇒ 计数器过期、fence 还在。
	 * 此时新接管者 {@code INCR} 从 1 重来，若脚本不与 fence 对账 ⇒ 拿到 `1 < fence` 的租约，
	 * 之后每次 `save` 都被 {@code STALE_EPOCH} 拒（500），续租又把这条写不进去的租约钉住 —— 永久卡死。
	 */
	@Test
	void counterExpiryAfterFenceIsSetDoesNotStallTheRun() {
		// ① 两任租约把 fence 推到 2（模拟"连续写、没人读"的长跑）
		this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.storeA.save(snapshot(RunSnapshot.RUNNING));
		this.fake.expireKey(this.storeA.runLeaseKey(RUN_ID));
		this.storeB.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.storeB.save(snapshot(RunSnapshot.RUNNING));
		assertThat(fence()).as("语料：fence 已推进到 2").isEqualTo(2L);

		// ② 计数器先死、租约也到期（新接管者视角：只有 fence 还活着）
		this.fake.expireKey(this.storeB.runLeaseEpochKey(RUN_ID));
		this.fake.expireKey(this.storeB.runLeaseKey(RUN_ID));
		assertThat(this.fake.string(this.storeB.runLeaseEpochKey(RUN_ID))).isNull();

		// ③ 新接管者抢租：号必须被抬到 fence 之上，而不是从 1 重来
		RunStore taker = newStore("inst-c");
		LeaseService.Lease takeover = taker.tryAcquireRunLease(RUN_ID).orElseThrow();
		assertThat(takeover.epoch())
			.as("计数器先死 ⇒ 取号必须自愈到 fence+1（否则这一轮永久 500）")
			.isGreaterThan(fence());
		assertThat(this.fake.string(this.storeA.runLeaseEpochKey(RUN_ID)))
			.as("计数器已按自愈结果写回").isEqualTo(String.valueOf(takeover.epoch()));

		// ④ 写回成功：不再永久 STALE_EPOCH
		taker.save(snapshot(RunSnapshot.RUNNING));
		assertThat(fence()).as("fence 推进到接管者的号").isEqualTo(takeover.epoch());
		assertThat(taker.get(RUN_ID)).as("快照真的写进去了（不是被拒后静默丢弃）").isNotNull();
	}

	/**
	 * <b>有效租约的重入</b>：不推进 epoch，但必须把（可能已过期的）计数器补回并刷新 TTL。
	 *
	 * <p>评审 N-1 的第 3 条存活路径：旧实现的命中分支"提前返回、不 PEXPIRE 也不重建计数器"，
	 * 于是重入一次就把计数器永久留在"缺失"状态 —— 下一次真正的取号又回到 1。
	 */
	@Test
	void reentrantAcquireKeepsTheEpochButRestoresAnExpiredCounter() {
		LeaseService.Lease first = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.fake.expireKey(this.storeA.runLeaseEpochKey(RUN_ID));

		// 同 owner 的另一个实例/重启后的进程：视图为空 ⇒ 真的走一次 Redis
		LeaseService.Lease again = newStore("inst-a").tryAcquireRunLease(RUN_ID).orElseThrow();

		assertThat(again.epoch()).as("有效租约重入不得推进 epoch").isEqualTo(first.epoch());
		assertThat(this.fake.string(this.storeA.runLeaseEpochKey(RUN_ID)))
			.as("重入必须把计数器补回到当前 epoch").isEqualTo(String.valueOf(first.epoch()));
		assertThat(this.fake.expiry(this.storeA.runLeaseEpochKey(RUN_ID)))
			.as("重入必须刷新计数器 TTL（否则它仍会比 fence 先死）")
			.isEqualTo(this.properties.getSessionTtl());
	}

	/**
	 * <b>陈旧租约</b>（epoch &lt; fence）必须被新 epoch 替换，而不是"看到键就据为己有"。
	 *
	 * <p>陈旧租约的持有人本来就写不进去（fence 拒写），把它当成"有人持有"只会让这一轮没人能接管。
	 */
	@Test
	void staleLeaseIsReplacedByAFreshEpochAboveTheFence() {
		this.fake.strings.put(this.storeA.fenceKey(RUN_ID), "5");
		this.fake.strings.put(this.storeA.runLeaseKey(RUN_ID), "2|inst-old");

		LeaseService.Lease fresh = newStore("inst-c").tryAcquireRunLease(RUN_ID).orElseThrow();

		assertThat(fresh.epoch()).as("新租约的号必须严格大于 fence").isEqualTo(6L);
		assertThat(this.fake.string(this.storeA.runLeaseKey(RUN_ID)))
			.as("陈旧租约已被替换").isEqualTo("6|inst-c");
	}

	/** fence 与计数器都缺失（全新一轮 / 被整体清掉）：按 0 处理，正常从 1 起。 */
	@Test
	void missingFenceAndCounterAreTreatedAsZero() {
		LeaseService.Lease lease = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();

		assertThat(lease.epoch()).isEqualTo(1L);
		assertThat(this.fake.string(this.storeA.runLeaseEpochKey(RUN_ID))).isEqualTo("1");
		assertThat(this.fake.string(this.storeA.fenceKey(RUN_ID)))
			.as("取号不写 fence（fence 只由 save 推进）").isNull();
	}

	// ── P-1：epoch == fence 是"可写的最小边界"，活租约不得被抢 ────────────────

	/**
	 * <b>{@code epoch == fence} 的有效租约不得被替换</b>（评审 P-1）。
	 *
	 * <h2>为什么这一个字符值得单独锁</h2>
	 * 脚本判据是 {@code curEpoch >= fence}。若哪天有人把它"顺手简化"成 {@code >}，现有 6 个用例
	 * <b>全部照绿</b>（其余用例的 fence 都是 0 或明确大于租约 epoch），而后果是：
	 * {@code epoch == fence} 的活租约 —— 恰好是"刚写过快照、仍能写"的最小合法边界 —— 会被别的实例抢走，
	 * 被抢方在续租失败前（≤1 个续租间隔）仍把自己当主人 ⇒ 双驱动窗口 + 一次无谓的号推进。
	 *
	 * <p>与 {@code FENCED_SET} 的判据对齐：`fence > epoch ⇒ 拒写`，所以 `epoch == fence` 的持有人
	 * <b>仍可写</b>，必须保留。
	 */
	@Test
	void aLeaseAtExactlyTheFenceIsValidAndIsNotStolen() {
		LeaseService.Lease held = this.storeA.tryAcquireRunLease(RUN_ID).orElseThrow();
		this.storeA.save(snapshot(RunSnapshot.RUNNING));
		assertThat(fence()).as("语料前提：save 之后 fence == held.epoch").isEqualTo(held.epoch());

		LeaseService.AcquireResult other = newStore("inst-x").acquireRunLease(RUN_ID);

		assertThat(other.outcome()).as("epoch == fence 的持有人仍可写 ⇒ 别人只能看到'有人持有'")
			.isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);
		assertThat(other.created()).as("不得新建一任").isFalse();
		assertThat(this.fake.string(this.storeA.runLeaseKey(RUN_ID)))
			.as("epoch == fence 的活租约不得被替换").isEqualTo(held.epoch() + "|inst-a");
		assertThat(this.fake.string(this.storeA.runLeaseEpochKey(RUN_ID)))
			.as("被拒的一方不得推进计数器").isEqualTo(String.valueOf(held.epoch()));
	}

	// ── Minor 1：fence 与租约键必须同槽（编程错误 → fail-fast，不静默降级） ──

	/**
	 * 传错 run 的 fence ⇒ <b>当场 {@link IllegalArgumentException}</b>，而不是 CROSSSLOT 被吞成
	 * {@code UNAVAILABLE}（那会把整轮静默降级成进程内互斥，现场只看到"租约设施不可用"）。
	 */
	@Test
	void fenceKeyFromAnotherRunIsRejectedInsteadOfSilentlyDegrading() {
		assertThatThrownBy(() -> this.leasesA.acquireFenced(this.storeA.runLeaseKey(RUN_ID),
				this.storeA.fenceKey(OTHER_RUN), TTL))
			.as("跨 run 的 fence 必须在入口就抛（不允许降到 UNAVAILABLE）")
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("同槽")
			.hasMessageContaining(OTHER_RUN);
	}

	/** 反向控制：无 tag 的资源锁 + 任何 fence 同样在入口被拒（它没有同槽 fence）。 */
	@Test
	void fenceOnAnUntaggedLockKeyIsRejected() {
		assertThatThrownBy(() -> this.leasesA.acquireFenced(LeaseKeys.task(7L), this.storeA.fenceKey(RUN_ID), TTL))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fence");
	}

	/** 正向控制：同 run 的 lease/fence 必须放行（守护不能把正常路径也挡掉）。 */
	@Test
	void matchingTagsStillAcquire() {
		LeaseService.AcquireResult result = this.leasesA.acquireFenced(this.storeA.runLeaseKey(RUN_ID),
				this.storeA.fenceKey(RUN_ID), TTL);

		assertThat(result.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(result.created()).isTrue();
	}

	// ── 语料与探针 ──────────────────────────────────────────────────────────

	/** 另起一个"进程/实例"：自己的 LeaseService 视图，共用同一份 Redis。 */
	private RunStore newStore(String instanceId) {
		return new RunStore(this.fake.redis, this.mapper, this.properties,
				new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(), instanceId));
	}


	private long fence() {
		String raw = this.fake.string(this.storeA.fenceKey(RUN_ID));
		return raw == null ? 0L : Long.parseLong(raw);
	}

	private RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("session-" + RUN_ID);
		snapshot.setStatus(status);
		return snapshot;
	}

}
