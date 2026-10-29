/**
 * R1 返修 · 真实 `ConditionForm` 接线：编辑一个字段不得冲掉其它字段的原条件。
 *
 * 本用例**不 stub ConditionForm**（element-plus 真组件一起挂载），在真实 DOM 上改「取值」
 * 输入框 → 组件 emit → `ExportWizardView.onConditionChange` → `applyDraftEdit` 按字段合并
 * → `confirm_step` 落库。目的是证明保留机制不是空转：如果只测纯函数，接线断在视图层
 * （handler 没走到 `applyDraftEdit`，或 `ConditionForm` 的 emit 判据把它吞掉）也照样"绿"。
 *
 * 覆盖：A 同字段多条件（表单表达不了）、用户只改 B → A 两条原条件逐字保持；
 * 连续编辑 B 两次；scopeKeys 变化（表单没有 scope 控件，用真实组件实例 emit 驱动）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  downloadFile: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  listDefinitions: vi.fn(),
  countData: vi.fn(),
  saveFile: vi.fn(),
  saveBlob: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '7' }, params: {} }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.saveBlob
}))

vi.mock('@/components/SpreadGrid.vue', () => ({
  default: defineComponent({ name: 'SpreadGrid', setup: () => () => null })
}))
vi.mock('@/components/JobProgress.vue', () => ({
  default: defineComponent({ name: 'JobProgress', setup: () => () => null })
}))
vi.mock('@/components/TaskWizard.vue', () => ({
  default: defineComponent({ name: 'TaskWizard', setup: (_props: unknown, { slots }: { slots: { default?: () => unknown } }) => () => slots.default?.() })
}))
vi.mock('@/components/wizard/DefSelector.vue', () => ({
  default: defineComponent({ name: 'DefSelector', setup: () => () => null })
}))

import ExportWizardView from '@/views/ExportWizardView.vue'
import ConditionForm from '@/components/wizard/ConditionForm.vue'
import { getPageHandler } from '@/stores/workspace'
import type { FieldQueryCondition } from '@/types/condition'

const CURRENCY_DEF = {
  id: 1,
  code: 'CURRENCY',
  name: '币种',
  level: 'GLOBAL',
  description: '',
  sortOrder: 1,
  fields: [
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 },
    { defCode: 'CURRENCY', code: 'amount', label: '金额', fieldType: 'NUMBER', required: false, key: false, sortOrder: 2 }
  ]
}

/** A = `code` 同字段多条件（表单一条草案表达不了）；B = `amount` 单条件。 */
const ORIGINAL: FieldQueryCondition = {
  fields: [
    { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
    { fieldCode: 'code', operator: 'NE', value: 'USD' },
    { fieldCode: 'amount', operator: 'EQ', value: '100' }
  ]
}

const A_ORIGINAL_JSON = JSON.stringify([
  { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
  { fieldCode: 'code', operator: 'NE', value: 'USD' }
])

function task() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep: 'QUERY_COND',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: JSON.stringify(ORIGINAL) }],
    settingsJson: null
  }
}

const mounted: VueWrapper[] = []

async function mountWizard(): Promise<VueWrapper> {
  const wrapper = mount(ExportWizardView, {
    attachTo: document.body,
    global: { plugins: [createPinia(), ElementPlus] }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

/** 真实表单里的取值输入框（`ConditionForm.valuePlaceholder` 的 EQ 文案是「值」；A 行在前、B 行在后）。 */
function valueInputs(wrapper: VueWrapper): ReturnType<VueWrapper['findAll']> {
  return wrapper.findAll('input').filter((input) => input.attributes('placeholder') === '值')
}

function lastConditionWrite(): FieldQueryCondition {
  const calls = api.setCondition.mock.calls.filter((call) => call[1] === 'CURRENCY')
  return JSON.parse(String(calls.at(-1)?.[2] ?? '{}')) as FieldQueryCondition
}

function codeFields(condition: FieldQueryCondition): unknown {
  return (condition.fields ?? []).filter((field) => field.fieldCode === 'code')
}

function amountFields(condition: FieldQueryCondition): unknown {
  return (condition.fields ?? []).filter((field) => field.fieldCode === 'amount')
}

async function confirmStep(): Promise<void> {
  const handler = getPageHandler('export.nextStep')
  expect(handler).not.toBeNull()
  await handler?.({ step: 'EXPORT' })
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(task())
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(task())
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
  api.saveFile.mockResolvedValue(undefined)
})

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
  document.body.innerHTML = ''
})

describe('真实 ConditionForm：只改 B ⇒ A 的原条件逐字保持', () => {
  it('A 同字段多条件 + B 单条件，改 B 的取值 ⇒ 落库仍是 A 两条原条件 + 新 B', async () => {
    const wrapper = await mountWizard()
    const inputs = valueInputs(wrapper)
    // 真实表单确实渲染了两行取值框：A(code) 与 B(amount)
    expect(inputs.length).toBe(2)
    expect(inputs[0]?.element).toHaveProperty('value', 'CNY')

    await inputs[1]?.setValue('200')
    await flushPromises()

    await confirmStep()

    const written = lastConditionWrite()
    // 修复前（RED）：改 B 后整份 verbatim 被丢，A 只剩首条 EQ CNY（NE USD 丢失）
    expect(JSON.stringify(codeFields(written))).toBe(A_ORIGINAL_JSON)
    expect(amountFields(written)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '200' }])
  })

  it('连续编辑 B 两次 ⇒ 两次落库 A 都逐字保持，B 取最后一次', async () => {
    const wrapper = await mountWizard()
    await valueInputs(wrapper)[1]?.setValue('200')
    await flushPromises()
    await confirmStep()

    const wrapper2Inputs = valueInputs(wrapper)
    await wrapper2Inputs[1]?.setValue('300')
    await flushPromises()
    await confirmStep()

    const written = lastConditionWrite()
    expect(JSON.stringify(codeFields(written))).toBe(A_ORIGINAL_JSON)
    expect(amountFields(written)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '300' }])
  })

  it('scopeKeys 变化（真实组件 emit）⇒ scope 生效且 A 原条件不丢', async () => {
    const wrapper = await mountWizard()
    const form = wrapper.findComponent(ConditionForm)
    expect(form.exists()).toBe(true)

    form.vm.$emit('update:modelValue', {
      scopeKeys: ['XN'],
      fields: {
        code: { operator: 'EQ', value: 'CNY', value2: '' },
        amount: { operator: 'EQ', value: '100', value2: '' }
      }
    })
    await flushPromises()

    await confirmStep()

    const written = lastConditionWrite()
    expect(written.scopeKeys).toEqual(['XN'])
    expect(JSON.stringify(codeFields(written))).toBe(A_ORIGINAL_JSON)
    expect(amountFields(written)).toEqual([{ fieldCode: 'amount', operator: 'EQ', value: '100' }])
  })
})
