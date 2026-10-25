/**
 * L0（2026-10-21 用户直裁）刷新恢复口径：**只为"请求帧已发"的待决卡重建**；
 * M-2b（2026-10-22）起孤儿轮（`req` 缺键）也恢复（判据见下），`req === false` 口径不变。
 *
 * 洞在刷新恢复路径上（B1「挂起即断」之后请求帧是逐条发的，`pendingEntries` 却整批落库）：
 * ① 旧代码遍历全部 PENDING 条目建卡 —— 包括请求帧还没发出的卡 2 / 卡 3，用户可提前点击；
 * ② kind 二值映射（非 CONFIRM 一律当 FRONTEND）—— 未执行的后端条目也被画成前端挂起卡；
 * ③ GF 表单随卡一起提前武装 ⇒ 可提前提交（后端根本还没走到那张卡）。
 *
 * 本 spec 锁四条判据（`restoreSuspendedSnapshot`）：
 * - 建卡 = `entryStatus === 'PENDING'` && `kind ∈ {CONFIRM, FRONTEND}` && `req !== false`；
 * - `req === false`（同批请求帧还没轮到）= 不建卡，等实时帧（活轮行为不变）；
 *   `req` 缺键 **不在此列** —— M-2b 起按「升级前遗留的孤儿轮」建卡（见
 *   `restore-orphan-null-req.spec.ts`；本文件末尾只留一条旧载荷回归锁）；
 * - `suspended` 置位与 req / kind **无关**：只要存在任意 PENDING 条目就置位（输入禁用语义不变）；
 * - 未发卡条目不渲染，也不新增任何占位 UI；**但无卡 ≠ 无后续帧**（I-1，2026-10-21 收口修）：
 *   挂起态下即使一张卡都重建不出来，也照常以 `archiveMaxSeq` 挂差量续收 —— 后续请求帧到达即
 *   按实时路径建卡（旧行为「无卡⇒无连接」会让页面停在"输入禁用 + 上方无卡"的死胡同）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AiRunCurrent, AiRunCurrentEntry, ChatMessage } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
// 收集期预热：用例内的动态 import（`freshStore()` / `mountPanel()`）在**首条用例**里要付
// "首次 transform + 首次装配"的一次性成本 —— 空载 1.9–3.4s，并发满载可涨到十几秒，直接吃掉
// 该用例自己的 timeout 预算（实测：首条超时、同文件后续用例 10–40ms 通过）。这里放到收集期
// 付掉（收集不受单用例 timeout 约束），用例内动态 import 只剩重执行（~20ms）；断言与 timeout 不变。
import '@/stores/ai'


/** 挂起快照骨架（默认单条"请求帧已发"的确认卡，用例按需覆写 pendingEntries）。 */
function snapshot(entries: AiRunCurrentEntry[], overrides: Partial<AiRunCurrent> = {}): AiRunCurrent {
  return {
    found: true,
    runId: 'run-l0',
    snapshotStatus: 'SUSPENDED',
    pendingEntries: entries,
    unresolvedExternal: entries.filter((item) => item.entryStatus === 'PENDING' && item.kind !== 'BACKEND')
      .map((item) => item.toolCallId),
    awaitingExternal: true,
    archiveMaxSeq: 5,
    ...overrides
  }
}

function confirmEntry(toolCallId: string, extra: Partial<AiRunCurrentEntry> = {}): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', ...extra }
}

const GF_ARGS = JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } })

function gfEntry(toolCallId: string, extra: Partial<AiRunCurrentEntry> = {}): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind: 'FRONTEND', name: 'generative_form', arguments: GF_ARGS, ...extra }
}

async function freshStore() {
  // 模块级状态（currentAbort 等）随 resetModules 归零：每个用例从干净现场起跑
  vi.resetModules()
  const mod = await import('@/stores/ai')
  return mod.useAiStore()
}

