/**
 * R-09（发布确认）+ R3-02（confirm_step 的写回回执）。
 *
 * 锁三件事：
 * 1. **REPLACE 发布必须走复选框确认**（页面按钮 / `import.startPublish` handler 同口径）：
 *    未勾选点「确定」不关框、零 `createJob`；勾选后才真正发作业；
 * 2. **MERGE 发布维持免确认**（不弹对话框，行为与现状一致）；
 * 3. `confirm_step` 从第 1 步推进时，回执必须如实说“已把整表写回服务端文件”，
 *    与实际 `saveFile` 调用一致；失败时不得谎称已写回。
 *
 * 对话框用桩（真组件在 jsdom 里走 teleport + 过渡），`beforeClose` 与复选框 VNode
 * 都按真实实现驱动 —— 与 `confirm-irreversible.spec.ts` 同一取证手法。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import type { VNode } from 'vue'

const confirmMock = vi.hoisted(() => vi.fn())
const gridState = vi.hoisted(() => ({ invalidDef: '' }))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
    ElMessageBox: { confirm: confirmMock }
  }
})

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
  getJob: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '3' }, params: {} }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  buildTemplateWorkbookJson: vi.fn().mockResolvedValue('{"sheets":{}}')
}))

// 表格引擎不进 jsdom；暴露 flushEditors 真正调用到的方法（toXlsxBlob / validateLocal）。
// `gridState.invalidDef` 用来让某一个配置项的本地校验失败（覆盖 partialNote 分支）。
vi.mock('@/components/SpreadGrid.vue', () => ({
  default: defineComponent({
    name: 'SpreadGrid',
    props: { defCode: { type: String, default: '' } },
    methods: {
      loadFromXlsx: async () => ({ missingHeaders: [] }),
      loadFromJson: async () => {},
      loadData: () => {},
      collectRows: () => [],
      toXlsxBlob: async () => new Blob(['xlsx']),
      validateLocal(this: { defCode?: string }) {
        return this.defCode === gridState.invalidDef
          ? [{ rowIndex: 1, columnCode: 'code', reason: '本地校验注入失败' }]
          : []
      },
      getSheetAdapter: () => ({}),
      whenLoaded: async () => {}
    },
    render: () => null
  })
}))

import ElementPlus, { ElMessage } from 'element-plus'
import ImportWizardView from '@/views/ImportWizardView.vue'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'
import type { ConfigDefinition } from '@/types/definition'

const DEFS: ConfigDefinition[] = [
  {
    code: 'CURRENCY',
    name: '币种',
    level: 'GLOBAL',
    description: '',
    sortOrder: 1,
    fields: [
      { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 },
      { defCode: 'CURRENCY', code: 'amount', label: '金额', fieldType: 'NUMBER', required: false, key: false, sortOrder: 2 }
    ]
  },
  {
    code: 'REGION',
    name: '地区',
    level: 'REGION',
    description: '',
    sortOrder: 2,
    fields: [
      { defCode: 'REGION', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
    ]
  }
]

function importTask(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 3,
    type: 'IMPORT',
    status: 'IN_PROGRESS',
    title: '导入任务',
    currentStep: 'PUBLISH',
    settingsJson: '{"importMode":"REPLACE"}',
    version: 1,
    items: [{ defCode: 'CURRENCY' }],
    ...overrides
  }
}

const PUBLISH_JOB = {
  id: 99,
  taskId: 3,
  jobType: 'PUBLISH',
  status: 'PENDING',
  progress: 0,
  total: 0,
  errorCount: 0,
  warningCount: 0,
  items: []
}

function fakeSource(): { onopen: (() => void) | null; onerror: (() => void) | null; readyState: number; close: () => void } {
  return { onopen: null, onerror: null, readyState: 1, close: vi.fn() }
}

let wrapper: VueWrapper | null = null

async function mountView(task: Record<string, unknown> = importTask()): Promise<VueWrapper> {
  api.getTask.mockResolvedValue(task)
  const pinia = createPinia()
  setActivePinia(pinia)
  wrapper = mount(ImportWizardView, { global: { plugins: [pinia, ElementPlus] }, attachTo: document.body })
  await flushPromises()
  await flushPromises()
  return wrapper
}

function publishButton(view: VueWrapper): ReturnType<VueWrapper['find']> {
  const button = view.findAll('button').find((item) => item.text().includes('确认发布'))
  expect(button, '页面上应存在「确认发布」按钮').toBeDefined()
  return button!
}

interface CapturedDialog {
  message: VNode
  title: string
  options: { beforeClose?: (action: string, instance: unknown, done: () => void) => void }
}

function lastDialog(): CapturedDialog {
  const call = confirmMock.mock.calls.at(-1)
  expect(call, '应已打开确认对话框').toBeDefined()
  return {
    message: call![0] as VNode,
    title: call![1] as string,
    options: call![2] as CapturedDialog['options']
  }
}

/** handler 成功走字符串、失败走结构化 `{ok:false, reason}`；测试统一取文本。 */
function outcomeText(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  if (value !== null && typeof value === 'object') {
    const structured = value as { result?: unknown; reason?: unknown }
    if (typeof structured.result === 'string') {
      return structured.result
    }
    if (typeof structured.reason === 'string') {
      return structured.reason
    }
  }
  return String(value)
}

