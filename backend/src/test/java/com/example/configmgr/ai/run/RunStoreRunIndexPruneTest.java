package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 恢复到期索引（{@code ai:recovery:due}）的<b>有界核对</b>：每拍最多 64 项、按偏移轮转，
 * 死成员（快照不存在）从到期索引与本人身份 run 索引一起摘除；终态成员只摘到期索引。
 *
 * <p>证红：去掉 score 条件 CAS ⇒ 并发更新会被旧读者误删；去掉"按成员身份读快照"⇒ 系统线程
 * 读不到任何带身份的轮次、全部误删；去掉批大小/偏移 ⇒ 一次全量扫描（无界）。
 */
@ExtendWith(OutputCaptureExtension.class)
class RunStoreRunIndexPruneTest {

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private static final String LIVE = "run-live";

	private static final String STALE = "run-stale";

	private static final String SESSION_ID = "s-prune";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	// ── 死成员收敛 / 活成员保留 ──────────────────────────────────────────────

	@Test
	void deadCandidateLeavesBothIndexesWhileLiveOnesStay() throws Exception {
		seedDue(TENANT_A, USER_A, LIVE, 500L);
		seedDue(TENANT_A, USER_A, STALE, 500L);
		seedRunList(TENANT_A, USER_A, LIVE);
		seedRunList(TENANT_A, USER_A, STALE);
		seedSnapshot(TENANT_A, USER_A, LIVE, RunSnapshot.RUNNING);

		int removed = this.store.pruneRunIndex();

		assertThat(removed).as("只有快照不存在的死成员被剔除").isEqualTo(1);
		assertThat(dueMembers()).as("到期索引只剩活轮").containsExactly(member(TENANT_A, USER_A, LIVE));
		assertThat(runList(TENANT_A, USER_A)).as("死成员同时从本人身份的 run 索引摘除").containsExactly(LIVE);
		assertThat(this.fake.strings).as("活轮快照不得被清理顺手删掉")
			.containsKey(RunStore.runScopedKey(RunStore.RUN_PREFIX, TENANT_A, USER_A, LIVE));
	}

	@Test
	void terminalRunLeavesTheDueIndexButKeepsItsHistoryIndexMember() throws Exception {
		seedDue(TENANT_A, USER_A, LIVE, 500L);
		seedRunList(TENANT_A, USER_A, LIVE);
		seedSnapshot(TENANT_A, USER_A, LIVE, RunSnapshot.DONE);

		assertThat(this.store.pruneRunIndex()).as("终态轮不再需要恢复对账").isEqualTo(1);

		assertThat(dueMembers()).isEmpty();
		assertThat(runList(TENANT_A, USER_A)).as("run 列表索引是历史发现面，终态不摘").containsExactly(LIVE);
	}

	/** score 已变化 = 有更新写者：旧读者不得删除该成员（CAS 语义）。 */
	@Test
	void staleReaderCannotRemoveACandidateWhoseScoreMoved() {
		seedDue(TENANT_A, USER_A, LIVE, 500L);

		assertThat(this.store.isRecoveryCandidateRemoved(LIVE, TENANT_A, USER_A, 999L))
			.as("score 不符 ⇒ 不摘").isFalse();
		assertThat(dueMembers()).containsExactly(member(TENANT_A, USER_A, LIVE));

		assertThat(this.store.isRecoveryCandidateRemoved(LIVE, TENANT_A, USER_A, 500L)).isTrue();
		assertThat(dueMembers()).isEmpty();
	}

	@Test
	void emptyDueIndexDisappearsWithItsLastMember() {
		seedDue(TENANT_A, USER_A, STALE, 500L);

		assertThat(this.store.pruneRunIndex()).isEqualTo(1);
		assertThat(this.fake.zsetsByKey).doesNotContainKey(RunStore.RECOVERY_DUE_INDEX);
		assertThat(this.store.pruneRunIndex()).as("空索引 ⇒ 无事可做").isZero();
	}

	// ── 身份：成员自带身份决定判活键（不是调用线程） ─────────────────────────

	/**
	 * 系统线程（无请求身份）同样按<b>成员自带身份</b>读快照：A 的活轮保留、B 的同名死轮剔除。
	 * 若按调用线程身份读，A 的活轮会被误判为"快照不存在"而删掉。
	 */
	@Test
	void livenessIsJudgedWithTheMembersOwnIdentityNotTheCallerThread() throws Exception {
		seedDue(TENANT_A, USER_A, LIVE, 500L);
		seedSnapshot(TENANT_A, USER_A, LIVE, RunSnapshot.RUNNING);
		seedDue(TENANT_B, USER_B, LIVE, 500L);
		assertThat(RequestContextUtils.currentOrNull()).as("前置：无请求身份（系统线程视图）").isNull();

		int removed = this.store.pruneRunIndex();

		assertThat(removed).as("只剔除 B 的死成员；A 的同名活轮保留").isEqualTo(1);
		assertThat(dueMembers()).containsExactly(member(TENANT_A, USER_A, LIVE));
	}

