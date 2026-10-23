package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.FakeRenewScheduler;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B23L1 批②b：<b>续跑权 fencing 在确认门上的接线</b>（设计稿 §2.2；方案第 1/2/6 条）。
 *
 * <h2>为什么等待方要持租</h2>
 * "谁在等这张卡"过去完全是进程内事实（{@code latches}）。挂起在 A、决策落在 B 之后，
 * A 的等待线程若还活着、B 又派发一次续跑，两个线程会同时驱动同一轮（重复帧 / settle 分叉）。
 * 租约把这条互斥从进程内搬到 Redis：<b>等待方持租 ⇒ 驱动方在别处抢不到 ⇒ 不会双驱动</b>；
 * 决策到达时把租约<b>交棒</b>（不释放），续租由调度器接手，终态由 {@code RunStore.save} 释放。
 *
 * <h2>本类锁的九条（G1–G9）</h2>
 * <ol>
 * <li>G1 等待方进 await 即持租、心跳按 {@code lease.renew-interval} 续租、决策到达后离开等待<b>不释放</b>；</li>
 * <li>G2 抢到租 → 派发续跑，且带上<b>同一个 lease</b>（{@code resume(runId,false,lease)}）+ 交调度器续租；</li>
 * <li>G3 抢不到 → 不派发、{@code LEASE_HELD_ELSEWHERE}，但决策<b>已经落库</b>（条目 APPROVED、回执帧一次）；</li>
 * <li>G4 延迟重试（{@code lease.retry-delay}）在 holder 租约失效后派发成功；</li>
 * <li>G5 {@code woke=true} 的快路径<b>不碰租约</b>（回归锁：T3-6a 的 WOKE 分支）；</li>
 * <li>G6 {@code ACTIVE_LATCH} 短路<b>不碰租约</b>（回归锁：连抢都不抢）；</li>
 * <li>G7 holder 存活时重试不派发也不再排重试；holder 死亡后下一次外部输入以<b>严格更大</b>的 epoch 接管；</li>
 * <li>G8 释放是 epoch scoped：等待方迟到的 release 不删新持有者的租约；</li>
 * <li>G9 决策打<b>另一实例</b>（A 的等待方持租、B 侧提交）：B 报 {@code LEASE_HELD_ELSEWHERE} 但决策
 *     已落库，A 的等待方在轮询节拍里收敛、租约交棒不释放 —— 决策不丢、不双驱动。</li>
 * </ol>
 *
 * <p>MUL 用例 M-02 的进程内下沉即 G9（设计稿 §2.10「挂起决策打 B 侧」）。
 *
 * <p>租约侧用真实 {@link LeaseService} + {@link FakeLeaseRedis}（②a 的测试底座）：
 * epoch 单调、脚本语义、TTL 到期后重抢都是真跑的，不是打桩自证。
 *
 * <h2>实例面维度（M8 / DC-11 边界）</h2>
 * 本类用<b>两个 {@link LeaseService}（inst-a / inst-b）共享同一份 {@link FakeLeaseRedis}</b>
 * 模拟双实例：进程内视图彼此独立、Redis 键面共享 —— 这正是本专项关心的那一层。
 * <ul>
 * <li><b>单实例即可达</b>：G1（等待方持租/续租/交棒）、G2（抢到即派发带租约）、G5/G6（两条不碰租约的
 *     短路）、G8（迟到 release 不删新主人）、C1（前端超时交棒）；</li>
 * <li><b>需要"另一个实例"才成立</b>：G3（抢不到 = LEASE_HELD_ELSEWHERE）、G4/G7（holder 死亡后
 *     到期接管、epoch 严格更大）、C1 的"交棒后 fence 仍生效"——它们的前提是<b>别人手里有租约</b>，
 *     单实例只会看到"自己重入"；</li>
 * <li><b>本 harness 不覆盖的</b>：真跨进程的调度时序、网络分区、Redis 主从切换 —— 这些属 DC-11 的
 *     双实例/演练边界，由 harness 承接，不在单测里假装。</li>
 * </ul>
 */
class ConfirmGateLeaseTest {

	private static final String RUN_ID = "run-lease";

	private static final String SESSION_ID = "s-lease";

