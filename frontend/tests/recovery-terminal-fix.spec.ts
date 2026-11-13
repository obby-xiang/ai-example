import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import {
  AUTO_RECOVERY_DELAY_MS,
  AUTO_RECOVERY_MAX_ATTEMPTS,
  useAiStore
} from '@/stores/ai'
import { mirrorStorageKey } from '@/utils/ai-identity'
import type { ActiveGenerativeForm, AiRunCurrent, AiRunCurrentEntry, ChatMessage } from '@/types/ai'
import { installIdentityFixture } from './ai-identity-fixture'

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

const sse = vi.hoisted(() => ({ probeReattach: vi.fn() }))
vi.mock('@/api/sse', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sse')>()),
  probeReattach: sse.probeReattach
}))

function entry(status: AiRunCurrentEntry['entryStatus']): AiRunCurrentEntry {
  return {
    toolCallId: 'confirm-1',
    entryStatus: status,
    kind: 'CONFIRM',
    name: 'start_export',
    arguments: '{}',
    req: true
  }
}

function snapshot(
  status: AiRunCurrentEntry['entryStatus'],
  snapshotStatus: AiRunCurrent['snapshotStatus'] = 'SUSPENDED'
): AiRunCurrent {
  return {
    found: true,
    runId: 'run-1',
    snapshotStatus,
    pendingEntries: [entry(status)],
    unresolvedExternal: [],
    awaitingExternal: snapshotStatus === 'SUSPENDED',
    archiveMaxSeq: 4
  }
}

function seed(runStatus: 'SUSPENDED' | 'RUNNING'): { store: ReturnType<typeof useAiStore>; host: ChatMessage } {
  const store = useAiStore()
  store.sessionId = 'sess-recovery-terminal'
  store.historyLoaded = true
  const host = store.appendAssistantMessage('已收到的部分正文')
  host.runId = 'run-1'
  host.streaming = true
  host.done = false
  store.activeRunId = 'run-1'
  store.upsertToolPart(host, {
    toolCallId: 'confirm-1',
    name: 'start_export',
    kind2: 'CONFIRM',
    status: 'pending',
    args: '{}',
    requiresAction: true
  })
  store.activeForm = {
    toolCallId: 'form-1',
    runId: 'run-1',
    form: {} as never,
    status: 'filling',
    error: null,
    values: { kept: true },
    fieldErrors: []
  } as ActiveGenerativeForm
  store.upsertToolPart(host, {
    toolCallId: 'preview-1',
    name: 'replace_values',
    kind2: 'FRONTEND',
    status: 'pending',
    args: '{}',
    requiresAction: true
  })
  store.activePreview = { toolCallId: 'preview-1' } as never
  store.loading = runStatus === 'RUNNING'
  store.suspended = runStatus === 'SUSPENDED'
  store.writeMirrorNow()
  return { store, host }
}

function pendingPart(host: ChatMessage) {
  return host.parts.find((part) => part.kind === 'tool' && part.toolCallId === 'confirm-1')
}

function mirror() {
  return JSON.parse(sessionStorage.getItem(mirrorStorageKey('sess-recovery-terminal')) ?? '{}') as {
    inFlight?: boolean
    messages?: ChatMessage[]
  }
}

