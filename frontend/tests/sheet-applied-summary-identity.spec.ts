/**
 * L-74（真机批①）：「AI 改动 N 格」摘要条从不渲染 —— 适配器身份不稳定的回归锁。
 *
 * <h2>缺陷面</h2>
 * `sheet-tools.ts` 的撤销栈是 `WeakMap<SheetAdapter, …>`，`sheetAppliedSummary(ctx)` /
 * `sheetUndoDepth(adapter)` 都按**适配器对象身份**取栈。而页面能力 `sheet.current`
 * 每次解析都现调 `SpreadGrid#getSheetAdapter()`；旧实现每次返回**新对象** ⇒
 * 应用时入的那一栈，下一次解析就取不到：
 * <ol>
 * <li>应用后紧跟着 `data-changed` ⇒ 视图调 `refreshSheetApplied()` ⇒ 摘要被算成 null，条子从不出现；</li>
 * <li>`undo_last_sheet_edit` 走 `resolveSheetContext()` 拿到新适配器 ⇒ 栈空 ⇒ 如实但错误地回「没有可撤销」。</li>
 * </ol>
 *
 * <h2>为什么既有用例全绿</h2>
 * `sheet-preview-apply-undo.spec.ts` 等的 `sheet.current` 桩返回**同一个** `sheet.ctx.adapter`，
 * 身份恒定 —— 恰好吃掉了这条缺陷。本文件用**真 `SpreadGrid` 组件**装表格（只有引擎是假的），
 * 页面能力按视图的写法每次现调 `getSheetAdapter()`，端到端走 store 的预览 → 应用 → 撤销。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { resolveSheetContext } from '@/utils/frontend-tools'
import { applyFrameToParts } from '@/utils/parts'
import { sheetUndoDepth } from '@/utils/sheet-tools'
import { field } from './sheet-fixtures'

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

/** 假引擎：只提供适配器要用到的那几个面（视口第 0 行 = 表头，数据从第 1 行起）。 */
const bindings = new Map<string, (payload?: unknown) => void>()
let sheetRows: unknown[][] = []
const backColors = new Map<string, unknown>()

vi.mock('@/utils/spreadjs', () => ({
  loadSpreadCore: () => Promise.resolve(fakeGc()),
  loadSpreadExcelIO: () => new Promise(() => {})
}))

function setValueAt(row: number, col: number, value: unknown): void {
  const line = sheetRows[row] ?? []
  line[col] = value
  sheetRows[row] = line
}

function fakeSheet(): Record<string, unknown> {
  return {
    name: () => 'Sheet1',
    options: { isProtected: false },
    bind: (event: string, handler: (payload?: unknown) => void) => {
      bindings.set(event, handler)
    },
    suspendPaint: () => {},
    resumePaint: () => {},
    setRowCount: () => {},
    setColumnCount: () => {},
    setColumnWidth: () => {},
    getRowCount: () => sheetRows.length,
    getColumnCount: () => (sheetRows[0] ?? []).length,
    getCell: (row: number, col: number) => ({
      formatter: () => {},
      validator: () => {},
      backColor: (color?: unknown) => {
        const key = `${row}:${col}`
        if (color === undefined) {
          return backColors.get(key)
        }
        backColors.set(key, color)
        return color
      }
    }),
    getValue: (row: number, col: number) => sheetRows[row]?.[col] ?? null,
    getArray: (row: number, col: number, rows: number, cols: number) => {
      const grid: unknown[][] = []
      for (let r = 0; r < rows; r += 1) {
        const line: unknown[] = []
        for (let c = 0; c < cols; c += 1) {
          line.push(sheetRows[row + r]?.[col + c] ?? null)
        }
        grid.push(line)
      }
      return grid
    },
    setArray: (row: number, col: number, values: unknown[][]) => {
      values.forEach((line, r) => line.forEach((value, c) => setValueAt(row + r, col + c, value)))
    },
    getSelections: () => []
  }
}

function fakeGc(): unknown {
  const sheet = fakeSheet()
  class Workbook {
    fromJSON(): void {}
    toJSON(): string {
      return '{}'
    }
    getActiveSheet(): unknown {
      return sheet
    }
    getSheetCount(): number {
      return 1
    }
    getSheet(): unknown {
      return sheet
    }
    refresh(): void {}
    destroy(): void {}
  }
  return {
    Spread: {
      Sheets: {
        Workbook,
        Events: {
          CellChanged: 'CellChanged',
          RangeChanged: 'RangeChanged',
          RowChanged: 'RowChanged',
          SelectionChanged: 'SelectionChanged'
        },
        DataValidation: {},
        ConditionalFormatting: { ComparisonOperators: {} },
        SheetArea: { viewport: 0 }
      }
    }
  }
}

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('name', '名称'),
  field('status', '状态')
]

type GridWrapper = ReturnType<typeof mount>

/** 挂真 SpreadGrid（引擎假），并按视图的写法注册 `sheet.current`。 */
async function mountGrid(): Promise<GridWrapper> {
  const { default: SpreadGrid } = await import('@/components/SpreadGrid.vue')
  const wrapper = mount(SpreadGrid, { props: { defCode: 'DEMO', sheetName: '演示表' } })
  await new Promise((resolve) => setTimeout(resolve, 0))
  const grid = wrapper.vm as unknown as { getSheetAdapter: () => unknown }
  registerPageHandler('sheet.current', (payload: Record<string, unknown>) => {
    const code = String(payload.defCode ?? '')
    if (code !== 'DEMO') {
      return { ok: false, reason: `当前激活的表格不是 ${code}` }
    }
    return {
      ok: true,
      defCode: 'DEMO',
      sheetName: '演示表',
      readOnly: false,
      version: useWorkspaceStore().dataVersion,
      fields,
      adapter: grid.getSheetAdapter()
    }
  })
  return wrapper
}

