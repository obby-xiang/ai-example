/**
 * Task 4（导出保真）：导出向导的**条件落库闸**。
 *
 * 真实入口：`export.start`（用户点「开始导出」与 AI 的 start_export 同一条链）。
 *
 * 修复前的失败面：`startExport()` 丢掉 `persistStepData()` 的结局 —— 条件 PUT 失败时
 * 仍然 `POST /jobs` 建导出作业，作业读的是**服务端旧条件**（可能是空条件 ⇒ 导出全量），
 * 界面却按"用户看到的条件"宣称已导出。这是"过滤条件丢失/导出全量"的根面。
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

/** 假 SpreadGrid：`toXlsxBlob()` = 当前载入的字节（没载入过 = 空工作簿）。 */
const grids = vi.hoisted(() => ({
  loaded: {} as Record<string, string>,
  /** 序列化闸：设置后 `toXlsxBlob()` 会先挂起，用来把"保存途中换任务"做成确定性时序 */
  toXlsxGate: null as null | (() => Promise<void>)
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
            return { missingHeaders: [] }
          },
          loadData: () => {
            grids.loaded[props.defCode] = ''
          },
          collectRows: () => [],
          toXlsxBlob: async () => {
            if (grids.toXlsxGate) {
              await grids.toXlsxGate()
            }
            return new Blob([grids.loaded[props.defCode] ?? '<EMPTY-WORKBOOK>'])
          },
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
  // TaskWizard 必须渲染默认插槽：三步都挂在它的 slot 里
  return { default: defineComponent({ name: 'TaskWizard', setup: (_props, { slots }) => () => slots.default?.() }) }
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

/** 服务端已落库的过滤条件（用户在界面上看到的同一条）。 */
const CONDITION_JSON = JSON.stringify({
  scopeKeys: [],
  fields: [{ fieldCode: 'monthlyFee', operator: 'GT', value: '100' }]
})

function exportTask() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep: 'SELECT_DEFS',
    title: '导出任务 7',
    items: [{ id: 1, defCode: 'DEMO', conditionJson: CONDITION_JSON }],
    settingsJson: null
  }
}

const mounted: VueWrapper[] = []

async function mountWizard(): Promise<void> {
  const wrapper = mount(ExportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } })
  mounted.push(wrapper)
  await flushPromises()
}

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  grids.loaded = {}
  grids.toXlsxGate = null
  routeHolder.route.query.taskId = '7'
  api.getTask.mockResolvedValue(exportTask())
  api.getFiles.mockResolvedValue([
    { taskId: 7, defCode: 'DEMO', fileType: 'EXPORT', storagePath: '/srv/DEMO.xlsx', fileName: 'DEMO.xlsx', rowCount: 3 }
  ])
  api.downloadFile.mockImplementation((_taskId: number, defCode: string) => Promise.resolve(new Blob([`${defCode}-BYTES`])))
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask())
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.createJob.mockResolvedValue({ id: 31, jobType: 'EXPORT', status: 'PENDING', progress: 0, total: 0 })
  api.listDefinitions.mockResolvedValue([DEMO_DEF])
  api.countData.mockResolvedValue({ count: 0 })
  // 建作业后页面会挂作业跟踪（SSE 主通道 + 轮询兜底）：给一个不发声的假 EventSource，
  // 本 spec 只断言"是否建作业"，不需要通道行为。
  api.getJob.mockResolvedValue({ id: 31, jobType: 'EXPORT', status: 'RUNNING', progress: 0, total: 0 })
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
})

describe('Task 4④：条件落库失败 ⇒ 不得启动导出作业（否则按服务端旧条件导出全量）', () => {
  it('setCondition reject ⇒ 不 POST /jobs，且回灌如实说明未启动', async () => {
    await mountWizard()
    api.setCondition.mockRejectedValue(new Error('PUT /items/DEMO/condition 500'))

    const execution = await executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 5_000 })
    await flushPromises()

    // 修复前（RED）：persistStepData 的结局被丢弃 ⇒ 照样 POST /jobs（按旧条件/空条件导出全量）
    expect(api.createJob).not.toHaveBeenCalled()
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('未启动')
  })
})

describe('Task 4⑤：条件正常落库时照常启动，且落库的就是界面上的过滤条件（防过度拦截）', () => {
  it('setCondition 拿到带过滤值的条件 JSON，随后 POST /jobs', async () => {
    await mountWizard()

    const execution = await executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 5_000 })
    await flushPromises()

    const conditionCalls = api.setCondition.mock.calls.filter((call) => call[1] === 'DEMO')
    expect(conditionCalls).toHaveLength(1)
    const conditionText = String(conditionCalls[0]?.[2] ?? '')
    expect(conditionText).toContain('monthlyFee')
    expect(conditionText).toContain('100')
    expect(api.createJob).toHaveBeenCalledTimes(1)
    expect(api.createJob.mock.calls[0]?.slice(0, 2)).toEqual([7, 'EXPORT'])
    expect(execution.ok).toBe(true)
  })
})

