package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租约的 <b>owner 维度</b>（B23L1 批③a / 裁决 7；S8）。
 *
 * <h2>为什么租约需要 owner</h2>
 * 批②的租约把"持有者"隐含成实例标识（{@link LeaseService#instanceId()}）：同一个 Redis 键上只有
 * "本实例 / 别人"两种身份。L1 资源互斥换了一个问题——"这个<b>业务动作</b>有没有人在做"：
 * 同一个实例里，两个不同任务的导入必须互斥、同一个任务的两把同类锁必须互斥，
 * 而它们的主人都是同一个 instanceId。把 owner 显式化之后，{@code key} 定"锁的是什么"，
 * {@code owner} 定"谁持有"，实例标识只是 owner 的<b>缺省值</b>。
 *
 * <h2>实例面维度（M8 口径）</h2>
 * 本类所有用例都跑在<b>同一份 Redis 替身</b>（{@link FakeLeaseRedis}）上，两个 owner 共享同一个
 * {@link LeaseService} 实例 —— 这正是最容易写错的一面（进程内视图若只按裸键记账，第二个 owner
 * 会命中第一个的视图短路，报 ACQUIRED 而不去 Redis 比对）。跨实例那一面（两个 {@code LeaseService}、
 * 两个 instanceId）由 {@code LeaseServiceTest} / {@code LeaseServiceTriStateTest} 覆盖，本类不重复。
 *
 * <h2>断言的三条不变量</h2>
 * <ol>
 * <li><b>同键异 owner ⇒ 占用</b>：第二个 owner 拿到 {@code HELD_ELSEWHERE}，且原值不动、不取号；</li>
 * <li><b>同 owner 重入 ⇒ 不 SET</b>：{@code created=false}（没有释放权），epoch 不变；</li>
 * <li><b>释放/校验按 owner 圈定</b>：错 owner 的 release 不删键、verifyHeld 不作废别人的视图。</li>
 * </ol>
 */
class LeaseServiceDistinctOwnerTest {

	private static final String KEY = LeaseKeys.task(7L);

	private static final String OWNER_A = "task-7:import";

	private static final String OWNER_B = "task-7:publish";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler scheduler = new FakeRenewScheduler();

	private final LeaseService service = new LeaseService(this.fake.redis, this.properties, this.scheduler, "inst-a");

	private final Duration ttl = Duration.ofSeconds(30);

	@BeforeEach
	void resetCounters() {
		this.fake.resetCounters();
	}

	@Test
	void secondOwnerOnTheSameKeyIsHeldElsewhere() {
		LeaseService.AcquireResult first = this.service.acquire(KEY, this.ttl, OWNER_A);

		LeaseService.AcquireResult second = this.service.acquire(KEY, this.ttl, OWNER_B);

		assertThat(first.outcome()).as("空键：第一个 owner 抢到").isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(first.created()).as("这一任是本调用建的").isTrue();
		assertThat(second.outcome()).as("同键异 owner ⇒ 别人持有（不是重入）")
			.isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);
		assertThat(second.lease()).isNull();
		assertThat(this.fake.string(KEY)).as("原值不得被覆盖").isEqualTo(first.lease().epoch() + "|" + OWNER_A);
		assertThat(this.fake.epochCounter.get()).as("被拒不取号（号不回退）").isEqualTo(first.lease().epoch());
		assertThat(this.fake.sets.get()).as("只有创建者那一次 SET").isEqualTo(1);
	}

	@Test
	void sameOwnerIsReentrantWithCreatedFalseAndNoSecondSet() {
		LeaseService.Lease first = this.service.acquire(KEY, this.ttl, OWNER_A).lease();

		LeaseService.AcquireResult again = this.service.acquire(KEY, this.ttl, OWNER_A);

		assertThat(again.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(again.created()).as("重入不是创建者 ⇒ 没有释放权").isFalse();
		assertThat(again.lease().epoch()).isEqualTo(first.epoch());
		assertThat(this.fake.sets.get()).isEqualTo(1);
		assertThat(this.fake.scriptCalls.get()).as("进程内视图按 (key, owner) 短路：第二次连脚本都不进")
			.isEqualTo(1);
	}

	@Test
	void releaseIsOwnerScoped() {
		LeaseService.Lease held = this.service.acquire(KEY, this.ttl, OWNER_A).lease();

		// 伪造一个"同 epoch、错 owner"的租约：token 对不上 ⇒ 脚本不删
		assertThat(this.service.release(new LeaseService.Lease(KEY, held.epoch(), OWNER_B)))
			.as("错 owner 的 release 不得删别人的租约").isFalse();
		assertThat(this.fake.string(KEY)).isEqualTo(held.epoch() + "|" + OWNER_A);
		assertThat(this.service.releaseIfHeld(KEY, OWNER_B)).as("视图里也没有 B 这一任").isFalse();

		assertThat(this.service.releaseIfHeld(KEY, OWNER_A)).as("按 owner 释放自己那一任").isTrue();
		assertThat(this.fake.string(KEY)).isNull();
	}

	@Test
	void verifyHeldIsOwnerScopedAndKeepsTheOtherOwnersView() {
		LeaseService.Lease held = this.service.acquire(KEY, this.ttl, OWNER_A).lease();

		assertThat(this.service.verifyHeld(KEY, held.epoch(), OWNER_B))
			.as("错 owner 校验 ⇒ LOST（现值不是这一任）").isEqualTo(LeaseService.Hold.LOST);
		assertThat(this.service.heldEpoch(KEY, OWNER_A)).as("错 owner 的校验不得作废 A 的视图")
			.isEqualTo(held.epoch());

		assertThat(this.service.verifyHeld(KEY, held.epoch(), OWNER_A)).isEqualTo(LeaseService.Hold.HELD);
	}

	@Test
	void processLocalViewsAreKeyedByOwner() {
		LeaseService.Lease held = this.service.acquire(KEY, this.ttl, OWNER_A).lease();

		assertThat(this.service.heldEpoch(KEY, OWNER_A)).isEqualTo(held.epoch());
		assertThat(this.service.heldEpoch(KEY, OWNER_B)).as("同键不同 owner 的视图互相独立").isZero();
		assertThat(this.service.heldLease(KEY, OWNER_B)).isEmpty();
		assertThat(this.service.heldEpoch(KEY)).as("缺省 owner = 本实例标识，与本例的 owner 不是一任").isZero();
	}

	@Test
	void defaultOwnerIsTheInstanceIdSoLegacyCallsAreUnchanged() {
		LeaseService.Lease lease = this.service.acquire(KEY, this.ttl).lease();

		assertThat(lease.owner()).as("owner 缺省 = instanceId（批②语义）").isEqualTo("inst-a");
		assertThat(this.fake.string(KEY)).isEqualTo(lease.epoch() + "|inst-a");
		assertThat(this.service.heldEpoch(KEY)).isEqualTo(lease.epoch());

		assertThat(this.service.acquire(KEY, this.ttl, "some-other-owner").outcome())
			.as("显式 owner 与实例视图不共享 ⇒ 占用").isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);
	}

	@Test
	void renewComparesTheLeaseOwner() {
		LeaseService.Lease held = this.service.acquire(KEY, this.ttl, OWNER_A).lease();
		this.fake.expiries.put(KEY, Duration.ofSeconds(1));

		assertThat(this.service.renew(new LeaseService.Lease(KEY, held.epoch(), OWNER_B), this.ttl))
			.as("错 owner 不续（现值是 A 那一任）").isFalse();
		assertThat(this.fake.expiry(KEY)).isEqualTo(Duration.ofSeconds(1));

		assertThat(this.service.renew(held, this.ttl)).isTrue();
		assertThat(this.fake.expiry(KEY)).isEqualTo(this.ttl);
	}

	@Test
	void renewDrivingIsRegisteredPerOwner() {
		LeaseService.Lease held = this.service.acquire(KEY, this.ttl, OWNER_A).lease();
		this.service.markDriving(held);
		this.service.markDriving(held);

		assertThat(this.scheduler.activeCount()).as("同一 (key, owner) 只登记一个续租任务").isEqualTo(1);

		this.service.stopDriving(KEY, OWNER_A);
		assertThat(this.scheduler.activeCount()).as("按 owner 停续租").isZero();
	}

	@Test
	void tryAcquireOverloadsAgreeOnTheOwner() {
		Optional<LeaseService.Lease> acquired = this.service.tryAcquire(KEY, this.ttl, OWNER_A);

		assertThat(acquired).isPresent();
		assertThat(acquired.get().owner()).isEqualTo(OWNER_A);
		assertThat(this.service.tryAcquire(KEY, this.ttl, OWNER_B)).as("同键异 owner ⇒ empty").isEmpty();
	}

}
