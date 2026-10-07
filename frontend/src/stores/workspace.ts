/**
 * 工作区契约 store（窄接口，AI ⇄ 业务之间唯一的通信层）。
 *
 * 三件事：
 * 1. 状态窄接口：buildContext()（注入每轮 chat/tool-result 的 Agent Context，严格 5 键）
 *    与 snapshot()（get_workspace_state 类工具的完整快照）；
 * 2. 页面能力注册表：registerPageHandler()（页面把能力反向暴露给 AI 工具，AI 侧不 import 视图）；
 * 3. ui_event 分发：把 AI 的动作落到与用户点击**同一套 store action**（deepseek 契约形态 + 版本号）。
 *
 * 裁剪性：本文件不 import AiPanel / ai store。移除 AI 栏后，业务页面照常工作。
 *
 * 后端对齐要点（核对 AiChatRequest.Context + AiContext#getContextKey）：
 * - context 只允许 page/taskType/step/taskId/extra 五个键；
 * - taskType 与 step **必须成对出现**，否则后端退回 page:* 口径，披露的工具子集就错了；
 * - step 取值必须与 @ToolScope 的标签一致（见 types/task.ts 的 TaskSteps）。
 */

import { defineStore } from 'pinia'
import router, { ROUTE_NAMES } from '@/router'
import { saveBlob } from '@/api/http'
import { tasksApi } from '@/api/tasks'
import { jobsApi } from '@/api/jobs'
import { useTaskStore } from '@/stores/task'
import { stepLabel } from '@/utils/format'
import {
  WORKSPACE_ACTION_LIMIT,
  WORKSPACE_CONTRACT_VERSION,
  type UiEvent,
  type WorkspaceActionRecord,
  type WorkspaceActionSource,
  type WorkspacePageId,
  type WorkspaceTopic
} from '@/types/tools'
import type { AiChatContext } from '@/types/ai'
import type { ImportMode, QueryCondition, TaskStatus, TaskType } from '@/types/task'
import type { PageHandler, PageHandlerName, UiEventResult, WorkspaceSnapshot } from '@/types/workspace'

/** 页面 id → 路由名。 */
const PAGE_ROUTES: Record<WorkspacePageId, string> = {
  tasks: ROUTE_NAMES.taskCenter,
  export: ROUTE_NAMES.exportWizard,
  import: ROUTE_NAMES.importWizard,
  definitions: ROUTE_NAMES.definitions,
  data: ROUTE_NAMES.dataBrowser
}

