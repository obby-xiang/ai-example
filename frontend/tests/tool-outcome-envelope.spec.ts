/**
 * 治理 P0-1（`docs/research/调研-AI工具执行治理与互斥-2026-10-19.md` §6.4）：统一结果信封的前端消费锁。
 *
 * 契约（与后端已锁定）：
 * - `tool_result` 帧可选 `outcome`（ok|failed|rejected|blocked|timeout|cancelled|unknown|skipped）；
 * - 历史 `toolCallStates` 可选 `outcome` / `code`；
 * - 回灌 POST /api/ai/frontend-tool-result 请求体可选 `outcome`。
 *
 * 核心口径：**outcome 优先、缺省回落既有 ok 三元 / status 旧表**；旧帧行为一字不动。
 * 验收锚：业务失败（create_task 类型非法，后端发 outcome=failed 且 ok 仍为 true）卡面必须
 * 显示「执行失败」——本文件第①例即帧级锁（status 派生失败，不依赖面板渲染）。
 *
 * 风格照抄 parts-reducer.spec.ts / frontend-tool-result-terminal.spec.ts：
 * 只驱动真实 store / 纯函数，不挂载面板。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AiRunCurrent, ChatMessage, ToolPart } from '@/types/ai'
import { applyFrameToParts, deriveProjections, historyToolCallStatus, outcomeStatus } from '@/utils/parts'
import { cardStatusOf } from '@/utils/blocks'
import { useAiStore } from '@/stores/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  reattachRun: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.frontendToolResult.mockReset()
  api.confirm.mockReset()
  api.chat.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  api.reattachRun.mockReset()
  api.frontendToolResult.mockResolvedValue({})
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  frontendTools.executeFrontendTool.mockReset()
})

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

function toolPart(message: ChatMessage, toolCallId: string): ToolPart {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  expect(part).toBeDefined()
  return part as ToolPart
}

describe('治理 P0-1 ①：tool_result 帧 outcome 优先（业务失败不被 ok=true 掩盖）', () => {
  it('outcome=failed + ok=true（异常处理器吞文本的新协议形态）⇒ 段状态 failed', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'tool_start', toolCallId: 'c1', name: 'create_task', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 'c1',
      name: 'create_task',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      status: 'EXECUTED',
      result: '任务类型非法：CREATE',
      outcome: 'failed',
      code: 'INVALID_ARGUMENT',
      retryable: false,
      target: { taskId: 7 }
    })

    // 帧级验收锚：卡头据此显示「执行失败」（面板按 status 派生文案，本次不改卡面）
    expect(toolPart(message, 'c1')).toMatchObject({
      status: 'failed',
      result: '任务类型非法：CREATE',
      outcome: 'failed',
      requiresAction: false
    })
    deriveProjections(message)
    expect(message.toolRuns[0]).toMatchObject({ status: 'failed' })
    // 卡头文案链（blocks.ts 状态机，不改卡面）：failed ⇒「执行失败」，绝非「已成功」
    expect(cardStatusOf(toolPart(message, 'c1')).label).toBe('执行失败')
  })

  it('outcome=ok ⇒ succeeded（ok 同向时为常规成功路）', () => {
    const message = makeMessage()
    applyFrameToParts(message, { type: 'tool_start', toolCallId: 'c2', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 'c2',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      result: 'ok',
      outcome: 'ok',
      retryable: false,
      target: null
    })
    expect(toolPart(message, 'c2')).toMatchObject({ status: 'succeeded', outcome: 'ok' })
  })

  it('outcome 八态映射表（timeout→expired / cancelled→cancelled / unknown→failed / skipped→skipped）', () => {
    expect(outcomeStatus('ok')).toBe('succeeded')
    expect(outcomeStatus('failed')).toBe('failed')
    expect(outcomeStatus('rejected')).toBe('rejected')
    expect(outcomeStatus('blocked')).toBe('blocked')
    expect(outcomeStatus('timeout')).toBe('expired')
    expect(outcomeStatus('cancelled')).toBe('cancelled')
    expect(outcomeStatus('unknown')).toBe('failed')
    expect(outcomeStatus('skipped')).toBe('skipped')
    // 缺失 / 域外值 ⇒ null（调用方回落旧口径）
    expect(outcomeStatus(undefined)).toBeNull()
    expect(outcomeStatus('bogus')).toBeNull()
  })

  it('BLOCKED 优先级仍最高：status=BLOCKED + outcome=ok ⇒ blocked；outcome=blocked 单独也落阻断口径', () => {
    const viaStatus = makeMessage()
    applyFrameToParts(viaStatus, { type: 'tool_start', toolCallId: 'b1', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(viaStatus, {
      type: 'tool_result',
      toolCallId: 'b1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: false,
      status: 'BLOCKED',
      outcome: 'ok'
    })
    expect(toolPart(viaStatus, 'b1')).toMatchObject({
      status: 'blocked',
      reason: '该工具不在当前工作区上下文的披露范围内，未执行'
    })

    const viaOutcome = makeMessage()
    applyFrameToParts(viaOutcome, { type: 'tool_start', toolCallId: 'b2', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(viaOutcome, {
      type: 'tool_result',
      toolCallId: 'b2',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: false,
      outcome: 'blocked'
    })
    expect(toolPart(viaOutcome, 'b2')).toMatchObject({
      status: 'blocked',
      reason: '该工具不在当前工作区上下文的披露范围内，未执行'
    })
  })
})

describe('治理 P0-1 ③：旧帧（无 outcome）走 ok 三元回落（回归锁）', () => {
  it('ok=true ⇒ succeeded、ok=false ⇒ failed，且段上不产生 outcome 键', () => {
    const okFrame = makeMessage()
    applyFrameToParts(okFrame, { type: 'tool_start', toolCallId: 'l1', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(okFrame, {
      type: 'tool_result',
      toolCallId: 'l1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      status: 'EXECUTED',
      result: 'ok'
    })
    const okPart = toolPart(okFrame, 'l1')
    expect(okPart.status).toBe('succeeded')
    expect('outcome' in okPart).toBe(false)

    const failFrame = makeMessage()
    applyFrameToParts(failFrame, { type: 'tool_start', toolCallId: 'l2', name: 'probe_tool', kind: 'BACKEND', args: '{}' })
    applyFrameToParts(failFrame, {
      type: 'tool_result',
      toolCallId: 'l2',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: false,
      executed: false,
      status: 'FAILED'
    })
    const failPart = toolPart(failFrame, 'l2')
    expect(failPart.status).toBe('failed')
    expect('outcome' in failPart).toBe(false)
  })
})

describe('治理 P0-1 ④：历史 toolCallStates.outcome 优先、缺省回落 status 旧表', () => {
  it('outcome=failed（status=EXECUTED 同帧）⇒ 刷新后段状态 failed；无 outcome ⇒ status 词表照旧', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '历史工具轮',
          toolCalls: [
            { id: 'h1', type: 'function', name: 'create_task', arguments: '{}' },
            { id: 'h2', type: 'function', name: 'danger_tool', arguments: '{}' }
          ],
          toolCallStates: {
            h1: { status: 'EXECUTED', kind: 'BACKEND', outcome: 'failed', code: 'INVALID_ARGUMENT' },
            h2: { status: 'REJECTED', kind: 'CONFIRM' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const message = store.messages[0] as ChatMessage
    expect(toolPart(message, 'h1')).toMatchObject({ status: 'failed', kind2: 'BACKEND', requiresAction: false })
    // 无 outcome 的条目回落 status 旧表（REJECTED → rejected），旧载荷行为不变
    expect(toolPart(message, 'h2')).toMatchObject({ status: 'rejected', kind2: 'CONFIRM' })
  })
})

describe('治理 P0-1 ⑤：回灌 POST 请求体携带前端执行器 outcome', () => {
  it('submitFrontendToolResult 带 outcome 时请求体含该字段；不带时旧形态无该键', async () => {
    const store = useAiStore()

    await store.submitFrontendToolResult('t1', '结果文本', 'frontend-executor', false, 'run-1', 'failed')
    expect(api.frontendToolResult).toHaveBeenCalledWith({
      runId: 'run-1',
      toolCallId: 't1',
      result: '结果文本',
      source: 'frontend-executor',
      outcome: 'failed'
    })

    api.frontendToolResult.mockClear()
    await store.submitFrontendToolResult('t2', '结果文本', 'frontend-executor', false, 'run-1')
    const payload = api.frontendToolResult.mock.calls[0]?.[0] as Record<string, unknown>
    expect(payload).toEqual({ runId: 'run-1', toolCallId: 't2', result: '结果文本', source: 'frontend-executor' })
    expect('outcome' in payload).toBe(false)
  })

  it('runFrontendTool 集成：执行器 outcome=failed ⇒ 段 failed 且回灌 POST 带 outcome=failed', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue({
      ok: false,
      outcome: 'failed',
      availability: 'degraded',
      result: '页面动作失败'
    })
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'r1',
      name: 'probe.tool',
      args: '{}',
      timeoutSeconds: 120,
      runId: 'run-1',
      seq: 2
    })

    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ runId: 'run-1', toolCallId: 'r1', outcome: 'failed' })
    ))
    expect(toolPart(assistant, 'r1')).toMatchObject({ status: 'failed', requiresAction: false })
  })
})

describe('治理 P0-1 I1：frontend_tool_result 帧带 outcome 时 outcome 优先', () => {
  it('ok=false + outcome=failed + status=FRONTEND_RESULT ⇒ 段状态 failed（旧表不再盖成 succeeded）', () => {
    const message = makeMessage()
    applyFrameToParts(message, {
      type: 'frontend_tool_request',
      toolCallId: 'f1',
      name: 'probe.tool',
      args: '{}',
      timeoutSeconds: 120
    })
    applyFrameToParts(message, {
      type: 'frontend_tool_result',
      toolCallId: 'f1',
      name: 'probe.tool',
      ok: false,
      executed: false,
      status: 'FRONTEND_RESULT',
      result: '页面动作失败',
      outcome: 'failed',
      code: 'EXECUTOR_FAILED',
      retryable: true,
      target: null
    })
    expect(toolPart(message, 'f1')).toMatchObject({
      status: 'failed',
      result: '页面动作失败',
      requiresAction: false
    })
    deriveProjections(message)
    expect(message.toolRuns[0]).toMatchObject({ status: 'failed' })
  })

  it('旧形态（无 outcome）走原逻辑：status=FRONTEND_RESULT ⇒ succeeded、status 缺失回落 ok 三元', () => {
    const legacy = makeMessage()
    applyFrameToParts(legacy, {
      type: 'frontend_tool_result',
      toolCallId: 'f2',
      name: 'probe.tool',
      ok: true,
      executed: true,
      status: 'FRONTEND_RESULT',
      result: 'ok'
    })
    expect(toolPart(legacy, 'f2')).toMatchObject({ status: 'succeeded' })

    const fallback = makeMessage()
    applyFrameToParts(fallback, {
      type: 'frontend_tool_result',
      toolCallId: 'f3',
      name: 'probe.tool',
      ok: false,
      executed: false
    })
    expect(toolPart(fallback, 'f3')).toMatchObject({ status: 'failed' })
  })
})

describe('治理 P0-1 M1：历史非终态条目不被 outcome=unknown 覆写', () => {
  it('status=PENDING/APPROVED + outcome=unknown ⇒ 保持 pending（不落 failed）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '等待人工的历史轮',
          toolCalls: [
            { id: 'm1', type: 'function', name: 'danger_tool', arguments: '{}' },
            { id: 'm2', type: 'function', name: 'danger_tool', arguments: '{}' }
          ],
          toolCallStates: {
            m1: { status: 'PENDING', kind: 'CONFIRM', outcome: 'unknown' },
            m2: { status: 'APPROVED', kind: 'CONFIRM', outcome: 'unknown' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const message = store.messages[0] as ChatMessage
    expect(toolPart(message, 'm1')).toMatchObject({ status: 'pending', kind2: 'CONFIRM' })
    expect(toolPart(message, 'm2')).toMatchObject({ status: 'pending', kind2: 'CONFIRM' })
  })
})

describe('契约增量：SKIPPED 一等状态（上游确定性失败 ⇒ 本工具未执行）', () => {
  it('tool_result outcome=skipped（真实后端序列：无 tool_start）⇒ 仅凭帧建段、只落「输出」、reason 不存在（不双显）', () => {
    const TEXT = '未执行：上游 start_export 失败（PRECHECK_NOT_PASSED），本调用已跳过'
    const message = makeMessage()
    // 真实序列：批次停走时该调用**从未执行**，后端只发 tool_result（不发 tool_start）。
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 's1',
      name: 'start_import',
      kind: 'BACKEND',
      ok: false,
      executed: false,
      status: 'SKIPPED',
      outcome: 'skipped',
      code: 'UPSTREAM_FAILED',
      result: TEXT
    })

    // 仅凭 tool_result 建段（不依赖先行 tool_start）
    expect(message.parts.filter((part) => part.kind === 'tool')).toHaveLength(1)
    const part = toolPart(message, 's1')
    expect(part).toMatchObject({
      status: 'skipped',
      result: TEXT,
      outcome: 'skipped',
      requiresAction: false
    })
    deriveProjections(message)
    expect(message.toolRuns[0]).toMatchObject({ status: 'skipped' })
    // 卡面状态词表：skipped ⇒「已跳过」（中性灰族），不是「执行失败」也不是「已超时」
    expect(cardStatusOf(part)).toEqual({ key: 'skipped', label: '已跳过', tone: 'neutral' })

    // F1 收口 I-3（裁决）：reason 键根本不写 ⇒ AiPanel 红色「原因」行（`!isBlankText(part.reason)`）
    // 自然不渲染，同一句文案只在「输出」出现一次（卡面同文双显清零）。
    expect(Object.prototype.hasOwnProperty.call(part, 'reason')).toBe(false)
    expect(part.reason).toBeUndefined()
    const carrying = [part.result, part.reason].filter((text) => typeof text === 'string' && text.includes(TEXT))
    expect(carrying).toHaveLength(1)
  })

  it('旧帧只带 envelope message（缺 result）⇒ 文案仍落「输出」，reason 同样不写', () => {
    const TEXT = '未执行：上游 start_export 失败，本工具未执行'
    const message = makeMessage()
    applyFrameToParts(message, {
      type: 'tool_result',
      toolCallId: 's2',
      name: 'start_import',
      kind: 'BACKEND',
      ok: false,
      executed: false,
      status: 'SKIPPED',
      outcome: 'skipped',
      code: 'UPSTREAM_FAILED',
      message: TEXT,
      result: null
    })
    const part = toolPart(message, 's2')
    expect(part).toMatchObject({ status: 'skipped', result: TEXT })
    expect(Object.prototype.hasOwnProperty.call(part, 'reason')).toBe(false)
  })

  it('历史 toolCallStates 的 SKIPPED status / outcome=skipped ⇒ 刷新后段状态 skipped', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '上游失败批次',
          toolCalls: [
            { id: 's1', type: 'function', name: 'start_import', arguments: '{}' },
            { id: 's2', type: 'function', name: 'start_publish', arguments: '{}' }
          ],
          toolCallStates: {
            s1: { status: 'SKIPPED', kind: 'BACKEND' },
            s2: { status: 'EXECUTED', kind: 'BACKEND', outcome: 'skipped', code: 'UPSTREAM_FAILED' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const message = store.messages[0] as ChatMessage
    expect(toolPart(message, 's1')).toMatchObject({ status: 'skipped', kind2: 'BACKEND', requiresAction: false })
    expect(toolPart(message, 's2')).toMatchObject({ status: 'skipped', kind2: 'BACKEND', requiresAction: false })
    expect(cardStatusOf(toolPart(message, 's1')).label).toBe('已跳过')
  })
})

describe('契约增量：PRECHECK_FAILED 历史终态（outcome 缺失时不得误显示成功）', () => {
  it('历史 toolCallStates 的 PRECHECK_FAILED（无 outcome）⇒ 刷新后段状态 failed，而非占位 succeeded', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '预检未通过，本次未进入确认门',
          toolCalls: [
            { id: 'pf1', type: 'function', name: 'start_publish', arguments: '{"taskId":5}' }
          ],
          // 真实载荷可能不带 outcome（旧写入 / 回灌重建）—— 只有 status 可依
          toolCallStates: {
            pf1: { status: 'PRECHECK_FAILED', kind: 'CONFIRM' }
          }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const message = store.messages[0] as ChatMessage
    expect(toolPart(message, 'pf1')).toMatchObject({ status: 'failed', kind2: 'CONFIRM', requiresAction: false })
    expect(cardStatusOf(toolPart(message, 'pf1')).label).toContain('执行失败')
  })
})

describe('治理 P0-1 M2：blocked 卡面 reason 按 code 选择', () => {
  it('TOOL_ALREADY_CLAIMED ⇒ 互斥抢占文案；SCOPE_NOT_DISCLOSED / 缺省 ⇒ 原文案', () => {
    const claimed = makeMessage()
    applyFrameToParts(claimed, {
      type: 'tool_result',
      toolCallId: 'k1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: false,
      executed: false,
      status: 'BLOCKED',
      outcome: 'blocked',
      code: 'TOOL_ALREADY_CLAIMED'
    })
    expect(toolPart(claimed, 'k1')).toMatchObject({
      status: 'blocked',
      reason: '该操作已由另一实例执行，本次未重复执行'
    })

    const scope = makeMessage()
    applyFrameToParts(scope, {
      type: 'tool_result',
      toolCallId: 'k2',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: false,
      executed: false,
      status: 'BLOCKED',
      outcome: 'blocked',
      code: 'SCOPE_NOT_DISCLOSED'
    })
    expect(toolPart(scope, 'k2')).toMatchObject({
      status: 'blocked',
      reason: '该工具不在当前工作区上下文的披露范围内，未执行'
    })

    const noCode = makeMessage()
    applyFrameToParts(noCode, {
      type: 'tool_result',
      toolCallId: 'k3',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: false,
      executed: false,
      status: 'BLOCKED',
      outcome: 'blocked'
    })
    expect(toolPart(noCode, 'k3')).toMatchObject({
      status: 'blocked',
      reason: '该工具不在当前工作区上下文的披露范围内，未执行'
    })
  })

  // B23L1 批③b：资源锁冲突 / 目标资源不成立 —— 与"未披露"分开说，且只增不改（既有两支保持原样）
  it('RESOURCE_LOCKED / TARGET_INVALID ⇒ 各自的阻断文案（status 仍是 blocked，不被降级）', () => {
    const locked = makeMessage()
    applyFrameToParts(locked, {
      type: 'tool_result',
      toolCallId: 'k4',
      name: 'start_import',
      kind: 'CONFIRM',
      ok: false,
      executed: false,
      status: 'BLOCKED',
      outcome: 'blocked',
      code: 'RESOURCE_LOCKED',
      retryable: true
    })
    expect(toolPart(locked, 'k4')).toMatchObject({
      status: 'blocked',
      reason: '该任务已有作业在执行，本次未执行'
    })

    const invalid = makeMessage()
    applyFrameToParts(invalid, {
      type: 'tool_result',
      toolCallId: 'k5',
      name: 'start_import',
      kind: 'CONFIRM',
      ok: false,
      executed: false,
      status: 'BLOCKED',
      outcome: 'blocked',
      code: 'TARGET_INVALID',
      retryable: false
    })
    expect(toolPart(invalid, 'k5')).toMatchObject({
      status: 'blocked',
      reason: '目标资源不存在或不在当前任务内，本次未执行'
    })

    // 只升不降：两支都仍是阻断口径（不是 failed/succeeded），且既有两支在上面那例原样通过
    expect(outcomeStatus('blocked')).toBe('blocked')
  })
})

describe('契约增量：SHEET_DISABLED 历史终态（治理批）', () => {
  it('历史 toolCallStates 的 SHEET_DISABLED（无 outcome）⇒ 刷新后段状态 failed，不是占位 succeeded', () => {
    expect(historyToolCallStatus('SHEET_DISABLED')).toBe('failed')
    // 其余既有映射不回退（只增不改）
    expect(historyToolCallStatus('PRECHECK_FAILED')).toBe('failed')
    expect(historyToolCallStatus('BLOCKED')).toBe('blocked')
    expect(historyToolCallStatus('EXECUTED')).toBe('succeeded')
  })
})