function unavailable(): ApiError {
  return new ApiError('service unavailable', 503, 'AI_REDIS_UNAVAILABLE', null)
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  sse.probeReattach.mockReset()
  sse.probeReattach.mockResolvedValue({ status: 404, preparing: null })
  api.history.mockResolvedValue({ sessionId: 'sess-recovery-terminal', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0 })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('恢复读取失败不伪造业务终态', () => {
  it('C-154：current 503 保留 HITL 与镜像，恢复 200+PENDING 后仍待人工且不自动批准', async () => {
    const { store, host } = seed('SUSPENDED')
    api.runsCurrent.mockRejectedValueOnce(unavailable())

    await store.restoreSnapshotGuarded()

    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(host.recoveryGap).toBe(true)
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(store.suspended).toBe(true)
    expect(store.activeForm?.values).toEqual({ kept: true })
    expect(store.activePreview).toMatchObject({ toolCallId: 'preview-1' })
    expect(mirror().inFlight).toBe(true)
    expect(pendingPart(mirror().messages?.[0] as ChatMessage)).toMatchObject({ status: 'pending', requiresAction: true })

    api.runsCurrent.mockResolvedValue(snapshot('PENDING'))
    await store.retryRestoreSnapshot()

    expect(store.currentError).toBeNull()
    expect(store.manualRecovery).toBeNull()
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(store.suspended).toBe(true)
    expect(api.confirm).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('C-155：current 503 保留 RUNNING 部分正文，故障解除后由原恢复路径续收', async () => {
    const { store, host } = seed('RUNNING')
    api.runsCurrent.mockRejectedValueOnce(unavailable())

    await store.restoreSnapshotGuarded()

    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(host.recoveryGap).toBe(true)
    expect(host).toMatchObject({ content: '已收到的部分正文', done: false, streaming: true })
    expect(store.loading).toBe(true)
    expect(mirror().inFlight).toBe(true)

    api.runsCurrent.mockResolvedValue(snapshot('PENDING', 'RUNNING'))
    await store.retryRestoreSnapshot()

    expect(store.currentError).toBeNull()
    expect(host).toMatchObject({ content: '已收到的部分正文', done: false, streaming: true })
    expect(store.loading).toBe(true)
    expect(api.reattachRun).toHaveBeenCalledWith('run-1', expect.any(Object), expect.any(Object))
  })

  it('持续 503 至自动恢复预算耗尽仍保留 pending/inFlight，不执行终局清理', async () => {
    vi.useFakeTimers()
    const { store } = seed('SUSPENDED')
    api.history.mockRejectedValue(unavailable())
    api.runsCurrent.mockResolvedValue(snapshot('PENDING'))

    await store.loadHistory()
    await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS * (AUTO_RECOVERY_MAX_ATTEMPTS + 1))

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(api.history).toHaveBeenCalledTimes(1 + AUTO_RECOVERY_MAX_ATTEMPTS)
    expect(store.manualRecovery).toMatchObject({ reason: 'load', scope: 'history' })
    expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable' })
    expect(host.recoveryGap).toBe(true)
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(store.suspended).toBe(true)
    expect(store.activeForm?.values).toEqual({ kept: true })
    expect(store.activePreview).toMatchObject({ toolCallId: 'preview-1' })
    expect(mirror().inFlight).toBe(true)
  })

  it('真实 TIMEOUT 条目仍由权威对账收口为 expired', async () => {
    const store = useAiStore()
    store.sessionId = 'sess-recovery-terminal'
    api.history.mockResolvedValue({
      sessionId: 'sess-recovery-terminal',
      count: 1,
      messages: [{
        type: 'ASSISTANT',
        text: '历史确认轮',
        runId: 'run-timeout',
        toolCalls: [{ id: 'confirm-1', type: 'function', name: 'start_export', arguments: '{}' }],
        toolCallStates: { 'confirm-1': { status: 'TIMEOUT', kind: 'CONFIRM', decision: 'timeout' } },
        segments: [{ seq: 1, kind: 'tool', toolCallId: 'confirm-1' }]
      }]
    })

    await store.loadHistory()

    const host = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    expect(pendingPart(host)).toMatchObject({ status: 'expired', requiresAction: false })
    expect(store.pendingToolCall).toBeNull()
    expect(store.suspended).toBe(false)
    expect(api.confirm).not.toHaveBeenCalled()
  })

  it('权威 DONE 终态不被残留 PENDING 复活，宿主和待决段照常终局清理', async () => {
    const { store, host } = seed('SUSPENDED')
    api.runsCurrent.mockResolvedValue(snapshot('PENDING', 'DONE'))

    await store.restoreSuspendedSnapshot(() => true)

    expect(host).toMatchObject({ done: true, streaming: false })
    expect(pendingPart(host)).toMatchObject({ status: 'expired', requiresAction: false })
    expect(store.suspended).toBe(false)
    expect(store.activeForm).toBeNull()
    expect(store.activePreview).toBeNull()
  })
})


function refreshedSeed(runStatus: 'SUSPENDED' | 'RUNNING') {
  const seeded = seed(runStatus)
  seeded.store.loading = false
  seeded.store.suspended = false
  return seeded
}

function historyAssistant(outcome: string | null, runId = 'run-1') {
  const status = outcome === 'DONE' ? 'EXECUTED' : outcome === 'TIMEOUT' ? 'TIMEOUT' : 'PENDING'
  return {
    type: 'ASSISTANT',
    text: outcome === null ? '当前未决正文' : '历史终态正文',
    runId,
    ...(outcome === null ? {} : { outcome }),
    timestamp: 1_700_000_000_000,
    toolCalls: [{ id: 'confirm-1', type: 'function', name: 'start_export', arguments: '{}' }],
    toolCallStates: {
      'confirm-1': {
        status,
        kind: 'CONFIRM',
        ...(outcome === 'TIMEOUT' ? { decision: 'timeout' } : {})
      }
    },
    segments: [
      { seq: 0, kind: 'text', text: outcome === null ? '当前未决正文' : '历史终态正文' },
      { seq: 1, kind: 'tool', toolCallId: 'confirm-1' }
    ]
  }
}

describe('loadHistory 完整组合恢复', () => {
  it('空 history + current found=false + probe 200 RUNNING 不过期 pending', async () => {
    const { store } = refreshedSeed('RUNNING')
    sse.probeReattach.mockResolvedValue({ status: 200, preparing: null, snapshotStatus: 'RUNNING' })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(mirror().inFlight).toBe(true)
  })

  it('空 history + current found=false + probe 200 SUSPENDED 不过期 pending', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    sse.probeReattach.mockResolvedValue({ status: 200, preparing: null, snapshotStatus: 'SUSPENDED' })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(mirror().inFlight).toBe(true)
  })

  it('空 history + current found=false + probe 503 保留 pending 和恢复缺口', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    sse.probeReattach.mockResolvedValue({ status: 503, preparing: null })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(host.recoveryGap).toBe(true)
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(mirror().inFlight).toBe(true)
  })

  it('空 history + current found=false + probe 确认无活动仍按原路径收口', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    // 本对照只验原有无活动收口，不把表单/预览的在途事实混入判据。
    store.activeForm = null
    store.activePreview = null
    store.suspended = true
    store.writeMirrorNow()
    store.suspended = false
    sse.probeReattach.mockResolvedValue({ status: 404, preparing: null })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(pendingPart(host)).toMatchObject({ status: 'expired', requiresAction: false })
    expect(host).toMatchObject({ done: true, streaming: false, interrupted: true })
    expect(mirror().inFlight).toBe(true)
  })

  it('history 503 + current DONE 由权威终态优先收口且保留 history 错误台账', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    api.history.mockRejectedValue(unavailable())
    api.runsCurrent.mockResolvedValue(snapshot('PENDING', 'DONE'))

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(store.historyError).toMatchObject({ scope: 'history', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable' })
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(pendingPart(host)).toMatchObject({ status: 'expired', requiresAction: false })
    expect(store.currentError).toBeNull()
    expect(mirror().inFlight).toBe(false)
  })

  it('先 current 503 后 history DONE 仍提交终态且 mirror.inFlight=false', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    api.runsCurrent.mockRejectedValue(unavailable())
    await store.restoreSnapshotGuarded()
    expect(store.currentError).toMatchObject({ status: 503 })
    api.history.mockResolvedValue({
      sessionId: 'sess-recovery-terminal',
      count: 1,
      messages: [historyAssistant('DONE')]
    })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(pendingPart(host)).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(mirror().inFlight).toBe(false)
  })

  it('current 503 + history TIMEOUT 不被错误返回挡住且工具正常过期', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    api.runsCurrent.mockRejectedValue(unavailable())
    api.history.mockResolvedValue({
      sessionId: 'sess-recovery-terminal',
      count: 1,
      messages: [historyAssistant('TIMEOUT')]
    })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(pendingPart(host)).toMatchObject({ status: 'expired', requiresAction: false })
    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(mirror().inFlight).toBe(false)
  })

  it('历史 DONE 与当前未决混合时只保留当前 pending，不让旧终态清理新现场', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    api.runsCurrent.mockRejectedValue(unavailable())
    api.history.mockResolvedValue({
      sessionId: 'sess-recovery-terminal',
      count: 2,
      messages: [historyAssistant('DONE', 'run-old'), historyAssistant(null, 'run-1')]
    })

    await store.loadHistory()

    const previous = store.messages.find((message) => message.runId === 'run-old') as ChatMessage
    const current = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(previous).toMatchObject({ done: true, streaming: false })
    expect(current).toMatchObject({ done: false, streaming: true })
    expect(pendingPart(current)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(store.activeForm?.values).toEqual({ kept: true })
    expect(store.activePreview).toMatchObject({ toolCallId: 'preview-1' })
    expect(mirror().inFlight).toBe(true)
  })

  it('取消仍清理表格预览、表单和待决段，镜像不留在途', async () => {
    const { store, host } = seed('SUSPENDED')

    await store.stop()

    expect(host).toMatchObject({ done: true, streaming: false, stopped: true })
    expect(pendingPart(host)).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(store.activePreview).toBeNull()
    expect(store.activeForm).toBeNull()
    expect(store.suspended).toBe(false)
    expect(mirror().inFlight).toBe(false)
  })
})


