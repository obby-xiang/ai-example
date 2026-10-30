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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>端到端单用例</b>：一帧的<b>交付号</b>、<b>归档号</b>、Stream entry ID、唤醒上界与 {@code lastSeq}
 * 必须是<b>同一个号</b>，且交付字节就是归档字节。
 *
 * <h2>被取消的旧口径（"先取低号、落档改高号"）</h2>
 * 旧实现是两步：先 {@code INCR} 取号 1，落档时表尾已被另一实例的 {@code 2-0} 越过，
 * 于是改号成 3 重写。它带来两个后果：① 若调用方已按 1 号投递过半帧，实时流（1）与回放（3）分叉；
 * ② "低号帧后到"这件事本身就是缺帧源 —— 订阅者游标越过该号之后，那一帧永远不会再被补发。
 *
 * <h2>本批口径（Task 2）</h2>
 * 定号与归档在<b>一次 Redis 脚本</b>里完成：{@code AppendResult.seq} 就是 entry ID 的毫秒段，
 * 帧体里的 {@code seq} 也是它；实时投递原样使用回传字节。于是"号"只有一个来源，
 * 不存在"两个号"这种中间态，也<b>不会</b>出现"未来落档一个更低号的新帧"。
 *
 * <p>kill：退回两步式（先取号、被越过就改号）⇒ 实时帧的号与归档号不相等（首条断言红），
 * 或出现"实时 1 / 归档 3"的两份不同字节（末条断言红）。
 */
class SseChatEmitterAtomicAppendConsistencyTest {

	private static final String RUN_ID = "run-atomic-e2e";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	/**
	 * 竞争前提：另一实例先归档了一帧（1），本写出器的帧随后归档 —— 它必须拿到<b>更高的号</b>（2），
	 * 且实时投递、唤醒、归档、{@code lastSeq} 全部同号同字节。
	 */
	@Test
	void deliveredWakeAndLastSeqAllCarryTheArchivedSeq() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore frames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		RedisFrameStore other = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		FrameStore.AppendResult competing = other.appendAndAllocateSeq(RUN_ID, frame("tool_result"), payloadFactory());
		assertThat(competing.seq()).as("另一实例先归档（1 号）").isEqualTo(1L);

		FakeWakeBus bus = new FakeWakeBus();
		SseChatEmitter out = emitter(frames, bus);
		FrameWire wire = FrameWire.attachTo(out);
		out.delta("正文");

		List<Map<String, Object>> live = FrameWire.normalize(wire.frames());
		// Task 2 返修第 1 条：订阅者游标是 0（attach 不走回放）⇒ 扇出前先从归档补齐更低号，
		// 再投本帧。因此这里应看到"另一实例的 1 + 本帧的 2"，顺序升序。
		assertThat(seqs(live)).as("先补齐跨实例写的低号，再投本帧").containsExactly(1L, 2L);
		long liveSeq = ((Number) live.get(live.size() - 1).get("seq")).longValue();
		assertThat(liveSeq).as("本写出器的帧拿到下一个号（不存在'先取低号'）").isEqualTo(2L);

		List<Long> archivedSeqs = seqs(frames.readFrom(RUN_ID, 0L, 0));
		assertThat(archivedSeqs).as("归档：另一实例的 1 号 + 本帧的 2 号，不丢不重").containsExactly(1L, 2L);
		assertThat(archivedSeqs.get(archivedSeqs.size() - 1)).as("交付号 == 归档号").isEqualTo(liveSeq);

