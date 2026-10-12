/**
 * 对话时序包 批2（子包②权威翻转）：`frontend/src/utils/parts.ts` 纯函数层 +
 * `handleFrame` 集成（seq 幂等 / 乱序兜底 / derive 时机 / R2-5 旧轮帧）回归锁。
 *
 * 口径（施工卡-刷新版 §4.2 验证方式五条 + §7 R2-1/R2-5 + o-R1）：
 *   ① seq 为 null 的旧帧照收；② 同 seq 重复投递幂等；③ text→tool→text 得 3 段且序正确；
 *   ④ tool_result 就地更新不新建段；⑤ message_end 不改段结构；
 *   ⑥ R2-5：resetSession 后旧轮帧不写新会话；⑦ o-R1：reasoning/text 交错与轮终态封口；
 *   ⑧ deriveProjections 全量映射（content join 等价 / toolRuns / pendingCall）。
 *
 * 翻转后口径：`parts` 是唯一真值，`content` / `reasoning` / `toolRuns` / `pendingCall`
 * 一律由 `deriveProjections` 派生（delta/reasoning 高频路径按 §6.1 性能注增量维护 content/reasoning）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart, ToolPart } from '@/types/ai'
import { GENERATIVE_FORM_TOOL } from '@/types/ai'
import {
  alreadyApplied,
  applyFrameToParts,
  deriveProjections,
  markApplied,
  openStreamPart,
  sealTrailingStreamPart,
  upsertToolPart
} from '@/utils/parts'
import { useAiStore } from '@/stores/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  chat: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  api.frontendToolResult.mockReset()
  api.confirm.mockReset()
  api.chat.mockReset()
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.frontendToolResult.mockResolvedValue({ ok: true })
})

/** 生产同形的助手消息：以"流式空文本段"起头（同 appendAssistantMessage）。 */
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

/** 工具段（断言便利）。 */
function toolPart(message: ChatMessage, toolCallId: string): ToolPart {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  expect(part).toBeDefined()
  return part as ToolPart
}

/** 段结构摘要（段序 + 编号 + 文本；不含流式标志 —— message_end 对比用）。 */
function segDigest(parts: MessagePart[]): string {
  return JSON.stringify(parts.map((part) => ({
    kind: part.kind,
    seg: part.seg,
    text: part.kind === 'tool' ? '' : part.text
  })))
}

