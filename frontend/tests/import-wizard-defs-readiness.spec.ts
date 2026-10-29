/**
 * R2 返修 · 导入侧"定义慢加载"窗口：编辑区必须等定义就绪后自动载入（失败仍 fail-closed）。
 *
 * 现场（reviewer）：挂载时 `loadDefinitions()` 与 `loadTask()` 并行；`loadTask` 赋
 * `selectedDefs` 触发 watch → `loadEditors()`，此刻 `defs` 可能还是空数组 ⇒ `editorDefs`
 * 为空 ⇒ 一次什么都没载入就结束；`onMounted` 末尾那次 `loadEditors()` 若也落在定义到达之前，
 * 之后**没有任何人重试** ⇒ 第 1 步编辑区一直空白（用户只能刷新整页）。
 *
 * 收口口径（与导出侧同族）：
 * - `loadDefinitions` 单飞 + `awaitDefinitionsReady`（有界）；
 * - `loadEditorsNow` 在"选中项的定义还没到且定义在途"时先等，再按真实定义算 targets；
 * - 定义到达后 `recoverEditorsAfterDefinitions` 自动补载未载入的编辑区；
 * - 定义加载失败 / 到点仍无定义 ⇒ 保持 fail-closed（不按 0 字段载入、零 `saveFile`）。
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

/** 编辑区表格桩：记录真实载入（`code:列数`）。 */
const grids = vi.hoisted(() => ({ loadCalls: [] as string[] }))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: vi.fn() }
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
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  buildTemplateWorkbookJson: vi.fn().mockResolvedValue('{"sheets":{}}'),
  downloadBlob: vi.fn(),
  zipBlobs: vi.fn(async () => new Blob(['zip']))
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
      loadFromJson(this: { defCode: string }, _json: string, fields: readonly unknown[]) {
        grids.loadCalls.push(`${this.defCode}:${fields.length}`)
      },
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
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 },
    { defCode: 'CURRENCY', code: 'amount', label: '金额', fieldType: 'NUMBER', required: false, key: false, sortOrder: 2 }
  ]
}

function task(id = 7) {
  return {
    id,
    type: 'IMPORT',
    status: 'ACTIVE',
    currentStep: 'UPLOAD',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: null }],
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
  const wrapper = mount(ImportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  grids.loadCalls = []
  routeHolder.route.query.taskId = '7'
  api.getTask.mockImplementation((id: number) => Promise.resolve(task(id)))
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.getDiff.mockResolvedValue({ added: 0, changed: 0, removed: 0 })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.setImportMode.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.goToStep.mockImplementation((id: number) => Promise.resolve(task(id)))
  api.saveFile.mockResolvedValue(undefined)
  api.downloadFile.mockResolvedValue(new Blob(['x']))
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

describe('导入编辑区：定义慢加载时等待就绪并自动载入（不再一直空白）', () => {
  it('定义在途时 selectedDefs watch 先触发 ⇒ 定义到达后自动载入 2 列', async () => {
    const gate = deferred<ConfigDefinition[]>()
    api.listDefinitions.mockReturnValue(gate.promise)

    await mountWizard()
    // 任务/文件已到、定义还在路上：此时不得按 0 字段载入
    expect(grids.loadCalls).toEqual([])

    gate.resolve([CURRENCY_DEF])
    await flushPromises()

    // 修复前（RED）：watch 那次 loadEditors 以空 editorDefs 结束，定义到达后无人重试
    expect(grids.loadCalls).toEqual(['CURRENCY:2'])
  })

  it('定义加载失败 ⇒ fail-closed：不按 0 字段载入、零 saveFile（不产出空文件）', async () => {
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))

    await mountWizard()
    expect(grids.loadCalls).toEqual([])

    const nextStep = getPageHandler('import.nextStep')
    expect(nextStep).not.toBeNull()
    const reply = await nextStep?.({ step: 'PRECHECK' })
    await flushPromises()

    // 没有可写回的编辑区（定义不可用 ⇒ 编辑区从未载入）⇒ 不得 PUT 任何文件
    expect(String(reply)).toContain('未写回服务端文件')
    expect(api.saveFile).not.toHaveBeenCalled()
    expect(grids.loadCalls).toEqual([])
  })

  it('定义就绪（相邻正向）⇒ 照常载入 2 列', async () => {
    await mountWizard()
    expect(grids.loadCalls).toEqual(['CURRENCY:2'])
  })

  it('连续两次工具调用只请求一次定义，第二次在冷却期内快速失败（R3）', async () => {
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))
    await mountWizard()
    // 挂载期唯一一次请求（失败后进入 5s 冷却）
    expect(api.listDefinitions).toHaveBeenCalledTimes(1)

    const sheet = getPageHandler('sheet.current')
    expect(sheet).not.toBeNull()
    const first = await sheet?.({ defCode: 'CURRENCY' })
    const startedAt = Date.now()
    const second = await sheet?.({ defCode: 'CURRENCY' })
    const elapsed = Date.now() - startedAt

    // 修复前（RED）：每次 loadEditors 都重发 GET ⇒ 3 次；第二次调用也不会"快速失败"
    expect(api.listDefinitions).toHaveBeenCalledTimes(1)
    expect(first).toMatchObject({ ok: false })
    expect(second).toMatchObject({ ok: false })
    expect(elapsed).toBeLessThan(500)
    expect(grids.loadCalls).toEqual([])
  })

  it('冷却期内 sheet.current 的失败文案给出可行动成因（定义不可用 / 冷却）', async () => {
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))
    await mountWizard()

    const sheet = getPageHandler('sheet.current')
    expect(sheet).not.toBeNull()
    const reply = await sheet?.({ defCode: 'CURRENCY' })
    await flushPromises()

    // 修复前（RED）：只有"编辑区表格未能载入（请重试…）"，模型读不出"定义不可用 / 正在冷却"
    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('配置定义')
    expect(reason).toContain('冷却')
    expect(grids.loadCalls).toEqual([])
  })

  it('冷却期内显式换任务可重置 ⇒ 重新尝试定义并自动载入', async () => {
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))
    await mountWizard()
    expect(api.listDefinitions).toHaveBeenCalledTimes(1)

    api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
    routeHolder.route.query.taskId = '8'
    await flushPromises()

    expect(api.listDefinitions).toHaveBeenCalledTimes(2)
    expect(grids.loadCalls).toEqual(['CURRENCY:2'])
  })

  it('首轮定义失败后换任务 ⇒ 编辑区载入时补一次定义并自动载入（不再永久空白）', async () => {
    api.listDefinitions.mockRejectedValue(new Error('GET /api/definitions 503'))
    await mountWizard()
    expect(grids.loadCalls).toEqual([])

    // 定义服务恢复：换到任务 8（同一组件、路由 query 变化 ⇒ bindTask → loadTask → watch → loadEditors）
    api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
    routeHolder.route.query.taskId = '8'
    await flushPromises()

    // 修复前（RED）：defs 一直是空数组、无任何重试 ⇒ 新任务的编辑区永远不载入
    expect(grids.loadCalls).toEqual(['CURRENCY:2'])
  })
})
