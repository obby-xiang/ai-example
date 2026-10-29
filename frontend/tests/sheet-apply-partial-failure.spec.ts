/**
 * 应用计划中途异常的如实回报（2026-10-29 复审返修 I-1 口径）。
 *
 * 复审事实：`writeCells` 内部是**逐列 / 逐 run 的 `setArray`**，一个 chunk 中途抛错时该 chunk
 * 可能已落了一部分。旧实现（以及上一轮"整 chunk 记已写"的修法）都把整块当成"全失"：
 * - 块内已写格既不进 `appliedCells`，也不进撤销与上色 ⇒ 永久不可撤销；
 * - 首块就出部分写入时回执说"没有任何一格写入" —— 假话。
 *
 * 本文件按**新契约**锁：`SheetAdapter.writeCells` 返回 `{ applied, error? }`（不再 throw），
 * `applySheetPlan` 用 `applied` 反查 changes，成功/失败都按**真实落笔**算回执、撤销、上色。
 */
import { describe, expect, it } from 'vitest'
import {
  applySheetPlan,
  applyUndoPlan,
  planReplace,
  planUndo,
  sheetUndoDepth,
  SHEET_WRITE_CHUNK_ROWS,
  type SheetCellUpdate
} from '@/utils/sheet-tools'
import { field, makeFakeSheet } from './sheet-fixtures'

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('status', '状态')
]

const args = {
  defCode: 'DEMO',
  scope: 'sheet' as const,
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用',
  preview: 'sample-diff' as const
}

function bigSheet(rowCount: number) {
  return makeFakeSheet({
    fields,
    rows: Array.from({ length: rowCount }, (_, index) => ({ code: `C${index}`, status: '停用' }))
  })
}

/** 把适配器换成"按计划落前 N 条，然后回报 error"的桩（模拟块内部分写入）。 */
function writeThenFail(applyFirst: (updates: readonly SheetCellUpdate[]) => void, limit: number) {
  return (updates: readonly SheetCellUpdate[]) => {
    const head = updates.slice(0, limit)
    applyFirst(head)
    return { applied: [...head], error: new Error('引擎写入失败') }
  }
}

