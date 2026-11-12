import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { useAiStore } from '@/stores/ai'
import { normalizeConditionBatch } from '@/types/tools'

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
  conditionDrafts: vi.fn()
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
vi.mock('@/api/ai', () => ({ aiApi: { conditionDrafts: api.conditionDrafts } }))

const CURRENCY_DEF = {
  id: 1,
  code: 'CURRENCY',
  name: '币种',
  level: 'GLOBAL',
  fields: [
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
  ]
}

const CNY = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }
const USD = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'USD' }] }

function projection(items: unknown[] = [], fingerprint = 'fp-1', complete = true) {
  return { complete, items, fingerprint }
}

function exportTask(conditionJson: string | null = null) {
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

async function mountWizard(conditionJson: string | null = null, sessionId = 'session-test'): Promise<void> {
  api.getTask.mockResolvedValue(exportTask(conditionJson))
  api.goToStep.mockResolvedValue(exportTask(conditionJson))
  const pinia = createPinia()
  setActivePinia(pinia)
  useAiStore().sessionId = sessionId
  const wrapper = shallowMount(ExportWizardView, {
    global: { plugins: [pinia], stubs: ELEMENT_STUBS }
  })
  mounted.push(wrapper)
  await flushPromises()
}

async function confirmStep(): Promise<unknown> {
  const handler = getPageHandler('export.nextStep')
  expect(handler).not.toBeNull()
  return await handler?.({ step: 'EXPORT' })
}

function conditionWrites(): unknown[] {
  return api.setCondition.mock.calls.map((call) => {
    const raw = call[2]
    return typeof raw === 'string' ? JSON.parse(raw) : raw
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
  api.conditionDrafts.mockResolvedValue(projection())
})

afterEach(() => {
  for (const wrapper of mounted.splice(0)) {
    wrapper.unmount()
  }
})

describe('服务端 set_conditions 草案投影恢复', () => {
  it('非空草案刷新后按投影 touched 提交并推进', async () => {
    api.conditionDrafts.mockResolvedValue(projection([{ defCode: 'CURRENCY', conditions: CNY }]))

    await mountWizard()
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([CNY])
    expect(api.conditionDrafts).toHaveBeenCalledTimes(2)
  })

  it('A→B 后只提交服务端投影的 B 条件', async () => {
    api.conditionDrafts.mockResolvedValue(projection([{ defCode: 'CURRENCY', conditions: USD }]))

    await mountWizard()
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([USD])
  })

  it('其他任务无候选时只提交本任务 ALL，不泄露旧条件', async () => {
    await mountWizard()
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([{}])
  })

  it('fingerprint 变化但最新 items 与页面规范条件一致时更新证据并继续', async () => {
    api.conditionDrafts
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: CNY }], 'fp-a'))
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: CNY }], 'fp-b'))

    await mountWizard()
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([CNY])
  })

  it('fingerprint 变化且最新 items 与页面条件不一致时零写', async () => {
    api.conditionDrafts
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: CNY }], 'fp-a'))
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: USD }], 'fp-b'))

    await mountWizard()
    const reply = await confirmStep()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('提交前 fingerprint 相同但 items 变化时同样零写', async () => {
    api.conditionDrafts
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: CNY }], 'fp-same'))
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: USD }], 'fp-same'))

    await mountWizard()
    const reply = await confirmStep()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('初始投影 complete=false 时不提交空条件', async () => {
    api.conditionDrafts.mockResolvedValue(projection([], 'fp-incomplete', false))

    await mountWizard()
    const reply = await confirmStep()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('恢复显式 {} 会清除服务端旧条件', async () => {
    api.conditionDrafts.mockResolvedValue(projection([{ defCode: 'CURRENCY', conditions: {} }]))

    await mountWizard(JSON.stringify(CNY))
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([{}])
  })

  it('无 AI 草案的手工 ALL 仍提交显式空条件', async () => {
    await mountWizard()
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([{}])
  })

  it('空投影后其他实例新增 CNY 且本页未执行 handler 必须拒绝', async () => {
    api.conditionDrafts
      .mockResolvedValueOnce(projection())
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: CNY }], 'fp-remote'))

    await mountWizard()
    const reply = await confirmStep()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('空候选后实时新增草案不被初次空投影误挡', async () => {
    api.conditionDrafts
      .mockResolvedValueOnce(projection())
      .mockResolvedValueOnce(projection([{ defCode: 'CURRENCY', conditions: CNY }], 'fp-live'))

    await mountWizard()
    const handler = getPageHandler('export.setConditions')
    await handler?.({ items: [{ defCode: 'CURRENCY', conditions: CNY }] })
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([CNY])
  })

  it('无 AI 会话的手工流程不读取投影', async () => {
    await mountWizard(null, '')
    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([{}])
    expect(api.conditionDrafts).not.toHaveBeenCalled()
  })
})

describe('normalizeConditionBatch 与后端批量边界一致', () => {
  it('conditions 只接受对象并 trim defCode 后判重和选中', async () => {
    expect(normalizeConditionBatch({ items: [{ defCode: 'CURRENCY', conditions: '{}' }] })).toBeNull()
    expect(normalizeConditionBatch({ items: [{ defCode: '   ', conditions: {} }] })).toBeNull()
    expect(normalizeConditionBatch({
      items: [{ defCode: ' CURRENCY ', conditions: {} }, { defCode: 'CURRENCY', conditions: {} }]
    })).toBeNull()
    expect(normalizeConditionBatch({ items: [{ defCode: ' CURRENCY ', conditions: CNY }] })).toEqual([
      { defCode: 'CURRENCY', conditions: CNY }
    ])

    await mountWizard()
    const handler = getPageHandler('export.setConditions')
    const reply = await handler?.({ items: [{ defCode: ' CURRENCY ', conditions: CNY }] })
    expect(reply).toMatchObject({ ok: true })
    expect(useWorkspaceStore().queryConditions.CURRENCY).toEqual(CNY)
  })
})
