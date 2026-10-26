package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GF 优化批（G-A / G-C / G-D / G-E / G-F）的规则层用例：结构化错误、校验最小集、联动五原语、
 * attachment 字段与长文本 variant。
 *
 * <p>与既有三例的关系：{@code schemaReasons/resultReasons} 的 {@code List<String>} 形态<b>保持</b>
 * （人读原因，既有断言逐条不降），本类锁的是新增的<b>机器可读</b>事实源
 * {@code schemaIssues/resultIssues}（{@code {key, code, message}}）。
 */
class GenerativeFormProBatchRulesTest {

	private final ObjectMapper mapper = new ObjectMapper();

	// ── G-A 结构化错误协议 ──────────────────────────────────────────────────

	@Test
	void schemaIssuesCarryKeyCodeAndHumanMessage() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"age","label":"年龄","type":"number","min":1,"max":120},
				  {"key":"email","label":"邮箱","type":"text","format":"email"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(form))).isEmpty();

		String bad = """
				{"scenario":"FILTER","fields":[{"key":"email","label":"邮箱","type":"text","format":"regex"}]}""";
		List<FormIssue> issues = GenerativeFormRules.schemaIssues(form(bad));
		assertThat(issues).hasSize(1);
		FormIssue issue = issues.get(0);
		assertThat(issue.key()).isEqualTo("email");
		assertThat(issue.code()).isEqualTo(FormIssueCodes.FIELD_FORMAT_UNSUPPORTED);
		assertThat(issue.message()).contains("email").contains("tel").contains("url").contains("idcard");
		// 人读清单与结构化清单同源（同一批 message）
		assertThat(GenerativeFormRules.schemaReasons(form(bad))).containsExactly(issue.message());
	}

	@Test
	void resultIssuesAreKeyedByFieldAndCodeIsMachineReadable() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[
				  {"key":"email","label":"邮箱","type":"text","format":"email"},
				  {"key":"age","label":"年龄","type":"number","integer":true}]}""";
		List<FormIssue> issues = GenerativeFormRules.resultIssues(schema(schema),
				values("{\"email\":\"not-an-email\",\"age\":3.5}"));
		assertThat(issues).extracting(FormIssue::key).containsExactly("email", "age");
		assertThat(issues).extracting(FormIssue::code)
			.containsExactly(FormIssueCodes.VALUE_FORMAT_MISMATCH, FormIssueCodes.VALUE_NOT_INTEGER);
	}

	@Test
	void guardVerdictCarriesStructuredIssuesAlongsideHumanReasons() {
		GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);
		String bad = "{\"form\":{\"scenario\":\"FILTER\",\"fields\":[{\"key\":\"a\",\"label\":\"A\","
				+ "\"type\":\"number\",\"min\":10,\"max\":1}]}}";
		GenerativeFormGuardInspector.VerdictView view = GenerativeFormGuardInspector.inspect(guard, bad);
		assertThat(view.accepted()).isFalse();
		assertThat(view.issues()).isNotEmpty();
		assertThat(view.issues().get(0).key()).isEqualTo("a");
		assertThat(view.issues().get(0).code()).isEqualTo(FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX);
		// 人读 render 仍带码与清单（既有契约不降）；GF 优化批另带机器可读段（供模型精准修复）
		assertThat(view.render()).startsWith(GenerativeFormRules.SCHEMA_REJECTED).contains("被拒原因")
			.contains("机器可读").contains("key=a").contains("code=" + FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX);
	}

	// ── G-C 校验最小集 ──────────────────────────────────────────────────────

	@Test
	void textLengthFormatAndHardCap() throws Exception {
		String ok = """
				{"scenario":"FILTER","fields":[
				  {"key":"mail","label":"邮箱","type":"text","format":"email","minLength":5,"maxLength":60},
				  {"key":"tel","label":"电话","type":"text","format":"tel"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(ok))).isEmpty();

		// 200 硬顶：maxLength 声明超过 200 直接拒绝
		String tooLong = """
				{"scenario":"FILTER","fields":[{"key":"k","label":"K","type":"text","maxLength":201}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(tooLong))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_MAX_LENGTH_TOO_LARGE);

		// longText variant 可放宽到 2000，超过 2000 拒绝
		String longText = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"note","label":"备注","type":"text","variant":"longText","maxLength":2000}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(longText))).isEmpty();
		String over = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"note","label":"备注","type":"text","variant":"longText","maxLength":2001}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(over))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_MAX_LENGTH_TOO_LARGE);

		// variant 只有 text 能带，且取值只有 longText
		String wrongVariant = """
				{"scenario":"FILTER","fields":[{"key":"n","label":"N","type":"number","variant":"longText"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(wrongVariant))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED);
		String unknownVariant = """
				{"scenario":"FILTER","fields":[{"key":"t","label":"T","type":"text","variant":"richText"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(unknownVariant))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_VARIANT_UNSUPPORTED);
	}

	@Test
	void modelSuppliedRegexIsRejected() throws Exception {
		String regex = """
				{"scenario":"FILTER","fields":[
				  {"key":"k","label":"K","type":"text","pattern":"^(a+)+$"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(regex))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_EXTRA_ATTRIBUTE);
	}

	@Test
	void textFormatIsEnforcedOnSubmit() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[
				  {"key":"mail","label":"邮箱","type":"text","format":"email"},
				  {"key":"url","label":"链接","type":"text","format":"url"},
				  {"key":"tel","label":"电话","type":"text","format":"tel"},
				  {"key":"id","label":"身份证","type":"text","format":"idcard"}]}""";
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values(
				"{\"mail\":\"a.b+c@example.co\",\"url\":\"https://example.com/x?y=1\","
						+ "\"tel\":\"+86 138-0000-0000\",\"id\":\"11010519491231002X\"}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values(
				"{\"mail\":\"a@b\",\"url\":\"javascript:alert(1)\",\"tel\":\"abc\",\"id\":\"123\"}")))
			.extracting(FormIssue::code)
			.containsOnly(FormIssueCodes.VALUE_FORMAT_MISMATCH);
	}

	@Test
	void numberStepIntegerAndRangeRules() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[
				  {"key":"n","label":"N","type":"number","min":1,"max":10,"step":0.5,"integer":false}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(schema))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"n\":1.5}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"n\":1.3}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_STEP_MISMATCH);
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"n\":11}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_RANGE);
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"n\":0}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_RANGE);

		String integer = """
				{"scenario":"FILTER","fields":[{"key":"i","label":"I","type":"number","integer":true}]}""";
		assertThat(GenerativeFormRules.resultIssues(schema(integer), values("{\"i\":2.0}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(integer), values("{\"i\":2.2}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_NOT_INTEGER);

		String badBounds = """
				{"scenario":"FILTER","fields":[{"key":"b","label":"B","type":"number","min":10,"max":1}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(badBounds))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX);
	}

	@Test
	void dateRangeRules() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[{"key":"d","label":"D","type":"date","min":"2026-01-01","max":"2026-12-31"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(schema))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"d\":\"2026-06-01\"}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"d\":\"2025-12-31\"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_RANGE);

		String badMin = """
				{"scenario":"FILTER","fields":[{"key":"d","label":"D","type":"date","min":"2026/01/01"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(badMin))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_DATE_BOUND_INVALID);
		String inverted = """
				{"scenario":"FILTER","fields":[{"key":"d","label":"D","type":"date","min":"2026-12-31","max":"2026-01-01"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(inverted))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX);
	}

	@Test
	void multiSelectItemBounds() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[{"key":"m","label":"M","type":"multi_select",
				  "options":[{"value":"A"},{"value":"B"},{"value":"C"}],"minItems":2,"maxItems":3}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(schema))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"m\":[\"A\",\"B\"]}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"m\":[\"A\"]}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_TOO_FEW_ITEMS);
		String tooManySchema = """
				{"scenario":"FILTER","fields":[{"key":"m","label":"M","type":"multi_select",
				  "options":[{"value":"A"}],"minItems":3,"maxItems":1}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(tooManySchema))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX);
	}

	@Test
	void blankTextCountsAsEmptyOnBothEnds() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[{"key":"t","label":"T","type":"text","required":true}]}""";
		// 前后端同源口径：纯空格 = 空（前端 checkFormValues 同判据）
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"t\":\"   \"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_REQUIRED_MISSING);
	}

	@Test
	void fieldAndOptionCountsStayAtTwentyAndFifty() {
		assertThat(GenerativeFormRules.MAX_FIELDS).isEqualTo(20);
		assertThat(GenerativeFormRules.MAX_OPTIONS).isEqualTo(50);
		assertThat(GenerativeFormRules.MAX_TEXT_VALUE_CHARS).isEqualTo(200);
		assertThat(GenerativeFormRules.MAX_LONGTEXT_VALUE_CHARS).isEqualTo(2000);
	}

	// ── G-D 联动五原语 ──────────────────────────────────────────────────────

	@Test
	void acceptsAllFiveLinkagePrimitivesWithinLimits() throws Exception {
		String form = """
				{"scenario":"FILTER","fields":[
				  {"key":"country","label":"国家","type":"enum","options":[{"value":"CN"},{"value":"US"}]},
				  {"key":"province","label":"省份","type":"enum",
				     "optionsFrom":{"dependsOn":"country","map":{"CN":[{"value":"SC"},{"value":"BJ"}],"US":[{"value":"CA"}]}},
				   "visibleWhen":{"key":"country","op":"notEmpty"},
				   "requiredWhen":{"key":"country","op":"eq","value":"CN"}},
				  {"key":"note","label":"备注","type":"text","defaultFrom":{"dependsOn":"country","map":{"CN":"国内","US":"海外"}}},
				  {"key":"frozen","label":"冻结","type":"text","disabledWhen":{"key":"country","op":"in","values":["US"]}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(form))).isEmpty();
	}

	@Test
	void rejectsSelfReferenceAndCyclesAndDeepChains() throws Exception {
		String self = """
				{"scenario":"FILTER","fields":[
				  {"key":"a","label":"A","type":"text","visibleWhen":{"key":"a","op":"notEmpty"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(self))).extracting(FormIssue::code)
			.contains(FormIssueCodes.LINKAGE_SELF_REFERENCE);

		String cycle = """
				{"scenario":"FILTER","fields":[
				  {"key":"a","label":"A","type":"text","visibleWhen":{"key":"b","op":"notEmpty"}},
				  {"key":"b","label":"B","type":"text","visibleWhen":{"key":"a","op":"notEmpty"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(cycle))).extracting(FormIssue::code)
			.contains(FormIssueCodes.LINKAGE_CYCLE);

		String deep = """
				{"scenario":"FILTER","fields":[
				  {"key":"a","label":"A","type":"text"},
				  {"key":"b","label":"B","type":"text","visibleWhen":{"key":"a","op":"notEmpty"}},
				  {"key":"c","label":"C","type":"text","visibleWhen":{"key":"b","op":"notEmpty"}},
				  {"key":"d","label":"D","type":"text","visibleWhen":{"key":"c","op":"notEmpty"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(deep))).extracting(FormIssue::code)
			.contains(FormIssueCodes.LINKAGE_DEPTH_EXCEEDED);
	}

	@Test
	void rejectsConditionsOnUndeclaredFieldsAndBadOperators() throws Exception {
		String unknown = """
				{"scenario":"FILTER","fields":[
				  {"key":"a","label":"A","type":"text","visibleWhen":{"key":"ghost","op":"eq","value":"x"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(unknown))).extracting(FormIssue::code)
			.contains(FormIssueCodes.LINKAGE_UNKNOWN_FIELD);

		String badOp = """
				{"scenario":"FILTER","fields":[
				  {"key":"a","label":"A","type":"text"},
				  {"key":"b","label":"B","type":"text","visibleWhen":{"key":"a","op":"regex","value":".*"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(badOp))).extracting(FormIssue::code)
			.contains(FormIssueCodes.LINKAGE_OPERATOR_UNSUPPORTED);

		String expression = """
				{"scenario":"FILTER","fields":[
				  {"key":"a","label":"A","type":"text"},
				  {"key":"b","label":"B","type":"text","visibleWhen":"a === 1 || alert(1)"}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(expression))).extracting(FormIssue::code)
			.contains(FormIssueCodes.LINKAGE_CONDITION_INVALID);
	}

	@Test
	void rejectsUnregisteredOrArbitraryOptionSources() throws Exception {
		String url = """
				{"scenario":"FILTER","fields":[
				  {"key":"def","label":"定义","type":"enum","options":[{"value":"X"}]},
				  {"key":"f","label":"字段","type":"enum","optionsFrom":{"dependsOn":"def","sourceId":"https://evil.example/x"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(url))).extracting(FormIssue::code)
			.contains(FormIssueCodes.OPTIONS_SOURCE_UNKNOWN);

		String script = """
				{"scenario":"FILTER","fields":[
				  {"key":"def","label":"定义","type":"enum","options":[{"value":"X"}]},
				  {"key":"f","label":"字段","type":"enum","optionsFrom":{"dependsOn":"def","sourceId":"javascript:alert(1)"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(script))).extracting(FormIssue::code)
			.contains(FormIssueCodes.OPTIONS_SOURCE_UNKNOWN);

		String ok = """
				{"scenario":"FILTER","fields":[
				  {"key":"def","label":"定义","type":"enum","options":[{"value":"CURRENCY"}]},
				  {"key":"f","label":"字段","type":"enum","optionsFrom":{"dependsOn":"def","sourceId":"definition_fields"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(ok))).isEmpty();
	}

	@Test
	void definitionEnumerationSourceIsTheSecondRegisteredClosure() throws Exception {
		// 闭集扩容到两个内置源：definition_fields（定义编码 → 字段）与 definitions（层级 → 定义）
		assertThat(GenerativeFormRules.OPTION_SOURCE_IDS)
			.containsExactlyInAnyOrder("definition_fields", FormOptionSource.DefinitionOptionSource.ID);

		String ok = """
				{"scenario":"FILTER","fields":[
				  {"key":"level","label":"层级","type":"enum",
				   "options":[{"value":"GLOBAL"},{"value":"REGION"},{"value":"PROJECT"}]},
				  {"key":"def","label":"配置项","type":"enum","required":true,
				   "optionsFrom":{"dependsOn":"level","sourceId":"definitions"}}]}""";
		// required 不误判"空选项"：选项在后端数据里，规则层不枚举、也不做成员校验（既有边界不扩大）
		assertThat(GenerativeFormRules.schemaIssues(form(ok))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(ok), values("{\"level\":\"GLOBAL\",\"def\":\"CURRENCY\"}")))
			.isEmpty();

		// 闭集没有变成开放面：近似 id / URL 一律拒
		String nearMiss = """
				{"scenario":"FILTER","fields":[
				  {"key":"level","label":"层级","type":"enum","options":[{"value":"GLOBAL"}]},
				  {"key":"def","label":"配置项","type":"enum",
				   "optionsFrom":{"dependsOn":"level","sourceId":"definitions_all"}}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(nearMiss))).extracting(FormIssue::code)
			.contains(FormIssueCodes.OPTIONS_SOURCE_UNKNOWN);
	}

	@Test
	void conditionalVisibilityDrivesRequirednessOnSubmit() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[
				  {"key":"country","label":"国家","type":"enum","options":[{"value":"CN"},{"value":"US"}]},
				  {"key":"province","label":"省份","type":"text","requiredWhen":{"key":"country","op":"eq","value":"CN"}},
				  {"key":"zip","label":"邮编","type":"text","visibleWhen":{"key":"country","op":"eq","value":"US"}}]}""";
		// 中国：省份必填
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"country\":\"CN\"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_REQUIRED_MISSING);
		// 美国：省份不必填，zip 可见（但没有必填约束）
		assertThat(GenerativeFormRules.resultIssues(schema(schema), values("{\"country\":\"US\"}"))).isEmpty();
		// 隐藏字段不参与复核：US 下提交省份值 / CN 下提交 zip 值都不报错
		assertThat(GenerativeFormRules.resultIssues(schema(schema),
				values("{\"country\":\"US\",\"province\":\"任意\",\"zip\":\"10001\"}"))).isEmpty();
	}

	@Test
	void optionsFromMapValuesAreHonouredOnSubmit() throws Exception {
		String schema = """
				{"scenario":"FILTER","fields":[
				  {"key":"country","label":"国家","type":"enum","options":[{"value":"CN"},{"value":"US"}]},
				  {"key":"province","label":"省份","type":"enum","required":true,
				     "optionsFrom":{"dependsOn":"country","map":{"CN":[{"value":"SC"}],"US":[{"value":"CA"}]}}}]}""";
		assertThat(GenerativeFormRules.resultIssues(schema(schema),
				values("{\"country\":\"CN\",\"province\":\"SC\"}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema),
				values("{\"country\":\"CN\",\"province\":\"CA\"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_OUT_OF_OPTIONS);
	}

	// ── G-E attachment 字段 ─────────────────────────────────────────────────

	@Test
	void attachmentIsTheSeventhWhitelistedType() throws Exception {
		assertThat(GenerativeFormRules.FIELD_TYPES).hasSize(7).endsWith("attachment");
		String ok = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"evidence","label":"附件","type":"attachment","required":true}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(ok))).isEmpty();
		// attachment 只接受 fileId 字符串形状，值校验在闸门（存在/归属/一次性）之前
		assertThat(GenerativeFormRules.resultIssues(schema(ok), values("{\"evidence\":\"att_12345678\"}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(ok), values("{\"evidence\":{\"name\":\"a.txt\"}}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_TYPE_MISMATCH);
		// 文件内容/URL 永不作为值：形状不符即拒
		assertThat(GenerativeFormRules.resultIssues(schema(ok), values("{\"evidence\":\"https://evil.example/a.bin\"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_ATTACHMENT_ID_INVALID);
	}

	@Test
	void attachmentRejectsOptionAndFormatAttributes() throws Exception {
		String bad = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"f","label":"附件","type":"attachment","format":"email","maxLength":10,
				   "options":[{"value":"A"}]}]}""";
		assertThat(GenerativeFormRules.schemaIssues(form(bad))).extracting(FormIssue::code)
			.contains(FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED);
	}

	// ── G-F 长文本 variant ──────────────────────────────────────────────────

	@Test
	void longTextAllowsTwoThousandCharsOnSubmit() throws Exception {
		String schema = """
				{"scenario":"CLARIFY","fields":[
				  {"key":"note","label":"备注","type":"text","variant":"longText"}]}""";
		assertThat(GenerativeFormRules.resultIssues(schema(schema),
				values("{\"note\":\"" + "字".repeat(2000) + "\"}"))).isEmpty();
		assertThat(GenerativeFormRules.resultIssues(schema(schema),
				values("{\"note\":\"" + "字".repeat(2001) + "\"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_TOO_LONG);
		// 普通 text 仍是 200 硬顶
		String plain = """
				{"scenario":"CLARIFY","fields":[{"key":"t","label":"T","type":"text"}]}""";
		assertThat(GenerativeFormRules.resultIssues(schema(plain),
				values("{\"t\":\"" + "字".repeat(201) + "\"}")))
			.extracting(FormIssue::code).containsExactly(FormIssueCodes.VALUE_TOO_LONG);
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private JsonNode form(String json) throws Exception {
		return this.mapper.readTree(json);
	}

	private JsonNode schema(String json) throws Exception {
		return this.mapper.readTree(json);
	}

	private JsonNode values(String json) throws Exception {
		return this.mapper.readTree(json);
	}

	/** 只读投影 {@code Verdict}（用例不改闸门口径，只断言结构化字段已随判定下发）。 */
	static final class GenerativeFormGuardInspector {

		private GenerativeFormGuardInspector() {
		}

		record VerdictView(boolean accepted, List<FormIssue> issues, String render) {
		}

		static VerdictView inspect(GenerativeFormGuard guard, String argumentsJson) {
			var verdict = guard.inspectArguments(argumentsJson);
			return new VerdictView(verdict.accepted(), verdict.issues(), verdict.render());
		}
	}

}
