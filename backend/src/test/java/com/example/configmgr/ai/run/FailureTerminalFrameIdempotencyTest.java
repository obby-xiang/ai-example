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
 * <b>失败终帧的幂等</b>（2026-10-29；返修后：**每次驱动**至多一条终帧）。
 *
 * <h2>被钉住的缺陷</h2>
 * 三条 DONE/FAILED 失败出口（{@code fail()} 含内容过滤/各类上游违规、{@code emptyReply()}、环外异常/续跑失败）
 * 原先不做终帧判定：同一驱动里已经落过终帧时，再补一条 {@code error} ⇒ 归档里 done + error 并存
 * （重放与前端收敛按第一条，第二条既误导"失败了"又破坏"终帧是本轮最后一帧"的帧序不变量）。
 *
 * <h2>判据（返修 I-2 后按<b>每次驱动</b>）</h2>
 * 判据是 {@link SseChatEmitter#terminalFrameArchivedSinceDriveStart()}：驱动入口记一次归档序号下界，
 * 只认 {@code seq > 下界} 的 done/error。**不能**用整轮归档（{@code terminalFrameArchived()}）——
 * 同一 runId 会被驱动多次（{@code retry-model-step}），上一次的 error 会把本次失败整条吞掉；
 * 本类下半部分的两条用例正是那个反向证据。
 *
 * <p><b>不用 AtomicBoolean</b>：进程内布尔只挡自己，也不解决"多次驱动"的代次问题；
 * 判据必须落在共享归档上（下界是本驱动入口的归档末号）。
 *
 * <p><b>边界（如实）</b>：这是"读 → 判 → 发"的顺序判据，**不**提供跨实例并发下的"恰好一条终帧"
 * （那需要原子终帧位，本批未做）。
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
	 * <b>返修 I-2 后的口径</b>：上一次驱动留下的 error <b>不属于</b>本次驱动 ⇒ 本次失败出口
	 * <b>必须</b>照发自己的终帧（这正是 {@code retry-model-step} 二次驱动失败的场景）。
	 *
	 * <p>旧口径（"归档里有没有任何终帧"）会在这里把本次的 error 与 {@code textEnd} 一起吞掉 ——
	 * 本用例在返修前是反向断言（{@code noneMatch(error)}），返修后倒转，锁的是同一条判据的另一面。
	 */
	@Test
	void aFailureStillEmitsItsOwnErrorWhenOnlyAnEarlierDriveArchivedOne() {
		seedArchivedTerminal("error");
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("上游握手失败（模拟）")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(frames).as("上一次驱动的 error 不得让本次失败静默").anyMatch(frame -> "error".equals(frame.get("type")));
		assertThat(terminalTypes(this.store.events(RUN_ID)))
			.as("归档里 = 上一次驱动那条 + 本次驱动这条")
			.containsExactly("error", "error");
	}

	/** 相邻对照：上一次驱动落的是 done，本次空回复失败同样要发自己的 error（不得被旧的 done 吞掉）。 */
	@Test
	void anEmptyReplyStillEmitsItsOwnErrorWhenAnEarlierDriveArchivedADone() {
		seedArchivedTerminal("done");
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(emptyChunk(), terminal("stop")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(frames).as("上一次驱动的 done 不得吞掉本次空回复的失败终帧")
			.anyMatch(frame -> "error".equals(frame.get("type")));
		assertThat(terminalTypes(this.store.events(RUN_ID))).containsExactly("done", "error");
		assertThat(this.store.get(RUN_ID).getStatus()).as("本次失败终态照旧落库").isEqualTo(RunSnapshot.FAILED);
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
