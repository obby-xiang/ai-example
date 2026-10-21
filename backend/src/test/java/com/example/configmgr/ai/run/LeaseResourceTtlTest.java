package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源锁的 TTL 与开关（B23L1 批③a / 裁决 12、13；S4）。
 *
 * <h2>为什么要单独一个 TTL</h2>
 * 续跑权租约的 TTL（30s）约束的是"持租进程死后别的实例多快能接管<b>这一轮</b>"；资源锁约束的是
 * "同一任务上的同类动作多快能<b>重试</b>"。两者取值依据不同（动作时长 vs 轮次时长），
 * 所以 {@code app.ai.lease.resource-ttl} 是独立一键（缺省 60s），<b>不</b>复用 {@code lease.ttl}。
 *
 * <h2>TTL 到期 = 锁自动放</h2>
 * 持锁进程崩溃时没人来 release：锁必须靠 TTL 自清，否则该任务上的同类动作会被一把<b>没有主人</b>的锁
 * 永久挡住。{@link FakeLeaseRedis#advanceTime(Duration)} 提供一个可控时钟把这个语义钉住
 * （不 sleep 真实时间）：推进到 TTL 之前别人仍被挡，越过后新 owner 取到<b>更大的 epoch</b>。
 *
 * <p><b>实例面维度（M8 口径）</b>：同一份 Redis 替身上两个 owner；与 S8 同一底座，但这里断言的是
 * "时间推进"这一维，不是身份。
 */
class LeaseResourceTtlTest {

	private static final String KEY = LeaseKeys.job(7L, "import");

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final LeaseService service = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	@BeforeEach
	void resetCounters() {
		this.fake.resetCounters();
	}

	@Test
	void resourceTtlDefaultsToSixtySecondsAndTheLockSwitchIsOn() {
		assertThat(this.properties.getLease().getResourceTtl())
			.as("裁决 12：资源锁 TTL 缺省 60s（与续跑权 30s 分开）")
			.isEqualTo(Duration.ofSeconds(60));
		assertThat(this.properties.getLease().getTtl()).as("续跑权 TTL 不动（30s）")
			.isEqualTo(Duration.ofSeconds(30));
		assertThat(this.properties.getLock().isEnabled()).as("裁决 13：app.ai.lock.enabled 缺省 true").isTrue();
	}

	@Test
	void theResourceTtlIsAppliedToTheLockKey() {
		Duration resourceTtl = this.properties.getLease().getResourceTtl();

		LeaseService.AcquireResult acquired = this.service.acquire(KEY, resourceTtl, "owner-a");

		assertThat(acquired.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(this.fake.expiry(KEY)).as("锁键 TTL = resource-ttl").isEqualTo(resourceTtl);
		assertThat(this.fake.expiry(KEY)).isNotEqualTo(this.properties.getLease().getTtl());
	}

	@Test
	void theLockBlocksUntilTheTtlExpiresAndThenANewOwnerTakesOver() {
		Duration resourceTtl = this.properties.getLease().getResourceTtl();
		LeaseService.Lease first = this.service.acquire(KEY, resourceTtl, "owner-a").lease();

		this.fake.advanceTime(resourceTtl.minusSeconds(1));
		assertThat(this.service.acquire(KEY, resourceTtl, "owner-b").outcome())
			.as("TTL 未到：仍被别人持有").isEqualTo(LeaseService.AcquireOutcome.HELD_ELSEWHERE);

		this.fake.advanceTime(Duration.ofSeconds(2));
		assertThat(this.fake.string(KEY)).as("TTL 到点 ⇒ 键自清（没有主人时靠它放开）").isNull();

		LeaseService.AcquireResult second = this.service.acquire(KEY, resourceTtl, "owner-b");
		assertThat(second.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(second.lease().epoch()).as("换主后 epoch 严格更大（fencing 可比）")
			.isGreaterThan(first.epoch());
	}

	@Test
	void anExpiredOwnerViewStillLosesAgainstRedisTruth() {
		Duration resourceTtl = this.properties.getLease().getResourceTtl();
		LeaseService.Lease first = this.service.acquire(KEY, resourceTtl, "owner-a").lease();

		this.fake.advanceTime(resourceTtl.plusSeconds(1));

		assertThat(this.service.verifyHeld(KEY, first.epoch(), "owner-a"))
			.as("TTL 已到：进程内视图不再被当作持有（对着 Redis 真值校验）")
			.isEqualTo(LeaseService.Hold.LOST);
		assertThat(this.service.heldEpoch(KEY, "owner-a")).as("校验失败即作废该任视图").isZero();
	}

}
