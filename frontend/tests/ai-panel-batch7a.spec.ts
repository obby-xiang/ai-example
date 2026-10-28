/**
 * 批次 7A：AiPanel 渲染生命周期 / 缓存边界 / 布局读取收敛的回归锁。
 *
 * 本轮只执行其中一个聚焦方法（收起-展开观察器迁移）的 RED/GREEN 各一次；
 * 其余用例按计划写在这里作后续回归面，未在本轮执行。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
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

vi.mock('@/utils/markdown', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/markdown')>()
  return {
    ...actual,
    renderMermaidSvg: () => Promise.resolve('<svg data-testid="mmd-stub"></svg>')
  }
})

const observers = vi.hoisted(() => ({
  io: [] as Array<{
    root: Element | Document | null
    targets: Element[]
    disconnected: boolean
    fire: (target: Element) => void
  }>,
  ro: [] as Array<{
    targets: Element[]
    disconnected: boolean
    fire: () => void
  }>
}))

class IntersectionObserverStub {
  readonly targetList: Element[] = []
  disconnected = false

  constructor(
    callback: IntersectionObserverCallback,
    options?: IntersectionObserverInit
  ) {
    const instance = this
    observers.io.push({
      root: options?.root ?? null,
      targets: this.targetList,
      get disconnected() {
        return instance.disconnected
      },
      fire: (target: Element) => {
        callback([{ target, isIntersecting: true } as unknown as IntersectionObserverEntry], this as unknown as IntersectionObserver)
      }
    })
  }

  observe(target: Element): void {
    this.targetList.push(target)
  }

  unobserve(): void {}

  disconnect(): void {
    this.disconnected = true
  }

  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

class ResizeObserverStub {
  readonly targetList: Element[] = []
  disconnected = false

  constructor(callback: ResizeObserverCallback) {
    const instance = this
    observers.ro.push({
      targets: this.targetList,
      get disconnected() {
        return instance.disconnected
      },
      fire: () => callback([] as unknown as ResizeObserverEntry[], this as unknown as ResizeObserver)
    })
  }

  observe(target: Element): void {
    this.targetList.push(target)
  }

  unobserve(): void {}

  disconnect(): void {
    this.disconnected = true
  }
}

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

/**
 * L-72 隔离口径：本文件的"现场"由**两个实例**构成 —— 一份 pinia、一份组件模块。
 * 两者都必须由夹具显式持有，用例只在这两个句柄上操作：
 *
 * - `testPinia`：用例侧一律 `useAiStore(testPinia)`，不靠"再取一次 activePinia"。
 *   否则一旦中途 active pinia 被换（另一份模块图里的 `setActivePinia` / 重新 import），
 *   用例改的是**另一个 store 实例**，组件侧观察不到（典型症状：改 `sessionId` 不触发清理、
 *   而 `handleFrame` 落在别处的消息上）。
 * - `panelModule`：AiPanel 模块只解析一次，全文件共用一个模块实例；`vi.resetModules()`
 *   后再 `import()` 会拿到**另一份模块图**，缓存 / 计数器 / 观察器各自成对，读数就串了。
 */
let testPinia = createPinia()
let panelModule: Promise<typeof import('@/components/AiPanel/AiPanel.vue')> | null = null

/** 面板组件模块的唯一解析口（同一模块实例 ⇒ 同一份缓存与调试面）。 */
function loadAiPanel(): Promise<typeof import('@/components/AiPanel/AiPanel.vue')> {
  if (panelModule === null) {
    panelModule = import('@/components/AiPanel/AiPanel.vue')
  }
  return panelModule
}

