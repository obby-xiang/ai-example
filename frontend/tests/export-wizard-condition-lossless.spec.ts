/**
 * AI 向导数据正确性 · 条件面（视图级）：`confirm_step` 不得把 AI 设置的条件**换义**。
 *
 * 现场：`export.setConditions` 先按原样落库，再把条件转成表单草案；`confirm_step` →
 * `goStep` → `persistStepData` 用草案重建条件并 PUT。旧实现里草案对
 * 「多值 IN」「同字段多条件」是有损的 ⇒ 服务端条件被重写：
 * - `IN ["CNY","USD"]` → `IN "CNY,USD"`；
 * - `EQ CNY` + `NE USD` → 只剩 `EQ CNY`。
 *
 * 收口口径（不等价禁止覆盖）：草案带 `verbatim`（原始条件）时，`persistStepData` 不再
 * 用草案覆盖服务端；能无损表达的条件照旧落库（防过度拦截）；被用户/AI 改过的草案按新值落库。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

/** 多值 IN + 同字段多条件：两项都不是"每字段一条草案"能表达的形态。 */
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

async function mountWizard(task = exportTask(null)): Promise<void> {
  api.getTask.mockResolvedValue(task)
  shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
  await flushPromises()
}

/** 直接取页面能力（与 `confirm_step` 的 handler 分支同一条调用链）。 */
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

describe('AI 设置的条件在 confirm_step 时不得被草案重写（多值 IN）', () => {
  it('多值 IN：confirm_step 的每次写入都保持数组语义（不得压成逗号串）', async () => {
    await mountWizard()

    const setConditions = getPageHandler('export.setConditions')
    expect(setConditions).not.toBeNull()
    const reply = await setConditions?.({
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] }] }
    })
    expect(reply).toEqual(expect.stringContaining('已设置'))
    await flushPromises()

    const firstWrite = conditionWrites()[0]
    expect(firstWrite?.[1]).toBe('CURRENCY')
    expect(JSON.parse(firstWrite?.[2] ?? '{}')).toEqual({ fields: [{ fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] }] })

    await confirmStep()
    await flushPromises()

    // 修复前（RED）：confirm_step 把 IN 数组压成 "CNY,USD" 再 PUT（换义）。
    // 收口后允许"等价重写"，但每份写入都必须是 IN 数组。
    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(writes.length).toBeGreaterThanOrEqual(1)
    for (const write of writes) {
      expect(write[2]).not.toContain('CNY,USD')
      expect(JSON.parse(write[2])).toEqual({
        fields: [{ fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] }]
      })
    }
  })

  it('同字段多条件：confirm_step 不得只留第一条（保留原始条件）', async () => {
    await mountWizard()

    const setConditions = getPageHandler('export.setConditions')
    await setConditions?.({ defCode: 'CURRENCY', conditions: LOSSY_CONDITION })
    await flushPromises()

    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    // 草案无法无损表达 ⇒ 一次都不再覆盖（服务端仍是 AI 原样落库的那份）
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0]?.[2] ?? '{}')).toEqual(LOSSY_CONDITION)
  })
})

describe('相邻路径：能无损表达的条件照旧落库（不得过度拦截）', () => {
  it('AI set_condition（EQ）后 confirm_step 仍落库等价条件', async () => {
    await mountWizard()

    const setConditions = getPageHandler('export.setConditions')
    await setConditions?.({ defCode: 'CURRENCY', conditions: REPRESENTABLE_CONDITION })
    await flushPromises()

    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(writes.length).toBeGreaterThanOrEqual(2)
    expect(JSON.parse(writes.at(-1)?.[2] ?? '{}')).toEqual(REPRESENTABLE_CONDITION)
  })
})

describe('持久化回读：恢复任务后的草案同样不得覆盖服务端条件', () => {
  it('任务 conditionJson 为多值 IN + 同字段多条件 ⇒ confirm_step 零条件写入', async () => {
    await mountWizard(exportTask(JSON.stringify(LOSSY_CONDITION)))

    await confirmStep()
    await flushPromises()

    expect(conditionWrites().filter((call) => call[1] === 'CURRENCY')).toHaveLength(0)
  })

  it('任务 conditionJson 为可表达条件 ⇒ confirm_step 照旧写回等价条件（相邻正向）', async () => {
    await mountWizard(exportTask(JSON.stringify(REPRESENTABLE_CONDITION)))

    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(writes).toHaveLength(1)
    expect(JSON.parse(writes[0]?.[2] ?? '{}')).toEqual(REPRESENTABLE_CONDITION)
  })
})

describe('改过即覆盖：AI 再次设置可表达条件后 confirm_step 落库新值', () => {
  it('先设多值 IN（有损）再设 EQ（可表达）⇒ confirm_step 写 EQ', async () => {
    await mountWizard()
    const setConditions = getPageHandler('export.setConditions')
    await setConditions?.({
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] }] }
    })
    await flushPromises()
    await setConditions?.({ defCode: 'CURRENCY', conditions: REPRESENTABLE_CONDITION })
    await flushPromises()

    await confirmStep()
    await flushPromises()

    const writes = conditionWrites().filter((call) => call[1] === 'CURRENCY')
    expect(JSON.parse(writes.at(-1)?.[2] ?? '{}')).toEqual(REPRESENTABLE_CONDITION)
  })
})
