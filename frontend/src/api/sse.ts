/**
 * SSE 通道（规格 §2「SSE 进度通道」/ §3 帧契约）。
 *
 * 两个通道形态不同，必须分开实现：
 * 1. `POST /api/ai/chat`：**POST + text/event-stream**，EventSource 只支持 GET，
 *    因此这里用 fetch + ReadableStream 自解 SSE 帧；
 * 2. `GET /api/ai/events/{runId}`：**GET**，是 ADR-5 的 reattach 通道，用 EventSource。
 *
 * 完成帧校验（规格 §3）：流正常收尾前必须出现过 `done` 或 `error` 终帧；
 * 缺终帧一律判**断流**，先尝试 reattach（有 runId 时），仍失败则按 ADR-6 提示。
 *
 * 帧解析口径：后端用无名 SSE 事件，data 是 JSON 对象且带 `type` 字段
 * （SseChatEmitter#emit），所以两种通道共用同一个帧解析器。
 */

import { ApiError, FRONTEND_ERROR_CODES } from './http'
import { acceptLanguage } from '@/locales'
import { frameSeq, isTerminalFrame, toSseFrame } from '@/types/sse'
import type { SseDoneFrame, SseErrorFrame, SseFrame } from '@/types/sse'
import type { AiChatRequest, StreamCloseInfo } from '@/types/ai'

/**
 * 帧间静默上限：超过即判断流（后端挂起期有 heartbeat，正常不会触发）。
 *
 * 45s（超时面治理批② 第 4 项；决策看板 `:16`）：原 90s **落在代理死区里** ——
 * nginx / ALB 一类反向代理的默认空闲超时是 60s，它们会先于前端把连接静默掐掉，
 * 于是前端看到的永远是「连接中断」而不是「静默超时」，判定链与提示文案都对不上号。
 * 45s < 60s（代理）< 90s（后端 `app.ai.resilience.inter-event-timeout`，**本批不动**）：
 * 这是**刻意的错层** —— 前端先失败、先复位 loading、先出提示，后端那层继续按机器口径判。
 */
export const DEFAULT_IDLE_TIMEOUT_MS = 45_000

/**
 * 断流后自动重挂的最大次数（超时面治理批② 第 5 项；决策看板 `:16` = 4 次 / 1·2·4·8s + jitter）。
 *
 * 原 2 次（1s/2s）在一次代理重建 + 后端跨实例续跑（数秒级）的现实里偏少：
 * 两次退避合计 3s，常常在"续跑还没就绪"时就把重挂额度用完。
 */
export const DEFAULT_MAX_RECONNECTS = 4

/** 首次重连等待（后续指数退避 1/2/4/8s，叠加 ±25% 抖动）。 */
export const DEFAULT_RECONNECT_DELAY_MS = 1_000

/** 退避抖动幅度（±25%）：避免"整批客户端在同一毫秒重连"的雷群。 */
export const RECONNECT_JITTER_RATIO = 0.25

/**
 * 第 `attempt` 次重连的等待（毫秒）= 基数 × 2^(attempt-1)，再乘 [0.75, 1.25] 的抖动。
 *
 * `random` 可注入（默认 `Math.random`）：抖动是"防雷群"的手段，但它会让断言失去确定性 ——
 * 测试注入固定值即可同时锁住基数与抖动边界（见 `tests/sse-watchdog.spec.ts`）。
 */
export function reconnectDelayMs(attempt: number, random: () => number = Math.random): number {
  const base = DEFAULT_RECONNECT_DELAY_MS * 2 ** (Math.max(1, attempt) - 1)
  const factor = 1 - RECONNECT_JITTER_RATIO + random() * RECONNECT_JITTER_RATIO * 2
  return Math.round(base * factor)
}

