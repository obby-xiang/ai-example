package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.DefinitionSelectionForm;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskIntent;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
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
 * <b>筛选条件由模型理解用户原话、宿主不再抢发条件表单</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 用户说"导出cny货币字典"并推进到 {@code QUERY_COND} 时，旧的宿主条件表单只看槽位里的大写候选值：
 * 小写 {@code cny} 没被槽位认成条件 ⇒ 宿主抢在模型之前挂起一张"筛选条件（可选）"文本框，模型这一拍
 * 根本没机会理解这句话。大写 {@code CNY} 则因为槽位已覆盖而不弹 —— 同一句话只因为大小写得到两种
 * 完全不同的交互。
 *
 * <h2>本批口径</h2>
 * <ol>
 * <li>宿主<b>不再</b>在 {@code QUERY_COND} 代替模型下发条件表单（首轮与续跑交棒两处入口都撤掉）；
 * 用户原话原样交棒模型，由模型决定"直接 set_condition"还是"条件确实缺失才发一次表单"；</li>
 * <li>小写/大写已给条件都不弹宿主表单；未知条件时模型自己的 {@code SET_CONDITIONS} 表单仍被放行；</li>
 * <li>模型发起的条件表单回灌空文本后照常交棒（留空 = 不设筛选，全量推进），不再补第二张；</li>
 * <li>创建导出任务且配置未知时，既有的定义选择表单不受影响（该弹的仍弹）。</li>
 * </ol>
 *
 * <p><b>证红</b>：续跑交棒点仍接宿主条件表单 ⇒ 小写与未知条件两条红（模型不被驱动、多出一张宿主表）；
 * 把定义选择表单一并撤掉 ⇒ 最后一条红。
 */
class ModelDrivenConditionCollectionResumeTest {

	private static final String RUN_ID = "run-model-cond-resume";

	private static final String SESSION_ID = "s-model-cond-resume";

	private static final long TASK_ID = 42L;

	private static final String CALL_CREATE = "call-create";

	private static final String CALL_NAVIGATE = "call-navigate";

	private static final String DEF_FORM = DefinitionSelectionForm.toolCallId(TASK_ID);

	/** 模型自己下发的条件表单（与 {@code GenerativeFormRules} 的单框形状一致）。 */
	private static final String CONDITION_FORM_JSON = "{\"form\":{\"scenario\":\"FILTER\","
			+ "\"purpose\":\"SET_CONDITIONS\",\"fields\":[{\"key\":\"conditions\",\"label\":\"筛选条件\","
			+ "\"type\":\"text\",\"required\":false,\"variant\":\"longText\"}]}}";

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

	private final DefinitionCatalog catalog = () -> new ArrayList<>(List.of(
			new SlotDefinition("CURRENCY", "货币字典",
					List.of(new SlotDefinition.SlotField("code", "代码", true, List.of("CNY", "USD")))),
			new SlotDefinition("FX_RATE", "汇率", List.of())));

