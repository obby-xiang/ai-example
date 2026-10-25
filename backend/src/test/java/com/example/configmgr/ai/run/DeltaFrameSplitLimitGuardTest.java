package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tool.TruncationContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 截断专项（复审 C1/I1/I2/I3）：<b>上限过小时必须失败，不能静默截断或变成空串</b>。
 *
 * <h2>判据</h2>
 * <ol>
 * <li>{@link FrameSizeLimiter#splitStringValue} 在"帧骨架已超限（budget ≤ 0）"、"预算放不下一个码点"、
 *     "复测收缩失败"时抛 {@link IllegalStateException}，消息含 {@code type/key/limit/originalChars}；
 *     绝不返回空串、绝不返回超限片、绝不回落 {@code apply} 的字段截断；</li>
 * <li>运行期下限独立成立：{@link SseChatEmitter} 构造器拒绝 {@code maxFrameBytes < 1024}
 *     （不依赖 {@code AiProperties} 的启动校验是否已接线）；</li>
 * <li>恰好允许的最小值（1024）仍能正常拆帧：每片 ≤ 上限、无截断标记、拼接 = 原文、无空片；</li>
 * <li>大输入按<b>精确预算</b>切片（ASCII 每片长度 = 预算，除最后一片），且不依赖"扫描整个余串"
 *     的二分上界（I3 的 hi 收缩）；</li>
 * <li>代理对精确边界：预算落在高代理项之后时回退一个 code unit，不产生半个 emoji。</li>
 * </ol>
 */
class DeltaFrameSplitLimitGuardTest {

	private static final String RUN_ID = "run-delta-split";

	private static final String MESSAGE_ID = "assistant-" + RUN_ID;

	private static final int LIMIT = 4096;

	/** 与运行期下限同值（spec：启动下限 1024；AiProperties 由主会话接线）。 */
	private static final int MIN_ALLOWED = 1024;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	// ── ① 小上限矩阵：必须抛错，不回落字段截断/空串 ──

	@Test
	void splitRejectsLimitsThatCannotHoldTheFrameSkeleton() {
		Map<String, Object> frame = skeletonWithText("字😀".repeat(20));
		int skeletonBytes = skeletonBytes();
		assertThat(skeletonBytes).as("用例自洽：固定小上限样本必须小于骨架，才能覆盖 budget<=0 分支")
				.isGreaterThan(130);

		for (int limit : new int[] { 60, 100, 110, 128, 130 }) {
			assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(frame, "text", this.mapper, limit))
					.as("limit=%s 必须失败而不是截断", limit)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("type=delta")
					.hasMessageContaining("key=text")
					.hasMessageContaining("limit=" + limit)
					.hasMessageContaining("originalChars=" + ((String) frame.get("text")).length())
					.hasMessageContaining("max-frame-bytes");
		}
		// 空正文也不能借"它本来就是空的"绕过上限校验：骨架放不下就是配置错误。
		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(skeletonWithText(""), "text", this.mapper, 60))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("originalChars=0");
	}

	@Test
	void splitRejectsBudgetSmallerThanOneCodePointInsteadOfEmptyOrOversize() {
		Map<String, Object> frame = skeletonWithText("😀");
		int skeletonBytes = skeletonBytes();
		int emojiBytes = FrameSizeLimiter.escapedLength("😀", this.mapper);
		assertThat(emojiBytes).as("一个 emoji = 2 个 code unit，JSON 转义后按字节计").isGreaterThan(2);
		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(frame, "text", this.mapper,
				skeletonBytes + emojiBytes - 1))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("firstCodePointBytes=" + emojiBytes)
				.hasMessageContaining("originalChars=2");

		// 恰好放得下一个码点 ⇒ 单片、原文、不丢成空串。
		assertThat(FrameSizeLimiter.splitStringValue(frame, "text", this.mapper, skeletonBytes + emojiBytes))
				.as("一个码点恰好放得下时不得抛错、不得切半")
				.containsExactly("😀");
	}

	// ── ①′ 非法入参：requireNonNull / 明确 IAE，不静默返回 null 片段 ──

	@Test
	void splitRejectsNonStringFieldAndInvalidArguments() {
		Map<String, Object> frame = skeletonWithText("正文");
		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(null, "text", this.mapper, LIMIT))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(frame, null, this.mapper, LIMIT))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(frame, "text", null, LIMIT))
				.isInstanceOf(NullPointerException.class);

		Map<String, Object> nonString = skeletonWithText("x");
		nonString.put("text", 42);
		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(nonString, "text", this.mapper, LIMIT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("field=text")
				.hasMessageContaining("Integer");

		assertThatThrownBy(() -> FrameSizeLimiter.splitStringValue(frame, "text", this.mapper, 0))
				.as("<=0 = 不限制只存在于 apply 的兼容路径，拆分器不允许无上限")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxBytes 必须 > 0");
	}

	// ── ② 运行期下限独立成立（不依赖 AiProperties） ──

	@Test
	void emitterRejectsMaxFrameBytesBelowRuntimeFloor() {
		for (int limit : new int[] { 1, 60, 100, 110, 128, 130, MIN_ALLOWED - 1 }) {
			assertThatThrownBy(() -> new SseChatEmitter(RUN_ID, this.store, this.mapper, Runnable::run, 256, limit))
					.as("maxFrameBytes=%s 必须在装配期失败", limit)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("max-frame-bytes")
					.hasMessageContaining("1024")
					.hasMessageContaining(String.valueOf(limit));
		}
		assertThat(new SseChatEmitter(RUN_ID, this.store, this.mapper, Runnable::run, 256, MIN_ALLOWED).maxFrameBytes())
				.as("下限本身必须可用")
				.isEqualTo(MIN_ALLOWED);
	}

	// ── ③ 最小允许上限仍能正常拆帧（无截断标记、无空片） ──

	@Test
	void minimumAllowedLimitStillSplitsWithoutTruncation() {
		SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.mapper, Runnable::run, 256, MIN_ALLOWED);
		FrameWire wire = FrameWire.attachTo(out);
		String original = "字😀".repeat(500);   // 每组 3 + 12 = 15 字节 ⇒ 7500 字节 > 1024

		out.delta(original);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).hasSizeGreaterThan(1);
		assertThat(frames).allSatisfy(frame -> assertThat(frame)
				.containsEntry("type", "delta")
				.containsEntry("messageId", MESSAGE_ID)
				.doesNotContainKey(TruncationContract.TRUNCATED_FIELD)
				.doesNotContainKey(TruncationContract.NOTICE_CODE_FIELD));
		assertThat(wire.payloads()).allSatisfy(payload -> assertThat(payload.getBytes(StandardCharsets.UTF_8).length)
				.isLessThanOrEqualTo(MIN_ALLOWED));
		List<String> texts = frames.stream().map(frame -> (String) frame.get("text")).toList();
		assertThat(texts).allSatisfy(text -> assertThat(text).as("不得把正文丢成空串").isNotEmpty());
		assertThat(String.join("", texts)).as("拼接 = 原文").isEqualTo(original);
	}

	// ── ④ 大输入：精确预算切片（不要每片扫整个余串） ──

	@Test
	void largeAsciiInputSplitsAtExactBudgetBoundaries() {
		String original = "a".repeat(1_000_000);
		Map<String, Object> frame = skeletonWithText(original);
		int budget = LIMIT - skeletonBytes();
		assertThat(budget).isPositive();

		List<String> chunks = FrameSizeLimiter.splitStringValue(frame, "text", this.mapper, LIMIT);

		int expected = (original.length() + budget - 1) / budget;
		assertThat(chunks).as("ASCII 每片恰好占满预算（除最后一片）").hasSize(expected);
		assertThat(chunks.subList(0, expected - 1))
				.allSatisfy(chunk -> assertThat(chunk).hasSize(budget));
		assertThat(chunks.get(expected - 1)).hasSize(original.length() - budget * (expected - 1));
		assertThat(String.join("", chunks)).isEqualTo(original);
	}

	// ── ⑤ 代理对精确边界 ──

	@Test
	void surrogatePairAtExactChunkBoundaryIsNotSplit() {
		String original = "a".repeat(40) + "😀" + "b".repeat(40);
		Map<String, Object> frame = skeletonWithText(original);
		int skeletonBytes = skeletonBytes();

		// 预算落在高代理项之后：40 个 'a' + 半个 emoji 的字节数（若按 code unit 切会切半）
		int emojiBytes = FrameSizeLimiter.escapedLength("😀", this.mapper);
		List<String> afterHighSurrogate = FrameSizeLimiter.splitStringValue(frame, "text", this.mapper,
				skeletonBytes + 40 + emojiBytes / 2);
		assertThat(afterHighSurrogate.get(0)).as("回退一个 code unit，不切半个 emoji").isEqualTo("a".repeat(40));
		assertThat(afterHighSurrogate.get(1)).startsWith("😀");
		assertThat(String.join("", afterHighSurrogate)).isEqualTo(original);

		// 预算恰好在完整 emoji 之后：40 个 'a' + 完整 emoji 恰好放得下
		List<String> withEmoji = FrameSizeLimiter.splitStringValue(frame, "text", this.mapper,
				skeletonBytes + 40 + emojiBytes);
		assertThat(withEmoji.get(0)).isEqualTo("a".repeat(40) + "😀");
		assertThat(withEmoji).allSatisfy(chunk -> assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)))
				.isFalse());
		assertThat(String.join("", withEmoji)).isEqualTo(original);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private Map<String, Object> skeletonWithText(String text) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("atMs", System.currentTimeMillis());
		frame.put("messageId", MESSAGE_ID);
		frame.put("text", text);
		frame.put("runId", RUN_ID);
		frame.put("seq", Long.MAX_VALUE);   // 与写出器拆分骨架同口径（最坏帧号）
		return frame;
	}

	private int skeletonBytes() {
		Map<String, Object> probe = skeletonWithText("");
		try {
			return this.mapper.writeValueAsBytes(probe).length;
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
