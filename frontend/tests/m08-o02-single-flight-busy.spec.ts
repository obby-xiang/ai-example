/**
 * M08 / O02 收口（真实 store，不挂面板）：
 *
 * <p>M08：`reattachActive` 与 `resumeSnapshotStream` 会为**同一个 runId** 各起一条 SSE
 * （全量回放 + 增量续收并存）⇒ 双倍连接、双倍重连预算、迟到回调写脏现场。
 * 口径：同一 runId 只有一个活动连接；后到的入口**并入**（不新建连接、不换轮次身份），
 * 锚点统一为已收最大序号；旧连接的回调按 epoch 一律失效。
 *
 * <p>O02：同 tick「停止 + 发送」时，停止必须先落地（本地收敛 + cancel 送达）再判断能否发送/
 * 是否忙态；停止未传播而回弹的 409 是残影，不得登记「回到进行中的回答」；真正在跑的轮 409
 * 照常登记（不吞 SESSION_BUSY）。DONE / CANCELLED / error 一到，忙提示立即作废。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'

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

function fresh() {
  setActivePinia(createPinia())
  return useAiStore()
}

function assistantHost(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '进行中',
    parts: [{ kind: 'text', seg: 0, text: '进行中', streaming: true }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false,
    runId: 'run-1',
    ...overrides
  }
}

/** 409 SESSION_BUSY（runId 可指回的"进行中轮"）。 */
function sessionBusy(runId: string): ApiError {
  return new ApiError('busy', 409, 'SESSION_BUSY', {
    code: 'SESSION_BUSY',
    message: 'busy',
    sessionId: 'sess',
    runId,
    reattach: `/api/ai/events/${runId}`,
    degraded: false
  })
}

interface CapturedHandlers {
  onFrame: (frame: SseFrame) => void
  onReconnect: (attempt: number, delayMs: number) => void
  onClose: (info: { terminal: boolean; suspended?: boolean }) => void
}

function handlersOf(callIndex: number): CapturedHandlers {
  return api.reattachRun.mock.calls[callIndex]?.[1] as CapturedHandlers
}

/** 受控的一次 SSE 生命周期结算（模拟"内部退避重连耗尽后 settle"）。 */
function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve: () => void = () => {}
  const promise = new Promise<void>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  // 连接保持"在跑"（永不结算）：不触发各轮 finally 里的轮次复位
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  })
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
})

describe('M08 · 同一 runId 单飞（single-flight）', () => {
  it('两个恢复入口并发对同一 runId：只建一条 SSE（不双倍连接）', () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'

    // 两个入口在同一 tick 并发：接管（全量回放 lastSeq=0）与增量续收（archiveMaxSeq=7，
    // 无宿主 —— I-1「无卡挂流」路径，与"全量回放"并存就是双倍连接的那一对）
    void store.reattachActive('run-1', { hostMessageId: 'a-1', lastSeq: 0 })
    void store.resumeSnapshotStream('run-1', 7, undefined, false)

    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-1')
  })

  it('并入已有连接：锚点统一为已收最大序号；旧连接的回调按 epoch 全部失效', () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'

    void store.resumeSnapshotStream('run-1', 4, 'a-1', false)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    const first = handlersOf(0)
    first.onFrame({ type: 'delta', runId: 'run-1', seq: 9, text: '（已收）' } as SseFrame)
    expect(store.runSeq['run-1']).toBe(9)

    // 第二个入口想从更小的锚点重来：并入现连接，既不新建连接，也不把锚点退回去
    void store.reattachActive('run-1', { hostMessageId: 'a-1', lastSeq: 0 })
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(store.runSeq['run-1']).toBe(9)

    // 换一轮接管（run-2）：旧连接此后收尾，其回调一律不得再写现场
    void store.reattachActive('run-2', { hostMessageId: 'a-1', lastSeq: 0 })
    expect(api.reattachRun).toHaveBeenCalledTimes(2)
    first.onFrame({ type: 'delta', runId: 'run-1', seq: 10, text: '（迟到）' } as SseFrame)
    first.onReconnect(2, 1000)
    first.onClose({ terminal: false, suspended: false })

    expect(store.messages[0]?.content ?? '').not.toContain('迟到')
    expect(store.streamInterrupted).toBe(false)
    expect(store.notice).toBeNull()
  })
})

