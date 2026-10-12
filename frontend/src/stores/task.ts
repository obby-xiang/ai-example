/**
 * 任务中心 store：列表/分页/筛选 + 手动刷新通道。
 *
 * 形态来源：deepseek-v4-pro `views/TasksView.vue:154`（TS 化；原 Q4 发布冲突异步形态
 * `publishConflict/issues` 一族死字段随 R-94 清除，冲突明细改走双通道落点，见
 * {@link applyPublishOutcome}）。
 *
 * 刷新口径（手动刷新批，M1 + F2）：任务中心**不再有任何周期自刷**（步1/步2 的
 * 4s 条件 + 30s 兜底两张表、步3 的任务级 SSE 订阅池整体摘除 —— 页面静止时对后端零请求）。
 * 唯一的拉取入口是 `refreshNow()` 手动刷新通道：右上角刷新按钮、用户写操作
 * （取消任务等）成功后的本地刷新、ui_event 驱动的即时刷新都走它；
 * 被在途 load（loading 挡住的）合并为恰好 1 次补发（refreshPending 标记 + finally 收口）。
 *
 * 口径提醒：
 * - 列表排序由后端固定 createdAt DESC, id DESC；关键词转义后端已处理（Q16②），前端原样传；
 * - 进度是**行级**口径（jobs.progress / jobs.total），配置项级进度看 job_items；
 * - 取消已终态作业 → 409 JOB_ALREADY_FINAL（按码给中文提示，不当普通错误弹）；
 * - 发布冲突**恒定 201**：冲突表现为作业 FAILED + issues 文案，必须等终态再查 issues。
 */

import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { jobsApi } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { ROUTE_NAMES } from '@/router'
import { ErrorCode, type PageResult } from '@/types/api'
import { isJobFinal } from '@/types/job'
import { TaskSteps, type Task, type TaskStatus, type TaskSummary, type TaskType } from '@/types/task'
import type { Job, JobStatus, ValidationIssue } from '@/types/job'
import { watchJob } from '@/utils/job-watch'
import type { RouteLocationRaw } from 'vue-router'

/** 向导路由目标（窄化形态，便于调用方读 name 做中文提示）。 */
export interface WizardRouteTarget {
  name: string
  query: { taskId: string }
}

/** 步进条状态映射的输入（以任务状态机为准，不用进度百分比猜）。 */
export interface StepStateView {
  /** el-steps 的 status */
  elStatus: 'wait' | 'process' | 'finish' | 'error' | 'success'
  /** 中文说明 */
  label: string
}

/** 任务状态 → 步进条状态（步进条状态映射以任务状态机为准）。 */
export function mapTaskStatusToStep(status: TaskStatus | null): StepStateView {
  switch (status) {
    case 'COMPLETED':
      return { elStatus: 'success', label: '已完成' }
    case 'FAILED':
      return { elStatus: 'error', label: '已失败' }
    case 'CANCELLED':
      return { elStatus: 'wait', label: '已取消' }
    case 'ACTIVE':
    default:
      return { elStatus: 'process', label: '进行中' }
  }
}

/**
 * refreshNow 的待办标记：被 loading 挡住时置位，由在途那次 load() 复位 loading 后补发**恰好一次**
 * （多次阻挡合并为 1 次）。模块级与调用方无关，任何页面经 refreshNow 进来的阻挡都合并。
 */
let refreshPending = false

/**
 * 发布跟踪的 300s 墙钟（M4）：独立定时器，**终态到达即清**。非终态到达 ⇒ 提示恰好 1 次 + 收摊。
 */
export const PUBLISH_WATCH_WALL_CLOCK_MS = 300_000

/**
 * 发布跟踪注册表（F4）：key = `taskId:jobId`。AI dispatch（workspace run_job）与向导页发起
 * 的 publish watcher 同键不得双开 —— 第二次调用直接返回在册 dispose。
 */
const publishWatchers = new Map<string, () => void>()

/**
 * 作业跟踪的提示出口。
 *
 * 仓内既有的用户提示机制是 ElMessage（views 层各页均在用）；stores 层此前只有列表加载失败用的
 * `error` 字段（绑在任务中心的 Alert 渲染位上，文案口径不符），故沿用 ElMessage 而不新建渲染位。
 * 每个出口都只调一次 —— 提示次数必须与跟踪期间的快照次数解耦。
 */
