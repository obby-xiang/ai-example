/**
 * R1 返修 · Critical：带 `verbatim` 的草案被编辑时，**未编辑字段的原条件必须逐字保留**。
 *
 * 现场（reviewer Critical）：`ConditionDraftModel.verbatim` 存的是整份原始条件，而
 * `ConditionForm` 用户改任何一个字段都会重建一份**不带 verbatim** 的模型 ⇒ `onConditionChange`
 * 只能整份丢掉 verbatim：其它没动过的字段（尤其同字段多条件、取值含逗号这种无法从表单行
 * 还原的）被按有损草案重写/丢弃。
 *
 * 收口口径 = **按字段合并**：
 * - 未编辑字段：原条件原样保留（`applyDraftEdit` 只从 verbatim 里摘掉改动/删除的字段）；
 * - 编辑/删除字段：只按新草案处理；
 * - `scopeKeys` 变化不影响字段级原条件（合并时 scope 全取草案）；
 * - 合并结果由 `buildConditionOutcome` 产出，且**先校验草案**（verbatim 不绕过 EMPTY_IN/EMPTY_VALUE）。
 */
import { describe, expect, it } from 'vitest'
import {
  applyDraftEdit,
  buildConditionOutcome,
  draftModelFromCondition,
  emptyDraftModel,
  isConditionEmpty,
  type ConditionDraftModel,
  type FieldQueryCondition
} from '@/types/condition'

/** A = 同字段多条件（无法用一条草案表达）；B = 单条件。 */
const ORIGINAL: FieldQueryCondition = {
  fields: [
    { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
    { fieldCode: 'code', operator: 'NE', value: 'USD' },
    { fieldCode: 'amount', operator: 'EQ', value: '100' }
  ]
}

function aConditions(condition: FieldQueryCondition | null): unknown {
  return (condition?.fields ?? []).filter((field) => field.fieldCode === 'code')
}

function bConditions(condition: FieldQueryCondition | null): unknown {
  return (condition?.fields ?? []).filter((field) => field.fieldCode === 'amount')
}

/** 模拟表单只改某个字段的值（其它字段草案不动）。 */
function editField(model: ConditionDraftModel, fieldCode: string, value: string): ConditionDraftModel {
  return { ...model, fields: { ...model.fields, [fieldCode]: { operator: 'EQ', value, value2: '' } } }
}

describe('verbatim 按字段合并：只改 B 时 A 的原条件逐字保留', () => {
  it('同字段多条件的 A 不动、B 改值 ⇒ A 两条原条件逐字保留，B 按新草案', () => {
    const model = draftModelFromCondition(ORIGINAL)
    expect(model.verbatim).toEqual(ORIGINAL)

    const edited = applyDraftEdit(model, editField(model, 'amount', '200'))
    const outcome = buildConditionOutcome(edited)

    expect(outcome.status).toBe('ok')
    expect(aConditions(outcome.condition)).toEqual([
      { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
      { fieldCode: 'code', operator: 'NE', value: 'USD' }
    ])
    expect(bConditions(outcome.condition)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '200' }])
  })

  it('连续编辑：第二次改 B 仍不丢 A 原条件（verbatim 继续携带供后续编辑）', () => {
    const model = draftModelFromCondition(ORIGINAL)
    const first = applyDraftEdit(model, editField(model, 'amount', '200'))
    const second = applyDraftEdit(first, editField(first, 'amount', '300'))

    const outcome = buildConditionOutcome(second)
    expect(outcome.status).toBe('ok')
    expect(aConditions(outcome.condition)).toEqual([
      { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
      { fieldCode: 'code', operator: 'NE', value: 'USD' }
    ])
    expect(bConditions(outcome.condition)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '300' }])
  })

  it('scopeKeys 变化不丢字段级原条件（scope 取草案，字段原条件保留）', () => {
    const model = draftModelFromCondition(ORIGINAL)
    const scoped = applyDraftEdit(model, { ...model, scopeKeys: ['XN'] })

    const outcome = buildConditionOutcome(scoped)
    expect(outcome.status).toBe('ok')
    expect(outcome.condition?.scopeKeys).toEqual(['XN'])
    expect(aConditions(outcome.condition)).toEqual([
      { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
      { fieldCode: 'code', operator: 'NE', value: 'USD' }
    ])
    expect(bConditions(outcome.condition)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '100' }])
  })

  it('删除 B 字段 ⇒ 只按新草案处理，B 原条件不再出现（A 仍保留）', () => {
    const model = draftModelFromCondition(ORIGINAL)
    const withoutB: ConditionDraftModel = { ...model, fields: { ...model.fields } }
    delete withoutB.fields.amount

    const outcome = buildConditionOutcome(applyDraftEdit(model, withoutB))

    expect(outcome.status).toBe('ok')
    expect(bConditions(outcome.condition)).toEqual([])
    expect(aConditions(outcome.condition)).toEqual([
      { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
      { fieldCode: 'code', operator: 'NE', value: 'USD' }
    ])
  })

  it('编辑 A 本身（有损字段）⇒ A 只按新草案，B 原条件保留', () => {
    const model = draftModelFromCondition(ORIGINAL)
    const outcome = buildConditionOutcome(applyDraftEdit(model, editField(model, 'code', 'EUR')))

    expect(outcome.status).toBe('ok')
    expect(aConditions(outcome.condition)).toEqual([{ fieldCode: 'code', operator: 'EQ', value: 'EUR' }])
    expect(bConditions(outcome.condition)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '100' }])
  })

  it('未编辑时合并结果仍是原始条件（R0 行为不回归）', () => {
    const model = draftModelFromCondition(ORIGINAL)
    const outcome = buildConditionOutcome(model)

    expect(outcome.status).toBe('ok')
    expect(outcome.condition).toEqual(ORIGINAL)
  })

  it('取值含逗号的 A（IN 多值）在只改 B 后仍逐字保留', () => {
    const condition: FieldQueryCondition = {
      fields: [
        { fieldCode: 'code', operator: 'IN', value: ['CNY,CNH', 'USD'] },
        { fieldCode: 'amount', operator: 'EQ', value: '100' }
      ]
    }
    const model = draftModelFromCondition(condition)
    expect(model.verbatim).toEqual(condition)

    const outcome = buildConditionOutcome(applyDraftEdit(model, editField(model, 'amount', '200')))

    expect(outcome.status).toBe('ok')
    expect(aConditions(outcome.condition)).toEqual([{ fieldCode: 'code', operator: 'IN', value: ['CNY,CNH', 'USD'] }])
    expect(bConditions(outcome.condition)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '200' }])
  })
})

