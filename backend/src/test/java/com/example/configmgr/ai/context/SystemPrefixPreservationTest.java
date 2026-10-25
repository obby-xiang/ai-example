package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>SYSTEM 消息不属于可裁剪历史</b>（续跑保 system 批）。
 *
 * <h2>缺陷形态</h2>
 * {@link ConversationTurns#split} 把"第一条 user 之前的残留"归入第 0 段，而第 0 段正好包含
 * 系统消息（人设 + 工作区快照）。于是：
 * <ul>
 * <li>{@link MemoryWindow} 按 turn 淘汰时整段丢掉第 0 段 ⇒ SYSTEM 随旧轮一起消失；</li>
 * <li>{@link RollingSummarizer} 把 {@code [0, cutIndex)} 整段折成摘要 ⇒ SYSTEM 被"摘要"掉；</li>
 * <li>{@link HardTrim} 直接 {@code subList(cutIndex, size)} ⇒ 同样丢 SYSTEM。</li>
 * </ul>
 * 丢 SYSTEM 之后续跑仍会发请求（模型没了人设与工作区约束），这是"静默降智"而不是报错。
 *
 * <h2>本批口径</h2>
 * 三个裁剪器都只处理<b>非 SYSTEM</b> 部分：SYSTEM 前缀原样保留在结果最前，且不计入被清除/
 * 摘要/丢弃的内容；预算/占比仍按整份历史计（SYSTEM 的 token 不会被当成"可以省掉的量"）。
 */
class SystemPrefixPreservationTest {

	private static final String SYSTEM_TEXT = "SYSTEM-SENTINEL 人设与工作区快照";

	/** 一条 SYSTEM + 每轮一条 user/assistant，正文足够大以便触发裁剪。 */
	private List<Message> history(int turns) {
		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(SYSTEM_TEXT));
		for (int i = 0; i < turns; i++) {
			messages.add(new UserMessage("user-" + i + " " + "u".repeat(400)));
			messages.add(new AssistantMessage("assistant-" + i + " " + "a".repeat(400)));
		}
		return messages;
	}

	@Test
	void memoryWindowKeepsSystemAtTheFrontWhenTurnsAreDropped() {
		MemoryWindow.Budget budget = new MemoryWindow.Budget(900, 1, 0, 5);

		MemoryWindow.Plan plan = MemoryWindow.plan(history(8), budget, null);

		assertThat(plan.changed()).isTrue();
		assertThat(plan.messages().get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(plan.messages().get(0).getText()).isEqualTo(SYSTEM_TEXT);
		assertThat(plan.messages().stream().filter(message -> message.getMessageType() == MessageType.SYSTEM))
			.as("SYSTEM 不得被淘汰或清除").hasSize(1);
		assertThat(plan.afterCount()).isEqualTo(plan.messages().size());
		assertThat(plan.summary()).containsEntry("droppedTurns", plan.droppedTurns());
	}

	@Test
	void rollingSummaryNeverSummarizesSystem() {
		List<String> prompts = new ArrayList<>();
		RollingSummarizer.Plan plan = RollingSummarizer.plan(history(6), 1, 4000, null,
				TestProviderRuntime.runtime(), (system, user, runtime) -> {
					prompts.add(user);
					return "SUMMARY";
				}, System.currentTimeMillis());

		assertThat(plan.changed()).isTrue();
		assertThat(plan.messages().get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(plan.messages().get(0).getText()).isEqualTo(SYSTEM_TEXT);
		assertThat(prompts).isNotEmpty();
		assertThat(prompts).allSatisfy(prompt ->
			assertThat(prompt).as("SYSTEM 不进摘要输入（否则它会被摘要替换掉）").doesNotContain(SYSTEM_TEXT));
	}

	@Test
	void hardTrimKeepsSystemAtTheFront() {
		HardTrim.Plan plan = HardTrim.plan(history(6), 1, null);

		assertThat(plan.changed()).isTrue();
		assertThat(plan.messages().get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(plan.messages().get(0).getText()).isEqualTo(SYSTEM_TEXT);
	}

	@Test
	void windowWithoutSystemKeepsItsExistingShape() {
		List<Message> messages = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			messages.add(new UserMessage("user-" + i + " " + "u".repeat(400)));
			messages.add(new AssistantMessage("assistant-" + i + " " + "a".repeat(400)));
		}

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, new MemoryWindow.Budget(900, 1, 0, 5), null);

		assertThat(plan.changed()).isTrue();
		assertThat(plan.messages()).noneMatch(message -> message.getMessageType() == MessageType.SYSTEM);
	}

}
