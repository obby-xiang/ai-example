package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L0（2026-10-21 用户直裁）刷新恢复口径的后端面：<b>已决条目到达 {@code await} 时不再补发请求帧</b>。
 *
 * <p>洞在刷新恢复路径上：B1「挂起即断」之后，一批多调用的请求帧是<b>逐条</b>发的，
 * 而 {@code saveSuspended} 把整批条目一起外置 —— 前端若按"条目存在"建卡，用户能对一张
 * 请求帧还没发出的卡提前决策；后端循环随后走到该条目时旧代码<b>无条件</b>
 * {@code confirmRequest}/{@code frontendToolRequest} + {@code completeSubscriptions}，
 * 已收敛的卡会在前端瞬时复活成 PENDING、再被结局帧收敛。
 *
 * <p>本类锁四件事（真实 {@link ConfirmGate} + 真实 {@link SseChatEmitter} + 语义真实的
 * {@link InMemoryRunStore}，只把注册表与取消登记做成桩）：
 * <ol>
 * <li>已决的确认门条目 → <b>不发</b> {@code confirm_request}、<b>不收流</b>，直接返回既有决策；</li>
 * <li>已决的前端工具条目 → <b>不发</b> {@code frontend_tool_request}，直接用既有 {@code resultText}；</li>
 * <li>已决分支不把预登记闸门留在 {@code latches} 里（否则本进程死卡兜底被挡住）；</li>
 * <li><b>M-1（2026-10-21 收口修）</b>「读时 PENDING、置位写前被另一实例决策」的跨进程窗口：
 * 写后复判同样按已决消费（不发帧、不断流、归还闸门）。</li>
 * </ol>
 */
class ConfirmGateResolvedSkipTest {

