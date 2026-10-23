/**
 * M（多实例）/ D（降级）/ O（并发）三域专用助手 —— `15-mdo.spec.ts` 私有。
 *
 * 只读复用 `support/helpers.ts` 的公共面 + harness 的环境变量口径，**不改任何既有文件**。
 * 两实例拓扑（M 域的地基）：harness 的后端 A（有 key + 桩上游）与后端 B（无 key 的降级面）。
 * B 虽无模型，但 `/api/ai/events/{runId}`、`/api/ai/runs/{runId}`、`/api/ai/confirm`、
 * `/api/ai/frontend-tool-result`、`/api/ai/cancel/{runId}` 都不查可用性 —— 决策/读面因此可以
 * 真的落在**另一个 JVM** 上；只要两实例共用同一个 Redis DB，它们看到的就是同一份轮次台账。
 * 本域因此用 `E2E_REDIS_DB == E2E_REDIS_DB_DEGRADED` 跑（见 15-mdo 的启动命令）。
 */
import { expect, type APIRequestContext } from '@playwright/test'

function baseOf(rawBase: string | undefined, portVar: string, fallback: string): string {
  if (rawBase !== undefined && rawBase !== '') return rawBase
  return `http://127.0.0.1:${process.env[portVar] ?? fallback}`
}

/** 实例 A（有模型）。 */
export const API = baseOf(process.env.E2E_API_BASE, 'E2E_BACKEND_PORT', '18330')
/** 实例 B（降级面：无 key，但读面/决策面全部可用）。 */
export const API_B = baseOf(process.env.E2E_DEGRADED_API_BASE, 'E2E_DEGRADED_BACKEND_PORT', '18331')

export interface SseFrame {
  type: string
  seq?: number
  runId?: string
  [key: string]: unknown
}

export function parseSse(text: string): SseFrame[] {
  const frames: SseFrame[] = []
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith('data:')) continue
    const payload = line.slice(5).trim()
    if (!payload.startsWith('{')) continue
    frames.push(JSON.parse(payload) as SseFrame)
  }
  return frames
}

export interface ChatResult {
  status: number
  headers: Record<string, string>
  frames: SseFrame[]
  text: string
}

