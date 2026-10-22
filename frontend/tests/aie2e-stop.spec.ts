/**
 * E2E 用例全集（第 53 件）· STP 手工停止域：单测缺口补齐（P-02 ~ P-13，P-10/P-13 的 DOM 面见
 * `aie2e-panel.spec.ts`）。
 *
 * <p>蓝本里这一域有 7 条是 ❌（P-02/P-04/P-06/P-07/P-08/P-12/P-13）—— 停止的**段级收敛**已由
 * `parts-residue-cleanup`/`rg-round-restore-stop`（G1/G2/S1~S4）锁住，缺的是
 * "停止之后用户还能不能继续用"（刷新看到什么、能不能再发、会不会双发、迟到帧会不会脏）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import type { ChatMessage } from '@/types/ai'
import {
  frame,
  freshStore,
  readMirrorFixture,
  textMessage,
  toolPart, nextSession
} from './aie2e-helpers'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

let SESSION = ''

interface FrameHandlers {
  onFrame: (incoming: unknown) => void
  onClose?: (info: { terminal: boolean; reason?: string; suspended?: boolean }) => void
}

/** 一条"正文 + 一张工具卡"的助手消息。 */
function assistantWithTool(text: string, toolCallId: string, status: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    ...textMessage('a-1', 'assistant', text, { streaming: true, done: false }),
    parts: [
      { kind: 'text', seg: 0, text, streaming: true },
      toolPart(toolCallId, 1, { status })
    ],
    ...overrides
  }
}

beforeEach(() => {
  sessionStorage.clear()
  SESSION = nextSession('s-stop')
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: SESSION, count: 0, messages: [] })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('P-02（P0）：停止后刷新 —— 正文不得整条消失', () => {
  /**
   * 判据：屏上出现「上一轮已停止（N 字）」只读条**或**半截正文；不得整条消失。
   * 后端侧对应的帧/落库事实（`stopped` 元数据 + 半截正文）已由 `CancelLedgerConformanceTest`
   * （后端）锁住；本条锁**前端读回**：`stopped` 透出且正文仍在。
   */
  it('历史回读带 stopped 的半截正文：正文保留且标记为已停止', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 2,
      messages: [
        { type: 'USER', text: '解释一下区域费率' },
        { type: 'ASSISTANT', text: '区域费率是按区域配置的分档价格，', stopped: true }
      ]
    })

    await store.loadHistory()

    const assistant = store.messages.at(-1)
    expect(assistant?.role).toBe('assistant')
    expect(assistant?.content).toBe('区域费率是按区域配置的分档价格，') // 不整条消失
    expect(assistant?.stopped).toBe(true) // 「已停止」标识的判据
  }, 20_000)
})

describe('P-03（P0）：工具执行中停止 —— 卡收敛且镜像同段终态', () => {
  it('running 卡停止后：段转 cancelled、镜像里同段也是终态', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('u-1', 'user', '导出'), assistantWithTool('正在导出…', 'c1', 'running')]
    store.loading = true
    store.activeRunId = 'run-3'

    await store.stop()

    const card = store.messages[1]?.parts.find((part) => part.kind === 'tool')
    expect(card?.kind === 'tool' ? card.status : null).toBe('cancelled')
    expect(card?.kind === 'tool' ? card.requiresAction : true).toBe(false)
    const mirrored = readMirrorFixture(SESSION)?.messages?.[1]?.parts?.find(
      (part) => part.kind === 'tool' && part.toolCallId === 'c1'
    )
    expect(mirrored?.kind === 'tool' ? mirrored.status : null).toBe('cancelled') // 镜像不留脏态
  }, 20_000)
})

