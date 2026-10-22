/**
 * AI 侧契约（一轮对话 / HITL 确认 / 前端工具回灌 / 健康探测 / 历史恢复）。
 *
 * 核对源：backend/.../ai/web/AiChatRequest.java、ConfirmRequest.java、
 *         FrontendToolResultRequest.java、AiController.java（端点与响应体现场）
 */

import type { SseFrame, PendingToolCallSnapshot, PendingStatus, ToolKind, ToolOutcome, ToolPreflight } from './sse'
import type { FormSpec, FormValueIssue } from './form-schema'
import type { ResultView } from './resultview'
import type { SheetToolMeta } from '@/utils/sheet-tools'
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
  /**
   * 表格采样元数据（AI 表格工具治理批；additive，可空 = 非表格工具/旧调用方）。
   *
   * <p>后端按白名单过滤后才落日志与台账：**任何业务值（单元格原文 / 条件取值 /
   * scopeSummary）在这里都会被丢弃**，不是靠调用方自觉。
   */
  sheetMeta?: SheetToolMeta
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
  /**
   * 模型配置专项批①（additive）：当前默认目标的 provider 身份。
   *
   * <p>
   * 批① 复审登记 N3 的口径：快照里没有可用 provider 时，`model`/`baseUrl` 会回落 env 回读，
   * 而 `providerId`/`defaultModelId` 为 null —— 消费者**先判 `providerId == null`** 再决定
   * 要不要相信 `model`（否则会把"env 回读值"当成"配置库里的默认模型"）。
   */
  providerId?: number | null
  defaultModelId?: string | null
  /** 逐 provider 的 key 状态（永不含 key 本身）；合成行 synthetic=true / id=null。 */
  providers?: AiHealthProvider[]
  /**
   * 表格工具开关的有效值 + 进程内计数（AI 表格工具治理批，additive）。
   *
   * <p>
   * `sampleMaxRows` / `projectionMaxColumns` 是**生效值**（已按硬顶收敛）：前端据此收紧采样上界
   * `min(args.limit, sampleMaxRows)`。缺该块（旧后端 / 未装配）⇒ 用硬编码常量兜底，
   * 与治理批之前的行为逐字等价（不引入"健康检查失败就不能用表格"的新故障面）。
   */
  sheet?: AiSheetHealth
}

/** `/health.sheet`（配置面 + 计数面）。四个配置键与 `AiProperties.Sheet` 同源。 */
export interface AiSheetHealth {
  readEnabled: boolean
  writeEnabled: boolean
  /** 有效采样行数上界（= min(配置值, 20)） */
  sampleMaxRows: number
  /** 有效投影列数上界（= min(配置值, 5)；选区路径不适用） */
  projectionMaxColumns: number
  /** 取值来源（当前恒 config） */
  effectiveFrom?: string
  /** 进程内计数（多实例下各实例视图；不作审计结论） */
  counters?: Record<string, number | string>
}

/**
 * `/health.providers[]` 的一行（M5：设置页据此判断"env 兜底还没落库"）。
 *
 * 合成行 = 表为空但 env 有 key 时临时合成的种子行：`id=null`、`source=ENV_SEED`、`synthetic=true`。
 * 全局 Jackson `default-property-inclusion=non_null` 可能省掉 `id` 键 ⇒ **认 `synthetic`**，不要认 `id`。
 */
