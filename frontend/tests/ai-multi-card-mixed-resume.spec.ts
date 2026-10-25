/**
 * Task 5 / FE-22 + FE-17：多卡、**混合卡**恢复与 409 之后的可恢复性。
 *
 * 缺陷（审查口径）：
 * - 挂起收口判据 `clearSuspendedIfNoOtherConfirms` 只数 **CONFIRM** 段。一轮里同时有
 *   确认门 + 前端卡（生成式表单 / 上传交接 / 表格预览）时，决掉确认门就把 `suspended` 置
 *   false ⇒ ① 输入区在还有卡等人时被解锁；② 后续那张非 CONFIRM 卡提交后
 *   `reconnectSuspendedRun` 因 `suspended=false` 早退 ⇒ 本轮再无连接，剩余卡永不出现，
 *   下一次发送撞 409（后端这一轮仍 SUSPENDED）。
 * - 409 幂等拒绝的权威校正只做**一次**（3s 后拉 `runs/current`）：条目仍 PENDING 就永久停在
 *   「已提交，同步中…」，双按钮与输入区一直禁用，只能刷新。
 *
 * 本组只驱动真实 store（mock 掉 HTTP + 帧源），不挂载面板。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { GATE_SYNC_GRACE_MS, useAiStore } from '@/stores/ai'
import type { AiRunCurrent, AiRunCurrentEntry, ChatMessage } from '@/types/ai'

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

vi.mock('@/api/ai', () => ({ aiApi: api }))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  // 续收连接保持"在跑"（永不结算）：断言只关心"有没有发起重连"
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.confirm.mockResolvedValue({ accepted: true, duplicate: false })
  api.frontendToolResult.mockResolvedValue({ accepted: true, duplicate: false })
  api.runsCurrent.mockResolvedValue(noSuspension())
})

afterEach(() => {
  vi.useRealTimers()
})

/** runs/current 的空快照（无在途轮）。 */
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

/** 一条待决 CONFIRM 条目。 */
function confirmEntry(toolCallId: string, name = 'danger_tool'): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind: 'CONFIRM', name, arguments: '{"x":1}', req: true }
}

/** 一条待决生成式表单条目（与真实帧同形：args.form 是合法 schema）。 */
function formEntry(toolCallId: string): AiRunCurrentEntry {
  return {
    toolCallId,
    entryStatus: 'PENDING',
    kind: 'FRONTEND',
    name: 'generative_form',
    arguments: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
    req: true
  }
}

/** 一轮"挂起等待人"的快照。 */
function suspendedSnapshot(entries: AiRunCurrentEntry[], runId = 'run-1'): AiRunCurrent {
  return {
    found: true,
    runId,
    snapshotStatus: 'PENDING',
    pendingEntries: entries,
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 4
  }
}

function partOf(messages: ChatMessage[], toolCallId: string) {
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.kind === 'tool' && part.toolCallId === toolCallId) {
        return part
      }
    }
  }
  return null
}

