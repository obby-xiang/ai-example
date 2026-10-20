/**
 * SSE 帧契约（判别联合，全部以 `type` 字段判别）。
 *
 * 核对源：backend/.../ai/run/SseChatEmitter.java（帧构造现场）、
 *         ai/run/PendingToolCall.java、ai/web/AiController.java
 *         task/service/TaskSseService.java（任务级事件通道，形态不同，见本文件后半）
 *
 * 形态说明：后端用**无名 SSE 事件**，把 JSON 放进 data 字段，`type` 在帧内
 * （SseChatEmitter#emit → objectMapper.writeValueAsString(frame)），
 * 因此前端只需解析 data 文本，不必 addEventListener 按事件名分派。
 *
 * 与规格书 §3 的差异（核对结论）：规格书按简写列了 `tool_call`，
 * 后端实际是 **两帧**：`tool_start`（工具开始执行）与 `tool_result`（执行结束），
 * 另有 `confirm_decision` / `frontend_tool_request` / `frontend_tool_result` / `retry`。
 * 本文件按后端实测帧集定义。
 */

/** 挂起种类（PendingToolCall.KIND_*）。 */
export type ToolKind = 'CONFIRM' | 'FRONTEND' | 'BACKEND'

/**
 * 工具结局维度（治理 P0-1 统一结果信封的 `outcome` 取值域；
 * 依据 `docs/research/调研-AI工具执行治理与互斥-2026-10-19.md` §6.4 P0-1）。
 *
 * 与 `ok` 的关系：`ok` 是旧协议的布尔二元、语义只覆盖"执行是否成功"；`outcome` 是八态
 * 真实结局，`ok:true` 并不蕴含 `outcome:'ok'`（业务失败但被异常处理器吞成文本时，后端按本字段
 * 如实报 `failed`）。**旧帧没有本字段 ⇒ 前端必须回落到 `ok` 三元**，不得臆造默认值。
 *
 * `skipped`（契约增量，与后端已锁定）：上游确定性失败导致本工具**未执行**；信封 `code`
 * 通常为 `UPSTREAM_FAILED`。它是一等状态 —— 不是 `failed`，也不是"空"。
 */
export type ToolOutcome = 'ok' | 'failed' | 'rejected' | 'blocked' | 'timeout' | 'cancelled' | 'unknown' | 'skipped'

/** 挂起条目状态（PendingToolCall 的状态常量）。 */
export type PendingStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'TIMEOUT'
  | 'FRONTEND_RESULT'
  /** T2a-D#2（C2）：前端工具取消终态（用户放弃/渲染器不可用），结局帧 status 实发值 */
  | 'FRONTEND_CANCELLED'
  /** 整轮取消（ConfirmGate#cancel 落地）实发值；T2a 返修（S1）起映射到 cancelled 终态 */
  | 'CANCELLED'
  | 'EXECUTED'
  /** 入参被 schema 安全闸拒绝（SpToolCallingManager#rejectFrontendArguments）实发值，T2a 返修（S1）补入 */
  | 'REJECTED_ARGUMENTS'
  /** DC-14 T1：防线③（执行兜底）拦截 —— 越 scope 的工具调用未执行 */
  | 'BLOCKED'
  /** 契约增量：上游确定性失败导致本工具未执行（一等状态，对应 outcome=`skipped`） */
  | 'SKIPPED'

/** 外置挂起条目（PendingToolCall#asMap 的字段集）。 */
export interface PendingToolCallSnapshot {
  toolCallId: string
  name: string
  /** AssistantMessage.ToolCall.type() 原文（OpenAI 协议下为 'function'） */
  type: string
  kind: ToolKind
  /** 工具入参 JSON **字符串** */
  arguments: string
  status: PendingStatus | string
  reason?: string | null
  resultText?: string | null
  executed: boolean
  executedBy?: string | null
  claimedAtMs?: number
  executedAtMs?: number
  requestedAtMs?: number
  resolvedAtMs?: number
  /** 治理 P0-1：条目结局八态（可选，旧载荷没有；含契约增量的 skipped） */
  outcome?: ToolOutcome
  /** 治理 P0-1：错误码（可选，null = 无码） */
  code?: string | null
}

/** 全部帧类型常量（顺序即后端产出顺序语义）。 */
export const SseFrameTypes = [
  'start',
  'message_start',
  'delta',
  'message_end',
  'tool_start',
  'tool_result',
  'confirm_request',
  'confirm_decision',
  'frontend_tool_request',
  'frontend_tool_result',
  'suspended',
  'heartbeat',
  'retry',
  'done',
  'error'
] as const

export type SseFrameType = (typeof SseFrameTypes)[number]

/** 前向兼容的思考链帧名（裁决⑦；后端当前不产出，故不在上面的实测白名单里）。 */
export const REASONING_FRAME_TYPE = 'reasoning'

