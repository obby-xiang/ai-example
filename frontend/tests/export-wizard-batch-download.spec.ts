/**
 * R-156（issue #9）：`download_export_files` 单工具按数量分流 —— 1 码直出 xlsx / ≥2 码单次 zip。
 *
 * 四例对应施工卡 §改动清单 6：
 *  ① 1 码 ⇒ 走既有单文件 xlsx 路径（换名前行为回归：下载文件名仍是该配置的结果文件名）；
 *  ② N 码 ⇒ 逐码取字节后**一次** zip 下载（`zipBlobs` / `downloadBlob` 调用计数断言）；
 *  ③ 即发即回 ⇒ 字节链路未结算（表格 blob 迟迟不落地）也照常回灌"已发起"（W-01 教训）；
 *  ④ 无效码 ⇒ 整体显式失败：回执点名无效码 + 可用码清单，且一次下载都不发起（不静默丢弃）。
 *
 * 挂载真实 ExportWizardView（shallowMount + 子组件/路由/API 全桩），经 `executeFrontendTool`
 * 的真实路由链驱动 —— 断言的是"真实回灌文本 + 真实下载调用计数"，不是 mock 行为。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ElMessage } from 'element-plus'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { executeFrontendTool, DOWNLOAD_PREFLIGHT_TIMEOUT_MS } from '@/utils/frontend-tools'
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
  downloadBlob: vi.fn(),
  zipBlobs: vi.fn()
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

vi.mock('@/components/JobProgress.vue', async () => {
  const { defineComponent: define } = await import('vue')
  return { default: define({ name: 'JobProgress', setup: () => () => null }) }
})
vi.mock('@/components/TaskWizard.vue', async () => {
  const { defineComponent: define } = await import('vue')
  return { default: define({ name: 'TaskWizard', setup: () => () => null }) }
})

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: { listJobs: api.listJobs, getIssues: api.getIssues } }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.downloadBlob
}))

// 下载与打包的发起点的真实 spy：ExportWizard 经 utils/excel#downloadBlob / #zipBlobs 落地
// （复核 I2：spy 错对象会让"只发起一次下载"的断言空转）。
vi.mock('@/utils/excel', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  downloadBlob: api.downloadBlob,
  zipBlobs: api.zipBlobs
}))

const TASK_7 = {
  id: 7,
  type: 'EXPORT',
  status: 'IN_PROGRESS',
  items: [],
  currentStep: 'SELECT_DEFS'
}

/** 任务 #7 的两个导出结果（≥2 码用例据此判定"可用码清单"与打包条目）。 */
const FILES = [
  { fileType: 'EXPORT', defCode: 'DEMO', fileName: 'DEMO.xlsx', rowCount: 1 },
  { fileType: 'EXPORT', defCode: 'CURR', fileName: 'CURR.xlsx', rowCount: 2 }
]

/**
 * 定义列表：下载/打包要求字段可用（`buildXlsx` 对 0 字段 fail-closed —— 不产出空文件）。
 * 本夹具只关心数量分流与下载计数，字段内容不参与断言。
 */
const DEFS = [
  {
    code: 'DEMO',
    name: '演示配置',
    level: 'GLOBAL',
    fields: [{ defCode: 'DEMO', code: 'code', label: '编码', fieldType: 'STRING', required: false, key: true, sortOrder: 1 }]
  },
  {
    code: 'CURR',
    name: '币种',
    level: 'GLOBAL',
    fields: [{ defCode: 'CURR', code: 'code', label: '编码', fieldType: 'STRING', required: false, key: true, sortOrder: 1 }]
  }
]

/**
 * 字节源在本夹具里固定走 `tasksApi.downloadFile`：结果表格挂在 `el-tabs` / `el-tab-pane` 插槽内，
 * 而本仓库测试不装 element-plus（未解析组件把插槽内容丢弃）⇒ `gridRefs` 为空，`buildXlsx` 取
 * "文件回落"支。两支路同名同源（都取该配置的当前导出结果），"逐码取字节"的计数断言不受影响。
 */
async function mountWizard(): Promise<void> {
  shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
  await flushPromises()
}

