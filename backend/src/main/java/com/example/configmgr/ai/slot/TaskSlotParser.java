package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单条用户文本 → 槽位意图（纯函数、无 Spring）。判定口径见类内各正则与
 * {@code docs/research/修复-AI多步工作流与动态披露-2026-10-28.md} §8：
 * <ul>
 * <li><b>只在明确的导出/选择意图下</b>把配置定义名/编码当成选择（"查询货币字典"不是选择）；</li>
 * <li><b>唯一匹配才填</b>：编码或唯一中文名各算一项，<b>多项明确配置是合法多选</b>；
 * 只有同一中文名对应多个编码（且没有编码消歧）、否定、取消才不猜；</li>
 * <li><b>条件必须保守</b>：明确"字段为/=值"，或唯一配置项 + 唯一主键 + 唯一合法大写值；
 * 本轮已明确选定唯一配置时裸值不要求筛选词（"导出CNY货币字典"），多候选仍不造条件。</li>
 * </ul>
 */
final class TaskSlotParser {

	/** 选择/导出意图（没有它，出现的配置定义名只是被查询/讨论，不是选择）。 */
	private static final Pattern SELECT_INTENT = Pattern.compile("(导出|导入|选择|勾选|选定|选用|改选|改成|换成|换为)");

	/** 否定选择（"不要导出 CURRENCY"/"不选它"）：明确撤回，不填槽位、也不沿用历史。 */
	private static final Pattern NEGATED_SELECT = Pattern
		.compile("(?:不要|不用|不需要|无需|别|请勿|不)\\s*(?:再)?\\s*(?:导出|导入|选择|勾选|选定|选用)");

	/** 取消/作废：同时清空选择与条件（"取消后处理"的有定义行为）。 */
	private static final Pattern CLEAR_ALL = Pattern
		.compile("(取消|算了|不用了|不需要了|清空|重置|重新选|换个配置|换一个配置)");

	/** 只清条件（"不加条件"/"全量导出"）：选择保留。 */
	private static final Pattern CLEAR_CONDITION = Pattern
		.compile("(不加条件|不要条件|没有条件|不筛选|不需要筛选|不过滤|不设置条件|清除条件|全部导出|全量导出|不加筛选|无需筛选|无需条件)");

	/** 筛选/过滤意图（没有它不得凭一个裸大写词造条件）。 */
	private static final Pattern FILTER_INTENT = Pattern
		.compile("(筛选|过滤|条件|只要|仅要|仅导出|只导出|限定|where)", Pattern.CASE_INSENSITIVE);

	/** 字段 → 值：`code = CNY`、`code为CNY`、`货币代码 等于 CNY`。 */
	private static final String FIELD_VALUE_CONNECTOR = "\\s*(?:=|==|等于|为|是)\\s*";

	/** 等值字面量（口令类字符不参与，遇到空白/标点即止；引号可有可无）。 */
	private static final String FIELD_VALUE_LITERAL = "[\"'“”]?([^\\s，。；,;、\"'“”]{1,64})[\"'“”]?";

	/** 大写候选值（CURRENCY+CNY 这类唯一主键缩写）。 */
	private static final Pattern UPPER_TOKEN = Pattern
		.compile("(?<![A-Za-z0-9_])[A-Z][A-Z0-9_]{1,63}(?![A-Za-z0-9_])");

	/** 裸大写词里明显不是筛选值的保留词（避免把步骤/层级/算子当值）。 */
	private static final Set<String> UPPER_STOP_WORDS = Set.of("EQ", "NE", "IN", "AND", "OR", "NOT", "WHERE",
			"GLOBAL", "REGION", "PROJECT", "EXPORT", "IMPORT", "SELECT_DEFS", "QUERY_COND", "UPLOAD", "PRECHECK",
			"PUBLISH", "REPLACE", "ADD", "MERGE", "CSV", "XLSX", "ZIP");

	private TaskSlotParser() {
	}

	/** 单条文本对槽位的四种作用（{@code ABSENT} = 这条文本没提，沿用前值）。 */
	enum SlotState {
		ABSENT, DETERMINED, AMBIGUOUS, CLEARED
	}

	/**
	 * 解析结果。{@code defCodes}/{@code conditions} 只在 {@code DETERMINED} 时非空；
	 * {@code taskType} 与选择/条件独立（"导出" 本身就给了任务类型）。
	 */
	record Intent(SlotState selection, List<String> defCodes, SlotState condition,
			List<ConditionSlot> conditions, String taskType) {

		static Intent absent() {
			return new Intent(SlotState.ABSENT, List.of(), SlotState.ABSENT, List.of(), null);
		}
	}

