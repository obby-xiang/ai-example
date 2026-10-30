package com.example.configmgr.ai.tool;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskSteps;

import java.util.List;
import java.util.Optional;

/**
 * AI 工具名的<b>唯一注册定义</b>（后端所有消费方共用这一份字面量）。
 *
 * <h2>为什么要有它</h2>
 * 同一个工具名此前至少抄在四处：{@code @Tool(name=…)}、{@code resources/ai/tools.yaml} 的
 * {@code toolKey}、闸门/预检里的常量（{@code ToolPreflightService.TOOL_* }、
 * {@code ToolTargetResolver.TOOL_* }、{@code ToolRegistry.TOOL_START_EXPORT}、
 * {@code RunContextRefresher.TOOL_*}）、{@code GenerativeFormRules.TOOL_NAME} 与各前端工具守卫的
 * {@code SUPPORTED} 集合。副本漂移时，守卫按旧名放行/拦截一个已改名的工具 —— 不报错，
 * 只表现为"某些调用静默不生效"。
 *
 * <p>注解要求编译期常量，故这里用 {@code public static final String}
 * （常量变量可合法出现在 {@code @Tool(name=…)} 里）；YAML 的 {@code toolKey} 无法引用 Java 常量，
 * 但 {@code ToolRegistry#applyOverrides} 会拒绝指向未注册工具的 toolKey —— 两侧因此仍收敛到本表。
 *
 * <h2>范围</h2>
 * 本表登记 {@code AiTools} 的业务/向导/表单工具（AI 协议面）。表格与 UI 原语的工具名
 * 由各自的开关表（{@code SheetSwitches}）与守卫持有，本批不迁（见批次报告"剩余副本"）。
 */
public final class AiToolNames {

	public static final String LIST_CONFIG_DEFS = "list_config_defs";

	public static final String GET_CONFIG_DEF = "get_config_def";

	public static final String LIST_TASKS = "list_tasks";

	public static final String GET_WORKSPACE_STATE = "get_workspace_state";

	public static final String CHECK_JOB_STATUS = "check_job_status";

	public static final String GET_ROW_COUNT = "get_row_count";

	public static final String CREATE_TASK = "create_task";

	public static final String START_EXPORT = "start_export";

	public static final String START_PRECHECK = "start_precheck";

	public static final String START_IMPORT = "start_import";

	public static final String START_PUBLISH = "start_publish";

	public static final String OPEN_EXPORT_FILE_EDITOR = "open_export_file_editor";

	public static final String DOWNLOAD_EXPORT_FILES = "download_export_files";

	public static final String NAVIGATE_TO = "navigate_to";

	public static final String SELECT_DEFINITIONS = "select_definitions";

	public static final String SET_CONDITION = "set_condition";

	public static final String CONFIRM_STEP = "confirm_step";

	public static final String GENERATIVE_FORM = "generative_form";

	// ── AI 表格工具（9）：名字同时被 {@code SheetSwitches} 的披露闸引用 ──

	public static final String READ_SHEET_SUMMARY = "read_sheet_summary";

	public static final String AGGREGATE_SHEET = "aggregate_sheet";

	public static final String INSPECT_COLUMNS = "inspect_columns";

	public static final String REVIEW_CELLS = "review_cells";

	public static final String FIND_ROWS = "find_rows";

	public static final String NORMALIZE_COLUMN = "normalize_column";

	public static final String REPLACE_VALUES = "replace_values";

	public static final String UPDATE_ROWS = "update_rows";

	public static final String UNDO_LAST_SHEET_EDIT = "undo_last_sheet_edit";

	// ── UI 通用原语（7）：名字同时被 {@code UiPrimitiveGuard} 的前缀判据覆盖 ──

	public static final String UI_SNAPSHOT = "ui_snapshot";

	public static final String UI_CLICK = "ui_click";

	public static final String UI_FILL = "ui_fill";

	public static final String UI_SELECT = "ui_select";

	public static final String UI_SCROLL = "ui_scroll";

	public static final String UI_HIGHLIGHT = "ui_highlight";

	public static final String UI_REQUEST_UPLOAD = "ui_request_upload";