/**
 * reattach **建连**（`onopen` 之前）的看门狗上限（R-03 / 前端篇 B-03）。
 *
 * 为什么不能复用 `DEFAULT_IDLE_TIMEOUT_MS`：帧间静默是"连上了但没数据"，建连超时是"根本没连上"
 * —— 后者判死的证据更强（reattach 是 GET + SseEmitter，响应头应立即 commit，且后端有心跳），
 * 预算理应显著更短。取值论证（对齐既有常量）：
 * - 落在审计建议的 10~15s（`blocking-audit/frontend.md` §S1-2 修法方向）**上界**，把"活但慢的
 *   连接被误判为死"的假阳性压到最低（一次 TCP/TLS 握手 + 代理首包 + 服务端排队都要放得下）；
 * - 建连预算按**每条连接**计（15s），不与重挂次数相乘后对比帧间静默：重挂 4 次时整段
 *   reattach 的最坏耗时 = 5×15 + (1+2+4+8) = 90s（抖动再乘 ≤1.25）—— 这是"断流后的恢复窗口"，
 *   与 `DEFAULT_IDLE_TIMEOUT_MS`（45s，单条流的无进展判据）是**两个维度**，不构成矛盾；
 * - 远小于 `api/http.ts` 的 axios 单请求上限（60s）与后端前端工具挂起上限（120s；确认门去超时后
 *   已无该键）⇒ 前端先失败、先复位 `loading`、先出提示，不再沉默到后端超时才收尾。
 */
export const DEFAULT_CONNECT_TIMEOUT_MS = 15_000

export const SSE_PATHS = {
  chat: '/api/ai/chat',
  /**
   * reattach 通道（DC-14 T6）：带 `lastSeq` 时服务端只补发 `seq > lastSeq` 的帧
   * （孤儿过滤）—— 长轮次的重挂代价从"整轮全部帧"降到"差量"。
   */
  events: (runId: string, lastSeq?: number): string =>
    lastSeq === undefined
      ? `/api/ai/events/${encodeURIComponent(runId)}`
      : `/api/ai/events/${encodeURIComponent(runId)}?lastSeq=${Math.max(0, Math.floor(lastSeq))}`
} as const

/** 帧回调集合。 */
export interface SseFrameHandlers {
  /** 每收到一帧调用一次（含心跳） */
  onFrame?: (frame: SseFrame) => void
  /** 连接建立（含 reattach 重连成功） */
  onOpen?: () => void
  /** 收到终帧 done/error */
  onTerminal?: (frame: SseDoneFrame | SseErrorFrame) => void
  /** 流关闭（terminal=false 表示断流） */
  onClose?: (info: StreamCloseInfo) => void
  /** 重连前的公告（前端可展示"连接中断，N 秒后重试"） */
  onReconnect?: (attempt: number, delayMs: number) => void
  /** 连接层面的错误（HTTP 非 2xx、网络异常） */
  onError?: (error: ApiError) => void
}

/** 流选项。 */
export interface SseStreamOptions {
  /** 外部取消（用户点"停止"）：abort 后不再重连 */
  signal?: AbortSignal
  /**
   * 退避抖动源（默认 `Math.random`）：只给测试注入固定值用 —— 生产不要传。
   * 传了也不会改变退避的"基数 × 2^n ± 25%"口径，只改变抽到的那个点。
   */
  random?: () => number
  /**
   * reattach 专用（DC-14 T6）：前端已收到的最大帧序号 —— 服务端据此只补发增量。
   * 不传 = 全量回放（与 S4.2 时期行为一致）。
   */
  lastSeq?: number
  /** 快照或剩余待决卡证明本轮仍 park；旧请求帧不会因增量回放再次送达。 */
  awaitingInput?: boolean
}

interface StreamState {
  runId: string | null
  terminal: boolean
  lastFrame: SseFrame | null
  reconnects: number
  /** 已收到的最大帧序号（T6：重挂时回传，服务端只补增量）。 */
  lastSeq: number | null
  /**
   * 本连接收到挂起类请求帧，或调用方已由快照/剩余待决卡确认本轮仍 park。
   *
   * B1（2026-10-20，挂起即断）：服务端在这类帧之后**主动完成订阅**（流派 B），
   * 因此"连接关闭"在这里是**正常收尾**而不是断流 —— 既不自动重连（否则挂起期
   * 又挂一条连接、还白占 2s 心跳），也不交给调用方按断流提示。重连只由用户决策后的
   * 显式路径触发（`stores/ai.ts` 决策成功后带 lastSeq 重连）。
   */
  awaitsInput: boolean
}

