package com.example.configmgr.ai.run;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolDefinitionOverride;
import com.example.configmgr.ai.tool.YamlToolDefinitionSource;
import com.example.configmgr.ai.tools.AiTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * create_task 的 openWizard 默认/例外语义。这里只验证模型实际可见的文本契约，
 * 不把提示词契约测试当作真实模型行为测试。
 */
class CreateTaskOpenWizardPromptContractTest {

	private static final String CREATION_IS_NOT_CREATE_ONLY = "“创建任务”只是流程入口，不等于“只创建”";
	private static final String DEFAULT_CONTINUATION =
			"未明确要求限制后续配置、打开或启动时，默认完整续接，openWizard 省略或传 true";
	private static final String EXPLICIT_CREATE_ONLY =
			"只有用户明确把本次意图限定为创建后停止（不继续配置、不打开向导、不启动后续流程）时，才传 openWizard=false";

	@Test
	void capabilityRulesDefaultToContinuationAndRequireExplicitCreateOnlyIntent() {
		String rules = PromptRulePacks.render(Set.of(AiToolNames.CREATE_TASK));

		assertThat(rules).contains(CREATION_IS_NOT_CREATE_ONLY);
		assertThat(rules).contains(DEFAULT_CONTINUATION);
		assertThat(rules).contains(EXPLICIT_CREATE_ONLY);
		assertThat(rules).contains("宿主不得自动导航、代选或发配置表单");
	}

	@Test
	void toolDefinitionsAndParameterShareTheSameDefaultAndException() throws NoSuchMethodException {
		ToolDefinitionOverride override = new YamlToolDefinitionSource().load().find(AiToolNames.CREATE_TASK);
		assertThat(override).isNotNull();
		assertThat(override.hasDescription()).isTrue();

		Method method = AiTools.class.getMethod("createTask", String.class, String.class, String[].class,
				AiTools.FieldQueryCondition.class, Boolean.class, Boolean.class, ToolContext.class);
		Tool tool = method.getAnnotation(Tool.class);
		assertThat(tool).isNotNull();

		for (String description : List.of(override.description(), tool.description())) {
			assertThat(description).contains(CREATION_IS_NOT_CREATE_ONLY);
			assertThat(description).contains(DEFAULT_CONTINUATION);
			assertThat(description).contains(EXPLICIT_CREATE_ONLY);
			assertThat(description).contains("宿主不得自动导航、代选或发配置表单");
		}

		Parameter openWizard = method.getParameters()[5];
		ToolParam param = openWizard.getAnnotation(ToolParam.class);
		assertThat(param).isNotNull();
		assertThat(param.required()).isFalse();
		assertThat(param.description()).contains(CREATION_IS_NOT_CREATE_ONLY);
		assertThat(param.description()).contains("默认完整续接，省略或传 true");
		assertThat(param.description()).contains(EXPLICIT_CREATE_ONLY);
		assertThat(new YamlToolDefinitionSource().version()).isEqualTo("2026-10-27.28");
	}
}
