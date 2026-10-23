/**
 * K 知识问答 / Q 快捷建议 / C 上下文 三域的私有助手（16-kqc*.spec.ts 专用）。
 *
 * 只读复用 `support/harness.ts` 的环境参数（RUN_DIR / PORTS / REDIS_DB），不改动任何既有文件。
 * 桩的请求记录（`<RUN_DIR>/stub/req-NNN.*`）是本域"工具真的被产品执行过"的唯一证据面：
 * 工具是 Spring AI `@Tool`，HTTP 上不存在直调入口，只有桩下发的 tool_call 能让后端真跑一遍。
 *
 * 纪律：等待一律走条件轮询（不 sleep 断言）；断言面 = 桩落档 / SSE 帧 / 端点载荷 / data-testid。
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import net from 'node:net'
import { join } from 'node:path'
import { PORTS, REDIS_DB, RUN_DIR } from './harness'

export const KQC_RUN_DIR = RUN_DIR
export const STUB_DIR = join(RUN_DIR, 'stub')
export const API = process.env.E2E_API_BASE ?? `http://127.0.0.1:${PORTS.backend}`
export const KQC_REDIS_DB = REDIS_DB

// ── HTTP（端点原文；本域的 /context 是裸对象，不能套 helpers 的 {success,data} 解包）─────────

export interface RawResponse {
  status: number
  text: string
  json: unknown
}

export async function httpRaw(method: 'GET' | 'POST' | 'PUT', path: string, body?: unknown): Promise<RawResponse> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(120_000)
  })
  const text = await res.text()
  let json: unknown = null
  try {
    json = JSON.parse(text)
  } catch {
    json = null
  }
  return { status: res.status, text, json }
}

/** 标准 `{success,data}` 端点取值（失败即抛，带响应原文）。 */
export async function apiData<T>(path: string): Promise<T> {
  const res = await httpRaw('GET', path)
  const body = res.json as { success?: boolean; data?: T } | null
  if (res.status !== 200 || body === null || body.success === false) {
    throw new Error(`GET ${path} => HTTP ${res.status} ${res.text.slice(0, 400)}`)
  }
  return body.data as T
}

export interface KbStatus {
  enabled: boolean
  ready: boolean
  source: string
  version: string
  doc_count: number
  chunk_count: number
  built_at: string | null
  last_refresh_at: string | null
  last_error: string | null
}

export function kbStatus(): Promise<KbStatus> {
  return apiData<KbStatus>('/api/ai/kb/status')
}

export interface ContextSnapshot {
  sessionId: string
  model: string | null
  turnNo: number
  measuredAtMs: number | null
  limitTokens: number | null
  usedTokens: number
  percent: number | null
  source: 'none' | 'stream' | 'estimate'
  estimated: boolean
  breakdown: Array<{ key: string; tokens: number }> | null
  compaction: {
    level: string
    circuit: string
    reason?: string
    lostTokens?: number
    compactedTurns?: number
    fuseFails?: number
  } | null
}

/** `GET /api/ai/context/{sid}`（裸对象；503 = Redis 不可用，调用方自行判定）。 */
export async function contextSnapshot(sessionId: string, options: { ledger?: boolean } = {}): Promise<ContextSnapshot> {
  const suffix = options.ledger === true ? '?ledger=1' : ''
  const res = await httpRaw('GET', `/api/ai/context/${encodeURIComponent(sessionId)}${suffix}`)
  if (res.status !== 200) {
    throw new Error(`GET /context => HTTP ${res.status} ${res.text.slice(0, 300)}`)
  }
  return res.json as ContextSnapshot
}

/** 压缩台账（`?ledger=1` 的 `ledger` 数组；失败事件也落在这里 ⇒ "不静默"的观测面）。 */
export async function contextLedger(sessionId: string): Promise<Array<Record<string, unknown>>> {
  const res = await httpRaw('GET', `/api/ai/context/${encodeURIComponent(sessionId)}?ledger=1`)
  if (res.status !== 200) {
    throw new Error(`GET /context?ledger=1 => HTTP ${res.status} ${res.text.slice(0, 300)}`)
  }
  const body = res.json as { ledger?: Array<Record<string, unknown>> }
  return Array.isArray(body.ledger) ? body.ledger : []
}

