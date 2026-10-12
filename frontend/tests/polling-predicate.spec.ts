/**
 * 手动刷新批（S1）：任务中心的**条件自刷谓词（hasActiveJob）与订阅池一并摘除**（M1）后，
 * 本文件不再锁谓词口径，只锁 `refreshNow()` **手动刷新通道**（F2 保留面）：
 * - 闲置状态也照拉一次（绕过任何谓词 —— 现在根本不存在谓词）；
 * - 被 loading 挡住的 refreshNow 合并为恰好 1 次补发（在途那次 load 的 finally 收口）。
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

describe('stores/task.ts · refreshNow()（手动刷新通道：绕过一切周期机制 + loading 合并补发）', () => {
  it('refresh-now-bypasses-idle-predicate：全程闲置也照拉一次', async () => {
    const store = useTaskStore()
    store.items = [makeSummary(1, 'COMPLETED')]

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
