package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>上游核查用例（L-91 技术点 1）</b>：Spring AI 1.1.7 的 OpenAI 流式客户端到底会不会
 * 吞掉非标准字段 {@code reasoning_content}？答案必须是<b>可执行的</b>，而不是"读了源码觉得"。
 *
 * <p>
 * 两段证据，缺一不可：
 * <ol>
 * <li>{@link #chunkJsonKeepsReasoningContent()}：线格式 → {@code ChatCompletionChunk} 的反序列化
 * 保留了 {@code reasoning_content}（组件 9 = {@code reasoningContent}，{@code @JsonProperty}
 * 映射同名 snake_case 字段）；</li>
 * <li>{@link #streamedChatResponseCarriesReasoningInMetadata()}：<b>公开</b>流式 API
 * （{@code OpenAiChatModel#stream}）产出的 {@code ChatResponse} 里，
 * {@code getResult().getOutput().getMetadata().get("reasoningContent")} 就是该分片的思考文本 ——
 * 即 1.1.7 已经把 reasoning 挂进 {@code AssistantMessage} 的 metadata（properties），
 * <b>不需要自定义反序列化、也不需要绕开 spring-ai 自建原始流处理</b>。</li>
 * </ol>
 *
 * <p>
 * 旁证（字节码，2026-10-23 复核 1.1.7 构件）：{@code OpenAiChatModel#lambda$internalStream$6}
 * 用 {@code Map.of("finishReason", …, "reasoningContent", choice.message().reasoningContent())}
 * 交给 {@code buildGeneration}，后者 {@code AssistantMessage.builder().properties(map)} ——
 * 与 {@link OpenAiChatModel} 的补丁类 {@code ReasoningAwareOpenAiChatModel} 读的键同名，
 * 故"回填"与"透传"共用同一份 metadata。
 *
 * <p>
 * <b>升版守卫</b>：本用例是"1.1.7/1.1.8 不吞 reasoning"的机制证明。升级 Spring AI 后若上游
 * 改成 {@code metadata} 换个键名或不再透出，本用例先红，{@code ResilientChatService} 的
 * reasoning 帧通路才不会静默失效。
 */
class ReasoningContentUpstreamProbeTest {

	/** 一条带 reasoning_content 的 OpenAI 兼容分片（deepseek-flash 思考模式的真实线格式）。 */
	private static final String CHUNK_JSON = """
			{"id":"cmpl-1","object":"chat.completion.chunk","created":1750000000,"model":"deepseek-flash",
			 "choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":"先想一步"},
			             "finish_reason":null}]}
			""";

	@Test
	void chunkJsonKeepsReasoningContent() throws Exception {
		ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		ChatCompletionChunk chunk = mapper.readValue(CHUNK_JSON, ChatCompletionChunk.class);

		assertThat(chunk.choices()).hasSize(1);
		assertThat(chunk.choices().get(0).delta().reasoningContent())
				.as("reasoning_content 必须落在 ChatCompletionMessage.reasoningContent（组件 9）上")
				.isEqualTo("先想一步");
	}

	@Test
	void streamedChatResponseCarriesReasoningInMetadata() {
		OpenAiApi api = mock(OpenAiApi.class);
		when(api.chatCompletionStream(any(), any())).thenReturn(Flux.just(chunkOf("", "先想一步"),
				chunkOf("", "再想一步"), chunkOf("答案是 2", "")));
		OpenAiChatModel model = new OpenAiChatModel(api, OpenAiChatOptions.builder().model("deepseek-flash").build(),
				mock(ToolCallingManager.class), RetryTemplate.defaultInstance(), ObservationRegistry.NOOP);

		List<ChatResponse> responses = model.stream(new Prompt("1+1 为什么等于 2？")).collectList().block();

		assertThat(responses).as("三个分片各产出一个 ChatResponse").hasSize(3);
		assertThat(reasoningOf(responses.get(0))).isEqualTo("先想一步");
		assertThat(reasoningOf(responses.get(1))).isEqualTo("再想一步");
		assertThat(reasoningOf(responses.get(2))).as("正文分片没有 reasoning ⇒ 空串（spring-ai 用空串占位）")
				.isEmpty();
		assertThat(responses.get(2).getResult().getOutput().getText()).isEqualTo("答案是 2");
	}

	/** 分片里 {@code reason.ingContent} 的口径：metadata 键 = {@code reasoningContent}。 */
	static String reasoningOf(ChatResponse response) {
		AssistantMessage message = response.getResult().getOutput();
		Object value = message.getMetadata().get("reasoningContent");
		return value instanceof String text ? text : null;
	}

	/** 构造一个分片（与线格式同形：delta 组件 9 = reasoning_content）。 */
	static ChatCompletionChunk chunkOf(String content, String reasoningContent) {
		OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage(content,
				OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null, null, null, null, null, reasoningContent);
		OpenAiApi.ChatCompletionChunk.ChunkChoice choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, delta,
				null);
		return new ChatCompletionChunk("cmpl-1", List.of(choice), 1750000000L, "deepseek-flash", null, null,
				"chat.completion.chunk", null);
	}

}
