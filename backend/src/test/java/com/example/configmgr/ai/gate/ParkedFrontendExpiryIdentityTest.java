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
import com.example.configmgr.ai.tenant.RunIdentityScope;
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

	/** 调度线程上<b>残留</b>的另一个身份（池化/复用线程的真实形态）。 */
	private static final TenantUser RESIDUAL_IDENTITY = new TenantUser(9002L, 8L);

	private static final String RESIDUAL_RUN_ID = "run-parked-expiry-residual-identity";

	private static final String RESIDUAL_SESSION_A = "s-parked-expiry-a";

	private static final String RESIDUAL_SESSION_B = "s-parked-expiry-b";

	private static final String RESIDUAL_CALL_ID = "call-nav-residual";

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

	/**
	 * <b>调度线程残留另一个身份时，到期裁决仍只收敛记录主人。</b>
	 *
	 * <h2>被钉住的缺陷</h2>
	 * 池化/定时线程可能带着上一位使用者的身份（{@code TransmittableThreadLocal} 的继承语义）。
	 * 若任务体<b>不覆盖</b>为记录身份，{@code store.get(runId)} / {@code pendingLenient} /
	 * {@code putPending} 全部按残留身份定键：A 的卡永远 PENDING（没人收敛），而 B 的同名 runId
	 * 条目被误收敛、误发结局帧 —— 一次"别人的超时"落进另一个租户的轮次。
	 *
	 * <p>本用例的 B 侧就是那条诱饵：同 runId、同 toolCallId、同样过期且仍 PENDING。
	 * 正确实现下只有 A 的条目变 TIMEOUT，B 的条目与快照一个字段都不动。
	 */
	@Test
	void schedulerThreadCarryingAnotherIdentityStillConvergesOnlyTheRecordOwner() throws Exception {
		long foreignUpdatedAt = seedForeignParkedRun();

		AtomicReference<TenantUser> schedulerIdentityAtBirth = new AtomicReference<>();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "park-expiry-residual-identity");
			thread.setDaemon(true);
			return thread;
		});
		try {
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
					RESIDUAL_IDENTITY.tenantId(), RESIDUAL_IDENTITY.userId())) {
				executor.submit(() -> schedulerIdentityAtBirth.set(RequestContextUtils.currentOrNull()))
					.get(5, TimeUnit.SECONDS);
			}
			catch (Exception ex) {
				throw new IllegalStateException("预热带残留身份的到期裁决线程失败", ex);
			}
			assertThat(schedulerIdentityAtBirth.get()).as("前置：到期裁决线程出生时带着另一个身份")
				.isEqualTo(RESIDUAL_IDENTITY);

			ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
			when(provider.getIfAvailable()).thenReturn(this.resumeService);
			ConfirmGate.RetryScheduler retryScheduler = (task, delay) -> {
				long millis = delay == null ? 0L : Math.max(1L, delay.toMillis());
				var future = executor.schedule(task, millis, TimeUnit.MILLISECONDS);
				return () -> future.cancel(false);
			};
			ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
					this.beacon, List.of(), provider, Runnable::run, null, retryScheduler);
			SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, gate,
					this.toolRegistry, this.codec, this.beacon, this.cancellations,
					new ToolResultLimiter(this.properties), List.of());

			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
					RECORD_IDENTITY.userId())) {
				this.store.create(RESIDUAL_RUN_ID, RESIDUAL_SESSION_A, AiContext.empty(),
						List.of(new UserMessage("跳到编辑器")), this.codec);
				RunSnapshot snapshot = this.store.get(RESIDUAL_RUN_ID);
				snapshot.setContext(AiContext.empty().toMap());
				this.store.save(snapshot);
				assertThatThrownBy(() -> manager.executeToolCalls(prompt(RESIDUAL_RUN_ID),
						response(RESIDUAL_CALL_ID))).isInstanceOf(RunParkedException.class);
			}

			awaitTrue(() -> {
				PendingToolCall current = pendingAs(RECORD_IDENTITY, RESIDUAL_RUN_ID, RESIDUAL_CALL_ID);
				return current != null && !PendingToolCall.PENDING.equals(current.getStatus());
			}, 8000, "残留 B 身份的调度线程上，A 的 park 条目必须按 A 的记录身份收敛");

			PendingToolCall owner = pendingAs(RECORD_IDENTITY, RESIDUAL_RUN_ID, RESIDUAL_CALL_ID);
			assertThat(owner.getStatus()).as("A 的条目照常收敛为 TIMEOUT").isEqualTo(PendingToolCall.TIMEOUT);
			assertThat(owner.getReason()).isEqualTo("FRONTEND_TIMEOUT");
			verify(this.resumeService, timeout(3000)).resume(RESIDUAL_RUN_ID);

			PendingToolCall foreign = pendingAs(RESIDUAL_IDENTITY, RESIDUAL_RUN_ID, RESIDUAL_CALL_ID);
			assertThat(foreign.getStatus()).as("残留身份 B 的同名条目不得被 A 的到期裁决打到")
				.isEqualTo(PendingToolCall.PENDING);
			assertThat(foreign.getOutcome()).isNull();
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(RESIDUAL_IDENTITY)) {
				RunSnapshot foreignSnapshot = this.store.get(RESIDUAL_RUN_ID);
				assertThat(foreignSnapshot.getSessionId()).isEqualTo(RESIDUAL_SESSION_B);
				assertThat(foreignSnapshot.getStatus()).as("B 的快照不得被改写").isEqualTo(RunSnapshot.RUNNING);
				assertThat(foreignSnapshot.getUpdatedAtMs()).as("B 的快照一个字段都不该动")
					.isEqualTo(foreignUpdatedAt);
			}
			assertThat(executor.submit(RequestContextUtils::currentOrNull).get(5, TimeUnit.SECONDS))
				.as("任务体关闭 A 的作用域后回到线程原本的残留身份（A 的身份不得漏到池线程）")
				.isEqualTo(RESIDUAL_IDENTITY);
		}
		finally {
			executor.shutdownNow();
		}
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
		return pendingAs(RECORD_IDENTITY, RUN_ID, CALL_ID);
	}

	/** 按给定身份读待决条目（跨身份核对"有没有打到别人的键"）。 */
	private PendingToolCall pendingAs(TenantUser identity, String runId, String callId) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return this.store.pendingLenient(runId, callId);
		}
	}

	/** B 侧诱饵：同 runId / 同 toolCallId 的已过期待决条目（残留身份若被沿用就会收敛到它）。 */
	private long seedForeignParkedRun() {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(RESIDUAL_IDENTITY)) {
			RunSnapshot snapshot = this.store.create(RESIDUAL_RUN_ID, RESIDUAL_SESSION_B, AiContext.empty(),
					List.of(new UserMessage("B 的轮")), this.codec);
			PendingToolCall bait = new PendingToolCall();
			bait.setToolCallId(RESIDUAL_CALL_ID);
			bait.setName("navigate_to");
			bait.setKind(PendingToolCall.KIND_FRONTEND);
			bait.setArguments("{\"page\":\"editor\"}");
			long now = System.currentTimeMillis();
			bait.setAtMs(now - 60_000L);
			bait.setExpiresAt(now - 1_000L);
			this.store.putPending(RESIDUAL_RUN_ID, bait);
			return snapshot.getUpdatedAtMs();
		}
	}

	private static Prompt prompt() {
		return prompt(RUN_ID);
	}

	private static Prompt prompt(String runId) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", runId))
			.build();
		return new Prompt(List.of(new UserMessage("跳到编辑器")), options);
	}

	private static ChatResponse response() {
		return response(CALL_ID);
	}

	private static ChatResponse response(String callId) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "navigate_to",
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
