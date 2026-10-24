package com.example.configmgr.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具结果截断的<b>统一字段契约</b>（G5 双通道对齐）。
 *
 * <h2>为什么要有这一个类</h2>
 * 模型侧（{@link ToolResultLimiter}）与前端/SSE 侧（{@code run/FrameSizeLimiter}）是两条
 * 独立的预算通道（字符 vs 字节），但它们说的是<b>同一件事</b>："这份结果没给你全部"。
 * 此前两侧各用一套字段名（模型侧只有内嵌文本标记、帧侧只有 {@code truncated/truncatedReason}），
 * 前端与排障只能各读各的。本类把字段名钉成<b>单一事实源</b>：
 *
 * <ul>
 * <li>{@code truncated}：是否被截断（bool）；</li>
 * <li>{@code strategy}：{@link #STRATEGY_HEAD} / {@link #STRATEGY_TAIL} / {@link #STRATEGY_HEAD_TAIL}，
 * 未截断为 null；</li>
 * <li>{@code originalChars}：截断前字符数；</li>
 * <li>{@code returnedChars}：截断后实际拿到/返回的字符数；</li>
 * <li>{@code noticeCode}：可选展示码（{@code frame_truncated} / {@code model_view_truncated} /
 * {@code frame_limit_unreached}）—— 新客户端按码 + 数字本地化，不把某种语言烧进历史帧；</li>
 * <li>{@code notice}：旧客户端兼容的人读短句（语言中立的英文降级文案，不含数字/字段名）；
 * 新客户端只在缺 {@code noticeCode}（旧帧）时回落展示。</li>
 * </ul>
 *
 * <p>
 * {@link #LEGACY_REASON_FIELD}（{@code truncatedReason}）是 {@code notice} 的<b>兼容别名</b>：
 * 历史段持久化读的是它，本期保留一版、值恒等于 {@code notice}；新代码读
 * {@code noticeCode} + {@code originalChars/returnedChars}。
 *
 * <h2>结构化结果（JSON）不走字符截断</h2>
 * 对 JSON 文本做 {@code substring} 会产出非法 JSON（下游解析失败 → 结果整段丢失）。
 * 故 JSON 一律走 {@link #jsonMarker}：<b>整体标记</b>成一份合法 JSON
 * （{@code truncated/strategy/originalChars/returnedChars/notice} 五个字段 +
 * {@code preview} 头部分片；{@code noticeCode} 只用于帧级展示，不进模型侧 marker），
 * 既不破坏解析、又让模型知道"还有更多"。
 */
public final class TruncationContract {

	/** 是否被截断。 */
	public static final String TRUNCATED_FIELD = "truncated";

	/** head / tail / head_tail。 */
	public static final String STRATEGY_FIELD = "strategy";

	/** 截断前字符数。 */
	public static final String ORIGINAL_CHARS_FIELD = "originalChars";

	/** 截断后返回的字符数。 */
	public static final String RETURNED_CHARS_FIELD = "returnedChars";

	/** 人读截断说明（旧客户端兼容；新客户端按 {@link #NOTICE_CODE_FIELD} 本地化）。 */
	public static final String NOTICE_FIELD = "notice";

	/** 可选展示码：新客户端据此选择 locale 模板，不再直接展示 {@link #NOTICE_FIELD}。 */
	public static final String NOTICE_CODE_FIELD = "noticeCode";

	/** 帧字节超限、已对可截字段做头部降采样。 */
	public static final String NOTICE_CODE_FRAME_TRUNCATED = "frame_truncated";

	/** 帧字节超限且无可压缩字段（或压缩后仍超限），未能进一步压缩。 */
	public static final String NOTICE_CODE_FRAME_LIMIT_UNREACHED = "frame_limit_unreached";

	/** 模型侧视图被裁，帧内 result 仍为全文。 */
	public static final String NOTICE_CODE_MODEL_VIEW_TRUNCATED = "model_view_truncated";

	/** {@code notice} 的兼容别名（前端角标/历史段仍读它）。 */
	public static final String LEGACY_REASON_FIELD = "truncatedReason";

	/** 结构化标记里的头部分片（字符串，非对象本身 —— 保证标记整体是合法 JSON）。 */
	public static final String PREVIEW_FIELD = "preview";

	public static final String STRATEGY_HEAD = "head";

	public static final String STRATEGY_TAIL = "tail";

	public static final String STRATEGY_HEAD_TAIL = "head_tail";

	private static final ObjectMapper JSON = new ObjectMapper();

	private TruncationContract() {
	}

	/**
	 * 是否是结构化结果（JSON 对象/数组）。
	 *
	 * <p>
	 * 判定口径故意宽松（只看首字符 + 能解析）：后端工具大量返回 JSON 字符串，
	 * 而"看起来像 JSON 但解析不了"的文本按普通文本处理 —— 截断它不会让任何下游更坏。
	 */
	public static boolean looksStructured(String text) {
		if (text == null) {
			return false;
		}
		String trimmed = text.stripLeading();
		if (trimmed.isEmpty()) {
			return false;
		}
		char first = trimmed.charAt(0);
		if (first != '{' && first != '[') {
			return false;
		}
		try {
			JsonNode node = JSON.readTree(text);
			return node != null && node.isContainerNode();
		}
		catch (Exception ex) {
			return false;
		}
	}

	/**
	 * 结构化结果的整体标记（合法 JSON 字符串）。
	 *
	 * <p>
	 * {@code returnedChars} 记的是<b>标记本身的长度</b>（模型/前端实际拿到的字符数），
	 * 所以它与自身长度互相依赖 —— 这里做有限次不动点迭代（数字位数稳定即收敛），
	 * 避免"字段说 M、实际长度 M±1"这种自相矛盾的标记。
	 *
	 * @param preview       头部分片（可为空串；分片本身按普通字符串转义，不破坏 JSON）
	 * @param originalChars 原文长度
	 * @param notice        人读说明
	 */
	public static String jsonMarker(String preview, int originalChars, String notice) {
		String json = render(preview, originalChars, notice, 0);
		for (int i = 0; i < 4; i++) {
			int next = json.length();
			String candidate = render(preview, originalChars, notice, next);
			if (candidate.length() == next) {
				return candidate;
			}
			json = candidate;
		}
		return json;
	}

	private static String render(String preview, int originalChars, String notice, int returnedChars) {
		Map<String, Object> marker = new LinkedHashMap<>();
		marker.put(TRUNCATED_FIELD, true);
		marker.put(STRATEGY_FIELD, STRATEGY_HEAD);
		marker.put(ORIGINAL_CHARS_FIELD, originalChars);
		marker.put(RETURNED_CHARS_FIELD, returnedChars);
		marker.put(NOTICE_FIELD, notice);
		marker.put(PREVIEW_FIELD, preview == null ? "" : preview);
		try {
			return JSON.writeValueAsString(marker);
		}
		catch (Exception ex) {
			// 只有 String/Integer/Boolean，序列化不可能失败；兜底给一份最小合法 JSON
			return "{\"truncated\":true,\"strategy\":\"head\",\"originalChars\":" + originalChars
					+ ",\"returnedChars\":" + returnedChars + ",\"notice\":\"结果已截断\",\"preview\":\"\"}";
		}
	}

}
