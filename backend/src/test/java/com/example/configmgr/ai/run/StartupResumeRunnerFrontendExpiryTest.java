package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>F2：park 后前端工具的 {@code expiresAt} 必须由"任一实例"消费</b>。
 *
 * <h2>缺陷</h2>
 * 到期收敛只有原实例进程内的定时器（{@code ConfirmGate#scheduleParkedFrontendExpiry}）：
 * 实例 A park 后被杀，或被调度到 B 重挂，{@code PendingToolCall.expiresAt} 虽然已经持久化，
 * 却<b>没有任何读侧用它</b> —— 条目永远 PENDING、该轮永远 SUSPENDED。
 *
 * <h2>本批口径</h2>
 * 运行期扫描/重挂按 {@code expiresAt <= now && status=PENDING && kind=FRONTEND} 调用既有
 * {@code expireFrontend}（经决策 guard 抢一次写权），到期是系统裁决：收敛条目 + 派发一次续跑。
 * <b>确认门无上限</b>（{@code timeoutSeconds == null}）的语义不变 —— 本用例显式钉住。
 */
class StartupResumeRunnerFrontendExpiryTest {

	private static final String RUN_ID = "run-parked-expiry-sweep";

	private static final String SESSION_ID = "s-parked-expiry-sweep";

	private static final String FRONTEND_CALL = "call-nav-expired";

	private static final String CONFIRM_CALL = "call-confirm-waiting";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private ResumeService resumeService;

	private ConfirmGate gate;

	private StartupResumeRunner runner;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		// 扫描的候选清单来自真 listRuns（ai:runs 索引），只有 resume 本身用 spy 桩住
		// （死卡兜底的派发动作不是本用例的被测对象）。
		this.resumeService = org.mockito.Mockito.spy(new ResumeService(this.store, this.registry, this.codec,
				mock(ResilientChatService.class), mock(SpToolCallingManager.class),
				mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class), this.redis,
				this.properties, this.cancellations, new ToolResultLimiter(this.properties)));
		org.mockito.Mockito.doReturn(new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()))
			.when(this.resumeService).resume(anyString());
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run);
		this.runner = new StartupResumeRunner(this.resumeService, this.store, this.properties, this.availability);
		wireConfirmGate(this.runner, this.gate);
	}

	@Test
	void sweepConsumesThePersistedExpiresAtExactlyOnceAndLeavesConfirmGatesWaiting() {
		seedSuspendedRunWithExpiredFrontendAndWaitingConfirm();

		Map<String, Object> first = this.runner.sweep();

		assertThat(expiredRunIds(first)).as("任实例的扫描都能消费 expiresAt").contains(RUN_ID);
		PendingToolCall expired = this.store.pending(RUN_ID, FRONTEND_CALL);
		assertThat(expired.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
		assertThat(expired.getOutcome()).isEqualTo(ToolOutcome.TIMEOUT.wire());
		assertThat(expired.getCode()).isEqualTo(ToolOutcome.CODE_FRONTEND_TIMEOUT);
		assertThat(expired.getReason()).isEqualTo("FRONTEND_TIMEOUT");
		assertThat(this.store.archivedTypes(RUN_ID)).as("结局帧入档（重挂可见）")
			.contains("frontend_tool_result");
		assertThat(this.store.pending(RUN_ID, CONFIRM_CALL).getStatus())
			.as("确认门没有到期分支：无限等待语义不变")
			.isEqualTo(PendingToolCall.PENDING);
		verify(this.resumeService, times(1)).resume(RUN_ID);

		Map<String, Object> second = this.runner.sweep();

		assertThat(expiredRunIds(second)).as("条目已收敛 ⇒ 下一次扫描没有可消费对象").isEmpty();
		assertThat(this.store.archivedTypes(RUN_ID)).as("到期结局帧恰一条").containsOnlyOnce("frontend_tool_result");
		verify(this.resumeService, times(1)).resume(RUN_ID);
	}

	/** SUSPENDED、业务活动超龄、一个到期前端工具 + 一个仍在等人（无上限）的确认门。 */
	private void seedSuspendedRunWithExpiredFrontendAndWaitingConfirm() {
		RunSnapshot snapshot = this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
		snapshot.setTenantId(TENANT_ID);
		snapshot.setUserId(USER_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
		long atMs = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
		snapshot.setUpdatedAtMs(atMs);
		this.store.appendEvent(RUN_ID, Map.of("type", "tool_start", "seq", 1L, "atMs", atMs));

		PendingToolCall frontend = new PendingToolCall();
		frontend.setToolCallId(FRONTEND_CALL);
		frontend.setName("navigate_to");
		frontend.setKind(PendingToolCall.KIND_FRONTEND);
		frontend.setStatus(PendingToolCall.PENDING);
		frontend.setAtMs(atMs);
		frontend.setExpiresAt(atMs + 120_000L);
		frontend.setReq(Boolean.TRUE);
		this.store.putPending(RUN_ID, frontend);

		PendingToolCall confirm = new PendingToolCall();
		confirm.setToolCallId(CONFIRM_CALL);
		confirm.setName("start_publish");
		confirm.setKind(PendingToolCall.KIND_CONFIRM);
		confirm.setStatus(PendingToolCall.PENDING);
		confirm.setAtMs(atMs);
		confirm.setReq(Boolean.TRUE);
		this.store.putPending(RUN_ID, confirm);
	}

	/** 装配确认门；旧实现没有这条接线时保持未注入 —— 本用例随后必须因"到期条目没人消费"而红。 */
	private static void wireConfirmGate(StartupResumeRunner runner, ConfirmGate confirmGate) {
		if (org.springframework.util.ReflectionUtils.findField(StartupResumeRunner.class, "confirmGate") != null) {
			ReflectionTestUtils.setField(runner, "confirmGate", confirmGate);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> expiredRunIds(Map<String, Object> report) {
		return ((List<Map<String, Object>>) report.getOrDefault("expired", List.of())).stream()
			.map(item -> String.valueOf(item.get("runId")))
			.toList();
	}

}
