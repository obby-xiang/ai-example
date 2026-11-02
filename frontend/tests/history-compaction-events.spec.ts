/**
 * B3：/history.events 与实时 context 帧对同一 eventId 的恢复/去重（不重复节点）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { insertCompactionEvent } from '@/utils/parts'
import { mirrorStorageKey } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({ history: vi.fn(), runsCurrent: vi.fn(), getContext: vi.fn() }))
vi.mock('@/api/ai', () => ({ aiApi: api }))

function assistant(): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    runId: 'run-a',
    content: '回答',
    parts: [
      { kind: 'text', seg: 0, text: '前文', streaming: false },
      {
        kind: 'tool',
        seg: 1,
        toolCallId: 'c-1',
        name: 'list_tasks',
        kind2: 'BACKEND',
        status: 'succeeded',
        requiresAction: false
      } as ToolPart,
      { kind: 'text', seg: 2, text: '后文', streaming: false }
    ],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: false
  }
}

function eventParts(message: ChatMessage): string[] {
  return message.parts
    .filter((part) => part.kind === 'event')
    .map((part) => part.eventId)
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  installIdentityFixture()
  api.runsCurrent.mockResolvedValue({ found: false })
  api.getContext.mockResolvedValue({ source: 'none' })
})

describe('B3 压缩事件恢复', () => {
  it('history events 按 runId+anchor 插入，重复加载不重复', () => {
    const store = useAiStore()
    store.messages = [assistant()]
    const events = [{ eventId: 'run-a:c1', runId: 'run-a', atMs: 10, anchorToolCallId: 'c-1' }]

    store.applyHistoryCompactionEvents(events)
    store.applyHistoryCompactionEvents(events)

    expect(eventParts(store.messages[0] as ChatMessage)).toEqual(['run-a:c1'])
    expect(store.messages[0]?.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'event', 'text'])
  })

  it('同毫秒按 eventId 数值序号排序（c2<c10，历史与实时一致）', () => {
    const store = useAiStore()
    store.messages = [assistant()]

    // 历史：故意先给 c10 再给 c2
    store.applyHistoryCompactionEvents([
      { eventId: 'run-a:c10', runId: 'run-a', atMs: 10 },
      { eventId: 'run-a:c2', runId: 'run-a', atMs: 10 }
    ])
    expect(eventParts(store.messages[0] as ChatMessage)).toEqual(['run-a:c2', 'run-a:c10'])

    // 实时：乱序到达（c10 先、c2 后）也按数值序号落到 c2 前
    const live = assistant()
    live.id = 'a-2'
    store.messages.push(live)
    insertCompactionEvent(live, { eventId: 'run-a:c10', atMs: 20 })
    insertCompactionEvent(live, { eventId: 'run-a:c2', atMs: 20 })
    expect(eventParts(live)).toEqual(['run-a:c2', 'run-a:c10'])
  })

  it('未知 run 不补节点', () => {
    const store = useAiStore()
    store.messages = [assistant()]

    store.applyHistoryCompactionEvents([{ eventId: 'run-x:c1', runId: 'run-x', atMs: 1 }])

    expect(eventParts(store.messages[0] as ChatMessage)).toEqual([])
  })

  it('实时 context 帧与 history events 共用 eventId 去重', () => {
    const store = useAiStore()
    const message = assistant()
    store.messages = [message]

    store.handleFrame({
      type: 'context',
      seq: 2,
      level: 'L1',
      before: 100,
      after: 90,
      turns: 0,
      lostTokens: 0,
      reason: 'l1_tool_result_clearing',
      est: false,
      eventId: 'run-a:c1',
      committed: true,
      runId: 'run-a',
      atMs: 10,
      anchorToolCallId: 'c-1'
    } as never)

    expect(eventParts(store.messages[0] as ChatMessage)).toEqual(['run-a:c1'])

    store.applyHistoryCompactionEvents([
      { eventId: 'run-a:c1', runId: 'run-a', atMs: 10, anchorToolCallId: 'c-1' }
    ])

    expect(eventParts(store.messages[0] as ChatMessage)).toEqual(['run-a:c1'])
  })
})

describe('B3 事件恢复时序（runId 补写之后）', () => {
  it('历史条目无 runId、镜像补回同一轮 ⇒ 事件按补写后的 runId 落到宿主', async () => {
    sessionStorage.setItem(mirrorStorageKey('sess-ev-order'), JSON.stringify({
      v: 2,
      savedAt: Date.now(),
      messages: [
        {
          id: 'u-m',
          role: 'user',
          content: '问一句',
          parts: [{ kind: 'text', seg: 0, text: '问一句', streaming: false }],
          reasoning: '',
          toolRuns: [],
          pendingCall: null
        },
        {
          id: 'a-m',
          role: 'assistant',
          content: '失败前的正文',
          runId: 'run-order',
          errorCard: { variant: 'generic', code: 'EMPTY_REPLY', title: '本轮出错', guide: '', action: '', detail: '' },
          parts: [{ kind: 'text', seg: 0, text: '失败前的正文', streaming: false }],
          reasoning: '',
          toolRuns: [],
          pendingCall: null
        }
      ]
    }))
    api.history.mockResolvedValue({
      sessionId: 'sess-ev-order',
      count: 2,
      // 历史通道不带 runId（旧 chat:mem 数据）
      messages: [
        { type: 'USER', text: '问一句' },
        { type: 'ASSISTANT', text: '失败前的正文' }
      ],
      events: [{ eventId: 'run-order:c1', runId: 'run-order', atMs: 10 }]
    })
    const store = useAiStore()
    store.sessionId = 'sess-ev-order'

    await store.loadHistory()

    const host = store.messages[store.messages.length - 1] as ChatMessage
    expect(host.runId).toBe('run-order')
    expect(eventParts(host)).toEqual(['run-order:c1'])
  })
})

describe('B3 分页后事件校正', () => {
  function textAssistant(id: string, runId = 'run-a'): ChatMessage {
    return {
      id,
      role: 'assistant',
      runId,
      content: '回答',
      parts: [{ kind: 'text', seg: 0, text: '回答', streaming: false }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: true
    }
  }

  function eventsIn(messages: ChatMessage[], eventId: string): ChatMessage[] {
    return messages.filter((message) => message.parts.some(
      (part) => part.kind === 'event' && part.eventId === eventId
    ))
  }

  it('锚点工具段随旧页到达：事件从粗宿主移到锚点宿主，全局仍唯一', () => {
    const store = useAiStore()
    const first = textAssistant('a-1')
    const second = textAssistant('a-2')
    store.messages = [first, second]

    // 首窗口没有锚点 ⇒ 先落在本 run 最后一条 assistant
    store.applyHistoryCompactionEvents([{ eventId: 'run-a:c1', runId: 'run-a', atMs: 10, anchorToolCallId: 'c1' }])
    expect(eventsIn(store.messages, 'run-a:c1')).toEqual([second])

    // 旧页到达：c1 工具段出现在 first 上 ⇒ 事件校正到 first
    first.parts.push({
      kind: 'tool',
      seg: 1,
      toolCallId: 'c1',
      name: 'list_tasks',
      kind2: 'BACKEND',
      status: 'succeeded',
      requiresAction: false
    })
    store.applyHistoryCompactionEvents([{ eventId: 'run-a:c1', runId: 'run-a', atMs: 10, anchorToolCallId: 'c1' }])

    expect(eventsIn(store.messages, 'run-a:c1')).toEqual([first])
    expect(second.parts.some((part) => part.kind === 'event')).toBe(false)
  })

  it('同消息内锚点晚到：事件从开头移到工具段之后', () => {
    const store = useAiStore()
    const host = textAssistant('a-1')
    store.messages = [host]

    store.applyHistoryCompactionEvents([{ eventId: 'run-a:c1', runId: 'run-a', atMs: 10, anchorToolCallId: 'c1' }])
    expect(host.parts.map((part) => part.kind)).toEqual(['event', 'text'])

    host.parts.push({
      kind: 'tool',
      seg: 2,
      toolCallId: 'c1',
      name: 'list_tasks',
      kind2: 'BACKEND',
      status: 'succeeded',
      requiresAction: false
    })
    store.applyHistoryCompactionEvents([{ eventId: 'run-a:c1', runId: 'run-a', atMs: 10, anchorToolCallId: 'c1' }])

    expect(host.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'event'])
    expect(eventsIn(store.messages, 'run-a:c1')).toEqual([host])
  })
})