describe('①③④⑤ applyFrameToParts：段写入（纯函数层）', () => {
  it('③ text→tool→text：3 段且序正确、seg 单调、工具处封口末尾文本段', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'message_start', messageId: 'm1', kind: 'text' })
    applyFrameToParts(message, { type: 'delta', text: '你好', messageId: 'm1' })
    applyFrameToParts(message, { type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{"a":1}' })
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 'c1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      status: 'EXECUTED',
      messageId: 'tool-c1',
      result: 'ok'
    })
    applyFrameToParts(message, { type: 'delta', text: '后续', messageId: 'm1' })

    expect(message.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'text'])
    expect(message.parts.map((part) => part.seg)).toEqual([0, 1, 2])
    expect(message.parts[0]).toMatchObject({ kind: 'text', seg: 0, text: '你好', streaming: false })
    expect(message.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'c1',
      name: 'probe_tool',
      kind2: 'BACKEND',
      status: 'succeeded',
      result: 'ok',
      messageId: 'tool-c1',
      requiresAction: false
    })
    expect(message.parts[2]).toMatchObject({ kind: 'text', seg: 2, text: '后续', streaming: true })
    expect(message.messageId).toBe('m1')
  })

  it('④ tool_result 就地更新不新建段：seg 不变、blocked/rejected 裁决优先于 ok', () => {
    const blocked = makeMessage()
    applyFrameToParts(blocked, { type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    const seg = toolPart(blocked, 'c1').seg
    applyFrameToParts(blocked, {
      type: 'tool_result',
      toolCallId: 'c1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: false,
      status: 'BLOCKED'
    })

    expect(blocked.parts).toHaveLength(2)
    expect(toolPart(blocked, 'c1')).toMatchObject({
      seg,
      status: 'blocked',
      reason: '该工具不在当前工作区上下文的披露范围内，未执行',
      requiresAction: false
    })

    // 人工拒绝（confirm_decision）保持「已拒绝」，不被紧随其后的 tool_result(ok=false) 覆盖
    const rejected = makeMessage()
    applyFrameToParts(rejected, { type: 'confirm_request', toolCallId: 'c2', name: 'danger_tool', args: '{"x":1}', timeoutSeconds: 120, expiresAt: 1_700_000_120_000, atMs: 1_700_000_000_000 })
    applyFrameToParts(rejected, {
      type: 'confirm_decision',
      toolCallId: 'c2',
      name: 'danger_tool',
      decision: 'REJECTED',
      approved: false,
      status: 'REJECTED',
      reason: '用户拒绝'
    })
    applyFrameToParts(rejected, {
      type: 'tool_result',
      toolCallId: 'c2',
      name: 'danger_tool',
      kind: 'CONFIRM',
      ok: false,
      executed: false,
      status: 'REJECTED'
    })

    expect(rejected.parts).toHaveLength(2)
    expect(toolPart(rejected, 'c2')).toMatchObject({ status: 'rejected', reason: '用户拒绝', requiresAction: false })
  })

  it('⑤ message_end 不改段结构：只封口末尾流式段 + 写 messageId', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'delta', text: '正文', messageId: 'm1' })
    const before = segDigest(message.parts)

    applyFrameToParts(message, { type: 'message_end', messageId: 'm1', kind: 'text', chars: 2 })

    expect(segDigest(message.parts)).toBe(before)
    expect(message.messageId).toBe('m1')
    expect(message.parts[0]).toMatchObject({ kind: 'text', streaming: false })
  })

  it('suspended 帧：已存在段更新（requiresAction 复位、status/seg 不变）；未知 toolCallId 建段（批3③b D5：status=pending）', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'confirm_request', toolCallId: 's1', name: 'danger_tool', args: '{"x":1}', timeoutSeconds: 120 })
    // 模拟段曾被收口过的现场
    upsertToolPart(message, { toolCallId: 's1', name: 'danger_tool', kind2: 'CONFIRM', requiresAction: false })

    applyFrameToParts(message, {
      type: 'suspended',
      runId: 'run-1',
      toolCalls: [{ toolCallId: 's1', name: 'danger_tool', type: 'function', kind: 'CONFIRM', arguments: '{"x":1}', status: 'PENDING', executed: false }]
    })

    expect(message.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(toolPart(message, 's1')).toMatchObject({ seg: 1, status: 'pending', requiresAction: true })

    applyFrameToParts(message, {
      type: 'suspended',
      runId: 'run-1',
      toolCalls: [{ toolCallId: 's-new', name: 'probe.auto', type: 'function', kind: 'FRONTEND', arguments: '{}', status: 'PENDING', executed: false }]
    })
    // 批3③b D5（R-140①-2）："只发 suspended"路径可达（SpToolCallingManager 取消竞态）——
    // 未知 toolCallId 不再"什么都不写"，而是建占位段（status='pending' + requiresAction=true），
    // 生命周期由紧随的 confirm_request/frontend_tool_request 或轮终态 expireResidueToolParts 兜底
    expect(message.parts).toHaveLength(3)
    expect(toolPart(message, 's-new')).toMatchObject({
      kind: 'tool', seg: 2, toolCallId: 's-new', name: 'probe.auto', kind2: 'FRONTEND',
      status: 'pending', requiresAction: true
    })
  })
})

