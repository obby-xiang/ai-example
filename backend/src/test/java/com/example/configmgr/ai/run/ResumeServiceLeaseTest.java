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
	 * <p>屏障架在 {@code acquireRunLease} 上：两边都在<b>读完"未持租"状态之后</b>才真正抢 ——
	 * 这条时序正是 harness 抓到的那个窗口，不靠运气。
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

		java.util.concurrent.atomic.AtomicReference<ResumeService.ResumeResult> first =
				new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<ResumeService.ResumeResult> second =
				new java.util.concurrent.atomic.AtomicReference<>();
		Thread a = new Thread(() -> first.set(racingService.resume(RUN_ID)), "r-concurrent-a");
		Thread b = new Thread(() -> second.set(racingService.resume(RUN_ID)), "r-concurrent-b");
		a.start();
		b.start();
		a.join(5_000);
		b.join(5_000);

		assertThat(a.isAlive()).isFalse();
		assertThat(b.isAlive()).isFalse();
		assertThat(java.util.List.of(first.get().outcome(), second.get().outcome()))
			.as("恰一个驱动、恰一个被进程内互斥挡下")
			.containsExactlyInAnyOrder(ResumeService.Outcome.ACCEPTED, ResumeService.Outcome.IN_PROGRESS);
		// 败方不得把胜方的租约删掉（同一 token、可重入命中 ⇒ created=false ⇒ 没有释放权）
		assertThat(this.fake.string(racing.runLeaseKey(RUN_ID)))
			.as("IN_PROGRESS 一方不得删除租约（fence 与 fencing 写全靠它）").isNotNull();
		assertThat(racing.heldRunEpoch(RUN_ID)).as("进程内视图仍持有").isPositive();
	}

	// ── 观测面 ────────────────────────────────────────────────────────────

	@Test
	void describeExposesLeaseAndFenceKeys() {
		seedResumableRun();

		@SuppressWarnings("unchecked")
		Map<String, Object> keys = (Map<String, Object>) this.service.describe(RUN_ID).get("keys");

		assertThat(keys).containsEntry("lease", this.store.runLeaseKey(RUN_ID))
			.containsEntry("fence", this.store.fenceKey(RUN_ID));
		assertThat(this.store.runLeaseKey(RUN_ID)).isEqualTo(RunStore.LEASE_RUN_PREFIX + RUN_ID);
		assertThat(this.store.fenceKey(RUN_ID)).isEqualTo(RunStore.FENCE_RUN_PREFIX + RUN_ID);
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
