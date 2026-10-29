/**
 * 「导出CNY货币字典」整屏闪（2026-10-29）回归锁。
 *
 * <h2>三个面</h2>
 * <ol>
 * <li><b>消息 memo 边界</b>：`loading` / 轮次阶段等**全局**现场只允许失效"当前消息"，
 *     历史消息行一格不动（DOM 节点同一性 + 不再走 markdown 渲染）；</li>
 * <li><b>确认卡展开稳定</b>：恢复期不异常展开；批准 → 重连 → 完成不折叠回弹，外壳不卸载；</li>
 * <li><b>stick-to-bottom 滚动</b>：只有"原本在底部阈值内 + measured size 变化"才钉底；
 *     状态变尺寸不变不滚；同一帧的 nextTick / RO 重复触发合并成一次写入。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import type { ChatMessage, MessagePart, PendingToolCall, ToolPart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'

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
  retryModelStep: vi.fn(),
  feedbackSubmit: vi.fn()
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
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.chat.mockResolvedValue(undefined)
  api.confirm.mockResolvedValue(undefined)
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
})

// ── 夹具 ─────────────────────────────────────────────────────────────────────

function textPart(text: string, streaming = false): MessagePart {
  return { kind: 'text', seg: 0, text, streaming }
}

function message(
  id: string,
  parts: MessagePart[],
  options: { streaming?: boolean; done?: boolean; pendingCall?: PendingToolCall | null } = {}
): ChatMessage {
  return {
    id,
    role: 'assistant',
    content: parts.map((part) => (part.kind === 'text' ? part.text : '')).join(''),
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: options.pendingCall ?? null,
    streaming: options.streaming ?? false,
    done: options.done ?? true
  }
}

function gatePart(status: ToolPart['status'], requiresAction: boolean): ToolPart {
  return {
    kind: 'tool',
    seg: 0,
    toolCallId: 'c1',
    name: 'start_export',
    kind2: 'CONFIRM',
    status,
    args: '{"taskId":1}',
    requiresAction
  }
}

function pendingGate(): PendingToolCall {
  return {
    toolCallId: 'c1',
    name: 'start_export',
    kind: 'CONFIRM',
    args: { taskId: 1 },
    rawArgs: '{"taskId":1}',
    status: 'pending'
  }
}

async function mountPanel(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  store.expanded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await nextTick()
  await flushPromises()
  return wrapper
}

function debugOf(wrapper: ReturnType<typeof mount>): { markdownHtmlCalls: number } {
  return (wrapper.vm as unknown as { __batch7aDebug: { markdownHtmlCalls: number } }).__batch7aDebug
}

async function settle(wrapper: ReturnType<typeof mount>, times = 3): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await wrapper.vm.$nextTick()
  }
  await flushPromises()
}

function scrollContainer(wrapper: ReturnType<typeof mount>): HTMLElement {
  return wrapper.find('.overflow-y-auto').element as HTMLElement
}

interface ScrollProbe {
  writes: number
  top: number
}

/**
 * 物理一致的滚动度量：`scrollTop` 被夹在 `[0, scrollHeight − clientHeight]`，并统计**写入次数**
 * （「重复滚动 / 循环补偿」的可观测面：一次内容变化只该有一次写入）。
 */
function installScrollMetrics(wrap: HTMLElement, clientHeight: number, height: () => number): ScrollProbe {
  const probe: ScrollProbe = { writes: 0, top: 0 }
  Object.defineProperty(wrap, 'clientHeight', { configurable: true, get: () => clientHeight })
  Object.defineProperty(wrap, 'scrollHeight', { configurable: true, get: () => height() })
  Object.defineProperty(wrap, 'scrollTop', {
    configurable: true,
    get: () => probe.top,
    set: (value: number) => {
      probe.writes += 1
      probe.top = Math.min(Math.max(0, value), Math.max(0, height() - clientHeight))
    }
  })
  return probe
}

