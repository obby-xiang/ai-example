package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextTrigger;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
import com.example.configmgr.ai.context.RollingSummarizer;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>首轮压缩阶段信号</b>（2026-10-29）：只有<b>真实 L2 模型摘要</b>才公告 {@code compacting}。
 *
 * <p>被钉住的口径（与最终用户口径逐字对应）：
 * <ol>
 * <li>普通轮（空历史 / 未达阈值 / 摘要器不可用 / 没有可摘要的旧轮）<b>完全不出现</b>
 *     {@code heartbeat{phase=compacting}} —— 前端因此没有"正在压缩"阶段条；</li>
 * <li>真实 L2 摘要的前后相位是 {@code streaming → compacting → streaming}：
 *     首轮保活从一开始就是 {@code streaming}（普通请求不改成任何准备/压缩相位），
 *     摘要真正开始前切 {@code compacting}，摘要结束立刻切回 {@code streaming}；</li>
 * <li>多分块摘要只公告一次 {@code compacting}（同相位不重复公告）。</li>
 * </ol>
 *
 * <p>证红方式（旧实现）：首轮在 {@code compactBeforeRequest} 之前无条件公告
 * {@code heartbeat{phase=compacting}} ⇒ 1、2、3、4 条用例同时红（每个普通轮都带压缩阶段条），
 * 第 5、6 条的相位序列也红（只有一条 compacting，没有前后 streaming）。
 */
class CompactionStageSignalTest {

	private static final String RUN_ID = "run-stage-signal";