/** 页面 id 的中文名（回灌给模型的说明文本用）。 */
const PAGE_LABELS: Record<WorkspacePageId, string> = {
  tasks: '任务中心',
  export: '导出向导',
  import: '导入向导',
  definitions: '配置定义',
  data: '数据浏览'
}

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    /** 契约版本号：两侧按此判定兼容性 */
    contractVersion: WORKSPACE_CONTRACT_VERSION,
    /** 页面标签（ToolScope 的 page:* 口径：tasks / definitions / data；向导页由 taskType+step 接管） */
    page: 'tasks' as string,
    /** 路由级页面 id */
    pageId: 'tasks' as WorkspacePageId,
    /** 当前任务类型（向导页才有） */
    taskType: null as TaskType | null,
    /** 当前步骤 key（与 @ToolScope 标签一致） */
    step: null as string | null,
    /** 当前任务 id */
    taskId: null as number | null,
    /** 当前任务状态 */
    taskStatus: null as TaskStatus | null,
    /** 已选配置项编码 */
    selectedDefs: [] as string[],
    /** 各配置项的查询条件（AI 驱动设置与用户手设共用） */
    queryConditions: {} as Record<string, QueryCondition | unknown>,
    /** 导入模式 */
    importMode: null as ImportMode | null,
    /** 数据版本号：工作区状态每次变更自增，供 AI 判断是否需要重拉 */
    dataVersion: 0,
    /** 最近的工作区动作（契约事件形态；随下一轮 AI 请求注入上下文，见 buildContext） */
    recentActions: [] as WorkspaceActionRecord[]
  }),

  getters: {
    /** 后端 AiContext#getContextKey 的前端镜像（便于日志与断言）。 */
    contextKey(state): string {
      if (state.taskType && state.step) {
        return `task:${state.taskType}/${state.step}`
      }
      if (state.page) {
        return `page:${state.page}`
      }
      return '*'
    },
    /** 未进入任何向导时（任务中心/定义页/数据页）AI 只能拿到 page:* 口径的工具。 */
    inWizard(state): boolean {
      return state.taskType !== null && state.step !== null
    },
    /** 上下文摘要（AI 面板的上下文 chip 文案）。 */
    contextSummary(state): string {
      const pageText = PAGE_LABELS[state.pageId] ?? state.page
      const parts = [pageText]
      if (state.taskId !== null) {
        parts.push(`任务 #${state.taskId}`)
      }
      if (state.step) {
        parts.push(`步骤 ${stepLabel(state.step)}`)
      }
      if (state.selectedDefs.length > 0) {
        parts.push(`已选 ${state.selectedDefs.length} 个配置项`)
      }
      return parts.join(' · ')
    }
  },

  actions: {
    /** 进入某页（路由守卫/页面 onMounted 调用）。 */
    enterPage(init: Partial<WorkspaceSnapshot>): void {
      if (init.pageId !== undefined) {
        this.pageId = init.pageId
      }
      if (init.page !== undefined) {
        this.page = init.page
      } else if (init.pageId !== undefined) {
        this.page = init.pageId
      }
      if (init.taskType !== undefined) {
        this.taskType = init.taskType
      }
      if (init.step !== undefined) {
        this.step = init.step
      }
      if (init.taskId !== undefined) {
        this.taskId = init.taskId
      }
      if (init.taskStatus !== undefined) {
        this.taskStatus = init.taskStatus
      }
      if (init.selectedDefs !== undefined) {
        this.selectedDefs = [...init.selectedDefs]
      }
      if (init.queryConditions !== undefined) {
        this.queryConditions = { ...init.queryConditions }
      }
      if (init.importMode !== undefined) {
        this.importMode = init.importMode
      }
      this.touch(`进入${PAGE_LABELS[this.pageId] ?? this.page}`)
    },

    /** 回到任务中心口径（离开向导时清掉 task 维度，避免披露错误的工具子集）。 */
    leaveWizard(): void {
      this.taskType = null
      this.step = null
      this.taskId = null
      this.taskStatus = null
      this.selectedDefs = []
      this.queryConditions = {}
      this.importMode = null
      this.touch('离开向导，回到任务中心')
    },

    setStep(step: string | null): void {
      this.step = step
      this.touch(step ? `切换到步骤「${stepLabel(step)}」` : '退出当前步骤')
    },

    setTaskId(taskId: number | null, taskType?: TaskType | null, status?: TaskStatus | null): void {
      this.taskId = taskId
      this.taskType = taskType === undefined ? this.taskType : taskType
      this.taskStatus = status === undefined ? this.taskStatus : status
      this.touch(taskId === null ? '解除任务绑定' : `绑定任务 #${taskId}`)
    },

    setSelectedDefs(codes: string[], mode: 'REPLACE' | 'ADD' = 'REPLACE'): void {
      if (mode === 'ADD') {
        this.selectedDefs = Array.from(new Set([...this.selectedDefs, ...codes]))
      } else {
        this.selectedDefs = [...codes]
      }
      this.touch(`已选择配置项：${this.selectedDefs.join('、') || '（空）'}`)
    },

    setQueryConditions(defCode: string, conditions: QueryCondition | unknown): void {
      this.queryConditions = { ...this.queryConditions, [defCode]: conditions }
      this.touch(`设置 ${defCode} 的查询条件`)
    },

    setImportMode(mode: ImportMode | null): void {
      this.importMode = mode
      this.touch(`导入模式设为 ${mode === null ? '未设置' : mode === 'MERGE' ? '增量合并' : '整体替换'}`)
    },

    /** 纯版本号自增（消息密集的变更用，例如表格连续编辑，避免动作记录被刷爆）。 */
    bumpVersion(): void {
      this.dataVersion += 1
    },

    /**
     * 记录一次工作区动作（契约联动的心脏）：
     * 1. 数据版本自增；
     * 2. 记入 recentActions（随下一轮 AI 请求注入 `context.extra.recentActions`）；
     * 3. 广播 `workspace_changed` 契约事件给 AI 面板（deepseek 蓝本事件形态 + 版本号）。
     *
     * @param summary 中文动作摘要（同时是给模型看的一句话）
     * @param source  界面（用户手点）或 AI（经契约层下发）
     */
    touch(summary: string, source: WorkspaceActionSource = '界面'): number {
      this.dataVersion += 1
      const record: WorkspaceActionRecord = {
        version: this.dataVersion,
        summary,
        at: Date.now(),
        source
      }
      this.recentActions = [...this.recentActions, record].slice(-WORKSPACE_ACTION_LIMIT)
      emitWorkspaceEvent('workspace_changed', record)
      return this.dataVersion
    },

    /** 清空待同步动作（AI 发消息带上上下文后调用：动作只注入一轮，不重复累积）。 */
    clearRecentActions(): void {
      this.recentActions = []
    },

    /** 注入到每轮 chat/tool-result 的 Agent Context（严格 5 键，多余信息进 extra）。 */
    buildContext(): AiChatContext {
      return {
        page: this.page,
        taskType: this.taskType,
        step: this.step,
        taskId: this.taskId,
        extra: {
          pageId: this.pageId,
          selectedDefs: [...this.selectedDefs],
          importMode: this.importMode,
          dataVersion: this.dataVersion,
          contractVersion: this.contractVersion,
          // 契约联动：把"用户刚刚手点了什么"一并交给模型（只带最近若干条）
          recentActions: this.recentActions.map((record) => `${record.summary}（来源：${record.source}）`)
        }
      }
    },

    /** 完整快照（供 get_workspace_state 口径的展示与诊断）。 */
    snapshot(): WorkspaceSnapshot {
      return {
        page: this.page,
        pageId: this.pageId,
        taskType: this.taskType,
        step: this.step,
        taskId: this.taskId,
        taskStatus: this.taskStatus,
        selectedDefs: [...this.selectedDefs],
        queryConditions: JSON.parse(JSON.stringify(this.queryConditions)) as Record<string, unknown>,
        importMode: this.importMode,
        dataVersion: this.dataVersion
      }
    }
  }
})

