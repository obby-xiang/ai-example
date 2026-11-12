import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  setCondition: vi.fn(),
  selectDefs: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  listDefinitions: vi.fn()
}))

vi.mock('@/api/tasks', () => ({ tasksApi: { setCondition: api.setCondition, selectDefs: api.selectDefs } }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))

import { executeFrontendTool } from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'

const CONDITION = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }

function bindExportContext(): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'QUERY_COND',
    taskId: 7,
    taskStatus: 'ACTIVE',
    selectedDefs: ['CURRENCY', 'REGION']
  })
  return workspace
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.setCondition.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([{ code: 'CURRENCY' }, { code: 'REGION' }])
})

afterEach(() => {
  unregisterPageHandler('export.setConditions')
})

describe('set_conditions 唯一批量协议', () => {
  it('items 原样进入页面并只产生一个动作和一份批量回执', async () => {
    const workspace = bindExportContext()
    const seen: Array<Record<string, unknown>> = []
    registerPageHandler('export.setConditions', (payload) => {
      seen.push(payload)
      return { ok: true, result: '已设置 2 个配置项的查询条件草案' }
    })
    const items = [
      { defCode: 'CURRENCY', conditions: CONDITION },
      { defCode: 'REGION', conditions: {} }
    ]
    const actionCount = workspace.recentActions.length

    const execution = await executeFrontendTool({ name: 'set_conditions', args: { items }, budgetMs: 5_000 })

    expect(execution.outcome).toBe('ok')
    expect(execution.result).toBe('已设置 2 个配置项的查询条件草案')
    expect(seen).toEqual([{ items }])
    expect(workspace.recentActions).toHaveLength(actionCount + 1)
    expect(workspace.recentActions.at(-1)?.summary).toBe('已设置 2 个配置项的查询条件草案')
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('旧 set_condition action、旧别名和单条 ui_event 全部拒绝且不进入页面', async () => {
    bindExportContext()
    const handler = vi.fn(() => ({ ok: true, result: '不应成功' }))
    registerPageHandler('export.setConditions', handler)

    const oldAction = await executeFrontendTool({
      name: 'set_condition',
      args: { defCode: 'CURRENCY', conditions: CONDITION },
      budgetMs: 5_000
    })
    const oldAlias = await executeFrontendTool({
      name: 'set_query_conditions',
      args: { defCode: 'CURRENCY', conditions: CONDITION },
      budgetMs: 5_000
    })
    const oldUiEvent = await executeFrontendTool({
      name: 'set_conditions',
      args: { defCode: 'CURRENCY', conditions: CONDITION },
      budgetMs: 5_000
    })

    expect(oldAction.outcome).toBe('failed')
    expect(oldAlias.outcome).toBe('failed')
    expect(oldUiEvent.outcome).toBe('failed')
    expect(oldUiEvent.result).toContain('items')
    expect(handler).not.toHaveBeenCalled()
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('空批次、缺 conditions 和重复 defCode 都在进入页面前拒绝', async () => {
    bindExportContext()
    const handler = vi.fn(() => ({ ok: true, result: '不应成功' }))
    registerPageHandler('export.setConditions', handler)
    const invalidBatches: unknown[] = [
      {},
      { items: [] },
      { items: [{ defCode: 'CURRENCY' }] },
      { items: [{ defCode: 'CURRENCY', conditions: null }] },
      { items: [
        { defCode: 'CURRENCY', conditions: CONDITION },
        { defCode: 'CURRENCY', conditions: {} }
      ] }
    ]

    for (const args of invalidBatches) {
      const execution = await executeFrontendTool({
        name: 'set_conditions',
        args: args as Record<string, unknown>,
        budgetMs: 5_000
      })
      expect(execution.outcome).toBe('failed')
      expect(execution.result).toContain('items')
    }
    expect(handler).not.toHaveBeenCalled()
    expect(api.setCondition).not.toHaveBeenCalled()
  })
})
