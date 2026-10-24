package com.example.configmgr.ai.conformance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停止后台账一致性（E2E 用例全集第 53 件 · P-14，兼 S-06 的 {@code turn_end} 面）。
 *
 * <p>蓝本判据：{@code /runs/{runId}}、{@code /pending/{runId}}、{@code turn_end.status} 三者同向；
 * 半截正文以 {@code stopped} 标记入记忆（否则"停止后刷新整条消失"，Q13）。
 *
 * <p>为什么必须新增：既有用例各自只锁一段 —— {@code CancelDecisionFramesTest} 锁补发帧、
 * {@code ChatTurnFrameSequenceConformanceTest} 锁 {@code done{cancelled}} 帧、{@code AiTurnLogTraceTest}
 * 只锁 {@code turn_end} 的 done/failed 两态，**没有一条同时断言"快照 + 条目 + 日志"同向**。
 * 三条分开都绿、合起来错位的情况（快照 CANCELLED 而条目仍 PENDING）谁也发现不了。
 *
 * <p>kill：把 {@code cancelTerminal} 里的 {@code emitCancelOutcomes} 挪到 {@code snapshot.save} 之前、
 * 或删掉 {@code STOPPED_KEY}，本类对应用例变红。
 */
class CancelLedgerConformanceTest {

	private static final String RUN_ID = "run-cancel-ledger";

	private static final String SESSION_ID = "s-cancel-ledger";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel,
			this.chatClient);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cancel-ledger-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService,
				this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
		this.store.save(snapshot);
		// 两条未决条目（一条确认门 + 一条前端工具）：停止后都必须落 CANCELLED
		this.store.putPending(RUN_ID, pending("c-confirm", PendingToolCall.KIND_CONFIRM, "start_export"));
		this.store.putPending(RUN_ID, pending("c-frontend", PendingToolCall.KIND_FRONTEND, "generative_form"));

		this.logs = new ListAppender<>();
		this.logs.start();
		((Logger) LoggerFactory.getLogger(AiTrace.class)).addAppender(this.logs);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
		((Logger) LoggerFactory.getLogger(AiTrace.class)).detachAppender(this.logs);
	}

	/**
	 * 判据（P-14 主形态）：取消收尾后 —— 快照 CANCELLED、两条未决条目 CANCELLED 且
	 * {@code reason=RUN_CANCELLED}、{@code turn_end} 行的 status/code 与之一致。
	 */
	@Test
	void cancelLedgerAgreesAcrossSnapshotPendingAndTurnEndLog() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
				Flux.just(chunk("半截")),
				Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "本轮对话已被取消"))));

		FrameWire wire = runTurn();

		// ① 快照
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(snapshot.isCancelled()).isTrue();
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
		assertThat(snapshot.getAssistantContent()).isNull();

		// ② 条目：全 CANCELLED + 统一 reason + 未执行
		List<PendingToolCall> pendings = this.store.pendings(RUN_ID);
		assertThat(pendings).hasSize(2);
		assertThat(pendings).allSatisfy(entry -> {
			assertThat(entry.getStatus()).isEqualTo(PendingToolCall.CANCELLED);
			assertThat(entry.getReason()).isEqualTo("RUN_CANCELLED");
			assertThat(entry.isExecuted()).isFalse();
		});

		// ③ 日志：turn_end.status=cancelled，code 是取消码（不是 none）
		String turnEnd = this.logs.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(message -> message.contains("turn_end"))
				.reduce((first, second) -> second)
				.orElseThrow(() -> new AssertionError("本轮没有 turn_end 行"));
		assertThat(turnEnd).contains("status=cancelled")
				.contains("code=" + StreamViolationException.CANCELLED)
				.contains("runId=" + RUN_ID);

		// ④ 帧：唯一终帧是 done{cancelled}，且补发的两条裁决帧都在
		List<Map<String, Object>> frames = FrameWire.normalize(this.store.events(RUN_ID));
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("cancelled", true)
				.containsEntry("partialChars", 2L);
		assertThat(FrameContract.ofType(frames, "error")).isEmpty();
		assertThat(FrameContract.types(wire.frames())).contains("done");
	}

	/**
	 * 判据（Q13 后半）：停止落库的半截正文**带 {@code stopped} 标记**写进记忆 —— 否则刷新后
	 * 前端看到的是"完整答案"，或者整条消失。
	 */
	@Test
	void cancelledPartialReplyIsPersistedWithTheStoppedMarker() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
				Flux.just(chunk("区域费率是按区域")),
				Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "本轮对话已被取消"))));

		runTurn();

		// 半截正文经 convergeMemory 写进 ChatMemory（/history 的唯一读源）；快照不存正文（口径：
		// assistantContent 只属于"挂起中的那一轮"，停止后必须清空 —— 见上一条用例）。
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> captured =
				ArgumentCaptor.forClass(List.class);
		verify(this.chatMemory).add(eq(SESSION_ID), captured.capture());
		List<org.springframework.ai.chat.messages.Message> written = captured.getValue();
		assertThat(written).as("半截正文必须进记忆（否则刷新后整条消失）").anySatisfy(message -> {
			assertThat(message).isInstanceOf(AssistantMessage.class);
			assertThat(message.getText()).isEqualTo("区域费率是按区域");
			assertThat(message.getMetadata()).as("前端据此渲染「已停止」标识").containsEntry("stopped", true);
		});
	}

	/**
	 * 判据（P-09 的后端一半）：取消幂等 —— 第二次取消只回事实，不改台账、不重复执行。
	 */
	@Test
	void cancellingTwiceKeepsASingleTerminalFact() {
		int first = this.store.cancelPendings(RUN_ID);
		int second = this.store.cancelPendings(RUN_ID);

		assertThat(first).isEqualTo(2);
		assertThat(second).as("幂等：已 CANCELLED 的条目不再被改写").isZero();
		assertThat(this.store.pendings(RUN_ID)).allMatch(entry -> PendingToolCall.CANCELLED.equals(entry.getStatus()));
	}

	/**
	 * N-1：UNKNOWN 基础设施状态不得被记成用户取消。终态必须是 FAILED + AI_REDIS_UNAVAILABLE，
	 * 条目保持 PENDING（不落 CANCELLED），且不得出现 done{cancelled} / cancelTerminal 的台账。
	 */
	@Test
	void stateUnavailableIsFailureNotUserCancellation() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(
				new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE, "取消状态未知")));

		FrameWire wire = runTurn();

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.isCancelled()).as("不是用户取消").isFalse();
		assertThat(snapshot.getError()).contains(ErrorCodes.AI_REDIS_UNAVAILABLE)
			.doesNotContain(ErrorCodes.AI_RUN_CANCELLED);

		assertThat(this.store.pendings(RUN_ID)).allSatisfy(entry ->
			assertThat(entry.getStatus()).as("取消收尾不得被触发").isEqualTo(PendingToolCall.PENDING));

		List<Map<String, Object>> frames = FrameWire.normalize(wire.frames());
		assertThat(FrameContract.ofType(frames, "done")).as("不得发 done{cancelled}").isEmpty();
		Map<String, Object> error = FrameContract.oneOfType(frames, "error");
		assertThat(error).containsEntry("code", ErrorCodes.AI_REDIS_UNAVAILABLE);
		assertThat(FrameContract.types(frames)).doesNotContain("done");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private FrameWire runTurn() {
		this.store.seedArchive(RUN_ID,
				FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		return wire;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static PendingToolCall pending(String toolCallId, String kind, String name) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setKind(kind);
		pending.setName(name);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}
}