export interface HealthView {
  available: boolean
  contextLimitTokens: number | null
  compactionOn: boolean
  model: string
  /** 当前默认运行目标的身份（压缩分母与 /health 同一取数点：模型行）。 */
  providerId?: number | null
  defaultModelId?: string | null
}

export function aiHealth(): Promise<HealthView> {
  // /api/ai/health 是**裸对象**（不是标准 {success,data} 包）
  return httpRaw('GET', '/api/ai/health').then((res) => {
    if (res.status !== 200) throw new Error(`GET /api/ai/health => HTTP ${res.status}`)
    return res.json as HealthView
  })
}

// ── 模型配置写面（分母 = `ai_model.max_context`；L-93）────────────────────────────
//
// 为什么用例要走写接口而不是 `--app.ai.context.limit-tokens`：自 L-89 起上限的唯一真源是
// DB 行（`ContextLimitResolver`），过渡键只在"模型行没声明 max_context"时作启动期回退 ——
// 用 env 压分母**结构性无效**（这正是 L-93 的实测结论：两次窗口配置下 level 恒 none）。
// 故窗口由 `PUT /api/ai/providers/{id}/models/{modelId}` 改，改完用 `/health.contextLimitTokens`
// 确认快照已重载（`/health` 与压缩判定同一个 resolver）再开跑。

export interface ModelRowView {
  modelId: string
  displayName: string
  maxContext: number | null
  maxOutput: number | null
  enabled: boolean
  isDefault: boolean
}

export interface ProviderView {
  id: number
  code: string
  source: string
  enabled: boolean
  models: ModelRowView[]
}

export interface ModelWindow {
  providerId: number
  modelId: string
  maxContext: number | null
}

export function aiProviders(): Promise<ProviderView[]> {
  return apiData<ProviderView[]>('/api/ai/providers')
}

/** 当前默认运行目标的模型行（= 压缩分母的那一行）。 */
export async function currentModelWindow(): Promise<ModelWindow> {
  const health = await aiHealth()
  const providerId = health.providerId ?? null
  const modelId = health.defaultModelId ?? null
  if (providerId === null || modelId === null) {
    throw new Error(`/health 未给出默认运行目标（providerId=${providerId} / modelId=${modelId}）`)
  }
  const provider = (await aiProviders()).find((row) => row.id === providerId)
  const model = provider?.models.find((row) => row.modelId === modelId)
  return { providerId, modelId, maxContext: model?.maxContext ?? null }
}

/** 改一行模型的 `max_context`（其余字段不动；写路径 bump 版本号 ⇒ 快照重载）。 */
export async function writeModelMaxContext(window: ModelWindow, maxContext: number): Promise<void> {
  const res = await httpRaw(
    'PUT',
    `/api/ai/providers/${window.providerId}/models/${encodeURIComponent(window.modelId)}`,
    { maxContext }
  )
  const body = res.json as { success?: boolean; code?: string; message?: string } | null
  if (res.status !== 200 || body?.success !== true) {
    throw new Error(
      `PUT /api/ai/providers/${window.providerId}/models/${window.modelId} maxContext=${maxContext} => ` +
        `HTTP ${res.status} ${res.text.slice(0, 300)}`
    )
  }
}

/** 等 `/health.contextLimitTokens` 变成期望值（= 快照里那一行真的生效了）。 */
export async function waitContextLimitTokens(expected: number, timeoutMs = 20_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  let last: number | null = null
  while (Date.now() < deadline) {
    last = await aiHealth()
      .then((health) => health.contextLimitTokens)
      .catch(() => null)
    if (last === expected) return
    await sleep(200)
  }
  throw new Error(`上下文分母未在 ${timeoutMs}ms 内变为 ${expected}（当前 = ${String(last)}）`)
}