	/** 全部业务工具名（顺序 = {@code AiTools} 的声明顺序，便于对照）。 */
	public static final List<String> ALL = List.of(LIST_CONFIG_DEFS, GET_CONFIG_DEF, LIST_TASKS, GET_WORKSPACE_STATE,
			CHECK_JOB_STATUS, GET_ROW_COUNT, CREATE_TASK, START_EXPORT, START_PRECHECK, START_IMPORT, START_PUBLISH,
			OPEN_EXPORT_FILE_EDITOR, DOWNLOAD_EXPORT_FILES, NAVIGATE_TO, SELECT_DEFINITIONS, SET_CONDITION,
			CONFIRM_STEP, GENERATIVE_FORM, READ_SHEET_SUMMARY, AGGREGATE_SHEET, INSPECT_COLUMNS, REVIEW_CELLS,
			FIND_ROWS, NORMALIZE_COLUMN, REPLACE_VALUES, UPDATE_ROWS, UNDO_LAST_SHEET_EDIT, UI_SNAPSHOT, UI_CLICK,
			UI_FILL, UI_SELECT, UI_SCROLL, UI_HIGHLIGHT, UI_REQUEST_UPLOAD);

	/**
	 * 披露标签里的<b>页面/任务类型前缀</b>（{@code task:<type>}）。
	 *
	 * <p>任务类型的字面量只有这里一份：类型名等于 {@code Task.TaskType} 的枚举名，但注解要求编译期常量，
	 * 不能写 {@code Task.TaskType.EXPORT.name()}；故落成常量并由 {@link #SCOPE_*} 组合。
	 */
	public static final String SCOPE_TASK_EXPORT = "task:EXPORT";

	public static final String SCOPE_TASK_IMPORT = "task:IMPORT";

	/** 步骤级披露标签（{@code task:<type>/<step>}）：步骤名取自 {@link TaskSteps}，不另抄字面量。 */
	public static final String SCOPE_EXPORT_SELECT_DEFS = SCOPE_TASK_EXPORT + "/" + TaskSteps.SELECT_DEFS;

	public static final String SCOPE_EXPORT_QUERY_COND = SCOPE_TASK_EXPORT + "/" + TaskSteps.QUERY_COND;

	public static final String SCOPE_EXPORT_EXECUTION = SCOPE_TASK_EXPORT + "/" + TaskSteps.EXPORT;

	public static final String SCOPE_IMPORT_UPLOAD = SCOPE_TASK_IMPORT + "/" + TaskSteps.UPLOAD;

	public static final String SCOPE_IMPORT_PRECHECK = SCOPE_TASK_IMPORT + "/" + TaskSteps.PRECHECK;

	public static final String SCOPE_IMPORT_EXECUTION = SCOPE_TASK_IMPORT + "/" + TaskSteps.IMPORT;

	public static final String SCOPE_IMPORT_PUBLISH = SCOPE_TASK_IMPORT + "/" + TaskSteps.PUBLISH;

	/**
	 * 进确认门、发作业的工具（四个 {@code start_*}）→ 作业类型 / 任务类型。
	 *
	 * <p>这份表此前分叉在 {@code ToolPreflightService#jobTypeOf}、{@code ToolTargetResolver#JOB_TOOLS}
	 * 与 {@code ToolPreflightService#expectedTaskType} 三处；现在只有这里一份，预检、目标校验、
	 * 资源锁共用。
	 */
	public enum ConfirmTool {
		START_EXPORT(AiToolNames.START_EXPORT, Job.JobType.EXPORT, Task.TaskType.EXPORT),
		START_PRECHECK(AiToolNames.START_PRECHECK, Job.JobType.PRECHECK, Task.TaskType.IMPORT),
		START_IMPORT(AiToolNames.START_IMPORT, Job.JobType.IMPORT, Task.TaskType.IMPORT),
		START_PUBLISH(AiToolNames.START_PUBLISH, Job.JobType.PUBLISH, Task.TaskType.IMPORT);

		private final String toolName;

		private final Job.JobType jobType;

		private final Task.TaskType taskType;

		ConfirmTool(String toolName, Job.JobType jobType, Task.TaskType taskType) {
			this.toolName = toolName;
			this.jobType = jobType;
			this.taskType = taskType;
		}

		public String toolName() {
			return this.toolName;
		}

		public Job.JobType jobType() {
			return this.jobType;
		}

		/** 该作业类型要求任务本身是哪一类（导出任务发 EXPORT，其余都发在导入任务上）。 */
		public Task.TaskType taskType() {
			return this.taskType;
		}

		/** 工具名 → 登记项；非作业发起工具返回 empty。 */
		public static Optional<ConfirmTool> of(String toolName) {
			if (toolName == null || toolName.isBlank()) {
				return Optional.empty();
			}
			for (ConfirmTool tool : values()) {
				if (tool.toolName.equals(toolName)) {
					return Optional.of(tool);
				}
			}
			return Optional.empty();
		}
	}

	private AiToolNames() {
	}

	/** 是否登记在册（null/空白 = false）。 */
	public static boolean isKnown(String toolName) {
		return toolName != null && !toolName.isBlank() && ALL.contains(toolName);
	}
}
