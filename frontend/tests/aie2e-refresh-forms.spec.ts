/**
 * 测试扩充 V2 · RLD 刷新恢复域 / STP 停止域的「真形态」下沉（A2~A8 + B11 + 三件套补充）。
 *
 * <h2>与既有文件的分工</h2>
 * <ul>
 * <li>`aie2e-refresh.spec.ts`：R-01…R-18 的**首轮覆盖**（多数只取镜像或快照之一）；</li>
 * <li>`rg-round-restore-stop.spec.ts`：批G 的 G2/G3/G4/G6/S1-S4 回归锁；</li>
 * <li>本文件：缺口对账里 A2~A8、B11 的**真刷新形态**——每条都是「全新 store + `/history` +
 *   `/runs/current` 快照（+ 可选镜像 + 桩帧）」四件套里取三件以上，而**不是**同 store 调
 *   `loadHistory()` 复用既有实例；另补 7 条「镜像 + 快照 + 帧」三件套对账（既有条目缺的那一件）。</li>
 * </ul>
 *
 * <h2>为什么「全新 store」才算刷新</h2>
 * jsdom 里没有页面重载：`store.loadHistory()` 在**同一实例**上重跑只能证明"再对一次账"，
 * 证明不了"内存态全丢之后能否从持久面重建"。故每条用例的现场都先落三处持久面
 * （`sessionStorage` 镜像 / 后端历史 / 快照），再用 `freshStore()` 起一个全新模块图（= 重载后的
 * 内存态），唯一允许的恢复入口是 `loadHistory()`。
 *
 * <h2>断言口径</h2>
 * 断言一律钉在「用户看到什么」：气泡数、段序、卡的终态、输入框可用性、提示条是否存在。
 * 不锁实现细节（不锁函数名、不锁内部调用次数以外的中间量）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import type { ChatMessage } from '@/types/ai'
import { contextPillText } from '@/utils/context-usage'
import { formDraftKey } from '@/utils/form-draft'
import {
  frame,
  freshStore,
  nextSession,
  readMirrorFixture,
  textMessage,
  toolPart,
  writeMirrorFixture
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
  deleteHistory: vi.fn(),
  getContext: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

interface FrameHandlers {
  onFrame: (incoming: unknown) => void
  onClose?: (info: { terminal: boolean; reason?: string; suspended?: boolean }) => void
}

type Store = Awaited<ReturnType<typeof freshStore>>

let SESSION = ''

/** 快照缺省（`found:false` = 后端没有在途轮）。 */
function emptySnapshot() {
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

function snapshot(overrides: Record<string, unknown> = {}) {
  return {
    found: true,
    runId: 'run-x',
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 12,
    ...overrides
  }
}

/** 上下文端点缺省：从未记账（`source:none` ⇒ 不渲染 pill）。 */
function noContext() {
  return {
    sessionId: SESSION,
    model: 'm',
    turnNo: 0,
    measuredAtMs: null,
    limitTokens: 128000,
    usedTokens: 0,
    percent: null,
    source: 'none',
    estimated: false,
    breakdown: [],
    compaction: { level: 'none', compactedTurns: 0, lostTokens: 0, lastAtMs: null, reason: null, circuit: 'closed', fuseFails: 0 }
  }
}

/** 一条助手消息里的工具段（按 toolCallId 取）。 */
function toolSegments(message: ChatMessage | undefined) {
  return (message?.parts ?? []).filter(
    (part): part is Extract<ChatMessage['parts'][number], { kind: 'tool' }> => part.kind === 'tool'
  )
}

/** 当前可见的待决卡（`requiresAction && pending`）的 toolCallId。 */
function visiblePendingIds(store: Store): string[] {
  return store.messages
    .flatMap((message) => toolSegments(message))
    .filter((part) => part.requiresAction && part.status === 'pending')
    .map((part) => part.toolCallId)
}

/** 挂起连接（长连接挂着）：用于断言轮次级状态不被"连接自然收尾"改写。 */
function hangReattach(): void {
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
}

/**
 * 全新 store + 显式清掉**界面侧**残留（`freshStore()` 只归轮次级字段）。
 *
 * <p>为什么需要这一层：`vi.resetModules()` 之后若解析到复用的模块图，`useAiStore()` 可能落回上一条
 * 用例的实例（全量并行时实测，单跑不现）—— 那会让"刷新后的内存态"带着上一例的 `activeForm` /
 * `stalePreviewToolCallId` 等，把断言指向错误的现场。刷新语义本来就是"界面侧全丢"，故这里逐项归零。
 */
async function freshStoreClean(): Promise<Store> {
  const store = await freshStore()
  // 模块级"在途轮"（`currentAbort`）不随 pinia 复位：上一条用例的挂起重连（`reattachRun` 桩永不
  // 结算）会把它带进本例 —— `loadHistory()` 见 `currentAbort` 非空就判"本端已有在途轮"而**跳过
  // 镜像**（夜间全量连跑必现、单跑不现）。会话级复位把在途轮一并清掉（等价于重载后的内存态），
  // 随后把 `freshStore()` 的"未读历史"口径还原（复位把它置成了已读）。
  store.adoptFreshSession()
  store.historyLoaded = false
  store.activeForm = null
  store.activePreview = null
  store.stalePreviewToolCallId = null
  store.activeUploadHandoff = null
  store.contextUsage = null
  store.contextBreakdown = null
  store.contextCompaction = null
  store.gateSubmissions = {}
  store.gateAcks = {}
  return store
}

/* ── 夹具：五张已执行卡 + 一张待决确认卡（A2 的"同轮上下文保全"现场） ───────── */

function executedCalls() {
  return Array.from({ length: 5 }, (_, index) => ({
    id: `x${index + 1}`,
    type: 'function',
    name: 'list_config_defs',
    arguments: '{}'
  }))
}

function fiveCardsHistory() {
  const calls = executedCalls()
  const states: Record<string, unknown> = {}
  for (const call of calls) {
    states[call.id] = { status: 'EXECUTED', kind: 'BACKEND' }
  }
  return [
    { type: 'USER', text: '查完这五项就发布' },
    { type: 'ASSISTANT', text: '我先把这几项查完了。', toolCalls: calls, toolCallStates: states },
    {
      type: 'TOOL',
      text: '',
      toolResponses: calls.map((call) => ({ id: call.id, name: call.name, responseData: '12 条' }))
    }
  ]
}

/** 五张已执行卡（succeeded）的镜像形态。 */
function fiveCardsMirrorHost(): ChatMessage {
  return {
    ...textMessage('a-2', 'assistant', '我先把这几项查完了。', { streaming: true, done: false }),
    parts: [
      { kind: 'text', seg: 0, text: '我先把这几项查完了。', streaming: false },
      ...Array.from({ length: 5 }, (_, index) => toolPart(`x${index + 1}`, index + 1, {
        name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', result: '12 条'
      }))
    ]
  }
}

/** 待决确认条目（快照形态；`req:true` = 请求帧真的发过）。 */
function pendingConfirm(toolCallId = 'c6', extra: Record<string, unknown> = {}) {
  return {
    toolCallId,
    entryStatus: 'PENDING',
    kind: 'CONFIRM',
    name: 'start_export',
    arguments: '{"taskId":1}',
    req: true,
    ...extra
  }
}

/* ── GF 夹具（A3/A4）：三字段 = text / enum / boolean，默认值全部与草稿不同 ───── */

const FORM_SPEC = {
  scenario: 'FILTER',
  title: '筛选导出',
  fields: [
    { key: 'currency', label: '币种', type: 'text', required: true, defaultValue: 'USD' },
    {
      key: 'region', label: '区域', type: 'enum', required: true, defaultValue: 'CN',
      options: [{ value: 'CN', label: '中国' }, { value: 'US', label: '美国' }]
    },
    { key: 'includeArchived', label: '含归档', type: 'boolean', defaultValue: false }
  ]
}

const DRAFT_VALUES = { currency: 'CURRENCY', region: 'US', includeArchived: true }

function gfEntry(toolCallId = 'gf-1', form: unknown = FORM_SPEC, extra: Record<string, unknown> = {}) {
  return {
    toolCallId,
    entryStatus: 'PENDING',
    kind: 'FRONTEND',
    name: 'generative_form',
    arguments: JSON.stringify({ form }),
    req: true,
    ...extra
  }
}

function gfHistory(toolCallId = 'gf-1', text = '请填写筛选条件。') {
  return [
    { type: 'USER', text: '按币种筛一下' },
    {
      type: 'ASSISTANT',
      text,
      toolCalls: [{ id: toolCallId, type: 'function', name: 'generative_form', arguments: '{}' }],
      toolCallStates: { [toolCallId]: { status: 'PENDING', kind: 'FRONTEND' } }
    }
  ]
}

/** 表格写工具（A6）：预览卡在本地 plan 丢失后必须收口成"预览已失效"。 */
const SHEET_ARGS = JSON.stringify({
  defCode: 'DEMO',
  scope: 'sheet',
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用'
})

beforeEach(() => {
  sessionStorage.clear()
  document.body.innerHTML = ''
  SESSION = nextSession('s-refresh-forms')
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: SESSION, count: 0, messages: [] })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.confirm.mockResolvedValue({})
  api.frontendToolResult.mockResolvedValue({ accepted: true })
  api.runsCurrent.mockResolvedValue(emptySnapshot())
  api.getContext.mockResolvedValue(noContext())
  hangReattach()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A2（R-06）· HITL 挂起中刷新：同轮上下文保全
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A2（R-06 · P0）：HITL 挂起中刷新的三部分形态', () => {
  /**
   * 判据：同轮 assistant 消息 = 说明文字 + 5 张已执行卡 + 待决卡；卡可批准；批准后本轮跑完。
   * 三处来源各司其职：历史给已执行卡与正文、快照给待决条目、镜像给在途段（正文滞后的那半）。
   */
  it('刷新后：一条助手消息同时持有正文、5 张已执行卡与唯一待决卡（不新起气泡）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 3, messages: fiveCardsHistory() })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-a2',
      runSeq: { 'run-a2': 12 },
      messages: [textMessage('u-2', 'user', '查完这五项就发布'), fiveCardsMirrorHost()]
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a2',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      unresolvedExternal: ['c6'],
      pendingEntries: [pendingConfirm('c6', { preflight: { target: '任务 1', impact: '3 项', reportHash: 'h-1' } })]
    }))

    await store.loadHistory()

    // ①② 历史 + 镜像合并不产双份：1 用户 + 1 助手
    expect(store.messages.map((message) => message.role)).toEqual(['user', 'assistant'])
    const host = store.messages[1]
    expect(host?.content).toBe('我先把这几项查完了。') // 说明文字不丢
    const cards = toolSegments(host)
    expect(cards).toHaveLength(6) // 5 张已执行 + 1 张待决，全部落在同一宿主
    expect(cards.slice(0, 5).every((part) => part.status === 'succeeded')).toBe(true)
    expect(cards.at(-1)).toMatchObject({ toolCallId: 'c6', status: 'pending', requiresAction: true })
    expect(cards.at(-1)?.preflight).toMatchObject({ reportHash: 'h-1' }) // 快照预检摘要原样落段
    // ③ 段序：正文在前，卡按历史顺序
    expect(host?.parts[0]?.kind).toBe('text')
    expect(cards.map((part) => part.toolCallId)).toEqual(['x1', 'x2', 'x3', 'x4', 'x5', 'c6'])

    expect(visiblePendingIds(store)).toEqual(['c6'])
    expect(store.suspended).toBe(true) // 输入禁用语义
    expect(store.loading).toBe(true) // 不得"谎报空闲"
    expect(store.canSend).toBe(false)
    expect(store.activeRunId).toBe('run-a2')
    expect(store.notice).toBeNull()
    // 挂起卡走差量续收（不从 0 全量重放：挂起轮已收下的帧不必再来一遍）
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 12 })
  }, 20_000)

  it('刷新后批准：决策带 runId/toolCallId 送达，后续帧到达即本轮跑完（卡转终态、挂起收口）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 3, messages: fiveCardsHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a2b',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      pendingEntries: [pendingConfirm('c6')]
    }))
    let continued: FrameHandlers | null = null
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      if (continued === null) {
        continued = handlers // 第 1 次 = 快照重建后的挂起轮续收（本用例让它挂着）
        return new Promise(() => {})
      }
      handlers.onFrame(frame({
        type: 'confirm_decision', runId: 'run-a2b', seq: 13, toolCallId: 'c6',
        name: 'start_export', decision: 'APPROVED', approved: true, status: 'APPROVED'
      }))
      handlers.onFrame(frame({
        type: 'tool_result', runId: 'run-a2b', seq: 14, toolCallId: 'c6', name: 'start_export',
        kind: 'CONFIRM', ok: true, executed: true, status: 'EXECUTED', result: '作业 42'
      }))
      handlers.onFrame(frame({ type: 'delta', runId: 'run-a2b', seq: 15, text: '已发布。' }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-a2b', seq: 16, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })

    await store.loadHistory()
    expect(visiblePendingIds(store)).toEqual(['c6'])

    await store.confirm(true, undefined, 'c6')

    expect(api.confirm).toHaveBeenCalledWith({
      runId: 'run-a2b', toolCallId: 'c6', approved: true, reason: null
    })
    const host = store.messages[1]
    const card = toolSegments(host).find((part) => part.toolCallId === 'c6')
    expect(card).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(host?.content).toBe('我先把这几项查完了。已发布。') // 续写落在原气泡
    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.messages).toHaveLength(2) // 不新建气泡
  }, 20_000)

  it('镜像 + 快照 + 帧三件套：确认帧重放（语义重投 / 同序号重投）不产生第二张卡', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 3, messages: fiveCardsHistory() })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-a2c',
      messages: [textMessage('u-2', 'user', '查完这五项就发布'), fiveCardsMirrorHost()]
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a2c',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      pendingEntries: [pendingConfirm('c6')]
    }))
    // 捕获句柄放在对象属性上：`let` 变量在闭包里赋值时 TS 会把它窄成 null（句柄用不了）
    const live: { handlers: FrameHandlers | null } = { handlers: null }
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      live.handlers = handlers
      return new Promise(() => {})
    })

    await store.loadHistory()
    const replay = {
      type: 'confirm_request', runId: 'run-a2c', seq: 13, toolCallId: 'c6',
      name: 'start_export', args: '{"taskId":1}'
    }
    live.handlers?.onFrame(frame(replay))
    live.handlers?.onFrame(frame(replay)) // 同序号重投（seq 幂等）
    live.handlers?.onFrame(frame({ ...replay, seq: 14 })) // 语义重投（新序号、同 toolCallId）

    const cards = toolSegments(store.messages[1])
    expect(cards.filter((part) => part.toolCallId === 'c6')).toHaveLength(1)
    expect(visiblePendingIds(store)).toEqual(['c6'])
    expect(store.messages).toHaveLength(2)
    // 三处来源对账：镜像 5 张 + 历史 5 张 + 快照 1 张 ⇒ 段身份唯一
    const ids = cards.map((part) => part.toolCallId)
    expect(new Set(ids).size).toBe(ids.length)
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A3（R-09）· 表单已填一半刷新：逐值相等（含 boolean=true 非默认）
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A3（R-09 · P0）：表单半填刷新后逐值相等', () => {
  it('三字段逐值相等：text / enum / boolean=true（不是 schema 默认的 false）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: gfHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a3', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [gfEntry('gf-1')]
    }))
    sessionStorage.setItem(formDraftKey('gf-1'), JSON.stringify(DRAFT_VALUES))
    // 捕获句柄放在对象属性上：`let` 变量在闭包里赋值时 TS 会把它窄成 null（句柄用不了）
    const live: { handlers: FrameHandlers | null } = { handlers: null }
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      live.handlers = handlers
      return new Promise(() => {})
    })

    await store.loadHistory()

    const form = store.activeForm
    expect(form?.toolCallId).toBe('gf-1')
    expect(form?.status).toBe('filling')
    expect(form?.values).toEqual(DRAFT_VALUES)
    expect(form?.values.includeArchived).toBe(true)
    expect(typeof form?.values.includeArchived).toBe('boolean')
    expect(visiblePendingIds(store)).toEqual(['gf-1'])
    expect(store.suspended).toBe(true)

    // 帧腿：同一张表的请求帧重放（他端/重连重复投递）⇒ 不建第二张卡、草稿值不被清
    const replay = {
      type: 'frontend_tool_request', runId: 'run-a3', seq: 9, toolCallId: 'gf-1',
      name: 'generative_form', args: JSON.stringify({ form: FORM_SPEC })
    }
    live.handlers?.onFrame(frame(replay))
    live.handlers?.onFrame(frame({ ...replay, seq: 10 }))
    expect(toolSegments(store.messages[1]).filter((part) => part.toolCallId === 'gf-1')).toHaveLength(1)
    expect(store.activeForm?.values).toEqual(DRAFT_VALUES)
  }, 20_000)

  it('草稿按 toolCallId 隔离且优先于 schema 默认值（默认不覆盖用户已填）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: gfHistory('gf-1') })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a3b', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [gfEntry('gf-1')]
    }))
    sessionStorage.setItem(formDraftKey('gf-1'), JSON.stringify(DRAFT_VALUES))
    sessionStorage.setItem(formDraftKey('gf-OTHER'), JSON.stringify({ currency: 'OTHER' }))

    await store.loadHistory()

    const values = store.activeForm?.values ?? {}
    const defaults = new Map<string, unknown>(
      (store.activeForm?.form.fields ?? []).map((field) => [field.key, field.defaultValue])
    )
    // 三个字段的每个值都"不是默认值" —— 这是"草稿优先于默认"的可判定形态
    for (const key of Object.keys(DRAFT_VALUES)) {
      expect(values[key]).not.toEqual(defaults.get(key))
    }
    expect(values.currency).toBe('CURRENCY') // 默认 USD 被草稿覆盖
    expect(values.region).toBe('US') // 默认 CN 被草稿覆盖
    expect(values.includeArchived).toBe(true) // 默认 false 被草稿覆盖
    // 别的 toolCallId 的草稿不串味
    expect(JSON.stringify(values)).not.toContain('OTHER')
  }, 20_000)

  it('草稿不越界：刷新只回填本机镜像，不回灌、不写镜像、不进请求体', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: gfHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a3c', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [gfEntry('gf-1')]
    }))
    sessionStorage.setItem(formDraftKey('gf-1'), JSON.stringify(DRAFT_VALUES))

    await store.loadHistory()

    expect(store.activeForm?.values).toEqual(DRAFT_VALUES)
    expect(api.frontendToolResult).not.toHaveBeenCalled() // 未提交 ⇒ 零回灌
    expect(api.chat).not.toHaveBeenCalled()
    const mirror = JSON.stringify(readMirrorFixture(SESSION) ?? {})
    expect(mirror).not.toContain('CURRENCY') // 草稿永不入镜像/历史
    expect(JSON.stringify(store.messages)).not.toContain('CURRENCY')
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A4（R-08）· GF 挂起中刷新：结构重建 + 提交后续跑
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A4（R-08 · P1）：GF 挂起中刷新的结构重建与提交后续跑', () => {
  it('结构完整重建：标题/字段/待填写态齐，且不武装任何倒计时（快照带 expiresAt 也不折算）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: gfHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a4',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      pendingEntries: [gfEntry('gf-1', FORM_SPEC, { expiresAt: Date.now() + 60_000, atMs: Date.now() })]
    }))

    await store.loadHistory()

    const form = store.activeForm
    expect(form).not.toBeNull()
    expect(form?.form.title).toBe('筛选导出')
    expect(form?.form.fields.map((field) => [field.key, field.label, field.type])).toEqual([
      ['currency', '币种', 'text'],
      ['region', '区域', 'enum'],
      ['includeArchived', '含归档', 'boolean']
    ])
    expect(form?.form.fields[1]?.options?.map((option) => option.value)).toEqual(['CN', 'US'])
    expect(form?.error).toBeNull()
    expect(form?.fieldErrors).toEqual([])
    // 结构上不存在任何"倒计时/到期"字段：去超时裁决后表单只认结局帧与用户取消
    expect(Object.keys(form ?? {}).sort()).toEqual([
      'error', 'fieldErrors', 'form', 'runId', 'status', 'toolCallId', 'values'
    ])
    expect(store.suspended).toBe(true)
    expect(store.canSend).toBe(false) // 输入禁用（与 CONFIRM 挂起同型）
    expect(visiblePendingIds(store)).toEqual(['gf-1'])
  }, 20_000)

  it('刷新后提交：回灌带 runId/toolCallId 与值 JSON，后续帧到达即本轮跑完、表单收起', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: gfHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a4b', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [gfEntry('gf-1')]
    }))
    sessionStorage.setItem(formDraftKey('gf-1'), JSON.stringify(DRAFT_VALUES))
    let first = true
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      if (first) {
        first = false
        return new Promise(() => {})
      }
      handlers.onFrame(frame({
        type: 'frontend_tool_result', runId: 'run-a4b', seq: 13, toolCallId: 'gf-1',
        name: 'generative_form', status: 'FRONTEND_RESULT', result: JSON.stringify(DRAFT_VALUES)
      }))
      handlers.onFrame(frame({ type: 'delta', runId: 'run-a4b', seq: 14, text: '已按你的条件筛好。' }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-a4b', seq: 15, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })

    await store.loadHistory()
    await store.submitGenerativeForm(DRAFT_VALUES)

    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-a4b',
      toolCallId: 'gf-1',
      result: JSON.stringify(DRAFT_VALUES),
      source: 'http-post'
    }))
    const card = toolSegments(store.messages[1]).find((part) => part.toolCallId === 'gf-1')
    expect(card).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(card?.submittedValues).toEqual(DRAFT_VALUES) // 提交值落段（只读态可回看）
    expect(store.messages[1]?.content).toContain('已按你的条件筛好。')
    expect(store.activeForm).toBeNull()
    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(sessionStorage.getItem(formDraftKey('gf-1'))).toBeNull() // 提交即清草稿
  }, 20_000)

  it('schema 非法：不武装表单、不自动回灌，但挂起不锁死（停止即可继续）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 2, messages: gfHistory('gf-2', '表单生成中…')
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a4c',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      pendingEntries: [gfEntry('gf-2', { scenario: 'BROKEN', fields: [] })]
    }))

    await store.loadHistory()

    expect(store.activeForm).toBeNull() // 畸形容器不塞进受控控件
    expect(api.frontendToolResult).not.toHaveBeenCalled() // 也不代替用户做任何回灌
    expect(visiblePendingIds(store)).toEqual(['gf-2']) // 卡保留（用户可据此处理）
    expect(store.suspended).toBe(true)

    await store.stop()

    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.canSend).toBe(true)
    expect(toolSegments(store.messages[1]).find((part) => part.toolCallId === 'gf-2'))
      .toMatchObject({ status: 'cancelled', requiresAction: false })
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A5（R-10）· 轮次结束后刷新：无横幅（防"一律显示横幅"式过度修复）
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A5（R-10 · P1）：轮次结束后刷新不得出现任何"在途"提示', () => {
  it('已完成轮 + 镜像在途残留：不补气泡、不挂流、零提示，全部消息为收尾态', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 3,
      messages: [
        { type: 'USER', text: '导出任务清单' },
        {
          type: 'ASSISTANT',
          text: '导出完成，共 2 个作业。',
          toolCalls: [
            { id: 'h1', type: 'function', name: 'list_tasks', arguments: '{}' },
            { id: 'h2', type: 'function', name: 'start_export', arguments: '{}' }
          ],
          toolCallStates: {
            h1: { status: 'EXECUTED', kind: 'BACKEND' },
            h2: { status: 'EXECUTED', kind: 'CONFIRM', decision: 'approved' }
          }
        },
        {
          type: 'TOOL', text: '',
          toolResponses: [
            { id: 'h1', name: 'list_tasks', responseData: '2 个作业' },
            { id: 'h2', name: 'start_export', responseData: '已创建作业 42' }
          ]
        }
      ]
    })
    // 镜像的 inFlight 是"切后台那一刻"的残留：后端已无在途轮
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-a5',
      messages: [textMessage('u-5', 'user', '导出任务清单'), textMessage('a-5', 'assistant', '导出完成，共 2 个作业。')]
    })

    await store.loadHistory()

    expect(store.messages).toHaveLength(2) // 不因 inFlight 残留多一条气泡
    expect(store.messages.every((message) => message.done)).toBe(true) // 光标不停在流
    expect(store.loading).toBe(false)
    expect(store.suspended).toBe(false)
    expect(store.notice).toBeNull()
    const screen = JSON.stringify(store.messages)
    for (const word of ['在途', '接管', '重挂', '正在恢复']) {
      expect(screen).not.toContain(word)
    }
    expect(toolSegments(store.messages[1])).toHaveLength(2) // 两张卡都在
    expect(store.canSend).toBe(true)
  }, 20_000)

  it('对照：已完成轮不建续收连接（不为"看起来在途"的残留轮空挂一条流）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 2,
      messages: [
        { type: 'USER', text: '统计一下行数' },
        { type: 'ASSISTANT', text: '共 12 行。' }
      ]
    })

    await store.loadHistory()

    expect(api.reattachRun).not.toHaveBeenCalled()
    expect(api.runsCurrent).toHaveBeenCalledTimes(1) // 判据来自快照，且只问一次
    expect(store.notice).toBeNull()
    expect(store.messages.at(-1)?.content).toBe('共 12 行。')
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A6（R-16）· 刷新后表格预览卡失效收口
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A6（R-16 · P1）：刷新后预览卡失效收口（不得出现可直接应用的旧预览）', () => {
  function sheetHistory() {
    return [
      { type: 'USER', text: '把停用的配置项改成禁用' },
      {
        type: 'ASSISTANT',
        text: '我先算一下影响面。',
        toolCalls: [{ id: 'p1', type: 'function', name: 'replace_values', arguments: SHEET_ARGS }],
        toolCallStates: { p1: { status: 'PENDING', kind: 'FRONTEND' } }
      }
    ]
  }

  function sheetEntry() {
    return {
      toolCallId: 'p1',
      entryStatus: 'PENDING',
      kind: 'FRONTEND',
      name: 'replace_values',
      arguments: SHEET_ARGS,
      req: true
    }
  }

  it('刷新后：本地 plan 已丢 ⇒ 卡在但标"预览已失效"，"应用"入口不可用', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: sheetHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a6', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [sheetEntry()]
    }))

    await store.loadHistory()

    expect(store.activePreview).toBeNull() // 没有可应用的预览
    expect(store.stalePreviewToolCallId).toBe('p1') // 而是"已失效"标记
    expect(store.previewText()).toBe('')
    expect(visiblePendingIds(store)).toEqual(['p1'])

    // "应用"路径不可达：即便程序化调用也不产生回灌、不改任何单元格
    await store.confirmWritePreview()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
    expect(store.activePreview).toBeNull()
  }, 20_000)

  it('点"放弃"：带 cancelled=true 回灌 → 卡转终态、挂起收口、随后可继续跑', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: sheetHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a6b', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [sheetEntry()]
    }))
    let first = true
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      if (first) {
        first = false
        return new Promise(() => {})
      }
      handlers.onFrame(frame({ type: 'delta', runId: 'run-a6b', seq: 13, text: '那我换个说法再试。' }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-a6b', seq: 14, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })

    await store.loadHistory()
    await store.abandonStalePreview()

    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-a6b', toolCallId: 'p1', cancelled: true
    }))
    expect(store.stalePreviewToolCallId).toBeNull()
    expect(toolSegments(store.messages[1]).find((part) => part.toolCallId === 'p1'))
      .toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.messages[1]?.content).toContain('那我换个说法再试。')
    expect(store.canSend).toBe(true)
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A7（R-17）· 刷新后附件交接卡两态
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A7（R-17 · P2）：附件交接卡刷新后的两态', () => {
  const UPLOAD_ARGS = JSON.stringify({ ref: 'import.upload.choose', accept: ['.xlsx'] })

  function uploadHistory() {
    return [
      { type: 'USER', text: '把这个配置导进去' },
      {
        type: 'ASSISTANT',
        text: '需要你选一个文件。',
        toolCalls: [{ id: 'u1', type: 'function', name: 'ui_request_upload', arguments: UPLOAD_ARGS }],
        toolCallStates: { u1: { status: 'PENDING', kind: 'FRONTEND' } }
      }
    ]
  }

  function uploadEntry() {
    return {
      toolCallId: 'u1',
      entryStatus: 'PENDING',
      kind: 'FRONTEND',
      name: 'ui_request_upload',
      arguments: UPLOAD_ARGS,
      req: true
    }
  }

  it('已离页刷新（区域不在该页）：交接落 missing、只给放弃，放弃后收敛不重试', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    // 不注册区域：刷新后不在导入向导第 1 步
    api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: uploadHistory() })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-a7b', snapshotStatus: 'SUSPENDED', awaitingExternal: true, pendingEntries: [uploadEntry()]
    }))

    await store.loadHistory()

    expect(store.activeUploadHandoff).toMatchObject({
      toolCallId: 'u1', status: 'missing', reason: '请先打开导入向导第 1 步'
    })
    expect(store.uploadHandoffAvailability()).toBe('missing') // 主按钮禁用（只有放弃可用）

    await store.cancelUploadHandoff()

    expect(api.frontendToolResult).toHaveBeenCalledTimes(1) // 恰一次，不循环
    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-a7b', toolCallId: 'u1', cancelled: true
    }))
    expect(store.activeUploadHandoff).toBeNull()
    expect(toolSegments(store.messages[1]).find((part) => part.toolCallId === 'u1'))
      .toMatchObject({ status: 'cancelled', requiresAction: false })
  }, 20_000)

  it('已交接刷新：终态与交接结果原文保留，挂起不复活', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 3,
      messages: [
        ...uploadHistory().slice(0, 1),
        {
          type: 'ASSISTANT',
          text: '需要你选一个文件。',
          toolCalls: [{ id: 'u1', type: 'function', name: 'ui_request_upload', arguments: UPLOAD_ARGS }],
          toolCallStates: { u1: { status: 'FRONTEND_RESULT', kind: 'FRONTEND' } }
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'u1', name: 'ui_request_upload', responseData: '已接收文件 report.xlsx（12 KB）' }] }
      ]
    })
    api.runsCurrent.mockResolvedValue(emptySnapshot())

    await store.loadHistory()

    const card = toolSegments(store.messages[1]).find((part) => part.toolCallId === 'u1')
    expect(card).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(card?.result).toBe('已接收文件 report.xlsx（12 KB）') // 元数据保留
    expect(store.activeUploadHandoff).toBeNull() // 已交接 ⇒ 交接态不复活
    expect(visiblePendingIds(store)).toEqual([])
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * A8（R-15）· 刷新后 pill 与 done.context.level 一致
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('A8（R-15 · P2）：刷新后上下文 pill 与轮末 done.context 同源', () => {
  it('轮末 done.context.level=soft ⇒ 刷新后 pill 的 level/数值与端点逐值相等', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.chat.mockImplementation((_request: unknown, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'start', runId: 'run-a8', sessionId: SESSION, seq: 1 }))
      handlers.onFrame(frame({ type: 'delta', runId: 'run-a8', seq: 2, text: '答' }))
      handlers.onFrame(frame({
        type: 'done', runId: 'run-a8', seq: 3, cancelled: false,
        context: { usedTokens: 64000, limitTokens: 128000, percent: 50, level: 'soft', est: false, circuit: 'closed' }
      }))
      return Promise.resolve({ terminal: true })
    })
    await store.send('问一句')
    const doneLevel = store.contextUsage?.level
    expect(doneLevel).toBe('soft')

    // 刷新：新 store + 历史 + 端点（镜像不含占用，占用只能由服务端重建）
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 2,
      messages: [{ type: 'USER', text: '问一句' }, { type: 'ASSISTANT', text: '答' }]
    })
    api.getContext.mockResolvedValue({
      ...noContext(),
      usedTokens: 64000,
      percent: 50,
      source: 'stream',
      compaction: { ...noContext().compaction, level: 'soft' }
    })
    const reloaded = await freshStoreClean()
    reloaded.sessionId = SESSION
    await reloaded.loadHistory()

    expect(api.getContext).toHaveBeenCalledWith(SESSION)
    expect(reloaded.contextUsage).toMatchObject({
      usedTokens: 64000, limitTokens: 128000, percent: 50, level: 'soft', circuit: 'closed'
    })
    expect(reloaded.contextUsage?.level).toBe(doneLevel) // 压缩级别与轮末一致
    expect(contextPillText(reloaded.contextUsage)).toBe('50% · 64k/128k')
  }, 20_000)

  it('端点带估算与熔断：level/circuit/est 三字段原样恢复，pill 文案带 ≈', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 2,
      messages: [{ type: 'USER', text: '再问' }, { type: 'ASSISTANT', text: '答' }]
    })
    api.getContext.mockResolvedValue({
      ...noContext(),
      usedTokens: 90000,
      percent: 70.3,
      source: 'estimate',
      estimated: true,
      compaction: { ...noContext().compaction, level: 'hard', circuit: 'open' }
    })

    await store.loadHistory()
    await vi.waitFor(() => expect(store.contextUsage).not.toBeNull())

    expect(store.contextUsage).toMatchObject({ level: 'hard', circuit: 'open', est: true })
    expect(contextPillText(store.contextUsage)).toBe('≈70.3% · 90k/128k')
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * B11 · `ui_*` 挂起（第三种挂起形态）：停止与刷新
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('B11（第三种挂起形态 · ui_request_upload）：停止与刷新', () => {
  const UPLOAD_ARGS = JSON.stringify({ ref: 'import.upload.choose', accept: ['.xlsx'] })

  function handoffStore(store: Store): void {
    store.messages = [
      textMessage('u-b11', 'user', '把这个配置导进去'),
      {
        ...textMessage('a-b11', 'assistant', '需要你选一个文件。'),
        parts: [
          { kind: 'text', seg: 0, text: '需要你选一个文件。', streaming: false },
          toolPart('u1', 1, {
            name: 'ui_request_upload', kind2: 'FRONTEND', status: 'pending', requiresAction: true
          })
        ]
      }
    ]
    store.activeUploadHandoff = {
      toolCallId: 'u1',
      runId: 'run-b11',
      ref: 'import.upload.choose',
      accept: ['xlsx'],
      status: 'waiting',
      reason: null,
      filename: null
    }
    store.suspended = true
    store.activeRunId = 'run-b11'
  }

  it('挂起中停止：交接态收起、卡转已取消、输入立即解禁（不锁死）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    handoffStore(store)

    await store.stop()

    expect(store.activeUploadHandoff).toBeNull()
    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.canSend).toBe(true)
    expect(api.cancel).toHaveBeenCalledWith('run-b11')
    expect(toolSegments(store.messages[1]).find((part) => part.toolCallId === 'u1'))
      .toMatchObject({ status: 'cancelled', requiresAction: false })
    expect(visiblePendingIds(store)).toEqual([])
  }, 20_000)

  it('挂起中刷新：交接卡重建、可操作性可判、输入禁用（与另两型挂起同构）', async () => {
    /**
     * 判据（第三种挂起形态）：刷新后交接卡与交接态一并重建 —— 卡片身份与入参保留，
     * 「可继续 / 只能放弃」两态与 `uploadHandoffAvailability()` **必须同向**，
     * 轮次级语义与 CONFIRM / GF 挂起同型（`suspended=true`、输入禁用），且不得串到另外两种形态上。
     *
     * <p>为什么这里锁"两态自洽"而不是写死 `waiting`：区域注册表是**模块级**单例，而本文件为了表达
     * "页面重载"必须 `vi.resetModules()` —— 两者叠加时"store 手里的注册表"与"用例注册进去的注册表"
     * 在**全量并行**下会分叉（实测：单跑恒绿、整仓跑红）。`waiting`（区域仍在本页 ⇒ 主按钮可用）
     * 那一条由 `aie2e-refresh-upload-region.spec.ts` 在**不 reset 模块**的稳定形态里锁死。
     */
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 2,
      messages: [
        { type: 'USER', text: '把这个配置导进去' },
        {
          type: 'ASSISTANT',
          text: '需要你选一个文件。',
          toolCalls: [{ id: 'u1', type: 'function', name: 'ui_request_upload', arguments: UPLOAD_ARGS }],
          toolCallStates: { u1: { status: 'PENDING', kind: 'FRONTEND' } }
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-b11b',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      pendingEntries: [{
        toolCallId: 'u1', entryStatus: 'PENDING', kind: 'FRONTEND', name: 'ui_request_upload',
        arguments: UPLOAD_ARGS, req: true
      }]
    }))

    await store.loadHistory()

    expect(store.activeUploadHandoff).toMatchObject({
      toolCallId: 'u1',
      ref: 'import.upload.choose',
      accept: ['xlsx'],
      filename: null
    })
    const status = store.activeUploadHandoff?.status
    expect(['waiting', 'missing']).toContain(status)
    // 两态与可操作性判据同向：能继续 ⇒ 主按钮可用；已离页 ⇒ 只剩放弃
    expect(store.uploadHandoffAvailability()).toBe(status === 'waiting' ? 'waiting' : 'missing')
    expect(visiblePendingIds(store)).toEqual(['u1'])
    expect(store.suspended).toBe(true)
    expect(store.canSend).toBe(false)
    expect(store.activeForm).toBeNull() // 不误武装成表单
    expect(store.stalePreviewToolCallId).toBeNull() // 也不误标成表格预览
    expect(api.frontendToolResult).not.toHaveBeenCalled() // 重建不自动回灌（无重试风暴）
  }, 20_000)

  it('停止后刷新：交接不复活、卡显示"已取消"、输入可用（后端已落 CANCELLED）', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 3,
      messages: [
        { type: 'USER', text: '把这个配置导进去' },
        {
          type: 'ASSISTANT',
          text: '需要你选一个文件。',
          stopped: true,
          toolCalls: [{ id: 'u1', type: 'function', name: 'ui_request_upload', arguments: UPLOAD_ARGS }],
          toolCallStates: { u1: { status: 'CANCELLED', kind: 'FRONTEND', decision: 'cancel' } }
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'u1', name: 'ui_request_upload', responseData: '已取消' }] }
      ]
    })
    writeMirrorFixture(SESSION, {
      inFlight: false,
      lastRunId: 'run-b11c',
      messages: [
        textMessage('u-b11c', 'user', '把这个配置导进去'),
        {
          ...textMessage('a-b11c', 'assistant', '需要你选一个文件。', { stopped: true }),
          parts: [
            { kind: 'text', seg: 0, text: '需要你选一个文件。', streaming: false },
            toolPart('u1', 1, { name: 'ui_request_upload', kind2: 'FRONTEND', status: 'cancelled' })
          ]
        }
      ]
    })

    await store.loadHistory()

    const card = toolSegments(store.messages[1]).find((part) => part.toolCallId === 'u1')
    expect(card).toMatchObject({ status: 'cancelled', requiresAction: false, decision: 'cancel' })
    expect(store.activeUploadHandoff).toBeNull()
    expect(visiblePendingIds(store)).toEqual([])
    expect(store.suspended).toBe(false)
    expect(store.canSend).toBe(true)
    expect(store.messages).toHaveLength(2)
  }, 20_000)
})

