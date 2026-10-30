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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code QUERY_COND} 的条件收集是<b>宿主确定性</b>的：条件未决定时，模型正文收尾后宿主只挂
 * <b>一张</b>非必填条件表；条件已由结构化 {@code set_condition} 落库时一张都不挂。
 *
 * <p>锁两半（同一用例）：
 * <ol>
 * <li>任务条目 {@code conditionJson=null}（未决定）⇒ 第一次挂起一张 {@code SET_CONDITIONS} 表，
 *     第二次（同一缺口）不再挂 —— 用户只被问一次；</li>
 * <li>模型已 {@code set_condition}（条目里有合法条件 JSON）⇒ 不挂，直接可推进。</li>
 * </ol>
 *
 * <p><b>证红</b>：让 {@link ConditionsForm#parkIfDueAtQueryCond} 在入口直接返回 {@code false}
 * （旧路径：没有宿主条件表，缺口只靠模型自觉）⇒ 第 1 半红；让它忽略
 * {@code plan.undecided().isEmpty()}（无条件也挂）⇒ 第 2 半红。
 */
class QueryCondConditionFormParkTest {

	private static final String RUN_ID = "run-cond-form";

	private static final String SESSION_ID = "s-cond-form";

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

	private ConditionsForm form;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(anyString())).thenReturn(this.emitter);
		when(this.taskService.findById(TASK_ID)).thenAnswer(invocation -> this.taskState.get());
		this.form = new ConditionsForm(this.store, this.registry, this.gate, this.codec, this.properties,
				this.taskService);
		seedRun();
	}

	/**
	 * 2026-10-30 新口径：真实空对象回灌（用户直接提交 = 留空）按"明确不设筛选"收口 ——
	 * 条件事实落成 {@code "{}"}，且<b>不再弹第二张</b>（也不再要求模型补 set_condition）。
	 */
	@Test
	void blankFormResultPersistsExplicitUnsetAndNoSecondCard() {
		this.taskState.set(task(null));
		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("前置：条件未决定 ⇒ 宿主挂一张").isTrue();

		PendingToolCall answered = this.store.pending(RUN_ID, ConditionsForm.toolCallId(RUN_ID, TASK_ID));
		answered.setStatus(PendingToolCall.FRONTEND_RESULT);
		answered.setResultText("{}");

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("留空 = 明确不设筛选：落库后不再弹第二张").isFalse();
		verify(this.taskService).setCondition(TASK_ID, "CURRENCY", "{}");
	}

	@Test
	void unknownConditionParksExactlyOnceAndAKnownConditionNeverParks() {
		// ── 上半：条件未决定（条目 conditionJson=null）⇒ 只挂一次 ──
		this.taskState.set(task(null));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("QUERY_COND 条件未决定 ⇒ 宿主正文收尾时挂一次条件表").isTrue();

		PendingToolCall pending = this.store.pending(RUN_ID, ConditionsForm.toolCallId(RUN_ID, TASK_ID));
		assertThat(pending).as("确定性 toolCallId：同一缺口同一张卡").isNotNull();
		assertThat(pending.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(pending.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
		JsonNode formNode = parse(pending.getArguments()).path("form");
		assertThat(formNode.path("purpose").asText()).isEqualTo(FormPurposes.SET_CONDITIONS);
		assertThat(formNode.path("fields")).hasSize(1);
		assertThat(formNode.path("fields").get(0).path("type").asText()).as("非必填自由文本，不是多选/枚举")
			.isEqualTo("text");
		assertThat(formNode.path("fields").get(0).path("required").asBoolean()).isFalse();
		verify(this.emitter).suspended(org.mockito.ArgumentMatchers.eq(List.of(pending)), anyString(), anyString(),
				anyString());

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("同一缺口已挂过 ⇒ 不得再弹第二张").isFalse();

		// ── 下半：模型已 set_condition（条目里有条件）⇒ 一张都不挂 ──
		this.taskState.set(task("{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("条件已落库 ⇒ 不再收集").isFalse();
	}

	/**
	 * {@code create_task(conditions=…)} 的候选是<b>结构化事实</b>：复杂条件（范围键 + IN 多值 + 数值比较）
	 * 通过同一份 {@code WizardActionGuard} 校验后直接落库，<b>不弹条件表</b>。
	 */
	@Test
	void complexCreateTaskCandidateLandsWithoutForm() {
		this.taskState.set(task(null));
		doAnswer(invocation -> {
			String defCode = invocation.getArgument(1);
			String conditionJson = invocation.getArgument(2);
			this.taskState.get().getItems().stream()
				.filter(item -> defCode.equals(item.getDefCode()))
				.forEach(item -> item.setConditionJson(conditionJson));
			return null;
		}).when(this.taskService).setCondition(eq(TASK_ID), anyString(), anyString());
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code", "monthlyFee"));
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("scopeKeys", List.of("XN"));
		conditions.put("fields", List.of(
				Map.of("fieldCode", "code", "operator", "IN", "value", List.of("CNY", "USD")),
				Map.of("fieldCode", "monthlyFee", "operator", "GTE", "value", 100)));
		AiContext candidate = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of(
				RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID,
				RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L,
				RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"),
				RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, candidate, history()))
			.as("候选已由宿主按同一份校验落库 ⇒ 不再弹条件表").isFalse();
		verify(this.taskService).setCondition(eq(TASK_ID), eq("CURRENCY"), contains("\"operator\":\"IN\""));
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
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

	private void seedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history()));
		this.store.save(snapshot);
	}

	private static AiContext context() {
		return AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());
	}

	private static List<org.springframework.ai.chat.messages.Message> history() {
		return List.of(new SystemMessage("PERSONA"), new UserMessage("按金额筛一下"));
	}

	private static Task task(String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("条件表用例");
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

	private JsonNode parse(String json) {
		try {
			return this.mapper.readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException("不是合法 JSON：" + json, ex);
		}
	}

}