function newState(): StreamState {
  return { runId: null, terminal: false, lastFrame: null, reconnects: 0, lastSeq: null, awaitsInput: false }
}

/** B1：挂起类请求帧 —— 服务端发出它们之后会主动收流（挂起即断）。 */
function isSuspendRequestFrame(frame: SseFrame): boolean {
  return frame.type === 'confirm_request' || frame.type === 'frontend_tool_request'
}

/**
 * SSE 事件块切分器：把字节流切成一个个 data 载荷。
 *
 * <p>导出理由：**可测试性**。分片 / 换行归一 / 多行 data / 流尾残余这些分支只在
 * `readStream` 内部可达，没有导出就只能靠整条 `streamChat` + fetch mock 间接覆盖，
 * 边界用例（一帧跨多块、`\r` 与 `\n` 分属两块、BOM）无法逐分支断言。
 * 它不是公共 API —— 生产调用点仍只有 `readStream`。
 */
export class SseChunkParser {
  private buffer = ''

  /** 上一块以裸 `\r` 结尾：留到下一块再归一，避免 CRLF 被块边界劈成"空行"。 */
  private carryCr = false

  /** 投喂一段文本，返回本次新产生的完整事件载荷。 */
  push(chunk: string): string[] {
    let text = this.carryCr ? `\r${chunk}` : chunk
    this.carryCr = false
    // CRLF / 单 CR 都归一成 LF；块尾的 CR 可能是被切开的 CRLF 前半，先挂起
    if (text.endsWith('\r')) {
      text = text.slice(0, -1)
      this.carryCr = true
    }
    this.buffer += text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    // 挂起的 CR 若已跟在换行之后（空行已成型），它就是该空行的行结束 —— 立即收口，
    // 否则 `\r\r` 这类块内分隔要等到下一块或 flush 才成帧。
    if (this.carryCr && this.buffer.endsWith('\n')) {
      this.buffer += '\n'
      this.carryCr = false
    }
    return this.drain()
  }

  /** 流结束时冲刷残余块（后端正常以 \n\n 结尾，这里是防御）。 */
  flush(): string[] {
    if (this.carryCr) {
      this.buffer += '\n'
      this.carryCr = false
    }
    if (!this.buffer.trim()) {
      this.buffer = ''
      return []
    }
    const payload = extractData(this.buffer)
    this.buffer = ''
    return payload === null ? [] : [payload]
  }

  private drain(): string[] {
    const payloads: string[] = []
    let index = this.buffer.indexOf('\n\n')
    while (index >= 0) {
      const block = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 2)
      const payload = extractData(block)
      if (payload !== null) {
        payloads.push(payload)
      }
      index = this.buffer.indexOf('\n\n')
    }
    return payloads
  }
}

/**
 * 从单个事件块抽取 data（多行 data 以 \n 连接；忽略注释与 event/id 字段）。
 *
 * BOM 容忍：部分代理/中间件会在流首（甚至每个事件块）补 U+FEFF，它落在 `data:` 之前时
 * 会让 `startsWith('data:')` 判定失败、整帧静默丢失 —— 先剥掉行首 BOM 再判前缀。
 */
