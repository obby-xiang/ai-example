/**
 * 批③c「解锁条目」域专用助手 —— 17-unlock*.spec.ts 私有。
 *
 * 只读复用 `support/helpers.ts` 的公共面（api / 页面驱动 / 夹具构造），不改动任何既有文件。
 *
 * 本域解锁的两条新桩面（见 `scripts/README.md` §2.3c）：
 *   - `tool=<name>&args=<json>` 请求级工具路由（L-02…L-08）；
 *   - `epoch=<nonce-N>` 请求级 epoch 注入（V 域模型侧成功路径）。
 * 两条都是**提示词标记**：同一提示词只发一次工具调用（桩的幂等闸），且续轮只发文本
 * （收敛契约 ③），因此「一轮一条请求级工具」是本域用例的固定形态。
 */
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { api, apiUrl, openPath, waitJob, type JobView } from './helpers'
import { aiFetch } from './ai-identity'

export const API_BASE = process.env.E2E_API_BASE
  ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`

/** 提示词唯一后缀：桩的会话指纹 = 首条 user 消息，复用同一句话会被判成同一会话。 */
let seq = 0
export function uniq(tag: string): string {
  seq += 1
  return `${tag}-${Date.now().toString(36)}${seq}${Math.random().toString(36).slice(2, 5)}`
}

export interface SseFrame {
  type: string
  seq?: number
  runId?: string
  toolCallId?: string
  name?: string
  ok?: boolean
  executed?: boolean
  status?: string
  outcome?: string
  code?: string | null
  retryable?: boolean
  result?: string
  args?: unknown
  resultView?: { kind?: string; columns?: Array<{ key?: string; label?: string }>; rows?: unknown[] } | null
  usage?: unknown
  [key: string]: unknown
}

export function parseSse(raw: string): SseFrame[] {
  const frames: SseFrame[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (!line.startsWith('data:')) continue
    const payload = line.slice(5).trim()
    if (!payload.startsWith('{')) continue
    try {
      frames.push(JSON.parse(payload) as SseFrame)
    } catch {
      /* 迟到帧/半帧：解析不了即跳过（解析成功与否由断言侧判定） */
    }
  }
  return frames
}

export interface ChatContext {
  page?: string
  taskType?: string
  step?: string
  taskId?: number
  extra?: Record<string, unknown>
}

/**
 * API 层直打 `POST /api/ai/chat`（一次性读完整个流）。
 *
 * 需要写 context 的用例（工具披露面由 `page` / `task:*` 决定）走这里；
 * 需要**中途决策**的用例（确认门）走 UI 的真按钮，不用本函数。
 */
export async function chatStream(
  sessionId: string,
  message: string,
  context?: ChatContext,
  timeoutMs = 180_000
): Promise<{ frames: SseFrame[]; raw: string; status: number }> {
  const res = await aiFetch(`${API_BASE}/api/ai/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(context === undefined ? { sessionId, message } : { sessionId, message, context }),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const raw = await res.text()
  return { frames: parseSse(raw), raw, status: res.status }
}

export function framesOf(frames: SseFrame[], type: string): SseFrame[] {
  return frames.filter((frame) => frame.type === type)
}

export function toolResults(frames: SseFrame[]): SseFrame[] {
  return framesOf(frames, 'tool_result')
}

/** 指定工具名的最后一个 tool_result（本轮只有一条时等于唯一一条）。 */
export function toolResultOf(frames: SseFrame[], name: string): SseFrame {
  const hits = toolResults(frames).filter((frame) => frame.name === name)
  expect(hits.length, `本轮应恰有 ≥1 个 ${name} 的 tool_result`).toBeGreaterThan(0)
  return hits[hits.length - 1] as SseFrame
}

export function doneFrame(frames: SseFrame[]): SseFrame {
  const done = framesOf(frames, 'done')
  expect(done.length, '本轮应恰有 1 个 done').toBe(1)
  return done[0] as SseFrame
}