/** 经真实路由链调用工具（scope 校验要求工作区处在 task:EXPORT/EXPORT 上下文）。 */
async function runTool(args: Record<string, unknown>): Promise<Awaited<ReturnType<typeof executeFrontendTool>>> {
  useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  return await executeFrontendTool({ name: 'download_export_files', args, budgetMs: 5_000 })
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(TASK_7)
  api.getFiles.mockResolvedValue(FILES)
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(TASK_7)
  api.downloadFile.mockResolvedValue(new Blob(['x']))
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.listDefinitions.mockResolvedValue(DEFS)
  api.zipBlobs.mockImplementation(async (entries: Array<{ name: string; blob: Blob }>) => {
    return new Blob([`zip:${entries.length}`])
  })
})

describe('R-156 ①：1 码 ⇒ 既有单文件 xlsx 路径（数量分流不改变单码行为）', () => {
  it('下载文件名仍是该配置的导出结果文件名，且不打包', async () => {
    await mountWizard()
    const execution = await runTool({ taskId: 7, defCodes: ['DEMO'] })
    await flushPromises()

    expect(execution.ok).toBe(true)
    expect(execution.result).toContain('已发起下载')
    expect(execution.result).toContain('DEMO')
    // 单码分支不产 zip：zipBlobs 一次都不该被调用，浏览器侧只发生一次下载
    expect(api.zipBlobs).not.toHaveBeenCalled()
    expect(api.downloadBlob).toHaveBeenCalledTimes(1)
    expect(api.downloadBlob.mock.calls[0]?.[1]).toBe('DEMO.xlsx')
  })
})

describe('R-156 ②：N 码 ⇒ 逐码取字节 + 单次 zip 下载（issue #9 的连续下载拦截根因）', () => {
  it('2 码 ⇒ 字节取两次、zipBlobs 一次、downloadBlob 一次', async () => {
    await mountWizard()
    const execution = await runTool({ taskId: 7, defCodes: ['DEMO', 'CURR'] })
    await flushPromises()

    expect(execution.ok).toBe(true)
    expect(execution.result).toContain('已发起打包下载')
    // 逐码取字节：两个码各一次（buildXlsx 调两次，本夹具固定走 downloadFile 回落支）
    expect(api.downloadFile).toHaveBeenCalledTimes(2)
    expect(api.downloadFile.mock.calls.map((call) => call[1])).toEqual(['DEMO', 'CURR'])
    // 合成只做一次，且两个条目都在
    expect(api.zipBlobs).toHaveBeenCalledTimes(1)
    const entries = api.zipBlobs.mock.calls[0]?.[0] as Array<{ name: string }>
    expect(entries.map((item) => item.name)).toEqual(['DEMO.xlsx', 'CURR.xlsx'])
    // 浏览器侧**只发生一次下载**（这正是"多个文件同样只弹一次保存"的硬保证）
    expect(api.downloadBlob).toHaveBeenCalledTimes(1)
    expect(api.downloadBlob.mock.calls[0]?.[1]).toBe('export-7.zip')
  })
})

describe('R-156 ③：即发即回 —— 字节链路未结算也照常回灌"已发起"（W-01 假失败教训）', () => {
  it('表格字节永不落地 ⇒ 执行器仍立即返回成功回执，且此时尚未发起下载', async () => {
    await mountWizard()
    // 字节构建卡在数据源侧（永不结算）：旧写法 await 链路会让回灌迟到（issue#6 实测 120,004ms）
    api.downloadFile.mockReturnValue(new Promise<Blob>(() => {}))

    const execution = await runTool({ taskId: 7, defCodes: ['DEMO', 'CURR'] })
    await flushPromises()

    expect(execution.ok).toBe(true)
    expect(execution.result).toContain('已发起打包下载')
    expect(execution.result).toContain('无需重试')
    // 回执已到而下载还没发起 —— 证明回灌不等落盘
    expect(api.downloadBlob).not.toHaveBeenCalled()
  })
})

describe('R-156 ④：无效码 ⇒ 整体显式失败（点名无效码 + 可用码清单，不静默丢弃）', () => {
  it('含不在可导出清单里的码 ⇒ 整批不下发，回执给出可用码清单', async () => {
    await mountWizard()
    const execution = await runTool({ taskId: 7, defCodes: ['DEMO', 'NOPE'] })
    await flushPromises()

    expect(execution.ok).toBe(false)
    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('无效码 NOPE')
    expect(execution.result).toContain('可用码清单 = DEMO、CURR')
    // 只下"能下的那几个"会让模型以为整批已完成：一次下载都不发起
    expect(api.zipBlobs).not.toHaveBeenCalled()
    expect(api.downloadBlob).not.toHaveBeenCalled()
  })
})

