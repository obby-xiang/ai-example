/**
 * 挂账 L-67 · `export.cond` 校验错误页面侧渲染面（测试扩充 V6）。
 *
 * 现状（批4-C 验收观察）：批4-C 已让模型**读得到**区域文本（`role=alert` 与 `aria-invalid`
 * 邻接文案双承载），但导出向导第 2 步的校验失败**没有页面侧承载** —— 只有 `ElMessage` 一闪而过，
 * 模型在 `export.cond` 上只看到「字段 / 操作符 / 值」的控件行，读不到「为什么不行」。
 *
 * 本批口径：条件表单在区域内补 `role=alert` 的常驻错误承载，三处来源同一条面：
 * - 本地类型校验（数字字段填非数字 / 日期格式 / 区间倒置）—— 提交前就拦住；
 * - 服务端校验失败（预览行数接口报错）—— 不再只弹一次性提示；
 * - 修正后告警自动消失（不留陈旧错误）。
 *
 * 证红：删掉 `ConditionForm.vue` 的 `role=alert` 容器 ⇒ L-67-01/03/04/05 红；
 * 只删本地校验 ⇒ L-67-01 红；只删服务端错误回灌 ⇒ L-67-03 红。
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

vi.mock('@/components/SpreadGrid.vue', () => ({
  default: defineComponent({ name: 'SpreadGrid', setup: () => () => null })
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))

import ElementPlus from 'element-plus'
import ExportWizardView from '@/views/ExportWizardView.vue'
import ConditionForm from '@/components/wizard/ConditionForm.vue'
import { aiSnapshot, resetUiRuntime } from '@/utils/ai-dom-tools'
import { resetAiRegions, listAiRegions } from '@/utils/ai-regions'
import { applyLocale, DEFAULT_LOCALE } from '@/locales'
import { emptyDraftModel, type ConditionDraftModel } from '@/types/condition'
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

/** 第 2 步「校验失败」的现场：数字字段（金额）被写成非数字。 */
const BAD_CONDITION_JSON = JSON.stringify({
  scopeKeys: [],
  fields: [{ fieldCode: 'amount', operator: 'GT', value: 'abc' }]
})

const GOOD_CONDITION_JSON = JSON.stringify({
  scopeKeys: [],
  fields: [{ fieldCode: 'amount', operator: 'GT', value: '100' }]
})

function exportTask(conditionJson: string) {
  return {
    id: 3,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    title: '导出任务',
    currentStep: 'SELECT_DEFS',
    version: 1,
    items: [
      { defCode: 'CURRENCY', conditionJson },
      { defCode: 'REGION', conditionJson: '' }
    ]
  }
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

function alertsInRegion(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('[role="alert"]'))
}

function alertText(): string {
  return alertsInRegion().map((node) => node.textContent ?? '').join(' / ')
}

function draft(fields: Record<string, { operator: 'GT'; value: string }>): ConditionDraftModel {
  const model = emptyDraftModel()
  for (const [code, item] of Object.entries(fields)) {
    model.fields[code] = { operator: item.operator, value: item.value, value2: '' }
  }
  return model
}

beforeEach(() => {
  applyLocale(DEFAULT_LOCALE)
  resetAiRegions()
  resetUiRuntime()
  vi.clearAllMocks()
  document.body.innerHTML = ''
  api.getTask.mockResolvedValue(exportTask(BAD_CONDITION_JSON))
  api.getFiles.mockResolvedValue([])
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.getDiff.mockResolvedValue([])
  api.listDefinitions.mockResolvedValue(DEFS)
  api.setCondition.mockResolvedValue({})
  api.selectDefs.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask(BAD_CONDITION_JSON))
  api.countData.mockResolvedValue({ count: 0 })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  applyLocale(DEFAULT_LOCALE)
})

describe('L-67-01：本地校验失败在区域内可见（role=alert + 文本快照可读）', () => {
  it('数字字段填非数字 ⇒ 区域内出现 role=alert；ui_snapshot text=true 读得到原因', async () => {
    await mountView(ExportWizardView)

    const alerts = alertsInRegion()
    expect(alerts.length).toBeGreaterThan(0)
    expect(alertText()).toContain('金额')
    expect(alertText()).toContain('数字')

    const out = aiSnapshot({ regions: ['export.cond'], text: true })
    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('区域文本')
    expect(out.result).toContain('金额')
    expect(out.result).toContain('数字')
  })

  it('告警容器是区域内元素（不是 body 直挂的全局提示）', async () => {
    await mountView(ExportWizardView)
    const region = listAiRegions().find((item) => item.id === 'export.cond')
    expect(region).toBeDefined()
    const alert = alertsInRegion()[0]
    expect(alert).toBeDefined()
    // 必须在 `export.cond` 区域 root 之内 —— 文本快照只取区域内元素（批4-C 的围栏）
    expect(region?.root.contains(alert as Node)).toBe(true)
    expect(alert?.hasAttribute('aria-live')).toBe(true)
    expect(alert?.className).toContain('text-[#f56c6c]')
  })
})

describe('L-67-02：合法条件零告警（不误报）', () => {
  it('金额 = 100（数字）⇒ 没有 role=alert，快照文本不含告警标题', async () => {
    api.getTask.mockResolvedValue(exportTask(GOOD_CONDITION_JSON))
    await mountView(ExportWizardView)
    expect(alertsInRegion()).toHaveLength(0)
    expect(aiSnapshot({ regions: ['export.cond'], text: true }).result).not.toContain('查询条件有误')
  })
})

describe('L-67-03：服务端校验失败也落区域（不再只弹一次性提示）', () => {
  it('预览行数接口报错 ⇒ 区域内 role=alert 常驻该原因，文本快照读得到', async () => {
    api.getTask.mockResolvedValue(exportTask(GOOD_CONDITION_JSON))
    api.countData.mockRejectedValue(new Error('字段 amount 的值不是合法数字'))
    await mountView(ExportWizardView)

    const button = document.querySelector<HTMLElement>('[data-ai-ref="export.cond.CURRENCY.count"]')
    expect(button).not.toBeNull()
    button?.click()
    await flushPromises()
    await nextTick()

    expect(alertText()).toContain('字段 amount 的值不是合法数字')
    const out = aiSnapshot({ regions: ['export.cond'], text: true })
    expect(out.result).toContain('字段 amount 的值不是合法数字')
  })
})

describe('L-67-04：双 locale 同键（en-US 文案随 locale 切换）', () => {
  it('切到 en-US ⇒ 告警文案为英文，键集合不变', async () => {
    await mountView(ExportWizardView)
    expect(alertText()).toContain('金额')

    applyLocale('en-US')
    await nextTick()
    const text = alertText()
    expect(text).toContain('must be a number')
    expect(text).not.toContain('数字')
  })
})

describe('L-67-05：修正后告警消失（不留陈旧错误）', () => {
  it('ConditionForm 的 modelValue 从非法改合法 ⇒ role=alert 随 computed 消失', async () => {
    const host = mount(ConditionForm, {
      props: { fields: DEFS[0]?.fields ?? [], modelValue: draft({ amount: { operator: 'GT', value: 'abc' } }) },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()
    expect(host.findAll('[role="alert"]').length).toBe(1)

    await host.setProps({ modelValue: draft({ amount: { operator: 'GT', value: '100' } }) })
    await nextTick()
    expect(host.findAll('[role="alert"]').length).toBe(0)
    host.unmount()
  })
})
