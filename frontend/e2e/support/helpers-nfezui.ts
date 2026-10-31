/**
 * NFEZUI 域（N 会话 / F 反馈与消息操作 / E 空态与错误卡 / Z 结果视图 / U 撤销 / I i18n）专用助手。
 *
 * 只读复用 `support/helpers.ts` 的通用面（API 基址、发消息、等收敛），在它之上补本域需要的能力：
 * 独立端口基下的降级面、会话/镜像键读取、语言切换、通知与错误卡取值。
 * **不改 support/helpers.ts**（同族并行写别的 spec，共享文件只读）。
 */
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { aiRequestOptions, pageSessionId } from './ai-identity'

export const NFEZUI_API = process.env.E2E_API_BASE ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '19130'}`
export const NFEZUI_DEGRADED_API = process.env.E2E_DEGRADED_API_BASE
  ?? `http://127.0.0.1:${process.env.E2E_DEGRADED_BACKEND_PORT ?? '19131'}`
export const NFEZUI_APP = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '19201'}`
export const NFEZUI_DEGRADED_APP = process.env.E2E_DEGRADED_APP
  ?? `http://127.0.0.1:${process.env.E2E_DEGRADED_APP_PORT ?? '19202'}`
export const NFEZUI_REDIS_DB = Number(process.env.E2E_REDIS_DB ?? '13')

/** 桩 turn-text 的确定性文本（事实源 = scripts/ci/stub-upstream.mjs 的 TURN_TEXT）。 */
export const TURN_TEXT = '命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），'
  + '以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。'

/** 本域自建桩的提示词标记（事实源 = support/nfezui-upstream.mjs 的 MARKERS）。 */
export const MARK = {
  listTasks: 'NFZ-LIST-TASKS',
  quota: 'NFZ-QUOTA-429',
  filter: 'NFZ-FILTER',
  generic: 'NFZ-GENERIC'
} as const

/**
 * `list_tasks` 单轮回填上限（事实源 = 后端 `AiTools.LIST_TASKS_MAX_ROWS`，2026-10-31 由 20 调为 50）。
 * 截断用例造到上限 + 2 条才会看到 `resultview-truncated`。
 */
export const LIST_TASKS_MAX_ROWS = 50

export interface ApiEnvelope<T> {
  success?: boolean
  data?: T
}

export async function apiGet<T>(request: APIRequestContext, path: string, base = NFEZUI_API): Promise<T> {
  const url = `${base}${path}`
  const res = await request.get(url, aiRequestOptions(url))
  if (!res.ok()) throw new Error(`GET ${path} => HTTP ${res.status()} ${await res.text()}`)
  const json = (await res.json()) as ApiEnvelope<T>
  return (json.data ?? json) as T
}

export async function apiRaw(request: APIRequestContext, method: 'get' | 'post' | 'put' | 'delete', path: string, body?: unknown) {
  const url = `${NFEZUI_API}${path}`
  return request[method](url, aiRequestOptions(url, body === undefined ? {} : { data: body }))
}

export async function openPanel(page: Page, path = '/'): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
}

export async function send(page: Page, text: string): Promise<void> {
  const composer = page.locator('[data-testid="composer-input"]')
  await composer.fill(text)
  await page.locator('[data-testid="send-btn"]').click()
}

/** 等本轮收敛（停止钮消失 = 不再 loading/suspended）。 */
export async function waitSettled(page: Page, timeout = 60_000): Promise<void> {
  await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(0, { timeout })
}

export function assistantBubbles(page: Page) {
  return page.locator('[data-testid^="msg-bubble-"].w-full')
}

export function assistantBubble(page: Page) {
  return assistantBubbles(page).last()
}

export function userBubbles(page: Page) {
  return page.locator('[data-testid^="msg-bubble-"]:not(.w-full)')
}

export async function sessionIdOf(page: Page): Promise<string> {
  return pageSessionId(page)
}

export interface MirrorState {
  keys: string[]
  sessionId: string | null
  mirror: string | null
}

/** 镜像与页签会话键的现场（会话键/镜像键都按页面身份的键形取，不读裸键）。 */
export async function storageState(page: Page): Promise<MirrorState> {
  return page.evaluate(() => {
    const keys: string[] = []
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i)
      if (key) keys.push(key)
    }
    const raw = sessionStorage.getItem('ai-identity')
    const identity = raw ? JSON.parse(raw) as { tenantId: number; userId: number } : null
    const fingerprint = identity ? `${identity.tenantId}:${identity.userId}` : null
    const sessionId = fingerprint ? sessionStorage.getItem(`ai-session-id:${fingerprint}`) : null
    return {
      keys,
      sessionId,
      mirror: identity && sessionId ? sessionStorage.getItem(`ai-mirror:${fingerprint}:${sessionId}`) : null
    }
  })
}

