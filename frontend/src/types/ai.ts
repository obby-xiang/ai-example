/**
 * AI 侧契约（一轮对话 / HITL 确认 / 前端工具回灌 / 健康探测 / 历史恢复）。
 *
 * 核对源：backend/.../ai/web/AiChatRequest.java、ConfirmRequest.java、
 *         FrontendToolResultRequest.java、AiController.java（端点与响应体现场）
 */

import type { SseFrame, PendingToolCallSnapshot, PendingStatus, ToolKind } from './sse'
import type { FormSpec } from './form-schema'

/**
 * 工作区上下文（AiChatRequest.Context）。
 *
 * **只允许这 5 个键**：后端的 ObjectMapper（JacksonConfig）是 `new ObjectMapper()`，
 * 未关闭 FAIL_ON_UNKNOWN_PROPERTIES，多余键有 400 风险；工作区的其余信息一律塞进 `extra`。
 *
 * page/taskType/step 三个标签决定本轮披露给模型的工具子集（见 types/tools.ts 的 ToolScope）。
 */
export interface AiChatContext {
  /** 页面标签：任务中心 / 导出向导 / 导入向导 / 配置定义 / 数据浏览（ToolScope 的 page:* 口径：tasks/definitions/data） */
  page?: string
  /** 任务类型标签：EXPORT / IMPORT（ToolScope 的 task:* 口径） */
  taskType?: 'EXPORT' | 'IMPORT' | null
  /** 当前步骤 key：SELECT_DEFS / QUERY_COND / EXPORT / UPLOAD / PRECHECK / IMPORT / PUBLISH */
  step?: string | null
  taskId?: number | null
  /** 额外上下文（工作区摘要、数据版本号等自由信息） */
  extra?: Record<string, unknown>
}

/** 一轮对话请求体（POST /api/ai/chat）。 */
export interface AiChatRequest {
  /** 页签会话 id（记忆窗口 CONVERSATION_ID）：sessionStorage 级，跨刷新保持、跨页签不同 */
  sessionId: string
  message: string
  context?: AiChatContext
}

/** HITL 确认/拒绝请求体（POST /api/ai/confirm）。 */
export interface ConfirmRequest {
  runId: string
  toolCallId: string
  /** true=放行执行，false=拒绝 */
  approved: boolean
  /** 拒绝原因，会原样回填给模型（放行时可空） */
  reason?: string | null
}

/** 确认结果（POST /api/ai/confirm 的响应体，**裸对象**，不套 ApiResponse）。 */
export interface ConfirmResult {
  accepted: boolean
  duplicate: boolean
  runId: string
  toolCallId: string
  status: PendingStatus | string
  approved?: boolean
  reason?: string | null
  executed?: boolean
  wokeInProcessGate?: boolean
  /** 写入者实例标识 */
  storedBy?: string
  note?: string
}

/** 前端工具结果回灌请求体（POST /api/ai/frontend-tool-result）。 */
export interface FrontendToolResultRequest {
  runId: string
  toolCallId: string
  /** 前端执行结果文本（值 JSON），原样作为工具结果回填给模型；cancelled=true 时可省 */
  result?: string
  /** 结果来源标注（默认 http-post），仅用于留档 */
  source?: string
  /**
   * GF-B（GFa 裁决 #2，可选字段）：true = 用户放弃 / 渲染器不可用 ——
   * 条目立刻落 FRONTEND_CANCELLED，不必等超时；与提交共用同一幂等入口。
   */
  cancelled?: boolean
}

/** 前端工具回灌结果（响应体同样是裸对象）。 */
export interface FrontendToolResultAck {
  accepted: boolean
  duplicate: boolean
  runId: string
  toolCallId: string
  status: PendingStatus | string
  executed: boolean
  wokeInProcessGate?: boolean
  storedBy?: string
}