describe('O02 · 停止 + 发送（同 tick）与忙提示', () => {
  it('同 tick 停止+发送：停止先生效，停止残影的 409 不再显示忙提示（失败仍可见）', async () => {
    const store = fresh()
    store.sessionId = 'sess'
    store.historyLoaded = true
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    store.loading = true
    // 停止尚未传播：/chat 仍回 409，并指回刚被停止的那一轮
    api.chat.mockRejectedValue(sessionBusy('run-1'))
    let releaseCancel: (() => void) | null = null
    api.cancel.mockImplementation(() => new Promise((resolve) => {
      releaseCancel = () => resolve({})
    }))

    const stopping = store.stop()
    const sending = store.send('接着问')
    await Promise.resolve()

    // 停止先生效：cancel 还没落地，先不发
    expect(api.chat).not.toHaveBeenCalled()
    expect(store.busyRun).toBeNull()

    releaseCancel!()
    const result = await sending
    await stopping

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(result.outcome).toBe('error')
    // 残影 409：不登记忙提示（面板因此不出「回到进行中的回答」）
    expect(store.busyRun).toBeNull()
    // 但这次确实没发出去：真实失败仍然可见（不吞 409）
    expect(store.noticeCode).toBe('ai.notice.sendFailed')
  })

  it('真实活动轮（未被停止）409：忙提示照常登记 —— 不吞真正的 SESSION_BUSY', async () => {
    const store = fresh()
    store.sessionId = 'sess'
    api.chat.mockRejectedValue(sessionBusy('run-live'))

    await store.send('继续')

    expect(store.busyRun).toMatchObject({ runId: 'run-live', reattach: '/api/ai/events/run-live' })
    expect(store.noticeCode).toBe('ai.notice.sendFailed')

    // 停止过 A 轮之后，指回**另一**活动轮的 409 仍是权威忙态
    const other = fresh()
    other.sessionId = 'sess'
    other.messages = [assistantHost()]
    other.activeRunId = 'run-1'
    other.loading = true
    await other.stop()
    api.chat.mockRejectedValue(sessionBusy('run-live'))

    await other.send('继续')

    expect(other.busyRun).toMatchObject({ runId: 'run-live' })
  })

  it('DONE 帧到达：busyRun 立即清空（不再提供「回到进行中的回答」）', () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    store.busyRun = { runId: 'run-1', reattach: null }

    store.handleFrame({ type: 'done', runId: 'run-1', seq: 12 } as SseFrame)

    expect(store.busyRun).toBeNull()
  })

  it('CANCELLED（done.cancelled）帧到达：busyRun 立即清空', () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    store.busyRun = { runId: 'run-1', reattach: null }

    store.handleFrame({ type: 'done', runId: 'run-1', seq: 12, cancelled: true } as SseFrame)

    expect(store.busyRun).toBeNull()
  })

  it('error 帧到达：busyRun 立即清空', () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    store.busyRun = { runId: 'run-1', reattach: null }

    store.handleFrame({ type: 'error', runId: 'run-1', seq: 12, code: 'UPSTREAM_ERROR', message: 'boom' } as SseFrame)

    expect(store.busyRun).toBeNull()
  })
})

describe('M08-R2 · 驱动所有权与重试预算按 runId 固化（跨连接存活期）', () => {
  it('R2：一次中断生命周期后另一入口不再另起驱动，总连接不超原预算', async () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    let handlers: CapturedHandlers | null = null
    const lifecycle = deferred()
    api.reattachRun.mockImplementation((_runId: string, captured: CapturedHandlers) => {
      handlers = captured
      return lifecycle.promise
    })

    const driving = store.reattachActive('run-1', { hostMessageId: 'a-1', lastSeq: 0 })
    // 真机形态：内部退避重连若干次（仍属同一次 reattachRun 的预算）后依旧断流
    handlers!.onReconnect(1, 400)
    handlers!.onReconnect(4, 3200)
    handlers!.onClose({ terminal: false })
    lifecycle.resolve()
    await driving
    expect(api.reattachRun).toHaveBeenCalledTimes(1)

    // 同一 run 的另一个恢复入口（下一拍）不得另起一条驱动：总连接仍是一次生命周期
    void store.resumeSnapshotStream('run-1', 0, 'a-1', false)
    void store.reattachActive('run-1', { hostMessageId: 'a-1', lastSeq: 0 })
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
  })

  it('R2：正常挂起收尾不消耗预算，后续恢复入口仍可续收（接管语义不破）', async () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    let handlers: CapturedHandlers | null = null
    const lifecycle = deferred()
    api.reattachRun.mockImplementation((_runId: string, captured: CapturedHandlers) => {
      handlers = captured
      return lifecycle.promise
    })

    const driving = store.resumeSnapshotStream('run-1', 4, 'a-1', true)
    // 挂起即断：服务端送达请求帧后主动收流 —— 正常收尾，不是断流
    handlers!.onClose({ terminal: false, suspended: true })
    lifecycle.resolve()
    await driving
    expect(api.reattachRun).toHaveBeenCalledTimes(1)

    // 刷新恢复再来一次（卡还在等人）：必须能续收，不被"预算"锁死
    void store.resumeSnapshotStream('run-1', 4, 'a-1', true)
    expect(api.reattachRun).toHaveBeenCalledTimes(2)
  })

  it('R2：显式重启后可重新驱动，且锚点为已收最大序号（lastSeq 一致）', async () => {
    const store = fresh()
    store.messages = [assistantHost()]
    store.activeRunId = 'run-1'
    let handlers: CapturedHandlers | null = null
    const lifecycle = deferred()
    api.reattachRun.mockImplementation((_runId: string, captured: CapturedHandlers) => {
      handlers = captured
      return lifecycle.promise
    })

    const driving = store.reattachActive('run-1', { hostMessageId: 'a-1', lastSeq: 0 })
    handlers!.onFrame({ type: 'delta', runId: 'run-1', seq: 9, text: '（已收）' } as SseFrame)
    handlers!.onClose({ terminal: false })
    lifecycle.resolve()
    await driving
    expect(store.runSeq['run-1']).toBe(9)

    // 自动入口到此为止：同一 run 不再自动重开（预算不翻倍）
    void store.resumeSnapshotStream('run-1', 0, 'a-1', false)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)

    // 显式动作（决策送达 / 用户重试 / 回到进行中的回答）重新接管：锚点取已收最大序号
    store.restartRunStreamDrive('run-1')
    void store.reattachActive('run-1', { hostMessageId: 'a-1' })
    expect(api.reattachRun).toHaveBeenCalledTimes(2)
    expect((api.reattachRun.mock.calls[1]?.[2] as { lastSeq?: number } | undefined)?.lastSeq).toBe(9)
  })
})
