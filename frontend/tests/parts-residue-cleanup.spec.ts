/**
 * 对话时序包 批2 前置收口（子包②权威翻转之前的必做项）：
 *
 * 施工项1 —— requiresAction 六残留点写段收口：done / error / confirm(乐观置位、DUPLICATE、
 *   失败回退) / cancelGenerativeForm / applyPendingTerminal / applyFrontendToolTerminal
 *   在收口挂起语义的同时写 parts 段。★ 批2 权威翻转后：挂起投影由段派生，
 *   "终态仍保留 pendingCall"类的旧断言已改写（逐条处见文内批2 注释）。
 * 施工项2 —— 镜像读取侧残留收口：存量 v2 镜像里已持久化的 requiresAction=true 在
 *   loadHistory 镜像分支读取时降级（幂等），防止刷新复活挂起语义。
 * 施工项3 —— m-R2 六写点回归锁 + 前端工具全链路（request → 挂起 → 本地执行 → result 回灌）。
 *
 * 风格照抄 parts-double-write.spec.ts：只驱动真实 store、断言纯逻辑层，不挂载面板。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import { MIRROR_PREFIX, useAiStore } from '@/stores/ai'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { registerPageHandler, unregisterPageHandler } from '@/stores/workspace'
import type { AiRunCurrent } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  // B1（2026-10-20）：回灌成功后带 lastSeq 重连收流会调 reattachRun —— 桩件保持"在跑"
  reattachRun: vi.fn(() => new Promise(() => {}))
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.frontendToolResult.mockReset()
  api.confirm.mockReset()
  api.frontendToolResult.mockResolvedValue({})
})

/**
 * 跨用例收尾：本文件的 `reattachRun` 桩**永不结算**，"在途连接"（`currentAbort`，模块级变量）
 * 因此不会被流的 finally 清掉；`setActivePinia` 只换 store，不碰模块级现场。
 * 不清的后果：下一个用例调 `loadHistory()` 时 `inFlightAtEntry` 判真 ⇒ 镜像分支与历史分支
 * 双双按"过期现场"跳过 —— messages 为空，断言却在别处（每文件只有一条用例时看不出来）。
 * 丢弃本页签轮次 = 每个用例等价于一张新页面。
 */
afterEach(() => {
  useAiStore().adoptFreshSession()
})

/** 消息上的工具段序列。 */
function toolPartsOf(message: ChatMessage): ToolPart[] {
  return message.parts.filter((part): part is ToolPart => part.kind === 'tool')
}

/** 唯一工具段（断言"不新建段"用）。 */
function soleToolPart(message: ChatMessage): ToolPart {
  const tools = toolPartsOf(message)
  expect(tools).toHaveLength(1)
  return tools[0] as ToolPart
}