/**
 * 用「小 max_context 的模型行」跑一段，结束后无论如何恢复原值。
 *
 * 取值纪律（P3 实测结论）：`max_context` 必须显著大于 `reserve-tokens`（默认 13000），
 * 否则 `limit − reserve` 被钳到 1，任何轮次都判满、每轮直冲 L3。本域用 17500 / 19000 两档
 * （见 spec 常量），两档都留 ≥ 4500 的分母余量。
 */
export async function withModelWindow<T>(maxContext: number, body: (window: ModelWindow) => Promise<T>): Promise<T> {
  const before = await currentModelWindow()
  await writeModelMaxContext(before, maxContext)
  await waitContextLimitTokens(maxContext)
  try {
    return await body({ ...before, maxContext })
  } finally {
    const restore = before.maxContext ?? 0
    if (restore > 0) {
      await writeModelMaxContext(before, restore).catch(() => undefined)
      await waitContextLimitTokens(restore).catch(() => undefined)
    }
  }
}

export function toolNames(page?: string): Promise<string[]> {
  const query = page === undefined ? '' : `?page=${encodeURIComponent(page)}`
  return apiData<{ toolNames: string[] }>(`/api/ai/tools${query}`).then((data) => data.toolNames)
}

// ── SSE 整轮（API 层直打 /chat）────────────────────────────────────────────────

export interface Frame {
  type: string
  seq?: number
  runId?: string
  [key: string]: unknown
}

export function parseFrames(text: string): Frame[] {
  const frames: Frame[] = []
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith('data:')) continue
    const payload = line.slice(5).trim()
    if (!payload.startsWith('{')) continue
    try {
      frames.push(JSON.parse(payload) as Frame)
    } catch {
      /* [DONE] 等非 JSON 行 */
    }
  }
  return frames
}

export interface RoundResult {
  sessionId: string
  status: number
  frames: Frame[]
  done: Frame | undefined
  errors: Frame[]
  text: string
}

