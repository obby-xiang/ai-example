/**
 * 导入向导第 1 步的**草案提交点**：配置项选择是页面草案，只有"下一步"才写 task_items。
 *
 * 现场（reviewer）：`downloadTemplates`（下载模板）与 `uploadOne`（上传文件）在发包前都调
 * `persistSelection()` ⇒ `POST /select-defs`。这两个动作都不是"确定选择"：
 * - 下载模板只是拿当前草案的编码去取空模板，不改变服务端任务；
 * - 上传文件的落库对象是 `task_files` + 文件存储，文件名匹配只需要"当前草案有哪些编码"，
 *   不需要（也不该顺带）建立 task_items。
 * 于是用户仅仅下载模板/上传一个文件，任务就被写成"已选这些配置项"。
 *
 * 收口口径：
 * - 下载模板：直接用当前 `selectedDefs` 请求 `GET /files/templates?codes=…`，零 `selectDefs`；
 * - 上传：把当前 `defCodes` 随 multipart 请求携带（由后端按这批编码做文件名匹配），零 `selectDefs`；
 * - 下一步（flushEditors → persistSelection）：保持既有契约，仍提交选择集。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import type { ConfigDefinition } from '@/types/definition'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  getDiff: vi.fn(),
  listDefinitions: vi.fn(),
  setImportMode: vi.fn(),
  setCondition: vi.fn(),
  selectDefs: vi.fn(),
  goToStep: vi.fn(),
  saveFile: vi.fn(),
  uploadFile: vi.fn(),
  downloadTemplates: vi.fn(),
  downloadFile: vi.fn(),
  countData: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  openTaskEvents: vi.fn(),
  getJob: vi.fn(),
  saveBlob: vi.fn()
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn(), alert: vi.fn() }
  }
})

const routeHolder = vi.hoisted(() => ({ route: null as unknown as { query: { taskId?: string } } }))

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

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.saveBlob
}))
const excel = vi.hoisted(() => ({ downloadBlob: vi.fn() }))
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  buildTemplateWorkbookJson: vi.fn().mockResolvedValue('{"sheets":{}}'),
  downloadBlob: excel.downloadBlob,
  zipBlobs: vi.fn(async () => new Blob(['zip']))
}))

vi.mock('@/components/SpreadGrid.vue', () => ({
  default: defineComponent({
    name: 'SpreadGrid',
    props: { defCode: { type: String, default: '' } },
    methods: {
      loadFromXlsx: () => ({ missingHeaders: [] }),
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

import ImportWizardView from '@/views/ImportWizardView.vue'
import { getPageHandler } from '@/stores/workspace'

const CURRENCY_DEF: ConfigDefinition = {
  code: 'CURRENCY',
  name: '币种',
  level: 'GLOBAL',
  description: '',
  sortOrder: 1,
  fields: [
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
  ]
}

const COUNTRY_DEF: ConfigDefinition = {
  code: 'COUNTRY',
  name: '国家',
  level: 'GLOBAL',
  description: '',
  sortOrder: 2,
  fields: [
    { defCode: 'COUNTRY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
  ]
}

function task(id = 7) {
  return {
    id,
    type: 'IMPORT',
    status: 'ACTIVE',
    currentStep: 'UPLOAD',
    items: [
      { id: 1, defCode: 'CURRENCY', conditionJson: null },
      { id: 2, defCode: 'COUNTRY', conditionJson: null }
    ],
    settingsJson: null
  }
}

const mounted: VueWrapper[] = []

async function mountWizard(): Promise<VueWrapper> {
  const wrapper = mount(ImportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  routeHolder.route.query.taskId = '7'
  api.getTask.mockImplementation((id: number) => Promise.resolve(task(id)))
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.getDiff.mockResolvedValue({ added: 0, changed: 0, removed: 0 })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF, COUNTRY_DEF])
  api.setImportMode.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.goToStep.mockImplementation((id: number) => Promise.resolve(task(id)))
  api.saveFile.mockResolvedValue(undefined)
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.downloadTemplates.mockResolvedValue(new Blob(['tpl']))
  api.countData.mockResolvedValue({ count: 0 })
  api.createJob.mockResolvedValue({ id: 31, jobType: 'PRECHECK', status: 'PENDING', progress: 0, total: 0 })
  api.cancelJob.mockResolvedValue({})
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
  api.getJob.mockResolvedValue({ id: 31, jobType: 'PRECHECK', status: 'RUNNING', progress: 0, total: 0 })
})

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
  document.body.innerHTML = ''
})

describe('下载模板只读当前草案：不得先提交选择集', () => {
  it('点「下载模板」⇒ 用当前 selectedDefs 请求模板，零 selectDefs', async () => {
    const wrapper = await mountWizard()

    await wrapper.find('[data-ai-ref="import.upload.templates"]').trigger('click')
    await flushPromises()

    expect(api.downloadTemplates).toHaveBeenCalledTimes(1)
    expect(api.downloadTemplates).toHaveBeenCalledWith(7, ['CURRENCY', 'COUNTRY'])
    // 修复前（RED）：downloadTemplates 先 persistSelection ⇒ POST /select-defs （选择集被草案动作落库）
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(excel.downloadBlob).toHaveBeenCalledTimes(1)
  })
})

describe('上传文件携带当前草案 defCodes：不得先提交选择集', () => {
  it('上传一个文件 ⇒ uploadFile 收到当前 defCodes，零 selectDefs，匹配结果照常回填', async () => {
    await mountWizard()
    const file = new File(['x'], 'COUNTRY.xlsx')
    api.uploadFile.mockResolvedValue({
      matchedFiles: [{ defCode: 'COUNTRY', fileName: 'COUNTRY.xlsx' }],
      unmatchedFiles: [],
      count: 1
    })

    const handler = getPageHandler('import.receiveFile')
    expect(handler).not.toBeNull()
    const reply = await handler?.({ file })
    await flushPromises()

    expect(api.uploadFile).toHaveBeenCalledTimes(1)
    const [forTask, sentFile, sentCodes] = api.uploadFile.mock.calls[0] ?? []
    expect(forTask).toBe(7)
    expect(sentFile).toBe(file)
    // 修复前（RED）：第三条实参不存在（后端只能靠"预先写入的 TaskItem"匹配）
    expect(sentCodes).toEqual(['CURRENCY', 'COUNTRY'])
    // 修复前（RED）：uploadOne 先 persistSelection ⇒ POST /select-defs
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(String(reply)).toContain('已上传并匹配 1 个文件')
  })
})

describe('下一步仍是选择集的唯一提交点', () => {
  it('从第 1 步推进 ⇒ 先提交当前选择集（persistSelection 契约不变）', async () => {
    await mountWizard()

    const handler = getPageHandler('import.nextStep')
    expect(handler).not.toBeNull()
    await handler?.({ step: 'PRECHECK' })
    await flushPromises()

    expect(api.selectDefs).toHaveBeenCalledWith(7, ['CURRENCY', 'COUNTRY'])
    expect(api.goToStep).toHaveBeenCalledWith(7, 'PRECHECK')
  })
})
