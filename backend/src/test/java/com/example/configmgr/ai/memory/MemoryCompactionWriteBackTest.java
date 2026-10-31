package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.example.configmgr.ai.config.AiModelConfig;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextTrigger;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
import com.example.configmgr.ai.context.RollingSummarizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>压缩 / 记忆窗口的写回必须落到 Redis 窗口</b>（KQC C-08 / C-13 回归）。
 *
 * <h2>被钉住的回归</h2>
 * 压缩与记忆窗口是<b>跨 run 的整窗改写</b>：L1 把旧工具结果换成占位符、L2 用摘要折叠旧轮、L3 丢最旧
 * 的轮次 —— 被改写的大多属于<b>别的 run</b>的块。若这类写回也走"按 run upsert"，改写会被跳过
 * （别的块以"当前窗口"为准），于是台账说压了、窗口却没动：下一轮模型仍看到原文（C-08 找不到占位符），
 * 压缩档位与占用也对不上（C-13）。
 *
 * <p>本组直接读<b>Redis 窗口</b>（{@code findByConversationId}）断言 L1/L2/L3 的产物真的可读回，
 * 且当前轮自己的消息仍然写得进去（轮末 converge 不受影响）。
 */
class MemoryCompactionWriteBackTest {

	private static final String SESSION_ID = "s-compaction-writeback";

	private static final String RUN_ID = "run-compaction-writeback";

	private static final long TENANT = 7L;

	private static final long USER = 701L;

	private static final String TOOL_PAYLOAD_MARKER = "原始工具结果正文";