/** 直打一轮 `POST /api/ai/chat`，等流收完再解析（含 done / error 帧）。 */
export async function chatRound(sessionId: string, message: string, timeoutMs = 120_000): Promise<RoundResult> {
  const res = await fetch(`${API}/api/ai/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
    body: JSON.stringify({ sessionId, message }),
    signal: AbortSignal.timeout(timeoutMs)
  })
  const text = await res.text()
  const frames = parseFrames(text)
  return {
    sessionId,
    status: res.status,
    frames,
    done: frames.find((frame) => frame.type === 'done'),
    errors: frames.filter((frame) => frame.type === 'error'),
    text
  }
}

export function deltaTextOf(round: RoundResult): string {
  return round.frames.filter((frame) => frame.type === 'delta').map((frame) => String(frame.text ?? '')).join('')
}

/**
 * **正文**增量（不含思考段）。
 *
 * L-91 后 `delta` 帧多了一个可选键 `kind`：`kind='reasoning'` = 思考段增量，缺省 = 正文。
 * 断言"用户看到什么"（答复内容 / 不吐调试串 / 引用口径）必须只看正文 —— 思考段里模型
 * 正当地在**候选与排除**之间推演（例如"要不要再看一眼 glm-seed？无关，跳过"），
 * 把思考段混进答复文本会把这种正常推理判成"编造"。
 */
export function answerTextOf(round: RoundResult): string {
  return round.frames
    .filter((frame) => frame.type === 'delta' && frame.kind !== 'reasoning')
    .map((frame) => String(frame.text ?? ''))
    .join('')
}

// ── 桩请求记录（披露清单 / 工具调用判定 / 工具结果回灌的唯一真值面）────────────────

export interface StubMessage {
  role: string
  content: unknown
  [key: string]: unknown
}

export interface StubRequest {
  index: number
  tag: string
  tools: string[]
  decision: Record<string, unknown> | null
  messages: StubMessage[]
  raw: Record<string, unknown>
}

interface ReqIndexEntry {
  index: number
  tag: string
}

function listRequestIndexes(): ReqIndexEntry[] {
  if (!existsSync(STUB_DIR)) return []
  const entries: ReqIndexEntry[] = []
  for (const name of readdirSync(STUB_DIR)) {
    const match = /^req-(\d+)\.json$/.exec(name)
    if (match) entries.push({ index: Number(match[1]), tag: match[1] })
  }
  return entries.sort((a, b) => a.index - b.index)
}

/** 当前最大请求序号（0 = 还没有任何上游请求）。 */
export function maxStubIndex(): number {
  const list = listRequestIndexes()
  return list.length === 0 ? 0 : list[list.length - 1].index
}

/** 读一条请求落档；缺文件返回 null（桩此刻还没写完）。 */
export function stubRequest(index: number): StubRequest | null {
  const tag = String(index).padStart(3, '0')
  const jsonPath = join(STUB_DIR, `req-${tag}.json`)
  if (!existsSync(jsonPath)) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(readFileSync(jsonPath, 'utf8')) as Record<string, unknown>
  } catch {
    return null
  }
  const toolsPath = join(STUB_DIR, `req-${tag}.tools.txt`)
  // 首行是 `disclosedCount=N` 台账行，工具名从第二行起。
  const tools = existsSync(toolsPath)
    ? readFileSync(toolsPath, 'utf8')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line !== '' && !line.startsWith('disclosedCount='))
    : []
  const decisionPath = join(STUB_DIR, `req-${tag}.decision.json`)
  let decision: Record<string, unknown> | null = null
  if (existsSync(decisionPath)) {
    try {
      decision = JSON.parse(readFileSync(decisionPath, 'utf8')) as Record<string, unknown>
    } catch {
      decision = null
    }
  }
  return {
    index,
    tag,
    tools,
    decision,
    messages: Array.isArray(raw.messages) ? (raw.messages as StubMessage[]) : [],
    raw
  }
}

/** 等 `before` 之后至少 `count` 条新请求落档（工具轮 = 调用帧请求 + 带 tool 结果的续轮）。 */
export async function waitStubRequestsAfter(
  before: number,
  count: number,
  timeoutMs = 90_000
): Promise<StubRequest[]> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const fresh = listRequestIndexes().filter((entry) => entry.index > before)
    if (fresh.length >= count) {
      const records = fresh.map((entry) => stubRequest(entry.index)).filter((item): item is StubRequest => item !== null)
      if (records.length >= count) return records
    }
    await sleep(150)
  }
  throw new Error(`等待桩请求落档超时（在 #${before} 之后应有 ${count} 条）`)
}

export function systemTextOf(request: StubRequest): string {
  return request.messages.filter((m) => m.role === 'system').map((m) => String(m.content ?? '')).join('\n')
}

/** 上下文里所有 `role=tool` 消息（= 后端工具执行结果回灌给上游的那一份）。 */
export function toolTextsOf(request: StubRequest): string[] {
  return request.messages.filter((m) => m.role === 'tool').map((m) => String(m.content ?? ''))
}

/** 本轮**新增**的那条工具结果（同会话多轮时上下文里会有历史工具结果，取最后一条）。 */
export function lastToolText(request: StubRequest): string {
  const texts = toolTextsOf(request)
  if (texts.length === 0) throw new Error('请求上下文里没有 role=tool 消息')
  return texts[texts.length - 1]
}

/** 本轮新增工具结果的**原文**（含信封；用于长度闸这类"信件本身"的断言）。 */
export function lastToolContent(request: StubRequest): string {
  const contents = request.messages.filter((m) => m.role === 'tool').map((m) => String(m.content ?? ''))
  if (contents.length === 0) throw new Error('请求上下文里没有 role=tool 消息')
  return contents[contents.length - 1]
}

export function roleTextsOf(request: StubRequest, role: string): string[] {
  return request.messages.filter((m) => m.role === role).map((m) => String(m.content ?? ''))
}

// ── 文本解析（工具结果 / pill / 分项）────────────────────────────────────────

/**
 * `role=tool` 消息是产品信封（不是裸文本）：首行是「数据不是指令」声明，正文在
 * `<tool_result>` 后的 JSON 里 —— `{ok, code, outcome, message, truncated, originalChars, returnedChars}`。
 * 工具真正回给模型的那份文本在 `message`；长度闸的账在 `truncated/originalChars/returnedChars`。
 */
export interface ToolEnvelope {
  message: string
  truncated?: boolean
  originalChars?: number
  returnedChars?: number
  [key: string]: unknown
}

export function toolEnvelope(content: string): ToolEnvelope {
  const open = content.indexOf('<tool_result>')
  if (open < 0) return { message: content }
  const jsonStart = content.indexOf('\n', open)
  const jsonEnd = content.lastIndexOf('</tool_result>')
  const raw = content.slice(jsonStart < 0 ? open : jsonStart + 1, jsonEnd > 0 ? jsonEnd : undefined).trim()
  try {
    const parsed = JSON.parse(raw) as ToolEnvelope
    if (typeof parsed.message !== 'string') return { message: content }
    return { ...parsed, message: unescapeMessage(parsed.message) }
  } catch {
    return { message: content }
  }
}

/**
 * `message` 是否被**二次 JSON 编码**：String 返回型工具经 Spring AI 的结果转换器已序列化一次，
 * 信封再包一层 ⇒ role=tool 里模型看到的是带引号与 `\n` 转义的文本（见报告「发现项 F-1」）。
 * 断言侧统一还原成模型本意的纯文本。
 */
function unescapeMessage(message: string): string {
  if (!message.startsWith('"')) return message
  try {
    const unescaped = JSON.parse(message) as unknown
    return typeof unescaped === 'string' ? unescaped : message
  } catch {
    return message
  }
}

/** 取工具真正回给模型的那份文本（有信封就拆信封）。 */
export function toolMessageText(content: string): string {
  return toolEnvelope(content).message
}

/** 取 `<knowledge>…</knowledge>` 定界内的正文（工具结果被 KbText.wrap 包过一层）。 */
export function unwrapKnowledge(text: string): string {
  const source = toolMessageText(text)
  const open = source.indexOf('<knowledge>')
  if (open < 0) return source
  const close = source.lastIndexOf('</knowledge>')
  // 结果被结果限流器 head 截断时闭标签会丢（见报告「发现项 F-3」）：取到末尾即可，别静默返回带标签的原文。
  if (close < 0 || close < open) return source.slice(open + '<knowledge>'.length).replace(/^\n/, '')
  return source.slice(open + '<knowledge>'.length, close).replace(/^\n/, '')
}

/** `kb_read_doc` 回执的正文段（`--- 正文 ---` 与 `[truncated=…]` 之间）。 */
export function readDocBody(toolText: string): string {
  const inner = unwrapKnowledge(toolText)
  const start = inner.indexOf('--- 正文 ---\n')
  if (start < 0) throw new Error(`kb_read_doc 结果缺正文段：${inner.slice(0, 200)}`)
  const rest = inner.slice(start + '--- 正文 ---\n'.length)
  const end = rest.lastIndexOf('\n[truncated=')
  return end < 0 ? rest : rest.slice(0, end)
}

/** `kb_read_doc` 回执的 truncated/next_offset 尾巴（`[truncated=true next_offset=1200]`）。 */
export function readDocTail(toolText: string): { truncated: boolean; nextOffset: number } {
  const inner = unwrapKnowledge(toolText)
  const match = /\[truncated=(true|false)(?: next_offset=(-?\d+))?[^\]]*\]\s*$/.exec(inner.trim())
  if (!match) throw new Error(`kb_read_doc 结果缺 truncated 尾巴：${inner.slice(-160)}`)
  return { truncated: match[1] === 'true', nextOffset: match[2] === undefined ? -1 : Number(match[2]) }
}

