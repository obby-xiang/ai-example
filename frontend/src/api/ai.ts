/**
 * AI 域 client（端点对齐 backend AiController，见 S4.4 规格 §3）。
 *
 * 注意：AI 端点的响应体是**裸对象**（不套 ApiResponse），因此这里一律用
 * http.ts 的 post/get，由拦截器原样放行。
 */

import type { AxiosRequestConfig } from 'axios'
import { ApiError, del, get, http, post } from './http'
import { reattach, streamChat } from './sse'
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
  RunDescription,
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

/** POST /api/ai/confirm（HITL 放行/拒绝；重复 toolCallId → 409 DUPLICATE_TOOL_CALL_ID）。 */
export function confirm(request: ConfirmRequest): Promise<ConfirmResult> {
  return post<ConfirmResult>('/ai/confirm', withWorkspaceContext(request))
}

/** POST /api/ai/frontend-tool-result（前端工具回灌；重复 → 409，未知 → 409）。 */
export function frontendToolResult(request: FrontendToolResultRequest): Promise<FrontendToolResultAck> {
  return post<FrontendToolResultAck>('/ai/frontend-tool-result', withWorkspaceContext(request))
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
  } catch {
    return undefined
  }
}

/** POST /api/ai/session 的响应（契约：`{sessionId:number(long)}`）。 */
export interface AiSessionIssue {
  sessionId: number
}

/**
 * POST /api/ai/session（**唯一**的会话发号入口）。
 *
 * <p>前端不再自己造 sessionId：切换身份、复制页签、410 恢复、刷新后无会话时统一走这里。
 *
 * <p><b>为什么按文本取数字</b>：后端按 `时间戳(ms) × 10^6 + …` 发号，量级约 1.7×10^18，
 * **超出 JS `Number.MAX_SAFE_INTEGER`（9×10^15）** —— 先 `JSON.parse` 成 number 会静默丢低位，
 * 前端就会拿一个"看起来对、实际错"的号去请求。这里让 axios 保留原始文本，用正则取出十进制
 * 数字串（JSON 文本里的数字本身不丢精度），全程按字符串存储/发送；后端 Jackson 把十进制字符串
 * 反序列化为 long 是标准行为。
 */
export async function createSession(): Promise<string> {
  const response = await http.post('/ai/session', undefined, {
    responseType: 'text',
    transformResponse: [(data: unknown) => data]
  })
  const sessionId = extractSessionId(response.data)
  if (!sessionId) {
    throw new Error('会话发号失败：响应缺少合法的 sessionId')
  }
  return sessionId
}

