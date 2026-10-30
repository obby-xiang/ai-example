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
		when(this.taskService.findById(any())).thenReturn(task(null));

		TaskSlots slots = this.resolver.resolve(AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE",
				Map.of()), "导出货币字典，不筛选", List.of());

		assertThat(slots.undecidedDefCodes()).as("用户明确不筛选 ⇒ 明确不设，不再收集").isEmpty();
		assertThat(slots.conditionDefCodes()).isEmpty();
	}

	/** Critical：非等值/排除/空值文本不得被当成已验证 EQ。 */
	@Test
	void heuristicNonEqPhrasesAreNotVerifiedConditions() {
		when(this.taskService.findById(any())).thenReturn(task(null));
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		assertThat(this.resolver.resolve(ctx, "导出 CURRENCY，code不等于CNY", List.of()).verifiedConditionDefCodes())
			.as("不等于").isEmpty();
		assertThat(this.resolver.resolve(ctx, "导出 CURRENCY，code为空", List.of()).verifiedConditionDefCodes())
			.as("为空").isEmpty();
		assertThat(this.resolver.resolve(ctx, "导出 CURRENCY，code是CNY以外", List.of()).verifiedConditionDefCodes())
			.as("以外").isEmpty();
	}

	/** 经验证的枚举等值条件仍标记为可信（自定义配置项同路径）。 */
	@Test
	void enumValidatedEqRemainsVerified() {
		when(this.taskService.findById(any())).thenReturn(task(null));
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.resolver.resolve(ctx, "导出 CURRENCY，code = CNY", List.of());

		assertThat(slots.verifiedConditionDefCodes()).containsExactly("CURRENCY");
	}

	/** Critical：同一配置项的多个等值字段必须全部无损落库。 */
	@Test
	void multipleFieldsOnOneDefinitionAreAllKeptAndPersisted() {
		TaskSlotResolver multi = new TaskSlotResolver(
				() -> List.of(new SlotDefinition("CURRENCY", "货币字典",
						List.of(new SlotDefinition.SlotField("code", "代码", true, List.of("CNY", "USD")),
								new SlotDefinition.SlotField("region", "区域", false, List.of("XN", "US"))))),
				this.taskService, new com.fasterxml.jackson.databind.ObjectMapper());
		when(this.taskService.findById(any())).thenReturn(task(null));
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = multi.resolve(ctx, "导出 CURRENCY，code = CNY 且 region = XN", List.of());

		assertThat(slots.conditions()).extracting(TaskSlots.ConditionSlot::fieldCode)
			.containsExactly("code", "region");
		assertThat(slots.verifiedConditionDefCodes()).containsExactly("CURRENCY");
		assertThat(new ExportConditionWorkflow(this.taskService).plan(task(null)).commands())
			.extracting(com.example.configmgr.ai.slot.ConditionCommand::defCode).containsExactly("CURRENCY");
	}

	/** Critical：或/之外等不完整结构绝不 verified、绝不自动落 EQ。 */
	@Test
	void orAndBeyondTextsAreNeverVerifiedOrPersisted() {
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());
		when(this.taskService.findById(any())).thenReturn(task(null));

		TaskSlots or = this.resolver.resolve(ctx, "导出 CURRENCY，code = CNY 或 USD", List.of());
		assertThat(or.verifiedConditionDefCodes()).as("或").isEmpty();
		TaskSlots beyond = this.resolver.resolve(ctx, "导出 CURRENCY，code = CNY之外", List.of());
		assertThat(beyond.verifiedConditionDefCodes()).as("之外").isEmpty();

		assertThat(new ExportConditionWorkflow(this.taskService).plan(task(null)).undecided())
			.containsExactly("CURRENCY");
	}

	/** 无枚举值的自定义配置项：结构完整 + 字段存在即可 verified。 */
	@Test
	void noEnumCustomDefinitionCanBeVerified() {
		TaskSlotResolver custom = new TaskSlotResolver(
				() -> List.of(new SlotDefinition("CUSTOM", "自定义字典",
						List.of(new SlotDefinition.SlotField("code", "代码", true, List.of())))),
				this.taskService, new com.fasterxml.jackson.databind.ObjectMapper());
		Task customTask = task("CUSTOM", null);
		when(this.taskService.findById(any())).thenReturn(customTask);
		AiContext ctx = AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = custom.resolve(ctx, "导出 CUSTOM，code = VIP", List.of());

		assertThat(slots.verifiedConditionDefCodes()).containsExactly("CUSTOM");
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
		when(this.taskService.findById(any())).thenReturn(task(null));
		AiContext context = AiContext.of("export", "EXPORT", "EXPORT", TASK_ID, "ACTIVE", Map.of());

		TaskSlots slots = this.resolver.resolve(context, "继续这个导出任务", List.of("导出货币字典"));

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
