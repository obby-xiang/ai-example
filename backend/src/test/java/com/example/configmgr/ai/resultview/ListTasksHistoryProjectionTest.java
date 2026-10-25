package com.example.configmgr.ai.resultview;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tools.AiTools;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-33（S3）：<b>历史归档的 {@code list_tasks} 卡面必须显示"当时"的结果</b>。
 *
 * <h2>缺陷（修复前实测）</h2>
 * {@code /history} 与实时帧共用一个 {@code build}，而它只做 {@code taskService.findAll()}：
 * 一次 {@code list_tasks} 之后任务被新建/改名/推进/删除，刷新对话 ⇒ 归档表格显示<b>今天</b>的任务行，
 * 与同轮 assistant 文本、与当时的"共 N 条"告知全都对不上；附带每次刷新都为每条历史
 * {@code list_tasks} 各跑一次全量任务表载入。
 *
 * <h2>判据</h2>
 * <ul>
 * <li>归档（{@code historical=true}）的行值只来自持久化结果原文，<b>一次都不许</b>调用服务层；</li>
 * <li>归档的截断三件套用<b>当时的</b>"共 N 条"，不重算；</li>
 * <li>原文不是当前行格式（旧会话 / 被模型侧裁剪切断）⇒ 不出视图（回落原文），不得用当前库补表；</li>
 * <li>实时帧（{@code historical=false}）行为逐字不变：仍取服务层结构化数据；</li>
 * <li>生产者（{@code AiTools#listTasks()}）与归档投影共用同一组格式常量，输出可往返。</li>
 * </ul>
 */
class ListTasksHistoryProjectionTest {

	private final TaskService taskService = mock(TaskService.class);

	@Test
	void archivedRowsComeFromThePersistedTextAndNeverTouchTheDatabase() {
		when(this.taskService.findAll()).thenReturn(List.of(currentTask(9L, "今天的标题")));

		ResultView view = provider().build("call-1", "list_tasks",
				"- #7 [IMPORT] 当时的标题 步骤:PRECHECK 状态:ACTIVE\n- #8 [EXPORT] 另一条 步骤:SELECT_DEFS 状态:ACTIVE",
				true);

		assertThat(view).isNotNull();
		List<Map<String, Object>> rows = ResultViewTest.rowsOf(view.toMap());
		assertThat(rows).hasSize(2);
		assertThat(rows.getFirst()).containsEntry("id", 7L).containsEntry("type", "IMPORT")
				.containsEntry("title", "当时的标题").containsEntry("step", "PRECHECK")
				.containsEntry("status", "ACTIVE");
		assertThat(rows.get(1)).containsEntry("id", 8L).containsEntry("title", "另一条");
		verify(this.taskService, never()).findAll();
	}

	@Test
	void archivedTruncationKeepsTheTotalsOfThatMoment() {
		when(this.taskService.findAll()).thenReturn(List.of());
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < AiTools.LIST_TASKS_MAX_ROWS; i++) {
			text.append("- #").append(100 + i).append(" [EXPORT] 任务 ").append(i)
					.append(" 步骤:EXPORT 状态:ACTIVE\n");
		}
		text.append(AiTools.LIST_TASKS_TOTAL_PREFIX).append(25).append(AiTools.LIST_TASKS_TOTAL_SUFFIX);

		Map<String, Object> map = provider().build("call-2", "list_tasks", text.toString(), true).toMap();

		assertThat(map).containsEntry("status", "partial");
		assertThat(ResultViewTest.rowsOf(map)).hasSize(AiTools.LIST_TASKS_MAX_ROWS);
		@SuppressWarnings("unchecked")
		Map<String, Object> truncated = (Map<String, Object>) ((Map<String, Object>) map.get("meta")).get("truncated");
		assertThat(truncated)
				.as("截断三件套必须是当时的数字（25 条 / 显示 20 条），不得随当前库重算")
				.containsEntry("shownRows", AiTools.LIST_TASKS_MAX_ROWS)
				.containsEntry("totalRows", 25);
		verify(this.taskService, never()).findAll();
	}

	@Test
	void archivedEmptyTaskListIsTheEmptyStatusNotAFailure() {
		when(this.taskService.findAll()).thenReturn(List.of(currentTask(9L, "今天的标题")));

		Map<String, Object> map = provider().build("call-3", "list_tasks", AiTools.LIST_TASKS_EMPTY_TEXT, true)
				.toMap();

		assertThat(map).containsEntry("status", "empty");
		assertThat(ResultViewTest.rowsOf(map)).isEmpty();
		@SuppressWarnings("unchecked")
		Map<String, Object> empty = (Map<String, Object>) map.get("empty");
		assertThat(empty.get("text")).isEqualTo(ListTasksResultViewProvider.EMPTY_TEXT);
		verify(this.taskService, never()).findAll();
	}

	@Test
	void archivedTextInAnotherFormatYieldsNoViewInsteadOfLiveRows() {
		when(this.taskService.findAll()).thenReturn(List.of(currentTask(9L, "今天的标题")));

		assertThat(provider().build("call-4", "list_tasks", "- #7 [IMPORT] 旧形态只有三列", true))
				.as("解析不了就不出视图（卡面回落原文），绝不用今天的库补一张表")
				.isNull();
		assertThat(provider().build("call-5", "list_tasks", null, true)).isNull();
		verify(this.taskService, never()).findAll();
	}

	@Test
	void liveFrameStillProjectsStructuredRowsFromTheService() {
		when(this.taskService.findAll()).thenReturn(List.of(currentTask(9L, "今天的标题")));

		Map<String, Object> map = provider().build("call-6", "list_tasks",
				"- #7 [IMPORT] 当时的标题 步骤:PRECHECK 状态:ACTIVE").toMap();

		List<Map<String, Object>> rows = ResultViewTest.rowsOf(map);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst()).containsEntry("id", 9L).containsEntry("title", "今天的标题");
	}

	/** 生产者与归档投影共用 {@code AiTools} 的格式常量：工具刚产出的文本必须能被归档侧原样反解。 */
	@Test
	void producerOutputRoundTripsThroughTheArchiveProjection() {
		List<Task> tasks = List.of(
				currentTask(7L, "出口报关导出"),
				currentTask(8L, "进口报关导入"));
		when(this.taskService.findAll()).thenReturn(tasks);
		AiTools aiTools = new AiTools(mock(com.example.configmgr.definition.service.DefinitionService.class),
				this.taskService, mock(com.example.configmgr.data.service.ConfigDataService.class),
				mock(com.example.configmgr.job.service.JobService.class));

		String produced = aiTools.listTasks();
		ResultView archived = provider().build("call-7", "list_tasks", produced, true);

		assertThat(archived).isNotNull();
		List<Map<String, Object>> rows = ResultViewTest.rowsOf(archived.toMap());
		assertThat(rows).hasSize(2);
		assertThat(rows.getFirst()).containsEntry("id", 7L).containsEntry("title", "出口报关导出")
				.containsEntry("step", "EXPORT").containsEntry("status", "ACTIVE");
		assertThat(rows.get(1)).containsEntry("id", 8L).containsEntry("title", "进口报关导入");
	}

	/**
	 * 把工具回填文本逐字钉住：归档投影要按这段格式反解，改一个字都是语义变更
	 * （模型看到的行格式 / 归档卡面的可解析性）。
	 */
	@Test
	void producerTextKeepsItsExactShape() {
		when(this.taskService.findAll()).thenReturn(List.of(currentTask(7L, "出口报关导出")));
		assertThat(aiTools().listTasks())
				.isEqualTo("- #7 [EXPORT] 出口报关导出 步骤:EXPORT 状态:ACTIVE");

		when(this.taskService.findAll()).thenReturn(List.of());
		assertThat(aiTools().listTasks())
				.isEqualTo("当前没有任务，可以创建一个新任务");

		List<Task> many = new java.util.ArrayList<>();
		for (int i = 0; i < AiTools.LIST_TASKS_MAX_ROWS + 1; i++) {
			many.add(currentTask((long) i, "任务 " + i));
		}
		when(this.taskService.findAll()).thenReturn(many);
		String truncated = aiTools().listTasks();
		assertThat(truncated.lines().count()).isEqualTo(AiTools.LIST_TASKS_MAX_ROWS + 1);
		assertThat(truncated)
				.endsWith("\n（共 21 条，仅显示前 20 条）")
				.startsWith("- #0 [EXPORT] 任务 0 步骤:EXPORT 状态:ACTIVE");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private ToolResultViewProvider provider() {
		return new ListTasksResultViewProvider(this.taskService, new AiProperties());
	}

	private AiTools aiTools() {
		return new AiTools(mock(com.example.configmgr.definition.service.DefinitionService.class),
				this.taskService, mock(com.example.configmgr.data.service.ConfigDataService.class),
				mock(com.example.configmgr.job.service.JobService.class));
	}

	private static Task currentTask(Long id, String title) {
		Task task = new Task();
		task.setId(id);
		task.setType(Task.TaskType.EXPORT);
		task.setTitle(title);
		task.setCurrentStep("EXPORT");
		task.setStatus(Task.TaskStatus.ACTIVE);
		return task;
	}
}
