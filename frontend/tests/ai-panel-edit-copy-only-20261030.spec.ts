/**
 * 2026-10-30 用户直裁：AiPanel 用户消息「编辑」= **只把该条正文回填到输入框并聚焦**。
 *
 * <p>不发送、不重跑、不删除/截断原消息、不设编辑/重跑锚点；之后用户自己点发送，
 * 就是一条普通新消息（追加、不截断、不带 `skipUserRecord`）。
 *
 * <p>本文件只锁这两条口径（每条一个精确用例）；旧的「编辑后重跑」用例已随该入口一并退役。
 */
import { beforeAll, beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { ChatMessage } from '@/types/ai'

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
  installIdentityFixture()
})

/** 跨用例收尾：丢弃本页签在途轮次（模块级 `currentAbort` 不随 pinia 换）⇒ 每个用例一张新页面。 */
afterEach(() => {
  useAiStore().adoptFreshSession()
})

enableAutoUnmount(afterEach)

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

function chatMessage(id: string, role: 'user' | 'assistant', text: string): ChatMessage {
  return {
    id,
    role,
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

/** 一轮完整现场：本轮提问 + 回答 + 后续追问（后被截断的正是这两条）。 */
function seed(): ChatMessage[] {
  return [
    chatMessage('u-1', 'user', '原始提问'),
    chatMessage('a-1', 'assistant', '原始回答'),
    chatMessage('u-2', 'user', '后续追问')
  ]
}

/** 现场快照：任何删除/截断/原地改写都会让它变。 */
function snapshot(store: ReturnType<typeof useAiStore>): unknown[] {
  return store.messages.map((message) => ({
    id: message.id,
    role: message.role,
    content: message.content,
    parts: message.parts.map((part) => (part.kind === 'text' ? `text:${part.text}` : part.kind))
  }))
}

describe('2026-10-30 直裁：编辑 = 只回填正文（不重跑）', { timeout: 20_000 }, () => {
  it('编辑只回填正文并聚焦：不发送、不改/不删消息、不弹确认', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.messages = seed()
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    const before = snapshot(store)
    const confirmSpy = vi.spyOn(window, 'confirm')
    const sendSpy = vi.spyOn(store, 'send')
    api.chat.mockClear()

    const input = wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement
    // jsdom 里 VTU 默认挂到游离节点，`document.activeElement` 不随之变；按仓内既有口径桩 focus。
    const focusSpy = vi.spyOn(input, 'focus')

    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    await flushPromises()

    expect(input.value).toBe('原始提问')
    expect(focusSpy.mock.calls.some((call) => call[0]?.preventScroll === true)).toBe(true)
    // 不发送、不重跑（无 /chat、无 store.send）
    expect(api.chat).not.toHaveBeenCalled()
    expect(sendSpy).not.toHaveBeenCalled()
    // 不删除/不截断/不原地改写：消息数组一字不动
    expect(snapshot(store)).toEqual(before)
    // 无任何确认弹窗（旧的「将删除 N 条 / 覆盖草稿」属于重跑入口）
    expect(confirmSpy).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    sendSpy.mockRestore()
    wrapper.unmount()
  })

  it('回填后主动发送 = 普通新消息：原消息不截断、新文本追加、不带 skipUserRecord', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.messages = seed()
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    // 点击编辑时输入框为空 ⇒ 新实现不弹确认；桩恒 true 只是为了让**改造前**的实现（删除条数确认）
    // 也能走到底，RED 才落在「发送走了重跑通道」而不是被弹窗掩掉。
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const sendSpy = vi.spyOn(store, 'send')
    api.chat.mockClear()

    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    const input = wrapper.find('[data-testid="composer-input"]')
    expect((input.element as HTMLTextAreaElement).value).toBe('原始提问')
    await input.setValue('改写后的提问')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect((api.chat.mock.calls[0][0] as Record<string, unknown>).message).toBe('改写后的提问')
    // 普通新消息：send 不带第二参（旧重跑通道 skipUserRecord 不再被这个入口触发）
    expect(sendSpy.mock.calls[0]?.[1]).toBeUndefined()

    // 不截断：原三条按原序仍在，原文本未被改写
    expect(store.messages.slice(0, 3).map((message) => message.id)).toEqual(['u-1', 'a-1', 'u-2'])
    expect(store.messages[0]?.content).toBe('原始提问')
    expect(store.messages[1]?.content).toBe('原始回答')
    expect(store.messages[2]?.content).toBe('后续追问')

    // 新文本作为**独立用户消息**追加（不是原地改写第一条提问）
    const appended = store.messages.slice(3).filter((message) => message.role === 'user')
    expect(appended).toHaveLength(1)
    expect(appended[0]?.content).toBe('改写后的提问')
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(3)

    confirmSpy.mockRestore()
    sendSpy.mockRestore()
    wrapper.unmount()
  })
})

