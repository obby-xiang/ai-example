/**
 * 测试扩充 V5 · B12 预览高亮的**收口矩阵**（L-23 落的是机制，本批锁"什么情况下必须消失"）。
 *
 * 既有 15 例（`sheet-preview-highlight.spec.ts`）锁的是：建卡/切档上色、切回计数档/取消/应用/停止清色。
 * 本批补的是**上下文离开**的四条路径 —— 预览是"这一屏、这一页、这一会话"里的事，离开任一层
 * 都不得把浅琥珀留在屏幕上：
 *
 * | 场景 | 期望 |
 * | --- | --- |
 * | 换选区（选区作用域的计划） | 计划失效（stale）+ 预览色清空 |
 * | 换选区（整表/条件作用域的计划） | **不误伤**：选区变化与计划无关，高亮保留 |
 * | 换页签（另一张表） | 计划失效 + 预览色清空；切回同一张表不清 |
 * | 离开页面 | 计划失效 + 预览色清空 |
 * | 换会话 | 预览连同会话一起丢弃，预览色清空 |
 * | 停止本轮 | 已在 P-05 锁；本批只做回归位（不重复计数） |
 *
 * 收口函数 = store 的 `dropSheetPreview()`：只清 `preview` 色调、不动已应用色
 * （撤销链的浅蓝由 `clearMarks()` 全清，两者色调分工不混）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { planReplace, type SheetContext } from '@/utils/sheet-tools'
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
  }
  target.ctx.adapter.clearMarks = (tone) => {
    clears.push(tone)
  }
}

function armWrite(
  store: ReturnType<typeof useAiStore>,
  args: Record<string, unknown>,
  toolCallId = 't1'
): void {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const message: ChatMessage = store.appendAssistantMessage()
  const frame: Extract<SseFrame, { type: 'frontend_tool_request' }> = {
    type: 'frontend_tool_request', toolCallId, name: 'replace_values',
    args: JSON.stringify(args), runId: 'run-1', seq: 2
  }
  applyFrameToParts(message, frame)
}

const sheetScopeArgs = {
  defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff'
}
const selectionScopeArgs = {
  defCode: 'DEMO', scope: 'selection', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff'
}

async function openPreview(
  store: ReturnType<typeof useAiStore>,
  args: Record<string, unknown>
): Promise<void> {
  armWrite(store, args)
  const message = store.messages[0]!
  const frame = {
    type: 'frontend_tool_request', toolCallId: 't1', name: 'replace_values',
    args: JSON.stringify(args), runId: 'run-1', seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  await store.openWritePreview(message, frame)
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
    ],
    // 选区路径：第 1–2 数据行 × 第 1–3 列（含表头偏移由适配器自己折算）
    selection: { row: 1, col: 0, rowCount: 2, colCount: 3 }
  })
  marks = []
  clears = []
  installSheet()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
})

describe('B12-01 换会话：预览随会话一起丢弃', () => {
  it('adoptFreshSession ⇒ 预览色清空、activePreview 归零、旧失效标记不跨会话', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)
    expect(store.activePreview).not.toBeNull()
    expect(marks).toHaveLength(1)

    store.stalePreviewToolCallId = 'old-stale'
    const nextId = store.adoptFreshSession()

    expect(clears).toContain('preview')
    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBeNull()
    expect(nextId).not.toBe('')
  })
})

describe('B12-02 换页签：换到另一张表才收口，切回同一张不误伤', () => {
  it('onSheetTabChanged(其它 defCode) ⇒ 预览失效 + 预览色清空', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)

    store.onSheetTabChanged('OTHER')
    expect(clears).toContain('preview')
    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBe('t1')
  })

  it('onSheetTabChanged(同一 defCode) ⇒ 计划与高亮都保留', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)
    const plan = store.activePreview

    store.onSheetTabChanged('DEMO')
    expect(clears).not.toContain('preview')
    expect(store.activePreview).toBe(plan)
    expect(store.stalePreviewToolCallId).toBeNull()
  })
})

describe('B12-03 换选区：只有「按选区算出来的计划」才失效', () => {
  it('选区作用域的计划：选区变化 ⇒ 计划失效 + 预览色清空（旧高亮不再指向新选区）', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, selectionScopeArgs)
    expect(store.activePreview?.scope).toBe('selection')

    store.onSheetSelectionChanged('DEMO')
    expect(clears).toContain('preview')
    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBe('t1')
  })

  it('整表作用域的计划：选区变化与计划无关 ⇒ 预览与高亮原样保留', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)
    const plan = store.activePreview
    expect(plan?.scope).toBe('sheet')

    store.onSheetSelectionChanged('DEMO')
    expect(clears).not.toContain('preview')
    expect(store.activePreview).toBe(plan)
    expect(store.stalePreviewToolCallId).toBeNull()
  })

  it('选区变化来自另一张表 ⇒ 与本计划的选区无关（defCode 不匹配即忽略）', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, selectionScopeArgs)

    store.onSheetSelectionChanged('OTHER')
    expect(clears).not.toContain('preview')
    expect(store.activePreview).not.toBeNull()
  })
})

describe('B12-04 离开页面：预览不再可达，收口为「预览已失效」', () => {
  it('onSheetPageLeft ⇒ 预览色清空 + 计划失效（卡面转「放弃」入口）', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)

    store.onSheetPageLeft()
    expect(clears).toContain('preview')
    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBe('t1')
  })

  it('没有预览时离开页面是 no-op（不产生任何适配器调用 / 失效标记）', () => {
    instrument(sheet)
    const store = useAiStore()

    store.onSheetPageLeft()
    expect(clears).toHaveLength(0)
    expect(store.stalePreviewToolCallId).toBeNull()
  })
})

describe('B12-05 停止本轮（回归位）：预览色仍随本轮放弃而清空', () => {
  it('stop() ⇒ 预览色清空且 activePreview 归零', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)

    await store.stop()
    expect(clears).toContain('preview')
    expect(store.activePreview).toBeNull()
  })
})

describe('B12-06 收口只清预览色，不碰已应用色', () => {
  it('dropSheetPreview 调用 clearMarks("preview")，绝不以无参全清（撤销链的浅蓝要留着）', async () => {
    instrument(sheet)
    const store = useAiStore()
    await openPreview(store, sheetScopeArgs)

    store.onSheetPageLeft()
    expect(clears).toEqual(['preview'])
    expect(clears).not.toContain(undefined)
  })
})

// 计划构造器冒烟：确保上面用到的两个作用域在纯函数层就是可分辩的（不依赖 store 私有推断）
describe('B12-07 计划携带作用域：scope 字段可判', () => {
  it('replace_values 的 sheet / selection 两档计划各自带 scope', () => {
    const ctx: SheetContext = sheet.ctx
    const sheetPlan = planReplace(ctx, sheetScopeArgs, 'p-sheet')
    const selectionPlan = planReplace(ctx, selectionScopeArgs, 'p-selection')
    if (!sheetPlan.ok || !selectionPlan.ok) throw new Error('计划构造失败')
    expect(sheetPlan.plan.scope).toBe('sheet')
    expect(selectionPlan.plan.scope).toBe('selection')
  })
})
