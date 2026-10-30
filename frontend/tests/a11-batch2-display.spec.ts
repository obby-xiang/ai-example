/**
 * 批次2 前端展示层 · 面板交互（只写不跑；聚焦 RED/GREEN 在 a11-batch2-error-card.spec.ts）。
 *
 * 覆盖父令的六件事：
 * 1. park 气泡 = 真实待操作时统一「等待中…」，其余运行阶段显示「工作中…」；
 * 2. unconfirmed 中性视图（本轮已结束、状态未确认 + 重新载入），不写乐观 done；
 * 3. 历史恢复错误条 + 重试（store 适配器）；
 * 4. 409「回到进行中的回答」按钮（store 适配器）；
 * 5. 编辑用户消息前提示「将删除 N 条后续消息」并确认；
 * 6. 继续 / 重试本轮分离；普通 regenerate 本批不做（AR-17）⇒ 入口整体隐藏。
 *
 * 本 spec 只通过 store 的**公开字段 / action** 注入状态与动作；「重试本轮」走
 * `store.retryModelStep`（真实窄入口，且要求 `message.runId` 归属可证明）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart, PendingToolCall } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import { applyLocale } from '@/locales'
import { buildErrorCard } from '@/utils/error-card'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn()
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

function buildMessage(
  parts: MessagePart[],
  pendingCall: PendingToolCall | null = null,
  streaming = false
): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-1',
    role: 'assistant',
    content: text,
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall,
    streaming,
    done: !streaming
  }
}

function userMessage(id: string, text: string): ChatMessage {
  return {
    id,
    role: 'user',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

type LooseStore = Record<string, unknown>

async function mountPanel(messages: ChatMessage[], state: LooseStore = {}): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  for (const [key, value] of Object.entries(state)) {
    ;(store as unknown as LooseStore)[key] = value
  }
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.chat.mockReset()
  api.feedbackSubmit.mockReset()
  api.confirm.mockReset()
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
  api.history.mockResolvedValue({ messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false })
  applyLocale('zh-CN')
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('批次2 · park 气泡', () => {
  it('真实待操作时统一显示「等待中」，沿用呼吸样式且不显示时间戳', async () => {
    const reply = buildMessage(
      [
        { kind: 'text', seg: 0, text: '准备执行', streaming: true },
        {
          kind: 'tool',
          seg: 1,
          toolCallId: 'c1',
          name: 'start_export',
          kind2: 'CONFIRM',
          status: 'pending',
          requiresAction: true
        }
      ],
      { toolCallId: 'c1', name: 'start_export', kind: 'CONFIRM', args: {}, rawArgs: '{}', status: 'pending' },
      true
    )
    reply.timestamp = 1_700_000_000_000
    const wrapper = await mountPanel([userMessage('u-1', '请导出'), reply], { suspended: true })
    const wait = wrapper.find('[data-testid="park-waiting"]')
    expect(wait.exists()).toBe(true)
    expect(wait.text()).toBe('等待中…')
    expect(wait.classes()).toEqual(expect.arrayContaining([
      'inline-flex', 'items-center', 'gap-[5px]', 'text-[12.2px]', 'text-[#b45309]', 'tracking-[.3px]'
    ]))
    expect(wait.classes()).not.toContain('loading-breath')
    const dot = wait.find('.animate-breathe')
    expect(dot.exists()).toBe(true)
    expect(dot.classes()).toContain('bg-[#b45309]')
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="msg-timestamp"]').exists()).toBe(false)

    const store = useAiStore()
    store.messages[1]!.streaming = false
    store.messages[1]!.done = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="park-waiting"]').exists()).toBe(false)
    await new Promise((resolve) => setTimeout(resolve, 350))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="msg-timestamp"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('挂起生成式表单时，气泡统一显示「等待中」', async () => {
    const reply = buildMessage(
      [
        {
          kind: 'tool',
          seg: 0,
          toolCallId: 'f1',
          name: 'generative_form',
          kind2: 'FRONTEND',
          status: 'pending',
          requiresAction: true
        }
      ],
      { toolCallId: 'f1', name: 'generative_form', kind: 'FRONTEND', args: {}, rawArgs: '{}', status: 'pending' },
      true
    )
    const wrapper = await mountPanel([reply], { suspended: true, activeForm: { toolCallId: 'f1', runId: 'r1', form: {}, status: 'filling', error: null, values: {}, fieldErrors: [] } })
    expect(wrapper.find('[data-testid="park-waiting"]').text()).toContain('等待中')
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('批次2 · unconfirmed 中性视图', () => {
  it('状态未确认时显示中性提示与重新载入入口，不伪造 done、不显示工作中/继续', async () => {
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '半截回答', streaming: true }], null, true)
    reply.unconfirmed = true
    const store = useAiStore()
    const rest = vi.spyOn(store, 'retryLoadHistory').mockResolvedValue(undefined)
    const wrapper = await mountPanel([reply], { suspended: true, lastRoundOutcome: 'unconfirmed' })
    const neutral = wrapper.find('[data-testid="turn-unconfirmed-a-1"]')
    expect(neutral.exists()).toBe(true)
    expect(neutral.text()).toContain('本轮已结束，状态未确认')
    expect(neutral.text()).toContain('重新载入')
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(false)
    expect(store.messages[0]?.done).toBe(false) // 显示层不写乐观 done
    expect(store.messages[0]?.streaming).toBe(true)
    await neutral.find('[data-testid="reload-turn-a-1"]').trigger('click')
    expect(rest).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('批次2 · 历史恢复错误出口', () => {
  it('historyError 存在时出错误条，点重试调用适配器 action', async () => {
    const store = useAiStore()
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '本地缓存', streaming: false }])], {
      historyError: { scope: 'history', code: 'NETWORK_ERROR', status: 0, at: Date.now() }
    })
    const bar = wrapper.find('[data-testid="alert-history-error"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('历史恢复失败')
    expect(bar.text()).toContain('网络不可达')
    const retry = vi.spyOn(store, 'retryLoadHistory').mockResolvedValue(undefined)
    await bar.find('[data-testid="retry-history"]').trigger('click')
    expect(retry).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('仅快照恢复失败（无 recoveryGap）时只有错误条与一个动作，不出现完整性文案', async () => {
    // restoreSuspendedSnapshot 的 preparingRun 分支只写 currentError、不登记缺口
    // （ai.ts:3717-3719）—— 此时「内容可能不完整」是凭空的事实，不得上屏。
    const store = useAiStore()
    const restore = vi.spyOn(store, 'retryRestoreSnapshot').mockResolvedValue(undefined)
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '完整内容', streaming: false }])], {
      currentError: { scope: 'current', code: 'NETWORK_ERROR', status: 0, at: Date.now() }
    })
    const bar = wrapper.find('[data-testid="alert-history-error"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('会话恢复失败')
    expect(bar.findAll('button')).toHaveLength(1)
    expect(wrapper.find('[data-testid="alert-recovery-gap"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('不完整')
    await bar.find('[data-testid="retry-history"]').trigger('click')
    expect(restore).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('同一恢复失败只出一条全局提示与一个重试动作（错误条合并不完整事实，消息级不重复）', async () => {
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '镜像里的这一段', streaming: false }])
    reply.id = 'a-gap'
    reply.recoveryGap = true
    const store = useAiStore()
    // sealRecovery 的一次失败：historyError 与 recoveryGap 同帧写入
    const wrapper = await mountPanel([reply], {
      historyError: { scope: 'history', code: 'NETWORK_ERROR', status: 0, at: Date.now() },
      recoveryGap: { reason: 'history-unavailable', fromSeq: null, toSeq: null, at: Date.now() }
    })
    expect(wrapper.findAll('[data-testid="alert-history-error"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="alert-recovery-gap"]').exists()).toBe(false)
    const bar = wrapper.find('[data-testid="alert-history-error"]')
    expect(bar.text()).toContain('历史恢复失败')
    expect(bar.text()).toContain('部分内容可能不完整')
    // 一个动作：不再并列第二个「重新载入」
    expect(bar.findAll('button')).toHaveLength(1)
    expect(wrapper.find('[data-testid="retry-recovery-gap"]').exists()).toBe(false)
    // 消息级第三条不出现
    expect(wrapper.find('[data-testid="recovery-gap-msg-a-gap"]').exists()).toBe(false)
    // 无镜像时同样出条 —— 不得无条件声称「当前显示的是本地缓存」
    expect(wrapper.text()).not.toContain('本地缓存')
    const retry = vi.spyOn(store, 'retryLoadHistory').mockResolvedValue(undefined)
    await bar.find('[data-testid="retry-history"]').trigger('click')
    expect(retry).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('archive-window 真实缺帧（未伴随恢复错误）仍单独出中性缺口条', async () => {
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '缺帧后的内容', streaming: false }])
    reply.id = 'a-gap2'
    reply.recoveryGap = true
    const wrapper = await mountPanel([reply], {
      recoveryGap: { reason: 'archive-window', fromSeq: 4, toSeq: 6, at: Date.now() }
    })
    expect(wrapper.find('[data-testid="alert-history-error"]').exists()).toBe(false)
    const bar = wrapper.find('[data-testid="alert-recovery-gap"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('部分内容可能不完整')
    expect(bar.findAll('button')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('批次2 · 409 回到进行中的回答', () => {
  it('store 暴露 busyRun 时通知槽出「回到进行中的回答」，点击走 reattachBusyRun', async () => {
    const store = useAiStore()
    const reattach = vi.spyOn(store, 'reattachBusyRun').mockResolvedValue(undefined)
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '上一轮', streaming: false }])], {
      notice: '这条消息没有发出去，请稍后再试。',
      noticeCode: 'ai.notice.sendFailed',
      busyRun: { runId: 'run-busy', reattach: null }
    })
    const button = wrapper.find('[data-testid="return-active-run"]')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('回到进行中的回答')
    await button.trigger('click')
    expect(reattach).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('批次2 · 编辑用户消息（2026-10-30 直裁：只回填、无删除提示）', () => {
  it('编辑不弹「将删除 N 条」：直接回填该条正文，后续消息一条不删', async () => {
    const user = userMessage('u-edit', '原提问')
    const a1 = buildMessage([{ kind: 'text', seg: 0, text: '回答一', streaming: false }])
    a1.id = 'a-1'
    const a2 = buildMessage([{ kind: 'text', seg: 0, text: '回答二', streaming: false }])
    a2.id = 'a-2'
    const wrapper = await mountPanel([user, a1, a2])
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    // 编辑不再有删除后果：不弹任何确认，正文直接回填
    expect(confirmSpy).not.toHaveBeenCalled()
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('原提问')
    // 后的两条消息仍在屏上（编辑是纯回填，不删不截断）
    expect(wrapper.find('[data-testid="msg-bubble-a-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="msg-bubble-a-2"]').exists()).toBe(true)
    confirmSpy.mockRestore()
    wrapper.unmount()
  })
})

describe('批次2 · 继续 / 重试本轮（AR-17：普通 regenerate 本批不做）', () => {
  it('有产出且失败 ⇒ 只出「继续」，不出「重试本轮」、不出 regenerate', async () => {
    const user = userMessage('u-1', '提问')
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '部分回答', streaming: false }])
    reply.id = 'a-1'
    reply.runId = 'run-a1'
    const wrapper = await mountPanel([user, reply], { lastRoundOutcome: 'interrupted' })
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="retry-turn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="regenerate-msg-a-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('零产出失败且归属可证明 ⇒ 只出「重试本轮」，不出「继续」', async () => {
    const user = userMessage('u-1', '提问')
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: false }])
    reply.id = 'a-1'
    reply.runId = 'run-a1'
    const wrapper = await mountPanel([user, reply], { lastRoundOutcome: 'error' })
    expect(wrapper.find('[data-testid="retry-turn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="regenerate-msg-a-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('零正文零思考但工具已结算的失败轮 ⇒ 重试本轮走仅模型步重跑', async () => {
    const user = userMessage('u-1', '提问')
    const reply = buildMessage([{
      kind: 'tool',
      seg: 0,
      toolCallId: 'tool-settled',
      name: 'list_tasks',
      kind2: 'BACKEND',
      status: 'succeeded',
      requiresAction: false,
      result: '[]'
    }])
    reply.id = 'a-1'
    reply.runId = 'run-a1'
    const store = useAiStore()
    const retry = vi.spyOn(store, 'retryModelStep').mockResolvedValue('accepted')
    const send = vi.spyOn(store, 'send').mockResolvedValue({ outcome: 'ok', userMessageId: null })
    const wrapper = await mountPanel([user, reply], { lastRoundOutcome: 'error' })
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(false)
    const button = wrapper.find('[data-testid="retry-turn"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(retry).toHaveBeenCalledWith(reply)
    expect(send).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('零产出失败但无 runId 归属证据 ⇒ 不出「重试本轮」（不得重试错轮）', async () => {
    const user = userMessage('u-1', '提问')
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: false }])
    reply.id = 'a-1'
    const wrapper = await mountPanel([user, reply], { lastRoundOutcome: 'error', activeRunId: 'run-other' })
    expect(wrapper.find('[data-testid="retry-turn"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('普通 regenerate 入口整体隐藏，绝不本地截断后 send', async () => {
    const user = userMessage('u-1', '提问')
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '旧回答', streaming: false }])
    reply.id = 'a-1'
    const store = useAiStore()
    const send = vi.spyOn(store, 'send').mockResolvedValue({ outcome: 'ok', userMessageId: null })
    const wrapper = await mountPanel([user, reply])
    expect(wrapper.find('[data-testid="regenerate-msg-a-1"]').exists()).toBe(false)
    expect(send).not.toHaveBeenCalled()
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(store.messages.some((message) => message.id === 'a-1')).toBe(true)
    wrapper.unmount()
  })

  it('重试本轮走真实 store action（仅模型步重跑），不本地截断、不追加气泡', async () => {
    const user = userMessage('u-1', '提问')
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: false }])
    reply.id = 'a-1'
    reply.runId = 'run-a1'
    const store = useAiStore()
    const retry = vi.spyOn(store, 'retryModelStep').mockResolvedValue('denied')
    const send = vi.spyOn(store, 'send').mockResolvedValue({ outcome: 'ok', userMessageId: null })
    const wrapper = await mountPanel([user, reply], { lastRoundOutcome: 'error' })
    await wrapper.find('[data-testid="retry-turn"]').trigger('click')
    expect(retry).toHaveBeenCalledWith(reply)
    expect(send).not.toHaveBeenCalled()
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(store.messages.some((message) => message.id === 'a-1')).toBe(true)
    wrapper.unmount()
  })
})

describe('批次2 · synthetic 与 recoveryGap 的中性呈现', () => {
  it('synthetic 历史条目渲染为系统级「已压缩」提示，不画用户气泡、不显示内部键', async () => {
    const synthetic: ChatMessage = {
      id: 'history-0',
      role: 'system',
      content: '[上下文压缩摘要]',
      parts: [{ kind: 'text', seg: 0, text: '[上下文压缩摘要]', streaming: false }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: true,
      synthetic: true
    }
    const wrapper = await mountPanel([synthetic])
    const notice = wrapper.find('[data-testid="synthetic-notice-history-0"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('已压缩')
    expect(notice.text()).not.toContain('compactedFrom')
    expect(wrapper.find('[data-testid="msg-bubble-history-0"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('recoveryGap 渲染中性「不可完整恢复」提示，不是错误卡', async () => {
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '缓存内容', streaming: false }])
    reply.id = 'a-gap'
    reply.recoveryGap = true
    const wrapper = await mountPanel([reply], {
      recoveryGap: { reason: 'history-unavailable', fromSeq: null, toSeq: null, at: Date.now() }
    })
    const bar = wrapper.find('[data-testid="alert-recovery-gap"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('不完整')
    // 同一事实只留一个提示面：全局条已在说这件事，消息级不再重复
    expect(wrapper.find('[data-testid="recovery-gap-msg-a-gap"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="error-card-a-gap"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('批次2 · 错误卡两层文案', () => {
  it('首屏是用户向摘要，机器码与后端原文收进诊断详情', async () => {
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '半截', streaming: false }])
    reply.id = 'a-err'
    reply.errorCard = buildErrorCard('UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT', '上游流提前结束')
    const wrapper = await mountPanel([reply])
    const card = wrapper.find('[data-testid="error-card-a-err"]')
    expect(card.exists()).toBe(true)
    expect(card.find('[data-testid="error-card-summary-a-err"]').text()).toContain('工具')
    expect(card.find('[data-testid="error-card-diagnostic-a-err"]').exists()).toBe(true)
    expect(card.find('[data-testid="error-card-code-a-err"]').text())
      .toBe('UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT')
    expect(card.find('[data-testid="error-card-detail-a-err"]').text()).toBe('上游流提前结束')
    wrapper.unmount()
  })
})
