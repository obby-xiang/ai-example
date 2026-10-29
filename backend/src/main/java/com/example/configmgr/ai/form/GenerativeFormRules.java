package com.example.configmgr.ai.form;

import com.example.configmgr.common.ErrorCodes;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生成式表单的<b>白名单 schema 规则</b>（DC-15 的安全闸本体，纯函数、无 Spring 依赖）。
 *
 * <h2>白名单口径（只有这里列出的类型/属性/上限被接受，其余一律拒绝）</h2>
 * <table>
 * <caption>表单级属性（{@code form}）</caption>
 * <tr><th>属性</th><th>必填</th><th>约束</th></tr>
 * <tr><td>{@code scenario}</td><td>是</td><td>{@code FILTER}（收集筛选条件）/ {@code CLARIFY}（询问澄清）</td></tr>
 * <tr><td>{@code purpose}</td><td>否</td><td>{@code SELECT_DEFINITIONS}（收集配置项选择）/ {@code SET_CONDITIONS}（收集筛选条件，显式给出时固定为<b>单个非必填 text 框</b>，可带 {@code variant=longText}）/ {@code TASK_TYPE}（收集任务类型，固定为<b>单个 enum 单选 {@code EXPORT/IMPORT}</b>）/ {@code GENERAL_CLARIFY}（通用澄清）；缺省时按结构推断（definitions 数据源 → 选择，FILTER → 条件），<b>形状约束只对显式 purpose 生效</b></td></tr>
 * <tr><td>{@code title}</td><td>否</td><td>文本 ≤ {@value #MAX_TITLE_CHARS} 字符</td></tr>
 * <tr><td>{@code fields}</td><td>是</td><td>1..{@value #MAX_FIELDS} 个字段</td></tr>
 * </table>
 *
 * <table>
 * <caption>字段级属性（{@code fields[]}）</caption>
 * <tr><th>属性</th><th>必填</th><th>约束</th></tr>
 * <tr><td>{@code key}</td><td>是</td><td>{@code ^[A-Za-z_][A-Za-z0-9_]*$}，≤ {@value #MAX_KEY_CHARS} 字符，表单内唯一，<b>且不得为原型污染键</b>（{@code __proto__}/{@code constructor}/{@code prototype}，见 {@link #FORBIDDEN_FIELD_KEYS}）</td></tr>
 * <tr><td>{@code label}</td><td>是</td><td>非空文本，≤ {@value #MAX_LABEL_CHARS} 字符</td></tr>
 * <tr><td>{@code type}</td><td>是</td><td>白名单七型：{@code text/number/boolean/date/enum/multi_select/attachment}</td></tr>
 * <tr><td>{@code required}</td><td>否</td><td>布尔（缺省 false）</td></tr>
 * <tr><td>{@code defaultValue}</td><td>否</td><td>类型必须与 {@code type} 相符；enum/multi_select 必须在选项内</td></tr>
 * <tr><td>{@code options}</td><td>enum/multi_select 必填（或改用 optionsFrom）</td><td>{@code [{"value":"…","label":"…"}]}，1..{@value #MAX_OPTIONS} 项，<b>只能是对象</b>（不接受裸字符串）</td></tr>
 * <tr><td>{@code placeholder}</td><td>否</td><td>仅 {@code text/number/date} 可带，≤ {@value #MAX_PLACEHOLDER_CHARS} 字符</td></tr>
 * <tr><td>{@code variant}</td><td>否</td><td>仅 {@code text} 可带，取值只有 {@code longText}（上限放宽到 {@value #MAX_LONGTEXT_VALUE_CHARS}）</td></tr>
 * <tr><td>{@code minLength/maxLength}</td><td>否</td><td>仅 {@code text}；maxLength ≤ 200（longText ≤ 2000）</td></tr>
 * <tr><td>{@code format}</td><td>否</td><td>仅 {@code text}；命名格式枚举 {@code email/tel/url/idcard}，<b>不接受模型自带正则</b></td></tr>
 * <tr><td>{@code min/max/step/integer}</td><td>否</td><td>number 的数值约束；date 只接受 {@code min/max}（ISO 日期）</td></tr>
 * <tr><td>{@code minItems/maxItems}</td><td>否</td><td>仅 {@code multi_select}</td></tr>
 * <tr><td>{@code visibleWhen/requiredWhen/disabledWhen}</td><td>否</td><td>联动条件 {@code {key,op,value|values}}，op ∈ {@code eq/in/notIn/empty/notEmpty/between}</td></tr>
 * <tr><td>{@code optionsFrom}</td><td>否</td><td>选项级联：静态映射 {@code {dependsOn,map}} 或注册数据源 {@code {dependsOn,sourceId}}</td></tr>
 * <tr><td>{@code defaultFrom}</td><td>否</td><td>默认值静态映射 {@code {dependsOn,map}}</td></tr>
 * </table>
 *
 * <h2>安全边界建立在"白名单"而不是"内容黑名单"上</h2>
 * 不在上表内的<b>类型与属性一律拒绝</b>（{@code type:"html"}、{@code attributes}、{@code onClick}、
 * {@code style} 之类根本进不来），因此不存在"模型自由拼 HTML/脚本"的表达能力 —— 这正是 DC-15
 * 要求的限定形态生成式 UI（对比 DC-14 N2 拒绝的完整 A2UI/OpenGenUI 渲染层：那是任意组件树，
 * 这里只有七种预定义控件）。
 *
 * <p><b>GF 优化批新增的两条硬边界</b>：
 * <ul>
 * <li><b>禁模型自带正则</b>：{@code format} 只接受 {@link #TEXT_FORMATS} 命名枚举（ReDoS 面不开放）；</li>
 * <li><b>联动是闭集、不是表达式</b>：{@code visibleWhen/requiredWhen/disabledWhen} 只接受固定 op 的结构化条件，
 * {@code visibleWhen:"a === 1 || alert(1)"} 这类字符串直接判 {@code LINKAGE_CONDITION_INVALID}
 * （前端<b>不执行任何表达式</b>，只按 op 做数据比较）。</li>
 * </ul>
 *
 * <h2>上限值</h2>
 * 单字段文本值 ≤ {@value #MAX_TEXT_VALUE_CHARS}（longText ≤ {@value #MAX_LONGTEXT_VALUE_CHARS}）；
 * {@code form} 序列化 ≤ {@value #MAX_FORM_CHARS} 字符；
 * 回灌值 JSON ≤ {@value #MAX_RESULT_CHARS} 字符（两者都在模型上下文与前端渲染的成本上限之内）。
 *
 * <h2>前端同源约束（本批口径）</h2>
 * 前端 {@code types/form-schema.ts} 与这里对同一条规则给出<b>同一判据</b>：纯空格算空、
 * 200 字上限（longText 2000）、字段 ≤20 / 选项 ≤50 —— 避免"前端放行、后端 400"的两套口径。
 */
public final class GenerativeFormRules {

	/**
	 * 工具名（与 {@code AiTools#generativeForm} 的 {@code @Tool(name)} 一致，由用例对账锁定）。
	 * <p>
	 * <b>双侧同步约束（T2b 顺手项⑤）</b>：前端判定同一字面量用的是
	 * {@code frontend/src/types/ai.ts} 的 {@code GENERATIVE_FORM_TOOL} 常量 ——
	 * 本值变更必须同步改该常量（前端不从后端接口读工具名，字面量漂移会导致挂起卡/守卫判定失灵）。
	 */
	public static final String TOOL_NAME = "generative_form";

	/** schema 级拒绝码（下发前端之前，工具错误结果）。 */
	public static final String SCHEMA_REJECTED = ErrorCodes.FORM_SCHEMA_REJECTED;

	/** 回灌结果级拒绝码（HTTP 400，挂起不被消费）。 */
	public static final String RESULT_REJECTED = ErrorCodes.FORM_RESULT_REJECTED;

	/**
	 * 字段类型白名单（DC-15 六型 + GF 优化批的 {@code attachment} = 七型）。
	 *
	 * <p>顺序即模型看到的描述顺序；{@code attachment} 固定压在末位，便于既有快照式断言稳定。
	 */
	public static final List<String> FIELD_TYPES = List.of("text", "number", "boolean", "date", "enum",
			"multi_select", "attachment");

	/** 表单场景白名单（DC-15 的两个场景）。 */
	public static final Set<String> SCENARIOS = Set.of("FILTER", "CLARIFY");

	/**
	 * 表单<b>用途</b>（槽位放行的判据，不是渲染属性）：宿主只按这个字段判断"这张表是来补哪个缺口的"，
	 * 绝不按标题猜。缺省时按结构推断（见 {@link #purposeOf}），推断不出才是 {@link #DEFAULT_PURPOSE}。
	 */
	public static final String PURPOSE_SELECT_DEFINITIONS = "SELECT_DEFINITIONS";

	public static final String PURPOSE_SET_CONDITIONS = "SET_CONDITIONS";

	/**
	 * 收集<b>任务类型</b>的用途（2026-10-29 闭环批）：只在类型未知时下发，
	 * 形状固定为"单个 enum 单选 {@code EXPORT/IMPORT}"（见 {@link #taskTypeShapeIssues}）——
	 * 用自由文本问类型等于把结构化缺口退回文字反问。
	 */
	public static final String PURPOSE_TASK_TYPE = "TASK_TYPE";

	/** 缺省用途：兼容既有 {@code generative_form} 调用（它们没有 purpose 字段）。 */
	public static final String DEFAULT_PURPOSE = "GENERAL_CLARIFY";

	public static final List<String> FORM_PURPOSES = List.of(PURPOSE_SELECT_DEFINITIONS, PURPOSE_SET_CONDITIONS,
			PURPOSE_TASK_TYPE, DEFAULT_PURPOSE);

	/** 任务类型表单的选项闭集（与 {@code Task.TaskType} 同名）。 */
	public static final List<String> TASK_TYPES = List.of("EXPORT", "IMPORT");

	public static final Set<String> FORM_ATTRIBUTES = Set.of("scenario", "purpose", "title", "fields");

	/** 字段属性白名单（与 {@code AiTools.FormField} 的属性名逐项对账）。 */
	public static final Set<String> FIELD_ATTRIBUTES = Set.of("key", "label", "type", "required", "defaultValue",
			"options", "placeholder", "variant", "minLength", "maxLength", "format", "min", "max", "step",
			"integer", "minItems", "maxItems", "visibleWhen", "requiredWhen", "disabledWhen", "optionsFrom",
			"defaultFrom");

	public static final Set<String> OPTION_ATTRIBUTES = Set.of("value", "label");

	/** 必须带 {@code options}（或 {@code optionsFrom}）的类型。 */
	public static final Set<String> OPTION_TYPES = Set.of("enum", "multi_select");

	/** 允许带 {@code placeholder} 的类型。 */
	public static final Set<String> PLACEHOLDER_TYPES = Set.of("text", "number", "date");

	/** 命名格式枚举（<b>禁模型自带正则</b>：ReDoS 面不开放；新增格式只能改这里并同步前端）。 */
	public static final List<String> TEXT_FORMATS = List.of("email", "tel", "url", "idcard");

	/** 联动条件 op 闭集（前后端同一份；前端只做数据比较，不执行表达式）。 */
	public static final Set<String> CONDITION_OPS = Set.of("eq", "in", "notIn", "empty", "notEmpty", "between");

	/** 条件对象自身的属性白名单。 */
	public static final Set<String> CONDITION_ATTRIBUTES = Set.of("key", "op", "value", "values");

	/** 允许 {@code variant} 的类型与取值（textarea 渲染；上限放宽到 2000）。 */
	public static final Set<String> VARIANT_TYPES = Set.of("text");

	public static final String VARIANT_LONG_TEXT = "longText";

	/**
	 * 后端注册的选项数据源 id（<b>闭集</b>：模型只能引用这里的 id，URL/脚本一律
	 * {@code OPTIONS_SOURCE_UNKNOWN}）。两个内置源：
	 * <ul>
	 * <li>{@code definition_fields}：父值 = 配置定义编码 → 该定义的字段；</li>
	 * <li>{@code definitions}：父值 = 层级（{@code GLOBAL/REGION/PROJECT}）→ 该层级的配置定义
	 * （供"选一个配置项"用，父值为空/未知时返回空列表，<b>不是</b>列出全部通配）。</li>
	 * </ul>
	 */
	public static final Set<String> OPTION_SOURCE_IDS = Set.of("definition_fields", "definitions");

	/**
	 * 字段键黑名单：<b>原型污染键</b>（与前端 {@code FORBIDDEN_FIELD_KEYS} 拦截口径一致，红队问题 3）。
	 *
	 * <p>三者都能通过 {@code KEY_PATTERN}，但赋到 JS 对象上会改写 {@code Object.prototype} 或取不到值，
	 * 前端防御性解析会因此丢掉整张表单 —— 对模型表现为一次无声的取消。后端在此一并拒绝，
	 * 把"前端渲染不了"变成一条可修正的错误提示。
	 */
	public static final Set<String> FORBIDDEN_FIELD_KEYS = Set.of("__proto__", "constructor", "prototype");

	public static final int MAX_FIELDS = 20;

	public static final int MAX_OPTIONS = 50;

	public static final int MAX_KEY_CHARS = 40;

	public static final int MAX_TITLE_CHARS = 100;

	public static final int MAX_LABEL_CHARS = 100;

	public static final int MAX_PLACEHOLDER_CHARS = 100;

	public static final int MAX_OPTION_TEXT_CHARS = 100;

	/** 普通 text 的硬顶（保留；前端同源）。 */
	public static final int MAX_TEXT_VALUE_CHARS = 200;

	/** longText（textarea variant）的硬顶（本批新增；前端同源）。 */
	public static final int MAX_LONGTEXT_VALUE_CHARS = 2000;

	/** 联动的最大依赖深度（边数）：A→B→C 允许，A→B→C→D 拒绝。 */
	public static final int MAX_LINKAGE_DEPTH = 2;

	public static final int MAX_FORM_CHARS = 8000;

	public static final int MAX_RESULT_CHARS = 8000;

	/** 字段键：字母或下划线开头，仅字母/数字/下划线，1..{@value #MAX_KEY_CHARS} 字符。 */
	private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,39}$");

	/** 附件值 = 上传接口签发的 fileId（前端只见 id/文件名/大小，文件内容永不进模型上下文）。 */
	private static final Pattern ATTACHMENT_ID_PATTERN = Pattern.compile("^att_[A-Za-z0-9_-]{8,64}$");

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

	private static final Pattern TEL_PATTERN = Pattern.compile("^\\+?[0-9][0-9\\s\\-()]{5,19}$");

	private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^\\s]+$");

	private static final Pattern IDCARD_PATTERN = Pattern.compile("^(\\d{15}|\\d{17}[\\dXx])$");

	/** 日期口径：严格 ISO（{@code yyyy-MM-dd}，月/日必须两位，非法日历日拒绝 —— 如 2026-02-30）。 */
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

	private GenerativeFormRules() {
	}

	// ── 两道校验的入口 ────────────────────────────────────────────────────────

	/**
	 * 工具入参（{@code {"form":{…}}}）的白名单校验：返回被拒原因清单，空列表 = 通过。
	 *
	 * <p>刻意<b>收集全部原因</b>而不是首错即返：模型一次就能看到要改的每一处，
	 * 少一次"改一处、再被拒、再改"的往返。
	 */
	public static List<String> argumentReasons(JsonNode arguments) {
		return messages(argumentIssues(arguments));
	}

	/** 工具入参的结构化校验（{@link FormIssue}：机器码 + 归属字段 + 人读说明）。 */
	public static List<FormIssue> argumentIssues(JsonNode arguments) {
		List<FormIssue> issues = new ArrayList<>();
		if (arguments == null || !arguments.isObject()) {
			issues.add(FormIssue.form(FormIssueCodes.ARG_NOT_OBJECT,
					"工具参数必须是 JSON 对象（形如 {\"form\":{…}}）"));
			return issues;
		}
		for (String name : names(arguments)) {
			if (!"form".equals(name)) {
				issues.add(FormIssue.form(FormIssueCodes.ARG_EXTRA_KEY,
						"工具参数只接受 form 一个键，出现了多余的键：" + name));
			}
		}
		if (!arguments.has("form")) {
			issues.add(FormIssue.form(FormIssueCodes.ARG_MISSING_FORM,
					"缺少 form 参数（表单 schema 的载体）"));
			return issues;
		}
		issues.addAll(schemaIssues(arguments.get("form")));
		return issues;
	}

	/** 表单 schema 本身的白名单校验（人读原因，保持既有契约）。 */
	public static List<String> schemaReasons(JsonNode form) {
		return messages(schemaIssues(form));
	}

	/** 表单 schema 本身的结构化校验。 */
	public static List<FormIssue> schemaIssues(JsonNode form) {
		List<FormIssue> issues = new ArrayList<>();
		if (form == null || !form.isObject()) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_NOT_OBJECT,
					"form 必须是 JSON 对象（{\"scenario\":…,\"fields\":[…]})"));
			return issues;
		}
		for (String name : names(form)) {
			if (!FORM_ATTRIBUTES.contains(name)) {
				issues.add(FormIssue.form(FormIssueCodes.FORM_EXTRA_ATTRIBUTE,
						"form 不允许的属性：" + name + "（表单属性白名单：" + String.join("/", sorted(FORM_ATTRIBUTES))
								+ "）"));
			}
		}
		int size = form.toString().length();
		if (size > MAX_FORM_CHARS) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_TOO_LARGE,
					"表单 schema 过大：" + size + " 字符（上限 " + MAX_FORM_CHARS + "）"));
		}

		JsonNode scenario = form.get("scenario");
		if (scenario == null || !scenario.isTextual() || !SCENARIOS.contains(scenario.asText())) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_SCENARIO_INVALID,
					"scenario 必填且必须是 " + String.join("/", sorted(SCENARIOS)) + " 之一（实际："
							+ (scenario == null ? "缺失" : scenario.asText()) + "）"));
		}
		JsonNode purpose = form.get("purpose");
		if (purpose != null
				&& (!purpose.isTextual() || !FORM_PURPOSES.contains(purpose.asText()))) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_PURPOSE_INVALID,
					"purpose 只能取 " + String.join("/", FORM_PURPOSES) + " 之一（缺省 = "
							+ DEFAULT_PURPOSE + "）；实际：" + (purpose.isTextual() ? purpose.asText() : purpose)));
		}
		issues.addAll(purposeShapeIssues(form));
		JsonNode title = form.get("title");
		if (title != null) {
			if (!title.isTextual()) {
				issues.add(FormIssue.form(FormIssueCodes.FORM_TITLE_INVALID, "title 必须是文本"));
			}
			else if (title.asText().length() > MAX_TITLE_CHARS) {
				issues.add(FormIssue.form(FormIssueCodes.FORM_TITLE_TOO_LONG,
						"title 过长：" + title.asText().length() + " 字符（上限 " + MAX_TITLE_CHARS + "）"));
			}
		}

		JsonNode fields = form.get("fields");
		if (fields == null) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_FIELDS_MISSING, "fields 必填（至少 1 个字段）"));
			return issues;
		}
		if (!fields.isArray()) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_FIELDS_NOT_ARRAY, "fields 必须是数组"));
			return issues;
		}
		if (fields.isEmpty()) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_FIELDS_EMPTY, "fields 至少 1 个字段"));
			return issues;
		}
		if (fields.size() > MAX_FIELDS) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_FIELDS_TOO_MANY,
					"fields 字段过多：" + fields.size() + " 个（上限 " + MAX_FIELDS + "）"));
		}
		Set<String> keys = new LinkedHashSet<>();
		for (int i = 0; i < fields.size(); i++) {
			issues.addAll(fieldIssues(fields.get(i), i, keys));
		}
		issues.addAll(linkageIssues(fields));
		return dedupe(issues);
	}

	/**
	 * 回灌结果（字段 key → 值）的类型复核：返回被拒原因清单，空列表 = 通过。
	 *
	 * @param form   挂起时下发的表单 schema（取自待决条目的入参原文）
	 * @param values 前端回灌的值对象
	 */
	public static List<String> resultReasons(JsonNode form, JsonNode values) {
		return messages(resultIssues(form, values));
	}

	/** 回灌结果的结构化复核（{@code key} 即字段键，前端据此点亮对应控件）。 */
	public static List<FormIssue> resultIssues(JsonNode form, JsonNode values) {
		List<FormIssue> issues = new ArrayList<>();
		if (values == null || !values.isObject()) {
			issues.add(FormIssue.form(FormIssueCodes.RESULT_NOT_OBJECT,
					"回灌结果必须是 JSON 对象（字段 key → 值）"));
			return issues;
		}
		Map<String, JsonNode> fields = fieldsByKey(form);
		if (fields == null) {
			issues.add(FormIssue.form(FormIssueCodes.RESULT_SCHEMA_UNAVAILABLE,
					"表单 schema 不可用（缺少 fields 数组），无法复核回灌结果 —— schema 在挂起前已通过校验，"
							+ "出现此情形说明入参在挂起后被改动"));
			return issues;
		}
		int size = values.toString().length();
		if (size > MAX_RESULT_CHARS) {
			issues.add(FormIssue.form(FormIssueCodes.RESULT_TOO_LARGE,
					"回灌结果过大：" + size + " 字符（上限 " + MAX_RESULT_CHARS + "）"));
		}
		for (String name : names(values)) {
			if (!fields.containsKey(name)) {
				issues.add(FormIssue.form(FormIssueCodes.RESULT_UNKNOWN_FIELD,
						"未知字段：" + name + "（表单字段：" + String.join(", ", fields.keySet()) + "）"));
			}
		}
		for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
			String key = entry.getKey();
			JsonNode field = entry.getValue();
			if (!isVisible(field, values)) {
				// 显隐联动：不可见字段既不必填，也不复核（前端不渲染、不提交它的值）
				continue;
			}
			boolean required = isRequired(field, values);
			JsonNode value = values.get(key);
			if (value == null || value.isNull()) {
				if (required) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_REQUIRED_MISSING,
							"缺少必填字段：" + key + "（" + field.path("label").asText(key) + "）"));
				}
				continue;
			}
			issues.addAll(valueIssues(key, field, value, required, values));
		}
		return issues;
	}

	// ── purpose 专属的表单形状（2026-10-29 闭环批） ─────────────────────────

	/**
	 * 显式 {@code purpose} 决定的表单形状：<b>只对显式给出的 purpose</b>生效
	 * （缺省 purpose 仍按结构推断，既有 {@code scenario=FILTER} 老表单不受新形状约束）。
	 *
	 * <ul>
	 * <li>{@code TASK_TYPE}：恰好 1 个 {@code enum} 字段，选项正好 {@code EXPORT/IMPORT}；</li>
	 * <li>{@code SET_CONDITIONS}：恰好 1 个非必填 {@code text} 字段（可带 {@code variant=longText}）。</li>
	 * </ul>
	 *
	 * <p>形状不合规 = <b>schema 级拒绝</b>（零副作用：表单不下发、不挂起），模型改一版即可用。
	 */
	private static List<FormIssue> purposeShapeIssues(JsonNode form) {
		String purpose = textOrNull(form.get("purpose"));
		JsonNode fields = form.get("fields");
		if (purpose == null || fields == null || !fields.isArray() || fields.isEmpty()) {
			// 缺 fields / 空 fields 由既有规则报，这里不重复
			return List.of();
		}
		if (PURPOSE_TASK_TYPE.equals(purpose)) {
			return taskTypeShapeIssues(fields);
		}
		if (PURPOSE_SET_CONDITIONS.equals(purpose)) {
			return conditionShapeIssues(fields);
		}
		return List.of();
	}

	private static List<FormIssue> taskTypeShapeIssues(JsonNode fields) {
		List<FormIssue> issues = new ArrayList<>();
		if (fields.size() != 1) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_FIELDS_TOO_MANY,
					"purpose=TASK_TYPE 的表单只允许 1 个字段（任务类型的单个单选），实际 " + fields.size() + " 个"));
			return issues;
		}
		JsonNode field = fields.get(0);
		String at = fieldKeyOrIndex(field, 0);
		String type = textOrNull(field.get("type"));
		if (!"enum".equals(type)) {
			issues.add(FormIssue.field(at, FormIssueCodes.FIELD_TYPE_UNSUPPORTED,
					"purpose=TASK_TYPE 的字段类型必须是 enum（单个单选；自由文本等于文字反问，多选会得到多个类型），实际 type="
							+ type));
			return issues;
		}
		List<String> options = optionValues(field);
		if (options.size() != TASK_TYPES.size() || !options.containsAll(TASK_TYPES)) {
			issues.add(FormIssue.field(at, FormIssueCodes.FIELD_ATTRIBUTE_INVALID,
					"purpose=TASK_TYPE 的选项必须正好是 " + String.join("/", TASK_TYPES) + "（实际："
							+ (options.isEmpty() ? "无" : String.join("/", options)) + "）"));
		}
		return issues;
	}

	private static List<FormIssue> conditionShapeIssues(JsonNode fields) {
		List<FormIssue> issues = new ArrayList<>();
		if (fields.size() != 1) {
			issues.add(FormIssue.form(FormIssueCodes.FORM_FIELDS_TOO_MANY,
					"purpose=SET_CONDITIONS 的表单只允许 1 个自由文本框（条件由模型从这段文本解析），实际 " + fields.size()
							+ " 个"));
			return issues;
		}
		JsonNode field = fields.get(0);
		String at = fieldKeyOrIndex(field, 0);
		String type = textOrNull(field.get("type"));
		if (!"text".equals(type)) {
			issues.add(FormIssue.field(at, FormIssueCodes.FIELD_TYPE_UNSUPPORTED,
					"purpose=SET_CONDITIONS 的字段类型必须是 text（自由文本，可带 variant=longText），实际 type=" + type));
		}
		JsonNode required = field.get("required");
		if (required != null && required.isBoolean() && required.asBoolean()) {
			issues.add(FormIssue.field(at, FormIssueCodes.FIELD_REQUIRED_INVALID,
					"条件收集框必须非必填：留空 = 不设筛选（直接 confirm_step 全量推进），写成 required=true 会把留空卡死"));
		}
		return issues;
	}

	/** 问题归属：有 key 用 key，否则用 {@code fields[i]}（与字段级校验同一口径）。 */
	private static String fieldKeyOrIndex(JsonNode field, int index) {
		String key = textOrNull(field.get("key"));
		return key == null || key.isBlank() ? "fields[" + index + "]" : key;
	}

	// ── 字段级校验 ────────────────────────────────────────────────────────────

	private static List<FormIssue> fieldIssues(JsonNode field, int index, Set<String> keys) {
		List<FormIssue> issues = new ArrayList<>();
		String at = "fields[" + index + "]";
		if (field == null || !field.isObject()) {
			issues.add(FormIssue.field(at, FormIssueCodes.FIELD_NOT_OBJECT, at + " 必须是 JSON 对象"));
			return issues;
		}
		String key = textOrNull(field.get("key"));
		String keyForIssue = key == null || key.isBlank() ? at : key;
		for (String name : names(field)) {
			if (!FIELD_ATTRIBUTES.contains(name)) {
				issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_EXTRA_ATTRIBUTE,
						at + " 不允许的属性：" + name + "（字段属性白名单："
								+ String.join("/", sorted(FIELD_ATTRIBUTES)) + "）"));
			}
		}

		if (key == null || key.isBlank()) {
			issues.add(FormIssue.field(at, FormIssueCodes.FIELD_KEY_MISSING, at + ".key 必填且不得为空"));
		}
		else if (FORBIDDEN_FIELD_KEYS.contains(key)) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_KEY_FORBIDDEN,
					at + ".key \"" + key + "\" 是原型污染键（" + String.join("/", sorted(FORBIDDEN_FIELD_KEYS))
							+ "），拒绝（与前端 FORBIDDEN_FIELD_KEYS 拦截口径一致）"));
		}
		else if (!KEY_PATTERN.matcher(key).matches()) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_KEY_INVALID,
					at + ".key \"" + key + "\" 不合法（字母或下划线开头，仅字母/数字/下划线，长度 ≤" + MAX_KEY_CHARS
							+ "）"));
		}
		else if (!keys.add(key)) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_KEY_DUPLICATE,
					at + ".key \"" + key + "\" 与前面的字段重复（同一表单内字段键必须唯一）"));
		}

		String label = textOrNull(field.get("label"));
		if (label == null || label.isBlank()) {
			issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_LABEL_MISSING, at + ".label 必填且不得为空"));
		}
		else if (label.length() > MAX_LABEL_CHARS) {
			issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_LABEL_TOO_LONG,
					at + ".label 过长：" + label.length() + " 字符（上限 " + MAX_LABEL_CHARS + "）"));
		}

		String type = textOrNull(field.get("type"));
		if (type == null) {
			issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_TYPE_MISSING, at + ".type 必填"));
			return issues;
		}
		if (!FIELD_TYPES.contains(type)) {
			issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_TYPE_UNSUPPORTED,
					at + ".type=" + type + " 不在白名单类型内（" + String.join("/", FIELD_TYPES) + "）"));
			return issues;
		}

		JsonNode requiredNode = field.get("required");
		if (requiredNode != null && !requiredNode.isBoolean()) {
			issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_REQUIRED_INVALID,
					at + ".required 必须是布尔（true/false）"));
		}

		JsonNode placeholder = field.get("placeholder");
		if (placeholder != null) {
			if (!placeholder.isTextual()) {
				issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_PLACEHOLDER_INVALID,
						at + ".placeholder 必须是文本"));
			}
			else if (placeholder.asText().length() > MAX_PLACEHOLDER_CHARS) {
				issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_PLACEHOLDER_TOO_LONG,
						at + ".placeholder 过长：" + placeholder.asText().length() + " 字符（上限 "
								+ MAX_PLACEHOLDER_CHARS + "）"));
			}
			else if (!PLACEHOLDER_TYPES.contains(type)) {
				issues.add(FormIssue.field(keyForIssue, FormIssueCodes.FIELD_PLACEHOLDER_TYPE_UNSUPPORTED,
						at + ".placeholder 仅 " + String.join("/", sorted(PLACEHOLDER_TYPES))
								+ " 类型可带（当前 type=" + type + "）"));
			}
		}

		issues.addAll(constraintIssues(field, at, keyForIssue, type));

		JsonNode options = field.get("options");
		if (OPTION_TYPES.contains(type)) {
			if (options == null && field.get("optionsFrom") == null) {
				issues.add(FormIssue.field(keyForIssue, FormIssueCodes.OPTIONS_MISSING,
						at + ".options 必填（type=" + type + " 必须在选项内取值，或改用 optionsFrom）"));
			}
			else if (options != null) {
				issues.addAll(optionIssues(options, at, keyForIssue));
			}
		}
		else if (options != null) {
			issues.add(FormIssue.field(keyForIssue, FormIssueCodes.OPTIONS_NOT_ALLOWED,
					at + ".options 仅 enum/multi_select 可带（当前 type=" + type + "）"));
		}

		JsonNode defaultValue = field.get("defaultValue");
		if (defaultValue != null) {
			issues.addAll(defaultValueIssues(defaultValue, at, keyForIssue, field));
		}
		return issues;
	}

	// ── 校验最小集（G-C / G-F） ─────────────────────────────────────────────

	private static List<FormIssue> constraintIssues(JsonNode field, String at, String key, String type) {
		List<FormIssue> issues = new ArrayList<>();
		boolean text = "text".equals(type);
		boolean number = "number".equals(type);
		boolean date = "date".equals(type);
		boolean multi = "multi_select".equals(type);

		String variant = textOrNull(field.get("variant"));
		boolean longText = false;
		if (variant != null) {
			if (!VARIANT_TYPES.contains(type)) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
						at + ".variant 仅 " + String.join("/", sorted(VARIANT_TYPES)) + " 可带（当前 type=" + type + "）"));
			}
			else if (!VARIANT_LONG_TEXT.equals(variant)) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_VARIANT_UNSUPPORTED,
						at + ".variant=" + variant + " 不在白名单内（只有 " + VARIANT_LONG_TEXT + "）"));
			}
			else {
				longText = true;
			}
		}
		int hardMax = longText ? MAX_LONGTEXT_VALUE_CHARS : MAX_TEXT_VALUE_CHARS;

		JsonNode minLength = field.get("minLength");
		JsonNode maxLength = field.get("maxLength");
		if (minLength != null || maxLength != null) {
			if (!text) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
						at + ".minLength/maxLength 仅 text 可带（当前 type=" + type + "）"));
			}
			else {
				Integer min = integerOrNull(minLength);
				Integer max = integerOrNull(maxLength);
				if ((minLength != null && min == null) || (maxLength != null && max == null)) {
					issues.add(FormIssue.field(key, FormIssueCodes.FIELD_LENGTH_BOUND_INVALID,
							at + ".minLength/maxLength 必须是整数"));
				}
				else {
					if (min != null && min < 0) {
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_LENGTH_BOUND_INVALID,
								at + ".minLength 不得为负（实际 " + min + "）"));
					}
					if (max != null && max < 0) {
						// 负数上界无法满足：值校验阶段 text.length() > -1 恒真，用户填什么都过不了闸。
						// schema 阶段拒绝（零副作用），模型改一次即可用。
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_LENGTH_BOUND_INVALID,
								at + ".maxLength 不得为负（实际 " + max + "）"));
					}
					if (max != null && max > hardMax) {
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_MAX_LENGTH_TOO_LARGE,
								at + ".maxLength 超过硬顶：" + max + "（上限 " + hardMax + "）"));
					}
					if (min != null && max != null && min > max) {
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX,
								at + ".minLength 不得大于 maxLength（" + min + " > " + max + "）"));
					}
				}
			}
		}

		String format = textOrNull(field.get("format"));
		if (field.has("format")) {
			if (format == null) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_INVALID, at + ".format 必须是文本"));
			}
			else if (!text) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
						at + ".format 仅 text 可带（当前 type=" + type + "）"));
			}
			else if (!TEXT_FORMATS.contains(format)) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_FORMAT_UNSUPPORTED,
						at + ".format=" + format + " 不在命名格式内（" + String.join("/", TEXT_FORMATS)
								+ "；不接受正则表达式）"));
			}
		}

		JsonNode min = field.get("min");
		JsonNode max = field.get("max");
		JsonNode step = field.get("step");
		JsonNode integer = field.get("integer");
		if (min != null || max != null || step != null || integer != null) {
			if (number) {
				issues.addAll(numberBoundIssues(at, key, min, max, step, integer));
			}
			else if (date) {
				if (step != null || integer != null) {
					issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
							at + ".step/integer 仅 number 可带（当前 type=date）"));
				}
				issues.addAll(dateBoundIssues(at, key, min, max));
			}
			else {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
						at + ".min/max/step/integer 仅 number 或 date 可带（当前 type=" + type + "）"));
			}
		}

		JsonNode minItems = field.get("minItems");
		JsonNode maxItems = field.get("maxItems");
		if (minItems != null || maxItems != null) {
			if (!multi) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
						at + ".minItems/maxItems 仅 multi_select 可带（当前 type=" + type + "）"));
			}
			else {
				Integer minCount = integerOrNull(minItems);
				Integer maxCount = integerOrNull(maxItems);
				if ((minItems != null && minCount == null) || (maxItems != null && maxCount == null)) {
					issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ITEMS_BOUND_INVALID,
							at + ".minItems/maxItems 必须是整数"));
				}
				else {
					if (minCount != null && minCount < 0) {
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ITEMS_BOUND_INVALID,
								at + ".minItems 不得为负（实际 " + minCount + "）"));
					}
					if (maxCount != null && maxCount < 0) {
						// 负数上界在值校验阶段是"任何选集都超限"：schema 阶段拒绝，不把死约束发给用户。
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ITEMS_BOUND_INVALID,
								at + ".maxItems 不得为负（实际 " + maxCount + "）"));
					}
					if (minCount != null && maxCount != null && minCount > maxCount) {
						issues.add(FormIssue.field(key, FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX,
								at + ".minItems 不得大于 maxItems（" + minCount + " > " + maxCount + "）"));
					}
				}
			}
		}
		return issues;
	}

	private static List<FormIssue> numberBoundIssues(String at, String key, JsonNode min, JsonNode max,
			JsonNode step, JsonNode integer) {
		List<FormIssue> issues = new ArrayList<>();
		BigDecimal minValue = finiteDecimalOrNull(min);
		BigDecimal maxValue = finiteDecimalOrNull(max);
		if ((min != null && minValue == null) || (max != null && maxValue == null)) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_NUMBER_BOUND_INVALID,
					at + ".min/max 必须是有限数字（NaN/Infinity/1e999 这类值不接受）"));
		}
		else if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX,
					at + ".min 不得大于 max（" + minValue + " > " + maxValue + "）"));
		}
		if (step != null) {
			Double stepValue = finiteOrNull(step);
			if (stepValue == null || stepValue <= 0) {
				issues.add(FormIssue.field(key, FormIssueCodes.FIELD_STEP_INVALID,
						at + ".step 必须是有限的正数（实际 " + step + "）"));
			}
		}
		if (integer != null && !integer.isBoolean()) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_INVALID,
					at + ".integer 必须是布尔（true/false）"));
		}
		return issues;
	}

	private static List<FormIssue> dateBoundIssues(String at, String key, JsonNode min, JsonNode max) {
		List<FormIssue> issues = new ArrayList<>();
		String minDate = textOrNull(min);
		String maxDate = textOrNull(max);
		if (min != null && (minDate == null || !isIsoDate(minDate))) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_DATE_BOUND_INVALID,
					at + ".min 必须是合法日期（yyyy-MM-dd），实际是 " + min));
		}
		if (max != null && (maxDate == null || !isIsoDate(maxDate))) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_DATE_BOUND_INVALID,
					at + ".max 必须是合法日期（yyyy-MM-dd），实际是 " + max));
		}
		if (minDate != null && maxDate != null && isIsoDate(minDate) && isIsoDate(maxDate)
				&& minDate.compareTo(maxDate) > 0) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_MIN_GREATER_THAN_MAX,
					at + ".min 不得晚于 max（" + minDate + " > " + maxDate + "）"));
		}
		return issues;
	}

	private static List<FormIssue> optionIssues(JsonNode options, String at, String key) {
		List<FormIssue> issues = new ArrayList<>();
		if (!options.isArray()) {
			issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_NOT_ARRAY, at + ".options 必须是数组"));
			return issues;
		}
		if (options.isEmpty()) {
			issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_EMPTY, at + ".options 至少 1 个选项"));
		}
		if (options.size() > MAX_OPTIONS) {
			issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_TOO_MANY,
					at + ".options 选项过多：" + options.size() + " 个（上限 " + MAX_OPTIONS + "）"));
		}
		Set<String> seen = new LinkedHashSet<>();
		for (int i = 0; i < options.size(); i++) {
			JsonNode option = options.get(i);
			String where = at + ".options[" + i + "]";
			if (option == null || !option.isObject()) {
				issues.add(FormIssue.field(key, FormIssueCodes.OPTION_NOT_OBJECT,
						where + " 必须是 {\"value\":\"…\",\"label\":\"…\"} 对象（不接受裸字符串）"));
				continue;
			}
			for (String name : names(option)) {
				if (!OPTION_ATTRIBUTES.contains(name)) {
					issues.add(FormIssue.field(key, FormIssueCodes.OPTION_EXTRA_ATTRIBUTE,
							where + " 不允许的属性：" + name + "（选项属性白名单：value/label）"));
				}
			}
			String value = textOrNull(option.get("value"));
			if (value == null || value.isBlank()) {
				issues.add(FormIssue.field(key, FormIssueCodes.OPTION_VALUE_MISSING,
						where + ".value 必填且不得为空"));
			}
			else if (value.length() > MAX_OPTION_TEXT_CHARS) {
				issues.add(FormIssue.field(key, FormIssueCodes.OPTION_VALUE_TOO_LONG,
						where + ".value 过长：" + value.length() + " 字符（上限 " + MAX_OPTION_TEXT_CHARS + "）"));
			}
			else if (!seen.add(value)) {
				issues.add(FormIssue.field(key, FormIssueCodes.OPTION_VALUE_DUPLICATE,
						where + ".value \"" + value + "\" 与前一个选项重复"));
			}
			String label = textOrNull(option.get("label"));
			if (label != null && label.length() > MAX_OPTION_TEXT_CHARS) {
				issues.add(FormIssue.field(key, FormIssueCodes.OPTION_LABEL_TOO_LONG,
						where + ".label 过长：" + label.length() + " 字符（上限 " + MAX_OPTION_TEXT_CHARS + "）"));
			}
		}
		return issues;
	}

	private static List<FormIssue> defaultValueIssues(JsonNode value, String at, String key, JsonNode field) {
		List<FormIssue> issues = new ArrayList<>();
		String type = field.path("type").asText("");
		List<String> options = optionValues(field);
		String prefix = at + ".defaultValue";
		switch (type) {
			case "text" -> {
				if (!value.isTextual()) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
							prefix + " 期望 text（字符串），实际是" + typeName(value)));
				}
				else {
					int limit = VARIANT_LONG_TEXT.equals(field.path("variant").asText())
							? MAX_LONGTEXT_VALUE_CHARS : MAX_TEXT_VALUE_CHARS;
					if (value.asText().length() > limit) {
						issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TOO_LONG,
								prefix + " 过长：" + value.asText().length() + " 字符（上限 " + limit + "）"));
					}
				}
			}
			case "number" -> {
				if (!value.isNumber()) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
							prefix + " 期望 number（JSON 数字），实际是" + typeName(value)));
				}
			}
			case "boolean" -> {
				if (!value.isBoolean()) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
							prefix + " 期望 boolean，实际是" + typeName(value)));
				}
			}
			case "date" -> {
				if (!value.isTextual() || !isIsoDate(value.asText())) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
							prefix + " 期望合法日期（yyyy-MM-dd），实际是 " + value));
				}
			}
			case "enum" -> {
				if (!value.isTextual()) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
							prefix + " 期望字符串（单选值），实际是" + typeName(value)));
				}
				else if (!options.contains(value.asText())) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_OUT_OF_OPTIONS,
							prefix + " \"" + value.asText() + "\" 不在选项内（" + String.join("/", options) + "）"));
				}
			}
			case "multi_select" -> {
				if (!value.isArray()) {
					issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
							prefix + " 期望数组（多选默认值），实际是" + typeName(value)));
				}
				else {
					Set<String> seen = new LinkedHashSet<>();
					for (JsonNode element : value) {
						if (!element.isTextual() || !options.contains(element.asText())) {
							issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_OUT_OF_OPTIONS,
									prefix + " 含不在选项内的值：" + element + "（可选：" + String.join("/", options) + "）"));
							continue;
						}
						if (!seen.add(element.asText())) {
							issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_DUPLICATE,
									prefix + " 含重复值：" + element.asText()));
						}
					}
				}
			}
			case "attachment" -> issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
					prefix + " 不允许（attachment 的值由用户上传产生，模型不得预填）"));
			default -> issues.add(FormIssue.field(key, FormIssueCodes.DEFAULT_TYPE_MISMATCH,
					prefix + " 无法校验（type=" + type + " 不在白名单内)"));
		}
		return issues;
	}

	// ── 联动五原语（G-D） ───────────────────────────────────────────────────

	private static List<FormIssue> linkageIssues(JsonNode fields) {
		List<FormIssue> issues = new ArrayList<>();
		Map<String, Integer> indexByKey = new LinkedHashMap<>();
		Set<String> declared = new LinkedHashSet<>();
		for (int i = 0; i < fields.size(); i++) {
			JsonNode field = fields.get(i);
			String key = textOrNull(field.get("key"));
			if (key != null && !key.isBlank()) {
				declared.add(key);
				indexByKey.putIfAbsent(key, i);
			}
		}
		Map<String, Set<String>> deps = new LinkedHashMap<>();
		for (String key : declared) {
			deps.put(key, new LinkedHashSet<>());
		}
		for (int i = 0; i < fields.size(); i++) {
			JsonNode field = fields.get(i);
			String key = textOrNull(field.get("key"));
			if (key == null || key.isBlank()) {
				continue;
			}
			String at = "fields[" + i + "]";
			for (String attr : List.of("visibleWhen", "requiredWhen", "disabledWhen")) {
				JsonNode condition = field.get(attr);
				if (condition == null) {
					continue;
				}
				issues.addAll(conditionIssues(attr, condition, at, key, declared, deps.get(key)));
			}
			JsonNode optionsFrom = field.get("optionsFrom");
			if (optionsFrom != null) {
				issues.addAll(optionsFromIssues(optionsFrom, at, key, field, declared, deps.get(key)));
				if (field.get("options") != null) {
					issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_SOURCE_CONFLICT,
							at + ".options 与 optionsFrom 不能同时出现（二选一：静态选项 or 级联选项）"));
				}
				if (isStaticallyRequired(field) && OPTION_TYPES.contains(field.path("type").asText(""))
						&& field.get("options") == null && !usesOptionSource(field)
						&& optionsFrom.path("map").isObject() && optionValues(field).isEmpty()) {
					// 空选项 + required = 死路（无分支必填）：用户永远填不了，模型必须重出可填表单。
					// sourceId 级联的选项在后端数据里，规则层不枚举，故不在此判空。
					issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_EMPTY,
							at + ".optionsFrom 解析不出任何可选项（映射各分支为空或缺失），但字段 required=true —— "
									+ "该必填字段永远无法填写，请至少给 1 个分支选项（不想必填请去掉 required）"));
				}
			}
			JsonNode defaultFrom = field.get("defaultFrom");
			if (defaultFrom != null) {
				issues.addAll(defaultFromIssues(defaultFrom, at, key, field, declared, deps.get(key)));
			}
		}
		issues.addAll(cycleIssues(deps));
		issues.addAll(depthIssues(deps));
		return issues;
	}

	private static List<FormIssue> conditionIssues(String attr, JsonNode condition, String at, String key,
			Set<String> declared, Set<String> depsOfField) {
		List<FormIssue> issues = new ArrayList<>();
		String where = at + "." + attr;
		if (!condition.isObject()) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
					where + " 必须是结构化条件对象 {\"key\":…,\"op\":…,\"value\"/\"values\":…}（不接受表达式字符串）"));
			return issues;
		}
		for (String name : names(condition)) {
			if (!CONDITION_ATTRIBUTES.contains(name)) {
				issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
						where + " 不允许的属性：" + name + "（条件属性白名单：key/op/value/values）"));
			}
		}
		String ref = textOrNull(condition.get("key"));
		if (ref == null || ref.isBlank()) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID, where + ".key 必填"));
		}
		else if (ref.equals(key)) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_SELF_REFERENCE,
					where + " 不得引用字段自身（" + key + "）"));
		}
		else if (!declared.contains(ref)) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_UNKNOWN_FIELD,
					where + ".key \"" + ref + "\" 未在表单字段中声明（已声明：" + String.join(", ", declared) + "）"));
		}
		else {
			depsOfField.add(ref);
		}
		String op = textOrNull(condition.get("op"));
		if (op == null || !CONDITION_OPS.contains(op)) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_OPERATOR_UNSUPPORTED,
					where + ".op=" + op + " 不在支持的操作内（" + String.join("/", sorted(CONDITION_OPS)) + "）"));
			return issues;
		}
		JsonNode value = condition.get("value");
		JsonNode values = condition.get("values");
		switch (op) {
			case "eq" -> {
				if (value == null) {
					issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
							where + " op=eq 必须带 value"));
				}
			}
			case "in", "notIn" -> {
				if (values == null || !values.isArray() || values.isEmpty()) {
					issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
							where + " op=" + op + " 必须带非空 values 数组"));
				}
			}
			case "between" -> {
				if (values == null || !values.isArray() || values.size() != 2) {
					issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
							where + " op=between 必须带两个元素的 values 数组"));
				}
			}
			default -> {
				// empty / notEmpty 不需要参数
			}
		}
		return issues;
	}

	private static List<FormIssue> optionsFromIssues(JsonNode optionsFrom, String at, String key, JsonNode field,
			Set<String> declared, Set<String> depsOfField) {
		List<FormIssue> issues = new ArrayList<>();
		String where = at + ".optionsFrom";
		if (!OPTION_TYPES.contains(field.path("type").asText(""))) {
			issues.add(FormIssue.field(key, FormIssueCodes.FIELD_ATTRIBUTE_TYPE_UNSUPPORTED,
					where + " 仅 enum/multi_select 可带（当前 type=" + field.path("type").asText("") + "）"));
		}
		if (!optionsFrom.isObject()) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
					where + " 必须是对象 {\"dependsOn\":…,\"map\"|\"sourceId\":…}"));
			return issues;
		}
		issues.addAll(dependsOnIssues(optionsFrom, where, key, declared, depsOfField));
		JsonNode map = optionsFrom.get("map");
		String sourceId = textOrNull(optionsFrom.get("sourceId"));
		if (map == null && sourceId == null) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_MAP_INVALID,
					where + " 必须提供静态映射 map 或注册数据源 sourceId 之一"));
			return issues;
		}
		if (map != null && sourceId != null) {
			issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_SOURCE_CONFLICT,
					where + " 的 map 与 sourceId 只能二选一"));
		}
		if (sourceId != null && !OPTION_SOURCE_IDS.contains(sourceId)) {
			issues.add(FormIssue.field(key, FormIssueCodes.OPTIONS_SOURCE_UNKNOWN,
					where + ".sourceId=\"" + sourceId + "\" 不是后端注册的数据源（可用："
							+ String.join("/", sorted(OPTION_SOURCE_IDS)) + "；不接受 URL/脚本）"));
		}
		if (map != null) {
			if (!map.isObject()) {
				issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_MAP_INVALID, where + ".map 必须是对象"));
			}
			else {
				for (Map.Entry<String, JsonNode> entry : iterable(map)) {
					if (!entry.getValue().isArray()) {
						issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_MAP_INVALID,
								where + ".map[\"" + entry.getKey() + "\"] 必须是选项数组"));
						continue;
					}
					issues.addAll(optionIssues(entry.getValue(),
							where + ".map[\"" + entry.getKey() + "\"]", key));
				}
			}
		}
		return issues;
	}

	private static List<FormIssue> defaultFromIssues(JsonNode defaultFrom, String at, String key, JsonNode field,
			Set<String> declared, Set<String> depsOfField) {
		List<FormIssue> issues = new ArrayList<>();
		String where = at + ".defaultFrom";
		if (!defaultFrom.isObject()) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID,
					where + " 必须是对象 {\"dependsOn\":…,\"map\":{…}}"));
			return issues;
		}
		issues.addAll(dependsOnIssues(defaultFrom, where, key, declared, depsOfField));
		JsonNode map = defaultFrom.get("map");
		if (map == null || !map.isObject()) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_MAP_INVALID,
					where + ".map 必须是对象（父值 → 默认值）"));
			return issues;
		}
		String type = field.path("type").asText("");
		for (Map.Entry<String, JsonNode> entry : iterable(map)) {
			JsonNode value = entry.getValue();
			boolean ok = switch (type) {
				case "text", "date", "enum", "attachment" -> value.isTextual();
				case "number" -> value.isNumber();
				case "boolean" -> value.isBoolean();
				case "multi_select" -> value.isArray();
				default -> false;
			};
			if (!ok) {
				issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_MAP_INVALID,
						where + ".map[\"" + entry.getKey() + "\"] 的值与 type=" + type + " 不符"));
			}
		}
		return issues;
	}

	private static List<FormIssue> dependsOnIssues(JsonNode node, String where, String key,
			Set<String> declared, Set<String> depsOfField) {
		List<FormIssue> issues = new ArrayList<>();
		String dependsOn = textOrNull(node.get("dependsOn"));
		if (dependsOn == null || dependsOn.isBlank()) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_CONDITION_INVALID, where + ".dependsOn 必填"));
		}
		else if (dependsOn.equals(key)) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_SELF_REFERENCE,
					where + " 不得依赖字段自身（" + key + "）"));
		}
		else if (!declared.contains(dependsOn)) {
			issues.add(FormIssue.field(key, FormIssueCodes.LINKAGE_UNKNOWN_FIELD,
					where + ".dependsOn \"" + dependsOn + "\" 未在表单字段中声明"));
		}
		else {
			depsOfField.add(dependsOn);
		}
		return issues;
	}

	/** 有向依赖图上的环检测（每个环节点报一次；自引用已在条件校验里单独报过，图上忽略自环）。 */
	private static List<FormIssue> cycleIssues(Map<String, Set<String>> deps) {
		List<FormIssue> issues = new ArrayList<>();
		Set<String> done = new HashSet<>();
		Set<String> stack = new LinkedHashSet<>();
		for (String node : deps.keySet()) {
			visit(node, deps, done, stack, issues);
		}
		return issues;
	}

	private static void visit(String node, Map<String, Set<String>> deps, Set<String> done,
			Set<String> stack, List<FormIssue> issues) {
		if (done.contains(node) || stack.contains(node)) {
			return;
		}
		stack.add(node);
		for (String next : deps.getOrDefault(node, Set.of())) {
			if (next.equals(node)) {
				continue;
			}
			if (stack.contains(next)) {
				issues.add(FormIssue.field(node, FormIssueCodes.LINKAGE_CYCLE,
						"联动依赖成环：" + String.join(" → ", stack) + " → " + next
								+ "（依赖必须无环，请改成单向级联）"));
				continue;
			}
			visit(next, deps, done, stack, issues);
		}
		stack.remove(node);
		done.add(node);
	}

	/** 依赖深度（最长链的边数）超过 {@value #MAX_LINKAGE_DEPTH} 即拒绝。 */
	private static List<FormIssue> depthIssues(Map<String, Set<String>> deps) {
		List<FormIssue> issues = new ArrayList<>();
		Map<String, Integer> memo = new HashMap<>();
		for (String node : deps.keySet()) {
			int depth = depthOf(node, deps, memo, new HashSet<>());
			if (depth > MAX_LINKAGE_DEPTH) {
				issues.add(FormIssue.field(node, FormIssueCodes.LINKAGE_DEPTH_EXCEEDED,
						"联动依赖过深：" + node + " 的依赖链有 " + depth + " 层（上限 " + MAX_LINKAGE_DEPTH
								+ " 层；A→B→C 允许，A→B→C→D 拒绝）"));
			}
		}
		return issues;
	}

	private static int depthOf(String node, Map<String, Set<String>> deps, Map<String, Integer> memo,
			Set<String> visiting) {
		Integer cached = memo.get(node);
		if (cached != null) {
			return cached;
		}
		if (!visiting.add(node)) {
			return 0;
		}
		int best = 0;
		for (String next : deps.getOrDefault(node, Set.of())) {
			if (next.equals(node)) {
				continue;
			}
			best = Math.max(best, 1 + depthOf(next, deps, memo, visiting));
		}
		visiting.remove(node);
		memo.put(node, best);
		return best;
	}

	/** 字段是否静态声明了 {@code required:true}（{@code requiredWhen} 在 schema 期无法求值，不算）。 */
	private static boolean isStaticallyRequired(JsonNode field) {
		JsonNode required = field.get("required");
		return required != null && required.isBoolean() && required.asBoolean();
	}

	/** 是否引用了后端注册的选项数据源（选项在数据里，规则层无法枚举，也就不能在 schema 期判"空"）。 */
	private static boolean usesOptionSource(JsonNode field) {
		JsonNode optionsFrom = field.get("optionsFrom");
		return optionsFrom != null && optionsFrom.isObject() && textOrNull(optionsFrom.get("sourceId")) != null;
	}

	// ── 回灌值的类型复核 ─────────────────────────────────────────────────────

	/**
	 * 数值比较的<b>精确</b>口径（C N10）：两个 JSON 整数按 {@code BigInteger} 精确比较，其余按
	 * {@link BigDecimal#compareTo} —— 都不过 {@code double} 中转，{@code >2^53} 的整数不再丢位。
	 * <b>不引入 epsilon</b>：小数相等仍是严格相等（{@code 0.1} 就是 {@code 0.1}）。
	 */
	private static int compareNumbers(JsonNode left, JsonNode right) {
		if (left.isIntegralNumber() && right.isIntegralNumber()) {
			return left.bigIntegerValue().compareTo(right.bigIntegerValue());
		}
		try {
			return left.decimalValue().compareTo(right.decimalValue());
		}
		catch (NumberFormatException ex) {
			// 非有限数（JSON 的 1e999 解析成 Infinity/NaN）没有 BigDecimal 表示：退回 double 比较
			// （仍是严格比较，不引入 epsilon）；这类值本身会被 valueIssues 判成类型错误。
			return Double.compare(left.asDouble(), right.asDouble());
		}
	}

	/** JSON 数字 → 精确 {@link BigDecimal}；非数字与 NaN/±Infinity 返回 {@code null}（同 finiteOrNull 口径）。 */
	private static BigDecimal finiteDecimalOrNull(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		try {
			BigDecimal value = node.decimalValue();
			return Double.isFinite(value.doubleValue()) ? value : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** JSON 数字是否为精确整数（{@code 2.0} 算整数；{@code 2.2} 不算；大整数不丢位）。 */
	private static boolean isExactInteger(JsonNode node) {
		if (node.isIntegralNumber()) {
			return true;
		}
		return node.decimalValue().stripTrailingZeros().scale() <= 0;
	}

	private static List<FormIssue> valueIssues(String key, JsonNode field, JsonNode value, boolean required,
			JsonNode values) {
		List<FormIssue> issues = new ArrayList<>();
		String type = field.path("type").asText("");
		// enum/multi_select 的可选集按**当前回灌值**解析（静态 options 或 optionsFrom.map 的父值分支）；
		// null = 规则层无法枚举（sourceId 级联，选项在后端数据源里）⇒ 不做成员校验。
		List<String> options = switch (type) {
			case "enum", "multi_select" -> runtimeOptionValues(field, values);
			default -> List.of();
		};
		switch (type) {
			case "text" -> {
				if (!value.isTextual()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望 text（字符串），实际是" + typeName(value)));
					break;
				}
				String text = value.asText();
				int limit = VARIANT_LONG_TEXT.equals(field.path("variant").asText())
						? MAX_LONGTEXT_VALUE_CHARS : MAX_TEXT_VALUE_CHARS;
				if (text.length() > limit) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TOO_LONG,
							"字段 " + key + " 过长：" + text.length() + " 字符（上限 " + limit + "）"));
					break;
				}
				if (required && text.isBlank()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_REQUIRED_MISSING,
							"必填字段 " + key + " 不得为空串"));
					break;
				}
				Integer minLength = integerOrNull(field.get("minLength"));
				Integer maxLength = integerOrNull(field.get("maxLength"));
				if (minLength != null && text.length() < minLength) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TOO_SHORT,
							"字段 " + key + " 过短：" + text.length() + " 字符（下限 " + minLength + "）"));
				}
				if (maxLength != null && text.length() > maxLength) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TOO_LONG,
							"字段 " + key + " 过长：" + text.length() + " 字符（上限 " + maxLength + "）"));
				}
				String format = textOrNull(field.get("format"));
				if (format != null && !text.isBlank() && !matchesFormat(format, text)) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_FORMAT_MISMATCH,
							"字段 " + key + " 不符合 " + format + " 格式：" + formatHint(format)));
				}
			}
			case "number" -> {
				if (!value.isNumber()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望 number（JSON 数字），实际是" + typeName(value) + "：" + value));
					break;
				}
				double number = value.asDouble();
				if (!Double.isFinite(number)) {
					// JSON 的 1e999 会解析成 Infinity：后续 BigDecimal 步长计算会抛 NumberFormatException（线上 = 500）
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望有限数字（NaN/Infinity 不接受），实际是 " + value));
					break;
				}
				BigDecimal exactMin = finiteDecimalOrNull(field.get("min"));
				BigDecimal exactMax = finiteDecimalOrNull(field.get("max"));
				BigDecimal exactValue = value.decimalValue();
				boolean boundsBroken = (field.has("min") && exactMin == null)
						|| (field.has("max") && exactMax == null);
				if (boundsBroken) {
					issues.add(FormIssue.field(key, FormIssueCodes.FIELD_NUMBER_BOUND_INVALID,
							"字段 " + key + " 的 schema min/max 不是有限数字，无法复核取值范围（请重新生成表单）"));
				}
				else if ((exactMin != null && exactValue.compareTo(exactMin) < 0)
						|| (exactMax != null && exactValue.compareTo(exactMax) > 0)) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_OUT_OF_RANGE,
							"字段 " + key + " 超出范围：" + exactValue + "（允许 " + (exactMin == null ? "-∞" : exactMin)
									+ ".." + (exactMax == null ? "+∞" : exactMax) + "）"));
				}
				if (field.path("integer").asBoolean(false) && !isExactInteger(value)) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_NOT_INTEGER,
							"字段 " + key + " 必须是整数（实际 " + exactValue + "）"));
				}
				Double step = finiteOrNull(field.get("step"));
				if (field.has("step") && step == null) {
					// 非有限 step 只可能来自旧版闸门放行的待决表单：给字段错误，不做步长算术（会抛异常）
					issues.add(FormIssue.field(key, FormIssueCodes.FIELD_STEP_INVALID,
							"字段 " + key + " 的 schema step 不是有限正数（" + field.get("step")
									+ "），无法按步长复核（请重新生成表单）"));
				}
				else if (step != null && step > 0) {
					double base = exactMin == null ? 0 : exactMin.doubleValue();
					BigDecimal units = BigDecimal.valueOf(number - base)
						.divide(BigDecimal.valueOf(step), 8, java.math.RoundingMode.HALF_UP);
					if (units.subtract(new BigDecimal(units.toBigInteger())).abs().doubleValue() > 1e-6) {
						issues.add(FormIssue.field(key, FormIssueCodes.VALUE_STEP_MISMATCH,
								"字段 " + key + " 必须按步长 " + step + " 取值（实际 " + number + "）"));
					}
				}
			}
			case "boolean" -> {
				if (!value.isBoolean()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望 boolean，实际是" + typeName(value) + "：" + value));
				}
			}
			case "date" -> {
				if (!value.isTextual() || !isIsoDate(value.asText())) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望合法日期（yyyy-MM-dd），实际是 " + value));
					break;
				}
				String date = value.asText();
				String min = textOrNull(field.get("min"));
				String max = textOrNull(field.get("max"));
				if ((min != null && isIsoDate(min) && date.compareTo(min) < 0)
						|| (max != null && isIsoDate(max) && date.compareTo(max) > 0)) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_OUT_OF_RANGE,
							"字段 " + key + " 超出日期范围：" + date + "（允许 " + (min == null ? "不限" : min)
									+ " .. " + (max == null ? "不限" : max) + "）"));
				}
			}
			case "enum" -> {
				if (!value.isTextual()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望字符串（单选值），实际是" + typeName(value)));
					break;
				}
				if (options != null && !options.contains(value.asText())) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_OUT_OF_OPTIONS,
							"字段 " + key + " 的值 \"" + value.asText() + "\" 不在选项内（" + optionsHint(options) + "）"));
				}
			}
			case "multi_select" -> {
				if (!value.isArray()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望数组（多选），实际是" + typeName(value)));
					break;
				}
				if (required && value.isEmpty()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_REQUIRED_MISSING,
							"必填字段 " + key + " 至少选 1 项"));
				}
				Integer minItems = integerOrNull(field.get("minItems"));
				Integer maxItems = integerOrNull(field.get("maxItems"));
				if (minItems != null && value.size() < minItems) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TOO_FEW_ITEMS,
							"字段 " + key + " 至少选 " + minItems + " 项（实际 " + value.size() + "）"));
				}
				if (maxItems != null && value.size() > maxItems) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TOO_MANY_ITEMS,
							"字段 " + key + " 最多选 " + maxItems + " 项（实际 " + value.size() + "）"));
				}
				Set<String> seen = new LinkedHashSet<>();
				for (JsonNode element : value) {
					if (!element.isTextual() || (options != null && !options.contains(element.asText()))) {
						issues.add(FormIssue.field(key, FormIssueCodes.VALUE_OUT_OF_OPTIONS,
								"字段 " + key + " 含不在选项内的值：" + element + "（" + optionsHint(options) + "）"));
						continue;
					}
					if (!seen.add(element.asText())) {
						issues.add(FormIssue.field(key, FormIssueCodes.VALUE_DUPLICATE,
								"字段 " + key + " 含重复值：" + element.asText()));
					}
				}
			}
			case "attachment" -> {
				if (!value.isTextual()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
							"字段 " + key + " 期望附件 fileId（字符串），实际是" + typeName(value)
									+ "（文件内容与 URL 都不会被接受）"));
					break;
				}
				if (!ATTACHMENT_ID_PATTERN.matcher(value.asText()).matches()) {
					issues.add(FormIssue.field(key, FormIssueCodes.VALUE_ATTACHMENT_ID_INVALID,
							"字段 " + key + " 的值不是有效的附件 fileId（应形如 att_xxxxxxxx；"
									+ "URL/路径/文件内容一律拒绝）"));
				}
			}
			default -> issues.add(FormIssue.field(key, FormIssueCodes.VALUE_TYPE_MISMATCH,
					"字段 " + key + " 的类型 " + type + " 不在白名单内，无法复核"));
		}
		return issues;
	}

	// ── 条件求值（前后端同一语义：纯数据比较，不执行表达式） ──────────────────

	/** 显隐联动：没有 visibleWhen 即可见；有条件则按回灌值求值。 */
	private static boolean isVisible(JsonNode field, JsonNode values) {
		JsonNode condition = field.get("visibleWhen");
		return condition == null || evaluate(condition, values);
	}

	/** 必填联动：静态 required 或 requiredWhen 成立。 */
	private static boolean isRequired(JsonNode field, JsonNode values) {
		if (field.path("required").asBoolean(false)) {
			return true;
		}
		JsonNode condition = field.get("requiredWhen");
		return condition != null && evaluate(condition, values);
	}

	private static boolean evaluate(JsonNode condition, JsonNode values) {
		if (condition == null || !condition.isObject()) {
			return false;
		}
		String ref = textOrNull(condition.get("key"));
		String op = textOrNull(condition.get("op"));
		JsonNode actual = ref == null ? null : values.get(ref);
		if (op == null) {
			return false;
		}
		return switch (op) {
			case "empty" -> isEmptyValue(actual);
			case "notEmpty" -> !isEmptyValue(actual);
			case "eq" -> equalValue(actual, condition.get("value"));
			case "in" -> containsValue(condition.get("values"), actual);
			case "notIn" -> !containsValue(condition.get("values"), actual);
			case "between" -> between(actual, condition.get("values"));
			default -> false;
		};
	}

	private static boolean isEmptyValue(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return true;
		}
		if (value.isTextual()) {
			return value.asText().isBlank();
		}
		if (value.isArray()) {
			return value.isEmpty();
		}
		return false;
	}

	private static boolean equalValue(JsonNode actual, JsonNode expected) {
		if (actual == null || actual.isNull() || expected == null || expected.isNull()) {
			return false;
		}
		if (actual.isBoolean() && expected.isBoolean()) {
			return actual.asBoolean() == expected.asBoolean();
		}
		if (actual.isNumber() && expected.isNumber()) {
			// C N10：精确比较（整数按 BigInteger、其余按 BigDecimal#compareTo），不过 double、不加 epsilon
			return compareNumbers(actual, expected) == 0;
		}
		return actual.asText().equals(expected.asText());
	}

	private static boolean containsValue(JsonNode list, JsonNode actual) {
		if (list == null || !list.isArray()) {
			return false;
		}
		for (JsonNode element : list) {
			if (equalValue(actual, element)) {
				return true;
			}
		}
		return false;
	}

	private static boolean between(JsonNode actual, JsonNode bounds) {
		if (actual == null || actual.isNull() || bounds == null || !bounds.isArray() || bounds.size() != 2) {
			return false;
		}
		JsonNode low = bounds.get(0);
		JsonNode high = bounds.get(1);
		if (actual.isNumber() && low.isNumber() && high.isNumber()) {
			// C N10：区间也按精确数值比较（>2^53 的边界不再因 double 丢位而误判）
			return compareNumbers(actual, low) >= 0 && compareNumbers(actual, high) <= 0;
		}
		String value = actual.asText();
		return value.compareTo(low.asText()) >= 0 && value.compareTo(high.asText()) <= 0;
	}

	// ── 小工具 ───────────────────────────────────────────────────────────────

	/**
	 * 表单用途：显式值优先；<b>只有缺省时</b>才按结构推断 ——
	 * <ol>
	 * <li>字段 {@code optionsFrom.sourceId=definitions}（层级 → 定义的配置选择结构）⇒
	 * {@code SELECT_DEFINITIONS}；</li>
	 * <li>否则 {@code scenario=FILTER} ⇒ {@code SET_CONDITIONS}；</li>
	 * <li>其它形状（含标题/label）一律 {@code GENERAL_CLARIFY}。</li>
	 * </ol>
	 * 显式 {@code GENERAL_CLARIFY} 直接返回，不被形状覆盖（旧表单的绕行口在缺省时堵住，
	 * 显式澄清不受影响）。非法值由 schema 校验拒绝，不进入本方法。
	 *
	 * @param form 表单 schema 节点（可为 null）
	 */
	public static String purposeOf(JsonNode form) {
		if (form == null || !form.isObject()) {
			return DEFAULT_PURPOSE;
		}
		JsonNode purpose = form.get("purpose");
		if (purpose != null && purpose.isTextual() && !purpose.asText().isBlank()) {
			return purpose.asText();
		}
		if (hasDefinitionsSelection(form)) {
			return PURPOSE_SELECT_DEFINITIONS;
		}
		JsonNode scenario = form.get("scenario");
		if (scenario != null && scenario.isTextual() && "FILTER".equals(scenario.asText())) {
			return PURPOSE_SET_CONDITIONS;
		}
		return DEFAULT_PURPOSE;
	}

	/** 表单里是否有"配置定义选择"的明确结构：{@code optionsFrom.sourceId=definitions}。 */
	private static boolean hasDefinitionsSelection(JsonNode form) {
		for (JsonNode field : form.path("fields")) {
			if ("definitions".equals(field.path("optionsFrom").path("sourceId").asText())) {
				return true;
			}
		}
		return false;
	}

	/** 结构化清单 → 人读清单（{@code schemaReasons/resultReasons/argumentReasons} 的唯一出口）。 */
	public static List<String> messages(List<FormIssue> issues) {
		List<String> out = new ArrayList<>(issues.size());
		for (FormIssue issue : issues) {
			out.add(issue.message());
		}
		return out;
	}

	/** schema 的 {@code fields[]} → key → 字段节点（顺序保持；schema 不可用时返回 null）。 */
	private static Map<String, JsonNode> fieldsByKey(JsonNode form) {
		if (form == null || !form.isObject() || !form.path("fields").isArray()) {
			return null;
		}
		Map<String, JsonNode> out = new LinkedHashMap<>();
		for (JsonNode field : form.path("fields")) {
			String key = textOrNull(field.get("key"));
			if (key != null) {
				out.put(key, field);
			}
		}
		return out;
	}

	/**
	 * schema 期的可取值全集：静态 {@code options} 优先；否则把 {@code optionsFrom.map} 各分支的选项
	 * <b>并集</b>（用于默认值/映射值校验；运行期的父值分支判定见 {@link #runtimeOptionValues}）。
	 */
	private static List<String> optionValues(JsonNode field) {
		List<String> values = new ArrayList<>();
		JsonNode options = field.get("options");
		if (options != null) {
			appendOptionValues(options, values);
			return values;
		}
		JsonNode map = field.path("optionsFrom").get("map");
		if (map != null && map.isObject()) {
			for (Map.Entry<String, JsonNode> entry : iterable(map)) {
				appendOptionValues(entry.getValue(), values);
			}
		}
		return values;
	}

	/**
	 * 运行期的可取值集合：静态 {@code options}；或 {@code optionsFrom.map} 中<b>当前父值对应</b>的分支。
	 *
	 * <p>父值缺失/无对应分支时返回<b>空集</b> —— 此时提交任何值都应判 {@code VALUE_OUT_OF_OPTIONS}
	 * （级联语义：父值没选，子选项就不存在）。只有 {@code sourceId} 级联返回 {@code null}
	 * （数据源在后端，规则层无法枚举），调用方据此跳过成员校验 —— 空集与"无法枚举"必须区分开，
	 * 否则空集会被当成"不用校验"，级联值就能任意通过。
	 */
	private static List<String> runtimeOptionValues(JsonNode field, JsonNode values) {
		JsonNode options = field.get("options");
		if (options != null) {
			List<String> out = new ArrayList<>();
			appendOptionValues(options, out);
			return out;
		}
		JsonNode optionsFrom = field.get("optionsFrom");
		if (optionsFrom == null || !optionsFrom.isObject() || !optionsFrom.path("map").isObject()) {
			return null;
		}
		JsonNode map = optionsFrom.get("map");
		List<String> out = new ArrayList<>();
		String parent = textOrNull(optionsFrom.get("dependsOn"));
		JsonNode parentValue = parent == null || values == null ? null : values.get(parent);
		if (parentValue == null || parentValue.isNull()) {
			return out;
		}
		JsonNode branch = map.get(parentValue.asText());
		if (branch != null) {
			appendOptionValues(branch, out);
		}
		return out;
	}

	/** 选项集合的人读说明（空集 = 级联父值未选/无分支，子选项不存在）。 */
	private static String optionsHint(List<String> options) {
		return options.isEmpty() ? "当前没有可选项：级联父值未选，或该父值没有对应分支"
				: "可选：" + String.join("/", options);
	}

	private static void appendOptionValues(JsonNode options, List<String> out) {
		if (options == null || !options.isArray()) {
			return;
		}
		for (JsonNode option : options) {
			String value = option != null && option.isObject() ? textOrNull(option.get("value")) : null;
			if (value != null && !value.isBlank() && !out.contains(value)) {
				out.add(value);
			}
		}
	}

	private static boolean matchesFormat(String format, String text) {
		return switch (format) {
			case "email" -> EMAIL_PATTERN.matcher(text).matches();
			case "tel" -> TEL_PATTERN.matcher(text).matches();
			case "url" -> URL_PATTERN.matcher(text).matches();
			case "idcard" -> IDCARD_PATTERN.matcher(text).matches();
			default -> true;
		};
	}

	private static String formatHint(String format) {
		return switch (format) {
			case "email" -> "形如 name@example.com";
			case "tel" -> "形如 13800000000 或 +86 138-0000-0000";
			case "url" -> "形如 https://example.com/path";
			case "idcard" -> "15 或 18 位身份证号";
			default -> "请按格式填写";
		};
	}

	/** 严格 ISO 日期（{@code yyyy-MM-dd}）判定：格式与日历合法性一并校验。 */
	public static boolean isIsoDate(String text) {
		if (text == null) {
			return false;
		}
		try {
			LocalDate.parse(text, DATE_FORMAT);
			return true;
		}
		catch (DateTimeParseException ex) {
			return false;
		}
	}

	private static List<FormIssue> dedupe(List<FormIssue> issues) {
		Map<String, FormIssue> unique = new LinkedHashMap<>();
		Deque<FormIssue> queue = new ArrayDeque<>(issues);
		while (!queue.isEmpty()) {
			FormIssue issue = queue.poll();
			unique.putIfAbsent(issue.key() + "\u0000" + issue.code() + "\u0000" + issue.message(), issue);
		}
		return new ArrayList<>(unique.values());
	}

	private static String textOrNull(JsonNode node) {
		return node != null && node.isTextual() ? node.asText() : null;
	}

	private static Integer integerOrNull(JsonNode node) {
		return node != null && node.isIntegralNumber() ? node.asInt() : null;
	}

	/**
	 * JSON 数字 → <b>有限</b> double；非数字与 NaN/±Infinity（如 {@code 1e999}）一律 null。
	 *
	 * <p>非有限数不能进数值运算：{@code BigDecimal.valueOf(∞)} 抛 {@code NumberFormatException}，
	 * 而回灌期的调用点（确认门）没有 catch，一次超大 step/min/max 就是一次 500。
	 */
	private static Double finiteOrNull(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		double value = node.asDouble();
		return Double.isFinite(value) ? value : null;
	}

	private static List<String> names(JsonNode node) {
		List<String> out = new ArrayList<>();
		node.fieldNames().forEachRemaining(out::add);
		return out;
	}

	private static List<Map.Entry<String, JsonNode>> iterable(JsonNode node) {
		List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
		node.fields().forEachRemaining(out::add);
		return out;
	}

	private static List<String> sorted(Set<String> values) {
		List<String> out = new ArrayList<>(values);
		out.sort(String::compareTo);
		return out;
	}

	/** JSON 节点的中文类型名（错误信息用）。 */
	private static String typeName(JsonNode node) {
		if (node == null || node.isNull()) {
			return "null";
		}
		if (node.isTextual()) {
			return "文本";
		}
		if (node.isNumber()) {
			return "数字";
		}
		if (node.isBoolean()) {
			return "布尔";
		}
		if (node.isArray()) {
			return "数组";
		}
		if (node.isObject()) {
			return "对象";
		}
		return node.getNodeType().name();
	}

}
