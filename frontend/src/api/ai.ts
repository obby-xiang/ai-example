/**
 * AI 域 client（端点对齐 backend AiController，见 S4.4 规格 §3）。
 *
 * 注意：AI 端点的响应体是**裸对象**（不套 ApiResponse），因此这里一律用
 * http.ts 的 post/get，由拦截器原样放行。
 */

import { ApiError, del, get, post } from './http'
import { reattach, streamChat } from './sse'
import type { SseFrameHandlers, SseStreamOptions } from './sse'
import type {
  AiCancelResult,
  AiChatRequest,
  AiHealth,
  AiHistory,
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
  return post<ConfirmResult>('/ai/confirm', request)
}

/** POST /api/ai/frontend-tool-result（前端工具回灌；重复 → 409，未知 → 409）。 */
export function frontendToolResult(request: FrontendToolResultRequest): Promise<FrontendToolResultAck> {
  return post<FrontendToolResultAck>('/ai/frontend-tool-result', request)
}

/** POST /api/ai/cancel/{runId}（取消一轮；幂等，重复返回 alreadyCancelled=true）。 */
export function cancel(runId: string): Promise<AiCancelResult> {
  return post<AiCancelResult>(`/ai/cancel/${encodeURIComponent(runId)}`)
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
 * DELETE /api/ai/history/{sessionId}（「新建对话」：删该会话的服务端记忆）。
 *
 * 幂等：会话不存在 / 重复删除也返回 200（裸 Map，不套 ApiResponse 信封），故调用方不需要
 * 区分"删过没删过"，也不需要处理 404。响应体字段（sessionId/deleted/existed/memoryKey/
 * removedFromIndex/runActive/activeRunId/storedBy/note）供诊断与取证用，前端不依赖。
 */
export function deleteHistory(sessionId: string): Promise<Record<string, unknown>> {
  return del<Record<string, unknown>>(`/ai/history/${encodeURIComponent(sessionId)}`)
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
  chat,
  reattachRun,
  confirm,
  frontendToolResult,
  cancel,
  cancelStatus,
  history,
  deleteHistory,
  health,
  runs,
  run,
  resume,
  pending,
  runsCurrent
}
