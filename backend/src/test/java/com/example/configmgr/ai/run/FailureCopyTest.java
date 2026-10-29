package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终局失败码的<b>用户面短句</b>（C13 第 5 项的一半：原因分开了，话也要分开）。
 *
 * <p>{@code WRAP_UP_IGNORED} 换成 {@link ErrorCodes#TOOL_BUDGET_EXHAUSTED} 之后，若白名单里没有这一条，
 * 用户会看到 {@code AI_RUN_FAILED} 的"AI 服务暂时不可用" —— 服务其实完全正常，是这一轮的预算用完了。
 * 同一个换码动作若把用户带向错误的处置（等一会儿再试 vs 开新会话），等于没分开。
 */
class FailureCopyTest {

	@Test
	void toolBudgetExhaustedHasItsOwnCopyInsteadOfTheGenericFallback() {
		String budget = ResilientChatService.safeFailureMessage(ErrorCodes.TOOL_BUDGET_EXHAUSTED);
		String overflow = ResilientChatService.safeFailureMessage(ErrorCodes.CONTEXT_OVERFLOW);
		String generic = ResilientChatService.safeFailureMessage(ErrorCodes.AI_RUN_FAILED);

		assertThat(budget).as("不许落到通用失败文案").isNotEqualTo(generic).isNotBlank();
		assertThat(budget).as("工具预算耗尽 ≠ 对话过长").isNotEqualTo(overflow);
	}
}