function useCapturedRO(): { cb: ResizeObserverCallback | null } {
  const captured: { cb: ResizeObserverCallback | null } = { cb: null }
  class CaptureRO {
    constructor(cb: ResizeObserverCallback) {
      captured.cb = cb
    }
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  vi.stubGlobal('ResizeObserver', CaptureRO)
  return captured
}

function expandedOf(wrapper: ReturnType<typeof mount>, toolCallId: string): boolean {
  const toggle = wrapper.find(`[data-testid="ro-toggle-${toolCallId}"]`)
  if (toggle.exists()) {
    return toggle.attributes('aria-expanded') === 'true'
  }
  return wrapper.find(`[data-testid="pending-card-${toolCallId}"]`).exists()
}

// ── 1 · 消息 memo 边界 ────────────────────────────────────────────────────────

describe('memo · loading 变化不整列重渲', () => {
  it('loading 变化只失效当前消息：历史消息正文不再重渲染，当前消息呼吸点/挂起等待仍生效', async () => {
    const store = useAiStore()
    const history = message('h-1', [textPart('历史回答')], { done: true })
    const gate: ToolPart = { ...gatePart('pending', true), toolCallId: 'c9' }
    const live = message('a-2', [textPart('当前回答'), gate], { streaming: false, done: false })
    const wrapper = await mountPanel([history, live])

    const historyBody = wrapper.find('[data-testid="msg-bubble-h-1"] .md-body').element
    const callsBefore = debugOf(wrapper).markdownHtmlCalls
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(false)

    // 收流开始：全局现场翻转
    store.loading = true
    await settle(wrapper)
    // 当前消息的必要更新仍生效（流式期呼吸点）
    expect(wrapper.find('[data-testid="msg-ops-a-2"] [data-testid="loading-breath"]').exists()).toBe(true)

    // 收流收掉、进入挂起等人：当前消息换成「等待你操作」
    store.loading = false
    store.suspended = true
    await settle(wrapper)
    expect(wrapper.find('[data-testid="msg-ops-a-2"] [data-testid="park-waiting"]').exists()).toBe(true)

    // 两次全局翻转都不许重渲历史正文（v-memo 不再挂全局现场）：markdown 渲染 0 次
    expect(wrapper.find('[data-testid="msg-bubble-h-1"] .md-body').element).toBe(historyBody)
    expect(debugOf(wrapper).markdownHtmlCalls - callsBefore).toBe(0)
    wrapper.unmount()
  })
})

// ── 2 · 确认卡展开稳定 ────────────────────────────────────────────────────────

describe('确认卡 · 展开稳定', () => {
  it('历史恢复期（restoringRecovery 且 loading）不得把已决只读卡异常展开', async () => {
    const store = useAiStore()
    const decided = message('a-hist', [gatePart('succeeded', false)])
    store.loading = true
    store.restoringRecovery = true

    const wrapper = await mountPanel([decided])
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').toContain('display: none')
    wrapper.unmount()
  })

  it('批准 → 重连 → 完成：外壳不卸载、展开不折叠回弹，终态收口后不复弹', async () => {
    const store = useAiStore()
    const part = gatePart('pending', true)
    const msg = message('a-park', [part], { done: false, pendingCall: pendingGate() })
    store.activeRunId = 'run-park'
    store.suspended = true
    store.loading = false

    const wrapper = await mountPanel([msg])
    const shell = wrapper.find('[data-testid="pending-card-c1"]').element
    const states: boolean[] = [expandedOf(wrapper, 'c1')]

    // 批准：段立即切只读，决策在途 ⇒ 仍展开
    const actions = wrapper.find('[data-testid="gate-actions"]').findAll('button')
    await actions[1]!.trigger('click')
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))

    // 重连：连接建立（loading）⇒ 仍展开
    store.loading = true
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))

    // 结局帧 + 本轮收口：在途撤掉、终态落地
    store.loading = false
    store.suspended = false
    store.clearGateSubmission('c1')
    store.messages[0]!.parts[0] = gatePart('succeeded', false)
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))
    // 再等一帧：不得回弹
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))

    expect(states).toEqual([true, true, true, false, false])
    // 外壳始终是同一个 DOM 节点（不卸载、不换 key）
    const resolved = wrapper.find('[data-testid="hitl-receipt"]')
    expect(resolved.exists()).toBe(true)
    expect(resolved.element).toBe(shell)
    wrapper.unmount()
  })
})

