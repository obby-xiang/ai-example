/**
 * 测试扩充 V5 · B13/D7 预览 → 应用 → 撤销的**跨层闭环**（L-21 卡面链路的浏览器侧半段）。
 *
 * 三段证据串成一条链，而不是各测各的：
 *
 * 1. **预览 → 应用**：`frontend_tool_request` 建卡 ⇒ 明细档上预览色（零写）⇒ 点应用 ⇒
 *    预览色先撤、`writeCells` 真写、已应用色上屏、回灌 `succeeded` + `sheetMeta`；
 * 2. **应用 → 撤销**：`undo_last_sheet_edit` 走**人围栏**（`frontend_tool_request` → 预览卡 → 点应用；
 *    工具安全边界批 2026-10-25：撤销会覆盖表格数据，未经确认不执行）⇒ 反向补丁写回、
 *    回执报「剩余可撤销 N 层」；
 * 3. **深度 3 → 空**：连做三次应用后连撤三次逐层回到对应前态，第 4 次如实回「没有可撤销」，
 *    每次撤销后已应用色全清（`clearMarks()` 无参），不留浅蓝残影。
 *
 * 导出字节一致性：桩/假表不产出 xlsx（`SpreadGrid#toXlsxBlob` 依赖 SpreadJS 引擎），
 * 本层**不可承载** ⇒ 不在此断言，随 L-21 卡面 DOM 半段留批③（Playwright / 真引擎）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { resolveSheetContext } from '@/utils/frontend-tools'
import { applyUndoPlan, planUndo, sheetUndoDepth } from '@/utils/sheet-tools'
import { field, makeFakeSheet, type FakeSheet } from './sheet-fixtures'

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

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('name', '名称'),
  field('status', '状态')
]

let sheet: FakeSheet
let marks: Array<{ cells: Array<{ row: number; col: number }>; tone?: string }>
let clears: Array<string | undefined>
let events: string[]

function installSheet(): void {
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: sheet.ctx.sheetName,
    readOnly: false,
    version: sheet.ctx.version,
    fields: sheet.ctx.fields,
    adapter: sheet.ctx.adapter
  }))
}

function instrument(target: FakeSheet): void {
  target.ctx.adapter.markCells = (cells, tone) => {
    marks.push({ cells: cells.map((cell) => ({ ...cell })), tone })
    events.push(`mark:${tone ?? 'applied'}`)
  }
  target.ctx.adapter.clearMarks = (tone) => {
    clears.push(tone)
    events.push(`clear:${tone ?? 'all'}`)
  }
}

let toolSeq = 0

/** 走 store 的正式入口：建卡（预览）→ 应用。 */
async function previewAndApply(
  store: ReturnType<typeof useAiStore>,
  args: Record<string, unknown>
): Promise<string> {
  toolSeq += 1
  const toolCallId = `t${toolSeq}`
  store.handleFrame({ type: 'start', runId: `run-${toolSeq}`, sessionId: store.sessionId, seq: 1 })
  const message: ChatMessage = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request', toolCallId, name: 'replace_values',
    args: JSON.stringify(args), runId: `run-${toolSeq}`, seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  await store.openWritePreview(message, frame)
  await store.confirmWritePreview()
  return toolCallId
}

/**
 * 走**确认面**撤销（工具安全边界批 2026-10-25：撤销会覆盖表格数据 ⇒ 必须经预览卡「应用」）：
 * 帧 → 建卡 → 点应用，回执文本取自回灌体。
 */
async function undo(): Promise<string> {
  const store = useAiStore()
  toolSeq += 1
  const toolCallId = `u${toolSeq}`
  store.handleFrame({ type: 'start', runId: `run-u${toolSeq}`, sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request', toolCallId, name: 'undo_last_sheet_edit',
    args: JSON.stringify({ defCode: 'DEMO' }), runId: `run-u${toolSeq}`, seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  api.frontendToolResult.mockClear()
  await store.openWritePreview(message, frame)
  if (store.activePreview?.toolCallId === toolCallId) {
    await store.confirmWritePreview()
  }
  const posted = api.frontendToolResult.mock.calls.at(-1)?.[0] as { result?: string } | undefined
  return posted?.result ?? ''
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  sheet = makeFakeSheet({
    fields,
    rows: [
      { code: 'CNY', name: '人民币', status: '启用' },
      { code: 'USD', name: '美元', status: '停用' },
      { code: 'CNH', name: '离岸', status: '停用' }
    ]
  })
  marks = []
  clears = []
  events = []
  toolSeq = 0
  installSheet()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

const step1Args = {
  defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff'
}

describe('B13-01 预览 → 应用：同一绘制入口的两种色，写只发生在应用之后', () => {
  it('预览期上预览色且零写；应用后预览色先撤、真写 2 格、已应用色上屏、回灌 succeeded', async () => {
    instrument(sheet)
    const store = useAiStore()

    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const message = store.appendAssistantMessage()
    const frame = {
      type: 'frontend_tool_request', toolCallId: 't1', name: 'replace_values',
      args: JSON.stringify(step1Args), runId: 'run-1', seq: 2
    } as Extract<SseFrame, { type: 'frontend_tool_request' }>
    applyFrameToParts(message, frame)

    await store.openWritePreview(message, frame)
    expect(marks).toHaveLength(1)
    expect(marks[0]!.tone).toBe('preview')
    expect(sheet.writes).toHaveLength(0)
    expect(api.frontendToolResult).not.toHaveBeenCalled()

    await store.confirmWritePreview()

    expect(clears).toContain('preview')
    expect(sheet.writes.length).toBeGreaterThan(0)
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '禁用', '禁用'])
    const applied = marks.filter((call) => call.tone === 'applied')
    expect(applied).toHaveLength(1)
    expect(applied[0]!.cells).toEqual([{ row: 1, col: 2 }, { row: 2, col: 2 }])
    // 顺序：预览色先撤，已应用色后上（同格不挂两种底色）
    expect(events.indexOf('clear:preview')).toBeLessThan(events.lastIndexOf('mark:applied'))

    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    const posted = api.frontendToolResult.mock.calls[0]![0] as { result: string; outcome?: string; sheetMeta?: { planId?: string } }
    expect(posted.outcome).toBe('ok')
    expect(posted.result).toContain('已应用')
    expect(posted.sheetMeta?.planId).toBeTruthy()
    expect(store.sheetApplied?.undoDepth).toBe(1)
  })
})

describe('B13-02 应用 → 撤销：走真实前端工具通道，写回前值并报剩余层数', () => {
  it('一次应用后撤销 ⇒ 值回到「停用」、回执「剩余可撤销 0 层」、已应用色全清', async () => {
    instrument(sheet)
    const store = useAiStore()
    await previewAndApply(store, step1Args)
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '禁用', '禁用'])

    const result = await undo()
    expect(result).toContain('已撤销 2 格')
    expect(result).toContain('剩余可撤销 0 层')
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '停用', '停用'])
    expect(clears.at(-1)).toBe('applied') // 撤销只清已应用色调（不误伤尚未应用的预览色）
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })
})

