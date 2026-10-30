package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.FakeRenewScheduler;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>跨身份同 runId 的续跑 / 仅模型步重跑归属</b>（真实 {@link RunStore} + {@link FakeLeaseRedis} +
 * 真实 {@link ResumeService} + 真实 {@link AiController}）。
 *
 * <h2>被钉住的三条</h2>
 * <ol>
 * <li><b>无归属 ⇒ 不驱动</b>：{@code POST /api/ai/runs/{runId}/resume} 与
 *     {@code POST /api/ai/runs/{runId}/retry-model-step} 在调用身份没有这一轮时以
 *     {@code 404 UNKNOWN_RUN} 拒绝，别人的快照/待决/租约一个都不动，也不交棒驱动；</li>
 * <li><b>同 runId 各动自己的</b>：A、B 各有同一 runId 的轮次时，B 的续跑只把 B 的快照推进到
 *     {@code RUNNING}（驱动 B 的会话），A 的仍停在 {@code SUSPENDED}、租约键为空；</li>
 * <li><b>本人仍可按契约执行</b>：A 自己的 resume / retry-model-step 照常受理（{@code 202}）并驱动
 *     一轮 —— 归属收窄不得把正常路径关掉。</li>
 * </ol>
 */
class ResumeCrossTenantSameRunIdTest {

	private static final String RUN_ID = "run-resume-shared";

	private static final String TOOL_CALL_ID = "call-resume-shared";

	private static final String SESSION_A = "s-resume-a";

