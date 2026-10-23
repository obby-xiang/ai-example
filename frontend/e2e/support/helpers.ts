/**
 * 真机 E2E 公共助手：页面驱动 + 真实后端的夹具构造。
 * 断言面一律 data-testid / 终态文本（设计稿 §0.2 A 级纪律）。
 */
import { expect, type APIRequestContext, type Page } from '@playwright/test'

export const API = process.env.E2E_API_BASE ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`
export const DEGRADED_API = process.env.E2E_DEGRADED_API_BASE
  ?? `http://127.0.0.1:${process.env.E2E_DEGRADED_BACKEND_PORT ?? '18331'}`
export const DEGRADED_APP = process.env.E2E_DEGRADED_APP
  ?? `http://127.0.0.1:${process.env.E2E_DEGRADED_APP_PORT ?? '18402'}`

/** 桩 turn-text 的确定性文本（事实源 = scripts/ci/stub-upstream.mjs 的 TURN_TEXT）。 */
export const TURN_TEXT = '命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），'
  + '以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。'

export function apiUrl(path: string, base = API): string {
  return `${base}${path}`
}

async function data<T>(request: APIRequestContext, method: 'get' | 'post' | 'put', path: string, body?: unknown): Promise<T> {
  const res = await request[method](apiUrl(path), body === undefined ? undefined : { data: body })
  if (!res.ok()) throw new Error(`${method.toUpperCase()} ${path} => HTTP ${res.status()} ${await res.text()}`)
  const json = (await res.json()) as { success?: boolean; data?: T }
  if (json.success === false) throw new Error(`${method.toUpperCase()} ${path} => success=false`)
  return json.data as T
}

export const api = {
  get: <T>(request: APIRequestContext, path: string): Promise<T> => data<T>(request, 'get', path),
  post: <T>(request: APIRequestContext, path: string, body: unknown): Promise<T> => data<T>(request, 'post', path, body),
  put: <T>(request: APIRequestContext, path: string, body: unknown): Promise<T> => data<T>(request, 'put', path, body)
}

export interface JobView {
  id: number
  jobType: string
  status: string
}

export async function waitJob(request: APIRequestContext, jobId: number, timeoutMs = 90_000): Promise<JobView> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const job = await api.get<JobView>(request, `/api/jobs/${jobId}`)
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)) return job
    await new Promise((done) => setTimeout(done, 300))
  }
  throw new Error(`作业 #${jobId} 未在 ${timeoutMs / 1000}s 内到达终态`)
}

/** 造一个"可发布"的导入任务：模板 xlsx → 上传 → PRECHECK 通过 → step=PUBLISH。 */export async function createPublishableImportTask(request: APIRequestContext): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-PUB-${Date.now()}` })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: ['DOC_TYPE'] })
  const template = await request.get(apiUrl(`/api/tasks/${id}/files/templates?codes=DOC_TYPE`))
  if (!template.ok()) throw new Error(`模板下载失败：HTTP ${template.status()}`)
  const bytes = await template.body()
  const upload = await request.post(apiUrl(`/api/tasks/${id}/files/upload`), {
    multipart: {
      file: {
        name: 'DOC_TYPE_e2e.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: bytes
      }
    }
  })
  if (!upload.ok()) throw new Error(`上传失败：HTTP ${upload.status()} ${await upload.text()}`)
  const job = await api.post<JobView>(request, `/api/tasks/${id}/jobs`, { jobType: 'PRECHECK' })
  const final = await waitJob(request, job.id)
  if (final.status !== 'COMPLETED') throw new Error(`PRECHECK 终态 ${final.status}`)
  await api.put(request, `/api/tasks/${id}/step`, { step: 'PUBLISH' })
  return id
}

/** 造一个停在「上传配置」步、已勾选 DOC_TYPE 的导入任务（上传区按钮因此可用）。 */
export async function createImportTaskAtUpload(request: APIRequestContext): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-UP-${Date.now()}` })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: ['DOC_TYPE'] })
  return id
}