/** `12.3k` / `842` / `128k` → 数字（与展示层同口径，独立实现，不复用产品代码）。 */
export function tokensFromLabel(label: string): number {
  const text = label.trim()
  const match = /^(\d+(?:\.\d+)?)(k|M)?$/.exec(text)
  if (!match) throw new Error(`无法解析 token 文案：${label}`)
  const value = Number(match[1])
  if (match[2] === 'k') return Math.round(value * 1000)
  if (match[2] === 'M') return Math.round(value * 1_000_000)
  return value
}

/** pill 文案三种形态：`66% · 84k/128k` / `≈已用 84k` / 空串。 */
export interface PillView {
  raw: string
  estimate: boolean
  percent: number | null
  used: number | null
  limit: number | null
}

export function parsePillText(raw: string): PillView {
  const text = raw.trim()
  const estimate = text.startsWith('≈')
  const body = estimate ? text.slice(1) : text
  const full = /^(\d+(?:\.\d+)?)% · ([0-9.]+[kM]?)\/([0-9.]+[kM]?)$/.exec(body)
  if (full) {
    return {
      raw: text,
      estimate,
      percent: Number(full[1]),
      used: tokensFromLabel(full[2]),
      limit: tokensFromLabel(full[3])
    }
  }
  const usedOnly = /^已用 ([0-9.]+[kM]?)$/.exec(body)
  if (usedOnly) {
    return { raw: text, estimate, percent: null, used: tokensFromLabel(usedOnly[1]), limit: null }
  }
  return { raw: text, estimate, percent: null, used: null, limit: null }
}

