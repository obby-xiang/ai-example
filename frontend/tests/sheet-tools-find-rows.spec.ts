/**
 * AI 表格操作专项 · 批B②：`find_rows`（全列 contains 检索）。
 *
 * 业界口径（Sheets/Excel 查找 + MCP 类表格工具）：返回**命中行号 + 命中单元格地址 +
 * 采样 + 共 N 行**，采样恒走 `capSheetResult` 整条闸（≤20 行 / 2000 字符）。
 */
import { describe, expect, it } from 'vitest'
import { SHEET_TOOL_MAX_RESULT_CHARS, findRows } from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('name', '名称'),
  field('region', '地区')
]

function sheetOf(count: number) {
  const rows = Array.from({ length: count }, (_, index) => ({
    code: `C${index + 1}`,
    name: `名称${index + 1}`,
    region: index % 2 === 0 ? '华东' : '华南'
  }))
  return makeFakeSheet({ fields, rows })
}

function sampleLines(text: string): string[] {
  return text.split('\n').filter((line) => line.startsWith('  第 '))
}

describe('B②-1 全列检索：命中行数 + 采样 + 共 N 行', () => {
  it('25 行里 13 行含“华东” ⇒ 共 13 行，默认采样 5 行', () => {
    const sheet = sheetOf(25)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('检索：contains「华东」')
    expect(result.text).toContain('（命中 13 行 / 共 25 行）')
    expect(result.text).toContain('命中列：region 13 行')
    expect(result.text).toContain('共 13 行，本次回 5 行')
    expect(sampleLines(result.text)).toHaveLength(5)
    // 采样只回命中单元格所在的列，不把整行 3 列都带进来
    expect(sampleLines(result.text)[0]).toBe('  第 2 行 | C2 region=华东')
  })

  it('limit=20 ⇒ 回 20 行；limit=50 ⇒ 人话拒绝（1 到 20）', () => {
    const sheet = sheetOf(45)
    const capped = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', limit: 20 })
    expect(capped.ok).toBe(true)
    if (!capped.ok) return
    expect(sampleLines(capped.text)).toHaveLength(20)
    expect(capped.text).toContain('共 23 行，本次回 20 行')

    const rejected = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', limit: 50 })
    expect(rejected.ok).toBe(false)
    if (rejected.ok) return
    expect(rejected.reason).toContain('1 到 20')
  })

  it('采样不是全量：第 21 名之后的命中只计进“共 N”，不进采样', () => {
    const sheet = sheetOf(25)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('共 13 行，本次回 5 行')
    expect(result.text).not.toContain('第 12 行')
  })
})

describe('B②-2 单元格地址：命中单元格带列字母 + Excel 行号', () => {
  it('第 1 条数据行（Excel 第 2 行）的 region 列（第 3 列）⇒ C2', () => {
    const sheet = sheetOf(4)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', limit: 3 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('C2 region=华东')
    expect(result.text).toContain('C4 region=华东')
  })

  it('多列同时命中 ⇒ 采样里逐格给地址（A5 与 B5 都在）', () => {
    const sheet = makeFakeSheet({
      fields,
      rows: [{ code: 'X1', name: 'X1-华东', region: '华东' }]
    })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const line = sampleLines(result.text)[0] ?? ''
    expect(line).toContain('B2 name=X1-华东')
    expect(line).toContain('C2 region=华东')
    expect(result.text).toContain('命中列：name 1 行、region 1 行')
  })
})

