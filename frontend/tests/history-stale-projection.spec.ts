import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { AiHistoryMessage } from '@/types/ai'

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

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK' })
  api.runsCurrent.mockResolvedValue({ found: false })
  api.getContext.mockResolvedValue({ source: 'none' })
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('C-167 · 过期 history 响应的原子提交', () => {
  it('A 慢响应在 B 公开分页 action 完成后到达：整包不得覆盖 B 的投影与恢复现场', async () => {
    const oldEntry: AiHistoryMessage = { type: 'USER', text: 'old', runId: 'run-old' }
    const nextEntry: AiHistoryMessage = { type: 'USER', text: 'next', runId: 'run-next' }
    const staleEntry: AiHistoryMessage = { type: 'USER', text: 'stale', runId: 'run-stale' }
    const slow = deferred<unknown>()
    api.history.mockImplementation((_sid: string, page: { before?: string }) => page.before === '5'
      ? Promise.resolve({
          total: 10, hasMore: true, nextCursor: '4', revision: 2,
          events: [{ eventId: 'event-next', runId: 'run-next' }], messages: [nextEntry]
        })
      : slow.promise)
    const store = useAiStore()
    store.sessionId = 'sess-stale-projection'
    store.historyTotal = 10
    store.historyOffset = 5
    store.historyHasMore = true
    store.historyNextCursor = '5'
    store.historyRevision = 1
    store.historyEvents = [{ eventId: 'event-old', runId: 'run-old' }]
    store.historyWindowEntries = [oldEntry]
    store.historyLoaded = false
    store.historyError = { scope: 'history', code: 'KEEP', status: 500, at: 1 }
    store.recoveryGap = { reason: 'history-unavailable', fromSeq: 2, toSeq: 4, at: 1 }

    const stale = store.loadHistory()
    await Promise.resolve()
    await store.loadEarlierHistory()
    expect(store.historyOffset).toBe(4)
    expect(store.historyNextCursor).toBe('4')
    expect(store.historyRevision).toBe(2)
    expect(store.historyWindowEntries).toEqual([nextEntry, oldEntry])

    slow.resolve({
      total: 999, hasMore: true, nextCursor: '999', revision: 99,
      events: [{ eventId: 'event-stale', runId: 'run-stale' }], messages: [staleEntry]
    })
    await stale

    expect(store.historyTotal).toBe(10)
    expect(store.historyOffset).toBe(4)
    expect(store.historyHasMore).toBe(true)
    expect(store.historyNextCursor).toBe('4')
    expect(store.historyRevision).toBe(2)
    expect(store.historyEvents).toEqual([{ eventId: 'event-next', runId: 'run-next' }])
    expect(store.historyWindowEntries).toEqual([nextEntry, oldEntry])
    expect(store.historyLoaded).toBe(false)
    expect(store.historyError).toEqual({ scope: 'history', code: 'KEEP', status: 500, at: 1 })
    expect(store.recoveryGap).toEqual({ reason: 'history-unavailable', fromSeq: 2, toSeq: 4, at: 1 })
  })

  it('等待期间登记新缺口：慢响应不得清除缺口或提交历史投影', async () => {
    const oldEntry: AiHistoryMessage = { type: 'USER', text: 'old', runId: 'run-old' }
    const staleEntry: AiHistoryMessage = { type: 'USER', text: 'stale', runId: 'run-stale' }
    const slow = deferred<unknown>()
    api.history.mockReturnValue(slow.promise)
    const store = useAiStore()
    store.sessionId = 'sess-stale-new-gap'
    store.historyTotal = 10
    store.historyOffset = 5
    store.historyHasMore = true
    store.historyNextCursor = '5'
    store.historyRevision = 1
    store.historyEvents = [{ eventId: 'event-old', runId: 'run-old' }]
    store.historyWindowEntries = [oldEntry]
    store.historyLoaded = false
    store.historyError = { scope: 'history', code: 'KEEP', status: 500, at: 1 }
    store.recoveryGap = null

    const stale = store.loadHistory()
    await Promise.resolve()
    store.markRecoveryGap('history-unavailable', { fromSeq: 2, toSeq: 4 })
    const newGap = store.recoveryGap
    slow.resolve({
      total: 999, hasMore: true, nextCursor: '999', revision: 99,
      events: [{ eventId: 'event-stale', runId: 'run-stale' }], messages: [staleEntry]
    })
    await stale

    expect(store.recoveryGap).toEqual(newGap)
    expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable', fromSeq: 2, toSeq: 4 })
    expect(store.historyTotal).toBe(10)
    expect(store.historyOffset).toBe(5)
    expect(store.historyHasMore).toBe(true)
    expect(store.historyNextCursor).toBe('5')
    expect(store.historyRevision).toBe(1)
    expect(store.historyEvents).toEqual([{ eventId: 'event-old', runId: 'run-old' }])
    expect(store.historyWindowEntries).toEqual([oldEntry])
    expect(store.historyLoaded).toBe(false)
    expect(store.historyError).toEqual({ scope: 'history', code: 'KEEP', status: 500, at: 1 })
  })
})
