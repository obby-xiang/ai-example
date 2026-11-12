package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.TestRunRegistryFactory;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新建任务的<b>首次结构化条件</b>必须直接下发前端落库，宿主不得把它换成条件表。
 *
 * <p>被钉住的缺陷：{@code create_task} 只留下任务候选（本例条件未决定，等价 {@code create_task(conditions={})}），
 * 用户随后由模型给出合法 {@code set_conditions} 时，旧实现的「新建任务首次条件」硬边界会在工具下发前
 * 把它拦成一张 {@code SET_CONDITIONS} 表（用户面零失败帧）—— 模型以为自己设了条件，
 * 用户看到的却是一张待填写的表。
 *
 * <p>口径：{@code WizardActionGuard} 已通过的 {@code set_conditions} 是结构化事实，直接下发前端；
 * 宿主只在模型正文收尾或 {@code confirm_step} 推进时仍 UNDECIDED 才补发一次条件表
 * （见 {@code ConditionsForm#parkIfDueAtQueryCond} / {@code #conditionGate}）。
 */
class NewTaskSetConditionDeliveryTest {

	private static final String RUN_ID = "run-newtask-delivery";

	private static final String SESSION_ID = "s-newtask-delivery";

	private static final String CALL_ID = "call-set-condition";

	private static final long TASK_ID = 42L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore(true);

	private final RunRegistry registry = TestRunRegistryFactory.create(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final AiProperties properties = new AiProperties();

	private final TaskService taskService = mock(TaskService.class);

	private final DefinitionService definitionService = mock(DefinitionService.class);

	private ConfirmGate confirmGate;

	private ConditionsForm conditionsForm;

	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.cancelState(anyString())).thenReturn(com.example.configmgr.ai.run.CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.isContextMatched(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("set_conditions")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("set_conditions")).thenReturn(ToolMeta.RiskLevel.WRITE);
		when(this.toolRegistry.categoryOf("set_conditions")).thenReturn(ToolMeta.Category.U);
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY"));
		when(this.taskService.findById(TASK_ID)).thenReturn(task());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setContext(context().toMap());
		this.store.save(snapshot);
	}

	@org.junit.jupiter.api.AfterEach
	void tearDown() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void firstValidSetConditionOnNewTaskReachesTheFrontendWithoutHostForm() {
		assertThatThrownBy(() -> manager().executeToolCalls(prompt(), response()))
			.as("前端工具在生产 park 下以挂起交还线程")
			.isInstanceOf(RunParkedException.class);

		List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
		assertThat(pendings)
			.as("合法的结构化 set_conditions 不得被换成宿主条件表")
			.noneMatch(pending -> pending != null && pending.getToolCallId() != null
					&& pending.getToolCallId().startsWith(ConditionsForm.TOOL_CALL_ID_PREFIX));
		PendingToolCall delivered = pendings.stream()
			.filter(pending -> pending != null && "set_conditions".equals(pending.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("set_conditions 没有下发前端（待决条目缺失）"));
		assertThat(delivered.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(delivered.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(this.store.archivedTypes(RUN_ID))
			.as("前端应收到 set_conditions 的请求帧")
			.contains("frontend_tool_request");
	}

	@Test
	void fifteenItemsShareOneFrontendRequestAndOneReceipt() throws Exception {
		List<String> defCodes = new ArrayList<>();
		for (int index = 1; index <= 15; index++) {
			defCodes.add(String.format("DEF_%02d", index));
		}
		when(this.taskService.findById(TASK_ID)).thenReturn(task(defCodes));
		when(this.definitionService.findByCode(anyString())).thenAnswer(invocation -> definition(invocation.getArgument(0)));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setContext(context(defCodes).toMap());
		this.store.save(snapshot);
		SpToolCallingManager manager = manager();

		assertThatThrownBy(() -> manager.executeToolCalls(prompt(), response(batchArgs(defCodes))))
			.isInstanceOf(RunParkedException.class);

		List<PendingToolCall> delivered = this.store.pendingsLenient(RUN_ID).stream()
			.filter(pending -> "set_conditions".equals(pending.getName())).toList();
		assertThat(delivered).hasSize(1);
		assertThat(this.store.archivedTypes(RUN_ID).stream()
			.filter("frontend_tool_request"::equals)).hasSize(1);
		PendingToolCall batch = delivered.getFirst();
		JsonNode items = this.mapper.readTree(batch.getArguments()).path("items");
		assertThat(items).hasSize(15);
		for (int index = 0; index < 15; index++) {
			assertThat(items.get(index).path("defCode").asText()).isEqualTo(defCodes.get(index));
			if (index < 14) {
				assertThat(items.get(index).path("conditions").path("scopeKeys").get(0).asText())
					.isEqualTo("SCOPE_" + (index + 1));
			}
			else {
				assertThat(items.get(index).path("conditions").isEmpty()).isTrue();
			}
		}

		assertThat(batch.getStatus()).as("提交回执前必须仍是待决").isEqualTo(PendingToolCall.PENDING);
		PendingToolCall rawBeforeSubmit = this.store.pendingRaw(RUN_ID, batch.getToolCallId());
		assertThat(rawBeforeSubmit.getStatus()).as("status=%s outcome=%s resolved=%s requested=%s expires=%s result=%s",
				rawBeforeSubmit.getStatus(), rawBeforeSubmit.getOutcome(), rawBeforeSubmit.getResolvedAtMs(),
				rawBeforeSubmit.getRequestedAtMs(), rawBeforeSubmit.getExpiresAt(), rawBeforeSubmit.getResultText())
			.isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.decisionOf(RUN_ID, batch.getToolCallId())).isNull();
		assertThat(this.store.claimedBy(RUN_ID, batch.getToolCallId())).isNull();
		ConfirmGate.Submission submission = this.confirmGate.submitFrontendResult(RUN_ID, batch.getToolCallId(),
				"batch-15-settled", "test", false, ToolOutcome.OK.wire(), Map.of(), null);
		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.store.archivedTypes(RUN_ID).stream()
			.filter("frontend_tool_result"::equals)).hasSize(1);
		PendingToolCall settled = this.store.pending(RUN_ID, batch.getToolCallId());
		assertThat(settled.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		assertThat(settled.effectiveOutcome()).isEqualTo(ToolOutcome.OK);
		assertThat(manager.resolvePending(RUN_ID, prompt(), settled)).isEqualTo("batch-15-settled");
		assertThat(this.store.archivedTypes(RUN_ID).stream()
			.filter("frontend_tool_result"::equals)).hasSize(1);

		PendingToolCall confirm = new PendingToolCall();
		confirm.setToolCallId("call-confirm-15");
		confirm.setName("confirm_step");
		confirm.setKind(PendingToolCall.KIND_FRONTEND);
		confirm.setArguments("{}");
		assertThat(this.conditionsForm.inspectConfirmStep(RUN_ID, context(defCodes), confirm,
				List.of(new UserMessage("批量设置"))).outcome())
			.isEqualTo(ConditionsForm.ConfirmStepDecision.Outcome.ALLOW);
	}

	private String batchArgs(List<String> defCodes) throws Exception {
		var root = this.mapper.createObjectNode();
		var items = root.putArray("items");
		for (int index = 0; index < defCodes.size(); index++) {
			var item = items.addObject();
			item.put("defCode", defCodes.get(index));
			if (index == defCodes.size() - 1) {
				item.set("conditions", this.mapper.createObjectNode());
			}
			else {
				item.putObject("conditions").putArray("scopeKeys").add("SCOPE_" + (index + 1));
			}
		}
		return this.mapper.writeValueAsString(root);
	}

	private static ChatResponse response(String arguments) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "set_conditions", arguments)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static AiContext context(List<String> defCodes) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, defCodes);
		extra.put("pageId", "export");
		extra.put("selectedDefs", defCodes);
		return AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", extra);
	}

	private static Task task(List<String> defCodes) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("十五项批量条件投递");
		List<TaskItem> items = new ArrayList<>();
		for (int index = 0; index < defCodes.size(); index++) {
			TaskItem item = new TaskItem();
			item.setTaskId(TASK_ID);
			item.setDefCode(defCodes.get(index));
			item.setSortOrder(index);
			items.add(item);
		}
		task.setItems(items);
		return task;
	}

	private SpToolCallingManager manager() {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		this.confirmGate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, (ConfirmGate.RetryScheduler) null);
		WizardActionGuard guard = new WizardActionGuard(this.definitionService);
		guard.setTaskService(this.taskService);
		SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.confirmGate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of(guard));
		this.conditionsForm = new ConditionsForm(this.store, this.registry, this.confirmGate, mock(MessageJsonCodec.class),
				this.properties, this.taskService);
		this.conditionsForm.setWizardActionGuard(guard);
		manager.setConditionsForm(this.conditionsForm);
		return manager;
	}

	/** 本轮新建任务（create_task 结算并绑定任务号），条件未决定：与真实 {@code create_task(conditions={})} 同形。 */
	private static AiContext context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"));
		extra.put("pageId", "export");
		extra.put("selectedDefs", List.of("CURRENCY"));
		return AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", extra);
	}

	private static Task task() {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("新建任务条件投递");
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode("CURRENCY");
		item.setSortOrder(0);
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

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("只导出 CNY")), options);
	}

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "set_conditions",
					"{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\","
							+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}}]}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
