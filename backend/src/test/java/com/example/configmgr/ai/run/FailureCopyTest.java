package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终局失败码的<b>用户面短句</b>（C13 第 5 项的一半：原因分开了，话也要分开）。
 *
 * <p>{@code WRAP_UP_IGNORED} 换成 {@link ErrorCodes#TOOL_BUDGET_EXHAUSTED} 之后，若白名单里没有这一条，
 * 用户会看到 {@code AI_RUN_FAILED} 的"AI 服务暂时不可用" —— 服务其实完全正常，是这一轮的预算用完了。
 * 同一个换码动作若把用户带向错误的处置（等一会儿再试 vs 开新会话），等于没分开。
 */
class FailureCopyTest {

	/**
	 * C13 / I4：换码之后<b>派生提示与中英文案</b>必须一起落地。
	 *
	 * <p>缺陷现场：`failureCode` 只在"派生码已登记"时才加后缀，而 `TOOL_BUDGET_EXHAUSTED` 没有登记
	 * `_AFTER_TOOL_SIDE_EFFECT` ⇒ 换码把"本轮已有工具执行"这条现场信息直接丢了；用户面文案又还是
	 * 工具卡那套"这一步没有执行/会继续回答"（这里其实是整轮失败）—— 与真实处置相反。
	 */
	@Test
	void toolBudgetExhaustedDerivesAfterSideEffectAndSaysSoInBothLocales() throws IOException {
		assertThat(ResilientChatService.failureCode(ErrorCodes.TOOL_BUDGET_EXHAUSTED, true, false))
			.as("有副作用 ⇒ 登记的派生码").isEqualTo("TOOL_BUDGET_EXHAUSTED_AFTER_TOOL_SIDE_EFFECT");
		assertThat(ResilientChatService.failureCode(ErrorCodes.TOOL_BUDGET_EXHAUSTED, true, true))
			.as("有副作用优先于已产出").isEqualTo("TOOL_BUDGET_EXHAUSTED_AFTER_TOOL_SIDE_EFFECT");
		assertThat(ResilientChatService.failureCode(ErrorCodes.TOOL_BUDGET_EXHAUSTED, false, true))
			.as("未登记的派生组合退回基础码（绝不发出未登记码）").isEqualTo(ErrorCodes.TOOL_BUDGET_EXHAUSTED);

		Properties zh = messages("messages_zh.properties");
		Properties en = messages("messages_en.properties");
		String zhBase = zh.getProperty("error." + ErrorCodes.TOOL_BUDGET_EXHAUSTED);
		String zhSide = zh.getProperty("error.TOOL_BUDGET_EXHAUSTED_AFTER_TOOL_SIDE_EFFECT");
		assertThat(zhBase).as("基础码：工具预算耗尽，不是「对话上下文已满」").contains("工具").contains("预算")
			.doesNotContain("对话上下文已满").doesNotContain("继续回答");
		assertThat(zhSide).as("派生码：预算耗尽 + 本轮已有工具执行").contains("工具").contains("预算")
			.contains("已执行").doesNotContain("继续回答");

		String enBase = en.getProperty("error." + ErrorCodes.TOOL_BUDGET_EXHAUSTED);
		String enSide = en.getProperty("error.TOOL_BUDGET_EXHAUSTED_AFTER_TOOL_SIDE_EFFECT");
		assertThat(enBase).as("英文不得沿用工具卡「这一步没有执行」").containsIgnoringCase("tool-call budget")
			.doesNotContain("will continue");
		assertThat(enSide).containsIgnoringCase("tool").contains("already ran")
			.doesNotContain("will continue");
	}

	private static Properties messages(String file) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = FailureCopyTest.class.getResourceAsStream("/" + file)) {
			assertThat(in).as("缺少文案文件 " + file).isNotNull();
			properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		return properties;
	}

	@Test
	void toolBudgetExhaustedHasItsOwnCopyInsteadOfTheGenericFallback() {
		String budget = ResilientChatService.safeFailureMessage(ErrorCodes.TOOL_BUDGET_EXHAUSTED);
		String overflow = ResilientChatService.safeFailureMessage(ErrorCodes.CONTEXT_OVERFLOW);
		String generic = ResilientChatService.safeFailureMessage(ErrorCodes.AI_RUN_FAILED);

		assertThat(budget).as("不许落到通用失败文案").isNotEqualTo(generic).isNotBlank();
		assertThat(budget).as("工具预算耗尽 ≠ 对话过长").isNotEqualTo(overflow);
	}
}
