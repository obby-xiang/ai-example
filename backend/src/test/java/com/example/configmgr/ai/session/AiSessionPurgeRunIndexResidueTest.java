package com.example.configmgr.ai.session;

import java.time.Duration;
import java.util.List;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话销毁的 <b>run 发现面与观测位</b>：会话→run 索引是唯一候选来源，身份段决定键面与隔离，
 * 归属不可读必须 fail-closed，观测位（{@code ai:observed:}）随销毁删除。
 *
 * <h2>当前口径（与旧 RT-28/全局回退不同）</h2>
 * <ul>
 * <li>候选 = 本会话索引 {@code ai:session:runs:<t>:<u>:<sid>} + 会话门点名 + sessionCurrent 点名；
 * <b>没有全局 {@code ai:runs} 回退</b>——不在索引里的 run 不会被发现（升级/补写由快照写入方回填）；</li>
 * <li>活跃轮延后（{@code deferred}/{@code complete=false}），durable 台账保留可重试；</li>
 * <li>快照归属不可读（坏 JSON）⇒ 整次枚举中止（phase=enumerate），一个数据键都不删；</li>
 * <li>快照属于他会话 ⇒ 跳过不删；快照消失但被 current 索引点名 ⇒ 仍按 UNKNOWN/active 收进候选并清孤儿键。</li>
 * </ul>
 */
class AiSessionPurgeRunIndexResidueTest {

	private static final String SESSION_A = "sess-idx-a";

	private static final TenantUser ALICE = new TenantUser(1L, 101L);

	private static final TenantUser BOB = new TenantUser(2L, 201L);

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = mock(SessionGate.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final FormAttachmentStore attachments = mock(FormAttachmentStore.class);

	private final com.example.configmgr.file.FileStorageService fileStorage = mock(
			com.example.configmgr.file.FileStorageService.class);

	private final SessionPurgeDatabase database = mock(SessionPurgeDatabase.class);

	private final SessionPurgeGuard guard = new SessionPurgeGuard(this.fake.redis);

	private final SessionPurgePendingStore pendingStore = new SessionPurgePendingStore(this.fake.redis, this.mapper,
			this.properties);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final RedisChatMemoryRepository memory = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			Duration.ofHours(24), this.guard);

