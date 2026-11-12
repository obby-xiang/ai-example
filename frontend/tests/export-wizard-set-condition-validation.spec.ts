import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import ConditionForm from '@/components/wizard/ConditionForm.vue'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'

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
  return { default: defineComponent({ name: 'TaskWizard', template: '<div><slot /></div>' }) }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))

function definition(code: string) {
  return {
    id: code,
    code,
    name: code,
    level: 'GLOBAL',
    fields: [
      { defCode: code, code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 },
      { defCode: code, code: 'amount', label: '金额', fieldType: 'NUMBER', required: false, key: false, sortOrder: 2 }
    ]
  }
}

function exportTask(codes: readonly string[], status = 'IN_PROGRESS') {
  return {
    id: 7,
    type: 'EXPORT',
    status,
    currentStep: 'QUERY_COND',
    items: codes.map((defCode, index) => ({ id: index + 1, defCode, conditionJson: null })),
    settingsJson: null
  }
}

function conditionFor(index: number) {
  return index % 2 === 0
    ? { fields: [{ fieldCode: 'code', operator: 'EQ', value: `VALUE_${index}` }] }
    : { fields: [{ fieldCode: 'amount', operator: 'GT', value: String(index) }] }
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

async function mountWizard(codes: readonly string[], status = 'IN_PROGRESS') {
  api.getTask.mockResolvedValue(exportTask(codes, status))
  api.goToStep.mockResolvedValue(exportTask(codes, status))
  api.listDefinitions.mockResolvedValue(codes.map(definition))
  const wrapper = shallowMount(ExportWizardView, {
    attachTo: document.body,
    global: { plugins: [createPinia()], stubs: ELEMENT_STUBS, renderStubDefaultSlot: true }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

async function setConditions(items: unknown, lifetime?: { abandoned: boolean }): Promise<unknown> {
  const handler = getPageHandler('export.setConditions')
  expect(handler).not.toBeNull()
  return await handler?.({ items }, lifetime)
}

async function confirmStep(): Promise<unknown> {
  const handler = getPageHandler('export.nextStep')
  expect(handler).not.toBeNull()
  return await handler?.({ step: 'EXPORT' })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
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
  api.countData.mockResolvedValue({ count: 0 })
})

afterEach(() => {
  for (const wrapper of mounted.splice(0)) {
    wrapper.unmount()
  }
  document.body.innerHTML = ''
})

describe('set_conditions 批量预检与统一草案写入', () => {
  it('15 项一次执行只有一个动作和一份回执，persistStepData 仍逐项提交', async () => {
    const codes = Array.from({ length: 15 }, (_, index) => `DEF_${index}`)
    await mountWizard(codes)
    const workspace = useWorkspaceStore()
    const items = codes.map((defCode, index) => ({
      defCode,
      conditions: index === 14 ? {} : conditionFor(index)
    }))
    const actionCount = workspace.recentActions.length

    const execution = await executeFrontendTool({ name: 'set_conditions', args: { items }, budgetMs: 5_000 })

    expect(execution.outcome).toBe('ok')
    expect(execution.result).toBe('已设置 15 个配置项的查询条件草案')
    expect(workspace.recentActions).toHaveLength(actionCount + 1)
    expect(workspace.recentActions.at(-1)?.summary).toBe('已设置 15 个配置项的查询条件草案')
    for (let index = 0; index < 14; index += 1) {
      expect(workspace.queryConditions[codes[index]]).toEqual(conditionFor(index))
    }
    expect(workspace.queryConditions).not.toHaveProperty('DEF_14')
    expect(api.setCondition).not.toHaveBeenCalled()

    await confirmStep()
    await flushPromises()

    expect(api.setCondition).toHaveBeenCalledTimes(15)
    for (let index = 0; index < 15; index += 1) {
      const write = api.setCondition.mock.calls[index]
      expect(write?.[1]).toBe(codes[index])
      expect(JSON.parse(String(write?.[2]))).toEqual(items[index]?.conditions)
    }
  })

  it('第二项非法时第一项的草案、已改登记、工作区和计数缓存都保持原状', async () => {
    const wrapper = await mountWizard(['CURRENCY', 'REGION'])
    const workspace = useWorkspaceStore()
    const first = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'OLD' }] }
    await setConditions([{ defCode: 'CURRENCY', conditions: first }])
    await flushPromises()
    const beforeModels = wrapper.findAllComponents(ConditionForm).map((form) => form.props('modelValue'))
    const beforeConditions = { ...workspace.queryConditions }
    const actionCount = workspace.recentActions.length

    const reply = await setConditions([
      { defCode: 'CURRENCY', conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'NEW' }] } },
      { defCode: 'REGION', conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: [] }] } }
    ])

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason)).toContain('REGION')
    expect(String((reply as { reason?: string }).reason)).toContain('未填值')
    expect(workspace.queryConditions).toEqual(beforeConditions)
    expect(workspace.recentActions).toHaveLength(actionCount)
    expect(wrapper.findAllComponents(ConditionForm).map((form) => form.props('modelValue'))).toEqual(beforeModels)
    expect(api.setCondition).not.toHaveBeenCalled()

    await confirmStep()
    await flushPromises()
    const firstWrite = api.setCondition.mock.calls.find((call) => call[1] === 'CURRENCY')
    expect(JSON.parse(String(firstWrite?.[2]))).toEqual(first)
  })

  it('任一项未选中时整批拒绝且零写', async () => {
    await mountWizard(['CURRENCY'])
    const workspace = useWorkspaceStore()

    const reply = await setConditions([{ defCode: 'ORDER', conditions: conditionFor(0) }])

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason)).toContain('ORDER')
    expect(String((reply as { reason?: string }).reason)).toContain('未勾选')
    expect(workspace.queryConditions).not.toHaveProperty('ORDER')
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('只读任务整批零写', async () => {
    await mountWizard(['CURRENCY'], 'CANCELLED')
    const workspace = useWorkspaceStore()

    const reply = await setConditions([{ defCode: 'CURRENCY', conditions: conditionFor(0) }])

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason)).toContain('任务已结束')
    expect(workspace.queryConditions).toEqual({})
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('停止标记下整批零写', async () => {
    await mountWizard(['CURRENCY'])
    const workspace = useWorkspaceStore()

    const reply = await setConditions(
      [{ defCode: 'CURRENCY', conditions: conditionFor(0) }],
      { abandoned: true }
    )

    expect(reply).toMatchObject({ ok: false })
    expect(workspace.queryConditions).toEqual({})
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('每项行数缓存分别失效，迟到的旧计数不得覆盖新修改', async () => {
    const codes = Array.from({ length: 15 }, (_, index) => `DEF_${index}`)
    const wrapper = await mountWizard(codes)
    const pending = codes.map(() => deferred<{ count: number }>())
    for (const request of pending) {
      api.countData.mockReturnValueOnce(request.promise)
    }
    const buttons = wrapper.findAll('[data-ai-ref$=".count"]')
    expect(buttons).toHaveLength(15)
    for (const button of buttons) {
      await button.trigger('click')
    }
    expect(api.countData).toHaveBeenCalledTimes(15)

    const items = codes.map((defCode, index) => ({ defCode, conditions: conditionFor(index) }))
    await executeFrontendTool({ name: 'set_conditions', args: { items }, budgetMs: 5_000 })
    api.countData.mockResolvedValueOnce({ count: 99 })
    await buttons[0]?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('99')

    for (let index = 0; index < pending.length; index += 1) {
      pending[index]?.resolve({ count: 1_000 + index })
    }
    await flushPromises()

    expect(wrapper.text()).toContain('99')
    for (let index = 0; index < pending.length; index += 1) {
      expect(wrapper.text()).not.toContain(String(1_000 + index))
    }
  })
})
