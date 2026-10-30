/**
 * 2026-10-29 ToC 状态自动收口批（8 项）。
 *
 * <h2>口径（每条都用真实 store 路径，不打桩内部实现）</h2>
 * <ol>
 * <li>准备中（preparing）**自动有界恢复** —— 面板里没有任何"立即重试"按钮（`retryPreparing`
 *     无 UI 调用点），恢复不能靠不存在的入口；</li>
 * <li>重挂 / 续收断流收尾要**封口旧消息**：`done=false + streaming=true` 挂着就是"永久工作中"；</li>
 * <li>权威快照（`/runs/current`）读成功 ⇒ 之前的恢复缺口（全局 + 逐消息）作废；</li>
 * <li>重连 / 续收的"正在第 N 次重试"进度通知在 onOpen / 首业务帧 / done 到达时清掉；
 *     空回复（`emptyReply`）重试**不得**说成"连接中断"；</li>
 * <li>停止生成保留未提交表单草稿（sessionStorage 既有键），只有明确放弃（取消表单）才清；</li>
 * <li>`uiAnnouncement` 按轮收口（终态 / 新发送）；</li>
 * <li>`readActionRecord` 保留 `display`（面板 chip 按语言重译），`source` 原样保留不丢；</li>
 * <li>确定零副作用的三类失败（首帧超时 / 历史读取失败 / 410 已换会话）走**共用有界**自动恢复，
 *     耗尽后保留既有手动出口，绝不重复副作用（不重发用户消息）。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import {
  AUTO_RECOVERY_DELAY_MS,
  AUTO_RECOVERY_MAX_ATTEMPTS,
  RUN_STREAM_DRIVE_BUDGET,
  readActionRecord,
  useAiStore
} from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { SseFrameHandlers, SseStreamOptions } from '@/api/sse'
import type { AiRunCurrent } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { formDraftKey } from '@/utils/form-draft'

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
  retryModelStep: vi.fn(),
  // Task 4：会话号只由后端发号（410 换会话等路径要真发一次号）
  createSession: vi.fn()
}))

const sse = vi.hoisted(() => ({ probeReattach: vi.fn() }))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/api/sse', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sse')>()),
  probeReattach: sse.probeReattach
}))

interface ChatTransport {
  handlers: SseFrameHandlers
  aborted: boolean
  emit(frame: SseFrame): void
  close(info?: { terminal?: boolean; suspended?: boolean; reason?: string; timeoutStage?: string }): void
}

/** 忠于 `api/sse.ts` 的假 /chat：abort/close 都会调 `onClose` 并 resolve（不伪造"连接还活着"）。 */
function createChatStub(): {
  impl: (_body: unknown, handlers: SseFrameHandlers, options?: SseStreamOptions) => Promise<void>
  transports: ChatTransport[]
  last(): ChatTransport
} {
  const transports: ChatTransport[] = []
  const impl = (
    _body: unknown,
    handlers: SseFrameHandlers,
    options: SseStreamOptions = {}
  ): Promise<void> => new Promise<void>((resolve) => {
    let settled = false
    const transport: ChatTransport = {
      handlers,
      aborted: false,
      emit(frame: SseFrame): void {
        if (settled || transport.aborted) {
          return
        }
        handlers.onFrame?.(frame)
      },
      close(info = {}): void {
        if (settled) {
          return
        }
        settled = true
        handlers.onClose?.({
          terminal: info.terminal ?? false,
          suspended: info.suspended,
          reason: info.reason,
          ...(info.timeoutStage === undefined ? {} : { timeoutStage: info.timeoutStage })
        } as never)
        resolve()
      }
    }
    transports.push(transport)
    options.signal?.addEventListener('abort', () => {
      if (settled) {
        return
      }
      settled = true
      transport.aborted = true
      handlers.onClose?.({ terminal: false, reason: '连接已中止' } as never)
      resolve()
    }, { once: true })
  })
  return {
    impl,
    transports,
    last(): ChatTransport {
      const last = transports[transports.length - 1]
      if (!last) {
        throw new Error('没有建立过 /chat 传输')
      }
      return last
    }
  }
}

