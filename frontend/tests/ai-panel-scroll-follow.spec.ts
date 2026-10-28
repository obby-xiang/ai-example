/**
 * 批 D · D2（光标跟随三处）：`AiPanel.vue` 滚动跟随的回归锁。
 *
 * <h2>三处根因（用户实测"断续 / 拖回"）</h2>
 * <ol>
 * <li><b>钉到旧底</b>：滚动指纹 watch 是 `flush:'pre'`（回调早于组件更新），回调里读到的
 *     scrollHeight 还是旧内容的高度 —— 长段补发后视图停在旧底、离底一截；</li>
 * <li><b>RO 观测错目标</b>：ResizeObserver 观测的是滚动容器自身，而容器的盒子由 flex 定死，
 *     内容在 `overflow-y-auto` 里长高只改 scrollHeight、不改它的 box ⇒ 内容增长零触发；</li>
 * <li><b>跟随闸被布局回声误关</b>：钉底之后浏览器补派的 scroll 事件读到的离底距离已经是
 *     下一帧内容的，跟随被翻成 false，此后 `scrollBottom()` 整段跳过 —— 自动跟随自己停掉。</li>
 * </ol>
 *
 * <h2>口径</h2>
 * 跟随态 = 新内容到达即钉底；用户上滑（显式意图）即暂停且不被拽回；回到底部附近自动恢复。
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'

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

beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.chat.mockReset()
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
})

function streamingMessage(text: string): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: true }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

async function mountPanel(text = '初始正文'): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = [streamingMessage(text)]
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

function scrollContainer(wrapper: ReturnType<typeof mount>): HTMLElement {
  return wrapper.find('.overflow-y-auto').element as HTMLElement
}

/**
 * 物理一致的滚动度量：浏览器把 `scrollTop` 夹在 `[0, scrollHeight − clientHeight]`，jsdom 不夹
 * （赋什么存什么）。不夹的话，「钉底 = 写 scrollHeight」会造出 `scrollTop + clientHeight >
 * scrollHeight`（离底距离为负）的非法态 —— 生产侧 `pinBottomTo` 的「已在底部就不重复写」去重
 * 会据此判定无需滚动，用例就成了对浏览器里不存在的状态的断言。
 */
function mockScrollMetrics(
  wrap: HTMLElement,
  clientHeight: number,
  contentHeight: () => number
): void {
  let top = 0
  Object.defineProperty(wrap, 'clientHeight', { configurable: true, get: () => clientHeight })
  Object.defineProperty(wrap, 'scrollHeight', { configurable: true, get: contentHeight })
  Object.defineProperty(wrap, 'scrollTop', {
    configurable: true,
    get: () => top,
    set: (value: number) => {
      // 内容不足一屏 ⇒ 无可滚距离（0）；否则上限 = scrollHeight − clientHeight
      top = Math.min(Math.max(0, value), Math.max(0, contentHeight() - clientHeight))
    }
  })
}

/** 每次 nextTick 都让出一帧（watch 的 `await nextTick()` 需要两跳才落到 DOM 补丁之后）。 */
async function settleTicks(wrapper: ReturnType<typeof mount>, times = 3): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await wrapper.vm.$nextTick()
  }
  await flushPromises()
}

/** 可控 RO：捕获回调与观测目标（jsdom 无真实 RO，布局变化全靠手动触发）。 */
function useCapturedRO(): { cb: ResizeObserverCallback | null; target: Element | null } {
  const captured: { cb: ResizeObserverCallback | null; target: Element | null } = { cb: null, target: null }
  class CaptureRO {
    constructor(cb: ResizeObserverCallback) {
      captured.cb = cb
    }
    observe(el: Element): void {
      captured.target = el
    }
    unobserve(): void {}
    disconnect(): void {}
  }
  vi.stubGlobal('ResizeObserver', CaptureRO)
  return captured
}

