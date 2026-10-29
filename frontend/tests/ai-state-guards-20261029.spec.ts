/**
 * 2026-10-29 审计批（M-17 / M-18 / M-19）：AI 对话状态的三条守卫。
 *
 * <h2>本文件为什么单列</h2>
 * 三条都是"跨 await 的轮次身份"缺口，证红方式各自独立（见各用例注释）。放在一个文件里只为
 * 共用同一套 store 装配（`@/api/ai` 桩 + 独立 pinia + 页签锁夹具），不影响逐条 `-t` 运行。
 *
 * <h2>口径</h2>
 * - M-17：历史重载在途时**不得整体替换** `messages`（在途轮的宿主一旦被换掉，后续帧会新建第二条气泡）；
 * - M-18：建卡失败 + 回灌失败时**该卡**的 `suspended` 必须收口，同轮其它待人卡照旧保持挂起；
 * - M-19：start 帧之前就停止 ⇒ 记会话停止意向，拿到 runId（start 帧 / 409 响应体）后补真实 cancel。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import type { AiHistory, AiRunCurrent, ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { channels, FakeBroadcastChannel, FakeLockManager } from './tab-ownership-fixtures'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  getContext: vi.fn(),
  retryModelStep: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

let locks: FakeLockManager

function inboxSnapshot(): AiRunCurrent {
  return {
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  }
}

/** 可外部结算的 Promise（"请求在途"的唯一表达方式）。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** SSE 回调槽（`aiApi.chat` 的第二个入参）。 */
interface FrameSink {
  onFrame: (frame: SseFrame) => void
}

/** 往捕获到的 SSE 回调槽推一帧（未捕获 = 用例装配错误，直接失败）。 */
function pushFrame(sink: FrameSink | null, frame: SseFrame): void {
  if (sink === null) {
    throw new Error('未捕获到 SSE 回调槽：send() 没有走到 aiApi.chat')
  }
  sink.onFrame(frame)
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  channels.clear()
  setActivePinia(createPinia())
  locks = new FakeLockManager()
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(inboxSnapshot())
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
  api.frontendToolResult.mockResolvedValue({})
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('M-17 历史重载的轮次守卫', () => {
  it('M-17 原始：历史在途时开新轮 ⇒ 不以历史整体替换在途 messages', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m17'
    store.historyLoaded = true

    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    const chat = deferred<void>()
    api.chat.mockImplementation(() => chat.promise)

    const loading = store.loadHistory()
    // 历史还没回来，用户已经发出新一轮（start 帧之前也算：开轮即换轮次纪元与连接句柄）
    const sending = store.send('新问题')
    await Promise.resolve()
    const host = store.messages.find((message) => message.role === 'assistant')
    expect(host).toBeDefined()
    const hostId = (host as ChatMessage).id

    history.resolve({
      sessionId: 'sess-m17',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)
    await loading

    // 在途轮的宿主仍在（整轮没被历史顶掉），历史正文没有落进屏上
    expect(store.messages.some((message) => message.id === hostId)).toBe(true)
    expect(store.messages.some((message) => message.content === '旧回答')).toBe(false)
    expect(store.loading).toBe(true)

    chat.resolve()
    await sending
  })

  it('M-17 相邻：没有在途轮时历史照常整体替换（守卫不误伤）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m17-ok'
    store.historyLoaded = true
    api.history.mockResolvedValue({
      sessionId: 'sess-m17-ok',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)

    await store.loadHistory()

    expect(store.messages.some((message) => message.content === '旧回答')).toBe(true)
    expect(store.messages.some((message) => message.role === 'user' && message.content === '旧问题')).toBe(true)
  })

  it('M-17 反向：在途时后端历史为空 ⇒ 不把在途待决段降级成过期', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m17-empty'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('半截正文')
    store.upsertToolPart(host, {
      toolCallId: 'confirm-inflight',
      name: 'start_export',
      kind2: 'CONFIRM',
      status: 'pending',
      args: '{}',
      requiresAction: true
    })

    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    const chat = deferred<void>()
    api.chat.mockImplementation(() => chat.promise)

    const loading = store.loadHistory()
    const sending = store.send('新问题')
    await Promise.resolve()

    history.resolve({ sessionId: 'sess-m17-empty', count: 0, messages: [] } as AiHistory)
    await loading

    // 在途轮的待决段仍是待决（历史为空不得就地"过期降级"一个还在跑的卡）
    expect(host.parts.find((part) => part.kind === 'tool')).toMatchObject({
      status: 'pending',
      requiresAction: true
    })

    chat.resolve()
    await sending
  })
})

