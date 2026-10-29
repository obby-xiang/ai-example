package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>首轮的驱动所有权</b>（2026-10-29）：首轮 {@code chat} 必须先取得续跑权，再进入模型/预检。
 *
 * <h2>被钉住的缺陷</h2>
 * 首轮 {@code chat()} 建完快照就直奔模型调用，<b>从不抢续跑权租约</b>：
 * <ul>
 * <li>跨实例：另一实例的僵尸扫描看到的证据是"快照 RUNNING + 没有租约 + 业务活动已超龄"
 *     ⇒ 判死并接管 —— 而首轮驱动线程其实还在长预检/首包等待里 ⇒ 一轮两个驱动方
 *     （重复帧、settle 分叉、历史分叉）；</li>
 * <li>同实例：租约是可重入的，光"持租"也挡不住（见 {@code ResumeZombieTakeoverRecheckTest}）。</li>
 * </ul>
 *
 * <h2>本批口径</h2>
 * 快照建好之后、模型调用之前取得租约，并按 {@code lease.renew-interval} <b>续租</b>
 * （与续跑同一条心跳）；终态写（{@code RunStore#persist}）释放，进程内驱动标记兜底停续租。
 */
class FirstTurnDriveOwnershipTest {

	private static final String RUN_ID = "run-first-turn-owner";

	private static final String SESSION_ID = "s-first-turn-owner";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final AiProperties properties = new AiProperties();

	private final FakeRenewScheduler renewScheduler = new FakeRenewScheduler();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, this.renewScheduler,
			"inst-a");

	/** 另一实例的租约视图（跨实例抢租的唯一造法：同一份 Redis，不同 owner）。 */
	private final LeaseService otherInstance = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-b");

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel, this.chatClient);

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
		this.store.useLeaseFacility(this.leases);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "first-turn-owner-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		when(this.chatMemory.get(SESSION_ID))
			.thenReturn(new ArrayList<>(List.of(new UserMessage("你好"), new AssistantMessage("你好呀"))));
		stubFluentChain();
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	/**
	 * 模型调用那一刻：本实例已经持有这一轮的续跑权（另一实例抢不到），续租任务在跑；
	 * 终态落库后租约被释放、续租停止。
	 */
	@Test
	void firstTurnAcquiresTheRunLeaseBeforeTheModelCallAndReleasesItAtTheTerminal() {
		AtomicLong epochAtModelCall = new AtomicLong(-1L);
		AtomicBoolean otherInstanceTookOver = new AtomicBoolean();
		AtomicLong renewActiveAtModelCall = new AtomicLong(-1L);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> {
			epochAtModelCall.set(this.store.heldRunEpoch(RUN_ID));
			// 另一实例此刻去抢同一轮的续跑权：能抢到就说明"僵尸扫描可以接管"，首轮会被双驱动
			otherInstanceTookOver.set(this.otherInstance
				.tryAcquire(this.store.runLeaseKey(RUN_ID), this.properties.getLease().getTtl())
				.isPresent());
			renewActiveAtModelCall.set(this.renewScheduler.activeCount());
			return Flux.just(chunk("好了"), terminal());
		});

		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		assertThat(epochAtModelCall.get()).as("模型调用前必须已取得续跑权（epoch > 0）").isPositive();
		assertThat(otherInstanceTookOver.get()).as("持租期间另一实例不得抢到（否则僵尸扫描可接管并双驱动）")
			.isFalse();
		assertThat(renewActiveAtModelCall.get()).as("驾驶期必须有续租心跳（与续跑同一条调度）").isEqualTo(1L);

		assertThat(this.store.heldRunEpoch(RUN_ID)).as("终态写释放本实例的续跑权").isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("终态后租约键应被删（不留 TTL 空窗）").isNull();
		assertThat(this.renewScheduler.activeCount()).as("轮次结束后不得留下仍在续租的僵尸任务").isZero();

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.oneOfType(frames, "done")).as("正常轮次恰好一条 done（oneOfType 自带唯一性）")
			.isNotNull();
		assertThat(FrameContract.types(frames)).as("不得出现失败帧").doesNotContain("error");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	/**
	 * <b>长预检不得被僵尸接管造成双驱动</b>：首轮建好快照、取得续跑权之后有一段长预检/首包等待
	 * （这里用上游 {@code chatResponse()} 首包那一笔把时间钉住）。预检期间：
	 * <ol>
	 * <li>另一实例抢不到这一轮的续跑权（跨实例的唯一接管入口被堵住）；</li>
	 * <li>同实例的 {@code forceTakeover} 僵尸接管被"驱动还活着"复核驳回（租约可重入，
	 *     光靠持租挡不住同实例）；</li>
	 * <li>整轮只有<b>一次</b>模型调用、<b>一条</b>终帧 —— 没有第二个驱动方。</li>
	 * </ol>
	 */
	@Test
	void aLongPreflightKeepsOwnershipSoNoTakeoverCanDoubleDrive() throws Exception {
		CountDownLatch preflightEntered = new CountDownLatch(1);
		CountDownLatch preflightRelease = new CountDownLatch(1);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> {
			preflightEntered.countDown();
			preflightRelease.await(10, TimeUnit.SECONDS);
			return Flux.just(chunk("好了"), terminal());
		});

		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> turn = worker.submit(
					() -> this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out));
			assertThat(preflightEntered.await(10, TimeUnit.SECONDS)).as("首轮必须走到预检那一笔").isTrue();

			assertThat(this.store.heldRunEpoch(RUN_ID)).as("预检期本实例持租").isPositive();
			assertThat(this.otherInstance.tryAcquire(this.store.runLeaseKey(RUN_ID),
					this.properties.getLease().getTtl())).as("预检期另一实例抢不到（僵尸扫描无从接管）").isEmpty();

			ResumeService takeover = new ResumeService(this.store, this.registry, this.codec, this.service,
					mock(SpToolCallingManager.class), mock(ThreadPoolTaskExecutor.class),
					mock(StringRedisTemplate.class), this.properties, this.cancellations,
					mock(ToolResultLimiter.class));
			ResumeService.ResumeResult result = takeover.resume(RUN_ID, true);
			assertThat(result.outcome()).as("同实例的僵尸接管必须被'驱动还活着'驳回（否则一轮两个驱动方）")
				.isEqualTo(ResumeService.Outcome.ALREADY_RUNNING);
			assertThat(result.body()).containsEntry("reason", "DRIVER_ALIVE_IN_PROCESS");

			preflightRelease.countDown();
			turn.get(15, TimeUnit.SECONDS);
		}
		finally {
			preflightRelease.countDown();
			worker.shutdownNow();
		}

		verify(this.chatClient, times(1)).prompt();
		assertThat(FrameContract.oneOfType(wire.frames(), "done")).as("恰好一条终帧（oneOfType 自带唯一性）")
			.isNotNull();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	/**
	 * <b>相邻取消</b>：取消落在"快照已建、驱动所有权已取得"之后、模型调用之前 ⇒
	 * 取消收尾接手（唯一 {@code done{cancelled:true}}），并把刚取得的续跑权交还。
	 *
	 * <p>这是本批改动最容易踩坏的相邻路径：所有权是新加的，取消出口必须先把它收干净，
	 * 否则"用户点了停止"的轮会留下一个续租到 TTL 的僵尸租约（下一次续跑要白等一个 TTL）。
	 */
	@Test
	void aCancelRightAfterTheSnapshotIsCreatedEndsWithOneCancellationTerminalAndReleasesTheLease() {
		AtomicInteger cancelStateCalls = new AtomicInteger();
		// 第 1 次（压缩后/建快照前）未取消；其后（交棒前那一拍）取消 —— 取消落在建快照之后
		when(this.cancellations.cancelState(anyString())).thenAnswer(invocation -> cancelStateCalls
			.incrementAndGet() <= 1 ? CancellationRegistry.CancelState.NOT_CANCELLED
					: CancellationRegistry.CancelState.CANCELLED);

		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		verify(this.chatClient, never()).prompt();
		assertThat(this.store.get(RUN_ID).getStatus()).as("取消落库").isEqualTo(RunSnapshot.CANCELLED);
		assertThat(FrameContract.oneOfType(wire.frames(), "done")).as("取消终帧恰好一条")
			.containsEntry("cancelled", true);
		assertThat(FrameContract.types(wire.frames())).as("取消不补失败帧").doesNotContain("error");
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("取消收尾/收尾 finally 必须交还刚取得的续跑权").isZero();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).isNull();
		assertThat(this.renewScheduler.activeCount()).as("不得留下仍在续租的僵尸任务").isZero();
	}

	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenReturn(Flux.just(chunk("好了"), terminal()));
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal() {
		return new ChatResponse(
				List.of(new Generation(AssistantMessage.builder().content("").build(),
						ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().model("test-model").build());
	}

}
