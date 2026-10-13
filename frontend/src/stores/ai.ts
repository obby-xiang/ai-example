/**
 * AI 会话 store（状态层）。
 *
 * 形态来源：kimi-k3 `stores/ai.js`（`initSessionId` / 会话镜像 / `loadHistory` 后端对账，
 * 原 .js:25-52 与 248-301），TS 化并按本仓后端契约重写：
 * - 会话 id 存 **sessionStorage**（页签级：同页签刷新保持，新页签是新会话，DC-06③）；
 * - 刷新恢复走"镜像即时恢复 + `GET /api/ai/history/{sessionId}` 对账"两步；
 * - 409 SESSION_BUSY 不当作普通失败：记下进行中的 runId 并允许一键 reattach（ADR-5）；
 * - 断流（缺 done/error 终帧）按 ADR-6 置 `streamInterrupted` 提示"结果可能不完整"；
 * - 契约联动（S4.4c）：订阅工作区 `workspace_changed` 事件，把用户手点的动作同步进
 *   下一轮请求上下文（`buildContext().extra.recentActions`）。
 */

import { defineStore } from 'pinia'
import { ApiError } from '@/api/http'
import { aiApi } from '@/api/ai'
import type { SseFrameHandlers, SseStreamOptions } from '@/api/sse'
import { onWorkspaceEvent, reportUiEventResult, useWorkspaceStore } from '@/stores/workspace'
import { useTaskStore } from '@/stores/task'
import { deriveFrontendToolBudgetMs, executeFrontendTool, parseToolArgs } from '@/utils/frontend-tools'
import {
  alreadyApplied,
  applyFrameToParts,
  deriveProjections,
  findToolPart,
  historyToolCallStatus,
  markApplied,
  openStreamPart,
  sealTrailingStreamPart,
  upsertToolPart as writeToolPart
} from '@/utils/parts'
import { ErrorCode, type SessionBusyConflict, type SuspendPoolSaturated } from '@/types/api'
import { frameSeq, isTerminalFrame } from '@/types/sse'
import { GENERATIVE_FORM_TOOL, MIRROR_VERSION, type ActiveGenerativeForm, type AiHealth, type AiHistory, type AiHistoryMessage, type AiRunCurrent, type AiSessionMirror, type ChatMessage, type MessagePart, type PendingToolCall, type ToolPart, type ToolPartPatch, type ToolRunStatus } from '@/types/ai'
import { parseFormSchema, checkFormValues } from '@/types/form-schema'
import type { SseFrame } from '@/types/sse'
import type { WorkspaceActionRecord } from '@/types/tools'
import { clearPublishToolLinks, findPublishTool, linkPublishTool, unlinkPublishTool } from '@/utils/publish-links'

/** sessionStorage 键：会话 id 与镜像前缀。 */
export const SESSION_ID_KEY = 'ai-session-id'
export const MIRROR_PREFIX = 'ai-mirror:'

/** 当前轮的 AbortController（模块级：不进响应式状态）。 */
let currentAbort: AbortController | null = null
/**
 * 当前轮的宿主助手消息 id（模块级：与 `currentAbort` 同为"这一轮"的身份判据）。
 *
 * issue#5 R2：终帧只允许复位**本轮**的轮次级状态 —— 旧轮迟到帧（`resetSession` 之后、
 * 或新轮已起）不得把新轮标记成空闲。runId 不能单独做判据：新一轮 start 帧到达之前
 * `activeRunId` 仍是上一轮的值，而宿主消息 id 在 send / reattachActive / 续收入口即已就位。
 */
let activeRoundHostId: string | null = null
/** 生成式表单本地超时计时器（模块级：activeForm 被结局帧/新轮清掉时随之中和）。 */
let formExpiryTimer: ReturnType<typeof setTimeout> | null = null

function disarmFormExpiry(): void {
  if (formExpiryTimer !== null) {
    clearTimeout(formExpiryTimer)
    formExpiryTimer = null
  }
}
/** 会话镜像是否已启动（防止重复 $subscribe）。 */
let mirrorStarted = false
let mirrorTimer: ReturnType<typeof setTimeout> | null = null
/** 工作区契约事件订阅是否已启动 + 反订阅句柄。 */
let workspaceSyncStarted = false
let workspaceSyncStops: Array<() => void> = []

/** 生成页签级会话 id（crypto.randomUUID 在非安全上下文不可用，故留回退）。 */
export function createSessionId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
  } catch {
    // 落回退实现
  }
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * T4-3（裁决 3，选项 a）：把服务端到期信息折算成本地倒计时 deadline。
 *
 * 口径：`expiresAt` 与 `atMs` 同为**服务端钟**的 epoch 毫秒（前者 = 服务端给的绝对到期时刻，
 * 后者 = 帧构造时刻），故 `expiresAt − atMs` 就是服务端给出的**剩余时长** —— 本地取
 * 「本地 now + 剩余时长」，倒计时便不因客户端钟漂移 X 而整段偏移 X（GFb 红队问题 7）。
 *
 * 回落（保持既有口径，不换语义）：`atMs` 缺失 / 非法 / **≤ 0**，或 `expiresAt < atMs` 时按绝对
 * `expiresAt` 对本地钟。T4-3 后半起**实时帧同样带 `atMs`**（后端帧构造点取值，与 `expiresAt`
 * 同钟）⇒ 正常实时 / 回放帧一律走折算分支；回落分支只剩旧格式帧与损坏数据（`atMs ≤ 0` 会把
 * 绝对 epoch 误当剩余时长、deadline 膨胀到数十年后，S3-2 面，故本守卫同时挡掉 0 与负数）。
 * 再缺 `expiresAt` 才按 `timeoutSeconds` 相对秒折算；三者皆无 → `null`（不武装本地计时器）。
 *
 * 已知代价（本批口径，登记于排期去向表观察项）：回放 / 重挂帧按**帧构造时**的剩余时长重新起算，
 * 不扣减 `atMs` 之后已流逝的等待时间 ⇒ 重挂后本地倒计时可能偏长。后端 `ConfirmGate` /
 * 结局帧仍是权威超时判据（本地先到 → 取消；后端先到 → 结局帧收敛），故本批以「免疫钟漂移」
 * 换「重挂重起算」。`nowMs` 参数仅为可复算 / 走查留口，业务调用一律用默认值。
 */
export function foldDeadline(
  expiresAt?: number,
  atMs?: number,
  timeoutSeconds?: number,
  nowMs: number = Date.now()
): number | null {
  if (typeof expiresAt === 'number' && Number.isFinite(expiresAt)) {
    if (typeof atMs === 'number' && Number.isFinite(atMs) && atMs > 0 && expiresAt >= atMs) {
      return nowMs + (expiresAt - atMs)
    }
    return expiresAt
  }
  return timeoutSeconds ? nowMs + timeoutSeconds * 1000 : null
}

/** 取/建页签会话 id。 */
function initSessionId(): string {
  try {
    const stored = sessionStorage.getItem(SESSION_ID_KEY)
    if (stored) {
      return stored
    }
    const created = createSessionId()
    sessionStorage.setItem(SESSION_ID_KEY, created)
    return created
  } catch {
    return createSessionId()
  }
}

/** AI 栏展开态（页签级记忆：用户收起后刷新仍保持收起，裁剪性不被刷新打回）。 */
const EXPANDED_KEY = 'ai-panel-expanded'

function initExpanded(): boolean {
  try {
    return sessionStorage.getItem(EXPANDED_KEY) !== '0'
  } catch {
    return true
  }
}

function persistExpanded(expanded: boolean): void {
  try {
    sessionStorage.setItem(EXPANDED_KEY, expanded ? '1' : '0')
  } catch {
    // 隐私模式忽略：展开态只是展示偏好
  }
}

function readMirror(sessionId: string): AiSessionMirror | null {
  try {
    const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
    if (!raw) {
      return null
    }
    return JSON.parse(raw) as AiSessionMirror
  } catch {
    return null
  }
}

/**
 * 旧镜像迁移（对话时序包 批1 §6.4）：v1 镜像没有 `parts` 段序列，就地补出来。
 *
 * 口径：
 * - 每条消息 `parts = [text(content)] + toolRuns.map(toToolPart)`（seg 按数组序 0..n 递增）；
 * - `pendingCall` 存在且能与某个 tool part 对上 toolCallId 时并入（kind→kind2、
 *   `requiresAction = status === 'pending'`）；段 status 只由 `run.status` 派生，不被待决口径覆盖；
 * - 迁移幂等：只对 `v < MIRROR_VERSION`（或缺 `v` / 损坏缺 `parts`）的镜像动手，迁移后置 `v`；
 * - 返回值 `migrated` 供调用方**立即回写**（不回写则下次刷新又迁一遍，且防抖写盘口径混乱）。
 */
function migrateMirror(mirror: AiSessionMirror): { messages: AiSessionMirror['messages']; migrated: boolean } {
  const version = typeof mirror.v === 'number' ? mirror.v : 0
  const intact = version >= MIRROR_VERSION
    && mirror.messages.every((message) => Array.isArray(message.parts))
  if (intact) {
    return { messages: mirror.messages, migrated: false }
  }
  const messages = mirror.messages.map((message) => {
    const toolRuns = Array.isArray(message.toolRuns) ? message.toolRuns : []
    const parts: MessagePart[] = [
      { kind: 'text', seg: 0, text: message.content ?? '', streaming: false },
      ...toolRuns.map((run, index): MessagePart => ({
        kind: 'tool',
        seg: index + 1,
        toolCallId: run.toolCallId,
        name: run.name,
        kind2: run.kind,
        status: run.status,
        args: run.args,
        result: run.result ?? null,
        reason: run.reason ?? null,
        messageId: run.messageId,
        requiresAction: false
      }))
    ]
    const pending = message.pendingCall
    if (pending) {
      const index = parts.findIndex((part) => part.kind === 'tool' && part.toolCallId === pending.toolCallId)
      const existing = index >= 0 ? parts[index] : undefined
      if (existing && existing.kind === 'tool') {
        parts.splice(index, 1, {
          ...existing,
          // 待决条目只供 kind2 与 requiresAction 两维；status 一律以 run.status 派生为权威
          // （存量 v1 镜像 toolRun 已终态、pendingCall 仍 pending/approved 时不得被覆盖）
          kind2: pending.kind,
          requiresAction: pending.status === 'pending'
        })
      }
    }
    return { ...message, parts }
  })
  return { messages, migrated: true }
}

/**
 * 对话时序包 批2 前置（GLM o1）：镜像读取侧的 requiresAction 残留收口。
 *
 * 存量 v2 镜像里已持久化的 requiresAction=true 不会被迁移幂等修正，刷新即复活挂起语义 ——
 * 故在读取侧（loadHistory 镜像分支）收口，且必须发生在 writeMirror 回写**之前**：
 * 仅对 done 的消息，把仍挂 requiresAction 的工具段降级（requiresAction=false；段 status
 * 仍 pending 的一并降为 expired，与 loadHistory 空历史的 expired 降级惯例一致）。
 * 幂等（二次执行无变化）；返回是否有改动（供调用方决定是否需要回写镜像）。
 *
 * 批3③b D3（R-139①）死码清除：**入参契约 = 调用方须已归一 `done`**（loadHistory 镜像分支在
 * 调用本函数之前已强制 `{ ...message, streaming:false, done:true }`，writeMirror 落镜像的 7
 * 字段本就不含 done）——原"进行中轮（done!==true）不触碰"守卫因此**永不触发**，删除。
 * ⚠️ 反向风险（保留警示）：若未来直调镜像**原始**数据（done 为 undefined），`undefined !== true`
 *  ⇒ 全段跳过（守卫方向反了：本该收口却整段罢工）——届时须在调用点先归一，不得恢复本守卫。
 */
function sanitizeMirrorResidues(messages: ChatMessage[]): boolean {
  let changed = false
  for (const message of messages) {
    message.parts.forEach((part, index) => {
      if (part.kind === 'tool' && part.requiresAction) {
        message.parts.splice(index, 1, {
          ...part,
          requiresAction: false,
          status: part.status === 'pending' ? 'expired' : part.status
        })
        changed = true
      }
    })
  }
  return changed
}

/** 批2 前置 + 批3③a D5③：轮次收尾（done / error 帧）时，消息上仍在等动作的工具段收口——
 * pending 段落 `expired`；非 pending 段（含 unknown 违例段）保持原终态、只收 requiresAction。 */
function expireResidueToolParts(message: ChatMessage): void {
  message.parts.forEach((part, index) => {
    if (part.kind === 'tool' && part.requiresAction) {
      // 批3③a D5③：与 :233 对齐 —— 非 pending 的残留段（含 unknown 违例段）保持原终态，
      // 只收 requiresAction；不得把 unknown 翻成 expired。
      message.parts.splice(index, 1, {
        ...part,
        requiresAction: false,
        status: part.status === 'pending' ? 'expired' : part.status
      })
    }
  })
}

/**
 * 需要"整帧处理完后全量重算派生投影"的帧类型（§6.1 性能注）。
 *
 * 收录口径：可能改动**段结构**或**段状态**的帧（工具类帧、段边界帧、轮终态帧）。
 * `delta` / `reasoning` 是每 token 一帧的高频路径 —— 它们在 case 内与段文本**锁步**维护
 * `content` / `reasoning`，不触发全量重算（否则每 token 替换 `toolRuns` 数组 ⇒ 工具卡重渲染）。
 */
const DERIVE_FRAME_TYPES: ReadonlySet<string> = new Set([
  'message_start',
  'message_end',
  'tool_start',
  'tool_result',
  'confirm_request',
  'confirm_decision',
  'frontend_tool_request',
  'frontend_tool_result',
  'suspended',
  'done',
  'error'
])

