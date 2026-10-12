/**
 * 任务中心 store：列表/分页/筛选 + 条件自刷 + 当前任务与作业/问题状态。
 *
 * 形态来源：deepseek-v4-pro `views/TasksView.vue:154`（运行中任务 4s 轮询 +
 * `if (!loading) load()` 防重叠），TS 化并补上 Q4 的**发布冲突异步形态**。
 *
 * 轮询治理步1/步2 的条件口径：有在跑作业（hasActiveJob）才 4s 刷，闲置每 30s 兜底刷；
 * 两张表由 `utils/polling.ts` 的**单 controller 动态切换**承载（步2 起不再并存双 setInterval），
 * 并接上可见性纪律（hidden 停表、visible 错峰补刷一次）。
 * 需要即时刷新的入口走 refreshNow()（绕过谓词，被 loading 挡住的请求合并补发 1 次）。
 *
 * 轮询治理步3 的事件口径：当前页有 RUNNING/PENDING 作业的任务订阅任务级事件流
 * （`utils/task-events.ts` 的池子，上限 3、按 taskId 降序淘汰保最新行），帧到达即
 * 直更对应行进度（JOB_PROGRESS）或即时 refreshNow（JOB_DONE / TASK_CHANGED）；
 * 池子随任务中心页生命周期开关（startTaskEvents / closeTaskEvents），集合在 load() 后重算。
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
import { JobWaitAbortedError, jobsApi, waitForJobFinal, type WaitJobFinalOutcome } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { ROUTE_NAMES } from '@/router'
import { ErrorCode, type PageResult, type PublishConflictAsync } from '@/types/api'
import { isJobFinal } from '@/types/job'
import { TaskSteps, type ImportMode, type Task, type TaskFile, type TaskStatus, type TaskSummary, type TaskType } from '@/types/task'
import type { Job, JobStatus, JobType, ValidationIssue } from '@/types/job'
import { createPollingController, type PollingController } from '@/utils/polling'
import { createTaskEventsPool, type TaskEventTarget, type TaskEventsPool } from '@/utils/task-events'
import type { JobProgressPayload } from '@/types/sse'
import type { RouteLocationRaw } from 'vue-router'

/** 向导路由目标（窄化形态，便于调用方读 name 做中文提示）。 */
export interface WizardRouteTarget {
  name: string
  query: { taskId: string }
}

/** 任务中心的自刷间隔（规格：4s —— 仅在**有在跑作业**时生效）。 */
export const TASK_AUTO_REFRESH_MS = 4000

/** 闲置兜底自刷间隔（规格：30s —— 列表无在跑作业时也要跟得上别处变更）。 */
export const TASK_IDLE_REFRESH_MS = 30_000

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
 * 自刷控制器放模块级：同一时刻只允许一个，切页/隐藏 AI 栏都不影响业务可用性。
 *
 * 轮询治理步2：步1 的「4s 条件 timer + 30s 兜底 timer」双表过渡形态在此收敛为**单 controller
 * 动态切换**（`utils/polling.ts`）—— 有在跑作业按 4s、闲置按 30s，由 controller 在每个 tick 前
 * 重新判定、节奏变化时清旧表起新表（绝不因切换在同一 tick 双发）。可见性纪律同批接入：
 * hidden 停表（不再空转打后端）、visible 错峰补刷一次。
 */
let autoRefreshController: PollingController | null = null

/**
 * refreshNow 的待办标记：被 loading 挡住时置位，由在途那次 load() 复位 loading 后补发**恰好一次**
 * （多次阻挡合并为 1 次）。与定时器同放模块级。
 */
let refreshPending = false

/**
 * 任务级事件订阅池（步3）：模块级单例（与 autoRefreshController 同口径——同一时刻只允许一个）。
 *
 * 订阅**随任务中心页生命周期**开关：`TaskCenterView.onMounted` 起、`onBeforeUnmount` **显式**关
 * （订阅状态挂 store、跨路由存活 ⇒ 不能依赖组件自动清理，见 utils/task-events.ts 的文件头）。
 * 集合重算只在 `load()` 成功后统一做：筛选/翻页/作业终态三个出口都经 load 落地。
 */
let taskEventsPool: TaskEventsPool | null = null

/**
 * 作业跟踪的提示出口。
 *
 * 仓内既有的用户提示机制是 ElMessage（views 层各页均在用）；stores 层此前只有列表加载失败用的
 * `error` 字段（绑在任务中心的 Alert 渲染位上，文案口径不符），故沿用 ElMessage 而不新建渲染位。
 * 每个出口都只调一次 —— 提示次数必须与 onTick 的秒级发声解耦。
 */
