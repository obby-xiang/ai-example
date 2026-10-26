package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T3-7 用例（A12/A14）：<b>统一终态判定函数</b> + <b>空回复即失败</b>（口径变更）。
 *
 * <h2>A12 的三种形态（都要判 FAILED + {@code EMPTY_REPLY}）</h2>
 * <ol>
 * <li><b>空串</b>：上游只给终帧、正文 0 字符（静默截断的典型产物）；</li>
 * <li><b>纯空白</b>：正文只有空格/换行（去空白后为空）；</li>
 * <li><b>空文本但本轮用过工具</b>：快照历史里已有本轮的 {@code assistant(tool_calls)}+{@code tool} 配对
 *     （即这一轮<b>做过事</b>），最终仍无正文 —— "用过工具"不豁免"没答任何东西"。</li>
 * </ol>
 *
 * <p>另附非空回复回归（原 {@code DONE} 行为不变）与 {@link RunSnapshot#isTerminal(String)} 的参数化断言
 * （RUNNING/SUSPENDED/DONE/FAILED/CANCELLED/REJECTED/null/未知串）。
 *
 * <p>帧序契约沿用 {@link FrameContract}：空回复的终帧是 {@code error}（此前是 {@code done}），
 * 且若本轮开过正文段，必须先 {@code message_end} 再终帧。
 */
class EmptyReplyTerminalTest {

	private static final String RUN_ID = "run-empty-reply";

	private static final String SESSION_ID = "s-empty-reply";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	/** 注册表桩：取用即返回同一个运行态（模型 = 下面那个 ChatModel 桩）。 */
	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel,
			mock(ChatClient.class));

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
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "t3b-empty-reply-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		// 模型配置专项批①：模型侧改成从注册表取用（ProviderRuntime 包住 api/model/client 三件套）
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService,
				this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@org.junit.jupiter.api.AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	// ── ① 空串 → FAILED + EMPTY_REPLY ──

	@Test
	void emptyTextTerminalBecomesFailedWithEmptyReplyCode() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空回复先按 AR-05 仅模型步重试一次（retry 帧），仍空才发 error 终帧")
				.containsExactly("start", "retry", "error");
		assertThat(FrameContract.oneOfType(frames, "retry")).as("重试是一次为限的模型步重试")
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
				.containsEntry("emptyReply", true);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		FrameContract.assertConformant(frames);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).contains(ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(snapshot.getFinalText()).isEqualTo("");
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
	}

	// ── ② 纯空白 → FAILED + EMPTY_REPLY（且已开的正文段先收尾） ──

	@Test
	void whitespaceOnlyTextAlsoBecomesFailed() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(
				Flux.just(chunk(" "), chunk("\n"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空白分片照发 delta，但终帧是 error")
				.containsExactly("start", "message_start", "delta", "delta", "message_end", "error");
		assertThat(FrameContract.oneOfType(frames, "message_end")).as("轮终态前先关正文段（与 S5c-5 同契约）")
				.containsEntry("chars", 2L);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		FrameContract.assertConformant(frames);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getFinalText()).isEqualTo(" \n");
	}

	// ── ③ 空文本但本轮用过工具 → 同样 FAILED + EMPTY_REPLY ──

	@Test
	void emptyTextAfterAToolRoundAlsoBecomesFailed() {
		// 造"本轮已用过工具"的事实：快照历史里已有 assistant(tool_calls) + tool 配对
		RunSnapshot seeded = this.store.get(RUN_ID);
		AssistantMessage assistantWithToolCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "list_config_defs", "{}")))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "list_config_defs", "OK")))
			.build();
		seeded.setMessageJson(this.codec.serializeAll(
				List.of(new UserMessage("你好"), assistantWithToolCall, toolResponse)));
		this.store.save(seeded);
		int before = seeded.getMessageJson().size();

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("用过工具不豁免空回复").isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getMessageJson()).as("空正文不写成 assistant 消息（不污染下一轮记忆）")
				.hasSize(before);
		assertThat(snapshot.getMessageJson().get(before - 1)).contains("list_config_defs");
	}

	// ── ④ 非空回复回归：原 DONE 行为不变 ──

	@Test
	void nonEmptyTextStillEndsWithDone() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你好"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("cancelled", false);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("你好");
	}

	// ── ⑥ 批次2（AR-05）：EMPTY_REPLY 只在"零产出 + 零副作用"时重试一次 ──

	/**
	 * 空回复 ⇒ <b>仅模型步</b>再试一次：{@code retry} 帧（additive {@code emptyReply=true}）后
	 * 第二次拿到正文 ⇒ 正常 {@code done}。不追加用户消息、不重执行工具（本用例零工具）。
	 */
	@Test
	void emptyReplyRetriesTheModelStepExactlyOnce() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")),
				Flux.just(chunk("第二次答出来了"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空回复先发 retry，再走正常成功帧序")
				.containsExactly("start", "retry", "message_start", "delta", "message_end", "done");
		assertThat(FrameContract.oneOfType(frames, "retry"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
				.containsEntry("emptyReply", true);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("第二次答出来了");
	}

	/**
	 * <b>只有思考、正文为空 ⇒ 不重试</b>（L-91 同族）：{@code emittedAny()=true} 时重试会把已发过的
	 * 思考段再吐一遍，且 {@code retry} 帧会落在思考 delta 之后（帧序契约禁止）。
	 */
	@Test
	void reasoningOnlyEmptyReplyIsNotRetried() {
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(reasoning("先想一下"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("已出思考 ⇒ 直接空回复失败，不重试")
				.doesNotContain("retry")
				.contains("error");
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	// ── ⑤ 终态判定函数（参数化，A13/A14） ──

	@ParameterizedTest(name = "isTerminal({0}) == {1}")
	@CsvSource({
			"RUNNING, false",
			"SUSPENDED, false",
			"DONE, true",
			"FAILED, true",
			"CANCELLED, true",
			"REJECTED, true",
			"done, false",
			"UNKNOWN_STATUS, false"
	})
	void isTerminalMatchesTheUnifiedRule(String status, boolean expected) {
		assertThat(RunSnapshot.isTerminal(status)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " ", "RUNNING " })
	void isTerminalIsStrictAboutNullAndUnknownStrings(String status) {
		assertThat(RunSnapshot.isTerminal(status)).as("null/空串/未知串都不是终态").isFalse();
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private FrameWire runTurn() {
		this.store.seedArchive(RUN_ID,
				FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		return wire;
	}

	/**
	 * <b>客户端最终持有的完整帧流</b> = 归档帧 ∪ 实时流帧，按 seq 归并去重。
	 *
	 * <p>
	 * B1（2026-10-20）起 delta 也入档，归档本身就是客户端完整流的超集；这里仍按 seq
	 * 去重合并（归档优先），于是"归档与实时两条通道给的是同一份帧"这一点也被顺带锁住。
	 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		Map<Long, Map<String, Object>> bySeq = new LinkedHashMap<>();
		for (Map<String, Object> frame : FrameWire.normalize(this.store.events(RUN_ID))) {
			bySeq.putIfAbsent(((Number) frame.get("seq")).longValue(), frame);
		}
		for (Map<String, Object> frame : wire.frames()) {
			if (frame.get("seq") instanceof Number number) {
				bySeq.putIfAbsent(number.longValue(), frame);
			}
		}
		List<Map<String, Object>> all = new ArrayList<>(bySeq.values());
		all.sort(Comparator.comparingLong(frame -> ((Number) frame.get("seq")).longValue()));
		return all;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	/**
	 * 思考分片（L-91）：Spring AI 1.1.7 用 {@code AssistantMessage} metadata 键
	 * {@code reasoningContent} 携带思考文本（与产品侧 {@code REASONING_METADATA_KEY} 同一拼写）。
	 */
	private static ChatResponse reasoning(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("")
			.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, text))
			.build())));
	}
}
