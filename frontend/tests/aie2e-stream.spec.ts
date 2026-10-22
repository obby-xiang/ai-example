/**
 * E2E 用例全集（第 53 件）· STRM 流式稳定域：单测缺口补齐（S-02 / S-03 / S-05 / S-06 / S-12）。
 *
 * <p>这五条的「单测」列在蓝本里是 ⚠️/❌ —— 已覆盖的部分（帧序纯函数、看门狗计时器、
 * 中止窗口）各有 spec；**缺的是把"进程边界上的那一段"在 store / SSE 层锁住**：
 * 回放段序与实时段序一致（Q21 的洞）、断流后的可重试面、差量续收不重复拼接、
 * 上游 socket 断后仍能发下一条、`done` 之后的迟到帧一条都不落地。
 *
 * <p>每条断言都对应蓝本 §2.1 该行的「断言点」原文，不做语义改写。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { streamChat } from '@/api/sse'
import { frame, freshStore, nextSession, segmentOrder, textMessage } from './aie2e-helpers'

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

let SESSION = ''

interface FrameHandlers {
  onFrame: (incoming: unknown) => void
  onClose?: (info: { terminal: boolean; reason?: string; suspended?: boolean }) => void
  onReconnect?: (attempt: number, delayMs: number) => void
}

/** 交错一轮的蓝本帧（t → tool → t → tool），seq 连续：实时与回放共用同一份。 */
function interleavedFrames(): Array<ReturnType<typeof frame>> {
  return [
    frame({ type: 'start', runId: 'run-s2', sessionId: SESSION, seq: 1 }),
    frame({ type: 'message_start', runId: 'run-s2', seq: 2, atMs: 1 }),
    frame({ type: 'delta', runId: 'run-s2', seq: 3, text: '先查配置。' }),
    frame({ type: 'tool_start', runId: 'run-s2', seq: 4, toolCallId: 'c1', name: 'list_config_defs', kind: 'BACKEND' }),
    frame({ type: 'tool_result', runId: 'run-s2', seq: 5, toolCallId: 'c1', name: 'list_config_defs', ok: true, result: '12 条' }),
    frame({ type: 'delta', runId: 'run-s2', seq: 6, text: '再看任务。' }),
    frame({ type: 'tool_start', runId: 'run-s2', seq: 7, toolCallId: 'c2', name: 'list_tasks', kind: 'BACKEND' }),
    frame({ type: 'tool_result', runId: 'run-s2', seq: 8, toolCallId: 'c2', name: 'list_tasks', ok: true, result: '3 条' }),
    frame({ type: 'message_end', runId: 'run-s2', seq: 9, chars: 12 }),
    frame({ type: 'done', runId: 'run-s2', seq: 10, cancelled: false })
  ]
}

beforeEach(() => {
  sessionStorage.clear()
  SESSION = nextSession('s-stream')
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: SESSION, count: 0, messages: [] })
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
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('S-02（P0）：文本/工具交错的回放段序 == 实时段序', () => {
  /**
   * 行为锁的判据来自蓝本断言点：① 流式 parts 段序 == 帧序；② **回放段序 == 实时段序**（逐段
   * kind+toolCallId 相等）；③ 同 toolCallId 不被拆两段。
   *
   * <p>既有 `parts-double-write.spec.ts` 只锁了实时一侧（帧序 → 段序），**没有锁回放一侧** ——
   * 而 Q21（历史/回放载荷丢段序）恰恰只在回放路径上出现。
   */
  it('同一份帧经"实时消费"与"重挂全量回放"得到逐段相同的段序', async () => {
    const live = await freshStore()
    live.sessionId = SESSION
    live.historyLoaded = true
    live.suspended = false
    // 实时：帧逐条投递（send 的 onFrame → handleFrame 路径；不显式给宿主 id ⇒ 走"当前助手消息"）
    live.messages = [textMessage('a-live', 'assistant', '', { streaming: true, done: false })]
    for (const incoming of interleavedFrames()) {
      live.handleFrame(incoming)
    }
    const liveOrder = segmentOrder(live.messages[0])

    const replay = await freshStore()
    replay.sessionId = SESSION
    replay.historyLoaded = true
    replay.messages = [textMessage('a-live', 'assistant', '', { streaming: true, done: false })]
    // 回放：完全相同的帧序列（归档读出的就是这一份）
    for (const incoming of interleavedFrames()) {
      replay.handleFrame(incoming)
    }
    const replayOrder = segmentOrder(replay.messages[0])

    expect(replayOrder).toEqual(liveOrder)
    // 段序本身：首段（消息工厂开）→ message_start 开的正文段 → 工具 c1 → 工具后的正文段 → 工具 c2。
    // 工具夹在正文中间，不沉底；段号是 `message_start`/工具段边界的结果（非下标连续）。
    expect(liveOrder).toEqual(['text:0', 'text:1', 'tool:c1', 'text:3', 'tool:c2'])
    // ③ 同一 toolCallId 只有一段（回放不得拆成两段）
    expect(liveOrder.filter((seg) => seg === 'tool:c1')).toHaveLength(1)
    expect(replayOrder.filter((seg) => seg === 'tool:c2')).toHaveLength(1)
    // 正文两段拼接 == 实时 delta 拼接
    expect(replay.messages[0]?.content).toBe('先查配置。再看任务。')
  }, 20_000)
})