function outcomeOk(value: unknown): boolean {
  return typeof value === 'string' || (value !== null && typeof value === 'object' && (value as { ok?: unknown }).ok === true)
}

beforeEach(() => {
  document.body.innerHTML = ''
  confirmMock.mockReset()
  gridState.invalidDef = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  vi.mocked(ElMessage.error).mockClear()
  vi.mocked(ElMessage.success).mockClear()
  vi.mocked(ElMessage.warning).mockClear()
  vi.mocked(ElMessage.info).mockClear()
  api.getTask.mockResolvedValue(importTask())
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.getDiff.mockResolvedValue([])
  api.listDefinitions.mockResolvedValue(DEFS)
  api.setImportMode.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.goToStep.mockResolvedValue(importTask({ currentStep: 'PRECHECK' }))
  api.saveFile.mockResolvedValue(undefined)
  api.createJob.mockResolvedValue(PUBLISH_JOB)
  api.getJob.mockResolvedValue(PUBLISH_JOB)
  api.openTaskEvents.mockImplementation(() => fakeSource())
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('R-09 REPLACE 发布：页面按钮复用复选框确认', () => {
  it('未勾选点「确定」⇒ 对话框不放行且零 createJob；勾选后才发发布作业', async () => {
    let resolveDialog: (() => void) | null = null
    confirmMock.mockImplementation(() => new Promise<void>((resolve) => {
      resolveDialog = resolve
    }))

    const view = await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    await publishButton(view).trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    const dialog = lastDialog()
    expect(dialog.title).toContain('整体替换')

    const done = vi.fn()
    dialog.options.beforeClose!('confirm', {}, done)
    expect(done).not.toHaveBeenCalled()
    await flushPromises()
    expect(api.createJob).not.toHaveBeenCalled()

    // 勾选后才放行；文案必须明确“范围内旧数据会被删除”
    const dialogWrapper = mount({ render: () => dialog.message })
    expect(dialogWrapper.text()).toContain('旧行')
    expect(dialogWrapper.text()).toContain('删除')
    expect(dialogWrapper.text()).toContain('无法恢复')
    await dialogWrapper.find('[data-testid="irreversible-ack-box"]').setValue(true)

    dialog.options.beforeClose!('confirm', {}, () => {
      done()
      resolveDialog?.()
    })
    expect(done).toHaveBeenCalledTimes(1)
    await flushPromises()

    expect(api.createJob).toHaveBeenCalledWith(3, 'PUBLISH')
    dialogWrapper.unmount()
  })

  it('用户取消确认 ⇒ 零 createJob（按钮路径）', async () => {
    confirmMock.mockRejectedValueOnce('cancel')
    const view = await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    await publishButton(view).trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(api.createJob).not.toHaveBeenCalled()
  })
})

describe('R-09 REPLACE 发布：import.startPublish handler 与按钮同口径', () => {
  it('确认接受 ⇒ createJob(PUBLISH)；回执如实给作业号', async () => {
    confirmMock.mockResolvedValue(undefined)
    await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))

    const handler = getPageHandler('import.startPublish')
    expect(handler).not.toBeNull()
    const result = String(await handler!({}))

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(api.createJob).toHaveBeenCalledWith(3, 'PUBLISH')
    expect(result).toContain('发布作业 #99')
  })

  it('用户取消确认 ⇒ 零 createJob，回执为结构化失败且明确“未启动”', async () => {
    confirmMock.mockRejectedValueOnce('cancel')
    await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))

    const handler = getPageHandler('import.startPublish')
    const result = await handler!({})

    expect(api.createJob).not.toHaveBeenCalled()
    expect(outcomeOk(result)).toBe(false)
    expect(outcomeText(result)).toContain('取消')
    expect(outcomeText(result)).toContain('未启动')
  })

  it('服务端未返回作业号 ⇒ 不拼出 "发布作业 #undefined"', async () => {
    api.createJob.mockResolvedValueOnce({ ...PUBLISH_JOB, id: undefined })
    await mountView(importTask({ settingsJson: '{"importMode":"MERGE"}' }))

    const result = outcomeText(await getPageHandler('import.startPublish')!({}))

    expect(result).toContain('未返回作业号')
    expect(result).not.toContain('#undefined')
  })
})

