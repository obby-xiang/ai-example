/**
 * 刷新回放 / 回灌窗口 / 409 权威纠偏：前端工具**零重执行、零重回灌、终态不被本地覆盖**。
 *
 * <h2>用户实测缺陷（2026-10-31 阻断）</h2>
 * 刷新后 `select_definitions` / `set_condition` 卡片显示"执行失败"，实际第一次已成功：
 * `restoreInFlightRound` 必须清段 + `lastSeq=0` 全量重放（镜像正文滞后），但段一清，
 * 重放回来的 `frontend_tool_request` 就再也看不到"这条工具已经成功过"——
 * `hasDecidedPendingResidue` 读段读不到 ⇒ 二次真实执行（第二次页面未就绪 ⇒ 失败），
 * 本地 failed 再覆盖成功；`confirm_step` 更是会**真实再推进一次**。
 *
 * <h2>口径（本文件锁住）</h2>
 * <ol>
 * <li>回放清段**前**按 run 记下本轮已决 toolCallId（只记 id、不留段 ⇒ 顺序与去重不受影响），
 *   `hasDecidedPendingResidue` 并入该事实 ⇒ 重放请求帧零执行零回灌；资格按 run 生效，不串 run；</li>
 * <li>执行中 toolCallId 去重：执行 → 回灌受理整段占位，同 id 请求帧不得重入，结束后释放；</li>
 * <li>409 DUPLICATE：后端权威终态（FRONTEND_RESULT）纠正本地 failed/unknown/running，
 *   本地这次的 `execution.outcome` 不得覆盖它；</li>
 * <li>`stillHere=false` 且段仍 pending/running ⇒ 中性收敛为 `unknown`（结局未知），
 *   不误判 failed、不留永久 running；</li>
 * <li>正常失败（非重复、非 409）仍显示失败 —— 纠偏不得削弱真实失败。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import type { AiRunCurrent } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { useAiStore } from '@/stores/ai'
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

/** 只替换执行器（其余前端工具面照旧真实：parseToolArgs / deriveFrontendToolBudgetMs 等）。 */
const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

type ToolRequestFrame = Extract<SseFrame, { type: 'frontend_tool_request' }>

const SUCCESS = { ok: true, outcome: 'ok' as const, availability: 'ok' as const, result: '已执行' }
const FAILED = { ok: false, outcome: 'failed' as const, availability: 'ok' as const, result: '页面未就绪，执行失败' }

function toolPart(message: ChatMessage, toolCallId: string): ToolPart {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  expect(part).toBeDefined()
  return part as ToolPart
}

function toolParts(message: ChatMessage): ToolPart[] {
  return message.parts.filter((part): part is ToolPart => part.kind === 'tool')
}

function requestFrame(
  toolCallId: string,
  name = 'probe.tool',
  runId = 'run-replay',
  seq = 2
): ToolRequestFrame {
  return {
    type: 'frontend_tool_request',
    toolCallId,
    name,
    args: '{"defCodes":["A"]}',
    timeoutSeconds: 120,
    runId,
    seq
  }
}

function resultFrame(toolCallId: string, name = 'probe.tool', runId = 'run-replay') {
  return {
    type: 'frontend_tool_result' as const,
    toolCallId,
    name,
    ok: true,
    executed: true,
    status: 'FRONTEND_RESULT',
    result: '已选择 A',
    runId,
    seq: 3
  }
}

function runningSnapshot(runId = 'run-replay'): AiRunCurrent {
  return {
    found: true,
    runId,
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  }
}