	private final AiSessionPurgeService service = new AiSessionPurgeService(this.fake.redis, this.store, this.gate,
			this.cancellations, this.memory, this.attachments, this.fileStorage, this.database, this.guard,
			this.pendingStore, this.properties, Duration.ZERO);

	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void setUp() {
		this.identity = RequestContextUtils.useContext(ALICE.tenantId(), ALICE.userId());
		when(this.gate.currentRunId(anyString())).thenReturn(null);
		when(this.cancellations.keyFor(anyString())).thenAnswer(invocation -> "ai:cancel:test:" + invocation.getArgument(0));
		when(this.cancellations.cancelState(anyString()))
			.thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.attachments.findBySessionId(anyString())).thenReturn(List.of());
		when(this.database.snapshot(anyString(), anyLong(), anyLong())).thenReturn(new SessionPurgeDatabase.Snapshot(0));
		when(this.database.delete(anyString(), anyLong(), anyLong())).thenReturn(new SessionPurgeDatabase.Deleted(0));
	}

	@AfterEach
	void tearDown() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void runListedInTheSessionIndexIsDeletedWithItsObservationKeys() {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE, true);
		seedObserved(ALICE, "run-a");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(this.fake.strings).doesNotContainKey(this.store.runKey("run-a"));
		assertThat(this.fake.strings).doesNotContainKey(this.store.pendingKey("run-a"));
		assertThat(this.fake.hashes).as("结算观测位随销毁删除（不只靠 TTL）")
			.doesNotContainKey(observedKey(ALICE, "run-a"));
	}

	/** 会话索引是唯一候选来源：不在索引里的 run 不会被发现（无全局回退）。 */
	@Test
	void runMissingFromTheSessionIndexIsNotDiscovered() {
		seedRun("run-b", SESSION_A, RunSnapshot.DONE, false);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("当前口径：没有全局回退，残留留待索引回填/再次 DELETE").isTrue();
		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId).doesNotContain("run-b");
		assertThat(this.fake.strings).as("不在候选面 ⇒ 13 键原样保留")
			.containsKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).containsKey(this.store.pendingKey("run-b"));
	}

	@Test
	void activeRunInTheSessionIndexIsDeferredAndKeepsDurableState() {
		seedRun("run-b", SESSION_A, RunSnapshot.RUNNING, true);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("活跃 run ⇒ 不许报 complete").isFalse();
		assertThat(result.deferred()).extracting(AiSessionPurgeService.DeferredView::runId)
			.as("延后事实如实报出（调度/再次 DELETE 会重试）").contains("run-b");
		assertThat(this.fake.strings).as("活跃 run 的键不得删（会被收尾写回/续跑复活）")
			.containsKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).as("durable 台账保留 ⇒ 可重试")
			.containsKey(SessionPurgePendingStore.pendingKey(SESSION_A, ALICE.tenantId(), ALICE.userId()));
	}

	/** 观测位键带身份段：A 的随销毁删除，B 的同名 run 一个字节都不动。 */
	@Test
	void observedKeysAreIdentityScopedSoOtherIdentitiesSurvive() {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE, true);
		seedObserved(ALICE, "run-a");
		seedObserved(BOB, "run-a");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(this.fake.hashes).doesNotContainKey(observedKey(ALICE, "run-a"));
		assertThat(this.fake.hashes).as("他会话身份的观测位原样保留").containsKey(observedKey(BOB, "run-a"));
	}

	@Test
	void observedKeyDeleteFailureIsReportedAsRetryableIncomplete() {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE, true);
		seedObserved(ALICE, "run-a");
		this.fake.failDeleteContaining = "ai:observed:";

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("观测位没删掉就不能报 complete").isFalse();
		assertThat(result.errors()).as("如实列入可重试错误").anyMatch(error -> error.contains("观测"));
		assertThat(this.fake.hashes).as("失败事实保留（可重试）").containsKey(observedKey(ALICE, "run-a"));
	}

	@Test
	void unreadableSnapshotInTheSessionIndexAbortsEnumerationFailClosed() {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE, true);
		seedRawIndexedRun("run-b", "{not-json");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.phase()).as("归属不可读 ⇒ 整次枚举中止").isEqualTo("enumerate");
		assertThat(result.complete()).isFalse();
		assertThat(this.fake.strings).as("中止时一个数据键都不许删").containsKey(this.store.runKey("run-a"));
		assertThat(this.fake.strings).containsKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).as("durable 台账保留（可重试）")
			.containsKey(SessionPurgePendingStore.pendingKey(SESSION_A, ALICE.tenantId(), ALICE.userId()));
	}

	@Test
	void snapshotBelongingToAnotherSessionIsSkippedNotDeleted() {
		seedRun("run-x", "sess-other", RunSnapshot.DONE, true);
		this.fake.sets.get(this.store.sessionRunsKey(SESSION_A)).add("run-x");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId).doesNotContain("run-x");
		assertThat(this.fake.strings).as("他会话 run 的键不得被本会话销毁删除")
			.containsKey(this.store.runKey("run-x"));
	}

	@Test
	void ghostRunNamedByTheCurrentRunIndexIsStillCleanedUp() {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE, true);
		this.fake.strings.put(this.store.sessionCurrentKey(SESSION_A), "run-ghost");
		this.fake.strings.put(this.store.pendingKey("run-ghost"), "{}");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId)
			.as("被 current 索引点名的死轮仍进候选（UNKNOWN/active 旧语义）").contains("run-ghost");
		assertThat(this.fake.strings).as("孤儿键照常清掉").doesNotContainKey(this.store.pendingKey("run-ghost"));
		assertThat(result.complete()).isTrue();
	}

	@Test
	void unrelatedRunsAreNeverReadForOwnership() {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE, true);
		for (int i = 0; i < 300; i++) {
			seedRun(String.format("other-%03d", i), "sess-other-" + i, RunSnapshot.DONE, false);
		}
		RunStore spyStore = org.mockito.Mockito.spy(this.store);

		AiSessionPurgeService.PurgeResult result = serviceWith(spyStore).purge(SESSION_A, "test");

		org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.never())
			.get(org.mockito.ArgumentMatchers.startsWith("other-"));
		assertThat(this.fake.strings).as("无关 run 的快照一个字节都不许动")
			.containsKey(this.store.runKey("other-000"));
		assertThat(this.fake.strings).doesNotContainKey(this.store.runKey("run-a"));
		assertThat(result.complete()).isTrue();
	}

	// ── 种子 ────────────────────────────────────────────────────────────────

	private AiSessionPurgeService serviceWith(RunStore runStore) {
		return new AiSessionPurgeService(this.fake.redis, runStore, this.gate, this.cancellations, this.memory,
				this.attachments, this.fileStorage, this.database, this.guard, this.pendingStore, this.properties,
				Duration.ZERO);
	}

	private void seedRun(String runId, String sessionId, String status, boolean indexedForSession) {
		writeSnapshot(runId, sessionId, status);
		this.fake.strings.put(this.store.pendingKey(runId), "{}");
		if (indexedForSession) {
			this.fake.sets
				.computeIfAbsent(this.store.sessionRunsKey(SESSION_A),
						ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
				.add(runId);
		}
	}

	/** 原样写入快照 JSON（坏值）并登记进本会话索引。 */
	private void seedRawIndexedRun(String runId, String json) {
		this.fake.strings.put(this.store.runKey(runId), json);
		this.fake.strings.put(this.store.pendingKey(runId), "{}");
		this.fake.sets
			.computeIfAbsent(this.store.sessionRunsKey(SESSION_A),
					ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(runId);
	}

	private void writeSnapshot(String runId, String sessionId, String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(status);
		snapshot.setTenantId(ALICE.tenantId());
		snapshot.setUserId(ALICE.userId());
		try {
			this.fake.strings.put(this.store.runKey(runId), this.mapper.writeValueAsString(snapshot));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void seedObserved(TenantUser identity, String runId) {
		this.fake.hashes.computeIfAbsent(observedKey(identity, runId),
				ignored -> new java.util.concurrent.ConcurrentHashMap<>()).put("call-1", "1|inst-a");
	}

	private static String observedKey(TenantUser identity, String runId) {
		return TenantKeyScope.scoped(com.example.configmgr.ai.observe.SettlementObservationStore.KEY_PREFIX,
				identity.tenantId(), identity.userId(), runId);
	}

}
