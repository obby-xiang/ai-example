package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * <b>W-21 Fix A</b>：挂起工具轮（{@code saveSuspended} 已落库、请求帧已公布）不得被当成"可普通重试的空轮"。
 *
 * <h2>现场（run 40247120358 · runId {@code 5df1ce3c…}）</h2>
 * <ol>
 * <li>模型发 {@code ui_click}；{@code SpToolCallingManager} 调 {@code saveSuspended}：快照 SUSPENDED +
 *     in-flight + 请求帧公布（此刻 {@code messageJson} 仍是 {@code [SYSTEM, USER]}）；</li>
 * <li>工具执行前的取消检查读到 UNKNOWN（上一轮打断 Redis GET 留下的进程级 5s 退避）⇒
 *     {@code AI_REDIS_UNAVAILABLE}；{@code emitted=false / sideEffect=false} ⇒ 重试判据放行；</li>
 * <li>重试从 memory 重建请求（user 被 advisor 复制、assistant(tool_calls)/TOOL 都不在），桩按幂等闸
 *     不再给工具 ⇒ {@code finish} 写 DONE 并清空 in-flight —— 挂起轮被抹掉；</li>
 * <li>{@code /history} 与 {@code historyRoles} 都只剩 {@code [USER, ASSISTANT]}（W-21 死在 TOOL 段缺失）。</li>
 * </ol>
 *
 * <p>本用例把"失败那一刻的状态"确定化：attempt 1 的模型流在返回错误前先把
 * SUSPENDED + in-flight + messageJson + pending 写好（等价于 {@code saveSuspended} 已完成），
 * 然后抛 {@code STATE_UNAVAILABLE}。旧代码会发第 2 次上游请求并收尾成 DONE（红）；修复后只调 1 次，
 * 快照保持 SUSPENDED、in-flight/pending 原样、无终帧、记忆不被覆写（绿）。
 * 另附回归：真正 RUNNING 的普通失败仍照旧重试（不把合法重试一起关掉）。
 */
class RetryWithSuspendedToolRoundTest {

	private static final String RUN_ID = "run-retry-suspended-tool";

	private static final String SESSION_ID = "s-retry-suspended-tool";

