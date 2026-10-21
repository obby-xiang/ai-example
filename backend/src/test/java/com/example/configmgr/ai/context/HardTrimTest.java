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
 * L3 硬截断兜底（上下文专项批③；设计稿 §3.5 / 用例 C-25、C-26）。
 *
 * <p>
 * L3 只在 L2 之后仍越 95% 线或 L2 失败时兜底：<b>按 Turn 丢最旧、pin 集不动、丢到 pin 下限即停</b>。
 * 它不追求"压到目标以内"——丢完仍超就照发请求，让上游如实报错（映射 {@code CONTEXT_OVERFLOW}），
 * <b>绝不静默截断</b>。
 */
class HardTrimTest {

	private static List<Message> turns(int count) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题 " + "问".repeat(30)));
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答 " + "答".repeat(30)).build());
		}
		return messages;
	}

	/** C-25：9 轮 keep 6 ⇒ 丢最旧 3 轮；切口落在 UserMessage 上。 */
	@Test
	void dropsOldestTurnsAndCutsAtUserBoundary() {
		List<Message> messages = turns(9);

		HardTrim.Plan plan = HardTrim.plan(messages, 6, Set.of());

		assertThat(plan.changed()).isTrue();
		assertThat(plan.droppedTurns()).isEqualTo(3);
		assertThat(plan.reason()).isEqualTo(HardTrim.REASON_HARD_TRIM);
		assertThat(plan.messages()).hasSize(12);
		assertThat(plan.messages().get(0)).isInstanceOf(UserMessage.class);
		assertThat(plan.messages().get(0).getText()).startsWith("第 4 轮问题");
		assertThat(plan.lostTokens()).isPositive();
	}

	/** C-26 / T-21：全部在 pin 窗口内 ⇒ 一刀不剪（不无限循环、不空转）。 */
	@Test
	void pinFloorStopsTrimming() {
		List<Message> messages = turns(9);

		HardTrim.Plan plan = HardTrim.plan(messages, 9, Set.of());

		assertThat(plan.changed()).isFalse();
		assertThat(plan.reason()).isEqualTo(HardTrim.REASON_NOTHING_TO_TRIM);
		assertThat(plan.messages()).isSameAs(messages);
		assertThat(plan.droppedTurns()).isZero();
	}

	/** 旧轮含未决 HITL ⇒ 截断止于该轮之前（P3：待审批 Turn 与它的配对骨架都不动）。 */
	@Test
	void protectedTurnStopsTheCut() {
		List<Message> messages = new ArrayList<>();
		messages.add(new UserMessage("第 1 轮问题"));
		messages.add(new AssistantMessage("第 1 轮回答"));
		messages.add(new UserMessage("第 2 轮问题（挂起）"));
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

		HardTrim.Plan plan = HardTrim.plan(messages, 2, Set.of("pending-1"));

		assertThat(plan.changed()).isTrue();
		assertThat(plan.droppedTurns()).isEqualTo(1);
		assertThat(plan.messages().get(0).getText()).isEqualTo("第 2 轮问题（挂起）");
	}

	/** keepRecentTurns = 0 时仍保留"本轮输入"（最后一条 UserMessage 起的那一轮）。 */
	@Test
	void zeroKeepStillProtectsTheCurrentTurn() {
		List<Message> messages = turns(4);

		HardTrim.Plan plan = HardTrim.plan(messages, 0, Set.of());

		assertThat(plan.droppedTurns()).isEqualTo(3);
		assertThat(plan.messages()).hasSize(2);
		assertThat(plan.messages().get(0).getText()).startsWith("第 4 轮问题");
	}

}
