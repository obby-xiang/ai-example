package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.slot.TaskSlots;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>confirm_step 的后置条件兜底</b>：模型试图从 {@code QUERY_COND} 直接推进到 {@code EXPORT} 时，
 * 若任务真实条目上还没有任何筛选条件、且本轮还没有收集过，宿主在真正推进前拦下并挂起一次
 * 非必填条件表单（任务仍停 {@code QUERY_COND}）；用户留空回灌后，后续 {@code confirm_step}
 * 才允许全量推进。
 *
 * <h2>为什么不能靠模型自觉</h2>
 * 真实 Chrome 里模型会直接 {@code confirm_step} 跳过条件收集，导出作业因此按全量执行 ——
 * 这是数据正确性问题，不能只靠提示词。判据必须是<b>任务条目的真实 {@code conditionJson}</b>，
 * 不是写死某个值或正则。
 *
 * <h2>覆盖</h2>
 * <ol>
 * <li>任务条目已有条件 ⇒ 不弹，{@code confirm_step} 照常执行；</li>
 * <li>条件未知 + 本轮未收集 ⇒ 拦下并挂一张可选条件表单，前端不被调用、任务仍停 QUERY_COND；</li>
 * <li>表单留空回灌后 ⇒ 再 {@code confirm_step} 放行（全量）；</li>
 * <li>已经收集过（同一轮已有宿主条件表单）⇒ 不重复弹；</li>
 * <li>已经到 {@code EXPORT} ⇒ 永不弹。</li>
 * </ol>
 */
class ConfirmStepConditionFallbackTest {

	private static final String RUN_ID = "run-confirm-cond-fallback";

	private static final String SESSION_ID = "s-confirm-cond-fallback";

	private static final long TASK_ID = 42L;

	private static final String HOST_FORM_ID = "host-cond-form-" + RUN_ID + "-" + TASK_ID;