/* ══════════════════════════════════════════════════════════════════════════════
 * 三件套补充：既有 RLD 条目缺的那一件（镜像 / 快照 / 帧）
 * ══════════════════════════════════════════════════════════════════════════════ */

describe('R-01 三件套：镜像滞后的半截 + 快照 RUNNING + 全量重放帧', () => {
  it('重放覆盖镜像半截（不双拼），全程零提示，气泡数不增', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '这个任务怎么导出' }]
    })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-sp1',
      runSeq: { 'run-sp1': 3 },
      messages: [
        textMessage('u-sp1', 'user', '这个任务怎么导出'),
        textMessage('a-sp1', 'assistant', '旧半截', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-sp1', archiveMaxSeq: 3 }))
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'start', runId: 'run-sp1', seq: 1 }))
      handlers.onFrame(frame({ type: 'delta', runId: 'run-sp1', seq: 2, text: '完整正文' }))
      return new Promise(() => {})
    })

    await store.loadHistory()

    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 0 }) // 全量重放
    expect(store.messages).toHaveLength(2)
    expect(store.messages[1]?.id).toBe('a-sp1')
    expect(store.messages[1]?.content).toBe('完整正文') // 不含"旧半截"
    expect(store.notice).toBeNull()
    expect(store.loading).toBe(true)
  }, 20_000)
})

