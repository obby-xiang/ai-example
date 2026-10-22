/**
 * AI 表格工具治理批 §6（P-01 ~ P-07）：count 快路径 + 分布。
 *
 * 背景（§6.1 的两个缺陷）：
 * ① 旧实现"先物化全量再判 10 万上限"——300 万格会先建 300 万个对象再拒绝；
 * ② 卡面承诺「只回计数与分布」，但从来没有分布。
 *
 * 本文件的断言都落在**可观测量**上：`getArray` 调用次数（扫描是否提前中止）、
 * `changes.length`（是否物化）、`distribution` 与 `aggregate_sheet` 的逐组一致性。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { field, makeFakeSheet, type FakeSheet } from './sheet-fixtures'
import {
  SHEET_AGGREGATE_MAX_GROUPS,
  SHEET_DISTRIBUTION_SHOW_GROUPS,
  SHEET_WRITE_HARD_MAX_CELLS,
  aggregateSheet,
  planAppliedText,
  planNormalize,
  planPreviewText,
  planReplace,
  planUpdate,
  replanSheet,
  type SheetContext
} from '@/utils/sheet-tools'

const fields = [
  field('code', '编码', { key: true }),
  field('region', '地区'),
  field('status', '状态')
]

let sheet: FakeSheet
let ctx: SheetContext

function install(rows: Array<Record<string, unknown>>): void {
  sheet = makeFakeSheet({ fields, rows })
  ctx = sheet.ctx
}

beforeEach(() => {
  install([
    { code: 'C1', region: 'XN', status: '停用' },
    { code: 'C2', region: 'XN', status: '停用' },
    { code: 'C3', region: 'SH', status: '停用' },
    { code: 'C4', region: 'HB', status: '启用' }
  ])
})

function replacePlan(preview: 'count' | 'sample-diff' | 'full-diff', extra: Record<string, unknown> = {}) {
  const planned = planReplace(
    ctx,
    { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview, ...extra },
    't-plan'
  )
  if (!planned.ok) throw new Error(planned.reason)
  return planned.plan
}

describe('P-02 count 档不物化', () => {
  it('count 档：detail=none、changes 只留 ≤3 处样例、计数精确', () => {
    const plan = replacePlan('count')

    expect(plan.detail).toBe('none')
    expect(plan.rowsCount).toBe(3)
    expect(plan.cellsCount).toBe(3)
    // 样例（卡面承诺的前 3 条）保留，但绝不是"全量变更集"
    expect(plan.changes.length).toBeLessThanOrEqual(3)
    expect(plan.changes.length).toBe(3)
  })

  it('sample-diff / full-diff：detail=full，changes 完整', () => {
    for (const level of ['sample-diff', 'full-diff'] as const) {
      install([
        { code: 'C1', region: 'XN', status: '停用' },
        { code: 'C2', region: 'XN', status: '停用' },
        { code: 'C3', region: 'SH', status: '停用' },
        { code: 'C4', region: 'HB', status: '启用' }
      ])
      const plan = replacePlan(level)
      expect(plan.detail).toBe('full')
      expect(plan.changes).toHaveLength(3)
    }
  })
})

describe('P-01 提前中止（HARD_MAX + 1）', () => {
  it('20 万行 × 3 列全命中 ⇒ 扫描在硬上限处中止（getArray 次数远小于全表）', () => {
    const rows = Array.from({ length: 200_000 }, (_, index) => ({ code: `C${index}`, region: 'XN', status: '停用' }))
    install(rows)
    sheet.calls.getArray = 0

    const planned = planReplace(
      ctx,
      { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'count' },
      't-big'
    )

    expect(planned.ok).toBe(false)
    if (planned.ok) return
    expect(planned.reason).toContain(`硬上限 ${SHEET_WRITE_HARD_MAX_CELLS}`)
    // 全表扫描需要 200000 / 500 = 400 批；提前中止在 (HARD_MAX+1) 格 ≈ 100001 行 ⇒ ≤ 210 批
    expect(sheet.calls.getArray).toBeLessThan(210)
    expect(sheet.calls.getArray).toBeGreaterThan(0)
  })
})

describe('P-03 切档重算：planId 不变、明细变成 full', () => {
  it('count → full-diff：重算后 changes 非空、planId 不变', () => {
    const countPlan = replacePlan('count')
    const replanned = replanSheet(ctx, countPlan, 'full-diff')

    expect(replanned.ok).toBe(true)
    if (!replanned.ok) return
    expect(replanned.plan.planId).toBe(countPlan.planId)
    expect(replanned.plan.detail).toBe('full')
    expect(replanned.plan.changes).toHaveLength(3)
    expect(replanned.plan.preview).toBe('full-diff')
  })
})

describe('P-04 版本变化 ⇒ 不套用旧结果', () => {
  it('切档前表格版本变了 ⇒ replan 失败并说明"预览已失效"', () => {
    const countPlan = replacePlan('count')
    const changed: SheetContext = { ...ctx, version: (ctx.version ?? 0) + 1 }

    const replanned = replanSheet(changed, countPlan, 'sample-diff')

    expect(replanned.ok).toBe(false)
    if (replanned.ok) return
    expect(replanned.reason).toContain('预览已失效')
  })
})

describe('P-05 分布与聚合器逐组一致', () => {
  it('缺省分组列 = 投影首列（无谓词时；§6.3 的推断口径）', () => {
    const plan = replacePlan('count')
    expect(plan.distribution?.by).toBe('status')
  })

  it('replace 的分布（按 region）与 aggregate_sheet(groupBy=region, metrics=count) 逐组相等', () => {
    const plan = replacePlan('count', { distributionBy: 'region' })
    const distribution = plan.distribution
    expect(distribution).toBeDefined()
    if (!distribution) return

    expect(distribution.by).toBe('region')
    // 与聚合器对同一条谓词（这里用 scope=columns + 全表）的结果比
    const aggregated = aggregateSheet(ctx, {
      defCode: 'DEMO',
      scope: 'sheet',
      groupBy: 'region',
      metrics: ['count'],
      sort: 'metric'
    })
    expect(aggregated.ok).toBe(true)
    if (!aggregated.ok) return
    for (const group of distribution.groups) {
      // 只比"改了的行"：XN 2 行、SH 1 行（HB 未命中，不在分布里）
      expect(aggregated.text).toContain(`${group.key}：${group.rows} 行`)
    }
  })

  it('分布口径：rows 与 cells 都显示（一行多列命中时二者不等）', () => {
    install([
      { code: 'C1', region: 'XN', status: '停用' },
      { code: 'C2', region: 'XN', status: '停用' }
    ])
    const planned = planReplace(
      ctx,
      { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status', 'code'], search: '停用', replace: '禁用', preview: 'count', distributionBy: 'region' },
      't-two'
    )
    expect(planned.ok).toBe(true)
    if (!planned.ok) return
    const distribution = planned.plan.distribution
    expect(distribution?.groups[0]).toEqual({ key: 'XN', rows: 2, cells: 2 })
  })

  it('组数超过 20 ⇒ truncated=true 且只显示前 5 组', () => {
    const many = Array.from({ length: 25 }, (_, index) => ({
      code: `C${index}`,
      region: `R${String(index).padStart(2, '0')}`,
      status: '停用'
    }))
    install(many)

    const plan = replacePlan('count', { distributionBy: 'region' })
    const distribution = plan.distribution
    expect(distribution).toBeDefined()
    if (!distribution) return
    expect(distribution.groups).toHaveLength(SHEET_DISTRIBUTION_SHOW_GROUPS)
    expect(distribution.otherGroups).toBe(25 - SHEET_DISTRIBUTION_SHOW_GROUPS)
    expect(distribution.truncated).toBe(true)
    expect(25).toBeGreaterThan(SHEET_AGGREGATE_MAX_GROUPS)
  })

  it('分布排序：rows 降序，同值按键字典序（可复现）', () => {
    install([
      { code: 'C1', region: 'SH', status: '停用' },
      { code: 'C2', region: 'XN', status: '停用' },
      { code: 'C3', region: 'HB', status: '停用' },
      { code: 'C4', region: 'HB', status: '停用' }
    ])
    const plan = replacePlan('count', { distributionBy: 'region' })
    expect(plan.distribution?.groups.map((group) => group.key)).toEqual(['HB', 'SH', 'XN'])
  })

  it('update_rows / normalize_column 的 count 档也产出分布', () => {
    const updated = planUpdate(
      ctx,
      { defCode: 'DEMO', scope: 'sheet', predicate: { scopeKeys: [], fields: [{ fieldCode: 'region', operator: 'EQ', value: 'XN' }] }, set: [{ columnCode: 'status', mode: 'literal', value: 'X' }], preview: 'count' },
      't-upd'
    )
    expect(updated.ok).toBe(true)
    if (!updated.ok) return
    expect(updated.plan.detail).toBe('none')
    expect(updated.plan.distribution?.by).toBe('region')

    const normalized = planNormalize(
      ctx,
      { defCode: 'DEMO', scope: 'sheet', columnCodes: ['region'], rules: ['case'], caseMode: 'lower', commit: true, preview: 'count' },
      't-norm'
    )
    expect(normalized.ok).toBe(true)
    if (!normalized.ok) return
    expect(normalized.plan.detail).toBe('none')
    expect(normalized.plan.distribution?.groups.length).toBeGreaterThan(0)
  })
})

describe('P-06 分布不进回灌', () => {
  it('planAppliedText 不含分布块（防止回灌扩大 D2 体积）', () => {
    const plan = replacePlan('sample-diff')
    const text = planAppliedText(plan, ctx)

    expect(text).not.toContain('分布')
    expect(text).toContain('已应用')
    expect(text).toContain('共 3 格')
  })

  it('planPreviewText 含分布行（卡面专用，不进回灌）', () => {
    const plan = replacePlan('count', { distributionBy: 'region' })
    const text = planPreviewText(plan)

    expect(text).toContain('分布（按')
    expect(text).toContain('XN 2 行 / 2 格')
    expect(text).toContain('明细未加载')
  })
})

describe('P-07 空命中不渲染分布块', () => {
  it('无匹配 ⇒ distribution 缺席（不显示"其他 0 组"这类空块）', () => {
    const plan = replacePlan('count', { search: '不存在的值' })

    expect(plan.distribution).toBeUndefined()
    expect(plan.rowsCount).toBe(0)
    expect(planPreviewText(plan)).not.toContain('分布（按')
  })
})

describe('P-01 附带：明细档仍受硬上限保护', () => {
  it('full-diff 超硬上限 ⇒ 拒绝（文案与 count 档同族）', () => {
    const rows = Array.from({ length: 120_000 }, (_, index) => ({ code: `C${index}`, region: 'XN', status: '停用' }))
    install(rows)

    const planned = planReplace(
      ctx,
      { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'full-diff' },
      't-full-big'
    )

    expect(planned.ok).toBe(false)
    if (planned.ok) return
    expect(planned.reason).toContain('硬上限')
  })
})
