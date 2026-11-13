package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolDefinitionOverride;
import com.example.configmgr.ai.tool.YamlToolDefinitionSource;
import com.example.configmgr.ai.tools.AiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 当前任务类型、条件模式与批量条件工具的文字契约；不断言真实模型行为。 */
class FlowTypeConditionPromptContractTest {

	private static final String INDEPENDENT_SLOTS =
			"defCodes 与 conditions 独立判断，空条件不能成为省略已知配置的理由";
	private static final String UNIQUE_MAPPING =
			"用户提及的配置编码、名称或别名能唯一映射到配置定义时必须传入对应 defCode；只有未提及或无法唯一映射时才省略";
	private static final String COMPACT_CONDITION = "紧凑修饰也可构成条件，不要求出现“筛选”“条件”或等号关键词";
	private static final String COMPACT_CONDITION_BOUNDARY =
			"只有能唯一确定所属配置、字段、算子和值/范围时才视为已知条件；无法唯一确定仍按未决定，不得猜测";
	private static final String MATERIALIZE_KNOWN_SLOTS =
			"已知槽位不得推迟到后续表单，后续 select_definitions/set_conditions 只物化草案";
	private static final String SINGLE_CONDITIONED_DEFINITION = "非空 conditions 只允许一个 defCode";
	private static final String SINGLE_CONFIG_KNOWN_CONDITION =
			"单配置且条件已知时 defCodes=[该项] 并传非空 conditions";
	private static final String MULTI_CONFIG_KNOWN_CONDITION =
			"多配置且条件已知时保留全部已知 defCodes，创建载荷 conditions={}，随后一次 set_conditions(items) 物化逐项条件";
	private static final String MULTI_CONFIG_EMPTY_CONDITIONS =
			"此时 {} 只表示创建载荷不承载逐项条件，不表示用户条件未知，更不是明确不设";
	private static final String MULTI_CONFIG_SHARED_OR_DISTINCT = "多配置不同或相同条件均走该分支";

