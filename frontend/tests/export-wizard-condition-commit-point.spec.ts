/**
 * 条件提交点：`set_condition` 只写草案，`confirm_step`（离开 QUERY_COND）才落库。
 *
 * 现场（2026-10-30 审核）：`export.setConditions` 校验通过后**立即** `PUT /items/{code}/condition`，
 * 于是"AI 说设置条件"这一步就产生了业务后台写；紧接着 `confirm_step` 的 `persistStepData` 又写一次。
 * 收口口径：
 * - `set_condition` = 参数校验 + 更新草案 + 标记 touched + 同步工作区，**零业务写**；
 * - `goStep → persistStepData` 是条件提交点：先提交草案再推进，失败不推进、不建作业；
 * - 离开 QUERY_COND 时，**确实空白**的草案显式提交 `{}`（"明确不设"），承接宿主条件表留空后
 *   `confirm_step`；不得覆盖已水合的服务端条件、verbatim 草案或 AI 刚设置的非空草案；
 * - 离开 SELECT_DEFS 不得因为空草案写条件。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'

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
  countData: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: { taskId: '7' } }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

vi.mock('@/components/SpreadGrid.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'SpreadGrid', setup: () => () => null }) }
})
vi.mock('@/components/JobProgress.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'JobProgress', setup: () => () => null }) }
})
vi.mock('@/components/TaskWizard.vue', async () => {
  const { defineComponent } = await import('vue')
  return { default: defineComponent({ name: 'TaskWizard', setup: () => () => null }) }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { countData: api.countData } }))

const CURRENCY_DEF = {
  id: 1,
  code: 'CURRENCY',
  name: '币种',
  level: 'GLOBAL',
  fields: [
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
  ]
}

const RATE_DEF = {
  id: 2,
  code: 'RATE',
  name: '汇率',
  level: 'GLOBAL',
  fields: [
    { defCode: 'RATE', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 }
  ]
}

/** 同字段多条件：表单一条草案表达不了 ⇒ 水合后草案带 `verbatim`。 */
const VERBATIM_CONDITION = {
  fields: [
    { fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] },
    { fieldCode: 'code', operator: 'NE', value: 'JPY' }
  ]
}

const CNY_CONDITION = { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }

function exportTask(
  currentStep: string,
  items: Array<{ id: number; defCode: string; conditionJson: string | null }>
) {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep,
    items,
    settingsJson: null
  }
}

async function mountWizard(task = exportTask('QUERY_COND', [{ id: 1, defCode: 'CURRENCY', conditionJson: null }])): Promise<void> {
  api.getTask.mockResolvedValue(task)
  shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
  await flushPromises()
}

async function setCondition(conditions: unknown, defCode = 'CURRENCY'): Promise<unknown> {
  const handler = getPageHandler('export.setConditions')
  expect(handler).not.toBeNull()
  return await handler?.({ defCode, conditions })
}

async function confirmStep(step = 'EXPORT'): Promise<unknown> {
  const handler = getPageHandler('export.nextStep')
  expect(handler).not.toBeNull()
  return await handler?.({ step })
}

function conditionWrites(): Array<[number, string, string]> {
  return api.setCondition.mock.calls.map((call) => [call[0] as number, call[1] as string, String(call[2])])
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF, RATE_DEF])
  api.countData.mockResolvedValue({ count: 0 })
})

describe('set_condition：只改草案，零业务后台写', () => {
  it('合法条件 ⇒ 回执已设置、零 setCondition、工作区同步待提交条件', async () => {
    await mountWizard()
    api.goToStep.mockResolvedValue(exportTask('QUERY_COND', [{ id: 1, defCode: 'CURRENCY', conditionJson: null }]))
    const workspace = useWorkspaceStore()

    const reply = await setCondition(CNY_CONDITION)
    await flushPromises()

    // 修复前（RED）：handler 立即 PUT 并回「已设置…并落库」
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(typeof reply).toBe('string')
    expect(String(reply)).toContain('已设置')
    expect(String(reply)).not.toContain('落库')
    expect(workspace.queryConditions.CURRENCY).toEqual(CNY_CONDITION)
  })

  it('显式 {} 清空同样只改草案：零 setCondition、工作区删键', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    await setCondition({})
    await flushPromises()

    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })
})

