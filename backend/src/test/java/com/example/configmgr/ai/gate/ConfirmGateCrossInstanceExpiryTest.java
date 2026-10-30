package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>F2：park 到期收敛的"任一实例可做、且只赢一次"</b>（原实例已死）。
 *
 * <h2>现场</h2>
 * 实例 A park 后进程消失（它的进程内到期定时器随之消失）；客户端重挂到实例 B。
 * {@code PendingToolCall.expiresAt} 已经持久化，B 必须能消费它 —— 但两个实例同时到点时
 * 只能有一个写者落库/发结局帧。
 *
 * <h2>判据</h2>
 * 收敛走 {@link ConfirmGate#convergeExpiredParkedFrontend(String)}：内部用决策 guard
 * （{@code ai:decided} 的 {@code HSETNX}）抢写权 ⇒ 第二次调用什么都不写、不再发帧。
 */
class ConfirmGateCrossInstanceExpiryTest {

	private static final String RUN_ID = "run-cross-instance-expiry";

	private static final String SESSION_ID = "s-cross-instance-expiry";

	private static final String CALL_ID = "call-cross-instance-expiry";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	/**
	 * 决策 guard 的进程内替身：复刻 {@code ai:decided} 的 {@code HSETNX} 语义
	 * （production 真实现是 {@code RunStore.tryRecordDecision}，InMemory 替身没有 Redis）。
	 * {@link #guardDown} 用来复刻"guard 设施不可用"（真实现抛 {@code AiStateUnavailableException}）。
	 */
	private final java.util.Map<String, String> decisions = new java.util.concurrent.ConcurrentHashMap<>();

	private volatile boolean guardDown;

	private final InMemoryRunStore store = new InMemoryRunStore() {
		@Override
		public RunStore.DecisionClaim tryRecordDecision(String runId, String toolCallId, String status) {
			if (ConfirmGateCrossInstanceExpiryTest.this.guardDown) {
				throw new com.example.configmgr.common.AiStateUnavailableException(
						"无法写入决策 guard " + toolCallId, new IllegalStateException("redis down"));
			}
			String key = runId + "|" + toolCallId;
			String value = status + "|" + System.currentTimeMillis() + "|instance-test";
			String previous = ConfirmGateCrossInstanceExpiryTest.this.decisions.putIfAbsent(key, value);
			return new RunStore.DecisionClaim(previous == null, previous == null ? value : previous);
		}
	};

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ResumeService resumeService = mock(ResumeService.class);

	private ConfirmGate instanceA;

	private ConfirmGate instanceB;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.resumeService.resume(anyString()))
			.thenReturn(new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()));
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.resumeService);
		// 两个实例共享同一份 Redis 替身（store/registry）；进程内状态（闸门/latch）各自独立。
		this.instanceA = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run);
		this.instanceB = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run);
	}

	@Test
	void theOtherInstanceConvergesTheExpiredCardAndOnlyOneWriterWins() {
		seedParkedExpiredFrontend();

		// 帧册写出器按"身份 + runId"分桶：没有请求身份时 SseChatEmitter 会 fail-closed（不落档）。
		// 生产里重挂/扫描都在记录身份作用域内，用例照做。
		try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored = com.example.configmgr.ai.tenant.RequestContextUtils
			.useContext(TENANT_ID, USER_ID)) {
			int byB = this.instanceB.convergeExpiredParkedFrontend(RUN_ID);
			int byA = this.instanceA.convergeExpiredParkedFrontend(RUN_ID);

			assertThat(byB).as("原实例已死时，B 必须能消费持久化的 expiresAt").isEqualTo(1);
			assertThat(byA).as("同一张卡只允许一个结论写者").isZero();
		}
		PendingToolCall expired = this.store.pending(RUN_ID, CALL_ID);
		assertThat(expired.getStatus()).isEqualTo(PendingToolCall.TIMEOUT);
		assertThat(expired.getCode()).isEqualTo(ToolOutcome.CODE_FRONTEND_TIMEOUT);
		assertThat(this.store.archivedTypes(RUN_ID)).as("结局帧恰一条").containsOnlyOnce("frontend_tool_result");
		verify(this.resumeService, times(1)).resume(RUN_ID);
	}

	/**
	 * 另一实例已经抢到这张卡的结论（guard 有值、条目还没落库）：本实例<b>什么都不写</b>，
	 * 也不发第二条结局帧 —— "只赢一次"在跨实例上的另一半。
	 */
	@Test
	void aCardWhoseConclusionIsAlreadyClaimedIsNotWrittenAgain() {
		seedParkedExpiredFrontend();
		this.decisions.put(RUN_ID + "|" + CALL_ID,
				PendingToolCall.TIMEOUT + "|" + System.currentTimeMillis() + "|slow-winner");

		try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored = com.example.configmgr.ai.tenant.RequestContextUtils
			.useContext(TENANT_ID, USER_ID)) {
			assertThat(this.instanceB.convergeExpiredParkedFrontend(RUN_ID))
				.as("结论已由别的写者持有 ⇒ 本写者不落库、不发帧")
				.isZero();
		}
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus())
			.as("败方不得覆盖条目（等待 guard 持有者落库）")
			.isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("frontend_tool_result");
		verify(this.resumeService, times(0)).resume(anyString());
	}

	/**
	 * <b>guard 设施不可用 ⇒ 失败关闭</b>：这一拍不落库、不发结局帧、不派发续跑，条目保持 PENDING。
	 *
	 * <p>旧实现把 guard 异常吞成 {@code null} 并退回单写路径：多实例下两个实例都会认为自己拿到
	 * 写权，各写一次 {@code TIMEOUT}、各发一条 {@code frontend_tool_result} —— 正是 F2 要消灭的形态。
	 */
	@Test
	void guardFailureFailsClosedWithoutWritingOrDispatching() {
		seedParkedExpiredFrontend();
		this.guardDown = true;

		try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored = com.example.configmgr.ai.tenant.RequestContextUtils
			.useContext(TENANT_ID, USER_ID)) {
			assertThat(this.instanceB.convergeExpiredParkedFrontend(RUN_ID))
				.as("guard 不可用 ⇒ 本拍不收敛（外层 catch 记录，下一拍重试）")
				.isZero();
		}
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus())
			.as("不得落库：条目仍是 PENDING")
			.isEqualTo(PendingToolCall.PENDING);
		assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("frontend_tool_result");
		verify(this.resumeService, times(0)).resume(anyString());
	}

	/** SUSPENDED、一个已到期（{@code expiresAt} 在过去）的即时前端工具条目。 */
	private void seedParkedExpiredFrontend() {		RunSnapshot snapshot = this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
		snapshot.setTenantId(TENANT_ID);
		snapshot.setUserId(USER_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
		long atMs = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
		snapshot.setUpdatedAtMs(atMs);
		this.store.appendEvent(RUN_ID, Map.of("type", "tool_start", "seq", 1L, "atMs", atMs));

		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("download_export_files");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setAtMs(atMs);
		pending.setExpiresAt(atMs + 120_000L);
		pending.setReq(Boolean.TRUE);
		this.store.putPending(RUN_ID, pending);
	}

}
