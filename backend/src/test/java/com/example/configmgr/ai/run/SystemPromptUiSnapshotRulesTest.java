package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 A · 提示词第 20 / 21 条 + 第 10 条收紧（D8-②/③、D5-③）。
 *
 * <p>
 * 三条都是「结构约束的说明书」：快照闸与动态披露在代码里已经强制，提示词负责让模型
 * <b>理解为什么工具消失 / 为什么被拒</b>，避免把能力收窄误读成故障后反复尝试或向用户道歉。
 *
 * <p><b>证红</b>：删掉第 20/21 条 ⇒ 对应用例红；把「导航类调用成功后即结束本轮」删掉 ⇒ 第 10 条用例红。
 */
class SystemPromptUiSnapshotRulesTest {

	@Test
	void snapshotGateRuleIsPresent() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).contains("20. 需要点界面元素前，必须先调用 ui_snapshot");
		assertThat(prompt).contains("ref 与 epoch 只能逐字取自最近一次快照");
		assertThat(prompt).contains("未快照过的 ref 会被直接拒绝执行");
	}

	@Test
	void noUiToolsRuleExplainsDynamicDisclosure() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).contains("21. 若本轮工具列表里没有 ui_*");
		assertThat(prompt).contains("当前页面没有 AI 可操作的已注册区域");
		assertThat(prompt).contains("不要反复尝试 ui_*");
	}

	@Test
	void navigationTighteningIsAppendedToRule10() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).contains("导航类调用成功后即结束本轮");
		assertThat(prompt).contains("同一目标页不要重复导航");
		// 收紧只加一句，不换掉第 10 条原有语义
		assertThat(prompt).contains("推进后不要在同一轮里接着调用刚解锁的工具");
	}

	@Test
	void numberingIsAppendedAfterRule19() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;
		int rule19 = prompt.indexOf("19. 正文用纯文本表达");
		int rule20 = prompt.indexOf("20. 需要点界面元素前");
		int rule21 = prompt.indexOf("21. 若本轮工具列表里没有 ui_*");

		assertThat(rule19).isGreaterThan(0);
		assertThat(rule20).isGreaterThan(rule19);
		assertThat(rule21).isGreaterThan(rule20);
	}
}
