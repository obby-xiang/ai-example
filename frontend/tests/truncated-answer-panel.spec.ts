/**
 * 截断专项（面板渲染，zh / en）：模型输出达到长度上限时，AiPanel 在消息元信息行渲染
 * 「已截断」徽标 + sr-only 完整说明（可访问文本），并且**不**渲染段级截断圆点。
 *
 * <p>为什么单独成文件：面板用例需要组件与 store 解析到同一个模块注册表 / 同一个 active Pinia；
 * `vi.resetModules()` 的流式用例与组件挂载混在同一个文件里会让面板读到上一条用例的 store
 * （实测）。本文件只做挂载渲染，store 一律「新 Pinia、不复位模块」。
 *
 * <p>可访问性口径（modern-web-guidance · accessibility）：这是**持久状态文本**，不是动态告警
 * —— 不做 `aria-live` 打断式播报；可见短标签 + `sr-only` 完整句，读屏顺读即可理解
 * "回答被长度上限截断、可能不完整"。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { ChatMessage } from '@/types/ai'

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
// 收集期预热：用例内的动态 import（`freshStore()` / `mountPanel()`）在**首条用例**里要付
// "首次 transform + 首次装配"的一次性成本 —— 空载 1.9–3.4s，并发满载可涨到十几秒，直接吃掉
// 该用例自己的 timeout 预算（实测：首条超时、同文件后续用例 10–40ms 通过）。这里放到收集期
// 付掉（收集不受单用例 timeout 约束），用例内动态 import 只剩重执行（~20ms）；断言与 timeout 不变。
import '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import '@/components/AiPanel/AiPanel.vue'


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

function assistantMessage(id: string, text: string, extra: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id,
    role: 'assistant',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...extra
  }
}

function userMessage(id: string, text: string): ChatMessage {
  return {
    id,
    role: 'user',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

/** 新 Pinia + 新 store；**不**复位模块注册表（组件与 store 必须同源）。 */
async function freshStore() {
  const pinia = await import('pinia')
  pinia.setActivePinia(pinia.createPinia())
  const mod = await import('@/stores/ai')
  const store = mod.useAiStore()
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.activeRunId = null
  store.runSeq = {}
  store.historyLoaded = true
  store.lastRoundOutcome = 'ok'
  return store
}

async function mountPanel(messages: ChatMessage[], sessionId: string): Promise<ReturnType<typeof mount>> {
  const store = await freshStore()
  store.sessionId = sessionId
  store.messages = messages
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

beforeEach(() => {
  sessionStorage.clear()
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

afterEach(async () => {
  const { applyLocale } = await import('@/locales')
  applyLocale('zh-CN')
  vi.restoreAllMocks()
})

describe('截断专项 · 面板徽标（zh / en）', () => {
  it('中文：可见「已截断」+ 读屏完整说明；不渲染段级截断圆点', async () => {
    const wrapper = await mountPanel(
      [userMessage('u-panel', '写一段长回答'), assistantMessage('a-panel', '半截回答', { truncated: true })],
      's-trunc-panel'
    )

    const badge = wrapper.find('[data-testid="msg-truncated-a-panel"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('已截断')
    expect(badge.text()).toContain('内容可能不完整')
    expect(wrapper.find('[data-testid="text-truncated-badge"]').exists()).toBe(false)
    expect(badge.attributes('title')).toBeUndefined()
    wrapper.unmount()
  })

  it('英文：徽标与说明走 en-US locale，无 key 原文泄漏', async () => {
    const { applyLocale } = await import('@/locales')
    applyLocale('en-US')
    const wrapper = await mountPanel(
      [userMessage('u-panel', 'Write a long answer'), assistantMessage('a-panel', 'Half answer', { truncated: true })],
      's-trunc-panel-en'
    )

    const badge = wrapper.find('[data-testid="msg-truncated-a-panel"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('Truncated')
    expect(badge.text()).toContain('length limit')
    expect(badge.text()).not.toContain('ai.round.')
    expect(badge.text()).not.toContain('已截断')
    wrapper.unmount()
  })

  it('未截断的消息不渲染徽标', async () => {
    const wrapper = await mountPanel(
      [userMessage('u-none', '问题'), assistantMessage('a-none', '完整回答')],
      's-trunc-panel-none'
    )

    expect(wrapper.find('[data-testid="msg-truncated-a-none"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
