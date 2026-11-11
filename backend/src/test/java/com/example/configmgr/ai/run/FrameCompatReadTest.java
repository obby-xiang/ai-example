package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrameStore 的<b>兼容读与分页契约</b>（B23L1 批① 之后的现行口径）。
 *
 * <p>
 * 旧 {@code ai:events:} LIST 兼容期已退场：现行契约由三块组成 ——
 * <ul>
 * <li><b>兼容实现</b>只实现 {@link FrameStore#readFrom} / {@link FrameStore#lastSeq}；原文读口
 * （{@code readRawFrom} / {@code readRawAfterEntry}）默认不可用，调用方回落 {@code readFrom}
 * 并按“语义等价”处理；</li>
 * <li><b>生产实现</b>（{@link RedisFrameStore}）提供物理 entry 游标分页
 * {@link FrameStore#readRawAfterEntry} 与廉价元数据探测 {@link FrameStore#replayMeta}；</li>
 * <li><b>失败语义</b>：读失败必须显式 {@code degraded}，空页/空帧不得被当成“确实没有帧”。</li>
 * </ul>
 *
 * <p>
 * 本文件用 {@link RedisFrameStore} + {@link FakeFrameRedis}（真取号/真 XADD/真 XRANGE）与
 * {@link InMemoryFrameStore}（仅 readFrom 的兼容实现）钉住：帧序、差量边界、物理分页无重无漏、
 * 非法游标与读失败的 degraded 口径。
 */
class FrameCompatReadTest {

	private static final String RUN_ID = "run-compat";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeFrameRedis redis = new FakeFrameRedis();

	private final RedisFrameStore frames = new RedisFrameStore(this.redis.redis, MAPPER, this.properties, 6000);

	/** 帧键与进程内簿记都带记录身份；每个用例在固定租户/用户作用域内执行。 */
	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void openIdentity() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void closeIdentity() {
		this.identity.close();
	}

	/** 仅实现 readFrom/lastSeq 的兼容替身：原文读口默认不可用，调用方必须能回落。 */
	@Test
	void compatImplementationFallsBackToReadFromAndKeepsFrameOrder() {
		InMemoryFrameStore compat = new InMemoryFrameStore();
		compat.seed(RUN_ID, frame("start", 1L), frame("delta", 2L), frame("done", 3L));

		assertThat(compat.readRawFrom(RUN_ID, 0L, 10)).as("兼容实现的原文读口默认不可用").isEmpty();
		assertThat(compat.readRawAfterEntry(RUN_ID, "", 10)).as("兼容实现没有物理 entry 游标").isNull();
		assertThat(seqs(compat.readFrom(RUN_ID, 0L, 0))).containsExactly(1L, 2L, 3L);
		FrameStore.ReadResult status = compat.readFromWithStatus(RUN_ID, 1L, 0);
		assertThat(status.degraded()).as("兼容实现不得谎报降级").isFalse();
		assertThat(seqs(status.frames())).containsExactly(2L, 3L);
	}

	/** 差量读：严格 seq &gt; lastSeq、升序、limit 只截尾，不重排。 */
	@Test
	void differenceReadKeepsFrameOrderAndHonoursLimit() {
		seedStream(1L, 2L, 3L, 4L, 5L);

		assertThat(seqs(this.frames.readFrom(RUN_ID, 0L, 0))).containsExactly(1L, 2L, 3L, 4L, 5L);
		assertThat(seqs(this.frames.readFrom(RUN_ID, 2L, 0))).containsExactly(3L, 4L, 5L);
		assertThat(seqs(this.frames.readFrom(RUN_ID, 2L, 2))).as("limit 只截尾").containsExactly(3L, 4L);
		assertThat(this.frames.readFrom(RUN_ID, 5L, 0)).as("末尾之后为空").isEmpty();
	}

	/** 物理 entry 游标分页：逐页推进走完全部帧，无重复、无漏帧、entry ID 严格递增。 */
	@Test
	void physicalEntryPaginationWalksEveryFrameOnce() {
		seedStream(1L, 2L, 3L, 4L, 5L);

		List<String> entryIds = new ArrayList<>();
		List<Long> walked = new ArrayList<>();
		String cursor = "";
		for (int page = 0; page < 10; page++) {
			FrameStore.RawEntryPage result = this.frames.readRawAfterEntry(RUN_ID, cursor, 2);
			assertThat(result).as("生产实现必须支持物理 entry 分页").isNotNull();
			assertThat(result.degraded()).as("正常分页不得降级").isFalse();
			if (result.frames().isEmpty()) {
				break;
			}
			for (FrameStore.RawEntryFrame frame : result.frames()) {
				entryIds.add(frame.entryId());
				walked.add(frame.seq());
			}
			cursor = result.frames().get(result.frames().size() - 1).entryId();
		}

		assertThat(walked).as("逐页拼接 = 全量且保序").containsExactly(1L, 2L, 3L, 4L, 5L);
		assertThat(entryIds).as("entry ID 严格递增且不重复").isSorted().doesNotHaveDuplicates();
		assertThat(this.frames.lastSeq(RUN_ID)).isEqualTo(5L);
	}

	/** 非法物理游标：失败的显式信号 = degraded，空页不得被当成归档末尾。 */
	@Test
	void invalidPhysicalCursorFailsClosedAsDegraded() {
		seedStream(1L, 2L);

		FrameStore.RawEntryPage result = this.frames.readRawAfterEntry(RUN_ID, "bad-cursor", 1);

		assertThat(result.frames()).isEmpty();
		assertThat(result.degraded()).as("非法游标必须显式 degraded（调用方据此重试用完整游标）").isTrue();
	}

	/** XRANGE 读失败：readFrom 仍不抛异常，但 withStatus 必须把 degraded 交给调用方。 */
	@Test
	void rangeReadFailureFailsClosedAsDegraded() {
		seedStream(1L, 2L, 3L, 4L, 5L);
		RedisFrameStore reader = new RedisFrameStore(this.redis.redis, MAPPER, this.properties, 6000);
		long failuresBefore = com.example.configmgr.ai.observe.RedisDegradation
			.count(com.example.configmgr.ai.observe.RedisDegradation.A3_FRAME_RANGE_READ);
		this.redis.failRangeRead = true;
		try {
			// 差量读走 XRANGE：不抛给回放链，但“读失败”必须计数留痕（A3），不得只表现为空差量
			FrameStore.ReadResult status = reader.readFromWithStatus(RUN_ID, 2L, 0);
			assertThat(status.frames()).isEmpty();
			assertThat(com.example.configmgr.ai.observe.RedisDegradation
				.count(com.example.configmgr.ai.observe.RedisDegradation.A3_FRAME_RANGE_READ))
				.as("读失败必须计入 A3 降级台账（可观测，不被伪装成空流）")
				.isGreaterThan(failuresBefore);
		}
		finally {
			this.redis.failRangeRead = false;
		}
	}

	/** 末号探测失败：replayMeta 必须报 degraded，而不是给出“无新帧”的假结论。 */
	@Test
	void tailProbeFailureMakesReplayMetaDegraded() {
		seedStream(1L, 2L);
		RedisFrameStore reader = new RedisFrameStore(this.redis.redis, MAPPER, this.properties, 6000);
		this.redis.failTailRead = true;
		try {
			FrameStore.ReadResult meta = reader.replayMeta(RUN_ID, 0L);
			assertThat(meta.frames()).as("元数据探测不读正文").isEmpty();
			assertThat(meta.degraded()).as("末号不可读 ⇒ 元数据降级").isTrue();
		}
		finally {
			this.redis.failTailRead = false;
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedStream(long... seqs) {
		for (long seq : seqs) {
			this.frames.append(RUN_ID, frame(seq == 1L ? "start" : seq == seqs.length ? "done" : "delta", seq));
		}
	}

	private static Map<String, Object> frame(String type, long seq) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		frame.put("atMs", 1_700_000_000_000L);
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
