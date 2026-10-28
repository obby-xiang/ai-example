/**
 * API 通用契约（分页包装 / 统一信封 / 错误码）。
 *
 * 字段名一律以后端 Java 实体的 Jackson 输出为准，本文件顶部记录核对来源。
 * 核对源：backend/src/main/java/com/example/configmgr/common/ApiResponse.java、
 *         common/ConflictException.java、common/GlobalExceptionHandler.java。
 */

/** 业务接口统一信封：`{ success, message?, code?, data? }`（@JsonInclude(NON_NULL)）。 */
export interface ApiResponse<T> {
  success: boolean
  message?: string
  /** 机器可读错误码，仅错误时出现（如 JOB_ALREADY_FINAL） */
  code?: string
  data?: T
}

/** Spring Data `Page<T>` 的 JSON 形态（Jackson 直出 PageImpl 的字段）。 */
export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  /** 当前页号（0 基） */
  number: number
  size: number
  numberOfElements: number
  first: boolean
  last: boolean
  empty: boolean
}

/** 分页查询入参（各域 client 的公共部分）。 */
export interface PageQuery {
  /** 0 基页号 */
  page?: number
  size?: number
}

/**
 * 前端归一化后的接口错误（axios 拦截器抛出）。
 * `code` 优先取响应体 `code`（业务冲突/AI 端点），否则为 HTTP 状态码的字符串形式。
 */
export interface ApiErrorBody {
  success?: boolean
  message?: string
  code?: string
  [key: string]: unknown
}

/** 错误码枚举（**镜像** `backend/.../common/ErrorCodes.java`，由 `ErrorCodesContractTest` 双向对账）。
 *
 * 维护口径：后端加码 ⇒ 本表同一批补一行（顺序与后端一致，便于 diff）；
 * 前端独有的只有下面显式标注的两枚网络层码（后端不发）。
 */
