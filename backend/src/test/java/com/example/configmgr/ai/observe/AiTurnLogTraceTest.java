package com.example.configmgr.ai.observe;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一轮对话的<b>日志链路</b>用例（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>断言的是"<b>只凭日志就能还原一轮</b>"这件事本身，而不是某条语句的措辞：
 *
 * <table>
 * <caption>用例矩阵</caption>
 * <tr><td>L1 成功轮</td><td>{@code turn_start} → {@code upstream_first_byte} → {@code turn_end}
 *     三行必现且有序；{@code turn_end.status=done} 且字段齐（耗时/审批等待/工具次数/三维 token/est）</td></tr>
 * <tr><td>L2 runId 贯穿</td><td>同一轮的所有事件行都带同一个 runId（没有"MCD 漏绑成了 -"）</td></tr>
 * <tr><td>L3 失败轮</td><td>重试与失败都有结构化行：{@code turn_retry} + {@code turn_end.status=failed}</td></tr>
 * <tr><td>L4 续跑轮</td><td>{@code driveResumed} 同样出 {@code turn_start.resumed=true} 与 {@code turn_end}</td></tr>
 * <tr><td>L5 无正文</td><td><b>负断言</b>：用户的问句与模型的答复正文都不出现在任何事件行里</td></tr>
 * <tr><td>L6 MDC 收尾</td><td>轮结束后不留锚点（池线程不被下一轮串味）</td></tr>
 * </table>
 */
class AiTurnLogTraceTest {

	private static final String RUN_ID = "run-logtrace-1";

	private static final String SESSION_ID = "s-logtrace-1";

	private static final String USER_TEXT = "用户问句-不要出现在日志里";

