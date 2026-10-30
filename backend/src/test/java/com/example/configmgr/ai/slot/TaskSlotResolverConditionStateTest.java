package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.tool.AiContext;
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

	private static Task task(String conditionJson) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep("QUERY_COND");
		task.setStatus(Task.TaskStatus.ACTIVE);
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode("CURRENCY");
		item.setConditionJson(conditionJson);
		task.getItems().add(item);
		return task;
	}
}
