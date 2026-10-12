/**
 * 轮询治理一期 · 步3（第一个 spec）：`utils/task-events.ts`（任务中心订阅池）。
 *
 * 现状问题：任务中心列表只有定时轮询，`TASK_CHANGED` / `JOB_DONE` 这类事件**零消费** ——
 * 别处（AI、向导页）改了任务状态，列表要等下一个 4s/30s tick 才知道；而如果为了即时性
 * 给每个在跑任务开一条 SSE，那 5 个在跑任务就是 5 条长连接（翻页/筛选/终态/卸载任一漏回收
 * 就累积，R3-1 面）。本 spec 锁的契约：
 * - **上限与淘汰（T8 终态 + F9）**：上限内按 `taskId` **降序**取前 3（保最新行），第 4+ 个不订阅；
 *   同 `taskId`
 *   去重；重订阅（sync）不重建已有实例 ⇒ 淘汰顺序稳定；
 * - **四个出口单调收敛（D5）**：筛选 / 翻页 / 作业终态（都经 `load()` 落地 resync）+ 卸载
 *   （显式 `closeTaskEvents`）后计数单调下降到 0；
 * - **可观测计数（T10 论据）**：store 暴露的订阅数与 `EventSource` 桩的**存活实例数**一致（无泄漏）；
 * - **dirty flag 收尾补发（D9 终态，口径②）**：事件到达时正有 `load()` 在途 ⇒ 本次被挡掉但
 *   **标记待办**，在途结束后**补发恰好 1 次**；在途期间 N 个事件叠加**合并为 1 次**。
 *
 * 桩件口径：`api/tasks.ts:106` 是**内部直接 `new EventSource`**（非注入）⇒ 只能把自写桩挂
 * `globalThis.EventSource`（jsdom 30 无内置；挂法同 `tests/setup.ts` 的 ResizeObserverStub）。
 * 只替换 `tasksApi.listTasks`（网络面），`openTaskEvents` 走**真实实现** —— 桩实例数即是
 * "真实 EventSource 实例数"的可观测面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({ listTasks: vi.fn() }))

vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, tasksApi: { ...actual.tasksApi, listTasks: api.listTasks } }
})

import { useTaskStore } from '@/stores/task'
import { TASK_EVENT_SUBSCRIPTION_LIMIT, createTaskEventsPool } from '@/utils/task-events'
import type { Job, JobStatus } from '@/types/job'
import type { PageResult } from '@/types/api'
import type { TaskEventType } from '@/types/sse'
import type { TaskSummary } from '@/types/task'

/** EventSource 自写桩：实例登记 + readyState + close() + 帧投递（onmessage）。 */
class EventSourceStub {
  static instances: EventSourceStub[] = []
  static readonly OPEN = 1
  static readonly CONNECTING = 0
  static readonly CLOSED = 2

  static live(): EventSourceStub[] {
    return EventSourceStub.instances.filter((instance) => !instance.closed)
  }

  static liveForTask(taskId: number): EventSourceStub[] {
    return EventSourceStub.live().filter((instance) => instance.taskId() === taskId)
  }

  static liveTaskIds(): number[] {
    return EventSourceStub.live().map((instance) => instance.taskId()).sort((a, b) => a - b)
  }

  readyState = EventSourceStub.CONNECTING
  closed = false
  onopen: ((ev: Event) => void) | null = null
  onmessage: ((ev: MessageEvent<string>) => void) | null = null
  onerror: ((ev: Event) => void) | null = null

  constructor(readonly url: string) {
    EventSourceStub.instances.push(this)
  }

  close(): void {
    this.closed = true
    this.readyState = EventSourceStub.CLOSED
  }

  /** 从 `/api/tasks/{id}/events` 解出订阅的任务 id（断言淘汰顺序用）。 */
  taskId(): number {
    const matched = /\/tasks\/(\d+)\/events/.exec(this.url)
    return matched ? Number(matched[1]) : Number.NaN
  }

  emit(type: TaskEventType, data: unknown = {}): void {
    this.onmessage?.({ data: JSON.stringify({ type, data }) } as MessageEvent<string>)
  }

  fireOpen(): void {
    this.readyState = EventSourceStub.OPEN
    this.onopen?.(new Event('open'))
  }

  fireError(): void {
    this.readyState = EventSourceStub.CONNECTING
    this.onerror?.(new Event('error'))
  }
}

