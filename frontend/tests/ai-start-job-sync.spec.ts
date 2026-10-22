/**
 * 批 D · D3（问题 6）：**AI 触发作业后工作区同步**。
 *
 * <h2>缺陷</h2>
 * AI 经确认门放行 `start_export` / `start_precheck` / `start_import` 后，作业在后端跑起来了，
 * 但向导页自己的作业面板仍是空的（用户切过去看不到进度、也不知道该等哪个作业）——
 * 工具结果只回到对话侧，页面侧没有任何人把"作业号 + 任务号"绑进自己的跟踪面。
 *
 * <h2>口径（仿 R-155 发布冲突跟踪 `armPublishConflictWatch`）</h2>
 * store 在 `tool_result` 里取号（结果文本「作业 #N」）+ 工具段 args 的 taskId，
 * 调页面自己注册的能力 `export.syncJob` / `import.syncJob`（AI 侧不 import 任何视图）；
 * 页面 handler 在 taskId 匹配时走既有 `resumeJobs()`，终态时再刷一次结果面。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ElMessage } from 'element-plus'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, unregisterPageHandler } from '@/stores/workspace'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  downloadFile: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  getJob: vi.fn(),
  openTaskEvents: vi.fn(),
  listDefinitions: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '7' } }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

vi.mock('@/components/SpreadGrid.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'SpreadGrid', setup: () => () => null }) }
})
vi.mock('@/components/TaskWizard.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'TaskWizard', setup: () => () => null }) }
})
vi.mock('@/components/JobProgress.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'JobProgress', props: { job: { type: Object, default: null } }, emits: ['cancel'], setup: () => () => null }) }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: api }))

vi.mock('@/api/ai', () => ({
  aiApi: {
    health: vi.fn(),
    history: vi.fn(),
    runsCurrent: vi.fn(),
    chat: vi.fn(),
    frontendToolResult: vi.fn(),
    reattachRun: vi.fn(),
    feedbackSubmit: vi.fn(),
    confirm: vi.fn()
  }
}))

const TASK_7 = {
  id: 7,
  type: 'EXPORT',
  status: 'IN_PROGRESS',
  items: [],
  currentStep: 'EXPORT'
}

function streamingMessage(): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '',
    parts: [],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

/** 走真实帧路径：tool_start（带 args）→ tool_result（带结果文本）。 */
function feedJobFrames(
  store: ReturnType<typeof useAiStore>,
  name: 'start_export' | 'start_precheck' | 'start_import',
  result: string,
  ok = true,
  args = '{"taskId":7}',
  seq = 1
): void {
  store.handleFrame({
    type: 'tool_start', toolCallId: `c-${seq}`, name, kind: 'CONFIRM', args, runId: 'run-1', seq
  })
  store.handleFrame({
    type: 'tool_result', toolCallId: `c-${seq}`, name, kind: 'CONFIRM', executed: true, ok, result, runId: 'run-1', seq: seq + 1
  })
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(TASK_7)
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(TASK_7)
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
})

afterEach(() => {
  unregisterPageHandler('export.syncJob')
  unregisterPageHandler('import.syncJob')
})

