/**
 * 批G（刷新恢复 + 停止收敛）store 层回归锁 —— G2 / G3 / G4 / G6 / S1 / S2 / S3 / S4。
 *
 * <p>
 * 每条用例对应批G 施工前的两件实证报告（刷新 / 停止各一件，属会话内证据、不入库）
 * 里的一处实证缺陷；断言口径按用户裁决：
 * - 在途轮刷新 ⇒ **静默**续收（无横幅、无占位文案、无 runId）；
 * - 在途轮里发送 ⇒ 可输入、**零提示**、不发请求（仓内同源规格：`AiPanel.vue` 的
 *   `onComposerKeydown` 注释「流式中 preventDefault 后静默返回（不发送 / 不停止 / 不提示 / 不换行）」）；
 * - 停止 ⇒ 本地就地收敛（输入立刻解禁）、消息尾「已停止」、半截正文立即入镜像。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'

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

/** 与 store 同源的镜像键前缀（`MIRROR_PREFIX` 导出只为测试可读，生产读点只有 store 自己）。 */
const MIRROR_PREFIX = 'ai-mirror:'

function mirrorOf(sessionId: string): {
  messages: Array<{ id: string; role: string; content: string; parts: unknown[]; stopped?: boolean }>
  inFlight?: boolean
  runSeq?: Record<string, number>
  lastRunId?: string | null
} | null {
  const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
  return raw ? JSON.parse(raw) : null
}

function textMessage(id: string, role: ChatMessage['role'], text: string): ChatMessage {
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

function snapshotOf(overrides: Record<string, unknown> = {}) {
  return {
    found: true,
    runId: 'run-g2',
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 12,
    ...overrides
  }
}

/**
 * 每例一个全新的 store：`resetModules` 之后**同一模块图**里重建 pinia —— 静态 import 的
 * `pinia` 与 reset 后的 store 模块属于不同图，跨图 `setActivePinia` 不生效（会复用上一例的
 * store 实例，造成消息串味）。
 */
async function freshStore() {
  vi.resetModules()
  const pinia = await import('pinia')
  pinia.setActivePinia(pinia.createPinia())
  const mod = await import('@/stores/ai')
  const store = mod.useAiStore()
  // 显式清场：模块级镜像状态（mirrorStarted / mirrorTimer）不随 resetModules 复位，
  // 单靠新 pinia 不足以保证例间隔离 —— 这里把轮次级状态归一回初值。
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.activeRunId = null
  store.runSeq = {}
  store.historyLoaded = false
  store.lastRoundOutcome = 'ok'
  return store
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('批G · G2：在途轮刷新 ⇒ 静默全量重放续收', () => {
  it('RUNNING 快照 + 镜像在途：复用宿主消息、清空其段、lastSeq=0 重放，全程无提示', async () => {
    const store = await freshStore()
    store.sessionId = 's-g2'
    sessionStorage.setItem(MIRROR_PREFIX + 's-g2', JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-g2',
      inFlight: true,
      runSeq: { 'run-g2': 12 },
      messages: [
        { ...textMessage('u-1', 'user', '这个任务怎么导出'), stopped: undefined },
        { ...textMessage('a-1', 'assistant', '已经写了一部分的正文'), streaming: true }
      ]
    }))
    api.history.mockResolvedValue({
      sessionId: 's-g2',
      count: 1,
      messages: [{ type: 'USER', text: '这个任务怎么导出' }]
    })
    api.runsCurrent.mockResolvedValue(snapshotOf())
    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: 's-g2', seq: 1 })
      handlers.onFrame({ type: 'delta', runId, text: '重放正文', seq: 2 })
      return new Promise(() => {})
    })

    await store.loadHistory()

    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-g2')
    // 全量重放：镜像正文本就滞后，增量续收必有缺口
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 0 })

    const assistants = store.messages.filter((message) => message.role === 'assistant')
    expect(assistants).toHaveLength(1) // 不新起气泡
    expect(assistants[0]?.id).toBe('a-1') // 续写镜像里那条宿主
    expect(assistants[0]?.content).toBe('重放正文') // 清空后重放，不双拼

    // 用户看不到任何"重挂"概念
    expect(store.notice).toBeNull()
    expect(JSON.stringify(store.messages)).not.toContain('run-g2')
    expect(JSON.stringify(store.messages)).not.toContain('重挂')
    expect(store.loading).toBe(true)
    expect(store.suspended).toBe(false)
  })

  it('没有宿主消息时可新建一条宿主（唯一允许的新消息形态），仍无任何提示文案', async () => {
    const store = await freshStore()
    store.sessionId = 's-g2b'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshotOf({ runId: 'run-g2b' }))
    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'delta', runId, text: '第一段', seq: 2 })
      return new Promise(() => {})
    })

    void store.restoreSuspendedSnapshot()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()

    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]?.role).toBe('assistant')
    expect(store.messages[0]?.content).toBe('第一段')
    expect(store.notice).toBeNull()
  })
})