/** 驱动一条 confirm_request 帧：建 pendingCall 投影 + pending/requiresAction 工具段。 */
function armConfirm(store: ReturnType<typeof useAiStore>, toolCallId = 'c1'): ChatMessage {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const assistant = store.appendAssistantMessage()
  store.handleFrame({
    type: 'confirm_request',
    toolCallId,
    name: 'danger_tool',
    args: '{"x":1}',
    timeoutSeconds: 120,
    expiresAt: 1_700_000_120_000,
    atMs: 1_700_000_000_000,
    seq: 2
  })
  return assistant
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

/** runs/current 的"无挂起"空快照（loadHistory 收尾必走）。 */
function noSuspension(): AiRunCurrent {
  return {
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  }
}

describe('施工项1：requiresAction 六残留点写段收口', () => {
  it('done 帧：该消息所有 requiresAction 工具段 → expired + requiresAction=false（pendingCall 投影照旧收掉）', () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 'c2',
      name: 'danger_tool',
      args: '{"y":2}',
      timeoutSeconds: 120,
      seq: 3
    })
    expect(toolPartsOf(assistant).map((part) => part.requiresAction)).toEqual([true, true])
    expect(assistant.pendingCall?.toolCallId).toBe('c2')

    store.handleFrame({ type: 'done', runId: 'run-1', seq: 9 })

    // 投影：仍处 pending 的挂起被收掉（基线语义不变）
    expect(assistant.pendingCall).toBeNull()
    // 段：全部待决段过期降级（与 loadHistory 空历史 expired 降级惯例一致）
    expect(toolPartsOf(assistant).map((part) => [part.status, part.requiresAction]))
      .toEqual([['expired', false], ['expired', false]])
  })

  it('error 帧：清 activeForm 的同时，待决工具段同样过期降级（段不留挂起语义）', () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')

    store.handleFrame({ type: 'error', runId: 'run-1', code: 'UPSTREAM', message: '上游异常', seq: 9 })

    expect(assistant.done).toBe(true)
    const part = soleToolPart(assistant)
    expect(part).toMatchObject({ toolCallId: 'c1', status: 'expired', requiresAction: false })
    // 批2（权威翻转）：段已过期降级 ⇒ 派生出的挂起卡为 null。翻转前这里断言的是双写期
    // "投影保留 pending"的旧口径 —— 那会留一张点了只会 404 的死卡（行为改善，非等价）
    expect(assistant.pendingCall).toBeNull()
  })

  it('confirm(true)：乐观置位同步落段 —— running + requiresAction=false，就地更新不新建段', async () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    const seg = soleToolPart(assistant).seg
    api.confirm.mockResolvedValue({})

    await store.confirm(true)

    // 批2（权威翻转）：段离开"等动作"态 ⇒ 挂起卡收掉，"调用中"由工具卡承载
    expect(assistant.pendingCall).toBeNull()
    const part = soleToolPart(assistant)
    expect(part).toMatchObject({ toolCallId: 'c1', seg, status: 'running', requiresAction: false })
  })

  it('confirm(false)：段落 rejected + requiresAction=false', async () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    api.confirm.mockResolvedValue({})

    await store.confirm(false, '用户拒绝')

    expect(assistant.pendingCall).toBeNull()
    expect(soleToolPart(assistant)).toMatchObject({ status: 'rejected', requiresAction: false })
  })

  it('confirm 无 id 兜底（审批身份③）：仍走单槽 pendingToolCall，既有调用签名兼容', async () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    api.confirm.mockResolvedValue({})

    await store.confirm(true)

    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0]).toMatchObject({ runId: 'run-1', toolCallId: 'c1', approved: true })
    expect(assistant.pendingCall).toBeNull()
    expect(soleToolPart(assistant)).toMatchObject({ status: 'running', requiresAction: false })
  })

  it('confirm 带 id：按卡片定位宿主与段（不依赖末段单槽），非确认/非待决段被拒', async () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 'c2',
      name: 'danger_tool',
      args: '{"y":2}',
      timeoutSeconds: 120,
      seq: 3
    })
    api.confirm.mockResolvedValue({})

    // 点第一张卡（c1）——单槽此刻指向 c2
    await store.confirm(false, '用户拒绝', 'c1')
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0].toolCallId).toBe('c1')

    // 非法身份：不存在的 id / 已决段 —— 提示并 return，不调用 API
    api.confirm.mockClear()
    await store.confirm(true, undefined, 'nope')
    expect(api.confirm).not.toHaveBeenCalled()
    expect(store.notice).toBe('没有待处理的确认请求')
    expect(toolPartsOf(assistant).find((part) => part.toolCallId === 'c2')).toMatchObject({ status: 'pending', requiresAction: true })
  })

  // R-166 口径③改写（原「段并入 running + 挂起卡收掉」是"硬写 approved"的诚实性缺陷）：
  // 409 不写任何乐观裁决 —— 段回待决（挂起卡仍在）+ 提交态置 syncing + 3s 后拉 runs/current
  // 落**真实裁决**（本用例后端真实状态是 REJECTED，旧口径会显示成「已确认」）。
  it('confirm 提交撞 DUPLICATE（R-166 口径③）：不硬写 approved —— 瞬态「同步中」+ 3s 后按 runs/current 落真实裁决', async () => {
    vi.useFakeTimers()
    try {
      const store = useAiStore()
      const assistant = armConfirm(store, 'c1')
      api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))
      api.runsCurrent.mockResolvedValue({
        found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
        pendingEntries: [{ toolCallId: 'c1', entryStatus: 'REJECTED', kind: 'CONFIRM', name: 'danger_tool' }],
        unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
      } as AiRunCurrent)

      await store.confirm(true)

      // ① 不写乐观裁决：段回 pending 待决（挂起卡与双按钮仍在，等待真实结局）
      expect(assistant.pendingCall?.status).toBe('pending')
      expect(soleToolPart(assistant)).toMatchObject({ status: 'pending', requiresAction: true })
      expect(soleToolPart(assistant).decision).toBeUndefined()
      // ② 提交态 = syncing（面板显示「已提交，同步中…」且双按钮禁用）
      expect(store.gateSubmissionOf('c1')).toMatchObject({ decision: 'approved', syncing: true })
      // ③ 同 toolCallId 再点被源头防重短路（同一条最多提交一次）
      api.confirm.mockResolvedValue({})
      await store.confirm(true)
      expect(api.confirm).toHaveBeenCalledTimes(1)
      expect(api.runsCurrent).not.toHaveBeenCalled()

      // ④ 3s 未达结局帧 ⇒ 拉 runs/current 校正为**真实裁决**（后端说 REJECTED，不是 approved）
      await vi.advanceTimersByTimeAsync(3000)
      await Promise.resolve()
      expect(api.runsCurrent).toHaveBeenCalledTimes(1)
      expect(assistant.pendingCall).toBeNull()
      expect(soleToolPart(assistant)).toMatchObject({ status: 'rejected', decision: 'rejected', requiresAction: false })
      expect(store.gateSubmissionOf('c1')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('confirm 提交失败回退：投影与段都回 pending（段恢复 requiresAction=true）', async () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    api.confirm.mockRejectedValue(new Error('网络异常'))

    await store.confirm(true)

    expect(assistant.pendingCall?.status).toBe('pending')
    expect(soleToolPart(assistant)).toMatchObject({ status: 'pending', requiresAction: true })
  })

  it('cancelGenerativeForm：段落 cancelled + requiresAction=false（经 applyPendingTerminal 收口）', async () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    expect(store.activeForm?.toolCallId).toBe('g1')

    await store.cancelGenerativeForm()

    expect(assistant.pendingCall).toBeNull()
    expect(soleToolPart(assistant)).toMatchObject({ toolCallId: 'g1', status: 'cancelled', requiresAction: false })
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ runId: 'run-1', toolCallId: 'g1', cancelled: true })
    )
  })

  it('applyPendingTerminal：pending/cancelled/requiresAction 段落映射终态（approved→running），域外段不动', () => {
    const store = useAiStore()

    // 分支一：approved 并入 running（段侧落合法 ToolRunStatus）
    const first = armConfirm(store, 'c1')
    store.applyPendingTerminal('c1', 'approved')
    expect(soleToolPart(first)).toMatchObject({ status: 'running', requiresAction: false })
    // 批2（权威翻转）：段出待决匹配域 ⇒ 派生挂起卡为 null（翻转前断言的是"投影保留 approved"）
    expect(first.pendingCall).toBeNull()
    // approved 后段已出待决匹配域，后到终态不再改写（first-wins 单调）
    store.applyPendingTerminal('c1', 'rejected')
    expect(soleToolPart(first).status).toBe('running')
    expect(first.pendingCall).toBeNull()

    // 分支二：pending/cancelled 段在匹配域内 —— 乐观 cancelled 可被后到终态覆盖
    const second = armConfirm(store, 'c2')
    store.applyPendingTerminal('c2', 'cancelled')
    expect(soleToolPart(second)).toMatchObject({ status: 'cancelled', requiresAction: false })
    store.applyPendingTerminal('c2', 'succeeded')
    expect(soleToolPart(second)).toMatchObject({ status: 'succeeded', requiresAction: false })
    // 已终态段 first-wins：不再被改写
    store.applyPendingTerminal('c2', 'rejected')
    expect(soleToolPart(second).status).toBe('succeeded')
  })

  it('applyFrontendToolTerminal：非 GF FRONTEND 段只收 requiresAction（status 不动，终态由结果帧承载）', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'f1',
      name: 'probe.unknown-nongf',
      args: '{"page":"tasks"}',
      timeoutSeconds: 120,
      runId: 'run-1',
      seq: 2
    })
    // 同步断言在异步本地执行改写之前：段仍是 pending + requiresAction
    expect(soleToolPart(assistant)).toMatchObject({ status: 'pending', requiresAction: true })

    store.applyFrontendToolTerminal('f1', 'succeeded')

    // 非 GF：收 requiresAction，status 不动（终态由 frontend_tool_result 帧的写点落定）
    expect(soleToolPart(assistant)).toMatchObject({ status: 'pending', requiresAction: false })
    // 投影基线：通用 FRONTEND 收掉挂起卡
    expect(assistant.pendingCall).toBeNull()
    // 等异步本地执行收尾，避免跨用例泄漏
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalled())
  })

  it('applyFrontendToolTerminal：GF 段 pending → 映射终态落段（succeeded），requiresAction=false', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')

    store.applyFrontendToolTerminal('g1', 'succeeded')

    expect(soleToolPart(assistant)).toMatchObject({ toolCallId: 'g1', status: 'succeeded', requiresAction: false })
    // 批2（权威翻转）：GF 段落终态 ⇒ 派生挂起卡为 null（"表单已提交"由工具卡承载）
    expect(assistant.pendingCall).toBeNull()
  })

  it('applyFrontendToolTerminal：GF 段 status===null → 只收 requiresAction，status 不动；GF 已终态段 first-wins 跳过', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')

    store.applyFrontendToolTerminal('g1', null)
    expect(soleToolPart(assistant)).toMatchObject({ status: 'pending', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()

    // 已落定终态的 GF 段不被后到帧改写
    store.applyFrontendToolTerminal('g1', 'succeeded')
    expect(soleToolPart(assistant).status).toBe('succeeded')
    store.applyFrontendToolTerminal('g1', 'cancelled')
    expect(soleToolPart(assistant).status).toBe('succeeded')
  })
})

