/**
 * AI 表格操作专项：测试用的假表格适配器（不拖 SpreadJS 进 jsdom）。
 *
 * 行列口径与 `utils/sheet-tools.ts` 一致：行 = 数据行下标（0 基，Excel 行号 = 下标 + 2），
 * 列 = 字段顺序（0 基）。
 */
import type { ConfigField } from '@/types/definition'
import type { SheetAdapter, SheetCellUpdate, SheetContext, SheetSelection } from '@/utils/sheet-tools'

export interface FakeSheet {
  ctx: SheetContext
  rows: Array<Record<string, unknown>>
  writes: SheetCellUpdate[][]
  setSelection(selection: SheetSelection | null): void
  setReadOnly(readOnly: boolean): void
}

export function field(
  code: string,
  label: string,
  extra: Partial<ConfigField> = {}
): ConfigField {
  return {
    defCode: 'DEMO',
    code,
    label,
    fieldType: 'STRING',
    required: false,
    key: false,
    sortOrder: 0,
    ...extra
  }
}

export function makeFakeSheet(input: {
  fields: readonly ConfigField[]
  rows: Array<Record<string, unknown>>
  headers?: string[]
  selection?: SheetSelection | null
  readOnly?: boolean
  defCode?: string
  sheetName?: string
  version?: number
}): FakeSheet {
  const rows = input.rows.map((row) => ({ ...row }))
  const writes: SheetCellUpdate[][] = []
  let selection: SheetSelection | null = input.selection ?? null
  let readOnly = input.readOnly ?? false

  const adapter: SheetAdapter = {
    rowCount: () => rows.length,
    colCount: () => input.fields.length,
    header: (col) => input.headers?.[col] ?? input.fields[col]?.label ?? '',
    getCell: (row, col) => rows[row]?.[input.fields[col]?.code ?? ''],
    getArray: (row, col, rowCount, colCount) => {
      const grid: unknown[][] = []
      for (let r = 0; r < rowCount; r += 1) {
        const line: unknown[] = []
        for (let c = 0; c < colCount; c += 1) {
          line.push(adapter.getCell(row + r, col + c))
        }
        grid.push(line)
      }
      return grid
    },
    writeCells: (updates) => {
      writes.push(updates.map((update) => ({ ...update })))
      for (const update of updates) {
        const code = input.fields[update.col]?.code
        if (code && rows[update.row]) {
          rows[update.row][code] = update.value
        }
      }
    },
    selection: () => selection
  }

  return {
    ctx: {
      defCode: input.defCode ?? 'DEMO',
      sheetName: input.sheetName ?? '演示表',
      readOnly,
      adapter,
      fields: input.fields,
      version: input.version ?? 1
    },
    rows,
    writes,
    setSelection: (next) => {
      selection = next
    },
    setReadOnly: (next) => {
      readOnly = next
    }
  }
}
