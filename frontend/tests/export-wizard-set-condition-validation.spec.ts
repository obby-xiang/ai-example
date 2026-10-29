/**
 * R2 返修 · `export.set_condition` 必须**先校验后写**：非法条件零 PUT、零工作区写。
 *
 * 现场（reviewer）：handler 先把新条件转成草案、写 `draftModels` / `touchedConditions` /
 * `counts`，再**直接 PUT** 原样条件并回「已设置…并落库」。于是 `IN []` / 值算子空串这类
 * 非法条件会先报成功、真到 `confirm_step`（`persistStepData` 的 `assertConditionBuildable`）
 * 才失败 —— 与"AI 说设了条件"的事实相反，且服务端已经被写了一份非法条件。
 *
 * 收口口径：在任何状态/网络写入前用**同一** `buildConditionOutcome` 校验；非法 ⇒
 * `{ok:false}` + 可行动文案、零 `setCondition`、零 `workspace.setQueryConditions`、
 * 不动草案/touched（后续 confirm_step 也不会写它）；合法复杂条件仍**原样**落库。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { executeFrontendTool } from '@/utils/frontend-tools'
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
    { defCode: 'CURRENCY', code: 'code', label: '编码', fieldType: 'STRING', required: true, key: true, sortOrder: 1 },
    { defCode: 'CURRENCY', code: 'amount', label: '金额', fieldType: 'NUMBER', required: false, key: false, sortOrder: 2 }
  ]
}

function exportTask() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'IN_PROGRESS',
    currentStep: 'QUERY_COND',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: null }],
    settingsJson: null
  }
}

const mounted: ReturnType<typeof shallowMount>[] = []

async function mountWizard(): Promise<void> {
  mounted.push(shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } }))
  await flushPromises()
}

async function setCondition(conditions: unknown): Promise<unknown> {
  const handler = getPageHandler('export.setConditions')
  expect(handler).not.toBeNull()
  return await handler?.({ defCode: 'CURRENCY', conditions })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(exportTask())
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(exportTask())
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([CURRENCY_DEF])
  api.countData.mockResolvedValue({ count: 0 })
})

describe('set_condition 非法条件：零 PUT、零工作区写、可行动失败', () => {
  it('IN [] ⇒ 结构化失败、零 setCondition、workspace 未写入该条件', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await setCondition({ fields: [{ fieldCode: 'code', operator: 'IN', value: [] }] })
    await flushPromises()

    // 修复前（RED）：返回「已设置 CURRENCY 的查询条件并落库：…」且 setCondition 已被调用 1 次
    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('查询条件里有未填值的字段')
    expect(reason).toContain('本次未设置')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('值算子空串（EQ ""）⇒ 结构化失败、零 setCondition、零工作区写', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await setCondition({ fields: [{ fieldCode: 'code', operator: 'EQ', value: '' }] })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('本次未设置')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('非法条件被拒后 confirm_step 也不会补写（草案/touched 均未改动）', async () => {
    await mountWizard()
    await setCondition({ fields: [{ fieldCode: 'code', operator: 'IN', value: [] }] })
    await flushPromises()
    expect(api.setCondition).not.toHaveBeenCalled()

    const nextStep = getPageHandler('export.nextStep')
    await nextStep?.({ step: 'EXPORT' })
    await flushPromises()

    // 修复前（RED）：非法条件先落库（1 次），confirm_step 还会再按草案写第 2 次
    expect(api.setCondition).not.toHaveBeenCalled()
  })
})

describe('set_condition 四类坏载荷：失败且零 PUT、零工作区写（R4）', () => {
  it('空串载荷 ⇒ 结构化失败，零 setCondition、工作区无该配置项占位', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await setCondition('')
    await flushPromises()

    // 修复前（RED）：parseConditionJson('') → null → 当"显式清空"，PUT "{}" 并回成功
    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('坏 JSON 字符串 ⇒ 结构化失败，零 PUT、零工作区写（不得当清空）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await setCondition('{"fields":[{"fieldCode":"code",')
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('无法解析')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('非对象载荷（数组 / 标量）⇒ 结构化失败，零 PUT、零工作区写', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const arrayReply = await setCondition('[1,2]')
    const scalarReply = await setCondition(42)
    await flushPromises()

    expect(arrayReply).toMatchObject({ ok: false })
    expect(scalarReply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('结构坏载荷（fields 非数组 / 条目缺 fieldCode）⇒ 结构化失败，零 PUT', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const badShape = await setCondition({ fields: 'oops' })
    const badEntry = await setCondition('{"fields":[{"operator":"EQ"}]}')
    await flushPromises()

    expect(badShape).toMatchObject({ ok: false })
    expect(badEntry).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('结构合法但无内容（{"fields":[]}）⇒ 失败，零 PUT（只有显式 {} 才清空）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await setCondition({ fields: [] })
    await flushPromises()

    expect(reply).toMatchObject({ ok: false })
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('显式 {} 才清空：PUT "{}"，且工作区**不留**该配置项占位（R4）', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const reply = await setCondition({})
    await flushPromises()

    expect(typeof reply).toBe('string')
    expect(String(reply)).toContain('已设置')
    expect(api.setCondition).toHaveBeenCalledTimes(1)
    expect(api.setCondition.mock.calls[0]?.[2]).toBe('{}')
    // 修复前（RED）：清空写成 queryConditions.CURRENCY = null（占位 "已设置条件"）
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })
})

describe('set_condition 缺 defCode：结构化失败（字符串会被当成功 —— R6）', () => {
  it('直接调用页面 handler 缺 defCode ⇒ {ok:false}、零 PUT、零工作区写', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()

    const handler = getPageHandler('export.setConditions')
    const reply = await handler?.({ conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] } })
    await flushPromises()

    // 修复前（RED）：返回字符串「缺少 defCode，未设置条件」⇒ interpretHandlerOutcome 判 ok
    expect(reply).toMatchObject({ ok: false })
    expect(String((reply as { reason?: string }).reason ?? '')).toContain('缺少 defCode')
    expect(api.setCondition).not.toHaveBeenCalled()
    expect(workspace.queryConditions).not.toHaveProperty('CURRENCY')
  })

  it('经 executeFrontendTool 路由缺 defCode ⇒ failed（不再被字符串兼容解读成 ok）', async () => {
    await mountWizard()

    const execution = await executeFrontendTool({
      name: 'set_condition',
      args: { conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] } },
      budgetMs: 5_000
    })

    expect(execution.ok).toBe(false)
    expect(execution.outcome).toBe('failed')
    expect(String(execution.result)).toContain('缺少 defCode')
  })
})

describe('set_condition 合法条件：仍按原样落库（相邻正向）', () => {
  it('复杂合法条件（同字段多条件 + 含逗号 IN）⇒ 原字节落库且写回工作区', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()
    const condition = {
      fields: [
        { fieldCode: 'code', operator: 'EQ', value: 'CNY' },
        { fieldCode: 'code', operator: 'NE', value: 'USD' },
        { fieldCode: 'amount', operator: 'IN', value: ['1,000', '2000'] }
      ]
    }

    const reply = await setCondition(condition)
    await flushPromises()

    expect(typeof reply).toBe('string')
    expect(String(reply)).toContain('已设置')
    expect(api.setCondition).toHaveBeenCalledTimes(1)
    expect(JSON.parse(String(api.setCondition.mock.calls[0]?.[2] ?? '{}'))).toEqual(condition)
    expect(workspace.queryConditions.CURRENCY).toEqual(condition)
  })

  it('可表达条件（EQ）⇒ 落库并写回工作区', async () => {
    await mountWizard()
    const workspace = useWorkspaceStore()
    const condition = { scopeKeys: ['XN'], fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }

    const reply = await setCondition(condition)
    await flushPromises()

    expect(String(reply)).toContain('已设置')
    expect(api.setCondition).toHaveBeenCalledTimes(1)
    expect(workspace.queryConditions.CURRENCY).toEqual(condition)
  })
})
