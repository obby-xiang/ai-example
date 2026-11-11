import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import type { AiRunCurrent, AiRunCurrentEntry, ChatMessage, ToolPart } from '@/types/ai'
import { resetAiIdentityCache } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import { FakeBroadcastChannel, FakeLockManager } from './tab-ownership-fixtures'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  createSession: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

const OK_EXECUTION = { ok: true, outcome: 'ok' as const, availability: 'available' as const, result: '已执行' }

function current(entries: AiRunCurrentEntry[], overrides: Partial<AiRunCurrent> = {}): AiRunCurrent {
  return {
    found: true,
    runId: 'run-freeze',
    snapshotStatus: 'SUSPENDED',
    pendingEntries: entries,
    unresolvedExternal: entries.filter((entry) => entry.entryStatus === 'PENDING').map((entry) => entry.toolCallId),
    awaitingExternal: entries.some((entry) => entry.entryStatus === 'PENDING'),
    archiveMaxSeq: 4,
    ...overrides
  }
}

function automaticEntry(toolCallId = 'tool-auto'): AiRunCurrentEntry {
  return {
    toolCallId,
    entryStatus: 'PENDING',
    kind: 'FRONTEND',
    name: 'navigate_to',
    arguments: '{"page":"export"}',
    req: true
  }
}

function seedTool(store: ReturnType<typeof useAiStore>, toolCallId: string, status: ToolPart['status'] = 'pending'): ChatMessage {
  const host = store.appendAssistantMessage('')
  host.runId = 'run-freeze'
  store.upsertToolPart(host, {
    toolCallId,
    name: 'navigate_to',
    kind2: 'FRONTEND',
    status,
    args: '{"page":"export"}',
    requiresAction: false
  })
  store.activeRunId = 'run-freeze'
  return host
}

function requestFrame(toolCallId: string) {
  return {
    type: 'frontend_tool_request' as const,
    toolCallId,
    name: 'navigate_to',
    args: '{"page":"export"}',
    runId: 'run-freeze'
  }
}

function toolPart(message: ChatMessage, toolCallId: string): ToolPart | undefined {
  return message.parts.find((part): part is ToolPart => part.kind === 'tool' && part.toolCallId === toolCallId)
}

class GatedLockManager {
  private readonly grants: Array<() => void> = []

  get pending(): number {
    return this.grants.length
  }

  request(_name: string, _options: { ifAvailable?: boolean }, callback: (lock: { name: string } | null) => unknown): Promise<unknown> {
    return new Promise((resolve) => {
      this.grants.push(() => {
        void Promise.resolve(callback({ name: _name })).then(() => resolve(undefined), () => resolve(undefined))
      })
    })
  }

  grant(): void {
    this.grants.shift()?.()
  }
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  frontendTools.executeFrontendTool.mockReset()
  frontendTools.executeFrontendTool.mockResolvedValue(OK_EXECUTION)
  Object.defineProperty(navigator, 'locks', { value: new FakeLockManager(), configurable: true })
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(current([], { found: false, runId: null, snapshotStatus: null, awaitingExternal: false }))
  api.frontendToolResult.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.createSession.mockResolvedValue('session-new')
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
  resetAiIdentityCache()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('K3.1 store/SSE 冻结回归', () => {
  it('自动工具-only 快照回灌后，即使 suspended=false 也另起续收连接', async () => {
    const store = useAiStore()
    store.sessionId = 'session-auto-only'
    api.runsCurrent.mockResolvedValue(current([automaticEntry()]))
    const reconnect = vi.spyOn(store, 'reconnectSuspendedRun')

    await store.restoreSuspendedSnapshot(() => true)
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))

    expect(store.suspended).toBe(false)
    expect(reconnect).toHaveBeenCalledWith('run-freeze', 'tool-auto', true, expect.any(Number))
    await vi.waitFor(() => expect(api.reattachRun).toHaveBeenCalledTimes(2))
    expect(api.reattachRun.mock.calls[1]?.[2]).toMatchObject({ awaitingInput: false })
  })

