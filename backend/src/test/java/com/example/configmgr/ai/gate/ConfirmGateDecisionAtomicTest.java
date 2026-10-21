package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4（B23L1 批②b）：<b>两个 ConfirmGate 实例共享同一 store</b>并发提交同一张卡的决策 ——
 * 恰一个 {@code ACCEPTED}、恰一个 {@code DUPLICATE}、{@code confirm_decision} 帧恰一次。
 *
 * <h2>为什么单进程锁不够</h2>
 * 决策的"读状态 → 判 PENDING → 改写 → 落库"过去只由 {@code submitLock} 串行化，而那是
 * <b>实例内</b>的锁：两个实例（两个 JVM / 两次请求落在不同实例）各读一次 {@code PENDING}，
 * 各自改写再落库 ⇒ 两次都成功、两帧回执、后写覆盖先写。Redis 侧唯一的原子判定点是
 * {@code ai:decided:<runId>} 的 {@code HSETNX}（批②a 建设施）：抢到 guard 的写者才是唯一写者。
 *
 * <p>本用例用"两个各自持锁的 gate 实例 + 同一份外置状态"把这条并发事实钉死；
 * {@link FirstTwoReadsBarrier} 让两个线程都先读到 {@code PENDING} 再继续，
 * 于是失效实现是<b>确定性红</b>（不是靠运气撞窗口）。
 *
 * <h2>实例面维度（M8 / DC-11 边界）</h2>
 * <ul>
 * <li><b>单实例即可达</b>：{@code duplicateFromAnotherInstance...}（顺序重复：第二读看到已决）、
 *     {@code walDecidedCardIsRepairedAndThenDriven}（WAL 补齐 + 驱动）；</li>
 * <li><b>必须是两个实例</b>：两条并发用例 —— 单实例只有一把 {@code submitLock}，双写窗口在锁内
 *     就被消除了，根本复现不出"两边都 accepted"；本类用两个 gate 实例（各自一把锁）+ 同一 store
 *     才让这条边界可见；</li>
 * <li><b>不覆盖</b>：真跨进程（两 JVM）的栅栏语义 —— 那是 harness 的事；本类证明的是
 *     "锁之外还有一层 guard"这个设计事实。</li>
 * </ul>
 */
class ConfirmGateDecisionAtomicTest {

	private static final String RUN_ID = "run-atomic";

	private static final String SESSION_ID = "s-atomic";

	private static final String TOOL_CALL_ID = "call-atomic";

	/** 两个提交线程都读到 PENDING 之后才允许继续（复现"双写窗口"；只在并发用例里武装）。 */
	private final FirstTwoReadsBarrier barrier = new FirstTwoReadsBarrier();

