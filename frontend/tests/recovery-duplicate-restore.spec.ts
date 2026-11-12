import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { AiRunCurrent, AiRunCurrentEntry } from '@/types/ai'

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

const RUN_ID = 'run-c163'

function pendingEntry(): AiRunCurrentEntry {
  return {
    toolCallId: 'nav-c163',
    name: 'navigate_to',
    kind: 'FRONTEND',
    entryStatus: 'PENDING',
    req: true,
    arguments: '{"page":"export"}'
  }
}

function snapshot(): AiRunCurrent {
  return {
    found: true,
    runId: RUN_ID,
    snapshotStatus: 'SUSPENDED',
    awaitingExternal: true,
    archiveMaxSeq: 7,
    pendingEntries: [pendingEntry()],
    unresolvedExternal: []
  }
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'sess-c163', count: 0, messages: [] })
  api.runsCurrent.mockImplementation(() => Promise.resolve(snapshot()))
  api.reattachRun.mockImplementation((_runId: string, handlers: { onClose: (info: { terminal: boolean; suspended: boolean }) => void }) => {
    handlers.onClose({ terminal: false, suspended: true })
    return Promise.resolve()
  })
  installIdentityFixture()
})

describe('C-163 · park 收流后的重复快照恢复', () => {
  it('同 session/run/anchor/pending 签名只恢复一次；anchor 与决策变化仍允许新连接', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-c163'
    const reattachActive = vi.spyOn(store, 'reattachActive').mockResolvedValue(undefined)
    const businessAttempts = vi.spyOn(store, 'canRunFrontendToolAttempt').mockResolvedValue(true)
    const current = snapshot()
    api.runsCurrent.mockImplementation(() => Promise.resolve(current))

    await store.restoreSuspendedSnapshot(() => true)
    await store.restoreSuspendedSnapshot(() => true)

    const evidence = {
      lastSeq: api.reattachRun.mock.calls.map((call) => call[2]?.lastSeq),
      probeCount: api.runsCurrent.mock.calls.length,
      resumeSnapshotStreamCount: api.reattachRun.mock.calls.length,
      reattachActiveCount: reattachActive.mock.calls.length,
      awaitingInput: api.reattachRun.mock.calls.map((call) => call[2]?.awaitingInput),
      businessAttemptCount: businessAttempts.mock.calls.length
    }
    expect(evidence).toEqual({
      lastSeq: [7],
      probeCount: 2,
      resumeSnapshotStreamCount: 1,
      reattachActiveCount: 0,
      awaitingInput: [true],
      businessAttemptCount: 1
    })

    current.archiveMaxSeq = 8
    store.runSeq[RUN_ID] = 8
    await store.restoreSuspendedSnapshot(() => true)
    expect(api.reattachRun.mock.calls.map((call) => call[2]?.lastSeq)).toEqual([7, 8])
    expect(businessAttempts).toHaveBeenCalledTimes(2)

    current.pendingEntries = []
    current.awaitingExternal = false
    await store.restoreSuspendedSnapshot(() => true)
    expect(reattachActive).toHaveBeenCalledTimes(1)
    expect(reattachActive.mock.calls[0]?.[1]).toMatchObject({ lastSeq: 8 })
  })

  it('显式用户动作清空同快照去重，允许重新接管', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-c163'
    const businessAttempts = vi.spyOn(store, 'canRunFrontendToolAttempt').mockResolvedValue(true)

    await store.restoreSuspendedSnapshot(() => true)
    store.restartRunStreamDrive(RUN_ID)
    await store.restoreSuspendedSnapshot(() => true)

    expect(api.reattachRun).toHaveBeenCalledTimes(2)
    expect(businessAttempts).toHaveBeenCalledTimes(1)
  })
})
