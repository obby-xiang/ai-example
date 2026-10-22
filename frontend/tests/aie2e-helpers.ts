/**
 * E2E 用例全集（第 53 件）「单测缺口补齐批」的公共夹具。
 *
 * <p>只做三件事：① 每例一个全新的 `stores/ai` 实例（模块级镜像状态不随 pinia 复位，
 * 故必须 `resetModules` 后重建模块图）；② 手搭消息/段；③ 帧构造 + 镜像写入。
 * 断言一律写在各 spec 内 —— 夹具不替用例表达语义。
 */
import { vi } from 'vitest'
import type { ChatMessage, MessagePart } from '@/types/ai'
import type { SseFrame } from '@/types/sse'

/** store 实例类型（避免在夹具里静态 import store，见 freshStore 的模块图说明）。 */
type AiStore = ReturnType<(typeof import('@/stores/ai'))['useAiStore']>

/** 与 store 同源的镜像键前缀（`MIRROR_PREFIX`；生产读点只有 store 自己）。 */
export const MIRROR_PREFIX = 'ai-mirror:'

/**
 * 每例唯一的会话 id。
 *
 * <p>为什么必须唯一：`loadHistory()` 会 `startMirror()`，节流写盘的最后一次（trailing）可能落在
 * 上一条用例结束之后；若同文件各例共用同一个 sessionId，那条尾写会覆盖/污染下一例的镜像键
 * （实测：整文件跑时 R-04 的气泡数被前一例的镜像多带出 2 条）。每例换键 = 污染面归零。
 */
let sessionSeq = 0

export function nextSession(prefix = 's-aie2e'): string {
  sessionSeq += 1
  return `${prefix}-${sessionSeq}`
}

/** 帧构造：用例只关心与断言相关的字段，其余按契约缺省（类型面收窄在此一处）。 */
export function frame(payload: Record<string, unknown>): SseFrame {
  return payload as unknown as SseFrame
}

/**
 * 每例一个全新的 store：`resetModules` 之后**同一模块图**里重建 pinia —— 静态 import 的
 * `pinia` 与 reset 后的 store 模块属于不同图，跨图 `setActivePinia` 不生效（会复用上一例的
 * store 实例，造成消息串味）。轮次级状态显式归零（镜像计时器等模块级状态不随 pinia 复位）。
 */
export async function freshStore(): Promise<AiStore> {
  vi.resetModules()
  const pinia = await import('pinia')
  pinia.setActivePinia(pinia.createPinia())
  const mod = await import('@/stores/ai')
  const store = mod.useAiStore()
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.activeRunId = null
  store.runSeq = {}
  store.historyLoaded = false
  store.lastRoundOutcome = 'ok'
  store.followupReady = false
  store.streamInterrupted = false
  store.stopped = false
  return store
}

/**
 * 与 `freshStore()` 同一注册表快照下的 `ApiError` 构造器。
 *
 * <p>为什么必须"同一快照"：`stores/ai.ts` 的 409 分支用 `error instanceof ApiError` 判真假，
 * 而 `vi.resetModules()` 之后**同一路径可能被解析成两份模块实例** —— 测试里自己 `new` 的
 * `ApiError` 与 store 手里的类不是同一个，`instanceof` 恒假，用例会在"全量并行"时随机变红
 * （实测：单跑绿、整仓跑红）。这里把 store 与 ApiError 的 import 放在**同一次** reset 之后取，
 * 两者必定共享同一实例。
 */
export async function freshStoreWithHttp(): Promise<{ store: AiStore; ApiError: ApiErrorCtor }> {
  const store = await freshStore()
  const http = await import('@/api/http')
  return { store, ApiError: http.ApiError as unknown as ApiErrorCtor }
}

/** `ApiError` 构造器签名（只取用例需要的那一部分，避免静态 import 破坏模块图假设）。 */
export type ApiErrorCtor = new (message: string, status: number, code: string, body: unknown) => Error & {
  readonly code: string
  readonly status: number
}

/** 一条消息（文本段 + 派生投影自洽）。 */
export function textMessage(
  id: string,
  role: ChatMessage['role'],
  text: string,
  overrides: Partial<ChatMessage> = {}
): ChatMessage {
  return {
    id,
    role,
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...overrides
  }
}

/** 一条工具段（`status` 决定卡面：succeeded / running / cancelled …）。 */
export function toolPart(toolCallId: string, seg: number, overrides: Record<string, unknown> = {}): MessagePart {
  return {
    kind: 'tool',
    seg,
    toolCallId,
    name: 'list_config_defs',
    kind2: 'BACKEND',
    status: 'succeeded',
    args: '{}',
    result: 'ok',
    requiresAction: false,
    ...overrides
  } as unknown as MessagePart
}

/** 镜像写入（形状与 `writeMirror` 同源：v2 + inFlight/runSeq/lastRunId）。 */
export function writeMirrorFixture(
  sessionId: string,
  shape: {
    messages: ChatMessage[]
    inFlight?: boolean
    lastRunId?: string | null
    runSeq?: Record<string, number>
  }
): void {
  sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify({
    v: 2,
    savedAt: 1,
    lastRunId: shape.lastRunId ?? null,
    inFlight: shape.inFlight === true,
    runSeq: shape.runSeq,
    messages: shape.messages.map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content,
      parts: message.parts,
      reasoning: message.reasoning,
      toolRuns: message.toolRuns,
      pendingCall: message.pendingCall,
      ...(message.stopped ? { stopped: true } : {})
    }))
  }))
}

/** 读镜像（断言"写没写、写了什么"的唯一入口）。 */
export function readMirrorFixture(sessionId: string): {
  lastRunId?: string | null
  inFlight?: boolean
  runSeq?: Record<string, number>
  messages?: ChatMessage[]
} | null {
  const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
  return raw ? JSON.parse(raw) : null
}

/** 段序指纹：逐段 `kind + toolCallId`（S-02 的"回放段序 == 实时段序"判据）。 */
export function segmentOrder(message: ChatMessage | undefined): string[] {
  return (message?.parts ?? []).map((part) => (
    part.kind === 'tool' ? `tool:${part.toolCallId}` : `${part.kind}:${part.seg}`
  ))
}