describe('R-156 ⑤：同一笔打包重复投递 ⇒ 回灌场景②（不重复发起，W-01 的重复下载面）', () => {
  it('第二次投递说"本次未重复发起"，且不产生新的下载', async () => {
    await mountWizard()
    // 保持在途：字节链一直不结算，两个码都留在在途集合里
    api.downloadFile.mockReturnValue(new Promise<Blob>(() => {}))

    const first = await runTool({ taskId: 7, defCodes: ['DEMO', 'CURR'] })
    const second = await runTool({ taskId: 7, defCodes: ['DEMO', 'CURR'] })
    await flushPromises()

    expect(first.result).toContain('已发起打包下载')
    expect(second.result).toContain('本次未重复发起')
    expect(api.downloadBlob).not.toHaveBeenCalled()
  })
})

describe('L-82：前置校验悬挂 ⇒ 显式超时结局（不能再无结局挂着）', () => {
  it('任务绑定请求永不返回 ⇒ 到点回 timeout 结局 + 人话回执，且一次下载都不发起', async () => {
    await mountWizard()
    // 修前形态（真机 VL 域实测 >90s）：`ensureTaskBoundForTool` 的串行 HTTP 迟迟不落地
    // （编辑器/引擎未预热），执行器既不给失败也不给成功 ⇒ 回灌悬空、模型与用户都拿不到结局。
    api.getTask.mockReturnValue(new Promise(() => {}))

    vi.useFakeTimers()
    try {
      useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
      const pending = executeFrontendTool({
        name: 'download_export_files',
        args: { taskId: 9, defCodes: ['DEMO'] },
        // 外层预算放大到真机默认值之上，证明是**下载工具自带的显式超时**在收口
        budgetMs: 120_000
      })
      await vi.advanceTimersByTimeAsync(DOWNLOAD_PREFLIGHT_TIMEOUT_MS + 1_000)
      const execution = await pending

      expect(execution.outcome).toBe('timeout')
      expect(execution.code).toBe('UI_DOWNLOAD_TIMEOUT')
      expect(execution.ok).toBe(false)
      expect(execution.result).toContain('超时')
      expect(execution.result).toContain('未触发下载') // 诚实：前置没过，下载没发起
      expect(api.downloadBlob).not.toHaveBeenCalled()
      expect(api.zipBlobs).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
})

// ══════════════════════════════════════════════════════════════════════════════
// 批次 1B：字节来源可区分 / 整批显式失败（不静默出空文件、不产出残缺 zip）
// ══════════════════════════════════════════════════════════════════════════════

describe('批次1B：回执必须交代字节来源（未载入回落服务端 / 真实 0 行不再混淆）', () => {
  it('1 码且服务端记录 0 行 ⇒ 回执写明"回落服务端"与"真实空结果"', async () => {
    api.getFiles.mockResolvedValue([
      { fileType: 'EXPORT', defCode: 'DEMO', fileName: 'DEMO.xlsx', rowCount: 0 }
    ])
    await mountWizard()

    const execution = await runTool({ taskId: 7, defCodes: ['DEMO'] })
    await flushPromises()

    expect(execution.ok).toBe(true)
    // 本夹具没有表格组件 ⇒ 字节源是服务端文件（不是在线编辑表格），且 0 行是真实空结果
    expect(execution.result).toContain('回落服务端导出文件字节')
    expect(execution.result).toContain('真实空结果')
  })
})

describe('批次1B：打包链路上任一码取字节失败 ⇒ 整批显式失败（零 zip / 零下载）', () => {
  it('2 码中 CURR 回落失败 ⇒ 不合成 zip、不触发浏览器下载，并点名整批未下载', async () => {
    await mountWizard()
    api.downloadFile.mockImplementation((_taskId: number, code: string) => (code === 'CURR'
      ? Promise.reject(new Error('GET /files/CURR 500'))
      : Promise.resolve(new Blob(['DEMO-BYTES']))))

    const execution = await runTool({ taskId: 7, defCodes: ['DEMO', 'CURR'] })
    await flushPromises()

    // 回执仍是"已发起"（即发即回），但浏览器侧一个文件都没落下
    expect(execution.ok).toBe(true)
    expect(api.zipBlobs).not.toHaveBeenCalled()
    expect(api.downloadBlob).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalled()
    const text = String((ElMessage.error as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]?.[0] ?? '')
    expect(text).toContain('均未下载')
    expect(text).toContain('CURR')
  })
})
