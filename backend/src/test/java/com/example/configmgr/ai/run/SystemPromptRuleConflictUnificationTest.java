package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则冲突统一（2026-10-29 后端自动处理批）：把提示词里互相打架的三处收成一套口径。
 *
 * <p>本类只锁<b>提示词文本口径</b>（模型是否照做只能真机观察，本用例不声称验证模型调用）：
 * <ol>
 * <li>规则 10「页面级动作失败即停」保留规则 29 的<b>零副作用一次纠正</b>例外 —— 旧文本读成
 *     "任何失败都不得再动步骤"，与 29 的"回执给出纠正指引时允许一次不同的纠正动作"直接冲突；</li>
 * <li>挂起期间用户在向导里手动改了步骤：续跑按<b>任务权威步骤</b>同步继续，<b>不为此新增确认卡</b>
 *     （步骤同步是事实收敛，不是授权）；</li>
 * <li>任务已完成（COMPLETED）本身不构成下载/打开编辑器的授权 —— 只有用户明确意图才可调用。</li>
 * </ol>
 */
class SystemPromptRuleConflictUnificationTest {

	@Test
	void pageActionStopRetainsTheRule29ZeroSideEffectCorrectionException() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("规则 10 的失败即停仍然存在（不放宽）")
			.contains("页面级动作失败即停")
			.contains("不要重试推进、跳步或反复导航");
		assertThat(prompt).as("规则 10 明确保留规则 29 的零副作用一次纠正例外")
			.contains("第 29 条的零副作用一次纠正例外");
		assertThat(prompt).as("例外只放宽\u201C一次不同的纠正动作\u201D，同参数重试仍在禁止之列")
			.contains("同参数重试推进仍在禁止之列");
		assertThat(prompt).as("规则 29 本体未被改写")
			.contains("29. 失败恢复的窄例外")
			.contains("下一批可以执行一次不同的纠正动作");
	}

	@Test
	void parkedManualStepChangeSyncsToAuthoritativeStepWithoutNewHitl() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("挂起期间用户在向导里手动改步骤是已知场景")
			.contains("挂起期间用户在向导里手动改了步骤");
		assertThat(prompt).as("后续跑按任务权威步骤同步，而不是沿用历史里的旧步骤")
			.contains("按任务的权威步骤同步");
		assertThat(prompt).as("步骤同步不新增 HITL：不新发起确认卡、不追问")
			.contains("不为此新增确认卡")
			.contains("不追问用户");
	}

	@Test
	void navigationFailureAlsoKeepsTheRule29CorrectionException() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("规则 24 的导航失败即停仍然存在").contains("导航失败即停");
		assertThat(prompt).as("导航同为页面级动作 ⇒ 第 29 条窄例外同样适用")
			.contains("第 29 条窄例外同样适用");
	}

	@Test
	void completedTaskAloneIsNotDownloadOrEditorAuthorization() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("已完成状态本身不是授权")
			.contains("任务已完成（COMPLETED）本身不构成下载或打开编辑器的授权");
		assertThat(prompt).as("下载仍需用户明确意图（原有口径保留）")
			.contains("只有用户明确提出下载/获取文件意图");
		assertThat(prompt).as("打开编辑器同样需要用户查看意图")
			.contains("查看编辑器也需用户有查看意图");
	}

	/**
	 * Task 1 第 4/5 项：条件与收尾口径只有一套。
	 * <ul>
	 * <li>「用户没提出就不要展开询问条件」与第 26 条「QUERY_COND 给一次非必填条件框」互相矛盾，删除；</li>
	 * <li>禁止 {@code SELECT_DEFS} 直接跳 {@code EXPORT}；</li>
	 * <li>导出收尾前必须发起 {@code start_export} 确认卡，且不得自动执行。</li>
	 * </ul>
	 */
	@Test
	void conditionAndWrapUpRulesNoLongerContradict() {
		String prompt = ResilientChatService.SYSTEM_PROMPT;

		assertThat(prompt).as("旧的'用户没提出就不要展开询问条件'与第 26 条冲突，必须删除")
			.doesNotContain("用户没提出就不要展开询问条件");
		assertThat(prompt).as("禁止从 SELECT_DEFS 直接跳到 EXPORT")
			.contains("禁止从 SELECT_DEFS 直接跳到 EXPORT");
		assertThat(prompt).as("进入 EXPORT 且 start_export 已披露时先发起确认卡")
			.contains("start_export 已披露")
			.contains("发起 start_export 确认卡");
		assertThat(prompt).as("确认卡只承接发起，不得自动执行")
			.contains("不得自动执行");
	}

}
