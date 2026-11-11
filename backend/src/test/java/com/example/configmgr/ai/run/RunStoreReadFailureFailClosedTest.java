package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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

	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void seedPending() {
		this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		this.store.putPending(RUN_ID, pending);
	}

	@org.junit.jupiter.api.AfterEach
	void releaseIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void fenceReadFailureRejectsTheClaimInsteadOfAssumingEpochZero() {
		this.lease.tryAcquireFenced(this.store.runLeaseKey(RUN_ID), this.store.fenceKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow();
		assertThat(this.store.heldRunEpoch(RUN_ID)).as("本实例持租，才会走到 fence 读取").isPositive();

		// 现行认领把 fence 读放在 CLAIM_EXECUTION Lua 内（写之前）；旧 Java 读口与 A1 计数已不存在，
		// 故障注入落在脚本执行内部，断言受控异常向上表达且没有任何认领/执行证据。
		this.fake.failClaimFenceRead();

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.as("脚本内 fence 读不出来 ⇒ 拒绝认领（不得按 epoch=0 放行）")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fence");
		assertThat(this.fake.table(this.store.claimKey(RUN_ID)))
			.as("拒绝认领不得留下 claim 记录")
			.doesNotContainKey(CALL_ID);
		assertThat(this.store.pendingRaw(RUN_ID, CALL_ID).isExecuted())
			.as("脚本在写之前失败 ⇒ 不得留下执行证据").isFalse();
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
			.hasMessageContaining("decision guard");
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
			.hasMessageContaining("decision guard");
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
			.hasMessageContaining("decision guard");
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
		// 与请求作用域一致的合法归属：否则会在 ownership 前置闸提前拒绝，测不到 save 的基础设施失败路径。
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setStatus(RunSnapshot.RUNNING);
		// 当前无租约的非取消状态写走取消守卫 Lua（TERMINAL_SET_IF_NOT_CANCELLED，redis.execute），
		// 不再走 values.set —— 故障注入因此落在脚本执行入口，与产品代码当前的写路径一致。
		doThrow(new IllegalStateException("模拟快照写失败"))
			.when(this.fake.redis)
			.execute(argThat((RedisScript<?> script) -> script.getScriptAsString()
				.contains("TERMINAL_SET_IF_NOT_CANCELLED")), anyList(), any(Object[].class));
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
	void claimScriptFailureAfterWritesIsNotReportedAsSuccessAndPartialStateIsObservable() {
		// 真实 Redis 的 EVAL 报错不回滚已写内容：注入点在 claim/pending 两笔 HSET 之后、TTL 写之前，
		// 脚本按写序已推进 claim 与 pending。调用方必须看到失败，且只能断言脚本写序可保证的状态。
		this.fake.failClaimTtlWrite();

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.as("脚本失败不得被包装成 ClaimResult.WON")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TTL");
		assertThat(this.fake.table(this.store.claimKey(RUN_ID)))
			.as("按真实写序：claim HSET 已生效且值属于本实例").containsEntry(CALL_ID,
					this.store.heldRunEpoch(RUN_ID) + "|" + this.store.instanceId());
		PendingToolCall raw = this.store.pendingRaw(RUN_ID, CALL_ID);
		assertThat(raw.isExecuted()).as("pending 的 executed 标记同样已写入（Lua 不回滚）").isTrue();
		assertThat(raw.getExecutedBy()).isEqualTo(this.store.instanceId());
	}

	@Test
	void failedClaimNeverDeletesOrOverwritesAnExistingClaimOfAnotherInstance() {
		// 旧 rollbackClaim 的私有方法已随原子脚本删除；等价负向覆盖 = 失败路径不得动他人 claim。
		String claimKey = this.store.claimKey(RUN_ID);
		this.fake.table(claimKey).put(CALL_ID, "9|inst-b");
		this.fake.failClaimPendingRead();

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("pending");
		assertThat(this.fake.table(claimKey))
			.as("失败路径不得删除或覆盖他人认领（旧值仍原样保留）").containsEntry(CALL_ID, "9|inst-b");
	}

	@Test
	void claimUsesRawPendingSoGuardReadFailureDoesNotCreateAFalseClaim() {
		this.lease.tryAcquireFenced(this.store.runLeaseKey(RUN_ID), this.store.fenceKey(RUN_ID),
				this.properties.getLease().getTtl()).orElseThrow();
		HashOperations<String, Object, Object> hash = this.fake.redis.opsForHash();
		when(hash.get(RunStore.runScopedKey(RunStore.DECIDED_PREFIX, RUN_ID), CALL_ID))
			.thenThrow(new IllegalStateException("模拟 claim 期间的 guard 读失败"));

		RunStore.ClaimResult claim = this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId());

		assertThat(claim.isWon()).as("claim 路径不得走 guard 收敛读，guard 读失败不影响认领").isTrue();
		PendingToolCall marked = this.store.pendingRaw(RUN_ID, CALL_ID);
		assertThat(marked.isExecuted()).as("认领后条目已标记 executed（不是只写 claim 的假认领）").isTrue();
		assertThat(marked.getExecutedBy()).isEqualTo(this.store.instanceId());
	}

	@Test
	void rawPendingReadFailureRejectsTheClaimWithoutClaimOrExecutionEvidence() {
		// 脚本内 raw pending 读失败（写之前）⇒ 拒绝认领，且不留下 claim/执行证据。
		this.fake.failClaimPendingRead();

		assertThatThrownBy(() -> this.store.tryClaimExecution(RUN_ID, CALL_ID, this.store.instanceId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("pending");
		assertThat(this.fake.table(this.store.claimKey(RUN_ID)))
			.as("读原始条目失败不得留下 24h 假认领").doesNotContainKey(CALL_ID);
		PendingToolCall raw = this.store.pendingRaw(RUN_ID, CALL_ID);
		assertThat(raw.isExecuted()).as("不得留下 executed=true 的假已执行").isFalse();
		assertThat(raw.getStatus()).isEqualTo(PendingToolCall.PENDING);
	}

}
