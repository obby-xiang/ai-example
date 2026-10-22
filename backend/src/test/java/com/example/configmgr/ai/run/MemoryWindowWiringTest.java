package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆窗口的<b>两个调用点</b>（记忆窗口批）：首轮 {@code chat()} 与续跑 {@code ResumeService#resume}。
 *
 * <p>
 * 为什么必须两处都测：首轮与续跑是两套历史来源（{@code chat:mem:} vs {@code ai:run:} 快照）。
 * 窗口只挂在首轮 ⇒ 续跑会把整份重建历史（挂起期越长越大）原样发给模型，而这条路径在人工冒烟里
 * 几乎看不出来（挂起→续跑是低频路径）。断言三件事：
 * <ol>
 * <li>越预算的历史<b>真的变短了</b>（不是"算了一下但没生效"）；</li>
 * <li>首轮把窗口<b>写回记忆</b> —— 官方 advisor 从 {@code ChatMemory} 读历史，不写回就等于
 * "快照里是短的、模型看到的是长的"（SM-01 同族分叉）；</li>
 * <li>{@code event=memory_window} 如实带上 before/after，未越预算时一行不发。</li>
 * </ol>
 */
class MemoryWindowWiringTest {

	private static final String RUN_ID = "run-mw-wiring";

	private static final String SESSION_ID = "s-mw-wiring";

	// ── 首轮 harness ────────────────────────────────────────────────────────

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ProviderRuntime runtime = new ProviderRuntime(7L, "deepseek", "test-model",
			"http://placeholder", "/chat/completions", "7|fp|u|p", null, null, this.chatModel, this.chatClient);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	// ── 续跑 harness ────────────────────────────────────────────────────────

	private final RunRegistry resumeRegistry = mock(RunRegistry.class);

	private final ResilientChatService resumedChatService = mock(ResilientChatService.class);

	private final com.example.configmgr.ai.gate.SpToolCallingManager toolCallingManager = mock(
			com.example.configmgr.ai.gate.SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final org.springframework.data.redis.core.StringRedisTemplate redis = mock(
			org.springframework.data.redis.core.StringRedisTemplate.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private ResumeService resumeService;

	private SseChatEmitter emitter;

	private Logger traceLogger;

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "mw-wiring-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.contextBuilder.buildContextMessage(any())).thenReturn("workspace");
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(this.runtime);
		when(this.providerRegistry.allowUserParams()).thenReturn(false);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);

		this.emitter = mock(SseChatEmitter.class);
		this.resumeService = new ResumeService(this.store, this.resumeRegistry, this.codec, this.resumedChatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

		this.traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);
		this.appender = new ListAppender<>();
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
		this.watchdogExecutor.shutdownNow();
	}

	// ── 首轮 ────────────────────────────────────────────────────────────────

	@Test
	void chatTrimsTheOverBudgetMemoryAndWritesItBack() {
		this.properties.getMemory().setTokenBudget(6_000);
		// 下限放到 2：本用例要断言"预算驱动的淘汰确实发生"，下限的护栏由 MemoryWindowTest 覆盖
		this.properties.getMemory().setMinMessages(2);
		List<Message> memory = turns(10, 1700);
		when(this.chatMemory.get(SESSION_ID)).thenReturn(memory);
		stubFluentChain();

		runChat();

		// ① 写回记忆的窗口 = 6 条（7 轮被整轮淘汰），且 token 已回到预算内
		ArgumentCaptor<List<Message>> captured = ArgumentCaptor.forClass(List.class);
		verify(this.chatMemory, atLeastOnce()).add(eq(SESSION_ID), captured.capture());
		List<Message> windowed = captured.getAllValues()
			.stream()
			.filter(list -> list.size() == 6)
			.findFirst()
			.orElseThrow(() -> new AssertionError("没有 6 条的写回：记忆窗口没生效；实收 = "
					+ captured.getAllValues().stream().map(List::size).toList()));
		assertThat(ContextEstimator.estimateMessages(windowed)).isLessThanOrEqualTo(6_000);
		assertThat(windowed.get(0).getMessageType()).isEqualTo(MessageType.USER);

		// ② 写回发生在"模型发请求之前"：官方 advisor 从 ChatMemory 读历史，
		//    先请求后写回等于这一轮模型看到的还是长的那份（顺序即语义）
		org.mockito.InOrder order = org.mockito.Mockito.inOrder(this.chatMemory, this.chatClient);
		order.verify(this.chatMemory).add(eq(SESSION_ID), org.mockito.ArgumentMatchers.anyList());
		order.verify(this.chatClient).prompt();

		// ③ 事件如实报 before/after
		String line = eventLines().stream()
			.filter(item -> item.startsWith("event=memory_window "))
			.findFirst()
			.orElseThrow(() -> new AssertionError("缺少事件行 event=memory_window；实收 = " + eventLines()));
		assertThat(line).contains("before=20").contains("after=6").contains("droppedTurns=7").contains("budget=6000");
	}

	@Test
	void chatUnderBudgetLeavesTheMemoryAlone() {
		when(this.chatMemory.get(SESSION_ID)).thenReturn(turns(2, 50));
		stubFluentChain();

		runChat();

		assertThat(eventLines()).as("未越预算不该有 memory_window 行").noneMatch(line -> line.startsWith("event=memory_window "));
		// 只剩轮末收敛那一次写（窗口没动 ⇒ 不额外写回一条 4 条的窗口）
		verify(this.chatMemory, never()).add(eq(SESSION_ID),
				org.mockito.ArgumentMatchers.<List<Message>>argThat(list -> list != null && list.size() == 4));
	}

	// ── 续跑 ────────────────────────────────────────────────────────────────

	@Test
	void resumeTrimsTheRebuiltHistoryBeforeHandingOff() {
		this.properties.getMemory().setTokenBudget(6_000);
		this.properties.getMemory().setMinMessages(2);
		seedSuspendedRun(turns(10, 1700));
		when(this.resumeRegistry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.resumeService.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		AssertCaptor captor = captureHandedOffHistory();
		assertThat(captor.history).hasSize(6);
		assertThat(ContextEstimator.estimateMessages(captor.history)).isLessThanOrEqualTo(6_000);
		assertThat(captor.history.get(0).getMessageType()).isEqualTo(MessageType.USER);
		assertThat(result.body()).as("续跑侧也要如实报告窗口（运维/取证面）")
			.containsEntry("memoryWindowTrimmed", true);
		assertThat(eventLines()).anyMatch(line -> line.startsWith("event=memory_window "));
	}

	@Test
	void resumeUnderBudgetHandsOffTheFullHistory() {
		seedSuspendedRun(turns(2, 50));
		when(this.resumeRegistry.of(RUN_ID)).thenReturn(this.emitter);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.resumeService.resume(RUN_ID);

		assertThat(captureHandedOffHistory().history).hasSize(4);
		assertThat(result.body()).containsEntry("memoryWindowTrimmed", false);
		assertThat(eventLines()).noneMatch(line -> line.startsWith("event=memory_window "));
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private record AssertCaptor(List<Message> history) {
	}

	private AssertCaptor captureHandedOffHistory() {
		ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
		verify(this.resumedChatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(), captor.capture(), any());
		return new AssertCaptor(new ArrayList<>(captor.getValue()));
	}

	private void seedSuspendedRun(List<Message> history) {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setModelId("test-model");
		snapshot.setMessageJson(this.codec.serializeAll(history));
		this.store.save(snapshot);
	}

	private void runChat() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);
	}

	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("好了"),
				terminal()));
	}

	private static List<Message> turns(int count, int cjkChars) {
		String text = "问" + "长".repeat(cjkChars - 1);
		List<Message> messages = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			messages.add(new UserMessage(text));
			messages.add(new AssistantMessage(text));
		}
		return messages;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().model("test-model").build());
	}

	private List<String> eventLines() {
		List<String> out = new ArrayList<>();
		for (ILoggingEvent event : new ArrayList<>(this.appender.list)) {
			String message = event.getFormattedMessage();
			if (message.startsWith("event=")) {
				out.add(message);
			}
		}
		return out;
	}

}
