/**
 * AI 表格操作专项 · 批B③/④：A1 单元格地址 + 批量读（`getArray` 取代逐格 `getCell`）。
 *
 * 口径：适配器窄接口注释已要求"读写都必须走 array 版本"（逐格在数千行上会卡主线程）。
 * 本文件用计数假适配器钉两条：
 * - 读路径的 `getCell` 调用数 = 0（全部走 `getArray` 批量）；
 * - 输出带列字母与单元格地址（`A2` / `C5` 形态），供模型与用户共同核对。
 */
import { describe, expect, it } from 'vitest'
import { aggregateSheet, findRows, readSheetSummary, reviewCells } from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('name', '名称')
]

function wideSheet(count: number) {
  const rows = Array.from({ length: count }, (_, index) => ({
    code: `C${index + 1}`,
    name: index % 3 === 0 ? '' : `名称${index + 1}`
  }))
  return makeFakeSheet({ fields, rows })
}

describe('B④ 批量读：读工具不再逐格 getCell', () => {
  it('read_sheet_summary（含空值统计/重复主键/采样）⇒ getCell 0 次', () => {
    const sheet = wideSheet(1200)
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    expect(sheet.calls.getCell).toBe(0)
    expect(sheet.calls.getArray).toBeGreaterThan(0)
    expect(sheet.calls.getArray).toBeLessThan(1200)
  })

  it('read_sheet_summary + predicate ⇒ 条件求值也走批量', () => {
    const sheet = wideSheet(300)
    const result = readSheetSummary(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      predicate: { scopeKeys: [], fields: [{ fieldCode: 'name', operator: 'CONTAINS', value: '名称1' }] }
    })
    expect(result.ok).toBe(true)
    expect(sheet.calls.getCell).toBe(0)
  })

  it('review_cells ⇒ getCell 0 次，且明细带单元格地址', () => {
    const sheet = makeFakeSheet({
      fields,
      rows: [
        { code: '', name: '甲' },
        { code: 'C2', name: '乙' }
      ]
    })
    const result = reviewCells(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(sheet.calls.getCell).toBe(0)
    expect(result.text).toContain('第 2 行 A2 code')
  })

  it('aggregate_sheet ⇒ getCell 0 次（分组与度量一次批量扫完）', () => {
    const sheet = wideSheet(400)
    const result = aggregateSheet(sheet.ctx, { defCode: 'DEMO', groupBy: 'name' })
    expect(result.ok).toBe(true)
    expect(sheet.calls.getCell).toBe(0)
  })

  it('find_rows ⇒ getCell 0 次', () => {
    const sheet = wideSheet(400)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '名称1' })
    expect(result.ok).toBe(true)
    expect(sheet.calls.getCell).toBe(0)
  })
})

describe('B③ A1 地址：列字母 + Excel 行号（表头为第 1 行）', () => {
  it('read_sheet_summary 列清单带列字母（A=code / B=name）', () => {
    const sheet = makeFakeSheet({ fields, rows: [{ code: 'CNY', name: '人民币' }] })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('A=code(编码)')
    expect(result.text).toContain('B=name(名称)')
  })

  it('read_sheet_summary 采样逐值带单元格地址（A2 code=CNY）', () => {
    const sheet = makeFakeSheet({ fields, rows: [{ code: 'CNY', name: '人民币' }] })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('A2 code=CNY')
    expect(result.text).toContain('B2 name=人民币')
  })

  it('列字母口径：第 27 列 = AA（与 utils/excel 的 columnLetter 同源）', () => {
    const wideFields = Array.from({ length: 27 }, (_, index) => field(`c${index}`, `列${index}`))
    const row: Record<string, unknown> = {}
    for (let index = 0; index < 27; index += 1) row[`c${index}`] = `v${index}`
    const sheet = makeFakeSheet({ fields: wideFields, rows: [row] })
    const result = readSheetSummary(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['c0', 'c1', 'c26'],
      limit: 1
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('AA=c26(列26)')
    expect(result.text).toContain('AA2 c26=v26')
  })
})