describe('⑧ deriveProjections：唯一派生写口（全量映射）', () => {
  it('content/reasoning = 段 join；toolRuns 逐字段映射（kind ← kind2）；pendingCall 取最后一个 requiresAction 段', () => {
    const message = makeMessage()
    message.parts = [
      { kind: 'text', seg: 0, text: '你好', streaming: false },
      { kind: 'reasoning', seg: 1, text: '想', streaming: false },
      { kind: 'tool', seg: 2, toolCallId: 'c1', name: 'probe_tool', kind2: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: 'ok', reason: null, messageId: 'tool-c1', requiresAction: false },
      { kind: 'text', seg: 3, text: '后续', streaming: false },
      { kind: 'tool', seg: 4, toolCallId: 'c2', name: 'danger_tool', kind2: 'CONFIRM', status: 'pending', args: '{"x":1}', timeoutSeconds: 120, expiresAt: 1_700_000_120_000, atMs: 1_700_000_000_000, requiresAction: true },
      { kind: 'tool', seg: 5, toolCallId: 'c3', name: 'probe.unknown', kind2: 'FRONTEND', status: 'pending', args: '{"y":2}', requiresAction: true }
    ]

    deriveProjections(message)

    expect(message.content).toBe('你好后续')
    expect(message.reasoning).toBe('想')
    expect(message.toolRuns).toEqual([
      { toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: 'ok', reason: null, messageId: 'tool-c1' },
      { toolCallId: 'c2', name: 'danger_tool', kind: 'CONFIRM', status: 'pending', args: '{"x":1}', result: undefined, reason: undefined, messageId: undefined },
      { toolCallId: 'c3', name: 'probe.unknown', kind: 'FRONTEND', status: 'pending', args: '{"y":2}', result: undefined, reason: undefined, messageId: undefined }
    ])
    // 最后一个 requiresAction 段胜出；kind 由段 kind2 派生、rawArgs 原样、args 已解析
    expect(message.pendingCall).toMatchObject({
      toolCallId: 'c3',
      name: 'probe.unknown',
      kind: 'FRONTEND',
      args: { y: 2 },
      rawArgs: '{"y":2}',
      status: 'pending'
    })
    // 确认门倒计时口径随段持久化：元数据由段派生进 pendingCall（T4/T4-3 倒计时不回退）
    upsertToolPart(message, { toolCallId: 'c3', name: 'probe.unknown', kind2: 'FRONTEND', requiresAction: false })
    deriveProjections(message)
    expect(message.pendingCall).toMatchObject({
      toolCallId: 'c2',
      kind: 'CONFIRM',
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000
    })

    // 无任何 requiresAction 段 → null
    upsertToolPart(message, { toolCallId: 'c2', name: 'danger_tool', kind2: 'CONFIRM', requiresAction: false })
    deriveProjections(message)
    expect(message.pendingCall).toBeNull()
  })

  it('确认门倒计时口径随段持久化：帧 → 段 → 派生 pendingCall 全程带 timeoutSeconds/expiresAt/atMs', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 'c9',
      name: 'danger_tool',
      args: '{"x":1}',
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000,
      seq: 1
    })

    // 段侧持久化（pendingCall 已由段派生，元数据不落段就会丢倒计时口径）
    expect(toolPart(assistant, 'c9')).toMatchObject({
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000
    })
    expect(assistant.pendingCall).toMatchObject({
      toolCallId: 'c9',
      kind: 'CONFIRM',
      status: 'pending',
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000
    })
  })
})

describe('②⑦ seq 记账与 o-R1 封口（纯函数层）', () => {
  it('alreadyApplied/markApplied：记账幂等、未记账为 false、seq 缺失（null）路径不做记账判据', () => {
    const message = makeMessage()
    expect(alreadyApplied(message, 5)).toBe(false)
    markApplied(message, 5)
    markApplied(message, 5)
    expect(message.appliedSeqs).toEqual([5])
    expect(alreadyApplied(message, 5)).toBe(true)
    expect(alreadyApplied(message, 6)).toBe(false)
    expect(alreadyApplied(message, null as unknown as number)).toBe(false)
  })

  it('o-R1：reasoning/text 交错时先封口被中断的上一段（与工具段封口对称）', () => {
    const message = makeMessage()
    const text = openStreamPart(message, 'text')
    text.text += '先答'
    const reasoning = openStreamPart(message, 'reasoning')
    reasoning.text += '再想'

    expect(message.parts.map((part) => part.kind)).toEqual(['text', 'reasoning'])
    expect(message.parts[0]).toMatchObject({ kind: 'text', streaming: false })
    expect(message.parts[1]).toMatchObject({ kind: 'reasoning', streaming: true })

    const text2 = openStreamPart(message, 'text')
    text2.text += '再答'
    expect(message.parts.map((part) => part.seg)).toEqual([0, 1, 2])
    expect(message.parts[1]).toMatchObject({ kind: 'reasoning', streaming: false })
    // 同类续写不另起段
    expect(openStreamPart(message, 'text')).toBe(text2)
  })

  it('o-R1：sealTrailingStreamPart 只封口末尾流式段，非流式段/工具段末尾不动', () => {
    const message = makeMessage()
    upsertToolPart(message, { toolCallId: 'c1', name: 'probe_tool', kind2: 'BACKEND', status: 'running', requiresAction: false })
    const before = message.parts.map((part) => JSON.stringify(part))
    sealTrailingStreamPart(message)
    expect(message.parts.map((part) => JSON.stringify(part))).toEqual(before)
  })
})

