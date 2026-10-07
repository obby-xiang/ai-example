package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 * <tr><td>{@code title}</td><td>否</td><td>文本 ≤ {@value #MAX_TITLE_CHARS} 字符</td></tr>
 * <tr><td>{@code fields}</td><td>是</td><td>1..{@value #MAX_FIELDS} 个字段</td></tr>
 * </table>
 *
 * <table>
 * <caption>字段级属性（{@code fields[]}）</caption>
 * <tr><th>属性</th><th>必填</th><th>约束</th></tr>
 * <tr><td>{@code key}</td><td>是</td><td>{@code ^[A-Za-z_][A-Za-z0-9_]*$}，≤ {@value #MAX_KEY_CHARS} 字符，表单内唯一</td></tr>
 * <tr><td>{@code label}</td><td>是</td><td>非空文本，≤ {@value #MAX_LABEL_CHARS} 字符</td></tr>
 * <tr><td>{@code type}</td><td>是</td><td>白名单六型：{@code text/number/boolean/date/enum/multi_select}</td></tr>
 * <tr><td>{@code required}</td><td>否</td><td>布尔（缺省 false）</td></tr>
 * <tr><td>{@code defaultValue}</td><td>否</td><td>类型必须与 {@code type} 相符；enum/multi_select 必须在选项内</td></tr>
 * <tr><td>{@code options}</td><td>enum/multi_select 必填</td><td>{@code [{"value":"…","label":"…"}]}，1..{@value #MAX_OPTIONS} 项，<b>只能是对象</b>（不接受裸字符串）</td></tr>
 * <tr><td>{@code placeholder}</td><td>否</td><td>仅 {@code text/number/date} 可带，≤ {@value #MAX_PLACEHOLDER_CHARS} 字符</td></tr>
 * </table>
 *
 * <h2>安全边界建立在"白名单"而不是"内容黑名单"上</h2>
 * 不在上表内的<b>类型与属性一律拒绝</b>（{@code type:"html"}、{@code attributes}、{@code onClick}、
 * {@code style} 之类根本进不来），因此不存在"模型自由拼 HTML/脚本"的表达能力 —— 这正是 DC-15
 * 要求的限定形态生成式 UI（对比 DC-14 N2 拒绝的完整 A2UI/OpenGenUI 渲染层：那是任意组件树，
 * 这里只有六种预定义控件）。文案内容（title/label/placeholder/选项文本）<b>不做黑名单式启发式过滤</b>：
 * 前端以文本插值渲染（非 {@code v-html}），字符串只是数据；滥用面由长度上限封顶。
 * 若将来前端引入富文本渲染，这条假设失效，必须回到此处补内容级校验。
 *
 * <h2>上限值</h2>
 * 单字段文本值 ≤ {@value #MAX_TEXT_VALUE_CHARS}；{@code form} 序列化 ≤ {@value #MAX_FORM_CHARS} 字符；
 * 回灌值 JSON ≤ {@value #MAX_RESULT_CHARS} 字符（两者都在模型上下文与前端渲染的成本上限之内）。
 */
public final class GenerativeFormRules {

	/** 工具名（与 {@code AiTools#generativeForm} 的 {@code @Tool(name)} 一致，由用例对账锁定）。 */
	public static final String TOOL_NAME = "generative_form";

	/** schema 级拒绝码（下发前端之前，工具错误结果）。 */
	public static final String SCHEMA_REJECTED = "FORM_SCHEMA_REJECTED";

	/** 回灌结果级拒绝码（HTTP 400，挂起不被消费）。 */
	public static final String RESULT_REJECTED = "FORM_RESULT_REJECTED";

	/** 字段类型白名单（DC-15 明文六型，不外扩）。 */
	public static final List<String> FIELD_TYPES = List.of("text", "number", "boolean", "date", "enum", "multi_select");

	/** 表单场景白名单（DC-15 的两个场景）。 */
	public static final Set<String> SCENARIOS = Set.of("FILTER", "CLARIFY");

	public static final Set<String> FORM_ATTRIBUTES = Set.of("scenario", "title", "fields");

	public static final Set<String> FIELD_ATTRIBUTES = Set.of("key", "label", "type", "required", "defaultValue",
			"options", "placeholder");

	public static final Set<String> OPTION_ATTRIBUTES = Set.of("value", "label");

	/** 必须带 {@code options} 的类型。 */
	public static final Set<String> OPTION_TYPES = Set.of("enum", "multi_select");

	/** 允许带 {@code placeholder} 的类型。 */
	public static final Set<String> PLACEHOLDER_TYPES = Set.of("text", "number", "date");

	public static final int MAX_FIELDS = 20;

	public static final int MAX_OPTIONS = 50;

	public static final int MAX_KEY_CHARS = 40;

	public static final int MAX_TITLE_CHARS = 100;

	public static final int MAX_LABEL_CHARS = 100;

	public static final int MAX_PLACEHOLDER_CHARS = 100;

	public static final int MAX_OPTION_TEXT_CHARS = 100;

	public static final int MAX_TEXT_VALUE_CHARS = 200;

	public static final int MAX_FORM_CHARS = 8000;

	public static final int MAX_RESULT_CHARS = 8000;

	/** 字段键：字母或下划线开头，仅字母/数字/下划线，1..{@value #MAX_KEY_CHARS} 字符。 */
	private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,39}$");

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
		List<String> reasons = new ArrayList<>();
		if (arguments == null || !arguments.isObject()) {
			reasons.add("工具参数必须是 JSON 对象（形如 {\"form\":{…}}）");
			return reasons;
		}
		for (String name : names(arguments)) {
			if (!"form".equals(name)) {
				reasons.add("工具参数只接受 form 一个键，出现了多余的键：" + name);
			}
		}
		if (!arguments.has("form")) {
			reasons.add("缺少 form 参数（表单 schema 的载体）");
			return reasons;
		}
		reasons.addAll(schemaReasons(arguments.get("form")));
		return reasons;
	}

	/** 表单 schema 本身的白名单校验。 */
	public static List<String> schemaReasons(JsonNode form) {
		List<String> reasons = new ArrayList<>();
		if (form == null || !form.isObject()) {
			reasons.add("form 必须是 JSON 对象（{\"scenario\":…,\"fields\":[…]})");
			return reasons;
		}
		for (String name : names(form)) {
			if (!FORM_ATTRIBUTES.contains(name)) {
				reasons.add("form 不允许的属性：" + name + "（表单属性白名单：" + String.join("/", sorted(FORM_ATTRIBUTES)) + "）");
			}
		}
		int size = form.toString().length();
		if (size > MAX_FORM_CHARS) {
			reasons.add("表单 schema 过大：" + size + " 字符（上限 " + MAX_FORM_CHARS + "）");
		}

		JsonNode scenario = form.get("scenario");
		if (scenario == null || !scenario.isTextual() || !SCENARIOS.contains(scenario.asText())) {
			reasons.add("scenario 必填且必须是 " + String.join("/", sorted(SCENARIOS)) + " 之一（实际："
					+ (scenario == null ? "缺失" : scenario.asText()) + "）");
		}
		JsonNode title = form.get("title");
		if (title != null) {
			if (!title.isTextual()) {
				reasons.add("title 必须是文本");
			}
			else if (title.asText().length() > MAX_TITLE_CHARS) {
				reasons.add("title 过长：" + title.asText().length() + " 字符（上限 " + MAX_TITLE_CHARS + "）");
			}
		}

		JsonNode fields = form.get("fields");
		if (fields == null) {
			reasons.add("fields 必填（至少 1 个字段）");
			return reasons;
		}
		if (!fields.isArray()) {
			reasons.add("fields 必须是数组");
			return reasons;
		}
		if (fields.isEmpty()) {
			reasons.add("fields 至少 1 个字段");
			return reasons;
		}
		if (fields.size() > MAX_FIELDS) {
			reasons.add("fields 字段过多：" + fields.size() + " 个（上限 " + MAX_FIELDS + "）");
		}
		Set<String> keys = new LinkedHashSet<>();
		for (int i = 0; i < fields.size(); i++) {
			reasons.addAll(fieldReasons(fields.get(i), i, keys));
		}
		return reasons;
	}

	/**
	 * 回灌结果（字段 key → 值）的类型复核：返回被拒原因清单，空列表 = 通过。
	 *
	 * @param form   挂起时下发的表单 schema（取自待决条目的入参原文）
	 * @param values 前端回灌的值对象
	 */
	public static List<String> resultReasons(JsonNode form, JsonNode values) {
		List<String> reasons = new ArrayList<>();
		if (values == null || !values.isObject()) {
			reasons.add("回灌结果必须是 JSON 对象（字段 key → 值）");
			return reasons;
		}
		Map<String, JsonNode> fields = fieldsByKey(form);
		if (fields == null) {
			reasons.add("表单 schema 不可用（缺少 fields 数组），无法复核回灌结果 —— schema 在挂起前已通过校验，"
					+ "出现此情形说明入参在挂起后被改动");
			return reasons;
		}
		int size = values.toString().length();
		if (size > MAX_RESULT_CHARS) {
			reasons.add("回灌结果过大：" + size + " 字符（上限 " + MAX_RESULT_CHARS + "）");
		}
		for (String name : names(values)) {
			if (!fields.containsKey(name)) {
				reasons.add("未知字段：" + name + "（表单字段：" + String.join(", ", fields.keySet()) + "）");
			}
		}
		for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
			String key = entry.getKey();
			JsonNode field = entry.getValue();
			boolean required = field.path("required").asBoolean(false);
			JsonNode value = values.get(key);
			if (value == null || value.isNull()) {
				if (required) {
					reasons.add("缺少必填字段：" + key + "（" + field.path("label").asText(key) + "）");
				}
				continue;
			}
			reasons.addAll(valueReasons(key, field, value, required));
		}
		return reasons;
	}

	// ── 字段级校验 ────────────────────────────────────────────────────────────

	private static List<String> fieldReasons(JsonNode field, int index, Set<String> keys) {
		List<String> reasons = new ArrayList<>();
		String at = "fields[" + index + "]";
		if (field == null || !field.isObject()) {
			reasons.add(at + " 必须是 JSON 对象");
			return reasons;
		}
		for (String name : names(field)) {
			if (!FIELD_ATTRIBUTES.contains(name)) {
				reasons.add(at + " 不允许的属性：" + name + "（字段属性白名单："
						+ String.join("/", sorted(FIELD_ATTRIBUTES)) + "）");
			}
		}

		String key = textOrNull(field.get("key"));
		if (key == null || key.isBlank()) {
			reasons.add(at + ".key 必填且不得为空");
		}
		else if (!KEY_PATTERN.matcher(key).matches()) {
			reasons.add(at + ".key \"" + key + "\" 不合法（字母或下划线开头，仅字母/数字/下划线，长度 ≤" + MAX_KEY_CHARS + "）");
		}
		else if (!keys.add(key)) {
			reasons.add(at + ".key \"" + key + "\" 与前面的字段重复（同一表单内字段键必须唯一）");
		}

		String label = textOrNull(field.get("label"));
		if (label == null || label.isBlank()) {
			reasons.add(at + ".label 必填且不得为空");
		}
		else if (label.length() > MAX_LABEL_CHARS) {
			reasons.add(at + ".label 过长：" + label.length() + " 字符（上限 " + MAX_LABEL_CHARS + "）");
		}

		String type = textOrNull(field.get("type"));
		if (type == null) {
			reasons.add(at + ".type 必填");
			return reasons;
		}
		if (!FIELD_TYPES.contains(type)) {
			reasons.add(at + ".type=" + type + " 不在白名单类型内（" + String.join("/", FIELD_TYPES) + "）");
			return reasons;
		}

		JsonNode requiredNode = field.get("required");
		if (requiredNode != null && !requiredNode.isBoolean()) {
			reasons.add(at + ".required 必须是布尔（true/false）");
		}

		JsonNode placeholder = field.get("placeholder");
		if (placeholder != null) {
			if (!placeholder.isTextual()) {
				reasons.add(at + ".placeholder 必须是文本");
			}
			else if (placeholder.asText().length() > MAX_PLACEHOLDER_CHARS) {
				reasons.add(at + ".placeholder 过长：" + placeholder.asText().length() + " 字符（上限 "
						+ MAX_PLACEHOLDER_CHARS + "）");
			}
			else if (!PLACEHOLDER_TYPES.contains(type)) {
				reasons.add(at + ".placeholder 仅 " + String.join("/", sorted(PLACEHOLDER_TYPES))
						+ " 类型可带（当前 type=" + type + "）");
			}
		}

		JsonNode options = field.get("options");
		if (OPTION_TYPES.contains(type)) {
			if (options == null) {
				reasons.add(at + ".options 必填（type=" + type + " 必须在选项内取值）");
			}
			else {
				reasons.addAll(optionReasons(options, at));
			}
		}
		else if (options != null) {
			reasons.add(at + ".options 仅 enum/multi_select 可带（当前 type=" + type + "）");
		}

		JsonNode defaultValue = field.get("defaultValue");
		if (defaultValue != null) {
			reasons.addAll(defaultValueReasons(defaultValue, at, type, optionValues(options)));
		}
		return reasons;
	}

	private static List<String> optionReasons(JsonNode options, String at) {
		List<String> reasons = new ArrayList<>();
		if (!options.isArray()) {
			reasons.add(at + ".options 必须是数组");
			return reasons;
		}
		if (options.isEmpty()) {
			reasons.add(at + ".options 至少 1 个选项");
		}
		if (options.size() > MAX_OPTIONS) {
			reasons.add(at + ".options 选项过多：" + options.size() + " 个（上限 " + MAX_OPTIONS + "）");
		}
		Set<String> seen = new LinkedHashSet<>();
		for (int i = 0; i < options.size(); i++) {
			JsonNode option = options.get(i);
			String where = at + ".options[" + i + "]";
			if (option == null || !option.isObject()) {
				reasons.add(where + " 必须是 {\"value\":\"…\",\"label\":\"…\"} 对象（不接受裸字符串）");
				continue;
			}
			for (String name : names(option)) {
				if (!OPTION_ATTRIBUTES.contains(name)) {
					reasons.add(where + " 不允许的属性：" + name + "（选项属性白名单：value/label）");
				}
			}
			String value = textOrNull(option.get("value"));
			if (value == null || value.isBlank()) {
				reasons.add(where + ".value 必填且不得为空");
			}
			else if (value.length() > MAX_OPTION_TEXT_CHARS) {
				reasons.add(where + ".value 过长：" + value.length() + " 字符（上限 " + MAX_OPTION_TEXT_CHARS + "）");
			}
			else if (!seen.add(value)) {
				reasons.add(where + ".value \"" + value + "\" 与前一个选项重复");
			}
			String label = textOrNull(option.get("label"));
			if (label != null && label.length() > MAX_OPTION_TEXT_CHARS) {
				reasons.add(where + ".label 过长：" + label.length() + " 字符（上限 " + MAX_OPTION_TEXT_CHARS + "）");
			}
		}
		return reasons;
	}

	private static List<String> defaultValueReasons(JsonNode value, String at, String type, List<String> options) {
		List<String> reasons = new ArrayList<>();
		switch (type) {
			case "text" -> {
				if (!value.isTextual()) {
					reasons.add(at + ".defaultValue 期望 text（字符串），实际是" + typeName(value));
				}
				else if (value.asText().length() > MAX_TEXT_VALUE_CHARS) {
					reasons.add(at + ".defaultValue 过长：" + value.asText().length() + " 字符（上限 "
							+ MAX_TEXT_VALUE_CHARS + "）");
				}
			}
			case "number" -> {
				if (!value.isNumber()) {
					reasons.add(at + ".defaultValue 期望 number（JSON 数字），实际是" + typeName(value));
				}
			}
			case "boolean" -> {
				if (!value.isBoolean()) {
					reasons.add(at + ".defaultValue 期望 boolean，实际是" + typeName(value));
				}
			}
			case "date" -> {
				if (!value.isTextual() || !isIsoDate(value.asText())) {
					reasons.add(at + ".defaultValue 期望合法日期（yyyy-MM-dd），实际是 " + value);
				}
			}
			case "enum" -> {
				if (!value.isTextual()) {
					reasons.add(at + ".defaultValue 期望字符串（单选值），实际是" + typeName(value));
				}
				else if (!options.contains(value.asText())) {
					reasons.add(at + ".defaultValue \"" + value.asText() + "\" 不在选项内（" + String.join("/", options) + "）");
				}
			}
			case "multi_select" -> {
				if (!value.isArray()) {
					reasons.add(at + ".defaultValue 期望数组（多选默认值），实际是" + typeName(value));
				}
				else {
					Set<String> seen = new LinkedHashSet<>();
					for (JsonNode element : value) {
						if (!element.isTextual() || !options.contains(element.asText())) {
							reasons.add(at + ".defaultValue 含不在选项内的值：" + element + "（可选："
									+ String.join("/", options) + "）");
							continue;
						}
						if (!seen.add(element.asText())) {
							reasons.add(at + ".defaultValue 含重复值：" + element.asText());
						}
					}
				}
			}
			default -> reasons.add(at + ".defaultValue 无法校验（type=" + type + " 不在白名单内）");
		}
		return reasons;
	}

	// ── 回灌值的类型复核 ─────────────────────────────────────────────────────

	private static List<String> valueReasons(String key, JsonNode field, JsonNode value, boolean required) {
		List<String> reasons = new ArrayList<>();
		String type = field.path("type").asText("");
		List<String> options = optionValues(field.get("options"));
		switch (type) {
			case "text" -> {
				if (!value.isTextual()) {
					reasons.add("字段 " + key + " 期望 text（字符串），实际是" + typeName(value));
				}
				else if (value.asText().length() > MAX_TEXT_VALUE_CHARS) {
					reasons.add("字段 " + key + " 过长：" + value.asText().length() + " 字符（上限 "
							+ MAX_TEXT_VALUE_CHARS + "）");
				}
				else if (required && value.asText().isBlank()) {
					reasons.add("必填字段 " + key + " 不得为空串");
				}
			}
			case "number" -> {
				if (!value.isNumber()) {
					reasons.add("字段 " + key + " 期望 number（JSON 数字），实际是" + typeName(value) + "：" + value);
				}
			}
			case "boolean" -> {
				if (!value.isBoolean()) {
					reasons.add("字段 " + key + " 期望 boolean，实际是" + typeName(value) + "：" + value);
				}
			}
			case "date" -> {
				if (!value.isTextual() || !isIsoDate(value.asText())) {
					reasons.add("字段 " + key + " 期望合法日期（yyyy-MM-dd），实际是 " + value);
				}
			}
			case "enum" -> {
				if (!value.isTextual()) {
					reasons.add("字段 " + key + " 期望字符串（单选值），实际是" + typeName(value));
				}
				else if (!options.contains(value.asText())) {
					reasons.add("字段 " + key + " 的值 \"" + value.asText() + "\" 不在选项内（"
							+ String.join("/", options) + "）");
				}
			}
			case "multi_select" -> {
				if (!value.isArray()) {
					reasons.add("字段 " + key + " 期望数组（多选），实际是" + typeName(value));
				}
				else {
					if (required && value.isEmpty()) {
						reasons.add("必填字段 " + key + " 至少选 1 项");
					}
					Set<String> seen = new LinkedHashSet<>();
					for (JsonNode element : value) {
						if (!element.isTextual() || !options.contains(element.asText())) {
							reasons.add("字段 " + key + " 含不在选项内的值：" + element + "（可选："
									+ String.join("/", options) + "）");
							continue;
						}
						if (!seen.add(element.asText())) {
							reasons.add("字段 " + key + " 含重复值：" + element.asText());
						}
					}
				}
			}
			default -> reasons.add("字段 " + key + " 的类型 " + type + " 不在白名单内，无法复核");
		}
		return reasons;
	}

	// ── 小工具 ───────────────────────────────────────────────────────────────

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

	/** 选项值集合（顺序保持；非法形态直接跳过 —— 它们已由 schema 校验报过原因）。 */
	private static List<String> optionValues(JsonNode options) {
		List<String> values = new ArrayList<>();
		if (options == null || !options.isArray()) {
			return values;
		}
		for (JsonNode option : options) {
			String value = option != null && option.isObject() ? textOrNull(option.get("value")) : null;
			if (value != null && !value.isBlank() && !values.contains(value)) {
				values.add(value);
			}
		}
		return values;
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

	private static String textOrNull(JsonNode node) {
		return node != null && node.isTextual() ? node.asText() : null;
	}

	private static List<String> names(JsonNode node) {
		List<String> out = new ArrayList<>();
		node.fieldNames().forEachRemaining(out::add);
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