	@Test
	void promptDeclaresTaskTypeFormInsteadOfTextQuestion() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).contains("TASK_TYPE")
				.contains("单个 enum 单选")
				.contains("EXPORT/IMPORT")
				.contains("不要用文字反问类型")
				.contains("类型已确定")
				.contains("直接调用 create_task");
	}

	@Test
	void capabilityRulesDeclareHostConditionModesAndDirectAdvance() {
		String rules = PromptRulePacks.render(Set.of(AiToolNames.SET_CONDITIONS, AiToolNames.CONFIRM_STEP));

		assertThat(rules).contains("conditionMode=ALL/CUSTOM")
				.contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
				.contains("CUSTOM 非空文本才由模型结构化")
				.contains("一次 set_conditions(items)")
				.doesNotContain("单个非必填自由文本框")
				.doesNotContain("逐项各调用一次");
	}

	@Test
	void toolDescriptionsAgreeOnConditionModesAndBatchDrafts() throws NoSuchMethodException {
		YamlToolDefinitionSource source = new YamlToolDefinitionSource();
		List<ToolDefinitionOverride> overrides = List.of(source.load().find(AiToolNames.CREATE_TASK),
				source.load().find(AiToolNames.CONFIRM_STEP), source.load().find(AiToolNames.GENERATIVE_FORM));
		for (ToolDefinitionOverride override : overrides) {
			assertThat(override.description()).contains("conditionMode=ALL/CUSTOM")
					.contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
					.doesNotContain("单个非必填自由文本框");
		}

		Method confirm = AiTools.class.getMethod("confirmStep", String.class);
		Method form = AiTools.class.getMethod("generativeForm", AiTools.FormSpec.class);
		for (Method method : List.of(createTaskMethod(), confirm, form)) {
			Tool annotation = method.getAnnotation(Tool.class);
			assertThat(annotation.description()).contains("conditionMode=ALL/CUSTOM")
					.contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
					.doesNotContain("单个非必填自由文本框");
		}

		ToolDefinitionOverride batch = source.load().find(AiToolNames.SET_CONDITIONS);
		assertThat(batch.description()).contains("items 是 1..N 非空数组")
				.contains("conditionMode=ALL 或 CUSTOM 留空后直接 confirm_step");
	}

	@Test
	void promptDeclaresConditionChainBatchAndIdempotencyRules() {
		String rules = PromptRulePacks.render(Set.of(AiToolNames.CONFIRM_STEP, AiToolNames.SET_CONDITIONS));

		assertThat(rules).contains("confirm_step 必须单独成批")
				.contains("一次 set_conditions(items)")
				.contains("不得拆成多次调用")
				.contains("只有完整有序 items 相同才幂等");
	}

	@Test
	void createSlotRulesStaySynchronizedAcrossPromptRulePackYamlAndAnnotation() throws NoSuchMethodException {
		for (String description : createTaskDescriptions()) {
			assertThat(description).contains(INDEPENDENT_SLOTS, UNIQUE_MAPPING, COMPACT_CONDITION,
					MATERIALIZE_KNOWN_SLOTS, SINGLE_CONDITIONED_DEFINITION)
					.contains("无法唯一映射时保留宿主配置选择卡");
		}
	}

	@Test
	void createSlotRulesCoverSingleAndMultiConfigConditionBranches() throws NoSuchMethodException {
		for (String description : createTaskDescriptions()) {
			assertThat(description).contains(SINGLE_CONFIG_KNOWN_CONDITION, MULTI_CONFIG_KNOWN_CONDITION,
					MULTI_CONFIG_EMPTY_CONDITIONS, MULTI_CONFIG_SHARED_OR_DISTINCT, COMPACT_CONDITION_BOUNDARY);
		}
	}

	@Test
	void createTaskParametersRepeatIndependentSlotRules() throws NoSuchMethodException {
		Parameter[] parameters = createTaskMethod().getParameters();
		ToolParam defCodes = parameters[2].getAnnotation(ToolParam.class);
		ToolParam conditions = parameters[3].getAnnotation(ToolParam.class);

		assertThat(defCodes.description()).contains(INDEPENDENT_SLOTS, UNIQUE_MAPPING, MATERIALIZE_KNOWN_SLOTS,
				SINGLE_CONFIG_KNOWN_CONDITION, MULTI_CONFIG_KNOWN_CONDITION)
				.contains("无法唯一映射时保留宿主配置选择卡");
		assertThat(conditions.description()).contains(INDEPENDENT_SLOTS, COMPACT_CONDITION, COMPACT_CONDITION_BOUNDARY,
				MATERIALIZE_KNOWN_SLOTS, SINGLE_CONDITIONED_DEFINITION, SINGLE_CONFIG_KNOWN_CONDITION,
				MULTI_CONFIG_KNOWN_CONDITION, MULTI_CONFIG_EMPTY_CONDITIONS, MULTI_CONFIG_SHARED_OR_DISTINCT);
	}

	@Test
	void toolsVersionIsBumpedForTheNewDescriptions() {
		assertThat(new YamlToolDefinitionSource().version()).isEqualTo("2026-10-27.40");
	}

	private static List<String> createTaskDescriptions() throws NoSuchMethodException {
		return List.of(ResilientChatService.SYSTEM_PROMPT,
				PromptRulePacks.render(Set.of(AiToolNames.CREATE_TASK, AiToolNames.SET_CONDITIONS)),
				new YamlToolDefinitionSource().load().find(AiToolNames.CREATE_TASK).description(),
				createTaskMethod().getAnnotation(Tool.class).description());
	}

	private static Method createTaskMethod() throws NoSuchMethodException {
		return AiTools.class.getMethod("createTask", String.class, String.class, String[].class,
				AiTools.FieldQueryCondition.class, Boolean.class, Boolean.class,
				org.springframework.ai.chat.model.ToolContext.class);
	}
}
