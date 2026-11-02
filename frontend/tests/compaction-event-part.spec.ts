/**
 * B3：压缩事件段（event part）的插入/去重/投影隔离/块分派。
 */
import { describe, expect, it } from 'vitest'
import type { ChatMessage, ToolPart } from '@/types/ai'
import { deriveCopyText, deriveProjections, insertCompactionEvent } from '@/utils/parts'
import { groupParts } from '@/utils/blocks'

function makeMessage(): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    runId: 'run-a',
    content: '',
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
    done: true
  }
}

describe('B3 insertCompactionEvent', () => {
  it('插到锚点工具卡之后，且不参与正文/工具投影/复制', () => {
    const message = makeMessage()

    expect(insertCompactionEvent(message, { eventId: 'run-a:c1', anchorToolCallId: 'c-1', atMs: 10 })).toBe(true)
    expect(message.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'event', 'text'])
    expect(message.parts[2]).toMatchObject({ kind: 'event', eventType: 'compaction', eventId: 'run-a:c1' })

    deriveProjections(message)
    expect(message.content).toBe('前文后文')
    expect(message.toolRuns).toHaveLength(1)
    expect(deriveCopyText(message)).toBe('前文\n\n后文')
  })

  it('无锚点插到 assistant 开头，同轮多事件保持顺序', () => {
    const message = makeMessage()

    insertCompactionEvent(message, { eventId: 'run-a:c1', atMs: 10 })
    insertCompactionEvent(message, { eventId: 'run-a:c2', atMs: 20 })

    expect(message.parts.map((part) => part.kind)).toEqual(['event', 'event', 'text', 'tool', 'text'])
    expect(message.parts.slice(0, 2).map((part) => part.kind === 'event' ? part.eventId : null))
      .toEqual(['run-a:c1', 'run-a:c2'])
  })

  it('同一 eventId 不重复插入（实时/重挂/镜像/历史共用）', () => {
    const message = makeMessage()
    insertCompactionEvent(message, { eventId: 'run-a:c1', anchorToolCallId: 'c-1' })
    const before = message.parts.length

    expect(insertCompactionEvent(message, { eventId: 'run-a:c1', anchorToolCallId: 'c-1' })).toBe(false)
    expect(message.parts).toHaveLength(before)
  })

  it('groupParts 产出独立 event 块（不并入 activity/text）', () => {
    const message = makeMessage()
    insertCompactionEvent(message, { eventId: 'run-a:c1', anchorToolCallId: 'c-1' })

    const blocks = groupParts(message.parts, message.id)

    expect(blocks.map((block) => block.kind)).toEqual(['text', 'activity', 'event', 'text'])
    expect(blocks[2]?.parts[0]).toMatchObject({ kind: 'event', eventId: 'run-a:c1' })
  })
})
