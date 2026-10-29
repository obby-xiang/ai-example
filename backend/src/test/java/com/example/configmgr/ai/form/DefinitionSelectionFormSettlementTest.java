package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宿主定义选择表单的<b>既有链路</b>：挂起 → 刷新恢复 → 提交/取消/非法回灌 → 不重复问。
 *
 * <p>全部跑在真实的外置存储替身（{@link InMemoryRunStore}）与真实 {@link ConfirmGate} 上 ——
 * 断言落在<b>真实状态与真实帧</b>（条目状态、req 标记、{@code frontend_tool_result} 帧），
 * 不是"方法被调用过"。这一批的要点是证明：宿主新增的只是"谁来发这张表"，
 * <b>挂起/取消/回灌/权限判据一个都没重写</b>。
 */
class DefinitionSelectionFormSettlementTest {

	private static final String RUN_ID = "run-def-settle";

	private static final long TASK_ID = 42L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final AiProperties properties = new AiProperties();

	private final List<SlotDefinition> definitions = new ArrayList<>(List.of(
			new SlotDefinition("CURRENCY", "币种", List.of()),
			new SlotDefinition("FX_RATE", "汇率", List.of()),
			new SlotDefinition("ROLE_DICT", "角色字典", List.of())));

	private final DefinitionCatalog catalog = () -> new ArrayList<>(this.definitions);

	private final GenerativeFormGuard guard = new GenerativeFormGuard(new ObjectMapper());

	private ConfirmGate gate;

