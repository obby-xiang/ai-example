package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tool.TruncationContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 截断专项：<b>assistant 正文/思考增量超单帧上限时拆成多条既有 {@code delta} 帧</b>，
 * 而不是把正文永久截掉。
 *
 * <h2>判据</h2>
 * <ol>
 * <li>不丢字：所有分片按到达顺序拼接 == 原文（逐 code unit 相等，含 CJK / emoji）；</li>
 * <li>每帧仍满足 {@code maxFrameBytes}（UTF-8 字节；判定口径含 JSON 转义与 seq 等全部字段）；</li>
 * <li>不新增帧型、不改键集合：仍是 {@code delta}（正文无 {@code kind}，思考 {@code kind=reasoning}），
 *     同一 {@code messageId}，<b>不挂</b>截断标记（{@code truncated}/{@code noticeCode} 等）；</li>
 * <li>{@code seq} 连续、每片独立占号；{@code lastSeq} 落在拆分边界时差量回放只补剩余分片；</li>
 * <li>实时投递原文与归档回放原文<b>逐字一致</b>（同一帧两条通道不分叉）；</li>
 * <li>{@code message_end.chars} == 分片文本长度之和（与实际拼接语义一致）；</li>
 * <li>分片在到达顺序上<b>连续</b>（并发出帧也不得插入分片之间）；思考段的 start/delta/end 在并发下
 *     仍成对闭合、{@code chars} 与本段 delta 之和相等（I4 的锁内口径）。</li>
 * </ol>
 *
 * <p>失败面：上限小到连帧骨架/一个码点都放不下时，拆分<b>抛 {@code IllegalStateException}</b>
 * （带 type/key/limit/originalChars），绝不回落字段截断或返回空串/超限片 —— 见
 * {@link DeltaFrameSplitLimitGuardTest}。
 *
 * <p>反例防线：把任一分片重新按旧逻辑截断 ⇒ 拼接 != 原文（判据 1 红）；只在拆分点少发/多发一片
 * ⇒ seq 洞或顺序断（判据 4 红）；对帧 JSON 做字节级切割 ⇒ 反序列化失败（回放读路径会静默丢帧）。
 */
class DeltaFrameSplitTest {

	private static final String RUN_ID = "run-delta-split";

	private static final String MESSAGE_ID = "assistant-" + RUN_ID;

