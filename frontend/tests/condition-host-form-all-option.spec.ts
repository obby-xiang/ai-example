import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import type { FormSpec } from '@/types/form-schema'

const HOST_CONDITION_SCHEMA: FormSpec = {
  scenario: 'FILTER',
  title: '筛选条件（可选）',
  fields: [
    {
      key: 'conditionMode',
      label: '筛选方式',
      type: 'enum',
      required: true,
      defaultValue: 'CUSTOM',
      options: [
        { value: 'CUSTOM', label: '自定义筛选' },
        { value: 'ALL', label: '全部数据（不筛选）' }
      ]
    },
    {
      key: 'conditions',
      label: '筛选条件',
      type: 'text',
      required: false,
      variant: 'longText',
      visibleWhen: { key: 'conditionMode', op: 'eq', value: 'CUSTOM' }
    }
  ]
}

function mountHostForm() {
  return mount(FormRenderer, {
    props: { schema: HOST_CONDITION_SCHEMA, submitting: false, error: null }
  })
}

describe('宿主条件表单的 ALL 选项', () => {
  it('默认 CUSTOM；切到 ALL 隐藏文本且提交时省略 conditions', async () => {
    const wrapper = mountHostForm()
    const mode = wrapper.find<HTMLSelectElement>('#gf-field-conditionMode')
    expect(mode.element.value).toBe('CUSTOM')
    expect(wrapper.find('#gf-field-conditions').exists()).toBe(true)

    await mode.setValue('ALL')
    expect(wrapper.find('#gf-field-conditions').exists()).toBe(false)
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({ conditionMode: 'ALL' })
  })

  it('CUSTOM 空白省略 conditions；ALL 草稿切回 CUSTOM 后原文仍在', async () => {
    const wrapper = mountHostForm()
    await wrapper.find('#gf-field-conditions').setValue('code = CNY')
    await wrapper.find('#gf-field-conditionMode').setValue('ALL')
    expect(wrapper.emitted('draft')?.at(-1)?.[0]).toMatchObject({
      conditionMode: 'ALL',
      conditions: 'code = CNY'
    })

    await wrapper.find('#gf-field-conditionMode').setValue('CUSTOM')
    expect(wrapper.find<HTMLTextAreaElement>('#gf-field-conditions').element.value).toBe('code = CNY')
    await wrapper.find('#gf-field-conditions').setValue('')
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({ conditionMode: 'CUSTOM' })
  })
})
