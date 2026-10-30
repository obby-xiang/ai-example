package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>驱动登记与压缩级别交接按「身份 + runId」分条</b>。
 *
 * <h2>缺陷形态</h2>
 * <ul>
 * <li>{@code drivingRuns}（首轮 {@code chat()} 登记、轮末摘除）用裸 runId：A 的首轮还在跑时，
 *     另一身份 B 的同名轮次的僵尸接管被"本进程驱动还活着"驳回 —— B 的轮次死在 RUNNING 也接管不了；
 *     续租的存活判据也会把 B 的登记当成 A 的。</li>
 * <li>{@code compactionLevels}（续跑侧压缩级别交接，读后即弃）用裸 runId：A 的 {@code L2} 会被
 *     B 的同名轮次消费掉（{@code done.context.level} 说了一次没做过的压缩）。</li>
 * </ul>
 */
class ResilientChatServiceDriveIsolationTest {

	private static final String RUN_ID = "run-shared-drive";

	private static final String SESSION_ID = "s-shared-drive";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

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
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "drive-isolation-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties(),
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

	@Test
	void drivingRunsAreScopedToIdentityPlusRunId() throws Exception {
		CountDownLatch modelCalled = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> {
			modelCalled.countDown();
			release.await(10, TimeUnit.SECONDS);
			return Flux.just(chunk("好了"), terminal());
		});

		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire.attachTo(out);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> turn = worker.submit(() -> as(A, () -> {
				this.service.chat(RUN_ID, new AiChatRequest(SESSION_ID, "你好", null, null, null), out);
				return null;
			}));
			assertThat(modelCalled.await(10, TimeUnit.SECONDS)).as("首轮必须走到模型调用").isTrue();

			assertThat(as(A, () -> this.service.isDriving(RUN_ID))).as("A 的驱动登记在 A 名下").isTrue();
			assertThat(as(B, () -> this.service.isDriving(RUN_ID)))
				.as("B 的同名轮次不得被判成'本进程还有驱动方'（B 的僵尸接管会被永久驳回）")
				.isFalse();

			release.countDown();
			turn.get(15, TimeUnit.SECONDS);
		}
		finally {
			release.countDown();
			worker.shutdownNow();
		}

		assertThat(as(A, () -> this.service.isDriving(RUN_ID))).as("轮末摘除本身份的登记").isFalse();
	}

	private AiProperties properties() {
		return new AiProperties();
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

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