function extractData(block: string): string | null {
  const lines = block.split('\n')
  const dataLines: string[] = []
  for (const raw of lines) {
    const line = raw.startsWith('\ufeff') ? raw.slice(1) : raw
    if (!line.startsWith('data:')) {
      continue
    }
    dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  return dataLines.length > 0 ? dataLines.join('\n') : null
}

/**
 * 文本 → 帧（非 JSON 或 type 不在白名单时返回 null，并交给 onFrame 之外的路径忽略）。
 *
 * BOM 容忍：`data: \ufeff{...}` 里的 BOM 会让 JSON.parse 失败；剥掉首字符后按原口径解析。
 */
export function parseSseFrame(text: string): SseFrame | null {
  const payload = text.startsWith('\ufeff') ? text.slice(1) : text
  if (!payload || payload === '[DONE]') {
    return null
  }
  try {
    return toSseFrame(JSON.parse(payload))
  } catch {
    return null
  }
}

interface ConsumeResult {
  terminal: boolean
  reason?: string
  aborted: boolean
}

function consumeFrame(
  frame: SseFrame,
  state: StreamState,
  handlers: SseFrameHandlers
): void {
  /**
   * S-12 终帧闸门（2026-10-22 修复）：终态（`done` / `error`）之后到达的帧**一条都不落地** ——
   * 不更新 lastSeq / lastFrame，不投递 `onFrame`，更不触发 `onTerminal`。
   *
   * <p>修掉的缺陷：旧实现的终帧判定在**整块消费完**之后（`for (const payload of parser.push(text))`
   * 循环之后才 `break`），同一 TCP 块里跟在 `done` 后面的迟到帧仍会逐帧投递给 `onFrame`，
   * 调用方据此写 parts / 新建工具卡 / 改卡状态（违反蓝本 §2.1 S-12「帧不入 parts、不改卡状态、
   * 不进镜像」）。
   *
   * <p>闸门放在本函数顶部而不是三个循环体内：主读循环、流尾 `decoder.decode()` 冲刷、
   * `parser.flush()` 残余块、以及 reattach 通道的 `onmessage` 都只经这一个投递口 ——
   * 一处判定即覆盖全部到达路径（双通道 / 重挂回放同样受约束）。
   */
  if (state.terminal) {
    return
  }
  /**
   * 单连接单轮次（帧归属闸）：一条 SSE 连接只承载一轮（`streamChat` 的 POST 与 `reattach`
   * 的 GET 都是按 runId 建连）。若服务端/代理异常地把**另一轮**的帧混进同一连接，
   * 这里整帧丢弃 —— 否则异轮的 `start` 会把 `state.runId` 改写成别人（断流后重挂到错的轮次），
   * 异轮的 `done` 会把本轮判成终态、提前收流，业务帧则会写进本轮的 parts（串轮）。
   *
   * 判据：本轮 runId 已确立（`start` 已到或 `reattach` 入参）、帧自带 runId 且不等。
   * 帧不带 runId（旧帧 / 心跳）与尚未确立 runId 的窗口一律照收，保持既有兼容口径。
   */
  const incomingRunId = (frame as { runId?: unknown }).runId
  if (state.runId !== null && typeof incomingRunId === 'string' && incomingRunId !== state.runId) {
    return
  }
  state.lastFrame = frame
  const seq = frameSeq(frame)
  if (seq !== null) {
    state.lastSeq = state.lastSeq === null ? seq : Math.max(state.lastSeq, seq)
  }
  if (frame.type === 'start') {
    state.runId = frame.runId
  }
  if (isSuspendRequestFrame(frame)) {
    // B1：卡面请求帧已送达 ⇒ 服务端随后主动收流（挂起即断）。标记只在本连接内有效。
    state.awaitsInput = true
  } else if (frame.type === 'delta' || frame.type === 'reasoning' || frame.type === 'message_start'
    || frame.type === 'retry' || frame.type === 'tool_start' || frame.type === 'tool_result'
    || frame.type === 'frontend_tool_result') {
    state.awaitsInput = false
  }
  handlers.onFrame?.(frame)
  if (isTerminalFrame(frame)) {
    state.terminal = true
    handlers.onTerminal?.(frame)
  }
}

/** 把外部 signal 与内部（含静默看门狗）signal 串起来。 */
function linkAbort(external: AbortSignal | undefined, controller: AbortController): () => void {
  if (!external) {
    return () => undefined
  }
  if (external.aborted) {
    controller.abort()
    return () => undefined
  }
  const forward = (): void => controller.abort()
  external.addEventListener('abort', forward, { once: true })
  return () => external.removeEventListener('abort', forward)
}

/** 非 2xx 响应 → ApiError（体可能是业务信封，也可能是 AI 裸对象）。 */
async function toResponseError(response: Response): Promise<ApiError> {
  let body: unknown = null
  let message = `请求失败（HTTP ${response.status}）`
  let code = String(response.status)
  try {
    body = await response.json()
  } catch {
    body = null
  }
  if (body && typeof body === 'object') {
    const candidate = body as { code?: unknown; message?: unknown }
    if (typeof candidate.code === 'string') {
      code = candidate.code
    }
    if (typeof candidate.message === 'string') {
      message = candidate.message
    }
  }
  return new ApiError(message, response.status, code, body)
}

function finish(
  state: StreamState,
  handlers: SseFrameHandlers,
  terminal: boolean,
  reason: string | undefined
): StreamCloseInfo {
  const suspended = !terminal && state.awaitsInput
  const info: StreamCloseInfo = {
    terminal,
    lastFrame: state.lastFrame,
    reconnects: state.reconnects,
    ...(reason === undefined ? {} : { reason }),
    ...(suspended ? { suspended: true } : {})
  }
  handlers.onClose?.(info)
  return info
}

/** 读取一个 fetch 响应体到结束；返回结束原因。 */
async function readStream(
  response: Response,
  controller: AbortController,
  state: StreamState,
  handlers: SseFrameHandlers,
  idleTimeoutMs: number,
  onIdle: () => void
): Promise<ConsumeResult> {
  if (!response.body) {
    return { terminal: false, aborted: false, reason: '响应无流式主体（浏览器不支持 ReadableStream）' }
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = new SseChunkParser()

  let idleTimer: ReturnType<typeof setTimeout> | null = null
  const resetIdle = (): void => {
    if (idleTimer !== null) {
      clearTimeout(idleTimer)
    }
    idleTimer = setTimeout(() => {
      onIdle()
      controller.abort()
    }, idleTimeoutMs)
  }
  resetIdle()

  try {
    for (;;) {
      // 批G · S6：**每块前先看中止标志**。abort 只断网络，不会撤回 `reader.read()` 已经返回
      // 的那一整块帧 —— 旧实现会把它全部消费掉（实证 D8：停止后 60ms 内还能新建/改写工具卡）。
      if (controller.signal.aborted) {
        return { terminal: state.terminal, aborted: true, reason: '连接被中止' }
      }
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      if (controller.signal.aborted) {
        // 读返回与 abort 同一 tick：这块帧属于"停止之后"，一律不落地
        return { terminal: state.terminal, aborted: true, reason: '连接被中止' }
      }
      resetIdle()
      const text = decoder.decode(value, { stream: true })
      for (const payload of parser.push(text)) {
        const frame = parseSseFrame(payload)
        if (frame) {
          consumeFrame(frame, state, handlers)
        }
      }
      // 终帧到达即不再等待服务端关流（后端紧随其后 complete()）
      if (state.terminal) {
        break
      }
    }
    if (controller.signal.aborted) {
      return { terminal: state.terminal, aborted: true, reason: '连接被中止' }
    }
    const tail = decoder.decode()
    if (tail) {
      for (const payload of parser.push(tail)) {
        const frame = parseSseFrame(payload)
        if (frame) {
          consumeFrame(frame, state, handlers)
        }
      }
    }
    for (const payload of parser.flush()) {
      const frame = parseSseFrame(payload)
      if (frame) {
        consumeFrame(frame, state, handlers)
      }
    }
  } catch (error) {
    const aborted = controller.signal.aborted
    if (aborted) {
      return { terminal: state.terminal, aborted: true, reason: '连接被中止' }
    }
    return {
      terminal: state.terminal,
      aborted: false,
      reason: error instanceof Error ? error.message : '流读取异常'
    }
  } finally {
    if (idleTimer !== null) {
      clearTimeout(idleTimer)
    }
    try {
      reader.releaseLock()
    } catch {
      // releaseLock 在已关闭的流上可能抛错，忽略
    }
  }
  return { terminal: state.terminal, aborted: false }
}

/**
 * 一轮对话：POST /api/ai/chat 并消费 SSE。
 *
 * 断流（未收终帧）时：有 runId → 自动 reattach 重挂；无 runId 或重挂用尽 →
 * 以 terminal=false 收尾，由调用方按 ADR-6 提示。
 *
 * <h2>为什么这条通道要自己带 `Accept-Language`</h2>
 *
 * axios 的请求拦截器给所有 REST 调用加了该头，但本通道是裸 `fetch`（EventSource 只支持 GET，
 * 且不能设请求头）—— 修复前这里只有 `Content-Type`/`Accept`，后端因此恒按 `zh-CN` 处理：
 * ① 开流前错误（`SESSION_BUSY`/`MESSAGE_TOO_LONG`/429/503）在英文界面上仍是中文；
 * ② 后端拿不到本轮 locale，A3-Y1（4B：默认中文、随用户语言）的"回答语言"没有来源。
 * 取值与 REST 通道同一函数（`acceptLanguage()`），两条通道的语言口径因此不会再分叉。
 *
 * <p>覆盖范围（I-1 修完之后）：走 `errorBody()/localize()` 的码**与**5 个专门 helper
 * （超长 / 限流 / 池饱和 / AI 不可用 / Redis 不可用）以及 `/context` 的 503 都由后端按同一
 * locale 出文案 —— 前端拿到的 `error.message` 已是目标语言，不再需要在模板里二次拼接。
 * 未覆盖项：`EventSource` reattach 通道读不到响应体，其 `reason` 文案仍是本地前端字符串。
 */
export async function streamChat(
  request: AiChatRequest,
  handlers: SseFrameHandlers = {},
  options: SseStreamOptions = {}
): Promise<StreamCloseInfo> {
  const idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS
  const state = newState()
  const controller = new AbortController()
  const unlink = linkAbort(options.signal, controller)
  let idleTriggered = false

  try {
    let response: Response
    try {
      response = await fetch(SSE_PATHS.chat, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          'Accept-Language': acceptLanguage()
        },
        body: JSON.stringify(request),
        signal: controller.signal
      })
    } catch (error) {
      const apiError = new ApiError(
        error instanceof Error ? error.message : '请求未能发出',
        0,
        FRONTEND_ERROR_CODES.NETWORK_ERROR,
        null
      )
      handlers.onError?.(apiError)
      throw apiError
    }

    if (!response.ok) {
      const apiError = await toResponseError(response)
      handlers.onError?.(apiError)
      throw apiError
    }

    handlers.onOpen?.()
    const result = await readStream(response, controller, state, handlers, idleTimeoutMs, () => {
      idleTriggered = true
    })

    if (state.terminal) {
      return finish(state, handlers, true, undefined)
    }
    if (result.aborted) {
      const reason = idleTriggered ? '帧间静默超时，判定断流' : '连接已中止'
      const reattached = idleTriggered
        ? await tryReattach(state, handlers, options)
        : { terminal: false, reason }
      return reattached.terminal
        ? finish(state, handlers, true, undefined)
        : finish(state, handlers, false, reattached.reason ?? reason)
    }
    if (state.awaitsInput) {
      // B1（2026-10-20 挂起即断）：卡面请求帧已送达 ⇒ 服务端主动收流，这是**正常收尾**。
      // 不自动重连（挂起期不占连接）；重连由决策成功后的显式路径带 lastSeq 触发。
      return finish(state, handlers, false, '挂起等待（服务端已主动收流，决策后带 lastSeq 重连）')
    }
    const reattached = await tryReattach(state, handlers, options)
    return reattached.terminal
      ? finish(state, handlers, true, undefined)
      : finish(state, handlers, false, reattached.reason ?? result.reason ?? '未收到终帧（断流）')
  } finally {
    unlink()
  }
}

