/**
 * 导航就绪第三维：向导页自报"任务详情已应用"（`export.taskDetailReady` 探针）。
 *
 * 真实挂载 `ExportWizardView`（子组件 / 路由 / API 全桩），锁住探针的两端：
 * ① 挂载后探针立即可用（与 `export.nextStep` 同一处注册），`GET /tasks/{id}` 未回时是 **false**；
 * ② 详情应用完（选中集/条件/步骤/状态已恢复并 `enterPage`）⇒ **true**，且不必等作业/结果文件。
 *
 * 这一维是"导航成功前等详情"的判据来源（见 `utils/frontend-tools.ts` 的
 * `TASK_DETAIL_READY_PROBES` / `waitForTaskBound`）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { ElMessage } from 'element-plus'
import ExportWizardView from '@/views/ExportWizardView.vue'
import ImportWizardView from '@/views/ImportWizardView.vue'
import { getPageHandler } from '@/stores/workspace'
import type { Task } from '@/types/task'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  listJobs: vi.fn(async () => []),
  getIssues: vi.fn(async () => ({ content: [] })),
  listDefinitions: vi.fn(async () => [])
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

const TASK_7 = {
  id: 7,
  type: 'EXPORT',
  status: 'IN_PROGRESS',
  items: [{ defCode: 'DEMO', conditionJson: null }],
  currentStep: 'QUERY_COND'
}

/** 手动控制的 promise：让"详情在路上"这个窗口可观测。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

let wrapper: VueWrapper | null = null

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('export.taskDetailReady：详情未应用 ⇒ false；应用完 ⇒ true', () => {
  it('GET 在途时是 false，回来后是 true（不等作业与结果文件）', async () => {
    const pending = deferred<typeof TASK_7>()
    api.getTask.mockReturnValue(pending.promise)

    wrapper = shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
    // 探针与 export.nextStep 同一处注册：挂载即存在（页面就绪维度的前提）
    const probe = getPageHandler('export.taskDetailReady')
    expect(probe).not.toBeNull()
    // 详情还在路上：选中集/条件/步骤都没恢复 ⇒ 不得对外的"已应用"作背书
    expect(probe!({})).toBe(false)

    pending.resolve(TASK_7)
    await flushPromises()

    expect(probe!({})).toBe(true)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('详情读取失败 ⇒ 保持 false（不给"已应用"的假背书）', async () => {
    api.getTask.mockRejectedValue(new Error('任务不存在'))

    wrapper = shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    expect(getPageHandler('export.taskDetailReady')!({})).toBe(false)
  })
})

describe('import.taskDetailReady：同一口径（导入页）', () => {
  it('GET 在途时是 false，回来后是 true', async () => {
    const pending = deferred<Task>()
    api.getTask.mockReturnValue(pending.promise)
    api.getFiles.mockResolvedValue([])

    wrapper = shallowMount(ImportWizardView, { global: { plugins: [createPinia()] } })
    const probe = getPageHandler('import.taskDetailReady')
    expect(probe).not.toBeNull()
    expect(probe!({})).toBe(false)

    pending.resolve({ ...TASK_7, type: 'IMPORT', items: [] })
    await flushPromises()

    expect(probe!({})).toBe(true)
  })
})
