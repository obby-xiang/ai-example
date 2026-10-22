/**
 * AI 表格操作专项 · 批B①：聚合排序 + Top-N（分组截断前先排序）。
 *
 * 口径对齐业界 pivot / insights 默认：分组结果按**度量降序**排列，再取前 `limit` 组
 * （"哪个地区最多"必须一次可答）。`sort:none` 保留出现顺序作为逃生口。
 */
import { describe, expect, it } from 'vitest'
import { aggregateSheet } from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('region', '地区'),
  field('amount', '金额')
]

/**
 * 出现顺序 Zeta → Mid → Alpha，计数 Mid(3) > Zeta(2) > Alpha(1)，
 * 求和 Alpha(1000) > Mid(32) > Zeta(3) —— 四条排序口径两两可区分。
 */
const rows: Array<Record<string, unknown>> = [
  { code: 'Z1', region: 'Zeta', amount: '1' },
  { code: 'Z2', region: 'Zeta', amount: '2' },
  { code: 'M1', region: 'Mid', amount: '20' },
  { code: 'A1', region: 'Alpha', amount: '1000' },
  { code: 'M2', region: 'Mid', amount: '7' },
  { code: 'M3', region: 'Mid', amount: '5' }
]

function groupLabels(text: string): string[] {
  return text
    .split('\n')
    .filter((line) => line.startsWith('  '))
    .map((line) => line.trim().replace(/^([^：]+)：.*$/, '$1'))
}

function aggregate(args: Record<string, unknown>) {
  const sheet = makeFakeSheet({ fields, rows })
  const result = aggregateSheet(sheet.ctx, { defCode: 'DEMO', groupBy: 'region', ...args })
  expect(result.ok).toBe(true)
  if (!result.ok) throw new Error(result.reason)
  return result.text
}

describe('B①-1 默认排序：不传 sort 也按首个度量（count）降序', () => {
  it('默认输出 Mid(3) → Zeta(2) → Alpha(1)', () => {
    const text = aggregate({})
    expect(groupLabels(text)).toEqual(['Mid', 'Zeta', 'Alpha'])
    expect(text).toContain('排序：按 count 降序（取前 20 组）')
  })

  it('sort=count 与默认同序（显式写法）', () => {
    expect(groupLabels(aggregate({ sort: 'count' }))).toEqual(['Mid', 'Zeta', 'Alpha'])
  })

  it('sort=metricAsc ⇒ Alpha(1) → Zeta(2) → Mid(3)', () => {
    const text = aggregate({ sort: 'metricAsc' })
    expect(groupLabels(text)).toEqual(['Alpha', 'Zeta', 'Mid'])
    expect(text).toContain('排序：按 count 升序')
  })
})

describe('B①-2 标签排序与逃生口', () => {
  it('sort=labelAsc ⇒ Alpha → Mid → Zeta（按标签而非计数）', () => {
    expect(groupLabels(aggregate({ sort: 'labelAsc' }))).toEqual(['Alpha', 'Mid', 'Zeta'])
  })

  it('sort=labelDesc ⇒ Zeta → Mid → Alpha', () => {
    expect(groupLabels(aggregate({ sort: 'labelDesc' }))).toEqual(['Zeta', 'Mid', 'Alpha'])
  })

  it('sort=none ⇒ 保留出现顺序 Zeta → Mid → Alpha', () => {
    const text = aggregate({ sort: 'none' })
    expect(groupLabels(text)).toEqual(['Zeta', 'Mid', 'Alpha'])
    expect(text).toContain('排序：保持原顺序')
  })
})

describe('B①-3 按指定度量排序（sum 与 count 序不同，证明真的按该度量）', () => {
  it('sort=sum + metrics=[sum] ⇒ Alpha(1000) → Mid(32) → Zeta(3)', () => {
    const text = aggregate({ metrics: ['sum'], columnCode: 'amount', sort: 'sum' })
    expect(groupLabels(text)).toEqual(['Alpha', 'Mid', 'Zeta'])
    expect(text).toContain('sum=1000')
    expect(text).toContain('排序：按 sum 降序')
  })

  it('sort=metric 取首个度量（metrics=[sum] ⇒ 按 sum 降序）', () => {
    expect(groupLabels(aggregate({ metrics: ['sum'], columnCode: 'amount', sort: 'metric' })))
      .toEqual(['Alpha', 'Mid', 'Zeta'])
  })
})

describe('B①-4 Top-N：排序在截断之前，limit 取的是前 N 名', () => {
  it('limit=2 ⇒ 回 Mid、Zeta（前二名），共 3 组本次回 2 组', () => {
    const text = aggregate({ limit: 2 })
    expect(groupLabels(text)).toEqual(['Mid', 'Zeta'])
    expect(text).toContain('共 3 组，本次回 2 组')
    expect(text).toContain('排序：按 count 降序（取前 2 组）')
  })

  it('limit=2 + labelAsc ⇒ 取标签序前二（Alpha、Mid），证明截断发生在排序之后', () => {
    expect(groupLabels(aggregate({ sort: 'labelAsc', limit: 2 }))).toEqual(['Alpha', 'Mid'])
  })
})

describe('B①-5 参数闸：排序口径不认识就拒，不静默回退', () => {
  it('sort=median ⇒ ok:false 且列出支持的口径', () => {
    const sheet = makeFakeSheet({ fields, rows })
    const result = aggregateSheet(sheet.ctx, { defCode: 'DEMO', groupBy: 'region', sort: 'median' })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('sort')
    expect(result.reason).toContain('metric')
    expect(result.reason).not.toContain('{')
  })

  it('sort=sum 但 metrics 不含 sum ⇒ 明确要求把 sum 列进 metrics', () => {
    const sheet = makeFakeSheet({ fields, rows })
    const result = aggregateSheet(sheet.ctx, {
      defCode: 'DEMO',
      groupBy: 'region',
      metrics: ['count'],
      columnCode: 'amount',
      sort: 'sum'
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toContain('metrics')
    expect(result.reason).toContain('sum')
  })

  it('不带 groupBy 时 sort 不影响总计（输出仍只有总计行）', () => {
    const sheet = makeFakeSheet({ fields, rows })
    const result = aggregateSheet(sheet.ctx, { defCode: 'DEMO', sort: 'count' })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.text).toContain('总计：6 行')
    expect(result.text).not.toContain('分组：')
  })
})

describe('B①-6 语义变更断言（单列）：分组输出顺序由“出现顺序”改为“度量降序”', () => {
  it('同一份数据：旧口径首组是 Zeta（先出现），新口径首组是 Mid（计数最多）', () => {
    const text = aggregate({})
    expect(groupLabels(text)[0]).toBe('Mid')
    expect(groupLabels(text)).not.toContain('Zeta（出现顺序残留）')
    // 逃生口可回到旧口径，证明变更不是“无法回退”
    expect(groupLabels(aggregate({ sort: 'none' }))[0]).toBe('Zeta')
  })
})