/** 全部消息里的工具段（段是唯一真值，卡由段派生）。 */
function toolSegments(messages: ChatMessage[]) {
  return messages
    .flatMap((message) => message.parts)
    .filter((part): part is Extract<ChatMessage['parts'][number], { kind: 'tool' }> => part.kind === 'tool')
}

function pendingCallHosts(messages: ChatMessage[]): ChatMessage[] {
  return messages.filter((message) => message.pendingCall !== null)
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.chat.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
})

describe('restoreSuspendedSnapshot：只重建请求帧已发的待决卡', () => {
  it('一批三卡：只有 req=true 的卡 1 重建；卡 2（req=false）与后端条目都不画卡', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([
      confirmEntry('c1', { req: true }),
      confirmEntry('c2', { req: false }), // 请求帧还没发（后端逐条发帧，尚未轮到它）
      confirmEntry('c3', { kind: 'BACKEND', name: 'read_tasks', req: true }) // 后端直接执行，从不弹卡
    ]))

    await store.restoreSuspendedSnapshot()

    const ids = toolSegments(store.messages).map((part) => part.toolCallId)
    expect(ids).toEqual(['c1'])
    expect(pendingCallHosts(store.messages)).toHaveLength(1)
    expect(store.messages[0]?.pendingCall).toMatchObject({ toolCallId: 'c1', kind: 'CONFIRM', status: 'pending' })
    // 未发卡条目不留任何痕迹（无占位 UI、无空助手消息之外的段）
    expect(store.messages.flatMap((message) => message.parts).filter((part) => part.kind === 'tool')).toHaveLength(1)
    expect(store.activeRunId).toBe('run-l0')
  }, 15000)

  it('BACKEND 条目即便 req=true 也不画卡（kind 过滤独立于 req，混批真实口径）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0-backend'
    store.historyLoaded = true
    // 真实可达形态：同批一条 CONFIRM（未决 ⇒ awaitingExternal=true）+ 一条后端条目
    api.runsCurrent.mockResolvedValue(snapshot([
      confirmEntry('c1', { req: true }),
      confirmEntry('b1', { kind: 'BACKEND', name: 'read_tasks', req: true })
    ]))

    await store.restoreSuspendedSnapshot()

    // 后端条目不是挂起卡：只有 CONFIRM 那一张
    expect(toolSegments(store.messages).map((part) => part.toolCallId)).toEqual(['c1'])
    expect(store.suspended).toBe(true)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
  }, 15000)

  it('全 BACKEND 批（awaitingExternal=false）⇒ 不建卡、不置挂起，按在途轮静默全量续收（批G · G2）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0-backend-only'
    store.historyLoaded = true
    // 生产口径：awaitingExternal = SUSPENDED && unresolvedExternal 非空，而 BACKEND 不计入后者
    api.runsCurrent.mockResolvedValue(snapshot([
      confirmEntry('b1', { kind: 'BACKEND', name: 'read_tasks', req: true }),
      confirmEntry('b2', { kind: 'BACKEND', name: 'read_jobs', req: true })
    ], { awaitingExternal: false, unresolvedExternal: [] }))

    await store.restoreSuspendedSnapshot()

    // 旧口径「不挂流」把这类轮判成死局（实证 C2：后端继续跑完、UI 12s 仍空）；
    // 新口径 = 与 RUNNING 同路：无卡、无横幅、无占位文案，lastSeq=0 全量重放后继续收流。
    expect(toolSegments(store.messages)).toHaveLength(0)
    expect(store.suspended).toBe(false)
    expect(store.notice).toBeNull()
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 0 })
  }, 15000)

  it('存在任意 PENDING 条目（全部 req=false）⇒ suspended 仍置位、activeRunId 指向该轮、照常挂流（I-1）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0-suspend'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([
      confirmEntry('c1', { req: false }),
      confirmEntry('c2', { req: false })
    ]))

    await store.restoreSuspendedSnapshot()

    expect(store.suspended).toBe(true)
    expect(store.activeRunId).toBe('run-l0')
    expect(toolSegments(store.messages)).toHaveLength(0)
    // I-1（收口修）：无卡 ≠ 无后续帧 —— 仍以 archiveMaxSeq 挂差量续收，等请求帧实时到达
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 5 })
  }, 15000)

  it('I-1 回归：卡 1 已决 + 卡 2 未发卡的窗口刷新 → 无卡但已挂流，卡 2 请求帧随后到达即渲染', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0-i1'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([
      confirmEntry('c1', { entryStatus: 'APPROVED', req: true }), // 卡 1 已决（其后端工具可能还在执行）
      confirmEntry('c2', { req: false }) // 卡 2 的请求帧还没发：这一窗口的真实形态
    ]))

    await store.restoreSuspendedSnapshot()

    // 刷新瞬间：无卡、输入禁用，但连接已经挂上（旧行为 = 直接返回、无连接 ⇒ 死胡同）
    expect(toolSegments(store.messages)).toHaveLength(0)
    expect(store.suspended).toBe(true)
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    const handlers = api.reattachRun.mock.calls[0]?.[1] as {
      onFrame: (frame: Record<string, unknown>) => void
    }
    expect(handlers?.onFrame).toBeTypeOf('function')

    // 卡 2 的请求帧沿这条连接实时到达 ⇒ 按实时路径建卡（不是恢复路径的 req 判据）
    handlers.onFrame({
      type: 'confirm_request',
      runId: 'run-l0',
      toolCallId: 'c2',
      name: 'start_export',
      args: '{"taskId":9}',
      seq: 6
    })

    expect(toolSegments(store.messages).map((part) => part.toolCallId)).toEqual(['c2'])
    expect(pendingCallHosts(store.messages)).toHaveLength(1)
    expect(store.messages.at(-1)?.pendingCall).toMatchObject({ toolCallId: 'c2', status: 'pending' })
  }, 15000)

  it('GF 表单：req=false 不武装 activeForm；req=true 才武装（表单一直可填，只认后端结局帧）', async () => {
    const notRequested = await freshStore()
    notRequested.sessionId = 'sess-l0-gf-a'
    notRequested.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([gfEntry('g1', { req: false })]))

    await notRequested.restoreSuspendedSnapshot()
    expect(notRequested.activeForm).toBeNull()
    expect(toolSegments(notRequested.messages)).toHaveLength(0)
    expect(notRequested.suspended).toBe(true)

    const requested = await freshStore()
    requested.sessionId = 'sess-l0-gf-b'
    requested.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([gfEntry('g2', { req: true })]))

    await requested.restoreSuspendedSnapshot()
    expect(requested.activeForm).toMatchObject({ toolCallId: 'g2', runId: 'run-l0', status: 'filling' })
    expect(toolSegments(requested.messages)).toHaveLength(1)
  }, 15000)

  it('旧载荷缺 req 键 ⇒ 按孤儿轮建卡（M-2b 无感恢复；不再按 false 处理）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0-legacy'
    store.historyLoaded = true
    // 不带 req / reqAtMs：本口径引入前的载荷（升级前遗留的挂起轮）
    api.runsCurrent.mockResolvedValue(snapshot([confirmEntry('legacy')]))

    await store.restoreSuspendedSnapshot()

    expect(toolSegments(store.messages).map((part) => part.toolCallId)).toEqual(['legacy'])
    expect(pendingCallHosts(store.messages)).toHaveLength(1)
    expect(store.messages[0]?.pendingCall).toMatchObject({ toolCallId: 'legacy', kind: 'CONFIRM', status: 'pending' })
    expect(store.suspended).toBe(true)
  }, 15000)

  it('已决条目与 req=true 并存：只重建未决那张，已决条目走既有的残留收敛', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-l0-mixed'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([
      confirmEntry('done', { entryStatus: 'APPROVED', req: true }),
      confirmEntry('live', { req: true })
    ]))

    await store.restoreSuspendedSnapshot()

    expect(toolSegments(store.messages).map((part) => part.toolCallId)).toEqual(['live'])
    expect(store.suspended).toBe(true)
  }, 15000)
})
