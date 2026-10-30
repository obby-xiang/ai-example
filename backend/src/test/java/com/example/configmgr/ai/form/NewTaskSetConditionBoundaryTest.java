package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 精确复现：run 内先 create_task B（无 defCodes/conditions）→ select_definitions CURRENCY →
 * 进入 QUERY_COND → 首次 set_condition 必须 PARKED（挂 B 自己的条件表）。
 *
 * <p>若实际 ALLOW，用例把每个提前放行点逐项打印出来定位根因。
 */
class NewTaskSetConditionBoundaryTest {

	private static final String RUN_ID = "run-newtask-boundary";

	private static final String SESSION_ID = "s-newtask-boundary";

	private static final long TASK_ID = 42L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final AiProperties properties = new AiProperties();

	private final TaskService taskService = mock(TaskService.class);

	private final DefinitionService definitionService = mock(DefinitionService.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	@Test
	void firstSetConditionOnNewTaskMustParkTheHostConditionsForm() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(anyString())).thenReturn(this.emitter);
		when(this.taskService.findByIdRaw(TASK_ID)).thenAnswer(invocation -> Optional.ofNullable(this.taskState.get()));
		when(this.taskService.findById(TASK_ID)).thenAnswer(invocation -> this.taskState.get());
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY"));

		// ── 1) create_task B（无 defCodes / conditions）结算 → 运行上下文 ──
		this.taskState.set(task("SELECT_DEFS", null));
		RunContextRefresher refresher = new RunContextRefresher(this.taskService);
		AiContext context = refresher.next(AiContext.empty(), settledCreateTask()).orElseThrow();

		// ── 2) select_definitions CURRENCY → 任务条目 + 进入 QUERY_COND ──
		this.taskState.set(task("QUERY_COND", null));
		context.setStep("QUERY_COND");
		context.setPage("export");
		context.getExtra().put("pageId", "export");
		context.getExtra().put("selectedDefs", List.of("CURRENCY"));
		context.getExtra().put("selectionSynced", true);

		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history()));
		this.store.save(snapshot);

		ConditionsForm form = new ConditionsForm(this.store, this.registry, this.gate, this.codec, this.properties,
				this.taskService);
		form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		// ── 3) 首次 set_condition（合法参数）──
		PendingToolCall pending = setConditionPending(
				"{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\","
						+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}}");

		ConditionsForm.SetConditionDecision decision = form.inspectSetCondition(RUN_ID, context, pending, 1, 1,
				false, history());

		System.out.println("=== NewTaskSetConditionBoundary SUMMARY ===");
		System.out.println("context.taskId=" + context.getTaskId()
				+ " createTaskTaskId=" + context.getExtra().get(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID)
				+ " requestedAt=" + context.getExtra().get(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT)
				+ " hasDefCodes=" + context.getExtra().containsKey(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES)
				+ " hasConditions=" + context.getExtra().containsKey(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS)
				+ " taskItems=" + this.taskState.get().getItems().stream()
					.map(i -> i.getDefCode() + ":" + i.getConditionJson()).toList()
				+ " decision=" + decision.outcome());
		System.out.println("=== END SUMMARY ===");

		if (!decision.parked()) {
			printDiagnostics(context, pending, form);
		}

		assertThat(decision.parked())
			.as("新建任务 B 首次 set_condition 必须 PARKED（挂 B 自己的条件表）；实际 outcome=%s code=%s message=%s",
					decision.outcome(), decision.code(), decision.message())
			.isTrue();
	}

	/** 逐项打印各提前放行点（只在未 PARKED 时调用）。 */
	private void printDiagnostics(AiContext context, PendingToolCall pending, ConditionsForm form) {
		Map<String, Object> extra = context.getExtra();
		System.out.println("=== NewTaskSetConditionBoundary DIAG ===");
		System.out.println("context.taskId              = " + context.getTaskId());
		System.out.println("extra.createTaskTaskId      = " + extra.get(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID));
		System.out.println("extra.createTaskRequestedAtMs = "
				+ extra.get(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT));
		System.out.println("extra.createTaskDefCodes    = " + extra.get(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES));
		System.out.println("extra.createTaskConditions  = "
				+ extra.get(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS));
		System.out.println("extra.taskSlots             = " + extra.get("taskSlots"));
		Task task = this.taskState.get();
		System.out.println("task.step                   = " + (task == null ? null : task.getCurrentStep()));
		System.out.println("task.items                  = " + (task == null || task.getItems() == null ? null
				: task.getItems().stream().map(i -> i.getDefCode() + ":" + i.getConditionJson()).toList()));

		FrontendToolGuard.Verdict guard = new WizardActionGuard(this.definitionService)
			.inspectArgumentsFor("set_condition", pending.getArguments());
		System.out.println("wizardActionGuard.accepted  = " + guard.accepted() + " reasons=" + guard.reasons());

		try {
			ExportConditionWorkflow.Plan plan = new ExportConditionWorkflow(this.taskService).plan(task);
			System.out.println("plan.malformed              = " + plan.malformed());
			System.out.println("plan.undecided              = " + plan.undecided());
		}
		catch (RuntimeException ex) {
			System.out.println("plan                        = EXCEPTION " + ex);
		}

		try {
			System.out.println("feedback(boundary)          = " + form.feedbackOf(RUN_ID, TASK_ID,
					boundaryOf(context)));
		}
		catch (RuntimeException ex) {
			System.out.println("feedback                    = EXCEPTION " + ex);
		}

		List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
		System.out.println("pendings                    = " + (pendings == null ? null
				: pendings.stream().map(p -> p.getName() + "|" + p.getToolCallId() + "|" + p.getStatus() + "|"
						+ p.getRequestedAtMs() + "|" + p.getResolvedAtMs()).toList()));
		System.out.println("=== END DIAG ===");
	}

	private static long boundaryOf(AiContext context) {
		Object raw = context.getExtra() == null ? null
				: context.getExtra().get(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT);
		return raw instanceof Number number ? number.longValue() : 0L;
	}

	private static PendingToolCall settledCreateTask() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-create-B");
		pending.setName(RunContextRefresher.TOOL_CREATE_TASK);
		pending.setArguments("{\"type\":\"EXPORT\"}");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("已创建导出任务 #" + TASK_ID + "（标题：导出任务，当前步骤：SELECT_DEFS）");
		return pending;
	}

	private static PendingToolCall setConditionPending(String arguments) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-set-condition");
		pending.setName("set_condition");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setArguments(arguments);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	private static Task task(String step, String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep(step);
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("B 任务");
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode("CURRENCY");
		item.setSortOrder(0);
		item.setConditionJson(conditionJson);
		List<TaskItem> items = new ArrayList<>();
		items.add(item);
		task.setItems(items);
		return task;
	}

	private static ConfigDefinition definition(String code) {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setCode(code);
		definition.setName("货币字典");
		ConfigField field = new ConfigField();
		field.setCode("code");
		field.setLabel("货币代码");
		definition.setFields(List.of(field));
		return definition;
	}

	private static List<Message> history() {
		return List.of(new SystemMessage("PERSONA"), new UserMessage("帮我新建一个导出任务"));
	}

}
