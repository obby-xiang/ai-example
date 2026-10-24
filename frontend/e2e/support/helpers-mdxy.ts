/**
 * MD / X / Y 三域的公共助手：桩正文常量、桩请求取证、唯一后缀、设置页打开。
 *
 * 与 `helpers.ts`（页面驱动）同族，但只读本域自己的桩落档目录（`<RUN_DIR>/stub`）与
 * `content.json` —— 用例与桩因此只有一个字面量来源，不会两边各写一份而漂移。
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { redisCommand, REDIS_DB, RUN_DIR } from './harness'

export { RUN_DIR }
export const STUB_DIR = join(RUN_DIR, 'stub')

export interface MdxyContent {
  basic: string
  highlight: string
  mermaid: string
  mermaidBroken: string
  xss: string
  long: string
  streamPart1: string
  streamPart2: string
  deltaPieces: string[]
  safetyMask: string
  safetyPem: string
  closingText: string
  defaultText: string
  stubModelIds: string[]
}

let cached: MdxyContent | null = null

/** 桩启动时落盘的正文常量（桩已就绪 ⇒ 文件必在）。 */
export function content(): MdxyContent {
  if (cached !== null) return cached
  const file = join(STUB_DIR, 'content.json')
  if (!existsSync(file)) throw new Error(`缺少桩正文常量：${file}（桩未就绪或 E2E_STUB_SCRIPT 未指向 mdxy-upstream.mjs）`)
  cached = JSON.parse(readFileSync(file, 'utf8')) as MdxyContent
  return cached
}

// ── 唯一后缀（每轮一条，做"每路由一次"的会话指纹） ────────────────────────────

let counter = 0

/** 纯字母后缀（不带数字：桩按首个数字认 taskId 的旧约定在此不适用，但保持同族书写习惯）。 */
export function uniq(prefix = ''): string {
  counter += 1
  const letters = Array.from({ length: 6 }, () => String.fromCharCode(97 + Math.floor(Math.random() * 26))).join('')
  return `${prefix}${letters}${String.fromCharCode(97 + (counter % 26))}`
}

// ── 桩侧取证（上游收到的原文） ────────────────────────────────────────────────

export interface StubRequest {
  tag: string
  raw: string
  tools: string[]
}

function requestFiles(): string[] {
  if (!existsSync(STUB_DIR)) return []
  return readdirSync(STUB_DIR).filter((name) => /^req-\d+\.json$/.test(name))
}

function readRequest(tag: string): StubRequest {
  const raw = readFileSync(join(STUB_DIR, `req-${tag}.json`), 'utf8')
  const toolsFile = join(STUB_DIR, `req-${tag}.tools.txt`)
  const tools = existsSync(toolsFile)
    ? readFileSync(toolsFile, 'utf8').split(/\r?\n/).map((line) => line.trim())
      .filter((line) => line !== '' && !line.startsWith('disclosedCount='))
    : []
  return { tag, raw, tools }
}

/** 最近一次上游请求（按 mtime；桩的序号单调，但落盘与写出有时序差）。 */
export function latestStubRequest(): StubRequest | null {
  const files = requestFiles()
  if (files.length === 0) return null
  const ordered = files.sort(
    (a, b) => statSync(join(STUB_DIR, b)).mtimeMs - statSync(join(STUB_DIR, a)).mtimeMs
  )
  return readRequest(ordered[0].replace(/^req-/, '').replace(/\.json$/, ''))
}

/** 等到 tag 之后出现的新请求（显式等待，不用固定 sleep）。 */
export async function untilStubRequestAfter(previousTag: string | null, timeoutMs = 60_000): Promise<StubRequest> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const record = latestStubRequest()
    if (record && record.tag !== previousTag) return record
    await new Promise((done) => setTimeout(done, 200))
  }
  throw new Error(`等待上游请求记录超时（在 ${previousTag ?? '(none)'} 之后无新请求）`)
}

/** 全部上游请求的原文拼串（"某段文本从未进过模型"类断言用）。 */
export function allStubRequestText(): string {
  return requestFiles().sort().map((name) => readFileSync(join(STUB_DIR, name), 'utf8')).join('\n')
}

/** 解析某条请求里最后一条 user 消息的正文。 */
export function lastUserTextOf(record: StubRequest): string {
  try {
    const body = JSON.parse(record.raw) as { messages?: Array<{ role?: string; content?: unknown }> }
    const users = (body.messages ?? []).filter((m) => m?.role === 'user')
    const content = users[users.length - 1]?.content
    if (typeof content === 'string') return content
    if (Array.isArray(content)) {
      return content.map((part) => (typeof part === 'string' ? part : '')).join('')
    }
    return ''
  } catch {
    return ''
  }
}

// ── 页面驱动 ─────────────────────────────────────────────────────────────────

/** 打开「AI 设置」页并等首屏加载完成（三态：loading → 内容/空/错误）。 */
export async function openAiSettings(page: Page): Promise<void> {
  await page.goto('/settings/ai', { waitUntil: 'domcontentloaded' })
  await expect(page.locator('[data-testid="ai-settings-view"]')).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('[data-testid="ai-settings-loading"]')).toHaveCount(0, { timeout: 30_000 })
}

// ── 安全面取证（X 域） ───────────────────────────────────────────────────────

export interface SecurityCounters {
  inputRejected: number
  inputRedacted: number
  outputRedacted: number
  outputBlocked: number
  rateLimited: number
  contextDowngraded: number
  payloadTruncated: number
}

function apiBase(): string {
  return process.env.E2E_API_BASE ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`
}

/** `/health.security` 的七计数快照（进程内口径，逐请求现算）。 */
export async function securityCounters(request: APIRequestContext): Promise<SecurityCounters> {
  const res = await request.get(`${apiBase()}/api/ai/health`)
  const body = (await res.json()) as { security?: Partial<SecurityCounters> }
  const raw = body.security ?? {}
  return {
    inputRejected: Number(raw.inputRejected ?? 0),
    inputRedacted: Number(raw.inputRedacted ?? 0),
    outputRedacted: Number(raw.outputRedacted ?? 0),
    outputBlocked: Number(raw.outputBlocked ?? 0),
    rateLimited: Number(raw.rateLimited ?? 0),
    contextDowngraded: Number(raw.contextDowngraded ?? 0),
    payloadTruncated: Number(raw.payloadTruncated ?? 0)
  }
}

/** RESP 数组回包 → 字符串数组（`redisCommand` 的原始回包解析；只处理 bulk string 形态）。 */
export function parseBulkArray(raw: string): string[] {
  const out: string[] = []
  const lines = raw.split('\r\n')
  for (let i = 0; i < lines.length; i += 1) {
    if (!lines[i].startsWith('$')) continue
    const length = Number(lines[i].slice(1))
    if (Number.isNaN(length) || length < 0) continue
    out.push(lines[i + 1] ?? '')
    i += 1
  }
  return out
}

/** 会话安全台账（`ai:safety:<sessionId>` 的 LRANGE 原文，新→旧）。 */
export async function safetyLedger(sessionId: string): Promise<string[]> {
  const raw = await redisCommand(REDIS_DB, ['LRANGE', `ai:safety:${sessionId}`, '0', '-1'])
  return parseBulkArray(raw)
}


