package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.form.ExportConditionWorkflow;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 槽位的条件三态（Task 1 第 1/3 项）：任务条目里的非 EQ 条件/范围键同样算"已设置"，
 * 逐项核对后没有未决定项；用户明确"不筛选"也是明确不设。
 */
class TaskSlotResolverConditionStateTest {

	private static final long TASK_ID = 7L;

	private final TaskService taskService = mock(TaskService.class);

	private final TaskSlotResolver resolver = new TaskSlotResolver(
			() -> List.of(new SlotDefinition("CURRENCY", "货币字典",
					List.of(new SlotDefinition.SlotField("code", "代码", true, List.of("CNY", "USD"))))),
			this.taskService, new com.fasterxml.jackson.databind.ObjectMapper());

	@Test
	void nonEqTaskConditionIsSetInsteadOfUndecided() {
		Task task = task("{\"scopeKeys\":[\"XN\"],\"fields\":["
				+ "{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"CNY\",\"USD\"]}]}");
		when(this.taskService.findById(any())).thenReturn(task);

		TaskSlots slots = this.resolver.resolve(AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE",
				Map.of()), "继续这个导出任务", List.of("导出货币字典"));

		assertThat(slots.defCodes()).containsExactly("CURRENCY");
		assertThat(slots.conditionDefCodes()).contains("CURRENCY");
		assertThat(slots.undecidedDefCodes()).as("IN/scopeKeys 已是条件事实，不得当成未决定").isEmpty();
		assertThat(slots.conditionTexts()).anyMatch(text -> text.contains("IN") && text.contains("CNY"));
	}

	@Test
	void explicitNoFilterMakesConditionsDecided() {
		// "明确不筛选"的结构化表达 = 任务条目 conditionJson 为 "{}"（EXPLICIT_UNSET）；
		// 自由文本不再参与判定（见 TaskSlotResolver 的自然语言信任边界）。
		when(this.taskService.findById(any())).thenReturn(task("{}"));

		TaskSlots slots = this.resolver.resolve(AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE",
				Map.of()), null, List.of());

		assertThat(slots.undecidedDefCodes()).as("明确不设 ⇒ 已有答案，不再收集").isEmpty();
		assertThat(slots.conditionDefCodes()).isEmpty();
		assertThat(slots.conditions()).isEmpty();
	}

	/**
	 * 非 EQ 文本不再是任何"可信条件"的来源：它们连条件事实都不产生（原文已不参与解析）。
	 * （原 {@code heuristicNonEqPhrasesAreNotVerifiedConditions} 只测已退役的文本可信链，已删。）
	 */
	@Test
	void freeTextConditionsAreNotFacts() {
		when(this.taskService.findById(any())).thenReturn(task(null));
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.resolver.resolve(ctx, "导出 CURRENCY，code不等于CNY", List.of());

		assertThat(slots.conditions()).isEmpty();
		assertThat(slots.conditionDefCodes()).isEmpty();
		assertThat(slots.undecidedDefCodes()).containsExactly("CURRENCY");
	}

	/** 任务条目里的枚举等值条件（结构化）即"已设置"，且落成模型可读的 EQ 槽位。 */
	@Test
	void taskConditionJsonEqBecomesSetAndVerifiedSlot() {
		when(this.taskService.findById(any())).thenReturn(task("{\"fields\":[{\"fieldCode\":\"code\","
				+ "\"operator\":\"EQ\",\"value\":\"CNY\"}]}"));
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.resolver.resolve(ctx, null, List.of());

		assertThat(slots.conditions()).containsExactly(new TaskSlots.ConditionSlot("CURRENCY", "code", "CNY"));
		assertThat(slots.conditionDefCodes()).containsExactly("CURRENCY");
		assertThat(slots.undecidedDefCodes()).isEmpty();
		// verifiedConditionDefCodes 是退役兼容字段（文本可信链删除后生产恒为空）
		assertThat(slots.verifiedConditionDefCodes()).isEmpty();
	}

	/** Critical：同一配置项的多个等值字段必须全部无损落库。 */
	@Test
	void multipleFieldsOnOneDefinitionAreAllKeptAndPersisted() {
		TaskSlotResolver multi = new TaskSlotResolver(
				() -> List.of(new SlotDefinition("CURRENCY", "货币字典",
						List.of(new SlotDefinition.SlotField("code", "代码", true, List.of("CNY", "USD")),
								new SlotDefinition.SlotField("region", "区域", false, List.of("XN", "US"))))),
				this.taskService, new com.fasterxml.jackson.databind.ObjectMapper());
		Task structured = task("{\"fields\":["
				+ "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"},"
				+ "{\"fieldCode\":\"region\",\"operator\":\"EQ\",\"value\":\"XN\"}]}");
		when(this.taskService.findById(any())).thenReturn(structured);
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = multi.resolve(ctx, null, List.of());

		assertThat(slots.conditions()).extracting(TaskSlots.ConditionSlot::fieldCode)
			.containsExactly("code", "region");
		assertThat(slots.conditionDefCodes()).containsExactly("CURRENCY");
		assertThat(new ExportConditionWorkflow(this.taskService).plan(structured).commands())
			.extracting(com.example.configmgr.ai.slot.ConditionCommand::defCode).containsExactly("CURRENCY");
	}

	/** Critical：没有任何条件事实时，导出条件工作流必须把该配置项判为"未决定"（不得凭空放行）。 */
	@Test
	void missingConditionFactsStayUndecidedInTheWorkflow() {
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());
		when(this.taskService.findById(any())).thenReturn(task(null));

		TaskSlots slots = this.resolver.resolve(ctx, "导出 CURRENCY，code = CNY 或 USD", List.of());
		assertThat(slots.conditions()).as("原文里的“或”不产生结构化条件").isEmpty();

		assertThat(new ExportConditionWorkflow(this.taskService).plan(task(null)).undecided())
			.containsExactly("CURRENCY");
	}

	/** 无枚举值的自定义配置项：任务条目里的结构化 EQ 同样成事实（不再需要"枚举自证"）。 */
	@Test
	void noEnumCustomDefinitionConditionIsAccepted() {
		TaskSlotResolver custom = new TaskSlotResolver(
				() -> List.of(new SlotDefinition("CUSTOM", "自定义字典",
						List.of(new SlotDefinition.SlotField("code", "代码", true, List.of())))),
				this.taskService, new com.fasterxml.jackson.databind.ObjectMapper());
		Task customTask = task("CUSTOM", "{\"fields\":[{\"fieldCode\":\"code\","
				+ "\"operator\":\"EQ\",\"value\":\"VIP\"}]}");
		when(this.taskService.findById(any())).thenReturn(customTask);
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = custom.resolve(ctx, null, List.of());

		assertThat(slots.conditions()).containsExactly(new TaskSlots.ConditionSlot("CUSTOM", "code", "VIP"));
		assertThat(slots.conditionDefCodes()).containsExactly("CUSTOM");
		assertThat(new ExportConditionWorkflow(this.taskService).plan(customTask).undecided()).isEmpty();
	}

	/** Important：空白判定只认任务权威 currentStep，不被请求 ctx.step 分叉带偏。 */
	@Test
	void exportBlankDecisionUsesAuthoritativeTaskStepNotContextStep() {
		Task exportTask = task(null);
		exportTask.setCurrentStep("EXPORT");
		when(this.taskService.findById(any())).thenReturn(exportTask);
		TaskSlots taskAtExport = this.resolver.resolve(
				AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of()),
				"继续这个导出任务", List.of());
		assertThat(taskAtExport.undecidedDefCodes()).as("任务已在 EXPORT ⇒ 空白=明确留空，即使 ctx 说 QUERY_COND").isEmpty();

		Task queryCondTask = task(null);
		queryCondTask.setCurrentStep("QUERY_COND");
		when(this.taskService.findById(any())).thenReturn(queryCondTask);
		TaskSlots ctxAtExport = this.resolver.resolve(
				AiContext.of("export", "EXPORT", "EXPORT", TASK_ID, "ACTIVE", Map.of()),
				"继续这个导出任务", List.of());
		assertThat(ctxAtExport.undecidedDefCodes()).as("任务仍在 QUERY_COND ⇒ 未决定，即使 ctx 说 EXPORT")
			.containsExactly("CURRENCY");
	}

	/** N-2：权威步骤 EXPORT 的 null 与预检同口径（明确留空），ContextBuilder 也渲染全量。 */
	@Test
	void blankConditionAtExportIsExplicitUnsetInSlotsAndContext() {
		// 权威步骤只看任务条目（ctx.step 不参与）：任务已在 EXPORT，空白 = 明确留空。
		Task exportTask = task(null);
		exportTask.setCurrentStep("EXPORT");
		when(this.taskService.findById(any())).thenReturn(exportTask);
		AiContext context = AiContext.of("export", "EXPORT", "EXPORT", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.resolver.resolve(context, null, List.of());

		assertThat(slots.undecidedDefCodes()).isEmpty();
		String rendered = new ContextBuilder(null, new com.fasterxml.jackson.databind.ObjectMapper())
			.buildContextMessage(AiContext.of("export", "EXPORT", "EXPORT", TASK_ID, "ACTIVE",
					Map.of(TaskSlots.EXTRA_KEY, slots.toMap())));
		assertThat(rendered).contains("未设筛选: 全量");
	}

	private static Task task(String conditionJson) {
		return task("CURRENCY", conditionJson);
	}

	private static Task task(String defCode, String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode(defCode);
		item.setConditionJson(conditionJson);
		task.getItems().add(item);
		return task;
	}
}
