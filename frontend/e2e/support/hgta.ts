/**
 * H/G/T/A 域 E2E 公共助手（本域独占；不改 `support/helpers.ts` 与 `support/harness.ts`）。
 *
 * 三件事：
 * 1. **起停幂等**：整仓跑时 globalSetup 已起服务 → 探活命中即跳过自起/收尾；
 *    本域单独跑时用 `harness-hgta`（独立 RUN_DIR）自起自收。
 * 2. **桩侧取证**：读 `RUN_DIR/stub/req-NNN.json`（上游请求原文）与 `decision.json`——
 *    "进模型的是什么" 只有这一条真实通道，比断言 UI 文案强得多。
 * 3. **唯一后缀**：桩的会话指纹 = 首条 user 消息，同族用例必须各自带唯一后缀，
 *    否则台账串台（`support/helpers.ts` 的提示词不带后缀，跨用例复用会让桩认为"同一轮"）。
 */
import { expect, type APIRequestContext, type Page, type Request } from '@playwright/test'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { RUN_DIR, startServices, stopServices, URLS } from './harness-hgta'

export { RUN_DIR, URLS }

/** 本域自起服务时用的组件集合：主后端 + 前端（降级面不在 H/G/T/A 的断言面内）。 */
const ONLY = ['main', 'app'] as const

let owned = false

async function alive(url: string): Promise<boolean> {
  try {
    const res = await fetch(url)
    return res.status < 500
  } catch {
    return false
  }
}

/**
 * beforeAll 用：已有实例（globalSetup 起的 / E2E_EXTERNAL）则不动，否则自起。
 * 返回 true 表示"这次是自起的，afterAll 必须收"。
 */
export async function ensureServices(): Promise<void> {
  if (process.env.E2E_EXTERNAL === '1') return
  if (await alive(`${URLS.api}/api/ai/health`) && await alive(`${URLS.app}/`)) return
  await startServices({ only: [...ONLY] })
  owned = true
}

export async function releaseServices(): Promise<void> {
  if (!owned) return
  owned = false
  await stopServices()
}

// ── 唯一后缀（桩的会话指纹按首条 user 消息） ──────────────────────────────────

let counter = 0

/** 纯字母后缀（不能带数字：桩按首条消息里的**第一个数字**取 taskId）。 */
export function uniq(prefix = ''): string {
  counter += 1
  const letters = Array.from({ length: 6 }, () => String.fromCharCode(97 + Math.floor(Math.random() * 26))).join('')
  return `${prefix}${letters}${String.fromCharCode(97 + (counter % 26))}`
}

// ── 桩侧取证 ────────────────────────────────────────────────────────────────

/**
 * 桩落档目录：本域单独跑时在 `RUN_DIR/stub`（harness-hgta 的独立目录）；
 * 整仓跑（既有 global-setup 用共享 RUN_DIR）时在 `test-results/.e2e-run/stub`。
 * 两个都试，取"最近写过文件"的那个，避免换入口跑时读到空目录。
 */
function stubDirs(): string[] {
  return [join(RUN_DIR, 'stub'), join(RUN_DIR, '..', '.e2e-run', 'stub')]
}

function activeStubDir(): string | null {
  let best: { dir: string; mtime: number } | null = null
  for (const dir of stubDirs()) {
    if (!existsSync(dir)) continue
    const names = readdirSync(dir).filter((name) => /^req-\d+\.json$/.test(name))
    if (names.length === 0) continue
    const newest = Math.max(...names.map((name) => statSync(join(dir, name)).mtimeMs))
    if (!best || newest > best.mtime) best = { dir, mtime: newest }
  }
  return best?.dir ?? null
}

interface StubDecision {
  kind?: string
  tool?: string
  route?: string
  reason?: string
}

export interface StubRequest {
  index: number
  body: Record<string, unknown>
  decision: StubDecision | null
  raw: string
}

/** 桩落档的请求原文（按序号升序）。目录不存在 = 桩没收到请求。 */
export function stubRequests(): StubRequest[] {
  const STUB_DIR = activeStubDir()
  if (STUB_DIR === null) return []
  const names = readdirSync(STUB_DIR).filter((name) => /^req-\d+\.json$/.test(name)).sort()
  const out: StubRequest[] = []
  for (const name of names) {
    const path = join(STUB_DIR, name)
    let raw = ''
    let body: Record<string, unknown> = {}
    try {
      raw = readFileSync(path, 'utf8')
      body = JSON.parse(raw) as Record<string, unknown>
    } catch {
      continue
    }
    let decision: StubDecision | null = null
    try {
      decision = JSON.parse(readFileSync(`${path}.decision.json`, 'utf8')) as StubDecision
    } catch {
      decision = null
    }
    out.push({ index: Number(/_(\d+)\.json$/.exec(name)?.[1] ?? '0'), body, decision, raw })
  }
  return out.sort((a, b) => a.index - b.index)
}

/** 该指纹（首条 user 消息前 160 字）对应的全部上游请求。 */
export function stubRequestsFor(firstUserText: string): StubRequest[] {
  const fp = firstUserText.slice(0, 160)
  return stubRequests().filter((req) => {
    const messages = (req.body.messages ?? []) as Array<{ role?: string; content?: unknown }>
    const first = messages.find((msg) => msg?.role === 'user')
    const text = typeof first?.content === 'string' ? first.content : JSON.stringify(first?.content ?? '')
    return text.slice(0, 160) === fp
  })
}

