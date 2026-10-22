/**
 * E2E 用例全集（第 53 件）· RLD 刷新恢复域：单测缺口补齐
 * （R-01 / R-02 / R-03 / R-04 / R-05 / R-07 / R-12 / R-13 / R-14 / R-18 + M-07）。
 *
 * <p>这一域在蓝本里 P0 最密（9 条），而「单测」列有 10 条是 ❌/⚠️ —— 原因不是"测不了"，而是
 * 既有 spec 只覆盖了**恢复路径的某个分支**（`restore-reconcile` 的二次对账、
 * `restore-request-emitted` 的建卡过滤、`rg-round-restore-stop` 的 G2/G3），
 * 没有按"用户看到的形态"（只剩用户气泡 / 问了没人答 / 卡消失 / 输入谎报空闲）逐条锁。
 *
 * <p>本文件的用例都走**真实的刷新入口** `loadHistory()`（= 面板挂载后第一件事），
 * 用 `sessionStorage` 镜像 + `/runs/current` 快照 + 桩帧三件套还原刷新现场。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
  frame,
  freshStore,
  readMirrorFixture,
  textMessage,
  toolPart,
  writeMirrorFixture, nextSession, freshStoreWithHttp
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

function snapshot(overrides: Record<string, unknown> = {}) {
  return {
    found: true,
    runId: 'run-r',
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 12,
    ...overrides
  }
}

/** 一条"一问一答 + 一张工具卡"的历史（第 1 轮）。 */
function roundOneHistory() {
  return [
    { type: 'USER', text: '统计一下行数' },
    {
      type: 'ASSISTANT',
      text: '我先看下定义。',
      toolCalls: [{ id: 'h1', type: 'function', name: 'list_config_defs', arguments: '{}' }]
    },
    { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'list_config_defs', responseData: '12 条' }] }
  ]
}

/** 接管不返回（模拟"长连接挂着"），用于断言 loading / 停止入口等轮次级状态。 */
function hangReattach(): void {
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
}

beforeEach(() => {
  sessionStorage.clear()
  SESSION = nextSession('s-refresh')
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

describe('R-01（P0）：在途轮刷新不得"只剩用户气泡且无任何提示"', () => {
  /**
   * 行为锁（批G · G2 落地后的形态：静默续收，不弹横幅）。Q1 的实证症状就是这个**组合**：
   * 只有用户气泡 + 输入框谎报空闲。三条一起断言，任一条不成立即复现症状。
   */
  it('历史只有 USER + 快照 RUNNING：补出助手宿主、loading 为真、零提示', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '这个任务怎么导出' }]
    })
    api.runsCurrent.mockResolvedValue(snapshot())
    hangReattach()

    await store.loadHistory()

    expect(store.messages.map((message) => message.role)).toEqual(['user', 'assistant'])
    expect(store.loading).toBe(true) // 输入框不得"谎报空闲"
    expect(store.notice).toBeNull() // 静默续收：无占位文案/横幅
    expect(JSON.stringify(store.messages)).not.toContain('正在重挂')
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 0 }) // 全量重放
  }, 20_000)
})

describe('R-02（P0）：老会话第 2 轮流式刷新 —— 第 1 轮必须完好', () => {
  /**
   * 判据（蓝本原文）：第 1 轮完好（含卡）；第 2 轮**不出现「问了没人答」**。
   *
   * <p>本用例锁的是 Q1 在"多轮会话"上的形态：宿主选取必须落在**本轮**（最后一条用户消息之后），
   * 不许把第 1 轮已完成的回答当成宿主而清空其段（那等于把上一轮答案吃掉）。
   *
   * <p>【红测先行记录】本条在 `0e8bc7c` 上是**红的**（实证：第 1 轮 assistant 的 `content`
   * 被清成 `''`，因为 `restoreInFlightRound` 用 `find(assistant && !done) ?? find(assistant)`
   * 选宿主，历史归一后全是 `done=true` ⇒ 选中上一轮）。同批并行施工方已在 `stores/ai.ts` 落地
   * `findInFlightHost()`（只认最后一条用户消息之后的未收尾助手，否则新建宿主）⇒ 本条转绿，
   * 现作为该修复的回归锁保留。
   */
  it('第 1 轮的正文与工具卡保留；第 2 轮进入续收', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 3, messages: roundOneHistory() })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-2',
      runSeq: { 'run-2': 4 },
      messages: [
        textMessage('u-2', 'user', '再按区域分一下'),
        textMessage('a-2', 'assistant', '正在分…', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-2' }))
    hangReattach()

    await store.loadHistory()

    const first = store.messages[1]
    expect(first?.role).toBe('assistant')
    expect(first?.content).toBe('我先看下定义。')
    expect(first?.parts.some((part) => part.kind === 'tool' && part.toolCallId === 'h1')).toBe(true)
    // 第 2 轮：不出现「问了没人答」（末尾存在助手宿主 + 在续收）
    expect(store.messages.filter((message) => message.role === 'user').length).toBeGreaterThanOrEqual(1)
    expect(store.messages.at(-1)?.role).toBe('assistant')
    expect(store.messages.at(-1)?.id).not.toBe(first?.id) // 宿主不得复用第 1 轮那条
    expect(store.loading).toBe(true)
  }, 20_000)
})

