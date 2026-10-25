package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续跑前的压缩（上下文统计与压缩专项批①的<b>第二个</b>调用点）。
 *
 * <p>
 * 为什么必须单独测：首轮与续跑是两套历史来源（{@code chat:mem:} vs {@code ai:run:} 快照）。
 * 压缩只挂在首轮 ⇒ "同一会话换个入口进来就不压了"，而这种分叉在人工冒烟里几乎看不出来
 * （挂起→续跑是低频路径）。本用例断言三件事：
 * <ol>
 * <li>续跑重建出来的工具响应<b>也</b>能被 L1 清到（挂起期执行的结果往往正是最大的一块）；</li>
 * <li>压缩后的历史<b>写回快照</b>（否则下一轮的 converge 会拿未压缩的那份覆盖回来）；</li>
 * <li>{@code resume} 的响应体如实报告 {@code contextCompaction}（运维/取证面）。</li>
 * </ol>
 */
class ResumeContextCompactionTest {

	private static final String RUN_ID = "run-resume-ctx";

	private static final String SESSION_ID = "s-resume-ctx";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	/** 真实写出器 + 帧线：本组要断言"续跑也发 context 帧、且位置合法"，mock 的 emitter 看不见帧序。 */
	private SseChatEmitter emitter;

	private FrameWire wire;

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final InMemoryContextStateStore contextStore = new InMemoryContextStateStore();

	private final ContextCompactor compactor = new ContextCompactor(this.properties,
			new ContextLimitResolver(this.properties), this.contextStore);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

	@BeforeEach
	void setUpEmitter() {
		this.emitter = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		this.wire = FrameWire.attachTo(this.emitter);
		// start 由原轮发出（生产上已在归档里），此处模拟"客户端已看过 start"的视角
		this.emitter.start(SESSION_ID);
	}

	@Test
	void resumeClearsOldToolResultsAndPersistsTheCompactedHistory() {
		seedSuspendedRun();
		// 上一轮真值越线：81000 / (128000 - 13000) = 70.4% ≥ L1
		this.contextStore.write(SESSION_ID, ContextState.EMPTY.withUsage(81000, 0, 1, false,
				System.currentTimeMillis() - 10_000, "test-model", 3000, 4000, 20));
		ReflectionTestUtils.setField(this.service, "contextCompactor", this.compactor);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.chatService.disclosedToolTokens(any(AiContext.class))).thenReturn(4000);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("导入作业已启动");
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		// 交棒后的真实轮次会产出正文帧：用它来断言"context 帧早于首个 delta"
		doAnswer(invocation -> {
			SseChatEmitter out = invocation.getArgument(4);
			out.textStart();
			out.delta("已清理");
			out.textEnd(3);
			out.done(Map.of("promptTokens", 70000, "completionTokens", 10, "totalTokens", 70010), "test-model",
					Map.of("cancelled", false), Map.of("usedTokens", 70000, "limitTokens", 128000, "percent", 54.7,
							"level", "L1", "est", false, "circuit", "closed"));
			return null;
		}).when(this.chatService).driveResumed(any(), any(), any(), any(), any());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("contextCompaction", "L1");