describe('批G · G3：历史 + 未被历史覆盖的镜像在途轮合并', () => {
  it('历史只有 USER（开轮即写入记忆）时，镜像里在途的助手正文与卡不被整替抹掉', async () => {
    const store = await freshStore()
    store.sessionId = 's-g3'
    sessionStorage.setItem(MIRROR_PREFIX + 's-g3', JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-g3',
      inFlight: true,
      runSeq: {},
      messages: [
        { ...textMessage('u-1', 'user', '统计行数'), stopped: undefined },
        {
          id: 'a-1',
          role: 'assistant',
          content: '正在统计',
          parts: [
            { kind: 'text', seg: 0, text: '正在统计', streaming: true },
            { kind: 'tool', seg: 1, toolCallId: 'tc-9', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded', args: '{}', requiresAction: false }
          ],
          reasoning: '',
          toolRuns: [],
          pendingCall: null
        }
      ]
    }))
    api.history.mockResolvedValue({
      sessionId: 's-g3',
      count: 1,
      messages: [{ type: 'USER', text: '统计行数' }]
    })
    // 后端已无在途轮（例如轮次刚被后端收尾）：合并结果仍要保住镜像那一轮，随后封口
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0 })

    await store.loadHistory()

    expect(store.messages.map((message) => message.role)).toEqual(['user', 'assistant'])
    const assistant = store.messages[1] as ChatMessage
    expect(assistant.content).toContain('正在统计')
    expect(assistant.parts.some((part) => part.kind === 'tool' && part.toolCallId === 'tc-9')).toBe(true)
    expect(assistant.done).toBe(true) // 已无在途轮 ⇒ 封口，光标不停在流
    expect(store.loading).toBe(false)
  })

  it('历史已收下这一轮回答 ⇒ 不补镜像（防双份）', async () => {
    const store = await freshStore()
    store.sessionId = 's-g3b'
    sessionStorage.setItem(MIRROR_PREFIX + 's-g3b', JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-g3b',
      inFlight: true,
      runSeq: {},
      messages: [
        { ...textMessage('u-1', 'user', '问题'), stopped: undefined },
        { ...textMessage('a-1', 'assistant', '镜像版本的回答') }
      ]
    }))
    api.history.mockResolvedValue({
      sessionId: 's-g3b',
      count: 2,
      messages: [
        { type: 'USER', text: '问题' },
        { type: 'ASSISTANT', text: '历史版本的回答' }
      ]
    })

    await store.loadHistory()

    expect(store.messages.map((message) => message.role)).toEqual(['user', 'assistant'])
    expect(store.messages[1]?.content).toBe('历史版本的回答')
    expect(store.messages.some((message) => message.content.includes('镜像版本'))).toBe(false)
  })
})