/** 请求里 role=tool 的消息文本（= 模型看到的工具结果；"进模型"的唯一真值面）。 */
export function toolMessagesOf(body: Record<string, unknown>): string[] {
  const messages = (body.messages ?? []) as Array<{ role?: string; content?: unknown }>
  return messages
    .filter((msg) => msg?.role === 'tool')
    .map((msg) => (typeof msg.content === 'string' ? msg.content : JSON.stringify(msg.content ?? '')))
}

/** 等到该指纹的上游请求数达到 n（模型真的被调用过 n 次）。 */
export async function untilStubRequests(firstUserText: string, n: number, timeoutMs = 30_000): Promise<StubRequest[]> {
  const deadline = Date.now() + timeoutMs
  let last: StubRequest[] = []
  while (Date.now() < deadline) {
    last = stubRequestsFor(firstUserText)
    if (last.length >= n) return last
    await new Promise((done) => setTimeout(done, 250))
  }
  throw new Error(`等待超时：指纹「${firstUserText.slice(0, 40)}…」的上游请求 ${last.length} < ${n}`)
}

// ── 页面与 API ──────────────────────────────────────────────────────────────

export const API = URLS.api

export async function apiGet<T>(request: APIRequestContext, path: string): Promise<T> {
  const res = await request.get(`${API}${path}`)
  if (!res.ok()) throw new Error(`GET ${path} => HTTP ${res.status()} ${await res.text()}`)
  const json = (await res.json()) as { success?: boolean; data?: T }
  if (json.success === false) throw new Error(`GET ${path} => success=false`)
  return json.data as T
}

export async function apiPost<T>(request: APIRequestContext, path: string, body: unknown): Promise<T> {
  const res = await request.post(`${API}${path}`, { data: body })
  if (!res.ok()) throw new Error(`POST ${path} => HTTP ${res.status()} ${await res.text()}`)
  const json = (await res.json()) as { success?: boolean; data?: T }
  if (json.success === false) throw new Error(`POST ${path} => success=false`)
  return json.data as T
}

/** 原始 POST（要断言 4xx 与错误码时用；不抛）。 */
export async function apiPostRaw(
  request: APIRequestContext,
  path: string,
  body: unknown
): Promise<{ status: number; json: Record<string, unknown>; text: string }> {
  const res = await request.post(`${API}${path}`, { data: body })
  const text = await res.text()
  let json: Record<string, unknown> = {}
  try {
    json = JSON.parse(text) as Record<string, unknown>
  } catch {
    json = {}
  }
  return { status: res.status(), json, text }
}

export interface CurrentRun {
  found: boolean
  runId?: string
  snapshotStatus?: string
  pendingEntries?: Array<Record<string, unknown>>
  unresolvedExternal?: string[]
  awaitingExternal?: boolean
  archiveMaxSeq?: number
}

export async function currentRun(request: APIRequestContext, sid: string): Promise<CurrentRun> {
  return apiGet<CurrentRun>(request, `/api/ai/runs/current?sessionId=${encodeURIComponent(sid)}`)
}

export function sidOf(page: Page): Promise<string> {
  return page.evaluate(() => sessionStorage.getItem('ai-session-id') ?? '')
}

/**
 * 直投一轮对话（API 层用；前端工具/确认门的"真值三通道"里那条 SSE 帧通道）。
 * 挂起即断连 ⇒ 调用在挂起处就返回，响应体是**已发帧**的原文。
 */
export async function chatFrames(
  request: APIRequestContext,
  payload: { sessionId: string; message: string; context?: Record<string, unknown> }
): Promise<{ status: number; text: string; frames: Array<Record<string, unknown>> }> {
  const res = await request.post(`${API}/api/ai/chat`, {
    data: { sessionId: payload.sessionId, message: payload.message, context: payload.context ?? { page: 'tasks' } },
    headers: { accept: 'text/event-stream' }
  })
  const text = await res.text()
  const frames: Array<Record<string, unknown>> = []
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed.startsWith('data:')) continue
    const payloadText = trimmed.slice(5).trim()
    if (!payloadText || payloadText === '[DONE]') continue
    try {
      frames.push(JSON.parse(payloadText) as Record<string, unknown>)
    } catch {
      /* 非 JSON 帧（心跳注释等）不计 */
    }
  }
  return { status: res.status(), text, frames }
}

/** 最后一个气泡的 testid（同一气泡续写时 id 不变）。 */
export async function lastBubbleId(page: Page): Promise<string> {
  const bubble = page.locator('[data-testid^="msg-bubble-"].w-full').last()
  const id = await bubble.getAttribute('data-testid')
  return id ?? ''
}

/** 页面里的 POST 请求（前端工具回灌/确认）——用于断言"回灌体只带 fileId"这类线上形状。 */
export function watchPost(page: Page, urlPart: string): Promise<Request> {
  return page.waitForRequest((req) => req.url().includes(urlPart) && req.method() === 'POST', { timeout: 20_000 })
}

/** 断言"行内没有再出现"的通用小工具（真 DOM 计数，不用 sleep）。 */
export async function expectGone(page: Page, testId: string, timeout = 20_000): Promise<void> {
  await expect(page.locator(`[data-testid="${testId}"]`)).toHaveCount(0, { timeout })
}