	private static final String TOOL_CALL_ID = "call-lease";

	private static final String OTHER_TOOL_CALL_ID = "call-lease-2";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler renewScheduler = new FakeRenewScheduler();

	/** 本实例（gate 所在进程）的租约设施。 */
	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, this.renewScheduler,
			"inst-a");

	/** 另一实例的租约设施（只共享 Redis，不共享进程内视图）。 */
	private final LeaseService otherLeases = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-b");

	/** 外置状态：租约薄口委托给上面的真实设施（fence 写 / heldRunEpoch 与之同源）。 */
	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);

	private final List<Runnable> dispatched = Collections.synchronizedList(new ArrayList<>());

	private final FakeRetryScheduler retry = new FakeRetryScheduler();

	/** G9：另一实例在这套 harness 里的两个进程内面（各自独立的派发队列与重试调度器）。 */
	private final List<Runnable> dispatchedOnB = Collections.synchronizedList(new ArrayList<>());

	private final FakeRetryScheduler retryOnB = new FakeRetryScheduler();

	private final ConfirmGate gate;

	ConfirmGateLeaseTest() {
		this.store.useLeaseFacility(this.leases);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), this.provider, this.dispatched::add, this.leases, this.retry);
	}

	@BeforeEach
	void stubCommon() {
		when(this.provider.getIfAvailable()).thenReturn(this.resumeService);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(eq(RUN_ID), any())).thenReturn(() -> {
		});
		when(this.resumeService.resume(eq(RUN_ID), anyBoolean(), any())).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.ACCEPTED, Map.of("runId", RUN_ID, "resumed", true)));
	}

	// ── G1 等待方持租 ──────────────────────────────────────────────────────

	@Test
	void waitingPartyHoldsTheLeaseRenewsItOnHeartbeatAndHandsItOffOnDecision() throws Exception {
		// 心跳节奏 = lease.renew-interval / HEARTBEAT_SECONDS；压到 1ms ⇒ 每跳续租（生产默认 10s = 5 跳）
		this.properties.getLease().setRenewInterval(Duration.ofMillis(1));
		seedSuspended(PendingToolCall.PENDING);
		PendingToolCall entry = pendingEntry(PendingToolCall.PENDING);
		Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, entry), "g1-await");
		waiter.setDaemon(true);

		waiter.start();
		awaitLatchRegistered(RUN_ID);

		String leaseKey = this.store.runLeaseKey(RUN_ID);
		long epoch = this.store.heldRunEpoch(RUN_ID);
		assertThat(epoch).as("进入挂起等待即持租").isPositive();
		assertThat(this.fake.string(leaseKey)).as("Redis 里的值 = <epoch>|<instance>").isEqualTo(epoch + "|inst-a");

		int renewalsBefore = this.fake.renewCalls.get();
		Thread.sleep(2_600L);
		assertThat(this.fake.renewCalls.get()).as("等待期心跳按下限节拍续租（TTL 不会被等穿）")
			.isGreaterThan(renewalsBefore);

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");
		assertThat(submission.woke()).isTrue();

		waiter.join(5_000L);
		assertThat(waiter.isAlive()).as("决策到达后等待方退出").isFalse();
		assertThat(this.fake.string(leaseKey)).as("决策到达 = 交棒给驱动方，等待方不得释放")
			.isEqualTo(epoch + "|inst-a");
		assertThat(this.fake.scriptDeletes.get()).as("离开等待（决策路径）一次都不删租约").isZero();
		// 交棒的另一半：续租交给调度器（驱动方还在跑，租约不能被等穿；终态由 RunStore.save 释放）
		assertThat(this.renewScheduler.activeCount()).as("交棒后续租任务已登记").isEqualTo(1);
	}

	// ── G2 抢到分支 ────────────────────────────────────────────────────────

	@Test
	void acquiredLeaseIsPassedToTheDispatchedResumeAndHandedToTheRenewScheduler() {
		seedSuspended(PendingToolCall.PENDING);

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

		assertThat(submission.woke()).isFalse();
		assertThat(submission.resumeTrigger().triggered()).isTrue();
		assertThat(submission.resumeTrigger().outcome()).isEqualTo("SCHEDULED");
		assertThat(this.dispatched).hasSize(1);
		long epoch = this.store.heldRunEpoch(RUN_ID);
		assertThat(epoch).isPositive();

		drainDispatch();

		ArgumentCaptor<LeaseService.Lease> lease = ArgumentCaptor.forClass(LeaseService.Lease.class);
		verify(this.resumeService).resume(eq(RUN_ID), eq(false), lease.capture());
		assertThat(lease.getValue().epoch()).as("派发带的就是抢到的那个 epoch").isEqualTo(epoch);
		assertThat(lease.getValue().key()).isEqualTo(this.store.runLeaseKey(RUN_ID));
		assertThat(this.renewScheduler.activeCount()).as("ACCEPTED ⇒ 交续租调度器（终态由 RunStore.save 释放）")
			.isEqualTo(1);
	}

	// ── G3 抢不到分支 ──────────────────────────────────────────────────────

	@Test
	void losingTheLeaseStillPersistsTheDecisionAndSkipsTheResume() {
		seedSuspended(PendingToolCall.PENDING);
		long heldByOther = this.otherLeases.tryAcquire(this.store.runLeaseKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow().epoch();

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

		assertThat(submission.outcome()).as("决策本身已经落库成功").isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(submission.resumeTrigger().triggered()).as("抢不到租约 ⇒ 不派发").isFalse();
		assertThat(submission.resumeTrigger().outcome()).isEqualTo("LEASE_HELD_ELSEWHERE");
		assertThat(this.dispatched).isEmpty();
		verify(this.resumeService, never()).resume(anyString(), anyBoolean(), any());
		// 决策的三件事照旧：条目已决 + 回执帧恰一次
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus()).isEqualTo(PendingToolCall.APPROVED);
		verify(this.emitter, times(1)).confirmDecision(any(PendingToolCall.class), anyString(), any(), eq(0L));
		// 一次性延迟重试已排（救 holder 已死的场景），间隔 = lease.retry-delay
		assertThat(this.retry.scheduled).isEqualTo(1);
		assertThat(this.retry.delay).isEqualTo(Duration.ofSeconds(35));
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("别人的租约不据为己有").isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID)))
			.isEqualTo(heldByOther + "|inst-b");
	}

	// ── G4 延迟重试后接管 ──────────────────────────────────────────────────

	@Test
	void delayedRetryDispatchesTheResumeOnceTheHoldersLeaseIsGone() {
		seedSuspended(PendingToolCall.PENDING);
		long heldByOther = this.otherLeases.tryAcquire(this.store.runLeaseKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow().epoch();
		assertThat(this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意").resumeTrigger().outcome())
			.isEqualTo("LEASE_HELD_ELSEWHERE");

		// holder 进程死亡：TTL 到期，键消失（另一实例的进程内视图已无意义）
		this.fake.dropKey(this.store.runLeaseKey(RUN_ID));
		this.retry.runAll();

		assertThat(this.dispatched).as("重试一拍就足以接管").hasSize(1);
		drainDispatch();
		verify(this.resumeService).resume(eq(RUN_ID), eq(false), any());
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("接管后的 epoch 严格大于前任")
			.isGreaterThan(heldByOther);
		assertThat(this.renewScheduler.activeCount()).as("ACCEPTED 后由调度器续租").isEqualTo(1);
	}

	// ── G5 woke=true 快路径不碰租约（回归锁） ──────────────────────────────

	@Test
	void wokeFastPathDoesNotTouchTheLease() throws Exception {
		seedSuspended(PendingToolCall.PENDING);
		PendingToolCall entry = pendingEntry(PendingToolCall.PENDING);
		Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, entry), "g5-await");
		waiter.setDaemon(true);
		waiter.start();
		awaitLatchRegistered(RUN_ID);
		long epoch = this.store.heldRunEpoch(RUN_ID);
		int scriptCallsBefore = this.fake.scriptCalls.get();

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

		assertThat(submission.resumeTrigger().outcome()).isEqualTo("WOKE_IN_PROCESS_GATE");
		assertThat(this.fake.scriptCalls.get()).as("WOKE 分支不发任何租约脚本（不抢/不续/不放）")
			.isEqualTo(scriptCallsBefore);
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("租约仍归等待方（未被动过）").isEqualTo(epoch);
		waiter.join(5_000L);
	}

	// ── G6 ACTIVE_LATCH 短路不碰租约（回归锁） ────────────────────────────

	@Test
	void activeLatchShortCircuitDoesNotEvenTryTheLease() {
		seedSuspended(PendingToolCall.PENDING);
		this.gate.arm(RUN_ID, List.of(OTHER_TOOL_CALL_ID));

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

		assertThat(submission.resumeTrigger().outcome()).isEqualTo("ACTIVE_LATCH_IN_PROCESS");
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("连抢都没抢").isNull();
		assertThat(this.fake.scriptCalls.get()).isZero();
		assertThat(this.retry.scheduled).isZero();
		this.gate.disarm(RUN_ID, List.of(OTHER_TOOL_CALL_ID));
	}

	// ── G7 holder 存活 → 重试静默；holder 死亡 → 下一次输入以更大 epoch 接管 ──

	@Test
	void retryStaysQuietWhileTheHolderLivesAndTheNextInputTakesOverAfterItDies() {
		seedSuspended(PendingToolCall.PENDING);
		seedPending(OTHER_TOOL_CALL_ID, PendingToolCall.PENDING);
		long heldByOther = this.otherLeases.tryAcquire(this.store.runLeaseKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow().epoch();

		assertThat(this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意").resumeTrigger().outcome())
			.isEqualTo("LEASE_HELD_ELSEWHERE");
		this.retry.runAll();
		assertThat(this.dispatched).as("holder 还活着 ⇒ 重试一拍不派发").isEmpty();
		assertThat(this.retry.scheduled).as("一次性重试：失败后不得再排下一次").isEqualTo(1);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isEqualTo(heldByOther + "|inst-b");

		// holder 进程死亡（TTL 到期）后，同批另一张卡的决策成为下一次触发点
		this.fake.dropKey(this.store.runLeaseKey(RUN_ID));
		ConfirmGate.Submission second = this.gate.submitDecision(RUN_ID, OTHER_TOOL_CALL_ID, true, "同意");

		assertThat(second.resumeTrigger().outcome()).isEqualTo("SCHEDULED");
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("新主人拿到严格更大的 epoch")
			.isGreaterThan(heldByOther);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID)))
			.isEqualTo(this.store.heldRunEpoch(RUN_ID) + "|inst-a");
	}

	// ── G8 释放 epoch scoped ───────────────────────────────────────────────

	@Test
	void lateReleaseFromTheWaiterDoesNotDeleteTheNewHoldersLease() throws Exception {
		seedSuspended(PendingToolCall.PENDING);
		PendingToolCall entry = pendingEntry(PendingToolCall.PENDING);
		Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, entry), "g8-await");
		waiter.setDaemon(true);
		waiter.start();
		awaitLatchRegistered(RUN_ID);
		long staleEpoch = this.store.heldRunEpoch(RUN_ID);

		// 等待方卡顿超过 TTL：租约过期，另一实例接管
		this.fake.dropKey(this.store.runLeaseKey(RUN_ID));
		long takeoverEpoch = this.otherLeases.tryAcquire(this.store.runLeaseKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow().epoch();
		assertThat(takeoverEpoch).isGreaterThan(staleEpoch);

		// 等待方异常退出（中断）：它的 release 带着过期 epoch
		waiter.interrupt();
		waiter.join(5_000L);

		assertThat(waiter.isAlive()).isFalse();
		assertThat(this.fake.scriptDeletes.get()).as("错 epoch 不删").isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID)))
			.as("新持有者的租约必须原样活着").isEqualTo(takeoverEpoch + "|inst-b");
	}

	// ── G9（MUL M-02 下沉）：决策打另一实例 ────────────────────────────────

	/**
	 * 设计稿 §2.10 M-02 的进程内形态：<b>A 侧挂着人（等待线程持租），决策从 B 侧打进来</b>。
	 *
	 * <p>与 G3 互为镜像 —— G3 是"别人持租、我在 A 提交"。真实现场更常见的是反过来的这一半：
	 * 浏览器把确认 POST 打到了负载均衡后的另一台（B），而挂起线程还在 A 上等。要同时成立的三件事：
	 * <ol>
	 * <li><b>决策不丢</b>：B 抢不到续跑权（{@code LEASE_HELD_ELSEWHERE}、不派发），但条目必须落库、
	 *     回执帧必须发出 —— 否则用户点了「批准」而系统什么都没发生；</li>
	 * <li><b>A 侧最终收敛</b>：A 的等待线程不等 B 的进程内唤醒（跨进程没有本地 latch 可用），
	 *     而是按心跳节拍轮询 Redis 里的条目状态发现已决；</li>
	 * <li><b>不双驱动</b>：B 排的一次性延迟重试在 A 仍持租时不得派发；A 离开等待时交棒（不释放）。</li>
	 * </ol>
	 */
	@Test
	void decisionSubmittedThroughTheOtherInstanceHoldsItsLeaseAndStillConvergesTheWaiter() throws Exception {
		seedSuspended(PendingToolCall.PENDING);
		AtomicReference<PendingToolCall> awaited = new AtomicReference<>();
		Thread waiter = new Thread(
				() -> awaited.set(this.gate.awaitDecision(RUN_ID, pendingEntry(PendingToolCall.PENDING))),
				"g9-await");
		waiter.setDaemon(true);
		waiter.start();
		awaitLatchRegistered(RUN_ID);
		long epochHeldByA = this.store.heldRunEpoch(RUN_ID);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isEqualTo(epochHeldByA + "|inst-a");

		// B 侧的闸门：同一份外置状态（= 同一 Redis），独立租约设施与进程内闸门表
		ConfirmGate gateOnB = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of(), this.provider, this.dispatchedOnB::add, this.otherLeases, this.retryOnB);
		ConfirmGate.Submission submission = gateOnB.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");

		assertThat(submission.outcome()).as("决策本身落库成功").isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(submission.woke()).as("B 侧没有本进程闸门可唤醒（跨进程唤醒走 Redis）").isFalse();
		assertThat(submission.resumeTrigger().outcome()).isEqualTo("LEASE_HELD_ELSEWHERE");
		assertThat(submission.resumeTrigger().triggered()).isFalse();
		assertThat(this.dispatchedOnB).as("抢不到续跑权 ⇒ B 不派发").isEmpty();
		assertThat(this.retryOnB.scheduled).as("B 排一次延迟重试（holder 已死的兜底）").isEqualTo(1);
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus()).as("决策不丢：条目已决")
			.isEqualTo(PendingToolCall.APPROVED);

		// A 侧收敛：等待线程按心跳节拍轮询条目状态（不依赖 B 的进程内唤醒）
		waiter.join(8_000L);
		assertThat(waiter.isAlive()).as("A 侧等待方在轮询节拍内收敛").isFalse();
		assertThat(awaited.get()).isNotNull();
		assertThat(awaited.get().getStatus()).as("A 侧拿到的是 B 落的那一版决策")
			.isEqualTo(PendingToolCall.APPROVED);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID)))
			.as("决策到达 = 交棒给驱动方：A 不得释放").isEqualTo(epochHeldByA + "|inst-a");
		assertThat(this.fake.scriptDeletes.get()).as("整段没有一次租约删除").isZero();
		assertThat(this.renewScheduler.activeCount()).as("A 的驱动方已登记续租").isEqualTo(1);
		verify(this.emitter, times(1)).confirmDecision(any(PendingToolCall.class), anyString(), any(), eq(0L));

		// 不双驱动：A 仍持租时，B 的延迟重试一拍不得派发
		this.retryOnB.runAll();
		assertThat(this.dispatchedOnB).as("holder 存活 ⇒ B 的重试静默").isEmpty();
		verify(this.resumeService, never()).resume(anyString(), anyBoolean(), any());
	}

	// ── C1 前端超时出口同样要交棒（本轮线程仍在驱动） ──────────────────────

	@Test
	void frontendTimeoutHandsOffTheLeaseInsteadOfReleasingIt() throws Exception {
		// 即时执行类前端工具：等待上限 = app.ai.hitl.frontend-tool-timeout（本例压到 1s）
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(1));
		seedSuspended(PendingToolCall.PENDING, PendingToolCall.KIND_FRONTEND, "navigate_to");
		PendingToolCall entry = pendingEntry(PendingToolCall.PENDING);
		entry.setKind(PendingToolCall.KIND_FRONTEND);
		entry.setName("navigate_to");
		Thread waiter = new Thread(() -> this.gate.awaitFrontendResult(RUN_ID, entry), "c1-await");
		waiter.setDaemon(true);

		waiter.start();
		awaitLatchRegistered(RUN_ID);
		long epoch = this.store.heldRunEpoch(RUN_ID);
		assertThat(epoch).isPositive();

		waiter.join(8_000L);
		assertThat(waiter.isAlive()).as("前端超时必须收口（回填结局给模型）").isFalse();
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
		// 超时不是"本轮结束"：模型收到结局文本后继续跑 ⇒ 租约必须交棒，不得释放
		assertThat(this.fake.scriptDeletes.get()).as("超时出口不得删租约").isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isEqualTo(epoch + "|inst-a");
		assertThat(this.store.heldRunEpoch(RUN_ID)).isEqualTo(epoch);
		assertThat(this.renewScheduler.activeCount()).as("交棒后续租任务已登记").isEqualTo(1);
		// fence 仍生效：驱动方随后的写回走 fenced 路径（只有持租写回才会把 runEpoch 落到快照上）
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(this.store.get(RUN_ID).getRunEpoch()).as("写回仍带本 epoch（fencing 语义仍在）")
			.isEqualTo(epoch);
	}

	/**
	 * 取消是<b>另一种</b>出口：整轮被终止（{@code done{cancelled:true}}），本线程不再驱动，
	 * 所以当场还租是对的（报告理由见收口报告）。本用例把这条差异锁住，
	 * 防止"把 C1 的修法照抄到取消路径"变成"取消后还占着续跑权"。
	 */
	@Test
	void cancelStillReleasesTheLeaseBecauseTheRunTerminates() throws Exception {
		seedSuspended(PendingToolCall.PENDING);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(true);
		PendingToolCall entry = pendingEntry(PendingToolCall.PENDING);
		Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, entry), "c1-cancel");
		waiter.setDaemon(true);
		waiter.start();
		// 取消是"加速通道"：闸门一进循环就被 isCancelled 拦下，不必等 latch 登记
		waiter.join(5_000L);

		assertThat(waiter.isAlive()).isFalse();
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("本轮终止 ⇒ 还租").isNull();
		assertThat(this.store.heldRunEpoch(RUN_ID)).isZero();
		assertThat(this.renewScheduler.activeCount()).as("不再续租").isZero();
	}

	// ── R2 派发路径的续租同样要有存活判据 ─────────────────────────────────

	/**
	 * 死卡兜底派发成功（ACCEPTED）后把租约交给续租调度器 —— 那个任务同样<b>不能恒真</b>：
	 * 驱动侧若再没写终态（线程被杀/异常退出），恒真续租会把租约永远攥在本进程手里，
	 * 别的实例接不了管、运维也看不出这个租约已经没有主人。
	 *
	 * <p>本用例模拟"终态由别处写入，但本进程没收到释放信号"（等价于驱动线程死掉）：
	 * 判据转假后下一拍必须停续租 + 归还租约，且<b>不再发生续租往返</b>。
	 */
	@Test
	void dispatchedResumeRenewalStopsOnceTheRunIsNoLongerActive() {
		seedSuspended(PendingToolCall.PENDING);
		assertThat(this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意").resumeTrigger().outcome())
			.isEqualTo("SCHEDULED");
		drainDispatch();
		assertThat(this.renewScheduler.activeCount()).as("ACCEPTED ⇒ 交续租调度器").isEqualTo(1);
		long epoch = this.store.heldRunEpoch(RUN_ID);
		int renewCallsBefore = this.fake.renewCalls.get();

		// 终态由别处写入（不走本进程的 save ⇒ 没触发 best-effort 释放）
		this.store.get(RUN_ID).setStatus(RunSnapshot.DONE);
		this.renewScheduler.tick();

		assertThat(this.fake.renewCalls.get()).as("判据转假 ⇒ 不再续租").isEqualTo(renewCallsBefore);
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("判据转假 ⇒ 归还租约（别让接管方等 TTL）")
			.isNull();
		assertThat(this.renewScheduler.activeCount()).isZero();
		assertThat(this.store.heldRunEpoch(RUN_ID)).isZero();
		assertThat(epoch).isPositive();
	}

	// ── 重入命中：非 ACCEPTED 不得还掉"别人那一任" ───────────────────────

	/**
	 * 本进程<b>已经</b>持有这一轮的租约（典型形态：等待方交棒后，外层循环正在驱动），此时又发生一次
	 * 死卡兜底触发 —— 抢租会可重入命中同一任（{@code created=false}）。若续跑结局不是 ACCEPTED
	 * （{@code PENDING_UNRESOLVED} / {@code ALREADY_RUNNING} 等），<b>不能</b>还租：
	 * 那会把正在驱动/正在等的那一任删掉，它的 fencing 写随之退化成 plain SET。
	 *
	 * <p>核对结论（第 3 条）：这条路径在旧实现里被 {@code alreadyHeld = heldRunEpoch > 0} 挡住了
	 * 单线程情形，但该判据是"读后做"——并发双触发时两边都可能读到 0，于是同型缺陷成立。
	 * 现在与 ResumeService 统一用 {@code created}，本用例把"重入不释放"锁住。
	 */
	@Test
	void reentrantTriggerNeverReleasesTheLeaseTheDriverAlreadyHolds() {
		seedSuspended(PendingToolCall.PENDING);
		long epoch = this.store.tryAcquireRunLease(RUN_ID).orElseThrow().epoch();
		when(this.resumeService.resume(eq(RUN_ID), eq(false), any())).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.PENDING_UNRESOLVED, Map.of("runId", RUN_ID, "reason", "PENDING_UNRESOLVED")));

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");
		assertThat(submission.resumeTrigger().outcome()).isEqualTo("SCHEDULED");
		drainDispatch();

		verify(this.resumeService).resume(eq(RUN_ID), eq(false), any());
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID)))
			.as("重入命中（created=false）⇒ 结局非 ACCEPTED 也不得还租").isNotNull();
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("进程内视图仍持有").isEqualTo(epoch);
		assertThat(this.fake.scriptDeletes.get()).as("一次都没删").isZero();
	}

	// ───────────────────────── helpers ─────────────────────────

	private void seedSuspended(String status) {
		seedSuspended(status, PendingToolCall.KIND_CONFIRM, "start_publish");
	}

	private void seedSuspended(String status, String kind, String name) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		PendingToolCall entry = pendingEntry(status);
		entry.setKind(kind);
		entry.setName(name);
		this.store.seedPending(RUN_ID, entry);
	}

	private void seedPending(String toolCallId, String status) {
		PendingToolCall pending = pendingEntry(status);
		pending.setToolCallId(toolCallId);
		this.store.seedPending(RUN_ID, pending);
	}

	private static PendingToolCall pendingEntry(String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setName("start_publish");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(status);
		return pending;
	}

	private void awaitLatchRegistered(String runId) throws InterruptedException {
		for (int i = 0; i < 300 && !this.gate.hasActiveLatch(runId); i++) {
			Thread.sleep(10L);
		}
		assertThat(this.gate.hasActiveLatch(runId)).as("等待侧闸门已登记").isTrue();
	}

	private void drainDispatch() {
		synchronized (this.dispatched) {
			this.dispatched.forEach(Runnable::run);
			this.dispatched.clear();
		}
	}

	/** 可控的一次性延迟重试调度替身（真 35s 只能靠 sleep，慢且不稳）。 */
	static final class FakeRetryScheduler implements ConfirmGate.RetryScheduler {

		int scheduled;

		Duration delay;

		private final List<Runnable> tasks = new ArrayList<>();

		@Override
		public AutoCloseable schedule(Runnable task, Duration delay) {
			this.scheduled++;
			this.delay = delay;
			this.tasks.add(task);
			return () -> {
			};
		}

		void runAll() {
			List<Runnable> copy;
			synchronized (this.tasks) {
				copy = new ArrayList<>(this.tasks);
				this.tasks.clear();
			}
			copy.forEach(Runnable::run);
		}

	}


}
