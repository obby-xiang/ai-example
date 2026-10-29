import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore, PAGE_ID_KEY } from '@/stores/ai'
import { mirrorStorageKey, sessionLockName } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import { buildErrorCard } from '@/utils/error-card'
import type { AiRunCurrent, AiRunCurrentEntry, ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { channels, FakeBroadcastChannel, FakeLockManager, seedTab } from './tab-ownership-fixtures'

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

function pendingEntry(
  toolCallId: string,
  kind: AiRunCurrentEntry['kind'],
  name: string,
  req = true
): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind, name, arguments: '{}', req }
}

function snapshot(entries: AiRunCurrentEntry[]): AiRunCurrent {
  return {
    found: true,
    runId: 'run-recovery',
    snapshotStatus: 'SUSPENDED',
    pendingEntries: entries,
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 7
  }
}

function historicalHost(): ChatMessage {
  return {
    id: 'a-recovery',
    role: 'assistant',
    content: '已有正文',
    parts: [{ kind: 'text', seg: 0, text: '已有正文', streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    runId: 'run-recovery'
  }
}

let locks: FakeLockManager

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
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0 })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('AI store 恢复与重试收口', () => {
  it('复制页签换会话但保持静默，仍清理继承现场并保留原锁', async () => {
    seedTab('9001')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-copy-silent')
    locks.hold(sessionLockName('9001'))
    const store = useAiStore()

    await store.ensureTabOwnership()

    // 复制品先换新会话：后端发号成功 ⇒ 本页签成为自己新会话的 owner（旧会话锁仍归原页签）。
    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).not.toBe('9001')
    expect(store.notice).toBeNull()
    expect(store.noticeCode).toBeNull()
    expect(sessionStorage.getItem(mirrorStorageKey('9001'))).toBeNull()
    expect(locks.isHeld(sessionLockName('9001'))).toBe(true)
    expect(locks.isHeld(sessionLockName(store.sessionId))).toBe(true)
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })

  it('自动工具与未发卡条目不伪造挂起，续流恢复宿主活动事实并由 done 收口', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-recovery'
    store.historyLoaded = true
    const host = historicalHost()
    store.messages = [host]
    api.runsCurrent.mockResolvedValue(snapshot([
      pendingEntry('auto-1', 'FRONTEND', 'navigate_to'),
      pendingEntry('backend-1', 'BACKEND', 'read_tasks'),
      pendingEntry('later-1', 'CONFIRM', 'start_export', false)
    ]))

    await store.restoreSuspendedSnapshot()

    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull()
    expect(host.parts.filter((part) => part.kind === 'tool')).toHaveLength(0)
    expect(host.streaming).toBe(true)
    expect(host.done).toBe(false)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 7, awaitingInput: false })

    const handlers = api.reattachRun.mock.calls[0]?.[1] as { onFrame: (frame: SseFrame) => void }
    handlers.onFrame({ type: 'done', runId: 'run-recovery', seq: 8 } as SseFrame)
    expect(host.streaming).toBe(false)
    expect(host.done).toBe(true)
    expect(store.suspended).toBe(false)
  })

  it('缺少正序号锚点拒绝重试并保留失败现场，恢复失败清掉本地待决锁', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-retry-guard'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('失败前的正文')
    host.runId = 'run-fail'
    host.done = true
    host.streaming = false
    host.errorCard = buildErrorCard('EMPTY_REPLY', '保留失败现场')
    store.lastRoundOutcome = 'error'
    api.retryModelStep.mockResolvedValue({ retried: true })

    const outcome = await store.retryModelStep(host)

    expect(outcome).toBe('denied')
    expect(store.noticeCode).toBe('ai.notice.retryRunUnknown')
    expect(api.retryModelStep).not.toHaveBeenCalled()
    expect(api.reattachRun).not.toHaveBeenCalled()
    expect(host.errorCard?.code).toBe('EMPTY_REPLY')
    expect(host.content).toBe('失败前的正文')
    expect(store.lastRoundOutcome).toBe('error')

    store.upsertToolPart(host, {
      toolCallId: 'confirm-recovery',
      name: 'start_export',
      kind2: 'CONFIRM',
      status: 'pending',
      args: '{}',
      requiresAction: true
    })
    store.suspended = true
    store.sealRecovery('current', new ApiError('snapshot down', 503, 'AI_REDIS_UNAVAILABLE', null))

    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull()
    expect(host.parts.find((part) => part.kind === 'tool')).toMatchObject({
      status: 'expired',
      requiresAction: false
    })
    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(api.cancel).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})