beforeEach(() => {
  sessionStorage.clear()
  observers.io.length = 0
  observers.ro.length = 0
  testPinia = createPinia()
  setActivePinia(testPinia)
  vi.stubGlobal('IntersectionObserver', IntersectionObserverStub)
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.feedbackSubmit.mockReset()
  api.confirm.mockReset()
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
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
  api.confirm.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

interface DebugView {
  mermaidPendingSize: number
  scrollBound: Element | null
  historyCacheKeys: string[]
  historyCacheSize: number
  historyCacheBytes: number
  streamCacheSize: number
  streamCacheBytes: number
  markdownHtmlCalls: number
  toolDeriveMisses: Record<string, number>
  measurePasses: string[][]
}

function debugOf(wrapper: ReturnType<typeof mount>): DebugView {
  return (wrapper.vm as unknown as { __batch7aDebug: DebugView }).__batch7aDebug
}

function messageWith(parts: ChatMessage['parts'], id = 'a-1'): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id,
    role: 'assistant',
    content: text,
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

function mermaidMessage(): ChatMessage {
  const text = '```mermaid\ngraph TD; A-->B;\n```'
  return messageWith([{ kind: 'text', seg: 0, text, streaming: false }])
}

function toolPart(overrides: Partial<ToolPart> = {}): ToolPart {
  return {
    kind: 'tool',
    seg: 1,
    toolCallId: 'c1',
    name: 'list_config_defs',
    kind2: 'BACKEND',
    status: 'succeeded',
    args: '{"type":"EXPORT","taskId":12,"secretToken":"abc"}',
    result: 'ok',
    requiresAction: false,
    ...overrides
  }
}

/** 用户消息夹具：`messageWith` 的助手默认值不适用（长文折叠 / 脏标记都只针对用户侧）。 */
function userMessageWith(parts: ChatMessage['parts'], id = 'u-1'): ChatMessage {
  return { ...messageWith(parts, id), role: 'user' }
}

/** 真在流式中的助手消息：`done:false` 才能被 `currentAssistantMessage()` 认作宿主。 */
function liveMessageWith(parts: ChatMessage['parts'], id = 'a-live'): ChatMessage {
  return { ...messageWith(parts, id), streaming: true, done: false }
}

async function mountPanel(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
  // 组件侧取 store 读的是 activePinia；挂载前再钉一次，确保它落的正是本用例持有的实例。
  setActivePinia(testPinia)
  const store = useAiStore(testPinia)
  store.messages = messages
  store.historyLoaded = true
  store.expanded = true
  const { default: AiPanel } = await loadAiPanel()
  setActivePinia(testPinia)
  const wrapper = mount(AiPanel, { global: { stubs } })
  await flushPromises()
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

async function settle(wrapper: ReturnType<typeof mount>): Promise<void> {
  await wrapper.vm.$nextTick()
  await flushPromises()
  await wrapper.vm.$nextTick()
  await flushPromises()
}

describe('批次 7A：渲染生命周期与缓存边界', () => {
  it('收起/展开：RO、IO、横滚监听随 expanded 迁移，收起即清 mermaidPending', async () => {
    const wrapper = await mountPanel([mermaidMessage()])
    const ioBefore = observers.io.at(-1)
    const roBefore = observers.ro.at(-1)
    const wrapBefore = wrapper.find('.overflow-y-auto').element
    expect(ioBefore).toBeDefined()
    expect(roBefore).toBeDefined()
    expect(ioBefore!.root).toBe(wrapBefore)
    expect(roBefore!.targets[0]).not.toBe(wrapBefore)
    expect(ioBefore!.targets.length).toBeGreaterThan(0)
    expect(debugOf(wrapper).scrollBound).toBe(wrapBefore)
    expect(debugOf(wrapper).mermaidPendingSize).toBeGreaterThan(0)

    const removeSpy = vi.spyOn(wrapBefore as HTMLElement, 'removeEventListener')
    useAiStore(testPinia).expanded = false
    await settle(wrapper)

    expect(roBefore!.disconnected).toBe(true)
    expect(ioBefore!.disconnected).toBe(true)
    expect(removeSpy).toHaveBeenCalledWith('scroll', expect.any(Function), expect.objectContaining({ capture: true }))
    expect(debugOf(wrapper).mermaidPendingSize).toBe(0)
    expect(debugOf(wrapper).scrollBound).toBeNull()

    useAiStore(testPinia).expanded = true
    await settle(wrapper)

    const wrapAfter = wrapper.find('.overflow-y-auto').element
    const ioAfter = observers.io.at(-1)
    const roAfter = observers.ro.at(-1)
    expect(wrapAfter).not.toBe(wrapBefore)
    expect(roAfter).not.toBe(roBefore)
    expect(roAfter!.disconnected).toBe(false)
    expect(roAfter!.targets[0]).not.toBe(wrapAfter)
    expect(ioAfter).not.toBe(ioBefore)
    expect(ioAfter!.root).toBe(wrapAfter)
    expect(ioAfter!.targets.length).toBeGreaterThan(0)
    expect(debugOf(wrapper).scrollBound).toBe(wrapAfter)
    wrapper.unmount()
  })

  it('同长不同文不误命中：内容指纹变化必须重渲染', async () => {
    const wrapper = await mountPanel([messageWith([{ kind: 'text', seg: 0, text: 'AAAA', streaming: false }])])
    const body = wrapper.find('.md-body')
    expect(body.text()).toContain('AAAA')

    const store = useAiStore(testPinia)
    const part = store.messages[0]!.parts[0]
    if (part.kind !== 'text') throw new Error('unexpected part')
    part.text = 'BBBB'
    await settle(wrapper)

    expect(wrapper.find('.md-body').text()).toContain('BBBB')
    expect(wrapper.find('.md-body').text()).not.toContain('AAAA')
    wrapper.unmount()
  })

  it('mdCache：历史块 LRU 命中刷新顺序，流式块单独成槽不挤历史', async () => {
    const wrapper = await mountPanel([
      messageWith([
        { kind: 'text', seg: 0, text: 'alpha', streaming: false },
        { kind: 'text', seg: 1, text: 'bravo', streaming: false }
      ])
    ])
    const before = debugOf(wrapper).historyCacheKeys
    expect(before).toHaveLength(2)

    useAiStore(testPinia).expanded = false
    await settle(wrapper)
    // 收起态改段序：展开后按新序访问两个历史块；LRU 命中应把后访问的块移到队尾。
    useAiStore(testPinia).messages[0]!.parts.reverse()
    useAiStore(testPinia).expanded = true
    await settle(wrapper)

    const after = debugOf(wrapper).historyCacheKeys
    expect(after).toHaveLength(2)
    expect(after).not.toEqual(before)
    expect(debugOf(wrapper).historyCacheSize).toBeLessThanOrEqual(256)
    expect(debugOf(wrapper).historyCacheBytes).toBeLessThanOrEqual(8 * 1024 * 1024)
    wrapper.unmount()

    const streaming = useAiStore(testPinia)
    const live = messageWith([{ kind: 'text', seg: 0, text: '流式正文', streaming: true }], 'a-live')
    live.streaming = true
    live.done = false
    streaming.messages = [live]
    const wrapper2 = await mountPanel([live])
    expect(debugOf(wrapper2).streamCacheSize).toBe(1)
    expect(debugOf(wrapper2).historyCacheSize).toBe(0)
    wrapper2.unmount()
  })

  it('streamMdCache 有块数上限，并在会话切换时清空', async () => {
    const messages = Array.from({ length: 70 }, (_, index) => {
      const message = messageWith([{ kind: 'text', seg: 0, text: `流式 ${index}`, streaming: true }], `live-${index}`)
      message.streaming = true
      message.done = false
      return message
    })
    const wrapper = await mountPanel(messages)
    expect(debugOf(wrapper).streamCacheSize).toBeLessThanOrEqual(64)
    expect(debugOf(wrapper).streamCacheBytes).toBeLessThanOrEqual(2 * 1024 * 1024)

    useAiStore(testPinia).sessionId = 'another-session'
    await settle(wrapper)
    expect(debugOf(wrapper).streamCacheSize).toBe(0)
    expect(debugOf(wrapper).streamCacheBytes).toBe(0)
    wrapper.unmount()
  })

  it('ToolPart 派生值按段 WeakMap 复用：同一段只解析一次', async () => {
    const wrapper = await mountPanel([
      messageWith([
        toolPart({ requiresAction: true, kind2: 'CONFIRM', status: 'pending' })
      ])
    ])
    const misses = debugOf(wrapper).toolDeriveMisses
    expect(misses.paramRows).toBeLessThanOrEqual(1)
    expect(misses.gateRows).toBeLessThanOrEqual(1)
    expect(misses.bodyRows).toBeLessThanOrEqual(1)
    expect(misses.cardSummary).toBeLessThanOrEqual(1)
    expect(misses.conflictRows).toBeLessThanOrEqual(1)
    wrapper.unmount()
  })

  it('布局只测新增/变化消息：流式助手 token 不重复量全部用户消息', async () => {
    const wrapper = await mountPanel([
      userMessageWith([{ kind: 'text', seg: 0, text: '早先用户长文', streaming: false }], 'u-1'),
      liveMessageWith([{ kind: 'text', seg: 0, text: '回答', streaming: true }], 'a-2')
    ])
    const store = useAiStore(testPinia)
    await settle(wrapper)
    const baseline = debugOf(wrapper).measurePasses.length
    store.handleFrame({ type: 'delta', text: '继续', seq: 2 })
    await settle(wrapper)
    // 夹具自检：帧必须落在**已在屏上的**宿主消息里。`messageWith()` 默认 `done:true` + 助手角色，
    // `currentAssistantMessage()` 会判成"无宿主"另开一条空气泡 —— 那是整列重渲，量全部消息是夹具
    // 造的假象，与"流式 token 是否只量变化消息"无关。
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1]!.content).toContain('继续')
    const after = debugOf(wrapper).measurePasses.slice(baseline)
    expect(after.every((ids) => ids.length === 0)).toBe(true)
    wrapper.unmount()
  })

  it('输入框本地态不触发消息列表重渲（v-memo 边界）', async () => {
    const wrapper = await mountPanel([messageWith([{ kind: 'text', seg: 0, text: '稳定正文', streaming: false }])])
    const calls = debugOf(wrapper).markdownHtmlCalls
    const textarea = wrapper.find('[data-testid="composer-input"]')
    await textarea.setValue('本地草稿')
    await wrapper.vm.$nextTick()
    expect(debugOf(wrapper).markdownHtmlCalls).toBe(calls)
    expect(wrapper.text()).toContain('稳定正文')
    wrapper.unmount()
  })
})