describe('最终复核：未知读取、终态重读与缺口来源', () => {
  it('空 history + found=false + probe status=0 仍属未明确，pending/done=false/inFlight 保留', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    sse.probeReattach.mockResolvedValue({ status: 0, preparing: null })

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(pendingPart(host)).toMatchObject({ status: 'pending', requiresAction: true })
    expect(host).toMatchObject({ done: false, streaming: true })
    expect(store.currentError).toMatchObject({ scope: 'current', status: 0 })
    expect(mirror().inFlight).toBe(true)
  })

  it('history 503 + current DONE 保留有界重读，耗尽后 manualRecovery 可见且 DONE 不收口失败通道', async () => {
    vi.useFakeTimers()
    const { store } = refreshedSeed('SUSPENDED')
    api.history.mockRejectedValue(unavailable())
    api.runsCurrent.mockResolvedValue(snapshot('PENDING', 'DONE'))

    await store.loadHistory()

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(mirror().inFlight).toBe(false)
    expect(store.historyError).toMatchObject({ scope: 'history', status: 503 })
    expect(store.manualRecovery).toBeNull()

    await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)
    expect(api.history).toHaveBeenCalledTimes(2)
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(mirror().inFlight).toBe(false)
    expect(store.manualRecovery).toBeNull()
    expect(store.restoringRecovery).toBe(true)
    expect(store.businessWriteBlocked).toBe(true)

    await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS * AUTO_RECOVERY_MAX_ATTEMPTS)
    expect(api.history).toHaveBeenCalledTimes(1 + AUTO_RECOVERY_MAX_ATTEMPTS)
    expect(store.manualRecovery).toMatchObject({ scope: 'history', reason: 'load' })
    expect(store.restoringRecovery).toBe(false)
    expect(store.businessWriteBlocked).toBe(true)
    expect(store.historyError).toMatchObject({ scope: 'history', status: 503 })
    expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable' })
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(mirror().inFlight).toBe(false)
  })

  it('history 成功按来源清消息标记，未解决 snapshot 与 archive-window 仍保留', async () => {
    const { store } = refreshedSeed('SUSPENDED')
    vi.spyOn(store, 'reattachActive').mockResolvedValue(undefined)
    api.history.mockRejectedValueOnce(unavailable())
    api.runsCurrent.mockResolvedValue({ ...snapshot('PENDING', 'RUNNING'), archiveMaxSeq: 0 })
    await store.loadHistory()
    let host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable' })
    expect(host.recoveryGap).toBe(true)

    api.history.mockResolvedValue({ sessionId: 'sess-recovery-terminal', count: 0, messages: [] })
    store.loading = false
    store.suspended = false
    await store.retryLoadHistory()

    host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(store.historyError).toBeNull()
    expect(store.recoveryGap).toBeNull()
    expect(host.recoveryGap).toBeUndefined()

    api.runsCurrent.mockRejectedValue(unavailable())
    store.loading = false
    store.suspended = false
    await store.loadHistory()
    host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    expect(host.recoveryGap).toBe(true)

    store.markRecoveryGap('archive-window')
    host.recoveryGapReason = 'archive-window'
    store.writeMirrorNow()
    api.runsCurrent.mockResolvedValue({ ...snapshot('PENDING', 'RUNNING'), archiveMaxSeq: 0 })
    store.loading = false
    store.suspended = false
    await store.loadHistory()
    host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(store.recoveryGap).toMatchObject({ reason: 'archive-window' })
    expect(host.recoveryGap).toBe(true)
    expect(host.recoveryGapReason).toBe('archive-window')
  })
})


