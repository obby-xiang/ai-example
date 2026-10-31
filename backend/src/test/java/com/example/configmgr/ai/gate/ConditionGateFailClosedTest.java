package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>条件链的 fail-closed 收口</b>（2026-10-31 审查 F3 / F4）。
 *
 * <h2>F3：条件收集失败不得 allow</h2>
 * 条件仍 {@code UNDECIDED} 时，任何"收不了答案"的路径（{@code app.ai.park.enabled=false}、
 * {@code saveSuspended} 失败、读任务异常、同一缺口的条件表已 BLOCKED）都必须
 * <b>零副作用拒绝 confirm_step</b>（{@code TOOL_EXECUTION_FAILED}）；
 * 只有已明确 {@code SET} / {@code EXPLICIT_UNSET} / 已结算的有效草案才放行 ——
 * 否则一次"宿主没问成"就会静默变成全量导出。
 *
 * <h2>F4：混批挂起会吞掉同批调用</h2>
 * 宿主在 {@code confirm_step} 复核里 park 条件表时，会先于同批其它调用把整批历史外置
 * （其它调用的确认卡/回执随之丢失）。最小修法：<b>混批时不 park</b>，零副作用拒绝
 * {@code confirm_step} 并明确"先单独调用 confirm_step"；同批其它工具照常结算。
 */
class ConditionGateFailClosedTest {

	private static final String RUN_ID = "run-cond-fail-closed";

	private static final String SESSION_ID = "s-cond-fail-closed";

	private static final long TASK_ID = 42L;

	private static final String HOST_FORM_ID = "host-cond-form-" + RUN_ID + "-" + TASK_ID;

	private static final String CONDITION_FORM_JSON = "{\"form\":{\"scenario\":\"FILTER\","
			+ "\"purpose\":\"SET_CONDITIONS\",\"fields\":[{\"key\":\"conditions\",\"label\":\"筛选条件\","
			+ "\"type\":\"text\",\"required\":false,\"variant\":\"longText\"}]}}";

	private InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final TaskService taskService = mock(TaskService.class);

