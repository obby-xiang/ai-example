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
import { applyLocale } from '@/locales'
import { MIRROR_PREFIX, useAiStore } from '@/stores/ai'
import type { ChatMessage } from '@/types/ai'
import { createFakeChat, type FakeChat } from './ai-fake-transport'

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

let fakeChat: FakeChat

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
  fakeChat = createFakeChat()
  api.chat.mockImplementation(fakeChat.impl)
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

  it('I8 顺序：回插在编辑锚点之后、失败气泡在末尾，并且现场写进镜像', async () => {
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

    api.chat.mockRejectedValueOnce(new ApiError('boom', 500, 'EMPTY_REPLY', null))
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    const ids = store.messages.map((message) => message.id)
    expect(ids.slice(0, 3)).toEqual(['u-edit', 'a-1', 'a-2'])
    // 本次失败轮自己的气泡（错误卡宿主）留在末尾
    expect(store.messages[store.messages.length - 1]?.role).toBe('assistant')
    expect(store.messages[store.messages.length - 1]?.id).not.toBe('a-2')

    // 现场写进镜像：刷新后不应再丢
    const mirror = sessionStorage.getItem(`${MIRROR_PREFIX}${store.sessionId}`)
    expect(mirror).toBeTruthy()
    expect(mirror).toContain('旧回答一')
    expect(mirror).toContain('旧回答二')
    expect(mirror).toContain('原始问题')
    wrapper.unmount()
  })

  it('I8 相邻：编辑重跑被用户停止（cancelled）同样还原删除现场', async () => {
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

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    const transport = fakeChat.last()
    // 用户点了停止：本轮被放弃 ⇒ 被删的旧回答必须回来（不是"停止即永久删除"）
    await store.stop()
    transport.close({ terminal: false, reason: '连接已中止' })
    await flushPromises()

    expect(store.messages.some((message) => message.id === 'a-1')).toBe(true)
    expect(store.messages.find((message) => message.id === 'u-edit')?.content).toBe('原始问题')
    wrapper.unmount()
  })

  it('I3 会话守卫：编辑重跑遇到 410 换会话 ⇒ 不回插旧会话消息，只回填草稿', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    store.sessionId = 'sess-before-purge'
    store.messages = [
      chatMessage('u-edit', 'user', '原始问题'),
      chatMessage('a-1', 'assistant', '旧回答一')
    ]
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    await wrapper.find('[data-testid="composer-input"]').setValue('改写后的问题')

    api.chat.mockRejectedValueOnce(new ApiError('purged', 410, 'SESSION_PURGED', null))
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    // 410 ⇒ 本地换会话（adoptFreshSession）：旧会话的消息不得被"编辑还原"复活进新会话
    expect(store.sessionId).not.toBe('sess-before-purge')
    expect(store.messages.some((message) => message.id === 'a-1')).toBe(false)
    expect(store.messages.some((message) => message.id === 'u-edit')).toBe(false)
    // 用户刚改的文本仍留在输入框（服务端从未收到过，不能一起丢）
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value)
      .toBe('改写后的问题')
    wrapper.unmount()
  })
})

