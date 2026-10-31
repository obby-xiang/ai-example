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
			.contains("表单是单个非必填自由文本框");
		assertThat(prompt).as("文本由模型解析成 set_condition 结构")
			.contains("非空回灌由模型解析成 set_condition");
		assertThat(prompt).as("留空（含 {}）= 不设筛选，直接推进（全量导出）")
			.contains("留空（包括 {} 与空文本）即明确不设筛选")
			.contains("confirm_step");
		assertThat(prompt).as("单通道：只由宿主在 QUERY_COND 且条件未决定时发放一次，模型不得自行下发")
			.contains("只由宿主在 QUERY_COND 且条件未决定时统一发放一次")
			.contains("模型不得自行下发");
	}

	@Test
	void toolDescriptionDeclaresTaskTypeAndConditionContract() throws Exception {
		Method target = AiTools.class.getMethod("generativeForm", AiTools.FormSpec.class);
		Tool tool = target.getAnnotation(Tool.class);
		assertThat(tool).isNotNull();
		String annotation = tool.description();
		assertThat(annotation).as("注解描述必须给出 TASK_TYPE 用途").contains("TASK_TYPE");
		assertThat(annotation).as("注解描述必须给出条件表单的单通道口径")
			.contains("SET_CONDITIONS 不由模型调用")
			.contains("只由宿主在 QUERY_COND");

		ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("generative_form");
		assertThat(yaml).isNotNull();
		assertThat(yaml.hasDescription()).isTrue();
		assertThat(yaml.description()).as("YAML 描述必须与注解同口径")
			.contains("TASK_TYPE")
			.contains("QUERY_COND")
			.contains("只由宿主在 QUERY_COND");
	}

	@Test
	void promptDeclaresOneShotConditionBoxAfterEnteringQueryCond() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("条件表只由宿主在 QUERY_COND 且条件未决定时发放一次")
			.contains("条件表单只由宿主在 QUERY_COND 且条件未决定时统一发放一次");
		assertThat(prompt).as("已有条件直接用于工具，不弹表单")
			.contains("条件已明确（槽位或用户已给）时直接用于工具");
		assertThat(prompt).as("框内留空（含 {}）就直接 confirm_step 全量推进")
			.contains("留空就直接 confirm_step");
		assertThat(prompt).as("条件只收集一次，不重复收集")
			.contains("不得再下发第二张");
		assertThat(prompt).as("模型代发条件表的旧口径必须删除")
			.doesNotContain("由你（模型）")
			.doesNotContain("不要为条件弹表单或追问");
	}

	@Test
	void toolDescriptionsAgreeOnTheOneShotConditionBox() throws Exception {
		ToolDefinitionOverride confirmStep = new YamlToolDefinitionSource().load().find("confirm_step");
		assertThat(confirmStep).isNotNull();
		assertThat(confirmStep.description())
			.as("YAML confirm_step 必须说明条件表由宿主发放（模型不下发），留空即照常推进")
			.contains("不要自行下发 SET_CONDITIONS 表单")
			.contains("由宿主统一给一次非必填自由文本框")
			.contains("留空即照常推进")
			.doesNotContain("进入 QUERY_COND 时按 generative_form(purpose=SET_CONDITIONS) 给一次");

		ToolDefinitionOverride setCondition = new YamlToolDefinitionSource().load().find("set_condition");
		assertThat(setCondition).isNotNull();
		assertThat(setCondition.description())
			.as("YAML set_condition 必须认可条件表单回填解析出的条件")
			.contains("自由文本框回填后解析出的条件");

		Method confirmStepMethod = AiTools.class.getMethod("confirmStep", String.class);
		String confirmStepAnnotation = confirmStepMethod.getAnnotation(Tool.class).description();
		assertThat(confirmStepAnnotation).as("注解回落也要有这条单通道说明")
			.contains("QUERY_COND")
			.contains("宿主统一给一次非必填自由文本框");

		Method setConditionMethod = AiTools.class.getMethod("setCondition", String.class,
				AiTools.FieldQueryCondition.class);
		String setConditionAnnotation = setConditionMethod.getAnnotation(Tool.class).description();
		assertThat(setConditionAnnotation).as("注解回落不得再把「只有用户明确提出」写成唯一入口")
			.contains("条件表单回填")
			.doesNotContain("只有用户明确提出筛选/查询条件时才调用");
	}

	@Test
	void promptDeclaresBothFormsHostIssued() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("类型表单仍由宿主先行下发：收到回灌就直接 create_task")
			.contains("类型未知时宿主会先行下发那张类型表单")
			.contains("收到它的回灌")
			.contains("就直接按该值 create_task");
		assertThat(prompt).as("条件表也由宿主统一发放一次（单通道）")
			.contains("条件表单只由宿主在 QUERY_COND 且条件未决定时统一发放一次");
		assertThat(prompt).as("模型代发条件表的旧口径必须删除")
			.doesNotContain("由你（模型）")
			.doesNotContain("不要等宿主代发");
		assertThat(prompt).as("留空（含 {}）是明确不设筛选")
			.contains("包括 {} 与空文本");
		assertThat(prompt).as("已经发过的那一张不得再发第二张（否则同一缺口弹两次）")
			.contains("不得再下发第二张");
	}

	@Test
	void toolsVersionIsBumpedForTheNewDescriptions() {
		YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();

		assertThat(shipped.version())
			.as("改描述必须发新 version（破 prompt 缓存）")
			.isEqualTo("2026-10-27.20");
	}

	/**
	 * 2026-10-31 明确不设筛选：用户答"无/不筛选/全部"时，模型必须<b>逐项</b>下发
	 * {@code set_condition(defCode, {})}，而不是重发条件表 —— 否则闸门以空条件拒绝、
	 * {@code confirm_step} 又以"非空回灌无草案"拒绝，两边互锁。
	 */
	@Test
	void promptDeclaresPerDefinitionExplicitUnsetViaEmptyObject() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("明确不设 = 逐项 set_condition(defCode, {})")
			.contains("对表单覆盖的每个 defCode 各调用一次")
			.contains("set_condition(defCode, {})");
		assertThat(prompt).as("不得重发条件表，也不得只清一部分就推进")
			.contains("不要重发 SET_CONDITIONS")
			.contains("也不要只清一部分就推进");
		assertThat(prompt).as("create_task 的 {} 仍只表示条件未决定（不等于明确不设）")
			.contains("create_task 的 {} 只表示条件未决定");
	}

	/**
	 * 2026-10-31 条件链 fail-closed（F4/F8/F11）：confirm_step 必须单独成批；set_condition 只对
	 * 已勾选配置项调用、相同入参幂等复用（不同值才是修改）。
	 */
	@Test
	void promptDeclaresConditionChainBatchAndIdempotencyRules() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("confirm_step 单独成批（混批会吞掉同批调用）")
			.contains("confirm_step 必须单独成批")
			.contains("先单独调用");
		assertThat(prompt).as("set_condition 只对已勾选配置项调用")
			.contains("只能对任务已勾选的配置项调用");
		assertThat(prompt).as("相同入参幂等复用、不同值才是修改")
			.contains("幂等复用")
			.contains("不同值才是修改");
	}
}
