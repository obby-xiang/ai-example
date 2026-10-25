/**
 * L-115（11b T 域串跑 flake）：工作表重建后适配器仍读**废弃的** worksheet —— 回归锁。
 *
 * <h2>缺陷面（真机 stack 直证）</h2>
 * `SpreadGrid` 的适配器缓存按 worksheet 对象身份存（L-74），但闭包捕获的是**建适配器那一刻**的
 * sheet 实例。`workbook.fromJSON()`（载入 xlsx / 模板 JSON）会**重建工作表集**：旧实例被引擎丢弃，
 * 其内部引用置空 —— 此时旧适配器的 `getRowCount()` 抛
 * `TypeError: Cannot read properties of null (reading 'getRowCount')`、
 * `getCell/getArray` 读回 `null`。11b 的 T02/T03 就是这样红的（整文件串跑复现，
 * 单跑因时序不同常绿）。
 *
 * <h2>第二个窗口</h2>
 * 页面把「表格位置」和「表格内容」当同一件事披露：canvas 一挂载 `sheet.current` 就可用，
 * 而 `loadFromXlsx/loadFromJson` 还在路上 ⇒ 工具读到空表（T05 的 0 行 / T13 的 0 组）。
 * `whenLoaded()` 是这条窗口的关闸：页面能力必须等它落定再交出适配器。
 *
 * <h2>为什么既有用例全绿</h2>
 * `sheet-applied-summary-identity.spec.ts` 的假引擎 `fromJSON()` 是空实现、sheet 实例恒不变，
 * 恰好吃掉了「重建换实例」这条缺陷；本文件让 `fromJSON()` **真的换实例并废弃旧的**（与引擎同形）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { field } from './sheet-fixtures'

interface SheetRecord {
  disposed: boolean
  rows: unknown[][]
}

const engine = vi.hoisted(() => ({
  /** `fromJSON()` 重建出来的内容（表头行 + 数据行）。 */
  nextRows: [] as unknown[][],
  /** `fromJSON` 被调用的次数（断言"真的重建过"）。 */
  rebuilds: 0,
  /** ExcelIO 的 open 回调：测试用它制造"载入在途"的窗口。 */
  excelOpen: null as null | ((blob: unknown, onOk: (json: string) => void, onFail: (error: unknown) => void) => void)
}))

vi.mock('@/utils/spreadjs', () => ({
  loadSpreadCore: () => Promise.resolve(fakeGc()),
  loadSpreadExcelIO: () => Promise.resolve({
    IO: class {
      open(blob: unknown, onOk: (json: string) => void, onFail: (error: unknown) => void): void {
        if (!engine.excelOpen) {
          onFail(new Error('未配置 ExcelIO'))
          return
        }
        engine.excelOpen(blob, onOk, onFail)
      }
    }
  })
}))

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('name', '名称'),
  field('status', '状态')
]

/** 当前活动工作表实例（`fromJSON()` 会把它换掉）。 */
let active: Record<string, unknown>
/** 活动工作表的数据账本（disposed 后所有读取都抛，与引擎同形）。 */
let activeRecord: SheetRecord