describe('限定复核：来源并集与恢复门禁', () => {
  it('target/source 各异来源合并取并集，history 与 snapshot 分别清理', () => {
    const store = useAiStore()
    store.sessionId = 'sess-recovery-terminal'
    const target = store.appendAssistantMessage('目标正文')
    target.runId = 'run-1'
    target.done = false
    target.streaming = true
    const source = store.appendAssistantMessage('来源正文')
    source.runId = 'run-1'
    source.done = true
    source.streaming = false
    store.sealRecovery('history', unavailable())
    target.done = true
    target.streaming = false
    delete target.recoveryGap
    source.done = false
    source.streaming = true
    store.sealRecovery('current', unavailable())
    target.done = false
    target.streaming = true
    target.recoveryGap = true
    store.recoveryGap = { reason: 'snapshot-unavailable', fromSeq: null, toSeq: null, at: Date.now() }

    const merged = store.mergeMirrorInFlightRound([target], [source], true)
    store.messages = merged
    const host = merged.find((message) => message.runId === 'run-1') as ChatMessage
    store.clearRecoveryGap()
    expect(host.recoveryGap).toBe(true)

    store.recoveryGap = { reason: 'history-unavailable', fromSeq: null, toSeq: null, at: Date.now() }
    store.clearHistoryRecoveryGap()
    expect(host.recoveryGap).toBeUndefined()
  })

  it('全局 history 不得误删消息 snapshot 来源，snapshot 解决后才清普通标记', () => {
    const store = useAiStore()
    store.sessionId = 'sess-recovery-terminal'
    const host = store.appendAssistantMessage('部分正文')
    host.runId = 'run-1'
    host.done = false
    host.streaming = true
    store.sealRecovery('current', unavailable())
    store.recoveryGap = { reason: 'history-unavailable', fromSeq: null, toSeq: null, at: Date.now() }

    store.clearHistoryRecoveryGap()
    expect(host.recoveryGap).toBe(true)

    store.recoveryGap = { reason: 'snapshot-unavailable', fromSeq: null, toSeq: null, at: Date.now() }
    store.clearRecoveryGap()
    expect(host.recoveryGap).toBeUndefined()
  })

  it('history 重读成功后恢复门禁释放，DONE 与 mirror.inFlight=false 不变', async () => {
    vi.useFakeTimers()
    const { store } = refreshedSeed('SUSPENDED')
    api.history.mockRejectedValueOnce(unavailable())
    api.history.mockResolvedValue({ sessionId: 'sess-recovery-terminal', count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(snapshot('PENDING', 'DONE'))

    await store.loadHistory()
    expect(store.restoringRecovery).toBe(true)
    expect(store.businessWriteBlocked).toBe(true)

    await vi.advanceTimersByTimeAsync(AUTO_RECOVERY_DELAY_MS)

    const host = store.messages.find((message) => message.runId === 'run-1') as ChatMessage
    expect(api.history).toHaveBeenCalledTimes(2)
    expect(store.historyError).toBeNull()
    expect(store.manualRecovery).toBeNull()
    expect(store.restoringRecovery).toBe(false)
    expect(store.businessWriteBlocked).toBe(false)
    expect(host).toMatchObject({ done: true, streaming: false })
    expect(mirror().inFlight).toBe(false)
  })
})
