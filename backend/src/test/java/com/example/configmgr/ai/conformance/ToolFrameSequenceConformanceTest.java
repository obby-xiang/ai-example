package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeStartImportTool;
import com.example.configmgr.ai.gate.SpToolCallingManager;
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
import com.example.configmgr.common.ConflictException;
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
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 不变量 <b>③ 工具配对 / ⑥ 前端通道挂起→回灌→续跑 / ⑦ 确认门两路</b>的一致性用例。
 *
 * <p>
 * 这一层跑的是<b>真实</b>的 {@link SpToolCallingManager}（官方循环里的工具扩展点）+ 真实的
 * {@link ConfirmGate} 状态机 + 真实的 {@link SseChatEmitter}，只把工具元数据（{@link ToolRegistry}）、
 * 外置状态（{@link InMemoryRunStore}）与取消标志做成替身；被测对象是<b>帧序本身</b>：
 * <ol>
 * <li>{@code tool_start} 必有配对结局帧（后端执行 {@code tool_result}、确认门拒绝 {@code tool_result}、
 * 前端通道 {@code frontend_tool_result}），且每个工具调用<b>恰好一个</b>结局帧（多一个 = 重复执行）；</li>
 * <li>确认门两路（放行/拒绝）：{@code confirm_request} → {@code confirm_decision} → {@code tool_result}，
 * 放行才认领+记台账（恰好执行一次），拒绝既不认领也不执行；</li>
 * <li>前端通道：{@code tool_start} → {@code frontend_tool_request} →（回灌）{@code frontend_tool_result}
 * 且 {@code executed=false}；回灌后<b>重放同一 toolCallId 不产生第二帧</b>（幂等，绝不二次执行）；</li>
 * <li>{@code suspended}（挂起态已外置的公告）必须先于 {@code tool_start}（硬规范①在帧序上的投影）。</li>
 * </ol>
 *
 * <p>
 * 挂起是阻塞的，而决策来自"另一条线程"（生产里是 HTTP 线程）—— 本类用一条决策线程 +
 * {@link FrameWire#awaitType} 把两个线程的帧序确定化，不靠 sleep 碰运气。
 *
 * <h2>为什么现在可以对挂起轮直接 {@code assertConformant}</h2>
 * {@code S5c-6} 修复后，{@code ConfirmGate} 的次序固定为"落库 → 发回执帧 → 唤醒"且三步同锁，
 * 等待方读待决状态的轮询也读同一把锁 —— 于是"回执帧"一定先于它唤起的后果帧，
 * 回执也不可能落到 {@code done} 之后。原先本类放行的两类竞态违规
 * （{@code tool_result} 早于 {@code confirm_decision}、到达顺序非严格递增）已不再可能，
 * 因此挂起轮的帧流一律跑满六条不变量，不再有"放行名单"。
 */
class ToolFrameSequenceConformanceTest {

	private static final String RUN_ID = "run-tool-frames";

	private static final String SESSION_ID = "s-tool-frames";

	private static final String CALL_ID = "call-tool-frames-1";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of());

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties),
			List.of());

	private final FakeStartImportTool fakeStartImport = new FakeStartImportTool();

	private ExecutorService decisionThread;

	@BeforeEach
	void setUp() {
		this.decisionThread = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "conformance-decision");
			thread.setDaemon(true);
			return thread;
		});
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 7L, null).toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.decisionThread.shutdownNow();
	}

	// ── ⑦ 确认门：放行 ──────────────────────────────────────────────────────

	@Test
	void approvedConfirmPathEmitsRequestThenDecisionThenResultAndExecutesExactlyOnce() throws Exception {
		stubConfirmTool("start_import");
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			return this.gate.submitDecision(RUN_ID, CALL_ID, true, "用户在确认卡片上放行");
		});

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		ConfirmGate.Submission submission = decision.get(10, TimeUnit.SECONDS);
		// 工具落定后由服务层收尾（与 ResilientChatService.finish 同序）
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		List<String> types = FrameContract.types(frames);
		// 全序确定性（S5c-6 修复后）：回执帧一定先于它唤起的后果帧
		assertThat(types).containsExactly("start", "suspended", "tool_start", "confirm_request", "confirm_decision",
				"tool_result", "done");
		FrameContract.assertConformant(frames);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(FrameContract.oneOfType(frames, "confirm_decision")).containsEntry("decision", "approve")
				.containsEntry("approved", true)
				.containsEntry("status", PendingToolCall.APPROVED);
		assertThat(FrameContract.oneOfType(frames, "tool_result")).containsEntry("ok", true)
				.containsEntry("outcome", ToolOutcome.OK.wire())
				.containsEntry("code", null)
				.containsEntry("retryable", false)
				.containsEntry("executed", true)
				.containsEntry("messageId", "tool-" + CALL_ID);
		assertThat(FrameContract.oneOfType(frames, "tool_result").get("target"))
				.as("目标资源从参数 JSON 提取").isEqualTo(Map.of("taskId", 7));
		// 放行 ⇒ 认领 + 真实执行 + 记台账，恰好一次
		assertThat(this.fakeStartImport.invocations.get()).isEqualTo(1);
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isEqualTo(this.store.instanceId());
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
		ToolResultEnvelope envelope = modelEnvelope(result);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.OK.wire());
		assertThat(envelope.code()).isNull();
		assertThat(envelope.target()).isEqualTo(Map.of("taskId", 7));
		assertThat(envelope.message()).contains("导入作业已启动（作业 #4242）");
	}

	/**
	 * I2：真实 Redis 的 {@code pending()} 每次返回反序列化出的<b>新对象</b>；本替身默认返回同一引用，
	 * 会掩盖"拿等待前那份旧 pending 取信封"的错误（旧对象停在 PENDING → 信封 unknown）。
	 * 往返模式下首轮执行必须回读结算后的条目，模型信封与帧都得到 ok。
	 *
	 * <p>kill：把 {@code SpToolCallingManager} 里结算后的 {@code store.pending(...)} 重读删掉，
	 * 本用例变红（而普通模式仍绿 —— 这正是需要副本模式的理由）。
	 */
	@Test
	void approvedConfirmPathSettlesEnvelopeWhenStoreReturnsFreshCopies() throws Exception {
		this.store.setDeepCopyPending(true);
		try {
			stubConfirmTool("start_import");
			SseChatEmitter out = this.registry.of(RUN_ID);
			FrameWire wire = FrameWire.attachTo(out);
			out.start(SESSION_ID);

			Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
				wire.awaitType("confirm_request", 5000L);
				return this.gate.submitDecision(RUN_ID, CALL_ID, true, "放行");
			});

			ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
					response("start_import", "{\"taskId\":7}"));
			assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

			ToolResultEnvelope envelope = modelEnvelope(result);
			assertThat(envelope.outcome()).isEqualTo(ToolOutcome.OK.wire());
			assertThat(envelope.code()).isNull();
			assertThat(envelope.message()).contains("导入作业已启动（作业 #4242）");
			assertThat(FrameContract.oneOfType(wire.frames(), "tool_result")).containsEntry("ok", true)
					.containsEntry("outcome", ToolOutcome.OK.wire());
			assertThat(this.store.pending(RUN_ID, CALL_ID).getOutcome()).isEqualTo(ToolOutcome.OK.wire());
		}
		finally {
			this.store.setDeepCopyPending(false);
		}
	}

	// ── ⑦ 确认门：拒绝 ──────────────────────────────────────────────────────

	@Test
	void rejectedConfirmPathEmitsResultWithoutExecuting() throws Exception {
		stubConfirmTool("start_import");
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			return this.gate.submitDecision(RUN_ID, CALL_ID, false, "先不导出");
		});

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		List<String> types = FrameContract.types(frames);
		assertThat(types).containsExactly("start", "suspended", "tool_start", "confirm_request", "confirm_decision",
				"tool_result", "done");
		FrameContract.assertConformant(frames);

		assertThat(FrameContract.oneOfType(frames, "confirm_decision")).containsEntry("decision", "reject")
				.containsEntry("approved", false)
				.containsEntry("status", PendingToolCall.REJECTED);
		assertThat(FrameContract.oneOfType(frames, "tool_result")).containsEntry("ok", false)
				.containsEntry("outcome", ToolOutcome.REJECTED.wire())
				.containsEntry("executed", false);
		// 拒绝 ⇒ 零副作用：不认领、不执行、不记台账，回填给模型的是"未执行 + 原因"
		assertThat(this.fakeStartImport.invocations.get()).isZero();
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(modelEnvelope(result).message()).contains("用户拒绝了该操作，工具未执行").contains("先不导出");
	}

	/** 重复决策（前端双击/重发）必须被拒且<b>不产生第二帧</b>（HTTP 层对应 409 DUPLICATE_TOOL_CALL_ID）。 */
	@Test
	void duplicateDecisionIsRejectedWithoutASecondDecisionFrame() throws Exception {
		stubConfirmTool("start_import");
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			this.gate.submitDecision(RUN_ID, CALL_ID, true, "放行");
			return this.gate.submitDecision(RUN_ID, CALL_ID, true, "放行");
		});

		this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		ConfirmGate.Submission second = decision.get(10, TimeUnit.SECONDS);

		assertThat(second.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(FrameContract.ofType(wire.frames(), "confirm_decision")).as("确认回执恰好一帧（重复决策不再回执）")
				.hasSize(1);
		assertThat(FrameContract.ofType(wire.frames(), "tool_result")).hasSize(1);
		assertThat(this.fakeStartImport.invocations.get()).as("重复决策不得二次执行").isEqualTo(1);
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
	}

	// ── ③ 配对：防线③拦截（越 scope）也要有结局帧 ──────────────────────────

	@Test
	void outOfScopeToolIsPairedWithASingleResultFrameWithoutAnySideEffect() {
		stubConfirmTool("start_import");
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(false);
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start", "tool_result",
				"done");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "tool_result")).containsEntry("ok", false)
				.containsEntry("outcome", ToolOutcome.BLOCKED.wire())
				.containsEntry("code", SpToolCallingManager.SCOPE_VIOLATION_CODE)
				.containsEntry("retryable", false)
				.containsEntry("executed", false)
				.containsEntry("status", PendingToolCall.BLOCKED);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getReason())
				.isEqualTo(SpToolCallingManager.SCOPE_VIOLATION_CODE);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getOutcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(modelEnvelope(result).message()).startsWith(SpToolCallingManager.SCOPE_VIOLATION_CODE);
	}

	// ── ⑥ 前端通道：挂起 → 回灌 → 续跑 ─────────────────────────────────────

	@Test
	void frontendToolSuspendsBackfillsAndEmitsExactlyOneResultFrameWithoutBackendExecution() throws Exception {
		when(this.toolRegistry.channelOf("navigate_to")).thenReturn(ToolMeta.Channel.FRONTEND);
		String payload = "{\"ok\":true,\"page\":\"export\"}";
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> backfill = this.decisionThread.submit(() -> {
			wire.awaitType("frontend_tool_request", 5000L);
			return this.gate.submitFrontendResult(RUN_ID, CALL_ID, payload, "conformance-test");
		});

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("navigate_to", "{\"page\":\"export\"}"),
				response("navigate_to", "{\"page\":\"export\"}"));
		assertThat(backfill.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		// 回灌帧由提交线程发出：等它落到线上再收尾（S5c-6 修复后回灌帧必然先于后果帧，
		// 这里保留等待是为了让"终帧"在帧线上确定地排在最后）
		wire.awaitType("frontend_tool_result", 5000L);
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
		int framesAfterTerminal = wire.size();

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start",
				"frontend_tool_request", "frontend_tool_result", "done");
		FrameContract.assertConformant(frames);

		assertThat(FrameContract.oneOfType(frames, "frontend_tool_result")).containsEntry("executed", false)
				.containsEntry("ok", true)
				.containsEntry("status", PendingToolCall.FRONTEND_RESULT)
				.containsEntry("messageId", "tool-" + CALL_ID);
		assertThat(FrameContract.oneOfType(frames, "frontend_tool_request")).containsEntry("callback",
				"POST /api/ai/frontend-tool-result {runId, toolCallId, result}");
		// 前端工具绝不由后端执行：不认领、不记台账、也不产 tool_result 帧
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(FrameContract.ofType(frames, "tool_result")).isEmpty();
		// 回灌结果原样回填给模型的 message（续跑用的是同一份文本）；信封 outcome=ok
		ToolResultEnvelope envelope = modelEnvelope(result);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.OK.wire());
		assertThat(envelope.target()).isEqualTo(Map.of("page", "export"));
		assertThat(envelope.message()).isEqualTo(payload);

		// 幂等：同一 toolCallId 再次回灌 → DUPLICATE，且不再出帧（不重复执行、不重复渲染）
		ConfirmGate.Submission duplicate = this.gate.submitFrontendResult(RUN_ID, CALL_ID, payload, "retry");
		assertThat(duplicate.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(wire.size()).as("重复回灌不再产生任何帧").isEqualTo(framesAfterTerminal);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
	}

	// ── ③ 配对：并行工具调用（同一 assistant 消息里多个 tool_calls） ─────────

	/**
	 * 一次上游响应里的多个工具调用：每个 {@code tool_start} 各有<b>恰好一个</b>结局帧，
	 * 且 start/result 成对出现（不交叉）。这里走"台账复用"路径（跨进程已执行过 ⇒ 不重复执行），
	 * 因此不依赖官方执行器，也不会碰数据库。
	 */
	@Test
	void parallelToolCallsKeepExactlyOneOutcomeFrameEach() {
		when(this.toolRegistry.channelOf("list_tasks")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("list_tasks")).thenReturn(ToolMeta.RiskLevel.READ);
		this.store.seedLedger(RUN_ID, "call-a", "list_tasks", "任务 A 的结果");
		this.store.seedLedger(RUN_ID, "call-b", "list_tasks", "任务 B 的结果");
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-a", "function", "list_tasks", "{\"page\":0}"),
					new AssistantMessage.ToolCall("call-b", "function", "list_tasks", "{\"page\":1}")))
			.build();
		this.manager.executeToolCalls(prompt("list_tasks", "{}"), new ChatResponse(List.of(new Generation(assistant))));
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start", "tool_result",
				"tool_start", "tool_result", "done");
		FrameContract.assertConformant(frames);
		// 复用台账 ⇒ 不再执行（reused=true），且两个 id 各自一帧结果
		assertThat(FrameContract.ofType(frames, "tool_result")).allSatisfy(
				frame -> assertThat(frame).containsEntry("reused", true).containsEntry("executed", true));
		assertThat(FrameContract.ofType(frames, "tool_result")).extracting(frame -> frame.get("toolCallId"))
				.containsExactly("call-a", "call-b");
		assertThat(this.store.ledger(RUN_ID)).as("复用不写新台账").hasSize(2);
	}

	// ── 批策略：首错即停的 skipped 帧（无 tool_start，帧序仍合规） ─────────────

	/**
	 * <b>【P1-10 全链路】</b>W 类别确定性失败 ⇒ 本批剩余调用不再执行、补发 {@code SKIPPED} 结局帧。
	 *
	 * <p>关键在帧序：被跳过项<b>从未开始执行</b>，所以真实的帧流里它<b>只有 {@code tool_result}、
	 * 没有 {@code tool_start}</b>。这正是 {@link FrameContract#toolPairingContract} 对
	 * {@code outcome=skipped}/{@code status=SKIPPED} 显式豁免的场景 —— 若为了"配对"补发
	 * {@code tool_start}，等于向前端伪造一次并未发生的执行。
	 *
	 * <p>kill：删掉 FrameContract 的跳过项豁免 ⇒ 本用例的 {@code assertConformant} 变红；
	 * 若在 {@code skipAsUpstreamFailed} 里补发 {@code tool_start} ⇒ 下方"skipped 无 start"断言变红。
	 */
	@Test
	void firstDeterministicFailureSkipsTheRestAndSkippedFrameHasNoToolStart() {
		when(this.toolRegistry.categoryOf("create_task")).thenReturn(ToolMeta.Category.W);
		when(this.toolRegistry.channelOf("create_task")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("create_task")).thenReturn(ToolMeta.RiskLevel.WRITE);
		when(this.toolRegistry.categoryOf("start_export")).thenReturn(ToolMeta.Category.J);
		when(this.toolRegistry.channelOf("start_export")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_export")).thenReturn(ToolMeta.RiskLevel.DANGER);
		FakeBatchTools tools = new FakeBatchTools();
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		ToolExecutionResult result = this.manager.executeToolCalls(promptWithTools(tools),
				response(new AssistantMessage.ToolCall("call-w", "function", "create_task", "{\"type\":\"IMPORT\"}"),
						new AssistantMessage.ToolCall("call-j", "function", "start_export", "{\"taskId\":9}")));
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start", "tool_result",
				"tool_result", "done");
		// 全链路帧序合规：skipped 的 tool_result 没有 tool_start 是合法的
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.ofType(frames, "tool_start")).extracting(frame -> frame.get("toolCallId"))
				.as("只有真正执行的上游工具发 tool_start；跳过项从未启动")
				.containsExactly("call-w");
		Map<String, Object> skipped = FrameContract.ofType(frames, "tool_result").stream()
			.filter(frame -> "call-j".equals(frame.get("toolCallId")))
			.findFirst()
			.orElseThrow();
		assertThat(skipped).containsEntry("ok", false)
				.containsEntry("outcome", ToolOutcome.SKIPPED.wire())
				.containsEntry("status", PendingToolCall.SKIPPED)
				.containsEntry("code", ToolOutcome.CODE_UPSTREAM_FAILED)
				.containsEntry("retryable", false)
				.containsEntry("executed", false)
				.containsEntry("messageId", "tool-call-j");
		assertThat(skipped.get("result")).asString()
				.isEqualTo("未执行：上游 create_task 失败（TASK_FINALIZED），本调用已跳过");
		// 跳过项零执行：下游工具一次都没被调用
		assertThat(tools.skippedInvocations.get()).isZero();
		assertThat(tools.failedInvocations.get()).isEqualTo(1);
		// 模型侧两个 toolCallId 都各有回填，第二条是 skipped 信封
		assertThat(result.conversationHistory().stream()
			.filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(response -> ToolResultEnvelope.fromJson(response.responseData()).outcome()))
			.containsExactly(ToolOutcome.FAILED.wire(), ToolOutcome.SKIPPED.wire());
	}

	// ── ⑦ 修复后回归（S5c-6）：决策回执帧先于它唤起的后果 ────────────────────

	/**
	 * <b>【S5c-6 回归】</b>回执帧先于其后果：回执帧卡在"慢订阅者/慢网络"上时，
	 * 被它唤醒的等待方<b>不得</b>先产出后果帧（{@code tool_result}），更不得把回执挤到 {@code done} 之后。
	 *
	 * <p>
	 * 事实链（修复前）：{@code ConfirmGate#submitDecision} 的顺序是
	 * {@code putPending → signal(唤醒等待方) → 发回执帧}；唤醒早于发帧，运行线程可在回执帧写出去
	 * 之前跑完后续动作 —— 轻则 {@code tool_result} 早于 {@code confirm_decision}，
	 * 重则回执落到终帧之后（"终帧之后仍有业务帧"，客户端状态机可能错乱）。
	 *
	 * <p>
	 * 修复后有两重保证：① 次序固定为"落库 → 发回执帧 → 唤醒"（回执先于后果）；
	 * ② 三步与等待方的轮询同锁（{@code ConfirmGate#submitLock}），且出帧本身串行
	 * （{@code SseChatEmitter#emitLock}）。
	 *
	 * <p>
	 * kill：把 {@code submitDecision} 改回 {@code signal(...)} 在 {@code confirmDecision(...)} 之前，
	 * 并去掉 {@code submitLock} ⇒ 卡住回执帧时等待方会先写出 {@code tool_result}，本用例变红。
	 */
	@Test
	void decisionReceiptIsWrittenBeforeTheConsequenceItWakes() throws Exception {
		stubConfirmTool("start_import");
		CountDownLatch receiptInSend = new CountDownLatch(1);
		CountDownLatch releaseReceipt = new CountDownLatch(1);
		FrameWire wire = new FrameWire(payload -> {
			if (payload.contains("confirm_decision")) {
				receiptInSend.countDown();
				try {
					// 回执帧的写出被拖住（模拟慢订阅者 / 慢网络）：此刻它还没"到达"
					releaseReceipt.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		});
		SseChatEmitter out = this.registry.of(RUN_ID);
		out.attach(wire.emitter());
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			return this.gate.submitDecision(RUN_ID, CALL_ID, true, "放行");
		});
		ExecutorService runThread = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "conformance-run");
			thread.setDaemon(true);
			return thread;
		});
		try {
			Future<ToolExecutionResult> run = runThread.submit(() -> this.manager.executeToolCalls(
					prompt("start_import", "{\"taskId\":7}"), response("start_import", "{\"taskId\":7}")));

			assertThat(receiptInSend.await(10, TimeUnit.SECONDS)).as("决策回执帧已进入写出").isTrue();
			assertThat(FrameContract.ofType(wire.frames(), "tool_result"))
					.as("回执帧还没到达，后果帧不得抢先（帧先于其后果）")
					.isEmpty();

			releaseReceipt.countDown();
			assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(run.get(10, TimeUnit.SECONDS)).isNotNull();
			out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
		}
		finally {
			runThread.shutdownNow();
		}

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).as("回执帧先于后果帧，且终帧之后无帧")
				.containsExactly("start", "suspended", "tool_start", "confirm_request", "confirm_decision",
						"tool_result", "done");
		FrameContract.assertConformant(frames);
		assertThat(this.fakeStartImport.invocations.get()).isEqualTo(1);
	}

	// ── ⑦ 失败出口：工具异常浮出为 failed 信封（P0-1） ──────────────────────

	/**
	 * {@code start_*}} 的业务冲突（{@link ConflictException}）不再被官方执行器吞成
	 * {@code ok=true} 的文本：{@code alwaysThrow=true} 让它浮出到 executeOnce 的 catch，
	 * 帧与模型信封同时得到 {@code outcome=failed / code=TASK_FINALIZED}。
	 */
	@Test
	void toolConflictSurfacesAsFailedEnvelopeInFrameAndModelText() {
		stubConfirmTool("start_import");
		this.fakeStartImport.failure = new ConflictException("TASK_FINALIZED", "任务已终态，不能启动导入");
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			return this.gate.submitDecision(RUN_ID, CALL_ID, true, "放行");
		});
		ToolExecutionResult result;
		try {
			result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
					response("start_import", "{\"taskId\":7}"));
			assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		}
		catch (Exception ex) {
			throw new AssertionError(ex);
		}
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "suspended", "tool_start", "confirm_request",
				"confirm_decision", "tool_result", "done");
		FrameContract.assertConformant(frames);
		Map<String, Object> frame = FrameContract.oneOfType(frames, "tool_result");
		assertThat(frame).containsEntry("ok", false)
				.containsEntry("outcome", ToolOutcome.FAILED.wire())
				.containsEntry("code", "TASK_FINALIZED")
				.containsEntry("retryable", false);
		ToolResultEnvelope envelope = modelEnvelope(result);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
		assertThat(envelope.code()).isEqualTo("TASK_FINALIZED");
		assertThat(envelope.message()).contains("工具执行失败：").contains("任务已终态");
		// 失败也记台账（结果文本是事实），且台账带信封
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
		assertThat(this.store.ledger(RUN_ID).get(0)).containsEntry("outcome", ToolOutcome.FAILED.wire())
				.containsEntry("code", "TASK_FINALIZED");
	}

	/**
	 * 确认门<b>不再超时</b>（2026-10-20 用户裁决第 1 条）：
	 * <ol>
	 * <li>{@code confirm_request} 帧<b>不带</b> {@code timeoutSeconds}/{@code expiresAt}
	 *     —— 前端据此不武装倒计时、不把按钮置灰；</li>
	 * <li>跨过多个心跳节拍（2s/拍）期间<b>没有</b>任何收口帧：既无 {@code confirm_decision(timeout)}，
	 *     也无 {@code tool_result}（旧逻辑在这里就会以 {@code CONFIRM_TIMEOUT} 收摊）；</li>
	 * <li>人拒绝之后才收口：{@code confirm_decision(decision=reject)} → {@code tool_result}，
	 *     且零执行。</li>
	 * </ol>
	 */
	@Test
	void confirmRequestCarriesNoDeadlineAndNeverTimesOut() throws Exception {
		stubConfirmTool("start_import");
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		out.start(SESSION_ID);

		Future<ConfirmGate.Submission> decision = this.decisionThread.submit(() -> {
			wire.awaitType("confirm_request", 5000L);
			Map<String, Object> request = FrameContract.oneOfType(wire.frames(), "confirm_request");
			assertThat(request).as("确认卡没有等待上限").doesNotContainKey("timeoutSeconds").doesNotContainKey("expiresAt");
			// 跨过多个心跳节拍：旧逻辑在 1s 上限下早已收摊，新逻辑必须仍在等
			Thread.sleep(2500L);
			List<Map<String, Object>> sofar = wire.frames();
			assertThat(FrameContract.ofType(sofar, "confirm_decision")).as("等待期不得有任何决策帧").isEmpty();
			assertThat(FrameContract.ofType(sofar, "tool_result")).as("等待期不得有工具结局帧").isEmpty();
			return this.gate.submitDecision(RUN_ID, CALL_ID, false, "先不导入");
		});

		ToolExecutionResult result;
		try {
			result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
					response("start_import", "{\"taskId\":7}"));
			assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		}
		catch (Exception ex) {
			throw new AssertionError(ex.getCause() == null ? ex : ex.getCause());
		}
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		List<Map<String, Object>> business = frames.stream()
			.filter(frame -> !FrameContract.HEARTBEAT.equals(frame.get("type")))
			.toList();
		assertThat(FrameContract.types(business)).containsExactly("start", "suspended", "tool_start",
				"confirm_request", "confirm_decision", "tool_result", "done");
		FrameContract.assertConformant(business);
		assertThat(this.fakeStartImport.invocations.get()).as("拒绝 ⇒ 零执行").isZero();
		Map<String, Object> decisionFrame = FrameContract.oneOfType(frames, "confirm_decision");
		assertThat(decisionFrame).containsEntry("decision", "reject").containsEntry("approved", false);
		ToolResultEnvelope envelope = modelEnvelope(result);
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private Prompt promptWithTools(Object toolObject) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(toolObject)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请执行批量工具")), options);
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	/** 批策略全链路用例的两个真实回调：W 类别确定性失败 + 本应被跳过的 J 类别。 */
	static class FakeBatchTools {

		final AtomicInteger failedInvocations = new AtomicInteger();

		final AtomicInteger skippedInvocations = new AtomicInteger();

		@Tool(name = "create_task", description = "受控替身：建任务必失败（触发首错即停）")
		public String createTask(@ToolParam(description = "任务类型") String type) {
			this.failedInvocations.incrementAndGet();
			throw new ConflictException("TASK_FINALIZED", "任务已终态，不能创建");
		}

		@Tool(name = "start_export", description = "受控替身：导出（本批应被跳过，永不执行）")
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			this.skippedInvocations.incrementAndGet();
			return "不应被执行";
		}

	}

	private void stubConfirmTool(String name) {
		when(this.toolRegistry.channelOf(name)).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(name)).thenReturn(ToolMeta.RiskLevel.DANGER);
	}

	private Prompt prompt(String tool, String args) {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.fakeStartImport)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
			.build();
		return new Prompt(List.of(new UserMessage("请执行 " + tool)), options);
	}

	private static ChatResponse response(String tool, String args) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static String toolText(ToolExecutionResult result) {
		return result.conversationHistory()
			.stream()
			.filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElseThrow();
	}

	private static ToolResultEnvelope modelEnvelope(ToolExecutionResult result) {
		return ToolResultEnvelope.fromJson(toolText(result));
	}

}