/** AI 可用性（GET /api/ai/health；不可用时 HTTP 503 + 同样的体）。 */
export interface AiHealth {
  available: boolean
  /** AI_AVAILABLE 或不可用原因码（AI_UNAVAILABLE / AI_REDIS_UNAVAILABLE） */
  code: string
  model: string
  baseUrl: string
  memoryBackend: string
  sessionTtl: string
  confirmTimeoutSeconds: number
  /** 前端工具挂起上限（秒；`app.ai.hitl.frontend-tool-timeout` 的折算值，与确认门键分场景） */
  frontendToolTimeoutSeconds: number
  suspendPoolSize: number
  activeSuspendGates: number
  heldSessions: number
  sessionLockRenewals: number
  resumeOnStartup: boolean
  instanceId: string
  /** ISO 时刻 */
  checkedAt: string
  message?: string
  redis?: Record<string, unknown>
  resilience?: Record<string, unknown>
  cancellations?: Record<string, unknown>
}

/** 取消一轮对话的结果（POST /api/ai/cancel/{runId}，裸对象）。 */
export interface AiCancelResult {
  runId: string
  cancelled: boolean
  alreadyCancelled: boolean
  redisFlagWritten: boolean
  wokeInProcess: boolean
  cancelKey: string
  degraded: boolean
  runExists: boolean
  statusBeforeCancel?: string | null
  storedBy?: string
  note?: string
}

/** 会话历史消息（GET /api/ai/history/{sessionId} → data.messages 元素）。 */
export interface AiHistoryMessage {
  /** Spring AI MessageType 名称：USER / ASSISTANT / SYSTEM / TOOL */
  type: string
  text: string
  /** assistant 的 toolCalls（Spring AI AssistantMessage.ToolCall 列表，字段结构由上游协议决定） */
  toolCalls?: Array<{ id?: string; type?: string; name?: string; arguments?: string }>
  /** tool 消息的响应（ToolResponseMessage.ToolResponse 列表） */
  toolResponses?: Array<{ id?: string; name?: string; responseData?: string }>
  timestamp?: unknown
}

/** 会话历史响应（GET /api/ai/history/{sessionId} 的 data）。 */
export interface AiHistory {
  sessionId: string
  count: number
  messages: AiHistoryMessage[]
}

/** 待续跑轮次的台账描述（GET /api/ai/runs/{runId} 的 data，字段随实现演进，故为宽松映射）。 */
export type RunDescription = Record<string, unknown>

// ── 前端展示态（消息模型） ──────────────────────────────────────────────────

/**
 * 工具卡状态：
 * pending=待人确认或待前端执行，running=执行中，succeeded/failed=已结束，
 * rejected=人工拒绝，expired=会话已失效（后端历史为空，挂起态无法续跑），
 * blocked=被渐进披露防线③拦下（DC-14 T1：越 scope 的调用未执行 —— 与"执行失败"不是一回事），
 * unknown=结局未知（未确认成功/失败；前端执行器超时等"无法判定成败"的收尾），
 * cancelled=已取消（用户放弃 / 渲染器不可用 / 整轮取消）。
 */
export type ToolRunStatus = 'pending' | 'running' | 'succeeded' | 'failed' | 'rejected' | 'expired' | 'blocked' | 'unknown' | 'cancelled'

/** 工具卡的一次执行记录（同一轮可多次工具调用）。 */
export interface ToolRun {
  toolCallId: string
  name: string
  kind: ToolKind | 'UNKNOWN'
  status: ToolRunStatus
  /** 入参 JSON 字符串（原样展示，可折叠） */
  args?: string
  /** 结果文本摘要 */
  result?: string | null
  /** 拒绝原因 / 失败原因 */
  reason?: string | null
  /** DC-14 T5：结果铸为消息后的消息 id（`tool-<toolCallId>`），供消息级渲染/去重 */
  messageId?: string
}

