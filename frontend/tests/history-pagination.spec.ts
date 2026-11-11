/**
 * #4：transcript 全量历史不再一次渲染 —— 首屏只取最近一页（`offset/limit`），
 * `hasMore` 时消息流内出**单个**「加载更早」入口，向前加载并保持视口不跳。
 *
 * 同时锁住两条不相干的语义：`truncated`（容量裁掉）与 `hasMore`（还能向前取）不互相替代。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { isReactive, markRaw } from 'vue'
import type { AiHistoryMessage, ChatMessage, ToolPart } from '@/types/ai'
import { useAiStore, HISTORY_PAGE_SIZE } from '@/stores/ai'
import { applyLocale } from '@/locales'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  getContext: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  reattachRun: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  deleteHistory: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

/** 一段交替 USER/ASSISTANT 的条目流（偶数下标 USER、奇数下标 ASSISTANT）。 */
function entry(index: number): AiHistoryMessage {
  return index % 2 === 0
    ? { type: 'USER', text: `q-${index}` }
    : { type: 'ASSISTANT', text: `a-${index}` }
}

function pageOf(start: number, size: number, total: number): { messages: AiHistoryMessage[] } {
  return {
    messages: Array.from({ length: Math.max(0, Math.min(size, total - start)) }, (_item, i) => entry(start + i))
  }
}

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
  api.runsCurrent.mockResolvedValue({ found: false })
  api.getContext.mockResolvedValue({ source: 'none' })
  installIdentityFixture()
  applyLocale('zh-CN')
})

describe('分页 · 首屏只取最近一页', () => {
  it(`长历史（500 条）首屏只取最近 ${HISTORY_PAGE_SIZE} 条，并记录窗口位置`, async () => {
    api.history.mockImplementation((_sid: string, page: { offset: number; limit: number }) => {
      if (page.offset === 0) {
        return Promise.resolve({ sessionId: 's', count: HISTORY_PAGE_SIZE, total: 500, messages: pageOf(0, page.limit, 500).messages })
      }
      return Promise.resolve({
        sessionId: 's',
        count: page.limit,
        total: 500,
        hasMore: true,
        messages: pageOf(page.offset, page.limit, 500).messages
      })
    })
    const store = useAiStore()
    store.sessionId = 'sess-page'

    await store.loadHistory()

    // 探测只取 1 条拿 total，随后取最近一页（不再先拖整页 200 再丢弃）
    expect(api.history.mock.calls.map((call) => call[1])).toEqual([
      { offset: 0, limit: 1 },
      { offset: 500 - HISTORY_PAGE_SIZE, limit: HISTORY_PAGE_SIZE }
    ])
    expect(store.messages).toHaveLength(HISTORY_PAGE_SIZE)
    expect(store.messages[0]?.id).toBe(`history-${500 - HISTORY_PAGE_SIZE}`)
    expect(store.historyOffset).toBe(500 - HISTORY_PAGE_SIZE)
    expect(store.historyTotal).toBe(500)
    expect(store.historyHasMore).toBe(true)
  })

  it('短历史（≤ 一页）：探测 1 条 + 取一页，hasMore=false', async () => {
    api.history.mockImplementation((_sid: string, page: { offset: number; limit: number }) => Promise.resolve({
      sessionId: 's',
      count: Math.min(page.limit, 3),
      total: 3,
      messages: [entry(0), entry(1), entry(2)].slice(0, page.limit)
    }))
    const store = useAiStore()
    store.sessionId = 'sess-short'

    await store.loadHistory()

    expect(api.history.mock.calls.map((call) => call[1])).toEqual([
      { offset: 0, limit: 1 },
      { offset: 0, limit: HISTORY_PAGE_SIZE }
    ])
    expect(store.messages).toHaveLength(3)
    expect(store.historyOffset).toBe(0)
    expect(store.historyHasMore).toBe(false)
  })

  it('旧后端忽略 limit：按不支持分页处理（不再二次取/不撞 id/无入口）', async () => {
    const all = Array.from({ length: 300 }, (_item, index) => entry(index))
    api.history.mockResolvedValue({ sessionId: 's', count: all.length, messages: all })
    const store = useAiStore()
    store.sessionId = 'sess-legacy'

    await store.loadHistory()

    expect(api.history).toHaveBeenCalledTimes(1)
    expect(store.messages).toHaveLength(300)
    expect(store.messages[0]?.id).toBe('history-0')
    expect(new Set(store.messages.map((message) => message.id)).size).toBe(300)
    expect(store.historyOffset).toBe(0)
    expect(store.historyHasMore).toBe(false)
  })

  it('容量裁剪（truncated）与 hasMore 分开：裁掉 ≠ 还能向前取（不再设 store 字段）', async () => {
    api.history.mockResolvedValue({ sessionId: 's', count: 2, total: 2, truncated: true, messages: [entry(0), entry(1)] })
    const store = useAiStore()
    store.sessionId = 'sess-trunc'

    await store.loadHistory()

    expect(store.messages).toHaveLength(2)
    expect(store.historyHasMore).toBe(false)
    expect(store).not.toHaveProperty('historyTruncated')
  })
})