describe('批G · G4：镜像节流（leading + trailing）与离开页面立即落盘', () => {
  it('连续出帧不再饿死写盘：首帧立即写、窗口内合并、窗口末尾补写', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000_000)
    const store = await freshStore()
    store.sessionId = 's-g4'
    store.startMirror()

    store.messages.push(textMessage('u-1', 'user', '问题'))
    await Promise.resolve()
    expect(mirrorOf('s-g4')?.messages).toHaveLength(1) // leading：立即落盘

    vi.setSystemTime(1_000_000_100)
    store.messages.push(textMessage('a-1', 'assistant', '增量'))
    await Promise.resolve()
    expect(mirrorOf('s-g4')?.messages).toHaveLength(1) // 窗口内：先不写

    vi.advanceTimersByTime(200) // 10_100 + 200 = 窗口末尾（300ms）
    expect(mirrorOf('s-g4')?.messages).toHaveLength(2) // trailing：补写最新快照
  })

  it('pagehide 立即 flush（关页/切后台那一刻的正文不丢），runSeq 一并入镜像', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(2_000_000_000)
    const store = await freshStore()
    store.sessionId = 's-g4b'
    store.startMirror()

    store.messages.push(textMessage('u-1', 'user', '问题'))
    await Promise.resolve()
    vi.setSystemTime(2_000_000_050)
    store.runSeq = { 'run-g4': 7 }
    store.messages.push(textMessage('a-1', 'assistant', '还没写进去的半截'))
    await Promise.resolve()
    expect(mirrorOf('s-g4b')?.messages).toHaveLength(1)

    window.dispatchEvent(new Event('pagehide'))

    const mirror = mirrorOf('s-g4b')
    expect(mirror?.messages).toHaveLength(2)
    expect(mirror?.runSeq).toMatchObject({ 'run-g4': 7 })
  })
})

describe('批G · G6：在途轮发送零响应 / 409 乐观气泡回收', () => {
  it('loading 中 send：不发请求、不写任何提示（零提示），草稿由调用方保留', async () => {
    const store = await freshStore()
    store.sessionId = 's-g6'
    store.loading = true

    const result = await store.send('这条不该发出去')

    expect(result).toEqual({ outcome: 'not-sent', userMessageId: null })
    expect(api.chat).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()
    expect(store.messages).toHaveLength(0)
  })

  it('409 SESSION_BUSY：撤回乐观用户气泡与空气泡，提示不含 runId / 重挂等实现词', async () => {
    const store = await freshStore()
    store.sessionId = 's-g6b'
    // 用同一模块图里的 ApiError（本 spec 逐例 resetModules：跨图 instanceof 不成立）
    const { ApiError: FreshApiError } = await import('@/api/http')
    api.chat.mockRejectedValue(new FreshApiError('该会话已有一轮对话在进行中', 409, 'SESSION_BUSY', {
      code: 'SESSION_BUSY',
      runId: 'run-busy',
      reattach: '/api/ai/events/run-busy'
    }))

    const result = await store.send('再发一条')

    expect(result.outcome).toBe('error')
    expect(store.messages).toHaveLength(0) // 幻影双份的根：旧的会留下用户气泡 + 假助手气泡
    expect(store.notice ?? '').not.toContain('run-busy')
    expect(store.notice ?? '').not.toContain('重挂')
    expect(store.notice ?? '').not.toContain('runId')
    expect(store.notice).toBe('这条消息没有发出去，请稍后再试。')
  })
})

describe('批G · S1：停止即本地收敛（HITL 挂起不再锁死）', () => {
  it('确认卡挂起中 stop：段转 cancelled、挂起投影清空、输入立刻可发', async () => {
    const store = await freshStore()
    store.sessionId = 's-s1'
    store.historyLoaded = true
    const message: ChatMessage = {
      id: 'a-s1',
      role: 'assistant',
      content: '请确认',
      parts: [
        { kind: 'text', seg: 0, text: '请确认', streaming: false },
        {
          kind: 'tool', seg: 1, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM',
          status: 'pending', args: '{"taskId":1}', requiresAction: true
        }
      ],
      reasoning: '',
      toolRuns: [],
      pendingCall: { toolCallId: 'c1', name: 'start_export', kind: 'CONFIRM', args: { taskId: 1 }, rawArgs: '{"taskId":1}', status: 'pending' },
      streaming: false,
      done: false
    }
    store.messages = [message]
    store.suspended = true
    store.activeRunId = 'run-s1'

    await store.stop()

    const part = message.parts.find((item) => item.kind === 'tool')
    expect(part).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(store.pendingToolCall).toBeNull()
    expect(store.pendingConfirms).toHaveLength(0)
    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.canSend).toBe(true) // 输入框解禁 = 会话不再锁死
    expect(api.cancel).toHaveBeenCalledWith('run-s1')
  })

  it('执行中的工具段（running）同样收敛为 cancelled（不再永久「调用中…」）', async () => {
    const store = await freshStore()
    store.sessionId = 's-s1b'
    const message: ChatMessage = {
      id: 'a-s1b',
      role: 'assistant',
      content: '查一下',
      parts: [
        { kind: 'text', seg: 0, text: '查一下', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'c2', name: 'get_row_count', kind2: 'BACKEND', status: 'running', args: '{}', requiresAction: false }
      ],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: true,
      done: false
    }
    store.messages = [message]
    store.loading = true
    store.activeRunId = 'run-s1b'
    api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })

    await store.stop()

    expect(message.parts.find((item) => item.kind === 'tool')).toMatchObject({ status: 'cancelled' })
    expect(message.streaming).toBe(false)
    expect(message.done).toBe(true)
  })
})

