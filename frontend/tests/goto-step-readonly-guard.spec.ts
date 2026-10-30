/**
 * R-137（T5）前端 goto_step 守卫锁 + 写类兜底收口（3/3）。
 *
 * <p><b>只读守卫</b>：`dispatchUiEvent({type:'goto_step'})` 在任务只读态（CANCELLED /
 * COMPLETED 且最终作业成功）下必须 fail，不触达 `tasksApi.goToStep`（与 F6 五处同构：
 * select_defs / set_conditions / set_import_mode / run_job / cancel_job）。
 * 只读判据 = stores/workspace.ts 的 isCurrentTaskReadOnly（本批既有函数）：
 * - CANCELLED ⇒ 只读；
 * - COMPLETED ⇒ 按类型核最终作业（IMPORT→PUBLISH、EXPORT→EXPORT 的最新一条 COMPLETED）。
 *
 * <p><b>兜底收口</b>：页面能力 handler 未注册时，`goto_step` **不得** `tasksApi.goToStep`
 * （那会绕过页面的 `persistStepData`：勾选集/条件尚未落库就先报了「已推进」）。
 * 未注册 ⇒ 确定性失败（`handled=false`，文案「页面未就绪」）、零业务 API、零工作区写；
 * 已注册 ⇒ 交给页面 handler（与用户点「下一步」同路径），契约层自己不发写请求。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  goToStep: vi.fn(),
  listJobs: vi.fn()
}))

vi.mock('@/api/tasks', () => ({ tasksApi: { goToStep: api.goToStep } }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs } }))
vi.mock('@/router', () => ({ default: { push: vi.fn() }, ROUTE_NAMES: { exportWizard: 'export', importWizard: 'import' } }))
vi.mock('@/stores/task', () => ({
  useTaskStore: () => ({ resolveWizardRoute: () => ({ name: 'export' }) })
}))

import {
  dispatchUiEvent,
  onWorkspaceEvent,
  registerPageHandler,
  unregisterPageHandler,
  useWorkspaceStore
} from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'

function setTask(workspace: ReturnType<typeof useWorkspaceStore>, status: string, type: 'EXPORT' | 'IMPORT'): void {
  workspace.taskId = 1
  workspace.taskType = type
  workspace.taskStatus = status as never
}

describe('R-137 · goto_step 只读守卫（workspace.ts dispatchUiEvent）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.goToStep.mockReset()
    api.listJobs.mockReset()
    api.listJobs.mockResolvedValue([])
  })

  afterEach(() => {
    unregisterPageHandler('export.nextStep')
    unregisterPageHandler('import.nextStep')
  })

  it('CANCELLED 任务：goto_step 被 fail，不触达 goToStep API', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'CANCELLED', 'EXPORT')

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'QUERY_COND' })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('任务已结束')
    expect(api.goToStep).not.toHaveBeenCalled()
  })

  it('COMPLETED + 最终作业成功（EXPORT/EXPORT COMPLETED）：goto_step 被 fail', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'COMPLETED', 'EXPORT')
    api.listJobs.mockResolvedValue([{ jobId: 1, jobType: 'EXPORT', status: 'COMPLETED' }])

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'QUERY_COND' })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('任务已结束')
    expect(api.goToStep).not.toHaveBeenCalled()
  })
})

describe('页面 handler 未注册：goto_step 不得直推步骤（不绕 persistStepData）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.goToStep.mockReset()
    api.listJobs.mockReset()
    api.listJobs.mockResolvedValue([])
  })

  afterEach(() => {
    unregisterPageHandler('export.nextStep')
    unregisterPageHandler('import.nextStep')
  })

  it('COMPLETED 但最终作业未成功（预检查后继续导入主路径）：仍 fail「页面未就绪」，零 goToStep', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'COMPLETED', 'IMPORT')
    api.listJobs.mockResolvedValue([{ jobId: 2, jobType: 'PUBLISH', status: 'FAILED' }])
    const stepBefore = workspace.step
    const changed: unknown[] = []
    const off = onWorkspaceEvent('workspace_changed', (payload) => changed.push(payload))

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'IMPORT' })
    off()

    // 修复前（RED）：只读守卫放行后直接 tasksApi.goToStep(1, 'IMPORT') + setStep
    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(api.goToStep).not.toHaveBeenCalled()
    expect(workspace.step).toBe(stepBefore)
    expect(changed).toEqual([])
    expect(workspace.recentActions.at(-1)).toMatchObject({ source: 'AI' })
  })

  it('ACTIVE 任务但页面未挂载：fail「页面未就绪」，零 goToStep、步骤不变', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'ACTIVE', 'EXPORT')

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'QUERY_COND' })

    expect(result.handled).toBe(false)
    expect(result.message).toContain('页面未就绪')
    expect(result.message).toContain('未跳转步骤')
    expect(api.goToStep).not.toHaveBeenCalled()
    expect(workspace.step).toBeNull()
  })
})

describe('页面 handler 已注册：goto_step 交给页面（与用户点「下一步」同路径）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.goToStep.mockReset()
    api.listJobs.mockReset()
    api.listJobs.mockResolvedValue([])
  })

  afterEach(() => {
    unregisterPageHandler('export.nextStep')
  })

  it('handler 收到 step，回执取 handler 文案，零 tasksApi.goToStep', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'ACTIVE', 'EXPORT')
    const seen: Array<Record<string, unknown>> = []
    registerPageHandler('export.nextStep', (payload) => {
      seen.push(payload)
      return '已推进到导出向导步骤「查询条件」'
    })

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'QUERY_COND' })

    expect(result.handled).toBe(true)
    expect(result.message).toBe('已推进到导出向导步骤「查询条件」')
    expect(seen).toEqual([{ step: 'QUERY_COND' }])
    expect(api.goToStep).not.toHaveBeenCalled()
  })
})

describe('AI 工具实际路径（confirm_step → goto_step 兜底）：页面未挂载不得直推', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.goToStep.mockReset()
    api.listJobs.mockReset()
    api.listJobs.mockResolvedValue([])
  })

  it('executeFrontendTool(confirm_step) ⇒ failed + degraded「页面未就绪」，零 goToStep、步骤不变', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: 1,
      taskStatus: 'ACTIVE'
    })

    const execution = await executeFrontendTool({
      name: 'confirm_step',
      args: { step: 'QUERY_COND' },
      budgetMs: 5_000
    })

    expect(execution.outcome).toBe('failed')
    expect(execution.availability).toBe('degraded')
    expect(String(execution.result)).toContain('页面未就绪')
    expect(api.goToStep).not.toHaveBeenCalled()
    expect(workspace.step).toBe('SELECT_DEFS')
  })
})
