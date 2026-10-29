package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.ToolDefinitionOverride;
import com.example.configmgr.ai.tool.YamlToolDefinitionSource;
import com.example.configmgr.ai.tools.AiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务类型与筛选条件收集口径（2026-10-29 闭环批）的<b>提示词/工具描述/版本契约</b>。
 *
 * <p>锁三面：
 * <ol>
 * <li>系统提示词：类型未知必须用表单（{@code purpose=TASK_TYPE}，单个 enum 单选）而不是文字反问；
 * 条件只在 {@code QUERY_COND} 用一个非必填自由文本框收集，留空即全量推进；{@code EXPORT} 及之后禁止条件表单。</li>
 * <li>工具描述：注解与 YAML 同批改（YAML 是可选覆盖，写反了模型看到两套口径）。</li>
 * <li>版本：改描述必须发新 version（破 prompt 缓存）。</li>
 * </ol>
 *
 * <p><b>证红</b>：提示词删掉 TASK_TYPE 段 ⇒ 第 1 条红；删掉条件单框/留空/EXPORT 后禁止 ⇒ 第 2 条红；
 * 注解或 YAML 漏改 ⇒ 第 3 条红；version 不升 ⇒ 第 4 条红。
 */
class FlowTypeConditionPromptContractTest {

	@Test
	void promptDeclaresTaskTypeFormInsteadOfTextQuestion() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("purpose 白名单里必须有 TASK_TYPE")
			.contains("TASK_TYPE");
		assertThat(prompt).as("类型未知必须用表单，且是单个 enum 单选")
			.contains("单个 enum 单选")
			.contains("EXPORT/IMPORT");
		assertThat(prompt).as("禁止用文字反问类型")
			.contains("不要用文字反问类型");
		assertThat(prompt).as("类型已确定时不再下发类型表单，直接 create_task")
			.contains("类型已确定")
			.contains("直接调用 create_task");
	}

	@Test
	void promptDeclaresSingleFreeTextConditionFormWithNoFilterOnBlank() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("条件表单：一个非必填自由文本框")
			.contains("只有一个非必填自由文本框")
			.contains("longText");
		assertThat(prompt).as("文本由模型解析成 set_condition 结构")
			.contains("解析成 set_condition");
		assertThat(prompt).as("留空 = 不设筛选，直接推进（全量导出）")
			.contains("留空即不设筛选")
			.contains("confirm_step");
		assertThat(prompt).as("EXPORT 及之后禁止条件表单")
			.contains("EXPORT 步骤及之后禁止条件表单");
	}

	@Test
	void toolDescriptionDeclaresTaskTypeAndConditionContract() throws Exception {
		Method target = AiTools.class.getMethod("generativeForm", AiTools.FormSpec.class);
		Tool tool = target.getAnnotation(Tool.class);
		assertThat(tool).isNotNull();
		String annotation = tool.description();
		assertThat(annotation).as("注解描述必须给出 TASK_TYPE 用途").contains("TASK_TYPE");
		assertThat(annotation).as("注解描述必须给出条件表单的步骤与单框口径")
			.contains("QUERY_COND")
			.contains("非必填");

		ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("generative_form");
		assertThat(yaml).isNotNull();
		assertThat(yaml.hasDescription()).isTrue();
		assertThat(yaml.description()).as("YAML 描述必须与注解同口径")
			.contains("TASK_TYPE")
			.contains("QUERY_COND")
			.contains("非必填");
	}

	@Test
	void toolsVersionIsBumpedForTheNewDescriptions() {
		YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();

		assertThat(shipped.version())
			.as("改描述必须发新 version（破 prompt 缓存）")
			.isEqualTo("2026-10-27.9");
	}
}
