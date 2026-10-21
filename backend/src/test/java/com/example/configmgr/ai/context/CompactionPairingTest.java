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
 * 压缩后的工具配对完整性（上下文专项批③；设计稿 §3.5 / 用例 C-17、C-18 的单测面）。
 *
 * <p>
 * 每个 {@code tool_call.id} 恰有一条 {@code tool} 响应、反之亦然。L1 只改 {@code responseData}、
 * L2/L3 以 Turn（整轮）为单位替换/丢弃 —— 三条路径都<b>不可能</b>在配对骨架中间下刀。
 * 这是"压缩后上游不报 400"的构造性保证，不是事后校验。
 */
class CompactionPairingTest {

	private static List<Message> turns(int count) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题 " + "问".repeat(30)));
			messages.add(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c-" + i, "function", "list_tasks", "{}")))
				.build());
			messages.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("c-" + i, "list_tasks", "结果 " + i)))
				.build());
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答").build());
		}
		return messages;
	}

	private static List<String> callIds(List<Message> messages) {
		List<String> ids = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					ids.add(call.id());
				}
			}
		}
		return ids;
	}

	private static List<String> responseIds(List<Message> messages) {
		List<String> ids = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage toolResponse) {
				for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
					ids.add(response.id());
				}
			}
		}
		return ids;
	}

	private static void assertPaired(List<Message> messages) {
		assertThat(responseIds(messages)).as("每个 tool_call 恰有一条 tool 响应（0 孤儿）")
			.containsExactlyInAnyOrderElementsOf(callIds(messages));
	}

	@Test
	void l1ClearingKeepsPairing() {
		List<Message> messages = turns(6);

		ToolResultClearing.Plan plan = ToolResultClearing.plan(messages, 2, Set.of(), "run-1");

		assertThat(plan.changed()).isTrue();
		assertThat(plan.messages()).hasSameSizeAs(messages);
		assertPaired(plan.messages());
	}

	@Test
	void l2SummaryKeepsPairing() {
		List<Message> messages = turns(9);

		RollingSummarizer.Plan plan = RollingSummarizer.plan(messages, 3, 8000, Set.of(), prompt -> "摘要",
				1_700_000_000_000L);

		assertThat(plan.changed()).isTrue();
		assertPaired(plan.messages());
	}

	@Test
	void l3HardTrimKeepsPairing() {
		List<Message> messages = turns(9);

		HardTrim.Plan plan = HardTrim.plan(messages, 3, Set.of());

		assertThat(plan.changed()).isTrue();
		assertPaired(plan.messages());
	}

	/** 修剪切口永远落在 UserMessage 上：不会被丢成"history 以 assistant(tool_calls) 开头"。 */
	@Test
	void trimmedHistoryAlwaysStartsAtAUserMessage() {
		HardTrim.Plan plan = HardTrim.plan(turns(9), 3, Set.of());

		assertThat(plan.messages().get(0)).isInstanceOf(UserMessage.class);
	}

}
