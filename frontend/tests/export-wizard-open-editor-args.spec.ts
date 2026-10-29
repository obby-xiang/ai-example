/**
 * R7 返修 · `export.openEditor` 缺 `defCode` 必须结构化失败（字符串会被判 ok）。
 *
 * 现场（reviewer 终审）：`PAGE_HANDLER_OPEN_EXPORT_EDITOR` 的缺参分支返回字符串
 * 「缺少 defCode，无法打开在线编辑器」；`interpretHandlerOutcome` 把字符串当"成功 + 文本"
 * ⇒ 工具卡显示成功、模型以为编辑器已打开，实际什么都没执行。
 *
 * 收口口径：**只把这条参数错误**改成 `{ ok:false, reason:'缺少 defCode，无法打开在线编辑器（未执行）' }`；
 * 其它刻意信息型回执（如只读态的「任务已结束，仅供查看…」）一律不动。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { executeFrontendTool, PAGE_HANDLER_OPEN_EXPORT_EDITOR } from '@/utils/frontend-tools'
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

function exportTask() {
  return {
    id: 7,
    type: 'EXPORT',
    status: 'ACTIVE',
    currentStep: 'EXPORT',
    items: [{ id: 1, defCode: 'CURRENCY', conditionJson: null }],
    settingsJson: null
  }
}

async function mountWizard(): Promise<void> {
  shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
  await flushPromises()
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

describe('export.openEditor 缺 defCode：结构化失败，零执行（R7）', () => {
  it('直接调用页面 handler 缺 defCode ⇒ {ok:false}、不读文件、不切页', async () => {
    await mountWizard()

    const handler = getPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR)
    expect(handler).not.toBeNull()
    const reply = await handler?.({ taskId: 7 })
    await flushPromises()

    // 修复前（RED）：返回字符串「缺少 defCode，无法打开在线编辑器」⇒ interpretHandlerOutcome 判 ok
    expect(reply).toMatchObject({ ok: false })
    const reason = String((reply as { reason?: string }).reason ?? '')
    expect(reason).toContain('缺少 defCode')
    expect(reason).toContain('未执行')
    // 零执行：没有读文件、没有推进步骤
    expect(api.downloadFile).not.toHaveBeenCalled()
    expect(api.goToStep).not.toHaveBeenCalled()
  })

  it('工具路由缺 defCode ⇒ 仍失败（工具层参数校验先行，是相邻对照不是本缺陷入口）', async () => {
    await mountWizard()
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })

    const execution = await executeFrontendTool({
      name: 'open_export_file_editor',
      args: { taskId: 7 },
      budgetMs: 5_000
    })
    await flushPromises()

    // 工具层 REQUIRED_PARAMS 先拦（文案不同），handler 的缺参分支只在直调时可达 ——
    // 本用例锁"路由侧不会因本次改动变成成功"，缺陷本体由上面那条直调用例锁住。
    expect(execution.ok).toBe(false)
    expect(execution.outcome).toBe('failed')
    expect(String(execution.result)).toContain('defCode')
  })
})
