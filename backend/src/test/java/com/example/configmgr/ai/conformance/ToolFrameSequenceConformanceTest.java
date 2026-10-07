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
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * <h2>为什么不直接对挂起轮用 {@code assertConformant}</h2>
 * 确认门/前端工具的<b>决策回执帧</b>由提交线程发出，而 {@code ConfirmGate} 先唤醒等待方、
 * 后发回执（{@code signal()} 早于 {@code confirmDecision(...)}），于是"回执帧"与"它唤起的后果帧"
 * （{@code tool_result}）之间的相对顺序<b>今天是竞态</b>（【待裁决 S5c-6】）。
 * 本类因此把顺序断言拆成两半：确定性部分照旧全量断言，竞态部分只断言"两条帧都在"，
 * 另用 {@link #pinsDecisionReceiptArrivingAfterItsConsequence()} 把竞态窗口确定化后钉住。
 */
class ToolFrameSequenceConformanceTest {

	private static final String RUN_ID = "run-tool-frames";

	private static final String SESSION_ID = "s-tool-frames";

	private static final String CALL_ID = "call-tool-frames-1";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties));

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
		// 确定性部分：挂起公告 → 工具出场 → 确认请求 … 终帧收尾
		assertThat(types.subList(0, 4)).containsExactly("start", "suspended", "tool_start", "confirm_request");
		assertThat(types.get(types.size() - 1)).isEqualTo("done");
		// 竞态部分（S5c-6）：回执与后果两条帧都必须在，相对顺序今天不保证
		assertThat(types.subList(4, types.size() - 1)).containsExactlyInAnyOrder("confirm_decision", "tool_result");
		assertConformantToleratingEmitRaces(frames);

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		assertThat(FrameContract.oneOfType(frames, "confirm_decision")).containsEntry("decision", "approve")
				.containsEntry("approved", true)
				.containsEntry("status", PendingToolCall.APPROVED);
		assertThat(FrameContract.oneOfType(frames, "tool_result")).containsEntry("ok", true)
				.containsEntry("executed", true)
				.containsEntry("messageId", "tool-" + CALL_ID);
		// 放行 ⇒ 认领 + 真实执行 + 记台账，恰好一次
		assertThat(this.fakeStartImport.invocations.get()).isEqualTo(1);
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isEqualTo(this.store.instanceId());
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
		assertThat(toolText(result)).contains("导入作业已启动（作业 #4242）");
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
		assertThat(types.subList(0, 4)).containsExactly("start", "suspended", "tool_start", "confirm_request");
		assertThat(types.get(types.size() - 1)).isEqualTo("done");
		assertThat(types.subList(4, types.size() - 1)).containsExactlyInAnyOrder("confirm_decision", "tool_result");
		assertConformantToleratingEmitRaces(frames);

		assertThat(FrameContract.oneOfType(frames, "confirm_decision")).containsEntry("decision", "reject")
				.containsEntry("approved", false)
				.containsEntry("status", PendingToolCall.REJECTED);
		assertThat(FrameContract.oneOfType(frames, "tool_result")).containsEntry("ok", false)
				.containsEntry("executed", false);
		// 拒绝 ⇒ 零副作用：不认领、不执行、不记台账，回填给模型的是"未执行 + 原因"
		assertThat(this.fakeStartImport.invocations.get()).isZero();
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(toolText(result)).contains("用户拒绝了该操作，工具未执行").contains("先不导出");
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
				.containsEntry("executed", false)
				.containsEntry("status", PendingToolCall.BLOCKED);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getReason())
				.isEqualTo(SpToolCallingManager.SCOPE_VIOLATION_CODE);
		assertThat(this.store.claimedBy(RUN_ID, CALL_ID)).isNull();
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(toolText(result)).startsWith(SpToolCallingManager.SCOPE_VIOLATION_CODE);
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
		// 回灌帧由提交线程发出：等它落到线上再收尾，避免"终帧抢在回执之前"（S5c-6 的同类窗口）
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
		// 回灌结果原样回填给模型（续跑用的是同一份文本）
		assertThat(toolText(result)).isEqualTo(payload);

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

	// ── 钉住现行为（S5c-6）：决策回执晚于它唤起的后果，甚至晚于终帧 ─────────

	/**
	 * <b>【待裁决 S5c-6】</b>钉住"决策回执帧与它唤起的后果之间的顺序不成立"这一现行为。
	 *
	 * <p>
	 * 事实链：{@code ConfirmGate#submitDecision}（以及 {@code #submitFrontendResult}）的顺序是
	 * {@code putPending → signal(唤醒等待方) → registry.of(runId).confirmDecision(发回执帧)}。
	 * 唤醒<b>早于</b>发帧，于是运行线程可能在回执帧写出去之前就跑完后续动作：
	 * 轻则 {@code tool_result} 早于 {@code confirm_decision}（客户端先看到工具结局、后看到人的决策），
	 * 重则整轮已经收尾 —— 回执帧落到 {@code done} <b>之后</b>（"终帧之后仍有业务帧"）。
	 *
	 * <p>
	 * 本用例把"决策线程在 signal 与 emit 之间被调度切走"这一窗口<b>确定化</b>（用帧线钩子把回执帧
	 * 压到终帧之后再写出）：这不是构造出来的假象，而是真实存在的窗口里<b>任意一次</b>调度切换的结果。
	 * 修复方向：把回执帧的发出放到 {@code signal()} 之前（或对"状态落库 + 发帧 + 唤醒"加同一把锁）。
	 * 本用例届时必须改写 —— 它钉的是缺陷，不是契约。
	 */
	@Test
	void pinsDecisionReceiptArrivingAfterItsConsequence() throws Exception {
		stubConfirmTool("start_import");
		CountDownLatch terminalRecorded = new CountDownLatch(1);
		FrameWire wire = new FrameWire(payload -> {
			if (payload.contains("\"type\":\"done\"")) {
				terminalRecorded.countDown();
			}
			if (payload.contains("confirm_decision")) {
				try {
					// 决策线程被调度切走：等运行线程把后果帧（以及终帧）写完再回来发回执
					terminalRecorded.await(5, TimeUnit.SECONDS);
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

		this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
		assertThat(decision.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).as("回执帧落在了它自己的后果与终帧之后")
				.containsExactly("start", "suspended", "tool_start", "confirm_request", "tool_result", "done",
						"confirm_decision");
		assertThat(FrameContract.orderingContract(frames)).anySatisfy(violation -> assertThat(violation)
				.contains("tool_result")
				.contains("早于 confirm_decision"));
		assertThat(FrameContract.terminalContract(frames)).anySatisfy(
				violation -> assertThat(violation).contains("终帧（第 5 帧 done）之后还有 1 帧"));
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	/**
	 * 挂起轮的帧序断言：<b>序号集合的完整性与唯一性照旧严格断言</b>，其余不变量照跑，
	 * 只放行两条由同一类并发窗口造成的已知违规（都已在钉住用例里确定化复现）：
	 * <ul>
	 * <li>{@code tool_result} 早于 {@code confirm_decision}（【S5c-6】：{@code signal()} 早于发帧）；</li>
	 * <li>到达顺序非严格递增（【S5c-3】：取号 → 落归档 → 写出不是一次不可分割的动作，
	 * 于是运行线程可以在决策线程"取了号还没写出"的窗口里超车）。</li>
	 * </ul>
	 * 两条都只影响<b>顺序</b>，不影响<b>集合</b>；集合完整性由本方法第一段独立证明，
	 * 因此这里的放行不会把"丢帧/重号"一起放过去。
	 */
	private static void assertConformantToleratingEmitRaces(List<Map<String, Object>> frames) {
		List<Long> seqs = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			seqs.add(seq instanceof Number number ? number.longValue() : -1L);
		}
		assertThat(seqs).as("帧序号不得重号").doesNotHaveDuplicates();
		List<Long> sorted = new ArrayList<>(seqs);
		sorted.sort(Long::compareTo);
		assertThat(sorted).as("帧序号集合必须完整（1..N，无缺口、无丢失）")
				.isEqualTo(java.util.stream.LongStream.rangeClosed(1, frames.size()).boxed().toList());

		List<String> tolerated = new ArrayList<>();
		for (String violation : FrameContract.violations(frames)) {
			if (violation.contains("早于 confirm_decision") || violation.contains("严格递增")) {
				tolerated.add(violation);
				continue;
			}
			throw new AssertionError("帧序一致性违规（帧流=" + FrameContract.types(frames) + "）：" + violation);
		}
		// 容忍条数不做上限断言：一次逆序会同时让"两帧各错位一次"产生多条 seq 违规，
		// 真正的守卫是上面的"序号集合完整 + 不重号"（丢帧/重号照旧失败）。
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

}
