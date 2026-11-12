package com.example.configmgr.ai.form;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 生成式表单 {@code purpose} 的<b>唯一注册定义与唯一解析入口</b>。
 *
 * <p>所有消费方共用显式用途和缺省值，不再按 scenario、字段或数据源形状猜测业务用途。
 * 缺省一律是 {@code GENERAL_CLARIFY}；选择和条件表单必须显式声明 purpose。
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

	public static final String DEFAULT = GENERAL_CLARIFY;

	/** 用途闭集（顺序即模型看到的描述顺序）。 */
	public static final List<String> ALL = List.of(SELECT_DEFINITIONS, SET_CONDITIONS, TASK_TYPE, GENERAL_CLARIFY);

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
	 * 解析后的用途（显式优先，缺省一律为 {@link #DEFAULT}）。
	 *
	 * <p>非法显式值原样返回（调用方由 schema 校验先拒绝），不在这里静默改成缺省值 ——
	 * 否则"写错用途"会变成"按澄清表单放行"。
	 */
	public static String resolve(JsonNode form) {
		String explicit = explicit(form);
		return explicit == null ? DEFAULT : explicit;
	}

	/** 该表单解析后的用途是否等于 {@code purpose}（宿主识别表单时用，例如"这是条件表吗"）。 */
	public static boolean is(JsonNode form, String purpose) {
		return purpose != null && purpose.equals(resolve(form));
	}
}
