/**
 * 任务中心 store：列表/分页/筛选 + 手动刷新通道。
 *
 * 形态来源：deepseek-v4-pro `views/TasksView.vue:154`（TS 化；原 Q4 发布冲突异步形态
 * `publishConflict/issues` 一族死字段随 R-94 清除，冲突明细改走双通道落点，见
 * {@link applyPublishOutcome}）。
 *
 * 刷新口径（手动刷新批，M1 + F2）：任务中心**不再有任何周期自刷**（步1/步2 的
 * 4s 条件 + 30s 兜底两张表、步3 的任务级 SSE 订阅池整体摘除 —— 页面静止时对后端零请求）。
 * 唯一的拉取入口是 `refreshNow()`：右上角刷新按钮、用户写操作（取消任务等）成功后的本地刷新、
 * ui_event 驱动的即时刷新都走它；撞上在途 load 的刷新合并为恰好 1 次补发
 * （refreshPending 标记 + 在途计数归零时收口）。
 *
 * 翻页/筛选的 last-wins（并发形态）：`load()` 不再用 `loading` 早退 —— 参数在发包前快照并领序号，
 * 旧响应晚到一律丢弃，新请求照常发出（见 `loadSeq` / `inFlightLoads`）。
 *
 * 事件后刷新（AR-08，批次 5B）：在"手动刷新"之上只**加**一条受限通道 —— ①AI 工具事件后
 * ②存在非终态作业（`PENDING`/`RUNNING`）③页面可见，三者同时成立才刷一次（{@link notifyJobEvent}）。
 * **不引入**常驻轮询 / 后台标签刷新 / 跨页签广播刷新：没有事件就没有请求，且"数据截至时刻"
 * 让"没刷新"与"没有变化"可区分。
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
import { translate } from '@/composables/useCopy'
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
  /** 中文说明（模型侧/旧调用面词表，不随 UI 语言变；TaskWizard 自己按 locale 渲染状态标签） */
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
 * refreshNow 的待办标记：撞上在途 load 时置位，由**最后一个**收尾的 load() 补发**恰好一次**
 * （多次阻挡合并为 1 次）。模块级与调用方无关，任何页面经 refreshNow 进来的阻挡都合并。
 */
let refreshPending = false

/**
 * 列表拉取的**最新序号**（last-wins 的唯一判据）。
 *
 * <p>为什么不能再用 `loading` 挡请求：翻页/改筛选本身会把 `page`/`filter*` 立刻写进 state，
 * 而"在途请求属于旧参数"这件事只有**响应回来时**才看得出来。旧实现用 `loading` 早退，
 * 第二次翻页连请求都发不出去（页面停在新页码、数据还是旧页）—— 丢的不是旧响应，是**新请求**。
 * 现在每个请求带序号，只有最新一次允许写回 `items`/`total`；旧响应晚到一律丢弃。
 */
let loadSeq = 0

/**
 * 在途 load 数（>0 = 还有列表请求没回来）。
 *
 * 与 {@link loadSeq} 分工：序号决定"谁能写回"，计数决定"什么时候收 loading/补发 refreshNow"。
 * 快速翻页会真的产生多条在途请求，loading 必须等**全部**收尾才落下（否则中间那次收尾就把遮罩
 * 撤了，用户看到的是尚未落定的旧数据）。
 */
let inFlightLoads = 0

/**
 * 发布跟踪的 360s 墙钟（M4）：独立定时器，**终态到达即清**。非终态到达 ⇒ 提示恰好 1 次 + 收摊。
 */
export const PUBLISH_WATCH_WALL_CLOCK_MS = 360_000

/**
 * 列表里是否存在**非终态作业**（AR-08 条件②，也是 AR-16「只探测 PENDING/RUNNING」的同族判据）。
 *
 * 只认 `PENDING` / `RUNNING`：终态作业不构成"还有东西要等"的理由 —— 把它也算作在途，
 * 一次终态落定就会让任务中心对每个后续事件都刷一次（那就是变相轮询）。
 */
