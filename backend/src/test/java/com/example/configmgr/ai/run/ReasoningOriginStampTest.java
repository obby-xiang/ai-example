package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.ReasoningOrigin;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>跨供应方推理元数据隔离（来源标记面）</b>：模型生成的 assistant 消息必须带中性 origin
 * （{@code providerId}/{@code modelId}），且该标记<b>只加不改</b>。
 *
 * <h2>为什么要标在消息上而不是按会话记一张表</h2>
 * 历史会经过 Redis 快照、会话记忆、L1/L2 压缩、跨实例续跑多次拷贝；只有"随消息一起走"的
 * 不透明 metadata 才能保证"这条思考是谁产出的"不丢。请求层（{@code ReasoningAwareOpenAiChatModel}）
 * 只按它决定是否回放 {@code reasoningContent} —— 存储一个字都不删（隔离只作用于模型输入）。
 *
 * <h2>证红方式</h2>
 * 去掉 {@code finish}/{@code cancelTerminal} 的 {@code ReasoningOrigin.stamp} ⇒ 前两例红；
 * 让 {@code stamp} 覆盖已有 origin ⇒ "标记只加不改"例红。
 */
class ReasoningOriginStampTest {

	private static final String RUN_ID = "run-origin-stamp";

	private static final String SESSION_ID = "s-origin-stamp";

	private static final Long PROVIDER_ID = 7L;