describe('B13-03 深度 3 → 空：三层逐层回退，第 4 次如实说「没有可撤销」', () => {
  it('三次应用 → 三次撤销逐层还原 → 第 4 次回空；每次撤销都清已应用色', async () => {
    instrument(sheet)
    const store = useAiStore()

    await previewAndApply(store, {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['name'], search: '美元', replace: '美元USD', preview: 'sample-diff'
    })
    await previewAndApply(store, {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['name'], search: '离岸', replace: '离岸CNH', preview: 'sample-diff'
    })
    await previewAndApply(store, step1Args)

    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(3)
    expect(store.sheetApplied?.layer).toBe(3)
    expect(sheet.rows.map((row) => [row.name, row.status])).toEqual([
      ['人民币', '启用'],
      ['美元USD', '禁用'],
      ['离岸CNH', '禁用']
    ])

    const clearCount = clears.length
    expect(await undo()).toContain('剩余可撤销 2 层')
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '停用', '停用'])
    expect(clears.length).toBeGreaterThan(clearCount)

    expect(await undo()).toContain('剩余可撤销 1 层')
    expect(sheet.rows.map((row) => row.name)).toEqual(['人民币', '美元USD', '离岸'])

    expect(await undo()).toContain('剩余可撤销 0 层')
    expect(sheet.rows.map((row) => row.name)).toEqual(['人民币', '美元', '离岸'])

    const empty = await undo()
    expect(empty).toContain('没有可撤销的 AI 修改')
    expect(empty).toContain('共 0 格')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })
})

describe('B13-04 预览色不参与撤销链：撤销只清「已应用」那一层留下的颜色', () => {
  it('另有一张待应用预览时，撤销请求被拒（不挤掉那张卡）、零写、预览色不被误伤', async () => {
    instrument(sheet)
    const store = useAiStore()
    await previewAndApply(store, step1Args)

    // 第二次预览只上色、不应用
    const before = clears.length
    store.handleFrame({ type: 'start', runId: 'run-9', sessionId: store.sessionId, seq: 1 })
    const message = store.appendAssistantMessage()
    const frame = {
      type: 'frontend_tool_request', toolCallId: 't9', name: 'replace_values',
      args: JSON.stringify({ ...step1Args, search: '启用', replace: '在运行' }), runId: 'run-9', seq: 2
    } as Extract<SseFrame, { type: 'frontend_tool_request' }>
    applyFrameToParts(message, frame)
    await store.openWritePreview(message, frame)
    expect(marks.at(-1)!.tone).toBe('preview')

    // 工具安全边界批：撤销卡不得静默覆盖一张仍在等人决策的预览卡
    const writesBefore = sheet.writes.length
    const result = await undo()
    expect(result).toContain('预览卡待你处理')
    expect(sheet.writes).toHaveLength(writesBefore)
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '禁用', '禁用'])
    // 预览计划仍在（用户可继续应用或取消），预览色不得被顺手抹掉
    expect(store.activePreview?.toolCallId).toBe('t9')
    expect(clears.length).toBe(before)
  })

  it('撤销落笔只清已应用色：预览色由预览计划自己管（纯函数面）', async () => {
    instrument(sheet)
    const store = useAiStore()
    await previewAndApply(store, step1Args)

    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return
    const planned = planUndo(resolved.ctx, { defCode: 'DEMO' }, 'undo-color')
    expect(planned.ok).toBe(true)
    if (!planned.ok) return

    const clearsBefore = clears.length
    const outcome = applyUndoPlan(resolved.ctx, planned.plan)
    expect(outcome.ok).toBe(true)
    expect(clears.slice(clearsBefore)).toEqual(['applied'])
    expect(clears).not.toContain(undefined)
  })
})
