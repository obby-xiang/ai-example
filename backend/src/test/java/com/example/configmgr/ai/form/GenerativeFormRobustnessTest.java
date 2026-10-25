package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 表单健壮性（Task 7）：级联选项与数值边界的"错就是字段错误"，不是放行、也不是 500。
 *
 * <p>两个已确认缺陷：
 * <ul>
 * <li><b>级联父值缺失时子值任意通过</b>：{@code optionsFrom.map} 的父值没填（或没有该分支）时，
 * 可取值集合为空，而成员校验写的是 {@code !options.isEmpty() && !contains} —— 空集合直接跳过校验，
 * 于是"省份=任意字符串"被放行，与 {@code runtimeOptionValues} 的文档口径
 * （"父值没选，子选项就不存在，提交任何值都应判 VALUE_OUT_OF_OPTIONS"）相反；</li>
 * <li><b>非有限 step/min/max 冒到 500</b>：JSON 里的 {@code 1e999} 解析成
 * {@code Double.POSITIVE_INFINITY}，schema 期只判 {@code step <= 0} 放行；回灌期
 * {@code BigDecimal.valueOf(∞)} 抛 {@code NumberFormatException}，从
 * {@code GenerativeFormGuard.inspectResult} 一路冒到确认门（该调用点无 catch）⇒ HTTP 500。</li>
 * </ul>
 */
class GenerativeFormRobustnessTest {

	private final ObjectMapper mapper = new ObjectMapper();

	/** 静态映射级联：province 的可取值只在 country 选过之后才存在。 */
	private static final String CASCADE = """
			{"scenario":"FILTER","fields":[
			  {"key":"country","label":"国家","type":"enum","options":[{"value":"CN"},{"value":"US"}]},
			  {"key":"province","label":"省份","type":"enum",
			   "optionsFrom":{"dependsOn":"country","map":{
			     "CN":[{"value":"SC"},{"value":"BJ"}]}}}]}""";

	// ── 级联：父值缺失 / 无分支时不得任意通过 ─────────────────────────────────

	@Test
	void cascadeValueWithoutParentIsRejected() throws Exception {
		List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(CASCADE), values("{\"province\":\"SC\"}"));

		assertThat(issues).extracting(FormIssue::key).containsExactly("province");
		assertThat(issues).extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_OPTIONS);
	}

	@Test
	void cascadeValueForUnknownParentBranchIsRejected() throws Exception {
		// 父值合法但 map 里没有它的分支（map 只声明了 CN）：子选项同样不存在
		List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(CASCADE),
				values("{\"country\":\"US\",\"province\":\"SC\"}"));

		assertThat(issues).extracting(FormIssue::key).containsExactly("province");
		assertThat(issues).extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_OPTIONS);
	}

	@Test
	void cascadeValueOutsideResolvedBranchIsStillRejected() throws Exception {
		// 既有口径不降：父值 CN 只有 SC/BJ
		List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(CASCADE),
				values("{\"country\":\"CN\",\"province\":\"CA\"}"));

		assertThat(issues).extracting(FormIssue::key).containsExactly("province");
		assertThat(issues).extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_OPTIONS);
	}

	@Test
	void cascadeValueInsideResolvedBranchIsAccepted() throws Exception {
		assertThat(GenerativeFormRules.resultIssues(schema(CASCADE),
				values("{\"country\":\"CN\",\"province\":\"SC\"}"))).isEmpty();
	}

	@Test
	void multiSelectCascadeValueWithoutParentIsRejected() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"country","label":"国家","type":"enum","options":[{"value":"CN"}]},
				  {"key":"defs","label":"配置项","type":"multi_select",
				   "optionsFrom":{"dependsOn":"country","map":{"CN":[{"value":"CURRENCY"}]}}}]}""";
		List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(form),
				values("{\"defs\":[\"CURRENCY\"]}"));

		assertThat(issues).extracting(FormIssue::key).containsExactly("defs");
		assertThat(issues).extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_OPTIONS);
	}

	@Test
	void sourceIdCascadeStaysUncheckedBecauseRulesCannotEnumerateIt() throws Exception {
		// sourceId 的选项在后端数据源里，规则层无法枚举 ⇒ 不做成员校验（既有边界不扩大）
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"kind","label":"类别","type":"text"},
				  {"key":"def","label":"配置项","type":"enum",
				   "optionsFrom":{"dependsOn":"kind","sourceId":"definition_fields"}}]}""";

		assertThat(GenerativeFormRules.schemaIssues(schema(form))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(form), values("{\"kind\":\"CURRENCY\",\"def\":\"AMOUNT\"}")))
			.isEmpty();
	}

	// ── 非有限数：schema 期是字段错误，回灌期不得抛异常（线上 = 500） ──────────

	@Test
	void nonFiniteStepIsAFieldErrorAtSchemaTime() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"age","label":"年龄","type":"number","step":1e999}]}""";

		List<FormIssue> issues = GenerativeFormRules.schemaIssues(schema(form));

		assertThat(issues).extracting(FormIssue::key).containsExactly("age");
		assertThat(issues).extracting(FormIssue::code).containsExactly(FormIssueCodes.FIELD_STEP_INVALID);
	}

	@Test
	void nonFiniteMinAndMaxAreFieldErrorsAtSchemaTime() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"age","label":"年龄","type":"number","min":-1e999,"max":1e999}]}""";

		List<FormIssue> issues = GenerativeFormRules.schemaIssues(schema(form));

		assertThat(issues).extracting(FormIssue::key).contains("age");
		assertThat(issues).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_NUMBER_BOUND_INVALID);
		assertThat(issues).noneMatch(issue -> issue.message().contains("不得大于"));
	}

	@Test
	void pendingFormWithNonFiniteStepYieldsFieldErrorInsteadOfThrowing() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"age","label":"年龄","type":"number","step":1e999}]}""";

		assertThatCode(() -> {
			List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(form), values("{\"age\":10}"));
			assertThat(issues).extracting(FormIssue::key).contains("age");
			assertThat(issues).extracting(FormIssue::code).contains(FormIssueCodes.FIELD_STEP_INVALID);
		}).doesNotThrowAnyException();
	}

	@Test
	void nonFiniteSubmittedNumberIsAValueErrorNotAnException() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"age","label":"年龄","type":"number","min":0,"step":1}]}""";

		assertThatCode(() -> {
			List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(form), values("{\"age\":1e999}"));
			assertThat(issues).extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_TYPE_MISMATCH);
		}).doesNotThrowAnyException();
	}

	@Test
	void finiteStepStillValidatesAsBefore() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"age","label":"年龄","type":"number","min":0,"step":5}]}""";

		assertThat(GenerativeFormRules.schemaIssues(schema(form))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(form), values("{\"age\":10}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(form), values("{\"age\":7}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_STEP_MISMATCH);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private JsonNode schema(String json) throws Exception {
		return this.mapper.readTree(json);
	}

	private JsonNode values(String json) throws Exception {
		return this.mapper.readTree(json);
	}

}
