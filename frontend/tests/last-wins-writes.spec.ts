/**
 * last-wins（并发形态）回归：三处"旧响应/旧任务结果覆盖新现场"的收口。
 *
 * ① `stores/task.ts#load`：快速翻页不得因为"已有请求在途"把新请求丢掉；旧页响应晚到不得覆盖新页。
 * ② `stores/workspace.ts#dispatchUiEvent(goto_step)`：PUT 在途期间换了任务 ⇒ 不得把步骤写到新任务。
 * ③ `utils/frontend-tools.ts#executeFrontendTool` 预算到点：被抛弃的续体不得再写工作区（`setStep` /
 *    `recentActions`）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  listTasks: vi.fn(),
  goToStep: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  setImportMode: vi.fn(),
  getTask: vi.fn(),
  listJobs: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  getIssues: vi.fn()
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))

import { useTaskStore } from '@/stores/task'
import { dispatchUiEvent, useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'
import type { TaskSummary } from '@/types/task'

function makeSummary(taskId: number): TaskSummary {
  return {
    task: {
      id: taskId,
      type: 'EXPORT',
      title: `任务 ${taskId}`,
      currentStep: 'SELECT_DEFS',
      status: 'ACTIVE',
      items: []
    },
    itemCount: 0,
    fileCount: 0,
    latestJob: null
  }
}

function pageOf(ids: number[]): { content: TaskSummary[]; totalElements: number } {
  return { content: ids.map(makeSummary), totalElements: ids.length }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

async function flush(): Promise<void> {
  for (let i = 0; i < 6; i += 1) {
    await Promise.resolve()
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.listJobs.mockResolvedValue([])
})

describe('stores/task.ts · 快速翻页 last-wins', () => {
  it('第二次翻页照常发请求，旧页响应晚到不得覆盖新页', async () => {
    const store = useTaskStore()
    const first = deferred<{ content: TaskSummary[]; totalElements: number }>()
    const second = deferred<{ content: TaskSummary[]; totalElements: number }>()
    api.listTasks.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    store.setPage(1)
    store.setPage(2)

    // 修复前：第二次 load 撞上 loading 直接 return ⇒ 只有一个请求，页面停在新页码却显示旧数据
    expect(api.listTasks).toHaveBeenCalledTimes(2)
    expect(api.listTasks.mock.calls[0]?.[0]).toMatchObject({ page: 1 })
    expect(api.listTasks.mock.calls[1]?.[0]).toMatchObject({ page: 2 })

    second.resolve(pageOf([21, 22]))
    await flush()
    expect(store.items.map((item) => item.task.id)).toEqual([21, 22])

    first.resolve(pageOf([11, 12]))
    await flush()
    // 旧响应（第 1 页）晚到：不得把列表换回旧页
    expect(store.items.map((item) => item.task.id)).toEqual([21, 22])
    expect(store.total).toBe(2)
    expect(store.loading).toBe(false)
  })

  it('存在在途请求时 loading 不提前落下（等全部收尾）', async () => {
    const store = useTaskStore()
    const first = deferred<{ content: TaskSummary[]; totalElements: number }>()
    const second = deferred<{ content: TaskSummary[]; totalElements: number }>()
    api.listTasks.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    store.setPage(1)
    store.setPage(2)

    second.resolve(pageOf([21]))
    await flush()
    expect(store.loading).toBe(true) // 第 1 页仍在途

    first.resolve(pageOf([11]))
    await flush()
    expect(store.loading).toBe(false)
    expect(store.items.map((item) => item.task.id)).toEqual([21])
  })
})

describe('stores/workspace.ts · 旧任务结果不写新任务', () => {
  it('goto_step 在途换任务：步骤不写到新任务的工作区', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: 1,
      taskStatus: 'ACTIVE'
    })

    const pending = deferred<unknown>()
    api.goToStep.mockReturnValue(pending.promise)

    const done = dispatchUiEvent({ type: 'goto_step', step: 'QUERY_COND' })
    await flush()
    expect(api.goToStep).toHaveBeenCalledWith(1, 'QUERY_COND')

    // PUT 在途：用户/AI 换到了任务 2（新任务自己的步骤是 SELECT_DEFS）
    workspace.setTaskId(2)
    workspace.setStep('SELECT_DEFS')

    pending.resolve({})
    const result = await done

    expect(result.handled).toBe(false)
    expect(result.message).toContain('任务已切换')
    // 关键断言：任务 1 的步骤变更没有落到任务 2 的工作区状态上
    expect(workspace.taskId).toBe(2)
    expect(workspace.step).toBe('SELECT_DEFS')
  })

  it('select_defs 在途换任务：选中集不写到新任务的工作区', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: 1,
      taskStatus: 'ACTIVE',
      selectedDefs: ['A']
    })

    const pending = deferred<unknown>()
    api.selectDefs.mockReturnValue(pending.promise)

    const done = dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: ['B'] })
    await flush()
    expect(api.selectDefs).toHaveBeenCalledWith(1, ['B'])

    workspace.setTaskId(2)
    workspace.setSelectedDefs(['C'])

    pending.resolve({})
    const result = await done

    expect(result.handled).toBe(false)
    expect(workspace.selectedDefs).toEqual(['C'])
  })
})

describe('utils/frontend-tools.ts · 预算到点后不得继续写工作区', () => {
  it('超时回灌后，晚到的 goto_step 续体不写 step / 不记动作', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: 1,
      taskStatus: 'ACTIVE'
    })

    const pending = deferred<unknown>()
    api.goToStep.mockReturnValue(pending.promise)
    const actionsBefore = workspace.recentActions.length

    const execution = await executeFrontendTool({
      name: 'goto_step',
      args: { step: 'QUERY_COND' },
      budgetMs: 10
    })
    expect(execution.code).toBe('UI_BUDGET_EXHAUSTED')
    expect(execution.outcome).toBe('timeout')

    // 预算已到点：此时被抛弃的续体才拿到响应
    pending.resolve({ status: 'ACTIVE', currentStep: 'QUERY_COND' })
    await flush()

    expect(workspace.step).toBe('SELECT_DEFS')
    expect(workspace.recentActions.length).toBe(actionsBefore)
  })
})
