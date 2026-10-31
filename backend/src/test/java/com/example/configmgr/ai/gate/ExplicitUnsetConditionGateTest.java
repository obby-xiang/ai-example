package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.form.ExportConditionWorkflow;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.slot.ConditionAssessment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>明确不设筛选（用户答"无"/"不筛选"/"全部"）的闸门与逐项放行</b>（2026-10-31）。
 *
 * <h2>现场</h2>
 * 条件表输入"无"：模型对 CURRENCY / TAX_RATE 各调用一次
 * {@code set_condition(defCode, {})}，{@link WizardActionGuard} 以"空条件"拒绝；
 * 随后 {@code confirm_step} 又因"非空回灌无草案"拒绝 —— 两条链路互相锁死，任务无法推进。
 *
 * <h2>口径（与用户裁决一致）</h2>
 * <ol>
 * <li>恰好空对象 {@code {}} = 该配置项<b>明确不设筛选</b>：前端 {@code validateConditionPayload}
 *     落成 {@code "{}"}、{@code ConditionAssessment} 判 {@code EXPLICIT_UNSET}，闸门同口径放行；</li>
 * <li>明确不设必须<b>逐项</b>表达：表单覆盖的每个 defCode 各一次，全部结算后 {@code confirm_step}
 *     才放行；只清一部分 ⇒ 零副作用拒绝并<b>点名剩余项</b>，不换第二张条件表；</li>
 * <li>缺失 / {@code null} / {@code fields:[]} / {@code scopeKeys:[]} 仍拒绝（不可写成"明确不设"）；</li>
 * <li>{@code create_task(conditions={})} 语义不变：仍是"条件未决定"，由宿主收集一次，不得
 *     被本次改动理解成"明确不设"。</li>
 * </ol>
 *
 * <p>本类走真实 {@link SpToolCallingManager} + 真实 {@link WizardActionGuard} + 真实
 * {@link ConditionsForm}（任务/定义服务为替身、前端以 {@link ConfirmGate} 替身即时结算），
 * 因此"闸门放行 → 草案结算 → confirm_step 放行"是同一链路的端到端证据。
 */
class ExplicitUnsetConditionGateTest {

	private static final String RUN_ID = "run-explicit-unset";

	private static final String SESSION_ID = "s-explicit-unset";

	private static final long TASK_ID = 42L;

	private static final String HOST_FORM_ID = "host-cond-form-" + RUN_ID + "-" + TASK_ID;

	private static final String CONDITION_FORM_JSON = "{\"form\":{\"scenario\":\"FILTER\","
			+ "\"purpose\":\"SET_CONDITIONS\",\"fields\":[{\"key\":\"conditions\",\"label\":\"筛选条件\","
			+ "\"type\":\"text\",\"required\":false,\"variant\":\"longText\"}]}}";

