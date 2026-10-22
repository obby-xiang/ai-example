package com.example.configmgr.ai.resultview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果视图（设计稿 §3）：一次工具执行的产出，按<b>受控白名单</b>描述成前端可渲的形状。
 *
 * <h2>三个结构性约束（都在类型/构造面上落地，而不是靠调用方自觉）</h2>
 * <ol>
 * <li><b>数据单份</b>（§4-规则 3）：行只在 {@code data.inline} 里出现，列只描述不携带值；</li>
 * <li><b>必填 fallback</b>（§4-规则 7）：任何版本、任何降级都有可读出口，不存在"空白卡"；</li>
 * <li><b>确定性</b>（§4-规则 10）：同一输入 ⇒ 同一字节载荷 —— {@link #toMap()} 用
 * {@link LinkedHashMap} 固定键序，且不含时间戳/随机 id。</li>
 * </ol>
 *
 * <h2>它不进模型载荷</h2>
 * 本对象只走 {@code tool_result} 帧的 additive 键（UI 通道）。模型侧读的仍是
 * {@code PendingToolCall#getResultText()}，两者同源两投影（§5.3）—— 这是"不变量不进上下文"的姿态。
 */
public final class ResultView {

	/** 契约版本（major-only 版本策略，§4-规则 7）。 */
	public static final String VERSION = "1.0";

	private final String id;

	private final int revision;

	private final ResultViewKind kind;

	private final String title;

	private final String description;

	private final ResultViewStatus status;

	private final Map<String, Object> data;

	private final List<ResultViewColumn> columns;

	private final List<Map<String, Object>> actions;

	private final Map<String, Object> empty;

	private final Map<String, Object> error;

	private final Map<String, Object> a11y;

	private final Map<String, Object> fallback;

	private final Map<String, Object> meta;

	private final int rowCount;

	ResultView(String id, int revision, ResultViewKind kind, String title, String description,
			ResultViewStatus status, Map<String, Object> data, List<ResultViewColumn> columns,
			List<Map<String, Object>> actions, Map<String, Object> empty, Map<String, Object> error,
			Map<String, Object> a11y, Map<String, Object> fallback, Map<String, Object> meta, int rowCount) {
		this.id = id;
		this.revision = revision;
		this.kind = kind;
		this.title = title;
		this.description = description;
		this.status = status;
		this.data = data;
		this.columns = columns;
		this.actions = actions;
		this.empty = empty;
		this.error = error;
		this.a11y = a11y;
		this.fallback = fallback;
		this.meta = meta;
		this.rowCount = rowCount;
	}

	public String id() {
		return this.id;
	}

	public ResultViewKind kind() {
		return this.kind;
	}

	public ResultViewStatus status() {
		return this.status;
	}

	/** 实际随视图下发的行数（构造期规模闸之后的口径）。 */
	public int rowCount() {
		return this.rowCount;
	}

	/**
	 * 帧载荷形态：键序固定（快照可对账）。可空的可选键<b>不出现</b>，
	 * 而不是以 {@code null} 出现 —— 与既有 {@code preflight}/{@code context} 的 additive 口径一致。
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("version", VERSION);
		map.put("id", this.id);
		map.put("revision", this.revision);
		map.put("kind", this.kind.wire());
		if (this.title != null) {
			map.put("title", this.title);
		}
		if (this.description != null) {
			map.put("description", this.description);
		}
		map.put("status", this.status.wire());
		if (this.data != null) {
			map.put("data", this.data);
		}
		if (this.columns != null && !this.columns.isEmpty()) {
			map.put("columns", this.columns.stream().map(ResultViewColumn::toMap).toList());
		}
		if (this.actions != null && !this.actions.isEmpty()) {
			map.put("actions", this.actions);
		}
		if (this.empty != null) {
			map.put("empty", this.empty);
		}
		if (this.error != null) {
			map.put("error", this.error);
		}
		if (this.a11y != null) {
			map.put("a11y", this.a11y);
		}
		map.put("fallback", this.fallback);
		if (this.meta != null) {
			map.put("meta", this.meta);
		}
		return map;
	}
}
