package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轮内"仍超窗"时的工具结果<b>收窄</b>（C13 第 3 项）。
 *
 * <p>与 L1 清除的分工：L1 只清 {@code keep-tool-pairs} 之外的旧结果；<b>最近的几对</b>它一律不碰。
 * 小窗口上恰恰是这几对（本批工具结果）最占地方，于是越窗之后还有一刀：把<b>最新、未受 HITL 保护</b>
 * 的结果<b>收窄</b>（不是清除、不是删除），保留头部分片与截断元数据。
 *
 * <p>四条不可动的边界（上游 400 的经典成因都在这里）：
 * <ol>
 * <li>不删消息、不改 id ⇒ 配对骨架不变；</li>
 * <li>未决 HITL 条目的结果一个字都不动（用户还要在卡片上复核"批的是什么"）；</li>
 * <li>已清除的占位符不重复处理（幂等）；</li>
 * <li>截断字段仍走 {@link ToolResultLimiter.Truncation} 同一套（模型看得见"不是全部"）。</li>
 * </ol>
 */
class ToolResultShrinkTest {

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	@Test
	void shrinksTheNewestUnprotectedResultAndKeepsPairing() {
		List<Message> history = historyWithResults(20_000, "旧结果：", "中结果：", "最新结果：");

		ToolResultShrink.Plan plan = ToolResultShrink.plan(history, 20_000, 10_000, 0, Set.of(), this.limiter);

		assertThat(plan.changed()).isTrue();
		assertThat(plan.shrunkIds()).as("最新的一对最先收窄").containsExactly("c-3");
		assertThat(plan.savedTokens()).isPositive();
		ToolResultEnvelope newest = envelopeOf(plan.messages(), "c-3");
		assertThat(newest.truncated()).isTrue();
		assertThat(newest.originalChars()).isGreaterThan(newest.returnedChars());
		assertThat(newest.message().length()).as("收窄后明显更短").isLessThan(20_000);
		assertThat(envelopeOf(plan.messages(), "c-1").truncated()).as("旧结果本轮不动（L1 的活）").isFalse();
		assertPairing(plan.messages(), history);
	}

	@Test
	void hitlProtectedResultsAreNeverShrunk() {
		List<Message> history = historyWithResults(20_000, "旧结果：", "中结果：", "最新结果：");

		ToolResultShrink.Plan plan = ToolResultShrink.plan(history, 20_000, 10_000, 0, Set.of("c-3"), this.limiter);

		assertThat(envelopeOf(plan.messages(), "c-3").truncated()).as("未决 HITL 条目一个字都不动").isFalse();
		assertThat(envelopeOf(plan.messages(), "c-3").message()).isEqualTo(envelopeOf(history, "c-3").message());
		assertThat(plan.shrunkIds()).as("退到下一个未保护的条目").containsExactly("c-2");
	}

	@Test
	void alreadyClearedPlaceholdersAreNotTouched() {
		List<Message> history = new ArrayList<>();
		history.add(new UserMessage("旧问题"));
		history.add(AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
			.build());
		history.add(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks",
					ToolResultClearing.placeholder("list_tasks", 9_000, "c-1"))))
			.build());

		ToolResultShrink.Plan plan = ToolResultShrink.plan(history, 20_000, 10_000, 0, Set.of(), this.limiter);

		assertThat(plan.changed()).as("占位符不在收窄范围内").isFalse();
		assertThat(plan.messages()).isSameAs(history);
	}

	@Test
	void nothingHappensWhenTheEstimateAlreadyFits() {
		List<Message> history = historyWithResults(20_000, "旧结果：", "中结果：", "最新结果：");

		ToolResultShrink.Plan plan = ToolResultShrink.plan(history, 5_000, 100_000, 0, Set.of(), this.limiter);

		assertThat(plan.changed()).isFalse();
		assertThat(plan.messages()).isSameAs(history);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static List<Message> historyWithResults(int repeat, String... prefixes) {
		List<Message> history = new ArrayList<>();
		history.add(new UserMessage("问题"));
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (int i = 0; i < prefixes.length; i++) {
			String id = "c-" + (i + 1);
			String message = prefixes[i] + "大".repeat(repeat);
			calls.add(new AssistantMessage.ToolCall(id, "function", "list_tasks", "{}"));
			responses.add(new ToolResponseMessage.ToolResponse(id, "list_tasks",
					ToolResultEnvelope.of(com.example.configmgr.ai.tool.ToolOutcome.OK, null, message, "{}")
						.toModelJson(null, "list_tasks")));
		}
		history.add(AssistantMessage.builder().toolCalls(calls).build());
		history.add(ToolResponseMessage.builder().responses(responses).build());
		history.add(new UserMessage("新问题"));
		return history;
	}

	private static ToolResultEnvelope envelopeOf(List<Message> history, String toolCallId) {
		for (Message message : history) {
			if (message instanceof ToolResponseMessage toolMessage) {
				for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
					if (toolCallId.equals(response.id())) {
						return ToolResultEnvelope.fromJson(response.responseData());
					}
				}
			}
		}
		throw new AssertionError("找不到工具结果 " + toolCallId);
	}

	private static void assertPairing(List<Message> history, List<Message> original) {
		assertThat(history).as("不删消息、不改结构").hasSameSizeAs(original);
		Set<String> calls = new HashSet<>();
		Set<String> responses = new HashSet<>();
		for (Message message : history) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				assistant.getToolCalls().forEach(call -> calls.add(call.id()));
			}
			if (message instanceof ToolResponseMessage toolMessage) {
				toolMessage.getResponses().forEach(response -> responses.add(response.id()));
			}
		}
		assertThat(responses).isEqualTo(calls);
	}

}
