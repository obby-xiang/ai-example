/**
 * AI 侧契约（一轮对话 / HITL 确认 / 前端工具回灌 / 健康探测 / 历史恢复）。
 *
 * 核对源：backend/.../ai/web/AiChatRequest.java、ConfirmRequest.java、
 *         FrontendToolResultRequest.java、AiController.java（端点与响应体现场）
 */

import type { SseFrame, PendingToolCallSnapshot, PendingStatus, ToolKind, ToolOutcome, ToolPreflight } from './sse'
import type { FormSpec } from './form-schema'
import type { ErrorCard } from '@/utils/error-card'

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
  /**
   * 治理 P0-1：前端执行器的结局八态（`FrontendToolExecution.outcome` 原样回灌）。
   * 后端据此落台账并在 tool_result 帧回发同值；取消路径可不带（`cancelled=true` 已表达结局）。
   */
  outcome?: ToolOutcome
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
  /** 前端工具挂起上限（秒；`app.ai.hitl.frontend-tool-timeout` 的折算值） */
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
  /**
   * R-144（后端历史携带状态）：按 toolCallId 映射的段级真态（挂在带 toolCalls 的 ASSISTANT 消息
   * metadata 上随载荷透出）。旧载荷缺该字段 ⇒ 前端占位兜底（status 'succeeded' / kind2 'UNKNOWN'）；
   * `kind` 缺数据 ⇒ 保持 'UNKNOWN'（不臆造默认值）；resume 重建轮（T4）同样无状态，共用该兜底。
   * R-160 M1：已决条目增透决策元数据 —— decision（approved/rejected/timeout，条目态派生）/
   * decidedAt（epoch 毫秒）/ submittedValues（FRONTEND 的 resultText 原文，前端 JSON.parse、
   * 失败兜底 null）。
   */
  toolCallStates?: Record<string, {
    status?: string
    kind?: string
    decision?: string
    decidedAt?: number
    submittedValues?: string
    /**
     * 治理 P0-1：条目结局八态（后端新载荷才有；含契约增量的 skipped）。**有则优先**于 status 经词表映射；
     * 缺（旧载荷 / resume 重建轮）⇒ 回落 status 旧表。
     */
    outcome?: string
    /** 治理 P0-1：错误码（可选，null = 无码）；一期不展示 */
    code?: string | null
  }>
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
 * cancelled=已取消（用户放弃 / 渲染器不可用 / 整轮取消），
 * skipped=已跳过（上游确定性失败导致本工具未执行；**一等状态**，不是 failed、不是空）。
 */
