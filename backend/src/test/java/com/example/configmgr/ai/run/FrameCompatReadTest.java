package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B23L1 批① · reattach 兼容期（M13/M14/M15）：回放源从 {@code ai:events:} LIST 切到
 * {@link FrameStore}，而旧 LIST 数据必须照旧可读、混合期必须拼得起来、孤儿过滤不得回归。
 *
 * <p>
 * 底座是 {@link RedisFrameStore} + {@link FakeFrameRedis}（真取号/真 XADD/真兼容双读），
 * 只把 Redis 换成进程内替身 —— 断言的是产品代码里的拼接与过滤逻辑。
 */
class FrameCompatReadTest {

	private static final String RUN_ID = "run-compat";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final FakeFrameRedis redis = new FakeFrameRedis();

	private final RedisFrameStore frames = new RedisFrameStore(this.redis.redis, MAPPER,
			new com.example.configmgr.ai.config.AiProperties(), 6000);

	private final SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), this.frames, MAPPER,
			Runnable::run, com.example.configmgr.ai.config.AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
			com.example.configmgr.ai.config.AiProperties.Frame.DEFAULT_MAX_BYTES, SseWriteBudget.direct(), null, null);

	/** M13：升级后新写已不进 LIST，但迁移前写进 LIST 的帧仍能完整回放。 */
	@Test
	void legacyListFramesRemainReplayable() {
		seedLegacy(frame("start", 1L), frame("suspended", 2L), frame("done", 3L));

		FrameWire wire = new FrameWire();
		int replayed = this.out.replayTo(wire.emitter(), null);

		assertThat(replayed).isEqualTo(3);
		assertThat(seqs(FrameWire.normalize(wire.frames()))).containsExactly(1L, 2L, 3L);
	}

	/** M14：混合期（LIST 1..2 + Stream 3..4）拼接正确，且差量 lastSeq 在拼接后的号空间上生效。 */
	@Test
	void mixedPeriodReplayHasNoGapOrDuplicate() {
		seedLegacy(frame("start", 1L), frame("delta", 2L));
		appendStream(frame("suspended", 3L));
		appendStream(frame("done", 4L));

		FrameWire full = new FrameWire();
		assertThat(this.out.replayTo(full.emitter(), null)).isEqualTo(4);
		assertThat(seqs(FrameWire.normalize(full.frames()))).containsExactly(1L, 2L, 3L, 4L);

		FrameWire diff = new FrameWire();
		assertThat(this.out.replayTo(diff.emitter(), 2L)).isEqualTo(2);
		assertThat(seqs(FrameWire.normalize(diff.frames()))).as("lastSeq=2 只补 3、4").containsExactly(3L, 4L);
	}

	/** M15：孤儿过滤不回归（lastSeq 落在末尾 ⇒ 不回放；落在中间 ⇒ 只补其后）。 */
	@Test
	void orphanFilterStillHolds() {
		seedLegacy(frame("start", 1L), frame("delta", 2L));
		appendStream(frame("done", 3L));

		FrameWire atTail = new FrameWire();
		assertThat(this.out.replayTo(atTail.emitter(), 3L)).isZero();
		assertThat(atTail.frames()).isEmpty();

		FrameWire middle = new FrameWire();
		assertThat(this.out.replayTo(middle.emitter(), 1L)).isEqualTo(2);
		assertThat(seqs(FrameWire.normalize(middle.frames()))).containsExactly(2L, 3L);
	}

	/** 兼容期的新帧序号接在旧 LIST 之后（锚定），不会与前端已有的 lastSeq 重叠。 */
	@Test
	void newFramesContinueAfterLegacyListSeq() {
		seedLegacy(frame("start", 1L), frame("suspended", 2L), frame("delta", 3L));

		this.out.delta("续跑正文");

		assertThat(this.frames.lastSeq(RUN_ID)).isEqualTo(4L);
		assertThat(seqs(this.frames.readFrom(RUN_ID, 3L, 0))).containsExactly(4L);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	@SafeVarargs
	private final void seedLegacy(Map<String, Object>... frames) {
		this.redis.seedList(this.frames.legacyListKey(RUN_ID), MAPPER, List.of(frames));
	}

	private void appendStream(Map<String, Object> frame) {
		this.frames.append(RUN_ID, frame);
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
