package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.ToolPreflightService;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.slot.ConditionAssessment;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.WizardActionGuard;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSteps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>真实业务库上的条件边界</b>：AI 的 {@code create_task(conditions=…)} 候选只能变成宿主
 * {@code set_condition} 页面草案（{@code TaskItem.conditionJson} 仍为 null、零
 * {@code TaskService#setCondition} 写入）；只有模拟前端 {@code persistStepData} 真正提交同形 JSON 后，
 * 条件工作流与 {@code start_export} 预检才读到 {@code SET}。
 *
 * <p>本用例使用 H2 + 真实 {@link TaskService}/{@link DefinitionService}/{@link ConditionsForm}；
 * 仅 Redis 外置面用 {@link InMemoryRunStore} 替代（生产语义已在帧/续跑用例覆盖）。
 */
@SpringBootTest
@Import(ConditionDraftBoundaryTest.StoreConfig.class)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:cond-draft-boundary;DB_CLOSE_DELAY=-1"
})
class ConditionDraftBoundaryTest {

	private static final String RUN_ID = "run-cond-draft-boundary";

	private static final String SESSION_ID = "s-cond-draft-boundary";

	private static final String DEF = "IT_COND_BOUNDARY";

	private static final String CONDITION_JSON = "{\"fields\":[{\"fieldCode\":\"code\","
			+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}";

	@TestConfiguration
	static class StoreConfig {

		@Bean
		@Primary
		InMemoryRunStore inMemoryRunStore() {
			return new InMemoryRunStore();
		}
	}

	@Autowired
	private TaskService taskService;

	@Autowired
	private DefinitionService definitionService;

	@Autowired
	private ConfigDefinitionRepository definitionRepository;

	@Autowired
	private ToolPreflightService preflightService;

	@Autowired
	private InMemoryRunStore store;

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final AiProperties properties = new AiProperties();

	private Long taskId;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		this.definitionRepository.findByCode(DEF).ifPresent(this.definitionRepository::delete);
		this.definitionRepository.save(definition());

		Task task = this.taskService.create(Task.TaskType.EXPORT, "条件草案边界测试 " + System.nanoTime());
		this.taskId = task.getId();
		this.taskService.selectDefs(this.taskId, List.of(DEF));
		this.taskService.goToStep(this.taskId, TaskSteps.QUERY_COND);
	}

	@AfterEach
	void tearDown() {
		if (this.taskId != null) {
			try {
				this.taskService.delete(this.taskId);
			}
			catch (RuntimeException ex) {
				// 用例失败时的清理不掩盖首个失败：任务/定义前缀唯一，残留不会污染其它用例。
			}
		}
		this.definitionRepository.findByCode(DEF).ifPresent(this.definitionRepository::delete);
	}

	@Test
	void candidateBecomesDraftWithoutWritingTheBusinessDatabase() {
		AiContext context = candidateContext();
		seedRun(context);

		assertThat(conditionsForm().parkIfDueAtQueryCond(RUN_ID, context, history())).isTrue();

		PendingDraft draft = onlyDraft();
		assertThat(draft.toolCallId()).isEqualTo("host-cond-draft-" + RUN_ID + "-" + this.taskId + "-" + DEF);
		assertThat(draft.arguments()).contains("\"defCode\":\"" + DEF + "\"").contains("\"operator\":\"EQ\"");

		TaskItem item = taskItemFromDatabase();
		assertThat(item.getConditionJson()).as("草案不得写业务库：条件仍为未决定").isNull();
		assertThat(ConditionAssessment.of(this.taskService.findById(this.taskId)).stateOf(DEF))
			.as("真实库读回仍是 UNDECIDED").isEqualTo(ConditionAssessment.State.UNDECIDED);
	}

	@Test
	void frontendPersistStepDataMakesTheSameJsonReadableAsSet() {
		AiContext context = candidateContext();
		seedRun(context);
		assertThat(conditionsForm().parkIfDueAtQueryCond(RUN_ID, context, history())).isTrue();
		onlyDraft();

		// 模拟前端离开 QUERY_COND 时的 persistStepData：同形 JSON 提交到真实业务库
		this.taskService.setCondition(this.taskId, DEF, CONDITION_JSON);

		Task persisted = this.taskService.findById(this.taskId);
		assertThat(taskItemFromDatabase().getConditionJson()).isEqualTo(CONDITION_JSON);
		assertThat(ConditionAssessment.of(persisted).stateOf(DEF)).isEqualTo(ConditionAssessment.State.SET);

		ExportConditionWorkflow.Plan plan = new ExportConditionWorkflow(this.taskService).plan(persisted);
		assertThat(plan.malformed()).isFalse();
		assertThat(plan.undecided()).as("真实库条件已 SET ⇒ 工作流不再收集").isEmpty();

		this.taskService.goToStep(this.taskId, TaskSteps.EXPORT);
		AiContext atExport = AiContext.of("export", "EXPORT", TaskSteps.EXPORT, this.taskId, "ACTIVE", Map.of());
		ToolPreflightService.PreflightReport report = this.preflightService.inspect(AiToolNames.START_EXPORT,
				"{\"taskId\":" + this.taskId + "}", atExport);
		ToolPreflightService.PreflightReport.Check conditionCheck = report.checks().stream()
			.filter(check -> ToolPreflightService.CheckCode.CONDITIONS_DECIDED.code().equals(check.code()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("预检没有 CONDITIONS_DECIDED 检查项：" + report));
		assertThat(conditionCheck.pass()).as("预检读真实库条件为已决定").isTrue();
		assertThat(report.pass()).as("条件已 SET 后 start_export 预检通过").isTrue();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private ConditionsForm conditionsForm() {
		ConditionsForm form = new ConditionsForm(this.store, this.registry, this.gate, this.codec, this.properties,
				this.taskService);
		form.setWizardActionGuard(new WizardActionGuard(this.definitionService));
		return form;
	}

	private AiContext candidateContext() {
		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(Map.of("fieldCode", "code", "operator", "EQ", "value", "CNY")));
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_TASK_ID, this.taskId);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_REQUESTED_AT, 1L);
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES, List.of(DEF));
		extra.put(RunContextRefresher.EXTRA_CREATE_TASK_CONDITIONS, conditions);
		return AiContext.of("export", "EXPORT", TaskSteps.QUERY_COND, this.taskId, "ACTIVE", extra);
	}

	private void seedRun(AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history()));
		this.store.save(snapshot);
	}

	private static List<Message> history() {
		return List.of(new UserMessage("导出 CNY"));
	}

	private PendingDraft onlyDraft() {
		return this.store.pendingsLenient(RUN_ID).stream()
			.filter(pending -> AiToolNames.SET_CONDITION.equals(pending.getName()))
			.findFirst()
			.map(pending -> new PendingDraft(pending.getToolCallId(), pending.getArguments()))
			.orElseThrow(() -> new AssertionError("宿主没有下发 set_condition 草案：" + this.store.pendingsLenient(RUN_ID)));
	}

	private TaskItem taskItemFromDatabase() {
		return this.taskService.findById(this.taskId).getItems().stream()
			.filter(item -> DEF.equals(item.getDefCode()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("任务条目缺失：" + DEF));
	}

	private ConfigDefinition definition() {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setCode(DEF);
		definition.setName("条件草案边界测试");
		definition.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
		definition.setSortOrder(999);
		List<ConfigField> fields = new ArrayList<>();
		fields.add(field("code", "编码", ConfigField.FieldType.STRING, true, true));
		definition.setFields(fields);
		return definition;
	}

	private static ConfigField field(String code, String label, ConfigField.FieldType type, boolean required,
			boolean key) {
		ConfigField field = new ConfigField();
		field.setDefCode(DEF);
		field.setCode(code);
		field.setLabel(label);
		field.setFieldType(type);
		field.setRequired(required);
		field.setKey(key);
		return field;
	}

	private record PendingDraft(String toolCallId, String arguments) {
	}

}