export interface HistoryEntry {
  role: string
  content?: string
  [key: string]: unknown
}

export interface AiHistory {
  count: number
  messages?: HistoryEntry[]
}

export async function historyOf(request: APIRequestContext, sid: string): Promise<AiHistory> {
  return apiGet<AiHistory>(request, `/api/ai/history/${encodeURIComponent(sid)}`)
}

export interface FeedbackEntry {
  messageId: string
  rating: number
  excerpt?: string | null
  createdAt?: string
  sessionId?: string
}

export async function feedbackList(request: APIRequestContext): Promise<FeedbackEntry[]> {
  return apiGet<FeedbackEntry[]>(request, '/api/ai/feedback')
}

/** 切到英文（真点 `data-test="locale-toggle"`；钮上写的是**目标语言**：未切时显示 EN）。 */
export async function switchToEnglish(page: Page): Promise<void> {
  const toggle = page.locator('[data-test="locale-toggle"]')
  if ((await toggle.innerText()).trim() === 'EN') {
    await toggle.click()
  }
  await expect.poll(async () => (await toggle.innerText()).trim()).toBe('中文')
}

/** 切回中文（同上，真点；英文态钮上显示「中文」）。 */
export async function switchToChinese(page: Page): Promise<void> {
  const toggle = page.locator('[data-test="locale-toggle"]')
  if ((await toggle.innerText()).trim() === '中文') {
    await toggle.click()
  }
  await expect.poll(async () => (await toggle.innerText()).trim()).toBe('EN')
}

export interface TaskRow {
  id: number
  type?: string
  title?: string
  currentStep?: string
  status?: string
}

/** 任务列表（视图与任务中心同源的真数据面；`GET /api/tasks` 回 Spring `Page` 而不是数组）。 */
export async function listTasks(request: APIRequestContext, size = 200): Promise<TaskRow[]> {
  // 行形状是 TaskSummary（`{task:{id,…}, itemCount, fileCount}`），不是裸 Task
  const page = await apiGet<Array<{ task?: TaskRow } & TaskRow> | { content?: Array<{ task?: TaskRow } & TaskRow> }>(
    request,
    `/api/tasks?page=0&size=${size}`
  )
  const rows = Array.isArray(page) ? page : (page?.content ?? [])
  return rows.map((row) => row.task ?? row)
}

/** 造一条任务（Z 域视图夹具）。 */
export async function createTask(request: APIRequestContext, title: string, type = 'EXPORT'): Promise<TaskRow> {
  const res = await request.post(`${NFEZUI_API}/api/tasks`, { data: { type, title } })
  if (!res.ok()) throw new Error(`POST /api/tasks => HTTP ${res.status()} ${await res.text()}`)
  const json = (await res.json()) as ApiEnvelope<TaskRow>
  const row = (json.data ?? json) as TaskRow
  if (!row || typeof row.id !== 'number') throw new Error('POST /api/tasks 未返回任务 id')
  return row
}

/** 删光任务（Z-04 空视图的唯一真实来源）。 */
export async function deleteAllTasks(request: APIRequestContext): Promise<number> {
  let removed = 0
  for (const task of await listTasks(request)) {
    const res = await request.delete(`${NFEZUI_API}/api/tasks/${task.id}`)
    if (res.ok()) removed += 1
  }
  return removed
}

/** 面板内可见文本（用于 CJK 残留扫描）。 */
export async function panelText(page: Page): Promise<string> {
  return page.locator('.ai-chat').first().innerText()
}

/** 本域 Redis DB 上的最小命令面（会话 TTL / 过期模拟；只碰本域独占库）。 */
export async function redisEval(db: number, args: string[]): Promise<string> {
  const { redisCommand } = await import('./harness')
  return redisCommand(db, args)
}

/** 整数型 Redis 回包（`:` 前缀）。 */
export async function redisInt(db: number, args: string[]): Promise<number> {
  const reply = (await redisEval(db, args)).trim().replace(/^:/, '')
  return Number(reply)
}

/** 命中 CJK 的片段（空数组 = 零残留）。 */
export function cjkHits(text: string): string[] {
  const hits: string[] = []
  for (const match of text.matchAll(/[\u4e00-\u9fff]+/g)) {
    hits.push(match[0])
  }
  return hits
}

/** 最后一张工具卡的 toolCallId（卡面 testid = `tool-card-<toolCallId>`）。 */
export async function lastToolCallId(page: Page): Promise<string> {
  const testId = (await page.locator('[data-testid^="card-title-"]').last().getAttribute('data-testid')) ?? ''
  return testId.replace('card-title-', '')
}