export async function sleep(ms: number): Promise<void> {
  await new Promise((done) => setTimeout(done, ms))
}

/** 唯一指纹后缀（桩「每路由一次」+ 证据可归因：同族用例互不串味）。 */
export function fingerprint(caseId: string): string {
  return `${caseId.toLowerCase()}-${Date.now().toString(36)}`
}

// ── Redis（本域 DB；harness 的 redisCommand 在 GET 上只等首行，这里等整包）────────────

/**
 * 发一条命令并读**完整**回包（空闲 80ms 视为收完；不引第三方依赖）。
 * 返回 `{ type, value }`：`:1` → `{type:'int', value:'1'}`；`$5\r\ndata\r\n` → `{type:'bulk', value:'data'}`。
 */
export function redisExec(args: string[], db = KQC_REDIS_DB): Promise<{ type: string; value: string }> {
  const host = process.env.E2E_REDIS_HOST ?? '127.0.0.1'
  const port = Number(process.env.E2E_REDIS_PORT ?? '6379')
  return new Promise((done, fail) => {
    const socket = net.connect(port, host)
    let buffer = ''
    let selected = false
    let idle: NodeJS.Timeout | null = null
    let settled = false
    const finish = (): void => {
      if (settled) return
      settled = true
      if (idle) clearTimeout(idle)
      socket.destroy()
      const text = buffer.replace(/\r\n$/, '')
      if (text.startsWith(':')) return done({ type: 'int', value: text.slice(1) })
      if (text.startsWith('$')) {
        const split = text.indexOf('\r\n')
        const payload = split >= 0 ? text.slice(split + 2) : ''
        return done({ type: 'bulk', value: payload.replace(/\r\n$/, '') })
      }
      if (text.startsWith('+')) return done({ type: 'status', value: text.slice(1) })
      done({ type: 'raw', value: text })
    }
    const failOnce = (error: Error): void => {
      if (settled) return
      settled = true
      if (idle) clearTimeout(idle)
      socket.destroy()
      fail(error)
    }
    socket.setTimeout(5000, () => failOnce(new Error('redis 超时')))
    socket.on('error', failOnce)
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      if (idle) clearTimeout(idle)
      idle = setTimeout(finish, 80)
      if (!selected && buffer.includes('\r\n')) {
        selected = true
        buffer = ''
        const bulk = `*${args.length}\r\n${args.map((part) => `$${Buffer.byteLength(part)}\r\n${part}\r\n`).join('')}`
        if (idle) clearTimeout(idle)
        socket.write(bulk)
      }
    })
    socket.on('connect', () => {
      socket.write(`*2\r\n$6\r\nSELECT\r\n$${String(db).length}\r\n${db}\r\n`)
    })
  })
}

