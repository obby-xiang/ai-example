package com.example.configmgr.ai.resultview;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * resultView 的<b>确定性构造器 + 字段级校验</b>（设计稿 §3 / §4 / §7.2）。
 *
 * <h2>为什么构造与校验必须同处一地</h2>
 * 契约的四条安全性质（白名单 / 数据单份 / 必填 fallback / 规模上限）如果靠调用方自觉，
 * 迟早在某个工具上分叉（设计稿 R2）。因此本类是<b>唯一造口</b>：
 * <ul>
 * <li>列数 &gt; 50 ⇒ 截到 50；行数 &gt; 200 或超出字节预算 ⇒ 按预算收缩行（二分定位最大可行行数）；</li>
 * <li>截断必带三件套：{@code status=partial} + {@code meta.truncated{shownRows,totalRows}} + 实际行数三处一致；</li>
 * <li>一切字符串值<b>在出端消毒</b>：HTML 标签 / 事件处理器属性 / {@code javascript:} 一律替换为
 * {@link #REDACTED}（标题类元数据整串置空）—— 渲染器虽不 {@code v-html}，但"出端不留可执行片段"是
 * 更便宜的一道闸（§4-规则 2）；</li>
 * <li>同一输入 ⇒ 同一字节载荷（键序固定、无时间戳/随机值，§4-规则 10）。</li>
 * </ul>
 *
 * <h2>与帧预算的关系（§6.3-3 / R1）</h2>
 * {@code resultView} 是 {@code FrameSizeLimiter} 的<b>不可截字段</b>（只截 {@code text}/{@code result}），
 * 所以必须在构造期自查：调用方传入的 {@code maxBytes} 应为
 * {@code min(契约上限 256KB, 单帧预算 − 其余字段余量)}。超限时收缩行数，<b>不</b>放宽帧截断面。
 */
public final class ResultViews {

	/** 契约上限：行数（调研建议值，§4-规则 8）。 */
	public static final int MAX_ROWS = 200;

	/** 契约上限：列数。 */
	public static final int MAX_COLUMNS = 50;

	/** 契约上限：UTF-8 字节（按数据单份计）。 */
	public static final int MAX_BYTES = 256 * 1024;

	/** 宿主组件目录标识（v1 只有这一个目录；多目录未实现前不作为判别依据，§8.1-R10）。 */
	public static final String CATALOG_ID = "configmgr/resultview/v1";

	/** 消毒占位符（与前端 {@code SANITIZE_PLACEHOLDER} 同值）。 */
	public static final String REDACTED = "[redacted]";

	/** 降级错误码（{@code status=error} 的稳定码）。 */
	public static final String CODE_UNSUPPORTED = "RESULT_VIEW_UNSUPPORTED";

	/**
	 * 禁形：HTML 标签（可执行/可外联的那几个）、{@code javascript:} 协议、事件处理器属性。
	 *
	 * <p>刻意<b>不</b>拦裸 {@code <}/{@code >}：配置值里出现 {@code 值 <ABC> 不合法} 是正常业务文本，
	 * 见 {@code message} 一类的自由文本字段 —— 拦裸尖括号会把正常结果误判成攻击。
	 */
	private static final Pattern FORBIDDEN_MARKUP = Pattern.compile(
			"(?i)<\\s*/?\\s*(script|iframe|object|embed|svg|style|link|meta|base)\\b[^>]*>?|javascript\\s*:|\\bon[a-z]+\\s*=");

	private static final ObjectMapper JSON = new ObjectMapper();

	private ResultViews() {
	}

	// ── 造口：table ─────────────────────────────────────────────────────────

	public static ResultView table(String id, String title, List<ResultViewColumn> columns,
			List<Map<String, Object>> rows, String fallbackText, String source) {
		return table(id, title, columns, rows, fallbackText, source, MAX_BYTES);
	}

	public static ResultView table(String id, String title, List<ResultViewColumn> columns,
			List<Map<String, Object>> rows, String fallbackText, String source, int maxBytes) {
		return table(id, title, columns, rows, fallbackText, source, maxBytes, -1);
	}

	/**
	 * 逐工具已先自行裁行的造口（如 {@code list_tasks} 只取前 20 条）：{@code totalRows} 是
	 * <b>真实总数</b>，供截断三件套如实表达"还有多少没展示"——传 {@code -1} = 以 {@code rows.size()} 为准。
	 */
	public static ResultView table(String id, String title, List<ResultViewColumn> columns,
			List<Map<String, Object>> rows, String fallbackText, String source, int maxBytes, int totalRows) {
		String safeFallback = requiredText(fallbackText);
		String safeTitle = optionalText(title);
		List<ResultViewColumn> safeColumns = sanitizeColumns(columns);
		if (safeColumns.isEmpty()) {
			// 列定义缺失 ⇒ 不猜类型，降级为可读出口（§4-规则 5-①）
			return degraded(id, safeFallback);
		}
		List<Map<String, Object>> safeRows = sanitizeRows(rows);
		int total = Math.max(totalRows, safeRows.size());
		int byteBudget = maxBytes > 0 ? maxBytes : MAX_BYTES;

		if (total == 0) {
			return new ResultView(id, 1, ResultViewKind.TABLE, safeTitle, null, ResultViewStatus.EMPTY,
					inlineData(List.of()), safeColumns, null, Map.of("text", safeFallback), null,
					a11y("table", safeTitle), fallback(safeFallback), meta(source, null), 0);
		}

		int cap = Math.min(safeRows.size(), MAX_ROWS);
		int shown = largestFitting(id, safeTitle, safeColumns, safeRows, safeFallback, source, cap, byteBudget, total);
		if (shown == 0) {
			// 连一行的载荷都放不进预算 ⇒ 契约层的 fallback 出口（绝不发超预算的不可渲载荷）
			return degraded(id, safeFallback);
		}
		ResultViewStatus status = shown < total ? ResultViewStatus.PARTIAL : ResultViewStatus.OK;
		Map<String, Object> truncated = shown < total
				? Map.of("shownRows", shown, "totalRows", total)
				: null;
		return new ResultView(id, 1, ResultViewKind.TABLE, safeTitle, null, status,
				inlineData(safeRows.subList(0, shown)), safeColumns, null, null, null,
				a11y("table", safeTitle), fallback(safeFallback), meta(source, truncated), shown);
	}

	// ── 造口：text / degraded ───────────────────────────────────────────────

	/** 只有一句话的结果（替换"把 JSON / 明细塞进 reason"的历史做法，§3.3）。 */
	public static ResultView text(String id, String title, String text, String fallbackText) {
		String safeFallback = requiredText(fallbackText);
		return new ResultView(id, 1, ResultViewKind.TEXT, optionalText(title), optionalText(text),
				ResultViewStatus.OK, null, null, null, null, null, a11y("text", optionalText(title)),
				fallback(safeFallback), meta(null, null), 0);
	}

	/** 无内容可描述的降级出口：{@code kind=text} + {@code status=error} + 必填 fallback。 */
	public static ResultView degraded(String id, String fallbackText) {
		String safeFallback = requiredText(fallbackText);
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", CODE_UNSUPPORTED);
		error.put("message", safeFallback);
		error.put("retryable", false);
		return new ResultView(id, 1, ResultViewKind.TEXT, null, null, ResultViewStatus.ERROR,
				null, null, null, null, error, null, fallback(safeFallback), meta(null, null), 0);
	}

	// ── 规模闸 ──────────────────────────────────────────────────────────────

	/** 二分出"预算内能放下几行"（行数单调 ⇒ 单调函数，O(log n) 次序列化）。 */
	private static int largestFitting(String id, String title, List<ResultViewColumn> columns,
			List<Map<String, Object>> rows, String fallback, String source, int cap, int byteBudget, int total) {
		if (bytesOf(id, title, columns, rows, fallback, source, cap, total) <= byteBudget) {
			return cap;
		}
		int low = 0;
		int high = cap;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			if (bytesOf(id, title, columns, rows, fallback, source, mid, total) <= byteBudget) {
				low = mid;
			}
			else {
				high = mid - 1;
			}
		}
		return low;
	}

	private static int bytesOf(String id, String title, List<ResultViewColumn> columns,
			List<Map<String, Object>> rows, String fallback, String source, int shown, int total) {
		Map<String, Object> truncated = shown < total ? Map.of("shownRows", shown, "totalRows", total) : null;
		ResultView candidate = new ResultView(id, 1, ResultViewKind.TABLE, title, null,
				shown < total ? ResultViewStatus.PARTIAL : ResultViewStatus.OK,
				inlineData(rows.subList(0, shown)), columns, null, null, null, a11y("table", title),
				fallback(fallback), meta(source, truncated), shown);
		try {
			return JSON.writeValueAsString(candidate.toMap()).getBytes(StandardCharsets.UTF_8).length;
		}
		catch (Exception ex) {
			return Integer.MAX_VALUE;
		}
	}

	// ── 消毒 ────────────────────────────────────────────────────────────────

	/** 是否含禁形（HTML 可执行标签 / 事件处理器 / 伪协议）。 */
	public static boolean containsForbiddenMarkup(String text) {
		return text != null && FORBIDDEN_MARKUP.matcher(text).find();
	}

	/** 自由文本消毒：命中的片段替换为 {@link #REDACTED}（保留其余业务文本的形状）。 */
	public static String sanitize(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		return FORBIDDEN_MARKUP.matcher(text).replaceAll(REDACTED);
	}

	private static String optionalText(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		return containsForbiddenMarkup(text) ? null : text;
	}

	private static String requiredText(String text) {
		if (text == null || text.isBlank()) {
			return "i18n:resultview.blocked";
		}
		return sanitize(text);
	}

	private static List<ResultViewColumn> sanitizeColumns(List<ResultViewColumn> columns) {
		if (columns == null) {
			return List.of();
		}
		List<ResultViewColumn> out = new ArrayList<>();
		for (ResultViewColumn column : columns) {
			if (out.size() >= MAX_COLUMNS) {
				break;
			}
			if (column == null || column.key() == null || column.key().isBlank() || column.type() == null) {
				// 列定义不合法 ⇒ 丢掉这一列（不猜类型），全丢则整视图降级
				continue;
			}
			out.add(new ResultViewColumn(column.key(), column.type(), optionalText(column.label()),
					column.format(), column.width(), column.align(), sanitizeOptions(column.options()), column.hidden()));
		}
		return List.copyOf(out);
	}

	private static List<Map<String, Object>> sanitizeOptions(List<Map<String, Object>> options) {
		if (options == null) {
			return null;
		}
		List<Map<String, Object>> out = new ArrayList<>(options.size());
		for (Map<String, Object> option : options) {
			@SuppressWarnings("unchecked")
			Map<String, Object> safe = (Map<String, Object>) sanitizeValue(option);
			out.add(safe);
		}
		return List.copyOf(out);
	}

	private static List<Map<String, Object>> sanitizeRows(List<Map<String, Object>> rows) {
		if (rows == null) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			if (row == null) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> safe = (Map<String, Object>) sanitizeValue(row);
			out.add(safe);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Object sanitizeValue(Object value) {
		if (value instanceof String text) {
			return sanitize(text);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> out = new LinkedHashMap<>();
			map.forEach((key, item) -> out.put(String.valueOf(key), sanitizeValue(item)));
			return out;
		}
		if (value instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			list.forEach(item -> out.add(sanitizeValue(item)));
			return out;
		}
		return value;
	}

	// ── 小装配件 ────────────────────────────────────────────────────────────

	private static Map<String, Object> inlineData(List<Map<String, Object>> rows) {
		Map<String, Object> inline = new LinkedHashMap<>();
		inline.put("rows", List.copyOf(rows));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("inline", inline);
		return data;
	}

	private static Map<String, Object> fallback(String text) {
		Map<String, Object> fallback = new LinkedHashMap<>();
		fallback.put("text", text);
		fallback.put("format", "plain");
		return fallback;
	}

	private static Map<String, Object> a11y(String role, String title) {
		Map<String, Object> a11y = new LinkedHashMap<>();
		if (title != null) {
			a11y.put("label", title);
		}
		a11y.put("role", role);
		return a11y;
	}

	private static Map<String, Object> meta(String source, Map<String, Object> truncated) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("catalogId", CATALOG_ID);
		if (source != null && !source.isBlank()) {
			meta.put("source", source);
		}
		if (truncated != null) {
			meta.put("truncated", truncated);
		}
		return meta;
	}
}
