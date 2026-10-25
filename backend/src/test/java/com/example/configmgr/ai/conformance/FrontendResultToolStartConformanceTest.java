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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>决策侧补发 {@code tool_start}：前端卡（{@code FRONTEND_RESULT}）的残支</b>
 * （{@code suspend-kill-a} 同一缺陷的另一个所有权边界）。
 *
 * <h2>被钉住的缺陷</h2>
 * 前端卡的结局帧 {@code frontend_tool_result} 由<b>决策路径</b>（{@code ConfirmGate#submitFrontendResult}）
 * 在落库同锁内发出，<b>早于</b>续跑。原实例若死在 {@code tool_start} 落档之前，续跑侧
 * （{@code resolvePending}）既不发帧也无从补发（此刻 result 已在归档里，补一条只会排在它后面）——
 * 帧册因此永久停在 {@code frontend_tool_request → frontend_tool_result}，没有 {@code tool_start}。
 *
 * <h2>本批口径</h2>
 * 补发点必须在<b>决策落库的那把 {@code submitLock} 内、发结局帧之前</b>，复用
 * {@link SseChatEmitter#ensureToolStart(PendingToolCall)}（归档判据、跨实例、幂等）：
 * <ul>
 * <li>原实例丢了 start ⇒ 决策方补<b>恰一条</b>，且排在 {@code frontend_tool_result} 之前；</li>
 * <li>start 已在归档 ⇒ 一条不补（不重复建段）；</li>
 * <li>决策幂等不变：重复回灌仍是 {@code DUPLICATE}，不产生任何新帧；</li>
 * <li>补发之后、结局帧之前崩溃 ⇒ 仍只有一条 start；重复回灌 / 续跑都不得再补；</li>
 * <li>首轮帧序 {@code suspended → tool_start → frontend_tool_request} 不变（对照用例直接断言）。</li>
 * </ul>
 */
class FrontendResultToolStartConformanceTest {

	private static final String RUN_ID = "run-frontend-result-start";

	private static final String SESSION_ID = "s-frontend-result-start";

	private static final String CALL_ID = "call-form-1";

	/** 让"原实例"死在 {@code tool_start} 落档之前：吞掉接下来的 N 次 {@code tool_start}。 */
	private final AtomicInteger suppressedStarts = new AtomicInteger();

	/** 模拟"补发 start 之后、结局帧之前"的进程死亡：让下一次结局帧抛出。 */
	private final AtomicInteger resultCrashes = new AtomicInteger();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final AiProperties properties = new AiProperties();

	private final SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, this.mapper) {
		@Override
		public void toolStart(PendingToolCall pending) {
			if (FrontendResultToolStartConformanceTest.this.suppressedStarts
				.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				// 原实例在落档之前死亡：归档里从来不会有这一帧（不取号、不留洞）
				return;
			}
			super.toolStart(pending);
		}

		@Override
		public void frontendToolResult(PendingToolCall pending, boolean ok, String result) {
			if (FrontendResultToolStartConformanceTest.this.resultCrashes
				.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				throw new IllegalStateException("模拟决策方死在补发 start 之后、结局帧之前");
			}
			super.frontendToolResult(pending, ok, result);
		}
	};

	/** 单写出器替身：决策侧与续跑侧拿到的必须是同一个写出器（身份一致性）。 */
	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null) {
		@Override
		public SseChatEmitter of(String runId) {
			return FrontendResultToolStartConformanceTest.this.emitter;
		}
	};

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor taskExecutor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private ConfirmGate gate;

	private SpToolCallingManager manager;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("generative_form")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("generative_form")).thenReturn(ToolMeta.RiskLevel.WRITE);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of());
		this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
				this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());
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

	/**
	 * <b>核心用例</b>：前端卡的 {@code tool_start} 丢了（原实例死在落档之前），决策路径必须补
	 * <b>恰一条</b>，且排在它自己发出的 {@code frontend_tool_result} 之前；随后的续跑不得再补。
	 * 未修的代码在这里得到 0 条 start。
	 */
	@Test
	void decisionBackfillsTheMissingToolStartBeforeTheFrontendResult() {
		parkWithMissingToolStart();

		ConfirmGate.Submission decision = submitResult();

		assertThat(decision.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		assertThat(countOf(events, "tool_start", CALL_ID))
			.as("决策路径必须补发原实例没来得及落档的 tool_start（帧册：每个被执行工具恰一条）")
			.isEqualTo(1);
		assertThat(countOf(events, "frontend_tool_result", CALL_ID)).as("结局帧恰一条").isEqualTo(1);
		assertThat(indexOf(events, "tool_start", CALL_ID))
			.as("补发的 tool_start 必须早于 frontend_tool_result（否则前端无法配对工具卡）")
			.isLessThan(indexOf(events, "frontend_tool_result", CALL_ID));
		assertThat(indexOf(events, "suspended", null))
			.as("挂起公告仍先于工具帧")
			.isLessThan(indexOf(events, "tool_start", CALL_ID));

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		List<Map<String, Object>> afterResume = this.store.events(RUN_ID);
		assertThat(countOf(afterResume, "tool_start", CALL_ID)).as("续跑不得重复补发").isEqualTo(1);
		assertThat(countOf(afterResume, "frontend_tool_result", CALL_ID)).as("续跑不重发结局帧").isEqualTo(1);
		assertThat(indexOf(afterResume, "tool_start", CALL_ID))
			.isLessThan(indexOf(afterResume, "frontend_tool_result", CALL_ID));
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
	}

	/** 对照：原实例的 {@code tool_start} 已在归档 ⇒ 决策一条不补，且首轮帧序不变。 */
	@Test
	void anArchivedToolStartIsNotDuplicatedByTheDecision() {
		parkKeepingToolStart();

		ConfirmGate.Submission decision = submitResult();

		assertThat(decision.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		assertThat(countOf(events, "tool_start", CALL_ID)).as("已有 start ⇒ 不重复补发").isEqualTo(1);
		assertThat(countOf(events, "frontend_tool_result", CALL_ID)).isEqualTo(1);
		assertThat(indexOf(events, "tool_start", CALL_ID))
			.isLessThan(indexOf(events, "frontend_tool_result", CALL_ID));
		// 正常帧序：suspended → tool_start → frontend_tool_request（首轮发帧路径一个字都没改）
		assertThat(indexOf(events, "suspended", null)).isLessThan(indexOf(events, "tool_start", CALL_ID));
		assertThat(indexOf(events, "tool_start", CALL_ID))
			.as("首轮契约：tool_start 必须先于请求帧")
			.isLessThan(indexOf(events, "frontend_tool_request", CALL_ID));
	}

	/** 重复回灌：第二次是 {@code DUPLICATE}，不新增任何帧（决策幂等与补发的幂等互不干扰）。 */
	@Test
	void aRepeatedDecisionIsStillDuplicateAndAddsNoFrame() {
		parkWithMissingToolStart();
		assertThat(submitResult().outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
		int framesAfterFirst = this.store.events(RUN_ID).size();

		ConfirmGate.Submission repeat = submitResult();

		assertThat(repeat.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
		assertThat(this.store.events(RUN_ID)).as("重复回灌不产生新帧").hasSize(framesAfterFirst);
		assertThat(countOf(this.store.events(RUN_ID), "tool_start", CALL_ID)).isEqualTo(1);
		assertThat(countOf(this.store.events(RUN_ID), "frontend_tool_result", CALL_ID)).isEqualTo(1);
	}

	/**
	 * <b>补发之后、结局帧之前</b>决策方死亡（结局帧抛出）：归档里应恰有一条 start、没有 result；
	 * 重复回灌（{@code DUPLICATE}）与后续续跑都不得再补一条 start。
	 */
	@Test
	void aCrashBetweenBackfillAndResultDoesNotDuplicateOnRepeatDecisionOrResume() {
		parkWithMissingToolStart();
		this.resultCrashes.set(1);

		assertThatThrownBy(this::submitResult).isInstanceOf(IllegalStateException.class);

		List<Map<String, Object>> afterCrash = this.store.events(RUN_ID);
		assertThat(countOf(afterCrash, "tool_start", CALL_ID)).as("补发已在崩溃前落档").isEqualTo(1);
		assertThat(countOf(afterCrash, "frontend_tool_result", CALL_ID)).as("崩溃发生在结局帧之前").isZero();

		assertThat(submitResult().outcome()).as("决策已落库 ⇒ 重复回灌按幂等处理")
			.isEqualTo(ConfirmGate.Outcome.DUPLICATE);

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		assertThat(countOf(events, "tool_start", CALL_ID)).as("重复回灌 + 续跑都不得重复补发").isEqualTo(1);
		assertThat(countOf(events, "frontend_tool_result", CALL_ID))
			.as("前端卡的结局帧只由决策路径产出；崩溃点之后的续跑不伪造它")
			.isZero();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private ConfirmGate.Submission submitResult() {
		return this.gate.submitFrontendResult(RUN_ID, CALL_ID, "{\"keyword\":\"E2E\"}", "http-post", false, null);
	}

	/** 首轮 park，且原实例死在 {@code tool_start} 落档之前（归档里只有 suspended + frontend_tool_request）。 */
	private void parkWithMissingToolStart() {
		this.suppressedStarts.set(1);
		assertParked();
		assertThat(countOf(this.store.events(RUN_ID), "tool_start", CALL_ID))
			.as("复现前提：归档里确实没有这一帧")
			.isZero();
		assertThat(countOf(this.store.events(RUN_ID), "frontend_tool_request", CALL_ID))
			.as("复现前提：卡已公布（回灌可由另一实例落库）")
			.isEqualTo(1);
	}

	/** 首轮 park，保留原实例的 {@code tool_start}（对照组）。 */
	private void parkKeepingToolStart() {
		this.suppressedStarts.set(0);
		assertParked();
		assertThat(countOf(this.store.events(RUN_ID), "tool_start", CALL_ID)).isEqualTo(1);
	}

	private void assertParked() {
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(), response()))
			.as("park 是控制流：请求帧公布后线程交还")
			.isInstanceOf(RunParkedException.class);
	}

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
		return events.stream()
			.filter(frame -> type.equals(String.valueOf(frame.get("type"))))
			.filter(frame -> toolCallId.equals(String.valueOf(frame.get("toolCallId"))))
			.count();
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("出一张筛选表单")), options);
	}

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "generative_form",
					"{\"form\":{\"scenario\":\"FILTER\"}}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
