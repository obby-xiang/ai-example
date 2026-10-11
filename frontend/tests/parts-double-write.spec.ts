/**
 * 对话时序包 批1（双写过渡）：`ChatMessage.parts` 与旧投影（content / toolRuns / pendingCall）
 * 的一致性回归锁 —— 帧序列经 store.handleFrame 送进真实 store，逐帧核对两侧口径。
 *
 * 本批口径：`parts` 是**旁路真值**（带顺序信息的判别联合），旧字段仍是渲染真值；
 * 两侧必须逐字一致（视觉零变化）。测试只断言纯逻辑层，不挂载面板。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart, ToolPart } from '@/types/ai'
import { MIRROR_PREFIX, useAiStore } from '@/stores/ai'

// 镜像迁移用例要走 loadHistory：把后端两个取数端点桩掉（其余用例不碰 API）
const api = vi.hoisted(() => ({ history: vi.fn(), runsCurrent: vi.fn() }))

vi.mock('@/api/ai', () => ({ aiApi: api }))

beforeEach(() => {
  setActivePinia(createPinia())
})

/** 把 parts 里的工具段抽成可与 toolRuns 对账的投影（字段口径两侧同名）。 */
function toolPartsOf(parts: MessagePart[]): Array<Record<string, unknown>> {
  return parts
    .filter((part): part is ToolPart => part.kind === 'tool')
    .map((part) => ({
      toolCallId: part.toolCallId,
      name: part.name,
      kind: part.kind2,
      status: part.status,
      args: part.args,
      result: part.result,
      messageId: part.messageId
    }))
}

/** 旧投影 toolRuns 的同名对账面。 */
function toolRunsOf(message: ChatMessage): Array<Record<string, unknown>> {
  return message.toolRuns.map((run) => ({
    toolCallId: run.toolCallId,
    name: run.name,
    kind: run.kind,
    status: run.status,
    args: run.args,
    result: run.result,
    messageId: run.messageId
  }))
}

/** 段结构快照（message_end 前后对比用：段序、编号、文本；不含流式标志）。 */
function segDigest(parts: MessagePart[]): string {
  return JSON.stringify(parts.map((part) => ({
    kind: part.kind,
    seg: part.seg,
    text: part.kind === 'tool' ? '' : part.text
  })))
}

