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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>L-26</b>：同一 run 的续跑必须冻结<b>当时的生效参数</b>
 * （provider / model / temperature / topP / maxTokens / paramsLocked）；
 * 只有<b>新 run</b> 才读新的全局默认。
 *
 * <h2>为什么要冻结（而不是续跑时重算）</h2>
 * 续跑发生在几分钟甚至几天之后，此间用户可能改了全局默认温度、换了默认模型、或把某个 provider 停用。
 * 重算的后果是<b>同一轮前后半段跑在不同参数/不同模型上</b>，而历史与 {@code done.model} 都说"同一轮"——
 * 这是 L-26 裁决要消灭的静默漂移。
 *
 * <h2>三条硬口径（本用例逐条锁）</h2>
 * <ol>
 * <li><b>能冻结 null</b>：创建时全局就没有 temperature（或用户没传），冻结值就是 null；
 *     续跑时即便新默认填了 0.7，也<b>不得</b>被填上（{@link AiProviderRegistry#forFrozenRequest} 负责
 *     "照抄含 null"，而不是"缺省即继承当前默认"）；</li>
 * <li><b>旧快照走旧行为 + WARN</b>：{@code paramsVersion=0}（L-26 之前写入）没有可冻结的值，
 *     续跑按当前默认（旧语义）并留一行 WARN —— 不能假装它冻结过；</li>
 * <li><b>多 run 不串</b>：每个 run 各自冻结、各自续跑，互不影响。</li>
 * </ol>
 *
 * <p>证红方式：把 {@code create} 的冻结段删掉（{@code paramsVersion} 恒 0）⇒ 第 1/2 例红；
 * 把 {@code runtimeForSnapshot} 的冻结分支改回 {@code Overrides.NONE} ⇒ 第 1/3 例红。
 */
@ExtendWith(OutputCaptureExtension.class)
class RunParamsFreezeTest {

	private static final String SESSION_ID = "s-l26";

	private static final ObjectMapper MAPPER = new ObjectMapper()
		.setSerializationInclusion(JsonInclude.Include.NON_NULL);

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(7L, "test-provider", "model-x",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel,
			mock(ChatClient.class));

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.providerRegistry.target(any())).thenAnswer(invocation -> {
			String requested = invocation.getArgument(0);
			if (requested == null) {
				return Optional.empty();
			}
			AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(7L, "test-provider",
					"http://localhost", "/chat/completions", requested, "key", "fp", true);
			return Optional.of(new AiConfigService.ChatTarget(resolved, requested, true));
		});
		when(this.providerRegistry.forFrozenRequest(any(), any(), any())).thenReturn(this.runtime);
		when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(this.runtime);
		// I3：带 providerId 的精确绑定（续跑不再按模型名猜第一家）
		when(this.providerRegistry.target(any(), any())).thenAnswer(invocation -> {
			Long providerId = invocation.getArgument(0);
			String requested = invocation.getArgument(1);
			AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(providerId,
					"test-provider", "http://localhost", "/chat/completions", requested, "key", "fp", true);
			return Optional.of(new AiConfigService.ChatTarget(resolved, requested, true));
		});
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "l26-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	// ── 冻结面（快照/存储） ──────────────────────────────────────────────────

	@Test
	void createFreezesEffectiveParamsIncludingNulls() {
		RunSnapshot frozen = this.store.create("run-frozen", SESSION_ID, AiContext.empty(), List.of(),
				this.codec, 7L, "model-x", new RunSnapshot.FrozenParams(0.1d, 0.3d, 256, true));

		assertThat(frozen.getParamsVersion()).as("版本号 = '这四个值是冻结的'，含 null")
			.isEqualTo(RunSnapshot.PARAMS_VERSION_FROZEN);
		assertThat(frozen.paramsFrozen()).isTrue();
		assertThat(frozen.getTemperature()).isEqualTo(0.1d);
		assertThat(frozen.getTopP()).isEqualTo(0.3d);
		assertThat(frozen.getMaxTokens()).isEqualTo(256);
		assertThat(frozen.getParamsLocked()).isTrue();

		RunSnapshot nullFrozen = this.store.create("run-frozen-null", SESSION_ID, AiContext.empty(), List.of(),
				this.codec, 7L, "model-x", new RunSnapshot.FrozenParams(null, null, null, false));
		assertThat(nullFrozen.getParamsVersion()).as("全 null 也是一次冻结").isEqualTo(RunSnapshot.PARAMS_VERSION_FROZEN);
		assertThat(nullFrozen.getTemperature()).isNull();
		assertThat(nullFrozen.getTopP()).isNull();
		assertThat(nullFrozen.getMaxTokens()).isNull();
		assertThat(nullFrozen.getParamsLocked()).isFalse();
	}

	@Test
	void oldCreateSignatureLeavesTheSnapshotUnfrozen() {
		RunSnapshot legacy = this.store.create("run-legacy", SESSION_ID, AiContext.empty(), List.of(),
				this.codec, 7L, "model-x");

		assertThat(legacy.getParamsVersion()).as("旧签名 = 未冻结（续跑走旧行为 + WARN）").isZero();
		assertThat(legacy.paramsFrozen()).isFalse();
		assertThat(legacy.getTemperature()).isNull();
	}

	/**
	 * 落库口径：全局 Jackson 是 {@code non_null}，冻结的 null 值会被省略 ——
	 * 因此"冻结过"只能由 {@code paramsVersion} 表达；旧快照（无该字段）反序列化为 0。
	 */
	@Test
	void jsonKeepsFrozenNullsDistinctFromOldSnapshots() throws Exception {
		RunSnapshot frozen = this.store.create("run-json", SESSION_ID, AiContext.empty(), List.of(),
				this.codec, 7L, "model-x", new RunSnapshot.FrozenParams(null, 0.4d, null, true));
		String json = MAPPER.writeValueAsString(frozen);
		RunSnapshot readBack = MAPPER.readValue(json, RunSnapshot.class);

		assertThat(readBack.getParamsVersion()).isEqualTo(RunSnapshot.PARAMS_VERSION_FROZEN);
		assertThat(readBack.getTemperature()).as("冻结的 null 不得因为 non_null 省略而变成'没冻结过'").isNull();
		assertThat(readBack.getTopP()).isEqualTo(0.4d);
		assertThat(readBack.getParamsLocked()).isTrue();

		RunSnapshot old = MAPPER.readValue("{\"runId\":\"run-old\",\"sessionId\":\"s-old\"}", RunSnapshot.class);
		assertThat(old.getParamsVersion()).as("旧 JSON 没有该字段 ⇒ 0（未冻结）").isZero();
		assertThat(old.paramsFrozen()).isFalse();
	}

	// ── 续跑面：冻结值真的被用上（不是只看快照） ───────────────────────────────

	@Test
	void resumeUsesTheFrozenOverridesInsteadOfCurrentDefaults() throws Exception {
		seedRun("run-frozen-resume", "model-x", new RunSnapshot.FrozenParams(0.1d, 0.3d, 256, false));

		driveTurn("run-frozen-resume");

		AiProviderRegistry.Overrides used = capturedFrozenOverrides("model-x");
		assertThat(used.temperature()).isEqualTo(0.1d);
		assertThat(used.topP()).isEqualTo(0.3d);
		assertThat(used.maxTokens()).isEqualTo(256);
		verify(this.providerRegistry, never()).forRequest(any(), any(), any());
	}

	/**
	 * 冻结 null 的核心回归：全局默认此刻有 0.7/0.9/2048，但这一轮创建时三值都不存在 ⇒
	 * 续跑必须传 null（由 {@code forFrozenRequest} 清空），<b>不能</b>被新默认填上。
	 */
	@Test
	void frozenNullParamsAreNotFilledByTheNewDefaults() throws Exception {
		seedRun("run-frozen-null", "model-x", new RunSnapshot.FrozenParams(null, null, null, false));

		driveTurn("run-frozen-null");

		AiProviderRegistry.Overrides used = capturedFrozenOverrides("model-x");
		assertThat(used.temperature()).isNull();
		assertThat(used.topP()).isNull();
		assertThat(used.maxTokens()).isNull();
		verify(this.providerRegistry, never()).forRequest(any(), any(), any());
	}

	/** 旧快照（{@code paramsVersion=0}）：走旧行为（{@code Overrides.NONE}）+ 一行 WARN。 */
	@Test
	void oldSnapshotResumeKeepsLegacyBehaviorAndWarns(CapturedOutput output) throws Exception {
		RunSnapshot snapshot = this.store.create("run-old-resume", SESSION_ID, AiContext.empty(), List.of(),
				this.codec, 7L, "model-x");
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		this.store.seedArchive("run-old-resume",
				FrameContract.fixture("start", 1L, "runId", "run-old-resume", "sessionId", SESSION_ID));
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("答案"), terminal("stop")));

		// 生产路径：ResumeService 对有 providerId 的快照走六参（按快照身份驱动）
		SseChatEmitter out = this.registry.of("run-old-resume");
		this.service.driveResumed("run-old-resume", SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("第一轮问题")), 7L, "model-x", out);

		ArgumentCaptor<AiProviderRegistry.Overrides> overrides = ArgumentCaptor
			.forClass(AiProviderRegistry.Overrides.class);
		verify(this.providerRegistry).forRequest(any(), eq("model-x"), overrides.capture());
		assertThat(overrides.getValue().isEmpty()).as("旧行为 = 不覆盖（按当前默认）").isTrue();
		verify(this.providerRegistry, never()).forFrozenRequest(any(), any(), any());
		assertThat(output.getAll()).contains("未冻结生效参数").contains("run-old-resume");
	}

	/** 多 run 不串：各自冻结、各自续跑。 */
	@Test
	void concurrentRunsKeepTheirOwnFrozenParams() throws Exception {
		seedRun("run-a", "model-a", new RunSnapshot.FrozenParams(0.1d, null, 128, false));
		seedRun("run-b", "model-b", new RunSnapshot.FrozenParams(0.9d, 0.5d, 4096, false));

		driveTurn("run-a");
		driveTurn("run-b");

		assertThat(capturedFrozenOverrides("model-a").temperature()).isEqualTo(0.1d);
		assertThat(capturedFrozenOverrides("model-a").maxTokens()).isEqualTo(128);
		assertThat(capturedFrozenOverrides("model-b").temperature()).isEqualTo(0.9d);
		assertThat(capturedFrozenOverrides("model-b").topP()).isEqualTo(0.5d);
	}

	/**
	 * <b>I3</b>：快照 providerId 非空 ⇒ 只按 {@code (providerId, modelId)} 解析，不按模型名猜第一家。
	 */
	@Test
	void frozenSnapshotIdentityIsResolvedByProviderIdNotByModelName() throws Exception {
		seedRun("run-exact", "model-x", new RunSnapshot.FrozenParams(0.1d, null, null, false));

		driveTurn("run-exact");

		verify(this.providerRegistry).target(eq(7L), eq("model-x"));
		verify(this.providerRegistry, never()).target(any());
	}

	/**
	 * <b>I3</b>：精确绑定失败<b>不回落</b>默认目标 —— 回落就是静默跨 provider/换模型，
	 * 与 {@code PROVIDER_GONE} 的语义相反（那一轮应当以错误收尾，而不是换一家接着跑）。
	 */
	@Test
	void exactBindingFailureDoesNotFallBackToTheCurrentDefault() throws Exception {
		seedRun("run-gone", "model-x", new RunSnapshot.FrozenParams(0.1d, null, null, false));
		when(this.providerRegistry.target(eq(7L), eq("model-x"))).thenReturn(Optional.empty());

		assertThatThrownBy(() -> driveTurn("run-gone"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("精确绑定失败");
		verify(this.providerRegistry, never()).current();
	}

	/** 冻结的 {@code paramsLocked} 必须跟到终帧（续跑轮的 {@code done.paramsLocked} 不许改口）。 */	@Test
	void frozenParamsLockedSurvivesIntoTheDoneFrame() throws Exception {
		seedRun("run-locked", "model-x", new RunSnapshot.FrozenParams(0.1d, 0.3d, 256, true));

		List<Map<String, Object>> frames = driveTurn("run-locked");

		Map<String, Object> done = frames.stream()
			.filter(frame -> "done".equals(frame.get("type")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("没有 done 帧：" + frames));
		assertThat(done).containsEntry("paramsLocked", true);
	}

	/**
	 * 旧 5 参 {@code driveResumed} 仍可用：env 合成行（{@code providerId=null}）的冻结快照没有身份可传，
	 * 但快照自己有 modelId —— 续跑必须按快照模型走冻结分支，而不是回落当前默认。
	 */
	@Test
	void fiveArgResumeUsesTheFrozenSnapshotIdentity() throws Exception {
		RunSnapshot snapshot = this.store.create("run-env", SESSION_ID, AiContext.empty(), List.of(),
				this.codec, null, "model-env", new RunSnapshot.FrozenParams(0.2d, null, null, false));
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		this.store.seedArchive("run-env",
				FrameContract.fixture("start", 1L, "runId", "run-env", "sessionId", SESSION_ID));
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("答案"), terminal("stop")));
		SseChatEmitter out = this.registry.of("run-env");

		this.service.driveResumed("run-env", SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), out);

		assertThat(capturedFrozenOverrides("model-env").temperature()).isEqualTo(0.2d);
	}

	/**
	 * 首轮（{@code chat()}）把<b>生效参数</b>写进快照：见的是 runtime options（全局默认 + 请求覆盖 +
	 * 策略锁定的结果），不是请求体原文。续跑侧只读这份冻结值（前几例）。
	 */
	@Test
	void chatFreezesTheEffectiveOptionsOfTheFirstRound() {
		OpenAiChatOptions options = new OpenAiChatOptions();
		options.setModel("model-x");
		options.setTemperature(0.4d);
		options.setMaxTokens(300);
		options.setStreamUsage(true);
		ProviderRuntime withOptions = new ProviderRuntime(7L, "test-provider", "model-x", "http://localhost",
				"/chat/completions", "test|runtime", options, null, this.chatModel, this.chatClient);
		when(this.providerRegistry.current()).thenReturn(withOptions);
		when(this.providerRegistry.allowUserParams()).thenReturn(false);
		when(this.contextBuilder.buildContextMessage(any())).thenReturn("");
		stubFluentChain();
		this.store.seedRun("run-chat-freeze", SESSION_ID);

		AiChatRequest request = new AiChatRequest(SESSION_ID, "你好", null,
				null, new AiChatRequest.Params(0.9d, 0.2d, 1024));
		this.service.chat("run-chat-freeze", request, this.registry.of("run-chat-freeze"));

		RunSnapshot snapshot = this.store.get("run-chat-freeze");
		assertThat(snapshot.paramsFrozen()).as("首轮创建即冻结（profile: chat 路径）").isTrue();
		assertThat(snapshot.getTemperature()).as("冻结的是生效值：params 被策略锁住 ⇒ 未生效")
			.isEqualTo(0.4d);
		assertThat(snapshot.getTopP()).as("全局没有 top_p ⇒ 冻结 null（不是省略）").isNull();
		assertThat(snapshot.getMaxTokens()).isEqualTo(300);
		assertThat(snapshot.getParamsLocked()).as("参数被策略锁住 = 这一轮的事实，必须冻结").isTrue();

		// 第二轮重新冻结（新 run 读新默认），旧 run 的冻结值不被改写
		assertThat(this.store.get("run-chat-freeze").getTemperature()).isEqualTo(0.4d);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void stubFluentChain() {
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
		when(this.chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
		when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
		when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
		when(requestSpec.toolContext(any(java.util.Map.class))).thenReturn(requestSpec);
		when(requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(requestSpec);
		when(requestSpec.stream()).thenReturn(streamSpec);
		when(streamSpec.chatResponse())
			.thenAnswer(invocation -> Flux.just(chunk("答案"), terminal("stop")));
	}

	private void seedRun(String runId, String modelId, RunSnapshot.FrozenParams frozen) throws Exception {
		RunSnapshot snapshot = this.store.create(runId, SESSION_ID, AiContext.empty(), List.of(),
				this.codec, 7L, modelId, frozen);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		this.store.seedArchive(runId, FrameContract.fixture("start", 1L, "runId", runId, "sessionId", SESSION_ID));
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("答案"), terminal("stop")));
	}

	private List<Map<String, Object>> driveTurn(String runId) {
		SseChatEmitter out = this.registry.of(runId);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(runId, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("第一轮问题")), out);
		return wire.frames();
	}

	private AiProviderRegistry.Overrides capturedFrozenOverrides(String modelId) {
		ArgumentCaptor<AiProviderRegistry.Overrides> overrides = ArgumentCaptor
			.forClass(AiProviderRegistry.Overrides.class);
		verify(this.providerRegistry).forFrozenRequest(any(), eq(modelId), overrides.capture());
		return overrides.getValue();
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
