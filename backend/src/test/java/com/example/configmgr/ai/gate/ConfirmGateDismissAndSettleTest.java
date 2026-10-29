package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormIssue;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确认门的两条竞态/次序口径（2026-10-29 修复批）。
 *
 * <h2>① dismiss 抢到决策 guard 之后必须重读条目</h2>
 * {@code dismiss} 的入参 {@code pending} 是<b>抢 guard 之前</b>读到的副本。若这中间有条
 * "不带 guard 的写者"落了库（前端超时收敛、整轮取消等），旧实现随后无条件把副本改成
 * {@code FRONTEND_CANCELLED} 并发帧 —— 于是<b>已经写下的结局被覆盖</b>，同一张卡出现
 * 两帧、两个互相矛盾的结论。正确口径与 {@code doSubmitDecision} 一致：抢到 guard 后复读，
 * 非 {@code PENDING} 就按幂等回 {@code DUPLICATE}，<b>不覆盖、不发第二帧</b>。
 *
 * <h2>② 附件的消费发生在"决策落库之后"</h2>
 * 复核器（{@code inspectResult}）只做只读校验；一次性资源的消费/回收走决策后的
 * {@code settleAcceptedResult} / {@code settleDiscardedResult} 钩子，且<b>只在结论已落库之后</b>调用、
 * 结论未落库（复核被拒 / 重复提交）时一次都不调用。
 */
class ConfirmGateDismissAndSettleTest {

	private static final String RUN_ID = "run-dismiss-settle";

	private static final String CALL_ID = "call-form";

	private final ObjectMapper mapper = new ObjectMapper();

	private final RecordingGuard guard = new RecordingGuard();

	private final LateWriterStore store = new LateWriterStore();

