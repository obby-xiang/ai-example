/**
 * 对话时序包 批2 收口修 F1：`frontend_tool_result` 帧的终态裁决。
 *
 * 红队 MAJOR-1：该帧的段写入口 `applyFrameToParts` 原先只读 `frame.ok`（不读 `frame.status`），
 * 于是后端实发的四类"非失败"终态（FRONTEND_CANCELLED / CANCELLED / TIMEOUT /
 * REJECTED_ARGUMENTS）全部落成 `failed`，工具卡恒显红「执行失败」。
 *
 * 三级验证（施工卡 F3）：
 *   ① reducer 级（纯函数 applyFrameToParts）：后端实发 status 全集五路 + status 缺失/未知兜底
 *      + localTerminal（本地乐观终态，含 cancelled）first-wins 保护；
 *   ② store 链路级（handleFrame 收终帧）：GF 路 / 非 GF 路各至少一例，证明终态由
 *      applyFrameToParts **单点**承载；
 *   ③ 渲染投影级：deriveProjections 后 toolRuns.status 正确、requiresAction 收口 ⇒ pendingCall=null。
 *
 * 后端实发枚举对账见 evidence-b2fix/backend-status-enum.txt（SseChatEmitter:613-619 取
 * PendingToolCall#getStatus() 入帧；实发值：FRONTEND_RESULT(ok=true) / FRONTEND_CANCELLED /
 * CANCELLED / TIMEOUT / REJECTED_ARGUMENTS）。
 *
 * 风格照抄 parts-residue-cleanup.spec.ts：只驱动真实 store / 纯函数，不挂载面板。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AiRunCurrent, ChatMessage, ToolPart, ToolRunStatus } from '@/types/ai'
import { applyFrameToParts, deriveProjections, frontendResultStatus, upsertToolPart } from '@/utils/parts'
import { frameStatusToPendingStatus, useAiStore } from '@/stores/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn()
}))

// 批3③b D8/D10：执行器调用计数与晚到结算由本桩接管（其余导出保持真实 —— parseToolArgs 等）。
const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.chat.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  api.frontendToolResult.mockReset()
  api.confirm.mockReset()
  // 批3③b D8/D10：执行器默认立即成功结算（各用例可自行 mockImplementation 接管）
  frontendTools.executeFrontendTool.mockReset()
  frontendTools.executeFrontendTool.mockResolvedValue({ ok: true, outcome: 'ok', availability: 'available', result: '执行完成' })
  // 续收连接保持"在跑"（永不结算）：不触发各轮 finally 里的轮次复位
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

/** 消息上的工具段序列。 */
function toolPartsOf(message: ChatMessage): ToolPart[] {
  return message.parts.filter((part): part is ToolPart => part.kind === 'tool')
}

/** 唯一工具段。 */
function soleToolPart(message: ChatMessage): ToolPart {
  const tools = toolPartsOf(message)
  expect(tools).toHaveLength(1)
  return tools[0] as ToolPart
}