describe('批 D · D2：滚动跟随（二次钉底 / RO 观测内容层 / 跟随滞回）', { timeout: 20_000 }, () => {
  it('D2①：指纹变化后要等 DOM 补丁完成再钉一次 —— 长段补发后视图仍在底部', async () => {
    // 非流式消息：正文渲染不经过 75ms 流式防抖 ⇒ DOM 在本次补丁里就长高（补发的真实形态）
    const store = useAiStore()
    const done: ChatMessage = {
      ...streamingMessage('短正文'),
      streaming: false,
      done: true,
      parts: [{ kind: 'text', seg: 0, text: '短正文', streaming: false }]
    }
    store.messages = [done]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    await flushPromises()
    const wrap = scrollContainer(wrapper)
    // 高度是"内容的函数"（模拟真实布局）：DOM 补丁之后才变高 —— 这正是旧实现钉不到的地方
    mockScrollMetrics(wrap, 200, () => 400 + (wrap.textContent ?? '').length * 4)
    /** 「贴在底部」= scrollTop 到可滚上限（浏览器口径），不是到 scrollHeight（那一档会被夹掉）。 */
    const atBottom = (): boolean => wrap.scrollTop === wrap.scrollHeight - wrap.clientHeight
    wrap.scrollTop = wrap.scrollHeight
    expect(atBottom()).toBe(true)
    const heightBefore = wrap.scrollHeight
    const topBefore = wrap.scrollTop

    // 长段补发（指纹变化 = 末段文本长度变了）。增量必须**大于一屏**：增量不足一屏时，
    // 「钉到旧底（写旧 scrollHeight）」在钳制语义下会被夹到与新底同一个位置，
    // 旧实现与新实现不可区分，用例就锁不住 D2①（这正是长段补发才炸的原因）。
    const part = store.messages[0]!.parts[0] as { kind: 'text'; text: string }
    part.text +=
      '\n- 第一步：选择配置项并确认字段映射\n- 第二步：设置筛选条件与去重口径\n' +
      '- 第三步：核对导出格式、编码与表头\n- 第四步：启动导出并等待落盘回执\n'
    await settleTicks(wrapper)

    expect(wrap.textContent ?? '').toContain('启动导出')
    expect(wrap.scrollHeight).toBeGreaterThan(heightBefore)
    // 视图确实跟着长高的内容往下追了一段，且仍贴在底部
    expect(wrap.scrollTop).toBeGreaterThan(topBefore)
    expect(atBottom()).toBe(true)
    wrapper.unmount()
  })

  it('D2②：RO 观测"随内容长高的内容层"，不是滚动容器自身', async () => {
    const captured = useCapturedRO()
    const wrapper = await mountPanel('一段正文')
    const wrap = scrollContainer(wrapper)

    expect(captured.target).not.toBeNull()
    expect(captured.target, '滚动容器自身的 box 不随内容变，观测它对内容增长零触发').not.toBe(wrap)
    expect(wrap.contains(captured.target)).toBe(true)
    wrapper.unmount()
  })

  it('D2③：钉底静默期内的布局回声不翻转跟随闸（无用户滚动意图）', async () => {
    const captured = useCapturedRO()
    const wrapper = await mountPanel('一段正文')
    const wrap = scrollContainer(wrapper)
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    wrap.scrollTop = 500

    // 内容长高 → RO 重钉（catch-up 钉底 ⇒ 开静默期）
    Object.defineProperty(wrap, 'scrollHeight', { value: 1300, configurable: true })
    captured.cb?.([] as unknown as ResizeObserverEntry[], {} as ResizeObserver)
    await wrapper.vm.$nextTick()
    expect(wrap.scrollTop).toBe(1300)

    // 布局回声：上方卡片展开把 scrollTop 顶回 1050 并派发 scroll（离底 50px，无用户意图）
    Object.defineProperty(wrap, 'scrollHeight', { value: 1600, configurable: true })
    wrap.scrollTop = 1050
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()

    // 跟随闸未被翻转：下一段内容到达仍然钉底
    Object.defineProperty(wrap, 'scrollHeight', { value: 1800, configurable: true })
    captured.cb?.([] as unknown as ResizeObserverEntry[], {} as ResizeObserver)
    await wrapper.vm.$nextTick()
    expect(wrap.scrollTop).toBe(1800)
    wrapper.unmount()
  })

  it('跟随口径三连：上滑暂停不被拽回、回到底部附近自动恢复、恢复后继续钉底', async () => {
    const wrapper = await mountPanel('一段正文')
    const store = useAiStore()
    const wrap = scrollContainer(wrapper)
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    wrap.scrollTop = 1000

    // 用户上滑（wheel 意图 + scroll）：暂停跟随，浮钮出现
    wrap.dispatchEvent(new WheelEvent('wheel', { deltaY: -240 }))
    wrap.scrollTop = 300
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(true)

    // 新内容到达：不得把视图拽回底部
    store.handleFrame({ type: 'delta', text: '\n补充一段说明。', seq: 12 })
    await settleTicks(wrapper)
    expect(wrap.scrollTop).toBe(300)

    // 回到底部附近（离底 20px < 32）：跟随自动恢复
    wrap.scrollTop = 480
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(false)

    // 恢复后：新内容到达重新钉底
    store.handleFrame({ type: 'delta', text: '\n再补一段。', seq: 13 })
    await settleTicks(wrapper)
    expect(wrap.scrollTop).toBe(1000)
    wrapper.unmount()
  })
})
