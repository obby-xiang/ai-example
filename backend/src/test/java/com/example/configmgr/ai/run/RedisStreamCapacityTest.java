package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 事件日志的<b>按字节容量治理</b>（Redis 容量专项 A）。
 *
 * <h2>问题</h2>
 * {@code ai:xevents} 此前只有条数预算（{@code MAXLEN ~ 6000}）：单帧上限 64KB ⇒ 单轮最坏
 * 6000 × 64KB ≈ 384MB；{@code noeviction} 下触顶会让 <b>所有</b> AI 写入失败（连同别的 run 与
 * 会话记忆）。Redis 5.0 / Cluster 都没有"按字节裁剪 Stream"（{@code XTRIM} 只按条数/ID），
 * 真按字节治理需要额外协议（{@code MEMORY USAGE} 采样 + 动态裁剪），本批不引入。
 *
 * <h2>本批的保守等效口径</h2>
 * {@code 有效窗口 = clamp(byte-budget / (本轮观察到的最大帧载荷 + 每条约 256B 开销), 下限 256, 条数窗口 6000)}
 * —— 每一条落档记录都 ≤ 观察到的最大载荷 + 开销，故"窗口 × 每条上界 ≤ 预算"可证；
 * 最大载荷取<b>本轮观察到的</b>（不是配置的 64KB 上界），因此小帧的正常轮次仍保留 6000 条窗口
 * （既有回放深度不变），只有真出现大帧的轮次才收窄。
 *
 * <h2>可观测（降级计数 + WARN）</h2>
 * <ul>
 * <li>{@code c1.streamByteCap}：字节预算成为实际约束（窗口从条数窗口收窄）；</li>
 * <li>{@code c1.streamByteFloor}：预算连下限窗口都撑不住（帧大于"预算/下限"）——保守界不可兑现，
 * 必须显式可见而不是静默超预算；</li>
 * <li>{@code c1.streamReplayGap}：读侧 {@code afterSeq} 落在被裁掉的区间（本次回放跳过了一段）。</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisStreamCapacityTest {

	private static final String RUN_ID = "run-capacity";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 与 {@link RedisFrameStore#ENTRY_OVERHEAD_BYTES} 同口径（断言里的算式显式写出来）。 */
	private static final long OVERHEAD = 256L;

	private final AiProperties properties = new AiProperties();

	// ── 预算边界（纯函数口径） ────────────────────────────────────────────────

	/**
	 * 边界 = 预算 ÷ (载荷 + 开销) 的整除点：多 1 字节就少一档。
	 *
	 * <p>kill：把 {@code windowForPayload} 改成"... + 1"或忽略开销 ⇒ 本用例的 1000/997 变红。
	 */
	@Test
	void windowIsBudgetDividedByPerEntryUpperBound() {
		this.properties.getStream().setByteBudget(400_000L);
		RedisFrameStore store = store(6000);

		assertThat(store.windowForPayload(144L)).as("(144 + 256) = 400 ⇒ 400000 / 400 = 1000").isEqualTo(1000);
		assertThat(store.windowForPayload(145L)).as("多 1 字节载荷就少一档（保守按每帧上界算）").isEqualTo(997);
	}

	/** 小帧不放大条数窗口；大帧收窄；超出预算只能保住下限窗口。 */
	@Test
	void windowIsClampedToTheCountWindowAndToTheFloor() {
		RedisFrameStore wide = store(6000);
		assertThat(wide.windowForPayload(100L)).as("默认预算 64MiB 下小帧仍保留条数窗口 6000")
			.isEqualTo(RedisFrameStore.STREAM_WINDOW);

		this.properties.getStream().setByteBudget(400_000L);
		RedisFrameStore tiny = store(6000);
		assertThat(tiny.windowForPayload(400_000L)).as("预算撑不住一帧 ⇒ 落到下限窗口（并计数，见下）")
			.isEqualTo(RedisFrameStore.MIN_WINDOW);

		RedisFrameStore smallCountWindow = store(8);
		assertThat(smallCountWindow.windowForPayload(400_000L)).as("下限不得把条数窗口放大（8 就是 8）")
			.isEqualTo(8);
	}

	/** {@code byte-budget = 0} = 关闭字节治理：只按条数窗口（显式逃生门，不静默变成"没有上限"）。 */
	@Test
	void zeroBudgetDisablesTheByteGovernance() {
		this.properties.getStream().setByteBudget(0L);
		RedisFrameStore store = store(6000);

		assertThat(store.windowForPayload(4L * 1024 * 1024)).isEqualTo(RedisFrameStore.STREAM_WINDOW);
		assertThat(store.windowForPayload(0L)).isEqualTo(RedisFrameStore.STREAM_WINDOW);
	}

	// ── 超限行为可观测 + 活跃 run 不误清 ─────────────────────────────────────

	/**
	 * 字节预算成为实际约束时：流被裁到"预算/每条上界"，<b>最新帧与号空间都保留</b>（活跃轮不被清空），
	 * 且降级计数 + WARN（每轮一次）可见。
	 */
	@Test
	void byteCapTrimsTheStreamButKeepsTheNewestFramesAndTheSeqSpace(CapturedOutput output) {
		String text = "x".repeat(400);
		long payload = payloadBytes(1500L, text);
		// 让"预算/每条上界"恰好落在 1000 附近且远小于条数窗口 2000
		this.properties.getStream().setByteBudget(1000L * (payload + OVERHEAD));
		RedisFrameStore store = store(2000);
		int expectedWindow = store.windowForPayload(payload);
		assertThat(expectedWindow).as("前置：字节预算确实比条数窗口更紧").isLessThan(2000);

		long before = RedisDegradation.count(RedisDegradation.C1_STREAM_BYTE_CAP);
		for (int i = 1; i <= 1500; i++) {
			nextAndAppend(store, text);
		}

		assertThat(this.fake.stream(store.streamKey(RUN_ID))).as("流被裁到字节预算推导出的窗口")
			.hasSize(expectedWindow);
		assertThat(store.lastSeq(RUN_ID)).as("裁剪不影响号空间（下一个号继续往前）").isEqualTo(1500L);
		List<Map<String, Object>> replayed = store.readFrom(RUN_ID, 0L, 0);
		assertThat(replayed).as("回放拿到的是最新窗口，末帧不丢").hasSize(expectedWindow);
		assertThat(((Number) replayed.get(replayed.size() - 1).get("seq")).longValue()).isEqualTo(1500L);
		assertThat(RedisDegradation.count(RedisDegradation.C1_STREAM_BYTE_CAP))
			.as("每次落档只要被字节预算约束就必须计数").isEqualTo(before + 1500);
		assertThat(occurrences(output.getAll(), "事件日志按字节预算裁剪"))
			.as("日志每轮只吼一次（计数负责量级，日志只负责'有这回事'）").isEqualTo(1);
	}

	/**
	 * 预算连下限窗口都撑不住（单帧 &gt; 预算/下限）：窗口落到下限（保证还有可回放窗口），
	 * 但<b>必须显式可观测</b> —— 否则"保守界"就成了静默超预算。
	 */
	@Test
	void budgetBelowTheFloorWindowIsReportedInsteadOfSilentlyIgnored(CapturedOutput output) {
		this.properties.getStream().setByteBudget(1_000L);
		RedisFrameStore store = store(6000);
		Map<String, Object> huge = frame("delta", 1L, "text", "y".repeat(100_000));
		long before = RedisDegradation.count(RedisDegradation.C1_STREAM_BYTE_FLOOR);

		assertThatCode(() -> store.append(RUN_ID, huge)).doesNotThrowAnyException();

		assertThat(store.windowForPayload(100_000L)).as("下限窗口兜住可回放性").isEqualTo(RedisFrameStore.MIN_WINDOW);
		assertThat(this.fake.stream(store.streamKey(RUN_ID))).as("帧本身不丢（裁剪不裁掉仅有的那条）").hasSize(1);
		assertThat(RedisDegradation.count(RedisDegradation.C1_STREAM_BYTE_FLOOR))
			.as("撑不住预算必须计数（运维据 /health 调整 byte-budget 或 max-frame-bytes）").isGreaterThan(before);
		assertThat(output.getAll()).contains("预算不足以下限窗口").contains(RUN_ID);
	}

	/** 读侧：{@code afterSeq} 落在被裁掉的区间 ⇒ 本次回放确实跳过了一段，必须计数（不是"没有新帧"）。 */
	@Test
	void replayBelowTheSurvivingWindowIsMarkedAsAGap() {
		String text = "z".repeat(400);
		long payload = payloadBytes(1500L, text);
		this.properties.getStream().setByteBudget(1000L * (payload + OVERHEAD));
		RedisFrameStore store = store(2000);
		int window = store.windowForPayload(payload);
		for (int i = 1; i <= 1500; i++) {
			nextAndAppend(store, text);
		}
		long firstSurviving = 1500L - window + 1L;

		long before = RedisDegradation.count(RedisDegradation.C1_STREAM_REPLAY_GAP);
		assertThat(seqs(store.readFrom(RUN_ID, firstSurviving - 2L, 0))).as("落后读者拿到幸存窗口")
			.first().isEqualTo(firstSurviving);
		assertThat(RedisDegradation.count(RedisDegradation.C1_STREAM_REPLAY_GAP))
			.as("回放跳过了一段就必须计数（不许把缺口当'没有新帧'）").isEqualTo(before + 1);

		assertThat(seqs(store.readFrom(RUN_ID, firstSurviving, 0))).hasSize(window - 1);
		assertThat(RedisDegradation.count(RedisDegradation.C1_STREAM_REPLAY_GAP))
			.as("没有跳过就不计数（计数含义 = 本次回放有缺口）").isEqualTo(before + 1);
	}

	/**
	 * Redis 5.0 / 近似裁剪语义不破：{@code MAXLEN ~} 仍<b>近似</b>（可多留几条），
	 * 读侧仍用<b>闭区间</b> {@code <from+1>-0}（替身按 5.0 拒绝排他下限）。
	 */
	@Test
	void approximateTrimAndInclusiveReadRangeArePreserved() {
		FakeFrameRedis redis = new FakeFrameRedis();
		redis.retainExtra = 3;
		String text = "w".repeat(400);
		long payload = payloadBytes(1500L, text);
		this.properties.getStream().setByteBudget(1000L * (payload + OVERHEAD));
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 2000);
		int window = store.windowForPayload(payload);
		for (int i = 1; i <= 1500; i++) {
			nextAndAppend(store, text);
		}

		assertThat(redis.stream(store.streamKey(RUN_ID))).as("MAXLEN ~ 仍近似（窗口 + 冗余条）")
			.hasSize(window + 3);
		List<Long> replayed = seqs(store.readFrom(RUN_ID, 0L, 0));
		assertThat(replayed).as("读侧夹到同一个窗口，且 5.0 闭区间可读（排他下限会被替身拒绝）")
			.hasSize(window).first().isEqualTo(1500L - window + 1L);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private FakeFrameRedis fake;

	private RedisFrameStore store(int countWindow) {
		this.fake = new FakeFrameRedis();
		return new RedisFrameStore(this.fake.redis, MAPPER, this.properties, countWindow);
	}

	private long nextAndAppend(RedisFrameStore store, String text) {
		long seq = store.nextSeq(RUN_ID);
		store.append(RUN_ID, frame("delta", seq, "text", text));
		return seq;
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

	/** 落档载荷的字节数（{@code RedisFrameStore} 写进 Stream 的正是这份 JSON）。 */
	private static long payloadBytes(long seq, String text) {
		try {
			return MAPPER.writeValueAsBytes(frame("delta", seq, "text", text)).length;
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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

	private static int occurrences(String haystack, String needle) {
		int count = 0;
		int index = haystack.indexOf(needle);
		while (index >= 0) {
			count++;
			index = haystack.indexOf(needle, index + needle.length());
		}
		return count;
	}

}
