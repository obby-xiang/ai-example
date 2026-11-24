package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返修（Important 3）：{@code /confirm} 与 {@code /frontend-tool-result} 携带的<b>最新工作区上下文</b>
 * 必须在续跑前写进快照，且走与 {@code /chat} 同一套 {@code ContextFieldGate} 消毒。
 *
 * <p>为什么需要：挂起期间用户可以在向导里手动改步骤/勾选，续跑却只读挂起那一刻的快照 ——
 * 模型按旧步骤/旧勾选继续（"提示词承诺了不存在的收敛"）。这条链上没有任何别的写口能拿到页面事实。
 */
class AiControllerWorkspaceContextTest {

	private static final String RUN = "r-ws-ctx";

	private static final String CALL = "c-ws-ctx";

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final RunStore runStore = mock(RunStore.class);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiController controller = controller();

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller).build();

	@Test
	void confirmPersistsSanitizedWorkspaceContextBeforeDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		AiContext stored = AiContext.fromMap(snapshot.getContext());
		assertThat(stored.getStep()).as("挂起期手动推进到 QUERY_COND").isEqualTo("QUERY_COND");
		assertThat(stored.getPage()).isEqualTo("export");
		assertThat(stored.getTaskId()).isEqualTo(42L);
		assertThat(stored.getExtra().get("selectedDefs")).isEqualTo(List.of());
		assertThat(stored.getExtra().get("selectionSynced")).as("保持布尔原值").isEqualTo(true);
		assertThat(stored.getExtra()).as("未知键（旧 taskSlots）被闸门丢弃，不得覆盖页面事实")
			.doesNotContainKey("taskSlots");
		verify(this.runStore).save(snapshot);
	}

	@Test
	void frontendToolResultPersistsWorkspaceContextBeforeDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_FRONTEND);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any(), any(), any()))
			.thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new FrontendToolResultRequest(RUN, CALL, "ok", null,
					false, null, null, null, context()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		AiContext stored = AiContext.fromMap(snapshot.getContext());
		assertThat(stored.getStep()).isEqualTo("QUERY_COND");
		assertThat(stored.getExtra().get("selectedDefs")).isEqualTo(List.of());
		verify(this.runStore).save(snapshot);
	}

	/** 旧客户端不带 context：快照一个字都不动（兼容方向 = 可选字段，缺省不改行为）。 */
	@Test
	void legacyRequestWithoutContextDoesNotTouchTheSnapshot() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null))))
			.andExpect(status().isOk());

		assertThat(AiContext.fromMap(snapshot.getContext()).getStep()).isEqualTo("SELECT_DEFS");
		verify(this.runStore, never()).save(any(RunSnapshot.class));
	}

	// ── 原任务非空时，其它任务的挂起上下文不得合并或提交 ─────────────────────

	@Test
	void confirmRejectsOtherTaskContextWithoutMergingOrDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE",
				serverExtra()).toMap());
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, otherTaskContext()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"))
			.andExpect(jsonPath("$.message").value("The task has changed. Return to the original task and try again."));

		assertThat(AiContext.fromMap(snapshot.getContext()).getTaskId()).isEqualTo(42L);
		assertThat(AiContext.fromMap(snapshot.getContext()).getExtra().get("selectedDefs"))
			.isEqualTo(List.of("CURRENCY"));
		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void frontendToolResultRejectsOtherTaskContextWithoutMergingOrDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE",
				serverExtra()).toMap());
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_FRONTEND);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any(), any(), any()))
			.thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new FrontendToolResultRequest(RUN, CALL, "ok", null,
					false, null, null, null, otherTaskContext()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitFrontendResult(eq(RUN), eq(CALL), eq("ok"), any(), eq(false), any(), any(), any());
	}

	@Test
	void taskListContextIsAlsoRejectedWhenOriginalTaskExists() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));
		AiChatRequest.Context switched = new AiChatRequest.Context("tasks", null, null, null, null,
				Map.of("pageId", "tasks", "selectedDefs", List.of(), "selectionSynced", false));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, switched))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		assertThat(AiContext.fromMap(snapshot.getContext()).getTaskId()).isEqualTo(42L);
		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void nullSnapshotDoesNotHidePendingTaskEvidenceWhenRequestTargetsAnotherTask() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", null, null,
				Map.of("pageId", "export", "selectedDefs", List.of("CURRENCY"))).toMap());
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":42}");
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, otherTaskContext()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		assertThat(AiContext.fromMap(snapshot.getContext()).getTaskId()).isNull();
		assertThat(AiContext.fromMap(snapshot.getContext()).getExtra().get("selectedDefs"))
			.isEqualTo(List.of("CURRENCY"));
		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void nullSnapshotWithPendingSameTaskEvidenceAllowsWorkspaceMerge() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", null, null,
				Map.of("pageId", "export")).toMap());
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":42}");
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isOk());

		assertThat(AiContext.fromMap(snapshot.getContext()).getTaskId()).isEqualTo(42L);
		verify(this.runStore).save(snapshot);
	}

	@Test
	void nullSnapshotWithoutTaskOrCreateEvidenceFailsClosed() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", null, null,
				Map.of("pageId", "export")).toMap());
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, otherTaskContext()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	/** 相邻：快照原本没有任务绑定 ⇒ 允许采纳客户端任务绑定（新建任务后的首个确认）。 */
	@Test
	void clientTaskBindingIsAdoptedOnlyWhenSnapshotHasNone() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", null, null,
				Map.of("pageId", "export")).toMap());
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		pending.setName("create_task");
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("pageId", "export");
		extra.put("selectedDefs", List.of("CURRENCY"));
		extra.put("selectionSynced", true);
		AiChatRequest.Context bound = new AiChatRequest.Context("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE",
				extra);

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, bound))))
			.andExpect(status().isOk());

		AiContext stored = AiContext.fromMap(snapshot.getContext());
		assertThat(stored.getTaskId()).isEqualTo(42L);
		assertThat(stored.getTaskType()).isEqualTo("EXPORT");
		assertThat(stored.getTaskStatus()).isEqualTo("ACTIVE");
	}

	// ── I-2：只在"决策会被受理"的前置条件下写上下文 ─────────────────────────

	/** 未知 toolCallId ⇒ 409，且<b>一个字节都不写</b>（入口拒绝 = 零副作用）。 */
	@Test
	void unknownToolCallDoesNotTouchTheSnapshot() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(null);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(eq(RUN), eq(CALL), eq(true), any()))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.NOT_FOUND, null, false, null));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isConflict());

		verify(this.runStore, never()).save(any(RunSnapshot.class));
	}

	/** DUPLICATE（条目已被处理过，非 PENDING）⇒ 409，且不写。 */
	@Test
	void duplicateDecisionDoesNotTouchTheSnapshot() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall processed = pending(PendingToolCall.KIND_CONFIRM);
		processed.setStatus(PendingToolCall.APPROVED);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(processed);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null))
			.thenReturn(new ConfirmGate.Submission(ConfirmGate.Outcome.DUPLICATE, processed, false, null));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isConflict());

		verify(this.runStore, never()).save(any(RunSnapshot.class));
	}

	/** 错 kind（入口预读命中另一通道的条目）⇒ 409，且不写。 */
	@Test
	void wrongKindEntryDoesNotTouchTheSnapshot() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending(PendingToolCall.KIND_FRONTEND));
		when(this.runStore.instanceId()).thenReturn("inst-a");

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isConflict());

		verify(this.runStore, never()).save(any(RunSnapshot.class));
	}

	/**
	 * RUNNING（或在跑轮）⇒ 不写：本功能只服务挂起期，给在跑轮换上下文会与驱动侧抢同一份快照，
	 * 且可能回退 status/inFlight 等字段（整对象写、后写者赢）。
	 */
	@Test
	void runningRunIsNotRewrittenByWorkspaceContext() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		snapshot.setStatus(RunSnapshot.RUNNING);
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.runStore.instanceId()).thenReturn("inst-a");
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isOk());

		verify(this.runStore, never()).save(any(RunSnapshot.class));
	}

	@Test
	void snapshotAndPendingTaskConflictRejectsEvenWhenRequestMatchesPending() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, otherTaskContext()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void malformedPendingArgumentsRejectInsteadOfFallingBackToSnapshot() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{bad");
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void malformedSnapshotTaskIdRejectsWithoutDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		Map<String, Object> malformed = new LinkedHashMap<>(snapshot.getContext());
		malformed.put("taskId", "not-a-number");
		snapshot.setContext(malformed);
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void snapshotReadFailureRejectsWithoutDecision() throws Exception {
		when(this.runStore.get(RUN)).thenThrow(new IllegalStateException("snapshot read failed"));
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TASK_CONTEXT_MISMATCH"));

		verify(this.runStore, never()).save(any(RunSnapshot.class));
		verify(this.gate, never()).submitDecision(eq(RUN), eq(CALL), eq(true), any());
	}

	@Test
	void snapshotAndPendingSameTaskEvidenceAllowsWorkspaceMerge() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":42}");
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		verify(this.runStore).save(snapshot);
		verify(this.gate).submitDecision(RUN, CALL, true, null);
	}

	@Test
	void snapshotSaveFailureStillAllowsDecision() throws Exception {
		RunSnapshot snapshot = suspendedSnapshot();
		when(this.runStore.get(RUN)).thenReturn(snapshot);
		PendingToolCall pending = pending(PendingToolCall.KIND_CONFIRM);
		when(this.runStore.pendingLenient(RUN, CALL)).thenReturn(pending);
		when(this.gate.submitDecision(RUN, CALL, true, null)).thenReturn(accepted(pending));
		org.mockito.Mockito.doThrow(new IllegalStateException("snapshot save failed")).when(this.runStore).save(snapshot);

		mockMvc.perform(post("/api/ai/confirm").contentType(MediaType.APPLICATION_JSON)
			.content(this.mapper.writeValueAsString(new ConfirmRequest(RUN, CALL, true, null, context()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(true));

		verify(this.gate).submitDecision(RUN, CALL, true, null);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private static AiChatRequest.Context context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("pageId", "export");
		extra.put("selectedDefs", List.of());
		extra.put("selectionSynced", true);
		// 客户端不可能伪造服务端槽位：闸门按白名单丢弃，页面事实不得被它压掉
		extra.put("taskSlots", Map.of("defCodes", List.of("STALE")));
		return new AiChatRequest.Context("export", "EXPORT", "QUERY_COND", 42L, "ACTIVE", extra);
	}

	private static AiChatRequest.Context otherTaskContext() {
		return new AiChatRequest.Context("import", "IMPORT", "PRECHECK", 7L, "ACTIVE",
				Map.of("pageId", "import", "selectedDefs", List.of("OTHER"), "selectionSynced", false));
	}

	private static Map<String, Object> serverExtra() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("pageId", "export");
		extra.put("selectedDefs", List.of("CURRENCY"));
		extra.put("selectionSynced", true);
		extra.put("importMode", "MERGE");
		extra.put(TaskSlots.EXTRA_KEY, new TaskSlots("EXPORT", List.of("CURRENCY"), List.of()).toMap());
		return extra;
	}

	private static RunSnapshot suspendedSnapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN);
		snapshot.setSessionId("s-ws-ctx");
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE",
				Map.of("pageId", "export", "selectedDefs", List.of("CURRENCY"), "selectionSynced", true)).toMap());
		snapshot.setMessageJson(List.of());
		return snapshot;
	}

	private static PendingToolCall pending(String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL);
		pending.setName("generative_form");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(kind);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	private static ConfirmGate.Submission accepted(PendingToolCall pending) {
		return new ConfirmGate.Submission(ConfirmGate.Outcome.ACCEPTED, pending, true, null);
	}

	private AiController controller() {
		AiController built = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate,
				this.runStore, mock(RunRegistry.class), mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper,
				new AiProperties(), mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "contextFieldGate", new ContextFieldGate());
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
		ReflectionTestUtils.setField(built, "errorBodyFactory",
				new com.example.configmgr.common.ErrorBodyFactory(
						beans.getBeanProvider(org.springframework.context.MessageSource.class)));
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