describe('R-03（P0）：工具执行中途刷新 —— 卡与说明文字续显且收敛为终态', () => {
  /**
   * 判据：卡片与前置说明文字续显；卡状态收敛为终态或「执行中（已接管）」；不得全部消失。
   * 走"镜像里 running 卡 + 回放把结局帧补回来"的真实形态（B1 挂起即断后卡只能靠归档补）。
   */
  it('回放 delta + tool_start + tool_result 后：说明文字与卡都在，卡不再是 running', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '导出这个任务的清单' }]
    })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-3',
      runSeq: { 'run-3': 5 },
      messages: [
        textMessage('u-3', 'user', '导出这个任务的清单'),
        {
          ...textMessage('a-3', 'assistant', '我先跑一次导出。', { streaming: true, done: false }),
          parts: [
            { kind: 'text', seg: 0, text: '我先跑一次导出。', streaming: false },
            toolPart('c3', 1, { name: 'start_export', kind2: 'CONFIRM', status: 'running' })
          ]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-3' }))
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'delta', runId: 'run-3', seq: 4, text: '我先跑一次导出。' }))
      handlers.onFrame(frame({
        type: 'tool_start', runId: 'run-3', seq: 5, toolCallId: 'c3', name: 'start_export', kind: 'CONFIRM'
      }))
      handlers.onFrame(frame({
        type: 'tool_result', runId: 'run-3', seq: 6, toolCallId: 'c3', name: 'start_export', ok: true, result: '作业 9'
      }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-3', seq: 7, cancelled: false }))
      return new Promise(() => {})
    })

    await store.loadHistory()

    const assistant = store.messages.at(-1)
    expect(assistant?.content).toBe('我先跑一次导出。') // 说明文字不丢
    const card = assistant?.parts.find((part) => part.kind === 'tool' && part.toolCallId === 'c3')
    expect(card).toBeDefined() // 卡不消失
    expect(card?.kind === 'tool' ? card.status : null).not.toBe('running') // 收敛为终态
    expect(store.suspended).toBe(false)
  }, 20_000)
})

describe('R-04（P0）：刷新后立刻发新消息（409）不得留幻影', () => {
  /**
   * 判据：① 用户气泡不新增；② 无「（本轮未开始…）」气泡；③ 同义提示只 1 条。
   * （③ 在 store 形态下 = `notice` 是**单个字符串**且只此一条，不叠第二处技术文案。）
   */
  it('409 SESSION_BUSY：气泡数回到发送前，提示唯一且不含实现词', async () => {
    const { store, ApiError } = await freshStoreWithHttp()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '上一句' }]
    })
    await store.loadHistory()
    const before = store.messages.length

    api.chat.mockRejectedValue(new ApiError(
      '该会话已有一轮对话在进行中', 409, 'SESSION_BUSY', {
        code: 'SESSION_BUSY', runId: 'run-busy', reattach: '/api/ai/events/run-busy'
      }
    ))
    const result = await store.send('刷新后立刻再问一句')

    expect(result.outcome).toBe('error')
    expect(store.messages).toHaveLength(before) // ① 用户气泡不新增
    expect(JSON.stringify(store.messages)).not.toContain('本轮未开始') // ② 无假助手气泡
    expect(store.notice).toBe('这条消息没有发出去，请稍后再试。') // ③ 提示唯一
    expect(store.notice).not.toContain('run-busy') // 不透出实现概念
    expect(store.loading).toBe(false)
  }, 20_000)
})

