package com.example.configmgr.ai.context;

import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 工具结果清除（T 组：C-15 / C-16 / C-19 / C-20，设计稿 §5.4）。
 *
 * <h2>这组的红线</h2>
 * <ol>
 * <li><b>不删消息</b>：条数必须不变 —— 删消息是孤儿对（{@code tool} 没有对应
 * {@code tool_calls} ⇒ 上游 400）的最常见成因；</li>
 * <li><b>占位符不复用截断标记</b>：{@code ToolResultLimiter#truncated} 的语义是"结果过长被截断"，
 * 复用会让"已清理"被误判成"被截断"；</li>
 * <li><b>最近 N 对不清理</b>（pin）：模型当下最可能要复用的就是这几对；</li>
 * <li><b>未决 HITL 条目不清理</b>：用户还要在卡片上决策，清掉结果会让"批的是什么"无从复核。</li>
 * </ol>
 */
class ToolResultClearingTest {

	/** 造 n 个"用户提问 → 工具调用 → 工具结果"的轮次（每轮一对）。 */
	private static List<Message> turns(int pairs) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= pairs; i++) {
			messages.add(new UserMessage("第 " + i + " 个问题"));
			messages.add(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c-" + i, "function", "list_tasks", "{}")))
				.build());
			messages.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("c-" + i, "list_tasks",
						"任务列表内容".repeat(50) + i)))
				.build());
		}
		return messages;
	}

	private static List<String> responseData(List<Message> messages) {
		List<String> out = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage tool) {
				tool.getResponses().forEach(response -> out.add(response.responseData()));
			}
		}
		return out;
	}

	/** C-15：6 对、keep=5 ⇒ 只清最旧 1 对；<b>消息条数不变</b>。kill：实现成删消息 ⇒ 条数断言红。 */
	@Test
	void c15OnlyTheOldestPairIsClearedAndNoMessageIsDropped() {
		List<Message> messages = turns(6);
		ToolResultClearing.Plan plan = ToolResultClearing.plan(messages, 5, Set.of());

		assertThat(plan.changed()).isTrue();
		assertThat(plan.clearedIds()).containsExactly("c-1");
		assertThat(plan.messages()).as("消息条数必须不变（删消息 = 孤儿对）").hasSameSizeAs(messages);
		assertThat(plan.messages()).extracting(Message::getMessageType)
			.containsExactlyElementsOf(messages.stream().map(Message::getMessageType).toList());
		// 配对的 assistant(tool_calls) 原样保留
		assertThat(plan.messages()).filteredOn(AssistantMessage.class::isInstance)
			.allSatisfy(message -> assertThat(((AssistantMessage) message).getToolCalls()).hasSize(1));
	}

	/** C-16：占位符 = 工具名 + 估算 token + toolCallId；不含截断标记、不暴露内部 Redis 台账键。 */
	@Test
	void c16PlaceholderCarriesNameTokensAndToolCallIdWithoutTruncationMarker() {
		List<Message> messages = turns(6);
		ToolResultClearing.Plan plan = ToolResultClearing.plan(messages, 5, Set.of());
		String placeholder = responseData(plan.messages()).get(0);
		int tokens = ContextEstimator.estimate(responseData(messages).get(0));

		// 文案即契约（2026-10-22 用户裁「占位符文案：改吧」）：前缀 + 工具名 + 估算 token + toolCallId
		// 逐字锁定；改格式必须同步改断言，不允许"contains 差不离"放过
		assertThat(placeholder).isEqualTo("[工具结果已清除：list_tasks · ≈" + tokens + " tokens · toolCallId=c-1]");
		assertThat(placeholder).startsWith(ToolResultClearing.PLACEHOLDER_PREFIX)
			.as("面向模型的文案不带内部 Redis 台账键（jargon 无信息量，还会诱导模型去查台账）")
			.doesNotContain("ai:ledger")
			.doesNotContain("原文见台账");
		ToolResultLimiter limiter = new ToolResultLimiter(new com.example.configmgr.ai.config.AiProperties());
		assertThat(limiter.truncated(placeholder)).as("不得复用截断标记（语义不同）").isFalse();
		assertThat(placeholder).doesNotContain(ToolResultLimiter.TRUNCATION_MARKER);
	}

	/** 文案改版（2026-10-22）：旧前缀不再算"已清除"⇒ 旧历史被改写一次落到新文案，之后稳定。 */
	@Test
	void legacyPlaceholderIsRewrittenOnceIntoTheNewText() {
		String legacy = "[tool_result 已清理：list_tasks · ≈120 tokens · toolCallId=c-9 · 原文见台账 ai:ledger:run-l1]";
		List<Message> messages = List.of(
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("c-9", "function", "list_tasks", "{}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-9", "list_tasks", legacy)))
					.build(),
				new UserMessage("继续"));

		assertThat(ToolResultClearing.isAlreadyCleared(legacy)).as("幂等判据只认新前缀").isFalse();

		ToolResultClearing.Plan first = ToolResultClearing.plan(messages, 0, Set.of());
		assertThat(first.clearedIds()).containsExactly("c-9");
		assertThat(responseData(first.messages()).get(0)).startsWith("[工具结果已清除：").doesNotContain("ai:ledger");

		assertThat(ToolResultClearing.plan(first.messages(), 0, Set.of()).cleared()).as("改写只发生一次")
			.isEmpty();
	}

	/** 幂等：已清除的占位符不再重复清除（重复跑压缩不该反复记账）。 */
	@Test
	void alreadyClearedPlaceholdersAreNotClearedAgain() {
		ToolResultClearing.Plan first = ToolResultClearing.plan(turns(6), 5, Set.of());
		ToolResultClearing.Plan second = ToolResultClearing.plan(first.messages(), 5, Set.of());

		assertThat(second.changed()).isFalse();
		assertThat(second.cleared()).isEmpty();
	}

	/** C-19：keep-tool-pairs 生效 —— 6 对只清 1 对；keep=0 时全清。kill：keepPairs 失效 ⇒ 多清。 */
	@Test
	void c19KeepPairsBoundsTheClearing() {
		assertThat(ToolResultClearing.plan(turns(3), 5, Set.of()).changed())
			.as("3 对 <= keep 5 ⇒ 一对都不清").isFalse();

		ToolResultClearing.Plan all = ToolResultClearing.plan(turns(6), 0, Set.of());
		assertThat(all.clearedIds()).containsExactly("c-1", "c-2", "c-3", "c-4", "c-5", "c-6");
	}

	/** C-20：未决 HITL 条目所在的工具结果不清（P3）；保护不扩大 pin 窗口。kill：不查 pendings ⇒ 清掉待审批对。 */
	@Test
	void c20PendingHitlToolResultsAreNeverCleared() {
		// keep=4 ⇒ 候选 = c-1, c-2；c-1 受保护 ⇒ 只清 c-2
		ToolResultClearing.Plan plan = ToolResultClearing.plan(turns(6), 4, Set.of("c-1"));
		assertThat(plan.clearedIds()).as("c-1 受保护 ⇒ 清下一条最旧的（且不因保护而多清一条）").containsExactly("c-2");
		assertThat(responseData(plan.messages()).get(0)).isEqualTo("任务列表内容".repeat(50) + 1);

		// keep=5 ⇒ 候选只有 c-1，而它受保护 ⇒ 一条都不清（保护不把窗口往后推）
		assertThat(ToolResultClearing.plan(turns(6), 5, Set.of("c-1")).clearedIds()).isEmpty();
	}

	/** 估算口径：savedTokens 为正且小于原文本量。 */
	@Test
	void savedTokensIsPositiveAndBounded() {
		ToolResultClearing.Plan plan = ToolResultClearing.plan(turns(6), 5, Set.of());

		assertThat(plan.savedTokens()).isGreaterThan(0);
		assertThat(plan.savedTokens()).isLessThan(ContextEstimator.estimate("任务列表内容".repeat(50) + 1));
	}

	/** 空历史/无工具结果 ⇒ 不动（不得返回 null 列表）。 */
	@Test
	void noOpOnEmptyOrToolFreeHistory() {
		assertThat(ToolResultClearing.plan(List.of(), 5, Set.of()).changed()).isFalse();
		assertThat(ToolResultClearing.plan(List.of(new UserMessage("你好")), 5, Set.of()).changed()).isFalse();
		assertThat(ToolResultClearing.plan(null, 5, Set.of()).changed()).isFalse();
	}

	/** 一条 ToolResponseMessage 里多份响应：只清该清的那几份，消息本身与其余响应不动。 */
	@Test
	void partiallyClearedMessageKeepsItsOtherResponses() {
		ToolResponseMessage multi = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "a", "第一份".repeat(50)),
					new ToolResponseMessage.ToolResponse("c-2", "b", "第二份".repeat(50))))
			.build();
		List<Message> messages = List.of(multi, new UserMessage("继续"));

		ToolResultClearing.Plan plan = ToolResultClearing.plan(messages, 1, Set.of());

		assertThat(plan.clearedIds()).containsExactly("c-1");
		List<String> data = responseData(plan.messages());
		assertThat(data.get(0)).startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
		assertThat(data.get(1)).isEqualTo("第二份".repeat(50));
	}

}
