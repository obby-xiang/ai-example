package com.example.configmgr.ai.form;

import com.example.configmgr.ai.slot.ConditionCommand;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 确定性导出条件工作流：只按任务真实 conditionJson 生成三态计划（不解析文本）。 */
class ExportConditionWorkflowTest {

	private final ExportConditionWorkflow workflow = new ExportConditionWorkflow(mock(TaskService.class));

	@Test
	void taskFactsAreLosslessAndNotReAsked() {
		Task task = task("QUERY_COND",
				item("ORDER", "{\"scopeKeys\":[\"XN\"],\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"CNY\",\"USD\"]}]}"),
				item("CURRENCY", null));

		ExportConditionWorkflow.Plan plan = this.workflow.plan(task);

		assertThat(plan.undecided()).containsExactly("CURRENCY");
		assertThat(plan.commands()).filteredOn(command -> "ORDER".equals(command.defCode()))
			.extracting(ConditionCommand::conditionJson)
			.anySatisfy(json -> assertThat(json).contains("IN").contains("scopeKeys"));
	}

	@Test
	void undecidedAtQueryCondIsCollectedButBlankAtExportIsExplicitUnset() {
		ExportConditionWorkflow.Plan pending = this.workflow.plan(task("QUERY_COND", item("ORDER", null)));
		assertThat(pending.undecided()).containsExactly("ORDER");

		ExportConditionWorkflow.Plan ready = this.workflow.plan(task("EXPORT", item("ORDER", null)));
		assertThat(ready.undecided()).isEmpty();
		assertThat(ready.commands()).extracting(ConditionCommand::state)
			.containsExactly(ConditionCommand.State.EXPLICIT_UNSET);
	}

	@Test
	void malformedStructureIsRejectedNotTreatedAsFull() {
		ExportConditionWorkflow.Plan plan = this.workflow.plan(
				task("QUERY_COND", item("ORDER", "{\"filters\":[]}")));

		assertThat(plan.malformed()).isTrue();
		assertThat(plan.readyToAdvance()).isFalse();
	}

	@Test
	void multipleFieldsFromTaskStayLossless() {
		Task task = task("QUERY_COND", item("ORDER",
				"{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"},"
						+ "{\"fieldCode\":\"region\",\"operator\":\"EQ\",\"value\":\"XN\"}]}"));

		ExportConditionWorkflow.Plan plan = this.workflow.plan(task);

		assertThat(plan.undecided()).isEmpty();
		assertThat(plan.commands().get(0).conditionJson()).contains("\"code\"").contains("\"region\"");
	}

	private static Task task(String step, TaskItem... items) {
		Task task = new Task();
		task.setId(7L);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep(step);
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setItems(List.of(items));
		return task;
	}

	private static TaskItem item(String defCode, String conditionJson) {
		TaskItem item = new TaskItem();
		item.setTaskId(7L);
		item.setDefCode(defCode);
		item.setConditionJson(conditionJson);
		return item;
	}
}
