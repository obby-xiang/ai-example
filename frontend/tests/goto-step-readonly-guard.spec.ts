/**
 * R-137（T5）前端 goto_step 只读守卫锁：`dispatchUiEvent({type:'goto_step'})`
 * 在任务只读态（CANCELLED / COMPLETED 且最终作业成功）下必须 fail，不触达
 * `tasksApi.goToStep`（与 F6 五处同构：select_defs/set_conditions/set_import_mode/run_job/cancel_job）。
 *
 * 只读判据 = stores/workspace.ts 的 isCurrentTaskReadOnly（本批既有函数）：
 * - CANCELLED ⇒ 只读；
 * - COMPLETED ⇒ 按类型核最终作业（IMPORT→PUBLISH、EXPORT→EXPORT 的最新一条 COMPLETED）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

import { dispatchUiEvent, useWorkspaceStore } from '@/stores/workspace'

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

  it('COMPLETED 但最终作业未成功（预检查后继续导入主路径）：goto_step 放行', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'COMPLETED', 'IMPORT')
    api.listJobs.mockResolvedValue([{ jobId: 2, jobType: 'PUBLISH', status: 'FAILED' }])

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'IMPORT' })

    expect(result.handled).toBe(true)
    expect(result.message).toContain('已跳转到步骤 IMPORT')
    expect(api.goToStep).toHaveBeenCalledWith(1, 'IMPORT')
  })

  it('ACTIVE 任务：goto_step 照常放行（既有行为回归）', async () => {
    const workspace = useWorkspaceStore()
    setTask(workspace, 'ACTIVE', 'EXPORT')

    const result = await dispatchUiEvent({ type: 'goto_step', step: 'QUERY_COND' })

    expect(result.handled).toBe(true)
    expect(api.goToStep).toHaveBeenCalledWith(1, 'QUERY_COND')
  })
})
