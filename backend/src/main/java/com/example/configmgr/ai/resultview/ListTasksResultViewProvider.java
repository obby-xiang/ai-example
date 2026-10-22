package com.example.configmgr.ai.resultview;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tools.AiTools;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code list_tasks} 的 {@code kind=table} 视图（迁移路径 §7.1-④ 的首个后端工具）。
 *
 * <h2>为什么行值来自服务层重新取数，而不是解析工具结果文本</h2>
 * 工具结果是给人/模型看的一行行文本（{@code - #7 [EXPORT] …}）。把它反解回结构化行，
 * 等于把设计稿 §1.2 缺口 A 的"借文本当协议"原样搬进新契约 —— 文案一改即静默失配。
 * 这里改为<b>同一数据源</b>（{@link TaskService#findAll()}，与 {@code AiTools#listTasks()} 同一次查询同一排序）
 * 取结构化行：视图与文本同源不同投影，与 {@code ResultView} 的"两通道同源"取向一致。
 *
 * <h2>与工具自身上限的关系</h2>
 * 工具回填模型的口径是"前 {@link AiTools#LIST_TASKS_MAX_ROWS} 条 + 共 N 条"（{@code AiTools}）。
 * 视图按同一口径裁行，因此卡面看到的行数 = 模型看到的行数，不会出现"卡面比模型多出几行"的
 * 两套事实；超出部分体现在 {@code meta.truncated{shownRows,totalRows}} 上。
 *
 * <h2>失败姿态</h2>
 * 取数异常 ⇒ 返回 {@code null}（本次不出视图）。视图是展示增强，<b>绝不</b>因为构造失败而让
 * 工具结果帧发不出去或让工具判失败（{@code status:error} 只用于"视图构造失败但工具成功"的显式降级，
 * 而这里连降级都省了 —— 旧卡面本来就是完整的）。
 */
@Slf4j
@Component
public class ListTasksResultViewProvider implements ToolResultViewProvider {

	public static final String TOOL_NAME = "list_tasks";

	/** 帧内其余字段（result/truncated 标记等）的预留字节：视图不用满整帧预算。 */
	private static final int FRAME_RESERVE_BYTES = 4 * 1024;

	private static final int MIN_VIEW_BUDGET = 4 * 1024;

	private final TaskService taskService;

	private final AiProperties properties;

	@Autowired
	public ListTasksResultViewProvider(TaskService taskService, AiProperties properties) {
		this.taskService = taskService;
		this.properties = properties == null ? new AiProperties() : properties;
	}

	@Override
	public boolean supports(String toolName) {
		return TOOL_NAME.equals(toolName);
	}

	@Override
	public ResultView build(String toolCallId, String toolName, String resultText) {
		if (!supports(toolName)) {
			return null;
		}
		try {
			List<Task> tasks = this.taskService.findAll();
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Task task : tasks) {
				if (rows.size() >= AiTools.LIST_TASKS_MAX_ROWS) {
					break;
				}
				rows.add(row(task));
			}
			return ResultViews.table("rv-" + toolCallId, "i18n:resultview.listTasks.title", columns(), rows,
					"i18n:resultview.listTasks.fallback", "list_tasks", viewBudget(), tasks.size());
		}
		catch (RuntimeException ex) {
			log.warn("list_tasks 视图构造失败（本次不出 resultView，卡面走旧形态）：toolCallId={}", toolCallId, ex);
			return null;
		}
	}

	/** 视图预算 = min(契约上限, 单帧预算 − 其余字段余量)：{@code resultView} 是不可截字段（§6.3-3）。 */
	private int viewBudget() {
		int frameBytes = Optional.ofNullable(this.properties.getResultBudget())
				.map(AiProperties.ResultBudget::getUi)
				.map(AiProperties.ResultBudget.Ui::getMaxFrameBytes)
				.orElse(AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		int usable = Math.max(MIN_VIEW_BUDGET, frameBytes - FRAME_RESERVE_BYTES);
		return Math.min(ResultViews.MAX_BYTES, usable);
	}

	private static List<ResultViewColumn> columns() {
		return List.of(
				ResultViewColumn.of("id", ResultViewColumnType.INTEGER, "i18n:resultview.listTasks.col.id", 1),
				ResultViewColumn.of("type", ResultViewColumnType.STRING, "i18n:resultview.listTasks.col.type", 2),
				ResultViewColumn.of("title", ResultViewColumnType.STRING, "i18n:resultview.listTasks.col.title", 5),
				ResultViewColumn.of("step", ResultViewColumnType.STRING, "i18n:resultview.listTasks.col.step", 2),
				ResultViewColumn.of("status", ResultViewColumnType.STRING, "i18n:resultview.listTasks.col.status", 2));
	}

	private static Map<String, Object> row(Task task) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", task.getId());
		row.put("type", task.getType() == null ? null : task.getType().name());
		row.put("title", task.getTitle());
		row.put("step", task.getCurrentStep());
		row.put("status", task.getStatus() == null ? null : task.getStatus().name());
		return row;
	}
}
