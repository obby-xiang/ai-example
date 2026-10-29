package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>取消收尾被写门拒绝后必须有接管者</b>（主 CI run 40211303032 · AIE2E-W-30 的回归位）。
 *
 * <h2>现场（boot.log:6724-6748，runId 3fef5d65-…）</h2>
 * 首个工具 {@code ui_scroll}（FRONTEND）刚发出请求帧，取消就落了下来。日志里<b>没有</b>
 * {@code approval_park}/{@code tool_call_park} —— 取消来自 {@code ConfirmGate#await} 的等待循环，
 * 该轮随后由驱动线程在 {@code cancelTerminal} 收尾。收尾被<b>唯一写门</b>拒绝：
 * {@code 续跑权只读：拒绝取消态 DB 历史/快照/记忆（租约设施不可用（无法确认是否仍持有））}
 * （{@code boot.log:6740}；**这是现场原文** —— 数据库完整历史写口已于 2026-10-30 整条移除，
 * 现行阶段名是「取消态快照/记忆」，其余判据不变），同一判据把终帧也丢了（{@code :6741}）；驱动线程记完
 * {@code turn_end}（{@code :6748}）后退出，<b>没有任何执行体再试</b> ⇒ 20s 后快照仍 SUSPENDED。
 *
 * <h2>为什么这一拍是 UNAVAILABLE（不是"Redis 故障"）</h2>
 * 日志只证明<b>一条在飞的 Redis 命令被打断</b>（{@code Redis command interrupted}，{@code :6733}）：
 * {@code LeaseService.facilityHealthy} 是整实例一个 volatile 位，任一线程一次失败就置假
 * （{@code markFacilityUnhealthy}），而自证恢复探针有 1s 节流；W-30 的拒写发生在失败后 4ms
 * ⇒ 即便 Redis 已经正常，这一拍也必然是 {@code UNAVAILABLE}（{@code boot.log:6735} 同一毫秒的
 * {@code 租约设施自证恢复} 就是佐证）。本类不假设 Redis 故障，也不改通用健康检查。
 *
 * <h2>判据</h2>
 * 取消是<b>已确认的事实</b>：一次"写权限不可确认"不能把它变成"无人管"。交棒必须走到
 * <b>非调用线程</b>、<b>重新取租（走生产三键 acquireFenced，不绕过 fencing）</b>、只做取消收尾
 * （<b>不</b>发起模型/工具），并且在终态判定、幂等判据、并发准入上与既有续跑同纪律。
 *
 * <p><b>与生产现实的差异（明示）</b>：{@code InMemoryRunStore.acquireRunLease} 默认走两键
 * {@code acquire}；本类在替身里显式改走生产的 {@code acquireFenced}（三键同槽自愈）。用例仍
 * <b>不能</b>替代 E2E：controller 在快照 RUNNING 时不派续跑、Reactor dispose 后由非驱动线程接管、
 * Spring 实际装配、aiResumeTrigger 池饱和 —— 这些必须由 GitHub 上的 E2E/CI 证明。
 */
class CancelFinalizeHandoffTest {

	private static final String RUN_ID = "run-cancel-finalize";

	private static final String SESSION_ID = "s-cancel-finalize";

	private static final String CALL_ID = "call-cancel-finalize";

	private static final String DONE_RUN = "run-cancel-finalize-done";

	private static final String FAILED_RUN = "run-cancel-finalize-failed";

	private static final String OTHER_RUN = "run-other-turn";

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-cancel");

	/** 终帧 done 真正落档时放行（同时记下是哪个线程落档的）。 */
	private final CountDownLatch doneArchived = new CountDownLatch(1);

	private final AtomicReference<String> doneArchivedBy = new AtomicReference<>();

	/**
	 * 往返模式 + 两个生产口径修正：{@code get} 返回副本（拒写的改动不"穿透"）、
	 * {@code acquireRunLease} 走生产的三键 {@code acquireFenced}。
	 */
	private final InMemoryRunStore store = new InMemoryRunStore(true) {

		@Override
		public RunSnapshot get(String runId) {
			RunSnapshot stored = super.get(runId);
			if (stored == null) {
				return null;
			}
			try {
				return CancelFinalizeHandoffTest.this.mapper.readValue(
						CancelFinalizeHandoffTest.this.mapper.writeValueAsString(stored), RunSnapshot.class);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

		@Override
		public LeaseService.AcquireResult acquireRunLease(String runId) {
			// 生产 RunStore#acquireRunLease = acquireFenced（租约/计数器/fence 三键同槽）；InMemory
			// 替身默认是两键 acquire。用例要覆盖生产语义，故在此显式对齐。
			return CancelFinalizeHandoffTest.this.leases.acquireFenced(runLeaseKey(runId), fenceKey(runId),
					null);
		}

		@Override
		public void appendEvent(String runId, Map<String, Object> frame) {
			super.appendEvent(runId, frame);
			if (RUN_ID.equals(runId) && "done".equals(String.valueOf(frame.get("type")))) {
				doneArchivedBy.set(Thread.currentThread().getName());
				doneArchived.countDown();
			}
		}

	};

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final ScheduledExecutorService watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "cancel-finalize-watchdog");
		thread.setDaemon(true);
		return thread;
	});

	/** 交棒执行器替身：入队，不自动跑（除线程用例显式换真池）。 */
	private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

	/** 交棒重试调度替身：入队 + 计数。 */
	private final Queue<Runnable> retries = new ConcurrentLinkedQueue<>();

	private final CountDownLatch retryScheduled = new CountDownLatch(1);

	private final AtomicReference<Duration> lastRetryDelay = new AtomicReference<>();

	private ResilientChatService service;

	private ResumeService resumeService;

	private CancelledRunFinalizer finalizer;

	// ── 用例 ────────────────────────────────────────────────────────────────

	/**
	 * <b>W-30 形态的闭环</b>：这一拍写权限不可确认 → 驱动侧收尾被拒 → 交棒到<b>非调用线程</b> →
	 * 写权限恢复后落 CANCELLED + 恰一条终帧；条目未执行、会话索引清空、重复交棒幂等。
	 */
	@Test
	void deniedCancelFinalizationHandsOffToANonCallingThreadAndConverges() throws Exception {
		ExecutorService handoffPool = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cancel-finalize-handoff");
			thread.setDaemon(true);
			return thread;
		});
		try {
			setUpService(handoffPool);
			seedSuspendedRunWithPending();

			// 复现前提：线程内一条在飞的 Redis 命令被打断 ⇒ 全局健康位 + 1s 节流，这一拍不可确认。
			// 用一次失败的抢租制造（不持有任何租约视图，与 W-30 的工具线程一致）。
			this.fake.goDown();
			assertThat(this.leases
				.acquireFenced(this.store.runLeaseKey(RUN_ID), this.store.fenceKey(RUN_ID), null).outcome())
				.isEqualTo(LeaseService.AcquireOutcome.UNAVAILABLE);
			assertThat(this.store.runLeaseAuthority(RUN_ID)).isEqualTo(LeaseService.LeaseAuthority.UNAVAILABLE);

			// 驱动线程的取消收尾（测试线程扮演；W-30 里是 ai-suspend-19）
			this.service.cancelParked(RUN_ID);
			assertThat(this.store.get(RUN_ID).getStatus()).as("取证明：这一次确实没写进去").isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(archivedTypes()).as("取证明：终帧也被同一判据丢弃").doesNotContain("done");
			assertThat(this.retryScheduled.await(5, TimeUnit.SECONDS))
				.as("第一次交棒（设施仍不可用）必须排重试，而不是放弃")
				.isTrue();
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);

			// 恢复（真实语义：三键抢租成功即清墓碑/清健康位）+ 交棒重试
			this.fake.heal();
			Runnable retry = this.retries.poll();
			assertThat(retry).isNotNull();
			handoffPool.execute(retry);
			assertThat(this.doneArchived.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(this.doneArchivedBy.get())
				.as("交棒必须在非调用线程执行（W-30 的驱动线程已被 dispose/打断）")
				.isEqualTo("cancel-finalize-handoff")
				.isNotEqualTo(Thread.currentThread().getName());
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
			assertThat(archivedTypes()).as("终帧恰一次").containsOnlyOnce("done");
			assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
			assertThat(this.store.pending(RUN_ID, CALL_ID).isExecuted()).as("取消不得把工具执行掉").isFalse();
			assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
			assertThat(this.store.sessionCurrentRunId(SESSION_ID)).as("终态清 session→current").isNull();

			// 会话门：一次性 token 取门、按同一 token 精确释放（与续跑同纪律）
			ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
			verify(this.sessionGate, org.mockito.Mockito.atLeastOnce()).acquire(eq(SESSION_ID), eq(RUN_ID),
					token.capture());
			verify(this.sessionGate, timeout(5000)).release(eq(SESSION_ID), eq(RUN_ID), eq(token.getValue()));
			assertThat(token.getValue()).as("一次性 token（不是 runId）").isNotEqualTo(RUN_ID);

			// 幂等：交棒重试撞上已成功的收尾 ⇒ 只读跳过，不再造第二条终帧
			assertThat(this.resumeService.cancelConfirmed(RUN_ID))
				.isEqualTo(ResumeService.CancelFinalize.ALREADY_TERMINAL);
			assertThat(archivedTypes()).containsOnlyOnce("done");
		}
		finally {
			handoffPool.shutdownNow();
		}
	}

	/**
	 * <b>I-1：延迟交棒绝不能把 DONE/FAILED 改写成 CANCELLED</b>（也不得补第二条 done）。
	 *
	 * <p>窗口是真实存在的：{@code LEASE_HELD_ELSEWHERE} 时按租约 TTL 反复重排，期间另一实例
	 * 合法接管并跑成 DONE（终态 save 会释放租约）⇒ 下一次重试能全新抢到租约并通过 verifyHeld。
	 */
	@Test
	void cancelFinalizationNeverRewritesADoneOrFailedRun() {
		setUpService(this.tasks::add);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		seedRunWithStatus(DONE_RUN, RunSnapshot.DONE);
		seedRunWithStatus(FAILED_RUN, RunSnapshot.FAILED);

		assertThat(this.resumeService.cancelConfirmed(DONE_RUN))
			.as("DONE 不是取消：交棒入口直接判终态")
			.isEqualTo(ResumeService.CancelFinalize.ALREADY_TERMINAL);
		assertThat(this.resumeService.cancelConfirmed(FAILED_RUN))
			.as("FAILED 同口径")
			.isEqualTo(ResumeService.CancelFinalize.ALREADY_TERMINAL);

		// 防御性：即便调用方直接把终态交进 cancelParked，也不得改写（其它调用方/旧路径）
		this.service.cancelParked(DONE_RUN);
		this.service.cancelParked(FAILED_RUN);

		assertThat(this.store.get(DONE_RUN).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(this.store.get(FAILED_RUN).getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(this.store.archivedTypes(DONE_RUN)).as("不得为已完成轮补取消终帧").doesNotContain("done");
		assertThat(this.store.archivedTypes(FAILED_RUN)).doesNotContain("done");
	}

	/**
	 * <b>I-2：终帧幂等判据 = 归档里有没有 done，不是快照状态。</b>
	 * 快照已 CANCELLED 但 done 未落档（save 成功、帧被写门/投递丢弃）必须能被补上，且只补一次。
	 */
	@Test
	void aCancelledSnapshotWithNoArchivedDoneIsRepairedExactlyOnce() {
		setUpService(this.tasks::add);
		RunSnapshot snapshot = seedRunWithStatus(RUN_ID, RunSnapshot.CANCELLED);
		seedPending(snapshot);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		assertThat(this.resumeService.cancelConfirmed(RUN_ID))
			.as("快照终态不等于终帧已发：必须补收尾")
			.isEqualTo(ResumeService.CancelFinalize.FINALIZED);
		assertThat(archivedTypes()).containsOnlyOnce("done");
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);

		assertThat(this.resumeService.cancelConfirmed(RUN_ID))
			.as("终帧已归档 ⇒ 幂等跳过")
			.isEqualTo(ResumeService.CancelFinalize.ALREADY_TERMINAL);
		assertThat(archivedTypes()).containsOnlyOnce("done");
	}

	/**
	 * <b>取消事实不可见时不得宣告"完成"，也不得驱动</b>（M-6）：{@code NOT_CANCELLED} +
	 * 快照非终态 ⇒ {@code CANCEL_FACT_UNCONFIRMED}，交棒按未知<b>有界重试</b>。
	 */
	@Test
	void theFinalizerKeepsRetryingWhenTheCancelFactIsNotVisible() {
		setUpService(this.tasks::add);
		seedSuspendedRunWithPending();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);

		assertThat(this.resumeService.cancelConfirmed(RUN_ID))
			.isEqualTo(ResumeService.CancelFinalize.CANCEL_FACT_UNCONFIRMED);
		assertThat(this.store.get(RUN_ID).getStatus()).as("不得因交棒而驱动/改写").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(archivedTypes()).isEmpty();

		assertThat(this.finalizer.handOff(RUN_ID, "test")).isTrue();
		this.tasks.poll().run();
		assertThat(this.retries)
			.as("取消事实不可见 ⇒ 有界重试（不是宣告完成，更不是调用模型）")
			.isNotEmpty();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);

		// 终态 + 非取消：没有什么可做，直接收敛（不再重试）
		this.retries.clear();
		seedRunWithStatus(DONE_RUN, RunSnapshot.DONE);
		when(this.cancellations.cancelState(DONE_RUN)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		assertThat(this.resumeService.cancelConfirmed(DONE_RUN))
			.isEqualTo(ResumeService.CancelFinalize.ALREADY_TERMINAL);
	}

	/** <b>I-3：与续跑共用进程内 {@code resuming} 准入</b> —— 占用中不得并发推进。 */
	@Test
	@SuppressWarnings("unchecked")
	void anInProgressResumeBlocksTheCancelHandoff() {
		setUpService(this.tasks::add);
		seedSuspendedRunWithPending();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		Map<String, Boolean> resuming = (Map<String, Boolean>) ReflectionTestUtils.getField(this.resumeService,
				"resuming");
		assertThat(resuming).isNotNull();
		resuming.put(RUN_ID, Boolean.TRUE);
		try {
			assertThat(this.resumeService.cancelConfirmed(RUN_ID))
				.as("同进程已有执行体在推进这一轮 ⇒ 本拍不并发")
				.isEqualTo(ResumeService.CancelFinalize.IN_PROGRESS);
		}
		finally {
			resuming.remove(RUN_ID);
		}
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(archivedTypes()).isEmpty();
	}

	/** <b>I-3：会话门被另一轮占着 ⇒ 可重试结局，不写不推进。</b> */
	@Test
	void aBusySessionLeavesTheRunForARetry() {
		setUpService(this.tasks::add);
		seedSuspendedRunWithPending();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.sessionGate.acquire(anyString(), anyString(), anyString()))
			.thenReturn(new SessionGate.Busy(OTHER_RUN, false));

		assertThat(this.resumeService.cancelConfirmed(RUN_ID))
			.isEqualTo(ResumeService.CancelFinalize.SESSION_BUSY);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(archivedTypes()).isEmpty();
		// 准入必须释放（否则这一轮永远 IN_PROGRESS）
		@SuppressWarnings("unchecked")
		Map<String, Boolean> resuming = (Map<String, Boolean>) ReflectionTestUtils.getField(this.resumeService,
				"resuming");
		assertThat(resuming).doesNotContainKey(RUN_ID);
	}

	/**
	 * <b>M-3：提交被拒（池饱和）也必须受重试上限约束</b> —— 不得绕过 {@code maxDispatchRetries}。
	 */
	@Test
	void submitRejectionRespectsTheRetryBudget() {
		this.properties.getPark().setMaxDispatchRetries(0);
		setUpService(task -> {
			throw new RejectedExecutionException("saturated");
		});
		seedSuspendedRunWithPending();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		assertThat(this.finalizer.handOff(RUN_ID, "test")).isTrue();
		assertThat(this.retries).as("上限 0 ⇒ 提交被拒也不得无界重排").isEmpty();
		assertThat(this.finalizer.handOff(RUN_ID, "test")).as("用尽后应清理在飞标记，可被下一次触发重新计数").isTrue();
	}

	/**
	 * <b>N5：终态清理 {@code forget} 释放交棒位与重试计数</b>（幂等、只动进程内表）。
	 *
	 * <p>为什么必须有：{@code inFlight} 是"同一 run 只交棒一次"的去重位，成功收敛后不会自行消失；
	 * 不清理的话，同一进程里这条 runId 之后的任何交棒都会被静默拒绝。
	 */
	@Test
	void forgetReleasesTheHandoffSlotAndRetryBudget() {
		setUpService(this.tasks::add);
		seedSuspendedRunWithPending();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		assertThat(this.finalizer.handOff(RUN_ID, "test")).isTrue();
		assertThat(this.finalizer.handOff(RUN_ID, "test")).as("在飞去重").isFalse();

		this.finalizer.forget(RUN_ID);
		assertThat(attemptCounts()).as("forget 清掉尝试计数").doesNotContainKey(RUN_ID);
		assertThat(this.finalizer.handOff(RUN_ID, "test")).as("交棒位已释放，可再次交棒").isTrue();

		// 幂等 + null 安全：不报错、不改变其它状态
		this.finalizer.forget(RUN_ID);
		this.finalizer.forget(null);
		assertThat(attemptCounts()).doesNotContainKey(RUN_ID);
	}

	/**
	 * <b>N5：Error 路径也必须释放交棒位</b> —— 执行器在派发时就抛 {@link Error}（OOM/实现异常）时，
	 * 若只清 on {@code RuntimeException}，该 run 会永久 {@code inFlight}，后续交棒全被静默拒绝。
	 */
	@Test
	void anErrorDuringTheHandoffStillReleasesTheInFlightSlot() {
		AtomicInteger dispatches = new AtomicInteger();
		setUpService(task -> {
			if (dispatches.getAndIncrement() == 0) {
				throw new AssertionError("boom");
			}
			task.run();
		});
		seedSuspendedRunWithPending();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		assertThatThrownBy(() -> this.finalizer.handOff(RUN_ID, "test")).isInstanceOf(AssertionError.class);
		assertThat(this.finalizer.handOff(RUN_ID, "test")).as("Error 路径之后交棒位必须已释放").isTrue();
	}

	/** {@code attempts} 的进程内视图（只读断言用）。 */
	@SuppressWarnings("unchecked")
	private Map<String, AtomicInteger> attemptCounts() {
		return (Map<String, AtomicInteger>) ReflectionTestUtils.getField(this.finalizer, "attempts");
	}

	// ── park 分支（<b>不是</b> W-30 的时序；只锁"写权限已丢才交棒"这条条件） ──

	@Test
	void parkExitHandsOffOnlyWhenTheWriterIsAlreadyReadOnly() {
		this.properties.getPark().setEnabled(true);
		setUpService(this.tasks::add);
		ConfirmGate gate = parkGate();
		seedSuspendedRunWithPending();
		PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
		LeaseService.Lease lease = this.store.tryAcquireRunLease(RUN_ID).orElseThrow();
		// park 退出前这一拍写权限已不可确认（W-30 的成因；这里用真实续租异常打墓碑）
		this.fake.failRenew(true);
		this.leases.renewState(lease, this.properties.getLease().getTtl());

		PendingToolCall cancelled = gate.awaitFrontendResult(RUN_ID, pending);

		assertThat(cancelled.getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(this.store.get(RUN_ID).getStatus()).as("快照终态由交棒侧负责（本线程已无写权限）")
			.isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.tasks).as("写权限已丢 ⇒ park 退出必须交棒").hasSize(1);
	}

	@Test
	void parkExitDoesNotHandOffWhenWriteAuthorityIsHealthy() {
		this.properties.getPark().setEnabled(true);
		setUpService(this.tasks::add);
		ConfirmGate gate = parkGate();
		seedSuspendedRunWithPending();
		PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);

		PendingToolCall cancelled = gate.awaitFrontendResult(RUN_ID, pending);

		assertThat(cancelled.getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(this.tasks).as("写权限正常 ⇒ 不交棒（随后由本线程 cancelTerminal 收尾，避免双终帧）").isEmpty();
		assertThat(archivedTypes()).as("正常 park 取消仍按旧口径出结局帧").contains("frontend_tool_result");
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private void setUpService(Executor handoffExecutor) {
		this.store.useLeaseFacility(this.leases);
		when(this.sessionGate.acquire(anyString(), anyString(), anyString())).thenReturn(null);
		when(this.sessionGate.release(anyString(), anyString(), anyString())).thenReturn(true);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(true);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		this.resumeService = new ResumeService(this.store, this.registry, this.codec, this.service,
				mock(SpToolCallingManager.class),
				mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
				mock(org.springframework.data.redis.core.StringRedisTemplate.class), this.properties,
				this.cancellations, new ToolResultLimiter(this.properties));
		ReflectionTestUtils.setField(this.resumeService, "sessionGate", this.sessionGate);

		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		this.finalizer = new CancelledRunFinalizer(provider, handoffExecutor, this.properties, (task, delay) -> {
			this.retries.add(task);
			this.lastRetryDelay.set(delay);
			this.retryScheduled.countDown();
			return () -> {
			};
		});
		this.service.setCancelFinalizer(this.finalizer);
	}

	private ConfirmGate parkGate() {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of(), provider, Runnable::run);
		gate.setCancelFinalizer(this.finalizer);
		return gate;
	}

	private RunSnapshot seedSuspendedRunWithPending() {
		RunSnapshot snapshot = seedRunWithStatus(RUN_ID, RunSnapshot.SUSPENDED);
		seedPending(snapshot);
		return snapshot;
	}

	private RunSnapshot seedRunWithStatus(String runId, String status) {
		String sessionId = RUN_ID.equals(runId) ? SESSION_ID : SESSION_ID + "-" + runId;
		RunSnapshot snapshot = this.store.seedRun(runId, sessionId);
		snapshot.setContext(AiContext.of("definitions", null, null, null, null).toMap());
		snapshot.setStatus(status);
		this.store.save(snapshot);
		return this.store.get(runId);
	}

	private PendingToolCall seedPending(RunSnapshot snapshot) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("ui_scroll");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setReq(Boolean.TRUE);
		pending.setAtMs(System.currentTimeMillis());
		this.store.putPending(snapshot.getRunId(), pending);
		return pending;
	}

	private List<String> archivedTypes() {
		return this.store.archivedTypes(RUN_ID);
	}

}
