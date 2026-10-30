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
 * 条件表单只有 {@code QUERY_COND} 允许（一个非必填自由文本框，进入该步给一次，留空即全量推进），
 * {@code SELECT_DEFS} 先 {@code confirm_step}，其它步骤由宿主零副作用拒绝。</li>
 * <li>工具描述：注解与 YAML 同批改（YAML 是可选覆盖，写反了模型看到两套口径）。</li>
 * <li>版本：改描述必须发新 version（破 prompt 缓存）。</li>
 * </ol>
 *
 * <p><b>证红</b>：提示词删掉 TASK_TYPE 段 ⇒ 第 1 条红；删掉条件单框/留空/「只在 QUERY_COND」的步骤门 ⇒
 * 条件契约两条红；注解或 YAML 漏改 ⇒ 第 3 条红；version 不升 ⇒ 第 4 条红。
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
		assertThat(prompt).as("只有 QUERY_COND 允许条件表单（SELECT_DEFS/EXPORT 及之后由宿主零副作用拒绝）")
			.contains("只有 QUERY_COND 允许下发这张表")
			.contains("SELECT_DEFS 要先")
			.contains("EXPORT 及之后一律零副作用拒绝");
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
	void promptDeclaresOneShotConditionBoxAfterEnteringQueryCond() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("进入 QUERY_COND 给一次非必填自由文本框（规则 30 与规则 26 同口径）")
			.contains("进入 QUERY_COND 后按第 26 条给一次非必填自由文本框");
		assertThat(prompt).as("已有条件直接 set_condition，不弹表单")
			.contains("用户已给条件就直接 set_condition");
		assertThat(prompt).as("框内留空就直接 confirm_step 全量推进")
			.contains("框内留空就直接 confirm_step");
		assertThat(prompt).as("条件只收集一次，不重复收集")
			.contains("不得重复下发条件表单");
		assertThat(prompt).as("旧口径「不要为条件弹表单或追问」与规则 26 冲突，必须删除")
			.doesNotContain("不要为条件弹表单或追问");
	}

	@Test
	void toolDescriptionsAgreeOnTheOneShotConditionBox() throws Exception {
		ToolDefinitionOverride confirmStep = new YamlToolDefinitionSource().load().find("confirm_step");
		assertThat(confirmStep).isNotNull();
		assertThat(confirmStep.description())
			.as("YAML confirm_step 必须给出 QUERY_COND 的一次非必填空框（与提示词同口径）")
			.contains("进入 QUERY_COND 时按 generative_form(purpose=SET_CONDITIONS) 给一次")
			.contains("留空即照常推进")
			.doesNotContain("不要因此收集条件");

		ToolDefinitionOverride setCondition = new YamlToolDefinitionSource().load().find("set_condition");
		assertThat(setCondition).isNotNull();
		assertThat(setCondition.description())
			.as("YAML set_condition 必须认可条件表单回填解析出的条件")
			.contains("自由文本框回填后解析出的条件")
			.doesNotContain("用户没提就不要设置条件");

		Method confirmStepMethod = AiTools.class.getMethod("confirmStep", String.class);
		String confirmStepAnnotation = confirmStepMethod.getAnnotation(Tool.class).description();
		assertThat(confirmStepAnnotation).as("注解回落也要有这次条件框")
			.contains("QUERY_COND")
			.contains("非必填");

		Method setConditionMethod = AiTools.class.getMethod("setCondition", String.class,
				AiTools.FieldQueryCondition.class);
		String setConditionAnnotation = setConditionMethod.getAnnotation(Tool.class).description();
		assertThat(setConditionAnnotation).as("注解回落不得再把「只有用户明确提出」写成唯一入口")
			.contains("条件表单回填")
			.doesNotContain("只有用户明确提出筛选/查询条件时才调用");
	}

	@Test
	void promptDeclaresTypeFormHostIssuedAndConditionFormModelIssued() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("类型表单仍由宿主先行下发：收到回灌就直接 create_task")
			.contains("宿主会先行下发那张类型表单")
			.contains("收到它的回灌")
			.contains("就直接按该值 create_task");
		assertThat(prompt).as("条件文本框不再由宿主代发（否则模型没机会理解用户原话）")
			.doesNotContain("宿主会先行下发那一次条件文本框");
		assertThat(prompt).as("条件未知时由模型在 QUERY_COND 下发那一次条件文本框")
			.contains("由你（模型）")
			.contains("不要等宿主代发");
		assertThat(prompt).as("已知条件不因大小写/别名写法被当成缺失")
			.contains("含小写、别名等")
			.contains("非规范写法");
		assertThat(prompt).as("已经发过的那一张不得再发第二张（否则同一缺口弹两次）")
			.contains("不得再下发第二张");
	}

	@Test
	void toolsVersionIsBumpedForTheNewDescriptions() {
		YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();

		assertThat(shipped.version())
			.as("改描述必须发新 version（破 prompt 缓存）")
			.isEqualTo("2026-10-27.11");
	}
}
