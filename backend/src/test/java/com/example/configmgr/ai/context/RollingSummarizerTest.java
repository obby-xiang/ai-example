package com.example.configmgr.ai.context;

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
 * L2 按 Turn 滑窗 + 递归摘要（上下文专项批③；设计稿 §3.5 / 用例 C-21..C-24）。
 *
 * <p>
 * 锁五条契约：<b>裁 Turn 不裁消息</b>、<b>摘要六项保留清单</b>、<b>递归分块（每次请求不越 chunk）</b>、
 * <b>失败即原样（绝不 catch 成截断）</b>、<b>pin 集逐字保留</b>。
 */
class RollingSummarizerTest {

	private static final String SUMMARY = "用户想把任务列表导出为 CSV；已确认工作区 <workspace>；无不可回滚动作。";

	private static List<Message> turns(int count, int padChars) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题 " + "问".repeat(padChars)));
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答 " + "答".repeat(padChars)).build());
		}
		return messages;
	}

	private static RollingSummarizer.Plan plan(List<Message> messages, int keepRecentTurns, int chunkTokens,
			Set<String> protectedIds, RollingSummarizer.Summarizer summarizer) {
		return RollingSummarizer.plan(messages, keepRecentTurns, chunkTokens, protectedIds, summarizer, 1_700_000_000_000L);
	}

	private static final class Capturing implements RollingSummarizer.Summarizer {

		private final List<String> prompts = new ArrayList<>();

		private final String answer;

		private Capturing(String answer) {
			this.answer = answer;
		}

		@Override
		public String summarize(String prompt) {
			this.prompts.add(prompt);
			return this.answer;
		}

	}

	/** C-21：最旧 N−keep 轮被替换为"合成 UserMessage + Assistant 摘要"两条，元数据齐全。 */
	@Test
	void replacesOldestTurnsWithSummaryPair() {
		List<Message> messages = turns(9, 50);
		Capturing summarizer = new Capturing(SUMMARY);

		RollingSummarizer.Plan plan = plan(messages, 6, 8000, Set.of(), summarizer);

		assertThat(plan.changed()).isTrue();
		assertThat(plan.summarizedTurns()).isEqualTo(3);
		assertThat(plan.reason()).isEqualTo(RollingSummarizer.REASON_SUMMARY);
		assertThat(plan.messages()).hasSize(2 + 6 * 2);
		assertThat(plan.lostTokens()).isPositive();
		assertThat(plan.summary()).isEqualTo(SUMMARY);

		Message head = plan.messages().get(0);
		assertThat(head).isInstanceOf(UserMessage.class);
		assertThat(head.getText()).isEqualTo(RollingSummarizer.SUMMARY_PLACEHOLDER);
		assertThat(ContextBreakdown.isCompacted(head)).as("metadata compacted=true 是前端提示条与 summary 分项的唯一判据").isTrue();
		assertThat(head.getMetadata()).containsEntry(RollingSummarizer.META_TURNS, 3)
			.containsEntry(RollingSummarizer.META_LOST, plan.lostTokens())
			.containsEntry(RollingSummarizer.META_AT, 1_700_000_000_000L);
		assertThat(plan.messages().get(1)).isInstanceOf(AssistantMessage.class);
		assertThat(plan.messages().get(1).getText()).isEqualTo(SUMMARY);
		assertThat(ContextBreakdown.isCompacted(plan.messages().get(1))).isTrue();

		assertThat(plan.messages().get(2)).isInstanceOf(UserMessage.class);
		assertThat(plan.messages().get(2).getText()).startsWith("第 4 轮问题");
	}

	/** C-22：摘要请求体必须带六项保留清单 + 跟随用户语言。 */
	@Test
	void summaryPromptCarriesTheSixSectionChecklist() {
		Capturing summarizer = new Capturing(SUMMARY);

		plan(turns(9, 20), 6, 8000, Set.of(), summarizer);

		assertThat(summarizer.prompts).isNotEmpty();
		String prompt = summarizer.prompts.get(0);
		for (String section : RollingSummarizer.REQUIRED_SECTIONS) {
			assertThat(prompt).as("保留清单缺段：%s", section).contains(section);
		}
		assertThat(prompt.toLowerCase()).as("摘要必须跟随用户语言（i18n 附录 A）").contains("language");
	}

	/** C-23：30K 历史按 8K 分块 ⇒ 多请求；每个分块的 transcript 估算 ≤ chunk-tokens。 */
	@Test
	void chunksRecursivelyAndNeverExceedsChunkBudget() {
		List<Message> messages = turns(30, 1000);
		Capturing summarizer = new Capturing(SUMMARY);

		RollingSummarizer.Plan plan = plan(messages, 6, 8000, Set.of(), summarizer);

		assertThat(plan.changed()).isTrue();
		assertThat(plan.requests()).isEqualTo(summarizer.prompts.size());
		assertThat(plan.requests()).as("30K 历史不可能一块摘要完").isGreaterThanOrEqualTo(4);
		for (String prompt : summarizer.prompts) {
			String marker = prompt.contains(RollingSummarizer.BODY_MARKER) ? RollingSummarizer.BODY_MARKER
					: RollingSummarizer.MERGE_MARKER;
			int index = prompt.indexOf(marker);
			assertThat(index).as("每个摘要请求都必须带分块/合并正文标记").isGreaterThanOrEqualTo(0);
			String body = prompt.substring(index + marker.length());
			assertThat(ContextEstimator.estimate(body)).as("分块/合并正文不得超过 chunk-tokens").isLessThanOrEqualTo(8000);
		}
	}

	/** C-24：摘要抛错 ⇒ 历史逐字不变、无占位、reason=summary_failed。 */
	@Test
	void summaryFailureLeavesHistoryUntouched() {
		List<Message> messages = turns(9, 50);
		RollingSummarizer.Summarizer broken = prompt -> {
			throw new IllegalStateException("上游 500");
		};

		RollingSummarizer.Plan plan = plan(messages, 6, 8000, Set.of(), broken);

		assertThat(plan.changed()).isFalse();
		assertThat(plan.reason()).isEqualTo(RollingSummarizer.REASON_SUMMARY_FAILED);
		assertThat(plan.messages()).isSameAs(messages);
	}

	/** 摘要产物为空/全空白 ⇒ 同样按失败处理（产物不可用，不落一个空摘要绕过判定）。 */
	@Test
	void blankSummaryIsAFailure() {
		List<Message> messages = turns(9, 50);

		RollingSummarizer.Plan plan = plan(messages, 6, 8000, Set.of(), prompt -> "   ");

		assertThat(plan.changed()).isFalse();
		assertThat(plan.reason()).isEqualTo(RollingSummarizer.REASON_SUMMARY_FAILED);
		assertThat(plan.messages()).isSameAs(messages);
	}

	/** P3/P7：未决 HITL 所在 Turn 逐字保留 ⇒ 摘要前缀止于它之前。 */
	@Test
	void protectedTurnIsNeverSummarized() {
		List<Message> messages = new ArrayList<>();
		messages.add(new UserMessage("第 1 轮问题"));
		messages.add(new AssistantMessage("第 1 轮回答"));
		messages.add(new UserMessage("第 2 轮问题（挂起待审批）"));
		messages.add(AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("pending-1", "function", "delete_task", "{}")))
			.build());
		messages.add(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("pending-1", "delete_task", "等待确认")))
			.build());
		for (int i = 3; i <= 9; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题"));
			messages.add(new AssistantMessage("第 " + i + " 轮回答"));
		}

		RollingSummarizer.Plan plan = plan(messages, 6, 8000, Set.of("pending-1"), new Capturing(SUMMARY));

		assertThat(plan.changed()).isTrue();
		assertThat(plan.summarizedTurns()).as("摘要只能覆盖受保护轮之前的前缀").isEqualTo(1);
		assertThat(plan.messages().get(2).getText()).isEqualTo("第 2 轮问题（挂起待审批）");
	}

	/** 没有可摘要的旧轮（全部在 keep 窗口内）⇒ 明确报 nothing_to_summarize，不写框架。 */
	@Test
	void nothingToSummarizeIsReported() {
		List<Message> messages = turns(3, 20);
		Capturing summarizer = new Capturing(SUMMARY);

		RollingSummarizer.Plan plan = plan(messages, 6, 8000, Set.of(), summarizer);

		assertThat(plan.changed()).isFalse();
		assertThat(plan.reason()).isEqualTo(RollingSummarizer.REASON_NOTHING_TO_SUMMARIZE);
		assertThat(summarizer.prompts).isEmpty();
	}

}
