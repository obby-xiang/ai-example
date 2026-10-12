/**
 * 手动刷新批（S4，M4 + F4）：`watchPublishOutcome` 复用 `utils/job-watch.ts`，
 * 消灭 `api/jobs.ts` 的 1s 裸轮询 `waitForJobFinal`。
 *
 * 本 spec 锁的契约（假时钟 + 受控 http.get + EventSource 全局桩）：
 * - **事件驱动替代裸轮询**：发布跟踪 = watchJob（SSE 主通道 + 3s 兜底 + 帧空闲看门狗），
 *   终态判定以快照为准（watchJob onFinal 内做）；onSnapshot 接替旧 onTick 直更 currentJobs；
 * - **300s 墙钟 = 独立定时器**：非终态到达 ⇒ 提示恰好 1 次 + 保留现场 + 收摊；
 *   终态到达 ⇒ 墙钟**即清**（推进再久也不超时）；
 * - **watchingJobId 闸（F4）移植进 onFinal**：旧作业晚到的终态不覆盖/清空新冲突；
 * - **注册表归属单一**：同 taskId+jobId 的 publish watcher 不得双开（第二次调用返回同一 dispose）；
 * - **取消链路 = dispose 形态（C1 收口修，唯一机制）**：调用方持有 dispose，轮次收尾调用即静默
 *   收摊（不弹提示、不上抛、void 调用点安全）——生产可达路径即此，spec 用例直接锁它；
 * - FAILED 终态 ⇒ issues 拉取与 Q4 冲突组装落 onFinal 内，单次提示（F5 纪律不变）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const http = vi.hoisted(() => ({ get: vi.fn() }))
const toast = vi.hoisted(() => ({
  warning: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn()
}))
const tasksApiMock = vi.hoisted(() => ({
  listTasks: vi.fn(),
  getTaskOverview: vi.fn(),
  getTask: vi.fn()
}))

vi.mock('@/api/http', () => ({
  get: http.get,
  post: vi.fn(),
  del: vi.fn(),
  ApiError: class ApiError extends Error {}
}))
vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, tasksApi: { ...actual.tasksApi, ...tasksApiMock } }
})
vi.mock('element-plus', () => ({ ElMessage: toast }))

import { PUBLISH_WATCH_WALL_CLOCK_MS, useTaskStore } from '@/stores/task'
import type { Job, JobStatus } from '@/types/job'
import type { PublishConflictAsync } from '@/types/api'

/** EventSource 全局桩（watchJob 构造时 new；本批多数用例不需要帧，终态靠开局快照）。 */
class EventSourceStub {
  static instances: EventSourceStub[] = []
  static readonly OPEN = 1
  static readonly CONNECTING = 0
  static readonly CLOSED = 2
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
  emit(type: string, data: unknown = {}): void {
    this.onmessage?.({ data: JSON.stringify({ type, data }) } as MessageEvent<string>)
  }
  fireOpen(): void {
    this.readyState = EventSourceStub.OPEN
    this.onopen?.(new Event('open'))
  }
}

function makeJob(status: JobStatus, id = 9): Job {
  return {
    id,
    taskId: 3,
    jobType: 'PUBLISH',
    status,
    progress: 1,
    total: 10,
    errorCount: 0,
    warningCount: 0,
    items: []
  }
}

const OLD_CONFLICT: PublishConflictAsync = {
  jobId: 9,
  jobStatus: 'FAILED',
  issueKeys: ['DEF_A#1'],
  message: '上次发布的版本冲突'
}

function jobPollCount(): number {
  return http.get.mock.calls.filter(([url]) => String(url).startsWith('/jobs/')).length
}

