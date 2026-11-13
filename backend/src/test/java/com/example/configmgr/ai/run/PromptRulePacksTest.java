package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolDefinitionOverride;
import com.example.configmgr.ai.tool.YamlToolDefinitionSource;
import com.example.configmgr.ai.tools.AiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRulePacksTest {

	@Test
	void createTaskRulesRequireSingleConditionedDefinitionAndBatchFollowUp() {
		String rules = PromptRulePacks.render(Set.of(AiToolNames.CREATE_TASK, AiToolNames.SET_CONDITIONS));

		assertThat(rules).contains("非空 conditions 只允许一个 defCode")
				.contains("defCodes 与 conditions 独立判断，空条件不能成为省略已知配置的理由")
				.contains("用户提及的配置编码、名称或别名能唯一映射到配置定义时必须传入对应 defCode；只有未提及或无法唯一映射时才省略")
				.contains("无法唯一映射时保留宿主配置选择卡")
				.contains("紧凑修饰也可构成条件，不要求出现“筛选”“条件”或等号关键词")
				.contains("只有能唯一确定所属配置、字段、算子和值/范围时才视为已知条件；无法唯一确定仍按未决定，不得猜测")
				.contains("单配置且条件已知时 defCodes=[该项] 并传非空 conditions")
				.contains("多配置且条件已知时保留全部已知 defCodes，创建载荷 conditions={}，随后一次 set_conditions(items) 物化逐项条件")
				.contains("此时 {} 只表示创建载荷不承载逐项条件，不表示用户条件未知，更不是明确不设")
				.contains("多配置不同或相同条件均走该分支")
				.contains("已知槽位不得推迟到后续表单，后续 select_definitions/set_conditions 只物化草案")
				.doesNotContain("多个配置项的不同条件必须在创建后通过一次 set_conditions(items) 给出")
				.doesNotContain("逐项各调用一次");
	}

	@Test
	void conditionRulesUseHostModeAndSingleBatchWithoutPerItemCalls() {
		String rules = PromptRulePacks.render(Set.of(AiToolNames.SET_CONDITIONS, AiToolNames.CONFIRM_STEP));

		assertThat(rules).contains("conditionMode=ALL/CUSTOM")
				.contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
				.contains("一次 set_conditions(items)")
				.doesNotContain("create_task 的 {} 只表示条件未决定")
				.doesNotContain("逐项各调用一次")
				.doesNotContain("单个非必填自由文本框");
	}

	@Test
	void toolDescriptionsAndYamlUseCurrentConditionWorkflowAndVersion() throws NoSuchMethodException {
		YamlToolDefinitionSource yamlSource = new YamlToolDefinitionSource();
		List<ToolDefinitionOverride> overrides = List.of(yamlSource.load().find(AiToolNames.CREATE_TASK),
				yamlSource.load().find(AiToolNames.CONFIRM_STEP));
		List<Tool> annotations = List.of(
				AiTools.class.getMethod("createTask", String.class, String.class, String[].class,
						AiTools.FieldQueryCondition.class, Boolean.class, Boolean.class,
						org.springframework.ai.chat.model.ToolContext.class).getAnnotation(Tool.class),
				AiTools.class.getMethod("confirmStep", String.class).getAnnotation(Tool.class));

		for (ToolDefinitionOverride override : overrides) {
			assertThat(override.description()).contains("conditionMode=ALL/CUSTOM")
					.contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
					.doesNotContain("单个非必填自由文本框");
		}
		for (Tool annotation : annotations) {
			assertThat(annotation.description()).contains("conditionMode=ALL/CUSTOM")
					.contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
					.doesNotContain("单个非必填自由文本框");
		}
		assertThat(yamlSource.version()).isEqualTo("2026-10-27.40");
	}
}
