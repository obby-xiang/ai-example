/**
 * 导出向导页面能力的"作废后零写"回归锁（2026-10-29）。
 *
 * 页面 handler 拿到 `UiEventLifetime`（第二参数）后，必须在**入口**与**每个 await 之后**
 * 读它：预算到点或用户停止 ⇒ 不写选中集 / 条件 / 工作区 / 步骤。本文件覆盖三条可达写入口
 * （selectDefs / setConditions / nextStep）与一条在途路径（confirm_step 落库 await 期间停止）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { useAiStore } from '@/stores/ai'

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
  cancel: vi.fn(),
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
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

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues, createJob: vi.fn() } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

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
    status: 'IN_PROGRESS',
    currentStep: 'QUERY_COND',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: null }],
    settingsJson: null
  }
}

const mounted: ReturnType<typeof shallowMount>[] = []

async function mountWizard(): Promise<void> {
  mounted.push(shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } }))
  await flushPromises()
}

/** 已作废的存活标记（预算到点 / 停止后的形态）。 */
const abandonedLifetime = { abandoned: true }

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(exportTask())
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask())
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
  api.cancel.mockResolvedValue({})
})

describe('R1 入口即作废：三条写入口零写', () => {
  it('export.selectDefs：作废 ⇒ 结构化失败，页面选中集与工作区都不动', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()
    const before = [...workspace.selectedDefs]

    const reply = await getPageHandler('export.selectDefs')?.({ defCodes: ['CURRENCY'] }, abandonedLifetime)

    expect(reply).toMatchObject({ ok: false })
    expect((reply as { reason: string }).reason).toContain('预算已到点或本轮已停止')
    expect(workspace.selectedDefs).toEqual(before)
  })

  it('export.setConditions：作废 ⇒ 零 setCondition、工作区无该条件', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await getPageHandler('export.setConditions')?.(
      { defCode: 'CURRENCY', conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] } },
      abandonedLifetime
    )

    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).toEqual({})
  })

  it('export.nextStep：作废 ⇒ 零落库、零步骤推进（工作区 step 不变）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()
    const before = workspace.step

    const reply = await getPageHandler('export.nextStep')?.({ step: 'EXPORT' }, abandonedLifetime)

    expect(reply).toMatchObject({ ok: false })
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(api.goToStep).not.toHaveBeenCalled()
    expect(workspace.step).toBe(before)
  })

  it('相邻对照：同一 handler 不传 lifetime（用户手点/直调）照常工作', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await getPageHandler('export.selectDefs')?.({ defCodes: ['CURRENCY'] })

    expect(typeof reply).toBe('string')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
  })
})

describe('R2 在途作废：confirm_step 落库 await 期间停止 ⇒ 不切步骤、不写工作区', () => {
  it('stop() 后落库返回 ⇒ goStep 停在原步（零 setStep / 零 goToStep）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()
    const before = workspace.step

    let releaseSelect: () => void = () => {}
    api.selectDefs.mockImplementation(() => new Promise<void>((resolve) => {
      releaseSelect = resolve
    }))

    const store = useAiStore()
    store.$patch({ loading: true, activeRunId: 'run-1' })
    const pending = executeFrontendTool({ name: 'confirm_step', args: { step: 'EXPORT' }, budgetMs: 5_000 })
    // 等 persistStepData 进入 selectDefs 的 await
    await vi.waitFor(() => {
      expect(api.selectDefs).toHaveBeenCalled()
    })

    await store.stop()
    releaseSelect()
    await pending

    expect(workspace.step).toBe(before)
    expect(api.goToStep).not.toHaveBeenCalled()
  })
})