describe('parts 双写：文本 / 工具段的顺序与封口', () => {
  it('文本-工具-文本序列：段序正确、seg 单调、tool_result 就地更新不新建段、message_end 不改段结构', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'message_start', messageId: 'm1', kind: 'text', seq: 1 })
    store.handleFrame({ type: 'delta', text: '你好', messageId: 'm1', seq: 2 })

    // 文本段：单段、流式中
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text'])
    expect(assistant.parts[0]).toMatchObject({ kind: 'text', seg: 0, text: '你好', streaming: true })

    store.handleFrame({
      type: 'tool_start',
      toolCallId: 'c1',
      name: 'probe_tool',
      kind: 'BACKEND',
      args: '{"a":1}',
      seq: 3
    })

    // 工具段插入：先封口末尾文本段，再 push 工具段
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[0]).toMatchObject({ kind: 'text', seg: 0, text: '你好', streaming: false })
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'c1',
      name: 'probe_tool',
      kind2: 'BACKEND',
      status: 'running',
      args: '{"a":1}',
      requiresAction: false
    })

    store.handleFrame({
      type: 'tool_result',
      toolCallId: 'c1',
      name: 'probe_tool',
      kind: 'BACKEND',
      ok: true,
      executed: true,
      status: 'EXECUTED',
      messageId: 'tool-c1',
      result: 'ok',
      seq: 4
    })

    // ③ 就地更新：仍是同一段（seg 不变、不产生第二个工具段）
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'c1',
      status: 'succeeded',
      result: 'ok',
      messageId: 'tool-c1'
    })

    store.handleFrame({ type: 'delta', text: '后续', messageId: 'm1', seq: 5 })

    // 工具段之后的正文另起新文本段（seg 递增，不复用已封口段）
    const kinds = assistant.parts.map((part) => part.kind)
    expect(kinds).toEqual(['text', 'tool', 'text'])
    const segs = assistant.parts.map((part) => part.seg)
    expect(segs).toEqual([0, 1, 2])
    expect(segs).toEqual([...segs].sort((a, b) => a - b))
    expect(new Set(segs).size).toBe(segs.length)
    expect(assistant.parts[2]).toMatchObject({ kind: 'text', seg: 2, text: '后续', streaming: true })

    // ④ 段边界帧不改段结构
    const before = segDigest(assistant.parts)
    store.handleFrame({ type: 'message_end', messageId: 'm1', kind: 'text', chars: 4, seq: 6 })
    expect(segDigest(assistant.parts)).toBe(before)

    store.handleFrame({ type: 'done', runId: 'run-1', seq: 7 })
    expect(store.messages[0]?.parts.map((part) => part.kind)).toEqual(['text', 'tool', 'text'])

    // ② 旧投影与双写前逐字一致
    expect(assistant.content).toBe('你好后续')
    const textParts = assistant.parts.filter((part) => part.kind === 'text')
    expect(assistant.content).toBe(textParts.map((part) => (part.kind === 'text' ? part.text : '')).join(''))
    expect(toolRunsOf(assistant)).toEqual(toolPartsOf(assistant.parts))
    expect(assistant.toolRuns).toHaveLength(1)
    expect(assistant.pendingCall).toBeNull()
    expect(assistant.streaming).toBe(false)
    expect(assistant.done).toBe(true)
  })

  it('确认门：pendingCall 与工具段对账一致，pending→rejected 两侧同步且不新建段', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 'c2',
      name: 'danger_tool',
      args: '{"x":1}',
      timeoutSeconds: 120,
      expiresAt: 1_700_000_120_000,
      atMs: 1_700_000_000_000,
      seq: 1
    })

    expect(assistant.pendingCall).toMatchObject({ toolCallId: 'c2', kind: 'CONFIRM', status: 'pending', rawArgs: '{"x":1}' })
    expect(assistant.parts.filter((part) => part.kind === 'tool')).toHaveLength(1)
    // 段序：工厂起头的空文本段（seg 0）被工具段封口，工具段落在 seg 1
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      toolCallId: 'c2',
      kind2: 'CONFIRM',
      status: 'pending',
      args: '{"x":1}',
      requiresAction: true
    })
    expect(toolRunsOf(assistant)).toEqual(toolPartsOf(assistant.parts))

    store.handleFrame({
      type: 'confirm_decision',
      toolCallId: 'c2',
      name: 'danger_tool',
      decision: 'REJECTED',
      approved: false,
      status: 'REJECTED',
      reason: '用户拒绝',
      seq: 2
    })

    expect(assistant.pendingCall?.status).toBe('rejected')
    expect(assistant.parts.filter((part) => part.kind === 'tool')).toHaveLength(1)
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      toolCallId: 'c2',
      status: 'rejected',
      reason: '用户拒绝',
      requiresAction: false
    })
    expect(toolRunsOf(assistant)).toEqual(toolPartsOf(assistant.parts))
  })

  it('思考段（reasoning 帧）：另起段、与正文段互不覆盖，旧投影 reasoning 同步拼接', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()

    store.handleFrame({ type: 'reasoning', text: '想' })
    store.handleFrame({ type: 'delta', text: '答', seq: 2 })
    store.handleFrame({ type: 'delta', text: '案', seq: 3 })
    store.handleFrame({ type: 'reasoning', text: '再想' })

    // 段序：工厂的空文本段(0) → 思考段(1) → 正文段(2) → 思考段(3)：段间不串写
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'reasoning', 'text', 'reasoning'])
    expect(assistant.parts.map((part) => part.seg)).toEqual([0, 1, 2, 3])
    expect(assistant.parts[1]).toMatchObject({ kind: 'reasoning', seg: 1, text: '想' })
    expect(assistant.parts[2]).toMatchObject({ kind: 'text', seg: 2, text: '答案' })
    expect(assistant.parts[3]).toMatchObject({ kind: 'reasoning', seg: 3, text: '再想' })
    expect(assistant.reasoning).toBe('想再想')
    expect(assistant.content).toBe('答案')
  })

  it('消息工厂：user 消息正文即为唯一文本段，assistant 新消息以空文本段起头', () => {
    const store = useAiStore()
    const user = store.recordUserMessage('你好呀')

    expect(user.parts).toEqual([{ kind: 'text', seg: 0, text: '你好呀', streaming: false }])
    expect(user.content).toBe('你好呀')

    const assistant = store.appendAssistantMessage()
    expect(assistant.parts).toEqual([{ kind: 'text', seg: 0, text: '', streaming: true }])
  })

  it('镜像迁移（§6.4）：v1 旧镜像补出 parts 并立即回写 v2，待决条目并入工具段，迁移幂等', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    // v1 镜像（无 v、无 parts）：正文 + 一条工具卡 + 一条与之同 toolCallId 的待决条目
    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      savedAt: 1,
      lastRunId: 'run-k',
      messages: [
        { id: 'u-1', role: 'user', content: '你好', reasoning: '', toolRuns: [], pendingCall: null },
        {
          id: 'a-1',
          role: 'assistant',
          content: '正文',
          reasoning: '',
          toolRuns: [{ toolCallId: 'k1', name: 'probe_tool', kind: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: 'ok', messageId: 'tool-k1' }],
          pendingCall: { toolCallId: 'k1', name: 'probe_tool', kind: 'CONFIRM', args: { a: 1 }, rawArgs: '{"a":1}', status: 'pending' }
        }
      ]
    }))
    api.history.mockResolvedValue({ sessionId: sid, count: 0, messages: [] })
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

    expect(store.messages[0]?.parts).toEqual([{ kind: 'text', seg: 0, text: '你好', streaming: false }])
    expect(store.messages[1]?.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(store.messages[1]?.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'k1',
      name: 'probe_tool',
      // 待决条目并入：kind→kind2、requiresAction 依"仍在等动作"置真；
      // status 以 toolRun 为权威（存量 v1 镜像 toolRun 已终态时不得被待决口径覆盖）
      kind2: 'CONFIRM',
      status: 'succeeded',
      requiresAction: true,
      // 原工具卡的字段保留（合并是就地 spread）
      args: '{"a":1}',
      result: 'ok',
      messageId: 'tool-k1'
    })

    // 立即回写：镜像已是 v2 且带 parts（不回写则下次刷新又迁一遍）
    const written = JSON.parse(sessionStorage.getItem(MIRROR_PREFIX + sid) ?? '{}')
    expect(written.v).toBe(2)
    expect(written.messages[1].parts).toHaveLength(2)
    const savedAt = written.savedAt
    expect(typeof savedAt).toBe('number')

    // 幂等：再走一次读取侧，v2 完整镜像直接放行（不再回写 —— savedAt 不变）
    store.messages = []
    await store.loadHistory()
    expect(store.messages[1]?.parts).toHaveLength(2)
    const again = JSON.parse(sessionStorage.getItem(MIRROR_PREFIX + sid) ?? '{}')
    expect(again.savedAt).toBe(savedAt)
  })
})
