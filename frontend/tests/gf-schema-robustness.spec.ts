/**
 * 表单健壮性（Task 7 + fix1）：级联选项的"不得任意通过"与"父值变化后必须裁剪陈旧子值"。
 *
 * 缺陷①（Task 7）：`optionsFrom.map` 的父值没填（或 map 里没有该分支）时 `resolveFieldOptions`
 * 返回空数组，而 enum / multi_select 的成员校验写的是 `options.length > 0 && !options.some(…)` ——
 * 空数组直接跳过，于是"省份=任意字符串"被本地复核放行。
 * 处置：**能枚举的选项源**（静态 options / optionsFrom.map）解析不出选项时，任何提交值都是
 * VALUE_OUT_OF_OPTIONS；**不能枚举的选项源**（optionsFrom.sourceId）保持跳过。
 *
 * 缺陷②（I2）：多选级联的复选框只渲染**当前**选项，父值一改成无分支的值整组消失，而模型里仍是旧值
 * ⇒ 提交被判不在选项内，用户既无法取消勾选也无法重交。处置：父值变化时按新的可解析选项集裁剪
 * 依赖字段的值（`pruneCascadeValues`），并接到渲染器的改值路径上。
 */
import { describe, expect, it } from 'vitest'
import {
  checkFormIssues,
  parseFormSchema,
  pruneCascadeValues,
  resolveFieldOptions,
  visibleValues,
  type FormSpec
} from '@/types/form-schema'

function spec(raw: unknown): FormSpec {
  const parsed = parseFormSchema(raw)
  if (parsed === null) {
    throw new Error('schema 应当解析成功')
  }
  return parsed
}

const CASCADE = {
  scenario: 'FILTER',
  fields: [
    { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
    {
      key: 'province',
      label: '省份',
      type: 'enum',
      optionsFrom: {
        dependsOn: 'country',
        map: { CN: [{ value: 'SC' }, { value: 'BJ' }] }
      }
    }
  ]
}

describe('级联选项：父值缺失时子值不得任意通过', () => {
  it('父值没填 ⇒ 子值判 VALUE_OUT_OF_OPTIONS', () => {
    const parsed = spec(CASCADE)
    const issues = checkFormIssues(parsed, { province: 'SC' })
    expect(issues.map((issue) => issue.key)).toEqual(['province'])
    expect(issues[0]?.code).toBe('VALUE_OUT_OF_OPTIONS')
  })

  it('父值合法但没有对应分支 ⇒ 子值判 VALUE_OUT_OF_OPTIONS', () => {
    const parsed = spec(CASCADE)
    expect(checkFormIssues(parsed, { country: 'US', province: 'SC' }).map((issue) => issue.code))
      .toEqual(['VALUE_OUT_OF_OPTIONS'])
  })

  it('既有口径不降：分支内放行、分支外拒绝', () => {
    const parsed = spec(CASCADE)
    expect(checkFormIssues(parsed, { country: 'CN', province: 'SC' })).toEqual([])
    expect(checkFormIssues(parsed, { country: 'CN', province: 'CA' }).map((issue) => issue.code))
      .toEqual(['VALUE_OUT_OF_OPTIONS'])
  })

  it('multi_select 级联同判据', () => {
    const parsed = spec({
      scenario: 'FILTER',
      fields: [
        { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }] },
        {
          key: 'defs',
          label: '配置项',
          type: 'multi_select',
          optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'CURRENCY' }] } }
        }
      ]
    })
    expect(checkFormIssues(parsed, { defs: ['CURRENCY'] }).map((issue) => issue.code))
      .toEqual(['VALUE_OUT_OF_OPTIONS'])
    expect(checkFormIssues(parsed, { country: 'CN', defs: ['CURRENCY'] })).toEqual([])
  })

  it('sourceId 级联保持跳过（规则层无法枚举后端数据源）', () => {
    const parsed = spec({
      scenario: 'FILTER',
      fields: [
        { key: 'kind', label: '类别', type: 'text' },
        { key: 'def', label: '配置项', type: 'enum', optionsFrom: { dependsOn: 'kind', sourceId: 'definition_fields' } }
      ]
    })
    expect(checkFormIssues(parsed, { kind: 'CURRENCY', def: 'AMOUNT' })).toEqual([])
    // 渲染口径不变：解析不出外部结果时仍是空列表（不是 null）
    expect(resolveFieldOptions(parsed.fields[1]!, { kind: 'CURRENCY' })).toEqual([])
  })
})