/** 断流后的重挂尝试（无 runId 或未配置重连时直接返回失败事实）。 */
async function tryReattach(
  state: StreamState,
  handlers: SseFrameHandlers,
  options: SseStreamOptions
): Promise<{ terminal: boolean; reason?: string }> {
  if (!state.runId) {
    return { terminal: false, reason: '未收到 start 帧（无 runId 可重挂），判定断流' }
  }
  if (options.signal?.aborted) {
    return { terminal: false, reason: '连接已取消' }
  }
  const info = await reattach(state.runId, handlers, options, state)
  return info.terminal ? { terminal: true } : { terminal: false, reason: info.reason ?? '重挂未能收到终帧' }
}

/**
 * reattach：重挂进行中/刚结束的那一轮（GET /api/ai/events/{runId}）。
 *
 * EventSource 会在连接断开时自行重连，这里接管该行为以便：限制重连次数、
 * 走指数退避、并在收到终帧或重连耗尽时明确收尾（避免"静默重连"把断流掩盖成挂起）。
 *
 * 两只看门狗（缺一不可）：
 * - **建连看门狗**（`connectTimer`，R-03）：覆盖 `open()` 到 `onopen` 之间的窗口 —— 原生
 *   `EventSource` 在这段没有任何计时器，连接迟迟不 open 也不 error 时 `settle` 原本无可达路径；
 * - **帧间看门狗**（`idleTimer`）：原有行为，`onopen` / 每帧刷新，静默超过 `idleTimeoutMs` 判断流。
 *
 * 本函数**只 resolve、不 reject**（错误一律经 `onClose` 的 `terminal=false` + `reason` 表达），
 * 调用方的 `finally`（复位 `loading`）因此必然执行。
 */
