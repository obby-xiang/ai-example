package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 快照缺失时的三条硬口径（DANGER 不得直执行 / 报告指纹 / kind 缺失）：
 *
 * <ol>
 * <li><b>可恢复</b>：本轮权威执行信封（{@link ToolExecutionEnvelope}）由本实例创建 ⇒ 恢复快照，
 *     照常预检 → {@code suspended}/pending → {@code confirm_request}，批准后才执行；round/步数/预算原值
 *     不被清零；</li>
 * <li><b>不可恢复</b>：没有信封 ⇒ 危险/前端工具拒绝（{@code blocked}/TOOL_KIND_MISMATCH，零执行），
 *     READ+BACKEND 仍放行；</li>
 * <li><b>执行前复核</b>：确认门条目的 {@code reportHash} 与当前预检不一致 ⇒ 不执行、要求重新确认；
 *     {@code kind=null} ⇒ 既不批准也不执行。</li>
 * </ol>
 */
class SpToolCallingManagerRecoveryTest {

	private static final String RUN_ID = "run-recovery";

	private static final String SESSION_ID = "s-recovery";

	private static final String CALL_ID = "call-recovery-1";

	private static final String STALE_CALL_ID = "call-recovery-stale";

	private static final String UNKNOWN_CALL_ID = "call-recovery-unknown";

	private static final String UNKNOWN_APPROVED_CALL_ID = "call-recovery-unknown-approved";

	private static final String REPORT_HASH = "3a9f2b1c";