describe('R-05（P1）：静默接管回到原气泡、无占位前缀、帧数一致', () => {
  /**
   * 判据：① 不新建气泡（气泡数不变）；② 首帧后正文**不含**「正在重挂」前缀 ——
   * 这里按更强的口径锁"正文逐字等于回放内容"（镜像里的半截被重放覆盖，不双拼）；
   * ③ 回放内容与归档一致（帧数相等）。
   *
   * <p>走**真实刷新入口**（`loadHistory`），因为 `reattachActive` 单独调用不会清空宿主段 ——
   * 清空是 `restoreInFlightRound` 的职责（这也是"只重放不双拼"的唯一正确形态）。
   */
  it('刷新接管：气泡数不变、正文逐字等于回放、投递帧数一致', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '问' }] })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-5',
      messages: [
        textMessage('u-5', 'user', '问'),
        textMessage('a-5', 'assistant', '半截', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-5' }))
    const replayed = [
      frame({ type: 'delta', runId: 'run-5', seq: 1, text: '完整' }),
      frame({ type: 'delta', runId: 'run-5', seq: 2, text: '答案' }),
      frame({ type: 'message_end', runId: 'run-5', seq: 3, chars: 4 })
    ]
    const delivered: unknown[] = []
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      for (const incoming of replayed) {
        delivered.push(incoming)
        handlers.onFrame(incoming)
      }
      return new Promise(() => {})
    })

    await store.loadHistory()

    expect(store.messages).toHaveLength(2) // ① 不新建气泡
    expect(store.messages[1]?.role).toBe('assistant')
    expect(store.messages[1]?.content).toBe('完整答案') // ② 不双拼、无前缀
    expect(store.messages[1]?.content).not.toContain('重挂')
    expect(delivered).toHaveLength(replayed.length) // ③ 帧数一致
  }, 20_000)
})

describe('R-07（P0）：批准后工具执行中刷新（RUNNING 静默失联）', () => {
  /**
   * 判据：① 不出现"什么都不恢复 + 输入框谎报空闲"；② 状态有变化（续收或终态）。
   * 现场 = 快照 RUNNING + 一条已批准（APPROVED）条目 + `awaitingExternal=false`。
   */
  it('快照 RUNNING + 已批准条目：进入续收（loading 真、挂流一次性建立）', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '批准后就跑' }] })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-7',
      pendingEntries: [{ toolCallId: 'c7', entryStatus: 'APPROVED', kind: 'CONFIRM', requestEmitted: true }]
    }))
    hangReattach()

    await store.loadHistory()

    expect(store.loading).toBe(true) // ① 不谎报空闲
    expect(store.suspended).toBe(false) // 已批准 ⇒ 不是挂起等待
    expect(api.reattachRun).toHaveBeenCalledTimes(1) // ② 有状态变化（挂了流）
    expect(store.activeRunId).toBe('run-7')
  }, 20_000)
})

describe('R-12（P0）：刷新后存在在途轮 ⇒ 停止入口可用', () => {
  /**
   * 判据：钮 visible（= 面板的 `ai.loading || ai.suspended` 判据为真）；点击后后端收到 cancel；
   * 输入框随即可用。这里锁 store 侧三条前提（钮的 visible 判据就是它们）。
   */
  it('刷新恢复后 loading 为真 ⇒ stop() 发出 runId 取消并解禁输入', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '在途' }] })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-12' }))
    hangReattach()
    await store.loadHistory()

    expect(store.loading || store.suspended).toBe(true) // 停止钮 visible 的判据
    expect(store.activeRunId).toBe('run-12')

    await store.stop()

    expect(api.cancel).toHaveBeenCalledWith('run-12')
    expect(store.loading).toBe(false) // 输入框随即可用
    expect(store.suspended).toBe(false)
    expect(store.stopping).toBe(false)
  }, 20_000)
})

describe('R-13（P0）：历史 + 镜像合并（不整替、不重复气泡）', () => {
  /**
   * 判据：历史里没有的那条在途助手消息**仍存在**（气泡数 = 历史 + 1）；无重复气泡。
   * 与 G3 的差别：这里历史里**已经有第 2 轮的用户消息**（真实形态：开轮即写入记忆），
   * 因此同时锁"用户气泡不重复"这一半。
   */
  it('历史 3 条（含第 2 轮 USER）+ 镜像在途助手 ⇒ 4 条、用户气泡各一份', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 3,
      messages: [
        { type: 'USER', text: '第一问' },
        { type: 'ASSISTANT', text: '第一答' },
        { type: 'USER', text: '第二问' }
      ]
    })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-13',
      messages: [
        textMessage('u-13', 'user', '第二问'),
        textMessage('a-13', 'assistant', '正在答…', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-13' }))
    hangReattach()

    await store.loadHistory()

    expect(store.messages).toHaveLength(4) // 历史 3 + 在途助手 1
    const userTexts = store.messages.filter((message) => message.role === 'user').map((message) => message.content)
    expect(userTexts).toEqual(['第一问', '第二问']) // 无重复气泡
    expect(store.messages.at(-1)?.id).toBe('a-13') // 在途那条仍在尾部
  }, 20_000)
})

