/**
 * 工作区契约 store（窄接口，AI ⇄ 业务之间唯一的通信层）。
 *
 * 三件事：
 * 1. 状态窄接口：buildContext()（注入每轮 chat/tool-result 的 Agent Context，键集与后端
 *    `AiChatRequest.Context` 一致：page/taskType/step/taskId/taskStatus/extra）与
 *    snapshot()（get_workspace_state 类工具的完整快照）；
 * 2. 页面能力注册表：registerPageHandler()（页面把能力反向暴露给 AI 工具，AI 侧不 import 视图）；
 * 3. ui_event 分发：把 AI 的动作落到与用户点击**同一套 store action**（deepseek 契约形态 + 版本号）。
 *
 * 裁剪性：本文件不 import AiPanel / ai store。移除 AI 栏后，业务页面照常工作。
 *
 * 后端对齐要点（核对 AiChatRequest.Context + AiContext#getContextKey）：
 * - context 只允许 page/taskType/step/taskId/taskStatus/extra 这六个键（白名单外的键会被
 *   `ContextFieldGate` 丢弃 ⇒ 静默失效，所以有消费面的字段一律顶层）；
 * - taskType 与 step **必须成对出现**，否则后端退回 page:* 口径，披露的工具子集就错了；
 * - step 取值必须与 @ToolScope 的标签一致（见 types/task.ts 的 TaskSteps）；
 * - taskStatus 有**披露面消费点**（后端终态收窄：COMPLETED/CANCELLED 任务不再披露
 *   `start_export`），故它必须随每轮上报 —— 放在 extra 里等于没上报。
 */

import { defineStore } from 'pinia'
import router, { ROUTE_NAMES } from '@/router'
import { saveBlob } from '@/api/http'
import { HTTP_TIMEOUT_MS } from '@/utils/time-constants'
import { tasksApi } from '@/api/tasks'
import { jobsApi } from '@/api/jobs'
import { definitionsApi } from '@/api/definitions'
import { useTaskStore } from '@/stores/task'
import { stepLabel } from '@/utils/format'
import { validateConditionPayload } from '@/utils/condition-payload'
import { definitionCodesProblem, normalizeDefinitionCodes } from '@/utils/definition-codes'
import { confirmPublishByMode } from '@/utils/confirm-irreversible'
import { resolvePublishMode } from '@/utils/publish-mode'
import { findPublishTool } from '@/utils/publish-links'
import { listAiRegions } from '@/utils/ai-regions'
import { translate } from '@/composables/useCopy'
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
import type {
  PageHandler,
  PageHandlerName,
  UiEventLifetime,
  UiEventResult,
  WorkspaceSnapshot
} from '@/types/workspace'

/** 页面 id → 路由名。 */
const PAGE_ROUTES: Record<WorkspacePageId, string> = {
  tasks: ROUTE_NAMES.taskCenter,
  export: ROUTE_NAMES.exportWizard,
  import: ROUTE_NAMES.importWizard,
  definitions: ROUTE_NAMES.definitions,
  data: ROUTE_NAMES.dataBrowser
}

/** 页面 id 的中文名（**回灌给模型的说明文本用**；C5 红线：切 en 后仍须是中文）。 */
const PAGE_LABELS: Record<WorkspacePageId, string> = {
  tasks: '任务中心',
  export: '导出向导',
  import: '导入向导',
  definitions: '配置定义',
  data: '数据浏览'
}

/**
 * 页面 id → **界面**文案 key（i18n L-69）。与 {@link PAGE_LABELS} 是两份受众面：
 * 上面那份给模型（C5 锁中文），这里给面板 chip / 上下文摘要（随 UI 语言）。
 */
const PAGE_LABEL_KEYS: Record<WorkspacePageId, string> = {
  tasks: 'ai.workspace.page.tasks',
  export: 'ai.workspace.page.export',
  import: 'ai.workspace.page.import',
  definitions: 'ai.workspace.page.definitions',
  data: 'ai.workspace.page.data'
}