// ── 页面驱动 ─────────────────────────────────────────────────────────────────

/** 导入向导的「上传配置」步（区域齐全 + 上传入口可点）。 */
export async function openImportUpload(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/import?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
  await expect(page.locator('[data-ai-ref="import.upload.choose"]')).toHaveCount(1, { timeout: 20_000 })
}

/** 导入向导的指定步（披露面随之切换）。 */
export async function openImportStep(page: Page, taskId: number, step: string): Promise<void> {
  await openPath(page, `/import?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText(`task:IMPORT/${step}`, { timeout: 30_000 })
}

export function handoffCard(page: Page) {
  return page.locator('[data-testid="upload-handoff"]')
}

/**
 * 建立一张真实的上传交接卡。
 *
 * 两种入口（都是产品既有路径）：
 *   - `ref` 变体：先让模型自己 `ui_snapshot`（快照闸的合法来源），再**新建对话**发 `ui_request_upload`。
 *     必须换新会话：桩的收敛契约 ③ 规定"上下文已有 role=tool 就只发文本"，
 *     同一会话的第二轮请求级工具路由永远退化成文本轮。
 *   - 缺省变体：不带 ref ⇒ 产品取区域声明的 `uploadEntry`（不走快照闸），一轮即可。
 */
export async function startUploadHandoff(page: Page, options: { ref?: string } = {}): Promise<void> {
  const args: Record<string, unknown> = { accept: ['xlsx'] }
  if (options.ref !== undefined) args.ref = options.ref
  if (options.ref !== undefined) {
    await sendPrompt(page, `tool=ui_snapshot&args={"limit":40,"kinds":["button","input"]} ${uniq('snap')}`)
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
  }
  await sendPromptUnsettled(page, `tool=ui_request_upload&args=${JSON.stringify(args)} ${uniq('handoff')}`)
  await expect(handoffCard(page)).toBeVisible({ timeout: 40_000 })
}

/**
 * 发一句并等本轮收敛（停止钮消失 = 非 loading / 非 suspended）。
 * 挂起类（确认卡 / 交接卡 / 表单）**不能**等收敛 —— 它们本来就要停下来等人，
 * 这类用 `sendPromptUnsettled` 或 `sendAndWaitGate`。
 */
export async function sendPrompt(page: Page, text: string, timeout = 90_000): Promise<void> {
  await dispatch(page, text)
  await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(0, { timeout })
}

/** 发一句但不等收敛（只确认被受理：出现了新的消息气泡）。 */
export async function sendPromptUnsettled(page: Page, text: string): Promise<void> {
  await dispatch(page, text)
}

async function dispatch(page: Page, text: string): Promise<void> {
  const composer = page.locator('[data-testid="composer-input"]')
  const send = page.locator('[data-testid="send-btn"]')
  const bubbles = page.locator('[data-testid^="msg-bubble-"]')
  // 上一轮的 done 与"发送可用"之间有一个极短窗口：此时点发送会被 409 打回
  // （store 回收乐观气泡、清草稿 ⇒ 提示词无声消失）。故按"气泡是否新增"判定受理并重试。
  await expect(composer).toBeEditable({ timeout: 30_000 })
  const before = await bubbles.count()
  let accepted = false
  for (let attempt = 0; attempt < 3 && !accepted; attempt += 1) {
    await composer.fill(text)
    await expect(send).toBeEnabled({ timeout: 15_000 })
    await send.click()
    for (let poll = 0; poll < 10; poll += 1) {
      if (await bubbles.count() > before) {
        accepted = true
        break
      }
      await page.waitForTimeout(200)
    }
  }
  if (!accepted) throw new Error(`提示词未被受理（无新气泡）：${text}`)
}

/** 发一句但**不等**收敛（挂起类用例自己等卡）。 */
export async function sendAndWaitGate(page: Page, text: string, timeout = 40_000): Promise<void> {
  const composer = page.locator('[data-testid="composer-input"]')
  await composer.fill(text)
  await page.locator('[data-testid="send-btn"]').click()
  await expect(page.locator('[data-testid="hitl-gate"]')).toBeVisible({ timeout })
}

// ── 夹具 ─────────────────────────────────────────────────────────────────────

/** 造一个「已上传真实文件 + 预检通过」的导入任务，并推进到指定步。 */
export async function createImportTaskAtStep(
  request: APIRequestContext,
  step: 'UPLOAD' | 'PRECHECK' | 'IMPORT',
  defCode = 'DOC_TYPE'
): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', {
    type: 'IMPORT',
    title: `E2E-UNLOCK-${step}-${Date.now()}`
  })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: [defCode] })
  const template = await request.get(apiUrl(`/api/tasks/${id}/files/templates?codes=${defCode}`))
  if (!template.ok()) throw new Error(`模板下载失败：HTTP ${template.status()}`)
  const bytes = await template.body()
  const upload = await request.post(apiUrl(`/api/tasks/${id}/files/upload`), {
    multipart: {
      file: {
        name: `${defCode}_unlock.xlsx`,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: bytes
      }
    }
  })
  if (!upload.ok()) throw new Error(`上传失败：HTTP ${upload.status()} ${await upload.text()}`)
  if (step === 'IMPORT') {
    const job = await api.post<JobView>(request, `/api/tasks/${id}/jobs`, { jobType: 'PRECHECK' })
    const final = await waitJob(request, job.id)
    if (final.status !== 'COMPLETED') throw new Error(`PRECHECK 终态 ${final.status}`)
    await api.put(request, `/api/tasks/${id}/step`, { step: 'IMPORT' })
  } else if (step === 'PRECHECK') {
    // 停在「检查配置」步（start_precheck 的披露面 = task:IMPORT/PRECHECK）
    await api.put(request, `/api/tasks/${id}/step`, { step: 'PRECHECK' })
  }
  return id
}

export interface TaskView {
  id: number
  title?: string
  type?: string
  status?: string
}

/**
 * `GET /api/tasks` 返回 Spring `Page`（`data.content` + 分页元数据），
 * 每行是 `TaskSummary{task, itemCount, fileCount, latestJob}` —— 任务本体在 `task` 键下。
 */
export async function listTasks(request: APIRequestContext, size = 50): Promise<TaskView[]> {
  const page = await api.get<{ content?: Array<{ task?: TaskView }> }>(request, `/api/tasks?size=${size}`)
  return (page.content ?? []).map((row) => row.task).filter((task): task is TaskView => task !== undefined)
}

export async function taskJobs(request: APIRequestContext, taskId: number, jobType?: string): Promise<JobView[]> {
  const jobs = await api.get<JobView[]>(request, `/api/tasks/${taskId}/jobs`)
  return jobType === undefined ? jobs : jobs.filter((job) => job.jobType === jobType)
}

// ── 页面内模块（真页面里的真模块实例）────────────────────────────────────────

/**
 * 在真页面里 import 应用**真实加载过**的模块实例。
 *
 * 为什么不能 `import('/src/utils/ai-regions.ts')`：dev server 的 HMR 会给改动过的模块加 `?t=`，
 * 裸路径拿到的是另一份实例（epoch / 快照闸 / 区域表都是模块级状态）。按 `performance` 的
 * 真实资源名取同一份 —— 与 `helpers-vl.ts` 同源做法（本文件自带一份，避免跨域助手耦合）。
 */
export async function installImporter(page: Page): Promise<void> {
  const install = (): void => {
    const host = window as unknown as { __unlockImport?: (path: string) => Promise<unknown> }
    host.__unlockImport = async (path: string) => {
      const loaded = performance.getEntriesByType('resource').map((entry) => entry.name)
      const exact = loaded.find((name) => name.includes(path))
      return import(exact ?? path)
    }
  }
  await page.addInitScript(install)
  await page.evaluate(install).catch(() => undefined)
}

/**
 * 在页面里做一次**真快照**（产品的 `aiSnapshot`，真 DOM 遍历）并回读当前 epoch。
 *
 * 为什么要用例自己发起：桩的收敛契约 ③ 使"同一会话第二轮只发文本"，而
 * `registerAiRegion` 每次注册都会推 epoch —— 换新会话时区域重建会把上一轮的快照闸打失效。
 * 因此「模型先 ui_snapshot、再 ui_*」这条两轮链在同一个 epoch 里不可达；
 * 用例改为用产品同一个快照函数开闸（判据仍是产品自己的快照闸 + epoch 围栏）。
 */
export async function snapshotAndEpoch(page: Page, args: Record<string, unknown> = { limit: 40 }): Promise<string> {
  await installImporter(page)
  return page.evaluate(async (snapshotArgs) => {
    const host = window as unknown as { __unlockImport?: (path: string) => Promise<unknown> }
    const load = async (path: string) => (host.__unlockImport ? host.__unlockImport(path) : import(path))
    const dom = await load('/src/utils/ai-dom-tools.ts') as { aiSnapshot: (args: Record<string, unknown>) => unknown }
    const regions = await load('/src/utils/ai-regions.ts') as { currentAiEpoch: () => string }
    dom.aiSnapshot(snapshotArgs)
    return regions.currentAiEpoch()
  }, args)
}

/** 页面当前 epoch（`<6 位 nonce>-<计数器>`；V 域请求级 epoch 注入的取值来源）。 */
export async function epochOf(page: Page): Promise<string> {
  await installImporter(page)
  return page.evaluate(async () => {
    const host = window as unknown as { __unlockImport?: (path: string) => Promise<unknown> }
    const load = async (path: string) => (host.__unlockImport ? host.__unlockImport(path) : import(path))
    const regions = await load('/src/utils/ai-regions.ts') as { currentAiEpoch: () => string }
    return regions.currentAiEpoch()
  })
}

/** 读 store 里的活动交接态（断言"卡是否还活着"的离散量）。 */
export async function activeHandoff(page: Page): Promise<{ toolCallId: string; status: string; ref: string | null } | null> {
  await installImporter(page)
  return page.evaluate(async () => {
    const host = window as unknown as { __unlockImport?: (path: string) => Promise<unknown> }
    const load = async (path: string) => (host.__unlockImport ? host.__unlockImport(path) : import(path))
    const mod = await load('/src/stores/ai.ts') as { useAiStore: () => { activeUploadHandoff: unknown } }
    const store = mod.useAiStore()
    const handoff = store.activeUploadHandoff as { toolCallId: string; status: string; ref: string | null } | null
    return handoff === null ? null : { toolCallId: handoff.toolCallId, status: handoff.status, ref: handoff.ref }
  })
}

/** 页面里的 UI 原语运行时（写动词配额 / 撤销栈 / 播报 / epoch）。 */
export async function uiRuntime(page: Page): Promise<{
  writeCount: number
  undo: unknown
  announcement: string | null
  epoch: string
}> {
  await installImporter(page)
  return page.evaluate(async () => {
    const host = window as unknown as { __unlockImport?: (path: string) => Promise<unknown> }
    const load = async (path: string) => (host.__unlockImport ? host.__unlockImport(path) : import(path))
    const dom = await load('/src/utils/ai-dom-tools.ts') as {
      uiWriteVerbCount: () => number
      peekUiUndo: () => unknown
      peekUiAnnouncement: () => string | null
    }
    const regions = await load('/src/utils/ai-regions.ts') as { currentAiEpoch: () => string }
    return {
      writeCount: dom.uiWriteVerbCount(),
      undo: dom.peekUiUndo(),
      announcement: dom.peekUiAnnouncement(),
      epoch: regions.currentAiEpoch()
    }
  })
}

/** 卡片右状态标签（`data-state` 是离散量，比文案稳定）。 */
export function cardState(page: Page, toolCallId: string) {
  return page.locator(`[data-testid="card-status-${toolCallId}"], [data-testid="ro-badge-${toolCallId}"]`)
}
