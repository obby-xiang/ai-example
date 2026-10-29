/**
 * 2026-10-29 审计批（M-17 / M-18 / M-19）—— 返修 R1：**全部走真实 SSE 传输与帧门**。
 *
 * <h2>R1 变更（reviewer C1 / C2 / I5 / I6）</h2>
 * - 不再用"永不 resolve 的 chat 桩 + 手动 pushFrame"：那种桩把"连接是否被 abort"整条事实抹掉，
 *   而 C2 修的正是"停止 abort 掉 /chat ⇒ start 帧到不了 ⇒ 补 cancel 不可达"；
 * - 现在用 `tests/ai-fake-transport.ts`：忠于 `api/sse.ts` 的三条语义 —— abort 后调
 *   `onClose({terminal:false})` 并 resolve、abort 后不再投帧、帧走 store 真实的 `onFrame`；
 * - C1：M-18 的"建卡失败 + 回灌"必须**先提交**（`parkedAtDecision` 才能为 true），成功走既有
 *   `reconnectSuspendedRun`（断言 `aiApi.reattachRun` 真被调用并能在其上收尾），失败才收口挂起；
 * - C2：`stop()` 在 runId 未知时**不得 abort** 这条 /chat 传输，start 到达后补真实 cancel；
 * - I5：409 不再"猜着取消"（无法证明归属 ⇒ 会误取消另一页签的新轮）；停止意向只在
 *   自身 start 到达（补 cancel）/ 自身传输收尾无 start / 新轮 start / 过期 / 换会话时清理。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import type { AiHistory, AiRunCurrent, ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import type { SseFrameHandlers } from '@/api/sse'
import { channels, FakeBroadcastChannel, FakeLockManager } from './tab-ownership-fixtures'
import { createFakeChat, type FakeChat, type FakeTransport } from './ai-fake-transport'

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
let fakeChat: FakeChat

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

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** 起一轮（拿真实传输句柄与宿主），帧一律经 `transport.emit` 投递。 */
async function startRound(
  store: ReturnType<typeof useAiStore>,
  text = '开始'
): Promise<{ sending: Promise<unknown>; transport: FakeTransport; host: ChatMessage }> {
  const sending = store.send(text)
  await Promise.resolve()
  const transport = fakeChat.last()
  const host = store.messages.find((message) => message.role === 'assistant')
  if (!host) {
    throw new Error('send() 没有建出助手宿主')
  }
  return { sending, transport, host }
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
  fakeChat = createFakeChat()
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(inboxSnapshot())
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(fakeChat.impl)
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

    const loading = store.loadHistory()
    const { sending, transport, host } = await startRound(store, '新问题')

    history.resolve({
      sessionId: 'sess-m17',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)
    await loading

    expect(store.messages.some((message) => message.id === host.id)).toBe(true)
    expect(store.messages.some((message) => message.content === '旧回答')).toBe(false)
    expect(store.loading).toBe(true)

    transport.close({ terminal: true })
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

    const loading = store.loadHistory()
    const { sending, transport } = await startRound(store, '新问题')

    history.resolve({ sessionId: 'sess-m17-empty', count: 0, messages: [] } as AiHistory)
    await loading

    expect(host.parts.find((part) => part.kind === 'tool')).toMatchObject({
      status: 'pending',
      requiresAction: true
    })

    transport.close({ terminal: true })
    await sending
  })
})

