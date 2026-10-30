package com.example.configmgr.ai.form;

import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.slot.TaskSlots.ConditionSlot;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个收集缺口的新口径（2026-10-29 闭环批；2026-10-30 条件单通道收口）：
 *
 * <ul>
 * <li><b>任务类型</b>（{@code purpose=TASK_TYPE}）：单个 enum 单选 {@code EXPORT/IMPORT}。
 * 类型已确定（槽位有值）⇒ 零副作用拒绝并指回 {@code create_task}；类型未知 ⇒ 放行。</li>
 * <li><b>筛选条件</b>（{@code purpose=SET_CONDITIONS}）：<b>只由宿主发放</b>（QUERY_COND 且条件未决定时一次），
 * 模型自行下发一律零副作用拒绝；表单形状 = 一个非必填自由文本框，留空 = 不设筛选（直接推进）。</li>
 * </ul>
 *
 * <p><b>证红</b>：{@code TASK_TYPE} 未进 purpose 白名单 ⇒ 类型未知放行、类型表单形状两条红；
 * 去掉槽位判据 ⇒ 类型重复拒绝红；把模型条件表单改成按步骤放行 ⇒ 单通道用例红；
 * 去掉 SET_CONDITIONS 单框契约 ⇒ 条件表单形状红。
 */
class GenerativeFormTaskTypeAndConditionTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

	// ── 任务类型（TASK_TYPE） ────────────────────────────────────────────────

	@Test
	void taskTypeFormIsRejectedWithCreateTaskGuidanceWhenTypeIsKnown() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				taskTypeForm(), context(null, "EXPORT"));

		assertThat(verdict.accepted()).as("类型已确定时不再下发类型表单（零副作用）").isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(verdict.render())
			.contains("EXPORT")
			.contains("create_task")
			.contains("type=EXPORT")
			.contains("未向前端下发表单");
	}

	@Test
	void taskTypeFormIsAllowedWhenTypeIsStillUnknown() {
		FrontendToolGuard.Verdict unknown = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				taskTypeForm(), context(null, null));

		assertThat(unknown.accepted()).as("类型未知时类型表单仍可用（不得用文字反问类型）").isTrue();
	}

	@Test
	void taskTypeFormMustBeOneEnumWithExactlyExportAndImport() {
		assertThat(GenerativeFormRules.argumentIssues(json(taskTypeForm())))
			.as("标准的单 enum 单选表单必须通过白名单")
			.isEmpty();

		assertThat(GenerativeFormRules.argumentIssues(json(
				"{\"form\":{\"purpose\":\"TASK_TYPE\",\"scenario\":\"CLARIFY\",\"fields\":["
						+ "{\"key\":\"taskType\",\"label\":\"任务类型\",\"type\":\"enum\",\"options\":"
						+ "[{\"value\":\"EXPORT\",\"label\":\"导出\"},{\"value\":\"IMPORT\",\"label\":\"导入\"}]},"
						+ "{\"key\":\"title\",\"label\":\"标题\",\"type\":\"text\"}]}}")))
			.as("类型表单只能有一个字段")
			.isNotEmpty();

		assertThat(GenerativeFormRules.argumentIssues(json(
				"{\"form\":{\"purpose\":\"TASK_TYPE\",\"scenario\":\"CLARIFY\",\"fields\":["
						+ "{\"key\":\"taskType\",\"label\":\"任务类型\",\"type\":\"text\"}]}}")))
			.as("类型表单必须是 enum 单选，不能是自由文本（文字反问的等价物）")
			.isNotEmpty();

		assertThat(GenerativeFormRules.argumentIssues(json(
				"{\"form\":{\"purpose\":\"TASK_TYPE\",\"scenario\":\"CLARIFY\",\"fields\":["
						+ "{\"key\":\"taskType\",\"label\":\"任务类型\",\"type\":\"enum\",\"options\":"
						+ "[{\"value\":\"EXPORT\",\"label\":\"导出\"}]}]}}")))
			.as("选项必须正好是 EXPORT/IMPORT 两个")
			.isNotEmpty();
	}

	// ── 筛选条件（SET_CONDITIONS）：单通道 —— 只由宿主发放 ─────────────────────

	/**
	 * 新口径（2026-10-30 条件收集单通道）：模型自行下发 {@code SET_CONDITIONS} 一律零副作用拒绝，
	 * 不论步骤与槽位（含 QUERY_COND、步骤未知）—— 避免模型与宿主双通道重复收集。
	 */
	@Test
	void modelConditionFormIsAlwaysRejectedWithHostOnlyGuidance() {
		for (String step : new String[] { "QUERY_COND", "SELECT_DEFS", "EXPORT", "DONE", null }) {
			FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
					conditionForm(), gapContext(step));

			assertThat(verdict.accepted()).as("步骤 %s：模型不得下发条件表单", step).isFalse();
			assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
			assertThat(verdict.render())
				.as("指引指向宿主单通道 + set_condition")
				.contains("宿主")
				.contains("零副作用")
				.contains("set_condition");
		}
	}

	@Test
	void blankConditionTextIsValidAndMeansNoFilter() {
		JsonNode form = json(conditionForm()).get("form");

		assertThat(GenerativeFormRules.resultIssues(form, json("{\"condition\":\"\"}")))
			.as("非必填框留空是合法输入：空文本 = 不设筛选")
			.isEmpty();
		assertThat(GenerativeFormRules.resultIssues(form, json("{}")))
			.as("前端对可选空值可以整个键都不提交")
			.isEmpty();
		assertThat(GenerativeFormRules.resultIssues(form,
				json("{\"condition\":\"CURRENCY 的 code = CNY，且 NAME 包含 人民币\"}")))
			.as("自由文本原样通过复核，由模型解析成 set_condition 结构")
			.isEmpty();
	}

	@Test
	void conditionFormIsRejectedWhenConditionsAreAlreadyKnown() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), conditionsContext("QUERY_COND"));

		assertThat(verdict.accepted()).as("条件已明确：不重复收集").isFalse();
		assertThat(verdict.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(verdict.render())
			.as("已知条件同样走宿主单通道，并指回 set_condition")
			.contains("宿主")
			.contains("set_condition");
	}

	@Test
	void conditionFormMustBeOneOptionalTextField() {
		assertThat(GenerativeFormRules.argumentIssues(json(conditionForm())))
			.as("标准的单框条件表单必须通过白名单")
			.isEmpty();

		assertThat(GenerativeFormRules.argumentIssues(json(
				"{\"form\":{\"purpose\":\"SET_CONDITIONS\",\"scenario\":\"FILTER\",\"fields\":["
						+ "{\"key\":\"condition\",\"label\":\"筛选条件\",\"type\":\"text\",\"variant\":\"longText\"},"
						+ "{\"key\":\"extra\",\"label\":\"补充\",\"type\":\"text\"}]}}")))
			.as("条件表单只能有一个输入框")
			.isNotEmpty();

		assertThat(GenerativeFormRules.argumentIssues(json(
				"{\"form\":{\"purpose\":\"SET_CONDITIONS\",\"scenario\":\"FILTER\",\"fields\":["
						+ "{\"key\":\"condition\",\"label\":\"筛选条件\",\"type\":\"text\",\"required\":true}]}}")))
			.as("条件框必须非必填：留空要能直接推进（全量导出）")
			.isNotEmpty();

		assertThat(GenerativeFormRules.argumentIssues(json(
				"{\"form\":{\"purpose\":\"SET_CONDITIONS\",\"scenario\":\"FILTER\",\"fields\":["
						+ "{\"key\":\"condition\",\"label\":\"筛选条件\",\"type\":\"enum\",\"options\":"
						+ "[{\"value\":\"EQ\",\"label\":\"等于\"}]}]}}")))
			.as("条件收集是自由文本，不是让模型预先拼枚举")
			.isNotEmpty();
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private JsonNode json(String text) {
		try {
			return this.mapper.readTree(text);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String taskTypeForm() {
		return "{\"form\":{\"purpose\":\"TASK_TYPE\",\"scenario\":\"CLARIFY\",\"title\":\"任务类型\",\"fields\":["
				+ "{\"key\":\"taskType\",\"label\":\"任务类型\",\"type\":\"enum\",\"required\":true,\"options\":"
				+ "[{\"value\":\"EXPORT\",\"label\":\"导出\"},{\"value\":\"IMPORT\",\"label\":\"导入\"}]}]}}";
	}

	private static String conditionForm() {
		return "{\"form\":{\"purpose\":\"SET_CONDITIONS\",\"scenario\":\"FILTER\",\"fields\":["
				+ "{\"key\":\"condition\",\"label\":\"筛选条件（可留空）\",\"type\":\"text\",\"variant\":\"longText\"}]}}";
	}

	/** 任务槽位上下文：{@code taskType} 可为 null（未知）。 */
	private static AiContext context(String step, String taskType) {
		Map<String, Object> slots = new LinkedHashMap<>();
		if (taskType != null) {
			slots.put("taskType", taskType);
		}
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, slots);
		return AiContext.of("tasks", null, step, null, extra);
	}

	/** 条件已明确的槽位上下文（配置项 + 条件都齐）。 */
	private static AiContext conditionsContext(String step) {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY"),
				List.of(new ConditionSlot("CURRENCY", "code", "CNY")));
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, slots.toMap());
		return AiContext.of("tasks", null, step, null, extra);
	}

	/** 有真实条件缺口的槽位上下文：配置项已知、条件逐项未决定（收集表单的合法时机）。 */
	private static AiContext gapContext(String step) {
		TaskSlots slots = new TaskSlots("EXPORT", List.of("CURRENCY"), List.of(), List.of(),
				List.of("CURRENCY"), List.of(), List.of());
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(TaskSlots.EXTRA_KEY, slots.toMap());
		return AiContext.of("tasks", null, step, null, extra);
	}
}
