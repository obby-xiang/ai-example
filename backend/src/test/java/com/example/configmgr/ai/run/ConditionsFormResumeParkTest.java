package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.form.DefinitionSelectionForm;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlotResolver;
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
import com.fasterxml.jackson.databind.JsonNode;
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
 * <b>续跑侧的真实链</b>：进入 {@code QUERY_COND} 之后，宿主是否<b>代替模型</b>把筛选条件文本框挂起，
 * 以及"用户已给条件 / 留空 / 过了条件步"三种情形是否各走各的路。
 *
 * <h2>为什么测在续跑侧</h2>
 * 生产 {@code app.ai.park.enabled=true}：{@code confirm_step} 是前端工具，它在首轮的等待处就结束了
 * 那一轮，真正的结算、批间上下文刷新与"下一跳"都在 {@link ResumeService}。于是"刚进入条件步、
 * 模型再被问之前"的确定点就在这里 —— 本类断言的就是这个点上<b>模型没有被问</b>
 * （{@code driveResumed} 一次都没被调用 ⇒ 不可能出现条件文字反问）。
 *
 * <h2>口径</h2>
 * <ol>
 * <li>进入 {@code QUERY_COND} + 条件未知 + 新任务链 ⇒ 挂起非必填文本框（不是驱动模型）；</li>
 * <li>用户提交文本后 ⇒ <b>同一轮</b>把文本作为工具结果交棒模型（模型据此 set_condition），不再弹第二张；</li>
 * <li>{@code EXPORT} 步 ⇒ 永不弹条件框，照常交棒（start_export 的确认卡由既有链路承接）；</li>
 * <li>全链路：创建 → 导航（配置表单）→ 提交 → 推进条件步（条件文本框）→ 提交 → 推进导出步 ⇒
 * 每一步只问该问的，已完成节点不重复。</li>
 * </ol>
 *
 * <p><b>证红</b>：续跑侧不接条件表单 ⇒ 第 1/4 条红；提交后仍挂起（不再交棒）⇒ 第 2 条红；
 * 步骤门写错（EXPORT 也弹）⇒ 第 3 条红。
 */
class ConditionsFormResumeParkTest {

	private static final String RUN_ID = "run-cond-form-resume";

	private static final String SESSION_ID = "s-cond-form-resume";

	private static final long TASK_ID = 42L;

	private static final String CALL_CREATE = "call-create";

	private static final String CALL_NAVIGATE = "call-navigate";

	private static final String CALL_STEP = "call-step";

	private static final String CALL_SET_CONDITION = "call-set-condition";

	private static final String DEF_FORM = DefinitionSelectionForm.toolCallId(TASK_ID);

