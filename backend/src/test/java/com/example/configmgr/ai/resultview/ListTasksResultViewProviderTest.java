package com.example.configmgr.ai.resultview;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第二个迁移对象（设计稿 §7.1-④ 的首个<b>后端</b>工具）：{@code list_tasks} 的
 * {@code kind=table} 视图。
 *
 * <p>判据：
 * <ul>
 * <li>只认领 {@code list_tasks}，其余工具一律不认（缺视图 = 旧卡面）；</li>
 * <li>行来自服务层<b>结构化数据</b>（不是把结果文本反解回来）；</li>
 * <li>行数超过工具自身上限（20）⇒ {@code status=partial} + {@code meta.truncated}；</li>
 * <li>零任务 ⇒ {@code status=empty} + {@code empty.text}；</li>
 * <li>取数异常 ⇒ 返回 null（视图构造失败不能拖垮工具执行/帧）。</li>
 * </ul>
 *
 * <p>kill：把 provider 改成解析结果文本、或去掉截断三件套、或让异常穿出，本类必红。
 */
class ListTasksResultViewProviderTest {

	private final TaskService taskService = mock(TaskService.class);

	@Test
	void supportsOnlyListTasks() {
		ToolResultViewProvider provider = provider();
		assertThat(provider.supports("list_tasks")).isTrue();
		assertThat(provider.supports("list_config_defs")).isFalse();
		assertThat(provider.supports(null)).isFalse();
	}

	@Test
	void buildsATableFromTheStructuredTaskList() {
		when(this.taskService.findAll()).thenReturn(List.of(
				task(7L, Task.TaskType.EXPORT, "出口报关导出", "EXPORT", Task.TaskStatus.ACTIVE),
				task(8L, Task.TaskType.IMPORT, "进口报关导入", "IMPORT", Task.TaskStatus.COMPLETED)));

		ResultView view = provider().build("call-9", "list_tasks", "- #7 [EXPORT] 出口报关导出 步骤:EXPORT 状态:ACTIVE");
		assertThat(view).isNotNull();
		Map<String, Object> map = view.toMap();
		assertThat(map).containsEntry("kind", "table")
				.containsEntry("status", "ok")
				.containsEntry("id", "rv-call-9");
		assertThat((List<?>) map.get("columns")).hasSize(5);
		List<Map<String, Object>> rows = ResultViewTest.rowsOf(map);
		assertThat(rows).hasSize(2);
		assertThat(rows.getFirst()).containsEntry("id", 7L).containsEntry("type", "EXPORT")
				.containsEntry("title", "出口报关导出").containsEntry("step", "EXPORT")
				.containsEntry("status", "ACTIVE");
		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) map.get("meta");
		assertThat(meta).containsEntry("source", "list_tasks");
		assertThat(meta).doesNotContainKey("truncated");
	}

	@Test
	void rowsBeyondTheToolLimitAreMarkedPartial() {
		List<Task> tasks = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			tasks.add(task((long) i, Task.TaskType.EXPORT, "任务 " + i, "EXPORT", Task.TaskStatus.ACTIVE));
		}
		when(this.taskService.findAll()).thenReturn(tasks);

		Map<String, Object> map = provider().build("call-1", "list_tasks", "rows").toMap();
		assertThat(ResultViewTest.rowsOf(map)).hasSize(20);
		assertThat(map).containsEntry("status", "partial");
		@SuppressWarnings("unchecked")
		Map<String, Object> truncated = (Map<String, Object>) ((Map<String, Object>) map.get("meta")).get("truncated");
		assertThat(truncated).containsEntry("shownRows", 20).containsEntry("totalRows", 25);
	}

	@Test
	void emptyTaskListDegradesToEmptyStatus() {
		when(this.taskService.findAll()).thenReturn(List.of());
		Map<String, Object> map = provider().build("call-2", "list_tasks", "当前没有任务").toMap();
		assertThat(map).containsEntry("status", "empty");
		assertThat(map).containsKey("empty");
		assertThat(map).containsKey("fallback");
	}

	/** 空任务列表 ≠ "任务列表无法以表格展示"：空态文案走工具自己的 key（设计稿 §3.2 `empty`）。 */
	@Test
	void emptyTaskListUsesTheTaskSpecificEmptyText() {
		when(this.taskService.findAll()).thenReturn(List.of());
		Map<String, Object> map = provider().build("call-2b", "list_tasks", "当前没有任务").toMap();
		@SuppressWarnings("unchecked")
		Map<String, Object> empty = (Map<String, Object>) map.get("empty");
		assertThat(empty.get("text")).isEqualTo("i18n:resultview.listTasks.empty");
		@SuppressWarnings("unchecked")
		Map<String, Object> fallback = (Map<String, Object>) map.get("fallback");
		assertThat(fallback.get("text")).as("失败出口仍是一句可读的降级文案").isEqualTo("i18n:resultview.listTasks.fallback");
	}

	@Test
	void serviceFailureYieldsNoViewInsteadOfBreakingTheFrame() {
		when(this.taskService.findAll()).thenThrow(new IllegalStateException("db down"));
		assertThat(provider().build("call-3", "list_tasks", "文本")).isNull();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private ToolResultViewProvider provider() {
		return new ListTasksResultViewProvider(this.taskService, new AiProperties());
	}

	private static Task task(Long id, Task.TaskType type, String title, String step, Task.TaskStatus status) {
		Task task = new Task();
		task.setId(id);
		task.setType(type);
		task.setTitle(title);
		task.setCurrentStep(step);
		task.setStatus(status);
		return task;
	}
}