/** 造本轮宿主（在途）：最后一条提问之后的未收尾助手消息。 */
function seedInFlightHost(store: ReturnType<typeof useAiStore>, runId = 'run-replay'): ChatMessage {
  const user: ChatMessage = {
    id: 'u-1',
    role: 'user',
    content: '把定义选上',
    parts: [{ kind: 'text', seg: 0, text: '把定义选上', streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
  store.messages.push(user)
  const host = store.appendAssistantMessage('')
  host.runId = runId
  host.parts = [{ kind: 'text', seg: 0, text: '好的，我来选', streaming: true }]
  return host
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
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

describe('① 刷新回放：已决工具零重执行 / 零回灌', () => {
  it('replay 里已 succeeded 的 select_definitions 不再执行、不再回灌，卡片仍是成功且只有一段', async () => {
    const store = useAiStore()
    store.sessionId = 's-replay'
    const host = seedInFlightHost(store)
    store.upsertToolPart(host, {
      toolCallId: 'tc-1',
      name: 'select_definitions',
      kind2: 'FRONTEND',
      status: 'succeeded',
      args: '{"defCodes":["A"]}',
      result: '已选择 A',
      requiresAction: false
    })
    store.activeRunId = 'run-replay'
    host.done = false

    // 后端归档全量重放：start → 同一条工具的请求帧 → 结果帧 → done
    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: 's-replay', seq: 1 })
      handlers.onFrame(requestFrame('tc-1', 'select_definitions', runId))
      handlers.onFrame(resultFrame('tc-1', 'select_definitions', runId))
      handlers.onFrame({ type: 'done', runId, seq: 4 })
      return new Promise(() => {})
    })

    // 续收连接永不结算（重放帧已在建连时同步投递）⇒ 不 await 恢复本身
    void store.restoreInFlightRound(runningSnapshot())
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 20))

    // 零二次副作用：执行器一次都没被调用；也没有第二次回灌
    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
    // 卡片仍是成功，且没有因为回放重复建段
    expect(toolPart(host, 'tc-1')).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(toolParts(host)).toHaveLength(1)
  })

  it('replay 里已 succeeded 的 confirm_step 不会真的再推进一次（执行器零调用）', async () => {
    const store = useAiStore()
    store.sessionId = 's-replay-step'
    const host = seedInFlightHost(store, 'run-step')
    store.upsertToolPart(host, {
      toolCallId: 'tc-step',
      name: 'confirm_step',
      kind2: 'FRONTEND',
      status: 'succeeded',
      args: '{}',
      result: '已进入下一步',
      requiresAction: false
    })
    store.activeRunId = 'run-step'

    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: 's-replay-step', seq: 1 })
      handlers.onFrame(requestFrame('tc-step', 'confirm_step', runId))
      handlers.onFrame(resultFrame('tc-step', 'confirm_step', runId))
      return new Promise(() => {})
    })

    void store.restoreInFlightRound(runningSnapshot('run-step'))
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 20))

    // confirm_step 是会真实推进向导的写工具：二次执行 = 二次推进 ⇒ 必须零调用、零回灌
    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
    expect(toolPart(host, 'tc-step').status).toBe('succeeded')
  })

  it('回放保留的已决 id 只对本 run 生效：换 run 后同 toolCallId 的请求帧照常执行（不串 run）', async () => {
    const store = useAiStore()
    store.sessionId = 's-replay-scope'
    const host = seedInFlightHost(store, 'run-a')
    store.upsertToolPart(host, {
      toolCallId: 'tc-same',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'succeeded',
      args: '{}',
      result: '已执行',
      requiresAction: false
    })
    store.activeRunId = 'run-a'

    // 回放只给请求帧（结果帧缺失）：段被清空后，唯一还记得"已决"的就是按 run 记下的 id
    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame(requestFrame('tc-same', 'probe.tool', runId))
      return new Promise(() => {})
    })
    void store.restoreInFlightRound(runningSnapshot('run-a'))
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()

    // 换到另一个 run：同 id 的请求帧属于新 run ⇒ 资格不跨 run，必须照常执行
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)
    store.activeRunId = 'run-b'
    store.loading = true
    store.handleFrame(requestFrame('tc-same', 'probe.tool', 'run-b', 9))
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
  })
})

describe('② 回灌 await 窗口：同 id 不得重入', () => {
  it('回灌未受理时再来一份同 id 请求帧 ⇒ 只执行一次、只回灌一次，段不被翻回 pending', async () => {
    const store = useAiStore()
    store.sessionId = 's-dup'
    store.loading = true
    store.activeRunId = 'run-dup'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-dup'
    store.upsertToolPart(host, {
      toolCallId: 'tc-dup',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{}',
      requiresAction: false
    })
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)
    let releaseSubmit: (() => void) | null = null
    api.frontendToolResult.mockImplementation(() => new Promise<void>((resolve) => {
      releaseSubmit = () => resolve()
    }))

    store.handleFrame(requestFrame('tc-dup', 'probe.tool', 'run-dup'))
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
    // 窗口内段仍是 running（可被停止取消），不是被重复请求帧翻回 pending
    expect(toolPart(host, 'tc-dup').status).toBe('running')

    // 重连/重复投递来的第二份同 id 请求帧（新 seq：seq 幂等只挡同序号重复投递，
    // 重放/重连后的重投会带新序号 —— 这正是"同一调用被跑第二遍"的实际形态）
    store.handleFrame(requestFrame('tc-dup', 'probe.tool', 'run-dup', 5))
    await flushPromises()
    expect(toolPart(host, 'tc-dup').status).toBe('running')

    const release = releaseSubmit as unknown as (() => void) | null
    release?.()
    await vi.waitFor(() => expect(toolPart(host, 'tc-dup').status).toBe('succeeded'))
    // 队尾项若混进来会在这里多跑一次
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
  })

  it('同一批里两份同 id 请求帧（首条还没开跑）⇒ 只执行一次、只回灌一次', async () => {
    const store = useAiStore()
    store.sessionId = 's-batch-dup'
    store.loading = true
    store.activeRunId = 'run-batch'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-batch'
    store.upsertToolPart(host, {
      toolCallId: 'tc-batch',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{}',
      requiresAction: false
    })
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)

    // 连续两帧（不同 seq）在同一批里到达：首条还在队列里没开跑
    store.handleFrame(requestFrame('tc-batch', 'probe.tool', 'run-batch', 2))
    store.handleFrame(requestFrame('tc-batch', 'probe.tool', 'run-batch', 5))

    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(toolPart(host, 'tc-batch').status).toBe('succeeded')
  })

  it('执行结束后同 id 的**新**请求帧（后端重投）不再被占用位拦住：走已决守卫而不是永久拒绝', async () => {
    const store = useAiStore()
    store.sessionId = 's-dup-release'
    store.loading = true
    store.activeRunId = 'run-rel'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-rel'
    store.upsertToolPart(host, {
      toolCallId: 'tc-rel',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{}',
      requiresAction: false
    })
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)

    store.handleFrame(requestFrame('tc-rel', 'probe.tool', 'run-rel'))
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))

    // 结束后（段已 succeeded）再来同 id 请求帧（新 seq）：零执行（已决守卫），而非排队等待
    store.handleFrame(requestFrame('tc-rel', 'probe.tool', 'run-rel', 5))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
  })
})

