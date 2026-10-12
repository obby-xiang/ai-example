/**
 * 轮询治理一期 · 步1（第三个 spec）：锁「同 kind 回收旧 watcher」+「dispose 后不写回在途快照」。
 *
 * 现状问题：导入向导的 check/import/publish 三条作业跟踪只往 `stopWatchers` 里堆，
 * 同一个 kind 重跑（例如再次「预检查」）时旧 watcher 仍在跑 ——
 *  ① 它继续按旧 jobId 打后端；
 *  ② 更糟的是 `syncSnapshot` 拿到的**旧作业**快照会写回**新作业**的展示位，
 *    甚至按旧作业的终态弹提示 / 触发 onFinal（"上个作业的结局盖掉这个作业"）。
 *
 * R-148（B2 收窄）：`createJobWatcherRegistry` 已删（唯一生产消费者 = ImportWizardView，
 * 已改直调：模块级 `Map<kind, dispose>`，同 kind 先释放旧柄再登记，卸载全释放）。
 * 本 spec 改写为锁**直调形态背后的契约**：
 *  - 同 kind 重跑先释放旧柄（测试内以与视图同构的三行 wiring 驱动）⇒ 旧 watcher 停止取数；
 *  - dispose 后的在途快照既不写回 onSnapshot 也不触发 onFinal（job-watch.ts 的 disposed 复检，
 *    与注册表时代同一道闸）；
 *  - 卸载全释放 ⇒ 无残留计时器与取数；
 *  - 终态自制 dispose ⇒ onDisposed 回调链路（一次性、幂等）。
 * 登记（语义收窄说明）：原注册表的 has()/size() 簿记面随抽象一并删除，无生产消费者，
 * 「同 kind 先释放」的原子性现由视图 wiring 的 get→delete→dispose 顺序承载（dispose 期间
 * 重入 beginWatchJob 在单线程事件模型下不成立，无并发窗口）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getJob: vi.fn(), openTaskEvents: vi.fn() }))

vi.mock('@/api/jobs', () => ({ jobsApi: { getJob: api.getJob } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { openTaskEvents: api.openTaskEvents } }))

import { watchJob, JOB_FALLBACK_POLL_MS } from '@/utils/job-watch'
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

/** 与 ImportWizardView 改直调后同构的最小 wiring：同 kind 先释放旧柄再登记。 */
const handles = new Map<string, () => void>()
function beginDirect(kind: string, dispose: () => void): void {
  handles.get(kind)?.()
  handles.delete(kind)
  handles.set(kind, dispose)
}
function disposeAllDirect(): void {
  for (const dispose of [...handles.values()]) {
    dispose()
  }
  handles.clear()
}

beforeEach(() => {
  api.getJob.mockReset()
  api.openTaskEvents.mockReset().mockImplementation(() => fakeSource())
  vi.useFakeTimers()
})

afterEach(() => {
  disposeAllDirect()
  vi.useRealTimers()
})