describe('施工项2：镜像读取侧 requiresAction 残留收口（GLM o1）', () => {
  it('v2 intact 镜像：done 消息里残留的 pending 工具段 → expired + requiresAction=false，并回写镜像；二次读取幂等', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-k',
      messages: [
        {
          id: 'a-1',
          role: 'assistant',
          content: '正文',
          reasoning: '',
          toolRuns: [],
          pendingCall: { toolCallId: 'k1', name: 'danger_tool', kind: 'CONFIRM', args: { x: 1 }, rawArgs: '{"x":1}', status: 'pending' },
          parts: [
            { kind: 'text', seg: 0, text: '正文', streaming: false },
            { kind: 'tool', seg: 1, toolCallId: 'k1', name: 'danger_tool', kind2: 'CONFIRM', status: 'pending', args: '{"x":1}', requiresAction: true }
          ]
        }
      ]
    }))
    api.history.mockResolvedValue({ sessionId: sid, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const part = soleToolPart(store.messages[0] as ChatMessage)
    expect(part).toMatchObject({ toolCallId: 'k1', status: 'expired', requiresAction: false })

    // 收口发生在 writeMirror 回写之前：镜像里不再复活挂起语义
    const written = JSON.parse(sessionStorage.getItem(MIRROR_PREFIX + sid) ?? '{}')
    expect(written.v).toBe(2)
    expect(written.messages[0].parts[1]).toMatchObject({ status: 'expired', requiresAction: false })
    const savedAt = written.savedAt
    expect(typeof savedAt).toBe('number')

    // 幂等：二次读取无改动、不再回写（savedAt 不变）
    store.messages = []
    await store.loadHistory()
    expect(soleToolPart(store.messages[0] as ChatMessage)).toMatchObject({ status: 'expired', requiresAction: false })
    const again = JSON.parse(sessionStorage.getItem(MIRROR_PREFIX + sid) ?? '{}')
    expect(again.savedAt).toBe(savedAt)
  })

  it('v2 intact 镜像：requiresAction 残留但段已非 pending（succeeded）→ 只收 requiresAction，status 保持', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: null,
      messages: [
        {
          id: 'a-2',
          role: 'assistant',
          content: '正文',
          reasoning: '',
          toolRuns: [{ toolCallId: 'k2', name: 'probe_tool', kind: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: 'ok', messageId: 'tool-k2' }],
          pendingCall: null,
          parts: [
            { kind: 'text', seg: 0, text: '正文', streaming: false },
            { kind: 'tool', seg: 1, toolCallId: 'k2', name: 'probe_tool', kind2: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: 'ok', messageId: 'tool-k2', requiresAction: true }
          ]
        }
      ]
    }))
    api.history.mockResolvedValue({ sessionId: sid, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(soleToolPart(store.messages[0] as ChatMessage))
      .toMatchObject({ toolCallId: 'k2', status: 'succeeded', requiresAction: false })
  })

  it('v1 旧镜像迁移结果同样收口：迁移补出的 requiresAction 段在回写前被清理', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    // v1 镜像：无 v、无 parts；toolRun 已终态 + pendingCall 仍 pending（迁移并入 requiresAction=true）
    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      savedAt: 1,
      lastRunId: 'run-k',
      messages: [
        {
          id: 'a-3',
          role: 'assistant',
          content: '正文',
          reasoning: '',
          toolRuns: [{ toolCallId: 'k3', name: 'danger_tool', kind: 'CONFIRM', status: 'succeeded', args: '{"x":1}', result: 'ok', messageId: 'tool-k3' }],
          pendingCall: { toolCallId: 'k3', name: 'danger_tool', kind: 'CONFIRM', args: { x: 1 }, rawArgs: '{"x":1}', status: 'pending' }
        }
      ]
    }))
    api.history.mockResolvedValue({ sessionId: sid, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    // 迁移并入的 requiresAction 残留被读取侧收口（status 以 run.status 为权威 = succeeded，不降级）
    expect(soleToolPart(store.messages[0] as ChatMessage))
      .toMatchObject({ toolCallId: 'k3', status: 'succeeded', requiresAction: false })
    const written = JSON.parse(sessionStorage.getItem(MIRROR_PREFIX + sid) ?? '{}')
    expect(written.v).toBe(2)
    expect(written.messages[0].parts[1]).toMatchObject({ status: 'succeeded', requiresAction: false })
  })
})