describe('分页 · 加载更早', () => {
  it('前插一页、窗口前移、id 不撞车，且不替换既有消息', async () => {
    api.history.mockImplementation((_sid: string, page: { offset: number; limit: number }) => Promise.resolve({
      sessionId: 's',
      count: page.limit,
      total: 5,
      hasMore: page.offset > 0,
      messages: pageOf(page.offset, page.limit, 5).messages
    }))
    const store = useAiStore()
    store.sessionId = 'sess-earlier'

    // 手搭窗口：最近 3 条（offset=2）
    store.historyOffset = 2
    store.historyTotal = 5
    store.historyHasMore = true
    store.messages = [entry(2), entry(3), entry(4)].map((item, i) => ({
      id: `history-${2 + i}`,
      role: (item.type === 'USER' ? 'user' : 'assistant') as ChatMessage['role'],
      content: item.text ?? '',
      parts: [{ kind: 'text', seg: 0, text: item.text ?? '', streaming: false }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: true
    }))

    await store.loadEarlierHistory()

    expect(api.history).toHaveBeenLastCalledWith('sess-earlier', { offset: 0, limit: 2 })
    expect(store.messages).toHaveLength(5)
    const ids = store.messages.map((message) => message.id)
    expect(new Set(ids).size).toBe(5)
    expect(ids).toEqual(['history-0', 'history-1', 'history-2', 'history-3', 'history-4'])
    expect(store.historyOffset).toBe(0)
    expect(store.historyHasMore).toBe(false)
  })

  it('跨页边界两侧都是 assistant ⇒ 按同轮合并（不拆成两个气泡）', () => {
    const store = useAiStore()
    const head: ChatMessage = {
      id: 'history-9',
      role: 'assistant',
      content: 'A2',
      parts: [{ kind: 'text', seg: 0, text: 'A2', streaming: false }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: true,
      runId: 'run-2'
    }
    store.messages = [head]
    const older: ChatMessage = { ...head, id: 'history-8', content: 'A1', runId: 'run-1', parts: [{ kind: 'text', seg: 0, text: 'A1', streaming: false }] }

    store.prependHistoryMessages([older])

    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]?.id).toBe('history-9')
    expect(store.messages[0]?.content).toBe('A1A2')
    expect(store.messages[0]?.runId).toBe('run-2')
  })
})