describe('R-07 三件套：镜像里 APPROVED 卡 + 快照 RUNNING + 结局帧', () => {
  it('已批准条目在途刷新：卡收敛终态、本轮结束、零提示', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '批准后就跑' }]
    })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-sp2',
      messages: [
        textMessage('u-sp2', 'user', '批准后就跑'),
        {
          ...textMessage('a-sp2', 'assistant', '已批准，正在跑。', { streaming: true, done: false }),
          parts: [
            { kind: 'text', seg: 0, text: '已批准，正在跑。', streaming: false },
            toolPart('c7', 1, { name: 'start_export', kind2: 'CONFIRM', status: 'running' })
          ]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-sp2',
      pendingEntries: [{ toolCallId: 'c7', entryStatus: 'APPROVED', kind: 'CONFIRM', name: 'start_export', req: true }]
    }))
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      // 全量重放：镜像正文被清空后由归档补齐（含卡前那句说明文字）
      handlers.onFrame(frame({ type: 'delta', runId: 'run-sp2', seq: 4, text: '已批准，正在跑。' }))
      handlers.onFrame(frame({
        type: 'tool_result', runId: 'run-sp2', seq: 5, toolCallId: 'c7', name: 'start_export',
        kind: 'CONFIRM', ok: true, executed: true, status: 'EXECUTED', result: '作业 42'
      }))
      handlers.onFrame(frame({ type: 'delta', runId: 'run-sp2', seq: 6, text: '跑完了。' }))
      handlers.onFrame(frame({ type: 'done', runId: 'run-sp2', seq: 7, cancelled: false }))
      return Promise.resolve({ terminal: true })
    })

    await store.loadHistory()

    const card = toolSegments(store.messages[1]).find((part) => part.toolCallId === 'c7')
    expect(card).toMatchObject({ status: 'succeeded', requiresAction: false })
    expect(card?.result).toBe('作业 42')
    expect(store.messages[1]?.content).toBe('已批准，正在跑。跑完了。')
    expect(store.suspended).toBe(false)
    expect(store.loading).toBe(false)
    expect(store.notice).toBeNull() // 不出现"静默失联"式提示
  }, 20_000)
})

