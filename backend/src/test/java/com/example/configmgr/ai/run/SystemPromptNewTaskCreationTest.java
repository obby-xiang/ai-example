package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新建任务口径（2026-10-29 审核问题 1/2）：在「已有/已完成任务」的页面里让 AI 新建导出/导入任务时，
 * 模型会先追问配置、先 {@code list_config_defs} 摸底、或把旧任务的配置/步骤/完成状态当成新任务的已知信息；
 * 创建并导航后又会用「请告诉我导出哪个」的开放式文字反问，或把定义列表丢给用户自己挑。
 *
 * <p><b>本批只改提示词与工具描述</b>（任务创建、导航、表单、槽位守卫的执行逻辑一字不动），
 * 所以用例锁的是模型看到的那份文本口径；模型是否真照做只能在真机真模型上观察，本用例不声称覆盖那一面。
 *
 * <p><b>证红</b>：删掉规则 30 的「已有任务/已完成任务页面」与「直接调用 create_task」⇒ 第 1 条红；
 * 删掉「旧任务参数不自动带入新任务」段 ⇒ 第 2 条红；把平铺多选的
 * {@code generative_form(purpose=SELECT_DEFINITIONS)} 换成文字反问或定义清单 ⇒ 第 3 条红；
 * 规则 30 插到 29 之前或删掉 ⇒ 第 4 条红。
 */
class SystemPromptNewTaskCreationTest {

	/** 规则 30 的正文（最后一条）；用例只在这段里取证，避免在别处碰巧命中同一句话。 */
	private static String ruleThirty() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;
		int start = prompt.indexOf("30. ");
		assertThat(start).as("系统提示必须落一条规则 30（新建任务口径）").isGreaterThan(0);
		return prompt.substring(start);
	}

	@Test
	void newTaskIntentOnExistingOrCompletedTaskPageCreatesDirectlyWithoutPreQuestions() {
		String rule = ruleThirty();

		assertThat(rule).as("页面前提写全：任务中心、已有任务页面、已完成任务页面都适用")
			.contains("已有任务")
			.contains("已完成任务页面");
		assertThat(rule).as("明确「新建」即新建意图，直接调用 create_task")
			.contains("意图就是新建")
			.contains("直接调用 create_task");
		assertThat(rule).as("创建前禁止追问配置/筛选/文件，也禁止拿 list_config_defs 摸底代替创建")
			.contains("不要先追问配置项、筛选条件或数据文件")
			.contains("不得先用 list_config_defs 摸底再回来问");
		assertThat(rule).as("create_task 的入参口径：只需类型 + 可选标题")
			.contains("只需要任务类型")
			.contains("可选 title")
			.contains("标题不追问");
		assertThat(rule).as("类型未知才用表单选类型，仍不用文字反问")
			.contains("只有类型确实无法判断时才用 generative_form 选 EXPORT/IMPORT")
			.contains("不要用文字反问类型");
	}

	@Test
	void oldTaskParametersAreNotInheritedByANewTask() {
		String rule = ruleThirty();

		assertThat(rule).as("旧任务参数不自动带入新任务（标题即结论）")
			.contains("旧任务参数不自动带入新任务");
		assertThat(rule).as("配置项/筛选条件/权威步骤/完成状态四类都归旧任务")
			.contains("配置项、筛选条件、权威步骤与完成状态都属于那条旧任务");
		assertThat(rule).as("不得把旧任务事实当成新任务的已知信息去勾选、填条件或跳过创建")
			.contains("不得当作新任务的已知信息")
			.contains("跳过创建");
		assertThat(rule).as("唯一例外：用户明确要求沿用当前任务配置")
			.contains("沿用/照当前任务的配置")
			.contains("时才可复用");
	}

	@Test
	void afterNavigationDefinitionsUseToolOrFlatMultiSelectFormInsteadOfOpenText() {
		String rule = ruleThirty();

		assertThat(rule).as("配置已知（含同一句话里给出的编码）⇒ 直接 select_definitions，不弹表单")
			.contains("配置已明确")
			.contains("直接 select_definitions，不弹表单");
		assertThat(rule).as("配置未知且必须选择 ⇒ purpose=SELECT_DEFINITIONS 的平铺多选表单")
			.contains("generative_form(purpose=SELECT_DEFINITIONS)")
			.contains("选项平铺多选配置项");
		assertThat(rule).as("禁止开放式文字反问，也禁止只列定义让用户自己挑")
			.contains("请告诉我导出哪个")
			.contains("开放式文字反问")
			.contains("禁止只把定义列表列在正文里让用户自己挑");
		assertThat(rule).as("筛选条件可选：没给条件就推进，不为条件弹表单")
			.contains("筛选条件可选")
			.contains("不要为条件弹表单或追问");
	}

	@Test
	void ruleThirtyKeepsAscendingNumberingAndClosesThePrompt() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;
		int rule29 = prompt.indexOf("29. 失败恢复的窄例外");
		int rule30 = prompt.indexOf("30. ");

		assertThat(rule29).isGreaterThan(0);
		assertThat(rule30).isGreaterThan(rule29);
		assertThat(prompt.substring(rule30)).as("30 是最后一条，正文到提示词结尾为止")
			.contains("不要为条件弹表单或追问");
	}
}
