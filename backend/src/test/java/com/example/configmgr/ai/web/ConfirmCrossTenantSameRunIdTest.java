package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.FakeRenewScheduler;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>跨身份同 runId 的确认写入归属</b>（真实 {@link RunStore} + {@link FakeLeaseRedis} + 真实
 * {@link ConfirmGate} + 真实 {@link AiController}）。
 *
 * <h2>被钉住的三条</h2>
 * <ol>
 * <li><b>无归属 ⇒ 无副作用</b>：{@code POST /api/ai/confirm} 在待决条目不属于调用身份时必须以
 *     {@code 409 UNKNOWN_TOOL_CALL} 拒绝，且不得在本人命名空间留下任何痕迹（决策 guard / 续跑租约 /
 *     状态改写），更不得触发续跑；</li>
 * <li><b>同 runId + 同 toolCallId 各自动自己</b>：A、B 各自挂起同一 {@code runId/toolCallId} 时，
 *     一方的确认只收敛自己的条目与自己的决策 guard，对方的条目、理由与状态一个字节都不变；</li>
 * <li><b>本人确认仍照常驱动</b>：确认受理后该身份的续跑租约被抢到、续跑被派发 —— 归属收窄不能把
 *     正常路径一起关掉。</li>
 * </ol>
 */
class ConfirmCrossTenantSameRunIdTest {

	private static final String RUN_ID = "run-confirm-shared";

	private static final String TOOL_CALL_ID = "call-confirm-shared";

	private static final String SESSION_A = "s-confirm-a";

	private static final String SESSION_B = "s-confirm-b";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

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

	private final ResumeService resumeService = mock(ResumeService.class);

	private final ConfirmGate gate;

	ConfirmCrossTenantSameRunIdTest() {
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
				mock(ToolActivityBeacon.class), List.of(), provider, Runnable::run, this.leases);
	}

	@BeforeEach
	void setUp() {
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.resumeService.resume(eq(RUN_ID), anyBoolean(), any(LeaseService.Lease.class)))
			.thenReturn(new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED,
					Map.of("runId", RUN_ID, "resumed", true)));
	}

	@Test
	void anotherIdentityCannotConfirmThePendingOfARunItDoesNotOwn() {
		seedSuspended(TENANT_A, USER_A, SESSION_A);

		ResponseEntity<?> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			response = controller().confirm(new ConfirmRequest(RUN_ID, TOOL_CALL_ID, true, "B 同意"));
		}

		assertThat(response.getStatusCode().value()).as("不属于当前身份的待决条目必须按不存在拒绝").isEqualTo(409);
		assertThat(bodyOf(response)).as("不得回显已受理").containsEntry("code", ErrorCodes.UNKNOWN_TOOL_CALL);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			PendingToolCall pending = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pending.getStatus()).as("A 的待决条目必须原样停在 PENDING").isEqualTo(PendingToolCall.PENDING);
			assertThat(pending.getReason()).as("不得替 A 写下任何理由").isNull();
			assertThat(this.store.get(RUN_ID).getStatus()).as("A 的快照状态不得被 B 的请求改写")
				.isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).as("不得替 A 抢决策 guard").isNull();
			assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("不得碰 A 的续跑权租约").isNull();
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.store.pendingRaw(RUN_ID, TOOL_CALL_ID)).as("B 的命名空间里不得凭空出现条目").isNull();
			assertThat(this.store.get(RUN_ID)).as("B 没有这一轮").isNull();
		}
		verify(this.resumeService, never()).resume(anyString(), anyBoolean(), any(LeaseService.Lease.class));
	}

	@Test
	void bothIdentitiesConfirmOnlyTheirOwnPendingEvenWithTheSameRunIdAndToolCallId() {
		seedSuspended(TENANT_A, USER_A, SESSION_A);
		seedSuspended(TENANT_B, USER_B, SESSION_B);

		ResponseEntity<?> forA;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			forA = controller().confirm(new ConfirmRequest(RUN_ID, TOOL_CALL_ID, true, "A 同意"));
		}
		assertThat(forA.getStatusCode().value()).as("本人身份的确认必须受理").isEqualTo(200);
		assertThat(bodyOf(forA)).containsEntry("status", PendingToolCall.APPROVED);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			PendingToolCall pendingB = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pendingB.getStatus()).as("A 的确认不得落到 B 的同名条目上")
				.isEqualTo(PendingToolCall.PENDING);
			assertThat(pendingB.getReason()).as("B 的条目不得被打上 A 的理由").isNull();
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).as("B 的决策 guard 不得被 A 写").isNull();
		}

		ResponseEntity<?> forB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			forB = controller().confirm(new ConfirmRequest(RUN_ID, TOOL_CALL_ID, false, "B 拒绝"));
		}
		assertThat(forB.getStatusCode().value()).as("B 自己那一轮的确认同样受理").isEqualTo(200);
		assertThat(bodyOf(forB)).containsEntry("status", PendingToolCall.REJECTED);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			PendingToolCall pendingA = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pendingA.getStatus()).as("B 的拒绝不得覆盖 A 的批准").isEqualTo(PendingToolCall.APPROVED);
			assertThat(pendingA.getReason()).as("A 的理由必须保持原样").isEqualTo("A 同意");
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).as("A 的决策 guard 仍是 A 自己的")
				.startsWith(PendingToolCall.APPROVED);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			PendingToolCall pendingB = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pendingB.getStatus()).isEqualTo(PendingToolCall.REJECTED);
			assertThat(pendingB.getReason()).as("B 的理由落在 B 自己的条目上").isEqualTo("B 拒绝");
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).startsWith(PendingToolCall.REJECTED);
		}
		// 归属收窄不得把正常路径一起关掉：两次受理各派发一次续跑（各自身份的租约）
		verify(this.resumeService, times(2)).resume(eq(RUN_ID), anyBoolean(), any(LeaseService.Lease.class));
		String leaseKeyA = runLeaseKeyOf(TENANT_A, USER_A);
		String leaseKeyB = runLeaseKeyOf(TENANT_B, USER_B);
		assertThat(leaseKeyA).as("两个身份的同名轮次必须落在不同的续跑租约键上").isNotEqualTo(leaseKeyB);
		assertThat(this.fake.string(leaseKeyA)).as("A 的续跑租约在 A 自己的键上").isNotNull();
		assertThat(this.fake.string(leaseKeyB)).as("B 的续跑租约在 B 自己的键上").isNotNull();
	}

	// ───────────────────────── helpers ─────────────────────────

	private void seedSuspended(long tenantId, long userId, String sessionId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, sessionId, AiContext.empty(),
					List.of(new org.springframework.ai.chat.messages.UserMessage("请执行导出")), this.codec);
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

	private String runLeaseKeyOf(long tenantId, long userId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			return this.store.runLeaseKey(RUN_ID);
		}
	}

	private AiController controller() {
		AiController built = new AiController(mock(AiAvailability.class), provider(null), provider(null), provider(null),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), this.gate, this.store,
				this.registry, this.cancellations, mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class),
				this.mapper, this.properties, mock(Environment.class), mock(JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
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