describe('P-04（P1）：停止在收尾处（对照）—— 不得"一律标已取消"', () => {
  /**
   * 判据：4 卡全成功、正文收尾中 ⇒ 卡与正文全保留、输入框立刻可用、零残留。
   * 与 P-03 配对：这一条防的是"过度修复"（把已成功的卡也改写掉）。
   */
  it('4 张 succeeded 卡 + 正文：停止后逐卡仍为 succeeded，正文不动，输入解禁', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    const parts = [
      { kind: 'text' as const, seg: 0, text: '四步都跑完了', streaming: true },
      toolPart('c1', 1, { status: 'succeeded' }),
      toolPart('c2', 2, { status: 'succeeded' }),
      toolPart('c3', 3, { status: 'succeeded' }),
      toolPart('c4', 4, { status: 'succeeded' })
    ]
    store.messages = [
      textMessage('u-1', 'user', '跑四步'),
      { ...textMessage('a-1', 'assistant', '四步都跑完了', { streaming: true, done: false }), parts }
    ]
    store.loading = true
    store.activeRunId = 'run-4'

    await store.stop()

    const cards = store.messages[1]?.parts.filter((part) => part.kind === 'tool') ?? []
    expect(cards).toHaveLength(4)
    expect(cards.every((part) => part.kind === 'tool' && part.status === 'succeeded')).toBe(true)
    expect(store.messages[1]?.content).toBe('四步都跑完了')
    expect(store.loading).toBe(false)
    expect(store.pendingToolCall).toBeNull()
  }, 20_000)
})

describe('P-05（P0）：HITL 挂起中停止 —— 不锁死、点卡不发请求、下一条能发', () => {
  it('停止后卡只读、confirm 不发请求、send 正常走网络', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [
      textMessage('u-1', 'user', '发布这个任务'),
      {
        ...textMessage('a-1', 'assistant', '需要你确认'),
        parts: [
          { kind: 'text', seg: 0, text: '需要你确认', streaming: false },
          toolPart('gate-1', 1, { name: 'start_publish', kind2: 'CONFIRM', status: 'pending', requiresAction: true })
        ]
      }
    ]
    store.suspended = true
    store.activeRunId = 'run-5'
    store.loading = false

    await store.stop()

    expect(store.suspended).toBe(false)
    expect(store.pendingToolCall).toBeNull() // 卡转只读（派生投影收掉）
    const card = store.messages[1]?.parts.find((part) => part.kind === 'tool')
    expect(card?.kind === 'tool' ? card.status : null).toBe('cancelled')

    // 点卡不再发请求：已取消的卡没有可提交的挂起调用 ⇒ confirm 静默返回
    await store.confirm(true)
    expect(api.confirm).not.toHaveBeenCalled()

    // 本会话能成功发出下一条
    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'done', runId: 'run-5b', seq: 1, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })
    const next = await store.send('那先别发布')
    expect(next.outcome).toBe('ok')
    expect(api.chat).toHaveBeenCalledTimes(1)
  }, 20_000)
})

describe('P-06（P0）：GF 挂起中停止 —— 表单收起、卡显示已取消', () => {
  it('stop 后 activeForm 清空、表单段转 cancelled（不是「待填写」空壳）', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [
      textMessage('u-1', 'user', '筛一下'),
      {
        ...textMessage('a-1', 'assistant', ''),
        parts: [
          { kind: 'text', seg: 0, text: '', streaming: false },
          toolPart('gf-1', 1, {
            name: 'generative_form', kind2: 'FRONTEND', status: 'pending', requiresAction: true
          })
        ]
      }
    ]
    store.activeForm = {
      toolCallId: 'gf-1', runId: 'run-6', form: { scenario: 'FILTER', fields: [] } as never,
      status: 'filling', error: null, values: {}, fieldErrors: []
    }
    store.suspended = true
    store.activeRunId = 'run-6'

    await store.stop()

    expect(store.activeForm).toBeNull()
    const card = store.messages[1]?.parts.find((part) => part.kind === 'tool')
    expect(card?.kind === 'tool' ? card.status : null).toBe('cancelled')
    expect(card?.kind === 'tool' ? card.requiresAction : true).toBe(false)
  }, 20_000)
})

describe('P-07（P0）：停止后立刻发新消息 —— 全部发出、无幻影、被停正文保留', () => {
  it('停止后连续发 4 次（10/50/100/600ms 语义）：每次都真的发出，旧正文不被回滚', async () => {
    vi.useFakeTimers()
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('u-1', 'user', '第一问'), assistantWithTool('被停的半截', 'c1', 'running')]
    store.loading = true
    store.activeRunId = 'run-7'
    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'done', runId: 'run-7x', seq: 1, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })

    await store.stop()

    for (const delay of [10, 50, 100, 600]) {
      vi.advanceTimersByTime(delay)
      const result = await store.send(`追问-${delay}`)
      expect(result.outcome).toBe('ok')
    }

    expect(api.chat).toHaveBeenCalledTimes(4)
    // 无幻影：用户气泡数 = 1（首问）+ 4 次追问；被停正文仍在
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(5)
    expect(store.messages[1]?.content).toBe('被停的半截')
    expect(store.messages[1]?.stopped).toBe(true)
  }, 20_000)
})