describe('级联裁剪：父值变化后清掉不再合法的子值（I2）', () => {
  const MULTI = {
    scenario: 'FILTER',
    fields: [
      { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
      {
        key: 'defs',
        label: '配置项',
        type: 'multi_select',
        optionsFrom: {
          dependsOn: 'country',
          map: { CN: [{ value: 'CURRENCY' }, { value: 'DOC_TYPE' }], US: [{ value: 'DOC_TYPE' }] }
        }
      },
      {
        key: 'province',
        label: '省份',
        type: 'enum',
        optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'SC' }], US: [{ value: 'CA' }] } }
      }
    ]
  }

  it('父值改成无分支 ⇒ 多选子值清空，且表单立刻可以重交', () => {
    const parsed = spec(MULTI)
    const before = { country: 'CN', defs: ['CURRENCY'], province: 'SC' }
    expect(checkFormIssues(parsed, before)).toEqual([])

    const after = pruneCascadeValues(parsed, { ...before, country: 'US' }, 'country')

    expect(after.defs).toEqual([])
    expect(after.province).toBe('')
    expect(checkFormIssues(parsed, after)).toEqual([])
    // 提交投影只丢"可选 + 空串"，清空成 [] 的多选照常提交（后端对空数组的 multi_select 无异议）
    expect(visibleValues(parsed, after)).toEqual({ country: 'US', defs: [] })
  })

  it('仍合法的子值保留（只裁掉不合法的那部分）', () => {
    const parsed = spec(MULTI)
    const after = pruneCascadeValues(parsed,
      { country: 'US', defs: ['CURRENCY', 'DOC_TYPE'], province: 'CA' }, 'country')

    expect(after.defs).toEqual(['DOC_TYPE'])
    expect(after.province).toBe('CA')
  })

  it('enum 子值合法时原样保留、不合法时清成未选', () => {
    const parsed = spec(MULTI)
    const kept = pruneCascadeValues(parsed, { country: 'CN', defs: [], province: 'SC' }, 'country')
    expect(kept.province).toBe('SC')

    const cleared = pruneCascadeValues(parsed, { country: 'US', defs: [], province: 'SC' }, 'country')
    expect(cleared.province).toBe('')
  })

  it('多级级联 A→B→C：A 变 ⇒ B 被裁 ⇒ C 跟着被裁', () => {
    const parsed = spec({
      scenario: 'FILTER',
      fields: [
        { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
        {
          key: 'city',
          label: '城市',
          type: 'enum',
          optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'SH' }], US: [{ value: 'NY' }] } }
        },
        {
          key: 'district',
          label: '区',
          type: 'enum',
          optionsFrom: { dependsOn: 'city', map: { SH: [{ value: 'PD' }], NY: [{ value: 'BK' }] } }
        }
      ]
    })
    const before = { country: 'CN', city: 'SH', district: 'PD' }
    expect(checkFormIssues(parsed, before)).toEqual([])

    const after = pruneCascadeValues(parsed, { ...before, country: 'US' }, 'country')

    expect(after.city).toBe('')
    expect(after.district).toBe('')
    expect(checkFormIssues(parsed, after)).toEqual([])
  })

  it('无法枚举的 sourceId 级联不动（判不了就不猜）', () => {
    const parsed = spec({
      scenario: 'FILTER',
      fields: [
        { key: 'kind', label: '类别', type: 'text' },
        { key: 'def', label: '配置项', type: 'enum', optionsFrom: { dependsOn: 'kind', sourceId: 'definition_fields' } }
      ]
    })
    expect(pruneCascadeValues(parsed, { kind: 'OTHER', def: 'AMOUNT' }, 'kind').def).toBe('AMOUNT')
  })

  it('无关字段原样保留（纯函数，不改入参）', () => {
    const parsed = spec(MULTI)
    const values = { country: 'US', defs: ['CURRENCY'], province: 'SC', note: '用户写的' }
    const snapshot = { ...values }
    const after = pruneCascadeValues(parsed, values, 'country')

    expect(values).toEqual(snapshot)
    expect(after.note).toBe('用户写的')
  })
})