	/**
	 * @param text        单条用户文本
	 * @param definitions 已知配置定义目录
	 * @param fallbackDefCodes 本条文本没提选择时，条件解析可依附的已知配置项（权威/历史）
	 */
	static Intent parse(String text, List<SlotDefinition> definitions, List<String> fallbackDefCodes) {
		if (text == null || text.isBlank()) {
			return Intent.absent();
		}
		SelectionMatch match = matchSelection(text, definitions);
		boolean selectIntent = SELECT_INTENT.matcher(text).find();
		boolean negated = NEGATED_SELECT.matcher(text).find();
		boolean clearAll = CLEAR_ALL.matcher(text).find();

		SlotState selection;
		List<String> defCodes = List.of();
		if (selectIntent && !negated) {
			if (match.ambiguous()) {
				// 同一中文名对应多个定义编码（且文本里没有编码消歧）：不猜
				selection = SlotState.AMBIGUOUS;
			}
			else if (!match.definitions().isEmpty()) {
				selection = SlotState.DETERMINED;
				defCodes = match.definitions().stream().map(SlotDefinition::code).toList();
			}
			else {
				selection = SlotState.ABSENT;
			}
		}
		else if (negated || clearAll) {
			selection = SlotState.CLEARED;
		}
		else {
			selection = SlotState.ABSENT;
		}

		List<String> effectiveDefCodes = switch (selection) {
			case DETERMINED -> defCodes;
			case CLEARED, AMBIGUOUS -> List.of();
			case ABSENT -> fallbackDefCodes == null ? List.of() : fallbackDefCodes;
		};

		SlotState condition;
		List<ConditionSlot> conditions = List.of();
		if (clearAll || CLEAR_CONDITION.matcher(text).find()) {
			condition = SlotState.CLEARED;
		}
		else if (effectiveDefCodes.size() == 1) {
			SlotDefinition def = definitionOf(definitions, effectiveDefCodes.get(0));
			List<ConditionSlot> explicit = def == null ? null : explicitConditions(text, def);
			if (explicit != null && !explicit.isEmpty()) {
				condition = SlotState.DETERMINED;
				conditions = explicit;
			}
			else if (def != null && (selection == SlotState.DETERMINED
					|| FILTER_INTENT.matcher(text).find())) {
				// 裸值缩写：本轮已明确选定唯一配置时不再要求"筛选/只要"等词
				// （"导出CNY货币字典"）；仍要求唯一主键 + 唯一合法大写候选。
				ShorthandResult shorthand = shorthandCondition(text, def);
				condition = shorthand.state();
				conditions = shorthand.slot() == null ? List.of() : List.of(shorthand.slot());
			}
			else {
				condition = SlotState.ABSENT;
			}
		}
		else if (effectiveDefCodes.size() > 1 && FILTER_INTENT.matcher(text).find()) {
			// 条件属于哪个配置项不唯一 —— 不造条件
			condition = SlotState.AMBIGUOUS;
		}
		else {
			condition = SlotState.ABSENT;
		}

		return new Intent(selection, defCodes, condition, conditions, taskType(text, negated));
	}

	// ── 配置定义识别 ─────────────────────────────────────────────────────────

	/**
	 * 文本里的配置定义选择：<b>多项明确配置是合法多选</b>（编码/唯一名称各算一项），
	 * 只有"同一中文名映射到多个编码且文本里没有编码消歧"才算歧义。
	 */
	private static SelectionMatch matchSelection(String text, List<SlotDefinition> definitions) {
		Set<String> codeMatched = new LinkedHashSet<>();
		Map<String, List<SlotDefinition>> byName = new LinkedHashMap<>();
		for (SlotDefinition definition : definitions) {
			if (definition == null || definition.code() == null) {
				continue;
			}
			if (containsCode(text, definition.code())) {
				codeMatched.add(definition.code());
			}
			if (containsName(text, definition.name())) {
				byName.computeIfAbsent(definition.name().trim(), key -> new ArrayList<>()).add(definition);
			}
		}
		for (List<SlotDefinition> group : byName.values()) {
			if (group.size() > 1
					&& group.stream().noneMatch(definition -> codeMatched.contains(definition.code()))) {
				return new SelectionMatch(true, List.of());
			}
		}
		List<SlotDefinition> selected = new ArrayList<>();
		for (SlotDefinition definition : definitions) {
			if (definition == null || definition.code() == null) {
				continue;
			}
			List<SlotDefinition> group = definition.name() == null ? null : byName.get(definition.name().trim());
			boolean byUniqueName = group != null && group.size() == 1
					&& group.get(0).code().equals(definition.code());
			if (codeMatched.contains(definition.code()) || byUniqueName) {
				selected.add(definition);
			}
		}
		return new SelectionMatch(false, selected);
	}

	private record SelectionMatch(boolean ambiguous, List<SlotDefinition> definitions) {
	}

