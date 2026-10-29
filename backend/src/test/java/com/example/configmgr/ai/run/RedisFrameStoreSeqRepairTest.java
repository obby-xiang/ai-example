package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-1 号回退修复的<b>原子性</b>单测（资源/帧序号修复批）。
 *
 * <h2>缺陷形态</h2>
 * 修复路径的形状是"读归档末号 → 把 {@code ai:seq} 抬到 anchor → {@code INCR}"。旧实现用
 * {@code SET key anchor} 落抬号，而 {@code SET} 是<b>读-改-写里的无条件写</b>：
 * 读末号与落值之间只要有另一个线程取过号（{@code INCR} 已经把它交出去、甚至已经落档/发出），
 * 那次 {@code SET} 就把计数器<b>压回</b> anchor，随后的 {@code INCR} 便再次发放同一个号 ——
 * 订阅者收到两个相同 {@code seq} 的帧，前端 {@code lastSeq} 去重会静默丢一帧；
 * 归档侧 {@code mergeBySeq} 同号合并同样丢一帧（M-1 要防的正是这个）。
 *
 * <p>
 * 判据（本类只钉这一条）：<b>修复窗口里发放的号不得被第二次发放</b>。
 * 注入点 = {@link FakeFrameRedis#afterTailReadHook}（末号已读出、抬号尚未落值）；
 * 实现若继续用"无条件 SET"，本用例必红；实现若把抬号收敛成单条原子命令
 * （{@code INCRBY} 增量），窗口内那次 {@code INCR} 只会让最终号更高，永不复用。
 */
class RedisFrameStoreSeqRepairTest {

	private static final String RUN_ID = "run-seq-repair";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	/**
	 * <b>并发修复</b>：{@code ai:seq} 键丢失后<b>多线程同时</b>取号，任何线程都不得拿到
	 * "已落档过"的号。
	 *
	 * <p>这是"修复原子化"的真正判据。旧形状（读末号 → {@code SET} 抬号 → {@code INCR}）下，
	 * 只有"拿到 1 号"的那个线程会去修复，同一瞬间拿到 2..N 的线程直接返回 —— 而 2..N
	 * 正是归档里已经存在的号：订阅者会收到重复 {@code seq}，前端去重与 {@code mergeBySeq}
	 * 各丢一帧。把"读末号 + 抬号 + 取号"收进<b>一次原子执行</b>（生产 = Redis 脚本串行化）后，
	 * 并发调用被 Redis 串行化，后到者看到的是已抬高的计数器。
	 *
	 * <p>本用例不注入任何交错点：全部线程在同一栅栏后同时取号，判定只看结果
	 * （号唯一 + 全部大于归档末号）。慢实现（先返回低号、后修复）必然违反第二条。
	 */
	@Test
	void concurrentAllocationsOnAMissingSeqKeyNeverReuseArchivedNumbers() throws Exception {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		for (int i = 0; i < 5; i++) {
			nextAndAppend(store, "delta");
		}
		assertThat(store.lastSeq(RUN_ID)).as("归档末号 = 5").isEqualTo(5L);
		redis.dropCounter(store.seqKey(RUN_ID));

		int threads = 8;
		int perThread = 4;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.Set<Long> issued = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
		java.util.List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				pool.execute(() -> {
					try {
						start.await(5, java.util.concurrent.TimeUnit.SECONDS);
						for (int j = 0; j < perThread; j++) {
							issued.add(store.nextSeq(RUN_ID));
						}
					}
					catch (Throwable ex) {
						failures.add(ex);
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures).isEmpty();
		assertThat(issued).as("并发取号不得重号").hasSize(threads * perThread);
		assertThat(issued).as("修复必须原子：并发下不得把已落档的号（1..5）再发一次")
			.allMatch(seq -> seq > 5L);
	}

	/**
	 * 相邻路径：修复之后号仍<b>单调不回退</b>，且落的档按号连续可读（修复不是"跳一下再乱"）。
	 *
	 * <p>抬号取加法以后，最坏结果是号多跳几格（空号无害）；这里钉的是"跳完之后继续单调"。
	 */
	@Test
	void repairKeepsTheCounterMonotonicAfterTheKeyComesBack() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		for (int i = 0; i < 3; i++) {
			nextAndAppend(store, "delta");
		}
		redis.dropCounter(store.seqKey(RUN_ID));

		long repaired = store.nextSeq(RUN_ID);
		assertThat(repaired).as("键回来后的第一个号必须越过归档末号").isGreaterThan(3L);

		long previous = repaired;
		for (int i = 0; i < 5; i++) {
			long next = store.nextSeq(RUN_ID);
			assertThat(next).as("修复后号必须严格递增（不得回退/复用）").isGreaterThan(previous);
			previous = next;
		}
	}

	/**
	 * 相邻路径：Redis 取号抖动期间的<b>进程内降级号</b>同样不得被恢复后的 Redis 号复写。
	 *
	 * <p>降级号已经发给订阅者（也进了归档），恢复时的抬号锚点必须把它们算进去；
	 * 旧实现按 {@code max(Stream 末号, LIST 末号)} 抬号、漏掉降级号，恢复后的头两个号就会撞上它们。
	 */
	@Test
	void repairAfterADegradedWindowJumpsAboveTheFallbackNumbers() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		assertThat(nextAndAppend(store, "start")).isEqualTo(1L);
		redis.dropCounter(store.seqKey(RUN_ID));

		redis.failIncrement = true;
		assertThat(store.nextSeq(RUN_ID)).as("降级号 = 归档末号 + 1").isEqualTo(2L);
		assertThat(store.nextSeq(RUN_ID)).isEqualTo(3L);
		redis.failIncrement = false;

		assertThat(store.nextSeq(RUN_ID)).as("恢复后的号必须跳过降级期已发的 2/3").isEqualTo(4L);
	}

	// ── per-run 簿记的生命周期（终态 forget / 归档到期有界清理） ──────────────

	/**
	 * <b>终态回收</b>：{@link FrameStore#forget(String)} 释放该轮的进程内簿记，且<b>不动归档锚点</b> ——
	 * 回收后继续取号仍从归档末号之后开始（不会因"簿记没了"而回退），降级计数器也按归档末号重锚。
	 *
	 * <p>为什么需要它：{@code fallbackSeqs / observedPayloadBytes / 三个 warn 集}都是按 runId 无界增长的
	 * 进程内 Map，此前只有"进程重启"才释放 —— 长跑实例上每轮一条，永不回收。
	 */
	@Test
	void forgetReleasesPerRunStateWithoutRollingBackTheAnchor() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		nextAndAppend(store, "start");
		nextAndAppend(store, "delta");
		assertThat(store.trackedRuns()).as("两帧 ⇒ 该轮簿记存在").isEqualTo(1);

		store.forget(RUN_ID);

		assertThat(store.trackedRuns()).as("终态回收后该轮簿记已释放").isZero();
		assertThat(store.nextSeq(RUN_ID)).as("回收只清进程内簿记，取号仍锚在归档末号之后").isEqualTo(3L);

		redis.failIncrement = true;
		assertThat(store.nextSeq(RUN_ID)).as("降级号从归档末号重锚（不是从 1 重来）").isEqualTo(4L);
	}

	/**
	 * <b>到期清理</b>：簿记的保留时长 = 归档键的 session TTL（归档没了，簿记也就没有读者）。
	 * 时间由注入的时钟推进，因此"24h 之后"是确定性的，不靠睡眠。
	 */
	@Test
	void idlePerRunStateIsSweptAfterTheArchiveTtl() {
		FakeFrameRedis redis = new FakeFrameRedis();
		java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L);
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000, now::get);

		store.nextSeq("run-a");
		store.nextSeq("run-b");
		assertThat(store.trackedRuns()).as("两个活轮各一条簿记").isEqualTo(2);

		now.addAndGet(this.properties.getSessionTtl().toMillis() + RedisFrameStore.SWEEP_INTERVAL_MS + 1L);
		store.nextSeq("run-c");

		assertThat(store.trackedRuns()).as("归档到期 ⇒ 旧轮簿记被清掉（只剩当前这一轮）").isEqualTo(1);
	}

	/**
	 * <b>硬上限</b>：簿记总量恒 ≤ {@link RedisFrameStore#MAX_TRACKED_RUNS} —— 终态回收漏了一轮时，
	 * 也不能让"每轮一条"变成无界增长（按最后触达时刻从旧到新淘汰）。
	 *
	 * <p>可证性：淘汰只影响"进程内兜底"，取号锚点始终在 Redis（归档 + {@code ai:seq}）。
	 */
	@Test
	void perRunStateIsCappedAtTheHardLimit() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);

		int runs = RedisFrameStore.MAX_TRACKED_RUNS + 200;
		for (int i = 0; i < runs; i++) {
			store.nextSeq("run-cap-" + i);
		}

		assertThat(store.trackedRuns()).as("簿记总量不得随轮数无界增长")
			.isLessThanOrEqualTo(RedisFrameStore.MAX_TRACKED_RUNS);
	}

	private RedisFrameStore store(FakeFrameRedis redis) {
		return new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
	}

	private long nextAndAppend(RedisFrameStore store, String type) {
		long seq = store.nextSeq(RUN_ID);
		java.util.Map<String, Object> frame = new java.util.LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		frame.put("atMs", 1_700_000_000_000L);
		store.append(RUN_ID, frame);
		return seq;
	}

}
