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
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F5（预检前移）的端到端用例：预检不通过 → 不进确认门、不弹卡；通过 → 报告随帧下发、批准绑定指纹。
 *
 * <p>用<b>真实</b> {@link ConfirmGate} + {@link SseChatEmitter} + 进程内 store，只把预检器换桩 ——
 * 断言的对象是帧流与台账，而不是"某方法被调用过"。
 *
 * <ol>
 * <li>预检失败：帧流里没有 {@code suspended}/{@code confirm_request}，只有一条 {@code tool_result}
 *     （{@code code=PRECHECK_FAILED}、{@code retryable=true}），闸门零预登记，工具零执行；</li>
 * <li>预检通过：{@code suspended.toolCalls[].preflight} 与 {@code confirm_request.preflight} 带同一份报告，
 *     批准后 {@code confirm_decision.preflightHash} 与执行台账的 {@code preflightHash} 都等于 {@code reportHash}；</li>
 * <li>F1 兼容：预检失败（retryable）<b>不触发</b>首错即停 —— 同批后续调用照常执行。</li>
 * </ol>
 */
class ToolPreflightGateTest {

	private static final String RUN_ID = "run-preflight";

	private static final String SESSION_ID = "s-preflight";

	private static final String CALL_ID = "call-preflight-1";

