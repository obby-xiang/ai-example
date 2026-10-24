package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租约的三态与续租存活判据（B23L1 批②b 复审收口 I3 / I4 / I7）。
 *
 * <h2>I3 归属校验</h2>
 * {@link LeaseService#verifyHeld(String, long)} 是"入口处决定要不要驱动这一轮"的判定口：
 * 一次往返、比对在 Lua 内完成。它与 {@code heldEpoch}（进程内视图、零往返）的分工见方法注 ——
 * 视图在"TTL 已到、续租还没失败"的窗口里会说谎，而这个判定恰好容不得说谎。
 *
 * <h2>I4 三态抢租</h2>
 * <b>被占用</b>与<b>设施不可用</b>必须分开：前者是"这一轮已经有主，我什么都不该做"，
 * 后者是"我不知道有没有主，按进程内互斥继续"。合并成一种，一次 Redis 抖动就会让
 * 启动续跑/运维续跑/死卡兜底全线停摆。
 *
 * <h2>I7 僵尸续租</h2>
 * 续租任务是不看业务的定时器；没有存活判据时，驱动线程死掉、快照停在 RUNNING 的轮会被
 * <b>永远</b>续租，别的实例既接不了管、运维也看不出"这个租约已经没有主人"。
 */
class LeaseServiceTriStateTest {

	/** 生产键形：run 租约带 {@code {run:<runId>}} tag（三键同槽脚本才可能成立）。 */
	private static final String KEY = "ai:lease:run:{run:r-tri}";

	/** 与租约键同槽的 fence 键（三键脚本的第三键）。 */
	private static final String FENCE_KEY = "ai:fence:run:{run:r-tri}";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler scheduler = new FakeRenewScheduler();

	private final LeaseService service = new LeaseService(this.fake.redis, this.properties, this.scheduler, "inst-a");

	private final LeaseService other = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-b");

	private final Duration ttl = Duration.ofSeconds(30);

	@BeforeEach
	void resetCounters() {
		this.fake.resetCounters();
	}

	// ── I4 三态 ────────────────────────────────────────────────────────────

	@Test
	void acquireDistinguishesOccupiedFromUnavailable() {
		this.other.tryAcquire(KEY, this.ttl).orElseThrow();

		LeaseService.AcquireResult occupied = this.service.acquireFenced(KEY, FENCE_KEY, this.ttl);

		assertThat(occupied.outcome()).as("别人持有 ⇒ HELD_ELSEWHERE（语义：这一轮已有主）")
			.isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);
		assertThat(occupied.lease()).isNull();
		assertThat(this.service.tryAcquire(KEY, this.ttl)).as("薄包装：仍然只回答拿到没有").isEmpty();

		this.fake.down = true;
		LeaseService.AcquireResult unavailable = this.service.acquire("ai:lease:run:other", this.ttl);

		assertThat(unavailable.outcome()).as("设施不可用 ⇒ UNAVAILABLE（语义：按进程内互斥继续）")
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(unavailable.acquired()).isFalse();
		assertThat(this.service.leaseOk()).as("降级可观测").isFalse();
	}

	// ── created：谁创建谁释放 ─────────────────────────────────────────────

	/**
	 * {@code created} 是"谁创建谁释放"的唯一判据（harness lease-scripts 抓到的真实缺陷的设施层）。
	 *
	 * <p>并发同主双抢时，两个调用方都会 {@code ACQUIRED}、owner 都是自己 —— 只有"这一任是谁建的"
	 * 能区分释放权。用"抢占前 epoch 变了没变"推断必然同形（两边读到同一个前置状态）。
	 */
	@Test
	void createdFlagPinpointsTheCreatorEvenUnderReentrantAcquire() {
		LeaseService.AcquireResult first = this.service.acquireFenced(KEY, FENCE_KEY, this.ttl);
		assertThat(first.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(first.created()).as("首抢 = 创建者").isTrue();

		LeaseService.AcquireResult reentrant = this.service.acquireFenced(KEY, FENCE_KEY, this.ttl);
		assertThat(reentrant.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(reentrant.lease().epoch()).as("可重入：同一任").isEqualTo(first.lease().epoch());
		assertThat(reentrant.created()).as("进程内视图短路 ⇒ 不是创建者（没有释放权）").isFalse();

		// 另一个实例：命中现值（owner 不是自己）⇒ 占用，且同样没有释放权
		LeaseService.AcquireResult occupied = this.other.acquireFenced(KEY, FENCE_KEY, this.ttl);
		assertThat(occupied.outcome()).isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);
		assertThat(occupied.created()).isFalse();

		this.fake.down = true;
		LeaseService.AcquireResult unavailable = this.service.acquire("ai:lease:run:other", this.ttl);
		assertThat(unavailable.outcome()).isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(unavailable.created()).as("没抢到就没有创建者身份").isFalse();
	}

	// ── I3 归属校验 ────────────────────────────────────────────────────────

	@Test
	void verifyHeldAnswersAgainstRedisTruthInOneRoundTrip() {
		LeaseService.Lease lease = this.service.tryAcquire(KEY, this.ttl).orElseThrow();
		int scriptCallsBefore = this.fake.scriptCalls.get();

		assertThat(this.service.verifyHeld(KEY, lease.epoch())).isEqualTo(LeaseService.Hold.HELD);
		assertThat(this.fake.verifyCalls.get()).as("一次往返").isEqualTo(1);
		assertThat(this.fake.scriptCalls.get()).isEqualTo(scriptCallsBefore + 1);
		assertThat(this.fake.scriptDeletes.get()).as("校验只读（不删不改）").isZero();
		assertThat(this.fake.expiry(KEY)).as("校验不动 TTL").isEqualTo(this.ttl);

		// 租约过期后被别人接管：进程内视图还没被续租失败清掉，但真值已经换主
		this.fake.dropKey(KEY);
		this.other.tryAcquire(KEY, this.ttl).orElseThrow();
		assertThat(this.service.verifyHeld(KEY, lease.epoch())).as("真值已换主 ⇒ LOST")
			.isEqualTo(LeaseService.Hold.LOST);
		assertThat(this.service.heldEpoch(KEY)).as("校验失败即作废进程内视图").isZero();

		this.fake.down = true;
		assertThat(this.service.verifyHeld(KEY, lease.epoch())).as("设施不可用 ⇒ UNAVAILABLE（调用方按无租约处理）")
			.isEqualTo(LeaseService.Hold.UNAVAILABLE);
	}

	// ── I7 续租存活判据 ────────────────────────────────────────────────────

	@Test
	void markDrivingStopsAndReleasesOnceTheLivenessPredicateTurnsFalse() {
		LeaseService.Lease lease = this.service.tryAcquire(KEY, this.ttl).orElseThrow();
		AtomicBoolean alive = new AtomicBoolean(true);
		this.service.markDriving(lease, alive::get);

		this.scheduler.tick();
		assertThat(this.fake.renewCalls.get()).as("判据为真 ⇒ 正常续租").isEqualTo(1);
		assertThat(this.fake.string(KEY)).isEqualTo(lease.epoch() + "|inst-a");

		alive.set(false);
		this.scheduler.tick();

		assertThat(this.fake.renewCalls.get()).as("判据转假后不再续租（也不再往返 Redis）").isEqualTo(1);
		assertThat(this.fake.string(KEY)).as("判据转假 ⇒ 主动归还租约（别让接管方等 TTL）").isNull();
		assertThat(this.fake.scriptDeletes.get()).as("归还是 epoch scoped 的脚本删除").isEqualTo(1);
		assertThat(this.scheduler.activeCount()).as("续租任务已停").isZero();
		assertThat(this.service.heldEpoch(KEY)).isZero();
	}

	// ── R3 条件删除：陈旧操作不得动"当前那一任" ──────────────────────────

	/**
	 * 本进程先持 epoch1、续租失败后视图作废；再抢到 epoch2 并登记续租。
	 * 此时一个<b>迟到的</b> {@code release(epoch1)} / {@code verifyHeld(key, epoch1)} 到达 ——
	 * 它只能承认"这一任已经没了"，绝不能再摘掉 epoch2 的视图、更不能停掉 epoch2 的续租任务
	 * （旧实现无条件 {@code stopDriving} + {@code held.remove(key)}，于是新一任被陈旧操作误杀）。
	 */
	@Test
	void staleReleaseOrVerifyDoesNotDropTheCurrentHold() {
		LeaseService.Lease first = this.service.tryAcquire(KEY, this.ttl).orElseThrow();
		this.service.markDriving(first);
		this.fake.dropKey(KEY);
		this.scheduler.tick(); // 续租失败 ⇒ 视图作废、任务停（这一任确实没了）
		assertThat(this.service.heldEpoch(KEY)).isZero();

		LeaseService.Lease second = this.service.tryAcquire(KEY, this.ttl).orElseThrow();
		assertThat(second.epoch()).isGreaterThan(first.epoch());
		this.service.markDriving(second);
		this.fake.resetCounters();

		assertThat(this.service.release(first)).as("错 epoch 不删（脚本已保证）").isFalse();
		assertThat(this.service.heldEpoch(KEY)).as("陈旧 release 不得摘掉当前持有的视图")
			.isEqualTo(second.epoch());
		this.scheduler.tick();
		assertThat(this.fake.renewCalls.get()).as("当前持有者的续租任务不得被陈旧 release 停掉").isEqualTo(1);

		assertThat(this.service.verifyHeld(KEY, first.epoch())).isEqualTo(LeaseService.Hold.LOST);
		assertThat(this.service.heldEpoch(KEY)).as("陈旧 epoch 的校验不得摘掉当前持有的视图")
			.isEqualTo(second.epoch());
	}

	@Test
	void markDrivingWithoutAPredicateKeepsTheLegacyBehaviour() {
		LeaseService.Lease lease = this.service.tryAcquire(KEY, this.ttl).orElseThrow();
		this.service.markDriving(lease);

		this.scheduler.tick();
		this.scheduler.tick();

		assertThat(this.fake.renewCalls.get()).as("无判据 = 恒真（既有调用口径不变）").isEqualTo(2);
		assertThat(this.fake.string(KEY)).isEqualTo(lease.epoch() + "|inst-a");
	}

}
