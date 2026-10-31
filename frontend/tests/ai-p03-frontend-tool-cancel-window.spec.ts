/**
 * P03（前端工具卡）：执行结局必须**先回灌受理、再写最终状态**，且异常/超时不得留永久 running。
 *
 * <h2>缺陷</h2>
 * `runFrontendTool` 原先在 `submitFrontendToolResult` **之前**就把段写成 succeeded。回灌 POST
 * 是一段真实 await 窗口（最坏 60s）：用户在这段里点「停止」，`stop()` 的
 * `cancelRoundParts` 只收 pending/running 段 —— 已经写成 succeeded 的段不在收敛范围内，
 * 于是本轮被停止、卡却仍写「已成功」。结局未受理就先落终态，正是"停止窗口无法取消"。
 *
 * <h2>口径（本文件锁住）</h2>
 * <ol>
 * <li>先回灌受理（`submitFrontendToolResult`），**仍在同轮**才写最终状态；</li>
 * <li>停止/换轮之后不得覆盖 `cancelled`（本地已收口的现场优先）；</li>
 * <li>异常必须收敛（finally 式），段不得永久停在 running；</li>
 * <li>迟到结局（停止后才返回的执行器）零写、零回灌 —— 既有约束不变。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
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

/** 只替换执行器（其余前端工具面照旧真实：`deriveFrontendToolBudgetMs` / `parseToolArgs` 等）。 */
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

/** 按 `frontend_tool_request` 帧的落段形态造宿主：段先 pending，执行器开跑才转 running。 */
function armTool(
  store: ReturnType<typeof useAiStore>,
  toolCallId = 'p1'
): { message: ChatMessage; frame: ToolRequestFrame } {
  const message = store.appendAssistantMessage('')
  store.upsertToolPart(message, {
    toolCallId,
    name: 'probe.tool',
    kind2: 'FRONTEND',
    status: 'pending',
    args: '{}',
    requiresAction: false
  })
  return {
    message,
    frame: {
      type: 'frontend_tool_request',
      toolCallId,
      name: 'probe.tool',
      args: '{}',
      timeoutSeconds: 120,
      runId: 'run-p03',
      seq: 2
    }
  }
}

const SUCCESS = { ok: true, outcome: 'ok' as const, availability: 'ok' as const, result: '已执行' }

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
  frontendTools.executeFrontendTool.mockReset()
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
})

describe('P03：先回灌受理，仍在同轮才写最终状态', () => {
  it('停止落在回灌窗口内 ⇒ 卡保持 cancelled，不被写成 succeeded', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)
    let releaseSubmit: (() => void) | null = null
    api.frontendToolResult.mockImplementation(() => new Promise<void>((resolve) => {
      releaseSubmit = () => resolve()
    }))

    const store = useAiStore()
    store.loading = true
    store.activeRunId = 'run-p03'
    const { message, frame } = armTool(store)

    const run = store.runFrontendTool(message, frame)
    await vi.waitFor(() => expect(api.frontendToolResult).toHaveBeenCalledTimes(1))
    // 回灌还没受理：段仍在 running（停止能收到它）
    expect(toolPart(message, 'p1').status).toBe('running')

    await store.stop()
    expect(toolPart(message, 'p1')).toMatchObject({ status: 'cancelled' })

    // 放行回灌应答（TS 的 CFA 看不到闭包内赋值，这里按仓内既有写法显式断言函数类型）
    const release = releaseSubmit as unknown as (() => void) | null
    release?.()
    await run

    // 停止已落地 ⇒ 迟到的"成功"不得把 cancelled 翻回去
    expect(toolPart(message, 'p1')).toMatchObject({ status: 'cancelled', decision: 'cancel' })
  })

  it('成功：回灌受理之后才写 succeeded，回灌体仍带执行器结局', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue(SUCCESS)
    let statusAtSubmit: string | null = null
    const store = useAiStore()
    store.activeRunId = 'run-p03'
    const { message, frame } = armTool(store)
    api.frontendToolResult.mockImplementation(async () => {
      statusAtSubmit = toolPart(message, 'p1').status
      return {}
    })

    await store.runFrontendTool(message, frame)

    expect(statusAtSubmit).toBe('running')
    expect(toolPart(message, 'p1')).toMatchObject({
      status: 'succeeded',
      result: '已执行',
      requiresAction: false
    })
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ runId: 'run-p03', toolCallId: 'p1', outcome: 'ok' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
  })

  it('迟到结局（停止后才返回的执行器）⇒ 零写、零回灌，段仍是 cancelled', async () => {
    let releaseExecution: (() => void) | null = null
    frontendTools.executeFrontendTool.mockImplementation(() => new Promise((resolve) => {
      releaseExecution = () => resolve(SUCCESS)
    }))

    const store = useAiStore()
    store.loading = true
    store.activeRunId = 'run-p03'
    const { message, frame } = armTool(store)

    const run = store.runFrontendTool(message, frame)
    await flushPromises()
    expect(toolPart(message, 'p1').status).toBe('running')

    await store.stop()
    const release = releaseExecution as unknown as (() => void) | null
    release?.()
    await run

    expect(toolPart(message, 'p1')).toMatchObject({ status: 'cancelled', decision: 'cancel' })
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('执行器异常 ⇒ finally 式收敛为 failed（不留永久 running）', async () => {
    frontendTools.executeFrontendTool.mockRejectedValue(new Error('执行器炸了'))

    const store = useAiStore()
    store.activeRunId = 'run-p03'
    const { message, frame } = armTool(store)

    await expect(store.runFrontendTool(message, frame)).resolves.toBeUndefined()

    expect(toolPart(message, 'p1').status).toBe('failed')
    expect(toolPart(message, 'p1').status).not.toBe('running')
    expect(toolPart(message, 'p1').requiresAction).toBe(false)
  })

  it('执行器超时 ⇒ 段收敛为终态（不是永久 running），回灌带 outcome=timeout', async () => {
    frontendTools.executeFrontendTool.mockResolvedValue({
      ok: false,
      outcome: 'timeout',
      availability: 'degraded',
      result: '执行超时，本次未完成'
    })

    const store = useAiStore()
    store.activeRunId = 'run-p03'
    const { message, frame } = armTool(store)

    await store.runFrontendTool(message, frame)

    // 前端工具状态词表：timeout 落 failed（结局八态由回灌体承载，见 ui-receipt 的映射表）
    expect(toolPart(message, 'p1')).toMatchObject({ status: 'failed', result: '执行超时，本次未完成' })
    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'p1', outcome: 'timeout' }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
  })
})
