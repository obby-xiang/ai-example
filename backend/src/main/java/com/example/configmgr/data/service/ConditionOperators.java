package com.example.configmgr.data.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 查询条件<b>算子白名单的唯一注册定义</b>（后端所有消费方共用这一份）。
 *
 * <h2>为什么要有它</h2>
 * 同一份 12 枚算子此前抄在四处：{@link ConditionEvaluator#validate}（求值白名单）、
 * {@code ai/tool/WizardActionGuard}（前端工具入参白名单）、{@code ai/tool/SheetWriteGuard}
 * （表格写预览谓词）、{@code ai/gate/ToolPreflightService#operatorLabel}（人读摘要），
 * 另有 {@code AiTools} 的 {@code @ToolParam} 描述再抄一遍。副本漂移不报错，只会让"求值认的算子"
 * 与"工具放行的算子"分叉（模型传一个工具放行、求值拒绝的算子，得到"工具成功、导出 0 行"）。
 * 本类把<b>名称、是否有值、按语言的人读标签</b>收成一处，其余只引用。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>{@code CONTAINS} 与 {@code LIKE} 在求值器里等价（文本包含），但两者都是线上合法取值，故都登记；</li>
 * <li>{@code EMPTY}/{@code NOT_EMPTY} 不带值（带值说明模型在猜语义）；</li>
 * <li>范围键（{@code scopeKeys}）不是字段算子，单独用 {@link #SCOPE} 表示 —— 它只出现在摘要渲染里。</li>
 * </ul>
 *
 * <p><b>注解常量对账</b>：{@code @ToolParam(description=…)} 要求编译期常量，故保留
 * {@link #JOINED_NAMES} 这一份字面量镜像；类初始化时与枚举对账，漂移即抛（启动失败），
 * 避免"描述里写着 12 枚、白名单里只剩 11 枚"。
 */
public final class ConditionOperators {

	/** 范围键的伪算子（只用于人读摘要：{@code scopeKeys} 不是字段条件）。 */
	public static final String SCOPE = "SCOPE";

	/** 算子枚举：{@code wire} = 线上大写名，{@code takesValue} = 是否必须带值，{@code zh/en} = 人读标签。 */
	public enum Operator {
		EQ(true, "等于", "equals"),
		NE(true, "不等于", "not equals"),
		CONTAINS(true, "包含", "contains"),
		LIKE(true, "包含", "contains"),
		STARTS_WITH(true, "以…开头", "starts with"),
		IN(true, "包含于", "in"),
		EMPTY(false, "为空", "is empty"),
		NOT_EMPTY(false, "不为空", "is not empty"),
		GT(true, "大于", "greater than"),
		GTE(true, "大于等于", "greater than or equal"),
		LT(true, "小于", "less than"),
		LTE(true, "小于等于", "less than or equal");

		private final boolean takesValue;

		private final String zh;

		private final String en;

		Operator(boolean takesValue, String zh, String en) {
			this.takesValue = takesValue;
			this.zh = zh;
			this.en = en;
		}

		public boolean takesValue() {
			return this.takesValue;
		}

		public String label(boolean english) {
			return english ? this.en : this.zh;
		}
	}

	/** 算子闭集（顺序即描述/摘要里的呈现顺序）。 */
	public static final List<Operator> OPERATORS = List.of(Operator.values());

	/** 算子名闭集（顺序同上）。 */
	public static final List<String> NAMES = OPERATORS.stream().map(Operator::name).toList();

	private static final Set<String> NAME_SET = Set.copyOf(NAMES);

	/** 不带取值的算子（带值反而说明模型在猜语义）。 */
	public static final Set<String> VALUELESS = Set.of(Operator.EMPTY.name(), Operator.NOT_EMPTY.name());

	/**
	 * {@code @ToolParam} 描述用的编译期常量镜像（顺序必须与 {@link #NAMES} 一致）。
	 * 类初始化时对账，漂移即启动失败。
	 */
	public static final String JOINED_NAMES = "EQ/NE/CONTAINS/LIKE/STARTS_WITH/IN/EMPTY/NOT_EMPTY/GT/GTE/LT/LTE";

	static {
		String joined = String.join("/", NAMES);
		if (!JOINED_NAMES.equals(joined)) {
			throw new IllegalStateException("算子注册表与描述常量漂移：JOINED_NAMES=" + JOINED_NAMES + "，注册表=" + joined);
		}
	}

	private ConditionOperators() {
	}

	/** 是否登记在册（大小写不敏感；null/空白 = false）。 */
	public static boolean isKnown(String operator) {
		if (operator == null || operator.isBlank()) {
			return false;
		}
		return NAME_SET.contains(operator.trim().toUpperCase(Locale.ROOT));
	}

	/** 该算子是否必须带值（未知算子在调用方已拒绝，这里按"有值"处理不构成放宽面）。 */
	public static boolean takesValue(String operator) {
		return !VALUELESS.contains(normalize(operator));
	}

	/** 人读标签：已登记算子按语言给词，未登记的<b>原样返回</b>（不编造含义）。 */
	public static String label(String operator, boolean english) {
		String normalized = normalize(operator);
		for (Operator registered : OPERATORS) {
			if (registered.name().equals(normalized)) {
				return registered.label(english);
			}
		}
		return operator;
	}

	/** 归一（去空白 + 大写）；null 返回空串。 */
	public static String normalize(String operator) {
		return operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT);
	}
}
