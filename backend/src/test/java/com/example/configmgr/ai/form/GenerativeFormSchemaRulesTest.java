package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-15 用例①：<b>表单 schema 的白名单校验</b>（正例覆盖白名单六型，反例覆盖越权表达与超限）。
 *
 * <p>本类是纯函数用例（不启 Spring、不碰 Redis）：校验的事实源是
 * {@link GenerativeFormRules} 对入参<b>原文</b>的判定。反例清单对应 DC-15 的安全边界
 * ——"不在白名单内的属性或类型一律拒绝"：自由 HTML（{@code type:"html"}）、
 * 任意属性注入（{@code html}/{@code attributes}/{@code onClick}/{@code style}）、
 * 未知类型、超长文本、缺选项、把选项写成裸字符串、默认值与类型不符等。
 */
class GenerativeFormSchemaRulesTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 正例：白名单六型各一个字段，可选属性也各用一遍。 */
	private static final String VALID_FORM = """
			{"scenario":"FILTER","title":"导出筛选条件","fields":[
			  {"key":"keyword","label":"关键字","type":"text","required":true,"placeholder":"如 CNY","defaultValue":"CNY"},
			  {"key":"minAmount","label":"最小金额","type":"number","placeholder":"0","defaultValue":10},
			  {"key":"onlyEnabled","label":"只看启用","type":"boolean","defaultValue":true},
			  {"key":"effectiveDate","label":"生效日期","type":"date","placeholder":"2026-10-07","defaultValue":"2026-10-07"},
			  {"key":"scopeKeys","label":"范围","type":"enum","required":true,
			     "options":[{"value":"XN","label":"西南"},{"value":"HD"}]},
			  {"key":"defs","label":"配置项","type":"multi_select",
			     "options":[{"value":"CURRENCY"},{"value":"DOC_TYPE"}],"defaultValue":["CURRENCY"]}
			]}""";

	// ── 正例 ────────────────────────────────────────────────────────────────

	@Test
	void acceptsEveryWhitelistedFieldType() throws Exception {
		assertThat(GenerativeFormRules.schemaReasons(form(VALID_FORM))).isEmpty();
		assertThat(GenerativeFormRules.argumentReasons(args(VALID_FORM))).isEmpty();
	}

	@Test
	void acceptsMinimalFormWithOnlyRequiredAttributes() throws Exception {
		String minimal = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"mode","label":"导入模式","type":"enum","options":[{"value":"ADD"},{"value":"REPLACE"}]}
				]}""";
		assertThat(GenerativeFormRules.schemaReasons(form(minimal))).isEmpty();
	}

	@Test
	void acceptsFieldCountAtTheUpperBound() throws Exception {
		StringBuilder fields = new StringBuilder();
		for (int i = 0; i < GenerativeFormRules.MAX_FIELDS; i++) {
			fields.append(i > 0 ? "," : "").append("{\"key\":\"f").append(i)
					.append("\",\"label\":\"字段").append(i).append("\",\"type\":\"text\"}");
		}
		String form = "{\"scenario\":\"FILTER\",\"fields\":[" + fields + "]}";
		assertThat(GenerativeFormRules.schemaReasons(form(form))).isEmpty();
	}

	// ── 反例：越权表达（安全边界） ───────────────────────────────────────────

	@Test
	void rejectsFieldsThatAskForFreeHtmlOrScript() throws Exception {
		String html = """
				{"scenario":"FILTER","fields":[
				  {"key":"content","label":"内容","type":"html","value":"<img src=x onerror=alert(1)>"}
				]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(html))))
				.contains("不在白名单类型内").contains("html");

		String scriptType = """
				{"scenario":"FILTER","fields":[{"key":"c","label":"内容","type":"script"}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(scriptType)))).contains("不在白名单类型内");
	}

	@Test
	void rejectsArbitraryAttributeInjection() throws Exception {
		String injected = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"name","label":"名称","type":"text","html":"<b>x</b>","attributes":{"class":"x"},
				   "onClick":"alert(1)","style":"color:red","maxLength":5}
				]}""";
		List<String> reasons = GenerativeFormRules.schemaReasons(form(injected));
		assertThat(joined(reasons)).contains("不允许的属性");
		// 五个白名单外的属性逐条报出（一次看全，模型不必改一处再被拒一次）
		assertThat(reasons).hasSize(5);
		assertThat(joined(reasons)).contains("html").contains("attributes").contains("onClick").contains("style")
				.contains("maxLength");
	}

	@Test
	void rejectsUnknownFormLevelAttribute() throws Exception {
		String form = """
				{"scenario":"FILTER","layout":"two-column","fields":[
				  {"key":"k","label":"K","type":"text"}],"style":"color:red"}""";
		List<String> reasons = GenerativeFormRules.schemaReasons(form(form));
		assertThat(joined(reasons)).contains("form 不允许的属性").contains("layout").contains("style");
	}

	@Test
	void rejectsOptionObjectsWithExtraAttributes() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"enum",
				  "options":[{"value":"A","label":"甲","icon":"star"}]}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(form)))).contains("选项属性白名单").contains("icon");
	}

	// ── 反例：原型污染键（与前端 FORBIDDEN_FIELD_KEYS 同步，红队问题 3） ──────────

	@Test
	void rejectsProtoFieldKey() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[{"key":"__proto__","label":"K","type":"text"}]}""";
		List<String> reasons = GenerativeFormRules.schemaReasons(form(form));
		assertThat(joined(reasons)).contains("\"__proto__\" 是原型污染键")
				.contains("__proto__/constructor/prototype");
		// 键名本身合正则：被拒的唯一原因是原型键黑名单，而不是键名字符集规则
		assertThat(joined(reasons)).doesNotContain("不合法");
	}

	@Test
	void rejectsConstructorFieldKey() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[{"key":"constructor","label":"K","type":"text"}]}""";
		List<String> reasons = GenerativeFormRules.schemaReasons(form(form));
		assertThat(joined(reasons)).contains("\"constructor\" 是原型污染键")
				.contains("__proto__/constructor/prototype");
		assertThat(joined(reasons)).doesNotContain("不合法");
	}

	@Test
	void rejectsPrototypeFieldKey() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[{"key":"prototype","label":"K","type":"text"}]}""";
		List<String> reasons = GenerativeFormRules.schemaReasons(form(form));
		assertThat(joined(reasons)).contains("\"prototype\" 是原型污染键")
				.contains("__proto__/constructor/prototype");
		assertThat(joined(reasons)).doesNotContain("不合法");
	}

	// ── 反例：结构/取值不合规 ───────────────────────────────────────────────

	@Test
	void rejectsMissingOrUnknownScenario() throws Exception {
		String missing = "{\"fields\":[{\"key\":\"k\",\"label\":\"K\",\"type\":\"text\"}]}";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(missing)))).contains("scenario 必填");
		String other = """
				{"scenario":"WIZARD","fields":[{"key":"k","label":"K","type":"text"}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(other)))).contains("CLARIFY").contains("FILTER");
	}

	@Test
	void rejectsTooManyFields() throws Exception {
		StringBuilder fields = new StringBuilder();
		for (int i = 0; i <= GenerativeFormRules.MAX_FIELDS; i++) {
			fields.append(i > 0 ? "," : "").append("{\"key\":\"f").append(i)
					.append("\",\"label\":\"字段\",\"type\":\"text\"}");
		}
		String form = "{\"scenario\":\"FILTER\",\"fields\":[" + fields + "]}";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(form)))).contains("字段过多").contains("上限 20");
	}

	@Test
	void rejectsEmptyFieldList() throws Exception {
		assertThat(joined(GenerativeFormRules.schemaReasons(form("{\"scenario\":\"FILTER\",\"fields\":[]}"))))
				.contains("至少 1 个字段");
		assertThat(joined(GenerativeFormRules.schemaReasons(form("{\"scenario\":\"FILTER\"}"))))
				.contains("fields 必填");
	}

	@Test
	void rejectsOverlongTexts() throws Exception {
		String longLabel = "标".repeat(GenerativeFormRules.MAX_LABEL_CHARS + 1);
		String form = "{\"scenario\":\"FILTER\",\"title\":\"" + "题".repeat(GenerativeFormRules.MAX_TITLE_CHARS + 1)
				+ "\",\"fields\":[{\"key\":\"k\",\"label\":\"" + longLabel + "\",\"type\":\"text\",\"placeholder\":\""
				+ "占".repeat(GenerativeFormRules.MAX_PLACEHOLDER_CHARS + 1) + "\"}]}";
		String reasons = joined(GenerativeFormRules.schemaReasons(form(form)));
		assertThat(reasons).contains("title 过长").contains("label 过长").contains("placeholder 过长");
	}

	@Test
	void rejectsTooLongFieldKey() throws Exception {
		String key = "k".repeat(GenerativeFormRules.MAX_KEY_CHARS + 1);
		String form = "{\"scenario\":\"FILTER\",\"fields\":[{\"key\":\"" + key + "\",\"label\":\"K\",\"type\":\"text\"}]}";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(form)))).contains(".key").contains("不合法");
	}

	@Test
	void rejectsIllegalFieldKeyCharsetAndDuplicates() throws Exception {
		String bad = """
				{"scenario":"FILTER","fields":[
				  {"key":"1st","label":"A","type":"text"},
				  {"key":"dup","label":"B","type":"text"},
				  {"key":"dup","label":"C","type":"text"}]}""";
		String reasons = joined(GenerativeFormRules.schemaReasons(form(bad)));
		assertThat(reasons).contains("\"1st\" 不合法").contains("重复").contains("唯一");
	}

	@Test
	void rejectsOptionTypesWithoutOptions() throws Exception {
		String enumNoOptions = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"enum"}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(enumNoOptions)))).contains("options 必填");

		String multiEmpty = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"multi_select","options":[]}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(multiEmpty)))).contains("至少 1 个选项");
	}

	@Test
	void rejectsOptionsOnNonOptionType() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"text",
				  "options":[{"value":"A"}]}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(form)))).contains("仅 enum/multi_select 可带");
	}

	@Test
	void rejectsBareStringOptionsAndDuplicateOptionValues() throws Exception {
		String bare = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"enum","options":["XN","HD"]}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(bare)))).contains("不接受裸字符串");

		String dup = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"enum",
				  "options":[{"value":"XN"},{"value":"XN"}]}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(dup)))).contains("与前一个选项重复");
	}

	@Test
	void rejectsPlaceholderOnNonTextTypes() throws Exception {
		String form = """
				{"scenario":"CLARIFY","fields":[{"key":"ok","label":"是/否","type":"boolean","placeholder":"选一个"}]}""";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(form)))).contains("placeholder 仅");
	}

	@Test
	void rejectsDefaultValuesThatDoNotMatchType() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"n","label":"N","type":"number","defaultValue":"10"},
				  {"key":"d","label":"D","type":"date","defaultValue":"2026-02-30"},
				  {"key":"b","label":"B","type":"boolean","defaultValue":"true"},
				  {"key":"e","label":"E","type":"enum","defaultValue":"ZZ","options":[{"value":"A"}]},
				  {"key":"m","label":"M","type":"multi_select","defaultValue":["A","Z"],"options":[{"value":"A"}]}]}""";
		String reasons = joined(GenerativeFormRules.schemaReasons(form(form)));
		assertThat(reasons).contains("期望 number").contains("期望合法日期").contains("期望 boolean")
				.contains("不在选项内").contains("含不在选项内的值");
	}

	@Test
	void rejectsOversizedForm() throws Exception {
		String filler = "长".repeat(GenerativeFormRules.MAX_FORM_CHARS);
		String form = "{\"scenario\":\"FILTER\",\"title\":\"" + filler + "\",\"fields\":[]}";
		assertThat(joined(GenerativeFormRules.schemaReasons(form(form)))).contains("表单 schema 过大");
	}

	@Test
	void rejectsNonObjectFormAndExtraArgumentKeys() throws Exception {
		assertThat(joined(GenerativeFormRules.argumentReasons(null))).contains("必须是 JSON 对象");
		assertThat(joined(GenerativeFormRules.argumentReasons(this.mapper.readTree("\"just-a-string\""))))
				.contains("必须是 JSON 对象");
		assertThat(joined(GenerativeFormRules.argumentReasons(this.mapper.readTree("{\"extra\":1}"))))
				.contains("缺少 form 参数").contains("多余的键：extra");
		assertThat(joined(GenerativeFormRules.argumentReasons(this.mapper.readTree("{\"form\":\"text\"}"))))
				.contains("form 必须是 JSON 对象");
		assertThat(joined(GenerativeFormRules.argumentReasons(
				this.mapper.readTree("{\"form\":{\"scenario\":\"FILTER\",\"fields\":[]},\"extra\":1}"))))
				.contains("多余的键：extra");
	}

	@Test
	void reportsEveryProblemInOnePass() throws Exception {
		String messy = """
				{"scenario":"FILTER","title":123,"fields":[
				  {"key":"k","label":"K","type":"html"},
				  {"key":"k","label":"","type":"text","onClick":"x"}]}""";
		List<String> reasons = GenerativeFormRules.schemaReasons(form(messy));
		// 一次给全：title 类型、类型越白名单、label 为空、key 重复、未知属性 —— 模型一改即过
		assertThat(reasons).hasSizeGreaterThanOrEqualTo(5);
		assertThat(joined(reasons)).contains("title 必须是文本").contains("不在白名单类型内").contains("重复");
	}

	// ── 日期口径 ────────────────────────────────────────────────────────────

	@Test
	void isoDateAcceptsRealCalendarDaysOnly() {
		assertThat(GenerativeFormRules.isIsoDate("2026-10-07")).isTrue();
		assertThat(GenerativeFormRules.isIsoDate("2026-02-29")).isFalse();
		assertThat(GenerativeFormRules.isIsoDate("2024-02-29")).isTrue();
		assertThat(GenerativeFormRules.isIsoDate("2026-2-3")).isFalse();
		assertThat(GenerativeFormRules.isIsoDate("2026/10/07")).isFalse();
		assertThat(GenerativeFormRules.isIsoDate("")).isFalse();
		assertThat(GenerativeFormRules.isIsoDate(null)).isFalse();
	}

	private JsonNode form(String json) throws Exception {
		return this.mapper.readTree(json);
	}

	/** 工具入参形态（{@code {"form":…}}）。 */
	private JsonNode args(String formJson) throws Exception {
		return this.mapper.readTree("{\"form\":" + formJson + "}");
	}

	private static String joined(List<String> reasons) {
		return String.join("\n", reasons);
	}

}