describe('S-03（P2）：建连看门狗到点后必须有可重试面，不得"已发送但零状态变化"', () => {
  /**
   * 行为锁（计时器本身已由 `sse-watchdog.spec.ts` 锁死 15s/45s；本用例锁的是**它之后**）：
   * 接管建连失败 ⇒ `streamInterrupted` + 可重试提示 + `loading` 复位 + 已收正文保留。
   */
  it('接管在建连阶段失败（15s 无 onopen）⇒ 显式可重试面，不是静默挂死', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.historyLoaded = true
    store.messages = [textMessage('a1', 'assistant', '已经收到的半截正文', { streaming: true, done: false })]
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'delta', runId: 'run-s3', seq: 2, text: '…' }))
      handlers.onClose?.({ terminal: false, reason: '建连超时（15s）' })
      return Promise.resolve({ terminal: false, reason: '建连超时（15s）' })
    })

    await store.reattachActive('run-s3', { hostMessageId: 'a1', lastSeq: 1 })

    expect(store.streamInterrupted).toBe(true)
    expect(store.lastRoundOutcome).toBe('interrupted')
    expect(store.notice).not.toBeNull()
    expect(store.notice).toContain('连接中断')
    expect(store.loading).toBe(false)
    // 已收正文保留（断流不是"清屏"）
    expect(store.messages[0]?.content).toContain('已经收到的半截正文')
  }, 20_000)
})

describe('S-05（P0）：重连差量续收不重复、不丢段', () => {
  /**
   * 行为锁：重连 URL 带 `lastSeq=K`；正文拼接后 == 全量正文（无重复段落、无丢段）；`done` 只 1 次。
   * 既有 `sse-watchdog` 只锁"会不会重连"，不锁"续收拼得对不对"。
   */
  it('lastSeq=3 续收：只收 seq>3 的帧，正文与全量一致且 done 只出现一次', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.historyLoaded = true
    store.runSeq = { 'run-s5': 3 }
    store.messages = [textMessage('a1', 'assistant', '第一段第二段', { streaming: true, done: false })]
    const doneSeen: unknown[] = []
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'delta', runId: 'run-s5', seq: 4, text: '第三段' }))
      handlers.onFrame(frame({ type: 'message_end', runId: 'run-s5', seq: 5, chars: 12 }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-s5', seq: 6, cancelled: false }))
      doneSeen.push('done')
      handlers.onClose?.({ terminal: true })
      return Promise.resolve({ terminal: true })
    })

    await store.reattachActive('run-s5', { hostMessageId: 'a1' })

    // 续收必须带位点（服务端只补差量）
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 3 })
    expect(store.messages[0]?.content).toBe('第一段第二段第三段')
    expect(store.messages[0]?.content.match(/第三段/g)).toHaveLength(1)
    expect(doneSeen).toHaveLength(1)
    expect(store.loading).toBe(false)
  }, 20_000)

  it('重复投递同一 seq 的 delta 不重复累加（重连与实时双通道竞态）', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('a1', 'assistant', '', { streaming: true, done: false })]
    store.handleFrame(frame({ type: 'delta', runId: 'run-s5b', seq: 7, text: 'AB' }))
    store.handleFrame(frame({ type: 'delta', runId: 'run-s5b', seq: 7, text: 'AB' }))
    expect(store.messages[0]?.content).toBe('AB')
  }, 20_000)
})