	private static final String SESSION_ID = "s-stage-signal";

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

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel, this.chatClient);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final InMemoryContextStateStore contextStore = new InMemoryContextStateStore();

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "stage-signal-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		stubFluentChain();
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	/** 空历史首轮（没有可压缩内容）：不得出现压缩阶段。 */
	@Test
	void emptyHistoryFirstTurnNeverAnnouncesCompacting() {
		history(List.of());
		useCompactor((system, user, runtime) -> "摘要正文");
		seedUsage(70000, 25000, "L1");

		FrameWire wire = runTurn();

		assertThat(stagePhases(wire)).as("普通请求不出现压缩阶段条")
			.doesNotContain(TurnKeepalive.PHASE_COMPACTING);
		assertThat(FrameContract.types(wire.frames())).containsExactly("start", "heartbeat", "message_start",
				"delta", "message_end", "done");
	}

	/** 未达阈值：不得出现压缩阶段。 */
	@Test
	void underThresholdFirstTurnNeverAnnouncesCompacting() {
		history(List.of(new UserMessage("你好"), new AssistantMessage("你好呀")));
		useCompactor((system, user, runtime) -> "摘要正文");
		seedUsage(1000, 0, null);

		FrameWire wire = runTurn();

		assertThat(stagePhases(wire)).doesNotContain(TurnKeepalive.PHASE_COMPACTING);
	}

	/** 摘要器不可用（L2 判到但压不了）：不得公告压缩阶段 —— 没有模型摘要发生。 */
	@Test
	void summarizerUnavailableFirstTurnNeverAnnouncesCompacting() {
		history(longTurns());
		useCompactor(null);
		seedUsage(70000, 25000, "L1");

		FrameWire wire = runTurn();

		assertThat(stagePhases(wire)).doesNotContain(TurnKeepalive.PHASE_COMPACTING);
	}

	/** 没有可摘要的旧轮（全在 keep 窗口里）：不得公告压缩阶段。 */
	@Test
	void nothingToSummarizeFirstTurnNeverAnnouncesCompacting() {
		history(List.of(new UserMessage("你好"), new AssistantMessage("你好呀")));
		AtomicInteger summaries = new AtomicInteger();
		useCompactor((system, user, runtime) -> {
			summaries.incrementAndGet();
			return "摘要正文";
		});
		seedUsage(70000, 25000, "L1");

		FrameWire wire = runTurn();

		assertThat(stagePhases(wire)).doesNotContain(TurnKeepalive.PHASE_COMPACTING);
		assertThat(summaries.get()).as("没有可摘要的旧轮 ⇒ 一次摘要调用都不该发生").isZero();
	}

	/** 真实 L2：相位序列 streaming → compacting → streaming，且摘要真的发生。 */
	@Test
	void realL2FirstTurnAnnouncesStreamingThenCompactingThenStreaming() {
		history(longTurns());
		AtomicInteger summaries = new AtomicInteger();
		useCompactor((system, user, runtime) -> {
			summaries.incrementAndGet();
			return "摘要正文";
		});
		seedUsage(70000, 25000, "L1");

		FrameWire wire = runTurn();

		assertThat(summaries.get()).as("模型摘要真的发生").isPositive();
		assertThat(stagePhases(wire)).containsExactly(TurnKeepalive.PHASE_STREAMING,
				TurnKeepalive.PHASE_COMPACTING, TurnKeepalive.PHASE_STREAMING);
		assertThat(FrameContract.oneOfType(wire.frames(), "context")).containsEntry("level", "L2");
	}

	/** 多分块摘要：compacting 只公告一次（同相位不重复公告）。 */
	@Test
	void multiChunkSummaryAnnouncesCompactingOnce() {
		this.properties.getContext().setChunkTokens(200);
		history(longTurns());
		AtomicInteger summaries = new AtomicInteger();
		useCompactor((system, user, runtime) -> {
			summaries.incrementAndGet();
			return "摘要正文";
		});
		seedUsage(70000, 25000, "L1");

		FrameWire wire = runTurn();

		assertThat(summaries.get()).as("分块摘要确实发了多次请求").isGreaterThan(1);
		List<String> phases = stagePhases(wire);
		assertThat(phases.stream().filter(TurnKeepalive.PHASE_COMPACTING::equals).count()).isEqualTo(1L);
		assertThat(phases).containsExactly(TurnKeepalive.PHASE_STREAMING, TurnKeepalive.PHASE_COMPACTING,
				TurnKeepalive.PHASE_STREAMING);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void history(List<Message> messages) {
		when(this.chatMemory.get(SESSION_ID)).thenReturn(new ArrayList<>(messages));
	}

	private void useCompactor(RollingSummarizer.Summarizer summarizer) {
		ContextCompactor compactor = summarizer == null
				? new ContextCompactor(this.properties, new ContextLimitResolver(this.properties), this.contextStore)
				: new ContextCompactor(this.properties, new ContextLimitResolver(this.properties), this.contextStore,
						summarizer);
		ReflectionTestUtils.setField(this.service, "contextCompactor", compactor);
	}

	private void seedUsage(int usedTokens, int completionTokens, String level) {
		long now = System.currentTimeMillis();
		ContextState state = ContextState.EMPTY.withUsage(usedTokens, completionTokens, 1, false, now, "test-model",
				0, 0, 0);
		if (level != null) {
			state = state.withCompaction(ContextTrigger.Level.fromWire(level), 0, 0, "seed", now);
		}
		this.contextStore.write(SESSION_ID, state);
	}

	private FrameWire runTurn() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);
		return wire;
	}

	private static List<String> stagePhases(FrameWire wire) {
		return FrameContract.ofType(wire.frames(), FrameContract.HEARTBEAT).stream()
			.map(frame -> String.valueOf(frame.get("phase")))
			.toList();
	}

	/** 9 轮历史：够 {@code keep-recent-turns=6} 切出 3 轮可摘要的旧轮。 */
	private static List<Message> longTurns() {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			messages.add(new UserMessage("第 " + i + " 轮问题 " + "问".repeat(40)));
			messages.add(AssistantMessage.builder().content("第 " + i + " 轮回答 " + "答".repeat(40)).build());
		}
		return messages;
	}

	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenReturn(Flux.just(chunk("好了"), terminal()));
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal() {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").build(),
						ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().model("test-model").build());
	}

}
