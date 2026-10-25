package org.springframework.ai.openai;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.ReasoningOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
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

	// ── 跨供应方推理元数据隔离（本批） ────────────────────────────────────────

	/**
	 * A → A：同供应方、同 run —— 推理与<b>未知</b> metadata 全保真（存储不动），
	 * 且协议层只回放 {@code reasoningContent}（其余元数据从不进请求体，故隔离面恰好等于协议面）。
	 */
	@Test
	void sameProviderKeepsReasoningAndStoresUnknownMetadataVerbatim() throws Exception {
		AssistantMessage stored = storedAssistantWithRichMetadata(7L, "model-a");
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = modelWithCurrentProvider(api, 7L);

		model.stream(new Prompt(List.of(new UserMessage("继续"), stored),
				OpenAiChatOptions.builder().model("model-a").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		ChatCompletionMessage sent = onlyAssistant(captor.getValue());
		assertThat(sent.reasoningContent()).as("同供应方必须回放（否则带 tools 的上游会 400）").isEqualTo(REASONING);
		assertThat(sent.content()).isEqualTo("答案正文");
		// 协议层只带 reasoningContent：其余推理/未知元数据不在请求体里（不是"清洗"，是从不发送）
		String requestJson = new ObjectMapper().writeValueAsString(captor.getValue());
		assertThat(requestJson).doesNotContain("encryptedContent").doesNotContain("signature")
			.doesNotContain(ReasoningOrigin.KEY_PROVIDER_ID);
		// 存储面原样保留（隔离只作用于模型输入）
		assertThat(stored.getMetadata()).containsEntry("reasoningContent", REASONING)
			.containsEntry("encryptedContent", "gAAA-opaque==")
			.containsEntry("signature", "sig-1");
		assertThat(providerIdOf(stored.getMetadata())).isEqualTo(7L);
	}

	/**
	 * A → B：A 的 {@code reasoningContent} 不得进入 B 的请求；正文 / toolCalls / toolResponses 配对原样，
	 * 存储里的私有元数据一个字都不删。
	 */
	@Test
	void crossProviderDropsOnlyTheInjectedReasoningButKeepsTheMessageShape() throws Exception {
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		AssistantMessage toolCall = AssistantMessage.builder()
			.content("")
			.properties(richMetadata(7L, "model-a"))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "start_export",
					"{\"taskId\":9}")))
			.build();
		List<Message> history = codec.deserializeAll(codec.serializeAll(List.of(
				new UserMessage("启动导出"),
				toolCall,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "start_export", "已启动")))
					.build())));
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		// 当前的 B 供应方 = providerId 8
		ReasoningAwareOpenAiChatModel model = modelWithCurrentProvider(api, 8L);

		model.stream(new Prompt(history, OpenAiChatOptions.builder().model("model-b").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		ChatCompletionMessage sent = onlyAssistant(captor.getValue());
		assertThat(sent.reasoningContent()).as("A 的私有推理字段不得进入 B 的请求").isNull();
		assertThat(sent.toolCalls()).as("toolCalls 原样（id/name/args）").hasSize(1);
		assertThat(sent.toolCalls().get(0).id()).isEqualTo("call-1");
		assertThat(sent.toolCalls().get(0).function().name()).isEqualTo("start_export");
		assertThat(sent.toolCalls().get(0).function().arguments()).isEqualTo("{\"taskId\":9}");
		ChatCompletionMessage tool = captor.getValue()
			.messages()
			.stream()
			.filter(message -> message.role() == ChatCompletionMessage.Role.TOOL)
			.findFirst()
			.orElseThrow(() -> new AssertionError("请求体里没有 role:tool"));
		assertThat(tool.toolCallId()).as("toolCallId 配对不变").isEqualTo("call-1");
		assertThat(tool.content()).isEqualTo("已启动");
		// 存储不删字：历史里的 metadata 仍在（含 A 的私有键）
		AssistantMessage reloaded = (AssistantMessage) history.get(1);
		assertThat(reloaded.getMetadata()).containsEntry("reasoningContent", REASONING)
			.containsEntry("encryptedContent", "gAAA-opaque==");
		assertThat(providerIdOf(reloaded.getMetadata())).isEqualTo(7L);
	}

	/**
	 * 旧数据（无 origin）：<b>声明的兼容策略 = 保持本批之前的透传</b>（fail-open）。
	 *
	 * <p>理由：来源未知时无法判定"是否跨供应方"，而 fail-closed 会让本批之前写入的 DeepSeek
	 * 工具轮历史在下一轮直接 400（存量会话 TTL 24h，兼容窗口有界）。本策略不假装知道来源：
	 * 只是"未知 ⇒ 沿用旧行为"，且新写入的消息一定带 origin，窗口随旧数据过期收敛。
	 */
	@Test
	void legacyMessageWithoutOriginKeepsThePreBatchReplay() {
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = modelWithCurrentProvider(api, 7L);

		model.stream(new Prompt(List.of(new UserMessage("继续"),
				AssistantMessage.builder().content("旧答案")
					.properties(Map.of("reasoningContent", REASONING))
					.build()),
				OpenAiChatOptions.builder().model("model-a").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		assertThat(onlyAssistant(captor.getValue()).reasoningContent())
			.as("无 origin = 来源未知：按声明的兼容策略沿用旧透传").isEqualTo(REASONING);
	}

	/**
	 * 同供应商、不同 model：<b>可回放</b>（声明口径 = 供应方级判定）。
	 *
	 * <p>理由：协议层回放的只是<b>纯文本</b> reasoningContent（不是加密/签名字段），它是供应方的
	 * 协议约定而不是某个模型的密钥；把 model 纳入判定会让"同供应商切换模型"的历史被误判成跨供应方，
	 * 反而触发上游 400。加密/签名/未知字段本就不进请求体，跨模型不构成泄漏面。
	 */
	@Test
	void sameProviderDifferentModelStillReplays() {
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = modelWithCurrentProvider(api, 7L);

		model.stream(new Prompt(List.of(new UserMessage("继续"),
				AssistantMessage.builder().content("旧答案").properties(richMetadata(7L, "model-a")).build()),
				OpenAiChatOptions.builder().model("model-a2").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		assertThat(onlyAssistant(captor.getValue()).reasoningContent()).isEqualTo(REASONING);
	}

	/** env 合成行（无 providerId）→ DB 供应方：视为切换（null vs 非 null），不回放。 */
	@Test
	void envOriginToDbProviderIsTreatedAsASwitch() {
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = modelWithCurrentProvider(api, 7L);

		model.stream(new Prompt(List.of(new UserMessage("继续"),
				AssistantMessage.builder().content("旧答案").properties(richMetadata(null, "env-model")).build()),
				OpenAiChatOptions.builder().model("model-a").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		assertThat(onlyAssistant(captor.getValue()).reasoningContent())
			.as("来源是 env 合成行、当前是 DB 供应方 ⇒ 不能当作同源").isNull();
	}

	/**
	 * 当前供应方未知（未接线）+ 消息<b>有</b>来源标记 ⇒ <b>不回放</b>（fail-closed）。
	 *
	 * <p>不知道"现在是谁"，就不该把别人的推理发出去；而无标记的旧数据仍走兼容策略（见上一例），
	 * 两者不冲突。生产装配恒带 providerId，本分支只在未接线的构造/单测里出现。
	 */
	@Test
	void stampedMessageIsNotReplayedWhenTheCurrentProviderIsUnknown() {
		OpenAiApi api = mock(OpenAiApi.class);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		// 旧 5 参构造 = 未接线（currentProviderId = null）
		ReasoningAwareOpenAiChatModel model = new ReasoningAwareOpenAiChatModel(api,
				OpenAiChatOptions.builder().model("model-a").build(), mock(ToolCallingManager.class),
				RetryTemplate.defaultInstance(), ObservationRegistry.NOOP);

		model.stream(new Prompt(List.of(new UserMessage("继续"),
				AssistantMessage.builder().content("旧答案").properties(richMetadata(7L, "model-a")).build()),
				OpenAiChatOptions.builder().model("model-a").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		assertThat(onlyAssistant(captor.getValue()).reasoningContent())
			.as("有来源标记但当前供应方未知 ⇒ fail-closed").isNull();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static ReasoningAwareOpenAiChatModel modelWithCurrentProvider(OpenAiApi api, Long providerId) {
		return new ReasoningAwareOpenAiChatModel(api,
				OpenAiChatOptions.builder().model("model-a").build(), mock(ToolCallingManager.class),
				RetryTemplate.defaultInstance(), ObservationRegistry.NOOP, providerId);
	}

	private static ChatCompletionMessage onlyAssistant(ChatCompletionRequest request) {
		return request.messages()
			.stream()
			.filter(message -> message.role() == ChatCompletionMessage.Role.ASSISTANT)
			.findFirst()
			.orElseThrow(() -> new AssertionError("请求体里没有 assistant 消息：" + request.messages()));
	}

	private static AssistantMessage storedAssistantWithRichMetadata(Long providerId, String modelId) {
		return AssistantMessage.builder()
			.content("答案正文")
			.properties(richMetadata(providerId, modelId))
			.build();
	}

	/** 推理 + 未知/嵌套元数据（模拟任意供应商的私有字段）。 */
	private static Map<String, Object> richMetadata(Long providerId, String modelId) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("reasoningContent", REASONING);
		metadata.put("encryptedContent", "gAAA-opaque==");
		metadata.put("signature", "sig-1");
		metadata.put("thinking", Map.of("trace", List.of("t1", "t2")));
		metadata.put("vendorUnknown", List.of(Map.of("k", "v")));
		if (providerId != null) {
			metadata.put(ReasoningOrigin.KEY_PROVIDER_ID, providerId);
		}
		if (modelId != null) {
			metadata.put(ReasoningOrigin.KEY_MODEL_ID, modelId);
		}
		return metadata;
	}

	private static long providerIdOf(Map<String, Object> metadata) {
		Object value = metadata.get(ReasoningOrigin.KEY_PROVIDER_ID);
		return value instanceof Number number ? number.longValue() : -1L;
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
