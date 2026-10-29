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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>失败终帧的幂等</b>（2026-10-29）：每 run 至多一条终帧。
 *
 * <h2>被钉住的缺陷</h2>
 * 取消收尾与"终态不可确认"两条出口已经按<b>归档里有没有终帧</b>判幂等，但三条
 * DONE/FAILED 失败出口没有：{@code fail()}（含内容过滤/各类上游违规）、{@code emptyReply()}、
 * 以及环外异常/续跑失败。缺了这一步，下列现场会落出<b>第二条终帧</b>：
 * <ul>
 * <li>同一轮的另一个驱动方（接管方/交棒执行体）已经落了终态与终帧，本线程还在跑；
 *     它随后失败，再补一条 {@code error} ⇒ 归档里 done + error 并存（重放与前端收敛按第一条，
 *     第二条既误导"失败了"又破坏"终帧是本轮最后一帧"的帧序不变量）；</li>
 * <li>写门/投递丢帧后重试（同一进程的第二次收尾）。</li>
 * </ul>
 *
 * <h2>为什么不用 AtomicBoolean</h2>
 * 进程内的布尔只能挡住<b>同一个实例</b>的重复发射；这里要挡的是"同一轮在<b>共享归档</b>里
 * 已经有终帧"。判据必须是归档本身（{@link SseChatEmitter#terminalFrameArchived()}）——
 * 跨实例、跨线程、跨进程重启都成立。
 */
class FailureTerminalFrameIdempotencyTest {

	private static final String RUN_ID = "run-terminal-idempotent";

	private static final String SESSION_ID = "s-terminal-idempotent";

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
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "terminal-idempotent-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("问题"))));
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	/**
	 * 归档里已有<b>别人落的</b> error 终帧 ⇒ 本线程的失败出口不得再补一条。
	 *
	 * <p>证红方式：去掉 {@code failLocked} 发 error 前的归档终帧判定 ⇒ 归档里出现两条 error。
	 */
	@Test
	void aFailureDoesNotAppendASecondErrorWhenTheTerminalIsAlreadyArchived() {
		seedArchivedTerminal("error");
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("上游握手失败（模拟）")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(frames).as("已有终帧 ⇒ 本线程一条 error 都不发").noneMatch(frame -> "error".equals(frame.get("type")));
		assertThat(terminalTypes(this.store.events(RUN_ID))).as("归档里终帧仍然只有别人那一条")
			.containsExactly("error");
	}

	/**
	 * 归档里已有<b>别人落的</b> done 终帧 ⇒ 本线程的空回复失败出口不得把 done 翻成 error。
	 */
	@Test
	void anEmptyReplyDoesNotAppendASecondErrorWhenTheTerminalIsAlreadyArchived() {
		seedArchivedTerminal("done");
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(emptyChunk(), terminal("stop")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(frames).as("已有 done ⇒ 不得补空回复的 error（一轮两条终帧）")
			.noneMatch(frame -> "error".equals(frame.get("type")) || "done".equals(frame.get("type")));
		assertThat(terminalTypes(this.store.events(RUN_ID))).as("归档里仍只有那一条 done").containsExactly("done");
	}

	/** 相邻对照：归档里<b>没有</b>终帧时，失败照旧恰好一条 error（幂等不得把正常失败关掉）。 */
	@Test
	void aFreshFailureStillEmitsExactlyOneError() {
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("上游握手失败（模拟）")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(frames).as("没有终帧 ⇒ 失败出口照旧发 error").anyMatch(frame -> "error".equals(frame.get("type")));
		assertThat(terminalTypes(this.store.events(RUN_ID))).as("且恰好一条").containsExactly("error");
		assertThat(this.store.get(RUN_ID).getStatus()).as("失败终态照旧落库").isEqualTo(RunSnapshot.FAILED);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private List<Map<String, Object>> runTurn() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("问题")), out);
		return wire.frames();
	}

	/** 铺一条"另一个驱动方已经落好"的终帧（start + 终帧，seq 1/2）。 */
	private void seedArchivedTerminal(String terminalType) {
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID),
				FrameContract.fixture(terminalType, 2L, "runId", RUN_ID, "code", "AI_UPSTREAM_ERROR"));
	}

	private static List<String> terminalTypes(List<Map<String, Object>> frames) {
		List<String> types = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				types.add(type);
			}
		}
		return types;
	}

	private static ChatResponse emptyChunk() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").build(),
						ChatGenerationMetadata.builder().finishReason(finishReason).build())),
				ChatResponseMetadata.builder().model("model").build());
	}

}