function noRun(): AiRunCurrent {
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

function runningRun(runId: string, archiveMaxSeq = 3): AiRunCurrent {
  return {
    ...noRun(),
    found: true,
    runId,
    snapshotStatus: 'RUNNING',
    archiveMaxSeq
  }
}

function asFrame(frame: Record<string, unknown>): SseFrame {
  return frame as unknown as SseFrame
}

function freshStore(): ReturnType<typeof useAiStore> {
  setActivePinia(createPinia())
  return useAiStore()
}

/**
 * 有界条件等待：只推进微任务，直到 `condition` 成立。
 *
 * <p>不能用 `flushPromises()`：它内部走 `setImmediate`，本文件大量用例开着 fake timers，
 * 那些计时器不会自己跑。3d801ff1 起 `send()` 取号前先 `await ensureSession()`（已有 sessionId
 * 也先等页签归属判定），`/chat` 不再落在 `send()` 的第一个微任务里 —— 因此要等到"传输真的建立"。
 */
async function until(condition: () => boolean, label: string): Promise<void> {
  for (let tick = 0; tick < 500; tick += 1) {
    if (condition()) {
      return
    }
    await Promise.resolve()
  }
  throw new Error(`等待超时：${label}`)
}

/** 起一轮并拿到真实传输句柄（`send()` 的取号门控跑完 = `/chat` 已建立）。 */
async function startRound(
  store: ReturnType<typeof useAiStore>,
  text: string
): Promise<ChatTransport> {
  const before = chat.transports.length
  void store.send(text)
  await until(() => chat.transports.length > before, 'send() 没有走到 aiApi.chat')
  return chat.last()
}

let chat: ReturnType<typeof createChatStub>

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  sse.probeReattach.mockReset()
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(noRun())
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.frontendToolResult.mockResolvedValue({})
  api.retryModelStep.mockResolvedValue({ retried: true })
  api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
  chat = createChatStub()
  api.chat.mockImplementation(chat.impl as never)
  installIdentityFixture()
})