describe('D3 · store 侧：tool_result 取号并调页面能力', { timeout: 20_000 }, () => {
  it('start_export ok ⇒ 调 export.syncJob，带 taskId 与 jobId', async () => {
    const handler = vi.fn().mockResolvedValue('ok')
    registerPageHandler('export.syncJob', handler)
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobFrames(store, 'start_export', '导出作业已启动（作业 #12），请稍后查看进度')
    await flushPromises()

    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith(expect.objectContaining({ taskId: 7, jobId: 12 }))
  })

  it('start_precheck / start_import ok ⇒ 调 import.syncJob（同一页面能力，作业类型由 jobId 归属）', async () => {
    const handler = vi.fn().mockResolvedValue('ok')
    registerPageHandler('import.syncJob', handler)
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobFrames(store, 'start_precheck', '预检查已启动（作业 #31），正在检查数据格式和依赖关系…', true, '{"taskId":7}', 1)
    await flushPromises()
    expect(handler).toHaveBeenLastCalledWith(expect.objectContaining({ taskId: 7, jobId: 31 }))

    feedJobFrames(store, 'start_import', '导入作业已启动（作业 #32），数据将写入暂存区，发布前可预览', true, '{"taskId":7}', 3)
    await flushPromises()
    expect(handler).toHaveBeenLastCalledWith(expect.objectContaining({ taskId: 7, jobId: 32 }))
    expect(handler).toHaveBeenCalledTimes(2)
  })

  it('非三件套 / ok=false / 结果里没有作业号 ⇒ 不调页面能力（不猜、不空调）', async () => {
    const handler = vi.fn().mockResolvedValue('ok')
    registerPageHandler('export.syncJob', handler)
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobFrames(store, 'start_export', '导出作业已启动（作业 #12），请稍后查看进度', false)
    feedJobFrames(store, 'start_export', '导出作业已启动，请稍后查看进度')
    store.handleFrame({
      type: 'tool_start', toolCallId: 'c-9', name: 'list_tasks', kind: 'BACKEND', args: '{}', runId: 'run-1', seq: 3
    })
    store.handleFrame({
      type: 'tool_result', toolCallId: 'c-9', name: 'list_tasks', kind: 'BACKEND', executed: true, ok: true, result: '作业 #12', runId: 'run-1', seq: 4
    })
    await flushPromises()

    expect(handler).not.toHaveBeenCalled()
  })

  it('当前页没注册该能力（不在向导页）⇒ 静默跳过，不抛错、不影响对话', async () => {
    const store = useAiStore()
    store.messages = [streamingMessage()]

    expect(() => feedJobFrames(store, 'start_export', '导出作业已启动（作业 #12），请稍后查看进度'))
      .not.toThrow()
    await flushPromises()
  })
})

describe('D3 · 导出向导页：AI 触发 start_export 后 exportJob 绑定并进入跟踪', { timeout: 20_000 }, () => {
  it('作业号进页面：exportJob = 作业 #12，且开始跟踪（openTaskEvents 按 taskId 7 / jobId 12 建通道）', async () => {
    api.listJobs.mockResolvedValue([
      { id: 12, taskId: 7, jobType: 'EXPORT', status: 'RUNNING', progress: 3, total: 10, errorCount: 0, warningCount: 0, items: [] }
    ])
    const { default: ExportWizardView } = await import('@/views/ExportWizardView.vue')
    const wrapper = mount(ExportWizardView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    // 挂载期的既有恢复链（loadTask → resumeJobs）先清零：本用例只计 AI 同步这一次
    api.getIssues.mockClear()
    api.openTaskEvents.mockClear()
    api.listJobs.mockClear()

    const store = useAiStore()
    store.messages = [streamingMessage()]
    feedJobFrames(store, 'start_export', '导出作业已启动（作业 #12），请稍后查看进度')
    await flushPromises()

    // 绑定：页面按本任务的作业清单重走 resumeJobs（listJobs(7) → exportJob = #12 → getIssues(#12)）
    expect(api.listJobs).toHaveBeenCalledWith(7)
    expect(api.getIssues).toHaveBeenCalledWith(12)
    // 跟踪：进度通道按该作业建立（taskId 7 / jobId 12，与用户手点「开始导出」同一条链）
    expect(api.openTaskEvents).toHaveBeenCalledWith(7, expect.any(Function), expect.anything())
    wrapper.unmount()
  })

  it('taskId 不匹配 ⇒ 不把别的任务的作业绑进来（只回一句如实说明）', async () => {
    const { getPageHandler } = await import('@/stores/workspace')
    api.listJobs.mockResolvedValue([
      { id: 12, taskId: 7, jobType: 'EXPORT', status: 'RUNNING', progress: 3, total: 10, errorCount: 0, warningCount: 0, items: [] }
    ])
    const { default: ExportWizardView } = await import('@/views/ExportWizardView.vue')
    const wrapper = mount(ExportWizardView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    // 挂载期 resumeJobs 已按本任务恢复过现场；本用例只断言 handler 的 taskId 闸
    api.openTaskEvents.mockClear()
    api.getIssues.mockClear()
    const handler = getPageHandler('export.syncJob')
    expect(handler).not.toBeNull()

    const reply = await handler?.({ taskId: 99, jobId: 12, toolCallId: 'c-9' })

    expect(String(reply)).toContain('未同步')
    expect(api.openTaskEvents).not.toHaveBeenCalled()
    expect(api.getIssues).not.toHaveBeenCalled()
    wrapper.unmount()
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})