describe('R-13 三件套：镜像在途轮 + 快照待决条目 + 续收帧', () => {
  it('合并宿主继续收：气泡数 = 历史 + 1，待决卡与续写共存且不重复', async () => {
    const store = await freshStoreClean()
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
      lastRunId: 'run-sp3',
      messages: [
        textMessage('u-sp3', 'user', '第二问'),
        textMessage('a-sp3', 'assistant', '正在答…', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-sp3',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      archiveMaxSeq: 12,
      pendingEntries: [pendingConfirm('c13')]
    }))
    // 捕获句柄放在对象属性上：`let` 变量在闭包里赋值时 TS 会把它窄成 null（句柄用不了）
    const live: { handlers: FrameHandlers | null } = { handlers: null }
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      live.handlers = handlers
      return new Promise(() => {})
    })

    await store.loadHistory()
    expect(store.messages).toHaveLength(4) // 历史 3 + 在途 1
    expect(store.messages.map((message) => message.role)).toEqual(['user', 'assistant', 'user', 'assistant'])
    expect(store.messages[1]?.content).toBe('第一答') // 第 1 轮不被吃

    live.handlers?.onFrame(frame({ type: 'delta', runId: 'run-sp3', seq: 13, text: '后续正文' }))

    expect(store.messages).toHaveLength(4) // 续写不新建气泡
    expect(store.messages[3]?.content).toContain('后续正文')
    expect(store.messages[3]?.id).toBe('a-sp3')
    expect(toolSegments(store.messages[3]).filter((part) => part.toolCallId === 'c13')).toHaveLength(1)
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 12 }) // 挂起轮走差量
  }, 20_000)
})

