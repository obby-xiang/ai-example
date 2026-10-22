/**
 * AI 表格工具治理批 §2.5 / §2.7（S-04）：前端侧的有效上界。
 *
 * 三条口径：
 * ① 默认策略 = 硬顶（20 行 / 5 列）—— 与治理批之前逐字等价（不引入新故障面）；
 * ② 只能收紧：`/health.sheet` 报 10 行 ⇒ 前端最多取 10；报 200 行 ⇒ 仍按 20（硬顶不漂移）；
 * ③ 收紧是**具名拒绝**而不是静默截断：`columnCodes` 超过有效列上限必须报出上限与出路
 *    （静默截断会让"列 A 没读到"被误读成"列 A 不存在"）。
 */
import { afterEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_SHEET_POLICY,
  SHEET_TOOL_MAX_COLUMNS,
  SHEET_TOOL_MAX_SAMPLE_ROWS,
  effectiveSheetPolicy,
  readSheetSummary,
  setSheetPolicy
} from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { key: true }),
  field('name', '名称'),
  field('status', '状态')
]

function sheetOf(rows: Array<Record<string, unknown>>) {
  return makeFakeSheet({ fields, rows })
}

afterEach(() => {
  setSheetPolicy(null)
})

describe('策略缺省与兜底', () => {
  it('缺省 = 硬顶 20 / 5（与治理批之前的行为逐字等价）', () => {
    expect(DEFAULT_SHEET_POLICY).toEqual({ sampleMaxRows: SHEET_TOOL_MAX_SAMPLE_ROWS, projectionMaxColumns: SHEET_TOOL_MAX_COLUMNS })
    expect(effectiveSheetPolicy()).toEqual(DEFAULT_SHEET_POLICY)
  })

  it('传入 null / 非法值 ⇒ 回到硬顶（不信任外部输入）', () => {
    expect(setSheetPolicy({ sampleMaxRows: 10, projectionMaxColumns: 3 })).toEqual({ sampleMaxRows: 10, projectionMaxColumns: 3 })
    expect(setSheetPolicy(null)).toEqual(DEFAULT_SHEET_POLICY)
    expect(setSheetPolicy({ sampleMaxRows: 0, projectionMaxColumns: -2 })).toEqual(DEFAULT_SHEET_POLICY)
  })

  it('配置放大不生效：报 200 行 / 9 列 ⇒ 仍是 20 / 5', () => {
    expect(setSheetPolicy({ sampleMaxRows: 200, projectionMaxColumns: 9 })).toEqual({
      sampleMaxRows: SHEET_TOOL_MAX_SAMPLE_ROWS,
      projectionMaxColumns: SHEET_TOOL_MAX_COLUMNS
    })
  })
})

describe('读侧采样上界随策略收紧', () => {
  const rows = Array.from({ length: 20 }, (_, index) => ({ code: `C${index}`, name: `n${index}`, status: '启用' }))

  it('策略 10 行 + 请求 20 行 ⇒ 只回 10 行采样（min 生效）', () => {
    setSheetPolicy({ sampleMaxRows: 10 })
    const ctx = sheetOf(rows).ctx

    const outcome = readSheetSummary(ctx, { defCode: 'DEMO', scope: 'sheet', limit: 20 })

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.meta?.sampleRows).toBe(10)
    expect(outcome.text).toContain('采样（前 10 行')
  })

  it('策略 5 行且未给 limit ⇒ 缺省也按 5 取（缺省值不得超过有效上界）', () => {
    setSheetPolicy({ sampleMaxRows: 5 })
    const ctx = sheetOf(rows).ctx

    const outcome = readSheetSummary(ctx, { defCode: 'DEMO', scope: 'sheet' })

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.meta?.sampleRows).toBe(5)
  })

  it('请求超过硬顶仍是人话拒绝（既有口径不回退）', () => {
    setSheetPolicy({ sampleMaxRows: 5 })
    const ctx = sheetOf(rows).ctx

    const outcome = readSheetSummary(ctx, { defCode: 'DEMO', scope: 'sheet', limit: 50 })

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.reason).toContain('1 到 20')
  })

  it('投影列超过有效上限 ⇒ 具名拒绝并点出配置键（不静默截断）', () => {
    setSheetPolicy({ projectionMaxColumns: 2 })
    const ctx = sheetOf(rows).ctx

    const outcome = readSheetSummary(ctx, { defCode: 'DEMO', scope: 'sheet', columnCodes: ['code', 'name', 'status'] })

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.reason).toContain('投影列上限 2 列')
    expect(outcome.reason).toContain('app.ai.sheet.projection-max-columns')
  })

  it('投影列在有效上限内（3 ≤ 5）⇒ 正常读', () => {
    setSheetPolicy({ projectionMaxColumns: 3 })
    const ctx = sheetOf(rows).ctx

    const outcome = readSheetSummary(ctx, { defCode: 'DEMO', scope: 'sheet', columnCodes: ['code', 'name', 'status'] })

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.meta?.projCols).toBe(3)
  })
})

describe('选区不收窄（2026-10-22 裁决，§8 K-11 / §10 O-9）', () => {
  it('选区 6 列 + 策略 2 列 ⇒ 选区仍按 6 列投影进采样（列面不设上限）', () => {
    const fake = sheetOf([{ code: 'C1', name: 'n1', status: '启用' }])
    fake.setSelection({ row: 0, col: 0, rowCount: 1, colCount: 3 })
    setSheetPolicy({ projectionMaxColumns: 2 })

    const outcome = readSheetSummary(fake.ctx, { defCode: 'DEMO', scope: 'selection' })

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    // 三列全部读到（投影列 = 选区实际列数），不被 2 列策略截断
    expect(outcome.meta?.projCols).toBe(3)
  })

  it('选区路径仍受行数硬闸约束（≤20）', () => {
    const rows = Array.from({ length: 30 }, (_, index) => ({ code: `C${index}`, name: `n${index}`, status: '启用' }))
    const fake = sheetOf(rows)
    fake.setSelection({ row: 0, col: 0, rowCount: 30, colCount: 3 })

    const outcome = readSheetSummary(fake.ctx, { defCode: 'DEMO', scope: 'selection', limit: 20 })

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.meta?.sampleRows).toBeLessThanOrEqual(SHEET_TOOL_MAX_SAMPLE_ROWS)
  })
})
