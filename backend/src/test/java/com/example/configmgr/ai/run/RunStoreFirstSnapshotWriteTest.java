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
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>首笔快照的写入必须显式判定</b>（2026-10-29）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code RunStore.create(...)} 直接 {@code save(snapshot)} 然后无条件返回快照 —— 而写口是<b>可判定</b>的：
 * 取消优先守卫会拒写（当前快照已是取消态）、续跑权判据会拒写（丢租 / 设施不可确认 / fence 已被接管），
 * 两种情况都<b>静默返回 {@code false}</b>。于是调用方以为"这一轮已经建好"，继续跑模型、继续发
 * {@code done{...}} —— 而 Redis 里根本没有这一轮：刷新/重挂/续跑/取消全都没有持久面，
 * 用户却收到一条"成功"。
 *
 * <h2>本批口径</h2>
 * 首笔快照写失败（被拒写或抛错）⇒ {@code create} 抛 {@link AiStateUnavailableException}：
 * <ul>
 * <li>调用方不得继续驱动（首轮在 {@code store.create} 之后才取驱动所有权并调模型）；</li>
 * <li>环外出口按既有取消优先守卫写 FAILED 并发 {@code error} —— <b>不得</b>发成功终帧；</li>
 * <li>失败的那一轮不登记进 {@code ai:runs} 索引（索引只指向真实存在的轮）。</li>
 * </ul>
 */
class RunStoreFirstSnapshotWriteTest {

	private static final String RUN_ID = "run-first-snapshot";

	private static final String SESSION_ID = "s-first-snapshot";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void createThrowsWhenTheFirstSnapshotWriteIsRefused() {
		// 同一 runId 已有一条取消态（幂等重放/迟到创建）：取消优先守卫会拒写 RUNNING 首笔快照
		// 记录的归属必须与写者身份一致（生产快照恒带身份；0/0 记录严禁写进某个身份命名空间）
		RunSnapshot cancelled = new RunSnapshot();
		cancelled.setRunId(RUN_ID);
		cancelled.setSessionId(SESSION_ID);
		cancelled.setTenantId(1L);
		cancelled.setUserId(101L);
		cancelled.setStatus(RunSnapshot.CANCELLED);
		cancelled.setCancelled(true);
		cancelled.setMessageJson(this.codec.serializeAll(List.of()));
		this.store.save(cancelled);

		assertThatThrownBy(() -> this.store.create(RUN_ID, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("问题")), this.codec))
			.as("首笔快照被拒写 ⇒ 必须失败，不得返回一个 Redis 里并不存在的快照")
			.isInstanceOf(AiStateUnavailableException.class);

