package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 表格审计面的字段白名单与计数（AI 表格工具治理方案 2026-10-22 §4.2 / §4.3 / §4.4）。
 *
 * <h2>本类守的是什么</h2>
 * 治理批最容易被后来人"顺手放宽"的两处：
 * <ol>
 * <li><b>字段白名单</b>：{@code sheetMeta} 是唯一从浏览器带上来的块，多加一个 {@code values}/
 * {@code predicate} 就等于把业务值送进日志与台账（违立法性裁决「不落库」的日志面口径）。
 * 这里逐键负断言：白名单外的键<b>一律被丢</b>，包括看起来"只是辅助"的 scopeSummary；</li>
 * <li><b>台账 kind</b>：必须 {@code sheet_sample}，不得混用 {@code dlp}（会让
 * {@code /health.security} 的"被拦"计数失真）。</li>
 * </ol>
 */
class SheetAuditTest {

	private static SheetAudit audit() {
		return new SheetAudit(new SheetSwitches(new AiProperties()), mock(RunStore.class));
	}

	private static PendingToolCall pending(String name, Map<String, Object> meta) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-1");
		pending.setName(name);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setExecuted(true);
		pending.setSheetMeta(meta);
		return pending;
	}

	private static Map<String, Object> fullMeta() {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("dataClass", "D2");
		meta.put("sampleRows", 5);
		meta.put("sampleChars", 412);
		meta.put("projCols", 3);
		meta.put("totalRows", 3120);
		meta.put("sheetVer", 7);
		meta.put("planId", "plan-abc-1");
		meta.put("previewLevel", "sample-diff");
		meta.put("defCode", "CURRENCY");
		meta.put("page", "task:EXPORT/EXPORT");
		return meta;
	}

	// ── 白名单 ──────────────────────────────────────────────────────────────

	@Test
	void whitelistKeepsMetadataAndDropsEverythingElse() {
		Map<String, Object> raw = fullMeta();
		// 这些是明令禁止的字段形态（单元格值 / 条件取值 / scopeSummary 原文）
		raw.put("values", java.util.List.of("华东", 1234));
		raw.put("cellValue", "业务值不要进日志");
		raw.put("predicate", Map.of("fieldCode", "region", "value", "华东"));
		raw.put("scopeSummary", "CURRENCY 条件「region EQ 华东」");

		Map<String, Object> sanitized = SheetAudit.sanitize(raw);

		assertThat(sanitized).containsOnlyKeys(SheetAudit.ALLOWED_FIELDS.toArray(String[]::new));
		assertThat(sanitized).doesNotContainKeys("values", "cellValue", "predicate", "scopeSummary");
		assertThat(sanitized.toString()).doesNotContain("华东").doesNotContain("业务值");
		assertThat(sanitized).containsEntry("dataClass", "D2")
			.containsEntry("sampleRows", 5L)
			.containsEntry("totalRows", 3120L)
			.containsEntry("sheetVer", 7L)
			.containsEntry("planId", "plan-abc-1");
	}

	@Test
	void invalidValuesAreDroppedRatherThanGuessed() {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("dataClass", "D9");            // 非法分级
		raw.put("sampleRows", -3);             // 负行数
		raw.put("sampleChars", "不是数字");      // 非数字
		raw.put("previewLevel", "pretty-diff"); // 非法档位
		raw.put("planId", "x".repeat(100));    // 超长 ⇒ 截断，不丢

		Map<String, Object> sanitized = SheetAudit.sanitize(raw);

		assertThat(sanitized).doesNotContainKeys("dataClass", "sampleRows", "sampleChars", "previewLevel");
		assertThat((String) sanitized.get("planId")).hasSize(64);
	}

	@Test
	void emptyOrNullMetaSanitizesToEmpty() {
		assertThat(SheetAudit.sanitize(null)).isEmpty();
		assertThat(SheetAudit.sanitize(Map.of())).isEmpty();
	}

	/** 日志面必须<b>恰好</b>是设计稿 §4.2 的 8 字段（顺序固定，便于 grep）。 */
	@Test
	void logPairsCoverExactlyEightFieldsInOrder() {
		assertThat(SheetAudit.LOG_FIELDS).containsExactly("dataClass", "sampleRows", "sampleChars", "projCols",
				"totalRows", "sheetVer", "planId", "previewLevel");
		Object[] pairs = SheetAudit.logPairs(fullMeta()).toArray();
		assertThat(pairs).hasSize(16);
		assertThat(pairs[0]).isEqualTo("dataClass");
		assertThat(pairs[1]).isEqualTo("D2");
		assertThat(pairs[12]).isEqualTo("planId");
		assertThat(pairs[13]).isEqualTo("plan-abc-1");
		// 缺席的键照样占位（值是 null ⇒ 日志渲染成 '-'），保证字段集合稳定
		Object[] missing = SheetAudit.logPairs(Map.of()).toArray();
		assertThat(missing).hasSize(16);
		assertThat(missing[1]).isNull();
	}

	// ── 台账 ────────────────────────────────────────────────────────────────

	@Test
	void ledgerEntryIsSheetSampleAndCarriesNoBusinessValue() {
		Map<String, Object> entry = SheetAudit.ledgerEntry(pending("read_sheet_summary", fullMeta()));

		assertThat(entry).containsEntry("kind", "sheet_sample")
			.containsEntry("tool", "read_sheet_summary")
			.containsEntry("page", "task:EXPORT/EXPORT")
			.containsEntry("defCode", "CURRENCY")
			.containsEntry("sampleRows", 5L)
			.containsEntry("totalRows", 3120L)
			.containsEntry("sheetVer", 7L);
		assertThat(entry).doesNotContainKeys("planId", "previewLevel", "dataClass");
		assertThat(entry).doesNotContainKey("values");
		assertThat(entry.get("atMs")).isInstanceOf(Long.class);
	}

	// ── 计数 ────────────────────────────────────────────────────────────────

	@Test
	void metricsStartAtZeroWithProcessScope() {
		Map<String, Object> metrics = audit().metrics();

		assertThat(metrics).containsEntry("scope", "process")
			.containsEntry("readCalls", 0L)
			.containsEntry("writePlans", 0L)
			.containsEntry("writeApplied", 0L)
			.containsEntry("writeCancelled", 0L)
			.containsEntry("undoCalls", 0L)
			.containsEntry("sampleRowsTotal", 0L)
			.containsEntry("sampleCharsMax", 0L)
			.containsEntry("disabledRejects", 0L);
	}

	@Test
	void readCallsAccumulateSampleVolume() {
		SheetAudit audit = audit();

		audit.record("s-1", pending("read_sheet_summary", fullMeta()));
		Map<String, Object> second = fullMeta();
		second.put("sampleRows", 2);
		second.put("sampleChars", 100);
		audit.record("s-1", pending("read_sheet_summary", second));

		Map<String, Object> metrics = audit.metrics();
		assertThat(metrics).containsEntry("readCalls", 2L)
			.containsEntry("sampleRowsTotal", 7L)
			// 峰值不累加：它是"单次最大"而不是体积总和
			.containsEntry("sampleCharsMax", 412L);
	}

	@Test
	void writeToolsCountPlansAppliedAndCancelled() {
		SheetAudit audit = audit();

		audit.record("s-1", pending("replace_values", fullMeta()));
		PendingToolCall cancelled = pending("update_rows", fullMeta());
		cancelled.setOutcome(ToolOutcome.CANCELLED.wire());
		cancelled.setStatus(PendingToolCall.FRONTEND_CANCELLED);
		audit.record("s-1", cancelled);
		audit.record("s-1", pending("undo_last_sheet_edit", Map.of("defCode", "CURRENCY")));

		Map<String, Object> metrics = audit.metrics();
		assertThat(metrics).containsEntry("writePlans", 2L)
			.containsEntry("writeApplied", 1L)
			.containsEntry("writeCancelled", 1L)
			.containsEntry("undoCalls", 1L);
	}

	@Test
	void pendingWithoutMetaIsIgnored() {
		SheetAudit audit = audit();

		audit.record("s-1", pending("read_sheet_summary", null));

		assertThat(audit.metrics()).containsEntry("readCalls", 0L);
	}

	@Test
	void coversOnlyTheNineSheetTools() {
		SheetAudit audit = audit();

		assertThat(audit.covers("read_sheet_summary")).isTrue();
		assertThat(audit.covers("undo_last_sheet_edit")).isTrue();
		assertThat(audit.covers("ui_snapshot")).isFalse();
		assertThat(audit.covers("list_tasks")).isFalse();
	}

}