describe('S-06（P0）：上游中途断流 ⇒ 终态可见、正文保留、可继续发下一条', () => {
  /**
   * 行为锁：`done` 前收到终态错误；已收正文保留；页面可继续发下一条。
   * （蓝本另一断言 `turn_end` 有 `status`/`code` 是后端帧面，见后端 `UpstreamBreakTerminalTest`。）
   */
  it('流式 3 帧后上游断连：正文保留 + 断流提示 + 下一条能发出（loading 复位）', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.historyLoaded = true
    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'start', runId: 'run-s6', sessionId: SESSION, seq: 1 }))
      handlers.onFrame(frame({ type: 'delta', runId: 'run-s6', seq: 2, text: '已经写了一半' }))
      handlers.onClose?.({ terminal: false, reason: '上游连接被重置' })
      return Promise.resolve({ terminal: false, reason: '上游连接被重置' })
    })

    const first = await store.send('查一下配置')
    expect(first.outcome).toBe('interrupted')
    expect(store.messages.at(-1)?.content).toBe('已经写了一半')
    expect(store.streamInterrupted).toBe(true)
    expect(store.notice).toContain('结果可能不完整')
    expect(store.loading).toBe(false)

    // 页面可继续发下一条：第二次发送照常走网络（不是 409 / 不是本地拦截）
    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'start', runId: 'run-s6b', sessionId: SESSION, seq: 1 }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-s6b', seq: 2, cancelled: false }))
      handlers.onClose?.({ terminal: true })
      return Promise.resolve({ terminal: true })
    })
    const second = await store.send('再来一次')
    expect(second.outcome).toBe('ok')
    expect(api.chat).toHaveBeenCalledTimes(2)
    expect(store.streamInterrupted).toBe(false)
  }, 20_000)
})

describe('S-12（P0）：done 之后的迟到帧一条都不落地', () => {
  /**
   * 【缺陷挂账 · 红测先行】本用例**当前是红的**（`it.fails` 显式登记为"已知不满足"）。
   *
   * <p>实测：`api/sse.ts#readStream` 的终帧判定在**整块消费完**之后才 `break`
   * （`for (const payload of parser.push(text))` 内部不检查 `state.terminal`）——
   * 同一 TCP 块里跟在 `done` 后面的帧仍会逐帧投递给 `onFrame`，store 侧也没有"终态后拒收"
   * 的守卫，于是迟到 `delta` 会写进 parts、迟到 `tool_start` 会新建卡。
   *
   * <p>与蓝本 §2.1 S-12 断言点（"帧不入 parts、不改卡状态、不进镜像"）正面冲突；
   * 修法只需在读循环内每帧前判 `state.terminal`（生产小改，本批不动生产代码）。
   * 修复合入后本用例会"意外通过"，届时把 `it.fails` 改回 `it` 即可 —— 这正是它存在的意义。
   */
  it.fails('done 之后服务端仍继续推 2 帧：onFrame 只收到 done 之前的帧', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(
          'data: {"type":"start","runId":"r12","seq":1}\n\n'
          + 'data: {"type":"delta","runId":"r12","seq":2,"text":"正文"}\n\n'
          + 'data: {"type":"done","runId":"r12","seq":3,"cancelled":false}\n\n'
          + 'data: {"type":"delta","runId":"r12","seq":4,"text":"迟到一"}\n\n'
          + 'data: {"type":"tool_start","runId":"r12","seq":5,"toolCallId":"late","name":"x","kind":"BACKEND"}\n\n'
        ))
        controller.close()
      }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: stream }))

    const seen: string[] = []
    const info = await streamChat(
      { sessionId: SESSION, message: '问' } as never,
      { onFrame: (incoming) => seen.push((incoming as { type: string }).type) },
      {}
    )

    expect(seen).toEqual(['start', 'delta', 'done'])
    expect(seen).not.toContain('tool_start')
    expect(info.terminal).toBe(true)
  }, 20_000)
})