	private static final int LIMIT = 4096;   // 用例自带小上限，等价于生产的 64KB 判定逻辑

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.mapper, Runnable::run, 256, LIMIT);

	// ── ① 大分片：拆成多条 delta，不丢字、不挂截断标记、每帧 ≤ 上限 ──

	@Test
	void oversizeDeltaIsSplitIntoContiguousDeltasWithoutLoss() {
		String huge = "y".repeat(20_000);
		FrameWire wire = FrameWire.attachTo(this.out);

		this.out.delta(huge);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).as("超限正文必须拆成多条既有 delta 帧").hasSizeGreaterThan(1);
		assertThat(frames).allSatisfy(frame -> assertThat(frame)
				.containsEntry("type", "delta")
				.containsEntry("messageId", MESSAGE_ID)
				.doesNotContainKey("kind")
				.doesNotContainKey(TruncationContract.TRUNCATED_FIELD)
				.doesNotContainKey(TruncationContract.NOTICE_CODE_FIELD));
		assertThat(wire.payloads())
				.as("每帧（含 JSON 转义与全部字段）≤ 单帧上限")
				.allSatisfy(payload -> assertThat(payload.getBytes(StandardCharsets.UTF_8).length)
						.isLessThanOrEqualTo(LIMIT));
		assertThat(concatTexts(frames)).as("实时投递不丢字、保序").isEqualTo(huge);
		assertThat(this.store.events(RUN_ID)).as("归档与实时一致：同数量的 delta 帧、原文不丢")
				.hasSize(frames.size());
		assertThat(concatTexts(this.store.events(RUN_ID))).isEqualTo(huge);
	}

	// ── ② CJK / emoji / 代理对不能被切成半个代理项 ──

	@Test
	void splitKeepsCjkAndSurrogatePairsIntact() throws Exception {
		String original = "汉字😀🧪".repeat(3_000);   // 每个"字组" 6 个 code unit，UTF-8 16 字节
		assertThat(original.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(LIMIT * 4);

		FrameWire wire = FrameWire.attachTo(this.out);
		this.out.delta(original);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).hasSizeGreaterThan(1);
		for (Map<String, Object> frame : frames) {
			String chunk = (String) frame.get("text");
			assertThat(chunk).as("分片不得为空").isNotEmpty();
			assertThat(Character.isLowSurrogate(chunk.charAt(0))).as("分片不得以低代理项开头").isFalse();
			assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)))
					.as("分片不得以高代理项结尾（半个 emoji）").isFalse();
			assertThat(this.mapper.writeValueAsBytes(frame).length).isLessThanOrEqualTo(LIMIT);
		}
		assertThat(concatTexts(frames)).isEqualTo(original);

		// 回放通道内容一致（非 BMP 字符在 Jackson 的 UTF-8/Writer 两个生成器上转义形式不同，
		// 属既有行为；这里断言解码后的帧与文本逐字一致、分片边界一致）。
		FrameWire replay = new FrameWire();
		this.out.replayTo(replay.emitter(), null);
		assertThat(replay.frames()).extracting(frame -> frame.get("seq"))
				.containsExactlyElementsOf(frames.stream().map(frame -> frame.get("seq")).toList());
		assertThat(concatTexts(replay.frames())).isEqualTo(original);
	}

	// ── ③ JSON 转义后的字节上限：引号/反斜杠/控制字符按转义后口径算 ──

	@Test
	void splitRespectsJsonEscapedByteBudget() throws Exception {
		// 每个单元 JSON 转义后：`"`→2、`\`→2、换行→2、\u0001→6，再加 3 个 ASCII ⇒ 远大于原字符数
		String original = "\"\\\n\u0001abc".repeat(2_000);
		assertThat(original.length()).isLessThan(20_000);

		FrameWire wire = FrameWire.attachTo(this.out);
		this.out.delta(original);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).hasSizeGreaterThan(1);
		for (Map<String, Object> frame : frames) {
			assertThat(this.mapper.writeValueAsBytes(frame).length)
					.as("判据必须是转义后的字节数，不是字符数")
					.isLessThanOrEqualTo(LIMIT);
		}
		assertThat(concatTexts(frames)).isEqualTo(original);
		for (String payload : wire.payloads()) {
			assertThat(this.mapper.readValue(payload, Map.class)).containsEntry("type", "delta");
		}
	}

	// ── ④ seq 连续 + lastSeq 落在拆分边界时只补剩余分片 ──

	@Test
	void replayFromSeqAtSplitBoundaryResumesExactly() {
		String original = "0123456789".repeat(2_000);
		FrameWire live = FrameWire.attachTo(this.out);
		this.out.delta(original);

		List<Map<String, Object>> liveFrames = live.frames();
		int total = liveFrames.size();
		assertThat(total).isGreaterThan(2);
		List<Long> seqs = new ArrayList<>();
		for (Map<String, Object> frame : liveFrames) {
			seqs.add(((Number) frame.get("seq")).longValue());
		}
		assertThat(seqs).as("每片独立占号且连续").containsExactlyElementsOf(expectedSeqs(total));

		int boundary = 2;   // 前端已收到前 2 片
		FrameWire replay = new FrameWire();
		int staged = this.out.replayTo(replay.emitter(), seqs.get(boundary - 1));

		assertThat(staged).isEqualTo(total - boundary);
		assertThat(replay.frames()).extracting(frame -> ((Number) frame.get("seq")).longValue())
				.containsExactlyElementsOf(seqs.subList(boundary, total));
		String expectedRest = String.join("", liveFrames.subList(boundary, total).stream()
				.map(frame -> (String) frame.get("text")).toList());
		assertThat(concatTexts(replay.frames())).as("边界之差 = 剩余分片，无重复无缺口").isEqualTo(expectedRest);
	}

	// ── ⑤ 实时 vs 回放逐字一致（BMP/ASCII：序列化器两条通道逐字节相同） ──

	@Test
	void realtimeAndReplayPayloadsAreIdentical() {
		String original = "重现汉字".repeat(3_000);
		FrameWire live = FrameWire.attachTo(this.out);
		this.out.delta(original);
		this.out.textStart();
		this.out.delta("尾巴");

		FrameWire replay = new FrameWire();
		this.out.replayTo(replay.emitter(), null);

		assertThat(replay.payloads()).as("回放原文与实时投递原文逐字一致（含 seq/atMs/分片边界）")
				.containsExactlyElementsOf(live.payloads());
	}

	// ── ⑥ message_end.chars 与实际拼接语义一致 ──

	@Test
	void messageEndCharsMatchesConcatenatedDeltas() {
		String original = "字符😀".repeat(4_000);   // 4 code unit ×4000 = 16000 > LIMIT
		FrameWire wire = FrameWire.attachTo(this.out);
		this.out.textStart();
		this.out.delta(original);
		// 与 ResilientChatService 同口径：chars = 累计器正文长度（= 原文长度）
		this.out.textEnd(original.length());

		List<Map<String, Object>> frames = wire.frames();
		List<Map<String, Object>> deltas = frames.stream().filter(frame -> "delta".equals(frame.get("type"))).toList();
		assertThat(deltas).hasSizeGreaterThan(1);
		long concatenated = deltas.stream().mapToLong(frame -> ((String) frame.get("text")).length()).sum();
		Map<String, Object> end = frames.stream().filter(frame -> "message_end".equals(frame.get("type")))
				.findFirst().orElseThrow();
		assertThat(((Number) end.get("chars")).longValue())
				.as("message_end.chars 必须等于分片文本长度之和（此前超限丢字时二者分家）")
				.isEqualTo(concatenated)
				.isEqualTo(original.length());
		assertThat(String.join("", deltas.stream().map(frame -> (String) frame.get("text")).toList()))
				.isEqualTo(original);
	}

	// ── ⑦ 思考段同样拆分：kind 保留、message_start 只开一次、chars 数原文 ──

	@Test
	void reasoningDeltaIsSplitAndStillCountsOriginalChars() {
		String original = "先想😀一步".repeat(2_000);
		FrameWire wire = FrameWire.attachTo(this.out);
		this.out.reasoningDelta(original);
		this.out.reasoningEnd();

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).extracting(frame -> frame.get("type"))
				.containsExactlyElementsOf(expectedTypes(frames.size()));
		assertThat(frames.stream().filter(frame -> "message_start".equals(frame.get("type"))).count())
				.as("思考段 message_start 只发一次").isEqualTo(1);
		List<Map<String, Object>> deltas = frames.stream().filter(frame -> "delta".equals(frame.get("type"))).toList();
		assertThat(deltas).hasSizeGreaterThan(1);
		assertThat(deltas).allSatisfy(frame -> assertThat(frame)
				.containsEntry("kind", "reasoning")
				.containsEntry("messageId", MESSAGE_ID)
				.doesNotContainKey(TruncationContract.TRUNCATED_FIELD));
		assertThat(wire.payloads()).allSatisfy(payload -> assertThat(payload.getBytes(StandardCharsets.UTF_8).length)
				.isLessThanOrEqualTo(LIMIT));
		assertThat(String.join("", deltas.stream().map(frame -> (String) frame.get("text")).toList()))
				.isEqualTo(original);
		Map<String, Object> end = frames.stream().filter(frame -> "message_end".equals(frame.get("type")))
				.findFirst().orElseThrow();
		assertThat(end).containsEntry("kind", "reasoning");
		assertThat(((Number) end.get("chars")).longValue()).as("思考段 chars = 原文长度，不是分片条数")
				.isEqualTo(original.length());
	}

	// ── ⑧ lastSeq 落在最后一片 / lastSeq=null 全量回放 ──

	@Test
	void replayFromLastChunkSeqStagesNothingAndNullReplaysEverything() {
		String original = "0123456789".repeat(2_000);
		FrameWire live = FrameWire.attachTo(this.out);
		this.out.delta(original);

		List<Map<String, Object>> liveFrames = live.frames();
		assertThat(liveFrames).hasSizeGreaterThan(1);
		long lastSeq = ((Number) liveFrames.get(liveFrames.size() - 1).get("seq")).longValue();

		FrameWire tail = new FrameWire();
		assertThat(this.out.replayTo(tail.emitter(), lastSeq)).as("前端已收到全部分片 ⇒ 差量必须为空").isZero();
		assertThat(tail.payloads()).isEmpty();

		FrameWire full = new FrameWire();
		assertThat(this.out.replayTo(full.emitter(), null)).as("lastSeq=null = 全量回放").isEqualTo(liveFrames.size());
		assertThat(full.payloads()).as("全量回放与实时投递逐字一致").containsExactlyElementsOf(live.payloads());
	}

	// ── ⑨ 分片之间不得插入任何其它帧（并发出帧下的连续性） ──

	@Test
	void splitChunksAreContiguousEvenWhenOtherFramesAreEmittedConcurrently() throws Exception {
		SseChatEmitter emitter = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), this.mapper, Runnable::run, 256,
				LIMIT);
		FrameWire wire = FrameWire.attachTo(emitter);
		String original = "帧".repeat(10_000);   // 30k 字节 ⇒ 多片
		CountDownLatch finished = new CountDownLatch(1);
		Thread noise = new Thread(() -> {
			while (finished.getCount() > 0) {
				emitter.heartbeat(Map.of("tick", 1));
				emitter.delta("干扰");
			}
		}, "frame-noise");
		noise.setDaemon(true);
		noise.start();
		try {
			emitter.delta(original);
		}
		finally {
			finished.countDown();
			noise.join(5_000);
		}

		List<Map<String, Object>> frames = wire.frames();
		List<Integer> chunkIndexes = new ArrayList<>();
		List<String> chunkTexts = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			Map<String, Object> frame = frames.get(i);
			if (!"delta".equals(frame.get("type"))) {
				continue;
			}
			String text = (String) frame.get("text");
			if ("干扰".equals(text)) {
				continue;
			}
			chunkIndexes.add(i);
			chunkTexts.add(text);
		}
		assertThat(chunkTexts).hasSizeGreaterThan(1);
		for (int i = 1; i < chunkIndexes.size(); i++) {
			assertThat(chunkIndexes.get(i)).as("分片之间不得插入其它帧").isEqualTo(chunkIndexes.get(i - 1) + 1);
		}
		assertThat(String.join("", chunkTexts)).as("并发干扰下拼接仍等于原文").isEqualTo(original);
	}

	// ── ⑩ 思考段边界与 chars 在并发出帧下仍自洽（I4） ──

	@Test
	void concurrentReasoningDeltasKeepSegmentBoundariesAndChars() throws Exception {
		for (int round = 0; round < 6; round++) {
			SseChatEmitter emitter = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), this.mapper, Runnable::run,
					256, LIMIT);
			FrameWire wire = FrameWire.attachTo(emitter);
			AtomicLong expectedChars = new AtomicLong();
			ExecutorService pool = Executors.newFixedThreadPool(4);
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < 4; t++) {
				int id = t;
				futures.add(pool.submit(() -> {
					for (int i = 0; i < 50; i++) {
						String chunk = "想" + id + "-" + i;
						expectedChars.addAndGet(chunk.length());
						emitter.reasoningDelta(chunk);
					}
				}));
			}
			// 与出帧线程并发地收口思考段：chars 的读取与 message_end 必须同锁（I4）。
			pool.shutdown();
			while (!pool.isTerminated()) {
				emitter.reasoningEnd();
				Thread.yield();
			}
			for (Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
			emitter.reasoningEnd();

			assertReasoningSegmentsAreWellFormed(wire.frames(), expectedChars.get());
		}
	}

	/**
	 * 帧序不变量：每个 {@code message_start(kind=reasoning)} 之后才是本段 delta，
	 * {@code message_end.chars} 恰好等于本段 delta 文本长度之和；结束后不得再出现本段 delta；
	 * 全部段都已闭合；{@code seq} 在到达顺序上严格递增。
	 */
	private static void assertReasoningSegmentsAreWellFormed(List<Map<String, Object>> frames, long expectedChars) {
		boolean open = false;
		long segmentChars = 0L;
		long total = 0L;
		long lastSeq = 0L;
		for (Map<String, Object> frame : frames) {
			Number seq = (Number) frame.get("seq");
			if (seq != null) {
				assertThat(seq.longValue()).as("seq 必须严格递增（到达顺序 = 取号顺序）").isGreaterThan(lastSeq);
				lastSeq = seq.longValue();
			}
			String type = String.valueOf(frame.get("type"));
			if ("message_start".equals(type)) {
				assertThat(frame).containsEntry("kind", "reasoning");
				assertThat(open).as("思考段不得嵌套/重复开段").isFalse();
				open = true;
				segmentChars = 0L;
			}
			else if ("delta".equals(type)) {
				assertThat(frame).containsEntry("kind", "reasoning");
				assertThat(open).as("delta 必须落在已开的思考段内").isTrue();
				segmentChars += ((String) frame.get("text")).length();
			}
			else if ("message_end".equals(type)) {
				assertThat(frame).containsEntry("kind", "reasoning");
				assertThat(open).as("message_end 必须配对一个已开的思考段").isTrue();
				assertThat(((Number) frame.get("chars")).longValue())
						.as("message_end.chars 必须等于本段 delta 文本长度之和").isEqualTo(segmentChars);
				open = false;
				total += segmentChars;
			}
			else {
				throw new AssertionError("思考段用例不应出现其它帧型：" + type);
			}
		}
		assertThat(open).as("最后一次 reasoningEnd 必须闭合思考段").isFalse();
		assertThat(total).as("所有段的 chars 之和 = 全部思考文本长度").isEqualTo(expectedChars);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private static String concatTexts(List<Map<String, Object>> frames) {
		return String.join("", frames.stream()
				.filter(frame -> "delta".equals(frame.get("type")))
				.map(frame -> (String) frame.get("text"))
				.toList());
	}

	private static List<Long> expectedSeqs(int total) {
		List<Long> seqs = new ArrayList<>(total);
		for (long seq = 1L; seq <= total; seq++) {
			seqs.add(seq);
		}
		return seqs;
	}

	private static List<Object> expectedTypes(int total) {
		List<Object> types = new ArrayList<>(total);
		types.add("message_start");
		for (int i = 1; i < total - 1; i++) {
			types.add("delta");
		}
		types.add("message_end");
		return types;
	}


}
