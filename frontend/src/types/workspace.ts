/**
 * 工作区契约（窄接口）：AI 侧只能看到这里定义的东西，业务视图不得被 AI 直接触碰。
 *
 * 形态来源：kimi-k3 `stores/workspace.js`（buildContext / registerPageHandler）+
 *          deepseek-v4-pro `utils/workspaceBus.js`、`workspaceContract.js`（ui_event + 版本号）。
 * 字段名一律对齐后端 AiChatRequest.Context（page/taskType/step/taskId/extra）。
 */

import type { AiChatContext } from './ai'
import type { ImportMode, QueryCondition, TaskStatus, TaskStep, TaskType } from './task'
import type { WorkspacePageId, UiEvent, WorkspaceTopic } from './tools'

/** 工作区内可注册的页面能力名（AI 经页面向导执行动作时按名取 handler）。 */
export type PageHandlerName =
  | 'export.selectDefs'
  | 'export.setConditions'
  | 'export.start'
  | 'export.nextStep'
  | 'import.selectDefs'
  | 'import.upload'
  | 'import.startPrecheck'
  | 'import.startImport'
  | 'import.startPublish'
  | 'import.nextStep'
  | 'data.refresh'

/** 页面能力 handler：入参由各页自定，返回说明文本（作为工具结果回灌）。 */
export type PageHandler = (payload: Record<string, unknown>) => unknown | Promise<unknown>

/** 工作区状态快照（AI 侧可读的摘要，raw 化前）。 */
export interface WorkspaceSnapshot {
  /** 当前页面标签（ToolScope 的 page:* 口径） */
  page: string
  /** 页面 id（路由级） */
  pageId: WorkspacePageId
  /** 任务类型 */
  taskType: TaskType | null
  /** 当前步骤 key */
  step: TaskStep | null
  /** 当前任务 id */
  taskId: number | null
  /** 当前任务状态 */
  taskStatus: TaskStatus | null
  /** 任务已选配置项编码 */
  selectedDefs: string[]
  /** 各配置项的查询条件 */
  queryConditions: Record<string, QueryCondition | unknown>
  /** 导入模式 */
  importMode: ImportMode | null
  /** 数据版本号（工作区状态变化的单调计数，供 AI 判断是否要重新拉取） */
  dataVersion: number
}

/** 契约层入口的窄接口（AI 面板只依赖这些）。 */
export interface WorkspaceContract {
  /** 注入到每次 chat / tool-result 请求的 Agent Context（严格 5 键） */
  buildContext(): AiChatContext
  /** 供 get_workspace_state 类工具拉取的完整快照 */
  snapshot(): WorkspaceSnapshot
  /** 用户/页面进入某页时更新上下文 */
  enterPage(init: Partial<WorkspaceSnapshot>): void
  /** ui_event 统一分发（与用户点击调用同一 store action） */
  dispatch(event: UiEvent): Promise<UiEventResult>
}

/** ui_event 分发结果（AI 侧据此决定回灌文案）。 */
export interface UiEventResult {
  handled: boolean
  /** 说明文本：'已打开导出向导' / '当前页面不支持该动作' 等 */
  message: string
}

/** 工作区总线订阅者（AI 面板订阅 workspace_changed 用）。 */
export type WorkspaceSubscriber = (payload: unknown) => void

/** 总线订阅句柄。 */
export interface WorkspaceSubscription {
  topic: WorkspaceTopic
  unsubscribe: () => void
}