export interface SseFrameBase {
  type: SseFrameType
  runId?: string
  /**
   * 帧的单调序号（DC-14 T6，后端每帧递增，重启续跑后接着归档里的最大值继续）。
   * reattach 时回传 `?lastSeq=N` 即只补差量；旧帧可能没有该字段（按"照收"处理）。
   */
  seq?: number
  /**
   * T4-3：帧的**服务端构造时刻**（epoch 毫秒；后端帧构造点 `SseChatEmitter#frame` 取值）。
   * **实时帧与归档 / 回放帧都带本字段**（同一份帧：`RunStore#appendEvent` 的补入只是兜底），
   * 故前端按可选字段处理即可 —— 仅旧格式帧 / 损坏数据缺失。
   * 与 `expiresAt` 同一服务端钟、同一帧内单点取值 ⇒ 二者之差精确等于 `timeoutSeconds * 1000`，
   * 即服务端给出的**剩余时长**（T4-3 后半）。
   */
  atMs?: number
}

/** 首包：runId 由服务端生成，前端据此做 reattach。 */
export interface SseStartFrame extends SseFrameBase {
  type: 'start'
  runId: string
  sessionId: string
}

/** 正文增量（生成中）。 */
export interface SseDeltaFrame extends SseFrameBase {
  type: 'delta'
  text: string
  /** DC-14 T5：本片正文所属的助手消息 id（消息级渲染/去重的判据） */
  messageId?: string
}

/**
 * 消息边界帧（DC-14 T5 / R8-2 的三段式：`message_start` → `delta`* → `message_end`）。
 *
 * 为什么需要它：一条助手消息可能"先思考、再正文、中间夹工具调用"，只有 content 帧时
 * 客户端无法判断两段文本属于同一条消息还是两条（SP-01ab §7.3 的混合消息渲染痛点）。
 * `kind` 区分文本段（`text`）与思考段（`reasoning`，后端今天不产 reasoning 帧，故实际只会出现 text）。
 */
export interface SseMessageBoundaryFrame extends SseFrameBase {
  type: 'message_start' | 'message_end'
  messageId: string
  kind: 'text' | 'reasoning' | string
  /** 仅 message_end：本段字符数（与 done 帧对账用） */
  chars?: number
}

/** 工具开始（后端执行或挂起前的公告）。 */
export interface SseToolStartFrame extends SseFrameBase {
  type: 'tool_start'
  toolCallId: string
  name: string
  kind: ToolKind
  /** 入参 JSON 字符串 */
  args?: string
}

/** 工具执行结束。 */
export interface SseToolResultFrame extends SseFrameBase {
  type: 'tool_result'
  toolCallId: string
  name: string
  kind: ToolKind
  ok: boolean
  executed: boolean
  executedBy?: string | null
  reused?: boolean
  /** 待决条目的最终状态（DC-14 T1：BLOCKED = 被防线③拦下，未执行） */
  status?: PendingStatus | string
  /** DC-14 T5：结果铸为消息后的消息 id（`tool-<toolCallId>`，重放/重挂不变） */
  messageId?: string
  result?: string | null
  /**
   * R-154 #2：单帧 64KB 字段级截断标记（后端 FrameSizeLimiter 的 TRUNCATED_FIELD /
   * TRUNCATED_REASON_FIELD）——result 被帧预算截断时挂 true 与原因；缺省 = 未截断（全文）。
   */
  truncated?: boolean
  truncatedReason?: string | null
  /**
   * 治理 P0-1（统一结果信封）：结局八态。**可选** —— 旧后端帧没有该字段，此时前端按
   * `ok` 三元回落（{@link ToolOutcome}）。带该字段时它**优先于 `ok`**：业务失败但被
   * 异常处理器吞成文本的场景下 `ok` 仍为 true，只有 `outcome` 如实报 `failed`。
   */
  outcome?: ToolOutcome
  /** 治理 P0-1：错误码（可选，null = 无码）；一期只落段/展示二期，卡面不展示 */
  code?: string | null
  /** 治理 P0-1：是否可重试（可选；旧帧没有）；一期仅契约透传，不参与卡面判定 */
  retryable?: boolean
  /** 治理 P0-1：目标资源（可选，null = 未声明）；一期仅契约透传 */
  target?: Record<string, unknown> | null
  /**
   * 信封人读文本（可选；SKIPPED 时由后端给出「未执行：上游 start_import 失败…」一类说明）。
   * 卡面 reason 行在 skipped 时直接展示本字段。
   */
  message?: string | null
}

