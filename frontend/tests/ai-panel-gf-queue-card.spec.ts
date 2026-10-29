/**
 * 批次4 集成返修 / AR-07 卡面：**排队中的表单卡必须有说明与取消入口**。
 *
 * 裁决来源 = `docs/research/裁决-AI交互整改实施口径-2026-10-26.md` AR-07 第 4 条
 * （「队列中的每一张都可被取消；取消出口恒在（不留死卡）」）。
 * store 侧队列（`gf-form-queue.spec.ts`）已保证"第二张不覆盖、按到达序补上"；本文件锁**卡面**：
 * 第二张在轮到它之前只能以排队卡出现 —— 必须写清"第几张 / 共几张 / 何时会出现"，并给出取消按钮，
 * 不得是一块没有任何说明、也点不动的空白卡体。
 *
 * 只挂载面板（Element Plus 全 stub），数据经真实 store 装配。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'

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

const FORM_A = {
  scenario: 'FILTER',
  title: '第一张表单',
  fields: [{ key: 'name', label: '名称', type: 'text', required: true }]
} as unknown as FormSpec

const FORM_B = {
  scenario: 'FILTER',
  title: '第二张表单',
  fields: [{ key: 'code', label: '编码', type: 'text', required: true }]
} as unknown as FormSpec

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
  api.frontendToolResult.mockResolvedValue({ accepted: true, duplicate: false })
  // 重连不 await（store 注释明示）：给一个永不结算的 Promise 即可，断言只关心"有没有发起"
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  installIdentityFixture()
})

/** 一条持三张 generative_form 待决段的消息（同批到达：一张活动 + 两张排队，用来锁顺序）。 */
function messageWithThreeForms(): ChatMessage {
  const parts: MessagePart[] = ['call-1', 'call-2', 'call-3'].map((toolCallId, seg): MessagePart => ({
    kind: 'tool',
    seg,
    toolCallId,
    name: 'generative_form',
    kind2: 'FRONTEND',
    status: 'pending',
    args: JSON.stringify({ form: seg === 0 ? FORM_A : FORM_B }),
    timeoutSeconds: 120,
    requiresAction: true
  }))
  return {
    id: 'a-queue',
    role: 'assistant',
    content: '',
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
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

describe('GF 排队卡（AR-07 卡面）', () => {
  it('排队中的表单卡显示排队位次与总数，并提供可用的取消入口', async () => {
    const store = useAiStore()
    const host = messageWithThreeForms()
    store.activeForm = {
      toolCallId: 'call-1',
      runId: 'run-1',
      form: FORM_A,
      status: 'filling',
      error: null,
      values: {},
      fieldErrors: []
    }
    store.queuedForms = [
      { toolCallId: 'call-2', runId: 'run-1', form: FORM_B },
      { toolCallId: 'call-3', runId: 'run-1', form: FORM_B }
    ]

    const wrapper = await mountPanel([host])

    // ① 两张排队卡各写清「第几张 / 共几张」—— 不是两块无说明的空白卡体（数量 + 顺序都在卡上）
    const queueCards = wrapper.findAll('[data-testid="gf-queue-card"]')
    expect(queueCards).toHaveLength(2)
    expect(queueCards.map((card) => card.attributes('data-tool-call-id'))).toEqual(['call-2', 'call-3'])
    expect(queueCards[0]?.find('[data-testid="gf-queue-position"]').text()).toBe('排队中 · 第 1 / 2 张')
    expect(queueCards[1]?.find('[data-testid="gf-queue-position"]').text()).toBe('排队中 · 第 2 / 2 张')
    // 每张都有一句"为什么现在不能填"（不留无说明死卡）
    expect(queueCards[0]?.find('[data-testid="gf-queue-hint"]').text()).toContain('正在填写另一张表单')

    // ② 活动表单那张**不渲染**排队卡（单活动表单）
    expect(queueCards.some((card) => card.attributes('data-tool-call-id') === 'call-1')).toBe(false)

    // ③ 取消出口逐卡归属：点第一张排队卡的取消 ⇒ 只收 call-2，call-3 仍在队列
    const cancel = queueCards[0]!.find('[data-testid="gf-queue-cancel"]')
    expect(cancel.exists()).toBe(true)
    await cancel.trigger('click')
    await flushPromises()

    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(api.frontendToolResult.mock.calls[0]?.[0]).toMatchObject({ toolCallId: 'call-2', cancelled: true })
    expect(store.queuedForms.map((entry) => entry.toolCallId)).toEqual(['call-3'])
    expect(store.activeForm?.toolCallId).toBe('call-1')
    wrapper.unmount()
  })

  it('运行态到期提示与排队卡共存：到期说明仍出，排队卡仍可取消（不出现无说明死卡）', async () => {
    const store = useAiStore()
    const host = messageWithThreeForms()
    store.activeRunId = 'run-1'
    // 已到期锚点（剩余 0）：面板出「本轮运行态已失效」提示，排队卡的自有说明与取消入口同时保留
    store.runExpiry = { runId: 'run-1', remainingMs: 0, expiresAt: Date.now() - 1_000, receivedAtMs: Date.now() }
    store.activeForm = {
      toolCallId: 'call-1',
      runId: 'run-1',
      form: FORM_A,
      status: 'filling',
      error: null,
      values: {},
      fieldErrors: []
    }
    store.queuedForms = [
      { toolCallId: 'call-2', runId: 'run-1', form: FORM_B },
      { toolCallId: 'call-3', runId: 'run-1', form: FORM_B }
    ]

    const wrapper = await mountPanel([host])

    expect(wrapper.find('[data-testid="run-expiry-expired"]').exists()).toBe(true)
    const queued = wrapper.findAll('[data-testid="gf-queue-card"]')
    expect(queued.length).toBeGreaterThan(0)
    expect(wrapper.findAll('[data-testid="gf-queue-cancel"]').length).toBe(queued.length)
    wrapper.unmount()
  })
})
