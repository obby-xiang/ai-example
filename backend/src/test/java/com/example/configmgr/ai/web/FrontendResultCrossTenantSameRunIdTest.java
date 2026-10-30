package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
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
 * <b>跨身份同 runId 的前端结果回灌归属</b>（真实 {@link RunStore} + {@link FakeLeaseRedis} + 真实
 * {@link ConfirmGate} + 真实 {@link AiController}）。
 *
 * <h2>被钉住的三条</h2>
 * <ol>
 * <li><b>无归属 ⇒ 不落结果</b>：{@code POST /api/ai/frontend-tool-result} 在待决条目不属于调用身份时
 *     必须 {@code 409 UNKNOWN_TOOL_CALL}，本人的条目仍 {@code PENDING}、状态不改、不派发续跑；</li>
 * <li><b>无归属 ⇒ 也不得取消回灌</b>：{@code cancelled=true}（关闭表单/放弃）同样改变状态，因此同样
 *     按不存在拒绝 —— 别人的卡不得被"猜到的 runId"收口成 {@code FRONTEND_CANCELLED}；</li>
 * <li><b>同 runId + 同 toolCallId 各自动自己</b>：A 回灌、B 放弃时，两条条目分别落在各自的键上，
 *     互不覆盖、互不收敛对方的卡。</li>
 * </ol>
 */
class FrontendResultCrossTenantSameRunIdTest {

	private static final String RUN_ID = "run-frontend-shared";

	private static final String TOOL_CALL_ID = "call-frontend-shared";

	private static final String SESSION_A = "s-frontend-a";

	private static final String SESSION_B = "s-frontend-b";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private static final String RESULT_A = "{\"keyword\":\"CNY\"}";

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

	FrontendResultCrossTenantSameRunIdTest() {
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
	void anotherIdentityCannotFeedAResultIntoThePendingOfARunItDoesNotOwn() {
		seedSuspended(TENANT_A, USER_A, SESSION_A);

		ResponseEntity<?> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			response = controller().frontendToolResult(
					new FrontendToolResultRequest(RUN_ID, TOOL_CALL_ID, RESULT_A, "frontend-form", false, null, null,
							null));
		}

		assertThat(response.getStatusCode().value()).as("不属于当前身份的待决条目必须按不存在拒绝").isEqualTo(409);
		assertThat(bodyOf(response)).containsEntry("code", ErrorCodes.UNKNOWN_TOOL_CALL);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			PendingToolCall pending = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pending.getStatus()).as("A 的条目必须原样停在 PENDING").isEqualTo(PendingToolCall.PENDING);
			assertThat(pending.getResultText()).as("不得把 B 的结果写进 A 的条目").isNull();
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
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
	void anotherIdentityCannotCancelThePendingOfARunItDoesNotOwn() {
		seedSuspended(TENANT_A, USER_A, SESSION_A);

		ResponseEntity<?> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			response = controller().frontendToolResult(
					new FrontendToolResultRequest(RUN_ID, TOOL_CALL_ID, null, "frontend-form", true, null, null, null));
		}

		assertThat(response.getStatusCode().value()).as("放弃同样改变状态：无归属必须拒绝").isEqualTo(409);
		assertThat(bodyOf(response)).containsEntry("code", ErrorCodes.UNKNOWN_TOOL_CALL);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			PendingToolCall pending = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pending.getStatus()).as("A 的卡不得被 B 收口成 FRONTEND_CANCELLED")
				.isEqualTo(PendingToolCall.PENDING);
			assertThat(pending.getReason()).as("不得留下 B 的放弃理由").isNull();
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).as("不得替 A 抢决策 guard").isNull();
			assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("不得碰 A 的续跑权租约").isNull();
		}
		verify(this.resumeService, never()).resume(anyString(), anyBoolean(), any(LeaseService.Lease.class));
	}

	@Test
	void bothIdentitiesSettleOnlyTheirOwnPendingEvenWithTheSameRunIdAndToolCallId() {
		seedSuspended(TENANT_A, USER_A, SESSION_A);
		seedSuspended(TENANT_B, USER_B, SESSION_B);

		ResponseEntity<?> forA;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			forA = controller().frontendToolResult(
					new FrontendToolResultRequest(RUN_ID, TOOL_CALL_ID, RESULT_A, "frontend-form", false, null, null,
							null));
		}
		assertThat(forA.getStatusCode().value()).as("本人身份的回灌必须受理").isEqualTo(200);
		assertThat(bodyOf(forA)).containsEntry("status", PendingToolCall.FRONTEND_RESULT);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			PendingToolCall pendingB = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pendingB.getStatus()).as("A 的回灌不得落到 B 的同名条目上")
				.isEqualTo(PendingToolCall.PENDING);
			assertThat(pendingB.getResultText()).as("B 的条目不得被写上 A 的结果").isNull();
			assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).as("B 的决策 guard 不得被 A 写").isNull();
		}

		ResponseEntity<?> forB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			forB = controller().frontendToolResult(
					new FrontendToolResultRequest(RUN_ID, TOOL_CALL_ID, null, "frontend-form", true, null, null, null));
		}
		assertThat(forB.getStatusCode().value()).as("B 自己那一轮的放弃同样受理").isEqualTo(200);
		assertThat(bodyOf(forB)).containsEntry("status", PendingToolCall.FRONTEND_CANCELLED);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			PendingToolCall pendingA = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pendingA.getStatus()).as("B 的放弃不得覆盖 A 已回灌的结果")
				.isEqualTo(PendingToolCall.FRONTEND_RESULT);
			assertThat(pendingA.getResultText()).as("A 的结果必须原样保留").isEqualTo(RESULT_A);
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).startsWith(PendingToolCall.FRONTEND_RESULT);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			PendingToolCall pendingB = this.store.pendingRaw(RUN_ID, TOOL_CALL_ID);
			assertThat(pendingB.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
			assertThat(this.store.decisionOf(RUN_ID, TOOL_CALL_ID)).startsWith(PendingToolCall.FRONTEND_CANCELLED);
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
					List.of(new org.springframework.ai.chat.messages.UserMessage("请填写条件")), this.codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId(TOOL_CALL_ID);
			pending.setName("generative_form");
			pending.setType("function");
			pending.setArguments("{}");
			pending.setKind(PendingToolCall.KIND_FRONTEND);
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