	/** 无身份段的 legacy 成员：无法判归属 ⇒ 按 score CAS 摘除，不猜身份。 */
	@Test
	void legacyBareMemberIsRemovedWithoutGuessingAnIdentity() {
		seedDueMember("run-legacy", 500L);

		assertThat(this.store.pruneRunIndex()).isEqualTo(1);
		assertThat(dueMembers()).isEmpty();
	}

	// ── 有界 + 轮转 ─────────────────────────────────────────────────────────

	@Test
	void reconciliationIsBoundedTo64PerTickAndRotatesThroughTheRest() {
		for (int i = 0; i < 70; i++) {
			seedDue(TENANT_A, USER_A, "run-dead-" + i, 500L);
		}

		assertThat(this.store.pruneRunIndex()).as("单拍最多 64 项").isEqualTo(64);
		assertThat(dueMembers()).hasSize(6);
		assertThat(this.store.pruneRunIndex()).as("下一拍处理剩余 6 项（偏移轮转）").isEqualTo(6);
		assertThat(dueMembers()).isEmpty();
	}

	// ── run 列表索引的读口：只读本身份、不探快照 ─────────────────────────────

	@Test
	void allRunIdsReadsOnlyTheCallersIdentityRunListWithoutTouchingSnapshots() {
		seedRunList(TENANT_A, USER_A, LIVE);
		seedRunList(TENANT_A, USER_A, STALE);
		seedRunList(TENANT_B, USER_B, "run-foreign");
		this.fake.getCounts.clear();

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.allRunIds()).containsExactly(LIVE, STALE);
		}
		assertThat(this.fake.getCounts).as("读发现面不得逐个反序列化快照").isEmpty();
	}

	// ── 失败可观测（不抛、不猜） ──────────────────────────────────────────────

	@Test
	void pruneFailureIsCountedAndNeverBreaksTheCaller(CapturedOutput output) {
		this.fake.goDown();
		long before = RedisDegradation.count(RedisDegradation.C1_RUN_INDEX_PRUNE);

		assertThatCode(() -> this.store.pruneRunIndex()).doesNotThrowAnyException();
		assertThat(this.store.pruneRunIndex()).isZero();

		assertThat(RedisDegradation.count(RedisDegradation.C1_RUN_INDEX_PRUNE)).isGreaterThan(before);
		assertThat(output.getAll()).contains("recovery due index bounded reconcile failed");
	}

	// ── 节流（由恢复扫描触发；create 路径不再做清理） ─────────────────────────

	@Test
	void reconcileThrottleHonoursTheConfiguredInterval() {
		this.properties.getStream().setIndexPruneInterval(Duration.ofMinutes(10));
		seedDue(TENANT_A, USER_A, STALE, 500L);

		this.store.reconcileRecoveryIndexThrottled();
		assertThat(dueMembers()).as("首次调用执行核对").isEmpty();

		seedDue(TENANT_A, USER_A, "run-stale-2", 500L);
		this.store.reconcileRecoveryIndexThrottled();
		assertThat(dueMembers()).as("间隔内不再清理（留到下一拍）")
			.containsExactly(member(TENANT_A, USER_A, "run-stale-2"));
	}

	@Test
	void zeroIntervalDisablesTheThrottledReconcileOnly() {
		this.properties.getStream().setIndexPruneInterval(Duration.ZERO);
		seedDue(TENANT_A, USER_A, STALE, 500L);

		this.store.reconcileRecoveryIndexThrottled();
		assertThat(dueMembers()).as("0 = 关闭机会式核对").containsExactly(member(TENANT_A, USER_A, STALE));

		assertThat(this.store.pruneRunIndex()).isEqualTo(1);
		assertThat(dueMembers()).isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static String member(long tenantId, long userId, String runId) {
		return RunKeyScope.indexMember(tenantId, userId, runId);
	}

	private void seedDue(long tenantId, long userId, String runId, long score) {
		seedDueMember(member(tenantId, userId, runId), score);
	}

	private void seedDueMember(String member, long score) {
		this.fake.zsetsByKey
			.computeIfAbsent(RunStore.RECOVERY_DUE_INDEX, ignored -> new ConcurrentHashMap<>())
			.put(member, (double) score);
	}

	private void seedRunList(long tenantId, long userId, String runId) {
		this.fake.zsetsByKey
			.computeIfAbsent(RunStore.RUN_LIST_INDEX_PREFIX + tenantId + ":" + userId,
					ignored -> new ConcurrentHashMap<>())
			.put(runId, 0.0);
	}

	private void seedSnapshot(long tenantId, long userId, String runId, String status) throws Exception {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setStatus(status);
		snapshot.setTenantId(tenantId);
		snapshot.setUserId(userId);
		this.fake.strings.put(RunStore.runScopedKey(RunStore.RUN_PREFIX, tenantId, userId, runId),
				this.mapper.writeValueAsString(snapshot));
	}

	private java.util.Set<String> dueMembers() {
		return this.fake.zsetsByKey.getOrDefault(RunStore.RECOVERY_DUE_INDEX, java.util.Map.of()).keySet();
	}

	private java.util.Set<String> runList(long tenantId, long userId) {
		return this.fake.zsetsByKey
			.getOrDefault(RunStore.RUN_LIST_INDEX_PREFIX + tenantId + ":" + userId, java.util.Map.of())
			.keySet();
	}

}
