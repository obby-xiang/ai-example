package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.DefinitionSelectionForm;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>已知信息跨"切标签页/导航"的续跑交棒</b>：用户在任务中心说"导出CNY货币字典"，模型建任务并导航到
 * 导出向导后，宿主<b>不得</b>再弹配置选择表单 —— 因为这句话本身就已经给出了配置与条件。
 *
 * <h2>被钉住的缺陷</h2>
 * 交棒前的槽位重算 {@code taskSlotResolver.enrich(context, "", history)} 用<b>空串</b>当"本轮用户文本"：
 * 而新任务的向导页在首次导航后会上报"已同步的空勾选"（{@code selectedDefs=[]} +
 * {@code selectionSynced=true}），这是<b>权威空</b>。当前文本为空 ⇒ 解析结果是"本轮没提选择" ⇒
 * 回落到权威空 ⇒ 用户刚说过的 CURRENCY 与 code=CNY 被一并抹掉，宿主误判"配置未知"而弹表单。
 *
 * <h2>本用例锁的口径</h2>
 * <ol>
 * <li>本轮原文<b>明确</b>给出的配置/条件按"当前用户明确值"参与重算：{@code defCodes=[CURRENCY]}、
 * {@code CURRENCY.code=CNY} 原样进入交棒槽位，配置表单不弹；</li>
 * <li>用户后来<b>手动清空</b>且最后一句只是"继续"时，权威空不得被历史选择复活（仍按缺失处理）；</li>
 * <li>只有"当前文本"能压过权威空 —— 用整段历史拼接当当前文本会复活旧选择，故只取<b>最近一条真实
 * 用户原文</b>（跳过合成消息）。</li>
 * </ol>
 *
 * <p><b>证红</b>：交棒前仍用空串当当前文本 ⇒ 第 1 条红（误弹配置表单、槽位丢失）；把整段历史拼成
 * 当前文本 ⇒ 第 2 条红（清空后的旧选择复活）。
 */
class KnownInfoHandoffResumeTest {

	private static final String RUN_ID = "run-known-handoff";

	private static final String SESSION_ID = "s-known-handoff";

	private static final long TASK_ID = 42L;

	private static final String CALL_CREATE = "call-create";

	private static final String CALL_NAVIGATE = "call-navigate";