/** 生产同形的助手消息（以流式空文本段起头）。 */
function makeMessage(): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '',
    parts: [{ kind: 'text', seg: 0, text: '', streaming: true }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

/** reducer 级现场：一条带 FRONTEND 工具段的消息（段 status 可控）。 */
function messageWithToolPart(
  toolCallId = 'r1',
  status: ToolRunStatus = 'pending',
  name = 'probe.tool'
): ChatMessage {
  const message = makeMessage()
  upsertToolPart(message, {
    toolCallId,
    name,
    kind2: 'FRONTEND',
    status,
    requiresAction: true
  })
  return message
}

/** 一条 frontend_tool_result 帧（status 可缺失 —— 后端旧口径/未知值的等价现场）。 */
function resultFrame(
  overrides: Partial<{ toolCallId: string; name: string; ok: boolean; status: string; result: string }> = {}
): Parameters<typeof applyFrameToParts>[1] {
  const { toolCallId = 'r1', name = 'probe.tool', ok = false, status, result = '回灌结果' } = overrides
  return {
    type: 'frontend_tool_result',
    toolCallId,
    name,
    ok,
    executed: ok,
    ...(status === undefined ? {} : { status }),
    result
  } as Parameters<typeof applyFrameToParts>[1]
}

/** runs/current 的"有一轮挂起等待"快照（单条 FRONTEND 待执行 —— 非 GF 路的现场；请求帧已发）。 */
function suspendedFrontendSnapshot(): AiRunCurrent {
  return {
    found: true,
    runId: 'run-old',
    snapshotStatus: 'PENDING',
    pendingEntries: [
      { toolCallId: 'n1', entryStatus: 'PENDING', kind: 'FRONTEND', name: 'probe.unknown-nongf', arguments: '{}', req: true }
    ],
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 7
  }
}

/** 驱动一条 generative_form 的 frontend_tool_request 帧（合法 schema → 武装 activeForm）。 */
function armGenerativeForm(store: ReturnType<typeof useAiStore>, toolCallId = 'g1'): ChatMessage {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const assistant = store.appendAssistantMessage()
  store.handleFrame({
    type: 'frontend_tool_request',
    toolCallId,
    name: 'generative_form',
    args: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
    timeoutSeconds: 120,
    runId: 'run-1',
    seq: 2
  })
  return assistant
}

describe('F1 ① reducer 级：后端实发 status 全集 → 段终态', () => {
  it('FRONTEND_RESULT + ok=true → succeeded（确认后的实发成功值）', () => {
    const message = messageWithToolPart('r1', 'running')
    applyFrameToParts(message, resultFrame({ ok: true, status: 'FRONTEND_RESULT' }))
    expect(soleToolPart(message)).toMatchObject({ status: 'succeeded', requiresAction: false })
  })

  it('CANCELLED + ok=false → cancelled（整轮取消 ConfirmGate.cancel 实发值）', () => {
    const message = messageWithToolPart('r1', 'running')
    applyFrameToParts(message, resultFrame({ status: 'CANCELLED' }))
    expect(soleToolPart(message).status).toBe('cancelled')
  })

  it('FRONTEND_CANCELLED + ok=false → cancelled（用户放弃表单 ConfirmGate.dismiss 实发值）', () => {
    const message = messageWithToolPart('r1', 'running')
    applyFrameToParts(message, resultFrame({ status: 'FRONTEND_CANCELLED' }))
    expect(soleToolPart(message).status).toBe('cancelled')
  })

  it('TIMEOUT + ok=false → expired（前端回执超时 ConfirmGate.expire 实发值）', () => {
    const message = messageWithToolPart('r1', 'running')
    applyFrameToParts(message, resultFrame({ status: 'TIMEOUT' }))
    expect(soleToolPart(message).status).toBe('expired')
  })

  it('REJECTED_ARGUMENTS + ok=false → rejected（入参被闸门拒 SpToolCallingManager 实发值）', () => {
    const message = messageWithToolPart('r1', 'running')
    applyFrameToParts(message, resultFrame({ status: 'REJECTED_ARGUMENTS' }))
    expect(soleToolPart(message).status).toBe('rejected')
  })

  it('status 缺失 + ok=false → failed（旧口径兜底）', () => {
    const message = messageWithToolPart('r1', 'running')
    applyFrameToParts(message, resultFrame({ ok: false }))
    expect(soleToolPart(message).status).toBe('failed')
  })

  it('status 未知值 + ok=false → failed；未知值 + ok=true → succeeded（前向兼容）', () => {
    const failed = messageWithToolPart('r1', 'running')
    applyFrameToParts(failed, resultFrame({ ok: false, status: 'SOMETHING_NEW' }))
    expect(soleToolPart(failed).status).toBe('failed')

    const succeeded = messageWithToolPart('r2', 'running')
    applyFrameToParts(succeeded, resultFrame({ toolCallId: 'r2', ok: true, status: 'SOMETHING_NEW' }))
    expect(soleToolPart(succeeded).status).toBe('succeeded')
  })

  it('localTerminal 保护：本地已 cancelled 段不被"缺省 status + ok=false"帧改成 failed', () => {
    const message = messageWithToolPart('r1', 'cancelled')
    applyFrameToParts(message, resultFrame({ ok: false }))
    expect(soleToolPart(message)).toMatchObject({ status: 'cancelled', requiresAction: false })
  })

  it('localTerminal 保护：本地已 cancelled 段不被后到 TIMEOUT 帧改写（first-wins 单调）', () => {
    const message = messageWithToolPart('r1', 'cancelled')
    applyFrameToParts(message, resultFrame({ status: 'TIMEOUT' }))
    expect(soleToolPart(message).status).toBe('cancelled')
  })

  it('localTerminal 保护：本地已 succeeded 段不被后到 CANCELLED 帧改写成 cancelled', () => {
    const message = messageWithToolPart('r1', 'succeeded')
    applyFrameToParts(message, resultFrame({ status: 'CANCELLED' }))
    expect(soleToolPart(message).status).toBe('succeeded')
  })

  it('③ 渲染投影：deriveProjections 后 toolRuns.status 为终态、requiresAction 收口 ⇒ pendingCall=null', () => {
    const message = messageWithToolPart('r1', 'running', 'probe.tool')
    applyFrameToParts(message, resultFrame({ status: 'FRONTEND_CANCELLED' }))
    deriveProjections(message)

    expect(message.toolRuns).toHaveLength(1)
    expect(message.toolRuns[0]).toMatchObject({ toolCallId: 'r1', status: 'cancelled' })
    expect(message.pendingCall).toBeNull()
  })
})

describe('F1 ② store 链路级：handleFrame 收 frontend_tool_result 终帧', () => {
  it('GF 路：FRONTEND_CANCELLED 终帧 → 段 cancelled + 挂起卡收口 + activeForm 收敛（单点写段）', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    expect(store.activeForm?.toolCallId).toBe('g1')
    expect(soleToolPart(assistant)).toMatchObject({ status: 'pending', requiresAction: true })

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: false,
      executed: false,
      status: 'FRONTEND_CANCELLED',
      result: '用户取消了该前端操作',
      seq: 3
    })

    // 终态由 applyFrameToParts 单点落段（GF 的 status 写段不再依赖 applyFrontendToolTerminal）
    expect(soleToolPart(assistant)).toMatchObject({
      toolCallId: 'g1',
      status: 'cancelled',
      requiresAction: false,
      result: '用户取消了该前端操作'
    })
    expect(assistant.pendingCall).toBeNull()
    expect(store.activeForm).toBeNull()
  })

  it('GF 路：FRONTEND_RESULT 终帧 → 段 succeeded + 挂起卡收口', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: true,
      executed: true,
      status: 'FRONTEND_RESULT',
      messageId: 'tool-g1',
      result: '{"k":"v"}',
      seq: 3
    })

    expect(soleToolPart(assistant)).toMatchObject({
      toolCallId: 'g1',
      status: 'succeeded',
      messageId: 'tool-g1',
      requiresAction: false
    })
    expect(assistant.pendingCall).toBeNull()
  })

  it('非 GF 路：续收重建的 pending 段收 TIMEOUT 终帧 → 段 expired（终态由单点承载）', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({ sessionId: store.sessionId, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(suspendedFrontendSnapshot())

    await store.loadHistory()

    const host = store.messages[0] as ChatMessage
    expect(host).toBeTruthy()
    expect(soleToolPart(host)).toMatchObject({ toolCallId: 'n1', status: 'pending', requiresAction: true })
    expect(host.pendingCall).toMatchObject({ toolCallId: 'n1', status: 'pending' })

    // 生产同形路由：续收连接以宿主消息 id 投帧（resumeSnapshotStream 的 onFrame）
    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'n1',
      name: 'probe.unknown-nongf',
      ok: false,
      executed: false,
      status: 'TIMEOUT',
      result: '未在 120 秒内收到前端回执',
      seq: 8
    }, host.id)

    expect(soleToolPart(host)).toMatchObject({
      toolCallId: 'n1',
      status: 'expired',
      requiresAction: false
    })
    expect(host.pendingCall).toBeNull()
    expect(host.toolRuns[0]).toMatchObject({ toolCallId: 'n1', status: 'expired' })
  })
})

