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
import { describe, expect, it, vi, beforeEach } from 'vitest'
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
  confirm: vi.fn()
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

  it('confirm 提交撞 DUPLICATE：段并入 running（approved 非 ToolRunStatus 合法值），挂起卡收掉', async () => {
    const store = useAiStore()
    const assistant = armConfirm(store, 'c1')
    api.confirm.mockRejectedValue(new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, null))

    await store.confirm(true)

    expect(assistant.pendingCall).toBeNull()
    expect(soleToolPart(assistant)).toMatchObject({ status: 'running', requiresAction: false })
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

  it('写点⑥ loadHistory 后端历史分支：text 段 + 工具段按序构造（result 按 id 回填、requiresAction=false）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }],
          toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

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