function writeMirror(sessionId: string, messages: ChatMessage[], lastRunId: string | null): void {
  try {
    const mirror: AiSessionMirror = {
      // 批2 权威翻转后口径：parts 是**唯一真值**，content/reasoning/toolRuns/pendingCall 是同一次
      // 写入前由 `deriveProjections` 派生的投影。镜像里两份都留 —— 只为回滚（旧版前端读镜像）与
      // 外部读（调试/对账脚本直接看 JSON）不必自行派生；权威口径与迁移说明见 types/ai.ts。
      v: MIRROR_VERSION,
      savedAt: Date.now(),
      lastRunId,
      // 镜像只留展示所需字段（pendingCall 内嵌其中，刷新后可重建确认卡）；
      // `appliedSeqs` 是内部幂等记账，**不入镜像**（Pick 不含它）
      messages: messages.map((message) => ({
        id: message.id,
        role: message.role,
        content: message.content,
        parts: message.parts,
        reasoning: message.reasoning,
        toolRuns: message.toolRuns,
        pendingCall: message.pendingCall
      }))
    }
    sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify(mirror))
  } catch {
    // 配额/隐私模式等场景忽略：镜像是刷新体验优化，不是正确性依赖
  }
}

/** 历史消息 type → 展示角色。 */
function roleOf(type: string): ChatMessage['role'] {
  const normalized = (type || '').toUpperCase()
  if (normalized === 'USER') {
    return 'user'
  }
  if (normalized === 'SYSTEM') {
    return 'system'
  }
  return 'assistant'
}

/** 契约事件载荷 → 工作区动作记录（形态不符则忽略，不抛错）。 */
function readActionRecord(payload: unknown): WorkspaceActionRecord | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }
  const record = payload as Partial<WorkspaceActionRecord>
  if (typeof record.summary !== 'string' || typeof record.version !== 'number') {
    return null
  }
  return {
    version: record.version,
    summary: record.summary,
    at: typeof record.at === 'number' ? record.at : Date.now(),
    source: record.source === 'AI' ? 'AI' : '界面'
  }
}

/**
 * T2a-D#2（C2）：帧/响应体 status（后端 PendingStatus 大写口径）→ pendingCall.status（小写展示态）。
 * 映射必须穷举后端经 `frontend_tool_result` / 409 响应体**实发**的 status：本函数是帧/响应体
 * status 到 pendingCall 终态的唯一翻译层，漏一支就会被 handler 的兜底（置 null）吞成"卡片消失"。
 * 导出仅为批3③b D9 防漂移一致性锁（表驱动单测与 parts.ts#frontendResultStatus 对账）。
 *
 * 批3③b D9（R-141④）防漂移注记：与 parts.ts `frontendResultStatus` 的**唯一真实分叉 =
 * `SUCCEEDED`**（本表不收录、返回 null —— SUCCEEDED 非本响应体实发值；帧路表收录以覆盖
 * ConfirmGate#cancel 竞态）。实发交集七值两表同映射；`CANCELLED` 的 run-cancel 依据注释见下。
 */
export function frameStatusToPendingStatus(status: unknown): PendingToolCall['status'] | null {
  switch (status) {
    case 'FRONTEND_RESULT':
      return 'succeeded'
    case 'FRONTEND_CANCELLED':
      return 'cancelled'
    case 'CANCELLED':
      // T2a 返修（S1）：run-cancel 路径实发值 —— ConfirmGate.cancel 把仍 PENDING 的条目标为
      // CANCELLED（reason=RUN_CANCELLED），SseChatEmitter#frontendToolResult 取 getStatus() 入帧。
      // 与 form-cancel 的 FRONTEND_CANCELLED 同收 cancelled 终态，两条取消路径不再分叉
      return 'cancelled'
    case 'REJECTED_ARGUMENTS':
      // T2a 返修（S1）：入参被 schema 安全闸拒绝的实发值
      // （SpToolCallingManager#rejectFrontendArguments，未挂起/未执行），语义同 REJECTED
      return 'rejected'
    case 'TIMEOUT':
      return 'expired'
    case 'REJECTED':
      return 'rejected'
    case 'BLOCKED':
      return 'blocked'
    default:
      return null
  }
}

/**
 * T2a-D#2：该 pendingCall.status 是否已是终态。集合**含** `cancelled` —— 本地乐观 cancelled
 * 也算终态：用户取消后同一 toolCallId 不应再被重新挂起，`frontend_tool_request` case 的入口
 * 守卫（`hasDecidedPendingResidue`）据此把后到的同 toolCallId 帧挡在**任何新挂起投影**之外，
 * 防"取消竞态重挂"成一张 filling 死卡。
 * 帧终态覆盖乐观 cancelled 属**写入路径**，两条路各自的口径（F1/F2 收口修后）：
 * - `frontend_tool_result` 帧路：`applyFrameToParts` 把乐观 cancelled 一并算作本地终态
 *   （first-wins），帧 status 只改非终态段；
 * - 非帧路（409 响应体 / 续收快照）：`applyFrontendToolTerminal` 仍按后端终态覆盖
 *   pending/cancelled 段。
 * 两者都与本函数的**入口拦截**语义互不冲突（入口拦截在 `frontend_tool_request` / `suspended`）。
 *
 * 批2（权威翻转）：入参同时接受段侧 `ToolRunStatus` —— 判据改读段（`hasDecidedPendingResidue`
 * 读 `part.status`），两个枚举的终态集合在这里取并集。
 *
 * 批3③b D9（R-141④）防漂移注记：本枚举（7 态：succeeded/rejected/blocked/expired/cancelled/
 * unknown/failed）与段侧 `localTerminal`（parts.ts 的 `frontend_tool_result` case 内）**同 7 态、无共享常量**
 * （parts.ts 不依赖 store 的纯函数层约束）——漂移由 frontend-tool-result-terminal.spec.ts
 * 的 D9 表驱动一致性锁兜底，不得为"消除重复"抽公共表。
 *
 * 批3③a D6(A)：集合纳入 `unknown` 和 `failed`（两者均为终态）—— 否则 unknown 段不被视为
 * 已决，重投帧可再次挂起（挂起卡复活）。
 * **保护面如实申报（不得写成"全覆盖"）**：两条帧路守卫（frontend_tool_request / suspended）
 * 均带 GENERATIVE_FORM_TOOL 前缀，故本扩充实际保护 = ①快照恢复路（restoreSuspendedSnapshot
 * 全条目）+ ②GF 帧路；**通用 FRONTEND 工具的重投帧现状不经本守卫**（handleFrame 直跑
 * runFrontendTool）—— 既有缺口归 ③b（R-141③），本批不闭合。
 */
function isFrameDecidedPendingStatus(status: PendingToolCall['status'] | ToolRunStatus): boolean {
  return status === 'succeeded' || status === 'rejected' || status === 'blocked'
    || status === 'expired' || status === 'cancelled'
    || status === 'unknown' || status === 'failed'
}