describe('R-09 发布模式以服务端权威值为准（fail-closed）', () => {
  it('本页显示 MERGE、服务端为 REPLACE ⇒ 发布前重新读取，仍弹确认', async () => {
    confirmMock.mockRejectedValueOnce('cancel')
    const view = await mountView(importTask({ settingsJson: '{"importMode":"MERGE"}' }))
    api.getTask.mockResolvedValueOnce(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))

    await publishButton(view).trigger('click')
    await flushPromises()

    expect(api.getTask).toHaveBeenCalled()
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(api.createJob).not.toHaveBeenCalled()
  })

  it('本页显示 REPLACE、服务端为 MERGE ⇒ 发布前重新读取后免确认', async () => {
    const view = await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    api.getTask.mockResolvedValueOnce(importTask({ settingsJson: '{"importMode":"MERGE"}' }))

    await publishButton(view).trigger('click')
    await flushPromises()

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.createJob).toHaveBeenCalledWith(3, 'PUBLISH')
  })

  it('发布前读取服务端模式失败 ⇒ 明确失败、不弹确认、零 createJob', async () => {
    const view = await mountView(importTask({ settingsJson: '{"importMode":"MERGE"}' }))
    api.getTask.mockRejectedValueOnce(new Error('network down'))

    await publishButton(view).trigger('click')
    await flushPromises()

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.createJob).not.toHaveBeenCalled()
    const messages = vi.mocked(ElMessage.error).mock.calls.map((call) => String(call[0] ?? ''))
    expect(messages.some((text) => text.includes('无法读取') && text.includes('未启动'))).toBe(true)
  })

  it('模式切换写失败 ⇒ 本地单选回滚到服务端值（不能停在失败的 MERGE）', async () => {
    const view = await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    api.setImportMode.mockRejectedValueOnce(new Error('500'))

    await view.find('[data-ai-ref="import.mode.merge"]').trigger('click')
    await flushPromises()

    expect(api.setImportMode).toHaveBeenCalledWith(3, 'MERGE')
    expect(view.find('[data-ai-ref="import.mode.replace"]').attributes('aria-checked')).toBe('true')
    expect(view.find('[data-ai-ref="import.mode.merge"]').attributes('aria-checked')).toBe('false')
  })

  it('权威模式解析成功后同步 workspace.importMode，且不记一条假“界面动作”', async () => {
    confirmMock.mockRejectedValueOnce('cancel')
    const view = await mountView(importTask({ settingsJson: '{"importMode":"MERGE"}' }))
    const workspace = useWorkspaceStore()
    workspace.importMode = 'MERGE'
    const before = workspace.recentActions.length

    api.getTask.mockResolvedValueOnce(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    await publishButton(view).trigger('click')
    await flushPromises()

    expect(workspace.importMode).toBe('REPLACE')
    const appended = workspace.recentActions.slice(before)
    expect(appended.some((action) => action.display?.code === 'ai.workspace.importMode')).toBe(false)
  })
})