/** 前端展示用的对话消息。 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  /** DC-14 T5：服务端给的消息身份（message_start/end 与 delta 帧的 messageId） */
  messageId?: string
  content: string
  /**
   * 消息的**段序列**：带顺序信息的判别联合 —— 文本段 / 思考段 / 工具段按真实时序交错，
   * 弥补 content + toolRuns + pendingCall 三字段并列、字段间无顺序信息的缺口。
   *
   * 本批口径（**权威翻转后**，对话时序包 批2）：`parts` 是**唯一真值**（面板按它渲染，
   * 帧只写它）；下方 `content` / `reasoning` / `toolRuns` / `pendingCall` 四个字段是同一份
   * 数据的**派生投影**（`utils/parts.ts` 的 `deriveProjections` 是唯一写口，delta / reasoning
   * 高频路径由 store 与段文本锁步 `+=`），保留它们只为存量读点（getter / 镜像 / 外部读）零改动。
   * 任何新写入点都必须写段、再派生 —— 直接写旧字段会与段漂移。
   */
  parts: MessagePart[]
  /** 思考链文本（= reasoning 段 join；后端当前不产 reasoning 帧，留字段供前向兼容） */
  reasoning: string
  toolRuns: ToolRun[]
  /** 当前挂起（待确认 / 待前端执行）的工具调用：= 最后一个 requiresAction 的工具段派生 */
  pendingCall: PendingToolCall | null
  /** 正文生成中 */
  streaming: boolean
  /** 已收到终帧 */
  done: boolean
  /**
   * 已应用过的帧序号（`types/sse.ts` 的 `frameSeq`；R2-1 的重复投递幂等记账）。
   *
   * **内部幂等记账，不入镜像**：`writeMirror` 的 `Pick` 不含本字段（刷新后新消息从空集合起算，
   * 旧帧的"已应用"事实不必跨刷新保留 —— 重挂以 `runSeq.lastSeq` 为准）。
   */
  appliedSeqs?: number[]
}

/** 消息段序号：同一消息内单调递增（封口后另起的段不复用编号）。 */
interface MessagePartBase {
  seg: number
}

/** 正文段（一次连续输出；被工具段/思考段打断即封口，后续正文另起一段）。 */
export interface TextPart extends MessagePartBase {
  kind: 'text'
  text: string
  /** 该段是否仍在接收增量（封口后为 false） */
  streaming: boolean
}

/** 思考段（reasoning 帧；后端当前不产出，照帧结构预留）。 */
export interface ReasoningPart extends MessagePartBase {
  kind: 'reasoning'
  text: string
  streaming: boolean
}

/**
 * 工具段（一次工具调用的时序占位，字段与旧投影 `ToolRun` 对齐 + 挂起语义）。
 * `kind2` 避开判别字段 `kind:'tool'` 的命名冲突，取值口径同 `ToolRun.kind`。
 */
export interface ToolPart extends MessagePartBase {
  kind: 'tool'
  toolCallId: string
  name: string
  kind2: ToolKind | 'UNKNOWN'
  status: ToolRunStatus
  /** 入参 JSON 字符串（原样展示，可折叠） */
  args?: string
  /** 结果文本摘要 */
  result?: string | null
  /** 拒绝原因 / 失败原因 */
  reason?: string | null
  /** DC-14 T5：结果铸为消息后的消息 id（`tool-<toolCallId>`） */
  messageId?: string
  /** 该工具段当前是否在等人工/前端动作（确认门待放行、前端工具待执行） */
  requiresAction: boolean
  /**
   * 确认门 / 前端工具的等待上限与到期口径（T4 / T4-3 原文，仅 CONFIRM / FRONTEND 段有）。
   *
   * 为什么落在段上（批2 起）：`message.pendingCall` 已改为由段派生，若这几个字段不随段持久化，
   * 派生出的挂起卡就丢了倒计时口径（确认卡会从"剩余 N 秒"退化成"等待人工确认"）。
   */
  timeoutSeconds?: number
  expiresAt?: number
  atMs?: number
}

/** 消息段判别联合（按 `kind` 判别）。 */
export type MessagePart = TextPart | ReasoningPart | ToolPart

