/**
 * AI 向导数据正确性 · 表格面：定义列表不可用 / 字段为空时，载入与产出必须 **fail-closed**。
 *
 * 现场：`fieldsOf(code)` 在定义列表未加载（加载失败 / 尚无定义）时返回 `[]`，而
 * `loadFromXlsx(blob, [])` / `loadFromJson(json, [])` 会"成功"载入一张 **0 列**的表并置位
 * "已载入"；随后 `toXlsxBlob()` 产出的是空工作簿：
 * - AI `download_export_files` ⇒ 下载一个空 xlsx；
 * - 保存编辑 / 导入草稿写回 ⇒ `PUT .../files/{code}` 用空文件覆盖服务端真实结果；
 * - `sheet.current` ⇒ 交出 0 字段适配器，工具在"空表"上读成 0 行、写进不存在的列。
 *
 * 收口口径：0 字段不得按成功载入（不置位）；AI 下载、保存写回、`buildXlsx` 一律显式失败，
 * 且不产出任何字节。字段齐全时行为不变（相邻正向）。
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

/** 表格桩：记录是否真的被要求载入 / 产出字节（0 字段时必须一次都不发生）。 */
const grids = vi.hoisted(() => ({
  loadCalls: [] as string[],
  toXlsxCalls: [] as string[]
}))

const downloads = vi.hoisted(() => ({ downloadBlob: vi.fn() }))

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
  downloadBlob: downloads.downloadBlob,
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
      toXlsxBlob(this: { defCode: string }) {
        grids.toXlsxCalls.push(this.defCode)
        return new Blob(['xlsx'])
      },
      validateLocal: () => [],
      getSheetAdapter: () => ({}),
      whenLoaded: async () => {}
    },
    render: () => null
  })
}))
// 选择器/条件表单不进本用例断言面（element-plus 的表格列插槽在 jsdom 下需真组件）
vi.mock('@/components/wizard/DefSelector.vue', () => ({
  default: defineComponent({ name: 'DefSelector', setup: () => () => null })
}))
vi.mock('@/components/wizard/ConditionForm.vue', () => ({
  default: defineComponent({ name: 'ConditionForm', setup: () => () => null })
}))

import ExportWizardView from '@/views/ExportWizardView.vue'
import ImportWizardView from '@/views/ImportWizardView.vue'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'

const FIELDS = [
  { defCode: 'DEMO', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
]

function demoDef(fields: unknown[]): ConfigDefinition {
  return {
    code: 'DEMO',
    name: '演示配置',
    level: 'GLOBAL',
    description: '',
    sortOrder: 1,
    fields: fields as ConfigDefinition['fields']
  }
}

function task(type: 'EXPORT' | 'IMPORT', step: string) {
  return {
    id: 7,
    type,
    status: 'IN_PROGRESS',
    currentStep: step,
    items: [{ id: 1, defCode: 'DEMO', conditionJson: null }],
    settingsJson: null
  }
}

const FILES = [
  { taskId: 7, defCode: 'DEMO', fileType: 'EXPORT', storagePath: '/srv/DEMO.xlsx', fileName: 'DEMO.xlsx', rowCount: 3 }
]

const mounted: VueWrapper[] = []

async function mountExport(def: ConfigDefinition): Promise<void> {
  api.listDefinitions.mockResolvedValue([def])
  api.getTask.mockResolvedValue(task('EXPORT', 'EXPORT'))
  mounted.push(mount(ExportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } }))
  await flushPromises()
}

async function mountImport(def: ConfigDefinition): Promise<void> {
  api.listDefinitions.mockResolvedValue([def])
  api.getTask.mockResolvedValue(task('IMPORT', 'UPLOAD'))
  mounted.push(mount(ImportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } }))
  await flushPromises()
}

