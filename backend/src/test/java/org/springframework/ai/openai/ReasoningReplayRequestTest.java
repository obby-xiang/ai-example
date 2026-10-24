package org.springframework.ai.openai;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>L-98 多轮回填链路</b>：记忆里落下的 {@code reasoningContent} 必须真的能回到下一轮请求体。
 *
 * <p>
 * 链路 = {@code ResilientChatService} 写 metadata → {@link MessageJsonCodec} 存 Redis →
 * 下一轮 {@code MessageChatMemoryAdvisor} 读回 → {@link ReasoningAwareOpenAiChatModel#createRequest}
 * 回填 {@code ChatCompletionMessage} 的第 9 组件 {@code reasoning_content}
 * （DeepSeek 带 tools 时不回填会 400）。
 *
 * <p>
 * 本用例取代"补丁类是死代码"的现状：先经<b>真实序列化器</b>往返（模拟 Redis 存取），
 * 再让<b>真实补丁模型</b>发出请求，断言抓到的请求体里带全文。
 *
 * <p>
 * 放在 {@code org.springframework.ai.openai} 包下：{@code createRequest} 是包私有的覆写点，
 * 同包才能在不反射的前提下打到"补丁类真的被走到"这一层。
 */
class ReasoningReplayRequestTest {

	private static final String REASONING = "先把问题拆开：1+1=2 是皮亚诺公理的自然推论。";

	@Test
	void memoryBackedReasoningIsReplayedIntoTheNextUpstreamRequest() {
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		// 模拟 Redis 里存下的历史：assistant 消息带 reasoningContent 元数据
		List<Message> history = codec.deserializeAll(codec.serializeAll(List.of(
				new UserMessage("1+1 为什么等于 2？"),
				AssistantMessage.builder()
					.content("答案是 2")
					.properties(Map.of("reasoningContent", REASONING))
					.build())));

		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = new ReasoningAwareOpenAiChatModel(api,
				OpenAiChatOptions.builder().model("deepseek-flash").build(), mock(ToolCallingManager.class),
				RetryTemplate.defaultInstance(), ObservationRegistry.NOOP);

		model.stream(new Prompt(history, OpenAiChatOptions.builder().model("deepseek-flash").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		List<ChatCompletionMessage> sent = captor.getValue().messages();
		ChatCompletionMessage assistant = sent.stream()
			.filter(message -> message.role() == ChatCompletionMessage.Role.ASSISTANT)
			.findFirst()
			.orElseThrow(() -> new AssertionError("请求体里没有 assistant 消息"));
		assertThat(assistant.reasoningContent())
			.as("下一轮请求必须回填 reasoning_content（否则 DeepSeek 带 tools 会 400）")
			.isEqualTo(REASONING);
		assertThat(assistant.content()).isEqualTo("答案是 2");
	}

	@Test
	void assistantWithoutReasoningKeepsTheFieldNull() {
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = new ReasoningAwareOpenAiChatModel(api,
				OpenAiChatOptions.builder().model("deepseek-flash").build(), mock(ToolCallingManager.class),
				RetryTemplate.defaultInstance(), ObservationRegistry.NOOP);

		model.stream(new Prompt(List.of(new UserMessage("你好"),
				AssistantMessage.builder().content("你好，有什么可以帮你？").build()),
				OpenAiChatOptions.builder().model("deepseek-flash").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		ChatCompletionMessage assistant = captor.getValue()
			.messages()
			.stream()
			.filter(message -> message.role() == ChatCompletionMessage.Role.ASSISTANT)
			.findFirst()
			.orElseThrow();
		assertThat(assistant.reasoningContent()).as("没有思考文本就不写该字段（不伪造空串）").isNull();
	}

	private static ChatCompletionChunk doneChunk() {
		OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage("好", 
				OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null, null, null, null, null, null);
		OpenAiApi.ChatCompletionChunk.ChunkChoice choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(
				OpenAiApi.ChatCompletionFinishReason.STOP, 0, delta, null);
		return new ChatCompletionChunk("cmpl-1", List.of(choice), 1750000000L, "deepseek-flash", null, null,
				"chat.completion.chunk", null);
	}

}
