package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已知信息复用（2026-10-28）：用户已明确 CURRENCY/CNY 之类的配置项与筛选值，AI 仍弹配置/条件表单
 * 或改用文字二次确认 —— 本轮把这条口径收进系统提示词，只做规则收口，不改 HITL、不改上传交接、
 * 不改合法多表单排队。
 *
 * <p><b>锁的是什么</b>：提示词文本口径（模型是否真的照做只能实机观察，本用例不声称验证模型调用）。
 * <b>证红</b>：把总纲段删掉 ⇒ {@code knownParametersAreReusedInsteadOfRegathered} 红；把规则 26
 * 新增的「已知值不二次确认 / 取消后不自动重问」删掉 ⇒ {@code generativeFormRuleBansKnownValueConfirmationAndAutoReask}
 * 红；把规则 18 的旧枚举「补充说明、选择配置项、筛选条件等」恢复 ⇒
 * {@code textQuestionsAreLimitedToGenuinelyMissingUnstructuredInfo} 红。
 */
class SystemPromptKnownInfoReuseTest {

	@Test
	void knownParametersAreReusedInsteadOfRegathered() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).contains("已知信息复用总纲");
		assertThat(prompt).as("总纲点名四类已给参数：配置项、筛选字段和值、任务类型、模式")
			.contains("已明确的配置项、筛选字段和值、任务类型、模式");
		assertThat(prompt).as("已给参数直接用于工具，禁止再用表单或文字重复收集")
			.contains("直接用于对应工具")
			.contains("禁止再用 generative_form 或文字重复收集");
		assertThat(prompt).as("跨轮原始意图包含已给参数，除非用户修改")
			.contains("跨轮延续的「原始意图」包含这些已给参数")
			.contains("除非用户明确修改");
		assertThat(prompt).as("总纲优先于后文，避免逐条规则再被读成「先问一句」")
			.contains("已知信息复用总纲（先于以下全部规则）");
	}

	@Test
	void generativeFormRuleBansKnownValueConfirmationAndAutoReask() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("表单只在真实缺口/歧义时用")
			.contains("generative_form 只在真正缺失或存在歧义、且需要结构化补充时才使用");
		assertThat(prompt).as("表单不用于已知信息二次确认，也不下发全已知值的预填表单")
			.contains("不用于已知信息的")
			.contains("不生成全为已知值的预填表单");
		assertThat(prompt).as("用户取消后不得换 toolCallId 自动重问，除非新信息或用户主动要求")
			.contains("用户取消表单后不得换")
			.contains("toolCallId 自动重问")
			.contains("除非用户给出了新信息或主动要求");
		assertThat(prompt).as("合法且彼此不同的必要表单不受影响")
			.contains("合法且彼此不同的必要表单不受此限");
		assertThat(prompt).as("导出配置项平铺多选与既有步骤口径保留")
			.contains("直接平铺多选配置项")
			.contains("generative_form 只收集值、不推进步骤");
	}

	@Test
	void knownCodesAndConditionsGoStraightToToolsWithinStepChain() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("已知配置定义编码直接调 select_definitions")
			.contains("已知配置定义编码（如 CURRENCY）时，直接调用 select_definitions");
		assertThat(prompt).as("已知筛选字段与值直接按既有步骤链执行 set_condition")
			.contains("已知筛选字段与值（如 CURRENCY 的 code = CNY）时，直接按既有步骤链执行 set_condition");
		assertThat(prompt).as("步骤链没有被放松：仍只在 QUERY_COND 调、仍先 confirm_step")
			.contains("仍在 QUERY_COND 调用，仍先 confirm_step")
			.contains("只要当前还在 SELECT_DEFS")
			.contains("下一批再调用 set_condition");
	}

	@Test
	void textQuestionsAreLimitedToGenuinelyMissingUnstructuredInfo() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("文字询问只剩「确实缺失且无法结构化」的开放式信息")
			.contains("只有确实缺失、且无法结构化的开放式信息");
		assertThat(prompt).as("结构化缺口用工具或表单收集")
			.contains("配置项、筛选字段与值等结构化缺口一律用工具或 generative_form 收集");
		assertThat(prompt).as("用户已明确的信息不再询问")
			.contains("用户已明确给出的信息不再询问");
		assertThat(prompt).as("旧口径把「选择配置项/筛选条件」列成开放式信息，是本轮要删的误导")
			.doesNotContain("补充说明、选择配置项、筛选条件等");
	}

	@Test
	void ruleSixForbidsPointlessNextStepRequests() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("规则 6 改成「不做无意义追问、不索要下一步指令」")
			.contains("6. 不做无意义的追问")
			.contains("不要索要下一步指令")
			.contains("用简洁陈述收尾");
		assertThat(prompt).as("旧口径（主动提示用户下一步可以做什么）必须删除")
			.doesNotContain("6. 主动提示用户下一步可以做什么");
	}

	@Test
	void failureRecoveryNarrowExceptionAllowsOneCorrectiveActionOnly() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("窄例外限定零副作用 + 可纠正 + 回执有明确指引")
			.contains("29. 失败恢复的窄例外")
			.contains("零副作用")
			.contains("回执明确给出了纠正指引");
		assertThat(prompt).as("只允许一次不同的纠正动作，不得重试同一调用")
			.contains("下一批可以执行一次不同的纠正动作")
			.contains("不得重试同一调用");
		assertThat(prompt).as("网络/落库/结局未知仍失败即停，并回到第 10 条口径")
			.contains("网络异常、落库结果未知、结局未知")
			.contains("按第 10 条如实说明当前状态");
		assertThat(prompt).as("规则 10 的既定口径未被破坏")
			.contains("页面级动作失败即停")
			.contains("不要重试推进、跳步或反复导航");
		assertThat(prompt).as("例外不覆盖确认与启动")
			.contains("本例外不改变 create_task / start_* 的确认卡");
	}

	@Test
	void taskSlotsAndFormPurposeAreDeclared() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("系统会解析并展示任务槽位（已知值），模型据此直接调工具")
			.contains("任务槽位")
			.contains("已确定");
		assertThat(prompt).as("表单必须显式给 purpose，宿主不按标题猜")
			.contains("purpose")
			.contains("SELECT_DEFINITIONS")
			.contains("SET_CONDITIONS")
			.contains("GENERAL_CLARIFY")
			.contains("禁止靠标题");
		assertThat(prompt).as("缺省 purpose 兼容为 GENERAL_CLARIFY")
			.contains("缺省");
		assertThat(prompt).as("槽位缺失/未知配置时表单仍可用（不得删掉这条说明）")
			.contains("未知配置")
			.contains("歧义")
			.contains("表单仍可用");
		assertThat(prompt).as("槽位已确定的冗余表单由宿主拒绝，模型按回执纠正一次")
			.contains("FORM_SLOTS_ALREADY_KNOWN")
			.contains("零副作用");
	}

	@Test
	void newRulesKeepAscendingNumbering() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		int rule28 = prompt.indexOf("28. 勾选配置项");
		int rule29 = prompt.indexOf("29. 失败恢复的窄例外");
		assertThat(rule28).isGreaterThan(0);
		assertThat(rule29).isGreaterThan(rule28);
		assertThat(prompt.indexOf("26. 选择配置项、设置筛选条件")).isLessThan(rule28);
	}
}