	private static final String SESSION_B = "s-resume-b";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private static final String MODEL_ID = "test-model";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.leases);

	private final CancellationRegistry cancellations = new CancellationRegistry(this.fake.redis, this.properties);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor suspendExecutor = mock(ThreadPoolTaskExecutor.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final ConfirmGate gate;

	private final ResumeService resumeService;

	ResumeCrossTenantSameRunIdTest() {
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> resumeProvider = mock(ObjectProvider.class);
		this.resumeService = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.suspendExecutor, this.fake.redis, this.properties, this.cancellations,
				new ToolResultLimiter(this.properties));
		when(resumeProvider.getIfAvailable()).thenReturn(this.resumeService);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				mock(ToolActivityBeacon.class), List.of(), resumeProvider, Runnable::run, this.leases);
	}

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), anyString(), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.chatService.systemPromptText(any())).thenReturn(null);
		// 续跑/重跑的交棒同步执行（断言的是"被驱动"，不是线程时序）
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.suspendExecutor).execute(any(Runnable.class));
	}

	@Test
	void resumeWithoutOwnRecordNeverDrivesTheOtherIdentitysRun() {
		seedSuspendedPending(TENANT_A, USER_A, SESSION_A);

		ResponseEntity<?> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			response = controller().resume(RUN_ID);
		}

		assertThat(response.getStatusCode().value()).as("B 没有自己这一轮 ⇒ 404").isEqualTo(404);
		assertThat(bodyOf(response)).containsEntry("reason", "UNKNOWN_RUN");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.get(RUN_ID).getStatus()).as("A 的快照不得被 B 的续跑改写")
				.isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(this.store.pendingRaw(RUN_ID, TOOL_CALL_ID).getStatus())
				.as("A 的待决条目不得被结算")
				.isEqualTo(PendingToolCall.PENDING);
			assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("不得抢 A 的续跑权租约").isNull();
			assertThat(this.store.fenceEpoch(RUN_ID)).as("不得推进 A 的 fence（B 从未取得 A 的授权）").isZero();
		}
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(), any());
	}

	@Test
	void resumeOfOwnRecordActsOnlyOnItsOwnRunEvenWithTheSameRunId() {
		seedSuspended(TENANT_A, USER_A, SESSION_A);
		seedSuspended(TENANT_B, USER_B, SESSION_B);

		ResponseEntity<?> forB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			forB = controller().resume(RUN_ID);
		}
		assertThat(forB.getStatusCode().value()).as("B 自己的续跑必须受理").isEqualTo(202);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.store.get(RUN_ID).getStatus()).as("B 推进的是自己那一轮").isEqualTo(RunSnapshot.RUNNING);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.get(RUN_ID).getStatus()).as("A 的状态不得被 B 的续跑改动")
				.isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("B 不得持有/改动 A 的续跑租约").isNull();
		}
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_B), any(AiContext.class), any(), same(this.emitter));

		ResponseEntity<?> forA;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			forA = controller().resume(RUN_ID);
		}
		assertThat(forA.getStatusCode().value()).as("A 自己的续跑照常按契约受理").isEqualTo(202);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.RUNNING);
		}
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_A), any(AiContext.class), any(), same(this.emitter));
	}

	@Test
	void retryModelStepWithoutOwnRecordNeverDrivesTheOtherIdentitysRun() {
		seedFailed(TENANT_A, USER_A, SESSION_A);

		ResponseEntity<?> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			response = controller().retryModelStep(RUN_ID);
		}

		assertThat(response.getStatusCode().value()).as("B 没有自己这一轮 ⇒ 404").isEqualTo(404);
		assertThat(bodyOf(response)).containsEntry("reason", "UNKNOWN_RUN");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.get(RUN_ID).getStatus()).as("A 的失败轮不得被 B 重跑")
				.isEqualTo(RunSnapshot.FAILED);
			assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("不得抢 A 的续跑权租约").isNull();
		}
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(), any());
	}

	@Test
	void retryModelStepOfOwnFailedRunRunsItWhileTheOtherIdentitysRunStaysUntouched() {
		seedFailed(TENANT_A, USER_A, SESSION_A);
		seedFailed(TENANT_B, USER_B, SESSION_B);

		ResponseEntity<?> forB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			forB = controller().retryModelStep(RUN_ID);
		}
		assertThat(forB.getStatusCode().value()).as("B 自己的重跑必须受理").isEqualTo(202);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.RUNNING);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.get(RUN_ID).getStatus()).as("A 的失败轮不得被 B 的重跑带走")
				.isEqualTo(RunSnapshot.FAILED);
			assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("B 不得持有/改动 A 的续跑租约").isNull();
		}
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_B), any(AiContext.class), any(), same(this.emitter));

		ResponseEntity<?> forA;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			forA = controller().retryModelStep(RUN_ID);
		}
		assertThat(forA.getStatusCode().value()).as("A 自己的重跑照常按契约受理").isEqualTo(202);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.RUNNING);
		}
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_A), any(AiContext.class), any(), same(this.emitter));
	}

	// ───────────────────────── helpers ─────────────────────────

	private void seedSuspendedPending(long tenantId, long userId, String sessionId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, sessionId, AiContext.empty(), List.of(new UserMessage("请执行导出")), this.codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId(TOOL_CALL_ID);
			pending.setName("start_publish");
			pending.setType("function");
			pending.setArguments("{}");
			pending.setKind(PendingToolCall.KIND_CONFIRM);
			pending.setStatus(PendingToolCall.PENDING);
			this.store.putPending(RUN_ID, pending);
		}
	}

	private void seedSuspended(long tenantId, long userId, String sessionId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, sessionId, AiContext.empty(), List.of(new UserMessage("请执行导出")), this.codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
		}
	}

	private void seedFailed(long tenantId, long userId, String sessionId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, sessionId, AiContext.empty(), List.of(new UserMessage("请执行导出")), this.codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setModelId(MODEL_ID);
			snapshot.setStatus(RunSnapshot.FAILED);
			this.store.save(snapshot);
		}
	}

	private AiController controller() {
		AiController built = new AiController(this.availability, provider(null), provider(this.resumeService),
				provider(null), mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				this.gate, this.store, this.registry, this.cancellations, this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), this.mapper, this.properties, mock(Environment.class),
				mock(JobWatchdog.class));
		return built;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
		assertThat(response.getBody()).isInstanceOf(Map.class);
		return (Map<String, Object>) response.getBody();
	}

	private static <T> ObjectProvider<T> provider(T bean) {
		@SuppressWarnings("unchecked")
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
