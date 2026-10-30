package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批③ 首轮的三个契约点（用真实的 {@link ResilientChatService#chat} 跑一轮，断言帧与请求体）：
 * <ol>
 * <li><b>paramsLocked</b>：{@code allowUserParams=false} ⇒ 参数不生效，且 {@code done.extra.paramsLocked=true}
 *     （载体取 {@code done.extra} 的 additive 键 —— 帧型不变，故本用例同时是"没有第 17 种帧"的旁证）；</li>
 * <li><b>能力位降级</b>：模型 {@code supportsToolCalling=false} ⇒ 本轮<b>不把工具交给模型</b>、不发
 *     {@code tool_start}（判据取 {@code ChatClient} 的工具面，不禁止内部的披露面读取）；</li>
 * <li><b>done.model 口径</b>：上游回显优先、回落本轮解析值。</li>
 * </ol>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>去掉 {@code putParamsLocked} ⇒ 第 1 条红；</li>
 * <li>把 {@code tools} 无条件交给模型（不判能力位）⇒ 第 2 条的 {@code toolCallbacks} 断言红；</li>
 * <li>把 {@code doneModel} 换成 {@code accumulator.model()} ⇒ 第 3 条"无回显时回落解析值"红。</li>
 * </ul>
 */
class ChatModelContractTest {

	private static final String RUN_ID = "run-model-contract";

	private static final String SESSION_ID = "s-model-contract";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "model-contract-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(any(), any())).thenReturn(() -> {
		});
		when(this.chatMemory.get(SESSION_ID)).thenReturn(List.of());
		when(this.contextBuilder.buildContextMessage(any())).thenReturn("workspace");
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	// ── ① paramsLocked ──────────────────────────────────────────────────────

	@Test
	void paramsLockedIsReportedInDoneExtra() {
		runtimeFor("deepseek-flash");
		stubFluentChain();
		when(this.providerRegistry.allowUserParams()).thenReturn(false);
		// 锁住时参数必须被<b>丢弃</b>：派生运行态的 overrides 应是 NONE
		when(this.providerRegistry.forRequest(any(), eq("deepseek-flash"), eq(AiProviderRegistry.Overrides.NONE)))
			.thenReturn(runtime("deepseek-flash"));
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("好了"),
				terminal()));

		List<Map<String, Object>> frames = runChat(new AiChatRequest(SESSION_ID, "你好", null, "deepseek-flash",
				new AiChatRequest.Params(0.1d, 0.2d, 512)));

		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("paramsLocked", true);
	}

	@Test
	void allowedParamsAreAppliedAndNotReportedAsLocked() {
		runtimeFor("deepseek-flash");
		stubFluentChain();
		when(this.providerRegistry.allowUserParams()).thenReturn(true);
		when(this.providerRegistry.forRequest(any(), eq("deepseek-flash"),
				eq(new AiProviderRegistry.Overrides(0.1d, 0.2d, 512))))
			.thenReturn(runtime("deepseek-flash"));
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("好了"),
				terminal()));

		List<Map<String, Object>> frames = runChat(new AiChatRequest(SESSION_ID, "你好", null, "deepseek-flash",
				new AiChatRequest.Params(0.1d, 0.2d, 512)));

		assertThat(FrameContract.oneOfType(frames, "done")).doesNotContainKey("paramsLocked");
	}

	// ── ② 能力位降级 ────────────────────────────────────────────────────────

	@Test
	void modelWithoutToolCallingGetsNoToolsAndNoToolFrames() {
		stubFluentChain();
		when(this.providerRegistry.target("plain-model"))
			.thenReturn(Optional.of(new AiConfigService.ChatTarget(
					new AiConfigService.ResolvedProvider(7L, "deepseek", "http://placeholder",
							"/chat/completions", "plain-model", "sk-test-0123456789", "fp", false),
					"plain-model", false)));
		when(this.providerRegistry.forRequest(any(), eq("plain-model"), any()))
			.thenReturn(runtime("plain-model"));
		when(this.providerRegistry.allowUserParams()).thenReturn(true);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("纯问答"),
				terminal()));

		List<Map<String, Object>> frames = runChat(new AiChatRequest(SESSION_ID, "你好", null, "plain-model", null));

		// 断言的是"交给模型的工具面"而不是内部读取：导出收尾的披露探测（disclosedStartExport）
		// 会读一次 registry 的披露面，那不是把工具交给模型。
		verify(this.requestSpec, never()).toolCallbacks(org.mockito.ArgumentMatchers
			.<List<org.springframework.ai.tool.ToolCallback>>argThat(list -> list != null && !list.isEmpty()));
		assertThat(FrameContract.ofType(frames, "tool_start")).as("不支持工具调用的模型不该有任何工具帧").isEmpty();
		assertThat(FrameContract.oneOfType(frames, "done")).as("降级不是错误：正常 done 收尾").isNotNull();
	}

	// ── ③ done.model ────────────────────────────────────────────────────────

	@Test
	void upstreamEchoWinsOverParsedModel() {
		runtimeFor("deepseek-pro");
		stubFluentChain();
		when(this.providerRegistry.allowUserParams()).thenReturn(true);
		when(this.providerRegistry.forRequest(any(), eq("deepseek-pro"), any())).thenReturn(runtime("deepseek-pro"));
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(
				chunkWithModel("好了", "deepseek-pro-0715"), terminalWithModel("deepseek-pro-0715")));

		List<Map<String, Object>> frames = runChat(new AiChatRequest(SESSION_ID, "你好", null, "deepseek-pro", null));

		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("model", "deepseek-pro-0715");
	}

	@Test
	void parsedModelIsTheFallbackWhenUpstreamOmitsIt() {
		runtimeFor("deepseek-pro");
		stubFluentChain();
		when(this.providerRegistry.allowUserParams()).thenReturn(true);
		when(this.providerRegistry.forRequest(any(), eq("deepseek-pro"), any())).thenReturn(runtime("deepseek-pro"));
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("好了"),
				terminal()));

		List<Map<String, Object>> frames = runChat(new AiChatRequest(SESSION_ID, "你好", null, "deepseek-pro", null));

		assertThat(FrameContract.oneOfType(frames, "done")).as("上游不回显时必须以本轮解析值兜底").containsEntry("model",
				"deepseek-pro");
	}

	// ── 装配与执行 ──────────────────────────────────────────────────────────

	/** {@code chat()} 走 ChatClient 的流式链：逐层返回同一个 spec，末端接回 {@code streamSpec}。 */
	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
	}

	private void runtimeFor(String modelId) {
		when(this.providerRegistry.target(modelId)).thenReturn(Optional.of(new AiConfigService.ChatTarget(
				new AiConfigService.ResolvedProvider(7L, "deepseek", "http://placeholder", "/chat/completions",
						modelId, "sk-test-0123456789", "fp", false),
				modelId, true)));
	}

	private ProviderRuntime runtime(String modelId) {
		return new ProviderRuntime(7L, "deepseek", modelId, "http://placeholder", "/chat/completions", "7|fp|u|p",
				null, null, this.chatModel, this.chatClient);
	}

	private List<Map<String, Object>> runChat(AiChatRequest request) {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.chat(RUN_ID, request, out);
		FrameContract.assertConformant(wire.frames());
		return wire.frames();
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse chunkWithModel(String text, String model) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())),
				ChatResponseMetadata.builder().model(model).build());
	}

	private static ChatResponse terminal() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())));
	}

	private static ChatResponse terminalWithModel(String model) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().model(model).build());
	}

}
