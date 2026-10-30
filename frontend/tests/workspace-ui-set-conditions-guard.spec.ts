/**
 * 写类 ui_event 兜底收口（2/3）：页面 handler 未注册时 `ui_event.set_conditions` **不得直写业务后台**。
 *
 * 现场：AI 调 `set_condition` / `set_conditions` 时若导出向导未挂载，路由落到 `runUiEvent` 的
 * `set_conditions` 分支 —— 它把载荷 PUT 到任务并写工作区。页面并不在场，条件却已落库：
 * 页面挂载后按详情回灌，界面与 AI 看到的是两份互相覆盖的条件。
 *
 * 收口口径：`set_conditions` 只认页面能力 handler（`export.setConditions`，导入向导没有该能力）；
 * handler 未注册 ⇒ 确定性失败（`handled=false`，文案「页面未就绪」），零 `tasksApi.setCondition`、
 * 零工作区写、零 `workspace_changed`。载荷校验/清空语义留在页面 handler（与用户手点同一判据链），
 * 契约层不再自己发写请求。
 */
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
  onWorkspaceEvent,
  registerPageHandler,
  unregisterPageHandler,
  useWorkspaceStore
} from '@/stores/workspace'

function bindTask(status: 'ACTIVE' | 'CANCELLED' = 'ACTIVE'): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'QUERY_COND',
    taskId: 7,
    taskStatus: status,
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

afterEach(() => {
  unregisterPageHandler('export.setConditions')
})

describe('页面 handler 未注册：set_conditions 零 PUT、零工作区写、零假成功', () => {
  it('合法条件 ⇒ handled=false「页面未就绪」，零 setCondition、条件未写入', async () => {
    const workspace = bindTask()
    const changed: unknown[] = []
    const off = onWorkspaceEvent('workspace_changed', (payload) => changed.push(payload))
    const condition = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: condition })
    off()

    // 修复前（RED）：直接 PUT 条件 + setQueryConditions + handled=true
    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(result.message).toContain('未设置查询条件')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
    expect(changed).toEqual([])
    expect(workspace.recentActions.at(-1)).toMatchObject({ source: 'AI' })
    expect(workspace.recentActions.at(-1)?.summary).toContain('页面未就绪')
  })

  it('显式 {}（清空条件）⇒ 同样失败：契约层不再替页面"PUT 空条件"', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: {} })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).toEqual({})
  })

  it('坏载荷（坏 JSON / IN []）⇒ 同一确定性失败，零 PUT、零工作区写', async () => {
    const workspace = bindTask()

    const broken = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: '{"fields":[{"fieldCode":"code",'
    })
    const emptyIn = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: [] }] }
    })

    expect(broken.handled).toBe(false)
    expect(broken.message).toContain('页面未就绪')
    expect(emptyIn.handled).toBe(false)
    expect(emptyIn.message).toContain('页面未就绪')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('导入任务：导入向导没有该页面能力 ⇒ 失败文案点名导入向导，零 PUT', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'import',
      page: 'import',
      taskType: 'IMPORT',
      step: 'UPLOAD',
      taskId: 9,
      taskStatus: 'ACTIVE'
    })

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: {} })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('导入向导')
    expect(result.message).toContain('页面未就绪')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).toEqual({})
  })
})

describe('页面 handler 未注册：既有守卫先于「未就绪」生效', () => {
  it('无任务 ⇒ 「当前没有进行中的任务」，零 PUT', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: {} })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('当前没有进行中的任务')
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('只读任务（CANCELLED）⇒ 「任务已结束」，零 PUT', async () => {
    const workspace = bindTask('CANCELLED')

    const result = await dispatchUiEvent({ type: 'set_conditions', defCode: 'CURRENCY', conditions: {} })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('任务已结束')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).toEqual({})
  })
})

describe('页面 handler 已注册：交给页面，契约层不直写', () => {
  it('handler 收到 defCode/conditions，回执取 handler 文案，零 tasksApi.setCondition', async () => {
    bindTask()
    const seen: Array<Record<string, unknown>> = []
    registerPageHandler('export.setConditions', (payload) => {
      seen.push(payload)
      return '已设置 CURRENCY 的查询条件（页面）'
    })

    const result = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }
    })

    expect(result.handled).toBe(true)
    expect(result.message).toBe('已设置 CURRENCY 的查询条件（页面）')
    expect(seen).toHaveLength(1)
    expect(seen[0]?.defCode).toBe('CURRENCY')
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('handler 回结构化失败 {ok:false} ⇒ handled=false（不得折回假成功）', async () => {
    bindTask()
    registerPageHandler('export.setConditions', () => ({ ok: false, reason: 'IN 至少一个值，本次未设置查询条件' }))

    const result = await dispatchUiEvent({
      type: 'set_conditions',
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: [] }] }
    })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('IN 至少一个值')
    expect(api.setCondition).not.toHaveBeenCalled()
  })
})
