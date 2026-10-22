/**
 * GF 优化批（G-A / G-C / G-D / G-F）前端规则层用例。
 *
 * 锁的是 `types/form-schema.ts` 与后端 `GenerativeFormRules` 的**同源口径**：
 * 纯空格算空、200 字硬顶（longText 2000）、字段 ≤20 / 选项 ≤50、命名格式（禁正则）、
 * number/date/multi_select 的取值约束，以及联动五原语的前端求值。
 */
import { describe, expect, it } from 'vitest'
import {
  MAX_FORM_FIELDS,
  MAX_FORM_OPTIONS,
  MAX_LONGTEXT_VALUE_CHARS,
  MAX_TEXT_VALUE_CHARS,
  applyDefaultFrom,
  buildInitialValues,
  checkFormIssues,
  checkFormValues,
  isFieldDisabled,
  isFieldRequired,
  isFieldVisible,
  parseFormSchema,
  resolveFieldOptions,
  type FormSpec
} from '@/types/form-schema'

function spec(raw: unknown): FormSpec {
  const parsed = parseFormSchema(raw)
  if (parsed === null) {
    throw new Error('schema 应当解析成功')
  }
  return parsed
}

const BASE = {
  scenario: 'FILTER',
  fields: [
    { key: 'mail', label: '邮箱', type: 'text', format: 'email' },
    { key: 'age', label: '年龄', type: 'number', min: 1, max: 120, step: 1, integer: true },
    { key: 'day', label: '日期', type: 'date', min: '2026-01-01', max: '2026-12-31' },
    {
      key: 'tags',
      label: '标签',
      type: 'multi_select',
      options: [{ value: 'A' }, { value: 'B' }, { value: 'C' }],
      minItems: 2,
      maxItems: 3
    }
  ]
}

describe('GF 校验最小集（前端先校验，与后端同源）', () => {
  it('命名格式按枚举校验，不执行任意正则', () => {
    const parsed = spec(BASE)
    expect(checkFormIssues(parsed, { mail: 'a.b@example.com' })).toEqual([])
    const issues = checkFormIssues(parsed, { mail: 'not-an-email' })
    expect(issues.map((issue) => issue.key)).toEqual(['mail'])
    expect(issues[0]?.code).toBe('VALUE_FORMAT_MISMATCH')
  })

  it('number 的 min/max/step/integer 与后端同判据', () => {
    const parsed = spec(BASE)
    expect(checkFormIssues(parsed, { age: 30 })).toEqual([])
    expect(checkFormIssues(parsed, { age: 121 }).map((i) => i.code)).toEqual(['VALUE_OUT_OF_RANGE'])
    expect(checkFormIssues(parsed, { age: 1.5 }).map((i) => i.code)).toEqual(['VALUE_STEP_MISMATCH', 'VALUE_NOT_INTEGER'])
  })

  it('date 的 min/max 按 ISO 字符串比较', () => {
    const parsed = spec(BASE)
    expect(checkFormIssues(parsed, { day: '2026-06-01' })).toEqual([])
    expect(checkFormIssues(parsed, { day: '2027-01-01' }).map((i) => i.code)).toEqual(['VALUE_OUT_OF_RANGE'])
  })

  it('multi_select 的 minItems/maxItems', () => {
    const parsed = spec(BASE)
    expect(checkFormIssues(parsed, { tags: ['A', 'B'] })).toEqual([])
    expect(checkFormIssues(parsed, { tags: ['A'] }).map((i) => i.code)).toEqual(['VALUE_TOO_FEW_ITEMS'])
  })

  it('纯空格算空；200 字硬顶；longText 放宽到 2000', () => {
    const parsed = spec({
      scenario: 'CLARIFY',
      fields: [
        { key: 'name', label: '名称', type: 'text', required: true },
        { key: 'note', label: '备注', type: 'text', variant: 'longText' }
      ]
    })
    expect(checkFormIssues(parsed, { name: '   ' }).map((i) => i.code)).toEqual(['VALUE_REQUIRED_MISSING'])
    expect(checkFormIssues(parsed, { name: 'x', note: '字'.repeat(201) })).toEqual([])
    expect(checkFormIssues(parsed, { name: 'x', note: '字'.repeat(2001) }).map((i) => i.code))
      .toEqual(['VALUE_TOO_LONG'])
    // 常量与后端同名同值（前端先校验的上限来源）
    expect(MAX_TEXT_VALUE_CHARS).toBe(200)
    expect(MAX_LONGTEXT_VALUE_CHARS).toBe(2000)
    expect(MAX_FORM_FIELDS).toBe(20)
    expect(MAX_FORM_OPTIONS).toBe(50)
  })

  it('字段 >20 / 选项 >50 在解析期即拒（不再"前端放行后端 400"）', () => {
    const tooManyFields = {
      scenario: 'FILTER',
      fields: Array.from({ length: 21 }, (_, i) => ({ key: `f${i}`, label: `F${i}`, type: 'text' }))
    }
    expect(parseFormSchema(tooManyFields)).toBeNull()
    const tooManyOptions = {
      scenario: 'FILTER',
      fields: [{
        key: 'e',
        label: 'E',
        type: 'enum',
        options: Array.from({ length: 51 }, (_, i) => ({ value: `v${i}` }))
      }]
    }
    expect(parseFormSchema(tooManyOptions)).toBeNull()
  })

  it('checkFormValues 保持人读清单契约（既有调用方零改动）', () => {
    const parsed = spec(BASE)
    expect(checkFormValues(parsed, { mail: 'bad' })).toHaveLength(1)
    expect(checkFormValues(parsed, { mail: 'bad' })[0]).toContain('邮箱')
  })
})