function makeSheet(record: SheetRecord): Record<string, unknown> {
  const guard = (): void => {
    if (record.disposed) {
      // 与真机 SpreadJS 一致：被 fromJSON 丢弃的 worksheet 内部引用已置空
      throw new TypeError("Cannot read properties of null (reading 'getRowCount')")
    }
  }
  const write = (row: number, col: number, value: unknown): void => {
    const line = record.rows[row] ?? []
    line[col] = value
    record.rows[row] = line
  }
  return {
    name: () => 'Sheet1',
    options: { isProtected: false },
    bind: () => {},
    suspendPaint: () => {},
    resumePaint: () => {},
    setRowCount: () => {},
    setColumnCount: () => {},
    setColumnWidth: () => {},
    frozenRowCount: () => {},
    getRowCount: () => {
      guard()
      return record.rows.length
    },
    getColumnCount: () => {
      guard()
      return (record.rows[0] ?? []).length
    },
    getValue: (row: number, col: number) => {
      guard()
      return record.rows[row]?.[col] ?? null
    },
    setValue: (row: number, col: number, value: unknown) => {
      guard()
      write(row, col, value)
    },
    getArray: (row: number, col: number, rows: number, cols: number) => {
      guard()
      const grid: unknown[][] = []
      for (let r = 0; r < rows; r += 1) {
        const line: unknown[] = []
        for (let c = 0; c < cols; c += 1) {
          line.push(record.rows[row + r]?.[col + c] ?? null)
        }
        grid.push(line)
      }
      return grid
    },
    setArray: (row: number, col: number, values: unknown[][]) => {
      guard()
      values.forEach((line, r) => line.forEach((value, c) => write(row + r, col + c, value)))
    },
    getCell: () => ({
      formatter: () => {},
      validator: () => {},
      font: () => {},
      hAlign: () => {},
      foreColor: () => {},
      backColor: () => undefined
    }),
    clear: () => {
      guard()
      record.rows = []
    },
    getSelections: () => []
  }
}

function resetActive(rows: unknown[][]): void {
  activeRecord = { disposed: false, rows }
  active = makeSheet(activeRecord)
}

function fakeGc(): unknown {
  class Workbook {
    fromJSON(): void {
      engine.rebuilds += 1
      // 引擎语义：重建工作表集，旧实例全部丢弃（读它们必抛）
      activeRecord.disposed = true
      resetActive(engine.nextRows.map((line) => [...line]))
    }
    toJSON(): string {
      return '{}'
    }
    getActiveSheet(): unknown {
      return active
    }
    getSheetCount(): number {
      return 1
    }
    getSheet(index: number): unknown {
      void index
      return active
    }
    refresh(): void {}
    destroy(): void {
      activeRecord.disposed = true
    }
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
        HorizontalAlign: { center: 1 },
        SheetArea: { viewport: 0 }
      }
    }
  }
}

type Adapter = {
  rowCount(): number
  colCount(): number
  header(col: number): string
  getCell(row: number, col: number): unknown
  getArray(row: number, col: number, rows: number, cols: number): unknown[][]
  instanceId?(): string
  loadGeneration?(): number
}

type GridVm = {
  loadFromJson: (json: string, fields: readonly unknown[]) => Promise<void>
  loadFromXlsx: (blob: Blob, fields: readonly unknown[]) => Promise<{ missingHeaders: string[] }>
  getSheetAdapter: () => Adapter
  whenLoaded?: () => Promise<void>
}

let wrapper: ReturnType<typeof mount> | null = null
let grid: GridVm

