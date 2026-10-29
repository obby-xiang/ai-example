/**
 * 压缩与准备心跳仍在 store 保留，但面板不显示阶段条；准备期沿用现有工作中提示。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useAiStore } from '@/stores/ai'
import { applyLocale } from '@/locales'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  deleteHistory: vi.fn(),
  getContext: vi.fn(),
  retryModelStep: vi.fn()
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

function host(): ChatMessage {
  return {
    id: 'a-b3',
    role: 'assistant',
    content: '',
    parts: [{ kind: 'text', seg: 0, text: '', streaming: true }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

function asFrame(frame: Record<string, unknown>): SseFrame {
  return frame as unknown as SseFrame
}

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await nextTick()
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
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false })
  applyLocale('zh-CN')
})

describe('批次3 · 阶段条口径（2026-10-29 ToC 呈现减负批改写）', () => {
  it('压缩心跳 ⇒ 出 compacting 阶段条（替代旧「阶段条退场」口径）', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = true
    ai.messages = [host()]
    ai.handleFrame(asFrame({ type: 'start', runId: 'run-panel', sessionId: 'sess' }))
    ai.handleFrame(asFrame({
      type: 'heartbeat',
      phase: 'compacting',
      elapsedMs: 12_000
    }))

    const wrapper = await mountPanel()
    const bar = wrapper.find('[data-testid="compaction-status"]')
    expect(bar.exists()).toBe(true)
    expect(bar.attributes('data-stage')).toBe('compacting')
    // 已用时间按"服务端 elapsed 折算的锚点 + 本地流逝"推进（挂载后可能多走 1 秒）
    expect(bar.text()).toMatch(/正在压缩上下文 · 已用 1[23] 秒/)
    // 压缩提示与「工作中」呼吸并存：阶段条说的是"在做什么"，呼吸说的是"还在产出"
    expect(wrapper.find('[data-testid="loading-breath"]').text()).toContain('工作中')
    expect(ai.compactionPhase).toBe('compacting')

    // 压缩完成（context 帧）⇒ 阶段条退场（不再多一条「压缩完成」提示面）
    ai.handleFrame(asFrame({
      type: 'context',
      level: 'L2',
      before: 90_000,
      after: 50_000,
      turns: 3,
      lostTokens: 40_000,
      reason: 'l2',
      est: false
    }))
    await nextTick()
    expect(wrapper.find('[data-testid="compaction-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="compaction-done"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('准备中出阶段条（带重试出口），runId 不上屏', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = true
    ai.messages = [host()]
    ai.preparingRun = { runId: 'secret-run-id', startedAtMs: Date.now() - 5_000, elapsedMs: null }

    const wrapper = await mountPanel()
    const bar = wrapper.find('[data-testid="compaction-status"]')
    expect(bar.exists()).toBe(true)
    expect(bar.attributes('data-stage')).toBe('preparing')
    expect(bar.find('[data-testid="compaction-retry"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="loading-breath"]').text()).toContain('工作中')
    expect(wrapper.text()).not.toContain('secret-run-id')
    wrapper.unmount()
  })
})