export const ErrorCode = {
  BAD_REQUEST: 'BAD_REQUEST',
  ILLEGAL_ARGUMENT: 'ILLEGAL_ARGUMENT',
  /**
   * 当前状态不允许该操作（409）。
   *
   * <p>批次2：`POST /api/ai/runs/{runId}/retry-model-step` 的前置拒绝也用它（**不新增码表项**），
   * 具体是哪一条不满足走响应体的 `failedCheck`（NOT_FAILED / CANCELLED / PENDING_TOOLS /
   * PARTIAL_CONTENT / TOOL_PAIRING_INCOMPLETE / MODEL_CHANGED），前端不得拿本码猜原因。
   */
  ILLEGAL_STATE: 'ILLEGAL_STATE',
  RESOURCE_NOT_FOUND: 'RESOURCE_NOT_FOUND',
  SESSION_BUSY: 'SESSION_BUSY',
  SESSION_PURGED: 'SESSION_PURGED',
  UNKNOWN_RUN: 'UNKNOWN_RUN',
  UNKNOWN_TOOL_CALL: 'UNKNOWN_TOOL_CALL',
  DUPLICATE_TOOL_CALL_ID: 'DUPLICATE_TOOL_CALL_ID',
  /**
   * 入口 kind 校验（409）：toolCallId 存在，但不属于这条入口
   * （`/confirm` 只收 CONFIRM，`/frontend-tool-result` 只收 FRONTEND）。
   * 条目与状态都未改变；前端应按 `expectedKind` 改走另一条接口。
   */
  TOOL_KIND_MISMATCH: 'TOOL_KIND_MISMATCH',
  SUSPEND_POOL_SATURATED: 'SUSPEND_POOL_SATURATED',
  AI_UNAVAILABLE: 'AI_UNAVAILABLE',
  AI_REDIS_UNAVAILABLE: 'AI_REDIS_UNAVAILABLE',
  AI_AVAILABLE: 'AI_AVAILABLE',
  RATE_LIMITED: 'RATE_LIMITED',
  MESSAGE_TOO_LONG: 'MESSAGE_TOO_LONG',
  /** 决策回灌的拒绝原因超长（只回数字，不回显原因原文） */
  REASON_TOO_LONG: 'REASON_TOO_LONG',
  AI_CONTEXT_UNAVAILABLE: 'AI_CONTEXT_UNAVAILABLE',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  TOOL_EXECUTION_FAILED: 'TOOL_EXECUTION_FAILED',
  TOOL_RESULT_MISSING: 'TOOL_RESULT_MISSING',
  TOOL_ALREADY_CLAIMED: 'TOOL_ALREADY_CLAIMED',
  SCOPE_NOT_DISCLOSED: 'SCOPE_NOT_DISCLOSED',
  CONFIRM_TIMEOUT: 'CONFIRM_TIMEOUT',
  FRONTEND_TIMEOUT: 'FRONTEND_TIMEOUT',
  /** 后端工具执行超时（超时面治理批②；等后端桩体，区别于 FRONTEND_TIMEOUT 等前端回执） */
  TOOL_TIMEOUT: 'TOOL_TIMEOUT',
  /** 工具调用步数到顶（超时面治理批②；skipped 结局，本轮部分完成收尾） */
  TOOL_STEP_LIMIT: 'TOOL_STEP_LIMIT',
  /** 轮内上下文预算耗尽（armed 后新工具不执行，skipped 结局） */
  TOOL_BUDGET_EXHAUSTED: 'TOOL_BUDGET_EXHAUSTED',
  RUN_CANCELLED: 'RUN_CANCELLED',
  /** 前端工具条目终态：用户取消/渲染器不可用（结局帧 status 口径，展示态登记） */
  FRONTEND_CANCELLED: 'FRONTEND_CANCELLED',
  UPSTREAM_FAILED: 'UPSTREAM_FAILED',
  PRECHECK_FAILED: 'PRECHECK_FAILED',
  /** 表格工具被管理员关闸（app.ai.sheet.*）：未执行、零副作用 */
  SHEET_DISABLED: 'SHEET_DISABLED',
  PRECHECK_UNAVAILABLE: 'PRECHECK_UNAVAILABLE',
  RESOURCE_LOCKED: 'RESOURCE_LOCKED',
  TARGET_INVALID: 'TARGET_INVALID',
  /** 同一轮第二次 create_task：本调用未创建（AR-03 单轮上限 1） */
  CREATE_TASK_TURN_LIMIT: 'CREATE_TASK_TURN_LIMIT',
  /** 同轮创建幂等冲突：本轮已创建过任务，本次未创建 */
  CREATE_TASK_CONFLICT: 'CREATE_TASK_CONFLICT',
  TOOL_NOT_PREFLIGHTABLE: 'TOOL_NOT_PREFLIGHTABLE',
  TOOL_ENVELOPE_SERIALIZE_FAILED: 'TOOL_ENVELOPE_SERIALIZE_FAILED',
  TOOL_ENVELOPE_PARSE_FAILED: 'TOOL_ENVELOPE_PARSE_FAILED',
  TOOL_RESULT_OUTCOME_MISMATCH: 'TOOL_RESULT_OUTCOME_MISMATCH',
  FORM_RESULT_REJECTED: 'FORM_RESULT_REJECTED',
  /**
   * 结构化前端回灌体超限（本地预检 + 后端兜底，用户 2026-10-25）。
   *
   * <p>与 `FORM_RESULT_REJECTED` 分开：后者是"值不符 schema"（改值可重试），本码只表达
   * "体积超过 max-chars"（缩短可重试）。前端在发送前用同一上限预检，正常情况下不会发出。
   */
  FRONTEND_RESULT_TOO_LARGE: 'FRONTEND_RESULT_TOO_LARGE',
  FORM_SCHEMA_REJECTED: 'FORM_SCHEMA_REJECTED',
  /** 表单要收集的槽位已由任务/工作区或用户明确值确定：冗余收集表单不下发（零副作用），改为直接调对应工具 */
  FORM_SLOTS_ALREADY_KNOWN: 'FORM_SLOTS_ALREADY_KNOWN',
  PRECHECK_NOT_PASSED: 'PRECHECK_NOT_PASSED',
  PRECHECK_STALE: 'PRECHECK_STALE',
  CONDITION_OPERATOR_UNKNOWN: 'CONDITION_OPERATOR_UNKNOWN',
  CONDITION_JSON_INVALID: 'CONDITION_JSON_INVALID',
  EXCEL_COLUMN_MAPPING_INVALID: 'EXCEL_COLUMN_MAPPING_INVALID',
  EXCEL_SHEET_NOT_FOUND: 'EXCEL_SHEET_NOT_FOUND',
  FILE_TYPE_UNSUPPORTED: 'FILE_TYPE_UNSUPPORTED',
  STEP_REQUIRED: 'STEP_REQUIRED',
  STEP_INVALID: 'STEP_INVALID',
  STEP_NOT_IN_SEQUENCE: 'STEP_NOT_IN_SEQUENCE',
  LEASE_KEY_INVALID: 'LEASE_KEY_INVALID',
  IMPORT_MODE_INVALID: 'IMPORT_MODE_INVALID',
  JOB_ALREADY_FINAL: 'JOB_ALREADY_FINAL',
  JOB_ALREADY_RUNNING: 'JOB_ALREADY_RUNNING',
  TASK_FINALIZED: 'TASK_FINALIZED',
  DEFINITION_CODE_DUPLICATE: 'DEFINITION_CODE_DUPLICATE',
  DEFINITION_HAS_DATA: 'DEFINITION_HAS_DATA',
  DEFINITION_IN_ACTIVE_TASK: 'DEFINITION_IN_ACTIVE_TASK',
  DEFINITION_REFERENCED: 'DEFINITION_REFERENCED',
  DEFINITION_FIELD_CODE_EMPTY: 'DEFINITION_FIELD_CODE_EMPTY',
  DEFINITION_FIELD_CODE_INVALID: 'DEFINITION_FIELD_CODE_INVALID',
  DEFINITION_FIELD_CODE_DUPLICATE: 'DEFINITION_FIELD_CODE_DUPLICATE',
  DEFINITION_FIELD_REFERENCE_INCOMPLETE: 'DEFINITION_FIELD_REFERENCE_INCOMPLETE',
  DEFINITION_PRIMARY_KEY_REQUIRED: 'DEFINITION_PRIMARY_KEY_REQUIRED',
  DEFINITION_DEPENDENCY_CYCLE: 'DEFINITION_DEPENDENCY_CYCLE',
  PROVIDER_NOT_FOUND: 'PROVIDER_NOT_FOUND',
  PROVIDER_EXISTS: 'PROVIDER_EXISTS',
  PROVIDER_IN_USE: 'PROVIDER_IN_USE',
  MODEL_NOT_FOUND: 'MODEL_NOT_FOUND',
  /** 503：provider/key 都在，但默认目标没有该 provider 自己的模型（不借别家的模型名） */
  NO_MODEL_CONFIGURED: 'NO_MODEL_CONFIGURED',
  MODEL_EXISTS: 'MODEL_EXISTS',
  MODELS_INVALID: 'MODELS_INVALID',
  MODELS_UNSUPPORTED: 'MODELS_UNSUPPORTED',
  INVALID_MODEL: 'INVALID_MODEL',
  INVALID_PARAM: 'INVALID_PARAM',
  /** 改主机名却保留旧 key：需重录或先清空（与 INVALID_PARAM 的通用校验分开） */
  KEY_BOUND_TO_HOST: 'KEY_BOUND_TO_HOST',
  INVALID_BASE_URL: 'INVALID_BASE_URL',
  BLOCKED_PRIVATE_ENDPOINT: 'BLOCKED_PRIVATE_ENDPOINT',
  AUTH_FAILED: 'AUTH_FAILED',
  UNREACHABLE: 'UNREACHABLE',
  CODE_IMMUTABLE: 'CODE_IMMUTABLE',
  AI_SECRET_KEK_MISSING: 'AI_SECRET_KEK_MISSING',
  AI_KEY_MISSING: 'AI_KEY_MISSING',
  /** 可用性 503：DB 托管 key 解不开（KEK 缺失/变更）；与配置写路径的 KEY_UNREADABLE 分工 */
  AI_KEY_UNREADABLE: 'AI_KEY_UNREADABLE',
  KEY_EMPTY: 'KEY_EMPTY',
  KEY_UNREADABLE: 'KEY_UNREADABLE',
  UPSTREAM_STREAM_ERROR: 'UPSTREAM_STREAM_ERROR',
  /** SSE error 帧：空回复（去空白后为空，轮次终态 FAILED） */
  EMPTY_REPLY: 'EMPTY_REPLY',
  /** 轮次终态：cancelled（done{cancelled:true}），不是 error 帧；永不重试 */
  AI_RUN_CANCELLED: 'AI_RUN_CANCELLED',
  /** SSE error 帧：上游首包超时 */
  UPSTREAM_FIRST_BYTE_TIMEOUT: 'UPSTREAM_FIRST_BYTE_TIMEOUT',
  /** SSE error 帧：上游事件间静默超时 */
  UPSTREAM_SILENT_TIMEOUT: 'UPSTREAM_SILENT_TIMEOUT',
  /** SSE error 帧：单轮总预算耗尽 */
  TOTAL_BUDGET_EXCEEDED: 'TOTAL_BUDGET_EXCEEDED',
  /** SSE error 帧：上游流提前结束（缺终帧） */
  UPSTREAM_STREAM_INCOMPLETE: 'UPSTREAM_STREAM_INCOMPLETE',
  /** SSE error 帧：上游连接中断（reset / EOF / premature close） */
  UPSTREAM_CONNECTION_BROKEN: 'UPSTREAM_CONNECTION_BROKEN',
  /** SSE error 帧：上下文超出模型窗口（重试无效，需压缩或新会话） */
  CONTEXT_OVERFLOW: 'CONTEXT_OVERFLOW',
  /** SSE error 帧：上游按"请求本身不成立"拒绝（400/404/409/422 等 4xx；不重试） */
  UPSTREAM_REJECTED: 'UPSTREAM_REJECTED',
  /** SSE error 帧：上游鉴权失败（401/403）；不重试，需检查/更新 API Key */
  UPSTREAM_AUTH_FAILED: 'UPSTREAM_AUTH_FAILED',
  /** SSE error 帧：上游限流（429）；有界重试并遵守 Retry-After */
  UPSTREAM_RATE_LIMITED: 'UPSTREAM_RATE_LIMITED',
  /** SSE error 帧：上游暂时不可用（408/5xx/网络层）；有界重试 */
  UPSTREAM_UNAVAILABLE: 'UPSTREAM_UNAVAILABLE',
  /** SSE error 帧：上游以 finish_reason=content_filter 收尾（明确失败，不重试） */
  CONTENT_FILTERED: 'CONTENT_FILTERED',
  /** 终局失败派生码（R-09）：基础码 + 已产出正文/工具副作用；用户文案沿基础码，码保留诊断 */
  UPSTREAM_STREAM_ERROR_AFTER_PARTIAL: 'UPSTREAM_STREAM_ERROR_AFTER_PARTIAL',
  UPSTREAM_STREAM_ERROR_AFTER_TOOL_SIDE_EFFECT: 'UPSTREAM_STREAM_ERROR_AFTER_TOOL_SIDE_EFFECT',
  UPSTREAM_CONNECTION_BROKEN_AFTER_PARTIAL: 'UPSTREAM_CONNECTION_BROKEN_AFTER_PARTIAL',
  UPSTREAM_CONNECTION_BROKEN_AFTER_TOOL_SIDE_EFFECT: 'UPSTREAM_CONNECTION_BROKEN_AFTER_TOOL_SIDE_EFFECT',
  UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL: 'UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL',
  UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT: 'UPSTREAM_STREAM_INCOMPLETE_AFTER_TOOL_SIDE_EFFECT',
  UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL: 'UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL',
  UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT: 'UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT',
  UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_PARTIAL: 'UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_PARTIAL',
  UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_TOOL_SIDE_EFFECT: 'UPSTREAM_FIRST_BYTE_TIMEOUT_AFTER_TOOL_SIDE_EFFECT',
  TOTAL_BUDGET_EXCEEDED_AFTER_PARTIAL: 'TOTAL_BUDGET_EXCEEDED_AFTER_PARTIAL',
  TOTAL_BUDGET_EXCEEDED_AFTER_TOOL_SIDE_EFFECT: 'TOTAL_BUDGET_EXCEEDED_AFTER_TOOL_SIDE_EFFECT',
  CONTEXT_OVERFLOW_AFTER_PARTIAL: 'CONTEXT_OVERFLOW_AFTER_PARTIAL',
  CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT: 'CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT',
  AI_RUN_FAILED: 'AI_RUN_FAILED',
  AI_RESUME_FAILED: 'AI_RESUME_FAILED',
  /** 续跑被确定拒绝（快照绑定的 provider/模型已不可用）：终态语义，重发一轮而不是重试续跑 */
  AI_PROVIDER_GONE: 'AI_PROVIDER_GONE',
  CONFIG_INVALID: 'CONFIG_INVALID',
  SHA256_UNAVAILABLE: 'SHA256_UNAVAILABLE',
  AI_SUMMARY_UNAVAILABLE: 'AI_SUMMARY_UNAVAILABLE',
  STORE_IO_FAILED: 'STORE_IO_FAILED',
  TASK_SETTINGS_UNREADABLE: 'TASK_SETTINGS_UNREADABLE',
  /** 前端独有：请求未到达后端（网络/代理不可达；后端不发此码） */
  NETWORK_ERROR: 'NETWORK_ERROR',
  /** 前端独有：非预期响应形态或未知异常（后端不发此码） */
  UNKNOWN_ERROR: 'UNKNOWN_ERROR'
} as const

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode]