beforeEach(async () => {
  engine.nextRows = []
  engine.rebuilds = 0
  engine.excelOpen = null
  resetActive([])
  const { default: SpreadGrid } = await import('@/components/SpreadGrid.vue')
  wrapper = mount(SpreadGrid, { props: { defCode: 'DEMO', sheetName: '演示表' } })
  await new Promise((resolve) => setTimeout(resolve, 0))
  await flushPromises()
  grid = wrapper.vm as unknown as GridVm
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('L-115 工作表重建：重建前取到的适配器必须跟到活着的表', () => {
  it('fromJSON 换实例后，旧适配器读新表（不抛 TypeError、不读回 null）', async () => {
    engine.nextRows = [
      ['code', 'name', 'status'],
      ['CNY', '人民币', '启用'],
      ['USD', '美元', '停用'],
      ['CNH', '离岸', '停用']
    ]
    await grid.loadFromJson('{}', fields)
    expect(engine.rebuilds).toBe(1)

    // 调用方在重建前一瞬取到适配器（真机 T02/T03 的窗口）
    const stale = grid.getSheetAdapter()
    expect(stale.rowCount()).toBe(3)
    expect(stale.getCell(1, 0)).toBe('USD')

    // 重建：引擎丢弃旧 worksheet、换上新实例（数据也换成另一份）
    engine.nextRows = [
      ['code', 'name', 'status'],
      ['AUD', '澳元', '启用'],
      ['BRL', '雷亚尔', '停用']
    ]
    await grid.loadFromJson('{}', fields)
    expect(engine.rebuilds).toBe(2)

    // 旧适配器：必须解析到「当前活着的 worksheet」，而不是继续读废弃实例
    expect(stale.rowCount()).toBe(2)
    expect(stale.header(0)).toBe('*编码')
    expect(stale.getCell(0, 0)).toBe('AUD')
    expect(stale.getArray(0, 0, 2, 1)).toEqual([['AUD'], ['BRL']])
  })

  it('重建后 getSheetAdapter() 返回新适配器（撤销栈按表隔离，不跨表串台）', async () => {
    engine.nextRows = [['code', 'name', 'status'], ['CNY', '人民币', '启用']]
    await grid.loadFromJson('{}', fields)
    const before = grid.getSheetAdapter()

    engine.nextRows = [['code', 'name', 'status'], ['AUD', '澳元', '启用']]
    await grid.loadFromJson('{}', fields)
    const after = grid.getSheetAdapter()

    expect(after).not.toBe(before)
    expect(after.getCell(0, 0)).toBe('AUD')
    // 同一张表（未重建）重复取仍是同一对象（L-74 的身份约束不能被本修破坏）
    expect(grid.getSheetAdapter()).toBe(after)
  })

  it('每次程序化载入（loadFromJson）都递增 loadGeneration；重建同时更换 instanceId', async () => {
    engine.nextRows = [['code', 'name', 'status'], ['CNY', '人民币', '启用']]
    await grid.loadFromJson('{}', fields)
    const adapter = grid.getSheetAdapter()
    const generation = adapter.loadGeneration?.()
    const instance = adapter.instanceId?.()
    expect(typeof generation).toBe('number')
    expect(instance).toBeTruthy()

    engine.nextRows = [['code', 'name', 'status'], ['AUD', '澳元', '启用']]
    await grid.loadFromJson('{}', fields)

    expect(adapter.loadGeneration?.()).toBe((generation ?? 0) + 1)
    expect(adapter.instanceId?.()).not.toBe(instance)
  })
})

describe('L-115 载入窗口：whenLoaded() 必须在真数据落定后才放行', () => {
  it('载入在途时 whenLoaded() 不结算，落定后才结算，适配器读到的是真数据', async () => {
    let release: (() => void) | null = null
    engine.excelOpen = (_blob, onOk) => {
      release = () => onOk('{}')
    }
    engine.nextRows = [
      ['code', 'name', 'status'],
      ['CNY', '人民币', '启用'],
      ['USD', '美元', '停用']
    ]

    const loading = grid.loadFromXlsx(new Blob(['x']), fields)
    expect(typeof grid.whenLoaded).toBe('function')

    let settled = false
    const waiting = (grid.whenLoaded as () => Promise<void>)().then(() => {
      settled = true
    })
    await flushPromises()
    // 载入还没落定 ⇒ 页面能力不得把"空表"当表格交出去
    expect(settled).toBe(false)

    expect(release).not.toBeNull()
    ;(release as unknown as () => void)()
    await loading
    await waiting

    expect(settled).toBe(true)
    expect(grid.getSheetAdapter().rowCount()).toBe(2)
    expect(grid.getSheetAdapter().getCell(1, 0)).toBe('USD')
  })

  it('无在途载入时 whenLoaded() 立即结算（不改变既有同步语义）', async () => {
    engine.nextRows = [['code', 'name', 'status'], ['CNY', '人民币', '启用']]
    await grid.loadFromJson('{}', fields)
    await expect((grid.whenLoaded as () => Promise<void>)()).resolves.toBeUndefined()
  })
})
