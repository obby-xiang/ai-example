/**
 * 对话时序包 批2（子包②权威翻转）：`AiPanel.vue` 渲染层的回归锁。
 *
 * 这一层的风险不在纯函数（parts-reducer.spec.ts 已覆盖），而在**模板本身**：
 * `v-for="part in m.parts"` 的三类段分派顺序、挂起卡跟随其工具段的位置（③b 合并前的过渡形态）、
 * 以及 N10（复制按钮的显示判据改 parts，不得再读 `m.content`，防"有段渲染但按钮不出现"的发散）。
 *
 * 挂载方式：Element Plus 组件全部用轻量 stub（本用例只关心段序与出现与否，不测视觉）；
 * `onMounted` 的 health/loadHistory 取数用 `@/api/ai` 桩屏蔽。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart, PendingToolCall } from '@/types/ai'
import { useAiStore } from '@/stores/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn()
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
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h', confirmTimeoutSeconds: 120, frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0, sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z' })
})

/** 手搭一条消息（含派生投影：两侧自洽 = deriveProjections 的结果）。 */
function buildMessage(parts: MessagePart[], pendingCall: PendingToolCall | null = null, streaming = false): ChatMessage {
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

async function mountPanel(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('AiPanel 按 parts 渲染（批2 权威翻转）', () => {
  it('text → tool → text 的段序在 DOM 里保持一致（工具夹在正文中间不再沉底）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'text', seg: 0, text: '先说话', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'c1', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: 'ok', requiresAction: false },
        { kind: 'text', seg: 2, text: '后说话', streaming: false }
      ])
    ])
    const rendered = wrapper.text()

    const firstText = rendered.indexOf('先说话')
    const toolCard = rendered.indexOf('列出配置定义')
    const secondText = rendered.indexOf('后说话')
    expect(firstText).toBeGreaterThanOrEqual(0)
    expect(toolCard).toBeGreaterThan(firstText)
    expect(secondText).toBeGreaterThan(toolCard)
    wrapper.unmount()
  })

  it('挂起卡跟随其工具段位置，且每条消息至多一张（单槽 pendingCall）', async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'text', seg: 0, text: '', streaming: false },
          { kind: 'tool', seg: 1, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', timeoutSeconds: 120, requiresAction: true },
          { kind: 'tool', seg: 2, toolCallId: 'c2', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', requiresAction: false }
        ],
        { toolCallId: 'c1', name: 'start_export', kind: 'CONFIRM', args: { taskId: 1 }, rawArgs: '{"taskId":1}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const html = wrapper.html()
    const cards = wrapper.findAll('[data-testid^="pending-card-"]')
    expect(cards).toHaveLength(1)
    expect(cards[0]?.attributes('data-testid')).toBe('pending-card-c1')

    // 位置：在 c1 工具卡之后、c2 工具卡之前（挂起卡不下沉到消息末尾）
    const confirmTool = html.indexOf('启动导出作业')
    const pendingCard = html.indexOf('pending-card-c1')
    const otherTool = html.indexOf('列出任务')
    expect(pendingCard).toBeGreaterThan(confirmTool)
    expect(otherTool).toBeGreaterThan(pendingCard)
    wrapper.unmount()
  })

  it('N10：复制按钮的显示判据取 parts（正文段非空即出按钮），不随 m.content 发散', async () => {
    // 合成态：段里有正文但派生投影未跟上 —— 渲染真值（parts）必须说话
    const inconsistent = buildMessage([{ kind: 'text', seg: 0, text: '段里有正文', streaming: false }])
    inconsistent.content = ''
    const empty = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: false }])
    empty.content = '投影里有正文但段是空的'

    const wrapper = await mountPanel([inconsistent, empty])
    const buttons = wrapper.findAll('[data-testid^="copy-msg-"]')
    expect(buttons).toHaveLength(1)
    expect(buttons[0]?.attributes('data-testid')).toBe('copy-msg-a-1')
    expect(wrapper.text()).toContain('段里有正文')
    wrapper.unmount()
  })

  it('三点闪烁（isTyping）按 parts 判据：流式中且无任何非空 text 段才显示', async () => {
    const typing = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)
    typing.id = 'a-typing'
    const speaking = buildMessage([{ kind: 'text', seg: 0, text: '正在说', streaming: true }], null, true)
    speaking.id = 'a-speaking'

    const wrapper = await mountPanel([typing, speaking])
    expect(wrapper.findAll('.typing')).toHaveLength(1)
    // 工具段已到、正文还没来时三点仍在（与批1 的 content 判据同语义）
    expect(wrapper.text()).toContain('正在说')
    wrapper.unmount()
  })
})

