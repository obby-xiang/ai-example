package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>AR-06（批次3）首轮首帧前移</b>：{@code start} 前移到请求前压缩之前，并定义"已 start、快照未建"
 * 这段窗口的出帧语义。
 *
 * <h2>被钉住的四件事（一条链，任一处退回去都红）</h2>
 * <ol>
 * <li><b>start 仍是首帧</b> —— 前移改变的是"它相对压缩的位置"，不是它的地位；</li>
 * <li><b>阶段帧</b>（{@code heartbeat{phase=streaming}}）是第二帧，由<b>请求线程</b>在进入
 *     同步压缩<b>之前</b>发出 —— 普通轮（本例没有 L2 摘要）<b>不</b>出现 compacting；
 *     "正在压缩"只属于真实 L2 模型摘要（见 {@link CompactionStageSignalTest}）；</li>
 * <li><b>同步压缩调用自身一帧不发</b>：快照建立那一刻的帧面恰好是 [start, 阶段帧]（压缩体若偷发
 *     任何帧，这里就会多出来）；</li>
 * <li><b>"已 start、快照未建"窗口真实存在</b>：建立快照时归档里已经有 start 与阶段帧 —— 这正是
 *     重挂侧"准备中"（202，见 {@code AiController#events}）要覆盖的那段，且阶段帧是<b>心跳</b>：
 *     不占业务 {@code seq}、不入归档（帧类型集仍是 16）。</li>
 * </ol>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 {@code out.start(...)} 挪回 {@code store.create(...)} 之后 ⇒ 第 2、3、4 条同时红
 *     （压缩前帧面是空的）；</li>
 * <li>把阶段帧改到压缩调用之后发 ⇒ 第 2 条红（第二帧不是它）；</li>
 * <li>让压缩体自己发帧（例如把上下文帧挪进 {@code ContextCompactor}）⇒ 第 3 条红；</li>
 * <li>把阶段帧改成业务帧（带 seq、入归档）⇒ 第 4 条红（归档里出现 heartbeat / 业务 seq 出现洞）。</li>
 * </ul>
 */
class FirstTurnCompactionStageTest {

	private static final String RUN_ID = "run-first-turn-stage";

	private static final String SESSION_ID = "s-first-turn-stage";

	/** 记录"建立快照那一刻的帧面"与"那一刻是否已有快照"的替身（压缩体发不发帧因此可判）。 */
	private static final class RecordingStore extends InMemoryRunStore {

		private volatile IntSupplier framesAtCreate = () -> -1;

		private final AtomicInteger framesObservedAtCreate = new AtomicInteger(-1);

		private volatile Boolean hadSnapshotAtCreate = null;

		@Override
		public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
				MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen) {
			this.framesObservedAtCreate.set(this.framesAtCreate.getAsInt());
			this.hadSnapshotAtCreate = get(runId) != null;
			return super.create(runId, sessionId, context, history, codec, providerId, modelId, frozen);
		}

	}

	private final RecordingStore store = new RecordingStore();

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

	private final ContextCompactor compactor = new ContextCompactor(this.properties,
			new ContextLimitResolver(this.properties), this.contextStore);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "first-turn-stage-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		ReflectionTestUtils.setField(this.service, "contextCompactor", this.compactor);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		// 首轮的记忆窗口非空（空历史会让 compactBeforeRequest 提前返回，压缩阶段就不存在了）
		when(this.chatMemory.get(SESSION_ID))
			.thenReturn(new ArrayList<>(List.of(new UserMessage("你好"), new AssistantMessage("你好呀"))));
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	/**
	 * 首轮：{@code start} 是首帧、阶段帧紧随其后且在同步压缩之前、压缩自身不发帧。
	 *
	 * <p>断言对象是<b>客户端收到的那条帧流</b>与<b>建立快照那一刻的帧面</b>，不是"某个方法被调用过"。
	 *
	 * <p>2026-10-29 起阶段帧的相位是 {@code streaming}（普通请求不出现压缩/准备阶段条）；
	 * 本例没有真实 L2 摘要，因此 {@code compacting} 一条都不该出现。
	 */
	@Test
	void firstTurnEmitsStartThenTheStreamingStageBeforeTheSyncCompaction() {
		stubFluentChain();
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.store.framesAtCreate = wire::size;

		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames))
			.as("首帧仍是 start（前移的是位置，不是地位）；阶段帧紧随其后")
			.containsExactly("start", "heartbeat", "message_start", "delta", "message_end", "done");

		Map<String, Object> stage = frames.get(1);
		assertThat(stage).containsEntry("phase", TurnKeepalive.PHASE_STREAMING)
			.containsEntry("elapsedMs", 0L)
			.containsEntry("attempt", 0L)
			.as("阶段帧走心跳通道：没有业务 seq")
			.doesNotContainKey("seq");
		assertThat(((Number) stage.get("heartbeatSeq")).longValue()).as("独立号空间从 1 起").isEqualTo(1L);
		assertThat(FrameContract.types(frames)).as("没有真实 L2 摘要 ⇒ 不出现压缩阶段条")
			.doesNotContain("compacting");

		assertThat(this.store.framesObservedAtCreate.get())
			.as("压缩 + 建快照期间只有 [start, 阶段帧]：同步压缩调用自身一帧不发")
			.isEqualTo(2);
		assertThat(this.store.hadSnapshotAtCreate)
			.as("建快照时还没有快照 ⇒ 这是真实的\"已 start、快照未建\"窗口（重挂侧返回准备中）")
			.isFalse();

		List<Map<String, Object>> business = frames.stream()
			.filter(frame -> !FrameContract.HEARTBEAT.equals(FrameContract.type(frame)))
			.toList();
		assertThat(business).extracting(frame -> ((Number) frame.get("seq")).longValue())
			.as("业务序号自 1 起、无缺口（阶段帧不占号）")
			.containsExactly(1L, 2L, 3L, 4L, 5L);
		assertThat(this.store.events(RUN_ID)).extracting(frame -> frame.get("type"))
			.as("心跳不入业务归档（既有心跳契约不变）")
			.doesNotContain("heartbeat");
		FrameContract.assertConformant(business);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	/**
	 * N4（返修）：<b>压缩期间收到取消</b> ⇒ 不发起模型调用；终态另有持久面（最小 CANCELLED 快照）。
	 *
	 * <p>压缩是首轮最长的零帧窗口（L2 = N 次串行摘要）。取消只有被"压缩后、调用模型前"的复查看见，
	 * 才谈得上零模型调用；若只靠后续的终态保护，上游已经收到请求。快照仍要落一份
	 * （{@link EarlyCancelDuringCompactionSnapshotTest} 钉住其最小内容），否则这一轮的取消终态
	 * 只剩帧归档、没有权威状态面。
	 */
	@Test
	void cancelDuringCompactionSkipsModelCallAndPersistsCancelledSnapshot() {
		stubFluentChain();
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		assertThat(this.store.get(RUN_ID).getStatus()).as("压缩期间取消 ⇒ 最小 CANCELLED 快照")
			.isEqualTo(RunSnapshot.CANCELLED);
		assertThat(this.store.get(RUN_ID).getAssistantContent()).as("模型未调用：无助手正文").isNull();
		verify(this.chatClient, never()).prompt();
		verify(this.chatModel, never()).stream(any(Prompt.class));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).as("压缩后取消：只有 start / 阶段帧 / 取消终帧")
			.doesNotContain("message_start", "delta", "error");
		assertThat(FrameContract.oneOfType(frames, "done")).as("终态与终帧仍归既有取消收尾")
			.containsEntry("cancelled", true);
	}

	/** {@code chat()} 走 ChatClient 的流式链：逐层返回同一个 spec，末端接回 scripted 分片。 */
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
