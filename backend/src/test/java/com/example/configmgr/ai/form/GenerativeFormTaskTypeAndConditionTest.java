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
 * 两个收集缺口的新口径（2026-10-29 闭环批）：
 *
 * <ul>
 * <li><b>任务类型</b>（{@code purpose=TASK_TYPE}）：单个 enum 单选 {@code EXPORT/IMPORT}。
 * 类型已确定（槽位有值）⇒ 零副作用拒绝并指回 {@code create_task}；类型未知 ⇒ 放行。</li>
 * <li><b>筛选条件</b>（{@code purpose=SET_CONDITIONS}）：一个非必填自由文本框（{@code variant=longText} 可选），
 * 由模型把文本解析成 {@code set_condition} 结构；留空 = 不设筛选（直接推进）。
 * 条件已明确不重复收集；<b>有明确步骤时只有 {@code QUERY_COND} 允许条件表单</b>，其余步骤
 * （{@code SELECT_DEFS}/{@code EXPORT}/{@code DONE}/导入链）一律零副作用拒绝并给继续指引。</li>
 * </ul>
 *
 * <p><b>证红</b>：{@code TASK_TYPE} 未进 purpose 白名单 ⇒ 类型未知放行、类型表单形状两条红；
 * 去掉槽位判据 ⇒ 类型重复拒绝红；把步骤门收成"只拒 EXPORT/DONE" ⇒ {@code SELECT_DEFS} 拒绝红
 * （前置步与后置步的指引不同，断言两侧互斥）；去掉 SET_CONDITIONS 单框契约 ⇒ 条件表单形状红。
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

	// ── 筛选条件（SET_CONDITIONS） ───────────────────────────────────────────

	@Test
	void conditionFormIsAcceptedOnlyAtQueryCondStep() {
		FrontendToolGuard.Verdict atQueryCond = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), context("QUERY_COND", "EXPORT"));
		assertThat(atQueryCond.accepted()).as("QUERY_COND 是条件收集的唯一现场").isTrue();

		FrontendToolGuard.Verdict atSelectDefs = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), context("SELECT_DEFS", "EXPORT"));
		assertThat(atSelectDefs.accepted()).as("SELECT_DEFS 不下发条件表单：先推进到 QUERY_COND").isFalse();
		assertThat(atSelectDefs.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(atSelectDefs.render())
			.as("前置步的指引是「先 confirm_step 推进到 QUERY_COND」，与后置步的「已关闭」可区分")
			.contains("SELECT_DEFS")
			.contains("先 confirm_step")
			.contains("推进到 QUERY_COND")
			.contains("未向前端下发表单")
			.doesNotContain("已关闭");

		FrontendToolGuard.Verdict atExport = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), context("EXPORT", "EXPORT"));
		assertThat(atExport.accepted()).as("EXPORT 步骤条件已定局：条件表单不再下发").isFalse();
		assertThat(atExport.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(atExport.render())
			.contains("EXPORT")
			.contains("QUERY_COND")
			.contains("未向前端下发表单")
			.as("后置步不再给「先推进到 QUERY_COND」的指引")
			.doesNotContain("先 confirm_step");

		FrontendToolGuard.Verdict atDone = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), context("DONE", "EXPORT"));
		assertThat(atDone.accepted()).as("DONE 在 EXPORT 之后，同样拒绝").isFalse();
		assertThat(atDone.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);

		FrontendToolGuard.Verdict atPrecheck = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), context("PRECHECK", "IMPORT"));
		assertThat(atPrecheck.accepted()).as("其它步骤（导入链）同样不收集条件").isFalse();
		assertThat(atPrecheck.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
	}

	@Test
	void conditionFormStaysAllowedWhenStepIsUnknown() {
		FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				conditionForm(), context(null, "EXPORT"));

		assertThat(verdict.accepted())
			.as("步骤未知 ≠ 关闭：无上下文的既有调用仍按槽位规则放行（兼容）")
			.isTrue();
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
			.contains("CURRENCY.code = CNY")
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
}