	private DefinitionSelectionForm form;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(this.guard));
		this.form = new DefinitionSelectionForm(this.store, this.registry, this.gate, this.codec, this.properties,
				this.catalog);
		seedNewTaskRun();
	}

	// ── ① 挂起后：刷新恢复能看到这张卡（req 已置位） ─────────────────────────

	@Test
	void parkedFormIsRestorableAfterRefreshWithTheRequestMarkerSet() {
		assertThat(park()).isTrue();

		// 刷新恢复只认 req=true 的卡：宿主走下的是同一条置位路径（markRequestEmitted）
		PendingToolCall restored = onlyPending();
		assertThat(restored.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(restored.getReq()).as("请求帧已置位 ⇒ 刷新/重挂后卡片可恢复").isTrue();
		assertThat(restored.getReqAtMs()).isPositive();
		assertThat(restored.getToolCallId()).isEqualTo(DefinitionSelectionForm.toolCallId(TASK_ID));
		// 快照是合法的恢复态：SUSPENDED + in-flight 恰好这张表
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(DefinitionSelectionForm.toolCallId(TASK_ID));
	}

	// ── ② 提交：走既有 frontend-tool-result 回灌（值 = 配置项编码） ──────────

	@Test
	void submittingTheFormSettlesThroughTheExistingFrontendResultPath() {
		assertThat(park()).isTrue();
		String values = "{\"" + DefinitionSelectionForm.FIELD_KEY + "\":[\"CURRENCY\",\"ROLE_DICT\"]}";

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID,
				DefinitionSelectionForm.toolCallId(TASK_ID), values, "unit-test");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		PendingToolCall settled = onlyPending();
		assertThat(settled.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(settled.getResultText()).isEqualTo(values);
		assertThat(settled.effectiveOutcome()).isEqualTo(ToolOutcome.OK);
		// 既有结局帧（前端据此收口表单卡），值与用户勾选逐字一致
		verify(this.emitter).frontendToolResult(any(PendingToolCall.class), eq(true), eq(values));
	}

	// ── ③ 取消：走既有 cancelled=true 明确终态 ──────────────────────────────

	@Test
	void cancellingTheFormSettlesThroughTheExistingFrontendCancelPath() {
		assertThat(park()).isTrue();

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID,
				DefinitionSelectionForm.toolCallId(TASK_ID), "", "unit-test", true);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		PendingToolCall settled = onlyPending();
		assertThat(settled.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
		assertThat(settled.getCode()).isEqualTo(ToolOutcome.CODE_FRONTEND_CANCELLED);
		verify(this.emitter).frontendToolResult(any(PendingToolCall.class), eq(false), anyString());
	}

	// ── ④ 非法/空回灌：既有复核拒绝，挂起不被消费 ──────────────────────────

	@Test
	void outOfOptionsSubmissionIsRejectedAndTheFormKeepsWaiting() {
		assertThat(park()).isTrue();

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID,
				DefinitionSelectionForm.toolCallId(TASK_ID),
				"{\"" + DefinitionSelectionForm.FIELD_KEY + "\":[\"NOT_A_DEFINITION\"]}", "unit-test");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.REJECTED);
		assertThat(onlyPending().getStatus()).as("拒绝不改状态：用户修正后还能再提交").isEqualTo(
				PendingToolCall.PENDING);
		verify(this.emitter, never()).frontendToolResult(any(PendingToolCall.class), anyBoolean(), anyString());
	}

	@Test
	void emptySubmissionIsRejectedAndTheFormKeepsWaiting() {
		assertThat(park()).isTrue();

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID,
				DefinitionSelectionForm.toolCallId(TASK_ID), "{}", "unit-test");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.REJECTED);
		assertThat(onlyPending().getStatus()).isEqualTo(PendingToolCall.PENDING);
	}

	// ── ⑤ 重复触发防护：同一任务已挂过（含已决）就不再问 ────────────────────

	@Test
	void theSameTaskNeverGetsASecondFormEvenAfterTheFirstOneWasAnswered() {
		assertThat(park()).isTrue();
		this.gate.submitFrontendResult(RUN_ID, DefinitionSelectionForm.toolCallId(TASK_ID),
				"{\"" + DefinitionSelectionForm.FIELD_KEY + "\":[\"CURRENCY\"]}", "unit-test");

		// 模型可能再次导航到同一任务：宿主不得再弹一张（同一缺口只问一次）
		boolean parkedAgain = this.form.parkIfDue(RUN_ID, wizardContext(java.util.Map.of()),
				List.of(navigateTo(TASK_ID)), List.of(new UserMessage("继续")));

		assertThat(parkedAgain).isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(1);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private boolean park() {
		return this.form.parkIfDue(RUN_ID, wizardContext(Map.of()), List.of(navigateTo(TASK_ID)),
				List.of(new UserMessage("帮我新建一个导出任务")));
	}

	private PendingToolCall onlyPending() {
		List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
		assertThat(pendings).hasSize(1);
		return pendings.get(0);
	}

	private void seedNewTaskRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, "s-def-settle");
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(wizardContext(Map.of()).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(
				new org.springframework.ai.chat.messages.SystemMessage("PERSONA"),
				new UserMessage("帮我新建一个导出任务"))));
		this.store.save(snapshot);
		PendingToolCall createTask = new PendingToolCall();
		createTask.setToolCallId("call-create");
		createTask.setName(RunContextRefresher.TOOL_CREATE_TASK);
		createTask.setKind(PendingToolCall.KIND_CONFIRM);
		createTask.setStatus(PendingToolCall.EXECUTED);
		createTask.setOutcome(ToolOutcome.OK.wire());
		createTask.setExecuted(true);
		createTask.setResultText("已创建导出任务 #" + TASK_ID + "（当前步骤：SELECT_DEFS）");
		this.store.appendLedger(RUN_ID, createTask, createTask.getResultText());
	}

	private static AiContext wizardContext(Map<String, Object> extra) {
		return AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", extra);
	}

	private static PendingToolCall navigateTo(long taskId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-navigate");
		pending.setName(RunContextRefresher.TOOL_NAVIGATE);
		pending.setType("function");
		pending.setArguments("{\"page\":\"export\",\"taskId\":" + taskId + "}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("前端已执行：navigate_to");
		return pending;
	}

}
