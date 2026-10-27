/**
 * 批 A · A-01~A-05：`navigate_to` 的同页同参幂等直返 + 同参去重（D5-①③ / D4-②）。
 *
 * 现场（调研 54 R2）：模型对同一目标页反复导航，每次都被当成"已提交"，页面反复重挂。
 * 本组锁住两条确定性：**已在目标页 ⇒ 不再 push**；**同 run 同参 ⇒ 只执行一次**。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { useWorkspaceStore } from '@/stores/workspace'
import { useTaskStore } from '@/stores/task'
import { resetAiTestState } from './ai-fixtures'

const push = vi.hoisted(() => vi.fn(async () => undefined))

vi.mock('@/router', () => ({
  default: { push },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  setActivePinia(createPinia())
  resetAiTestState()
  push.mockClear()
})

function enter(pageId: 'tasks' | 'export' | 'import', taskId: number | null = null): void {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId,
    page: pageId,
    taskType: pageId === 'tasks' ? null : (pageId === 'export' ? 'EXPORT' : 'IMPORT'),
    step: pageId === 'tasks' ? null : 'EXPORT',
    taskId
  })
  // `navigate_to(page, taskId)` 现在**缓存优先、未命中就 GET**（查不到即不导航）；
  // 这里按页面类型钉住权威缓存，等价于"任务中心列表已加载 / 之前 restore_task 查过"。
  if (taskId !== null && pageId !== 'tasks') {
    useTaskStore().knownTaskTypes[taskId] = pageId === 'export' ? 'EXPORT' : 'IMPORT'
  }
}

describe('A-01 / A-02：同页同参幂等直返（push 0 次）', () => {
  it('无 taskId：已在目标页 ⇒ ok + 「已在目标页」', async () => {
    enter('export')
    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export' },
      runId: 'run-a01',
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('已在目标页')
    expect(push).toHaveBeenCalledTimes(0)
  })

  it('带 taskId 且已绑定该任务 ⇒ ok + 「任务 #579 已绑定」', async () => {
    enter('export', 579)
    const execution = await executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export', taskId: 579 },
      runId: 'run-a02',
      budgetMs: 5_000
    })
    expect(execution.outcome).toBe('ok')
    expect(execution.result).toContain('已在目标页')
    expect(execution.result).toContain('任务 #579 已绑定')
    expect(push).toHaveBeenCalledTimes(0)
  })
})

describe('A-03：同页但任务不同 ⇒ 不直返，正常派发', () => {
  it('当前绑 #579、目标 #580 ⇒ push 1 次且不说「已在目标页」', async () => {
    vi.useFakeTimers()
    try {
      enter('export', 579)
      // 目标任务 #580 的类型已知（列表已加载 / 之前查过）：导航只需 cache 命中，不再发 GET
      useTaskStore().knownTaskTypes[580] = 'EXPORT'
      const pending = executeFrontendTool({
        name: 'navigate_to',
        args: { page: 'export', taskId: 580 },
        runId: 'run-a03',
        budgetMs: 30_000
      })
      await vi.advanceTimersByTimeAsync(3_500)
      const execution = await pending
      expect(push).toHaveBeenCalledTimes(1)
      expect(execution.result).not.toContain('已在目标页')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('A-04：导航已提交但页面未就绪 ⇒ failed + UI_PAGE_NOT_READY（不再「结局未知」）', () => {
  it('3s 上界耗尽 ⇒ 可判定失败 + 「本轮不要重复导航」', async () => {
    vi.useFakeTimers()
    try {
      enter('tasks')
      const pending = executeFrontendTool({
        name: 'navigate_to',
        args: { page: 'export' },
        runId: 'run-a04',
        budgetMs: 30_000
      })
      await vi.advanceTimersByTimeAsync(3_500)
      const execution = await pending
      expect(execution.outcome).toBe('failed')
      expect(execution.code).toBe('UI_PAGE_NOT_READY')
      expect(execution.availability).toBe('degraded')
      expect(execution.result).toContain('导航已提交')
      expect(execution.result).toContain('本轮不要重复导航')
      expect(execution.result).not.toContain('结局未知')
    } finally {
      vi.useRealTimers()
    }
  })
})

/**
 * NAV3 之后 A-05 的口径只剩一半：**成功**结果仍去重；**失败**结果不得再挡住重试。
 *
 * 旧版本拿"导航超时（failed）后被去重"当去重证据 —— 那正是要根治的毒化：
 * 一次超时被记进表，同参重试直接被挡回"本轮已执行过"，模型据此认定路走不通。
 */
describe('A-05：同 run 同参去重（第二次回执「本轮已执行过」）', () => {
  it('同页幂等成功后再连调 ⇒ 第二次去重，push 仍 0 次', async () => {
    enter('export')
    const call = () => executeFrontendTool({
      name: 'navigate_to',
      args: { page: 'export' },
      runId: 'run-a05',
      budgetMs: 30_000
    })
    const first = await call()
    expect(first.outcome).toBe('ok')
    expect(first.code).toBe('UI_ALREADY_ON_PAGE')

    const second = await call()
    expect(second.result).toContain('本轮已执行过')
    expect(push).toHaveBeenCalledTimes(0)
  })

  it('首次导航失败（超时）⇒ 不缓存，同参重试会真执行（push 2 次）', async () => {
    vi.useFakeTimers()
    try {
      enter('tasks')
      const call = () => executeFrontendTool({
        name: 'navigate_to',
        args: { page: 'export' },
        runId: 'run-a05b',
        budgetMs: 30_000
      })
      const first = call()
      await vi.advanceTimersByTimeAsync(3_500)
      expect((await first).outcome).toBe('failed')

      const second = call()
      await vi.advanceTimersByTimeAsync(3_500)
      const retried = await second
      expect(retried.outcome).toBe('failed')
      expect(retried.result).not.toContain('本轮已执行过')
      expect(push).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
