/**
 * R1 返修 · 契约层兜底同样 fail-closed：`ui_event.select_defs` 不得写入非法编码。
 *
 * 现场（reviewer Important）：页面 handler 已按当前定义列表校验编码，但**页面未挂载**时
 * 走的是契约层兜底（`dispatchUiEvent` 的 `select_defs` 分支）：它直接 `tasksApi.selectDefs`
 * 落库 + 写工作区，从不校验编码 —— 非法码因此仍能经 AI 的 ui_event 路径进入任务项与
 * `extra.selectedDefs`。修法：兜底分支用**权威定义服务**（`GET /api/definitions`）校验；
 * 定义不可用则零写（fail-closed）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  listDefinitions: vi.fn(),
  selectDefs: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn()
}))

vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/tasks', () => ({ tasksApi: { selectDefs: api.selectDefs } }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))

import { dispatchUiEvent, useWorkspaceStore } from '@/stores/workspace'

const DEFS = [
  { code: 'CURRENCY', name: '币种', level: 'GLOBAL', fields: [{ code: 'code', label: '编码', fieldType: 'STRING' }] },
  { code: 'REGION', name: '地区', level: 'REGION', fields: [{ code: 'code', label: '编码', fieldType: 'STRING' }] }
]

function bindTask(): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'SELECT_DEFS',
    taskId: 7,
    taskStatus: 'ACTIVE',
    selectedDefs: ['CURRENCY']
  })
  return workspace
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.listDefinitions.mockResolvedValue(DEFS)
  api.selectDefs.mockResolvedValue({})
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
})

describe('契约层兜底 select_defs：非法编码不得落库、不得写工作区', () => {
  it('含非法编码 ⇒ handled=false，零 selectDefs、工作区选中集不变', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({
      type: 'select_defs',
      task: 'export',
      defCodes: ['CURRENCY', 'NOT_A_DEF']
    })

    // 修复前（RED）：兜底分支直接 tasksApi.selectDefs(7, [...]) 并写工作区
    expect(result.handled).toBe(false)
    expect(result.message).toContain('NOT_A_DEF')
    expect(result.message).toContain('可用编码')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })

  it('定义列表不可用 ⇒ handled=false，零 selectDefs、工作区选中集不变（不得当"没有可用编码"放行）', async () => {
    const workspace = bindTask()
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))

    const result = await dispatchUiEvent({
      type: 'select_defs',
      task: 'export',
      defCodes: ['CURRENCY']
    })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('定义列表')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })

  it('合法编码 ⇒ 照常落库并写工作区（相邻正向不回归）', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({
      type: 'select_defs',
      task: 'export',
      defCodes: ['REGION']
    })

    expect(result.handled).toBe(true)
    expect(api.selectDefs).toHaveBeenCalledWith(7, ['REGION'])
    expect(workspace.selectedDefs).toEqual(['REGION'])
  })

  it('空 codes ⇒ handled=false，零 selectDefs、工作区不变（不得把"清空选择"当成功落库）', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: [] })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('codes 为空')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })
})
