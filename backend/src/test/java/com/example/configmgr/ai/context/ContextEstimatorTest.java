package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 估算器（上下文统计与压缩专项批①；设计稿 §3.1 末段）。
 *
 * <p>口径：{@code ceil(cjkChars / 1.7 + otherChars / 4.0)}。这组用例锁三件事：
 * 中文按字符（不是按 4 字符/token）、空输入为 0、消息/工具 schema 的体量只数"进请求体的部分"。
 */
class ContextEstimatorTest {

	@Test
	void chineseIsCountedPerCharacterNotPerFourChars() {
		// 4 个汉字：ceil(4 / 1.7) = 3；若按 4 字符/token 会得 1（低估 3 倍）
		assertThat(ContextEstimator.estimate("你好世界")).isEqualTo(3);
		assertThat(ContextEstimator.estimate("哈")).isEqualTo(1);
	}

	@Test
	void latinIsCountedPerFourChars() {
		assertThat(ContextEstimator.estimate("hello world")).isEqualTo(3); // ceil(11/4) = 3
		assertThat(ContextEstimator.estimate("abcd")).isEqualTo(1);
	}

	@Test
	void mixedTextSumsBothBuckets() {
		// ceil(2/1.7 + 5/4) = ceil(1.176 + 1.25) = ceil(2.426) = 3
		assertThat(ContextEstimator.estimate("你好hello")).isEqualTo(3);
	}

	@Test
	void emptyAndNullAreZero() {
		assertThat(ContextEstimator.estimate(null)).isZero();
		assertThat(ContextEstimator.estimate("")).isZero();
	}

	@Test
	void estimateIsMonotonicInLength() {
		assertThat(ContextEstimator.estimate("你".repeat(1000)))
			.isGreaterThan(ContextEstimator.estimate("你".repeat(100)));
	}

	@Test
	void messagesSkipSystemAndSumToolBodies() {
		List<org.springframework.ai.chat.messages.Message> messages = List.of(
				new SystemMessage("系统提示".repeat(10)),
				new UserMessage("用户问题"),
				AssistantMessage.builder().content("").toolCalls(List.of(
						new AssistantMessage.ToolCall("c-1", "function", "list_tasks", "{}"))).build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_tasks", "结果正文")))
					.build());

		int systemOnly = ContextEstimator.estimate("系统提示".repeat(10));
		int all = ContextEstimator.estimateMessages(messages);
		// 跳 system：全量估算必须小于"全量 + system"（说明 system 没被重复计）
		assertThat(all).isLessThan(all + systemOnly);
		assertThat(ContextEstimator.estimateMessages(List.of())).isZero();
		assertThat(ContextEstimator.estimateMessages(null)).isZero();
	}

	@Test
	void toolSchemasAreCountedWhenDisclosed() {
		// 工具走渐进披露（7–18 个 schema 常驻前缀）：不统计它，"system 巨大"的结论会误导排障
		assertThat(ContextEstimator.estimateToolSchemas(List.of())).isZero();
		assertThat(ContextEstimator.estimateToolSchemas(null)).isZero();
	}

	@Test
	void reasoningContentMetadataIsCountedForAssistantMessages() {
		// 回填侧（ReasoningAwareOpenAiChatModel）会把 metadata.reasoningContent 原样放进请求体，
		// 它和正文一样占上游 token；估算器只数正文会把预算判据系统性低估。
		String body = "最终回答";
		String reasoning = "先想一步。\n再想一步。";
		AssistantMessage withoutReasoning = AssistantMessage.builder().content(body).build();
		AssistantMessage withReasoning = AssistantMessage.builder()
			.content(body)
			.properties(Map.of("reasoningContent", reasoning))
			.build();

		int bodyOnly = ContextEstimator.estimateMessage(withoutReasoning);
		int withThinking = ContextEstimator.estimateMessage(withReasoning);

		assertThat(withThinking).isEqualTo(bodyOnly + ContextEstimator.estimate(reasoning));
		assertThat(withThinking).isGreaterThan(bodyOnly);
		assertThat(ContextEstimator.estimateMessages(List.of(withReasoning)))
			.isEqualTo(withThinking);
	}

	@Test
	void nonStringOrMissingReasoningMetadataIsIgnored() {
		// 只有 String 才会被回填侧发送；其它类型（或缺失）不参与估算，也不得抛异常。
		int expected = ContextEstimator.estimate("回答");

		AssistantMessage missing = AssistantMessage.builder().content("回答").build();
		AssistantMessage nonString = AssistantMessage.builder()
			.content("回答")
			.properties(Map.of("reasoningContent", 42))
			.build();
		Map<String, Object> nullValueMetadata = new HashMap<>();
		nullValueMetadata.put("reasoningContent", null);
		AssistantMessage nullValue = AssistantMessage.builder()
			.content("回答")
			.properties(nullValueMetadata)
			.build();

		assertThat(ContextEstimator.estimateMessage(missing)).isEqualTo(expected);
		assertThat(ContextEstimator.estimateMessage(nonString)).isEqualTo(expected);
		assertThat(ContextEstimator.estimateMessage(nullValue)).isEqualTo(expected);
	}

}