	private static final String DEF_FORM = DefinitionSelectionForm.toolCallId(TASK_ID);

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final TaskService taskService = mock(TaskService.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	/** 目录里"货币字典" = CURRENCY，主键 code 的取值集合含 CNY（"导出CNY货币字典"才可解析成条件）。 */
	private final DefinitionCatalog catalog = () -> new ArrayList<>(List.of(new SlotDefinition("CURRENCY",
			"货币字典", List.of(new SlotDefinition.SlotField("code", "代码", true, List.of("CNY", "USD"))))));

	private AnnotationConfigApplicationContext registryContext;

	private ToolRegistry toolRegistry;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.registryContext = new AnnotationConfigApplicationContext();
		this.registryContext.registerBean(WizardTools.class);
		this.registryContext.registerBean(ToolRegistry.class);
		this.registryContext.refresh();
		this.toolRegistry = this.registryContext.getBean(ToolRegistry.class);

		when(this.taskService.findByIdRaw(any())).thenAnswer(invocation -> Optional.ofNullable(this.taskState.get())
			.filter(task -> task.getId() != null && task.getId().equals(invocation.getArgument(0))));
		when(this.taskService.findById(any())).thenAnswer(invocation -> this.taskState.get() != null
				&& this.taskState.get().getId() != null
				&& this.taskState.get().getId().equals(invocation.getArgument(0)) ? this.taskState.get() : null);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenAnswer(invocation -> invocation.<PendingToolCall>getArgument(2).getResultText());
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.chatService.systemPromptText(any())).thenReturn(SYSTEM_TEXT);
		when(this.chatService.workspaceStateText(any())).thenReturn("## 当前工作区状态\n<workspace_state>\n"
				+ "当前步骤: SELECT_DEFS\n</workspace_state>\n");
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenAnswer(invocation -> new Prompt(invocation.<List<Message>>getArgument(3),
					ToolCallingChatOptions.builder().build()));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
		this.service.setContextRefresher(new RunContextRefresher(this.taskService));
		this.service.setTaskSlotResolver(new TaskSlotResolver(this.catalog, this.taskService, this.mapper));
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of(new GenerativeFormGuard(new ObjectMapper())));
		this.service.setDefinitionSelectionForm(new DefinitionSelectionForm(this.store, this.registry, gate,
				this.codec, this.properties, this.catalog));
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── ① "导出CNY货币字典" → 导航到选择步：不弹表，配置与条件都在槽位里 ──────

	@Test
	void knownDefinitionAndConditionFromTheUserMessageSurviveTheNavigationHandoff() {
		this.taskState.set(task(TASK_ID, "SELECT_DEFS"));
		seedCreatedTask();
		seedSuspendedRun(List.of(navigateEntry()), List.of("导出CNY货币字典"),
				wizardContext(emptySyncedSelection()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).as("用户本轮已明确配置与条件 ⇒ 不得弹配置选择表单")
			.doesNotContainEntry("reason", "DEFINITION_FORM_PARKED");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("宿主一张表都不该挂")
			.noneMatch(pending -> DEF_FORM.equals(pending.getToolCallId()));

		TaskSlots slots = TaskSlots.fromExtra(contextOfDriveResumed().getExtra());
		assertThat(slots.defCodes()).as("本轮原话里点名的配置项必须原样进入下一跳").containsExactly("CURRENCY");
		assertThat(slots.conditions()).as("同一句里的 code=CNY 也是已知条件")
			.extracting(TaskSlots.ConditionSlot::defCode, TaskSlots.ConditionSlot::fieldCode,
					TaskSlots.ConditionSlot::value)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("CURRENCY", "code", "CNY"));
	}

	// ── ② 手动清空后最后一句只是"继续"：权威空不复活历史选择 ────────────────

	@Test
	void manualClearStaysClearedWhenTheLastMessageIsJustContinue() {
		this.taskState.set(task(TASK_ID, "SELECT_DEFS"));
		seedCreatedTask();
		// 历史里有"导出CNY货币字典"，但用户随后清空了勾选、最后一句只是"继续"
		seedSuspendedRun(List.of(navigateEntry()), List.of("导出CNY货币字典", "继续"),
				wizardContext(emptySyncedSelection()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).as("配置此刻确实是空的（用户清空过）⇒ 宿主照常给选择表单")
			.containsEntry("reason", "DEFINITION_FORM_PARKED");
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		PendingToolCall parked = this.store.pending(RUN_ID, DEF_FORM);
		assertThat(parked).isNotNull();
		assertThat(parked.getArguments()).as("清空后不得把历史里的 CURRENCY 预填回表单")
			.doesNotContain("defaultValue");
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n当前步骤: SELECT_DEFS\n"
			+ "</workspace_state>\n";

	private AiContext contextOfDriveResumed() {
		ArgumentCaptor<AiContext> nextHop = ArgumentCaptor.forClass(AiContext.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).driveResumed(eq(RUN_ID), eq(SESSION_ID),
				nextHop.capture(), any(), eq(this.emitter));
		return nextHop.getValue();
	}

	private void seedCreatedTask() {
		PendingToolCall createTask = new PendingToolCall();
		createTask.setToolCallId(CALL_CREATE);
		createTask.setName(RunContextRefresher.TOOL_CREATE_TASK);
		createTask.setKind(PendingToolCall.KIND_CONFIRM);
		createTask.setStatus(PendingToolCall.EXECUTED);
		createTask.setOutcome(ToolOutcome.OK.wire());
		createTask.setExecuted(true);
		createTask.setResultText("已创建导出任务 #" + TASK_ID + "（当前步骤：SELECT_DEFS）");
		this.store.appendLedger(RUN_ID, createTask, createTask.getResultText());
	}

	private void seedSuspendedRun(List<PendingToolCall> inFlight, List<String> userMessages, AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(context.toMap());
		List<Message> history = new ArrayList<>();
		history.add(new SystemMessage(SYSTEM_TEXT));
		for (String userMessage : userMessages) {
			history.add(new UserMessage(userMessage));
		}
		snapshot.setMessageJson(this.codec.serializeAll(history));
		snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
		this.store.save(snapshot);
		for (PendingToolCall pending : inFlight) {
			this.store.putPending(RUN_ID, pending);
		}
	}

	private static PendingToolCall navigateEntry() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_NAVIGATE);
		pending.setName(RunContextRefresher.TOOL_NAVIGATE);
		pending.setType("function");
		pending.setArguments("{\"page\":\"export\",\"taskId\":" + TASK_ID + "}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("前端已执行：navigate_to");
		pending.setReq(Boolean.TRUE);
		return pending;
	}

	/** 向导页已同步的"空勾选"（= 权威空；这正是把空文本当当前文本时会误判的现场）。 */
	private static Map<String, Object> emptySyncedSelection() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("pageId", "export");
		extra.put("selectedDefs", List.of());
		extra.put("selectionSynced", true);
		return extra;
	}

	private static AiContext wizardContext(Map<String, Object> extra) {
		return AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", extra);
	}

	private static Task task(Long id, String step) {
		Task task = new Task();
		task.setId(id);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep(step);
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("任务 " + id);
		return task;
	}

	/** 只声明本用例关心的披露面（与 {@code AiTools} 的真实注解同源）：工具体永不执行。 */
	static class WizardTools {

		@Tool(name = "navigate_to", description = "驱动前端页面导航")
		@ToolScope("*")
		@ToolRisk(ToolMeta.RiskLevel.READ)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String navigateTo(@ToolParam(description = "目标页面") String page,
				@ToolParam(description = "任务ID", required = false) Long taskId) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "create_task", description = "创建任务（确认门）")
		@ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.W)
		public String createTask(@ToolParam(description = "任务类型") String type,
				@ToolParam(description = "标题", required = false) String title) {
			return "不应被执行";
		}

		@Tool(name = "select_definitions", description = "勾选配置项")
		@ToolScope({ "task:EXPORT/SELECT_DEFS", "task:IMPORT/UPLOAD" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String selectDefinitions(@ToolParam(description = "配置定义编码列表") String[] codes) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "set_condition", description = "设置查询条件")
		@ToolScope({ "task:EXPORT/QUERY_COND" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String setCondition(@ToolParam(description = "配置定义编码") String defCode,
				@ToolParam(description = "字段") String field, @ToolParam(description = "值") String value) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "confirm_step", description = "推进向导步骤")
		@ToolScope({ "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
			return "前端哨兵桩（永不执行）";
		}

	}

}