describe('R1 块内部分写入：按真实落笔计数、登记撤销、上色', () => {
  it('首块先落 3 格再报错（计划仅一块）⇒ 报已写入 3 格，不得说"没有任何一格写入"', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-first-chunk')
    if (!plan.ok) throw new Error(plan.reason)
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    const marks: number[] = []
    sheet.ctx.adapter.markCells = (cells) => {
      marks.push(cells.length)
    }
    sheet.ctx.adapter.writeCells = (updates) => writeThenFail(
      (head) => {
        original(head)
      },
      3
    )(updates)

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.reason).toContain('已写入 3 格')
    expect(outcome.reason).not.toContain('没有任何一格写入')
    expect(outcome.appliedCells).toBe(3)
    expect(outcome.undoable).toBe(true)
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)
    expect(marks).toEqual([3])
  })

  it('第二块内部只落 2 格再报错 ⇒ 已写入 = 500 + 2（不是整块 500/1000）', () => {
    const rowCount = SHEET_WRITE_CHUNK_ROWS + 4
    const sheet = bigSheet(rowCount)
    const plan = planReplace(sheet.ctx, args, 't-mid-chunk')
    if (!plan.ok) throw new Error(plan.reason)

    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    let calls = 0
    sheet.ctx.adapter.writeCells = (updates) => {
      calls += 1
      if (calls === 1) {
        original(updates)
        return { applied: [...updates] }
      }
      // 第二块：只落前 2 条
      const head = updates.slice(0, 2)
      original(head)
      return { applied: [...head], error: new Error('引擎写入失败') }
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.appliedCells).toBe(SHEET_WRITE_CHUNK_ROWS + 2)
    expect(outcome.reason).toContain(`已写入 ${SHEET_WRITE_CHUNK_ROWS + 2} 格`)
    // 前 500 行 + 第二块前 2 行已改，其余保持原样
    expect(sheet.rows[0]!.status).toBe('禁用')
    expect(sheet.rows[SHEET_WRITE_CHUNK_ROWS + 1]!.status).toBe('禁用')
    expect(sheet.rows[SHEET_WRITE_CHUNK_ROWS + 2]!.status).toBe('停用')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)
  })

  it('反向：中途失败后同一份计划重放 ⇒ 被状态闸拒绝，不再写一格', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-replay')
    if (!plan.ok) throw new Error(plan.reason)

    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    let calls = 0
    sheet.ctx.adapter.writeCells = (updates) => {
      calls += 1
      return writeThenFail((head) => original(head), 1)(updates)
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

describe('R2 撤销只覆盖真实落笔的那部分', () => {
  it('部分写入后撤销 ⇒ 只回滚已写格，未写格保持原样', () => {
    const sheet = bigSheet(4)
    const plan = planReplace(sheet.ctx, args, 't-undo-partial')
    if (!plan.ok) throw new Error(plan.reason)

    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => writeThenFail((head) => original(head), 2)(updates)
    const applied = applySheetPlan(sheet.ctx, plan.plan)
    expect(applied.ok).toBe(false)
    expect(sheet.rows[1]!.status).toBe('禁用')
    expect(sheet.rows[2]!.status).toBe('停用')

    // 撤销走正常适配器（故障注入只针对那一次应用）
    sheet.ctx.adapter.writeCells = original
    const undoPlan = planUndo(sheet.ctx, {}, 't-undo')
    if (!undoPlan.ok) throw new Error(undoPlan.reason)
    const undone = applyUndoPlan(sheet.ctx, undoPlan.plan)

    expect(undone.ok).toBe(true)
    expect(sheet.rows[0]!.status).toBe('停用')
    expect(sheet.rows[1]!.status).toBe('停用')
    expect(sheet.rows[3]!.status).toBe('停用')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })
})

describe('R4 收尾 best-effort：上色/登记失败不得吞掉回执与状态（复审 I-2）', () => {
  it('中途失败 + 上色抛错 ⇒ 仍回 failed、plan.status=failed、已登记撤销', () => {
    const sheet = bigSheet(4)
    const plan = planReplace(sheet.ctx, args, 't-mark-throw')
    if (!plan.ok) throw new Error(plan.reason)
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => writeThenFail((head) => original(head), 2)(updates)
    sheet.ctx.adapter.markCells = () => {
      throw new Error('表格已重建或已关闭，请重新打开表格后重试')
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.reason).toContain('已写入 2 格')
    expect(plan.plan.status).toBe('failed')
    const depth = sheetUndoDepth(sheet.ctx.adapter)
    expect(depth).toBe(1)
    // undoable 必须与真实登记结果一致
    expect(outcome.undoable).toBe(depth === 1)
  })

  it('成功路径 + 上色抛错 ⇒ 仍算成功、plan.status=applied、撤销已登记', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-mark-throw-ok')
    if (!plan.ok) throw new Error(plan.reason)
    sheet.ctx.adapter.markCells = () => {
      throw new Error('表格已重建或已关闭，请重新打开表格后重试')
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.cells).toBe(3)
    expect(plan.plan.status).toBe('applied')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)
  })

  it('零落笔的中途失败 ⇒ undoable=false，且回执不声称"已登记可撤销"', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-zero')
    if (!plan.ok) throw new Error(plan.reason)
    sheet.ctx.adapter.writeCells = () => ({ applied: [], error: new Error('引擎写入失败') })

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.appliedCells).toBe(0)
    expect(outcome.undoable).toBe(false)
    expect(outcome.reason).toContain('没有任何一格写入')
    expect(outcome.reason).not.toContain('已登记为可撤销')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })
})

describe('R3 成功路径：被适配器忽略（越界/不可写）的更新不计入回执与撤销', () => {
  it('适配器回报少一格 ⇒ 回执只报实际落笔格数，撤销/上色也只覆盖这 N-1 格', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-drop')
    if (!plan.ok) throw new Error(plan.reason)
    expect(plan.plan.changes).toHaveLength(3)

    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => {
      // 模拟"第 3 行越界被过滤"：只落前两条，且**没有** error（成功但少写）
      const head = updates.filter((update) => update.row !== 2)
      original(head)
      return { applied: [...head] }
    }
    const marks: Array<Array<{ row: number; col: number }>> = []
    sheet.ctx.adapter.markCells = (cells) => {
      marks.push(cells.map((cell) => ({ ...cell })))
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.cells).toBe(2)
    expect(outcome.text).toContain('改动 2 格')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)
    // 已应用色**真的**只画实际落笔的两格（不是同义反复的"计划里有几行"）
    expect(marks).toEqual([[
      { row: 0, col: 1 },
      { row: 1, col: 1 }
    ]])
  })
})