/** HITL 确认请求（挂起等待口径）。 */
export interface SseConfirmRequestFrame extends SseFrameBase {
  type: 'confirm_request'
  toolCallId: string
  name: string
  args?: string
  /** 与 args 同值（后端同时放了两份，供展示摘要用） */
  summary?: string
  timeoutSeconds: number
  /**
   * DC-14 T4：挂起等待的**绝对**到期时刻（epoch 毫秒 = 帧内 `atMs` + timeoutSeconds，服务端单点取钟）。
   *
   * **R-166 口径变更**：本字段不再驱动门卡倒计时 —— 原 T4-3 的 `expiresAt − atMs` 差值折算在
   * 重派生 / 刷新恢复时会给出"完整等待时长"从而把倒计时打回起点（本批修复的症②）。倒计时现由
   * `stores/ai.ts` 的 `ConfirmClock` 承载：**实时帧新鲜时锚定一次**（折算实现 `frameDeadlineLocal`），
   * 之后由挂起期心跳校正（`heartbeatDeadlineLocal`）。本字段随帧照原样透出，供对账 / 回滚。
   */
  expiresAt?: number
  /** 回调端点说明文本：POST /api/ai/confirm {runId, toolCallId, approved, reason} */
  callback?: string
}

/** HITL 确认决策回执（放行/拒绝双路径都走这里）。 */
export interface SseConfirmDecisionFrame extends SseFrameBase {
  type: 'confirm_decision'
  toolCallId: string
  name: string
  /** 决策原文（如 APPROVED/REJECTED/TIMEOUT） */
  decision: string
  approved: boolean
  status: PendingStatus | string
  reason?: string | null
  waitedMs?: number
}

/** 前端工具请求（副作用在浏览器：打开编辑器 / 触发下载）。 */
export interface SseFrontendToolRequestFrame extends SseFrameBase {
  type: 'frontend_tool_request'
  toolCallId: string
  name: string
  args?: string
  timeoutSeconds: number
  /** DC-14 T4 同口径：绝对到期时刻（epoch 毫秒）；T4-3 后半起帧恒带 `atMs`，按 `expiresAt − atMs` 剩余时长折算，见 `SseConfirmRequestFrame.expiresAt` */
  expiresAt?: number
  /** 回调端点说明文本：POST /api/ai/frontend-tool-result {runId, toolCallId, result} */
  callback?: string
}

/** 前端工具结果回执。 */
export interface SseFrontendToolResultFrame extends SseFrameBase {
  type: 'frontend_tool_result'
  toolCallId: string
  name: string
  ok: boolean
  executed: boolean
  /** 待决条目的最终状态（BLOCKED = 被防线③拦下） */
  status?: PendingStatus | string
  /** DC-14 T5：结果铸为消息后的消息 id */
  messageId?: string
  result?: string | null
  /**
   * 治理 P0-1（统一结果信封，I1 收口）：结局八态，契约同 `tool_result` 帧。**可选** ——
   * 旧后端帧没有该字段（此时前端走原 `status` 旧表 + ok 三元）；带该字段时**优先于**
   * `status`/`ok`（`status='FRONTEND_RESULT'` 不再把 `ok=false` 盖成成功）。
   */
  outcome?: ToolOutcome
  /** 治理 P0-1：错误码（可选，null = 无码）；一期不参与卡面判定 */
  code?: string | null
  /** 治理 P0-1：是否可重试（可选；旧帧没有）；一期仅契约透传 */
  retryable?: boolean
  /** 治理 P0-1：目标资源（可选，null = 未声明）；一期仅契约透传 */
  target?: Record<string, unknown> | null
  /** 信封人读文本（可选；SKIPPED 时卡面 reason 行直接展示本字段） */
  message?: string | null
}

/** 挂起公告（快照已外置 Redis，带键名与写入者实例，供"可重挂"提示）。 */
export interface SseSuspendedFrame extends SseFrameBase {
  type: 'suspended'
  runId: string
  toolCalls: PendingToolCallSnapshot[]
  stateKey?: string
  pendingKey?: string
  writtenBy?: string
}

/**
 * 心跳帧（挂起期保活 + **R-166 起为门卡倒计时的主驱动**；流式期只保活）。键集不固定。
 *
 * 挂起期载荷见后端 `ConfirmGate.heartbeatData`（`runId` / `suspendKind` / `toolCallId` /
 * `name` / `status` / `waitedSeconds` / `timeoutSeconds`）——**两个秒数同帧单点取值**，
 * 故 `timeoutSeconds − waitedSeconds` 就是该帧时刻的服务端权威剩余秒。
 * 流式期心跳（`ResilientChatService`）不带这四个键，前端据此区分两条心跳通道。
 */