describe('施工项3：m-R2 六写点回归锁', () => {
  it('写点① frontend_tool_request：建段 pending + requiresAction=true（同步可见，先于异步本地执行）', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 0 })
    const assistant = store.appendAssistantMessage()

    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'r1',
      name: 'probe.unknown-tool',
      args: '{"a":1}',
      timeoutSeconds: 120,
      seq: 1
    })

    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'r1',
      name: 'probe.unknown-tool',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{"a":1}',
      requiresAction: true
    })
    expect(store.suspended).toBe(true)
    // 等异步本地执行收尾，避免跨用例泄漏
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalled())
  })

  it('写点② frontend_tool_result：就地更新终态落段（messageId/result），不新建段、seg 不变', async () => {
    const store = useAiStore()
    registerPageHandler('probe.ok-tool', () => '执行完成')
    try {
      const assistant = store.appendAssistantMessage()
      store.handleFrame({
        type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 0
      })
      store.handleFrame({
        type: 'frontend_tool_request',
        toolCallId: 'r2',
        name: 'probe.ok-tool',
        args: '{}',
        timeoutSeconds: 120,
        runId: 'run-1',
        seq: 1
      })
      // 等本地执行先收尾（段已 succeeded，但 messageId 只能由结果帧写点落段）
      await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
      const seg = soleToolPart(assistant).seg

      store.handleFrame({
        type: 'frontend_tool_result',
        toolCallId: 'r2',
        name: 'probe.ok-tool',
        ok: true,
        executed: true,
        status: 'FRONTEND_RESULT',
        messageId: 'tool-r2',
        result: 'ok',
        seq: 2
      })

      expect(toolPartsOf(assistant)).toHaveLength(1)
      expect(assistant.parts[1]).toMatchObject({
        kind: 'tool',
        seg,
        toolCallId: 'r2',
        status: 'succeeded',
        messageId: 'tool-r2',
        result: 'ok',
        requiresAction: false
      })
    } finally {
      unregisterPageHandler('probe.ok-tool')
    }
  })

  it('写点③ suspended（批3③b D5 createIfMissing=true）：已存在段被更新（requiresAction 复位、status/seg 不变），未知 toolCallId 建段', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 's1',
      name: 'danger_tool',
      args: '{"x":1}',
      timeoutSeconds: 120,
      seq: 1
    })
    // 模拟段曾被清理过的现场：requiresAction=false，status 仍 pending
    const index = assistant.parts.findIndex((part) => part.kind === 'tool')
    assistant.parts.splice(index, 1, { ...soleToolPart(assistant), requiresAction: false })

    store.handleFrame({
      type: 'suspended',
      runId: 'run-9',
      toolCalls: [{ toolCallId: 's1', name: 'danger_tool', type: 'function', kind: 'CONFIRM', arguments: '{"x":1}', status: 'PENDING', executed: false }],
      seq: 2
    })

    // 只更新语义：不新建段（仍是 text+tool 两段）、seg 不变、status 保持 pending、requiresAction 复位为 true
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[1]).toMatchObject({ kind: 'tool', seg: 1, toolCallId: 's1', status: 'pending', requiresAction: true })

    // 未知 toolCallId（③b D5）：suspended 帧建占位段（status='pending' + requiresAction=true）
    const other = store.appendAssistantMessage()
    store.handleFrame({
      type: 'suspended',
      runId: 'run-9',
      toolCalls: [{ toolCallId: 's-new', name: 'probe.auto', type: 'function', kind: 'FRONTEND', arguments: '{}', status: 'PENDING', executed: false }],
      seq: 3
    })
    expect(other.parts.some((part) => part.kind === 'tool' && part.toolCallId === 's-new')).toBe(true)
    const created = other.parts.find((part) => part.kind === 'tool' && part.toolCallId === 's-new')
    expect(created).toMatchObject({ kind: 'tool', status: 'pending', requiresAction: true, kind2: 'FRONTEND' })
  })

  it('批3③b D5 生命周期锁：suspended 建段 → 轮终态 done 帧 ⇒ 段收敛为 expired（轮终态兜底①）', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({
      type: 'suspended',
      runId: 'run-9',
      toolCalls: [{ toolCallId: 's2', name: 'danger_tool', type: 'function', kind: 'CONFIRM', arguments: '{}', status: 'PENDING', executed: false }],
      seq: 1
    })
    // "只发 suspended"的现场：段已建（占位 pending + requiresAction）
    expect(soleToolPart(assistant)).toMatchObject({ toolCallId: 's2', status: 'pending', requiresAction: true })
    expect(assistant.pendingCall).toMatchObject({ toolCallId: 's2', status: 'pending' })

    // 后端最终发出轮终态（done 帧；error 帧同口径）—— 占位段不得永久 pending
    store.handleFrame({ type: 'done', runId: 'run-9', seq: 2 })

    expect(soleToolPart(assistant)).toMatchObject({ toolCallId: 's2', status: 'expired', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
  })

  it('写点④ openGenerativeForm 失败收尾：本地 failed 段 + requiresAction=false（表单不武装）', async () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    // form schema 非法（fields 缺失）→ 防御性解析失败 → 取消收敛路径
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'g9',
      name: 'generative_form',
      args: JSON.stringify({ form: { scenario: 'broken' } }),
      timeoutSeconds: 120,
      runId: 'run-1',
      seq: 2
    })

    expect(store.activeForm).toBeNull()
    expect(assistant.toolRuns[0]).toMatchObject({ toolCallId: 'g9', status: 'failed' })
    expect(soleToolPart(assistant)).toMatchObject({ toolCallId: 'g9', status: 'failed', requiresAction: false })
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'g9', cancelled: true })
    ))
  })

  it('写点⑤ runFrontendTool 本地结局：handler 真结局落 succeeded，执行失败落 failed，均收 requiresAction', async () => {
    const store = useAiStore()
    registerPageHandler('probe.local-ok', () => '本地完成')
    registerPageHandler('probe.local-fail', () => {
      throw new Error('页面炸了')
    })
    try {
      const okMsg = store.appendAssistantMessage()
      store.handleFrame({
        type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 0
      })
      store.handleFrame({
        type: 'frontend_tool_request', toolCallId: 'l1', name: 'probe.local-ok', args: '{}', timeoutSeconds: 120, runId: 'run-1', seq: 1
      })
      await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
      expect(soleToolPart(okMsg)).toMatchObject({ toolCallId: 'l1', status: 'succeeded', result: '本地完成', requiresAction: false })

      const failMsg = store.appendAssistantMessage()
      store.handleFrame({
        type: 'frontend_tool_request', toolCallId: 'l2', name: 'probe.local-fail', args: '{}', timeoutSeconds: 120, runId: 'run-1', seq: 2
      })
      await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(2))
      expect(soleToolPart(failMsg)).toMatchObject({ toolCallId: 'l2', status: 'failed', requiresAction: false })
    } finally {
      unregisterPageHandler('probe.local-ok')
      unregisterPageHandler('probe.local-fail')
    }
  })

  it('写点⑥ loadHistory 后端历史分支（R-144 无状态占位口径）：旧载荷无 toolCallStates ⇒ 占位 kind2 UNKNOWN + status succeeded、result 按 id 回填、requiresAction=false', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 3,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    // R-149：TOOL 条目不产生 ChatMessage（消空白块），消息条数 = 非 TOOL 条目数
    // R-144 占位口径：无 toolCallStates（旧载荷/resume 重建轮）⇒ 占位值非事实断言，禁令见 ai.ts D7(B) 改判注记
    expect(store.messages).toHaveLength(2)
    const user = store.messages[0] as ChatMessage
    const assistant = store.messages[1] as ChatMessage
    expect(user.parts).toEqual([{ kind: 'text', seg: 0, text: '问题', streaming: false }])
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'h1',
      name: 'probe_tool',
      kind2: 'UNKNOWN',
      status: 'succeeded',
      args: '{"a":1}',
      result: 'ok',
      requiresAction: false
    })
  })

  it('写点⑥-R144 有状态读真态：toolCallStates 携 status/kind ⇒ 经词表单表映射为真态（非占位）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 3,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }],
          toolCallStates: { h1: { status: 'REJECTED', kind: 'CONFIRM' } }
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const assistant = store.messages[1] as ChatMessage
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'h1',
      kind2: 'CONFIRM', // T3：kind 同通道还原
      status: 'rejected', // 词表单表：REJECTED → rejected（非占位 succeeded）
      result: 'ok',
      requiresAction: false
    })
  })

  it('端到端：frontend_tool_request → 挂起 → 本地执行 → frontend_tool_result 回灌，段生命周期 pending→running→succeeded 全程同一段', async () => {
    const store = useAiStore()
    let release!: (value: string) => void
    registerPageHandler('probe.e2e-tool', () => new Promise<string>((resolve) => {
      release = resolve
    }))
    try {
      store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      const assistant = store.appendAssistantMessage()
      store.handleFrame({
        type: 'frontend_tool_request',
        toolCallId: 'e2e-1',
        name: 'probe.e2e-tool',
        args: '{"q":1}',
        timeoutSeconds: 120,
        runId: 'run-1',
        seq: 2
      })

      // ① 挂起：段 pending + requiresAction=true
      expect(store.suspended).toBe(true)
      const pendingPart = soleToolPart(assistant)
      expect(pendingPart).toMatchObject({ toolCallId: 'e2e-1', status: 'pending', requiresAction: true })
      const seg = pendingPart.seg

      // ② 本地执行开跑：段转 running（不再等动作），同一段
      await vi.waitFor(() => expect(soleToolPart(assistant).status).toBe('running'))
      expect(soleToolPart(assistant)).toMatchObject({ seg, toolCallId: 'e2e-1', requiresAction: false })

      // ③ 本地执行完成：段终态 succeeded，仍同一段
      release('端到端执行完成')
      await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
      expect(soleToolPart(assistant)).toMatchObject({ seg, status: 'succeeded', result: '端到端执行完成' })

      // ④ 后端结果帧回灌：就地更新（messageId 落段），不新建段
      store.handleFrame({
        type: 'frontend_tool_result',
        toolCallId: 'e2e-1',
        name: 'probe.e2e-tool',
        ok: true,
        executed: true,
        status: 'FRONTEND_RESULT',
        messageId: 'tool-e2e-1',
        result: '端到端执行完成',
        seq: 3
      })

      expect(toolPartsOf(assistant)).toHaveLength(1)
      expect(assistant.parts[1]).toMatchObject({
        kind: 'tool',
        seg,
        toolCallId: 'e2e-1',
        status: 'succeeded',
        messageId: 'tool-e2e-1',
        requiresAction: false
      })
      expect(store.suspended).toBe(false)
    } finally {
      unregisterPageHandler('probe.e2e-tool')
    }
  })
})


