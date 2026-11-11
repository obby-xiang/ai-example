import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import { resetAiIdentityCache, sessionIdStorageKey, writeAiIdentity } from '@/utils/ai-identity'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  getContext: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

describe('响应路径收敛', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    for (const fn of Object.values(api)) {
      fn.mockReset()
    }
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    api.history.mockResolvedValue({ sessionId: 'sess-busy', count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-live',
      snapshotStatus: 'RUNNING',
      pendingEntries: [],
      unresolvedExternal: [],
      awaitingExternal: false,
      archiveMaxSeq: 3
    })
    api.cancel.mockResolvedValue({})
    api.deleteHistory.mockResolvedValue({})
    api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
    const identity = { tenantId: 7, userId: 71 }
    resetAiIdentityCache()
    writeAiIdentity(identity)
    sessionStorage.setItem(sessionIdStorageKey(identity), 'sess-busy')
  })

  afterEach(() => {
    useAiStore().adoptFreshSession()
  })

  it('409 只有 runId 时保留手动重挂且不自动接流', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-busy'
    api.chat.mockRejectedValue(new ApiError('busy', 409, 'SESSION_BUSY', {
      code: 'SESSION_BUSY',
      sessionId: 'sess-busy',
      runId: 'run-live',
      degraded: false
    }))

    await store.send('继续')

    expect(store.busyRun).toEqual({ runId: 'run-live' })
    expect(api.reattachRun).not.toHaveBeenCalled()

    await store.reattachBusyRun()

    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-live')
  })
})
