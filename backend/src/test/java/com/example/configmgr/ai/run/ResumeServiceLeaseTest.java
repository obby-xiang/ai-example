package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.LeaseService.Lease;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B23L1 批②b：{@link ResumeService} 的<b>续跑权租约</b>接线（方案第 3 条）。
 *
 * <h2>两条签名、一套判据</h2>
 * <ul>
 * <li><b>旧签名</b>（{@code resume(runId)} / {@code resume(runId, boolean)}，所有既有调用方）：
 * 自抢租约 → 走带租约重载 → 非 {@code ACCEPTED} 立刻还租；{@code ACCEPTED} 则把租约交给
 * 续租调度器（终态由 {@code RunStore.save} 释放）。调用方零改动即获得 fencing；</li>
 * <li><b>带租约重载</b>（{@code resume(runId, forceTakeover, lease)}，确认门死卡兜底用）：
 * <b>不再自抢</b>（租约已在调用方手里），只校验归属 —— {@code LeaseService#verifyHeld(key, epoch)}
 * 对着 <b>Redis 真值</b>做（R5；进程内 {@code heldRunEpoch} 在"TTL 已到、续租未失败"的窗口里
 * 会说谎），不符即 {@link ResumeService.Outcome#LEASE_HELD_ELSEWHERE}；设施不可用按无租约降级放行。</li>
 * </ul>
 *
 * <p>{@code ai:resume:lock:} 已摘除：GET-比对-DEL 的非原子实现会删掉别人的锁，
 * 且与租约是同一件事的两套口径。进程内 {@code resuming} 表保留（同进程并发续跑的短路）。
 *
 * <h2>实例面维度（M8 / DC-11 边界）</h2>
 * <ul>
 * <li><b>单实例即可达</b>：旧签名自抢/放、重载的归属校验（{@code verifyHeld}）、
 *     {@code describe()} 的键面、Redis 不可用时的降级（I4）；</li>
 * <li><b>需要第二个实例</b>：{@code legacySignatureReportsHeldElsewhere...} 两例 ——
 *     "租约在别人手里"只能由另一个 {@link LeaseService}（inst-b）制造，单实例看到的是可重入；</li>
 * <li><b>不覆盖</b>：真跨进程的"接管瞬间"时序（旧主人还在驱动、新主人已抢到）—— 那需要
 *     双实例演练（DC-11），本类只锁"入口判定"这一层。</li>
 * </ul>
 */
class ResumeServiceLeaseTest {

	private static final String RUN_ID = "run-lease-resume";

	private static final String SESSION_ID = "s-lease-resume";

	private static final String CALL_ID = "call-lease-resume";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler renewScheduler = new FakeRenewScheduler();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, this.renewScheduler,
			"inst-a");

	private final LeaseService otherLeases = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-b");

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service;

	ResumeServiceLeaseTest() {
		this.store.useLeaseFacility(this.leases);
		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
	}

	@BeforeEach
	void setUp() {
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("已执行");
		// 续跑驱动同步跑完（断言的是"续跑被驱动"，不是线程时序）
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
	}

	// ── 旧签名：自抢 ───────────────────────────────────────────────────────

	@Test
	void legacySignatureAcquiresTheLeaseAndHandsItToTheRenewSchedulerOnAccepted() {
		seedResumableRun();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		long epoch = this.store.heldRunEpoch(RUN_ID);
		assertThat(epoch).as("旧签名自抢（调用方零改动即获得 fencing）").isPositive();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isEqualTo(epoch + "|inst-a");
		assertThat(this.renewScheduler.activeCount()).as("ACCEPTED ⇒ 交续租调度器").isEqualTo(1);
	}

	@Test
	void legacySignatureReleasesTheLeaseWhenItRefusesToDrive() {
		seedResumableRun();
		// 仍 PENDING 的外部挂起项（确认门）：续跑拒绝驱动（PENDING_UNRESOLVED）
		PendingToolCall pending = entry(PendingToolCall.PENDING);
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		this.store.putPending(RUN_ID, pending);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("没驱动 ⇒ 当场还租").isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isNull();
		assertThat(this.renewScheduler.activeCount()).as("没驱动 ⇒ 不登记续租").isZero();
		verify(this.chatService, never()).driveResumed(any(), any(), any(), any(), any());
	}

	/** C2：取消状态未知 = typed 拒绝（不是 RuntimeException），且不驱动、当场还租。 */
	@Test
	void unknownCancelStateRefusesWithATypedOutcomeAndReleasesTheLease() {
		seedResumableRun();
		when(this.cancellations.cancelState(RUN_ID))
			.thenReturn(CancellationRegistry.CancelState.UNKNOWN);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
		assertThat(result.body()).containsEntry("reason", "CANCEL_STATE_UNKNOWN");
		verify(this.chatService, never()).driveResumed(any(), any(), any(), any(), any());
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("未驱动 ⇒ 当场还租").isZero();
	}

	@Test
	void legacySignatureReportsHeldElsewhereWithoutCallingTheDriver() {
		seedResumableRun();
		long heldByOther = this.otherLeases.tryAcquire(this.store.runLeaseKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow().epoch();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.LEASE_HELD_ELSEWHERE);
		assertThat(result.body()).containsEntry("resumed", false).containsEntry("reason", "LEASE_HELD_ELSEWHERE");
		assertThat(this.store.get(RUN_ID).getStatus()).as("抢不到租约不得改状态").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isEqualTo(heldByOther + "|inst-b");
		verify(this.executor, never()).execute(any(Runnable.class));
	}

	@Test
	void legacySignatureReportsHeldElsewhereForForceTakeoverToo() {
		seedResumableRun();
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);
		this.otherLeases.tryAcquire(this.store.runLeaseKey(RUN_ID), this.properties.getLease().getTtl())
			.orElseThrow();

		// 启动自动续跑的僵尸接管（forceTakeover=true）同样服从 fencing：租约在别人手里就不动
		ResumeService.ResumeResult result = this.service.resume(RUN_ID, true);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.LEASE_HELD_ELSEWHERE);
		verify(this.executor, never()).execute(any(Runnable.class));
	}

	// ── 带租约重载：只校验归属，不再自抢 ───────────────────────────────────

	@Test
	void overloadValidatesOwnershipWithoutAcquiringAndLeavesTheRenewalToTheCaller() {
		seedResumableRun();
		Lease lease = this.leases.tryAcquire(this.store.runLeaseKey(RUN_ID), this.properties.getLease().getTtl())
			.orElseThrow();
		int scriptCallsBefore = this.fake.scriptCalls.get();
		int setsBefore = this.fake.sets.get();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, false, lease);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		// I3：重载只做一次归属校验（一次往返），不抢（不 SET）、不还（不 DEL）
		assertThat(this.fake.verifyCalls.get()).as("归属校验走一次 verifyHeld").isEqualTo(1);
		assertThat(this.fake.scriptCalls.get()).as("重载的全部往返 = 一次校验").isEqualTo(scriptCallsBefore + 1);
		assertThat(this.fake.sets.get()).as("不抢（不写租约）").isEqualTo(setsBefore);
		assertThat(this.fake.scriptDeletes.get()).as("不还（不删租约）").isZero();
		assertThat(this.store.heldRunEpoch(RUN_ID)).isEqualTo(lease.epoch());
		assertThat(this.renewScheduler.activeCount()).as("续租调度由调用方（ConfirmGate）接手").isZero();
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(), eq(this.emitter));
	}

	@Test
	void overloadRejectsAForeignOrStaleLease() {
		seedResumableRun();
		this.leases.tryAcquire(this.store.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		Lease stale = new Lease(this.store.runLeaseKey(RUN_ID), 999L, "inst-a");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, false, stale);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.LEASE_HELD_ELSEWHERE);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		verify(this.executor, never()).execute(any(Runnable.class));
	}

	// ── I4：Redis 不可用 ⇒ 无租约降级（不得报 LEASE_HELD_ELSEWHERE） ────────

	/**
	 * 租约设施不可用时，旧签名必须<b>放行</b>（按 {@code leaseService == null} 的同一条口径走进程内互斥），
	 * 而不是把"抢不到"误报成"别人持有"—— 那会让 Redis 抖一下就把启动续跑/运维续跑全部卡死。
	 * 降级是否发生由 {@code leaseOk()} 可观测。
	 */
	@Test
	void redisOutageDegradesToTheInProcessMutexInsteadOfRefusingTheResume() {
		StringRedisTemplate broken = mock(StringRedisTemplate.class);
		when(broken.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
				org.mockito.ArgumentMatchers.anyList(), any(Object[].class)))
			.thenThrow(new IllegalStateException("Redis 不可用（模拟宕机）"));
		LeaseService brokenLeases = new LeaseService(broken, this.properties, new FakeRenewScheduler(), "inst-a");
		this.store.useLeaseFacility(brokenLeases);
		seedResumableRun();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("Redis 不可用 ⇒ 无租约降级放行（不是 LEASE_HELD_ELSEWHERE）")
			.isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(brokenLeases.leaseOk()).as("降级必须可观测").isFalse();
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(), eq(this.emitter));
	}

	// ── 并发同主双抢：谁创建谁释放（harness lease-scripts 实锤缺陷） ──────────

	/**
	 * 同一实例两线程并发 {@code resume(runId,false)}：都读到"本进程未持租"，Lua ACQUIRE 由第一个
	 * 建号、第二个命中同一现值（owner 是自己 ⇒ 可重入），两条都 {@code ACQUIRED}。
	 *
	 * <p>旧实现用 {@code epochBefore != lease.epoch()} 判"是不是我抢到的"：两边都是 true，于是
	 * 败方（撞 {@code resuming} 互斥 → IN_PROGRESS）在外层按"fres"还租 —— 删掉的却是<b>胜方同一个
	 * token</b> 的租约，顺带清掉进程内视图。后果：胜方后续写回全走 plain SET，fence 键从未创建。
	 *
	 * <h2>确定性（复审 I-2 收口：去 flake，不放宽断言）</h2>
	 * 旧版只有 {@code acquireRunLease} 上的屏障：两条线程同时出屏障后，<b>谁先跑到快照状态判定</b>
	 * 由调度决定 —— 慢机器上胜方可能整轮跑完才轮到败方，败方看到 {@code status=RUNNING} 得
	 * {@code ALREADY_RUNNING}，断言 {@code [ACCEPTED, IN_PROGRESS]} 就红（实测见过
	 * {@code [ALREADY_RUNNING, ACCEPTED]}）。现在照 L-19 的写法把时序钉死：
	 * <ol>
	 * <li>{@code acquireRunLease} 屏障保证两边都"读完未持租"再抢（原始缺陷窗口，保留）；</li>
	 * <li>胜方进入 {@code driveResumed}（= 已占住 {@code resuming} 互斥）后<b>被测试按住</b>；</li>
	 * <li>败方此时必然撞互斥 ⇒ 反序返回的那个必是 {@code IN_PROGRESS}，与机器快慢无关；</li>
	 * <li>断言仍要求"恰一个 ACCEPTED + 恰一个 IN_PROGRESS"，<b>不放宽</b>成"含某结局"。</li>
	 * </ol>
	 */
	@Test
	void concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease() throws Exception {
		java.util.concurrent.CyclicBarrier bothEntered = new java.util.concurrent.CyclicBarrier(2);
		com.example.configmgr.ai.conformance.InMemoryRunStore racing =
				new com.example.configmgr.ai.conformance.InMemoryRunStore() {

					@Override
					public LeaseService.AcquireResult acquireRunLease(String runId) {
						try {
							bothEntered.await(2, java.util.concurrent.TimeUnit.SECONDS);
						}
						catch (Exception ex) {
							throw new IllegalStateException("并发屏障失效", ex);
						}
						return super.acquireRunLease(runId);
					}

				};
		racing.useLeaseFacility(this.leases);
		seedResumableRun(racing);
		ResumeService racingService = new ResumeService(racing, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

		// 胜方的驱动被按住：等到"败方已返回"再放行 —— 这是本用例确定性的来源
		CountDownLatch winnerEnteredDriver = new CountDownLatch(1);
		CountDownLatch releaseWinner = new CountDownLatch(1);
		doAnswer(invocation -> {
			winnerEnteredDriver.countDown();
			releaseWinner.await(10, TimeUnit.SECONDS);
			return null;
		}).when(this.chatService).driveResumed(any(), any(), any(), any(), any());

		java.util.concurrent.atomic.AtomicReference<ResumeService.ResumeResult> first =
				new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<ResumeService.ResumeResult> second =
				new java.util.concurrent.atomic.AtomicReference<>();
		Thread a = new Thread(() -> first.set(racingService.resume(RUN_ID)), "r-concurrent-a");
		Thread b = new Thread(() -> second.set(racingService.resume(RUN_ID)), "r-concurrent-b");
		a.start();
		b.start();
		assertThat(winnerEnteredDriver.await(5, TimeUnit.SECONDS))
			.as("恰有一个线程成为胜方并进入驱动（已占住 resuming 互斥）").isTrue();

		// 胜方被按住期间，败方必然撞互斥 ⇒ 先返回的那个必是 IN_PROGRESS（与调度无关）
		ResumeService.ResumeResult loserResult = awaitFirstOutcome(first, second, 5_000L);
		assertThat(loserResult.outcome()).as("败方：进程内互斥挡下").isEqualTo(ResumeService.Outcome.IN_PROGRESS);
		// 败方不得把胜方的租约删掉（同一 token、可重入命中 ⇒ created=false ⇒ 没有释放权）
		assertThat(this.fake.string(racing.runLeaseKey(RUN_ID)))
			.as("IN_PROGRESS 一方不得删除租约（fence 与 fencing 写全靠它）").isNotNull();
		assertThat(racing.heldRunEpoch(RUN_ID)).as("进程内视图仍持有").isPositive();

		releaseWinner.countDown();
		a.join(10_000L);
		b.join(10_000L);
		assertThat(a.isAlive()).isFalse();
		assertThat(b.isAlive()).isFalse();
		ResumeService.ResumeResult winnerResult = first.get() == loserResult ? second.get() : first.get();
		assertThat(winnerResult.outcome()).as("胜方：驱动跑完").isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.fake.string(racing.runLeaseKey(RUN_ID))).as("胜方驱动期间租约一直在").isNotNull();
	}

	/** 等到两条续跑线程中<b>先</b>返回的那一个（另一个此刻正被测试按住）。 */
	private static ResumeService.ResumeResult awaitFirstOutcome(
			AtomicReference<ResumeService.ResumeResult> first,
			AtomicReference<ResumeService.ResumeResult> second, long millis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < deadline) {
			if (first.get() != null) {
				return first.get();
			}
			if (second.get() != null) {
				return second.get();
			}
			Thread.sleep(5L);
		}
		throw new AssertionError("两条续跑线程在 " + millis + "ms 内都没有返回（胜方是否被驱动门卡住？）");
	}

	// ── L-19：创建者输掉进程内互斥，也不得还租 ─────────────────────────────

	/**
	 * <b>确定性</b>复现 L-19（不靠调度运气，时序由屏障钉死）：
	 * <ol>
	 * <li>X 先 {@code acquireRunLease} → 创建租约（{@code created=true}），但<b>停在 acquire 之后</b>；</li>
	 * <li>Y 随后抢 → 重入命中（{@code created=false}），并<b>第一个</b>进入 {@code resuming} 互斥，
	 *     卡在驱动里（{@code driveResumed} 被测试按住）；</li>
	 * <li>X 这才继续 → 互斥已被占 ⇒ {@code IN_PROGRESS}。</li>
	 * </ol>
	 *
	 * <p>旧实现只按"是不是我创建的"决定还租：X 是创建者，于是它在 IN_PROGRESS 时把租约还了 ——
	 * 而那一任正被 Y 使用（同一 token），Y 后续写回因此退化成 plain SET、fence 键从不推进。
	 * 正确口径是<b>两个条件都满足才还</b>：created=true <b>且</b>结局在归还白名单内。
	 */
	@Test
	void creatorLosingTheInProcessMutexDoesNotReleaseTheLeaseTheWinnerUses() throws Exception {
		CountDownLatch creatorAcquired = new CountDownLatch(1);
		CountDownLatch winnerInsideMutex = new CountDownLatch(1);
		CountDownLatch holdDriver = new CountDownLatch(1);
		InMemoryRunStore racing = new InMemoryRunStore() {

			@Override
			public LeaseService.AcquireResult acquireRunLease(String runId) {
				LeaseService.AcquireResult result = super.acquireRunLease(runId);
				if (result.created()) {
					// 创建者：抢到了，但先别往下走 —— 等胜方进入互斥并卡在驱动里
					creatorAcquired.countDown();
					awaitQuietly(winnerInsideMutex, 5_000L);
				}
				return result;
			}

		};
		racing.useLeaseFacility(this.leases);
		seedResumableRun(racing);
		ResumeService racingService = new ResumeService(racing, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
		// 胜方的驱动卡住：进入 driveResumed = 已在互斥内
		doAnswer(invocation -> {
			winnerInsideMutex.countDown();
			holdDriver.await(10, TimeUnit.SECONDS);
			return null;
		}).when(this.chatService).driveResumed(any(), any(), any(), any(), any());

		AtomicReference<ResumeService.ResumeResult> creator = new AtomicReference<>();
		Thread x = new Thread(() -> creator.set(racingService.resume(RUN_ID)), "l19-creator");
		x.start();
		assertThat(creatorAcquired.await(5, TimeUnit.SECONDS)).as("创建者已抢到租约（正等胜方进互斥）").isTrue();

		AtomicReference<ResumeService.ResumeResult> winner = new AtomicReference<>();
		Thread y = new Thread(() -> winner.set(racingService.resume(RUN_ID)), "l19-winner");
		y.start();
		assertThat(winnerInsideMutex.await(5, TimeUnit.SECONDS)).as("胜方已进入 resuming 互斥并卡在驱动里").isTrue();

		x.join(5_000L);
		assertThat(x.isAlive()).as("创建者应在胜方持锁期间立即返回").isFalse();
		assertThat(creator.get().outcome()).isEqualTo(ResumeService.Outcome.IN_PROGRESS);
		// L-19 的核心断言：创建者输掉互斥 ⇒ 这一任仍归胜方使用，不得还
		assertThat(this.fake.string(racing.runLeaseKey(RUN_ID))).as("租约仍在（胜方正在用同一 token）").isNotNull();
		assertThat(racing.heldRunEpoch(RUN_ID)).as("进程内视图仍持有").isPositive();

		holdDriver.countDown();
		y.join(10_000L);
		assertThat(y.isAlive()).isFalse();
		assertThat(winner.get().outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.fake.string(racing.runLeaseKey(RUN_ID))).as("胜方驱动期间租约一直在").isNotNull();
	}

	/**
	 * 归还白名单的另一侧（防止"一律不还"式过度修正）：<b>轮不存在</b>时没有任何执行者会接手，
	 * 租约必须还 —— 否则一个误传的 runId 会白占 30s（TTL），把后续同 id 的真实续跑挡在
	 * {@code LEASE_HELD_ELSEWHERE} 上。
	 */
	@Test
	void unknownRunStillReleasesTheLeaseBecauseNobodyCanTakeOver() {
		String missing = "run-l19-missing";

		ResumeService.ResumeResult result = this.service.resume(missing);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.NOT_FOUND);
		assertThat(this.fake.string(this.store.runLeaseKey(missing))).as("无人接管 ⇒ 当场还").isNull();
		assertThat(this.store.heldRunEpoch(missing)).isZero();
	}

	// ── 观测面 ────────────────────────────────────────────────────────────

	@Test
	void describeExposesLeaseAndFenceKeys() {
		seedResumableRun();

		@SuppressWarnings("unchecked")
		Map<String, Object> keys = (Map<String, Object>) this.service.describe(RUN_ID).get("keys");

		assertThat(keys).containsEntry("lease", this.store.runLeaseKey(RUN_ID))
			.containsEntry("fence", this.store.fenceKey(RUN_ID));
		assertThat(this.store.runLeaseKey(RUN_ID))
			.as("租约与其余 11 键同 tag（一轮 12 键同槽）")
			.isEqualTo(RunStore.runScopedKey(RunStore.LEASE_RUN_PREFIX, RUN_ID));
		assertThat(this.store.fenceKey(RUN_ID))
			.as("fence 与快照同槽（{run:<runId>} tag）")
			.isEqualTo(RunStore.runScopedKey(RunStore.FENCE_RUN_PREFIX, RUN_ID));
	}

	// ───────────────────────── helpers ─────────────────────────

	/** 可续跑形态：快照 SUSPENDED + in-flight 一条已执行条目（与 {@code ResumeEnvelopeShapeTest} 同形）。 */
	private void seedResumableRun() {
		seedResumableRun(this.store);
	}

	private void seedResumableRun(com.example.configmgr.ai.conformance.InMemoryRunStore target) {
		RunSnapshot snapshot = target.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 7L, null).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请启动导出"))));
		PendingToolCall inFlight = entry(PendingToolCall.EXECUTED);
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(inFlight)));
		target.save(snapshot);
		target.seedPending(RUN_ID, entry(PendingToolCall.EXECUTED));
	}

	private static void awaitQuietly(CountDownLatch latch, long millis) {
		try {
			if (!latch.await(millis, TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("屏障超时（" + millis + "ms）");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("屏障被中断", ex);
		}
	}

	private static PendingToolCall entry(String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setType("function");
		pending.setArguments("{\"taskId\":7}");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(status);
		if (PendingToolCall.EXECUTED.equals(status)) {
			pending.setOutcome(ToolOutcome.OK.wire());
			pending.setResultText("已执行");
			pending.setExecuted(true);
			pending.setExecutedBy("instance-old");
		}
		return pending;
	}

}
