package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InMemoryRunStore#isExecutionCompletedWithLedger} 的契约锚定：对齐生产
 * {@code COMPLETE_EXECUTION_WITH_LEDGER} Lua 的取消分支与原子落账。
 *
 * <p>为什么需要：续跑接管执行（Read 工具）会以 {@code respectCancel=true} 调结算。若替身不建模取消，
 * "取消后仍落 ledger/幂等索引"会静默失真，其它用例的 GREEN 就失去意义。
 */
class InMemoryRunStoreContractTest {

	private static final String RUN_ID = "run-atomic-settlement";

	private static final String CALL_ID = "call-settle";

	private static final String INSTANCE = "inst-test";

	private final InMemoryRunStore store = new InMemoryRunStore(true);

	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void setUp() {
		this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, "s-settle");
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setModelId("test-model");
		this.store.save(snapshot);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.APPROVED);
		this.store.putPending(RUN_ID, pending);
	}

	@AfterEach
	void tearDown() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void cancelledReadOnlySettlementIsRefusedWithoutWritingResultLedgerOrIndex() {
		RunStore.ClaimResult claim = this.store.tryClaimExecution(RUN_ID, CALL_ID, INSTANCE);
		assertThat(claim.isWon()).as("前置：认领成功，claim 位与 epoch 合法").isTrue();
		this.store.markCancelledForTest(RUN_ID);

		PendingToolCall settlement = this.store.pendingRaw(RUN_ID, CALL_ID);
		settlement.setStatus(PendingToolCall.EXECUTED);
		settlement.setResultText("已执行");

		boolean written = this.store.isExecutionCompletedWithLedger(RUN_ID, settlement, "已执行",
				claim.epoch(), INSTANCE, true);

		assertThat(written).as("取消键存在 ⇒ 只读结果不得结算").isFalse();
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus())
			.as("取消分支不得把完成态写进 pending").isNotEqualTo(PendingToolCall.EXECUTED);
		assertThat(this.store.ledger(RUN_ID)).as("取消分支不得写台账").isEmpty();
		assertThat(this.store.idemFor(RUN_ID, CALL_ID)).as("取消分支不得写幂等索引").isNull();
	}

	@Test
	void legalClaimSettlesPendingLedgerAndIndexAtomically() {
		RunStore.ClaimResult claim = this.store.tryClaimExecution(RUN_ID, CALL_ID, INSTANCE);
		assertThat(claim.isWon()).isTrue();

		PendingToolCall settlement = this.store.pendingRaw(RUN_ID, CALL_ID);
		settlement.setStatus(PendingToolCall.EXECUTED);
		settlement.setResultText("已执行");

		boolean written = this.store.isExecutionCompletedWithLedger(RUN_ID, settlement, "已执行",
				claim.epoch(), INSTANCE, true);

		assertThat(written).as("未取消且 claim 合法 ⇒ 原子落账成功").isTrue();
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus())
			.isEqualTo(PendingToolCall.EXECUTED);
		assertThat(this.store.ledger(RUN_ID)).as("台账恰一条").hasSize(1);
		assertThat(this.store.idemFor(RUN_ID, CALL_ID)).as("幂等索引指向该条台账")
			.isNotNull()
			.containsEntry("toolCallId", CALL_ID);
	}

	@Test
	void claimMismatchIsRefusedAsTypedInfrastructureError() {
		PendingToolCall settlement = this.store.pendingRaw(RUN_ID, CALL_ID);
		settlement.setStatus(PendingToolCall.EXECUTED);
		settlement.setResultText("已执行");

		assertThatThrownBy(() -> this.store.isExecutionCompletedWithLedger(RUN_ID, settlement, "已执行",
				0L, INSTANCE, false))
			.isInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining("CONFLICT_CLAIMED");
		assertThat(this.store.ledger(RUN_ID)).isEmpty();
		assertThat(this.store.idemFor(RUN_ID, CALL_ID)).isNull();
	}

	@Test
	void createWithRequestExtraBodyBuildsOnceAndSavesOnce() {
		AtomicInteger saves = new AtomicInteger();
		InMemoryRunStore counting = new InMemoryRunStore(true) {
			@Override
			public void save(RunSnapshot snapshot) {
				saves.incrementAndGet();
				super.save(snapshot);
			}
		};

		RunSnapshot snapshot = counting.createWithRequestExtraBody("run-create-once", "s-create-once",
				AiContext.empty(), List.of(), new MessageJsonCodec(new ObjectMapper()), 7L, "test-model",
				new RunSnapshot.FrozenParams(null, null, 1024, false), Map.of("k", "v"), "cid-1", "hi", 1_000L);

		assertThat(saves).as("一次性构造后只 save 一次（updatedAt 只刷一次）").hasValue(1);
		assertThat(snapshot.getUpdatedAtMs()).isPositive();
		assertThat(counting.get("run-create-once")).as("单次 save 后快照可见").isNotNull();
	}

	@Test
	void recoveryCursorOnlyContinuesSameDueScoreAndGreaterMember() {
		long zombieAgeMs = new AiProperties().getResume().getZombieAge().toMillis();
		long dueA = 1_000_000L;
		long dueC = 2_000_000L;
		seedDue("run-due-a", dueA - zombieAgeMs);
		seedDue("run-due-b", dueA - zombieAgeMs);
		seedDue("run-due-c", dueC - zombieAgeMs);

		long now = 10_000_000L;
		String memberA = RunKeyScope.indexMember(1L, 101L, "run-due-a");
		List<RunStore.RecoveryCandidate> page = this.store.recoveryDueRecordsAfter(now, dueA, memberA, 10);

		assertThat(page).as("游标模式不得把更高 score 的候选提前带出")
			.extracting(candidate -> candidate.ref().runId())
			.containsExactly("run-due-b");
		assertThat(this.store.recoveryDueRecords(now, 10))
			.extracting(candidate -> candidate.ref().runId())
			.contains("run-due-a", "run-due-b", "run-due-c");
	}

	@Test
	void settlementAndDecisionShareTheSamePerRunLock() throws Exception {
		RunStore.ClaimResult claim = this.store.tryClaimExecution(RUN_ID, CALL_ID, INSTANCE);
		assertThat(claim.isWon()).isTrue();
		PendingToolCall settlement = this.store.pendingRaw(RUN_ID, CALL_ID);
		settlement.setStatus(PendingToolCall.EXECUTED);
		settlement.setResultText("已执行");

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Object settleLock = this.store.runLockForTest(RUN_ID);
			Future<Boolean> settle;
			synchronized (settleLock) {
				settle = executor.submit(() -> this.store.isExecutionCompletedWithLedger(RUN_ID, settlement,
						"已执行", claim.epoch(), INSTANCE, false));
				assertThatThrownBy(() -> settle.get(150, TimeUnit.MILLISECONDS))
					.as("结算必须在 per-run 锁内，锁被持有时不得先行写入")
					.isInstanceOf(TimeoutException.class);
			}
			assertThat(settle.get(2, TimeUnit.SECONDS)).isTrue();

			String decisionRun = "run-atomic-decision";
			RunSnapshot snapshot = this.store.seedRun(decisionRun, "s-atomic-decision");
			snapshot.setTenantId(1L);
			snapshot.setUserId(101L);
			this.store.save(snapshot);
			PendingToolCall decisionPending = new PendingToolCall();
			decisionPending.setToolCallId("call-atomic-decision");
			decisionPending.setName("start_import");
			decisionPending.setKind(PendingToolCall.KIND_CONFIRM);
			decisionPending.setStatus(PendingToolCall.PENDING);
			this.store.putPending(decisionRun, decisionPending);

			Object decisionLock = this.store.runLockForTest(decisionRun);
			Future<RunStore.DecisionClaim> decision;
			synchronized (decisionLock) {
				decision = executor.submit(() -> this.store.tryApplyDecision(decisionRun, decisionPending));
				assertThatThrownBy(() -> decision.get(150, TimeUnit.MILLISECONDS))
					.as("决策的 pending+decided 多键写也必须在同一把 per-run 锁内")
					.isInstanceOf(TimeoutException.class);
			}
			assertThat(decision.get(2, TimeUnit.SECONDS).won()).isTrue();
		}
		finally {
			executor.shutdownNow();
		}
	}

	private void seedDue(String runId, long updatedAtMs) {
		RunSnapshot snapshot = this.store.seedRun(runId, "s-due");
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		this.store.save(snapshot);
		// seedRun/save 会刷新 updatedAt；直接改同一快照对象（runs 表持有引用）以固定 due 分数。
		snapshot.setUpdatedAtMs(updatedAtMs);
	}

}
