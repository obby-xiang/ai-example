/**
 * Task 4（文件保真）：导入向导第 1 步的**写回闸**。
 *
 * 三条真实入口都挂载真 `ImportWizardView`（子组件/API 全桩，SpreadGrid 用可编程假实现：
 * `toXlsxBlob()` 返回"当前表格里是什么字节"），断言的是**服务端调用面**，不是 mock 行为：
 *  ① A→B 换任务：A 的编辑区字节不得被 PUT 到 B（换任务后编辑区未载入 ⇒ 不得写）；
 *  ② GET /files 失败：页面不知道服务端有哪些文件 ⇒ 不得写回（否则空模板覆盖真文件）；
 *  ③ 编辑区未载入（下载失败）：不得把空工作簿 PUT 上去。
 *
 * 修复前的失败面（RED）见各用例注释。
 */
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { executeFrontendTool } from '@/utils/frontend-tools'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  downloadFile: vi.fn(),
  saveFile: vi.fn(),
  selectDefs: vi.fn(),
  goToStep: vi.fn(),
  uploadFile: vi.fn(),
  downloadTemplates: vi.fn(),
  setImportMode: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  createJob: vi.fn(),
  cancelJob: vi.fn(),
  getDiff: vi.fn(),
  listDefinitions: vi.fn(),
  buildTemplate: vi.fn(),
  openTaskEvents: vi.fn(),
  getJob: vi.fn(),
  confirmPublishByMode: vi.fn()
}))

/**
 * 换任务在这页是**路由 query 变化**（同一条路由记录不重建组件）⇒ 假路由必须是 reactive，
 * 否则 watch(() => route.query.taskId) 永远不触发、A→B 场景空转。
 */
const routeHolder = vi.hoisted(() => ({ route: null as unknown as { query: { taskId: string } } }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  const { reactive } = await import('vue')
  routeHolder.route = reactive({ query: { taskId: '1' } })
  return {
    ...actual,
    useRoute: () => routeHolder.route,
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() },
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() }
}))

// 发布确认窗口（fix3）：把"用户确认"改成可控 Promise，才能确定性地把任务切换插进窗口里
vi.mock('@/utils/confirm-irreversible', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  confirmPublishByMode: api.confirmPublishByMode
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
// 模板 JSON 走桩：本 spec 只关心"页面拿什么字节去 PUT"，SpreadJS 生成模板不在断言面内。
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  buildTemplateWorkbookJson: api.buildTemplate
}))

/** 假 SpreadGrid：`toXlsxBlob()` = 当前载入的字节（没载入过 = 空工作簿）。 */
const grids = vi.hoisted(() => ({
  loaded: {} as Record<string, string>,
  loads: [] as string[]
}))

vi.mock('@/components/SpreadGrid.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'SpreadGridStub',
      props: {
        defCode: { type: String, default: '' },
        sheetName: { type: String, default: '' },
        readOnly: { type: Boolean, default: false }
      },
      setup(props, { expose }) {
        expose({
          loadFromXlsx: async (blob: Blob) => {
            grids.loaded[props.defCode] = await blob.text()
            grids.loads.push(`xlsx:${props.defCode}:${grids.loaded[props.defCode]}`)
            return { missingHeaders: [] }
          },
          loadFromJson: async (json: string) => {
            grids.loaded[props.defCode] = json
            grids.loads.push(`json:${props.defCode}`)
          },
          loadData: () => {
            grids.loaded[props.defCode] = ''
          },
          collectRows: () => [],
          toXlsxBlob: async () => new Blob([grids.loaded[props.defCode] ?? '<EMPTY-WORKBOOK>']),
          validateLocal: () => [],
          getSheetAdapter: () => ({}),
          whenLoaded: async () => {}
        })
        return () => h('div', { class: 'grid-stub', 'data-code': props.defCode })
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
  // TaskWizard 必须渲染默认插槽：整页四步都挂在它的 slot 里
  return { default: defineComponent({ name: 'TaskWizard', setup: (_props, { slots }) => () => slots.default?.() }) }
})
vi.mock('@/components/wizard/DefSelector.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'DefSelector', setup: () => () => null }) }
})

import ImportWizardView from '@/views/ImportWizardView.vue'
import { useTaskStore } from '@/stores/task'

const CURRENCY_FIELD = {
  id: 1,
  defCode: 'CURRENCY',
  code: 'code',
  label: '币种编码',
  fieldType: 'STRING' as const,
  required: false,
  key: true,
  sortOrder: 1,
  optionsJson: null
}

