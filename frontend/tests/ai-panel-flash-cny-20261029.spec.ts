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
import { installIdentityFixture } from './ai-identity-fixture'

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
  // RO 桩（`vi.stubGlobal`）逐例复位：jsdom 无真实 ResizeObserver，残留会让用例顺序敏感。
  vi.unstubAllGlobals()
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
  installIdentityFixture()
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

interface PanelDebug {
  markdownHtmlCalls: number
  gfCardRenders: Record<string, number>
}

function debugOf(wrapper: ReturnType<typeof mount>): PanelDebug {
  return (wrapper.vm as unknown as { __batch7aDebug: PanelDebug }).__batch7aDebug
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

    // 两次全局翻转都不许重渲历史正文（v-memo 不再挂全局现场）。
    // 真锁 = markdown 生成次数：缓存命中时该计数器同样自增，所以"历史块重渲过"一定被记到。
    // 上面的 DOM 节点同一性只是弱断言（Vue patch 会复用元素，旧实现同样同一），不单独作证。
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

  it('恢复结束且本轮仍在收流 ⇒ 已决只读卡按"回合未结束"重新展开（正半支）', async () => {
    const store = useAiStore()
    const decided = message('a-hist', [gatePart('succeeded', false)], { streaming: true, done: false })
    store.loading = true
    store.restoringRecovery = true

    const wrapper = await mountPanel([decided])
    // 恢复期：不展开（对过去的镜像不做实时渲染）
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').toContain('display: none')

    // 恢复结束，而这一轮确实还在收流 ⇒ 回到"回合未结束仍展开"的既有口径
    store.restoringRecovery = false
    await settle(wrapper)
    expect(store.loading).toBe(true)
    expect(wrapper.find('[data-testid="ro-toggle-c1"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()
  })

  /**
   * 2026-10-29 返修（复审 I-1′）：判据看**进入恢复那一刻**这一轮是否还在收流。
   * 只看 `loading && !restoringRecovery` 会在"流中手动重载"与"自动恢复两次 attempt 之间"
   * 各把卡折一次再展开（最多两轮），BASE 在这两条路径是保持展开。
   */
  it('流中进入恢复期（进入时 loading=true）：已决只读卡保持展开', async () => {
    const store = useAiStore()
    const decided = message('a-hist', [gatePart('succeeded', false)], { streaming: true, done: false })
    store.loading = true

    const wrapper = await mountPanel([decided])
    expect(expandedOf(wrapper, 'c1')).toBe(true)

    // 用户点「重新载入」/ 自动恢复进入：这一轮本来就在收流
    store.restoringRecovery = true
    await settle(wrapper)
    expect(expandedOf(wrapper, 'c1')).toBe(true)
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()
  })

  it('连续两次恢复 attempt 之间不折（loading 恒真，标志一真一假来回）', async () => {
    const store = useAiStore()
    const decided = message('a-hist', [gatePart('succeeded', false)], { streaming: true, done: false })
    store.loading = true

    const wrapper = await mountPanel([decided])
    const states: boolean[] = [expandedOf(wrapper, 'c1')]

    store.restoringRecovery = true // attempt 1
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))
    store.restoringRecovery = false // attempt 之间的 400ms 间隔
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))
    store.restoringRecovery = true // attempt 2
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))
    store.restoringRecovery = false // 预算耗尽 / 恢复结束，仍在收流
    await settle(wrapper)
    states.push(expandedOf(wrapper, 'c1'))

    expect(states).toEqual([true, true, true, true, true])

    // 恢复窗口结束且这一轮确实收口 ⇒ 才收成卡头行（不会停在"永续展开"）
    store.loading = false
    await settle(wrapper)
    expect(expandedOf(wrapper, 'c1')).toBe(false)
    wrapper.unmount()
  })

  it('首次历史恢复（进入时 loading=false）仍不展开：采样看进入时刻，不看恢复期内的 loading 变化', async () => {
    const store = useAiStore()
    const decided = message('a-hist', [gatePart('succeeded', false)], { streaming: true, done: false })
    store.loading = false

    const wrapper = await mountPanel([decided])
    store.restoringRecovery = true
    await settle(wrapper)
    // 进入时没有再收流 ⇒ 恢复期不展开（刷新首屏 / 轮已收口的重新载入）
    expect(expandedOf(wrapper, 'c1')).toBe(false)

    // 恢复期内 loading 变真也不展开：采样是"进入那一刻"的快照，不是实时读
    store.loading = true
    await settle(wrapper)
    expect(expandedOf(wrapper, 'c1')).toBe(false)

    // 恢复结束、确实在收流 ⇒ 按既有口径展开
    store.restoringRecovery = false
    await settle(wrapper)
    expect(expandedOf(wrapper, 'c1')).toBe(true)
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
    // 批准后挂起已被清掉（`confirm` 立即 resolve + 无其它待决卡）：这一步的展开只能靠
    // "决策在途"（`gateSubmissions`），不是靠 `suspended` 侥幸绿 —— 时序一变这里先红。
    expect(store.suspended).toBe(false)
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

  it('grew 第三信号正半支：只有高度变高（无 RO、无内容增量）也钉底', async () => {
    useCapturedRO()
    const tool: ToolPart = {
      kind: 'tool',
      seg: 1,
      toolCallId: 't-grew',
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
    let height = 600
    const probe = installScrollMetrics(wrap, 300, () => height)
    probe.top = 300

    const store = useAiStore()
    // 第一次状态翻转：只建立基线（高度此前未测到 ⇒ 不许滚）
    store.messages[0]!.parts[1] = { ...tool, status: 'succeeded' }
    await settle(wrapper)
    expect(probe.writes).toBe(0)

    // 视口上方长高（字体 / 图片 / mermaid 落地）：本条内容形态没变、RO 也没被触发，
    // 只剩"测到的高度比基线高"这一条证据
    height = 900
    store.messages[0]!.parts[1] = { ...tool, status: 'failed' }
    await settle(wrapper)

    expect(probe.writes).toBe(1)
    expect(probe.top).toBe(900 - 300)
    wrapper.unmount()
  })

  it('原生锚定只在跟随态关闭：上翻阅读时恢复浏览器锚定', async () => {
    const wrapper = await mountPanel([message('a-1', [textPart('第一段')], { done: true })])
    const wrap = scrollContainer(wrapper)
    const anchorOff = (): boolean => wrap.classList.contains('[overflow-anchor:none]')
    // 贴底跟随：自己补偿 ⇒ 关掉原生锚定（否则浏览器也补一次）
    expect(anchorOff()).toBe(true)

    // 用户上翻：我们不再补偿，位置只能交给浏览器锚定
    Object.defineProperty(wrap, 'clientHeight', { value: 300, configurable: true })
    Object.defineProperty(wrap, 'scrollHeight', { value: 600, configurable: true })
    wrap.scrollTop = 0
    wrap.dispatchEvent(new WheelEvent('wheel', { deltaY: -240 }))
    wrap.dispatchEvent(new Event('scroll'))
    await settle(wrapper)
    expect(anchorOff()).toBe(false)

    // 回到底部附近（离底 < 32）⇒ 跟随恢复 ⇒ 重新关掉原生锚定
    wrap.scrollTop = 300
    wrap.dispatchEvent(new Event('scroll'))
    await settle(wrapper)
    expect(anchorOff()).toBe(true)
    wrapper.unmount()
  })
})

// ── M-1 · GF 卡 memo 边界（2026-10-29 返修）─────────────────────────────────

const GF_FORM = {
  scenario: 'FILTER',
  title: '导出参数',
  fields: [
    { key: 'region', label: '导出范围', type: 'enum', required: true, options: [{ value: 'HB', label: '华北' }, { value: 'HD', label: '华东' }] }
  ]
}

const GF_ARGS = JSON.stringify({ form: GF_FORM })

describe('M-1 · GF 卡 memo 边界', () => {
  it('只有当前表单的宿主卡随 liveForm 值更新，历史 GF 卡不整列失效', async () => {
    const store = useAiStore()
    const history = message('a-gf-hist', [
      {
        kind: 'tool',
        seg: 0,
        toolCallId: 'g-hist',
        name: 'generative_form',
        kind2: 'FRONTEND',
        status: 'succeeded',
        args: GF_ARGS,
        requiresAction: false,
        submittedValues: { region: 'HB' }
      }
    ])
    const live = message('a-gf-live', [
      {
        kind: 'tool',
        seg: 0,
        toolCallId: 'g-live',
        name: 'generative_form',
        kind2: 'FRONTEND',
        status: 'pending',
        args: GF_ARGS,
        requiresAction: true
      }
    ], {
      done: false,
      pendingCall: {
        toolCallId: 'g-live',
        name: 'generative_form',
        kind: 'FRONTEND',
        args: { form: GF_FORM },
        rawArgs: GF_ARGS,
        status: 'pending'
      }
    })
    store.activeForm = {
      toolCallId: 'g-live',
      runId: 'run-1',
      form: GF_FORM as never,
      status: 'filling',
      error: null,
      values: { region: 'HB' },
      fieldErrors: []
    }

    const wrapper = await mountPanel([history, live])
    const before = debugOf(wrapper).gfCardRenders
    expect(before['g-hist'] ?? 0).toBeGreaterThan(0)
    expect(before['g-live'] ?? 0).toBeGreaterThan(0)

    // 用户在当前表单里改值：`liveForm.value` 与 `values` 身份都被替换
    store.activeForm = { ...store.activeForm!, values: { region: 'HD' } }
    await settle(wrapper)
    const after = debugOf(wrapper).gfCardRenders

    // 宿主卡照常更新（表单值这一帧就在它身上）
    expect(after['g-live'] ?? 0).toBeGreaterThan(before['g-live'] ?? 0)
    // 历史 GF 卡不该被全局 liveForm 拖下水（每敲一个字就整列重渲）
    expect(after['g-hist'] ?? 0).toBe(before['g-hist'] ?? 0)
    // 卡面本身没坏：历史卡仍是同一张只读卡
    expect(wrapper.find('[data-testid="ro-summary-g-hist"]').text()).toContain('导出范围 华北')
    wrapper.unmount()
  })

  /**
   * 复审 M-1′：`queuedForms` 的总数只以标量入 memo —— 必须证明"取消后面那张"时，
   * 前面那张**位次不变、总数变小**的卡面也会更新（只靠位次 dep 是锁不住的）。
   *
   * 两张排队卡放**不同消息**：否则后一张的位次变化会连带把同一消息行整行 patch，
   * 前一张靠"行被连带刷新"侥幸变绿，锁不住 `queuedForms.length` 这条标量依赖。
   */
  it('GF 队列取消后面那张：剩余卡的 DOM 总数随之更新（位次不变也更新）', async () => {
    const store = useAiStore()
    const queuePart = (toolCallId: string, seg: number): MessagePart => ({
      kind: 'tool',
      seg,
      toolCallId,
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'pending',
      args: GF_ARGS,
      requiresAction: true
    })
    const first = message('a-gf-q2', [queuePart('q2', 0)], { done: false })
    const second = message('a-gf-q3', [queuePart('q3', 0)], { done: false })
    store.queuedForms = [
      { toolCallId: 'q2', runId: 'run-q', form: GF_FORM as never },
      { toolCallId: 'q3', runId: 'run-q', form: GF_FORM as never }
    ]

    const wrapper = await mountPanel([first, second])
    const before = wrapper.findAll('[data-testid="gf-queue-card"]')
    expect(before).toHaveLength(2)
    expect(before[0]!.find('[data-testid="gf-queue-position"]').text()).toContain('第 1 / 2 张')

    // 取消**后面**那张（q3）：q2 位次仍是 1，只有总数 2→1
    store.queuedForms = [{ toolCallId: 'q2', runId: 'run-q', form: GF_FORM as never }]
    await settle(wrapper)

    const after = wrapper.findAll('[data-testid="gf-queue-card"]')
    expect(after).toHaveLength(1)
    expect(after[0]!.attributes('data-tool-call-id')).toBe('q2')
    expect(after[0]!.find('[data-testid="gf-queue-position"]').text()).toContain('第 1 / 1 张')
    wrapper.unmount()
  })
})
