/**
 * 批次4 集成返修 / 确认卡：`create_task` 的预检 target **没有任务号**。
 *
 * 事实源 = 后端 `ToolPreflightService#inspectCreate`：target 只有 `{type,title}`
 * （任务在**批准后**才存在，预检只判创建参数是否可用）。卡头若沿用「任务 #<id>」模板，
 * 会在真实数据上渲染出 `任务 #undefined` —— 这是可确证的卡面错误，本文件锁住「创建」句式：
 * 待决态 = `待你确认：创建<类型>任务『<标题>』`，只读态 = 同句去前缀。
 *
 * 只挂载面板（Element Plus 全 stub）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import type { ToolPreflight } from '@/types/sse'
import { useAiStore } from '@/stores/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  getContext: vi.fn(),
  runsCurrent: vi.fn(),
  runs: vi.fn(),
  run: vi.fn(),
  resume: vi.fn(),
  retryModelStep: vi.fn(),
  pending: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  cancelStatus: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  feedbackSubmit: vi.fn(),
  feedbackList: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

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

/** create_task 的预检载荷（target 只有 type/title，无 taskId/status）。 */
const CREATE_PREFLIGHT = {
  target: { type: 'EXPORT', title: '月报' },
  checks: [{ name: '创建类型合法', pass: true, detail: '' }],
  impact: '将创建 EXPORT 任务「月报」（创建≠执行，不启动作业）',
  conditionSummary: '无筛选条件（创建任务）',
  publishMode: null,
  reportHash: 'hash-create'
} as unknown as ToolPreflight

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
    memoryBackend: 'redis',
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
})

function hitlMessage(part: ToolPart): ChatMessage {
  return {
    id: 'a-create',
    role: 'assistant',
    content: '',
    parts: [part],
    reasoning: '',
    toolRuns: [],
    pendingCall: {
      toolCallId: part.toolCallId,
      name: 'create_task',
      kind: 'CONFIRM',
      args: { type: 'EXPORT', title: '月报' },
      rawArgs: '{"type":"EXPORT","title":"月报"}',
      timeoutSeconds: 120,
      status: 'pending'
    },
    streaming: false,
    done: false
  } as unknown as ChatMessage
}

async function mountPanel(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('create_task 确认卡：预检 target 无任务号 ⇒ 用「创建」句式', () => {
  it('待决卡头是「待你确认：创建导出配置任务『月报』」，不出现 undefined 任务号', async () => {
    const wrapper = await mountPanel([
      hitlMessage({
        kind: 'tool',
        seg: 0,
        toolCallId: 'c-create',
        name: 'create_task',
        kind2: 'CONFIRM',
        status: 'pending',
        args: '{"type":"EXPORT","title":"月报"}',
        timeoutSeconds: 120,
        requiresAction: true,
        preflight: CREATE_PREFLIGHT
      })
    ])

    const title = wrapper.find('[data-testid="card-title-c-create"]').text()
    expect(title).toBe('待你确认：创建导出配置任务『月报』')
    expect(title).not.toContain('undefined')
    expect(title).not.toContain('#')
    // 知情摘要三字段仍按后端给值渲染（影响面 / 条件摘要；publishMode=null 不出行）
    expect(wrapper.find('[data-testid="gate-facts-toggle"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('已决只读卡头去掉「待你确认：」前缀，仍是创建句（不套任务号模板）', async () => {
    const wrapper = await mountPanel([
      hitlMessage({
        kind: 'tool',
        seg: 0,
        toolCallId: 'c-create-done',
        name: 'create_task',
        kind2: 'CONFIRM',
        status: 'succeeded',
        decision: 'approved',
        args: '{"type":"EXPORT","title":"月报"}',
        result: '已创建导出任务 #12',
        requiresAction: false,
        preflight: CREATE_PREFLIGHT
      })
    ])

    const title = wrapper.find('[data-testid="card-title-c-create-done"]').text()
    expect(title).toBe('创建导出配置任务『月报』')
    expect(title).not.toContain('undefined')
    wrapper.unmount()
  })
})