describe('1 · 准备中自动有界恢复（不靠不存在的按钮）', () => {
  it('准备中无需手动重试：有界自动探活后按既有恢复路径续收', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-prep-auto'
      const host = store.appendAssistantMessage('半截')
      host.streaming = true
      host.done = false
      host.runId = 'run-prep'
      store.activeRunId = 'run-prep'
      api.runsCurrent.mockResolvedValue(noRun())
      sse.probeReattach.mockResolvedValue({
        status: 202,
        preparing: { runId: 'run-prep', elapsedMs: 4_000, retryAfterMs: 1_000 }
      })

      await store.restoreSnapshotGuarded()
      expect(store.preparingRun?.runId).toBe('run-prep')

      // 快照建成（服务端压缩结束）：下一次自动探活应接管续收
      api.runsCurrent.mockResolvedValue(runningRun('run-prep', 2))
      const reattach = vi.spyOn(store, 'reattachActive').mockResolvedValue(undefined)
      // 手动重试动作不得被调用（面板没有这个按钮）
      const retryPreparing = vi.spyOn(store, 'retryPreparing')

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)

      expect(retryPreparing).not.toHaveBeenCalled()
      expect(reattach).toHaveBeenCalledWith('run-prep', expect.objectContaining({ lastSeq: 0 }))
      expect(store.preparingRun).toBeNull()
      expect(store.currentError).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('准备中自动恢复耗尽：停止重探并保留既有手动出口（错误条重试）', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-prep-exhaust'
      const host = store.appendAssistantMessage('半截')
      host.streaming = true
      host.done = false
      host.runId = 'run-prep-x'
      store.activeRunId = 'run-prep-x'
      api.runsCurrent.mockResolvedValue(noRun())
      sse.probeReattach.mockResolvedValue({
        status: 202,
        preparing: { runId: 'run-prep-x', elapsedMs: 1_000, retryAfterMs: 1_000 }
      })

      await store.restoreSnapshotGuarded()
      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS * (AUTO_RECOVERY_MAX_ATTEMPTS + 2))
      await flushPromises()

      const probes = api.runsCurrent.mock.calls.length
      // 有界：初始 1 次 + 自动至多 AUTO_RECOVERY_MAX_ATTEMPTS 次
      expect(probes).toBe(1 + AUTO_RECOVERY_MAX_ATTEMPTS)
      // 耗尽后仍停在准备中现场，但错误条给出既有手动重试出口
      expect(store.preparingRun?.runId).toBe('run-prep-x')
      expect(store.currentError).toMatchObject({ scope: 'current' })

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS * 4)
      expect(api.runsCurrent.mock.calls.length).toBe(probes)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('2 · 重挂 / 续收断流收尾必须封口旧消息', () => {
  it('重挂连接断了：宿主消息封口（不再永久工作中），断流提示照旧', async () => {
    const store = freshStore()
    store.sessionId = 'sess-seal-reattach'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('')
    host.streaming = true
    host.done = false
    host.runId = 'run-seal'
    let handlers: SseFrameHandlers | null = null
    api.reattachRun.mockImplementation((_runId: string, sink: SseFrameHandlers) => {
      handlers = sink
      return new Promise(() => {})
    })

    void store.reattachActive('run-seal', { hostMessageId: host.id, lastSeq: 0 })
    await flushPromises()
    handlers!.onFrame?.(asFrame({ type: 'delta', runId: 'run-seal', seq: 1, text: '半截正文' }))
    await flushPromises()

    handlers!.onClose?.({ terminal: false, reason: '连接中断' } as never)
    await flushPromises()

    expect(store.streamInterrupted).toBe(true)
    expect(host.done).toBe(true)
    expect(host.streaming).toBe(false)
    expect(host.parts.some((part) => part.kind === 'text' && part.streaming === true)).toBe(false)
  })

  it('续收连接断了：同一轮宿主同样封口（不留在流式假态）', async () => {
    const store = freshStore()
    store.sessionId = 'sess-seal-resume'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('')
    host.streaming = true
    host.done = false
    host.runId = 'run-resume'
    let handlers: SseFrameHandlers | null = null
    api.reattachRun.mockImplementation((_runId: string, sink: SseFrameHandlers) => {
      handlers = sink
      return new Promise(() => {})
    })

    void store.resumeSnapshotStream('run-resume', 0, host.id, false)
    await flushPromises()
    handlers!.onClose?.({ terminal: false, reason: '连接中断' } as never)
    await flushPromises()

    expect(host.done).toBe(true)
    expect(host.streaming).toBe(false)
  })
})

describe('3 · 权威快照恢复成功清恢复缺口', () => {
  it('快照读到在途轮：全局 recoveryGap 与逐消息标记一并作废', async () => {
    const store = freshStore()
    store.sessionId = 'sess-gap-clear'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('半截')
    host.streaming = true
    host.done = false
    host.runId = 'run-gap'
    host.recoveryGap = true
    store.activeRunId = 'run-gap'
    store.recoveryGap = { reason: 'snapshot-unavailable', fromSeq: null, toSeq: null, at: 1 }
    api.runsCurrent.mockResolvedValue(runningRun('run-gap', 2))
    vi.spyOn(store, 'reattachActive').mockResolvedValue(undefined)

    await store.restoreSnapshotGuarded()

    expect(store.recoveryGap).toBeNull()
    expect(host.recoveryGap).toBeUndefined()
    expect(store.currentError).toBeNull()
  })
})

describe('4 · 重连进度通知与空回复重试口径', () => {
  it('onOpen / 首业务帧 / done 都清"正在第 N 次重连"进度通知', async () => {
    const store = freshStore()
    store.sessionId = 'sess-notice'
    store.historyLoaded = true
    const transport = await startRound(store, '跑一个长任务')

    transport.handlers.onReconnect?.(2, 1_000)
    expect(store.noticeCode).toBe('ai.notice.reconnect')
    transport.handlers.onOpen?.()
    expect(store.noticeCode).toBeNull()

    transport.handlers.onReconnect?.(3, 2_000)
    expect(store.noticeCode).toBe('ai.notice.reconnect')
    transport.emit(asFrame({ type: 'start', runId: 'run-notice' }))
    expect(store.noticeCode).toBeNull()

    transport.handlers.onReconnect?.(4, 3_000)
    expect(store.noticeCode).toBe('ai.notice.reconnect')
    transport.emit(asFrame({ type: 'done', runId: 'run-notice' }))
    expect(store.noticeCode).toBeNull()
  })

  it('空回复重试帧（emptyReply）不得说成"连接中断"；真正的断流重试才那样说', async () => {
    const store = freshStore()
    store.sessionId = 'sess-empty-retry'
    store.historyLoaded = true
    store.appendAssistantMessage('')

    store.handleFrame(asFrame({
      type: 'retry',
      runId: 'run-empty',
      nextAttempt: 2,
      code: 'EMPTY_REPLY',
      emptyReply: true
    }))
    expect(store.noticeCode).not.toBe('ai.notice.upstreamRetry')
    expect(store.noticeText ?? '').not.toContain('连接中断')
    expect(store.noticeText ?? '').not.toContain('尚未产出内容')

    store.handleFrame(asFrame({
      type: 'retry',
      runId: 'run-empty',
      nextAttempt: 3,
      code: 'UPSTREAM_CONNECTION_BROKEN'
    }))
    expect(store.noticeCode).toBe('ai.notice.upstreamRetry')
  })
})

describe('5 · 停止生成保留未提交表单草稿', () => {
  it('停止只收起表单卡；草稿留在既有 sessionStorage 键，明确放弃才清', async () => {
    const store = freshStore()
    store.sessionId = 'sess-form-stop'
    store.historyLoaded = true
    store.loading = true
    store.activeRunId = 'run-form'
    store.activeForm = {
      toolCallId: 'call-stop',
      runId: 'run-form',
      form: { title: '表单', fields: [] } as never,
      status: 'filling',
      error: null,
      values: {},
      fieldErrors: []
    }
    store.updateFormDraft({ region: 'HB', project: 'P1' })
    expect(sessionStorage.getItem(formDraftKey('call-stop'))).toContain('HB')

    await store.stop()
    await flushPromises()

    expect(store.activeForm).toBeNull()
    expect(sessionStorage.getItem(formDraftKey('call-stop'))).toContain('HB')

    // 明确放弃（取消表单）才清草稿
    store.activeForm = {
      toolCallId: 'call-abandon',
      runId: 'run-form',
      form: { title: '表单', fields: [] } as never,
      status: 'filling',
      error: null,
      values: {},
      fieldErrors: []
    }
    store.updateFormDraft({ region: 'BJ' })
    expect(sessionStorage.getItem(formDraftKey('call-abandon'))).toContain('BJ')
    await store.cancelGenerativeForm('call-abandon')
    expect(sessionStorage.getItem(formDraftKey('call-abandon'))).toBeNull()
  })
})

describe('6 · uiAnnouncement 按轮收口', () => {
  it('终态与新发送都清掉上一轮的界面播报', async () => {
    const store = freshStore()
    store.sessionId = 'sess-announce'
    store.historyLoaded = true
    store.appendAssistantMessage('')
    store.uiAnnouncement = '已选择配置项：A'
    store.handleFrame(asFrame({ type: 'done', runId: 'run-a' }))
    expect(store.uiAnnouncement).toBeNull()

    store.uiAnnouncement = '已选择配置项：B'
    // 3d801ff1：新轮清理播报排在取号门（`ensureSession`）之后 ⇒ 等本轮真正开起来再断言
    await startRound(store, '下一轮')
    expect(store.uiAnnouncement).toBeNull()
  })
})

describe('7 · readActionRecord 保留 display，source 不丢', () => {
  it('结构化展示面随记录保留；两值 source 原样保留', () => {
    const record = readActionRecord({
      version: 7,
      summary: '绑定任务 #9',
      at: 1_700_000_000_000,
      source: 'AI',
      display: { code: 'ai.workspace.bindTask', args: { taskId: 9 } }
    })
    expect(record).toMatchObject({
      version: 7,
      summary: '绑定任务 #9',
      source: 'AI',
      display: { code: 'ai.workspace.bindTask', args: { taskId: 9 } }
    })
    // 旧数据码 '界面' 仍被读入侧接受：归一为数据码 'UI'（兼容面，不丢已知值）
    expect(readActionRecord({ version: 8, summary: '选配置项', source: '界面' }))
      .toMatchObject({ source: 'UI' })
    // 非法 display 不塞给面板（不臆造 code），记录本身仍可用
    expect(readActionRecord({ version: 9, summary: 'x', source: '界面', display: { args: { a: 1 } } }))
      .toMatchObject({ summary: 'x', source: 'UI' })
  })
})

describe('8 · 共用有界自动恢复（零副作用面）', () => {
  it('首帧超时（零帧零副作用）：自动按快照续收，绝不重发用户消息', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-first-frame'
      store.historyLoaded = true
      const transport = await startRound(store, '跑一个长任务')
      api.runsCurrent.mockResolvedValue(runningRun('run-ff', 4))
      let reattachSink: SseFrameHandlers | null = null
      api.reattachRun.mockImplementation((_runId: string, sink: SseFrameHandlers) => {
        reattachSink = sink
        return new Promise(() => {})
      })

      transport.close({ terminal: false, timeoutStage: 'first-frame', reason: '首帧超时' })
      await flushPromises()
      expect(store.loading).toBe(true)
      expect(store.noticeCode).toBe('ai.notice.firstFrameTimeout')

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)
      await flushPromises()

      expect(api.runsCurrent).toHaveBeenCalled()
      expect(api.reattachRun).toHaveBeenCalledWith('run-ff', expect.anything(), expect.anything())
      expect(reattachSink).not.toBeNull()
      // 绝不重发：首帧超时面唯一安全的恢复是"接管既有轮"
      expect(api.chat).toHaveBeenCalledTimes(1)
      expect(store.loading).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('历史读取失败：自动有界重读；耗尽后保留既有手动出口', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-hist-auto'
      api.history
        .mockRejectedValueOnce(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))
        .mockResolvedValueOnce({ sessionId: 'sess-hist-auto', count: 0, messages: [] })

      await store.loadHistory()
      expect(store.historyError).not.toBeNull()

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)
      await flushPromises()
      expect(store.historyError).toBeNull()
      expect(api.history).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }

    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-hist-exhaust'
      // 新场景换新 pinia：计数从零起（上一场景已用掉 2 次调用）
      api.history.mockClear()
      api.history.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

      await store.loadHistory()
      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS * (AUTO_RECOVERY_MAX_ATTEMPTS + 2))
      await flushPromises()

      // 有界：初次 1 次 + 自动至多 MAX 次；耗尽后错误条与缺口保留（既有手动重试出口）
      expect(api.history).toHaveBeenCalledTimes(1 + AUTO_RECOVERY_MAX_ATTEMPTS)
      expect(store.historyError).toMatchObject({ scope: 'history' })
      expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('410 已换会话：本地换 id 后自动对账新会话，绝不复发用户消息', async () => {
    vi.useFakeTimers()
    try {
      // 旧会话号：身份键下的存量指针（会话号契约是十进制长整）
      installIdentityFixture({ sessionId: 4401 })
      const store = freshStore()
      expect(store.sessionId).toBe('4401')
      // 换会话必须拿到**新**号（后端发号）
      api.createSession.mockResolvedValueOnce({ sessionId: 4402 })
      api.chat.mockRejectedValueOnce(new ApiError('purged', 410, 'SESSION_PURGED', {
        code: 'SESSION_PURGED',
        message: 'purged',
        sessionId: '4401',
        action: 'NEW_SESSION'
      }))

      const result = await store.send('帮我导一张表')
      expect(result.outcome).toBe('error')
      const nextId = store.sessionId
      expect(nextId).not.toBe('4401')

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)
      await flushPromises()

      // 零副作用恢复 = 新会话对账（GET），不是把用户那句话再发一遍
      expect(api.chat).toHaveBeenCalledTimes(1)
      expect(api.history).toHaveBeenCalledTimes(1)
      expect(api.history.mock.calls[0]?.[0]).toBe(nextId)
      expect(store.historyError).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })
})

