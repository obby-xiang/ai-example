package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link LeaseService} 的设施语义（B23L1 批②a / 设计稿 §2.2）。
 *
 * <h2>为什么整族都走 Lua</h2>
 * 现实现（{@code ResumeService#releaseResumeLock}）是 {@code GET} → Java 比对 → {@code DEL}：
 * 比对与删除之间租约可能已经过期并被别的实例重抢，于是<b>删掉别人的租约</b>（本类 L6 锁的就是它）。
 * 抢/续/放三条路径因此全部收敛成一次 {@code redis.execute(RedisScript, keys, args)}（L7）。
 *
 * <p>epoch 取号是<b>与租约键同槽</b>的 per-run 计数器（{@code ai:lease:epoch:{run:<runId>}}），
 * {@code INCR + SET} 在同一条 Lua 里（评审 I-1：取号与占用一旦分开，迟到者会用更小的号占租、
 * 让这一轮永久写不进去）。计数器与 fence 同面同寿：TTL 只约束"这一任/这一轮活多久"，
 * 但不允许比 fence 先过期（否则号从 1 重来又被残留 fence 拒死）。L4 锁"新 epoch 严格更大"。
 */
class LeaseServiceTest {

	/**
	 * 生产键形（run 租约带 {@code {run:<runId>}} tag）：取号走<b>与租约键同槽</b>的 per-run 计数器，
	 * {@code INCR + SET} 在同一条 Lua 里 —— 这才是线上跑的那条路径（评审 I-1）。
	 * 无 tag 的资源锁路径由 {@code LeaseServiceDistinctOwnerTest} 覆盖。
	 */
	private static final String KEY = "ai:lease:run:{run:r-1}";

	/** 与 {@link #KEY} 同槽的取号计数器（LeaseService 按 tag 推导，这里直写以免测试自证）。 */
	private static final String EPOCH_KEY = "ai:lease:epoch:{run:r-1}";

	/** 与 {@link #KEY} 同槽的 fence（生产路径的第三个键：取号时与它对齐）。 */
	private static final String FENCE_KEY = "ai:fence:run:{run:r-1}";

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

	/** L1：空键可抢，epoch &gt; 0，TTL = 30s。 */
	@Test
	void acquiresAFreeKeyWithAFreshEpochAndTheConfiguredTtl() {
		Optional<LeaseService.Lease> acquired = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl);

		assertThat(acquired).isPresent();
		assertThat(acquired.get().epoch()).as("epoch 由 per-run INCR 发放，从 1 起").isPositive();
		assertThat(acquired.get().owner()).isEqualTo("inst-a");
		assertThat(this.fake.expiry(KEY)).as("租约 TTL = 传入的 30s").isEqualTo(Duration.ofSeconds(30));
		assertThat(this.fake.string(KEY)).isEqualTo(acquired.get().epoch() + "|inst-a");
		assertThat(this.fake.string(EPOCH_KEY)).as("per-run 计数器（与租约键同槽）").isEqualTo(
				String.valueOf(acquired.get().epoch()));
		assertThat(this.fake.string(LeaseService.EPOCH_KEY)).as("旧全局计数器不再参与 run 租约取号").isNull();
	}

	/** L2：已被他实例占用 ⇒ 拒绝且不覆盖原值、不消耗新 epoch。 */
	@Test
	void refusesAnOccupiedKeyWithoutOverwritingIt() {
		LeaseService.Lease held = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		String epochAfterFirst = this.fake.string(EPOCH_KEY);

		assertThat(this.other.tryAcquireFenced(KEY, FENCE_KEY, this.ttl)).as("占用者不是自己 ⇒ 抢不到").isEmpty();

		assertThat(this.fake.string(KEY)).as("原值不得被覆盖").isEqualTo(held.epoch() + "|inst-a");
		assertThat(this.fake.string(EPOCH_KEY)).as("被拒不取号（GET 命中在 INCR 之前返回）").isEqualTo(epochAfterFirst);
	}

	/** L3：同实例可重入 —— 同一 epoch，且 Redis 只发生一次 SET。 */
	@Test
	void reentrantAcquireReturnsTheSameEpochWithASingleSet() {
		LeaseService.Lease first = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		LeaseService.Lease second = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();

		assertThat(second.epoch()).as("可重入 ⇒ 同一 epoch").isEqualTo(first.epoch());
		assertThat(this.fake.sets.get()).as("可重入不得再 SET 一次").isEqualTo(1);
		assertThat(this.fake.scriptCalls.get()).as("进程内持租视图短路：第二次连脚本都不进").isEqualTo(1);
	}

	/** L4：TTL 到点后可被别的实例抢到，且新 epoch 严格更大（号不回退）。 */
	@Test
	void expiredLeaseCanBeAcquiredWithAStrictlyGreaterEpoch() {
		LeaseService.Lease first = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		this.fake.dropKey(KEY);

		LeaseService.Lease second = this.other.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();

		assertThat(second.epoch()).as("过期后重抢：新 epoch 严格更大").isGreaterThan(first.epoch());
		assertThat(second.owner()).isEqualTo("inst-b");
	}

	/** L5：续租比对 epoch —— 错 epoch / 错 owner 一律不续（返回 false，TTL 不动）。 */
	@Test
	void renewRejectsAMismatchedEpochOrOwner() {
		LeaseService.Lease lease = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		this.fake.expiries.put(KEY, Duration.ofSeconds(1));

		assertThat(this.service.renew(new LeaseService.Lease(KEY, lease.epoch() + 1, "inst-a"), this.ttl))
				.as("错 epoch ⇒ 不续").isFalse();
		assertThat(this.service.renew(new LeaseService.Lease(KEY, lease.epoch(), "inst-b"), this.ttl))
				.as("错 owner ⇒ 不续").isFalse();
		assertThat(this.fake.expiry(KEY)).as("失败的续租不得改 TTL").isEqualTo(Duration.ofSeconds(1));

		assertThat(this.service.renew(lease, this.ttl)).as("对得上 ⇒ 续").isTrue();
		assertThat(this.fake.expiry(KEY)).isEqualTo(Duration.ofSeconds(30));
	}

	/** L6：释放比对 epoch —— 错 epoch 不删（GET-比对-DELETE 非原子的缺陷锁）。 */
	@Test
	void releaseOnlyDeletesItsOwnEpoch() {
		LeaseService.Lease lease = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();

		assertThat(this.service.release(new LeaseService.Lease(KEY, lease.epoch() + 1, "inst-a")))
				.as("错 epoch ⇒ 不删（否则会删掉新主人的租约）").isFalse();
		assertThat(this.fake.string(KEY)).isEqualTo(lease.epoch() + "|inst-a");

		assertThat(this.service.release(lease)).as("对得上 ⇒ 删").isTrue();
		assertThat(this.fake.string(KEY)).isNull();
		assertThat(this.fake.scriptDeletes.get()).as("删除发生在脚本里（比对与删除同一原子步）").isEqualTo(1);
	}

	/** L7：抢/续/放三条写路径都只走 Lua —— 不出现裸 DEL / SET / HSET。 */
	@Test
	void allWritePathsGoThroughOneScriptCallEach() {
		LeaseService.Lease lease = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		this.service.renew(lease, this.ttl);
		this.service.release(lease);

		verify(this.fake.redis, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
		verify(this.fake.redis, never()).delete(org.mockito.ArgumentMatchers.anyString());
		verify(this.fake.redis, never()).delete(anyList());
		assertThat(this.fake.templateDeletes.get()).as("模板级 DEL 一次都没有").isZero();
		assertThat(this.fake.scriptCalls.get()).isEqualTo(3);
	}

	/** L8：Redis 不可用 ⇒ 显式降级（返回 empty、不抛、leaseOk=false）。 */
	@Test
	void degradesExplicitlyWhenRedisIsDown() {
		this.fake.down = true;

		assertThatCode(() -> {
			assertThat(this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl)).as("降级 = 调用方走进程内互斥").isEmpty();
			assertThat(this.service.renew(new LeaseService.Lease(KEY, 1L, "inst-a"), this.ttl)).isFalse();
			assertThat(this.service.release(new LeaseService.Lease(KEY, 1L, "inst-a"))).isFalse();
		}).doesNotThrowAnyException();

		assertThat(this.service.leaseOk()).as("降级必须可观测").isFalse();
		assertThat(this.service.heldEpoch(KEY)).as("降级期间不假装持租").isZero();

		this.fake.down = false;
		assertThat(this.service.leaseOk()).as("探针恢复后回绿").isTrue();
	}

	/** L9：{@code heldEpoch} 是进程内所有权视图（按 key 独立、释放即归零）。 */
	@Test
	void heldEpochIsAProcessLocalOwnershipView() {
		assertThat(this.service.heldEpoch(KEY)).isZero();

		LeaseService.Lease lease = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		assertThat(this.service.heldEpoch(KEY)).isEqualTo(lease.epoch());
		assertThat(this.service.heldEpoch("ai:lease:run:other")).as("键之间互不影响").isZero();
		assertThat(this.other.heldEpoch(KEY)).as("别的实例不共享进程内视图").isZero();

		this.service.release(lease);
		assertThat(this.service.heldEpoch(KEY)).isZero();
	}

	/** L10：markDriving 按 renewInterval 续租，release 之后停。 */
	@Test
	void markDrivingRenewsOnTheConfiguredIntervalUntilRelease() {
		LeaseService.Lease lease = this.service.tryAcquireFenced(KEY, FENCE_KEY, this.ttl).orElseThrow();
		this.service.markDriving(lease);
		this.service.markDriving(lease);

		assertThat(this.scheduler.activeCount()).as("同一个 key 只登记一个续租任务").isEqualTo(1);
		assertThat(this.scheduler.interval()).as("续租间隔 = app.ai.lease.renew-interval（10s）")
				.isEqualTo(Duration.ofSeconds(10));

		this.fake.expiries.put(KEY, Duration.ofSeconds(1));
		this.scheduler.tick();
		assertThat(this.fake.renewCalls.get()).as("tick 一次 ⇒ 续租一次").isEqualTo(1);
		assertThat(this.fake.expiry(KEY)).isEqualTo(Duration.ofSeconds(30));

		this.service.release(lease);
		this.scheduler.tick();
		assertThat(this.fake.renewCalls.get()).as("释放之后不再续租").isEqualTo(1);
		assertThat(this.scheduler.activeCount()).isZero();
	}

}