describe('Task 4⑥：保存编辑时只写"内容确实载入过"的结果表格（不得拿空工作簿覆盖导出文件）', () => {
  it('只载入过一个标签页 ⇒ 只写该配置项；未载入的表格不 PUT', async () => {
    api.getFiles.mockResolvedValue([
      { taskId: 7, defCode: 'DEMO', fileType: 'EXPORT', storagePath: '/srv/DEMO.xlsx', fileName: 'DEMO.xlsx', rowCount: 3 },
      { taskId: 7, defCode: 'OTHER', fileType: 'EXPORT', storagePath: '/srv/OTHER.xlsx', fileName: 'OTHER.xlsx', rowCount: 2 }
    ])
    await mountWizard()
    // 前提：只有首个标签页的表格被载入过（导出结果只懒加载 activeTab）
    expect(grids.loaded.DEMO).toBe('DEMO-BYTES')
    expect(grids.loaded.OTHER).toBeUndefined()

    const saveButton = mounted[0]
      ?.findAll('el-button')
      .find((node) => node.text().includes('保存编辑到服务端'))
    expect(saveButton).toBeTruthy()
    await saveButton?.trigger('click')
    await flushPromises()

    // 修复前（RED）：未载入的 OTHER 也被 PUT（内容为空工作簿 ⇒ 覆盖服务端导出结果）
    const written = api.saveFile.mock.calls.map((call) => [call[0] as number, call[1] as string])
    expect(written).toEqual([[7, 'DEMO']])
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// Task 4 fix1：复核 C2 / I2（导出侧"在途≠已载入"与任务代次闸）
// ══════════════════════════════════════════════════════════════════════════════

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

function saveButton(): ReturnType<VueWrapper['find']> | undefined {
  return mounted[0]?.findAll('el-button').find((node) => node.text().includes('保存编辑到服务端'))
}

describe('Fix1 C2：结果表格下载在途时点「保存编辑到服务端」⇒ 不得写空工作簿', () => {
  it('下载还没落定 ⇒ 该配置项不在"已载入"里，本次零 PUT', async () => {
    const download = deferred<Blob>()
    api.downloadFile.mockImplementation(() => download.promise)
    await mountWizard()
    // 前提：结果清单已到（按钮在），但字节还在路上
    expect(saveButton()).toBeTruthy()

    await saveButton()?.trigger('click')
    await flushPromises()

    // 修复前（RED）：loadedGrids 在下载前置位 ⇒ 拿空工作簿 PUT 覆盖服务端导出结果
    expect(api.saveFile).not.toHaveBeenCalled()

    download.resolve(new Blob(['DEMO-BYTES']))
    await flushPromises()
  })
})

describe('Fix1 I2a：保存编辑途中换任务 ⇒ 不得把 A 的表格字节写到 B', () => {
  it('toXlsxBlob 在途时切到任务 8 ⇒ 本次不发 PUT', async () => {
    await mountWizard()
    expect(grids.loaded.DEMO).toBe('DEMO-BYTES')

    const gate = deferred<void>()
    grids.toXlsxGate = () => gate.promise
    await saveButton()?.trigger('click')
    await flushPromises()

    api.getTask.mockImplementation((id: number) => Promise.resolve({ ...exportTask(), id, items: [] }))
    routeHolder.route.query.taskId = '8'
    await flushPromises()

    gate.resolve()
    await flushPromises()

    // 修复前（RED）：序列化后实时读 taskId.value ⇒ saveFile(8, 'DEMO', A 的字节)
    expect(api.saveFile).not.toHaveBeenCalled()
  })
})

describe('Fix1 I2b：落库途中换任务 ⇒ 不得在 B 上建导出作业', () => {
  it('setCondition 在途时切到任务 8 ⇒ 本次不 POST /jobs（也不往 8 落条件）', async () => {
    await mountWizard()
    const pending = deferred<object>()
    api.setCondition.mockImplementation(() => pending.promise)
    api.getTask.mockImplementation((id: number) => Promise.resolve({ ...exportTask(), id, items: [] }))

    const running = executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 5_000 })
    await flushPromises()

    routeHolder.route.query.taskId = '8'
    await flushPromises()

    pending.resolve({})
    const execution = await running
    await flushPromises()

    expect(api.setCondition.mock.calls.filter((call) => call[0] === 8)).toEqual([])
    expect(api.createJob).not.toHaveBeenCalled()
    expect(execution.ok).toBe(false)
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// Task 4 fix2：复核 N1（导出侧）—— 启动导出在途换任务不得留下转圈按钮
// ══════════════════════════════════════════════════════════════════════════════

describe('Fix2 N1：启动导出在途换任务 ⇒ 「开始导出」按钮的 loading 必须归零', () => {
  it('createJob 悬挂时切到任务 8 ⇒ 按钮不再 loading', async () => {
    const created = deferred<unknown>()
    api.createJob.mockImplementation(() => created.promise)
    await mountWizard()
    // 切到任务 8 后要有可加载的详情（任务 7 保持原样，selectedDefs 才不会空）
    api.getTask.mockImplementation((id: number) => Promise.resolve(
      id === 8 ? { ...exportTask(), id, items: [] } : exportTask()
    ))

    const startButton = () => mounted[0]
      ?.findAll('el-button')
      .find((node) => node.text().includes('开始导出'))
    expect(startButton()?.attributes('loading')).toBe('false')

    void executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 5_000 })
    await flushPromises()
    // 前提：建作业在途 ⇒ 按钮在转圈（落库已完成，startingExport 已置位）
    expect(startButton()?.attributes('loading')).toBe('true')

    routeHolder.route.query.taskId = '8'
    await flushPromises()

    // 修复前（RED）：仅在同代次复位的 finally 被跳过 ⇒ startingExport 永远为 true
    expect(startButton()?.attributes('loading')).toBe('false')

    created.resolve({ id: 31, jobType: 'EXPORT', status: 'PENDING', progress: 0, total: 0 })
    await flushPromises()
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// Task 4 fix3：复核 ② 导出 syncStepToBackend 代次闸 / ① detached 回执不得诱导重试
// ══════════════════════════════════════════════════════════════════════════════

describe('Fix3 ②：步骤同步在途换任务 ⇒ A 的步骤响应不得覆盖 B 的任务快照（导出孪生）', () => {
  it('goToStep 悬挂时切到任务 8，A 返回 CANCELLED ⇒ B 不得变只读（开始导出按钮仍在）', async () => {
    const stepSync = deferred<unknown>()
    api.goToStep.mockImplementation(() => stepSync.promise)
    await mountWizard()
    api.getTask.mockImplementation((id: number) => Promise.resolve(
      id === 8 ? { ...exportTask(), id, items: [] } : exportTask()
    ))

    const startButton = () => mounted[0]
      ?.findAll('el-button')
      .find((node) => node.text().includes('开始导出'))
    expect(startButton()).toBeTruthy()

    const running = executeFrontendTool({ name: 'export.nextStep', args: {}, budgetMs: 5_000 })
    await flushPromises()

    routeHolder.route.query.taskId = '8'
    await flushPromises()

    // A 的步骤响应晚到，且说 A 已取消：若被采用，B 的页面会变只读、开始导出按钮消失
    stepSync.resolve({ ...exportTask(), status: 'CANCELLED' })
    await running
    await flushPromises()

    // 修复前（RED）：实时读 taskId 的回写把 A 的 CANCELLED 挂到 B 上 ⇒ 按钮消失
    expect(startButton()).toBeTruthy()
    expect(api.goToStep.mock.calls.every((call) => call[0] === 7)).toBe(true)
  })
})

describe('Fix3 ①：导出 detached 回执是成功形，且不得出现"重试"引导', () => {
  it('createJob 在途时切到任务 8 ⇒ ok:true + 作业号/归属 + 请勿重复发起，无"重试"字样', async () => {
    const created = deferred<unknown>()
    api.createJob.mockImplementation(() => created.promise)
    await mountWizard()
    api.getTask.mockImplementation((id: number) => Promise.resolve(
      id === 8 ? { ...exportTask(), id, items: [] } : exportTask()
    ))

    const running = executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 5_000 })
    await flushPromises()

    routeHolder.route.query.taskId = '8'
    await flushPromises()

    created.resolve({ id: 31, taskId: 7, jobType: 'EXPORT', status: 'PENDING', progress: 0, total: 0 })
    const execution = await running
    await flushPromises()

    // 修复前（RED）：ok:false ⇒ outcome 'failed'，且回执追加"请提示用户核对网络后重试"（邀请重复导出）
    const text = String(execution.result)
    expect(execution.ok).toBe(true)
    expect(text).toContain('#31')
    expect(text).toContain('任务 #7')
    expect(text).toContain('请勿重复发起')
    expect(text).not.toContain('重试')
  })
})

describe('导出作业未创建时回执必须是失败', () => {
  it('只读任务 ⇒ ok:false 且零 createJob', async () => {
    api.getTask.mockResolvedValue({ ...exportTask(), status: 'CANCELLED' })
    await mountWizard()

    const execution = await executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 5_000 })

    expect(api.createJob).not.toHaveBeenCalled()
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('未启动导出作业')
  })
})