describe('R-149：loadHistory 双条目合并（TOOL 响应并入 ASSISTANT 工具段）', () => {
  it('① 一对多：一条 ASSISTANT 多 call ↔ 一条 TOOL 多 response（consume-on-match 逐个摘）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '双工具回答',
          toolCalls: [
            { id: 'c1', type: 'function', name: 'tool_a', arguments: '{"a":1}' },
            { id: 'c2', type: 'function', name: 'tool_b', arguments: '{"b":2}' }
          ]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [
            { id: 'c1', name: 'tool_a', responseData: 'ra' },
            { id: 'c2', name: 'tool_b', responseData: 'rb' }
          ]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(1)
    const parts = toolPartsOf(store.messages[0] as ChatMessage)
    expect(parts.map((part) => [part.toolCallId, part.result])).toEqual([
      ['c1', 'ra'],
      ['c2', 'rb']
    ])
  })

  it('② 连续多轮工具：各 TOOL 只吃自己那份，不串结果（R-150：同一用户轮内两条 A(T) 合并为一条）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '开工' },
        {
          type: 'ASSISTANT',
          text: '第一轮',
          toolCalls: [{ id: 'a1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'a1', name: 'probe_tool', responseData: 'ra' }] },
        {
          type: 'ASSISTANT',
          text: '第二轮',
          toolCalls: [{ id: 'b1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'b1', name: 'probe_tool', responseData: 'rb' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    // R-150：同一用户轮内连续 ASSISTANT（中间仅夹 TOOL）合并为一条 → USER + 1 条合并消息
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1]?.id).toBe('history-1') // 合并消息 id = 首条原始 index
    const merged = store.messages[1] as ChatMessage
    expect(merged.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'text', 'tool'])
    expect(merged.parts.map((part) => part.seg)).toEqual([0, 1, 2, 3]) // seg 全局单调重排
    const tools = toolPartsOf(merged)
    expect(tools.map((part) => [part.toolCallId, part.result])).toEqual([
      ['a1', 'ra'],
      ['b1', 'rb']
    ]) // 各 TOOL 只吃自己那份
    expect(merged.content).toBe('第一轮第二轮') // 空段保留、正文顺次拼接
    // 一轮一头像（store 层条数锁）：该用户轮 assistant 消息数 = 1
    expect(store.messages.filter((message) => message.role === 'assistant')).toHaveLength(1)
  })

  it('③ id 缺失序位兜底 + name 闸：同 name 才按序互配，name 不同 result=null 但卡片保留', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '无 id 调用',
          toolCalls: [
            { type: 'function', name: 'alpha', arguments: '{"x":1}' },
            { type: 'function', name: 'beta', arguments: '{"y":2}' }
          ]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [
            { id: '', name: 'alpha', responseData: 'ra' },
            { id: '', name: 'gamma', responseData: 'rg' }
          ]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const parts = toolPartsOf(store.messages[0] as ChatMessage)
    expect(parts[0]?.result).toBe('ra') // alpha ↔ alpha 序位配中
    expect(parts[1]?.result).toBeNull() // beta vs gamma：name 闸拦截
    expect(parts[1]).toMatchObject({ kind: 'tool', name: 'beta', status: 'succeeded' }) // 卡片保留不丢
    // C1（复核 m-1）：序位兜底命中后投影须同步 —— toolRuns[].result 非 null 且与 parts 一致
    // （旧实现下本断言为红：兜底在 deriveProjections 之后写 part.result，投影按值快照滞留 null）
    const runs = (store.messages[0] as ChatMessage).toolRuns
    expect(runs.map((run) => run.result)).toEqual(['ra', null])
  })

  it('④ 重复 id consume-on-match：防御性 tie-break（后出现覆盖仅为确定性兜底），索引摘除后后来者退序位兜底', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        {
          type: 'ASSISTANT',
          text: '一轮',
          toolCalls: [{ id: 'dup', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'dup', name: 'probe_tool', responseData: 'first' }] },
        {
          type: 'ASSISTANT',
          text: '二轮',
          toolCalls: [{ id: 'dup', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'dup', name: 'probe_tool', responseData: 'second' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    // R-150：无 USER 夹断 → 同一用户轮内两条 A(T) 合并为一条（两个 toolCallId 均为 'dup' 的段共存于合并消息）
    expect(store.messages).toHaveLength(1)
    const merged = store.messages[0] as ChatMessage
    expect(merged.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'text', 'tool'])
    const tools = toolPartsOf(merged)
    // 防御性 tie-break（C2，红队 O-1）：后端 toolCallId 唯一、实际不产重复 id；
    // 以下断言只锁"实现确定性"，不宣称符合后端实际产出序
    expect(tools.map((part) => part.result)).toEqual(['second', 'first']) // 后出现覆盖 + 序位兜底
    expect(tools.map((part) => part.toolCallId)).toEqual(['dup', 'dup'])
  })

  it('⑤ responseData=null：result=null 但工具卡片仍在', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '空结果',
          toolCalls: [{ id: 'n1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'n1', name: 'probe_tool', responseData: null }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const part = soleToolPart(store.messages[0] as ChatMessage)
    expect(part).toMatchObject({ toolCallId: 'n1', result: null, status: 'succeeded', requiresAction: false })
  })

  it('⑥ 残缺无主 response：无主 TOOL 响应兜底丢弃，不报错不抛异常', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        { type: 'USER', text: '问题' },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan', name: 'probe_tool', responseData: 'ro' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory() // 不抛即过

    expect(store.messages).toHaveLength(1) // 孤儿 TOOL 不产生消息
    expect((store.messages[0] as ChatMessage).role).toBe('user')
  })

  it('⑦ 镜像路径回归：同一历史经"后端合并 → 镜像写盘 → 再走 loadHistory"，parts 深度相等（结果不丢）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 3,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()
    const viaBackend = JSON.parse(JSON.stringify(store.messages.map((message) => message.parts)))
    expect(soleToolPart(store.messages[1] as ChatMessage).result).toBe('ok')

    // 模拟"刷新前镜像已写盘"（v2 形态 = 合并后的 ChatMessage），再走一遍 loadHistory：
    // 镜像分支先水合、后端分支再整替 —— 两遍产物必须逐段一致
    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: null,
      messages: store.messages
    }))
    await store.loadHistory()

    const viaMirrorRoundTrip = store.messages.map((message) => message.parts)
    expect(JSON.parse(JSON.stringify(viaMirrorRoundTrip))).toEqual(viaBackend)
    expect(soleToolPart(store.messages[1] as ChatMessage).result).toBe('ok')
  })

  it('⑧ 空白块消除：TOOL 条目零消息产出，id 锁死原始数组下标（含被跳过条目的空洞）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 5,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }] },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan', name: 'probe_tool', responseData: 'ro' }] },
        { type: 'SYSTEM', text: '系统提示' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(3) // 两条 TOOL 都不产生消息
    expect(store.messages.map((message) => message.id)).toEqual(['history-0', 'history-1', 'history-4'])
    // 无任何"TOOL 条目落成的空白 assistant 块"（空 text 段 + 无工具段）
    for (const message of store.messages) {
      const isBlankToolBlock = message.parts.length === 1
        && message.parts[0]?.kind === 'text'
        && message.parts[0].text === ''
        && message.role === 'assistant'
      expect(isBlankToolBlock).toBe(false)
    }
  })
})


