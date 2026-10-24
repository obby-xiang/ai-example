package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 原生 <b>Redis Cluster</b>（3 主 3 从 / 16384 槽）下的运行面回归（B23L1 批① Cluster 硬阻断 /
 * 报告 §十 批次 1 的 R1-01 / R1-02 / R1-03 / R1-05）。
 *
 * <h2>为什么必须有这一层用例</h2>
 * 现网门禁、e2e、双实例 harness 全是<b>单节点 Redis</b>：跨槽在单节点下不存在，任何脚本写法都绿。
 * 于是"多键 Lua 跨槽"这类缺陷在现有 1000+ 例里<b>没有一例能红</b>。本类把
 * {@link FakeLeaseRedis#enableClusterMode()} 打开 —— 替身按真 Redis 口径（CRC16-XMODEM mod 16384，
 * hash tag 优先）逐条判 KEYS 是否同槽，跨槽即抛 {@link FakeLeaseRedis.CrossSlotException}。
 *
 * <h2>失败模式的不对称（本类存在的理由）</h2>
 * <ul>
 * <li>{@code ACQUIRE} 跨槽会被 {@code LeaseService#acquire} 的 catch 折成 {@code UNAVAILABLE}
 * ⇒ <b>静默退化</b>（租约、fencing、资源锁三族一起失效，日志只有 WARN）；</li>
 * <li>{@code FENCED_SET} 跨槽是<b>硬抛</b>（{@code RunStore#fencedSet} 包成
 * {@code IllegalStateException} ⇒ 500）：只修 ACQUIRE 会第一次让 {@code epoch>0} 成立，
 * 于是"每次快照写都 500"，比现状（静默无 fencing）更糟 —— 两者<b>必须同批</b>；</li>
 * <li>{@code IDEM_PUSH} 跨槽炸在<b>不可撤销的副作用已经发生之后</b>（台账与索引都没写 ⇒ 续跑再执行一遍）；</li>
 * <li>{@code deleteRun} 的 13 键 {@code DEL} 跨槽：客户端会按槽拆键 ⇒ 原子性没了（严格实现直接报错）。</li>
 * </ul>
 *
 * <p><b>断言的是行为</b>：Cluster 口径下每一族都真的走通（拿到租约、写进快照、台账与索引同落、
 * 轮次回收不抛），而不是"键名里有没有花括号"。键名只作为"为什么同槽"的解释性断言。
 */
class ClusterSlotTest {

	private static final String RUN_ID = "run-cluster-1";

	private static final Duration TTL = Duration.ofSeconds(30);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final LeaseService lease = new LeaseService(this.fake.redis, this.properties, new FakeRenewScheduler(),
			"inst-cluster");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.lease);

	@BeforeEach
	void clusterOn() {
		this.fake.enableClusterMode();
	}

	// ── R1-01：ACQUIRE 取号外置为单键 INCR，脚本只剩单键 GET/SET ─────────────

	/**
	 * 抢租在 Cluster 下必须真的<b>抢到</b>（而不是被 catch 折成 {@code UNAVAILABLE}）。
	 *
	 * <p>静默退化的现场表现：{@code leaseOk=true}、日志 WARN、业务照跑，但每个实例都"没拿到租约"
	 * ⇒ 续跑权互斥消失、fencing 写退化为 plain SET。这条用例把它钉成红。
	 */
	@Test
	void acquireWorksUnderCluster() {
		// 生产入口：三键同槽（租约 / 计数器 / fence），取号与占用同一个原子步（评审 N-1）
		LeaseService.AcquireResult result = this.store.acquireRunLease(RUN_ID);

		assertThat(result.outcome()).as("Cluster 下抢租不得退化成 UNAVAILABLE").isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(result.lease()).isNotNull();
		assertThat(result.lease().epoch()).as("epoch 由 per-run 计数器发放（>0）").isPositive();
		assertThat(result.created()).as("首次抢到 = 创建者").isTrue();
		assertThat(this.fake.lastScriptKeys()).as("取号在脚本内：ACQUIRE 带 租约键 + 计数器键 + fence 键").hasSize(3);
		assertThat(this.fake.lastScriptKeys()).containsExactly(this.store.runLeaseKey(RUN_ID),
				this.store.runLeaseEpochKey(RUN_ID), this.store.fenceKey(RUN_ID));
		assertThat(this.fake.string(this.store.runLeaseEpochKey(RUN_ID)))
			.as("取号落在与租约键同槽的 per-run 计数器上").isEqualTo(String.valueOf(result.lease().epoch()));
		assertThat(this.fake.string(LeaseService.EPOCH_KEY))
			.as("旧全局计数器不再参与 run 租约取号").isNull();
	}

	/** 同 owner 重入不消耗新 epoch、不第二次 SET（L3 口径在 Cluster 下同样成立）。 */
	@Test
	void reentrantAcquireDoesNotAdvanceTheEpochUnderCluster() {
		LeaseService.Lease first = this.lease.acquire(this.store.runLeaseKey(RUN_ID), TTL).lease();
		LeaseService.AcquireResult again = this.lease.acquire(this.store.runLeaseKey(RUN_ID), TTL);

		assertThat(again.outcome()).isEqualTo(LeaseService.AcquireOutcome.ACQUIRED);
		assertThat(again.created()).isFalse();
		assertThat(again.lease().epoch()).isEqualTo(first.epoch());
	}

	// ── R1-02 / R1-03：FENCED_SET 与 IDEM_PUSH 同槽 ─────────────────────────

	/**
	 * 持租后的快照写必须走通（否则就是"抢到租约 ⇒ 每次 save 500"），台账与索引也必须同落。
	 *
	 * <p>这条把 R1-01 与 R1-02/03 的<b>联动</b>钉在一起：只修 ACQUIRE 时它必红。
	 */
	@Test
	void fencedWriteAndLedgerPushWorkUnderCluster() {
		LeaseService.Lease held = this.store.tryAcquireRunLease(RUN_ID).orElseThrow();
		assertThat(held.epoch()).isPositive();

		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("session-cluster");
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);

		assertThat(this.fake.string(this.store.runKey(RUN_ID))).as("fence 写进得去（fence ≤ epoch）").isNotNull();
		assertThat(this.fake.string(this.store.fenceKey(RUN_ID))).isEqualTo(String.valueOf(held.epoch()));

		this.store.appendLedger(RUN_ID, entry("call-1"), "已执行");

		assertThat(this.fake.table(this.store.idemKey(RUN_ID)))
			.as("幂等索引与台账同一个原子步落库").containsKey(RunStore.idemField(RUN_ID, "call-1"));
		assertThat(this.store.idemFor(RUN_ID, "call-1")).as("读口命中索引").isNotNull();

		// 键名解释：两族各自同槽（hash tag = {run:<runId>}），且 tag 用的就是 runId 本身。
		assertThat(this.store.runKey(RUN_ID)).contains("{run:" + RUN_ID + "}");
		assertThat(FakeLeaseRedis.slot(this.store.runKey(RUN_ID)))
			.as("FENCED_SET：快照与 fence 必须同槽").isEqualTo(FakeLeaseRedis.slot(this.store.fenceKey(RUN_ID)));
		assertThat(FakeLeaseRedis.slot(this.store.ledgerKey(RUN_ID)))
			.as("IDEM_PUSH：台账与幂等索引必须同槽").isEqualTo(FakeLeaseRedis.slot(this.store.idemKey(RUN_ID)));
	}

	// ── R1-05：deleteRun 的 13 键同槽，一条 DEL 原子清空 ─────────────────────

	/**
	 * 一轮的<b>12 个键必须落同一槽</b>（tag = {@code {run:<runId>}}，且不许留旧裸键形）。
	 *
	 * <p>为什么是"全部 12"而不是"多键脚本用到的那几个"：{@code deleteRun} 用<b>一条</b>
	 * {@code DEL} 清这一轮的全部键 —— 只要有一个键在别的槽上，这条命令在 Cluster 下就会被拆成
	 * N 条（原子性消失），严格客户端还会直接报错。
	 */
	@Test
	void thirteenRunKeysShareOneSlot() {
		Set<String> keys = new LinkedHashSet<>(List.of(this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.claimKey(RUN_ID), this.store.ledgerKey(RUN_ID), this.store.idemKey(RUN_ID),
				this.store.eventsKey(RUN_ID), this.store.seqKey(RUN_ID), this.store.xeventsKey(RUN_ID),
				this.store.runLeaseKey(RUN_ID), this.store.fenceKey(RUN_ID), this.store.reqKey(RUN_ID),
				this.store.decidedKey(RUN_ID), this.store.runLeaseEpochKey(RUN_ID)));

		assertThat(keys).as("13 个键两两不同（拼接口没把两个键名合成一个）").hasSize(13);
		assertThat(keys).allSatisfy(key -> assertThat(key).as("键形必须带 {run:<runId>} tag")
			.contains("{run:" + RUN_ID + "}"));
		int slot = FakeLeaseRedis.slot(this.store.runKey(RUN_ID));
		assertThat(keys).allSatisfy(key -> assertThat(FakeLeaseRedis.slot(key)).as("同槽：%s", key).isEqualTo(slot));

		// 反证（为什么这条用例在改造前必红）：旧裸键形的四个"帧族/租约"键槽号各不相同 ——
		// 改造前 deleteRun 的那条多键 DEL 正是跨这四个槽。
		Set<Integer> legacySlots = new LinkedHashSet<>(List.of(
				FakeLeaseRedis.slot(RunStore.LEASE_RUN_PREFIX + RUN_ID),
				FakeLeaseRedis.slot(RunStore.EVENTS_PREFIX + RUN_ID),
				FakeLeaseRedis.slot(RunStore.SEQ_PREFIX + RUN_ID),
				FakeLeaseRedis.slot(RunStore.XEVENTS_PREFIX + RUN_ID)));
		assertThat(legacySlots).as("旧键形不在同一槽 ⇒ 13 键 DEL 曾经跨槽").hasSizeGreaterThan(1);

		// 取号计数器与租约键必须<b>是同一个键</b>：LeaseService 按租约键的 tag 推导，RunStore 按 runId 拼，
		// 两条路径各拼一份就会漂移（后果是脚本带两个不同槽的键 ⇒ CROSSSLOT ⇒ 抢租静默 UNAVAILABLE）。
		assertThat(LeaseService.epochKeyFor(this.store.runLeaseKey(RUN_ID)))
			.as("LeaseService 推出来的取号键 = RunStore 的 per-run 计数器键")
			.isEqualTo(this.store.runLeaseEpochKey(RUN_ID));
		assertThat(LeaseService.epochKeyFor(LeaseKeys.task(7L)))
			.as("无 tag 的资源锁没有同槽计数器（走全局）").isNull();
	}

	/**
	 * 帧日志三族的键名与 {@link RunStore} 的键方法<b>逐字相同</b>（写方在 FrameStore、删/续期方在 RunStore）。
	 *
	 * <p>这条是"13 键同槽"能否成立的前提：{@code RedisFrameStore} 若自己拼名字（裸前缀），
	 * 写方与删方就落在两个键上 —— 事件日志永不回收、也永不续期，而且 13 键里那个"xevents"其实是空键。
	 */
	@Test
	void frameStoreAndRunStoreAgreeOnKeyNames() {
		RedisFrameStore frames = new RedisFrameStore(this.fake.redis, this.mapper, this.properties);

		assertThat(frames.streamKey(RUN_ID)).isEqualTo(this.store.xeventsKey(RUN_ID));
		assertThat(frames.seqKey(RUN_ID)).isEqualTo(this.store.seqKey(RUN_ID));
		assertThat(frames.legacyListKey(RUN_ID)).isEqualTo(this.store.eventsKey(RUN_ID));
	}

	/**
	 * 租约键反解两种键形（迁移期：旧实例写的裸键 + 新实例写的 tagged 键）。
	 *
	 * <p>{@code ConfirmGate#runIdOf} 靠它做"续跑权还归我吗"的存活判据；反解错 = 判据转假 =
	 * 每拍释放租约（续跑权静默丢失），所以两种形态都要钉住。
	 */
	@Test
	void leaseKeyReverseParseAcceptsTaggedAndLegacyShapes() {
		assertThat(RunStore.runIdFromLeaseKey(this.store.runLeaseKey(RUN_ID))).as("新键形（带 tag）")
			.isEqualTo(RUN_ID);
		assertThat(RunStore.runIdFromLeaseKey(RunStore.LEASE_RUN_PREFIX + RUN_ID)).as("迁移期旧裸键形")
			.isEqualTo(RUN_ID);
		assertThat(RunStore.runIdFromLeaseKey("ai:lock:task:7")).as("不是租约键 ⇒ null（判据退化，不误判）").isNull();
	}

	/**
	 * <b>全路径真实写→读→删</b>：13 个键各由真实业务口写一次（不是直接铺 Redis），再从读口读回，
	 * 最后 {@code deleteRun} 用<b>一条</b>命令清空 —— 全程 Cluster 口径（任何跨槽都会抛）。
	 *
	 * <p>覆盖"写新删旧/写旧删新"这类键形不一致：只要有一族两边拼得不一样，本用例必红。
	 */
	@Test
	void writeReadThenDeleteAllThirteenKeysInOneCommand() {
		this.store.tryAcquireRunLease(RUN_ID).orElseThrow();
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("session-cluster");
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);
		this.store.saveSuspended(RUN_ID, "assistant", List.of(pendingItem("call-1")), List.of(),
				mock(MessageJsonCodec.class));
		this.store.claimExecution(RUN_ID, "call-1", this.store.instanceId());
		this.store.markRequestEmitted(RUN_ID, "call-1", this.fake.nowMs());
		this.store.tryRecordDecision(RUN_ID, "call-1", PendingToolCall.APPROVED);
		this.store.appendLedger(RUN_ID, entry("call-1"), "已执行");
		// 帧三族的写方是 RedisFrameStore（本替身不建模 Stream）：按它的键名铺"已写过"的痕迹，
		// 用来证明删除方认的是同一个键（键名不同则删不掉，下面的断言会红）。
		RedisFrameStore frames = new RedisFrameStore(this.fake.redis, this.mapper, this.properties);
		this.fake.strings.put(frames.streamKey(RUN_ID), "stream-placeholder");
		this.fake.strings.put(frames.seqKey(RUN_ID), "7");
		this.fake.lists.put(frames.legacyListKey(RUN_ID), new java.util.concurrent.CopyOnWriteArrayList<>());

		// 读路径：全部读得回来（键形一致 ⇒ 读方看到写方）
		assertThat(this.store.pending(RUN_ID, "call-1")).isNotNull();
		assertThat(this.store.claimedBy(RUN_ID, "call-1")).isEqualTo(this.store.instanceId());
		assertThat(this.store.idemFor(RUN_ID, "call-1")).as("幂等索引读得到").isNotNull();
		assertThat(this.store.ledger(RUN_ID)).hasSize(1);
		assertThat(this.store.seqKey(RUN_ID)).isEqualTo(frames.seqKey(RUN_ID));

		this.fake.resetCounters();
		this.store.deleteRun(RUN_ID);

		assertThat(this.fake.templateDeletes).as("13 键一条 DEL（不是 N 条单键删）").hasValue(1);
		assertThat(this.fake.lastDeletedKeys).as("一条命令恰好带 13 个键").hasSize(13);
		Set<String> expected = new LinkedHashSet<>(List.of(this.store.runKey(RUN_ID),
				this.store.pendingKey(RUN_ID), this.store.claimKey(RUN_ID), this.store.ledgerKey(RUN_ID),
				this.store.idemKey(RUN_ID), this.store.eventsKey(RUN_ID), this.store.seqKey(RUN_ID),
				this.store.xeventsKey(RUN_ID), this.store.runLeaseKey(RUN_ID), this.store.fenceKey(RUN_ID),
				this.store.reqKey(RUN_ID), this.store.decidedKey(RUN_ID), this.store.runLeaseEpochKey(RUN_ID)));
		assertThat(new LinkedHashSet<>(this.fake.lastDeletedKeys)).isEqualTo(expected);

		assertThat(this.store.get(RUN_ID)).as("快照已删").isNull();
		assertThat(this.fake.hashTables.get(this.store.pendingKey(RUN_ID))).as("待决已删").isNull();
		assertThat(this.fake.table(this.store.claimKey(RUN_ID))).as("认领已删").isEmpty();
		assertThat(this.fake.lists.get(this.store.ledgerKey(RUN_ID))).as("台账已删").isNull();
		assertThat(this.fake.hashTables.get(this.store.idemKey(RUN_ID))).as("索引已删").isNull();
		assertThat(this.fake.lists.get(this.store.eventsKey(RUN_ID))).as("兼容 LIST 已删").isNull();
		assertThat(this.fake.string(this.store.seqKey(RUN_ID))).as("seq 已删").isNull();
		assertThat(this.fake.string(frames.streamKey(RUN_ID))).as("事件 Stream 已删").isNull();
		assertThat(this.fake.string(this.store.runLeaseKey(RUN_ID))).as("租约已删").isNull();
		assertThat(this.fake.string(this.store.fenceKey(RUN_ID))).as("fence 已删").isNull();
		assertThat(this.fake.string(this.store.runLeaseEpochKey(RUN_ID))).as("取号计数器已删").isNull();
		assertThat(this.fake.table(this.store.reqKey(RUN_ID))).as("req 位已删").isEmpty();
		assertThat(this.fake.table(this.store.decidedKey(RUN_ID))).as("决策 guard 已删").isEmpty();
		assertThat(this.store.idemFor(RUN_ID, "call-1")).as("读口同步收敛为空").isNull();
	}

	// ── 替身自身的非空断言（否则上面几条可能是"没检查"） ───────────────────

	/** 跨槽的多键脚本必须抛 —— 证明 {@link FakeLeaseRedis#enableClusterMode()} 不是装饰。 */
	@Test
	void crossSlotScriptsAreRejected() {
		String left = "ai:probe:{run:a}";
		String right = "ai:probe:{run:b}";
		assertThat(FakeLeaseRedis.slot(left)).as("语料前提：两个 tag 不同槽").isNotEqualTo(FakeLeaseRedis.slot(right));

		var probe = new DefaultRedisScript<Long>("-- LEASE_VERIFY\nreturn 0", Long.class);
		assertThatThrownBy(() -> this.fake.redis.execute(probe, List.of(left, right), "0|probe"))
			.isInstanceOf(FakeLeaseRedis.CrossSlotException.class)
			.hasMessageContaining("CROSSSLOT");
	}

	/** 槽算法自检：Redis 官方示例的三个键（foo/bar/hello）+ hash tag 优先规则。 */
	@Test
	void slotAlgorithmMatchesRedis() {
		assertThat(FakeLeaseRedis.slot("foo")).isEqualTo(12182);
		assertThat(FakeLeaseRedis.slot("bar")).isEqualTo(5061);
		assertThat(FakeLeaseRedis.slot("hello")).isEqualTo(866);
		assertThat(FakeLeaseRedis.slot("{user1000}.following"))
			.as("tag 优先：同 tag 的前后缀落同一槽").isEqualTo(FakeLeaseRedis.slot("{user1000}.followers"));
		assertThat(FakeLeaseRedis.hashTagOf("foo{}{bar}")).as("空 tag 不算 tag（按整键散列）")
			.isEqualTo("foo{}{bar}");
		assertThat(FakeLeaseRedis.hashTagOf("{user1000}.x")).isEqualTo("user1000");
	}

	// ── 语料 ────────────────────────────────────────────────────────────────

	private PendingToolCall entry(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setExecuted(true);
		pending.setExecutedBy(this.store.instanceId());
		return pending;
	}

	/** 挂起中的待决条目（{@code saveSuspended} 的入参形态）。 */
	private PendingToolCall pendingItem(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.PENDING);
		pending.setRequestedAtMs(this.fake.nowMs());
		return pending;
	}

}