	private static final String REPLY_TEXT = "模型答复正文-也不要出现";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	/** {@code chat()} 走 {@code ChatClient} 链：fluent 段用 RETURNS_SELF 桩，末端流由 {@link #upstream} 提供。 */
	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class,
			org.mockito.Answers.RETURNS_SELF);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final java.util.concurrent.atomic.AtomicReference<Flux<ChatResponse>> upstream = new java.util.concurrent.atomic.AtomicReference<>();

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel, this.chatClient);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.providerRegistry.target(any())).thenReturn(Optional.empty());
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> this.upstream.get());
		when(this.chatMemory.get(anyString())).thenReturn(List.of());
		when(this.contextBuilder.buildContextMessage(any(AiContext.class))).thenReturn("");
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "logtrace-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService,
				this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	// ── L1 / L2 / L5 / L6：成功轮的完整链路 ──

	@Test
	void successfulTurnEmitsStartFirstByteAndEndInOrder() {
		this.upstream.set(Flux.just(chunk(REPLY_TEXT), terminal("stop")));

		runTurn();

		List<String> names = eventNames();
		assertThat(names).as("一轮至少要有起点、首包、终点三行；实收=" + names)
			.contains("turn_start", "upstream_first_byte", "turn_end");
		assertThat(indexOf("turn_start")).isLessThan(indexOf("upstream_first_byte"));
		assertThat(indexOf("upstream_first_byte")).isLessThan(indexOf("turn_end"));

		String start = line("turn_start");
		assertThat(start).contains("runId=" + RUN_ID).contains("sessionId=" + SESSION_ID)
			.contains("model=test-model").contains("attempt=1").contains("resumed=false")
			.contains("resumeReason=none");

		String firstByte = line("upstream_first_byte");
		assertThat(firstByte).contains("attempt=1").containsPattern("ms=\\d+");

		String end = line("turn_end");
		assertThat(end).contains("status=done").contains("attempts=1").contains("gateWaitMs=").contains("activeMs=")
			.contains("totalMs=").contains("toolCalls=0").contains("inputTokens=").contains("outputTokens=")
			.contains("totalTokens=").contains("est=").contains("chars=");
	}

	// ── L7：turn_end 的跨段/排队/可信度字段（日志最小补齐批） ──

	/**
	 * 一轮的"慢"分三段：排队（{@code turn_accepted → turn_start}）、机器（{@code activeMs}）、人
	 * （{@code gateWaitMs}）。缺 queueMs 时"提交后迟迟没开始"会被算进机器耗时，误判成模型慢。
	 *
	 * <p>{@code runTotalMs} 从 run 创建算起，跨挂起/续跑两段 —— 与 {@code totalMs}（本段墙钟）分工不同：
	 * 前者回答"这个 run 从我点发送到现在多久"，后者回答"这一次驱动跑了多久"。
	 */
	@Test
	void turnEndCarriesQueueAndRunTotalAndProviderAndUsageCredibility() {
		this.upstream.set(Flux.just(chunk(REPLY_TEXT), terminal("stop")));
		AiTrace.markAccepted();

		runTurn();

		String end = line("turn_end");
		assertThat(end).as("排队耗时（turn_accepted → turn_start）").containsPattern("queueMs=\\d+");
		assertThat(end).as("跨段总时长（run 创建 → 终态）").containsPattern("runTotalMs=\\d+");
		assertThat(end).as("本轮 provider（与 turn_start 同源）").contains("provider=test-provider");
		assertThat(end).as("usage 可信度：采到的 usage 帧数 / 判脏帧数").containsPattern("frames=\\d+")
			.containsPattern("dirty=\\d+");
		assertThat(end).as("终态所在尝试序号").contains("attempt=1");
	}

	/**
	 * 没有 {@code turn_accepted}（直接驱动 / 续跑）时 queueMs 必须显式写 -1 —— "没测到"与"排队 0ms"
	 * 是两件事，不能都渲染成 0。
	 */
	@Test
	void turnEndWithoutAnAcceptedMarkReportsQueueMsAsUnknown() {
		this.upstream.set(Flux.just(chunk(REPLY_TEXT), terminal("stop")));

		runTurn();

		assertThat(line("turn_end")).contains("queueMs=-1");
	}

	/**
	 * 重试行的三问：这次尝试花了多久（{@code attemptMs}）、首包到了没有（{@code firstByteMs}，
	 * -1 = 一个分片都没到）、这次尝试烧了多少 token（{@code inputTokens}/{@code outputTokens}）。
	 */
	@Test
	void retryCarriesAttemptDurationFirstByteAndPerAttemptTokens() {
		this.upstream.set(Flux.error(new IllegalStateException("upstream stream error")));

		runTurn();

		String retry = line("turn_retry");
		assertThat(retry).containsPattern("attemptMs=\\d+").containsPattern("firstByteMs=-1|firstByteMs=\\d+")
			.containsPattern("inputTokens=\\d+").containsPattern("outputTokens=\\d+");
	}

	@Test
	void everyEventOfATurnCarriesTheSameRunId() {
		this.upstream.set(Flux.just(chunk(REPLY_TEXT), terminal("stop")));

		runTurn();

		List<String> lines = eventLines();
		assertThat(lines).isNotEmpty();
		assertThat(lines).allSatisfy(line -> assertThat(line)
			.as("runId 贯穿同一轮（%- 表示 MDC 漏绑）: %s", line)
			.contains("runId=" + RUN_ID)
			.contains("sessionId=" + SESSION_ID));
	}

	@Test
	void eventLinesNeverCarryConversationContent() {
		this.upstream.set(Flux.just(chunk(REPLY_TEXT), terminal("stop")));

		runTurn();

		assertThat(eventLines()).isNotEmpty();
		assertThat(eventLines()).allSatisfy(line -> assertThat(line)
			.as("对话正文不得进日志（对话数据不落库的同一裁决）")
			.doesNotContain(USER_TEXT)
			.doesNotContain(REPLY_TEXT));
	}

	@Test
	void turnDoesNotLeaveTheAnchorOnTheCallingThread() {
		this.upstream.set(Flux.just(chunk(REPLY_TEXT), terminal("stop")));

		runTurn();

		assertThat(MDC.get(AiTrace.RUN_ID)).as("轮结束后不留锚点").isNull();
		assertThat(MDC.get(AiTrace.SESSION_ID)).isNull();
	}

	// ── L3：失败轮 ──

	@Test
	void failedTurnEmitsRetryAndFailedTurnEnd() {
		this.upstream.set(Flux.error(new IllegalStateException("upstream stream error")));

		runTurn();

		assertThat(eventNames()).contains("turn_retry", "turn_end");
		assertThat(line("turn_end")).contains("status=failed").containsPattern("code=[A-Z_]+");
		assertThat(line("turn_retry")).contains("attempt=1").contains("nextAttempt=2");
	}

	// ── L3b：其余终态出口同样有 turn_end（按 runId grep 一定有收尾行） ──

	@Test
	void emptyReplyEndsWithTurnEndCarryingTheEmptyReplyCode() {
		this.upstream.set(Flux.just(terminal("stop")));

		runTurn();

		assertThat(line("turn_end")).contains("status=failed")
			.contains("code=" + ResilientChatService.EMPTY_REPLY_CODE);
	}

	@Test
	void blockedOutputEndsWithTurnEndCarryingTheBlockCode() {
		String head = "-----BEGIN " + "RSA PRIVATE KEY-----";
		String foot = "-----END " + "RSA PRIVATE KEY-----";
		String body = "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun";
		this.upstream.set(Flux.just(chunk("这是配置：\n" + head + "\n" + body + "\n" + foot), terminal("stop")));

		runTurn();

		assertThat(line("turn_end")).contains("status=blocked")
			.contains("code=" + ResilientChatService.SENSITIVE_OUTPUT_BLOCKED);
	}

	// ── L4：续跑轮 ──
	@Test
	void resumedTurnIsMarkedResumedAndStillEndsWithTurnEnd() {
		runResumedTurn();

		assertThat(line("turn_start")).contains("resumed=true").contains("model=test-model")
			.contains("resumeReason=reattach");
		assertThat(line("turn_end")).contains("status=done");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	/** 跑一轮（首轮入口 {@code chat()}：{@code ChatClient} 链 → 上游流由 {@link #upstream} 提供）。 */
	private void runTurn() {
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, USER_TEXT, null, null, null), this.registry.of(RUN_ID));
	}

	/** 续跑入口（{@code runtime.chatModel().stream(...)} 直连，不经 ChatClient）。 */
	private void runResumedTurn() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk(REPLY_TEXT), terminal("stop")));
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage(USER_TEXT)),
				this.registry.of(RUN_ID));
	}

	/** 本轮产生的事件行（顺序 = 产生顺序）。 */
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

	private List<String> eventNames() {
		return eventLines().stream().map(line -> line.substring("event=".length()).split(" ")[0]).toList();
	}

	private String line(String name) {
		return eventLines().stream().filter(line -> line.startsWith("event=" + name + " ")).findFirst()
			.orElseThrow(() -> new AssertionError("缺少事件行 event=" + name + "；实收 = " + eventLines()));
	}

	private int indexOf(String name) {
		List<String> names = eventNames();
		int index = names.indexOf(name);
		assertThat(index).as("缺少事件 event=" + name).isGreaterThanOrEqualTo(0);
		return index;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