const CURRENCY_DEF = { id: 1, code: 'CURRENCY', name: '币种', fields: [CURRENCY_FIELD] }

function task(id: number, defCodes: string[]) {
  return {
    id,
    type: 'IMPORT',
    status: 'IN_PROGRESS',
    currentStep: 'UPLOAD',
    title: `任务 ${id}`,
    items: defCodes.map((defCode, index) => ({ id: index + 1, defCode, conditionJson: null })),
    settingsJson: null
  }
}

function uploadFile(taskId: number, defCode: string) {
  return { taskId, defCode, fileType: 'UPLOAD', storagePath: `/srv/${defCode}.xlsx`, fileName: `${defCode}.xlsx` }
}

/** mount 必须 attachTo：区域注册要求 root 已挂载在当前文档（detached 会被 assertRegionOptions 拒绝）。 */
const mounted: VueWrapper[] = []
/** 同一个 pinia 实例：`mount` 里再 createPinia 会让用例拿到的 store 与页面用的不是同一个。 */
let pinia = createPinia()

async function mountWizard(): Promise<void> {
  const wrapper = mount(ImportWizardView, { attachTo: document.body, global: { plugins: [pinia] } })
  mounted.push(wrapper)
  await flushPromises()
}

afterEach(() => {
  while (mounted.length > 0) {
    const wrapper = mounted.pop()
    wrapper?.unmount()
  }
})

/** saveFile 的载荷是 ArrayBuffer（整本工作簿字节）—— 断言看的是"里面是什么内容"。 */
function writtenText(call: unknown[]): string {
  const payload = call[2]
  if (payload instanceof ArrayBuffer) {
    return new TextDecoder().decode(payload)
  }
  return String(payload)
}

/** 走页面自己的能力入口（用户点「下一步」的同一条链）。 */
async function nextStep(): Promise<{ ok: boolean; result: string }> {
  const execution = await executeFrontendTool({ name: 'import.nextStep', args: {}, budgetMs: 5_000 })
  return { ok: execution.ok === true, result: String(execution.result ?? '') }
}

beforeEach(() => {
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
  grids.loaded = {}
  grids.loads = []
  routeHolder.route.query.taskId = '1'
  api.getTask.mockImplementation((id: number) => Promise.resolve(task(id, ['CURRENCY'])))
  api.getFiles.mockResolvedValue([uploadFile(1, 'CURRENCY')])
  api.downloadFile.mockImplementation((_taskId: number, defCode: string) => Promise.resolve(new Blob([`${defCode}:A-BYTES`])))
  api.saveFile.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.goToStep.mockImplementation((id: number) => Promise.resolve(task(id, ['CURRENCY'])))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  // 作业跟踪（SSE 主通道 + 轮询兜底）：给一个不发声的假 EventSource
  api.getJob.mockResolvedValue({ id: 31, jobType: 'PRECHECK', status: 'RUNNING', progress: 0, total: 0 })
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
  api.confirmPublishByMode.mockResolvedValue(true)
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.buildTemplate.mockResolvedValue('{"template":"empty"}')
})

describe('Task 4①：A→B 换任务不得把 A 的编辑区字节 PUT 到 B', () => {
  it('换到没有该文件的 B 后，编辑区不再是 A 的内容，写回也只可能是 B 自己的内容', async () => {
    await mountWizard()
    // 前提：A 的编辑区确实载入了 A 的文件字节
    expect(grids.loaded.CURRENCY).toBe('CURRENCY:A-BYTES')

    // 换任务：B 没有 CURRENCY 的 UPLOAD 文件
    routeHolder.route.query.taskId = '2'
    api.getFiles.mockResolvedValue([])
    api.downloadFile.mockRejectedValue(new Error('任务 2 没有 CURRENCY 的文件'))
    await flushPromises()

    // 页面态隔离：B 的编辑区是 B 的空模板，不是 A 的数据
    expect(grids.loaded.CURRENCY).toBe('{"template":"empty"}')

    await nextStep()
    await flushPromises()

    // 修复前（RED）：uploadedFiles/editorLoaded 未按任务代次复位 ⇒ 直接 PUT A 的字节到任务 2
    const writesToB = api.saveFile.mock.calls
      .filter((call) => call[0] === 2)
      .map((call) => writtenText(call))
    expect(writesToB).toEqual(['{"template":"empty"}'])
    expect(writesToB.some((text) => text === 'CURRENCY:A-BYTES')).toBe(false)
  })

  it('B 的文件还在下载途中就推进 ⇒ 本次不写回（A 的字节不得趁在途写进 B）', async () => {
    await mountWizard()
    expect(grids.loaded.CURRENCY).toBe('CURRENCY:A-BYTES')

    // B 也有同名配置的文件，但下载悬停在途（真机上就是"刚切任务就点下一步"）
    const pending: { release: ((blob: Blob) => void) | null } = { release: null }
    routeHolder.route.query.taskId = '2'
    api.downloadFile.mockImplementation(() => new Promise<Blob>((resolve) => {
      pending.release = resolve
    }))
    await flushPromises()

    const outcome = await nextStep()
    await flushPromises()

    // 修复前（RED）：stale editorLoaded ⇒ 拿 A 的字节 PUT 到任务 2
    expect(api.saveFile).not.toHaveBeenCalled()
    expect(outcome.ok).toBe(false)
    expect(outcome.result).toContain('未载入')

    pending.release?.(new Blob(['CURRENCY:B-BYTES']))
    await flushPromises()
  })
})