export interface AiHealthProvider {
  id?: number | null
  code: string
  name: string
  baseUrl?: string
  enabled: boolean
  keyConfigured: boolean
  keyRef: string
  source: string
  /** true = env 兜底合成行（尚未写入配置库） */
  synthetic?: boolean
  /** OK / MISSING / UNREADABLE / DISABLED */
  status: string
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
  /**
   * 批G · S4：这一条 assistant 是**被用户停止**的半截正文（后端 {@code stopped} 元数据透出）。
   * 前端据此渲染「已停止」，而不是把半句话当完整答案。
   */
  stopped?: boolean
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
  /**
   * 批G · S3：本轮被用户**手工停止**（消息尾出「已停止」标识）。
   *
   * <p>
   * 与 {@link errorCard} / 断流提示互斥：一条被停的回答不是"出错"，也不是"完整答案"。
   * 实时路（`done{cancelled}` 帧 / `stop()` 本地收口）与刷新路（后端 history 的 `stopped`）
   * 都写这个字段；**入镜像**（刷新后标记仍在），不入历史载荷之外的任何持久化。
   */
  stopped?: boolean
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
   * S6 批 2：工具结果的视图通道（`tool_result.resultView` 落段，或发布冲突检出时由前端
   * 确定性构造后写段）。随段持久化 —— 镜像/历史恢复后卡面表格仍在（设计稿 §6.3-1/2）。
   *
   * 缺省 = 旧段（无视图）⇒ 卡面走旧渲染路径（发布冲突仍按 reason 反解，§7.1 的最后一步才删）。
   */
  resultView?: ResultView
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

// ── AI 表格操作专项：写预览计划（设计稿 §3.3bis / §3.5） ─────────────────────

/** 预览三档：count（0 行业务值）/ sample-diff（≤20 行）/ full-diff（0 行经模型，只在前端卡渲染）。 */
export type SheetPreviewLevel = 'count' | 'sample-diff' | 'full-diff'

/**
 * 计数档的分布（AI 表格工具治理方案 2026-10-22 §6.4）。
 *
 * <p>
 * `rows` 与 `cells` 必须都显示：一 row 多列命中时二者不等，只报格数会低估影响面。
 * 键是<b>业务值</b>（D2），但**只在浏览器渲染、不回灌**（`planAppliedText` 不含本块）——
 * 一旦回灌，模型侧的 D2 体积就多一块，而模型此刻正在等它确认，本就不需要分布。
 */
export interface SheetDistribution {
  /** 分组列（字段编码） */
  by: string
  /** 字段中文标签（有则显示，无则只显示 code） */
  byLabel?: string
  /** 前 5 组（按 rows 降序，同值按键字典序） */
  groups: { key: string; rows: number; cells: number }[]
  /** 被折叠的组数（0 = 无折叠；不含「其他」这一组本身） */
  otherGroups: number
  /** 组数是否超过内部上限 20（超过即只统计前 20 组，"其余"不再细分） */
  truncated: boolean
}

/** 单个单元格改动（预览 / 反向补丁共用的最小单元）。 */
export interface SheetChange {
  /** 数据行下标（0 基；文案转 Excel 行号） */
  row: number
  /** 列下标（0 基） */
  col: number
  oldValue: unknown
  newValue: unknown
}

/**
 * 写预览计划：模型只签一张“变更集”（planId + 计数 + 分布），业务行值留在浏览器。
 *
 * `changes` 只供前端卡渲染（P2 全量 diff），**不得**进入回灌文本；
 * `fingerprint` 与 `targetRows` 一一对应（行指纹，应用前逐行复核）。
 */
export interface SheetWritePlan {
  planId: string
  toolCallId: string
  defCode: string
  tool: string
  level: 'small' | 'large'
  preview: SheetPreviewLevel
  scopeSummary: string
  predicateSummary: string
  rowsCount: number
  cellsCount: number
  skippedCount: number
  samples: SheetChange[]
  changes: SheetChange[]
  targetRows: number[]
  fingerprint: string[]
  warnings: string[]
  rulesText: string
  status: 'pending' | 'applied' | 'cancelled' | 'stale'
  /**
   * 明细粒度（AI 表格工具治理方案 §6.2 的 P0 快路径）：
   * `none` = 计数档，**没有物化 changes**（`changes` 恒空、`targetRows`/`fingerprint` 恒空）；
   * `full` = 已物化逐格明细（sample-diff / full-diff）。
   *
   * <p>
   * 为什么必须有这个字段：count 档此前也走"先物化全量再判 10 万上限"，300 万格会先建
   * 300 万个对象再拒绝。快路径只数不建，但"没有明细"这件事必须显式化 —— 否则
   * {@code changes.length === 0} 会被误读成"没有命中"，而"应用"会静默改 0 格。
   */
  detail: 'none' | 'full'
  /**
   * 计数档的分布（§6.3）。`undefined` = 无命中 / 无分组列（不渲染空块）。
   *
   * <p>只在卡面渲染，**不进回灌**（P-06）：分布键是业务值，回灌等于白送一块 D2 体积。
   */
  distribution?: SheetDistribution
  /**
   * 本计划的入参快照（切档重算用）：`setPreviewLevel` 用它把同一份计划重跑成另一档，
   * `planId` 保持不变（撤销补丁按 planId 绑定，换 id 会让撤销指向不存在的计划）。
   *
   * <p><b>只在浏览器内存</b>：可能含 predicate 的取值，绝不进回灌、不进日志。
   */
  args?: Record<string, unknown>
  /** 计划生成时的工作区版本号（切档重算前比对，变了即"预览失效"，不静默套用旧结果）。 */
  sheetVer?: number
  /** 所属轮次（store 回灌用；纯函数计划不带） */
  runId?: string
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
  /** 见 {@link ToolPart.resultView}（S6 批 2 视图通道，随段持久化） */
  resultView?: ResultView
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
  messages: Array<Pick<ChatMessage, 'id' | 'role' | 'content' | 'parts' | 'reasoning' | 'toolRuns' | 'pendingCall' | 'stopped'>>
  /** 最近一轮的 runId（刷新后据此静默续收在途轮） */
  lastRunId?: string | null
  /**
   * 批G · G4：写入时该轮**是否仍在途中**（`loading`）。
   *
   * <p>
   * 刷新侧唯一的"这一轮还没收尾"判据：`messages` 里没有 `done` 字段（写入侧本来就不存），
   * 而"最后一条助手消息是不是流式"恰恰决定要不要静默续收。
   */
  inFlight?: boolean
  /**
   * 批G · G4：各 runId 已收到的最大帧序号（刷新后若走增量续收，服务端据此只补差量）。
   * 缺省 = 序号未知（走全量重放）。
   */
  runSeq?: Record<string, number>
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
  /**
   * GF 优化批（G-B）：**浏览器草稿快照**（sessionStorage 镜像 `cm.ai.form.draft.<toolCallId>`）。
   * 只在本机读写：永不进模型上下文、永不写服务器；提交/取消/终态即清。
   */
  values: Record<string, unknown>
  /**
   * GF 优化批（G-A）：后端回灌拒绝的结构化错误（`{key, code, message}`），
   * 前端按 `key` 点亮对应字段；`code` 决定是否可重试。
   */
  fieldErrors: FormValueIssue[]
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
  /**
   * L0（2026-10-21）：该条目的请求帧（`confirm_request` / `frontend_tool_request`）**真的发过**。
   *
   * 一批多调用挂起时请求帧是逐条发的，`pendingEntries` 却是整批一起落库的 —— `req === false`
   * 的条目（同批尚未轮到）刷新恢复时不建卡，等实时帧到达，否则会画出一张"后端还没走到、
   * 请求帧还没发出"的卡并被提前点击。
   * M-2b（2026-10-22）：**缺键 = 升级前遗留的孤儿轮**（无法判定），按可建处理（无感恢复）；
   * 已决条目恒 false。
   */
  req?: boolean
  /** 上述置位时刻（服务端钟；null/缺键 = 未发射或旧数据） */
  reqAtMs?: number | null
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

// ── 上下文占用（上下文统计与压缩专项批①：GET /api/ai/context/{sessionId}） ────────────

/**
 * `done.context` 的形状（真值来源在帧类型里，故定义在 `types/sse.ts` 的 `SseDoneFrame` 边上，
 * 这里转出给 store/组件用 —— 避免 store 为了一个类型去 import 帧模块的整个判别联合）。
 */
export type { ContextUsage } from './sse'

/** 分项键（顺序以服务端返回为准；前端只按返回顺序渲染，不重排）。 */
export type ContextBreakdownKey = 'history' | 'toolResults' | 'summary' | 'system' | 'tools' | 'input' | 'free'

export interface ContextBreakdownItem {
  key: ContextBreakdownKey | string
  /** 已用项为归一后的估算（Σ 前六项 == usedTokens）；`free` 为 `limit − used`，可为负 */
  tokens: number
}

/** 压缩态（批① 只有 `level=turns=lostTokens=0` 与 `circuit=closed`；字段先定形状，批③ 填值）。 */
export interface ContextCompaction {
  level: string
  compactedTurns: number
  lostTokens: number
  lastAtMs: number | null
  reason: string | null
  circuit: string
  fuseFails: number
}

/**
 * 上下文快照端点载荷（刷新恢复 / 展开分项 / 挂起态的唯一数据源）。
 *
 * `source` 三态：`stream`（真值）/ `estimate`（估算）/ `none`（会话从未记账 —— 此时
 * `percent` 为 null，界面不得显示 0%）。
 */
export interface ContextSnapshot {
  sessionId: string
  model: string | null
  turnNo: number
  measuredAtMs: number | null
  limitTokens: number | null
  usedTokens: number
  percent: number | null
  source: 'stream' | 'estimate' | 'none' | string
  estimated: boolean
  breakdown: ContextBreakdownItem[]
  compaction: ContextCompaction
  /** `?ledger=1` 时才返回（最近 20 条压缩事件） */
  ledger?: Record<string, unknown>[]
}

/* ────────────────────────────────────────────────────────────────────────────
 * 批③（模型配置 · 对话面板 + 契约）：**尾部追加**
 *
 * 为什么用「同名 interface 合并」而不是回到文件中部改 `AiChatRequest` / `AiHealth`：
 * 本文件与表格专项共享（对方在同一文件的工具类型段追加 Sheet*）。把本批的新增全部落在文件末尾，
 * 两批合并就不存在行号/lint 竞争 —— TypeScript 的 declaration merging 让同名的 interface
 * 在同一个模块里自然合并，类型面与"就地加两个可选字段"完全等价。
 * ──────────────────────────────────────────────────────────────────────────── */

/** 一轮对话的三项参数覆盖（键名与后端 `AiChatRequest.Params` 的 record 组件名逐字一致）。 */
export interface AiChatParams {
  temperature?: number | null
  topP?: number | null
  maxTokens?: number | null
}

/**
 * `POST /api/ai/chat` 的批③ 增量（可选）。
 *
 * - `modelId` 缺省 ⇒ 后端按默认解析链（`is_default` → 首个 enabled → 全局默认 → env）；
 * - `params` 缺省 ⇒ 全部用全局默认。
 *
 * <p><b>批 F · F1</b>：两个键都只是<b>后端能力</b>的镜像 —— 界面已无模型选择与参数弹层，
 * 前端请求体<b>既不带 `modelId` 也不带 `params`</b>（字段保留，重做选择器/调参时零后端改动）。
 */
export interface AiChatRequest {
  modelId?: string
  params?: AiChatParams
}

/**
 * `/health` 的批③ 增量（additive）。
 *
 * - `allowedParams`：`ai_setting['allow.userParams']`，缺省 = true；
 * - `defaultParams`：全局默认三值（null = 该项未设置）。
 */
export interface AiHealth {
  allowedParams?: boolean
  defaultParams?: AiChatParams
}