	private static final String CONDITION_FORM_JSON = "{\"form\":{\"scenario\":\"FILTER\","
			+ "\"purpose\":\"SET_CONDITIONS\",\"fields\":[{\"key\":\"conditions\",\"label\":\"筛选条件\","
			+ "\"type\":\"text\",\"required\":false,\"variant\":\"longText\"}]}}";

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
			return pending;
		});

		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, this.limiter,
				List.of(new GenerativeFormGuard(this.mapper)));
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

	// ── ① 已知条件已落在任务条目上：直接放行，不弹 ──────────────────────────

	@Test
	void knownConditionSetOnTheTaskNeverParks() {
		this.taskState.set(task("QUERY_COND", "{\"fields\":[{\"fieldCode\":\"code\","
				+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));
		seedSnapshot("QUERY_COND");

		ToolExecutionResult result = runConfirmStep("{\"step\":\"EXPORT\"}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.OK.wire());
		verify(this.gate).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("条件已设置 ⇒ 不得再弹条件表单")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

	// ── ② 条件未知 + 试图跳过：拦下并挂一次可选条件表单 ────────────────────

	@Test
	void unknownConditionConfirmStepIsInterceptedOnce() {
		// 从未设置（null）才是"未决定"；"{}" 是用户明确清空（见 ②b），两者必须区分
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");

		assertThatThrownBy(() -> runConfirmStep("{}"))
			.isInstanceOf(RunParkedException.class);

		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.taskState.get().getCurrentStep()).as("任务仍停 QUERY_COND（前端没有被调用）")
			.isEqualTo("QUERY_COND");
		PendingToolCall form = this.store.pending(RUN_ID, HOST_FORM_ID);
		assertThat(form).isNotNull();
		assertThat(form.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(form.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(form.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.get(RUN_ID).getInFlightToolCalls())
			.extracting(PendingToolCall::getToolCallId)
			.containsExactly(HOST_FORM_ID);
		assertThat(form.getArguments()).contains("SET_CONDITIONS").contains("\"required\":false");
		verify(this.emitter).suspended(eq(List.of(form)), any(String.class), any(String.class), any(String.class));
	}

	/** ②b 明确不设：前端把条件清空后落库的形态是 {@code "{}"}（不是 null）⇒ 已答过，直接放行。 */
	@Test
	void explicitlyClearedConditionJsonAllowsTheAdvance() {
		this.taskState.set(task("QUERY_COND", "{}"));
		seedSnapshot("QUERY_COND");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.OK.wire());
		verify(this.gate).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("明确不设不得再弹条件表单")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

	// ── ②c 非空回灌未落库：零副作用拒绝 confirm_step，指回 set_condition ─────

	@Test
	void nonEmptyConditionFeedbackThatWasNotPersistedRejectsConfirmStep() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedCollectedForm("{\"conditions\":\"code = CNY\"}");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message())
			.contains("set_condition")
			.contains("尚未生成草案");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("已知未落库不得再弹表单")
			.noneMatch(pending -> PendingToolCall.PENDING.equals(pending.getStatus()));
	}

	// ── ②d 条件未决定：confirm_step 不自行落库，交还宿主挂一次条件表（单通道）──

	/**
	 * 新口径（2026-10-30 条件收集单通道）：槽位里"看起来已知"的条件不再由工作流自动落库
	 * （文本可信链已删除）；任务条目无 conditionJson ⇒ 条件未决定 ⇒ confirm_step 交还线程，
	 * 由宿主挂一次 {@code SET_CONDITIONS}（零副作用，不写库）。
	 */
	@Test
	void undecidedConditionAtConfirmStepParksTheHostConditionForm() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshotWithSlots("QUERY_COND", "{\"taskType\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
				+ "\"conditions\":[{\"defCode\":\"CURRENCY\",\"fieldCode\":\"code\",\"value\":\"CNY\"}],"
				+ "\"conditionDefCodes\":[\"CURRENCY\"],\"undecidedDefCodes\":[\"CURRENCY\"],"
				+ "\"conditionTexts\":[\"code = CNY\"],\"verifiedConditionDefCodes\":[\"CURRENCY\"]}");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> runConfirmStep("{}"))
			.as("条件未决定 ⇒ 交还线程（park），不经过模型、也不自行写库")
			.isInstanceOf(RunParkedException.class);

		verify(this.taskService, never()).setCondition(any(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("宿主挂的是那张条件表（单通道）")
			.anyMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName())
					&& PendingToolCall.PENDING.equals(pending.getStatus()));
	}

	// ── ②e 禁止 SELECT_DEFS 直接跳 EXPORT ───────────────────────────────────

	@Test
	void selectDefsToExportJumpIsRejectedWithoutSideEffect() {
		this.taskState.set(task("SELECT_DEFS", null));
		seedSnapshot("SELECT_DEFS");

		ToolExecutionResult result = runConfirmStep("{\"step\":\"EXPORT\"}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message())
			.contains("SELECT_DEFS")
			.contains("QUERY_COND")
			.contains("confirm_step");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.taskState.get().getCurrentStep()).isEqualTo("SELECT_DEFS");
	}

	/** 显式点名 {@code step=EXPORT}（不是默认下一步）同样在真正推进前被拦。 */
	@Test
	void explicitExportTargetIsAlsoIntercepted() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");

		assertThatThrownBy(() -> runConfirmStep("{\"step\":\"EXPORT\"}"))
			.isInstanceOf(RunParkedException.class);
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pending(RUN_ID, HOST_FORM_ID)).isNotNull();
	}

	// ── ②f 宿主条件表单文案复用回答语言（Task 1 第 6 项）─────────────────────
	@Test
	void hostConditionFormCopyFollowsTheReplyLanguage() {
		java.util.Locale previous = org.springframework.context.i18n.LocaleContextHolder.getLocale();
		try {
			org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
			this.taskState.set(task("QUERY_COND", null));
			seedSnapshot("QUERY_COND");

			assertThatThrownBy(() -> runConfirmStep("{}")).isInstanceOf(RunParkedException.class);

			PendingToolCall form = this.store.pending(RUN_ID, HOST_FORM_ID);
			assertThat(form.getArguments()).contains(ConditionsForm.TITLE_EN)
				.contains(ConditionsForm.FIELD_LABEL_EN)
				.contains(ConditionsForm.PLACEHOLDER_EN)
				.doesNotContain(ConditionsForm.TITLE)
				.doesNotContain(ConditionsForm.PLACEHOLDER);
		}
		finally {
			org.springframework.context.i18n.LocaleContextHolder.setLocale(previous);
		}
	}

	/** 模型自己发的条件表与宿主条件表共享同一份回灌结果（Task 1 第 2 项）。 */
	@Test
	void modelIssuedConditionFormFeedbackIsSharedAndRejectsConfirmStep() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedModelForm("code = CNY");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message()).contains("set_condition");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
	}

	// ── ②g 多配置逐项核对：一个有条件不得整体放行 ─────────────────────────
	@Test
	void partiallyConditionedMultiConfigStillAsksForTheRemainingItem() {
		Task task = task("QUERY_COND", "{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}");
		TaskItem second = new TaskItem();
		second.setTaskId(TASK_ID);
		second.setDefCode("ORDER");
		second.setConditionJson(null);
		task.getItems().add(second);
		this.taskState.set(task);
		seedSnapshot("QUERY_COND");

		assertThatThrownBy(() -> runConfirmStep("{}")).isInstanceOf(RunParkedException.class);
		assertThat(this.store.pending(RUN_ID, HOST_FORM_ID + "-ORDER")).as("按剩余缺口生成新的卡号").isNotNull();
	}

	/** Task 1 第 6/7 项：locale 由本轮执行信封显式传播到工具线程，而不是靠 ThreadLocal 残留。 */
	@Test
	void hostConditionFormCopyUsesTheLocalePropagatedInToolContext() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
		try {
			assertThatThrownBy(() -> runConfirmStepWithLocale("{}", "en-US"))
				.isInstanceOf(RunParkedException.class);

			PendingToolCall form = this.store.pending(RUN_ID, HOST_FORM_ID);
			assertThat(form.getArguments()).contains(ConditionsForm.TITLE_EN)
				.contains(ConditionsForm.PLACEHOLDER_EN)
				.doesNotContain(ConditionsForm.TITLE);
		}
		finally {
			org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
		}
	}

	// ── ③ 留空回灌后：同一轮的 confirm_step 放行（全量） ─────────────────────

	/** 取消不换卡重问：用户关掉条件表就是明确"不回答"，后续 confirm_step 不得再挂第二张。 */	/**
	 * 取消不自动重问，但也<b>不得放行</b>：条件仍未决定，confirm_step 必须零副作用拒绝并说明
	 * "先 set_condition 或由用户明确全量"，否则后续 start_export 会被同源预检卡死。
	 */
	@Test
	void cancelledConditionFormIsRejectedWithoutReAsking() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedFormWithStatus(PendingToolCall.FRONTEND_CANCELLED, null);

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message())
			.contains("下一轮")
			.contains("全量")
			.doesNotContain("重新收集一次");
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID))
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.as("取消过的那张是唯一一张，不换 toolCallId 重问").hasSize(1);
	}

	/** Critical-A：部分缺口卡（host-cond-form-<run>-<task>-<def>）的回灌必须归属本任务。 */
	@Test
	void gapScopedHostFormFeedbackIsRecognizedAndRejectsConfirmStep() {
		Task task = task("QUERY_COND", "{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}");
		TaskItem remaining = new TaskItem();
		remaining.setTaskId(TASK_ID);
		remaining.setDefCode("ORDER");
		remaining.setConditionJson(null);
		task.getItems().add(remaining);
		this.taskState.set(task);
		seedSnapshot("QUERY_COND");
		seedForm(HOST_FORM_ID + "-ORDER", PendingToolCall.FRONTEND_RESULT, "{\"conditions\":\"code = USD\"}");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).as("不能误认其他 task 而直接 ALLOW")
			.isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message()).contains("set_condition");
	}

	/** Critical-A：部分缺口卡的留空回灌必须放行，但<b>后端零业务写</b>（由前端离开 QUERY_COND 时提交 {}）。 */
	@Test
	void gapScopedHostFormBlankFeedbackAllowsTheAdvanceWithoutBackendWrite() {
		Task task = task("QUERY_COND", "{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}");
		TaskItem remaining = new TaskItem();
		remaining.setTaskId(TASK_ID);
		remaining.setDefCode("ORDER");
		remaining.setConditionJson(null);
		task.getItems().add(remaining);
		this.taskState.set(task);
		seedSnapshot("QUERY_COND");
		seedForm(HOST_FORM_ID + "-ORDER", PendingToolCall.FRONTEND_RESULT, "{\"conditions\":\"\"}");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.OK.wire());
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	/**
	 * 非空表单回灌必须按表单覆盖的 defCode 逐项生成草案：只处理了已设置的 CURRENCY、没有处理
	 * 尚未决定的 ORDER ⇒ 零副作用拒绝并指回 set_condition（不换卡重问，也不误放行）。
	 */
	@Test
	void unhandledRemainingItemInNonEmptyFeedbackRejectsWithoutAnotherForm() {
		Task task = task("QUERY_COND", "{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}");
		TaskItem remaining = new TaskItem();
		remaining.setTaskId(TASK_ID);
		remaining.setDefCode("ORDER");
		remaining.setConditionJson(null);
		task.getItems().add(remaining);
		this.taskState.set(task);
		seedSnapshot("QUERY_COND");
		PendingToolCall form = seedFormWithStatus(PendingToolCall.FRONTEND_RESULT, "{\"conditions\":\"code = CNY\"}");
		form.setResolvedAtMs(System.currentTimeMillis() - 1000L);
		this.store.putPending(RUN_ID, form);
		seedSetConditionFeedback();

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message()).contains("set_condition").contains("ORDER");
		assertThat(this.store.pendingsLenient(RUN_ID))
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.as("未处理的是非空回灌，不得换卡再问用户").hasSize(1);
	}

	@Test
	void blankSubmittedFormAllowsTheAdvance() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedCollectedForm("{\"conditions\":\"\"}");

		ToolExecutionResult result = runConfirmStep("{\"step\":\"EXPORT\"}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.OK.wire());
		verify(this.gate).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("留空回灌后不得再补第二张")
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.hasSize(1);
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ③b 已成功结算的 set_condition 草案：confirm 认可，不要求 conditionJson 已存在 ──

	@Test
	void settledSetConditionDraftAllowsTheAdvanceWithoutTaskConditionJson() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedSetConditionDraft("CURRENCY", "{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome())
			.as("草案已成功结算 ⇒ 前端会在下一步 persistStepData 提交，不再要求提前落库").isEqualTo(ToolOutcome.OK.wire());
		verify(this.gate).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID)).as("已有草案不得再弹条件表")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	@Test
	void failedSetConditionDraftRejectsWithoutAskingAgain() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedSetConditionDraft("CURRENCY",
				"{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}", ToolOutcome.FAILED.wire());

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID)).as("内部执行失败不得转化成再次询问用户")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

	// ── ③c 候选绑定错误/校验失败：内部失败，不弹表、不写库 ─────────────────

	@Test
	void invalidCreateTaskCandidateAtConfirmRejectsWithoutForm() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshotWithExtra("QUERY_COND", candidateExtra(Map.of(
				"fields", List.of(Map.of("fieldCode", "unknownField", "operator", "EQ", "value", "CNY")))));

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("候选校验失败不得改成再问用户一次").noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

	@Test
	void invalidCreateTaskCandidateBindingAtConfirmRejectsWithoutForm() {
		this.taskState.set(task("QUERY_COND", null));
		Map<String, Object> extra = new java.util.LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID + 1);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"));
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS,
				Map.of("fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY"))));
		seedSnapshotWithExtra("QUERY_COND", extra);

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").code()).isEqualTo(ErrorCodes.TOOL_EXECUTION_FAILED);
		verify(this.gate, never()).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

	/** 非空表单回灌压过 create_task 候选：必须先由模型解析成草案，不能自动下发候选。 */
	@Test
	void nonEmptyFeedbackIsNotOverriddenByCreateTaskCandidate() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshotWithExtra("QUERY_COND", candidateExtra(Map.of(
				"fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")))));
		seedFormWithStatus(PendingToolCall.FRONTEND_RESULT, "{\"conditions\":\"code = USD\"}");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
		assertThat(envelopeOf(result, "call-confirm").message()).contains("set_condition");
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("非空回灌未解析前不得下发候选草案").noneMatch(pending -> "set_condition".equals(pending.getName()));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	/** 留空回灌压过 create_task 候选：允许推进，零草案、零业务写。 */
	@Test
	void blankFeedbackIsNotOverriddenByCreateTaskCandidate() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshotWithExtra("QUERY_COND", candidateExtra(Map.of(
				"fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")))));
		seedCollectedForm("{\"conditions\":\"\"}");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.OK.wire());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("留空 = 全量：不得再下发候选草案").noneMatch(pending -> "set_condition".equals(pending.getName()));
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ④ 重复不弹：同一轮已有宿主条件表单，再 confirm_step 不再挂 ──────────

	@Test
	void secondConfirmStepAfterCollectionDoesNotParkAgain() {
		this.taskState.set(task("QUERY_COND", null));
		seedSnapshot("QUERY_COND");
		seedCollectedForm("{\"conditions\":\"\"}");

		runConfirmStep("{}");
		runConfirmStep("{}");

		verify(this.gate, times(2)).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID))
			.filteredOn(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()))
			.hasSize(1);
	}

	// ── ⑤ 已经到 EXPORT：永不弹 ─────────────────────────────────────────────

	@Test
	void exportStepNeverParks() {
		this.taskState.set(task("EXPORT", null));
		seedSnapshot("EXPORT");

		ToolExecutionResult result = runConfirmStep("{}");

		assertThat(envelopeOf(result, "call-confirm").outcome()).isEqualTo(ToolOutcome.OK.wire());
		verify(this.gate).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
		assertThat(this.store.pendingsLenient(RUN_ID))
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private void seedSnapshot(String step) {
		seedSnapshotWithSlots(step, null);
	}

	private void seedSnapshotWithSlots(String step, String slotsJson) {
		Map<String, Object> extra = new java.util.LinkedHashMap<>();
		if (slotsJson != null) {
			extra.put(TaskSlots.EXTRA_KEY, slotsMap(slotsJson));
		}
		seedSnapshotWithExtra(step, extra);
	}

	private void seedSnapshotWithExtra(String step, Map<String, Object> extra) {
		AiContext context = AiContext.of("export", "EXPORT", step, TASK_ID, "ACTIVE", extra);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage("PERSONA"), new UserMessage("导出货币字典"))));
		snapshot.setInFlightToolCalls(new ArrayList<>());
		this.store.save(snapshot);
	}

	private static Map<String, Object> candidateExtra(Map<String, Object> conditions) {
		Map<String, Object> extra = new java.util.LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"));
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions);
		return extra;
	}

	/** 槽位 JSON → extra 里的 Map 形态（与 {@code TaskSlots.fromValue} 同一读法）。 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> slotsMap(String json) {
		try {
			return this.mapper.readValue(json, Map.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void seedCollectedForm(String resultText) {
		seedFormWithStatus(PendingToolCall.FRONTEND_RESULT, resultText);
	}

	private PendingToolCall seedFormWithStatus(String status, String resultText) {
		return seedForm(HOST_FORM_ID, status, resultText);
	}

	/** 模型自己下发的条件表（toolCallId 不带宿主前缀）：同一 run 里与宿主表共享回灌结果。 */
	private void seedModelForm(String text) {
		seedForm("call-model-cond", PendingToolCall.FRONTEND_RESULT,
				"{\"conditions\":\"" + text + "\"}");
	}

	private PendingToolCall seedForm(String toolCallId, String status, String resultText) {
		PendingToolCall form = new PendingToolCall();
		form.setToolCallId(toolCallId);
		form.setName(GenerativeFormRules.TOOL_NAME);
		form.setType("function");
		form.setArguments(CONDITION_FORM_JSON);
		form.setKind(PendingToolCall.KIND_FRONTEND);
		form.setStatus(status);
		form.setOutcome(ToolOutcome.OK.wire());
		form.setResultText(resultText);
		form.setResolvedAtMs(System.currentTimeMillis());
		this.store.putPending(RUN_ID, form);
		return form;
	}

	/** N-1：FRONTEND set_condition 的回灌事实在待决条目里（不依赖 ai:ledger）。 */
	private void seedSetConditionFeedback() {
		seedSetConditionDraft("CURRENCY",
				"{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}");
	}

	private void seedSetConditionDraft(String defCode, String conditionsJson) {
		seedSetConditionDraft(defCode, conditionsJson, ToolOutcome.OK.wire());
	}

	/** 成功结算的 set_condition 草案（前端只改页面草案，业务库仍为空）；outcome 可铺失败语料。 */
	private void seedSetConditionDraft(String defCode, String conditionsJson, String outcome) {
		PendingToolCall set = new PendingToolCall();
		set.setToolCallId("call-set-draft-" + defCode);
		set.setName("set_condition");
		set.setKind(PendingToolCall.KIND_FRONTEND);
		set.setStatus(PendingToolCall.FRONTEND_RESULT);
		set.setOutcome(outcome);
		set.setArguments("{\"defCode\":\"" + defCode + "\",\"conditions\":" + conditionsJson + "}");
		set.setResolvedAtMs(System.currentTimeMillis());
		set.setResultText(ToolOutcome.OK.wire().equals(outcome) ? "已设置 " + defCode + " 的条件" : "设置条件失败");
		this.store.putPending(RUN_ID, set);
	}

	private ToolExecutionResult runConfirmStep(String arguments) {
		return runConfirmStep(arguments, Map.of("runId", RUN_ID));
	}

	private ToolExecutionResult runConfirmStepWithLocale(String arguments, String localeTag) {
		return runConfirmStep(arguments, Map.of("runId", RUN_ID,
				SpToolCallingManager.TOOL_CONTEXT_REPLY_LOCALE, localeTag));
	}

	private ToolExecutionResult runConfirmStep(String arguments, Map<String, Object> toolContext) {
		AiContext context = AiContext.fromMap(this.store.get(RUN_ID).getContext());
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-confirm", "function", "confirm_step",
					arguments)))
			.build();
		Prompt prompt = new Prompt(List.of(new SystemMessage("PERSONA"), new UserMessage("导出货币字典")),
				ToolCallingChatOptions.builder()
					.toolCallbacks(this.toolRegistry.forContext(context))
					.toolContext(toolContext)
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