	private static final String RUN_ID = "run-resolved-skip";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	/** 真实注册表 + 真实写出器（{@code properties == null} ⇒ 同步直执，帧序确定）。 */
	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final SseChatEmitter out = this.registry.of(RUN_ID);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations,
			this.beacon, List.of());

	@BeforeEach
	void stubCommon() {
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		this.store.seedRun(RUN_ID, "sess-1");
	}

	@Test
	void approvedEntryAtAwaitIsConsumedWithoutReEmittingTheRequestFrame() {
		FrameWire wire = FrameWire.attachTo(this.out);
		// 预置：该条目已被人决策（另一页签 / 旧客户端在请求帧之外提前提交），循环随后才走到它
		this.store.putPending(RUN_ID, decided("call-1", PendingToolCall.KIND_CONFIRM, PendingToolCall.APPROVED, null));
		// 预登记闸门（真实路径由 SpToolCallingManager 在 suspended 帧之前 arm）
		this.gate.arm(RUN_ID, List.of("call-1"));

		PendingToolCall resolved = this.gate.awaitDecision(RUN_ID, pending("call-1", PendingToolCall.KIND_CONFIRM));

		assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.APPROVED);
		assertThat(wire.types()).as("已决条目不得补发确认请求帧").doesNotContain("confirm_request");
		assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("confirm_request");
		// 断连点也跟着跳过：本来就没有"待卡送达"的连接
		assertThat(this.out.subscriberCount()).as("已决条目不得收流").isEqualTo(1);
		// 闸门不残留（下一张卡的 arm/await 与死卡兜底判据不被污染）
		assertThat(this.gate.hasActiveLatch(RUN_ID)).isFalse();
	}

	@Test
	void resolvedFrontendEntryAtAwaitIsConsumedWithoutReEmittingTheRequestFrame() {
		FrameWire wire = FrameWire.attachTo(this.out);
		this.store.putPending(RUN_ID,
				decided("call-f", PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT, "前端已回灌的结果"));

		PendingToolCall resolved = this.gate.awaitFrontendResult(RUN_ID, pending("call-f", PendingToolCall.KIND_FRONTEND));

		assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(resolved.getResultText()).isEqualTo("前端已回灌的结果");
		assertThat(wire.types()).as("已决条目不得补发前端工具请求帧").doesNotContain("frontend_tool_request");
		assertThat(this.out.subscriberCount()).as("已决条目不得收流").isEqualTo(1);
	}

	@Test
	void rejectedEntryAtAwaitKeepsTheExistingRejectionTextForTheModel() {
		this.store.putPending(RUN_ID, decided("call-r", PendingToolCall.KIND_CONFIRM, PendingToolCall.REJECTED,
				"用户拒绝了该操作，工具未执行。"));

		PendingToolCall resolved = this.gate.awaitDecision(RUN_ID, pending("call-r", PendingToolCall.KIND_CONFIRM));

		assertThat(resolved.getStatus()).isEqualTo(PendingToolCall.REJECTED);
		assertThat(resolved.getResultText()).isEqualTo("用户拒绝了该操作，工具未执行。");
		assertThat(resolved.isExecuted()).isFalse();
	}

	// ── M-1：读时 PENDING、置位写前被另一实例决策（跨进程窗口） ─────────────────

	@Test
	void entryDecidedBetweenTheReadAndTheMarkIsConsumedInsteadOfEmitted() throws Exception {
		// 木桩 store 精确复现窗口：第一次读（判定用）返回 PENDING，置位调用返回"已决"的新副本
		// （真相 = 另一实例在本实例的 HGET 与 HSET 之间写了决策）
		RunStore racingStore = mock(RunStore.class);
		when(racingStore.pending(RUN_ID, "call-x")).thenReturn(pending("call-x", PendingToolCall.KIND_CONFIRM));
		when(racingStore.markRequestEmitted(eq(RUN_ID), eq("call-x"), anyLong()))
			.thenReturn(decided("call-x", PendingToolCall.KIND_CONFIRM, PendingToolCall.APPROVED, null));
		ConfirmGate racingGate = new ConfirmGate(racingStore, this.registry, new AiProperties(), this.cancellations,
				this.beacon, List.of());
		FrameWire wire = FrameWire.attachTo(this.out);
		racingGate.arm(RUN_ID, List.of("call-x"));

		// 放在守护线程里跑：**回归形态是"挂死"而不是断言失败**（旧实现照常发帧后无限等一个永远不会
		// 再来的决策），用 join 超时把它变回可判定的失败，避免 CI 挂住。
		PendingToolCall[] resolved = new PendingToolCall[1];
		Thread runner = new Thread(() -> resolved[0] = racingGate
			.awaitDecision(RUN_ID, pending("call-x", PendingToolCall.KIND_CONFIRM)), "m1-racing");
		runner.setDaemon(true);
		runner.start();
		runner.join(5000);

		assertThat(runner.isAlive()).as("已决条目不得进入等待（回归时本条先红，避免挂死）").isFalse();
		assertThat(resolved[0].getStatus()).as("按已决消费（不虚构 req 置位后照常发帧）")
			.isEqualTo(PendingToolCall.APPROVED);
		assertThat(wire.types()).as("写后复判：已决不得补发请求帧").doesNotContain("confirm_request");
		assertThat(this.out.subscriberCount()).as("已决不得收流").isEqualTo(1);
		assertThat(racingGate.hasActiveLatch(RUN_ID)).as("已决分支归还预登记闸门").isFalse();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 已决条目：状态、结局文本与 resolvedAtMs 齐备（与 {@code submitDecision} 落库形态同形）。 */
	private static PendingToolCall decided(String toolCallId, String kind, String status, String resultText) {
		PendingToolCall pending = pending(toolCallId, kind);
		pending.setStatus(status);
		pending.setOutcome(ToolOutcome.REJECTED.wire());
		pending.setResultText(resultText);
		pending.setExecuted(false);
		pending.setResolvedAtMs(System.currentTimeMillis());
		return pending;
	}

	private static PendingToolCall pending(String toolCallId, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(PendingToolCall.KIND_FRONTEND.equals(kind) ? "generative_form" : "start_export");
		pending.setKind(kind);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

}