	/** 往返模式：{@code pending()} 返回深拷贝（与真实 Redis 的反序列化语义一致）。 */
	private final InMemoryRunStore store = new InMemoryRunStore(true) {

		@Override
		public PendingToolCall pendingRaw(String runId, String toolCallId) {
			// 写路径（submitDecision/submitFrontendResult）读的是原始条目，屏障就架在这里：
			// 让两个线程都先读到 PENDING，再各自去抢 guard，双写窗口才是确定性的。
			PendingToolCall pending = super.pendingRaw(runId, toolCallId);
			return ConfirmGateDecisionAtomicTest.this.barrier.armed()
					? ConfirmGateDecisionAtomicTest.this.barrier.hold(pending)
					: pending;
		}

	};

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);

	/** 两个实例：各自一把 {@code submitLock}，共享同一份外置状态。 */
	private final ConfirmGate instanceA = gate();

	private final ConfirmGate instanceB = gate();

	@Test
	void twoInstancesSubmittingConcurrentlyYieldExactlyOneAcceptedAndOneDuplicate() throws Exception {
		seedSuspendedCard();
		CyclicBarrier start = new CyclicBarrier(2);
		AtomicReference<ConfirmGate.Submission> first = new AtomicReference<>();
		AtomicReference<ConfirmGate.Submission> second = new AtomicReference<>();
		Thread a = submitter(this.instanceA, start, first);
		Thread b = submitter(this.instanceB, start, second);

		this.barrier.arm();
		a.start();
		b.start();
		a.join(5_000);
		b.join(5_000);

		assertThat(a.isAlive()).as("提交线程 A 未在 5s 内结束").isFalse();
		assertThat(b.isAlive()).as("提交线程 B 未在 5s 内结束").isFalse();
		assertThat(List.of(first.get().outcome(), second.get().outcome()))
			.as("恰一个 accepted、恰一个 DUPLICATE（另一个实例不得再落一次决策）")
			.containsExactlyInAnyOrder(ConfirmGate.Outcome.ACCEPTED, ConfirmGate.Outcome.DUPLICATE);
		// 回执帧是"人的决策已落库"的对外证据：双写会让前端看到两条同 id 决策
		verify(this.emitter, times(1)).confirmDecision(any(PendingToolCall.class), anyString(), any(), eq(0L));
		// 落库结论唯一且稳定
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus()).isEqualTo(PendingToolCall.APPROVED);
		// 只有抢到 guard 的一方会尝试续跑（DUPLICATE 分支不再触发）
		long triggered = List.of(first.get(), second.get()).stream()
			.filter(submission -> submission.resumeTrigger().triggered())
			.count();
		assertThat(triggered).as("只有唯一写者可以触发续跑").isLessThanOrEqualTo(1L);
	}

	@Test
	void duplicateFromAnotherInstanceReportsTheDecidedStatusInsteadOfRewritingIt() {
		seedSuspendedCard();
		// 另一实例已经落了决策（guard + 条目都已 decided）
		ConfirmGate.Submission winner = this.instanceA.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");
		assertThat(winner.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		long resolvedAt = this.store.pending(RUN_ID, TOOL_CALL_ID).getResolvedAtMs();

		// 第二实例用相反的结论提交：必须按幂等拒绝，且不得把 APPROVED 改写成 REJECTED
		ConfirmGate.Submission loser = this.instanceB.submitDecision(RUN_ID, TOOL_CALL_ID, false, "拒绝");

		assertThat(loser.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(loser.pending().getStatus()).isEqualTo(PendingToolCall.APPROVED);
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus()).isEqualTo(PendingToolCall.APPROVED);
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getResolvedAtMs())
			.as("重复提交不得二次结算").isEqualTo(resolvedAt);
		verify(this.emitter, times(1)).confirmDecision(any(PendingToolCall.class), anyString(), any(), eq(0L));
	}

	@Test
	void frontendResultFromTwoInstancesAlsoEmitsOneFrameOnly() throws Exception {
		seedSuspendedCard(PendingToolCall.KIND_FRONTEND);
		CyclicBarrier start = new CyclicBarrier(2);
		AtomicReference<ConfirmGate.Submission> first = new AtomicReference<>();
		AtomicReference<ConfirmGate.Submission> second = new AtomicReference<>();
		Thread a = submitter(this.instanceA, start, first, "{\"value\":\"x\"}");
		Thread b = submitter(this.instanceB, start, second, "{\"value\":\"x\"}");

		this.barrier.arm();
		a.start();
		b.start();
		a.join(5_000);
		b.join(5_000);

		assertThat(List.of(first.get().outcome(), second.get().outcome()))
			.as("回灌与决策同一套 guard 口径（submitFrontendResult 同构接入）")
			.containsExactlyInAnyOrder(ConfirmGate.Outcome.ACCEPTED, ConfirmGate.Outcome.DUPLICATE);
		verify(this.emitter, times(1)).frontendToolResult(any(PendingToolCall.class), eq(true), anyString());
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus())
			.isEqualTo(PendingToolCall.FRONTEND_RESULT);
	}

	// ── I2：WAL 补齐出来的结论必须有驱动者 ────────────────────────────────

	/**
	 * 上一写者死在"写 guard → 改条目"之间（{@code ai:decided} 就是那条 WAL）：
	 * 后来的提交按幂等回 {@code DUPLICATE}，但<b>条目必须被补齐</b>，并且补出来的结论
	 * <b>要有驱动者</b>（{@code maybeTriggerResume}）—— 否则这张卡停在"已决 + 无人推进"。
	 */
	@Test
	void walDecidedCardIsRepairedAndThenDriven() {
		seedSuspendedCard();
		this.store.tryRecordDecision(RUN_ID, TOOL_CALL_ID, PendingToolCall.REJECTED);

		ConfirmGate.Submission submission = this.instanceB.submitDecision(RUN_ID, TOOL_CALL_ID, false, "拒绝");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(this.store.pending(RUN_ID, TOOL_CALL_ID).getStatus())
			.as("WAL 补齐：结论对读侧可见").isEqualTo(PendingToolCall.REJECTED);
		// 补出来的结论由这个写者负责驱动（woke=false + 快照 SUSPENDED ⇒ 走死卡兜底）
		verify(this.resumeService, times(1)).resume(RUN_ID);
	}

	// ───────────────────────── helpers ─────────────────────────

	private void seedSuspendedCard() {
		seedSuspendedCard(PendingToolCall.KIND_CONFIRM);
	}

	private void seedSuspendedCard(String kind) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setName(PendingToolCall.KIND_FRONTEND.equals(kind) ? "generative_form" : "start_publish");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(kind);
		pending.setStatus(PendingToolCall.PENDING);
		this.store.seedPending(RUN_ID, pending);
		when(this.provider.getIfAvailable()).thenReturn(this.resumeService);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.resumeService.resume(RUN_ID)).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.NOT_FOUND, Map.of("runId", RUN_ID)));
	}

	/** 两个实例都只装配到 8 参兼容构造器（本用例锁的是决策原子性，不是租约）。 */
	private ConfirmGate gate() {
		return new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations, this.beacon,
				List.of(), this.provider, Runnable::run);
	}

	private Thread submitter(ConfirmGate gate, CyclicBarrier start, AtomicReference<ConfirmGate.Submission> sink) {
		return new Thread(() -> {
			await(start);
			sink.set(gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意"));
		}, "r4-submit");
	}

	private Thread submitter(ConfirmGate gate, CyclicBarrier start, AtomicReference<ConfirmGate.Submission> sink,
			String result) {
		return new Thread(() -> {
			await(start);
			sink.set(gate.submitFrontendResult(RUN_ID, TOOL_CALL_ID, result, "http-post"));
		}, "r4-frontend");
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(5, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("并发屏障失效", ex);
		}
	}

	/** 前两次 {@code pending()} 读互相等待：两边都拿到了 PENDING 副本才继续。 */
	static final class FirstTwoReadsBarrier {

		private final AtomicInteger calls = new AtomicInteger();

		private final CountDownLatch both = new CountDownLatch(2);

		private volatile boolean armed;

		void arm() {
			this.armed = true;
		}

		boolean armed() {
			return this.armed;
		}

		PendingToolCall hold(PendingToolCall pending) {
			if (this.calls.incrementAndGet() <= 2) {
				this.both.countDown();
				try {
					if (!this.both.await(2, TimeUnit.SECONDS)) {
						throw new IllegalStateException("两个提交线程未在 2s 内都完成首次读");
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("并发屏障被中断", ex);
				}
			}
			return pending;
		}

	}

}