/** 推进假时钟越过 300s 墙钟（分片推进，逼出链式定时器）。 */
async function advancePastWallClock(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await vi.advanceTimersByTimeAsync(80_000)
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  http.get.mockReset()
  Object.values(toast).forEach((spy) => spy.mockReset())
  tasksApiMock.getTaskOverview.mockReset()
  tasksApiMock.getTaskOverview.mockResolvedValue({ task: {}, jobs: [], files: [] })
  tasksApiMock.getTask.mockReset()
  tasksApiMock.getTask.mockResolvedValue({ type: 'EXPORT' })
  tasksApiMock.listTasks.mockReset()
  tasksApiMock.listTasks.mockResolvedValue({ content: [], totalElements: 0 })
  EventSourceStub.instances = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = EventSourceStub
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('stores/task.ts · watchPublishOutcome 复用 watchJob（M4：无 1s 裸轮询）', () => {
  it('wall-clock-const-300s：发布跟踪墙钟 = 300_000ms（独立定时器口径）', () => {
    expect(PUBLISH_WATCH_WALL_CLOCK_MS).toBe(300_000)
  })

  it('failed-writes-conflict-once：开局快照即 FAILED ⇒ onFinal 内拉 issues 组装 Q4 冲突，提示恰好 1 次', async () => {
    http.get.mockImplementation((url: string) => {
      if (url.includes('/issues')) {
        return Promise.resolve({
          content: [{ severity: 'ERROR', defCode: 'DEF_A', rowKey: 'R1', message: '发布冲突明细' }],
          totalElements: 1
        })
      }
      return Promise.resolve(makeJob('FAILED'))
    })
    const store = useTaskStore()

    const dispose = store.watchPublishOutcome(3, 9)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)

    expect(store.publishConflict).not.toBeNull()
    expect(store.publishConflict?.jobId).toBe(9)
    expect(store.publishConflict?.jobStatus).toBe('FAILED')
    expect(store.publishConflict?.message).toBe('发布冲突明细')
    expect(toast.warning).toHaveBeenCalledTimes(1) // F5：失败分支单次提示
    expect(typeof dispose).toBe('function') // 返回 dispose（调用方持有）
  })

  it('completed-clears-conflict：非 FAILED 终态 ⇒ 清冲突态、零提示', async () => {
    http.get.mockResolvedValue(makeJob('COMPLETED'))
    const store = useTaskStore()
    store.publishConflict = { ...OLD_CONFLICT }

    store.watchPublishOutcome(3, 9)
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)

    expect(store.publishConflict).toBeNull()
    expect(toast.warning).not.toHaveBeenCalled()
  })

  it('wall-clock-300s-exit：300s 未终态 ⇒ 超时提示恰好 1 次、保留现场、watcher 收摊', async () => {
    http.get.mockResolvedValue(makeJob('RUNNING'))
    const store = useTaskStore()
    store.publishConflict = { ...OLD_CONFLICT }

    store.watchPublishOutcome(3, 9)
    await vi.advanceTimersByTimeAsync(0)
    await advancePastWallClock()
    await vi.advanceTimersByTimeAsync(0)

    expect(store.publishConflict?.message).toBe(OLD_CONFLICT.message) // 不静默清冲突
    expect(toast.warning).toHaveBeenCalledTimes(1)
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('等待超时'))

    const pollsAtExit = jobPollCount()
    lastSource().emit('JOB_DONE', { jobId: 9, status: 'COMPLETED' }) // 已收摊：帧不再驱动取数
    await vi.advanceTimersByTimeAsync(10_000)
    expect(jobPollCount()).toBe(pollsAtExit)
  })

  it('wall-clock-cleared-on-final：终态到达即清墙钟 —— 之后再推进超 300s 也不报超时', async () => {
    let calls = 0
    http.get.mockImplementation((url: string) => {
      if (url.includes('/issues')) {
        return Promise.resolve({ content: [], totalElements: 0 })
      }
      calls += 1
      return Promise.resolve(makeJob(calls >= 2 ? 'FAILED' : 'RUNNING'))
    })
    const store = useTaskStore()

    store.watchPublishOutcome(3, 9)
    await vi.advanceTimersByTimeAsync(10_000) // 第二拉在 3s 兜底：FAILED 终态到达
    await vi.advanceTimersByTimeAsync(0)
    expect(store.publishConflict).not.toBeNull() // FAILED 且零 ERROR issues ⇒ 组装默认文案冲突
    expect(toast.warning).toHaveBeenCalledTimes(1) // 失败提示（非超时）

    await advancePastWallClock() // 远超 300s：墙钟已清 ⇒ 不再超时提示
    expect(toast.warning).toHaveBeenCalledTimes(1)
  })

  it('dispose-cleanup-no-unhandled：取消链路 = dispose 形态（C1，唯一机制）——轮次收尾调用即静默收摊、不上抛、不弹提示', async () => {
    const seen: unknown[] = []
    const onUnhandled = (reason: unknown): void => {
      seen.push(reason)
    }
    process.on('unhandledRejection', onUnhandled)
    try {
      http.get.mockResolvedValue(makeJob('RUNNING'))
      const store = useTaskStore()

      // 与 stores/workspace.ts run_job 同形态：void 调用、不 await
      const dispose = store.watchPublishOutcome(3, 9)
      await vi.advanceTimersByTimeAsync(0)
      expect(jobPollCount()).toBe(1)

      dispose() // 轮次收尾：生产可达的唯一取消链路
      await vi.advanceTimersByTimeAsync(0)
      expect(seen).toEqual([]) // 不上抛（void 调用点无 unhandled rejection）
      expect(toast.warning).not.toHaveBeenCalled() // dispose 静默收摊，不弹"已取消"
      expect(EventSourceStub.instances[0]?.closed).toBe(true) // 跟踪真收摊

      const polls = jobPollCount()
      await vi.advanceTimersByTimeAsync(10_000) // 收摊后兜底表停摆
      expect(jobPollCount()).toBe(polls)
    } finally {
      process.removeListener('unhandledRejection', onUnhandled)
    }
  })

  it('stale-outcome-gated（F4）：并发两条跟踪，旧作业晚到终态不覆盖/清空新冲突（闸移植进 onFinal）', async () => {
    let job9Calls = 0
    http.get.mockImplementation((url: string) => {
      if (url.includes('/issues')) {
        return Promise.resolve({
          content: [{ severity: 'ERROR', defCode: 'DEF_X', rowKey: 'R', message: '新冲突明细' }],
          totalElements: 1
        })
      }
      if (String(url).startsWith('/jobs/9')) {
        job9Calls += 1
        return Promise.resolve(makeJob(job9Calls >= 3 ? 'FAILED' : 'RUNNING', 9)) // 慢：第三次拉才终态
      }
      return Promise.resolve(makeJob('FAILED', 10)) // 快：开局即终态
    })
    const store = useTaskStore()

    const stale = store.watchPublishOutcome(3, 9) // 旧作业（慢）
    const current = store.watchPublishOutcome(3, 10) // 新作业（快）—— 占据 watchingJobId 闸位
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    expect(store.publishConflict?.jobId).toBe(10) // 新冲突已落位

    await vi.advanceTimersByTimeAsync(10_000) // 旧作业兜底 tick 拉到终态（晚到结局）
    await vi.advanceTimersByTimeAsync(0)
    expect(store.publishConflict?.jobId).toBe(10) // 闸生效：旧结局不得覆盖/清空
    stale()
    current()
  })

  it('single-registry-no-double-open（F4）：同 taskId+jobId 不得双开 —— 第二次调用返回同一 dispose，不起第二个 watcher', async () => {
    http.get.mockResolvedValue(makeJob('RUNNING'))
    const store = useTaskStore()

    const first = store.watchPublishOutcome(3, 9)
    const second = store.watchPublishOutcome(3, 9)
    await vi.advanceTimersByTimeAsync(0)

    expect(second).toBe(first) // 同一注册项：不双开
    expect(EventSourceStub.instances).toHaveLength(1)
    expect(jobPollCount()).toBe(1) // 只有一份开局快照
    first()
  })

  it('snapshot-updates-currentJobs：onSnapshot 接替旧 onTick 直更当前作业行', async () => {
    const running = { ...makeJob('RUNNING', 77), progress: 7, total: 10 }
    http.get.mockResolvedValue(running)
    const store = useTaskStore()
    store.currentTaskId = 3
    store.currentJobs = [{ ...makeJob('RUNNING', 77), progress: 1, total: 10 }]

    const dispose = store.watchPublishOutcome(3, 77) // 唯一键：不与前用例遗留注册项撞车
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)

    expect(store.currentJobs[0]?.progress).toBe(7)
    expect(store.currentJobs[0]?.id).toBe(77)
    dispose()
  })
})

function lastSource(): EventSourceStub {
  const source = EventSourceStub.instances.at(-1)
  if (!source) {
    throw new Error('EventSource 桩未创建')
  }
  return source
}