	private final DefinitionService definitionService = mock(DefinitionService.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	private AnnotationConfigApplicationContext registryContext;

	private ToolRegistry toolRegistry;

	private SpToolCallingManager manager;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.registryContext = new AnnotationConfigApplicationContext();
		this.registryContext.registerBean(WizardTools.class);
		this.registryContext.registerBean(ToolRegistry.class);
		this.registryContext.refresh();
		this.toolRegistry = this.registryContext.getBean(ToolRegistry.class);

		when(this.taskService.findById(any())).thenAnswer(invocation -> {
			Task task = this.taskState.get();
			return task != null && task.getId() != null && task.getId().equals(invocation.getArgument(0)) ? task
					: null;
		});
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.gate.awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
			PendingToolCall pending = invocation.getArgument(1);
			pending.setStatus(PendingToolCall.FRONTEND_RESULT);
			pending.setOutcome(ToolOutcome.OK.wire());
			pending.setResultText("前端已执行：" + pending.getName());
			pending.setResolvedAtMs(System.currentTimeMillis());
			return pending;
		});
		when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
			PendingToolCall pending = invocation.getArgument(1);
			pending.setStatus(PendingToolCall.REJECTED);
			pending.setOutcome(ToolOutcome.REJECTED.wire());
			pending.setResultText("用户未批准（测试替身）");
			pending.setResolvedAtMs(System.currentTimeMillis());
			return pending;
		});

		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.manager = newManager(this.store);
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	private SpToolCallingManager newManager(InMemoryRunStore runStore) {
		SpToolCallingManager created = new SpToolCallingManager(runStore, this.registry, this.gate,
				this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter,
				List.of(new WizardActionGuard(this.definitionService)));
		ConditionsForm conditionsForm = new ConditionsForm(runStore, this.registry, this.gate, this.codec,
				this.properties, this.taskService);
		conditionsForm.setWizardActionGuard(new WizardActionGuard(this.definitionService));
		created.setConditionsForm(conditionsForm);
		return created;
	}

	// ── F3 ①：park 未启用 ⇒ 拒绝（不能全量推进）────────────────────────────

	@Test
	void parkDisabledWithUndecidedConditionRejectsInsteadOfAdvancing() {
		this.properties.getPark().setEnabled(false);
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot(this.store, "QUERY_COND");

		ToolResultEnvelope confirm = envelopeOf(runConfirmStep(this.store), "call-confirm");

		assertThat(confirm.outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(confirm.code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		assertThat(confirm.message())
			.as("必须说清：条件未决定、宿主收不了答案、不能按全量推进")
			.contains("条件")
			.contains("未决定");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.taskState.get().getCurrentStep()).isEqualTo("QUERY_COND");
	}

	// ── F3 ②：saveSuspended 失败 ⇒ 拒绝（不是"按既有路径继续"）──────────────

	@Test
	void saveSuspendedFailureRejectsInsteadOfAdvancing() {
		this.taskState.set(task("QUERY_COND", null));
		InMemoryRunStore failing = new FailingSaveStore();
		seedSnapshot(failing, "QUERY_COND");
		this.manager = newManager(failing);

		ToolResultEnvelope confirm = envelopeOf(runConfirmStep(failing), "call-confirm");

		assertThat(confirm.outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(confirm.code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		PendingToolCall form = failing.pending(RUN_ID, HOST_FORM_ID);
		assertThat(form).as("挂起失败必须把条目收敛成终态（不留看不见的 PENDING）").isNotNull();
		assertThat(form.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
	}

	// ── F3 ③：读任务异常 ⇒ 拒绝 ──────────────────────────────────────────────

	@Test
	void taskReadFailureDuringTheConditionGateRejectsInsteadOfAdvancing() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot(this.store, "QUERY_COND");
		when(this.taskService.findById(any())).thenThrow(new IllegalStateException("db down"));

		ToolResultEnvelope confirm = envelopeOf(runConfirmStep(this.store), "call-confirm");

		assertThat(confirm.outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(confirm.code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		assertThat(confirm.message()).contains("读取").contains("条件");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
	}

	// ── F3 ④：同一缺口的条件表已 BLOCKED（未结算）⇒ 拒绝，不换第二张 ─────────

	@Test
	void blockedConditionFormStillRejectsInsteadOfAdvancing() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot(this.store, "QUERY_COND");
		seedForm(this.store, HOST_FORM_ID, PendingToolCall.BLOCKED, null);

		ToolResultEnvelope confirm = envelopeOf(runConfirmStep(this.store), "call-confirm");

		assertThat(confirm.outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(confirm.code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		assertThat(confirm.message()).contains("条件").contains("未决定");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID))
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.as("不换第二张表").hasSize(1);
	}

	// ── F4：混批不 park；confirm_step 零副作用拒绝，同批 start_export 的卡不丢 ──

	@Test
	void mixedBatchRejectsConfirmStepAndKeepsTheStartExportCard() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot(this.store, "QUERY_COND");

		ToolExecutionResult result = runBatch(this.store,
				new AssistantMessage.ToolCall("call-confirm", "function", "confirm_step", "{}"),
				new AssistantMessage.ToolCall("call-start", "function", "start_export",
						"{\"taskId\":" + TASK_ID + "}"));

		ToolResultEnvelope confirm = envelopeOf(result, "call-confirm");
		assertThat(confirm.outcome()).as("混批时 confirm_step 必须零副作用拒绝").isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(confirm.code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		assertThat(confirm.message())
			.as("必须给出可执行的纠正：先单独调用 confirm_step")
			.contains("先单独调用 confirm_step");
		assertThat(this.store.pending(RUN_ID, HOST_FORM_ID)).as("混批不得先挂条件表再改").isNull();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<PendingToolCall>> captor = ArgumentCaptor.forClass(List.class);
		verify(this.emitter).suspended(captor.capture(), anyString(), anyString(), anyString());
		assertThat(captor.getValue()).extracting(PendingToolCall::getName).contains("start_export");

		verify(this.gate).awaitDecision(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.taskState.get().getCurrentStep()).isEqualTo("QUERY_COND");
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private ToolExecutionResult runConfirmStep(InMemoryRunStore runStore) {
		this.store = runStore;
		return runBatch(runStore, new AssistantMessage.ToolCall("call-confirm", "function", "confirm_step", "{}"));
	}

	private ToolExecutionResult runBatch(InMemoryRunStore runStore, AssistantMessage.ToolCall... calls) {
		AiContext context = AiContext.fromMap(runStore.get(RUN_ID).getContext());
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(calls))
			.build();
		Prompt prompt = new Prompt(List.of(new SystemMessage("PERSONA"), new UserMessage("导出货币字典")),
				ToolCallingChatOptions.builder()
					.toolCallbacks(this.toolRegistry.forContext(context))
					.toolContext(Map.of("runId", RUN_ID))
					.build());
		return this.manager.executeToolCalls(prompt, new ChatResponse(List.of(new Generation(assistant))));
	}

	private static ToolResultEnvelope envelopeOf(ToolExecutionResult result, String toolCallId) {
		String text = null;
		for (Message message : result.conversationHistory()) {
			if (message instanceof ToolResponseMessage toolMessage) {
				for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
					if (toolCallId.equals(response.id())) {
						text = response.responseData();
					}
				}
			}
		}
		return ToolResultEnvelope.fromJson(text);
	}

	private void seedSnapshot(InMemoryRunStore runStore, String step) {
		AiContext context = AiContext.of("export", "EXPORT", step, TASK_ID, "ACTIVE", Map.of());
		RunSnapshot snapshot = runStore.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage("PERSONA"), new UserMessage("导出货币字典"))));
		snapshot.setInFlightToolCalls(new ArrayList<>());
		runStore.save(snapshot);
	}

	private void seedForm(InMemoryRunStore runStore, String toolCallId, String status, String resultText) {
		PendingToolCall form = new PendingToolCall();
		form.setToolCallId(toolCallId);
		form.setName(GenerativeFormRules.TOOL_NAME);
		form.setType("function");
		form.setArguments(CONDITION_FORM_JSON);
		form.setKind(PendingToolCall.KIND_FRONTEND);
		form.setStatus(status);
		form.setOutcome(ToolOutcome.OK.wire());
		form.setResultText(resultText);
		form.setRequestedAtMs(System.currentTimeMillis() - 1000L);
		form.setResolvedAtMs(System.currentTimeMillis() - 1000L);
		runStore.putPending(RUN_ID, form);
	}

	private static Task task(String step, String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep(step);
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("任务 " + TASK_ID);
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode("CURRENCY");
		item.setConditionJson(conditionJson);
		task.getItems().add(item);
		return task;
	}

	private static ConfigDefinition definition(String code, String... fieldCodes) {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setCode(code);
		definition.setName(code);
		List<ConfigField> fields = new ArrayList<>();
		for (String fieldCode : fieldCodes) {
			ConfigField field = new ConfigField();
			field.setCode(fieldCode);
			field.setLabel(fieldCode);
			fields.add(field);
		}
		definition.setFields(fields);
		return definition;
	}

	/** 挂起写失败（模拟 Redis 抖动的确定性失败面）。 */
	static class FailingSaveStore extends InMemoryRunStore {

		@Override
		public void saveSuspended(String runId, String assistantContent, List<PendingToolCall> pendings,
				List<Message> history, MessageJsonCodec codec, Map<String, Object> assistantMetadata) {
			throw new IllegalStateException("redis down（测试替身）");
		}

	}

	/** 只声明本用例关心的披露面（与 AiTools 的真实注解同源）：工具体永不执行。 */
	static class WizardTools {

		@Tool(name = "confirm_step", description = "确认并推进向导步骤")
		@ToolScope({ "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
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

		/**
		 * 确认门工具（真实 {@code start_export} 的 scope 是 {@code task:EXPORT/EXPORT}；
		 * 本地桩放宽到 {@code task:EXPORT}，以便在 QUERY_COND 观察"同批的卡没有被 park 吞掉"）。
		 */
		@Tool(name = "start_export", description = "启动导出作业")
		@ToolScope({ "task:EXPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.J)
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			return "确认门哨兵桩（永不执行）";
		}

	}

}