describe('R5 零落笔与部分落笔的行数口径（复审 I-A / M3）', () => {
  it('changes 非空但适配器一格都不落（无 error）⇒ failed，不得 ok:true / status=applied', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-zero-success')
    if (!plan.ok) throw new Error(plan.reason)
    expect(plan.plan.changes.length).toBeGreaterThan(0)
    sheet.ctx.adapter.writeCells = () => ({ applied: [] })

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.appliedCells).toBe(0)
    expect(outcome.undoable).toBe(false)
    expect(outcome.reason).toContain('没有任何一格写入')
    expect(plan.plan.status).toBe('failed')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })

  it('部分落笔的成功回执按**实际行数**（不出现"2 格 / 3 行"）', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-partial-rows')
    if (!plan.ok) throw new Error(plan.reason)
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    sheet.ctx.adapter.writeCells = (updates) => {
      const head = updates.filter((update) => update.row !== 2)
      original(head)
      return { applied: [...head] }
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.text).toContain('改动 2 格 / 2 行')
    expect(outcome.text).not.toContain('2 格 / 3 行')
  })
})

describe('R6 适配器自身 throw：调用侧兜底（复审 I-B）', () => {
  it('applySheetPlan：适配器 throw ⇒ 如实 failed（有回执、status=failed），不向上冒泡', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-throw-apply')
    if (!plan.ok) throw new Error(plan.reason)
    sheet.ctx.adapter.writeCells = () => {
      throw new Error('引擎写入失败（throw 形态）')
    }

    const outcome = applySheetPlan(sheet.ctx, plan.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.appliedCells).toBe(0)
    expect(outcome.reason).toContain('写入中途失败')
    expect(plan.plan.status).toBe('failed')
  })

  it('applyUndoPlan：撤销写入 throw ⇒ 如实 failed 回执，撤销层保留（不消失）', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-throw-undo')
    if (!plan.ok) throw new Error(plan.reason)
    const applied = applySheetPlan(sheet.ctx, plan.plan)
    expect(applied.ok).toBe(true)

    sheet.ctx.adapter.writeCells = () => {
      throw new Error('撤销写入失败（throw 形态）')
    }
    const undoPlan = planUndo(sheet.ctx, {}, 't-undo-throw')
    if (!undoPlan.ok) throw new Error(undoPlan.reason)
    const undone = applyUndoPlan(sheet.ctx, undoPlan.plan)

    expect(undone.ok).toBe(false)
    if (undone.ok) return
    expect(undone.reason).toContain('撤销未完成')
    expect(undone.reason).toContain('剩余 3 格')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)
  })
})

describe('R7 撤销静默少写：保留未回写格、重试只针对剩余（复审 I-C）', () => {
  it('适配器只回写 2/3 格（无 error）⇒ 不弹栈、如实报剩余；重试可把剩余格撤完', () => {
    const sheet = bigSheet(3)
    const plan = planReplace(sheet.ctx, args, 't-undo-partial-silent')
    if (!plan.ok) throw new Error(plan.reason)
    const applied = applySheetPlan(sheet.ctx, plan.plan)
    expect(applied.ok).toBe(true)

    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    // 第一次撤销：静默只回写前 2 格（无 error）
    sheet.ctx.adapter.writeCells = (updates) => {
      const head = updates.slice(0, 2)
      original(head)
      return { applied: [...head] }
    }
    const undoPlan = planUndo(sheet.ctx, {}, 't-undo-silent')
    if (!undoPlan.ok) throw new Error(undoPlan.reason)
    const first = applyUndoPlan(sheet.ctx, undoPlan.plan)

    expect(first.ok).toBe(false)
    if (first.ok) return
    expect(first.reason).toContain('已回写 2 格 / 共 3 格')
    expect(first.reason).toContain('剩余 1 格')
    expect(sheet.rows[0]!.status).toBe('停用')
    expect(sheet.rows[1]!.status).toBe('停用')
    expect(sheet.rows[2]!.status).toBe('禁用')
    // 撤销层保留（未弹栈）
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(1)

    // 重试：只针对未回写的 1 格（不被"手动改过"的冲突闸误拦）
    sheet.ctx.adapter.writeCells = original
    const retryPlan = planUndo(sheet.ctx, {}, 't-undo-retry')
    if (!retryPlan.ok) throw new Error(retryPlan.reason)
    const second = applyUndoPlan(sheet.ctx, retryPlan.plan)

    expect(second.ok).toBe(true)
    if (!second.ok) return
    expect(second.cells).toBe(1)
    expect(sheet.rows[2]!.status).toBe('停用')
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
  })
})
