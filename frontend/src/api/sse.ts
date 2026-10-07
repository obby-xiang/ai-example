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
import { isTerminalFrame, toSseFrame } from '@/types/sse'
import type { SseDoneFrame, SseErrorFrame, SseFrame } from '@/types/sse'
import type { AiChatRequest, StreamCloseInfo } from '@/types/ai'

/** 帧间静默上限：超过即判断流（后端挂起期有 heartbeat，正常不会触发）。 */
export const DEFAULT_IDLE_TIMEOUT_MS = 90_000

/** 断流后自动重挂的最大次数。 */
export const DEFAULT_MAX_RECONNECTS = 2

/** 首次重连等待（后续指数退避）。 */
export const DEFAULT_RECONNECT_DELAY_MS = 1_000

export const SSE_PATHS = {
  chat: '/api/ai/chat',
  events: (runId: string): string => `/api/ai/events/${encodeURIComponent(runId)}`
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
  /** 重连前的公告（前端可展示"连接中断，正在重挂"） */
  onReconnect?: (attempt: number, delayMs: number) => void
  /** 连接层面的错误（HTTP 非 2xx、网络异常） */
  onError?: (error: ApiError) => void
}

/** 流选项。 */
export interface SseStreamOptions {
  /** 外部取消（用户点"停止"）：abort 后不再重连 */
  signal?: AbortSignal
  idleTimeoutMs?: number
  maxReconnects?: number
  reconnectDelayMs?: number
}

interface StreamState {
  runId: string | null
  terminal: boolean
  lastFrame: SseFrame | null
  reconnects: number
}

function newState(): StreamState {
  return { runId: null, terminal: false, lastFrame: null, reconnects: 0 }
}

/** SSE 事件块切分器：把字节流切成一个个 data 载荷。 */
class SseChunkParser {
  private buffer = ''

  /** 投喂一段文本，返回本次新产生的完整事件载荷。 */
  push(chunk: string): string[] {
    this.buffer += chunk.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
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

  /** 流结束时冲刷残余块（后端正常以 \n\n 结尾，这里是防御）。 */
  flush(): string[] {
    if (!this.buffer.trim()) {
      this.buffer = ''
      return []
    }
    const payload = extractData(this.buffer)
    this.buffer = ''
    return payload === null ? [] : [payload]
  }
}

/** 从单个事件块抽取 data（多行 data 以 \n 连接；忽略注释与 event/id 字段）。 */
function extractData(block: string): string | null {
  const lines = block.split('\n')
  const dataLines: string[] = []
  for (const line of lines) {
    if (!line.startsWith('data:')) {
      continue
    }
    dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  return dataLines.length > 0 ? dataLines.join('\n') : null
}

/** 文本 → 帧（非 JSON 或 type 不在白名单时返回 null，并交给 onFrame 之外的路径忽略）。 */
export function parseSseFrame(text: string): SseFrame | null {
  if (!text || text === '[DONE]') {
    return null
  }
  try {
    return toSseFrame(JSON.parse(text))
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
  state.lastFrame = frame
  if (frame.type === 'start') {
    state.runId = frame.runId
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
  const info: StreamCloseInfo = {
    terminal,
    lastFrame: state.lastFrame,
    reconnects: state.reconnects,
    ...(reason === undefined ? {} : { reason })
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
      const { done, value } = await reader.read()
      if (done) {
        break
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
 */
export async function streamChat(
  request: AiChatRequest,
  handlers: SseFrameHandlers = {},
  options: SseStreamOptions = {}
): Promise<StreamCloseInfo> {
  const idleTimeoutMs = options.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS
  const state = newState()
  const controller = new AbortController()
  const unlink = linkAbort(options.signal, controller)
  let idleTriggered = false

  try {
    let response: Response
    try {
      response = await fetch(SSE_PATHS.chat, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
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
  const maxReconnects = options.maxReconnects ?? DEFAULT_MAX_RECONNECTS
  if (!state.runId) {
    return { terminal: false, reason: '未收到 start 帧（无 runId 可重挂），判定断流' }
  }
  if (options.signal?.aborted) {
    return { terminal: false, reason: '连接已取消' }
  }
  if (maxReconnects <= 0) {
    return { terminal: false, reason: '未收到终帧（已禁用自动重挂）' }
  }
  const info = await reattach(state.runId, handlers, options, state)
  return info.terminal ? { terminal: true } : { terminal: false, reason: info.reason ?? '重挂未能收到终帧' }
}

/**
 * reattach：重挂进行中/刚结束的那一轮（GET /api/ai/events/{runId}）。
 *
 * EventSource 会在连接断开时自行重连，这里接管该行为以便：限制重连次数、
 * 走指数退避、并在收到终帧或重连耗尽时明确收尾（避免"静默重连"把断流掩盖成挂起）。
 */
export async function reattach(
  runId: string,
  handlers: SseFrameHandlers = {},
  options: SseStreamOptions = {},
  existing?: StreamState
): Promise<StreamCloseInfo> {
  const state = existing ?? newState()
  state.runId = runId
  const maxReconnects = options.maxReconnects ?? DEFAULT_MAX_RECONNECTS
  const baseDelay = options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS
  const idleTimeoutMs = options.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS

  return await new Promise<StreamCloseInfo>((resolve) => {
    let source: EventSource | null = null
    let idleTimer: ReturnType<typeof setTimeout> | null = null
    let attempts = 0
    let settled = false

    const clearIdle = (): void => {
      if (idleTimer !== null) {
        clearTimeout(idleTimer)
      }
      idleTimer = null
    }

    const settle = (terminal: boolean, reason?: string): void => {
      if (settled) {
        return
      }
      settled = true
      clearIdle()
      source?.close()
      source = null
      options.signal?.removeEventListener('abort', onAbort)
      resolve(finish(state, handlers, terminal, reason))
    }

    const onAbort = (): void => {
      settle(state.terminal, '连接已取消')
    }

    const startIdle = (): void => {
      clearIdle()
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

    const scheduleReconnect = (reason: string): void => {
      if (settled) {
        return
      }
      if (options.signal?.aborted) {
        settle(state.terminal, '连接已取消')
        return
      }
      if (attempts >= maxReconnects) {
        settle(state.terminal, `${reason}（重挂已尝试 ${attempts} 次）`)
        return
      }
      attempts += 1
      state.reconnects = attempts
      const delay = baseDelay * 2 ** (attempts - 1)
      handlers.onReconnect?.(attempts, delay)
      setTimeout(() => {
        if (!settled && !options.signal?.aborted) {
          open()
        }
      }, delay)
    }

    const open = (): void => {
      source = new EventSource(SSE_PATHS.events(runId))
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