describe('③ 409 DUPLICATE：后端权威终态纠正本地结论', () => {
  it('本地这次执行失败 + 409 说该调用已 FRONTEND_RESULT ⇒ 卡片显示成功，不被 execution.outcome 覆盖', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue(FAILED)
    api.frontendToolResult.mockRejectedValue(new ApiError(
      '该工具调用已有裁决',
      409,
      ErrorCode.DUPLICATE_TOOL_CALL_ID,
      { status: 'FRONTEND_RESULT' }
    ))

    const store = useAiStore()
    store.activeRunId = 'run-dup409'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-dup409'
    store.upsertToolPart(host, {
      toolCallId: 'tc-409',
      name: 'select_definitions',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{"defCodes":["A"]}',
      requiresAction: false
    })

    await store.runFrontendTool(host, requestFrame('tc-409', 'select_definitions', 'run-dup409'))

    // 后端权威结局 = 成功（第一次真的成功了）；本地这次失败只是重复执行的副产物
    expect(toolPart(host, 'tc-409').status).toBe('succeeded')
  })

  it('本地已落 failed 的段被 409 权威纠正为 succeeded（applyFrontendToolTerminal 兜底）', async () => {
    const store = useAiStore()
    store.sessionId = 's-409'
    store.activeRunId = 'run-409'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-409'
    store.upsertToolPart(host, {
      toolCallId: 'tc-409b',
      name: 'set_condition',
      kind2: 'FRONTEND',
      status: 'failed',
      args: '{}',
      result: '页面未就绪',
      requiresAction: false
    })
    api.frontendToolResult.mockRejectedValue(new ApiError(
      '该工具调用已有裁决',
      409,
      ErrorCode.DUPLICATE_TOOL_CALL_ID,
      { status: 'FRONTEND_RESULT' }
    ))

    await store.submitFrontendToolResult('tc-409b', '页面未就绪', 'frontend-executor', false, 'run-409', 'failed')

    expect(toolPart(host, 'tc-409b').status).toBe('succeeded')
  })

  it('正常失败（无重复、无 409）仍显示失败 —— 纠偏不削弱真实失败', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue(FAILED)
    api.frontendToolResult.mockResolvedValue({})

    const store = useAiStore()
    store.activeRunId = 'run-fail'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-fail'
    store.upsertToolPart(host, {
      toolCallId: 'tc-fail',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{}',
      requiresAction: false
    })

    await store.runFrontendTool(host, requestFrame('tc-fail', 'probe.tool', 'run-fail'))

    expect(toolPart(host, 'tc-fail')).toMatchObject({ status: 'failed', result: '页面未就绪，执行失败' })
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tc-fail', outcome: 'failed' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
  })
})

describe('④ stillHere=false：中性收敛，不留永久 running', () => {
  it('执行期间换轮（新轮已占场）⇒ 旧段收敛为 unknown，不是 running/failed', async () => {
    let releaseExecution: (() => void) | null = null
    frontendTools.executeFrontendTool.mockImplementation(() => new Promise((resolve) => {
      releaseExecution = () => resolve(SUCCESS)
    }))

    const store = useAiStore()
    store.sessionId = 's-orphan'
    store.activeRunId = 'run-orphan'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-orphan'
    store.upsertToolPart(host, {
      toolCallId: 'tc-orphan',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{}',
      requiresAction: false
    })

    const run = store.runFrontendTool(host, requestFrame('tc-orphan', 'probe.tool', 'run-orphan'))
    await flushPromises()
    expect(toolPart(host, 'tc-orphan').status).toBe('running')

    // 换轮：新轮起（chat 永不结算），本轮纪元与宿主都换代
    void store.send('真正的新一轮')
    await flushPromises()
    await flushPromises()
    expect(store.loading).toBe(true)

    const release = releaseExecution as unknown as (() => void) | null
    release?.()
    await run

    // 中性收敛：结局未知（不谎报成功、不误判失败、不永久 running）
    expect(toolPart(host, 'tc-orphan').status).toBe('unknown')
    expect(toolPart(host, 'tc-orphan').status).not.toBe('running')
    // 旧轮的结局不回灌给新轮
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})
