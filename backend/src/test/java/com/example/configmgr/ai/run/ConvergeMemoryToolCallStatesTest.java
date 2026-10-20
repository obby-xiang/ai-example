package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
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
 * R-144 B-2 + S0 实证：{@link ResilientChatService#convergeMemory} 的状态 join。
 *
 * <p>状态源替身 = {@link InMemoryRunStore}（T5 只限定 B-1 载荷面不用它，B-2 可用它作状态源）；
 * 驱动 {@code driveResumed} 的终态路径（finish / emptyReply / cancelTerminal）让 convergeMemory
 * 真实运行，从 {@link ChatMemory#add} 捕获写回记忆窗口的消息并断言 {@code toolCallStates} 内容。
 *
 * <p>覆盖：终态直写、T2 升格判据（正/反/台账）、定序 + WARN（CANCELLED / DONE / FAILED 三路），
 * 以及 S0 的三条路径（取消挂起中 / 取消已 settle / 超时）—— 后三例同时是 S0「convergeMemory
 * 是否可见当轮终态」的运行时实证。
 */
class ConvergeMemoryToolCallStatesTest {

	private static final String RUN_ID = "run-converge";

	private static final String SESSION_ID = "s-converge";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

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

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "r144-converge-watchdog");
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
		this.appender = new ListAppender<>();
		this.appender.start();
		((Logger) LoggerFactory.getLogger(ResilientChatService.class)).addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(ResilientChatService.class)).detachAppender(this.appender);
		this.watchdogExecutor.shutdownNow();
	}

	// ── 终态直写 + T3 kind 携载 ──

	@Test
	void precheckFailedEntryIsTerminalEvenWithoutPersistedOutcome() {
		// NIT（收口）：PRECHECK_FAILED 补进 isTerminalEntryStatus —— 否则旧载荷缺 outcome 时
		// 会落到"非终态"分支，最终可能被前端兜底成 succeeded。
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-precheck");
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PRECHECK_FAILED);

		Map<String, Object> state = this.service.resolveToolCallState(RUN_ID, pending, RunSnapshot.SUSPENDED,
				"c-precheck");

		assertThat(state).containsEntry("status", PendingToolCall.PRECHECK_FAILED)
			.containsEntry("outcome", ToolOutcome.FAILED.wire())
			.containsEntry("code", ToolOutcome.CODE_PRECHECK_FAILED);
	}

	@Test
	void terminalEntryStatusIsCarriedPerToolCall() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.EXECUTED, true, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.EXECUTED)
			.containsEntry("kind", PendingToolCall.KIND_CONFIRM);
	}

	/** P1-10：SKIPPED 是终态，历史段状态照常写 outcome=skipped 与 code=UPSTREAM_FAILED。 */
	@Test
	void skippedEntryIsCarriedAsSkippedOutcome() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		PendingToolCall skipped = pending("tc-1", PendingToolCall.SKIPPED, false, PendingToolCall.KIND_CONFIRM);
		skipped.setOutcome(ToolOutcome.SKIPPED.wire());
		skipped.setCode(ToolOutcome.CODE_UPSTREAM_FAILED);
		this.store.seedPending(RUN_ID, skipped);

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.SKIPPED)
			.containsEntry("outcome", ToolOutcome.SKIPPED.wire())
			.containsEntry("code", ToolOutcome.CODE_UPSTREAM_FAILED);
	}

	// ── T2 升格判据 ──

	@Test
	void approvedWithExecutedFlagUpgradesToExecuted() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.APPROVED, true, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.EXECUTED);
	}

	@Test
	void approvedWithoutExecutedAndWithoutLedgerStaysApproved() {
		// 反向例（T2 升格判据不误触发）：APPROVED + executed=false + 台账未命中 ⇒ 保持 APPROVED（前端映射 pending）。
		// 该分支要求快照非终态；convergeMemory 三个调用点均在终态快照下运行，故直接调 resolveToolCallState 验证。
		Map<String, Object> state = this.service.resolveToolCallState(RUN_ID,
				pending("tc-1", PendingToolCall.APPROVED, false, PendingToolCall.KIND_CONFIRM),
				RunSnapshot.RUNNING, "tc-1");

		assertThat(state).containsEntry("status", PendingToolCall.APPROVED)
			.containsEntry("kind", PendingToolCall.KIND_CONFIRM);
	}

	/**
	 * M1：未决条目（PENDING/APPROVED）不写 {@code outcome} —— 结局还没产生，写 unknown 会让前端
	 * 的 unknown→failed 兜底把"等待中"卡面覆盖成失败。缺键 = 前端按 status 展示。
	 */
	@Test
	void unresolvedEntriesOmitOutcomeSoFrontendKeepsPendingCard() {
		Map<String, Object> pendingState = this.service.resolveToolCallState(RUN_ID,
				pending("tc-1", PendingToolCall.PENDING, false, PendingToolCall.KIND_CONFIRM),
				RunSnapshot.RUNNING, "tc-1");
		assertThat(pendingState).containsEntry("status", PendingToolCall.PENDING).doesNotContainKey("outcome");

		Map<String, Object> approvedState = this.service.resolveToolCallState(RUN_ID,
				pending("tc-1", PendingToolCall.APPROVED, false, PendingToolCall.KIND_CONFIRM),
				RunSnapshot.RUNNING, "tc-1");
		assertThat(approvedState).containsEntry("status", PendingToolCall.APPROVED).doesNotContainKey("outcome");
	}

	@Test
	void approvedWithLedgerHitUpgradesToExecuted() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.APPROVED, false, PendingToolCall.KIND_CONFIRM));
		this.store.seedLedger(RUN_ID, "tc-1", "start_publish", "OK");

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.EXECUTED);
	}

	// ── R-160 M1：决策元数据三字段（decision/decidedAt/submittedValues） ──

	@Test
	void approvedDecisionMetadataSurvivesT2UpgradeToExecuted() {
		// T2 升格 EXECUTED 不抹掉已发生的决策：decision 由条目态（APPROVED）派生，decidedAt ← resolvedAtMs。
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		PendingToolCall pending = pending("tc-1", PendingToolCall.APPROVED, true, PendingToolCall.KIND_CONFIRM);
		pending.setResolvedAtMs(1_700_000_000_000L);
		this.store.seedPending(RUN_ID, pending);

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.EXECUTED)
			.containsEntry("decision", "approved")
			.containsEntry("decidedAt", 1_700_000_000_000L)
			.doesNotContainKey("submittedValues"); // 限 FRONTEND：确认门不带 submittedValues
	}

	@Test
	void rejectedDecisionIsDerivedWithDecidedAt() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		PendingToolCall pending = pending("tc-1", PendingToolCall.REJECTED, false, PendingToolCall.KIND_CONFIRM);
		pending.setResolvedAtMs(1_700_000_000_000L);
		this.store.seedPending(RUN_ID, pending);

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.REJECTED)
			.containsEntry("decision", "rejected")
			.containsEntry("decidedAt", 1_700_000_000_000L);
	}

	@Test
	void frontendResultCarriesSubmittedValuesRawText() {
		// submittedValues ← resultText 原文（限 kind==FRONTEND；解析与失败兜底在前端）。
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		PendingToolCall pending = pending("tc-1", PendingToolCall.FRONTEND_RESULT, true, PendingToolCall.KIND_FRONTEND);
		pending.setResultText("{\"region\":\"HB\"}");
		this.store.seedPending(RUN_ID, pending);

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.FRONTEND_RESULT)
			.containsEntry("submittedValues", "{\"region\":\"HB\"}")
			.doesNotContainKey("decision"); // FRONTEND_RESULT 非决策态，decision 不派生
	}

	// ── 定序 + WARN（快照终态 vs 条目非终态） ──

	@Test
	void pendingWithCancelledSnapshotFallsBackToCancelledAndWarns() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.PENDING, false, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.error(new StreamViolationException(
				StreamViolationException.CANCELLED, "取消")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.CANCELLED);
		assertThat(this.appender.list).anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains("以轮次终态为准")
				&& event.getFormattedMessage().contains(RUN_ID)
				&& event.getFormattedMessage().contains("tc-1")
				&& event.getFormattedMessage().contains(RunSnapshot.CANCELLED));
	}

	@Test
	void pendingWithDoneSnapshotFallsBackToDoneAndWarns() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.PENDING, false, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", RunSnapshot.DONE);
		assertThat(this.appender.list).anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains("以轮次终态为准")
				&& event.getFormattedMessage().contains(RUN_ID)
				&& event.getFormattedMessage().contains(RunSnapshot.DONE));
	}

	@Test
	void pendingWithFailedSnapshotFallsBackToEntryAndWarns() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.PENDING, false, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.just(terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.PENDING);
		assertThat(this.appender.list).anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains("不参与段态推导")
				&& event.getFormattedMessage().contains(RUN_ID)
				&& event.getFormattedMessage().contains(RunSnapshot.FAILED));
	}

	// ── S0 实证：cancel/timeout 路径下 convergeMemory 是否可见当轮终态 ──

	/** S0 路径②（取消已 settle、轮未收尾）：当轮 assistant 已在 messageJson，条目已终态 ⇒ 可见终态。 */
	@Test
	void cancelAfterSettleSeesTerminalState() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.EXECUTED, true, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.error(new StreamViolationException(
				StreamViolationException.CANCELLED, "取消")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.EXECUTED);
	}

	/** S0 路径③（超时）：ConfirmGate 置 TIMEOUT（终态）后轮收尾 ⇒ 可见终态。 */
	@Test
	void timeoutEntryIsVisibleToConvergeMemory() {
		seed(List.of(new UserMessage("你好"), assistantWithToolCalls("tc-1"), toolResponse("tc-1")),
				RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.TIMEOUT, false, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.just(chunk("你好"), terminal("stop")));

		assertThat(toolCallState(windowed, 1, "tc-1")).containsEntry("status", PendingToolCall.TIMEOUT);
	}

	/** S0 路径①（取消挂起等待中）：当轮 assistant 尚未进 messageJson ⇒ 无消息则无状态需求，不产悬空段。 */
	@Test
	void cancelDuringSuspendLeavesNoDanglingToolState() {
		seed(List.of(new UserMessage("你好")), RunSnapshot.RUNNING);
		this.store.seedPending(RUN_ID, pending("tc-1", PendingToolCall.PENDING, false, PendingToolCall.KIND_CONFIRM));

		List<Message> windowed = driveAndCapture(Flux.error(new StreamViolationException(
				StreamViolationException.CANCELLED, "取消")));

		assertThat(windowed).noneMatch(message -> message instanceof AssistantMessage assistant
				&& assistant.getMetadata().containsKey(ResilientChatService.TOOL_CALL_STATES_KEY));
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private void seed(List<Message> history, String snapshotStatus) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(history));
		snapshot.setStatus(snapshotStatus);
		this.store.save(snapshot);
	}

	private List<Message> driveAndCapture(Flux<ChatResponse> stream) {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(stream);
		SseChatEmitter out = this.registry.of(RUN_ID);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
		verify(this.chatMemory).add(eq(SESSION_ID), captor.capture());
		return captor.getValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> toolCallState(List<Message> windowed, int assistantIndex, String toolCallId) {
		Message message = windowed.get(assistantIndex);
		assertThat(message).isInstanceOf(AssistantMessage.class);
		AssistantMessage assistant = (AssistantMessage) message;
		Map<String, Object> states = (Map<String, Object>) assistant.getMetadata()
			.get(ResilientChatService.TOOL_CALL_STATES_KEY);
		assertThat(states).as("assistant 应携带 toolCallStates").isNotNull();
		return (Map<String, Object>) states.get(toolCallId);
	}

	private static AssistantMessage assistantWithToolCalls(String toolCallId) {
		return AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "start_publish", "{}")))
			.build();
	}

	private static ToolResponseMessage toolResponse(String toolCallId) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, "start_publish", "OK")))
			.build();
	}

	private static PendingToolCall pending(String toolCallId, String status, boolean executed, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_publish");
		pending.setStatus(status);
		pending.setExecuted(executed);
		pending.setKind(kind);
		return pending;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