/* ══════════════════════════════════════════════════════════════════════════════
 * 复审返修 R1（I-1 / M-1 / M-2 / M-4）：只收窄误清与误封，不扩散。
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('R1 · I-1 缺口只清"读快照能解决"的那一类', () => {
  it('archive-window / history-unavailable 不得被一次成功快照抹掉', async () => {
    for (const reason of ['archive-window', 'history-unavailable'] as const) {
      const store = freshStore()
      store.sessionId = `sess-gap-${reason}`
      store.historyLoaded = true
      const host = store.appendAssistantMessage('缺了中间帧的半截')
      host.streaming = true
      host.done = false
      host.runId = 'run-arch'
      host.recoveryGap = true
      store.activeRunId = 'run-arch'
      store.recoveryGap = { reason, fromSeq: 3, toSeq: 5, at: 1 }
      api.runsCurrent.mockResolvedValue(runningRun('run-arch', 6))
      vi.spyOn(store, 'reattachActive').mockResolvedValue(undefined)

      await store.restoreSnapshotGuarded()

      // 读 /runs/current 补不回被归档窗口裁掉的帧 ⇒ 缺口与逐消息标记都必须保留
      expect(store.recoveryGap).toMatchObject({ reason })
      expect(host.recoveryGap).toBe(true)
    }
  })

  it('found=false（轮已消失）不得把半截回答洗成完整：缺口与标记保留', async () => {
    const store = freshStore()
    store.sessionId = 'sess-gap-gone'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('半截回答')
    host.streaming = true
    host.done = false
    host.runId = 'run-gone'
    host.recoveryGap = true
    store.activeRunId = 'run-gone'
    store.recoveryGap = { reason: 'snapshot-unavailable', fromSeq: null, toSeq: null, at: 1 }
    api.runsCurrent.mockResolvedValue(noRun())
    sse.probeReattach.mockResolvedValue({ status: 404, preparing: null })

    await store.restoreSnapshotGuarded()

    // 封口 ≠ 完整：半截回答封口后仍必须带着"不可确认完整"的标记
    expect(host.done).toBe(true)
    expect(host.recoveryGap).toBe(true)
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
  })
})

describe('R1 · M-1 进度通知清理排在轮次归属判定之后', () => {
  it('旧轮迟到帧不得清新轮的"正在第 N 次重连"通知', () => {
    const store = freshStore()
    store.sessionId = 'sess-notice-owned'
    store.historyLoaded = true
    store.appendAssistantMessage('本轮')
    const late = store.appendAssistantMessage('旧轮')
    late.runId = 'run-old'
    late.done = false

    store.noticeCode = 'ai.notice.reconnect'
    store.notice = '连接中断，正在第 2 次重连'

    // 旧轮（非本轮宿主）迟到的 start 帧：不得改写轮次级通知
    store.handleFrame(asFrame({ type: 'start', runId: 'run-old', seq: 1 }), late.id)
    expect(store.noticeCode).toBe('ai.notice.reconnect')

    // 对照：本轮的帧照样清（清理本身不能被修死）
    store.handleFrame(asFrame({ type: 'start', runId: 'run-new', seq: 1 }))
    expect(store.noticeCode).toBeNull()
  })
})

describe('R1 · M-2 首帧超时恢复以"真接回"为判据', () => {
  it('预算不足接不回：耗尽后封口宿主并解禁（不留"工作中无连接"）', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-ff-noconn'
      store.historyLoaded = true
      const transport = await startRound(store, '跑一个长任务')
      const host = store.messages.find((message) => message.role === 'assistant')
      expect(host).toBeTruthy()
      api.runsCurrent.mockResolvedValue(runningRun('run-ff-x', 3))
      // 驱动预算已用尽 ⇒ restoreInFlightRound 只置 loading、不建连接（loading 不能当"接回"）
      store.streamDrives = {
        'run-ff-x': { token: 1, used: RUN_STREAM_DRIVE_BUDGET, active: false }
      }

      transport.close({ terminal: false, timeoutStage: 'first-frame', reason: '首帧超时' })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS * (AUTO_RECOVERY_MAX_ATTEMPTS + 1))
      await flushPromises()

      expect(api.reattachRun).not.toHaveBeenCalled()
      expect(api.chat).toHaveBeenCalledTimes(1) // 绝不重发
      expect(store.loading).toBe(false)
      expect(host?.done).toBe(true)
      expect(host?.streaming).toBe(false)
      // 手动出口保留：首帧超时提示仍在
      expect(store.noticeCode).toBe('ai.notice.firstFrameTimeout')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('R1 · M-4 封口兜底只封本轮宿主', () => {
  it('无宿主 id 的续收断流：不误封与本轮无关的未收尾助手消息', () => {
    const store = freshStore()
    store.sessionId = 'sess-seal-run'
    store.historyLoaded = true
    const target = store.appendAssistantMessage('本轮半截')
    target.runId = 'run-this'
    target.streaming = true
    target.done = false
    const unrelated = store.appendAssistantMessage('别的轮半截')
    unrelated.runId = 'run-other'
    unrelated.streaming = true
    unrelated.done = false

    store.sealInterruptedHost(null, 'run-this')

    expect(target.done).toBe(true)
    expect(target.streaming).toBe(false)
    expect(unrelated.done).toBe(false)
    expect(unrelated.streaming).toBe(true)
  })
})

/**
 * 2026-10-29 返修（复审 I-1）：`restoringRecovery` 是"恢复在途"的**唯一**呈现层判据
 * （`turnStillOpen` 用它把"恢复期"从"本轮未终态"里排除）。而它原先只在 4 个**手动**包装层
 * 置位；两条**自动恢复**路径直接调 `restoreSnapshotGuarded()`，标志全程为假 ⇒ 恢复期仍把
 * 末条已决只读卡先展开、快照回来再折。本组用例只锁"自动恢复 attempt 也置位 / finally 复位"。
 */