describe('verbatim 不得绕过草案校验（item 6）', () => {
  it('IN [] ⇒ invalid / EMPTY_IN（先校验草案，再谈原条件）', () => {
    const model = draftModelFromCondition({
      fields: [{ fieldCode: 'code', operator: 'IN', value: [] }]
    })

    const outcome = buildConditionOutcome(model)

    expect(outcome.status).toBe('invalid')
    expect(outcome.status === 'invalid' ? outcome.issues.map((issue) => issue.code) : []).toEqual(['EMPTY_IN'])
  })

  it('同字段多条件 + 首条为空值 ⇒ invalid / EMPTY_VALUE（不得凭 verbatim 报成功）', () => {
    const model = draftModelFromCondition({
      fields: [
        { fieldCode: 'code', operator: 'EQ', value: '' },
        { fieldCode: 'code', operator: 'NE', value: 'USD' }
      ]
    })

    const outcome = buildConditionOutcome(model)

    expect(outcome.status).toBe('invalid')
    expect(outcome.status === 'invalid' ? outcome.issues.map((issue) => issue.code) : []).toEqual(['EMPTY_VALUE'])
  })

  it('空模型仍是 cleared（校验通过但无意向条件）', () => {
    expect(buildConditionOutcome(emptyDraftModel()).status).toBe('cleared')
    expect(isConditionEmpty(buildConditionOutcome(emptyDraftModel()).condition)).toBe(true)
  })
})
