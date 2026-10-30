/**
 * 2026-10-30 复审 I-A：算子切换的取值归一（只此一例锁两个方向）。
 *
 * <p>① `IN`（多选，逗号串承载）→ `EQ`：只能取首段（否则提交 `EQ "CNY,USD"`，后端按字符串
 * 比较几乎永不命中，本地校验又看不出来）；
 * <p>② 自由文本里的逗号**不得**被误伤：`EQ "Smith, John"` → `CONTAINS` 必须原样保留
 * （这条防的是"无脑按逗号切"的过修）。
 *
 * <p>挂载用真实 `ConditionForm`，只把 `el-select` / `el-option` / `el-input` 换成原生控件桩
 * （Element Plus 的下拉是 teleport + popper，jsdom 里点选不稳；桩上仍是同一条
 * `model-value` / `update:modelValue` 契约，新旧实现都能跑）。
 */
import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import type { ConditionDraftModel } from '@/types/condition'
import type { ConfigField } from '@/types/definition'
import ConditionForm from '@/components/wizard/ConditionForm.vue'

const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: {
    modelValue: { type: [String, Array], default: '' },
    multiple: { type: Boolean, default: false }
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    const onChange = (event: Event): void => {
      const target = event.target as HTMLSelectElement
      if (props.multiple) {
        emit('update:modelValue', Array.from(target.selectedOptions).map((option) => option.value))
        return
      }
      emit('update:modelValue', target.value)
    }
    return { onChange }
  },
  template: '<select class="stub-select" :multiple="multiple" @change="onChange"><slot /></select>'
})

const ElOptionStub = defineComponent({
  name: 'ElOption',
  props: { label: { type: String, default: '' }, value: { type: String, default: '' } },
  template: '<option :value="value">{{ label }}</option>'
})

const ElInputStub = defineComponent({
  name: 'ElInput',
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(_props, { emit }) {
    const onInput = (event: Event): void => {
      emit('update:modelValue', (event.target as HTMLInputElement).value)
    }
    return { onInput }
  },
  template: '<input class="stub-input" @input="onInput" />'
})

const stubs = {
  'el-select': ElSelectStub,
  'el-option': ElOptionStub,
  'el-input': ElInputStub,
  'el-date-picker': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' }
}

const fields: ConfigField[] = [
  {
    defCode: 'CURRENCY_DEF',
    code: 'CURRENCY',
    label: '货币',
    fieldType: 'ENUM',
    required: false,
    key: false,
    sortOrder: 1,
    optionsJson: JSON.stringify([{ value: 'CNY', label: '人民币' }, { value: 'USD', label: '美元' }])
  },
  {
    defCode: 'OWNER_DEF',
    code: 'OWNER',
    label: '负责人',
    fieldType: 'STRING',
    required: false,
    key: false,
    sortOrder: 2
  }
]

function draftOf(fieldsMap: ConditionDraftModel['fields']): ConditionDraftModel {
  return { scopeKeys: [], fields: fieldsMap }
}

/** 挂载并把"当前行第二个下拉"（字段=第 1 个、算子=第 2 个）暴露出来。 */
function mountWith(draft: ConditionDraftModel) {
  const wrapper = mount(ConditionForm, {
    props: { fields, modelValue: draft },
    global: { stubs }
  })
  return {
    wrapper,
    /** 表单最近一次 emit 的草案模型（未 emit 过则为初始值）。 */
    model(): ConditionDraftModel {
      const emitted = wrapper.emitted('update:modelValue') ?? []
      return (emitted.at(-1)?.[0] as ConditionDraftModel | undefined) ?? draft
    },
    operatorSelect() {
      return wrapper.findAll('select')[1]!
    }
  }
}

describe('2026-10-30 复审 I-A：算子切换的取值归一', { timeout: 20_000 }, () => {
  it('IN 多选切到 EQ 只取首段；自由文本 Smith, John 切到 CONTAINS 不截断', async () => {
    // ① 枚举面：IN（多选）值 = 逗号串 → 切 EQ
    const enumCase = mountWith(draftOf({ CURRENCY: { operator: 'IN', value: 'CNY,USD', value2: '' } }))
    await flushPromises()
    await enumCase.operatorSelect().setValue('EQ')
    await flushPromises()
    expect(enumCase.model().fields.CURRENCY).toMatchObject({ operator: 'EQ', value: 'CNY' })
    enumCase.wrapper.unmount()

    // ② 文本面：自由文本里的逗号不是多值分隔符 → 切 CONTAINS 必须原样
    const textCase = mountWith(draftOf({ OWNER: { operator: 'EQ', value: 'Smith, John', value2: '' } }))
    await flushPromises()
    await textCase.operatorSelect().setValue('CONTAINS')
    await flushPromises()
    expect(textCase.model().fields.OWNER).toMatchObject({ operator: 'CONTAINS', value: 'Smith, John' })
    textCase.wrapper.unmount()
  })
})