describe('handleFrame 集成：seq 幂等 / 乱序兜底 / derive 时机', () => {
  it('① seq 为 null 的旧帧照收：两张无 seq 的 delta 逐帧累加（不做去重）', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'delta', text: '甲' })
    store.handleFrame({ type: 'delta', text: '乙' })

    expect(assistant.content).toBe('甲乙')
    expect(assistant.parts).toHaveLength(1)
    expect(assistant.parts[0]).toMatchObject({ kind: 'text', text: '甲乙' })
    expect(assistant.appliedSeqs ?? []).toEqual([])
  })

  it('② 同 seq 重复投递幂等：文本不重复累加、段不重建；工具帧同样整帧跳过', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'delta', text: '你好', seq: 1 })
    store.handleFrame({ type: 'delta', text: '你好', seq: 1 })

    expect(assistant.content).toBe('你好')
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text'])

    store.handleFrame({ type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{}', seq: 2 })
    store.handleFrame({ type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{}', seq: 2 })

    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.toolRuns).toHaveLength(1)

    // 新 seq 照收
    store.handleFrame({ type: 'delta', text: '！', seq: 3 })
    expect(assistant.content).toBe('你好！')
  })

  it('乱序兜底：小于已应用 seq 但未应用过的帧仍照收（只去重、不做连续性判定）', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'delta', text: '五', seq: 5 })
    store.handleFrame({ type: 'delta', text: '三', seq: 3 }) // 迟到帧：seq 更小但未应用过 → 照收
    expect(assistant.content).toBe('五三')

    store.handleFrame({ type: 'delta', text: '三', seq: 3 }) // 再来一次 → 幂等丢弃
    expect(assistant.content).toBe('五三')
    expect(assistant.appliedSeqs).toEqual(expect.arrayContaining([3, 5]))
  })

  it('derive 时机：delta 高频路径不重算投影（数组身份不变），工具帧/终帧后全量重算', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    const runsBefore = assistant.toolRuns

    store.handleFrame({ type: 'delta', text: '正文', seq: 1 })
    // 结构性断言：delta 路径不调 deriveProjections（否则 toolRuns 会被新数组替换）
    expect(assistant.toolRuns).toBe(runsBefore)
    expect(assistant.content).toBe('正文')
    // 锁步等价：content 与 text 段 join 一致
    expect(assistant.content).toBe(
      assistant.parts.filter((part) => part.kind === 'text').map((part) => (part.kind === 'text' ? part.text : '')).join('')
    )

    store.handleFrame({ type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{}', seq: 2 })
    expect(assistant.toolRuns).not.toBe(runsBefore)
    expect(assistant.toolRuns).toHaveLength(1)

    store.handleFrame({ type: 'done', runId: 'run-1', seq: 3 })
    expect(assistant.toolRuns).toHaveLength(1)
    expect(assistant.pendingCall).toBeNull()
  })

  it('R2-5：resetSession 后旧轮迟到帧不写新会话 parts（宿主已不在 ⇒ 整帧丢弃）', async () => {
    api.chat.mockImplementation(() => new Promise(() => {}))
    const store = useAiStore()
    const round = store.send('旧轮消息') // send 同步落 activeRoundHostId（宿主 = 新助手消息）
    await vi.waitFor(() => expect(api.chat).toHaveBeenCalled())
    const old = store.messages[1] as ChatMessage
    store.handleFrame({ type: 'delta', text: '旧轮内容', seq: 1 }, old.id)
    expect(old.content).toBe('旧轮内容')

    await store.resetSession()
    expect(store.messages).toHaveLength(0)

    store.handleFrame({ type: 'delta', text: '旧轮迟到', seq: 2 }, old.id)
    store.handleFrame({ type: 'tool_start', toolCallId: 'c-old', name: 'probe_tool', kind: 'BACKEND', args: '{}', seq: 3 }, old.id)

    // 新会话零污染：既没有新消息，旧宿主消息也不再被改写
    expect(store.messages).toHaveLength(0)
    expect(old.content).toBe('旧轮内容')
    expect(old.parts.map((part) => part.kind)).toEqual(['text'])
    expect(old.toolRuns).toHaveLength(0)
    void round // 旧轮连接保持未决（本用例不需要它收尾）
  })

  it('residue 守卫（批2 改读段）：已取消的 generative_form 帧重放不再重挂挂起态', async () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const formArgs = JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } })
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'g1',
      name: GENERATIVE_FORM_TOOL,
      args: formArgs,
      timeoutSeconds: 120,
      runId: 'run-1',
      seq: 2
    })
    expect(store.activeForm?.toolCallId).toBe('g1')

    await store.cancelGenerativeForm()
    expect(toolPart(assistant, 'g1').status).toBe('cancelled')
    store.suspended = false // 只看守卫本身：回放不得重新置挂起

    // reattach 回放同一 toolCallId 的请求帧：段已终态 ⇒ 守卫拦下（不重挂、不重武装表单）
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'g1',
      name: GENERATIVE_FORM_TOOL,
      args: formArgs,
      timeoutSeconds: 120,
      runId: 'run-1',
      seq: 3
    })

    expect(store.suspended).toBe(false)
    expect(store.activeForm?.status).toBe('cancelled')
    expect(toolPart(assistant, 'g1').status).toBe('cancelled')
    expect(assistant.pendingCall).toBeNull()
  })
})

