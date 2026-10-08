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
   * DC-14 T4：挂起等待的**绝对**到期时刻（epoch 毫秒 = 挂起时刻 + timeoutSeconds）。
   * 倒计时以它为准（绝对时钟不受前端本地时间漂移与重挂延迟影响）；缺字段时回落到 timeoutSeconds。
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
  /** DC-14 T4 同口径：绝对到期时刻（epoch 毫秒） */
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

/** 挂起期心跳（证明连接存活 + 前端倒计时节拍）。键集不固定。 */
export interface SseHeartbeatFrame extends SseFrameBase {
  type: 'heartbeat'
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

/** 帧是否属于"挂起等待"口径（N6 裁决：suspended/confirm_request=挂起等待）。 */
export function isSuspendedFrame(frame: SseFrame): boolean {
  return frame.type === 'suspended'
    || frame.type === 'confirm_request'
    || frame.type === 'frontend_tool_request'
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
export type TaskEventType = 'HEARTBEAT' | 'TASK_CHANGED' | 'JOB_PROGRESS' | 'JOB_DONE'

/** TASK_CHANGED 的 data（TaskSseService#onTaskChanged）。 */
export interface TaskChangedPayload {
  taskId: number
  version: number
  currentStep: string
  status: string
  /** 动作摘要（如"跳转到步骤: EXPORT"）—— 前端操作流水的来源 */
  summary: string
}

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
  data: TaskChangedPayload | JobProgressPayload | JobDonePayload | Record<string, never>
}
