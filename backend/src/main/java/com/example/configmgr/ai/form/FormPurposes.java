package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 生成式表单 {@code purpose} 的<b>唯一注册定义与唯一解析入口</b>。
 *
 * <h2>为什么要有它</h2>
 * {@code purpose} 此前有三个消费方各读一份原始 JSON：schema 校验（{@code GenerativeFormRules#schemaIssues}）、
 * 形状约束（{@code purposeShapeIssues}）、槽位放行（{@code GenerativeFormGuard#inspectSlots}），
 * 另有宿主表单识别（如 {@code ConditionsForm}）直接 {@code form.path("purpose").asText()}。
 * 三处对"缺省 purpose"的处理不一样（一处当非法、一处当 {@code GENERAL_CLARIFY}、一处按结构推断），
 * 于是同一张表单在不同闸门得到不同用途。本类把<b>闭集、缺省值、显式读取、结构推断</b>收成一处，
 * 所有消费方只调这里：
 * <ul>
 * <li>{@link #explicit(JsonNode)}：只认模型显式给出的合法值（形状约束用它 —— 旧表单不受新形状约束）；</li>
 * <li>{@link #resolve(JsonNode)}：显式优先，缺省按结构推断（槽位放行与宿主识别用它）。</li>
 * </ul>
 *
 * <h2>推断口径（只在缺省时生效）</h2>
 * <ol>
 * <li>字段 {@code optionsFrom.sourceId=definitions}（层级 → 定义的配置选择结构）⇒ {@code SELECT_DEFINITIONS}；</li>
 * <li>否则 {@code scenario=FILTER} ⇒ {@code SET_CONDITIONS}；</li>
 * <li>其它形状（含标题/label）一律 {@code GENERAL_CLARIFY} —— <b>绝不按标题猜</b>。</li>
 * </ol>
 */
public final class FormPurposes {

	/** 收集配置项选择。 */
	public static final String SELECT_DEFINITIONS = "SELECT_DEFINITIONS";

	/** 收集筛选条件。 */
	public static final String SET_CONDITIONS = "SET_CONDITIONS";

	/** 收集任务类型（{@code EXPORT/IMPORT} 单个 enum 单选）。 */
	public static final String TASK_TYPE = "TASK_TYPE";

	/** 通用澄清（也是缺省用途）。 */
	public static final String GENERAL_CLARIFY = "GENERAL_CLARIFY";

	/** 缺省用途：兼容既有 {@code generative_form} 调用（它们没有 purpose 字段）。 */
	public static final String DEFAULT = GENERAL_CLARIFY;

	/** 用途闭集（顺序即模型看到的描述顺序）。 */
	public static final List<String> ALL = List.of(SELECT_DEFINITIONS, SET_CONDITIONS, TASK_TYPE, GENERAL_CLARIFY);

	/** 内置选项数据源里那个"层级 → 配置定义"的 id（推断 {@code SELECT_DEFINITIONS} 的结构判据）。 */
	private static final String SOURCE_DEFINITIONS = "definitions";

	/** 条件表单的结构判据（{@code scenario=FILTER}）。 */
	private static final String SCENARIO_FILTER = "FILTER";

	private FormPurposes() {
	}

	/** 是否登记在册（大小写敏感：线上取值就是大写常量）。 */
	public static boolean isRegistered(String purpose) {
		return purpose != null && ALL.contains(purpose);
	}

	/**
	 * 模型<b>显式</b>给出的用途；缺省/空白/非文本返回 {@code null}。
	 *
	 * <p>不做推断：形状约束只对显式用途生效，调用方据此区分"模型说要这么填"与"结构看起来像"。
	 * 非法值不在这里判（由 schema 校验给 {@code FORM_PURPOSE_INVALID}）。
	 */
	public static String explicit(JsonNode form) {
		if (form == null || !form.isObject()) {
			return null;
		}
		JsonNode purpose = form.get("purpose");
		if (purpose == null || !purpose.isTextual() || purpose.asText().isBlank()) {
			return null;
		}
		return purpose.asText().trim();
	}

	/**
	 * 解析后的用途（显式优先，缺省按结构推断，推断不出 = {@link #DEFAULT}）。
	 *
	 * <p>非法显式值原样返回（调用方由 schema 校验先拒绝），不在这里静默改成缺省值 ——
	 * 否则"写错用途"会变成"按澄清表单放行"。
	 */
	public static String resolve(JsonNode form) {
		String explicit = explicit(form);
		if (explicit != null) {
			return explicit;
		}
		if (form == null || !form.isObject()) {
			return DEFAULT;
		}
		if (hasDefinitionsSelection(form)) {
			return SELECT_DEFINITIONS;
		}
		JsonNode scenario = form.get("scenario");
		if (scenario != null && scenario.isTextual() && SCENARIO_FILTER.equals(scenario.asText())) {
			return SET_CONDITIONS;
		}
		return DEFAULT;
	}

	/** 该表单解析后的用途是否等于 {@code purpose}（宿主识别表单时用，例如"这是条件表吗"）。 */
	public static boolean is(JsonNode form, String purpose) {
		return purpose != null && purpose.equals(resolve(form));
	}

	/** 表单里是否有"配置定义选择"的明确结构：{@code optionsFrom.sourceId=definitions}。 */
	private static boolean hasDefinitionsSelection(JsonNode form) {
		for (JsonNode field : form.path("fields")) {
			if (SOURCE_DEFINITIONS.equals(field.path("optionsFrom").path("sourceId").asText())) {
				return true;
			}
		}
		return false;
	}
}