/**
 * 结构化前端回灌体的字符上限（用户 2026-10-25「同一 max-chars 预检」）。
 *
 * <p>与后端同一把尺：`GenerativeFormRules.MAX_RESULT_CHARS`（8000）与
 * `app.result-budget.callback.max-chars`（默认 8000）。前端只做「不发出注定被拒的请求」，
 * 真正的拒绝仍在后端（本值不放大任何一侧）。
 */
export const STRUCTURED_RESULT_MAX_CHARS = 8000

/**
 * 409 SESSION_BUSY 的响应体形态。
 *
 * 契约（ADR-5）：**持有者可知时**必须携进行中轮的 `runId` 与 `reattach`。
 * **批次2 口径**：这两个字段只作面板「回到进行中的回答」**手动入口**的入参 —— 用户点击后由
 * store 做**一次**历史对账 + 静默续收（`reattachBusyRun`），**不自动重挂、不轮询**。
 * **持有者不可知**时（会话锁诊断读失败 / 锁已消失、无法给出 runId）后端**不编造**这两个字段：
 * 它们缺省、`unknown` 为 true，其余不变（仍是 409 + `SESSION_BUSY` + `degraded`）。
 * 此时**不得**自行拼接重挂路径（如 `/api/ai/events/null`），按既有重试/退避恢复即可（无粘滞）。
 */
