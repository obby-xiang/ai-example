package com.example.configmgr.ai.form;

import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表单用途 × 任务槽位的<b>下发前放行</b>（冗余收集表单不下发、零副作用）。
 *
 * <p>口径：先跑原有 schema 白名单（非法仍是 {@code FORM_SCHEMA_REJECTED}），再看
 * {@code purpose} 与 {@code AiContext.extra[taskSlots]}：
 * <ul>
 * <li>{@code SELECT_DEFINITIONS} 且配置项已确定 ⇒ 拒绝，指向 {@code select_definitions}；</li>
 * <li>{@code SET_CONDITIONS} 且条件已覆盖全部配置项 ⇒ 拒绝，指向 confirm_step/QUERY_COND 链；</li>
 * <li>槽位缺失/歧义、{@code GENERAL_CLARIFY}、缺省 purpose ⇒ 正常放行（未知配置仍可用表单）。</li>
 * </ul>
 */
class GenerativeFormGuardSlotsTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

	@Test
	void selectDefinitionsFormIsRejectedWhenDefCodesKnown() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SELECT_DEFINITIONS", "defs"), context(List.of("CURRENCY"), List.of()));

		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(verdict.render())
			.contains("CURRENCY")
			.contains("select_definitions")
			.contains("未向前端下发表单");
	}

	@Test
	void setConditionsFormIsRejectedWhenConditionsKnown() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SET_CONDITIONS", "value"), context(List.of("CURRENCY"),
						List.of(new ConditionSlot("CURRENCY", "code", "CNY"))));

		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(verdict.render())
			.contains("CURRENCY.code = CNY")
			.contains("confirm_step")
			.contains("QUERY_COND")
			.contains("set_condition");
	}

	@Test
	void unknownSlotsLetTheFormThrough() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SELECT_DEFINITIONS", "defs"), AiContext.empty());

		assertThat(verdict.accepted()).as("未知配置仍可用选择表单收集").isTrue();
	}

	@Test
	void ambiguousSlotsLetTheFormThrough() {
		// 解析器对多候选只写 taskType、不写 defCodes：这就是"歧义 = 缺失"
		Map<String, Object> slots = new LinkedHashMap<>();
		slots.put("taskType", "EXPORT");
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SET_CONDITIONS", "value"), context(slots));

		assertThat(verdict.accepted()).as("歧义时表单仍是可用的澄清手段").isTrue();
	}

	@Test
	void generalClarifyIsNeverBlockedByKnownSlots() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("GENERAL_CLARIFY", "note"), context(List.of("CURRENCY"),
						List.of(new ConditionSlot("CURRENCY", "code", "CNY"))));

		assertThat(verdict.accepted()).as("其它 CLARIFY 不受槽位机制影响").isTrue();
	}

	@Test
	void missingPurposeDefaultsToGeneralClarifyAndIsAllowed() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form(null, "note"), context(List.of("CURRENCY"), List.of()));

		assertThat(verdict.accepted()).as("缺省 purpose 必须兼容既有调用").isTrue();
	}

	@Test
	void invalidSchemaIsRejectedBeforeSlotRules() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				"{\"form\":{\"purpose\":\"SELECT_DEFINITIONS\",\"fields\":[]}}",
				context(List.of("CURRENCY"), List.of()));

		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.code()).isEqualTo(GenerativeFormRules.SCHEMA_REJECTED);
	}

	@Test
	void conditionFormStillPassesWhenConditionsMissing() {
		FrontendToolGuard.Verdict condition = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SET_CONDITIONS", "value"), context(List.of("CURRENCY"), List.of()));
		assertThat(condition.accepted()).as("配置项已知但条件槽位仍缺：条件表单可用").isTrue();
	}

	/**
	 * Task 1 第 3 项：多配置<b>逐项核对</b>。任一配置项有条件不得整体放行 ——
	 * 另一个配置项仍未决定时，条件表单仍有真实缺口（可以收集）。
	 */
	@Test
	void partiallyConditionedMultiConfigStillAllowsTheForm() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY", "ORDER"), List.of(),
				List.of("CURRENCY"), List.of("ORDER"), List.of("code = CNY"));
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SET_CONDITIONS", "value"), context(slots.toMap()));

		assertThat(verdict.accepted())
			.as("CURRENCY 有条件 ≠ ORDER 也有条件：仍有缺口，不得按'任一有条件即整体放行'拒绝")
			.isTrue();
	}

	/** 逐项都已有答案（已设置或明确不设）时，条件表单才被拒绝。 */
	@Test
	void fullyDecidedMultiConfigRejectsTheFormEvenWithoutConditionsOnEveryItem() {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY", "ORDER"), List.of(),
				List.of("CURRENCY"), List.of(), List.of("code = CNY"));
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form("SET_CONDITIONS", "value"), context(slots.toMap()));

		assertThat(verdict.accepted()).isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
	}

	@Test
	void missingPurposeWithDefinitionsSourceIsStillReleasedAsSelection() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				definitionSelectionForm(null), context(List.of("CURRENCY"), List.of()));

		assertThat(verdict.accepted())
			.as("旧选择表单没写 purpose：definitions 数据源是明确的配置选择结构，不能绕闸")
			.isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
	}

	@Test
	void missingPurposeWithDefinitionsSourceStillAllowsUnknownDefinitions() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				definitionSelectionForm(null), AiContext.empty());

		assertThat(verdict.accepted()).as("未知配置时老表单照常可用").isTrue();
	}

	@Test
	void explicitGeneralClarifyIsNotOverriddenByShape() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				definitionSelectionForm("GENERAL_CLARIFY"), context(List.of("CURRENCY"), List.of()));

		assertThat(verdict.accepted())
			.as("显式 GENERAL_CLARIFY 不因形状被改判为选择表单")
			.isTrue();
	}

	@Test
	void filterScenarioWithoutPurposeInfersConditionCollection() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				filterForm(), context(List.of("CURRENCY"),
						List.of(new ConditionSlot("CURRENCY", "code", "CNY"))));

		assertThat(verdict.accepted()).as("缺省 purpose + FILTER 场景 = 条件表单").isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
	}

	@Test
	void titleAndLabelAloneDoNotInferPurpose() {
		String form = "{\"form\":{\"scenario\":\"CLARIFY\",\"title\":\"选择配置项\",\"fields\":["
				+ "{\"key\":\"defs\",\"label\":\"配置项\",\"type\":\"text\"}]}}";
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				form, context(List.of("CURRENCY"), List.of()));

		assertThat(verdict.accepted())
			.as("标题/label 不参与用途判断，仍按 GENERAL_CLARIFY 放行")
			.isTrue();
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private static AiContext context(List<String> defCodes, List<ConditionSlot> conditions) {
		TaskSlots slots = new TaskSlots(null, defCodes, conditions);
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, slots.toMap());
		return AiContext.of("tasks", null, null, null, extra);
	}

	private static AiContext context(Map<String, Object> slots) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, slots);
		return AiContext.of("tasks", null, null, null, extra);
	}

	private String form(String purpose, String fieldKey) {
		String purposeField = purpose == null ? "" : "\"purpose\":\"" + purpose + "\",";
		String json = "{\"form\":{" + purposeField + "\"scenario\":\"CLARIFY\",\"fields\":["
				+ "{\"key\":\"" + fieldKey + "\",\"label\":\"值\",\"type\":\"text\"}]}}";
		return json;
	}

	/** 旧式"层级 → 配置定义"选择表单：没有 purpose，但 optionsFrom.sourceId=definitions 是选择结构。 */
	private static String definitionSelectionForm(String purpose) {
		String purposeField = purpose == null ? "" : "\"purpose\":\"" + purpose + "\",";
		return "{\"form\":{" + purposeField + "\"scenario\":\"CLARIFY\",\"fields\":["
				+ "{\"key\":\"level\",\"label\":\"层级\",\"type\":\"enum\",\"required\":true,"
				+ "\"options\":[{\"value\":\"GLOBAL\",\"label\":\"全局\"},{\"value\":\"PROJECT\",\"label\":\"项目\"}]},"
				+ "{\"key\":\"defs\",\"label\":\"配置项\",\"type\":\"multi_select\","
				+ "\"optionsFrom\":{\"dependsOn\":\"level\",\"sourceId\":\"definitions\"}}]}}";
	}

	/** 旧式条件表单：没有 purpose，只有 scenario=FILTER。 */
	private static String filterForm() {
		return "{\"form\":{\"scenario\":\"FILTER\",\"fields\":["
				+ "{\"key\":\"value\",\"label\":\"值\",\"type\":\"text\"}]}}";
	}
}
