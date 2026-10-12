/**
 * 轮询治理一期 · 步1（第二个 spec）：锁 api/jobs.ts 的等待结局 + stores/task.ts 的发布跟踪出口。
 *
 * 现状问题三条：
 *  ① `waitForJobFinal` 超时走 `return job` —— 返回的是一个**非终态**的 Job，调用方无法判别，
 *     于是「等待超时」被当成「正常拿到终态」，静默地清空发布冲突态；
 *  ② 等待被 signal 取消时抛的 Error 无类名，调用方只能靠文案猜；
 *  ③ `watchPublishOutcome` 的调用点是 stores/workspace.ts:452 的 `void` 调用 ——
 *     任何 rejection 上抛都变成 unhandled rejection（无提示、无落点）。
 *
 * 本 spec 用假时钟压掉 jobs.ts:66 的 `setTimeout(intervalMs)`，并让 http.get 受控返回，
 * 断言"可判别 + 恰好一次提示 + 不上抛"。
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
const tasksApiMock = vi.hoisted(() => ({ listTasks: vi.fn(), getTaskOverview: vi.fn(), getTask: vi.fn() }))

// 只替换数据面 get：waitForJobFinal 本身仍走**真实实现**（含 jobs.ts:66 的 setTimeout）。
vi.mock('@/api/http', () => ({
  get: http.get,
  post: vi.fn(),
  del: vi.fn(),
  ApiError: class ApiError extends Error {}
}))
vi.mock('@/api/tasks', () => ({ tasksApi: tasksApiMock }))
vi.mock('element-plus', () => ({ ElMessage: toast }))

import { waitForJobFinal } from '@/api/jobs'
import { useTaskStore } from '@/stores/task'
import type { Job, JobStatus } from '@/types/job'
import type { PublishConflictAsync } from '@/types/api'

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

/** 推进假时钟越过 300s 默认超时预算（分片推进，逼出链式 setTimeout）。 */
async function advancePastTimeout(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await vi.advanceTimersByTimeAsync(80_000)
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  http.get.mockReset()
  Object.values(toast).forEach((spy) => spy.mockReset())
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('api/jobs.ts · waitForJobFinal 的结局可判别', () => {
  it('abort-throws-recognizable：signal 已 aborted ⇒ 抛可识别错误（不返回"看起来正常"的 Job）', async () => {
    const ctrl = new AbortController()
    ctrl.abort()

    const error: unknown = await waitForJobFinal(9, { signal: ctrl.signal }).then(
      () => null,
      (reason: unknown) => reason
    )

    expect(error).toBeInstanceOf(Error)
    expect((error as Error).name).toBe('JobWaitAbortedError')
    expect((error as Error).message).toBe('等待作业终态已被取消')
    expect(http.get).not.toHaveBeenCalled()
  })

  it('timeout-not-silent-return：假时钟推进 > 300s ⇒ 可判别的超时结局，而不是裸 Job', async () => {
    http.get.mockResolvedValue(makeJob('RUNNING'))

    const pending = waitForJobFinal(9, { intervalMs: 1000 })
    await advancePastTimeout()
    const outcome = await pending

    // 旧实现 `return job` ⇒ 这里的判别字段不存在（RED 点）
    expect(outcome.status).toBe('timeout')
    expect(outcome.job.status).toBe('RUNNING')
    expect(http.get.mock.calls.length).toBeGreaterThan(1) // 确实轮询了不止一次
  })
})

describe('stores/task.ts · watchPublishOutcome 的单次出口', () => {
  it('no-silent-publishconflict-clear：非终态超时到达 ⇒ 不静默清空发布冲突态，且提示发生', async () => {
    const store = useTaskStore()
    store.publishConflict = { ...OLD_CONFLICT }
    http.get.mockResolvedValue(makeJob('RUNNING'))

    const pending = store.watchPublishOutcome(9)
    await advancePastTimeout()
    await pending

    expect(store.publishConflict).not.toBeNull()
    expect(store.publishConflict?.message).toBe(OLD_CONFLICT.message)
    expect(toast.warning).toHaveBeenCalledTimes(1)
  })

  it('abort-no-unhandled-rejection：abort 路径不上抛 rejection（void 调用点无 unhandled rejection）', async () => {
    vi.useRealTimers()
    const seen: unknown[] = []
    const onUnhandled = (reason: unknown): void => {
      seen.push(reason)
    }
    process.on('unhandledRejection', onUnhandled)
    try {
      const store = useTaskStore()
      const ctrl = new AbortController()
      ctrl.abort()

      // 与 stores/workspace.ts:452 同形态：调用方不 await、不 catch
      void store.watchPublishOutcome(9, ctrl.signal)
      await new Promise((resolve) => setTimeout(resolve, 20))

      expect(seen).toEqual([])
      await expect(store.watchPublishOutcome(9, ctrl.signal)).resolves.toBeUndefined()
      expect(toast.warning).toHaveBeenCalledTimes(2)
    } finally {
      process.removeListener('unhandledRejection', onUnhandled)
    }
  })

  it('timeout-notice-exactly-once：超时提示恰好 1 次，且与 onTick 次数解耦', async () => {
    const store = useTaskStore()
    http.get.mockResolvedValue(makeJob('RUNNING'))

    const pending = store.watchPublishOutcome(9)
    await advancePastTimeout()
    await pending

    expect(toast.warning).toHaveBeenCalledTimes(1)
    // onTick 每取一次快照就发声（每次取数都会进 onTick），提示不得跟着它走
    expect(http.get.mock.calls.length).toBeGreaterThan(5)
  })

  it('failed-outcome-notice-once：FAILED 结局 ⇒ 写 publishConflict 且提示恰好 1 次（F5：与 abort/timeout 同出口纪律）', async () => {
    const store = useTaskStore()
    tasksApiMock.getTaskOverview.mockResolvedValue({ task: {}, jobs: [], files: [] })
    http.get.mockImplementation((url: string) => {
      if (url.includes('/issues')) {
        return Promise.resolve({
          content: [{ severity: 'ERROR', defCode: 'DEF_A', rowKey: 'R1', message: '发布冲突明细' }],
          totalElements: 1
        })
      }
      return Promise.resolve(makeJob('FAILED'))
    })

    await store.watchPublishOutcome(9)

    expect(store.publishConflict).not.toBeNull()
    expect(store.publishConflict?.jobId).toBe(9)
    expect(store.publishConflict?.jobStatus).toBe('FAILED')
    expect(toast.warning).toHaveBeenCalledTimes(1) // 失败分支同样单次提示（此前独漏）
  })

  it('stale-outcome-gated：并发两条跟踪，旧作业晚到结局不覆盖/清空新冲突（F4：watchingJobId 闸）', async () => {
    const store = useTaskStore()
    tasksApiMock.getTaskOverview.mockResolvedValue({ task: {}, jobs: [], files: [] })
    let job9Final = false
    http.get.mockImplementation((url: string) => {
      if (url.includes('/issues')) {
        return Promise.resolve({
          content: [{ severity: 'ERROR', defCode: 'DEF_X', rowKey: 'R', message: '新冲突明细' }],
          totalElements: 1
        })
      }
      if (url.startsWith('/jobs/9')) {
        return Promise.resolve(makeJob(job9Final ? 'FAILED' : 'RUNNING', 9))
      }
      return Promise.resolve(makeJob('FAILED', 10))
    })

    const stale = store.watchPublishOutcome(9) // 先发起的旧作业跟踪（慢，还在 1s 轮询）
    const current = store.watchPublishOutcome(10) // 后发起的新作业跟踪（立即 FAILED 落冲突）

    await current
    expect(store.publishConflict?.jobId).toBe(10) // 新冲突已落位

    job9Final = true // 旧作业此刻才进终态（晚到结局）
    await vi.advanceTimersByTimeAsync(1000)
    await stale

    expect(store.publishConflict?.jobId).toBe(10) // 旧结局不得覆盖/清空新冲突
  })
})
