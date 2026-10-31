package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>条件结构化交棒的标记写入口径</b>：{@code ConditionsForm} 只在
 * {@code allowUnknownForm=false} <b>且条件确实未决定</b>时给运行上下文
 * {@code extra[EXTRA_CONDITION_STRUCTURE_HANDOFF]=true}。
 *
 * <p>已知候选（{@code create_task(conditions=…)} 已下发成 {@code set_condition} 页面草案）、
 * 已落库条件都不置标记 —— 否则下一跳会无谓地收窄工具面，把模型锁在"只能调四个工具"的现场。
 */
class ConditionsFormStructureHandoffTest {

	private static final String RUN_ID = "run-structure-handoff";

	private static final String SESSION_ID = "s-structure-handoff";

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

	// ── ① 真正 UNDECIDED + allowUnknownForm=false：置标记，不弹表 ────────────

	@Test
	void trulyUndecidedConditionMarksTheStructuredHandoff() {
		this.taskState.set(task(null));
		AiContext context = context();

		boolean parked = this.form.parkIfDueAtQueryCond(RUN_ID, context, history(), false);

		assertThat(parked).as("首次交棒不替模型决定未知条件").isFalse();
		assertThat(context.getExtra()).containsEntry(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE);
		assertThat(this.store.pendingsLenient(RUN_ID)).as("未决定也不弹条件表").isEmpty();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ② 已知候选 + allowUnknownForm=false：下发草案，不置标记 ─────────────

	@Test
	void knownCandidateNeverMarksTheStructuredHandoff() {
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
		AiContext context = context();

		boolean parked = this.form.parkIfDueAtQueryCond(RUN_ID, context, history(), false);

		assertThat(parked).as("已知候选挂页面草案").isTrue();
		assertThat(this.store.pending(RUN_ID, DRAFT_ID)).as("草案走既有 set_condition 通道").isNotNull();
		assertThat(context.getExtra())
			.as("条件已知 ⇒ 不得收窄下一跳工具面")
			.doesNotContainKey(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF);
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
	}

	// ── ③ 已落库条件 + allowUnknownForm=false：直接推进，不置标记 ───────────

	@Test
	void settledConditionNeverMarksTheStructuredHandoff() {
		this.taskState.set(task("{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));
		AiContext context = context();

		boolean parked = this.form.parkIfDueAtQueryCond(RUN_ID, context, history(), false);

		assertThat(parked).as("条件已落库 ⇒ 直接推进").isFalse();
		assertThat(context.getExtra()).doesNotContainKey(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF);
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
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
		task.setTitle("条件结构化交棒用例");
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