const step1Args = {
  defCode: 'DEMO',
  scope: 'sheet',
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用',
  preview: 'sample-diff'
}

/** 走 store 的正式入口：预览 → 应用（与 `SheetPreviewCard` 的「应用」同一个 action）。 */
async function previewAndApply(store: ReturnType<typeof useAiStore>): Promise<ChatMessage> {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request',
    toolCallId: 't1',
    name: 'replace_values',
    args: JSON.stringify(step1Args),
    runId: 'run-1',
    seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  await store.openWritePreview(message, frame)
  await store.confirmWritePreview()
  return message
}

let grid: GridWrapper

/** 按视图的写法取一次适配器（每次都现调 `getSheetAdapter()`）。 */
function currentAdapter(): unknown {
  return (grid.vm as unknown as { getSheetAdapter: () => unknown }).getSheetAdapter()
}

beforeEach(async () => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  bindings.clear()
  backColors.clear()
  sheetRows = [
    ['code', 'name', 'status'],
    ['CNY', '人民币', '启用'],
    ['USD', '美元', '停用'],
    ['CNH', '离岸', '停用']
  ]
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
  api.health.mockResolvedValue({
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
    memoryBackend: 'redis',
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  grid = await mountGrid()
})

afterEach(() => {
  grid?.unmount()
})

describe('L-74 适配器身份：同一张表上重复解析必须拿到同一个适配器', () => {
  it('getSheetAdapter() 连调两次返回同一对象（撤销栈按身份存取的前提）', () => {
    expect(currentAdapter()).toBe(currentAdapter())
  })
})

describe('L-74 预览 → 应用 → 摘要：页面重解析上下文后「AI 改动 N 格」仍在', () => {
  it('应用后视图重算摘要（refreshSheetApplied）⇒ 格数/层号/可撤销层数不丢', async () => {
    const store = useAiStore()
    await previewAndApply(store)
    expect(sheetRows.map((row) => row[2])).toEqual(['status', '启用', '禁用', '禁用'])

    // 视图侧 onGridChanged 的实况：表格变了就重算一次摘要
    await store.refreshSheetApplied('DEMO')

    expect(store.sheetApplied).not.toBeNull()
    expect(store.sheetApplied).toMatchObject({
      tool: 'replace_values',
      defCode: 'DEMO',
      cells: 2,
      rows: 2,
      layer: 1,
      undoDepth: 1,
      manualChanges: 0
    })
  })

  it('应用后重新解析上下文 ⇒ 撤销深度读数为 1（不是 0）', async () => {
    const store = useAiStore()
    await previewAndApply(store)

    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return
    expect(sheetUndoDepth(resolved.ctx.adapter)).toBe(1)
  })
})

describe('L-74 撤销：重解析上下文后仍能撤回这一批（且必须经预览卡确认）', () => {
  it('undo_last_sheet_edit 帧 → 建卡 → 点应用 ⇒ 值回到「停用」且报剩余 0 层', async () => {
    const store = useAiStore()
    await previewAndApply(store)

    store.handleFrame({ type: 'start', runId: 'run-undo', sessionId: store.sessionId, seq: 1 })
    const message = store.appendAssistantMessage()
    const frame = {
      type: 'frontend_tool_request',
      toolCallId: 'u1',
      name: 'undo_last_sheet_edit',
      args: JSON.stringify({ defCode: 'DEMO' }),
      runId: 'run-undo',
      seq: 2
    } as Extract<SseFrame, { type: 'frontend_tool_request' }>
    applyFrameToParts(message, frame)
    await store.openWritePreview(message, frame)
    // 工具安全边界批：建卡 ≠ 执行 —— 没点「应用」前表格不动
    expect(store.activePreview?.tool).toBe('undo_last_sheet_edit')
    expect(sheetRows.map((row) => row[2])).toEqual(['status', '启用', '禁用', '禁用'])

    await store.confirmWritePreview()
    const posted = api.frontendToolResult.mock.calls.at(-1)?.[0] as { result?: string } | undefined
    expect(posted?.result).toContain('已撤销 2 格')
    expect(posted?.result).toContain('剩余可撤销 0 层')
    expect(sheetRows.map((row) => row[2])).toEqual(['status', '启用', '停用', '停用'])
  })
})

describe('L-74 卡面：摘要条真的出现在 DOM 里', () => {
  it('应用 → 重算摘要 → 面板渲染「AI 改动 2 格（可撤销，深度 3 中第 1 层）」', async () => {
    const store = useAiStore()
    await previewAndApply(store)
    await store.refreshSheetApplied('DEMO')
    store.historyLoaded = true

    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, {
      global: {
        stubs: {
          'el-alert': true,
          'el-button': { template: '<button><slot /></button>' },
          'el-empty': true,
          'el-icon': { template: '<i><slot /></i>' },
          'el-input': true,
          'el-skeleton': true,
          'el-tag': { template: '<span class="tag"><slot /></span>' },
          FormRenderer: true
        }
      }
    })
    await wrapper.vm.$nextTick()
    await flushPromises()

    const bar = wrapper.find('[data-testid="sheet-applied-summary"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('AI 改动 2 格（可撤销，深度 3 中第 1 层）')
    wrapper.unmount()
  }, 30_000)
})