	private static final String MODEL_ID = "model-a";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(PROVIDER_ID, "prov-a", MODEL_ID,
			"http://localhost", "/chat/completions", "7|runtime", null, null, this.chatModel,
			mock(ChatClient.class));

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final RecordingRepository repository = new RecordingRepository();

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
			AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(PROVIDER_ID, "prov-a",
					"http://localhost", "/chat/completions", requested, "key", "fp", true);
			return Optional.of(new AiConfigService.ChatTarget(resolved, requested, true));
		});
		// I3：带 providerId 的快照走精确绑定
		when(this.providerRegistry.target(any(), any())).thenAnswer(invocation -> {
			Long providerId = invocation.getArgument(0);
			String requested = invocation.getArgument(1);
			AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(providerId, "prov-a",
					"http://localhost", "/chat/completions", requested, "key", "fp", true);
			return Optional.of(new AiConfigService.ChatTarget(resolved, requested, true));
		});
		when(this.providerRegistry.forFrozenRequest(any(), any(), any())).thenReturn(this.runtime);
		when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(this.runtime);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "origin-stamp-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.repository, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
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

	// ── 落定/取消两条生成路径都带 origin ───────────────────────────────────────

	@Test
	void finishStampsTheNeutralOriginOfTheFrozenSnapshot() {
		seedRun(PROVIDER_ID, MODEL_ID);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
				reasoningChunk("先想一步。"), chunk("答案"), terminal("stop")));

		runTurn();

		AssistantMessage written = lastAssistant(this.repository.findByConversationId(SESSION_ID));
		assertThat(written.getMetadata()).as("来源标记随消息进记忆（跨实例/续跑都能读到）")
			.containsEntry(ReasoningOrigin.KEY_PROVIDER_ID, PROVIDER_ID)
			.containsEntry(ReasoningOrigin.KEY_MODEL_ID, MODEL_ID)
			.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "先想一步。");
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(providerIdOf(lastAssistant(this.codec.deserializeAll(snapshot.getMessageJson())).getMetadata()))
			.as("快照与记忆同源").isEqualTo(PROVIDER_ID);
	}

	@Test
	void cancelStampsOriginOnTheStoppedAssistant() {
		seedRun(PROVIDER_ID, MODEL_ID, List.of(new UserMessage("问题")));
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
				Flux.just(chunk("半截正文")),
				Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "本轮已取消"))));

		runTurn();

		AssistantMessage stopped = lastAssistant(this.repository.findByConversationId(SESSION_ID));
		assertThat(stopped.getMetadata()).as("取消半截也要标明'是谁产生的'")
			.containsEntry(ResilientChatService.STOPPED_KEY, true);
		assertThat(providerIdOf(stopped.getMetadata())).isEqualTo(PROVIDER_ID);
		assertThat(stopped.getMetadata()).containsEntry(ReasoningOrigin.KEY_MODEL_ID, MODEL_ID);
	}

	// ── 标记语义（只加不改 / 不伪造） ─────────────────────────────────────────

	@Test
	void stampPreservesEverythingAndNeverRewritesAKnownOrigin() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("reasoningContent", "想法");
		metadata.put("encryptedContent", "opaque==");
		metadata.put("signature", "sig");
		metadata.put("vendorUnknown", List.of(Map.of("k", "v")));
		AssistantMessage original = AssistantMessage.builder().content("正文").properties(metadata).build();

		AssistantMessage stamped = ReasoningOrigin.stamp(original, 7L, "model-a");

		assertThat(stamped.getMetadata()).as("原有键一个不少（不解析、不截断）")
			.containsAllEntriesOf(metadata)
			.containsEntry(ReasoningOrigin.KEY_PROVIDER_ID, 7L)
			.containsEntry(ReasoningOrigin.KEY_MODEL_ID, "model-a");
		assertThat(stamped.getText()).isEqualTo("正文");

		AssistantMessage restamped = ReasoningOrigin.stamp(stamped, 8L, "model-b");
		assertThat(restamped).as("已有来源的消息不再改写（provenance 不可被后来者覆盖）").isSameAs(stamped);
		assertThat(restamped.getMetadata()).containsEntry(ReasoningOrigin.KEY_PROVIDER_ID, 7L);
	}

	@Test
	void stampWithNoIdentityDoesNotFabricateAnOrigin() {
		AssistantMessage original = AssistantMessage.builder().content("正文")
			.properties(Map.of("reasoningContent", "想法")).build();

		AssistantMessage stamped = ReasoningOrigin.stamp(original, null, null);

		assertThat(stamped).as("不知道来源就保持未知（不伪造'当前供应方'）").isSameAs(original);
		assertThat(stamped.getMetadata()).doesNotContainKey(ReasoningOrigin.KEY_PROVIDER_ID);
	}

	/** 旧快照（无 providerId/modelId）：落定消息也不该凭空多出来源标记。 */
	@Test
	void legacySnapshotWithoutIdentityStaysUnstamped() {
		seedRun(null, null);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("答案"), terminal("stop")));

		runTurn();

		AssistantMessage written = lastAssistant(this.repository.findByConversationId(SESSION_ID));
		assertThat(written.getMetadata()).doesNotContainKey(ReasoningOrigin.KEY_PROVIDER_ID)
			.doesNotContainKey(ReasoningOrigin.KEY_MODEL_ID);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedRun(Long providerId, String modelId) {
		seedRun(providerId, modelId, List.of());
	}

	private void seedRun(Long providerId, String modelId, List<Message> history) {
		RunSnapshot snapshot = this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), history,
				this.codec, providerId, modelId, new RunSnapshot.FrozenParams(0.1d, null, null, false));
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
	}

	/** metadata 里的 providerId 经 JSON 往返会变成 Integer —— 断言统一按数值比。 */
	private static long providerIdOf(Map<String, Object> metadata) {
		Object value = metadata.get(ReasoningOrigin.KEY_PROVIDER_ID);
		return value instanceof Number number ? number.longValue() : -1L;
	}

	private void runTurn() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("第一轮问题")), out);
	}

	private static AssistantMessage lastAssistant(List<Message> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof AssistantMessage assistant) {
				return assistant;
			}
		}
		throw new AssertionError("历史里没有 assistant 消息：" + messages);
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse reasoningChunk(String reasoning) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
			.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, reasoning))
			.build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	/** 只记录写入的仓储（看"消息最后长什么样"，不关心存储实现）。 */
	private static final class RecordingRepository implements ChatMemoryRepository {

		private final Map<String, List<Message>> store = new LinkedHashMap<>();

		@Override
		public List<String> findConversationIds() {
			return new ArrayList<>(this.store.keySet());
		}

		@Override
		public List<Message> findByConversationId(String conversationId) {
			return List.copyOf(this.store.getOrDefault(conversationId, List.of()));
		}

		@Override
		public void saveAll(String conversationId, List<Message> messages) {
			if (messages == null || messages.isEmpty()) {
				this.store.remove(conversationId);
				return;
			}
			this.store.put(conversationId, new ArrayList<>(messages));
		}

		@Override
		public void deleteByConversationId(String conversationId) {
			this.store.remove(conversationId);
		}

	}

}