describe('M-18 建卡失败 + 回灌（C1：先提交，成功续流）', () => {
  it('C1 原始：回灌成功 ⇒ 走既有 reconnectSuspendedRun（reattach 真被调用）并能续流收尾', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18'
    store.historyLoaded = true
    let reattach: SseFrameHandlers | null = null
    api.reattachRun.mockImplementation((_runId: string, handlers: SseFrameHandlers) => {
      reattach = handlers
      return new Promise(() => {})
    })

    const { sending, transport, host } = await startRound(store, '给我一张表单')
    transport.emit({
      type: 'frontend_tool_request',
      runId: 'run-m18',
      toolCallId: 'gf-broken',
      name: 'generative_form',
      args: '{}'
    } as SseFrame)
    // 挂起即断：服务端在请求帧之后主动收流（真实时序）
    transport.close({ suspended: true })
    await sending
    await flushPromises()

    const part = host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'gf-broken')
    expect(part).toMatchObject({ status: 'failed', requiresAction: false })
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    // ★ C1：回灌成功必须续流（不再因"先清 suspended"而 parkedAtDecision=false 早退）
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-m18')
    expect(reattach).not.toBeNull()

    // 续流到终帧：本轮正常收口（挂起态不会被永久锁住）
    const sink = reattach as unknown as SseFrameHandlers
    sink.onFrame?.({ type: 'done', runId: 'run-m18', seq: 9 } as SseFrame)
    await flushPromises()
    expect(store.suspended).toBe(false)
  })

  it('C1 反向：回灌失败 ⇒ 收口这张卡的挂起态，且不去续流一个已无卡等人的轮', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-fail'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const { sending, transport, host } = await startRound(store, '给我一张表单')
    transport.emit({
      type: 'frontend_tool_request',
      runId: 'run-m18-fail',
      toolCallId: 'gf-broken',
      name: 'generative_form',
      args: '{}'
    } as SseFrame)
    transport.close({ suspended: true })
    await sending
    await flushPromises()

    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'gf-broken'))
      .toMatchObject({ status: 'failed', requiresAction: false })
    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull()
    expect(api.reattachRun).not.toHaveBeenCalled()
  })

  it('C1 反向：同轮另有确认门在等 ⇒ 失败的那张收口、其它卡与挂起态保留', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-others'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const { sending, transport, host } = await startRound(store, '导出并给我一张表单')
    transport.emit({
      type: 'confirm_request',
      runId: 'run-m18-others',
      toolCallId: 'confirm-other',
      name: 'start_export',
      args: '{}'
    } as SseFrame)
    await flushPromises()
    expect(store.suspended).toBe(true)

    transport.emit({
      type: 'frontend_tool_request',
      runId: 'run-m18-others',
      toolCallId: 'gf-broken-2',
      name: 'generative_form',
      args: '{}'
    } as SseFrame)
    await flushPromises()

    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'gf-broken-2'))
      .toMatchObject({ status: 'failed', requiresAction: false })
    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'confirm-other'))
      .toMatchObject({ status: 'pending', requiresAction: true })
    expect(store.suspended).toBe(true)

    transport.close({ suspended: true })
    await sending
  })

  it('C1 相邻：写预览建卡失败 + 回灌失败 ⇒ 同样收口挂起（不锁输入区）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-preview'
    store.historyLoaded = true
    store.activePreview = { toolCallId: 'prev-preview', tool: 'write_sheet_cells' } as never
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const { sending, transport, host } = await startRound(store, '撤销上一次表格编辑')
    transport.emit({
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

    transport.close({ suspended: true })
    await sending
  })

  it('C1 相邻：上传交接建卡失败 + 回灌失败 ⇒ 收口挂起', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-upload'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const { sending, transport, host } = await startRound(store, '让我上传文件')
    transport.emit({
      type: 'frontend_tool_request',
      runId: 'run-m18-upload',
      toolCallId: 'upload-broken',
      name: 'ui_request_upload',
      args: '{}'
    } as SseFrame)
    await flushPromises()

    expect(host.parts.find((item) => item.kind === 'tool' && item.toolCallId === 'upload-broken'))
      .toMatchObject({ status: 'failed', requiresAction: false })
    expect(store.suspended).toBe(false)

    transport.close({ suspended: true })
    await sending
  })

  it('C1 相邻：上传 ref 不在本轮快照 ⇒ 不建卡也不留挂起（回灌失败同样收口）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m18-upload-ref'
    store.historyLoaded = true
    api.frontendToolResult.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    const { sending, transport, host } = await startRound(store, '让我上传文件')
    transport.emit({
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

    transport.close({ suspended: true })
    await sending
  })
})