describe('R-14（P1）：镜像落盘时机（切后台 + 全文比例 + lastRunId/runSeq）', () => {
  /**
   * 判据：镜像助手段 `text` 长度 ≥ 屏上正文的 80%；`lastRunId` 与 `runSeq` 均已写入。
   * G4 已锁 pagehide + runSeq；本条补 **visibilitychange(hidden)** 与 **lastRunId / 正文比例**。
   */
  it('visibilitychange=hidden 立即落盘：正文比例 ≥80%、lastRunId 与 runSeq 齐', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(3_000_000_000)
    const store = await freshStore()
    store.sessionId = SESSION
    store.startMirror()
    store.activeRunId = 'run-14'
    store.runSeq = { 'run-14': 42 }
    store.loading = true
    const onScreen = '第一段正文'.repeat(40) // 200 字
    store.messages = [textMessage('u-14', 'user', '问'), textMessage('a-14', 'assistant', onScreen, {
      streaming: true, done: false
    })]
    // 窗口内再挤进一帧（节流期内）：镜像此刻应还是旧快照
    vi.setSystemTime(3_000_000_100)
    store.messages[1].content = `${onScreen}追加`
    await Promise.resolve()

    const hidden = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState')
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    if (hidden) {
      Object.defineProperty(document, 'visibilityState', hidden)
    } else {
      delete (document as unknown as { visibilityState?: string }).visibilityState
    }

    const mirror = readMirrorFixture(SESSION)
    const saved = mirror?.messages?.[1]?.content ?? ''
    expect(saved.length).toBeGreaterThanOrEqual(Math.floor(store.messages[1]!.content.length * 0.8))
    expect(mirror?.lastRunId).toBe('run-14')
    expect(mirror?.runSeq).toMatchObject({ 'run-14': 42 })
    expect(mirror?.inFlight).toBe(true)
  }, 20_000)
})

describe('R-18（P2）：续收期间不得出现"当前会话正在进行中"这类横幅', () => {
  /**
   * 判据：横幅在 onopen 后即消失、续收期间不再出现。批G 的裁决是**全程不出现**（静默续收），
   * 因此逐帧断言 `notice` 恒为 null —— 比"消失"更强（只升不降）。
   */
  it('回放帧逐条到达的过程中 notice 始终为 null，消息里无实现词', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '在途' }] })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-18' }))
    const notices: Array<string | null> = []
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'start', runId: 'run-18', seq: 1 }))
      notices.push(store.notice)
      handlers.onFrame(frame({ type: 'delta', runId: 'run-18', seq: 2, text: '继续写' }))
      notices.push(store.notice)
      handlers.onFrame(frame({ type: 'done', runId: 'run-18', seq: 3, cancelled: false }))
      notices.push(store.notice)
      return new Promise(() => {})
    })

    await store.loadHistory()

    expect(notices).toEqual([null, null, null])
    expect(JSON.stringify(store.messages)).not.toContain('正在进行中')
    expect(store.messages.at(-1)?.content).toBe('继续写')
  }, 20_000)
})

describe('M-07（P2）：多页签同会话的降级（复制页签不得与原页签抢同一轮）', () => {
  /**
   * 判据：另一页签通过"操作/刷新"同步（不指望实时）；**不出现两页签各跑一轮**。
   * 客户端形态 = RC-4 的复制品判据：`adoptFreshSession()` 就地换新会话（旧 id 的镜像/记忆不继承），
   * 之后本页签的请求只带新 id。
   */
  it('复制页签换新会话：旧镜像清掉、新请求带新 sessionId（不与原页签同轮）', async () => {
    const store = await freshStore()
    store.sessionId = 's-original'
    sessionStorage.setItem('ai-session-id', 's-original')
    writeMirrorFixture('s-original', { messages: [textMessage('u', 'user', '原页签的问')] })
    store.messages = [textMessage('u', 'user', '原页签的问')]

    const next = store.adoptFreshSession()

    expect(next).not.toBe('s-original')
    expect(store.sessionId).toBe(next)
    expect(store.messages).toHaveLength(0)
    expect(readMirrorFixture('s-original')).toBeNull() // 旧会话镜像不继承
    expect(sessionStorage.getItem('ai-session-id')).toBe(next)

    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'done', runId: 'run-m7', seq: 1, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })
    await store.send('新页签自己的问题')
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: next })
  }, 20_000)
})
