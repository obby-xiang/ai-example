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
  resumeRun: vi.fn()
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

    await store.restoreSuspendedSnapshot(() => true)

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

  it('恢复读取失败保留本地待决锁和部分正文现场', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-recovery-fail'
    store.historyLoaded = true
    const host = store.appendAssistantMessage('失败前的正文')
    host.runId = 'run-fail'
    host.done = false
    host.streaming = true

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

    expect(store.suspended).toBe(true)
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(host.parts.find((part) => part.kind === 'tool')).toMatchObject({
      status: 'pending',
      requiresAction: true
    })
    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(host.recoveryGap).toBe(true)
    expect(api.cancel).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('历史保存降级先补写再对账，只有健康 DONE 清除提示', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-transcript-recovery'
    store.historyLoaded = true
    const host = historicalHost()
    host.code = 'AI_TRANSCRIPT_INCOMPLETE'
    host.errorCard = buildErrorCard('AI_TRANSCRIPT_INCOMPLETE', 'incomplete')
    store.lastRoundOutcome = 'error'
    store.messages = [host]
    const calls: string[] = []
    let finishResume: (() => void) | undefined
    api.resumeRun.mockImplementation(() => {
      calls.push('resume')
      return new Promise((resolve) => {
        finishResume = () => resolve({ resumed: true, historyComplete: true, reason: 'TRANSCRIPT_RECOVERED' })
      })
    })
    api.history.mockImplementation(async () => {
      calls.push('history')
      return {
        sessionId: store.sessionId,
        count: 1,
        messages: [{
          type: 'ASSISTANT',
          text: '已有正文',
          runId: 'run-recovery',
          outcome: 'DONE',
          segments: [{ kind: 'text', text: '已有正文', seq: 1 }]
        }]
      }
    })

    store.publishTranscriptDegradedRecovery()
    store.recoverTranscriptDegradedTerminal(host)
    store.recoverTranscriptDegradedTerminal(host)
    expect(store.manualRecovery).toMatchObject({ reason: 'transcript-degraded' })
    expect(host.transcriptDegraded).toBe(true)
    expect(host.code).toBe('AI_TRANSCRIPT_INCOMPLETE')
    expect(host.errorCard).not.toBeNull()
    expect(store.lastRoundOutcome).toBe('error')
    await vi.waitFor(() => {
      expect(calls).toEqual(['resume'])
    })
    expect(store.manualRecovery).toMatchObject({ reason: 'transcript-degraded' })
    expect(host.transcriptDegraded).toBe(true)
    expect(api.resumeRun).toHaveBeenCalledTimes(1)
    finishResume?.()
    await vi.waitFor(() => {
      expect(calls).toEqual(['resume', 'history'])
    })
    await vi.waitFor(() => {
      expect(store.manualRecovery).toBeNull()
    })

    expect(calls).toEqual(['resume', 'history'])
    expect(api.resumeRun).toHaveBeenCalledWith('run-recovery')
    expect(store.messages.find((item) => item.runId === 'run-recovery')?.transcriptDegraded).toBeUndefined()
  })

  it('历史补写后复读仍降级时保留提示和错误现场', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-transcript-recovery-failed'
    store.historyLoaded = true
    const host = historicalHost()
    store.messages = [host]
    let finishResume: (() => void) | undefined
    api.resumeRun.mockImplementation(() => new Promise((resolve) => {
      finishResume = () => resolve({ resumed: false, historyComplete: false, reason: 'ALREADY_DONE' })
    }))
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 1,
      messages: [{
        type: 'ASSISTANT',
        text: '已有正文',
        runId: 'run-recovery',
        outcome: 'DONE',
        transcriptDegraded: true,
        errorCode: 'AI_RUN_FAILED',
        errorMessage: 'failed',
        segments: [{ kind: 'text', text: '已有正文', seq: 1 }]
      }]
    })

    store.publishTranscriptDegradedRecovery()
    store.recoverTranscriptDegradedTerminal(host)
    await vi.waitFor(() => {
      expect(api.resumeRun).toHaveBeenCalledTimes(1)
    })
    expect(store.manualRecovery).toMatchObject({ reason: 'transcript-degraded' })
    expect(host.transcriptDegraded).toBe(true)
    finishResume?.()
    await vi.waitFor(() => {
      expect(api.resumeRun).toHaveBeenCalledTimes(2)
      expect(store.manualRecovery).toMatchObject({ reason: 'transcript-degraded' })
    }, { timeout: 3000 })

    const degraded = store.messages.find((item) => item.runId === 'run-recovery')
    expect(degraded?.transcriptDegraded).toBe(true)
    expect(degraded?.code).toBe('AI_RUN_FAILED')
    expect(degraded?.errorCard).not.toBeNull()
  })
})
