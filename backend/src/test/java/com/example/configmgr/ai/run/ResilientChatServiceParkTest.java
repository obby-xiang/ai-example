package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>park 的控制流口径</b>：{@link RunParkedException} 从工具挂起点穿出模型流时，
 * {@link ResilientChatService} 必须<b>直接结束本次执行</b> —— 不发终帧、不判失败、不重试、
 * 不把快照写成终态。
 *
 * <p>为什么不能按普通异常处理：那会把"合法的挂起态（等人）"变成"这一轮失败了"，
 * 决策到达后 ResumeService 的重建依据（SUSPENDED + pending）就没了。
 *
 * <p>证红：把 {@code runAttempts} 里的 park 分支删掉 ⇒ 本用例变成 error 帧 + FAILED（红）。
 */
class ResilientChatServiceParkTest {

	private static final String RUN_ID = "run-service-park";

	private static final String SESSION_ID = "s-service-park";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "prov", "model", "http://localhost",
			"/chat/completions", "k", null, null, this.chatModel, mock(ChatClient.class));

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		// 真实 park 现场：saveSuspended 已把快照写成 SUSPENDED（park 只负责交还线程）
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void parkedTurnEndsSilentlyWithoutTerminalFrameOrRetry() {
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new RunParkedException(RUN_ID, "call-1", "CONFIRM")));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), out);

		assertThat(wire.types()).as("park 不发终帧、不补 error（客户端断开时也没人收）")
			.doesNotContain("done", "error");
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).as("快照保持 SUSPENDED（合法恢复态）")
			.isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(this.store.get(RUN_ID).getError()).as("park 不是失败").isNull();
		verify(this.beacon).clear(RUN_ID);
		verify(this.cancellations).unregister(RUN_ID);
	}

	/**
	 * <b>I1</b>：park 不得回写状态 —— {@code saveSuspended} 早已把快照写成 SUSPENDED，
	 * 而续跑轮在交棒前被置 RUNNING；旧实现会在 parkTurn 里把 RUNNING 改回 SUSPENDED，
	 * 那是"用一条无效回写掩盖另一个问题"（真实 park 现场根本走不到），也会让
	 * "RUNNING 但无人驱动"的诊断信息被抹掉。
	 */
	@Test
	void parkDoesNotWriteBackSnapshotStatus() {
		RunSnapshot running = this.store.get(RUN_ID);
		running.setStatus(RunSnapshot.RUNNING);
		this.store.save(running);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new RunParkedException(RUN_ID, "call-1", "CONFIRM")));
		SseChatEmitter out = this.registry.of(RUN_ID);

		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), out);

		assertThat(this.store.get(RUN_ID).getStatus())
			.as("park 只交还线程，不改快照状态（saveSuspended 负责 SUSPENDED）")
			.isEqualTo(RunSnapshot.RUNNING);
	}

	/** 非 park 的正常错误仍按失败收尾（本批没有把失败路径一起改掉）。 */
	@Test
	void ordinaryFailureStillEndsWithAnErrorFrame() {
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("boom")));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), out);

		assertThat(wire.types()).contains("error");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

}
