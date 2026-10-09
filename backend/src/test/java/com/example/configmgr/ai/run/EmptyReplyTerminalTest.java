package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
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

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

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
			Thread thread = new Thread(runnable, "t3b-empty-reply-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(mock(ChatClient.class), this.chatModel, this.chatMemory, this.codec,
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
		assertThat(FrameContract.types(frames)).as("空回复不发 done，改发 error 终帧")
				.containsExactly("start", "error");
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

	/** 客户端视角的完整帧流 = 归档状态帧 ∪ 实时 delta（与前端实际持有的帧集一致）。 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		List<Map<String, Object>> all = new ArrayList<>(FrameWire.normalize(this.store.events(RUN_ID)));
		all.addAll(FrameContract.ofType(wire.frames(), "delta"));
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
}