// ── 页面能力注册表（非响应式，页面挂载/卸载时登记） ────────────────────────────

const pageHandlers = new Map<string, PageHandler>()

/** 页面把能力反向暴露给 AI（AI 侧不 import 任何视图）。 */
export function registerPageHandler(name: PageHandlerName | string, handler: PageHandler): void {
  pageHandlers.set(name, handler)
}

export function unregisterPageHandler(name: PageHandlerName | string): void {
  pageHandlers.delete(name)
}

export function getPageHandler(name: PageHandlerName | string): PageHandler | null {
  return pageHandlers.get(name) ?? null
}

// ── 工作区事件总线（业务 → AI 的通知；AI 面板订阅，工作区不反向依赖） ──────────

type Subscriber = (payload: unknown) => void
const subscribers = new Map<WorkspaceTopic, Subscriber[]>()

export function onWorkspaceEvent(topic: WorkspaceTopic, subscriber: Subscriber): () => void {
  const list = subscribers.get(topic) ?? []
  list.push(subscriber)
  subscribers.set(topic, list)
  return () => {
    const current = subscribers.get(topic)
    if (!current) {
      return
    }
    const index = current.indexOf(subscriber)
    if (index >= 0) {
      current.splice(index, 1)
    }
  }
}

export function emitWorkspaceEvent(topic: WorkspaceTopic, payload?: unknown): void {
  for (const subscriber of (subscribers.get(topic) ?? []).slice()) {
    try {
      subscriber(payload)
    } catch (error) {
      console.warn('[workspace] 订阅者处理失败', topic, error)
    }
  }
}

/** 业务动作完成后通知 AI（AI 面板据此刷新上下文 chip）。 */
export function notifyWorkspaceChanged(summary: string): void {
  const workspace = useWorkspaceStore()
  workspace.touch(summary, 'AI')
}

// ── ui_event 统一分发（AI → 业务，与用户点击同一套 action） ───────────────────

function ok(message: string): UiEventResult {
  return { handled: true, message }
}

function fail(message: string): UiEventResult {
  return { handled: false, message }
}

/**
 * 分发 AI 侧发来的 ui_event。
 *
 * 每个分支都落到**与用户手点相同**的 API/action 上，因此 AI 驱动与手工操作
 * 的后端副作用完全一致（不新建"AI 专用后门"）。
 *
 * 契约联动：分发结果会同时 (1) 记入工作区动作（source=AI，随下一轮请求回到模型），
 * (2) 经 `ui_event_result` 事件广播给 AI 面板展示。
 */
