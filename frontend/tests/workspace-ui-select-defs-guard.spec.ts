/**
 * 写类 ui_event 兜底收口（1/3）：页面 handler 未注册时 `ui_event.select_defs` **不得直写业务后台**。
 *
 * 现场：AI 调 `select_definitions` / `select_defs` 时若导出（或导入）向导未挂载，
 * 「页面能力 handler → 契约层兜底」的两级路由会落到 `runUiEvent` 的 `select_defs` 分支 ——
 * 它直接 `tasksApi.selectDefs` 落库并写工作区。页面并不在场，任务里的选中集却已被改写：
 * 页面一旦挂载又按任务详情回灌，AI 与用户看到的是两条互相覆盖的事实。
 *
 * 收口口径：草案写类动作（`select_defs` / `set_conditions` / `goto_step`）只认**页面能力 handler**；
 * handler 未注册 ⇒ 确定性失败（`handled=false`，文案「页面未就绪」），零业务 API、零工作区写、
 * 零 `workspace_changed`（不得假成功）。handler 已注册时交给页面（与用户手点同一套页面逻辑），
 * 契约层不再自己发写请求 —— 正常 handler 路径的页面语义由页面侧用例覆盖。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

import {
  dispatchUiEvent,
  onWorkspaceEvent,
  registerPageHandler,
  unregisterPageHandler,
  useWorkspaceStore
} from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'

function bindTask(
  status: 'ACTIVE' | 'CANCELLED' = 'ACTIVE'
): ReturnType<typeof useWorkspaceStore> {
  const workspace = useWorkspaceStore()
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'SELECT_DEFS',
    taskId: 7,
    taskStatus: status,
    selectedDefs: ['CURRENCY']
  })
  return workspace
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.listDefinitions.mockResolvedValue([{ code: 'CURRENCY' }, { code: 'REGION' }])
  api.selectDefs.mockResolvedValue({})
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
})

afterEach(() => {
  unregisterPageHandler('export.selectDefs')
})

describe('页面 handler 未注册：select_defs 零业务写入、零假成功', () => {
  it('合法编码 ⇒ handled=false「页面未就绪」，零 selectDefs、选中集不变、零 workspace_changed', async () => {
    const workspace = bindTask()
    const changed: unknown[] = []
    const off = onWorkspaceEvent('workspace_changed', (payload) => changed.push(payload))

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: ['REGION'] })
    off()

    // 修复前（RED）：兜底分支直接 tasksApi.selectDefs(7, ['REGION']) + setSelectedDefs
    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(result.message).toContain('未变更所选配置项')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    // 不得假成功：「工作区已同步」的数据源一次都不许发
    expect(changed).toEqual([])
    // 被拒事实仍留在时间线（模型下一轮要看到这一手没落地）
    expect(workspace.recentActions.at(-1)).toMatchObject({ source: 'AI' })
    expect(workspace.recentActions.at(-1)?.summary).toContain('页面未就绪')
  })

  it('含非法编码 ⇒ 同一确定性失败（不在契约层做编码校验，也不落库）', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({
      type: 'select_defs',
      task: 'export',
      defCodes: ['CURRENCY', 'NOT_A_DEF']
    })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })

  it('空 codes ⇒ 同样失败，零 selectDefs（不得把"清空选择"当成功落库）', async () => {
    const workspace = bindTask()

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: [] })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })

  it('导入向导同样未注册 ⇒ 失败文案点名导入向导，零 API', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'import',
      page: 'import',
      taskType: 'IMPORT',
      step: 'UPLOAD',
      taskId: 9,
      taskStatus: 'ACTIVE',
      selectedDefs: []
    })

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'import', defCodes: ['CURRENCY'] })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('导入向导')
    expect(result.message).toContain('页面未就绪')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual([])
  })
})

describe('页面 handler 未注册：既有守卫先于「未就绪」生效', () => {
  it('无任务 ⇒ 「当前没有进行中的任务」，零 API', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: ['A'] })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('当前没有进行中的任务')
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('只读任务（CANCELLED）⇒ 「任务已结束」，零 API', async () => {
    const workspace = bindTask('CANCELLED')

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: ['REGION'] })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('任务已结束')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })
})

describe('页面 handler 已注册：交给页面，契约层不直写', () => {
  it('handler 收到 defCodes/mode，回执取 handler 文案，零 tasksApi.selectDefs', async () => {
    bindTask()
    const seen: Array<Record<string, unknown>> = []
    registerPageHandler('export.selectDefs', (payload) => {
      seen.push(payload)
      return '已选择配置项：REGION（页面）'
    })

    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: ['REGION'] })

    expect(result.handled).toBe(true)
    expect(result.message).toBe('已选择配置项：REGION（页面）')
    expect(seen).toEqual([{ defCodes: ['REGION'], mode: 'REPLACE' }])
    expect(api.selectDefs).not.toHaveBeenCalled()
  })
})

describe('AI 工具实际路径（select_definitions → 契约层）：页面未挂载同样零写', () => {
  it('executeFrontendTool 走二级兜底 ⇒ failed + degraded「页面未就绪」，零 selectDefs', async () => {
    const workspace = bindTask()

    const execution = await executeFrontendTool({
      name: 'select_definitions',
      args: { codes: ['REGION'] },
      budgetMs: 5_000
    })

    expect(execution.outcome).toBe('failed')
    expect(execution.availability).toBe('degraded')
    expect(String(execution.result)).toContain('页面未就绪')
    expect(String(execution.result)).toContain('请提示用户在界面上手动完成该动作')
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })
})
