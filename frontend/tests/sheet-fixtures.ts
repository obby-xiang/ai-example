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
  calls: { getCell: number; getArray: number }
  setSelection(selection: SheetSelection | null): void
  setReadOnly(readOnly: boolean): void
  /** 模拟程序化载入（同表重载）：新一代，旧预览必须失效。 */
  reload(): void
  /** 模拟换文件/引擎重建：实例身份也变。 */
  replaceInstance(): void
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
  sheetInstanceId?: string
  loadGeneration?: number
}): FakeSheet {
  const rows = input.rows.map((row) => ({ ...row }))
  const writes: SheetCellUpdate[][] = []
  const calls = { getCell: 0, getArray: 0 }
  let selection: SheetSelection | null = input.selection ?? null
  let readOnly = input.readOnly ?? false
  let sheetInstanceId = input.sheetInstanceId ?? nextFakeInstanceId()
  let loadGeneration = input.loadGeneration ?? 0

  const cellAt = (row: number, col: number): unknown => rows[row]?.[input.fields[col]?.code ?? '']

  const adapter: SheetAdapter = {
    rowCount: () => rows.length,
    colCount: () => input.fields.length,
    header: (col) => input.headers?.[col] ?? input.fields[col]?.label ?? '',
    getCell: (row, col) => {
      calls.getCell += 1
      return cellAt(row, col)
    },
    getArray: (row, col, rowCount, colCount) => {
      calls.getArray += 1
      const grid: unknown[][] = []
      for (let r = 0; r < rowCount; r += 1) {
        const line: unknown[] = []
        for (let c = 0; c < colCount; c += 1) {
          line.push(cellAt(row + r, col + c))
        }
        grid.push(line)
      }
      return grid
    },
    writeCells: (updates) => {
      writes.push(updates.map((update) => ({ ...update })))
      const applied: SheetCellUpdate[] = []
      for (const update of updates) {
        const code = input.fields[update.col]?.code
        if (code && rows[update.row]) {
          rows[update.row][code] = update.value
          applied.push({ ...update })
        }
      }
      // 新契约（复审 I-1）：回报实际落笔（行不存在 / 列无字段的不算）
      return { applied }
    },
    selection: () => selection,
    instanceId: () => sheetInstanceId,
    loadGeneration: () => loadGeneration
  }

  const ctx: SheetContext = {
    defCode: input.defCode ?? 'DEMO',
    sheetName: input.sheetName ?? '演示表',
    readOnly,
    adapter,
    fields: input.fields,
    version: input.version ?? 1,
    sheetInstanceId,
    loadGeneration
  }

  return {
    ctx,
    rows,
    writes,
    calls,
    setSelection: (next) => {
      selection = next
    },
    setReadOnly: (next) => {
      readOnly = next
    },
    reload: () => {
      loadGeneration += 1
      ctx.loadGeneration = loadGeneration
    },
    replaceInstance: () => {
      sheetInstanceId = nextFakeInstanceId()
      loadGeneration += 1
      ctx.sheetInstanceId = sheetInstanceId
      ctx.loadGeneration = loadGeneration
    }
  }
}

let fakeInstanceSequence = 0

function nextFakeInstanceId(): string {
  fakeInstanceSequence += 1
  return `fake-sheet-${fakeInstanceSequence}`
}
