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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, this.limiter,
				List.of(new GenerativeFormGuard(this.mapper)));
		this.manager.setConditionsForm(new ConditionsForm(this.store, this.registry, this.gate, this.codec,
				this.properties, this.taskService));
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
		// 前端清空条件后落库的形态是 "{}"（不是 null）：同样必须被识别为"未设置"
		this.taskState.set(task("QUERY_COND", "{}"));
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

	// ── ③ 留空回灌后：同一轮的 confirm_step 放行（全量） ─────────────────────

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
		AiContext context = AiContext.of("export", "EXPORT", step, TASK_ID, "ACTIVE", Map.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage("PERSONA"), new UserMessage("导出货币字典"))));
		snapshot.setInFlightToolCalls(new ArrayList<>());
		this.store.save(snapshot);
	}

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
		this.store.putPending(RUN_ID, form);
	}

	private ToolExecutionResult runConfirmStep(String arguments) {
		AiContext context = AiContext.fromMap(this.store.get(RUN_ID).getContext());
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-confirm", "function", "confirm_step",
					arguments)))
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