describe('混合卡（CONFIRM + FRONTEND）恢复：决掉一张后其余仍可提交并续流', () => {
  it('决掉确认门后：仍挂起、下一张（表单）已补、其提交会带 lastSeq 重连', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([confirmEntry('c1'), formEntry('g1')]))

    await store.restoreSuspendedSnapshot()
    expect(store.suspended).toBe(true)
    // 串行呈现：只补第一张（CONFIRM），表单卡在队列里
    expect(store.activeForm).toBeNull()

    api.reattachRun.mockClear()
    await store.confirm(true, undefined, 'c1')

    // ① 表单卡还在等人 ⇒ 不得解锁输入 / 挂起态
    expect(store.suspended).toBe(true)
    // ② 下一张已就地补上（串行队列 +1）
    expect(store.activeForm?.toolCallId).toBe('g1')
    // ③ 决策送达后的续流只发起一次（领跑卡自己的那次）
    expect(api.reattachRun).toHaveBeenCalledTimes(1)

    // ④ 非 CONFIRM 卡提交后同样必须续流：否则本轮再无连接，剩余帧与后续卡永远不到
    await store.submitGenerativeForm({ k: 'v' })
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(api.reattachRun).toHaveBeenCalledTimes(2)
  })

  it('时序反转：confirm_decision 帧先于 confirm POST 响应到达 ⇒ 仍不得解锁、不得丢重连与第二张卡', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([confirmEntry('c1'), formEntry('g1')]))

    await store.restoreSuspendedSnapshot()
    expect(store.suspended).toBe(true)
    // 串行呈现：第二张还在 `restoredPendingQueue` 里、**尚未建段**
    expect(store.activeForm).toBeNull()
    expect(store.restoredPendingQueue).toHaveLength(1)

    api.reattachRun.mockClear()
    // 真实 SSE 的到达顺序不受前端控制：结局帧可能与 POST 响应同锁先到（合法时序）
    api.confirm.mockImplementation(async () => {
      store.handleFrame({
        type: 'confirm_decision',
        toolCallId: 'c1',
        name: 'danger_tool',
        decision: 'APPROVED',
        approved: true,
        status: 'APPROVED',
        runId: 'run-1',
        seq: 5
      })
      return { accepted: true, duplicate: false }
    })

    await store.confirm(true, undefined, 'c1')

    // ① 队列里的第二张（尚无段）必须被计为"还有人等" ⇒ 解锁前不得收口挂起态
    expect(store.suspended).toBe(true)
    // ② 必须续流（否则本轮剩余帧与第二张的请求帧永远没人收）
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    // ③ 第二张必须补出来，且队列出清
    expect(store.activeForm?.toolCallId).toBe('g1')
    expect(store.restoredPendingQueue).toHaveLength(0)
  })

  it('纯 CONFIRM 三卡：逐张补卡，最后一张决完才收口（既有能力不破）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(
      suspendedSnapshot([confirmEntry('c1'), confirmEntry('c2'), confirmEntry('c3')])
    )

    await store.restoreSuspendedSnapshot()
    expect(store.suspended).toBe(true)
    expect(partOf(store.messages, 'c1')).not.toBeNull()
    api.reattachRun.mockClear()

    await store.confirm(true, undefined, 'c1')
    expect(store.suspended).toBe(true)
    expect(partOf(store.messages, 'c2')).not.toBeNull()

    await store.confirm(false, '不要了', 'c2')
    expect(store.suspended).toBe(true)
    expect(partOf(store.messages, 'c3')).not.toBeNull()

    await store.confirm(true, undefined, 'c3')
    expect(store.suspended).toBe(false)
    // 每一张都恰好一次续流；非末卡仍等待输入，末卡必须恢复执行态的重连语义
    expect(api.reattachRun).toHaveBeenCalledTimes(3)
    expect(api.reattachRun.mock.calls.map((call) => call[2]?.awaitingInput ?? false))
      .toEqual([true, true, false])
  })
})