export function hasInFlightJob(items: readonly TaskSummary[]): boolean {
  return items.some((item) => {
    const status = item.latestJob?.status
    return status === 'PENDING' || status === 'RUNNING'
  })
}

/**
 * 会改变任务中心列表事实的 AI 事件名白名单（`ui_event_result.event` 与 `start_*` 工具名同表）。
 *
 * 只认**起作业 / 取消作业**这一类；导航、选配置、设条件不刷新 —— 否则一轮对话能把列表刷十几次。
 */
export const TASK_CENTER_REFRESH_EVENTS: ReadonlySet<string> = new Set([
  'run_job',
  'cancel_job',
  'start_export',
  'start_precheck',
  'start_import',
  'start_publish'
])

/** 事件名是否在白名单内（视图订阅 `ui_event_result` 时的唯一判据）。 */
export function isTaskCenterRefreshEvent(event: unknown): event is string {
  return typeof event === 'string' && TASK_CENTER_REFRESH_EVENTS.has(event)
}

/**
 * 事件本身是否证明"有作业刚起来"（AR-08 条件②的第二种成立方式）。
 *
 * 起作业后该作业必然非终态，而列表快照**还没回来**（甚至还是旧数据、看不出在途）——
 * 只按列表判据会把"刚起的那一次"整条丢掉（用户停在任务中心等结果时最需要那一次刷新）。
 */
export function jobEventStartedJob(event: string): boolean {
  return event === 'run_job' || event.startsWith('start_')
}

/** 页面可见判据（AR-08 条件③：`document.visibilityState === 'visible'`）。 */
function isDocumentVisible(): boolean {
  return typeof document === 'undefined' || document.visibilityState === 'visible'
}

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
 * R-94 范围 B → S6 批1（v7.10 N8）：冲突明细摘要组装。
 *
 * 形态：**首行 = 人话摘要**（`发布作业 #N 校验冲突明细（共 M 处）`），其后逐条编号明细
 * （`N. defCode#rowKey：message`，截断前 5 条）。首行即卡面 reason 红底行的**唯一**文案
 * （「原因：<首行>」）—— 两种形态不同屏重复报同一份明细（N8 判据）。
 * 文本同时是 Ai 保证通道的 message 载荷（回灌模型），故明细本身保留在字符串里。
 *
 * S6 批 2：卡面明细表已改由 `resultView`（结构化 issues → kind=table）承载，
 * 本串只服务**模型侧**与旧段回退；字符串形态**刻意不动**（「reason 是否只留一句」是独立的模型侧决策）。
 */
function formatPublishConflictSummary(jobId: number, issues: ValidationIssue[]): string {
  const lines = issues.slice(0, 5).map((issue, index) =>
    `${index + 1}. ${issue.defCode}#${issue.rowKey ?? '-'}：${issue.message}`)
  const head = issues.length > 5
    ? `发布作业 #${jobId} 校验冲突明细（共 ${issues.length} 处，仅列前 5 条）`
    : `发布作业 #${jobId} 校验冲突明细（共 ${issues.length} 处）`
  return [head, ...lines].join('\n')
}

/**
 * 发布跟踪的 jobId 闸（F4，红队 M4）：`watchPublishOutcome` 的结局只在 jobId 匹配**当前跟踪**时
 * 生效。并发两条跟踪时后发起者占据闸位 —— 旧作业晚到的结局不得覆盖/清空新作业已落位的冲突态。
 */
let watchingJobId: number | null = null

/**
 * `ensureTaskType` 的读取失败分类：只有**瞬时**失败值得退避重试。
 *
 * - 网络层未到达（`status === 0`）/ 网关或服务端 5xx / 显式限流与超时（408 / 429）⇒ 重试；
 * - 任务确定不存在（404 等确定性 4xx）⇒ 不重试，立刻 fail-closed（重试同一条不会变）；
 * - 非 `ApiError` 的意外异常 ⇒ 按读取失败处理（不知道是不是瞬时的，给一次机会）。
 */
