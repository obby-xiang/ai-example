/**
 * 上下文占用的"刷新恢复"与"流式中不跳动"（C-35 / C-36，上下文统计与压缩专项批①）。
 *
 * <h2>两条难点</h2>
 * <ol>
 * <li><b>刷新后不依赖本地状态</b>：`contextUsage` 是内存态（store），刷新即丢。若 pill 只靠
 * {@code done.context} 写值，刷新后它会**消失**——而"我刚看到 84k，刷新一下变没了"会被读成
 * "上下文被清了"。故 {@code loadHistory()} 必须并行拉一次端点重建（C-35）；</li>
 * <li><b>流式中不跳动</b>：半轮的真值是半轮的。若每帧刷新，进度条会在生成过程中一路爬升，
 * 读者会把它读成"要爆了"（C-36）。写值点**只有** `done` 帧与端点重建两处。</li>
 * </ol>
 *
 * <p>本文件带最小装配（health / history / runsCurrent / context 四桩都给实值），
 * 不依赖其它 spec 的桩残留。
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  getContext: vi.fn(),
  chat: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'

/** Element Plus 全部退场；FormRenderer 与本文件无关。 */
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

const measuredSnapshot = (used: number, limit: number | null) => ({
  sessionId: 's-1',
  model: 'deepseek-flash',
  turnNo: 3,
  measuredAtMs: 1_700_000_000_000,
  limitTokens: limit,
  usedTokens: used,
  percent: limit === null ? null : Math.round((used * 1000) / limit) / 10,
  source: 'stream',
  estimated: false,
  breakdown: [
    { key: 'history', tokens: 41000 },
    { key: 'toolResults', tokens: 34080 },
    { key: 'summary', tokens: 0 },
    { key: 'system', tokens: 3120 },
    { key: 'tools', tokens: 5120 },
    { key: 'input', tokens: 93 },
    { key: 'free', tokens: limit === null ? 0 : limit - used }
  ],
  compaction: {
    level: 'none',
    compactedTurns: 0,
    lostTokens: 0,
    lastAtMs: null,
    reason: null,
    circuit: 'closed',
    fuseFails: 0
  }
})

beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  Object.values(api).forEach((fn) => fn.mockReset())
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
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.getContext.mockResolvedValue(measuredSnapshot(84213, 128000))
  api.chat.mockResolvedValue(undefined)
  installIdentityFixture()
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('刷新恢复（C-35）', () => {
  it('本地无任何帧历史时，药丸由端点重建（不是靠上一次的 done.context）', async () => {
    const wrapper = await mountPanel()

    const pill = wrapper.find('[data-testid="context-pill"]')
    expect(pill.exists()).toBe(true)
    // 圆环：语义是 <progress>，值就是占比；可见文字为空，说明只有一行 tooltip
    expect(pill.element.tagName).toBe('PROGRESS')
    expect(pill.attributes('value')).toBe('65.8')
    expect(pill.text()).toBe('')
    expect(pill.attributes('title')).toBe('上下文占用 65.8%')
    // 端点确实被拉过（重建的唯一来源）
    expect(api.getContext).toHaveBeenCalled()

    wrapper.unmount()
  })

  it('端点报"从未记账"（source=none）⇒ 不渲染药丸（不说谎成 0%）', async () => {
    api.getContext.mockResolvedValue({
      ...measuredSnapshot(0, 128000),
      percent: null,
      source: 'none',
      estimated: false
    })
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="context-pill"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('端点 503 ⇒ 保持无药丸且不影响历史渲染', async () => {
    api.getContext.mockRejectedValue(new Error('503'))
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="context-pill"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="composer-input"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('新建对话清空占用量（不把上一会话的 84k 带进新会话）', async () => {
    const store = useAiStore()
    store.contextUsage = {
      usedTokens: 84213,
      limitTokens: 128000,
      percent: 65.8,
      level: 'none',
      est: false,
      circuit: 'closed'
    }
    store.adoptFreshSession()

    expect(store.contextUsage).toBeNull()
    expect(store.contextBreakdown).toBeNull()
  })
})

describe('流式中不跳动（C-36）', () => {
  it('delta/message_start 帧不改占用；done 帧才刷新', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    const host = store.recordUserMessage('你好')
    store.messages.push({
      id: 'a-1',
      role: 'assistant',
      content: '正在生成',
      parts: [{ kind: 'text', seg: 0, text: '正在生成', streaming: true }],
      reasoning: '',
      toolRuns: [],
      streaming: true,
      done: false
    } as never)
    // 上一轮的占用（药丸当前值）
    store.contextUsage = {
      usedTokens: 42000,
      limitTokens: 128000,
      percent: 32.8,
      level: 'none',
      est: false,
      circuit: 'closed'
    }

    // 不传 assistantId ⇒ 宿主取当前助手消息（并视为本轮，进入完整分支）
    store.handleFrame({ type: 'message_start', messageId: 'a-1', kind: 'text', runId: 'r', seq: 1 } as never)
    store.handleFrame({ type: 'delta', messageId: 'a-1', text: '继', runId: 'r', seq: 2 } as never)
    expect(store.contextUsage?.usedTokens).toBe(42000)

    store.handleFrame(
      {
        type: 'done',
        runId: 'r',
        seq: 3,
        cancelled: false,
        context: { usedTokens: 90000, limitTokens: 128000, percent: 70.3, level: 'L1', est: false, circuit: 'closed' }
      } as never
    )
    expect(store.contextUsage?.usedTokens).toBe(90000)
    expect(store.contextUsage?.level).toBe('L1')
    // 拆分随轮末置脏（下次开 popover 重新拉，避免旧七项配新总量）
    expect(store.contextBreakdown).toBeNull()
    // 占位断言：宿主消息仍在（本用例不关心渲染，只要帧处理没抛）
    expect(host.id).toBeTruthy()
  })

  it('context 帧（压缩事件）只记现场，不刷新药丸', async () => {
    const store = useAiStore()
    store.contextUsage = {
      usedTokens: 42000,
      limitTokens: 128000,
      percent: 32.8,
      level: 'none',
      est: false,
      circuit: 'closed'
    }
    store.messages.push({
      id: 'a-2',
      role: 'assistant',
      content: '',
      parts: [],
      reasoning: '',
      toolRuns: [],
      streaming: true,
      done: false
    } as never)

    store.handleFrame(
      {
        type: 'context',
        runId: 'r',
        seq: 1,
        level: 'L1',
        before: 90000,
        after: 60000,
        turns: 0,
        lostTokens: 0,
        reason: 'l1_tool_result_clearing',
        est: false
      } as never
    )

    expect(store.contextUsage?.usedTokens).toBe(42000)
    expect(store.contextCompaction?.level).toBe('L1')
    expect(store.contextCompaction?.reason).toBe('l1_tool_result_clearing')
  })
})