describe('R-09 发布防双击：确认前就进入 pending，finally 复位', () => {
  it('确认期间重复点击 ⇒ 只弹一个对话框；结束后可再次发起', async () => {
    let resolveTask: (value: unknown) => void = () => {}
    const firstTask = new Promise<unknown>((resolve) => {
      resolveTask = resolve
    })
    await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    api.getTask.mockImplementationOnce(() => firstTask)
    confirmMock.mockRejectedValue('cancel')

    const handler = getPageHandler('import.startPublish')!
    const first = handler({})
    // 让第一次调用进入“等 getTask”的 pending 窗口
    await Promise.resolve()
    const second = await handler({})

    expect(outcomeOk(second)).toBe(false)
    expect(outcomeText(second)).toContain('正在处理')
    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.createJob).not.toHaveBeenCalled()

    resolveTask(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    const firstResult = await first
    expect(outcomeText(firstResult)).toContain('取消')
    expect(confirmMock).toHaveBeenCalledTimes(1)

    // finally 已复位：下一次调用能重新进入确认
    api.getTask.mockResolvedValueOnce(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    const third = await handler({})
    expect(outcomeText(third)).toContain('取消')
    expect(confirmMock).toHaveBeenCalledTimes(2)
  })
})

describe('R-09 MERGE 发布：维持免确认', () => {
  it('页面按钮与 handler 都不弹确认框，直接 createJob', async () => {
    const view = await mountView(importTask({ settingsJson: '{"importMode":"MERGE"}' }))
    await publishButton(view).trigger('click')
    await flushPromises()

    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.createJob).toHaveBeenCalledWith(3, 'PUBLISH')

    api.createJob.mockClear()
    const handler = getPageHandler('import.startPublish')
    await handler!({})
    expect(confirmMock).not.toHaveBeenCalled()
    expect(api.createJob).toHaveBeenCalledWith(3, 'PUBLISH')
  })
})

describe('R3-02 confirm_step 离开第 1 步：回执与实际 saveFile 一致', () => {
  it('saveFile 成功 ⇒ 回执点名写回服务端文件与配置项，不再声称“仅在浏览器内存”', async () => {
    await mountView(importTask({ currentStep: 'UPLOAD', settingsJson: '{"importMode":"MERGE"}' }))

    const handler = getPageHandler('import.nextStep')
    expect(handler).not.toBeNull()
    const result = String(await handler!({ step: 'PRECHECK' }))

    expect(api.saveFile).toHaveBeenCalledTimes(1)
    expect(api.saveFile).toHaveBeenCalledWith(3, 'CURRENCY', expect.any(ArrayBuffer))
    // “标 WRITE”不等于“新增确认门”：confirm_step 仍是用户点「下一步」的同一条路径。
    expect(confirmMock).not.toHaveBeenCalled()
    expect(result).toContain('已推进到导入向导步骤')
    expect(result).toContain('写回服务端文件')
    expect(result).toContain('CURRENCY')
    expect(result).not.toContain('浏览器内存')
    expect(result).not.toContain('未保存')
  })

  it('saveFile 失败 ⇒ 结构化失败，回执带底层错误，不谎称已写回', async () => {
    api.saveFile.mockRejectedValueOnce(new Error('磁盘写入失败'))
    await mountView(importTask({ currentStep: 'UPLOAD', settingsJson: '{"importMode":"MERGE"}' }))

    const raw = await getPageHandler('import.nextStep')!({ step: 'PRECHECK' })
    const result = outcomeText(raw)

    expect(api.saveFile).toHaveBeenCalledTimes(1)
    expect(outcomeOk(raw)).toBe(false)
    expect(result).toContain('步骤未推进')
    expect(result).toContain('磁盘写入失败')
    expect(result).not.toContain('已推进到导入向导步骤')
  })
})

