package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-09：运行活性锚点必须看见<b>新的落档口</b>。
 *
 * <p>
 * B23L1 批① 起业务帧只写 {@code ai:xevents:<runId>} Stream，旧 {@code ai:events:} LIST 进入
 * 只读兼容期。活动和僵尸判定若仍只读 LIST 尾部，跨实例看到的就是"这一轮已经很久没动" ——
 * 长工具 / 长流式轮会被误判成僵尸并接管（同一轮执行两遍）。
 *
 * <p>
 * 本用例锁两件事：
 * <ol>
 * <li>Stream 里的业务帧必须进入活动锚点；</li>
 * <li>旧 LIST 与 Stream 同时存在时取<b>较新者</b>，不信任"哪一边是当前写口"。</li>
 * </ol>
 */
class RunStoreActivityAnchorTest {

	private static final String RUN_ID = "run-anchor";

	private static final String LEGACY_KEY = RunStore.runScopedKey(RunStore.EVENTS_PREFIX, RUN_ID);

	private final FakeFrameRedis fake = new FakeFrameRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final RedisFrameStore frames = new RedisFrameStore(this.fake.redis, this.mapper, this.properties);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	/** 新写口（Stream）里的业务帧必须被业务活动锚点看见。 */
	@Test
	void streamFramesAreVisibleToTheActivityAnchor() {
		appendToStream("delta", 7L, 5_000L);

		assertThat(this.store.lastActivityAtMs(RUN_ID))
			.as("业务活动锚点仍只读已冻结的旧 LIST ⇒ 新业务帧不可见（R1-09）")
			.isEqualTo(5_000L);
	}

	/** 旧 LIST 与 Stream 混合：锚点取两者的最新时刻，不假设写口。 */
	@Test
	void mixedLegacyListAndStreamAnchorTakesNewest() {
		seedLegacyList("delta", 3L, 1_000L);
		appendToStream("delta", 7L, 5_000L);

		assertThat(this.store.lastActivityAtMs(RUN_ID))
			.as("Stream 更新时取 Stream 的时刻")
			.isEqualTo(5_000L);

		String otherRun = "run-anchor-legacy-newer";
		seedLegacyList(otherRun, "tool_start", 2L, 9_000L);
		appendToStream(otherRun, "delta", 4L, 5_000L);

		assertThat(this.store.lastActivityAtMs(otherRun))
			.as("兼容期 LIST 更新时取 LIST 的时刻（两侧取 max，而不是只认一个写口）")
			.isEqualTo(9_000L);
	}

	/** 心跳单独存在时只是"进程还活着"，不能推进业务活动锚点。 */
	@Test
	void heartbeatAloneIsNotBusinessActivity() {
		appendToStream("delta", 7L, 5_000L);
		this.store.touchActivity(RUN_ID);

		long beat = this.store.lastBeatAtMs(RUN_ID);
		assertThat(beat).as("心跳戳已刷新").isGreaterThan(5_000L);
		assertThat(this.store.lastActivityAtMs(RUN_ID))
			.as("业务活动锚点不得被心跳刷新（否则卡死的轮永远年轻）")
			.isEqualTo(5_000L);
		assertThat(this.store.lastEventAtMs(RUN_ID))
			.as("lastEventAtMs = 任何活动（业务或心跳）的较新者")
			.isEqualTo(beat);
	}

	/** 出帧点直接写锚点：不依赖事件日志是否落档成功。 */
	@Test
	void businessTouchAdvancesTheAnchorAndIsMonotonic() {
		this.store.touchBusinessActivity(RUN_ID, 7_000L);
		assertThat(this.store.lastActivityAtMs(RUN_ID)).isEqualTo(7_000L);

		this.store.touchBusinessActivity(RUN_ID, 6_000L);
		assertThat(this.store.lastActivityAtMs(RUN_ID))
			.as("后到的旧帧不得把活动锚点回拨")
			.isEqualTo(7_000L);

		assertThat(this.fake.expiry(this.store.activityKey(RUN_ID)))
			.as("活动锚点与会话同寿（滑动续期）")
			.isEqualTo(this.properties.getSessionTtl());
	}

	/** 坏值不能把锚点永久卡死：解析失败时按 0 处理并覆盖为本次 atMs。 */
	@Test
	void malformedAnchorValueIsOverwrittenInsteadOfBlockingFutureWrites() {
		FakeLeaseRedis redis = new FakeLeaseRedis();
		RunStore other = new RunStore(redis.redis, this.mapper, this.properties);
		redis.strings.put(other.activityKey(RUN_ID), "not-a-number");

		other.touchBusinessActivity(RUN_ID, 5_000L);

		assertThat(other.lastActivityAtMs(RUN_ID))
			.as("坏锚点必须被新值覆盖，而不是让该轮的活动锚点失效到 TTL 到期")
			.isEqualTo(5_000L);
	}

	/** 删轮与生产一致：TTL 型锚点（beat/activity）不随 13 键原子 DEL 删除。 */
	@Test
	void deletingARunKeepsTheTtlBasedActivityAnchors() {
		InMemoryRunStore memory = new InMemoryRunStore();
		memory.touchBusinessActivity(RUN_ID, 5_000L);
		memory.touchActivity(RUN_ID);

		memory.deleteRun(RUN_ID);

		assertThat(memory.lastActivityAtMs(RUN_ID))
			.as("ai:activity 与 ai:beat 一样按 TTL 自清，不在 deleteRun 的键族里")
			.isEqualTo(5_000L);
		assertThat(memory.lastBeatAtMs(RUN_ID)).isPositive();
	}

	/** 并发乱序写入不得让锚点回拨：原子 max 是唯一承诺。 */
	@Test
	void concurrentOutOfOrderTouchesNeverRollTheAnchorBack() throws Exception {
		int writers = 32;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(writers);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		for (int i = 1; i <= writers; i++) {
			long stamp = i * 1_000L;
			pool.submit(() -> {
				try {
					start.await();
					this.store.touchBusinessActivity(RUN_ID, stamp);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

		assertThat(this.store.lastActivityAtMs(RUN_ID))
			.as("并发/乱序写入后锚点必须停在最大值（读-比-写会回拨）")
			.isEqualTo(writers * 1_000L);
	}

	private void appendToStream(String type, long seq, long atMs) {
		appendToStream(RUN_ID, type, seq, atMs);
	}

	private void appendToStream(String runId, String type, long seq, long atMs) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		frame.put("atMs", atMs);
		this.frames.append(runId, frame);
	}

	private void seedLegacyList(String type, long seq, long atMs) {
		seedLegacyList(RUN_ID, type, seq, atMs);
	}

	private void seedLegacyList(String runId, String type, long seq, long atMs) {
		try {
			this.fake.seedList(RunStore.runScopedKey(RunStore.EVENTS_PREFIX, runId), this.mapper,
					java.util.List.of(Map.of("type", type, "seq", seq, "atMs", atMs)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