/** 展开工具卡卡体（结果视图 / 明细在 `block-body-*` 里，完成态的卡默认收起）。 */
export async function expandToolCard(page: Page, toolCallId: string): Promise<void> {
  const card = page.locator(`[data-testid="tool-card-${toolCallId}"]`)
  await expect(card).toBeVisible({ timeout: 60_000 })
  const toggle = card.locator('[data-testid^="block-toggle-"]').first()
  if ((await toggle.count()) === 0) {
    return
  }
  if ((await toggle.getAttribute('aria-expanded')) === 'false') {
    await toggle.click()
  }
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
}

/** 错误卡：读 `data-variant` 与文本（按消息 id 排序取最后一张）。 */
export function errorCards(page: Page) {
  return page.locator('[data-testid^="error-card-"][data-variant]')
}

export async function waitErrorCard(page: Page, timeout = 40_000): Promise<string> {
  const card = errorCards(page).last()
  await expect(card).toBeVisible({ timeout })
  return (await card.getAttribute('data-variant')) ?? ''
}

/**
 * 制造「缺终帧断流」：让 `POST /api/ai/chat` 回一段**没有 done/error 终帧**的合法 SSE。
 *
 * 为什么不用桩开关 `kill-stream`：它断的是**上游 TCP**，后端会重试并有终帧收尾（实测断流判定不成立）；
 * 而「浏览器侧连接在 done 之前结束」才是 ADR-6 的断流面。这里只在浏览器边界造这一种网络结局，
 * 帧形态按 `api/sse.ts` 的解析契约（`type` 白名单 + `seq`/`runId`）。
 */
export async function fulfillTruncatedStream(page: Page, marker: string, partial = '半截正文仍在屏上'): Promise<void> {
  await page.route('**/api/ai/chat', async (route) => {
    if (route.request().method() !== 'POST' || !(route.request().postData() ?? '').includes(marker)) {
      await route.continue()
      return
    }
    const runId = `nfezui-truncated-${Date.now()}`
    const frames = [
      `data: ${JSON.stringify({ type: 'start', runId, sessionId: 'nfezui', turnNo: 1, seq: 1 })}\n\n`,
      `data: ${JSON.stringify({ type: 'delta', runId, seq: 2, text: partial, assistantId: 'nfezui-a' })}\n\n`
    ].join('')
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache' },
      body: frames
    })
  })
}

/** 真实鼠标悬停（不是 `element.hover()` 的语义替代 —— 本域 F-08 要真指针事件）。 */
export async function realHover(page: Page, selector: string): Promise<void> {
  const box = await page.locator(selector).last().boundingBox()
  if (!box) throw new Error(`元素没有布局盒：${selector}`)
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 8 })
}

export async function computed(page: Page, selector: string, prop: string): Promise<string> {
  return page.locator(selector).last().evaluate(
    (el, name) => window.getComputedStyle(el as Element).getPropertyValue(name),
    prop
  )
}

/** 页面内直接取 store（与组件同一个 Vite 模块实例；`fn` 会被序列化，不能带闭包）。 */
export async function storeEval<T>(
  page: Page,
  fn: (store: Record<string, unknown>, arg: unknown) => T,
  arg?: unknown
): Promise<T> {
  return page.evaluate(async ({ source, payload }) => {
    const mod = await import('/src/stores/ai.ts') as unknown as { useAiStore: () => Record<string, unknown> }
    const store = mod.useAiStore()
    // eslint-disable-next-line no-new-func
    const runner = new Function('store', 'arg', `return (${source})(store, arg)`) as (s: unknown, a: unknown) => unknown
    return runner(store, payload) as never
  }, { source: fn.toString(), payload: arg })
}

/** 造一个「上传配置」步、已勾选 DOC_TYPE 的导入任务（界面原语区域的宿主）。 */
export async function createImportTaskAtUpload(request: APIRequestContext, defCode = 'DOC_TYPE'): Promise<number> {
  const task = await request.post(`${NFEZUI_API}/api/tasks`, {
    data: { type: 'IMPORT', title: `NFEZUI-UP-${Date.now()}` }
  })
  if (!task.ok()) throw new Error(`POST /api/tasks => HTTP ${task.status()}`)
  const json = (await task.json()) as ApiEnvelope<{ id: number }>
  const id = (json.data ?? json) as unknown as number | { id: number }
  const taskId = typeof id === 'number' ? id : id.id
  const selected = await request.post(`${NFEZUI_API}/api/tasks/${taskId}/select-defs`, { data: { defCodes: [defCode] } })
  if (!selected.ok()) throw new Error(`select-defs => HTTP ${selected.status()} ${await selected.text()}`)
  return taskId
}
