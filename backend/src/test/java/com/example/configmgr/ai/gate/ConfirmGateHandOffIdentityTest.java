package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>交棒兜底路径也必须按记录身份登记续租</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code ConfirmGate#handOffLease} 在"租约键形不认得"（{@code runIdFromLeaseKey} 返回 {@code null}）
 * 时退回两参 {@code markDriving(lease)}。两参重载把<b>调用线程</b>的当前身份当成记录身份，
 * 而身份定键的键面（快照/待决/租约）要求"记录身份"：恢复型入口（交棒线程由续跑池/挂起池承载）
 * 上常常没有身份 ⇒ 续租回调以空身份执行 ⇒ 读 legacy 键 ⇒ 判"驱动者已不在" ⇒ 提前还租。
 *
 * <p>本条路径没有可靠的存活判据（键形不认得），所以判据仍是恒真；要修的是<b>身份来源</b>：
 * 用 {@code recordIdentityNow(runId)} 显式取记录身份，三参登记，不读线程。
 */
class ConfirmGateHandOffIdentityTest {

	private static final String RUN_ID = "run-handoff-identity";

	private static final String SESSION_ID = "s-handoff-identity";

	private static final String TOOL_CALL_ID = "call-handoff-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final LeaseService leases = mock(LeaseService.class);

	private final FakeRetryScheduler retry = new FakeRetryScheduler();

	private final ConfirmGate gate;

	@SuppressWarnings("unchecked")
	ConfirmGateHandOffIdentityTest() {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, this.leases, this.retry);
	}

	@Test
	void handOffOnAnUnrecognisedLeaseKeyRegistersRenewalWithTheRecordIdentityNotTheThreadIdentity()
			throws Exception {
		seedSuspendedUnderRecordIdentity();
		// 键形不认得的租约（不是本类拼的键）：交棒只能退回"无存活判据"的兜底分支。
		LeaseService.Lease lease = new LeaseService.Lease("ai:lease:opaque:" + RUN_ID, 11L, "inst-a");
		when(this.leases.tryAcquireFenced(anyString(), anyString(), any(Duration.class), any()))
			.thenReturn(Optional.of(lease));
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(eq(RUN_ID), any())).thenReturn(() -> {
		});
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);

		// 等待线程在<b>没有任何身份</b>时创建：交棒那一拍没有身份可"顺带"取（这正是缺陷形态）。
		Thread waiter = new Thread(() -> this.gate.awaitDecision(RUN_ID, pendingEntry()), "handoff-identity");
		waiter.setDaemon(true);
		waiter.start();
		awaitLatchRegistered(RUN_ID);

		assertThat(RequestContextUtils.currentOrNull()).as("前置：交棒线程/调用线程都没有身份").isNull();
		this.gate.submitDecision(RUN_ID, TOOL_CALL_ID, true, "同意");
		waiter.join(5_000L);
		assertThat(waiter.isAlive()).as("决策到达后等待方退出").isFalse();

		verify(this.leases).markDriving(same(lease), isNull(), eq(RECORD_IDENTITY));
		verify(this.leases, never()).markDriving(any(LeaseService.Lease.class), any(BooleanSupplier.class));
	}

	// ───────────────────────── helpers ─────────────────────────

	private void seedSuspendedUnderRecordIdentity() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(),
					new MessageJsonCodec(this.mapper));
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
		}
		PendingToolCall entry = pendingEntry();
		this.store.seedPending(RUN_ID, entry);
	}

	private static PendingToolCall pendingEntry() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(TOOL_CALL_ID);
		pending.setName("start_publish");
		pending.setType("function");
		pending.setArguments("{}");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	private void awaitLatchRegistered(String runId) throws InterruptedException {
		for (int i = 0; i < 300 && !this.gate.hasActiveLatch(runId); i++) {
			Thread.sleep(10L);
		}
		assertThat(this.gate.hasActiveLatch(runId)).as("等待侧闸门已登记").isTrue();
	}

	/** 一次性延迟重试的替身（本用例不涉及真实等待）。 */
	static final class FakeRetryScheduler implements ConfirmGate.RetryScheduler {

		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public AutoCloseable schedule(Runnable task, Duration delay) {
			this.tasks.add(task);
			return () -> {
			};
		}

	}

}