		// ── I-1：续跑也发 context 帧，位置 = 晚于 start、早于首个 delta（帧序不变量同样成立）──
		List<Map<String, Object>> frames = this.wire.frames();
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.types(frames)).containsExactly("start", "context", "message_start", "delta",
				"message_end", "done");
		Map<String, Object> contextFrame = FrameContract.oneOfType(frames, "context");
		assertThat(contextFrame).containsEntry("level", "L1").containsEntry("turns", 0L)
			.containsEntry("lostTokens", 0L).containsEntry("reason", "l1_tool_result_clearing")
			.containsEntry("est", false)
			.containsKeys("before", "after", "savedTokens", "clearedPairs");

		// ── I-1：级别随交棒传给驱动侧（否则续跑轮的 done.context.level 恒为 none）──
		verify(this.chatService).noteCompaction(RUN_ID, "L1");

		// ① 最旧的几对被清成占位符（pin 最近 5 对）
		RunSnapshot snapshot = this.store.get(RUN_ID);
		List<Message> history = this.codec.deserializeAll(snapshot.getMessageJson());
		List<String> toolData = new ArrayList<>();
		history.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
			.forEach(message -> message.getResponses().forEach(response -> toolData.add(response.responseData())));
		assertThat(toolData.get(0)).startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
		// ② 压缩后的历史已写回快照（否则下一轮 converge 会把原文覆盖回来）
		assertThat(toolData.stream().filter(ToolResultClearing::isAlreadyCleared).count()).isGreaterThanOrEqualTo(1);
		// L1 不删消息：1 user + 6×(assistant+tool) + 挂起轮的 assistant + 重建的 tool = 15 条
		assertThat(history).hasSize(15);
		// ③ 真值重置为压缩后的估算基线（等下一次真值回填）
		assertThat(this.contextStore.read(SESSION_ID).est()).isTrue();
		assertThat(this.contextStore.read(SESSION_ID).level()).isEqualTo("L1");
	}

	@Test
	void resumeWithoutCompactionReportsNone() {
		seedSuspendedRun();
		this.contextStore.write(SESSION_ID, ContextState.EMPTY.withUsage(1000, 0, 1, false,
				System.currentTimeMillis() - 10_000, "test-model", 0, 0, 0));
		ReflectionTestUtils.setField(this.service, "contextCompactor", this.compactor);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.chatService.disclosedToolTokens(any(AiContext.class))).thenReturn(0);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("导入作业已启动");
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.body()).containsEntry("contextCompaction", "none");
		assertThat(this.contextStore.archiveCount()).isZero();
		assertThat(FrameContract.types(this.wire.frames())).as("未压缩 ⇒ 一帧不发").containsExactly("start");
		verify(this.chatService, never()).noteCompaction(any(), any());
	}

	/**
	 * 复核 N-a：交棒被拒（池饱和）时，已登记的级别交接位必须就地清掉。
	 *
	 * <p>
	 * 为什么必须清：交接位是"一轮一个、读完即删"的一次性槽位。池饱和意味着这一轮<b>不会</b>被
	 * 驱动 ⇒ {@code resumedTurn} 永远不来取 ⇒ 槽位留到进程结束（runId 不复用，不致命，
	 * 但容量论证与排障都会失真：看上去"压过 L1"，其实这一轮没跑）。
	 */
	@Test
	void poolSaturationClearsTheCompactionHandoffSlot() {
		seedSuspendedRun();
		armCompactingResume();
		doThrow(new RejectedExecutionException("pool full")).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.POOL_SATURATED);
		assertThat(result.body()).containsEntry("resumed", false).containsEntry("reason", "SUSPEND_POOL_SATURATED");
		InOrder order = inOrder(this.chatService);
		order.verify(this.chatService).noteCompaction(RUN_ID, "L1");
		order.verify(this.chatService).clearCompaction(RUN_ID);
	}

	/**
	 * 复核 N-b：{@code context} 帧发不出去（客户端已断 / emitter 已关）不得影响续跑成败。
	 *
	 * <p>
	 * 发帧是观测面，续跑是业务面。二者若耦合，一个已经断开的客户端能让一轮续跑变成 500 ——
	 * 而挂起轮的续跑本来就是给"离开后又回来"的客户端用的，恰好是最容易撞上断连的路径。
	 */
	@Test
	void contextFrameFailureDoesNotBlockResume() {
		seedSuspendedRun();
		armCompactingResume();
		SseChatEmitter broken = mock(SseChatEmitter.class);
		doThrow(new IllegalStateException("emitter closed")).when(broken).context(any());
		when(this.registry.of(RUN_ID)).thenReturn(broken);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("resumed", true).containsEntry("contextCompaction", "L1");
		// 帧失败 ≠ 交接丢失：级别仍要传给驱动侧，否则本轮 done.context.level 会退化成 none
		verify(this.chatService).noteCompaction(RUN_ID, "L1");
		verify(this.chatService, never()).clearCompaction(any());
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(), any(), eq(broken));
	}

	/** 越线真值 + 压缩设施装配 + 同步执行器（池未饱和的公共前置）。 */
	private void armCompactingResume() {
		// 上一轮真值越线：81000 / (128000 - 13000) = 70.4% ≥ L1
		this.contextStore.write(SESSION_ID, ContextState.EMPTY.withUsage(81000, 0, 1, false,
				System.currentTimeMillis() - 10_000, "test-model", 3000, 4000, 20));
		ReflectionTestUtils.setField(this.service, "contextCompactor", this.compactor);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.chatService.disclosedToolTokens(any(AiContext.class))).thenReturn(4000);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("导入作业已启动");
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
	}

	@Test
	void cancellationDuringHistoryRebuildSkipsApprovedIrreversibleTool() {
		seedSuspendedRun();
		PendingToolCall approved = entry();
		approved.setKind(PendingToolCall.KIND_CONFIRM);
		approved.setStatus(PendingToolCall.APPROVED);
		approved.setExecuted(false);
		approved.setExecutedBy(null);
		approved.setOutcome(null);
		approved.setResultText(null);
		approved.setIrreversible(true);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(approved)));
		this.store.save(snapshot);
		this.store.seedPending(RUN_ID, approved);
		AtomicBoolean cancelled = new AtomicBoolean();
		when(this.cancellations.cancelState(RUN_ID)).thenAnswer(invocation -> cancelled.get()
				? CancellationRegistry.CancelState.CANCELLED : CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenAnswer(invocation -> {
				cancelled.set(true);
				return new Prompt(List.of());
			});
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("发布作业已启动");

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		verify(this.toolCallingManager, never()).resolvePending(eq(RUN_ID), any(Prompt.class),
				any(PendingToolCall.class));
		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ALREADY_CANCELLED);
		verify(this.chatService).cancelParked(RUN_ID);
	}

	private void seedSuspendedRun() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 7L, null).toMap());
		snapshot.setModelId("test-model");
		List<Message> history = new ArrayList<>();
		history.add(new UserMessage("请启动导出"));
		for (int i = 1; i <= 6; i++) {
			history.add(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c-" + i, "function", "list_tasks", "{}")))
				.build());
			history.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("c-" + i, "list_tasks",
						"任务列表内容".repeat(100))))
				.build());
		}
		snapshot.setMessageJson(this.codec.serializeAll(history));
		PendingToolCall inFlight = entry();
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(inFlight)));
		this.store.save(snapshot);
		this.store.seedPending(RUN_ID, entry());
	}

	private static PendingToolCall entry() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("c-inflight");
		pending.setName("start_export");
		pending.setType("function");
		pending.setArguments("{\"taskId\":7}");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("导出作业已启动");
		pending.setExecuted(true);
		pending.setExecutedBy("instance-old");
		return pending;
	}

}