	private static final String BIG_TOOL_PAYLOAD = TOOL_PAYLOAD_MARKER + "长".repeat(4_000);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			Duration.ofHours(24));

	private final AiProperties properties = new AiProperties();

	private final ChatMemory chatMemory = new AiModelConfig().chatMemory(this.repository, this.properties);

	/**
	 * 快照替身补上"创建时落历史"这一步（真实 {@code RunStore#create} 会写 {@code messageJson}，
	 * 进程内替身只顾了身份/状态/上下文）。不补的话本轮 converge 拿不到历史，
	 * "轮末写回仍按 run 合并"这一面就测不到。
	 */
	private final InMemoryRunStore store = new InMemoryRunStore() {
		@Override
		public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
				MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen) {
			RunSnapshot snapshot = super.create(runId, sessionId, context, history, codec, providerId, modelId, frozen);
			snapshot.setMessageJson(codec.serializeAll(history));
			save(snapshot);
			return snapshot;
		}
	};

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final InMemoryContextStateStore contextStore = new InMemoryContextStateStore();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ProviderRuntime runtime = new ProviderRuntime(7L, "deepseek", "test-model",
			"http://placeholder", "/chat/completions", "7|fp|u|p", null, null, this.chatModel, this.chatClient);

	private ScheduledExecutorService watchdogExecutor;

	private RequestContextUtils.SafeCloseable identity;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.identity = RequestContextUtils.useContext(TENANT, USER);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "compaction-writeback-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.repository, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.contextBuilder.buildContextMessage(any())).thenReturn("workspace");
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(this.runtime);
		when(this.providerRegistry.allowUserParams()).thenReturn(false);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		stubFluentChain();
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
		this.identity.close();
	}

	/** L1：旧 run 的工具结果被换成占位符 —— 必须能从 Redis 窗口读回（原文不再出现）。 */
	@Test
	void l1ClearedToolResultsAreReadableBackFromTheRedisWindow() {
		installCompactor(null);
		this.properties.getContext().setKeepToolPairs(0);
		seedContext("none", 85_000);
		seedWindow(l1Turns());

		runChat();

		assertThat(this.contextStore.read(SESSION_ID).level()).as("前置：压缩真的跑到了 L1").isEqualTo("L1");
		assertThat(toolPayload("c-1")).as("L1 占位符必须真的写进窗口（不是只写台账）")
			.startsWith("[工具结果已清除：");
		assertThat(windowText()).as("原文不得再出现在窗口里").doesNotContain(TOOL_PAYLOAD_MARKER);
	}

	/** L2：摘要必须落进窗口，被折叠的旧轮不再出现；最近若干轮保留。 */
	@Test
	void l2SummaryIsReadableBackFromTheRedisWindow() {
		installCompactor((system, user, promptRuntime) -> "摘要正文");
		this.properties.getContext().setHysteresisPercent(100);
		seedContext("L1", 95_000);
		seedWindow(longTurns());

		runChat();

		assertThat(this.contextStore.read(SESSION_ID).level()).as("前置：压缩真的跑到了 L2").isEqualTo("L2");
		List<String> window = windowText();
		assertThat(window).as("L2 摘要必须真的写进窗口")
			.anyMatch(text -> text.startsWith(RollingSummarizer.SUMMARY_PREFIX));
		assertThat(window).as("被摘要折叠的旧轮不得还在窗口里").doesNotContain("第1轮问题");
		assertThat(window).as("最近的轮次必须保留").contains("第9轮问题");
	}

	/** L3：被硬裁掉的旧轮必须从窗口消失（会话真的变短，而不是只改了台账）。 */
	@Test
	void l3TrimmedTurnsAreRemovedFromTheRedisWindow() {
		installCompactor(null);
		this.properties.getContext().setHysteresisPercent(100);
		seedContext("L2", 109_500);
		seedWindow(longTurns());

		runChat();

		assertThat(this.contextStore.read(SESSION_ID).level()).as("前置：压缩真的跑到了 L3").isEqualTo("L3");
		List<String> window = windowText();
		assertThat(window).as("L3 丢掉的最旧轮次不得还在窗口里").doesNotContain("第1轮问题");
		assertThat(window).contains("第9轮问题");
		assertThat(window).as("本轮自己的消息仍写回（轮末 converge 不受影响）").contains("你好", "好了");
	}

	/**
	 * <b>压缩写回与并发写者的交错</b>：压缩算完之后（快照已读）另一条 run 落了新消息 ⇒ 本次整窗写回
	 * 必须放弃：新消息不得被压缩前的旧内容覆盖，且放弃要 WARN + W1（否则台账说压了、窗口没动）。
	 */
	@Test
	void compactionWriteIsAbandonedAndCountedWhenAnotherWriterLands() {
		installCompactor(null);
		this.properties.getContext().setKeepToolPairs(0);
		seedContext("none", 85_000);
		seedWindow(l1Turns());
		long w1Before = RedisDegradation.count(RedisDegradation.W1_MEMORY_WRITE);
		RedisChatMemoryRepository otherInstance = new RedisChatMemoryRepository(this.fake.redis, this.codec,
				Duration.ofHours(24));
		java.util.concurrent.atomic.AtomicBoolean injected = new java.util.concurrent.atomic.AtomicBoolean();
		this.fake.beforeScript = () -> {
			if (!injected.compareAndSet(false, true)) {
				return;
			}
			// 压缩正在落盘前的这一拍，另一条 run 写下自己的成功轮（推进窗口版本）
			otherInstance.upsertRun(SESSION_ID, new MemoryTurn("run-concurrent", 99_000L),
					List.of(new UserMessage("并发写者的问题"), AssistantMessage.builder().content("并发写者的回答").build()),
					40, true);
		};

		runChat();

		assertThat(windowText()).as("并发写者的新消息不得被压缩的旧内容覆盖").contains("并发写者的问题", "并发写者的回答");
		assertThat(toolPayload("c-1")).as("压缩写回被放弃 ⇒ 旧内容保持原样（不是半压）")
			.doesNotStartWith("[工具结果已清除：");
		assertThat(RedisDegradation.count(RedisDegradation.W1_MEMORY_WRITE))
			.as("放弃也是一次没落地的写回：必须计入 W1，不能静默").isEqualTo(w1Before + 1);
	}

	// ───────────────────────── harness ─────────────────────────

	private void installCompactor(RollingSummarizer.Summarizer summarizer) {
		ReflectionTestUtils.setField(this.service, "contextCompactor", new ContextCompactor(this.properties,
				new ContextLimitResolver(this.properties), this.contextStore, summarizer));
	}

	/** 会话的压缩基线（真值口径：压缩触发/滞回都看它）。 */
	private void seedContext(String level, int usedTokens) {
		ContextState state = ContextState.EMPTY
			.withCompaction(ContextTrigger.Level.fromWire(level), 0, 0, "seeded", System.currentTimeMillis())
			.withUsage(usedTokens, 0, 1, false, System.currentTimeMillis(), "test-model", 0, 0, 0);
		this.contextStore.write(SESSION_ID, state);
	}

	/** 每一轮一个 run（"每个用户轮次 = 新 run"），按起点依次写进窗口。 */
	private void seedWindow(List<List<Message>> turns) {
		long startedAt = 1_000L;
		for (int i = 0; i < turns.size(); i++) {
			this.repository.upsertRun(SESSION_ID, new MemoryTurn("run-old-" + (i + 1), startedAt), turns.get(i), 40,
					true);
			startedAt += 1_000L;
		}
	}

	/** L1 素材：一条带大工具结果的旧轮 + 一条普通旧轮。 */
	private static List<List<Message>> l1Turns() {
		List<List<Message>> turns = new ArrayList<>();
		turns.add(List.of(new UserMessage("第一轮问题"),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "list_items", "{}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "list_items", BIG_TOOL_PAYLOAD)))
					.build(),
				AssistantMessage.builder().content("第一轮回答").build()));
		turns.add(List.of(new UserMessage("第二轮问题"), AssistantMessage.builder().content("第二轮回答").build()));
		return turns;
	}

	/** L2/L3 素材：9 轮普通对话（> keep-recent-turns=6，因此有可折叠/可裁的旧轮）。 */
	private static List<List<Message>> longTurns() {
		List<List<Message>> turns = new ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			turns.add(List.of(new UserMessage("第" + i + "轮问题"),
					AssistantMessage.builder().content("第" + i + "轮回答").build()));
		}
		return turns;
	}

	private void runChat() {
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), this.registry.of(RUN_ID));
	}

	private List<String> windowText() {
		return this.repository.findByConversationId(SESSION_ID).stream().map(Message::getText).toList();
	}

	private String toolPayload(String toolCallId) {
		for (Message message : this.repository.findByConversationId(SESSION_ID)) {
			if (message instanceof ToolResponseMessage toolResponse) {
				for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
					if (toolCallId.equals(response.id())) {
						return response.responseData();
					}
				}
			}
		}
		return "";
	}

	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(chunk("好了"), terminal()));
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal() {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").build(),
						org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())),
				org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model("test-model").build());
	}

}