		List<String> ids = redis.streamIds(frames.streamKey(RUN_ID));
		assertThat(ids).as("Stream entry ID 的毫秒段 == 业务 seq（显式 ID，无自动 ID）").last()
			.isEqualTo(liveSeq + "-0");
		assertThat(frames.lastSeq(RUN_ID)).as("lastSeq 精确到归档号").isEqualTo(liveSeq);
		assertThat(bus.published).as("唤醒上界 = 归档号").containsExactly(RUN_ID + "@" + liveSeq);
		assertThat(wire.payloads().get(wire.payloads().size() - 1)).as("实时帧原文 == 归档帧体（同一份字节、同一个 seq）")
			.isEqualTo(archivedFrameJson(redis, frames.streamKey(RUN_ID)));
	}

	/**
	 * <b>贴着单帧上限 + 宽号</b>：负载在<b>号占位</b>（{@value RedisFrameStore#SEQ_PLACEHOLDER_DIGITS} 位数字，
	 * 恒 ≥ 任何真实号）下完成截断/序列化，最终号只会更窄 ⇒ 归档/交付字节绝不会因为号变宽越过上限。
	 *
	 * <p>场景：先预置一个 12 位号的归档（模拟长跑/迁移后的量级），下一帧因此是 13 位号；
	 * 一个远超上限的 {@code tool_result} 帧被字段级截断。
	 *
	 * <p>kill：按"当前号宽度"截断后再改成更宽的号 ⇒ 归档/实时帧多出几个字节，
	 * {@code ≤ max-frame-bytes} 断言红；实时与归档不同源 ⇒ 末条断言红。
	 */
	@Test
	void frameAtTheSizeCapIsTruncatedForTheWidestSeqSoTheFinalBytesNeverOverflow() {
		int maxFrameBytes = FrameSizeLimiter.MIN_FRAME_BYTES;
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore frames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		FrameStore.AppendResult seeded = frames.appendAndGetSeq(RUN_ID, frame("delta", 999_999_999_999L),
				payloadFactory());
		assertThat(seeded.seq()).as("预置 12 位号").isEqualTo(999_999_999_999L);

		SseChatEmitter out = emitter(frames, maxFrameBytes);
		FrameWire wire = FrameWire.attachTo(out);
		out.toolResult(executed("call-cap"), true, false, "x".repeat(4000));

		List<String> payloads = wire.payloads();
		// 订阅者游标 0 ⇒ 先补齐归档里的预置帧（12 位号），再投本帧：末条才是本帧。
		assertThat(payloads).hasSize(2);
		long liveSeq = ((Number) FrameWire.normalize(wire.frames().get(payloads.size() - 1)).get("seq")).longValue();
		assertThat(liveSeq).as("下一帧是 13 位号（比预置号宽一位）").isEqualTo(1_000_000_000_000L);

		String archived = archivedFrameJson(redis, frames.streamKey(RUN_ID));
		String lastPayload = payloads.get(payloads.size() - 1);
		assertThat(lastPayload.getBytes(StandardCharsets.UTF_8).length)
			.as("实时帧必须 ≤ max-frame-bytes（截断按最宽号占位算，最终号更窄）")
			.isLessThanOrEqualTo(maxFrameBytes);
		assertThat(archived.getBytes(StandardCharsets.UTF_8).length).as("归档帧同样 ≤ max-frame-bytes")
			.isLessThanOrEqualTo(maxFrameBytes);
		assertThat(lastPayload).as("实时 == 归档（同号、同一次截断的同一份字节）").isEqualTo(archived);
		assertThat(redis.streamIds(frames.streamKey(RUN_ID))).as("Stream ID 毫秒段 == 最终号").last()
			.isEqualTo(liveSeq + "-0");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter emitter(FrameStore frames, FrameWakeBus bus) {
		return emitter(frames, bus, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
	}

	private SseChatEmitter emitter(FrameStore frames, int maxFrameBytes) {
		return emitter(frames, null, maxFrameBytes);
	}

	private SseChatEmitter emitter(FrameStore frames, FrameWakeBus bus, int maxFrameBytes) {
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

	private static FrameStore.PayloadFactory payloadFactory() {
		return frame -> {
			try {
				return MAPPER.writeValueAsBytes(frame);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

	private static Map<String, Object> frame(String type) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("atMs", 1_700_000_000_000L);
		frame.put("text", "另一实例的帧");
		return frame;
	}

	private static Map<String, Object> frame(String type, long seq) {
		Map<String, Object> frame = frame(type);
		frame.put("seq", seq);
		return frame;
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

}