function makeJob(taskId: number, status: JobStatus, jobId = taskId * 10): Job {
  return {
    id: jobId,
    taskId,
    jobType: 'IMPORT',
    status,
    progress: 0,
    total: 0,
    errorCount: 0,
    warningCount: 0,
    items: []
  }
}

function makeSummary(taskId: number, status: JobStatus): TaskSummary {
  return {
    task: {
      id: taskId,
      type: 'IMPORT',
      title: `任务 ${taskId}`,
      currentStep: 'UPLOAD',
      status: 'ACTIVE',
      items: []
    },
    itemCount: 0,
    fileCount: 0,
    latestJob: makeJob(taskId, status)
  }
}

function pageOf(items: TaskSummary[]): PageResult<TaskSummary> {
  return {
    content: items,
    totalElements: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    number: 0,
    size: items.length,
    numberOfElements: items.length,
    first: true,
    last: true,
    empty: items.length === 0
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

/** 让挂起的 promise 链（load → finally → 补发 load）全部落地。 */
async function flush(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await vi.advanceTimersByTimeAsync(0)
  }
}

let store: ReturnType<typeof useTaskStore>

beforeEach(() => {
  setActivePinia(createPinia())
  store = useTaskStore()
  EventSourceStub.instances = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = EventSourceStub
  api.listTasks.mockReset()
  api.listTasks.mockResolvedValue(pageOf([]))
  vi.useFakeTimers()
})

afterEach(() => {
  store.closeTaskEvents()
  store.stopAutoRefresh()
  vi.useRealTimers()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('utils/task-events.ts · 订阅上限 / 淘汰顺序 / 去重 / 计数', () => {  it('subscribe-cap-3-and-eviction-order：5 个在跑任务 ⇒ 活跃实例 ≤3、第 1/2 个（最老行）无实例、按 taskId 降序取前 3（保最新行，F9）、重订阅顺序稳定', async () => {
    expect(TASK_EVENT_SUBSCRIPTION_LIMIT).toBe(3)
    store.items = [1, 2, 3, 4, 5].map((taskId) => makeSummary(taskId, 'RUNNING'))

    store.startTaskEvents()
    await flush()

    expect(EventSourceStub.live().length).toBeLessThanOrEqual(3)
    // F9 降序取前 3（列表 createdAt DESC, id DESC ⇒ 大行号 = 最新行）：订阅 {5,4,3}
    expect(EventSourceStub.liveTaskIds()).toEqual([3, 4, 5])
    expect(EventSourceStub.liveForTask(1)).toHaveLength(0) // 第 1 个（最老行）不订阅
    expect(EventSourceStub.liveForTask(2)).toHaveLength(0)
    expect(store.taskEventSubscriptionCount).toBe(3)

    // 重订阅：已有实例不重建（无 churn），淘汰顺序稳定
    const created = EventSourceStub.instances.length
    store.syncTaskEvents()
    await flush()
    expect(EventSourceStub.liveTaskIds()).toEqual([3, 4, 5])
    expect(EventSourceStub.instances.length).toBe(created)

    // 最新行（5）退出在跑集合 ⇒ 名额让给原来排第 4 的 2；顺序仍按 taskId 降序
    store.items = [2, 3, 4].map((taskId) => makeSummary(taskId, 'RUNNING'))
    store.syncTaskEvents()
    await flush()
    expect(EventSourceStub.liveTaskIds()).toEqual([2, 3, 4])
  })

  it('four-exits-monotonic-close：筛选 / 翻页 / 作业终态 / 卸载四出口各触发后，计数单调下降至 0', async () => {
    const page: TaskSummary[] = [1, 2, 3, 4, 5].map((taskId) => makeSummary(taskId, 'RUNNING'))
    api.listTasks.mockImplementation(() => Promise.resolve(pageOf([...page])))

    // 入页：当前页 5 个在跑 → 上限 3（F9 降序保最新行：订阅 {5,4,3}）
    store.startTaskEvents()
    store.items = [...page]
    store.syncTaskEvents()
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(3)

    // 出口① 作业终态：JOB_DONE 帧 → refreshNow → load；新一页只剩 2 个在跑
    const doneSource = EventSourceStub.liveForTask(5)[0]
    expect(doneSource).toBeDefined()
    page.splice(0, page.length, makeSummary(2, 'RUNNING'), makeSummary(3, 'RUNNING'))
    doneSource?.emit('JOB_DONE', { jobId: 50, status: 'COMPLETED' })
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(2)
    expect(EventSourceStub.liveTaskIds()).toEqual([2, 3])

    // 出口② 翻页：新一页只剩 1 个在跑
    page.splice(0, page.length, makeSummary(3, 'RUNNING'))
    store.setPage(1)
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(1)
    expect(EventSourceStub.liveTaskIds()).toEqual([3])

    // 出口③ 筛选：新一页无在跑作业
    page.splice(0, page.length)
    store.applyFilters({ status: 'COMPLETED' })
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(0)
    expect(EventSourceStub.live()).toHaveLength(0)

    // 出口④ 卸载：跨层显式关闭全部（state 挂 store，跨路由存活 ⇒ 必须显式出口）
    page.splice(0, page.length, makeSummary(6, 'RUNNING'), makeSummary(7, 'RUNNING'))
    store.setPage(0)
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(2)

    store.closeTaskEvents()
    expect(store.taskEventSubscriptionCount).toBe(0)
    expect(EventSourceStub.live()).toHaveLength(0)
    expect(EventSourceStub.instances.every((instance) => instance.closed)).toBe(true)
  })

  it('counter-matches-stub：store 暴露的订阅数与 EventSource 桩存活实例数逐步一致（无泄漏）', async () => {
    store.items = [1, 2, 3, 4, 5].map((taskId) => makeSummary(taskId, 'RUNNING'))
    store.startTaskEvents()
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(EventSourceStub.live().length)

    store.items = [9].map((taskId) => makeSummary(taskId, 'RUNNING'))
    store.syncTaskEvents()
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(1)
    expect(store.taskEventSubscriptionCount).toBe(EventSourceStub.live().length)
    expect(EventSourceStub.live().length).toBe(
      EventSourceStub.instances.length - EventSourceStub.instances.filter((instance) => instance.closed).length
    )

    store.closeTaskEvents()
    expect(store.taskEventSubscriptionCount).toBe(0)
    expect(store.taskEventSubscriptionCount).toBe(EventSourceStub.live().length)
  })

  it('dedupe-same-taskid：同 taskId 重复订阅不产生重复实例（含 sync 幂等）', async () => {
    const pool = createTaskEventsPool({
      getTargets: () => [
        { taskId: 7, jobId: 70 },
        { taskId: 7, jobId: 70 },
        { taskId: 7, jobId: 70 }
      ],
      onProgress: () => {},
      onRefresh: () => {}
    })
    pool.sync()
    expect(EventSourceStub.instances).toHaveLength(1)
    expect(EventSourceStub.liveForTask(7)).toHaveLength(1)
    expect(pool.activeCount()).toBe(1)

    pool.sync()
    pool.sync()
    expect(EventSourceStub.instances).toHaveLength(1) // 不重建、不重复
    expect(pool.activeCount()).toBe(1)

    pool.closeAll()
    expect(pool.activeCount()).toBe(0)
    expect(EventSourceStub.live()).toHaveLength(0)
  })

  it('dead-channel-releases-slot：死通道（onerror 且未 OPEN）⇒ close 并释放名额；该任务标记走列表轮询兜底、不再重订阅（F6）', async () => {
    store.items = [1, 2, 3].map((taskId) => makeSummary(taskId, 'RUNNING'))
    store.startTaskEvents()
    await flush()
    expect(store.taskEventSubscriptionCount).toBe(3)

    const dead = EventSourceStub.liveForTask(2)[0]
    expect(dead).toBeDefined()
    dead?.fireError() // readyState=CONNECTING（≠ OPEN）⇒ 判死通道
    await flush()

    expect(dead?.closed).toBe(true) // 关闭
    expect(store.taskEventSubscriptionCount).toBe(2) // 释放名额
    expect(EventSourceStub.liveTaskIds()).toEqual([1, 3])

    // 标记走列表轮询兜底：下一次 sync 不给死通道任务重订阅（不再 ~3s 无限重连占名额）
    store.syncTaskEvents()
    await flush()
    expect(EventSourceStub.liveTaskIds()).toEqual([1, 3])
    expect(store.taskEventSubscriptionCount).toBe(2)

    // 死通道上再到的帧被 close-gate 丢弃，不触发刷新
    const before = api.listTasks.mock.calls.length
    dead?.emit('JOB_DONE', { jobId: 20, status: 'COMPLETED' })
    await flush()
    expect(api.listTasks.mock.calls.length).toBe(before)
  })

  it('applyjobprogress-jobid-guard：JOB_PROGRESS 的 jobId 与行内 latestJob.id 不一致 ⇒ 不直更（F7②：守卫真执行用例）', async () => {
    store.items = [makeSummary(1, 'RUNNING')] // latestJob.id = 10
    store.startTaskEvents()
    await flush()
    const source = EventSourceStub.liveForTask(1)[0]
    expect(source).toBeDefined()

    // 旧作业的进度帧（jobId 999 ≠ 10）：守卫必须拦住，不许盖到新作业展示位
    source?.emit('JOB_PROGRESS', { jobId: 999, taskId: 1, jobType: 'IMPORT', defCode: 'D', processed: 7, total: 10, pct: 70 })
    expect(store.items[0]?.latestJob?.progress).toBe(0)
    expect(store.items[0]?.latestJob?.total).toBe(0)

    // 匹配行内 latestJob.id 的帧：正常直更
    source?.emit('JOB_PROGRESS', { jobId: 10, taskId: 1, jobType: 'IMPORT', defCode: 'D', processed: 7, total: 10, pct: 70 })
    expect(store.items[0]?.latestJob?.progress).toBe(7)
    expect(store.items[0]?.latestJob?.total).toBe(10)
  })})

describe('utils/task-events.ts · 事件路径的 dirty flag 收尾补发（口径②）', () => {
  it('dirty-marked-inflight：在途 load 期间事件到达 ⇒ 本次被挡（调用数不增）但标记待办', async () => {
    const pending = deferred<PageResult<TaskSummary>>()
    api.listTasks.mockReturnValueOnce(pending.promise)
    store.items = [makeSummary(1, 'RUNNING')]
    store.startTaskEvents()
    store.startAutoRefresh() // F7④：走 startAutoRefresh 生产分支（controller 非 null）
    await flush()

    const loadPromise = store.load() // 在途（模拟筛选/翻页那次 load）
    await flush()
    expect(store.loading).toBe(true)
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    EventSourceStub.liveForTask(1)[0]?.emit('JOB_DONE', { jobId: 10, status: 'COMPLETED' })
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(1) // 事件路径撞在途：不并发打后端

    pending.resolve(pageOf([makeSummary(1, 'COMPLETED')]))
    await loadPromise
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(2) // 被挡的那次不丢弃：标记待办并补发
  })

  it('dirty-flush-exactly-once：在途结束后补发恰好 1 次（总数精确 = 2，不是 0 也不是 2 次额外）', async () => {
    const pending = deferred<PageResult<TaskSummary>>()
    api.listTasks.mockReturnValueOnce(pending.promise)
    store.items = [makeSummary(1, 'RUNNING')]
    store.startTaskEvents()
    store.startAutoRefresh() // F7④：走 startAutoRefresh 生产分支（controller 非 null）
    await flush()

    const loadPromise = store.load()
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    EventSourceStub.liveForTask(1)[0]?.emit('JOB_DONE', { jobId: 10, status: 'COMPLETED' })
    await flush()

    pending.resolve(pageOf([makeSummary(1, 'COMPLETED')]))
    await loadPromise
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(2) // 恰好 2（1 在途 + 1 收尾补发）

    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(2) // 补发落地后不再追加（不重复补）
  })

  it('dirty-merge-multiple：在途期间 N 个事件叠加 ⇒ 收尾仍只补发 1 次', async () => {
    const pending = deferred<PageResult<TaskSummary>>()
    api.listTasks.mockReturnValueOnce(pending.promise)
    store.items = [makeSummary(1, 'RUNNING'), makeSummary(2, 'RUNNING')]
    store.startTaskEvents()
    store.startAutoRefresh() // F7④：走 startAutoRefresh 生产分支（controller 非 null）
    await flush()

    const loadPromise = store.load()
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    const first = EventSourceStub.liveForTask(1)[0]
    const second = EventSourceStub.liveForTask(2)[0]
    first?.emit('JOB_DONE', { jobId: 10, status: 'COMPLETED' })
    second?.emit('TASK_CHANGED', { taskId: 2, version: 2, currentStep: 'UPLOAD', status: 'ACTIVE', summary: 'x' })
    first?.emit('TASK_CHANGED', { taskId: 1, version: 3, currentStep: 'UPLOAD', status: 'ACTIVE', summary: 'y' })
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    pending.resolve(pageOf([makeSummary(1, 'COMPLETED')]))
    await loadPromise
    await flush()
    expect(api.listTasks).toHaveBeenCalledTimes(2) // 3 个事件合并为 1 次补发
  })
})