describe('F2：帧路径段终态的单写点（applyFrameToParts）与终态收敛幂等', () => {
  it('handleFrame 收终帧**不**经 applyFrontendToolTerminal —— 段终态由 applyFrameToParts 单点承载', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    const spy = vi.spyOn(store, 'applyFrontendToolTerminal')

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: false,
      executed: false,
      status: 'FRONTEND_CANCELLED',
      result: '用户取消了该前端操作',
      seq: 3
    })

    // 帧路径不再有第二个写点（GF 的 status 写段随之不再依赖它）
    expect(spy).not.toHaveBeenCalled()
    expect(soleToolPart(assistant)).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
    spy.mockRestore()
  })

  it('终态收敛幂等：终帧落段后同值广播（非帧路径 409/续收的口径）不改变段与投影', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: false,
      executed: false,
      status: 'FRONTEND_CANCELLED',
      result: '用户取消了该前端操作',
      seq: 3
    })
    const before = { ...soleToolPart(assistant) }

    // 非帧路径（409 DUPLICATE / 续收快照）与帧路径同源同映射值 ⇒ 二次收敛无副作用
    store.applyFrontendToolTerminal('g1', 'cancelled')

    expect(soleToolPart(assistant)).toEqual(before)
    expect(assistant.pendingCall).toBeNull()
    expect(assistant.toolRuns[0]).toMatchObject({ toolCallId: 'g1', status: 'cancelled' })
  })
})

