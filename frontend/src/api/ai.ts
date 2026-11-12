/**
 * AI 域 client（端点对齐 backend AiController，见 S4.4 规格 §3）。
 *
 * 注意：AI 端点的响应体是**裸对象**（不套 ApiResponse），因此这里一律用
 * http.ts 的 post/get，由拦截器原样放行。
 */

import type { AxiosRequestConfig } from 'axios'
import { ApiError, del, get, http, post } from './http'
import { reattach, streamChat } from './sse'
import { logCaughtError } from '@/utils/log-caught-error'
import type { SseFrameHandlers, SseStreamOptions } from './sse'
import { getActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'
import type { TenantUsersEntry } from '@/utils/ai-identity'
import { normalizeSessionId } from '@/utils/ai-identity'
import type {
  AiCancelResult,
  AiChatContext,
  AiChatRequest,
  AiHealth,
  ContextSnapshot,
  AiHistory,
  SessionPurgeResponse,
  AiRunCurrent,
  ConfirmRequest,
  ConfirmResult,
  FrontendToolResultAck,
  FrontendToolResultRequest,
  StreamCloseInfo
} from '@/types/ai'

/** POST /api/ai/chat（SSE 一轮对话；409→SESSION_BUSY 携 runId，503→池饱和/无 key/无 Redis）。 */
export function chat(
  request: AiChatRequest,
  handlers?: SseFrameHandlers,
  options?: SseStreamOptions
): Promise<StreamCloseInfo> {
  return streamChat(request, handlers, options)
}

/** GET /api/ai/events/{runId}（reattach：回放状态类帧 + 续收实时帧）。 */
export function reattachRun(
  runId: string,
  handlers?: SseFrameHandlers,
  options?: SseStreamOptions
): Promise<StreamCloseInfo> {
  return reattach(runId, handlers, options)
}

/**
 * POST /api/ai/confirm（HITL 放行/拒绝；重复 toolCallId → 409 DUPLICATE_TOOL_CALL_ID）。
 *
 * <p>`config` 只用于**显式身份头**：axios 拦截器在微任务里才跑，点击后立刻切身份时它读到的已是
 * 新身份 —— 调用方须把点击时捕获的身份放进 `headers`（与 `cancel` 同口径；拦截器只补缺省、不覆盖）。
 */
export function confirm(request: ConfirmRequest, config?: AxiosRequestConfig): Promise<ConfirmResult> {
  return post<ConfirmResult>('/ai/confirm', withWorkspaceContext(request), config)
}

/**
 * POST /api/ai/frontend-tool-result（前端工具回灌；重复 → 409，未知 → 409）。
 *
 * <p>`config` 同 {@link confirm}：提交时身份显式随请求发出，避免切换到新身份后旧回灌被 409 丢弃。
 */
export function frontendToolResult(
  request: FrontendToolResultRequest,
  config?: AxiosRequestConfig
): Promise<FrontendToolResultAck> {
  return post<FrontendToolResultAck>('/ai/frontend-tool-result', withWorkspaceContext(request), config)
}

/**
 * HITL 写入口附带<b>最新工作区上下文</b>（可选字段）。
 *
 * 为什么在 api 层拼而不是让每个调用方传：挂起期间用户可能已在向导里改了步骤/勾选/任务详情，
 * 而续跑只读快照 —— 后端 `/confirm`、`/frontend-tool-result` 是决策落地前唯一能拿到"用户此刻
 * 看到的页面"的写入口。没有活动 store（单测/非应用环境）时不带，行为与改造前一致。
 *
 * <b>部署顺序：后端先上。</b>后端 MVC 的 {@code ObjectMapper} 是 `JacksonConfig` 里的裸
 * `new ObjectMapper()`（`FAIL_ON_UNKNOWN_PROPERTIES=true`），旧后端收到未知的 `context` 会 400；
 * 反向（新后端 + 旧前端）安全 —— 字段可选，缺省即不写快照。
 *
 * 返回值刻意用 {@code object} 拓宽：请求类型不新增字段（旧调用方零影响），只多一个 additive 键
 * {@code context}。
 */
function withWorkspaceContext<T extends object>(request: T): T {
  const context = currentWorkspaceContext()
  if (!context) {
    return request
  }
  return { ...request, context } as T
}

/** 当前工作区上下文（无活动 pinia / store 异常 ⇒ undefined，绝不阻断请求）。 */
function currentWorkspaceContext(): AiChatContext | undefined {
  try {
    if (!getActivePinia()) {
      return undefined
    }
    return useWorkspaceStore().buildContext()
  } catch (error) {
      logCaughtError(error, 'api/ai.ts:102')
    return undefined
  }
}

/**
 * POST /api/ai/session（**唯一**的会话发号入口）。
 *
 * <p>前端不再自己造 sessionId：切换身份、复制页签、410 恢复、刷新后无会话时统一走这里。
 *
 * <p><b>唯一值 = `sessionIdText`</b>（十进制字符串，与后端 `AiSessionController` 契约同名）：
 * 会话号是 long，JSON number 在消费端不可安全往返，故一律按字符串存储/发送；后端 Jackson 把
 * 十进制字符串反序列化为 long 是标准行为。数值 `sessionId` 字段不再回读。
 */
export async function createSession(): Promise<string> {
  const response = await http.post('/ai/session', undefined, {
    responseType: 'text',
    transformResponse: [(data: unknown) => data]
  })
  const sessionId = extractSessionId(response.data)
  if (!sessionId) {
    throw new Error('Session creation failed: response is missing a valid sessionId')
  }
  return sessionId
}

/** 从原始响应文本（或已解析对象）里取十进制会话号；非法一律 ''。 */
function extractSessionId(body: unknown): string {
  if (typeof body === 'string') {
    // 只认 `data` 对象里的字段（响应若回显请求体，裸扫全串可能命中第二处 `sessionId`）。
    // 没有 `data` 包装时退回整串（裸对象契约）。从 `"data"` 起点往后找，避免被 data 内的嵌套对象截断。
    const dataIndex = body.search(/"data"\s*:\s*\{/)
    const scope = dataIndex >= 0 ? body.slice(dataIndex) : body
    const authoritative = /"sessionIdText"\s*:\s*"([1-9]\d*)"/.exec(scope)
    return authoritative ? authoritative[1] : ''
  }
  if (body && typeof body === 'object') {
    const envelope = body as {
      sessionIdText?: unknown
      data?: { sessionIdText?: unknown }
    }
    return normalizeSessionId(envelope.sessionIdText)
      || normalizeSessionId(envelope.data?.sessionIdText)
  }
  return ''
}

/**
 * GET /api/ai/tenants（预置租户与用户目录；与后端 `TenantDirectoryController` 同一路径）。
 *
 * <p>不参与租户隔离（用户还没选身份，必须先能读到选项），故不在 `TenantIdentityFilter` 的
 * 边界内、前端也不带身份头。返回 `{tenantId, tenantName?, users:[{userId, displayName|userName}]}`
 * —— 字段兼容由 store 的 `normalizeTenantEntry` 收口。
 */
export function tenantUsers(): Promise<TenantUsersEntry[]> {
  return get<TenantUsersEntry[]>('/ai/tenants')
}

/** POST /api/ai/cancel/{runId}（取消一轮；幂等，重复返回 alreadyCancelled=true）。 */
export function cancel(runId: string, config?: AxiosRequestConfig): Promise<AiCancelResult> {
  return post<AiCancelResult>(`/ai/cancel/${encodeURIComponent(runId)}`, undefined, config)
}

/**
 * POST /api/ai/cancel/client-request（停止闭环 additive 端点）。
 *
 * 只凭**会话 + clientRequestId** 精确定位：后端映射在 /chat 创建 run 前写入，客户端无法用它
 * 指定 runId，也不扫描 run；run 尚未创建时写取消墓碑，随后到达的 /chat 在压缩/建快照/模型调用
 * 前收敛。幂等（重复刷新/重复取消不重复副作用）。
 */
export function cancelClientRequest(
  request: { sessionId: string; clientRequestId: string },
  config?: AxiosRequestConfig
): Promise<{
  cancelled: boolean
  alreadyCancelled: boolean
  clientRequestId?: string
  sessionId?: string
  runId?: string | null
  pendingRun?: boolean
  redisFlagWritten?: boolean
  degraded?: boolean
  note?: string
}> {
  return post('/ai/cancel/client-request', request, config)
}

export interface AiHistoryPage extends AiHistory {
  nextCursor?: string
  revision?: number
}

/** 无参数读取最近一页；latest 限定页大小，before 稳定向前翻页。 */
export function history(
  sessionId: string,
  page: { latest?: number; before?: string } = {}
): Promise<AiHistoryPage> {
  const query = new URLSearchParams()
  if (typeof page.latest === 'number') {
    query.set('latest', String(page.latest))
  }
  if (typeof page.before === 'string' && page.before !== '') {
    query.set('before', page.before)
  }
  const suffix = query.toString() === '' ? '' : `?${query.toString()}`
  return get<AiHistoryPage>(`/ai/history/${encodeURIComponent(sessionId)}${suffix}`)
}

/**
 * POST /api/ai/form/attachments（GF 优化批 G-E：attachment 字段的固定上传口）。
 *
 * 只上传**文件本体**，回填表单的是返回的 `fileId`；文件内容永不进模型上下文。
 * `runId + toolCallId` 用于把附件绑定到"本次挂起的这张表单"（回灌时按同一对 id 校验归属，
 * 成功后一次性消费）。
 */
export async function uploadFormAttachment(
  file: File,
  context: { runId: string; toolCallId: string; sessionId?: string; signal?: AbortSignal }
): Promise<{ fileId: string; fileName: string; size: number; contentType?: string }> {
  const form = new FormData()
  form.append('file', file)
  form.append('runId', context.runId)
  form.append('toolCallId', context.toolCallId)
  if (context.sessionId) {
    form.append('sessionId', context.sessionId)
  }
  const body = await post<{ success?: boolean; data?: unknown } | Record<string, unknown>>(
    '/ai/form/attachments',
    form,
    context.signal ? { signal: context.signal } : undefined
  )
  const payload = unwrapApiData(body) as Partial<{ fileId: string; fileName: string; size: number; contentType: string }>
  if (!payload || typeof payload.fileId !== 'string') {
    throw new Error('Attachment upload failed: response is missing fileId')
  }
  return {
    fileId: payload.fileId,
    fileName: typeof payload.fileName === 'string' ? payload.fileName : file.name,
    size: typeof payload.size === 'number' ? payload.size : file.size,
    ...(typeof payload.contentType === 'string' ? { contentType: payload.contentType } : {})
  }
}

/**
 * GET /api/ai/form/option-sources/{sourceId}（联动原语④的数据源解析口）。
 *
 * `sourceId` 是**后端注册的闭集**（`GenerativeFormRules#OPTION_SOURCE_IDS`，当前两个内置源）：
 * - `definition_fields`：父值 = 配置定义编码 → 该定义的字段（先选定义，再选它的字段）；
 * - `definitions`：父值 = 层级（GLOBAL / REGION / PROJECT）→ 该层级的配置定义（「层级→定义」用法）。
 *
 * 模型无法让前端去请求任意 URL；这里的路径参数只是把已通过 schema 闸门的 sourceId 交给后端复核
 * （未知 id 后端按闭集拒绝，前端不做自由请求）。父值缺失/不可解析时返回空列表（不猜测）。
 */
export async function formOptionSource(
  sourceId: string,
  value: string
): Promise<{ options: Array<{ value: string; label?: string }> }> {
  const body = await get<unknown>(
    `/ai/form/option-sources/${encodeURIComponent(sourceId)}?value=${encodeURIComponent(value)}`
  )
  const payload = unwrapApiData(body) as { options?: unknown } | null
  const options = Array.isArray(payload?.options) ? payload?.options : []
  return {
    options: (options ?? []).filter(
      (item): item is { value: string; label?: string } =>
        !!item && typeof item === 'object' && typeof (item as { value?: unknown }).value === 'string'
    )
  }
}

/** 信封拆包：业务端点 `{success,data}` 取 `data`；AI 域的裸对象原样返回（与 http.ts 拦截器同口径）。 */
function unwrapApiData(body: unknown): unknown {
  if (body && typeof body === 'object' && typeof (body as { success?: unknown }).success === 'boolean') {
    return (body as { data?: unknown }).data
  }
  return body
}

/**
 * GET /api/ai/context/{sessionId}（上下文占用摘要：刷新恢复）。
 *
 * 姿态与 `/history` 同族：只读、不查 AI key；Redis 不可用或计量未装配时后端回 503
 * （调用方保持现状，**不得**把它当成"用了 0"）。七项拆分 / 压缩台账已不再消费。
 */
export function getContext(sessionId: string): Promise<ContextSnapshot> {
  return get<ContextSnapshot>(`/ai/context/${encodeURIComponent(sessionId)}`)
}

/**
 * DELETE /api/ai/session/{sessionId}（「新建对话」= 完整会话销毁）。
 *
 * 幂等：会话不存在 / 重复删除也返回 200（裸 Map，不套 ApiResponse 信封），故调用方不需要
 * 区分"删过没删过"，也不需要处理 404。`complete/deferred/errors/clientCleanup` 由调用方消费：
 * 本地只按 `clientCleanup` 精确清理，销毁进度本身是后台语义。
 * `config` 同 {@link cancel}：调用方可显式携带操作入口捕获的旧身份头。
 */
export function deleteHistory(sessionId: string, config?: AxiosRequestConfig): Promise<SessionPurgeResponse> {
  return del<SessionPurgeResponse>(`/ai/session/${encodeURIComponent(sessionId)}`, config)
}

/**
 * GET /api/ai/health（AI 可用性）。
 *
 * 不可用时后端返回 **503 + 同一个体**（available=false、code=AI_UNAVAILABLE/AI_REDIS_UNAVAILABLE），
 * 这是正常语义而非异常，故这里把 503 的体照样解析返回，只有网络异常才 reject。
 */
export async function health(): Promise<AiHealth> {
  try {
    return await get<AiHealth>('/ai/health')
  } catch (error) {
    if (error instanceof ApiError && error.status === 503) {
      const body = error.bodyAs<AiHealth>()
      if (body && typeof body.available === 'boolean') {
        return body
      }
    }
    logCaughtError(error, 'ai:health')
    throw error
  }
}

/**
 * GET /api/ai/runs/current?sessionId=（T2b-D#3：刷新后挂起快照重建的数据源）。
 * 空态（索引缺失/进程已死/快照已清）为 found=false 的同一个体。
 */
export function runsCurrent(sessionId: string): Promise<AiRunCurrent> {
  return get<AiRunCurrent>(`/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`)
}

export function resumeRun(runId: string): Promise<{
  resumed: boolean
  historyComplete?: boolean
  reason?: string
}> {
  return post(`/ai/runs/${encodeURIComponent(runId)}/resume`, undefined)
}

export const aiApi = {
  createSession,
  tenantUsers,
  chat,
  reattachRun,
  confirm,
  frontendToolResult,
  cancel,
  cancelClientRequest,
  history,
  getContext,
  deleteHistory,
  health,
  runsCurrent,
  resumeRun,
  feedbackSubmit,
  feedbackList
}

/** R-163 T5：赞踩上报（rating ∈ 1/-1/0，0=取消；后端幂等 upsert）。 */
export interface AiFeedbackRequest {
  messageId: string
  sessionId: string
  rating: 1 | -1 | 0
  reason?: string
  /** R-163 收口修 F2：被评价消息正文前 200 字符快照（人工复盘可读摘要）。 */
  excerpt?: string
}

/** R-163 T5：反馈查看列表条目（设置页「AI 反馈」）。 */
export interface AiFeedbackEntry {
  messageId: string
  sessionId: string
  rating: 1 | -1
  reason: string | null
  /** F2：正文快照（旧数据/未携时为 null）。 */
  excerpt?: string | null
  createdAt: string
  updatedAt: string
}

/** POST /api/ai/feedback（幂等 upsert；message_id 唯一）。 */
export function feedbackSubmit(request: AiFeedbackRequest): Promise<{ saved: boolean; cancelled: boolean }> {
  return post<{ saved: boolean; cancelled: boolean }>('/ai/feedback', request)
}

/** GET /api/ai/feedback（设置页「AI 反馈」查看列表，固定上限 100 倒序——后端硬编码，前端不传参）。 */
export function feedbackList(): Promise<AiFeedbackEntry[]> {
  return get<AiFeedbackEntry[]>('/ai/feedback')
}