export async function dispatchUiEvent(event: UiEvent): Promise<UiEventResult> {
  const result = await runUiEvent(event)
  const workspace = useWorkspaceStore()
  workspace.touch(result.message, 'AI')
  emitWorkspaceEvent('ui_event_result', { event: event.type, ...result })
  return result
}

async function runUiEvent(event: UiEvent): Promise<UiEventResult> {
  const workspace = useWorkspaceStore()
  const taskStore = useTaskStore()
  try {
    switch (event.type) {
      case 'open_page': {
        const routeName = PAGE_ROUTES[event.page]
        await router.push({ name: routeName })
        return ok(`已打开${PAGE_LABELS[event.page]}`)
      }
      case 'restore_task': {
        const target = taskStore.resolveWizardRoute(event.taskId)
        await router.push(target)
        const isImport = target.name === ROUTE_NAMES.importWizard
        return ok(`已恢复到任务 #${event.taskId} 的向导（${isImport ? '导入' : '导出'}）`)
      }
      case 'goto_step': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法跳转步骤')
        }
        await tasksApi.goToStep(taskId, event.step)
        workspace.setStep(event.step)
        await taskStore.refreshCurrent()
        return ok(`已跳转到步骤 ${event.step}`)
      }
      case 'select_defs': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法选择配置项')
        }
        await tasksApi.selectDefs(taskId, event.defCodes)
        workspace.setSelectedDefs(event.defCodes)
        await taskStore.refreshCurrent()
        return ok(`已选择配置项：${event.defCodes.join('、') || '（空）'}`)
      }
      case 'set_conditions': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法设置查询条件')
        }
        await tasksApi.setCondition(taskId, event.defCode, event.conditions)
        workspace.setQueryConditions(event.defCode, event.conditions)
        return ok(`已设置 ${event.defCode} 的查询条件`)
      }
      case 'set_import_mode': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法设置导入模式')
        }
        await tasksApi.setImportMode(taskId, event.mode)
        workspace.setImportMode(event.mode)
        return ok(`导入模式已设为 ${event.mode === 'MERGE' ? '增量合并' : '整体替换'}`)
      }
      case 'run_job': {
        const job = await jobsApi.createJob(event.taskId, event.jobType)
        await taskStore.refreshCurrent()
        if (event.jobType === 'PUBLISH') {
          // Q4：发布冲突是**异步形态**（恒 201），必须等作业终态后查 issues
          void taskStore.watchPublishOutcome(job.id ?? 0)
          return ok(`发布作业已启动（作业 #${job.id}），冲突会在完成后以作业失败 + 问题清单呈现`)
        }
        return ok(`作业已启动：${event.jobType}（作业 #${job.id}）`)
      }
      case 'cancel_job': {
        const outcome = await taskStore.cancelJob(event.jobId)
        return outcome.handled ? ok(outcome.message) : fail(outcome.message)
      }
      case 'export_started': {
        await router.push({ name: ROUTE_NAMES.exportWizard, query: { taskId: String(event.taskId) } })
        return ok(`已进入导出向导（任务 #${event.taskId}）`)
      }
      case 'import_batch': {
        await router.push({ name: ROUTE_NAMES.importWizard, query: { taskId: String(event.batchId) } })
        return ok(`已进入导入向导（任务 #${event.batchId}）`)
      }
      case 'import_action': {
        await router.push({ name: ROUTE_NAMES.importWizard })
        return ok(`已打开导入向导，请执行：${event.action}`)
      }
      case 'download': {
        const response = await fetch(event.url)
        if (!response.ok) {
          return fail(`下载失败：HTTP ${response.status}`)
        }
        saveBlob(await response.blob(), event.filename ?? 'download')
        return ok(`已触发下载 ${event.filename ?? event.url}`)
      }
      case 'refresh_defs': {
        notifyWorkspaceChanged('AI 请求刷新配置定义')
        return ok('已通知配置定义页刷新')
      }
      case 'refresh_data': {
        notifyWorkspaceChanged('AI 请求刷新数据浏览')
        return ok(`已通知数据浏览页刷新${event.defCode ? `（${event.defCode}）` : ''}`)
      }
      case 'refresh_tasks': {
        await taskStore.load()
        return ok('任务列表已刷新')
      }
      default: {
        const exhaustive: never = event
        return fail(`未支持的 ui_event：${JSON.stringify(exhaustive)}`)
      }
    }
  } catch (error) {
    return fail(`ui_event 处理失败：${error instanceof Error ? error.message : String(error)}`)
  }
}