describe('M-19 start 帧之前停止（C2：传输保留 + 真实补 cancel）', () => {
  it('C2 原始：runId 未知时停止不 abort /chat；start 到达后补真实 cancel 并收尾连接', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19'
    store.historyLoaded = true

    const { sending, transport } = await startRound(store, '跑一个长任务')
    expect(transport.aborted).toBe(false)

    await store.stop()
    // ★ C2：不得 abort 初始 /chat 传输 —— 否则 start 帧永远到不了，补 cancel 不可达
    expect(transport.aborted).toBe(false)
    expect(api.cancel).not.toHaveBeenCalled()
    // 本地已按"已停止"展示（发送入口立刻回来）
    expect(store.loading).toBe(false)
    expect(store.stopped).toBe(true)

    transport.emit({ type: 'start', runId: 'run-late' } as SseFrame)
    await flushPromises()

    expect(api.cancel).toHaveBeenCalledTimes(1)
    expect(api.cancel).toHaveBeenCalledWith('run-late')
    expect(store.stoppedRunId).toBe('run-late')
    expect(transport.aborted).toBe(true)

    await sending
    expect(store.stopIntent).toBeNull()
  })

  it('C2 反向：旧意向不得取消新轮的 start（新轮宿主不同 ⇒ 不补 cancel，意向随之清理）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-m19-new-round'
    store.historyLoaded = true

    const first = await startRound(store, '第一轮')
    await store.stop()
    expect(store.stopIntent).toBeTruthy()

    const second = await startRound(store, '第二轮')
    second.transport.emit({ type: 'start', runId: 'run-second' } as SseFrame)
    await flushPromises()

    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.stoppedRunId).toBeNull()
    expect(store.stopIntent).toBeNull()

    first.transport.close({ terminal: true })
    second.transport.close({ terminal: true })
    await Promise.all([first.sending, second.sending])
  })

  it('I5 跨页签：停止意向不得取消另一页签的新轮（409 只登记，不猜归属）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-i5-cross-tab'
    store.historyLoaded = true

    const first = await startRound(store, '被停的这一轮')
    await store.stop()
    expect(store.stopIntent).toBeTruthy()

    // 另一页签刚起了新轮：本端下一条消息撞 409，响应体里是**别人的** runId
    api.chat.mockRejectedValueOnce(
      new ApiError('busy', 409, 'SESSION_BUSY', { runId: 'run-other-tab' })
    )
    await store.send('再来一条')
    await flushPromises()

    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.stoppedRunId).toBeNull()
    // 别人的轮照旧可作为"回到进行中的回答"入口（不谎报已停）
    expect(store.busyRun).toMatchObject({ runId: 'run-other-tab' })

    first.transport.close({ terminal: true })
    await first.sending
  })

  it('I5 生命周期：被停那一轮的传输收尾仍无 start ⇒ 意向作废（不留到 TTL 影响后续判断）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-i5-lifecycle'
    store.historyLoaded = true

    const { sending, transport } = await startRound(store, '没有 start 的一轮')
    await store.stop()
    expect(store.stopIntent).toBeTruthy()

    transport.close({ terminal: false, reason: '首帧超时' })
    await sending
    await flushPromises()

    expect(store.stopIntent).toBeNull()
    expect(api.cancel).not.toHaveBeenCalled()
  })
})

