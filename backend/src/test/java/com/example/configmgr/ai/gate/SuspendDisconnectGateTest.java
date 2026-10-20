package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>B1（2026-10-20）挂起即断 SSE 的接线证据</b>：真实 {@link ConfirmGate} + 真实
 * {@link SseChatEmitter} + 语义真实的 {@link InMemoryRunStore}，只把工具注册表与取消登记做成桩。
 *
 * <p>
 * 锁住四件事（对应设计稿裁决 1/3/4/5）：
 * <ol>
 * <li><b>断连点在请求帧之后</b>：确认卡的 {@code confirm_request} 先<b>真的写进连接</b>，
 * 随后订阅被完成（{@code subscriberCount()==0} + {@code emitter.complete()}）；</li>
 * <li><b>一批多 toolCall 的请求帧一个都不丢</b>：管理器是<b>逐个</b> await 的 ——
 * 卡 1 的请求帧在断连前投递，卡 2 的请求帧在卡 1 决策之后、零订阅者下发出（只落档），
 * 复查连接带 {@code ?lastSeq} 重连即可差量补回；</li>
 * <li><b>断连后 emit 无副作用</b>：零订阅者期间帧照旧取号落档，重连回放可见；</li>
 * <li><b>delta 入档</b>：挂起窗口内产生的正文帧可经差量回放补回（见归档断言）。</li>
 * </ol>
 */
class SuspendDisconnectGateTest {