export type ToolRunStatus = 'pending' | 'running' | 'succeeded' | 'failed' | 'rejected' | 'expired' | 'blocked' | 'unknown' | 'cancelled' | 'skipped'

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
   * R-154 #17：消息时间戳（epoch 毫秒或 ISO 字符串，载荷原样透传）。历史路 = 后端历史条目
   * metadata 的 timestamp（AiHistoryMessage.timestamp，后端已回传）；实时路 = message_start 帧的
   * 服务端构造时刻 atMs。仅展示用途，可选、不参与任何判定；不入镜像（历史重载即恢复）。
   */
  timestamp?: unknown
  /**
   * 已应用过的帧序号（`types/sse.ts` 的 `frameSeq`；R2-1 的重复投递幂等记账）。
   *
   * **内部幂等记账，不入镜像**：`writeMirror` 的 `Pick` 不含本字段（刷新后新消息从空集合起算，
   * 旧帧的"已应用"事实不必跨刷新保留 —— 重挂以 `runSeq.lastSeq` 为准）。
   */
  appliedSeqs?: number[]
  /**
   * R-165 S5c：错误卡（demo v7.7 裁决区 08）—— **内联于对话流、按发生位置插入，绝不做顶部固定**。
   * 挂在本消息上（= 错误发生的那一轮），面板在元信息行之后渲染；分类口径见 `utils/error-card.ts`。
   * 内存态、不入镜像（刷新后由历史重载消失；错误卡记录的是"本轮现场"，不属可回读的业务事实）。
   */
  errorCard?: ErrorCard | null
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
  /**
   * R-158 #10：HITL 只读回执元数据（confirm()/submitGenerativeForm 写回，随段持久化，
   * 历史/镜像/过期降级的 spread 重建语义下保留；旧数据缺省 ⇒ 徽标从 status 派生）。
   *
   * 2A 挂账①：`timeout` 是**第三种门结局**（闸门未开、本次未执行）—— 后端 confirm_decision
   * 帧的 decision/status（TIMEOUT）由此落段；缺省（旧数据 / 历史载荷）仍从 status 派生。
   */
  decision?: 'approved' | 'rejected' | 'timeout'
  decidedAt?: number
  /** R-158 #10：generative_form 提交值回执（提交成功时写入，刷新后可回溯已填值） */
  submittedValues?: Record<string, unknown>
  /**
   * R-154 #2：帧侧截断标记（FrameSizeLimiter 64KB 字段级截断）——true = result 被截断、
   * truncatedReason 带原因；缺省 = 全文。历史侧 ToolResultLimiter 内嵌文本标记不在本字段范围。
   */
  truncated?: boolean
  truncatedReason?: string | null
  /**
   * 治理 P0-1：帧侧结局八态（tool_result 帧带 outcome 时落段，随镜像持久化；旧帧无值）。
   * 卡面**不展示本字段**（阶段二期再做）；段状态仍以 `status` 为准，二者由 parts.ts 的
   * `outcomeStatus` 单表映射，当前写入点只在 tool_result。
   */
  outcome?: ToolOutcome
  /** 该工具段当前是否在等人工/前端动作（确认门待放行、前端工具待执行） */
  requiresAction: boolean
  /**
   * 确认门 / 前端工具的等待上限与到期口径（T4 / T4-3 原文，仅 CONFIRM / FRONTEND 段有）。
   *
   * 为什么落在段上（批2 起）：`message.pendingCall` 已改为由段派生，若这几个字段不随段持久化，
   * 派生的挂起卡就丢了原始等待口径。
   *
   * 2026-10-20 去超时裁决后：前端**不再**据此折算任何倒计时/本地超时（表单一直可填、确认卡
   * 不设倒计时），本组字段只作**原始帧口径的对账/回滚依据**（镜像与快照重建仍照原样携带）。
   */
  timeoutSeconds?: number
  expiresAt?: number
  atMs?: number
  /**
   * H1 确认门预检摘要（可选契约增量；来自 confirm_request / suspended 帧，随段持久化）。
   * 缺省 = 旧帧 / 无预检的工具（GF、前端工具、后端工具），门卡不渲染预检区。
   * 预检**不通过**时后端不弹卡，故门卡只在 `preflight` 存在（且通过）时出现该区。
   */
  preflight?: ToolPreflight | null
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
  /** 见 {@link ToolPart.decision} / {@link ToolPart.submittedValues}（R-158 #10 回执元数据） */
  decision?: 'approved' | 'rejected' | 'timeout'
  decidedAt?: number
  submittedValues?: Record<string, unknown>
  /** 见 {@link ToolPart.truncated}（R-154 #2 帧侧截断标记，随段持久化） */
  truncated?: boolean
  truncatedReason?: string | null
  /** 见 {@link ToolPart.outcome}（治理 P0-1 帧侧结局八态） */
  outcome?: ToolOutcome
  /** 见 {@link ToolPart.timeoutSeconds}（确认门/前端工具的等待口径，随段持久化） */
  timeoutSeconds?: number
  expiresAt?: number
  atMs?: number
  /** 见 {@link ToolPart.preflight}（H1 确认门预检摘要，可选增量） */
  preflight?: ToolPreflight | null
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
  /** 挂起等待上限（秒），来自 confirm_request 帧（去超时后仅作对账口径，不驱动任何倒计时） */
  timeoutSeconds?: number
  /**
   * DC-14 T4：挂起等待的绝对到期时刻（epoch 毫秒，服务端钟）。
   * 去超时裁决后只随段原样透出以对账/回滚。
   */
  expiresAt?: number
  /**
   * T4-3：帧的**服务端构造时刻**（epoch 毫秒；实时 / 归档帧均带）。保留以对账。
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
 * R-166（口径③）：确认门提交态（同 toolCallId **最多提交一次**）。
 *
 * 进表时机 = 点击批准/拒绝的同一刻（**点击即入表**，按钮与程序化入口皆据此禁用/短路）；
 * 出表时机 = 结局帧到达 / 权威校正落定 / 提交失败回退。
 */
export interface GateSubmission {
  /** 本次提交的裁决（若已知 —— 由 `confirm()` 落账；仅作诊断/对账，不参与任何判定） */
  decision?: 'approved' | 'rejected'
  submittedAt: number
  /**
   * true = 409 `DUPLICATE_TOOL_CALL_ID` 之后的**「已提交，同步中…」瞬态** ——
   * 不写任何乐观裁决，等 SSE 结局帧落真实状态；3 秒未达则拉 `runs/current` 校正。
   */
  syncing: boolean
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
 * 不走 frontend-tools.ts 通用执行器 —— 那段说明文本不是值 JSON，会被闸门 400 拒；去超时裁决后
 * 表单一直可填，收口只认后端结局帧 / 用户取消（GFa §8.3），故 frontend_tool_request 帧在 store
 * 层特判挂起，等用户填写。
 */
export interface ActiveGenerativeForm {
  toolCallId: string
  runId: string
  /** 已过防御性解析的表单 schema */
  form: FormSpec
  status: GenerativeFormStatus
  /** 客户端复核 / 后端 FORM_RESULT_REJECTED 的提示（null = 无） */
  error: string | null
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
  /**
   * B1（2026-10-20 挂起即断）：本次收尾是**挂起**导致的服务端主动收流（正常收尾），
   * 不是断流。判据 = 本连接已收到 `confirm_request` / `frontend_tool_request` 之后连接关闭。
   * 调用方据此不置 `streamInterrupted`、不报「结果可能不完整」；卡仍待用户决策。
   */
  suspended?: boolean
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
  /** R-160 M3：挂起到期口径（服务端钟；保留作对账/回滚，前端不再据此折算任何倒计时） */
  expiresAt?: number | null
  /**
   * R-160 M3：expiresAt 的构造时刻（帧内单点取值）。去超时裁决后本字段只作对账口径，
   * 前端不再据此折算倒计时。
   */
  atMs?: number | null
  /**
   * H1 确认门预检摘要（可选增量；缺省 = 旧载荷/无预检的工具）。刷新恢复据此重建带摘要的
   * 挂起卡 —— 与 `suspended` / `confirm_request` 帧同一形状（后端 `PendingToolCall.preflight`）。
   */
  preflight?: ToolPreflight | null
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