describe('Task 4②：GET /files 失败 ⇒ 不得写回（空模板覆盖服务端真文件）', () => {
  it('文件清单读不到时：不构造模板、不 PUT，且如实回灌未推进', async () => {
    api.getFiles.mockRejectedValue(new Error('GET /files 503'))
    await mountWizard()
    // 清单未知 ⇒ 连模板都不许构造（构造出来就会在第 1 步被当成内容写回）
    expect(grids.loaded.CURRENCY).toBeUndefined()

    const outcome = await nextStep()
    await flushPromises()

    // 修复前（RED）：模板空工作簿被 PUT 覆盖服务端文件
    expect(api.saveFile).not.toHaveBeenCalled()
    expect(outcome.ok).toBe(false)
    expect(outcome.result).toContain('文件清单')
  })
})

describe('Task 4③：编辑区未载入 ⇒ 不得 PUT 空工作簿', () => {
  it('UPLOAD 文件下载失败（表格从未载入）时，写回不发出', async () => {
    api.downloadFile.mockRejectedValue(new Error('GET /files/CURRENCY 500'))
    await mountWizard()
    expect(grids.loaded.CURRENCY).toBeUndefined()

    const outcome = await nextStep()
    await flushPromises()

    // 修复前（RED）：未载入的表格被当成空表 PUT 上去
    expect(api.saveFile).not.toHaveBeenCalled()
    expect(outcome.ok).toBe(false)
  })

  it('载入成功时照常写回（防过度拦截的对照）', async () => {
    await mountWizard()
    expect(grids.loaded.CURRENCY).toBe('CURRENCY:A-BYTES')

    const outcome = await nextStep()
    await flushPromises()

    const call = api.saveFile.mock.calls[0] ?? []
    expect(api.saveFile).toHaveBeenCalledTimes(1)
    expect(call[0]).toBe(1)
    expect(call[1]).toBe('CURRENCY')
    expect(writtenText(call)).toBe('CURRENCY:A-BYTES')
    expect(outcome.ok).toBe(true)
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// Task 4 fix1：复核 C1 / I1 / I3 / I5 / I4（代次与"载入先于清单"的语义修复）
// ══════════════════════════════════════════════════════════════════════════════

/** 手动放行的挂起 Promise（确定性"晚到"时序：回调在指定时刻才落定）。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

describe('Fix1 C1：B 有同名上传文件、文件清单晚于编辑区触发到达时，编辑区必须是 B 的文件内容', () => {
  it('清单晚到 ⇒ 不构造模板；清单落定后按文件载入，写回的是 B 的文件字节', async () => {
    await mountWizard()
    expect(grids.loaded.CURRENCY).toBe('CURRENCY:A-BYTES')

    // 换到 B：B 也有 CURRENCY 的 UPLOAD 文件，但 GET /files 悬停在途（真实时序：网络慢于 nextTick）
    const files = deferred<unknown[]>()
    routeHolder.route.query.taskId = '2'
    api.getFiles.mockImplementation((id: number) => (id === 2 ? files.promise : Promise.resolve([])))
    api.downloadFile.mockImplementation((id: number, defCode: string) =>
      Promise.resolve(new Blob([`${defCode}:${id === 2 ? 'B-BYTES' : 'A-BYTES'}`])))
    await flushPromises()

    // 清单未到 ⇒ 编辑区被清空且不得出现"模板"（模板会被当成 B 的内容写回）
    expect(grids.loaded.CURRENCY).toBe('')
    expect(grids.loads.some((entry) => entry.startsWith('json:CURRENCY'))).toBe(false)

    files.resolve([uploadFile(2, 'CURRENCY')])
    await flushPromises()

    // 清单落定后按文件载入：内容是 B 的字节
    expect(grids.loaded.CURRENCY).toBe('CURRENCY:B-BYTES')

    await nextStep()
    await flushPromises()

    const writesToB = api.saveFile.mock.calls
      .filter((call) => call[0] === 2)
      .map((call) => writtenText(call))
    expect(writesToB).toEqual(['CURRENCY:B-BYTES'])
  })
})

describe('Fix1 I1：GET /files 首次失败、补读成功 ⇒ 先按文件重载，再写回文件内容', () => {
  it('补读成功后不得把"清单未知期间"的状态写回（此处连模板都没构造过）', async () => {
    let attempts = 0
    api.getFiles.mockImplementation(() => {
      attempts += 1
      return attempts === 1
        ? Promise.reject(new Error('GET /files 503'))
        : Promise.resolve([uploadFile(1, 'CURRENCY')])
    })
    await mountWizard()
    // 首次失败后页面会再补读一次（清单是载入的前置条件）⇒ 落定时按文件载入，绝不构造模板
    expect(attempts).toBeGreaterThanOrEqual(2)
    expect(grids.loads.every((entry) => !entry.startsWith('json:'))).toBe(true)
    expect(grids.loaded.CURRENCY).toBe('CURRENCY:A-BYTES')

    await nextStep()
    await flushPromises()

    const call = api.saveFile.mock.calls[0] ?? []
    expect(api.saveFile).toHaveBeenCalledTimes(1)
    expect(call[0]).toBe(1)
    expect(call[1]).toBe('CURRENCY')
    // 写回的是服务端文件字节（来自补读成功后的重载），不是模板/空工作簿
    expect(writtenText(call)).toBe('CURRENCY:A-BYTES')
  })
})

describe('Fix1 I3：旧代次的下载结果不得画进新任务的编辑区', () => {
  it('A 的下载在换任务之后才返回 ⇒ 不落格、不标记（B 的编辑区保持 B 的内容）', async () => {
    // A 的文件下载悬停在途（挂载完成时编辑区尚未载入）
    const aDownload = deferred<Blob>()
    api.downloadFile.mockImplementation((id: number, defCode: string) =>
      id === 1 ? aDownload.promise : Promise.resolve(new Blob([`${defCode}:B-BYTES`])))
    await mountWizard()
    expect(grids.loaded.CURRENCY).toBeUndefined()

    // 换到没有该文件的 B：B 走模板分支
    routeHolder.route.query.taskId = '2'
    api.getFiles.mockResolvedValue([])
    await flushPromises()
    expect(grids.loaded.CURRENCY).toBe('{"template":"empty"}')

    // A 的字节迟到：不得画进 B 的面板
    aDownload.resolve(new Blob(['CURRENCY:A-BYTES']))
    await flushPromises()

    expect(grids.loads.some((entry) => entry.includes('A-BYTES'))).toBe(false)
    expect(grids.loaded.CURRENCY).toBe('{"template":"empty"}')
  })
})

describe('Fix1 I5：旧任务的作业列表晚到 ⇒ 不得挂到新任务的作业面板/跟踪面', () => {
  it('A 的 listJobs 在换到 B 之后返回 ⇒ 不拉 A 的问题清单、不登记 A 的 watcher', async () => {
    const aJobs = deferred<unknown[]>()
    api.listJobs.mockImplementation((id: number) => (id === 1 ? aJobs.promise : Promise.resolve([])))
    await mountWizard()

    routeHolder.route.query.taskId = '2'
    await flushPromises()

    aJobs.resolve([{ id: 31, taskId: 1, jobType: 'PRECHECK', status: 'RUNNING', progress: 0, total: 0 }])
    await flushPromises()

    expect(api.getIssues.mock.calls.filter((call) => call[0] === 31)).toEqual([])
    expect(api.openTaskEvents.mock.calls.filter((call) => call[1] === 31)).toEqual([])
  })
})

describe('Fix1 I4：新建任务走同一条"换任务"链路，且合法的新任务导入仍可写回', () => {
  it('无任务页点「创建并进入」⇒ 复位并换代次、按新任务拉清单、模板可写回新任务', async () => {
    routeHolder.route.query.taskId = ''
    api.getTask.mockImplementation((id: number) => Promise.resolve(task(id, ['CURRENCY'])))
    api.getFiles.mockResolvedValue([])
    const store = useTaskStore()
    const createTask = vi.spyOn(store, 'createTask').mockResolvedValue(task(9, ['CURRENCY']) as never)

    await mountWizard()
    expect(api.getFiles).not.toHaveBeenCalled()

    const createButton = mounted[0]
      ?.findAll('el-button')
      .find((node) => node.text().includes('创建导入任务并进入'))
    expect(createButton).toBeTruthy()
    await createButton?.trigger('click')
    await flushPromises()

    expect(createTask).toHaveBeenCalledTimes(1)
    // 新任务同样先拉清单再载入编辑区（同一套绑定/代次流程）
    expect(api.getFiles.mock.calls.map((call) => call[0])).toEqual([9])
    expect(grids.loaded.CURRENCY).toBe('{"template":"empty"}')

    await nextStep()
    await flushPromises()

    const call = api.saveFile.mock.calls[0] ?? []
    expect(call[0]).toBe(9)
    expect(call[1]).toBe('CURRENCY')
    // 合法路径：新任务确实没有文件 ⇒ 空模板照常写回（不得因修闸而卡死新建任务）
    expect(writtenText(call)).toBe('{"template":"empty"}')
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// Task 4 fix2：复核 N1 / N2（任务切换后的 loading 标志与作业/快照归属）
// ══════════════════════════════════════════════════════════════════════════════

describe('Fix2 N1：操作在途换任务 ⇒ 新任务页面不得残留 loading（按钮永久转圈）', () => {
  it('写回在途（selectDefs 悬挂）时换任务 ⇒ 保存草稿按钮的 loading 必须归零', async () => {
    const hang = deferred<object>()
    api.selectDefs.mockImplementation(() => hang.promise)
    await mountWizard()

    const flushButton = () => mounted[0]?.find('[data-ai-ref="import.upload.flush"]')
    expect(flushButton()?.attributes('loading')).toBe('false')

    void executeFrontendTool({ name: 'import.nextStep', args: {}, budgetMs: 5_000 })
    await flushPromises()
    // 前提：写回在途 ⇒ 按钮在转圈
    expect(flushButton()?.attributes('loading')).toBe('true')

    routeHolder.route.query.taskId = '2'
    await flushPromises()

    // 修复前（RED）：只在同代次才复位的 finally 被跳过 ⇒ busy 永远停在 'flush'
    expect(flushButton()?.attributes('loading')).toBe('false')

    hang.resolve({})
    await flushPromises()
  })
})

describe('Fix2 N2：建作业在途换任务 ⇒ A 的作业不得挂到 B 的面板/跟踪面', () => {
  it('createJob 在途时切到任务 2 ⇒ 不拉 A 的 issues、不为 A 登记 watcher、回执按原任务说明', async () => {
    const created = deferred<unknown>()
    api.createJob.mockImplementation(() => created.promise)
    await mountWizard()

    const running = executeFrontendTool({ name: 'import.startPrecheck', args: {}, budgetMs: 5_000 })
    await flushPromises()

    routeHolder.route.query.taskId = '2'
    await flushPromises()

    created.resolve({ id: 99, taskId: 1, jobType: 'PRECHECK', status: 'RUNNING', progress: 0, total: 0 })
    const execution = await running
    await flushPromises()

    // 修复前（RED）：attachJob/beginWatchJob 用默认代次 ⇒ getIssues(99) + openTaskEvents(2)
    expect(api.getIssues.mock.calls.filter((call) => call[0] === 99)).toEqual([])
    expect(api.openTaskEvents).not.toHaveBeenCalled()
    // 作业**已建**（在任务 1 上）：回执必须是成功/信息形，且点明归属与"请勿重复发起"，
    // 不能是 failed（review ①：failed 会诱导模型重发一个已经在跑的作业）
    expect(execution.ok).toBe(true)
    expect(String(execution.result)).toContain('#99')
    expect(String(execution.result)).toContain('任务 #1')
    expect(String(execution.result)).toContain('请勿重复发起')
    expect(String(execution.result)).not.toContain('重试')
  })

  it('步骤同步在途换任务 ⇒ A 的步骤响应不得覆盖 B 的任务快照（只读态不得被带偏）', async () => {
    const stepSync = deferred<unknown>()
    api.goToStep.mockImplementation(() => stepSync.promise)
    await mountWizard()

    const running = executeFrontendTool({ name: 'import.nextStep', args: {}, budgetMs: 5_000 })
    await flushPromises()

    routeHolder.route.query.taskId = '2'
    await flushPromises()

    // A 的步骤响应回来了，且它说 A 已被取消（若被采用，B 的页面会变成只读、上传区消失）
    stepSync.resolve({ ...task(1, ['CURRENCY']), status: 'CANCELLED' })
    await running
    await flushPromises()

    // 修复前（RED）：实时读 taskId 的回写把 A 的 CANCELLED 快照挂到 B 上 ⇒ 上传区按钮消失
    expect(mounted[0]?.find('[data-ai-ref="import.upload.flush"]').exists()).toBe(true)
    // 步骤同步始终按入口捕获的任务号发出（不得把 B 的步骤写回/写错任务）
    expect(api.goToStep.mock.calls.every((call) => call[0] === 1)).toBe(true)
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// Task 4 fix3：复核 ① 回执真实性 / ③ 发布确认窗口的代次
// ══════════════════════════════════════════════════════════════════════════════

describe('Fix3 ③：发布确认窗口在途换任务 ⇒ A 的确认不得在 B 上建发布作业', () => {
  it('确认框悬挂时切到任务 2 ⇒ 零 createJob，回执如实说明"确认期间任务已切换"', async () => {
    const confirm = deferred<boolean>()
    api.confirmPublishByMode.mockImplementation(() => confirm.promise)
    await mountWizard()

    const running = executeFrontendTool({ name: 'import.startPublish', args: {}, budgetMs: 5_000 })
    await flushPromises()

    routeHolder.route.query.taskId = '2'
    await flushPromises()

    // 用户对**任务 1**点了"确认"：这不能变成任务 2 的发布作业（REPLACE 会删 B 的行）
    confirm.resolve(true)
    const execution = await running
    await flushPromises()

    // 修复前（RED）：确认之后 startJob 捕获的是**当前**任务 ⇒ createJob(2, 'PUBLISH')
    expect(api.createJob).not.toHaveBeenCalled()
    expect(execution.ok).toBe(false)
    const text = String(execution.result)
    expect(text).toContain('任务已切换')
    expect(text).toContain('未发起任何发布作业')
  })
})

describe('Fix3 ①：作业已建但本页挂接抛错 ⇒ 回执不得说"未启动"', () => {
  it('createJob 成功、登记 watcher 抛错 ⇒ 成功形回执 + 作业号 + 请勿重复发起', async () => {
    api.createJob.mockResolvedValue({ id: 77, taskId: 1, jobType: 'PRECHECK', status: 'RUNNING', progress: 0, total: 0 })
    api.openTaskEvents.mockImplementation(() => {
      throw new Error('SSE 通道创建失败')
    })
    await mountWizard()

    const execution = await executeFrontendTool({ name: 'import.startPrecheck', args: {}, budgetMs: 5_000 })
    await flushPromises()

    // 修复前（RED）：外层 catch 把"已建作业 + 挂接失败"折成 {job:null} ⇒ 回执"预检查未启动"（诱使重发）
    const text = String(execution.result)
    expect(api.createJob).toHaveBeenCalledTimes(1)
    expect(execution.ok).toBe(true)
    expect(text).toContain('#77')
    expect(text).toContain('请勿重复发起')
    expect(text).not.toContain('未启动')
    expect(text).toContain('SSE 通道创建失败')
  })
})

describe('作业未创建时回执必须是失败', () => {
  it('createJob 拒绝 ⇒ ok:false 且保留真实失败原因', async () => {
    api.createJob.mockRejectedValue(new Error('创建作业连接失败'))
    await mountWizard()

    const execution = await executeFrontendTool({ name: 'import.startPrecheck', args: {}, budgetMs: 5_000 })

    expect(api.createJob).toHaveBeenCalledTimes(1)
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('创建作业连接失败')
  })

  it('只读任务 ⇒ ok:false 且零 createJob', async () => {
    api.getTask.mockResolvedValue({ ...task(1, ['CURRENCY']), status: 'CANCELLED' })
    await mountWizard()

    const execution = await executeFrontendTool({ name: 'import.startImport', args: {}, budgetMs: 5_000 })

    expect(api.createJob).not.toHaveBeenCalled()
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('未启动导入作业')
  })
})
