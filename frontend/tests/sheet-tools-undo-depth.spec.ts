/**
 * AI 表格工具治理批 §5（U-01 ~ U-06）：撤销深度 3 + 整批全或无 + 卡面标识。
 *
 * 三条硬口径：
 * ① 深度 3：连撤 3 层各自回到对应前态，第 4 次如实回「没有可撤销」；
 * ② 整批全或无：应用后手改一格 ⇒ 整批拒撤、该格值不变、栈不弹出（不做"半撤销"）；
 * ③ **不承诺 Ctrl+Z**：撤销只走自有反向补丁，回执里不出现任何"Ctrl+Z 可撤销"的说法。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { SheetContext } from '@/utils/sheet-tools'
import {
  SHEET_UNDO_DEPTH,
  applySheetPlan,
  planReplace,
  replanSheet,
  sheetAppliedSummary,
  sheetManualChangeCount,
  sheetUndoDepth,
  undoLastSheetEdit
} from '@/utils/sheet-tools'
import type { SheetWritePlan } from '@/types/ai'
import { field, makeFakeSheet, type FakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { key: true }),
  field('name', '名称'),
  field('status', '状态')
]

let sheet: FakeSheet
let ctx: SheetContext

function fresh(rows: Array<Record<string, unknown>>): void {
  sheet = makeFakeSheet({ fields, rows })
  ctx = sheet.ctx
}

/** 建一张计划（缺省 count 档）。 */
function planFor(search: string, replace: string, preview: 'count' | 'sample-diff' | 'full-diff' = 'count') {
  const planned = planReplace(ctx, { defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search, replace, preview }, 't1')
  if (!planned.ok) throw new Error(planned.reason)
  return planned.plan
}

/**
 * 走**与 store 同一条路**把计划变成可应用的明细计划：count 档先 `replanSheet` 物化，
 * 并沿用预览时的指纹（物化不能把"改动后的状态"当成"预览时的状态"）。
 */
function materialize(plan: SheetWritePlan): SheetWritePlan {
  if (plan.detail === 'full') return plan
  const replanned = replanSheet(ctx, plan, 'sample-diff')
  if (!replanned.ok) throw new Error(replanned.reason)
  return { ...replanned.plan, targetRows: plan.targetRows, fingerprint: plan.fingerprint }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  fresh([
    { code: 'CNY', name: '人民币', status: '停用' },
    { code: 'USD', name: '美元', status: '停用' },
    { code: 'CNH', name: '离岸', status: '启用' }
  ])
})

describe('深度常量', () => {
  it('SHEET_UNDO_DEPTH = 3（2026-10-22 裁决，从 1 提到 3）', () => {
    expect(SHEET_UNDO_DEPTH).toBe(3)
  })
})

describe('U-01 连续三层各自还原', () => {
  it('三次 apply → 三次 undo 逐层回到对应前态；第 4 次回「没有可撤销」', () => {
    const first = planFor('停用', 'A')
    expect(applySheetPlan(ctx, materialize(first)).ok).toBe(true)
    expect(ctx.adapter.getCell(0, 2)).toBe('A')

    const second = planFor('A', 'B', 'sample-diff')
    expect(applySheetPlan(ctx, materialize(second)).ok).toBe(true)
    expect(ctx.adapter.getCell(0, 2)).toBe('B')

    const third = planFor('B', 'C', 'full-diff')
    expect(applySheetPlan(ctx, materialize(third)).ok).toBe(true)
    expect(ctx.adapter.getCell(0, 2)).toBe('C')
    expect(sheetUndoDepth(ctx.adapter)).toBe(3)

    const undo1 = undoLastSheetEdit(ctx)
    expect(undo1.ok && undo1.text).toContain('已撤销')
    expect(ctx.adapter.getCell(0, 2)).toBe('B')

    undoLastSheetEdit(ctx)
    expect(ctx.adapter.getCell(0, 2)).toBe('A')

    undoLastSheetEdit(ctx)
    expect(ctx.adapter.getCell(0, 2)).toBe('停用')

    const exhausted = undoLastSheetEdit(ctx)
    expect(exhausted.ok).toBe(true)
    if (!exhausted.ok) return
    expect(exhausted.text).toContain('没有可撤销的 AI 修改')
    expect(exhausted.text).toContain('共 0 格')
  })
})

describe('U-02 深度恒 ≤3（FIFO 淘汰栈底）', () => {
  it('第 4 次 apply 后栈深仍是 3，最旧那层已被淘汰（撤 3 次后仍有可撤）', () => {
    for (const [search, replace] of [['停用', 'L1'], ['L1', 'L2'], ['L2', 'L3'], ['L3', 'L4']] as const) {
      const plan = planFor(search, replace, 'sample-diff')
      expect(applySheetPlan(ctx, materialize(plan)).ok).toBe(true)
    }
    expect(ctx.adapter.getCell(0, 2)).toBe('L4')
    expect(sheetUndoDepth(ctx.adapter)).toBe(SHEET_UNDO_DEPTH)

    // 三层都能撤（撤到 L1 为止 —— 最旧那层（回到"停用"）已被淘汰，这是深度 3 的代价）
    for (let index = 0; index < 3; index += 1) {
      expect(undoLastSheetEdit(ctx).ok).toBe(true)
    }
    expect(ctx.adapter.getCell(0, 2)).toBe('L1')
    expect(sheetUndoDepth(ctx.adapter)).toBe(0)
  })

  it('同一 planId 重复 apply 不重复入栈（防双击）', () => {
    const plan = planFor('停用', 'X', 'sample-diff')
    expect(applySheetPlan(ctx, materialize(plan)).ok).toBe(true)
    // 第二次同 plan 会因 status/指纹判定被拒，栈仍只有 1 层
    applySheetPlan(ctx, materialize(plan))
    expect(sheetUndoDepth(ctx.adapter)).toBe(1)
  })
})

