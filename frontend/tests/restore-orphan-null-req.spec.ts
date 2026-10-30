/**
 * M-2b（2026-10-22 用户直裁「做到用户无感，交互跟之前一样，不要加任何别的提示」）：
 * 刷新后**升级前遗留的孤儿挂起轮**（`req` 缺键 = 请求帧是否发过已不可知）也要无感恢复。
 *
 * 锁六条：
 * ① `req` 缺失视为可建卡（单卡重建后可直接批准，成功路径不写任何提示）；
 * ② 串行呈现：一次只出一张待决卡，决完一张下一张自动出现（复用快照条目的本地队列，
 *    与实时路径「一次一帧一张卡」一致），全部决完后挂起态正常收口；
 * ③ 表格写预览卡：本地 plan 刷新即丢 —— 仍建卡，但按既有「预览已失效 + 放弃」呈现
 *    （`stalePreviewToolCallId`），放弃后下一张卡继续出现；
 * ④ 表单卡：`req` 缺失同样重新武装 `activeForm`，取消后下一张卡继续出现；
 * ⑤ 上传交接卡：`req` 缺失同样重建 `activeUploadHandoff`（区域不在该页则 missing）；
 * ⑥ 活轮（`req=true` + 尚未发帧的 `req=false`）行为不变：仍只重建已发帧那张，
 *    未发帧条目等实时帧到达；多卡批次中间态不产生任何「同步中/失败」提示。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { installIdentityFixture } from './ai-identity-fixture'
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

const GF_ARGS = JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } })
const SHEET_ARGS = JSON.stringify({
  defCode: 'DEMO',
  scope: 'sheet',
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用'
})

function snapshot(entries: AiRunCurrentEntry[], overrides: Partial<AiRunCurrent> = {}): AiRunCurrent {
  return {
    found: true,
    runId: 'run-m2b',
    snapshotStatus: 'SUSPENDED',
    pendingEntries: entries,
    unresolvedExternal: entries.filter((item) => item.entryStatus === 'PENDING' && item.kind !== 'BACKEND')
      .map((item) => item.toolCallId),
    awaitingExternal: true,
    archiveMaxSeq: 9,
    ...overrides
  }
}

/** 升级前遗留形态：`req` / `reqAtMs` 均缺键。 */
function orphanConfirm(toolCallId: string, extra: Partial<AiRunCurrentEntry> = {}): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', ...extra }
}

function orphanGf(toolCallId: string): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind: 'FRONTEND', name: 'generative_form', arguments: GF_ARGS }
}

function orphanSheet(toolCallId: string): AiRunCurrentEntry {
  return { toolCallId, entryStatus: 'PENDING', kind: 'FRONTEND', name: 'replace_values', arguments: SHEET_ARGS }
}

async function freshStore() {
  vi.resetModules()
  const mod = await import('@/stores/ai')
  return mod.useAiStore()
}

function toolParts(messages: ChatMessage[]) {
  return messages
    .flatMap((message) => message.parts)
    .filter((part): part is Extract<ChatMessage['parts'][number], { kind: 'tool' }> => part.kind === 'tool')
}

/** 当前可见的待决卡（requiresAction + pending）—— 串行口径下恒 ≤ 1。 */
function visiblePendingIds(messages: ChatMessage[]): string[] {
  return toolParts(messages)
    .filter((part) => part.requiresAction && part.status === 'pending')
    .map((part) => part.toolCallId)
}

// 预热 stores/ai 的首次 transform/eval：放在用例/钩子超时之外，避免高负载下首个 freshStore 的
// 动态 import 吃掉 15s 用例预算；随后 resetModules，仍由各例 freshStore 重建隔离实例。
await import('@/stores/ai')
vi.resetModules()

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.chat.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  api.frontendToolResult.mockReset()
  api.confirm.mockReset()
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.confirm.mockResolvedValue({})
  api.frontendToolResult.mockResolvedValue({})
  installIdentityFixture()
})

