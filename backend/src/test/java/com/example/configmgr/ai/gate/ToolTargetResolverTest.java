package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F2 {@link ToolTargetResolver} 的单元用例（B23L1 批③b）：<b>执行前</b>把工具参数指向的资源与
 * 当前工作区上下文对一次账。
 *
 * <h2>实例面维度（M8 / DC-11 口径）</h2>
 * 本类是<b>单实例</b>的纯判据面（不涉及实例间协调）：同一份仓储在不同实例上读到的任务一致，
 * 校验结论因此与"谁在跑"无关。跨实例那一面体现在门级用例里（参数在 A 实例校验通过、B 实例仍会
 * 独立校验一次 —— 校验不缓存、不跨实例共享）。
 *
 * <h2>覆盖</h2>
 * F1 缺 taskId / F2 任务不存在 / F3 类型不符 / F4 与上下文不一致 / F5 合法放行 /
 * F6 非作业工具不校验 / F7 taskId 非法形态（0/负/非数字/小数）/ F8 defCode 分支（私有预留）。
 */
class ToolTargetResolverTest {

	private static final long TASK_ID = 7L;

	private final TaskRepository tasks = mock(TaskRepository.class);

	private final ToolTargetResolver resolver = new ToolTargetResolver(this.tasks);

	private static final AiContext CONTEXT = AiContext.of("import", "IMPORT", "IMPORT", TASK_ID, null);

	// ── F1：缺 taskId ──────────────────────────────────────────────────────

	@Test
	void missingTaskIdIsInvalid() {
		ToolTargetResolver.Verdict verdict = this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "{}", CONTEXT);

