/**
 * 对话时序包 批3③a T7：W-06 收口 —— 导出向导 goStep 取结局（D7/D8）。
 *
 * 三个用例对应三条真实入口（不注入失败则空转，故全部注入）：
 *  ① export.nextStep handler + persistStepData 失败注入 ⇒ 回灌如实"未推进"（撒谎点本体）；
 *  ② export.nextStep handler + syncStepToBackend(goToStep) reject 注入 ⇒ 回灌含同步失败警告；
 *  ③ download_export_files 执行器（preflightDownload 残余入口）+ persist 失败注入 ⇒ 确定性失败；
 *  ④ export.openEditor handler（残余入口 :1225）+ persist 失败注入 ⇒ 结构化失败（不得谎报成功）。
 *
 * 挂载真实 ExportWizardView（shallowMount + 子组件/路由/API 全桩），经
 * executeFrontendTool 的真实路由链驱动 —— 断言的是"回灌文本/结局"，不是 mock 行为。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ElMessage } from 'element-plus'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { PAGE_HANDLER_OPEN_EXPORT_EDITOR } from '@/utils/frontend-tools'
import { useWorkspaceStore } from '@/stores/workspace'

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
  saveBlob: vi.fn(),
  downloadBlob: vi.fn()
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

// 子组件桩：避免 SpreadJS 等重组件进 jsdom（与本用例断言无关）。必须逐字静态路径 ——
// vi.mock 无法解析模板字符串拼接的动态路径。
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
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.saveBlob
}))

// 下载发起点的真实 spy：ExportWizard 经 utils/excel#downloadBlob 触发浏览器下载
// （不 import api/http#saveBlob）——复核 I2：spy 错对象会让"未发起下载"断言空转。
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  downloadBlob: api.downloadBlob
}))

const TASK_7 = {
  id: 7,
  type: 'EXPORT',
  status: 'IN_PROGRESS',
  items: [],
  currentStep: 'SELECT_DEFS'
}

async function mountWizard(): Promise<void> {
  shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(TASK_7)
  api.getFiles.mockResolvedValue([])
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(TASK_7)
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue([])
})

afterEach(() => {
  // 组件 onBeforeUnmount 会自注销 handler；卸载保险由下个用例重新挂载覆盖
})

describe('批3③a T7①：export.nextStep + persistStepData 失败注入 ⇒ 回灌如实"未推进"（D7 撒谎点本体）', () => {
  it('selectDefs reject ⇒ handler 回灌含"未推进"与保存失败语义，不再说"已推进到"', async () => {
    await mountWizard()
    api.selectDefs.mockRejectedValue(new Error('selectDefs 注入失败'))

    const execution = await executeFrontendTool({ name: 'export.nextStep', args: {}, budgetMs: 5_000 })
    expect(execution.result).toContain('未推进')
    expect(execution.result).toContain('selectDefs 注入失败')
    // 复核 I1 锁：失败支必须落结构化 failed —— 字符串支会让卡面 tag 显示「已成功」、
    // 卡内文却写「步骤未推进」（卡面与回灌自相矛盾，正是本包根治的谎报面）。
    expect(execution.outcome).toBe('failed')
    expect(execution.ok).toBe(false)
  })
})

describe('批3③a T7②：export.nextStep + goToStep reject 注入 ⇒ 回灌含"服务端步骤同步失败"警告（D7/D8）', () => {
  it('推进界面不阻断，但回灌文本必须带同步失败警告', async () => {
    await mountWizard()
    api.goToStep.mockRejectedValue(new Error('goToStep 注入 5xx'))

    const execution = await executeFrontendTool({ name: 'export.nextStep', args: {}, budgetMs: 5_000 })
    expect(execution.result).toContain('已推进')
    expect(execution.result).toContain('服务端步骤同步失败')
  })

  it('批3③b D11（R-143①）单点提示锁：同步失败只弹一条 warning，不再 error+warning 双弹', async () => {
    await mountWizard()
    api.goToStep.mockRejectedValue(new Error('goToStep 注入 5xx'))

    await executeFrontendTool({ name: 'export.nextStep', args: {}, budgetMs: 5_000 })
    // syncStepToBackend 不自弹（error 0 次）；goStep 单点 warning 恰一次
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledTimes(1)
  })
})

describe('批3③a T7③：download_export_files 执行器（preflight 残余入口）+ persist 失败注入 ⇒ 确定性失败', () => {
  it('goStep(2) 被 persist 阻断 ⇒ 不发起下载，回灌失败语义', async () => {
    api.getFiles.mockImplementation((id: number) => Promise.resolve(
      id === 8
        ? [{ fileType: 'EXPORT', defCode: 'DEMO', fileName: 'DEMO.xlsx', rowCount: 1 }]
        : []
    ))
    await mountWizard()
    // download_export_files 的 @ToolScope 是 task:EXPORT/EXPORT —— 把工作区步骤拨到 EXPORT 让
    // scope 校验通过。preflight 以 args.taskId=8 ≠ 绑定任务 7 走"切换绑定"真实路径：绑定会把
    // 向导恢复到任务 8 的首步（workspace.step 回落 SELECT_DEFS、stepIndex 归 0），于是
    // preflight 的 goStep(2) 分支真实可达（issue #2 场景：payload.taskId 与绑定任务不同）。
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
    api.getTask.mockImplementation((id: number) => Promise.resolve({ ...TASK_7, id }))
    api.selectDefs.mockRejectedValue(new Error('selectDefs 注入失败'))

    const execution = await executeFrontendTool({
      name: 'download_export_files',
      args: { taskId: 8, defCodes: ['DEMO'] },
      budgetMs: 5_000
    })
    expect(execution.result).toContain('无法切换')
    expect(execution.ok).toBe(false)
    // 复核 I2 锁：下载必须经 utils/excel#downloadBlob 发起（ExportWizard 不 import
    // api/http#saveBlob —— spy 错对象会让"未发起下载"断言空转）。
    expect(api.downloadBlob).not.toHaveBeenCalled()
  })
})

describe('批3③a T7④：export.openEditor handler（残余入口）+ persist 失败注入 ⇒ 结构化失败（A3/D2）', () => {
  it('goStep(2) 被 persist 阻断 ⇒ 结局 failed，不再走到 grid 兜底文案', async () => {
    api.getFiles.mockResolvedValue([{ fileType: 'EXPORT', defCode: 'DEMO', fileName: 'DEMO.xlsx', rowCount: 1 }])
    await mountWizard()
    api.selectDefs.mockRejectedValue(new Error('selectDefs 注入失败'))

    const execution = await executeFrontendTool({
      name: PAGE_HANDLER_OPEN_EXPORT_EDITOR,
      args: { taskId: 7, defCode: 'DEMO' },
      budgetMs: 5_000
    })
    expect(execution.ok).toBe(false)
    expect(execution.result).toContain('selectDefs 注入失败')
  })
})