/**
 * 对话时序包 批3③a：unknown 三态收口的段侧锁（T1 生死线 / T2 X1 不变式 / T3 已决守卫扩充）。
 *
 * T1 是生死线用例：`submitFrontendToolResult` 的 POST payload 不带 status/ok
 * （types/ai.ts FrontendToolResultRequest 仅 runId/toolCallId/result/source/cancelled），
 * 后端回帧恒为 FRONTEND_RESULT —— `localTerminal`（parts.ts）是本地 unknown 段
 * 不被回执帧翻成 succeeded 的**唯一机制**。
 */
describe('批3③a T1：本地 unknown 段的 first-wins 存活（生死线）', () => {
  it('本地 unknown 段收 FRONTEND_RESULT 回执帧后仍是 unknown（不被翻成 succeeded）', () => {
    const message = makeMessage()
    upsertToolPart(message, {
      toolCallId: 'r1',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'unknown',
      result: '本次调用结局未知 —— 既未确认成功，也未确认失败',
      requiresAction: false
    })
    applyFrameToParts(message, resultFrame({ ok: true, status: 'FRONTEND_RESULT' }))
    expect(soleToolPart(message)).toMatchObject({ status: 'unknown', requiresAction: false })
  })

  it('本地 unknown 段收 TIMEOUT 回执帧后仍是 unknown（first-wins 单调）', () => {
    const message = makeMessage()
    upsertToolPart(message, {
      toolCallId: 'r1',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'unknown',
      requiresAction: false
    })
    applyFrameToParts(message, resultFrame({ ok: false, status: 'TIMEOUT' }))
    expect(soleToolPart(message)).toMatchObject({ status: 'unknown', requiresAction: false })
  })
})