  it('无 Web Locks 时不自动执行，回灌 unknown 并收口卡片', async () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    const store = useAiStore()
    store.sessionId = 'session-no-locks'
    const host = seedTool(store, 'tool-no-locks')

    await store.runFrontendTool(host, requestFrame('tool-no-locks'))

    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tool-no-locks', outcome: 'unknown' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
    expect(toolPart(host, 'tool-no-locks')).toMatchObject({ status: 'unknown', requiresAction: false })
  })

  it('工具锁竞争失败时只等待持锁方权威结果：不执行、不回灌 unknown、不本地结算', async () => {
    Object.defineProperty(navigator, 'locks', {
      value: { request: vi.fn(async (_name, _options, callback) => callback(null)) },
      configurable: true
    })
    const store = useAiStore()
    store.sessionId = 'session-contended'
    const host = seedTool(store, 'tool-contended')

    await store.runFrontendTool(host, requestFrame('tool-contended'))

    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
    expect(toolPart(host, 'tool-contended')).toMatchObject({ status: 'pending', requiresAction: false })
  })

  it('历史占位 succeeded 遇真实 PENDING 快照时翻回 pending 可操作卡', async () => {
    const store = useAiStore()
    store.sessionId = 'session-placeholder'
    api.history.mockResolvedValue({
      sessionId: 'session-placeholder',
      count: 2,
      messages: [
        { type: 'USER', text: '继续' },
        {
          type: 'ASSISTANT',
          text: '',
          runId: 'run-freeze',
          toolCalls: [{ id: 'tool-placeholder', name: 'start_export', arguments: '{"taskId":7}' }]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(current([{
      toolCallId: 'tool-placeholder',
      entryStatus: 'PENDING',
      kind: 'CONFIRM',
      name: 'start_export',
      arguments: '{"taskId":7}',
      req: true
    }]))

    await store.loadHistory()

    const host = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    expect(toolPart(host, 'tool-placeholder')).toMatchObject({ status: 'pending', requiresAction: true })
    expect(host.pendingCall).toMatchObject({ toolCallId: 'tool-placeholder', status: 'pending' })
  })

  it('runs/current 的条目 outcome=skipped 按同一结局词表消费', async () => {
    const store = useAiStore()
    store.sessionId = 'session-outcome'
    const host = seedTool(store, 'tool-skipped', 'running')
    api.runsCurrent.mockResolvedValue(current([{
      toolCallId: 'tool-skipped',
      entryStatus: 'SKIPPED',
      kind: 'FRONTEND',
      name: 'navigate_to',
      outcome: 'skipped'
    } as AiRunCurrentEntry], { awaitingExternal: false, unresolvedExternal: [] }))

    await store.restoreSuspendedSnapshot(() => true)

    expect(toolPart(host, 'tool-skipped')).toMatchObject({ status: 'skipped', requiresAction: false })
  })

  it('换会话后不复用旧 ownershipProbe，新会话完成新代次认领', async () => {
    installIdentityFixture({ sessionId: '9401' })
    const gated = new GatedLockManager()
    Object.defineProperty(navigator, 'locks', { value: gated, configurable: true })
    api.createSession.mockResolvedValue('9402')
    const store = useAiStore()

    const old = store.ensureSession()
    await flushPromises()
    expect(gated.pending).toBe(1)

    store.adoptFreshSession()
    const fresh = store.ensureSession()
    await flushPromises()
    expect(api.createSession).toHaveBeenCalledTimes(1)
    expect(store.sessionId).toBe('9402')
    expect(store.tabOwnership).toBe('unknown')
    expect(gated.pending).toBe(2)

    gated.grant()
    await expect(old).resolves.toBe('')
    gated.grant()
    await expect(fresh).resolves.toBe('9402')
    expect(store.tabOwnership).toBe('owner')
  })

  it('前端工具收到 409 后，生成式表单的本地 unknown 必须让位后端成功终态', async () => {
    const { ApiError } = await import('@/api/http')
    const store = useAiStore()
    store.sessionId = 'session-duplicate-form'
    const host = seedTool(store, 'tool-duplicate-form', 'unknown')
    store.upsertToolPart(host, {
      toolCallId: 'tool-duplicate-form',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'unknown',
      requiresAction: false
    })
    api.frontendToolResult.mockRejectedValue(new ApiError(
      '该工具调用已有裁决',
      409,
      'DUPLICATE_TOOL_CALL_ID',
      { status: 'FRONTEND_RESULT' }
    ))

    await store.submitFrontendToolResult(
      'tool-duplicate-form',
      '本地未知结局',
      'frontend-executor',
      false,
      'run-freeze',
      'unknown'
    )

    expect(toolPart(host, 'tool-duplicate-form')).toMatchObject({ status: 'succeeded', requiresAction: false })
  })

  it('409 后的权威快照可纠正本地 unknown，而不是被 first-wins 挡住', () => {
    const store = useAiStore()
    store.sessionId = 'session-gate-authority'
    const host = seedTool(store, 'tool-gate-authority', 'unknown')

    store.applyGateReceipt('tool-gate-authority', { decision: 'approved', status: 'succeeded' })

    expect(toolPart(host, 'tool-gate-authority')).toMatchObject({
      status: 'succeeded',
      decision: 'approved',
      requiresAction: false
    })
  })

  it('历史空白失败条目恢复 code 与 errorCard，不被空白清理丢弃', () => {
    const store = useAiStore()

    const messages = store.buildHistoryMessages([{
      type: 'ASSISTANT',
      text: '   ',
      errorCode: 'UPSTREAM_STREAM_ERROR',
      errorMessage: '上游连接失败'
    }])

    expect(messages).toHaveLength(1)
    expect(messages[0]).toMatchObject({
      role: 'assistant',
      code: 'UPSTREAM_STREAM_ERROR',
      done: true,
      errorCard: { code: 'UPSTREAM_STREAM_ERROR', detail: '上游连接失败' }
    })
  })

  it('同轮 assistant 合并时保留后续空白失败条目的错误事实', () => {
    const store = useAiStore()

    const messages = store.buildHistoryMessages([
      { type: 'ASSISTANT', text: '已生成的半截正文' },
      {
        type: 'ASSISTANT',
        text: '',
        errorCode: 'UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL',
        errorMessage: '缺少终帧'
      }
    ])

    expect(messages).toHaveLength(1)
    expect(messages[0]?.content).toBe('已生成的半截正文')
    expect(messages[0]).toMatchObject({
      code: 'UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL',
      errorCard: { code: 'UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL', detail: '缺少终帧' }
    })
  })

  it('未知活性出口收到 409 后不再用本地 unknown 覆盖后端成功结局', async () => {
    const { ApiError } = await import('@/api/http')
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    const store = useAiStore()
    store.sessionId = 'session-uncertain-duplicate'
    const host = seedTool(store, 'tool-uncertain-duplicate')
    api.frontendToolResult.mockRejectedValue(new ApiError(
      '该工具调用已有裁决',
      409,
      'DUPLICATE_TOOL_CALL_ID',
      { status: 'FRONTEND_RESULT' }
    ))

    await store.runFrontendTool(host, requestFrame('tool-uncertain-duplicate'))

    expect(toolPart(host, 'tool-uncertain-duplicate')).toMatchObject({
      status: 'succeeded',
      requiresAction: false
    })
  })
})
