/**
 * T-23 predicate 一致性（前端侧）：11 个既有算子 × 空值 × IN 数组。
 *
 * 同一份夹具同时出现在后端 `ConditionEvaluatorParityTest`；两侧命中集合必须一致
 * （设计稿 §8 风险 12：两份实现漂移会让“卡上说命中 312 行、实际只改 280 行”）。
 */
import { describe, expect, it } from 'vitest'
import { matchesCondition } from '@/utils/sheet-tools'
import type { FieldQueryCondition } from '@/types/condition'

const ROWS: Array<Record<string, unknown>> = [
  { code: 'CNY', name: '人民币', region: 'XN', amount: '100', date: '2026-01-05', note: '' },
  { code: 'USD', name: '美元', region: 'SH', amount: '20', date: '2026-02-10', note: 'x' },
  { code: 'CNH', name: '离岸', region: 'XN', amount: '100', date: null, note: null },
  { code: null, name: '空码', region: 'BJ', amount: '0', date: null, note: 'y' }
]

function hits(condition: FieldQueryCondition): string[] {
  return ROWS.filter((row) => matchesCondition(row, condition))
    .map((row) => String(row.name))
}

describe('T-23 前端 predicate 求值器（与后端 ConditionEvaluator 同夹具）', () => {
  it('EQ / NE', () => {
    expect(hits({ fields: [{ fieldCode: 'region', operator: 'EQ', value: 'XN' }] })).toEqual(['人民币', '离岸'])
    expect(hits({ fields: [{ fieldCode: 'region', operator: 'NE', value: 'XN' }] })).toEqual(['美元', '空码'])
  })

  it('CONTAINS / LIKE / STARTS_WITH', () => {
    expect(hits({ fields: [{ fieldCode: 'name', operator: 'CONTAINS', value: '人民' }] })).toEqual(['人民币'])
    expect(hits({ fields: [{ fieldCode: 'name', operator: 'LIKE', value: '美' }] })).toEqual(['美元'])
    expect(hits({ fields: [{ fieldCode: 'code', operator: 'STARTS_WITH', value: 'CN' }] })).toEqual(['人民币', '离岸'])
  })

  it('IN（数组）', () => {
    expect(hits({ fields: [{ fieldCode: 'region', operator: 'IN', value: ['XN', 'SH'] }] }))
      .toEqual(['人民币', '美元', '离岸'])
  })

  it('EMPTY / NOT_EMPTY（空串与 null 同判为空）', () => {
    expect(hits({ fields: [{ fieldCode: 'note', operator: 'EMPTY' }] })).toEqual(['人民币', '离岸'])
    expect(hits({ fields: [{ fieldCode: 'note', operator: 'NOT_EMPTY' }] })).toEqual(['美元', '空码'])
  })

  it('GT / GTE / LT / LTE（数值优先）', () => {
    expect(hits({ fields: [{ fieldCode: 'amount', operator: 'GT', value: '20' }] })).toEqual(['人民币', '离岸'])
    expect(hits({ fields: [{ fieldCode: 'amount', operator: 'GTE', value: '20' }] })).toEqual(['人民币', '美元', '离岸'])
    expect(hits({ fields: [{ fieldCode: 'amount', operator: 'LT', value: '20' }] })).toEqual(['空码'])
    expect(hits({ fields: [{ fieldCode: 'amount', operator: 'LTE', value: '20' }] })).toEqual(['美元', '空码'])
  })

  it('日期优先比较（yyyy-MM-dd）', () => {
    expect(hits({ fields: [{ fieldCode: 'date', operator: 'GT', value: '2026-02-01' }] })).toEqual(['美元'])
  })

  it('多条件 AND', () => {
    expect(hits({
      fields: [
        { fieldCode: 'region', operator: 'EQ', value: 'XN' },
        { fieldCode: 'amount', operator: 'EQ', value: '100' }
      ]
    })).toEqual(['人民币', '离岸'])
  })

  it('未知算子不算命中（保持 false，不静默放行）', () => {
    expect(matchesCondition({ code: 'X' }, { fields: [{ fieldCode: 'code', operator: 'BETWEEN' as never, value: '1' }] }))
      .toBe(false)
  })
})
