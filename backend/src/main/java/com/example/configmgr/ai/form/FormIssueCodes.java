package com.example.configmgr.ai.form;

/**
 * 生成式表单结构化错误的<b>机器可读码</b>白名单（前端按码分支：点亮字段 / 决定是否可重试）。
 *
 * <p>命名口径：{@code 层级_对象_问题}，层级三档 —— {@code ARG_}（工具入参）、
 * {@code FORM_/FIELD_/OPTION_}（schema 本身）、{@code VALUE_}（回灌值）；
 * {@code LINKAGE_*} 是联动规则、{@code OPTIONS_SOURCE_*} 是选项数据源、{@code ATTACHMENT_*} 是附件。
 *
 * <p><b>不改名</b>：码是前后端的稳定契约，语义变化只能新增码（旧码保留并在 Javadoc 标注退役语义）。
 */
public final class FormIssueCodes {

	private FormIssueCodes() {
	}

	// ── 工具入参（G-A） ────────────────────────────────────────────────────

	public static final String ARG_NOT_OBJECT = "ARG_NOT_OBJECT";
	public static final String ARG_EXTRA_KEY = "ARG_EXTRA_KEY";
	public static final String ARG_MISSING_FORM = "ARG_MISSING_FORM";

	// ── 表单级 ──────────────────────────────────────────────────────────────

	public static final String FORM_NOT_OBJECT = "FORM_NOT_OBJECT";
	public static final String FORM_EXTRA_ATTRIBUTE = "FORM_EXTRA_ATTRIBUTE";
	public static final String FORM_TOO_LARGE = "FORM_TOO_LARGE";
	public static final String FORM_SCENARIO_INVALID = "FORM_SCENARIO_INVALID";
	public static final String FORM_PURPOSE_INVALID = "FORM_PURPOSE_INVALID";
	public static final String FORM_SLOT_ALREADY_KNOWN = "FORM_SLOT_ALREADY_KNOWN";
	public static final String FORM_TITLE_INVALID = "FORM_TITLE_INVALID";
	public static final String FORM_TITLE_TOO_LONG = "FORM_TITLE_TOO_LONG";
	public static final String FORM_FIELDS_MISSING = "FORM_FIELDS_MISSING";
	public static final String FORM_FIELDS_NOT_ARRAY = "FORM_FIELDS_NOT_ARRAY";
	public static final String FORM_FIELDS_EMPTY = "FORM_FIELDS_EMPTY";
	public static final String FORM_FIELDS_TOO_MANY = "FORM_FIELDS_TOO_MANY";

	// ── 字段级 ──────────────────────────────────────────────────────────────

	public static final String FIELD_NOT_OBJECT = "FIELD_NOT_OBJECT";
	public static final String FIELD_EXTRA_ATTRIBUTE = "FIELD_EXTRA_ATTRIBUTE";
	public static final String FIELD_KEY_MISSING = "FIELD_KEY_MISSING";
	public static final String FIELD_KEY_FORBIDDEN = "FIELD_KEY_FORBIDDEN";
	public static final String FIELD_KEY_INVALID = "FIELD_KEY_INVALID";
	public static final String FIELD_KEY_DUPLICATE = "FIELD_KEY_DUPLICATE";
	public static final String FIELD_LABEL_MISSING = "FIELD_LABEL_MISSING";
	public static final String FIELD_LABEL_TOO_LONG = "FIELD_LABEL_TOO_LONG";
	public static final String FIELD_TYPE_MISSING = "FIELD_TYPE_MISSING";
	public static final String FIELD_TYPE_UNSUPPORTED = "FIELD_TYPE_UNSUPPORTED";
	public static final String FIELD_REQUIRED_INVALID = "FIELD_REQUIRED_INVALID";
	public static final String FIELD_PLACEHOLDER_INVALID = "FIELD_PLACEHOLDER_INVALID";
	public static final String FIELD_PLACEHOLDER_TOO_LONG = "FIELD_PLACEHOLDER_TOO_LONG";
	public static final String FIELD_PLACEHOLDER_TYPE_UNSUPPORTED = "FIELD_PLACEHOLDER_TYPE_UNSUPPORTED";

	// 校验最小集（G-C）
	public static final String FIELD_VARIANT_UNSUPPORTED = "FIELD_VARIANT_UNSUPPORTED";
	public static final String FIELD_LENGTH_BOUND_INVALID = "FIELD_LENGTH_BOUND_INVALID";
	public static final String FIELD_MAX_LENGTH_TOO_LARGE = "FIELD_MAX_LENGTH_TOO_LARGE";
	public static final String FIELD_MIN_GREATER_THAN_MAX = "FIELD_MIN_GREATER_THAN_MAX";
	public static final String FIELD_NUMBER_BOUND_INVALID = "FIELD_NUMBER_BOUND_INVALID";
	public static final String FIELD_STEP_INVALID = "FIELD_STEP_INVALID";
	public static final String FIELD_DATE_BOUND_INVALID = "FIELD_DATE_BOUND_INVALID";
	public static final String FIELD_ITEMS_BOUND_INVALID = "FIELD_ITEMS_BOUND_INVALID";
	public static final String FIELD_FORMAT_UNSUPPORTED = "FIELD_FORMAT_UNSUPPORTED";
	public static final String FIELD_ATTRIBUTE_TYPE_UNSUPPORTED = "FIELD_ATTRIBUTE_TYPE_UNSUPPORTED";
	public static final String FIELD_ATTRIBUTE_INVALID = "FIELD_ATTRIBUTE_INVALID";

