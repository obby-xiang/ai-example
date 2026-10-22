package com.example.configmgr.ai.resultview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resultView 契约模型（设计稿 §3 / §4 / §7.2）的后端锁。
 *
 * <p>kill 一览：改 {@link ResultViews#table} 的字段装配 / 上限常量 / 截断三件套 / 文本消毒，
 * 本类必红；改成"每次都新建随机 id / 时间戳"，确定性用例必红。
 */
class ResultViewTest {

	private static final String FALLBACK = "i18n:resultview.test.fallback";

	private static final ObjectMapper JSON = new ObjectMapper();

	// ── 骨架：§3.2 必填字段与 data.inline 单份 ───────────────────────────────

	@Test
	void tableViewCarriesTheContractSkeleton() {
		ResultView view = ResultViews.table("rv-call_1", "i18n:resultview.test.title",
				List.of(ResultViewColumn.of("defCode", ResultViewColumnType.STRING, "i18n:table.col.defCode", 4),
						ResultViewColumn.of("rowKey", ResultViewColumnType.STRING, "i18n:table.col.rowKey", 3),
						ResultViewColumn.of("message", ResultViewColumnType.STRING, "i18n:table.col.message", 5)),
				List.of(row("CFG_A", "XN-001", "版本冲突")), FALLBACK, "validation_issue");

		Map<String, Object> map = view.toMap();
		assertThat(map).containsEntry("version", "1.0")
				.containsEntry("id", "rv-call_1")
				.containsEntry("revision", 1)
				.containsEntry("kind", "table")
				.containsEntry("status", "ok");
		assertThat(map.get("title")).isEqualTo("i18n:resultview.test.title");
		assertThat(map).containsKey("fallback");

		@SuppressWarnings("unchecked")
		Map<String, Object> fallback = (Map<String, Object>) map.get("fallback");
		assertThat(fallback).containsEntry("text", FALLBACK).containsEntry("format", "plain");

		// §4-规则 3 数据单份：行只在 data.inline 里出现，列只描述不携带值
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) map.get("data");
		assertThat(data).containsOnlyKeys("inline");
		@SuppressWarnings("unchecked")
		Map<String, Object> inline = (Map<String, Object>) data.get("inline");
		assertThat(inline).containsOnlyKeys("rows");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> columns = (List<Map<String, Object>>) map.get("columns");
		assertThat(columns).hasSize(3);
		assertThat(columns.getFirst()).containsEntry("key", "defCode")
				.containsEntry("type", "string")
				.containsEntry("label", "i18n:table.col.defCode")
				.containsEntry("width", 4);
		assertThat(columns.getFirst()).doesNotContainKey("value");
		assertThat(JSON.valueToTree(map).toString()).doesNotContain("\"value\"");

		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) map.get("meta");
		assertThat(meta).containsEntry("catalogId", "configmgr/resultview/v1")
				.containsEntry("source", "validation_issue");
		assertThat(meta).doesNotContainKey("truncated");
	}

	@Test
	void sameInputProducesTheSameBytes() throws Exception {
		ResultView first = buildSimple();
		ResultView second = buildSimple();
		assertThat(JSON.writeValueAsString(second.toMap()))
				.as("§4-规则 10：同一输入 ⇒ 同一字节载荷（可快照对账）")
				.isEqualTo(JSON.writeValueAsString(first.toMap()));
	}

	@Test
	void emptyRowsDegradeToEmptyStatusWithText() {
		ResultView view = ResultViews.table("rv-empty", null,
				List.of(ResultViewColumn.of("defCode", ResultViewColumnType.STRING, null, null)),
				List.of(), FALLBACK, "list_tasks");

		Map<String, Object> map = view.toMap();
		assertThat(map).containsEntry("status", "empty");
		assertThat(map).containsKey("empty");
		@SuppressWarnings("unchecked")
		Map<String, Object> empty = (Map<String, Object>) map.get("empty");
		assertThat(empty.get("text")).as("§3.2：status=empty 必填 empty.text").isNotNull();
	}

	// ── 规模上限：§4-规则 8（截断 + partial + meta.truncated 三件同真） ──────

	@Test
	void rowsBeyondTheLimitAreTruncatedWithAllThreeMarkers() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < 250; i++) {
			rows.add(row("CFG_" + i, "RK-" + i, "第 " + i + " 行"));
		}
		ResultView view = ResultViews.table("rv-big", null,
				List.of(ResultViewColumn.of("defCode", ResultViewColumnType.STRING, null, null),
						ResultViewColumn.of("rowKey", ResultViewColumnType.STRING, null, null),
						ResultViewColumn.of("message", ResultViewColumnType.STRING, null, null)),
				rows, FALLBACK, "validation_issue");

		Map<String, Object> map = view.toMap();
		assertThat(rowsOf(map)).hasSize(ResultViews.MAX_ROWS);
		assertThat(map).containsEntry("status", "partial");
		@SuppressWarnings("unchecked")
		Map<String, Object> truncated = (Map<String, Object>) ((Map<String, Object>) map.get("meta")).get("truncated");
		assertThat(truncated).containsEntry("shownRows", ResultViews.MAX_ROWS).containsEntry("totalRows", 250);
	}

	@Test
	void columnsBeyondTheLimitAreDropped() {
		List<ResultViewColumn> columns = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			columns.add(ResultViewColumn.of("c" + i, ResultViewColumnType.STRING, null, null));
		}
		Map<String, Object> map = ResultViews.table("rv-wide", null, columns,
				List.of(Map.of("c0", "v")), FALLBACK, "test").toMap();
		assertThat((List<?>) map.get("columns")).hasSize(ResultViews.MAX_COLUMNS);
	}

	@Test
	void byteBudgetShrinksRowsInsteadOfBreakingTheFrame() throws Exception {
		// 200 行 × 2KB 的载荷 ≈ 400KB > 256KB ⇒ 必须在构造期收敛到预算内（不许靠帧级截断兜底）
		List<Map<String, Object>> rows = new ArrayList<>();
		String chunk = "配".repeat(700);
		for (int i = 0; i < 200; i++) {
			rows.add(row("CFG_" + i, "RK-" + i, chunk));
		}
		int budget = 64 * 1024;
		ResultView view = ResultViews.table("rv-huge", null,
				List.of(ResultViewColumn.of("defCode", ResultViewColumnType.STRING, null, null),
						ResultViewColumn.of("rowKey", ResultViewColumnType.STRING, null, null),
						ResultViewColumn.of("message", ResultViewColumnType.STRING, null, null)),
				rows, FALLBACK, "validation_issue", budget);

		Map<String, Object> map = view.toMap();
		int bytes = JSON.writeValueAsString(map).getBytes(StandardCharsets.UTF_8).length;
		assertThat(bytes).as("构造期规模闸：落在注入的字节预算内").isLessThanOrEqualTo(budget);
		assertThat(map).containsEntry("status", "partial");
		@SuppressWarnings("unchecked")
		Map<String, Object> truncated = (Map<String, Object>) ((Map<String, Object>) map.get("meta")).get("truncated");
		assertThat(truncated).containsEntry("totalRows", 200);
		assertThat(((Number) truncated.get("shownRows")).intValue()).isPositive()
				.isLessThan(200);
		assertThat(view.rowCount()).isEqualTo(((Number) truncated.get("shownRows")).intValue());
	}

	// ── 禁 HTML / 事件串：§4-规则 2 ──────────────────────────────────────────

	@Test
	void scriptAndEventHandlerStringsAreNeutralisedInThePayload() throws Exception {
		ResultView view = ResultViews.table("rv-xss", "<script>alert(1)</script>",
				List.of(ResultViewColumn.of("defCode", ResultViewColumnType.STRING, null, null),
						ResultViewColumn.of("message", ResultViewColumnType.STRING, null, null)),
				List.of(row("<script>alert(2)</script>", "x", "javascript:alert(3)")),
				"<iframe src=//evil></iframe>", "validation_issue");

		String json = JSON.writeValueAsString(view.toMap());
		assertThat(json).doesNotContain("<script").doesNotContain("<iframe")
				.doesNotContain("javascript:");
		assertThat(view.toMap().get("title")).as("标题撞禁词 ⇒ 置空，绝不留半截标签").isNull();
		assertThat(JSON.valueToTree(view.toMap()).toString()).contains(ResultViews.REDACTED);
	}

	@Test
	void eventHandlerAttributeInACellIsNeutralised() throws Exception {
		ResultView view = ResultViews.table("rv-on", null,
				List.of(ResultViewColumn.of("message", ResultViewColumnType.STRING, null, null)),
				List.of(Map.of("message", "配置 onerror = alert(1) 不合法")), FALLBACK, "validation_issue");
		String json = JSON.writeValueAsString(view.toMap());
		assertThat(json.toLowerCase()).doesNotContain("onerror =");
	}

	// ── 未知枚举一律降级（不抛错）：§4-规则 1 / §7.2-3 ───────────────────────

	@Test
	void unknownKindAndColumnTypeAreReportedAsUnsupportedNotThrown() {
		assertThat(ResultViewKind.fromWire("table")).contains(ResultViewKind.TABLE);
		assertThat(ResultViewKind.fromWire("gauge")).isEmpty();
		assertThat(ResultViewColumnType.fromWire("string")).contains(ResultViewColumnType.STRING);
		assertThat(ResultViewColumnType.fromWire("rich-html")).isEmpty();

		ResultView degraded = ResultViews.degraded("rv-degraded", FALLBACK);
		assertThat(degraded.toMap()).containsEntry("kind", "text")
				.containsEntry("status", "error")
				.containsEntry("id", "rv-degraded");
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) degraded.toMap().get("error");
		assertThat(error).containsEntry("code", "RESULT_VIEW_UNSUPPORTED");
	}

	@Test
	void textViewNeedsNoColumnsAndKeepsTheSentence() {
		ResultView view = ResultViews.text("rv-text", null, "i18n:resultview.test.sentence", FALLBACK);
		Map<String, Object> map = view.toMap();
		assertThat(map).containsEntry("kind", "text").containsEntry("status", "ok");
		assertThat(map).doesNotContainKey("columns").doesNotContainKey("data");
		assertThat(map.get("description")).isEqualTo("i18n:resultview.test.sentence");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static ResultView buildSimple() {
		return ResultViews.table("rv-1", "i18n:t", List.of(
						ResultViewColumn.of("defCode", ResultViewColumnType.STRING, null, null),
						ResultViewColumn.of("message", ResultViewColumnType.STRING, null, null)),
				List.of(row("A", "-", "m")), FALLBACK, "validation_issue");
	}

	static Map<String, Object> row(String defCode, String rowKey, String message) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("defCode", defCode);
		row.put("rowKey", rowKey);
		row.put("message", message);
		return row;
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> rowsOf(Map<String, Object> map) {
		Map<String, Object> data = (Map<String, Object>) map.get("data");
		Map<String, Object> inline = (Map<String, Object>) data.get("inline");
		return (List<Map<String, Object>>) inline.get("rows");
	}
}
