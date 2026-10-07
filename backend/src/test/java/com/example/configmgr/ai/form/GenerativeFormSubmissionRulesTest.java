package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-15 用例②：<b>回灌值的类型复核</b>（正反例）。
 *
 * <p>回灌通道沿用既有 {@code POST /api/ai/frontend-tool-result}（幂等语义不变），
 * 但生成式表单的结果必须与<b>当初下发的那份 schema</b>逐字段相符 —— 本类锁的正是这条：
 * number 必须是 JSON 数字（不是字符串）、date 必须是合法日历日、enum/multi_select 的值必须在选项内、
 * 未知字段与缺失必填一律拒绝、结果必须是 JSON 对象。
 */
class GenerativeFormSubmissionRulesTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 覆盖六型的表单 schema（与回灌值一一对应）。 */
	private static final String SCHEMA = """
			{"scenario":"FILTER","fields":[
			  {"key":"keyword","label":"关键字","type":"text","required":true},
			  {"key":"minAmount","label":"最小金额","type":"number"},
			  {"key":"onlyEnabled","label":"只看启用","type":"boolean"},
			  {"key":"effectiveDate","label":"生效日期","type":"date"},
			  {"key":"scopeKey","label":"范围","type":"enum","required":true,"options":[{"value":"XN"},{"value":"HD"}]},
			  {"key":"defs","label":"配置项","type":"multi_select","required":true,
			     "options":[{"value":"CURRENCY"},{"value":"DOC_TYPE"}]}
			]}""";

	// ── 正例 ────────────────────────────────────────────────────────────────

	@Test
	void acceptsValuesMatchingEveryWhitelistedType() throws Exception {
		String values = """
				{"keyword":"CNY","minAmount":10.5,"onlyEnabled":true,"effectiveDate":"2026-10-07",
				 "scopeKey":"XN","defs":["CURRENCY","DOC_TYPE"]}""";
		assertThat(GenerativeFormRules.resultReasons(schema(), values(values))).isEmpty();
	}

	@Test
	void acceptsOptionalFieldsOmittedOrNull() throws Exception {
		String omitted = "{\"keyword\":\"CNY\",\"scopeKey\":\"HD\",\"defs\":[\"CURRENCY\"]}";
		assertThat(GenerativeFormRules.resultReasons(schema(), values(omitted))).isEmpty();
		String explicitNull = """
				{"keyword":"CNY","minAmount":null,"onlyEnabled":null,"effectiveDate":null,
				 "scopeKey":"HD","defs":["CURRENCY"]}""";
		assertThat(GenerativeFormRules.resultReasons(schema(), values(explicitNull))).isEmpty();
	}

	@Test
	void acceptsFalsyAndZeroValuesForOptionalFields() throws Exception {
		String values = """
				{"keyword":"X","minAmount":0,"onlyEnabled":false,"scopeKey":"XN","defs":["DOC_TYPE"]}""";
		assertThat(GenerativeFormRules.resultReasons(schema(), values(values))).isEmpty();
	}

	// ── 反例：类型不符 ───────────────────────────────────────────────────────

	@Test
	void rejectsNumberGivenAsText() throws Exception {
		String values = """
				{"keyword":"X","minAmount":"10","scopeKey":"XN","defs":["CURRENCY"]}""";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(values))))
				.contains("minAmount").contains("期望 number").contains("文本");
	}

	@Test
	void rejectsBooleanGivenAsText() throws Exception {
		String values = """
				{"keyword":"X","onlyEnabled":"true","scopeKey":"XN","defs":["CURRENCY"]}""";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(values))))
				.contains("onlyEnabled").contains("期望 boolean");
	}

	@Test
	void rejectsTextGivenAsNumberOrObject() throws Exception {
		String number = "{\"keyword\":1,\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(number))))
				.contains("keyword").contains("期望 text");
		String object = "{\"keyword\":{\"a\":1},\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(object)))).contains("期望 text");
	}

	@Test
	void rejectsDatesThatAreNotIsoOrNotRealDays() throws Exception {
		for (String bad : List.of("2026/10/07", "2026-02-30", "2026-2-3", "20261007", "")) {
			String values = "{\"keyword\":\"X\",\"effectiveDate\":\"" + bad + "\",\"scopeKey\":\"XN\","
					+ "\"defs\":[\"CURRENCY\"]}";
			assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(values))))
					.as("非法日期 %s 必须被拒", bad)
					.contains("effectiveDate").contains("期望合法日期");
		}
	}

	@Test
	void rejectsEnumValueOutsideOptions() throws Exception {
		String values = "{\"keyword\":\"X\",\"scopeKey\":\"ZZ\",\"defs\":[\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(values))))
				.contains("scopeKey").contains("不在选项内").contains("XN/HD");
	}

	@Test
	void rejectsMultiSelectWithUnknownDuplicateOrNonArrayValues() throws Exception {
		String unknown = "{\"keyword\":\"X\",\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\",\"NOPE\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(unknown))))
				.contains("defs").contains("含不在选项内的值");
		String duplicate = "{\"keyword\":\"X\",\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\",\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(duplicate)))).contains("含重复值");
		String notArray = "{\"keyword\":\"X\",\"scopeKey\":\"XN\",\"defs\":\"CURRENCY\"}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(notArray)))).contains("期望数组");
	}

	// ── 反例：键与必填 ───────────────────────────────────────────────────────

	@Test
	void rejectsUnknownFieldKey() throws Exception {
		String values = """
				{"keyword":"X","scopeKey":"XN","defs":["CURRENCY"],"injectedKey":"<script>alert(1)</script>"}""";
		String reasons = joined(GenerativeFormRules.resultReasons(schema(), values(values)));
		assertThat(reasons).contains("未知字段：injectedKey").contains("表单字段：keyword");
	}

	@Test
	void rejectsMissingRequiredFields() throws Exception {
		String noKeyword = "{\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(noKeyword))))
				.contains("缺少必填字段：keyword");
		String emptyMulti = "{\"keyword\":\"X\",\"scopeKey\":\"XN\",\"defs\":[]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(emptyMulti))))
				.contains("必填字段 defs 至少选 1 项");
		String blankKeyword = "{\"keyword\":\"   \",\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(blankKeyword))))
				.contains("不得为空串");
	}

	// ── 反例：结果形态与体积 ─────────────────────────────────────────────────

	@Test
	void rejectsNonObjectResult() throws Exception {
		for (String bad : List.of("[]", "\"text\"", "123", "true")) {
			assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(bad))))
					.as("结果 %s 必须被拒", bad)
					.contains("必须是 JSON 对象");
		}
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), null))).contains("必须是 JSON 对象");
	}

	@Test
	void rejectsOversizedResult() throws Exception {
		String values = "{\"keyword\":\"" + "长".repeat(GenerativeFormRules.MAX_RESULT_CHARS)
				+ "\",\"scopeKey\":\"XN\",\"defs\":[\"CURRENCY\"]}";
		assertThat(joined(GenerativeFormRules.resultReasons(schema(), values(values)))).contains("回灌结果过大");
	}

	@Test
	void rejectsWhenSchemaIsUnavailable() throws Exception {
		assertThat(joined(GenerativeFormRules.resultReasons(null, values("{}")))).contains("schema 不可用");
		assertThat(joined(GenerativeFormRules.resultReasons(
				this.mapper.readTree("{\"scenario\":\"FILTER\"}"), values("{}")))).contains("schema 不可用");
	}

	private JsonNode schema() throws Exception {
		return this.mapper.readTree(SCHEMA);
	}

	private JsonNode values(String json) throws Exception {
		return this.mapper.readTree(json);
	}

	private static String joined(List<String> reasons) {
		return String.join("\n", reasons);
	}

}