// ── 3 · stick-to-bottom ─────────────────────────────────────────────────────

describe('滚动 · stick-to-bottom', () => {
  it('状态变但尺寸不变不滚：尾部状态翻转不把视线拽回底部', async () => {
    useCapturedRO()
    const tool: ToolPart = {
      kind: 'tool',
      seg: 1,
      toolCallId: 't1',
      name: 'list_tasks',
      kind2: 'BACKEND',
      status: 'running',
      args: '{}',
      result: '任务清单',
      requiresAction: false
    }
    const msg = message('a-1', [textPart('正文'), tool], { done: false })
    const wrapper = await mountPanel([msg])
    const wrap = scrollContainer(wrapper)
    // 尺寸恒定：本用例只翻状态，不改高度
    const probe = installScrollMetrics(wrap, 300, () => 600)
    // 用户原本在底部阈值内（离底 20px < 32）
    probe.top = 280
    const before = probe.top

    const store = useAiStore()
    store.messages[0]!.parts[1] = { ...tool, status: 'succeeded' }
    await settle(wrapper)

    expect(probe.top).toBe(before)
    expect(probe.writes).toBe(0)
    wrapper.unmount()
  })

  it('尺寸变化才钉底，且同一帧的重复触发合并为一次写入', async () => {
    const captured = useCapturedRO()
    const msg = message('a-1', [textPart('第一段')], { done: true })
    const wrapper = await mountPanel([msg])
    const wrap = scrollContainer(wrapper)
    // 高度 = 内容的函数：DOM 补丁之后才长高（真实布局语义）
    const probe = installScrollMetrics(wrap, 300, () => 600 + (wrap.textContent ?? '').length * 4)
    probe.top = 280

    const store = useAiStore()
    ;(store.messages[0]!.parts[0] as { kind: 'text'; text: string }).text += '\n补一段说明。'
    // 同一帧：watch 二次钉底 + RO 重复回调（真实浏览器里两者会同时到）
    captured.cb?.([{ target: wrap } as unknown as ResizeObserverEntry], {} as ResizeObserver)
    await settle(wrapper)
    captured.cb?.([{ target: wrap } as unknown as ResizeObserverEntry], {} as ResizeObserver)
    await settle(wrapper)

    const atBottom = wrap.scrollHeight - wrap.clientHeight
    expect(probe.top).toBe(atBottom)
    // 一次内容变化只写一次 scrollTop（无 watch/RO 双份补偿、无滚动循环）
    expect(probe.writes).toBe(1)
    wrapper.unmount()
  })

  it('用户上翻立即释放：内容长高不把视图拽回底部', async () => {
    const captured = useCapturedRO()
    const msg = message('a-1', [textPart('第一段')], { done: true })
    const wrapper = await mountPanel([msg])
    const wrap = scrollContainer(wrapper)
    const probe = installScrollMetrics(wrap, 300, () => 600 + (wrap.textContent ?? '').length * 4)
    probe.top = 0

    // 用户上翻（滚轮意图 + scroll）：离底 300px > 32 ⇒ 释放跟随
    wrap.dispatchEvent(new WheelEvent('wheel', { deltaY: -240 }))
    wrap.dispatchEvent(new Event('scroll'))
    await settle(wrapper)
    const before = probe.top

    const store = useAiStore()
    ;(store.messages[0]!.parts[0] as { kind: 'text'; text: string }).text += '\n再补一段。'
    captured.cb?.([{ target: wrap } as unknown as ResizeObserverEntry], {} as ResizeObserver)
    await settle(wrapper)

    expect(probe.top).toBe(before)
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
