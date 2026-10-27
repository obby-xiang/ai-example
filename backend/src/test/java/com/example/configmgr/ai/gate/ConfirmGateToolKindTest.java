package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 闸门侧的入口 kind 校验（第 6 项）：写入<b>前</b>按服务端条目的 {@code kind} 判入口归属。
 *
 * <p>控制器那层是"早拒绝"（少一次无谓的写路径），本类锁的是<b>权威口径</b>：即便调用方绕开
 * {@code AiController}（内部任务、旧脚本、将来的新入口），错入口的提交也<b>不得落库</b> ——
 * 不写状态、不占决策 guard、不发回执帧、不唤醒等待方。
 *
 * <p>顺序也是契约的一部分：{@code NOT_FOUND} → <b>kind</b> → {@code DUPLICATE}。于是
 * "正确 kind 的重复提交"仍是 409 DUPLICATE，而"错入口 + 已决"报 mismath（入口归属比幂等更根本）。
 *
 * <p>{@code kind=null}：同样拒绝（{@code TOOL_KIND_MISMATCH}）+ WARN —— 缺证据不授权。
 * 这是本批收紧的口径（原"兼容放行"已废止）：无法证明条目属于哪条通道时，既不批准也不执行。
 */
class ConfirmGateToolKindTest {

	private static final String RUN = "run-kind-gate";

	private static final String SESSION = "s-kind-gate";

	private static final String CALL = "call-kind-gate";

	private static final String CALL2 = "call-kind-gate-2";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations,
			new ToolActivityBeacon(), List.of());

	@BeforeEach
	void setUp() {
		when(this.cancellations.isCancelled(RUN)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		RunSnapshot snapshot = this.store.seedRun(RUN, SESSION);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
	}

	// ── 错入口：确认门 ← FRONTEND ──────────────────────────────────────────

	@Test
	void decisionOnAFrontendEntryIsRejectedWithoutTouchingIt() {
		PendingToolCall seeded = entry(PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING);
		this.store.putPending(RUN, seeded);

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN, CALL, true, null);

		assertThat(submission.outcome().name()).isEqualTo("TOOL_KIND_MISMATCH");
		assertThat(submission.woke()).isFalse();
		PendingToolCall after = this.store.pending(RUN, CALL);
		assertThat(after.getStatus()).as("条目原样（仍是 PENDING）").isEqualTo(PendingToolCall.PENDING);
		assertThat(after.isExecuted()).isFalse();
		assertThat(after.getResolvedAtMs()).isZero();
		assertThat(this.store.archivedTypes(RUN)).as("错入口不发回执帧").doesNotContain("confirm_decision");
	}

	// ── 错入口：回灌 ← CONFIRM ─────────────────────────────────────────────

	@Test
	void frontendResultOnAConfirmEntryIsRejectedWithoutTouchingIt() {
		this.store.putPending(RUN, entry(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING));

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN, CALL, "ok", "http-post", false,
				null);

		assertThat(submission.outcome().name()).isEqualTo("TOOL_KIND_MISMATCH");
		PendingToolCall after = this.store.pending(RUN, CALL);
		assertThat(after.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(after.getResultText()).isNull();
		assertThat(this.store.archivedTypes(RUN)).doesNotContain("frontend_tool_result");
	}

	/** 放弃与回灌同一张表：错入口的 cancelled 也不能把确认门条目写成"用户放弃了"。 */
	@Test
	void cancelledFrontendResultOnAConfirmEntryIsRejectedToo() {
		this.store.putPending(RUN, entry(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING));

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN, CALL, null, "http-post", true,
				null);

		assertThat(submission.outcome().name()).isEqualTo("TOOL_KIND_MISMATCH");
		assertThat(this.store.pending(RUN, CALL).getStatus()).isEqualTo(PendingToolCall.PENDING);
	}

	// ── 收紧：缺 kind 的条目既不批准也不回灌 ─────────────────────────────────

	@Test
	void entryWithoutKindIsRejectedByBothPaths() {
		this.store.putPending(RUN, entry(null, PendingToolCall.PENDING));
		ConfirmGate.Submission decision = this.gate.submitDecision(RUN, CALL, true, null);
		assertThat(decision.outcome()).as("kind=null 不得批准").isEqualTo(ConfirmGate.Outcome.TOOL_KIND_MISMATCH);
		assertThat(this.store.pending(RUN, CALL).getStatus()).isEqualTo(PendingToolCall.PENDING);

		this.store.putPending(RUN, entry(CALL2, null, PendingToolCall.PENDING));
		ConfirmGate.Submission result = this.gate.submitFrontendResult(RUN, CALL2, "ok", "http-post", false, null);
		assertThat(result.outcome()).as("kind=null 也不得被回灌改写").isEqualTo(ConfirmGate.Outcome.TOOL_KIND_MISMATCH);
		assertThat(this.store.pending(RUN, CALL2).getStatus()).isEqualTo(PendingToolCall.PENDING);
	}

	// ── 正确 kind：幂等/正常路径不回归 ─────────────────────────────────────

	@Test
	void correctKindDuplicatesStayDuplicate() {
		this.store.putPending(RUN, entry(PendingToolCall.KIND_CONFIRM, PendingToolCall.APPROVED));
		assertThat(this.gate.submitDecision(RUN, CALL, true, null).outcome())
			.isEqualTo(ConfirmGate.Outcome.DUPLICATE);

		this.store.putPending(RUN, entry(CALL2, PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT));
		assertThat(this.gate.submitFrontendResult(RUN, CALL2, "ok", "http-post", false, null).outcome())
			.isEqualTo(ConfirmGate.Outcome.DUPLICATE);
	}

	@Test
	void correctKindDecisionAndFrontendCancelStillWork() {
		this.store.putPending(RUN, entry(PendingToolCall.KIND_CONFIRM, PendingToolCall.PENDING));
		assertThat(this.gate.submitDecision(RUN, CALL, false, "不需要").outcome())
			.isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.store.pending(RUN, CALL).getStatus()).isEqualTo(PendingToolCall.REJECTED);

		this.store.putPending(RUN, entry(CALL2, PendingToolCall.KIND_FRONTEND, PendingToolCall.PENDING));
		assertThat(this.gate.submitFrontendResult(RUN, CALL2, null, "http-post", true, null).outcome())
			.isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.store.pending(RUN, CALL2).getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
		assertThat(this.store.pending(RUN, CALL2).isExecuted()).isFalse();
	}

	/** 错入口 + 已决：报 mismatch（入口归属是比"重复提交"更根本的事实）。 */
	@Test
	void wrongKindBeatsDuplicate() {
		this.store.putPending(RUN, entry(PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT));
		assertThat(this.gate.submitDecision(RUN, CALL, true, null).outcome().name())
			.isEqualTo("TOOL_KIND_MISMATCH");
	}

	@Test
	void unknownEntryStillReportsNotFound() {
		assertThat(this.gate.submitDecision("no-such-run", CALL, true, null).outcome())
			.isEqualTo(ConfirmGate.Outcome.NOT_FOUND);
		assertThat(this.gate.submitFrontendResult("no-such-run", CALL, "ok", "http-post", false, null).outcome())
			.isEqualTo(ConfirmGate.Outcome.NOT_FOUND);
	}

	private static PendingToolCall entry(String kind, String status) {
		return entry(CALL, kind, status);
	}

	private static PendingToolCall entry(String callId, String kind, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(callId);
		pending.setName("generative_form");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(kind);
		pending.setStatus(status);
		return pending;
	}

}