	private static final String COND_FORM = ConditionsForm.toolCallId(TASK_ID);

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
			new SlotDefinition("CURRENCY", "币种", List.of()),
			new SlotDefinition("FX_RATE", "汇率", List.of())));

	private AnnotationConfigApplicationContext registryContext;

	private ToolRegistry toolRegistry;

	private ConfirmGate gate;

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
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(new GenerativeFormGuard(new ObjectMapper())));
		this.service.setDefinitionSelectionForm(new DefinitionSelectionForm(this.store, this.registry, this.gate,
				this.codec, this.properties, this.catalog));
		this.service.setConditionsForm(new ConditionsForm(this.store, this.registry, this.gate, this.codec,
				this.properties));
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── ① 进入条件步：宿主挂框，模型不被问 ──────────────────────────────────

	@Test
	void confirmStepIntoQueryCondParksInsteadOfDrivingTheModel() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		seedSuspendedRun(List.of(stepEntry()), "帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("reason", "CONDITIONS_FORM_PARKED");
		// 模型一次都没被问 ⇒ 不可能出现"请告诉我筛选条件"这类文字反问
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				org.mockito.ArgumentMatchers.<Long>isNull(), any(), any());

		PendingToolCall parked = this.store.pending(RUN_ID, COND_FORM);
		assertThat(parked).isNotNull();
		assertThat(parked.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(parked.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(parked.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(parked.getReq()).as("请求帧已置位（刷新可重挂）").isTrue();
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(COND_FORM);

		JsonNode field = parse(parked.getArguments()).path("form").path("fields").get(0);
		assertThat(field.path("type").asText()).isEqualTo("text");
		assertThat(field.path("required").asBoolean()).isFalse();
		verify(this.emitter).frontendToolRequest(eq(parked), org.mockito.ArgumentMatchers.isNull());
	}

	// ── ② 提交文本：同一轮交棒模型，不再弹第二张 ────────────────────────────

	@Test
	void submittedConditionsTextDrivesTheModelInTheSameTurnWithoutAskingAgain() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		String values = "{\"conditions\":\"code = CNY\"}";
		seedSuspendedRun(List.of(condFormEntry(PendingToolCall.FRONTEND_RESULT, values)),
				"帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		// 用户输入的文本作为工具结果进历史（模型据此 set_condition）；步骤不变
		List<Message> handed = historyOfDriveResumed();
		Message last = handed.get(handed.size() - 1);
		assertThat(last).isInstanceOf(ToolResponseMessage.class);
		List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) last).getResponses();
		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).name()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(responses.get(0).responseData()).contains("code = CNY");
		assertThat(contextOfDriveResumed().getStep()).isEqualTo("QUERY_COND");
		assertThat(this.store.pendingsLenient(RUN_ID)).as("提交后不得再挂第二张条件框").hasSize(1);
	}

	// ── ③ 已过条件步（EXPORT）：不弹，交棒模型走 start_export ───────────────

	@Test
	void exportStepNeverParksAndTheModelContinues() {
		this.taskState.set(task(TASK_ID, "EXPORT"));
		seedCreatedTask();
		seedSuspendedRun(List.of(stepEntry()), "帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("EXPORT 之后不得再弹条件框")
			.noneMatch(pending -> COND_FORM.equals(pending.getToolCallId()));
		assertThat(contextOfDriveResumed().getStep()).isEqualTo("EXPORT");
	}

	// ── ④ 用户明确要求别的表单/工具时不抢占（GF2 回归）───────────────────────

	@Test
	void anExplicitClarifyRequestDrivesTheModelInsteadOfParkingTheConditionBox() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		// GF2 原话：既要"继续这个导出任务"，又明确要求模型发 CLARIFY 表单
		String gf2 = "我想继续这个导出任务，但你缺两个信息。请调用 generative_form 工具（scenario=CLARIFY）"
				+ "向我澄清 exportScope/includeInactive 分别要什么，不要调用其他工具";
		seedSuspendedRun(List.of(stepEntry()), gf2,
				AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).as("不得挂起条件框（否则 CLARIFY 永远发不出来）")
			.doesNotContainEntry("reason", "CONDITIONS_FORM_PARKED");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("宿主一张表都不该挂")
			.noneMatch(pending -> COND_FORM.equals(pending.getToolCallId()));
	}

	@Test
	void anExplicitConditionRequestStillParksOnTheResumePath() {
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		seedCreatedTask();
		seedSuspendedRun(List.of(stepEntry()),
				"继续这个导出任务，请调用 generative_form（scenario=FILTER）收集筛选条件",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).containsEntry("reason", "CONDITIONS_FORM_PARKED");
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
	}
	// ── ⑤ 全链路：每个节点只问该问的，已完成节点不重复 ──────────────────────

	@Test
	void fullChainFromCreationToExportStepAsksEachGapExactlyOnce() {
		this.taskState.set(task(TASK_ID, "SELECT_DEFS"));
		seedCreatedTask();
		seedSuspendedRun(List.of(navigateEntry()), "帮我新建一个导出任务",
				AiContext.of("tasks", null, null, null, null));

		// 节点 1：导航到新任务的选择步 ⇒ 宿主问配置项（既有行为）
		assertThat(this.service.resume(RUN_ID).body()).containsEntry("reason", "DEFINITION_FORM_PARKED");
		submitAndSettle(DEF_FORM, "{\"defCodes\":[\"CURRENCY\"]}");
		repark(List.of(this.store.pending(RUN_ID, DEF_FORM)));

		// 节点 2：配置回灌 ⇒ 交棒模型（不重复问配置）
		assertThat(this.service.resume(RUN_ID).body()).doesNotContainKey("reason");
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));

		// 节点 3：推进到条件步 ⇒ 宿主问条件（一次）
		this.taskState.set(task(TASK_ID, "QUERY_COND"));
		repark(List.of(stepEntry()));
		assertThat(this.service.resume(RUN_ID).body()).containsEntry("reason", "CONDITIONS_FORM_PARKED");
		submitAndSettle(COND_FORM, "{\"conditions\":\"code = CNY\"}");
		repark(List.of(this.store.pending(RUN_ID, COND_FORM)));

		// 节点 4：条件回灌 ⇒ 交棒模型（文本进历史），不重复弹条件框
		assertThat(this.service.resume(RUN_ID).body()).doesNotContainKey("reason");
		assertThat(this.store.pendingsLenient(RUN_ID)).extracting(PendingToolCall::getToolCallId)
			.as("两张宿主表单各一张，没有第二张")
			.filteredOn(id -> id != null && id.startsWith("host-"))
			.containsExactlyInAnyOrder(DEF_FORM, COND_FORM);

		// 节点 5：推进到导出步 ⇒ 不弹任何宿主表单，交棒模型（start_export 确认卡由既有链路承接）
		this.taskState.set(task(TASK_ID, "EXPORT"));
		repark(List.of(setConditionEntry()));
		ResumeService.ResumeResult exportStep = this.service.resume(RUN_ID);
		assertThat(exportStep.body()).doesNotContainKey("reason");
		assertThat(exportStep.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(contextOfDriveResumed().getStep()).isEqualTo("EXPORT");
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n页面：任务中心\n"
			+ "</workspace_state>\n";

	private void submitAndSettle(String toolCallId, String values) {
		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, toolCallId, values, "unit-test");
		assertThat(submission.outcome()).as("宿主表单回灌必须走既有闸门").isEqualTo(ConfirmGate.Outcome.ACCEPTED);
	}

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

	private void seedSuspendedRun(List<PendingToolCall> inFlight, String userMessage, AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage(SYSTEM_TEXT), new UserMessage(userMessage))));
		snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
		this.store.save(snapshot);
		for (PendingToolCall pending : inFlight) {
			this.store.putPending(RUN_ID, pending);
		}
	}

	private void repark(List<PendingToolCall> inFlight) {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
		snapshot.setAssistantContent(null);
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

	private static PendingToolCall stepEntry() {
		return entry(CALL_STEP, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire());
	}

	private static PendingToolCall setConditionEntry() {
		return entry(CALL_SET_CONDITION, "set_condition",
				"{\"defCode\":\"CURRENCY\",\"field\":\"code\",\"value\":\"CNY\"}",
				PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire());
	}

	private static PendingToolCall condFormEntry(String status, String resultText) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(COND_FORM);
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setType("function");
		pending.setArguments("{\"form\":{\"scenario\":\"FILTER\",\"purpose\":\"SET_CONDITIONS\","
				+ "\"fields\":[{\"key\":\"conditions\",\"label\":\"筛选条件\",\"type\":\"text\",\"required\":false,"
				+ "\"variant\":\"longText\"}]}}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(status);
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

	private JsonNode parse(String json) {
		try {
			return this.mapper.readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException("不是合法 JSON：" + json, ex);
		}
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

		@Tool(name = "start_export", description = "启动导出（确认门）")
		@ToolScope({ "task:EXPORT/EXPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.W)
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			return "不应被执行";
		}

	}

}