	/** 真实注册表 + 真实写出器（{@code properties == null} ⇒ 同步直执，帧序确定）。 */
	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final SseChatEmitter out = this.registry.of(RUN_ID);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations,
			this.beacon, List.of(this.guard));

	@BeforeEach
	void seedPendingFrontendForm() {
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		this.store.seedRun(RUN_ID, "sess-1");
		this.store.putPending(RUN_ID, pendingForm());
		this.guard.store = this.store;
	}

	@Test
	void dismissAfterLosingToAGuardlessWriterKeepsTheWrittenOutcomeAndEmitsNoFrame() {
		FrameWire wire = FrameWire.attachTo(this.out);
		// LateWriterStore 在 tryRecordDecision 之前模拟"不带 guard 的写者"把条目写成 REJECTED（超时收敛）
		this.store.injectGuardlessWriter = true;
		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{}", "http-post", true);

		assertThat(submission.outcome()).as("抢 guard 之后复读发现已决 ⇒ 幂等拒绝").isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		PendingToolCall stored = this.store.pendingRaw(RUN_ID, CALL_ID);
		assertThat(stored.getStatus()).as("不得用副本覆盖别人写下的结局")
			.isEqualTo(PendingToolCall.REJECTED);
		assertThat(stored.getResultText()).contains("另一写者写下的结局");
		assertThat(wire.types()).as("同一张卡不得出现第二帧").doesNotContain("frontend_tool_result");
		assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("frontend_tool_result");
		assertThat(this.guard.discarded.get()).as("结论不是本侧写的，回收也不该由本侧做").isZero();
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	@Test
	void dismissWinningTheGuardStillCancelsTheFormAndSettlesOnce() {
		FrameWire wire = FrameWire.attachTo(this.out);

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{}", "http-post", true);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		PendingToolCall stored = this.store.pendingRaw(RUN_ID, CALL_ID);
		assertThat(stored.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
		assertThat(stored.getResultText()).contains("FRONTEND_CANCELLED");
		assertThat(wire.types().stream().filter("frontend_tool_result"::equals)).as("正常放弃照旧只有一帧").hasSize(1);
		assertThat(this.guard.discarded.get()).as("放弃后的回收钩子恰一次").isEqualTo(1);
		assertThat(this.guard.statusAtDiscard).as("回收发生在取消结论落库之后")
			.containsExactly(PendingToolCall.FRONTEND_CANCELLED);
	}

	@Test
	void acceptedResultSettlesResourcesOnlyAfterTheDecisionIsPersisted() {
		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{\"a\":1}", "http-post",
				false, "ok");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.guard.settled.get()).as("接受路径的资源收口恰一次").isEqualTo(1);
		assertThat(this.guard.statusAtSettle).as("钩子跑在决策落库之后（不是校验阶段）")
			.containsExactly(PendingToolCall.FRONTEND_RESULT);
	}

	@Test
	void rejectedInspectionNeverSettlesResources() {
		FrameWire wire = FrameWire.attachTo(this.out);
		this.guard.reject = true;

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{}", "http-post");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.REJECTED);
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(this.guard.settled.get()).as("复核不过：一次性资源一件都不许动").isZero();
		assertThat(wire.types()).doesNotContain("frontend_tool_result");
	}

	@Test
	void duplicateSubmissionDoesNotSettleResourcesAgain() {
		this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{\"a\":1}", "http-post", false, "ok");

		ConfirmGate.Submission duplicate = this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{\"a\":1}", "http-post",
				false, "ok");

		assertThat(duplicate.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(this.guard.settled.get()).as("重复提交不再触发收口").isEqualTo(1);
	}

	@Test
	void settleFailureDoesNotChangeTheAlreadyPersistedDecision() {
		this.guard.throwOnSettle = true;

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{\"a\":1}", "http-post",
				false, "ok");

		assertThat(submission.outcome()).as("收口失败不得改写结论").isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(this.guard.settled.get()).isEqualTo(1);
	}

	private static PendingToolCall pendingForm() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("generative_form");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setArguments("{\"form\":{\"scenario\":\"CLARIFY\",\"fields\":[]}}");
		return pending;
	}

	/** 记录"决策后钩子"的调用次数与调用时刻的条目状态（次序即断言对象）。 */
	private static final class RecordingGuard implements FrontendToolGuard {

		private final AtomicInteger settled = new AtomicInteger();

		private final AtomicInteger discarded = new AtomicInteger();

		private final List<String> statusAtSettle = new CopyOnWriteArrayList<>();

		private final List<String> statusAtDiscard = new CopyOnWriteArrayList<>();

		private volatile boolean reject;

		private volatile boolean throwOnSettle;

		private RunStore store;

		@Override
		public boolean supports(String toolName) {
			return "generative_form".equals(toolName);
		}

		@Override
		public Verdict inspectArguments(String argumentsJson) {
			return Verdict.accept();
		}

		@Override
		public Verdict inspectResult(String argumentsJson, String resultJson) {
			return Verdict.accept();
		}

		@Override
		public Verdict inspectResult(String runId, String toolCallId, String argumentsJson, String resultJson) {
			if (this.reject) {
				List<FormIssue> issues = List.of(FormIssue.form("VALUE_TYPE_MISMATCH", "模拟复核不过"));
				return Verdict.rejectIssues("FORM_RESULT_REJECTED", "模拟复核不过", issues);
			}
			return Verdict.accept();
		}

		@Override
		public void settleAcceptedResult(String runId, String toolCallId, String argumentsJson, String resultJson) {
			this.settled.incrementAndGet();
			if (this.throwOnSettle) {
				throw new IllegalStateException("模拟收口故障（Redis 抖动）");
			}
			this.statusAtSettle.add(this.store == null ? "no-store"
					: String.valueOf(this.store.pending(runId, toolCallId).getStatus()));
		}

		@Override
		public void settleDiscardedResult(String runId, String toolCallId) {
			this.discarded.incrementAndGet();
			this.statusAtDiscard.add(this.store == null ? "no-store"
					: String.valueOf(this.store.pending(runId, toolCallId).getStatus()));
		}

	}

	/** 在"抢 guard 那一刻"注入一个不带 guard 的写者（模拟超时/取消侧先落库）。 */
	private static final class LateWriterStore extends InMemoryRunStore {

		private final Set<String> injected = ConcurrentHashMap.newKeySet();

		private volatile boolean injectGuardlessWriter;

		@Override
		public DecisionClaim tryRecordDecision(String runId, String toolCallId, String status) {
			if (this.injectGuardlessWriter && this.injected.add(toolCallId)) {
				PendingToolCall concurrent = pendingRaw(runId, toolCallId);
				if (concurrent != null && PendingToolCall.PENDING.equals(concurrent.getStatus())) {
					concurrent.setStatus(PendingToolCall.REJECTED);
					concurrent.setResolvedAtMs(System.currentTimeMillis());
					concurrent.setResultText("另一写者写下的结局：超时收敛");
					putPending(runId, concurrent);
				}
			}
			return super.tryRecordDecision(runId, toolCallId, status);
		}

	}

}