describe('分页 · 加载更早的跨页配对接线', () => {
  it('页边界切开 call 与 TOOL：loadEarlierHistory 用窗口原始条目回填结果', async () => {
    const entries: AiHistoryMessage[] = [
      { type: 'USER', text: 'q-0' },
      { type: 'ASSISTANT', text: '', toolCalls: [{ id: 'c1', name: 'list_tasks', arguments: '{}' }] },
      { type: 'TOOL', text: '', toolResponses: [{ id: 'c1', name: 'list_tasks', responseData: '{"ok":1}' }] },
      { type: 'ASSISTANT', text: 'a-3' }
    ]
    api.history.mockImplementation((_sid: string, page: { offset: number; limit: number }) => Promise.resolve({
      sessionId: 's',
      count: page.limit,
      total: 4,
      hasMore: page.offset > 0,
      messages: entries.slice(page.offset, page.offset + page.limit)
    }))
    const store = useAiStore()
    store.sessionId = 'sess-cross-page'

    // 首窗口（最近 2 条）：TOOL(c1) 的 call 在更早页 —— 孤儿响应不可按名字错配
    store.historyOffset = 2
    store.historyTotal = 4
    store.historyHasMore = true
    store.historyWindowEntries = markRaw(entries.slice(2))
    // 内部配对窗口不进响应式代理（长会话整份 transcript 不建深度代理）
    expect(isReactive(store.historyWindowEntries)).toBe(false)
    expect(isReactive(store.historyWindowEntries[0] as object)).toBe(false)
    store.messages = store.buildHistoryMessages(entries.slice(2), 2)
    expect(store.messages[0]?.parts.some((part) => part.kind === 'tool')).toBe(false)

    await store.loadEarlierHistory()

    const tool = store.messages
      .flatMap((message) => message.parts)
      .find((part): part is ToolPart => part.kind === 'tool' && part.toolCallId === 'c1')
    expect(tool?.result).toBe('{"ok":1}')
    // 无重复：4 条原始条目对应 2 条展示消息（USER + 跨页合并后的 ASSISTANT）
    expect(store.messages).toHaveLength(2)
    expect(store.historyOffset).toBe(0)
    expect(store.historyHasMore).toBe(false)
    expect(store.historyWindowEntries).toHaveLength(4)
  })
})

describe('分页 · 跨页 TOOL 配对', () => {
  function callEntry(id: string, name = 'list_tasks'): AiHistoryMessage {
    return { type: 'ASSISTANT', text: '', toolCalls: [{ id, name, arguments: '{}' }] }
  }

  function toolEntry(id: string, payload: string, name = 'list_tasks'): AiHistoryMessage {
    return { type: 'TOOL', text: '', toolResponses: [{ id, name, responseData: payload }] }
  }

  function toolPartOf(message: ChatMessage | undefined): ToolPart | undefined {
    const part = message?.parts.find((item) => item.kind === 'tool')
    return part && part.kind === 'tool' ? part : undefined
  }

  it('页边界切开 call 与 TOOL：加载更早按 id 从相邻窗口回填结果', () => {
    const store = useAiStore()

    // 旧页只有 call，TOOL 在新页（相邻窗口）
    const built = store.buildHistoryMessages([callEntry('c1')], 0, [toolEntry('c1', '{"ok":1}')])

    expect(toolPartOf(built[0])?.result).toBe('{"ok":1}')
  })

  it('本页同名未配对 call 不吃页外孤儿响应（不按名字错配）', () => {
    const store = useAiStore()

    // TOOL(c1) 的 call 不在本窗口 ⇒ 孤儿响应直接丢弃，不能配给同名的 c2
    const built = store.buildHistoryMessages(
      [callEntry('c2'), toolEntry('c1', '{"wrong":true}')],
      0
    )

    expect(toolPartOf(built[0])?.result ?? null).toBeNull()
  })

  it('相邻窗口的响应不参与本页按名字兜底', () => {
    const store = useAiStore()

    const built = store.buildHistoryMessages([callEntry('c2')], 0, [toolEntry('c1', '{"wrong":true}')])

    expect(toolPartOf(built[0])?.result ?? null).toBeNull()
  })
})
