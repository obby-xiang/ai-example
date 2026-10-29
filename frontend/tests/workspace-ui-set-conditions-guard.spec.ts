/**
 * R3 返修 · 契约层兜底 `ui_event.set_conditions` 必须与页面同口径校验。
 *
 * 现场（reviewer Important）：页面未挂载时走 `dispatchUiEvent` 的 `set_conditions` 分支，它把
 * `event.conditions` **原样** `PUT` 并写工作区 —— `IN []` / 值算子空串会落库成"0 行/空值"
 * 条件，坏 JSON 甚至会以字符串落库或被后端当成空条件（导出全量）。
 *
 * 收口口径（与 `export.setConditions` 同一判据链）：
 * `parseConditionJson` → `draftModelFromCondition` → `buildConditionOutcome`。
 * - 不可解析 / 结构坏 / 非法空值 ⇒ `handled=false`、**零 `tasksApi.setCondition`、零工作区写**；
 * - 显式 `{}`（或等价空条件）与坏载荷**分开**：前者是"清空条件"（PUT `{}`，与页面一致），
 *   后者一律失败，绝不静默当成"无条件 = 全量"；
 * - 合法复杂条件（同字段多条件、含逗号 IN）按内容原样落库。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

import { dispatchUiEvent, useWorkspaceStore } from '@/stores/workspace'

function bindTask(): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'QUERY_COND',
    taskId: 7,
    taskStatus: 'ACTIVE',
    selectedDefs: ['CURRENCY'],
    queryConditions: {}
  })
  return workspace
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.listDefinitions.mockResolvedValue([{ code: 'CURRENCY' }])
  api.setCondition.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
})

describe('契约层兜底 set_conditions：非法/坏载荷零 PUT、零工作区写', () => {
  it('IN [] ⇒ handled=false、零 setCondition、工作区未写入该条件', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: [] }] }
    })

    // 修复前（RED）：原样 PUT + 写工作区 + handled=true
    expect(result.handled).toBe(false)
    expect(result.message).toContain('未设置查询条件')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('值算子空串（EQ ""）⇒ 失败、零 setCondition、零工作区写', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: '' }] }
    })

    expect(result.handled).toBe(false)
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('坏 JSON 字符串 ⇒ 失败且**不**被当成"清空/全量"，零 PUT、零工作区写', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: '{"fields":[{"fieldCode":"code",'
    })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('无法解析')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('结构坏载荷（fields 非数组 / 条目缺 fieldCode）⇒ 失败、零 PUT', async () => {
    bindTask()

    const badShape = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: '{"fields":"oops"}'
    })
    expect(badShape.handled).toBe(false)

    const badEntry = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: '{"fields":[{"operator":"EQ"}]}'
    })
    expect(badEntry.handled).toBe(false)
    expect(api.setCondition).not.toHaveBeenCalled()
  })
})

describe('契约层兜底 set_conditions：显式清空与合法复杂条件（相邻正向）', () => {
  it('显式 {} ⇒ 按"清空条件"落库（PUT "{}"），工作区不留该配置项占位（R4）', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: {} })

    expect(result.handled).toBe(true)
    expect(api.setCondition).toHaveBeenCalledTimes(1)
    expect(api.setCondition.mock.calls[0]?.[1]).toBe('CURRENCY')
    expect(api.setCondition.mock.calls[0]?.[2]).toBe('{}')
    // 修复前（RED）：写成 queryConditions.CURRENCY = null（把"清空"记成一条已设置条件）
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('合法复杂条件（同字段多条件 + 含逗号 IN）⇒ 按内容原样落库并写回工作区', async () => {
    const workspace = bindTask()
    const condition = {
      fields: [
        { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
        { fieldCode: 'code', operator: 'NE', value: 'USD' },
        { fieldCode: 'amount', operator: 'IN', value: ['1,000', '2000'] }
      ]
    }

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: condition })

    expect(result.handled).toBe(true)
    expect(JSON.parse(String(api.setCondition.mock.calls[0]?.[2] ?? '{}'))).toEqual(condition)
    expect(workspace.queryConditions.CURRENCY).toEqual(condition)
  })
})