describe('409 幂等拒绝后的恢复（不止一次权威校正，且用尽后必须解锁）', () => {
  /** 造一张活的确认门（start → confirm_request），返回 toolCallId。 */
  function armGate(store: ReturnType<typeof useAiStore>, toolCallId = 'c9'): string {
    store.handleFrame({ type: 'start', runId: 'run-9', sessionId: store.sessionId, seq: 1 })
    store.appendAssistantMessage()
    store.handleFrame({
      type: 'confirm_request',
      toolCallId,
      name: 'danger_tool',
      args: '{"x":1}',
      timeoutSeconds: 120,
      seq: 2
    })
    return toolCallId
  }

  it('权威仍报 PENDING ⇒ 有界重试；用尽后解除提交锁（可重试/可刷新），不永久卡死', async () => {
    vi.useFakeTimers()
    const store = useAiStore()
    store.sessionId = 'sess'
    const toolCallId = armGate(store)
    api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))
    // 滞后读：/runs/current 一直报 PENDING（真实触发条件）
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([confirmEntry(toolCallId)], 'run-9'))

    await store.confirm(true, undefined, toolCallId)
    expect(store.gateSubmissionOf(toolCallId)?.syncing).toBe(true)

    // 第一档宽限到期：一次权威校正；仍 PENDING ⇒ 现状是"到此为止"
    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS + 10)
    expect(api.runsCurrent.mock.calls.length).toBe(1)

    // 继续等：必须还有后续校正（不是一次性），且总时长有界
    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS * 10)
    expect(api.runsCurrent.mock.calls.length).toBeGreaterThan(1)

    // 用尽后不得留在「同步中…」：解掉提交锁，让用户能重试（409 是幂等拒绝，重试不会翻转裁决）
    expect(store.gateSubmissionOf(toolCallId)).toBeNull()
  })

  it('迟到但真实到达的裁决 ⇒ 后续校正把它落成段终态并撤提交态', async () => {
    vi.useFakeTimers()
    const store = useAiStore()
    store.sessionId = 'sess'
    const toolCallId = armGate(store, 'c10')
    api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))
    api.runsCurrent
      .mockResolvedValueOnce(suspendedSnapshot([confirmEntry(toolCallId)], 'run-9'))
      .mockResolvedValueOnce(suspendedSnapshot([{ ...confirmEntry(toolCallId), entryStatus: 'APPROVED' }], 'run-9'))

    await store.confirm(true, undefined, toolCallId)
    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS + 10)
    // 第一次仍是 PENDING：不伪造裁决
    expect(store.gateSubmissionOf(toolCallId)?.syncing).toBe(true)

    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS * 3)
    const part = partOf(store.messages, toolCallId)
    expect(part?.status).toBe('running')
    expect(part?.requiresAction).toBe(false)
    expect(store.gateSubmissionOf(toolCallId)).toBeNull()
  })

  it('409 校正拿到真实裁决时：恢复队列里的下一张仍要补出（不得锁死输入区且无卡可点）', async () => {
    vi.useFakeTimers()
    const store = useAiStore()
    store.sessionId = 'sess'
    // 恢复现场：c1 已建段，g1 还在队列（尚未建段）
    api.runsCurrent
      .mockResolvedValueOnce(suspendedSnapshot([confirmEntry('c1'), formEntry('g1')]))
      .mockResolvedValue(suspendedSnapshot(
        [{ ...confirmEntry('c1'), entryStatus: 'APPROVED' }, formEntry('g1')]
      ))
    await store.restoreSuspendedSnapshot()
    expect(store.restoredPendingQueue).toHaveLength(1)

    api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))
    await store.confirm(true, undefined, 'c1')
    expect(store.gateSubmissionOf('c1')?.syncing).toBe(true)
    // 409 当场不解锁、不补卡（等权威裁决）
    expect(store.suspended).toBe(true)

    // 宽限到期 ⇒ 权威校正拿到 APPROVED ⇒ 决策落地：队列里的第二张必须补出
    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS + 10)
    expect(store.gateSubmissionOf('c1')).toBeNull()
    expect(store.activeForm?.toolCallId).toBe('g1')
    expect(store.restoredPendingQueue).toHaveLength(0)
    expect(store.suspended).toBe(true)
  })
})

