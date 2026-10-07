/**
 * 任务中心 store：列表/分页/筛选 + 4s 自刷 + 当前任务与作业/问题状态。
 *
 * 形态来源：deepseek-v4-pro `views/TasksView.vue:154`（运行中任务 4s 轮询 +
 * `if (!loading) load()` 防重叠），TS 化并补上 Q4 的**发布冲突异步形态**。
 *
 * 口径提醒：
 * - 列表排序由后端固定 createdAt DESC, id DESC；关键词转义后端已处理（Q16②），前端原样传；
 * - 进度是**行级**口径（jobs.progress / jobs.total），配置项级进度看 job_items；
 * - 取消已终态作业 → 409 JOB_ALREADY_FINAL（按码给中文提示，不当普通错误弹）；
 * - 发布冲突**恒定 201**：冲突表现为作业 FAILED + issues 文案，必须等终态再查 issues。
 */

import { defineStore } from 'pinia'
import { ApiError } from '@/api/http'
import { jobsApi, waitForJobFinal } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { ROUTE_NAMES } from '@/router'
import { ErrorCode, type PageResult, type PublishConflictAsync } from '@/types/api'
import { isJobFinal } from '@/types/job'
import { TaskSteps, type ImportMode, type Task, type TaskFile, type TaskStatus, type TaskSummary, type TaskType } from '@/types/task'
import type { Job, JobStatus, JobType, ValidationIssue } from '@/types/job'
import type { RouteLocationRaw } from 'vue-router'

/** 向导路由目标（窄化形态，便于调用方读 name 做中文提示）。 */
export interface WizardRouteTarget {
  name: string
  query: { taskId: string }
}

/** 任务中心的默认自刷间隔（规格：4s）。 */
export const TASK_AUTO_REFRESH_MS = 4000

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

/** 作业状态 → 中文标签。 */
export function jobStatusLabel(status: JobStatus | string): string {
  switch (status) {
    case 'PENDING':
      return '待执行'
    case 'RUNNING':
      return '执行中'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '已失败'
    case 'CANCELLED':
      return '已取消'
    default:
      return String(status)
  }
}