	/** 固定创建时刻（原快照的事实；恢复后必须仍是它，不能是当前钟）。 */
	private static final long CREATED_AT_MS = 1_750_000_000_000L;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of());

	private final ToolPreflightService preflight = mock(ToolPreflightService.class);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
			List.of(), this.preflight);

	private final FakeStartImportTool startImport = new FakeStartImportTool();

	private final FakeReadTool readTool = new FakeReadTool();

	private ExecutorService decisionThread;

	@BeforeEach
	void setUp() {
		this.decisionThread = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "recovery-decision");
			thread.setDaemon(true);
			return thread;
		});
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.riskOf("read_tasks_probe")).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.categoryOf("start_import")).thenReturn(ToolMeta.Category.J);
		when(this.toolRegistry.categoryOf("read_tasks_probe")).thenReturn(ToolMeta.Category.R);
		when(this.preflight.inspect(eq("start_import"), anyString(), any(AiContext.class))).thenReturn(passingReport());
	}

	@AfterEach
	void tearDown() {
		this.decisionThread.shutdownNow();
	}

	// ── ① 快照缺失但信封可恢复：卡能出、批准能续跑 ────────────────────────────

	@Test
	void missingSnapshotIsRecoveredFromEnvelopeAndDangerToolWaitsForApproval() throws Exception {
		RunSnapshot seeded = seedSnapshot();
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(seeded, 41L);
		assertThat(envelope).isNotNull();
		assertThat(envelope.round()).isEqualTo(2);
		assertThat(envelope.toolSteps()).isEqualTo(3);
		assertThat(envelope.budgetSkippedBatches()).isEqualTo(2);
		assertThat(envelope.seqFloor()).isEqualTo(41L);
		assertThat(envelope.createdAtMs()).isEqualTo(CREATED_AT_MS);
		assertThat(envelope.createdBy()).isEqualTo(this.store.instanceId());

		this.store.deleteRun(RUN_ID);
		assertThat(this.store.get(RUN_ID)).as("前置：快照确实缺失").isNull();

		// 不在这里发 start：恢复前出的帧会让 RunStoreFrameStore 的号锚先初始化成 1，
		// 抬号后已缓存的计数器不会回读 max(归档, ai:seq) —— 本用例要断言的是"恢复后所有新帧 > 41"。
		FrameWire wire = attach();
		AtomicInteger invocationsAtApproval = new AtomicInteger(-1);
		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			invocationsAtApproval.set(this.startImport.invocations.get());
			return this.gate.submitDecision(RUN_ID, CALL_ID, true, "用户在确认卡片上放行");
		});

		ToolExecutionResult result = this.manager.executeToolCalls(promptWithEnvelope(envelope),
				response(toolCall(CALL_ID, "start_import", "{\"taskId\":7}")));
		assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);

		// 卡先出、批准前零执行；批准后续跑恰好执行一次
		assertThat(invocationsAtApproval.get()).as("批准前不得执行").isZero();
		assertThat(this.startImport.invocations.get()).isEqualTo(1);
		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsSubsequence("suspended", "confirm_request", "confirm_decision",
				"tool_result");
		assertThat(FrameContract.oneOfType(frames, "suspended")).isNotNull();

		// 序号下界：恢复后发出的每一帧都必须 > seqFloor（否则前端带 lastSeq 重挂会丢卡）
		assertThat(this.store.lastIssuedSeq(RUN_ID)).isGreaterThanOrEqualTo(41L);
		assertThat(frames).isNotEmpty().allSatisfy(frame -> {
			assertThat(frame.get("seq")).as("恢复后的帧必须带 seq").isInstanceOf(Number.class);
			assertThat(((Number) frame.get("seq")).longValue()).isGreaterThan(41L);
		});

		// 快照已恢复，且轮次/步数/预算/创建时刻原值带回（步数 = 3 + 本次 1；round = 2 + 落定 1）
		RunSnapshot restored = this.store.get(RUN_ID);
		assertThat(restored).isNotNull();
		assertThat(restored.getCreatedBy()).isEqualTo(this.store.instanceId());
		assertThat(restored.getCreatedAtMs()).as("创建时刻是本轮事实，恢复不得改成当前钟").isEqualTo(CREATED_AT_MS);
		assertThat(restored.getToolSteps()).isEqualTo(4);
		assertThat(restored.getRound()).isEqualTo(3);
		assertThat(restored.getBudgetSkippedBatches()).isEqualTo(2);
		assertThat(restored.getStatus()).isEqualTo(RunSnapshot.RUNNING);

		PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
		// 确认门条目的既有口径：执行后状态停留 APPROVED（executeOnce 只对 BACKEND 写 EXECUTED），
		// 证据在 executed/outcome/台账三处。
		assertThat(pending.getStatus()).isEqualTo(PendingToolCall.APPROVED);
		assertThat(pending.isExecuted()).isTrue();
		assertThat(pending.effectiveOutcome()).isEqualTo(ToolOutcome.OK);
		assertThat(pending.getExecutedBy()).isEqualTo(this.store.instanceId());
		assertThat(this.store.ledger(RUN_ID)).anySatisfy(record -> {
			assertThat(record).containsEntry("toolCallId", CALL_ID);
			assertThat(record).containsEntry("outcome", ToolOutcome.OK.wire());
			assertThat(record).containsEntry("preflightHash", REPORT_HASH);
		});
		assertThat(result.conversationHistory()).isNotEmpty();
	}

	// ── ② 没有可信信封：危险工具拒绝，READ+BACKEND 放行 ───────────────────────

	@Test
	void missingSnapshotWithoutEnvelopeRejectsDangerButPassesReadBackend() {
		assertThat(this.store.get(RUN_ID)).isNull();

		AssistantMessage.ToolCall danger = toolCall(CALL_ID, "start_import", "{\"taskId\":7}");
		AssistantMessage.ToolCall read = toolCall("call-recovery-read", "read_tasks_probe", "{\"filter\":\"\"}");
		ToolExecutionResult result = this.manager.executeToolCalls(promptWithTools(List.of("runId", "sessionId")),
				response(danger, read));

		assertThat(this.startImport.invocations.get()).as("危险工具不得直执行").isZero();
		assertThat(this.readTool.invocations.get()).as("READ+BACKEND 可放行").isEqualTo(1);
		assertThat(this.store.get(RUN_ID)).as("不因该路径凭空造快照").isNull();

		List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> responses = toolResponses(result);
		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).id()).isEqualTo(CALL_ID);
		assertThat(responses.get(0).responseData()).contains(ToolOutcome.CODE_TOOL_KIND_MISMATCH)
			.contains("未执行");
		assertThat(responses.get(1).id()).isEqualTo("call-recovery-read");
		assertThat(responses.get(1).responseData()).contains("没有进行中的任务");

		AssistantMessage assistant = result.conversationHistory()
			.stream()
			.filter(AssistantMessage.class::isInstance)
			.map(AssistantMessage.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(assistant.getToolCalls()).as("历史保留完整 assistant(tool_calls)").hasSize(2);
	}

	// ── ③ 执行前复核：报告指纹不一致 / kind 缺失 ──────────────────────────────

	@Test
	void staleApprovedReportIsNotExecutedAndKindNullCannotBeApprovedOrExecuted() {
		seedSnapshot();

		PendingToolCall stale = pending(STALE_CALL_ID, PendingToolCall.KIND_CONFIRM, PendingToolCall.APPROVED);
		Map<String, Object> cardReport = new LinkedHashMap<>();
		cardReport.put("reportHash", "old-report-hash");
		stale.setPreflight(cardReport);
		this.store.putPending(RUN_ID, stale);

		String staleText = this.manager.resolvePending(RUN_ID, promptWithTools(List.of("runId")), stale);

		assertThat(this.startImport.invocations.get()).as("报告指纹不一致绝不执行").isZero();
		assertThat(staleText).contains(ErrorCodes.PRECHECK_STALE).contains("未执行");
		PendingToolCall afterStale = this.store.pending(RUN_ID, STALE_CALL_ID);
		assertThat(afterStale.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
		assertThat(afterStale.getCode()).isEqualTo(ErrorCodes.PRECHECK_STALE);
		assertThat(afterStale.isExecuted()).isFalse();

		// kind=null：批准入口直接拒（条目保持 PENDING）
		PendingToolCall unknown = pending(UNKNOWN_CALL_ID, null, PendingToolCall.PENDING);
		this.store.putPending(RUN_ID, unknown);
		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, UNKNOWN_CALL_ID, true, null);
		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.TOOL_KIND_MISMATCH);
		assertThat(this.store.pending(RUN_ID, UNKNOWN_CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);

		// kind=null + 历史 APPROVED：续跑也不执行
		PendingToolCall unknownApproved = pending(UNKNOWN_APPROVED_CALL_ID, null, PendingToolCall.APPROVED);
		this.store.putPending(RUN_ID, unknownApproved);
		String unknownText = this.manager.resolvePending(RUN_ID, promptWithTools(List.of("runId")),
				unknownApproved);
		assertThat(unknownText).contains(ToolOutcome.CODE_TOOL_KIND_MISMATCH).contains("kind=null");
		assertThat(this.store.pending(RUN_ID, UNKNOWN_APPROVED_CALL_ID).getStatus())
			.isEqualTo(PendingToolCall.BLOCKED);
		assertThat(this.startImport.invocations.get()).as("kind 缺失绝不执行").isZero();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private RunSnapshot seedSnapshot() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setCreatedBy(this.store.instanceId());
		snapshot.setCreatedAtMs(CREATED_AT_MS);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
		snapshot.setRound(2);
		snapshot.setToolSteps(3);
		snapshot.setBudgetSkippedBatches(2);
		this.store.save(snapshot);
		return snapshot;
	}

	private FrameWire attach() {
		com.example.configmgr.ai.run.SseChatEmitter out = this.registry.of(RUN_ID);
		// 刻意不发 start：本用例要求"快照缺失 → 恢复抬号 → 后续所有帧 > seqFloor"，
		// 恢复前发帧会先把 RunStoreFrameStore 的号锚固化成 1（见用例内注释）。
		return FrameWire.attachTo(out);
	}

	/**
	 * 客户端眼里的完整帧流 = 连接收到的帧 ∪ 归档帧（按 seq 归并去重）。确认卡请求送达后服务端
	 * 主动收流，批准之后的 {@code confirm_decision}/{@code tool_result} 只进归档（重挂时补发）。
	 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		java.util.Map<Long, Map<String, Object>> bySeq = new java.util.TreeMap<>();
		for (Map<String, Object> frame : wire.frames()) {
			if (frame.get("seq") instanceof Number number) {
				bySeq.putIfAbsent(number.longValue(), frame);
			}
		}
		for (Map<String, Object> frame : FrameWire.normalize(this.store.events(RUN_ID))) {
			if (frame.get("seq") instanceof Number number) {
				bySeq.putIfAbsent(number.longValue(), frame);
			}
		}
		return new ArrayList<>(bySeq.values());
	}

	private Prompt promptWithEnvelope(ToolExecutionEnvelope envelope) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.startImport)
			.build()
			.getToolCallbacks());
		Map<String, Object> toolContext = new LinkedHashMap<>();
		toolContext.put("runId", RUN_ID);
		toolContext.put("sessionId", SESSION_ID);
		toolContext.put(ToolExecutionEnvelope.TOOL_CONTEXT_KEY, envelope);
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(toolContext)
			.build();
		return new Prompt(List.of(new UserMessage("请执行 start_import")), options);
	}

	/** 只有键、没有信封的 toolContext（不可恢复路径）。 */
	private Prompt promptWithTools(List<String> contextKeys) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.startImport, this.readTool)
			.build()
			.getToolCallbacks());
		Map<String, Object> toolContext = new LinkedHashMap<>();
		for (String key : contextKeys) {
			toolContext.put(key, "sessionId".equals(key) ? SESSION_ID : RUN_ID);
		}
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(toolContext)
			.build();
		return new Prompt(List.of(new UserMessage("请执行工具")), options);
	}

	private static AssistantMessage.ToolCall toolCall(String id, String name, String arguments) {
		return new AssistantMessage.ToolCall(id, "function", name, arguments);
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static PendingToolCall pending(String callId, String kind, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(callId);
		pending.setName("start_import");
		pending.setType("function");
		pending.setArguments("{\"taskId\":7}");
		pending.setKind(kind);
		pending.setStatus(status);
		return pending;
	}

	private static List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> toolResponses(
			ToolExecutionResult result) {
		List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> out = new ArrayList<>();
		for (org.springframework.ai.chat.messages.Message message : result.conversationHistory()) {
			if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage toolMessage) {
				out.addAll(toolMessage.getResponses());
			}
		}
		return out;
	}

	private static ToolPreflightService.PreflightReport passingReport() {
		return new ToolPreflightService.PreflightReport(true, Map.of("taskId", 7L, "title", "订单配置导入"),
				List.of(new ToolPreflightService.PreflightReport.Check("任务存在", true, "")),
				"将发布 1 个配置定义 · 影响 12,480 行数据", REPORT_HASH);
	}

	/** 纯读替身：证明不可恢复路径下 READ+BACKEND 仍可执行。 */
	static class FakeReadTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "read_tasks_probe", description = "受控替身：列出任务（纯读）")
		public String listTasks(@org.springframework.ai.tool.annotation.ToolParam(description = "筛选") String filter) {
			this.invocations.incrementAndGet();
			return "没有进行中的任务";
		}

	}

}
