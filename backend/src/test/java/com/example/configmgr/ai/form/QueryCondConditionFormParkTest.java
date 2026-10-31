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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
	 * 留空回灌只表示"明确不设筛选"，<b>后端零业务写</b>：由前端在离开 QUERY_COND 的
	 * {@code persistStepData} 提交 {@code "{}"}。宿主也不再弹第二张。
	 */
	@Test
	void blankFormResultAllowsAdvanceWithoutBackendWrite() {
		this.taskState.set(task(null));
		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("前置：条件未决定 ⇒ 宿主挂一张").isTrue();

		PendingToolCall answered = this.store.pending(RUN_ID, ConditionsForm.toolCallId(RUN_ID, TASK_ID));
		answered.setStatus(PendingToolCall.FRONTEND_RESULT);
		answered.setResultText("{}");

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history()))
			.as("留空 = 明确不设筛选：不再弹第二张").isFalse();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
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
	 * 通过同一份 {@code WizardActionGuard} 校验后，复用前端 {@code set_condition} 通道生成<b>页面草案</b>：
	 * 不弹条件表、不写业务库（真正的提交由前端 {@code persistStepData} 在下一步完成）。
	 */
	@Test
	void createTaskCandidateDispatchesSetConditionDraftWithoutFormOrDbWrite() {
		this.taskState.set(task(null));
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
			.as("候选合法 ⇒ 挂起 set_condition 草案（不是条件表）").isTrue();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("不得弹 SET_CONDITIONS 表单").noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));

		String draftId = "host-cond-draft-" + RUN_ID + "-" + TASK_ID + "-CURRENCY";
		PendingToolCall draft = this.store.pending(RUN_ID, draftId);
		assertThat(draft).as("确定性 toolCallId = 幂等").isNotNull();
		assertThat(draft.getName()).isEqualTo("set_condition");
		assertThat(draft.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(draft.getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(draft.getArguments()).contains("\"defCode\":\"CURRENCY\"").contains("\"operator\":\"IN\"");
		verify(this.emitter).suspended(eq(List.of(draft)), anyString(), anyString(), anyString());
	}

	/** 已知 CNY 候选：零条件表、零 TaskService.setCondition，只下发一张 set_condition 草案。 */
	@Test
	void knownCnyCandidateNeverAsksAndNeverWritesTheBusinessDatabase() {
		this.taskState.set(task(null));
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")));
		AiContext candidate = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of(
				RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID,
				RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L,
				RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"),
				RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, candidate, history())).isTrue();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("已知 CNY 候选不弹条件表").noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		assertThat(this.store.pending(RUN_ID, "host-cond-draft-" + RUN_ID + "-" + TASK_ID + "-CURRENCY"))
			.as("复用前端 set_condition 通道生成页面草案").isNotNull();
	}

	/**
	 * 刚进入 {@code QUERY_COND} 的首次交棒（{@code allowUnknownForm=false}）：条件仍未知时宿主<b>不抢答</b>，
	 * 先交棒模型按原对话做结构化；已落库的条件在两种交棒口径下都保持静默。
	 */
	@Test
	void unknownConditionDoesNotParkOnTheFirstHandoff() {
		this.taskState.set(task(null));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history(), false))
			.as("首次交棒 + 条件未知 ⇒ 交棒模型，宿主不弹表").isFalse();
		assertThat(this.store.pendingsLenient(RUN_ID)).as("不留下任何条件表/草案").isEmpty();
		verify(this.emitter, never()).suspended(any(), anyString(), anyString(), anyString());

		this.taskState.set(task("{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));
		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history(), false))
			.as("条件已落库：首次交棒口径同样不收集").isFalse();
	}

	/**
	 * {@code allowUnknownForm=false} 只暂缓<b>未知条件</b>的宿主收集：已知 {@code create_task} 候选
	 * 仍照旧下发 {@code set_condition} 草案（不弹表、不写库）。
	 */
	@Test
	void knownCnyCandidateStillDispatchesTheDraftWhenUnknownFormIsDisallowed() {
		this.taskState.set(task(null));
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")));
		AiContext candidate = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of(
				RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID,
				RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L,
				RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"),
				RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, candidate, history(), false))
			.as("已知 CNY 候选在首次交棒口径下仍下发草案").isTrue();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("已知候选不弹条件表").noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
		assertThat(this.store.pending(RUN_ID, "host-cond-draft-" + RUN_ID + "-" + TASK_ID + "-CURRENCY"))
			.as("复用前端 set_condition 通道生成页面草案").isNotNull();
	}

	/** 多 defCodes 候选：每个配置项各一张草案，仍不弹条件表、不写库。 */
	@Test
	void multiDefCodeCandidateDispatchesOneDraftPerItem() {
		Task task = task(null);
		TaskItem second = new TaskItem();
		second.setTaskId(TASK_ID);
		second.setDefCode("RATE");
		task.getItems().add(second);
		this.taskState.set(task);
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		when(this.definitionService.findByCode("RATE")).thenReturn(definition("RATE", "code"));
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")));
		AiContext candidate = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of(
				RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID,
				RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L,
				RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY", "RATE"),
				RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, candidate, history())).isTrue();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID)).filteredOn(pending -> "set_condition".equals(pending.getName()))
			.extracting(PendingToolCall::getToolCallId)
			.containsExactlyInAnyOrder(
					"host-cond-draft-" + RUN_ID + "-" + TASK_ID + "-CURRENCY",
					"host-cond-draft-" + RUN_ID + "-" + TASK_ID + "-RATE");
	}

	/**
	 * 候选绑定错误/校验失败不得转化成"再问用户一次"：零表单、零业务写，只留 WARN。
	 */
	@Test
	void invalidCreateTaskCandidateFailsWithoutFormOrDbWrite() {
		this.taskState.set(task(null));
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(Map.of("fieldCode", "unknownField", "operator", "EQ", "value", "CNY")));
		AiContext candidate = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of(
				RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, TASK_ID,
				RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L,
				RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"),
				RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, candidate, history()))
			.as("内部失败口径：不交代表单、不弹表").isFalse();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
		assertThat(this.store.pendingsLenient(RUN_ID)).isEmpty();
	}

	@Test
	void invalidCandidateBindingFailsWithoutFormOrDbWrite() {
		this.taskState.set(task(null));
		when(this.definitionService.findByCode("CURRENCY")).thenReturn(definition("CURRENCY", "code"));
		this.form.setWizardActionGuard(new WizardActionGuard(this.definitionService));

		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")));
		AiContext otherTask = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of(
				RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, 99L,
				RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L,
				RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of("CURRENCY"),
				RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions));

		assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, otherTask, history())).isFalse();
		verify(this.taskService, never()).setCondition(anyLong(), anyString(), anyString());
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
