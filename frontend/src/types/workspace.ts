/**
 * 工作区契约（窄接口）：AI 侧只能看到这里定义的东西，业务视图不得被 AI 直接触碰。
 *
 * 形态来源：kimi-k3 `stores/workspace.js`（buildContext / registerPageHandler）+
 *          deepseek-v4-pro `utils/workspaceBus.js`、`workspaceContract.js`（ui_event + 版本号）。
 * 字段名一律对齐后端 AiChatRequest.Context（page/taskType/step/taskId/taskStatus/extra）。
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
  /** AI 可视可操作专项：上传交接把用户手势选到的文件交回页面既有上传链（永不传路径）。 */
  | 'import.receiveFile'
  /**
   * 批 D · D3（问题 6）：AI 触发作业后由页面把作业绑进自己的跟踪面（入参 taskId/jobId）。
   * 导出侧 = 导出作业，导入侧 = 预检查 / 导入作业（页面按 jobType 落到各自的跟踪位）。
   */
  | 'export.syncJob'
  | 'import.syncJob'

/**
 * 一次前端工具执行的**存活标记**（由 `utils/frontend-tools` 的 `runWithBudget` 持有，
 * 停止时由 `abandonInFlightFrontendTools()` 一并作废）。
 *
 * <p>为什么需要：预算到点后 `executeFrontendTool` 已经回灌"超时"，但被抛弃的异步续体还在跑 ——
 * 它若继续写工作区（`setStep` / `setSelectedDefs` / `setQueryConditions` / `setImportMode` /
 * `touch` / `ui_event_result`）或推进页面步骤，面板就会出现"已经超时的动作又报了一次同步"，
 * 下一轮请求也会带着一条模型并未确认的动作。用户点「停止」是同一类事实（本轮已放弃），
 * 故 `stop()` 也把在途工具的标记置为作废。
 *
 * <p>传播面：契约层 `dispatchUiEvent` / `runUiEvent`、页面能力 handler（`PageHandler` 第二参数）
 * 与登记执行器（`FrontendToolExecutor` 第二参数）都读它。缺省（页面手点 / 单测直调）不传 ⇒
 * 行为与改动前完全一致；不新增任何跨进程协议。
 */
export interface UiEventLifetime {
  abandoned: boolean
}

/** 页面能力 handler：入参由各页自定，返回说明文本（作为工具结果回灌）。 */
export type PageHandler = (
  payload: Record<string, unknown>,
  lifetime?: UiEventLifetime
) => unknown | Promise<unknown>

/**
 * 页面级上下文（批次5A 的「声明即全集」口径）：这些字段是「当前任务 / 当前页」的事实，
 * 进入某页时**整体声明** —— 没声明的一律回中性值，跨页残留会让下一轮上下文报错页。
 *
 * @see WorkspaceContract.enterPage
 */
export type WorkspacePageContext = Pick<
  WorkspaceSnapshot,
  'taskType' | 'step' | 'taskId' | 'taskStatus' | 'selectedDefs' | 'queryConditions' | 'importMode'
>

/** 工作区状态快照（AI 侧可读的摘要，raw 化前）。 */
export interface WorkspaceSnapshot {
  /**
   * 当前页面标签（ToolScope 的 page:* 口径）：上报给后端的页面维度，
   * **实现上与 `pageId` 同值**（路由页 id 的镜像，见 `stores/workspace.ts#enterPage` 的回退）；
   * 合法值域 = tasks / export / import / definitions / data。
   *
   * <p><b>设置类页面（AI 设置 / AI 反馈）不上报 `settings`</b>（批次5A 显式记录）：后端 page 是
   * **闭集** —— `ContextFieldGate#page` 只放行 `ContextBuilder.PAGE_LABELS` 里的五个 id，未知值会被
   * 降级成 `unknown`（每轮一条降级日志、提示词渲染成「未知页面」）。故这两页按「不在工作区内」处理：
   * 复位到默认页 `tasks` + 任务态中性，**不虚增页面 id**（要真加 `settings`，须后端白名单、
   * `PAGE_LABELS` 与 ToolScope 同批改，并同步本文件与 `stores/workspace.ts` 的映射表）。
   */
  page: string
  /**
   * 页面 id（路由级）；AI 侧判定"在哪个页面"请用本字段（`page` 只是它的镜像）。
   * 设置类页面同样复位到默认页 `tasks`（理由见 {@link WorkspaceSnapshot.page}）。
   */
  pageId: WorkspacePageId
  /** 任务类型 */
  taskType: TaskType | null
  /** 当前步骤 key */
  step: TaskStep | null
  /** 当前任务 id */
  taskId: number | null
  /** 当前任务状态（**每轮顶层上报**：后端披露面据此做终态收窄） */
  taskStatus: TaskStatus | null
  /** 任务已选配置项编码 */
  selectedDefs: string[]
  /** 各配置项的查询条件 */
  queryConditions: Record<string, QueryCondition | unknown>
  /** 导入模式 */
  importMode: ImportMode | null
  /**
   * 数据版本号（工作区状态变化的单调计数，供 AI 判断是否要重新拉取）。
   * **时间线字段**：进页复位不重置它（见 {@link WorkspaceContract.enterPage}）。
   */
  dataVersion: number
}

/** 契约层入口的窄接口（AI 面板只依赖这些）。 */
export interface WorkspaceContract {
  /** 注入到每次 chat / tool-result 请求的 Agent Context（六个顶层键，与后端 Context 一致） */
  buildContext(): AiChatContext
  /** 供 get_workspace_state 类工具拉取的完整快照 */
  snapshot(): WorkspaceSnapshot
  /**
   * 进入某页：**声明本页的页面级上下文（声明即全集）**。
   *
   * <p>带页面身份（`pageId` / `page`）时，{@link WorkspacePageContext} 里**没声明**的字段一律回中性值
   * （`null` / `[]` / `{}`）—— 上一页向导的 `taskType + step`、旧 `taskId`、终态 `taskStatus`、
   * 选中集与查询条件都不得跨页残留（会让下一轮上下文报错页、让只读守卫误拒其它页的写事件）。
   * 不带页面身份的调用是**增量更新**（只写显式给出的字段）。
   *
   * <p>**不扩大清空**：`dataVersion` / `recentActions`（时间线）与 `contractVersion` 一律保留；
   * 本页显式声明的值（例：数据浏览页正在浏览的 `selectedDefs`）原样保留。
   */
  enterPage(init: Partial<WorkspaceSnapshot>): void
  /**
   * 服务端任务状态同步（作业终态后由页面刷新快照写回）：驱动后端披露面的终态收窄。
   * 不记 `recentActions` —— 这是事实同步，不是用户/AI 的动作。
   */
  setTaskStatus(status: TaskStatus | null): void
  /** ui_event 统一分发（与用户点击调用同一 store action） */
  dispatch(event: UiEvent): Promise<UiEventResult>
}

/**
 * ui_event 分发结果（AI 侧据此决定回灌文案）。
 *
 * <p>面板侧口径（批次5A）：`handled === true` 才算「工作区已同步」（广播 `workspace_changed`）；
 * `handled === false` 只经 `ui_event_result` 进面板回执位，并作为「被拒」留在工作区时间线里。
 */
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
