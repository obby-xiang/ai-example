package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 帧面与运行面的接线（上下文统计与压缩专项批①）：{@code done.context} 子对象、压缩时的
 * {@code context} 帧（帧类型集 15 → 16 的唯一新增）、以及"帧序不变量不被新帧打破"。
 *
 * <p>
 * 这组用例刻意走<b>真实</b>的 {@link ResilientChatService#driveResumed}（续跑路径：一条 Prompt 就够，
 * 不必桩 ChatClient 链）—— 断言对象是"帧里到底有什么"，而不是"某个方法被调了几次"。
 */
class ContextFrameWiringTest {

	private static final String RUN_ID = "run-ctx";

	private static final String SESSION_ID = "s-ctx";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel, null);

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
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ctx-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		ReflectionTestUtils.setField(this.service, "contextCompactor", this.compactor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	/** 一条带 usage 的终帧响应（必须带 finishReason：完成帧校验的唯一判据）。 */
	private static ChatResponse response(String text, Integer prompt, Integer completion) {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content(text).build(),
						ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().model("test-model")
					.usage(new DefaultUsage(prompt, completion, prompt + completion))
					.build());
	}

	private SseChatEmitter emitter() {
		return new SseChatEmitter(RUN_ID, this.store, this.mapper);
	}

	private List<Map<String, Object>> framesOf(com.example.configmgr.ai.conformance.FrameWire wire) {
		return wire.frames();
	}

	/** 正常一轮：{@code done.context} 带真值 + 上限 + 百分比，且帧序不变量照旧成立。 */
	@Test
	void doneFrameCarriesContextSubObject() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("你好", 42000, 900)));
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);

		List<Map<String, Object>> frames = framesOf(wire);
		FrameContract.assertConformant(frames);
		Map<String, Object> done = FrameContract.oneOfType(frames, "done");
		assertThat(done.get("context")).isInstanceOf(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> context = (Map<String, Object>) done.get("context");
		assertThat(context).containsKey("usedTokens").containsEntry("est", false).containsEntry("level", "none");
		assertThat(((Number) context.get("limitTokens")).intValue()).isEqualTo(128000);
		assertThat(((Number) context.get("usedTokens")).intValue()).isEqualTo(42000);
		assertThat(((Number) context.get("percent")).doubleValue()).isEqualTo(32.8d);
		// 计量真值同步回写 ai:ctx:（端点与 done 必须同源）
		assertThat(this.contextStore.read(SESSION_ID).usedTokens()).isEqualTo(42000);
	}

	/** 上游全程无 usage ⇒ {@code est=true}（前端 ≈ 的唯一来源），且 done.usage 也标 estimate。 */
	@Test
	void missingUsageIsMarkedAsEstimate() {
		// 有终帧（finishReason）但完全没有 usage —— 这才是"上游不给 usage"的真实形态
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("好").build(),
						ChatGenerationMetadata.builder().finishReason("stop").build())))));
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);

		Map<String, Object> done = FrameContract.oneOfType(framesOf(wire), "done");
		@SuppressWarnings("unchecked")
		Map<String, Object> context = (Map<String, Object>) done.get("context");
		@SuppressWarnings("unchecked")
		Map<String, Object> usage = (Map<String, Object>) done.get("usage");
		assertThat(context).containsEntry("est", true);
		assertThat(usage).containsEntry("est", true).containsEntry("source", "estimate");
	}

	/**
	 * 上下文帧（帧类型集 15 → 16 的唯一新增）不破任何帧序不变量。
	 *
	 * <p>
	 * 断言对象是<b>整条帧流</b>（不是"方法被调用过"）：{@code context} 是非终帧、非工具帧，
	 * 插在 {@code start} 之后、正文之前时，6 条不变量必须全部照旧成立。kill：把
	 * {@code context} 排在终帧之后（或让 {@code emit} 跳过取号）⇒ 本条红。
	 */
	@Test
	void contextFrameIsLegalAnywhereBeforeTheTerminal() {
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);

		out.start(SESSION_ID);
		out.context(Map.of("level", "L1", "before", 90000, "after", 60000, "turns", 0, "lostTokens", 0, "reason",
				"l1_tool_result_clearing", "est", false));
		out.textStart();
		out.delta("好的");
		out.textEnd(2);
		out.done(Map.of("promptTokens", 60000, "completionTokens", 10, "totalTokens", 60010, "est", false, "source",
				"stream", "frames", 1, "dirty", 0), "test-model", Map.of("cancelled", false),
				Map.of("usedTokens", 60000, "limitTokens", 128000, "percent", 46.9, "level", "L1", "est", false,
						"circuit", "closed"));

		List<Map<String, Object>> frames = framesOf(wire);
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.types(frames)).containsExactly("start", "context", "message_start", "delta",
				"message_end", "done");
		Map<String, Object> contextFrame = FrameContract.oneOfType(frames, "context");
		assertThat(contextFrame).containsEntry("level", "L1").containsEntry("reason", "l1_tool_result_clearing")
			.containsKeys("before", "after", "turns", "lostTokens");
		// 帧类型集 15 → 16：新帧走同一条取号/落档通道（seq 连续、进归档）
		assertThat(contextFrame.get("seq")).isEqualTo(2L);
		assertThat(this.store.events(RUN_ID)).extracting(frame -> frame.get("type")).contains("context");
	}

	/**
	 * I-1：续跑轮的 {@code done.context.level} 必须是<b>真实级别</b>。
	 *
	 * <p>
	 * 续跑的压缩发生在 {@code ResumeService} 里（早于 {@code driveResumed}），故"本轮压没压过"
	 * 不能靠 {@code ai:ctx:} 的时刻去猜（压完再交棒，时刻必然早于本轮的 {@code turnStartedAtMs}）——
	 * 级别由压缩方显式交接（{@code noteCompaction}），驱动侧读后即弃（一次性）。
	 * kill：删掉交接或用时刻比较 ⇒ 本条报 {@code level=none}。
	 */
	@Test
	void resumeCompactionLevelReachesDoneContext() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("好", 70000, 10)));
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		this.service.noteCompaction(RUN_ID, "L1");
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("继续")), out);

		Map<String, Object> done = FrameContract.oneOfType(framesOf(wire), "done");
		@SuppressWarnings("unchecked")
		Map<String, Object> context = (Map<String, Object>) done.get("context");
		assertThat(context).containsEntry("level", "L1");
	}

	/** I-1 续：交接是一次性的 —— 同一轮的第二次驱动不得再报上一轮的级别。 */
	@Test
	void compactionLevelHandoffIsOneShot() {
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(response("好", 70000, 10)), Flux.just(response("再", 71000, 10)));

		SseChatEmitter first = emitter();
		FrameWire firstWire = FrameWire.attachTo(first);
		first.start(SESSION_ID);
		this.service.noteCompaction(RUN_ID, "L1");
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("继续")), first);

		SseChatEmitter second = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		FrameWire secondWire = FrameWire.attachTo(second);
		second.start(SESSION_ID);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("再问")), second);

		@SuppressWarnings("unchecked")
		Map<String, Object> firstContext = (Map<String, Object>) FrameContract.oneOfType(framesOf(firstWire), "done")
			.get("context");
		@SuppressWarnings("unchecked")
		Map<String, Object> secondContext = (Map<String, Object>) FrameContract.oneOfType(framesOf(secondWire), "done")
			.get("context");
		assertThat(firstContext).containsEntry("level", "L1");
		assertThat(secondContext).as("交接读过即弃，不得残留").containsEntry("level", "none");
	}

	/** 未注入压缩设施（既有单测直接 new 的场景）⇒ 不带 context 键：未装配 ≠ 计量为 0。 */
	@Test
	void unassembledServiceOmitsContextKey() {
		ResilientChatService bare = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("你好", 42000, 900)));
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		bare.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);

		FrameContract.assertConformant(framesOf(wire));
		assertThat(FrameContract.oneOfType(framesOf(wire), "done")).doesNotContainKey("context");
	}

	/**
	 * 上游越窗（上下文专项批③；设计稿 §3.5 L3）⇒ 终帧错误码 {@code CONTEXT_OVERFLOW}，且<b>不重试</b>。
	 *
	 * <p>
	 * 为什么必须单独一条：越窗是"请求太大"，重发同一份请求只会再超一次。若被归成
	 * {@code UPSTREAM_STREAM_ERROR}（断流类），重试次数会白烧在一份注定失败的请求上，
	 * 用户看到的还是含糊的"上游流错误"。
	 */
	@Test
	void contextOverflowIsMappedToItsOwnCodeWithoutRetry() {
		byte[] body = "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens\"}}"
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(
				org.springframework.web.reactive.function.client.WebClientResponseException.create(400, "Bad Request",
						org.springframework.http.HttpHeaders.EMPTY, body, null)));
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);

		List<Map<String, Object>> frames = framesOf(wire);
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", "CONTEXT_OVERFLOW");
		org.mockito.Mockito.verify(this.chatModel, org.mockito.Mockito.times(1)).stream(any(Prompt.class));
	}

}
