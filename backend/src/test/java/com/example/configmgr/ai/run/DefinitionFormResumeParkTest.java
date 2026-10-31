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
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>续跑侧的真实链</b>：新建任务 → 导航到配置选择步骤之后，宿主是否<b>代替模型</b>把平铺多选表单挂起。
 *
 * <h2>为什么测在续跑侧</h2>
 * 生产 {@code app.ai.park.enabled=true}：{@code navigate_to} 是前端工具，它在首轮的等待处就结束了
 * 那一轮（{@code RunParkedException}），真正的结算、批间上下文刷新与"下一跳"都在 {@link ResumeService}。
 * 于是"导航成功之后、模型再被问之前"的唯一确定点就在这里 —— 本类断言的就是这个点上<b>模型没有被问</b>
 * （{@code driveResumed} 一次都没被调用 ⇒ 不可能出现文字反问），以及挂起的是<b>既有面</b>上的一张平铺表。
 *
 * <h2>口径</h2>
 * <ol>
 * <li>新任务 + 未知配置 ⇒ 挂起平铺多选（选项顺序 = 目录顺序，值 = defCode，无层级/无预填）；</li>
 * <li>用户已明确配置（本轮原话里点名编码）⇒ 不弹，照常交棒模型去 {@code select_definitions}；</li>
 * <li>旧任务的槽位/任务号不得污染新任务：旧槽位不压制新任务的表单，表单也不预填旧选择；</li>
 * <li>已完成的旧任务（普通浏览，本轮没有 {@code create_task}）⇒ 不弹；</li>
 * <li>表单回灌后的续跑：照常交棒（选择值进历史）；</li>
 * <li>选择成功后的连续节点（{@code select_definitions} 成功 → 宿主挂起确定性 {@code confirm_step}）⇒
 * 宿主代替模型推进、不驱动模型，也不再问第二次配置；到 {@code QUERY_COND} 之后交棒前会先问条件表单，
 * 它挂起时同样不驱动模型。</li>
 * </ol>
 */
class DefinitionFormResumeParkTest {

	private static final String RUN_ID = "run-def-form-resume";

	private static final String SESSION_ID = "s-def-form-resume";

	private static final long NEW_TASK_ID = 42L;

	private static final long OLD_TASK_ID = 7L;

	private static final String CALL_CREATE = "call-create";

	private static final String CALL_NAVIGATE = "call-navigate";

	private static final String CALL_SELECT = "call-select";

	private static final String CALL_STEP = "call-step";

	private static final String CALL_FORM = DefinitionSelectionForm.toolCallId(NEW_TASK_ID);

	/** 宿主在配置选择成功后代替模型下发的确定性推进条目（id 由任务号决定）。 */
	private static final String CALL_ADVANCE = "host-def-next-" + NEW_TASK_ID;

	private InMemoryRunStore store = new InMemoryRunStore();

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

	private final ConditionsForm conditionsForm = mock(ConditionsForm.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	private final DefinitionCatalog catalog = () -> new ArrayList<>(List.of(
			new SlotDefinition("CURRENCY", "币种", List.of()),
			new SlotDefinition("FX_RATE", "汇率", List.of()),
			new SlotDefinition("ROLE_DICT", "角色字典", List.of())));

	private AnnotationConfigApplicationContext registryContext;

	private ToolRegistry toolRegistry;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		// 本类要的是**生产装配**（park=true）：宿主表单只在 park 下生效（见 DefinitionSelectionForm 类注）
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
		// 结算替身：把条目自带的 resultText 当作结算结果（真实实现由 SpToolCallingManager 写回）
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
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of(new GenerativeFormGuard(new ObjectMapper())));
		this.service.setDefinitionSelectionForm(new DefinitionSelectionForm(this.store, this.registry, gate,
				this.codec, this.properties, this.catalog));
		// 条件表在真实链路上是同一次交棒前的最后一个宿主动作；桩只回答"该不该挂起"，候选用例不在本类。
		ReflectionTestUtils.setField(this.service, "conditionsForm", this.conditionsForm);
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── ① 新任务 + 未知配置：宿主挂表，模型不被问 ────────────────────────────

