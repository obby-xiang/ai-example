/**
 * L-23 预览态高亮（挂账销记面）：待改格在**预览期**就上屏幕底色，与已应用态共用
 * `SheetAdapter#markCells` 这一条绘制入口（AI 表格工具治理 §5.4 的"一次做两次用"）。
 *
 * 四条硬口径：
 * ① 预览期仍**零写**：只调 `markCells(cells, 'preview')`，`writeCells` 零次、`conditionalFormats` 不碰；
 * ② 收口齐全：取消 / 放弃 / 应用前置 / 切回计数档都清掉预览色，不留过期高亮；
 * ③ 计数档没有逐格明细 ⇒ 不上色（没有可核对的单元格，就不该有高亮）；
 * ④ 卡面逐格给 A1 地址（如 `B3`），让「我改了 B3」在卡上可核对；文案双 locale 对称。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, SheetWritePlan } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import {
  clearPreviewMarks,
  markPreviewCells,
  planReplace,
  replanSheet,
  type SheetContext
} from '@/utils/sheet-tools'
import SheetPreviewCard from '@/components/AiPanel/SheetPreviewCard.vue'
import zhCN from '@/locales/zh-CN.json'
import enUS from '@/locales/en-US.json'
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
  field('name', '名称', { required: false }),
  field('status', '状态', { required: false })
]

interface MarkCall {
  cells: Array<{ row: number; col: number }>
  tone?: string
}

let sheet: FakeSheet
let marks: MarkCall[]
let clears: Array<string | undefined>
let events: string[]

function installSheet(): void {
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: sheet.ctx.sheetName,
    readOnly: sheet.ctx.readOnly,
    version: sheet.ctx.version,
    fields: sheet.ctx.fields,
    adapter: sheet.ctx.adapter
  }))
}

function instrumentMarks(target: FakeSheet): void {
  target.ctx.adapter.markCells = (cells, tone) => {
    marks.push({ cells: cells.map((cell) => ({ ...cell })), tone })
    events.push(`mark:${tone ?? 'undefined'}`)
  }
  target.ctx.adapter.clearMarks = (tone) => {
    clears.push(tone)
    events.push(`clear:${tone ?? 'all'}`)
  }
}

function planOf(preview: 'count' | 'sample-diff' | 'full-diff' = 'sample-diff'): SheetWritePlan {
  const planned = planReplace(
    sheet.ctx,
    { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview },
    't1'
  )
  if (!planned.ok) throw new Error(planned.reason)
  return planned.plan
}

function armWrite(
  store: ReturnType<typeof useAiStore>,
  name: string,
  args: Record<string, unknown>,
  toolCallId = 't1'
): { message: ChatMessage; frame: Extract<SseFrame, { type: 'frontend_tool_request' }> } {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame: Extract<SseFrame, { type: 'frontend_tool_request' }> = {
    type: 'frontend_tool_request',
    toolCallId,
    name,
    args: JSON.stringify(args),
    runId: 'run-1',
    seq: 2
  }
  applyFrameToParts(message, frame)
  return { message, frame }
}

const replaceArgs = {
  defCode: 'DEMO',
  scope: 'sheet',
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用'
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
  installSheet()
  for (const engine of Object.values(api)) {
    engine.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

describe('P-01 绘制入口：预览色只走屏幕态', () => {
  it('待改格按 preview 色标记，预览期 writeCells 零次（不写 cell style）', () => {
    instrumentMarks(sheet)
    const plan = planOf('sample-diff')
    expect(plan.changes).toHaveLength(2)

    const marked = markPreviewCells(sheet.ctx, plan)

    expect(marked).toBe(2)
    expect(marks).toHaveLength(1)
    expect(marks[0]!.tone).toBe('preview')
    expect(marks[0]!.cells).toEqual([
      { row: 1, col: 2 },
      { row: 2, col: 2 }
    ])
    expect(sheet.writes).toHaveLength(0)
  })

  it('计数档没有逐格明细 ⇒ 不上色（哪怕带了 3 处样例，也不拿样例当待改格）', () => {
    instrumentMarks(sheet)
    const plan = planOf('count')
    expect(plan.detail).toBe('none')
    // 计数档的 changes 只是前 3 条样例（卡面"看样例"的承诺），不是待改全集 ⇒ 不能据此上色
    expect(plan.changes.length).toBeLessThanOrEqual(3)

    const marked = markPreviewCells(sheet.ctx, plan)

    expect(marked).toBe(0)
    expect(marks).toHaveLength(0)
    expect(clears).toEqual(['preview'])
  })

  it('clearPreviewMarks 只清预览色（已应用色仍由 clearMarks() 全清）', () => {
    instrumentMarks(sheet)
    clearPreviewMarks(sheet.ctx)
    expect(clears).toEqual(['preview'])
  })

  it('适配器没有标记能力时两个入口都是 no-op（可选能力，不是必需依赖）', () => {
    const plain = makeFakeSheet({ fields, rows: [{ code: 'CNY', name: '人民币', status: '停用' }] })
    expect(plain.ctx.adapter.markCells).toBeUndefined()
    const planned = planReplace(
      plain.ctx,
      { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff' },
      't2'
    )
    if (!planned.ok) throw new Error(planned.reason)

    expect(markPreviewCells(plain.ctx, planned.plan)).toBe(1)
    expect(() => clearPreviewMarks(plain.ctx)).not.toThrow()
    expect(plain.writes).toHaveLength(0)
  })
})

describe('P-02 store 收口：建卡 / 切档 / 取消 / 应用各自处理预览色', () => {
  it('切到明细档才上预览色；预览态零写、零回灌', async () => {
    instrumentMarks(sheet)
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', replaceArgs)

    await store.openWritePreview(message, frame)
    expect(store.activePreview?.preview).toBe('count')
    expect(marks).toHaveLength(0)

    await store.setPreviewLevel('sample-diff')
    expect(marks).toHaveLength(1)
    expect(marks[0]!.tone).toBe('preview')
    expect(marks[0]!.cells.length).toBe(2)
    expect(sheet.writes).toHaveLength(0)
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('切回计数档清掉预览色（不留没处核对的旧高亮）', async () => {
    instrumentMarks(sheet)
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', replaceArgs)

    await store.openWritePreview(message, frame)
    await store.setPreviewLevel('sample-diff')
    await store.setPreviewLevel('count')

    expect(clears).toContain('preview')
    expect(store.activePreview?.preview).toBe('count')
  })

  it('点取消 ⇒ 预览色清空 + 零写', async () => {
    instrumentMarks(sheet)
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', replaceArgs)

    await store.openWritePreview(message, frame)
    await store.setPreviewLevel('sample-diff')
    await store.cancelWritePreview()

    expect(clears).toContain('preview')
    expect(sheet.writes).toHaveLength(0)
    expect(store.activePreview).toBeNull()
  })

  it('点应用 ⇒ 先清预览色再上已应用色（同一套绘制入口、两种色）', async () => {
    instrumentMarks(sheet)
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', replaceArgs)

    await store.openWritePreview(message, frame)
    await store.setPreviewLevel('sample-diff')
    await store.confirmWritePreview()

    expect(clears).toContain('preview')
    const applied = marks.filter((call) => call.tone === 'applied')
    expect(applied).toHaveLength(1)
    expect(applied[0]!.cells).toHaveLength(2)
    // 顺序：清预览色在写入已应用色之前（同格不会同时挂两种底色）
    expect(events.indexOf('clear:preview')).toBeGreaterThanOrEqual(0)
    expect(events.indexOf('clear:preview')).toBeLessThan(events.lastIndexOf('mark:applied'))
  })
})

describe('P-03 卡面：逐格 A1 地址 + 双 locale 图例', () => {
  const samplePlan = {
    planId: 'p1',
    toolCallId: 't1',
    defCode: 'DEMO',
    tool: 'replace_values',
    level: 'small',
    preview: 'sample-diff',
    scopeSummary: 'DEMO 全表',
    predicateSummary: '',
    rowsCount: 2,
    cellsCount: 2,
    skippedCount: 0,
    samples: [
      { row: 1, col: 2, oldValue: '停用', newValue: '禁用' },
      { row: 2, col: 2, oldValue: '停用', newValue: '禁用' }
    ],
    changes: [
      { row: 1, col: 2, oldValue: '停用', newValue: '禁用' },
      { row: 2, col: 2, oldValue: '停用', newValue: '禁用' }
    ],
    targetRows: [1, 2],
    fingerprint: ['USD', 'CNH'],
    warnings: [],
    rulesText: '整格相等',
    status: 'pending',
    detail: 'full'
  } as SheetWritePlan

  it('采样档逐条给 A1 地址（第 2 数据行的第 3 列 ⇒ C3）', () => {
    const wrapper = mount(SheetPreviewCard, { props: { plan: samplePlan } })
    const chips = wrapper.findAll('[data-testid="sheet-preview-cell"]')
    expect(chips).toHaveLength(2)
    expect(chips[0]!.text()).toBe('C3')
    expect(chips[1]!.text()).toBe('C4')
    // 禁手写样式：只允许 Tailwind class
    expect(chips[0]!.attributes('style')).toBeUndefined()
    wrapper.unmount()
  })

  it('全量 diff 档同样逐格给地址', () => {
    const wrapper = mount(SheetPreviewCard, {
      props: { plan: { ...samplePlan, preview: 'full-diff' } }
    })
    const chips = wrapper.findAll('[data-testid="sheet-preview-cell"]')
    expect(chips).toHaveLength(2)
    expect(chips[0]!.text()).toBe('C3')
    wrapper.unmount()
  })

  it('明细档带图例（说明表里已临时标色、且不进文件）；计数档不渲染图例', () => {
    const sample = mount(SheetPreviewCard, { props: { plan: samplePlan } })
    const legend = sample.find('[data-testid="sheet-preview-highlight-legend"]')
    expect(legend.exists()).toBe(true)
    expect(legend.text()).toContain('临时标色')
    sample.unmount()

    const count = mount(SheetPreviewCard, {
      props: { plan: { ...samplePlan, preview: 'count', detail: 'none', changes: [], samples: [] } }
    })
    expect(count.find('[data-testid="sheet-preview-highlight-legend"]').exists()).toBe(false)
    count.unmount()
  })

  it('sheet.preview 子树在 zh-CN / en-US 键集完全一致（新图例不缺英文）', () => {
    const keys = (tree: Record<string, unknown>): string[] => Object.keys(tree).sort()
    expect(keys(enUS.sheet.preview as Record<string, unknown>)).toEqual(
      keys(zhCN.sheet.preview as Record<string, unknown>)
    )
    expect(zhCN.sheet.preview.highlightLegend).toBeTruthy()
    expect(enUS.sheet.preview.highlightLegend).toBeTruthy()
  })
})

describe('P-04 预览态高亮不改变计划语义', () => {
  it('切档重算后仍是同一 planId（高亮不新建计划、撤销绑定不断）', () => {
    instrumentMarks(sheet)
    const plan = planOf('count')
    const replanned = replanSheet(sheet.ctx, plan, 'sample-diff')
    if (!replanned.ok) throw new Error(replanned.reason)
    expect(replanned.plan.planId).toBe(plan.planId)
    expect(markPreviewCells(sheet.ctx, replanned.plan)).toBe(2)
    expect(marks[0]!.tone).toBe('preview')
  })
})

describe('P-05 停止本轮也清预览色', () => {
  it('stop() 收起预览 ⇒ 预览色随之清空（不留过期高亮）', async () => {
    instrumentMarks(sheet)
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', replaceArgs)
    await store.openWritePreview(message, frame)
    await store.setPreviewLevel('sample-diff')
    api.cancel.mockResolvedValue({})

    await store.stop()

    expect(clears).toContain('preview')
  })
})

describe('P-06 SheetContext 不因高亮而变', () => {
  it('标记调用不读单元格、不推进版本（屏幕态与数据面解耦）', () => {
    instrumentMarks(sheet)
    const ctx: SheetContext = sheet.ctx
    const plan = planOf('sample-diff')
    const before = { version: ctx.version, reads: sheet.calls.getCell, arrays: sheet.calls.getArray }
    markPreviewCells(ctx, plan)
    clearPreviewMarks(ctx)
    expect(ctx.version).toBe(before.version)
    expect(sheet.calls.getCell).toBe(before.reads)
    expect(sheet.calls.getArray).toBe(before.arrays)
  })
})
