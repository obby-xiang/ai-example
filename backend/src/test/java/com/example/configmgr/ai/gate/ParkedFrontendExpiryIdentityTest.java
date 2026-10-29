package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>HITL 到期收敛的身份链路</b>（真实 {@link RunStore} + 真实键形）。
 *
 * <h2>被钉住的缺陷</h2>
 * park 之后的前端回执到期裁决跑在 {@link ConfirmGate} 的重试调度线程上，那里<b>没有</b>请求身份；
 * 而待决条目 / 快照的键由身份拼出。旧实现「先 {@code store.get(runId)} 再按快照身份开作用域」：
 * 无身份线程读到 legacy 键 ⇒ {@code null} ⇒ 空作用域 ⇒ {@code pendingLenient} 也读不到 ⇒ 直接 return。
 * 用户可见后果 = 条目<b>永远 PENDING</b>、没有终帧、没有续跑（"前端工具挂了之后永不收敛"）。
 *
 * <p>修法：提交到期任务时抓<b>记录身份</b>随任务携带，任务体在它的作用域里执行收敛 + 派发。
 */
class ParkedFrontendExpiryIdentityTest {

	private static final String RUN_ID = "run-parked-expiry-identity";

	private static final String SESSION_ID = "s-parked-expiry-identity";

	private static final String CALL_ID = "call-nav-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	/**
	 * 到期裁决线程（<b>在没有任何身份时创建</b>）。
	 *
	 * <p>为什么不用 {@code ConfirmGate.DEFAULT_RETRY_SCHEDULER}：它是进程级静态单线程池，线程在
	 * <b>首次调度</b>时创建；而 {@code RequestContextUtils} 用的是 {@code TransmittableThreadLocal}
	 * （本质是 inherit 型），若那一次调度发生在有身份的线程上，池线程会<b>永久继承</b>那个身份 ——
	 * 用例就测不到"定时线程没有身份"这一真实形态（生产里池线程通常先于任何请求创建）。
	 */
	private ScheduledExecutorService expiryExecutor;

	private final AtomicReference<TenantUser> expiryThreadIdentityAtBirth = new AtomicReference<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(1));
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("navigate_to")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("navigate_to")).thenReturn(ToolMeta.RiskLevel.READ);
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		ConfirmGate.RetryScheduler retryScheduler = identityLessScheduler();
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, retryScheduler);
		SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of());

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("跳到编辑器")), this.codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setContext(AiContext.empty().toMap());
			this.store.save(snapshot);
			assertThatThrownBy(() -> manager.executeToolCalls(prompt(), response()))
				.isInstanceOf(RunParkedException.class);
		}
	}

	@Test
	void parkedFrontendCardConvergesOnTheSchedulerThreadThatHasNoIdentity() {
		assertThat(this.expiryThreadIdentityAtBirth.get()).as("前置：到期裁决线程出生时没有身份").isNull();
		assertThat(RequestContextUtils.currentOrNull()).as("前置：用例主线程此刻无身份").isNull();

		awaitTrue(() -> {
			PendingToolCall current = pendingAsRecord();
			return current != null && !PendingToolCall.PENDING.equals(current.getStatus());
		}, 8000,
				"park 后的即时前端工具必须在 expiresAt 到期收敛（带身份的轮次不得因为定时线程无身份而永不收敛）");

		PendingToolCall expired = pendingAsRecord();
		assertThat(expired.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
		assertThat(expired.getOutcome()).isEqualTo(ToolOutcome.TIMEOUT.wire());
		assertThat(expired.getReason()).isEqualTo("FRONTEND_TIMEOUT");
		verify(this.resumeService, timeout(3000)).resume(RUN_ID);
		assertThat(RequestContextUtils.currentOrNull()).as("到期裁决结束不得在调度线程残留身份").isNull();
	}

	@AfterEach
	void tearDown() {
		if (this.expiryExecutor != null) {
			this.expiryExecutor.shutdownNow();
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 调度器：线程早于任何身份创建（并断言它出生时确实没有身份）。 */
	private ConfirmGate.RetryScheduler identityLessScheduler() {
		this.expiryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "park-expiry-identity-test");
			thread.setDaemon(true);
			return thread;
		});
		try {
			this.expiryExecutor.submit(() -> this.expiryThreadIdentityAtBirth.set(RequestContextUtils.currentOrNull()))
				.get(5, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("预热到期裁决线程失败", ex);
		}
		return (task, delay) -> {
			long millis = delay == null ? 0L : Math.max(1L, delay.toMillis());
			var future = this.expiryExecutor.schedule(task, millis, TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		};
	}

	/** 按<b>记录身份</b>读待决条目（待决键同样由身份拼出：用例主线程没有身份）。 */
	private PendingToolCall pendingAsRecord() {
		try (com.example.configmgr.ai.tenant.RunIdentityScope.Scope ignored =
				com.example.configmgr.ai.tenant.RunIdentityScope.open(RECORD_IDENTITY)) {
			return this.store.pendingLenient(RUN_ID, CALL_ID);
		}
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("跳到编辑器")), options);
	}

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "navigate_to",
					"{\"page\":\"editor\"}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(20L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("等待超时：" + message);
	}

}
