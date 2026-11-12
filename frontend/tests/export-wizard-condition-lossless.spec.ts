import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { getPageHandler } from '@/stores/workspace'

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
  countData: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '7' } }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

vi.mock('@/components/SpreadGrid.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'SpreadGrid', setup: () => () => null }) }
})
vi.mock('@/components/JobProgress.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'JobProgress', setup: () => () => null }) }
})
vi.mock('@/components/TaskWizard.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'TaskWizard', setup: () => () => null }) }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))

const CURRENCY_DEF = {
  id: 1,
  code: 'CURRENCY',
  name: '币种',
  level: 'GLOBAL',
  fields: [
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
  ]
}

const LOSSY_CONDITION = {
  fields: [
    { fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] },
    { fieldCode: 'code', operator: 'NE', value: 'JPY' }
  ]
}

const REPRESENTABLE_CONDITION = {
  fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }]
}

function exportTask(conditionJson: string | null) {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep: 'QUERY_COND',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson }],
    settingsJson: null
  }
}

const mounted: ReturnType<typeof shallowMount>[] = []

const ELEMENT_STUBS = {
  'el-button': { template: '<button v-bind="$attrs"><slot /></button>' },
  'el-alert': { template: '<div><slot /></div>' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-divider': { template: '<hr />' },
  'el-checkbox': { template: '<label><slot /></label>' },
  'el-checkbox-group': { template: '<div><slot /></div>' },
  'el-tab-pane': { template: '<div><slot /></div>' },
  'el-tabs': { template: '<div><slot /></div>' }
}

async function mountWizard(task = exportTask(null)): Promise<void> {
  api.getTask.mockResolvedValue(task)
  const wrapper = shallowMount(ExportWizardView, {
    global: { plugins: [createPinia()], stubs: ELEMENT_STUBS }
  })
  mounted.push(wrapper)
  await flushPromises()
}

async function setConditions(conditions: unknown): Promise<unknown> {
  const handler = getPageHandler('export.setConditions')
  expect(handler).not.toBeNull()
  return await handler?.({ items: [{ defCode: 'CURRENCY', conditions }] })
}

async function confirmStep(): Promise<unknown> {
  const handler = getPageHandler('export.nextStep')
  expect(handler).not.toBeNull()
  return await handler?.({ step: 'EXPORT' })
}

function conditionWrites(): Array<[number, string, string]> {
  return api.setCondition.mock.calls.map((call) => [call[0] as number, call[1] as string, String(call[2])])
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask(null))
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
})

afterEach(() => {
  for (const wrapper of mounted.splice(0)) {
    wrapper.unmount()
  }
})

describe('批量条件草案在逐项提交时保持原语义', () => {
  it('多值 IN 和同字段多条件不被压成逗号串或单条件', async () => {
    await mountWizard()

    const reply = await setConditions(LOSSY_CONDITION)
    expect(reply).toMatchObject({ ok: true, result: '已设置 1 个配置项的查询条件草案' })
    expect(conditionWrites()).toEqual([])

    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0]?.[2] ?? '{}')).toEqual(LOSSY_CONDITION)
  })

  it('可表达条件仍按原样逐项提交', async () => {
    await mountWizard()

    await setConditions(REPRESENTABLE_CONDITION)
    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0]?.[2] ?? '{}')).toEqual(REPRESENTABLE_CONDITION)
  })

  it('服务端恢复的未修改草案不被重复覆盖', async () => {
    await mountWizard(exportTask(JSON.stringify(LOSSY_CONDITION)))

    await confirmStep()
    await flushPromises()

    expect(conditionWrites().filter((call) => call[1] === 'CURRENCY')).toHaveLength(0)
  })

  it('后一批修改覆盖前一批，迟到提交只写最新条件', async () => {
    await mountWizard()
    await setConditions(LOSSY_CONDITION)
    await setConditions(REPRESENTABLE_CONDITION)
    await flushPromises()

    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0]?.[2] ?? '{}')).toEqual(REPRESENTABLE_CONDITION)
  })
})