describe('I8 健康提示文案与发送闸同一判据', { timeout: 20_000 }, () => {
  it('N1 原始：探针失败 ⇒ 胶囊单独一档「探测失败」（不得绿色可用），healthError 有出口', async () => {
    api.health.mockRejectedValue(new ApiError('probe down: 502', 502, 'NETWORK_ERROR', null))
    const wrapper = await mountPanel()
    const store = useAiStore()
    const chip = wrapper.find('[data-testid="health-chip"]')
    expect(chip.text()).toBe('探测失败')
    // 不是 success 绿（R-163 C2 的绿族），也不是"可用"
    expect(chip.classes()).not.toContain('text-[#1a7f45]')
    expect(chip.classes()).not.toContain('bg-[#edf7f0]')
    // healthError 有出口 ⇒ 固定友好文案（R3 起原文不上屏，见 N-1）
    expect(chip.attributes('title')).toBe('AI 状态检测未成功，可点「重新检测」；不影响页面其它操作')
    expect(chip.attributes('title')).toBeTruthy()
    expect(chip.attributes('title')).not.toBe(store.healthError)
    wrapper.unmount()
  })

  it('N-1 原始：探针失败的胶囊 title 是固定友好文案（后端原文不上屏、不含机制词）', async () => {
    // 后端/网关原文里带足机制词：一律不得出现在屏上任何位置
    api.health.mockRejectedValue(new ApiError(
      'AI_API_KEY is not set for provider=deepseek (KEK unreadable, http 502 from gateway, Redis down)',
      502,
      'AI_KEY_MISSING',
      null
    ))
    const wrapper = await mountPanel()
    const store = useAiStore()
    expect(store.healthError).toContain('AI_API_KEY')

    const chip = wrapper.find('[data-testid="health-chip"]')
    expect(chip.attributes('title')).toBe('AI 状态检测未成功，可点「重新检测」；不影响页面其它操作')
    expect(chip.attributes('title')).not.toBe(store.healthError)

    const forbidden = ['AI_API_KEY', 'DEEPSEEK_API_KEY', 'AI_SECRET_KEK', 'KEK', 'provider', 'http', '502', 'Redis']
    for (const word of forbidden) {
      expect(chip.attributes('title'), word).not.toContain(word)
      expect(wrapper.text(), word).not.toContain(word)
    }
    wrapper.unmount()
  })

  it('N-1 相邻：同一档在英文界面给英文友好文案（不是中文、也不是原文）', async () => {
    api.health.mockRejectedValue(new ApiError('AI_API_KEY missing (provider=deepseek)', 502, 'AI_KEY_MISSING', null))
    const wrapper = await mountPanel()
    applyLocale('en-US')
    await wrapper.vm.$nextTick()

    const title = wrapper.find('[data-testid="health-chip"]').attributes('title')
    expect(title).toBe('AI health check did not succeed. Click “Recheck”; other page features are unaffected.')
    expect(title).not.toContain('AI_API_KEY')
    applyLocale('zh-CN')
    wrapper.unmount()
  })

  it('N1 相邻：明确不可用 ⇒ 「已降级」；从未探测 ⇒ 「未检测」（三档不串）', async () => {
    api.health.mockResolvedValue({ available: false, code: 'AI_UNAVAILABLE', message: '未配置可用模型' })
    const degraded = await mountPanel()
    expect(degraded.find('[data-testid="health-chip"]').text()).toBe('已降级')
    expect(degraded.find('[data-testid="health-chip"]').attributes('title')).toBeUndefined()
    degraded.unmount()

    api.health.mockImplementation(() => new Promise(() => {}))
    const store = useAiStore()
    store.health = null
    store.healthError = null
    const untested = await mountPanel()
    expect(untested.find('[data-testid="health-chip"]').text()).toBe('未检测')
    untested.unmount()
  })

  it('I8 原始：探针失败 ⇒ 不显示"AI 能力不可用"降级条、占位文案不谎报不可用', async () => {
    api.health.mockRejectedValue(new ApiError('probe down', 502, 'NETWORK_ERROR', null))
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="alert-degraded"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="composer-input"]').attributes('placeholder'))
      .toBe('输入消息，Enter 发送，Shift+Enter 换行')
    wrapper.unmount()
  })

  it('I8 反向：探针成功且明确不可用 ⇒ 降级条与不可用占位文案一致出现', async () => {
    api.health.mockResolvedValue({ available: false, code: 'AI_UNAVAILABLE', message: '未配置可用模型' })
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="alert-degraded"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="composer-input"]').attributes('placeholder'))
      .toBe('AI 当前不可用（已降级，可继续使用页面手动操作）')
    wrapper.unmount()
  })
})

describe('R4 停止态不恢复挂起卡 + 探针原文零出口', { timeout: 20_000 }, () => {
  it('R4 原始：停止后 loadHistory 即使后端 awaitingExternal 也不重建挂起卡、不重置 suspended', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-r4-stopped'
    store.historyLoaded = true
    api.history.mockResolvedValue({
      sessionId: 'sess-r4-stopped',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    })
    // 后端仍把这一轮当"等外部决策"（cancel 尚未落地/未传播）
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-stopped',
      snapshotStatus: 'SUSPENDED',
      pendingEntries: [
        { toolCallId: 'confirm-stopped', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{}', req: true }
      ],
      unresolvedExternal: [],
      awaitingExternal: true,
      archiveMaxSeq: 9
    })
    store.loading = true
    await store.stop()
    expect(store.stopped).toBe(true)

    const wrapper = await mountPanel()
    await flushPromises()

    // 本地"已停止"展示保持不变：不挂起、不锁输入、不重建可点卡、不重连
    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull()
    expect(store.pendingConfirms).toHaveLength(0)
    expect(store.messages.some((message) => message.parts.some(
      (part) => part.kind === 'tool' && part.toolCallId === 'confirm-stopped'
    ))).toBe(false)
    expect(api.reattachRun).not.toHaveBeenCalled()
    // 输入区解禁（发送入口在，停止钮不在）
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="stop-btn"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('R4 反向：未停止时同快照照常恢复挂起卡并锁输入（守卫不误伤）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-r4-live'
    store.historyLoaded = true
    store.messages = [
      {
        id: 'a-live',
        role: 'assistant',
        content: '正文',
        parts: [{ kind: 'text', seg: 0, text: '正文', streaming: false }],
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: false,
        done: true,
        runId: 'run-live'
      }
    ]
    expect(store.stopped).toBe(false)
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-live',
      snapshotStatus: 'SUSPENDED',
      pendingEntries: [
        { toolCallId: 'confirm-live', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{}', req: true }
      ],
      unresolvedExternal: [],
      awaitingExternal: true,
      archiveMaxSeq: 9
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))

    await store.restoreSuspendedSnapshot()

    expect(store.suspended).toBe(true)
    expect(store.pendingConfirms).toHaveLength(1)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
  })

  it('R4 相邻：探针原文零出口 —— 降级文案只取 health.message / 固定兜底', async () => {
    const store = useAiStore()
    store.health = { available: false, code: 'AI_UNAVAILABLE', message: '未配置可用模型' } as never
    store.healthError = 'AI_API_KEY is not set for provider=deepseek (KEK unreadable)'
    expect(store.unavailableReason).toBe('未配置可用模型')

    store.health = null
    expect(store.unavailableReason).toBeNull()

    const wrapper = await mountPanel()
    const forbidden = ['AI_API_KEY', 'provider', 'KEK']
    for (const word of forbidden) {
      expect(wrapper.text(), word).not.toContain(word)
    }
    wrapper.unmount()
  })
})