function notifyJobWatch(message: string): void {
  ElMessage.warning(message)
}

/**
 * R-94 范围 B：冲突明细摘要组装（逐条列出，截断前 5 条 + 「共 N 处」）。
 * 文本即 Ai 保证通道的 message 载荷（回灌模型）与人读明细。
 */
function formatPublishConflictSummary(jobId: number, issues: ValidationIssue[]): string {
  const lines = issues.slice(0, 5).map((issue, index) =>
    `${index + 1}. ${issue.defCode}#${issue.rowKey ?? '-'}：${issue.message}`)
  const tail = issues.length > 5 ? `（仅列前 5 条，共 ${issues.length} 处）` : `（共 ${issues.length} 处）`
  return [`发布作业 #${jobId} 校验冲突明细：`, ...lines, tail].join('\n')
}

/**
 * 发布跟踪的 jobId 闸（F4，红队 M4）：`watchPublishOutcome` 的结局只在 jobId 匹配**当前跟踪**时
 * 生效。并发两条跟踪时后发起者占据闸位 —— 旧作业晚到的结局不得覆盖/清空新作业已落位的冲突态。
 */
let watchingJobId: number | null = null

export const useTaskStore = defineStore('task', {
  state: () => ({
    items: [] as TaskSummary[],
    total: 0,
    page: 0,
    size: 10,
    /** 筛选条件（keyword 的 LIKE 转义由后端处理） */
    filterType: null as TaskType | null,
    filterStatus: null as TaskStatus | null,
    filterKeyword: '',
    loading: false,
    error: null as string | null,
    lastLoadedAt: 0 as number
  }),

  getters: {
    /** 任务中心的筛选参数（拉取时用）。 */
    queryParams(state): { type?: TaskType; status?: TaskStatus; keyword?: string; page: number; size: number } {
      const params: { type?: TaskType; status?: TaskStatus; keyword?: string; page: number; size: number } = {
        page: state.page,
        size: state.size
      }
      if (state.filterType) {
        params.type = state.filterType
      }
      if (state.filterStatus) {
        params.status = state.filterStatus
      }
      if (state.filterKeyword.trim()) {
        params.keyword = state.filterKeyword.trim()
      }
      return params
    }
  },

  actions: {
    /** 拉取任务列表（分页 + 筛选）。 */
    async load(): Promise<void> {
      if (this.loading) {
        return
      }
      this.loading = true
      this.error = null
      try {
        const result: PageResult<TaskSummary> = await tasksApi.listTasks(this.queryParams)
        this.items = result.content ?? []
        this.total = result.totalElements ?? 0
        this.lastLoadedAt = Date.now()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '任务列表加载失败'
      } finally {
        this.loading = false
        // 被 loading 挡住的 refreshNow 在这里补发（恰好 1 次；正常完成与被挡住两条路径共用此检查点）
        this.flushPendingRefresh()
      }
    },

    /**
     * 补发被 loading 挡住的 refreshNow（多次阻挡合并为 1 次）。
     *
     * 检查点只此一处即可覆盖两条路径：refreshNow 自己走到 load()（正常完成），
     * 以及 refreshNow 被在途 load 挡住（由**在途那次**的 finally 兜住）。
     */
    flushPendingRefresh(): void {
      if (!refreshPending) {
        return
      }
      refreshPending = false
      void this.load()
    },

    /**
     * 手动刷新通道（M1 + F2 后**唯一**的列表拉取入口）：立即拉一次列表。
     * 右上角刷新按钮、用户写操作成功后的本地刷新、ui_event 即时刷新都走这里。
     * 被在途 load 挡住的刷新**不丢弃**——记 refreshPending，由在途那次的 finally 补发恰好 1 次。
     */
    async refreshNow(): Promise<void> {
      if (this.loading) {
        refreshPending = true
        return
      }
      await this.load()
    },

    setPage(page: number): void {
      this.page = page
      void this.load()
    },

    setSize(size: number): void {
      this.size = size
      this.page = 0
      void this.load()
    },

    applyFilters(patch: { type?: TaskType | null; status?: TaskStatus | null; keyword?: string }): void {
      if (patch.type !== undefined) {
        this.filterType = patch.type
      }
      if (patch.status !== undefined) {
        this.filterStatus = patch.status
      }
      if (patch.keyword !== undefined) {
        this.filterKeyword = patch.keyword
      }
      this.page = 0
      void this.load()
    },

    async createTask(type: TaskType, title?: string): Promise<Task | null> {
      try {
        const task = await tasksApi.createTask(type, title)
        await this.load()
        return task
      } catch (error) {
        this.error = error instanceof Error ? error.message : '创建任务失败'
        return null
      }
    },

    async removeTask(taskId: number): Promise<boolean> {
      try {
        await tasksApi.deleteTask(taskId)
        await this.load()
        return true
      } catch (error) {
        this.error = error instanceof Error ? error.message : '删除任务失败'
        return false
      }
    },

    /** 取消作业：终态 409 按码给提示（不当普通错误）。 */
    async cancelJob(jobId: number): Promise<{ handled: boolean; message: string }> {
      try {
        await jobsApi.cancelJob(jobId)
        return { handled: true, message: `已请求取消作业 #${jobId}` }
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.JOB_ALREADY_FINAL) {
          return { handled: false, message: `作业 #${jobId} 已是终态，无需取消` }
        }
        return { handled: false, message: error instanceof Error ? error.message : '取消作业失败' }
      }
    },

    /**
     * 一键恢复向导：按任务类型与当前步骤跳到对应向导页。
     *
     * 前端**禁止**把发布当成同步请求判错：POST /api/tasks/{id}/jobs 恒 201，
     * 冲突只能从作业终态 + issues 文案读出。
     *
     * 出口语义（调用点是 stores/workspace.ts 的 `void` 调用，不接 rejection）：
     * - **取消链路 = dispose 形态（唯一机制，C1 收口修对齐 M4）**：调用方持有返回的 dispose，
     *   轮次收尾/新一轮发起时调用即静默收摊（不弹提示）；不上抛（上抛即 unhandled rejection）；
     * - 300s 墙钟（独立定时器，终态到达即清）→ 非终态到达：提示恰好 1 次 + 收摊；
     * - 失败/成功终态 → onFinal 内收口（R-94 范围 B 双通道：ElMessage 瞬时提示 +
     *   onConflict 明细摘要回灌，见 {@link applyPublishOutcome}）。
     *
     * @param handlers.onConflict 冲突明细摘要的出口回调（AI 保证通道缝点，run_job 分支注入）
     * @returns dispose —— 调用方持有；组件卸载/轮次收尾调用。
     */
    watchPublishOutcome(
      taskId: number,
      jobId: number,
      handlers: { onConflict?: (summary: string) => void } = {}
    ): () => void {
      if (!jobId) {
        return () => {}
      }
      const key = `${taskId}:${jobId}`
      const existing = publishWatchers.get(key)
      if (existing) {
        return existing // F4 注册表归属单一：同 taskId+jobId 不得双开
      }
      watchingJobId = jobId // F4：占据闸位 —— 只认最后发起的跟踪

      let wallClockTimer: ReturnType<typeof setTimeout> | null = null
      const clearWallClock = (): void => {
        if (wallClockTimer !== null) {
          clearTimeout(wallClockTimer)
          wallClockTimer = null
        }
      }
      let disposeWatched: (() => void) | null = null
      const dispose = (): void => {
        clearWallClock()
        publishWatchers.delete(key)
        disposeWatched?.()
        disposeWatched = null
      }

      disposeWatched = watchJob({
        taskId,
        jobId,
        onFinal: (job) => {
          clearWallClock() // 终态到达即清墙钟（300s 出口只在"没等到终态"时发生）
          dispose()
          void this.applyPublishOutcome(jobId, job, handlers)
        },
        onError: (error) => {
          dispose()
          notifyJobWatch(`发布作业 #${jobId} 的终态跟踪中断：${error instanceof Error ? error.message : '未知错误'}`)
        }
      })
      publishWatchers.set(key, dispose)
      // 300s 墙钟：与 watchJob 内部定时器独立；非终态到达 ⇒ 单次提示 + 收摊
      wallClockTimer = setTimeout(() => {
        wallClockTimer = null
        notifyJobWatch(`发布作业 #${jobId} 等待超时（仍未进入终态），结果请稍后在任务中心刷新查看`)
        dispose()
      }, PUBLISH_WATCH_WALL_CLOCK_MS)
      return dispose
    },

    /**
     * 发布终态收口（onFinal 内调用，R-94 范围 B）：F4 jobId 闸 → 非 FAILED 直接收；
     * FAILED 时拉 issues 组明细摘要，双通道落点（不复活 store 字段、不建视图渲染位）：
     * ① `handlers.onConflict`（AI 保证通道 —— run_job 分支注入，明细摘要经 UiEventResult
     *   同形通道回灌模型 + 面板回执位）；② `notifyJobWatch` ElMessage 瞬时提示。提示只做一次。
     */
    async applyPublishOutcome(
      jobId: number,
      job: Job,
      handlers: { onConflict?: (summary: string) => void } = {}
    ): Promise<void> {
      if (watchingJobId !== jobId) {
        // F4：旧作业晚到终态 —— 闸位已被更新的跟踪占据，不覆盖/清空新冲突
        return
      }
      if (job.status !== 'FAILED') {
        return
      }
      let issues: ValidationIssue[] | null = null
      try {
        const result = await jobsApi.getIssues(jobId, 0, 50)
        issues = result.content ?? []
      } catch {
        issues = null // 明细拉取失败：提示纪律不变（F5），明细通道降级为无清单形态
      }
      if (issues === null) {
        notifyJobWatch(`发布作业 #${jobId} 失败，校验问题清单拉取失败，请在任务中心查看`)
        handlers.onConflict?.(`发布作业 #${jobId} 失败（发布冲突），校验问题明细拉取失败`)
        return
      }
      // 冲突项 = ERROR 级（沿袭原 publishConflict.issueKeys 口径）；无 ERROR 条目时降级用全量清单
      const conflictIssues = issues.filter((issue) => issue.severity === 'ERROR')
      const counted = conflictIssues.length > 0 ? conflictIssues : issues
      notifyJobWatch(`发布冲突：${counted.length} 处，明细见 AI 面板回执`)
      handlers.onConflict?.(formatPublishConflictSummary(jobId, counted))
    },

    /**
     * 一键恢复向导：按任务类型跳到对应向导页。
     * 类型未知时按提交的 taskType 兜底，仍未知则先拉一次任务详情再定。
     * （R-94：原 `currentTask?.type` 读点随死字段删除 —— 该值恒 null 恒走兜底，功能等效死码。）
     */
    async restoreWizard(taskId: number, taskType?: TaskType): Promise<RouteLocationRaw> {
      const type = taskType ?? (await this.fetchTaskType(taskId))
      if (type === 'IMPORT') {
        return { name: ROUTE_NAMES.importWizard, query: { taskId: String(taskId) } }
      }
      return { name: ROUTE_NAMES.exportWizard, query: { taskId: String(taskId) } }
    },

    /** 同步版的向导目标（供 ui_event 分发使用，命中缓存即回声，不回则按 EXPORT 兜底）。 */
    resolveWizardRoute(taskId: number, taskType?: TaskType): WizardRouteTarget {
      const cached = taskType
        ?? this.items.find((item) => item.task.id === taskId)?.task.type
        ?? 'EXPORT'
      if (cached === 'IMPORT') {
        return { name: ROUTE_NAMES.importWizard, query: { taskId: String(taskId) } }
      }
      return { name: ROUTE_NAMES.exportWizard, query: { taskId: String(taskId) } }
    },

    async fetchTaskType(taskId: number): Promise<TaskType> {
      try {
        const task = await tasksApi.getTask(taskId)
        return task.type
      } catch {
        return 'EXPORT'
      }
    },

    /** 当前任务在向导中的步骤 key 序列（步进条与 AI 上下文共用）。 */
    stepsOf(taskType: TaskType | null): readonly string[] {
      if (taskType === 'IMPORT') {
        return TaskSteps.IMPORT
      }
      return TaskSteps.EXPORT
    },

    /** 判断作业是否终态（供模板/组件复用，避免各页各写一套）。 */
    isFinal(status: JobStatus): boolean {
      return isJobFinal(status)
    }
  }
})
