/**
 * 2026-10-29 审计批（M-15 / M-16）：AiPanel 的发送闸与「编辑后重跑」的失败还原。
 *
 * <h2>两条口径</h2>
 * - M-15：健康探针失败（网络抖动 / 代理 502 / 体形态不符）**不得**直接禁用发送 ——
 *   发送闸只看真实轮次（`loading`）与挂起（`suspended` / 待决卡）；探针结果只作提示。
 * - M-16：「编辑后重跑」是先删后发（不可逆删除 + 原文本改写）。发送失败时，被删的消息、
 *   被改写的原文、编辑锚点都必须回来，否则用户只剩刷新一条路（而刚改的文本服务端从未收到）。
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
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
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('M-15 健康探针失败不再锁死输入', { timeout: 20_000 }, () => {
  it('M-15 原始：探针失败（health=null + healthError）⇒ 仍可输入并发送', async () => {
    api.health.mockRejectedValue(new ApiError('probe down', 502, 'NETWORK_ERROR', null))
    const wrapper = await mountPanel()
    const store = useAiStore()
    expect(store.available).toBe(false)
    expect(store.healthError).not.toBeNull()

    await wrapper.find('[data-testid="composer-input"]').setValue('探针失败也要能发')
    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    expect(api.chat).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('M-15 反向：探针成功且明确不可用 ⇒ 仍按降级限制（不把"已关掉 AI"当抖动）', async () => {
    api.health.mockResolvedValue({ available: false, code: 'AI_UNAVAILABLE', message: '未配置可用模型' })
    const wrapper = await mountPanel()
    const store = useAiStore()
    expect(store.health).not.toBeNull()
    await wrapper.find('[data-testid="composer-input"]').setValue('不可用时不能发')

    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    expect(api.chat).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('M-15 反向：真实轮次与挂起仍然限制发送（健康探针成功也不放宽）', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    expect(store.available).toBe(true)
    await wrapper.find('[data-testid="composer-input"]').setValue('有内容')

    store.suspended = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="stop-btn"]').exists()).toBe(true)

    store.suspended = false
    store.loading = true
    await wrapper.vm.$nextTick()
    // 在跑一轮时输入区改为「停止」按钮：发送入口不出现（不是"变灰的发送钮"）
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="stop-btn"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

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

describe('M-16 编辑重跑的失败还原', { timeout: 20_000 }, () => {
  it('M-16 原始：编辑重跑发送失败 ⇒ 回插被删消息并还原原文', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.messages = [
      chatMessage('u-edit', 'user', '原始问题'),
      chatMessage('a-1', 'assistant', '旧回答一'),
      chatMessage('a-2', 'assistant', '旧回答二')
    ]
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    const input = wrapper.find('[data-testid="composer-input"]')
    expect((input.element as HTMLTextAreaElement).value).toBe('原始问题')
    await input.setValue('改写后的问题')

    api.chat.mockRejectedValueOnce(new ApiError('boom', 500, 'EMPTY_REPLY', null))
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    // 被删的两条回来了（AI 的旧回答不再只能靠刷新找回）
    expect(store.messages.some((message) => message.id === 'a-1')).toBe(true)
    expect(store.messages.some((message) => message.id === 'a-2')).toBe(true)
    // 被改写的用户原文回来了
    const target = store.messages.find((message) => message.id === 'u-edit')
    expect(target?.content).toBe('原始问题')
    wrapper.unmount()
  })

  it('M-16 相邻：编辑重跑成功 ⇒ 被删消息不再回插（还原只在失败路径）', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.messages = [
      chatMessage('u-edit', 'user', '原始问题'),
      chatMessage('a-1', 'assistant', '旧回答一'),
      chatMessage('a-2', 'assistant', '旧回答二')
    ]
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    await wrapper.find('[data-testid="composer-input"]').setValue('改写后的问题')
    api.chat.mockResolvedValueOnce(undefined)
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect(store.messages.some((message) => message.id === 'a-1')).toBe(false)
    expect(store.messages.some((message) => message.id === 'a-2')).toBe(false)
    const target = store.messages.find((message) => message.id === 'u-edit')
    expect(target?.content).toBe('改写后的问题')
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    wrapper.unmount()
  })

  it('M-16 反向：失败时用户已另写内容 ⇒ 消息照样回插，但草稿不被覆盖', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.messages = [
      chatMessage('u-edit', 'user', '原始问题'),
      chatMessage('a-1', 'assistant', '旧回答一')
    ]
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    await wrapper.find('[data-testid="composer-input"]').setValue('改写后的问题')

    let rejectChat: (error: unknown) => void = () => {}
    api.chat.mockImplementationOnce(() => new Promise<void>((_resolve, reject) => {
      rejectChat = reject
    }))
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await Promise.resolve()

    // 发送在途时用户又写了新内容
    await wrapper.find('[data-testid="composer-input"]').setValue('新写的草稿')
    rejectChat(new ApiError('boom', 500, 'EMPTY_REPLY', null))
    await flushPromises()

    expect(store.messages.some((message) => message.id === 'a-1')).toBe(true)
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value)
      .toBe('新写的草稿')
    wrapper.unmount()
  })
})