/** 自刷定时器放模块级：同一时刻只允许一个，切页/隐藏 AI 栏都不影响业务可用性。 */
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null

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
    /** 自动刷新是否开启 */
    autoRefresh: false,
    lastLoadedAt: 0 as number,

    /** 当前任务（详情抽屉/向导共用） */
    currentTaskId: null as number | null,
    currentTask: null as Task | null,
    currentJobs: [] as Job[],
    currentFiles: [] as TaskFile[],
    /** 当前任务最新作业的校验问题 */
    issues: [] as ValidationIssue[],
    issuesTotal: 0,
    issuesLoading: false,
    detailLoading: false,

    /** Q4 发布冲突的异步形态（作业 FAILED + issues 文案） */
    publishConflict: null as PublishConflictAsync | null
  }),

  getters: {
    /** 最新作业（行级进度展示用）。 */
    latestJob(state): Job | null {
      return state.currentJobs[0] ?? state.items[0]?.latestJob ?? null
    },
    hasRunningJob(state): boolean {
      return state.currentJobs.some((job) => job.status === 'RUNNING' || job.status === 'PENDING')
    },
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
      }
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

    /** 开启 4s 自刷（已在跑则忽略；每次 tick 前检查 loading 防重叠）。 */
    startAutoRefresh(intervalMs = TASK_AUTO_REFRESH_MS): void {
      if (autoRefreshTimer !== null) {
        this.autoRefresh = true
        return
      }
      autoRefreshTimer = setInterval(() => {
        if (!this.loading) {
          void this.load()
        }
      }, intervalMs)
      this.autoRefresh = true
    },

    stopAutoRefresh(): void {
      if (autoRefreshTimer !== null) {
        clearInterval(autoRefreshTimer)
        autoRefreshTimer = null
      }
      this.autoRefresh = false
    },

    /** 打开任务详情（任务 + 全部作业 + 文件）。 */
    async openTask(taskId: number): Promise<void> {
      this.currentTaskId = taskId
      await this.refreshCurrent()
    },

    /** 刷新当前任务详情与其最新作业的问题。 */
    async refreshCurrent(): Promise<void> {
      const taskId = this.currentTaskId
      if (taskId === null) {
        return
      }
      this.detailLoading = true
      try {
        const overview = await tasksApi.getTaskOverview(taskId)
        this.currentTask = overview.task
        this.currentJobs = overview.jobs ?? []
        this.currentFiles = overview.files ?? []
        const latest = this.currentJobs[0]
        if (latest?.id !== undefined && latest.status === 'FAILED') {
          await this.loadIssues(latest.id)
        }
      } catch (error) {
        this.error = error instanceof Error ? error.message : '任务详情加载失败'
      } finally {
        this.detailLoading = false
      }
    },

    closeTask(): void {
      this.currentTaskId = null
      this.currentTask = null
      this.currentJobs = []
      this.currentFiles = []
      this.issues = []
      this.issuesTotal = 0
      this.publishConflict = null
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
        if (this.currentTaskId === taskId) {
          this.closeTask()
        }
        await this.load()
        return true
      } catch (error) {
        this.error = error instanceof Error ? error.message : '删除任务失败'
        return false
      }
    },

    async setImportMode(taskId: number, mode: ImportMode): Promise<boolean> {
      try {
        await tasksApi.setImportMode(taskId, mode)
        await this.refreshCurrent()
        return true
      } catch (error) {
        this.error = error instanceof Error ? error.message : '设置导入模式失败'
        return false
      }
    },

    /** 启动作业并刷新快照。 */
    async startJob(taskId: number, jobType: JobType): Promise<Job | null> {
      try {
        const job = await jobsApi.createJob(taskId, jobType)
        await this.refreshCurrent()
        await this.load()
        return job
      } catch (error) {
        this.error = error instanceof Error ? error.message : '启动作业失败'
        return null
      }
    },

    /** 取消作业：终态 409 按码给提示（不当普通错误）。 */
    async cancelJob(jobId: number): Promise<{ handled: boolean; message: string }> {
      try {
        await jobsApi.cancelJob(jobId)
        await this.refreshCurrent()
        return { handled: true, message: `已请求取消作业 #${jobId}` }
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.JOB_ALREADY_FINAL) {
          return { handled: false, message: `作业 #${jobId} 已是终态，无需取消` }
        }
        return { handled: false, message: error instanceof Error ? error.message : '取消作业失败' }
      }
    },

    /** 加载某作业的校验问题（Q4 冲突文案来源）。 */
    async loadIssues(jobId: number, page = 0, size = 50): Promise<void> {
      this.issuesLoading = true
      try {
        const result = await jobsApi.getIssues(jobId, page, size)
        this.issues = result.content ?? []
        this.issuesTotal = result.totalElements ?? 0
      } catch (error) {
        this.error = error instanceof Error ? error.message : '校验问题加载失败'
      } finally {
        this.issuesLoading = false
      }
    },

    /**
     * 跟踪发布作业终态：FAILED 时查 issues 并组装 Q4 的冲突形态。
     *
     * 前端**禁止**把发布当成同步请求判错：POST /api/tasks/{id}/jobs 恒 201，
     * 冲突只能从作业终态 + issues 文案读出。
     */
    async watchPublishOutcome(jobId: number): Promise<void> {
      if (!jobId) {
        return
      }
      const job = await waitForJobFinal(jobId, {
        intervalMs: 1000,
        onTick: (tick) => {
          const index = this.currentJobs.findIndex((item) => item.id === jobId)
          if (index >= 0) {
            this.currentJobs.splice(index, 1, tick)
          }
        }
      })
      await this.refreshCurrent()
      if (job.status !== 'FAILED') {
        this.publishConflict = null
        return
      }
      await this.loadIssues(jobId)
      const errorIssues = this.issues.filter((issue) => issue.severity === 'ERROR')
      const first = errorIssues[0]
      this.publishConflict = {
        jobId,
        jobStatus: 'FAILED',
        issueKeys: errorIssues.map((issue) => `${issue.defCode}#${issue.rowKey ?? '-'}`),
        message: first?.message ?? '发布作业失败，请查看校验问题清单'
      }
    },

    /**
     * 一键恢复向导：按任务类型与当前步骤跳到对应向导页。
     * 类型未知时按提交的 taskType 兜底，仍未知则先拉一次任务详情再定。
     */
    async restoreWizard(taskId: number, taskType?: TaskType): Promise<RouteLocationRaw> {
      const type = taskType ?? this.currentTask?.type ?? (await this.fetchTaskType(taskId))
      if (type === 'IMPORT') {
        return { name: ROUTE_NAMES.importWizard, query: { taskId: String(taskId) } }
      }
      return { name: ROUTE_NAMES.exportWizard, query: { taskId: String(taskId) } }
    },

    /** 同步版的向导目标（供 ui_event 分发使用，命中缓存即回声，不回则按 EXPORT 兜底）。 */
    resolveWizardRoute(taskId: number, taskType?: TaskType): WizardRouteTarget {
      const cached = taskType
        ?? this.currentTask?.type
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
