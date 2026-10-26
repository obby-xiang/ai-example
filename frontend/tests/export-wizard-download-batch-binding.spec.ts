/**
 * S3：**下载结果与批次绑定**（下载发起时捕获 `resultsBatch`，异步 build/打包期间换批即作废）。
 *
 * <h2>缺陷</h2>
 * `startDownload` 的 `buildXlsx`（表格载入 / 服务端回落）与批量入口的 `zipBlobs` 都是 await；
 * 期间一个导出作业完成会把**新一批**结果整批铺开（`resultsBatch` 前进、`exportResults` 替换）。
 * 旧口径没有这道绑定：构建晚到的字节照样 `downloadBlob`（= `link.click`）⇒ 用户/AI 拿到的是
 * 上一批导出文件，而页面已显示新批次。
 *
 * <h2>本用例覆盖</h2>
 * 两条入口在**同一次**「换批」下都必须作废，而不是只修一条：
 *  ① 单文件（AI 侧 `startDownloadInBackground` → `startDownload`）；
 *  ② 批量（AI 侧 `startBatchDownloadInBackground` → `zipBlobs`）—— 零 zip、零下载；
 *  ③ 作废后页面不锁死：最新批次上的下一次下载正常落一次 `downloadBlob`。
 *
 * 换批走**真实链**：先有在途作业（`resumeJobs` 把 `jobBatch` 推进）→ 作业完成后经页面能力
 * `export.syncJob` 触发 `refreshResults`（`resultsBatch` 追平 `jobBatch`）—— 与线上"作业跑完、
 * 新文件铺开"同一条路径，不靠改私有 ref 造状态。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ElMessage } from 'element-plus'
import ExportWizardView from '@/views/ExportWizardView.vue'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { getPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyLocale } from '@/locales'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  downloadFile: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  getJob: vi.fn(),
  openTaskEvents: vi.fn(),
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
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: api }))
vi.mock('@/api/http', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  saveBlob: api.downloadBlob
}))
// 下载/打包的真实落地点：断言"一次浏览器下载都没发生"就断言这两个 spy 没被调用
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
  // 停在导出步：避免挂载后再走一次步骤联动（与本用例的换批无关）
  currentStep: 'EXPORT'
}

const FILES = [
  { fileType: 'EXPORT', defCode: 'DEMO', fileName: 'DEMO.xlsx', rowCount: 1 },
  { fileType: 'EXPORT', defCode: 'CURR', fileName: 'CURR.xlsx', rowCount: 2 }
]

function job(id: number, status: string): Record<string, unknown> {
  return { id, taskId: 7, jobType: 'EXPORT', status, progress: 0, total: 0, errorCount: 0, warningCount: 0, items: [] }
}

/** 经真实路由链调用工具（scope 校验要求工作区处在 task:EXPORT/EXPORT 上下文）。 */
async function runTool(args: Record<string, unknown>): Promise<Awaited<ReturnType<typeof executeFrontendTool>>> {
  useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  return await executeFrontendTool({ name: 'download_export_files', args, budgetMs: 5_000 })
}

/**
 * 让**新一批**结果铺开（真实链）：在途作业 ⇒ `jobBatch` 前进；作业完成后经 `export.syncJob`
 * 触发 `refreshResults` ⇒ `resultsBatch` 追平（旧结果被新一批替换）。
 */
async function completeExportRun(jobId: number): Promise<void> {
  const handler = getPageHandler('export.syncJob')
  expect(handler).not.toBeNull()
  api.listJobs.mockResolvedValue([job(jobId, 'RUNNING')])
  await handler?.({ taskId: 7, jobId })
  api.listJobs.mockResolvedValue([job(jobId, 'COMPLETED')])
  await handler?.({ taskId: 7, jobId })
  await flushPromises()
}

function infoTexts(): string[] {
  return (ElMessage.info as unknown as { mock: { calls: unknown[][] } }).mock.calls
    .map((call) => String(call[0] ?? ''))
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue(TASK_7)
  api.getFiles.mockResolvedValue(FILES)
  api.selectDefs.mockResolvedValue({})
  api.setCondition.mockResolvedValue({})
  api.goToStep.mockResolvedValue(TASK_7)
  api.listDefinitions.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  // 轮询快照永不结算：本用例的换批由页面能力驱动，不让 3s 兜底表插一手
  api.getJob.mockReturnValue(new Promise(() => {}))
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
  api.zipBlobs.mockImplementation(async (entries: Array<{ name: string; blob: Blob }>) => new Blob([`zip:${entries.length}`]))
})

describe('S3：构建/打包期间换批 ⇒ 取消本次下载（不触发浏览器下载），且后续下载不受影响', () => {
  it('单文件与批量两条入口都作废；最新批次上的下一次下载正常落一次', async () => {
    applyLocale('en-US')
    // 挂载时已有在途作业：`jobBatch` 前进到 2，而结果面仍是第 1 批（旧结果）
    api.listJobs.mockResolvedValue([job(11, 'RUNNING')])
    const wrapper = shallowMount(ExportWizardView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    /** 本次下载的字节构建卡点（resolve 即"字节晚到"）。 */
    let pending: Array<(blob: Blob) => void> = []
    api.downloadFile.mockImplementation(() => new Promise<Blob>((resolve) => pending.push(resolve)))

    // ① 单文件入口（AI 侧 startDownloadInBackground → startDownload）
    const single = await runTool({ taskId: 7, defCodes: ['DEMO'] })
    expect(single.ok).toBe(true)
    expect(pending).toHaveLength(1)

    // 构建在途期间作业跑完、新一批结果铺开
    await completeExportRun(12)
    pending.shift()?.(new Blob(['batch-1-bytes']))
    await flushPromises()

    // 旧批次的字节绝不交给浏览器；作废只提示一句（不当作失败）
    expect(api.downloadBlob).not.toHaveBeenCalled()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(infoTexts().at(-1)).toContain('this download was cancelled')

    // ② 批量入口（AI 侧 startBatchDownloadInBackground）
    pending = []
    const batched = await runTool({ taskId: 7, defCodes: ['DEMO', 'CURR'] })
    expect(batched.ok).toBe(true)
    expect(pending).toHaveLength(2)

    await completeExportRun(13)
    for (const resolve of pending) {
      resolve(new Blob(['batch-2-bytes']))
    }
    await flushPromises()

    // 零 zip、零下载：不把上一批字节打成 zip 交出去
    expect(api.zipBlobs).not.toHaveBeenCalled()
    expect(api.downloadBlob).not.toHaveBeenCalled()
    expect(infoTexts().at(-1)).toContain('this download was cancelled')

    // ③ 作废不锁死后续下载：最新批次上再发一次，正常落一次浏览器下载
    api.downloadFile.mockResolvedValue(new Blob(['fresh']))
    const fresh = await runTool({ taskId: 7, defCodes: ['DEMO'] })
    await flushPromises()

    expect(fresh.ok).toBe(true)
    expect(api.downloadBlob).toHaveBeenCalledTimes(1)
    expect(api.downloadBlob.mock.calls[0]?.[1]).toBe('DEMO.xlsx')

    wrapper.unmount()
  })
})
