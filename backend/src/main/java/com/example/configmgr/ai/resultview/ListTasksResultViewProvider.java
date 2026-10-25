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
 * <h2>两条通道，两个真值来源</h2>
 * <b>实时帧</b>（{@code SpToolCallingManager#resultViewOf}）走 {@link #build(String, String, String)}：
 * 行值来自服务层重新取数，而不是解析工具结果文本 —— 工具结果是给人/模型看的一行行文本，
 * 把它反解回结构化行等于把设计稿 §1.2 缺口 A 的"借文本当协议"原样搬进新契约，文案一改即静默失配。
 * 这里改为<b>同一数据源</b>（{@link TaskService#findAll()}，与 {@code AiTools#listTasks()} 同一次查询同一排序）
 * 取结构化行：视图与文本同源不同投影，与 {@code ResultView} 的"两通道同源"取向一致。
 *
 * <p><b>历史归档</b>（{@code AiController#historyResultView}）走
 * {@link #build(String, String, String, boolean)} 的 {@code historical=true} 分支：那时"当时的
 * 结构化数据源"已不存在，再查库读到的是<b>今天</b>的任务表 —— 归档卡面会与同轮 assistant 文本
 * 自相矛盾（BZ-33），且每次刷新都要全量载入一遍任务表（BZ-09 的放大器）。
 * 因此归档唯一忠实的来源是<b>那次调用的结果原文</b>，按 {@code AiTools} 的格式常量反解；
 * 解析不了就<b>不出视图</b>（回落原始文本），绝不用当前库补一张表。
 *
 * <h2>与工具自身上限的关系</h2>
 * 工具回填模型的口径是"前 {@link AiTools#LIST_TASKS_MAX_ROWS} 条 + 共 N 条"（{@code AiTools}）。
 * 视图按同一口径裁行，因此卡面看到的行数 = 模型看到的行数，不会出现"卡面比模型多出几行"的
 * 两套事实；超出部分体现在 {@code meta.truncated{shownRows,totalRows}} 上（归档侧取当时的"共 N 条"）。
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

	/**
	 * 零任务时的空态文案（设计稿 §3.2 `empty`）：**不复用** fallback —— "暂无任务"与
	 * "任务列表无法以表格展示"是两件事，把前者读成后者会让空列表看起来像降级失败。
	 */
	public static final String EMPTY_TEXT = "i18n:resultview.listTasks.empty";

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
					"i18n:resultview.listTasks.fallback", "list_tasks", viewBudget(), tasks.size(), EMPTY_TEXT);
		}
		catch (RuntimeException ex) {
			log.warn("list_tasks 视图构造失败（本次不出 resultView，卡面走旧形态）：toolCallId={}", toolCallId, ex);
			return null;
		}
	}

	/**
	 * 带通道标记的构造：实时帧仍走服务层结构化数据（{@link #build(String, String, String)}），
	 * 历史归档改走<b>持久化结果原文</b>。
	 *
	 * <p><b>为什么归档不能查库</b>（BZ-33）：{@code /history} 每次刷新都为每条历史 {@code list_tasks}
	 * 重跑一次 {@code taskService.findAll()} —— 任务被改名/推进/删除后，归档卡面显示的是<b>今天</b>
	 * 的任务行，与同轮 assistant 文本、与当时的"共 N 条"告知全都对不上；顺带每次刷新还要全量载入
	 * 一遍任务表。归档时"当时的结构化数据源"已经不存在，唯一忠实的事实是那次调用的结果原文。
	 */
	@Override
	public ResultView build(String toolCallId, String toolName, String resultText, boolean historical) {
		if (!supports(toolName)) {
			return null;
		}
		return historical ? fromPersistedText(toolCallId, resultText) : build(toolCallId, toolName, resultText);
	}

	/**
	 * 归档投影：按 {@link AiTools#LIST_TASKS_ROW_PREFIX} 等格式常量反解当时的结果原文。
	 *
	 * <h2>失败姿态（宁可不出视图，也不查库）</h2>
	 * 原文为空、行格式不是当前格式（旧会话 / 被模型侧裁剪切断）⇒ 返回 {@code null}，
	 * 卡面回落到原文形态 —— 那是诚实的旧形态；用今天的库补一张表才是改写历史。
	 */
	private ResultView fromPersistedText(String toolCallId, String resultText) {
		String text = resultText == null ? "" : resultText.strip();
		if (text.isEmpty()) {
			return null;
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (String line : text.split("\n")) {
			String trimmed = line.strip();
			if (!trimmed.startsWith(AiTools.LIST_TASKS_ROW_PREFIX)) {
				continue;
			}
			Map<String, Object> row = parseRow(trimmed);
			if (row == null) {
				log.debug("list_tasks 历史结果的行格式无法解析（本轮不出视图，卡面走原文）：toolCallId={}",
						toolCallId);
				return null;
			}
			rows.add(row);
		}
		if (rows.isEmpty()) {
			// 零任务原文 = "当时确实没有任务"；其它形态一律不出视图
			return text.startsWith(AiTools.LIST_TASKS_EMPTY_TEXT)
					? ResultViews.table("rv-" + toolCallId, "i18n:resultview.listTasks.title", columns(),
							List.of(), "i18n:resultview.listTasks.fallback", "list_tasks", viewBudget(), 0,
							EMPTY_TEXT)
					: null;
		}
		return ResultViews.table("rv-" + toolCallId, "i18n:resultview.listTasks.title", columns(), rows,
				"i18n:resultview.listTasks.fallback", "list_tasks", viewBudget(),
				totalRowsOf(text, rows.size()), EMPTY_TEXT);
	}

	/** 一行结果原文 → 视图行：{@code - #7 [EXPORT] 标题 步骤:SELECT_DEFS 状态:ACTIVE}。 */
	private static Map<String, Object> parseRow(String line) {
		int prefixLength = AiTools.LIST_TASKS_ROW_PREFIX.length();
		int statusAt = line.lastIndexOf(AiTools.LIST_TASKS_STATUS_TOKEN);
		int stepAt = line.lastIndexOf(AiTools.LIST_TASKS_STEP_TOKEN);
		int typeStart = line.indexOf('[');
		int typeEnd = line.indexOf(']');
		int idEnd = line.indexOf(' ', prefixLength);
		if (statusAt < 0 || stepAt < 0 || statusAt <= stepAt
				|| typeStart < prefixLength || typeEnd < typeStart || idEnd < prefixLength || idEnd > typeStart
				|| stepAt <= typeEnd) {
			return null;
		}
		String id = line.substring(prefixLength, idEnd).strip();
		String type = line.substring(typeStart + 1, typeEnd).strip();
		String title = line.substring(typeEnd + 1, stepAt).strip();
		String step = line.substring(stepAt + AiTools.LIST_TASKS_STEP_TOKEN.length(), statusAt).strip();
		String status = line.substring(statusAt + AiTools.LIST_TASKS_STATUS_TOKEN.length()).strip();
		if (type.isEmpty() || step.isEmpty() || status.isEmpty()) {
			return null;
		}
		long taskId;
		try {
			taskId = Long.parseLong(id);
		}
		catch (NumberFormatException ex) {
			return null;
		}
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", taskId);
		row.put("type", type);
		row.put("title", title);
		row.put("step", step);
		row.put("status", status);
		return row;
	}

	/** 当时的"共 N 条"告知（截断三件套的口径必须与当时一致）；没有告知 ⇒ 以实际行数为准。 */
	private static int totalRowsOf(String text, int fallback) {
		int from = text.indexOf(AiTools.LIST_TASKS_TOTAL_PREFIX);
		if (from < 0) {
			return fallback;
		}
		from += AiTools.LIST_TASKS_TOTAL_PREFIX.length();
		int to = text.indexOf(AiTools.LIST_TASKS_TOTAL_SUFFIX, from);
		if (to < 0) {
			return fallback;
		}
		try {
			return Math.max(fallback, Integer.parseInt(text.substring(from, to).strip()));
		}
		catch (NumberFormatException ex) {
			return fallback;
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
