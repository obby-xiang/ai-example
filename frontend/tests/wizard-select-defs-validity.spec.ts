/**
 * AI 向导数据正确性 · 勾选面：`select_definitions` 必须按**当前定义列表**校验编码。
 *
 * 现场：两个向导的 `selectDefs` 页面 handler 直接把模型传来的 `codes` 写进本页选中集并
 * 收敛进工作区（`extra.selectedDefs`），从不校验编码是否存在。模型给错编码（拼写、幻觉、
 * 旧定义已删除）时回执是「已选择配置项：NOT_A_DEF」—— 选中集、工作区、后续落库都带着
 * 一个不存在的配置项（`persistStepData` 会把任务项写成一个不可导出的集合）。
 *
 * 收口口径：
 * - 任一编码不在当前定义列表 ⇒ 整笔失败（结构化 `{ok:false}`），点名无效编码 + 可用编码；
 * - 失败时零副作用：本页选中集 / 工作区 / 服务端任务项都不动（不"部分成功"）；
 * - 定义列表不可用（空）时无法校验 ⇒ 失败，不得当成"没有可用编码"报成功或清空选中集。
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
  downloadBlob: vi.fn(),
  zipBlobs: vi.fn(async () => new Blob(['zip']))
}))

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

const DEFS: ConfigDefinition[] = [
  {
    code: 'CURRENCY',
    name: '币种',
    level: 'GLOBAL',
    description: '',
    sortOrder: 1,
    fields: [
      { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
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

function task(type: 'EXPORT' | 'IMPORT', step: string, defCodes: string[]) {
  return {
    id: 7,
    type,
    status: 'IN_PROGRESS',
    currentStep: step,
    items: defCodes.map((defCode, index) => ({ id: index + 1, defCode, conditionJson: null })),
    settingsJson: null
  }
}

const mounted: VueWrapper[] = []

async function mountExport(defs: ConfigDefinition[] = DEFS, defCodes: string[] = ['CURRENCY']): Promise<void> {
  api.listDefinitions.mockResolvedValue(defs)
  api.getTask.mockResolvedValue(task('EXPORT', 'SELECT_DEFS', defCodes))
  mounted.push(mount(ExportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } }))
  await flushPromises()
}

async function mountImport(defs: ConfigDefinition[] = DEFS, defCodes: string[] = ['CURRENCY']): Promise<void> {
  api.listDefinitions.mockResolvedValue(defs)
  api.getTask.mockResolvedValue(task('IMPORT', 'UPLOAD', defCodes))
  mounted.push(mount(ImportWizardView, { attachTo: document.body, global: { plugins: [createPinia()] } }))
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(task('EXPORT', 'SELECT_DEFS', ['CURRENCY']))
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.countData.mockResolvedValue({ count: 0 })
  api.saveFile.mockResolvedValue(undefined)
})

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()?.unmount()
  }
  document.body.innerHTML = ''
})

describe('导出向导：select_definitions 按当前定义列表校验编码', () => {
  it('非法编码 ⇒ 结构化失败，选中集/工作区/服务端都不动，回执点名无效码与可用码', async () => {
    await mountExport()
    const workspace = useWorkspaceStore()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])

    const handler = getPageHandler('export.selectDefs')
    expect(handler).not.toBeNull()
    const reply = await handler?.({ defCodes: ['CURRENCY', 'NOT_A_DEF'], mode: 'REPLACE' })
    await flushPromises()

    // 修复前（RED）：字符串成功回执 + 选中集/工作区都写进 NOT_A_DEF
    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('NOT_A_DEF')
    expect(reason).toContain('可用编码')
    expect(reason).toContain('CURRENCY')
    expect(reason).toContain('REGION')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    // 零落库：勾选不写任务项，出错更不得写
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('定义列表不可用（空）⇒ 失败且不清空选中集（不得当"无可用编码"报成功）', async () => {
    await mountExport([], ['CURRENCY'])
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('export.selectDefs')
    const reply = await handler?.({ defCodes: ['CURRENCY'], mode: 'REPLACE' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('定义列表')
    // R1：拿不到清单时不得同时声称"可用编码：（无）"（那是把未校验写成事实）
    expect(reason).toContain('拿不到可用编码清单')
    expect(reason).not.toContain('可用编码：（无）')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('codes 为空 ⇒ 结构化失败，零落库、工作区不变（不得把"清空选择"当成功）', async () => {
    await mountExport()
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('export.selectDefs')
    const reply = await handler?.({ defCodes: [], mode: 'REPLACE' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('codes 为空')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('合法编码 ⇒ 照常选中并同步工作区（相邻正向不回归）', async () => {
    await mountExport()
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('export.selectDefs')
    const reply = await handler?.({ defCodes: ['REGION'], mode: 'REPLACE' })
    await flushPromises()

    expect(typeof reply).toBe('string')
    expect(String(reply)).toContain('已选择配置项')
    expect(workspace.selectedDefs).toEqual(['REGION'])
  })
})

describe('导入向导：select_definitions 同样按定义列表校验编码', () => {
  it('ADD 模式含非法编码 ⇒ 整笔失败，原选中集不变（不部分成功）', async () => {
    await mountImport()
    const workspace = useWorkspaceStore()
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])

    const handler = getPageHandler('import.selectDefs')
    expect(handler).not.toBeNull()
    const reply = await handler?.({ defCodes: ['REGION', 'NOT_A_DEF'], mode: 'ADD' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('NOT_A_DEF')
    expect(reason).toContain('可用编码')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    expect(api.selectDefs).not.toHaveBeenCalled()
  })

  it('合法编码（ADD）⇒ 合并进选中集并同步工作区（相邻正向）', async () => {
    await mountImport()
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('import.selectDefs')
    await handler?.({ defCodes: ['REGION'], mode: 'ADD' })
    await flushPromises()

    expect(workspace.selectedDefs).toEqual(['CURRENCY', 'REGION'])
  })

  it('定义列表不可用（空）⇒ 失败、零调用、文案不声称"可用编码：（无）"', async () => {
    await mountImport([], ['CURRENCY'])
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('import.selectDefs')
    const reply = await handler?.({ defCodes: ['REGION'], mode: 'ADD' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('定义列表')
    expect(reason).toContain('拿不到可用编码清单')
    expect(reason).not.toContain('可用编码：（无）')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    expect(api.selectDefs).not.toHaveBeenCalled()
    expect(api.setCondition).not.toHaveBeenCalled()
  })

  it('codes 为空 ⇒ 结构化失败、零落库、选中集不变', async () => {
    await mountImport()
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('import.selectDefs')
    const reply = await handler?.({ defCodes: [], mode: 'REPLACE' })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('codes 为空')
    expect(workspace.selectedDefs).toEqual(['CURRENCY'])
    expect(api.selectDefs).not.toHaveBeenCalled()
  })
})
