/**
 * 取数窗口内"快速完成/失败的一轮"必须保住 / 前端工具异常与回灌失败的最小收敛（F3 / F4 / F6）。
 *
 * <h2>缺陷</h2>
 * <ul>
 * <li><b>F3</b>：R04 把 `roundChanged` 换成"现场里有没有真轮"（loading / suspended / currentAbort），
 *   但一轮若在取数窗口内**开流并收尾**，三个现场判据都已复位 ⇒ 过期历史仍会整替，把刚完成的问答 /
 *   错误卡抹掉。真轮的可观测证据是**单调的 start 帧**（409 的未起轮永远没有它）。</li>
 * <li><b>F4</b>：`executeFrontendTool` 抛异常时只落本地 failed —— 后端仍挂着等满 120s 前端工具上限。
 *   必须把失败事实回灌，让这一轮及时收敛。</li>
 * <li><b>F6</b>：回灌 `ok:false`（非 duplicate）不能盲写本地终态（执行器自称成功不等于后端已受理），
 *   要保留后端规范文本；409 仍然是权威（既有测试锁定）。</li>
 * </ul>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { ApiError } from '@/api/http'
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

const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

type ToolRequestFrame = Extract<SseFrame, { type: 'frontend_tool_request' }>

function toolPart(message: ChatMessage, toolCallId: string): ToolPart {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  expect(part).toBeDefined()
  return part as ToolPart
}

function requestFrame(toolCallId: string, runId: string): ToolRequestFrame {
  return {
    type: 'frontend_tool_request',
    toolCallId,
    name: 'probe.tool',
    args: '{}',
    timeoutSeconds: 120,
    runId,
    seq: 2
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve: (value: T) => void = () => undefined
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
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
  api.chat.mockImplementation(() => new Promise(() => {}))
  frontendTools.executeFrontendTool.mockReset()
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
})

/**
 * 驱动一段"真轮"：start → delta → 终帧。返回宿主消息。
 *
 * <p>刻意**不走 send()**：这里要的正是"取数窗口内开流并收尾"的最短形态，帧路就是权威证据。
 */
function runFastRound(
  store: ReturnType<typeof useAiStore>,
  runId: string,
  terminal: 'done' | 'error' = 'done'
): ChatMessage {
  store.loading = true
  const host = store.appendAssistantMessage('')
  store.handleFrame({ type: 'start', runId, sessionId: store.sessionId, seq: 1 })
  store.handleFrame({ type: 'delta', runId, text: '刚完成的回答', seq: 2 })
  store.handleFrame(terminal === 'done'
    ? { type: 'done', runId, seq: 3 }
    : { type: 'error', runId, code: 'MODEL_FAILED', message: '模型调用失败', seq: 3 })
  return host
}

describe('F3 取数窗口内开流并收尾的一轮：历史不得整替', () => {
  it('窗口内 start→delta→done（现场判据全部复位）⇒ 历史整份丢弃，刚完成的问答保留', async () => {
    const store = useAiStore()
    store.sessionId = 's-f3'
    const history = deferred<{ sessionId: string; count: number; messages: unknown[] }>()
    api.history.mockImplementation(() => history.promise)

    const loading = store.loadHistory()
    await flushPromises()

    runFastRound(store, 'run-fast-done')
    expect(store.loading).toBe(false)
    await flushPromises()

    history.resolve({
      sessionId: 's-f3',
      count: 1,
      messages: [{ type: 'USER', text: '上一轮的老问题' }]
    })
    await loading
    await flushPromises()

    expect(store.messages.some((message) => message.content.includes('刚完成的回答'))).toBe(true)
    expect(store.messages.some((message) => message.content === '上一轮的老问题')).toBe(false)
  })

  it('窗口内开流后以 error 收尾 ⇒ 错误卡不被历史整替抹掉', async () => {
    const store = useAiStore()
    store.sessionId = 's-f3b'
    const history = deferred<{ sessionId: string; count: number; messages: unknown[] }>()
    api.history.mockImplementation(() => history.promise)

    const loading = store.loadHistory()
    await flushPromises()

    const host = runFastRound(store, 'run-fast-error', 'error')
    expect(host.errorCard).not.toBeNull()
    await flushPromises()

    history.resolve({
      sessionId: 's-f3b',
      count: 1,
      messages: [{ type: 'USER', text: '上一轮的老问题' }]
    })
    await loading
    await flushPromises()

    const kept = store.messages.find((message) => message.id === host.id)
    expect(kept?.errorCard).not.toBeNull()
    expect(store.messages.some((message) => message.content === '上一轮的老问题')).toBe(false)
  })
})

describe('F4 执行器异常：落 failed 且回灌失败事实（不等 120s）', () => {
  it('executeFrontendTool 抛错 ⇒ 段 failed 且 frontend-tool-result 带 outcome=failed', async () => {
    frontendTools.executeFrontendTool.mockRejectedValue(new Error('执行器炸了'))
    const store = useAiStore()
    store.activeRunId = 'run-f4'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-f4'
    store.upsertToolPart(host, {
      toolCallId: 'tc-f4',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'pending',
      args: '{}',
      requiresAction: false
    })

    await store.runFrontendTool(host, requestFrame('tc-f4', 'run-f4'))

    expect(toolPart(host, 'tc-f4').status).toBe('failed')
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'tc-f4', runId: 'run-f4', outcome: 'failed' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
  })
})

describe('F6 回灌未被受理（非 duplicate）：不盲写本地终态，保留后端文本', () => {
  it('执行器报成功但回灌 500 ⇒ 段不得是 succeeded，中性收敛并带上后端文本', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue({
      ok: true, outcome: 'ok', availability: 'ok', result: '已执行'
    })
    api.frontendToolResult.mockRejectedValue(new ApiError('保存失败：服务端不可用', 500, 'INTERNAL_ERROR', {}))

    const store = useAiStore()
    store.activeRunId = 'run-f6'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-f6'
    store.upsertToolPart(host, {
      toolCallId: 'tc-f6',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{}',
      requiresAction: false
    })

    await store.runFrontendTool(host, requestFrame('tc-f6', 'run-f6'))

    const part = toolPart(host, 'tc-f6')
    expect(part.status).not.toBe('succeeded')
    expect(part.status).toBe('unknown')
    expect(`${part.reason ?? ''}${part.result ?? ''}`).toContain('保存失败：服务端不可用')
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
  })

  it('已决段不被回灌失败改写（后端/帧已给结论时保持原值）', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue({
      ok: true, outcome: 'ok', availability: 'ok', result: '已执行'
    })

    const store = useAiStore()
    store.activeRunId = 'run-f6b'
    const host = store.appendAssistantMessage('')
    host.runId = 'run-f6b'
    store.upsertToolPart(host, {
      toolCallId: 'tc-f6b',
      name: 'probe.tool',
      kind2: 'FRONTEND',
      status: 'running',
      args: '{}',
      requiresAction: false
    })
    // 结果帧在回灌 awaiting 期间先到（段已 succeeded），随后回灌才失败
    api.frontendToolResult.mockImplementation(async () => {
      store.upsertToolPart(host, {
        toolCallId: 'tc-f6b',
        name: 'probe.tool',
        kind2: 'FRONTEND',
        status: 'succeeded',
        result: '已执行',
        requiresAction: false
      })
      throw new ApiError('保存失败：服务端不可用', 500, 'INTERNAL_ERROR', {})
    })

    await store.runFrontendTool(host, requestFrame('tc-f6b', 'run-f6b'))

    expect(toolPart(host, 'tc-f6b')).toMatchObject({ status: 'succeeded', result: '已执行' })
  })
})