	// ── 选项级 ──────────────────────────────────────────────────────────────

	public static final String OPTIONS_MISSING = "OPTIONS_MISSING";
	public static final String OPTIONS_NOT_ALLOWED = "OPTIONS_NOT_ALLOWED";
	public static final String OPTIONS_NOT_ARRAY = "OPTIONS_NOT_ARRAY";
	public static final String OPTIONS_EMPTY = "OPTIONS_EMPTY";
	public static final String OPTIONS_TOO_MANY = "OPTIONS_TOO_MANY";
	public static final String OPTION_NOT_OBJECT = "OPTION_NOT_OBJECT";
	public static final String OPTION_EXTRA_ATTRIBUTE = "OPTION_EXTRA_ATTRIBUTE";
	public static final String OPTION_VALUE_MISSING = "OPTION_VALUE_MISSING";
	public static final String OPTION_VALUE_TOO_LONG = "OPTION_VALUE_TOO_LONG";
	public static final String OPTION_VALUE_DUPLICATE = "OPTION_VALUE_DUPLICATE";
	public static final String OPTION_LABEL_TOO_LONG = "OPTION_LABEL_TOO_LONG";

	// ── 默认值 ──────────────────────────────────────────────────────────────

	public static final String DEFAULT_TYPE_MISMATCH = "DEFAULT_TYPE_MISMATCH";
	public static final String DEFAULT_TOO_LONG = "DEFAULT_TOO_LONG";
	public static final String DEFAULT_OUT_OF_OPTIONS = "DEFAULT_OUT_OF_OPTIONS";
	public static final String DEFAULT_DUPLICATE = "DEFAULT_DUPLICATE";

	// ── 回灌值（G-A / G-C） ────────────────────────────────────────────────

	public static final String RESULT_NOT_OBJECT = "RESULT_NOT_OBJECT";
	public static final String RESULT_SCHEMA_UNAVAILABLE = "RESULT_SCHEMA_UNAVAILABLE";
	public static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";
	public static final String RESULT_UNKNOWN_FIELD = "RESULT_UNKNOWN_FIELD";
	public static final String VALUE_REQUIRED_MISSING = "VALUE_REQUIRED_MISSING";
	public static final String VALUE_TYPE_MISMATCH = "VALUE_TYPE_MISMATCH";
	public static final String VALUE_TOO_LONG = "VALUE_TOO_LONG";
	public static final String VALUE_TOO_SHORT = "VALUE_TOO_SHORT";
	public static final String VALUE_OUT_OF_OPTIONS = "VALUE_OUT_OF_OPTIONS";
	public static final String VALUE_DUPLICATE = "VALUE_DUPLICATE";
	public static final String VALUE_FORMAT_MISMATCH = "VALUE_FORMAT_MISMATCH";
	public static final String VALUE_OUT_OF_RANGE = "VALUE_OUT_OF_RANGE";
	public static final String VALUE_STEP_MISMATCH = "VALUE_STEP_MISMATCH";
	public static final String VALUE_NOT_INTEGER = "VALUE_NOT_INTEGER";
	public static final String VALUE_TOO_FEW_ITEMS = "VALUE_TOO_FEW_ITEMS";
	public static final String VALUE_TOO_MANY_ITEMS = "VALUE_TOO_MANY_ITEMS";
	public static final String VALUE_ATTACHMENT_ID_INVALID = "VALUE_ATTACHMENT_ID_INVALID";

	// ── 联动五原语（G-D） ──────────────────────────────────────────────────

	public static final String LINKAGE_CONDITION_INVALID = "LINKAGE_CONDITION_INVALID";
	public static final String LINKAGE_OPERATOR_UNSUPPORTED = "LINKAGE_OPERATOR_UNSUPPORTED";
	public static final String LINKAGE_UNKNOWN_FIELD = "LINKAGE_UNKNOWN_FIELD";
	public static final String LINKAGE_SELF_REFERENCE = "LINKAGE_SELF_REFERENCE";
	public static final String LINKAGE_CYCLE = "LINKAGE_CYCLE";
	public static final String LINKAGE_DEPTH_EXCEEDED = "LINKAGE_DEPTH_EXCEEDED";
	public static final String LINKAGE_MAP_INVALID = "LINKAGE_MAP_INVALID";
	public static final String OPTIONS_SOURCE_UNKNOWN = "OPTIONS_SOURCE_UNKNOWN";
	public static final String OPTIONS_SOURCE_CONFLICT = "OPTIONS_SOURCE_CONFLICT";

	// ── 附件（G-E，闸门层） ────────────────────────────────────────────────

	public static final String ATTACHMENT_NOT_FOUND = "ATTACHMENT_NOT_FOUND";
	public static final String ATTACHMENT_FORBIDDEN = "ATTACHMENT_FORBIDDEN";
	public static final String ATTACHMENT_CONSUMED = "ATTACHMENT_CONSUMED";

	/** 登记簿/校验面暂时不可用（Redis 故障）：可重试，不是用户填错。 */
	public static final String ATTACHMENT_UNAVAILABLE = "ATTACHMENT_UNAVAILABLE";

}