describe('I4 轮内主动重载（入口即在途）', () => {
  it('I4 原始：入口在途 ⇒ 不先替换镜像、也不整体替换历史；丢弃过期历史时清错误与缺口', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-i4'
    store.historyLoaded = true
    // 镜像是"上一刻的现场"（滞后于屏上本轮）：先写进镜像，再清屏开新一轮
    store.messages = [
      {
        id: 'mirror-user',
        role: 'user',
        content: '镜像里的旧提问',
        parts: [{ kind: 'text', seg: 0, text: '镜像里的旧提问', streaming: false }],
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: false,
        done: true
      },
      {
        id: 'mirror-assistant',
        role: 'assistant',
        content: '镜像里的旧回答',
        parts: [{ kind: 'text', seg: 0, text: '镜像里的旧回答', streaming: false }],
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: false,
        done: true
      }
    ] as ChatMessage[]
    store.writeMirrorNow()
    store.messages = []

    const { sending, transport, host } = await startRound(store, '本轮提问')
    transport.emit({ type: 'delta', text: '本轮的半截正文' } as SseFrame)
    await flushPromises()

    // 用户在本轮进行中点"重试/重新载入"（历史失败后的重试钮就是这条路）
    store.historyError = { scope: 'history', code: 'NETWORK_ERROR', status: 502, at: Date.now() }
    store.recoveryGap = { reason: 'history-unavailable', fromSeq: null, toSeq: null, at: Date.now() }
    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)

    const loading = store.loadHistory()
    // ★ 入口不得先拿镜像（滞后内容）把屏上本轮换掉
    expect(store.messages.some((message) => message.id === host.id)).toBe(true)
    expect(store.messages.some((message) => message.content === '镜像里的旧回答')).toBe(false)

    history.resolve({
      sessionId: 'sess-i4',
      count: 2,
      messages: [
        { type: 'USER', text: '服务端旧提问' },
        { type: 'ASSISTANT', text: '服务端旧回答' }
      ]
    } as AiHistory)
    await loading

    // 历史回来同样不整替；在途正文还在
    expect(store.messages.some((message) => message.content === '服务端旧回答')).toBe(false)
    expect(store.messages.find((message) => message.id === host.id)?.content).toContain('本轮的半截正文')
    // 成功但丢弃过期历史 ⇒ 历史错误与缺口作废（否则错误条会一直挂着）
    expect(store.historyError).toBeNull()
    expect(store.recoveryGap).toBeNull()

    transport.close({ terminal: true })
    await sending
  })

  it('N3 原始：停止后（保留的旧传输仍在）历史重载照常落盘，不被"在途"误判丢弃', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n3'
    store.historyLoaded = true

    const { sending, transport } = await startRound(store, '被停的一轮')
    await store.stop()
    // 停止后传输被保留（C2）—— 旧判据只看 currentAbort !== null，会把这次重载当"在途"整份丢弃
    expect(store.stopped).toBe(true)

    api.history.mockResolvedValue({
      sessionId: 'sess-n3',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)
    await store.loadHistory()

    expect(store.messages.some((message) => message.content === '旧回答')).toBe(true)

    transport.close({ terminal: true })
    await sending
  })

  it('N3 反向：停止后又真的开了新一轮（loading）⇒ 历史仍受在途保护、不整替', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n3-again'
    store.historyLoaded = true

    const first = await startRound(store, '被停的一轮')
    await store.stop()

    const second = await startRound(store, '停后新开的一轮')
    expect(store.loading).toBe(true)
    api.history.mockResolvedValue({
      sessionId: 'sess-n3-again',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)
    await store.loadHistory()

    expect(store.messages.some((message) => message.content === '旧回答')).toBe(false)
    expect(store.messages.some((message) => message.id === second.host.id)).toBe(true)

    first.transport.close({ terminal: true })
    second.transport.close({ terminal: true })
    await Promise.all([first.sending, second.sending])
  })
})

