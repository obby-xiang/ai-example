/**
 * visop 批 B · 注册面扩面（O-2 裁决 = 选项 A）。
 *
 * 覆盖两页五个区域：导入向导 `import.selectDefs` / `import.mode` / `import.grid`，
 * 导出向导 `export.selectDefs` / `export.cond`。断言的是**真实视图挂载 + 真实快照**，
 * 不是 mock 出来的 DOM（否则等于把被测物换成桩）。
 *
 * 用例编号对齐设计稿 §2.2 批 B 测试矩阵（B-01/B-03/B-07/B-08），并补两条与批4-A
 * 机制（快照闸 / 风险分流）的联动断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'

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
  openTaskEvents: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '3' }, params: {} }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

// SpreadJS 引擎不进 jsdom；其余 Element Plus 组件走真实实现（标注要落在真组件渲染出的 DOM 上）。
vi.mock('@/components/SpreadGrid.vue', () => ({
  default: defineComponent({ name: 'SpreadGrid', setup: () => () => null })
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))

import ElementPlus from 'element-plus'
import ImportWizardView from '@/views/ImportWizardView.vue'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { bumpAiEpoch, currentAiEpoch, listAiRegions, resetAiRegions } from '@/utils/ai-regions'
import { aiClick, aiFill as aiFillTool, aiSnapshot, resetUiRuntime } from '@/utils/ai-dom-tools'
import { useWorkspaceStore } from '@/stores/workspace'
import { applyLocale } from '@/locales'
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
    fields: [{ defCode: 'REGION', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }]
  }
]

const CONDITION_JSON = JSON.stringify({
  scopeKeys: [],
  fields: [
    { fieldCode: 'code', operator: 'EQ', value: 'CN' },
    { fieldCode: 'amount', operator: 'GT', value: '100' }
  ]
})

const IMPORT_TASK = {
  id: 3,
  type: 'IMPORT',
  status: 'IN_PROGRESS',
  title: '导入任务',
  currentStep: 'UPLOAD',
  version: 1,
  items: [{ defCode: 'CURRENCY' }, { defCode: 'REGION' }]
}

const EXPORT_TASK = {
  id: 3,
  type: 'EXPORT',
  status: 'IN_PROGRESS',
  title: '导出任务',
  currentStep: 'SELECT_DEFS',
  version: 1,
  items: [
    { defCode: 'CURRENCY', conditionJson: CONDITION_JSON },
    { defCode: 'REGION', conditionJson: '' }
  ]
}

let pinia: Pinia
let wrapper: VueWrapper | null = null

async function mountView(component: unknown): Promise<void> {
  pinia = createPinia()
  setActivePinia(pinia)
  wrapper = mount(component as never, { global: { plugins: [pinia, ElementPlus] }, attachTo: document.body })
  await flushPromises()
  await nextTick()
}

function regionIds(): string[] {
  return listAiRegions().map((region) => region.id)
}

function refsOf(snapshot: string): string[] {
  return snapshot
    .split('\n')
    .filter((line) => line.startsWith('['))
    .map((line) => line.slice(1, line.indexOf(']')))
}

function elementLines(snapshot: string): string[] {
  return snapshot.split('\n').filter((line) => line.startsWith('['))
}

beforeEach(() => {
  resetAiRegions()
  resetUiRuntime()
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(IMPORT_TASK)
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.getDiff.mockResolvedValue([])
  api.listDefinitions.mockResolvedValue(DEFS)
  api.setImportMode.mockResolvedValue({})
  api.goToStep.mockResolvedValue(IMPORT_TASK)
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.countData.mockResolvedValue({ count: 0 })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('B-01：导入向导第 1 步快照（selectDefs / mode / grid 三区域可见）', () => {
  it('三个新区域全部注册，且快照里能看见配置项勾选框、模式单选、表格视口 tab', async () => {
    await mountView(ImportWizardView)
    expect(regionIds()).toEqual(expect.arrayContaining(['import.selectDefs', 'import.mode', 'import.grid']))

    const out = aiSnapshot({ kinds: ['all'] })
    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('import.selectDefs.search')
    expect(out.result).toContain('import.selectDefs.CURRENCY')
    expect(out.result).toContain('import.mode.merge')
    // tab 角色不在快照默认 kinds（button/input/select/toggle）里，需显式声明 kinds=all/tab
    expect(out.result).toContain('import.grid.tab.CURRENCY')
    // 尾行的「共 N 个元素」必须与正文行数一致（模型据此判断是否看全）
    const total = Number(/共 (\d+) 个元素/.exec(out.result)?.[1] ?? '-1')
    expect(total).toBe(elementLines(out.result).length)
    expect(total).toBeGreaterThanOrEqual(5)
  })

  it('模式单选与勾选框的风险档位正确：mode = consequential，勾选框 = mutate-local', async () => {
    await mountView(ImportWizardView)
    const out = aiSnapshot({})
    expect(out.result).toMatch(/\[import\.mode\.merge\].*state=/)
    // 元素风险是安全边界：模式单选会向服务端写（PUT /import-mode），必须按后果性标注
    const mode = listAiRegions().find((region) => region.id === 'import.mode')
    expect(mode?.risk).toBe('consequential')
    const selectDefs = listAiRegions().find((region) => region.id === 'import.selectDefs')
    expect(selectDefs?.risk).toBe('mutate-local')
  })
})

describe('回归：行勾选框的既有交互不因标注而退化', () => {
  it('手工点击行勾选框 ⇒ 仍按 v-model 契约 emit 选中集', async () => {
    const { default: DefSelector } = await import('@/components/wizard/DefSelector.vue')
    const w = mount(DefSelector, {
      props: { defs: DEFS, modelValue: [] },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()
    const box = w.find('.el-table__body-wrapper .el-checkbox__original')
    expect(box.exists()).toBe(true)
    // 原生 click 才会跑 label/checkbox 的默认激活动作（VTU 的 trigger 不会）
    ;(box.element as HTMLInputElement).click()
    await flushPromises()
    expect(w.emitted('update:modelValue')?.at(-1)?.[0]).toEqual(['CURRENCY'])
    w.unmount()
  })
})

describe('B-01b：导出向导 SELECT_DEFS / COND 快照', () => {  it('两个新区域注册；SELECT_DEFS 步快照元素数 ≥3 且尾行一致', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    expect(regionIds()).toEqual(expect.arrayContaining(['export.selectDefs', 'export.cond']))

    const out = aiSnapshot({ regions: ['export.selectDefs'] })
    expect(out.outcome).toBe('ok')
    const refs = refsOf(out.result)
    expect(refs).toEqual(expect.arrayContaining(['export.selectDefs.search', 'export.selectDefs.CURRENCY']))
    expect(refs.length).toBeGreaterThanOrEqual(3)
    const total = Number(/共 (\d+) 个元素/.exec(out.result)?.[1] ?? '-1')
    expect(total).toBe(elementLines(out.result).length)
  })

  it('COND 步能看见查询条件字段（含值框可填）', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    const out = aiSnapshot({ regions: ['export.cond'] })
    expect(out.result).toContain('export.cond.CURRENCY.code.field')
    expect(out.result).toContain('export.cond.CURRENCY.code.value')
    expect(out.result).toContain('export.cond.CURRENCY.add')
    // 值框是唯一的写入口：声明 fill=allow 且为 mutate-local（可填的条件值）
    expect(out.result).toMatch(/\[export\.cond\.CURRENCY\.code\.value\][^\n]*fill=allow/)
  })
})

describe('批 B 验收口径：快照元素数 ≥ 页面可操作控件盘点基线的 80%', () => {
  /**
   * 盘点基线（人工点数一次，记在这里当审计锚）：
   * 导入向导第 1 步 = 层级筛选 4 + 搜索 1 + 行勾选 2 + 上传区 3 + 编辑区 tab 2 = 12
   * 导出向导第 1 步 = 层级筛选 4 + 搜索 1 + 行勾选 2 = 7
   * 导出向导第 2 步 = CURRENCY（2 行 × 4 + 添加 1 + 预览 1）+ REGION（添加 1 + 预览 1）= 12
   */
  it('导入向导第 1 步：12 个可操作控件，覆盖 ≥80%', async () => {
    await mountView(ImportWizardView)
    const refs = refsOf(aiSnapshot({ kinds: ['all'] }).result)
    expect(refs.length / 12).toBeGreaterThanOrEqual(0.8)
    expect(refs).toEqual(expect.arrayContaining([
      'import.selectDefs.level.all',
      'import.selectDefs.search',
      'import.selectDefs.CURRENCY',
      'import.grid.tab.CURRENCY',
      'import.upload.choose'
    ]))
  })

  it('导出向导第 1 步：7 个可操作控件，覆盖 ≥80%', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    const refs = refsOf(aiSnapshot({ regions: ['export.selectDefs'] }).result)
    expect(refs.length / 7).toBeGreaterThanOrEqual(0.8)
  })

  it('导出向导第 2 步：12 个可操作控件，覆盖 ≥80%', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    const snap = aiSnapshot({ regions: ['export.cond'] }).result
    const refs = refsOf(snap)
    expect(refs.length / 12).toBeGreaterThanOrEqual(0.8)
    expect(refs).toEqual(expect.arrayContaining([
      'export.cond.CURRENCY.code.field',
      'export.cond.CURRENCY.code.op',
      'export.cond.CURRENCY.code.value',
      'export.cond.CURRENCY.code.remove',
      'export.cond.CURRENCY.amount.value',
      'export.cond.CURRENCY.add',
      'export.cond.REGION.add'
    ]))
  })
})

