/**
 * 条件草案的**逆映射必须无损**（AI 向导数据正确性 · 条件面）。
 *
 * 现场：AI `set_condition` 先按原样落库，再把条件转成表单草案（`draftModelFromCondition`）。
 * 旧实现按 `fieldCode` 分组、只留第一条（GTE+LTE 合并成 BETWEEN 除外），且 IN 的数组值
 * 被 `join(',')` 压成字符串。随后 `confirm_step` / `persistStepData` 用草案重建条件并 PUT
 * —— 服务端条件被**换义**：
 * - `IN ["A","B"]` → `IN "A,B"`（后端按字符串比较 ⇒ 命中 0 行）；
 * - 同字段多条件丢条目（`EQ A` + `NE B` ⇒ 只剩 `EQ A`）。
 *
 * 收口口径：草案层对"能无损表达的条件"必须往返等价；不能无损表达时，草案携带原始条件
 * （`verbatim`），任何重建都返回原条件（等价），且视图不得用草案覆盖服务端。
 */
import { describe, expect, it } from 'vitest'
import {
  buildConditionOutcome,
  draftContentEqual,
  draftModelFromCondition,
  emptyDraftModel,
  type FieldQueryCondition
} from '@/types/condition'

describe('条件草案逆映射：多值 IN 与同字段多条件必须无损', () => {
  it('多值 IN：草案往返后仍是数组，条件不变（不得压成逗号串）', () => {
    const condition: FieldQueryCondition = {
      fields: [{ fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] }]
    }

    const model = draftModelFromCondition(condition)
    const outcome = buildConditionOutcome(model)

    // 能无损表达 ⇒ 不挂 verbatim（否则后续用户编辑会一直被原条件遮住）
    expect(model.verbatim).toBeUndefined()
    expect(outcome.status).toBe('ok')
    expect(outcome.condition).toEqual(condition)
  })

  it('同字段多条件：草案保留原条件，不得只留第一条', () => {
    const condition: FieldQueryCondition = {
      fields: [
        { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
        { fieldCode: 'code', operator: 'NE', value: 'USD' }
      ]
    }

    const model = draftModelFromCondition(condition)

    // 表单模型每字段只放得下一条 ⇒ 原始条件必须原样保留
    expect(model.verbatim).toEqual(condition)
    expect(buildConditionOutcome(model)).toMatchObject({ status: 'ok', condition })
  })

  it('可表达条件：往返等价且不携带 verbatim（不得过度拦截）', () => {
    const condition: FieldQueryCondition = {
      scopeKeys: ['XN'],
      fields: [
        { fieldCode: 'amount', operator: 'GTE', value: '100' },
        { fieldCode: 'amount', operator: 'LTE', value: '200' }
      ]
    }

    const model = draftModelFromCondition(condition)

    expect(model.verbatim).toBeUndefined()
    expect(buildConditionOutcome(model)).toMatchObject({
      status: 'ok',
      condition: {
        scopeKeys: ['XN'],
        fields: [
          { fieldCode: 'amount', operator: 'GTE', value: '100' },
          { fieldCode: 'amount', operator: 'LTE', value: '200' }
        ]
      }
    })
  })

  it('模型被替换（用户/AI 改过）后不再保留原条件：按新草案落库', () => {
    const lossy = draftModelFromCondition({
      fields: [
        { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
        { fieldCode: 'code', operator: 'NE', value: 'USD' }
      ]
    })
    expect(lossy.verbatim).toBeDefined()

    const edited = emptyDraftModel()
    edited.fields.code = { operator: 'EQ', value: 'CNY', value2: '' }

    expect(buildConditionOutcome(edited)).toMatchObject({
      status: 'ok',
      condition: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }
    })
  })

  it('草案内容相等判定忽略 verbatim（表单重建不得把"未改动"当成改动）', () => {
    const lossy = draftModelFromCondition({
      fields: [
        { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
        { fieldCode: 'code', operator: 'NE', value: 'USD' }
      ]
    })
    const rebuilt = { ...emptyDraftModel(lossy.scopeKeys), fields: { ...lossy.fields } }

    expect(draftContentEqual(lossy, rebuilt)).toBe(true)
    expect(rebuilt.verbatim).toBeUndefined()
  })

  it('IN 空白取值仍判非法（拆分后为空列表，不得静默变成"无条件"）', () => {
    const model = emptyDraftModel()
    model.fields.code = { operator: 'IN', value: ' , ', value2: '' }

    const outcome = buildConditionOutcome(model)

    expect(outcome.status).toBe('invalid')
    expect(outcome.status === 'invalid' ? outcome.issues.map((issue) => issue.code) : []).toEqual(['EMPTY_IN'])
  })
})
