/**
 * 2026-10-30 复审（纯 UI 复现的根因）：**换任务时旧任务的条件草案不得被写进新任务**。
 *
 * <p>现象：A（任务 #7）的条件草案留在 `draftModels` 里；切到 B（任务 #8）后，B 的任务详情
 * 还没回来（`loadTask` 仍在路上），此时点「下一步」⇒ `goStep → persistStepData` 遍历
 * 新任务的勾选集、却从**旧草案**取条件 ⇒ 把 A 的 `GT 100` PUT 到 B（"说 A 实际给 B"的条件版）。
 *
 * <p>本用例只钉这一件事（单点）：换任务后、详情未回来时点「下一步」，**不得**发生任何
 * `setCondition(8, …)`。修复点 = 换任务清理时把条件草案（含 touched / 预览数字 / 字段错误）
 * 一并清空，等任务详情回来后按 B 的 `items[].conditionJson` 重新水合。
 *
 * <p>刻意不动：本任务内的编辑保留（切步骤不重置草案）、停止时未提交表单草稿的既有规格。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  downloadFile: vi.fn(),
  saveFile: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  listDefinitions: vi.fn(),
  countData: vi.fn(),
  getJob: vi.fn(),
  openTaskEvents: vi.fn()
}))

const routeHolder = vi.hoisted(() => ({ route: null as unknown as { query: { taskId: string } } }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  const { reactive } = await import('vue')
  routeHolder.route = reactive({ query: { taskId: '7' } })
  return {
    ...actual,
    useRoute: () => routeHolder.route,
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))

vi.mock('@/components/SpreadGrid.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'SpreadGridStub',
      props: { defCode: { type: String, default: '' } },
      setup(_props, { expose }) {
        expose({
          loadFromXlsx: async () => ({ missingHeaders: [] }),
          loadData: () => {},
          collectRows: () => [],
          toXlsxBlob: async () => new Blob(['<EMPTY>']),
          validateLocal: () => [],
          getSheetAdapter: () => ({}),
          whenLoaded: async () => {}
        })
        return () => h('div', { class: 'grid-stub' })
      }
    })
  }
})
vi.mock('@/components/JobProgress.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'JobProgress', setup: () => () => null }) }
})
vi.mock('@/components/TaskWizard.vue', async () => {
  const { defineComponent } = await import('vue')
  // 必须渲染默认插槽：三步都挂在它的 slot 里；本用例用 `$emit('next')` 走「下一步」同一条路
  return {
    default: defineComponent({
      name: 'TaskWizard',
      emits: ['next', 'prev', 'step-click'],
      setup: (_props: unknown, { slots }: { slots: { default?: () => unknown } }) => () => slots.default?.()
    })
  }
})
vi.mock('@/components/wizard/DefSelector.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'DefSelector', setup: () => () => null }) }
})
vi.mock('@/components/wizard/ConditionForm.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'ConditionForm', setup: () => () => null }) }
})

import ExportWizardView from '@/views/ExportWizardView.vue'

const DEMO_DEF = {
  id: 1,
  code: 'DEMO',
  name: '演示配置',
  fields: [
    {
      id: 1,
      defCode: 'DEMO',
      code: 'monthlyFee',
      label: '月费',
      fieldType: 'NUMBER' as const,
      required: false,
      key: false,
      sortOrder: 1,
      optionsJson: null
    }
  ]
}

/** 任务 A 已落库的条件（界面草案由它水合出来）。 */
const TASK_A_CONDITION = JSON.stringify({
  scopeKeys: [],
  fields: [{ fieldCode: 'monthlyFee', operator: 'GT', value: '100' }]
})

function taskA() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep: 'SELECT_DEFS',
    title: '导出任务 7',
    items: [{ id: 1, defCode: 'DEMO', conditionJson: TASK_A_CONDITION }],
    settingsJson: null
  }
}

const mounted: VueWrapper[] = []

async function mountWizard(): Promise<VueWrapper> {
  const wrapper = mount(ExportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  routeHolder.route.query.taskId = '7'
  api.getTask.mockResolvedValue(taskA())
  api.getFiles.mockResolvedValue([])
  api.downloadFile.mockResolvedValue(new Blob(['BYTES']))
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(taskA())
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.createJob.mockResolvedValue({ id: 31, jobType: 'EXPORT', status: 'PENDING', progress: 0, total: 0 })
  api.listDefinitions.mockResolvedValue([DEMO_DEF])
  api.countData.mockResolvedValue({ count: 0 })
  api.getJob.mockResolvedValue({ id: 31, jobType: 'EXPORT', status: 'RUNNING', progress: 0, total: 0 })
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
})

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
})

describe('2026-10-30 复审：换任务不得把旧任务条件草案写进新任务', { timeout: 30_000 }, () => {
  it('A 的条件草案不随切任务落到 B：B 详情未回来时点下一步，零 setCondition(8, …)', async () => {
    const wrapper = await mountWizard()
    // 任务 A 的详情已应用 ⇒ draftModels['DEMO'] = A 的 GT 100
    expect(api.getTask).toHaveBeenCalledWith(7)

    // 切到任务 B（#8）：详情**故意不回**（模拟慢详情窗口），但绑定与清理是同步完成的
    api.getTask.mockImplementation(((id: number) => (
      id === 8 ? new Promise(() => {}) : Promise.resolve(taskA())
    )) as never)
    routeHolder.route.query.taskId = '8'
    await flushPromises()

    // B 详情还没回来时点「下一步」：走 goStep → persistStepData（forTask = 8）
    wrapper.findComponent({ name: 'TaskWizard' }).vm.$emit('next')
    await flushPromises()

    // 修复前（RED）：A 的草案被当成 B 的条件 PUT 上去
    expect(api.setCondition).not.toHaveBeenCalled()
  })
})