/**
 * `upsertToolPart` 的补丁口径：前三项定位与建立段所需，其余为可选就地更新字段。
 * 字段名与 `ToolPart` / `ToolRun` 同名，便于"同一帧同时写两侧"时逐字段对齐。
 *
 * `status` 可选：**只更新**语义的调用点（如 suspended 帧，它不写工具投影）不提供 status，
 * 就地合并时保留段上原值 —— 若给成新值就会与同帧没被改写的旧投影对不上。
 */
export interface ToolPartPatch {
  toolCallId: string
  name: string
  kind2: ToolKind | 'UNKNOWN'
  requiresAction: boolean
  status?: ToolRunStatus
  args?: string
  result?: string | null
  reason?: string | null
  messageId?: string
  /** 见 {@link ToolPart.timeoutSeconds}（确认门/前端工具的等待口径，随段持久化） */
  timeoutSeconds?: number
  expiresAt?: number
  atMs?: number
}

/** 展示态的挂起调用（由 SSE 帧聚合而来）。 */
export interface PendingToolCall {
  toolCallId: string
  name: string
  kind: 'CONFIRM' | 'FRONTEND'
  /** 入参对象（由帧里的 args JSON 字符串解析；解析失败为 null） */
  args: Record<string, unknown> | null
  /** 原始入参字符串 */
  rawArgs?: string
  /** 确认门倒计时（秒），来自 confirm_request 帧 */
  timeoutSeconds?: number
  /**
   * DC-14 T4：挂起等待的绝对到期时刻（epoch 毫秒，服务端给的 `expiresAt`）。
   * T4-3 后半起倒计时按**服务端剩余时长**折算（见 `atMs`）：`atMs` 可用（> 0）时用
   * `expiresAt − atMs`；不可用（旧格式 / 损坏数据）才用本绝对值直接对本地钟。
   */
  expiresAt?: number
  /**
   * T4-3：帧的**服务端构造时刻**（epoch 毫秒；实时 / 归档帧均带）—— 与 `expiresAt` 同为
   * 服务端钟、同帧单点取值，故二者之差即服务端给出的剩余时长（折算见 `stores/ai.ts` 的
   * `foldDeadline`；`atMs ≤ 0` 视为不可用）。
   */
  atMs?: number
  /**
   * T2a-D#2：帧 status → pendingCall.status 终态映射的落点（FRONTEND_RESULT→succeeded /
   * FRONTEND_CANCELLED→cancelled / TIMEOUT→expired / REJECTED→rejected / BLOCKED→blocked），
   * 另保留本地确认/执行过程的 running/approved。
   */
  status: 'pending' | 'running' | 'approved' | 'rejected' | 'expired' | 'cancelled' | 'succeeded' | 'blocked'
}

/**
 * 页签级会话镜像（sessionStorage）写入形态。
 *
 * 版本（`v`，对话时序包 批1 起）：当前为 {@link MIRROR_VERSION}。旧镜像（缺 `v` 或 `v < 2`）
 * 由读取侧 `migrateMirror` 迁移出 `parts` 后立即回写；写入侧一律带 `v` 与 `parts`。
 *
 * 批2（权威翻转）起：`parts` 是唯一真值，`content` / `reasoning` / `toolRuns` / `pendingCall`
 * 是同一次写入前由段派生出的投影 —— 镜像里**仍双存**两份，为的是回滚（旧版前端读镜像）与
 * 外部读（调试/对账脚本直接看 JSON）不需要自行派生。
 */
export interface AiSessionMirror {
  /** 镜像结构版本（缺省 = v1 旧镜像，需迁移） */
  v?: number
  savedAt: number
  messages: Array<Pick<ChatMessage, 'id' | 'role' | 'content' | 'parts' | 'reasoning' | 'toolRuns' | 'pendingCall'>>
  /** 已结束轮的 runId（供 reattach 提示） */
  lastRunId?: string | null
}

/**
 * 当前镜像结构版本：
 * - v1（隐式）：只有 `content` / `toolRuns` / `pendingCall`，无 `parts`；
 * - v2（对话时序包 批1，批2 沿用）：新增 `parts` 段序列（批2 起为唯一真值，旧字段为派生投影）。
 */
