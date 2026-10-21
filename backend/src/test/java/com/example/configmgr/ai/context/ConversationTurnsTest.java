package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turn 切分（上下文专项批③；设计稿 §3.5 L2"裁剪单位 = Turn"）。
 *
 * <p>
 * 切分口径必须与 Spring AI 的 {@code TurnWindow} 一致：<b>一条 {@code UserMessage} 起、
 * 到下一条 {@code UserMessage} 止</b>。裁剪单位是 Turn 而不是消息条数 —— 按条数裁会在
 * {@code assistant(tool_calls)} 与 {@code tool} 之间下刀，制造孤儿对（上游 400 的经典成因）。
 */
class ConversationTurnsTest {

	@Test
	void splitsTurnsAtEveryUserMessage() {
		List<Message> messages = List.of(new AssistantMessage("前导（窗口被截断后的残留）"), new UserMessage("u1"),
				new AssistantMessage("a1"), new UserMessage("u2"), new AssistantMessage("a2"));

		List<ConversationTurns.Segment> segments = ConversationTurns.split(messages);

		assertThat(segments).hasSize(3);
		assertThat(segments.get(0).startIndex()).isZero();
		assertThat(segments.get(0).endIndex()).isEqualTo(1);
		assertThat(segments.get(1).startIndex()).isEqualTo(1);
		assertThat(segments.get(1).endIndex()).isEqualTo(3);
		assertThat(segments.get(2).startIndex()).isEqualTo(3);
		assertThat(segments.get(2).endIndex()).isEqualTo(5);
	}

	@Test
	void toolMessagesStayInsideTheirTurn() {
		List<Message> messages = List.of(new UserMessage("u1"),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "结果")))
					.build(),
				new UserMessage("u2"));

		List<ConversationTurns.Segment> segments = ConversationTurns.split(messages);

		assertThat(segments).hasSize(2);
		assertThat(segments.get(0).size()).isEqualTo(3);
		assertThat(segments.get(1).size()).isEqualTo(1);
	}

	@Test
	void emptyHistoryHasNoTurns() {
		assertThat(ConversationTurns.split(List.of())).isEmpty();
		assertThat(ConversationTurns.split(null)).isEmpty();
	}

	/** 一条 UserMessage 都没有（例如只有重建出来的工具回环）⇒ 整段算一个 Turn，绝不丢。 */
	@Test
	void historyWithoutUserMessageIsOneTurn() {
		List<Message> messages = new ArrayList<>();
		messages.add(new AssistantMessage("a"));
		messages.add(new AssistantMessage("b"));

		assertThat(ConversationTurns.split(messages)).hasSize(1)
			.extracting(ConversationTurns.Segment::size)
			.containsExactly(2);
	}

}