	private static final String CALL_ID = "call-stub-205";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "prov", "ci-stub", "http://localhost",
			"/chat/completions", "k", null, null, this.chatModel, mock(ChatClient.class));

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	private final AtomicInteger streams = new AtomicInteger();

	@BeforeEach
	void setUp() {
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "w21-fix-a-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void persistedParkedToolRoundIsNotRetriedAsAnEmptyRound() {
		PendingToolCall pending = pendingFrontendCall();
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			if (this.streams.incrementAndGet() > 1) {
				// 旧行为：第 2 次尝试从 memory 重建（没有工具段），桩只会回纯文本 ⇒ finish 收尾成 DONE。
				return Flux.just(chunk("重试的纯文本"), terminal("stop"));
			}
			// 现场：saveSuspended 已完成（SUSPENDED + in-flight + messageJson + pending），随后取消检查读 UNKNOWN。
			RunSnapshot suspended = this.store.get(RUN_ID);
			suspended.setStatus(RunSnapshot.SUSPENDED);
			suspended.setInFlightToolCalls(List.of(pending));
			suspended.setAssistantContent("{\"toolCalls\":[{\"id\":\"" + CALL_ID + "\",\"name\":\"ui_click\"}]}");
			suspended.setMessageJson(
					this.codec.serializeAll(List.of(new SystemMessage("系统提示"), new UserMessage("点一下「选择文件」"))));
			this.store.save(suspended);
			this.store.putPending(RUN_ID, pending);
			return Flux.error(new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
					"取消状态未知（Redis 读失败），工具执行在事件边界终止（runId=" + RUN_ID + "）"));
		});

		FrameWire wire = runTurn();

		// 软断言：一次红跑就能看到全部被破坏的不变量（旧行为：2 次调用 / DONE / in-flight 清空 / 记忆被覆写）
		assertSoftly(softly -> {
			softly.assertThat(this.streams.get())
				.as("权威快照已是 SUSPENDED 的挂起工具轮：不得再发一次上游请求（旧行为：2 次）")
				.isEqualTo(1);
			RunSnapshot after = this.store.get(RUN_ID);
			softly.assertThat(after.getStatus()).as("快照必须保持 SUSPENDED（等 resume 物化）")
				.isEqualTo(RunSnapshot.SUSPENDED);
			softly.assertThat(after.getInFlightToolCalls()).as("in-flight 不得被清空").hasSize(1);
			softly.assertThat(after.getMessageJson()).as("挂起轮的消息面不得被改写成完轮").hasSize(2);
			softly.assertThat(after.getFinalText()).as("不得写成功终局正文").isNull();
			softly.assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).as("待决条目原样保留")
				.isEqualTo(PendingToolCall.PENDING);
			softly.assertThat(wire.types()).as("不重试 ⇒ 没有普通 finish 的 done，也没有失败 error")
				.doesNotContain("done", "error");
			softly.assertThat(memoryCalls()).as("不得把 user/答案写进记忆（旧行为会 clear+add）").isEmpty();
		});
	}

	/**
	 * Fix A 边界（复审 193）：{@code emittedAny} 为真（已吐正文/思考）时，旧判据把
	 * {@code parkedToolRound} 绑在 ordinary-retryable 上（{@code retryable=false} ⇒ 该布尔为 false），
	 * 已持久化的挂起轮于是走 {@code fail()} 被写成 FAILED、并发出 error 终帧。
	 * 不变量：<b>挂起轮一旦 saveSuspended 落库，就与"能不能普通重试"无关</b> —— 任何尝试失败都交还 resume。
	 */
	@Test
	void emittedParkedToolRoundFailureAlsoHandsBackInsteadOfFailing() {
		PendingToolCall pending = pendingFrontendCall();
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			if (this.streams.incrementAndGet() > 1) {
				return Flux.just(chunk("重试的纯文本"), terminal("stop"));
			}
			RunSnapshot suspended = this.store.get(RUN_ID);
			suspended.setStatus(RunSnapshot.SUSPENDED);
			suspended.setInFlightToolCalls(List.of(pending));
			suspended.setAssistantContent("{\"toolCalls\":[{\"id\":\"" + CALL_ID + "\",\"name\":\"ui_click\"}]}");
			suspended.setMessageJson(
					this.codec.serializeAll(List.of(new SystemMessage("系统提示"), new UserMessage("点一下「选择文件」"))));
			this.store.save(suspended);
			this.store.putPending(RUN_ID, pending);
			// 已产出正文后失败：emittedAny=true ⇒ 旧判据 retryable=false 且 parkedToolRound=false ⇒ fail()
			return Flux.concat(Flux.just(chunk("半截正文")),
					Flux.error(new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
							"取消状态未知（Redis 读失败），工具执行在事件边界终止（runId=" + RUN_ID + "）")));
		});

		FrameWire wire = runTurn();

		assertSoftly(softly -> {
			softly.assertThat(this.streams.get()).as("不重试：只调 1 次上游").isEqualTo(1);
			RunSnapshot after = this.store.get(RUN_ID);
			softly.assertThat(after.getStatus()).as("挂起轮不得被 fail() 写成 FAILED")
				.isEqualTo(RunSnapshot.SUSPENDED);
			softly.assertThat(after.getInFlightToolCalls()).as("in-flight 不得被清空").hasSize(1);
			softly.assertThat(after.getFinalText()).as("不得写成功终局正文").isNull();
			softly.assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).as("待决条目原样保留")
				.isEqualTo(PendingToolCall.PENDING);
			softly.assertThat(terminalTypes(wire)).as("不发 done/error：交还 resume，而不是失败终态").isEmpty();
			softly.assertThat(wire.types()).as("已产出的半截正文帧仍在（不被失败终态抹掉）").contains("delta");
		});
	}

	/** 回归护栏：真正 RUNNING 的普通失败（无挂起轮）仍照旧重试 —— 修复不得把合法重试一起关掉。 */
	@Test
	void runningSnapshotStillRetriesOrdinaryFailures() {
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			if (this.streams.incrementAndGet() == 1) {
				return Flux.error(new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
						"取消状态未知（Redis 读失败）"));
			}
			return Flux.just(chunk("重试后的回答"), terminal("stop"));
		});

		FrameWire wire = runTurn();

		assertThat(this.streams.get()).as("RUNNING 快照 + 可重试失败 ⇒ 仍走第 2 次尝试").isEqualTo(2);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(wire.types()).contains("done");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private FrameWire runTurn() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("点一下「选择文件」")),
				out);
		return wire;
	}

	private static List<String> terminalTypes(FrameWire wire) {
		return wire.types().stream().filter(type -> "done".equals(type) || "error".equals(type)).toList();
	}

	/** 记忆面实际收到的调用（软断言用：不依赖 verify 的硬失败）。 */
	private List<String> memoryCalls() {
		return mockingDetails(this.chatMemory).getInvocations()
			.stream()
			.map(invocation -> invocation.getMethod().getName())
			.toList();
	}

	private static PendingToolCall pendingFrontendCall() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("ui_click");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setReq(Boolean.TRUE);
		pending.setAtMs(System.currentTimeMillis());
		return pending;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