	private AnnotationConfigApplicationContext registryContext;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.registryContext = new AnnotationConfigApplicationContext();
		this.registryContext.registerBean(WizardTools.class);
		this.registryContext.registerBean(ToolRegistry.class);
		this.registryContext.refresh();
		ToolRegistry toolRegistry = this.registryContext.getBean(ToolRegistry.class);

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
		when(this.chatService.workspaceStateText(any())).thenAnswer(invocation -> {
			AiContext context = invocation.getArgument(0);
			return "## 当前工作区状态\n<workspace_state>\n任务: #" + context.getTaskId()
					+ "\n当前步骤: " + context.getStep() + "\n</workspace_state>\n";
		});
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
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(new GenerativeFormGuard(this.mapper)));
		this.service.setDefinitionSelectionForm(new DefinitionSelectionForm(this.store, this.registry, gate,
				this.codec, this.properties, this.catalog));
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── ① 小写条件：宿主不得抢发条件表单，原话交棒模型 ─────────────────────

	@Test
	void lowercaseConditionInTheUserMessageIsLeftToTheModel() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		seedSuspendedRun(List.of(), List.of("导出cny货币字典"),
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).as("宿主不再代替模型下发条件表单").doesNotContainKey("reason");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("一张 generative_form 都不该由宿主挂")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));

		List<Message> handed = historyOfDriveResumed();
		assertThat(TaskIntent.lastRealUserText(handed)).as("模型看到的是用户原话（大小写原样）")
			.isEqualTo("导出cny货币字典");
		TaskSlots slots = TaskSlots.fromExtra(contextOfDriveResumed().getExtra());
		assertThat(slots.defCodes()).as("配置项已知：币种 = CURRENCY").containsExactly("CURRENCY");
		assertThat(slots.conditions()).as("小写 cny 不靠宿主写死解析，留给模型理解").isEmpty();
	}

	// ── ② 大写已知条件：同样不弹宿主表单，槽位保留已知条件 ──────────────────

	@Test
	void uppercaseKnownConditionDoesNotTriggerAHostForm() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		seedSuspendedRun(List.of(), List.of("导出CNY货币字典"),
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).doesNotContainKey("reason");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID)).noneMatch(
				pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		TaskSlots slots = TaskSlots.fromExtra(contextOfDriveResumed().getExtra());
		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions())
			.extracting(TaskSlots.ConditionSlot::defCode, TaskSlots.ConditionSlot::fieldCode,
					TaskSlots.ConditionSlot::value)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("CURRENCY", "code", "CNY"));
	}

	// ── ③ 未知条件：仍由模型发那张表单，宿主不抢先 ──────────────────────────

	@Test
	void unknownConditionStillLetsTheModelAskWithTheConditionForm() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		seedSuspendedRun(List.of(), List.of("导出 CURRENCY 币种", "继续这个导出任务"),
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).doesNotContainKey("reason");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID)).noneMatch(
				pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		TaskSlots slots = TaskSlots.fromExtra(contextOfDriveResumed().getExtra());
		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditions()).isEmpty();

		FrontendToolGuard.Verdict verdict = new GenerativeFormGuard(this.mapper)
			.inspectArguments(GenerativeFormRules.TOOL_NAME, CONDITION_FORM_JSON, contextOfDriveResumed());
		assertThat(verdict.accepted()).as("条件未知时模型仍可用一次非必填条件表单收集").isTrue();
	}

	// ── ④ 模型表单留空：交棒模型全量推进，不再补第二张 ──────────────────────

	@Test
	void blankConditionFormSubmissionContinuesWithFullExport() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		seedSuspendedRun(List.of(modelConditionForm("{\"conditions\":\"\"}")),
				List.of("导出 CURRENCY 币种", "继续这个导出任务"),
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).doesNotContainKey("reason");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		List<Message> handed = historyOfDriveResumed();
		Message last = handed.get(handed.size() - 1);
		assertThat(last).isInstanceOf(ToolResponseMessage.class);
		List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) last).getResponses();
		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).name()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(responses.get(0).responseData()).contains("conditions").contains("\\\"\\\"");
		assertThat(this.store.pendingsLenient(RUN_ID)).as("留空不得触发第二张条件表单")
			.noneMatch(pending -> PendingToolCall.PENDING.equals(pending.getStatus()));
		assertThat(contextOfDriveResumed().getStep()).as("步骤不变：由模型决定 confirm_step 全量推进")
			.isEqualTo("QUERY_COND");
	}

	// ── ⑤ 新建导出任务且配置未知：既有定义选择表单不受影响 ──────────────────

	@Test
	void creatingAnExportTaskWithUnknownDefinitionsStillParksTheDefinitionForm() {
		this.taskState.set(task(TASK_ID, "SELECT_DEFS"));
		seedCreatedTask();
		seedSuspendedRun(List.of(navigateEntry()), List.of("帮我新建一个导出任务"),
				AiContext.of("tasks", null, null, null, null));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).as("配置未知时宿主仍挂定义选择表单（本批没有改它）")
			.containsEntry("reason", "DEFINITION_FORM_PARKED");
		assertThat(this.store.pending(RUN_ID, DEF_FORM)).isNotNull();
		assertThat(this.store.pendingsLenient(RUN_ID)).as("待决的只有定义选择表单，不得顺带挂条件表单")
			.filteredOn(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
			.extracting(PendingToolCall::getToolCallId)
			.containsExactly(DEF_FORM);
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n页面：任务中心\n"
			+ "</workspace_state>\n";

	private AiContext contextOfDriveResumed() {
		ArgumentCaptor<AiContext> nextHop = ArgumentCaptor.forClass(AiContext.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).driveResumed(eq(RUN_ID), eq(SESSION_ID),
				nextHop.capture(), any(), eq(this.emitter));
		return nextHop.getValue();
	}

	@SuppressWarnings("unchecked")
	private List<Message> historyOfDriveResumed() {
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).driveResumed(eq(RUN_ID), eq(SESSION_ID),
				any(AiContext.class), history.capture(), eq(this.emitter));
		return history.getValue();
	}

	private void seedCreatedTask() {
		PendingToolCall createTask = entry(CALL_CREATE, RunContextRefresher.TOOL_CREATE_TASK,
				"{\"type\":\"EXPORT\"}", PendingToolCall.KIND_CONFIRM, PendingToolCall.EXECUTED,
				ToolOutcome.OK.wire());
		createTask.setResultText("已创建导出任务 #" + TASK_ID + "（当前步骤：SELECT_DEFS）");
		this.store.appendLedger(RUN_ID, createTask, createTask.getResultText());
	}

	private void seedSuspendedRun(List<PendingToolCall> inFlight, List<String> userMessages, AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(context.toMap());
		List<Message> history = new ArrayList<>();
		history.add(new SystemMessage(SYSTEM_TEXT));
		for (String message : userMessages) {
			history.add(new UserMessage(message));
		}
		snapshot.setMessageJson(this.codec.serializeAll(history));
		snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
		this.store.save(snapshot);
		for (PendingToolCall pending : inFlight) {
			this.store.putPending(RUN_ID, pending);
		}
	}

	private static PendingToolCall navigateEntry() {
		return entry(CALL_NAVIGATE, RunContextRefresher.TOOL_NAVIGATE,
				"{\"page\":\"export\",\"taskId\":" + TASK_ID + "}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire());
	}

	private static PendingToolCall modelConditionForm(String resultText) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-model-cond-form");
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setType("function");
		pending.setArguments(CONDITION_FORM_JSON);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText(resultText);
		pending.setReq(Boolean.TRUE);
		return pending;
	}

	private static PendingToolCall entry(String callId, String name, String arguments, String kind, String status,
			String outcome) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(callId);
		pending.setName(name);
		pending.setType("function");
		pending.setArguments(arguments);
		pending.setKind(kind);
		pending.setStatus(status);
		pending.setOutcome(outcome);
		pending.setResultText("前端已执行：" + name);
		pending.setReq(Boolean.TRUE);
		return pending;
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