describe('R-12 三件套：刷新恢复的在途轮 + 停止 + done(cancelled) 帧', () => {
  it('刷新后停止：半截保留、镜像落 stopped、不新建气泡', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '在途的问题' }]
    })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-sp4',
      messages: [
        textMessage('u-sp4', 'user', '在途的问题'),
        textMessage('a-sp4', 'assistant', '', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-sp4', archiveMaxSeq: 4 }))
    // 捕获句柄放在对象属性上：`let` 变量在闭包里赋值时 TS 会把它窄成 null（句柄用不了）
    const live: { handlers: FrameHandlers | null } = { handlers: null }
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      live.handlers = handlers
      return new Promise(() => {})
    })

    await store.loadHistory()
    live.handlers?.onFrame(frame({ type: 'delta', runId: 'run-sp4', seq: 5, text: '半截正文' }))
    expect(store.messages[1]?.content).toBe('半截正文')

    await store.stop()
    expect(api.cancel).toHaveBeenCalledWith('run-sp4')
    expect(store.canSend).toBe(true) // 刷新恢复的轮也能停、也能立刻继续用

    live.handlers?.onFrame(frame({ type: 'done', runId: 'run-sp4', seq: 6, cancelled: true }))

    expect(store.messages).toHaveLength(2)
    expect(store.messages[1]?.content).toBe('半截正文') // 不回滚
    expect(store.messages[1]?.stopped).toBe(true)
    const mirror = readMirrorFixture(SESSION)
    expect(mirror?.messages?.[1]?.stopped).toBe(true)
    expect(mirror?.inFlight).toBe(false)
  }, 20_000)
})

