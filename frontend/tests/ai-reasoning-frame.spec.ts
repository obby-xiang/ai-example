/**
 * <b>L-91：思考链（reasoning）通路的前端三面锁</b> —— 帧解析 / store 承接 / 面板渲染。
 *
 * <p>背景：上游 `deepseek-flash` 一直返回 `reasoning_content`，但后端此前没有任何出口，
 * E2E-S15（`e2e/10-srp.spec.ts`）因此恒红。L-91 起后端用**既有帧型 + additive 可选键**透传
 * （`delta{kind:'reasoning'}` + `message_start/message_end{kind:'reasoning'}`，帧类型集仍是 16），
 * 本文件锁住前端这一侧：解析要收、store 要分流、面板要渲染成独立可折叠块且不混入正文。
 *
 * <p>三节各自的可红点：
 * <ol>
 * <li><b>解析</b>：`toSseFrame` 对 `delta{kind:'reasoning'}` 放行并保留 `kind`；</li>
 * <li><b>store</b>：思考 delta 进 `reasoning` 投影与 reasoning 段，<b>不</b>进 `content` 投影；
 *     正文 delta 的旧行为（无 kind 键）一字不动；</li>
 * <li><b>渲染</b>：思考块有 `reasoning-toggle-*` / `reasoning-body-*` / `reasoning-scroll-*`
 *     （S-15 的判据 testid），默认折叠、点击展开，正文 `.md-body` 不含思考文本。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { toSseFrame } from '@/types/sse'

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
  'el-tag': { template: '<span class="tag"><slot /></span>' }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.health.mockResolvedValue({ available: false, code: 'AI_UNAVAILABLE', checkedAt: '2026-01-01T00:00:00Z' })
  api.history.mockResolvedValue({ sessionId: 's-1', messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false })
})

// ── ① 帧解析 ───────────────────────────────────────────────────────────────

describe('L-91 帧解析：delta 的 kind 位', () => {
  it('思考 delta 被收下且 kind 保留（不被白名单静默丢弃）', () => {
    const frame = toSseFrame({ type: 'delta', text: '先想一步', kind: 'reasoning', seq: 3 })

    expect(frame).not.toBeNull()
    expect(frame).toMatchObject({ type: 'delta', text: '先想一步', kind: 'reasoning', seq: 3 })
  })

  it('正文 delta 没有 kind 键（旧帧形态）照旧收下', () => {
    const frame = toSseFrame({ type: 'delta', text: '答案' })

    expect(frame).toMatchObject({ type: 'delta', text: '答案' })
    expect((frame as { kind?: string }).kind).toBeUndefined()
  })

  it('思考段边界帧（message_start/message_end, kind=reasoning）在白名单内', () => {
    expect(toSseFrame({ type: 'message_start', messageId: 'm', kind: 'reasoning' })).toMatchObject({
      type: 'message_start',
      kind: 'reasoning'
    })
    expect(toSseFrame({ type: 'message_end', messageId: 'm', kind: 'reasoning', chars: 4 })).toMatchObject({
      type: 'message_end',
      kind: 'reasoning',
      chars: 4
    })
  })

  it('同一帧型集不变：reasoning 仍不是白名单帧型（只做旧流兜底）', () => {
    // 后端 L-91 不再发 type:'reasoning'；旧桩/旧归档回放仍可被解析（前向兼容）
    expect(toSseFrame({ type: 'reasoning', text: '旧形态' })).toMatchObject({ type: 'reasoning' })
  })
})

// ── ② store 承接 ───────────────────────────────────────────────────────────

describe('L-91 store 承接：思考段与正文段分流', () => {
  it('思考 delta 进 reasoning 段与 reasoning 投影，正文 delta 进正文段与 content 投影', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'message_start', messageId: 'm-1', kind: 'reasoning', seq: 1 })
    store.handleFrame({ type: 'delta', text: '先想', kind: 'reasoning', messageId: 'm-1', seq: 2 })
    store.handleFrame({ type: 'delta', text: '一步', kind: 'reasoning', messageId: 'm-1', seq: 3 })
    store.handleFrame({ type: 'message_end', messageId: 'm-1', kind: 'reasoning', chars: 4, seq: 4 })
    store.handleFrame({ type: 'message_start', messageId: 'm-1', kind: 'text', seq: 5 })
    store.handleFrame({ type: 'delta', text: '答案是 2', messageId: 'm-1', seq: 6 })
    store.handleFrame({ type: 'message_end', messageId: 'm-1', kind: 'text', chars: 5, seq: 7 })

    // 段序：工厂空文本段(0) → 思考段(1) → 正文段(2)
    expect(assistant.parts.filter((part) => part.kind !== 'text' || part.text).map((part) => part.kind))
      .toEqual(['reasoning', 'text'])
    const reasoningPart = assistant.parts.find((part) => part.kind === 'reasoning')
    expect(reasoningPart).toMatchObject({ kind: 'reasoning', text: '先想一步', streaming: false })

    // 投影：思考与正文互不混入（deriveProjections 与锁步累加同源）
    expect(assistant.reasoning).toBe('先想一步')
    expect(assistant.content).toBe('答案是 2')
    expect(assistant.content).not.toContain('先想')
    expect(assistant.reasoning).not.toContain('答案')
  })

  it('没有 thinking 的轮次（旧后端/旧桩）：reasoning 恒空 ⇒ 面板不出折叠块', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'message_start', messageId: 'm-2', kind: 'text', seq: 1 })
    store.handleFrame({ type: 'delta', text: '只有正文', messageId: 'm-2', seq: 2 })
    store.handleFrame({ type: 'message_end', messageId: 'm-2', kind: 'text', chars: 4, seq: 3 })

    expect(assistant.reasoning).toBe('')
    expect(assistant.parts.some((part) => part.kind === 'reasoning')).toBe(false)
  })

  it('思考与正文交错时不串写（思考→正文→再思考 = 三个独立段）', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'delta', text: '想', kind: 'reasoning', seq: 1 })
    store.handleFrame({ type: 'delta', text: '答', seq: 2 })
    store.handleFrame({ type: 'delta', text: '再想', kind: 'reasoning', seq: 3 })

    expect(assistant.parts.filter((part) => part.kind === 'reasoning').map((part) => part.text))
      .toEqual(['想', '再想'])
    expect(assistant.reasoning).toBe('想再想')
    expect(assistant.content).toBe('答')
  })
})

// ── ③ 面板渲染 ─────────────────────────────────────────────────────────────

/** 手搭一条含思考段的消息（parts 是唯一真值，投影按 deriveProjections 口径手填一致值）。 */
function messageWithReasoning(reasoning: string, text: string): ChatMessage {
  const parts: MessagePart[] = [
    { kind: 'text', seg: 0, text: '', streaming: false },
    { kind: 'reasoning', seg: 1, text: reasoning, streaming: false },
    { kind: 'text', seg: 2, text, streaming: false }
  ]
  return {
    id: 'a-l91',
    role: 'assistant',
    content: text,
    parts,
    reasoning,
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

/** 折叠态判定：展开体是 `v-show`（DOM 恒在、只切 `display`）—— jsdom 下按内联样式判定最稳。 */
function hidden(node: ReturnType<ReturnType<typeof mount>['find']>): boolean {
  return (node.attributes('style') ?? '').includes('display: none')
}

/** 思考块展开体（每次重新取：展开会重建块子树，缓存节点会拿到已摘除的元素）。 */
function bodyOf(wrapper: ReturnType<typeof mount>, blockId: string) {
  return wrapper.find(`[data-testid="reasoning-body-${blockId}"]`)
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

describe('L-91 渲染：思考块独立成框、可折叠、不混入正文', () => {
  it('思考块有 S-15 的三个 testid，默认折叠，点击展开后可见', async () => {
    const wrapper = await mountPanel([messageWithReasoning('先想一步再回答', '答案是 2')])

    const toggles = wrapper.findAll('[data-testid^="reasoning-toggle-"]')
    expect(toggles).toHaveLength(1)
    const blockId = toggles[0]!.attributes('data-testid')!.replace('reasoning-toggle-', '')

    const body = bodyOf(wrapper, blockId)
    const scroll = wrapper.find(`[data-testid="reasoning-scroll-${blockId}"]`)
    expect(body.exists()).toBe(true)
    expect(scroll.text()).toBe('先想一步再回答')
    expect(hidden(body)).toBe(true)

    // 展开态由 store 的 expandedBlocks 驱动（三态 D-C6）；重新取节点 —— 展开会触发块子树重渲染
    await toggles[0]!.trigger('click')
    expect(hidden(bodyOf(wrapper, blockId))).toBe(false)
    await toggles[0]!.trigger('click')
    expect(hidden(bodyOf(wrapper, blockId))).toBe(true)

    wrapper.unmount()
  })

  it('思考文本只出现在 reasoning-scroll 内，正文 md-body 不含它（S-15 ③ 的渲染面）', async () => {
    const wrapper = await mountPanel([messageWithReasoning('先想一步再回答', '答案是 2')])

    const blockId = wrapper
      .find('[data-testid^="reasoning-toggle-"]')
      .attributes('data-testid')!
      .replace('reasoning-toggle-', '')
    const reasoningText = wrapper.find(`[data-testid="reasoning-scroll-${blockId}"]`).text()

    const bodies = wrapper.findAll('.md-body')
    expect(bodies.length).toBeGreaterThan(0)
    for (const body of bodies) {
      expect(body.text()).not.toContain(reasoningText)
    }

    wrapper.unmount()
  })

  it('无思考段的消息不渲染任何思考块（有帧才有块）', async () => {
    const message = messageWithReasoning('', '只有正文')
    const wrapper = await mountPanel([message])

    expect(wrapper.findAll('[data-testid^="reasoning-toggle-"]')).toHaveLength(0)
    wrapper.unmount()
  })
})