/** 直打某实例的 POST /api/ai/chat（API 层；不依赖 DOM）。 */
export async function postChat(
  sessionId: string,
  message: string,
  options: { base?: string; timeoutMs?: number; context?: unknown } = {}
): Promise<ChatResult> {
  const body: Record<string, unknown> = { sessionId, message }
  if (options.context !== undefined) body.context = options.context
  const res = await fetch(`${options.base ?? API}/api/ai/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(options.timeoutMs ?? 120_000)
  })
  const headers: Record<string, string> = {}
  res.headers.forEach((value, key) => {
    headers[key.toLowerCase()] = value
  })
  const text = await res.text()
  return { status: res.status, headers, frames: parseSse(text), text }
}

/** 归档/回放通道整轮读取（终态轮次读完即收尾）。 */
export async function eventsRaw(runId: string, lastSeq?: number, base = API): Promise<string> {
  const suffix = lastSeq === undefined ? '' : `?lastSeq=${Math.max(0, Math.floor(lastSeq))}`
  const res = await fetch(`${base}/api/ai/events/${encodeURIComponent(runId)}${suffix}`, {
    signal: AbortSignal.timeout(60_000)
  })
  return res.text()
}

export async function eventsFrames(
  runId: string,
  lastSeq?: number,
  base = API
): Promise<SseFrame[]> {
  return parseSse(await eventsRaw(runId, lastSeq, base))
}

export interface LiveStream {
  /** 已收到的帧（随收随长）。 */
  frames: SseFrame[]
  /** 收到终帧（done/error）后 resolve；超时 reject。 */
  done: Promise<SseFrame[]>
  /** 主动断开（用例收尾）。 */
  close: () => void
}

/**
 * 以**流式**方式订阅一条 SSE（POST /chat 或 GET /events）——「同时刻两路订阅」类用例
 * （M-01 扇出 / O-03 双连接）必须真的并发读，不能等响应体收完。
 */
export function openSse(
  url: string,
  options: { method?: 'GET' | 'POST'; body?: unknown; timeoutMs?: number } = {}
): LiveStream {
  const controller = new AbortController()
  const frames: SseFrame[] = []
  const timeoutMs = options.timeoutMs ?? 120_000
  let settle: (value: SseFrame[]) => void = () => undefined
  let fail: (error: Error) => void = () => undefined
  const done = new Promise<SseFrame[]>((res, rej) => {
    settle = res
    fail = rej
  })
  const timer = setTimeout(() => {
    controller.abort()
    fail(new Error(`SSE 订阅超时（${timeoutMs}ms）：${url}`))
  }, timeoutMs)

  void (async () => {
    try {
      const res = await fetch(url, {
        method: options.method ?? 'GET',
        headers: options.method === 'POST' ? { 'content-type': 'application/json' } : undefined,
        body: options.method === 'POST' ? JSON.stringify(options.body ?? {}) : undefined,
        signal: controller.signal
      })
      if (!res.body) {
        throw new Error(`无响应体：HTTP ${res.status}`)
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const chunk = await reader.read()
        if (chunk.done) break
        buffer += decoder.decode(chunk.value, { stream: true })
        let index = buffer.indexOf('\n')
        while (index >= 0) {
          const line = buffer.slice(0, index).replace(/\r$/, '')
          buffer = buffer.slice(index + 1)
          index = buffer.indexOf('\n')
          if (!line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (!payload.startsWith('{')) continue
          const frame = JSON.parse(payload) as SseFrame
          frames.push(frame)
          if (frame.type === 'done' || frame.type === 'error') {
            clearTimeout(timer)
            controller.abort()
            settle(frames)
            return
          }
        }
      }
      clearTimeout(timer)
      settle(frames)
    } catch (error) {
      clearTimeout(timer)
      if ((error as Error).name === 'AbortError') {
        settle(frames)
        return
      }
      fail(error as Error)
    }
  })()

  return {
    frames,
    done,
    close: () => {
      clearTimeout(timer)
      controller.abort()
    }
  }
}

/** 等某条流上出现满足条件的帧（不阻塞读取循环）。 */
export async function waitFrame(
  stream: LiveStream,
  predicate: (frame: SseFrame) => boolean,
  timeoutMs = 60_000,
  label = '目标帧'
): Promise<SseFrame> {
  const deadline = Date.now() + timeoutMs
  let seen = 0
  while (Date.now() < deadline) {
    for (; seen < stream.frames.length; seen += 1) {
      if (predicate(stream.frames[seen])) return stream.frames[seen]
    }
    await new Promise((done) => setTimeout(done, 50))
  }
  throw new Error(`等待超时：${label}（已收 ${stream.frames.length} 帧：${stream.frames.map((f) => f.type).join(',')}）`)
}

export function countType(frames: SseFrame[], type: string): number {
  return frames.filter((frame) => frame.type === type).length
}

/** 帧序号序列（心跳不占业务序号，故只取带 seq 的帧）。 */
export function seqs(frames: SseFrame[]): number[] {
  return frames
    .filter((frame) => typeof frame.seq === 'number')
    .map((frame) => Number(frame.seq))
}

export async function waitFor(
  check: () => Promise<boolean> | boolean,
  timeoutMs: number,
  label: string,
  intervalMs = 300
): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await check()) return
    await new Promise((done) => setTimeout(done, intervalMs))
  }
  throw new Error(`等待超时：${label}`)
}

export interface HealthBody {
  available?: boolean
  code?: string
  instanceId?: string
  redis?: { available?: boolean }
  resilience?: { maxAttempts?: number; firstByteTimeout?: string; interEventTimeout?: string }
  frontendToolTimeoutSeconds?: number
  leaseOk?: boolean
  resumeOnStartup?: boolean
}

export async function health(base = API): Promise<{ status: number; body: HealthBody }> {
  const res = await fetch(`${base}/api/ai/health`)
  const text = await res.text()
  return { status: res.status, body: JSON.parse(text) as HealthBody }
}

export interface RunDetail {
  exists?: boolean
  instanceId?: string
  snapshot?: { status?: string; attempts?: number; error?: string | null; runId?: string } & Record<string, unknown>
  pending?: Array<Record<string, unknown>>
  ledger?: Array<Record<string, unknown>>
  ledgerCounts?: Record<string, number>
  inFlight?: Array<Record<string, unknown>>
  keys?: Record<string, string | null>
}

export async function runDetail(runId: string, base = API): Promise<RunDetail> {
  const res = await fetch(`${base}/api/ai/runs/${encodeURIComponent(runId)}`)
  const json = (await res.json()) as { data?: RunDetail }
  return json.data ?? {}
}

export async function pendingOf(runId: string, toolCallId: string, base = API): Promise<Record<string, unknown> | undefined> {
  const detail = await runDetail(runId, base)
  return (detail.pending ?? []).find((entry) => entry.toolCallId === toolCallId)
}

/** 等某 pending 条目达到终态且满足条件（跨实例决策的收敛判据）。 */
export async function waitPending(
  runId: string,
  toolCallId: string,
  predicate: (entry: Record<string, unknown>) => boolean,
  timeoutMs: number,
  label: string,
  base = API
): Promise<Record<string, unknown>> {
  let last: Record<string, unknown> | undefined
  await waitFor(async () => {
    last = await pendingOf(runId, toolCallId, base)
    return last !== undefined && predicate(last)
  }, timeoutMs, label)
  return last as Record<string, unknown>
}

/** 等轮次收敛到终态（任一实例上读）。 */
export async function waitTerminal(runId: string, timeoutMs = 90_000, base = API): Promise<RunDetail> {
  const terminal = ['DONE', 'FAILED', 'CANCELLED', 'REJECTED']
  let detail: RunDetail = {}
  await waitFor(async () => {
    detail = await runDetail(runId, base)
    return terminal.includes(String(detail.snapshot?.status ?? ''))
  }, timeoutMs, `轮次 ${runId} 收敛到终态`)
  return detail
}

/** 写一个唯一的提示词后缀（桩「每路由一次」指纹：同路由不能被同一轮复用）。 */
export function tag(name: string): string {
  return `${name}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

/** 页面上「本轮已发出的 user 气泡数」（O 域防幻影的判据）。 */
export function userBubbles(page: import('@playwright/test').Page) {
  return page.locator('[data-testid^="msg-bubble-"]:not(.w-full)')
}

/** 断言页面只有一条 in-flight 轮的证据：activeRunId 与气泡数一致。 */
export async function activeRunId(page: import('@playwright/test').Page): Promise<string | null> {
  const id = await page.evaluate(() => {
    const raw = window.sessionStorage.getItem('ai-session-id') ?? ''
    return { sid: raw }
  })
  expect(id.sid).toBeTruthy()
  return null
}

/** 确认门/前端工具的决策（可直打任一实例 —— 这是 M-02 的跨实例面）。 */
export async function confirmOn(
  base: string,
  runId: string,
  toolCallId: string,
  approved: boolean,
  reason?: string
): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${base}/api/ai/confirm`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ runId, toolCallId, approved, reason: reason ?? null })
  })
  const text = await res.text()
  return { status: res.status, body: safeJson(text) }
}

export async function frontendResultOn(
  base: string,
  runId: string,
  toolCallId: string,
  result: string,
  extra: Record<string, unknown> = {}
): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${base}/api/ai/frontend-tool-result`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ runId, toolCallId, result, ...extra })
  })
  const text = await res.text()
  return { status: res.status, body: safeJson(text) }
}

export async function cancelOn(base: string, runId: string): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${base}/api/ai/cancel/${encodeURIComponent(runId)}`, { method: 'POST' })
  return { status: res.status, body: safeJson(await res.text()) }
}

function safeJson(text: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>
    return parsed
  } catch {
    return { __raw: text }
  }
}

/** `POST /api/ai/chat` 的错误体（非 SSE 分支：400/409/429/503 都是 JSON）。 */
export async function chatError(
  request: APIRequestContext,
  sessionId: string,
  message: string
): Promise<{ status: number; body: Record<string, unknown>; retryAfter: string | null }> {
  const res = await request.post(`${API}/api/ai/chat`, { data: { sessionId, message } })
  return {
    status: res.status(),
    body: safeJson(await res.text()),
    retryAfter: res.headers()['retry-after'] ?? null
  }
}
