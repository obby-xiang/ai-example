import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import type { ChatMessage } from '@/types/ai'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
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

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h', frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0, sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z' })
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
})

enableAutoUnmount(afterEach)

describe('AiPanel 恢复错误活动态', () => {
  it('恢复错误/手动重试中的未终态助手显示三点且无终态操作，真实终态恢复操作区', async () => {
    const store = useAiStore()
    const historical: ChatMessage = {
      id: 'a-history',
      role: 'assistant',
      content: '历史终态',
      parts: [{ kind: 'text', seg: 0, text: '历史终态', streaming: false }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: true,
      timestamp: 1_700_000_000_000
    }
    const host: ChatMessage = {
      id: 'a-recovery',
      role: 'assistant',
      content: '部分正文',
      parts: [
        { kind: 'text', seg: 0, text: '部分正文', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'confirm-1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{}', requiresAction: true }
      ],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: false,
      timestamp: 1_700_000_000_000,
      compactingStartedAtMs: 1
    }
    store.messages = [historical, host]
    store.historyLoaded = true
    store.suspended = true
    store.currentError = { scope: 'current', code: 'AI_REDIS_UNAVAILABLE', status: 503, at: Date.now() }
    store.manualRecovery = { scope: 'current', reason: 'load', error: store.currentError }
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await flushPromises()

    const historicalOps = wrapper.find('[data-testid="msg-ops-a-history"]')
    expect(historicalOps.find('[data-testid="msg-timestamp"]').exists()).toBe(true)
    expect(historicalOps.find('[data-testid="copy-msg-a-history"]').exists()).toBe(true)
    expect(historicalOps.find('[data-testid="feedback-up-a-history"]').exists()).toBe(true)
    const currentOps = wrapper.find('[data-testid="msg-ops-a-recovery"]')
    const recovery = currentOps.find('[data-testid="recovery-loading"]')
    expect(recovery.exists()).toBe(true)
    expect(recovery.findAll('.recovery-loading-dot')).toHaveLength(3)
    expect(currentOps.find('[data-testid="loading-breath"]').exists()).toBe(false)
    expect(currentOps.find('[data-testid="park-waiting"]').exists()).toBe(false)
    expect(currentOps.find('[data-testid="compaction-message"]').exists()).toBe(false)
    expect(currentOps.text()).not.toContain('工作中')
    expect(currentOps.text()).not.toContain('等待')
    expect(currentOps.text()).not.toContain('压缩')
    expect(currentOps.find('[data-testid="msg-timestamp"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="copy-msg-a-recovery"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="feedback-up-a-recovery"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="feedback-down-a-recovery"]').exists()).toBe(false)

    host.done = true
    store.currentError = null
    store.manualRecovery = null
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="msg-ops-a-recovery"] [data-testid="recovery-loading"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="msg-ops-a-recovery"] [data-testid="msg-timestamp"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="copy-msg-a-recovery"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="feedback-up-a-recovery"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="feedback-down-a-recovery"]').exists()).toBe(true)
  })
})
