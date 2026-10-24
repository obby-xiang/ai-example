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
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆边界的生产写入面：{@link ResilientChatService} 注入 {@link ChatMemoryRepository} 后，
 * 整窗覆盖写<b>直落 {@code saveAll}</b> —— 不经 {@code MessageWindowChatMemory.add} 的追加/合并，
 * 也不再先 {@code clear}。
 *
 * <p>四条锁：
 * <ol>
 * <li>普通收尾：一次 {@code saveAll(整窗)}，{@link ChatMemory} 的 {@code clear/add} 零调用；</li>
 * <li>{@code saveAll} 失败：旧历史保持可读（staging + RENAME 的失败安全），本轮仍正常收尾；</li>
 * <li>空窗：仍走 {@code saveAll(empty)}（= 删除），不把"空"当成"不写"；</li>
 * <li>窗口裁剪/思考元数据语义不变（沿用 {@code trimKeepingToolPairs} / maxMessages）。</li>
 * </ol>
 */
class ResilientChatServiceMemoryWriteTest {

	private static final String RUN_ID = "run-memory-write";

	private static final String SESSION_ID = "s-memory-write";

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

	private final RecordingRepository repository = new RecordingRepository();

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
			Thread thread = new Thread(runnable, "memory-write-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.repository, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
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

	@Test
	void normalFinishWritesTheWholeWindowWithOneSaveAllAndNeverClearsChatMemory() {
		RunSnapshot seeded = this.store.get(RUN_ID);
		seeded.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("第一轮问题"))));
		this.store.save(seeded);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("答案"), terminal("stop")));

		runTurn();

		assertThat(this.repository.saves).isEqualTo(1);
		List<Message> written = this.repository.findByConversationId(SESSION_ID);
		assertThat(written).hasSize(2);
		assertThat(written.get(0)).isInstanceOf(UserMessage.class);
		assertThat(((AssistantMessage) written.get(1)).getText()).isEqualTo("答案");
		assertThat(this.repository.ops).containsExactly("saveAll");
		verify(this.chatMemory, never()).clear(anyString());
		verify(this.chatMemory, never()).add(anyString(), anyList());
	}

	@Test
	void failedSaveAllKeepsTheOldHistoryAndDoesNotClearFirst() {
		List<Message> old = List.of(new UserMessage("旧历史"));
		this.repository.seed(SESSION_ID, old);
		this.repository.failOnSave = true;
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("新答案"), terminal("stop")));

		runTurn();

		assertThat(this.repository.findByConversationId(SESSION_ID)).hasSize(1)
			.first()
			.isInstanceOf(UserMessage.class);
		assertThat(this.repository.ops).containsExactly("saveAll");
		assertThat(this.store.get(RUN_ID).getStatus()).as("记忆写失败不得让本轮对话失败").isEqualTo(RunSnapshot.DONE);
		verify(this.chatMemory, never()).clear(anyString());
	}

	@Test
	void nullWindowIsWarnedNotTreatedAsDelete() {
		List<Message> old = List.of(new UserMessage("旧历史"));
		this.repository.seed(SESSION_ID, old);

		this.service.persistMemory(SESSION_ID, null, "null-window");

		assertThat(this.repository.saves).as("null 不得被当作空窗 saveAll(empty)").isZero();
		assertThat(this.repository.ops).isEmpty();
		assertThat(this.repository.findByConversationId(SESSION_ID)).hasSize(1);
	}

	@Test
	void emptyWindowStillCallsSaveAllWithEmptyListWhichDeletes() {
		RunSnapshot seeded = this.store.get(RUN_ID);
		seeded.setMessageJson(this.codec.serializeAll(List.of(new SystemMessage("只有系统提示"))));
		this.store.save(seeded);
		this.repository.seed(SESSION_ID, List.of(new UserMessage("旧历史")));
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "本轮已取消")));

		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new SystemMessage("只有系统提示")), out);

		assertThat(this.repository.ops).containsExactly("saveAll");
		assertThat(this.repository.lastSave).isEmpty();
		assertThat(this.repository.findByConversationId(SESSION_ID)).isEmpty();
		assertThat(this.repository.deletes).isEqualTo(1);
		verify(this.chatMemory, never()).clear(anyString());
	}

	@Test
	void maxMessagesStillTrimsTheWindowKeepingToolPairs() {
		this.properties.getMemory().setMaxMessages(2);
		RunSnapshot seeded = this.store.get(RUN_ID);
		seeded.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("第一轮问题"),
				AssistantMessage.builder().content("第一轮回答").build())));
		this.store.save(seeded);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("第二轮回答"), terminal("stop")));

		SseChatEmitter out = runTurn();

		assertThat(this.repository.findByConversationId(SESSION_ID)).hasSize(2);
		assertThat(((AssistantMessage) this.repository.findByConversationId(SESSION_ID).get(1)).getText())
			.isEqualTo("第二轮回答");
		// 输入历史 + 本轮回答 = 3 条；maxMessages=2 ⇒ 裁掉最旧一条（第一轮问题）
		assertThat(this.repository.findByConversationId(SESSION_ID).get(0)).isInstanceOf(AssistantMessage.class);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private SseChatEmitter runTurn() {
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("第一轮问题")), out);
		wire.frames();
		return out;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	/** 记录型仓储：看"写了几次/是否先删"，并能在写入时失败以验证旧历史保留。 */
	private static final class RecordingRepository implements ChatMemoryRepository {

		private final Map<String, List<Message>> store = new LinkedHashMap<>();

		private final List<String> ops = new ArrayList<>();

		private List<Message> lastSave = List.of();

		private int saves;

		private int deletes;

		private boolean failOnSave;

		void seed(String conversationId, List<Message> messages) {
			this.store.put(conversationId, new ArrayList<>(messages));
		}

		@Override
		public List<String> findConversationIds() {
			return new ArrayList<>(this.store.keySet());
		}

		@Override
		public List<Message> findByConversationId(String conversationId) {
			return List.copyOf(this.store.getOrDefault(conversationId, List.of()));
		}

		@Override
		public void saveAll(String conversationId, List<Message> messages) {
			this.ops.add("saveAll");
			this.saves++;
			this.lastSave = messages == null ? List.of() : List.copyOf(messages);
			if (this.failOnSave) {
				throw new IllegalStateException("redis down");
			}
			if (messages == null || messages.isEmpty()) {
				this.store.remove(conversationId);
				this.deletes++;
				return;
			}
			this.store.put(conversationId, new ArrayList<>(messages));
		}

		@Override
		public void deleteByConversationId(String conversationId) {
			this.ops.add("delete");
			this.store.remove(conversationId);
			this.deletes++;
		}

	}

}