export interface SseHeartbeatFrame extends SseFrameBase {
  type: 'heartbeat'
  /** 挂起种类：`confirm`（确认门）/ `frontend-tool`（前端工具）；流式期心跳无此键 */
  suspendKind?: string
  /** 本条挂起条目的 toolCallId（流式期心跳无此键 ⇒ 不参与倒计时） */
  toolCallId?: string
  /** 挂起等待上限（秒） */
  timeoutSeconds?: number
  /** 已等待秒数（服务端钟，与 `timeoutSeconds` 同帧单点取值） */
  waitedSeconds?: number
  [key: string]: unknown
}

/**
 * 思考链增量（裁决⑦：**有帧才渲染**，无帧不显示空的"思考过程"折叠块）。
 *
 * 与后端实测帧集的差异（如实登记，不臆造）：`SseChatEmitter` **当前不产出该帧**
 * —— deepseek-flash 的 `reasoning_content` 只做回填补丁（ADR-4），不透传前端。
 * 故它**不在** {@link SseFrameTypes}（后端 13 类实测白名单）里，但前端按前向兼容解析：
 * 后端一旦补上该帧，面板的思考链折叠即自动生效，前端契约无需再改。
 */
export interface SseReasoningFrame {
  type: 'reasoning'
  runId?: string
  /** 思考链增量文本 */
  text: string
}

/** 断流重试公告（本轮无正文产出时才会重试）。键集不固定。 */
export interface SseRetryFrame extends SseFrameBase {
  type: 'retry'
  [key: string]: unknown
}

/** 终帧：done（正常完成或取消共用，cancelled=true 表示取消）。 */
export interface SseDoneFrame extends SseFrameBase {
  type: 'done'
  runId: string
  usage?: Record<string, unknown>
  model?: string
  cancelled?: boolean
  attempts?: number
  upstreamEvents?: number
  terminalSignal?: string
}

/** 终帧：error。 */
export interface SseErrorFrame extends SseFrameBase {
  type: 'error'
  runId: string
  code: string
  message: string
}

/** 一轮对话的 SSE 帧判别联合（后端 15 类 + 前向兼容的 reasoning）。 */
export type SseFrame =
  | SseStartFrame
  | SseMessageBoundaryFrame
  | SseDeltaFrame
  | SseReasoningFrame
  | SseToolStartFrame
  | SseToolResultFrame
  | SseConfirmRequestFrame
  | SseConfirmDecisionFrame
  | SseFrontendToolRequestFrame
  | SseFrontendToolResultFrame
  | SseSuspendedFrame
  | SseHeartbeatFrame
  | SseRetryFrame
  | SseDoneFrame
  | SseErrorFrame

/** 终帧判定：只有 done / error 是终帧（缺终帧 = 断流，按 ADR-6 提示）。 */
export function isTerminalFrame(frame: SseFrame): frame is SseDoneFrame | SseErrorFrame {
  return frame.type === 'done' || frame.type === 'error'
}

/** 运行时校验：把任意 JSON 值收窄为 SseFrame（type 命中后端白名单或前向兼容帧）。 */
export function toSseFrame(value: unknown): SseFrame | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const candidate = value as { type?: unknown }
  if (typeof candidate.type !== 'string') {
    return null
  }
  if (candidate.type === REASONING_FRAME_TYPE) {
    return value as SseReasoningFrame
  }
  return (SseFrameTypes as readonly string[]).includes(candidate.type) ? (value as SseFrame) : null
}

/**
 * 帧序号的读取（DC-14 T6）：缺字段/非数字一律返回 null（老帧按"照收"处理，不参与孤儿过滤）。
 */
export function frameSeq(frame: SseFrame): number | null {
  const candidate = frame as { seq?: unknown }
  return typeof candidate.seq === 'number' && Number.isFinite(candidate.seq) ? candidate.seq : null
}

// ── 任务级事件通道（GET /api/tasks/{id}/events，形态与 AI 帧不同） ──────────────

/**
 * 任务级帧类型（TaskSseService：大写 type + 包一层 data）。
 *
 * `JOB_PROGRESS` 由各作业执行器在分片边界发布
 * （ExportJobRunner/ImportJobRunner/PrecheckJobRunner/PublishJobRunner#publishProgress），
 * 载荷是**行级** processed/total + defCode + 百分比，是向导"SSE 进度"的数据来源。
 */
export type TaskEventType = 'HEARTBEAT' | 'JOB_PROGRESS' | 'JOB_DONE'

/** JOB_DONE 的 data（JobService#cancel 等发布点）。 */
export interface JobDonePayload {
  jobId: number
  status: string
}

/** JOB_PROGRESS 的 data（作业执行器 publishProgress）。 */
export interface JobProgressPayload {
  jobId: number
  jobType: string
  /** 完成一个配置项时带出的配置编码 */
  defCode: string
  processed: number
  total: number
  pct: number
}

/** 任务级 SSE 帧。 */
export interface TaskEventFrame {
  type: TaskEventType
  data: JobProgressPayload | JobDonePayload | Record<string, never>
}
