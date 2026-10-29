/**
 * 应用计划中途异常的如实回报（2026-10-29）。
 *
 * 旧实现按 500 行分块 `writeCells`，任一块抛错就**直接冒泡**：预览卡停在"应用"上、没有任何回执，
 * 而前面几块**已经写进表格**且没有登记撤销 —— 用户既不知道改了多少，也撤不回来；重试还可能
 * 把同一批改动写第二遍。
 *
 * 新口径：分块写入中途异常 ⇒ ① 如实 `failed`（不是成功、也不是未知）；② 已写部分**登记为可撤销**
 * （撤销深度 +1、可回滚到修改前值）；③ 回执报**实际写入格数**；④ 计划被消费（重放不再写）。
 */
import { describe, expect, it } from 'vitest'
import { applySheetPlan, planReplace, planUndo, applyUndoPlan, sheetUndoDepth, SHEET_WRITE_CHUNK_ROWS } from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('status', '状态')
]

function bigSheet(rowCount: number) {
  const sheet = makeFakeSheet({
    fields,
    rows: Array.from({ length: rowCount }, (_, index) => ({ code: `C${index}`, status: '停用' }))
  })
  return sheet
}

const args = {
  defCode: 'DEMO',
  scope: 'sheet' as const,
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用',
  preview: 'sample-diff' as const
}

describe('R1 中途异常：failed + 实际写入数 + 可撤销', () => {
  it('第 2 块写入抛错 ⇒ 回执 failed、报已写入格数，且只登记已写部分的撤销', () => {
    const rowCount = SHEET_WRITE_CHUNK_ROWS + 100
    const sheet = bigSheet(rowCount)
    const plan = planReplace(sheet.ctx, args, 't-partial')
    if (!plan.ok) throw new Error(plan.reason)
    expect(plan.plan.changes).toHaveLength(rowCount)

    let calls = 0
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => {
      calls += 1
      if (calls === 2) {
        throw new Error('引擎写入失败')
      }
      original(updates)
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    // 实际写入 = 第 1 块（500 格），不是计划的 600 格
    expect(outcome.appliedCells).toBe(SHEET_WRITE_CHUNK_ROWS)
    expect(outcome.reason).toContain(`已写入 ${SHEET_WRITE_CHUNK_ROWS} 格`)
    expect(outcome.reason).toContain('可撤销')
    expect(outcome.undoable).toBe(true)
    // 前 500 行已改、其余保持原样
    expect(sheet.rows[0]!.status).toBe('禁用')
    expect(sheet.rows[SHEET_WRITE_CHUNK_ROWS]!.status).toBe('停用')
    // 已写部分进了撤销栈（深度 1）
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)
  })

  it('撤销已登记的部分改动 ⇒ 回到修改前值（不会少撤、也不会多撤）', () => {
    const rowCount = SHEET_WRITE_CHUNK_ROWS + 100
    const sheet = bigSheet(rowCount)
    const plan = planReplace(sheet.ctx, args, 't-partial-undo')
    if (!plan.ok) throw new Error(plan.reason)

    let calls = 0
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => {
      calls += 1
      if (calls === 2) {
        throw new Error('引擎写入失败')
      }
      original(updates)
    }
    applySheetPlan(sheet.ctx, plan.plan)

    const undoPlan = planUndo(sheet.ctx, {}, 't-undo')
    if (!undoPlan.ok) throw new Error(undoPlan.reason)
    const undone = applyUndoPlan(sheet.ctx, undoPlan.plan)

    expect(undone.ok).toBe(true)
    expect(sheet.rows[0]!.status).toBe('停用')
    expect(sheet.rows[rowCount - 1]!.status).toBe('停用')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })

  it('反向：中途失败后同一份计划重放 ⇒ 被状态闸拒绝，不再写一格', () => {
    const rowCount = SHEET_WRITE_CHUNK_ROWS + 100
    const sheet = bigSheet(rowCount)
    const plan = planReplace(sheet.ctx, args, 't-replay')
    if (!plan.ok) throw new Error(plan.reason)

    let calls = 0
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => {
      calls += 1
      if (calls === 2) {
        throw new Error('引擎写入失败')
      }
      original(updates)
    }
    const first = applySheetPlan(sheet.ctx, plan.plan)
    expect(first.ok).toBe(false)

    const writesBefore = calls
    const second = applySheetPlan(sheet.ctx, plan.plan)

    expect(second.ok).toBe(false)
    if (second.ok) return
    expect(second.reason).toContain('该预览已失效或已处理')
    expect(calls).toBe(writesBefore)
  })
})
