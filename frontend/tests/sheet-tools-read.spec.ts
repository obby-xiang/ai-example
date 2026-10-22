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
  planNormalize,
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
      caseMode: 'upper'
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('trim')
    expect(result.text).toContain('共')
    expect(sheet.writes).toHaveLength(0)
    expect(sheet.rows[0]?.code).toBe(' CNY ')
  })
})

// ── 表格工具批A：修错与订正（P0-2 / P0-3 / P0-5 / P1-1） ──────────────────────

describe('P0-2 caseMode 真正生效（参数名与后端 AiTools/SheetWriteGuard 一致）', () => {
  it('planNormalize(caseMode=lower) ⇒ 变更值真的转小写', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: [{ code: 'AbC', name: '甲' }] })
    const planned = planNormalize(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['code'],
      rules: ['case'],
      caseMode: 'lower',
      commit: true
    })
    expect(planned.ok).toBe(true)
    if (!planned.ok) return
    expect(planned.plan.changes).toHaveLength(1)
    expect(planned.plan.changes[0]?.newValue).toBe('abc')
  })

  it('normalizeSuggest(caseMode=lower) ⇒ 文案标 lower 且样例为小写', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: [{ code: 'AbC', name: '甲' }] })
    const result = normalizeSuggest(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['code'],
      rules: ['case'],
      caseMode: 'lower'
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('case → lower')
    expect(result.text).toContain('「AbC」→「abc」')
  })

  it('非法 caseMode 仍被拒（upper/lower 之外）', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: [{ code: 'AbC', name: '甲' }] })
    const result = normalizeSuggest(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['code'],
      rules: ['case'],
      caseMode: 'title'
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('upper 或 lower')
  })
})

describe('P0-3 scope=rows：rowRange 是字符串区间', () => {
  it('scope=rows + rowRange="2-41" ⇒ 命中 2..26 行（表尾截断）且 ok', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: rowsOf(25) })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'rows', rowRange: '2-41', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('scope=rows')
    expect(result.text).toContain('（命中 25 行 / 共 25 行）')
    expect(result.text).toContain('第 2 行')
    expect(result.text).toContain('第 6 行')
    expect(result.text).not.toContain('第 7 行')
  })

  it('scope=rows 缺 rowRange ⇒ 人话拒绝，不猜区间', () => {
    const sheet = makeFakeSheet({ fields: baseFields, rows: rowsOf(5) })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'rows' })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('rowRange')
  })
})

describe('P0-5 读侧投影缺省收窄：主键列 + 前 4 列，≤5 列上限', () => {
  it('12 列未给 columnCodes ⇒ 投影 5 列（主键列优先 + 前 4 列）', () => {
    const fields = Array.from({ length: 12 }, (_, index) => field(`c${index}`, `列${index}`, { key: index === 7 }))
    const row: Record<string, unknown> = {}
    for (let index = 0; index < 12; index += 1) row[`c${index}`] = `v${index}`
    const sheet = makeFakeSheet({ fields, rows: [row] })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 5 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('共 5 列')
    expect(result.text).toContain('另有 7 列未显示')
    // 主键列 + 前 4 列：c7 在投影内，c5 及以后不在
    expect(result.text).toContain('c7=v7')
    expect(result.text).not.toContain('c5=v5')
    expect(result.text).not.toContain('c11=v11')
  })

  it('columnCodes 超 5 列 ⇒ 拒绝而非静默全列', () => {
    const fields = Array.from({ length: 12 }, (_, index) => field(`c${index}`, `列${index}`))
    const row: Record<string, unknown> = {}
    for (let index = 0; index < 12; index += 1) row[`c${index}`] = `v${index}`
    const sheet = makeFakeSheet({ fields, rows: [row] })
    const result = readSheetSummary(sheet.ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['c0', 'c1', 'c2', 'c3', 'c4', 'c5'],
      limit: 5
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('最多 5 列')
  })
})

describe('P1-1 尺寸闸覆盖整条结果（表头/空值/重复/采样/尾行全计入 2000 字符）', () => {
  it('20 行 × 5 列长值 ⇒ 整条结果 ≤2000 字符且带截断标记与“共 N 行”', () => {
    const fields = Array.from({ length: 5 }, (_, index) =>
      field(`c${index}`, `列${index}`, { key: index === 0, required: true }))
    const rows = Array.from({ length: 25 }, (_, rowIndex) => {
      const row: Record<string, unknown> = {}
      for (let col = 0; col < 5; col += 1) row[`c${col}`] = `R${rowIndex}-C${col}-${'长'.repeat(80)}`
      return row
    })
    const sheet = makeFakeSheet({ fields, rows })
    const result = readSheetSummary(sheet.ctx, { defCode: 'DEMO', scope: 'sheet', limit: 20 })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text.length).toBeLessThanOrEqual(SHEET_TOOL_MAX_RESULT_CHARS)
    expect(result.text).toContain('表格：DEMO')
    expect(result.text).toContain('采样（前 20 行')
    expect(result.text).toContain('已截断')
    expect(result.text).toContain('共 25 行')
    const returned = Number(/本次回 (\d+) 行/.exec(result.text)?.[1])
    expect(Number.isInteger(returned)).toBe(true)
    expect(returned).toBeLessThanOrEqual(SHEET_TOOL_MAX_SAMPLE_ROWS)
  })

  it('行数与字符同时触发 ⇒ 报最先触发的 rows（不被 chars 覆盖）', () => {
    const lines = Array.from({ length: 25 }, (_, index) => `第 ${index + 2} 行 | ${'长'.repeat(300)}`)
    const capped = capSheetResult({ lines, total: 25, returned: 25, unit: '行' })
    expect(capped.truncated).toBe(true)
    expect(capped.truncatedReason).toBe('rows')
    expect(capped.text).toContain('共 25 行，本次回 20 行')
  })
})