describe('B②-3 匹配口径：contains 默认、equals 精确定格、大小写默认不敏感', () => {
  const mixed = [
    { code: 'C1', name: '华东区', region: '华东' },
    { code: 'C2', name: '华东仓', region: '华南' }
  ]

  it('contains 命中 2 行（name 里出现也算）', () => {
    const sheet = makeFakeSheet({ fields, rows: mixed })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('（命中 2 行 / 共 2 行）')
  })

  it('match=equals ⇒ 只有整格等于“华东”的 C1 命中', () => {
    const sheet = makeFakeSheet({ fields, rows: mixed })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', match: 'equals' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('（命中 1 行 / 共 2 行）')
    expect(result.text).toContain('C2 region=华东')
    expect(result.text).not.toContain('华东仓')
  })

  it('ignoreCase 默认 true；显式 false 时 ALPHA 不再命中 alpha', () => {
    const caseRows = [
      { code: 'C1', name: 'Alpha', region: '华东' },
      { code: 'C2', name: 'alpha', region: '华东' }
    ]
    const insensitive = makeFakeSheet({ fields, rows: caseRows })
    const hit = findRows(insensitive.ctx, { defCode: 'DEMO', search: 'ALPHA' })
    expect(hit.ok).toBe(true)
    if (!hit.ok) return
    expect(hit.text).toContain('共 2 行')

    const sensitive = makeFakeSheet({ fields, rows: caseRows })
    const miss = findRows(sensitive.ctx, { defCode: 'DEMO', search: 'ALPHA', ignoreCase: false })
    expect(miss.ok).toBe(true)
    if (!miss.ok) return
    expect(miss.text).toContain('共 0 行，本次回 0 行')
  })

  it('match=wildcard ⇒ 人话拒绝并列出支持的口径', () => {
    const sheet = makeFakeSheet({ fields, rows: mixed })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', match: 'wildcard' })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('match')
    expect(result.reason).not.toContain('{')
  })
})

describe('B②-4 范围收窄：columnCodes / scope / predicate 都只读命中行', () => {
  it('columnCodes 只检索给定列（name 里的“华东区”不再命中）', () => {
    const sheet = makeFakeSheet({
      fields,
      rows: [
        { code: 'C1', name: '华东区', region: '华南' },
        { code: 'C2', name: '华南区', region: '华东' }
      ]
    })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', columnCodes: ['region'] })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('列 region')
    expect(result.text).toContain('（命中 1 行 / 共 2 行）')
    expect(result.text).toContain('C3 region=华东')
  })

  it('scope=selection ⇒ 只在选区内检索', () => {
    const sheet = sheetOf(6)
    sheet.setSelection({ row: 2, col: 0, rowCount: 2, colCount: 3 })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', scope: 'selection' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    // 数据行下标 2、3 ⇒ Excel 第 4、5 行；第 4 行 region=华东
    expect(result.text).toContain('（命中 1 行 / 共 2 行）')
    expect(result.text).toContain('C4 region=华东')
  })

  it('predicate 先过滤再检索（只报 Zeta 区里的命中）', () => {
    const sheet = makeFakeSheet({
      fields,
      rows: [
        { code: 'A-1', name: '华东', region: 'Alpha' },
        { code: 'Z-1', name: '华东', region: 'Zeta' },
        { code: 'Z-2', name: '华南', region: 'Zeta' }
      ]
    })
    const result = findRows(sheet.ctx, {
      defCode: 'DEMO',
      search: '华东',
      predicate: { scopeKeys: [], fields: [{ fieldCode: 'region', operator: 'EQ', value: 'Zeta' }] }
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    // 命中 = 检索命中 1 行；共 = 条件命中后的候选行 2 行
    expect(result.text).toContain('（命中 1 行 / 共 2 行）')
    expect(result.text).toContain('B3 name=华东')
  })
})

describe('B②-5 参数闸与零命中', () => {
  it('search 为空 ⇒ 拒绝且不含 JSON 片段', () => {
    const sheet = sheetOf(3)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '  ' })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('search')
    expect(result.reason).not.toContain('{')
  })

  it('columnCodes 含未知列 ⇒ 指名拒绝', () => {
    const sheet = sheetOf(3)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '华东', columnCodes: ['nope'] })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('nope')
  })

  it('零命中 ⇒ 共 0 行且无采样行', () => {
    const sheet = sheetOf(3)
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '不存在的词' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('共 0 行，本次回 0 行')
    expect(sampleLines(result.text)).toHaveLength(0)
  })
})

describe('B②-6 尺寸闸：整条结果 ≤2000 字符且必有截断标记', () => {
  it('25 行 × 200 字符长值 ⇒ 截断且有标记，尾部仍是“共 N 行”', () => {
    const rows = Array.from({ length: 25 }, (_, index) => ({
      code: `C${index + 1}`,
      name: `命中-${'长'.repeat(200)}`,
      region: '华东'
    }))
    const sheet = makeFakeSheet({ fields, rows })
    const result = findRows(sheet.ctx, { defCode: 'DEMO', search: '命中', limit: 20 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text.length).toBeLessThanOrEqual(SHEET_TOOL_MAX_RESULT_CHARS)
    expect(result.text).toContain('已截断')
    expect(result.text).toContain('共 25 行')
  })
})