	private static final String CNY_CONDITION_JSON = "{\"fields\":[{\"fieldCode\":\"code\","
			+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}";

	private static final String USD_CONDITION_JSON = "{\"fields\":[{\"fieldCode\":\"code\","
			+ "\"operator\":\"EQ\",\"value\":\"USD\"}]}";

	private final InMemoryRunStore store = new InMemoryRunStore();

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
			// 与真实 ConfirmGate 同口径：回灌结算时刻要落到条目上（条件表回灌后的草案核对按它排序）
			pending.setResolvedAtMs(System.currentTimeMillis());
			return pending;
		});
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		when(this.definitionService.findByCode("ORDER")).thenReturn(definition("ORDER", "code"));

		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, this.limiter,
				List.of(new WizardActionGuard(this.definitionService)));
		ConditionsForm conditionsForm = new ConditionsForm(this.store, this.registry, this.gate, this.codec,
				this.properties, this.taskService);
		conditionsForm.setWizardActionGuard(new WizardActionGuard(this.definitionService));
		this.manager.setConditionsForm(conditionsForm);
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── ① 单配置：恰好 {} 放行 → 草案结算 → confirm_step 放行 ───────────────

	@Test
	void singleDefinitionExplicitUnsetIsDeliveredAndAllowsConfirmStep() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND", Map.of());

		ToolResultEnvelope set = setCondition("call-set-currency", "CURRENCY", "{}");

		assertThat(set.ok()).as("恰好空对象 = 明确不设，闸门必须放行：%s", set).isTrue();
		verify(this.gate).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		PendingToolCall draft = this.store.pending(RUN_ID, "call-set-currency");
		assertThat(draft.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(draft.getArguments()).contains("\"conditions\":{}");

		ToolResultEnvelope confirm = confirmStep();

		assertThat(confirm.outcome()).as("草案已结算 ⇒ 前端会在下一步提交 {}，不再卡 confirm_step")
			.isEqualTo(ToolOutcome.OK.wire());
		assertThat(this.store.pendingsLenient(RUN_ID)).as("已有明确不设的草案，不得再弹条件表")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ② 两个配置全清才放行 ────────────────────────────────────────────────

	@Test
	void clearingEveryConfigCoveredByTheFormAllowsConfirmStep() {
		this.taskState.set(task("QUERY_COND", null, "ORDER"));
		seedSnapshot("QUERY_COND", Map.of());
		seedCollectedForm("{\"conditions\":\"无\"}");

		assertThat(setCondition("call-set-currency", "CURRENCY", "{}").ok()).isTrue();
		assertThat(setCondition("call-set-order", "ORDER", "{}").ok()).isTrue();

		ToolResultEnvelope confirm = confirmStep();

		assertThat(confirm.outcome()).as("表单覆盖的两个配置项都明确不设 ⇒ 放行全量推进")
			.isEqualTo(ToolOutcome.OK.wire());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.as("用户已回答不筛选，不得换第二张条件表").hasSize(1);
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ③ 只清 A：拒绝并点名剩余项 B（不换第二张表）────────────────────────

	@Test
	void clearingOnlyOneConfigRejectsAndNamesTheRemainingOne() {
		this.taskState.set(task("QUERY_COND", null, "ORDER"));
		seedSnapshot("QUERY_COND", Map.of());
		seedCollectedForm("{\"conditions\":\"无\"}");

		assertThat(setCondition("call-set-currency", "CURRENCY", "{}").ok()).isTrue();

		ToolResultEnvelope confirm = confirmStep();

		assertThat(confirm.outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(confirm.message())
			.as("拒绝理由必须点名剩余项，且不得把已清空的 CURRENCY 重新算作缺口")
			.contains("ORDER")
			.doesNotContain("CURRENCY");
		assertThat(confirm.message()).contains("set_condition(defCode, {})");
		assertThat(this.store.pendingsLenient(RUN_ID))
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.as("未处理的是非空回灌，不得换卡再问用户").hasSize(1);
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ④ 已有条件被清空：仍然是明确不设 ────────────────────────────────────

	@Test
	void clearingAnAlreadySetConditionIsStillAnExplicitUnset() {
		this.taskState.set(task("QUERY_COND", CNY_CONDITION_JSON));
		seedSnapshot("QUERY_COND", Map.of());

		assertThat(setCondition("call-set-currency", "CURRENCY", "{}").ok())
			.as("已有条件时清空同样走 set_condition(defCode, {})").isTrue();

		// 模拟前端离开 QUERY_COND 时的 persistStepData：草案（空）提交成 "{}"
		this.taskState.get().getItems().get(0).setConditionJson("{}");

		assertThat(ConditionAssessment.of(this.taskState.get()).stateOf("CURRENCY"))
			.as("落库的 {} 是 EXPLICIT_UNSET（与 ConditionAssessment 同口径）")
			.isEqualTo(ConditionAssessment.State.EXPLICIT_UNSET);
		assertThat(new ExportConditionWorkflow(this.taskService).plan(this.taskState.get()).undecided())
			.as("明确不设不再算缺口").isEmpty();

		ToolResultEnvelope confirm = confirmStep();

		assertThat(confirm.outcome()).isEqualTo(ToolOutcome.OK.wire());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ⑤ create_task 的空 {} 仍是"未决定"（本次改动不得外溢）──────────────

	@Test
	void emptyCreateTaskConditionsRemainUndecidedInsteadOfExplicitUnset() {
		this.taskState.set(task("QUERY_COND", null));
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"));
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, Map.of());
		seedSnapshot("QUERY_COND", extra);

		assertThatThrownBy(this::confirmStep)
			.as("create_task(conditions={}) = 未决定：宿主仍要收集一次，不得当成明确不设直接推进")
			.isInstanceOf(RunParkedException.class);

		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("空候选不得下发 set_condition 草案").noneMatch(pending -> "set_condition".equals(pending.getName()));
		assertThat(this.store.pending(RUN_ID, HOST_FORM_ID)).as("条件未决定 ⇒ 挂一次可选条件表").isNotNull();
	}

	// ── ⑥ 空数组仍拒绝（不可写成"明确不设"）────────────────────────────────

	@Test
	void emptyArraysAreStillRejectedBeforeReachingTheFrontend() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND", Map.of());

		ToolResultEnvelope fields = setCondition("call-set-fields-empty", "CURRENCY", "{\"fields\":[]}");
		assertThat(fields.ok()).isFalse();
		assertThat(fields.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);
		assertThat(fields.message()).contains("导出全部");

		ToolResultEnvelope scope = setCondition("call-set-scope-empty", "CURRENCY", "{\"scopeKeys\":[]}");
		assertThat(scope.ok()).isFalse();
		assertThat(scope.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);

		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
	}

	// ── ⑦ 重复调用幂等（F11）：同 run/task/defCode + 完全相同入参不再重放 ─────

	/**
	 * 模型把同一条件重复调用一次（措辞相同）时，不得再下发一次前端（重复草案/重复写）；
	 * 回执与首次一致（幂等成功）。<b>不同值</b>是修改，仍照常执行。
	 */
	@Test
	void identicalSetConditionCallsInTheSameRunAreIdempotent() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND", Map.of());

		ToolResultEnvelope first = setCondition("call-set-1", "CURRENCY", CNY_CONDITION_JSON);
		assertThat(first.ok()).isTrue();

		ToolResultEnvelope repeat = setCondition("call-set-2", "CURRENCY", CNY_CONDITION_JSON);

		assertThat(repeat.ok()).as("完全相同入参的重复调用必须幂等成功").isTrue();
		assertThat(repeat.message()).as("幂等回执与首次一致").isEqualTo(first.message());
		verify(this.gate, times(1)).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		PendingToolCall replayed = this.store.pending(RUN_ID, "call-set-2");
		assertThat(replayed.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(replayed.effectiveOutcome()).isEqualTo(ToolOutcome.OK);

		// 不同值 = 修改：仍可执行（不能被 defCode 维度的幂等吃掉）
		assertThat(setCondition("call-set-3", "CURRENCY", USD_CONDITION_JSON).ok()).isTrue();
		verify(this.gate, times(2)).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
	}

	/** 同一批次内的完全重复：只下发第一条，第二条按首条结局幂等收口（不重复写）。 */
	@Test
	void duplicateSetConditionCallsInTheSameBatchDispatchOnlyOnce() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND", Map.of());
		String arguments = conditionArguments("CURRENCY", CNY_CONDITION_JSON);

		ToolExecutionResult result = runBatch(
				new AssistantMessage.ToolCall("call-set-a", "function", "set_condition", arguments),
				new AssistantMessage.ToolCall("call-set-b", "function", "set_condition", arguments));

		assertThat(envelopeOf(result, "call-set-a").ok()).isTrue();
		assertThat(envelopeOf(result, "call-set-b").ok()).as("同批重复项幂等成功").isTrue();
		verify(this.gate, times(1)).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pending(RUN_ID, "call-set-b").effectiveOutcome()).isEqualTo(ToolOutcome.OK);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private ToolResultEnvelope setCondition(String toolCallId, String defCode, String conditionsJson) {
		return envelopeOf(runBatch(new AssistantMessage.ToolCall(toolCallId, "function", "set_condition",
				conditionArguments(defCode, conditionsJson))), toolCallId);
	}

	private static String conditionArguments(String defCode, String conditionsJson) {
		return "{\"defCode\":\"" + defCode + "\",\"conditions\":" + conditionsJson + "}";
	}

	private ToolResultEnvelope confirmStep() {
		return envelopeOf(runBatch(new AssistantMessage.ToolCall("call-confirm", "function", "confirm_step", "{}")),
				"call-confirm");
	}

	private ToolExecutionResult runBatch(AssistantMessage.ToolCall... calls) {
		AiContext context = AiContext.fromMap(this.store.get(RUN_ID).getContext());
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

	private void seedSnapshot(String step, Map<String, Object> extra) {
		AiContext context = AiContext.of("export", "EXPORT", step, TASK_ID, "ACTIVE", extra);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage("PERSONA"), new UserMessage("导出货币字典，不设筛选"))));
		snapshot.setInFlightToolCalls(new ArrayList<>());
		this.store.save(snapshot);
	}

	/** 宿主条件表的非空回灌（用户答"无"）：同一 run 的待决条目，resolvedAtMs 早于后续草案。 */
	private void seedCollectedForm(String resultText) {
		PendingToolCall form = new PendingToolCall();
		form.setToolCallId(HOST_FORM_ID);
		form.setName(GenerativeFormRules.TOOL_NAME);
		form.setType("function");
		form.setArguments(CONDITION_FORM_JSON);
		form.setKind(PendingToolCall.KIND_FRONTEND);
		form.setStatus(PendingToolCall.FRONTEND_RESULT);
		form.setOutcome(ToolOutcome.OK.wire());
		form.setResultText(resultText);
		form.setResolvedAtMs(System.currentTimeMillis() - 1000L);
		this.store.putPending(RUN_ID, form);
	}

	/** 任务：首个条目 CURRENCY；{@code conditionJson} 为 {@code null} = 未决定。 */
	private static Task task(String step, String currencyConditionJson, String... extraDefCodes) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep(step);
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("任务 " + TASK_ID);
		task.getItems().add(item("CURRENCY", currencyConditionJson));
		for (String defCode : extraDefCodes) {
			task.getItems().add(item(defCode, null));
		}
		return task;
	}

	private static TaskItem item(String defCode, String conditionJson) {
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode(defCode);
		item.setConditionJson(conditionJson);
		return item;
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

	/** 只声明本用例关心的披露面（与 {@code AiTools} 的真实注解同源）：工具体永不执行。 */
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

	}

}
