package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B23L1 批① · 事件日志（{@link RedisFrameStore}）机制单测。
 *
 * <p>
 * 断言对象是<b>存储语义</b>而不是"某方法被调用过"：取号单调不重、按号回读的四个边界、
 * 窗口近似裁剪、TTL 滑动、LIST/Stream 兼容拼接去重。Redis 用进程内替身
 * （{@link FakeFrameRedis}，按 INCR/XADD/XRANGE 的语义实现，<b>且按目标部署 5.0.14
 * 拒绝排他区间</b>），不起真实 Redis。
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisFrameStoreTest {

	private static final String RUN_ID = "run-frames";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	// ── M1 取号：单调、不重、并发安全 ────────────────────────────────────────

	@Test
	void seqStartsAtOneAndNeverGoesBackwards() {
		RedisFrameStore store = store(new FakeFrameRedis());

		assertThat(store.lastSeq(RUN_ID)).as("空日志末号 = 0").isZero();
		assertThat(nextAndAppend(store, "start")).isEqualTo(1L);
		assertThat(nextAndAppend(store, "delta")).isEqualTo(2L);
		assertThat(nextAndAppend(store, "done")).isEqualTo(3L);

		assertThat(store.lastSeq(RUN_ID)).isEqualTo(3L);
		assertThat(seqs(store.readFrom(RUN_ID, 0L, 0))).containsExactly(1L, 2L, 3L);
	}

	/**
	 * 并发取号（8 线程 × 50 次）：号集合必须恰好是 1..400 —— 不重号、不回退、不漏号。
	 *
	 * <p>
	 * <b>牙口口径（M-5 修订）</b>：替身的 {@code INCR} 是原子 {@code merge}，因此本用例守的是
	 * <b>"生产取号确实走 Redis 原子自增"</b>这条实现事实 —— 若把 {@code nextSeq} 改成
	 * "读事件日志末号 + 1"（进程内算号），并发下必然重号，本用例变红；但替身无法复现
	 * "真实 Redis 上 GET+SET 的竞态"，那一面靠 Redis 语义保证，不在本用例承诺内。
	 */
	@Test
	void concurrentSeqAllocationIsUniqueAndMonotonic() throws Exception {
		RedisFrameStore store = store(new FakeFrameRedis());
		int threads = 8;
		int perThread = 50;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		Set<Long> issued = java.util.Collections.synchronizedSet(new HashSet<>());
		AtomicInteger failures = new AtomicInteger();
		try {
			for (int i = 0; i < threads; i++) {
				pool.execute(() -> {
					try {
						start.await(5, TimeUnit.SECONDS);
						for (int j = 0; j < perThread; j++) {
							issued.add(store.nextSeq(RUN_ID));
						}
					}
					catch (Exception ex) {
						failures.incrementAndGet();
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures.get()).isZero();
		assertThat(issued).as("并发取号不得重号（数量 = 线程数 × 次数）").hasSize(threads * perThread);
		assertThat(issued).as("号空间恰好是 1..400，无跳号").containsExactlyInAnyOrderElementsOf(
				java.util.stream.LongStream.rangeClosed(1, threads * perThread).boxed().toList());
	}

	/**
	 * M-1：{@code ai:seq} 键过期/被删后取号<b>不许从 1 重来</b>（否则与历史同号 ⇒
	 * mergeBySeq 同号合并丢帧、前端 lastSeq 倒退）。
	 *
	 * <p>kill：去掉 {@code repairRollback}（回到"只 SETNX 一次"）⇒ 键消失后 INCR 返回 1，本用例变红。
	 */
	@Test
	void seqDoesNotRollBackWhenTheSeqKeyExpiresOrIsDeleted() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		for (int i = 0; i < 3; i++) {
			nextAndAppend(store, "delta");
		}
		assertThat(store.nextSeq(RUN_ID)).as("正常态：4（已发放未落档）").isEqualTo(4L);

		redis.dropCounter(store.seqKey(RUN_ID));
		long repaired = store.nextSeq(RUN_ID);
		assertThat(repaired).as("键过期/被删后按已落档末号抬回，不许从 1 重来").isEqualTo(4L);
		store.append(RUN_ID, frame("delta", repaired));

		redis.dropCounter(store.seqKey(RUN_ID));
		assertThat(store.nextSeq(RUN_ID)).as("再来一次仍按末号 4 抬回 ⇒ 5").isEqualTo(5L);
		assertThat(seqs(store.readFrom(RUN_ID, 0L, 0))).as("日志里无同号重号").doesNotHaveDuplicates();
	}

	// ── I-2：晚到的低号帧仍可读（落档改为"重分配号"，而不是自动 ID 兜底） ────────

	/**
	 * 低号帧后到（跨实例并发出帧 / 降级号回退）：显式 ID {@code 4-0} 已被表尾越过
	 * ⇒ 落档<b>重分配号</b>后再写（本批口径），于是流里每条记录的 ID 仍等于它自己的 {@code seq}，
	 * 读者的 {@code XRANGE <afterSeq+1>-0 +} 仍是精确差量：<b>三帧都读得到、不重、不整窗</b>。
	 *
	 * <p>旧口径在这条路径改用自动 ID：物理位次靠后但 seq 字段是原号，读侧要靠"整窗 + 按 seq 过滤"
	 * 兜住，末号探测也会被墙钟 ID 干扰（RT-31）。
	 *
	 * <p>kill：退回 {@code XADD *} 兜底 ⇒ ID == seq 断言红；丢掉被拒的帧 ⇒ "三帧都在"红。
	 */
	@Test
	void lateLowSeqAppendIsResequencedAndStaysReadable() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		appendRaw(store, frame("delta", 5L));
		appendRaw(store, frame("delta", 6L));

		// 乱序：4 号在 5/6 之后才落档（显式 ID 4-0 已小于表尾）
		appendRaw(store, frame("delta", 4L));

		assertPhysicalIdsMatchBusinessSeqs(redis, store.streamKey(RUN_ID));
		assertThat(redis.stream(store.streamKey(RUN_ID))).as("低号帧不得丢（重分配后仍落档）").hasSize(3);

		List<Long> archived = seqs(store.readFrom(RUN_ID, 0L, 0));
		assertThat(archived).as("低号帧必须仍可读（对所有读者）、按号升序、不重")
			.hasSize(3)
			.doesNotHaveDuplicates()
			.isSorted();
		assertThat(seqs(store.readFrom(RUN_ID, archived.get(0), 0))).as("差量读精确（不是整窗回退）")
			.containsExactly(archived.get(1), archived.get(2));
		assertThat(store.lastSeq(RUN_ID)).as("末号精确").isEqualTo(archived.get(2));
	}

	// ── 多实例并发写同一轮：物理 ID 必须恒等于业务 seq（禁止自动 ID 兜底） ──────

	/**
	 * <b>多实例并发落档</b>：另一实例（confirm / frontend-tool-result）先落档了更高的号，
	 * 驱动随后才落自己的低号帧 —— 显式 ID {@code <seq>-0} 被表尾越过。
	 *
	 * <p>判据（本批要钉的唯一一条）：<b>物理 Stream ID 必须始终等于业务 seq</b>
	 * ——每条记录的 ID 毫秒段、{@code seq} 字段、帧体内的 {@code seq} 三者同值，
	 * 且<b>绝不</b>退回 {@code XADD *}（自动 ID）。
	 *
	 * <p>为什么自动 ID 是缺陷而不是兜底：自动 ID 的毫秒段是写入时刻的墙钟（≈1.7e12），
	 * 远大于任何 {@code seq-0} ⇒ 一旦出现一条，<b>此后每一条</b>显式 ID 都会被拒、
	 * 全部帧都改成墙钟序落档：物理序与 seq 序从此分叉，末号探测（末
	 * {@value RedisFrameStore#TAIL_PROBE} 条取 max）在乱序 ≥ 该条数时低估 ⇒ 读者拿到
	 * "空结果且 degraded=false"（永久缺段）、前端 lastSeq 倒退、差量读退化为整窗。
	 *
	 * <p>正确口径：被表尾越过时<b>重分配序号</b>（Redis 原子抬号，仍落在同一槽）并重试落档 ——
	 * 帧不丢、ID 与 seq 仍一一对应；重复写同一帧则幂等跳过（见 {@link #repeatedAppendIsIdempotent}）。
	 *
	 * <p>kill：改回 {@code XADD *} 兜底 ⇒ 首条断言（ID == seq）即红；
	 * 丢掉被拒的帧 ⇒ "两帧都在"红；按 {@code seq} 之外的口径算末号 ⇒ 末号断言红。
	 */
	@Test
	void concurrentInstancesNeverFallBackToAutoIds() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore driver = store(redis);
		RedisFrameStore confirm = store(redis);

		long driverSeq = driver.nextSeq(RUN_ID);
		long confirmSeq = confirm.nextSeq(RUN_ID);
		assertThat(driverSeq).as("两实例共享同一号源（跨实例原子自增）").isEqualTo(1L);
		assertThat(confirmSeq).isEqualTo(2L);

		// 另一实例先落档高号；驱动的低号帧随后才到（跨实例并发的真实交错）
		confirm.append(RUN_ID, frame("tool_result", confirmSeq));
		driver.append(RUN_ID, frame("delta", driverSeq));

		assertPhysicalIdsMatchBusinessSeqs(redis, driver.streamKey(RUN_ID));
		assertThat(redis.stream(driver.streamKey(RUN_ID))).as("低号帧不得丢（重分配后必须仍落档）").hasSize(2);

		List<Long> archived = seqs(driver.readFrom(RUN_ID, 0L, 0));
		assertThat(archived).as("两帧都读得到、按号升序、不重").hasSize(2).doesNotHaveDuplicates();
		long maxSeq = archived.get(1);
		assertThat(driver.lastSeq(RUN_ID)).as("末号必须精确（不得低估）").isEqualTo(maxSeq);
		assertThat(seqs(driver.readFrom(RUN_ID, maxSeq, 0))).as("afterSeq = 末号：无差量（不回退成整窗读）").isEmpty();
		assertThat(seqs(driver.readFrom(RUN_ID, confirmSeq, 0))).as("差量读只给 > afterSeq 的帧")
			.containsExactly(maxSeq);
	}

	/** 记录的物理 ID、{@code seq} 字段与帧体里的 {@code seq} 必须同值（{@code <seq>-0}）。 */
	private static void assertPhysicalIdsMatchBusinessSeqs(FakeFrameRedis redis, String streamKey) {
		List<String> ids = redis.streamIds(streamKey);
		assertThat(ids).hasSameSizeAs(redis.stream(streamKey));
		for (int i = 0; i < ids.size(); i++) {
			assertPhysicalIdMatchesBusinessSeq(redis, streamKey, i);
		}
	}

	/** 第 {@code index} 条（物理序）记录的 ID / {@code seq} 字段 / 帧体 {@code seq} 必须同值。 */
	private static void assertPhysicalIdMatchesBusinessSeq(FakeFrameRedis redis, String streamKey, int index) {
		String id = redis.streamIds(streamKey).get(index);
		Map<String, byte[]> fields = FakeFrameRedis.asMap(redis.stream(streamKey).get(index));
		long seq = Long.parseLong(new String(fields.get(RedisFrameStore.FIELD_SEQ),
				java.nio.charset.StandardCharsets.UTF_8));
		assertThat(id).as("物理 ID 必须等于业务 seq（禁止自动 ID 兜底）").isEqualTo(seq + "-0");
		String body = new String(fields.get(RedisFrameStore.FIELD_FRAME), java.nio.charset.StandardCharsets.UTF_8);
		assertThat(body).as("帧体内的 seq 必须与记录 ID 一致（重分配后重新序列化）")
			.contains("\"seq\":" + seq);
	}

	/**
	 * <b>幂等</b>：同一份帧（同号 + 同帧体）被重复落档（重试 / 重复调用）时不得写出第二条记录。
	 *
	 * <p>旧实现在这里落到"显式 ID 被拒 ⇒ 自动 ID 兜底"：同一个 {@code seq} 在流里有两条记录，
	 * 回放时同号合并（{@code mergeBySeq}）只能留一条，前端 {@code lastSeq} 也会因为墙钟 ID 而错位。
	 * 新口径：表尾就是同一 {@code seq} 且帧体逐字节相同 ⇒ 视为已落档（只续期，不写第二条）。
	 */
	@Test
	void repeatedAppendIsIdempotent() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);

		long seq = store.nextSeq(RUN_ID);
		Map<String, Object> frame = frame("delta", seq);
		store.append(RUN_ID, frame);
		store.append(RUN_ID, frame);

		assertThat(redis.stream(store.streamKey(RUN_ID))).as("同一帧重试不得写出第二条（同号重复会丢一帧）")
			.hasSize(1);
		assertPhysicalIdsMatchBusinessSeqs(redis, store.streamKey(RUN_ID));
		assertThat(store.lastSeq(RUN_ID)).isEqualTo(seq);
	}

	/**
	 * <b>升级兼容</b>：表尾遗留旧实现的"自动 ID 兜底"记录（墙钟毫秒 ID）时，新的落档
	 * 既不得继续用自动 ID，也不得因为"任何 {@code seq-0} 都小于墙钟 ID"而被永久拒绝（= 整轮静默丢帧）。
	 *
	 * <p>口径：按表尾 ID 的毫秒段作地板<b>重分配</b>一次号（于是这次跳号），此后 ID == seq 恢复。
	 * 不处理这一形态的代价：部署后 24h 内（遗留记录的 TTL）所有落档都被拒 ⇒ 事件日志整段不可见。
	 */
	@Test
	void appendAboveALegacyAutoIdTailResequencesInsteadOfUsingAutoIds() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		long legacySeq = store.nextSeq(RUN_ID);
		seedAutoIdEntry(redis, store.streamKey(RUN_ID), legacySeq, "{\"type\":\"delta\",\"seq\":1}");

		long nextSeq = store.nextSeq(RUN_ID);
		store.append(RUN_ID, frame("delta", nextSeq));

		assertThat(redis.stream(store.streamKey(RUN_ID))).as("表尾是墙钟 ID 时也不得丢帧").hasSize(2);
		// 第 0 条是刻意铺的旧形态记录（它本就违反 ID == seq）；新落档的第 1 条必须符合不变量。
		assertPhysicalIdMatchesBusinessSeq(redis, store.streamKey(RUN_ID), 1);
		assertThat(store.lastSeq(RUN_ID)).as("末号 = 新落档的号（精确，不低估）")
			.isGreaterThan(legacySeq);
	}

	/** 用原始 {@code XADD ... *} 铺一条旧实现的自动 ID 记录（本批之前的落档形态）。 */
	private static void seedAutoIdEntry(FakeFrameRedis redis, String key, long seq, String frameJson) {
		java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
		redis.connection.execute("XADD", key.getBytes(utf8), "*".getBytes(utf8),
				RedisFrameStore.FIELD_SEQ.getBytes(utf8), String.valueOf(seq).getBytes(utf8),
				RedisFrameStore.FIELD_FRAME.getBytes(utf8), frameJson.getBytes(utf8));
	}

	// ── M2 readFrom 边界 ────────────────────────────────────────────────────

	@Test
	void readFromBoundariesCoverEmptyFullMiddleAndBeyondTail() {
		RedisFrameStore store = store(new FakeFrameRedis());
		for (int i = 1; i <= 5; i++) {
			nextAndAppend(store, "delta");
		}

		assertThat(store.readFrom(RUN_ID, 0L, 0)).as("全量").hasSize(5);
		assertThat(seqs(store.readFrom(RUN_ID, 2L, 0))).as("中间：只要 > 2").containsExactly(3L, 4L, 5L);
		assertThat(store.readFrom(RUN_ID, 5L, 0)).as("afterSeq = 末尾：无差量").isEmpty();
		assertThat(store.readFrom(RUN_ID, 99L, 0)).as("afterSeq 超出窗口：无差量").isEmpty();
		assertThat(seqs(store.readFrom(RUN_ID, 0L, 2))).as("limit 取最前面的两帧").containsExactly(1L, 2L);

		RedisFrameStore empty = store(new FakeFrameRedis());
		assertThat(empty.readFrom("run-empty", 0L, 0)).as("空日志").isEmpty();
	}

	// ── M3 窗口近似裁剪 ─────────────────────────────────────────────────────

	/**
	 * 窗口 = 8、追加 12 帧：日志只留尾部 8 帧（seq 5..12），且<b>号空间不回退</b>
	 * —— 裁剪只影响"能补回多远"，不影响"下一个号是多少"。
	 *
	 * <p>
	 * kill：把 {@code XADD} 的 MAXLEN 参数去掉 ⇒ 替身按命令实参裁剪、无参即不裁，12 帧全留，
	 * 本用例的"只剩 8 条"断言变红（M-5：替身读的是命令实参，不是构造参数）。
	 */
	@Test
	void streamIsTrimmedToWindowWhileSeqKeepsAdvancing() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis, 8);
		for (int i = 0; i < 12; i++) {
			nextAndAppend(store, "delta");
		}

		assertThat(redis.stream(store.streamKey(RUN_ID))).as("近似裁剪：窗口外的最旧帧被裁掉").hasSize(8);
		assertThat(seqs(store.readFrom(RUN_ID, 0L, 0))).containsExactly(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
		assertThat(store.lastSeq(RUN_ID)).isEqualTo(12L);
		assertThat(store.nextSeq(RUN_ID)).as("裁剪不影响取号（号空间继续往前）").isEqualTo(13L);
	}

	/**
	 * M′（复核确认轮）：{@code MAXLEN ~} 近似裁剪下流内可留 窗口+冗余 条；此时<b>全量回放</b>
	 * （{@code afterSeq=0}）必须拿到<b>最新窗口</b>且含末帧，而不是最旧窗口（旧实现会把最新的
	 * ≤ 冗余条丢掉，可能正是 {@code done}）。
	 *
	 * <p>kill：把读取下限退回 {@code afterSeq}（不夹到 {@code tail − 窗口}）⇒ 本用例拿到
	 * 5..12（缺 13/14/15），变红。
	 */
	@Test
	void fullReplayOverAnApproximatelyTrimmedStreamReturnsTheNewestWindow() {
		FakeFrameRedis redis = new FakeFrameRedis();
		redis.retainExtra = 3;
		RedisFrameStore store = store(redis, 8);
		for (int i = 0; i < 15; i++) {
			nextAndAppend(store, "delta");
		}

		assertThat(redis.stream(store.streamKey(RUN_ID))).as("近似裁剪：窗口 8 + 冗余 3 = 11 条").hasSize(11);
		assertThat(seqs(store.readFrom(RUN_ID, 0L, 0))).as("全量回放取最新窗口，含末帧")
				.containsExactly(8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L);
		assertThat(seqs(store.readFrom(RUN_ID, 9L, 0))).as("落后但仍在窗口内的读者只补差量")
				.containsExactly(10L, 11L, 12L, 13L, 14L, 15L);
	}

	/** G4（复审补）：{@code afterSeq} 落在被窗口裁掉的区间内 ⇒ 从现窗口头开始给，不抛错不阻塞。 */
	@Test
	void afterSeqInsideTheTrimmedRegionReturnsTheSurvivingTail() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis, 8);
		for (int i = 0; i < 12; i++) {
			nextAndAppend(store, "delta");
		}

		assertThat(seqs(store.readFrom(RUN_ID, 2L, 0))).as("2 号已被裁掉：从窗口头 5 开始，不报错")
				.containsExactly(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
		assertThat(seqs(store.readFrom(RUN_ID, 4L, 0))).containsExactly(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
	}

	/** G3（复审补）：跨源边界 —— LIST 末号 3、Stream 4..5，{@code afterSeq=3} 只应拿到 Stream 的 4、5。 */
	@Test
	void afterSeqBetweenListTailAndStreamTailReadsOnlyTheStreamPart() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER,
				List.of(frame("start", 1L), frame("delta", 2L), frame("suspended", 3L)));
		appendRaw(store, frame("tool_start", 4L));
		appendRaw(store, frame("done", 5L));

		assertThat(seqs(store.readFrom(RUN_ID, 3L, 0))).containsExactly(4L, 5L);
		assertThat(seqs(store.readFrom(RUN_ID, 2L, 0))).containsExactly(3L, 4L, 5L);
	}

	// ── M-4：降级路径（取号/落档失败不中断出帧） ────────────────────────────

	/** {@code INCR} 失败 ⇒ 进程内单调计数兜底，不抛异常；恢复后不得回退到降级号之下。 */
	@Test
	void incrementFailureDegradesToMonotonicInProcessCounting() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		long first = nextAndAppend(store, "start");
		assertThat(first).isEqualTo(1L);

		redis.failIncrement = true;
		long degradedFirst = store.nextSeq(RUN_ID);
		long degradedSecond = store.nextSeq(RUN_ID);
		assertThat(degradedFirst).isGreaterThan(first);
		assertThat(degradedSecond).isGreaterThan(degradedFirst);

		redis.failIncrement = false;
		long recovered = store.nextSeq(RUN_ID);
		assertThat(recovered).as("恢复后按降级末号抬回，不回退").isGreaterThan(degradedSecond);
	}

	/** {@code XADD} 失败 ⇒ 只记日志、不抛异常（帧仍可实时投递），事件日志留空。 */
	@Test
	void appendFailureIsSwallowedInsteadOfBreakingTheTurn() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.failXadd = true;

		org.assertj.core.api.Assertions.assertThatCode(() -> store.append(RUN_ID, frame("delta", 1L)))
				.as("落档失败不得把异常抛给出帧线程").doesNotThrowAnyException();
		assertThat(redis.stream(store.streamKey(RUN_ID))).isEmpty();
	}

	// ── M4 TTL 滑动 ────────────────────────────────────────────────────────

	@Test
	void appendRenewsSessionTtl() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);

		nextAndAppend(store, "start");

		assertThat(redis.expiry(store.streamKey(RUN_ID)))
				.as("事件日志与会话同寿（每次 append 续满 session-ttl）")
				.isEqualTo(Duration.ofHours(24));
		assertThat(redis.expiry(store.seqKey(RUN_ID)))
				.as("序号键同口径续期").isEqualTo(Duration.ofHours(24));
	}

	// ── M5 + M13 + M14：LIST 兼容读与拼接去重 ───────────────────────────────

	/** M13：迁移前的 LIST 数据（新写已不进 LIST）仍能完整回放。 */
	@Test
	void legacyListFramesRemainReplayable() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER,
				List.of(frame("start", 1L), frame("suspended", 2L), frame("done", 3L)));

		assertThat(seqs(store.readFrom(RUN_ID, 0L, 0))).containsExactly(1L, 2L, 3L);
		assertThat(store.lastSeq(RUN_ID)).as("末号取自 LIST 尾巴（兼容期）").isEqualTo(3L);
	}

	/**
	 * M14：混合期（LIST 1..3 + Stream 4..5）拼接正确 —— 升序、不重、不丢；同号以 Stream 为准。
	 */
	@Test
	void readFromMergesLegacyListAndStreamBySeq() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER,
				List.of(frame("start", 1L), frame("suspended", 2L), frame("delta", 3L, "text", "LIST 副本")));
		// 新写只进 Stream：seq 3 是同号的"新副本"（LIST 里那条是迁移前的旧副本）
		appendRaw(store, frame("delta", 3L, "text", "Stream 副本"));
		appendRaw(store, frame("done", 4L));

		List<Map<String, Object>> merged = store.readFrom(RUN_ID, 0L, 0);
		assertThat(seqs(merged)).containsExactly(1L, 2L, 3L, 4L);
		assertThat(merged.get(2)).as("同号以 Stream 为准").containsEntry("text", "Stream 副本");
		assertThat(seqs(store.readFrom(RUN_ID, 2L, 0))).containsExactly(3L, 4L);
		assertThat(store.lastSeq(RUN_ID)).isEqualTo(4L);
	}

	/** 没有 seq 的老帧（升级期）无法判孤儿 ⇒ 一律保留，且排在有序帧之前。 */
	@Test
	void legacyFramesWithoutSeqAreKeptAtTheHead() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		Map<String, Object> legacy = new LinkedHashMap<>();
		legacy.put("type", "suspended");
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER, List.of(legacy, frame("start", 1L)));
		appendRaw(store, frame("delta", 2L));

		List<Map<String, Object>> frames = store.readFrom(RUN_ID, 5L, 0);
		assertThat(frames).as("afterSeq 再大也不能丢无 seq 的老帧（宁可不发，不可丢帧）").hasSize(1);
		assertThat(frames.get(0)).containsEntry("type", "suspended");
	}

	/** 序号锚定：遗留 LIST 已经发到 41 号 ⇒ 新实例从 42 起号（不回退、不重号）。 */
	@Test
	void seqAnchorSeedsFromLegacyListTail() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER, List.of(frame("delta", 41L)));

		assertThat(store.nextSeq(RUN_ID)).isEqualTo(42L);
	}

	// ── 目标部署 Redis 5.0.14：排他区间（`(id`）不可用 ──────────────────────
	//  排他区间是 6.2 才有；5.0 把 `(123-0` 当非法 stream ID 直接报错，读侧若用它就是整次差量
	//  读失败（被 catch 吞成空 ⇒ 线上"回放 0 帧"）。替身按 5.0 报错，于是下面三条：
	//  首次回放 / lastSeq 差量 在排他实现下拿到 0 帧，闭区间实现下才是整窗与差量。

	/**
	 * 长 run（&gt; 窗口 {@value RedisFrameStore#STREAM_WINDOW}）首次回放：
	 * {@code afterSeq=0}，但读取下限被夹到 {@code tail − 窗口 &gt; 0} —— 排他实现下即 0 帧。
	 */
	@Test
	void firstReplayOfALongRunReturnsTheNewestWindow() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		int total = RedisFrameStore.STREAM_WINDOW + 5;
		for (int i = 0; i < total; i++) {
			nextAndAppend(store, "delta");
		}

		List<Long> replayed = seqs(store.readFrom(RUN_ID, 0L, 0));
		assertThat(replayed).as("首次回放必须拿到最新整窗 %d 帧（排他实现下是 0 帧）", RedisFrameStore.STREAM_WINDOW)
				.hasSize(RedisFrameStore.STREAM_WINDOW);
		assertThat(replayed.get(0)).as("窗口头 = tail − 窗口 + 1").isEqualTo(6L);
		assertThat(replayed.get(replayed.size() - 1)).as("含末帧").isEqualTo((long) total);
		assertThat(store.lastSeq(RUN_ID)).isEqualTo(total);
	}

	/** lastSeq 差量：{@code afterSeq=7 ⇒ 8/9/10}（{@code from &gt; 0}，排他实现下被吞成空）。 */
	@Test
	void deltaReadAboveLastSeqReturnsOnlyTheNewFrames() {
		RedisFrameStore store = store(new FakeFrameRedis());
		for (int i = 0; i < 10; i++) {
			nextAndAppend(store, "delta");
		}

		assertThat(seqs(store.readFrom(RUN_ID, 7L, 0))).containsExactly(8L, 9L, 10L);
		assertThat(store.readFrom(RUN_ID, 10L, 0)).as("afterSeq = 末号：无差量").isEmpty();
	}

	/** 普通空流：没有帧 ⇒ 空结果，且<b>不</b>得表现为读失败（两条路径必须可区分）。 */
	@Test
	void emptyStreamIsNotReportedAsAReadFailure(CapturedOutput output) {
		RedisFrameStore store = store(new FakeFrameRedis());
		long rangeBefore = RedisDegradation.count(RedisDegradation.A3_FRAME_RANGE_READ);
		long tailBefore = RedisDegradation.count(RedisDegradation.A4_FRAME_TAIL_READ);

		assertThat(store.readFrom(RUN_ID, 0L, 0)).isEmpty();
		assertThat(store.readFrom(RUN_ID, 5L, 0)).as("from > 0 的空流同样只是空").isEmpty();
		assertThat(store.readFromWithStatus(RUN_ID, 0L, 0).degraded()).as("空流不是降级读").isFalse();
		assertThat(store.lastSeqIfKnown(RUN_ID)).as("空流末号是已知的 0").hasValue(0L);
		assertThat(output.getAll()).as("空流不是读失败，不许告警").doesNotContain("读取失败").doesNotContain("探测失败");
		assertThat(RedisDegradation.count(RedisDegradation.A3_FRAME_RANGE_READ)).as("空流不得计入读失败")
			.isEqualTo(rangeBefore);
		assertThat(RedisDegradation.count(RedisDegradation.A4_FRAME_TAIL_READ)).as("空流不得计入末号失败")
			.isEqualTo(tailBefore);
	}

	/** 读失败（连接层异常）不得静默折成空：结果仍为空（韧性口径）但必须 WARN + 计数。 */
	@Test
	void streamReadFailureIsReportedInsteadOfSilentlyEmpty(CapturedOutput output) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		for (int i = 0; i < 3; i++) {
			nextAndAppend(store, "delta");
		}

		long before = RedisDegradation.count(RedisDegradation.A3_FRAME_RANGE_READ);
		redis.failRangeRead = true;
		assertThat(store.readFrom(RUN_ID, 0L, 0)).as("读失败不抛给调用链（挂起重连/唤醒补帧不受牵连）").isEmpty();
		assertThat(output.getAll()).as("读失败必须可观测：WARN + runId").contains("事件日志读取失败").contains(RUN_ID);
		assertThat(RedisDegradation.count(RedisDegradation.A3_FRAME_RANGE_READ))
			.as("A3 读失败必须进统一降级计数")
			.isGreaterThan(before);
	}

	/**
	 * 末号探测失败：必须 WARN + 计数；I-1 起 afterSeq=0 的回放不得退回最旧窗口，
	 * 必须返回"空 + degraded"由上层回 503/重试；lastSeqIfKnown 也必须为空（不能回 0）。
	 */
	@Test
	void tailProbeFailureIsReportedAndFullReplayReturnsDegradedEmpty(CapturedOutput output) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		nextAndAppend(store, "delta");

		long before = RedisDegradation.count(RedisDegradation.A4_FRAME_TAIL_READ);
		redis.failTailRead = true;
		FrameStore.ReadResult full = store.readFromWithStatus(RUN_ID, 0L, 0);
		assertThat(full.frames()).as("末号未知时全量回放不得退回最旧窗口").isEmpty();
		assertThat(full.degraded()).as("API 层必须能区分降级读与空流").isTrue();
		assertThat(store.lastSeqIfKnown(RUN_ID)).as("末号读失败不得折成 0").isEmpty();
		assertThat(store.lastSeq(RUN_ID)).isEqualTo(-1L);
		assertThat(output.getAll()).contains("事件日志末号探测失败").contains(RUN_ID);
		assertThat(RedisDegradation.count(RedisDegradation.A4_FRAME_TAIL_READ))
			.as("A4 末号探测失败必须进统一降级计数")
			.isGreaterThan(before);
	}

	/** 差量读（afterSeq>0）在末号未知时仍可用 afterSeq 作可靠下界，但必须标记 degraded。 */
	@Test
	void tailProbeFailureStillAllowsDeltaReadFromKnownAfterSeq() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		nextAndAppend(store, "a");
		nextAndAppend(store, "b");
		nextAndAppend(store, "c");

		redis.failTailRead = true;
		FrameStore.ReadResult delta = store.readFromWithStatus(RUN_ID, 2L, 0);
		assertThat(seqs(delta.frames())).containsExactly(3L);
		assertThat(delta.degraded()).isTrue();
	}

	/** 兼容期 LIST 末帧探测失败：整批老帧会被跳过 ⇒ 必须 WARN（不许伪装成空表）。 */
	@Test
	void legacyListTailProbeFailureIsReportedInsteadOfSilentlyEmpty(CapturedOutput output) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER, List.of(frame("start", 1L)));

		redis.failListTailProbe = true;
		assertThat(store.readFrom(RUN_ID, 0L, 0)).as("末帧探测失败 ⇒ 整段 LIST 分支被跳过").isEmpty();
		assertThat(output.getAll()).as("末帧探测失败必须 WARN，且文案与整段读可区分")
				.contains("末帧探测")
				.doesNotContain("本批 LIST 帧缺失");
	}

	/**
	 * 兼容期 LIST <b>整段读</b>失败：末帧探测成功、{@code readLegacyList} 才抛 —— 这是另一条独立路径
	 * （I1：老用例用"全挂"开关只命中末帧探测，把 {@code :409} 那条 WARN 改回 debug 仍全绿）。
	 */
	@Test
	void legacyListFullReadFailureIsReportedInsteadOfSilentlyEmpty(CapturedOutput output) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.seedList(store.legacyListKey(RUN_ID), MAPPER, List.of(frame("start", 1L)));

		redis.failListFullRead = true;
		assertThat(store.readFrom(RUN_ID, 0L, 0)).as("整段读失败 ⇒ 本批 LIST 帧缺失，返回空").isEmpty();
		assertThat(output.getAll()).as("整段 LIST 读取失败必须 WARN，且不是靠末帧探测兜住")
				.contains("本批 LIST 帧缺失")
				.doesNotContain("末帧探测");
	}

	// ── M7（存储侧）：心跳不进事件日志 ───────────────────────────────────────

	@Test
	void heartbeatIsNeverAppendedAndDoesNotConsumeASeq() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);

		Map<String, Object> heartbeat = frame("heartbeat", 0L);
		heartbeat.remove("seq");
		store.append(RUN_ID, heartbeat);

		assertThat(redis.stream(store.streamKey(RUN_ID))).isEmpty();
		assertThat(redis.counters).as("心跳不取业务号").doesNotContainKey(store.seqKey(RUN_ID));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private RedisFrameStore store(FakeFrameRedis redis) {
		return store(redis, 6000);
	}

	private RedisFrameStore store(FakeFrameRedis redis, int window) {
		return new RedisFrameStore(redis.redis, MAPPER, this.properties, window);
	}

	private long nextAndAppend(RedisFrameStore store, String type) {
		long seq = store.nextSeq(RUN_ID);
		store.append(RUN_ID, frame(type, seq));
		return seq;
	}

	private void appendRaw(RedisFrameStore store, Map<String, Object> frame) {
		store.append(RUN_ID, frame);
	}

	private static Map<String, Object> frame(String type, long seq) {
		return frame(type, seq, null, null);
	}

	private static Map<String, Object> frame(String type, long seq, String key, Object value) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		frame.put("atMs", 1_700_000_000_000L);
		if (key != null) {
			frame.put(key, value);
		}
		return frame;
	}

	private static List<Long> seqs(List<Map<String, Object>> frames) {
		List<Long> out = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

}
