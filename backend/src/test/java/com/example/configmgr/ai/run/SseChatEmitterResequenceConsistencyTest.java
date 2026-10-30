package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>端到端单用例</b>：跨实例并发写同一轮时，落档被"表尾越过"而<b>重分配号</b>的那一帧，
 * 实时投递必须改用<b>落档号</b>（而不是取号时那个号）。
 *
 * <h2>缺陷形态（本批要消灭的残留）</h2>
 * 驱动先取号（设为 1），另一实例（confirm / frontend-tool-result）随后取到 2 并先落档；
 * 驱动的 {@code XADD 1-0} 被表尾越过 ⇒ 落档重分配为 3 并写入 {@code 3-0}。
 * 旧实现的实时帧仍带 {@code seq=1}：同一帧在<b>实时流</b>里是 1、在<b>回放/唤醒</b>里是 3 ——
 * 前端 {@code lastSeq} 与去重按号走，于是同一帧会被当成两帧（重复渲染），
 * 或被 {@code mergeBySeq} 当同号丢弃；{@code lastSeq} 也随之错位。
 *
 * <h2>判据（四者同号 + 一份字节）</h2>
 * 实时 fanout 的帧原文、归档帧体、Stream entry ID 的毫秒段、唤醒上界 / {@code lastSeq} 必须<b>同号</b>；
 * 且实时帧原文与归档 {@code frame} 字段<b>逐字节相同</b>（帧体内容与截断结果都是同一份）。
 *
 * <p>kill：实时继续用取号时的号 ⇒ 首条"号上移"断言与原文比较同时红；
 * 只改号不改负载 ⇒ 实时原文与归档不等（帧体内 seq 仍是旧号）；
 * 唤醒仍发旧号 ⇒ publish 断言红。
 */
class SseChatEmitterResequenceConsistencyTest {

