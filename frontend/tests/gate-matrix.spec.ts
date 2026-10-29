/**
 * 测试扩充 V4 · C7 权限关闸（前端断言）。
 *
 * <p>四格 = 全局停用（`AI_UNAVAILABLE`，本仓等价于「无可用 provider / key 缺失」，没有 `app.ai.enabled`
 * 这种总开关）· 只读任务写动词拒执（F6 五处 + `goto_step`）· 表格四开关（读开写关矩阵的前端面）·
 * 关闸回执（`SHEET_DISABLED` 不并进 `SCOPE_NOT_DISCLOSED`）。
 *
 * <p>与既有覆盖的分界：`goto-step-readonly-guard.spec.ts` 只锁 `goto_step` 一条；
 * `sheet-tools-write.spec.ts#T-15` 锁「只读表格壳的写预览」；`sheet-tools-policy.spec.ts` 锁
 * `setSheetPolicy` 的纯函数口径 —— 本文件锁**五条写事件一次性矩阵**、**health.sheet 的落点**、
 * **关闸码在段/DOM 上的呈现**。后端 2×2 矩阵由 `SheetSwitchesTest` / `ToolRegistrySheetGateTest` /
 * `SheetDisabledGateTest` 覆盖，本批不重复。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import type { UiEvent } from '@/types/tools'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import { useWorkspaceStore, dispatchUiEvent } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { DEFAULT_SHEET_POLICY, effectiveSheetPolicy } from '@/utils/sheet-tools'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn(),
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn(),
  goToStep: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  setImportMode: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  listJobs: vi.fn()
}))

const taskStore = vi.hoisted(() => ({ cancelJob: vi.fn(), resolveWizardRoute: vi.fn() }))

vi.mock('@/api/ai', () => ({
  aiApi: api,
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: api.formOptionSource
}))
vi.mock('@/api/tasks', () => ({
  tasksApi: {
    goToStep: api.goToStep,
    selectDefs: api.selectDefs,
    setCondition: api.setCondition,
    setImportMode: api.setImportMode
  }
}))
vi.mock('@/api/jobs', () => ({
  jobsApi: { createJob: api.createJob, cancelJob: api.cancelJob, listJobs: api.listJobs }
}))
vi.mock('@/router', () => ({
  default: { push: vi.fn() },
  ROUTE_NAMES: { exportWizard: 'export', importWizard: 'import', taskCenter: 'tasks' }
}))
vi.mock('@/stores/task', () => ({ useTaskStore: () => taskStore }))

const stubs = {
  'el-alert': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-empty': true,
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': true,
  'el-skeleton': true,
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  FormRenderer: true
}

function healthFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    available: true, code: 'AI_AVAILABLE', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z',
    ...overrides
  }
}

function buildMessage(parts: MessagePart[], overrides: Partial<ChatMessage> = {}): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-1', role: 'assistant', content: text, parts, reasoning: '', toolRuns: [],
    pendingCall: null, streaming: false, done: true, ...overrides
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  taskStore.cancelJob.mockReset()
  taskStore.resolveWizardRoute.mockReset()
  api.health.mockResolvedValue(healthFixture())
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  api.chat.mockResolvedValue(undefined)
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
  workspace.taskType = null
  workspace.step = null
  workspace.taskId = null
  installIdentityFixture()
})

describe('C7-01 全局停用：不可用时发不出去，且原因如实上屏', () => {
  it('health.available=false + AI_UNAVAILABLE ⇒ 降级条给后端原文、输入框仍可编辑、只关发送、Enter 零请求', async () => {
    api.health.mockResolvedValue(healthFixture({
      available: false,
      code: 'AI_UNAVAILABLE',
      message: '未配置可用的模型供应商（或密钥缺失），AI 功能不可用'
    }))
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    await flushPromises()

    const store = useAiStore()
    expect(store.available).toBe(false)
    const degraded = wrapper.find('[data-testid="alert-degraded"]')
    expect(degraded.exists()).toBe(true)
    expect(degraded.text()).toContain('AI 能力暂不可用')
    expect(degraded.text()).toContain('未配置可用的模型供应商')

    // composer 口径：输入框**始终可编辑**（用户写草稿的权利不被降级剥夺），发送闸独立拦截。
    // 锁 disabled 会把旧形态（降级即禁输）固化回来，故这里锁"无 disabled"。
    const box = wrapper.find('[data-testid="composer-input"]')
    expect((box.element as HTMLTextAreaElement).disabled).toBe(false)
    expect(box.attributes('disabled')).toBeUndefined()
    expect(box.attributes('placeholder')).toContain('AI 当前不可用')
    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeDefined()

    // 键盘路径同样不放行（可编辑 ≠ 可发送；不是只靠按钮置灰的视觉）
    await box.setValue('还能用吗')
    await box.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.chat).not.toHaveBeenCalled()
    expect((box.element as HTMLTextAreaElement).value).toBe('还能用吗') // 草稿留在框里
    wrapper.unmount()
  }, 20_000)
})

describe('C7-02 只读任务：五条写事件 + 跳步骤一律拒执、零 API', () => {
  it('CANCELLED 任务 ⇒ select_defs / set_conditions / set_import_mode / run_job / cancel_job 全部 fail 且零副作用', async () => {
    const workspace = useWorkspaceStore()
    workspace.taskId = 7
    workspace.taskType = 'EXPORT'
    workspace.taskStatus = 'CANCELLED'

    const cases: Array<{ event: UiEvent; expect: string }> = [
      { event: { type: 'select_defs', task: 'export', defCodes: ['A', 'B'] }, expect: '未变更所选配置项' },
      { event: { type: 'set_conditions', defCode: 'A', conditions: {} }, expect: '未设置查询条件' },
      { event: { type: 'set_import_mode', mode: 'MERGE' }, expect: '未变更导入模式' },
      { event: { type: 'run_job', jobType: 'EXPORT', taskId: 7 }, expect: '未启动作业' },
      { event: { type: 'cancel_job', jobId: 11 }, expect: '未取消作业' }
    ]

    for (const item of cases) {
      const result = await dispatchUiEvent(item.event)
      expect(result.handled, item.event.type).toBe(false)
      expect(result.message, item.event.type).toContain('任务已结束')
      expect(result.message, item.event.type).toContain(item.expect)
    }

    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(api.setImportMode).not.toHaveBeenCalled()
    expect(api.createJob).not.toHaveBeenCalled()
    expect(taskStore.cancelJob).not.toHaveBeenCalled()
    // 只读壳里的写事件不写工作区动作（模型下一轮读不到"已改动"的假事实）
    expect(workspace.recentActions.filter((action) => action.source === 'AI')).toHaveLength(5)
    expect(workspace.recentActions.every((action) => action.summary.includes('任务已结束'))).toBe(true)
  })
})

describe('C7-03 表格四开关：/health.sheet 落到前端，只收窄不放宽', () => {
  it('读开写关 + 8 行 / 3 列 ⇒ 策略收窄；写开关原样保留（语义在披露面，前端不擅自放开）', async () => {
    api.health.mockResolvedValue(healthFixture({
      sheet: { readEnabled: true, writeEnabled: false, sampleMaxRows: 8, projectionMaxColumns: 3, effectiveFrom: 'config' }
    }))
    const store = useAiStore()
    await store.probeHealth()

    expect(effectiveSheetPolicy()).toEqual({ sampleMaxRows: 8, projectionMaxColumns: 3 })
    expect(store.health?.sheet?.writeEnabled).toBe(false)
    expect(store.health?.sheet?.readEnabled).toBe(true)
  })

  it('管理员把数值配大（200 / 9）⇒ 前端仍按硬顶 20 / 5；旧后端缺 sheet 块 ⇒ 回硬顶兜底', async () => {
    api.health.mockResolvedValue(healthFixture({
      sheet: { readEnabled: true, writeEnabled: true, sampleMaxRows: 200, projectionMaxColumns: 9 }
    }))
    const store = useAiStore()
    await store.probeHealth()
    expect(effectiveSheetPolicy()).toEqual(DEFAULT_SHEET_POLICY)

    api.health.mockResolvedValue(healthFixture())
    await store.probeHealth()
    expect(effectiveSheetPolicy()).toEqual({ sampleMaxRows: 20, projectionMaxColumns: 5 })
    expect(store.health?.sheet).toBeUndefined()
  })
})

describe('C7-04 关闸回执：SHEET_DISABLED 独立成「失败」，不并进「未披露」', () => {
  const disabledText = 'SHEET_DISABLED：工具 update_rows 未执行 —— 表格写入功能已被管理员关闭，本次未做任何修改'
    + '（管理员关闭了 app.ai.sheet.write-enabled）。不要重试；请让用户联系管理员开启表格写入。'

  function messageWithFrames(): ChatMessage {
    const message = buildMessage([], { id: 'a-sheet', streaming: true, done: false })
    applyFrameToParts(message, {
      type: 'tool_start', toolCallId: 'tc-sheet', name: 'update_rows', kind: 'FRONTEND', args: '{"defCode":"CURRENCY"}'
    } as SseFrame)
    applyFrameToParts(message, {
      type: 'tool_result', toolCallId: 'tc-sheet', name: 'update_rows', kind: 'FRONTEND',
      ok: false, executed: false, status: 'SHEET_DISABLED', outcome: 'failed', code: 'SHEET_DISABLED',
      result: disabledText
    } as SseFrame)
    return message
  }

  it('段状态 = failed（不是 blocked）、零「原因」行、原文进输出区且不带截断角标', async () => {
    const message = messageWithFrames()
    const part = message.parts.find((item) => item.kind === 'tool')
    expect(part?.kind).toBe('tool')
    if (!part || part.kind !== 'tool') return
    expect(part.status).toBe('failed')
    expect(part.status).not.toBe('blocked')
    expect(part.reason).toBeNull()
    expect(part.result).toContain('表格写入功能已被管理员关闭')
    expect(part.result).toContain('不要重试')
    expect(part.truncated).toBe(false)

    const store = useAiStore()
    store.messages = [message]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    await flushPromises()

    expect(wrapper.find('[data-testid="card-status-tc-sheet"]').text()).toContain('失败')
    expect(wrapper.find('[data-testid="tool-output-text"]').text()).toContain('表格写入功能已被管理员关闭')
    expect(wrapper.find('[data-testid="tool-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="truncated-badge"]').exists()).toBe(false)
    wrapper.unmount()
  }, 20_000)
})
