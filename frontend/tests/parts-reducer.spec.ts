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
  deriveCopyText,
  deriveProjections,
  markApplied,
  openStreamPart,
  sealTrailingStreamPart,
  upsertToolPart
} from '@/utils/parts'
import { cardStatusOf, readOnlyStateOf } from '@/utils/blocks'
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

  it('H1 预检摘要：confirm_request / suspended 帧的 preflight 落段；缺省帧不产生该键（旧帧零影响）', () => {
    const message = makeMessage()
    const preflight = {
      // 后端 ToolPreflightService#asMap 实发形状：target 用 Task 枚举名；checks 固定四项、
      // 顺序 = inspect 的写入序（任务存在 → 类型匹配 → 无在途作业 → 预检指纹新鲜），
      // 每项恒带 detail（通过时可为空串）
      target: { taskId: 5, title: '订单配置导入', type: 'IMPORT', status: 'ACTIVE' },
      checks: [
        { name: '任务存在', pass: true, detail: '' },
        { name: '类型匹配', pass: true, detail: '' },
        { name: '无在途作业', pass: true, detail: '' },
        { name: '预检指纹新鲜', pass: true, detail: '预检作业 #12 · 3a9f…' }
      ],
      impact: '将发布 3 个配置定义 · 影响 12,480 行数据',
      reportHash: '3a9f'
    }

    applyFrameToParts(message, { type: 'confirm_request', toolCallId: 'pf1', name: 'start_publish', args: '{}', timeoutSeconds: 120, preflight })
    expect(toolPart(message, 'pf1').preflight).toEqual(preflight)

    // 紧随其后的无 preflight 帧（真实顺序：suspended 先发、confirm_request 后到，反之亦然）
    // 不得抹掉已落段的摘要 —— 缺省字段不产生键，段上原值保留。
    applyFrameToParts(message, {
      type: 'suspended',
      runId: 'run-1',
      toolCalls: [{ toolCallId: 'pf1', name: 'start_publish', type: 'function', kind: 'CONFIRM', arguments: '{}', status: 'PENDING', executed: false }]
    })
    expect(toolPart(message, 'pf1').preflight).toEqual(preflight)

    // "只发 suspended"路径同样落段（suspended 载荷条目自带 preflight）
    applyFrameToParts(message, {
      type: 'suspended',
      runId: 'run-1',
      toolCalls: [{ toolCallId: 'pf2', name: 'start_publish', type: 'function', kind: 'CONFIRM', arguments: '{}', status: 'PENDING', executed: false, preflight }]
    })
    expect(toolPart(message, 'pf2').preflight).toEqual(preflight)

    // 旧帧（无该字段）：段上不产生 preflight 键 —— 旧帧口径逐字不变
    applyFrameToParts(message, { type: 'confirm_request', toolCallId: 'old1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    expect(toolPart(message, 'old1')).not.toHaveProperty('preflight')
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

  it('M2 收口：同消息两张待决 CONFIRM，第一张 confirm_decision 不清 suspended，最后一张才清', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'confirm_request', toolCallId: 'c1', name: 'danger_a', args: '{}', timeoutSeconds: 120, seq: 1 })
    store.handleFrame({ type: 'confirm_request', toolCallId: 'c2', name: 'danger_b', args: '{}', timeoutSeconds: 120, seq: 2 })
    expect(store.pendingConfirms.map((part) => part.toolCallId)).toEqual(['c1', 'c2'])
    expect(store.suspended).toBe(true)

    // 第一张卡落终态：另一张仍待决 ⇒ 挂起态不得被误导清
    store.handleFrame({
      type: 'confirm_decision', toolCallId: 'c1', name: 'danger_a',
      decision: 'APPROVED', approved: true, status: 'APPROVED', seq: 3
    })
    expect(toolPart(assistant, 'c1')).toMatchObject({ status: 'running', requiresAction: false })
    expect(store.pendingConfirms.map((part) => part.toolCallId)).toEqual(['c2'])
    expect(store.suspended).toBe(true)

    // 最后一张也落终态：无其它待决确认段 ⇒ 挂起态收口
    store.handleFrame({
      type: 'confirm_decision', toolCallId: 'c2', name: 'danger_b',
      decision: 'REJECTED', approved: false, status: 'REJECTED', reason: '用户拒绝', seq: 4
    })
    expect(toolPart(assistant, 'c2')).toMatchObject({ status: 'rejected', requiresAction: false })
    expect(store.pendingConfirms).toEqual([])
    expect(store.suspended).toBe(false)
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

/**
 * R-158 #11 新增（归因：前端面施工方 K2.8）：deriveCopyText 复制口径 —— text 段 join('\n\n')。
 * 红线：deriveProjections 的 content 逐字一致契约不动（复制口径独立，见函数头注）。
 */
describe('R-158 ⑪：deriveCopyText 复制口径（段间换行）', () => {
  it('单段原样 / R-150 合并两段 join \\n\\n / 无 text 段空串', () => {
    const single = makeMessage()
    applyFrameToParts(single, { type: 'delta', text: '只有一段', messageId: 'm1' })
    expect(deriveCopyText(single)).toBe('只有一段')

    // R-150 合并形态：段边界后另起的第二段 text（message_end 封口 → 新段）
    const merged = makeMessage()
    applyFrameToParts(merged, { type: 'delta', text: '第一段', messageId: 'm1' })
    applyFrameToParts(merged, { type: 'message_end', messageId: 'm1', kind: 'text', chars: 3 })
    applyFrameToParts(merged, { type: 'delta', text: '第二段', messageId: 'm1' })
    expect(deriveCopyText(merged)).toBe('第一段\n\n第二段')
    // content 契约不动（无间隔拼接，红线面）
    expect(merged.content).toBe('')

    const toolOnly = makeMessage()
    applyFrameToParts(toolOnly, { type: 'tool_start', toolCallId: 'c1', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    expect(deriveCopyText(toolOnly)).toBe('')
  })
})

/**
 * 2A 挂账剩余两项（用户 2026-10-20 批准）：
 * ① 实时 confirm_decision 帧原只看 `approved`，后端超时帧（decision=timeout + approved=false +
 *   status=TIMEOUT）被一律落成 rejected，卡面实时显示「✗ 已拒绝」（刷新后历史表才显示「! 已超时」）；
 * ② 本地倒计时已落 expired 后，晚到的 TIMEOUT 帧把段覆盖回 rejected（无 first-wins）。
 * ③ 确认卡批准后工具执行失败（tool_result outcome=failed）→ readOnlyStateOf 的 failed 分支可达。
 */
describe('2A 挂账修复：①超时口径（decision/status 优先 + first-wins）与 ③已批准·执行失败', () => {
  it('①a confirm_decision TIMEOUT 帧（decision=timeout / status=TIMEOUT / approved=false）⇒ 段 expired（「! 已超时」），不是 rejected', () => {
    const message = makeMessage()
    applyFrameToParts(message, {
      type: 'confirm_request',
      toolCallId: 't1',
      name: 'danger_tool',
      args: '{"x":1}',
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000
    })
    applyFrameToParts(message, {
      type: 'confirm_decision',
      toolCallId: 't1',
      name: 'danger_tool',
      decision: 'timeout',
      approved: false,
      status: 'TIMEOUT',
      reason: 'CONFIRM_TIMEOUT'
    })

    const part = toolPart(message, 't1')
    expect(part).toMatchObject({ status: 'expired', decision: 'timeout', requiresAction: false })
    expect(readOnlyStateOf(part)).toBe('expired')
    expect(cardStatusOf(part)).toMatchObject({ key: 'expired', label: '! 已超时', tone: 'warning' })
  })

  it('①a 负控制：REJECTED 帧仍落 rejected；APPROVED 帧仍落 running（既有口径不动）', () => {
    const rejected = makeMessage()
    applyFrameToParts(rejected, { type: 'confirm_request', toolCallId: 'r1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(rejected, {
      type: 'confirm_decision', toolCallId: 'r1', name: 'danger_tool', decision: 'reject', approved: false, status: 'REJECTED', reason: '用户拒绝'
    })
    expect(toolPart(rejected, 'r1')).toMatchObject({ status: 'rejected', decision: 'rejected', reason: '用户拒绝' })
    expect(cardStatusOf(toolPart(rejected, 'r1')).label).toBe('✗ 已拒绝')

    const approved = makeMessage()
    applyFrameToParts(approved, { type: 'confirm_request', toolCallId: 'a1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(approved, {
      type: 'confirm_decision', toolCallId: 'a1', name: 'danger_tool', decision: 'approve', approved: true, status: 'APPROVED'
    })
    expect(toolPart(approved, 'a1')).toMatchObject({ status: 'running', decision: 'approved', requiresAction: false })
    expect(cardStatusOf(toolPart(approved, 'a1')).label).toBe('✓ 已批准')
  })

  it('①a CANCELLED 分支（NIT-1）：decision=cancel / status=CANCELLED ⇒ 段 cancelled、卡面「! 已超时」', () => {
    const byDecision = makeMessage()
    applyFrameToParts(byDecision, { type: 'confirm_request', toolCallId: 'c1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(byDecision, {
      type: 'confirm_decision', toolCallId: 'c1', name: 'danger_tool', decision: 'cancel', approved: false
    } as never)
    const cancelled = toolPart(byDecision, 'c1')
    // 门卡无「已取消」：段态 cancelled + 无决策元数据，只读态归「已超时」
    expect(cancelled).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(cancelled.decision).toBeUndefined()
    expect(readOnlyStateOf(cancelled)).toBe('expired')
    expect(cardStatusOf(cancelled)).toMatchObject({ key: 'expired', label: '! 已超时', tone: 'warning' })

    const byStatus = makeMessage()
    applyFrameToParts(byStatus, { type: 'confirm_request', toolCallId: 'c2', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(byStatus, {
      type: 'confirm_decision', toolCallId: 'c2', name: 'danger_tool', decision: 'cancel', approved: false, status: 'CANCELLED'
    })
    expect(toolPart(byStatus, 'c2')).toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(cardStatusOf(toolPart(byStatus, 'c2')).label).toBe('! 已超时')
  })

  it('MINOR-1 优先序：decision=cancel + status=APPROVED ⇒ 按 approved 归并，不落 cancelled', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'confirm_request', toolCallId: 'p1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    // 后端 cancel 对非 PENDING 条目仍发 decision='cancel' + 真实 status 的帧
    applyFrameToParts(message, {
      type: 'confirm_decision', toolCallId: 'p1', name: 'danger_tool', decision: 'cancel', approved: false, status: 'APPROVED'
    })
    const part = toolPart(message, 'p1')
    expect(part).toMatchObject({ status: 'running', decision: 'approved', requiresAction: false })
    expect(readOnlyStateOf(part)).toBe('approved')
    expect(cardStatusOf(part)).toMatchObject({ key: 'approved', label: '✓ 已批准' })
  })

  it('①a 旧帧兼容：decision/status 皆缺时仍按 approved 布尔（true→running、false→rejected，旧口径不动）', () => {
    const legacyApprove = makeMessage()
    applyFrameToParts(legacyApprove, { type: 'confirm_request', toolCallId: 'l1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(legacyApprove, { type: 'confirm_decision', toolCallId: 'l1', name: 'danger_tool', approved: true } as never)
    expect(toolPart(legacyApprove, 'l1')).toMatchObject({ status: 'running', decision: 'approved' })

    const legacyReject = makeMessage()
    applyFrameToParts(legacyReject, { type: 'confirm_request', toolCallId: 'l2', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(legacyReject, { type: 'confirm_decision', toolCallId: 'l2', name: 'danger_tool', approved: false } as never)
    expect(toolPart(legacyReject, 'l2')).toMatchObject({ status: 'rejected', decision: 'rejected' })
  })

  it('①b first-wins：本地倒计时已落 expired 后晚到的 TIMEOUT 帧不改写已落定结局（保持「! 已超时」）', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'confirm_request', toolCallId: 'x1', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    // 与 store.applyGateReceipt 的本地超时作废同形：status=expired + expiredLocally，无决策元数据
    upsertToolPart(message, {
      toolCallId: 'x1',
      name: 'danger_tool',
      kind2: 'CONFIRM',
      status: 'expired',
      expiredLocally: true,
      requiresAction: false
    })

    applyFrameToParts(message, {
      type: 'confirm_decision',
      toolCallId: 'x1',
      name: 'danger_tool',
      decision: 'timeout',
      approved: false,
      status: 'TIMEOUT',
      reason: 'CONFIRM_TIMEOUT'
    })

    const part = toolPart(message, 'x1')
    expect(part.status).toBe('expired')
    expect(part.expiredLocally).toBe(true)
    expect(part.decision).toBeUndefined()
    expect(cardStatusOf(part)).toMatchObject({ key: 'expired', label: '! 已超时' })
  })

  it('③ 确认卡批准（乐观写 decision=approved）→ tool_result outcome=failed 同 toolCallId ⇒ 卡面「已批准 · 执行失败」', () => {
    const message = makeMessage()
    applyFrameToParts(message, {
      type: 'confirm_request',
      toolCallId: 'f1',
      name: 'danger_tool',
      args: '{"x":1}',
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000
    })
    // store.confirm(true) 的乐观置位（点击即写，先于 confirm_decision 帧）
    upsertToolPart(message, {
      toolCallId: 'f1',
      name: 'danger_tool',
      kind2: 'CONFIRM',
      status: 'running',
      decision: 'approved',
      decidedAt: 1_700_000_010_000,
      requiresAction: false
    })
    applyFrameToParts(message, {
      type: 'confirm_decision',
      toolCallId: 'f1',
      name: 'danger_tool',
      decision: 'approve',
      approved: true,
      status: 'APPROVED'
    })
    expect(cardStatusOf(toolPart(message, 'f1')).label).toBe('✓ 已批准')

    // 后端执行失败：CONFIRM 条目的 status 仍是 APPROVED，结局由 outcome=failed 承载
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 'f1',
      name: 'danger_tool',
      kind: 'CONFIRM',
      ok: false,
      executed: true,
      status: 'APPROVED',
      outcome: 'failed',
      code: 'TOOL_EXECUTION_FAILED',
      result: '工具执行失败：boom'
    })

    const part = toolPart(message, 'f1')
    expect(part).toMatchObject({ status: 'failed', kind2: 'CONFIRM', decision: 'approved', requiresAction: false })
    expect(readOnlyStateOf(part)).toBe('failed')
    expect(cardStatusOf(part)).toMatchObject({ key: 'failed', label: '! 已批准 · 执行失败', tone: 'danger' })
  })

  it('③ 无乐观写（另一端批准 / 重挂回放）也成立：帧序 confirm_decision(approved) → tool_result(outcome=failed)', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'confirm_request', toolCallId: 'f2', name: 'danger_tool', args: '{}', timeoutSeconds: 120 })
    applyFrameToParts(message, {
      type: 'confirm_decision', toolCallId: 'f2', name: 'danger_tool', decision: 'approve', approved: true, status: 'APPROVED'
    })
    applyFrameToParts(message, {
      type: 'tool_result', toolCallId: 'f2', name: 'danger_tool', kind: 'CONFIRM', ok: false, executed: true,
      status: 'APPROVED', outcome: 'failed', code: 'TOOL_EXECUTION_FAILED', result: '工具执行失败：boom'
    })
    const part = toolPart(message, 'f2')
    expect(part.status).toBe('failed')
    expect(cardStatusOf(part).label).toBe('! 已批准 · 执行失败')
  })

  it('③ store 集成：handleFrame 帧序 confirm_request → confirm_decision(APPROVED) → tool_result(failed) 卡面第四态可达', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'confirm_request', toolCallId: 's1', name: 'danger_tool', args: '{"x":1}', timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000, atMs: 1_700_000_000_000, seq: 1
    })
    // 无乐观写：本页签只是收到另一端批准 / 重挂回放的帧
    store.handleFrame({
      type: 'confirm_decision', toolCallId: 's1', name: 'danger_tool', decision: 'approve', approved: true,
      status: 'APPROVED', seq: 2
    })
    store.handleFrame({
      type: 'tool_result', toolCallId: 's1', name: 'danger_tool', kind: 'CONFIRM', ok: false, executed: true,
      status: 'APPROVED', outcome: 'failed', code: 'TOOL_EXECUTION_FAILED', result: '工具执行失败：boom', seq: 3
    })

    const part = toolPart(assistant, 's1')
    expect(part).toMatchObject({ status: 'failed', kind2: 'CONFIRM', requiresAction: false })
    expect(assistant.pendingCall).toBeNull()
    expect(cardStatusOf(part)).toMatchObject({ key: 'failed', label: '! 已批准 · 执行失败', tone: 'danger' })
  })

  it('④ 历史恢复（NIT-4 + MINOR-2）：outcome=failed + decision=approved ⇒「已批准 · 执行失败」；decision=timeout 回读 ⇒「已超时」', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '历史确认轮',
          toolCalls: [
            { id: 'hist-f1', type: 'function', name: 'danger_tool', arguments: '{"x":1}' },
            { id: 'hist-t1', type: 'function', name: 'slow_tool', arguments: '{}' }
          ],
          toolCallStates: {
            'hist-f1': { status: 'EXECUTED', kind: 'CONFIRM', decision: 'approved', outcome: 'failed', code: 'TOOL_EXECUTION_FAILED' },
            'hist-t1': { status: 'TIMEOUT', kind: 'CONFIRM', decision: 'timeout' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue({
      found: false,
      runId: null,
      snapshotStatus: null,
      pendingEntries: [],
      unresolvedExternal: [],
      awaitingExternal: false,
      archiveMaxSeq: 0
    })

    await store.loadHistory()

    const message = store.messages[0] as ChatMessage
    // MINOR-2：timeout 决策元数据历史回读（刷新后不丢「已超时」的决策口径）
    expect(toolPart(message, 'hist-t1')).toMatchObject({
      status: 'expired', kind2: 'CONFIRM', decision: 'timeout', requiresAction: false
    })
    expect(cardStatusOf(toolPart(message, 'hist-t1')).label).toBe('! 已超时')
    // NIT-4：批准 + 执行失败 = 卡面第四态（decision 从历史载荷回读，status 由 outcome 覆写为 failed）
    const failed = toolPart(message, 'hist-f1')
    expect(failed).toMatchObject({ status: 'failed', kind2: 'CONFIRM', decision: 'approved', requiresAction: false })
    expect(readOnlyStateOf(failed)).toBe('failed')
    expect(cardStatusOf(failed)).toMatchObject({ key: 'failed', label: '! 已批准 · 执行失败', tone: 'danger' })
  })
})
