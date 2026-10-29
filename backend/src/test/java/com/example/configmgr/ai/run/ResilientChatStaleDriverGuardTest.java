package com.example.configmgr.ai.run;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>RT-20：旧驱动（丢租/不可确认/续跑权已被接管）不得赢下答案面</b>。
 *
 * <h2>缺陷形态</h2>
 * {@code authority} 只拦了快照写（{@code RunStore#save} 的 fence）与出帧（{@code SseChatEmitter}），
 * 但终局的"答案面"写口 {@code convergeMemory(...)} → {@code saveAll} 整窗覆写 {@code chat:mem:*}
 * 完全没看它：旧驱动把整窗历史盖回去，下一轮读到的还是作废答案。
 * 本批口径：该写口在<b>写之前</b>问一次续跑权（与快照/出帧/认领/台账同源）；
 * 只读（LOST/UNAVAILABLE/fence 已过本实例 epoch）⇒ 一律不写，只留 WARN。
 * 出帧侧不需要改服务：{@code SseChatEmitter} 的闸门读的是同一个 {@code runLeaseAuthority}，
 * 因此"旧驱动发第二份终帧"也一并被拦（本用例直接断言 frames 里没有终帧）。
 */
class ResilientChatStaleDriverGuardTest {

	private static final String RUN_ID = "run-stale-driver";

	private static final String SESSION_ID = "s-stale-driver";

	private final StaleAuthorityStore store = new StaleAuthorityStore();

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
			Thread thread = new Thread(runnable, "stale-driver-watchdog");
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
		// 本轮输入历史（与记忆写回用例同口径）：终局整窗 = 1 条 user + 1 条 assistant。
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("第一轮问题"))));
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void lostLeaseDriverWritesNeitherMemoryNorATerminalFrame() {
		this.store.authority = LeaseService.LeaseAuthority.LOST;
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("旧驱动的作废回答"), terminal("stop")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(this.repository.saves).as("RT-20：丢租的旧驱动不得整窗覆写记忆").isZero();
		assertThat(this.repository.ops).as("记忆面一次写/删都不许发生").isEmpty();
		assertThat(terminalFrameTypes(frames)).as("旧驱动不得再发一份终帧（前端会提前收敛到作废回答）").isEmpty();
	}

	@Test
	void unavailableLeaseDriverIsAlsoReadOnlyForAnswerSurfaces() {
		this.store.authority = LeaseService.LeaseAuthority.UNAVAILABLE;
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("不可确认时的回答"), terminal("stop")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(this.repository.saves).as("无法确认续跑权 ⇒ 不写记忆（宁可不写也不能写错）").isZero();
		assertThat(terminalFrameTypes(frames)).isEmpty();
	}

	@Test
	void fenceAheadDriverCannotWriteMemory() {
		// HELD-but-fence-ahead：租约视图还在（authority=HELD），但 fence 已被新主人推进到更高 epoch。
		// 快照写会被 fence 拒、台账/帧/序号被 RunStore 的写门拒；这里锁住"答案面"（记忆窗口）。
		this.store.authority = LeaseService.LeaseAuthority.HELD;
		this.store.epoch = 1L;
		this.store.fence = 2L;
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("被接管后的回答"), terminal("stop")));

		runTurn();

		assertThat(this.repository.saves).as("fence 已过本实例 epoch ⇒ 不得写记忆").isZero();
	}

	@Test
	void healthyDriverStillWritesMemoryAndTerminalFrame() {
		// 回归护栏：修复不得把正常路径一起关掉（无租约语义 = NEVER_HELD 保持原样）。
		this.store.authority = LeaseService.LeaseAuthority.NEVER_HELD;
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("正常回答"), terminal("stop")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(this.repository.saves).as("正常收尾照旧整窗写一次").isEqualTo(1);
		assertThat(this.repository.findByConversationId(SESSION_ID)).hasSize(2);
		assertThat(terminalFrameTypes(frames)).as("正常收尾必须有终帧").contains("done");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private List<Map<String, Object>> runTurn() {
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("第一轮问题")), out);
		return wire.frames();
	}

	/** 终帧类型（done / error）：旧驱动一条都不许发。 */
	private static List<String> terminalFrameTypes(List<Map<String, Object>> frames) {
		List<String> types = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				types.add(type);
			}
		}
		return types;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	/** 续跑权可编程的进程内 store：只替换"权威判定"，其余语义沿用 {@link InMemoryRunStore}。 */
	private static final class StaleAuthorityStore extends InMemoryRunStore {

		private volatile LeaseService.LeaseAuthority authority = LeaseService.LeaseAuthority.NEVER_HELD;

		/** 本进程 epoch 视图与 fence（fence > epoch = 续跑权已被新主人接管）。 */
		private volatile long epoch;

		private volatile long fence;

		@Override
		public LeaseService.LeaseAuthority runLeaseAuthority(String runId) {
			return this.authority;
		}

		@Override
		public long heldRunEpoch(String runId) {
			return this.epoch;
		}

		@Override
		public long fenceEpoch(String runId) {
			return this.fence;
		}

	}

	/** 记录型仓储（与记忆写回用例同口径）：只看"写了几次/是否先删"。 */
	private static final class RecordingRepository implements ChatMemoryRepository {

		private final Map<String, List<Message>> store = new LinkedHashMap<>();

		private final List<String> ops = new ArrayList<>();

		private int saves;

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
			if (messages == null || messages.isEmpty()) {
				this.store.remove(conversationId);
				return;
			}
			this.store.put(conversationId, new ArrayList<>(messages));
		}

		@Override
		public void deleteByConversationId(String conversationId) {
			this.ops.add("delete");
			this.store.remove(conversationId);
		}

	}

}