/**
 * R-154 #2 / #17 新增（归因：前端面施工方 K2.8）：
 *   ⑨ tool_result 帧侧截断标记（FrameSizeLimiter TRUNCATED_FIELD/TRUNCATED_REASON_FIELD）落段；
 *   ⑩ message_start 帧 atMs → 消息时间戳（实时路；历史路见 ai-panel-parts-render.spec.ts）。
 */
describe('R-154 ⑨⑩：截断标记落段 + message_start 时间戳（纯函数层）', () => {
  it('⑨ tool_result 带 truncated/truncatedReason ⇒ 段原样携带（缺省帧 ⇒ 未截断缺省值）', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 'c1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      result: '前 64KB…',
      truncated: true,
      truncatedReason: '单帧 64KB 预算超限，结果字段被截断'
    })

    expect(toolPart(message, 'c1')).toMatchObject({
      status: 'succeeded',
      result: '前 64KB…',
      truncated: true,
      truncatedReason: '单帧 64KB 预算超限，结果字段被截断'
    })

    // 缺省帧（旧格式 / 未截断）⇒ 显式 false / null，卡面角标判据稳定
    const plain = makeMessage()
    applyFrameToParts(plain, { type: 'tool_start', toolCallId: 'c2', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(plain, {
      type: 'tool_result',
      toolCallId: 'c2',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      result: '全文'
    })
    expect(toolPart(plain, 'c2')).toMatchObject({ truncated: false, truncatedReason: null })
  })

  it('⑩ message_start 帧 atMs ⇒ 消息时间戳（已有值不回填覆盖）', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'message_start', messageId: 'm1', kind: 'text', atMs: 1_700_000_000_000 })
    expect(message.timestamp).toBe(1_700_000_000_000)

    // 后续 message_start（同消息重放 / 第二段）不覆盖既有时间戳
    applyFrameToParts(message, { type: 'message_start', messageId: 'm1', kind: 'text', atMs: 1_700_000_099_000 })
    expect(message.timestamp).toBe(1_700_000_000_000)

    // atMs 缺失（旧格式帧）⇒ 时间戳保持 undefined
    const legacy = makeMessage()
    applyFrameToParts(legacy, { type: 'message_start', messageId: 'm2', kind: 'text' })
    expect(legacy.timestamp).toBeUndefined()
  })
})
