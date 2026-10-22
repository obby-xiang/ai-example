package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 批G · S5：取消时**补裁决帧** —— 让刷新/重放后的卡不悬空。
 *
 * <p>
 * 现场（实证 S3c 归档 seq52-56）：{@code suspended → tool_start → confirm_request → message_end →
 * done{cancelled:true}}，<b>没有</b> {@code confirm_decision}。{@code RunStore.cancelPendings}
 * 只改 Redis 条目、不发帧 ⇒ 前端既收不到收口帧，重挂也回放不出结局，确认卡永远「待确认」。
 *
 * <p>
 * 口径：CONFIRM 条目发 {@code confirm_decision(decision=cancel, status=CANCELLED)}；FRONTEND
 * 条目发 {@code frontend_tool_result(ok=false, status=CANCELLED)}（前端两表都把 CANCELLED 译成
 * 段终态 cancelled）。已终态条目不补帧（first-wins，避免把已执行的卡改写成取消）。
 */
class CancelDecisionFramesTest {

	private static final String RUN_ID = "run-cancel-frames";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	@Test
	void pendingConfirmAndFrontendEntriesEmitCancelFrames() {
		this.store.putPending(RUN_ID, pending("c-confirm", PendingToolCall.KIND_CONFIRM));
		this.store.putPending(RUN_ID, pending("c-frontend", PendingToolCall.KIND_FRONTEND));

		ResilientChatService.emitCancelOutcomes(RUN_ID, this.store, this.out);

		ArgumentCaptor<PendingToolCall> decisions = ArgumentCaptor.forClass(PendingToolCall.class);
		verify(this.out).confirmDecision(decisions.capture(), eq("cancel"), eq("RUN_CANCELLED"), anyLong());
		assertThat(decisions.getValue().getToolCallId()).isEqualTo("c-confirm");
		assertThat(decisions.getValue().getStatus()).isEqualTo(PendingToolCall.CANCELLED);

		ArgumentCaptor<PendingToolCall> frontendResults = ArgumentCaptor.forClass(PendingToolCall.class);
		verify(this.out).frontendToolResult(frontendResults.capture(), eq(false), anyString());
		assertThat(frontendResults.getValue().getToolCallId()).isEqualTo("c-frontend");
		assertThat(frontendResults.getValue().getStatus()).isEqualTo(PendingToolCall.CANCELLED);

		// 条目本身照样落 CANCELLED（帧与存储同一事实）
		List<PendingToolCall> pendings = this.store.pendings(RUN_ID);
		assertThat(pendings).allMatch(entry -> PendingToolCall.CANCELLED.equals(entry.getStatus()));
	}

	@Test
	void alreadySettledEntriesDoNotEmitFrames() {
		PendingToolCall executed = pending("c-executed", PendingToolCall.KIND_CONFIRM);
		executed.setStatus(PendingToolCall.EXECUTED);
		this.store.putPending(RUN_ID, executed);

		ResilientChatService.emitCancelOutcomes(RUN_ID, this.store, this.out);

		verify(this.out, never()).confirmDecision(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyLong());
		verify(this.out, never()).frontendToolResult(org.mockito.ArgumentMatchers.any(), eq(false), anyString());
		assertThat(this.store.pendings(RUN_ID).get(0).getStatus()).isEqualTo(PendingToolCall.EXECUTED);
	}

	private static PendingToolCall pending(String toolCallId, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(PendingToolCall.KIND_CONFIRM.equals(kind) ? "start_export" : "generative_form");
		pending.setKind(kind);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}
}