export interface SessionBusyConflict {
  code: typeof ErrorCode.SESSION_BUSY
  message: string
  sessionId: string
  /** 进行中那一轮的 runId；持有者可知时必在，不可知时缺省 */
  runId?: string
  degraded?: boolean
  /** 重挂路径 `/api/ai/events/{runId}`；与 `runId` 同进同缺 */
  reattach?: string
  /** 持有者不可知（没有可指向的轮）；与 `runId`/`reattach` 互斥 */
  unknown?: boolean
}

/** 410 SESSION_PURGED：旧 sessionId 已销毁，必须走既有新建对话路径。 */
export interface SessionPurgedConflict {
  code: typeof ErrorCode.SESSION_PURGED
  message: string
  sessionId: string
  action: 'NEW_SESSION'
  note?: string
}

/** 503 SUSPEND_POOL_SATURATED 的响应体形态。 */
export interface SuspendPoolSaturated {
  code: typeof ErrorCode.SUSPEND_POOL_SATURATED
  message: string
  runId: string
  poolSize: number
}

/**
 * 503 AI_UNAVAILABLE / AI_KEY_MISSING / AI_KEY_UNREADABLE / AI_REDIS_UNAVAILABLE /
 * NO_MODEL_CONFIGURED 的响应体形态。
 *
 * <p>批次2：`AI_REDIS_UNAVAILABLE` **不是一个"可能成功"的信号**。终态暂不可确认时后端用
 * `error` 帧的 additive `unconfirmed=true`（+ `turn_end=unconfirmed`）表达，码仍是本值；
 * store 只认显式 `unconfirmed` 标记，绝不拿本码推断中性视图。
 */
