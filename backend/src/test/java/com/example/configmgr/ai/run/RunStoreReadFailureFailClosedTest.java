package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * A1 / A6 的 fail-closed 口径（Cluster / 5.0 可观测批）。
 *
 * <p>A1：fence 读失败不得折成 {@code 0}（= 无 fence）后放行认领；A6：决策 guard 读失败不得折成
 * "未决策"后重建卡。两处都必须让读失败<b>向上表达</b>，而不是伪装成"没有数据"。
 */
class RunStoreReadFailureFailClosedTest {

	private static final String RUN_ID = "run-read-failure";

	private static final String CALL_ID = "call-1";

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.lease);

	@BeforeEach
	void seedPending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		this.store.putPending(RUN_ID, pending);
	}

	@Test
	void fenceReadFailureRejectsTheClaimInsteadOfAssumingEpochZero() {
		this.lease.tryAcquire(this.store.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("本实例持租，才会走到 fence 读取").isPositive();

		long before = RedisDegradation.count(RedisDegradation.A1_FENCE_READ);
		when(this.fake.redis.opsForValue().get(this.store.fenceKey(RUN_ID)))
			.thenThrow(new IllegalStateException("模拟 fence 读失败"));

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.as("fence 读不出来 ⇒ 拒绝认领（不得按 epoch=0 放行）")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fence");
		assertThat(this.fake.table(this.store.claimKey(RUN_ID)))
			.as("拒绝认领不得留下 claim 记录")
			.doesNotContainKey(CALL_ID);
		assertThat(RedisDegradation.count(RedisDegradation.A1_FENCE_READ)).isGreaterThan(before);
	}

	@Test
	void decisionGuardReadFailureIsNotTreatedAsUndecided() {
		long before = RedisDegradation.count(RedisDegradation.A6_DECISION_GUARD_READ);
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟决策 guard 读失败"));

		assertThatThrownBy(() -> this.store.pending(RUN_ID, CALL_ID))
			.as("单条读侧 WAL 收敛：guard 读不出来 ⇒ 不得把卡当成待人工输入")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("决策 guard");
		assertThat(RedisDegradation.count(RedisDegradation.A6_DECISION_GUARD_READ)).isGreaterThan(before);
	}

	@Test
	void decisionGuardBatchReadFailureIsNotTreatedAsUndecided() {
		long before = RedisDegradation.count(RedisDegradation.A6_DECISION_GUARD_READ);
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.entries(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID)))
			.thenThrow(new IllegalStateException("模拟决策 guard 批量读失败"));

		assertThatThrownBy(() -> this.store.pendings(RUN_ID))
			.as("整批读侧 WAL 收敛：guard 读不出来 ⇒ 不得整批按未决返回")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("决策 guard");
		assertThat(RedisDegradation.count(RedisDegradation.A6_DECISION_GUARD_READ)).isGreaterThan(before);
	}

	@Test
	void decisionOfReadFailureIsNotTreatedAsUndecided() {
		long before = RedisDegradation.count(RedisDegradation.A6_DECISION_GUARD_READ);
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟 decisionOf 读失败"));

		assertThatThrownBy(() -> this.store.decisionOf(RUN_ID, CALL_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("决策 guard");
		assertThat(RedisDegradation.count(RedisDegradation.A6_DECISION_GUARD_READ)).isGreaterThan(before);
	}

	@Test
	void unknownDecisionMarkerStillReadsAsNull() {
		// 对照组：读成功且无该 field 仍是"未决策"（不依赖 Mockito 默认值空桩）。
		assertThat(this.store.decisionOf(RUN_ID, "call-unknown")).isNull();
	}

	@Test
	void cancelPendingsUsesLenientReadSoGuardFailureDoesNotLeaveEntriesPending() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.entries(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID)))
			.thenThrow(new IllegalStateException("模拟取消收尾时的 guard 读失败"));

		int changed = this.store.cancelPendings(RUN_ID);

		assertThat(changed).as("guard 读失败也必须把 PENDING 条目收敛成 CANCELLED").isEqualTo(1);
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
	}

	@Test
	void lenientPendingKeepsTheRawEntryWhenGuardIsUnreadable() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟确认门等待期的 guard 读失败"));

		assertThat(this.store.pendingLenient(RUN_ID, CALL_ID).getStatus())
			.as("非抛读口返回原始条目，等待循环可继续").isEqualTo(PendingToolCall.PENDING);
		assertThatThrownBy(() -> this.store.pending(RUN_ID, CALL_ID))
			.as("强一致读口仍 fail-closed").isInstanceOf(IllegalStateException.class);
	}

	@Test
	void cancelPendingsDoesNotBlockWhenThePendingHashItselfIsUnreadable() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.entries(this.store.pendingKey(RUN_ID)))
			.thenThrow(new IllegalStateException("模拟待决 HASH 整表读失败"));

		assertThat(this.store.pendingsLenient(RUN_ID)).as("非抛读口降级为空表").isEmpty();
		assertThat(this.store.cancelPendings(RUN_ID)).as("取消收尾不得因整表读失败中断").isZero();
	}

	@Test
	void strictPendingsAndSnapshotSaveFailuresAreTypedInfrastructureErrors() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.entries(this.store.pendingKey(RUN_ID)))
			.thenThrow(new IllegalStateException("模拟待决整表读失败"));
		assertThatThrownBy(() -> this.store.pendings(RUN_ID))
			.isInstanceOf(AiStateUnavailableException.class);

		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		ValueOperations<String, String> values = this.fake.redis.opsForValue();
		doThrow(new IllegalStateException("模拟快照写失败"))
			.when(values).set(org.mockito.ArgumentMatchers.eq(this.store.runKey(RUN_ID)),
					org.mockito.ArgumentMatchers.anyString(),
					org.mockito.ArgumentMatchers.any(java.time.Duration.class));
		assertThatThrownBy(() -> this.store.save(snapshot))
			.isInstanceOf(AiStateUnavailableException.class);
	}

	@Test
	void pendingRawReadFailureIsTypedInfrastructureError() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(this.store.pendingKey(RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟 Redis 读失败"));

		assertThatThrownBy(() -> this.store.pendingRaw(RUN_ID, CALL_ID))
			.isInstanceOf(AiStateUnavailableException.class);
	}

	@Test
	void putPendingWriteFailureIsTypedInfrastructureError() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		doThrow(new IllegalStateException("模拟 Redis 写失败")).when(hash)
			.put(org.mockito.ArgumentMatchers.eq(this.store.pendingKey(RUN_ID)),
					org.mockito.ArgumentMatchers.eq(CALL_ID), org.mockito.ArgumentMatchers.anyString());

		assertThatThrownBy(() -> this.store.putPending(RUN_ID, this.store.pendingRaw(RUN_ID, CALL_ID)))
			.isInstanceOf(AiStateUnavailableException.class);
	}

	@Test
	void failedClaimRestoresExecutedFalseWhenPutSucceedsButExpireFails() {
		when(this.fake.redis.expire(org.mockito.ArgumentMatchers.eq(this.store.pendingKey(RUN_ID)),
				org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
			.thenThrow(new IllegalStateException("模拟 expire 失败"));

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.table(this.store.claimKey(RUN_ID))).doesNotContainKey(CALL_ID);
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).isExecuted())
			.as("回滚后不得留下 executed=true 的假已执行").isFalse();
	}

	@Test
	void rollbackClaimDeletesOnlyWhenTheValueStillMatches() throws Exception {
		String claimKey = this.store.claimKey(RUN_ID);
		this.fake.table(claimKey).put(CALL_ID, "9|inst-b");
		java.lang.reflect.Method rollback = RunStore.class.getDeclaredMethod("rollbackClaim", String.class,
				String.class, String.class);
		rollback.setAccessible(true);

		rollback.invoke(this.store, RUN_ID, CALL_ID, "0|inst-a");
		assertThat(this.fake.table(claimKey)).as("值不匹配 ⇒ 不得误删他人认领").containsKey(CALL_ID);

		this.fake.table(claimKey).put(CALL_ID, "0|inst-a");
		rollback.invoke(this.store, RUN_ID, CALL_ID, "0|inst-a");
		assertThat(this.fake.table(claimKey)).doesNotContainKey(CALL_ID);
	}

	@Test
	void claimUsesRawPendingSoGuardReadFailureDoesNotCreateAFalseClaim() {
		this.lease.tryAcquire(this.store.runLeaseKey(RUN_ID), this.properties.getLease().getTtl()).orElseThrow();
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟 claim 期间的 guard 读失败"));

		RunStore.ClaimResult claim = this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId());

		assertThat(claim.won()).as("claim 路径不得走 guard 收敛读，guard 读失败不影响认领").isTrue();
		PendingToolCall marked = this.store.pendingRaw(RUN_ID, CALL_ID);
		assertThat(marked.isExecuted()).as("认领后条目已标记 executed（不是只写 claim 的假认领）").isTrue();
		assertThat(marked.getExecutedBy()).isEqualTo(this.store.instanceId());
	}

	@Test
	void claimIsRolledBackWhenRawPendingReadFails() {
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(this.store.pendingKey(RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟 pendingRaw 读失败"));

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.isInstanceOf(IllegalStateException.class);
		assertThat(this.fake.table(this.store.claimKey(RUN_ID)))
			.as("读原始条目失败必须回滚 claim，不能留下 24h 假认领")
			.doesNotContainKey(CALL_ID);
	}

}
