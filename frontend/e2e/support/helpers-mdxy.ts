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

// ── 渲染降级开关（L-91 旁路；MD 域断言的前置条件）─────────────────────────────

/** 当前页签的渲染降级开关值（`utils/markdown.ts` 的模块级单例）。 */
export async function renderDegraded(page: Page): Promise<boolean> {
  return page.evaluate(async () => {
    const md = (await import('/src/utils/markdown.ts')) as { isDegraded: () => boolean }
    return md.isDegraded()
  })
}

/**
 * 把「单次渲染耗时预算」（`RENDER_BUDGET_MS` = 24ms）这个**环境相关**的自适应开关在本用例内中性化。
 *
 * <p>产品口径（`src/utils/markdown.ts`）：任意一次渲染耗时越过 24ms 就把 `degraded` 置为
 * **页签级粘滞**，此后该页签不包 `figure.code`、不跑 hljs、不渲染 mermaid —— 短正文也一样
 * （`degraded` 只在渲染**之后**记账，所以是"下一次渲染起"生效）。
 * 在 CI（2 核 + 5 个 JVM + 2 个 vite + Chromium）上，一条普通正文的首次渲染偶尔就会越线；
 * 单测因此在 `tests/setup.ts` 里每例 `resetDegradation()` 归零 —— 即**这条预算本来就不是
 * 用来被 DOM 断言测的东西**，它是 CPU 自适应策略。
 *
 * <p>e2e 的等价动作（同 harness 抬高 AI 限流的做法：策略与判据分离，环境项在 e2e 中性化）：
 * 用产品自己导出的 `resetDegradation()` 在页面启动前挂一个 10ms 复位器，
 * 任何一次越线都被迅速抹平，MD 域断言只测**渲染结构**，不测"机器够不够快"。
 *
 * <p>必须在首次导航**之前**调用（`page.addInitScript`），否则首个渲染窗口不受保护。
 */
export async function neutralizeRenderBudget(page: Page): Promise<void> {
  await page.addInitScript(() => {
    type MdModule = { resetDegradation: () => void }
    let mod: Promise<MdModule> | null = null
    setInterval(() => {
      mod ??= import('/src/utils/markdown.ts') as Promise<MdModule>
      void mod.then((md) => md.resetDegradation()).catch(() => undefined)
    }, 10)
  })
}

/**
 * 降级渲染的 **DOM 指纹**：一个不在 `figure.code` 内的裸 `pre.md-pre`，里面装着**非 mermaid**
 * 语言的围栏且没跑过高亮。
 *
 * <p>常规渲染里不存在这种形态：非 mermaid 围栏一律被包进 `figure.code` 并过 hljs；
 * mermaid 围栏的源码块由卡片接管（`language-mermaid`），故显式排除 —— 否则 MD04–MD07
 * 的卡片源码块会被误判成"降级"。
 */
async function degradedRenderInDom(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const bodies = Array.from(document.querySelectorAll('[data-testid^="msg-bubble-"].w-full .md-body'))
    const body = bodies[bodies.length - 1]
    if (!body) {
      return false
    }
    return Array.from(body.querySelectorAll('pre.md-pre')).some((pre) => {
      if (pre.closest('figure.code') !== null) {
        return false
      }
      const code = pre.querySelector('code[class*="language-"]')
      if (code === null || (code.getAttribute('class') ?? '').includes('language-mermaid')) {
        return false
      }
      return pre.querySelector('span[class*="hljs-"]') === null
    })
  })
}

/**
 * 兜底：断言前若本页签仍是降级渲染（开关还亮着，或**降级版 HTML 已被组件 memo 钉住** ——
 * 后者是"慢渲染把开关点亮"与"同一 tick 的收尾渲染"撞在一起的形态，复位器来不及介入），
 * 用产品自己的恢复动作（刷新）重建 realm 与组件 memo。
 *
 * <p>判据两条：①`isDegraded()`；②{@link degradedRenderInDom} 的 DOM 指纹。
 * 只看开关会漏掉"memo 钉住"那一类，故两者取或。刷新后复位器随 `addInitScript` 一并生效，
 * 该次历史重放是**单次非流式渲染**，输出即常规版。
 *
 * <p>返回 `true` = 本次确实做了恢复（CI 日志里留痕，不静默改路由）。
 *
 * @param marker 恢复后用于等待"历史重放已渲染出该条正文"的一段正文（各形状自己的收口句）
 */
export async function ensureFullRender(page: Page, marker: string, timeoutMs = 30_000): Promise<boolean> {
  const body = page.locator('[data-testid^="msg-bubble-"].w-full .md-body').last()
  let repaired = false
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const [degradedDom, flag] = await Promise.all([degradedRenderInDom(page), renderDegraded(page)])
    if (!degradedDom && !flag) {
      return repaired
    }
    repaired = true
    // eslint-disable-next-line no-console
    console.log(`[md-e2e] 降级渲染已生效（开关=${flag} DOM指纹=${degradedDom}）`
      + `⇒ 第 ${attempt} 次刷新重建 realm 后再断言`)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(body).toContainText(marker, { timeout: timeoutMs })
  }
  return repaired
}