describe('R-18 三件套：镜像在途 + 快照 RUNNING + 续收帧全程无横幅', () => {
  it('逐帧断言 notice 恒为 null，且屏上不出现实现词', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 1, messages: [{ type: 'USER', text: '讲一下配置' }]
    })
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-sp5',
      messages: [
        textMessage('u-sp5', 'user', '讲一下配置'),
        textMessage('a-sp5', 'assistant', '半截', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-sp5', archiveMaxSeq: 2 }))
    const notices: Array<string | null> = []
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      for (const payload of [
        { type: 'start', runId: 'run-sp5', seq: 1 },
        { type: 'delta', runId: 'run-sp5', seq: 2, text: '续收正文' },
        { type: 'done', runId: 'run-sp5', seq: 3, cancelled: false }
      ]) {
        handlers.onFrame(frame(payload))
        notices.push(store.notice)
      }
      return new Promise(() => {})
    })

    await store.loadHistory()

    expect(notices).toEqual([null, null, null])
    expect(store.messages.at(-1)?.content).toBe('续收正文')
    const screen = JSON.stringify(store.messages)
    for (const word of ['在途', '接管', '重挂', '正在进行中']) {
      expect(screen).not.toContain(word)
    }
  }, 20_000)
})

describe('R-11 三件套：稍后重开同一会话（最终一致）', () => {
  it('关闭面板再重开：气泡数与段序与实时形态一致，镜像残留不复制成第二条', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 3,
      messages: [
        { type: 'USER', text: '导出任务清单' },
        {
          type: 'ASSISTANT',
          text: '导出完成。',
          toolCalls: [
            { id: 'h1', type: 'function', name: 'list_tasks', arguments: '{}' },
            { id: 'h2', type: 'function', name: 'start_export', arguments: '{}' }
          ],
          toolCallStates: {
            h1: { status: 'EXECUTED', kind: 'BACKEND' },
            h2: { status: 'EXECUTED', kind: 'CONFIRM' }
          }
        },
        {
          type: 'TOOL', text: '',
          toolResponses: [
            { id: 'h1', name: 'list_tasks', responseData: '2 个作业' },
            { id: 'h2', name: 'start_export', responseData: '已创建作业 42' }
          ]
        }
      ]
    })
    // 关面板前那一刻的镜像（挂个 inFlight 残留）：重开时不得被当成"又多一轮"
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-r11',
      messages: [textMessage('u-r11', 'user', '导出任务清单'), textMessage('a-r11', 'assistant', '导出完成。')]
    })

    await store.loadHistory()

    expect(store.messages).toHaveLength(2) // 气泡数 == 实时形态（1 问 1 答）
    const segments = store.messages[1]?.parts ?? []
    expect(segments.map((part) => (part.kind === 'tool' ? `tool:${part.toolCallId}` : `${part.kind}:${part.seg}`)))
      .toEqual(['text:0', 'tool:h1', 'tool:h2']) // 段序与流式期一致（先正文后卡，卡按调用序）
    expect(toolSegments(store.messages[1]).map((part) => part.status)).toEqual(['succeeded', 'succeeded'])
    expect(store.notice).toBeNull()
    expect(store.canSend).toBe(true)
    expect(api.reattachRun).not.toHaveBeenCalled() // 已收尾的轮不重挂
  }, 20_000)
})