describe('U-03 人工改动 ⇒ 整批拒撤', () => {
  it('应用后手改一格再 undo ⇒ 整批拒绝、该格值不变、栈不弹出', () => {
    const plan = planFor('停用', '禁用', 'sample-diff')
    expect(applySheetPlan(ctx, materialize(plan)).ok).toBe(true)
    expect(ctx.adapter.getCell(0, 2)).toBe('禁用')

    // 用户手动把其中一格改成别的值
    sheet.rows[0]!.status = '人工改的'

    const undo = undoLastSheetEdit(ctx)
    expect(undo.ok).toBe(true)
    if (!undo.ok) return
    expect(undo.text).toContain('未撤销')
    expect(undo.text).toContain('1 格被你手动改过')
    expect(undo.text).toContain('共 0 格')
    // 值不变（没有被回滚）、栈不弹出（用户可先处理冲突）
    expect(ctx.adapter.getCell(0, 2)).toBe('人工改的')
    expect(ctx.adapter.getCell(1, 2)).toBe('禁用')
    expect(sheetUndoDepth(ctx.adapter)).toBe(1)
  })
})

describe('U-04 回执含 planId 与剩余层数', () => {
  it('撤销成功回执含 planId 与「剩余可撤销 N 层」', () => {
    const first = planFor('停用', 'X1', 'sample-diff')
    applySheetPlan(ctx, materialize(first))
    const second = planFor('X1', 'X2', 'sample-diff')
    applySheetPlan(ctx, materialize(second))

    const undo = undoLastSheetEdit(ctx)
    expect(undo.ok).toBe(true)
    if (!undo.ok) return
    expect(undo.text).toContain(`plan #${second.planId}`)
    expect(undo.text).toContain('剩余可撤销 1 层')
    expect(undo.text).toContain('共 2 格')
  })

  it('回执不含 Ctrl+Z 承诺（§5.5 红线）', () => {
    applySheetPlan(ctx, materialize(planFor('停用', 'X', 'sample-diff')))
    const undo = undoLastSheetEdit(ctx)
    if (!undo.ok) return
    expect(undo.text).not.toContain('Ctrl+Z')
    expect(undo.text).not.toContain('ctrl+z')
  })
})

describe('U-05 撤销写回只触发一次 data-changed 口径（批量写）', () => {
  it('两次撤销各只走一次 writeCells 批（不与写路径逐格落值）', () => {
    applySheetPlan(ctx, materialize(planFor('停用', 'Y', 'sample-diff')))
    sheet.writes.length = 0

    undoLastSheetEdit(ctx)

    expect(sheet.writes).toHaveLength(1)
    expect(sheet.writes[0]).toHaveLength(2)
  })
})

describe('U-06 高亮只走屏幕态（markCells 能力）', () => {
  it('应用时只调用 markCells（屏幕态），不写 cell style、不调 conditionalFormats', () => {
    const marked: Array<{ row: number; col: number }> = []
    let cleared = 0
    const adapterWithMarks = {
      ...sheet.ctx.adapter,
      markCells: (cells: readonly { row: number; col: number }[]) => {
        marked.push(...cells)
      },
      clearMarks: () => {
        cleared += 1
      }
    }
    const ctxWithMarks: SheetContext = { ...sheet.ctx, adapter: adapterWithMarks }

    applySheetPlan(ctxWithMarks, materialize(planFor('停用', 'Z', 'sample-diff')))
    expect(marked).toHaveLength(2)
    expect(marked[0]).toEqual({ row: 0, col: 2 })

    undoLastSheetEdit(ctxWithMarks)
    expect(cleared).toBe(1)
  })

  it('适配器无 markCells 能力时照常应用（可选能力，不是必需依赖）', () => {
    const plan = planFor('停用', 'W', 'sample-diff')
    expect(ctx.adapter.markCells).toBeUndefined()
    expect(applySheetPlan(ctx, materialize(plan)).ok).toBe(true)
    expect(ctx.adapter.getCell(0, 2)).toBe('W')
  })
})

describe('§5.4 卡面摘要：AI 改动格数 / 层号 / 人工改动现算', () => {
  it('应用后摘要给出格数、层号与可撤销层数；无人手改时人工改动为 0', () => {
    applySheetPlan(ctx, materialize(planFor('停用', 'M1', 'sample-diff')))
    const summary = sheetAppliedSummary(ctx)
    expect(summary).not.toBeNull()
    expect(summary?.cells).toBe(2)
    expect(summary?.layer).toBe(1)
    expect(summary?.undoDepth).toBe(1)
    expect(summary?.manualChanges).toBe(0)
    expect(sheetManualChangeCount(ctx)).toBe(0)
  })

  it('手改一格后人工改动计数 = 1（现算，不是应用瞬间的 0）', () => {
    applySheetPlan(ctx, materialize(planFor('停用', 'M2', 'sample-diff')))
    sheet.rows[1]!.status = '人工'
    expect(sheetManualChangeCount(ctx)).toBe(1)
    expect(sheetAppliedSummary(ctx)?.manualChanges).toBe(1)
  })

  it('撤销后摘要消失（没有 AI 改动就不渲染那一行）', () => {
    applySheetPlan(ctx, materialize(planFor('停用', 'M3', 'sample-diff')))
    undoLastSheetEdit(ctx)
    expect(sheetAppliedSummary(ctx)).toBeNull()
  })
})
