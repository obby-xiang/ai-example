/**
 * S 域（流式）/ R 域（真刷新）/ P 域（手工停止）专用助手 —— 10-srp.spec.ts 私有。
 *
 * 端点/帧口径见 backend `ai/web/AiController.java` 与 `frontend/src/types/sse.ts`。
 */
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { api } from './helpers'
import { RUN_DIR } from './harness'

export const API_BASE = process.env.E2E_API_BASE
  ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`

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

/** 直打 POST /api/ai/chat（API 层），返回整轮帧序列。 */
export async function chatFrames(
  sessionId: string,
  message: string,
  timeoutMs = 120_000
): Promise<SseFrame[]> {
  const res = await fetch(`${API_BASE}/api/ai/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sessionId, message }),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const text = await res.text()
  return parseSse(text)
}

/** 桩侧一次上游请求的落档（`<RUN_DIR>/stub/req-NNN.*`）。 */
export interface StubRequest {
  /** 桩进程内的落档序号（单调递增 ⇒ 到达顺序）。 */
  index: number
  /** 落档写入时刻（epoch ms，桩进程/文件系统的钟，与后端帧的 `atMs` 相互独立）。 */
  atMs: number
  /** 桩对该请求的应答状态（`decision.json` 无 `status` = 正常 200 流）。 */
  status: number
}

/**
 * 按请求体里的**一次性标记**找桩落档 —— 这是"上游真的收到了几次请求、隔了多久"的唯一证据面
 * （后端 `retry`/`delta` 帧的 `atMs` 是产品自报的钟，不能单独用来证明"真的等过"）。
 *
 * 落档在请求处理入口写（`record()`），故 `mtime` = 该请求到达桩的时刻；
 * 两次落档之差 ≥ 两次请求之间产品真实等待的时长。
 */
export function stubRequestsContaining(marker: string): StubRequest[] {
  const dir = join(RUN_DIR, 'stub')
  if (!existsSync(dir)) return []
  const found: StubRequest[] = []
  for (const name of readdirSync(dir)) {
    const match = /^req-(\d+)\.json$/.exec(name)
    if (!match) continue
    const [, tag] = match
    const raw = readFileSync(join(dir, name), 'utf8')
    if (!raw.includes(marker)) continue
    let status = 200
    const decisionPath = join(dir, `req-${tag}.decision.json`)
    if (existsSync(decisionPath)) {
      const decision = JSON.parse(readFileSync(decisionPath, 'utf8')) as { status?: number }
      if (typeof decision.status === 'number') status = decision.status
    }
    found.push({ index: Number(tag), atMs: statSync(join(dir, name)).mtimeMs, status })
  }
  return found.sort((a, b) => a.index - b.index)
}

/** 打开 GET /api/ai/events/{runId}?lastSeq= 直读文本（回放/续收通道）。 */
export async function eventsRaw(runId: string, lastSeq?: number): Promise<string> {
  const suffix = lastSeq === undefined ? '' : `?lastSeq=${Math.max(0, Math.floor(lastSeq))}`
  const res = await fetch(`${API_BASE}/api/ai/events/${encodeURIComponent(runId)}${suffix}`)
  return res.text()
}

export function eventsUrl(runId: string, lastSeq?: number): string {
  const suffix = lastSeq === undefined ? '' : `?lastSeq=${Math.max(0, Math.floor(lastSeq))}`
  return `${API_BASE}/api/ai/events/${encodeURIComponent(runId)}${suffix}`
}

export function deltaText(frames: SseFrame[]): string {
  return frames.filter((f) => f.type === 'delta').map((f) => String(f.text ?? '')).join('')
}

export function countType(frames: SseFrame[], type: string): number {
  return frames.filter((f) => f.type === type).length
}

/**
 * 段序（段 = 连续的同类内容）：工具段按 `toolCallId`，文本段按 `message_start`。
 * 同一 `toolCallId` 只允许出现一次（不被拆两段）。
 */
