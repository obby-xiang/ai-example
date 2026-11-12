package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolDefinitionOverride;
import com.example.configmgr.ai.tool.YamlToolDefinitionSource;
import com.example.configmgr.ai.tools.AiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 当前任务类型、条件模式与批量条件工具的文字契约；不断言真实模型行为。 */
class FlowTypeConditionPromptContractTest {

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

		Method create = AiTools.class.getMethod("createTask", String.class, String.class, String[].class,
				AiTools.FieldQueryCondition.class, Boolean.class, Boolean.class,
				org.springframework.ai.chat.model.ToolContext.class);
		Method confirm = AiTools.class.getMethod("confirmStep", String.class);
		Method form = AiTools.class.getMethod("generativeForm", AiTools.FormSpec.class);
		for (Method method : List.of(create, confirm, form)) {
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
	void toolsVersionIsBumpedForTheNewDescriptions() {
		assertThat(new YamlToolDefinitionSource().version()).isEqualTo("2026-10-27.39");
	}
}