/**
 * 对话时序包 批3③b（D6/D7/D8）：「同一 toolCallId 全局唯一一张卡」渲染面三路锁。
 * 计数口径：pending-card-*（挂起卡）+ 全消息域同 id 工具段数 —— **锁单卡，不锁状态正确**
 * （历史整替路的 succeeded 是 D7(B) 登记的占位失真，R-144 修复前不得以状态断言此路）。
 */
describe('批3③b：一卡全生命周期 —— 重投 / 新宿主 / 历史整替 三路单卡锁', () => {
  /** 带一个已决（unknown）FRONTEND 工具段的旧消息。 */
  function messageWithDecidedTool(): ChatMessage {
    return buildMessage([
      { kind: 'text', seg: 0, text: '上一轮正文', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId: 'r1', name: 'probe.tool', kind2: 'FRONTEND',
        status: 'unknown', args: '{}', result: '本次调用结局未知', reason: null, requiresAction: false
      }
    ])
  }

  it('重投路：已决段重投 frontend_tool_request ⇒ 无挂起卡复活、同 id 仍只有旧卡一张', async () => {
    const store = useAiStore()
    const old = messageWithDecidedTool()
    store.messages = [old]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 2
    }, old.id)
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    const segments = store.messages.flatMap((m) => m.parts.filter((p) => p.kind === 'tool' && p.toolCallId === 'r1'))
    expect(segments).toHaveLength(1)
    wrapper.unmount()
  })

  it('新宿主路：re-attach 回放（挂起语义帧 + 终态帧）⇒ 全消息域仍只有旧卡一张（D6 已决面）', async () => {
    const store = useAiStore()
    store.messages = [messageWithDecidedTool()]
    store.historyLoaded = true
    api.reattachRun.mockImplementation((_runId: string, options: { onFrame: (frame: never) => void }) => {
      // 全量回放现场：request / suspended / 终态帧都到达新宿主
      options.onFrame({
        type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
        timeoutSeconds: 120, runId: 'run-x', seq: 11
      } as never)
      options.onFrame({
        type: 'suspended', runId: 'run-x', seq: 12,
        toolCalls: [{ toolCallId: 'r1', name: 'probe.tool', type: 'function', kind: 'FRONTEND', arguments: '{}', status: 'PENDING', executed: false }]
      } as never)
      options.onFrame({
        type: 'frontend_tool_result', toolCallId: 'r1', name: 'probe.tool',
        ok: true, executed: true, status: 'FRONTEND_RESULT', result: 'ok', runId: 'run-x', seq: 13
      } as never)
      return new Promise(() => {})
    })

    void store.reattachActive('run-x')
    const wrapper = await mountPanel(store.messages)

    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    const segments = store.messages.flatMap((m) => m.parts.filter((p) => p.kind === 'tool' && p.toolCallId === 'r1'))
    expect(segments).toHaveLength(1)
    expect(segments[0]).toMatchObject({ status: 'unknown' })
    wrapper.unmount()
  })

  it('历史整替路（§6(c) 口径）：整替后同 id 单卡、无挂起卡 —— 锁单卡不锁状态', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 1,
      messages: [
        {
          type: 'ASSISTANT',
          text: '历史正文',
          toolCalls: [{ id: 'h1', name: 'probe.tool', arguments: '{}' }],
          toolResponses: [],
          timestamp: '2026-01-01T00:00:00Z'
        }
      ]
    })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0 })

    await store.loadHistory()
    const wrapper = await mountPanel(store.messages)

    // D7(B)：h1 的 succeeded 是载荷缺状态的占位值（R-144 承接）——本锁只数卡数
    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    const segments = store.messages.flatMap((m) => m.parts.filter((p) => p.kind === 'tool' && p.toolCallId === 'h1'))
    expect(segments).toHaveLength(1)
    wrapper.unmount()
  })
})
