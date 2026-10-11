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
  frontendToolResult: vi.fn()
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
