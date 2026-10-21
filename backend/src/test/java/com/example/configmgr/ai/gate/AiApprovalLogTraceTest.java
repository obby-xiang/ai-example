package com.example.configmgr.ai.gate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批链路的日志用例（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>挂起与决策是同一轮里<b>唯一会跨越人机边界</b>的两行：{@code approval_suspend} 回答"卡什么时候
 * 发出去、等的是谁"，{@code approval_decided} 回答"人什么时候拍的板、等了多久、谁拍的"。
 * 没有这两行，"这一轮为什么慢"永远只能靠时间戳猜。
 *
 * <table>
 * <caption>用例矩阵</caption>
 * <tr><td>G1</td><td>进入等待 ⇒ {@code approval_suspend}（toolCallId/tool/kind/无正文）</td></tr>
 * <tr><td>G2</td><td>提交决策 ⇒ {@code approval_decided}（decision/waitedMs/actor）</td></tr>
 * <tr><td>G3</td><td>拒收（NOT_FOUND）也留一行，且有 outcome 字段——失败面同样可追</td></tr>
 * </table>
 */
class AiApprovalLogTraceTest {

	private static final String RUN_ID = "run-approval-log";

	private static final String TOOL_CALL_ID = "call-approval-1";

	private final RunStore store = mock(RunStore.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(),
			this.cancellations, this.beacon, List.of());

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	@Test
	void enteringTheGateEmitsApprovalSuspend() throws Exception {
		Map<String, PendingToolCall> state = new ConcurrentHashMap<>();
		state.put(TOOL_CALL_ID, pending());
		when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenAnswer(invocation -> copyOf(state.get(TOOL_CALL_ID)));
		doAnswer(invocation -> {
			PendingToolCall saved = invocation.getArgument(1);
			state.put(saved.getToolCallId(), copyOf(saved));
			return null;
		}).when(this.store).putPending(eq(RUN_ID), any(PendingToolCall.class));
		CountDownLatch requested = new CountDownLatch(1);
		doAnswer(invocation -> {
			requested.countDown();
			return null;
		}).when(this.emitter).confirmRequest(any(PendingToolCall.class));

		Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, pending()), "approval-suspend-probe");
		waiter.setDaemon(true);
		waiter.start();
		try {
			assertThat(requested.await(5, TimeUnit.SECONDS)).as("确认卡已发出").isTrue();
			String line = awaitLine("approval_suspend");
			assertThat(line).contains("runId=" + RUN_ID).contains("toolCallId=" + TOOL_CALL_ID)
				.contains("tool=start_export").contains("kind=confirm");
		}
		finally {
			waiter.interrupt();
			waiter.join(2000);
		}
	}

	@Test
	void submittingADecisionEmitsApprovalDecidedWithWaitTime() {
		Map<String, PendingToolCall> state = new ConcurrentHashMap<>();
		PendingToolCall waiting = pending();
		waiting.setReq(Boolean.TRUE);
		waiting.setReqAtMs(System.currentTimeMillis() - 4321L);
		state.put(TOOL_CALL_ID, waiting);
		when(this.store.pendingRaw(RUN_ID, TOOL_CALL_ID)).thenAnswer(invocation -> copyOf(state.get(TOOL_CALL_ID)));
		when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenAnswer(invocation -> copyOf(state.get(TOOL_CALL_ID)));
		doAnswer(invocation -> {
			PendingToolCall saved = invocation.getArgument(1);
			state.put(saved.getToolCallId(), copyOf(saved));
			return null;
		}).when(this.store).putPending(eq(RUN_ID), any(PendingToolCall.class));
		when(this.store.tryRecordDecision(RUN_ID, TOOL_CALL_ID, PendingToolCall.APPROVED))
			.thenReturn(new RunStore.DecisionClaim(true, PendingToolCall.APPROVED));

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "批准执行");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		String line = line("approval_decided");
		assertThat(line).contains("runId=" + RUN_ID).contains("toolCallId=" + TOOL_CALL_ID)
			.contains("decision=approve").contains("actor=user").contains("outcome=ACCEPTED")
			.containsPattern("waitedMs=\\d{3,}");
	}

	@Test
	void rejectedSubmissionStillLeavesATraceLine() {
		when(this.store.pendingRaw(RUN_ID, TOOL_CALL_ID)).thenReturn(null);
		when(this.store.pending(RUN_ID, TOOL_CALL_ID)).thenReturn(null);

		ConfirmGate.Submission submission = this.gate.submitDecision(RUN_ID, "call-missing", true, "批准");

		assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.NOT_FOUND);
		assertThat(line("approval_decided")).contains("outcome=NOT_FOUND").contains("toolCallId=call-missing");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private List<String> eventLines() {
		List<String> out = new ArrayList<>();
		// 事件可能由别的线程正在追加：先做一次快照（toArray 语义，不经迭代器 ⇒ 不会 CME）
		for (ILoggingEvent event : new ArrayList<>(this.appender.list)) {
			String message = event.getFormattedMessage();
			if (message.startsWith("event=")) {
				out.add(message);
			}
		}
		return out;
	}

	private String line(String name) {
		return eventLines().stream().filter(line -> line.startsWith("event=" + name + " ")).findFirst()
			.orElseThrow(() -> new AssertionError("缺少事件行 event=" + name + "；实收 = " + eventLines()));
	}

	/**
	 * 等事件行出现（另一条线程正在写）：事件与"帧已发出"不是同一时刻，直接读会撞上竞态。
	 */
	private String awaitLine(String name) {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			for (String line : eventLines()) {
				if (line.startsWith("event=" + name + " ")) {
					return line;
				}
			}
			try {
				Thread.sleep(20L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return line(name);
	}

	private static PendingToolCall pending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setArguments("{\"taskId\":\"t-1\"}");
		return pending;
	}

	private static PendingToolCall copyOf(PendingToolCall source) {
		PendingToolCall copy = new PendingToolCall();
		copy.setToolCallId(source.getToolCallId());
		copy.setName(source.getName());
		copy.setKind(source.getKind());
		copy.setStatus(source.getStatus());
		copy.setArguments(source.getArguments());
		copy.setResultText(source.getResultText());
		copy.setOutcome(source.getOutcome());
		copy.setCode(source.getCode());
		copy.setReason(source.getReason());
		copy.setExecuted(source.isExecuted());
		copy.setExecutedBy(source.getExecutedBy());
		copy.setReq(source.getReq());
		copy.setReqAtMs(source.getReqAtMs());
		copy.setAtMs(source.getAtMs());
		copy.setResolvedAtMs(source.getResolvedAtMs());
		return copy;
	}

}
