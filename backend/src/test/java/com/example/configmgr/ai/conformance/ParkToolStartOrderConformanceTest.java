package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>park 开启时的工具帧序契约</b>（真模型 CI run 40171918873 的 G-01 回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * park 批把本批每张卡的 {@code frontend_tool_request} 提前到执行循环之前公布
 * （{@code ConfirmGate#publishRequestsForParkedBatch}，目的：首张卡 park 后其余卡仍有请求帧），
 * 但 {@code tool_start} 仍由执行循环逐张发 ⇒ 真实帧序变成
 * {@code suspended → frontend_tool_request → tool_start}，与契约
 * （{@code suspended → tool_start → frontend_tool_request}，见本包 {@code ToolFrameSequenceConformanceTest}）
 * 相反。前端 {@code parts.ts} 的 {@code tool_start} 分支会把已经建立的等动作卡复位成
 * {@code status=running / requiresAction=false} —— 生成式表单因此只剩只读 "cancelled"，永远提交不了。
 *
 * <h2>本批口径</h2>
 * park 开启时 {@code tool_start} 与请求帧<b>同批前置</b>：先逐张 {@code tool_start}，再逐张
 * {@code frontend_tool_request}；执行循环看到的已发卡不再重发（每张卡恰一条 {@code tool_start}，
 * 不重发、不重复）。
 */
class ParkToolStartOrderConformanceTest {

	private static final String RUN_ID = "run-park-frame-order";

	private static final String SESSION_ID = "s-park-frame-order";

	private static final String[] CALLS = { "call-form-1", "call-form-2" };

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	/** 真编解码（续跑要按快照 messageJson 重建历史；mock 会返回 null 让续跑链断掉）。 */
	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	/** 续跑侧：模型驱动面替身（只关心"有没有被派发"与它拿到的历史）。 */
	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final org.springframework.data.redis.core.StringRedisTemplate redis =
			mock(org.springframework.data.redis.core.StringRedisTemplate.class);

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("generative_form")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("generative_form")).thenReturn(ToolMeta.RiskLevel.WRITE);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@Test
	void everyFrontendCardGetsItsToolStartBeforeThePublishedRequest() {
		SpToolCallingManager manager = manager();

		assertThatThrownBy(() -> manager.executeToolCalls(prompt(), response()))
			.as("park 是控制流：首张卡请求帧公布后线程交还")
			.isInstanceOf(RunParkedException.class);

		List<Map<String, Object>> events = this.store.events(RUN_ID);
		int suspended = indexOf(events, "suspended", null);
		int firstStart = indexOf(events, "tool_start", null);
		int firstRequest = indexOf(events, "frontend_tool_request", null);
		assertThat(suspended).as("挂起公告先于任何工具帧").isGreaterThanOrEqualTo(0);
		assertThat(firstStart).as("tool_start 必须存在（否则卡只有请求帧、没有卡面）").isGreaterThanOrEqualTo(0);
		assertThat(firstRequest).isGreaterThanOrEqualTo(0);
		assertThat(suspended).isLessThan(firstStart);
		assertThat(firstStart).as("契约帧序：tool_start 必须先于 frontend_tool_request").isLessThan(firstRequest);

		for (String callId : CALLS) {
			int start = indexOf(events, "tool_start", callId);
			int request = indexOf(events, "frontend_tool_request", callId);
			assertThat(start)
				.as("卡 %s 必须有自己的 tool_start，且先于它的请求帧", callId)
				.isGreaterThanOrEqualTo(0)
				.isLessThan(request);
			assertThat(countOf(events, "tool_start", callId))
				.as("每张卡的 tool_start 恰一条（不重发/不重复）")
				.isEqualTo(1);
			assertThat(countOf(events, "frontend_tool_request", callId))
				.as("每张卡的请求帧恰一条")
				.isEqualTo(1);
		}
		assertThat(this.store.get(RUN_ID).getInFlightToolCalls())
			.as("两张卡都进入挂起态（可分别决策）")
			.hasSize(2);
		assertThat(this.store.pending(RUN_ID, CALLS[0]).getStatus()).isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.pending(RUN_ID, CALLS[1]).getStatus()).isEqualTo(PendingToolCall.PENDING);
	}

	/**
	 * <b>F7：park → 两张卡都回灌 → 真续跑链路不得再发一次 {@code tool_start}</b>。
	 *
	 * <p>帧序契约要求每张卡恰一条 {@code tool_start}。park 批把 start 前置到了挂起公告之后，
	 * 续跑侧（{@link ResumeService} → {@code resolvePending}）只结算条目、不执行也不发 start ——
	 * 本用例用<b>真</b> {@link SpToolCallingManager} + 真 {@link ResumeService} + 真写出器把这条
	 * 不变量锁住（不是"按构造推断"）。
	 */
	@Test
	void resumeAfterParkDoesNotEmitASecondToolStartForTheSameCard() {
		SpToolCallingManager manager = manager();
		assertThatThrownBy(() -> manager.executeToolCalls(prompt(), response()))
			.isInstanceOf(RunParkedException.class);
		assertThat(this.store.pending(RUN_ID, CALLS[0]).getStatus()).isEqualTo(PendingToolCall.PENDING);

		// 两张卡都回灌（与生产决策同一结局：FRONTEND_RESULT + resultText）
		for (String callId : CALLS) {
			PendingToolCall pending = this.store.pending(RUN_ID, callId);
			pending.setStatus(PendingToolCall.FRONTEND_RESULT);
			pending.setOutcome(ToolOutcome.OK.wire());
			pending.setExecuted(false);
			pending.setResolvedAtMs(System.currentTimeMillis());
			pending.setResultText("已填 " + callId);
			this.store.putPending(RUN_ID, pending);
		}
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService, manager,
				this.executor, this.redis, this.properties, this.cancellations,
				new ToolResultLimiter(this.properties));
		ResumeService.ResumeResult result = service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				any(SseChatEmitter.class));
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		for (String callId : CALLS) {
			assertThat(countOf(events, "tool_start", callId))
				.as("续跑不得重发 tool_start（卡 %s）", callId)
				.isEqualTo(1);
			assertThat(countOf(events, "frontend_tool_request", callId))
				.as("续跑不得重发请求帧（卡 %s）", callId)
				.isEqualTo(1);
		}
		assertThat(this.store.get(RUN_ID).getInFlightToolCalls())
			.as("续跑结算后不再有 in-flight 条目")
			.isEmpty();
	}

	private SpToolCallingManager manager() {
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of());
		return new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry, this.codec, this.beacon,
				this.cancellations, new ToolResultLimiter(this.properties), List.of());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static int indexOf(List<Map<String, Object>> events, String type, String toolCallId) {
		for (int i = 0; i < events.size(); i++) {
			Map<String, Object> frame = events.get(i);
			if (!type.equals(String.valueOf(frame.get("type")))) {
				continue;
			}
			if (toolCallId == null || toolCallId.equals(String.valueOf(frame.get("toolCallId")))) {
				return i;
			}
		}
		return -1;
	}

	private static long countOf(List<Map<String, Object>> events, String type, String toolCallId) {
		long count = 0;
		for (Map<String, Object> frame : events) {
			if (type.equals(String.valueOf(frame.get("type")))
					&& toolCallId.equals(String.valueOf(frame.get("toolCallId")))) {
				count++;
			}
		}
		return count;
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("出一张筛选表单")), options);
	}

	private static ChatResponse response() {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (String callId : CALLS) {
			calls.add(new AssistantMessage.ToolCall(callId, "function", "generative_form",
					"{\"form\":{\"scenario\":\"FILTER\"}}"));
		}
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(calls).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
