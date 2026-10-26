/**
 * 批次3 前端 · 阶段条展示（只写不跑；聚焦 RED/GREEN 在 b3-compaction-stage.spec.ts）。
 *
 * 断言：压缩中显示"正在压缩 · 已用 N 秒"（真实秒数、无百分比、秒数不在 live region 内）、
 * context 后显示完成提示；准备中显示可重试入口且**不暴露 runId**。
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

describe('批次3 · 阶段条', () => {
  it('压缩中显示真实秒数与完成提示，不显示百分比；秒数不在 live region 内', async () => {
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
    const status = wrapper.find('[data-testid="compaction-status"]')
    expect(status.exists()).toBe(true)
    expect(status.text()).toContain('正在压缩上下文')
    expect(status.text()).toMatch(/已用 \d+ 秒/)
    expect(status.text()).not.toContain('%')
    // 视觉秒数不在 aria-live 里：live 区只有静态阶段词（不逐秒播报）
    const aria = status.find('[data-testid="compaction-aria"]')
    expect(aria.text()).toContain('开始压缩上下文')
    expect(aria.text()).not.toContain('12')

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
    expect(wrapper.find('[data-testid="compaction-done"]').text()).toContain('上下文压缩完成')
    wrapper.unmount()
  })

  it('准备中显示可重试入口、真实已用秒数，且不暴露 runId', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = true
    ai.messages = [host()]
    ai.preparingRun = { runId: 'secret-run-id', startedAtMs: Date.now() - 5_000, elapsedMs: null }
    const retry = vi.spyOn(ai, 'retryPreparing').mockResolvedValue(undefined)

    const wrapper = await mountPanel()
    const status = wrapper.find('[data-testid="compaction-status"]')
    expect(status.text()).toContain('正在准备本轮回答')
    expect(status.text()).not.toContain('secret-run-id')

    const button = status.find('[data-testid="compaction-retry"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(retry).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
