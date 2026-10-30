package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>首轮续租回调必须按捕获的请求身份读快照，不靠线程上下文</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 首轮 {@code chat} 取得驱动所有权后登记续租，存活判据是"本进程仍登记驱动 ∧ 快照非终态"。
 * 这条判据跑在 {@link LeaseService} 自建的<b>裸续租线程</b>上，而快照键由身份拼出：
 * <ul>
 * <li>旧实现用两参 {@code markDriving(lease, alive)}：身份取"登记那一拍的线程身份"，
 *     判据里的读则是<b>隐式</b>的（按当拍线程身份算键）—— 一旦续租线程上没有身份
 *     （或残留了别的调用者身份），读到的是 legacy 键 ⇒ {@code null} ⇒ 判"驱动者已不在"
 *     ⇒ 首拍还租，fencing 失效、另一实例可接管（双驱动）；</li>
 * <li>本用例把两件事一起钉住：登记时显式带<b>请求身份</b>（三参重载），且判据在<b>无身份线程</b>上
 *     执行时仍读到本轮快照（{@code RunStore#get(runId, tenantId, userId)} 显式读口）。</li>
 * </ul>
 *
 * <p>真实 {@link RunStore} + 真实键形（{@link FakeLeaseRedis}），身份 {@code 9001/7}。
 */
class FirstTurnRenewIdentityTest {

	private static final String RUN_ID = "run-first-turn-renew";

	private static final String SESSION_ID = "s-first-turn-renew";

	private static final TenantUser REQUEST_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final FakeRenewScheduler renewScheduler = new FakeRenewScheduler();

	private final LeaseService leases = spy(new LeaseService(this.fake.redis, this.properties, this.renewScheduler,
			"inst-a"));

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.leases);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

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
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "first-turn-renew-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(this.runtime));
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		when(this.chatMemory.get(SESSION_ID))
			.thenReturn(new ArrayList<>(List.of(new UserMessage("你好"), new AssistantMessage("你好呀"))));
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void theRenewalPredicateReadsTheSnapshotThroughTheCapturedRequestIdentity() throws Exception {
		CountDownLatch modelEntered = new CountDownLatch(1);
		CountDownLatch releaseModel = new CountDownLatch(1);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> {
			modelEntered.countDown();
			releaseModel.await(10, TimeUnit.SECONDS);
			return Flux.just(chunk("好了"), terminal());
		});

		SseChatEmitter out = this.registry.of(RUN_ID);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> turn = worker.submit(() -> {
				// 请求线程携带的本轮身份（HTTP 边界建立；此后一律按记录恢复）
				try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
						REQUEST_IDENTITY.tenantId(), REQUEST_IDENTITY.userId())) {
					this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);
				}
			});
			assertThat(modelEntered.await(10, TimeUnit.SECONDS)).as("首轮必须走到模型调用那一拍").isTrue();

			ArgumentCaptor<BooleanSupplier> alive = ArgumentCaptor.forClass(BooleanSupplier.class);
			verify(this.leases).markDriving(any(LeaseService.Lease.class), alive.capture(), eq(REQUEST_IDENTITY));
			verify(this.leases, never()).markDriving(any(LeaseService.Lease.class), any(BooleanSupplier.class));

			assertThat(RequestContextUtils.currentOrNull()).as("前置：续租判据在无身份线程上执行").isNull();
			assertThat(alive.getValue().getAsBoolean())
				.as("判据必须用捕获的身份显式读快照（隐式读会在无身份线程上读到 legacy 键 ⇒ 首拍还租）")
				.isTrue();

			releaseModel.countDown();
			turn.get(20, TimeUnit.SECONDS);
		}
		finally {
			releaseModel.countDown();
			worker.shutdownNow();
		}

		assertThat(this.store.get(RUN_ID, REQUEST_IDENTITY.tenantId(), REQUEST_IDENTITY.userId()).getStatus())
			.as("收尾仍是正常终态").isEqualTo(RunSnapshot.DONE);
		assertThat(this.renewScheduler.activeCount()).as("轮次结束后不留僵尸续租").isZero();
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
