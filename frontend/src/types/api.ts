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
  ILLEGAL_STATE: 'ILLEGAL_STATE',
  RESOURCE_NOT_FOUND: 'RESOURCE_NOT_FOUND',
  SESSION_BUSY: 'SESSION_BUSY',
  UNKNOWN_RUN: 'UNKNOWN_RUN',
  UNKNOWN_TOOL_CALL: 'UNKNOWN_TOOL_CALL',
  DUPLICATE_TOOL_CALL_ID: 'DUPLICATE_TOOL_CALL_ID',
  SUSPEND_POOL_SATURATED: 'SUSPEND_POOL_SATURATED',
  AI_UNAVAILABLE: 'AI_UNAVAILABLE',
  AI_REDIS_UNAVAILABLE: 'AI_REDIS_UNAVAILABLE',
  AI_AVAILABLE: 'AI_AVAILABLE',
  RATE_LIMITED: 'RATE_LIMITED',
  MESSAGE_TOO_LONG: 'MESSAGE_TOO_LONG',
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
  TOOL_NOT_PREFLIGHTABLE: 'TOOL_NOT_PREFLIGHTABLE',
  TOOL_ENVELOPE_SERIALIZE_FAILED: 'TOOL_ENVELOPE_SERIALIZE_FAILED',
  TOOL_ENVELOPE_PARSE_FAILED: 'TOOL_ENVELOPE_PARSE_FAILED',
  TOOL_RESULT_OUTCOME_MISMATCH: 'TOOL_RESULT_OUTCOME_MISMATCH',
  FORM_RESULT_REJECTED: 'FORM_RESULT_REJECTED',
  FORM_SCHEMA_REJECTED: 'FORM_SCHEMA_REJECTED',
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
  MODEL_EXISTS: 'MODEL_EXISTS',
  MODELS_INVALID: 'MODELS_INVALID',
  MODELS_UNSUPPORTED: 'MODELS_UNSUPPORTED',
  INVALID_MODEL: 'INVALID_MODEL',
  INVALID_PARAM: 'INVALID_PARAM',
  INVALID_BASE_URL: 'INVALID_BASE_URL',
  BLOCKED_PRIVATE_ENDPOINT: 'BLOCKED_PRIVATE_ENDPOINT',
  AUTH_FAILED: 'AUTH_FAILED',
  UNREACHABLE: 'UNREACHABLE',
  CODE_IMMUTABLE: 'CODE_IMMUTABLE',
  AI_SECRET_KEK_MISSING: 'AI_SECRET_KEK_MISSING',
  AI_KEY_MISSING: 'AI_KEY_MISSING',
  KEY_EMPTY: 'KEY_EMPTY',
  KEY_UNREADABLE: 'KEY_UNREADABLE',
  UPSTREAM_STREAM_ERROR: 'UPSTREAM_STREAM_ERROR',
  AI_RUN_FAILED: 'AI_RUN_FAILED',
  AI_RESUME_FAILED: 'AI_RESUME_FAILED',
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

/** 409 SESSION_BUSY 的响应体形态（ADR-5：必须携进行中轮的 runId）。 */
export interface SessionBusyConflict {
  code: typeof ErrorCode.SESSION_BUSY
  message: string
  sessionId: string
  /** 进行中那一轮的 runId，前端据此改走 /api/ai/events/{runId} */
  runId: string
  degraded?: boolean
  /** 后端直接给出的重挂路径：/api/ai/events/{runId} */
  reattach: string
}

/** 503 SUSPEND_POOL_SATURATED 的响应体形态。 */
export interface SuspendPoolSaturated {
  code: typeof ErrorCode.SUSPEND_POOL_SATURATED
  message: string
  runId: string
  poolSize: number
}

/** 503 AI_UNAVAILABLE / AI_REDIS_UNAVAILABLE 的响应体形态。 */
export interface AiUnavailableBody {
  code: typeof ErrorCode.AI_UNAVAILABLE | typeof ErrorCode.AI_REDIS_UNAVAILABLE
  message: string
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