describe('R-149 收口修 C3：两遍扫描序无关性补测', () => {
  it('TOOL 在 ASSISTANT 前 + 多 TOOL 夹一条 ASSISTANT：配对正确、无主响应丢弃、零空白块', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 5,
      messages: [
        // 响应先于调用出现（序无关判据①）
        { type: 'TOOL', text: '', toolResponses: [{ id: 'c1', name: 'probe_tool', responseData: 'rc1' }] },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'c1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan', name: 'probe_tool', responseData: 'ro' }] },
        { type: 'ASSISTANT', text: '补充' },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan2', name: 'probe_tool', responseData: 'ro2' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    // R-150：无 USER/SYSTEM 夹断 → 两条 ASSISTANT（含纯文本条）合并为一条，id = 首条原始 index
    expect(store.messages).toHaveLength(1) // 三条 TOOL 零消息产出 + 两条 ASSISTANT 合并
    expect(store.messages.map((message) => message.id)).toEqual(['history-1']) // 原始下标含空洞
    const merged = store.messages[0] as ChatMessage
    expect(merged.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'text'])
    expect(soleToolPart(merged).result).toBe('rc1') // 前置于调用的响应照样配对
  })

  it('全 TOOL 历史：零消息、不抛异常（残缺历史兜底丢弃）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        { type: 'TOOL', text: '', toolResponses: [{ id: 'o1', name: 'probe_tool', responseData: 'r1' }] },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'o2', name: 'probe_tool', responseData: 'r2' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory() // 不抛即过

    expect(store.messages).toHaveLength(0)
  })
})