		RunSnapshot persisted = this.store.get(RUN_ID);
		assertThat(persisted.getStatus()).as("取消态不得被这条静默失败的创建覆盖").isEqualTo(RunSnapshot.CANCELLED);
	}

	@Test
	void createDoesNotRegisterTheRunIndexWhenTheFirstWriteIsRefused() {
		// 记录的归属必须与写者身份一致（生产快照恒带身份；0/0 记录严禁写进某个身份命名空间）
		RunSnapshot cancelled = new RunSnapshot();
		cancelled.setRunId(RUN_ID);
		cancelled.setSessionId(SESSION_ID);
		cancelled.setTenantId(1L);
		cancelled.setUserId(101L);
		cancelled.setStatus(RunSnapshot.CANCELLED);
		cancelled.setCancelled(true);
		cancelled.setMessageJson(this.codec.serializeAll(List.of()));
		this.store.save(cancelled);
		this.fake.setsByKey.clear();

		assertThatThrownBy(() -> this.store.create(RUN_ID, SESSION_ID, AiContext.empty(),
				List.of(new UserMessage("问题")), this.codec))
			.isInstanceOf(AiStateUnavailableException.class);

		assertThat(this.store.allRunIds()).as("没有持久面的轮不得登记进 ai:runs 索引").doesNotContain(RUN_ID);
	}

	// ── 相邻对照：首轮在"快照建不起来"时不得继续发成功终帧 ──────────────────

	private static final class FailingCreateStore extends InMemoryRunStore {

		@Override
		public RunSnapshot create(String runId, String sessionId, AiContext context, List<Message> history,
				MessageJsonCodec codec, Long providerId, String modelId, RunSnapshot.FrozenParams frozen) {
			throw new AiStateUnavailableException("模拟首笔快照写失败 runId=" + runId);
		}

	}

	private FailingCreateStore chatStore;

	private RunRegistry chatRegistry;

	private ChatModel chatModel;

	private ChatClient chatClient;

	private ChatClient.ChatClientRequestSpec requestSpec;

	private ChatClient.StreamResponseSpec streamSpec;

	private AiProviderRegistry providerRegistry;

	private ResilientChatService service;

	private ScheduledExecutorService watchdogExecutor;

	/** 直调真实 RunStore 必须显式带身份（create 缺身份 fail-closed）。 */
	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable tenantScope;

	@BeforeEach
	void setUpChatHarness() {
		this.tenantScope = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
		this.chatStore = new FailingCreateStore();
		this.chatRegistry = new RunRegistry(this.chatStore, this.mapper, null);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "first-snapshot-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.chatModel = mock(ChatModel.class);
		this.chatClient = mock(ChatClient.class);
		this.requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		this.streamSpec = mock(ChatClient.StreamResponseSpec.class);
		this.providerRegistry = mock(AiProviderRegistry.class);
		ProviderRuntime runtime = new ProviderRuntime(null, "prov", "model", "http://localhost", "/chat/completions",
				"k", null, null, this.chatModel, this.chatClient);
		when(this.providerRegistry.current()).thenReturn(runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(runtime));
		ChatMemory chatMemory = mock(ChatMemory.class);
		ToolRegistry toolRegistry = mock(ToolRegistry.class);
		AiSessionService sessionService = mock(AiSessionService.class);
		CancellationRegistry cancellations = mock(CancellationRegistry.class);
		ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenReturn(Flux.just(new ChatResponse(List.of(
				new Generation(AssistantMessage.builder().content("不该出现的回答").build())))));
		when(toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(sessionService.ensure(SESSION_ID)).thenReturn(true);
		when(cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(chatMemory.get(SESSION_ID)).thenReturn(new ArrayList<>());
		this.service = new ResilientChatService(this.providerRegistry, chatMemory, this.codec, this.chatStore,
				this.chatRegistry, toolRegistry, mock(ContextBuilder.class), sessionService, this.properties,
				cancellations, beacon, this.watchdogExecutor);
	}

	@AfterEach
	void tearDown() {
		if (this.watchdogExecutor != null) {
			this.watchdogExecutor.shutdownNow();
		}
		if (this.tenantScope != null) {
			this.tenantScope.close();
		}
	}

	/**
	 * 首笔快照建不起来 ⇒ 这一轮不得继续推进，更不得发 {@code done}（"成功但没有持久面"）。
	 */
	@Test
	void firstTurnDoesNotEmitDoneWhenTheFirstSnapshotCannotBePersisted() {
		SseChatEmitter out = this.chatRegistry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		assertThatThrownBy(() -> this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null),
				out)).as("首笔快照写失败必须上抛（由环外出口写 FAILED + error），不得静默继续")
			.isInstanceOf(AiStateUnavailableException.class);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).as("没有持久面就不得发成功终帧").noneMatch(frame -> "done".equals(frame.get("type")));
		verify(this.chatModel, never()).stream(any(Prompt.class));
		verify(this.chatClient, never()).prompt();
		assertThat(this.chatStore.get(RUN_ID)).as("这一轮在存储里根本不存在（这正是必须失败的原因）").isNull();
	}

}
