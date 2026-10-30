/**
 * R5 返修 · 页面 handler 路由：漏传 `conditions` 不得被补成 `{}`（= 显式清空）。
 *
 * 现场（reviewer Important）：`normalizeActionArgs`（页面 handler 的载荷归一）里有
 * `conditions: args.conditions ?? {}` —— 模型/调用方漏传 `conditions` 时，页面 handler 收到的
 * 是 `{}`，走"显式清空"分支：PUT `{}` + 回成功。漏参于是变成了"清空全部筛选条件"。
 *
 * 收口口径：`conditions` 必填；漏传（undefined / null / 根本没有该键）在一切写入前给出明确失败，
 * 零 PUT、零工作区写、零草案/touched 改动；只有**显式 `{}`** 才清空。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
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

function exportTask() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'ACTIVE',
    currentStep: 'QUERY_COND',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: null }],
    settingsJson: null
  }
}

async function mountWizard(): Promise<void> {
  shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
  await flushPromises()
}

async function runSetCondition(args: Record<string, unknown>): ReturnType<typeof executeFrontendTool> {
  return await executeFrontendTool({ name: 'set_condition', args, budgetMs: 5_000 })
}

/** 条件提交点：离开 QUERY_COND（`confirm_step` → `goStep` → `persistStepData`）。 */
async function confirmStep(): Promise<unknown> {
  const handler = getPageHandler('export.nextStep')
  expect(handler).not.toBeNull()
  return await handler?.({ step: 'EXPORT' })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(exportTask())
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask())
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
})

describe('页面 handler 路由：漏传 conditions 不得当清空（R5）', () => {
  it('executeFrontendTool 漏传 conditions ⇒ 失败、零 PUT、工作区无占位', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const execution = await runSetCondition({ defCode: 'CURRENCY' })
    await flushPromises()

    // 修复前（RED）：normalizeActionArgs 把空参补成 {} ⇒ handler 当"显式清空" ⇒ PUT "{}" 且回成功
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('conditions')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('conditions: null / undefined ⇒ 同样失败，零 PUT（只认显式 {}）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const withNull = await runSetCondition({ defCode: 'CURRENCY', conditions: null })
    const withUndefined = await runSetCondition({ defCode: 'CURRENCY', conditions: undefined })
    await flushPromises()

    expect(withNull.ok).toBe(false)
    expect(withUndefined.ok).toBe(false)
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('直接调用页面 handler 漏传 conditions ⇒ 明确指名 conditions 并零写', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('export.setConditions')
    expect(handler).not.toBeNull()
    const reply = await handler?.({ defCode: 'CURRENCY' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('conditions')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })
})

describe('页面 handler 路由：显式 {} 与合法条件仍正常（相邻正向）', () => {
  it('显式 {} ⇒ 设置时零 PUT、工作区删键；confirm_step 才提交 "{}"', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const execution = await runSetCondition({ defCode: 'CURRENCY', conditions: {} })
    await flushPromises()

    expect(execution.ok).toBe(true)
    // 修复前（RED）：设置条件当场 PUT "{}"（"设置即落库"），confirm_step 再写一次
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')

    await confirmStep()
    await flushPromises()

    expect(api.setCondition.mock.calls.map((call) => [call[1], String(call[2])])).toEqual([['CURRENCY', '{}']])
  })

  it('合法条件：设置时零 PUT，confirm_step 在正确路由下原样提交', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()
    const condition = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }

    const execution = await runSetCondition({ defCode: 'CURRENCY', conditions: condition })
    await flushPromises()

    expect(execution.ok).toBe(true)
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions.CURRENCY).toEqual(condition)

    await confirmStep()
    await flushPromises()

    expect(api.setCondition).toHaveBeenCalledTimes(1)
    expect(JSON.parse(String(api.setCondition.mock.calls[0]?.[2] ?? '{}'))).toEqual(condition)
  })
})