/** 从原始响应文本（或已解析对象）里取十进制会话号；非法一律 ''。 */
function extractSessionId(body: unknown): string {
  if (typeof body === 'string') {
    // 只认 `data` 对象里的那个字段（响应若回显请求体，裸扫全串可能命中第二处 `sessionId`）。
    // 没有 `data` 包装时退回整串（裸对象契约）。从 `"data"` 起点往后找，避免被 data 内的嵌套对象截断。
    const dataIndex = body.search(/"data"\s*:\s*\{/)
    const scope = dataIndex >= 0 ? body.slice(dataIndex) : body
    const match = /"sessionId"\s*:\s*"?([1-9]\d*)"?/.exec(scope)
    return match ? match[1] : ''
  }
  if (body && typeof body === 'object') {
    const envelope = body as { sessionId?: unknown; data?: { sessionId?: unknown } }
    return normalizeSessionId(envelope.sessionId ?? envelope.data?.sessionId)
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

/** GET /api/ai/cancel/{runId}（取消标志现状，取证用）。 */
export function cancelStatus(runId: string): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>(`/ai/cancel/${encodeURIComponent(runId)}`)
}

/** GET /api/ai/history/{sessionId}（刷新恢复用的会话记忆原文）。 */
export function history(sessionId: string): Promise<AiHistory> {
  return get<AiHistory>(`/ai/history/${encodeURIComponent(sessionId)}`)
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
    throw new Error('附件上传失败：响应缺少 fileId')
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
 * GET /api/ai/context/{sessionId}（上下文占用：刷新恢复 / 展开分项 / 挂起态）。
 *
 * 姿态与 `/history` 同族：只读、不查 AI key；Redis 不可用或计量未装配时后端回 503
 * （调用方按"没有数据"降级，**不得**把它当成"用了 0"）。
 *
 * @param ledger 是否附带最近 20 条压缩台账（运维面；默认不带）
 */
export function getContext(sessionId: string, ledger = false): Promise<ContextSnapshot> {
  const query = ledger ? '?ledger=1' : ''
  return get<ContextSnapshot>(`/ai/context/${encodeURIComponent(sessionId)}${query}`)
}

/**
 * DELETE /api/ai/session/{sessionId}（「新建对话」= 完整会话销毁）。
 *
 * 旧 `DELETE /api/ai/history/{sessionId}` 在后端也映射到同一 purge 语义；前端走新路径。
 * 幂等：会话不存在 / 重复删除也返回 200（裸 Map，不套 ApiResponse 信封），故调用方不需要
 * 区分"删过没删过"，也不需要处理 404。旧字段（sessionId/deleted/existed/memoryKey/
 * removedFromIndex/runActive/activeRunId/storedBy/note）保留，新增 complete/deferred/errors/
 * clientCleanup 由调用方消费：`complete=false` 必须提示"未删净"。
 */
export function deleteHistory(sessionId: string): Promise<SessionPurgeResponse> {
  return del<SessionPurgeResponse>(`/ai/session/${encodeURIComponent(sessionId)}`)
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
    throw error
  }
}

/** GET /api/ai/runs（发现待续跑轮次）。 */
export function runs(): Promise<RunDescription[]> {
  return get<RunDescription[]>('/ai/runs')
}

/** GET /api/ai/runs/{runId}（单轮明细）。 */
export function run(runId: string): Promise<RunDescription> {
  return get<RunDescription>(`/ai/runs/${encodeURIComponent(runId)}`)
}

/** POST /api/ai/runs/{runId}/resume（续跑；202=已受理，200=已终态，503=池饱和）。 */
export function resume(runId: string): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>(`/ai/runs/${encodeURIComponent(runId)}/resume`)
}

/**
 * POST /api/ai/runs/{runId}/retry-model-step（批次2 / AR-05：仅模型步重跑）。
 *
 * 202 = 已受理（随后用既有 `GET /api/ai/events/{runId}` 重挂；**不追加用户消息**）；
 * 200 = 已收尾/已取消（幂等空操作）；409 = 在途或前置失败（`failedCheck`）；
 * 404 = 轮次不存在；503 = AI/Redis 不可用（可重试）。
 */
export function retryModelStep(runId: string): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>(`/ai/runs/${encodeURIComponent(runId)}/retry-model-step`)
}

/** GET /api/ai/pending/{runId}（挂起项快照，诊断用）。 */
export function pending(runId: string): Promise<PendingSnapshot[]> {
  return get<PendingSnapshot[]>(`/ai/pending/${encodeURIComponent(runId)}`)
}

/** 挂起项快照的宽松形态（后端为 PendingToolCall#asMap）。 */
export type PendingSnapshot = Record<string, unknown>

/**
 * GET /api/ai/runs/current?sessionId=（T2b-D#3：刷新后挂起快照重建的数据源）。
 * 空态（索引缺失/进程已死/快照已清）为 found=false 的同一个体。
 */
export function runsCurrent(sessionId: string): Promise<AiRunCurrent> {
  return get<AiRunCurrent>(`/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`)
}

export const aiApi = {
  createSession,
  tenantUsers,
  chat,
  reattachRun,
  confirm,
  frontendToolResult,
  cancel,
  cancelStatus,
  history,
  getContext,
  deleteHistory,
  health,
  runs,
  run,
  resume,
  retryModelStep,
  pending,
  runsCurrent,
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
