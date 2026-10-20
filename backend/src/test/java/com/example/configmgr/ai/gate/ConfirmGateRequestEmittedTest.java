package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L0（2026-10-21 用户直裁）裁决 A 的发射侧证据：<b>请求帧发过才置位 {@code req}</b>。
 *
 * <p>刷新恢复只重建"请求帧已发"的卡（前端 {@code restoreSuspendedSnapshot} 的 {@code req === true}
 * 判据），因此这个位必须与"帧真的发出去"一一对应，而不是与"条目被外置"对应：
 *
 * <ol>
 * <li><b>逐卡置位</b>：一批两张确认卡 —— 卡 1 的帧发出后卡 1 的 {@code req=true}，卡 2 仍是未置位
 * （它的请求帧要等卡 1 决策后才发）；卡 2 的帧发出后同样置位；</li>
 * <li><b>BACKEND 条目不产生请求帧</b>（既有行为锁，配合裁决 B 的三值 kind）：后端直接执行的条目
 * 从头到尾没有卡，{@code req} 恒未置位 —— 前端据此不画卡；</li>
 * <li>置位时刻 {@code reqAtMs} 与请求帧同向（先置位、后发帧，见 {@code ConfirmGate#reserveRequestSlot}）。</li>
 * </ol>
 */
class ConfirmGateRequestEmittedTest {

	private static final String RUN_ID = "run-request-emitted";

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
	void stubCommon() {
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("start_export")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_export")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.toolRegistry.channelOf("read_tasks")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("read_tasks")).thenReturn(ToolMeta.RiskLevel.READ);
		this.store.seedRun(RUN_ID, "sess-1");
	}

	@Test
	void requestFrameEmissionMarksEachCardIndividually() throws Exception {
		FrameWire wire = FrameWire.attachTo(this.out);
		Thread runner = new Thread(() -> this.manager.executeToolCalls(prompt(), twoConfirmCalls()), "request-emitted");
		runner.start();
		try {
			wire.awaitType("confirm_request", 5000);

			PendingToolCall card1 = this.store.pending(RUN_ID, "call-1");
			assertThat(card1.getReq()).as("卡 1 的请求帧已发 ⇒ req 置位").isTrue();
			assertThat(card1.getReqAtMs()).isPositive();
			// 卡 2 的请求帧还没发（逐条 await：卡 1 决策之后才轮到它）—— 刷新恢复此刻不得给卡 2 画卡
			PendingToolCall card2Before = this.store.pending(RUN_ID, "call-2");
			assertThat(card2Before.getReq()).as("卡 2 的请求帧未发 ⇒ req 未置位").isNull();

			this.gate.submitDecision(RUN_ID, "call-1", false, "unit-reject");
			awaitTrue(() -> countArchived("confirm_request") == 2, 5000, "卡 2 的请求帧未发出");

			PendingToolCall card2After = this.store.pending(RUN_ID, "call-2");
			assertThat(card2After.getReq()).as("卡 2 的请求帧发出后置位").isTrue();
			assertThat(card2After.getReqAtMs()).isPositive();
		}
		finally {
			this.gate.submitDecision(RUN_ID, "call-2", false, "unit-reject");
			runner.join(5000);
		}
		assertThat(runner.isAlive()).as("两张卡都决策后整批应当结算").isFalse();
	}

	@Test
	void requestFrameIsEmittedOnlyAfterTheMarkCallReturns() throws Exception {
		// N-2（2026-10-21 收口修）：既有用例在**观测到帧之后**读条目，正反两种顺序都能过。
		// 直接锁顺序不能靠"读同一个对象"（内存替身存的是引用，"先发帧、再就地 set" 也会读到已置位），
		// 故用**调用时序**证明：置位调用返回时打点 A，请求帧写出时打点 B，A 必须早于（或同于）B。
		RunStore ordered = mock(RunStore.class);
		when(ordered.pending(RUN_ID, "call-1")).thenReturn(pending("call-1", PendingToolCall.KIND_CONFIRM));
		java.util.concurrent.atomic.AtomicInteger markCalls = new java.util.concurrent.atomic.AtomicInteger();
		when(ordered.markRequestEmitted(eq(RUN_ID), eq("call-1"), anyLong())).thenAnswer(invocation -> {
			markCalls.incrementAndGet();
			PendingToolCall marked = pending("call-1", PendingToolCall.KIND_CONFIRM);
			marked.setReq(Boolean.TRUE);
			marked.setReqAtMs(1_790_000_000_000L);
			return marked;
		});
		ConfirmGate orderedGate = new ConfirmGate(ordered, this.registry, new AiProperties(), this.cancellations,
				this.beacon, List.of());
		java.util.concurrent.atomic.AtomicInteger marksAtEmit = new java.util.concurrent.atomic.AtomicInteger(-1);
		FrameWire wire = new FrameWire(payload -> {
			if (payload.contains("\"confirm_request\"")) {
				marksAtEmit.set(markCalls.get());
			}
		});
		this.out.attach(wire.emitter());

		Thread runner = new Thread(() -> orderedGate.awaitDecision(RUN_ID, pending("call-1",
				PendingToolCall.KIND_CONFIRM)), "request-order");
		runner.setDaemon(true);
		runner.start();
		try {
			wire.awaitType("confirm_request", 5000);
			assertThat(marksAtEmit.get()).as("请求帧写出时，置位调用已返回（先置位、后发帧）").isEqualTo(1);
		}
		finally {
			// 中断等待循环（不提交决策：本用例只关心"置位先于发帧"，木桩 store 不会给出"已决"副本）
			runner.interrupt();
			runner.join(2000);
		}
	}

	@Test
	void backendKindEntryNeverEmitsRequestFrameAndStaysUnmarked() {
		FrameWire wire = FrameWire.attachTo(this.out);

		this.manager.executeToolCalls(prompt(), response(
				new AssistantMessage.ToolCall("call-read", "function", "read_tasks", "{}")));

		assertThat(wire.types()).as("后端直接执行的条目没有卡").doesNotContain("confirm_request",
				"frontend_tool_request");
		PendingToolCall stored = this.store.pending(RUN_ID, "call-read");
		assertThat(stored.getKind()).as("kind 三值原文").isEqualTo(PendingToolCall.KIND_BACKEND);
		assertThat(stored.getReq()).as("从未发请求帧 ⇒ 未置位").isNull();
	}

	@Test
	void frontendToolRequestMarksTheEntryToo() throws Exception {
		FrameWire wire = FrameWire.attachTo(this.out);
		when(this.toolRegistry.channelOf("generative_form")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("generative_form")).thenReturn(ToolMeta.RiskLevel.READ);
		Thread runner = new Thread(() -> this.manager.executeToolCalls(prompt(), response(
				new AssistantMessage.ToolCall("call-gf", "function", "generative_form",
						"{\"form\":{\"scenario\":\"demo\",\"fields\":[]}}"))),
				"frontend-request-emitted");
		runner.start();
		try {
			wire.awaitType("frontend_tool_request", 5000);
			PendingToolCall stored = this.store.pending(RUN_ID, "call-gf");
			assertThat(stored.getReq()).as("前端工具请求帧同样置位").isTrue();
			assertThat(stored.getReqAtMs()).isPositive();
		}
		finally {
			this.gate.submitFrontendResult(RUN_ID, "call-gf", "{}", "unit-test", true);
			runner.join(5000);
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private int countArchived(String type) {
		return (int) this.store.archivedTypes(RUN_ID).stream().filter(type::equals).count();
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

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
				.toolCallbacks(List.of())
				.toolContext(Map.of("runId", RUN_ID))
				.build();
		return new Prompt(List.of(new UserMessage("hi")), options);
	}

	private static PendingToolCall pending(String toolCallId, String kind) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName(PendingToolCall.KIND_FRONTEND.equals(kind) ? "generative_form" : "start_export");
		pending.setKind(kind);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

	private static ChatResponse twoConfirmCalls() {
		return response(new AssistantMessage.ToolCall("call-1", "function", "start_export", "{\"taskId\":7}"),
				new AssistantMessage.ToolCall("call-2", "function", "start_import", "{\"taskId\":9}"));
	}

	private static ChatResponse response(AssistantMessage.ToolCall... calls) {
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
