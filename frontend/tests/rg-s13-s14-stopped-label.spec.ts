/**
 * 批G 真机回归（第二轮）—— S13/S14「已停止」与停止后卡面文案。
 *
 * <p>
 * 两条都发生在**挂起中停止**（确认门 / 生成式表单）：
 * - S14/S13：`stop()` 的「已停止」标记打在"最后一条助手消息"，且要求有正文或非文本段；
 *   真实形态里 GF 轮的宿主可能只有卡（无正文段），标记本身成立但面板的**元信息行**以
 *   "有正文或流式中"为渲染前提 ⇒ chip 无处渲染（真机 4/4 缺）；
 * - S13：确认卡在停止后显示「! 已超时」—— 停止就地收敛 `cancelRoundParts()` 只写段状态
 *   `cancelled`、不写决策元数据，`readOnlyStateOf()` 落 default ⇒ 用户停掉的一轮被读成"系统超时"。
 *
 * <p>
 * 断言口径：停止是**用户决策**（decision=cancel）⇒ 卡面「✗ 已取消」；「已停止」是消息级事实，
 * 一旦打上不得被后续迟到帧覆盖。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import type { ChatMessage, MessagePart } from '@/types/ai'
import { cardStatusOf, readOnlyStateOf } from '@/utils/blocks'

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
// 收集期预热：用例内的动态 import（`freshStore()` / `mountPanel()`）在**首条用例**里要付
// "首次 transform + 首次装配"的一次性成本 —— 空载 1.9–3.4s，并发满载可涨到十几秒，直接吃掉
// 该用例自己的 timeout 预算（实测：首条超时、同文件后续用例 10–40ms 通过）。这里放到收集期
// 付掉（收集不受单用例 timeout 约束），用例内动态 import 只剩重执行（~20ms）；断言与 timeout 不变。
import '@/stores/ai'
import '@/components/AiPanel/AiPanel.vue'


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

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

function toolPart(overrides: Partial<Extract<MessagePart, { kind: 'tool' }>> = {}): MessagePart {
  return {
    kind: 'tool',
    seg: 0,
    toolCallId: 'tc-1',
    name: 'start_export',
    kind2: 'CONFIRM',
    status: 'pending',
    args: '{}',
    requiresAction: true,
    ...overrides
  }
}

/** 挂起轮宿主：GF/确认卡轮的真实形态 —— 只有卡，没有正文段。 */
function cardOnlyHost(tool: MessagePart, id = 'a-cards'): ChatMessage {
  return {
    id,
    role: 'assistant',
    content: '',
    parts: [tool],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: false
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

async function freshStore() {
  vi.resetModules()
  const pinia = await import('pinia')
  pinia.setActivePinia(pinia.createPinia())
  const mod = await import('@/stores/ai')
  const store = mod.useAiStore()
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.activeRunId = null
  store.runSeq = {}
  store.historyLoaded = true
  store.lastRoundOutcome = 'ok'
  return store
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.deleteHistory.mockResolvedValue({})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('批G · S13：停止后确认卡是「✗ 已取消」，不是「! 已超时」', () => {
  it('挂起中 stop：段落 cancelled + decision=cancel ⇒ 只读态 cancelled、文案「✗ 已取消」', async () => {
    const store = await freshStore()
    store.sessionId = 's-s13'
    store.messages = [userMessage('u-s13', '帮我导出'), cardOnlyHost(toolPart({ toolCallId: 'tc-export' }))]
    store.suspended = true
    store.activeRunId = 'run-s13'

    await store.stop()

    const host = store.messages[1] as ChatMessage
    const part = host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'tc-export')
    expect(part !== undefined && part.kind === 'tool' ? part.status : null).toBe('cancelled')
    expect(part !== undefined && part.kind === 'tool' ? part.decision : null).toBe('cancel')
    expect(part !== undefined && part.kind === 'tool' ? part.requiresAction : true).toBe(false)
    expect(readOnlyStateOf(part as never)).toBe('cancelled')
    expect(cardStatusOf(part as never)).toMatchObject({ key: 'cancelled', label: '✗ 已取消' })
  })

  it('停止标记是消息级事实：迟到的 done{cancelled} 帧不得抹掉「已停止」', async () => {
    const store = await freshStore()
    store.sessionId = 's-s13b'
    store.messages = [userMessage('u-s13b', '帮我导出'), cardOnlyHost(toolPart({ toolCallId: 'tc-export-b' }), 'a-s13b')]
    store.suspended = true
    store.activeRunId = 'run-s13b'

    await store.stop()
    expect((store.messages[1] as ChatMessage).stopped).toBe(true)

    store.handleFrame(
      { type: 'done', runId: 'run-s13b', seq: 30, cancelled: true } as never,
      'a-s13b'
    )

    expect((store.messages[1] as ChatMessage).stopped).toBe(true)
  })
})

describe('批G · S14：「已停止」chip 不依赖正文（卡-only 宿主也要有）', () => {
  it('GF 轮宿主只有表单卡、正文为空：面板仍渲染「已停止」chip', async () => {
    const store = await freshStore()
    store.sessionId = 's-s14'
    store.messages = [
      userMessage('u-s14', '请用一张表单收集我的查询条件'),
      {
        ...cardOnlyHost(
          toolPart({
            toolCallId: 'gf-1',
            name: 'generative_form',
            kind2: 'FRONTEND',
            status: 'cancelled',
            requiresAction: false
          }),
          'a-s14'
        ),
        stopped: true
      }
    ]
    store.suspended = false
    store.lastRoundOutcome = 'cancelled'

    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="msg-stopped-a-s14"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('stop() 在卡-only 宿主上照样打标（hasBody 由工具段成立，不要求正文）', async () => {
    const store = await freshStore()
    store.sessionId = 's-s14b'
    store.messages = [
      userMessage('u-s14b', '请用一张表单收集我的查询条件'),
      cardOnlyHost(
        toolPart({ toolCallId: 'gf-2', name: 'generative_form', kind2: 'FRONTEND' }),
        'a-s14b'
      )
    ]
    store.suspended = true
    store.activeRunId = 'run-s14b'

    await store.stop()

    expect((store.messages[1] as ChatMessage).stopped).toBe(true)
  })
})