describe('批3③a T2：unknown ⇒ requiresAction === false 不变式（X1）', () => {
  it('违例段（unknown + requiresAction=true，镜像/未来写点的 rogue 形态）不产出挂起卡', () => {
    const message = makeMessage()
    upsertToolPart(message, {
      toolCallId: 'r1',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'unknown',
      requiresAction: true
    })
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    deriveProjections(message)
    // 不变式：unknown 段永不复活为挂起卡
    expect(message.pendingCall).toBeNull()
    expect(message.toolRuns[0]).toMatchObject({ toolCallId: 'r1', status: 'unknown' })
    // dev 断言双落（断言存在但不阻断投影）
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
  })
})

describe('批3③a T3：hasDecidedPendingResidue 对 unknown / failed 段的拦截（D6(A)）', () => {
  it('unknown 段与 failed 段均视为已决（快照恢复路可测口径）', () => {
    const store = useAiStore()
    const withUnknown = makeMessage()
    upsertToolPart(withUnknown, {
      toolCallId: 'u1', name: 'probe.tool', kind2: 'FRONTEND', status: 'unknown', requiresAction: false
    })
    const withFailed = makeMessage()
    upsertToolPart(withFailed, {
      toolCallId: 'f1', name: 'probe.tool', kind2: 'FRONTEND', status: 'failed', requiresAction: false
    })
    store.messages.push(withUnknown, withFailed)
    expect(store.hasDecidedPendingResidue('u1')).toBe(true)
    expect(store.hasDecidedPendingResidue('f1')).toBe(true)
  })
})

/**
 * 对话时序包 批3③b D8（R-141③）：挂起语义帧的"已决不重投"闸 —— 守卫去 GF 前缀、
 * 扩到全部工具（confirm_request / frontend_tool_request / suspended 各 case 内），
 * 判据统一 8 态（isFrameDecidedPendingStatus，含 unknown / failed / skipped）。
 * 终态帧 case 不入本闸（③a E17 回归锁不破）；已决面的"跨宿主段重建"由 D6(B) 店级守卫承接
 * （见 ai-panel-parts-render.spec.ts 新宿主路渲染锁）。
 */
