/**
 * 批G 面板层回归锁 —— G5（删除「一键重挂」概念）/ G6（在途轮零提示）/ G8（刷新后停止入口）/
 * S2（停止中按钮态）/ S3（「已停止」标识与「继续」互斥）。
 *
 * <p>
 * 规格出处（仓内既有口径，本次只按用户直裁补上"零提示"这一条）：
 * `AiPanel.vue` 的 `onComposerKeydown` 注释「空闲普通 Enter 走现有 send；流式中 preventDefault
 * 后静默返回（不发送 / 不停止 / 不提示 / 不换行）」，以及 `composerHardDisabled` 注释
 * 「`ai.loading`（流式生成中）**不**硬禁用 —— 生成中仍可继续编辑」。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
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

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

function assistantMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '半截正文',
    parts: [{ kind: 'text', seg: 0, text: '半截正文', streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...overrides
  }
}

/** 面板与本用例共享同一个 store 实例（静态 import 保证同一模块图）。 */
function resetStore(): ReturnType<typeof useAiStore> {
  const store = useAiStore()
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.streamInterrupted = false
  store.activeRunId = null
  store.lastRoundOutcome = 'ok'
  store.historyLoaded = true
  return store
}

async function mountPanel(): Promise<{ wrapper: ReturnType<typeof mount>; store: ReturnType<typeof useAiStore> }> {
  const store = resetStore()
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return { wrapper, store }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetStore()
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.deleteHistory.mockResolvedValue({})
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('批G · G5：删除「一键重挂」（横幅 / 占位文案 / runId / 技术措辞）', () => {
  it('断流提示不再提供重挂按钮，也不出现 runId 或「重挂」字样', async () => {
    const { wrapper, store } = await mountPanel()
    store.streamInterrupted = true
    store.activeRunId = 'run-x'
    store.notice = '连接中断，结果可能不完整'
    await wrapper.vm.$nextTick()

    const text = wrapper.text()
    expect(text).not.toContain('重挂')
    expect(text).not.toContain('runId')
    expect(text).not.toContain('正在重挂')
    expect(text).not.toContain('本轮未开始')
    expect(wrapper.find('[data-testid="alert-busy-conflict"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('批G · G6：在途轮输入框可输入、发送零响应（零提示）', () => {
  it('loading 中输入照常、回车不发、无任何提示、草稿保留', async () => {
    const { wrapper, store } = await mountPanel()
    store.loading = true
    await wrapper.vm.$nextTick()

    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.attributes('disabled')).toBeUndefined() // 可输入

    await box.setValue('在途草稿')
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    box.element.dispatchEvent(event)
    await wrapper.vm.$nextTick()

    expect(event.defaultPrevented).toBe(true) // 静默短路（不换行、不发）
    expect(api.chat).not.toHaveBeenCalled()
    expect((box.element as HTMLTextAreaElement).value).toBe('在途草稿')
    expect(store.notice).toBeNull() // 零提示
    expect(wrapper.find('[data-testid="notice-bar"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('批G · G8：刷新后在途轮恢复停止入口', () => {
  it('RUNNING 快照恢复后（静默续收中）停止按钮出现，点击即取消该轮', async () => {
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-g8', snapshotStatus: 'RUNNING', pendingEntries: [],
      unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 3
    })

    const { wrapper, store } = await mountPanel()
    await wrapper.vm.$nextTick()

    expect(store.loading).toBe(true) // 在途语义恢复 ⇒ 停止入口的前提
    const stopBtn = wrapper.find('[data-testid="stop-btn"]')
    expect(stopBtn.exists()).toBe(true)
    expect(stopBtn.attributes('disabled')).toBeUndefined()

    await stopBtn.trigger('click')
    expect(api.cancel).toHaveBeenCalledWith('run-g8')
    wrapper.unmount()
  })
})

describe('批G · S2：停止中按钮态', () => {
  it('stopping 时停止按钮 disabled 且文案为「停止中…」', async () => {
    const { wrapper, store } = await mountPanel()
    store.loading = true
    store.stopping = true
    await wrapper.vm.$nextTick()

    const stopBtn = wrapper.find('[data-testid="stop-btn"]')
    expect(stopBtn.exists()).toBe(true)
    expect(stopBtn.attributes('disabled')).toBeDefined()
    expect(stopBtn.attributes('aria-label')).toBe('停止中…')
    wrapper.unmount()
  })
})

describe('批G · S3：「已停止」标识与「继续」入口互斥', () => {
  it('stopped 消息出「已停止」chip，且不出「继续」按钮', async () => {
    const { wrapper, store } = await mountPanel()
    store.messages = [assistantMessage({ stopped: true })]
    store.lastRoundOutcome = 'cancelled'
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="msg-stopped-a-1"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('已停止')
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('出错轮（未停止）仍出「继续」按钮、无「已停止」chip', async () => {
    const { wrapper, store } = await mountPanel()
    store.messages = [assistantMessage()]
    store.lastRoundOutcome = 'error'
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="msg-stopped-a-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
