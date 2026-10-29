/**
 * R1 返修 · 定义列表**慢加载**：不能先失败且永不重试（初始化空白）。
 *
 * 现场（reviewer Important）：页面挂载时 `loadDefinitions()` 与 `loadTask()` 并行；任务先回来
 * 就会触发 `loadExistingResults → ensureGridLoaded → loadGrid`，而此刻 `defs` 还是空数组 ⇒
 * 判定"0 字段"直接失败；等定义真到达时**没有任何人重试**，导出结果页一直是空白（用户只能
 * 刷新整页）。
 *
 * 收口口径：
 * - `loadGrid` 在定义列表**在途**时等它就绪（有界），再判定字段可用性；
 * - 定义到达后**自动恢复**：重新载入当前激活标签的结果（不再等用户手动重开）；
 * - 定义加载**失败**（或就绪后仍无该定义/无字段）⇒ 保持 fail-closed（不按 0 字段载入、不产出空文件）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import type { ConfigDefinition } from '@/types/definition'

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
  saveFile: vi.fn(),
  saveBlob: vi.fn()
}))

/** 表格桩：记录真实载入（列数），用于证明"等定义到达后按 1 列载入"。 */
const grids = vi.hoisted(() => ({ loadCalls: [] as string[] }))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn() }
  }
})

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '7' }, params: {} }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.saveBlob
}))

vi.mock('@/components/SpreadGrid.vue', () => ({
  default: defineComponent({
    name: 'SpreadGrid',
    props: { defCode: { type: String, default: '' } },
    methods: {
      loadFromXlsx(this: { defCode: string }, _blob: Blob, fields: readonly unknown[]) {
        grids.loadCalls.push(`${this.defCode}:${fields.length}`)
        return { missingHeaders: [] }
      },
      loadFromJson: () => {},
      loadData: () => {},
      collectRows: () => [],
      toXlsxBlob: async () => new Blob(['xlsx']),
      validateLocal: () => [],
      getSheetAdapter: () => ({}),
      whenLoaded: async () => {}
    },
    render: () => null
  })
}))
vi.mock('@/components/JobProgress.vue', () => ({
  default: defineComponent({ name: 'JobProgress', setup: () => () => null })
}))
vi.mock('@/components/TaskWizard.vue', () => ({
  default: defineComponent({
    name: 'TaskWizard',
    setup: (_props: unknown, { slots }: { slots: { default?: () => unknown } }) => () => slots.default?.()
  })
}))
vi.mock('@/components/wizard/DefSelector.vue', () => ({
  default: defineComponent({ name: 'DefSelector', setup: () => () => null })
}))
vi.mock('@/components/wizard/ConditionForm.vue', () => ({
  default: defineComponent({ name: 'ConditionForm', setup: () => () => null })
}))

import ExportWizardView from '@/views/ExportWizardView.vue'
import { getPageHandler } from '@/stores/workspace'

const DEMO_DEF: ConfigDefinition = {
  code: 'DEMO',
  name: '演示配置',
  level: 'GLOBAL',
  description: '',
  sortOrder: 1,
  fields: [{ defCode: 'DEMO', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }]
}

const FILES = [
  { taskId: 7, defCode: 'DEMO', fileType: 'EXPORT', storagePath: '/srv/DEMO.xlsx', fileName: 'DEMO.xlsx', rowCount: 3 }
]

function task() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep: 'EXPORT',
    items: [{ id: 1, defCode: 'DEMO', conditionJson: null }],
    settingsJson: null
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (reason: unknown) => void } {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const mounted: VueWrapper[] = []

async function mountWizard(): Promise<VueWrapper> {
  const wrapper = mount(ExportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

async function openEditor(defCode = 'DEMO'): Promise<unknown> {
  const handler = getPageHandler('export.openEditor')
  expect(handler).not.toBeNull()
  return await handler?.({ defCode, taskId: 7 })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  grids.loadCalls = []
  api.getTask.mockResolvedValue(task())
  api.getFiles.mockResolvedValue(FILES)
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(task())
  api.downloadFile.mockResolvedValue(new Blob(['DEMO-BYTES']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([DEMO_DEF])
  api.countData.mockResolvedValue({ count: 0 })
  api.saveFile.mockResolvedValue(undefined)
})

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
  document.body.innerHTML = ''
})

describe('定义慢加载：等待就绪后按真实列载入（不再初始化空白）', () => {
  it('定义在途时打开编辑器 ⇒ 等定义到达后成功载入 1 列（旧实现立刻失败且不重试）', async () => {
    const gate = deferred<ConfigDefinition[]>()
    api.listDefinitions.mockReturnValue(gate.promise)

    await mountWizard()
    // 任务与文件已到，但定义还在路上：此时不得按 0 字段载入
    const pending = openEditor()
    await flushPromises()
    expect(grids.loadCalls).toEqual([])

    gate.resolve([DEMO_DEF])
    const reply = await pending
    await flushPromises()

    // 修复前（RED）：0 字段失败 ⇒ 结构化失败，且定义到达后没有任何人重试（grids.loadCalls 一直为空）
    expect(typeof reply).toBe('string')
    expect(String(reply)).toContain('已打开')
    expect(grids.loadCalls).toEqual(['DEMO:1'])
  })

  it('定义加载失败 ⇒ 保持 fail-closed：结构化失败、零载入、零下载', async () => {
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))

    await mountWizard()
    const reply = await openEditor()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('字段')
    expect(grids.loadCalls).toEqual([])
    expect(api.downloadFile).not.toHaveBeenCalled()
  })

  it('定义就绪但列表里没有该编码 ⇒ 立即失败（不必等，也不按 0 字段载入）', async () => {
    api.listDefinitions.mockResolvedValue([{ ...DEMO_DEF, code: 'OTHER' }])

    await mountWizard()
    const reply = await openEditor()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('定义列表')
    expect(grids.loadCalls).toEqual([])
  })

  it('定义首次失败后「重试读取」拿到定义 ⇒ 自动恢复载入当前标签（不需要手动重开）', async () => {
    api.listDefinitions.mockRejectedValueOnce(new Error('GET /api/definitions 503'))
    api.listJobs.mockRejectedValueOnce(new Error('GET /api/jobs 503'))

    const wrapper = await mountWizard()
    // 前提：首轮定义失败 ⇒ 没有载入任何列（fail-closed）
    expect(grids.loadCalls).toEqual([])

    api.listDefinitions.mockResolvedValue([DEMO_DEF])
    api.listJobs.mockResolvedValue([])
    const retry = wrapper.findAll('el-button').find((button) => button.text().includes('重试读取'))
    expect(retry).toBeTruthy()
    await retry?.trigger('click')
    await flushPromises()

    // 修复前（RED）：重试只重读清单/作业，不重读定义、也不重载表格 ⇒ 页面一直空白
    expect(grids.loadCalls).toEqual(['DEMO:1'])
  })
})
