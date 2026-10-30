package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code ai:runs} 索引的<b>惰性清理</b>（Redis 容量专项 A 的第二半）。
 *
 * <h2>问题</h2>
 * {@code ai:runs} 是 SET，<b>没有 TTL</b>；轮次回收（{@code deleteRun}）之外的路径（进程崩溃、
 * 快照 24h 自然过期）都会留下成员，索引只增不减。
 *
 * <h2>为什么不做"整键 TTL"</h2>
 * SET 无法按成员过期；对整键 {@code EXPIRE} 会把<b>活跃</b>轮次的发现面一起删掉 ——
 * 挂起等人没有上限，一个挂起 3 天的活轮在"索引不再被写"的窗口里就会消失，重启后无法自动续跑。
 * 故本批只做<b>按成员的惰性清理</b>：快照键已不在的成员才剔除；索引键变成空 SET 时由 Redis
 * 自己删除（不会出现"空索引键"）。
 *
 * <p>清理入口两处：启动扫描（{@code allRunIds} 已有，本批抽成同一条实现）与
 * {@link RunStore#create} 的<b>节流</b>机会式清理（默认 10min 一次，{@code 0} = 关闭）。
 */
@ExtendWith(OutputCaptureExtension.class)
class RunStoreRunIndexPruneTest {

	private static final String LIVE = "run-live";

	private static final String STALE = "run-stale";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	/** 直调存储的用例显式建立身份（RunStore.create 缺身份 fail-closed；索引成员带身份）。 */
	private RequestContextUtils.SafeCloseable tenantScope;

	@BeforeEach
	void enterTenantIdentity() {
		this.tenantScope = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void leaveTenantIdentity() {
		if (this.tenantScope != null) {
			this.tenantScope.close();
		}
	}

	// ── stale index 可收敛 ───────────────────────────────────────────────────

	/** 快照已不在的成员被剔除；活着的成员一个都不动。 */
	@Test
	void staleMembersConvergeWhileLiveOnesStay() {
		seedIndex(STALE, LIVE);
		seedSnapshot(LIVE);

		int removed = this.store.pruneRunIndex();

		assertThat(removed).isEqualTo(1);
		assertThat(members()).containsExactly(LIVE);
		assertThat(this.fake.strings).as("活轮的快照不得被清理顺手删掉").containsKey(this.store.runKey(LIVE));
	}

	/** 索引键本身不设 TTL、不被整键 DEL —— 只有"变成空 SET"时靠 Redis 自己消失。 */
	@Test
	void pruneNeverExpiresOrDeletesTheIndexOfLiveRuns() {
		seedIndex(STALE, LIVE);
		seedSnapshot(LIVE);
		this.fake.enableClusterMode();

		assertThatCode(() -> this.store.pruneRunIndex()).doesNotThrowAnyException();

		assertThat(this.fake.expiry(RunStore.RUN_INDEX)).as("整键 TTL 会删掉活跃轮的发现面").isNull();
		assertThat(this.fake.templateDeletes).as("不得对索引键发 DEL（单键剔成员即可）").hasValue(0);
		assertThat(this.fake.lastDeletedKeys).isEmpty();
		assertThat(members()).containsExactly(LIVE);
	}

	/**
	 * <b>带身份的请求线程只清理自己的成员</b>：别的身份的成员（无论死活）既不做 {@code EXISTS}
	 * 探测，也不得 {@code SREM} —— 即使按"快照键还在不在"看它确实是死的。legacy 成员同样不归
	 * 请求线程判（那是系统线程的视图）。无身份系统线程仍可全表清理（见
	 * {@link #systemThreadWithoutIdentityStillPrunesTheWholeIndexAndKeepsLegacyLiveness()}）。
	 */
	@Test
	void identityRequestPrunesOnlyItsOwnMembersAndNeverProbesForeignOnes() {
		seedIndex(STALE, LIVE);
		seedSnapshot(LIVE);
		this.fake.setsByKey.get(RunStore.RUN_INDEX)
			.add(RunKeyScope.indexMember(2L, 202L, "run-foreign"));
		this.fake.setsByKey.get(RunStore.RUN_INDEX).add("run-legacy");

		int removed = this.store.pruneRunIndex();

		assertThat(removed).as("只剔除本身份的死成员").isEqualTo(1);
		assertThat(members()).as("别的身份的成员与 legacy 成员原样保留")
			.containsExactlyInAnyOrder(LIVE, "run-foreign", "run-legacy");
		verify(this.fake.redis, never())
			.hasKey(RunKeyScope.scoped(RunStore.RUN_PREFIX, 2L, 202L, "run-foreign"));
		verify(this.fake.redis, never()).hasKey(RunStore.legacyRunKey(RunStore.RUN_PREFIX, "run-legacy"));
	}

	/**
	 * 对照：<b>无身份的系统线程</b>仍全表清理 —— 成员自带的身份段决定判活键，legacy 成员按旧键形
	 * 判活（活 legacy 保留、死 legacy 剔除）。修复"请求线程不越界"不得把系统收敛一起关掉。
	 */
	@Test
	void systemThreadWithoutIdentityStillPrunesTheWholeIndexAndKeepsLegacyLiveness() {
		seedIndex(LIVE);
		seedSnapshot(LIVE);
		this.fake.setsByKey.get(RunStore.RUN_INDEX)
			.add(RunKeyScope.indexMember(2L, 202L, "run-foreign"));
		this.fake.setsByKey.get(RunStore.RUN_INDEX).add("run-legacy-live");
		this.fake.setsByKey.get(RunStore.RUN_INDEX).add("run-legacy-dead");
		this.fake.strings.put(RunStore.legacyRunKey(RunStore.RUN_PREFIX, "run-legacy-live"), "{}");
		this.tenantScope.close();
		this.tenantScope = null;
		assertThat(RequestContextUtils.currentOrNull()).as("前置：无身份（系统线程视图）").isNull();

		int removed = this.store.pruneRunIndex();

		assertThat(removed).as("死成员跨身份全表剔除；活 legacy 不误杀").isEqualTo(2);
		assertThat(members()).containsExactlyInAnyOrder(LIVE, "run-legacy-live");
	}

	/** 最后一个 stale 成员被剔除后，空 SET 由 Redis 自动消失（不留空键）。 */
	@Test
	void emptyIndexKeyDisappearsWithItsLastMember() {
		seedIndex(STALE);

		assertThat(this.store.pruneRunIndex()).isEqualTo(1);

		assertThat(this.fake.setsByKey).doesNotContainKey(RunStore.RUN_INDEX);
		assertThat(members()).isEmpty();
	}

	/** 启动扫描（{@code allRunIds}）与显式清理是同一条实现：返回活轮并顺手收敛索引。 */
	@Test
	void allRunIdsReturnsLiveRunsAndPrunesDeadOnes() {
		seedIndex(STALE, LIVE);
		seedSnapshot(LIVE);

		assertThat(this.store.allRunIds()).containsExactly(LIVE);
		assertThat(members()).as("读口也要收敛（启动扫描后索引与快照一致）").containsExactly(LIVE);
	}

	// ── 活跃 run 不误清 / 机会式清理与节流 ───────────────────────────────────

	/**
	 * {@code create} 触发机会式清理：进程起来后的第一次创建会把历史残留剔掉，
	 * <b>但刚创建的活轮必须留着</b>（SADD 在应用快照之后，清理读到的索引里它是活成员）。
	 */
	@Test
	void firstCreateAfterStartupDropsLeftoversAndKeepsTheNewRun() {
		seedIndex(STALE);

		this.store.create(LIVE, "s-live", AiContext.empty(), List.of(), this.codec);

		assertThat(members()).containsExactly(LIVE);
		assertThat(this.fake.strings).containsKey(this.store.runKey(LIVE));
	}

	/** 节流：默认间隔内不再扫索引（避免每次 /chat 都 SMEMBERS + 逐成员 EXISTS）。 */
	@Test
	void opportunisticPruneIsThrottledByTheConfiguredInterval() {
		this.store.create(LIVE, "s-live", AiContext.empty(), List.of(), this.codec);
		seedIndex(STALE);

		this.store.create("run-second", "s-second", AiContext.empty(), List.of(), this.codec);

		assertThat(members()).as("间隔内不清理：残留留到下一拍（不是丢数据，只是迟收敛）")
			.contains(STALE, LIVE, "run-second");
	}

	/** {@code index-prune-interval = 0} = 关闭机会式清理（显式逃生门）；显式调用仍然可用。 */
	@Test
	void zeroIntervalDisablesTheOpportunisticPruneOnly() {
		this.properties.getStream().setIndexPruneInterval(Duration.ZERO);
		seedIndex(STALE);

		this.store.create(LIVE, "s-live", AiContext.empty(), List.of(), this.codec);
		assertThat(members()).contains(STALE, LIVE);

		assertThat(this.store.pruneRunIndex()).isEqualTo(1);
		assertThat(members()).containsExactly(LIVE);
	}

	// ── 失败可观测（不抛、不猜） ──────────────────────────────────────────────

	/** Redis 不可用：清理跳过并计数（WARN 留痕），<b>不抛给调用方</b>（create 不能因清理失败而失败）。 */
	@Test
	void pruneFailureIsCountedAndNeverBreaksTheCaller(CapturedOutput output) {
		this.fake.goDown();
		long before = RedisDegradation.count(RedisDegradation.C1_RUN_INDEX_PRUNE);

		assertThatCode(() -> this.store.pruneRunIndex()).doesNotThrowAnyException();
		assertThat(this.store.pruneRunIndex()).isZero();

		assertThat(RedisDegradation.count(RedisDegradation.C1_RUN_INDEX_PRUNE)).isGreaterThan(before);
		assertThat(output.getAll()).contains("ai:runs 索引惰性清理失败");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedIndex(String... runIds) {
		Set<String> members = this.fake.setsByKey.computeIfAbsent(RunStore.RUN_INDEX,
				key -> ConcurrentHashMap.newKeySet());
		for (String runId : runIds) {
			members.add(RunKeyScope.indexMember(1L, 101L, runId));
		}
	}

	private void seedSnapshot(String runId) {
		this.fake.strings.put(this.store.runKey(runId), "{}");
	}

	/** 索引成员（归一成 runId：断言面仍是"哪些轮在索引里"）。 */
	private Set<String> members() {
		Set<String> out = new java.util.LinkedHashSet<>();
		for (String member : this.fake.setsByKey.getOrDefault(RunStore.RUN_INDEX, Set.of())) {
			RunKeyScope.IndexRef ref = RunKeyScope.parseIndexMember(member);
			out.add(ref == null ? member : ref.runId());
		}
		return out;
	}

}
