package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>L-98</b>：思考全文的<b>写入面</b> —— 落定（{@link ResilientChatService} 的 finish）与
 * 取消半截（cancelTerminal）两条路径，都必须把本轮的 reasoning 全文随 assistant 消息写进
 * 快照与对话记忆（metadata 键 {@value ResilientChatService#REASONING_METADATA_KEY}）。
 *
 * <p>
 * 为什么两条路径都要写：记忆是下一轮请求的唯一历史来源。只写正文不写思考 ⇒ 下一轮
 * {@code ReasoningAwareOpenAiChatModel} 拿不到 {@code reasoning_content}（DeepSeek 带 tools 直接 400）；
 * 取消路径漏写 ⇒ 用户停一次之后，那条历史在后续每一轮都是"缺思考"的。
 *
 * <p>
 * 体积口径：<b>不做人造截断</b>，思考全文随会话记忆走（Redis 会话 TTL 24h 兜底）。
 */
class ReasoningMemoryConvergenceTest {

	private static final String RUN_ID = "run-reasoning-memory";

	private static final String SESSION_ID = "s-reasoning-memory";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel,
			mock(ChatClient.class));

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
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "l98-reasoning-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
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

	// ── ① 落定路径 ────────────────────────────────────────────────────────────

	@Test
	void finishWritesTheWholeReasoningIntoMemoryAndSnapshot() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(reasoning("先想一步。"), reasoning("再想一步。"),
				chunk("答案是 2"), terminal("stop")));

		runTurn();

		AssistantMessage assistant = lastMemoryAssistant();
		assertThat(assistant.getText()).isEqualTo("答案是 2");
		assertThat(assistant.getMetadata()).as("思考全文（两片拼接，不截断）随 assistant 消息进记忆")
			.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "先想一步。再想一步。");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(lastSnapshotAssistant().getMetadata()).as("快照与记忆同源")
			.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "先想一步。再想一步。");
	}

	@Test
	void finishKeepsLongReasoningUntruncated() {
		String longReasoning = "长思考。".repeat(2000);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(reasoning(longReasoning), chunk("好"), terminal("stop")));

		runTurn();

		assertThat(lastMemoryAssistant().getMetadata())
			.as("体积口径：不做人造截断（" + longReasoning.length() + " 字全文保留）")
			.containsEntry(ResilientChatService.REASONING_METADATA_KEY, longReasoning);
	}

	@Test
	void finishWithoutReasoningDoesNotFabricateTheKey() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("直接回答"), terminal("stop")));

		runTurn();

		assertThat(lastMemoryAssistant().getMetadata()).doesNotContainKey(ResilientChatService.REASONING_METADATA_KEY);
	}

	// ── ② 取消半截路径 ────────────────────────────────────────────────────────

	@Test
	void cancelTerminalWritesThePartialReasoningAndKeepsTheStoppedMark() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(reasoning("想了第一步，"), chunk("半截")),
				Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "本轮对话已被取消"))));

		runTurn();

		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		AssistantMessage assistant = lastMemoryAssistant();
		assertThat(assistant.getText()).isEqualTo("半截");
		assertThat(assistant.getMetadata()).as("取消半截：stopped 与思考全文并存")
			.containsEntry(ResilientChatService.STOPPED_KEY, true)
			.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "想了第一步，");
	}

	// ── ③ 记忆整体覆盖写路径（convergeMemory）不许吃掉元数据 ──────────────────

	@Test
	void convergeMemoryKeepsPreviousRoundsReasoningWhileAppendingTheNewOne() {
		RunSnapshot seeded = this.store.get(RUN_ID);
		seeded.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("上一轮的问题"),
				AssistantMessage.builder()
					.content("上一轮的回答")
					.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "上一轮的思考"))
					.build())));
		this.store.save(seeded);

		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(reasoning("这一轮的思考"), chunk("这一轮的回答"), terminal("stop")));

		runTurn();

		List<Message> written = memoryWrite();
		assertThat(written).hasSize(3);
		assertThat(written.get(1).getMetadata()).as("历史轮次的思考不因整体覆盖写而丢失")
			.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "上一轮的思考");
		assertThat(written.get(2).getMetadata()).containsEntry(ResilientChatService.REASONING_METADATA_KEY,
				"这一轮的思考");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private void runTurn() {
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("1+1 为什么等于 2？")),
				out);
		wire.frames();
	}

	@SuppressWarnings("unchecked")
	private List<Message> memoryWrite() {
		ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
		verify(this.chatMemory).add(eq(SESSION_ID), captor.capture());
		return captor.getValue();
	}

	private AssistantMessage lastMemoryAssistant() {
		List<Message> written = memoryWrite();
		Message last = written.get(written.size() - 1);
		assertThat(last).isInstanceOf(AssistantMessage.class);
		return (AssistantMessage) last;
	}

	private AssistantMessage lastSnapshotAssistant() {
		List<Message> snapshot = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
		return (AssistantMessage) snapshot.get(snapshot.size() - 1);
	}

	private static ChatResponse reasoning(String text) {
		return new ChatResponse(List.of(new Generation(
				AssistantMessage.builder().content("").properties(Map.of("reasoningContent", text)).build())));
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
