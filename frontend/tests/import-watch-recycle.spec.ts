/**
 * 轮询治理一期 · 步1（第三个 spec）：锁「同 kind 回收旧 watcher」+「dispose 后不写回在途快照」。
 *
 * 现状问题：导入向导的 check/import/publish 三条作业跟踪只往 `stopWatchers` 里堆，
 * 同一个 kind 重跑（例如再次「预检查」）时旧 watcher 仍在跑 ——
 *  ① 它继续按旧 jobId 打后端；
 *  ② 更糟的是 `syncSnapshot` 拿到的**旧作业**快照会写回**新作业**的展示位，
 *    甚至按旧作业的终态弹提示 / 触发 onFinal（"上个作业的结局盖掉这个作业"）。
 *
 * 步1 的修法：
 *  - 回收表（createJobWatcherRegistry）挂在模块可导出位置：同 kind register 先回收旧的，
 *    不同 kind 互不回收（三条可并存）；
 *  - job-watch.ts 的 syncSnapshot 在 `await getJob` 之后复检 `disposed`，
 *    保证 dispose 后的在途快照既不写回 onSnapshot 也不触发 onFinal。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getJob: vi.fn(), openTaskEvents: vi.fn() }))

vi.mock('@/api/jobs', () => ({ jobsApi: { getJob: api.getJob } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { openTaskEvents: api.openTaskEvents } }))

import { createJobWatcherRegistry, watchJob, JOB_FALLBACK_POLL_MS } from '@/utils/job-watch'
import type { Job, JobStatus } from '@/types/job'

function makeJob(id: number, status: JobStatus): Job {
  return {
    id,
    taskId: 1,
    jobType: 'IMPORT',
    status,
    progress: 0,
    total: 0,
    errorCount: 0,
    warningCount: 0,
    items: []
  }
}

/** watchJob 只用到 EventSource 的 onopen/onerror/close/readyState 这几个面。 */
function fakeSource(): { onopen: (() => void) | null; onerror: (() => void) | null; readyState: number; close: () => void } {
  return { onopen: null, onerror: null, readyState: 1, close: vi.fn() }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

let registry: ReturnType<typeof createJobWatcherRegistry>

beforeEach(() => {
  registry = createJobWatcherRegistry()
  api.getJob.mockReset()
  api.openTaskEvents.mockReset().mockImplementation(() => fakeSource())
  vi.useFakeTimers()
})

afterEach(() => {
  registry.disposeAll()
  vi.useRealTimers()
})

describe('job-watch 回收表 · 同 kind 回收 / 不同 kind 并存', () => {
  it('same-kind-old-disposed：同 kind 连启 2 次 ⇒ 旧 dispose 被调用，且旧 watcher 不再取数', async () => {
    api.getJob.mockResolvedValue(makeJob(101, 'RUNNING'))

    const disposeA = vi.fn(watchJob({ taskId: 1, jobId: 101 }))
    registry.register('check', disposeA)
    const disposeB = vi.fn(watchJob({ taskId: 1, jobId: 102 }))
    registry.register('check', disposeB)

    expect(disposeA).toHaveBeenCalledTimes(1)
    expect(disposeB).not.toHaveBeenCalled()
    expect(registry.size()).toBe(1)

    api.getJob.mockClear()
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS * 3)
    const polled = api.getJob.mock.calls.map(([jobId]) => jobId)
    expect(polled.length).toBeGreaterThan(0)
    expect(polled.every((jobId) => jobId === 102)).toBe(true)
  })

  it('inflight-resolve-not-written-back：同 kind 回收后旧 job 的在途 getJob resolve ⇒ 不写回、不弹旧终态', async () => {
    const slowA = deferred<Job>()
    const slowB = deferred<Job>()
    api.getJob.mockImplementation((jobId: number) => (jobId === 201 ? slowA.promise : slowB.promise))

    const onSnapshotA = vi.fn()
    const onFinalA = vi.fn()
    // watcher A：getJob 挂住（在途）
    registry.register('import', watchJob({ taskId: 1, jobId: 201, onSnapshot: onSnapshotA, onFinal: onFinalA }))
    expect(api.getJob).toHaveBeenCalledWith(201)

    const onSnapshotB = vi.fn()
    registry.register('import', watchJob({ taskId: 1, jobId: 202, onSnapshot: onSnapshotB }))

    // A 已随同 kind 回收；此刻它的在途快照才回来（终态 COMPLETED —— 若不复检 disposed 就会写回并弹旧终态）
    slowA.resolve(makeJob(201, 'COMPLETED'))
    slowB.resolve(makeJob(202, 'RUNNING'))
    await vi.advanceTimersByTimeAsync(0)

    expect(onSnapshotA).not.toHaveBeenCalled()
    expect(onFinalA).not.toHaveBeenCalled()
    expect(onSnapshotB).toHaveBeenCalledTimes(1)
    expect(onSnapshotB.mock.calls[0]?.[0]?.id).toBe(202)
  })

  it('different-kind-independent：不同 kind 并存互不回收', async () => {
    api.getJob.mockResolvedValue(makeJob(301, 'RUNNING'))

    const disposeCheck = vi.fn(watchJob({ taskId: 1, jobId: 301 }))
    const disposeImport = vi.fn(watchJob({ taskId: 1, jobId: 302 }))
    const disposePublish = vi.fn(watchJob({ taskId: 1, jobId: 303 }))
    registry.register('check', disposeCheck)
    registry.register('import', disposeImport)
    registry.register('publish', disposePublish)

    expect(disposeCheck).not.toHaveBeenCalled()
    expect(disposeImport).not.toHaveBeenCalled()
    expect(disposePublish).not.toHaveBeenCalled()
    expect(registry.size()).toBe(3)

    api.getJob.mockClear()
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS * 2)
    const ids = new Set(api.getJob.mock.calls.map(([jobId]) => jobId))
    expect(ids).toEqual(new Set([301, 302, 303]))
  })

  it('unmount-collects-all：卸载回收全部（每个恰好 1 次），之后无残留计时器与取数', async () => {
    api.getJob.mockResolvedValue(makeJob(401, 'RUNNING'))

    const disposeCheck = vi.fn(watchJob({ taskId: 1, jobId: 401 }))
    const disposeImport = vi.fn(watchJob({ taskId: 1, jobId: 402 }))
    registry.register('check', disposeCheck)
    registry.register('import', disposeImport)
    await vi.advanceTimersByTimeAsync(0)

    registry.disposeAll()

    expect(disposeCheck).toHaveBeenCalledTimes(1)
    expect(disposeImport).toHaveBeenCalledTimes(1)
    expect(registry.size()).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    api.getJob.mockClear()
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS * 5)
    expect(api.getJob).not.toHaveBeenCalled()
  })

  it('self-dispose-syncs-registry：watcher 自制 dispose（终态）后注册表同步注销（F10：has()/size() 不失真）', async () => {
    api.getJob.mockResolvedValue(makeJob(501, 'RUNNING'))
    // 与 ImportWizardView.beginWatchJob 同口径接线：onDisposed 同步回收注册表
    const dispose = watchJob({ taskId: 1, jobId: 501, onDisposed: () => registry.recycle('check') })
    registry.register('check', dispose)
    expect(registry.has('check')).toBe(true)
    expect(registry.size()).toBe(1)

    // 跟踪器自制终态：快照说终态 ⇒ 内部 dispose（不经注册表）
    api.getJob.mockResolvedValue(makeJob(501, 'COMPLETED'))
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS)
    await vi.advanceTimersByTimeAsync(0)

    expect(registry.has('check')).toBe(false) // 自制 dispose 后注册表同步注销
    expect(registry.size()).toBe(0)

    // 用户侧再回收/卸载：幂等，不重复调用、不报错
    registry.recycle('check')
    registry.disposeAll()
    expect(registry.size()).toBe(0)
  })

  it('onerror-not-fired-after-dispose：dispose 后在途快照失败的 catch 面不得再触 onError（F8：与 resolve 面的 disposed 复检对称）', async () => {
    const slow = deferred<Job>()
    api.getJob.mockReturnValueOnce(slow.promise) // 开局首拉挂住（在途）
    const onError = vi.fn()
    const onSnapshot = vi.fn()
    const dispose = watchJob({ taskId: 1, jobId: 601, onError, onSnapshot })
    registry.register('check', dispose)
    expect(api.getJob).toHaveBeenCalledWith(601)

    dispose() // 在途期间被回收（同 kind 重跑/卸载）

    slow.reject(new Error('network down')) // 在途快照此刻才失败
    await vi.advanceTimersByTimeAsync(0)

    expect(onError).not.toHaveBeenCalled() // disposed 后 catch 面复检：不再透传 onError
    expect(onSnapshot).not.toHaveBeenCalled()
  })
})
