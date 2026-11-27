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
import { mount } from '@vue/test-utils'
import { applyLocale } from '@/locales'

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
const aiPanelApi = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
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
vi.mock('@/api/ai', () => ({ aiApi: aiPanelApi }))

import { dispatchUiEvent, useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'

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

  it('真实工具链发布取消传专用用户码，模型回执保留原文且零 createJob', async () => {
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(false)

    const execution = await executeFrontendTool({
      name: 'run_job',
      args: { taskId: 7, jobType: 'PUBLISH' },
      runId: 'run-publish-confirm-cancel'
    })

    expect(execution.userTextCode).toBe('PUBLISH_CONFIRM_CANCELLED')
    expect(execution.result).toContain('用户取消了发布确认，未启动发布作业')
    expect(api.createJob).not.toHaveBeenCalled()
  })

  it('真实发布取消结果在 AiPanel 按中英定稿渲染，模型原文不变', async () => {
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(false)
    const execution = await executeFrontendTool({
      name: 'run_job',
      args: { taskId: 7, jobType: 'PUBLISH' },
      runId: 'run-publish-cancel-render'
    })
    aiPanelApi.health.mockResolvedValue({ available: true })
    aiPanelApi.history.mockResolvedValue({ sessionId: '7001', count: 0, messages: [] })
    aiPanelApi.runsCurrent.mockResolvedValue({ found: false, pendingEntries: [] })
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    store.messages = [{
      id: 'a-publish-cancel', role: 'assistant', content: '', reasoning: '', toolRuns: [], pendingCall: null,
      streaming: false, done: true,
      parts: [{
        kind: 'tool', seg: 0, toolCallId: 'publish-cancel', name: 'run_job', kind2: 'FRONTEND',
        status: 'failed', args: '{}', result: execution.result, userTextCode: execution.userTextCode,
        requiresAction: false
      }]
    }]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, {
      global: {
        stubs: {
          'el-alert': true, 'el-button': { template: '<button><slot /></button>' }, 'el-empty': true,
          'el-icon': { template: '<i><slot /></i>' }, 'el-input': true, 'el-skeleton': true,
          'el-tag': { template: '<span><slot /></span>' }, FormRenderer: true
        }
      }
    })
    store.loading = true
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('发布确认已取消，未启动发布作业。')
    applyLocale('en-US')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Publish confirmation was cancelled. No publish job was started.')
    expect(execution.result).toContain('用户取消了发布确认，未启动发布作业')
    wrapper.unmount()
    applyLocale('zh-CN')
  })

  it('其他 workspace 动作失败仍使用手动操作摘要', async () => {
    const workspace = useWorkspaceStore()
    workspace.pageId = 'import'
    workspace.step = 'SELECT_DEFS'

    const execution = await executeFrontendTool({
      name: 'select_definitions',
      args: { taskId: 7, defCodes: ['ORDER'] },
      runId: 'run-other-workspace-failure'
    })

    expect(execution.userTextCode).toBe('MANUAL_ACTION')
  })

  it('权威模式解析成功后同步当前任务的 workspace.importMode（不记假 UI 动作）', async () => {
    const workspace = useWorkspaceStore()
    workspace.importMode = 'MERGE'
    const before = workspace.recentActions.length
    confirmApi.confirmPublishByMode.mockResolvedValueOnce(false)

    await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    expect(workspace.importMode).toBe('REPLACE')
    const appended = workspace.recentActions.slice(before)
    expect(appended.some((action) => action.summary.includes('导入模式'))).toBe(false)
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

describe('run_job 晚到发布冲突：按发起 taskId 回灌工作区动作', () => {
  it('任务 A 切到 B 后，A 的晚到回调不得写入 B 的 recentActions', async () => {
    let onConflict: ((summary: string) => void) | undefined
    taskStore.watchPublishOutcome.mockImplementation((
      _taskId: number,
      _jobId: number,
      handlers: { onConflict: (summary: string) => void }
    ) => {
      onConflict = handlers.onConflict
      return vi.fn()
    })
    const workspace = useWorkspaceStore()
    await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })

    workspace.setTaskId(8, 'IMPORT')
    onConflict?.('任务 A 晚到冲突')

    expect(workspace.recentActions.map((action) => action.summary)).toEqual(['绑定任务 #8'])
  })

  it('同任务晚到回调仍注入 recentActions', async () => {
    let onConflict: ((summary: string) => void) | undefined
    taskStore.watchPublishOutcome.mockImplementation((
      _taskId: number,
      _jobId: number,
      handlers: { onConflict: (summary: string) => void }
    ) => {
      onConflict = handlers.onConflict
      return vi.fn()
    })
    const workspace = useWorkspaceStore()
    await dispatchUiEvent({ type: 'run_job', jobType: 'PUBLISH', taskId: 7 })
    workspace.clearRecentActions()

    onConflict?.('同任务晚到冲突')

    expect(workspace.recentActions.map((action) => action.summary)).toEqual(['同任务晚到冲突'])
  })
})
