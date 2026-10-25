/**
 * Task 4（数值保真）：`utils/excel.ts` 的读写通路不得改写"看起来像数字"的标识符。
 *
 * 两条真实通路：
 *  ① 读：`readRows`（编辑区 → 行对象）—— 值来自工作表单元格，`NUMBER` 字段此前被
 *     `String(Number(text))` 归一化 ⇒ `007` 变 `7`、超过 2^53 的长标识符被 double 舍入；
 *  ② 写：`buildRowsXlsxBlob`（行对象 → PUT 的 xlsx 字节）—— 同样按 `Number` 写数字单元格，
 *     长标识符在 xlsx 里就已经失真，后端 DataFormatter 读到的也是舍入后的值。
 *
 * 保真判据：**能原样往返才写数字**，否则保留文本。
 */
import { describe, expect, it } from 'vitest'
import JSZip from 'jszip'
import { buildRowsXlsxBlob, normalizeCellValue, readRows } from '@/utils/excel'
import type { ConfigField } from '@/types/definition'

function field(code: string, fieldType: ConfigField['fieldType'], extra: Partial<ConfigField> = {}): ConfigField {
  return {
    defCode: 'DEMO',
    code,
    label: code,
    fieldType,
    required: false,
    key: false,
    sortOrder: 1,
    optionsJson: null,
    ...extra
  }
}

const FIELDS = [field('sku', 'STRING'), field('amount', 'NUMBER')]

/** 最小工作表假体：`readRows` 只用到这三个方法（行为与 SpreadJS 视口同口径）。 */
function fakeSheet(rows: Array<Array<string | number | null>>) {
  const columnCount = Math.max(...rows.map((row) => row.length))
  return {
    getColumnCount: () => columnCount,
    getRowCount: () => rows.length,
    getValue: (row: number, column: number) => rows[row]?.[column] ?? null
  }
}

describe('Task 4⑥：读回数值保真（readRows / normalizeCellValue）', () => {
  it('NUMBER 字段的前导零不被吃掉（007 仍是 007）', () => {
    expect(normalizeCellValue(field('amount', 'NUMBER'), '007')).toBe('007')
  })

  it('超过 2^53 的标识符不被 double 舍入', () => {
    expect(normalizeCellValue(field('amount', 'NUMBER'), '9007199254740993')).toBe('9007199254740993')
  })

  it('工作表里的文本单元格原样读回（007 / 长标识符）', () => {
    const sheet = fakeSheet([
      ['sku', 'amount'],
      ['A-1', '007'],
      ['A-2', '9007199254740993']
    ])
    const { rows } = readRows(sheet as never, FIELDS)
    expect(rows.map((row) => row.amount)).toEqual(['007', '9007199254740993'])
  })

  it('普通可往返数值仍归一为数字串（防过度保留）', () => {
    expect(normalizeCellValue(field('amount', 'NUMBER'), '100')).toBe('100')
    expect(normalizeCellValue(field('amount', 'NUMBER'), ' 1.5 ')).toBe('1.5')
  })
})

describe('Task 4⑦：写出行保真（buildRowsXlsxBlob）', () => {
  async function sheetXml(blob: Blob): Promise<{ sheet: string; shared: string }> {
    const zip = await JSZip.loadAsync(await blob.arrayBuffer())
    return {
      sheet: await zip.file('xl/worksheets/sheet1.xml')!.async('string'),
      shared: await zip.file('xl/sharedStrings.xml')!.async('string')
    }
  }

  it('长标识符按文本落格（sharedStrings 里是全精度，sheet 里没有舍入后的数字）', async () => {
    const blob = await buildRowsXlsxBlob(FIELDS, [{ sku: 'A-2', amount: '9007199254740993' }], {
      defCode: 'DEMO',
      sheetName: '数据'
    })
    const { sheet, shared } = await sheetXml(blob)
    expect(shared).toContain('9007199254740993')
    expect(sheet).not.toContain('9007199254740992')
  })

  it('007 按文本落格（不写成数字 7）', async () => {
    const blob = await buildRowsXlsxBlob(FIELDS, [{ sku: 'A-1', amount: '007' }], {
      defCode: 'DEMO',
      sheetName: '数据'
    })
    const { sheet, shared } = await sheetXml(blob)
    expect(shared).toContain('007')
    expect(sheet).not.toContain('<v>7</v>')
  })

  it('普通数值仍写数字单元格（Excel 端可排序/求和）', async () => {
    const blob = await buildRowsXlsxBlob(FIELDS, [{ sku: 'A-3', amount: '100' }], {
      defCode: 'DEMO',
      sheetName: '数据'
    })
    const { sheet } = await sheetXml(blob)
    expect(sheet).toContain('<v>100</v>')
  })
})
