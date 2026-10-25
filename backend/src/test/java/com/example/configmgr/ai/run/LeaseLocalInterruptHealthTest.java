package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;

import io.lettuce.core.RedisCommandInterruptedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <b>局部线程中断（取消/dispose 时的 boundedElastic 工具线程）不得扩散成全实例"设施不可用"</b>。
 *
 * <h2>缺陷形态（CI run 40211303032 的 W30 现场）</h2>
 * 取消一轮对话会 {@code dispose} 上游并把执行工具的那个 Reactor 线程中断，随后的 Redis 命令以
 * {@code RedisSystemException("Redis command interrupted")}（cause：
 * {@code io.lettuce.core.RedisCommandInterruptedException} ← {@code InterruptedException}）失败。
 * {@link LeaseService#acquire} 的 {@code catch (Exception)} 把这<B>线程局部</B>的失败记成
 * <b>进程级</b> {@code facilityHealthy=false} + {@code lastProbeOk=false}；而
 * {@link LeaseService#authority} 对"没有 held/ended 的轮次"要靠带 1s 节流的 PING 自证 ——
 * 节流窗口内复用到的正是那份 {@code false}。于是<b>同一实例上另一个完全正常的 B</b> 也会在
 * 这一拍被判成 {@code UNAVAILABLE}，终态/终帧被写门跳过（"别的轮次被误伤"）。
 *
 * <h2>本类锁的四件事</h2>
 * <ol>
 * <li>被中断的那一轮（A）<b>仍然只读</b>：它的"是否持租"确实不可知，不得继续本次写；</li>
 * <li>B 的写权限<b>只</b>由设施健康与 B 自己的租约事实决定，不替 A 的线程中断买单；</li>
 * <li>真连接失败 / 真宕机仍然 fail-closed（分类器不认文案、只认因果链里的 {@code InterruptedException}）。</li>
 * <li>那份"本轮只读"的墓碑<b>只属于 run 租约</b>（有 fence、写门读 {@code authority} 的那条路径）；
 * 资源锁（无 fence）的失败不落墓碑 —— 它的调用方不读 authority，也没有清理通道（m1）。</li>
 * </ol>
 *
 * <h2>为什么判据不能是"catch 现场 interrupt 位还在"</h2>
 * Lettuce 的同步等待就是 {@code CompletableFuture.get()}：中断时它<b>先清掉中断位</b>再抛
 * {@code InterruptedException}（{@code Thread.interrupted()}），spring-data-redis 再把
 * {@code RedisCommandInterruptedException} 折成 {@code RedisSystemException} 并<b>保留 cause</b>。
 * 所以 catch 现场 {@code isInterrupted()} 通常是假 —— 可靠证据只有因果链，
 * {@link #aCancelledThreadsInterruptMustNotMakeAnotherRunReadOnly()} 把这一事实显式断言下来。
 */
class LeaseLocalInterruptHealthTest {

	/** 生产键形：run 租约带 {@code {run:<runId>}} tag（三键同槽脚本才可能成立）。 */
	private static final String LEASE_A = "ai:lease:run:{run:cancel-a}";

	/** 与租约键同槽的 fence 键（{@link LeaseService#acquireFenced} 的第三键）。 */
	private static final String FENCE_A = "ai:fence:run:{run:cancel-a}";

	/** 同实例上另一个正常轮次（从未持租、也没有墓碑）。 */
	private static final String LEASE_B = "ai:lease:run:{run:cancel-b}";

	/** 第二个正常轮次（"中断之后的下一拍"）。 */
	private static final String LEASE_C = "ai:lease:run:{run:cancel-c}";

	/** 只用来把"设施刚被 PING 证过一遍"铺成前置状态。 */
	private static final String LEASE_PROBE = "ai:lease:run:{run:cancel-probe}";

	/**
	 * 资源锁（{@code ai:lock:task|job:*}）：<b>无 hash tag、无 fence</b>，owner 由调用方给出，
	 * 抢不到/抢失败时调用方按"没拿到锁"降级，<b>不读</b> {@code authority}。
	 */
	private static final String RESOURCE_LOCK = "ai:lock:task:cancel-lock-1";

	private static final Duration TTL = Duration.ofSeconds(30);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-cancel");

	@AfterEach
	void clearInterruptFlag() {
		// 用例把线程真的中断过：不留给后续用例（fork 复用时 pthread 会被复用）。
		Thread.interrupted();
	}

	/**
	 * <b>反例主证据</b>：A 的线程被取消（interrupt），不得让同实例的 B 在这一拍变成只读。
	 *
	 * <p>红色形态（修复前）：第 ③ 步 `expected NEVER_HELD but was UNAVAILABLE`。
	 */
	@Test
	void aCancelledThreadsInterruptMustNotMakeAnotherRunReadOnly() {
		// ① 前置：设施刚刚被 PING 自证成功 ⇒ lastProbeOk=true，探针时刻落在 1s 节流窗口内。
		assertThat(seedRecentlyVerifiedFacility())
			.as("前置：设施刚被 PING 证过一遍（探针结论 = 健康）")
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);

		// ② A 的驱动线程被取消：interrupt 置位，随后那条命令以生产同形异常失败。
		Thread.currentThread().interrupt();
		this.fake.beforeAcquire = () -> {
			throw interruptedRedisCommand();
		};
		LeaseService.AcquireResult cancelled = this.lease.acquireFenced(LEASE_A, FENCE_A, TTL);
		assertThat(cancelled.outcome()).as("被取消的这次抢租仍然失败（不得因此变成“拿到”）")
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(Thread.currentThread().isInterrupted())
			.as("真实形态：InterruptedException 抛出时中断位已被清掉（故识别口径只能看因果链）")
			.isFalse();
		assertThat(this.lease.authority(LEASE_A).readOnly())
			.as("被中断的这一轮：是否持租不可知 ⇒ 本轮只读（不得继续本次写）")
			.isTrue();

		// ③ 同一实例、另一轮正常 B（无 held、无 ended 墓碑）。
		assertThat(this.lease.authority(LEASE_B))
			.as("B 的写权限只取决于设施健康与 B 自己的租约事实，不该替 A 的线程中断买单")
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);

		// ④ 设施其实一直可用：一次 PING 就回绿 —— 上面那条只读不是"真宕机"换来的。
		assertThat(this.lease.leaseOk()).as("fake 的 PING 实际可用").isTrue();
	}

	/**
	 * <b>对照组</b>：真正的设施失败仍然 fail-closed（本修复不得放宽这条）。
	 */
	@Test
	void aRealFacilityFailureStillMakesEveryRunReadOnly() {
		this.fake.goDown();
		assertThat(this.lease.acquireFenced(LEASE_A, FENCE_A, TTL).outcome())
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(this.lease.authority(LEASE_B).readOnly())
			.as("真宕机 ⇒ 同实例别的轮次也必须只读").isTrue();
	}

	/**
	 * <b>对照组</b>：分类器认因果链、不认文案 —— 真连接失败（cause = {@code ConnectException}）
	 * 同样不含 {@code InterruptedException}，仍然作废设施健康位。
	 *
	 * <p>前置同样铺"刚自证成功"，两条用例的差别<b>只有</b>异常链的形状：一个含
	 * {@code InterruptedException}（局部中断）、一个不含（设施级失败）。
	 */
	@Test
	void aConnectionFailureIsNotMistakenForALocalInterrupt() {
		assertThat(seedRecentlyVerifiedFacility())
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);
		this.fake.beforeAcquire = () -> {
			throw new RedisConnectionFailureException("Redis command interrupted",
					new ConnectException("Connection refused"));
		};
		assertThat(this.lease.acquireFenced(LEASE_A, FENCE_A, TTL).outcome())
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(this.lease.authority(LEASE_B).readOnly())
			.as("文案一样但因果链不同（无 InterruptedException）⇒ 设施级失败，仍然只读").isTrue();
	}

	/**
	 * <b>同类洞</b>：探针（PING）本身在中断线程上失败时，同样不能把"本线程被打断"缓存成
	 * "设施坏了"——否则 1s 节流窗口内的下一个调用方会复用这份假结论。
	 */
	@Test
	void anInterruptedProbeIsNotCachedAsAFacilityVerdict() {
		// ① 先观测到一次真实失败（设施位已假，下面才会走 PING 自证）。
		this.fake.goDown();
		assertThat(this.lease.acquireFenced(LEASE_A, FENCE_A, TTL).outcome())
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		this.fake.heal();

		// ② PING 只在"本线程被中断"时失败（复刻 lettuce 的 await：中断 ⇒ 命令中断，链路本身正常）。
		when(this.fake.redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
			if (Thread.currentThread().isInterrupted()) {
				throw interruptedRedisCommand();
			}
			return "PONG";
		});

		Thread.currentThread().interrupt();
		LeaseService.LeaseAuthority whileInterrupted = this.lease.authority(LEASE_B);
		assertThat(whileInterrupted.readOnly())
			.as("中断线程上问不出结论 ⇒ 本次判定只读（fail-closed）").isTrue();

		// ③ 中断结束后必须立刻能重探，而不是复用节流窗口里的"坏"。
		assertThat(this.lease.authority(LEASE_C))
			.as("探针被中断不是设施结论：不得在 1s 节流窗口里被复用成“设施坏了”")
			.isEqualTo(LeaseService.LeaseAuthority.NEVER_HELD);
	}

	/**
	 * <b>m1：局部中断的墓碑只属于 run 租约（有 fence 的那条路径）</b>。
	 *
	 * <p>资源锁（{@code ai:lock:task|job:*}，无 tag、无 fence）的调用方
	 * （{@code ToolResourceLocks}、{@code SessionPurgeRetryRunner}）<b>不读</b> {@code authority}，
	 * 失败的 {@code acquire} 也没有 release/verify 去清墓碑 ⇒ 若在这里也
	 * {@code markEnded}，每次"取消恰好打断资源锁抢用"都会往无 TTL、无容量淘汰的
	 * {@code ended} 里留一条永久条目。
	 *
	 * <p>要保住的两件事：原操作<b>仍然</b> {@code UNAVAILABLE}（调用方按"没拿到锁"降级），
	 * 以及不留下无人清理的墓碑。run 租约那条路径的墓碑由
	 * {@link #aCancelledThreadsInterruptMustNotMakeAnotherRunReadOnly()} 锁住（必须留）。
	 */
	@Test
	void aLocallyInterruptedResourceLockLeavesNoTombstone() {
		String owner = "inst-cancel:run-lock-1:call-1";
		Thread.currentThread().interrupt();
		this.fake.beforeAcquire = () -> {
			throw interruptedRedisCommand();
		};

		LeaseService.AcquireResult lock = this.lease.acquire(RESOURCE_LOCK, TTL, owner);
		assertThat(lock.outcome()).as("无 fence 的资源锁被局部中断：原操作仍然失败（fail-closed）")
			.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
		assertThat(endedTombstones())
			.as("资源锁调用方不读 authority、也没有清理通道 ⇒ 不得留下永久墓碑")
			.doesNotContainKey(LeaseService.viewKey(RESOURCE_LOCK, owner));
	}

	/** 铺"设施刚自证成功"（{@code lastProbeOk=true} + 节流锚点 = 现在）。 */
	private LeaseService.LeaseAuthority seedRecentlyVerifiedFacility() {
		this.fake.goDown();
		assertThat(this.lease.leaseOk()).as("先让设施位变假，节流分支才可能被走到").isFalse();
		this.fake.heal();
		return this.lease.authority(LEASE_PROBE);
	}

	/**
	 * {@code ended}（"这一任已结束"的墓碑表）当前内容。
	 *
	 * <p>用反射读而不加生产 getter：这是内部状态，只为断言"没有留下无人清理的条目"。
	 */
	@SuppressWarnings("unchecked")
	private Map<String, LeaseService.LeaseAuthority> endedTombstones() {
		return (Map<String, LeaseService.LeaseAuthority>) ReflectionTestUtils.getField(this.lease, "ended");
	}

	/**
	 * 生产同形的"命令被中断"异常链：
	 * {@code RedisSystemException("Redis command interrupted")} ←
	 * {@code io.lettuce.core.RedisCommandInterruptedException} ← {@code InterruptedException}。
	 *
	 * <p>同时复刻 {@code CompletableFuture.get} 的行为：<b>抛出前已经清掉</b>线程中断位。
	 */
	private static RedisSystemException interruptedRedisCommand() {
		Thread.interrupted();
		return new RedisSystemException("Redis command interrupted",
				new RedisCommandInterruptedException(new InterruptedException()));
	}

}