describe('M-18 建卡失败 + 回灌失败时的挂起收口', () => {
  it('M-18 原始：表单卡建卡失败且回灌失败 ⇒ 该卡挂起态收口（不锁输入区）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const chat = deferred<void>()
    let frames: FrameSink | null = null
    api.chat.mockImplementation((_body: unknown, handlers: FrameSink) => {
      frames = handlers
      return chat.promise
    })
    const sending = store.send('给我一张表单')
    await Promise.resolve()
    const host = store.messages.find((message) => message.role === 'assistant')
    expect(host).toBeDefined()

    pushFrame(frames, {
      type: 'frontend_tool_request',
      runId: 'run-m18',
      toolCallId: 'gf-broken',
      name: 'generative_form',
      args: '{}'
    } as SseFrame)
    await flushPromises()

    const part = (host as ChatMessage).parts.find(
      (item) => item.kind === 'tool' && item.toolCallId === 'gf-broken'
    )
    expect(part).toMatchObject({ status: 'failed', requiresAction: false })
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    // 无可点卡 ⇒ 不得继续以"挂起等待"锁住输入区
    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull()

    chat.resolve()
    await sending
  })

  it('M-18 相邻：写预览建卡失败 + 回灌失败 ⇒ 同样收口挂起（不锁输入区）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-preview'
    store.historyLoaded = true
    store.activePreview = { toolCallId: 'prev-preview', tool: 'write_sheet_cells' } as never
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const chat = deferred<void>()
    let frames: FrameSink | null = null
    api.chat.mockImplementation((_body: unknown, handlers: FrameSink) => {
      frames = handlers
      return chat.promise
    })
    const sending = store.send('撤销上一次表格编辑')
    await Promise.resolve()
    const host = store.messages.find((message) => message.role === 'assistant') as ChatMessage

    pushFrame(frames, {
      type: 'frontend_tool_request',
      runId: 'run-m18-preview',
      toolCallId: 'undo-blocked',
      name: 'undo_last_sheet_edit',
      args: '{}'
    } as SseFrame)
    await flushPromises()

    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'undo-blocked'))
      .toMatchObject({ status: 'failed', requiresAction: false })
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(store.suspended).toBe(false)

    chat.resolve()
    await sending
  })

  it('M-18 反向：上传交接建卡失败 + 回灌失败 ⇒ 收口挂起', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-upload'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const chat = deferred<void>()
    let frames: FrameSink | null = null
    api.chat.mockImplementation((_body: unknown, handlers: FrameSink) => {
      frames = handlers
      return chat.promise
    })
    const sending = store.send('让我上传文件')
    await Promise.resolve()
    const host = store.messages.find((message) => message.role === 'assistant') as ChatMessage

    pushFrame(frames, {
      type: 'frontend_tool_request',
      runId: 'run-m18-upload',
      toolCallId: 'upload-broken',
      name: 'ui_request_upload',
      args: '{}'
    } as SseFrame)
    await flushPromises()

    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'upload-broken'))
      .toMatchObject({ status: 'failed', requiresAction: false })
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(store.suspended).toBe(false)

    chat.resolve()
    await sending
  })

  it('M-18 相邻：上传 ref 不在本轮快照 ⇒ 不建卡也不留挂起（回灌失败同样收口）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-upload-ref'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const chat = deferred<void>()
    let frames: FrameSink | null = null
    api.chat.mockImplementation((_body: unknown, handlers: FrameSink) => {
      frames = handlers
      return chat.promise
    })
    const sending = store.send('让我上传文件')
    await Promise.resolve()
    const host = store.messages.find((message) => message.role === 'assistant') as ChatMessage

    pushFrame(frames, {
      type: 'frontend_tool_request',
      runId: 'run-m18-upload-ref',
      toolCallId: 'upload-ref',
      name: 'ui_request_upload',
      args: '{"ref":"upload.btn"}'
    } as SseFrame)
    await flushPromises()

    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'upload-ref'))
      .toMatchObject({ status: 'rejected', requiresAction: false })
    expect(store.suspended).toBe(false)

    chat.resolve()
    await sending
  })

  it('M-18 反向：同轮另有确认门在等 ⇒ 失败的那张收口、其它卡与挂起态保留', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-others'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const chat = deferred<void>()
    let frames: FrameSink | null = null
    api.chat.mockImplementation((_body: unknown, handlers: FrameSink) => {
      frames = handlers
      return chat.promise
    })
    const sending = store.send('导出并给我一张表单')
    await Promise.resolve()
    const host = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    const push = (frame: SseFrame): void => pushFrame(frames, frame)

    push({
      type: 'confirm_request',
      runId: 'run-m18-others',
      toolCallId: 'confirm-other',
      name: 'start_export',
      args: '{}'
    } as SseFrame)
    await flushPromises()
    expect(store.suspended).toBe(true)

    push({
      type: 'frontend_tool_request',
      runId: 'run-m18-others',
      toolCallId: 'gf-broken-2',
      name: 'generative_form',
      args: '{}'
    } as SseFrame)
    await flushPromises()

    // 失败的那张已收口，但另一张还在等人 ⇒ 挂起态与那张卡都保留
    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'gf-broken-2'))
      .toMatchObject({ status: 'failed', requiresAction: false })
    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'confirm-other'))
      .toMatchObject({ status: 'pending', requiresAction: true })
    expect(store.suspended).toBe(true)

    chat.resolve()
    await sending
  })
})

