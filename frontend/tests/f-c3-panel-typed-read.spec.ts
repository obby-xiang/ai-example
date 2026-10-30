/**
 * F-C3：AiPanel 的恢复/轮次适配层只做**类型化直读** —— 不再按候选名嗅探不存在的旧字段/动作别名，
 * 也不再有"无 `busyRun` 时拿 `activeRunId` 猜一轮"的不可达兜底。
 *
 * <p>本套用例用**旧别名**注入状态：改造前它们会被 `storeField` / `storeAction` 命中并驱动 UI；
 * 改造后 store 的真实契约（`busyRun` / `historyError` / `currentError` / `retryRestoreSnapshot()` /
 * `retryLoadHistory()`、`message.unconfirmed` + `lastRoundOutcome`）仍然照常判定。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn()
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

type LooseStore = Record<string, unknown>

function buildMessage(parts: MessagePart[], id = 'a-1'): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id,
    role: 'assistant',
    content: text,
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

function textMessage(id = 'a-1'): ChatMessage {
  return buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }], id)
}

async function mountPanel(messages: ChatMessage[], state: LooseStore = {}): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  for (const [key, value] of Object.entries(state)) {
    ;(store as unknown as LooseStore)[key] = value
  }
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

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
  api.history.mockResolvedValue({ messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false })
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('F-C3 · unconfirmed 只认当前判定链', () => {
  it('message.turnUnconfirmed 旧别名不再当未确认', async () => {
    const reply = textMessage('a-alias')
    ;(reply as unknown as LooseStore).turnUnconfirmed = true
    const wrapper = await mountPanel([reply])
    expect(wrapper.find('[data-testid="turn-unconfirmed-a-alias"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('message.turnState / turnEnd 旧同义字段不再当未确认', async () => {
    const reply = textMessage('a-alias2')
    ;(reply as unknown as LooseStore).turnState = 'unconfirmed'
    ;(reply as unknown as LooseStore).turnEnd = 'unconfirmed'
    const wrapper = await mountPanel([reply])
    expect(wrapper.find('[data-testid="turn-unconfirmed-a-alias2"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('store 级 turnUnconfirmed / unconfirmedRunId 旧标志不再当未确认', async () => {
    const reply = textMessage('a-alias3')
    const wrapper = await mountPanel([reply], {
      turnUnconfirmed: true,
      unconfirmedRunId: 'run-x',
      activeRunId: 'run-x'
    })
    expect(wrapper.find('[data-testid="turn-unconfirmed-a-alias3"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('保留当前链：message.unconfirmed 仍出中性视图', async () => {
    const reply = textMessage('a-keep1')
    reply.unconfirmed = true
    const wrapper = await mountPanel([reply])
    expect(wrapper.find('[data-testid="turn-unconfirmed-a-keep1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('保留当前链：最后一轮 lastRoundOutcome=unconfirmed 仍出中性视图', async () => {
    const reply = textMessage('a-keep2')
    const wrapper = await mountPanel([reply], { lastRoundOutcome: 'unconfirmed' })
    expect(wrapper.find('[data-testid="turn-unconfirmed-a-keep2"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('F-C3 · 恢复错误只认 historyError / currentError', () => {
  it('旧别名 restoreError / snapshotError 不再产错误条', async () => {
    const wrapper = await mountPanel([textMessage()], {
      restoreError: { scope: 'history', code: 'NETWORK_ERROR', status: 0, at: Date.now() },
      snapshotError: { scope: 'current', code: 'NETWORK_ERROR', status: 0, at: Date.now() }
    })
    expect(wrapper.find('[data-testid="alert-history-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('historyError 直读：错误条 + 重试走真实 retryLoadHistory', async () => {
    const store = useAiStore()
    const retry = vi.spyOn(store, 'retryLoadHistory').mockResolvedValue(undefined)
    const wrapper = await mountPanel([textMessage()], {
      historyError: { scope: 'history', code: 'NETWORK_ERROR', status: 0, at: Date.now() }
    })
    const bar = wrapper.find('[data-testid="alert-history-error"]')
    expect(bar.exists()).toBe(true)
    await bar.find('[data-testid="retry-history"]').trigger('click')
    expect(retry).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('currentError 直读：重试走真实 retryRestoreSnapshot', async () => {
    const store = useAiStore()
    const restore = vi.spyOn(store, 'retryRestoreSnapshot').mockResolvedValue(undefined)
    const wrapper = await mountPanel([textMessage()], {
      currentError: { scope: 'current', code: 'NETWORK_ERROR', status: 0, at: Date.now() }
    })
    const bar = wrapper.find('[data-testid="alert-history-error"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('会话恢复失败')
    await bar.find('[data-testid="retry-history"]').trigger('click')
    expect(restore).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('F-C3 · 409 回执只认 busyRun', () => {
  it('busyRun 直读：出「回到进行中的回答」，点击走真实 reattachBusyRun', async () => {
    const store = useAiStore()
    const reattach = vi.spyOn(store, 'reattachBusyRun').mockResolvedValue(undefined)
    const wrapper = await mountPanel([textMessage()], {
      notice: '这条消息没有发出去，请稍后再试。',
      noticeCode: 'ai.notice.sendFailed',
      busyRun: { runId: 'run-busy', reattach: null }
    })
    const button = wrapper.find('[data-testid="return-active-run"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(reattach).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('旧别名 busyRunId / sessionBusyRunId 与 activeRunId 兜底都不再出入口', async () => {
    const wrapper = await mountPanel([textMessage()], {
      notice: '这条消息没有发出去，请稍后再试。',
      noticeCode: 'ai.notice.sendFailed',
      activeRunId: 'run-active',
      busyRunId: 'run-legacy',
      sessionBusyRunId: 'run-legacy2',
      busyRun: null
    })
    expect(wrapper.find('[data-testid="return-active-run"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
