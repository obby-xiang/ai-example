package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次2 / AR-05：<b>仅模型步重跑</b>的七条前置判定（{@link ResumeService#checkModelStepRetry}）。
 *
 * <p>为什么把判定做成纯静态函数：入口本身要抢租约、抢会话门、交棒线程池（重设施），
 * 而"哪一条前置不满足"是<b>纯数据判定</b>。把它单独测，能在不驱动任何模型/工具的前提下锁住：
 * <ul>
 * <li>只有 {@code FAILED} 才允许（DONE / CANCELLED / RUNNING 一律拒绝，取消优先）；</li>
 * <li>待决工具未全结算 ⇒ 拒绝（且入口会翻成既有 {@code PENDING_UNRESOLVED}）；</li>
 * <li>工具入出参<b>不配对</b> ⇒ 拒绝（悬空 tool_calls 会被上游拒绝）；</li>
 * <li>有<b>半截正文/半截思考</b> ⇒ 拒绝（AR-05：不做"失效替换"）；</li>
 * <li>七条全满足 ⇒ 允许（重跑只重跑模型步：工具零重执行、不追加用户消息、同模型、同固定历史）。</li>
 * </ul>
 */
class ResumeServiceModelStepRetryTest {

	private static final String RUN_ID = "run-model-step-retry";

	@Test
	void failedTurnWithoutPartialContentIsRetryable() {
		RunSnapshot snapshot = snapshot(RunSnapshot.FAILED);
		List<Message> history = List.of(new UserMessage("你好"));

		ResumeService.ModelStepRetryCheck check = ResumeService.checkModelStepRetry(snapshot, List.of(), history);

		assertThat(check.allowed()).as("已失败 + 无待决 + 无半截 + 配对完整 ⇒ 允许").isTrue();
		assertThat(check.failedCheck()).isNull();
	}

	@Test
	void onlyFailedTurnsQualify() {
		assertThat(ResumeService.checkModelStepRetry(snapshot(RunSnapshot.DONE), List.of(), List.of()).failedCheck())
			.isEqualTo("NOT_FAILED");
		assertThat(ResumeService.checkModelStepRetry(snapshot(RunSnapshot.RUNNING), List.of(), List.of()).failedCheck())
			.isEqualTo("NOT_FAILED");
		assertThat(ResumeService.checkModelStepRetry(snapshot(RunSnapshot.SUSPENDED), List.of(), List.of())
			.failedCheck()).isEqualTo("NOT_FAILED");
	}

	/** 取消优先：取消态（状态或取消位任一）一律拒绝 —— 与 FAILED 写口的守卫同一口径。 */
	@Test
	void cancelledTurnIsNeverRetried() {
		RunSnapshot cancelled = snapshot(RunSnapshot.CANCELLED);
		cancelled.setCancelled(true);
		assertThat(ResumeService.checkModelStepRetry(cancelled, List.of(), List.of()).failedCheck())
			.isEqualTo("CANCELLED");

		RunSnapshot flagged = snapshot(RunSnapshot.FAILED);
		flagged.setCancelled(true);
		assertThat(ResumeService.checkModelStepRetry(flagged, List.of(), List.of()).failedCheck())
			.as("状态还是 FAILED 但取消位已置位（中间形态）同样拒绝")
			.isEqualTo("CANCELLED");
	}

	/** 待决工具没结算完 ⇒ 不是"仅模型步"能救的场景。 */
	@Test
	void unsettledPendingToolBlocksRetry() {
		RunSnapshot snapshot = snapshot(RunSnapshot.FAILED);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-1");
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PENDING);

		ResumeService.ModelStepRetryCheck check = ResumeService.checkModelStepRetry(snapshot, List.of(pending),
				List.of(new UserMessage("你好")));

		assertThat(check.allowed()).isFalse();
		assertThat(check.failedCheck()).isEqualTo("PENDING_TOOLS");
	}

	/** 工具入出参不配对（有 tool_call 没 tool 响应）⇒ 拒绝：重跑会把悬空调用再发给上游。 */
	@Test
	void danglingToolCallBlocksRetry() {
		RunSnapshot snapshot = snapshot(RunSnapshot.FAILED);
		AssistantMessage withCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "list_tasks", "{}")))
			.build();
		List<Message> history = List.of(new UserMessage("列出任务"), withCall);

		ResumeService.ModelStepRetryCheck check = ResumeService.checkModelStepRetry(snapshot, List.of(), history);

		assertThat(check.allowed()).isFalse();
		assertThat(check.failedCheck()).isEqualTo("TOOL_PAIRING_INCOMPLETE");
		assertThat(ResumeService.toolPairsComplete(history)).isFalse();
	}

	/** 配对完整时配对判据为真（同一份 history 由 tool_calls + tool 响应组成）。 */
	@Test
	void completeToolPairingPasses() {
		AssistantMessage withCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "list_tasks", "{}")))
			.build();
		ToolResponseMessage response = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "list_tasks", "OK")))
			.build();
		List<Message> history = new ArrayList<>(List.of(new UserMessage("列出任务"), withCall, response));

		assertThat(ResumeService.toolPairsComplete(history)).isTrue();
		assertThat(ResumeService.checkModelStepRetry(snapshot(RunSnapshot.FAILED), List.of(), history).allowed())
			.isTrue();
	}

	/** 半截正文（快照 assistantContent 非空）⇒ 拒绝：AR-05 明确不做"失效替换"。 */
	@Test
	void partialAssistantContentBlocksRetry() {
		RunSnapshot snapshot = snapshot(RunSnapshot.FAILED);
		snapshot.setAssistantContent("说到一半");

		ResumeService.ModelStepRetryCheck check = ResumeService.checkModelStepRetry(snapshot, List.of(),
				List.of(new UserMessage("你好")));

		assertThat(check.allowed()).isFalse();
		assertThat(check.failedCheck()).isEqualTo("PARTIAL_CONTENT");
	}

	/** 半截思考（快照 assistantMetadata 里有 reasoningContent）⇒ 同样拒绝。 */
	@Test
	void partialReasoningBlocksRetry() {
		RunSnapshot snapshot = snapshot(RunSnapshot.FAILED);
		snapshot.setAssistantMetadata(Map.of(ResilientChatService.REASONING_METADATA_KEY, "想到一半"));

		ResumeService.ModelStepRetryCheck check = ResumeService.checkModelStepRetry(snapshot, List.of(),
				List.of(new UserMessage("你好")));

		assertThat(check.allowed()).isFalse();
		assertThat(check.failedCheck()).isEqualTo("PARTIAL_CONTENT");
	}

	/** 历史里有被停止的半截 assistant（{@code stopped} 标记）⇒ 拒绝。 */
	@Test
	void stoppedAssistantInHistoryBlocksRetry() {
		AssistantMessage stopped = AssistantMessage.builder()
			.content("被停在这里")
			.properties(Map.of(ResilientChatService.STOPPED_KEY, true))
			.build();

		ResumeService.ModelStepRetryCheck check = ResumeService.checkModelStepRetry(snapshot(RunSnapshot.FAILED),
				List.of(), List.of(new UserMessage("你好"), stopped));

		assertThat(check.allowed()).isFalse();
		assertThat(check.failedCheck()).isEqualTo("PARTIAL_CONTENT");
	}

	/**
	 * <b>隐藏思考不阻断仅模型步重跑</b>（本批收口）：思考展示关闭时，快照里的
	 * {@code reasoningContent} 从未上屏，不构成"用户可见的半截产出"；展示开启时仍按旧口径拒绝
	 * （用户已经看到半截思考，重跑会叠出第二段）。
	 */
	@Test
	void hiddenReasoningDoesNotBlockModelStepRetryWhileDisplayedReasoningStillDoes() {
		RunSnapshot hidden = snapshot(RunSnapshot.FAILED);
		hidden.setAssistantMetadata(Map.of(ResilientChatService.REASONING_METADATA_KEY, "想到一半（未上屏）"));
		List<Message> history = List.of(new UserMessage("你好"));

		ResumeService.ModelStepRetryCheck allowed = ResumeService.checkModelStepRetry(hidden, List.of(), history,
				false);
		assertThat(allowed.allowed()).as("隐藏思考不阻断重跑").isTrue();
		assertThat(allowed.failedCheck()).isNull();

		ResumeService.ModelStepRetryCheck denied = ResumeService.checkModelStepRetry(hidden, List.of(), history,
				true);
		assertThat(denied.allowed()).as("已展示的思考仍是半截产出").isFalse();
		assertThat(denied.failedCheck()).isEqualTo("PARTIAL_CONTENT");
	}

	private static RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-model-step-retry");
		snapshot.setStatus(status);
		snapshot.setModelId("test-model");
		snapshot.setMessageJson(List.of());
		return snapshot;
	}
}
