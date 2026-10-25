package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 表格采样/改动的审计面（AI 表格工具治理方案 2026-10-22 §4）。
 *
 * <h2>三条硬约束（先于实现）</h2>
 * <ol>
 * <li><b>不落库</b>（立法性裁决 2026-10-21，{@code docs/决策看板.md:21}）：只许指标与元数据；</li>
 * <li><b>零组件日志面</b>（{@code docs/决策看板.md:42}）：不引 OTel / Micrometer，计数用
 * {@link AtomicLong} 手写快照（与 {@code SecurityEvents} 同一范式）；</li>
 * <li><b>不新增事件名、不新增键族、不新增持久层</b>：采样事实挂既有 {@code tool_call_end} 的
 * additive 字段（由 {@code gate/ToolCallEndTrace} 这一处载荷构造点发射 —— 内联批循环与 park 结算
 * 共用同一份键序）、台账复用 {@code ai:safety:<sessionId>}
 * 的 {@code kind=sheet_sample} 条目、计数挂 {@code /health.sheet}。</li>
 * </ol>
 *
 * <h2>字段白名单是"入口"，不是"出口"</h2>
 * {@link #sanitize(Map)} 只保留 {@link #ALLOWED_FIELDS} 里的键并逐键归一；<b>其余一律丢弃</b> ——
 * 于是"日志/台账里出现单元格值、条件取值、scopeSummary 原文"这类违例在结构上不可表达，
 * 而不是靠评审记得不去写。白名单的两个增补键 {@code defCode}/{@code page} 是 D1 标识
 * （配置定义编码 / 页面 id），只进台账、不进日志（日志面严格等于设计稿 §4.2 的 8 字段）。
 */
@Slf4j
@Component
public class SheetAudit {

	/**
	 * 允许随工具结果上送的元数据键（白名单；多一个键都不落）。
	 *
	 * <p>前 8 个 = 设计稿 §4.2 的 {@code tool_call_end} additive 字段；后 2 个 = 台账条目
	 * （§4.3）的定位标识（D1，非业务值）。
	 */
	public static final List<String> ALLOWED_FIELDS = List.of("dataClass", "sampleRows", "sampleChars",
			"projCols", "totalRows", "sheetVer", "planId", "previewLevel", "defCode", "page");

	/** 进日志面的 8 字段（顺序固定，便于 grep 与用例断言）。 */
	public static final List<String> LOG_FIELDS = ALLOWED_FIELDS.subList(0, 8);

	/** 预览档枚举（与前端 {@code SheetPreviewLevel} 同值）。 */
	private static final List<String> PREVIEW_LEVELS = List.of("count", "sample-diff", "full-diff");

	private final SheetSwitches switches;

	private final RunStore store;

	private final AtomicLong readCalls = new AtomicLong();

	private final AtomicLong writePlans = new AtomicLong();

	private final AtomicLong writeApplied = new AtomicLong();

	private final AtomicLong writeCancelled = new AtomicLong();

	private final AtomicLong undoCalls = new AtomicLong();

	private final AtomicLong sampleRowsTotal = new AtomicLong();

	private final AtomicLong sampleCharsMax = new AtomicLong();

	private final AtomicLong disabledRejects = new AtomicLong();

	@Autowired
	public SheetAudit(SheetSwitches switches, RunStore store) {
		this.switches = switches;
		this.store = store;
	}

	/**
	 * 白名单过滤 + 逐键归一（唯一入口）。
	 *
	 * <p>归一规则：数值键必须是非负整数（负数/非数字 ⇒ 丢弃该键）；{@code dataClass} 只接受
	 * {@code D1}/{@code D2}；{@code previewLevel} 只接受三档；{@code planId} 截到 64 字符。
	 * <b>不做"猜测式修复"</b>：拿不准的键宁可缺席（日志里是 {@code -}），也不写一个错值。
	 */
	public static Map<String, Object> sanitize(Map<String, Object> raw) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		Object dataClass = raw.get("dataClass");
		if ("D1".equals(dataClass) || "D2".equals(dataClass)) {
			out.put("dataClass", dataClass);
		}
		for (String numeric : List.of("sampleRows", "sampleChars", "projCols", "totalRows", "sheetVer")) {
			Long value = nonNegative(raw.get(numeric));
			if (value != null) {
				out.put(numeric, value);
			}
		}
		Object planId = raw.get("planId");
		if (planId instanceof String text && !text.isBlank()) {
			out.put("planId", text.length() > 64 ? text.substring(0, 64) : text);
		}
		Object previewLevel = raw.get("previewLevel");
		if (previewLevel instanceof String level && PREVIEW_LEVELS.contains(level)) {
			out.put("previewLevel", level);
		}
		for (String label : List.of("defCode", "page")) {
			Object value = raw.get(label);
			if (value instanceof String text && !text.isBlank() && text.length() <= 128) {
				out.put(label, text);
			}
		}
		return out;
	}

	private static Long nonNegative(Object value) {
		if (value instanceof Number number) {
			double asDouble = number.doubleValue();
			if (asDouble < 0 || Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
				return null;
			}
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				long parsed = Long.parseLong(text.trim());
				return parsed < 0 ? null : parsed;
			}
			catch (NumberFormatException error) {
				return null;
			}
		}
		return null;
	}

	/**
	 * 记一次表格工具结算（{@code tool_call_end} 的同一时点；由 {@code gate/ToolCallEndTrace} 在
	 * 两个所有权边界上各调一次：内联批循环、park 结算的胜者 —— 决策 guard 或执行认领）。
	 *
	 * <p>计数口径（进程内，多实例各一份 —— 与 {@code /health.security} 同一缺口，见 §4.4）：
	 * <ul>
	 * <li>读工具结算 ⇒ {@code readCalls}；</li>
	 * <li>写工具结算且带 {@code planId} ⇒ {@code writePlans}；结局 ok ⇒ {@code writeApplied}；
	 * 结局 cancelled ⇒ {@code writeCancelled}；</li>
	 * <li>撤销工具 ⇒ {@code undoCalls}；</li>
	 * <li>任何带采样事实的结算 ⇒ {@code sampleRowsTotal} 累加、{@code sampleCharsMax} 取峰值。</li>
	 * </ul>
	 */
	public void record(String sessionId, PendingToolCall pending) {
		if (pending == null) {
			return;
		}
		Map<String, Object> meta = pending.getSheetMeta();
		if (meta == null || meta.isEmpty()) {
			return;
		}
		String name = pending.getName();
		ToolOutcome outcome = pending.effectiveOutcome();
		if (SheetSwitches.READ_TOOLS.contains(name)) {
			this.readCalls.incrementAndGet();
		}
		else if ("undo_last_sheet_edit".equals(name)) {
			this.undoCalls.incrementAndGet();
		}
		else if (SheetSwitches.WRITE_TOOLS.contains(name) || SheetSwitches.DUAL_TOOLS.contains(name)) {
			if (meta.containsKey("planId")) {
				this.writePlans.incrementAndGet();
			}
			if (outcome == ToolOutcome.OK) {
				this.writeApplied.incrementAndGet();
			}
			else if (outcome == ToolOutcome.CANCELLED) {
				this.writeCancelled.incrementAndGet();
			}
		}
		Object rows = meta.get("sampleRows");
		if (rows instanceof Number number && number.longValue() > 0) {
			this.sampleRowsTotal.addAndGet(number.longValue());
		}
		Object chars = meta.get("sampleChars");
		if (chars instanceof Number number) {
			this.sampleCharsMax.accumulateAndGet(number.longValue(), Math::max);
		}
		if ("D2".equals(meta.get("dataClass"))) {
			appendLedger(sessionId, pending, meta);
		}
	}

	/** 开关关掉后被拒的次数（应恒 0；非 0 = 有旧缓存调用或在途残留）。 */
	public void disabledReject() {
		this.disabledRejects.incrementAndGet();
	}

	/**
	 * 台账条目（{@code ai:safety:<sessionId>}，{@code kind=sheet_sample}）。
	 *
	 * <p><b>不复用 {@code kind=dlp}</b>：安全台账的既有语义是"命中规则/被拦"，采样是"按授权放行"，
	 * 混在一起会让 {@code /health.security} 的七个计数失真（§4.3）。
	 */
	public static Map<String, Object> ledgerEntry(PendingToolCall pending) {
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> meta = sanitize(pending.getSheetMeta());
		out.put("kind", "sheet_sample");
		out.put("atMs", System.currentTimeMillis());
		out.put("tool", pending.getName());
		for (String key : List.of("page", "defCode", "sampleRows", "sampleChars", "projCols", "totalRows",
				"sheetVer")) {
			if (meta.containsKey(key)) {
				out.put(key, meta.get(key));
			}
		}
		return out;
	}

	private void appendLedger(String sessionId, PendingToolCall pending, Map<String, Object> meta) {
		if (this.store == null) {
			return;
		}
		if (sessionId == null || sessionId.isBlank()) {
			// 没有会话锚点 = 无法落到随会话共亡的键上：宁可不记，也不写一个没有归属的台账
			log.warn("表格采样台账跳过：缺少 sessionId tool={}", pending.getName());
			return;
		}
		try {
			this.store.appendSafetyEvent(sessionId, ledgerEntry(pending));
		}
		catch (RuntimeException error) {
			// appendSafetyEvent 自身已吞异常；这里只防"store 是桩/未装配"的实现差异
			log.warn("表格采样台账写入失败 tool={}：{}（不影响本轮）", pending.getName(), error.getMessage());
		}
	}

	/**
	 * 进程内计数快照（{@code /health.sheet.counters}；顺序固定，便于阅读与用例断言）。
	 *
	 * <p>口径诚实声明：多实例下是各实例视图；<b>不承诺</b>用于计费或审计结论 —— 审计结论以台账
	 * （{@code ai:safety:}）与日志为准。
	 */
	public Map<String, Object> metrics() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", "process");
		out.put("readCalls", this.readCalls.get());
		out.put("writePlans", this.writePlans.get());
		out.put("writeApplied", this.writeApplied.get());
		out.put("writeCancelled", this.writeCancelled.get());
		out.put("undoCalls", this.undoCalls.get());
		out.put("sampleRowsTotal", this.sampleRowsTotal.get());
		out.put("sampleCharsMax", this.sampleCharsMax.get());
		out.put("disabledRejects", this.disabledRejects.get());
		return out;
	}

	/** 日志面 8 字段的成对键值（供 {@code AiTrace.eventOn} 展开；缺席键渲染为 {@code -}）。 */
	public static List<Object> logPairs(Map<String, Object> meta) {
		List<Object> pairs = new ArrayList<>(LOG_FIELDS.size() * 2);
		for (String key : LOG_FIELDS) {
			pairs.add(key);
			pairs.add(meta == null ? null : meta.get(key));
		}
		return pairs;
	}

	/** 该工具名是否受本审计面治理（= 9 个表格工具）。 */
	public boolean covers(String toolName) {
		return this.switches != null && SheetSwitches.SHEET_TOOLS.contains(toolName);
	}

}
