package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构化 {@code create_task(defCodes=…)} 的候选配置项：只写进运行上下文，既不落任务条目，
 * 也足以让宿主/模型的选择表单被"已知槽位"零副作用拒绝。
 *
 * <p>锁两半（同一用例，两半必须同时在）：
 * <ol>
 * <li>带 {@code defCodes} ⇒ {@code RunContextRefresher} 写入候选（{@code extra.taskSlots} 有 defCodes），
 *     {@code generative_form(SELECT_DEFINITIONS)} 被 {@code FORM_SLOTS_ALREADY_KNOWN} 拒绝，模型直接
 *     {@code select_definitions}；</li>
 * <li>不带 {@code defCodes} ⇒ 没有候选，同一张选择表单照常放行（宿主只弹一次）。</li>
 * </ol>
 *
 * <p><b>证红</b>：删掉 {@code RunContextRefresher#applyCreateTask} 里的
 * {@code applyCreateTaskDefCodes(base, settled)} 调用（旧路径：参数被丢弃）⇒ 第 1 半红；
 * 让它无条件写候选 ⇒ 第 2 半红。
 */
class CreateTaskDefCodesSlotTest {

	private static final long TASK_ID = 7L;

	private static final String SELECT_DEFINITIONS_FORM = "{\"form\":{\"scenario\":\"CLARIFY\","
			+ "\"purpose\":\"SELECT_DEFINITIONS\",\"fields\":["
			+ "{\"key\":\"defs\",\"label\":\"配置项\",\"type\":\"text\"}]}}";

	private final ObjectMapper mapper = new ObjectMapper();

	private final TaskService taskService = mock(TaskService.class);

	private final RunContextRefresher refresher = new RunContextRefresher(this.taskService);

	private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

	@Test
	void defCodesFromCreateTaskBecomeSlotFactsAndOnlyThenBlockTheSelectionForm() {
		when(this.taskService.findByIdRaw(TASK_ID)).thenReturn(Optional.of(
				task(TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE)));

		// ── 上半：用户本轮明确"货币字典" ⇒ 模型在 create_task 里给出结构化候选 ──
		AiContext withDefs = this.refresher.next(AiContext.empty(),
				settledCreateTask("{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"]}")).orElseThrow();

		assertThat(TaskSlots.fromExtra(withDefs.getExtra()).defCodes())
			.as("create_task 的结构化 defCodes 必须成为槽位候选").containsExactly("CURRENCY");
		assertThat(withDefs.getExtra().get(RunContextRefresher.EXTRA_CREATE_TASK_DEF_CODES))
			.as("候选同时以服务端键留存，供后续重算槽位").isEqualTo(java.util.List.of("CURRENCY"));

		FrontendToolGuard.Verdict blocked = this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME,
				SELECT_DEFINITIONS_FORM, withDefs);
		assertThat(blocked.accepted()).as("已知配置不再允许下发重复选择表单").isFalse();
		assertThat(blocked.code()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(blocked.render()).contains("CURRENCY").contains("select_definitions");

		// ── 下半：用户没提配置 ⇒ 没有候选，表单照常可用（宿主只弹一次） ──
		AiContext withoutDefs = this.refresher.next(AiContext.empty(),
				settledCreateTask("{\"type\":\"EXPORT\"}")).orElseThrow();

		assertThat(TaskSlots.fromExtra(withoutDefs.getExtra()).hasDefCodes())
			.as("未传 defCodes 时不得凭空产生候选").isFalse();
		assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, SELECT_DEFINITIONS_FORM, withoutDefs)
			.accepted()).as("未知配置仍可用选择表单收集").isTrue();
	}

	private static PendingToolCall settledCreateTask(String arguments) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-create-1");
		pending.setName(RunContextRefresher.TOOL_CREATE_TASK);
		pending.setArguments(arguments);
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText("已创建导出任务 #" + TASK_ID + "（标题：导出任务，当前步骤：SELECT_DEFS）");
		return pending;
	}

	private static Task task(Long id, Task.TaskType type, String step, Task.TaskStatus status) {
		Task task = new Task();
		task.setId(id);
		task.setType(type);
		task.setCurrentStep(step);
		task.setStatus(status);
		task.setTitle("任务 " + id);
		return task;
	}

}