describe('GF 联动五原语（闭集、无表达式）', () => {
  const linkage = () => spec({
    scenario: 'FILTER',
    fields: [
      { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
      {
        key: 'province',
        label: '省份',
        type: 'enum',
        optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'SC' }, { value: 'BJ' }], US: [{ value: 'CA' }] } },
        visibleWhen: { key: 'country', op: 'notEmpty' },
        requiredWhen: { key: 'country', op: 'eq', value: 'CN' }
      },
      { key: 'note', label: '备注', type: 'text', defaultFrom: { dependsOn: 'country', map: { CN: '国内', US: '海外' } } },
      { key: 'frozen', label: '冻结', type: 'text', disabledWhen: { key: 'country', op: 'in', values: ['US'] } }
    ]
  })

  it('显隐 / 条件必填 / 条件禁用按依赖值求值', () => {
    const parsed = linkage()
    expect(isFieldVisible(fieldOf(parsed, 'province'), { country: 'CN' })).toBe(true)
    expect(isFieldVisible(fieldOf(parsed, 'province'), { country: '' })).toBe(false)
    expect(isFieldRequired(fieldOf(parsed, 'province'), { country: 'CN' })).toBe(true)
    expect(isFieldRequired(fieldOf(parsed, 'province'), { country: 'US' })).toBe(false)
    expect(isFieldDisabled(fieldOf(parsed, 'frozen'), { country: 'US' })).toBe(true)
    expect(isFieldDisabled(fieldOf(parsed, 'frozen'), { country: 'CN' })).toBe(false)
  })

  it('选项级联按父值分支解析（静态 map）', () => {
    const parsed = linkage()
    expect(resolveFieldOptions(fieldOf(parsed, 'province'), { country: 'CN' }).map((o) => o.value))
      .toEqual(['SC', 'BJ'])
    expect(resolveFieldOptions(fieldOf(parsed, 'province'), { country: 'US' }).map((o) => o.value)).toEqual(['CA'])
    expect(resolveFieldOptions(fieldOf(parsed, 'province'), {})).toEqual([])
  })

  it('默认值联动只在父值变化时套用（不覆盖用户已填值）', () => {
    const parsed = linkage()
    const values = { ...buildInitialValues(parsed), country: 'US' }
    expect(applyDefaultFrom(parsed, values, 'country').note).toBe('海外')
    // 已是用户手填值：不覆盖
    const touched = { country: 'CN', note: '用户写的' }
    expect(applyDefaultFrom(parsed, touched, 'country', { touched: new Set(['note']) }).note).toBe('用户写的')
  })

  it('隐藏字段不参与必填/取值复核', () => {
    const parsed = linkage()
    // country 为空 ⇒ province 不可见 ⇒ 即便 requiredWhen 存在也不报错
    expect(checkFormIssues(parsed, {})).toEqual([])
    // country=CN ⇒ province 可见且必填
    expect(checkFormIssues(parsed, { country: 'CN' }).map((i) => i.key)).toEqual(['province'])
  })

  it('条件只能引用已声明字段；非法 op 在解析期被丢弃（不执行表达式）', () => {
    const parsed = spec({
      scenario: 'FILTER',
      fields: [
        { key: 'a', label: 'A', type: 'text' },
        {
          key: 'b',
          label: 'B',
          type: 'text',
          visibleWhen: { key: 'ghost', op: 'eq', value: 'x' }
        },
        {
          key: 'c',
          label: 'C',
          type: 'text',
          visibleWhen: { key: 'a', op: 'regex', value: '.*' }
        },
        { key: 'd', label: 'D', type: 'text', visibleWhen: 'a === 1 || alert(1)' }
      ]
    })
    // 未声明字段的条件：永不成立（字段按不可见处理，宁可少渲染也不猜）
    expect(isFieldVisible(fieldOf(parsed, 'b'), { ghost: 'x', a: '1' })).toBe(false)
    expect(isFieldVisible(fieldOf(parsed, 'c'), { a: '1' })).toBe(false)
    expect(isFieldVisible(fieldOf(parsed, 'd'), { a: '1' })).toBe(false)
  })
})

describe('GF attachment / longText（G-E / G-F）', () => {
  it('attachment 是第七型，值是 fileId 字符串', () => {
    const parsed = spec({
      scenario: 'CLARIFY',
      fields: [{ key: 'evidence', label: '附件', type: 'attachment', required: true }]
    })
    expect(parsed.fields[0]?.type).toBe('attachment')
    expect(checkFormIssues(parsed, { evidence: 'att_12345678' })).toEqual([])
    expect(checkFormIssues(parsed, { evidence: 'https://evil.example/a.bin' }).map((i) => i.code))
      .toEqual(['VALUE_ATTACHMENT_ID_INVALID'])
    expect(checkFormIssues(parsed, {}).map((i) => i.code)).toEqual(['VALUE_REQUIRED_MISSING'])
  })

  it('longText 变体与 format 的 input 类型可由 schema 得出', () => {
    const parsed = spec({
      scenario: 'CLARIFY',
      fields: [
        { key: 'note', label: '备注', type: 'text', variant: 'longText' },
        { key: 'mail', label: '邮箱', type: 'text', format: 'email' }
      ]
    })
    expect(parsed.fields[0]?.variant).toBe('longText')
    expect(parsed.fields[1]?.format).toBe('email')
  })
})

function fieldOf(spec: FormSpec, key: string) {
  const field = spec.fields.find((item) => item.key === key)
  if (!field) {
    throw new Error(`缺少字段 ${key}`)
  }
  return field
}
