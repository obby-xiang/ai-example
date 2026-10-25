package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>park 结算的审计面</b>（{@code AIE2E-T-13} 的回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * park 化（人工等待 park：请求帧投递后线程交还）之后，工具的结算从"内联批循环"挪到了
 * "决策回灌 → 续跑复用结果"这条路上。内联循环那条唯一的 {@code tool_call_end} 与
 * {@link SheetAudit#record} 因此整条消失：9 个表格工具（全部 {@code FRONTEND} 通道）的
 * {@code /health.sheet.counters} 恒为 0，日志里则变成"有 start、没有 end"的孤儿调用
 * （主 CI {@code AIE2E-T-13}：{@code readCalls 未增长（0 → 0）}）。
 *
 * <h2>修法的判据 = 所有权边界（不是"看到结果就补一行"）</h2>
 * 两条互斥的结算所有权，恰好一个发点：
 * <ul>
 * <li><b>决策写胜者 ∧ 本进程无人等待</b>（{@code woke=false}）：线程已交还，结算属续跑 ⇒
 * 决策/放弃的写入方发一行（{@link ConfirmGate#submitFrontendResult} / {@code dismiss}）；</li>
 * <li><b>本进程有人等待</b>（{@code woke=true}）：等待线程接着驱动，内联循环自己结算 ⇒
 * 决策写方<b>不发</b>（否则同一个 toolCallId 两条 end）；</li>
 * <li><b>后端执行</b>：{@code claimExecution} 的胜者发一行（续跑路径），内联路径仍由循环发。</li>
 * </ul>
 * 续跑的"复用结果"分支刻意<b>不</b>作为发点：重挂/重试会反复经过它，记账会被放大。
 *
 * <p>本类用"真闸门 + 真续跑 + 真 store"的装配复现真实链路（park → 回灌 → 续跑），
 * 而不是对 mock 的假设：{@code readCalls} 与 {@code tool_call_end} 行数是同一条链路的两个面。
 */
class ParkSettlementAuditTest {

	private static final String RUN_ID = "run-park-audit";

	private static final String SESSION_ID = "s-park-audit";

	private static final String CALL_ID = "call-park-audit";

	/** 回灌结果文本（T-13 的真实前端文案，只要非空即可；日志面只记字符数）。 */
	private static final String RESULT_TEXT = "按 decimalPlaces 聚合：共 2 组（默认按 count 降序）\n  2 位 = 4 行\n"
			+ "  0 位 = 1 行\n共 5 行";

	/** T-13 的读侧元数据（真实前端 {@code aggregateSheet} 上送的那一组）。 */
	private static final Map<String, Object> AGG_META = Map.of("dataClass", "D1", "totalRows", 5, "sheetVer", 1,
			"defCode", "CURRENCY");

	private final AiProperties properties = new AiProperties();

	/** 往返模式：每次读条目都是新副本（与真实 Redis 反序列化同口径）。 */
	private final InMemoryRunStore store = new InMemoryRunStore(true);

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor taskExecutor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	/** 派发面替身：本类自己调 {@code service.resume}，不让死卡兜底在断言中途改动状态。 */
	private final ResumeService dispatchedResume = mock(ResumeService.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<ResumeService> resumeProvider = mock(ObjectProvider.class);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final FakeStartImportTool backendTool = new FakeStartImportTool();

	private SheetAudit audit;

	/** 本实例的结算观测位（进程内退化模式；跨实例面见 ParkSettlementOwnershipTest）。 */
	private final SettlementObservationStore observations = new SettlementObservationStore();

	private ConfirmGate gate;

	private SpToolCallingManager manager;

	private ResumeService service;

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.sheetDisabled(anyString())).thenReturn(false);
		// 表格工具全部是 FRONTEND 通道（SheetSwitches 的 9 个）；确认卡走 BACKEND + DANGER（下方覆盖）
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()).toMap());
		this.store.save(snapshot);
		when(this.resumeProvider.getIfAvailable()).thenReturn(this.dispatchedResume);
		when(this.dispatchedResume.resume(RUN_ID)).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.NOT_FOUND, Map.of("runId", RUN_ID)));
		this.audit = new SheetAudit(new SheetSwitches(this.properties), this.store);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), this.resumeProvider, Runnable::run);
		this.gate.setSheetAudit(this.audit);
		this.gate.setSettlementObservationStore(this.observations);
		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
		this.manager.setSheetAudit(this.audit);
		this.manager.setSettlementObservationStore(this.observations);
		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService, this.manager,
				this.taskExecutor, this.redis, this.properties, this.cancellations,
				new ToolResultLimiter(this.properties));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.taskExecutor).execute(any(Runnable.class));
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(prompt());
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	/**
	 * <b>核心用例（T-13 的复现）</b>：park 之后回灌 {@code frontend_result} ⇒
	 * {@code readCalls} 1、{@code tool_call_end} 恰 1 条（带表格 8 字段），
	 * 且字段值与内联路径同口径（{@code outcome/code/executed/skipped} 都取自条目真值）。
	 */
	@Test
	void parkedFrontendResultSettlesReadCallAndExactlyOneEnd() {
		parkCard("aggregate_sheet");
		assertThat(lines("tool_call_end")).as("复现前提：park 那一刻还没有任何结算行").isEmpty();

		ConfirmGate.Submission submission = submitResult("ok", AGG_META);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.audit.metrics()).as("读工具结算必须进审计面（T-13 的红就在这一行）")
			.containsEntry("readCalls", 1L);
		List<String> ends = lines("tool_call_end");
		assertThat(ends).as("park 结算恰一条 tool_call_end（start 不再留孤儿）").hasSize(1);
		assertThat(ends.get(0)).contains("tool=aggregate_sheet").contains("kind=FRONTEND").contains("skipped=false")
			.contains("ok=true").contains("outcome=ok").contains("code=-")
			.as("前端工具的桩体不由后端执行：executed 保持条目真值（false）")
			.contains("executed=false")
			.contains("dataClass=D1").contains("totalRows=5").contains("sheetVer=1")
			.contains("sampleRows=-").contains("planId=-");
		assertThat(ends.get(0)).as("日志面严格 8 字段：defCode 只进台账、不进日志")
			.doesNotContain("defCode=");
		assertThat(this.store.safetyLedger(SESSION_ID)).as("D1 纯元数据不写台账（防台账被结构查询刷满）").isEmpty();
	}

	/** 重复回灌（第二个页签/重发）：先到的那次已决，后到的 DUPLICATE —— 计数与行数都不许再涨。 */
	@Test
	void duplicateFrontendResultIsNotASecondSettlement() {
		parkCard("aggregate_sheet");
		submitResult("ok", AGG_META);

		ConfirmGate.Submission again = submitResult("ok", AGG_META);

		assertThat(again.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(this.audit.metrics()).containsEntry("readCalls", 1L);
		assertThat(lines("tool_call_end")).hasSize(1);
	}

	/**
	 * 续跑的"复用结果"分支<b>不是发点</b>：重挂/重试会反复经过它，在那里记账会把
	 * {@code readCalls} 放大成"重挂次数"。
	 */
	@Test
	void resumeReplayOfTheSettledResultDoesNotAmplify() {
		parkCard("aggregate_sheet");
		submitResult("ok", AGG_META);

		String first = this.manager.resolvePending(RUN_ID, prompt(), pending());
		String second = this.manager.resolvePending(RUN_ID, prompt(), pending());

		assertThat(first).isEqualTo(RESULT_TEXT);
		assertThat(second).isEqualTo(RESULT_TEXT);
		assertThat(this.audit.metrics()).as("复用分支不得再记一次读调用").containsEntry("readCalls", 1L);
		assertThat(lines("tool_call_end")).as("复用分支不得再发一条 end").hasSize(1);
	}

	/** 整条 park → 回灌 → 续跑链走一遍（真 ResumeService）：计数与行数都停在 1，二次续跑不放大。 */
	@Test
	void fullResumeAfterParkKeepsTheAuditAtOne() {
		parkCard("aggregate_sheet");
		submitResult("ok", AGG_META);

		ResumeService.ResumeResult resumed = this.service.resume(RUN_ID);

		assertThat(resumed.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.audit.metrics()).containsEntry("readCalls", 1L);
		assertThat(lines("tool_call_end")).hasSize(1);
		assertThat(this.pending().getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ALREADY_RUNNING);
		assertThat(this.audit.metrics()).as("第二次续跑不改变审计面").containsEntry("readCalls", 1L);
		assertThat(lines("tool_call_end")).hasSize(1);
	}

	/** 放弃（{@code cancelled=true}）：写工具的结局计数落 {@code writeCancelled}，end 恰 1 条。 */
	@Test
	void parkedDismissSettlesCancelledWriteOnce() {
		parkCard("replace_values");
		Map<String, Object> meta = Map.of("dataClass", "D2", "planId", "plan-write-9", "sampleRows", 3,
				"sampleChars", 120);

		ConfirmGate.Submission submission = this.gate.submitFrontendResult(RUN_ID, CALL_ID, null, "aie2e", true, null,
				meta, null);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(this.audit.metrics()).as("写工具放弃 = writePlans + writeCancelled（与内联口径同源）")
			.containsEntry("writePlans", 1L)
			.containsEntry("writeCancelled", 1L)
			.containsEntry("writeApplied", 0L);
		List<String> ends = lines("tool_call_end");
		assertThat(ends).hasSize(1);
		assertThat(ends.get(0)).contains("tool=replace_values").contains("ok=false").contains("outcome=cancelled")
			.contains("code=FRONTEND_CANCELLED").contains("executed=false").contains("planId=plan-write-9");

		ConfirmGate.Submission again = this.gate.submitFrontendResult(RUN_ID, CALL_ID, null, "aie2e", true, null,
				meta, null);
		assertThat(again.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(this.audit.metrics()).containsEntry("writeCancelled", 1L);
		assertThat(lines("tool_call_end")).hasSize(1);
	}

	/**
	 * <b>R1（续跑侧的终止）：取消落在"执行前检查"与"认领"之间</b>时，续跑必须走取消终态
	 * （{@code ALREADY_CANCELLED} + 交回取消收口），不得带着一条 CANCELLED 工具结果继续问模型。
	 *
	 * <p>时序（确定性）：入口检查与重建内逐条检查都看到"未取消"（前两次），此后（认领前复核）
	 * 看到 CANCELLED —— 正好是认领窗口。工具不得执行。
	 */
	@Test
	void cancelLandingBetweenThePreCheckAndTheClaimEndsTheResumeAsCancelled() {
		parkConfirmCard();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED,
				CancellationRegistry.CancelState.NOT_CANCELLED, CancellationRegistry.CancelState.CANCELLED);

		ResumeService.ResumeResult resumed = this.service.resume(RUN_ID);

		assertThat(resumed.outcome()).as("取消优先：认领前命中取消必须收敛成取消终态")
			.isEqualTo(ResumeService.Outcome.ALREADY_CANCELLED);
		assertThat(this.backendTool.invocations.get()).as("不可逆工具不得执行").isZero();
		assertThat(this.pending().getStatus()).isEqualTo(PendingToolCall.CANCELLED);
		assertThat(this.pending().isExecuted()).isFalse();
		org.mockito.Mockito.verify(this.chatService).cancelParked(RUN_ID);
	}

	/**
	 * <b>UNKNOWN 的 typed 结局（续跑路径）</b>：首次取消状态可正常读取，而<b>续跑重建/认领前</b>
	 * 那一次读变成 {@code UNKNOWN}（先成功后抖动）时，HTTP 必须拿到<b>既有</b>的
	 * {@code CANCEL_STATE_UNKNOWN} + {@code reason=CANCEL_STATE_UNKNOWN} + {@code AI_REDIS_UNAVAILABLE}
	 * （503 契约），<b>不是 500</b>。
	 *
	 * <p>认领前复核为 fail-closed 会抛 {@code StreamViolationException(STATE_UNAVAILABLE)}：
	 * 它在 inline 路径是既有语义，但穿出 {@code resume()} 就会变成未翻译的运行时异常 ⇒ 500。
	 * 本用例锁住"在 {@code ResumeService} 里翻译成既有 typed 结局"：
	 * 不执行工具、不进入模型、不写终态，快照保持可重试的 {@code SUSPENDED}。
	 */
	@Test
	void unknownCancelStateDuringRebuildMapsToTheTypedResumeOutcome() {
		parkConfirmCard();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");
		// 第 1 次（入口检查）与第 2 次（逐条执行前检查）= 正常；第 3 次（认领前复核）= UNKNOWN
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED,
				CancellationRegistry.CancelState.NOT_CANCELLED, CancellationRegistry.CancelState.UNKNOWN);

		ResumeService.ResumeResult resumed = this.service.resume(RUN_ID);

		assertThat(resumed.outcome()).as("不得变成 500：必须走既有 CANCEL_STATE_UNKNOWN 结局")
			.isEqualTo(ResumeService.Outcome.CANCEL_STATE_UNKNOWN);
		assertThat(resumed.body()).containsEntry("reason", "CANCEL_STATE_UNKNOWN")
			.containsEntry("code", ErrorCodes.AI_REDIS_UNAVAILABLE)
			.containsEntry("resumed", false);
		assertThat(this.backendTool.invocations.get()).as("取消状态不可知时不得执行不可逆工具").isZero();
		org.mockito.Mockito.verify(this.chatService, org.mockito.Mockito.never())
			.driveResumed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
					org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
					org.mockito.ArgumentMatchers.any());
		RunSnapshot after = this.store.get(RUN_ID);
		assertThat(after.getStatus()).as("不写终态：快照保持可重试的 SUSPENDED").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(lines("tool_call_end")).isEmpty();
	}

	/** 后端执行（续跑侧真正跑工具的那一支）：认领胜者结算一次，end 恰 1 条。 */
	@Test
	void resumeExecutionSettlesBackendToolExactlyOnce() {
		parkConfirmCard();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");

		ResumeService.ResumeResult resumed = this.service.resume(RUN_ID);

		assertThat(resumed.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.backendTool.invocations.get()).as("确认放行后恰执行一次").isEqualTo(1);
		List<String> ends = lines("tool_call_end");
		assertThat(ends).as("续跑执行同样要有 end（不是内联循环专有）").hasSize(1);
		assertThat(ends.get(0)).contains("tool=start_import").contains("kind=CONFIRM").contains("ok=true")
			.contains("outcome=ok").contains("code=-").contains("executed=true");
		assertThat(ends.get(0)).as("非表格工具一个 sheet 键都不写").doesNotContain("dataClass=")
			.doesNotContain("planId=");

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ALREADY_RUNNING);
		assertThat(lines("tool_call_end")).as("二次续跑不得再结一次").hasSize(1);
		assertThat(this.backendTool.invocations.get()).isEqualTo(1);
	}

	/** 后端执行失败（续跑侧）：结局是真值（{@code ok=false}/{@code failed}），end 仍恰 1 条。 */
	@Test
	void resumeExecutionFailureSettlesOnceWithTheRealOutcome() {
		this.backendTool.failure = new IllegalStateException("作业冲突：已有同任务在执行");
		parkConfirmCard();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);

		List<String> ends = lines("tool_call_end");
		assertThat(ends).hasSize(1);
		assertThat(ends.get(0)).contains("tool=start_import").contains("ok=false").contains("outcome=failed")
			.contains("code=TOOL_EXECUTION_FAILED").contains("executed=true");
	}

	/**
	 * <b>M-2′（拒绝面）</b>：park 开启时用户点<b>拒绝</b> ⇒ 该 toolCallId <b>恰 1 条</b>
	 * {@code tool_call_end}（{@code outcome=rejected, executed=false, skipped=false}）。
	 *
	 * <p>缺陷形态：park 时整批 {@code tool_start} 已前移公布，而首张确认卡在 {@code await} 处
	 * 直接穿出批循环（{@code RunParkedException}）—— 循环末尾那条 {@code emitOnce} 永远不会执行。
	 * 用户拒绝落在另一个请求/实例上，{@code doSubmitDecision} 改条目 + 发回执帧 + 派续跑，
	 * 唯独不结算 ⇒ 日志里留下"有 start、没有 end"的孤儿调用（与进程死在 start 与 end 之间
	 * 不可区分），{@code ai:observed} 的对应 field 也永远空着。
	 *
	 * <p>为什么批准不在这里断言：放行后由续跑认领（{@code claimExecution}）的胜者结算
	 * （见 {@link #resumeExecutionSettlesBackendToolExactlyOnce}）—— 决策侧补会把同一调用发两次。
	 */
	@Test
	void parkedConfirmRejectSettlesExactlyOneEnd() {
		parkConfirmCard();

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, CALL_ID, false, "不要动这张表");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		List<String> ends = lines("tool_call_end");
		assertThat(ends).as("park 的拒绝路径同样要有结算行（start 不留孤儿）").hasSize(1);
		assertThat(ends.get(0)).contains("toolCallId=" + CALL_ID)
			.contains("tool=start_import")
			.contains("kind=CONFIRM")
			.contains("ok=false")
			.contains("outcome=rejected")
			.contains("executed=false")
			.contains("skipped=false");

		// 重复拒绝（第二个页签/重发）：DUPLICATE 不得再发一条
		assertThat(this.gate.submitDecision(RUN_ID, CALL_ID, false, "重复").outcome())
			.isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(lines("tool_call_end")).as("重复拒绝不得变成两条 end").hasSize(1);
	}

	/**
	 * <b>非 park 的反面钉子（拒绝）</b>：park 关闭时等待线程在场，拒绝后内联循环接着走到自己的
	 * {@code emitOnce} ⇒ 仍<b>恰 1 条</b>。决策侧不得参与（否则 2 条）。
	 */
	@Test
	void inlineRejectStillSettlesExactlyOnce() throws Exception {
		this.properties.getPark().setEnabled(false);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> driving = worker.submit(() -> this.manager.executeToolCalls(prompt(), response("start_import")));
			awaitPending();
			assertThat(this.gate.submitDecision(RUN_ID, CALL_ID, false, "不要动这张表").outcome())
				.isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			driving.get(20, TimeUnit.SECONDS);
		}
		finally {
			worker.shutdownNow();
		}

		List<String> ends = lines("tool_call_end");
		assertThat(ends).as("park 关闭时结算权仍只在等待线程手上").hasSize(1);
		assertThat(ends.get(0)).contains("outcome=rejected").contains("executed=false");
		assertThat(this.backendTool.invocations.get()).as("拒绝不执行工具").isZero();
	}

	/**
	 * <b>非 park 行为不变的钉子</b>：等待线程在场（{@code woke=true}）时由内联循环发那一行 ——
	 * 决策写方不得再补一条（否则同一 toolCallId 两条 end、{@code readCalls} 记两次）。
	 */
	@Test
	void inlineWaitStillSettlesExactlyOnce() throws Exception {
		this.properties.getPark().setEnabled(false);
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(5));
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> driving = worker.submit(() -> this.manager.executeToolCalls(prompt(), response("aggregate_sheet")));
			awaitPending();
			assertThat(submitResult("ok", AGG_META).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			driving.get(20, TimeUnit.SECONDS);
		}
		finally {
			worker.shutdownNow();
		}

		assertThat(this.audit.metrics()).as("内联路径的审计面与改造前逐字一致").containsEntry("readCalls", 1L);
		assertThat(lines("tool_call_end")).as("内联循环自发一行，决策侧不发").hasSize(1);
	}

	/**
	 * <b>跨实例的非 park 部署</b>：决策落在没有等待方的那个实例上（{@code signal} 自然 miss），
	 * 但"等人"的线程在另一个实例里活着 —— 结算权仍归它（下一拍轮询到已决即自行结算）。
	 * 决策侧不得把 {@code woke=false} 当成"没人会结算"就抢先记一笔，否则同一次调用两条 end。
	 *
	 * <p>边界（如实登记）：非 park 且<b>全集群都没有等待方</b>（进程死在等待中、尚未续跑）时，
	 * 这一行仍会缺席 —— 与 park 缺陷之前的存量口径一致，本批不扩大到"跨实例认领结算权"。
	 */
	@Test
	void nonParkDecisionFromAnInstanceWithoutTheWaiterDoesNotStealTheSettlement() {
		this.properties.getPark().setEnabled(false);
		seedPendingFrontendCard();

		ConfirmGate.Submission submission = submitResult("ok", AGG_META);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(submission.woke()).isFalse();
		assertThat(this.audit.metrics()).as("非 park：结算权在等待线程手上").containsEntry("readCalls", 0L);
		assertThat(lines("tool_call_end")).as("决策侧不抢非 park 的结算权").isEmpty();
	}

	@Test
	void parkedFrontendTimeoutEmitsExactlyOneSettlement() {
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(1));
		parkCard("navigate_to");

		org.assertj.core.api.Assertions.assertThatCode(() -> awaitStatus(CALL_ID, PendingToolCall.TIMEOUT))
			.doesNotThrowAnyException();

		assertThat(lines("tool_call_end")).singleElement().satisfies(line -> assertThat(line)
			.contains("toolCallId=" + CALL_ID).contains("outcome=timeout").contains("code=FRONTEND_TIMEOUT"));
	}

	@Test
	void everyPublishedFrontendCardGetsItsExpiryAndSettlement() throws Exception {
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(1));
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(),
				response("navigate_to", CALL_ID, "open_export_file_editor", "call-park-audit-2")))
			.isInstanceOf(RunParkedException.class);

		awaitStatus(CALL_ID, PendingToolCall.TIMEOUT);
		awaitStatus("call-park-audit-2", PendingToolCall.TIMEOUT);

		assertThat(lines("tool_call_end")).hasSize(2);
		assertThat(lines("tool_call_end")).anySatisfy(line -> assertThat(line).contains("toolCallId=" + CALL_ID));
		assertThat(lines("tool_call_end")).anySatisfy(line -> assertThat(line).contains("toolCallId=call-park-audit-2"));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void awaitStatus(String toolCallId, String status) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			PendingToolCall pending = this.store.pending(RUN_ID, toolCallId);
			if (pending != null && status.equals(pending.getStatus())) {
				return;
			}
			Thread.sleep(50L);
		}
		throw new AssertionError("条目未按期收敛 toolCallId=" + toolCallId + " status="
				+ this.store.pending(RUN_ID, toolCallId).getStatus());
	}

	/** 直接铺一张"已公布、仍待回灌"的前端卡（用于不经过首轮的场景）。 */
	private void seedPendingFrontendCard() {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("aggregate_sheet");
		pending.setType("function");
		pending.setArguments("{\"defCode\":\"CURRENCY\"}");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		this.store.putPending(RUN_ID, pending);
	}

	/** 首轮 park 一张前端卡（线程交还：请求帧已投递、无人等待）。 */
	private void parkCard(String tool) {
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(), response(tool)))
			.as("park 是控制流：请求帧公布后线程交还")
			.isInstanceOf(RunParkedException.class);
		assertThat(this.pending().getStatus()).as("复现前提：条目仍 PENDING（等人/等前端）")
			.isEqualTo(PendingToolCall.PENDING);
		assertThat(this.pending().getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
	}

	/** 首轮 park 一张确认卡（{@code BACKEND} + {@code DANGER} ⇒ 进确认门）。 */
	private void parkConfirmCard() {
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(), response("start_import")))
			.isInstanceOf(RunParkedException.class);
		assertThat(this.pending().getKind()).isEqualTo(PendingToolCall.KIND_CONFIRM);
	}

	private ConfirmGate.Submission submitResult(String outcome, Map<String, Object> meta) {
		return this.gate.submitFrontendResult(RUN_ID, CALL_ID, RESULT_TEXT, "aie2e", false, outcome, meta, null);
	}

	private PendingToolCall pending() {
		return this.store.pending(RUN_ID, CALL_ID);
	}

	/** 等待首轮线程真的进到"等人"（条目已落库仍 PENDING）。 */
	private void awaitPending() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			PendingToolCall pending = this.pending();
			if (pending != null && PendingToolCall.PENDING.equals(pending.getStatus())) {
				return;
			}
			Thread.sleep(50L);
		}
		throw new AssertionError("首轮没有进入挂起等待（条目未落库）");
	}

	private Prompt prompt() {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.backendTool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("这张表按小数位数各有多少种货币？")), options);
	}

	private static ChatResponse response(String tool) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, "{\"defCode\":\"CURRENCY\"}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static ChatResponse response(String firstTool, String firstId, String secondTool, String secondId) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(
					new AssistantMessage.ToolCall(firstId, "function", firstTool, "{\"defCode\":\"CURRENCY\"}"),
					new AssistantMessage.ToolCall(secondId, "function", secondTool, "{\"defCode\":\"CURRENCY\"}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	/** 捕获的结构化事件行（{@code event=<name> ...}；按行首匹配，避免前缀误命中）。 */
	private List<String> lines(String event) {
		List<String> out = new ArrayList<>();
		for (ILoggingEvent item : this.appender.list) {
			String message = item.getFormattedMessage();
			if (message.startsWith("event=" + event + " ")) {
				out.add(message);
			}
		}
		return out;
	}

}