describe('R-150：loadHistory 连续 ASSISTANT 合并（同一用户轮内，遇 USER/SYSTEM 即断）', () => {
  it('真形状基准（C-6 改写）：USER + A(T) + TOOL + A(text) → 合并为一条；空白 text + toolCalls ⇒ 无空前导段，首段为工具', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '', // R-144 C-6：空白 text + toolCalls ⇒ 不产 text 段（载荷无段序字段，消空打头段）
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }] },
        { type: 'ASSISTANT', text: '补充结论' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(2) // USER + 合并消息（一轮一头像：assistant 条数 = 1）
    const merged = store.messages[1] as ChatMessage
    expect(merged.id).toBe('history-1') // 合并消息 id = 首条原始 index
    // C-6 必改锁：空白 text 不产空前导段 ⇒ kinds ['tool','text']、seg [0,1]、首段为工具
    expect(merged.parts.map((part) => part.kind)).toEqual(['tool', 'text'])
    expect(merged.parts.map((part) => part.seg)).toEqual([0, 1]) // seg 全局单调重排（渲染 key = kind+'-'+seg）
    expect(toolPartsOf(merged)[0]).toMatchObject({ seg: 0, result: 'ok' }) // 首段 = 工具段
    expect(merged.content).toBe('补充结论') // 正文 = 各条目 text 顺次拼接（不变）
  })

  it('夹 USER 不误并：两个用户轮各自成窗，assistant 条数 = 2', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 6,
      messages: [
        { type: 'USER', text: '第一问' },
        {
          type: 'ASSISTANT',
          text: '答一',
          toolCalls: [{ id: 'a1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'a1', name: 'probe_tool', responseData: 'ra' }] },
        { type: 'USER', text: '第二问' },
        {
          type: 'ASSISTANT',
          text: '答二',
          toolCalls: [{ id: 'b1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'b1', name: 'probe_tool', responseData: 'rb' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(4) // USER/A/USER/A：遇 USER 即断，不成窗
    expect(store.messages.filter((message) => message.role === 'assistant')).toHaveLength(2)
    expect(toolPartsOf(store.messages[1] as ChatMessage)[0]?.result).toBe('ra')
    expect(toolPartsOf(store.messages[3] as ChatMessage)[0]?.result).toBe('rb')
  })

  it('responseData=null 仍并：TOOL 条目存在即算有回应（闸按结构性判据，不看响应内容）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'n1', type: 'function', name: 'probe_tool', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'n1', name: 'probe_tool', responseData: null }] },
        { type: 'ASSISTANT', text: '收尾' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(2)
    expect(toolPartsOf(store.messages[1] as ChatMessage)[0]?.result).toBeNull() // 配对成功但响应为 null
  })

  it('有 TOOL 回应但前端配对失败仍并：同 name 无 id 多 call 只配中其一，闸不看配对成败', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [
            { type: 'function', name: 'probe_tool', arguments: '{"x":1}' },
            { type: 'function', name: 'probe_tool', arguments: '{"y":2}' }
          ]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: '', name: 'probe_tool', responseData: 'only-one' }] },
        { type: 'ASSISTANT', text: '收尾' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(2) // 仍合并：窗口内存在可配对 TOOL 条目
    const tools = toolPartsOf(store.messages[1] as ChatMessage)
    expect(tools.map((part) => part.result)).toEqual(['only-one', null]) // 第二个 call 配对失败 result=null，卡片仍在
  })

  it('悬空 A(T) + 下轮 USER：不并不吞——悬空卡独立成消息且保留到下轮之后', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 5,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '调用工具',
          toolCalls: [{ id: 'd1', type: 'function', name: 'probe_tool', arguments: '{}' }]
          // 无 TOOL 条目：悬空 A(T)
        },
        { type: 'USER', text: '追一问' },
        { type: 'ASSISTANT', text: '追一答' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(4) // USER/A(悬空)/USER/A：无任何合并或吞并
    const dangling = store.messages[1] as ChatMessage
    expect(dangling.parts.map((part) => part.kind)).toEqual(['text', 'tool']) // 卡片保留不丢
    expect(toolPartsOf(dangling)[0]?.result).toBeNull()
  })

  it('drop 规则正例：无 toolCalls 且 text 空的 ASSISTANT 直接丢弃（不产生空白块）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '问题' },
        { type: 'ASSISTANT', text: '' }, // 应被 drop
        { type: 'ASSISTANT', text: '有内容' },
        { type: 'USER', text: '下一句' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(3) // USER/A(有内容)/USER
    expect(store.messages.map((message) => message.id)).toEqual(['history-0', 'history-2', 'history-3'])
    expect((store.messages[1] as ChatMessage).content).toBe('有内容')
  })

  it('⑦ 式镜像深度相等：合并形态经镜像写盘后再走 loadHistory，parts 深度相等', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }] },
        { type: 'ASSISTANT', text: '补充' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()
    const viaBackend = JSON.parse(JSON.stringify(store.messages.map((message) => message.parts)))
    expect(store.messages).toHaveLength(2) // 合并已发生

    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: null,
      messages: store.messages
    }))
    await store.loadHistory()

    expect(JSON.parse(JSON.stringify(store.messages.map((message) => message.parts)))).toEqual(viaBackend)
  })

  it('merged id 与兜底 namespace：被并条目无 id call 的序位兜底 toolCallId 用其自身原始 index', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 4,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ type: 'function', name: 'alpha', arguments: '{}' }] // 原始 index 1，无 id
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: '', name: 'alpha', responseData: 'ra' }] },
        { type: 'ASSISTANT', text: '收尾' }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(2)
    const merged = store.messages[1] as ChatMessage
    expect(merged.id).toBe('history-1') // 合并消息 id = 首条原始 index
    // 被并条目的兜底 toolCallId 不得漂到首条 index（防同 name 无 id 多 call 撞号）
    expect(toolPartsOf(merged)[0]?.toolCallId).toBe('history-1-alpha')
  })

  it('多轮工具同轮合并（R-150 主形态）：A(T)+TOOL+A(T)+TOOL 一轮四段、两条工具结果各归其位', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 5,
      messages: [
        { type: 'USER', text: '问' },
        {
          type: 'ASSISTANT',
          text: '先查',
          toolCalls: [{ id: 'q1', type: 'function', name: 'lookup', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'q1', name: 'lookup', responseData: 'rq' }] },
        {
          type: 'ASSISTANT',
          text: '再写',
          toolCalls: [{ id: 'w1', type: 'function', name: 'writer', arguments: '{}' }]
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'w1', name: 'writer', responseData: 'rw' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    expect(store.messages).toHaveLength(2)
    const merged = store.messages[1] as ChatMessage
    expect(merged.parts.map((part) => [part.kind, part.seg])).toEqual([
      ['text', 0],
      ['tool', 1],
      ['text', 2],
      ['tool', 3]
    ])
    expect(toolPartsOf(merged).map((part) => [part.name, part.result])).toEqual([
      ['lookup', 'rq'],
      ['writer', 'rw']
    ])
  })
})