describe('P-08（P2）：停止后同 tick 回车 —— 不得静默吞掉草稿', () => {
  /**
   * 判据：要么发出（200），要么显式「正在停止，请稍候」且草稿保留。批G 的落地形态是**前者**：
   * `stop()` 本地就近把 `loading` 置 false，`send()` 只拦 `loading` ⇒ 停止瞬间的发送**照常出去**，
   * 草稿不可能被吞（`send()` 不写输入框）。本条把这个口径锁死。
   */
  it('cancel 在途（stopping=true）时 send：请求照发，草稿语义不被 store 改写', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('u-1', 'user', '问'), assistantWithTool('半截', 'c1', 'running')]
    store.loading = true
    store.activeRunId = 'run-8'
    const gate: { release: (() => void) | null } = { release: null }
    api.cancel.mockImplementation(() => new Promise((resolve) => {
      gate.release = () => resolve({ cancelled: true, alreadyCancelled: false })
    }))
    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'done', runId: 'run-8b', seq: 1, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })

    const stopping = store.stop()
    expect(store.stopping).toBe(true)
    const result = await store.send('停在半路时敲的回车')
    expect(result.outcome).toBe('ok')
    expect(api.chat).toHaveBeenCalledTimes(1)

    gate.release?.()
    await stopping
    expect(store.stopping).toBe(false)
  }, 20_000)
})

describe('P-09（P2）：双击停止幂等 —— 只 1 次 cancel，alreadyCancelled 只消费不啰嗦', () => {
  it('同 tick 两次 stop：后端只收 1 次；alreadyCancelled 不追加任何提示', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('u-1', 'user', '问'), assistantWithTool('半截', 'c1', 'running')]
    store.loading = true
    store.activeRunId = 'run-9'
    api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: true })

    const [first, second] = await Promise.all([store.stop(), store.stop()])

    expect(first).toBeUndefined()
    expect(second).toBeUndefined()
    expect(api.cancel).toHaveBeenCalledTimes(1) // 第 2 次被本地拦截
    expect(store.notice).toBeNull() // "同一件事不说两遍"
    expect(store.lastRoundOutcome).toBe('cancelled')
    expect(store.stopping).toBe(false)
  }, 20_000)
})

