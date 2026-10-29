/**
 * R5 返修 · 契约层路由：漏传 `conditions` 不得被补成 `{}`（= 显式清空）。
 *
 * 现场（reviewer Important）：`frontend-tools.ts` 里 `set_condition` 的 `toUiEvent` 与
 * ui_event 同名动作 `set_conditions` 的归一化都写了 `conditions: args.conditions ?? {}` ——
 * 页面未挂载（契约层兜底）时，漏参会被当成"显式清空"：PUT `{}` 并回成功。
 *
 * 收口口径：`conditions` 必填；漏传（undefined / null）⇒ 参数不完整失败，零 PUT、零工作区写；
 * 只有**显式 `{}`** 才清空。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
import { useWorkspaceStore } from '@/stores/workspace'

function bindExportContext(): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'QUERY_COND',
    taskId: 7,
    taskStatus: 'ACTIVE',
    selectedDefs: ['CURRENCY']
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
  api.listDefinitions.mockResolvedValue([{ code: 'CURRENCY' }])
})

describe('契约层路由：漏传 conditions 不得当清空（R5）', () => {
  it('set_condition 漏传 conditions ⇒ 参数不完整失败、零 PUT、零工作区写', async () => {
    const workspace = bindExportContext()

    const execution = await executeFrontendTool({
      name: 'set_condition',
      args: { defCode: 'CURRENCY' },
      budgetMs: 5_000
    })

    // 修复前（RED）：toUiEvent 补 {} ⇒ 契约层当"显式清空" ⇒ PUT "{}" 且回成功
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('conditions')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('ui_event 同名动作 set_conditions 漏传 conditions ⇒ 失败、零 PUT、零工作区写', async () => {
    const workspace = bindExportContext()

    const execution = await executeFrontendTool({
      name: 'set_conditions',
      args: { defCode: 'CURRENCY' },
      budgetMs: 5_000
    })

    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('conditions')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('conditions: null ⇒ 失败、零 PUT（只认显式 {}）', async () => {
    const workspace = bindExportContext()

    const execution = await executeFrontendTool({
      name: 'set_condition',
      args: { defCode: 'CURRENCY', conditions: null },
      budgetMs: 5_000
    })

    expect(execution.ok).toBe(false)
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })
})

describe('契约层路由：显式 {} 与合法条件仍正常（相邻正向）', () => {
  it('显式 {} ⇒ 照旧清空（PUT "{}"），工作区不留占位', async () => {
    const workspace = bindExportContext()

    const execution = await executeFrontendTool({
      name: 'set_condition',
      args: { defCode: 'CURRENCY', conditions: {} },
      budgetMs: 5_000
    })

    expect(execution.ok).toBe(true)
    expect(api.setCondition).toHaveBeenCalledTimes(1)
    expect(api.setCondition.mock.calls[0]?.[2]).toBe('{}')
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('别名 set_conditions 显式 {} ⇒ 同样清空', async () => {
    const workspace = bindExportContext()

    const execution = await executeFrontendTool({
      name: 'set_conditions',
      args: { defCode: 'CURRENCY', conditions: {} },
      budgetMs: 5_000
    })

    expect(execution.ok).toBe(true)
    expect(api.setCondition.mock.calls[0]?.[2]).toBe('{}')
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('合法条件 ⇒ 按内容原样落库并写回工作区', async () => {
    const workspace = bindExportContext()
    const condition = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }

    const execution = await executeFrontendTool({
      name: 'set_condition',
      args: { defCode: 'CURRENCY', conditions: condition },
      budgetMs: 5_000
    })

    expect(execution.ok).toBe(true)
    expect(JSON.parse(String(api.setCondition.mock.calls[0]?.[2] ?? '{}'))).toEqual(condition)
    expect(workspace.queryConditions.CURRENCY).toEqual(condition)
  })
})
