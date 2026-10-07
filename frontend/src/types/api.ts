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

/** 错误码枚举：前 5 个是 S4.4 规格书点名的裁决态，其余为后端实测存在的附加码。 */
export const ErrorCode = {
  /** 409：同 sessionId 已有一轮在进行中，响应体携进行中 runId + reattach 路径 */
  SESSION_BUSY: 'SESSION_BUSY',
  /** 503：挂起专用线程池饱和 */
  SUSPEND_POOL_SATURATED: 'SUSPEND_POOL_SATURATED',
  /** 503：无 AI key，模型侧未装配 */
  AI_UNAVAILABLE: 'AI_UNAVAILABLE',
  /** 503：AI 运行时依赖的 Redis 不可用 */
  AI_REDIS_UNAVAILABLE: 'AI_REDIS_UNAVAILABLE',
  /** 409：取消已终态作业（ADR-8 W1 边界） */
  JOB_ALREADY_FINAL: 'JOB_ALREADY_FINAL',
  /** 409：同一 toolCallId 重复决策/回灌（幂等拒绝） */
  DUPLICATE_TOOL_CALL_ID: 'DUPLICATE_TOOL_CALL_ID',
  /** 409：未知或已过期的 runId/toolCallId */
  UNKNOWN_TOOL_CALL: 'UNKNOWN_TOOL_CALL',
  /** 404：未知 runId（reattach 时） */
  UNKNOWN_RUN: 'UNKNOWN_RUN',
  /** 400：参数校验失败 */
  BAD_REQUEST: 'BAD_REQUEST',
  /** SSE error 帧：AI 轮次未捕获异常 */
  AI_RUN_FAILED: 'AI_RUN_FAILED',
  /** 400：生成式表单回灌值不符 schema（挂起仍在等待，可改值用同一 toolCallId 重试） */
  FORM_RESULT_REJECTED: 'FORM_RESULT_REJECTED',
  /** 400：生成式表单 schema 越白名单（后端在工具入参处拒绝不下发，前端防御性登记展示态） */
  FORM_SCHEMA_REJECTED: 'FORM_SCHEMA_REJECTED',
  /** 前端工具条目终态：用户取消/渲染器不可用（结局帧 status 口径，展示态登记） */
  FRONTEND_CANCELLED: 'FRONTEND_CANCELLED'
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
 * 后端现状：无 AuditLog 表，删除等动作以结构化日志行 + TASK_CHANGED 事件（summary）落地
 * （TaskService#delete 注释与 ADR-11b）。此处定义前端展示用的流水条目形态，
 * 落库的 AuditLog 表属 DC-10 P2 —— 见证据文档【待裁决】。
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
