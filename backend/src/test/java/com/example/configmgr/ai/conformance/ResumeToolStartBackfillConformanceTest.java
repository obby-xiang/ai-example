package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeStartImportTool;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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
 * <b>续跑侧的帧册不变量：每个被执行工具恰一条 {@code tool_start}，且先于它的结局帧</b>
 * （真实集群调研 {@code suspend-kill-a} 的产品侧缺口）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code saveSuspended}（状态先对外成立）→ {@code suspended} 帧 → 本批 {@code tool_start} → 请求帧：
 * 原实例若死在 {@code tool_start} <b>落档之前</b>，归档里就<b>永远</b>没有这一帧；而接管/续跑
 * （{@code ResumeService#resolvePending} → {@code claimAndExecute}）只发 {@code tool_result}、
 * 从不补 {@code tool_start} ⇒ 该轮帧册永久停在"有结局、没有开始"，前端无法把工具卡与结果帧配对。
 *
 * <h2>本批口径</h2>
 * 续跑在执行结算的入口<b>幂等补发</b>缺失的 {@code tool_start}：先读事件日志（跨实例）判断
 * 这个 {@code toolCallId} 是否已有 {@code tool_start}，没有才补一帧；已有则不补（不重复）。
 * 首个用例在未修的代码上必红（{@code tool_start} 计数 0）。
 */
class ResumeToolStartBackfillConformanceTest {

	private static final String RUN_ID = "run-resume-start-backfill";

	private static final String SESSION_ID = "s-resume-start-backfill";

	private static final String CALL_ID = "call-import-1";

	/** 让"原实例"死在 {@code tool_start} 落档之前：吞掉接下来的 N 次 {@code tool_start}。 */
	private final AtomicInteger suppressedStarts = new AtomicInteger();

	/** 模拟"补发之后、结局帧之前"的进程死亡：让下一次台账写抛错（结局帧因此不会落档）。 */
	private final AtomicInteger ledgerCrashes = new AtomicInteger();

	private final FakeStartImportTool tool = new FakeStartImportTool();

	private final InMemoryRunStore store = new InMemoryRunStore() {
		@Override
		public void appendLedger(String runId, PendingToolCall pending, String resultText) {
			if (ResumeToolStartBackfillConformanceTest.this.ledgerCrashes
				.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				throw new IllegalStateException("模拟进程死在续跑结算中途（tool_start 已落档、tool_result 未落档）");
			}
			super.appendLedger(runId, pending, resultText);
		}
	};

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final AiProperties properties = new AiProperties();

	private final SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, this.mapper) {
		@Override
		public void toolStart(PendingToolCall pending) {
			if (ResumeToolStartBackfillConformanceTest.this.suppressedStarts
				.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				// 原实例在落档之前死亡：归档里从来不会有这一帧（不占号、不留洞）
				return;
			}
			super.toolStart(pending);
		}
	};

	/** 单写出器替身：把"同一轮的写出器"固定成上面那个可控实例（其余语义走真实现）。 */
	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null) {
		@Override
		public SseChatEmitter of(String runId) {
			return ResumeToolStartBackfillConformanceTest.this.emitter;
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
		// 确认卡：后端可执行 + 高风险 ⇒ 首轮进确认门、放行后由后端委托执行
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
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
	 * <b>核心用例</b>：原实例在 park 公布之后、{@code tool_start} 落档之前死亡（归档里没有该帧），
	 * 另一个实例接管并把这张已批准的确认卡执行完 —— 必须恰好补出一条 {@code tool_start}，
	 * 且它在 {@code tool_result} 之前。未修的代码在这里得到 0 条。
	 */
	@Test
	void crossInstanceResumeBackfillsTheMissingToolStartBeforeTheResult() {
		parkWithMissingToolStart();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		assertThat(countOf(events, "tool_start", CALL_ID))
			.as("接管方必须补发原实例没来得及落档的 tool_start（帧册：每个被执行工具恰一条）")
			.isEqualTo(1);
		assertThat(countOf(events, "tool_result", CALL_ID)).as("工具恰执行一次、结局帧恰一条").isEqualTo(1);
		assertThat(indexOf(events, "tool_start", CALL_ID))
			.as("补发的 tool_start 必须早于对应的 tool_result")
			.isLessThan(indexOf(events, "tool_result", CALL_ID));
		assertThat(indexOf(events, "suspended", null))
			.as("挂起公告仍先于工具帧（首轮帧序不受影响）")
			.isLessThan(indexOf(events, "tool_start", CALL_ID));
		assertThat(this.tool.invocations.get()).as("恰执行一次（不重放副作用）").isEqualTo(1);
		assertThat(this.store.pending(RUN_ID, CALL_ID).isExecuted()).isTrue();
	}

	/** 归档里已有原实例的 {@code tool_start} ⇒ 续跑<b>不得</b>再补一条（重复会让前端重复建段）。 */
	@Test
	void resumeKeepsExactlyOneToolStartWhenTheOriginalOneIsAlreadyArchived() {
		parkKeepingToolStart();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		assertThat(countOf(events, "tool_start", CALL_ID)).as("已有 start ⇒ 不重复补发").isEqualTo(1);
		assertThat(countOf(events, "tool_result", CALL_ID)).isEqualTo(1);
		assertThat(indexOf(events, "tool_start", CALL_ID)).isLessThan(indexOf(events, "tool_result", CALL_ID));
		assertThat(this.tool.invocations.get()).isEqualTo(1);
	}

	/**
	 * <b>补发之后、结局帧之前</b>进程死亡（台账写抛错 ⇒ 没有 {@code tool_result}）：
	 * 归档里此刻应已有补发的 {@code tool_start}；下一次续跑不得再补一条，结局帧恰一条。
	 */
	@Test
	void aCrashBetweenBackfillAndResultDoesNotDuplicateTheStartOnTheNextResume() {
		parkWithMissingToolStart();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");
		this.ledgerCrashes.set(1);

		assertThatThrownBy(() -> this.service.resume(RUN_ID)).isInstanceOf(IllegalStateException.class);

		List<Map<String, Object>> afterCrash = this.store.events(RUN_ID);
		assertThat(countOf(afterCrash, "tool_start", CALL_ID)).as("补发已在崩溃前落档").isEqualTo(1);
		assertThat(countOf(afterCrash, "tool_result", CALL_ID)).as("崩溃发生在结局帧之前").isZero();

		ResumeService.ResumeResult second = this.service.resume(RUN_ID);

		assertThat(second.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		List<Map<String, Object>> events = this.store.events(RUN_ID);
		assertThat(countOf(events, "tool_start", CALL_ID)).as("再次续跑不得重复补发").isEqualTo(1);
		assertThat(countOf(events, "tool_result", CALL_ID)).as("结局帧恰一条（认领已失 ⇒ 不再执行）").isEqualTo(1);
		assertThat(indexOf(events, "tool_start", CALL_ID)).isLessThan(indexOf(events, "tool_result", CALL_ID));
		assertThat(this.tool.invocations.get()).as("第二次续跑不得重放副作用").isEqualTo(1);
	}

	/** 重复续跑：一轮已经跑完（快照 RUNNING）之后再挂一次，不产生任何新帧、不重复补发。 */
	@Test
	void repeatedResumeAfterACompletedRoundAddsNoFrames() {
		parkWithMissingToolStart();
		this.gate.submitDecision(RUN_ID, CALL_ID, true, "批准");
		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		int framesAfterFirst = this.store.events(RUN_ID).size();

		ResumeService.ResumeResult again = this.service.resume(RUN_ID);

		assertThat(again.outcome()).isEqualTo(ResumeService.Outcome.ALREADY_RUNNING);
		assertThat(this.store.events(RUN_ID)).as("重复续跑不产生新帧").hasSize(framesAfterFirst);
		assertThat(countOf(this.store.events(RUN_ID), "tool_start", CALL_ID)).isEqualTo(1);
		assertThat(countOf(this.store.events(RUN_ID), "tool_result", CALL_ID)).isEqualTo(1);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 首轮 park，且原实例死在 {@code tool_start} 落档之前（归档里只有 suspended + confirm_request）。 */
	private void parkWithMissingToolStart() {
		this.suppressedStarts.set(1);
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(), response()))
			.as("park 是控制流：请求帧公布后线程交还")
			.isInstanceOf(RunParkedException.class);
		assertThat(countOf(this.store.events(RUN_ID), "tool_start", CALL_ID))
			.as("复现前提：归档里确实没有这一帧")
			.isZero();
		assertThat(countOf(this.store.events(RUN_ID), "confirm_request", CALL_ID))
			.as("复现前提：卡已公布（决策可由另一实例落库）")
			.isEqualTo(1);
	}

	/** 首轮 park，保留原实例的 {@code tool_start}（对照组）。 */
	private void parkKeepingToolStart() {
		this.suppressedStarts.set(0);
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(), response()))
			.isInstanceOf(RunParkedException.class);
		assertThat(countOf(this.store.events(RUN_ID), "tool_start", CALL_ID)).isEqualTo(1);
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

	private Prompt prompt() {
		List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
			.toolObjects(this.tool)
			.build()
			.getToolCallbacks());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(callbacks)
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("启动导入任务 7")), options);
	}

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "start_import",
					"{\"taskId\":7}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
