/**
 * 批次5A 返修 · 导出向导的**条件回填**（只写不跑）。
 *
 * 现场：`enterPage` 改成「声明即全集」后，`loadTask()` 里那次进页声明**没带** `queryConditions`
 * ⇒ 恢复任务时条件被复位成 `{}`。表现是 `get_workspace_state` 把**有条件的**任务报成"全量"，
 * 模型据此以为用户没设过条件（可能建议/发起全量导出）。
 *
 * 收口口径：恢复任务时，条件的权威来源是任务条目自己的 `conditionJson` —— 用既有
 * `parseConditionJson` 解析（与 `draftModels` 同一份解析，不前移成两份口径）后，
 * 随 `enterPage` 一并声明。空条件（`null` / 空对象）不写占位键：那本身就表示"全量、无条件"。
 *
 * 证红：把 `queryConditions: restoredConditions` 从 `loadTask` 的 `enterPage` 调用里删掉 ⇒ 本用例红。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { useWorkspaceStore } from '@/stores/workspace'

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
  saveBlob: vi.fn(),
  downloadBlob: vi.fn()
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

// 子组件桩：避免 SpreadJS 等重组件进 jsdom（与本用例断言无关）。静态路径，逐字。
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
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.saveBlob
}))
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  downloadBlob: api.downloadBlob
}))

/** 任务 7：CURRENCY 有条件、REGION 无条件（空条件不入 queryConditions）。 */
const TASK_7 = {
  id: 7,
  type: 'EXPORT',
  status: 'IN_PROGRESS',
  currentStep: 'QUERY_COND',
  items: [
    {
      defCode: 'CURRENCY',
      // 与 `parseConditionJson` 的回读形状一致（字段键 = fieldCode）
      conditionJson: '{"fields":[{"fieldCode":"code","operator":"EQ","value":"USD"}]}'
    },
    { defCode: 'REGION', conditionJson: null }
  ]
}

/** 组件与断言必须用**同一个** Pinia 实例（否则断言读到的是另一个 store：L-72 同族坑）。 */
let pinia: ReturnType<typeof createPinia>

async function mountWizard(): Promise<void> {
  shallowMount(ExportWizardView, { global: { plugins: [pinia] } })
  await flushPromises()
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(TASK_7)
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(TASK_7)
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([])
})

describe('批次5A 导出向导：恢复任务时 queryConditions 必须回填权威条件', () => {
  it('loadTask 的 enterPage 带上了从 conditionJson 解析出的条件（空条件不占位）', async () => {
    await mountWizard()

    const workspace = useWorkspaceStore()
    // 条件来自任务条目本身（权威），不是页面草案的猜测
    expect(workspace.queryConditions).toEqual({
      CURRENCY: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'USD' }] }
    })
    expect(workspace.queryConditions).not.toHaveProperty('REGION')
    // 同一次声明里的其余页面级事实照旧（复位语义不受影响）
    expect(workspace.pageId).toBe('export')
    expect(workspace.taskId).toBe(7)
    expect(workspace.step).toBe('QUERY_COND')
    expect(workspace.selectedDefs).toEqual(['CURRENCY', 'REGION'])
  })

  it('空条件任务 ⇒ queryConditions 保持 {}（"全量"是事实，不是漏填）', async () => {
    api.getTask.mockResolvedValue({
      ...TASK_7,
      items: [
        { defCode: 'CURRENCY', conditionJson: '' },
        { defCode: 'REGION', conditionJson: '{}' }
      ]
    })

    await mountWizard()

    expect(useWorkspaceStore().queryConditions).toEqual({})
  })
})
