/**
 * 手动刷新批（S4，M4 + F4）→ R-94 范围 B：`watchPublishOutcome` 复用 `utils/job-watch.ts`，
 * 原 Q4 `publishConflict/issues` 死字段族清除，发布冲突改走双通道落点。
 *
 * 本 spec 锁的契约（假时钟 + 受控 http.get + EventSource 全局桩）：
 * - **事件驱动**：发布跟踪 = watchJob（SSE 主通道 + 3s 兜底 + 帧空闲看门狗），终态判定以快照为准；
 * - **360s 墙钟 = 独立定时器**：非终态到达 ⇒ 提示恰好 1 次 + 收摊；终态到达 ⇒ 墙钟**即清**；
 * - **watchingJobId 闸（F4）**：旧作业晚到的终态不覆盖/清空新作业的冲突出口；
 * - **注册表归属单一**：同 taskId+jobId 的 publish watcher 不得双开；
 * - **取消链路 = dispose 形态（C1，唯一机制）**：轮次收尾调用即静默收摊（不弹提示、不上抛）；
 * - **R-94 双通道**：FAILED 终态 ⇒ 拉 issues 组明细摘要 —— ① onConflict 回调（AI 保证通道，
 *   run_job 分支注入，UiEventResult 同形回灌）；② ElMessage 瞬时提示（不承载明细）；
 *   摘要逐条列前 5 条 + 「共 N 处」。
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

function jobPollCount(): number {
  return http.get.mock.calls.filter(([url]) => String(url).startsWith('/jobs/')).length
}

/** 推进假时钟越过 360s 墙钟（分片推进，逼出链式定时器）：5 × 80s = 400s。 */
async function advancePastWallClock(): Promise<void> {
  for (let i = 0; i < 5; i += 1) {
    await vi.advanceTimersByTimeAsync(80_000)
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  http.get.mockReset()
  Object.values(toast).forEach((spy) => spy.mockReset())
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

describe('stores/task.ts · watchPublishOutcome 复用 watchJob（M4：无裸轮询）→ R-94 双通道出口', () => {
  it('wall-clock-const-360s：发布跟踪墙钟 = 360_000ms（独立定时器口径）', () => {
    expect(PUBLISH_WATCH_WALL_CLOCK_MS).toBe(360_000)
  })

  it('failed-dual-channel：开局快照即 FAILED ⇒ 单次 ElMessage + onConflict 收明细摘要（共 N 处）', async () => {
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
    const onConflict = vi.fn()

    const dispose = store.watchPublishOutcome(3, 9, { onConflict })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)

    // 用户瞬时通道：单次提示（F5），文案不承载明细（R-94 范围 B ②）
    expect(toast.warning).toHaveBeenCalledTimes(1)
    expect(toast.warning).toHaveBeenCalledWith('发布冲突：1 处，明细见 AI 面板回执')
    // AI 保证通道：明细摘要（逐条 + 共 N 处）经 onConflict 出口
    expect(onConflict).toHaveBeenCalledTimes(1)
    const summary = onConflict.mock.calls[0][0] as string
    expect(summary).toContain('发布作业 #9 校验冲突明细')
    expect(summary).toContain('1. DEF_A#R1：发布冲突明细')
    expect(summary).toContain('（共 1 处）')
    expect(typeof dispose).toBe('function') // 返回 dispose（调用方持有）
  })

  it('conflict-summary-truncates-at-5：明细摘要只列前 5 条 + 「共 N 处」截断标注', async () => {
    const issues = Array.from({ length: 7 }, (_, i) => ({
      severity: 'ERROR', defCode: `DEF_${i + 1}`, rowKey: `R${i + 1}`, message: `冲突 ${i + 1}`
    }))
    http.get.mockImplementation((url: string) => {
      if (url.includes('/issues')) {
        return Promise.resolve({ content: issues, totalElements: 7 })
      }
      return Promise.resolve(makeJob('FAILED'))
    })
    const store = useTaskStore()
    const onConflict = vi.fn()

    store.watchPublishOutcome(3, 9, { onConflict })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)

    expect(toast.warning).toHaveBeenCalledWith('发布冲突：7 处，明细见 AI 面板回执')
    const summary = onConflict.mock.calls[0][0] as string
    expect(summary).toContain('5. DEF_5#R5：冲突 5')
    expect(summary).not.toContain('6. DEF_6')
    // S6 批1（v7.10 N8）：首行 = 人话摘要（卡面 reason 行只显这一句），明细仍在字符串里供表格解析
    expect(summary.split('\n')[0]).toBe('发布作业 #9 校验冲突明细（共 7 处，仅列前 5 条）')
  })

  it('completed-no-prompt：非 FAILED 终态 ⇒ 零提示、onConflict 不出', async () => {
    http.get.mockResolvedValue(makeJob('COMPLETED'))
    const store = useTaskStore()
    const onConflict = vi.fn()

    store.watchPublishOutcome(3, 9, { onConflict })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)

    expect(toast.warning).not.toHaveBeenCalled()
    expect(onConflict).not.toHaveBeenCalled()
  })

  it('wall-clock-360s-exit：360s 未终态 ⇒ 超时提示恰好 1 次、watcher 收摊（帧不再驱动取数）', async () => {
    http.get.mockResolvedValue(makeJob('RUNNING'))
    const store = useTaskStore()

    store.watchPublishOutcome(3, 9)
    await vi.advanceTimersByTimeAsync(0)
    await advancePastWallClock()
    await vi.advanceTimersByTimeAsync(0)

    expect(toast.warning).toHaveBeenCalledTimes(1)
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('等待超时'))

    const pollsAtExit = jobPollCount()
    lastSource().emit('JOB_DONE', { jobId: 9, status: 'COMPLETED' }) // 已收摊：帧不再驱动取数
    await vi.advanceTimersByTimeAsync(10_000)
    expect(jobPollCount()).toBe(pollsAtExit)
  })

  it('wall-clock-cleared-on-final：FAILED 终态到达即清墙钟 —— 之后再推进超 360s 也不重复提示', async () => {
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
    expect(toast.warning).toHaveBeenCalledTimes(1) // 失败提示恰好 1 次（空清单 ⇒ 0 处形态）
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('发布冲突'))

    await advancePastWallClock() // 远超 360s：墙钟已清 ⇒ 不再超时提示
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

  it('stale-outcome-gated（F4）：并发两条跟踪，旧作业晚到终态不得再走冲突出口（闸在 onFinal 内）', async () => {
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
    const staleConflict = vi.fn()
    const currentConflict = vi.fn()

    const stale = store.watchPublishOutcome(3, 9, { onConflict: staleConflict }) // 旧作业（慢）
    const current = store.watchPublishOutcome(3, 10, { onConflict: currentConflict }) // 新作业（快）—— 占据闸位
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(0)
    // 新作业冲突已走出口（明细摘要 + 单次提示）
    expect(currentConflict).toHaveBeenCalledTimes(1)
    expect(currentConflict.mock.calls[0][0]).toContain('DEF_X')
    expect(toast.warning).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10_000) // 旧作业兜底 tick 拉到终态（晚到结局）
    await vi.advanceTimersByTimeAsync(0)
    expect(staleConflict).not.toHaveBeenCalled() // 闸生效：旧结局不得再走冲突出口
    expect(toast.warning).toHaveBeenCalledTimes(1) // 提示仍只 1 次
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
})

function lastSource(): EventSourceStub {
  const source = EventSourceStub.instances.at(-1)
  if (!source) {
    throw new Error('EventSource 桩未创建')
  }
  return source
}