		assertThat(verdict.valid()).isFalse();
		assertThat(verdict.detail()).contains("缺少 taskId");
	}

	// ── F2：任务不存在 ─────────────────────────────────────────────────────

	@Test
	void unknownTaskIsInvalid() {
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.empty());

		ToolTargetResolver.Verdict verdict = this.resolver.check(ToolTargetResolver.TOOL_IMPORT,
				"{\"taskId\":7}", CONTEXT);

		assertThat(verdict.valid()).isFalse();
		assertThat(verdict.detail()).contains("任务 #7 不存在");
	}

	// ── F3：类型不符 ───────────────────────────────────────────────────────

	@Test
	void taskTypeMismatchIsInvalid() {
		// 导出任务不能发起导入作业（作业类型由任务类型决定）
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.of(task(Task.TaskType.EXPORT)));

		ToolTargetResolver.Verdict importVerdict = this.resolver.check(ToolTargetResolver.TOOL_IMPORT,
				"{\"taskId\":7}", CONTEXT);
		ToolTargetResolver.Verdict publishVerdict = this.resolver.check(ToolTargetResolver.TOOL_PUBLISH,
				"{\"taskId\":7}", CONTEXT);

		assertThat(importVerdict.valid()).isFalse();
		assertThat(importVerdict.detail()).contains("类型 EXPORT").contains("IMPORT");
		assertThat(publishVerdict.valid()).isFalse();
	}

	@Test
	void exportJobNeedsExportTask() {
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.of(task(Task.TaskType.IMPORT)));

		ToolTargetResolver.Verdict verdict = this.resolver.check(ToolTargetResolver.TOOL_EXPORT,
				"{\"taskId\":7}", AiContext.of("tasks", null, null, null, null));

		assertThat(verdict.valid()).isFalse();
		assertThat(verdict.detail()).contains("不能发起 EXPORT 作业");
	}

	// ── F4：与上下文 taskId 不一致 ──────────────────────────────────────────

	@Test
	void taskIdDifferentFromContextIsInvalid() {
		AiContext otherTaskContext = AiContext.of("import", "IMPORT", "IMPORT", 9L, null);

		ToolTargetResolver.Verdict verdict = this.resolver.check(ToolTargetResolver.TOOL_IMPORT,
				"{\"taskId\":7}", otherTaskContext);

		assertThat(verdict.valid()).isFalse();
		assertThat(verdict.detail()).contains("与当前工作区任务 #9 不一致");
	}

	// ── F5：合法放行 ───────────────────────────────────────────────────────

	@Test
	void validTargetPassesForAllFourJobTools() {
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.of(task(Task.TaskType.IMPORT)));
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_PRECHECK, "{\"taskId\":7}", CONTEXT).valid()).isTrue();
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "{\"taskId\":7}", CONTEXT).valid()).isTrue();
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_PUBLISH, "{\"taskId\":7}", CONTEXT).valid()).isTrue();

		// 数字写成串也接受（模型的 JSON 偶尔把数字写成文本）
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "{\"taskId\":\"7\"}", CONTEXT).valid()).isTrue();

		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.of(task(Task.TaskType.EXPORT)));
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_EXPORT, "{\"taskId\":7}",
				AiContext.of("tasks", null, null, null, null)).valid()).isTrue();
	}

	// ── F6：非作业发起工具不校验（create_task 没有 taskId） ─────────────────

	@Test
	void nonJobToolsAreNotChecked() {
		assertThat(this.resolver.check("create_task", "{\"type\":\"IMPORT\",\"title\":\"新任务\"}", CONTEXT).valid())
			.isTrue();
		assertThat(this.resolver.check("create_task", "{}", CONTEXT).valid()).isTrue();
		assertThat(this.resolver.check("check_job_status", "{\"jobId\":1}", CONTEXT).valid()).isTrue();
		// 非作业工具连"参数不是 JSON"都不判 —— 校验面之外零干预
		assertThat(this.resolver.check("create_task", "not-json", CONTEXT).valid()).isTrue();
	}

	// ── F7：taskId 非法形态 ────────────────────────────────────────────────

	@Test
	void illegalTaskIdShapesAreInvalid() {
		for (String args : List.of("{\"taskId\":0}", "{\"taskId\":-3}", "{\"taskId\":\"abc\"}",
				"{\"taskId\":7.5}", "{\"taskId\":{}}")) {
			ToolTargetResolver.Verdict verdict = this.resolver.check(ToolTargetResolver.TOOL_IMPORT, args, CONTEXT);
			assertThat(verdict.valid()).as(args + " 应判非法").isFalse();
			assertThat(verdict.detail()).as(args).contains("taskId 非法");
		}
		// 参数不是 JSON 对象：同样判非法（作业工具必须带可解析的入参）
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "not-json", CONTEXT).valid()).isFalse();
	}

	// ── F8：defCode 分支（私有预留，用合成入参驱动） ───────────────────────

	@Test
	void defCodeMustBelongToTheTask() {
		Task task = task(Task.TaskType.IMPORT);
		task.setItems(new ArrayList<>(List.of(item("CURRENCY"), item("REGION"))));
		when(this.tasks.findById(TASK_ID)).thenReturn(Optional.of(task));

		assertThat(this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "{\"taskId\":7,\"defCode\":\"CURRENCY\"}",
				CONTEXT).valid()).isTrue();
		assertThat(this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "{\"taskId\":7,\"defCode\":\"currency\"}",
				CONTEXT).valid()).as("大小写不敏感").isTrue();

		ToolTargetResolver.Verdict foreign = this.resolver.check(ToolTargetResolver.TOOL_IMPORT,
				"{\"taskId\":7,\"defCode\":\"USD\"}", CONTEXT);
		assertThat(foreign.valid()).isFalse();
		assertThat(foreign.detail()).contains("配置定义 USD 不在任务 #7 内");
	}

	// ── 失败开放：仓储抖动 ≠ 目标非法 ───────────────────────────────────────

	@Test
	void repositoryFailureFailsOpen() {
		when(this.tasks.findById(anyLong())).thenThrow(new IllegalStateException("DB 抖动"));

		assertThat(this.resolver.check(ToolTargetResolver.TOOL_IMPORT, "{\"taskId\":7}", CONTEXT).valid())
			.as("查不了不等于不存在（交给执行路径如实报错）").isTrue();
	}

	private static Task task(Task.TaskType type) {
		Task task = new Task();
		task.setId(TASK_ID);
		task.setType(type);
		task.setTitle("受控任务");
		task.setCurrentStep("IMPORT");
		task.setStatus(Task.TaskStatus.ACTIVE);
		return task;
	}

	private static TaskItem item(String defCode) {
		TaskItem item = new TaskItem();
		item.setTaskId(TASK_ID);
		item.setDefCode(defCode);
		return item;
	}

}