describe('job-watch 直调回收 · 同 kind 回收 / 不同 kind 并存（R-148 B2 收窄后形态）', () => {
  it('same-kind-old-disposed：同 kind 连启 2 次 ⇒ 旧 dispose 被调用，且旧 watcher 不再取数', async () => {
    api.getJob.mockResolvedValue(makeJob(101, 'RUNNING'))

    const disposeA = vi.fn(watchJob({ taskId: 1, jobId: 101 }))
    beginDirect('check', disposeA)
    const disposeB = vi.fn(watchJob({ taskId: 1, jobId: 102 }))
    beginDirect('check', disposeB) // 同 kind 重跑：先释放 A 再登记 B

    expect(disposeA).toHaveBeenCalledTimes(1)
    expect(disposeB).not.toHaveBeenCalled()

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
    const disposeA = watchJob({ taskId: 1, jobId: 201, onSnapshot: onSnapshotA, onFinal: onFinalA })
    expect(api.getJob).toHaveBeenCalledWith(201)

    const onSnapshotB = vi.fn()
    beginDirect('import', disposeA)
    beginDirect('import', watchJob({ taskId: 1, jobId: 202, onSnapshot: onSnapshotB }))

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
    beginDirect('check', disposeCheck)
    beginDirect('import', disposeImport)
    beginDirect('publish', disposePublish)

    expect(disposeCheck).not.toHaveBeenCalled()
    expect(disposeImport).not.toHaveBeenCalled()
    expect(disposePublish).not.toHaveBeenCalled()

    api.getJob.mockClear()
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS * 2)
    const ids = new Set(api.getJob.mock.calls.map(([jobId]) => jobId))
    expect(ids).toEqual(new Set([301, 302, 303]))
  })

  it('unmount-collects-all：卸载回收全部（每个恰好 1 次），之后无残留计时器与取数', async () => {
    api.getJob.mockResolvedValue(makeJob(401, 'RUNNING'))

    const disposeCheck = vi.fn(watchJob({ taskId: 1, jobId: 401 }))
    const disposeImport = vi.fn(watchJob({ taskId: 1, jobId: 402 }))
    beginDirect('check', disposeCheck)
    beginDirect('import', disposeImport)
    await vi.advanceTimersByTimeAsync(0)

    disposeAllDirect()

    expect(disposeCheck).toHaveBeenCalledTimes(1)
    expect(disposeImport).toHaveBeenCalledTimes(1)
    expect(handles.size).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    api.getJob.mockClear()
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS * 5)
    expect(api.getJob).not.toHaveBeenCalled()
  })

  it('self-dispose-syncs-and-idempotent：watcher 自制 dispose（终态）触发 onDisposed 同步摘柄，重复回收幂等不双 dispose', async () => {
    api.getJob.mockResolvedValue(makeJob(501, 'RUNNING'))
    const onDisposed = vi.fn()
    // 与 ImportWizardView.beginWatchJob 同口径接线：onDisposed 同步摘柄
    const dispose = watchJob({
      taskId: 1,
      jobId: 501,
      onDisposed: () => {
        onDisposed()
        if (handles.get('check') === dispose) {
          handles.delete('check')
        }
      }
    })
    const kind = 'check'
    handles.set(kind, dispose)
    expect(handles.get(kind)).toBe(dispose)

    // 跟踪器自制终态：快照说终态 ⇒ 内部 dispose（不经登记入口）
    api.getJob.mockResolvedValue(makeJob(501, 'COMPLETED'))
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS)
    await vi.advanceTimersByTimeAsync(0)

    expect(onDisposed).toHaveBeenCalledTimes(1) // 自制 dispose 后 onDisposed 恰好一次
    expect(handles.get(kind)).toBeUndefined()

    // 用户侧再回收/卸载：幂等，不重复调用、不报错
    handles.get(kind)?.()
    handles.delete(kind)
    dispose()
    expect(onDisposed).toHaveBeenCalledTimes(1)
  })

  it('onerror-not-fired-after-dispose：dispose 后在途快照失败的 catch 面不得再触 onError（F8：与 resolve 面的 disposed 复检对称）', async () => {
    const slow = deferred<Job>()
    api.getJob.mockReturnValueOnce(slow.promise) // 开局首拉挂住（在途）
    const onError = vi.fn()
    const onSnapshot = vi.fn()
    const dispose = watchJob({ taskId: 1, jobId: 601, onError, onSnapshot })
    expect(api.getJob).toHaveBeenCalledWith(601)

    dispose() // 在途期间被回收（同 kind 重跑/卸载）

    slow.reject(new Error('network down')) // 在途快照此刻才失败
    await vi.advanceTimersByTimeAsync(0)

    expect(onError).not.toHaveBeenCalled() // disposed 后 catch 面复检：不再透传 onError
    expect(onSnapshot).not.toHaveBeenCalled()
  })
})
