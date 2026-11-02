/**
 * B4 面板口径：
 * - 真实 L2 `compacting` 心跳只给宿主消息加紫色「压缩中…」，顶部不再出现压缩阶段条；
 * - 普通轮没有阶段条，只有"工作中"呼吸；
 * - `preparing` 顶部条仍保留（带重试出口）；`streaming` 心跳清掉准备中/紫色过程态。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
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
  getContext: vi.fn()
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
  installIdentityFixture()
})

describe('批次3 · 阶段条口径（2026-10-29 ToC 呈现减负批改写）', () => {
  it('真实压缩心跳 ⇒ 只给宿主消息紫色「压缩中…」，顶部无压缩条', async () => {
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
    const compacting = wrapper.find('[data-testid="compaction-message"]')
    expect(compacting.exists()).toBe(true)
    // 已用时间按"服务端 elapsed 折算的锚点 + 本地流逝"推进（挂载后可能多走 1 秒）
    expect(compacting.text()).toMatch(/压缩中… · 已用 1[23] 秒/)
    // B4：顶部不再出现压缩阶段条；紫色过程态占元信息行，蓝色工作中让位
    expect(wrapper.find('[data-testid="compaction-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(false)

    // 压缩完成（context 帧）⇒ 紫色过程态退场，回到蓝色工作中；顶部仍无压缩提示
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
    expect(wrapper.find('[data-testid="compaction-message"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="compaction-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('压缩播报：只播进入与真实提交完成；streaming 退场不播、同文案连续提交会重播', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = true
    ai.messages = [host()]
    ai.handleFrame(asFrame({ type: 'start', runId: 'run-live', sessionId: 'sess' }))

    const wrapper = await mountPanel()
    const live = wrapper.find('[data-testid="compaction-live"]')
    // 播报是"清空 → 等一帧 → 写入"两步：等两帧再看最终文案
    const settle = async (): Promise<void> => {
      await nextTick()
      await nextTick()
    }
    expect(live.text()).toBe('')
    expect(live.attributes('aria-live')).toBe('polite')

    ai.handleFrame(asFrame({ type: 'heartbeat', phase: 'compacting', elapsedMs: 2_000 }))
    await settle()
    expect(live.text()).toBe('正在压缩上下文')
    // 秒表是可见文本，不随秒数进入 live region
    expect(live.text()).not.toContain('秒')

    // 真实顺序：streaming 退场（未提交，紫色消失）不产生任何退出播报
    ai.handleFrame(asFrame({ type: 'heartbeat', phase: 'streaming', elapsedMs: 2_500 }))
    await settle()
    expect(live.text()).toBe('正在压缩上下文')
    expect(live.text()).not.toContain('完成')

    // 随后 committed 事实到达：只播「完成」（中间没有"过程已结束"）
    const commit = (eventId: string) => ai.handleFrame(asFrame({
      type: 'context',
      level: 'L2',
      before: 90_000,
      after: 50_000,
      turns: 3,
      lostTokens: 40_000,
      reason: 'l2',
      est: false,
      committed: true,
      eventId,
      runId: 'run-live',
      atMs: Date.now()
    }))
    commit('run-live:c1')
    await settle()
    expect(live.text()).toBe('上下文压缩完成')

    // 第二次真实提交：文案与上次相同，但 live region 仍要经历一次清空后的新变化（读屏会重播）
    const seen: string[] = []
    const observer = new MutationObserver(() => {
      seen.push(live.element.textContent ?? '')
    })
    observer.observe(live.element, { childList: true, characterData: true, subtree: true })
    commit('run-live:c2')
    await settle()
    await Promise.resolve()
    observer.disconnect()
    expect(seen).toContain('')
    expect(live.text()).toBe('上下文压缩完成')

    // 停止/换轮清过程态：不额外播退出（live region 保持上一条播报）
    ai.handleFrame(asFrame({ type: 'heartbeat', phase: 'compacting', elapsedMs: 1_000 }))
    await settle()
    expect(live.text()).toBe('正在压缩上下文')
    ai.clearCompactingMessages()
    await settle()
    expect(live.text()).toBe('正在压缩上下文')

    // 换会话：播报现场清空（下一会话首次同文案不会被吞）
    ai.sessionId = 'sess-next'
    await nextTick()
    expect(live.text()).toBe('')
    wrapper.unmount()
  })

  it('HITL 等待优先：琥珀「等待中…」压过紫色压缩过程态', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = false
    ai.suspended = true
    const waiting = host()
    waiting.compactingStartedAtMs = Date.now() - 3_000
    waiting.parts.push({
      kind: 'tool',
      seg: 1,
      toolCallId: 'c-1',
      name: 'start_publish',
      kind2: 'CONFIRM',
      status: 'pending',
      requiresAction: true
    })
    ai.messages = [waiting]

    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="park-waiting"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="compaction-message"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="compaction-status"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('history.truncated 在消息流内只显示一条中性标记（无按钮/非压缩事件）', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.historyTruncated = true
    ai.messages = [host()]

    const wrapper = await mountPanel()
    const marker = wrapper.find('[data-testid="history-truncated"]')
    expect(marker.exists()).toBe(true)
    expect(marker.attributes('role')).toBe('note')
    expect(marker.text()).toContain('更早的对话未在本机保留')
    expect(marker.find('button').exists()).toBe(false)
    expect(wrapper.find('[data-testid="compaction-message"]').exists()).toBe(false)
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

  it('普通轮只有 streaming 心跳 ⇒ 不出任何阶段条，只剩「工作中」呼吸', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = true
    ai.messages = [host()]
    ai.handleFrame(asFrame({ type: 'start', runId: 'run-plain', sessionId: 'sess' }))
    // 首轮/续跑初始保活相位 = streaming（普通请求不再无条件公告 compacting）
    ai.handleFrame(asFrame({ type: 'heartbeat', phase: 'streaming', elapsedMs: 0 }))

    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="compaction-status"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('正在压缩上下文')
    expect(wrapper.find('[data-testid="loading-breath"]').text()).toContain('工作中')
    expect(wrapper.find('[data-testid="compaction-message"]').exists()).toBe(false)
    expect(ai.preparingRun).toBeNull()
    wrapper.unmount()
  })

  it('准备中阶段条随 streaming 心跳退场（摘要结束立即恢复工作中）', async () => {
    const ai = useAiStore()
    ai.expanded = true
    ai.historyLoaded = true
    ai.loading = true
    ai.messages = [host()]
    ai.handleFrame(asFrame({ type: 'start', runId: 'run-prep', sessionId: 'sess' }))
    ai.notePreparingHeartbeat('run-prep', 1_000)

    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="compaction-status"]').attributes('data-stage')).toBe('preparing')

    ai.handleFrame(asFrame({ type: 'heartbeat', phase: 'streaming', elapsedMs: 0 }))
    await nextTick()
    expect(ai.preparingRun).toBeNull()
    expect(wrapper.find('[data-testid="compaction-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="loading-breath"]').text()).toContain('工作中')
    wrapper.unmount()
  })
})
