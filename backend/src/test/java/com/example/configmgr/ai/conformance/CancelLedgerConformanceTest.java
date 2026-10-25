package com.example.configmgr.ai.conformance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

	/** 「取消恰在批首卡被 checkCancelled 看到」的复现轮（与上两个用例的轮次隔离）。 */
	private static final String ABORT_RUN = "run-cancel-abort";

	private static final String ABORT_SESSION = "s-cancel-abort";

	private static final String ABORT_CALL = "c-abort-frontier";

	/** 「已批准但尚未认领/执行即被停止」的复现轮（R1）。 */
	private static final String PRE_RUN = "run-preexec-cancel";

	private static final String PRE_SESSION = "s-preexec-cancel";

	private static final String PRE_CALL = "c-pre-exec";

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
	 * <b>M-2′（取消面）</b>：park 开启时用户停止（{@code cancelParked} 是 {@code POST /api/ai/cancel}
	 * 在服务层的落点）⇒ 每张被取消的 {@code CONFIRM}/{@code FRONTEND} 卡<b>恰 1 条</b>
	 * {@code tool_call_end}（{@code outcome=cancelled, code=RUN_CANCELLED, executed=false}）。
	 *
	 * <p>缺陷形态：park 时没有驱动线程，批循环早在 {@code RunParkedException} 处穿出；
	 * {@code cancelTerminal} 只做"条目转 CANCELLED + 补裁决帧"，不发结算行 ⇒
	 * 用户取消在日志里也是"有 start、没有 end"的孤儿调用。
	 *
	 * <p>口径边界：超时/取消的<b>计数</b>为 0 是对的（{@code SheetAudit} 只在条目带
	 * {@code sheetMeta} 时记账，确认卡从来没有），这里断言的是"不得为了计数虚构 sheetMeta"——
	 * 两条条目都没有元数据面 ⇒ 审计计数一个都不许动。
	 */
	@Test
	void parkedCancelSettlesEveryCancelledCardExactlyOnce() {
		this.properties.getPark().setEnabled(true);
		SheetAudit audit = new SheetAudit(new SheetSwitches(this.properties), this.store);
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of());
		gate.setSheetAudit(audit);
		gate.setSettlementObservationStore(observations);
		this.service.setParkSettlement(gate);

		this.service.cancelParked(RUN_ID);

		List<String> ends = ends();
		assertThat(ends).as("取消也是这些卡的终局：每张恰一条结算行").hasSize(2);
		assertThat(ends).anySatisfy(line -> assertThat(line).contains("toolCallId=c-confirm")
			.contains("tool=start_export")
			.contains("kind=CONFIRM")
			.contains("ok=false")
			.contains("outcome=cancelled")
			.contains("code=RUN_CANCELLED")
			.contains("executed=false"));
		assertThat(ends).anySatisfy(line -> assertThat(line).contains("toolCallId=c-frontend")
			.contains("tool=generative_form")
			.contains("kind=FRONTEND")
			.contains("outcome=cancelled")
			.contains("code=RUN_CANCELLED"));
		assertThat(this.store.pendings(RUN_ID)).allMatch(entry -> PendingToolCall.CANCELLED.equals(entry.getStatus()));
		assertThat(audit.metrics()).as("无 sheetMeta ⇒ 不得为了计数虚构元数据")
			.containsEntry("readCalls", 0L)
			.containsEntry("writePlans", 0L)
			.containsEntry("writeCancelled", 0L);

		// 取消幂等（P-09）：再取消一次不得变成第二条 end —— 仲裁位（ai:observed）由首写者占住
		this.service.cancelParked(RUN_ID);
		assertThat(ends()).as("重复取消不得放大结算行").hasSize(2);
	}

	/**
	 * <b>M-2′ 残余（abort × 取消双发）</b>：取消在"卡已开始执行、尚未 park"的窗口被看到时，
	 * {@code ConfirmGate#await} 走 {@code cancel(...)} 返回 CANCELLED 条目，批循环随后的
	 * {@code checkCancelled} 穿出并让 {@code SpToolCallingManager#traceToolAborted} 发一条
	 * {@code tool_call_end}（<b>直发</b>、不占 {@code ai:observed}）；紧接着
	 * {@code cancelParked → cancelTerminal} 的 {@code settleCancelledFromPark} 又按 CANCELLED
	 * 结算同一条 ⇒ 同一 toolCallId <b>两行</b>。
	 *
	 * <p>判据：真实批循环 + 真实取消收尾走完后，该 toolCallId <b>恰 1 条</b>，且那一行仍是"取消"
	 * 语义（{@code outcome=cancelled}/{@code code=RUN_CANCELLED}/{@code executed=false}）。
	 * 两行各自都"合法"，错的是同一条调用被结算两次 —— {@code ai:observed} 的仲裁位必须覆盖 abort 发点。
	 *
	 * <p>时序（确定性，无并发）：{@code cancelState} 第 1 次（批首检查）= NOT_CANCELLED，
	 * 第 2 次起（{@code await} 的 park 出口）= CANCELLED —— 正是"取消恰好落在两者之间"。
	 */
	@Test
	void cancelSeenAtTheParkExitDoesNotDoubleSettle() {
		this.properties.getPark().setEnabled(true);
		seedAbortRun();
		stubConfirmTool();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);
		SpToolCallingManager manager = manager(gate, observations);
		when(this.cancellations.cancelState(ABORT_RUN)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED,
				CancellationRegistry.CancelState.CANCELLED);

		assertThatThrownBy(() -> manager.executeToolCalls(prompt(ABORT_RUN), response("start_import")))
			.as("park 出口看到取消：条目转 CANCELLED，批循环在结算后复查处穿出")
			.isInstanceOf(StreamViolationException.class);
		assertThat(endsFor(ABORT_CALL)).as("前提：abort 行已经发出").hasSize(1);

		this.service.cancelParked(ABORT_RUN);

		List<String> ends = endsFor(ABORT_CALL);
		assertThat(ends).as("同一条调用不得被 abort 与取消收尾各结算一次").hasSize(1);
		assertThat(ends.get(0)).contains("outcome=cancelled")
			.contains("code=RUN_CANCELLED")
			.contains("executed=false");
		assertThat(this.store.pending(ABORT_RUN, ABORT_CALL).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
	}

	/**
	 * <b>非 park 边界（abort）</b>：同一窗口在 park 关闭时走的是等待循环里的取消检查
	 * （{@code await} 的 wait loop），条目同样转 CANCELLED 并产生 abort 行；但取消侧在 park 关闭时
	 * 本来就不结算 ⇒ 仍只有一条。本用例锁住"给 abort 加仲裁不得改变 park-off 的既有形态"。
	 */
	@Test
	void abortLineStaysSingleWhenParkIsOff() {
		this.properties.getPark().setEnabled(false);
		seedAbortRun();
		stubConfirmTool();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);
		SpToolCallingManager manager = manager(gate, observations);
		when(this.cancellations.cancelState(ABORT_RUN)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED,
				CancellationRegistry.CancelState.CANCELLED);

		assertThatThrownBy(() -> manager.executeToolCalls(prompt(ABORT_RUN), response("start_import")))
			.isInstanceOf(StreamViolationException.class);
		assertThat(endsFor(ABORT_CALL)).as("park 关闭：abort 行照旧一条").hasSize(1);

		this.service.cancelParked(ABORT_RUN);

		assertThat(endsFor(ABORT_CALL)).as("park 关闭：取消侧不补").hasSize(1);
	}

	/**
	 * <b>BACKEND 不得被取消侧补发</b>：{@code BACKEND} 的结算权归执行认领、不占 {@code ai:observed}
	 * （{@code ToolCallEndTrace#requiresArbitration=false} ⇒ 仲裁对它<b>不生效</b>）。因此取消侧若把它
	 * 也收进来，就是"无条件补一条"；本用例锁住取消侧只结 CONFIRM/FRONTEND。
	 */
	@Test
	void cancelSideNeverSettlesBackendEntries() {
		this.properties.getPark().setEnabled(true);
		seedAbortRun();
		PendingToolCall backend = pending("c-backend-cancelled", PendingToolCall.KIND_BACKEND, "read_sheet");
		backend.setStatus(PendingToolCall.CANCELLED);
		backend.setReason("RUN_CANCELLED");
		backend.setResolvedAtMs(System.currentTimeMillis());
		backend.setResultText("本轮对话已被用户取消，该工具未执行。");
		this.store.putPending(ABORT_RUN, backend);
		this.store.putPending(ABORT_RUN, pending("c-confirm-cancelled", PendingToolCall.KIND_CONFIRM, "start_import"));
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);

		this.service.cancelParked(ABORT_RUN);

		assertThat(endsFor("c-backend-cancelled")).as("BACKEND 不在取消侧的结算集合里").isEmpty();
		assertThat(endsFor("c-confirm-cancelled")).as("CONFIRM 照常结一条").hasSize(1);
	}

	/**
	 * <b>R1-A：已批准、尚未认领/执行时被停止</b> ⇒ 条目必须从 {@code APPROVED} 收敛为
	 * {@code CANCELLED}（{@code outcome=cancelled}/{@code code=RUN_CANCELLED}/{@code executed=false}），
	 * 工具<b>绝不执行</b>，且该 toolCallId <b>恰 1 条</b> end。
	 *
	 * <p>缺陷形态：`cancelPendings` 只碰 {@code PENDING}，批准后的条目留在 {@code APPROVED}；
	 * 取消侧只结 {@code CANCELLED} ⇒ 这张卡既没被执行、也没有结算行，`APPROVED` 还会让下一次
	 * 续跑（若取消标志丢失/被清）把它执行掉。真正的工具保护在 {@code ResumeService} 的执行前
	 * {@code cancelStateOutcome}（本例不驱动续跑，故工具必然未执行），本条锁的是**收敛与结算**。
	 */
	@Test
	void approvedButNeverClaimedIsCancelledAndSettledExactlyOnce() {
		this.properties.getPark().setEnabled(true);
		seedPreRun();
		stubConfirmTool();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);
		SpToolCallingManager manager = manager(gate, observations);

		// 1) 首轮 park 一张确认卡：tool_start 已前移公布，条目 PENDING，请求帧已发出
		assertThatThrownBy(() -> manager.executeToolCalls(prompt(PRE_RUN), response(PRE_CALL, "start_import")))
			.isInstanceOf(RunParkedException.class);
		// 2) 用户批准：决策落库为 APPROVED，仍未认领、未执行
		assertThat(gate.submitDecision(PRE_RUN, PRE_CALL, true, "批准").outcome())
			.isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		PendingToolCall approved = this.store.pending(PRE_RUN, PRE_CALL);
		assertThat(approved.getStatus()).isEqualTo(PendingToolCall.APPROVED);
		assertThat(approved.isExecuted()).as("复现前提：批准 ≠ 已执行").isFalse();

		// 3) 执行方认领之前，用户按停止
		this.service.cancelParked(PRE_RUN);

		// 4) 条目可靠收敛（不是继续挂着 APPROVED）+ 恰一条取消语义的 end
		PendingToolCall cancelled = this.store.pending(PRE_RUN, PRE_CALL);
		assertThat(cancelled.getStatus()).as("已批准但未认领的条目必须收敛为 CANCELLED").isEqualTo(PendingToolCall.CANCELLED);
		assertThat(cancelled.effectiveOutcome()).isEqualTo(ToolOutcome.CANCELLED);
		assertThat(cancelled.effectiveCode()).isEqualTo(ToolOutcome.CODE_RUN_CANCELLED);
		assertThat(cancelled.isExecuted()).isFalse();
		List<String> ends = endsFor(PRE_CALL);
		assertThat(ends).as("取消收尾必须给这张卡补上唯一一条结算行").hasSize(1);
		assertThat(ends.get(0)).contains("outcome=cancelled")
			.contains("code=RUN_CANCELLED")
			.contains("executed=false");
	}

	/**
	 * <b>R1-A（认领前窗口）</b>：条目仍是 {@code APPROVED}（已决、未认领）而取消标志已置位时，
	 * 执行方在<b>真正 claim 之前</b>必须复核并放弃执行 —— 否则就是"取消后仍执行不可逆工具"。
	 *
	 * <p>这里直接调用 {@code resolvePending}（续跑重建里紧接在 {@code cancelStateOutcome} 之后的那一步），
	 * 正是"取消落在检查与认领之间"的时刻：工具回调可被真实解析、调用即计数。
	 */
	@Test
	void approvedEntryIsNotExecutedWhenStopLandsBeforeTheClaim() {
		this.properties.getPark().setEnabled(true);
		seedPreRun();
		stubConfirmTool();
		RecordingImportTool tool = new RecordingImportTool();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);
		SpToolCallingManager manager = manager(gate, observations);
		PendingToolCall approved = approvedPending(PRE_CALL, "start_import");
		this.store.putPending(PRE_RUN, approved);
		when(this.cancellations.cancelState(PRE_RUN)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		manager.resolvePending(PRE_RUN, promptWithTools(PRE_RUN, tool), this.store.pending(PRE_RUN, PRE_CALL));

		assertThat(tool.invocations.get()).as("取消后不得再认领/执行该工具").isZero();
		PendingToolCall after = this.store.pending(PRE_RUN, PRE_CALL);
		assertThat(after.getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(after.isExecuted()).as("未执行 ⇒ executed 必须保持 false").isFalse();
		assertThat(after.effectiveOutcome()).isEqualTo(ToolOutcome.CANCELLED);

		// 取消终局（真实 cancelParked）→ 同一条调用恰一条 end（arbitration 只发一次）
		this.service.cancelParked(PRE_RUN);
		assertThat(endsFor(PRE_CALL)).hasSize(1);
		assertThat(endsFor(PRE_CALL).get(0)).contains("outcome=cancelled").contains("executed=false");
	}

	/**
	 * <b>R1-B：已认领/已完成绝不伪装成"未执行"</b>。任何 {@code executed} / {@code claimedAtMs} /
	 * {@code claimedEpoch} / {@code executedBy} 证据存在时，取消收尾不得改写状态、不得抹掉结果，
	 * 也不得补一条"取消"结算行（那会把一次真实发生的副作用说成没发生）。
	 */
	@Test
	void claimedOrCompletedEntriesAreNeverRelabelledAsCancelled() {
		this.properties.getPark().setEnabled(true);
		seedPreRun();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);

		PendingToolCall claimed = approvedPending("c-claimed", "start_import");
		claimed.setExecuted(true);
		claimed.setClaimedAtMs(System.currentTimeMillis());
		claimed.setClaimedEpoch(1L);
		claimed.setExecutedBy("instance-b");
		claimed.setResultText("执行中（认领已落库）");
		this.store.putPending(PRE_RUN, claimed);

		PendingToolCall completed = pending("c-completed", PendingToolCall.KIND_CONFIRM, "start_import");
		completed.setStatus(PendingToolCall.EXECUTED);
		completed.setExecuted(true);
		completed.setExecutedBy("instance-b");
		completed.setExecutedAtMs(System.currentTimeMillis());
		completed.setOutcome(ToolOutcome.OK.wire());
		completed.setResultText("导入作业已启动（作业 #4242）");
		this.store.putPending(PRE_RUN, completed);

		this.service.cancelParked(PRE_RUN);

		PendingToolCall claimedAfter = this.store.pending(PRE_RUN, "c-claimed");
		assertThat(claimedAfter.getStatus()).as("已认领的条目不得被改成 CANCELLED").isEqualTo(PendingToolCall.APPROVED);
		assertThat(claimedAfter.isExecuted()).isTrue();
		assertThat(claimedAfter.getExecutedBy()).isEqualTo("instance-b");
		assertThat(claimedAfter.getResultText()).isEqualTo("执行中（认领已落库）");

		PendingToolCall completedAfter = this.store.pending(PRE_RUN, "c-completed");
		assertThat(completedAfter.getStatus()).isEqualTo(PendingToolCall.EXECUTED);
		assertThat(completedAfter.getResultText()).as("真实结果不得被抹掉").contains("#4242");

		assertThat(endsFor("c-claimed")).as("已认领的不补\"取消\"结算行").isEmpty();
		assertThat(endsFor("c-completed")).isEmpty();
	}

	/**
	 * <b>R1-C1（UNKNOWN 必须 fail-closed）</b>：认领前复核读到取消状态 {@code UNKNOWN}
	 * （Redis 读失败/恢复窗口）时，与相邻的 {@code checkCancelled} 同口径 —— <b>不得执行不可逆工具</b>，
	 * 用既有 {@code STATE_UNAVAILABLE} 语义终止，<b>不改条目状态、不发假终帧</b>。
	 *
	 * <p>缺陷形态：旧口径把"读不出来"当成"未取消"直接放行认领 ⇒ 依赖抖动期间工具照跑。
	 */
	@Test
	void unknownCancelStateBeforeTheClaimFailsClosedLikeCheckCancelled() {
		this.properties.getPark().setEnabled(true);
		seedPreRun();
		stubConfirmTool();
		RecordingImportTool tool = new RecordingImportTool();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);
		SpToolCallingManager manager = manager(gate, observations);
		this.store.putPending(PRE_RUN, approvedPending(PRE_CALL, "start_import"));
		when(this.cancellations.cancelState(PRE_RUN)).thenReturn(CancellationRegistry.CancelState.UNKNOWN);

		assertThatThrownBy(() -> manager.resolvePending(PRE_RUN, promptWithTools(PRE_RUN, tool),
				this.store.pending(PRE_RUN, PRE_CALL)))
			.isInstanceOf(StreamViolationException.class)
			.satisfies(error -> assertThat(((StreamViolationException) error).getCode())
				.isEqualTo(StreamViolationException.STATE_UNAVAILABLE));

		assertThat(tool.invocations.get()).as("取消状态不可知时不得执行不可逆工具").isZero();
		PendingToolCall after = this.store.pending(PRE_RUN, PRE_CALL);
		assertThat(after.getStatus()).as("基础设施不可知不是取消，条目状态不得被改写")
			.isEqualTo(PendingToolCall.APPROVED);
		assertThat(after.isExecuted()).isFalse();
		assertThat(endsFor(PRE_CALL)).as("不得发假终帧/假结算行").isEmpty();
	}

	/**
	 * <b>R1-C2（已 CANCELLED 且无认领证据）</b>：调用方手里仍是旧的 {@code APPROVED} 副本、
	 * 而存储里该条目已被取消（另一实例/取消收尾先到）时，认领前复核必须
	 * <b>不认领、不执行</b>并返回<b>既有取消文本</b>；不得因为"状态不是 PENDING/APPROVED"就
	 * 掉回 {@code tryClaimExecution} 去执行一条已取消的调用。
	 *
	 * <p>缺陷形态：旧判据只认 PENDING/APPROVED ⇒ {@code cancellableBeforeExecution=false}
	 * ⇒ 返回 null ⇒ 继续认领 ⇒ 已取消的工具被真正执行。
	 */
	@Test
	void alreadyCancelledEntryWithStaleApprovedCopyIsNotClaimedOrExecuted() {
		this.properties.getPark().setEnabled(true);
		seedPreRun();
		stubConfirmTool();
		RecordingImportTool tool = new RecordingImportTool();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);
		SpToolCallingManager manager = manager(gate, observations);
		PendingToolCall cancelled = approvedPending(PRE_CALL, "start_import");
		cancelled.setStatus(PendingToolCall.CANCELLED);
		cancelled.setReason("RUN_CANCELLED");
		cancelled.setExecuted(false);
		cancelled.setResolvedAtMs(System.currentTimeMillis());
		String cancelText = "本轮对话已被用户取消，该工具未执行。";
		cancelled.setResultText(cancelText);
		this.store.putPending(PRE_RUN, cancelled);
		when(this.cancellations.cancelState(PRE_RUN)).thenReturn(CancellationRegistry.CancelState.CANCELLED);

		// 调用方持有的旧副本：status 仍是 APPROVED（真实的"读到之后被取消"窗口）
		PendingToolCall stale = approvedPending(PRE_CALL, "start_import");
		String text = manager.resolvePending(PRE_RUN, promptWithTools(PRE_RUN, tool), stale);

		assertThat(tool.invocations.get()).as("已取消的调用不得再被认领/执行").isZero();
		assertThat(text).as("返回既有取消文本（不伪造执行结果）").isEqualTo(cancelText);
		PendingToolCall after = this.store.pending(PRE_RUN, PRE_CALL);
		assertThat(after.getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(after.isExecuted()).isFalse();
		assertThat(this.store.claimedBy(PRE_RUN, PRE_CALL)).as("不得占用认领位").isNull();
		assertThat(endsFor(PRE_CALL)).isEmpty();
	}

	/**
	 * <b>R1-C3（认领表已写、条目仍零证据）</b>：跨实例认领的中间窗口 —— 对方
	 * {@code HSETNX} 已落而 {@code ai:pending} 还没回写 {@code executed/claimedAt}。
	 * 取消收尾<b>不得</b>把该条转成 {@code CANCELLED}，也不得补发"取消"结算行
	 * （那会把一次已被认领、随时会执行的副作用说成没发生）。
	 */
	@Test
	void claimHashWrittenBeforePendingIsNeverRelabelledAsCancelled() {
		this.properties.getPark().setEnabled(true);
		seedPreRun();
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = parkedGate(observations);
		this.service.setParkSettlement(gate);

		PendingToolCall approved = approvedPending("c-claim-window", "start_import");
		approved.setResultText("（已批准，尚未回写认领字段）");
		this.store.putPending(PRE_RUN, approved);
		this.store.seedClaimOnly(PRE_RUN, "c-claim-window", "1|instance-b");

		this.service.cancelParked(PRE_RUN);

		PendingToolCall after = this.store.pending(PRE_RUN, "c-claim-window");
		assertThat(after.getStatus()).as("认领表已有该条 ⇒ 不得伪造成未执行").isEqualTo(PendingToolCall.APPROVED);
		assertThat(after.isExecuted()).isFalse();
		assertThat(after.getResultText()).isEqualTo("（已批准，尚未回写认领字段）");
		assertThat(endsFor("c-claim-window")).as("不得补发错误的取消结算行").isEmpty();
	}

	/**
	 * <b>非 park 的边界</b>：park 关闭时结算权始终在等待线程手上（它会在下一拍轮询到已决/已取消
	 * 并自己结算）⇒ 取消侧<b>不补</b>任何结算行。本用例锁住"修 park 不得把非 park 变成补发源"。
	 */
	@Test
	void cancelWithParkOffDoesNotSettleFromTheCancelSide() {
		this.properties.getPark().setEnabled(false);
		SettlementObservationStore observations = new SettlementObservationStore();
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of());
		gate.setSettlementObservationStore(observations);
		this.service.setParkSettlement(gate);

		this.service.cancelParked(RUN_ID);

		assertThat(ends()).as("非 park：结算权不在取消侧").isEmpty();
		assertThat(this.store.pendings(RUN_ID)).allMatch(entry -> PendingToolCall.CANCELLED.equals(entry.getStatus()));
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

	/** 捕获的 {@code tool_call_end} 行（按行首匹配，避免前缀误命中）。 */
	private List<String> ends() {
		return this.logs.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.filter(message -> message.startsWith("event=tool_call_end "))
			.toList();
	}

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

	/** 该 toolCallId 的结算行（同一 id 两行 = "恰好一次"被破坏）。 */
	private List<String> endsFor(String toolCallId) {
		return ends().stream().filter(line -> line.contains("toolCallId=" + toolCallId)).toList();
	}

	/** abort 复现轮的快照（批循环要求 runId 有快照；挂起态由 saveSuspended 写）。 */
	private void seedAbortRun() {
		RunSnapshot snapshot = this.store.seedRun(ABORT_RUN, ABORT_SESSION);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 1L, Map.of()).toMap());
		this.store.save(snapshot);
	}

	/** 确认卡工具：{@code BACKEND} + {@code DANGER} ⇒ kind=CONFIRM。 */
	private void stubConfirmTool() {
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.sheetDisabled(anyString())).thenReturn(false);
	}

	/** park 结算面已接线的闸门（仲裁位为进程内替身）。 */
	private ConfirmGate parkedGate(SettlementObservationStore observations) {
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				this.beacon, List.of());
		gate.setSettlementObservationStore(observations);
		return gate;
	}

	/** 本轮工具调用批的处理器（9 参轻装配：无预检/无资源锁）；仲裁位与闸门<b>同一个</b>（生产同为单例）。 */
	private SpToolCallingManager manager(ConfirmGate gate, SettlementObservationStore observations) {
		SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
		manager.setSettlementObservationStore(observations);
		return manager;
	}

	private static Prompt prompt(String runId) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolContext(Map.of("runId", runId)).build();
		return new Prompt(List.of(new UserMessage("把这张表导入")), options);
	}

	private static ChatResponse response(String tool) {
		return response(ABORT_CALL, tool);
	}

	private static ChatResponse response(String toolCallId, String tool) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", tool, "{}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	/** 「已批准未执行」复现轮的快照（context 固定，便于 kind/scope 判定）。 */
	private void seedPreRun() {
		RunSnapshot snapshot = this.store.seedRun(PRE_RUN, PRE_SESSION);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 1L, Map.of()).toMap());
		this.store.save(snapshot);
	}

	/** 已决但未认领未执行的确认卡（R1 的起点状态）。 */
	private static PendingToolCall approvedPending(String toolCallId, String name) {
		PendingToolCall pending = pending(toolCallId, PendingToolCall.KIND_CONFIRM, name);
		pending.setStatus(PendingToolCall.APPROVED);
		pending.setExecuted(false);
		pending.setResolvedAtMs(System.currentTimeMillis());
		return pending;
	}

	/** 带<b>真实可解析工具回调</b>的 Prompt（取消后必须 0 次调用才能证明"没执行"）。 */
	private static Prompt promptWithTools(String runId, Object... tools) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(toolCallbacks(tools))
			.toolContext(Map.of("runId", runId))
			.build();
		return new Prompt(List.of(new UserMessage("把这张表导入")), options);
	}

	private static List<ToolCallback> toolCallbacks(Object... tools) {
		if (tools == null || tools.length == 0) {
			return List.of();
		}
		return List.of(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks());
	}

	/** 受控的不可逆工具替身：被真正调用即计数（0 = 从未执行）。 */
	static final class RecordingImportTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "start_import", description = "受控替身：启动导入作业（不写库）")
		String startImport() {
			this.invocations.incrementAndGet();
			return "导入作业已启动（作业 #4242）";
		}

	}
}