function isTransientTaskReadFailure(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return true
  }
  if (error.status === 0 || error.status === 408 || error.status === 429) {
    return true
  }
  return error.status >= 500
}

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
    /** 列表加载失败（TaskCenterView 顶部横幅）。 */
    loadError: null as string | null,
    /** 写操作失败（创建/删除；只走 ElMessage，不污染列表横幅）。 */
    actionError: null as string | null,
    lastLoadedAt: 0 as number,
    /**
     * AR-08：任务中心是否**正在被看**（视图挂载/卸载写入）。
     * 没人在看时的作业事件不刷新 —— 没有渲染位，刷了也只是给后端加一次无用请求。
     */
    watchingCenter: false,
    /** AR-08：隐藏期收到、且已满足①②的刷新请求（回可见补刷**一次**，不按时间补偿）。 */
    autoRefreshPending: false,
    /**
     * 权威任务类型缓存（`GET /tasks/{id}` 的落点，按 store 实例隔离）。
     * `restore_task` / `navigate_to` 以它为准 —— **不因读取失败回落 EXPORT**（那是"导入跳导出"的根因）。
     */
    knownTaskTypes: {} as Record<number, TaskType>
  }),

  getters: {
    /** 列表里是否存在非终态作业（AR-08 条件②；视图与用例读同一处判据）。 */
    hasInFlightJob(state): boolean {
      return hasInFlightJob(state.items)
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
    /**
     * 拉取任务列表（分页 + 筛选）。
     *
     * <p><b>last-wins</b>：参数在**发包前**快照，并领一个序号；响应回来时只有"仍是最新一次"
     * 才允许写回。快速翻页 / 连改筛选会产生多条在途请求 —— 旧响应晚到不得覆盖新页，
     * 也不能再因为 `loading` 把新请求挡在门外（那会让页面停在新页码却显示旧数据）。
     */
    async load(): Promise<void> {
      const seq = ++loadSeq
      const params = this.queryParams
      inFlightLoads += 1
      this.loading = true
      this.loadError = null
      try {
        const result: PageResult<TaskSummary> = await tasksApi.listTasks(params)
        if (seq !== loadSeq) {
          return // 旧参数的结果：更新的那次请求才是列表的归属，丢弃
        }
        this.items = result.content ?? []
        this.total = result.totalElements ?? 0
        this.lastLoadedAt = Date.now()
        // 列表已是最新：隐藏期攒下的那次补刷不再需要（手动刷新 / 事件刷新都从这里收口）
        this.autoRefreshPending = false
      } catch (error) {
        if (seq !== loadSeq) {
          return // 旧请求的失败不得污染当前列表的错误位
        }
        this.loadError = error instanceof Error ? error.message : translate('views.tasks.msg.loadFailed')
      } finally {
        inFlightLoads -= 1
        if (inFlightLoads === 0) {
          this.loading = false
          // 被在途请求挡住的 refreshNow 在这里补发（恰好 1 次；全部收尾后才补）
          this.flushPendingRefresh()
        }
      }
    },

    /**
     * 补发被在途 load 挡住的 refreshNow（多次阻挡合并为 1 次）。
     *
     * 检查点只在"在途计数归零"时触发；仍有请求在飞 ⇒ 留给最后收尾的那次兜住。
     */
    flushPendingRefresh(): void {
      if (!refreshPending || inFlightLoads > 0) {
        return
      }
      refreshPending = false
      void this.load()
    },

    /**
     * 手动刷新通道（M1 + F2 后**唯一**的列表拉取入口）：立即拉一次列表。
     * 右上角刷新按钮、用户写操作成功后的本地刷新、ui_event 即时刷新都走这里。
     * 撞上在途请求的刷新**不丢弃**——记 refreshPending，由最后收尾的那次补发恰好 1 次。
     */
    async refreshNow(): Promise<void> {
      if (inFlightLoads > 0) {
        refreshPending = true
        return
      }
      await this.load()
    },

    /**
     * AR-08：开始"正在看任务中心"的观察窗（视图 `onMounted`）。
     * 观察窗之外的事件一律不刷新 —— 自动刷新的前提是**有人要看这份数据**。
     */
    watchCenter(): void {
      this.watchingCenter = true
    },

    /** 结束观察窗（视图 `onBeforeUnmount`）；待补标记一并清掉，不给离开的页面留隐形待办。 */
    unwatchCenter(): void {
      this.watchingCenter = false
      this.autoRefreshPending = false
    },

    /**
     * AR-08：**事件后**的唯一自动刷新判据（AI 工具事件 → 本函数）。
     *
     * 三条件同时成立才刷一次：
     * ① 事件后 —— 由调用方保证（`start_*` 工具结果 / 面板转发的 `ui_event_result`）；
     * ② 存在非终态作业 —— 列表里已有 `PENDING`/`RUNNING`，或**本次事件就是起作业**
     *    （`jobEventStartedJob`：刚起的作业必然非终态，列表快照还没回来的那一拍靠它兜住）；
     * ③ 页面可见 —— `visibilityState === 'visible'`。
     *
     * ③ 不成立 ⇒ **不刷**，只记 `autoRefreshPending`（回可见补刷恰好 1 次，不补后台错过的次数）。
     * ② 不成立 ⇒ 直接返回：没有要等的作业，事件不该换来一次请求（本函数就是"不空转"的闸）。
     *
     * **这不是轮询**：本函数没有任何定时器，没有事件就没有请求（AR-08 明令禁止常驻轮询 /
     * 后台标签刷新 / 跨页签广播刷新）。
     */
    notifyJobEvent(event: string): void {
      if (!this.watchingCenter) {
        return
      }
      if (!jobEventStartedJob(event) && !hasInFlightJob(this.items)) {
        return
      }
      if (!isDocumentVisible()) {
        this.autoRefreshPending = true
        return
      }
      this.autoRefreshPending = false
      void this.refreshNow()
    },

    /**
     * AR-08：回到前台时补刷隐藏期收到的那次事件（**恰好一次**，不按时间补偿）。
     *
     * 视图在 `visibilitychange` 且可见时调用；没观察 / 无待补 / 仍隐藏 ⇒ 什么都不做。
     * 补刷落地（成功或失败）都由 `load()` 收口 —— 失败进 `loadError`，不静默。
     */
    flushAutoRefreshOnVisible(): void {
      if (!this.autoRefreshPending || !this.watchingCenter || !isDocumentVisible()) {
        return
      }
      this.autoRefreshPending = false
      void this.refreshNow()
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
      this.actionError = null
      try {
        const task = await tasksApi.createTask(type, title)
        if (task?.id !== undefined && (task.type === 'EXPORT' || task.type === 'IMPORT')) {
          // 创建响应就是权威事实：钉进类型缓存，后续 restore_task / 导航不必再拉一次
          this.knownTaskTypes[task.id] = task.type
        }
        await this.load()
        return task
      } catch (error) {
        this.actionError = error instanceof Error ? error.message : translate('views.tasks.msg.createFailed')
        return null
      }
    },

    async removeTask(taskId: number): Promise<boolean> {
      this.actionError = null
      try {
        await tasksApi.deleteTask(taskId)
        await this.load()
        return true
      } catch (error) {
        this.actionError = error instanceof Error ? error.message : translate('views.tasks.msg.deleteFailed')
        return false
      }
    },

    /** 取消作业：终态 409 按码给提示（不当普通错误）。 */
    async cancelJob(jobId: number): Promise<{ handled: boolean; message: string }> {
      try {
        await jobsApi.cancelJob(jobId)
        return { handled: true, message: translate('views.tasks.msg.cancelRequested', { jobId }) }
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.JOB_ALREADY_FINAL) {
          return { handled: false, message: translate('views.tasks.msg.jobAlreadyFinal', { jobId }) }
        }
        return { handled: false, message: error instanceof Error ? error.message : translate('views.tasks.msg.cancelFailed') }
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
     * - 360s 墙钟（独立定时器，终态到达即清）→ 非终态到达：提示恰好 1 次 + 收摊；
     * - 失败/成功终态 → onFinal 内收口（R-94 范围 B 双通道：ElMessage 瞬时提示 +
     *   onConflict 明细摘要回灌，见 {@link applyPublishOutcome}）。
     *
     * @param handlers.onConflict 冲突明细摘要的出口回调（AI 保证通道缝点，run_job 分支注入）。
     *   第二个参数是**结构化 issues**（S6 批 2）：调用方据此构造 `resultView` 表格，
     *   不必再回头反解摘要字符串（设计稿 §5.3「拆的是消费者，不是字符串」）。
     * @returns dispose —— 调用方持有；组件卸载/轮次收尾调用。
     */
    watchPublishOutcome(
      taskId: number,
      jobId: number,
      handlers: { onConflict?: (summary: string, issues?: ValidationIssue[]) => void } = {}
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
          clearWallClock() // 终态到达即清墙钟（360s 出口只在"没等到终态"时发生）
          dispose()
          void this.applyPublishOutcome(jobId, job, handlers)
        },
        onError: (error) => {
          dispose()
          notifyJobWatch(translate('views.tasks.msg.watchInterrupted', {
            jobId,
            message: error instanceof Error ? error.message : translate('views.tasks.msg.unknownError')
          }))
        }
      })
      publishWatchers.set(key, dispose)
      // 360s 墙钟：与 watchJob 内部定时器独立；非终态到达 ⇒ 单次提示 + 收摊
      wallClockTimer = setTimeout(() => {
        wallClockTimer = null
        notifyJobWatch(translate('views.tasks.msg.watchTimeout', { jobId }))
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
      handlers: { onConflict?: (summary: string, issues?: ValidationIssue[]) => void } = {}
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
        notifyJobWatch(translate('views.tasks.msg.issuesLoadFailed', { jobId }))
        handlers.onConflict?.(`发布作业 #${jobId} 失败（发布冲突），校验问题明细拉取失败`)
        return
      }
      // 冲突项 = ERROR 级（沿袭原 publishConflict.issueKeys 口径）；无 ERROR 条目时降级用全量清单
      const conflictIssues = issues.filter((issue) => issue.severity === 'ERROR')
      const counted = conflictIssues.length > 0 ? conflictIssues : issues
      notifyJobWatch(translate('views.tasks.msg.conflictSummary', { count: counted.length }))
      handlers.onConflict?.(formatPublishConflictSummary(jobId, counted), counted)
    },

    /**
     * 一键恢复向导：按任务类型跳到对应向导页。
     * 类型未知时按提交的 taskType 兜底，仍未知则先拉一次任务详情再定。
     * （R-94：原 `currentTask?.type` 读点随死字段删除 —— 该值恒 null 恒走兜底，功能等效死码。）
     */
    async restoreWizard(taskId: number, taskType?: TaskType): Promise<RouteLocationRaw> {
      const type = taskType ?? (await this.ensureTaskType(taskId)) ?? 'EXPORT'
      if (type === 'IMPORT') {
        return { name: ROUTE_NAMES.importWizard, query: { taskId: String(taskId) } }
      }
      return { name: ROUTE_NAMES.exportWizard, query: { taskId: String(taskId) } }
    },

    /**
     * 同步版的向导目标（供 ui_event 分发使用）。
     *
     * 优先级：显式传入的类型 → **权威缓存**（`ensureTaskType` 拉过的那一份）→ 列表行 → EXPORT 兜底。
     * 权威缓存排在列表之前，是因为 `restore_task` 会先用 `ensureTaskType` 把类型钉住再分发事件
     * （导入任务不得因为列表未加载 / 分页不含该行而被送进导出向导）。
     */
    resolveWizardRoute(taskId: number, taskType?: TaskType): WizardRouteTarget {
      const cached = taskType
        ?? this.knownTaskTypes[taskId]
        ?? this.items.find((item) => item.task.id === taskId)?.task.type
        ?? 'EXPORT'
      if (cached === 'IMPORT') {
        return { name: ROUTE_NAMES.importWizard, query: { taskId: String(taskId) } }
      }
      return { name: ROUTE_NAMES.exportWizard, query: { taskId: String(taskId) } }
    },

    /**
     * **已知**任务类型（只读缓存，**不发请求**）：权威缓存 → 列表行。
     *
     * 供导航类动作做"不额外花一次 GET"的类型校正（`navigate_to(page, taskId)` 的页面以它为准）；
     * 需要"必须知道类型"的动作（`restore_task`）走 {@link ensureTaskType}。
     */
    knownWizardType(taskId: number): TaskType | null {
      return this.knownTaskTypes[taskId]
        ?? this.items.find((item) => item.task.id === taskId)?.task.type
        ?? null
    },

    /**
     * **权威任务类型**（`restore_task` / 带任务的导航的唯一依据）。
     *
     * 命中缓存（本列表行 / 之前拉过）⇒ 直接返回；否则拉 `GET /tasks/{id}` 并记入缓存。
     * **读取失败返回 `null`，不兜底成 EXPORT**：兜底会让导入任务在"列表未加载 / 瞬时失败"
     * 的那一拍被送进导出向导（"导入跳导出"的根因），调用方应据此给出可行动的回执，而不是替用户选错页。
     *
     * 有限退避（NAV3 同批）：首发 + 200ms + 400ms 共 **3 次**，只为跨过一次瞬时抖动
     * （网络 / 网关 / 5xx）—— **不轮询**，也不再延长上界。只重试**瞬时**读取失败：
     * 任务确定不存在（404 等确定性 4xx）立即收口，重试只会把"没有这条"拖成三次无谓请求。
     * 每次重试前重查缓存：退避期间别的调用点（列表加载 / 事件分发）可能已经把类型钉住。
     */
    async ensureTaskType(taskId: number): Promise<TaskType | null> {
      const known = this.knownWizardType(taskId)
      if (known) {
        this.knownTaskTypes[taskId] = known
        return known
      }
      const backoffMs = [200, 400]
      for (let attempt = 0; attempt <= backoffMs.length; attempt += 1) {
        if (attempt > 0) {
          await new Promise<void>((resolve) => {
            setTimeout(resolve, backoffMs[attempt - 1])
          })
          // 缓存优先：退避窗口里已被别处钉住的类型（权威缓存 / 列表行）直接复用，不再发请求
          const pinned = this.knownWizardType(taskId)
          if (pinned) {
            this.knownTaskTypes[taskId] = pinned
            return pinned
          }
        }
        try {
          const task = await tasksApi.getTask(taskId)
          if (!task || (task.type !== 'EXPORT' && task.type !== 'IMPORT')) {
            return null
          }
          this.knownTaskTypes[taskId] = task.type
          return task.type
        } catch (error) {
          // 确定性不存在（404 等）：重试同一条也不会变，立即 fail-closed
          if (!isTransientTaskReadFailure(error)) {
            return null
          }
        }
      }
      return null
    },

    async fetchTaskType(taskId: number): Promise<TaskType> {
      return (await this.ensureTaskType(taskId)) ?? 'EXPORT'
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