describe('M-a（fix2）：409 校正必须消费同一份权威 pendingEntries 再补卡', () => {
  it('队列项权威已是 APPROVED（已裁决、执行中）⇒ 按 running 展现且不可点，不补成可点卡', async () => {
    vi.useFakeTimers()
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent
      .mockResolvedValueOnce(suspendedSnapshot([confirmEntry('c1'), confirmEntry('c2')]))
      .mockResolvedValue(suspendedSnapshot([
        { ...confirmEntry('c1'), entryStatus: 'APPROVED' },
        { ...confirmEntry('c2'), entryStatus: 'APPROVED' }
      ]))
    await store.restoreSuspendedSnapshot()
    expect(store.restoredPendingQueue).toHaveLength(1)

    api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))
    await store.confirm(true, undefined, 'c1')
    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS + 10)

    // 权威说"已裁决、执行中"⇒ 仍然要展现（不能简单丢弃），但只能是只读的 running 卡
    const c2 = partOf(store.messages, 'c2')
    expect(c2?.status).toBe('running')
    expect(c2?.requiresAction).toBe(false)
    expect(c2?.decision).toBe('approved')
    expect(store.restoredPendingQueue).toHaveLength(0)
    // 屏上不得留可点入口（原缺陷：补成 pending+requiresAction 的可点卡，点下去必 409）
    expect(store.pendingToolCall).toBeNull()
    expect(store.suspended).toBe(false)
  })

  it('队列项权威已 EXECUTED（真终态）⇒ 出队且不重建（不产生任何段）', async () => {
    vi.useFakeTimers()
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent
      .mockResolvedValueOnce(suspendedSnapshot([confirmEntry('c1'), confirmEntry('c2')]))
      .mockResolvedValue(suspendedSnapshot([
        { ...confirmEntry('c1'), entryStatus: 'APPROVED' },
        { ...confirmEntry('c2'), entryStatus: 'EXECUTED' }
      ]))
    await store.restoreSuspendedSnapshot()
    expect(store.restoredPendingQueue).toHaveLength(1)

    api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))
    await store.confirm(true, undefined, 'c1')
    await vi.advanceTimersByTimeAsync(GATE_SYNC_GRACE_MS + 10)

    expect(partOf(store.messages, 'c2')).toBeNull()
    expect(store.restoredPendingQueue).toHaveLength(0)
    expect(store.pendingToolCall).toBeNull()
    expect(store.suspended).toBe(false)
  })
})

describe('M-b（fix2）：前端工具 409（他端已决）后不得丢卡 / 锁死', () => {
  it('恢复队列里还有下一张 ⇒ 补出且仍可提交（409 当次不能把本轮锁在无卡可点）', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([formEntry('g1'), formEntry('g2')]))
    await store.restoreSuspendedSnapshot()
    expect(store.activeForm?.toolCallId).toBe('g1')
    expect(store.restoredPendingQueue).toHaveLength(1)

    api.frontendToolResult
      .mockRejectedValueOnce(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, { status: 'FRONTEND_RESULT' }))
      .mockResolvedValue({ accepted: true, duplicate: false })
    await store.submitGenerativeForm({ k: 'v' })

    // 段侧：他端已决 ⇒ 本卡就地收敛，不再等人
    const g1 = partOf(store.messages, 'g1')
    expect(g1?.status).toBe('succeeded')
    expect(g1?.requiresAction).toBe(false)
    // 队列侧：下一张必须补出（否则输入区锁死、屏上无可点卡）
    expect(store.activeForm?.toolCallId).toBe('g2')
    expect(store.activeForm?.status).toBe('filling')
    expect(store.restoredPendingQueue).toHaveLength(0)
    expect(store.suspended).toBe(true)
    // 可提交性：补出的这张真的能提交并续流
    await store.submitGenerativeForm({ k: 'v2' })
    expect(api.frontendToolResult).toHaveBeenCalledTimes(2)
    expect(store.activeForm?.status).toBe('submitted')
  })

  it('没有下一张 ⇒ 按真实待决解除挂起（不留「等待确认」死锁），且不盲连', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([formEntry('g1')]))
    await store.restoreSuspendedSnapshot()
    expect(store.suspended).toBe(true)
    api.reattachRun.mockClear()

    api.frontendToolResult.mockRejectedValue(
      new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, { status: 'FRONTEND_RESULT' })
    )
    await store.submitGenerativeForm({ k: 'v' })

    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull()
    expect(api.reattachRun).not.toHaveBeenCalled()
  })
})