describe('M-19 start 帧之前停止的意愿落地', () => {
  it('M-19 原始：start 帧之前停止 ⇒ 拿到 runId 后补一次真实 cancel', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19'
    store.historyLoaded = true

    const chat = deferred<void>()
    let frames: FrameSink | null = null
    api.chat.mockImplementation((_body: unknown, handlers: FrameSink) => {
      frames = handlers
      return chat.promise
    })
    const sending = store.send('跑一个长任务')
    await Promise.resolve()

    // 后端还没发 start（runId 未知）：本地必须立刻收敛，但 cancel 暂时没有对象
    await store.stop()
    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.stopIntent).toBeTruthy()

    pushFrame(frames, {
      type: 'start',
      runId: 'run-late'
    } as SseFrame)
    await flushPromises()

    expect(api.cancel).toHaveBeenCalledTimes(1)
    expect(api.cancel).toHaveBeenCalledWith('run-late')
    expect(store.stoppedRunId).toBe('run-late')
    expect(store.stopIntent).toBeNull()

    chat.resolve()
    await sending
  })

  it('M-19 相邻：停止意向 + 下一条消息撞 409 ⇒ 按响应体 runId 补 cancel，不当成"仍在进行"', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19-409'
    store.historyLoaded = true

    // 停止发生在 start 帧之前（runId 未知）
    store.loading = true
    await store.stop()
    expect(store.stopIntent).toBeTruthy()

    api.chat.mockRejectedValue(new ApiError('busy', 409, 'SESSION_BUSY', { runId: 'run-busy' }))
    const result = await store.send('再发一条')
    await flushPromises()

    expect(result.outcome).toBe('error')
    expect(api.cancel).toHaveBeenCalledTimes(1)
    expect(api.cancel).toHaveBeenCalledWith('run-busy')
    expect(store.stoppedRunId).toBe('run-busy')
    // 不是"上一轮仍在进行中"：忙态入口不登记
    expect(store.busyRun).toBeNull()
    expect(store.stopIntent).toBeNull()
  })

  it('M-19 反向：没有停止意向 ⇒ 409 照旧登记权威忙态且不发 cancel', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19-no-intent'
    store.historyLoaded = true
    api.chat.mockRejectedValue(new ApiError('busy', 409, 'SESSION_BUSY', { runId: 'run-other' }))

    const result = await store.send('新消息')
    await flushPromises()

    expect(result.outcome).toBe('error')
    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.busyRun).toMatchObject({ runId: 'run-other' })
    expect(store.stoppedRunId).toBeNull()
  })

  it('M-19 反向：停止意向过期（超过 TTL）⇒ 不再取消任何后来的轮', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19-stale'
    store.historyLoaded = true
    store.loading = true
    await store.stop()
    store.stopIntent = { hostMessageId: null, at: Date.now() - 10 * 60_000 }

    api.chat.mockRejectedValue(new ApiError('busy', 409, 'SESSION_BUSY', { runId: 'run-new' }))
    await store.send('很久之后的新消息')
    await flushPromises()

    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.busyRun).toMatchObject({ runId: 'run-new' })
    expect(store.stopIntent).toBeNull()
  })

  it('M-19 反向：意向属于上一轮 ⇒ 新轮的 start 帧不被它取消（只留给 409）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19-other-round'
    store.historyLoaded = true

    const firstChat = deferred<void>()
    api.chat.mockImplementationOnce((_body: unknown, _handlers: FrameSink) => firstChat.promise)
    const first = store.send('第一轮')
    await Promise.resolve()
    const firstHost = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    await store.stop()
    expect(store.stopIntent?.hostMessageId).toBe(firstHost.id)
    firstChat.resolve()
    await first

    const secondChat = deferred<void>()
    let secondFrames: FrameSink | null = null
    api.chat.mockImplementationOnce((_body: unknown, handlers: FrameSink) => {
      secondFrames = handlers
      return secondChat.promise
    })
    const second = store.send('第二轮')
    await Promise.resolve()
    pushFrame(secondFrames, {
      type: 'start',
      runId: 'run-second'
    } as SseFrame)
    await flushPromises()

    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.stoppedRunId).toBeNull()
    // 意向没有被这一帧消耗掉（留给下一条消息的 409 判据）
    expect(store.stopIntent).toBeTruthy()

    secondChat.resolve()
    await second
  })
})
