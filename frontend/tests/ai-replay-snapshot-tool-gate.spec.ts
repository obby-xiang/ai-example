/**
 * F6 残留：刷新回放对"本地段仍 running / 进程内无占位 / 旁路无已决 id"的工具不得二次执行。
 *
 * <h2>用户实测缺陷链</h2>
 * 工具（`confirm_step` / `set_condition`）已经真的执行完，但回灌 POST 还在途、结果帧还没到 ——
 * 此刻刷新：
 * <ul>
 * <li>镜像里的段仍是 `running` ⇒ 不是"已决"，进不了回放保留的已决 id 旁路；</li>
 * <li>进程内 `frontendToolsInFlight` 是内存态，刷新即空；</li>
 * <li>`restoreInFlightRound` 清段 + `lastSeq=0` 全量重放 ⇒ 重放的 `frontend_tool_request`
 *   一路畅通 ⇒ **二次执行**（`confirm_step` 二次推进向导、`set_condition` 二次落库），
 *   而 409 只能拒绝回灌、撤不回已经发生的副作用。</li>
 * </ul>
 *
 * <h2>本文件锁住的口径</h2>
 * <ol>
 * <li>重放执行前用 `runs/current.pendingEntries` 对账：**按 toolCallId** 逐条判定，条目不存在的
 *   不牵连别的条目；已非 PENDING ⇒ 只补展示（重放的结果帧落段），不执行；</li>
 * <li>`confirm_step` 幂等：目标步骤**已经到达**就不重复 goStep / persistStepData（仍在原步骤才执行）；</li>
 * <li>快照条目不可用（拿不到 pendingEntries）⇒ 有副作用工具保守不执行 + 可恢复提示，
 *   只读工具照旧执行（不能因为保守把无副作用的也停掉）。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AiRunCurrent, ChatMessage, ToolPart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
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

const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

const SUCCESS = { ok: true, outcome: 'ok' as const, availability: 'ok' as const, result: '已执行' }

function toolPart(message: ChatMessage, toolCallId: string): ToolPart | undefined {
  return message.parts.find((item): item is ToolPart => item.kind === 'tool' && item.toolCallId === toolCallId)
}

function requestFrame(
  toolCallId: string,
  name: string,
  runId: string,
  seq = 2,
  args = '{}'
) {
  return {
    type: 'frontend_tool_request' as const,
    toolCallId,
    name,
    args,
    timeoutSeconds: 120,
    runId,
    seq
  }
}

function resultFrame(toolCallId: string, name: string, runId: string, seq = 3) {
  return {
    type: 'frontend_tool_result' as const,
    toolCallId,
    name,
    ok: true,
    executed: true,
    status: 'FRONTEND_RESULT',
    result: '已推进',
    runId,
    seq
  }
}

function snapshot(overrides: Partial<AiRunCurrent> = {}): AiRunCurrent {
  return {
    found: true,
    runId: 'run-gate',
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0,
    ...overrides
  }
}

function entry(toolCallId: string, entryStatus: string, name = 'confirm_step') {
  return { toolCallId, entryStatus, kind: 'FRONTEND', name }
}

/** 造本轮宿主（用户提问 + 未收尾助手消息）。 */
function seedHost(store: ReturnType<typeof useAiStore>, runId: string): ChatMessage {
  const user: ChatMessage = {
    id: 'u-1',
    role: 'user',
    content: '把这一步推进下去',
    parts: [{ kind: 'text', seg: 0, text: '把这一步推进下去', streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
  store.messages.push(user)
  const host = store.appendAssistantMessage('')
  host.runId = runId
  host.parts = [{ kind: 'text', seg: 0, text: '好', streaming: true }]
  return host
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(snapshot({ found: false, runId: null, snapshotStatus: null }))
  api.frontendToolResult.mockResolvedValue({})
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  frontendTools.executeFrontendTool.mockReset()
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
})

describe('① 回放按 toolCallId 与快照对账：已非 PENDING 只补展示', () => {
  it('本地段仍 running + 快照 FRONTEND_RESULT ⇒ 零二次执行、零二次回灌，卡片由重放结果帧落成 succeeded', async () => {
    const store = useAiStore()
    store.sessionId = 's-gate'
    const host = seedHost(store, 'run-gate')
    // "执行完了但回灌 POST 在途" 的现场：段还是 running，旁路里没有它
    store.upsertToolPart(host, {
      toolCallId: 'tc-step',
      name: 'confirm_step',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{"step":"QUERY_COND"}',
      requiresAction: false
    })

    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: 's-gate', seq: 1 })
      handlers.onFrame(requestFrame('tc-step', 'confirm_step', runId, 2, '{"step":"QUERY_COND"}'))
      handlers.onFrame(resultFrame('tc-step', 'confirm_step', runId, 3))
      return new Promise(() => {})
    })

    void store.restoreInFlightRound(snapshot({
      pendingEntries: [entry('tc-step', 'FRONTEND_RESULT')]
    }))
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 20))

    // 零二次副作用：执行器一次都没被调用；也没有第二次回灌
    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
    // 展示面由重放的结果帧补齐（不是 running、也不留 pending 卡）
    expect(toolPart(host, 'tc-step')).toMatchObject({ status: 'succeeded' })
  })

  it('快照里只有一条已决、另一条不在快照里 ⇒ 只跳过已决那条，缺失的照常执行（逐条对账，不牵连）', async () => {
    const store = useAiStore()
    store.sessionId = 's-gate2'
    const host = seedHost(store, 'run-gate2')
    store.upsertToolPart(host, {
      toolCallId: 'tc-step',
      name: 'confirm_step',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{"step":"QUERY_COND"}',
      requiresAction: false
    })
    store.upsertToolPart(host, {
      toolCallId: 'tc-cond',
      name: 'set_condition',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{"defCode":"DEMO","conditions":{}}',
      requiresAction: false
    })
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)

    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: 's-gate2', seq: 1 })
      handlers.onFrame(requestFrame('tc-step', 'confirm_step', runId, 2, '{"step":"QUERY_COND"}'))
      handlers.onFrame(resultFrame('tc-step', 'confirm_step', runId, 3))
      handlers.onFrame(requestFrame('tc-cond', 'set_condition', runId, 4, '{"defCode":"DEMO","conditions":{}}'))
      return new Promise(() => {})
    })

    void store.restoreInFlightRound(snapshot({
      runId: 'run-gate2',
      // 只给 tc-step：tc-cond 在这一版快照里"缺失"
      pendingEntries: [entry('tc-step', 'FRONTEND_RESULT')]
    }))
    await flushPromises()
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))

    // 只有缺失的那条被执行（一次），已决那条零执行
    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
    expect(frontendTools.executeFrontendTool.mock.calls[0]?.[0]).toMatchObject({ toolCallId: 'tc-cond' })
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tc-cond' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
  })
})

