/**
 * R-09 第三入口：旧 `run_job` ui_event 分支的发布语义必须与页面按钮 / `import.startPublish`
 * handler 同口径 —— REPLACE 先走同一份复选框确认，MERGE 免确认；未确认 ⇒ 零 createJob。
 *
 * Fix round 1（C-1）：发布模式**只以任务服务端值为准**，不再信任 `workspace.importMode`，
 * 也不再“读不到就按 MERGE”。读取失败 / settings 不可解析 ⇒ fail-closed：不确认、不建作业，
 * 回执明确未启动。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const confirmApi = vi.hoisted(() => ({ confirmPublishByMode: vi.fn() }))
const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  goToStep: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  setImportMode: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  listJobs: vi.fn()
}))
const taskStore = vi.hoisted(() => ({
  watchPublishOutcome: vi.fn(),
  cancelJob: vi.fn(),
  resolveWizardRoute: vi.fn()
}))

vi.mock('@/utils/confirm-irreversible', () => ({
  confirmPublishByMode: confirmApi.confirmPublishByMode
}))
vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/router', () => ({
  default: { push: vi.fn() },
  ROUTE_NAMES: { exportWizard: 'export', importWizard: 'import', taskCenter: 'tasks' }
}))
vi.mock('@/stores/task', () => ({ useTaskStore: () => taskStore }))

import { dispatchUiEvent, useWorkspaceStore } from '@/stores/workspace'

const PUBLISH_JOB = {
  id: 99,
  taskId: 7,
  jobType: 'PUBLISH',
  status: 'PENDING',
  progress: 0,
  total: 0,
  errorCount: 0,
  warningCount: 0,
  items: []
}

beforeEach(() => {
  setActivePinia(createPinia())
  for (const fn of [...Object.values(api), ...Object.values(taskStore), confirmApi.confirmPublishByMode]) {
    fn.mockReset()
  }
  api.createJob.mockResolvedValue(PUBLISH_JOB)
  api.getTask.mockResolvedValue({ id: 7, settingsJson: '{"importMode":"REPLACE"}' })
  taskStore.watchPublishOutcome.mockReturnValue(vi.fn())
  confirmApi.confirmPublishByMode.mockResolvedValue(true)

  const workspace = useWorkspaceStore()
  workspace.taskId = 7
  workspace.taskType = 'IMPORT'
  workspace.step = 'PUBLISH'
  workspace.taskStatus = null
  workspace.importMode = null
})

describe('R-09 run_job PUBLISH：REPLACE 复用同一份确认', () => {
  it('服务端 REPLACE、本页 importMode=MERGE ⇒ 仍按权威值确认；未确认零 createJob', async () => {
    const workspace = useWorkspaceStore()
    workspace.importMode = 'MERGE'
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(false)

    const result = await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(api.getTask).toHaveBeenCalledWith(7)
    expect(confirmApi.confirmPublishByMode).toHaveBeenCalledWith('REPLACE')
    expect(api.createJob).not.toHaveBeenCalled()
    expect(result.handled).toBe(false)
    expect(result.message).toContain('未启动')
  })

  it('权威模式解析成功后同步当前任务的 workspace.importMode（不记假 UI 动作）', async () => {
    const workspace = useWorkspaceStore()
    workspace.importMode = 'MERGE'
    const before = workspace.recentActions.length
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(false)

    await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(workspace.importMode).toBe('REPLACE')
    const appended = workspace.recentActions.slice(before)
    expect(appended.some((action) => action.display?.code === 'ai.workspace.importMode')).toBe(false)
  })

  it('确认接受 ⇒ createJob(PUBLISH) 并建立发布跟踪', async () => {
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(true)

    const result = await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(confirmApi.confirmPublishByMode).toHaveBeenCalledWith('REPLACE')
    expect(api.createJob).toHaveBeenCalledWith(7, 'PUBLISH')
    expect(taskStore.watchPublishOutcome).toHaveBeenCalledTimes(1)
    expect(result.handled).toBe(true)
  })

  it('非当前绑定任务 ⇒ 按 taskId 读取服务端模式', async () => {
    const workspace = useWorkspaceStore()
    workspace.taskId = 8
    api.getTask.mockResolvedValue({ id: 7, settingsJson: '{"importMode":"REPLACE"}' })
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(true)

    await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(api.getTask).toHaveBeenCalledWith(7)
    expect(confirmApi.confirmPublishByMode).toHaveBeenCalledWith('REPLACE')
    expect(api.createJob).toHaveBeenCalledWith(7, 'PUBLISH')
  })
})

describe('R-09 run_job PUBLISH：MERGE 免确认', () => {
  it('服务端 MERGE、本页 importMode=REPLACE ⇒ 按权威值免确认，createJob 正常发起', async () => {
    const workspace = useWorkspaceStore()
    workspace.importMode = 'REPLACE'
    api.getTask.mockResolvedValue({ id: 7, settingsJson: '{"importMode":"MERGE"}' })
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(true)

    const result = await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(confirmApi.confirmPublishByMode).toHaveBeenCalledWith('MERGE')
    expect(api.createJob).toHaveBeenCalledWith(7, 'PUBLISH')
    expect(result.handled).toBe(true)
  })

  it('任务 settingsJson 未设置模式 ⇒ 后端默认 MERGE，不弹确认', async () => {
    api.getTask.mockResolvedValue({ id: 7, settingsJson: null })
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(true)

    await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(confirmApi.confirmPublishByMode).toHaveBeenCalledWith('MERGE')
    expect(api.createJob).toHaveBeenCalledWith(7, 'PUBLISH')
  })
})

describe('R-09 run_job PUBLISH：模式不可知 ⇒ fail-closed', () => {
  it('getTask 失败 ⇒ 明确失败、不确认、零 createJob', async () => {
    api.getTask.mockRejectedValueOnce(new Error('network down'))

    const result = await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(confirmApi.confirmPublishByMode).not.toHaveBeenCalled()
    expect(api.createJob).not.toHaveBeenCalled()
    expect(result.handled).toBe(false)
    expect(result.message).toContain('无法读取')
    expect(result.message).toContain('未启动')
  })

  it('settingsJson 不可解析 ⇒ 明确失败、不确认、零 createJob', async () => {
    api.getTask.mockResolvedValue({ id: 7, settingsJson: '{not-json' })

    const result = await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(confirmApi.confirmPublishByMode).not.toHaveBeenCalled()
    expect(api.createJob).not.toHaveBeenCalled()
    expect(result.handled).toBe(false)
    expect(result.message).toContain('无法解析')
    expect(result.message).toContain('未启动')
  })
})