describe('confirm_step（离开 QUERY_COND）是条件提交点', () => {
  it('AI 设置的条件在下一步时才提交（提交 CNY，且不是 {}）', async () => {
    await mountWizard()
    await setCondition(CNY_CONDITION)
    await flushPromises()
    expect(api.setCondition).not.toHaveBeenCalled()

    const reply = await confirmStep()
    await flushPromises()

    // 修复前（RED）：set_condition 已 PUT 一次，confirm_step 再写一次（两次写、两步之间就有业务写）
    expect(conditionWrites()).toEqual([[7, 'CURRENCY', JSON.stringify(CNY_CONDITION)]])
    expect(String(reply)).toContain('已推进到导出向导步骤')
    expect(api.goToStep).toHaveBeenCalledWith(7, 'EXPORT')
  })

  it('空白草案离开 QUERY_COND ⇒ 提交 {} 表示"明确不设"（一次，且只为该配置项）', async () => {
    await mountWizard()

    await confirmStep()
    await flushPromises()

    // 修复前（RED）：空白草案一律跳过 ⇒ 宿主条件表留空后 confirm_step 没有任何"留空"反馈
    expect(conditionWrites()).toEqual([[7, 'CURRENCY', '{}']])
  })

  it('未选中的配置项：条件只写草案，回执如实说明"推进步骤时不会提交"', async () => {
    await mountWizard()

    const reply = await setCondition(CNY_CONDITION, 'RATE')
    await flushPromises()

    expect(api.setCondition).not.toHaveBeenCalled()
    expect(String(reply)).toContain('不在当前选中集')
  })

  it('服务端存的空条件（"{}"）同样提交 {}：留空仍是一次明确反馈', async () => {
    await mountWizard(exportTask('QUERY_COND', [{ id: 1, defCode: 'CURRENCY', conditionJson: '{}' }]))

    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([[7, 'CURRENCY', '{}']])
  })

  it('两张表：空白的那张提交 {}，有草案的那张按草案提交（各一次）', async () => {
    await mountWizard(exportTask('QUERY_COND', [
      { id: 1, defCode: 'CURRENCY', conditionJson: null },
      { id: 2, defCode: 'RATE', conditionJson: null }
    ]))
    await setCondition(CNY_CONDITION, 'RATE')
    await flushPromises()

    await confirmStep()
    await flushPromises()

    expect(conditionWrites()).toEqual([
      [7, 'CURRENCY', '{}'],
      [7, 'RATE', JSON.stringify(CNY_CONDITION)]
    ])
  })
})

describe('不该写的时候不写', () => {
  it('SELECT_DEFS 步离开 ⇒ 不因为空草案写条件', async () => {
    await mountWizard(exportTask('SELECT_DEFS', [{ id: 1, defCode: 'CURRENCY', conditionJson: null }]))
    api.goToStep.mockResolvedValue(exportTask('QUERY_COND', [{ id: 1, defCode: 'CURRENCY', conditionJson: null }]))

    await confirmStep('QUERY_COND')
    await flushPromises()

    // 修复前（RED）：空草案提交 {} 的规则没有"离开哪一步"的门，选择配置步也照写
    expect(conditionWrites()).toEqual([])
    expect(api.selectDefs).toHaveBeenCalledWith(7, ['CURRENCY'])
  })

  it('已水合的 verbatim 草案不被空草案规则覆盖：离开 QUERY_COND 零条件写', async () => {
    await mountWizard(exportTask('QUERY_COND', [
      { id: 1, defCode: 'CURRENCY', conditionJson: JSON.stringify(VERBATIM_CONDITION) }
    ]))

    await confirmStep()
    await flushPromises()

    // 修复前（RED）：{} 覆盖服务端已有的多条件（"全量导出"事故的同族）
    expect(conditionWrites()).toEqual([])
  })

  it('条件提交失败 ⇒ 不推进步骤（界面停在原步）', async () => {
    await mountWizard()
    api.setCondition.mockRejectedValue(new Error('PUT /items/CURRENCY/condition 500'))

    const reply = await confirmStep()
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('步骤未推进')
    expect(api.goToStep).not.toHaveBeenCalled()
  })
})