/**
 * 2026-10-30 补充规格：覆盖保护 —— 输入框**含任何非空白字符**时，点「编辑」必须先确认；
 * 确认才复制正文并聚焦，取消则输入框与消息完全不变；空 / 全空白字符不弹确认，直接复制聚焦。
 * 仍然不发送、不重跑、不删除/截断、不设锚点。
 */
describe('2026-10-30 补充：编辑的覆盖保护（草稿非空先确认）', { timeout: 20_000 }, () => {
  /** 装载现场并返回输入框原生元素（草稿由调用方决定）。 */
  async function mountWithDraft(draft: string): Promise<{
    wrapper: ReturnType<typeof mount>
    store: ReturnType<typeof useAiStore>
    input: HTMLTextAreaElement
    before: unknown[]
  }> {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.messages = seed()
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    const before = snapshot(store)
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue(draft)
    return { wrapper, store, input: box.element as HTMLTextAreaElement, before }
  }

  it('草稿非空 + 取消：不复制、不聚焦，输入框与消息完全不变', async () => {
    const { wrapper, store, input, before } = await mountWithDraft('还没发出去的草稿')
    const focusSpy = vi.spyOn(input, 'focus')
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const sendSpy = vi.spyOn(store, 'send')
    api.chat.mockClear()

    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(String(confirmSpy.mock.calls[0]?.[0])).toContain('覆盖')
    // 取消 = 什么都不做：草稿原样、焦点不动、消息数组逐字不变、没有任何请求
    expect(input.value).toBe('还没发出去的草稿')
    expect(focusSpy).not.toHaveBeenCalled()
    expect(snapshot(store)).toEqual(before)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(api.chat).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    sendSpy.mockRestore()
    wrapper.unmount()
  })

  it('草稿非空 + 确认：复制该条正文并聚焦（消息仍不变、不发送）', async () => {
    const { wrapper, store, input, before } = await mountWithDraft('还没发出去的草稿')
    const focusSpy = vi.spyOn(input, 'focus')
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const sendSpy = vi.spyOn(store, 'send')
    api.chat.mockClear()

    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(input.value).toBe('原始提问')
    expect(focusSpy.mock.calls.some((call) => call[0]?.preventScroll === true)).toBe(true)
    // 覆盖保护只决定「敢不敢覆盖草稿」：不发送、不重跑、不删/不截断、不设锚点
    expect(snapshot(store)).toEqual(before)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(api.chat).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    sendSpy.mockRestore()
    wrapper.unmount()
  })

  it('草稿全是空白字符：不弹确认，直接复制并聚焦', async () => {
    const { wrapper, store, input, before } = await mountWithDraft('  \n\t  ')
    const focusSpy = vi.spyOn(input, 'focus')
    const confirmSpy = vi.spyOn(window, 'confirm')
    api.chat.mockClear()

    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    await flushPromises()

    // 全空白不算「用户真的有草稿」：不打扰，直接回填
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(input.value).toBe('原始提问')
    expect(focusSpy.mock.calls.some((call) => call[0]?.preventScroll === true)).toBe(true)
    expect(snapshot(store)).toEqual(before)
    expect(api.chat).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    wrapper.unmount()
  })
})