export function segmentOrder(frames: SseFrame[]): string[] {
  const out: string[] = []
  for (const frame of frames) {
    if (frame.type === 'tool_start' && typeof frame.toolCallId === 'string') {
      out.push(`tool:${frame.toolCallId}`)
    } else if (frame.type === 'message_start' && frame.kind === 'text') {
      out.push('text')
    }
  }
  return out
}

interface HistoryMessage {
  type?: string
  role?: string
  text?: string
  toolCalls?: Array<{ id?: string }>
}

/** 历史侧段序：助手消息的 toolCalls 与正文按落库顺序展开。 */
export function historySegments(history: { messages?: HistoryMessage[] }): string[] {
  const out: string[] = []
  for (const message of history.messages ?? []) {
    const role = (message.type ?? message.role ?? '').toUpperCase()
    if (role !== 'ASSISTANT') continue
    for (const call of message.toolCalls ?? []) {
      if (call.id) out.push(`tool:${call.id}`)
    }
    if ((message.text ?? '').trim().length > 0) out.push('text')
  }
  return out
}

/**
 * 监听页面自身的 POST /api/ai/chat 响应体（真流式响应；前端读完才 resolve）。
 * 用例据此拿到与页面所见**同一份**帧序列，做三方对账。
 */
export function captureChat(page: Page): { body: () => Promise<string> } {
  let resolveBody: (value: string) => void = () => undefined
  const body = new Promise<string>((done) => {
    resolveBody = done
  })
  page.on('response', (response) => {
    if (!response.url().includes('/api/ai/chat')) return
    void response.text().then((text) => resolveBody(text)).catch(() => resolveBody(''))
  })
  return { body: () => body }
}

/** 本轮助手消息 id（`msg-bubble-<id>` 里的 `<id>`；只取助手气泡）。 */
export async function assistantMessageId(page: Page): Promise<string> {
  const id = await page
    .locator('[data-testid^="msg-bubble-"].w-full')
    .last()
    .getAttribute('data-testid')
  expect(id).toBeTruthy()
  return String(id).replace('msg-bubble-', '')
}

export interface RunDetail {
  exists?: boolean
  snapshot?: { status?: string; runId?: string; [key: string]: unknown }
}

export async function runDetail(request: APIRequestContext, runId: string): Promise<RunDetail> {
  return api.get<RunDetail>(request, `/api/ai/runs/${encodeURIComponent(runId)}`)
}

/** 归档帧序列（按 seq 升序，去掉心跳）。 */
export async function archivedFrames(runId: string, lastSeq = 0): Promise<SseFrame[]> {
  return parseSse(await eventsRaw(runId, lastSeq)).filter((f) => f.type !== 'heartbeat')
}

/** 会话镜像（sessionStorage `ai-mirror:<sid>`，节流落盘）。 */
export interface MirrorPayload {
  v?: number
  inFlight?: boolean
  lastRunId?: string | null
  runSeq?: Record<string, number>
  messages?: Array<{ id?: string; role?: string; content?: string; stopped?: boolean; done?: boolean; parts?: unknown[] }>
}

export async function readMirror(page: Page, sid: string): Promise<MirrorPayload | null> {
  const raw = await page.evaluate(
    (key) => window.sessionStorage.getItem(key),
    `ai-mirror:${sid}`
  )
  if (!raw) return null
  return JSON.parse(raw) as MirrorPayload
}

/** sessionStorage 里属于本会话的键（镜像 + 会话 id；用于「只有 1 个会话键」的判定）。 */
export async function sessionStorageKeys(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const keys: string[] = []
    for (let i = 0; i < window.sessionStorage.length; i += 1) {
      const key = window.sessionStorage.key(i)
      if (key) keys.push(key)
    }
    return keys
  })
}

/** ISO-8601 时长（`PT12S` / `PT1M30S` / `PT5M`）→ 毫秒（`/health` 的 resilience 值就是这种形态）。 */
export function isoDurationMs(value: string): number {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value.trim())
  if (!match) return Number.NaN
  const hours = Number(match[1] ?? 0)
  const minutes = Number(match[2] ?? 0)
  const seconds = Number(match[3] ?? 0)
  return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000)
}