describe('B-07：i18n 稳定性 —— 切语言后 ref 逐字不变', () => {
  it('英文 locale 下同一页面的 ref 集与中文完全一致', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    const zh = refsOf(aiSnapshot({}).result)
    applyLocale('en-US')
    await nextTick()
    const en = refsOf(aiSnapshot({}).result)
    expect(zh.length).toBeGreaterThan(0)
    expect(en).toEqual(zh)
  })
})

describe('B-08：尺寸闸 —— 最复杂的导出 COND 快照 ≤2000 字符且尾行恒在', () => {
  it('两个配置项各带条件时仍受 2000 字符硬顶，尾行「共 N 个元素」不丢', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    const out = aiSnapshot({})
    expect(out.result.length).toBeLessThanOrEqual(2000)
    expect(out.result).toMatch(/共 \d+ 个元素/)
  })
})

describe('与批4-A 机制的联动（快照闸 / 风险分流）', () => {
  it('未快照直接点新标注的勾选框 ⇒ 拒执 UI_REF_NOT_SNAPSHOTTED（快照闸对新 ref 同样生效）', async () => {
    await mountView(ImportWizardView)
    const out = await aiClick({ ref: 'import.selectDefs.REGION', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('rejected')
    expect(out.code).toBe('UI_REF_NOT_SNAPSHOTTED')
  })

  it('快照后点勾选框 ⇒ 生效并记撤销点（mutate-local）', async () => {
    await mountView(ImportWizardView)
    aiSnapshot({})
    const workspace = useWorkspaceStore()
    expect(workspace.selectedDefs).toEqual(expect.arrayContaining(['CURRENCY', 'REGION']))

    const out = await aiClick({ ref: 'import.selectDefs.REGION', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    await flushPromises()
    expect(useWorkspaceStore().selectedDefs).toEqual(['CURRENCY'])
    expect(out.result).toContain('可撤销')
  })

  it('只读任务：勾选框在快照里如实显示 disabled（看得见的状态必须是真的）', async () => {
    api.getTask.mockResolvedValue({ ...IMPORT_TASK, status: 'CANCELLED' })
    await mountView(ImportWizardView)
    const out = aiSnapshot({ regions: ['import.selectDefs'] })
    expect(out.result).toMatch(/\[import\.selectDefs\.CURRENCY\][^\n]*state=disabled/)
  })

  it('模式单选是后果性动作：一次不点，回执指路注册动作 set_import_mode', async () => {
    await mountView(ImportWizardView)
    aiSnapshot({})
    const out = await aiClick({ ref: 'import.mode.replace', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('failed')
    expect(out.result).toContain('set_import_mode')
    expect(api.setImportMode).not.toHaveBeenCalled()
  })
})

describe('可操作闭环：新增区域除了「看得见」还要「点得着」', () => {
  it('点编辑区 tab ⇒ 真的切到该标签页（aria-selected 跟随）', async () => {
    await mountView(ImportWizardView)
    const before = aiSnapshot({ kinds: ['all'] }).result
    expect(before).toMatch(/\[import\.grid\.tab\.CURRENCY\][^\n]*state=selected/)

    const out = await aiClick({ ref: 'import.grid.tab.REGION', epoch: currentAiEpoch() })
    expect(out.outcome).toBe('ok')
    await flushPromises()
    // 快照按 epoch 缓存（同 epoch 同参直返），所以点完要看新画面必须先失效 epoch
    bumpAiEpoch()
    const after = aiSnapshot({ kinds: ['all'] }).result
    expect(after).toMatch(/\[import\.grid\.tab\.REGION\][^\n]*state=selected/)
    expect(after).toMatch(/\[import\.grid\.tab\.CURRENCY\][^\n]*state=unselected/)
  })

  it('填写导出向导搜索框 ⇒ 真的过滤表格行（fill=allow 生效）', async () => {
    api.getTask.mockResolvedValue(EXPORT_TASK)
    await mountView(ExportWizardView)
    aiSnapshot({ regions: ['export.selectDefs'] })
    const epoch = currentAiEpoch()
    const out = await aiFillTool({ ref: 'export.selectDefs.search', value: 'CUR', epoch })
    expect(out.outcome).toBe('ok')
    await flushPromises()
    bumpAiEpoch()
    const refs = refsOf(aiSnapshot({ regions: ['export.selectDefs'] }).result)
    expect(refs).toContain('export.selectDefs.CURRENCY')
    expect(refs).not.toContain('export.selectDefs.REGION')
  })
})
