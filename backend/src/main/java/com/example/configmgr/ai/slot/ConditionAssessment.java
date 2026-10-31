package com.example.configmgr.ai.slot;

import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>筛选条件的统一判定</b>（导出条件与一轮闭环批，Task 1 第 1 项）。
 *
 * <p>此前三处消费方各判一套：{@code ConditionsForm} 只看 EQ 结构，{@code TaskSlotResolver} 只把
 * EQ 当"权威条件"（IN/scopeKeys 被当成没设），{@code ContextBuilder} 在条件为空时一律渲染
 * "未设筛选 = 全量"，{@code ToolPreflightService} 解析失败也回落"全部数据（无条件）"。
 * 后果是同一份任务条目在不同位置得到不同结论：已知条件仍弹表、范围/IN 条件被当成全量、
 * 坏 JSON 被写成"全量"。
 *
 * <h2>三态（唯一口径）</h2>
 * <ul>
 * <li>{@link State#SET}：任一配置项条目里有合法 {@code fields[]}（{@code fieldCode}+{@code operator}
 * 均非空，算子不限于 EQ）或非空 {@code scopeKeys}；</li>
 * <li>{@link State#EXPLICIT_UNSET}：<b>恰好空对象 {@code {}}</b>（没有任何键）——
 * 这是前端在用户清空条件后落库的形态，语义是"明确不设 = 全量"；</li>
 * <li>{@link State#UNDECIDED}：空串/空白（从未决定）、坏 JSON、非对象、<b>空数组形态</b>
 * （{@code fields:[]} / {@code scopeKeys:[]} 有键但没有条件）、未知键/残缺字段 —— <b>不得</b>
 * 当成全量。</li>
 * </ul>
 *
 * <p>判据只认结构，不认具体字段名/值/大小写，因此不绑定任何业务配置项（不是写死 CNY/CURRENCY）。
 */
public final class ConditionAssessment {

	private static final ObjectMapper JSON = new ObjectMapper();

	private ConditionAssessment() {
	}

	public enum State {
		SET, EXPLICIT_UNSET, UNDECIDED
	}

	/**
	 * 一条已设置条件的事实（渲染给模型/preflight 用）。
	 *
	 * @param defCode  配置定义编码
	 * @param fieldCode 字段编码；范围键为 {@code null}
	 * @param operator 算子；范围键为 {@code SCOPE}
	 * @param value    值的可读形态（数组以"、"连接；EMPTY/NOT_EMPTY 为空串）
	 */
	public record ConditionFact(String defCode, String fieldCode, String operator, String value) {

		/** 人读摘要（不编造：值为空时不追加空格）。 */
		public String render() {
			if ("SCOPE".equals(this.operator)) {
				return "范围 = " + this.value;
			}
			return this.fieldCode + " " + this.operator + (this.value == null || this.value.isBlank() ? "" : " " + this.value);
		}
	}

	/** 单个配置项的判定结果。 */
	public record ItemCondition(String defCode, State state, boolean unparseable, List<ConditionFact> facts) {

		public ItemCondition {
			facts = facts == null ? List.of() : List.copyOf(facts);
		}
	}

	/** 一次判定的全部结果（按配置项；同一 defCode 合并为一条）。 */
	public record Assessment(List<ItemCondition> items) {

		public Assessment {
			items = items == null ? List.of() : List.copyOf(items);
		}

		/** 该配置项的状态；没有条目 = 未决定（没有条件事实，不等于全量）。 */
		public State stateOf(String defCode) {
			ItemCondition item = itemOf(defCode);
			return item == null ? State.UNDECIDED : item.state();
		}

		public ItemCondition itemOf(String defCode) {
			if (defCode == null) {
				return null;
			}
			for (ItemCondition item : this.items) {
				if (defCode.equals(item.defCode())) {
					return item;
				}
			}
			return null;
		}

		/** 已设置的配置项编码（任何算子/范围键都算）。 */
		public Set<String> setDefCodes() {
			Set<String> out = new LinkedHashSet<>();
			for (ItemCondition item : this.items) {
				if (item.state() == State.SET) {
					out.add(item.defCode());
				}
			}
			return out;
		}

		/** 未决定/无法解析的配置项编码。 */
		public List<String> undecidedDefCodes() {
			List<String> out = new ArrayList<>();
			for (ItemCondition item : this.items) {
				if (item.state() == State.UNDECIDED) {
					out.add(item.defCode());
				}
			}
			return out;
		}

		/** 是否存在无法解析的条件 JSON（"坏 JSON"与"没设置"必须可区分）。 */
		public boolean hasUnparseable() {
			for (ItemCondition item : this.items) {
				if (item.unparseable()) {
					return true;
				}
			}
			return false;
		}

		/** 全部已设置条件的事实（按条目顺序）。 */
		public List<ConditionFact> facts() {
			List<ConditionFact> out = new ArrayList<>();
			for (ItemCondition item : this.items) {
				out.addAll(item.facts());
			}
			return out;
		}

		/** 该配置项是否已有条件（任何算子/范围键）。 */
		public boolean isSet(String defCode) {
			return stateOf(defCode) == State.SET;
		}
	}

	/** 任务条目判定（{@code null} 任务/条目 = 没有任何条件事实）。 */
	public static Assessment of(Task task) {
		return ofItems(task == null ? null : task.getItems());
	}

	/**
	 * 条目集合判定：同一 {@code defCode} 的多条条目合并（SET 优先于 EXPLICIT_UNSET 优先于 UNDECIDED），
	 * 因为"至少有一条真实条件"就已经是已设置。
	 */
	public static Assessment ofItems(Collection<TaskItem> items) {
		if (items == null || items.isEmpty()) {
			return new Assessment(List.of());
		}
		Map<String, ItemCondition> merged = new LinkedHashMap<>();
		for (TaskItem item : items) {
			if (item == null) {
				continue;
			}
			String defCode = plainText(item.getDefCode());
			if (defCode == null) {
				continue;
			}
			ItemCondition assessed = assess(defCode, item.getConditionJson());
			ItemCondition previous = merged.get(defCode);
			if (previous == null || rank(assessed.state()) > rank(previous.state())) {
				merged.put(defCode, assessed);
			}
			else if (previous.state() == assessed.state() && !assessed.facts().isEmpty()) {
				List<ConditionFact> union = new ArrayList<>(previous.facts());
				for (ConditionFact fact : assessed.facts()) {
					if (!union.contains(fact)) {
						union.add(fact);
					}
				}
				merged.put(defCode, new ItemCondition(defCode, previous.state(), previous.unparseable(), union));
			}
		}
		return new Assessment(new ArrayList<>(merged.values()));
	}

	/**
	 * 单条 {@code conditionJson} 的三态判定。
	 *
	 * <p><b>只有恰好空对象 {@code {}}</b> 是 {@link State#EXPLICIT_UNSET}（用户明确不设 = 全量）：
	 * 空数组形态（{@code {"fields":[]}} / {@code {"scopeKeys":[]}}）是"有结构但没有条件"的非法写入，
	 * 按 {@code UNDECIDED + unparseable} 处理（既不能当全量，也不能当已设置）。
	 */
	public static ItemCondition assess(String defCode, String conditionJson) {
		if (conditionJson == null || conditionJson.isBlank()) {
			return new ItemCondition(defCode, State.UNDECIDED, false, List.of());
		}
		JsonNode node;
		try {
			node = JSON.readTree(conditionJson);
		}
		catch (Exception ex) {
			return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
		}
		if (node == null || !node.isObject()) {
			return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
		}
		// 只有 fields / scopeKeys 两个键是条件契约；未知键说明结构来路不明（未来版本/坏载荷），
		// 不得当成"空条件 = 全量"（Task 1 第 1 项）。
		var names = node.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			if (!"fields".equals(name) && !"scopeKeys".equals(name)) {
				return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
			}
		}
		JsonNode fields = node.get("fields");
		if (fields != null && !fields.isArray()) {
			return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
		}
		JsonNode scopeKeys = node.get("scopeKeys");
		if (scopeKeys != null && !scopeKeys.isArray()) {
			return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
		}
		List<ConditionFact> facts = new ArrayList<>();
		for (String key : scopeKeys(scopeKeys)) {
			if (key == null) {
				return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
			}
			facts.add(new ConditionFact(defCode, null, "SCOPE", key));
		}
		List<ConditionFact> fieldFacts = fieldFacts(defCode, fields);
		if (fieldFacts == null) {
			return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
		}
		facts.addAll(fieldFacts);
		if (!facts.isEmpty()) {
			return new ItemCondition(defCode, State.SET, false, facts);
		}
		if (node.isEmpty()) {
			return new ItemCondition(defCode, State.EXPLICIT_UNSET, false, List.of());
		}
		// 有键但没有任何可用条件（fields:[] / scopeKeys:[]）：非法写入形态，不得当"明确不设"。
		return new ItemCondition(defCode, State.UNDECIDED, true, List.of());
	}

	/**
	 * 字段事实；任一字段残缺（非对象 / 缺 fieldCode / 缺 operator）返回 {@code null} ——
	 * 残缺结构不是"没有条件"，调用方按未决定处理。
	 */
	private static List<ConditionFact> fieldFacts(String defCode, JsonNode fields) {
		if (fields == null) {
			return List.of();
		}
		List<ConditionFact> out = new ArrayList<>();
		for (JsonNode field : fields) {
			if (field == null || !field.isObject()) {
				return null;
			}
			String fieldCode = text(field.get("fieldCode"));
			String operator = text(field.get("operator"));
			if (fieldCode == null || operator == null) {
				return null;
			}
			out.add(new ConditionFact(defCode, fieldCode, operator.toUpperCase(java.util.Locale.ROOT),
					renderValue(field.get("value"))));
		}
		return out;
	}

	/**
	 * 范围键；非数组已在上层拒绝。数组里的空/非文本项说明载荷残缺，返回含 {@code null} 的列表，
	 * 由调用方按未决定处理（不得把坏项过滤掉后当"空数组 = 明确不设"）。
	 */
	private static List<String> scopeKeys(JsonNode scopeKeys) {
		if (scopeKeys == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonNode key : scopeKeys) {
			if (key == null || !key.isTextual() || key.asText().isBlank()) {
				out.add(null);
			}
			else {
				out.add(key.asText().trim());
			}
		}
		return out;
	}

	private static String renderValue(JsonNode value) {
		if (value == null || value.isNull()) {
			return "";
		}
		if (value.isArray()) {
			List<String> parts = new ArrayList<>();
			value.forEach(element -> parts.add(element == null || element.isNull() ? "" : element.asText()));
			return String.join("、", parts);
		}
		return value.asText();
	}

	private static String text(JsonNode node) {
		if (node == null || !node.isTextual()) {
			return null;
		}
		return plainText(node.asText());
	}

	private static String plainText(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.trim();
		return value.isEmpty() ? null : value;
	}

	private static int rank(State state) {
		return switch (state) {
			case SET -> 2;
			case EXPLICIT_UNSET -> 1;
			case UNDECIDED -> 0;
		};
	}

}
