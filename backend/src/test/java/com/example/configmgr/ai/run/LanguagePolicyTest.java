package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3-Y1（用户裁决 4B，2026-10-24）：回答语言口径<b>只声明一处</b> ——
 * 「AI 默认中文，用户使用其它语言则从其语言」。
 *
 * <p>修复前的形态是两条相反硬约束：主提示规则 1「始终使用中文回复」+ KB 规则 8「回答按用户所用语言生成」。
 * 两条都在"硬约束"层级、后者位置更近 ⇒ en 界面下同一份系统消息自相矛盾，行为随模型漂移。
 *
 * <p>本类锁四件事：
 * <ol>
 * <li>主提示不再有"始终中文"这类排它措辞；</li>
 * <li>主提示给出 4B 的两段语义（默认中文 + 随用户语言），并把最终裁决权交给本轮语言段；</li>
 * <li>内部协议标识（工具名/参数名/错误码/doc_id/章节路径）明确"保持原样、不翻译" ——
 * 否则"随用户语言"会被模型执行成"把 SESSION_BUSY、kb_search 也翻成英文"；</li>
 * <li>KB 规则 8 不再另立一套语言政策（不再出现"回答按用户所用语言生成"），只声明检索桥接与引用锚定。</li>
 * </ol>
 *
 * <p><b>证红方式</b>：把规则 1 改回「始终使用中文回复」⇒ 第 1 条红；删掉"按其语言回答"⇒ 第 2 条红；
 * 删掉内部标识句 ⇒ 第 3 条红；把 KB 规则 8 改回"回答按用户所用语言生成"⇒
 * {@code KbCitationIdConformanceTest#kbRulesDeferToTheSystemLanguageRequirement} 红。
 */
class LanguagePolicyTest {

	@Test
	void mainPromptNoLongerPinsChineseOnly() {
		assertThat(ResilientChatService.SYSTEM_PROMPT)
			.as("A3-Y1：排它措辞必须消失（它与 KB 规则 8 直接相反）")
			.doesNotContain("始终使用中文回复");
	}

	@Test
	void mainPromptDeclaresDefaultChineseAndFollowsUserLanguage() {
		assertThat(ResilientChatService.SYSTEM_PROMPT)
			.as("4B 的前半：默认中文")
			.contains("默认使用中文回复")
			.as("4B 的后半：用户用其它语言则从其语言")
			.contains("按其语言回答");
	}

	@Test
	void mainPromptDefersToThePerTurnLanguageRequirement() {
		assertThat(ResilientChatService.SYSTEM_PROMPT)
			.as("语言来源（Accept-Language → 本轮语言段）在提示词里必须有落点，否则前端 locale 传不进模型")
			.contains("回答语言（本轮）");
	}

	@Test
	void mainPromptProtectsInternalProtocolIdentifiers() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).contains("工具名").contains("参数名").contains("错误码").contains("doc_id");
		assertThat(prompt).as("必须显式禁止翻译，否则'随用户语言'会把协议标识一起翻掉")
			.contains("保持原样")
			.contains("不要翻译");
	}

	/**
	 * M-4（4B 独立评审 → 2026-10-27 裁决更新）：规则 10 不再机械引导用户回复「继续」。
	 *
	 * <p>修复前它写「请回复「继续」完成最后一步」，上一轮改成双语举例，随后一度收紧成全局"不得索取继续"。
	 * 主控验收指出后者过宽：新步骤工具必须下一轮才披露时，没有用户输入就无法继续。最终口径 =
	 * 确需下一轮披露时可简短说明状态并允许用户给下一步指令，只是禁止机械套用"请回复继续"；
	 * 禁止再要批准/继续仅限 create_task 已批准/已创建成功这一例外。
	 */
	@Test
	void continueGuidanceInRuleTenIsLanguageNeutral() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt)
			.as("确需下一轮披露时允许用户给下一步指令，但不得机械套用「请回复继续」")
			.contains("允许用户给出下一步指令")
			.contains("不要机械套用");
		assertThat(prompt)
			.as("旧的继续引导不得回归")
			.doesNotContain("请回复「继续」")
			.doesNotContain("引导用户回复继续指令");
	}
}