describe('P-11（P0）：停止后迟到帧不写 —— 屏上帧数不增、卡状态不回退、镜像不写', () => {
  /**
   * 【缺陷挂账 · 已修复（2026-10-22）】本用例原为 `it.fails`（红测先行），根因已修，现为常绿回归锁。
   *
   * <p>缺陷史（实测于 HEAD 0e8bc7c）：`stop()` 已把本轮助手消息 `done=true` 封口，于是
   * `handleFrame` 的宿主解析（`currentAssistantMessage()`：找最后一条 `!done` 的助手消息，
   * 找不到就 **append 一条新的**）会把迟到 `delta` 写进**一条全新的空气泡** —— 屏上多出一条
   * 「迟到正文」消息。实测证据：messages 由 2 条变 3 条。这正是 Q18「停止后 60ms 内还能新建
   * 工具卡/改写卡状态」在 store 层的同型面；`api/sse.ts` 的读循环当时只挡住了"中止后已到达的
   * 整块"，挡不住"停止之后仍被投递的帧"（例如另一条双通道连接、或 SSE 层已收进队列的那几帧）。
   *
   * <p>修复锚点：`frontend/src/stores/ai.ts#handleFrame` **入口处**的 P-11 终态闸门 ——
   * `this.stopped` 为真时非终态帧整帧丢弃，且闸门必须在宿主解析之前（否则空气泡已 append）。
   * 与 `frontend/src/api/sse.ts#consumeFrame` 的 S-12 终帧闸门同口径；`stopped` 在每个新连接
   * 建立处复位，故只作用于被停止的那一轮。
   */
  it('stop 之后投递 delta / tool_start：消息数、parts 与镜像都不动', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('u-1', 'user', '问'), assistantWithTool('半截', 'c1', 'running')]
    store.loading = true
    store.activeRunId = 'run-11'

    await store.stop()
    const countBefore = store.messages.length
    const partsBefore = JSON.stringify(store.messages[1]?.parts)
    const mirrorBefore = JSON.stringify(readMirrorFixture(SESSION))

    store.handleFrame(frame({ type: 'delta', runId: 'run-11', seq: 99, text: '迟到正文' }))
    store.handleFrame(frame({
      type: 'tool_start', runId: 'run-11', seq: 100, toolCallId: 'late', name: 'list_tasks', kind: 'BACKEND'
    }))

    expect(store.messages).toHaveLength(countBefore) // 屏上帧数不增（不得凭空多一条气泡）
    expect(JSON.stringify(store.messages[1]?.parts)).toBe(partsBefore) // 卡状态不回退
    expect(store.messages[1]?.content).toBe('半截')
    expect(JSON.stringify(readMirrorFixture(SESSION))).toBe(mirrorBefore) // 镜像不写
  }, 20_000)

  /**
   * 【常驻回归锁（2026-10-22 补）】上一条只锁**非终态**帧；本条锁同一缺陷族的**终帧分支**：
   * 停止后 `done(cancelled)` 到达时，宿主解析一度仍走 `currentAssistantMessage()` 的
   * "找不到 `!done` 的助手消息就 append" 分支 —— 屏上凭空多一条空消息（修复前实测
   * messages 2 → 3，一条 content 为空的 done 气泡）。
   *
   * <p>修复锚点：`frontend/src/stores/ai.ts#handleFrame` 的 **P-11 宿主解析**（`sealedRoundHost`）——
   * 停止轮只认已存在的助手消息（优先本轮宿主），一条都没有才整帧丢弃；终态帧本身仍放行，
   * 以便轮次级收口（`contextUsage` / `clearGateSubmissions`）照常执行。
   *
   * <p>断言面：消息数不变 ⇒ 不新建空气泡；原消息「已停止」标记与半截正文完好 ⇒ 终帧不得回退
   * 用户决策的事实；卡仍为 cancelled ⇒ 卡状态不回退。
   */
  it('stop 之后投递 done(cancelled)：不新建空气泡，原消息「已停止」标记完好', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    store.messages = [textMessage('u-1', 'user', '问'), assistantWithTool('半截', 'c1', 'running')]
    store.loading = true
    store.activeRunId = 'run-11b'

    await store.stop()
    const countBefore = store.messages.length
    const idsBefore = store.messages.map((item) => item.id)

    store.handleFrame(frame({ type: 'done', runId: 'run-11b', seq: 101, cancelled: true }))

    expect(store.messages).toHaveLength(countBefore) // 终帧也不得凭空多一条气泡
    expect(store.messages.map((item) => item.id)).toEqual(idsBefore) // 宿主身份不变
    expect(store.messages[1]?.stopped).toBe(true) // 「已停止」是消息级事实，终帧抹不掉
    expect(store.messages[1]?.content).toBe('半截') // 半截正文完好
    expect(store.messages[1]?.done).toBe(true)
    const card = store.messages[1]?.parts.find((part) => part.kind === 'tool')
    expect(card?.kind === 'tool' ? card.status : null).toBe('cancelled') // 卡状态不回退
  }, 20_000)
})

describe('P-12（P1）：停止后刷新 —— 执行中的卡显示「已取消」而不是消失', () => {
  /**
   * 判据：该卡**不消失**，显示「已取消」（防"回到更早的已完成态"这种隐性丢信息）。
   * 刷新后的权威载荷是后端历史（停止落库时条目已 `CANCELLED`，见后端 `cancelPendings`），
   * 前端按 `toolCallStates` 的词表单表读真态。
   */
  it('历史载荷带 CANCELLED 状态：卡仍在且为 cancelled（不回落成 succeeded）', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 3,
      messages: [
        { type: 'USER', text: '导出' },
        {
          type: 'ASSISTANT',
          text: '正在导出…',
          stopped: true,
          toolCalls: [{ id: 'h1', type: 'function', name: 'start_export', arguments: '{}' }],
          toolCallStates: { h1: { status: 'CANCELLED', kind: 'CONFIRM' } }
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'start_export', responseData: '已取消' }] }
      ]
    })

    await store.loadHistory()

    const assistant = store.messages[1]
    const card = assistant?.parts.find((part) => part.kind === 'tool' && part.toolCallId === 'h1')
    expect(card).toBeDefined() // 卡不消失
    expect(card?.kind === 'tool' ? card.status : null).toBe('cancelled')
    expect(assistant?.stopped).toBe(true)
    expect(assistant?.content).toBe('正在导出…')
  }, 20_000)
})
