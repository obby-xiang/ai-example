package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>早取消的持久面</b>（2026-10-29）：首轮压缩期间收到取消时，终态不能只有一条帧。
 *
 * <h2>被钉住的缺陷</h2>
 * 旧实现在"压缩后、建快照前"复查到取消就<b>直接发终帧</b>（{@code done{cancelled:true}}），
 * 不建立任何快照。帧归档能回放出终帧，但"这一轮被取消了"没有持久面：runId 的权威状态仍然是
 * "不存在"，重挂/刷新只能靠归档猜结局。
 *
 * <h2>新口径（已审方案）</h2>
 * <ol>
 * <li>即使模型一次都没被调用，也要落一份<b>最小 CANCELLED 快照</b>（runId / sessionId / context /
 *     status / createdAt 等既有字段）；</li>
 * <li><b>不写</b>助手正文、工具调用/工具结果 —— 模型从未被调用，不得伪造任何轮内产出；</li>
 * <li>模型调用次数为 <b>0</b>，终帧<b>恰好一条</b> {@code done{cancelled:true}}；</li>
 * <li>重挂（同一归档、新连接）能读到该取消终帧，并按终态正常收流。</li>
 * </ol>
 *
 * <p>证红方式：删掉 {@code chat()} 早取消分支里的快照创建 ⇒ 第一条用例的
 * {@code store.get(runId)} 为 null（旧行为）而红；把终帧改成额外再发一条 ⇒ 第二条用例的
 * "恰好一条 done"红。
 */
class EarlyCancelDuringCompactionSnapshotTest {

	private static final String RUN_ID = "run-early-cancel";

	private static final String SESSION_ID = "s-early-cancel";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel, this.chatClient);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final InMemoryContextStateStore contextStore = new InMemoryContextStateStore();

	private final ContextCompactor compactor = new ContextCompactor(this.properties,
			new ContextLimitResolver(this.properties), this.contextStore);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "early-cancel-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		ReflectionTestUtils.setField(this.service, "contextCompactor", this.compactor);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		// 记忆非空：压缩阶段真实发生（空历史会让 compactBeforeRequest 提前返回）
		when(this.chatMemory.get(SESSION_ID))
			.thenReturn(new ArrayList<>(List.of(new UserMessage("你好"), new AssistantMessage("你好呀"))));
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	/**
	 * 压缩期取消 ⇒ 最小 CANCELLED 快照；零模型调用；恰好一条 {@code done{cancelled:true}}。
	 */
	@Test
	void cancelDuringCompactionSavesAMinimalCancelledSnapshotWithoutAnyModelCall() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot).as("取消也必须有持久面：runId 的权威状态不能只是'不存在'").isNotNull();
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(snapshot.isCancelled()).isTrue();
		assertThat(snapshot.getRunId()).isEqualTo(RUN_ID);
		assertThat(snapshot.getSessionId()).isEqualTo(SESSION_ID);
		assertThat(snapshot.getCreatedAtMs()).as("createdAt 是既有必要字段").isPositive();
		assertThat(snapshot.getContext()).as("context 随快照落盘（续跑/披露的既有输入）").isNotNull();

		assertThat(snapshot.getAssistantContent()).as("模型从未被调用：不得写助手正文").isNull();
		assertThat(snapshot.getFinalText()).as("没有交付正文").isNull();
		assertThat(snapshot.getInFlightToolCalls()).as("没有工具调用/工具结果").isEmpty();

		verify(this.chatClient, never()).prompt();
		verify(this.chatModel, never()).stream(any(Prompt.class));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).as("取消轮不打开正文段、不发失败帧")
			.doesNotContain("message_start", "delta", "error");
		assertThat(FrameContract.oneOfType(frames, "done")).as("终帧恰好一条（oneOfType 自带唯一性断言），且是取消终帧")
			.containsEntry("cancelled", true);
	}

	/**
	 * 重挂（新连接、同一归档）：回放能读到唯一的取消终帧，回放结果标记为"已读到终帧"⇒ 正常收流。
	 */
	@Test
	void reattachAfterAnEarlyCancelReplaysTheSingleCancellationTerminal() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);

		// 另一条连接（同一份外置状态）：这就是 GET /api/ai/events/{runId} 的回放路径
		SseChatEmitter reattached = new SseChatEmitter(RUN_ID, this.store, this.mapper);
		FrameWire wire = new FrameWire();
		SseChatEmitter.ReplayResult plan = reattached.replayToWithStatus(wire.emitter(), null);

		assertThat(plan.staged()).as("重挂必须能补到帧").isPositive();
		assertThat(plan.terminalFrame()).as("回放读到终帧 ⇒ 客户端可以收流（不悬挂）").isTrue();
		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.oneOfType(frames, "done")).as("重挂读到的是那一条取消终帧")
			.containsEntry("cancelled", true);
		assertThat(FrameContract.type(frames.get(frames.size() - 1))).as("终帧是回放末帧").isEqualTo("done");
		assertThat(reattached.terminalFrameArchived()).isEqualTo(Boolean.TRUE);
	}

}
