/**
 * 导出向导：AI 表格写入的来源归因（2026-10-29）。
 *
 * 缺陷面：AI 在预览卡上「应用」后，`SpreadGrid` 发 `data-changed`，页面 handler 一律按用户手改处理
 * —— 工作区动作记给「界面」、判脏闸回执也说"你（用户）有尚未保存的编辑"。模型据此误判
 * "用户还没保存"，`start_export` 被当成用户未决事项。
 *
 * 新口径：事件带来源；`ai` ⇒ 工作区动作 source=AI，判脏闸回执明确说"AI 应用后的改动尚未保存"
 * （仍拦下，避免静默丢改动 —— 授权矩阵不变，只修来源与措辞）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
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
  createJob: vi.fn(),
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

/** 真组件替身：暴露结果表格要用的面（载入 / 适配器 / 存盘），不发多余事件。 */
vi.mock('@/components/SpreadGrid.vue', async () => {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      name: 'SpreadGrid',
      props: { defCode: { type: String, default: '' } },
      setup: () => () => null,
      methods: {
        loadFromXlsx: () => ({ missingHeaders: [] }),
        loadFromJson: () => {},
        loadData: () => {},
        collectRows: () => [],
        toXlsxBlob: async () => new Blob(['xlsx']),
        validateLocal: () => [],
        getSheetAdapter: () => ({ contentVersion: () => 1 }),
        whenLoaded: async () => undefined
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
  return {
    default: defineComponent({
      name: 'TaskWizard',
      setup: (_props: unknown, { slots }: { slots: { default?: () => unknown } }) => () => slots.default?.()
    })
  }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues, createJob: api.createJob } }))
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
    status: 'IN_PROGRESS',
    currentStep: 'EXPORT',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: null }],
    settingsJson: null
  }
}

function exportFile() {
  return {
    id: 1,
    taskId: 7,
    defCode: 'CURRENCY',
    fileType: 'EXPORT',
    storagePath: 'x',
    fileName: 'CURRENCY.xlsx',
    rowCount: 2
  }
}

let wrapper: ReturnType<typeof shallowMount>

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(exportTask())
  api.getFiles.mockResolvedValue([exportFile()])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask())
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.createJob.mockResolvedValue({ id: 99 })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
})

afterEach(() => {
  wrapper?.unmount()
})

async function mountWizard(): Promise<void> {
  wrapper = shallowMount(ExportWizardView, {
    global: { plugins: [createPinia()], renderStubDefaultSlot: true },
    attachTo: document.body
  })
  await flushPromises()
}

/** 从结果表格组件发一次 data-changed（来源 = ai / user）。 */
function emitGridChange(origin: 'ai' | 'user'): void {
  const grid = wrapper.findComponent({ name: "SpreadGrid" })
  expect(grid.exists()).toBe(true)
  grid.vm.$emit('data-changed', origin)
}

describe('R1 AI 应用的写入归因给 AI', () => {
  it('data-changed 来源 ai ⇒ 工作区动作 source=AI（不是「界面/用户」）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    emitGridChange('ai')
    await flushPromises()

    const last = workspace.recentActions.at(-1)
    expect(last?.source).toBe('AI')
    expect(last?.summary).toContain('AI')
  })

  it('相邻对照：来源 user ⇒ 仍记给界面（人工编辑的既有口径不丢）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    emitGridChange('user')
    await flushPromises()

    const last = workspace.recentActions.at(-1)
    expect(last?.source).toBe('UI')
  })

  it('只有 AI 改动时 start_export 回执不再说"用户未保存"，而是点名 AI 改动且仍不建作业', async () => {
    await mountWizard()
    await getPageHandler('export.selectDefs')?.({ defCodes: ['CURRENCY'] })
    await flushPromises()

    emitGridChange('ai')
    await flushPromises()

    const reply = await getPageHandler('export.start')?.({})
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    const reason = (reply as { reason: string }).reason
    expect(reason).toContain('AI')
    expect(reason).toContain('尚未保存')
    expect(api.createJob).not.toHaveBeenCalled()
  })

  it('反向：用户手改后的脏闸回执保持原文案（不把用户改动说成 AI 的）', async () => {
    await mountWizard()
    await getPageHandler('export.selectDefs')?.({ defCodes: ['CURRENCY'] })
    await flushPromises()

    emitGridChange('user')
    await flushPromises()

    const reply = await getPageHandler('export.start')?.({})
    const reason = (reply as { reason: string }).reason
    expect(reason).not.toContain('AI')
  })
})