function saveButton(): ReturnType<VueWrapper['find']> | undefined {
  return mounted[0]?.findAll('el-button').find((node) => node.text().includes('保存编辑到服务端'))
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  grids.loadCalls = []
  grids.toXlsxCalls = []
  api.getFiles.mockResolvedValue(FILES)
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(task('EXPORT', 'EXPORT'))
  api.downloadFile.mockResolvedValue(new Blob(['DEMO-BYTES']))
  api.countData.mockResolvedValue({ count: 0 })
  api.saveFile.mockResolvedValue(undefined)
})

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
  document.body.innerHTML = ''
})

describe('定义字段为空：不得按 0 字段成功载入', () => {
  it('导出：打开在线编辑器 ⇒ 结构化失败，且一次都没有把文件交给 0 列表格', async () => {
    await mountExport(demoDef([]))
    const handler = getPageHandler('export.openEditor')
    expect(handler).not.toBeNull()

    const reply = await handler?.({ defCode: 'DEMO' })
    await flushPromises()

    // 修复前（RED）：loadFromXlsx(blob, []) 成功 ⇒ 回灌"已打开在线编辑器" + 一张空表
    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('字段')
    expect(grids.loadCalls).toEqual([])
  })

  it('AI 下载：字段为空 ⇒ 确定性失败且零下载（不产出空 xlsx）', async () => {
    await mountExport(demoDef([]))
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })

    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      budgetMs: 5_000
    })
    await flushPromises()

    // 修复前（RED）：回落支照样 downloadFile + downloadBlob，用户拿到空文件且回执说"已发起"
    expect(execution.ok).toBe(false)
    expect(String(execution.result)).toContain('字段')
    expect(String(execution.result)).toContain('未触发下载')
    expect(api.downloadFile).not.toHaveBeenCalled()
    expect(downloads.downloadBlob).not.toHaveBeenCalled()
    expect(grids.toXlsxCalls).toEqual([])
  })

  it('导出：保存编辑 ⇒ 零 PUT（不得用空文件覆盖服务端导出结果）', async () => {
    await mountExport(demoDef([]))
    expect(saveButton()).toBeTruthy()

    await saveButton()?.trigger('click')
    await flushPromises()

    // 修复前（RED）：0 字段表格被当作"已载入" ⇒ toXlsxBlob() 空工作簿 PUT 覆盖
    expect(api.saveFile).not.toHaveBeenCalled()
  })

  it('导入：离开第 1 步（confirm_step）⇒ 失败且零 saveFile', async () => {
    await mountImport(demoDef([]))
    const handler = getPageHandler('import.nextStep')
    expect(handler).not.toBeNull()

    const reply = await handler?.({ step: 'PRECHECK' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('字段')
    expect(api.saveFile).not.toHaveBeenCalled()
  })

  it('sheet.current：字段为空 ⇒ 不交出表格能力（不得当 0 字段成功）', async () => {
    await mountExport(demoDef([]))
    // 让 DEMO 成为激活标签：走一次 AI 打开编辑器（失败也无妨，标签已切到前台）
    const open = getPageHandler('export.openEditor')
    await open?.({ defCode: 'DEMO' })
    await flushPromises()

    const sheet = getPageHandler('sheet.current')
    const reply = await sheet?.({ defCode: 'DEMO' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('字段')
  })
})

describe('相邻正向：字段齐全时行为不变', () => {
  it('导出：字段齐全 ⇒ 载入 1 列并照常发起一次下载', async () => {
    await mountExport(demoDef(FIELDS))
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })

    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 7, defCodes: ['DEMO'] },
      budgetMs: 5_000
    })
    await flushPromises()

    expect(execution.ok).toBe(true)
    expect(String(execution.result)).toContain('已发起下载')
    // 真载入（1 列）后取表格字节，下载一次
    expect(grids.loadCalls).toContain('DEMO:1')
    expect(api.downloadFile).toHaveBeenCalledWith(7, 'DEMO')
    expect(downloads.downloadBlob).toHaveBeenCalledTimes(1)
  })
})