describe('9 · 恢复期标志覆盖自动恢复入口（2026-10-29 返修 I-1）', () => {
  /** 可挂起的 `runs/current`：让 `restoreSnapshotGuarded` 停在"恢复在途"（返回 release）。 */
  function pendingRunsCurrent(): (value: AiRunCurrent) => void {
    let release: (value: AiRunCurrent) => void = () => {}
    api.runsCurrent.mockImplementation(() => new Promise<AiRunCurrent>((resolve) => {
      release = resolve
    }))
    return (value) => release(value)
  }

  it('准备中自动恢复在途：restoringRecovery 置位，恢复结束 finally 复位', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-i1-prep'
      const host = store.appendAssistantMessage('半截')
      host.streaming = true
      host.done = false
      host.runId = 'run-i1-prep'
      store.activeRunId = 'run-i1-prep'
      const release = pendingRunsCurrent()

      store.notePreparingState('run-i1-prep', 1_000)
      expect(store.restoringRecovery).toBe(false)

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)
      await flushPromises()

      expect(api.runsCurrent).toHaveBeenCalled()
      expect(store.restoringRecovery).toBe(true)

      release(noRun())
      await flushPromises()
      expect(store.restoringRecovery).toBe(false)
      store.resetAutoRecovery('preparing')
    } finally {
      vi.useRealTimers()
    }
  })

  it('首帧超时自动恢复在途：restoringRecovery 置位，恢复结束 finally 复位', async () => {
    vi.useFakeTimers()
    try {
      const store = freshStore()
      store.sessionId = 'sess-i1-first-frame'
      store.historyLoaded = true
      const transport = await startRound(store, '跑一个长任务')
      const release = pendingRunsCurrent()

      transport.close({ terminal: false, timeoutStage: 'first-frame', reason: '首帧超时' })
      await flushPromises()
      expect(store.restoringRecovery).toBe(false)

      await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)
      await flushPromises()

      expect(api.runsCurrent).toHaveBeenCalled()
      expect(store.restoringRecovery).toBe(true)

      release(noRun())
      await flushPromises()
      expect(store.restoringRecovery).toBe(false)
      store.resetAutoRecovery('first-frame')
    } finally {
      vi.useRealTimers()
    }
  })
})
