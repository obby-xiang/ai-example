package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

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

}