export async function reattach(
  runId: string,
  handlers: SseFrameHandlers = {},
  options: SseStreamOptions = {},
  existing?: StreamState
): Promise<StreamCloseInfo> {
  const state = existing ?? newState()
  state.runId = runId
  state.awaitsInput ||= options.awaitingInput === true
  const maxReconnects = DEFAULT_MAX_RECONNECTS
  const random = options.random ?? Math.random
  const idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS

  return await new Promise<StreamCloseInfo>((resolve) => {
    let source: EventSource | null = null
    let idleTimer: ReturnType<typeof setTimeout> | null = null
    let connectTimer: ReturnType<typeof setTimeout> | null = null
    let attempts = 0
    let settled = false
    let connectionEstablished = false

    const clearIdle = (): void => {
      if (idleTimer !== null) {
        clearTimeout(idleTimer)
      }
      idleTimer = null
    }

    /**
     * 清建连看门狗（R-03）。
     *
     * 不变量：任一时刻**只武装一个**看门狗 —— 建连看门狗在 `onopen`（或首个帧）让位给帧间
     * 看门狗，退避期间两个都清（避免上一只残留计时器在退避中途再次 `scheduleReconnect`，
     * 造成次数重复计数 / 双连接）。
     */
    const clearConnect = (): void => {
      if (connectTimer !== null) {
        clearTimeout(connectTimer)
      }
      connectTimer = null
    }

    const settle = (terminal: boolean, reason?: string): void => {
      if (settled) {
        return
      }
      settled = true
      clearIdle()
      clearConnect()
      source?.close()
      source = null
      options.signal?.removeEventListener('abort', onAbort)
      resolve(finish(state, handlers, terminal, reason))
    }

    const onAbort = (): void => {
      settle(state.terminal, '连接已取消')
    }

    const startIdle = (): void => {
      connectionEstablished = true
      clearIdle()
      // 连接已被证明活着（onopen / 收到帧），建连看门狗让位
      clearConnect()
      idleTimer = setTimeout(() => {
        if (state.terminal) {
          settle(true)
          return
        }
        source?.close()
        source = null
        scheduleReconnect('帧间静默超时，判定断流')
      }, idleTimeoutMs)
    }

    /**
     * 建连看门狗（R-03 / 前端篇 B-03）：覆盖 `open()` 之后到 `onopen` 之间的窗口。
     *
     * 修掉的缺陷：`EventSource` 从构造到 `onopen` 之间原本**没有任何计时器**，
     * `idleTimer` 只在 `onopen` 里 `startIdle()` 才武装 ⇒ 连接迟迟不 open 也不 error 时
     * （TCP 已建但响应头不 commit / 代理黑洞 / 静默挂起），`settle` 无可达路径，promise 永不
     * settle，调用方（`stores/ai.ts` 的 send / reattachActive / resumeSnapshotStream）的
     * `finally` 不执行 ⇒ `loading` 永不复位、输入区假死。超时后走既有收尾路径
     * （`scheduleReconnect` → 退避重试 → 次数耗尽后 `settle(false, …)` → `onClose` 提示）。
     */
    const startConnect = (): void => {
      clearConnect()
      connectTimer = setTimeout(() => {
        connectTimer = null
        if (settled) {
          return
        }
        if (state.terminal) {
          settle(true)
          return
        }
        source?.close()
        source = null
        scheduleReconnect('重挂连接建立超时（未收到 onopen）')
      }, DEFAULT_CONNECT_TIMEOUT_MS)
    }

    const scheduleReconnect = (reason: string): void => {
      if (settled) {
        return
      }
      // 退避期间不保留任何看门狗：重试由下面的 delay 计时器负责，open() 会重新武装建连看门狗
      clearIdle()
      clearConnect()
      if (options.signal?.aborted) {
        settle(state.terminal, '连接已取消')
        return
      }
      if (state.awaitsInput && connectionEstablished) {
        // 请求帧或权威快照都已确认本轮在等人：服务端收流属正常收尾，不做无意义重连。
        // 后续连接是否允许重连由调用方重新判定；已收到的终帧不会被这个初始状态覆盖。
        settle(false, '挂起等待（服务端已主动收流，决策后带 lastSeq 重连）')
        return
      }
      if (attempts >= maxReconnects) {
        if (!connectionEstablished) {
          state.awaitsInput = false
        }
        settle(state.terminal, `${reason}（重挂已尝试 ${attempts} 次）`)
        return
      }
      attempts += 1
      state.reconnects = attempts
      const delay = reconnectDelayMs(attempts, random)
      handlers.onReconnect?.(attempts, delay)
      setTimeout(() => {
        if (!settled && !options.signal?.aborted) {
          open()
        }
      }, delay)
    }

    const open = (): void => {
      // T6：优先用"本连接已收到的最大序号"（增量重连），退回调用方给的 lastSeq
      const since = state.lastSeq ?? options.lastSeq
      source = new EventSource(SSE_PATHS.events(runId, since ?? undefined))
      // R-03：看门狗必须在 onopen 之前武装（构造是同步的，首个事件只会在此后的任务里派发，
      // 故此处与构造之间没有可丢失事件的窗口）
      startConnect()
      source.onopen = () => {
        handlers.onOpen?.()
        startIdle()
      }
      source.onmessage = (event: MessageEvent<string>) => {
        startIdle()
        const frame = parseSseFrame(event.data)
        if (!frame) {
          return
        }
        consumeFrame(frame, state, handlers)
        if (state.terminal) {
          settle(true)
        }
      }
      source.onerror = () => {
        if (settled) {
          return
        }
        if (state.terminal) {
          // 已终态轮次：后端回放完即 complete()，浏览器把正常关流也报成 error
          settle(true)
          return
        }
        source?.close()
        source = null
        scheduleReconnect('重挂连接中断')
      }
    }

    if (options.signal?.aborted) {
      settle(false, '连接已取消')
      return
    }
    options.signal?.addEventListener('abort', onAbort, { once: true })
    open()
  })
}
