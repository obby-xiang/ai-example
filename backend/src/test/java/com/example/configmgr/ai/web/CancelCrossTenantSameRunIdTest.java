package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>跨租户同 runId 的取消归属</b>（真实 {@link RunStore} + 真实 {@link CancellationRegistry} + 真实键形）。
 *
 * <h2>被钉住的两条</h2>
 * <ol>
 * <li><b>无归属 ⇒ 无取消副作用</b>：{@code POST /cancel/{runId}} 在快照不属于当前身份时必须以
 *     {@code 404 UNKNOWN_RUN} 拒绝，且<b>不得</b>在自己命名空间里留下取消旗标（旧实现"先写旗标再
 *     填响应字段"：即使快照不是我的，也会写一个 {@code ai:cancel:<t>:<u>:<runId>}）—— 取消是会中断
 *     执行的副作用，不能靠"猜到的 runId"触发；</li>
 * <li><b>同 runId 两个身份互不影响</b>：A 取消自己的轮次后，B 的那一轮取消状态必须仍是
 *     {@code NOT_CANCELLED}（旗标键带身份段）。</li>
 * </ol>
 */
class CancelCrossTenantSameRunIdTest {

	private static final String RUN_ID = "run-cancel-shared";

	private static final String SESSION_A = "s-cancel-a";

	private static final String SESSION_B = "s-cancel-b";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final CancellationRegistry cancellations = new CancellationRegistry(this.fake.redis, this.properties);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	@Test
	void cancelWithoutOwnershipHasNoSideEffectEvenForAnExistingForeignRun() {
		seedRun(TENANT_A, USER_A, SESSION_A);

		ResponseEntity<?> response;
		CancellationRegistry.CancelState stateForB;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			response = controller().cancel(RUN_ID);
			stateForB = this.cancellations.cancelState(RUN_ID);
		}

		assertThat(response.getStatusCode().value()).as("无归属的取消必须被拒绝").isEqualTo(404);
		assertThat(response.getBody()).as("不得回显取消成功").isEqualTo(
				Map.of("code", com.example.configmgr.common.ErrorCodes.UNKNOWN_RUN, "runId", RUN_ID, "cancelled", false));
		assertThat(stateForB).as("拒绝时不得在任何命名空间留下取消旗标")
			.isEqualTo(CancellationRegistry.CancelState.NOT_CANCELLED);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.cancellations.cancelState(RUN_ID)).as("他人发起的取消不得落到本轮的旗标上")
				.isEqualTo(CancellationRegistry.CancelState.NOT_CANCELLED);
		}
	}

	@Test
	void cancelOfOneIdentitysRunDoesNotCancelTheOtherIdentitysSameRunId() {
		seedRun(TENANT_A, USER_A, SESSION_A);
		seedRun(TENANT_B, USER_B, SESSION_B);

		ResponseEntity<?> response;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			response = controller().cancel(RUN_ID);
		}

		assertThat(response.getStatusCode().value()).as("本人身份的轮次可以取消").isEqualTo(200);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.cancellations.cancelState(RUN_ID)).as("A 自己的取消必须可见")
				.isEqualTo(CancellationRegistry.CancelState.CANCELLED);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.cancellations.cancelState(RUN_ID)).as("B 的同名轮次不得被 A 的取消波及")
				.isEqualTo(CancellationRegistry.CancelState.NOT_CANCELLED);
		}
	}

	private void seedRun(long tenantId, long userId, String sessionId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, sessionId, AiContext.empty(), List.of(), this.codec);
		}
	}

	private AiController controller() {
		return new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), this.store, mock(RunRegistry.class), this.cancellations,
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper, this.properties,
				mock(Environment.class), mock(JobWatchdog.class));
	}

	private static <T> ObjectProvider<T> provider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		return provider;
	}

}
