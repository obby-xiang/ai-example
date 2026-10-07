/**
 * 任务与任务条目契约。
 *
 * 核对源：backend/.../task/entity/Task.java、TaskItem.java、TaskFile.java
 *         task/controller/TaskController.java、TaskSummary.java
 */

/** 任务类型（Task.TaskType）。 */
export type TaskType = 'EXPORT' | 'IMPORT'

/** 任务状态（Task.TaskStatus）。 */
export type TaskStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'FAILED'

/**
 * 任务条目状态（TaskItem.status 为自由字符串，取值见后端注释）。
 * PENDING / READY / CHECKING / CHECKED / IMPORTING / IMPORTED / PUBLISHING / PUBLISHED / FAILED
 */
export type TaskItemStatus =
  | 'PENDING'
  | 'READY'
  | 'CHECKING'
  | 'CHECKED'
  | 'IMPORTING'
  | 'IMPORTED'
  | 'PUBLISHING'
  | 'PUBLISHED'
  | 'FAILED'

/** 导入模式（settingsJson.importMode）。 */
export type ImportMode = 'MERGE' | 'REPLACE'

/**
 * 任务步骤 key（与后端 @ToolScope 的 task:<type>/<step> 标签一致）。
 * EXPORT: SELECT_DEFS → QUERY_COND → EXPORT（完成态 DONE）
 * IMPORT: UPLOAD → PRECHECK → IMPORT → PUBLISH（完成态 DONE）
 */
export const TaskSteps = {
  EXPORT: ['SELECT_DEFS', 'QUERY_COND', 'EXPORT'],
  IMPORT: ['UPLOAD', 'PRECHECK', 'IMPORT', 'PUBLISH'],
  DONE: 'DONE'
} as const

export type ExportStep = (typeof TaskSteps.EXPORT)[number]
export type ImportStep = (typeof TaskSteps.IMPORT)[number]
/** 后端 currentStep 为自由字符串，故联合类型保留 string 分支 */
export type TaskStep = ExportStep | ImportStep | 'DONE' | (string & {})

/** 查询条件（后端 QueryCondition 的 JSON 形态，存于 TaskItem.conditionJson）。 */
export interface QueryCondition {
  /** 条件组：组间 AND，组内按 logic 组合 */
  groups?: ConditionGroup[]
}

export interface ConditionGroup {
  /** AND / OR */
  logic?: 'AND' | 'OR'
  conditions?: ConditionItem[]
}

export interface ConditionItem {
  field: string
  /** 操作符：EQ/NE/GT/GE/LT/LE/CONTAINS/IN/BETWEEN 等，最终以后端 ConditionEvaluator 为准 */
  op: string
  value?: unknown
  value2?: unknown
}

/** 任务条目（一个任务选中的配置项）。 */
export interface TaskItem {
  id?: number
  taskId: number
  defCode: string
  sortOrder: number
  /** 查询条件的 JSON 字符串（后端为 String/CLOB） */
  conditionJson?: string | null
  status: TaskItemStatus | string
}

/** 任务本体。 */
export interface Task {
  id?: number
  type: TaskType
  title: string
  /** 当前步骤 key，如 SELECT_DEFS / UPLOAD / DONE */
  currentStep: TaskStep
  status: TaskStatus
  /** 任务级设置的 JSON 字符串（含 importMode） */
  settingsJson?: string | null
  /** 乐观锁版本（@Version） */
  version?: number
  items: TaskItem[]
  createdAt?: string
  updatedAt?: string
}

/** 任务文件（上传件 / 导出结果 / 模板）。 */
export interface TaskFile {
  id?: number
  taskId: number
  defCode: string
  /** TEMPLATE / UPLOAD / EXPORT */
  fileType: 'TEMPLATE' | 'UPLOAD' | 'EXPORT' | string
  storagePath: string
  originalPath?: string | null
  fileName?: string | null
  rowCount?: number | null
  version?: number
  createdAt?: string
  updatedAt?: string
}

/** 任务列表摘要（后端 record TaskSummary(Task task, int itemCount, int fileCount, Job latestJob)）。 */
export interface TaskSummary {
  task: Task
  itemCount: number
  fileCount: number
  /** 最新作业（可能为空数组时的 null） */
  latestJob: import('./job').Job | null
}

/** 任务列表查询参数（后端 TaskController /api/tasks）。 */
export interface TaskListQuery {
  type?: TaskType
  status?: TaskStatus
  keyword?: string
  page?: number
  size?: number
}

/** 任务详情概览（/api/tasks/{id}/overview 的 data）。 */
export interface TaskOverview {
  task: Task
  jobs: import('./job').Job[]
  files: TaskFile[]
}

/** 读取任务级设置里的导入模式。 */
export function readImportMode(task: Pick<Task, 'settingsJson'>): ImportMode | null {
  if (!task.settingsJson) {
    return null
  }
  try {
    const parsed = JSON.parse(task.settingsJson) as { importMode?: string }
    return parsed.importMode === 'MERGE' || parsed.importMode === 'REPLACE' ? parsed.importMode : null
  } catch {
    return null
  }
}