function notifyJobWatch(message: string): void {
  ElMessage.warning(message)
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
    /** 自动刷新是否开启 */
    autoRefresh: false,
    lastLoadedAt: 0 as number,
    /**
     * 任务级事件订阅数（步3 的可观测字段）：与真实 `EventSource` 实例数一一对应
     * （上限见 utils/task-events.ts；泄漏可直接断言计数，不必翻 devtools）。
     */
    taskEventSubscriptionCount: 0 as number,

    /** 当前任务（向导与 ui_event 刷新链路共用） */
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
    /**
     * 是否有"在跑/待跑"的作业（4s 条件自刷的谓词）。
     *
     * 以**当前 items** 为准（后端过滤后等价承载 filterStatus 口径）：后端已按筛选条件分页，
     * 筛选后当前页没有在跑行就不该 4s 空转（其余由 30s 兜底 tick 覆盖）。
     */
    hasActiveJob(state): boolean {
      return state.items.some(
        (item) => item.latestJob?.status === 'RUNNING' || item.latestJob?.status === 'PENDING'
      )
    },

    /**
     * 需要开任务级事件订阅的行（步3）：当前页 `latestJob` 处于 RUNNING/PENDING 的任务。
     *
     * 只给"能动的"任务开通道；池内另有上限（3）与按 taskId 降序取前 3（保最新行）的淘汰，
     * 死通道（onerror 判死）会被关闭并退回列表轮询兜底，见 utils/task-events.ts。
     * 无条件作业的行、以及被淘汰的第 4+ 个在跑行，都由列表的条件轮询兜底（`hasActiveJob` 覆盖全体）。
     */
    taskEventTargets(state): TaskEventTarget[] {
      const targets: TaskEventTarget[] = []
      for (const item of state.items) {
        const job = item.latestJob
        if (!job) {
          continue
        }
        if (job.status !== 'RUNNING' && job.status !== 'PENDING') {
          continue
        }
        const taskId = item.task.id
        if (taskId === undefined) {
          continue
        }
        targets.push({ taskId, jobId: job.id ?? null })
      }
      return targets
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
        // 事件订阅集合随当前页重算（步3）：筛选/翻页/作业终态三个出口都经 load 落地
        this.syncTaskEvents()
        // F2 冷进入节奏升级：load 写回新 items 后，若 armed 节奏落后于 hasActiveJob 判定
        // （典型 = 空列表武装了 30s 保底、本次 load 才写回在跑行），立即重排为 4s ——
        // 不把升级推迟到下一个保底 tick（那样"有在跑作业每 4s"最长失真 30s）。节奏未变则无操作。
        autoRefreshController?.resync()
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
     * 绕过 hasActiveJob 谓词拉一次列表（手动刷新 / ui_event 驱动的即时刷新 / 回可见补刷入口）。
     *
     * 步2 起**委托 controller**：立即跑一次（不受条件谓词限制）+ **重置 interval 起点**（口径①：
     * 刷新时刻重新起表，防"补刷 + 原 tick"同刻双发）。在途 load 挡住的刷新**不丢弃**——
     * controller 侧记 dirty flag（收尾补发恰好 1 次），store 侧 `refreshPending` 作为第二道防线；
     * 两侧都只补 1 次，且 run 与 tick 同体（同一 `!loading` 守卫）⇒ 不会同 tick 双跑。
     */
    async refreshNow(): Promise<void> {
      const controller = autoRefreshController
      if (this.loading) {
        refreshPending = true
        // 仍让 controller 重置起点（其 run 被 `!loading` 守卫短路，本发不落地）
        controller?.refreshNow()
        return
      }
      if (controller) {
        controller.refreshNow()
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

    /**
     * 开启自刷（已在跑则忽略 —— 重复调用不产生第二个 interval）。
     *
     * 轮询治理步2：单 controller 动态切换承载两张节奏（步1 的双 setInterval 过渡形态已收敛）：
     * - 有在跑作业（`shouldRun` = hasActiveJob）→ 每 intervalMs（4s）拉一次；
     * - 闲置 → 每 TASK_IDLE_REFRESH_MS（30s）兜底拉一次，别处的变更仍能跟上来。
     * tick 体在进 load() 前查 loading 防重叠（deepseek 蓝本形态）。
     */
    startAutoRefresh(intervalMs = TASK_AUTO_REFRESH_MS): void {
      if (autoRefreshController !== null) {
        this.autoRefresh = true
        return
      }
      const controller = createPollingController({
        intervalMs,
        fallbackIntervalMs: TASK_IDLE_REFRESH_MS,
        shouldRun: () => this.hasActiveJob,
        run: () => {
          if (this.loading) {
            return
          }
          return this.load()
        }
      })
      autoRefreshController = controller
      controller.start()
      this.autoRefresh = true
    },

    stopAutoRefresh(): void {
      autoRefreshController?.stop()
      autoRefreshController = null
      this.autoRefresh = false
    },

    /**
     * 开启任务级事件订阅池（任务中心页 onMounted；幂等）。
     *
     * 订阅状态挂 store（Pinia 单例、**跨路由存活**）⇒ 池子随页面生命周期开关：
     * 起表在这里，出口是**显式**的 `closeTaskEvents()`（`TaskCenterView.onBeforeUnmount`），
     * 集合重算在 `load()` 成功后统一做（筛选/翻页/终态都经 load 落地）。
     */
    startTaskEvents(): void {
      if (taskEventsPool) {
        taskEventsPool.sync()
        return
      }
      taskEventsPool = createTaskEventsPool({
        getTargets: () => this.taskEventTargets,
        onProgress: (taskId, payload) => this.applyJobProgress(taskId, payload),
        onRefresh: () => {
          // TASK_CHANGED / JOB_DONE 的即时刷新走 refreshNow：撞在途守卫时不丢弃（收尾补发恰好 1 次）
          void this.refreshNow()
        },
        onCountChange: (count) => {
          this.taskEventSubscriptionCount = count
        }
      })
      taskEventsPool.sync()
    },

    /** 按当前页重算订阅集合（`load()` 成功后调用；池未开启时无操作）。 */
    syncTaskEvents(): void {
      taskEventsPool?.sync()
    },

    /**
     * 关闭全部任务级订阅（任务中心页卸载的**显式出口**；幂等）。
     *
     * 订阅状态跨路由存活，所以这条出口必须由页面显式调——不依赖组件卸载自动清理。
     */
    closeTaskEvents(): void {
      taskEventsPool?.closeAll()
      taskEventsPool = null
      this.taskEventSubscriptionCount = 0
    },

    /**
     * JOB_PROGRESS 帧直更当前行（行级 `progress`/`total`，不等下一次列表刷新）。
     *
     * 只认与 `latestJob.id` 相同的 payload：旧作业的进度不许盖到新作业的展示位上
     * （与 job-watch 的在途快照 disposed 复检同一条纪律）。
     */
    applyJobProgress(taskId: number, payload: JobProgressPayload): void {
      const item = this.items.find((row) => row.task.id === taskId)
      const latest = item?.latestJob
      if (!latest || latest.id !== payload.jobId) {
        return
      }
      latest.progress = payload.processed
      latest.total = payload.total
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
     *
     * 轮询治理步1 的两条收口（调用点是 stores/workspace.ts 的 `void` 调用，不接 rejection）：
     * - abort（外部 signal 取消）→ 提示恰好 1 次后正常返回，**不上抛**（上抛即 unhandled rejection）；
     * - 超时兜底（非终态到达）→ 显式提示恰好 1 次，不再静默把 publishConflict 清成 null。
     * 两者共用"单次出口"语义：提示次数与 onTick 的秒级发声解耦（onTick 里绝不发声）。
     */
    async watchPublishOutcome(jobId: number, signal?: AbortSignal): Promise<void> {
      if (!jobId) {
        return
      }
      watchingJobId = jobId // F4：占据闸位 —— 只认最后发起的跟踪
      let outcome: WaitJobFinalOutcome
      try {
        outcome = await waitForJobFinal(jobId, {
          intervalMs: 1000,
          signal,
          onTick: (tick) => {
            const index = this.currentJobs.findIndex((item) => item.id === jobId)
            if (index >= 0) {
              this.currentJobs.splice(index, 1, tick)
            }
          }
        })
      } catch (error) {
        if (error instanceof JobWaitAbortedError) {
          notifyJobWatch(`发布作业 #${jobId} 的终态跟踪已取消，结果请稍后在任务中心查看`)
          return
        }
        notifyJobWatch(`发布作业 #${jobId} 的终态跟踪中断：${error instanceof Error ? error.message : '未知错误'}`)
        return
      }
      if (watchingJobId !== jobId) {
        // F4：旧作业晚到结局 —— 闸位已被更新的跟踪占据，不覆盖/清空新冲突
        return
      }
      await this.refreshCurrent()
      if (outcome.status === 'timeout') {
        // 非终态到达：保留现场（不为"没等到"清掉已知的冲突态），显式提示一次让用户自行刷新
        notifyJobWatch(`发布作业 #${jobId} 等待超时（仍未进入终态），结果请稍后在任务中心刷新查看`)
        return
      }
      const job = outcome.job
      if (job.status !== 'FAILED') {
        this.publishConflict = null
        return
      }
      // F5：FAILED 结局与 abort/timeout 同出口纪律 —— 单次提示（此前独漏失败分支）；
      // 不重建 publishConflict 渲染位（归 R-94 批）。
      notifyJobWatch(`发布作业 #${jobId} 失败，请查看校验问题清单`)
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