describe('R-144：history 载荷 toolCallStates 读真态（T1 映射 / T3 kind 还原 / C-6 保序）', () => {
  it('一轮多工具异态（T1 映射而非标量的验收锚）：两 toolCalls 状态各异 ⇒ 两段各读真态', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '双工具',
          toolCalls: [
            { id: 'c1', type: 'function', name: 'tool_a', arguments: '{}' },
            { id: 'c2', type: 'function', name: 'tool_b', arguments: '{}' }
          ],
          toolCallStates: {
            c1: { status: 'EXECUTED', kind: 'CONFIRM' },
            c2: { status: 'TIMEOUT', kind: 'BACKEND' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const parts = toolPartsOf(store.messages[0] as ChatMessage)
    expect(parts.map((part) => [part.toolCallId, part.status, part.kind2])).toEqual([
      ['c1', 'succeeded', 'CONFIRM'],
      ['c2', 'expired', 'BACKEND']
    ])
  })

  it('两处定稿修正：FRONTEND_RESULT ⇒ succeeded（非瞬态）、APPROVED ⇒ pending（不取 expired）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '前端工具轮',
          toolCalls: [
            { id: 'f1', type: 'function', name: 'gen_form', arguments: '{}' },
            { id: 'f2', type: 'function', name: 'lookup', arguments: '{}' }
          ],
          toolCallStates: {
            f1: { status: 'FRONTEND_RESULT' },
            f2: { status: 'APPROVED' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const parts = toolPartsOf(store.messages[0] as ChatMessage)
    expect(parts.map((part) => [part.toolCallId, part.status])).toEqual([
      ['f1', 'succeeded'], // FRONTEND_RESULT 定稿修正
      ['f2', 'pending'] // APPROVED 定稿修正（T2 升格判据未命中时兜底 pending）
    ])
  })

  it('T3 kind 还原：有 kind 还原其值；缺 kind 保持 UNKNOWN（不臆造默认值）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: 'kind 面',
          toolCalls: [
            { id: 'k1', type: 'function', name: 'with_kind', arguments: '{}' },
            { id: 'k2', type: 'function', name: 'no_kind', arguments: '{}' }
          ],
          toolCallStates: {
            k1: { status: 'EXECUTED', kind: 'FRONTEND' },
            k2: { status: 'EXECUTED' } // 无 kind 键
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const parts = toolPartsOf(store.messages[0] as ChatMessage)
    expect(parts.map((part) => [part.toolCallId, part.kind2])).toEqual([
      ['k1', 'FRONTEND'],
      ['k2', 'UNKNOWN'] // 禁臆造：缺数据不猜 BACKEND/FRONTEND
    ])
  })

  it('词表快照兜底行：DONE ⇒ succeeded；FAILED 不参与段态推导 ⇒ 落占位（null 口径）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '快照兜底',
          toolCalls: [
            { id: 'd1', type: 'function', name: 'done_tool', arguments: '{}' },
            { id: 'd2', type: 'function', name: 'failed_tool', arguments: '{}' }
          ],
          toolCallStates: {
            d1: { status: 'DONE' },
            d2: { status: 'FAILED' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const parts = toolPartsOf(store.messages[0] as ChatMessage)
    expect(parts.map((part) => [part.toolCallId, part.status])).toEqual([
      ['d1', 'succeeded'],
      ['d2', 'succeeded'] // FAILED → null → 占位（非事实断言，禁令照旧）
    ])
  })

  it('C-6 保序：text 非空 ⇒ text 段仍前置（回归对照）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '有正文',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{}' }],
          toolCallStates: { h1: { status: 'EXECUTED' } }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const message = store.messages[0] as ChatMessage
    expect(message.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(message.parts.map((part) => part.seg)).toEqual([0, 1])
    expect(toolPartsOf(message)[0]?.status).toBe('succeeded')
  })
})
