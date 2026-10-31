package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code create_task} 的通用收尾纠正：tasks 页、无任务号、{@code create_task} 已披露、本 run 既没有
 * 待决/台账里的 {@code create_task}、又已经成功读过配置定义时，模型正文 STOP 后补一次纠正。
 *
 * <p>事实源是<b>台账</b>（{@code list_config_defs}/{@code get_config_def} 是后端只读工具，不落 pendings），
 * 不读原话措辞；纠正指令是条件式的（创建意图才调工具，只查询则直接答复，不自动创建）。
 * 标记与前缀按 run 级共存，但只保留<b>最新一次</b>纠正的前缀（创建/导出共用同一段历史）。
 */
class CreateTaskWrapUpCorrectionTest {

	private static final String RUN_ID = "run-create-correction";

	private static final String RUN_GET = "run-create-correction-get";

	private static final String RUN_FAILED = "run-create-correction-failed";

	private static final String RUN_LEDGER_CREATE = "run-create-correction-ledger-create";

	private static final String RUN_PLAIN = "run-create-correction-plain";

	private static final String RUN_UNDISCLOSED = "run-create-correction-undisclosed";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		seedRun(RUN_ID);
		for (String runId : List.of(RUN_GET, RUN_FAILED, RUN_LEDGER_CREATE, RUN_PLAIN, RUN_UNDISCLOSED)) {
			seedRun(runId);
		}
		ToolCallback createTask = mock(ToolCallback.class);
		ToolDefinition definition = mock(ToolDefinition.class);
		when(definition.name()).thenReturn(AiToolNames.CREATE_TASK);
		when(createTask.getToolDefinition()).thenReturn(definition);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of(createTask));
		this.service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class), this.codec,
				this.store, mock(RunRegistry.class), this.toolRegistry, mock(ContextBuilder.class),
				mock(AiSessionService.class), new AiProperties(), mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), null);
	}

	// ── ① 只有定义读取（只读后端工具在台账里）⇒ 触发 ────────────────────────

	@Test
	void ledgerOnlyDefinitionLookupsTriggerTheCorrection() {
		ledger(RUN_ID, "call-list-defs", AiToolNames.LIST_CONFIG_DEFS, ToolOutcome.OK);
		assertThat(due(RUN_ID, tasksPage())).as("list_config_defs 成功 ⇒ 模型可能只列配置，补一次纠正").isTrue();

		ledger(RUN_GET, "call-get-def", AiToolNames.GET_CONFIG_DEF, ToolOutcome.OK);
		assertThat(due(RUN_GET, tasksPage())).as("get_config_def 成功同样算读过定义").isTrue();

		ledger(RUN_FAILED, "call-list-defs-failed", AiToolNames.LIST_CONFIG_DEFS, ToolOutcome.FAILED);
		assertThat(due(RUN_FAILED, tasksPage())).as("失败的定义读取不是事实 ⇒ 不纠正").isFalse();
	}

	// ── ② pending / 台账里已有 create_task ⇒ 不触发 ─────────────────────────

	@Test
	void createTaskInPendingsOrLedgerSuppressesTheCorrection() {
		ledger(RUN_ID, "call-list-defs", AiToolNames.LIST_CONFIG_DEFS, ToolOutcome.OK);
		this.store.putPending(RUN_ID, pending("call-create", AiToolNames.CREATE_TASK,
				PendingToolCall.PENDING));
		assertThat(due(RUN_ID, tasksPage())).as("已有待决 create_task ⇒ 不重复纠正").isFalse();

		ledger(RUN_LEDGER_CREATE, "call-list-defs-2", AiToolNames.LIST_CONFIG_DEFS, ToolOutcome.OK);
		ledger(RUN_LEDGER_CREATE, "call-create-done", AiToolNames.CREATE_TASK, ToolOutcome.OK);
		assertThat(due(RUN_LEDGER_CREATE, tasksPage()))
			.as("台账已有 create_task ⇒ 不是缺失动作").isFalse();
	}

	// ── ③ 普通 tasks 页：没有定义读取 ⇒ 不触发 ──────────────────────────────

	@Test
	void plainTasksPageWithoutDefinitionReadsNeverTriggers() {
		ledger(RUN_PLAIN, "call-list-tasks", AiToolNames.LIST_TASKS, ToolOutcome.OK);
		assertThat(due(RUN_PLAIN, tasksPage())).as("只列任务、没读定义 ⇒ 不纠正").isFalse();

		ledger(RUN_ID, "call-list-defs", AiToolNames.LIST_CONFIG_DEFS, ToolOutcome.OK);
		AiContext exportPage = AiContext.of("export", "EXPORT", "QUERY_COND", 42L, "ACTIVE", Map.of());
		RunSnapshot moved = this.store.get(RUN_ID);
		moved.setContext(exportPage.toMap());
		this.store.save(moved);
		assertThat(due(RUN_ID, exportPage)).as("不在 tasks 页 ⇒ 不纠正").isFalse();
	}

	@Test
	void undisclosedCreateTaskNeverTriggers() {
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		ledger(RUN_UNDISCLOSED, "call-list-defs", AiToolNames.LIST_CONFIG_DEFS, ToolOutcome.OK);

		assertThat(due(RUN_UNDISCLOSED, tasksPage())).as("create_task 未披露 ⇒ 不纠正").isFalse();
	}

	// ── ④ 每 run 只一次：marker 已存在返回 false，且不改写前缀 ───────────────

	@Test
	void correctionIsMarkedAtMostOncePerRun() {
		Boolean first = mark("第一段", ResilientChatService.CREATE_TASK_CORRECTION_MARKER,
				ResilientChatService.CREATE_TASK_CORRECTION_PREFIX_KEY);

		assertThat(first).isTrue();
		assertThat(this.service.correctionAlreadyUsed(RUN_ID, ResilientChatService.CREATE_TASK_CORRECTION_MARKER))
			.isTrue();

		Boolean second = mark("第二段", ResilientChatService.CREATE_TASK_CORRECTION_MARKER,
				ResilientChatService.CREATE_TASK_CORRECTION_PREFIX_KEY);

		assertThat(second).as("marker 已存在 ⇒ 返回 false，防重复纠正").isFalse();
		assertThat(this.service.correctionPrefix(RUN_ID)).as("重复标记不得改写前缀").isEqualTo("第一段");
	}

	// ── ⑤ 创建/导出纠正不串、前缀只留最新 ──────────────────────────────────

	@Test
	void createAndExportCorrectionsStayDisjointAndKeepOnlyTheLatestPrefix() {
		String create = ResilientChatService.createTaskWrapUpCorrection();
		String export = ResilientChatService.exportWrapUpCorrection(42L);

		assertThat(create).as("创建纠正只谈 create_task，条件式、不自动创建").contains("create_task")
			.contains("如果用户只是查询配置而不要求新建，直接给出查询结果")
			.contains("不得自动批准创建")
			.doesNotContain("start_export");
		assertThat(export).as("导出纠正只谈 start_export").contains("start_export").doesNotContain("create_task");
		assertThat(SyntheticMessages.isSynthetic(new UserMessage(create))).isTrue();
		assertThat(SyntheticMessages.isSynthetic(new UserMessage(export))).isTrue();

		assertThat(mark("导出第一段", ResilientChatService.EXPORT_CORRECTION_MARKER,
				ResilientChatService.EXPORT_CORRECTION_PREFIX_KEY))
			.isTrue();
		assertThat(this.service.correctionPrefix(RUN_ID)).isEqualTo("导出第一段");

		assertThat(mark("创建第二段", ResilientChatService.CREATE_TASK_CORRECTION_MARKER,
				ResilientChatService.CREATE_TASK_CORRECTION_PREFIX_KEY))
			.isTrue();

		assertThat(this.service.correctionPrefix(RUN_ID)).as("只保留最新一次纠正的前缀").isEqualTo("创建第二段");
		assertThat(this.service.correctionPrefix(this.store.get(RUN_ID))).isEqualTo("创建第二段");
		Map<String, Object> extra = AiContext.fromMap(this.store.get(RUN_ID).getContext()).getExtra();
		assertThat(extra).doesNotContainKey(ResilientChatService.EXPORT_CORRECTION_PREFIX_KEY)
			.containsEntry(ResilientChatService.CREATE_TASK_CORRECTION_PREFIX_KEY, "创建第二段");
		assertThat(this.service.correctionAlreadyUsed(RUN_ID, ResilientChatService.EXPORT_CORRECTION_MARKER))
			.isTrue();
		assertThat(this.service.correctionAlreadyUsed(RUN_ID, ResilientChatService.CREATE_TASK_CORRECTION_MARKER))
			.isTrue();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private boolean due(String runId, AiContext context) {
		Boolean due = ReflectionTestUtils.invokeMethod(this.service, "createTaskWrapUpCorrectionDue", runId,
				context);
		return Boolean.TRUE.equals(due);
	}

	private Boolean mark(String prefix, String marker, String prefixKey) {
		return ReflectionTestUtils.invokeMethod(this.service, "markCorrectionUsed", RUN_ID, prefix, marker,
				prefixKey);
	}

	private void seedRun(String runId) {
		RunSnapshot snapshot = this.store.seedRun(runId, "s-" + runId);
		snapshot.setContext(tasksPage().toMap());
		this.store.save(snapshot);
	}

	private void ledger(String runId, String toolCallId, String name, ToolOutcome outcome) {
		this.store.appendLedger(runId, pending(toolCallId, name, PendingToolCall.EXECUTED, outcome), "ok");
	}

	private static PendingToolCall pending(String toolCallId, String name, String status) {
		return pending(toolCallId, name, status, ToolOutcome.OK);
	}

	private static PendingToolCall pending(String toolCallId, String name, String status, ToolOutcome outcome) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(name);
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(status);
		pending.setOutcome(outcome.wire());
		pending.setArguments("{}");
		return pending;
	}

	private static AiContext tasksPage() {
		return AiContext.of("tasks", null, null, null, null, Map.of());
	}

}
