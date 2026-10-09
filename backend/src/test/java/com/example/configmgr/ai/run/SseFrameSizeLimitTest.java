package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-10 用例：<b>SSE 单帧 64KB 上限 + 字段级截断</b>（A19/A21）。
 *
 * <h2>五条判据</h2>
 * <ol>
 * <li>超限帧被<b>字段级</b>截断后仍是合法 JSON，且 {@code RunStore#events} 回放读不丢帧
 *     （R5 的反例防线：坏 JSON 会被静默忽略 = seq 洞）；</li>
 * <li>{@code truncated}/{@code truncatedReason} 两个标记字段存在且可被读取；</li>
 * <li>未超限帧<b>一字不改</b>（不误伤：无标记、值原样）；</li>
 * <li>边界：恰好等于上限不截断、上限 + 1 触发截断且落地 ≤ 上限（截后复测）；</li>
 * <li>回放 seq 无洞（超限帧照旧占号）。</li>
 * </ol>
 *
 * <p>观测面说明：{@code delta} 帧自 T3-2 起<b>不入归档</b>（与心跳同待遇），故 {@code text} 字段的
 * 断言走"投递给订阅者的负载原文"（{@link FrameWire#payloads()}）；{@code result} 字段的断言可直接
 * 打在归档上（{@code tool_result} 帧入档）。两条通道都过同一个截断点（{@code emit} 锁内）。
 */
class SseFrameSizeLimitTest {

	private static final String RUN_ID = "run-frame-limit";

	private static final int LIMIT = 4096;   // 用例自带小上限，等价于生产的 64KB 判定逻辑

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.mapper, Runnable::run, 256, LIMIT);

	// ── ① 超限帧字段级截断 + 回放不丢帧（result 通道，入归档） ──

	@Test
	void oversizeToolResultIsTruncatedAtFieldLevelAndStillReplayable() throws Exception {
		String huge = "x".repeat(20_000);
		this.out.toolResult(pending("call-1"), true, false, huge);

		List<Map<String, Object>> archived = this.store.events(RUN_ID);
		assertThat(archived).as("归档里必须有这一帧（坏 JSON 会被静默忽略 = seq 洞）").hasSize(1);

		Map<String, Object> frame = archived.get(0);
		assertThat(frame.get("type")).isEqualTo("tool_result");
		String result = (String) frame.get("result");
		assertThat(result).as("值被截断（不是整帧丢弃）").startsWith("x").hasSizeLessThan(huge.length());
		assertThat(this.mapper.writeValueAsBytes(frame).length)
				.as("落地帧的 UTF-8 字节数 ≤ 上限").isLessThanOrEqualTo(LIMIT);
		// 合法 JSON：能被反序列化回 Map（回放读路径就是这么读的）
		assertThat(this.mapper.readValue(this.mapper.writeValueAsString(frame), Map.class))
				.containsEntry("type", "tool_result")
				.containsEntry("toolCallId", "call-1");
	}

	// ── ①′ 同一截断点也覆盖 delta 的 text 字段（投递通道） ──

	@Test
	void oversizeDeltaPayloadIsTruncatedToo() throws Exception {
		FrameWire wire = FrameWire.attachTo(this.out);
		String huge = "y".repeat(20_000);
		this.out.delta(huge);

		assertThat(wire.payloads()).hasSize(1);
		Map<String, Object> payload = this.mapper.readValue(wire.payloads().get(0), Map.class);
		assertThat(payload).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true);
		assertThat((String) payload.get("text")).hasSizeLessThan(huge.length());
		assertThat(wire.payloads().get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
				.isLessThanOrEqualTo(LIMIT);
	}

	// ── ② 标记字段 ──

	@Test
	void truncatedFrameCarriesTruncatedAndReason() {
		this.out.toolResult(pending("call-1"), true, false, "y".repeat(9_000));

		Map<String, Object> frame = this.store.events(RUN_ID).get(0);
		assertThat(frame).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true);
		assertThat(String.valueOf(frame.get(FrameSizeLimiter.TRUNCATED_REASON_FIELD)))
				.contains("frame-bytes-exceeded")
				.contains("limit=" + LIMIT)
				.contains("result");
		assertThat(frame.get("toolCallId")).as("其余字段原样保留").isEqualTo("call-1");
		assertThat(frame.get("name")).isEqualTo("list_config_defs");
	}

	// ── ③ 未超限帧一字不改（不误伤） ──

	@Test
	void smallFrameIsUntouched() throws Exception {
		FrameWire wire = FrameWire.attachTo(this.out);
		String text = "你好，世界";
		this.out.delta(text);
		this.out.toolResult(pending("call-1"), true, false, "{\"ok\":true}");

		Map<String, Object> archived = this.store.events(RUN_ID).get(0);
		assertThat(archived.get("result")).as("值原样").isEqualTo("{\"ok\":true}");
		assertThat(archived).as("不挂标记（没有截断发生）")
				.doesNotContainKey(FrameSizeLimiter.TRUNCATED_FIELD)
				.doesNotContainKey(FrameSizeLimiter.TRUNCATED_REASON_FIELD);

		Map<String, Object> payload = this.mapper.readValue(wire.payloads().get(0), Map.class);
		assertThat(payload.get("text")).as("投递负载里的值也原样").isEqualTo(text);
		assertThat(payload).doesNotContainKey(FrameSizeLimiter.TRUNCATED_FIELD);
	}

	// ── ④ 边界：恰好 = 上限不截断；上限 + 1 触发 ──

	@Test
	void boundaryAtExactLimitIsNotTruncatedButOneByteOverIs() throws Exception {
		int base = this.mapper.writeValueAsBytes(emptyTextFrame()).length;
		int exactChars = LIMIT - base;
		assertThat(exactChars).as("用例自洽：上限需大于帧骨架").isGreaterThan(100);

		FrameWire wire = FrameWire.attachTo(this.out);
		this.out.delta("z".repeat(exactChars));
		this.out.delta("z".repeat(exactChars + 1));

		Map<String, Object> exact = this.mapper.readValue(wire.payloads().get(0), Map.class);
		assertThat(exact).as("恰好等于上限（" + LIMIT + " 字节）不得触发截断")
				.doesNotContainKey(FrameSizeLimiter.TRUNCATED_FIELD);
		assertThat((String) exact.get("text")).hasSize(exactChars);

		Map<String, Object> over = this.mapper.readValue(wire.payloads().get(1), Map.class);
		assertThat(over).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true);
		assertThat(wire.payloads().get(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
				.as("截后复测：仍超则迭代收缩到满足").isLessThanOrEqualTo(LIMIT);
	}

	// ── ⑤ 回放 seq 无洞（超限帧照旧占号） ──

	@Test
	void oversizedFramesStillConsumeContiguousSeqs() {
		this.out.toolResult(pending("call-1"), true, false, "a".repeat(20_000));
		this.out.toolResult(pending("call-2"), true, false, "b".repeat(30_000));
		this.out.toolResult(pending("call-3"), true, false, "ok");

		assertThat(this.store.archivedSeqs(RUN_ID)).as("超限帧照旧占号（回放 seq 无洞）")
				.containsExactly(1L, 2L, 3L);
		assertThat(this.store.events(RUN_ID)).as("回放读一帧不少").hasSize(3);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	/** delta 帧的骨架（text 为空）——用来精确算"恰好到上限"的字符数。 */
	private Map<String, Object> emptyTextFrame() {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("messageId", "assistant-" + RUN_ID);
		frame.put("text", "");
		frame.put("runId", RUN_ID);
		frame.put("seq", 1L);
		return frame;
	}

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("list_config_defs");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.EXECUTED);
		return pending;
	}
}