export const useAiStore = defineStore('ai', {
  state: () => ({
    /** 页签级会话 id（记忆窗口 CONVERSATION_ID） */
    sessionId: initSessionId(),
    /** AI 面板是否展开（裁剪性：收起后业务照常；页签级记忆，刷新后保持用户的选择） */
    expanded: initExpanded(),
    messages: [] as ChatMessage[],
    /** 是否有轮次在进行 */
    loading: false,
    /** 当前轮 runId（来自 start 帧） */
    activeRunId: null as string | null,
    /** 是否处于挂起等待（suspended / confirm_request 帧口径，N6 裁决） */
    suspended: false,
    /** 连接/降级提示（断流、重连、池饱和、无 key 等） */
    notice: null as string | null,
    /** 409 SESSION_BUSY 的现场（可一键 reattach） */
    busyConflict: null as SessionBusyConflict | null,
    /** 503 挂起池饱和现场 */
    poolSaturated: null as SuspendPoolSaturated | null,
    /** AI 可用性 */
    health: null as AiHealth | null,
    healthError: null as string | null,
    /** 历史对账是否完成（UI 可用它做骨架屏） */
    historyLoaded: false,
    /**
     * 本轮是否已被用户停止。
     * 只由 `stop()` 置位；复位点在「新连接建立处」—— `send()` / `reattachActive()` /
     * `resumeSnapshotStream()`（新连接 ≠ 被停止的那条连接，红队 MINOR-1 收口）—— 与 `resetSession()`。
     * 用途**仅限**抑制"主动停止轮"的断流提示 —— abort 会让 `onClose(terminal=false)` 到达，
     * 那是停止的正常收尾而非断流。新增调用方直接改写它会稀释该语义，请勿在其它地方置位。
     */
    stopped: false,
    /**
     * 断流标志（ADR-6）：本轮结束时没收到 done/error 终帧 → 提示"连接中断，结果可能不完整"。
     * 与 notice 分开，便于面板用不同级别的 Alert 呈现（notice 覆盖更多降级场景）。
     */
    streamInterrupted: false,
    /** 最近一次工作区契约事件（面板"工作区已同步"chip 的数据源） */
    contextSync: null as WorkspaceActionRecord | null,
    /** 最近一次 ui_event 分发结果（AI 驱动工作区的回执展示） */
    lastUiEventResult: null as { event: string; handled: boolean; message: string } | null,
    /**
     * GF-B（DC-15）：当前待填写的生成式表单（generative_form 特判挂起，等用户填写）。
     * 不进镜像：刷新后由 pendingCall 的 expired 降级与后端超时收敛。
     */
    activeForm: null as ActiveGenerativeForm | null,
    /**
     * 已收到的最大帧序号（DC-14 T6）：reattach 时回传，服务端只补增量。
     * 按 runId 记录 —— 换轮次（新 runId）即从零开始，不跨轮混用序号。
     */
    runSeq: {} as Record<string, number>,
    /**
     * 批4④ A5'/D4：折叠块展开态（**会话内内存态**：不进镜像、不做 sessionStorage）。
     * 三态（D-C6）：true = 显式展开 / false = 显式收起 / 键缺省（undefined）= 未触碰 ——
     * 自动逻辑（defaultExpanded）只作用于未触碰组；requiresAction 组强制展开（resolveBlockExpanded，
     * 手动收起不生效）。resetSession / 新建会话须清空（防跨会话串味）。
     */
    expandedBlocks: {} as Record<string, boolean>
  }),

  getters: {
    /** 当前待处理（待确认 / 待前端执行）的工具调用。 */
    pendingToolCall(state): PendingToolCall | null {
      for (let i = state.messages.length - 1; i >= 0; i -= 1) {
        const message = state.messages[i]
        if (!message) {
          continue
        }
        if (message.role === 'assistant' && message.pendingCall) {
          // T2a-D#2（C3）：非 pending 终态（含旧镜像残留）按终态展示、跳过继续向前扫，
          // 不得提前短路（否则会漏掉更早消息里真正 pending 的调用）
          if (message.pendingCall.status === 'pending') {
            return message.pendingCall
          }
        }
      }
      return null
    },
    /** 需要人工放行的确认门（DANGER 工具）。 */
    pendingConfirm(): PendingToolCall | null {
      const pending = this.pendingToolCall as PendingToolCall | null
      return pending && pending.kind === 'CONFIRM' ? pending : null
    },
    /** AI 侧是否可用（无 key / 无 Redis 时降级提示）。 */
    available(state): boolean {
      return state.health?.available ?? false
    },
    /** 降级原因文案（不可用时）。 */
    unavailableReason(state): string | null {
      if (state.health?.available) {
        return null
      }
      if (state.healthError) {
        return state.healthError
      }
      return state.health?.message ?? null
    },
    /**
     * 运行状态文案（N6 裁决：按帧口径区分）。
     * - 生成中：本轮进行中且没有挂起等待；
     * - 挂起等待：收到 suspended / confirm_request / frontend_tool_request 后；
     * - 空闲：没有进行中的轮次。
     */
    runStateLabel(state): string {
      if (!state.loading) {
        return '空闲'
      }
      return state.suspended ? '挂起等待中…（待人工确认或待前端执行）' : '生成中…'
    },
    /** 输入区是否可用（执行中/挂起等待中一律禁发）。 */
    canSend(state): boolean {
      return !state.loading
    }
  },

  actions: {
    toggleExpand(): void {
      this.expanded = !this.expanded
      persistExpanded(this.expanded)
    },

    /** 启动会话镜像：messages 任何变化（含流式 token）防抖写入 sessionStorage。 */
    startMirror(): void {
      if (mirrorStarted) {
        return
      }
      mirrorStarted = true
      this.$subscribe(() => {
        if (mirrorTimer !== null) {
          clearTimeout(mirrorTimer)
        }
        mirrorTimer = setTimeout(() => writeMirror(this.sessionId, this.messages, this.activeRunId), 300)
      }, { detached: true, deep: true })
    },

    /**
     * 启动工作区契约订阅（S4.4c 契约联动）。
     *
     * 订阅两个主题：
     * - `workspace_changed`：用户在业务页面上的动作（选配置/切步骤/改条件/编辑表格…）
     *   → 面板显示"工作区已同步"chip；动作本身已由 workspace store 记入 recentActions，
     *   随下一轮 `buildContext()` 注入给模型（AI 面板不自己拼上下文，仍走窄接口）；
     * - `ui_event_result`：AI 驱动工作区动作的回执（AI→业务方向）。
     */
    startWorkspaceSync(): void {
      if (workspaceSyncStarted) {
        return
      }
      workspaceSyncStarted = true
      workspaceSyncStops.push(
        onWorkspaceEvent('workspace_changed', (payload) => {
          this.contextSync = readActionRecord(payload)
        }),
        onWorkspaceEvent('ui_event_result', (payload) => {
          const record = payload as { event?: unknown; handled?: unknown; message?: unknown } | null
          if (!record || typeof record.event !== 'string') {
            return
          }
          this.lastUiEventResult = {
            event: record.event,
            handled: record.handled === true,
            message: typeof record.message === 'string' ? record.message : ''
          }
        })
      )
    },

    /** 退订工作区事件（面板卸载时调用；测试/热更场景亦可用）。 */
    stopWorkspaceSync(): void {
      workspaceSyncStops.forEach((stop) => stop())
      workspaceSyncStops = []
      workspaceSyncStarted = false
    },

    /** 探测 AI 可用性（不可用时后端 503 + 同一个体，属正常语义）。 */
    async probeHealth(): Promise<void> {
      try {
        this.health = await aiApi.health()
        this.healthError = null
      } catch (error) {
        this.health = null
        this.healthError = error instanceof Error ? error.message : 'AI 可用性探测失败'
      }
    },

    /**
     * 进入页面时恢复历史（刷新不丢）：
     * 1) 先按 sessionStorage 镜像即时恢复展示；
     * 2) 再与 `GET /api/ai/history/{sessionId}` 对账 —— 后端有内容以后端为准，
     *    后端为空（重启/TTL 过期）则保留镜像，但把挂起的工具调用降级为 expired
     *    （旧会话已无法回灌续跑，用户发新消息即自动按新会话重建）。
     *
     * 红队 MAJOR-1 收口（会话身份守卫）：本 action 跨越 await 取历史，期间用户可能新建对话 ——
     * `resetSession()` 会换 `sessionId` 并清空 messages/轮次状态。故发起时先记下会话身份，
     * 完成路径每次落盘（写 `messages`、触发 `restoreSuspendedSnapshot`）前对账：身份已变即整体
     * 丢弃，不让旧会话的历史/挂起卡写进新会话（写后写竞态）。守卫口径同本文件 isCurrentRound 同族。
     */
    async loadHistory(): Promise<void> {
      const sid = this.sessionId
      this.startMirror()
      const mirror = readMirror(this.sessionId)
      if (mirror && Array.isArray(mirror.messages) && mirror.messages.length > 0) {
        // 对话时序包 批1 §6.4：旧镜像（无 parts / v<2）先同步迁移出 parts 再落展示态，
        // 迁移后**立即回写**新镜像 —— 早于 startMirror 的防抖写盘（300ms），顺序与
        // "先行置位再触发重建"同族：先把镜像定成新版形态，之后的写盘一律是新口径
        const migrated = migrateMirror(mirror)
        this.messages = migrated.messages.map((message) => ({
          ...message,
          streaming: false,
          done: true,
          // 批3③b D4（R-140①-4）：存量 v2 镜像把 message.parts 原样入镜像（writeMirror 不做
          // 段级归一），段级 streaming=true 假态随镜像存活；消费侧 openStreamPart /
          // sealTrailingStreamPart 读段 streaming（段边界假态）—— 与上一行消息级归一同口径，
          // 读取侧逐段归一。parts 是数组须逐段 map；**不收进** sanitizeMirrorResidues（防职责扩大）。
          parts: message.parts.map((part) => ('streaming' in part && part.streaming ? { ...part, streaming: false } : part))
        }))
        this.activeRunId = mirror.lastRunId ?? null
        // 批2 前置（GLM o1）：存量镜像残留的 requiresAction=true 在读取侧收口（v1 迁移结果与
        // v2 intact 都生效），须在 writeMirror 回写之前完成 —— 否则刷新复活挂起语义
        const sanitized = sanitizeMirrorResidues(this.messages)
        // 批2（权威翻转）：镜像里的旧投影一律按段重算（存量 v2 镜像可能是双写期的产物，
        // 投影里仍留着"已终态但挂起卡不消失"的旧口径）—— 落展示态前先归一到段口径
        this.messages.forEach((message) => deriveProjections(message))
        if (migrated.migrated || sanitized) {
          // 只在"迁移过 / 收口过"时回写：无变化时回写会让每次刷新都写盘
          // （既有幂等断言 `savedAt` 不变即锁这一口径），派生本身是幂等纯函数
          writeMirror(this.sessionId, this.messages, this.activeRunId)
        }
      }

      let history: AiHistory
      try {
        history = await aiApi.history(this.sessionId)
      } catch {
        if (this.sessionId !== sid) {
          return // 会话已换：过期失败不落任何状态（新会话的 historyLoaded 由新会话自己落）
        }
        this.historyLoaded = true
        return // 后端不可达：保留镜像展示
      }

      // 会话身份对账（红队 MAJOR-1）：不匹配则整体丢弃 —— 下面的 messages 写入与
      // restoreSuspendedSnapshot（读 this.sessionId 拉 runs/current、就地重建挂起卡）都不得落进新会话
      if (this.sessionId !== sid) {
        return
      }

      if (!history.messages || history.messages.length === 0) {
        // 后端历史为空（重启/TTL 过期）：镜像里的挂起已无法回灌续跑 —— 待决工具段过期降级
        // （与 loadHistory 空历史的 expired 降级惯例一致）。批2：降级写**段**，投影随之派生。
        for (const message of this.messages) {
          if (!message.parts.some((part) => part.kind === 'tool' && part.requiresAction)) {
            continue
          }
          expireResidueToolParts(message)
          deriveProjections(message)
        }
        this.historyLoaded = true
        // T2b-D#3：historyLoaded 先行置位后再触发快照重建（loadHistory 先行完成，
        // 消除镜像/历史写入与挂起重建之间的写后写竞态）；后端不可达的 catch 分支不重建
        await this.restoreSuspendedSnapshot()
        return
      }

      // 批3③b D7(B)（R-141②）注记 **R-144 改判 (A)**（依据 = docs/evidence/补档-CHATTIMELINE-批3③b-决策卡-K3.md:180
      // 「R-144 落地 ⇒ 本项必须改判 (A)（读真实状态）」）：后端历史载荷按 toolCallId 携载
      // `toolCallStates`（types/ai.ts AiHistoryMessage），下方段构造**有状态 ⇒ 经词表单表
      // `historyToolCallStatus`（parts.ts）读真态**。双口径并存：① 有状态 ⇒ 读真态；② 无状态
      //（旧载荷存量 / resume 重建轮（T4 第二来源，ResumeService 重建消息不带状态））⇒ 维持占位
      // 值 status:'succeeded' / kind2:'UNKNOWN'（**非事实断言**）——占位口径下禁令照旧有效：
      // **任何消费方不得以该 succeeded 值做业务判定**。词表与两帧面表的有意分叉见 D9 注记第③对。
      // R-149 双条目合并：后端历史里工具结果是**独立 TOOL 条目**（Spring AI 形态），
      // 第一遍遍历建响应索引，第二遍构建 ChatMessage；TOOL 条目跳过（不生成消息，杜绝空白块）。
      // 配对规则：① call.id 三态为真按 id 匹配（consume-on-match：命中即从索引摘除）；
      // ② 未中退序位兜底：未配对 call × 未消费 response 按序互配 + name 相等闸；
      // ③ 混排先 id 后序位；④ 仍不中 result=null（卡片保留不丢）。
      const responsePool: NonNullable<AiHistoryMessage['toolResponses']>[number][] = []
      const responseIndex = new Map<string, NonNullable<AiHistoryMessage['toolResponses']>[number]>()
      for (const item of history.messages) {
        if ((item.type || '').toUpperCase() !== 'TOOL') {
          continue
        }
        for (const response of item.toolResponses ?? []) {
          responsePool.push(response)
          // 防御性 tie-break（C2，红队 O-1）：后端 Spring AI 的 toolCallId 唯一、实际不产重复 id；
          // 万一载荷出现重复 id，后出现覆盖先出现仅作**确定性兜底**，不宣称符合后端实际产出序
          if (response.id !== null && response.id !== undefined && response.id !== '') {
            responseIndex.set(response.id, response)
          }
        }
      }
      const consumedResponses = new Set<object>()
      const unmatchedCalls: Array<{ name: string; part: ToolPart }> = []
      this.messages = history.messages.flatMap((item, index) => {
        if ((item.type || '').toUpperCase() === 'TOOL') {
          return [] // TOOL 条目：响应已并入 ASSISTANT 工具段，本身不再产生消息
        }
        const role = roleOf(item.type)
        // 批2：历史整体替换的分支同样**只建段**（正文段 + 按 toolCalls 顺序的工具段，
        // result 由 TOOL 条目的 toolResponses 回填（id 优先、序位兜底）；历史条目没有待决语义，
        // 故 requiresAction=false），旧投影由 deriveProjections 一次产出，不手工拼 content / toolRuns。
        // R-144（C-3）：status/kind2 双口径——载荷 `toolCallStates[call.id]` 有 status ⇒ 经词表单表
        // `historyToolCallStatus` 读真态；缺（旧载荷 / resume 重建轮 T4）⇒ 占位 'succeeded'。kind 有
        // 数据 ⇒ 还原（CONFIRM/FRONTEND/BACKEND），缺 ⇒ 'UNKNOWN'（不臆造默认值）。
        // R-144（C-6）保序：text 空白且 toolCalls ≥ 1 ⇒ **不产空前导 text 段**（载荷无段序字段，
        // 同条目 text↔toolCalls 相对序不可知，消「空 text 段打头」渲染面）；工具段 seg = 构造后
        // 数组下标（有 text 段时 = toolIndex+1，无 = toolIndex；R-150 合并 pass 仍全局重排）。
        const calls = item.toolCalls ?? []
        const blankText = (item.text ?? '').trim() === ''
        const hasTextPart = !blankText || calls.length === 0
        const parts: MessagePart[] = [
          ...(hasTextPart ? [{ kind: 'text', seg: 0, text: item.text ?? '', streaming: false } as MessagePart] : []),
          ...calls.map((call, toolIndex): ToolPart => {
            const name = call.name ?? '未知工具'
            const state = call.id !== null && call.id !== undefined && call.id !== ''
              ? item.toolCallStates?.[call.id]
              : undefined
            const mappedStatus = state?.status !== undefined ? historyToolCallStatus(state.status) : null
            const kind = state?.kind
            let matched: NonNullable<AiHistoryMessage['toolResponses']>[number] | undefined
            if (call.id !== null && call.id !== undefined && call.id !== '') {
              const hit = responseIndex.get(call.id)
              if (hit) {
                responseIndex.delete(call.id) // consume-on-match：命中即从索引摘除
                matched = hit
              }
            }
            const part: ToolPart = {
              kind: 'tool',
              seg: hasTextPart ? toolIndex + 1 : toolIndex,
              toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`,
              name,
              kind2: kind === 'CONFIRM' || kind === 'FRONTEND' || kind === 'BACKEND' ? kind : 'UNKNOWN',
              status: mappedStatus ?? 'succeeded',
              args: call.arguments,
              result: null,
              requiresAction: false
            }
            if (matched) {
              consumedResponses.add(matched)
              part.result = matched.responseData ?? null
            } else {
              unmatchedCalls.push({ name, part }) // 序位兜底候选（见下方统一互配）
            }
            return part
          })
        ]
        const message: ChatMessage = {
          // R-149：index 锁死 = 原始数组下标（含被跳过 TOOL 条目的空洞，不用合并后下标）
          id: `history-${index}`,
          role,
          content: '',
          parts,
          reasoning: '',
          toolRuns: [],
          pendingCall: null,
          streaming: false,
          done: true,
          // R-154 #17：历史路时间戳 = 后端条目 metadata.timestamp（原样透传，仅展示用途）
          timestamp: item.timestamp
        }
        deriveProjections(message)
        return [message]
      })
      // 序位兜底（R-149②）：未配对 call × 未消费 response 按序互配，name 相等才配；
      // 仍不中的 call 保持 result=null —— 卡片保留不丢（残缺历史无主 response 在此一并兜底丢弃）
      for (const unmatched of unmatchedCalls) {
        const hitIndex = responsePool.findIndex(
          (response) => !consumedResponses.has(response) && (response.name ?? '未知工具') === unmatched.name
        )
        if (hitIndex < 0) {
          continue
        }
        const hit = responsePool[hitIndex] as NonNullable<AiHistoryMessage['toolResponses']>[number]
        consumedResponses.add(hit)
        unmatched.part.result = hit.responseData ?? null
      }
      // C1（复核 m-1）：序位兜底在 deriveProjections（flatMap 内）**之后**才写 part.result ——
      // 投影按值快照（parts.ts）会滞留 null。对含工具段的受影响消息重跑 deriveProjections
      // （幂等纯函数；契约：段字段落段后须同步派生投影），兜底命中的 toolRuns[].result 随之就位。
      this.messages.forEach((message) => {
        if (message.parts.some((part) => part.kind === 'tool')) {
          deriveProjections(message)
        }
      })
      // ── R-150 后置 pass：同一用户轮内连续 ASSISTANT 合并（中间仅夹被跳过的 TOOL，遇 USER/SYSTEM 即断）──
      // 位置钉死在 R-149 配对 + 序位兜底 + C1 重派生**全部完成之后**（GLM C1：兜底在 flatMap 后才
      // 回填 result，遍历内联判定必误判）。闸（结构性判据）：被并条目的每个 toolCall 在原始历史窗口内
      // 存在可配对 TOOL 条目（id 相等；call.id 缺失时 name 相等；responseData=null 也算有回应）——
      // 与前端配对启发成败无关。悬空 A(T)（无任何可配对 TOOL）不并不吞、也不开窗（纵深防御，
      // S0 实证该形态后端不可达，闸保留防旧镜像/未来形态）。drop：无 toolCalls 且 text 空的
      // ASSISTANT 直接丢弃（防旧镜像空白块复活），丢弃同时断窗。
      const toolWindowEntries = history.messages.filter((item) => (item.type || '').toUpperCase() === 'TOOL')
      const hasPairableResponse = (call: { id?: string | null; name?: string }): boolean => {
        const callId = call.id ?? null
        return toolWindowEntries.some((entry) =>
          (entry.toolResponses ?? []).some((response) => {
            if (callId !== null && callId !== undefined && callId !== '') {
              return response.id === callId
            }
            return (response.name ?? '未知工具') === (call.name ?? '未知工具')
          })
        )
      }
      const builtMessages = this.messages
      const mergedMessages: ChatMessage[] = []
      let builtCursor = 0
      let group: ChatMessage[] = []
      const flushGroup = (): void => {
        if (group.length === 0) {
          return
        }
        if (group.length === 1) {
          mergedMessages.push(group[0] as ChatMessage)
        } else {
          // 合并：parts 顺次拼接（保留空 text 段），seg 全局单调重排（渲染 key = kind+'-'+seg）；
          // id 取首条（history-{首条原始 index}，flatMap 时已钉）；被并条目的序位兜底 toolCallId
          // 用其自身原始 index（flatMap 时已钉，此处不触碰）
          const head = group[0] as ChatMessage
          head.parts = group.flatMap((member) => (member as ChatMessage).parts)
            .map((part, seg) => ({ ...part, seg })) as MessagePart[]
          deriveProjections(head) // 拼接 + 重 seg 后重派生投影（content/toolRuns 随 parts）
          mergedMessages.push(head)
        }
        group = []
      }
      for (const item of history.messages) {
        const type = (item.type || '').toUpperCase()
        if (type === 'TOOL') {
          continue // 无消息产出（R-149 已跳过），不断窗
        }
        const message = builtMessages[builtCursor] as ChatMessage
        builtCursor += 1
        if (type === 'USER' || type === 'SYSTEM') {
          flushGroup()
          mergedMessages.push(message)
          continue
        }
        const calls = item.toolCalls ?? []
        if (calls.length === 0 && (item.text ?? '').trim().length === 0) {
          flushGroup() // drop 规则：空白 ASSISTANT 丢弃且断窗
          continue
        }
        // 闸：被并条目的每个 toolCall 须存在可配对 TOOL 条目（无 toolCalls 时恒真）
        if (!calls.every((call) => hasPairableResponse(call))) {
          flushGroup() // 悬空 A(T)：不并、不吞、不开窗
          mergedMessages.push(message)
          continue
        }
        group.push(message)
      }
      flushGroup()
      this.messages = mergedMessages
      // 历史里若存在未决挂起，补一张待处理卡片（前端工具/确认门都可能出现在这里）
      this.historyLoaded = true
      // T2b-D#3：historyLoaded 先行置位后再触发快照重建（loadHistory 先行完成，
      // 消除历史整体替换与挂起重建之间的写后写竞态）
      await this.restoreSuspendedSnapshot()
    },

    /**
     * T2b-D#3（红队 A1/A3/B3）：刷新后的挂起卡走"快照重建 + 增量续收"，禁止全量回放重建
     * （防双重渲染/孤儿过滤失效/死卡复活）。数据源为后端 runs/current 的 pendingEntries 快照，
     * 由本 action 在 loadHistory 完成后触发；历史/镜像既有的渲染形态一律兜底，不复制历史工具卡。
     *
     * 红队 R-130/R-132（会话身份 / 轮次身份）：本函数内部自带 await（runs/current），
     * 故**入口自持身份**——取数用入口捕获的 sid（不用写回时的 this.sessionId），
     * await 返回后、**任何写回之前必须二次对账**：会话已换（sid 不等）或同会话已起新轮
     * （`currentAbort` 句柄已被新连接替换）时，旧快照一律不得写回。否则：旧会话的挂起卡 /
     * activeForm / activeRunId 会落进新会话，并用旧快照 runId 覆盖在跑轮的 activeRunId
     * ⇒ 紧接着的 resumeSnapshotStream 会 abort 掉在跑轮自己的连接（死锁链）。
     *
     * **规则：本函数内任何新增 await 之后、写回之前都必须重新对账**（对账口径见下方守卫）。
     */
    async restoreSuspendedSnapshot(): Promise<void> {
      const sid = this.sessionId
      const abortAtEntry = currentAbort
      let current: AiRunCurrent
      try {
        current = await aiApi.runsCurrent(sid)
      } catch {
        // 该路径不可用时刷新体验降级为既有镜像/历史形态（空历史的 expired 降级规则兜底）
        return
      }
      // 取数窗口内的身份对账（R-130/R-132）：sid 不等 = 会话已换；abort 句柄已换 = 同会话已起
      // 新轮 —— 两种都说明这份快照属于过期的现场，整体丢弃（含续收连接的建立）
      if (this.sessionId !== sid || currentAbort !== abortAtEntry) {
        return
      }
      if (!current.found || !current.runId || !current.awaitingExternal) {
        // 已决/空态：不重建挂起卡，既有历史渲染与空历史 expired 降级规则兜底
        return
      }

      // 已决条目收敛（不留 pending 残留）：快照里非 PENDING 的条目若本地仍有 pending/乐观
      // cancelled 残留，按 frameStatusToPendingStatus 同口径就地收敛。applyFrontendToolTerminal
      // 内部已做 kind/name 判定与 first-wins 单调，直接调即可
      for (const entry of current.pendingEntries) {
        if (entry.entryStatus === 'PENDING') {
          continue
        }
        const mapped = frameStatusToPendingStatus(entry.entryStatus)
        if (mapped && this.hasDecidedPendingResidue(entry.toolCallId)) {
          this.applyFrontendToolTerminal(entry.toolCallId, mapped)
        }
      }

      // 重建：仅 entryStatus === 'PENDING' 的条目，已决条目不重建挂起卡
      // （勘误 4 同口径：hasDecidedPendingResidue / isFrameDecidedPendingStatus）
      let host: ChatMessage | null = null
      for (const entry of current.pendingEntries) {
        if (entry.entryStatus !== 'PENDING') {
          continue
        }
        if (this.hasDecidedPendingResidue(entry.toolCallId)) {
          continue
        }
        const kind = entry.kind === 'CONFIRM' ? 'CONFIRM' : 'FRONTEND'
        // 宿主消息：优先镜像/历史里已持该工具**段**的消息；否则最后一条助手消息；都没有才
        // append 一条空助手消息作宿主 —— 唯一允许的新消息形态（历史/镜像皆无宿主），
        // 不复制历史工具卡、无双渲染。
        // 批2（权威翻转）：判据由 `toolRuns.some` 改读 `parts`（段是唯一真值，语义等价）
        host = this.messages.find((item) => item.parts.some(
          (part): boolean => part.kind === 'tool' && part.toolCallId === entry.toolCallId
        ))
          ?? [...this.messages].reverse().find((item) => item.role === 'assistant')
          ?? null
        if (!host) {
          host = this.appendAssistantMessage('')
          host.streaming = false
          host.done = true
        }
        // 批2：挂起卡不再是"就地写独立投影"，而是**落段再派生** —— 段侧 requiresAction=true
        // 因为该条目正是"等人工/前端动作"的那一个（派生出唯一 pendingCall，不新增重复工具卡）
        this.upsertToolPart(host, {
          toolCallId: entry.toolCallId,
          name: entry.name,
          kind2: kind,
          status: 'pending',
          args: entry.arguments ?? undefined,
          requiresAction: true
        })
        deriveProjections(host)
        if (entry.name === GENERATIVE_FORM_TOOL && !this.activeForm) {
          // deadline = null：不武装本地计时器，后端权威超时收敛（快照路径无 expiresAt 折算依据）
          const spec = parseFormSchema(parseToolArgs(entry.arguments)?.form)
          if (spec) {
            this.activeForm = {
              toolCallId: entry.toolCallId,
              runId: current.runId,
              form: spec,
              status: 'filling',
              error: null,
              deadline: null
            }
          }
          // schema 非法：只留挂起卡（用户可取消），不武装表单态
        }
        this.suspended = true
        this.activeRunId = current.runId
      }

      if (host) {
        // 增量续收：lastSeq = archiveMaxSeq 只收新帧（见 resumeSnapshotStream）
        void this.resumeSnapshotStream(current.runId, current.archiveMaxSeq, host.id)
      }
    },

    /**
     * T2b-D#3 增量续收（仿 reattachActive，但不新起消息 —— 挂起卡宿主已由快照重建就地就位）。
     * lastSeq = archiveMaxSeq：服务端只补发增量帧，禁全量回放（红队 A1/A3/B3）。
     */
    async resumeSnapshotStream(runId: string, lastSeq: number, hostMessageId: string): Promise<void> {
      const previous = currentAbort
      this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, lastSeq)
      this.loading = true
      this.streamInterrupted = false
      // 红队 MINOR-1 收口：新连接 ≠ 被停止的那条连接 —— `stop()` 留下的 stopped 置位只该
      // 作用于被中止的那一轮，不能跨连接生效（否则本连接断流时 onClose 会误判"用户主动停止"
      // 而不报"结果可能不完整"）。复位点与 send() / resetSession() 对齐。
      this.stopped = false
      const controller = new AbortController()
      currentAbort = controller
      activeRoundHostId = hostMessageId
      /** R6（issue#5）：续收连接是否仍是"当前轮"（旧连接的断流提示不写进新轮）。 */
      const isCurrentRound = (): boolean => currentAbort === controller
      // 防双连接：**先换身份再中止旧连接** —— 旧连接的收尾此时判定为"非本轮"，
      // 其 onClose 的断流提示不会写脏新连接的现场
      previous?.abort()
      try {
        await aiApi.reattachRun(
          runId,
          {
            onFrame: (frame) => this.handleFrame(frame, hostMessageId),
            onReconnect: (attempt, delayMs) => {
              if (!isCurrentRound()) {
                return
              }
              this.notice = `续收中断，${Math.round(delayMs / 1000)} 秒后第 ${attempt} 次重试`
            },
            onClose: (info) => {
              if (!isCurrentRound()) {
                return
              }
              // 苞 B 项 3：用户主动停止不报断流（同 send 的守卫；重挂/续收路径同样适用）
              if (!info.terminal && !this.stopped) {
                this.streamInterrupted = true
                this.notice = `连接中断，结果可能不完整（${info.reason ?? '断流'}）。挂起卡保留，可刷新重试。`
              }
            }
          },
          { signal: controller.signal, lastSeq }
        )
      } finally {
        if (isCurrentRound()) {
          // 不置 suspended = false：挂起卡状态由帧/投影决定，done 帧的 handler 会自己清
          this.loading = false
          currentAbort = null
          activeRoundHostId = null
        }
      }
    },

    /**
     * R4（issue#5）：发送守卫命中的统一提示 —— 面板层与 store 层共用同一文案，避免两处漂移。
     * 只写提示：**不动 `inputText`**（草稿保留），也不触碰 `send()` 里的清空逻辑。
     */
    noticeBusySend(): void {
      this.notice = '本轮进行中：本条消息未发送，可等本轮结束后重发。'
    },

    /** 发一轮消息（含渐进披露上下文注入 + 工作区动作同步）。 */
    /**
     * @param resend.skipUserRecord 截断后重发通道（R-157 收口 MAJOR-1）：调用方已把用户消息
     *   原地保留/更新并截断其后消息（编辑重跑 / 重试 regenerate），send 不得再追加新用户消息 ——
     *   否则旧文本与新文本并存（编辑）或同一提问重复两次（重试）。
     */
    async send(text: string, resend: { skipUserRecord?: boolean } = {}): Promise<void> {
      const content = text.trim()
      if (!content) {
        // 空内容依旧静默（面板层已用 trim 拦过，此处只是防御）
        return
      }
      if (this.loading) {
        // R4（issue#5）：守卫不再静默 —— 当前 UI 下真实 Enter 到不了这里（输入区 disabled），
        // 但程序化调用与 loading/轮次失步窗口可达，必须给出可读反馈而不是丢弃用户输入
        this.noticeBusySend()
        return
      }
      const workspace = useWorkspaceStore()
      if (!resend.skipUserRecord) {
        this.recordUserMessage(content)
      }
      const assistant = this.appendAssistantMessage()
      this.loading = true
      this.stopped = false
      this.notice = null
      this.streamInterrupted = false
      this.busyConflict = null
      this.poolSaturated = null
      const controller = new AbortController()
      currentAbort = controller
      activeRoundHostId = assistant.id
      /** R1/R6（issue#5）：本轮是否仍是"当前轮" —— 以 AbortController 身份为准。 */
      const isCurrentRound = (): boolean => currentAbort === controller

      const handlers: SseFrameHandlers = {
        onFrame: (frame) => this.handleFrame(frame, assistant.id),
        onReconnect: (attempt, delayMs) => {
          if (!isCurrentRound()) {
            return // R6：旧轮的迟到重连公告不写进新轮
          }
          this.notice = `连接中断，${Math.round(delayMs / 1000)} 秒后重挂（第 ${attempt} 次）`
        },
        onClose: (info) => {
          if (!isCurrentRound()) {
            return // R6：旧轮断流提示不得覆盖新轮状态
          }
          // 苞 B 项 3：用户主动停止的轮次不报断流 —— abort → terminal=false 是停止的**正常**收尾，
          // 不是断流。`stopped` 只在 stop() 置位，在每次新连接建立处（send/reattachActive/
          // resumeSnapshotStream）与 resetSession 复位 ⇒ 只影响被停止的那一轮。
          if (!info.terminal && !this.stopped) {
            // ADR-6：缺 done/error 终帧 = 断流，必须显式提示"结果可能不完整"
            this.streamInterrupted = true
            this.notice = `连接中断，结果可能不完整（${info.reason ?? '未收到完成帧'}）。已生成的内容保留，可重新发送或一键重挂该轮。`
            if (this.activeForm !== null) {
              // 红队问题 2：断流是第五个收尾出口 —— 与 done/error/stop/resetSession 对齐，
              // 收表单态与本地超时计时器（刷新后由后端权威超时收敛）
              disarmFormExpiry()
              this.activeForm = null
            }
          }
        }
      }

      const options: SseStreamOptions = { signal: controller.signal }
      // 契约联动：上下文里带上"用户刚手点了什么"（buildContext 的 extra.recentActions）；
      // 动作只注入这一轮，构造完即清空，避免下一轮重复携带。
      const context = workspace.buildContext()
      workspace.clearRecentActions()
      try {
        await aiApi.chat(
          { sessionId: this.sessionId, message: content, context },
          handlers,
          options
        )
      } catch (error) {
        if (isCurrentRound()) {
          this.handleChatError(error, assistant.id) // R6：旧轮的异常提示不写进新轮
        }
      } finally {
        if (isCurrentRound()) {
          // R1（issue#5）：只有"仍是本轮"时才复位轮次级状态 —— 否则旧轮的收尾会把新轮抹平
          // （界面谎报空闲 + 新轮丢失本地取消句柄）
          this.loading = false
          this.suspended = false
          currentAbort = null
          activeRoundHostId = null
        }
        const target = this.messages.find((message) => message.id === assistant.id)
        if (target && !target.done) {
          target.streaming = false
          target.done = true
        }
      }
    },

    /** 处理 409/503 等开流前失败（按错误码给不同文案与动作）。 */
    handleChatError(error: unknown, assistantId?: string): void {
      if (assistantId) {
        const message = this.messages.find((item) => item.id === assistantId)
        if (message) {
          message.streaming = false
          message.done = true
          // 本轮压根没开始：给占位助手消息一个可读的收尾，避免留空气泡。
          // 批2：正文不再直写 `content` —— 文案写进 text 段（同段口径），投影由 derive 产出。
          // SESSION_BUSY 用 `||`（正文已有内容则不覆盖），其余错误只在正文为空时补文案；
          // "正文已有内容"的判据取 **parts**（渲染真值），不读派生投影（同 §6.3 的消费点口径）。
          const hasText = message.parts.some((part) => part.kind === 'text' && part.text !== '')
          const literal = error instanceof ApiError && error.code === ErrorCode.SESSION_BUSY
            ? '（本轮未开始：该会话已有一轮正在进行，可一键重挂接收它）'
            : error instanceof Error ? `（本轮未开始：${error.message}）` : null
          if (literal !== null && !hasText) {
            const part = openStreamPart(message, 'text')
            part.text = literal
            part.streaming = false
            deriveProjections(message)
          }
        }
      }
      if (!(error instanceof ApiError)) {
        this.notice = error instanceof Error ? error.message : '对话请求失败'
        return
      }
      switch (error.code) {
        case ErrorCode.SESSION_BUSY: {
          const body = error.bodyAs<SessionBusyConflict>()
          this.busyConflict = body
          this.notice = body
            ? `该会话已有一轮进行中（runId=${body.runId}），可一键重挂继续接收。`
            : '该会话已有一轮进行中，可尝试重挂。'
          break
        }
        case ErrorCode.SUSPEND_POOL_SATURATED: {
          const body = error.bodyAs<SuspendPoolSaturated>()
          this.poolSaturated = body
          this.notice = body
            ? `挂起池已饱和（容量 ${body.poolSize}），请稍后重试。`
            : '挂起池已饱和，请稍后重试。'
          break
        }
        case ErrorCode.AI_UNAVAILABLE:
          this.notice = `AI 未启用：${error.message}`
          void this.probeHealth()
          break
        case ErrorCode.AI_REDIS_UNAVAILABLE:
          this.notice = `AI 运行时依赖的 Redis 不可用：${error.message}`
          void this.probeHealth()
          break
        default:
          this.notice = error.message
      }
    },

    /**
     * 一键 reattach：重挂进行中那一轮（ADR-5）。
     * 409 的响应体已带 reattach 路径，这里只取 runId（路径由 sse.ts 统一拼）。
     *
     * `runId` 参数做**类型防御**：UI 侧若把点击事件当参数传进来（形如 `[object PointerEvent]`），
     * 会把 reattach 打到 404 上（S4.4c E6 实测发现的缺陷），故非字符串一律忽略、落回现场 runId。
     *
     * DC-14 T6：带上该 runId 已收到的最大帧序号 —— 服务端只补发 `seq > lastSeq` 的增量
     * （本地已有的帧不重发），回放与实时续接之间不会重复渲染。序号未知（首挂/旧格式帧）则不传，
     * 服务端全量回放（行为与 S4.4c 一致）。
     */
    async reattachActive(runId?: string): Promise<void> {
      const explicit = typeof runId === 'string' && runId.trim() !== '' ? runId : null
      const targetRunId = explicit ?? this.busyConflict?.runId ?? this.activeRunId
      if (!targetRunId) {
        this.notice = '没有可重挂的轮次（缺少 runId）'
        return
      }
      const previous = currentAbort
      const lastSeq = this.runSeq[targetRunId]
      const assistant = this.appendAssistantMessage('（正在重挂进行中的轮次…）')
      this.loading = true
      this.streamInterrupted = false
      // 红队 MINOR-1 收口：新连接 ≠ 被停止的那条连接 —— `stop()` 留下的 stopped 置位只该
      // 作用于被中止的那一轮，不能跨连接生效（否则重挂后的断流会被 onClose 误判成"用户主动
      // 停止"而吞掉"结果可能不完整"提示）。复位点与 send() / resetSession() 对齐。
      this.stopped = false
      this.notice = lastSeq === undefined
        ? '正在重挂该轮（先回放已产出的帧，再续收实时帧）…'
        : `正在重挂该轮（只补发第 ${lastSeq} 帧之后的增量）…`
      const controller = new AbortController()
      currentAbort = controller
      activeRoundHostId = assistant.id
      /** R1/R6（issue#5）：本次重挂是否仍是"当前轮"（以 AbortController 身份为准）。 */
      const isCurrentRound = (): boolean => currentAbort === controller
      // R5②（issue#5）：连点/重复重挂先中止既有连接（与 resumeSnapshotStream#防双连接同款）——
      // 否则两条 EventSource 同时往不同宿主消息写帧（双渲染），且两者 finally 互相覆盖轮次状态。
      // **先换身份再中止**：旧连接的收尾据此判定"非本轮"，不会把断流/取消提示写脏新连接现场。
      previous?.abort()
      try {
        await aiApi.reattachRun(
          targetRunId,
          {
            onFrame: (frame) => this.handleFrame(frame, assistant.id),
            onReconnect: (attempt, delayMs) => {
              if (!isCurrentRound()) {
                return
              }
              this.notice = `重挂中断，${Math.round(delayMs / 1000)} 秒后第 ${attempt} 次重试`
            },
            onClose: (info) => {
              if (!isCurrentRound()) {
                return
              }
              // 苞 B 项 3：用户主动停止不报断流（同 send 的守卫；重挂/续收路径同样适用）
              if (!info.terminal && !this.stopped) {
                this.streamInterrupted = true
                this.notice = `连接中断，结果可能不完整（重挂未能收到完成帧：${info.reason ?? '断流'}）`
                if (this.activeForm !== null) {
                  // 与红队问题 2 同构：重挂断流同样是收尾出口，不能只置通知而漏收表单态
                  // 与本地超时计时器（刷新后由后端权威超时收敛）
                  disarmFormExpiry()
                  this.activeForm = null
                }
              }
            }
          },
          { signal: controller.signal, lastSeq }
        )
        this.busyConflict = null
      } catch (error) {
        if (isCurrentRound()) {
          this.notice = error instanceof Error ? error.message : '重挂失败'
        }
      } finally {
        if (isCurrentRound()) {
          // R1 同型（issue#5）：被后一次重挂/新轮取代的旧连接，其收尾不得复位新轮状态
          this.loading = false
          this.suspended = false
          currentAbort = null
          activeRoundHostId = null
        }
      }
    },

    /** HITL 确认/拒绝（双路径：approved=true 放行，false 拒绝并回填原因）。 */
    async confirm(approved: boolean, reason?: string): Promise<void> {
      const pending = this.pendingToolCall
      const runId = this.activeRunId
      if (!pending || !runId) {
        this.notice = '没有待处理的确认请求'
        return
      }
      // 批2（权威翻转）：确认门的乐观置位只写**段** —— 挂起卡与运行态全部由段派生。
      // 宿主 = 持该 toolCallId 工具段的消息（段由 confirm_request 帧建好；缺失时不新建）
      const host = this.messages.find((item) => item.parts.some(
        (part): boolean => part.kind === 'tool' && part.toolCallId === pending.toolCallId
      ))
      if (host) {
        // R-158 #10：决策元数据随段写回（回执卡徽标/时刻展示；乐观写，帧终态后为最终裁决）
        this.upsertToolPart(host, {
          toolCallId: pending.toolCallId,
          name: pending.name,
          kind2: pending.kind,
          status: approved ? 'running' : 'rejected',
          decision: approved ? 'approved' : 'rejected',
          decidedAt: Date.now(),
          requiresAction: false
        })
        deriveProjections(host)
      }
      try {
        await aiApi.confirm({ runId, toolCallId: pending.toolCallId, approved, reason: reason ?? null })
        this.suspended = false
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
          this.notice = '该确认已提交过（幂等拒绝），无需重复操作'
          // approved 并入 running 落段（approved 不是 ToolRunStatus 合法值，与 m-R1 同口径）
          if (host) {
            this.upsertToolPart(host, {
              toolCallId: pending.toolCallId,
              name: pending.name,
              kind2: pending.kind,
              status: 'running',
              decision: 'approved',
              decidedAt: Date.now(),
              requiresAction: false
            })
            deriveProjections(host)
          }
          return
        }
        // 提交失败回退：段回到待决（requiresAction=true），派生出的挂起卡随之复现；
        // 决策元数据一并清回缺省（未形成有效决策，防回执卡误显）
        if (host) {
          this.upsertToolPart(host, {
            toolCallId: pending.toolCallId,
            name: pending.name,
            kind2: pending.kind,
            status: 'pending',
            decision: undefined,
            decidedAt: undefined,
            requiresAction: true
          })
          deriveProjections(host)
        }
        this.notice = error instanceof Error ? error.message : '确认提交失败'
      }
    },

    /**
     * 前端工具结果回灌（幂等：重复 → 409，未知 → 409）。
     * GF-B：cancelled=true 为取消路径（GFa 裁决 #2，可选字段，与提交共用同一入口），
     * 用户放弃 / 渲染器不可用时回灌让挂起立刻落 FRONTEND_CANCELLED，不必等超时。
     *
     * 返回结构化结果（供生成式表单区分 400 FORM_RESULT_REJECTED「挂起仍在等待，可重试」
     * 与真失败）；通用执行器路径（runFrontendTool）忽略返回值，行为不变。
     */
    async submitFrontendToolResult(
      toolCallId: string,
      result: string,
      source = 'http-post',
      cancelled = false,
      runId?: string
    ): Promise<{ ok: boolean; duplicate?: boolean; rejected?: string; message?: string }> {
      // GF-B 修复（红队问题 1）：表单回灌优先用表单创建时捕获的 runId，
      // 缺省才回退当前轮的 activeRunId（避免旧表单错配到新 run）
      const targetRunId = runId ?? this.activeRunId
      if (!targetRunId) {
        this.notice = '缺少 runId，无法回灌前端工具结果'
        return { ok: false, message: '缺少 runId' }
      }
      try {
        await aiApi.frontendToolResult(
          cancelled
            ? { runId: targetRunId, toolCallId, cancelled: true, source }
            : { runId: targetRunId, toolCallId, result, source }
        )
        return { ok: true }
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
          // T2a-D#4：409 DUPLICATE 是中性终态（不报错）：按响应体携带的条目 status
          // 收敛 pendingCall 投影（帧未到达/丢失时的就地收敛；响应体 status 与帧同口径）
          const bodyStatus = error.bodyAs<{ status?: unknown }>()?.status
          const mapped = frameStatusToPendingStatus(bodyStatus)
          if (mapped) {
            // T2a 返修（S2）：与 frontend_tool_result 帧同口径 —— 终态保留只限 generative_form，
            // 通用 FRONTEND 工具就地收掉挂起投影
            this.applyFrontendToolTerminal(toolCallId, mapped)
          }
          this.notice = cancelled ? '该工具调用已是终态（幂等拒绝）' : '该工具结果已回灌过（幂等拒绝）'
          return { ok: false, duplicate: true }
        }
        if (error instanceof ApiError && error.code === ErrorCode.FORM_RESULT_REJECTED) {
          // 400 + status:"PENDING"：挂起仍在等待 —— 不置 notice 打断填写，由表单卡展示拒绝原因，
          // 用户改值后用同一 toolCallId 重发，或改发取消
          const reasons = error.bodyAs<{ reasons?: unknown }>()?.reasons
          const detail = Array.isArray(reasons) && reasons.length > 0
            ? reasons.map((item) => String(item)).join('；')
            : error.message
          return { ok: false, rejected: detail }
        }
        const message = error instanceof Error ? error.message : '前端工具结果回灌失败'
        this.notice = message
        return { ok: false, message }
      }
    },

    /**
     * T2a-D#3（B3 防御守卫）/ T2a 返修（S3）：该 toolCallId 是否已有**已决**的工具段
     * （终态判定看全部消息，含本次帧即将写入的那条）。调用方必须在写入新挂起语义**之前**用它判定
     * —— 已决 toolCallId 不得产生任何新挂起段（含 requiresAction 段 / suspended / activeForm）。
     *
     * 批2（权威翻转）：判据从 `pendingCall` 投影改读**段**（见下方实现注释）。
     */
    hasDecidedPendingResidue(toolCallId: string): boolean {
      // 批2（权威翻转）：判据改读**段** —— 挂起投影不再保留终态（终态段 requiresAction=false，
      // 派生出的 pendingCall 为 null），若继续读投影这个守卫会静默失效（取消竞态重挂复活）。
      return this.messages.some(
        (item) => item.parts.some(
          (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
            && isFrameDecidedPendingStatus(part.status)
        )
      )
    },

    /**
     * GF-B：generative_form 帧到达 → 防御性解析 schema。
     * 合法：挂到会话态进"等待用户填写"（AiPanel 渲染 FormRenderer），并按帧带时限武装本地超时；
     * 非法 / 缺 runId（渲染器不可用的任何异常路径）：带 cancelled:true 回灌，挂起立刻收敛
     * （GFa §8.3 / 裁决 #3），绝不让通用执行器回灌说明文本。
     */
    openGenerativeForm(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): void {
      // T2a-D#3（B3 防御守卫）/ T2a 返修（S3）："已决 toolCallId 不挂起"的守卫由**调用方**在
      // 写段（requiresAction=true）**之前**前置执行（`hasDecidedPendingResidue`，见
      // frontend_tool_request case）—— 放在这里会太晚：段已落 requiresAction，
      // reattach 回放会先渲染一张瞬时"表单状态已丢失"卡，再等后续结局帧收敛。
      disarmFormExpiry()
      // 批2：挂起投影由段派生，args 取调用方已派生好的 pendingCall（同帧的 frontend_tool_request）
      const args = message.pendingCall?.args ?? parseToolArgs(frame.args)
      const spec = parseFormSchema(args?.form)
      const runId = frame.runId ?? this.activeRunId
      if (!spec || !runId) {
        const result = '表单 schema 解析失败（或缺少 runId），前端无法渲染，已带 cancelled:true 回灌收敛（GFa §8.3）'
        this.upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'FRONTEND',
          status: 'failed',
          result,
          requiresAction: false
        })
        deriveProjections(message)
        void this.submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true, runId ?? undefined)
        return
      }
      // T4-3 落点①：本地 deadline 按服务端剩余时长折算（`expiresAt − atMs`），缺 `atMs` 回落原口径
      // —— 口径与回落分支的说明见 `foldDeadline` 的注释（后端帧构造点起**所有帧都带** `atMs`，
      // 实时帧亦走折算分支；只有旧格式 / 损坏数据才回落）。
      const deadline = foldDeadline(frame.expiresAt, frame.atMs, frame.timeoutSeconds)
      this.activeForm = {
        toolCallId: frame.toolCallId,
        runId,
        form: spec,
        status: 'filling',
        error: null,
        deadline
      }
      if (deadline !== null) {
        formExpiryTimer = setTimeout(() => {
          formExpiryTimer = null
          // 本地判过期：收起表单并带 cancelled:true 回灌（409 幂等拒绝按现有口径忽略）
          void this.cancelGenerativeForm(true)
        }, Math.max(0, deadline - Date.now()))
      }
    },

    /** GF-B：提交表单值 —— result 为字段 key→值的 JSON 字符串；客户端复核不过就地提示不发出。 */
    async submitGenerativeForm(values: Record<string, unknown>): Promise<void> {
      const form = this.activeForm
      if (!form || form.status !== 'filling') {
        return
      }
      const problems = checkFormValues(form.form, values)
      if (problems.length > 0) {
        form.error = problems.join('；')
        return
      }
      form.status = 'submitting'
      form.error = null
      const outcome = await this.submitFrontendToolResult(form.toolCallId, JSON.stringify(values), 'http-post', false, form.runId)
      if (this.activeForm?.toolCallId !== form.toolCallId) {
        // 结局帧/新表单已收敛：不写回过期状态（红队问题 6，防瞬时覆写）
        return
      }
      if (outcome.ok || outcome.duplicate) {
        form.status = 'submitted'
        // R-158 #10：提交值回执写回工具段（随段持久化，刷新后可回溯已填值）
        const host = this.messages.find((item) => item.parts.some(
          (part): boolean => part.kind === 'tool' && part.toolCallId === form.toolCallId
        ))
        const segment = host?.parts.find((part) => part.kind === 'tool' && part.toolCallId === form.toolCallId)
        if (host && segment && segment.kind === 'tool') {
          this.upsertToolPart(host, {
            toolCallId: form.toolCallId,
            name: segment.name,
            kind2: segment.kind2,
            submittedValues: values,
            requiresAction: false
          })
        }
        return
      }
      if (outcome.rejected) {
        // 400 FORM_RESULT_REJECTED：挂起仍在等待 —— 回填写态，同一 toolCallId 可改值重发或取消
        form.status = 'filling'
        form.error = `提交被后端拒绝（挂起仍在等待）：${outcome.rejected}`
        return
      }
      form.status = 'filling'
      // 红队问题 4：泛化文案并入后端具体原因，表单卡内可见真实失败点
      form.error = outcome.message
        ? `提交失败：${outcome.message}（可重试或点「取消」放弃）`
        : '提交失败，请重试或点「取消」放弃'
    },

    /** GF-B：显式取消（或本地超时自动取消）—— 带 cancelled:true 回灌，挂起立刻收敛。 */
    async cancelGenerativeForm(auto = false): Promise<void> {
      const form = this.activeForm
      if (!form || form.status === 'cancelled' || form.status === 'submitted') {
        return
      }
      form.status = 'cancelled'
      // T2a-D#2（A2）：本地 cancel 仅乐观置位 —— 只收敛仍处 pending 的 pendingCall 投影；
      // 帧终态为最终裁决，后到帧可覆盖此乐观 cancelled（他端先提交时翻为 succeeded，不降级）
      this.applyPendingTerminal(form.toolCallId, 'cancelled')
      disarmFormExpiry()
      const outcome = await this.submitFrontendToolResult(form.toolCallId, '', 'http-post', true, form.runId)
      if (this.activeForm?.toolCallId !== form.toolCallId) {
        // 结局帧/新表单已收敛：错误提示不写进过期表单（红队问题 6）
        return
      }
      if (!outcome.ok && !outcome.duplicate) {
        form.error = `${auto ? '表单已超时，' : ''}取消回灌未送达（${outcome.message ?? '网络异常'}），后端将在超时后自动收尾`
        // 批4④ A4'（D3/D11）：同一份文案（同源自 form.error，不两处各写字面量）落段 reason ——
        // 段持久（activeForm 七处清空不影响可见性），卡内 reason 位渲染；不经 applyPendingTerminal
        // （防污染 confirm 路径 reason 语义）。D-C2 钉死形状：requiresAction 显式 false（必填字段，
        // 误置 true 会把刚收口的终态段重挂回 requiresAction，破 ③b D8 闸）；
        // createIfMissing=false：段不存在时静默丢写（不新建段）+ DEV 警告（须含 toolCallId）。
        const reason = form.error
        // 收口修 F5（红队 m-3）：reason 单落点 = **末条**持该 toolCallId 的宿主消息 —— 与
        // formHostMessageId（AiPanel.vue 倒序扫末条，D2 保留面）同口径。误取首条时，双卡/重投
        // 场景下用户在末条宿主卡上取消，reason 会落到首条消息卡上，与操作位错位。
        let host: ChatMessage | undefined
        for (let i = this.messages.length - 1; i >= 0; i--) {
          const message = this.messages[i]
          if (message && message.parts.some((part) => part.kind === 'tool' && part.toolCallId === form.toolCallId)) {
            host = message
            break
          }
        }
        const existing = host?.parts.find((part) => part.kind === 'tool' && part.toolCallId === form.toolCallId)
        if (host && existing && existing.kind === 'tool') {
          this.upsertToolPart(host, {
            toolCallId: form.toolCallId,
            name: existing.name,
            kind2: existing.kind2,
            requiresAction: false,
            reason
          }, false)
          // 收口修 F4（红队 m-2）：段 reason 落段后同步派生投影 —— 否则 toolRuns[].reason
          // （parts.ts:199 投影含 reason）滞留旧值，违背「parts 唯一真值、投影由 deriveProjections 派生」契约。
          deriveProjections(host)
        } else if (import.meta.env.DEV) {
          console.warn(`[批4④ A4'] 取消回灌失败：未找到 toolCallId=${form.toolCallId} 的工具段，reason 未落段（不新建段）`)
        }
      }
    },

    /** 停止本轮：先断本地连接，再通知后端取消（后端不可达也不影响本地停止）。 */
    async stop(): Promise<void> {
      this.stopped = true
      try {
        currentAbort?.abort()
      } catch {
        // 忽略：中止失败不影响后续取消请求
      }
      const runId = this.activeRunId
      // R3（issue#5）：**不在这里抢清 loading** —— 复位交给本轮自身的收尾（send 的 finally 里
      // 按 AbortController 身份判定）。否则"停止后立刻发送"会让旧轮的收尾与 abort 提示
      // 覆盖新轮（界面谎报空闲 + 新轮丢失取消句柄）。
      if (this.activeForm !== null) {
        // 用户停止本轮：表单随轮收起（后端取消会让挂起走 TIMEOUT/取消收尾）
        disarmFormExpiry()
        this.activeForm = null
      }
      if (!runId) {
        return
      }
      try {
        await aiApi.cancel(runId)
        this.notice = '已请求取消本轮对话'
      } catch (error) {
        this.notice = error instanceof Error ? `取消请求失败：${error.message}` : '取消请求失败'
      }
    },

    /**
     * 新建对话（页签内重置会话）：立即清屏 + 换新 sessionId + 删旧会话的服务端记忆。
     *
     * 顺序（苞 A 项 1 裁决）：
     * 1. 先在跑的轮次 `abort`（R5①）——否则 loading 不复位、后续帧会写进已清空的 messages；
     * 2. 立刻换 id 并清状态（**同步完成**：点击=立即清屏，不等后端）；
     * 3. 再 best-effort `POST /api/ai/cancel/{oldRunId}` 通知后端停旧轮（后端不可达不阻断本地重置，
     *    口径同 `stop()`）；
     * 4. **最后** `DELETE /api/ai/history/{oldSessionId}` 删旧记忆（失败只提示，不影响新对话；
     *    提示带「仍是本轮」守卫，见函数末尾与复核 F5）。
     *
     * 已知代价（施工卡 §5.2，登记；口径按复核 F2 订正为如实版）：旧轮终局的记忆收敛（`clear + add`）
     * 会把窗口整体写回同名键，而 `cancel` 只是「置取消标志 + 唤醒收尾」、轮次收盘是异步的
     * ⇒ **在飞路径下 DELETE 必然先于收盘重写**（顺序必然，不是概率竞态；先 cancel 再 delete 也
     * 缩不小这个窗口），即旧键必被写回、残留至 TTL 到期。旧 id 已被前端丢弃，故残留期间无人读取
     * （用户不可见）。更强的保证（端点 409 硬拒）已被明确否掉 —— 「新建对话」不能变成可能失败的动作。
     */
    async resetSession(): Promise<void> {
      // 两个旧值必须在清状态之前取：oldRunId 用于取消旧轮，oldSessionId 用于删旧记忆与删旧镜像
      const oldSessionId = this.sessionId
      const oldRunId = this.activeRunId
      // R5①（issue#5）：在跑的轮次先中止 —— 否则 loading 不复位（输入区一直禁用到流结束），
      // 且后续帧会写进**已清空**的 messages（按 assistantId 找不到宿主 ⇒ 孤儿消息/孤儿帧）
      try {
        currentAbort?.abort()
      } catch {
        // 忽略：中止失败不影响重置
      }
      currentAbort = null
      activeRoundHostId = null
      this.loading = false
      // 换 id：**同一个** nextId 双写 sessionStorage 与响应式 state。
      // 修复（苞 A 必修）：此前只写 sessionStorage、从不写 this.sessionId ⇒ `send` 仍带旧 id
      // 请求、"新建对话"实际继承旧会话的服务端记忆（实测缺陷，见施工报告「必修前置缺陷」）。
      const nextId = createSessionId()
      try {
        sessionStorage.removeItem(MIRROR_PREFIX + oldSessionId)
        sessionStorage.setItem(SESSION_ID_KEY, nextId)
      } catch {
        // 隐私模式/配额：持久化失败不阻断本次重置（本页签内照常换 id）
      }
      this.sessionId = nextId
      this.messages = []
      this.activeRunId = null
      this.notice = null
      this.busyConflict = null
      this.poolSaturated = null
      this.suspended = false
      disarmFormExpiry()
      this.activeForm = null
      // 批4④ A5'/D4：折叠展开态随会话清空（防跨会话串味；会话内内存态，不进镜像）
      this.expandedBlocks = {}
      // R-155：发布冲突 jobId→工具段注册表随会话清空（内存态，与折叠展开态同生命周期口径）
      clearPublishToolLinks()
      // 轮次级标志一并复位：新会话不该继承上一轮的停止/断流事实（骨架屏见 historyLoaded）
      this.stopped = false
      this.streamInterrupted = false
      this.historyLoaded = true
      // best-effort 停掉后端仍在跑的旧轮（只断本地连接不停后端，故必须显式 POST）
      if (oldRunId) {
        try {
          await aiApi.cancel(oldRunId)
        } catch {
          // 忽略：取消请求失败只意味着后端可能多跑一轮，不影响新建对话
        }
      }
      // 删除放最后：**顺序不缩小复活窗口**（口径订正：复核 F2）—— `cancel` 只置取消标志 + 唤醒收尾，
      // 轮次收盘异步 ⇒ 在飞路径下本 DELETE 必然先于收盘重写；此处的顺序只为"尽量早地把旧记忆清掉"。
      // F5（复核收口）：失败提示是**异步落地**的（后端不可达时最坏要等到 HTTP 超时），故必须带
      // 「仍是本轮」守卫 —— 这只在本次新建对话换出的会话里、且其间没有发生任何轮次时才允许写提示；
      // 否则这条过期提示会盖掉新会话/新轮的 notice（store 其余异步落盘点都用 isCurrentRound 同族守卫，
      // 本处"当前轮"的判据 = 会话 id 仍是 nextId（未被再次新建对话换掉）且 messages 仍为空
      // （send 会同步 record 用户消息，故 messages 非空 ⇔ 新会话里已经起过轮次）。
      const isCurrentRound = (): boolean => this.sessionId === nextId && this.messages.length === 0
      try {
        await aiApi.deleteHistory(oldSessionId)
      } catch {
        // 删除失败不阻断新对话（旧会话记忆到期自然清理）
        if (isCurrentRound()) {
          this.notice = '旧会话记忆删除失败（不影响新对话）'
        }
      }
    },

    // ── 内部：消息与帧处理 ─────────────────────────────────────────────────

    recordUserMessage(content: string): ChatMessage {
      const message: ChatMessage = {
        id: `u-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        role: 'user',
        content: '',
        // 用户消息一次性成文：单段文本、非流式（批2：段是唯一真值，投影建段后派生）
        parts: [{ kind: 'text', seg: 0, text: content, streaming: false }],
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: false,
        done: true,
        // R-154 #17：用户消息时间戳 = 本地发送时刻
        timestamp: Date.now()
      }
      deriveProjections(message)
      this.messages.push(message)
      return message
    },

    appendAssistantMessage(initial = ''): ChatMessage {
      const message: ChatMessage = {
        id: `a-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        role: 'assistant',
        content: '',
        // 新助手消息以"流式空文本段"起头：delta 帧直接续写该段（工具段插入时才封口）
        parts: [{ kind: 'text', seg: 0, text: initial, streaming: true }],
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: true,
        done: false
      }
      deriveProjections(message)
      this.messages.push(message)
      return message
    },

    /** 当前正在流式输出的助手消息（没有则新建）。 */
    currentAssistantMessage(): ChatMessage {
      for (let i = this.messages.length - 1; i >= 0; i -= 1) {
        const message = this.messages[i]
        if (!message) {
          continue
        }
        if (message.role === 'assistant' && !message.done) {
          return message
        }
      }
      return this.appendAssistantMessage()
    },

    /** SSE 帧 → store（判别联合 switch，覆盖后端全部帧类型）。 */
    handleFrame(frame: SseFrame, assistantId?: string): void {
      /**
       * R2/R6（issue#5）帧归属判定：只有"本轮"（宿主消息仍是当前轮的宿主）的帧才允许改写
       * **轮次级**状态（activeRunId / loading / suspended / notice / activeForm）——旧轮迟到帧
       * （resetSession 之后、或新轮已起）只收敛它自己那条消息，不能把新轮标记成空闲。
       */
      const ownedByActiveRound = assistantId === undefined || assistantId === activeRoundHostId
      const message = assistantId
        ? this.messages.find((item) => item.id === assistantId)
          ?? (ownedByActiveRound ? this.currentAssistantMessage() : null)
        : this.currentAssistantMessage()
      if (!message) {
        // 宿主消息已不在（会话已重置/历史已整体替换）且该帧不属于当前轮 ⇒ 整帧丢弃，
        // 不再凭空 append 一条孤儿助手消息（G5）
        return
      }

      // T6：按 runId 记录最大帧序号（reattach 时回传，服务端只补增量）
      const seq = frameSeq(frame)
      if (seq !== null) {
        const runId = frame.runId ?? this.activeRunId
        if (runId) {
          this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, seq)
        }
      }

      if (!ownedByActiveRound) {
        /**
         * 旧轮迟到帧（R2/R6）：只收敛它自己那条消息的收尾，**一律不改写轮次级状态**
         * （`loading`/`activeRunId`/`suspended`/`notice`/`activeForm`）——否则旧轮的 done/error
         * 会把正在跑的新轮标记成空闲，旧轮的 retry/取消提示会覆盖新轮提示（G2/G3/G7）。
         */
        if (isTerminalFrame(frame)) {
          message.streaming = false
          message.done = true
        }
        return
      }

      // R2-1（批2）：seq 幂等 —— 同一帧被重复投递（reattach 全量回放 / 双连接竞态）时整帧跳过：
      // 不重复累加文本、不重建段、不重放工具卡。只做"同序号去重"，**不做连续性判定** ——
      // 心跳不占业务 seq、归档续收本就会跳号，序号断层是正常现象（R2-3）。
      if (seq !== null && alreadyApplied(message, seq)) {
        return
      }

      // 批3③b D6(B)（R-141①，店级守卫）：跨宿主"已决"终帧守卫 —— 重挂/续收回放时，若帧的
      // toolCallId 已在**其他消息**上持有已决段（hasDecidedPendingResidue，统一 7 态），且当前
      // 宿主消息没有它的段，则整帧跳过：不得在新宿主重建同 id 段（旧卡 unknown/failed/cancelled
      // 仍在 ⇒ 第二张状态矛盾卡）。当前宿主已持段的正常收敛不受影响（含 T1 生死线的 first-wins）。
      // 挂起语义帧（request/suspended/confirm_request）的拦截由 D8 各 case 守卫承担；本守卫只覆盖
      // 工具终态/起始帧。契约前提：后端不重发已终态 toolCallId、toolCallId 跨轮不复用（UUID）。
      if ((frame.type === 'tool_start' || frame.type === 'tool_result'
        || frame.type === 'frontend_tool_result' || frame.type === 'confirm_decision')
        && this.hasDecidedPendingResidue(frame.toolCallId)
        && !findToolPart(message, frame.toolCallId)) {
        return
      }

      switch (frame.type) {
        case 'start':
          this.activeRunId = frame.runId
          this.notice = null
          break
        case 'message_start':
        case 'message_end':
          // T5 三段式的 start/end：本段边界（正文/思考段共用同一套帧）—— 只写消息身份与段边界
          // （message_end 顺带封口末尾流式段，o-R1），**不改段结构**（R2-2：段收口只发生在工具类帧
          // 与轮终态）。批2：写入统一走 applyFrameToParts。
          applyFrameToParts(message, frame)
          break
        case 'delta': {
          // 高频路径（§6.1 性能注）：段文本与派生投影 content **锁步** `+=`，不调 deriveProjections
          //（全量重算会每 token 替换 toolRuns 数组 ⇒ 工具卡列表整列重渲染）
          message.content += frame.text
          applyFrameToParts(message, frame)
          break
        }
        case 'reasoning': {
          // 裁决⑦：有 reasoning 帧才渲染思考链（无帧则 reasoning 恒空 → 面板不显示折叠块）
          message.reasoning += frame.text
          applyFrameToParts(message, frame)
          break
        }
        case 'tool_start': {
          applyFrameToParts(message, frame)
          break
        }
        case 'tool_result': {
          // 补丁③ / DC-14 T1 的终态裁决口径（BLOCKED > REJECTED > ok 三元）落在段上，见
          // utils/parts.ts 的 applyFrameToParts —— 本 case 不再有任何投影直写
          applyFrameToParts(message, frame)
          // R-155：start_publish 工具结果取号 jobId → 登记并武装发布冲突跟踪
          this.armPublishConflictWatch(message, frame)
          break
        }
        case 'confirm_request': {
          // 批3③b D8（R-141③）：已决 toolCallId 的重投帧不产生新挂起语义 —— 守卫去 GF 前缀、
          // 扩到全部工具（判据统一 7 态）。实现取 break：seq 记账与 markApplied（switch 后统一
          // 收口）照常执行，**语义再投以本闸兜底**（与 seq 幂等的职责分界：闸管语义、seq 管重复）。
          if (this.hasDecidedPendingResidue(frame.toolCallId)) {
            break
          }
          this.suspended = true
          // 工具卡四态之"等待确认"：确认门未决期间该工具卡显示为等待确认（段 status=pending +
          // requiresAction=true ⇒ 派生出挂起卡）；倒计时元数据随段持久化
          applyFrameToParts(message, frame)
          break
        }
        case 'confirm_decision': {
          // approved → running、rejected → rejected 的段裁决在 applyFrameToParts；
          // 挂起卡随 requiresAction=false 派生为 null（结局由工具卡承载）
          applyFrameToParts(message, frame)
          this.suspended = false
          break
        }
        case 'frontend_tool_request': {
          // 批3③b D8（R-141③）：守卫**去 GF 前缀** —— 通用 FRONTEND 工具的重投帧同样被拦：
          // 不再触发第二次 runFrontendTool（二次浏览器副作用）、不把已终态段改回
          // pending+requiresAction（挂起卡复活）。已决判据统一 7 态（含 unknown/failed）。
          // 原 GF 前缀守卫的行为对 GF 用例不变（回归锁见 frontend-tool-result-terminal.spec.ts）。
          if (this.hasDecidedPendingResidue(frame.toolCallId)) {
            // T2a 返修（S3）：守卫前移到**写段之前** —— 已决 toolCallId 不产生任何新挂起语义
            //（不落 requiresAction 段、不置 suspended、不武装 activeForm），
            // 防 reattach 回放先渲染一张瞬时挂起卡、再等后续结局帧收敛
            break
          }
          this.suspended = true
          applyFrameToParts(message, frame)
          // 表单武装前先派生：openGenerativeForm 读派生出的 pendingCall.args
          deriveProjections(message)
          if (frame.name === GENERATIVE_FORM_TOOL) {
            // GF-B：表单挂起走渲染器通道等用户填写 —— 绝不能让通用执行器兜底
            // （那段说明文本不是值 JSON，会被闸门 400 拒、挂起拖到超时，GFa §8.3）
            this.openGenerativeForm(message, frame)
            break
          }
          // 前端工具没有 HITL：立即在浏览器执行并回灌结果
          void this.runFrontendTool(message, frame)
          break
        }
        case 'frontend_tool_result': {
          // 段侧终态裁决（帧 status 优先 → ok 兜底，本地已终态 first-wins）与 requiresAction 收口
          // 全在 applyFrameToParts —— 它是本帧型**唯一**的段写点（F2 收口修：此处原先还二次调用
          // applyFrontendToolTerminal。F1 之后该调用对绝大多数段是空写（段早已非 pending/cancelled），
          // 唯一不空的是本地乐观 cancelled 段：applyFrameToParts 的 first-wins 保留了 'cancelled'，
          // 二次调用却按帧 status 把它翻成另一种终态 —— 同一段两个写点给出不同裁决，正是要收掉的
          // 双写口径分裂面）。挂起卡随 requiresAction=false 由派生抽掉。
          // 非帧路径（409 响应体 / 续收快照）仍用 applyFrontendToolTerminal 就地收敛，见其注释。
          applyFrameToParts(message, frame)
          if (this.activeForm?.toolCallId === frame.toolCallId) {
            // 结局帧已到：表单收敛（提交/取消/超时皆自此收尾）。
            // T2a-D#2（B2 缓释）：帧到达时本地仍在填写（未在提交在途）→ 该表单已被他端提交/处理
            if (this.activeForm.status === 'filling') {
              this.notice = '该表单已在其他窗口提交，本地填写已停止'
            }
            disarmFormExpiry()
            this.activeForm = null
          }
          this.suspended = false
          break
        }
        case 'suspended': {
          // 后端对**每一批工具调用**都会先发 suspended（把挂起态外置，见 SpToolCallingManager），
          // 其中只有 CONFIRM/FRONTEND 两类需要人工或浏览器介入：BACKEND 工具随即执行完毕，
          // 不该被判成"挂起等待"（否则运行状态与输入区提示会误报）。
          // 批3③b D8 遍历口径：suspended 帧**没有单数 toolCallId 字段**（types/sse.ts
          // SseSuspendedFrame.toolCalls 是复数数组）——判据钉在写面条目（parts.ts 同口径：
          // find 首个 CONFIRM/FRONTEND）的 toolCallId 上，逐条目遍历判已决；已决 ⇒ 不建段 /
          // 不写 requiresAction / 不置 suspended（防 failed+requiresAction rogue 面经
          // pendingStatusOf 折 pending 复活挂起卡，R-141③ 行尾注记 / DS Pro O-5）。
          const waiting = frame.toolCalls.find((call) => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')
          if (waiting) {
            if (this.hasDecidedPendingResidue(waiting.toolCallId)) {
              break
            }
            this.suspended = true
            // 本帧更新已存在的工具段（requiresAction=true）；段尚不存在时由 applyFrameToParts
            // 建占位段（③b D5：createIfMissing=true + status='pending'）——"只发 suspended"路径
            // （取消竞态，SpToolCallingManager）不再整轮无声丢失
            applyFrameToParts(message, frame)
          }
          break
        }
        case 'heartbeat':
          // 心跳只用于保活与倒计时，不产生展示内容
          break
        case 'retry':
          this.notice = '上游连接中断，正在重试（本轮尚未产出内容）'
          break
        case 'done': {
          message.streaming = false
          message.done = true
          // o-R1：轮终态封口末尾流式段（否则已完成的消息里仍留着 streaming 段的假态，且会被
          // 镜像持久化）+ 批2 前置：仍在等动作的工具段一并过期降级（与空历史 expired 降级惯例一致）。
          // 旧口径的 `message.pendingCall = pendingCall?.status === 'pending' ? null : ...` 直写
          // 已删除 —— 挂起卡由段派生（requiresAction=false ⇒ 派生为 null）
          sealTrailingStreamPart(message)
          expireResidueToolParts(message)
          if (this.activeForm !== null) {
            // 轮次收尾时表单未决（如后端侧超时/放弃）：本地一并收起，计时器随之中和
            disarmFormExpiry()
            this.activeForm = null
          }
          this.suspended = false
          if (frame.cancelled) {
            this.notice = '本轮已取消'
          }
          break
        }
        case 'error': {
          message.streaming = false
          message.done = true
          // 出错收尾与 done 同口径：封口末尾流式段 + 待决工具段过期降级
          sealTrailingStreamPart(message)
          expireResidueToolParts(message)
          if (this.activeForm !== null) {
            disarmFormExpiry()
            this.activeForm = null
          }
          this.notice = `本轮出错（${frame.code}）：${frame.message}`
          break
        }
        default:
          // 联合类型已穷尽；运行时多出的未知帧（后端加帧）在此静默忽略
          break
      }

      // 工具类帧 / 段边界帧 / 轮终态帧到达后：段是唯一真值 ⇒ 全量重算派生投影（§6.1）。
      // delta / reasoning 已在各自 case 里与段文本锁步维护，故不在此列（高频路径不重算）。
      if (DERIVE_FRAME_TYPES.has(frame.type)) {
        deriveProjections(message)
      }
      // R2-1：记账"已应用"（放在 case 处理之后 —— 半途 break 的帧同样算处理过，不被重放）
      if (seq !== null) {
        markApplied(message, seq)
      }

      if (isTerminalFrame(frame)) {
        this.loading = false
      }
    },

    /**
     * T2a-D#1/D#2：把帧裁决的终态广播到**段**（挂起投影由其派生）—— 全部持该 toolCallId 且
     * 仍处 pending / 乐观 cancelled / 仍挂 requiresAction 的段就地置终态（已终态段 first-wins 不动）。
     *
     * 批2（权威翻转）：原先把终态广播到 `pendingCall` 的那一半已删除 —— 挂起卡是段的派生结果，
     * 「段收口 ⇒ 挂起卡消失」由 deriveProjections 保证。
     */
    applyPendingTerminal(toolCallId: string, status: PendingToolCall['status']): void {
      // 段侧终态映射（approved 并入 running —— approved 不是 ToolRunStatus 合法值）
      const mapped: ToolRunStatus = status === 'approved' ? 'running' : status
      for (const item of this.messages) {
        const index = item.parts.findIndex((part) => part.kind === 'tool' && part.toolCallId === toolCallId)
        const part = index >= 0 ? item.parts[index] : undefined
        if (part && part.kind === 'tool'
          // 批3③a D5②：覆盖域显式排除 unknown / failed —— 本地已落的终态（含"结局未知"）
          // first-wins，不得被 409 响应体 / 续收快照的二次收敛改写成别的终态。
          && part.status !== 'unknown' && part.status !== 'failed'
          && (part.status === 'pending' || part.status === 'cancelled' || part.requiresAction)) {
          item.parts.splice(index, 1, { ...part, requiresAction: false, status: mapped })
          deriveProjections(item)
        }
      }
    },

    /**
     * 前端工具结局的**段级**终态收口。与 {@link applyPendingTerminal} 的差别是**只对
     * generative_form 落终态**：通用 FRONTEND 工具（navigate_to / download_export_files 等）
     * 只收 requiresAction（结局由结果帧承载），因为它的挂起卡本来就该收掉（留着会与工具卡
     * 叠成一张冗余"已执行"卡）。判定依据取段自身的既有字段（kind2 + name），不依赖帧字段。
     * `status === null` = 未给可翻译终态（旧后端口径）：只收 requiresAction。
     *
     * 调用点（F2 收口修后**只剩非帧路径**）：
     * - 409 DUPLICATE 响应体的条目 status（{@link submitFrontendToolResult}）；
     * - 续收快照里非 PENDING 条目对本地残留的就地收敛（{@link restoreSuspendedSnapshot}）。
     * `frontend_tool_result` 帧路径**不再调用本 action** —— 该帧型的段写点是唯一的
     * `applyFrameToParts`（F1 起它同时承载 status 与 requiresAction；原先这里被二次调用，
     * 在帧序下恒为空写，构成双写口径分裂面）。
     *
     * 因此下面 GF 分支的"仍处 pending/cancelled 才写 status"守卫**不是死分支**：它服务的正是
     * 上述两条没有结果帧的收敛路径（有帧时段早已终态，条件自然为假 —— 两种情形同一条守卫覆盖）。
     *
     * 批2（权威翻转）：原先直接改 `item.pendingCall` 的那一半已删除 —— 挂起卡是段的派生结果。
     */
    applyFrontendToolTerminal(toolCallId: string, status: PendingToolCall['status'] | null): void {
      // approved 并入 running（同 applyPendingTerminal 映射）
      const mapped: ToolRunStatus | null = status === null ? null : status === 'approved' ? 'running' : status
      for (const item of this.messages) {
        const partIndex = item.parts.findIndex((part) => part.kind === 'tool' && part.toolCallId === toolCallId)
        const part = partIndex >= 0 ? item.parts[partIndex] : undefined
        if (!part || part.kind !== 'tool') {
          continue
        }
        const generative = part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL
        if (!generative) {
          // 通用 FRONTEND：只收 requiresAction；终态段状态由结果帧 / 本地结局的写点承载，不动
          if (part.requiresAction) {
            item.parts.splice(partIndex, 1, { ...part, requiresAction: false })
            deriveProjections(item)
          }
        } else if (part.status === 'pending' || part.status === 'cancelled') {
          // GF 仍处待决：status===null 只收 requiresAction（status 不动），否则落映射终态
          item.parts.splice(partIndex, 1, mapped === null
            ? { ...part, requiresAction: false }
            : { ...part, requiresAction: false, status: mapped })
          deriveProjections(item)
        }
        // GF 已终态段：first-wins 单调，跳过
      }
    },

    /**
     * 工具帧 → `parts` 的唯一写点（对话时序包 批1 建立、批2 起是权威写口）：
     * 与批1 的 `upsertToolRun` 同语义（按 toolCallId 就地 spread 合并，不产生重复段），
     * 差别只在写的是段序列。本 action 是 `utils/parts.ts` 纯函数的**薄包装**（既有调用点与
     * 测试沿用 action 形态，纯函数层可独立单测）。
     *
     * 新建时先**封口末尾的流式段**（工具调用之后到达的正文属于新的一段，seg 单调递增）；
     * `createIfMissing=false` = 只更新语义（段已存在才写；③b D5 起 suspended 帧不再用此形态，
     * 见 utils/parts.ts 的 suspended 支）。status 省略时保留段上原值，理由见 {@link ToolPartPatch}。
     */
    /**
     * 批4④ A5'/D4：折叠块展开态写动作。blockId = groupParts 产出的稳定组 id
     * （`${messageId}:${toolCallId}` / `${messageId}:reasoning-${seg}`，收口修 F1 命名空间）；expanded 为显式三态值（D-C6），
     * 由 AiPanel 经 resolveBlockExpanded 与 defaultExpanded 合成最终展开态。
     */
    setBlockExpanded(blockId: string, expanded: boolean): void {
      this.expandedBlocks[blockId] = expanded
    },

    upsertToolPart(message: ChatMessage, patch: ToolPartPatch, createIfMissing = true): void {
      writeToolPart(message, patch, createIfMissing)
    },

    /**
     * R-155：start_publish 工具结果帧取号 jobId（结果文本「作业 #N」），登记 jobId→toolCallId
     * 并武装发布冲突跟踪（复用 task store watchPublishOutcome，终态冲突经 onConflict 回此）。
     * 任一环取不到号（非 start_publish / 未执行 / 无 jobId / 无 taskId）静默跳过。
     */
    armPublishConflictWatch(message: ChatMessage, frame: Extract<SseFrame, { type: 'tool_result' }>): void {
      if (frame.name !== 'start_publish' || !frame.ok || typeof frame.result !== 'string') {
        return
      }
      const matched = /作业 #(\d+)/.exec(frame.result)
      const jobId = matched ? Number(matched[1]) : Number.NaN
      if (!Number.isInteger(jobId)) {
        return
      }
      const part = findToolPart(message, frame.toolCallId)
      const args = part ? parseToolArgs(part.args) : null
      const taskId = args ? Number(args.taskId) : Number.NaN
      if (!Number.isInteger(taskId)) {
        return
      }
      linkPublishTool(jobId, { toolCallId: frame.toolCallId, sessionId: this.sessionId })
      useTaskStore().watchPublishOutcome(taskId, jobId, {
        onConflict: (summary) => {
          this.attachPublishConflict(jobId, summary)
          reportUiEventResult('start_publish', summary)
        }
      })
    },

    /**
     * R-155：把发布冲突明细摘要写入该 jobId 关联的 start_publish 工具段 reason（卡面红字行）。
     * 仿 A4' 收口范式：段唯一写口 upsertToolPart（createIfMissing=false 不新建段）+ 同步派生投影；
     * reason 单落点 = 末条持该 toolCallId 的宿主消息。命中即注销注册项。
     * @returns 是否落段；查无关联 / 会话已切换 / 段不存在 ⇒ false（调用方静默走原通道）
     */
    attachPublishConflict(jobId: number, summary: string): boolean {
      const link = findPublishTool(jobId)
      if (!link) {
        return false
      }
      if (link.sessionId !== this.sessionId) {
        unlinkPublishTool(jobId)
        return false
      }
      for (let i = this.messages.length - 1; i >= 0; i -= 1) {
        const message = this.messages[i]
        if (!message) {
          continue
        }
        const part = findToolPart(message, link.toolCallId)
        if (part) {
          this.upsertToolPart(message, {
            toolCallId: link.toolCallId,
            name: part.name,
            kind2: part.kind2,
            requiresAction: false,
            reason: summary
          }, false)
          deriveProjections(message)
          unlinkPublishTool(jobId)
          return true
        }
      }
      return false
    },

    /**
     * 执行前端工具并把结果回灌（三级可用性兜底 + **统一超时预算**在 utils/frontend-tools.ts）。
     *
     * 预算由帧里的**服务端**挂起上限折算（`timeoutSeconds`，默认 120s → 60s = 上限 −
     * 回灌 POST 的 axios 上限 60s），理由与取值见 `FRONTEND_TOOL_TIMEOUT_BUDGET_MS` /
     * `deriveFrontendToolBudgetMs`：本函数必须等 `executeFrontendTool`
     * 返回才发 POST，因此预算是"回灌一定发出"的保障（issue#6）。
     * `generative_form` 不走本函数（帧 handler 特判 `openGenerativeForm`），其挂起等的是
     * 用户填写，不受本预算影响。
     */
    async runFrontendTool(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): Promise<void> {
      // 批3③b D10（R-142）：入口捕获轮次句柄 —— `isCurrentRound` 是 send / reattachActive /
      // resumeSnapshotStream / resetSession 内的局部闭包（非 drop-in），此处以模块级
      // currentAbort 的身份比对为准（与 :811/:883/:1037 同族口径）。
      const controllerAtStart = currentAbort
      // 批2：本地执行开跑即落 running（只更新语义 —— 段由 frontend_tool_request 帧建好；
      // 段不再等动作）——前端工具段生命周期 pending→running→终态，与确认门放行同口径。
      // 先让出同步窗口：request 帧的 pending 段必须先落盘（m-R2 写点①的同步可观测语义）
      await Promise.resolve()
      this.upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status: 'running',
        requiresAction: false
      }, false)
      deriveProjections(message)
      const execution = await executeFrontendTool({
        name: frame.name,
        toolCallId: frame.toolCallId,
        args: parseToolArgs(frame.args),
        budgetMs: deriveFrontendToolBudgetMs(frame.timeoutSeconds)
      })
      // 批3③b D10（R-142）：await 返回后比对轮次句柄 —— stop()/新轮/重挂已把 currentAbort
      // 复位（finally）或换成新句柄 ⇒ 本执行器已是"旧轮在途"：段终态写（:1829-1836 区）与
      // 回灌 POST（submitFrontendToolResult）整体丢弃，防晚到结局覆盖已收尾现场/向已取消轮回灌。
      // 窗口残留如实申报（施工报告 §8.4-6）：stop() 至本轮 finally 复位之间返回的执行器
      // 仍正常写段（当时仍是当前轮），只有"返回晚于收尾"才被丢弃。
      if (currentAbort !== controllerAtStart) {
        return
      }
      // 批3③a A8：三态落卡（execution.ok 已 @deprecated，改读 outcome）——
      // unknown（预算到期 / 等待上界耗尽）如实落 'unknown'，卡面显示「结局未知」且
      // 回灌文本与卡面不再自相矛盾；unknown 段的回执帧翻写防护在 parts.ts localTerminal。
      const status: ToolRunStatus = execution.outcome === 'ok'
        ? 'succeeded'
        : execution.outcome === 'unknown' ? 'unknown' : 'failed'
      // 批2：浏览器侧执行结局落段（工具段在此之前由 frontend_tool_request 建好），投影随段派生
      this.upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status,
        result: execution.result,
        requiresAction: false
      })
      deriveProjections(message)
      if (execution.availability === 'degraded' || execution.availability === 'unavailable') {
        this.notice = execution.result
      }
      await this.submitFrontendToolResult(frame.toolCallId, execution.result, 'frontend-executor')
    }
  }
})