describe('批3③b D8：已决 toolCallId 的重投帧闸（R-141③，通用 FRONTEND 工具重投不重执行）', () => {
  it('frontend_tool_request 重投（已决 succeeded）：执行器不被二次调用、段不被改回 pending', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 2
    })
    await vi.waitFor(() => expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1))
    expect(soleToolPart(assistant)).toMatchObject({ status: 'succeeded', requiresAction: false })

    // 重投（新 seq）：已决守卫必须拦截 —— 不二次执行、终态不被改写、挂起语义不复活
    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 3
    })
    await Promise.resolve()
    await Promise.resolve()
    expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1)
    expect(soleToolPart(assistant)).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
  })

  it('frontend_tool_request 重投（已决 unknown，③a 新引入面的已知代价）：同拦', async () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    upsertToolPart(assistant, {
      toolCallId: 'u1', name: 'probe.tool', kind2: 'FRONTEND', status: 'unknown',
      result: '本次调用结局未知', requiresAction: false
    })
    deriveProjections(assistant)

    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'u1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 2
    })
    await Promise.resolve()
    await Promise.resolve()
    expect(frontendTools.executeFrontendTool).not.toHaveBeenCalled()
    expect(soleToolPart(assistant)).toMatchObject({ status: 'unknown', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
  })

  it('suspended 重投：遍历 toolCalls[] 判已决（failed 段），requiresAction 不复活', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    upsertToolPart(assistant, {
      toolCallId: 'f1', name: 'probe.tool', kind2: 'FRONTEND', status: 'failed', requiresAction: false
    })
    deriveProjections(assistant)

    // rogue 面（R-141③ 行尾注记 / DS Pro O-5）：failed + requiresAction=true 经 suspended 支
    // ⇒ pendingStatusOf 折 pending 复活挂起卡 —— 闸拦后该旁路不可达
    store.handleFrame({
      type: 'suspended',
      runId: 'run-1',
      seq: 2,
      toolCalls: [{ toolCallId: 'f1', name: 'probe.tool', type: 'function', kind: 'FRONTEND', arguments: '{}', status: 'PENDING', executed: false }]
    })
    expect(soleToolPart(assistant)).toMatchObject({ status: 'failed', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
  })

  it('confirm_request 重投（已决 cancelled）：不复活挂起卡', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    upsertToolPart(assistant, {
      toolCallId: 'c1', name: 'danger_tool', kind2: 'CONFIRM', status: 'cancelled', requiresAction: false
    })
    deriveProjections(assistant)

    store.handleFrame({
      type: 'confirm_request', toolCallId: 'c1', name: 'danger_tool',
      args: '{}', timeoutSeconds: 120, seq: 2
    })
    expect(soleToolPart(assistant)).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
  })

  it('GF 回归锁（去 GF 前缀后既有行为不变）：已决 GF toolCallId 重投不重新武装 activeForm', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    store.handleFrame({
      type: 'frontend_tool_result', toolCallId: 'g1', name: 'generative_form',
      ok: false, executed: false, status: 'FRONTEND_CANCELLED', result: '用户取消了该前端操作', seq: 3
    })
    expect(soleToolPart(assistant)).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(store.activeForm).toBeNull()

    // 重投 request 帧：守卫仍拦（前缀去掉后 GF 路径口径不变）
    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'g1', name: 'generative_form',
      args: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
      timeoutSeconds: 120, runId: 'run-1', seq: 4
    })
    expect(store.activeForm).toBeNull()
    expect(soleToolPart(assistant)).toMatchObject({ status: 'cancelled', requiresAction: false })
  })

  it('收窄面：未决 toolCallId 的首轮 frontend_tool_request 照常执行（闸不误拦）', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'n1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 2
    })
    await vi.waitFor(() => expect(frontendTools.executeFrontendTool).toHaveBeenCalledTimes(1))
    expect(soleToolPart(assistant)).toMatchObject({ status: 'succeeded', requiresAction: false })
  })
})

/** 批3③b D10（R-142）：stop()/换轮后在途执行器晚到 —— 段写与回灌丢弃（入口捕获轮次句柄）。 */
describe('批3③b D10：轮次失配丢弃（stop 后在途 executeFrontendTool 不写段、不回灌）', () => {
  it('执行器晚于轮次句柄替换返回 ⇒ 段不被终态覆盖、回灌 POST 不发', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    let resolveExecution!: (value: unknown) => void
    frontendTools.executeFrontendTool.mockImplementation(() => new Promise((resolve) => {
      resolveExecution = resolve
    }))
    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 2
    })
    // 开跑落 running 属正常（校验点只在 await 返回之后）
    await vi.waitFor(() => expect(soleToolPart(assistant).status).toBe('running'))

    // 轮次句柄被替换（重挂/新轮同口径；stop() 触发的本轮 finally 复位同效）：
    // 执行器从此是"旧轮在途" —— reattachActive 的首个 await 之前同步完成换句柄
    void store.reattachActive('run-2')

    resolveExecution({ ok: true, outcome: 'ok', availability: 'available', result: '晚到的旧轮结果' })
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()

    const seg = assistant.parts.find((part) => part.kind === 'tool' && part.toolCallId === 'r1')
    expect(seg).toMatchObject({ status: 'running' })
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})

/** 批3③b D12（R-143③）：assertUnknownTerminalInvariant 补 import.meta.env.DEV 门（口径如实）。 */
describe('批3③b D12：X1 dev 断言的 DEV 门', () => {
  it('DEV=false（生产形态）：违例段不 console.error，投影行为不变（挂起卡仍被跳过）', () => {
    vi.stubEnv('DEV', false)
    const message = makeMessage()
    upsertToolPart(message, {
      toolCallId: 'r1', name: 'probe.tool', kind2: 'FRONTEND', status: 'unknown', requiresAction: true
    })
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    deriveProjections(message)
    expect(message.pendingCall).toBeNull()
    expect(message.toolRuns[0]).toMatchObject({ toolCallId: 'r1', status: 'unknown' })
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
    vi.unstubAllEnvs()
  })
})