/** 发布跟踪的轮次 dispose（S4）：run_job 每发起一轮 publish 跟踪先收上一轮，卸载/收尾不留活口。 */
let publishWatcherDispose: (() => void) | null = null

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    /** 契约版本号：两侧按此判定兼容性 */
    contractVersion: WORKSPACE_CONTRACT_VERSION,
    /**
     * 页面标签（ToolScope 的 page:* 口径）：**与 pageId 同值**，即路由页 id 的镜像 ——
     * tasks / export / import / definitions / data。
     * 向导页的披露权威是 taskType+step（ToolScope.java 的 `task:*`）；
     * `page:export` / `page:import` 是保留粒度，当前无任何工具使用。
     */
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
    /** 上下文摘要（AI 面板的上下文 chip 文案；i18n L-69：页面名/deco 走 locale，随 UI 语言）。 */
    contextSummary(state): string {
      const pageText = translate(PAGE_LABEL_KEYS[state.pageId])
      const parts = [pageText]
      if (state.taskId !== null) {
        parts.push(translate('ai.workspace.task', { id: state.taskId }))
      }
      if (state.step) {
        parts.push(translate('ai.workspace.step', { label: stepLabel(state.step) }))
      }
      if (state.selectedDefs.length > 0) {
        parts.push(translate('ai.workspace.defsSelected', { count: state.selectedDefs.length }))
      }
      return parts.join(' · ')
    }
  },

  actions: {
    /**
     * 进入某页（路由守卫/页面 onMounted 调用）。
     *
     * <p><b>声明即全集</b>（批次5A）：带页面身份（`pageId`/`page`）的调用，是把本页的页面级上下文
     * **整体声明**一遍 —— `taskType` / `step` / `taskId` / `taskStatus` / `selectedDefs` /
     * `queryConditions` / `importMode` 里**没声明的一律回中性值**。理由：这些字段是「当前任务 /
     * 当前页」的事实，跨页残留会让下一轮上下文报错页 ——
     * <ul>
     * <li>残留 `taskType + step` ⇒ 后端披露面按 `task:*` 给工具，模型以为用户还在向导里；</li>
     * <li>残留 `taskStatus`（COMPLETED / CANCELLED）⇒ 只读守卫把**其它页**的写事件当
     * 「任务已结束」误拒（取消作业尤其明显）。</li>
     * </ul>
     * 不带页面身份的调用仍是**增量更新**（只写显式给出的字段），供同一页内补声明使用。
     *
     * <p><b>复位边界（不扩大清空）</b>：只复位页面级事实。`dataVersion` / `recentActions`
     * （面板与模型据此判断「刚才发生了什么」）与 `contractVersion` 一律不动；本页显式声明的值
     * （例：数据浏览页正在浏览的 `selectedDefs`）原样保留。
     */
    enterPage(init: Partial<WorkspaceSnapshot>): void {
      if (init.pageId !== undefined) {
        this.pageId = init.pageId
      }
      if (init.page !== undefined) {
        this.page = init.page
      } else if (init.pageId !== undefined) {
        this.page = init.pageId
      }
      if (init.pageId !== undefined || init.page !== undefined) {
        this.taskType = init.taskType ?? null
        this.step = init.step ?? null
        this.taskId = init.taskId ?? null
        this.taskStatus = init.taskStatus ?? null
        this.selectedDefs = init.selectedDefs === undefined ? [] : [...init.selectedDefs]
        this.queryConditions = init.queryConditions === undefined ? {} : { ...init.queryConditions }
        this.importMode = init.importMode ?? null
      } else {
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
      }
      this.touch(`进入${PAGE_LABELS[this.pageId] ?? this.page}`, '界面', {
        code: 'ai.workspace.enterPage',
        args: { page: translate(PAGE_LABEL_KEYS[this.pageId]) }
      })
    },

    setStep(step: string | null): void {
      this.step = step
      if (step) {
        this.touch(`切换到步骤「${stepLabel(step)}」`, '界面', {
          code: 'ai.workspace.switchStep',
          args: { step: stepLabel(step) }
        })
      } else {
        this.touch('退出当前步骤', '界面', { code: 'ai.workspace.exitStep' })
      }
    },

    /**
     * 任务状态写入（**后端快照驱动的唯一状态入口**）。
     *
     * <p>为什么要有它：`taskStatus` 是后端披露面的终态收窄依据（`ToolRegistry#stateAllows`：
     * COMPLETED 任务不再披露 `start_export`，只收这一支），而任务状态的变化几乎都来自**服务端**
     * （作业终态联动 ⇒ `GET /tasks/{id}` 快照）。向导页在作业终态后刷新快照，必须把新状态写回
     * 工作区，否则下一轮请求仍带着旧状态 ⇒ 收窄不生效（模型看到"还能导出"的假入口）。
     *
     * <p>不记 `recentActions`：这是**服务端事实的同步**，不是用户/AI 的动作（记进去会让
     * `extra.recentActions` 被状态刷新刷爆，模型误以为有人在操作）。同理不 `touch`、不广播
     * `workspace_changed` —— 面板 chip 不因一次状态轮询抖动。
     */
    setTaskStatus(status: TaskStatus | null): void {
      this.taskStatus = status
    },

    setTaskId(taskId: number | null, taskType?: TaskType | null, status?: TaskStatus | null): void {
      this.taskId = taskId
      this.taskType = taskType === undefined ? this.taskType : taskType
      this.taskStatus = status === undefined ? this.taskStatus : status
      if (taskId === null) {
        this.touch('解除任务绑定', '界面', { code: 'ai.workspace.unbindTask' })
      } else {
        this.touch(`绑定任务 #${taskId}`, '界面', {
          code: 'ai.workspace.bindTask',
          args: { taskId }
        })
      }
    },

    /**
     * 选中集写入（唯一写入口）。
     *
     * @param source 动作来源：页面把用户手点记为「界面」（默认，与改动前一致），
     *   AI 经页面 handler 驱动的写入记「AI」—— 面板 chip 与 `recentActions` 展示该来源，
     *   标错会让模型以为"用户自己点的"。可选参数，既有 3 个调用点无需改动。
     */
    setSelectedDefs(codes: string[], mode: 'REPLACE' | 'ADD' = 'REPLACE', source: WorkspaceActionSource = '界面'): void {
      if (mode === 'ADD') {
        this.selectedDefs = Array.from(new Set([...this.selectedDefs, ...codes]))
      } else {
        this.selectedDefs = [...codes]
      }
      const joined = this.selectedDefs.join('、')
      this.touch(`已选择配置项：${joined || '（空）'}`, source, {
        code: 'ai.workspace.selectDefs',
        args: { defs: joined || translate('ai.workspace.empty') }
      })
    },

    setQueryConditions(defCode: string, conditions: QueryCondition | unknown): void {
      this.queryConditions = { ...this.queryConditions, [defCode]: conditions }
      this.touch(`设置 ${defCode} 的查询条件`, '界面', {
        code: 'ai.workspace.queryConditions',
        args: { code: defCode }
      })
    },

    /**
     * 清空某配置项的查询条件（显式清空 = 回到"导出全部"）。
     *
     * 为什么不是 `setQueryConditions(defCode, null)`：留着 `{ [defCode]: null }` 会被
     * `get_workspace_state` / 上下文当成"已设置过条件"的占位（模型据此以为有筛选）；
     * 契约层与页面统一走本方法**删键**，工作区里"没有这个键"才等于"无条件"。
     */
    clearQueryConditions(defCode: string): void {
      if (!Object.prototype.hasOwnProperty.call(this.queryConditions, defCode)) {
        return
      }
      const next = { ...this.queryConditions }
      delete next[defCode]
      this.queryConditions = next
      this.touch(`清空 ${defCode} 的查询条件`, '界面', {
        code: 'ai.workspace.queryConditions',
        args: { code: defCode }
      })
    },

    setImportMode(mode: ImportMode | null): void {
      this.importMode = mode
      const label = mode === null
        ? translate('ai.workspace.importModeNone')
        : mode === 'MERGE'
          ? translate('ai.workspace.importModeMerge')
          : translate('ai.workspace.importModeReplace')
      this.touch(`导入模式设为 ${mode === null ? '未设置' : mode === 'MERGE' ? '增量合并' : '整体替换'}`, '界面', {
        code: 'ai.workspace.importMode',
        args: { mode: label }
      })
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
     * @param summary 中文动作摘要（同时是给模型看的一句话；C5：**不随 UI 语言变**）
     * @param source  界面（用户手点）或 AI（经契约层下发）
     * @param display 可选的**界面**结构化形态（i18n L-69）：面板 chip 按当前语言重译，
     *   与 `summary` 是同一动作的两份受众面
     */
    touch(summary: string, source: WorkspaceActionSource = '界面', display?: WorkspaceActionRecord['display']): number {
      const record = this.recordAction(summary, source, display)
      emitWorkspaceEvent('workspace_changed', record)
      return record.version
    },

    /**
     * 只落时间线、**不**广播 `workspace_changed`（批次5A：失败/被拒回执的落点）。
     *
     * <p>为什么仍要记：模型下一轮必须看到「这一手被拒了」（`extra.recentActions`），否则会把
     * 被拒当成已改动继续推进 —— 只读壳里的写事件尤其如此（既有用例锁住这条）。
     * 为什么不广播：面板的「工作区已同步」chip 只认**真落地**的动作；失败挂在那里就是谎报同步，
     * 失败回执另走 `ui_event_result` 到面板回执位（见 {@link dispatchUiEvent}）。
     */
    recordAction(
      summary: string,
      source: WorkspaceActionSource = 'AI',
      display?: WorkspaceActionRecord['display']
    ): WorkspaceActionRecord {
      this.dataVersion += 1
      const record: WorkspaceActionRecord = {
        version: this.dataVersion,
        summary,
        at: Date.now(),
        source,
        ...(display === undefined ? {} : { display })
      }
      this.recentActions = [...this.recentActions, record].slice(-WORKSPACE_ACTION_LIMIT)
      return record
    },

    /** 清空待同步动作（AI 发消息带上上下文后调用：动作只注入一轮，不重复累积）。 */
    clearRecentActions(): void {
      this.recentActions = []
    },

    /**
     * 注入到每轮 chat/tool-result 的 Agent Context。
     *
     * 键集 = 后端 `AiChatRequest.Context` 的六个顶层字段（page/taskType/step/taskId/taskStatus/extra），
     * 多余信息一律进 extra（`ContextFieldGate` 只把白名单键交给消费方）。`taskStatus` **必须顶层**：
     * 后端 `ToolRegistry#forContext` 用它做终态收窄（COMPLETED 任务不再披露 `start_export`）。
     */
    buildContext(): AiChatContext {
      return {
        page: this.page,
        taskType: this.taskType,
        step: this.step,
        taskId: this.taskId,
        taskStatus: this.taskStatus,
        extra: {
          pageId: this.pageId,
          selectedDefs: [...this.selectedDefs],
          importMode: this.importMode,
          dataVersion: this.dataVersion,
          contractVersion: this.contractVersion,
          // 批 A（A-11/A-12）：披露标签源 —— 只给后端的工具披露判定用，**刻意不进提示词**
          // （ContextBuilder.ALLOWED_EXTRA_KEYS 不含本键，见那里的刻意例外登记）。
          uiRegionCount: listAiRegions().length,
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

/**
 * `ui_event download` 的下载请求上限（毫秒）：<b>代码同源</b> = `utils/time-constants.ts` 的
 * {@link HTTP_TIMEOUT_MS}（axios 单请求上限）。两者都是"拉字节"类请求、延迟分布同类，
 * 故不各写一个字面量（超时面治理批① 第 4 项：此前是 `60_000` 字面量 + 注释对齐）。
 *
 * 为什么必须有（R-11）：本分支此前是**无 signal、无上限**的裸 `fetch`，上游不响应时
 * 这一步会永久挂住 —— 它在 `dispatchUiEvent` 的 await 链上，于是"回灌给模型的结果"
 * 也一起被拖住（与本批 W-01 同族：等待没有上界、结局没人消费）。
 */
const DOWNLOAD_FETCH_TIMEOUT_MS = HTTP_TIMEOUT_MS

function ok(message: string): UiEventResult {
  return { handled: true, message }
}

function fail(message: string): UiEventResult {
  return { handled: false, message }
}

/**
 * R-94 范围 B：异步终态结果（发布冲突明细）的回灌出口，与 {@link dispatchUiEvent} 同形同通道：
 * (1) 记入工作区动作（source=AI，随下一轮请求回到模型 = AI 保证通道）；
 * (2) `ui_event_result` 事件广播（AI 面板回执位，既有渲染位）。
 *
 * 为什么不在 run_job 的同步返回里等明细：发布作业分钟级才出终态，而 dispatch 链上有界等待
 * 是既有红线（R-11 / download 分支同族），故冲突检出时（终态后）经本出口异步回灌。
 */
export function reportUiEventResult(event: string, message: string): void {
  const workspace = useWorkspaceStore()
  workspace.touch(message, 'AI')
  emitWorkspaceEvent('ui_event_result', { event, handled: true, message })
}

/**
 * 一次前端工具执行的**存活标记**（定义与完整说明见 `types/workspace.ts#UiEventLifetime`）。
 *
 * <p>本文件是既有 import 面（`@/stores/workspace` 的 `UiEventLifetime`）的再导出点；
 * 契约层（`dispatchUiEvent` / `runUiEvent`）在每次 await 之后读它，已作废的续体不再落
 * 任何工作区写入口。
 */
export type { UiEventLifetime } from '@/types/workspace'

/**
 * 只读态公共判据（收口修 F6，红队 minor-4）：与两向导 `isReadOnly` 同口径的契约层版，
 * 供 ui_event 写事件共用（守卫逻辑只此一份，各分支只传文案）。
 *
 * CANCELLED 必为只读；COMPLETED 须按类型核「最终作业」——导入侧预检查 0 错误即置 COMPLETED、
 * 但流程仍可继续，单看任务状态会把「预检查后继续导入」误判为只读而误伤 AI 驱动，故拉一次
 * 作业列表复核（GET，非写；仅在任务已 COMPLETED/CANCELLED 时才会触发这一跳）。
 */
async function isCurrentTaskReadOnly(workspace: ReturnType<typeof useWorkspaceStore>): Promise<boolean> {
  const status = workspace.taskStatus
  if (status === 'CANCELLED') {
    return true
  }
  if (status !== 'COMPLETED' || workspace.taskId === null || workspace.taskType === null) {
    return false
  }
  // R-137 收口修 R-1（口径差明示）：此处判「该类型**最近一条**作业 COMPLETED」；后端终态守卫
  // （JobService/TaskService 的 assertTaskNotFinalized）判「**存在任一** COMPLETED 最终作业」。
  // 发散方向仅「后端拒而前端放行」，且该状态在终态机下不可达（最终作业成功后任务即 COMPLETED、
  // 守卫拒新建；失败/取消均经 applyJobOutcome 联动改任务态），实际影响仅限遗留脏数据窗口。
  // 统一 finder 口径留待后续清理批（红队 R-1 / 复核 F-1 登记），本注释不改判定逻辑。
  const finalJobType = workspace.taskType === 'IMPORT' ? 'PUBLISH' : 'EXPORT'
  const jobs = await jobsApi.listJobs(workspace.taskId).catch(() => null)
  const latest = jobs?.filter((job) => job.jobType === finalJobType)[0]
  return latest?.status === 'COMPLETED'
}

/**
 * 分发 AI 侧发来的 ui_event。
 *
 * 每个分支都落到**与用户手点相同**的 API/action 上，因此 AI 驱动与手工操作
 * 的后端副作用完全一致（不新建"AI 专用后门"）。
 *
 * 契约联动（批次5A 语义收口）：
 * - **成功**（`handled=true`）：记入工作区动作并广播 `workspace_changed`（面板出「工作区已同步」chip，
 *   `source=AI` 的动作随下一轮请求回到模型）；
 * - **失败**（`handled=false`）：**不广播** `workspace_changed` —— 「已同步」不能拿来描述一次没落地的
 *   动作（审查 §5-4 的「失败仍已同步」）。但时间线仍留一条（模型下一轮要知道这一手被拒），
 *   失败回执经 `ui_event_result` 送面板回执位（`handled=false` + 原因文案）。
 *
 * @param lifetime 前端工具执行的存活标记（见 {@link UiEventLifetime}）。到点作废 ⇒ **不记动作、
 *   不广播**（时间线与 chip 都不得替一次已经回灌"超时"的动作背书）；缺省不传 = 行为不变。
 */
export async function dispatchUiEvent(event: UiEvent, lifetime?: UiEventLifetime): Promise<UiEventResult> {
  const result = await runUiEvent(event, lifetime)
  if (lifetime?.abandoned === true) {
    return result
  }
  const workspace = useWorkspaceStore()
  if (result.handled) {
    workspace.touch(result.message, 'AI')
  } else {
    workspace.recordAction(result.message, 'AI')
  }
  emitWorkspaceEvent('ui_event_result', { event: event.type, ...result })
  return result
}

async function runUiEvent(event: UiEvent, lifetime?: UiEventLifetime): Promise<UiEventResult> {
  const workspace = useWorkspaceStore()
  const taskStore = useTaskStore()
  /** 预算是否已作废（到点后不再写工作区；每个 await 之后各查一次）。 */
  const abandoned = (): boolean => lifetime?.abandoned === true
  /** 任务身份核对：await 之后工作区绑定的仍是发起时的那个任务，才允许写回。 */
  const taskStill = (taskId: number): boolean => workspace.taskId === taskId
  /** 任务已切换的如实回执（不写工作区、不假称已完成）。 */
  const switched = (taskId: number): UiEventResult => fail(
    `任务已切换（原任务 #${taskId}，当前任务 #${workspace.taskId ?? '（无）'}）：本次动作已提交给原任务，未写入当前工作区`
  )
  try {
    switch (event.type) {
      case 'open_page': {
        const routeName = PAGE_ROUTES[event.page]
        await router.push({ name: routeName })
        return ok(`已打开${PAGE_LABELS[event.page]}`)
      }
      case 'restore_task': {
        // 类型优先用事件里带的**权威值**（导航侧已由 `ensureTaskType` 钉过）；没带就当场补一次
        // 缓存优先的查询（未命中 GET）。查不到即 fail-closed：按列表兜底成 EXPORT 正是
        // "冷缓存下导入任务被送进导出向导"的根因。
        const taskType = event.taskType ?? (await taskStore.ensureTaskType(event.taskId))
        if (taskType === null) {
          return fail(`无法确认任务 #${event.taskId} 的类型（任务不存在或读取失败），未导航`)
        }
        const target = taskStore.resolveWizardRoute(event.taskId, taskType)
        await router.push(target)
        const isImport = target.name === ROUTE_NAMES.importWizard
        return ok(`已恢复到任务 #${event.taskId} 的向导（${isImport ? '导入' : '导出'}）`)
      }
      case 'goto_step': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法跳转步骤')
        }
        // R-137（T5）：只读态守卫，与 F6 五处同构 —— 终态任务的 currentStep 不得再动
        if (await isCurrentTaskReadOnly(workspace)) {
          return fail('任务已结束，无法跳转步骤')
        }
        // 守卫期间预算可能已到点：到点后不得再发写请求
        if (abandoned()) {
          return fail('前端工具预算已到点：本次未跳转步骤')
        }
        await tasksApi.goToStep(taskId, event.step)
        // 写回前必须核对：改的是**发起时那个任务**的工作区状态（换任务后不得写到新任务上）
        if (abandoned()) {
          return fail(`前端工具预算已到点：步骤变更已提交给任务 #${taskId}，未写入当前工作区`)
        }
        if (!taskStill(taskId)) {
          return switched(taskId)
        }
        workspace.setStep(event.step)
        return ok(`已跳转到步骤 ${event.step}`)
      }
      case 'select_defs': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法选择配置项')
        }
        // 收口修 F6（红队 minor-4）：只读态守卫，与页面 handler 同口径
        if (await isCurrentTaskReadOnly(workspace)) {
          return fail('任务已结束，仅供查看，未变更所选配置项')
        }
        if (abandoned()) {
          return fail('前端工具预算已到点：本次未变更所选配置项')
        }
        // R1 返修（reviewer Important）：兜底分支此前**不校验编码** —— 页面未挂载时，
        // 非法码照样落库并写进 `extra.selectedDefs`。这里用**权威定义服务**做与页面同口径的
        // 校验；定义不可用 / 编码非法一律零写（不落库、不写工作区）。
        const codes = normalizeDefinitionCodes(event.defCodes)
        let available: string[] = []
        try {
          available = (await definitionsApi.listDefinitions()).map((def) => def.code)
        } catch {
          // 定义服务不可用 ⇒ 无法校验 ⇒ 零写；文案由同一判据生成（空清单必返回原因，无需兜底）
          return fail(definitionCodesProblem(codes, []))
        }
        if (abandoned()) {
          return fail('前端工具预算已到点：本次未变更所选配置项')
        }
        const problem = definitionCodesProblem(codes, available)
        if (problem !== null) {
          return fail(problem)
        }
        if (!taskStill(taskId)) {
          return switched(taskId)
        }
        await tasksApi.selectDefs(taskId, codes)
        if (abandoned()) {
          return fail(`前端工具预算已到点：所选配置项已提交给任务 #${taskId}，未写入当前工作区`)
        }
        if (!taskStill(taskId)) {
          return switched(taskId)
        }
        workspace.setSelectedDefs(codes)
        return ok(`已选择配置项：${codes.join('、') || '（空）'}`)
      }
      case 'set_conditions': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法设置查询条件')
        }
        // 收口修 F6（红队 minor-4）：只读态守卫，与页面 handler 同口径
        if (await isCurrentTaskReadOnly(workspace)) {
          return fail('任务已结束，仅供查看，未设置查询条件')
        }
        if (abandoned()) {
          return fail('前端工具预算已到点：本次未设置查询条件')
        }
        // R3 返修（reviewer Important）：兜底分支此前把模型载荷**原样** PUT 并写工作区 ——
        // `IN []` / 空值 / 坏 JSON 都能落库。这里用与页面完全相同的判据链
        // （parseConditionJson → draftModelFromCondition → buildConditionOutcome）先校验：
        // 非法 / 不可解析 ⇒ 零写；显式空条件（`{}`）与坏载荷分开，绝不把坏载荷当"全量"。
        const verdict = validateConditionPayload(event.conditions)
        if (!verdict.ok) {
          return fail(verdict.reason)
        }
        if (!taskStill(taskId)) {
          return switched(taskId)
        }
        await tasksApi.setCondition(taskId, event.defCode, verdict.serialized)
        if (abandoned()) {
          return fail(`前端工具预算已到点：查询条件已提交给任务 #${taskId}，未写入当前工作区`)
        }
        if (!taskStill(taskId)) {
          return switched(taskId)
        }
        if (verdict.condition === null) {
          // 显式清空：工作区**删键**（不留 `{defCode: null}` 占位，与页面同形）
          workspace.clearQueryConditions(event.defCode)
        } else {
          workspace.setQueryConditions(event.defCode, verdict.condition)
        }
        return ok(`已设置 ${event.defCode} 的查询条件`)
      }
      case 'set_import_mode': {
        const taskId = workspace.taskId
        if (taskId === null) {
          return fail('当前没有进行中的任务，无法设置导入模式')
        }
        // 收口修 F6（红队 minor-4）：只读态守卫，与页面 handler 同口径
        if (await isCurrentTaskReadOnly(workspace)) {
          return fail('任务已结束，仅供查看，未变更导入模式')
        }
        if (abandoned()) {
          return fail('前端工具预算已到点：本次未变更导入模式')
        }
        await tasksApi.setImportMode(taskId, event.mode)
        if (abandoned()) {
          return fail(`前端工具预算已到点：导入模式已提交给任务 #${taskId}，未写入当前工作区`)
        }
        if (!taskStill(taskId)) {
          return switched(taskId)
        }
        workspace.setImportMode(event.mode)
        return ok(`导入模式已设为 ${event.mode === 'MERGE' ? '增量合并' : '整体替换'}`)
      }
      case 'run_job': {
        // 收口修 F6（红队 minor-4）：目标任务是当前只读任务时拒绝（与页面 start 系 handler 同口径）；
        // 其余任务 id 不在当前只读壳语义内，按原路径放行（后端终态守卫另见 R-137 登记）。
        if (event.taskId === workspace.taskId && (await isCurrentTaskReadOnly(workspace))) {
          return fail('任务已结束，仅供查看，未启动作业')
        }
        // R-09（fix round 1 / C-1）：旧 ui_event 分支与页面同口径 —— 发布模式只认服务端权威值。
        // 读取失败 / settings 不可解析 ⇒ fail-closed：不弹确认、不建作业，回执明确未启动。
        if (event.jobType === 'PUBLISH') {
          const resolution = await resolvePublishMode(event.taskId)
          if (!resolution.ok) {
            return fail(`${resolution.reason}。本次未启动发布作业`)
          }
          // Minor 2：当前任务的权威模式回写工作区（模型上下文的事实源）；直接赋字段，
          // 不调用 setImportMode()（那会记一条并不存在的“界面动作”）。
          if (workspace.taskId === event.taskId && workspace.importMode !== resolution.mode) {
            workspace.importMode = resolution.mode
          }
          const confirmed = await confirmPublishByMode(resolution.mode)
          if (!confirmed) {
            return fail('用户取消了发布确认，未启动发布作业')
          }
        }
        const job = await jobsApi.createJob(event.taskId, event.jobType)
        if (event.jobType === 'PUBLISH') {
          // Q4 / R-94 范围 B：发布冲突是**异步形态**（恒 201），必须等作业终态后查 issues 组明细摘要。
          // 取消链路 = dispose 形态（C1 收口修对齐 M4，唯一机制）：持有 dispose，
          // 新一轮 publish 发起时先收旧跟踪（轮次收尾），静默、不弹提示。
          publishWatcherDispose?.()
          publishWatcherDispose = taskStore.watchPublishOutcome(event.taskId, job.id ?? 0, {
            // AI 保证通道：明细摘要经 reportUiEventResult（UiEventResult 同形）回灌模型 + 面板回执位
            onConflict: (summary, issues) => {
              // R-155：注册表命中（该 jobId 有 start_publish 工具段关联）时把明细写回工具卡 reason；
              // S6 批 2：同一份结构化 issues 一并落成 resultView（卡面渲表，不再反解 reason）。
              // 查无关联（run_job 自建作业等）静默走原回执条通道，不报错、不阻断
              if (findPublishTool(job.id ?? 0)) {
                void import('@/stores/ai').then(({ useAiStore }) => {
                  useAiStore().attachPublishConflict(job.id ?? 0, summary, issues)
                })
              }
              reportUiEventResult('run_job', summary)
            }
          })
          return ok(`发布作业已启动（作业 #${job.id}），冲突会在完成后以作业失败 + 问题清单呈现`)
        }
        return ok(`作业已启动：${event.jobType}（作业 #${job.id}）`)
      }
      case 'cancel_job': {
        // 收口修 F6（红队 minor-4）：只读态守卫 —— 与 F1 页面侧「取消作业」守卫同根同口径
        // （异类型并发态下任务 CANCELLED 仍可能有在途作业，只读壳内不得再发取消）。
        if (await isCurrentTaskReadOnly(workspace)) {
          return fail('任务已结束，仅供查看，未取消作业')
        }
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
        // R-11：补上"无 signal、无上限"的缺口 —— 到点 abort ⇒ 走下方 catch 如实回 fail，
        // 而不是永远等（该 await 在 `dispatchUiEvent` 链上，等下去连回灌都发不出去）。
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), DOWNLOAD_FETCH_TIMEOUT_MS)
        try {
          const response = await fetch(event.url, { signal: controller.signal })
          if (!response.ok) {
            return fail(`下载失败：HTTP ${response.status}`)
          }
          saveBlob(await response.blob(), event.filename ?? 'download')
          return ok(`已触发下载 ${event.filename ?? event.url}`)
        } catch (error) {
          if (controller.signal.aborted) {
            return fail(`下载失败：${DOWNLOAD_FETCH_TIMEOUT_MS / 1000} 秒内未取回文件（已中止本次请求），未写入任何文件`)
          }
          throw error
        } finally {
          clearTimeout(timer)
        }
      }
      case 'refresh_defs': {
        // W-09（文案改实）：本动作只发工作区事件，而 `workspace_changed` 的**唯一订阅者**是
        // AI 面板的上下文 chip（`stores/ai.ts`），**没有任何页面订阅它去重新拉取数据**
        // —— 故"已通知配置定义页刷新"是空回执（数据不会变）。这里改为如实陈述。
        notifyWorkspaceChanged('AI 请求刷新配置定义')
        return ok('已记录刷新意图（AI 请求刷新配置定义）：当前没有任何页面订阅该事件并重新拉取数据，配置定义页的数据不会因此变化；'
          + '如需最新定义，请让用户在界面上重新打开该页，或稍后重新查询。')
      }
      case 'refresh_data': {
        // W-09（文案改实）：同 `refresh_defs` —— 空回执改为如实陈述（无页面订阅者）。
        notifyWorkspaceChanged('AI 请求刷新数据浏览')
        return ok(`已记录刷新意图（AI 请求刷新数据浏览${event.defCode ? `：${event.defCode}` : ''}）：`
          + '当前没有任何页面订阅该事件并重新拉取数据，数据浏览页的数据不会因此变化；'
          + '如需最新数据，请让用户在界面上重新打开该页，或稍后重新查询。')
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