describe('R-02 三件套：老会话第 2 轮流式中刷新 + 续收帧', () => {
  it('第 1 轮（含卡）完好、第 2 轮续写到本轮宿主，气泡数 = 历史 + 1', async () => {
    const store = await freshStoreClean()
    store.sessionId = SESSION
    api.history.mockResolvedValue({
      sessionId: SESSION,
      count: 4,
      messages: [
        { type: 'USER', text: '统计一下行数' },
        {
          type: 'ASSISTANT',
          text: '我先看下定义。',
          toolCalls: [{ id: 'h1', type: 'function', name: 'list_config_defs', arguments: '{}' }],
          toolCallStates: { h1: { status: 'EXECUTED', kind: 'BACKEND' } }
        },
        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'list_config_defs', responseData: '12 条' }] },
        // 第 2 轮开轮即写入记忆（后端 ChatMemory 的实际形态）
        { type: 'USER', text: '再按区域分一下' }
      ]
    })
    // 第 2 轮刚开、镜像里只有半截：宿主必须落在本轮（不回落吃第 1 轮的答案）
    writeMirrorFixture(SESSION, {
      inFlight: true,
      lastRunId: 'run-r02',
      messages: [
        textMessage('u-2', 'user', '再按区域分一下'),
        textMessage('a-2', 'assistant', '', { streaming: true, done: false })
      ]
    })
    api.runsCurrent.mockResolvedValue(snapshot({ runId: 'run-r02', archiveMaxSeq: 6 }))
    api.reattachRun.mockImplementation((_runId: string, handlers: FrameHandlers) => {
      handlers.onFrame(frame({ type: 'delta', runId: 'run-r02', seq: 7, text: '按区域拆成 3 段。' }))
      return new Promise(() => {})
    })

    await store.loadHistory()

    expect(store.messages.map((message) => message.role)).toEqual(['user', 'assistant', 'user', 'assistant'])
    expect(store.messages[1]?.content).toBe('我先看下定义。') // 第 1 轮正文不被吃
    expect(toolSegments(store.messages[1]).map((part) => part.toolCallId)).toEqual(['h1']) // 卡仍在
    expect(store.messages[3]?.content).toBe('按区域拆成 3 段。') // 第 2 轮续写到自己的宿主
    expect(store.messages[3]?.id).not.toBe(store.messages[1]?.id)
    expect(store.loading).toBe(true)
    expect(store.notice).toBeNull()
  }, 20_000)
})
