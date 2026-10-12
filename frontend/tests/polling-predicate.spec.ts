/**
 * 轮询治理一期 · 步1（RED 先行的第一个 spec）：锁 stores/task.ts 的条件轮询口径。
 *
 * 现状问题：任务中心 `startAutoRefresh` 每 4s **无条件** load()，页面开着就在持续打后端；
 * 步1 把口径改成「有在跑作业（RUNNING/PENDING）才 4s 刷」，并为事件驱动/手动刷新补一个
 * `refreshNow()`（绕过谓词，且被 loading 挡住时合并补发恰好 1 次）。
 *
 * 谓词口径的关键：以**当前 items**为准（后端已按筛选条件分页返回），
 * 所以"构造时全量有在跑行、筛选后当前页没有"必须判为 false —— 否则筛选视图会继续 4s 空转。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({ listTasks: vi.fn() }))

vi.mock('@/api/tasks', () => ({
  tasksApi: { listTasks: api.listTasks }
}))

import { useTaskStore } from '@/stores/task'
import type { Job, JobStatus } from '@/types/job'
import type { TaskSummary } from '@/types/task'

function makeJob(status: JobStatus, id: number): Job {
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

/** latestJob=null 表示该行尚无作业（后端 TaskSummary 的形态）。 */
function makeSummary(taskId: number, status: JobStatus | null): TaskSummary {
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
    latestJob: status === null ? null : makeJob(status, taskId * 10)
  }
}

function pageOf(items: TaskSummary[]): { content: TaskSummary[]; totalElements: number } {
  return { content: items, totalElements: items.length }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

beforeEach(() => {
  setActivePinia(createPinia())
  api.listTasks.mockReset()
})

describe('stores/task.ts · hasActiveJob 谓词（以当前 items 为准）', () => {
  it('predicate-all-idle：全部行 latestJob 为 COMPLETED 或 null ⇒ false', () => {
    const store = useTaskStore()
    store.items = [makeSummary(1, null), makeSummary(2, 'COMPLETED'), makeSummary(3, 'FAILED'), makeSummary(4, 'CANCELLED')]

    expect(store.hasActiveJob).toBe(false)
  })

  it('predicate-running-or-pending：任一行 RUNNING 或 PENDING ⇒ true', () => {
    const store = useTaskStore()
    store.items = [makeSummary(1, 'COMPLETED'), makeSummary(2, 'RUNNING')]
    expect(store.hasActiveJob).toBe(true)

    store.items = [makeSummary(1, null), makeSummary(2, 'PENDING')]
    expect(store.hasActiveJob).toBe(true)
  })

  it('predicate-filtered-scope：筛选后当前 items 无在跑行 ⇒ false（即使构造时全量有在跑行）', async () => {
    const store = useTaskStore()
    // 全量视图：任务 2 有 RUNNING 作业
    api.listTasks.mockResolvedValueOnce(pageOf([makeSummary(1, null), makeSummary(2, 'RUNNING')]))
    await store.load()
    expect(store.hasActiveJob).toBe(true)

    // 筛选视图（后端按 status=COMPLETED 过滤后返回）：当前页没有任何在跑行
    api.listTasks.mockResolvedValueOnce(pageOf([makeSummary(1, 'COMPLETED')]))
    store.applyFilters({ status: 'COMPLETED' })
    await vi.waitFor(() => expect(api.listTasks).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(store.hasActiveJob).toBe(false))
    expect(store.items.map((item) => item.task.id)).toEqual([1])
  })
})

describe('stores/task.ts · refreshNow()（绕过谓词 + dirty 合并补发）', () => {
  it('refresh-now-bypasses-idle-predicate：全程闲置也照拉一次', async () => {
    const store = useTaskStore()
    store.items = [makeSummary(1, 'COMPLETED')]
    expect(store.hasActiveJob).toBe(false)

    api.listTasks.mockResolvedValueOnce(pageOf([makeSummary(1, 'COMPLETED'), makeSummary(2, 'COMPLETED')]))
    await store.refreshNow()

    expect(api.listTasks).toHaveBeenCalledTimes(1)
    expect(store.items).toHaveLength(2)
  })

  it('refresh-now-coalesces-when-loading：被 loading 挡住的多次请求合并为恰好 1 次补发', async () => {
    const store = useTaskStore()
    const first = deferred<{ content: TaskSummary[]; totalElements: number }>()
    api.listTasks.mockReturnValueOnce(first.promise) // 第 1 次：挂住
    api.listTasks.mockResolvedValue(pageOf([makeSummary(9, 'COMPLETED')])) // 补发那次

    void store.load() // 在途 load（loading=true）
    expect(store.loading).toBe(true)

    // 三连请求全被 loading 挡住 ⇒ 只标记待办，不各自发起
    void store.refreshNow()
    void store.refreshNow()
    void store.refreshNow()
    await Promise.resolve()
    expect(api.listTasks).toHaveBeenCalledTimes(1)

    first.resolve(pageOf([makeSummary(1, null)]))
    await vi.waitFor(() => expect(api.listTasks).toHaveBeenCalledTimes(2)) // 恰好 1 次补发
    await vi.waitFor(() => expect(store.loading).toBe(false))
    expect(store.items.map((item) => item.task.id)).toEqual([9])
  })
})
