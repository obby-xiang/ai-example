package com.example.configmgr.ai.session;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>销毁的"每个 run 的键"面：索引哨兵缺口（RT-28）与结算观测位（B4）</b>。
 *
 * <h2>RT-28：{@code complete} 哨兵不得掩盖缺成员</h2>
 * {@code ai:session:runs:<sid>} 的 {@code complete} 只表示"上次按 sessionId 全量回填覆盖过"，
 * 而新 run 入索引是 best-effort（{@code RunStore#create} 的 SADD 失败只 WARN）。于是
 * "哨兵在 + 成员缺"时销毁只按索引枚举候选 ⇒ 该轮的 13 键/工具入参/草稿键全部残留，
 * 却报 {@code complete=true}（没有第二次机会重试）。本批口径：销毁尾部按全局 {@code ai:runs}
 * 复核本会话 run 残留，终态即删、活跃即延后（{@code complete=false} 可重试）。
 *
 * <h2>B4：{@code ai:observed:<runId>} 必须随销毁删除</h2>
 * 用户裁定"所有 AI 数据在会话销毁时删除，不只靠 TTL"。该键是 Task 2 的结算观测位
 * （HASH、首写即置 session-ttl），与 {@code ai:beat:}/{@code ai:activity:} 同列 TTL 型 run 锚点，
 * 因此走同一个 {@code deleteObservabilityKeys}：按 runId 单键 DEL，删除失败记可重试错误。
 */
class AiSessionPurgeRunIndexResidueTest {

	private static final String SESSION_A = "sess-idx-a";

	private static final String SESSION_B = "sess-idx-b";

	/** Task 2 的结算观测位键形（{@code ai:observed:<runId>}，见其报告 §5 的键生命周期契约）。 */
	private static final String OBSERVED_PREFIX = "ai:observed:";

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

	@BeforeEach
	void setUp() {
		when(this.gate.currentRunId(anyString())).thenReturn(null);
		when(this.cancellations.keyFor(anyString())).thenAnswer(invocation -> "ai:cancel:" + invocation.getArgument(0));
		when(this.attachments.findBySessionId(anyString())).thenReturn(List.of());
		when(this.database.snapshot(anyString())).thenReturn(new SessionPurgeDatabase.Snapshot(0));
		when(this.database.delete(anyString())).thenReturn(new SessionPurgeDatabase.Deleted(0));
	}

	@Test
	void sentinelCompleteWithAMissingMemberStillDeletesTheRunAndReportsResidueFree() {
		// 会话索引已"完整回填"（哨兵在）且含 run-a；run-b 也是本会话的，但它的 SADD 曾经失败
		// ⇒ 只在全局 ai:runs 里，会话索引看不见。
		seedRunIndex("run-a", SESSION_A);
		this.store.backfillSessionRuns(SESSION_A, Set.of("run-a"));
		assertThat(this.store.sessionRunIndex(SESSION_A).complete()).isTrue();
		assertThat(this.store.sessionRunIndex(SESSION_A).runIds()).containsExactly("run-a");
		seedGlobalOnlyRun("run-b", SESSION_A, RunSnapshot.DONE);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId)
			.as("按全局索引补发现缺成员的 run")
			.containsExactlyInAnyOrder("run-a", "run-b");
		assertThat(this.fake.strings).as("run-b 的 13 键（含快照）必须删掉")
			.doesNotContainKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).doesNotContainKey(this.store.pendingKey("run-b"));
		assertThat(result.complete()).as("补发现并删净后才可以 complete").isTrue();
	}

	@Test
	void sentinelCompleteWithAnActiveMissingMemberLeavesRetryableIncompleteState() {
		seedRunIndex("run-a", SESSION_A);
		this.store.backfillSessionRuns(SESSION_A, Set.of("run-a"));
		// run-b 还在跑（非终态）：不得删它的 13 键，但也不能假装删完了。
		seedGlobalOnlyRun("run-b", SESSION_A, RunSnapshot.RUNNING);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("缺成员的活跃 run ⇒ 不许报 complete").isFalse();
		assertThat(result.deferred()).extracting(AiSessionPurgeService.DeferredView::runId)
			.as("把延后事实如实报出来（调度/再次 DELETE 会重试）")
			.contains("run-b");
		assertThat(this.fake.strings).as("活跃 run 的键不得删（会被收尾写回/续跑复活）")
			.containsKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).as("pending 保留 ⇒ 可重试").containsKey(
				SessionPurgeGuard.PENDING_PREFIX + SESSION_A);
	}

	@Test
	void observedKeyOfThisSessionsRunsIsDeletedAndOtherSessionsAreUntouched() {
		seedRunIndex("run-a", SESSION_A);
		seedRunIndex("run-b", SESSION_A);
		seedRunIndex("run-c", SESSION_B);
		// Task 2 的结算观测位（HASH）：本会话两轮 + 他会话一轮。
		seedObserved("run-a");
		seedObserved("run-b");
		seedObserved("run-c");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(this.fake.hashes).as("本会话各轮的结算观测位必须删掉（不只靠 TTL）")
			.doesNotContainKey(OBSERVED_PREFIX + "run-a")
			.doesNotContainKey(OBSERVED_PREFIX + "run-b");
		assertThat(this.fake.hashes).as("他会话的观测位一个字节都不许动").containsKey(OBSERVED_PREFIX + "run-c");
	}

	@Test
	void observedKeyDeleteFailureIsReportedAsRetryableIncomplete() {
		seedRunIndex("run-a", SESSION_A);
		seedObserved("run-a");
		// 只让"结算观测位"这一条 DEL 失败：13 键删除必须照常成功（否则错误会先被 13 键的 catch 吃掉）。
		this.fake.failDeleteContaining = OBSERVED_PREFIX;

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("观测位没删掉就不能报 complete").isFalse();
		assertThat(result.errors()).anyMatch(error -> error.contains("结算观测位"));
	}

	/**
	 * <b>复审 Important（fix2）：无关 run 不得被完整反序列化，且扫描量不能被"找到几条残留"的计数上限
	 * 假限制</b>。
	 *
	 * <h2>缺陷形态</h2>
	 * 旧实现逐 <code>ai:runs</code> 成员调 {@code runStore.get(runId)} 判归属 ⇒ 每个<b>无关</b>轮次都会
	 * 把整份快照（含整窗 {@code messageJson}）反序列化成对象图，销毁端点为此付出
	 * O(总轮次 × 快照大小) 的 CPU/内存；而 {@code residual.size()} 上限只限制"发现了多少条本会话 run"，
	 * 对扫描量毫无作用（无关 run 再多也照扫照解析）。
	 *
	 * <h2>本批口径</h2>
	 * 归属判断走轻量口（原始 JSON + 流式取顶层 {@code sessionId}），完整 {@code RunSnapshot} 只在
	 * "确实属于本会话"的 {@code deleteOrDefer} 里读；<b>不为省成本截断扫描</b>（截断会让 complete
	 * 永远为假 ⇒ 每次自动重试都从头扫、永远删不完）。
	 */
	@Test
	void largeUnrelatedIndexDoesNotFullyDeserializeForeignSnapshots() {
		seedRunIndex("run-a", SESSION_A);
		this.store.backfillSessionRuns(SESSION_A, Set.of("run-a"));
		// 本会话漏在会话索引外的 run（必须仍被发现并删掉）
		seedGlobalOnlyRun("run-b", SESSION_A, RunSnapshot.DONE);
		// 大量无关 run（他会话、活跃、快照带整窗历史）：旧实现在这里逐条反序列化
		for (int i = 0; i < 300; i++) {
			seedGlobalOnlyRun(String.format("other-%03d", i), "sess-other-" + i, RunSnapshot.DONE);
		}
		RunStore spyStore = org.mockito.Mockito.spy(this.store);

		AiSessionPurgeService.PurgeResult result = serviceWith(spyStore).purge(SESSION_A, "test");

		org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.never())
			.get(org.mockito.ArgumentMatchers.startsWith("other-"));
		assertThat(this.fake.strings).as("无关 run 的快照一个字节都不许动")
			.containsKey(this.store.runKey("other-000"));
		assertThat(this.fake.strings).as("完整性不得为省成本让步：缺成员的 run-b 仍要删掉")
			.doesNotContainKey(this.store.runKey("run-b"));
		assertThat(result.complete()).isTrue();
	}

	/**
	 * <b>归属读取失败必须 fail-closed（回归钉子）</b>：快照读不出来/坏值时，既不得<b>猜</b>成"他会话"
	 * （那会漏删本会话的 13 键），也不得直接删（那可能删到他会话）—— 只记可重试错误，`complete=false`，
	 * 键原样保留。轻量归属口必须继承这条既有行为（旧实现靠 {@code get} 抛错达成，新口子必须显式返回
	 * "不可读"而不是"非本会话"）。
	 */
	@Test
	void unreadableSnapshotIsFailClosedInsteadOfTreatedAsAnotherSession() {
		seedRunIndex("run-a", SESSION_A);
		this.store.backfillSessionRuns(SESSION_A, Set.of("run-a"));
		// 本会话的 run-b：只有它在索引里、快照是坏 JSON（模拟写坏/半截）
		this.fake.sets.computeIfAbsent(RunStore.RUN_INDEX,
				ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add("run-b");
		this.fake.strings.put(this.store.runKey("run-b"), "{not-json");
		this.fake.strings.put(this.store.pendingKey("run-b"), "{}");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("归属不可读 ⇒ 不得报 complete").isFalse();
		assertThat(result.errors()).as("必须留可重试事实（点名 run-b）")
			.anyMatch(error -> error.contains("run-b"));
		assertThat(this.fake.strings).as("归属不可知时不得删它的键（可能属于他会话）")
			.containsKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).as("同一趟里的正常 run 仍要删（不因一条坏数据停摆）")
			.doesNotContainKey(this.store.runKey("run-a"));
	}

	/**
	 * <b>复审 fix3（Important）：首次销毁（没有 {@code __session_runs_complete__} 哨兵）的回退分支
	 * 同样不得对无关 run 完整反序列化</b>。
	 *
	 * <h2>缺陷形态</h2>
	 * 回退路径把 {@code ai:runs} 全部成员当候选，逐条 {@code runStore.get(...)} <b>只为判归属</b> ——
	 * 与 fix2 修掉的 {@code residualRuns} 是同一个成本形态，只是发生在<b>主枚举路径</b>上：
	 * 首次销毁（新会话/老数据/升级后第一条 DELETE）没有哨兵，必须回退全局索引，于是海量无关轮次
	 * 全被反序列化。fix2 的用例预置了哨兵（走快路径），恰好<b>绕过</b>了这条路径。
	 *
	 * <h2>本批口径</h2>
	 * 回退分支的归属过滤同样走 {@link RunStore#snapshotOwner(String)}（原始 JSON + 流式顶层
	 * {@code sessionId}），只有确实属于本会话的 run 才完整 {@code get}；归属不可读 ⇒ 中止整次枚举
	 * （一个键都不删），绝不跳过。
	 */
	@Test
	void firstPurgeWithoutSentinelDoesNotFullyDeserializeForeignSnapshots() {
		// 故意不调 backfillSessionRuns：没有哨兵 ⇒ enumerate 走全局回退
		seedRunIndex("run-a", SESSION_A);
		assertThat(this.store.sessionRunIndex(SESSION_A).complete()).as("无哨兵 ⇒ 必须回退全局").isFalse();
		seedGlobalOnlyRun("run-b", SESSION_A, RunSnapshot.DONE);
		for (int i = 0; i < 300; i++) {
			seedGlobalOnlyRun(String.format("other-%03d", i), "sess-other-" + i, RunSnapshot.DONE);
		}
		this.fake.strings.put("jobs", "business");
		seedObserved("run-a");
		RunStore spyStore = org.mockito.Mockito.spy(this.store);

		AiSessionPurgeService.PurgeResult result = serviceWith(spyStore).purge(SESSION_A, "test");

		org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.never())
			.get(org.mockito.ArgumentMatchers.startsWith("other-"));
		assertThat(this.fake.strings).as("完整性：漏在会话索引外的 run-b 仍要发现并删掉")
			.doesNotContainKey(this.store.runKey("run-b"));
		assertThat(this.fake.strings).as("它的 13 键一并清掉").doesNotContainKey(this.store.pendingKey("run-b"));
		assertThat(this.fake.hashes).as("ai:observed: 仍随销毁真实删除").doesNotContainKey(OBSERVED_PREFIX + "run-a");
		assertThat(this.fake.strings).as("业务数据一个字节都不许动").containsKey("jobs");
		assertThat(this.fake.strings).as("无关 run 的快照原样保留").containsKey(this.store.runKey("other-000"));
		assertThat(result.complete()).isTrue();
	}

	/**
	 * <b>非字符串、非 null 的 {@code sessionId} = 结构不符 = UNREADABLE</b>：既不能当他会话跳过
	 * （可能正是本会话，会漏删），也不能当本会话（可能误删；Jackson 会把数字强转成字符串，旧实现因此
	 * 静默当成"他会话"并 {@code complete=true}）。回退分支必须<b>中止整次枚举</b>，一个键都不删。
	 */
	@Test
	void nonStringSessionIdIsUnreadableAndAbortsEnumerateWithoutDeleting() {
		seedRunIndex("run-a", SESSION_A);
		this.seedRawSnapshot("run-weird", "{\"runId\":\"run-weird\",\"sessionId\":123,\"status\":\"DONE\"}");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.phase()).as("归属不可知 ⇒ 整次枚举中止（不得静默跳过）").isEqualTo("enumerate");
		assertThat(result.complete()).isFalse();
		assertThat(this.fake.deleted).as("枚举失败一个数据键都不许删")
			.noneMatch(key -> !key.equals(SessionPurgeGuard.PENDING_PREFIX + SESSION_A));
		assertThat(this.fake.strings).containsKey(this.store.runKey("run-a"));
		assertThat(this.fake.strings).containsKey(this.store.runKey("run-weird"));
	}

	/**
	 * <b>旧快照（无 {@code sessionId} 字段）= ABSENT，不是"死成员"</b>：JSON 合法但拿不到归属数据，
	 * 既不能当本会话删（可能误删他会话），也不能当死成员把 13 键清掉 —— 只能跳过。
	 * 与"键已消失"（MISSING，死成员）必须区分开。
	 */
	@Test
	void legacySnapshotWithoutSessionIdIsSkippedInsteadOfDeletedAsDeadMember() {
		seedRunIndex("run-a", SESSION_A);
		this.seedRawSnapshot("run-legacy", "{\"runId\":\"run-legacy\",\"status\":\"DONE\"}");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(this.fake.strings).as("旧快照无法归属：不得当死成员删它的键")
			.containsKey(this.store.runKey("run-legacy"));
		assertThat(this.fake.strings).as("它的待决条目也要保留").containsKey(this.store.pendingKey("run-legacy"));
		assertThat(this.fake.strings).as("本会话自己的 run 照删").doesNotContainKey(this.store.runKey("run-a"));
		assertThat(result.complete()).isTrue();
	}

	/**
	 * <b>gateRunId / currentRunId 的"键已消失"旧语义必须保留</b>：被会话门或当前轮索引点名的 run
	 * 即使快照已过期，仍按 {@code UNKNOWN}/active 收进候选（随后按最新状态落终态并清孤儿键），
	 * 不能因为"归属读不到"就被轻量口过滤掉。
	 */
	@Test
	void missingSnapshotNamedByCurrentRunIndexKeepsUnknownActiveSemantics() {
		seedRunIndex("run-a", SESSION_A);
		// 当前轮索引指向一个快照已消失的 run，但它的其它键还在（孤儿清理的目标）
		this.fake.strings.put(RunStore.SESSION_CURRENT_PREFIX + SESSION_A, "run-ghost");
		this.fake.strings.put(this.store.pendingKey("run-ghost"), "{}");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId)
			.as("点名的死轮必须留在候选里（UNKNOWN/active 旧语义）")
			.contains("run-ghost");
		assertThat(this.fake.strings).as("孤儿键照常清掉").doesNotContainKey(this.store.pendingKey("run-ghost"));
		assertThat(result.complete()).isTrue();
	}

	// ── 种子 ────────────────────────────────────────────────────────────────

	private AiSessionPurgeService serviceWith(RunStore runStore) {		return new AiSessionPurgeService(this.fake.redis, runStore, this.gate, this.cancellations, this.memory,
				this.attachments, this.fileStorage, this.database, this.guard, this.pendingStore, this.properties,
				Duration.ZERO);
	}

	private void seedRunIndex(String runId, String sessionId) {
		this.fake.sets.computeIfAbsent(RunStore.RUN_INDEX, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(runId);
		this.fake.sets.computeIfAbsent(RunStore.SESSION_RUNS_PREFIX + sessionId,
				ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(runId);
		writeSnapshot(runId, sessionId, RunSnapshot.DONE);
	}

	/** 只写全局索引与快照，<b>不</b>进会话索引（模拟 best-effort SADD 失败）。 */
	private void seedGlobalOnlyRun(String runId, String sessionId, String status) {
		this.fake.sets.computeIfAbsent(RunStore.RUN_INDEX, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(runId);
		writeSnapshot(runId, sessionId, status);
		this.fake.strings.put(this.store.pendingKey(runId), "{}");
	}

	private void writeSnapshot(String runId, String sessionId, String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(status);
		try {
			this.fake.strings.put(this.store.runKey(runId), this.mapper.writeValueAsString(snapshot));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** 原样写入快照 JSON（不走 RunSnapshot 序列化：用于坏值/旧格式/非字符串 sessionId）。 */
	private void seedRawSnapshot(String runId, String json) {
		this.fake.sets.computeIfAbsent(RunStore.RUN_INDEX,
				ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(runId);
		this.fake.strings.put(this.store.runKey(runId), json);
		this.fake.strings.put(this.store.pendingKey(runId), "{}");
	}

	private void seedObserved(String runId) {
		this.fake.hashes.computeIfAbsent(OBSERVED_PREFIX + runId,
				ignored -> new java.util.concurrent.ConcurrentHashMap<>()).put("call-1", "1|inst-a");
	}

}