	private static boolean containsCode(String text, String code) {
		if (code.isBlank()) {
			return false;
		}
		Pattern pattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(code) + "(?![A-Za-z0-9_])");
		return pattern.matcher(text).find();
	}

	private static boolean containsName(String text, String name) {
		// 单字名（如"值"）在任何句子里都会命中，不参与识别 —— 宁可缺失，不猜
		return name != null && name.trim().length() >= 2 && text.contains(name.trim());
	}

	private static SlotDefinition definitionOf(List<SlotDefinition> definitions, String code) {
		for (SlotDefinition definition : definitions) {
			if (definition != null && code.equals(definition.code())) {
				return definition;
			}
		}
		return null;
	}

	// ── 条件识别 ─────────────────────────────────────────────────────────────

	/**
	 * 明确"字段为/=值"：同一字段两个不同值 ⇒ 歧义（返回 null，不造条件）。
	 * 只在该配置定义的字段里找（不跨定义关联）。
	 */
	private static List<ConditionSlot> explicitConditions(String text, SlotDefinition definition) {
		Map<String, String> byField = new LinkedHashMap<>();
		for (SlotDefinition.SlotField field : definition.fields()) {
			if (field == null || field.code() == null) {
				continue;
			}
			List<String> values = explicitValues(text, field);
			if (values.isEmpty()) {
				continue;
			}
			if (values.size() > 1) {
				// 同一字段出现多个不同值（"code = CNY 或 code = USD"）：歧义，不造条件
				return null;
			}
			byField.put(field.code(), values.get(0));
		}
		if (byField.isEmpty()) {
			return List.of();
		}
		List<ConditionSlot> out = new ArrayList<>();
		for (Map.Entry<String, String> entry : byField.entrySet()) {
			out.add(new ConditionSlot(definition.code(), entry.getKey(), entry.getValue()));
		}
		return out;
	}

	/** 该字段在文本里被明确赋过的所有不同值（按出现顺序）。 */
	private static List<String> explicitValues(String text, SlotDefinition.SlotField field) {
		List<String> values = new ArrayList<>();
		for (String token : tokens(field)) {
			Pattern pattern = Pattern.compile(token + FIELD_VALUE_CONNECTOR + FIELD_VALUE_LITERAL,
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			Matcher matcher = pattern.matcher(text);
			while (matcher.find()) {
				String value = matcher.group(1);
				if (value != null && !value.isBlank() && !values.contains(value)) {
					values.add(value);
				}
			}
		}
		return values;
	}

	/** 字段的匹配记号：编码带词边界，中文标签按原文（>1 字才用）。 */
	private static List<String> tokens(SlotDefinition.SlotField field) {
		List<String> tokens = new ArrayList<>();
		if (field.code() != null && !field.code().isBlank()) {
			tokens.add("(?<![A-Za-z0-9_])" + Pattern.quote(field.code()) + "(?![A-Za-z0-9_])");
		}
		if (field.label() != null && field.label().trim().length() >= 2) {
			tokens.add(Pattern.quote(field.label().trim()));
		}
		return tokens;
	}

	/**
	 * 唯一主键 + 唯一大写值的确定场景（如"导出 CURRENCY，筛选 CNY"、"导出CNY货币字典"）。
	 * 以下任一不成立即不造条件：主键不唯一、没有候选、值不在可选枚举内；
	 * <b>多候选</b>是歧义（{@link SlotState#AMBIGUOUS}），无候选则是"没提"（沿用前值）。
	 */
	private static ShorthandResult shorthandCondition(String text, SlotDefinition definition) {
		SlotDefinition.SlotField key = definition.keyField().orElse(null);
		if (key == null) {
			return new ShorthandResult(SlotState.ABSENT, null);
		}
		Set<String> excluded = new LinkedHashSet<>();
		excluded.add(definition.code().toLowerCase(Locale.ROOT));
		for (SlotDefinition.SlotField field : definition.fields()) {
			if (field.code() != null) {
				excluded.add(field.code().toLowerCase(Locale.ROOT));
			}
		}
		List<String> candidates = new ArrayList<>();
		Matcher matcher = UPPER_TOKEN.matcher(text);
		while (matcher.find()) {
			String token = matcher.group();
			String lower = token.toLowerCase(Locale.ROOT);
			if (excluded.contains(lower) || UPPER_STOP_WORDS.contains(token)) {
				continue;
			}
			if (!candidates.contains(token)) {
				candidates.add(token);
			}
		}
		if (candidates.isEmpty()) {
			return new ShorthandResult(SlotState.ABSENT, null);
		}
		if (candidates.size() > 1) {
			return new ShorthandResult(SlotState.AMBIGUOUS, null);
		}
		String value = candidates.get(0);
		if (!key.optionValues().isEmpty() && !key.optionValues().contains(value)) {
			// 有枚举就按枚举校验：不在可选值里不是"筛选"，是另一个词
			return new ShorthandResult(SlotState.ABSENT, null);
		}
		return new ShorthandResult(SlotState.DETERMINED, new ConditionSlot(definition.code(), key.code(), value));
	}

	private record ShorthandResult(SlotState state, ConditionSlot slot) {
	}

	// ── 任务类型 ─────────────────────────────────────────────────────────────

	private static String taskType(String text, boolean negated) {
		if (negated) {
			return null;
		}
		boolean export = text.contains("导出");
		boolean imports = text.contains("导入");
		if (export == imports) {
			return null;
		}
		return export ? "EXPORT" : "IMPORT";
	}
}
