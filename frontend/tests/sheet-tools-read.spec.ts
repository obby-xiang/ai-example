/**
 * AI 表格操作专项 批①：读侧五档 + 尺寸闸（T-01…T-09、T-27）。
 *
 * 全部用例只驱动 `utils/sheet-tools.ts` 的纯函数 + 假适配器（不挂 SpreadJS、不挂面板），
 * 断言口径来自设计稿 §3.2 / §3.3（≤20 行、≤2000 字符、必回“共 N”）。
 */
import { describe, expect, it } from 'vitest'
import {
  SHEET_TOOL_MAX_RESULT_CHARS,
  SHEET_TOOL_MAX_SAMPLE_ROWS,
  aggregateSheet,
  capSheetResult,
  inspectColumns,
  normalizeSuggest,
  readSheetSummary,
  reviewCells
} from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const baseFields = [field('code', '编码', { required: true, key: true }), field('name', '名称', { required: true })]

function rowsOf(count: number): Array<Record<string, unknown>> {
  return Array.from({ length: count }, (_, index) => ({ code: `C${index + 1}`, name: `名称${index + 1}` }))
}

describe('T-01/T-04 尺寸闸：行数硬顶 20 与恒定“共 N”', () => {
  it('T-01：25 行 + limit=20 ⇒ 回 20 行 + 共 25 行，本次回 20 行', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: rowsOf(25) })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 20 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const dataLines = result.text.split('\n').filter((line) => line.startsWith('  第 '))
    expect(dataLines).toHaveLength(20)
    expect(result.text).toContain('共 25 行，本次回 20 行')
    expect(SHEET_TOOL_MAX_SAMPLE_ROWS).toBe(20)
  })

  it('T-04：未截断场景末尾仍带“共 N 行”', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: rowsOf(3) })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('共 3 行，本次回 3 行')
  })
})

describe('T-02 参数闸：limit 越界即拒（人话、无 JSON）', () => {
  it('limit=50 ⇒ ok:false 且说明 1..20', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: rowsOf(3) })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 50 })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('1 到 20')
    expect(result.reason).not.toContain('{')
  })
})

describe('T-03 尺寸闸：字符硬顶 2000（行数优先）', () => {
  it('20 行 × 300 字符 ⇒ 按字符截断且标记含“原 20 行”', () => {
    const lines = Array.from({ length: 20 }, (_, index) => `第 ${index + 2} 行 | ${'长'.repeat(300)}`)
    const capped = capSheetResult({ lines, total: 20, returned: 20, unit: '行' })
    expect(capped.truncated).toBe(true)
    expect(capped.truncatedReason).toBe('chars')
    expect(capped.text).toContain('已截断')
    expect(capped.text).toContain('原 20 行')
    expect(capped.text).toContain('字符')
    expect(capped.text).toContain('共 20 行')
    // 正文（不含尾行标记）必须收在 2000 字符内
    const body = capped.text.split('\n…[已截断')[0] ?? ''
    expect(body.length).toBeLessThanOrEqual(SHEET_TOOL_MAX_RESULT_CHARS)
  })
})

describe('T-05/T-06 投影与脱敏：只回选定列、第 21 行起零命中', () => {
  it('T-05：12 列只投影 3 列 ⇒ 另有 9 列未显示', () => {
    const fields = Array.from({ length: 12 }, (_, index) => field(`c${index}`, `列${index}`))
    const row: Record<string, unknown> = {}
    for (let index = 0; index < 12; index += 1) row[`c${index}`] = `v${index}`
    const sheet = makeFakeSheet({ fields, rows: [row] })
    const result = readSheetSummary(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['c0', 'c1', 'c2'],
      limit: 5
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('另有 9 列未显示')
    expect(result.text).not.toContain('c11')
  })

  it('T-06：种子词表负断言 —— 模型侧文本不含第 21 行起的字段值', () => {
    const rows = rowsOf(25)
    rows[20] = { code: 'SECRET21', name: 'SECRETNAME21' }
    rows[24] = { code: 'SECRET25', name: 'SECRETNAME25' }
    const sheet = makeFakeSheet({ fields: baseFields, rows })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).not.toContain('SECRET21')
    expect(result.text).not.toContain('SECRETNAME25')
  })
})

describe('T-07 列映射诊断：把“静默丢列”变成“可见建议”', () => {
  it('表头「名 称」⇒ 疑似映射到 name（理由：空白差异）', () => {
    const fields = [field('name', '名称', { required: true }), field('region', '地区')]
    const sheet = makeFakeSheet({
      fields,
      rows: [{ name: '甲', region: 'XN' }],
      headers: ['名 称', 'REGION']
    })
    const result = inspectColumns(sheet.ctx, { defCode: 'DEMO' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('疑似映射')
    expect(result.text).toContain('name')
    expect(result.text).toContain('空白差异')
  })
})

describe('T-08 本地校验摘要：按字段聚合 + 共 N 处', () => {
  it('5 处必填缺失 + 2 组主键重复 ⇒ 共 7 处', () => {
    const rows: Array<Record<string, unknown>> = []
    for (let index = 0; index < 5; index += 1) {
      rows.push({ code: `M${index}`, name: '' })
    }
    rows.push({ code: 'DUP1', name: '甲' }, { code: 'DUP1', name: '乙' })
    rows.push({ code: 'DUP2', name: '丙' }, { code: 'DUP2', name: '丁' })
    const sheet = makeFakeSheet({ fields: baseFields, rows })
    const result = reviewCells(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('按字段聚合')
    expect(result.text).toContain('共 7 处')
    expect(result.text).toContain('前 5 条')
  })
})

describe('T-09 聚合尺寸闸：37 组只回 20 组', () => {
  it('groupBy=code 37 组 ⇒ 共 37 组，本次回 20 组', () => {
    const rows = Array.from({ length: 37 }, (_, index) => ({ code: `G${index}`, name: 'x' }))
    const sheet = makeFakeSheet({ fields: baseFields, rows })
    const result = aggregateSheet(sheet.ctx, { defCode: 'DEMO', groupBy: 'code', limit: 20 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const groupLines = result.text.split('\n').filter((line) => line.startsWith('  '))
    expect(groupLines).toHaveLength(20)
    expect(result.text).toContain('共 37 组，本次回 20 组')
  })
})

describe('T-27 归一化建议档：commit=false 零写入', () => {
  it('只出各规则影响格数 + 样例，adapter 写调用零次', () => {
    const rows = [{ code: ' CNY ', name: '人民币' }, { code: 'cny', name: '人民币' }]
    const sheet = makeFakeSheet({ fields: baseFields, rows })
    const result = normalizeSuggest(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['code'],
      rules: ['trim', 'case'],
      case: 'upper'
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('trim')
    expect(result.text).toContain('共')
    expect(sheet.writes).toHaveLength(0)
    expect(sheet.rows[0]?.code).toBe(' CNY ')
  })
})
