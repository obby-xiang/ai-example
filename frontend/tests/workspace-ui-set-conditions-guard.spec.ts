import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  listDefinitions: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn()
}))

vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { selectDefs: api.selectDefs, setCondition: api.setCondition } }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))

import {
  dispatchUiEvent,
  registerPageHandler,
  unregisterPageHandler,
  useWorkspaceStore
} from '@/stores/workspace'

const ITEMS = [
  { defCode: 'CURRENCY', conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] } },
  { defCode: 'REGION', conditions: {} }
]

function bindTask(status: 'ACTIVE' | 'CANCELLED' = 'ACTIVE'): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'QUERY_COND',
    taskId: 7,
    taskStatus: status,
    selectedDefs: ['CURRENCY', 'REGION'],
    queryConditions: {}
  })
  return workspace
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.listDefinitions.mockResolvedValue([{ code: 'CURRENCY' }, { code: 'REGION' }])
  api.setCondition.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
})

afterEach(() => {
  unregisterPageHandler('export.setConditions')
})

describe('set_conditions 契约层守卫与批量转交', () => {
  it('页面未就绪时整批失败且零写', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({ type: 'set_conditions', items: ITEMS })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).toEqual({})
  })

  it('items 只转交一次并只记一个成功动作', async () => {
    const workspace = bindTask()
    const seen: Array<Record<string, unknown>> = []
    registerPageHandler('export.setConditions', (payload) => {
      seen.push(payload)
      return { ok: true, result: '已设置 2 个配置项的查询条件草案' }
    })
    const actionCount = workspace.recentActions.length

    const result = await dispatchUiEvent({ type: 'set_conditions', items: ITEMS })

    expect(result).toEqual({ handled: true, message: '已设置 2 个配置项的查询条件草案' })
    expect(seen).toEqual([{ items: ITEMS }])
    expect(workspace.recentActions).toHaveLength(actionCount + 1)
    expect(workspace.recentActions.at(-1)?.summary).toBe('已设置 2 个配置项的查询条件草案')
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('只读任务在页面 handler 之前整批拒绝', async () => {
    const workspace = bindTask('CANCELLED')
    const handler = vi.fn(() => ({ ok: true, result: '不应成功' }))
    registerPageHandler('export.setConditions', handler)

    const result = await dispatchUiEvent({ type: 'set_conditions', items: ITEMS })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('任务已结束')
    expect(handler).not.toHaveBeenCalled()
    expect(workspace.queryConditions).toEqual({})
  })

  it('停止后的迟延续体不得再进入页面或写工作区', async () => {
    const workspace = bindTask()
    const handler = vi.fn(() => ({ ok: true, result: '不应成功' }))
    registerPageHandler('export.setConditions', handler)
    const actionCount = workspace.recentActions.length

    const result = await dispatchUiEvent(
      { type: 'set_conditions', items: ITEMS },
      { abandoned: true }
    )

    expect(result.handled).toBe(false)
    expect(handler).not.toHaveBeenCalled()
    expect(workspace.recentActions).toHaveLength(actionCount)
    expect(workspace.queryConditions).toEqual({})
  })

  it('页面结构化失败原样返回且不折成成功', async () => {
    bindTask()
    registerPageHandler('export.setConditions', () => ({ ok: false, reason: '第二项非法，整批未写入' }))

    const result = await dispatchUiEvent({ type: 'set_conditions', items: ITEMS })

    expect(result).toEqual({ handled: false, message: '第二项非法，整批未写入' })
    expect(api.setCondition).not.toHaveBeenCalled()
  })
})