describe('M-d（fix2）：结局帧先于 POST 时不得丢续收（含 req=false 的未来卡）', () => {
  it('confirm_decision 帧先到 ⇒ 决策送达仍要续收；随后的下一张请求帧能建卡', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    // 第二张 `req=false`（请求帧还没发）⇒ 按设计不进恢复队列：唯一的下发通道是实时帧
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([
      confirmEntry('c1'),
      { ...formEntry('g2'), req: false }
    ]))
    // 真实时序：恢复那条连接在 park 时被服务端收掉（loading 归 false），决策帧已在该连接上到达
    api.reattachRun.mockResolvedValue(undefined)
    await store.restoreSuspendedSnapshot()
    await Promise.resolve()
    await Promise.resolve()
    expect(store.restoredPendingQueue).toHaveLength(0)
    expect(store.loading).toBe(false)

    api.reattachRun.mockClear()
    api.confirm.mockImplementation(async () => {
      store.handleFrame({
        type: 'confirm_decision',
        toolCallId: 'c1',
        name: 'danger_tool',
        decision: 'APPROVED',
        approved: true,
        status: 'APPROVED',
        runId: 'run-1',
        seq: 5
      })
      return { accepted: true, duplicate: false }
    })
    await store.confirm(true, undefined, 'c1')

    // 不得因 `suspended` 已被帧清掉而跳过续收（否则这张卡的请求帧永远没人收）
    expect(api.reattachRun).toHaveBeenCalledTimes(1)

    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'g2',
      name: 'generative_form',
      args: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
      runId: 'run-1',
      seq: 6
    })
    expect(store.suspended).toBe(true)
    expect(store.activeForm?.toolCallId).toBe('g2')
  })

  it('反向①：POST 期间本轮已收口（done 先到）⇒ 不得对终态重连', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([
      confirmEntry('c1'),
      { ...formEntry('g2'), req: false }
    ]))
    api.reattachRun.mockResolvedValue(undefined)
    await store.restoreSuspendedSnapshot()
    await Promise.resolve()
    await Promise.resolve()
    api.reattachRun.mockClear()

    api.confirm.mockImplementation(async () => {
      store.handleFrame({ type: 'done', runId: 'run-1', seq: 9 })
      return { accepted: true, duplicate: false }
    })
    await store.confirm(true, undefined, 'c1')

    expect(api.reattachRun).not.toHaveBeenCalled()
    expect(store.suspended).toBe(false)
  })

  it('反向②：连接仍在跑（loading=true）⇒ 不得再开第二条连接', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    // 恢复那条连接还没收（never-resolving）：帧由它送达，后续帧也由它送达 ⇒ 不需要新连接
    api.runsCurrent.mockResolvedValue(suspendedSnapshot([confirmEntry('c1')]))
    await store.restoreSuspendedSnapshot()
    expect(store.loading).toBe(true)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    api.reattachRun.mockClear()

    api.confirm.mockImplementation(async () => {
      store.handleFrame({
        type: 'confirm_decision',
        toolCallId: 'c1',
        name: 'danger_tool',
        decision: 'APPROVED',
        approved: true,
        status: 'APPROVED',
        runId: 'run-1',
        seq: 5
      })
      return { accepted: true, duplicate: false }
    })
    await store.confirm(true, undefined, 'c1')

    // 帧清掉了挂起，但连接还在跑（后续帧仍由它送达）⇒ 一条新连接都不该开
    expect(store.suspended).toBe(false)
    expect(api.reattachRun).not.toHaveBeenCalled()
  })

  it('反向③：inline 前端工具回灌（流还开着）⇒ 不得另开连接', async () => {
    const store = useAiStore()
    store.sessionId = 'sess'
    void store.reattachActive('run-live')
    await Promise.resolve()
    await Promise.resolve()
    expect(store.loading).toBe(true)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)

    await store.submitFrontendToolResult('t-inline', '结果', 'frontend-executor')
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
  })
})
