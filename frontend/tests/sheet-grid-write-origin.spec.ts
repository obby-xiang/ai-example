/**
 * 表格写入来源标注回归锁（2026-10-29）。
 *
 * 旧实现里 `writeCells`（AI 应用预览的落笔点）与用户手改发的是**同一个** `data-changed`
 * 事件、且不带任何来源：页面只能把 AI 的写入记成"用户改了表格"（工作区 source=界面），
 * 于是下一轮上下文/`start_export` 判脏都会说成"用户有未保存编辑"。
 *
 * 新口径：事件带来源（`ai` / `user`），页面据此把工作区动作记给 `AI`。
 * 同时适配器提供**表内内容版本**（`contentVersion`）：载入 / AI 写入 / 用户手改各 +1，
 * 与工作区全局 `dataVersion` 无关。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

vi.mock('@/utils/spreadjs', () => ({
  loadSpreadCore: () => Promise.resolve(fakeGc()),
  loadSpreadExcelIO: () => new Promise(() => {})
}))

const bindings = new Map<string, (payload?: unknown) => void>()
let sheetRows: unknown[][] = []
/** 故障注入：第 N 次 setArray 抛错（1 基）；null = 不注入。 */
let failSetArrayOnCall: number | null = null
let setArrayCalls = 0
/** 故障注入：下一次 resumePaint 抛错（只抛一次）。 */
let resumePaintFailure: Error | null = null

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
    resumePaint: () => {
      if (resumePaintFailure !== null) {
        const error = resumePaintFailure
        resumePaintFailure = null
        throw error
      }
    },
    setRowCount: () => {},
    setColumnCount: () => {},
    setColumnWidth: () => {},
    getRowCount: () => sheetRows.length,
    getColumnCount: () => (sheetRows[0] ?? []).length,
    getCell: (_row: number, _col: number) => ({
      formatter: () => {},
      validator: () => {},
      backColor: () => undefined
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
      setArrayCalls += 1
      if (failSetArrayOnCall !== null && setArrayCalls === failSetArrayOnCall) {
        throw new Error('setArray 失败')
      }
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

async function mountGrid(): Promise<ReturnType<typeof mount>> {
  const { default: SpreadGrid } = await import('@/components/SpreadGrid.vue')
  const wrapper = mount(SpreadGrid, { props: { defCode: 'DEMO', sheetName: '演示表' } })
  await new Promise((resolve) => setTimeout(resolve, 0))
  return wrapper
}

interface WriteResult {
  applied: Array<{ row: number; col: number; value: unknown }>
  error?: unknown
}

type GridApi = {
  getSheetAdapter: () => {
    writeCells: (updates: Array<{ row: number; col: number; value: unknown }>) => WriteResult
    contentVersion?: () => number
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  bindings.clear()
  failSetArrayOnCall = null
  setArrayCalls = 0
  resumePaintFailure = null
  sheetRows = [
    ['code', 'name', 'status'],
    ['CNY', '人民币', '启用'],
    ['USD', '美元', '停用']
  ]
})

describe('R1 data-changed 带写入来源', () => {
  it('适配器 writeCells（AI 应用）⇒ 事件来源 ai', async () => {
    const wrapper = await mountGrid()
    const grid = wrapper.vm as unknown as GridApi

    grid.getSheetAdapter().writeCells([{ row: 1, col: 2, value: '禁用' }])

    const events = wrapper.emitted('data-changed')
    expect(events?.at(-1)).toEqual(['ai'])
    wrapper.unmount()
  })

  it('用户手改（CellChanged 事件）⇒ 事件来源 user', async () => {
    const wrapper = await mountGrid()

    bindings.get('CellChanged')?.({})

    const events = wrapper.emitted('data-changed')
    expect(events?.at(-1)).toEqual(['user'])
    wrapper.unmount()
  })
})

describe('R2 表内内容版本 contentVersion', () => {
  it('AI 写入与用户手改各 +1；工作区 dataVersion 变化不影响它', async () => {
    const wrapper = await mountGrid()
    const grid = wrapper.vm as unknown as GridApi
    const adapter = grid.getSheetAdapter()
    const start = adapter.contentVersion?.() ?? 0

    useWorkspaceStore().bumpVersion()
    useWorkspaceStore().bumpVersion()
    expect(adapter.contentVersion?.() ?? 0).toBe(start)

    adapter.writeCells([{ row: 1, col: 2, value: '禁用' }])
    expect(adapter.contentVersion?.() ?? 0).toBe(start + 1)

    bindings.get('CellChanged')?.({})
    expect(adapter.contentVersion?.() ?? 0).toBe(start + 2)
    wrapper.unmount()
  })
})

describe('R3 writeCells 的落笔回报（复审 I-1 / Minor-7）', () => {
  it('逐列落笔：第二列的 setArray 抛错 ⇒ 返回第一列已落部分 + error（不 throw）', async () => {
    const wrapper = await mountGrid()
    const grid = wrapper.vm as unknown as GridApi
    failSetArrayOnCall = 2

    const result = grid.getSheetAdapter().writeCells([
      { row: 1, col: 2, value: '禁用' },
      { row: 1, col: 1, value: '人民币-改' }
    ])

    expect(result.error).toBeInstanceOf(Error)
    expect(result.applied).toEqual([{ row: 1, col: 2, value: '禁用' }])
    // 已落的那一列真的进了表格（视口第 0 行是表头 ⇒ 数据行 1 = sheetRows[2]）
    expect(sheetRows[2]?.[2]).toBe('禁用')
    expect(sheetRows[2]?.[1]).toBe('美元')
    wrapper.unmount()
  })

  it('resumePaint 抛错不残留 restoring：随后用户手改仍发 data-changed', async () => {
    const wrapper = await mountGrid()
    const grid = wrapper.vm as unknown as GridApi
    resumePaintFailure = new Error('resumePaint 失败')

    const result = grid.getSheetAdapter().writeCells([{ row: 1, col: 2, value: '禁用' }])
    // 绘制失败不是写入失败：值已落，error 不设（或至少 applied 如实）
    expect(result.applied).toHaveLength(1)

    bindings.get('CellChanged')?.({})
    const events = wrapper.emitted('data-changed')
    expect(events?.at(-1)).toEqual(['user'])
    wrapper.unmount()
  })
})