	@Test
	void newTaskNavigationParksTheFlatFormInsteadOfCallingTheModel() {
		this.taskState.set(task(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(navigateEntry(NEW_TASK_ID)), "帮我新建一个导出任务");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("reason", "DEFINITION_FORM_PARKED");
		// 模型一次都没被问 ⇒ 不可能出现"请告诉我导出哪个配置"这类文字反问
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				org.mockito.ArgumentMatchers.<Long>isNull(), any(), any());

		// 挂起态是合法的恢复态：SUSPENDED + in-flight = 这张表 + req 已置位（刷新恢复可重挂）
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(CALL_FORM);
		PendingToolCall parked = this.store.pending(RUN_ID, CALL_FORM);
		assertThat(parked.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(parked.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(parked.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(parked.getReq()).as("请求帧已置位（刷新/重挂只认 req=true 的卡）").isTrue();

		// 真实帧链：suspended 公告 + 请求帧（前端由后者渲染既有表单卡），无 tool_start 也不影响渲染
		verify(this.emitter).suspended(eq(List.of(parked)), anyString(), anyString(), anyString());
		verify(this.emitter).frontendToolRequest(eq(parked), org.mockito.ArgumentMatchers.isNull());

		// 平铺多选：值 = 目录编码、顺序 = 目录顺序，无层级/无预填
		JsonNode form = parse(parked.getArguments()).path("form");
		assertThat(form.path("purpose").asText()).isEqualTo(GenerativeFormRules.PURPOSE_SELECT_DEFINITIONS);
		JsonNode field = form.path("fields").get(0);
		assertThat(field.path("type").asText()).isEqualTo("multi_select");
		assertThat(field.path("options").findValuesAsText("value"))
			.containsExactly("CURRENCY", "FX_RATE", "ROLE_DICT");
		assertThat(field.path("options").findValuesAsText("label")).containsExactly("币种", "汇率", "角色字典");
		assertThat(field.has("defaultValue")).isFalse();
		assertThat(field.has("optionsFrom")).isFalse();

		// 本批的 navigate_to 结果仍在交棒历史里（工具轮不丢：回灌后模型看到的就是这条 + 表单结果）
		List<Message> persisted = this.codec.deserializeAll(snapshot.getMessageJson());
		assertThat(persisted.get(persisted.size() - 1).getMessageType().name()).isEqualTo("TOOL");
	}

	// ── ②′ 挂起表单不得抹掉上一轮的推理元数据（下一跳的 provider 回放要用） ─────────

	@Test
	void parkedFormKeepsTheNavigationTurnReasoningMetadata() {
		this.taskState.set(task(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(navigateEntry(NEW_TASK_ID)), "帮我新建一个导出任务");
		RunSnapshot seeded = this.store.get(RUN_ID);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("reasoningContent", "先建任务，再导航到选择步骤");
		metadata.put("encrypted_content", "sig-abc");
		seeded.setAssistantMetadata(metadata);
		this.store.save(seeded);

		this.service.resume(RUN_ID);

		RunSnapshot parked = this.store.get(RUN_ID);
		assertThat(parked.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		// 挂起只换"谁在等"：推理元数据原样留在快照上（否则下一跳请求少了 provider 要求的推理件）
		assertThat(parked.getAssistantMetadata()).isEqualTo(metadata);
	}

	// ── ③ 已知配置：不弹，交给模型走 select_definitions ─────────────────────

	@Test
	void knownConfigFromTheUserMessageDrivesTheModelWithoutAForm() {
		// 已知配置来自任务条目（结构化 defCodes），不再由宿主解析原话。
		this.taskState.set(taskWithDefs(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS",
				Task.TaskStatus.ACTIVE, "CURRENCY", "ROLE_DICT"));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(navigateEntry(NEW_TASK_ID)),
				"帮我新建一个导出任务，导出 CURRENCY 和 ROLE_DICT");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("配置已知 ⇒ 宿主不弹表（仍由模型调 select_definitions 完成选择）")
			.noneMatch(pending -> CALL_FORM.equals(pending.getToolCallId()));
		// 槽位是模型与闸门共用的"已知配置"判据：两名编码都在（不是靠猜）
		AiContext handoff = contextOfDriveResumed();
		assertThat(TaskSlots.fromExtra(handoff.getExtra()).defCodes())
			.containsExactly("CURRENCY", "ROLE_DICT");
	}

	// ── ③ 旧任务配置不带入新任务 ────────────────────────────────────────────

	@Test
	void oldTaskSlotsNeverSuppressOrPrefillTheNewTaskForm() {
		this.taskState.set(task(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedCreatedTask(NEW_TASK_ID);
		// 现场：上下文还停在旧任务 #7 上，且带着旧任务解析出来的槽位（这是"旧任务配置"最典型的残留形态）
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, new TaskSlots("EXPORT", List.of("CURRENCY"), List.of()).toMap());
		seedSuspendedRun(List.of(navigateEntry(NEW_TASK_ID)), "帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", OLD_TASK_ID, "ACTIVE", extra));

		this.service.resume(RUN_ID);

		PendingToolCall parked = this.store.pending(RUN_ID, CALL_FORM);
		assertThat(parked).as("旧任务的槽位/任务号不得压制新任务的选择表单").isNotNull();
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		JsonNode field = parse(parked.getArguments()).path("form").path("fields").get(0);
		assertThat(field.has("defaultValue")).as("旧任务的选择不得预填进新任务的表单").isFalse();
		// 上下文已换成新任务（判据落在新任务的权威值上，而不是旧任务）
		assertThat(AiContext.fromMap(this.store.get(RUN_ID).getContext()).getTaskId()).isEqualTo(NEW_TASK_ID);
	}

	// ── ④ 已完成的旧任务：普通浏览不误弹 ────────────────────────────────────

	@Test
	void browsingACompletedOldTaskNeverParksTheForm() {
		this.taskState.set(task(OLD_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.COMPLETED));
		seedSuspendedRun(List.of(navigateEntry(OLD_TASK_ID)), "打开任务 7 看看");

		this.service.resume(RUN_ID);

		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("本轮没有 create_task（只是打开既有/已完成任务）⇒ 宿主不得弹表单")
			.noneMatch(pending -> CALL_FORM.equals(pending.getToolCallId()));
	}

	// ── ⑤ 挂起快照成功后的发帧失败：不得交回模型（否则 SUSPENDED + 待决 + 模型在跑 = 双驱动） ──

	@Test
	void framePublishFailureAfterTheSnapshotIsParkedNeverDrivesTheModel() {
		this.taskState.set(task(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(navigateEntry(NEW_TASK_ID)), "帮我新建一个导出任务");
		// 请求帧发布失败（SSE 通道抖动）：快照此时已经写成 SUSPENDED
		org.mockito.Mockito.doThrow(new IllegalStateException("sse down")).when(this.emitter)
			.frontendToolRequest(any(), any());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("reason", "DEFINITION_FORM_PARKED");
		// 快照已是"等人在填表" ⇒ 本拍绝不能再让模型跑（双驱动会让同一轮的帧/历史分叉）
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		// 待决条目保持可恢复：用户提交/取消仍能结算它（不是被收敛成终态的孤儿）
		PendingToolCall parked = this.store.pending(RUN_ID, CALL_FORM);
		assertThat(parked).isNotNull();
		assertThat(parked.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(CALL_FORM);
	}

	// ── ⑥ 表单回灌后的续跑：不再问第二次 ────────────────────────────────────

	@Test
	void resumingAfterTheFormWasSubmittedHandsOffToTheModelWithoutAskingAgain() {
		this.taskState.set(task(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedCreatedTask(NEW_TASK_ID);
		String values = "{\"defCodes\":[\"CURRENCY\"]}";
		// 挂起那一刻的现场就是"已在新任务的配置选择步骤"（宿主表单正是在这里挂起的）
		seedSuspendedRun(List.of(formEntry(PendingToolCall.FRONTEND_RESULT, values, null)),
				"帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", NEW_TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
		// 用户勾选的值作为工具结果进历史（模型据此调 select_definitions）；步数/步骤不变
		List<Message> handed = historyOfDriveResumed();
		Message last = handed.get(handed.size() - 1);
		assertThat(last).isInstanceOf(org.springframework.ai.chat.messages.ToolResponseMessage.class);
		List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> responses =
				((org.springframework.ai.chat.messages.ToolResponseMessage) last).getResponses();
		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).name()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(responses.get(0).responseData()).as("回灌值回填模型").contains("CURRENCY");
		assertThat(contextOfDriveResumed().getStep()).as("表单只收集值，不推进步骤").isEqualTo("SELECT_DEFS");
		assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(1);
	}

	// ── ⑥ 连续节点：select_definitions 成功 → 宿主挂起 confirm_step，不再问/不再驱动模型 ──

	@Test
	void consecutiveStepsAfterSelectionNeverAskAgain() {
		// 勾选成功的现场：任务条目已落（defCodes 已知），上下文停在新任务的 SELECT_DEFS
		this.taskState.set(taskWithDefs(NEW_TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS",
				Task.TaskStatus.ACTIVE, "CURRENCY"));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(entry(CALL_SELECT, "select_definitions",
				"{\"codes\":[\"CURRENCY\"]}", PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT,
				ToolOutcome.OK.wire())), "帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", NEW_TASK_ID, "ACTIVE", Map.of()));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		// ① 宿主代替模型把确定性 confirm_step 挂起：本拍不驱动模型（也就不会再来一次文字反问/重复配置表）
		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("reason", "DEFINITION_STEP_ADVANCE_PARKED");
		assertThat(result.body()).containsEntry("resumed", false);
		assertThat(result.body()).containsEntry("taskId", NEW_TASK_ID);
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				org.mockito.ArgumentMatchers.<Long>isNull(), any(), any());

		// ② 挂起面是既有前端通道上的确定性条目（不是第二张配置表，也不代填推进参数/不直接写业务库）
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("勾选成功后不重复问配置").noneMatch(pending -> CALL_FORM.equals(pending.getToolCallId()));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(CALL_ADVANCE);
		PendingToolCall advance = this.store.pending(RUN_ID, CALL_ADVANCE);
		assertThat(advance).as("同一任务只会下发一次确定性推进").isNotNull();
		assertThat(advance.getName()).isEqualTo("confirm_step");
		assertThat(advance.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(advance.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(parse(advance.getArguments()).size()).as("推进目标由页面/守卫决定，宿主不代填步骤")
			.isZero();
		verify(this.emitter).suspended(eq(List.of(advance)), anyString(), anyString(), anyString());
		verify(this.emitter).frontendToolRequest(eq(advance), any());
		// 停在选择步骤的推进处时，条件表单根本没有被问到（顺序：推进 → 配置表 → 条件表）
		verify(this.conditionsForm, never()).parkIfDueAtQueryCond(anyString(), any(AiContext.class), any(),
				anyBoolean());

		// ③ 用户点过确认卡（回灌成功）后到 QUERY_COND：不再弹配置表，照常交棒
		settle(CALL_ADVANCE);
		this.taskState.set(taskWithDefs(NEW_TASK_ID, Task.TaskType.EXPORT, "QUERY_COND",
				Task.TaskStatus.ACTIVE, "CURRENCY"));
		repark(List.of(entry(CALL_STEP, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire())));
		this.service.resume(RUN_ID);

		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("条件步骤同样不得被当成配置缺口再问一次").noneMatch(pending -> CALL_FORM.equals(pending.getToolCallId()));
		AiContext second = contextOfDriveResumed();
		assertThat(second.getStep()).isEqualTo("QUERY_COND");
	}

	// ── ⑦ QUERY_COND 交棒前先问条件表：它挂起时同样不驱动模型 ─────────────────

	@Test
	void queryCondParksTheConditionsFormBeforeHandoffAndThenNeverDrivesTheModel() {
		this.taskState.set(taskWithDefs(NEW_TASK_ID, Task.TaskType.EXPORT, "QUERY_COND",
				Task.TaskStatus.ACTIVE, "CURRENCY"));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(entry(CALL_STEP, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire())), "帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", NEW_TASK_ID, "ACTIVE", Map.of()));
		when(this.conditionsForm.parkIfDueAtQueryCond(eq(RUN_ID), any(AiContext.class), any(), anyBoolean()))
			.thenReturn(true);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("reason", "CONDITIONS_FORM_PARKED");
		assertThat(result.body()).containsEntry("resumed", false);
		// 调用点就是交棒前那一份上下文（步骤已按任务权威值同步到 QUERY_COND）
		ArgumentCaptor<AiContext> handoff = ArgumentCaptor.forClass(AiContext.class);
		ArgumentCaptor<Boolean> allowUnknownForm = ArgumentCaptor.forClass(Boolean.class);
		verify(this.conditionsForm).parkIfDueAtQueryCond(eq(RUN_ID), handoff.capture(), any(),
				allowUnknownForm.capture());
		assertThat(handoff.getValue().getStep()).isEqualTo("QUERY_COND");
		assertThat(handoff.getValue().getTaskId()).isEqualTo(NEW_TASK_ID);
		assertThat(allowUnknownForm.getValue())
			.as("confirm_step 成功进入 QUERY_COND 的首次交棒：未知条件先交模型结构化").isFalse();
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				org.mockito.ArgumentMatchers.<Long>isNull(), any(), any());
	}

	// ── ⑦′ 首次 confirm 交棒 false；下一拍（非 confirm）交棒 true ─────────────

	@Test
	void confirmHandoffDisallowsTheUnknownFormOnceAndTheNextHandoffAllowsIt() {
		this.taskState.set(taskWithDefs(NEW_TASK_ID, Task.TaskType.EXPORT, "QUERY_COND",
				Task.TaskStatus.ACTIVE, "CURRENCY"));
		seedCreatedTask(NEW_TASK_ID);
		seedSuspendedRun(List.of(entry(CALL_STEP, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire())), "帮我新建一个导出任务",
				AiContext.of("export", "EXPORT", "SELECT_DEFS", NEW_TASK_ID, "ACTIVE", Map.of()));

		this.service.resume(RUN_ID);

		ArgumentCaptor<Boolean> allowUnknownForm = ArgumentCaptor.forClass(Boolean.class);
		verify(this.conditionsForm).parkIfDueAtQueryCond(eq(RUN_ID), any(AiContext.class), any(),
				allowUnknownForm.capture());
		assertThat(allowUnknownForm.getValue())
			.as("首次交棒（confirm_step 成功进入 QUERY_COND）⇒ 不替模型决定未知条件").isFalse();
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));

		// 下一拍：本批没有 confirm_step（条件草案结算回灌），仍未知时宿主才收集
		repark(List.of(entry("call-set-condition", "set_condition", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire())));
		this.service.resume(RUN_ID);

		ArgumentCaptor<Boolean> allHandoffs = ArgumentCaptor.forClass(Boolean.class);
		verify(this.conditionsForm, org.mockito.Mockito.times(2)).parkIfDueAtQueryCond(eq(RUN_ID),
				any(AiContext.class), any(), allHandoffs.capture());
		assertThat(allHandoffs.getAllValues())
			.as("首次 confirm 交棒 false ⇒ 后续非 confirm 交棒 true").containsExactly(false, true);
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

	private void seedCreatedTask(long taskId) {
		PendingToolCall createTask = entry(CALL_CREATE, RunContextRefresher.TOOL_CREATE_TASK,
				"{\"type\":\"EXPORT\"}", PendingToolCall.KIND_CONFIRM, PendingToolCall.EXECUTED, ToolOutcome.OK.wire());
		createTask.setResultText("已创建导出任务 #" + taskId + "（当前步骤：SELECT_DEFS）");
		this.store.appendLedger(RUN_ID, createTask, createTask.getResultText());
	}

	private void seedSuspendedRun(List<PendingToolCall> inFlight, String userMessage) {
		seedSuspendedRun(inFlight, userMessage, AiContext.of("tasks", null, null, null, null));
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

	/** 模拟用户回灌一张挂起卡（续跑前置会拒绝仍 PENDING 的外部条目）。 */
	private void settle(String toolCallId) {
		PendingToolCall pending = this.store.pending(RUN_ID, toolCallId);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("前端已执行：" + pending.getName());
		pending.setResolvedAtMs(System.currentTimeMillis());
		this.store.putPending(RUN_ID, pending);
	}

	private static PendingToolCall navigateEntry(long taskId) {
		return entry(CALL_NAVIGATE, RunContextRefresher.TOOL_NAVIGATE,
				"{\"page\":\"export\",\"taskId\":" + taskId + "}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire());
	}

	private static PendingToolCall formEntry(String status, String resultText, String outcome) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_FORM);
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setType("function");
		pending.setArguments("{\"form\":{\"scenario\":\"CLARIFY\",\"purpose\":\"SELECT_DEFINITIONS\","
				+ "\"fields\":[{\"key\":\"defCodes\",\"label\":\"配置项\",\"type\":\"multi_select\",\"required\":true,"
				+ "\"options\":[{\"value\":\"CURRENCY\",\"label\":\"币种\"}]}]}}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(status);
		pending.setOutcome(outcome == null ? ToolOutcome.OK.wire() : outcome);
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

	private static Task task(Long id, Task.TaskType type, String step, Task.TaskStatus status) {
		Task task = new Task();
		task.setId(id);
		task.setType(type);
		task.setCurrentStep(step);
		task.setStatus(status);
		task.setTitle("任务 " + id);
		return task;
	}

	/** 带结构化条目（仅 defCodes，条件未决定）的任务：已知配置的唯一事实来源。 */
	private static Task taskWithDefs(Long id, Task.TaskType type, String step, Task.TaskStatus status,
			String... defCodes) {
		Task task = task(id, type, step, status);
		List<com.example.configmgr.task.entity.TaskItem> items = new ArrayList<>();
		int order = 0;
		for (String defCode : defCodes) {
			com.example.configmgr.task.entity.TaskItem item = new com.example.configmgr.task.entity.TaskItem();
			item.setTaskId(id);
			item.setDefCode(defCode);
			item.setSortOrder(order++);
			items.add(item);
		}
		task.setItems(items);
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
		public String setCondition(@ToolParam(description = "配置定义编码") String defCode) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "confirm_step", description = "确认并推进向导步骤")
		@ToolScope({ "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
			return "前端哨兵桩（永不执行）";
		}

	}

}
