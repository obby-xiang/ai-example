/**
 * R04（刷新恢复）：**一次未起轮的 409 发送不算新轮**，不得否决旧历史恢复。
 *
 * <h2>缺陷</h2>
 * `loadHistoryInner` 用 `roundEpoch` 是否变化来判"取历史期间用户已起新轮"。但 `send()` 在
 * **开流之前**就递增纪元：一条撞上 409 SESSION_BUSY 的发送（后端拒绝，本轮**从未开始**）
 * 同样让纪元变化。于是历史回来时整份被当成"过期现场"丢掉 —— 真正的在途轮（刷新恢复要接回的
 * 那一条）既不落盘也不接回，用户看不到「停止」入口，只能再刷新一次。
 *
 * <h2>口径（本文件锁住）</h2>
 * <ol>
 * <li>只有 `loading` / `suspended` / `currentAbort`（未停止时）能证明"新轮真正占场"—— 才拒绝旧历史；</li>
 * <li>409 回执里的 runId 只作**手动**入口（`busyRun`），绝不自动接进本页签（不自动 reattach）；</li>
 * <li>真新轮（loading 在途）照旧受保护：历史不得整体替换屏上本轮。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { useAiStore } from '@/stores/ai'
import type { AiHistory } from '@/types/ai'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve: (value: T) => void = () => undefined
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

function sessionBusy(runId: string): ApiError {
  return new ApiError('该会话已有一轮对话在进行中', 409, ErrorCode.SESSION_BUSY, {
    code: 'SESSION_BUSY',
    runId,
    reattach: `/api/ai/events/${runId}`
  })
}

function runningSnapshot(runId: string) {
  return {
    found: true,
    runId,
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 3
  }
}

function emptySnapshot() {
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

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(emptySnapshot())
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  installIdentityFixture()
})

/**
 * 跨用例收尾：本文件的 chat/reattachRun 桩可能永不结算 ⇒ 模块级在途连接不会自行清空。
 * 换会话 = 丢弃本页签轮次（与 tool-outcome-envelope.spec.ts 同口径）。
 */
afterEach(() => {
  useAiStore().adoptFreshSession()
})

describe('R04：未起轮 409 不否决旧历史恢复', () => {
  it('历史在途时撞 409 ⇒ 历史照常落盘，并把真正的在途轮接回（停止入口在）', async () => {
    const store = useAiStore()
    store.sessionId = 's-r04'
    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    // 409 是"上一轮（本页签刷新前的那条）仍在跑" ⇒ 快照里确有在途轮，恢复要把它接回来
    api.runsCurrent.mockResolvedValue(runningSnapshot('run-a'))
    api.chat.mockRejectedValue(sessionBusy('run-a'))

    const loading = store.loadHistory()
    await flushPromises()

    const result = await store.send('这条会撞 409')
    expect(result.outcome).toBe('error')
    // 本轮从未开始：没有 loading、没有连接、没有挂起
    expect(store.loading).toBe(false)

    history.resolve({
      sessionId: 's-r04',
      count: 1,
      messages: [{ type: 'USER', text: '上一轮的问题' }]
    })
    await loading
    await flushPromises()

    // 历史没有被当成"过期现场"丢掉
    expect(store.messages.some((message) => message.content === '上一轮的问题')).toBe(true)
    expect(store.historyLoaded).toBe(true)
    // 在途轮被接回：读的是本会话权威快照（不是 409 回执里的猜归属）
    expect(api.runsCurrent).toHaveBeenCalledWith('s-r04')
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-a')
    // 停止入口（loading）在 ⇒ 用户能看到「停止」而不是一句"没发出去"后无事可做
    expect(store.loading).toBe(true)
    expect(store.canSend).toBe(false)
  })

  it('409 回执里的别页签 run 只登记为手动入口：不自动接流、不改 activeRunId', async () => {
    const store = useAiStore()
    store.sessionId = 's-r04b'
    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    // 本会话没有在途轮（那条 409 说的是别处的轮）
    api.runsCurrent.mockResolvedValue(emptySnapshot())
    api.chat.mockRejectedValue(sessionBusy('run-other'))

    const loading = store.loadHistory()
    await flushPromises()
    await store.send('这条会撞 409')

    history.resolve({
      sessionId: 's-r04b',
      count: 1,
      messages: [{ type: 'USER', text: '历史里的问题' }]
    })
    await loading
    await flushPromises()

    // 旧历史照常恢复（未被 409 的纪元变化否决）
    expect(store.messages.some((message) => message.content === '历史里的问题')).toBe(true)
    // 但别页签的 run 一个字都不接：不 reattach、不占 activeRunId、不置 loading
    expect(api.reattachRun).not.toHaveBeenCalled()
    expect(store.activeRunId).toBeNull()
    expect(store.loading).toBe(false)
    // 手动入口（点击才过去）仍在
    expect(store.busyRun).toMatchObject({ runId: 'run-other' })
  })

  it('真新轮保护：历史在途时已起新轮（loading 占场）⇒ 历史整体丢弃，不替换屏上本轮', async () => {
    const store = useAiStore()
    store.sessionId = 's-r04c'
    store.historyLoaded = true
    const history = deferred<AiHistory>()
    api.history.mockImplementation(() => history.promise)
    // 新轮真的开着（连接永不结算）
    api.chat.mockImplementation(() => new Promise(() => {}))

    const loading = store.loadHistory()
    await flushPromises()
    void store.send('真正的新一轮')
    await flushPromises()
    const hostId = store.messages.find((message) => message.role === 'assistant')?.id
    expect(store.loading).toBe(true)

    history.resolve({
      sessionId: 's-r04c',
      count: 2,
      messages: [
        { type: 'USER', text: '旧问题' },
        { type: 'ASSISTANT', text: '旧回答' }
      ]
    })
    await loading
    await flushPromises()

    expect(store.messages.some((message) => message.id === hostId)).toBe(true)
    expect(store.messages.some((message) => message.content === '旧回答')).toBe(false)
    // 本轮在途 ⇒ 不去读/接别的轮
    expect(api.runsCurrent).not.toHaveBeenCalled()
    expect(store.loading).toBe(true)
  })
})
