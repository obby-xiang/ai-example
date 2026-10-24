package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.TruncationContract;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
 * <li>{@code truncated}/数字/{@code noticeCode} 标记字段存在且可被读取（{@code truncatedReason}
 *     仅作旧客户端兼容别名）；</li>
 * <li>未超限帧<b>一字不改</b>（不误伤：无标记、值原样）；</li>
 * <li>边界：恰好等于上限不截断、上限 + 1 触发截断且落地 ≤ 上限（截后复测）；</li>
 * <li>回放 seq 无洞（超限帧照旧占号）。</li>
 * </ol>
 *
 * <p>观测面说明：B1（2026-10-20）起 {@code delta} 也入归档，但 {@code text} 字段的断言仍走
 * "投递给订阅者的负载原文"（{@link FrameWire#payloads()}）—— 实时与归档两条通道共用同一个
 * 截断点（{@code emit} 锁内），断言哪一条都等价，这里保留原观测面以免掩盖投递侧回归；
 * {@code result} 字段的断言直接打在归档上（{@code tool_result} 帧入档）。
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
		assertThat(frame).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true)
				.containsEntry("noticeCode", "frame_truncated");
		String legacyNotice = String.valueOf(frame.get(FrameSizeLimiter.TRUNCATED_REASON_FIELD));
		assertThat(legacyNotice)
				.as("旧客户端兼容字段：英文降级短句，不含机器前缀/数字/中文")
				.isEqualTo("Result truncated because it exceeded the frame size limit.")
				.doesNotContain("frame-bytes-exceeded")
				.doesNotContain("limit=")
				.doesNotContain("truncatedFields")
				.doesNotContainPattern("\\d")
				.doesNotContainPattern("[\\u4e00-\\u9fff]");
		assertThat(frame.get(TruncationContract.NOTICE_FIELD)).isEqualTo(legacyNotice);
		assertThat(frame.get("toolCallId")).as("其余字段原样保留").isEqualTo("call-1");
		assertThat(frame.get("name")).isEqualTo("list_config_defs");
	}

	/** I-2：没有可压缩字段时不得谎称“已压缩”，noticeCode 单独区分该结局。 */
	@Test
	void uncompressibleOversizeFrameDoesNotClaimCompression() throws Exception {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "tool_result");
		frame.put("payload", "z".repeat(9_000));

		byte[] bytes = FrameSizeLimiter.apply(frame, this.mapper, LIMIT);

		assertThat(bytes).isNotNull();
		assertThat(bytes.length).as("不可截字段占主导时无法压到上限内").isGreaterThan(LIMIT);
		assertThat(frame).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true)
				.containsEntry("noticeCode", "frame_limit_unreached")
				.containsEntry(TruncationContract.ORIGINAL_CHARS_FIELD, 0)
				.containsEntry(TruncationContract.RETURNED_CHARS_FIELD, 0);
		assertThat(frame).doesNotContainKey(TruncationContract.STRATEGY_FIELD);
		String legacyNotice = String.valueOf(frame.get(TruncationContract.NOTICE_FIELD));
		assertThat(legacyNotice)
				.isEqualTo("Result exceeds the frame size limit and could not be compressed further.")
				.doesNotContain("truncated because")
				.doesNotContainPattern("[\\u4e00-\\u9fff]");
		assertThat(frame.get(TruncationContract.LEGACY_REASON_FIELD)).isEqualTo(legacyNotice);
	}

	/** I-3 / Minor 6：模型侧先裁、帧字节再裁时，帧码必须覆盖模型码（不能留下“此处仍为全文”的假话）。 */
	@Test
	void frameTruncationOverridesModelViewNoticeCode() {
		ToolResultLimiter limiter = new ToolResultLimiter(new AiProperties());
		SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, RunStoreFrameStore.of(this.store),
				this.mapper, Runnable::run, 256, LIMIT, SseWriteBudget.direct(), null, null, limiter);
		String full = "p".repeat(25_000);

		emitter.toolResult(pending("call-both"), true, false, full);

		Map<String, Object> frame = this.store.events(RUN_ID).get(0);
		assertThat(frame).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true)
				.containsEntry("noticeCode", "frame_truncated")
				.containsEntry(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
		assertThat(String.valueOf(frame.get(TruncationContract.NOTICE_FIELD)))
				.isEqualTo("Result truncated because it exceeded the frame size limit.")
				.doesNotContain("full result")
				.doesNotContainPattern("[\\u4e00-\\u9fff]");
		assertThat((String) frame.get("result")).as("帧字节超限后 result 已不是全文").hasSizeLessThan(full.length());
		assertThat((Integer) frame.get(TruncationContract.ORIGINAL_CHARS_FIELD)).isEqualTo(25_000);
		assertThat((Integer) frame.get(TruncationContract.RETURNED_CHARS_FIELD)).isPositive()
				.isLessThan(25_000);
	}

	/** I-3：模型侧视图截断与帧字节截断共用同一 noticeCode 契约。 */
	@Test
	void modelViewTruncationCarriesItsOwnNoticeCode() {
		ToolResultLimiter limiter = new ToolResultLimiter(new AiProperties());
		SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, RunStoreFrameStore.of(this.store),
				this.mapper, Runnable::run, 256, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), null, null, limiter);
		String full = "m".repeat(25_000);

		emitter.toolResult(pending("call-model-view"), true, false, full);

		Map<String, Object> frame = this.store.events(RUN_ID).get(0);
		assertThat(frame).containsEntry(FrameSizeLimiter.TRUNCATED_FIELD, true)
				.containsEntry("noticeCode", "model_view_truncated")
				.containsEntry(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
		assertThat(frame.get("result")).as("模型侧被裁，帧 result 仍为全文").isEqualTo(full);
		assertThat((Integer) frame.get(TruncationContract.ORIGINAL_CHARS_FIELD)).isEqualTo(25_000);
		assertThat((Integer) frame.get(TruncationContract.RETURNED_CHARS_FIELD)).isPositive()
				.isLessThan(25_000);
		String legacyNotice = String.valueOf(frame.get(TruncationContract.NOTICE_FIELD));
		assertThat(legacyNotice)
				.isEqualTo("Result truncated for the model; this frame shows the full result.")
				.doesNotContainPattern("\\d")
				.doesNotContainPattern("[\\u4e00-\\u9fff]");
		assertThat(frame.get(TruncationContract.LEGACY_REASON_FIELD)).isEqualTo(legacyNotice);
	}

	/** A10 R-15：诊断数字与字段名进 warn 日志（用户 notice 不再承载）。 */
	@Test
	void truncationDiagnosticsGoToWarnLog() {
		Logger logger = (Logger) LoggerFactory.getLogger(FrameSizeLimiter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			this.out.toolResult(pending("call-1"), true, false, "y".repeat(9_000));

			assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
					.contains("original=")
					.contains("limit=" + LIMIT)
					.contains("fields=result"));
		} finally {
			logger.detachAppender(appender);
		}
	}

	// ── ③ 未超限帧一字不改（不误伤） ──

	@Test
	void smallFrameIsUntouched() throws Exception {
		FrameWire wire = FrameWire.attachTo(this.out);
		String text = "你好，世界";
		this.out.delta(text);
		this.out.toolResult(pending("call-1"), true, false, "{\"ok\":true}");

		Map<String, Object> archived = this.store.events(RUN_ID).stream()
				.filter(frame -> "tool_result".equals(frame.get("type")))
				.findFirst()
				.orElseThrow();
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

	// ── ⑥ G5 统一截断字段契约（两侧同一套字段名） ──

	@Test
	void truncatedFrameCarriesTheUnifiedFieldSet() {
		this.out.toolResult(pending("call-1"), true, false, "y".repeat(9_000));

		Map<String, Object> frame = this.store.events(RUN_ID).get(0);
		assertThat(frame).containsEntry(TruncationContract.TRUNCATED_FIELD, true)
				.containsEntry("noticeCode", "frame_truncated")
				.containsEntry(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
		assertThat(frame.get(TruncationContract.ORIGINAL_CHARS_FIELD)).isEqualTo(9_000);
		assertThat((Integer) frame.get(TruncationContract.RETURNED_CHARS_FIELD)).isPositive()
				.isLessThan(9_000);
		assertThat(String.valueOf(frame.get(TruncationContract.NOTICE_FIELD)))
				.isEqualTo("Result truncated because it exceeded the frame size limit.");
		assertThat(frame.get(TruncationContract.LEGACY_REASON_FIELD))
				.as("truncatedReason 保留为 notice 的兼容别名").isEqualTo(frame.get(TruncationContract.NOTICE_FIELD));
	}

	@Test
	void structuredResultIsWholeMarkedInsteadOfCharCut() throws Exception {
		StringBuilder sb = new StringBuilder("{\"rows\":[");
		for (int i = 1; i <= 200; i++) {
			if (i > 1) {
				sb.append(',');
			}
			sb.append("{\"code\":\"C-").append(i).append("\",\"title\":\"定义 ").append(i).append("\"}");
		}
		sb.append("]}");
		String json = sb.toString();
		assertThat(json.length()).isGreaterThan(LIMIT);

		this.out.toolResult(pending("call-1"), true, false, json);

		Map<String, Object> frame = this.store.events(RUN_ID).get(0);
		assertThat(this.mapper.writeValueAsBytes(frame).length).isLessThanOrEqualTo(LIMIT);
		String result = (String) frame.get("result");
		Map<?, ?> marker = this.mapper.readValue(result, Map.class);
		assertThat(marker.get("truncated")).as("结构化结果整体标记成合法 JSON，绝不半截").isEqualTo(true);
		assertThat(String.valueOf(marker.get("preview"))).isNotEmpty();
		assertThat(marker.get("strategy")).isEqualTo(TruncationContract.STRATEGY_HEAD);
		assertThat(((Number) marker.get("originalChars")).intValue()).isEqualTo(json.length());
		assertThat(String.valueOf(marker.get("notice")))
				.as("A10 R-15 I-A：结构化 marker 也进用户可见 result，必须是语言中立英文且不承诺台账")
				.isEqualTo("Structured result exceeded the frame size limit; marked as a JSON envelope with a header preview.")
				.doesNotContainPattern("[\\u4e00-\\u9fff]")
				.doesNotContain("执行台账")
				.doesNotContain("台账");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	/**
	 * delta 帧的骨架（text 为空）——用来精确算"恰好到上限"的字符数。
	 *
	 * <p>
	 * 必须与 {@code SseChatEmitter#frame} 的骨架逐字段同形（T4-3 后半起该骨架含 {@code atMs}，
	 * 服务端钟 13 位毫秒）；少一个字段就会把"恰好等于上限"算小，边界用例误判为超限。
	 */
	private Map<String, Object> emptyTextFrame() {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("atMs", System.currentTimeMillis());
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