	private static final String RUN_ID = "run-reseq";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	@Test
	void resequencedFrameCarriesTheArchivedSeqToFanoutWakeAndLastSeq() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore competing = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		RacingFrameStore driver = new RacingFrameStore(redis.redis, MAPPER, this.properties, competing);
		FakeWakeBus bus = new FakeWakeBus();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), driver, MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), bus, null);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("正文");

		assertThat(driver.raced).as("用例前提：另一实例确实在驱动取号后、落档前抢先落档了更高号").isTrue();
		assertThat(driver.driverSeq).as("驱动本帧取号（会被表尾越过）").isEqualTo(1L);
		assertThat(driver.competingSeq).as("另一实例抢先落档的高号").isEqualTo(2L);

		List<Map<String, Object>> live = FrameWire.normalize(wire.frames());
		assertThat(live).as("本帧只实时投递一次（不得因改号重复入队）").hasSize(1);
		long liveSeq = ((Number) live.get(0).get("seq")).longValue();
		assertThat(liveSeq).as("实时帧必须携带落档号，而不是取号时的旧号").isNotEqualTo(driver.driverSeq)
			.isGreaterThan(driver.competingSeq);

		List<Long> archivedSeqs = seqs(driver.readFrom(RUN_ID, 0L, 0));
		assertThat(archivedSeqs).as("归档：另一实例的高号 + 本帧的重分配号（不丢帧、不重号）")
			.hasSize(2)
			.doesNotHaveDuplicates();
		assertThat(archivedSeqs.get(archivedSeqs.size() - 1)).as("实时 seq == 归档 seq").isEqualTo(liveSeq);

		List<String> ids = redis.streamIds(driver.streamKey(RUN_ID));
		assertThat(ids).as("Stream entry ID 的毫秒段 == 业务 seq（显式 ID，无自动 ID 兜底）")
			.last().isEqualTo(liveSeq + "-0");
		assertThat(driver.lastSeq(RUN_ID)).as("lastSeq 必须精确到落档号").isEqualTo(liveSeq);
		assertThat(bus.published).as("唤醒上界 = 落档号（别的实例据此补帧，不能少一帧或多一帧）")
			.containsExactly(RUN_ID + "@" + liveSeq);

		List<String> payloads = wire.payloads();
		assertThat(payloads).hasSize(1);
		assertThat(payloads.get(0))
			.as("实时帧原文必须与归档帧体逐字节一致（同一份截断结果、同一个 seq）")
			.isEqualTo(archivedFrameJson(redis, driver.streamKey(RUN_ID)));
	}

	/**
	 * <b>贴单帧上限 + 重分配后号位数变长</b>：截断必须发生在<b>最终落档号确定之后</b>。
	 *
	 * <p>场景：驱动取号 1（1 位），另一实例抢先落档 999000（6 位）⇒ 本帧落档重分配为 999001。
	 * 若截断先按 1 位号做完再改号，归档/实时帧就会比 {@code max-frame-bytes} 多出 5 字节 ——
	 * "贴着上限"的帧必然越界（上一版"改号后以归档为准"正是这样被接受的）。
	 *
	 * <p>判据：实时负载 UTF-8 字节 ≤ 上限、归档同样 ≤ 上限、实时 == 归档（同一次最终号截断）、
	 * seq / Stream ID / wake / lastSeq 一致；且本帧确实被重分配（号位数 1 → 6）。
	 *
	 * <p>kill：截断按取号号先做（旧实现）⇒ 1024 字节的帧 + 5 字节号宽 = 1029 &gt; 1024，首条断言红。
	 */
	@Test
	void resequencedFrameAtTheSizeCapIsTruncatedForTheFinalSeq() {
		int maxFrameBytes = FrameSizeLimiter.MIN_FRAME_BYTES;
		String toolCallId = "call-reseq-cap";
		// 1) 标定：同一条出帧路径在 seq=1 时整帧恰好等于上限所需的 result 字符数
		//    （ASCII ⇒ 每多 1 字符多 1 字节；探针与正式用例同 RUN_ID/toolCallId，骨架长度一致）。
		int probeChars = 512;
		int probeBytes = probePayloadBytes(maxFrameBytes, toolCallId, probeChars);
		int resultChars = probeChars + (maxFrameBytes - probeBytes);
		assertThat(resultChars).as("标定后正文必须更长（否则说明探针已被截断，标不准）").isGreaterThan(probeChars);

		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore competing = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		RacingFrameStore driver = new RacingFrameStore(redis.redis, MAPPER, this.properties, competing, 999_000L);
		FakeWakeBus bus = new FakeWakeBus();
		SseChatEmitter out = emitter(driver, bus, maxFrameBytes);
		FrameWire wire = FrameWire.attachTo(out);

		out.toolResult(executed(toolCallId), true, false, "x".repeat(resultChars));

		assertThat(driver.driverSeq).as("本帧取号（1 位号）").isEqualTo(1L);
		assertThat(driver.competingSeq).as("另一实例抢先落档（6 位号）").isEqualTo(999_000L);

		List<String> payloads = wire.payloads();
		assertThat(payloads).hasSize(1);
		String live = payloads.get(0);
		long liveSeq = ((Number) FrameWire.normalize(wire.frames().get(0)).get("seq")).longValue();
		assertThat(liveSeq).as("落档重分配后的号（位数 1 → 6，多 5 字节）").isEqualTo(999_001L);

		String archived = archivedFrameJson(redis, driver.streamKey(RUN_ID));
		assertThat(live.getBytes(StandardCharsets.UTF_8).length)
			.as("实时帧必须 ≤ max-frame-bytes（截断发生在最终号确定之后，不允许越界）")
			.isLessThanOrEqualTo(maxFrameBytes);
		assertThat(archived.getBytes(StandardCharsets.UTF_8).length).as("归档帧同样 ≤ max-frame-bytes")
			.isLessThanOrEqualTo(maxFrameBytes);
		assertThat(live).as("实时 == 归档（同号、同一次截断的同一份字节）").isEqualTo(archived);
		assertThat(redis.streamIds(driver.streamKey(RUN_ID))).as("Stream ID 毫秒段 == 最终号").last()
			.isEqualTo(liveSeq + "-0");
		assertThat(driver.lastSeq(RUN_ID)).as("lastSeq == 最终号").isEqualTo(liveSeq);
		assertThat(bus.published).as("唤醒上界 == 最终号").containsExactly(RUN_ID + "@" + liveSeq);
	}

	/** 标定：同一条出帧路径（tool_result + 给定 result 长度）在 seq=1 时的整帧字节数。 */
	private int probePayloadBytes(int maxFrameBytes, String toolCallId, int resultChars) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore probeStore = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		SseChatEmitter probe = emitter(probeStore, new FakeWakeBus(), maxFrameBytes);
		FrameWire probeWire = FrameWire.attachTo(probe);
		probe.toolResult(executed(toolCallId), true, false, "x".repeat(resultChars));
		assertThat(probeWire.payloads()).hasSize(1);
		assertThat(((Number) FrameWire.normalize(probeWire.frames().get(0)).get("seq")).longValue())
			.as("标定前提：探针帧用的是 1 位号（与正式用例的取号号同宽）").isEqualTo(1L);
		return probeWire.payloads().get(0).getBytes(StandardCharsets.UTF_8).length;
	}

	private SseChatEmitter emitter(FrameStore frames, FakeWakeBus bus, int maxFrameBytes) {
		return new SseChatEmitter(RUN_ID, new InMemoryRunStore(), frames, MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, maxFrameBytes, SseWriteBudget.direct(), bus, null);
	}

	/** 已执行的后端条目（{@code tool_result} 的 {@code ok=true} 只能配这个结局）。 */
	private static PendingToolCall executed(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_import");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(PendingToolCall.EXECUTED);
		return pending;
	}

	/** 归档最后一条记录的 {@code frame} 字段原文。 */
	private static String archivedFrameJson(FakeFrameRedis redis, String streamKey) {
		List<Map<byte[], byte[]>> records = redis.stream(streamKey);
		Map<String, byte[]> fields = FakeFrameRedis.asMap(records.get(records.size() - 1));
		return new String(fields.get(RedisFrameStore.FIELD_FRAME), StandardCharsets.UTF_8);
	}

	private static List<Long> seqs(List<Map<String, Object>> frames) {
		List<Long> out = new ArrayList<>(frames.size());
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

	/**
	 * 出帧链的<b>交错注入器</b>：驱动 {@code nextSeq} 返回之后、{@code append} 之前，
	 * 让另一实例按更高号落档 —— 这正是跨实例并发时显式 ID 被表尾越过的那个窗口。
	 *
	 * <p>继承生产实现（而不是包一层 {@link FrameStore} 装饰器）：落档/回读/取号的新口径
	 * 都直接继承，注入点只有"取号之后"这一处。
	 */
	private static final class RacingFrameStore extends RedisFrameStore {

		private final RedisFrameStore other;

		/** 竞态写用的号（{@code <= 0} = 让另一实例照常取号）。 */
		private final long forcedCompetingSeq;

		private final AtomicBoolean raced = new AtomicBoolean();

		volatile long driverSeq;

		volatile long competingSeq;

		RacingFrameStore(org.springframework.data.redis.core.StringRedisTemplate redis, ObjectMapper mapper,
				AiProperties properties, RedisFrameStore other) {
			this(redis, mapper, properties, other, 0L);
		}

		RacingFrameStore(org.springframework.data.redis.core.StringRedisTemplate redis, ObjectMapper mapper,
				AiProperties properties, RedisFrameStore other, long forcedCompetingSeq) {
			super(redis, mapper, properties, 6000);
			this.other = other;
			this.forcedCompetingSeq = forcedCompetingSeq;
		}

		@Override
		public long nextSeq(String runId) {
			long seq = super.nextSeq(runId);
			this.driverSeq = seq;
			if (this.raced.compareAndSet(false, true)) {
				this.competingSeq = this.forcedCompetingSeq > 0L ? this.forcedCompetingSeq
						: this.other.nextSeq(runId);
				Map<String, Object> frame = new LinkedHashMap<>();
				frame.put("type", "tool_result");
				frame.put("seq", this.competingSeq);
				this.other.append(runId, frame);
			}
			return seq;
		}

	}

}