/**
 * 批3③b D9（R-141④）：两翻译表 / 两终态枚举的防漂移一致性锁。
 * 唯一真实分叉 = SUCCEEDED（仅帧路表收录；响应体表不翻译 ⇒ null），显式登记。
 */
describe('批3③b D9：两表 / 两枚举防漂移一致性锁', () => {
  it('两枚举成员集合一致（8 态）：段侧 localTerminal == store 侧 isFrameDecidedPendingStatus', () => {
    const CANDIDATES: ToolRunStatus[] = ['pending', 'running', 'succeeded', 'rejected', 'blocked', 'expired', 'cancelled', 'unknown', 'failed', 'skipped']
    // 段侧 localTerminal（parts.ts）行为枚举：已终态段 first-wins 不被 FRONTEND_RESULT 翻写
    const partSide = CANDIDATES.filter((status) => {
      const message = messageWithToolPart('r1', status)
      applyFrameToParts(message, resultFrame({ ok: true, status: 'FRONTEND_RESULT' }))
      return soleToolPart(message).status === status
    })
    // store 侧 isFrameDecidedPendingStatus（ai.ts）行为枚举：经公开的 hasDecidedPendingResidue
    const store = useAiStore()
    const storeSide = CANDIDATES.filter((status) => {
      const message = makeMessage()
      upsertToolPart(message, {
        toolCallId: `probe-${status}`, name: 'probe.tool', kind2: 'FRONTEND', status, requiresAction: false
      })
      store.messages.push(message)
      return store.hasDecidedPendingResidue(`probe-${status}`)
    })
    expect(partSide).toHaveLength(8)
    expect(storeSide).toHaveLength(8)
    expect([...partSide].sort()).toEqual([...storeSide].sort())
  })

  it('两翻译表逐一对账 + 唯一真实分叉 SUCCEEDED 显式登记（CANCELLED 依据注释实存，两表同收 cancelled）', () => {
    const SHARED: Array<[string, ToolRunStatus]> = [
      ['FRONTEND_RESULT', 'succeeded'],
      ['FRONTEND_CANCELLED', 'cancelled'],
      ['CANCELLED', 'cancelled'],
      ['TIMEOUT', 'expired'],
      ['REJECTED_ARGUMENTS', 'rejected'],
      ['REJECTED', 'rejected'],
      ['BLOCKED', 'blocked']
    ]
    for (const [frameStatus, expected] of SHARED) {
      const message = messageWithToolPart('r1', 'running')
      applyFrameToParts(message, resultFrame({ ok: expected === 'succeeded', status: frameStatus }))
      expect(soleToolPart(message).status).toBe(expected)
      expect(frontendResultStatus(frameStatus)).toBe(expected)
      expect(frameStatusToPendingStatus(frameStatus)).toBe(expected)
    }
    // 唯一真实分叉（R-141④ 台账误述订正后剩下的全部差异）：
    // 帧路表收录 SUCCEEDED（ConfirmGate#cancel 竞态带出的已决值同形映射）；响应体表不翻译。
    expect(frontendResultStatus('SUCCEEDED')).toBe('succeeded')
    expect(frameStatusToPendingStatus('SUCCEEDED')).toBeNull()
    // 契约增量（skipped）：SKIPPED 只走后端批次停走的 tool_result 帧，不经
    // frontend_tool_result / 409 响应体通道 —— 两表同返回 null（显式登记，非第二处分叉）。
    expect(frameStatusToPendingStatus('SKIPPED')).toBeNull()
    expect(frontendResultStatus('SKIPPED')).toBeNull()
  })
})
