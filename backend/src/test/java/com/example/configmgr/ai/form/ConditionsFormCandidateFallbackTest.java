package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CreateTaskDraftStore;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolOutcome;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ConditionsForm} 的候选<b>回落</b>：运行上下文 {@code extra} 缺失时，候选仍须从
 * <b>本轮已结算的 {@code create_task} 原始入参</b>或<b>会话级 Redis 草案</b>恢复。
 *
 * <p>锁三件事（两条来源同一口径）：
 * <ol>
 * <li>合法候选复用前端 {@code set_condition} 通道生成<b>页面草案</b>（确定性 toolCallId）；</li>
 * <li><b>不弹</b> {@code SET_CONDITIONS} 表单 —— 用户已经说过条件，不该被再问一次；</li>
 * <li><b>不写业务库</b> —— 真正的条件提交只属于前端 {@code persistStepData}。</li>
 * </ol>
 */
class ConditionsFormCandidateFallbackTest {

	private static final String RUN_ID = "run-fallback";

	private static final String SESSION_ID = "s-fallback";

	private static final long TASK_ID = 42L;

	private static final String DRAFT_ID = ConditionsForm.draftToolCallId(RUN_ID, TASK_ID, "CURRENCY");

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final AiProperties properties = new AiProperties();

	private final TaskService taskService = mock(TaskService.class);

	private final DefinitionService definitionService = mock(DefinitionService.class);

	private final CreateTaskDraftStore draftStore = mock(CreateTaskDraftStore.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	private ConditionsForm form;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(anyString())).thenReturn(this.emitter);
		when(this.taskService.findById(TASK_ID)).thenAnswer(invocation -> this.taskState.get());
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.form = new ConditionsForm(this.store, this.registry, this.gate, this.codec, this.properties,
				this.taskService);
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));
		seedRun();
	}

	@Test
	void missingExtraFallsBackToTheSettledCreateTaskArguments() {
		this.taskState.set(task(null));
		PendingToolCall created = new PendingToolCall();
		created.setToolCallId("call-create");
		created.setName(AiToolNames.CREATE_TASK);
		created.setKind(PendingToolCall.KIND_BACKEND);
		created.setStatus(PendingToolCall.FRONTEND_RESULT);
		created.setOutcome(ToolOutcome.OK.wire());
		created.setResultText("已创建导出任务 #" + TASK_ID + "（当前步骤：QUERY_COND）");
		created.setArguments("{\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"code\","
				+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}}");
		this.store.seedPending(RUN_ID, created);

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("extra 缺失 ⇒ 从本轮已结算的 create_task 入参恢复候选并挂页面草案").isTrue();

		assertDraftParkedWithoutFormOrDbWrite();
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("create_task 条目仍在（候选来源不改写工具台账）")
			.anyMatch(pending -> "call-create".equals(pending.getToolCallId()));
	}

	@Test
	void missingExtraFallsBackToTheSessionDraft() {
		this.taskState.set(task(null));
		when(this.draftStore.read(RUN_ID, TASK_ID)).thenReturn(new CreateTaskDraftStore.Draft(List.of("CURRENCY"),
				conditions("CNY"), System.currentTimeMillis()));
		this.form.setCreateTaskDraftStore(this.draftStore);

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("extra 与本轮入参都没有 ⇒ 从会话级草案恢复候选并挂页面草案").isTrue();

		assertDraftParkedWithoutFormOrDbWrite();
		verify(this.draftStore).read(RUN_ID, TASK_ID);
	}

	private void assertDraftParkedWithoutFormOrDbWrite() {
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("不得弹 SET_CONDITIONS 表单").noneMatch(
					pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));

		PendingToolCall draft = this.store.pending(RUN_ID, DRAFT_ID);
		assertThat(draft).as("确定性 toolCallId 的 set_condition 页面草案").isNotNull();
		assertThat(draft.getName()).isEqualTo(AiToolNames.SET_CONDITION);
		assertThat(draft.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(draft.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(draft.getArguments()).contains("\"defCode\":\"CURRENCY\"")
			.contains("\"operator\":\"EQ\"")
			.contains("\"value\":\"CNY\"");
		verify(this.emitter).suspended(eq(List.of(draft)), anyString(), anyString(), anyString());
	}

	private void seedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history()));
		this.store.save(snapshot);
	}

	private static AiContext context() {
		return AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());
	}

	private static List<Message> history() {
		return List.of(new SystemMessage("PERSONA"), new UserMessage("按金额筛一下"));
	}

	private static Task task(String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("条件回落用例");
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

	private static Map<String, Object> conditions(String value) {
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("fieldCode", "code");
		field.put("operator", "EQ");
		field.put("value", value);
		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(field));
		return conditions;
	}

	private static ConfigDefinition definition(String code, String... fieldCodes) {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setCode(code);
		definition.setName("货币字典");
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

}
