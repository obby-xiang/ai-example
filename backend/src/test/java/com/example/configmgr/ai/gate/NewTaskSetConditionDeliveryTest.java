package com.example.configmgr.ai.gate;

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
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
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
 * 用户随后由模型给出合法 {@code set_condition} 时，旧实现的「新建任务首次条件」硬边界会在工具下发前
 * 把它拦成一张 {@code SET_CONDITIONS} 表（用户面零失败帧）—— 模型以为自己设了条件，
 * 用户看到的却是一张待填写的表。
 *
 * <p>口径：{@code WizardActionGuard} 已通过的 {@code set_condition} 是结构化事实，直接下发前端；
 * 宿主只在模型正文收尾或 {@code confirm_step} 推进时仍 UNDECIDED 才补发一次条件表
 * （见 {@code ConditionsForm#parkIfDueAtQueryCond} / {@code #conditionGate}）。
 */
class NewTaskSetConditionDeliveryTest {

	private static final String RUN_ID = "run-newtask-delivery";

	private static final String SESSION_ID = "s-newtask-delivery";

	private static final String CALL_ID = "call-set-condition";

	private static final long TASK_ID = 42L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final AiProperties properties = new AiProperties();

	private final TaskService taskService = mock(TaskService.class);

	private final DefinitionService definitionService = mock(DefinitionService.class);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("set_condition")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("set_condition")).thenReturn(ToolMeta.RiskLevel.WRITE);
		when(this.toolRegistry.categoryOf("set_condition")).thenReturn(ToolMeta.Category.U);
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY"));
		when(this.taskService.findById(TASK_ID)).thenReturn(task());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context().toMap());
		this.store.save(snapshot);
	}

	@Test
	void firstValidSetConditionOnNewTaskReachesTheFrontendWithoutHostForm() {
		assertThatThrownBy(() -> manager().executeToolCalls(prompt(), response()))
			.as("前端工具在生产 park 下以挂起交还线程")
			.isInstanceOf(RunParkedException.class);

		List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
		assertThat(pendings)
			.as("合法的结构化 set_condition 不得被换成宿主条件表")
			.noneMatch(pending -> pending != null && pending.getToolCallId() != null
					&& pending.getToolCallId().startsWith(ConditionsForm.TOOL_CALL_ID_PREFIX));
		PendingToolCall delivered = pendings.stream()
			.filter(pending -> pending != null && "set_condition".equals(pending.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("set_condition 没有下发前端（待决条目缺失）"));
		assertThat(delivered.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(delivered.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(this.store.archivedTypes(RUN_ID))
			.as("前端应收到 set_condition 的请求帧")
			.contains("frontend_tool_request");
	}

	private SpToolCallingManager manager() {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		WizardActionGuard guard = new WizardActionGuard(this.definitionService);
		SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of(guard));
		ConditionsForm form = new ConditionsForm(this.store, this.registry, gate, mock(MessageJsonCodec.class),
				this.properties, this.taskService);
		form.setWizardActionGuard(guard);
		manager.setConditionsForm(form);
		return manager;
	}

	/** 本轮新建任务（create_task 结算并绑定任务号），条件未决定：与真实 {@code create_task(conditions={})} 同形。 */
	private static AiContext context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L);
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
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "set_condition",
					"{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\","
							+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