export interface AiUnavailableBody {
  code:
    | typeof ErrorCode.AI_UNAVAILABLE
    | typeof ErrorCode.AI_KEY_MISSING
    | typeof ErrorCode.AI_KEY_UNREADABLE
    | typeof ErrorCode.AI_REDIS_UNAVAILABLE
    | typeof ErrorCode.NO_MODEL_CONFIGURED
  message: string
  /** 仅 AI_REDIS_UNAVAILABLE：安全探活事实（不含 lastError —— 原始诊断只在 /health 与日志） */
  redis?: Record<string, unknown>
}

/**
 * 发布冲突的**异步形态**（Q4）：发布请求恒 201，
 * 冲突表现为作业 FAILED + `GET /api/jobs/{id}/issues` 的文案，
 * 前端必须等作业终态后查 issues，禁止按同步错误处理。
 */
export interface PublishConflictAsync {
  jobId: number
  jobStatus: 'FAILED'
  /** 冲突判定依据：issues 里 severity=ERROR 的条目（文案含版本冲突说明） */
  issueKeys: string[]
  /** 冲突文案（由 issues 的第一条 ERROR 提供） */
  message: string
}

/**
 * 操作流水（FR-4.4 前端形态）。
 *
 * 后端现状：无 AuditLog 表，删除等动作以结构化日志行落地（TaskService#delete 注释与 ADR-11b；
 * 手动刷新批 S5 起 TASK_CHANGED 事件链已摘除，流水不再伴随事件推送）。此处定义前端展示用的
 * 流水条目形态，落库的 AuditLog 表属 DC-10 P2 —— 见证据文档【待裁决】。
 */
export interface OperationLogEntry {
  /** 动作，如 CREATE_TASK / DELETE_TASK / START_JOB / PUBLISH */
  action: string
  /** 对象类型，如 task / job / definition / data */
  objectType: string
  /** 对象标识（id 或 defCode） */
  objectId: string | number
  /** 前值摘要（自由文本，后端日志同口径） */
  beforeSummary?: string
  /** 来源：界面 / API / AI */
  source: '界面' | 'API' | 'AI'
  /** 时刻（ISO 字符串） */
  at: string
}