/** 新会话 id（每个用例独立会话，避免跨用例记忆污染）。 */
export function newSessionId(caseId: string): string {
  return `kqc-${caseId.toLowerCase()}-${Date.now().toString(36)}`
}

/** `KEYS <pattern>`（本域独占 DB，量小无需 SCAN 分页）。 */
export function redisKeys(pattern: string, db = KQC_REDIS_DB): Promise<string[]> {
  const host = process.env.E2E_REDIS_HOST ?? '127.0.0.1'
  const port = Number(process.env.E2E_REDIS_PORT ?? '6379')
  return new Promise((done, fail) => {
    const socket = net.connect(port, host)
    let buffer = ''
    let selected = false
    let settled = false
    const timer = setTimeout(() => failOnce(new Error('redis KEYS 超时')), 5000)
    const failOnce = (error: Error): void => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      socket.destroy()
      fail(error)
    }
    const parse = (text: string): string[] => {
      const values: string[] = []
      const body = text.replace(/^\*\d+\r\n/, '')
      for (const part of body.split(/\r\n\$(?=\d)/)) {
        const chunk = part.replace(/^\d+\r\n/, '')
        if (chunk !== '') values.push(chunk)
      }
      return values
    }
    socket.setTimeout(5000, () => failOnce(new Error('redis KEYS 超时')))
    socket.on('error', failOnce)
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      if (settled) return
      if (!selected && buffer.includes('\r\n')) {
        selected = true
        buffer = ''
        const args = ['KEYS', pattern]
        const bulk = `*${args.length}\r\n${args.map((part) => `$${Buffer.byteLength(part)}\r\n${part}\r\n`).join('')}`
        socket.write(bulk)
        return
      }
      if (selected && (buffer.startsWith('*') || buffer.startsWith('-'))) {
        const lines = buffer.split('\r\n').length
        // `*N` 之后应有 N 条 `$len` + 内容 ⇒ 行数 ≥ 2N+1 才算收全
        const count = Number(/^\*(\d+)/.exec(buffer)?.[1] ?? '-1')
        if (count >= 0 && lines >= 2 * count + 2) {
          settled = true
          clearTimeout(timer)
          socket.destroy()
          done(parse(buffer))
        }
      }
    })
    socket.on('connect', () => {
      socket.write(`*2\r\n$6\r\nSELECT\r\n$${String(db).length}\r\n${db}\r\n`)
    })
  })
}

/** `TTL <key>`（秒；-1 = 无过期，-2 = 键不存在）。 */
export async function redisTtl(key: string, db = KQC_REDIS_DB): Promise<number> {
  const reply = await redisExec(['TTL', key], db)
  return Number(reply.value)
}

/** 读仓内的 usage 形状表（唯一取数点，避免用例内写死数字）。 */
export function usageShapes(): Record<string, { promptTokens: number; completionTokens: number; totalTokens: number }> {
  const path = join(KQC_RUN_DIR, '..', '..', 'scripts', 'ci', 'stub-routes.json')
  const candidates = [
    join(process.cwd(), '..', 'scripts', 'ci', 'stub-routes.json'),
    join(process.cwd(), 'scripts', 'ci', 'stub-routes.json'),
    path
  ]
  for (const candidate of candidates) {
    if (!existsSync(candidate)) continue
    const parsed = JSON.parse(readFileSync(candidate, 'utf8')) as {
      usageShapes?: Record<string, { promptTokens?: number; completionTokens?: number; totalTokens?: number }>
    }
    const shapes = parsed.usageShapes ?? {}
    const out: Record<string, { promptTokens: number; completionTokens: number; totalTokens: number }> = {}
    for (const [name, value] of Object.entries(shapes)) {
      if (typeof value.promptTokens !== 'number') continue
      out[name] = {
        promptTokens: value.promptTokens,
        completionTokens: value.completionTokens ?? 0,
        totalTokens: value.totalTokens ?? 0
      }
    }
    return out
  }
  throw new Error('找不到 scripts/ci/stub-routes.json（usageShapes 唯一取数点）')
}