describe('N4 帧归属先于 seq 记账', () => {  it('N4 原始：旧宿主的迟到帧不得推进新 run 的 lastSeq（无 runId 帧不得借用 activeRunId）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n4'
    store.historyLoaded = true
    const hostA = store.appendAssistantMessage('A 的回答')
    hostA.done = true

    const round = await startRound(store, '新一轮')
    round.transport.emit({ type: 'start', runId: 'run-B', seq: 1 } as SseFrame)
    await flushPromises()
    expect(store.runSeq['run-B']).toBe(1)

    // 旧宿主上的迟到帧（无 runId、seq=7）：归属不是本轮 ⇒ 不得写进新 run 的帧序号锚点
    // （帧门上游本就会按连接身份丢弃这类帧；这里直接对 handleFrame 断言"归属先于 seq 记账"的顺序）
    store.handleFrame({ type: 'delta', text: '迟到帧', seq: 7 } as SseFrame, hostA.id)
    await flushPromises()

    expect(store.runSeq['run-B']).toBe(1)

    round.transport.close({ terminal: true })
    await round.sending
  })

  it('N4 相邻：本轮自己的帧照常推进锚点（守卫不误伤）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n4-ok'
    store.historyLoaded = true

    const round = await startRound(store, '正常一轮')
    round.transport.emit({ type: 'start', runId: 'run-ok', seq: 1 } as SseFrame)
    round.transport.emit({ type: 'delta', text: '正文', seq: 2 } as SseFrame)
    await flushPromises()

    expect(store.runSeq['run-ok']).toBe(2)

    round.transport.close({ terminal: true })
    await round.sending
  })
})

describe('N5 新轮 start 让旧停止意向失效时收掉旧传输', () => {
  it('N5 原始：新轮 start 使旧意向作废 ⇒ 顺手 abort 旧 /chat 传输（不再空等到首帧超时）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n5'
    store.historyLoaded = true

    const first = await startRound(store, '被停的一轮')
    await store.stop()
    expect(store.stopIntent).toBeTruthy()
    expect(first.transport.aborted).toBe(false)

    const second = await startRound(store, '新的一轮')
    second.transport.emit({ type: 'start', runId: 'run-second' } as SseFrame)
    await flushPromises()

    // 归属判断不变：新轮不被旧意向取消
    expect(api.cancel).not.toHaveBeenCalled()
    expect(store.stoppedRunId).toBeNull()
    expect(store.stopIntent).toBeNull()
    // 旧传输在这一刻已无意义 ⇒ 收掉（否则它会一直挂到 45s 首帧超时才结束）
    expect(first.transport.aborted).toBe(true)

    second.transport.close({ terminal: true })
    await Promise.all([first.sending, second.sending])
  })

  it('N5 相邻：自身 start 命中（补 cancel）同样收掉该传输，且只收自己的', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n5-own'
    store.historyLoaded = true

    const round = await startRound(store, '被停的这一轮')
    await store.stop()
    round.transport.emit({ type: 'start', runId: 'run-own' } as SseFrame)
    await flushPromises()

    expect(api.cancel).toHaveBeenCalledWith('run-own')
    expect(round.transport.aborted).toBe(true)

    await round.sending
  })
})

describe('N-1（Minor）停止后历史重载：await 后只比对轮次纪元', () => {
  it('N-1 原始：停止后重载期间旧连接被收（currentAbort 置 null）⇒ 历史仍要落盘', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n1-history'
    store.historyLoaded = true

    const { sending, transport } = await startRound(store, '被停的一轮')
    await store.stop()
    expect(store.stopped).toBe(true)

    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    const loading = store.loadHistory()

    // await 期间：保留的那条 pre-start 连接收尾/被收 ⇒ currentAbort 置 null（轮次纪元不变）
    transport.close({ terminal: false, reason: '连接已中止' })
    await sending
    expect(store.stopped).toBe(true)

    history.resolve({
      sessionId: 'sess-n1-history',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)
    await loading

    // 停止后重载：只认轮次纪元 ⇒ 连接被收不该把历史整份丢掉
    expect(store.messages.some((message) => message.content === '旧回答')).toBe(true)
  })

  it('N-1 反向：未停止时连接身份变化仍按原双判据丢弃（守卫不误伤）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-n1-history-live'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('屏上正文')

    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    const loading = store.loadHistory()

    // 未停止：await 期间起了续收连接（roundEpoch 未变，但 currentAbort 从 null 变为在途）
    void store.resumeSnapshotStream('run-live', 0, host.id, false)
    await Promise.resolve()

    history.resolve({
      sessionId: 'sess-n1-history-live',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    } as AiHistory)
    await loading

    expect(store.messages.some((message) => message.content === '旧回答')).toBe(false)
    expect(store.messages.some((message) => message.id === host.id)).toBe(true)
  })
})