describe('R3-02 confirm_step：非推进/回退分支不得谎称推进或写回', () => {
  it('目标步骤等于当前步 ⇒ 如实说“步骤未改变”，不出现“已推进/未写回”', async () => {
    await mountView(importTask({ currentStep: 'UPLOAD', settingsJson: '{"importMode":"MERGE"}' }))
    api.goToStep.mockClear()

    const result = outcomeText(await getPageHandler('import.nextStep')!({ step: 'UPLOAD' }))

    expect(result).toContain('步骤未改变')
    expect(result).not.toContain('已推进到')
    expect(result).not.toContain('本次未写回服务端文件')
    // 同一步不应发无意义的 PUT /step（否则失败还会丢 syncWarning）
    expect(api.goToStep).not.toHaveBeenCalled()
  })

  it('目标步骤早于当前步 ⇒ 如实说“已回退”，不谎称推进/写回', async () => {
    await mountView(importTask({ currentStep: 'PRECHECK', settingsJson: '{"importMode":"MERGE"}' }))

    const result = outcomeText(await getPageHandler('import.nextStep')!({ step: 'UPLOAD' }))

    expect(result).toContain('已回退')
    expect(result).not.toContain('已推进到')
    expect(result).not.toContain('本次未写回服务端文件')
  })

  it('从第 2 步往后推进 ⇒ 不声称写回（未从第 1 步离开）', async () => {
    await mountView(importTask({ currentStep: 'PRECHECK', settingsJson: '{"importMode":"MERGE"}' }))

    const result = outcomeText(await getPageHandler('import.nextStep')!({ step: 'IMPORT' }))

    expect(result).toContain('已推进到')
    expect(result).toContain('未从第 1 步离开')
    expect(result).not.toContain('未写回服务端文件（没有可写回的编辑区内容）')
  })
})

describe('R3-02 confirm_step：失败必须落 failed 结局并带真实原因', () => {
  it('saveFile 失败 ⇒ executeFrontendTool 的 outcome=failed，且带出底层错误', async () => {
    api.saveFile.mockRejectedValueOnce(new Error('磁盘写入失败'))
    await mountView(importTask({ currentStep: 'UPLOAD', settingsJson: '{"importMode":"MERGE"}' }))

    const execution = await executeFrontendTool({
      name: 'confirm_step',
      args: { step: 'PRECHECK' },
      budgetMs: 5_000
    })

    expect(execution.outcome).toBe('failed')
    expect(execution.ok).toBe(false)
    expect(execution.result).toContain('步骤未推进')
    expect(execution.result).toContain('磁盘写入失败')
  })

  it('部分配置项已写回、另一个本地校验失败 ⇒ 失败回执点名已写回项', async () => {
    gridState.invalidDef = 'REGION'
    await mountView(importTask({
      currentStep: 'UPLOAD',
      settingsJson: '{"importMode":"MERGE"}',
      items: [{ defCode: 'CURRENCY' }, { defCode: 'REGION' }]
    }))

    const result = await getPageHandler('import.nextStep')!({ step: 'PRECHECK' })
    const text = outcomeText(result)

    expect(outcomeOk(result)).toBe(false)
    expect(api.saveFile).toHaveBeenCalledTimes(1)
    expect(api.saveFile).toHaveBeenCalledWith(3, 'CURRENCY', expect.any(ArrayBuffer))
    expect(text).toContain('未推进')
    expect(text).toContain('CURRENCY')
    expect(text).toContain('已写回服务端文件')
  })
})

describe('R-09 发布取消不得清掉既有冲突提示', () => {
  it('取消确认后原发布冲突告警仍在（只在真正发起前清理）', async () => {
    api.listJobs.mockResolvedValue([{ ...PUBLISH_JOB, status: 'FAILED' }])
    api.getIssues.mockResolvedValue({
      content: [{ severity: 'ERROR', defCode: 'CURRENCY', rowKey: 'A', message: '冲突注入' }]
    })
    const view = await mountView(importTask({ settingsJson: '{"importMode":"REPLACE"}' }))
    expect(view.text()).toContain('发布冲突')

    confirmMock.mockRejectedValueOnce('cancel')
    await publishButton(view).trigger('click')
    await flushPromises()

    expect(api.createJob).not.toHaveBeenCalled()
    expect(view.text()).toContain('发布冲突')
    expect(view.text()).toContain('冲突注入')
  })
})