	private static final String RUN_ID = "run-suspend-disconnect";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	/** 真实注册表 + 真实写出器（{@code properties == null} ⇒ 同步直执，帧序确定）。 */
	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final SseChatEmitter out = this.registry.of(RUN_ID);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations,
			this.beacon, List.of());

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, mock(MessageJsonCodec.class), this.beacon, this.cancellations,
			new ToolResultLimiter(new AiProperties()), List.of());

	@BeforeEach
	void stubCommon() throws Exception {
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("start_export")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_export")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		this.store.seedRun(RUN_ID, "sess-1");
	}

	// ── ① 断连点：请求帧投递之后、进入阻塞等待之前 ───────────────────────────

	@Test
	void confirmRequestIsDeliveredBeforeTheSubscriptionIsCompleted() throws Exception {
		FrameWire card = FrameWire.attachTo(this.out);

		Thread runner = new Thread(() -> this.manager.executeToolCalls(prompt(), oneCall()),
				"suspend-run");
		runner.start();
		try {
			Map<String, Object> request = card.awaitType("confirm_request", 5000);
			assertThat(request).containsEntry("toolCallId", "call-1");

			// ① 连接在请求帧之后被完成：订阅表清空（前端 onclose 看到的"服务端收流"）
			awaitTrue(() -> this.out.subscriberCount() == 0, 5000, "请求帧之后订阅未被完成");
			// ①′ 请求帧不是在 complete 之后才写的（顺序：写帧 → 收尾）
			assertThat(card.types()).contains("suspended", "confirm_request");
			assertThat(this.store.archivedTypes(RUN_ID)).contains("confirm_request");

			this.gate.submitDecision(RUN_ID, "call-1", false, "unit-reject");
			runner.join(5000);
			assertThat(runner.isAlive()).as("决策后挂起等待应当返回").isFalse();
		}
		finally {
			runner.join(1000);
		}
	}

	// ── ② 一批多 toolCall：每张卡的请求帧都活下来 ────────────────────────────

	/**
	 * 实测帧流（本用例断言的正是这条链）：
	 * <pre>
	 * suspended[call-1, call-2]  → tool_start(call-1) → confirm_request(call-1) → [订阅完成]
	 *   （决策 call-1）
	 * confirm_decision(call-1) → tool_start(call-2) → confirm_request(call-2)   ← 零订阅者，只落档
	 *   （重连 ?lastSeq → 差量回放把上面三帧补回）
	 *   （决策 call-2）→ tool_result(call-2) …
	 * </pre>
	 * 结论：任何一张卡的请求帧都发在"该帧之前"的断连点之后，不会被截断；第 2 张卡的卡面
	 * 由决策后重连的差量回放送达（这正是前端 B1 的恢复路径）。
	 */
	@Test
	void everyCardRequestFrameSurvivesTheSuspendDisconnect() throws Exception {
		FrameWire first = FrameWire.attachTo(this.out);

		Thread runner = new Thread(() -> this.manager.executeToolCalls(prompt(), twoCalls()), "suspend-run-2");
		runner.start();
		try {
			Map<String, Object> card1 = first.awaitType("confirm_request", 5000);
			assertThat(card1).containsEntry("toolCallId", "call-1");
			awaitTrue(() -> this.out.subscriberCount() == 0, 5000, "卡 1 之后订阅未被完成");

			long lastSeqBeforeDecision = maxSeq(first.frames());
			this.gate.submitDecision(RUN_ID, "call-1", false, "unit-reject");
			// 卡 2 的请求帧此刻无人可投，只能落档
			awaitTrue(() -> countArchived("confirm_request") == 2, 5000, "卡 2 的请求帧未落档");

			// 前端决策后重连：带 lastSeq 只收差量
			FrameWire reconnect = new FrameWire();
			int replayed = this.out.replayAndAttach(reconnect.emitter(), lastSeqBeforeDecision, false);
			assertThat(replayed).isPositive();
			assertThat(reconnect.types()).contains("confirm_decision", "tool_start", "confirm_request");
			assertThat(reconnect.frames()).filteredOn(frame -> "confirm_request".equals(frame.get("type")))
					.singleElement()
					.satisfies(frame -> assertThat(frame).containsEntry("toolCallId", "call-2"));

			this.gate.submitDecision(RUN_ID, "call-2", false, "unit-reject");
			runner.join(5000);
			assertThat(runner.isAlive()).as("两张卡都决策后整批应当结算").isFalse();
			// 两张卡的请求帧都在归档里（重连/刷新都能重建两张卡，不存在"被断连截断的卡"）
			assertThat(countArchived("confirm_request")).isEqualTo(2);
		}
		finally {
			runner.join(1000);
		}
	}

	// ── ③ 断连后 emit 无副作用：零订阅者照旧取号落档，重连可见 ───────────────

	@Test
	void emitWithZeroSubscribersKeepsArchivingAndNewSubscriptionsCanAttach() {
		assertThat(this.out.subscriberCount()).isZero();
		assertThat(this.out.completeSubscriptions()).as("零订阅者 = no-op").isZero();

		this.out.start("sess-1");
		this.out.delta("挂起窗口内的正文");
		this.out.suspended(List.of(pending("call-9")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());
		// 心跳：不占业务号、不入档（T7 口径不动）
		long seqBeforeHeartbeat = this.store.lastEventSeq(RUN_ID);
		this.out.heartbeat(Map.of("phase", "suspend", "waitedSeconds", 2));
		assertThat(this.store.lastEventSeq(RUN_ID)).as("心跳不占业务号").isEqualTo(seqBeforeHeartbeat);

		// delta 入档（B1 裁决 3）：正文可经差量补回
		assertThat(this.store.archivedTypes(RUN_ID)).containsExactly("start", "delta", "suspended");

		// 新订阅仍可接入：回放全部 + 续收实时帧
		FrameWire late = new FrameWire();
		this.out.replayAndAttach(late.emitter(), null, false);
		this.out.delta("重连之后");
		assertThat(late.types()).containsExactly("start", "delta", "suspended", "delta");
		assertThat(this.out.subscriberCount()).isEqualTo(1);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private int countArchived(String type) {
		return (int) this.store.archivedTypes(RUN_ID).stream().filter(type::equals).count();
	}

	private static long maxSeq(List<Map<String, Object>> frames) {
		long max = 0L;
		for (Map<String, Object> frame : frames) {
			if (frame.get("seq") instanceof Number number) {
				max = Math.max(max, number.longValue());
			}
		}
		return max;
	}

	private static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(20L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("等待超时：" + message);
	}

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
				.toolCallbacks(List.of())
				.toolContext(Map.of("runId", RUN_ID))
				.build();
		return new Prompt(List.of(new UserMessage("hi")), options);
	}

	private static ChatResponse oneCall() {
		return response(new AssistantMessage.ToolCall("call-1", "function", "start_export", "{\"taskId\":7}"));
	}

	private static ChatResponse twoCalls() {
		return response(
				new AssistantMessage.ToolCall("call-1", "function", "start_export", "{\"taskId\":7}"),
				new AssistantMessage.ToolCall("call-2", "function", "start_import", "{\"taskId\":9}"));
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