/** 造一个「上传配置」步且已有上传文件的导入任务（表格出现在该页，任务未终态 ⇒ 可写）。 */
export async function createUploadedImportTask(request: APIRequestContext, defCode = 'CURRENCY'): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-UP-${Date.now()}` })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: [defCode] })
  const template = await request.get(apiUrl(`/api/tasks/${id}/files/templates?codes=${defCode}`))
  if (!template.ok()) throw new Error(`模板下载失败：HTTP ${template.status()}`)
  const bytes = await template.body()
  const upload = await request.post(apiUrl(`/api/tasks/${id}/files/upload`), {
    multipart: {
      file: {
        name: `${defCode}_e2e.xlsx`,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: bytes
      }
    }
  })
  if (!upload.ok()) throw new Error(`上传失败：HTTP ${upload.status()} ${await upload.text()}`)
  return id
}

/** 用「真导出结果」当导入文件：保证表里有业务行（模板可能只有表头）。 */
export async function createSheetImportTask(request: APIRequestContext, defCode = 'CURRENCY'): Promise<number> {
  const exportTask = await api.post<{ id: number }>(request, '/api/tasks', { type: 'EXPORT', title: `E2E-SRC-${Date.now()}` })
  await api.post(request, `/api/tasks/${exportTask.id}/select-defs`, { defCodes: [defCode] })
  await api.put(request, `/api/tasks/${exportTask.id}/step`, { step: 'EXPORT' })
  const job = await api.post<JobView>(request, `/api/tasks/${exportTask.id}/jobs`, { jobType: 'EXPORT' })
  const final = await waitJob(request, job.id)
  if (final.status !== 'COMPLETED') throw new Error(`源导出作业终态 ${final.status}`)
  const file = await request.get(apiUrl(`/api/tasks/${exportTask.id}/files/${defCode}`))
  if (!file.ok()) throw new Error(`导出文件下载失败：HTTP ${file.status()}`)
  const bytes = await file.body()

  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-UP-${Date.now()}` })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: [defCode] })
  const upload = await request.post(apiUrl(`/api/tasks/${id}/files/upload`), {
    multipart: {
      file: {
        name: `${defCode}_e2e.xlsx`,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: bytes
      }
    }
  })
  if (!upload.ok()) throw new Error(`上传失败：HTTP ${upload.status()} ${await upload.text()}`)
  return id
}

/** 造一个已导出完成的 EXPORT 任务（结果表格 = CURRENCY）。 */
export async function createExportedTask(request: APIRequestContext): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'EXPORT', title: `E2E-SHEET-${Date.now()}` })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: ['CURRENCY'] })
  await api.put(request, `/api/tasks/${id}/step`, { step: 'EXPORT' })
  const job = await api.post<JobView>(request, `/api/tasks/${id}/jobs`, { jobType: 'EXPORT' })
  const final = await waitJob(request, job.id)
  if (final.status !== 'COMPLETED') throw new Error(`EXPORT 终态 ${final.status}`)
  return id
}

// ── 页面驱动 ─────────────────────────────────────────────────────────────────

export async function openApp(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
}

export async function openPath(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
}

export async function sendMessage(page: Page, text: string): Promise<void> {
  const composer = page.locator('[data-testid="composer-input"]')
  await composer.fill(text)
  await page.locator('[data-testid="send-btn"]').click()
}

/** 最后一条助手气泡（用户气泡带 max-w-[86%]，助手气泡是 w-full）。 */
export function assistantBubble(page: Page) {
  return page.locator('[data-testid^="msg-bubble-"].w-full').last()
}

export async function assistantText(page: Page): Promise<string> {
  return (await assistantBubble(page).innerText()).trim()
}

/** 等本轮收敛（停止钮消失 = 不再 loading/suspended）。 */
export async function waitRoundSettled(page: Page, timeout = 60_000): Promise<void> {
  await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(0, { timeout })
}

export async function sessionId(page: Page): Promise<string> {
  const id = await page.evaluate(() => sessionStorage.getItem('ai-session-id'))
  if (!id) throw new Error('sessionStorage 中没有 ai-session-id')
  return id
}

export interface RunCurrent {
  found: boolean
  runId?: string
  snapshotStatus?: string
  archiveMaxSeq?: number
}

export async function runCurrent(request: APIRequestContext, sid: string): Promise<RunCurrent> {
  return api.get<RunCurrent>(request, `/api/ai/runs/current?sessionId=${encodeURIComponent(sid)}`)
}

export async function runStatus(request: APIRequestContext, runId: string): Promise<string> {
  const run = await api.get<{ exists?: boolean; snapshot?: { status?: string } }>(
    request,
    `/api/ai/runs/${encodeURIComponent(runId)}`
  )
  return run.snapshot?.status ?? ''
}

/** 轮询直到条件成立（显式等待，不用固定 sleep 断言）。 */
export async function until(check: () => Promise<boolean>, timeoutMs: number, label: string): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await check()) return
    await new Promise((done) => setTimeout(done, 300))
  }
  throw new Error(`等待超时：${label}`)
}