describe('M-2b：req 缺键（孤儿轮）无感恢复', () => {
  it('① 单卡：req 缺键重建可批准，成功路径不写任何提示', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-m2b-single'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([orphanConfirm('c1')]))

    await store.restoreSuspendedSnapshot()

    expect(visiblePendingIds(store.messages)).toEqual(['c1'])
    expect(store.messages[0]?.pendingCall).toMatchObject({ toolCallId: 'c1', kind: 'CONFIRM', status: 'pending' })
    expect(store.suspended).toBe(true)
    expect(store.activeRunId).toBe('run-m2b')

    await store.confirm(true, undefined, 'c1')

    expect(api.confirm).toHaveBeenCalledWith(
      { runId: 'run-m2b', toolCallId: 'c1', approved: true, reason: null },
      expect.objectContaining({ headers: expect.any(Object) })
    )
    expect(visiblePendingIds(store.messages)).toEqual([])
    // 中间态静默：成功路径不得出现「同步中 / 失败」假象
    expect(store.notice).toBeNull()
  }, 15000)

  it('② 多卡串行：一次一张，决完一张下一张自动出现，全决完后挂起收口', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-m2b-serial'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([orphanConfirm('c1'), orphanConfirm('c2'), orphanConfirm('c3')]))

    await store.restoreSuspendedSnapshot()
    expect(visiblePendingIds(store.messages)).toEqual(['c1'])
    const bubbleCount = store.messages.length

    await store.confirm(true, undefined, 'c1')
    expect(visiblePendingIds(store.messages)).toEqual(['c2'])
    expect(store.messages[0]?.pendingCall).toMatchObject({ toolCallId: 'c2', status: 'pending' })
    expect(store.suspended).toBe(true)
    expect(store.notice).toBeNull()

    await store.confirm(true, undefined, 'c2')
    expect(visiblePendingIds(store.messages)).toEqual(['c3'])
    expect(store.suspended).toBe(true)
    expect(store.notice).toBeNull()

    await store.confirm(true, undefined, 'c3')
    expect(visiblePendingIds(store.messages)).toEqual([])
    expect(store.suspended).toBe(false)
    // 串行重建全部落在同一宿主消息里：不新增空气泡
    expect(store.messages.length).toBe(bubbleCount)
    expect(api.confirm).toHaveBeenCalledTimes(3)
  }, 15000)

  it('③ 表格写预览卡：req 缺键仍建卡但按「预览已失效 + 放弃」呈现，放弃后下一张出现', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-m2b-sheet'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([orphanSheet('p1'), orphanConfirm('c2')]))

    await store.restoreSuspendedSnapshot()

    expect(visiblePendingIds(store.messages)).toEqual(['p1'])
    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBe('p1')

    await store.abandonStalePreview()

    expect(api.frontendToolResult).toHaveBeenCalledWith(
      expect.objectContaining({ toolCallId: 'p1', cancelled: true }),
      expect.objectContaining({ headers: expect.any(Object) })
    )
    expect(store.stalePreviewToolCallId).toBeNull()
    expect(visiblePendingIds(store.messages)).toEqual(['c2'])
    expect(store.notice).toBeNull()
  }, 15000)

  it('④ 表单卡：req 缺键重新武装 activeForm，取消后下一张出现并重新武装', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-m2b-gf'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([orphanGf('g1'), orphanGf('g2')]))

    await store.restoreSuspendedSnapshot()

    expect(visiblePendingIds(store.messages)).toEqual(['g1'])
    expect(store.activeForm).toMatchObject({ toolCallId: 'g1', runId: 'run-m2b', status: 'filling' })

    await store.cancelGenerativeForm()

    // 下一张表单卡照常出现并武装（旧表已取消，不得挡新表）
    expect(visiblePendingIds(store.messages)).toEqual(['g2'])
    expect(store.activeForm).toMatchObject({ toolCallId: 'g2', runId: 'run-m2b', status: 'filling' })
    expect(store.notice).toBeNull()
  }, 15000)

  it('⑤ 上传交接卡：req 缺键重建 activeUploadHandoff（区域不在该页 ⇒ missing）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-m2b-upload'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([{
      toolCallId: 'u1',
      entryStatus: 'PENDING',
      kind: 'FRONTEND',
      name: 'ui_request_upload',
      arguments: JSON.stringify({ ref: 'import.upload', accept: ['.xlsx'] })
    }]))

    await store.restoreSuspendedSnapshot()

    expect(visiblePendingIds(store.messages)).toEqual(['u1'])
    // 区域未注册（刷新后不在导入页）⇒ 只能放弃；卡与交接态仍照常重建
    expect(store.activeUploadHandoff).toMatchObject({ toolCallId: 'u1', status: 'missing' })
  }, 15000)

  it('⑥ 活轮不变：req=true 才重建；req=false 等实时帧，不提前出卡', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-m2b-live'
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue(snapshot([
      orphanConfirm('c1', { req: true }),
      orphanConfirm('c2', { req: false })
    ]))

    await store.restoreSuspendedSnapshot()
    expect(visiblePendingIds(store.messages)).toEqual(['c1'])

    await store.confirm(true, undefined, 'c1')
    // 卡 2 的请求帧还没发：不得凭快照提前画卡（与升级前实时路径一致）
    expect(visiblePendingIds(store.messages)).toEqual([])
    expect(store.notice).toBeNull()

    const handlers = api.reattachRun.mock.calls.at(-1)?.[1] as {
      onFrame: (frame: Record<string, unknown>) => void
    }
    handlers.onFrame({
      type: 'confirm_request',
      runId: 'run-m2b',
      toolCallId: 'c2',
      name: 'start_export',
      args: '{"taskId":2}',
      seq: 10
    })
    expect(visiblePendingIds(store.messages)).toEqual(['c2'])
  }, 15000)
})
