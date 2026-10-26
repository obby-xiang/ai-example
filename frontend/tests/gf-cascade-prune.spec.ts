/**
 * 级联子值的 UI 清空入口（I2）：父值变化时，渲染器必须把"按新选项已不合法"的子值裁掉。
 *
 * 缺陷（复审 I2）：多选的复选框只渲染**当前**选项 —— 选 `country=CN` 勾上 `defs=[CURRENCY]`，
 * 再把 `country` 改成没有分支的 `US`，复选框整组消失，而模型里仍是 `['CURRENCY']`
 * ⇒ 提交被判 `VALUE_OUT_OF_OPTIONS`，用户既无法取消勾选，也无法重交（只能把父值改回去或重开卡片）。
 * enum 侧同理：控件显示"未选"，模型仍带旧值，提交照样被拒。
 *
 * 本 spec 只经**渲染器的真实交互**（勾选/改父值 ⇒ 读 emit 出来的 draft）断言，不直接调裁剪函数，
 * 因此它在修复前就是红的（复现用户症状），修复后变绿。
 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import { checkFormIssues, parseFormSchema, type FormSpec } from '@/types/form-schema'

const api = vi.hoisted(() => ({
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  aiApi: {},
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: api.formOptionSource
}))

function spec(raw: unknown): FormSpec {
  const parsed = parseFormSchema(raw)
  if (parsed === null) {
    throw new Error('schema 应当解析成功')
  }
  return parsed
}

function lastDraft(wrapper: VueWrapper): Record<string, unknown> {
  const last = (wrapper.emitted('draft') ?? []).at(-1)?.[0]
  if (!last) {
    throw new Error('没有收到 draft 事件')
  }
  return last as Record<string, unknown>
}

const MULTI_CASCADE = {
  scenario: 'FILTER',
  fields: [
    { key: 'country', label: '国家', type: 'enum', options: [{ value: 'CN' }, { value: 'US' }] },
    {
      key: 'defs',
      label: '配置项',
      type: 'multi_select',
      optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'CURRENCY' }, { value: 'DOC_TYPE' }], US: [{ value: 'DOC_TYPE' }] } }
    },
    {
      key: 'province',
      label: '省份',
      type: 'enum',
      optionsFrom: { dependsOn: 'country', map: { CN: [{ value: 'SC' }], US: [{ value: 'CA' }] } }
    }
  ]
}

const mountForm = (schema: FormSpec) =>
  mount(FormRenderer, { props: { schema, submitting: false, error: null } })

describe('I2 级联子值在渲染层被裁剪', () => {
  it('父值改成无分支 ⇒ 陈旧多选值不留在提交里（用户可重交）', async () => {
    const schema = spec(MULTI_CASCADE)
    const wrapper = mountForm(schema)

    // 初始 country = 首个选项 CN
    await wrapper.find('#gf-field-defs-CURRENCY').setValue(true)
    expect(lastDraft(wrapper).defs).toEqual(['CURRENCY'])

    await wrapper.find('#gf-field-country').setValue('US')

    // 复选框整组消失（只剩 US 分支的 DOC_TYPE）
    expect(wrapper.find('#gf-field-defs-CURRENCY').exists()).toBe(false)

    const draft = lastDraft(wrapper)
    expect(draft.defs).toEqual([])
    expect(checkFormIssues(schema, draft)).toEqual([])
  })

  it('仍合法的子值保留，只裁掉不合法的那部分', async () => {
    const schema = spec(MULTI_CASCADE)
    const wrapper = mountForm(schema)

    await wrapper.find('#gf-field-defs-CURRENCY').setValue(true)
    await wrapper.find('#gf-field-defs-DOC_TYPE').setValue(true)
    await wrapper.find('#gf-field-province').setValue('SC')
    expect(lastDraft(wrapper).defs).toEqual(['CURRENCY', 'DOC_TYPE'])

    await wrapper.find('#gf-field-country').setValue('US')

    const draft = lastDraft(wrapper)
    expect(draft.defs).toEqual(['DOC_TYPE'])
    expect(draft.province).toBe('')
    expect(checkFormIssues(schema, draft)).toEqual([])
  })

  it('子值在新分支里仍合法 ⇒ 原样保留（不误清用户输入）', async () => {
    const schema = spec(MULTI_CASCADE)
    const wrapper = mountForm(schema)

    await wrapper.find('#gf-field-defs-DOC_TYPE').setValue(true)
    await wrapper.find('#gf-field-province').setValue('SC')
    // 改到 US：DOC_TYPE 两个分支都有 ⇒ 保留；SC 只在 CN 分支 ⇒ 清掉
    await wrapper.find('#gf-field-country').setValue('US')

    const draft = lastDraft(wrapper)
    expect(draft.defs).toEqual(['DOC_TYPE'])
    expect(draft.province).toBe('')
  })
})

/**
 * AR-07（批次4 前端C）：「父值变化清值**有提示**」。
 *
 * 裁决策略：清值是**一个动作的后果**，不是 N 个独立事件 —— 一次父值改动最多出一句聚合提示，
 * 逐字段弹条会把"我改了一个筛选"变成一串与用户无关的噪音。所以断言点是
 * ① 没清值时没有提示（不预先嚷嚷）；② 清了两个字段也只有**一个**提示元素；
 * ③ 那句提示同时点名被清的两个字段（用户能对上号）。
 */
describe('AR-07 级联清值的一次性告知', () => {
  it('父值变化清掉两个级联子值 ⇒ 只有一句聚合提示，并点名两个字段', async () => {
    const schema = spec(MULTI_CASCADE)
    const wrapper = mountForm(schema)

    await wrapper.find('#gf-field-defs-CURRENCY').setValue(true)
    await wrapper.find('#gf-field-province').setValue('SC')
    // 还没有任何清值 ⇒ 不出现提示
    expect(wrapper.find('[data-testid="gf-cascade-notice"]').exists()).toBe(false)

    // CN → US：CURRENCY 不在 US 分支、SC 也不在 US 分支 ⇒ 两个子值同时被清
    await wrapper.find('#gf-field-country').setValue('US')

    const notices = wrapper.findAll('[data-testid="gf-cascade-notice"]')
    expect(notices).toHaveLength(1)
    const text = notices[0]?.text() ?? ''
    expect(text).toContain('配置项')
    expect(text).toContain('省份')
  })
})