	private static final String REPORT_HASH = "3a9f2b1c";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of());

	private final ToolPreflightService preflight = mock(ToolPreflightService.class);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
			List.of(), this.preflight);

	private final FakeStartImportTool startImport = new FakeStartImportTool();

	private final FakeStatusTool statusTool = new FakeStatusTool();

	private ExecutorService decisionThread;

	@BeforeEach
	void setUp() {
		this.decisionThread = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "preflight-decision");
			thread.setDaemon(true);
			return thread;
		});
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.riskOf("check_job_status")).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.categoryOf("start_import")).thenReturn(ToolMeta.Category.J);
		when(this.toolRegistry.categoryOf("check_job_status")).thenReturn(ToolMeta.Category.R);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.decisionThread.shutdownNow();
	}

	// ── ① 预检不通过：不进确认门 ────────────────────────────────────────────

	@Test
	void preflightFailureNeverEntersConfirmGateAndEmitsNoCard() {
		when(this.preflight.inspect(eq("start_import"), anyString(), any(AiContext.class))).thenReturn(failingReport());
		FrameWire wire = attach();

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		finish();

		List<Map<String, Object>> frames = wire.frames();
		// 没有 suspended（不挂起）、没有 confirm_request（不弹卡）：只有一张"未执行"的失败工具卡
		assertThat(FrameContract.types(frames)).containsExactly("start", "tool_start", "tool_result", "done");
		FrameContract.assertConformant(frames);

		Map<String, Object> frame = FrameContract.oneOfType(frames, "tool_result");
		assertThat(frame).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.FAILED.wire())
			.containsEntry("code", ToolOutcome.CODE_PRECHECK_FAILED)
			.containsEntry("retryable", true)
			.containsEntry("status", PendingToolCall.PRECHECK_FAILED)
			.containsEntry("executed", false);
		assertThat(String.valueOf(frame.get("result"))).contains("预检未通过").contains("无在途作业");

		// 闸门零残留：没有 arm、没有进入等待
		assertThat(this.gate.hasActiveLatch(RUN_ID)).isFalse();
		assertThat(this.gate.pendingGates()).isZero();
		assertThat(this.startImport.invocations.get()).as("预检不通过绝不执行").isZero();

		// 条目已落库为终态（续跑会复用它、不会再执行），且带上那份报告
		PendingToolCall stored = this.store.pending(RUN_ID, CALL_ID);
		assertThat(stored.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
		assertThat(stored.getCode()).isEqualTo(ToolOutcome.CODE_PRECHECK_FAILED);
		assertThat(stored.getPreflight()).containsEntry("reportHash", REPORT_HASH);

		// 模型侧信封：outcome=failed / code=PRECHECK_FAILED / retryable=true
		ToolResultEnvelope envelope = envelope(result);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_PRECHECK_FAILED);
		assertThat(envelope.retryable()).isTrue();
		assertThat(envelope.message()).contains("预检未通过").contains("请按上述原因修正后重试");
	}

	// ── ② 预检通过：报告随帧下发 + 批准绑定指纹 ────────────────────────────

	@Test
	void preflightPassCarriesReportAndBindsHashOnApproval() throws Exception {
		when(this.preflight.inspect(eq("start_import"), anyString(), any(AiContext.class))).thenReturn(passingReport());
		FrameWire wire = attach();
		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			return this.gate.submitDecision(RUN_ID, CALL_ID, true, "用户在确认卡片上放行");
		});

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		decision.get(10, TimeUnit.SECONDS);
		finish();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start",
				"confirm_request", "confirm_decision", "tool_result", "done");
		FrameContract.assertConformant(frames);

		// suspended.toolCalls[].preflight 是那份报告（契约新增可选字段）
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) FrameContract
			.oneOfType(frames, "suspended").get("toolCalls");
		assertThat((Map<String, Object>) toolCalls.get(0).get("preflight"))
			.containsEntry("reportHash", REPORT_HASH)
			.containsEntry("impact", "将发布 1 个配置定义 · 影响 12,480 行数据");

		// confirm_request.preflight 同一份
		assertThat((Map<String, Object>) FrameContract.oneOfType(frames, "confirm_request").get("preflight"))
			.containsEntry("reportHash", REPORT_HASH);

		// 批准绑定：决策回执与执行台账都记下同一指纹
		assertThat(FrameContract.oneOfType(frames, "confirm_decision")).containsEntry("preflightHash", REPORT_HASH);
		Map<String, Object> ledger = this.store.ledger(RUN_ID).stream()
			.filter(record -> CALL_ID.equals(record.get("toolCallId")))
			.findFirst()
			.orElseThrow();
		assertThat(ledger).containsEntry("preflightHash", REPORT_HASH)
			.containsEntry("outcome", ToolOutcome.OK.wire());
		assertThat(this.startImport.invocations.get()).isEqualTo(1);

		assertThat(envelope(result).code()).isNull();
	}

	// ── ③ F1 兼容：预检失败不停批 ──────────────────────────────────────────

	@Test
	void preflightFailureDoesNotHaltBatch() {
		when(this.preflight.inspect(eq("start_import"), anyString(), any(AiContext.class))).thenReturn(failingReport());
		FrameWire wire = attach();

		ToolExecutionResult result = this.manager.executeToolCalls(promptWithTools(),
				response(new AssistantMessage.ToolCall("call-preflight-1", "function", "start_import",
						"{\"taskId\":7}"),
						new AssistantMessage.ToolCall("call-preflight-2", "function", "check_job_status",
								"{\"jobId\":1}")));
		finish();

		List<Map<String, Object>> results = FrameContract.ofType(wire.frames(), "tool_result");
		assertThat(results).hasSize(2);
		assertThat(results).noneMatch(frame -> ToolOutcome.SKIPPED.wire().equals(frame.get("outcome")));
		assertThat(results.get(1)).containsEntry("outcome", ToolOutcome.OK.wire())
			.containsEntry("executed", true);
		assertThat(this.statusTool.invocations.get()).as("预检失败不触发首错即停").isEqualTo(1);
		// 两个 toolCallId 都有回填（批策略要求每个调用一个 ToolResponse）
		assertThat(toolResponses(result)).hasSize(2);
	}

	// ── ④ MINOR-1：越 scope 的 start_* 不预检，走既有防线③ ──────────────────

	@Test
	void outOfScopeStartToolSkipsPreflightAndBlocksAsScopeViolation() {
		when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(false);
		FrameWire wire = attach();

		this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		finish();

		// 预检一次都不跑：不读任务、不产出 PRECHECK_FAILED、不把目标标题带进预检/确认帧
		verifyNoInteractions(this.preflight);
		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).doesNotContain("confirm_request");
		Map<String, Object> frame = FrameContract.oneOfType(frames, "tool_result");
		assertThat(frame).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
			.containsEntry("code", SpToolCallingManager.SCOPE_VIOLATION_CODE)
			.containsEntry("status", PendingToolCall.BLOCKED)
			.containsEntry("executed", false);
		assertThat(this.startImport.invocations.get()).isZero();
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.BLOCKED);
	}

	// ── ⑤ MINOR-2：预检前后打被动活动锚点，但不计副作用次数 ────────────────

	@Test
	void preflightMarksPassiveActivityBeforeAndAfterWithoutStartingTool() {
		when(this.preflight.inspect(eq("start_import"), anyString(), any(AiContext.class))).thenReturn(failingReport());
		attach();

		this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		finish();

		InOrder order = inOrder(this.beacon, this.preflight);
		order.verify(this.beacon).markPassiveActivity(RUN_ID);
		order.verify(this.preflight).inspect(eq("start_import"), anyString(), any(AiContext.class));
		order.verify(this.beacon).markPassiveActivity(RUN_ID);
		// 被动锚点不得冒充"工具已开始"（否则会污染重试副作用判定）
		verify(this.beacon, never()).enter(anyString(), anyString(), anyString());
	}

	// ── ⑥ MINOR-4：预检基础设施异常 → PRECHECK_UNAVAILABLE 安全侧兜底 ──────

	@Test
	void preflightInfrastructureFailureFailsSafeWithoutEnteringGate() {
		when(this.preflight.inspect(eq("start_import"), anyString(), any(AiContext.class)))
			.thenThrow(new IllegalStateException("预检仓储暂时不可用"));
		FrameWire wire = attach();

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		finish();

		List<Map<String, Object>> frames = wire.frames();
		// 不进确认门、不弹卡、不执行：只有一张"未能预检"的失败工具卡
		assertThat(FrameContract.types(frames)).containsExactly("start", "tool_start", "tool_result", "done");
		FrameContract.assertConformant(frames);
		Map<String, Object> frame = FrameContract.oneOfType(frames, "tool_result");
		assertThat(frame).containsEntry("ok", false)
			.containsEntry("outcome", ToolOutcome.FAILED.wire())
			.containsEntry("code", ToolOutcome.CODE_PRECHECK_UNAVAILABLE)
			.containsEntry("retryable", true)
			.containsEntry("status", PendingToolCall.PRECHECK_FAILED)
			.containsEntry("executed", false);
		assertThat(String.valueOf(frame.get("result"))).contains("预检暂不可用")
			.contains("不代表条件已满足");
		assertThat(this.gate.hasActiveLatch(RUN_ID)).isFalse();
		assertThat(this.gate.pendingGates()).isZero();
		assertThat(this.startImport.invocations.get()).isZero();
		assertThat(this.store.pending(RUN_ID, CALL_ID).getCode())
			.isEqualTo(ToolOutcome.CODE_PRECHECK_UNAVAILABLE);

		ToolResultEnvelope envelope = envelope(result);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_PRECHECK_UNAVAILABLE);
		assertThat(envelope.retryable()).isTrue();
	}

	// ── ⑦ AR-03：单轮第二次 create_task 直接拒绝（不弹第二张卡、不执行） ──────────

	@Test
	void secondCreateTaskInSameTurnIsRejectedBeforeCard() {
		seedExecutedCreateTask("call-created", "已创建导出任务 #99（标题：季度导出）。创建不等于执行：尚未启动任何作业。");
		when(this.toolRegistry.riskOf("create_task")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.categoryOf("create_task")).thenReturn(ToolMeta.Category.W);
		when(this.toolRegistry.channelOf("create_task")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
			.thenReturn(createPassingReport());
		FrameWire wire = attach();
		FakeCreateTaskTool createTool = new FakeCreateTaskTool();

		ToolExecutionResult result = this.manager.executeToolCalls(
				prompt(createTool, "create_task", "{\"type\":\"EXPORT\"}"),
				response("create_task", "{\"type\":\"EXPORT\"}"));
		finish();

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).doesNotContain("suspended", "confirm_request");
		Map<String, Object> frame = FrameContract.oneOfType(frames, "tool_result");
		assertThat(frame).containsEntry("code", ToolOutcome.CODE_CREATE_TASK_TURN_LIMIT)
			.containsEntry("status", PendingToolCall.SKIPPED)
			.containsEntry("executed", false);
		assertThat(String.valueOf(frame.get("result"))).contains("任务 #99").contains("同一轮只允许创建一次任务");
		assertThat(createTool.invocations.get()).as("被上限挡下的调用绝不执行").isZero();
		assertThat(this.gate.pendingGates()).isZero();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private void seedExecutedCreateTask(String toolCallId, String resultText) {
		PendingToolCall executed = new PendingToolCall();
		executed.setToolCallId(toolCallId);
		executed.setName("create_task");
		executed.setKind(PendingToolCall.KIND_CONFIRM);
		executed.setStatus(PendingToolCall.EXECUTED);
		executed.setOutcome(ToolOutcome.OK.wire());
		executed.setExecuted(true);
		this.store.appendLedger(RUN_ID, executed, resultText);
	}

	private static ToolPreflightService.PreflightReport createPassingReport() {
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("type", "EXPORT");
		target.put("title", null);
		return new ToolPreflightService.PreflightReport(true, target, List.of(
				new ToolPreflightService.PreflightReport.Check("创建类型合法", true, "")),
				"将创建 EXPORT 任务（创建≠执行，不启动作业）", "无筛选条件（创建任务）", null, "c0ffee01");
	}

	private Prompt prompt(Object toolObject, String tool, String args) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(toolObject)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请执行 " + tool)), options);
	}

	private FrameWire attach() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);
		return wire;
	}

	/**
	 * B1（2026-10-20 挂起即断）：客户端眼里的完整帧流 = 连接收到的帧 ∪ 归档帧（按 seq 归并去重）。
	 *
	 * <p>确认卡请求帧送达后服务端主动收流，决策之后的 {@code confirm_decision} / {@code tool_result}
	 * 只进归档；前端在决策后带 {@code ?lastSeq} 重连把它补回来 —— 本方法等价复刻这一视角。
	 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		Map<Long, Map<String, Object>> bySeq = new TreeMap<>();
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

	private void finish() {
		this.registry.of(RUN_ID).done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
	}

	private static ToolPreflightService.PreflightReport passingReport() {
		return new ToolPreflightService.PreflightReport(true, target(), checks(true), "将发布 1 个配置定义 · 影响 12,480 行数据",
				REPORT_HASH);
	}

	private static ToolPreflightService.PreflightReport failingReport() {
		return new ToolPreflightService.PreflightReport(false, target(), checks(false), "影响面暂不可估", REPORT_HASH);
	}

	private static Map<String, Object> target() {
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("taskId", 7L);
		target.put("title", "订单配置导入");
		target.put("type", "IMPORT");
		target.put("status", "IN_PROGRESS");
		return target;
	}

	private static List<ToolPreflightService.PreflightReport.Check> checks(boolean pass) {
		return List.of(new ToolPreflightService.PreflightReport.Check("任务存在", true, ""),
				new ToolPreflightService.PreflightReport.Check("类型匹配", true, ""),
				new ToolPreflightService.PreflightReport.Check("无在途作业", pass, pass ? ""
						: "任务 #7 已有同类作业在途：作业 #4221 [PUBLISH] 状态 RUNNING"),
				new ToolPreflightService.PreflightReport.Check("预检指纹新鲜", true, "3a9f…"));
	}

	private Prompt promptWithTools() {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.startImport, this.statusTool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请执行批量工具")), options);
	}

	private Prompt prompt(String tool, String args) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.startImport)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请执行 " + tool)), options);
	}

	private static ChatResponse response(String tool, String args) {
		return response(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args));
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static List<ToolResponseMessage.ToolResponse> toolResponses(ToolExecutionResult result) {
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.toList();
	}

	private static ToolResultEnvelope envelope(ToolExecutionResult result) {
		return ToolResultEnvelope.fromJson(toolResponses(result).get(0).responseData());
	}

	/** create_task 的真实回调替身（证明"被单轮上限挡下时绝不执行"）。 */
	static class FakeCreateTaskTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "create_task", description = "受控替身：创建任务")
		public String createTask(@ToolParam(description = "任务类型") String type) {
			this.invocations.incrementAndGet();
			return "已创建任务 #1";
		}

	}

	/** 同批第二调用的真实回调（证明"预检失败不停批"）。 */
	static class FakeStatusTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(name = "check_job_status", description = "受控替身：查作业状态")
		public String checkJobStatus(@ToolParam(description = "作业ID") Long jobId) {
			this.invocations.incrementAndGet();
			return "作业 #" + jobId + " 状态:RUNNING";
		}

	}

}
