package com.example.configmgr.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>L-98</b>：思考文本随对话记忆一起落 Redis（{@code chat:mem:<sid>}）的<b>序列化往返</b>。
 *
 * <p>
 * 写入方在 {@code ResilientChatService} 的落定 / 取消两条路径（metadata 键
 * {@code reasoningContent}）；本用例只锁存储层的保真面：
 * <ol>
 * <li>原文 JSON 里真的出现该键与全文（不是只在内存对象上挂着）；</li>
 * <li>{@code deserializeAll} 之后 {@code getMetadata()} 仍拿得到同一份全文
 *     —— 下一轮 {@code ReasoningAwareOpenAiChatModel} 读的就是这个槽位；</li>
 * <li>往返对同一消息幂等（{@code canonicalJson} 相等）。</li>
 * </ol>
 */
class ReasoningMetadataCodecTest {

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	@Test
	void assistantReasoningContentSurvivesTheRedisRoundTrip() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("答案是 2")
			.properties(Map.of("reasoningContent", "先想一步。\n再想一步。"))
			.build();

		List<Message> restored = this.codec.deserializeAll(
				this.codec.serializeAll(List.of(new UserMessage("1+1 为什么等于 2？"), assistant)));

		assertThat(restored).hasSize(2);
		Message last = restored.get(1);
		assertThat(last).isInstanceOf(AssistantMessage.class);
		assertThat(last.getMetadata()).as("思考全文必须原样挂在 metadata 上（键名与补丁类同源）")
			.containsEntry("reasoningContent", "先想一步。\n再想一步。");
		assertThat(last.getText()).isEqualTo("答案是 2");
	}

	@Test
	void serializedJsonCarriesTheReasoningTextVerbatim() {
		String reasoning = "第一段思考；第二段含有换行与中文标点：" + "甲".repeat(200);
		AssistantMessage assistant = AssistantMessage.builder()
			.content("正文")
			.properties(Map.of("reasoningContent", reasoning))
			.build();

		String json = this.codec.serialize(assistant);

		assertThat(json).contains("\"reasoningContent\"");
		assertThat(this.codec.deserialize(json).getMetadata()).containsEntry("reasoningContent", reasoning);
		assertThat(this.codec.canonicalJson(this.codec.deserialize(json)))
			.as("往返对同一消息幂等（时间戳之外逐字段相等）")
			.isEqualTo(this.codec.canonicalJson(assistant));
	}

	@Test
	void assistantWithoutReasoningKeepsNoReasoningKey() {
		AssistantMessage assistant = AssistantMessage.builder().content("普通回答").build();

		String json = this.codec.serialize(assistant);

		assertThat(json).doesNotContain("reasoningContent");
		assertThat(this.codec.deserialize(json).getMetadata()).doesNotContainKey("reasoningContent");
	}

}