describe('② confirm_step 幂等：目标步骤已达就不重复推进', () => {
  it('workspace 已是目标步骤 ⇒ 不调执行器（零 goStep/persistStepData），但仍回灌 ok 让本轮收敛', async () => {
    useWorkspaceStore().enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 7
    })
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)

    const store = useAiStore()
    store.sessionId = 's-step'
    const host = seedHost(store, 'run-step')
    store.upsertToolPart(host, {
      toolCallId: 'tc-idem',
      name: 'confirm_step',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{"step":"QUERY_COND"}',
      requiresAction: false
    })
    store.activeRunId = 'run-step'

    await store.runFrontendTool(host, requestFrame('tc-idem', 'confirm_step', 'run-step', 2, '{"step":"QUERY_COND"}'))

    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tc-idem', outcome: 'ok' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
    expect(toolPart(host, 'tc-idem')?.status).toBe('succeeded')
  })

  it('仍在原步骤（目标未到达）⇒ 照常执行', async () => {
    useWorkspaceStore().enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: 7
    })
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)

    const store = useAiStore()
    store.sessionId = 's-step2'
    const host = seedHost(store, 'run-step2')
    store.upsertToolPart(host, {
      toolCallId: 'tc-adv',
      name: 'confirm_step',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{"step":"QUERY_COND"}',
      requiresAction: false
    })
    store.activeRunId = 'run-step2'

    await store.runFrontendTool(host, requestFrame('tc-adv', 'confirm_step', 'run-step2', 2, '{"step":"QUERY_COND"}'))

    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
  })
})

describe('③ 快照条目不可用：有副作用工具保守不执行 + 可恢复提示', () => {
  it('拿不到 pendingEntries ⇒ 写工具不执行并收敛为 unknown + 提示；只读工具照常执行', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)

    const store = useAiStore()
    store.sessionId = 's-untrusted'
    const host = seedHost(store, 'run-u')
    store.upsertToolPart(host, {
      toolCallId: 'tc-write',
      name: 'set_condition',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{"defCode":"DEMO","conditions":{}}',
      requiresAction: false
    })
    store.upsertToolPart(host, {
      toolCallId: 'tc-read',
      name: 'read_sheet_summary',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{"defCode":"DEMO"}',
      requiresAction: false
    })

    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: 's-untrusted', seq: 1 })
      handlers.onFrame(requestFrame('tc-write', 'set_condition', runId, 2, '{"defCode":"DEMO","conditions":{}}'))
      handlers.onFrame(requestFrame('tc-read', 'read_sheet_summary', runId, 4, '{"defCode":"DEMO"}'))
      return new Promise(() => {})
    })

    // 快照条目清单不可用（读不到 / 形状不可信）
    void store.restoreInFlightRound(snapshot({
      runId: 'run-u',
      pendingEntries: undefined as unknown as AiRunCurrent['pendingEntries']
    }))
    await flushPromises()
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))

    // 写工具零执行、段收敛（不留 running），并给出可恢复提示
    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
    expect(frontendTools.executeFrontendTool.mock.calls[0]?.[0]).toMatchObject({ toolCallId: 'tc-read' })
    expect(toolPart(host, 'tc-write')?.status).toBe('unknown')
    expect(store.notice).not.toBeNull()
    expect(store.notice ?? '').toContain('刷新')
    // 只读工具照常执行并回灌
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tc-read' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
  })
})