export const MIRROR_VERSION = 2

/** 生成式表单（generative_form）的会话态生命周期。 */
export type GenerativeFormStatus = 'filling' | 'submitting' | 'submitted' | 'cancelled'

/**
 * GF-B（DC-15）：当前待填写的生成式表单（会话态，挂在 store 上，AiPanel 据此渲染 FormRenderer）。
 * 不走 frontend-tools.ts 通用执行器 —— 那段说明文本不是值 JSON，会被闸门 400 拒、挂起拖到超时
 * （GFa §8.3），故 frontend_tool_request 帧在 store 层特判挂起，等用户填写。
 */
export interface ActiveGenerativeForm {
  toolCallId: string
  runId: string
  /** 已过防御性解析的表单 schema */
  form: FormSpec
  status: GenerativeFormStatus
  /** 客户端复核 / 后端 FORM_RESULT_REJECTED 的提示（null = 无） */
  error: string | null
  /** 本地判超时的绝对时刻（epoch 毫秒；服务端剩余时长折算 `expiresAt − atMs` 优先（`atMs > 0`），缺 `atMs` 回落 `expiresAt` 绝对值、再缺回落 `timeoutSeconds`；null = 不限） */
  deadline: number | null
}

/** SSE 一轮的收尾判定结果。 */
export interface StreamCloseInfo {
  /** 是否收到终帧（done/error）；false = 断流，按 ADR-6 提示 */
  terminal: boolean
  lastFrame?: SseFrame | null
  /** 已尝试过的重连次数 */
  reconnects: number
  /** 断流原因文案 */
  reason?: string
}

/** 前端开发期读取 Spring AI 历史时使用的宽松消息快照（供对账比较）。 */
export interface AiHistorySnapshot {
  sessionId: string
  count: number
  /** 与镜像对比用：回答条数 */
  assistantCount: number
  /** 用户条数 */
  userCount: number
}

/** 由历史条目抽取对账摘要。 */
export function summarizeHistory(history: AiHistory): AiHistorySnapshot {
  let assistantCount = 0
  let userCount = 0
  for (const message of history.messages) {
    const type = (message.type || '').toUpperCase()
    if (type === 'ASSISTANT') {
      assistantCount += 1
    } else if (type === 'USER') {
      userCount += 1
    }
  }
  return { sessionId: history.sessionId, count: history.count, assistantCount, userCount }
}

/** 待决条目的固定键（供工具卡与确认卡复用）。 */
export type PendingSnapshotKey = keyof PendingToolCallSnapshot

/**
 * T2b 顺手项⑤：生成式表单工具名的唯一字面量来源。
 * 与后端 GenerativeFormRules.TOOL_NAME（backend/.../ai/form/GenerativeFormRules.java）同步 ——
 * 改值需双侧同步，单侧改会断判定。
 */
export const GENERATIVE_FORM_TOOL = 'generative_form'

/** GET /api/ai/runs/current 单条挂起条目快照（后端 PendingToolCall 条目原文）。 */
export interface AiRunCurrentEntry {
  toolCallId: string
  /** 后端条目状态原文：PENDING / TIMEOUT / FRONTEND_RESULT / FRONTEND_CANCELLED /
   * CANCELLED / REJECTED / BLOCKED / APPROVED / EXECUTED */
  entryStatus: string
  kind: string
  name: string
  /** 入参 JSON 字符串（原样，展示/解析由前端做） */
  arguments?: string | null
}

/** GET /api/ai/runs/current?sessionId= 的 data：当前会话进行中的 run 挂起快照（T2b-D#3）。 */
export interface AiRunCurrent {
  /** 空态（索引缺失/进程已死/快照已清）：仅 found=false，其余为默认值 */
  found: boolean
  runId: string | null
  snapshotStatus: string | null
  pendingEntries: AiRunCurrentEntry[]
  unresolvedExternal: string[]
  awaitingExternal: boolean
  /** 归档流最大帧序号：增量续收（lastSeq）的锚点 */
  archiveMaxSeq: number
}