describe('批G · S2：停止防重（stopping 态 + 幂等）', () => {
  it('停止在途连点两次只发一次 cancel，stopping 复位后再次停止需要新一轮', async () => {
    const store = await freshStore()
    store.sessionId = 's-s2'
    store.loading = true
    store.activeRunId = 'run-s2'
    const deferred: { resolve: ((value: unknown) => void) | null } = { resolve: null }
    api.cancel.mockImplementation(() => new Promise((resolve) => {
      deferred.resolve = resolve
    }))

    const first = store.stop()
    expect(store.stopping).toBe(true)

    await store.stop() // 在途重复点击：直接返回
    expect(api.cancel).toHaveBeenCalledTimes(1)

    deferred.resolve?.({ cancelled: true, alreadyCancelled: false })
    await first
    expect(store.stopping).toBe(false)

    // 幂等：没有在途轮/挂起时再停是空操作
    await store.stop()
    expect(api.cancel).toHaveBeenCalledTimes(1)
  })
})

describe('批G · S3/S4：停止可见 + 半截正文立即入镜像', () => {
  it('stop 立刻写镜像（stopped 标记 + 半截正文 + inFlight=false），刷新后仍可见', async () => {
    const store = await freshStore()
    store.sessionId = 's-s4'
    store.historyLoaded = true
    const message: ChatMessage = {
      id: 'a-s4',
      role: 'assistant',
      content: '半截正文到',
      parts: [{ kind: 'text', seg: 0, text: '半截正文到', streaming: true }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: true,
      done: false
    }
    store.messages = [textMessage('u-s4', 'user', '讲一下配置'), { ...message, done: false, streaming: true }]
    store.loading = true
    store.activeRunId = 'run-s4'

    await store.stop()

    expect(store.messages[1]?.stopped).toBe(true)
    expect(store.messages[1]?.done).toBe(true)
    const mirror = mirrorOf('s-s4')
    expect(mirror?.inFlight).toBe(false)
    expect(mirror?.messages[1]?.stopped).toBe(true)
    expect(mirror?.messages[1]?.content).toContain('半截正文到')

    // 刷新：历史为空（后端尚未落记忆）也照样看得见「已停止」的半截正文
    const reloaded = await freshStore()
    reloaded.sessionId = 's-s4'
    await reloaded.loadHistory()
    const assistant = reloaded.messages.find((item) => item.role === 'assistant')
    expect(assistant?.content).toContain('半截正文到')
    expect(assistant?.stopped).toBe(true)
    expect(assistant?.done).toBe(true)
  })

  it('后端 history 的 stopped 标记透到消息上（S4 后端半截正文）', async () => {
    const store = await freshStore()
    store.sessionId = 's-s4b'
    api.history.mockResolvedValue({
      sessionId: 's-s4b',
      count: 2,
      messages: [
        { type: 'USER', text: '讲一下配置' },
        { type: 'ASSISTANT', text: '半截正文', stopped: true }
      ]
    })

    await store.loadHistory()

    const assistant = store.messages.find((item) => item.role === 'assistant')
    expect(assistant?.stopped).toBe(true)
  })
})
