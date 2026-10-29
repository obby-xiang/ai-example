/**
 * AI 会话 store（状态层）。
 *
 * 形态来源：kimi-k3 `stores/ai.js`（`initSessionId` / 会话镜像 / `loadHistory` 后端对账，
 * 原 .js:25-52 与 248-301），TS 化并按本仓后端契约重写：
 * - 会话 id 存 **sessionStorage**（页签级：同页签刷新保持，新页签是新会话，DC-06③）；
 * - 刷新恢复走"镜像即时恢复 + `GET /api/ai/history/{sessionId}` 对账"两步；
 * - 409 SESSION_BUSY 不当作普通失败：回收乐观气泡（不出现幻影双份），提示里不暴露 runId；
 *   在途轮由刷新恢复**静默续收**（批G，用户看不到「重挂」概念）；
 * - 断流（缺 done/error 终帧）按 ADR-6 置 `streamInterrupted` 提示"结果可能不完整"；
 * - 契约联动（S4.4c）：订阅工作区 `workspace_changed` 事件，把用户手点的动作同步进
 *   下一轮请求上下文（`buildContext().extra.recentActions`）。
 */

import { defineStore, getActivePinia } from 'pinia'
import { ApiError, FRONTEND_ERROR_CODES } from '@/api/http'
import { aiApi } from '@/api/ai'
import {
  AI_MIRROR_PREFIX,
  AI_SESSION_ID_KEY,
  AI_SESSION_LOCK_PREFIX,
  currentAiIdentity,
  identityFingerprint,
  isPositiveId,
  isSameIdentity,
  mirrorStorageKey,
  normalizeIdentity,
  normalizeSessionId,
  sessionIdStorageKey,
  sessionLockName,
  writeAiIdentity,
  type AiIdentity,
  type TenantUsersEntry
} from '@/utils/ai-identity'
import { SSE_TIMEOUT_CODES, probeReattach, type SseFrameHandlers, type SseStreamOptions } from '@/api/sse'
import { getPageHandler, onWorkspaceEvent, reportUiEventResult, useWorkspaceStore } from '@/stores/workspace'
import { useTaskStore } from '@/stores/task'
import { aiConfigErrorKey } from '@/constants/ai-settings-copy'
import { deriveFrontendToolBudgetMs, abandonInFlightFrontendTools, executeFrontendTool, parseToolArgs, resolveSheetContext } from '@/utils/frontend-tools'
import { toolStatusOfOutcome } from '@/utils/ui-receipt'
import { UI_HANDOFF_TOOLS } from '@/types/tools'
import type { ActiveUploadHandoff } from '@/types/ui-handoff'
import {
  abortUiOperations,
  applyUiUndo,
  beginUiTurn,
  clearUiAnnouncement,
  clearUiUndo,
  peekUiAnnouncement,
  peekUiUndo,
  isRefSnapshotted,
  requestFilePicker,
  supportsFilePicker,
  takeUiUndo,
  uploadFileNameOf,
  validateUploadArgs
} from '@/utils/ai-dom-tools'
import {
  currentAiEpoch,
  listAiRegions,
  resolveAiRef,
  type AiRegion
} from '@/utils/ai-regions'
import { aiHighlight, clearUiHighlights } from '@/utils/ai-dom-tools'
import type { UiUndoEntry } from '@/utils/ai-dom-tools'
import {
  applySheetPlan,
  applyUndoPlan,
  clearPreviewMarks,
  markPreviewCells,
  needsSheetPreview,
  planCancelledText,
  planPreviewText,
  planSheetWithLevel,
  replanSheet,
  setSheetPolicy,
  sheetAppliedSummary,
  sheetReadOnlyWriteBlocked,
  type SheetAppliedSummary,
  type SheetContext,
  type SheetToolMeta
} from '@/utils/sheet-tools'
import {
  alreadyApplied,
  applyFrameToParts,
  deriveProjections,
  findToolPart,
  historyToolCallStatus,
  isSlotReleasePhantom,
  markApplied,
  openStreamPart,
  outcomeStatus,
  sealTrailingStreamPart,
  upsertToolPart as writeToolPart,
  writeSubmittedValuesToMessage
} from '@/utils/parts'
import { ErrorCode, STRUCTURED_RESULT_MAX_CHARS, type SuspendPoolSaturated } from '@/types/api'
import { frameSeq, isTerminalFrame, type ToolOutcome, type TruncationNoticeCode } from '@/types/sse'
import { GENERATIVE_FORM_TOOL, MIRROR_VERSION, type ActiveGenerativeForm, type AiBusyRunRef, type AiHealth, type AiHistory, type AiHistoryMessage, type AiLoadErrorScope, type AiLoadErrorView, type AiPreparingRun, type AiRecoveryGap, type AiRecoveryGapReason, type AiRetryModelStepOutcome, type AiRunCurrent, type AiRunCurrentEntry, type AiSessionMirror, type ChatMessage, type CompactionPhase, type ContextBreakdownItem, type ContextCompaction, type ContextUsage, type GateStaleCode, type GateSubmission, type MessagePart, type MessageTruncationFields, type PendingToolCall, type SessionPurgeResponse, type SheetPreviewLevel, type SheetWritePlan, type ToolPart, type ToolPartPatch, type ToolRunStatus } from '@/types/ai'
import type { FormSpec, FormValueIssue } from '@/types/form-schema'
import { FORM_DRAFT_PREFIX, clearFormDraft, loadFormDraft, pruneFormDrafts, saveFormDraft } from '@/utils/form-draft'
import { parseFormSchema, checkFormValues } from '@/types/form-schema'
import type { SseFrame } from '@/types/sse'
import type { ValidationIssue } from '@/types/job'
import type { WorkspaceActionRecord } from '@/types/tools'
import { clearPublishToolLinks, findPublishTool, linkPublishTool, unlinkPublishTool } from '@/utils/publish-links'
import { buildErrorCard } from '@/utils/error-card'
import { buildPublishConflictView } from '@/utils/resultview'
import {
  runExpiryAnchorOf,
  runExpiryRemainingMs,
  type RunExpiryAnchor
} from '@/utils/run-expiry'
import { translate } from '@/composables/useCopy'

/** sessionStorage 键：会话 id 与镜像前缀（Task 4 起带身份时追加 `tenantId:userId` 指纹）。 */
export const SESSION_ID_KEY = AI_SESSION_ID_KEY
export const MIRROR_PREFIX = AI_MIRROR_PREFIX

/**
 * AR-07：同批后续表单的排队态 —— 只保存"重建这张卡所需"的三件。
 *
 * <p>为什么不在排队时就建 `ActiveGenerativeForm`：活动表单的 `status` 蕴含"用户此刻能填/能交"，
 * 排队中的那张还没有这个语义；草稿仍按 toolCallId 留在 sessionStorage（{@link loadFormDraft}），
 * 轮到它时再回填。这样"同一时刻只有一个活动表单"由类型本身约束，不靠调用方自觉。
 */
export interface QueuedGenerativeForm {
  toolCallId: string
  runId: string
  /** 已过防御性解析的 schema（解析失败的不会进队） */
  form: FormSpec
}

/** `send()` 收尾结果：composer 据此决定是否保稿、是否设编辑锚点。 */
export type AiSendOutcome = 'ok' | 'error' | 'interrupted' | 'cancelled' | 'not-sent'
export interface AiSendResult {
  outcome: AiSendOutcome
  /** 本轮新记录的用户消息 id；`skipUserRecord` / 早退为 null。 */
  userMessageId: string | null
}

/**
 * 通知槽级别（AR-13）：**随通知自身写入**，不从 `lastRoundOutcome` 推断 ——
 * 换会话/新轮留下的旧结局不得把一条无害提示染红。
 */
export type NoticeSeverity = 'info' | 'success' | 'warning' | 'error'

/** 当前轮的 AbortController（模块级：不进响应式状态）。 */
let currentAbort: AbortController | null = null
/**
 * Task 4：会话发号引导门（模块级）。
 *
 * <p>同一身份并发进入（面板恢复 + 首条发送 + 复制页签判定）只发**一次** `POST /api/ai/session`；
 * 切换身份/换会话时递增 `sessionGateToken`，在途旧号的回包据此丢弃（不得写进新身份）。
 */
let sessionGate: Promise<string> | null = null
let sessionGateFingerprint: string | null = null
let sessionGateToken = 0
/** 所有权探活的单飞句柄（面板挂载 + 发号成功补判可能同时触发）。 */
let ownershipProbe: Promise<void> | null = null
/** 目录加载单飞句柄（`ensureSession` 在需要身份时等待它，而不是让面板阻塞启动）。 */
let directoryGate: Promise<void> | null = null
/**
 * 当前预览计划对应的表格上下文（模块级：不进响应式状态）。
 *
 * 为什么单独留一份：预览收口（换选区 / 换页签 / 离开页面 / 换会话 / 停止）必须**同步**清掉
 * 屏幕上的预览色，而 `resolveSheetContext()` 是异步的、且页面卸载后 handler 已经摘掉 ——
 * 那时再解析只会拿到「当前页面没有可操作的表格」。这份引用在**建卡时**取一次，
 * 收口时直接用它把颜色擦掉（与 `activePreview` 同寿，任何清空 activePreview 的路径都同步清它）。
 */
let previewSheetCtx: SheetContext | null = null
/**
 * 预览决策单飞令牌（交互专项 2026-10-25）：同一张卡的应用/取消/撤销只消费一次。
 *
 * <p>为什么需要它：`confirmWritePreview` 里有 `await resolveSheetContext()`，双击会在第一次
 * 尚未落笔时进来第二次 —— 两次都读到同一个 `activePreview`，就会把同一批写入执行两遍、
 * 回灌两次。令牌在首个 `await` 之前同步置位；第二击直接空操作。它是**模块级**的：
 * 同一页签内多个 store 实例（跨实例重复确认）也共用这一次消费。
 */
let previewDecisionInFlight: string | null = null

function previewDecisionKey(plan: SheetWritePlan): string {
  return plan.previewId ?? `${plan.toolCallId}:${plan.planId}`
}

/** 尝试占用决策权（false = 同一张卡已有决策在途）。 */
function beginPreviewDecision(plan: SheetWritePlan): boolean {
  const key = previewDecisionKey(plan)
  if (previewDecisionInFlight === key) {
    return false
  }
  previewDecisionInFlight = key
  return true
}

function endPreviewDecision(plan: SheetWritePlan): void {
  if (previewDecisionInFlight === previewDecisionKey(plan)) {
    previewDecisionInFlight = null
  }
}
/**
 * 当前轮的宿主助手消息 id（模块级：与 `currentAbort` 同为"这一轮"的身份判据）。
 *
 * issue#5 R2：终帧只允许复位**本轮**的轮次级状态 —— 旧轮迟到帧（`resetSession` 之后、
 * 或新轮已起）不得把新轮标记成空闲。runId 不能单独做判据：新一轮 start 帧到达之前
 * `activeRunId` 仍是上一轮的值，而宿主消息 id 在 send / reattachActive / 续收入口即已就位。
 */
let activeRoundHostId: string | null = null

/**
 * 轮次纪元（B1 收口修 C-1）：**真正换轮**才递增 —— `send()` 起新轮、`reattachActive()` 重挂到
 * 既有轮、`adoptFreshSession()` 换会话、`stop()` 取消本轮。
 *
 * 与 `currentAbort` 的分工：后者是**连接**身份，挂起收流（服务端主动收流 → `finally` 置 null）
 * 与决策后的同轮重连（`resumeSnapshotStream` 换 controller）都会换掉/清掉它，但那一轮**并未结束**
 * ——在途前端工具的结局仍属于本轮，必须照常写段终态并回灌。因此 `runFrontendTool` 的"晚到结局
 * 丢弃"守卫比对本纪元，而不是比对 controller 引用；上列四个换轮入口之外的任何路径都不得递增。
 */
let roundEpoch = 0

/**
 * 轮次现场快照（B2 共性代次）：异步动作**入队/发起时**取一份，回到现场再比一次。
 *
 * <p>三件一起判：会话身份（换会话后旧现场不得落进新会话）、轮次纪元（停止/换轮即失效）、
 * 以及必要时的 runId（同一会话里只允许原来那一轮）。判据本身是纯函数、不读 store 状态
 * —— 排队工具在 `frontendToolChains` 里等执行时也能拿它自查。
 */
interface RoundGuard {
  sessionId: string | null
  epoch: number
  runId: string | null
}

function captureRound(sessionId: string | null, runId: string | null): RoundGuard {
  return { sessionId, epoch: roundEpoch, runId }
}

/**
 * 是否仍是同一轮现场。
 *
 * <p>runId 只做**附加**约束：调用方读不到当前 run（轮已收尾 / 首次挂载）时以会话 + 纪元为准，
 * 否则同会话里换了 run 也算换轮。
 */
function stillSameRound(guard: RoundGuard, sessionId: string | null, runId: string | null): boolean {
  if (roundEpoch !== guard.epoch || sessionId !== guard.sessionId) {
    return false
  }
  return guard.runId === null || runId === null || runId === guard.runId
}

/**
 * 记录某轮已见最大帧序号（B2 有界）：唯一写入口 —— 只增不减 + 只留最近 {@link RUN_SEQ_MAX_RUNS} 个 runId。
 *
 * <p>为什么有界：`runSeq` 按 runId 累积、随会话长期存活，长会话里每个 run 一条永不回收。
 * 键序即插入序，超窗时丢最旧的那个；未超窗时返回原对象（不制造无谓的响应式写入）。
 */
const RUN_SEQ_MAX_RUNS = 16

function recordRunSeq(runSeqs: Record<string, number>, runId: string, seq: number): Record<string, number> {
  runSeqs[runId] = Math.max(runSeqs[runId] ?? 0, seq)
  const ids = Object.keys(runSeqs)
  if (ids.length <= RUN_SEQ_MAX_RUNS) {
    return runSeqs
  }
  return Object.fromEntries(
    ids.slice(-RUN_SEQ_MAX_RUNS).map((id) => [id, runSeqs[id] as number] as const)
  )
}

/**
 * 轮终态序号（M-d / fix2）：`done` / `error` 各递增一次，且只在**本轮**的终帧上递增
 * （帧归属守卫已在前置 `ownedByActiveRound` 分支返回旧轮帧）。
 *
 * <p>为什么需要它：决策 POST 在途时，本轮的结局帧可能先到（帧与决策同锁发出）——
 * `suspended` 会被结局帧清掉，此后 `reconnectSuspendedRun` 的"帧先于 POST"补救分支就
 * 必须能区分"帧只是清了挂起（本轮仍在服务端挂起，要续收）"与"本轮已经收口（绝不重连终态）"。
 * 决策入口捕获本值，送达后不等 ⇒ 不重连。不响应式、不落镜像。
 */
let roundTerminalSeq = 0

/**
 * R-166（口径③）：409 幂等拒绝后**等待真实裁决**的宽限（毫秒）—— 到点仍未收到结局帧，
 * 就拉一次 `GET /api/ai/runs/current` 校正（见 `syncGateFromAuthority`）。
 * 取值 3s：挂起期心跳节拍 2s，结局帧与决策同锁发出（`ConfirmGate.submitDecision` 的
 * 「落库 → 发帧 → 唤醒」），故 3s 足够覆盖正常投递；再等只是让用户多看一会儿假态。
 */
export const GATE_SYNC_GRACE_MS = 3000

/**
 * FE-17：409 后权威校正的**次数上限**（有界重试，含首次）。
 *
 * 旧口径只查一次：条目仍 PENDING（滞后读）/ 轮次快照取不到时，门卡就永久停在
 * 「已提交，同步中…」——双按钮与输入区一直禁用，只能刷新。改成有界退避（3s / 6s / 12s，
 * 合计 21s）后，用尽即**解锁并如实提示**：409 是幂等拒绝，用户重试不会翻转已落的裁决。
 */
export const GATE_SYNC_MAX_ATTEMPTS = 3

/** 409 后的校正在途计时器（按 toolCallId 记账；模块级，不进响应式状态）。 */
const gateSyncTimers = new Map<string, ReturnType<typeof setTimeout>>()
/** 已完成的校正轮次（按 toolCallId 记账；`armGateSync` 的退避指数与"用尽"判据）。 */
const gateSyncAttempts = new Map<string, number>()

function disarmGateSync(toolCallId: string): void {
  const timer = gateSyncTimers.get(toolCallId)
  if (timer !== undefined) {
    clearTimeout(timer)
    gateSyncTimers.delete(toolCallId)
  }
  gateSyncAttempts.delete(toolCallId)
}

function disarmAllGateSync(): void {
  gateSyncTimers.forEach((timer) => clearTimeout(timer))
  gateSyncTimers.clear()
  gateSyncAttempts.clear()
}
/** 批次3："准备中"手动重试的单飞令牌（防双击重复探测）。 */
let preparingRetryInFlight = false

/** 会话镜像是否已启动（防止重复 $subscribe）。 */
let mirrorStarted = false
let mirrorTimer: ReturnType<typeof setTimeout> | null = null
/** 批次7B：trailing 写盘的 idle 句柄（有 requestIdleCallback 时用它，避免占渲染帧）。 */
let mirrorIdleHandle: number | null = null
/** 批G · G4：最后一次真实落盘时刻（throttle 的 leading 判据）与节流窗口。 */
let mirrorLastWriteAt = 0
const MIRROR_THROTTLE_MS = 300

/** 取消待写的 trailing（timer + idle 各一），pagehide/可见性 flush 前必须先取消，避免重复写。 */
function cancelMirrorSchedule(): void {
  if (mirrorTimer !== null) {
    clearTimeout(mirrorTimer)
    mirrorTimer = null
  }
  if (mirrorIdleHandle !== null) {
    const cancel = (window as unknown as { cancelIdleCallback?: (handle: number) => void }).cancelIdleCallback
    if (typeof cancel === 'function') {
      cancel(mirrorIdleHandle)
    }
    mirrorIdleHandle = null
  }
}

/** 批次7B：把一次（已经节流过的）落盘放到浏览器空闲期；没有 idle API 时同步执行。 */
function writeMirrorWhenIdle(run: () => void): void {
  const requestIdle = (window as unknown as {
    requestIdleCallback?: (callback: () => void, options?: { timeout?: number }) => number
  }).requestIdleCallback
  if (typeof requestIdle !== 'function') {
    run()
    return
  }
  mirrorIdleHandle = requestIdle(() => {
    mirrorIdleHandle = null
    run()
  }, { timeout: MIRROR_THROTTLE_MS })
}

/**
 * 批次2（F6）：**开流前**瞬时失败的有界重试。
 *
 * <p>判据极窄 —— 只有"一个帧都没收到"（`sawAnyFrame === false`）、本轮仍属当前连接、且失败是
 * 瞬时类型（网络不可达 / 502/503/504 的传输层失败）时才重试；`SESSION_BUSY` / `SESSION_PURGED`
 * 与配置类 503（无 key / 无模型 / Redis 不可用）都**不**重试。重试复用同一条用户气泡与同一条
 * 助手消息（零副作用：不追加消息、不重跑工具、不换会话），次数 = 1（共 2 次尝试）。
 */
export const PRE_STREAM_RETRY_MAX = 1
/** 重试前等待（毫秒）：给瞬时抖动一个恢复窗口，但不让用户感知为"卡住"。 */
export const PRE_STREAM_RETRY_DELAY_MS = 400

/**
 * 共用**有界自动恢复**的预算（2026-10-29 ToC 收口批）：每类事件最多自动尝试次数、两次尝试的间隔。
 *
 * <p>四类事件（准备中 / 首帧超时 / 历史读取失败 / 410 已换会话）共用同一套预算与调度：
 * 只对**可证明零副作用**的恢复动作发起（读快照、读历史、把既有轮接回来），绝不重发用户消息、
 * 不重放工具；耗尽后一律回到既有的手动出口（错误条重试 / 回稿）。
 */
export const AUTO_RECOVERY_MAX_ATTEMPTS = 2
export const AUTO_RECOVERY_DELAY_MS = 400

/** 自动恢复的四类触发面（预算与调度按类共享；同类同时只有一条恢复链）。 */
export type AutoRecoveryKind = 'preparing' | 'first-frame' | 'history' | 'purged'

/** 已排上的自动恢复计时器（按类唯一）。 */
const autoRecoveryTimers = new Map<AutoRecoveryKind, ReturnType<typeof setTimeout>>()
/** 正在等下一次尝试的解析函数（作废时立刻放行，避免悬空 await）。 */
const autoRecoveryWaiters = new Map<AutoRecoveryKind, () => void>()
/** 正在执行恢复尝试的类别（防重入）。 */
const autoRecoveryInFlight = new Set<AutoRecoveryKind>()
/** 每类一个版本号：换会话 / 停止 / 新轮 / 用户手动重试都推进它，链路每步之后对版。 */
const autoRecoveryEpochs = new Map<AutoRecoveryKind, number>()

function autoRecoveryEpoch(kind: AutoRecoveryKind): number {
  return autoRecoveryEpochs.get(kind) ?? 0
}

/**
 * 作废自动恢复：清计时器、放行等待中的链路、推进版本号。缺省 = 全部作废
 * （换会话 / 停止 / 新发送这类"现场整体作废"的出口）。
 */
function cancelAutoRecovery(kind?: AutoRecoveryKind): void {
  const kinds = kind === undefined
    ? [...new Set<AutoRecoveryKind>([
      ...autoRecoveryEpochs.keys(),
      ...autoRecoveryTimers.keys(),
      ...autoRecoveryWaiters.keys(),
      ...autoRecoveryInFlight
    ])]
    : [kind]
  for (const item of kinds) {
    autoRecoveryEpochs.set(item, autoRecoveryEpoch(item) + 1)
    const timer = autoRecoveryTimers.get(item)
    if (timer !== undefined) {
      clearTimeout(timer)
      autoRecoveryTimers.delete(item)
    }
    const waiter = autoRecoveryWaiters.get(item)
    if (waiter !== undefined) {
      autoRecoveryWaiters.delete(item)
      waiter()
    }
  }
}

/** 重连 / 续收的"正在第 N 次重试"进度通知码 —— 它们只描述过程，过程一结束就该消失。 */
const RECONNECT_PROGRESS_NOTICES: ReadonlySet<string> = new Set([
  'ai.notice.reconnect',
  'ai.notice.resumeRetry'
])

/**
 * 清掉重连 / 续收进度通知（只清这一族，不误清别的提示）。
 *
 * <p>为什么必须清：`onReconnect` 写的是"连接中断，正在第 N 次重连"这类**过程**事实。
 * 连接一旦打开（onOpen）/ 首业务帧到达 / 终帧到达，过程就结束了 —— 留着它等于在一条
 * 正常工作的连接上一直说"正在重连"。
 */
function clearReconnectProgressNotice(sink: NoticeSink): void {
  const code = sink.noticeCode
  if (code !== null && RECONNECT_PROGRESS_NOTICES.has(code)) {
    clearNotice(sink)
  }
}

/**
 * O02：同 tick「停止 + 发送」时，发送在**停止落地**（cancel 送达）前最多等这么久。
 *
 * <p>停止是一个决策：`stop()` 的本地收敛是同步的，cancel 请求则要一点时间才到后端。
 * 发送等它落地，才不会撞上"停止尚未传播"的残留 409（那会让界面谎报"上一轮仍在进行中"）。
 * 等待有界：后端不可达时不能让用户的新消息卡在输入框里 —— 超时后照常发送，残影 409
 * 仍由 {@link isAuthoritativeBusyRun} 拦下。
 */
export const STOP_SETTLE_MAX_WAIT_MS = 2_000

/**
 * M-19（2026-10-29）：**start 帧之前就停止**时，"停止意向"的有效期（毫秒）。
 *
 * <p>这段时间里后端可能刚建好这一轮（runId 只能从 start 帧或下一条消息的 409 响应体里拿到）。
 * 超时即作废 —— 绝不拿一个很久以前的停止意向去取消后来的新轮。
 */
export const STOP_INTENT_TTL_MS = 60_000

/**
 * O02：`stop()` 的落地闸门（模块级，与 `currentAbort` 同为"这一轮"的句柄）。
 * 非 null = 有一次停止还在等 cancel 送达；{@link send} 据此先等它落地。
 */
let stopSettleGate: Promise<void> | null = null

/** 等停止落地（有界：见 {@link STOP_SETTLE_MAX_WAIT_MS}）。无停止在途 = 立即返回。 */
function waitForStopSettlement(): Promise<void> {
  const gate = stopSettleGate
  if (gate === null) {
    return Promise.resolve()
  }
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, STOP_SETTLE_MAX_WAIT_MS)
    void gate.then(() => {
      clearTimeout(timer)
      resolve()
    })
  })
}

/**
 * M08-R2（真机 40793937300）：同一 runId 的**续流驱动**预算 —— 一次 SSE 生命周期
 * （一次 `aiApi.reattachRun` 调用，其内部已自带 4 次退避重连，即原预算）。
 *
 * <p>为什么按"生命周期"而不是按"连接"计：连接一断就清登记（上一版的做法）会让另一个恢复
 * 入口下一拍再建一条，两条驱动各烧一份内部重试预算 —— 真机上同一 runId 出现 `?lastSeq=0`
 * 与无 query 各 5 次交替（5 = 1 次建连 + 4 次重连，两份预算 = 10）。现在所有权与预算都按
 * runId 固化到"驱动"上：连接存活、退避、重连都是同一个驱动；只有终态 / 停止 / 换会话 /
 * 显式动作（决策、用户重试）才释放或重置，连接结束本身不算放弃这一轮。
 */
export const RUN_STREAM_DRIVE_BUDGET = 1

/** M08-R2：某 run 的续流驱动现场（所有权 + 预算）。 */
interface RunStreamDrive {
  /** 驱动令牌（每次真正接管时递增；旧驱动的收尾据它判定"已被接管"） */
  token: number
  /** 已消耗的生命周期预算（只有**中断**收尾才消耗：终态/挂起/准备中/用户停止都不算） */
  used: number
  /** 是否有在途驱动（连接存活 / 退避 / 重连中） */
  active: boolean
}

/** M08-R2：驱动令牌发号器（模块级：与 `currentAbort` 同为连接/驱动身份判据）。 */
let runStreamDriveSeq = 0

/** 这些码即使带 5xx 也是**确定性**失败（重试只会重复同一结果）。 */
const NON_TRANSIENT_PRE_STREAM_CODES: ReadonlySet<string> = new Set([
  ErrorCode.SESSION_BUSY,
  ErrorCode.SESSION_PURGED,
  ErrorCode.AI_UNAVAILABLE,
  ErrorCode.AI_KEY_MISSING,
  ErrorCode.AI_KEY_UNREADABLE,
  ErrorCode.AI_REDIS_UNAVAILABLE,
  ErrorCode.NO_MODEL_CONFIGURED
])

/** 开流前失败是否属于"瞬时、可安全重试"（零副作用）类型。 */
function isTransientPreStreamError(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return false
  }
  if (NON_TRANSIENT_PRE_STREAM_CODES.has(error.code)) {
    return false
  }
  if (error.code === FRONTEND_ERROR_CODES.NETWORK_ERROR || error.status === 0) {
    return true
  }
  // 502/504 = 网关瞬时失败；503 在配置类码被排除后也只剩传输层/池抖动
  return error.status === 502 || error.status === 503 || error.status === 504
}

/** 任意异常 → 视图错误现场（机器码，不含文案）。 */
function toLoadErrorView(scope: AiLoadErrorScope, error: unknown): AiLoadErrorView {
  if (error instanceof ApiError) {
    return { scope, code: error.code, status: error.status, at: Date.now() }
  }
  return { scope, code: FRONTEND_ERROR_CODES.UNKNOWN_ERROR, status: 0, at: Date.now() }
}

/**
 * 批次3 / AR-06：`/runs/current` 是否表示"已 start、快照未建"。
 *
 * 两个等价信号（后端择一即可）：显式 `preparing=true`，或 `snapshotStatus='PREPARING'`。
 * `found` 必须为真且有 runId —— 空态（found=false）仍然是"没有在途轮"，不冒充准备中。
 */
function preparingRunOf(current: AiRunCurrent): AiPreparingRun | null {
  if (!current.found || !current.runId) {
    return null
  }
  const status = (current.snapshotStatus ?? '').toUpperCase()
  if (current.preparing !== true && status !== 'PREPARING') {
    return null
  }
  const reportedElapsed = current.preparingElapsedMs ?? current.elapsedMs
  const reportedSince = current.preparingSinceMs ?? current.startedAtMs
  const elapsedMs = typeof reportedElapsed === 'number' ? Math.max(0, reportedElapsed) : null
  const startedAtMs = elapsedMs !== null
    ? Date.now() - elapsedMs
    : typeof reportedSince === 'number'
      ? reportedSince
      : Date.now()
  return { runId: current.runId, startedAtMs, elapsedMs }
}

/** 批次3：阶段心跳里的真实已用毫秒（`elapsedMs` 优先，其次整秒兼容字段，缺省 = null）。 */
function heartbeatElapsedMs(frame: { elapsedMs?: unknown; elapsedSeconds?: unknown }): number | null {
  if (typeof frame.elapsedMs === 'number' && Number.isFinite(frame.elapsedMs)) {
    return Math.max(0, frame.elapsedMs)
  }
  if (typeof frame.elapsedSeconds === 'number' && Number.isFinite(frame.elapsedSeconds)) {
    return Math.max(0, Math.round(frame.elapsedSeconds * 1000))
  }
  return null
}

/** 409 回执里的 `reattach` 必须与 `runId` 推出的事件路径一致，否则视为不可信（不指向编造地址）。 */
function trustedReattachPath(runId: string, reattach: unknown): string | null {
  if (typeof reattach !== 'string' || reattach === '') {
    return null
  }
  return reattach === `/api/ai/events/${encodeURIComponent(runId)}` ? reattach : null
}

/** 批次2：409 回执 → 可手动指回的进行中轮（runId 不可用/不可信时返回 null）。 */
function readBusyRunRef(error: ApiError): AiBusyRunRef | null {
  const body = error.bodyAs<{ runId?: unknown; reattach?: unknown; unknown?: unknown }>()
  if (!body || body.unknown === true || typeof body.runId !== 'string' || body.runId.trim() === '') {
    return null
  }
  const runId = body.runId
  return { runId, reattach: trustedReattachPath(runId, body.reattach) }
}

/**
 * 批次2（AR-05）：中性「状态未确认」终帧的判据。
 *
 * <p>只认**显式标记**（协议增量，均为 additive 键）：`code=TURN_UNCONFIRMED` / `turnEnd=unconfirmed`
 * / `unconfirmed=true`。**不**拿 `AI_REDIS_UNAVAILABLE` 猜 —— 那是真实故障码，猜错会把普通失败
 * 说成"可能成功"，比不说更糟。
 */
function isUnconfirmedTerminal(frame: unknown): boolean {
  const raw = frame as { code?: unknown; turnEnd?: unknown; unconfirmed?: unknown }
  return raw.code === 'TURN_UNCONFIRMED' || raw.turnEnd === 'unconfirmed' || raw.unconfirmed === true
}

/**
 * 批次2 / AR-05：仅模型步重跑的 409 前置失败码（后端 `failedCheck`）。
 *
 * <p>只从**结构化回执**读取；不拿文案猜。缺省 = 普通可重试失败。
 */
function failedCheckOf(error: unknown): string | null {
  if (!(error instanceof ApiError)) {
    return null
  }
  const body = error.bodyAs<{ failedCheck?: unknown }>()
  return body && typeof body.failedCheck === 'string' ? body.failedCheck : null
}

/** 重跑窄入口 409 回执里的 `reason`（在后端未给 `failedCheck` 时用于区分在途/已取消）。 */
function retryReasonOf(error: unknown): string | null {
  if (!(error instanceof ApiError)) {
    return null
  }
  const body = error.bodyAs<{ reason?: unknown }>()
  return body && typeof body.reason === 'string' ? body.reason : null
}

/** 把 `failedCheck` 映射到用户可读的本地化通知码（未知值走通用句）。 */
function retryDeniedNoticeKey(failedCheck: string | null): string {
  switch (failedCheck) {
    case 'NOT_FAILED':
      return 'ai.notice.retryDeniedNotFailed'
    case 'PENDING_TOOLS':
      return 'ai.notice.retryDeniedPending'
    case 'TOOL_PAIRING_INCOMPLETE':
      return 'ai.notice.retryDeniedHistory'
    case 'MODEL_CHANGED':
      return 'ai.notice.retryDeniedModel'
    default:
      return 'ai.notice.retryDenied'
  }
}

/**
 * 目录行收窄（`{tenantId, users:[{userId,displayName}]}`）：非正整数 id / 空用户组一律丢弃，
 * 面板只渲染收窄后的数据（模型/后端返回畸形数据不该让下拉崩掉）。
 */
function normalizeTenantEntry(raw: unknown): TenantUsersEntry | null {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const candidate = raw as { tenantId?: unknown; tenantName?: unknown; users?: unknown }
  if (!isPositiveId(candidate.tenantId) || !Array.isArray(candidate.users)) {
    return null
  }
  const users = candidate.users
    .map((item) => {
      if (!item || typeof item !== 'object') {
        return null
      }
      const user = item as { userId?: unknown; displayName?: unknown; userName?: unknown }
      if (!isPositiveId(user.userId)) {
        return null
      }
      const label = typeof user.displayName === 'string' && user.displayName !== ''
        ? user.displayName
        : (typeof user.userName === 'string' && user.userName !== '' ? user.userName : String(user.userId))
      return { userId: user.userId, displayName: label }
    })
    .filter((user): user is { userId: number; displayName: string } => user !== null)
  return users.length > 0
    ? {
        tenantId: candidate.tenantId,
        ...(typeof candidate.tenantName === 'string' && candidate.tenantName !== '' ? { tenantName: candidate.tenantName } : {}),
        users
      }
    : null
}

/** 目录里是否有该身份（刷新恢复时校验存量身份仍然有效）。 */
function directoryHas(directory: TenantUsersEntry[], identity: AiIdentity): boolean {
  return directory.some(
    (entry) => entry.tenantId === identity.tenantId
      && entry.users.some((user) => user.userId === identity.userId)
  )
}

/** 页面离开/隐藏时的立即落盘是否已装上（只装一次）。 */
let mirrorFlushInstalled = false
let mirrorFlushHandler: (() => void) | null = null
/** 工作区契约事件订阅是否已启动 + 反订阅句柄。 */
let workspaceSyncStarted = false
let workspaceSyncStops: Array<() => void> = []

/**
 * 成功即**不回执位**的 ui_event（"无噪声成功"）。
 *
 * 这六类动作都是"把界面摆到该在的位置"：导航 / 恢复向导 / 切步骤 / 三类刷新。用户能直接看见
 * 结果（页面已经换过去了），回执位再报一条"已打开导出向导"就是噪声；模型侧要的证明也已经由
 * 工具结果（回灌文本）给出，不靠这条 Alert。
 *
 * **不在此列的一律保留**（失败永远保留）：`run_job` / `cancel_job` / `download` / `select_defs` /
 * `set_conditions` / `set_import_mode` / `export_started` / `import_batch` / `import_action` ——
 * 它们的成功意味着后端已落副作用（建作业、取文件、改数据），必须有一条可见回执。
 *
 * 底层不动：`workspace.touch` / `recentActions` / `workspace_changed` chip / `dispatchUiEvent` 的广播
 * 全部照旧 —— 本表只决定 **AI 面板回执位**（`lastUiEventResult`）写不写。
 */
const SILENT_SUCCESS_UI_EVENTS: ReadonlySet<string> = new Set([
  'open_page',
  'restore_task',
  'goto_step',
  'refresh_defs',
  'refresh_data',
  'refresh_tasks'
])

/**
 * 页签随机 id（**只**用于 pageId 轮换）。会话 id 一律由后端 `POST /api/ai/session` 发号，
 * 本函数与"会话"无关 —— 名字保持中性，防止再被误用为本地会话号。
 */
export function createPageIdToken(): string {
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
 * R-166 口径③/④：挂起条目态（后端 `PendingToolCall` 状态常量 / `runs/current` 的 `entryStatus`）
 * → 确认门回执（`decision` + 段终态）。
 *
 * 为什么不复用 `frameStatusToPendingStatus`：那张表是**帧/回灌**口径，**不收录 `APPROVED` /
 * `EXECUTED`**（`/confirm` 的 409 响应体与 `runs/current` 却正是这两个值），照用会把"已批准"
 * 读成"未定"⇒ 门卡永远收不了口。`null` = 条目仍未定（PENDING / 未知态）。
 */
function gateReceiptOfStatus(status: unknown): { decision?: 'approved' | 'rejected' | 'cancel'; status: ToolRunStatus } | null {
  switch (status) {
    case 'APPROVED':
      return { decision: 'approved', status: 'running' }
    case 'EXECUTED':
      return { decision: 'approved', status: 'succeeded' }
    case 'SUCCEEDED':
      return { decision: 'approved', status: 'succeeded' }
    case 'REJECTED':
    case 'REJECTED_ARGUMENTS':
      return { decision: 'rejected', status: 'rejected' }
    case 'TIMEOUT':
      return { status: 'expired' }
    case 'CANCELLED':
    case 'FRONTEND_CANCELLED':
      // 批G · S13：取消是用户决策（与 confirm_decision(decision=cancel) 帧同口径）⇒「✗ 已取消」
      return { decision: 'cancel', status: 'cancelled' }
    case 'BLOCKED':
      return { status: 'blocked' }
    default:
      return null
  }
}

/**
 * ── RC-4：复制页签会话隔离（三层握手，**替换** R-166 口径⑤的 navigation type 判据）─────────
 *
 * <h3>为什么换判据</h3>
 * R-166 用 `performance.getEntriesByType('navigation')[0].type` 判页签旅程：`navigate` 判
 * "新页签 / 复制页签 / 手输地址"、`reload` 与 `back_forward` 判"同一页签"。真机实测
 * **复制页签 = `back_forward`**（浏览器按历史恢复复制）⇒ 判据整个作废：复制页签会连
 * `sessionStorage` 一起继承，两页签共写同一后端会话，正是本项要修的场景。
 *
 * <h3>三层握手（业界口径）</h3>
 * ① **pageId**：每页签一个 `crypto.randomUUID()` 存 `sessionStorage`（`ai-tab-page-id`）——
 *    本页签身份，随刷新保持；复制页签会继承原值，故**判为复制品时轮换**。
 * ② **Web Locks 探活**（决定性判据）：`navigator.locks.request('ai-session-<sessionId>',
 *    { ifAvailable: true })` —— 拿到锁 ⇒ 本页签是原页签（持锁到文档销毁）；回调收到 `null`
 *    ⇒ 该会话已被另一页签持有 ⇒ **本页签是复制品** ⇒ 起新会话，不接管原会话。
 *    锁是浏览器按**文档（页签）**维护的资源，不依赖任何前端启发式。
 * ③ **BroadcastChannel**（`ai-session-ownership`）：`claim` / `released` 两种广播 ——
 *    原页签 `pagehide` 时广播 `released`（**让渡意向**），被判为复制品的**空白**页签在
 *    {@link OWNERSHIP_RELEASE_GRACE_MS} 宽限后**再探一次锁**才接手；原页签在这段期间重新广播
 *    `claim`（刷新回来拿到同一把锁）⇒ 副本撤销接手。
 *
 * <h3>为什么 `released` 不是"立刻交接"（收口）</h3>
 * 旧实现 `pagehide` 一到就 `released` + 释放锁：刷新 / 慢重载时空白副本会立刻接手，原页签的新文档
 * 回来反被判成复制品并清屏。现在 `pagehide` 只广播意向、**不**同步释放锁（锁的真实归属由浏览器在
 * 文档销毁时裁决），并给副本一段**有界宽限**：
 * <ul>
 * <li>宽限是**顺序辅助**，不是"某毫秒能证明所有浏览器/任意慢重载"——锁始终是唯一裁决；</li>
 * <li>确实关掉的页签：文档销毁 ⇒ 锁释放 ⇒ 副本宽限后探活拿到锁，照旧接手（现有意图保留）；</li>
 * <li>比宽限还慢的重载：副本可能先接手。回来的原页签凭 `pagehide` 写下的**返回意向**
 *   （{@link RETURN_INTENT_KEY}，一次性、按 pageId + 时效校验）认出自己不是复制品，只把角色
 *   降级为 'duplicate'，**不清会话、不清屏**（复制页签没有这份意向，仍走"换新会话 + 清镜像"）。</li>
 * </ul>
 *
 * <h3>bfcache</h3>
 * `pagehide.persisted === true` = 文档进 bfcache（还会回来）⇒ **不广播、不释放**；`pageshow`
 * 恢复后做一次**有界重认领**：锁仍归我 ⇒ 重播 `claim`；锁已归别人 ⇒ 角色降级为 'duplicate'，
 * 但不清会话（清屏在 bfcache 恢复时是灾难），也不再自认 owner（不双广播）。
 *
 * 环境不支持 Web Locks（旧浏览器 / jsdom）⇒ 判 'unsupported'，按**恢复**处理（保守：不丢现场，
 * 与 R-166 时代"读不到导航条目按恢复"同一兜底方向）。
 */

/** 本页签 pageId 的 sessionStorage 键（RC-4 ①）。 */
export const PAGE_ID_KEY = 'ai-tab-page-id'
/** 会话所有权锁名前缀（RC-4 ②）：`ai-session-<sessionId>`；带身份时 `ai-session-<t>:<u>:<sessionId>`。 */
export const SESSION_LOCK_PREFIX = AI_SESSION_LOCK_PREFIX
/** 所有权广播通道名（RC-4 ③）。 */
export const OWNERSHIP_CHANNEL_NAME = 'ai-session-ownership'
/** 锁探活次数与间隔（RC-4 ②）：刷新时旧文档的锁可能尚未随文档销毁释放，限次重试再判"被占"。 */
export const LOCK_PROBE_ATTEMPTS = 3
export const LOCK_PROBE_RETRY_MS = 150
/**
 * 让渡宽限（毫秒）：副本收到 `released` 后等这么久再探活/接手，给原页签的新文档留出"拿回同一把锁
 * 并重播 claim"的窗口（`claim` 会撤销待接手）。取 1500ms 只是常见刷新时长的折中，**不是正确性证明**
 * —— 真正的裁决仍是锁；慢于宽限的重载由 {@link RETURN_INTENT_KEY} 的降级路兜住"不清屏"。
 */
export const OWNERSHIP_RELEASE_GRACE_MS = 1500
/** 「本页签刚从 pagehide 回来」的一次性意向键（慢重载时用来区分"回来的原页签"与"复制页签"）。 */
export const RETURN_INTENT_KEY = 'ai-session-return-intent'
/** 返回意向的有效期：过期的意向不再可信（避免旧值把后续复制页签误判成原页签）。 */
export const RETURN_INTENT_TTL_MS = 60_000

/** 本页签的会话所有权角色（RC-4）：'duplicate' = 由复制产生、已换新会话且尚未接手原会话；
 *  'unsupported' = 环境无 Web Locks（旧浏览器）⇒ 按恢复处理（终态，不再探活）。 */
export type TabOwnership = 'unknown' | 'owner' | 'duplicate' | 'unsupported'

/** 所有权通道的最小面（环境无 `BroadcastChannel` 时为 null）。 */
interface OwnershipChannel {
  postMessage(data: unknown): void
  close(): void
  onmessage: ((event: { data: unknown }) => void) | null
}

/** 通道消息（RC-4 ③）：`claim` = 认领 / `released` = 原页签离开、所有权让渡。 */
export interface OwnershipMessage {
  kind: 'claim' | 'released'
  sessionId: string
  pageId: string
  /**
   * Task 4：身份指纹（`tenantId` + `userId`）。带身份时**必填**且必须与接收方身份一致 ——
   * 不同租户/用户不得互相接管会话；无身份（未接目录）时缺省，保持旧单用户口径。
   */
  tenantId?: number
  userId?: number
}

/** 本页签持有的会话锁与自有会话 id（模块级：文档级资源，不进响应式状态）。 */
let heldLock: { name: string; release: () => void } | null = null
let ownedSessionId: string | null = null

/**
 * 只读探针（诊断/测试）：本页签当前记账的会话锁名（null = 账本为空）。
 * 用于断言"当前会话身份"与 `heldLock` 不发生分叉（例如竞态放弃分支不得留下别人的锁）。
 */
export function heldSessionLockName(): string | null {
  return heldLock?.name ?? null
}
let ownershipChannel: OwnershipChannel | null = null
let ownershipLifecycleBound = false
/** 本页签曾在 pagehide 里"离开"过（bfcache 冻结 / 卸载）——`pageshow` 只在此时重认领。 */
let ownershipHidden = false
/** 待接手的让渡（`released` 之后的宽限计时器）；`claim` 到达即撤销。 */
let pendingTakeover: { sessionId: string; timer: ReturnType<typeof setTimeout> } | null = null
/** 生命周期监听只绑一次，回调里的 store 面由最近一次 `ensureTabOwnership` 的实例提供。 */
let tabStore: TabOwnershipSink | null = null

/** 生命周期回调需要的 store 最小面（避免闭包捕获某一个 pinia 实例）。 */
interface TabOwnershipSink {
  revalidateTabOwnership(): Promise<void>
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function readPageId(): string {
  try {
    return sessionStorage.getItem(PAGE_ID_KEY) ?? ''
  } catch {
    return ''
  }
}

/** 本页签 pageId（RC-4 ①）：缺失即生成落盘；复制页签继承原值，判为复制品时由调用方轮换。 */
function ensurePageId(): string {
  const stored = readPageId()
  return stored !== '' ? stored : rotatePageId()
}

function rotatePageId(): string {
  const created = createPageIdToken()
  try {
    sessionStorage.setItem(PAGE_ID_KEY, created)
  } catch {
    // 隐私模式/配额：落不了盘不影响本页签内存里的身份
  }
  return created
}

function channelCtor(): (new (name: string) => OwnershipChannel) | null {
  const ctor = (globalThis as { BroadcastChannel?: new (name: string) => OwnershipChannel }).BroadcastChannel
  return typeof ctor === 'function' ? ctor : null
}

/** 关掉旧通道再开新的（RC-4 ③）：同一文档只留一条所有权通道，重复初始化不叠加监听。 */
function openOwnershipChannel(onMessage: (message: OwnershipMessage) => void): void {
  closeOwnershipChannel()
  const ctor = channelCtor()
  if (!ctor) {
    return
  }
  try {
    const channel = new ctor(OWNERSHIP_CHANNEL_NAME)
    channel.onmessage = (event) => {
      const data = event.data as Partial<OwnershipMessage> | null
      if (!data || (data.kind !== 'claim' && data.kind !== 'released') || typeof data.sessionId !== 'string') {
        return
      }
      onMessage({
        kind: data.kind,
        sessionId: data.sessionId,
        pageId: typeof data.pageId === 'string' ? data.pageId : '',
        ...(typeof data.tenantId === 'number' ? { tenantId: data.tenantId } : {}),
        ...(typeof data.userId === 'number' ? { userId: data.userId } : {})
      })
    }
    ownershipChannel = channel
  } catch {
    ownershipChannel = null
  }
}

function closeOwnershipChannel(): void {
  try {
    ownershipChannel?.close()
  } catch {
    // 关不掉只影响本页签的广播能力，不影响所有权判定
  }
  ownershipChannel = null
}

/**
 * 所有权消息的身份判据（Task 4）：双方身份必须**完全一致**（含"双方都无身份"的旧单用户口径）。
 * 不同租户/用户之间既不认领也不让渡 —— 收到异身份消息一律忽略，避免跨身份接管。
 */
function ownershipIdentityMatches(message: OwnershipMessage, identity: AiIdentity | null): boolean {
  const hasMessageIdentity = typeof message.tenantId === 'number' && typeof message.userId === 'number'
  if (identity === null) {
    return !hasMessageIdentity
  }
  return hasMessageIdentity
    && message.tenantId === identity.tenantId
    && message.userId === identity.userId
}

function postOwnership(kind: OwnershipMessage['kind'], sessionId: string): void {
  // 身份指纹随消息发出（接管方据此拒绝跨租户/跨用户接手）；无身份时省略字段，保持旧单用户口径。
  const identity = currentAiIdentity()
  const message: OwnershipMessage = {
    kind,
    sessionId,
    pageId: readPageId(),
    ...(identity ? { tenantId: identity.tenantId, userId: identity.userId } : {})
  }
  try {
    ownershipChannel?.postMessage(message)
  } catch {
    // 广播失败只影响让渡，不影响本页签继续用当前会话
  }
}

/** Web Locks 面（RC-4 ②）：环境不支持时为 null ⇒ 判 'unknown' 走恢复兜底。 */
function lockManager(): LockManager | null {
  try {
    const locks = typeof navigator === 'undefined' ? undefined : navigator.locks
    return locks && typeof locks.request === 'function' ? locks : null
  } catch {
    return null
  }
}

/**
 * 会话锁探活（RC-4 ②）：'granted' = 锁到手（本页签是原页签，持锁至文档销毁）；
 * 回调收到 `null` = 'occupied'（别的页签持着该会话 ⇒ 本页签是复制品）；无锁能力 = 'unsupported'。
 * 调用入口已先判锁能力（`ensureTabOwnership`），这里的 'unsupported' 只服务认领/让渡两个补路。
 *
 * <p>API 失败（`request` 同步抛错 / promise reject）一律判 'unsupported'，**绝不**判 'occupied'：
 * 前者只说明"无法确认"，后者是"另一文档确持锁"的正面证据。判定下游连着不可恢复的整份清空
 * （{@link clearDuplicatedTabStorage}），把"问不到"当"确认复制"会误删现场。
 */
async function probeSessionLock(sessionId: string): Promise<'granted' | 'occupied' | 'unsupported'> {
  const locks = lockManager()
  if (!locks) {
    return 'unsupported'
  }
  const name = sessionLockName(sessionId)
  if (heldLock?.name === name) {
    // 同一文档重复探活：锁已在手（自持锁的 ifAvailable 请求会被判"不可用"）
    return 'granted'
  }
  return await new Promise<'granted' | 'occupied' | 'unsupported'>((resolve) => {
    let settled = false
    const settle = (verdict: 'granted' | 'occupied' | 'unsupported'): void => {
      if (!settled) {
        settled = true
        resolve(verdict)
      }
    }
    try {
      void locks.request(name, { ifAvailable: true }, (lock) => {
        if (!lock) {
          settle('occupied')
          return undefined
        }
        ownedSessionId = sessionId
        settle('granted')
        // 常挂 promise = 持锁到文档销毁（释放由浏览器负责；让渡/离开时显式释放）
        return new Promise<void>((release) => {
          heldLock = { name, release }
        })
      }).catch(() => settle('unsupported'))
    } catch {
      settle('unsupported')
    }
  })
}

/** 释放本页签持有的会话锁（让渡 / 离开页面时用；文档销毁时浏览器自会释放）。 */
function releaseSessionLock(): void {
  const held = heldLock
  heldLock = null
  ownedSessionId = null
  try {
    held?.release()
  } catch {
    // 释放失败只影响让渡时序
  }
}

/**
 * 只释放「本页签文档持有、且确实属于 `sessionId` 这把锁」的会话锁 —— 换会话 id 前用。
 *
 * <p>换 id 不释放旧锁会让 `heldLock` 被随后的新锁覆盖：旧锁的 `release` 句柄丢失，锁一直占着
 * 到文档销毁（别的页签在此期间探活会被误判"原页签还活着"）。反向约束同样重要：**不碰别人的锁**
 * —— 账本不一致时只清归属、不做释放（宁可少释放，也不误放别人的）。
 *
 * <p>两种环境：**supported**（有 Web Locks）按上面的账本释放；**unsupported**（旧浏览器 /
 * jsdom，`ownedSessionId` 恒为 null）两者都是空操作 —— 没有锁可泄漏，也不需要额外分支。
 */
function releaseOwnLock(sessionId: string): void {
  if (ownedSessionId !== sessionId) {
    return
  }
  if (heldLock !== null && heldLock.name !== sessionLockName(sessionId)) {
    ownedSessionId = null
    return
  }
  releaseSessionLock()
}

/** 撤销待接手的让渡（`claim` 到达 / 重新判定角色 / 换会话时）。 */
function cancelPendingTakeover(): void {
  if (pendingTakeover === null) {
    return
  }
  clearTimeout(pendingTakeover.timer)
  pendingTakeover = null
}

/** 记下"本页签刚从 pagehide 离开"的意向（刷新/慢重载回来后据此认出自己不是复制品）。 */
function rememberReturnIntent(sessionId: string): void {
  const identity = currentAiIdentity()
  try {
    sessionStorage.setItem(
      RETURN_INTENT_KEY,
      JSON.stringify({
        sessionId,
        pageId: readPageId(),
        at: Date.now(),
        ...(identity ? { fingerprint: identityFingerprint(identity) } : {})
      })
    )
  } catch {
    // 隐私模式/配额：写不进去只是少一条降级保护，不影响所有权判定
  }
}

/**
 * 读取并**消费**返回意向：一次性（读到即删），且必须同会话 + 同 pageId + 未过期。
 * 任何不自洽的意向都当不存在 —— 复制页签继承了旧意向时，最多退化成"不清屏"的无害降级。
 */
function takeReturnIntent(sessionId: string): boolean {
  let raw: string | null = null
  try {
    raw = sessionStorage.getItem(RETURN_INTENT_KEY)
    if (raw !== null) {
      sessionStorage.removeItem(RETURN_INTENT_KEY)
    }
  } catch {
    return false
  }
  if (raw === null) {
    return false
  }
  try {
    const value = JSON.parse(raw) as { sessionId?: unknown; pageId?: unknown; at?: unknown; fingerprint?: unknown }
    const identity = currentAiIdentity()
    const fingerprintMatches = identity
      ? value.fingerprint === identityFingerprint(identity)
      : value.fingerprint === undefined
    return value.sessionId === sessionId
      && value.pageId === readPageId()
      && fingerprintMatches
      && typeof value.at === 'number'
      && Date.now() - value.at <= RETURN_INTENT_TTL_MS
  } catch {
    return false
  }
}

/**
 * 三层握手的判定入口（RC-4）：锁被占**限次重试**（`LOCK_PROBE_ATTEMPTS` × `LOCK_PROBE_RETRY_MS`）
 * —— 刷新场景里旧文档的锁可能尚未随文档销毁释放，早判会把刷新误判成复制品。
 *
 * <p>`duplicate` 只能由**每一次探活都真实收到 `null` 回调**得出；探活 API 失败（'unsupported'）
 * 按无 Web Locks 保守处理，返回终态 'unsupported'（不换身份、不清空、不重试）。
 */
async function resolveTabOwnership(sessionId: string): Promise<TabOwnership> {
  for (let attempt = 1; attempt <= LOCK_PROBE_ATTEMPTS; attempt += 1) {
    const verdict = await probeSessionLock(sessionId)
    if (verdict === 'unsupported') {
      return 'unsupported'
    }
    if (verdict === 'granted') {
      return 'owner'
    }
    if (attempt < LOCK_PROBE_ATTEMPTS) {
      await sleep(LOCK_PROBE_RETRY_MS)
    }
  }
  return 'duplicate'
}

/**
 * 页签生命周期（RC-4 ③）：`pagehide` 广播**让渡意向**，`pageshow` 重新探活。只绑一次。
 *
 * <p>`pagehide` 不再同步释放锁 —— 刷新场景里新的文档要能立刻重新拿到同一把锁（浏览器在文档
 * 销毁时才释放）。宽限到期仍未销毁的文档自己做一次清理，避免长期占着"已让渡"的锁。
 *
 * <p>`pagehide.persisted === true`（进 bfcache）连意向都不发：文档还活着，锁与会话都在，
 * 广播只会让空白副本接手一个还会回来的页签。
 */
function bindOwnershipLifecycle(): void {
  if (ownershipLifecycleBound) {
    return
  }
  ownershipLifecycleBound = true
  window.addEventListener('pagehide', (event) => {
    if (ownedSessionId === null) {
      return
    }
    ownershipHidden = true
    if ((event as PageTransitionEvent).persisted === true) {
      return
    }
    const sid = ownedSessionId
    postOwnership('released', sid)
    rememberReturnIntent(sid)
    // 卸载路径上这个计时器不会跑（文档先销毁，锁由浏览器释放）；跑得到说明文档还活着 ⇒ 释放不占着
    setTimeout(() => {
      if (ownedSessionId === sid) {
        releaseSessionLock()
      }
    }, OWNERSHIP_RELEASE_GRACE_MS)
  })
  window.addEventListener('pageshow', () => {
    if (!ownershipHidden) {
      return
    }
    ownershipHidden = false
    void tabStore?.revalidateTabOwnership()
  })
}

/** R-166 口径⑤：清掉本页签全部镜像引用（键前缀口径，不依赖当前 sessionId）。 */
function clearMirrorRefs(): void {
  try {
    const doomed: string[] = []
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i)
      if (key && key.startsWith(MIRROR_PREFIX)) {
        doomed.push(key)
      }
    }
    doomed.forEach((key) => sessionStorage.removeItem(key))
  } catch {
    // 隐私模式/配额：清不掉不影响"本页签用新 id"的口径
  }
}

/**
 * 复制页签（用户批准口径）：对本页签**从原页签继承**来的整份 sessionStorage 执行**一次** `clear()`。
 *
 * <p>只在这一条路径上允许整份清空 —— 调用点必须已经由 {@link resolveTabOwnership} 用 Web Locks
 * **证实**"该会话锁被另一个文档持有"（每一次 `ifAvailable` 探活都真实收到 `null` 回调；
 * 探活 API 失败只算 'unsupported'，不构成复制的证据），且不是带着返回意向回来的原页签。清空发生在
 * `adoptFreshSession()` / `rotatePageId()` **之前**：先抹掉继承现场（会话镜像、未提交表单草稿、
 * 业务页签键），再写回全新的 sessionId 与 pageId；顺序反了会把刚建的新身份一起抹掉。
 *
 * <p>边界：**只清本页签**（sessionStorage 按页签隔离），`localStorage` 一个字不碰；原页签的锁、
 * 广播与服务端会话都不受影响。`clear()` 不可用（隐私模式/被禁用）时退回镜像前缀清理 ——
 * 宁可残留，也不抛错打断"本页签用新 id"。
 */
function clearDuplicatedTabStorage(identity: AiIdentity | null): void {
  try {
    sessionStorage.clear()
  } catch {
    clearMirrorRefs()
  }
  // Task 4 返修 C1：清空的是"从原页签继承来的现场"，**不包括**本页签当前选择的身份。
  // 身份的内存缓存是权威，这里必须立即回写 storage —— 否则该页签此后所有 AI 请求漏身份头、
  // 镜像/草稿/锁键形退化为无身份口径（且跨页签认领消息发出时也不带指纹）。
  if (identity !== null) {
    writeAiIdentity(identity)
  }
}

/**
 * 取页签会话 id（只读，不生成）。
 *
 * <p>Task 4：**选定身份后**只读该身份的会话键，缺失即返回 ''（等 `ensureSession()` 向后端发号）。
 * 前端不存在任何"本地造会话号"的分支 —— 无身份时也返回 ''；「空 id 不发请求」是引导门的第一条。
 *
 * RC-4：**不再**用导航类型判"新页签 / 复制页签"（真机复制页签 = `back_forward`，判据作废）——
 * 存量 id 一律先按**恢复**取用，是否复制品由 {@link resolveTabOwnership} 在面板挂载后用
 * Web Locks 判定，判为复制品再就地换新 id（见 store 的 `ensureTabOwnership`）。
 */
function initSessionId(): string {
  const identity = currentAiIdentity()
  if (!identity) {
    // 未选身份就没有"本页签会话"可恢复；等目录落定后由 `ensureSession()` 向后端发号。
    return ''
  }
  return storedSessionPointer(identity)
}

/** 该身份当前保存的会话指针（切换身份时保留；空 = 没有可恢复会话）。 */
function storedSessionPointer(identity: AiIdentity): string {
  try {
    return normalizeSessionId(sessionStorage.getItem(sessionIdStorageKey(identity)) ?? '')
  } catch {
    return ''
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
    const raw = sessionStorage.getItem(mirrorStorageKey(sessionId))
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

/**
 * 批 D · D3（问题 6）：**发起作业类工具 → 页面能力名**。
 *
 * 这三个工具成功后作业在后端已经跑起来了，但向导页的作业面板没有任何人告诉它"该跟哪个作业"
 * —— 用户切过去看到的是一张空面板。映射到页面自己注册的能力（页面侧 = 既有 `resumeJobs()`），
 * 与 R-155 的发布冲突跟踪同体例：AI 侧只给"任务号 + 作业号"，不 import 任何视图。
 *
 * 批次 5B 补 `start_publish`：发布作业同样落在导入向导的作业面板（`import.syncJob` 按 jobType
 * 分派 check / import / publish 三位），此前只跟冲突明细、不进页面跟踪面 —— 页面看不到"正在发布"。
 */
const START_JOB_SYNC: Readonly<Record<string, string>> = {
  start_export: 'export.syncJob',
  start_precheck: 'import.syncJob',
  start_import: 'import.syncJob',
  start_publish: 'import.syncJob'
}

/**
 * 页面同步 handler 的**失败**结局（批次 5B：失败回执必须可见，不得静默）。
 *
 * 页面 handler 的约定形态：成功回说明字符串 / 不返回值；失败回结构化
 * `{ ok: false, reason }`。这里只认结构化失败 —— 字符串一律视为成功（与
 * `utils/frontend-tools.ts#interpretHandlerOutcome` 的兼容解读同口径）。
 *
 * @returns `{ reason }`（`reason === null` = 页面没给原因）；`null` = 成功
 */
function startJobSyncFailure(outcome: unknown): { reason: string | null } | null {
  if (outcome === null || typeof outcome !== 'object' || !('ok' in outcome)) {
    return null
  }
  const structured = outcome as { ok: unknown; reason?: unknown }
  if (structured.ok !== false) {
    return null
  }
  return {
    reason: typeof structured.reason === 'string' && structured.reason !== '' ? structured.reason : null
  }
}

/**
 * 同一助手消息内 `frontend_tool_request` 的**串行队列**（Task 8 / 批次 5B）。
 *
 * 键 = 助手消息 id（一轮一条助手消息），值 = 该消息已排队动作的**队尾** promise。
 */
const frontendToolChains = new Map<string, Promise<void>>()

/**
 * 把一次前端工具执行排到该消息队尾（严格串行）。
 *
 * <h2>为什么必须串行</h2>
 * 一帧 `frontend_tool_request` 只有一条工具，但同一轮里模型可以连续下发多条
 * （`navigate_to` 后紧跟 `select_definitions`）。此前每条都 `void runFrontendTool(...)`
 * 并发开跑：后一条会在前一条改变界面**之前**读现场（目标页还没挂载 / 任务还没绑定），
 * 于是模型拿到"页面不支持该动作"这种**假失败**，并据此放弃一条本来可行的工作区操作。
 *
 * <h2>边界</h2>
 * - 串行范围只到"回灌 POST 发出"（`runFrontendTool` 的返回点），不再往后拖整轮收尾；
 * - 队尾一次失败不阻塞后续（`runFrontendTool` 自带结局收敛；这里再兜一层 catch，
 *   保证队列不会因为一次异常永久卡死）；
 * - **不跨消息**：不同助手消息属于不同轮次，串起来只会让两条并行回复互相拖住。
 * - 等人类工具（生成式表单 / 上传交接 / 表格写预览）**不排队**：它们在帧分支里就建卡返回，
 *   等的是人而不是执行器，排进队列会把后续动作一起挂住。
 * - **入队即捕获轮次现场**（B2）：排队期间被停止 / 换会话 / 换轮后，轮到它时不执行任何动作
 *   （不写段、不动浏览器、不回灌）—— 停止必须对**排队中**的动作也立即生效，而不是等它们在
 *   旧纪元里跑完。
 */
function enqueueFrontendTool(
  messageId: string,
  stillCurrent: () => boolean,
  run: () => Promise<void>
): void {
  const previous = frontendToolChains.get(messageId) ?? Promise.resolve()
  const settled = previous
    .then(() => (stillCurrent() ? run() : undefined))
    .catch(() => undefined)
  frontendToolChains.set(messageId, settled)
  void settled.then(() => {
    if (frontendToolChains.get(messageId) === settled) {
      frontendToolChains.delete(messageId)
    }
  })
}

/**
 * 换轮 / 换会话时丢弃排队链表（B2）。
 *
 * <p>只清 map 是不够的：已排队的 promise 已经挂在前一条的 then 上，清掉 map 不会让它们停下 ——
 * 真正的"停"由每条队列项自带的轮次现场判据（`enqueueFrontendTool` 的 `stillCurrent`）执行。
 * 这里清 map 的作用是**不把新会话的新动作排到旧链尾**（否则新动作要等一条可能永远不返回的
 * 旧执行器），并让旧链的引用随会话一起被回收。
 */
function clearFrontendToolChains(): void {
  frontendToolChains.clear()
}

/* ============================ 批次7B（P1⑤）：镜像增量 / 有界 / idle 落盘 ============================
 * 旧实现每次节流窗口都把**整个会话**重新 deep `JSON.stringify`（O(会话)），长会话在流式每帧
 * 稳定做一次全量序列化；配额/隐私模式失败又被 catch 静默吞掉（用户不知道刷新恢复已失效）。
 *
 * 本批口径：
 * - **增量**：按消息对象 WeakMap 缓存已序列化 JSON（guard = 消息字段/段字段浅比较），
 *   未变化的历史消息不再重复 stringify；只有变化的那条重算；
 * - **浅比较前提（复核结论）**：`toolRuns` / `pendingCall` / `errorCard` / 段内 `resultView` /
 *   `preflight` / `submittedValues` 在当前全部写点都是**整体替换**（含 `Object.assign` 只写
 *   截断标量字段），故按引用比较即可；若将来出现原地改嵌套字段，必须先扩展这里的比较，
 *   否则会漏更新；
 * - **有界**：只保最近 {@link MIRROR_MAX_MESSAGES} 条，且消息 JSON 总量不超过
 *   {@link MIRROR_MAX_CHARS}（从最旧开始丢；更早内容只剩服务端 Redis 记忆窗口，不再落库补齐）。最后一条助手 /
 *   最后一条用户 / 最新一条不受字符预算约束（`mirrorBoundedMessages`），保证在途轮
 *   「提问 + 半截回答」不被拆散；
 * - **不重复写**：现场（消息 + lastRunId + inFlight + runSeq）与上次落盘一致时**整次 no-op**，
 *   `savedAt` 不前进（与既有"无改动不回写"的幂等断言同口径）；
 * - **配额失败一次性可见**：写失败落一次通知（同一故障段不刷屏，成功写盘后再次失败可重新提示）。
 * 消息字段映射（含 errorCard/code/runId/思考 timing 剔除本地锚）与旧镜像逐字一致，读侧零改动。
 * 序列化失败（脏段 / 循环引用）与写盘失败分开口径：前者只吞异常不外溢，不冒充配额提示。
 * ================================================================================================ */

/** 镜像有界：最多保留的最近消息条数（更早的只剩服务端 Redis 记忆窗口，不再落库补齐）。 */
export const MIRROR_MAX_MESSAGES = 120
/** 镜像有界：消息 JSON 总量预算（UTF-16 字符，与 sessionStorage 配额同单位）。 */
export const MIRROR_MAX_CHARS = 512 * 1024
/** 写盘失败（配额已满 / 隐私模式）的通知码；同一故障段只提示一次。 */
const MIRROR_STORAGE_NOTICE = 'ai.notice.mirrorQuotaExceeded'

type MirrorWriteOutcome = 'ok' | 'unchanged' | 'error'

type MirroredMessage = AiSessionMirror['messages'][number]

interface MirrorMessageSnapshot {
  scalars: unknown[]
  parts: Array<{ part: MessagePart; fields: Array<[string, unknown]> }>
}

/** 与 `AiSessionMirror.messages` 的 Pick 键一一对应（顺序只影响快照比较，不影响镜像 JSON）。 */
const MIRROR_MESSAGE_SCALAR_KEYS = [
  'id',
  'role',
  'content',
  'reasoning',
  'toolRuns',
  'pendingCall',
  'stopped',
  'truncated',
  'errorCard',
  'code',
  'unconfirmed',
  'recoveryGap',
  'synthetic',
  'runId'
] as const

/** 消息 → 上次序列化时的快照与 JSON（WeakMap：消息被替换/回收即释放）。 */
const mirrorMessageCache = new WeakMap<ChatMessage, { snapshot: MirrorMessageSnapshot; json: string }>()

function mirrorPartFields(part: MessagePart): Array<[string, unknown]> {
  const fields: Array<[string, unknown]> = []
  for (const key of Object.keys(part)) {
    // 运行时锚不入镜像（AR-12），也不参与变更判定 —— 否则每个思考帧都会触发重算。
    if (key === 'lastFrameLocalAt') {
      continue
    }
    fields.push([key, (part as unknown as Record<string, unknown>)[key]])
  }
  return fields
}

function mirrorSnapshotOf(message: ChatMessage): MirrorMessageSnapshot {
  return {
    scalars: MIRROR_MESSAGE_SCALAR_KEYS.map((key) => message[key]),
    parts: message.parts.map((part) => ({ part, fields: mirrorPartFields(part) }))
  }
}

/** 段字段浅比较（值按 `===`；正文/args 都是不可变字符串，同引用时 O(1)）。 */
function sameMirrorPartFields(previous: Array<[string, unknown]>, part: MessagePart): boolean {
  const comparable = Object.keys(part).filter((key) => key !== 'lastFrameLocalAt')
  if (comparable.length !== previous.length) {
    return false
  }
  for (let index = 0; index < comparable.length; index += 1) {
    const key = comparable[index] as string
    const prev = previous[index]
    if (prev === undefined || prev[0] !== key || prev[1] !== (part as unknown as Record<string, unknown>)[key]) {
      return false
    }
  }
  return true
}

function sameMirrorSnapshot(snapshot: MirrorMessageSnapshot, message: ChatMessage): boolean {
  for (let index = 0; index < MIRROR_MESSAGE_SCALAR_KEYS.length; index += 1) {
    if (snapshot.scalars[index] !== message[MIRROR_MESSAGE_SCALAR_KEYS[index]]) {
      return false
    }
  }
  if (snapshot.parts.length !== message.parts.length) {
    return false
  }
  for (let index = 0; index < message.parts.length; index += 1) {
    const previous = snapshot.parts[index]
    const part = message.parts[index]
    if (previous === undefined || part === undefined || previous.part !== part) {
      return false
    }
    if (!sameMirrorPartFields(previous.fields, part)) {
      return false
    }
  }
  return true
}

/** 单条消息 → 镜像形态（字段与批次2 逐字一致；新增字段时只加这里 + `MIRROR_MESSAGE_SCALAR_KEYS`）。 */
function mirrorMessageOf(message: ChatMessage): MirroredMessage {
  return {
    id: message.id,
    role: message.role,
    content: message.content,
    parts: stripReasoningLocalAnchors(message.parts),
    reasoning: message.reasoning,
    toolRuns: message.toolRuns,
    pendingCall: message.pendingCall,
    ...(message.stopped ? { stopped: true } : {}),
    ...(message.truncated ? { truncated: true } : {}),
    // 批次2（镜像 additive 键）：失败现场与中性态活过刷新 —— 否则"失败"会被归一成一条完整回答。
    ...(message.errorCard ? { errorCard: message.errorCard } : {}),
    ...(message.code ? { code: message.code } : {}),
    ...(message.unconfirmed ? { unconfirmed: true } : {}),
    ...(message.recoveryGap ? { recoveryGap: true } : {}),
    // 批次2：系统级合成提示（压缩摘要）也必须活过刷新，不能回退成用户气泡
    ...(message.synthetic ? { synthetic: true } : {}),
    // 批次2：run 归属必须活过刷新 —— 「重试本轮」只按它定位后端 run（不得猜 activeRunId）
    ...(message.runId ? { runId: message.runId } : {})
  }
}

function mirrorMessageJsonOf(message: ChatMessage): string {
  const cached = mirrorMessageCache.get(message)
  if (cached !== undefined && sameMirrorSnapshot(cached.snapshot, message)) {
    return cached.json
  }
  const json = JSON.stringify(mirrorMessageOf(message))
  mirrorMessageCache.set(message, { snapshot: mirrorSnapshotOf(message), json })
  return json
}

/**
 * 从最新往回取，直到条数/字符预算用尽。
 *
 * 两条**永不按字符预算裁剪**：①最新一条（丢它=丢本轮现场）；②最后一条助手消息与最后一条
 * 用户消息（在途轮的「提问 + 半截回答」必须成对）—— 否则超大回答会把配对提问挤掉，
 * 刷新后只剩孤儿回答，`mergeMirrorInFlightRound` / `carryMirrorOutcomeMarkers` 的同句配对
 * 判据也会失配。条数上限（{@link MIRROR_MAX_MESSAGES}）天然覆盖这三条（一轮至多两三条）。
 */
function mirrorBoundedMessages(messages: ChatMessage[]): string[] {
  let lastUserIndex = -1
  let lastAssistantIndex = -1
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const role = messages[index]?.role
    if (lastAssistantIndex < 0 && role === 'assistant') {
      lastAssistantIndex = index
    }
    if (lastUserIndex < 0 && role === 'user') {
      lastUserIndex = index
    }
    if (lastUserIndex >= 0 && lastAssistantIndex >= 0) {
      break
    }
  }
  const json: string[] = []
  let chars = 0
  for (let index = messages.length - 1; index >= 0 && json.length < MIRROR_MAX_MESSAGES; index -= 1) {
    const message = messages[index]
    if (message === undefined) {
      continue
    }
    const serialized = mirrorMessageJsonOf(message)
    // 最新一条 / 最后一条助手 / 最后一条用户：不受字符预算约束（条数上限仍生效）。
    const preserved = index === messages.length - 1 || index === lastAssistantIndex || index === lastUserIndex
    if (!preserved && json.length > 0 && chars + serialized.length > MIRROR_MAX_CHARS) {
      break
    }
    json.push(serialized)
    chars += serialized.length
  }
  return json.reverse()
}

interface MirrorWriteMemo {
  sessionId: string | null
  messageCount: number
  messages: string[]
  lastRunId: string | null
  inFlight: boolean
  runSeq: string
}

/** 每个 store 实例一份"上次落盘现场"（WeakMap：新 store / 换会话自然失效）。 */
const mirrorWriteMemos = new WeakMap<object, MirrorWriteMemo>()

function mirrorMemoOf(owner: object): MirrorWriteMemo {
  let memo = mirrorWriteMemos.get(owner)
  if (memo === undefined) {
    memo = { sessionId: null, messageCount: 0, messages: [], lastRunId: null, inFlight: false, runSeq: 'null' }
    mirrorWriteMemos.set(owner, memo)
  }
  return memo
}

/** 同一故障段是否已提示过（按 store 实例 + 会话记账；成功写盘后清除）。 */
const mirrorFailureNotified = new WeakMap<object, string>()

function noteMirrorFailure(sink: object & NoticeSink, sessionId: string): void {
  if (mirrorFailureNotified.get(sink) === sessionId) {
    return
  }
  mirrorFailureNotified.set(sink, sessionId)
  setNotice(sink, MIRROR_STORAGE_NOTICE, {}, 'warning')
}

/**
 * 唯一写口：增量序列化 + 有界裁剪 + 幂等去重 + 失败通知。
 *
 * @returns `unchanged` = 现场与上次一致，未写盘（`savedAt` 不前进）；`error` = 写盘/序列化失败。
 */
function writeMirror(
  sink: object & NoticeSink,
  sessionId: string,
  messages: ChatMessage[],
  lastRunId: string | null,
  extra: { inFlight?: boolean; runSeq?: Record<string, number> } = {}
): MirrorWriteOutcome {
  const inFlight = extra.inFlight === true
  const memo = mirrorMemoOf(sink)
  let messageJsons: string[] = []
  let runSeqJson = 'null'
  let payload = ''
  try {
    runSeqJson = JSON.stringify(extra.runSeq ?? null)
    messageJsons = mirrorBoundedMessages(messages)
    if (
      memo.sessionId === sessionId
      && memo.messageCount === messages.length
      && memo.lastRunId === lastRunId
      && memo.inFlight === inFlight
      && memo.runSeq === runSeqJson
      && memo.messages.length === messageJsons.length
      && memo.messages.every((json, index) => json === messageJsons[index])
    ) {
      return 'unchanged'
    }

    // 信封照旧（v/savedAt/lastRunId/inFlight/runSeq），messages 直接拼**已序列化**的单条 JSON，
    // 不再把整棵镜像对象交给一次全量 stringify。
    const envelope = JSON.stringify({
      v: MIRROR_VERSION,
      savedAt: Date.now(),
      lastRunId,
      inFlight,
      ...(extra.runSeq ? { runSeq: { ...extra.runSeq } } : {})
    })
    payload = `${envelope.slice(0, -1)},"messages":[${messageJsons.join(',')}]}`
  } catch {
    // 序列化异常（脏段 / 循环引用等）：旧实现整体在 try 内、异常不外溢 —— 镜像是刷新体验
    // 增强，不是正确性依赖，绝不能把异常抛进 `$subscribe`。此类失败不冒充配额提示。
    return 'error'
  }
  try {
    sessionStorage.setItem(mirrorStorageKey(sessionId), payload)
    memo.sessionId = sessionId
    memo.messageCount = messages.length
    memo.messages = messageJsons
    memo.lastRunId = lastRunId
    memo.inFlight = inFlight
    memo.runSeq = runSeqJson
    mirrorFailureNotified.delete(sink)
    // 故障恢复后清掉**自己留下的**降级提示（不误清别的通知）。
    if (sink.noticeCode === MIRROR_STORAGE_NOTICE) {
      clearNotice(sink)
    }
    return 'ok'
  } catch {
    // 配额/隐私模式：镜像是刷新体验优化，不是正确性依赖 —— 但失败必须**一次性可见**。
    noteMirrorFailure(sink, sessionId)
    return 'error'
  }
}

/** 批G：一条消息的全部正文（段是唯一真值；`content` 只是同一份数据的派生投影）。 */
function messageText(message: ChatMessage): string {
  return message.parts
    .filter((part): part is Extract<ChatMessage['parts'][number], { kind: 'text' }> => part.kind === 'text')
    .map((part) => part.text)
    .join('')
}

/** 批G：跨镜像/历史搬运消息时的浅拷贝（段与投影各复制一层，避免两处共写同一对象）。 */
function cloneMessage(message: ChatMessage): ChatMessage {
  return {
    ...message,
    parts: message.parts.map((part) => ({ ...part })) as ChatMessage['parts'],
    toolRuns: message.toolRuns.map((run) => ({ ...run })),
    pendingCall: message.pendingCall ? { ...message.pendingCall } : null,
    appliedSeqs: []
  }
}

/**
 * 批次6A 返修：镜像写出时剔除思考段的**本地接收锚**（AR-12：不为计时新增持久化字段）。
 * 锚只在运行时有效；刷新读回时由 `seedReasoningLocalAnchors` 用镜像 `savedAt` 重播。
 */
function stripReasoningLocalAnchors(parts: MessagePart[]): MessagePart[] {
  return parts.map((part) => {
    if (part.kind !== 'reasoning' || part.lastFrameLocalAt === undefined) {
      return part
    }
    const rest = { ...part }
    delete (rest as { lastFrameLocalAt?: number }).lastFrameLocalAt
    return rest as MessagePart
  })
}

/**
 * 批次6A 返修：镜像读回时为思考段重播本地接收锚 = 镜像写入时刻（`savedAt`，本地钟）。
 *
 * <p>为什么用 `savedAt`：它是"最后一帧已包含在这份镜像里"的本地收帧时刻（镜像写盘由帧事件
 * 节流触发），与段的 `lastFrameAt`（服务端钟）成对，正好构成接收锚 —— 刷新/离线的那段
 * 因此计入真实已用时长，且不落任何新持久化字段。已有的运行时锚（同一次页面会话）不覆盖。
 */
function seedReasoningLocalAnchors(messages: ChatMessage[], savedAtMs: unknown): void {
  if (typeof savedAtMs !== 'number' || !Number.isFinite(savedAtMs)) {
    return
  }
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.kind === 'reasoning' && part.lastFrameAt !== undefined && part.lastFrameLocalAt === undefined) {
        part.lastFrameLocalAt = savedAtMs
      }
    }
  }
}

/**
 * 批G · R5/R2（真机回归）：**提问判重的同口径归一化**。
 *
 * <p>
 * 历史侧的用户消息是后端 `InputSanitizer` 的产物 —— NFKC 折叠（全角标点/兼容字符 → 规范形态）
 * 后再落 ChatMemory；镜像侧是用户原文。逐字比原文时两边永远不等（`，` vs `,`），同一句提问就会
 * 在刷新后被补成两条气泡（真机 R2：4 气泡变 5、两条 head 只差标点）。
 *
 * <p>
 * 这里复刻后端的同一把尺：NFKC + 剥离不可见/控制字符（保留换行与制表，同 `stripInvisible`
 * 的例外），再折叠空白。只用于**判定"是不是同一句"**，不参与任何展示 —— 界面照旧显示各自原文。
 */
function normalizeUserText(text: string): string {
  const strip = (value: string): string => value
    // Cf = 格式字符（零宽/BOM/双向控制符）、Cs = 孤立代理项 —— 肉眼不可见，整类剥掉
    .replace(/[\p{Cf}\p{Cs}]/gu, '')
    // Cc = 控制字符，保留换行与制表（与后端 stripInvisible 的例外一字对齐）
    .replace(/\p{Cc}/gu, (char) => (char === '\n' || char === '\t' ? char : ''))
  // 与后端同序：先剥再 NFKC，再收敛一次（剥字符可能让新的组合序列浮现）
  return strip(strip(text).normalize('NFKC')).replace(/\s+/g, ' ').trim()
}

/** 批G：轮次快照状态是否已终态（`/runs/current` 的 `snapshotStatus` 口径）。 */
function isTerminalRunStatus(status: string | null | undefined): boolean {
  const normalized = (status ?? '').toUpperCase()
  return normalized === 'DONE' || normalized === 'FAILED' || normalized === 'CANCELLED' || normalized === 'REJECTED'
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

/**
 * 历史载荷的截断元数据（A10 R-15）：只按<b>实际存在的键</b>透传到段上。
 *
 * <p>两条来源：① `toolResponses[]` —— 后端从持久化信封里取回
 * `truncated/noticeCode/originalChars/returnedChars/strategy/truncatedReason`；② 历史条目自身
 * （additive，文本段可携带同名字段；当前后端仅对 `toolResponses` 产出）。缺键一律不写，保证旧历史
 * 重建后的段形状与既有实现逐字一致（不回退出 `undefined` 键）。
 *
 * <p><b>消息级 vs 段级同名键（截断专项）</b>：只有裸 `truncated` 时按消息级长度截断处理，
 * 走 {@link ChatMessage.truncated} + 面板徽标；若同时有 noticeCode/字符数/策略/旧原因等段级
 * 元数据，则是旧帧的段级截断，必须保留 truncated 及其余字段，刷新后角标不能丢。
 */
function historyTruncationFields(
  source: unknown,
  options: { includeTruncated?: boolean } = {}
): Partial<MessageTruncationFields> {
  if (source === null || typeof source !== 'object') {
    return {}
  }
  const value = source as {
    truncated?: unknown
    noticeCode?: unknown
    originalChars?: unknown
    returnedChars?: unknown
    strategy?: unknown
    truncatedReason?: unknown
  }
  const fields: Partial<MessageTruncationFields> = {}
  if (options.includeTruncated !== false && typeof value.truncated === 'boolean') {
    fields.truncated = value.truncated
  }
  if (value.noticeCode === 'frame_truncated' || value.noticeCode === 'frame_limit_unreached'
    || value.noticeCode === 'model_view_truncated') {
    fields.noticeCode = value.noticeCode as TruncationNoticeCode
  }
  if (typeof value.originalChars === 'number') {
    fields.originalChars = value.originalChars
  }
  if (typeof value.returnedChars === 'number') {
    fields.returnedChars = value.returnedChars
  }
  if (typeof value.strategy === 'string' || value.strategy === null) {
    fields.strategy = value.strategy as string | null
  }
  if (typeof value.truncatedReason === 'string' || value.truncatedReason === null) {
    fields.truncatedReason = value.truncatedReason as string | null
  }
  return fields
}

/**
 * 契约事件载荷 → 工作区动作记录（形态不符则忽略，不抛错）。导出仅为单测锁形状。
 *
 * <p>2026-10-29 收口：`display`（结构化展示面：locale code + 命名插值）必须**随记录保留** ——
 * 面板 chip 用它按当前语言重译，丢了它就只能回落到 `summary` 那份送模型的中文串（切 en 不变）。
 * `source` 仍是两值闭集（`AI` / `界面`）原样保留：只认已知值，超出闭集/缺失才回落到「界面」默认，
 * 不臆造第三个值、也不丢已知值。
 */
export function readActionRecord(payload: unknown): WorkspaceActionRecord | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }
  const record = payload as Partial<WorkspaceActionRecord>
  if (typeof record.summary !== 'string' || typeof record.version !== 'number') {
    return null
  }
  const display = readActionDisplay(record.display)
  return {
    version: record.version,
    summary: record.summary,
    at: typeof record.at === 'number' ? record.at : Date.now(),
    source: record.source === 'AI' || record.source === '界面' ? record.source : '界面',
    ...(display === null ? {} : { display })
  }
}

/**
 * 展示面形状闸：`code` 必须是非空字符串（面板直接 `t(code)`），`args` 只收标量值
 * （字符串 / 数字）—— 对象/数组塞进插值只会渲染成 `[object Object]`，宁可不给。
 * 形状不符 ⇒ null（记录仍可用，只是 chip 回落到 `summary`）。
 */
function readActionDisplay(raw: unknown): WorkspaceActionRecord['display'] | null {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const value = raw as { code?: unknown; args?: unknown }
  if (typeof value.code !== 'string' || value.code === '') {
    return null
  }
  const args: Record<string, string | number> = {}
  if (value.args && typeof value.args === 'object' && !Array.isArray(value.args)) {
    for (const [key, item] of Object.entries(value.args as Record<string, unknown>)) {
      if (typeof item === 'string' || typeof item === 'number') {
        args[key] = item
      }
    }
  }
  return Object.keys(args).length > 0 ? { code: value.code, args } : { code: value.code }
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
 * 契约增量（skipped）：`SKIPPED` 显式登记为 null —— 它不经本表的翻译语义（skipped 段只由
 * tool_result 早分支落；409 里见到 SKIPPED 时该段已终态、无待收敛投影），
 * 两表同返回 null ⇒ 不构成新分叉，见 case 内注。
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
    case 'SKIPPED':
      // 契约增量（SKIPPED 一等状态）**不经本表**：SKIPPED 只由后端批次策略
      // （SpToolCallingManager#skipAsUpstreamFailed）经 **tool_result** 帧发出，段由 parts.ts 的
      // tool_result 早分支直接落 skipped（requiresAction=false ⇒ 无挂起投影）。
      // 本表只翻译 `frontend_tool_result` 帧与 409 响应体的 status：前者由 GF 提交/取消路径铸帧
      //（FRONTEND_RESULT / FRONTEND_CANCELLED），不含 SKIPPED；后者的 SKIPPED 只可能出现在
      // "该条目已被批策略跳过、又被重复回灌"的 409 里 —— 此时段已是 skipped 终态、无待收敛投影。
      // `PendingToolCall['status']` 也没有 skipped 成员（挂起卡态没有"已跳过"）。
      // 故显式返回 null：不收敛挂起投影、不误落成 pending/failed，且与 parts.ts#frontendResultStatus
      // 同映射（两表不产生新分叉）。
      return null
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
 * 批3③b D9（R-141④）防漂移注记：本枚举（8 态：succeeded/rejected/blocked/expired/cancelled/
 * unknown/failed/skipped）与段侧 `localTerminal`（parts.ts 的 `frontend_tool_result` case 内）**同 8 态、无共享常量**
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
    || status === 'unknown' || status === 'failed' || status === 'skipped'
}

/**
 * M-2b（2026-10-22 用户直裁「无感恢复」）：快照条目是否可重建为挂起卡。
 *
 * - `PENDING` + `kind === 'CONFIRM'`，或确实需要用户操作的 FRONTEND 条目；
 * - `req !== false`：`true` = 请求帧已发；**缺键 = 升级前遗留的孤儿轮**（无法判定）——
 *   按可建处理，否则刷新后整轮从界面消失、输入框永久禁用。显式 `false`（同批请求帧
 *   还没轮到）保持 L0 不建卡，等实时帧到达（活轮行为不变）。
 * 自动前端工具不走挂起卡恢复：它们的后续请求帧仍由续收路径处理，不伪造等待确认。
 */
function isRestorablePendingEntry(entry: AiRunCurrentEntry): boolean {
  if (entry.entryStatus !== 'PENDING' || entry.req === false) {
    return false
  }
  if (entry.kind === 'CONFIRM') {
    return true
  }
  if (entry.kind !== 'FRONTEND') {
    return false
  }
  return entry.name === GENERATIVE_FORM_TOOL
    || UI_HANDOFF_TOOLS.has(entry.name)
    || needsSheetPreview(entry.name, parseToolArgs(entry.arguments))
}

/**
 * AI 可视可操作专项：上传入口的定位（显式 ref 优先，否则取区域声明的 `uploadEntry`）。
 *
 * 只在**已注册区域**里找：区域未注册 = 该页没有上传入口（不可见即不可操作）。
 */
function resolveUploadRegion(refArg: string | null): { region: AiRegion; entry: string } | null {
  if (refArg) {
    const hit = resolveAiRef(refArg)
    if (hit) {
      return { region: hit.region, entry: refArg }
    }
  }
  for (const region of listAiRegions()) {
    if (region.uploadEntry) {
      return { region, entry: region.uploadEntry }
    }
  }
  return null
}

/**
 * 上传交接的两种"交不出去"人话原因（**模型侧单语串，按 C5 红线留源码**）。
 *
 * 三处共用同一份字面量（建卡闸 / 卡面已 missing 时的 choose / 选文件期间区域消失），
 * 避免同一句话在三处漂移；`check-i18n-residue` 按文件计行，提取成常量不增残留。
 */
const UPLOAD_NO_REGION_TEXT = '当前页面没有可用的上传入口（请先打开导入向导的第 1 步），本次未执行上传。'
const UPLOAD_READONLY_TEXT = '任务已结束，仅供查看；上传入口在只读页面不可用，本次未执行上传。'

/** 高亮上传入口（失败不影响交接主流程：高亮是"引导"，不是前提）。 */
function highlightUploadEntry(ref: string): void {
  try {
    aiHighlight({ ref, epoch: currentAiEpoch() })
  } catch {
    // 高亮失败不改变交接三态（用户仍能在对话里点「选择文件」）
  }
}

function pickUiAnnouncement(): string | null {
  return peekUiAnnouncement()
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** 通知槽的写入面（store 的 `this` 只暴露这四个字段时即可调用）。 */
interface NoticeSink {
  notice: string | null
  noticeCode: string | null
  noticeArgs: Record<string, string | number> | null
  noticeSeverity: NoticeSeverity
}

/**
 * 本地模板通知的默认级别（AR-13）：在**写入时**定级并随通知保存。
 * 未列出的模板退化为 `info`；不要在这里读 `lastRoundOutcome`。
 */
const NOTICE_SEVERITY: Readonly<Record<string, NoticeSeverity>> = {
  'ai.notice.sendFailed': 'error',
  'ai.notice.chatRequestFailed': 'error',
  'ai.notice.turnError': 'error',
  'ai.notice.poolSaturated': 'error',
  'ai.notice.poolSaturatedWithSize': 'error',
  'ai.notice.aiDisabled': 'error',
  'ai.notice.keyMissing': 'error',
  'ai.notice.keyUnreadable': 'error',
  'ai.notice.noModelConfigured': 'error',
  'ai.notice.redisUnavailable': 'error',
  'ai.notice.missingRunId': 'error',
  'ai.notice.decisionFailed': 'error',
  'ai.notice.toolSubmitFailed': 'error',
  'ai.notice.formRejected': 'error',
  'ai.notice.formSubmitFailed': 'error',
  'ai.notice.formSubmitFailedPlain': 'error',
  'ai.notice.formCancelFailed': 'error',
  'ai.notice.uploadReceiveFailed': 'error',
  'ai.notice.networkError': 'error',
  'ai.notice.interruptedSuspended': 'warning',
  'ai.notice.interruptedKeep': 'warning',
  'ai.notice.interruptedKeepShort': 'warning',
  'ai.notice.healthProbeFailed': 'warning',
  'ai.notice.sessionPurged': 'warning',
  'ai.notice.sessionPurgeIncomplete': 'warning',
  'ai.notice.memoryDeleteFailed': 'warning',
  'ai.notice.decisionPending': 'warning',
  'ai.notice.runExpired': 'warning',
  'ai.notice.decisionSubmitting': 'warning',
  'ai.notice.decisionIdempotent': 'warning',
  'ai.notice.ackRequired': 'warning',
  'ai.notice.staleConfirmCard': 'warning',
  'ai.notice.sheetLargePlan': 'warning',
  'ai.notice.formSubmittedElsewhere': 'warning',
  'ai.notice.formResultTooLarge': 'warning',
  'ai.notice.formResultTooLargeRejected': 'warning',
  'ai.notice.uploadNoRegion': 'warning',
  'ai.notice.uploadPickerUnsupported': 'warning',
  'ai.notice.uploadPickerCancelled': 'warning',
  'ai.notice.uploadPickerFailed': 'warning',
  'ai.notice.uploadNoFile': 'warning',
  'ai.notice.noPendingDecision': 'warning',
  'ai.notice.toolTerminal': 'warning',
  'ai.notice.toolAlreadySubmitted': 'warning',
  'ai.notice.noUiUndo': 'warning',
  // 复制页签告知是**中性事实**（不是错误/降级）：级别必须自带，不能继承上一轮 error。
  'ai.notice.copiedNewTab': 'info',
  // 批次7B：镜像写盘失败（配额已满 / 隐私模式）是刷新恢复的降级事实，按 warning 提示一次。
  [MIRROR_STORAGE_NOTICE]: 'warning'
}

/** 陈旧确认卡：码 → 人话 key。只收"后端已无该条目"的码（见 {@link GateStaleCode}）。 */
const STALE_GATE_NOTICE: Readonly<Partial<Record<GateStaleCode, string>>> = {
  UNKNOWN_TOOL_CALL: 'ai.notice.staleConfirmCard',
  UNKNOWN_RUN: 'ai.notice.staleConfirmCard'
}

/** 码在陈旧表里 ⇒ 返回人话 key；否则 null（仍按可重试失败处理）。 */
function staleGateNoticeKey(code: string): string | null {
  return STALE_GATE_NOTICE[code as GateStaleCode] ?? null
}

function noticeSeverityOf(code: string): NoticeSeverity {
  return NOTICE_SEVERITY[code] ?? 'info'
}

/**
 * 写一条**本地模板**通知：`code` = locale key（`ai.notice.*`），`args` = 命名插值。
 *
 * <p>
 * 同时落一份**设定时刻**的译文到 `notice` —— 既有消费方（单槽字符串断言 / 面板以外的读点）
 * 不必改；结构化形态才是渲染与切语言的依据（见 store 的 `noticeText` getter）。
 * 级别随本次写入落 `noticeSeverity`，调用方可用第四参覆盖默认表。
 */
function setNotice(
  sink: NoticeSink,
  code: string,
  args: Record<string, string | number> = {},
  severity: NoticeSeverity = noticeSeverityOf(code)
): void {
  sink.noticeCode = code
  sink.noticeArgs = args
  sink.noticeSeverity = severity
  sink.notice = translate(code, args)
}

/**
 * 写一条**原文**通知：后端 `message`（中文兜底，设计稿 §3.5）与测试注入走这里。
 *
 * 不翻译、不带 code —— 原文是数据不是文案（C5 同口径）。级别默认 info，调用方可按现场覆盖。
 */
function setRawNotice(sink: NoticeSink, text: string, severity: NoticeSeverity = 'info'): void {
  sink.noticeCode = null
  sink.noticeArgs = null
  sink.noticeSeverity = severity
  sink.notice = text
}

/** 清空通知槽（code / args / 级别一并清，否则 getter 会把旧模板重新翻出来）。 */
function clearNotice(sink: NoticeSink): void {
  sink.noticeCode = null
  sink.noticeArgs = null
  sink.noticeSeverity = 'info'
  sink.notice = null
}

/**
 * 精确清理旧会话的浏览器残留（服务端 `clientCleanup` 的唯一消费点）。
 *
 * <p>只删两处：<b>该会话</b>的镜像键 `ai-mirror:<sid>`（响应值必须与期望键一致，否则回落期望键，
 * 防止服务端字段异常删到别的键）与<b>枚举到的</b> `draftToolCallIds` 对应草稿键（由
 * `clearFormDraft` 固定拼 `cm.ai.form.draft.<id>`）。本函数**不做**前缀扫描、更不
 * `sessionStorage.clear()`：同一页签可能已换到新会话，业务状态也在同一 sessionStorage 里。
 * 整份清空只有复制页签确认那一条路径（{@link clearDuplicatedTabStorage}）；换会话留下的孤儿
 * 草稿键由 {@link useAiStore.pruneOrphanFormDrafts} 按前缀 + 在场白名单清（同样不是 clear）。
 *
 * <p>响应缺字段（旧后端/旧版本）时仍清该会话镜像；草稿只在有 id 时清（当前活动表单已由
 * `adoptFreshSession` 的 `dropActiveForm` 清掉，孤儿键也已由同处的 prune 收口）。
 */
function cleanupSessionLocalArtifacts(
  sessionId: string,
  payload: SessionPurgeResponse | null,
  identity: AiIdentity | null = currentAiIdentity()
): void {
  const expectedMirror = mirrorStorageKey(sessionId, identity)
  const cleanup = payload?.clientCleanup
  const ownMirrorKey = (key: string | undefined): key is string =>
    key === expectedMirror || key === `${MIRROR_PREFIX}${sessionId}`
  const mirrorKey = ownMirrorKey(cleanup?.mirrorKey) ? cleanup.mirrorKey : expectedMirror
  try {
    // 两个口径都清：本前端的身份键 + 后端 clientCleanup 给出的键（旧后端仍是 `ai-mirror:<sid>`，
    // 新契约应是 `ai-mirror:<t>:<u>:<sid>`）—— 都是"本会话自己的镜像"，不会误删别的身份。
    sessionStorage.removeItem(expectedMirror)
    if (mirrorKey !== expectedMirror) {
      sessionStorage.removeItem(mirrorKey)
    }
  } catch {
    // 隐私模式/存储被禁用：清不掉不影响主流程（新会话已换 id）
  }
  // 草稿键按**当前身份**拼；身份在销毁回包前已切换 ⇒ 一个都不清（那是新身份的草稿）。
  if (!isSameIdentity(identity, currentAiIdentity())) {
    return
  }
  const draftIds = Array.isArray(cleanup?.draftToolCallIds) ? cleanup.draftToolCallIds : []
  // clientCleanup 的新键形由后端给出（`cm.ai.form.draft.<tenant>:<user>.`）；前端按契约同时
  // 认自己的身份前缀与后端给的 draftPrefix（两者一致时就是同一个键），多清一次也不会误删 ——
  // 两个键都带同一身份指纹，且 id 来自本会话的清理清单。
  const serverPrefix = typeof cleanup?.draftPrefix === 'string' && cleanup.draftPrefix.startsWith(FORM_DRAFT_PREFIX)
    ? cleanup.draftPrefix
    : null
  for (const toolCallId of draftIds) {
    if (typeof toolCallId === 'string' && toolCallId.length > 0) {
      clearFormDraft(toolCallId)
      if (serverPrefix !== null) {
        try {
          sessionStorage.removeItem(`${serverPrefix}${toolCallId}`)
        } catch {
          // 隐私模式/存储被禁用：忽略
        }
      }
    }
  }
}

/** `complete=false` 的三种形态（含字段缺失兼容：旧后端没有 complete 时按完成处理）。 */
function purgeWasIncomplete(payload: SessionPurgeResponse | null): boolean {
  if (!payload) {
    return false
  }
  const deferred = Array.isArray(payload.deferred) ? payload.deferred : []
  const errors = Array.isArray(payload.errors) ? payload.errors : []
  return payload.complete === false || payload.aborted === true || deferred.length > 0 || errors.length > 0
}

export const useAiStore = defineStore('ai', {
  state: () => ({
    /** 页签级会话 id（记忆窗口 CONVERSATION_ID；Task 4 起由后端发号，'' = 尚未发号） */
    sessionId: initSessionId(),
    /** 当前页签选中的租户/用户（null = 尚未接入目录，退回旧单用户口径）。 */
    identity: currentAiIdentity() as AiIdentity | null,
    /** 当前会话归属的身份（换身份/换会话时更新；镜像写入据此拒绝跨身份落盘）。 */
    sessionIdentity: currentAiIdentity() as AiIdentity | null,
    /** `GET /api/ai/tenants` 的目录（少量预置租户，不做循环处理）。 */
    tenantDirectory: [] as TenantUsersEntry[],
    /** 目录加载态：`unavailable` = 身份功能不可用（面板横幅 + 发送禁用 + 可重试），不得静默降级。 */
    directoryStatus: 'idle' as 'idle' | 'loading' | 'ready' | 'unavailable',
    /** 目录加载失败（面板提示）。 */
    directoryError: null as string | null,
    /**
     * 发号失败（返修 round3）：只在**确实失败**时置位，横幅据此显示；发号中只是
     * `sessionReady=false`，不再闪红条。成功后立即清除。
     */
    sessionIssueFailed: false,
    /**
     * 本页签的会话所有权角色（RC-4）：'unknown' = 尚未判定；'owner' = 本页签持有会话锁；
     * 'duplicate' = 由复制产生、已就地换新会话且**尚未**接手原会话（让渡判据）；
     * 'unsupported' = 环境无 Web Locks，按恢复处理（终态）。
     */
    tabOwnership: 'unknown' as TabOwnership,
    /** AI 面板是否展开（裁剪性：收起后业务照常；页签级记忆，刷新后保持用户的选择） */
    expanded: initExpanded(),
    messages: [] as ChatMessage[],
    /** 是否有轮次在进行 */
    loading: false,
    /** 当前轮 runId（来自 start 帧） */
    activeRunId: null as string | null,
    /**
     * 批次3 补齐 / AR-10：本轮运行态（Redis 24h 滑动保留期）的**只读**寿命锚点。
     *
     * <p>只在 `GET /api/ai/runs/current` 明确给出 `snapshotRemainingMs` 时落值（服务端
     * `PTTL(ai:run:<runId>)` 的权威推导）。前端不拿 24h 常量另算一套：卡面只按
     * "收到时刻 + 剩余毫秒"递减，到期后转「已失效 + 可达出口」。
     * `null` = 未知（旧后端 / 键刚过期 / 无锚点）——此时不显示时间，也不猜测。
     */
    runExpiry: null as RunExpiryAnchor | null,
    /** 是否处于挂起等待（suspended / confirm_request 帧口径，N6 裁决） */
    suspended: false,
    /** 连接/降级提示（断流、重连、池饱和、无 key 等） */
    notice: null as string | null,
    /**
     * 通知槽的结构化形态（i18n L-69）：`code` = locale key、`args` = 命名插值。
     * 只有经过 {@link setNotice} 写入的通知才有（本地模板），后端 `message` 原文与测试注入走
     * `notice` 裸串、这两个字段为 null。渲染层以 code 为准重译（切语言即变），`notice` 只是**设定时**
     * 的语言快照（既有断言与「单槽纯字符串」消费方仍可读）。
     */
    noticeCode: null as string | null,
    noticeArgs: null as Record<string, string | number> | null,
    /**
     * 通知槽级别（AR-13）：与 `notice` 同时写入、同时清空。
     * 渲染层只认这个字段，**不**读 `lastRoundOutcome` —— 后者是轮次状态，不是通知属性。
     */
    noticeSeverity: 'info' as NoticeSeverity,
    /**
     * 批G · S2：停止请求在途（按钮「停止中」disabled；`stop()` 幂等）。
     * 只由 `stop()` 置位/复位，其它地方不得改写。
     */
    stopping: false,
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
    /**
     * R-163 T4：最近一轮结局（内存态，不进镜像/持久化）——error/断流/取消后最后一条助手消息
     * 出「继续」入口；新轮发送时复位 'ok'。
     *
     * <p>批次2：新增 `'unconfirmed'`（中性「状态未确认」，AR-05）——它**不是**普通失败：
     * 面板据此给"重新载入 / 新开一轮"而不是错误卡/报错通知。
     */
    lastRoundOutcome: 'ok' as 'ok' | 'error' | 'interrupted' | 'cancelled' | 'unconfirmed',
    /**
     * 批 E · E2：上一轮是否以 **done 正常终态** 收尾（"回复下方出建议"的轮次级判据）。
     *
     * 为什么不用 `lastRoundOutcome === 'ok'` 单独判定：`lastRoundOutcome` 的初值就是 'ok'，
     * 历史的最后一轮也可能是"上一页签的完成"——那会在刷新后凭空冒出一组建议。本标志只在
     * **本页签收到非取消的 done 帧**时置真，`send()` 起新轮 / error 帧 / 停止 / 换会话即复位。
     * 挂起、待确认卡、表单、出错等其余闸门由面板按实时状态再判（这里只管"这一轮是不是正常完成"）。
     */
    followupReady: false,
    /** 最近一次工作区契约事件（面板"工作区已同步"chip 的数据源） */
    contextSync: null as WorkspaceActionRecord | null,
    /** 最近一次 ui_event 分发结果（AI 驱动工作区的回执展示） */
    lastUiEventResult: null as { event: string; handled: boolean; message: string } | null,
    /**
     * GF-B（DC-15）：当前待填写的生成式表单（generative_form 特判挂起，等用户填写）。
     * 不进镜像：刷新后由 pendingCall 的 expired 降级与后端超时收敛。
     *
     * <p><b>AR-07：同一时刻只有一个活动表单</b>。同批到达的第二张及以后进 {@link queuedForms}，
     * 第一张结算（提交 / 取消 / 失败）后按到达序补上 —— 不覆盖、不丢弃、不留死卡。
     */
    activeForm: null as ActiveGenerativeForm | null,
    /**
     * AR-07：同批后续表单的排队态（**到达序**；只留在内存，草稿在 sessionStorage 按 toolCallId 存）。
     *
     * <p>为什么排队而不是拒绝第二张：实时路径此前是单槽覆盖 ⇒ 第二张连同用户可能看到的信息
     * 一起消失；而恢复路径（快照串行重建）本来就是对的 —— 排队等于**让实时路径向已正确的
     * 恢复路径看齐**（两条路径同构）。不提供"批量一次提交"：仍逐张确认（HITL 语义）。
     */
    queuedForms: [] as QueuedGenerativeForm[],
    /**
     * AI 表格操作专项：当前待确认的写预览（设计稿 §3.3bis / §3.4）。
     * 只活在浏览器内存（业务行值不外送）；刷新即丢 ⇒ 恢复路径转 {@link stalePreviewToolCallId}。
     */
    activePreview: null as SheetWritePlan | null,
    /**
     * 刷新后失去本地 plan 的待决写工具（L0 恢复路径会为 PENDING 条目建卡）：
     * 写工具无超时，若不收口会渲染一张点不动的“应用”卡 —— 面板据此改渲染“预览已失效 + 放弃”。
     */
    stalePreviewToolCallId: null as string | null,
    /**
     * AI 表格工具治理批（§5.4）：最近一次已应用的 AI 改动摘要（卡面一行 + 撤销深度）。
     *
     * <p>
     * 只活在内存（撤销栈本身是 WeakMap，刷新即失效 —— 与"AI 改动只在本次页面有效"同一口径）。
     * `manualChanges` 由 {@link refreshSheetApplied} 现算：用户手改后重新取一次，卡面才不说旧数。
     */
    sheetApplied: null as SheetAppliedSummary | null,
    /**
     * M-2b（2026-10-22 用户直裁「无感恢复」）：快照恢复时**尚未呈现**的同批待决条目。
     *
     * 串行呈现与实时路径一致（一次只出一张卡）：刷新恢复只建第一张，其余按快照顺序排在这里，
     * 当前卡决完后由 {@link revealNextRestoredPendingCard} 就地补下一张。只活在内存、不落镜像，
     * 换会话 / 起新轮 / 停止即清空。
     */
    restoredPendingQueue: [] as AiRunCurrentEntry[],
    /**
     * AI 可视可操作专项：上传交接的活动态（设计稿 §3.9）。
     *
     * 与表格预览是**两种形态**：预览依赖浏览器里算出的 diff（刷新必死 → stalePreviewToolCallId）；
     * 交接只依赖「页面入口是否还在」，刷新后仍可继续（`status` 由区域是否仍注册决定）。
     * 不进镜像：`toolCallId/runId/ref` 足够重建，卡渲染时再判区域。
     */
    activeUploadHandoff: null as ActiveUploadHandoff | null,
    /**
     * 最近一次界面原语留下的撤销点（深度 1；与表格的 `undo_last_sheet_edit` 互不共享状态）。
     * 只给用户（面板摘要行的「撤销」按钮），**不提供** `ui_undo` 工具。
     */
    uiUndoEntry: null as UiUndoEntry | null,
    /** 撤销点归属的工具段（面板只在那一张卡上出「撤销」按钮）。 */
    uiUndoToolCallId: null as string | null,
    /** 界面原语的一次性播报（面板 `aria-live` 区消费）。 */
    uiAnnouncement: null as string | null,
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
    expandedBlocks: {} as Record<string, boolean>,
    /**
     * R-166（口径③）：确认门提交态（同 toolCallId 最多提交一次）。
     * 进表 = 点击批准/拒绝的同一刻；出表 = 结局帧 / 权威校正 / 提交失败回退。
     */
    gateSubmissions: {} as Record<string, GateSubmission>,
    /**
     * 批4-D（用户 2026-10-23 裁决）：**不可撤销确认卡的勾选态**（`toolCallId → true`）。
     *
     * 只对 `part.irreversible === true` 的门卡有意义（普通后果确认卡不读本表）。键缺省 = 未勾选；
     * **变更即复位**：新的 `confirm_request` 帧 / 结局帧（`confirm_decision`）/ 提交成功 /
     * 会话或轮次收口都清 —— 不让"上一张卡的勾选"变成下一张卡的默认同意。
     */
    gateAcks: {} as Record<string, boolean>,
    /**
     * 上下文占用（上下文专项批①）：`done.context` 的逐轮快照 + 端点重建的七项拆分。
     *
     * 更新口径（C-36）：**只在 `done` 帧到达时**写 —— 流式中不跳动（半轮的真值是半轮的，
     * 拿它刷新进度条会让读者把"正在变大"误读成"要爆了"）。`breakdown` 独立于 `contextUsage`：
     * 它只在端点返回时才有（popover 打开时按需拉，不必每轮都发）。
     */
    contextUsage: null as ContextUsage | null,
    contextBreakdown: null as ContextBreakdownItem[] | null,
    /** 端点返回的压缩态（"压了什么"的数据；面板另有"正在做什么"的阶段态，见下） */
    contextCompaction: null as ContextCompaction | null,
    /**
     * 批次3：压缩**过程**阶段（`idle` / `compacting` / `done`）。
     *
     * 与 `contextCompaction` 分工：后者是压缩结果的账（level/before/after），只在 `context` 帧
     * 到达时写；本组字段是"此刻是否正在压缩"，由 `heartbeat{phase:'compacting'}` 推进，
     * 供面板显示"正在压缩 · 已用 N 秒"。**不含百分比**——压缩没有可伪造的进度刻度。
     */
    compactionPhase: 'idle' as CompactionPhase,
    /**
     * 压缩阶段起点（客户端锚点）。收到服务端 `elapsedMs` 时按"此刻 − elapsedMs"折算，
     * 之后由面板的局部时钟推进；没有服务端数字时退回本地首帧时刻（不编造历史耗时）。
     */
    compactionStartedAtMs: null as number | null,
    /** 最近一次服务端给出的已用毫秒（null = 服务端没给，面板只用本地 elapsed）。 */
    compactionElapsedMs: null as number | null,
    /** 压缩完成时刻（`context` 帧或压缩后的 `streaming` 心跳）；null = 未完成。 */
    compactionCompletedAtMs: null as number | null,
    /**
     * 批次3 / AR-06：快照未建的"准备中"现场（可重试，不是错误、不是"轮不存在"）。
     * `null` = 当前没有该阶段。
     */
    preparingRun: null as AiPreparingRun | null,
    /**
     * 批次2：历史对账失败现场（`GET /api/ai/history` 的失败）。`null` = 无失败。
     *
     * <p>只有机器码/状态/时刻 —— 文案由面板按 `scope` 选词条（store 不落用户可见串）。
     * 失败时同时做**统一封口**（{@link sealRecovery}）并置 `recoveryGap`（不可完整恢复），
     * 所以界面不会永久停在"正在加载/正在生成"。
     */
    historyError: null as AiLoadErrorView | null,
    /** 批次2：`GET /api/ai/runs/current`（恢复快照）失败现场。 */
    currentError: null as AiLoadErrorView | null,
    /**
     * 批次2：上下文明细端点失败现场。**不覆盖**已有数据（"读不到"不等于"用了 0"），
     * popover 据此出"失败 + 重试"，有旧数据时数据与失败提示并存。
     */
    contextError: null as AiLoadErrorView | null,
    /** 批次2：上下文明细的加载态（idle/loading/ready/error）—— popover 三态判据。 */
    contextStatus: 'idle' as 'idle' | 'loading' | 'ready' | 'error',
    /**
     * 批次2：恢复完整性缺口（不可完整恢复）。中性事实：不是轮失败。
     * 值只在"能说清为什么/缺哪段"时才有；无缺口 = null。
     */
    recoveryGap: null as AiRecoveryGap | null,
    /**
     * 批次2：恢复/续收进行中（面板据此显示中性的"正在恢复"而不是普通生成中，
     * 也用于 `runPhase` 四态判定）。只由 loadHistory 与"回到进行中的回答"置位。
     */
    restoringRecovery: false,
    /**
     * 批次2（ADR-5）：409 `SESSION_BUSY` 回执里可手动指回的进行中轮。
     *
     * <p>**只作动作入参**：面板给"回到进行中的回答"入口，`runId` 绝不进用户可见文本；
     * store 不自动重挂、不轮询。持有者不可知（`unknown=true`）时为 null。
     */
    busyRun: null as AiBusyRunRef | null,
    /**
     * O02：本端已经**发出停止**的那一轮（`stop()` 同步置位）。
     *
     * <p>停止与它的传播之间存在窗口：窗口里后端仍可能回 409 指回这一轮。那是对"停止尚未
     * 传播"的残影，不是"上一轮仍在进行中" —— 见 {@link isAuthoritativeBusyRun}。
     * runId 每轮唯一，故置位后无需回收（换会话时随现场清空）。
     */
    stoppedRunId: null as string | null,
    /**
     * M-19（2026-10-29，R1 返修）：**start 帧之前就停止**的会话停止意向（`stop()` 在 runId 未知时置位）。
     *
     * <p>为什么需要：runId 只由 start 帧给出。旧实现在 runId 为空时只做本地收敛、**从不发 cancel**
     * —— 后端那一轮继续跑（工具副作用、记忆写入），用户却以为已停。
     *
     * <p>R1 关键点（C2）：`stop()` 此时**不得 abort 初始 /chat 传输** —— 否则 start 帧永远到不了，
     * "拿到 runId 再补 cancel"就成了不可达的假绿。传输留着，start 到达即按 runId 精确补 cancel，
     * 随后再关掉本地连接。`transport` 就是这条要等的连接（同时用作"意向失效"的判据）。
     *
     * <p>生命周期（I5）：自身 start 到达并补 cancel ⇒ 消耗；自身传输收尾仍无 start ⇒ 作废；
     * 新轮的 start 到达（宿主不同）⇒ 作废；超过 {@link STOP_INTENT_TTL_MS} ⇒ 作废；换会话 ⇒ 清空。
     * **不再拿 409 猜归属**：会话级 409 的 runId 无法证明是本端被停的那一轮（可能是另一页签的新轮），
     * 猜着取消会误杀别人的轮。
     */
    stopIntent: null as {
      hostMessageId: string | null
      at: number
      transport: AbortController | null
    } | null,
    /**
     * M08：当前**活动续收连接**所在的 runId（single-flight 登记位，null = 没有活动连接）。
     *
     * <p>与 `currentAbort` 一起判活（{@link hasLiveRunStream}）：同一 runId 只允许一条 SSE，
     * 后到的恢复入口并入现连接，不新建 —— 否则 `reattachActive` 与 `resumeSnapshotStream`
     * 各起一条（全量回放 + 增量续收并存），连接与重连预算都翻倍。
     */
    activeStreamRunId: null as string | null,
    /**
     * M08：上面那条活动连接是否**按"挂起等待"语义**建立的（服务端送达请求帧后会主动收流）。
     *
     * <p>这类连接不会带来决策之后的续跑帧 —— 决策送达时必须**重连**（既有 B1 口径），
     * 所以它不参与 single-flight 的"并入"（否则续流会被静默吞掉）。判据见 {@link resumeSnapshotStream}。
     */
    activeStreamParked: false,
    /**
     * M08-R2：按 runId 固化的**续流驱动**（所有权 + 已消耗预算）。
     *
     * <p>与 {@link activeStreamRunId} 的分工：后者只描述"此刻有一条连接活着"，连接一断就清；
     * 本记录描述"这个 run 由谁驱动、还剩几份预算"，**跨连接存活期**有效（连接存活 / 退避 /
     * 重连都属于同一次驱动）。记录只在终态 / 停止 / 换会话 / 显式动作时删除 —— 见
     * {@link acquireRunStreamDrive} / {@link settleRunStreamDrive} / {@link restartRunStreamDrive}。
     */
    streamDrives: {} as Record<string, RunStreamDrive>,
    /**
     * 2026-10-29 ToC 收口批：共用**有界自动恢复**的已用次数（按类别）。
     *
     * <p>不落镜像、不进持久面：它只描述"这一次现场里自动恢复试了几次"。换会话 / 停止 /
     * 新发送 / 用户手动重试都清零或作废（见 {@link resetAutoRecovery}）。
     */
    autoRecoveryUsed: {} as Partial<Record<AutoRecoveryKind, number>>
  }),

  getters: {
    /**
     * Task 4：会话就绪（后端已发号）。`false` 时**任何** AI 请求都不得发出 ——
     * 这是"不发空 id"的引导门只读标志（真正的闸在 `ensureSession()`）。
     */
    sessionReady(state): boolean {
      return state.sessionId !== ''
    },
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
    /**
     * 全部待人工放行的确认门段 —— 同一消息可有多张确认卡（多闸门并存），
     * 逐段扫描、不用 `pendingCall` 单槽（调研 §3.9 ③：审批身份按卡片而非全局槽）。
     */
    pendingConfirms(state): ToolPart[] {
      const result: ToolPart[] = []
      for (const message of state.messages) {
        for (const part of message.parts) {
          if (part.kind !== 'tool') {
            continue
          }
          if (part.kind2 === 'CONFIRM' && part.requiresAction && part.status === 'pending') {
            result.push(part)
          }
        }
      }
      return result
    },
    /** AI 侧是否可用（无 key / 无 Redis 时降级提示）。 */
    available(state): boolean {
      return state.health?.available ?? false
    },
    /**
     * 降级原因文案（不可用时）。
     *
     * <p>R4（2026-10-29）：**只读服务端的 `health.message`**（可读的原因），固定兜底由面板的
     * `degradeReason` 给（`ai.panel.degradeUnknown`）。`healthError` 是**诊断字段**（探针失败的
     * 异常原文，可能含 `AI_API_KEY` / provider / KEK 等机制词），不进任何用户文案 —— 它的出口只有
     * DEV 日志与"探测失败"的固定提示。
     *
     * <p>R5：用 `||` 而不是 `??` —— 后端 `message: ''`（空串）时 `??` 会把它当有效值返回，降级条
     * 就成了一条空白栏；空串/undefined 都回落到面板的固定兜底。
     */
    unavailableReason(state): string | null {
      if (state.health?.available) {
        return null
      }
      return state.health?.message || null
    },
    /** 通知槽的渲染文本（i18n L-69）：有 code 就按**当前语言**重译，否则回落裸串。
     *  `notice === null` 一律视为空槽（外部直接置 null / 既有清空写法不得被旧 code 复活）。 */
    noticeText(state): string | null {
      if (state.notice === null) {
        return null
      }
      if (state.noticeCode !== null) {
        return translate(state.noticeCode, state.noticeArgs ?? {})
      }
      return state.notice
    },
    /**
     * 运行状态文案（N6 裁决：按帧口径区分；i18n L-69：走 locale，切语言即重算）。
     * - 生成中：本轮进行中且没有挂起等待；
     * - 挂起等待：收到 suspended / confirm_request / frontend_tool_request 后；
     * - 空闲：没有进行中的轮次。
     */
    runStateLabel(state): string {
      if (!state.loading) {
        return translate('ai.panel.runState.idle')
      }
      return state.suspended
        ? translate('ai.panel.runState.suspended')
        : translate('ai.panel.runState.generating')
    },
    /** 输入区是否可用（执行中/挂起等待中一律禁发）。 */
    canSend(state): boolean {
      return !state.loading
    },
    /**
     * 批次2：本轮的**视图阶段**（四态，单一判据）。
     *
     * <p>为什么不让面板自己拼 `loading`/`suspended`：B1「挂起即断」之后 park 是
     * `loading=false && suspended=true`，与"空闲"只差一个布尔 —— 拼错就会把"等人决策"说成
     * "没有在途轮"。这里把口径钉死：
     * <ul>
     * <li>`parked` = 挂起等待（有卡等人/等前端执行；连接可能已按设计收流）；</li>
     * <li>`recovering` = 正在恢复/续收（`restoringRecovery`，含"回到进行中的回答"）；</li>
     * <li>`running` = 有连接在收流且无人工等待；</li>
     * <li>`idle` = 没有在途轮。</li>
     * </ul>
     * 优先级：parked > recovering > running > idle（挂起时"有没有连接"不再是用户的关注点）。
     */
    runPhase(state): 'idle' | 'running' | 'parked' | 'recovering' {
      if (state.suspended) {
        return 'parked'
      }
      if (state.restoringRecovery) {
        return 'recovering'
      }
      return state.loading ? 'running' : 'idle'
    },
    /**
     * R-166（口径③）：某挂起条目的提交态（null = 未提交）——面板据此把批准/拒绝双按钮
     * 一并禁用，并在 `syncing` 时显示「已提交，同步中…」瞬态。
     */
    gateSubmissionOf(state) {
      return (toolCallId: string): GateSubmission | null => state.gateSubmissions[toolCallId] ?? null
    },
    /**
     * 批4-D：不可撤销门卡的勾选态（false = 未勾选 / 普通确认卡恒 false）。
     * 面板据此禁用「批准」并显示提示行 —— 拒绝永远可用。
     */
    gateAckOf(state) {
      return (toolCallId: string): boolean => state.gateAcks[toolCallId] === true
    }
  },

  actions: {
    /**
     * 写一条**原文**通知（i18n L-69）：调用方已按当前语言取好文案（如面板的即时反馈提示），
     * 或文案本身是后端/工具原文 —— 同时清掉 `noticeCode`，否则旧模板会在渲染时盖掉这条。
     */
    showRawNotice(text: string, severity: NoticeSeverity = 'info'): void {
      setRawNotice(this, text, severity)
    },

    /**
     * 批次3：清零压缩阶段（新轮 / 终态 / 换会话）。**不碰** `contextCompaction`
     * —— 那是上一轮"压了什么"的账，由会话重置或下一轮 `context` 帧自己更新。
     */
    resetCompactionProgress(): void {
      this.compactionPhase = 'idle'
      this.compactionStartedAtMs = null
      this.compactionElapsedMs = null
      this.compactionCompletedAtMs = null
    },

    /**
     * 批次3：消费 `heartbeat{phase:'compacting'}` —— 只记**真实已用时间**，不推进任何百分比。
     *
     * <p>起点锚定：服务端给了 `elapsedMs` 就按"此刻 − elapsedMs"回推（首帧心跳即可显示真实
     * 已用时长）；后续心跳只校正锚点，不重新从 0 计时。服务端没给数字时退回本地首帧时刻。
     */
    noteCompactionHeartbeat(frame: { elapsedMs?: unknown; elapsedSeconds?: unknown }): void {
      const elapsed = heartbeatElapsedMs(frame)
      if (this.compactionPhase !== 'compacting') {
        this.compactionStartedAtMs = elapsed !== null ? Date.now() - elapsed : Date.now()
        this.compactionCompletedAtMs = null
      } else if (elapsed !== null) {
        this.compactionStartedAtMs = Date.now() - elapsed
      }
      if (elapsed !== null) {
        this.compactionElapsedMs = elapsed
      }
      this.compactionPhase = 'compacting'
    },

    /** 批次3：`context` 帧（或压缩后的 `streaming` 心跳）落地 = 压缩完成提示的唯一来源。 */
    markCompactionDone(): void {
      if (this.compactionPhase !== 'compacting') {
        return
      }
      this.compactionPhase = 'done'
      this.compactionCompletedAtMs = Date.now()
    },

    /**
     * 批次3：消费 `heartbeat{phase:'preparing'}` / 快照准备中信号。
     * 只存内部 runId 与真实起点；面板**不显示 runId**，也不把它当成错误。
     */
    notePreparingHeartbeat(runId: string, elapsedMs: number | null): void {
      const current = this.preparingRun
      const startedAtMs = elapsedMs !== null
        ? Date.now() - elapsedMs
        : current?.runId === runId
          ? current.startedAtMs
          : Date.now()
      this.preparingRun = {
        runId,
        startedAtMs,
        elapsedMs: elapsedMs ?? (current?.runId === runId ? current.elapsedMs : null)
      }
    },

    /** 批次3：清掉"准备中"现场（快照建成 / 新轮 / 终态 / 换会话）。 */
    clearPreparingRun(): void {
      this.preparingRun = null
    },

    /**
     * 批次3 / AR-06：准备中的**一次性手动重试**（不轮询、不自动重挂）。
     *
     * 重跑既有 `runs/current → 恢复` 路径：快照已建成则按批次2 的静默续收接着走；
     * 仍准备中则重新落准备中（不封口、不报"轮不存在"）；真正空态才交给既有封口逻辑。
     */
    async retryPreparing(): Promise<void> {
      if (!this.preparingRun || preparingRetryInFlight) {
        return
      }
      // 手动出口接管：本类自动恢复的排期与预算一并作废（用户已经自己动手，自动链让位）
      this.resetAutoRecovery('preparing')
      preparingRetryInFlight = true
      this.currentError = null
      this.restoringRecovery = true
      try {
        await this.restoreSnapshotGuarded()
      } finally {
        this.restoringRecovery = false
        preparingRetryInFlight = false
      }
    },

    /**
     * 2026-10-29 ToC 收口批：进入"准备中"现场（三处入口共用）—— **同时排一次有界自动恢复**。
     *
     * <p>为什么不能只留手动重试：面板里没有"立即重试"的调用点（`CompactionStatus.vue` 连同
     * `retryPreparing` 都没有 UI 入口），只靠它就是一条**不可达**的出口 —— 快照一直没建好时
     * 界面会永远停在"准备中"。自动恢复只做零副作用的探活：`runs/current → 恢复` 既有路径。
     */
    notePreparingState(runId: string, elapsedMs: number | null): void {
      this.restoredPendingQueue = []
      this.notePreparingHeartbeat(runId, elapsedMs)
      this.activeRunId = runId
      this.currentError = null
      this.loading = true
      this.suspended = false
      this.streamInterrupted = false
      this.startAutoRecovery(
        'preparing',
        async () => {
          if (this.preparingRun === null) {
            return true
          }
          // 2026-10-29 返修（复审 I-1）：自动恢复的 attempt 与手动包装层同款 —— 恢复在途期间
          // `restoringRecovery` 必须为真，否则呈现层（`turnStillOpen`）会把"恢复期"当成
          // "本轮未终态"，末条已决只读卡照旧先展开、快照回来再折。
          this.restoringRecovery = true
          try {
            await this.restoreSnapshotGuarded()
            return this.preparingRun === null
          } finally {
            this.restoringRecovery = false
          }
        },
        () => this.notePreparingExhausted()
      )
    },

    /**
     * 准备中的自动恢复**耗尽**：停止自动探活，回到既有手动出口 ——
     * 面板的恢复错误条（`currentError` + 「重试恢复」→ {@link retryRestoreSnapshot}）。
     * 不封口、不报"轮不存在"：准备中仍是可恢复阶段。
     */
    notePreparingExhausted(): void {
      if (this.preparingRun === null || this.currentError !== null) {
        return
      }
      this.currentError = toLoadErrorView(
        'current',
        // 诊断串（不进用户可见面：`toLoadErrorView` 只留 code/status；文案由面板按 scope 选词条）
        new ApiError('preparing: auto recovery exhausted', 202, 'PREPARING_TIMEOUT', null)
      )
    },

    /** 手动出口接管：清掉该类自动恢复的排期与预算（用户/其它入口已经自己动手了）。 */
    resetAutoRecovery(kind: AutoRecoveryKind): void {
      cancelAutoRecovery(kind)
      if (this.autoRecoveryUsed[kind] === undefined) {
        return
      }
      const next = { ...this.autoRecoveryUsed }
      delete next[kind]
      this.autoRecoveryUsed = next
    },

    /**
     * 共用**有界自动恢复**（2026-10-29 ToC 收口批）。
     *
     * <p>调用方必须保证 `attempt` 是**零副作用**的：读快照 / 读历史 / 把既有轮接回来 /
     * 给新会话做一次对账 —— 绝不重发用户消息、不重放工具、不重复任何写。
     * 预算按类别计（{@link AUTO_RECOVERY_MAX_ATTEMPTS}），两次尝试之间隔
     * {@link AUTO_RECOVERY_DELAY_MS}；任何一步之后现场被作废（换会话 / 停止 / 新轮 / 手动重试）
     * 即放弃，不再写回。
     *
     * @param onExhausted 预算用尽（或本次调用时已用尽）的回调 —— 调用方在这里把现场交回
     *   **既有**手动出口（错误条 + 重试钮 / 回稿），不新增 UI
     * @returns 是否恢复成功
     */
    async autoRecoverBounded(
      kind: AutoRecoveryKind,
      attempt: () => Promise<boolean>,
      onExhausted?: () => void
    ): Promise<boolean> {
      if (autoRecoveryInFlight.has(kind)) {
        return false
      }
      let used = this.autoRecoveryUsed[kind] ?? 0
      if (used >= AUTO_RECOVERY_MAX_ATTEMPTS) {
        onExhausted?.()
        return false
      }
      autoRecoveryInFlight.add(kind)
      const epoch = autoRecoveryEpoch(kind)
      try {
        while (used < AUTO_RECOVERY_MAX_ATTEMPTS) {
          used += 1
          this.autoRecoveryUsed = { ...this.autoRecoveryUsed, [kind]: used }
          if (await attempt()) {
            this.autoRecoveryUsed = { ...this.autoRecoveryUsed, [kind]: 0 }
            return true
          }
          if (autoRecoveryEpoch(kind) !== epoch) {
            return false
          }
          if (used >= AUTO_RECOVERY_MAX_ATTEMPTS) {
            break
          }
          const keepGoing = await new Promise<boolean>((resolve) => {
            autoRecoveryWaiters.set(kind, () => resolve(false))
            const timer = setTimeout(() => {
              autoRecoveryTimers.delete(kind)
              autoRecoveryWaiters.delete(kind)
              resolve(true)
            }, AUTO_RECOVERY_DELAY_MS)
            autoRecoveryTimers.set(kind, timer)
          })
          if (!keepGoing || autoRecoveryEpoch(kind) !== epoch) {
            return false
          }
        }
        onExhausted?.()
        return false
      } finally {
        autoRecoveryInFlight.delete(kind)
      }
    },

    /**
     * 排一次自动恢复（**延时启动**：先给人 / 其它入口让路，自动动作不抢先）。
     *
     * @returns 是否排上（false = 同类已在跑 / 已排 / 预算用尽 —— 调用方据此保留既有手动出口；
     *   注意 false 不区分"在跑"与"用尽"，只有用尽时才回调 `onExhausted`）
     */
    startAutoRecovery(
      kind: AutoRecoveryKind,
      attempt: () => Promise<boolean>,
      onExhausted?: () => void
    ): boolean {
      if (autoRecoveryInFlight.has(kind) || autoRecoveryTimers.has(kind)) {
        return false
      }
      if ((this.autoRecoveryUsed[kind] ?? 0) >= AUTO_RECOVERY_MAX_ATTEMPTS) {
        onExhausted?.()
        return false
      }
      const epoch = autoRecoveryEpoch(kind)
      const timer = setTimeout(() => {
        autoRecoveryTimers.delete(kind)
        if (autoRecoveryEpoch(kind) !== epoch) {
          return
        }
        void this.autoRecoverBounded(kind, attempt, onExhausted)
      }, AUTO_RECOVERY_DELAY_MS)
      autoRecoveryTimers.set(kind, timer)
      return true
    },

    toggleExpand(): void {
      this.expanded = !this.expanded
      persistExpanded(this.expanded)
    },

    /**
     * 启动会话镜像：messages 任何变化（含流式 token）**节流**写入 sessionStorage。
     *
     * <p>
     * 批G · G4：原先是**防抖**（每个 token 都 clearTimeout 重排 300ms）—— 只要帧间间隔 < 300ms，
     * 镜像就永远写不下去（实证 A2：刷新前镜像正文为空、DOM 已 154 字）。节流 = leading 立即写
     * + 窗口内合并为一次 trailing；另在 pagehide / visibilitychange(hidden) 立即 flush，
     * 保证"关页/切后台"那一刻的正文不丢。
     *
     * <p>
     * 批次7B（P1⑤）：trailing 落到浏览器 idle 再写（长会话不再占渲染帧）；同一现场重复触发时
     * 由 {@link writeMirror} 去重（no-op），不产生重复写。增量序列化 / 有界裁剪 / 配额提示见该函数。
     */
    startMirror(): void {
      if (mirrorStarted) {
        return
      }
      mirrorStarted = true
      this.$subscribe(() => {
        const now = Date.now()
        const elapsed = now - mirrorLastWriteAt
        if (elapsed >= MIRROR_THROTTLE_MS) {
          cancelMirrorSchedule()
          this.writeMirrorNow()
          return
        }
        if (mirrorTimer !== null || mirrorIdleHandle !== null) {
          return // 窗口内已有一次待写：合并（trailing 覆盖最新快照）
        }
        // 批次7B：窗口用尽后**等到浏览器空闲**再写盘（长会话序列化不再占渲染帧）。
        mirrorTimer = setTimeout(() => {
          mirrorTimer = null
          writeMirrorWhenIdle(() => this.writeMirrorNow())
        }, MIRROR_THROTTLE_MS - elapsed)
      }, { detached: true, deep: true })
      // 页面隐藏/卸载 = 最后一次机会：立即落盘（退订由浏览器负责，页签生命周期内不重复装）
      if (!mirrorFlushInstalled) {
        mirrorFlushInstalled = true
        mirrorFlushHandler = () => {
          cancelMirrorSchedule()
          this.writeMirrorNow()
        }
        window.addEventListener('pagehide', mirrorFlushHandler)
        document.addEventListener('visibilitychange', () => {
          if (document.visibilityState === 'hidden') {
            mirrorFlushHandler?.()
          }
        })
      }
    },

    /**
     * 立即落盘（throttle 的 leading / trailing、pagehide、stop() 共用唯一写口）。
     *
     * <p>
     * 批G · R2（真机回归）：**启动期空态不是"确实为空"，而是"还没读"** —— 面板挂载时
     * `startMirror()` 先装订阅，`ensureTabOwnership()` 的任何 state 变更（写 `tabOwnership` 就会
     * 触发）都会以此刻的 `messages=[]` 立即落盘，把上一页签留下的持久化镜像清空；紧接着
     * `loadHistory()` 的 `readMirror` 读到空镜像，G3 合并在途轮空转（真机打点顺序
     * `SET n=0 → GET n=0 → SET n=3`）。故 `historyLoaded` 之前的空态一律不写：
     * 那时镜像里若有内容，只能来自另一次更完整的现场，任何本地空态都无权覆盖它。
     */
    writeMirrorNow(): void {
      if (!this.historyLoaded && this.messages.length === 0) {
        return
      }
      // Task 4：没有后端发号的会话、或会话归属身份已与当前身份不一致（切换在途）⇒ 不落盘，
      // 否则旧身份的在途帧会写进新身份的镜像键（跨身份污染）。
      if (this.sessionId === '' || !isSameIdentity(this.identity, this.sessionIdentity)) {
        return
      }
      const outcome = writeMirror(this, this.sessionId, this.messages, this.activeRunId, {
        // 批G · R4（真机回归）：**挂起态也是在途** —— B1「挂起即断」把挂起轮写成
        // `loading=false && suspended=true`（收流了，但这一轮还在等人决策）。只看 loading 会把
        // "还在等决策的一轮"标成不在途，刷新后 `mergeMirrorInFlightRound` 首行直接返回，
        // 只存在于镜像里的同段正文与已执行卡全丢（真机：5 张卡只剩一张重建的确认卡）。
        inFlight: this.loading || this.suspended,
        runSeq: this.runSeq
      })
      // 批次7B：去重命中（现场未变）不推进节流锚 —— 下一次真实变化应尽快落盘。
      if (outcome !== 'unchanged') {
        mirrorLastWriteAt = Date.now()
      }
    },

    /**
     * 启动工作区契约订阅（S4.4c 契约联动）。
     *
     * 订阅两个主题：
     * - `workspace_changed`：用户在业务页面上的动作（选配置/切步骤/改条件/编辑表格…）
     *   → 面板显示"工作区已同步"chip；动作本身已由 workspace store 记入 recentActions，
     *   随下一轮 `buildContext()` 注入给模型（AI 面板不自己拼上下文，仍走窄接口）；
     * - `ui_event_result`：AI 驱动工作区动作的回执（AI→业务方向）。**成功且属于"无噪声"类**
     *   （导航 / 恢复向导 / 切步骤 / 三类刷新，见 `SILENT_SUCCESS_UI_EVENTS`）不写回执位；
     *   失败与有副作用类（建作业 / 下载 / 选配置 / 设条件 / 改模式 …）照旧写。
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
          // 无噪声成功：这类动作的成功不写回执位（见 SILENT_SUCCESS_UI_EVENTS）。
          // 槽位保持原样 —— 上一手**确实是失败**的那条事实不该被一次成功的导航抹掉。
          if (record.handled === true && SILENT_SUCCESS_UI_EVENTS.has(record.event)) {
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
        // AI 表格工具治理批（§2.5）：把管理员的有效上界下发到前端采样的"下笔之前"。
        // 取不到 / 旧后端 ⇒ 传 undefined 回到硬顶兜底（20/5），与治理批之前逐字等价。
        setSheetPolicy(this.health?.sheet ?? null)
      } catch (error) {
        this.health = null
        this.healthError = error instanceof Error ? error.message : translate('ai.notice.healthProbeFailed')
        // N-1（R3）：原文**只进日志/诊断**（`healthError` 字段本身也是诊断用途）—— 用户可见的
        // 胶囊/告警一律用固定友好文案（`ai.panel.probeFailedHint`），机制词不上屏。
        if (import.meta.env.DEV) {
          console.warn('[ai] health probe failed:', this.healthError)
        }
        // 探测失败不清策略：已生效的收紧值比"回到 20"更安全（K-10：兜底是放宽方向，仍安全）
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
    /**
     * 拉取会话上下文占用（上下文统计与压缩专项批①；`GET /api/ai/context/{sessionId}`）。
     *
     * <p>
     * 三个调用点：① {@link #loadHistory}（刷新恢复：本地 store 是内存态，刷新即丢，占用量必须由
     * 服务端重建）；② popover 打开（七项拆分按需拉，不必每轮都发）；③ 挂起态（没有新帧，占用仍要可读）。
     *
     * <p>
     * 两条降级口径：
     * <ul>
     * <li>端点失败（503/网络）⇒ **保持现状**、不覆盖 —— 把"读不到"写成"用了 0"是最糟的谎；</li>
     * <li>`source === 'none'`（会话从未记账）⇒ `contextUsage = null`（pill 不出现：没有可说的数）。</li>
     * </ul>
     */
    async loadContext(): Promise<void> {
      // Task 4 引导门：没有后端发号的会话就**不发请求**（空 id 一律不落网）。
      // 已就绪时**不**多切一个 microtask（既有调用时序/同步断言不变）。
      if (this.sessionId === '' && (await this.ensureSession()) === '') {
        return
      }
      const sid = this.sessionId
      this.contextStatus = 'loading'
      this.contextError = null
      try {
        const snapshot = await aiApi.getContext(sid)
        // 会话身份守卫（同 loadHistory 的红队 MAJOR-1 口径）：跨 await 期间换了会话即整体丢弃
        if (this.sessionId !== sid) {
          return
        }
        this.contextBreakdown = snapshot.breakdown ?? null
        this.contextCompaction = snapshot.compaction ?? null
        this.contextStatus = 'ready'
        if (snapshot.source === 'none') {
          this.contextUsage = null
          return
        }
        this.contextUsage = {
          usedTokens: snapshot.usedTokens,
          limitTokens: snapshot.limitTokens,
          percent: snapshot.percent,
          level: snapshot.compaction?.level ?? 'none',
          est: snapshot.estimated,
          circuit: snapshot.compaction?.circuit ?? 'closed'
        }
      } catch (error) {
        // 读不到就不动数据（历史渲染与既有状态不受影响），但要**可见**：失败 + 可重试。
        if (this.sessionId !== sid) {
          return
        }
        this.contextStatus = 'error'
        this.contextError = toLoadErrorView('context', error)
      }
    },

    /** 批次2：上下文明细失败后的重试（一次性；popover 的重试钮入口）。 */
    async retryLoadContext(): Promise<void> {
      await this.loadContext()
    },

    /**
     * 批次2：历史对账入口（`restoringRecovery` 的唯一置位点 + 必达复位）。
     *
     * <p>把 `restoringRecovery` 放在**包装层**而不是内层各 return 上：恢复路径有 5+ 个早退
     * 分支，逐个补复位必然会漏一个（漏掉就是"永久恢复中"）。`loadHistoryInner` 只管取数与落盘。
     */
    async loadHistory(): Promise<void> {
      // Task 4 引导门：history 必须先有后端发号的会话（空 id 不发请求）。
      if (this.sessionId === '' && (await this.ensureSession()) === '') {
        this.historyLoaded = true
        return
      }
      const sid = this.sessionId
      this.restoringRecovery = true
      try {
        await this.loadHistoryInner()
      } catch (error) {
        if (this.sessionId !== sid) {
          return // 会话已换：过期异常不落进新会话
        }
        // 兜底：未预期异常同样必须可见 + 封口（按"历史是否已落定"选择归属，不误报历史失败）
        if (!this.historyLoaded) {
          this.sealRecovery('history', error)
        } else if (this.currentError === null) {
          this.sealRecovery('current', error)
        }
      } finally {
        this.restoringRecovery = false
      }
    },

    async loadHistoryInner(): Promise<void> {
      const sid = this.sessionId
      // M-17（2026-10-29）：取历史期间用户可能已发出新一轮（`send()` 递增 `roundEpoch` 并换
      // `currentAbort`）。轮次身份必须在入口捕获 —— 否则历史回来时会整体替换 `messages`，
      // 在途轮的宿主与半截正文被顶掉，后续帧只能新建第二条助手气泡（同一轮两条气泡）。
      const epochAtEntry = roundEpoch
      const abortAtEntry = currentAbort
      // I4（R1 返修）：入口时本端已有在途轮（用户在本轮里点"重试/重新载入"就是这条路）⇒ 镜像
      // 是**滞后现场**，不得先拿它把屏上本轮换掉；历史回来也只能"对账不替换"。判据放在这里而不是
      // 只靠 await 后比对：镜像物化发生在 await **之前**，只比对轮次身份拦不住它。
      // N3（R2）：`stopped` 之后保留的旧传输（C2 为等 start 特意不 abort）不算"在途轮"—— 这一轮
      // 已被放弃，历史重载必须能照常落盘，否则停止后再也刷不出历史。
      const inFlightAtEntry = this.loading || (currentAbort !== null && !this.stopped)
      this.startMirror()
      const mirror = readMirror(this.sessionId)
      // 批G · G4：镜像里的在途标记与帧序号在**归一之前**取出 —— 归一会把消息一律标成 done
      const mirrorInFlight = !inFlightAtEntry && mirror?.inFlight === true
      const mirrorRunSeq = mirror?.runSeq ?? {}
      /** 归一后的镜像消息快照（G3 合并要用 —— this.messages 随后会被历史整体替换） */
      let mirrorMessages: ChatMessage[] = []
      if (!inFlightAtEntry && mirror && Array.isArray(mirror.messages) && mirror.messages.length > 0) {
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
        // 批次6A 返修：思考段计时用**帧接收锚**（服务器 lastFrameAt ↔ 本地收帧时刻）。
        // 镜像不持久化该本地锚（AR-12），读回时用镜像写入时刻 `savedAt` 重播 —— 仍是本地钟，
        // 与 lastFrameAt 成对，刷新/离线的那段计入真实已用时长。
        seedReasoningLocalAnchors(this.messages, mirror.savedAt)
        this.activeRunId = mirror.lastRunId ?? null
        // 批G · G4：帧序号随镜像回来（增量续收的锚点）；刷新后本轮之前没有更新的序号，直接接管
        this.runSeq = { ...mirrorRunSeq }
        if (mirrorInFlight) {
          // 写镜像时该轮还在途：末条助手消息保持"流式未收尾"，供静默续收就地续写
          // （否则 G2 找不到宿主，只能新起一条气泡）
          const host = [...this.messages].reverse().find((item) => item.role === 'assistant')
          if (host) {
            host.done = false
            host.streaming = true
          }
        }
        // 批2 前置（GLM o1）：存量镜像残留的 requiresAction=true 在读取侧收口（v1 迁移结果与
        // v2 intact 都生效），须在 writeMirror 回写之前完成 —— 否则刷新复活挂起语义。
        // 批G：在途轮保留原样（它还没收尾，挂起语义可能是真的 —— 不能在读取侧替它收口）
        const sanitized = mirrorInFlight ? false : sanitizeMirrorResidues(this.messages)
        // 批2（权威翻转）：镜像里的旧投影一律按段重算（存量 v2 镜像可能是双写期的产物，
        // 投影里仍留着"已终态但挂起卡不消失"的旧口径）—— 落展示态前先归一到段口径
        this.messages.forEach((message) => deriveProjections(message))
        mirrorMessages = this.messages
        if (migrated.migrated || sanitized) {
          // 只在"迁移过 / 收口过"时回写：无变化时回写会让每次刷新都写盘
          // （既有幂等断言 `savedAt` 不变即锁这一口径），派生本身是幂等纯函数
          writeMirror(this, this.sessionId, this.messages, this.activeRunId, {
            inFlight: mirrorInFlight,
            runSeq: this.runSeq
          })
        }
      }

      let history: AiHistory
      try {
        history = await aiApi.history(this.sessionId)
      } catch (error) {
        if (this.sessionId !== sid) {
          return // 会话已换：过期失败不落任何状态（新会话的 historyLoaded 由新会话自己落）
        }
        this.historyLoaded = true
        // 批次2：失败必须**可见 + 可重试**，且当场统一封口（不留在流式假态 / 不永久 loading）。
        // 镜像内容只是"现场"，不是可确认完整的答案 ⇒ 同步置 recoveryGap（中性，不是轮失败）。
        this.sealRecovery('history', error)
        // 2026-10-29 收口（共用有界自动恢复）：历史是**纯 GET**（零副作用）⇒ 自动重读有界；
        // 耗尽后回到既有手动出口（错误条 + 「重新载入历史」钮 + 恢复缺口入口）。
        this.startAutoRecovery('history', async () => {
          await this.loadHistory()
          return this.historyError === null
        })
        return
      }

      // 会话身份对账（红队 MAJOR-1）：不匹配则整体丢弃 —— 下面的 messages 写入与
      // restoreSuspendedSnapshot（读 this.sessionId 拉 runs/current、就地重建挂起卡）都不得落进新会话
      if (this.sessionId !== sid) {
        return
      }
      // M-17 / I4：取数窗口内已起新轮 / 换了连接，或**入口时本端就在途** ⇒ 这份历史属于过期现场，
      // 只对账不替换。保留屏上的在途现场（镜像 + 实时帧），本次历史整体丢弃；`historyLoaded` 仍落定
      // （对账已结束，镜像写入恢复正常），后续帧继续写原来的宿主。历史这一路**成功**了，故历史错误与
      // "历史不可用"缺口一并作废（否则重试成功后错误条还挂着）。
      // N-1（R3）：**停止后只比对轮次纪元**。C2 特意保留的 pre-start 连接会在 await 期间被收
      //（`currentAbort` 置 null）—— 那是"被放弃的那一轮"的连接收尾，不是换了轮；再拿连接身份判就
      // 会把这次合法重载整份丢掉（停止后再也刷不出历史）。未停止时维持原双判据（换连接 = 换轮）。
      const roundChanged = this.stopped
        ? roundEpoch !== epochAtEntry
        : (roundEpoch !== epochAtEntry || currentAbort !== abortAtEntry)
      if (inFlightAtEntry || roundChanged) {
        this.historyLoaded = true
        this.historyError = null
        this.recoveryGap = null
        return
      }
      // 批次2：历史拿到了 ⇒ 历史错误与"历史不可用"类缺口一并作废（归档缺口可能在续收时另行检出）
      this.historyError = null
      this.recoveryGap = null

      // 上下文专项批①：占用条在刷新后必须由服务端重建（本地 store 是内存态，刷新即丢）。
      // 与历史并行拉，不阻塞历史渲染（pill 慢一拍可接受，历史渲染慢一拍不可接受）。
      void this.loadContext()

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
        await this.restoreSnapshotGuarded()
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
      // 槽位放行的幻影卡（2026-10-29）：后端对 FORM_SLOTS_ALREADY_KNOWN 已不发用户可见的工具帧，
      // 但条目/历史照旧（审计与模型回执要留）。历史面按同一口径隐藏该 toolCallId 的段与响应，
      // 否则"实时看不到、刷新后又冒出来一张"——同一件事两个答案。
      // 响应也要一并摘掉：留着会让"序位兜底"把它错配给另一张未配对卡（错配比缺失更糟）。
      const silentSlotFormIds = new Set<string>()
      for (const item of history.messages) {
        for (const call of item.toolCalls ?? []) {
          const state = call.id !== null && call.id !== undefined && call.id !== ''
            ? item.toolCallStates?.[call.id]
            : undefined
          if (call.id && isSlotReleasePhantom(call.name, state?.code)) {
            silentSlotFormIds.add(call.id)
          }
        }
      }
      for (const item of history.messages) {
        if ((item.type || '').toUpperCase() !== 'TOOL') {
          continue
        }
        for (const response of item.toolResponses ?? []) {
          if (response.id && silentSlotFormIds.has(response.id)) {
            continue
          }
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
      // 整条不产出消息的历史下标（当前唯一来源 = 只带槽位放行幻影卡的 assistant）。
      // 必须记下来交给 R-150 合并 pass：那里用"非 TOOL 条目 ↔ 已建消息"的**按位游标**配对，
      // 掉了一条而不告诉它，后续每条都会拿到上一条的消息、末尾读到 undefined（合并时直接抛错，
      // 异常被 loadHistory 吞成 historyError，界面表现为历史加载失败 + 内容错位）。
      const suppressedHistoryIndexes = new Set<number>()
      this.messages = history.messages.flatMap((item, index) => {
        if ((item.type || '').toUpperCase() === 'TOOL') {
          return [] // TOOL 条目：响应已并入 ASSISTANT 工具段，本身不再产生消息
        }
        // 批次2 / AR-01 第 5 条：后端 `synthetic=true` 的条目是合成/系统提示（压缩摘要、步数收尾），
        // **不得**按 USER 建成用户气泡。这里统一转成 SYSTEM 消息并保留 `synthetic` 标记，
        // 面板据此渲染系统级「已压缩」提示（不展示内部配置键、不冒充用户原话）。
        const synthetic = item.synthetic === true
        const role: ChatMessage['role'] = synthetic ? 'system' : roleOf(item.type)
        // 批2：历史整体替换的分支同样**只建段**（正文段 + 按 toolCalls 顺序的工具段，
        // result 由 TOOL 条目的 toolResponses 回填（id 优先、序位兜底）；历史条目没有待决语义，
        // 故 requiresAction=false），旧投影由 deriveProjections 一次产出，不手工拼 content / toolRuns。
        // R-144（C-3）：status/kind2 双口径——载荷 `toolCallStates[call.id]` 有 status ⇒ 经词表单表
        // `historyToolCallStatus` 读真态；缺（旧载荷 / resume 重建轮 T4）⇒ 占位 'succeeded'。kind 有
        // 数据 ⇒ 还原（CONFIRM/FRONTEND/BACKEND），缺 ⇒ 'UNKNOWN'（不臆造默认值）。
        // R-144（C-6）保序：text 空白且 toolCalls ≥ 1 ⇒ **不产空前导 text 段**（载荷无段序字段，
        // 同条目 text↔toolCalls 相对序不可知，消「空 text 段打头」渲染面）；工具段 seg = 构造后
        // 数组下标（有 text 段时 = toolIndex+1，无 = toolIndex；R-150 合并 pass 仍全局重排）。
        // 槽位放行的幻影卡（FORM_SLOTS_ALREADY_KNOWN）不进段：后端已不为它发用户可见的工具帧，
        // 历史面必须同口径（否则刷新后又冒出一张卡）。它的响应也已从 responsePool 摘除，
        // 因此这里过滤掉之后不会把"无主响应"错配给它后面的卡。
        const calls = (item.toolCalls ?? []).filter((call) => !(call.id && silentSlotFormIds.has(call.id)))
        const blankText = (item.text ?? '').trim() === ''
        // 全部调用都被隐藏（本条只剩幻影卡）⇒ 不补空 text 段：那会留下一个空气泡。
        const allCallsSilenced = (item.toolCalls ?? []).length > 0 && calls.length === 0
        const hasTextPart = !blankText || (calls.length === 0 && !allCallsSilenced)
        // 批次6A 返修 / AR-12：历史 assistant 自带的思考正文（记忆 metadata.reasoningContent，
        // 通道有该键才展示）→ 一条 `streaming=false` 的思考段。**不带任何计时字段**：历史已完成
        // 轮不显示耗时、不回算；通道缺键则不出思考卡（不伪造、不新写持久化）。
        const reasoningText = typeof item.reasoningContent === 'string' ? item.reasoningContent : ''
        const hasReasoningPart = reasoningText.trim() !== ''
        const textSeg = hasReasoningPart ? 1 : 0
        const toolSegBase = textSeg + (hasTextPart ? 1 : 0)
        const hasTextFrameTruncation = item.noticeCode !== undefined
          || item.originalChars !== undefined || item.returnedChars !== undefined
          || item.strategy !== undefined || item.truncatedReason !== undefined
        const parts: MessagePart[] = [
          ...(hasReasoningPart
            ? [{ kind: 'reasoning', seg: 0, text: reasoningText, streaming: false } as MessagePart]
            : []),
          ...(hasTextPart
            ? [{
              kind: 'text',
              seg: textSeg,
              text: item.text ?? '',
              streaming: false,
              // 裸 truncated 是消息级长度截断；有段级元数据时则保留旧帧的段级截断标记。
              ...historyTruncationFields(item, { includeTruncated: hasTextFrameTruncation })
            } as MessagePart]
            : []),
          ...calls.map((call, toolIndex): ToolPart => {
            const name = call.name ?? '未知工具'
            const state = call.id !== null && call.id !== undefined && call.id !== ''
              ? item.toolCallStates?.[call.id]
              : undefined
            // 治理 P0-1：结局八态 **outcome 优先**（新载荷）—— 有合法 outcome 即用，缺/域外值才
            // 回落 status 旧表（旧载荷 / resume 重建轮 T4 同口径，行为一字不动）。
            // M1（收口修）：非终态（PENDING/APPROVED ⇒ pending）不得被 outcome='unknown' 覆写成
            // failed —— 条目仍在等人工，后端也不会对其写 outcome；此处为双保险。
            const statusDerived = state?.status !== undefined ? historyToolCallStatus(state.status) : null
            const mappedStatus = statusDerived === 'pending'
              ? statusDerived
              : outcomeStatus(state?.outcome) ?? statusDerived
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
              seg: toolSegBase + toolIndex,
              toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`,
              name,
              kind2: kind === 'CONFIRM' || kind === 'FRONTEND' || kind === 'BACKEND' ? kind : 'UNKNOWN',
              status: mappedStatus ?? 'succeeded',
              args: call.arguments,
              result: null,
              // A10 R-15：持久化的模型侧截断元数据随历史响应回到段上（刷新后角标/本地化 hover）。
              ...historyTruncationFields(matched),
              requiresAction: false
            }
            // R-160 M2/M1：回读决策元数据三字段（写口 R-158 #10 已成，历史路此前丢失 ⇒
            // 刷新回执卡不渲染）。decision 按词表白名单（approved/rejected —— 2A 收口修 MINOR-2
            // 起补 timeout：同批新增的第三种门结局，不补则刷新后超时卡丢失决策口径；批G · S13
            // 起补 cancel：用户停止也是一次决策，不补则刷新后「已取消」的卡又读成「已超时」）；
            // decidedAt 仅认数值；submittedValues 为后端 resultText 原文（限 FRONTEND），
            // JSON.parse 失败兜底 null。
            if (state) {
              if (state.decision === 'approved' || state.decision === 'rejected'
                || state.decision === 'timeout' || state.decision === 'cancel') {
                part.decision = state.decision
              }
              if (typeof state.decidedAt === 'number') {
                part.decidedAt = state.decidedAt
              }
              if (kind === 'FRONTEND' && typeof state.submittedValues === 'string') {
                let submitted: Record<string, unknown> | null = null
                try {
                  submitted = JSON.parse(state.submittedValues) as Record<string, unknown>
                } catch {
                  submitted = null // 兜底 null（不伪造提交值；徽标/回执从 status 派生）
                }
                part.submittedValues = (submitted ?? undefined) as Record<string, unknown>
              }
            }
            if (matched) {
              consumedResponses.add(matched)
              part.result = matched.responseData ?? null
              part.userTextCode = matched.userTextCode
              // L-78：历史面的视图（后端按工具名重新派生）随段落位 —— 刷新后卡面仍是表格形态，
              // 而不是回落到旧文本。缺键 = 该工具没有视图投影（与帧内 `resultView` 同一契约）。
              if (matched.resultView) {
                part.resultView = matched.resultView
              }
            } else {
              unmatchedCalls.push({ name, part }) // 序位兜底候选（见下方统一互配）
            }
            return part
          })
        ]
        if (parts.length === 0) {
          // 本条的段全被隐藏（例如整条只带一张槽位放行的幻影表单卡）⇒ 不产空消息；
          // 下标登记给 R-150 合并 pass，保证"按位游标"与 `history.messages` 不错位。
          suppressedHistoryIndexes.add(index)
          return []
        }
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
          timestamp: item.timestamp,
          // 批次2：服务端条目带 runId 时落消息，作为「重试本轮」的归属证据（40 条记忆窗口通常无此键）
          ...(typeof item.runId === 'string' && item.runId !== '' ? { runId: item.runId } : {}),
          // 批G · S4：被用户停止的半截正文（后端 stopped 元数据透出）—— 渲染「已停止」
          ...(item.stopped === true ? { stopped: true } : {}),
          // 截断专项：长度截断（finish_reason=length）的回答不完整 —— 刷新后仍要提示
          ...(item.truncated === true ? { truncated: true } : {}),
          // 批次2：系统级合成提示（压缩摘要/步数收尾）—— 面板不画用户气泡、不显示内部键
          ...(synthetic ? { synthetic: true } : {})
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
        unmatched.part.userTextCode = hit.userTextCode
        Object.assign(unmatched.part, historyTruncationFields(hit))
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
      for (const [historyIndex, item] of history.messages.entries()) {
        const type = (item.type || '').toUpperCase()
        if (type === 'TOOL') {
          continue // 无消息产出（R-149 已跳过），不断窗
        }
        if (suppressedHistoryIndexes.has(historyIndex)) {
          // 该条目整条未产出消息（只带槽位放行幻影卡）：不推进游标、也不断窗 ——
          // 它没有可见内容，等价于"这一条什么都没说"，前后同一用户轮的 assistant 仍按既有口径合并。
          continue
        }
        const message = builtMessages[builtCursor] as ChatMessage
        builtCursor += 1
        if (type === 'USER' || type === 'SYSTEM') {
          flushGroup()
          mergedMessages.push(message)
          continue
        }
        const calls = item.toolCalls ?? []
        // 批次6A 返修：只带思考正文的 assistant 不是"空白条目"（历史思考要能展示），不得被丢弃。
        const blankReasoning = typeof item.reasoningContent !== 'string'
          || item.reasoningContent.trim().length === 0
        if (calls.length === 0 && (item.text ?? '').trim().length === 0 && blankReasoning) {
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
      // 批G · G3：历史 + **未被历史覆盖的镜像在途轮** —— 用户消息在开轮即写入 ChatMemory
      // （实测流式中 /history 已有 USER），若整体替换就会把镜像里还在途的助手消息（正文 +
      // 已执行卡）抹掉（实证 C：整轮卡与说明文字丢失）。
      this.messages = this.mergeMirrorInFlightRound(mergedMessages, mirrorMessages, mirrorInFlight)
      // 批次2：历史通道不带失败现场（errorCard/code/unconfirmed）—— 由镜像侧把它补回**同一轮**，
      // 否则刷新后一次失败会被"历史整体替换"归一成一条完整回答（看起来像成功）。
      this.carryMirrorOutcomeMarkers(this.messages, mirrorMessages)
      // 历史里若存在未决挂起，补一张待处理卡片（前端工具/确认门都可能出现在这里）
      this.historyLoaded = true
      // T2b-D#3：historyLoaded 先行置位后再触发快照重建（loadHistory 先行完成，
      // 消除历史整体替换与挂起重建之间的写后写竞态）
      await this.restoreSnapshotGuarded()
    },

    /**
     * 批次2：快照重建的**兜底出口** —— 未预期异常同样落 `currentError` + 统一封口。
     *
     * <p>`restoreSuspendedSnapshot` 只把 `runs/current` 的失败当错误处理；重建路径里若还有
     * 未预期异常（例如载荷形态异常），不接住就会以 rejected promise 结束 —— 既没有可见错误、
     * 也没有封口。这里把这两类失败合成同一个出口（不重复处理已登记的 `currentError`）。
     */
    async restoreSnapshotGuarded(): Promise<void> {
      try {
        await this.restoreSuspendedSnapshot()
      } catch (error) {
        if (this.currentError === null) {
          this.sealRecovery('current', error)
        }
      }
    },

    /**
     * 批G · G3：把镜像里**仍在途**的那条助手消息接回历史尾部。
     *
     * <p>
     * 判据（宁可少补、不可双份）：
     * <ol>
     * <li>镜像写了 `inFlight`（写镜像时本轮还没收尾）；</li>
     * <li>镜像末条是 assistant；</li>
     * <li>历史的末条**不是**一条已收下的助手回答（末条是 assistant 且已有正文/工具段 ⇒ 不补，
     *    说明后端已经把这轮收进记忆，此时历史才是权威）。</li>
     * </ol>
     * 用户消息只在"历史里没有同一句用户消息"时补（否则同一句话出现两遍）。**"同一句"按后端
     * `InputSanitizer` 的同口径归一化后比较**（批G · R5/R2：历史侧标点已被 NFKC 折成半角，
     * 逐字比原文必然不等 ⇒ 真机出现两条同样的提问气泡）。
     */
    mergeMirrorInFlightRound(
      historical: ChatMessage[],
      mirrorMessages: ChatMessage[],
      inFlight: boolean
    ): ChatMessage[] {
      if (!inFlight || mirrorMessages.length === 0) {
        return historical
      }
      const tail = mirrorMessages[mirrorMessages.length - 1]
      if (!tail || tail.role !== 'assistant') {
        return historical
      }
      const last = historical[historical.length - 1]
      const historyHasReply = last !== undefined && last.role === 'assistant'
        && (last.content.trim() !== '' || last.parts.some((part) => part.kind !== 'text'))
      if (historyHasReply) {
        return historical
      }
      const mirrorUser = [...mirrorMessages].reverse().find((item) => item.role === 'user') ?? null
      const historyUser = [...historical].reverse().find((item) => item.role === 'user') ?? null
      const merged = [...historical]
      if (mirrorUser && (!historyUser
        || normalizeUserText(messageText(mirrorUser)) !== normalizeUserText(messageText(historyUser)))) {
        merged.push(cloneMessage(mirrorUser))
      }
      merged.push(cloneMessage(tail))
      return merged
    },

    /**
     * 批次2：历史整体替换后，把**镜像侧**的结局标记补回同一轮的助手消息上。
     *
     * <p>为什么需要：`/history` 只带正文/工具/stopped/truncated，**不带** errorCard/code/
     * unconfirmed/recoveryGap。一次失败轮在历史里就是一段 assistant 文本 —— 不加这一步，刷新
     * 后它就变成"一条完整回答"，用户再也看不到"这轮其实失败了/状态未确认"。
     *
     * <p>判据（宁可少补、不可错配）：
     * <ol>
     * <li>镜像末条是 assistant 且带任一结局标记；</li>
     * <li>历史末条 assistant 未带同类标记（历史口径优先，已有就不覆盖）；</li>
     * <li>两侧最后一条用户消息**归一后同句**（同一轮才补；对不上即放弃）。</li>
     * </ol>
     * 只补标记，不改正文/段/状态。
     */
    carryMirrorOutcomeMarkers(historical: ChatMessage[], mirrorMessages: ChatMessage[]): void {
      if (mirrorMessages.length === 0) {
        return
      }
      const tail = mirrorMessages[mirrorMessages.length - 1]
      if (!tail || tail.role !== 'assistant') {
        return
      }
      const hasOutcome = tail.errorCard != null || tail.code != null || tail.unconfirmed === true
        || tail.recoveryGap === true
      if (!hasOutcome) {
        return
      }
      const target = [...historical].reverse().find((item) => item.role === 'assistant')
      if (!target) {
        return
      }
      const mirrorUser = [...mirrorMessages].reverse().find((item) => item.role === 'user') ?? null
      const historyUser = [...historical].reverse().find((item) => item.role === 'user') ?? null
      if (!mirrorUser || !historyUser
        || normalizeUserText(messageText(mirrorUser)) !== normalizeUserText(messageText(historyUser))) {
        return
      }
      // 批次2：run 归属与结局标记同一轮才搬运；即使目标已有结局标记，也补 runId
      // （历史通道通常不带 runId，缺了它「重试本轮」就没有可证明的归属）。
      if (!target.runId && tail.runId) {
        target.runId = tail.runId
      }
      if (target.errorCard != null || target.code != null || target.unconfirmed === true
        || target.recoveryGap === true) {
        return
      }
      if (tail.errorCard != null) {
        target.errorCard = tail.errorCard
      }
      if (tail.code != null) {
        target.code = tail.code
      }
      if (tail.unconfirmed === true) {
        target.unconfirmed = true
      }
      if (tail.recoveryGap === true) {
        target.recoveryGap = true
      }
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
     *
     * **M-2b（2026-10-22 用户直裁「无感恢复」）：孤儿轮也重建，且串行呈现。** 判据 ——
     * `entryStatus === 'PENDING'`、真实需要用户操作的 CONFIRM/FRONTEND 条目、`req !== false`。
     * `BACKEND` 与自动前端工具直接续跑、从不弹卡；`req === true` = 请求帧已发；
     * **缺键 = 升级前遗留的孤儿轮**（是否发过帧已不可知）—— 按可建处理，否则整轮从界面消失、
     * 输入框永久禁用。显式 `req === false`（同批里请求帧还没轮到）维持 L0 口径：不建卡，
     * 等实时帧到达（活轮行为不变）。
     *
     * 串行呈现与实时路径一致：本函数**只重建第一张**，同批其余待决条目排进
     * `restoredPendingQueue`；当前卡决完后由 {@link revealNextRestoredPendingCard} 就地补下一张。
     * 未发卡条目不渲染、不新增占位 UI；`suspended` 只按可重建的待人卡置位。
     */
    async restoreSuspendedSnapshot(): Promise<void> {
      // R4（2026-10-29）：**停止态不接受后端快照的挂起重建**。用户在 start 帧之前/之后停止后，
      // 后端那一轮可能仍是 SUSPENDED + awaitingExternal（cancel 尚未落地/未传播）；照旧重建就会
      // 把一张可点卡摆回屏上、`suspended` 置回 true —— 输入区被重新锁住，与用户"已经停了"的认知相反。
      // 停止是**本地已落地**的决策：保持"已停止"展示，不恢复外部待决/挂起卡、不重连；
      // 刷新（内存态 `stopped` 复位）后才会按服务端真实状态恢复。
      if (this.stopped) {
        return
      }
      // Task 4 引导门：runs/current 属于对话域，必须带后端发号的会话。
      if (this.sessionId === '' && (await this.ensureSession()) === '') {
        return
      }
      const sid = this.sessionId
      const abortAtEntry = currentAbort
      let current: AiRunCurrent
      try {
        current = await aiApi.runsCurrent(sid)
      } catch (error) {
        if (this.sessionId !== sid) {
          return // 会话已换：过期失败不落进新会话
        }
        // 批次3：准备中阶段的读失败**不封口** —— 它仍是可重试的准备中，不是"轮不存在"。
        // 错误现场照样可见（面板给重试），但镜像里的在途轮保持原状。
        if (this.preparingRun) {
          this.currentError = toLoadErrorView('current', error)
          return
        }
        // 批次3 兼容窗口：旧后端在"已 start、快照未建"时可能仍回 404/UNKNOWN_RUN。
        // 只要本地还有镜像留下的在途轮与 runId，就把这一次 404 当成"准备中"（可重试），
        // 不封口、不落"轮不存在"；后端提供 preparing 标记后走上面的正路。
        const missingRun = error instanceof ApiError
          && (error.status === 404 || error.code === ErrorCode.UNKNOWN_RUN)
        const hasInFlightHost = this.messages.some((message) => message.role === 'assistant'
          && !message.done
          && message.runId !== undefined
          && message.runId === this.activeRunId)
        if (missingRun && this.activeRunId && hasInFlightHost) {
          this.preparingRun = {
            runId: this.activeRunId,
            startedAtMs: Date.now(),
            elapsedMs: null
          }
          this.currentError = null
          this.loading = true
          this.suspended = false
          return
        }
        // 批次2：快照读不到同样必须**可见 + 可重试**，且当场统一封口（镜像里的在途轮不能永久"在流"）。
        // 缺口原因 = 快照不可用（中性事实，不是把这一轮判失败）。
        this.sealRecovery('current', error)
        return
      }
      // 取数窗口内的身份对账（R-130/R-132）：sid 不等 = 会话已换；abort 句柄已换 = 同会话已起
      // 新轮 —— 两种都说明这份快照属于过期的现场，整体丢弃（含续收连接的建立）
      if (this.sessionId !== sid || currentAbort !== abortAtEntry) {
        return
      }
      // 批次3 / AR-06：已 start、快照未建 = "准备中"（可恢复），不是空态、不是轮不存在。
      // 不封口在途消息、不落 currentError；面板显示"准备中 · 已用 N 秒"。
      // 2026-10-29 收口：同一条落点排**有界自动恢复**（面板没有"立即重试"的调用点）。
      const preparing = preparingRunOf(current)
      if (preparing) {
        this.notePreparingState(preparing.runId, preparing.elapsedMs)
        return
      }
      this.clearPreparingRun()
      // M-2b：本次快照是当前会话的权威口径 —— 先作废旧队列，空态/已决态不得残留可补的旧卡
      this.restoredPendingQueue = []
      // AR-07：同批排队的表单同样以快照为权威口径（不在快照里的表单不该再被补出来）
      this.clearQueuedForms()
      // 批次2：快照读到了 ⇒ 上一轮的"快照不可用"错误作废（归档缺口另行按续收首帧判定）
      this.currentError = null
      // 2026-10-29 收口：权威快照读到**在途轮** ⇒ 上一轮的"快照不可用"缺口作废。
      // 复审返修（I-1）：`found=false`（轮已消失/保留期到期）**不是**"已解决"——
      // 那一刻紧接着的 `sealLeftoverInFlight()` 只会封口，不会补缺口；在这里清掉就把
      // 一条从未被确认完整的半截回答洗成了完整回答。可解决的类别判据见 `clearRecoveryGap`。
      if (current.found && current.runId) {
        this.clearRecoveryGap()
      }
      // AR-10（批次3 补齐）：寿命锚点只认后端 PTTL 的只读推导 —— 有就落、没有就清（不猜 24h）。
      // 若"上一次已知的锚点已经到期"且这次已拿不到运行态，说明它按保留期消失了：
      // ① 落一条**可见**通知（不是卡悄悄没了）；② 保留这个"已到期"锚点，让仍在屏上的挂起卡
      // 继续显示「已失效 + 刷新 / 新会话出口」，而不是变成一张可点却无解释的死卡。
      const expiryAnchor = runExpiryAnchorOf(current, Date.now())
      if (expiryAnchor === null && this.runExpiry !== null
        && runExpiryRemainingMs(this.runExpiry, Date.now()) <= 0) {
        setNotice(this, 'ai.notice.runExpired')
        this.runExpiry = { ...this.runExpiry, remainingMs: 0, receivedAtMs: Date.now() }
      } else {
        this.runExpiry = expiryAnchor
      }
      if (!current.found || !current.runId) {
        // 批次3 / AR-06：`/runs/current` 在"已 start、快照未建"期间同样是 found=false
        // （索引要等 store.create 才写）。若镜像还留着在途轮与 runId，先探一次重挂端点：
        // 202+preparing ⇒ 准备中（可重试，不封口）；200 ⇒ 快照已建，转正式重挂；
        // 404 ⇒ 确实没有这一轮，才走既有封口。
        // 归属必须可证明：镜像里的在途助手消息带着**同一 runId**（start 帧已落镜像）。
        // 只有 lastRunId、没有消息归属时不猜 —— 旧镜像的悬空轮仍走既有封口，避免把
        // 一个说不清归属的轮次探测成"准备中"。
        const inFlightHost = [...this.messages].reverse().find((item) => item.role === 'assistant'
          && !item.done
          && item.runId !== undefined
          && item.runId !== '')
        const recoveryRunId = inFlightHost?.runId ?? this.activeRunId
        if (recoveryRunId && inFlightHost) {
          const probe = await probeReattach(recoveryRunId)
          // 探测窗口内的身份对账（与上面同口径；任何 await 之后写回前必须重比）
          if (this.sessionId !== sid || currentAbort !== abortAtEntry) {
            return
          }
          if (probe.preparing) {
            this.notePreparingState(probe.preparing.runId, probe.preparing.elapsedMs)
            return
          }
          if (probe.status === 202) {
            // 202 但缺 preparing 键（旧后端/异常体）：仍按准备中，不把正常压缩判成失败
            this.notePreparingState(recoveryRunId, 0)
            return
          }
          if (probe.status === 200) {
            // 探测期间快照已建立：交给正式重挂（全量回放最稳；镜像正文本就滞后）
            void this.reattachActive(recoveryRunId, {
              hostMessageId: inFlightHost.id,
              lastSeq: this.runSeq[recoveryRunId] ?? 0
            })
            return
          }
          if (probe.status >= 500) {
            // 服务端明确不可用：按批次2 的快照失败出口（可见 + 可重试 + 统一封口）。
            this.sealRecovery('current', new ApiError('重挂探测失败', probe.status, String(probe.status), null))
            return
          }
        }
        // 空态：没有在途轮 —— 镜像若留下"未收尾"的助手消息，就地封口（否则光标永远在流）。
        // 既有历史渲染与空历史 expired 降级规则兜底。
        this.sealLeftoverInFlight()
        return
      }
      if (!current.awaitingExternal) {
        // 批G · G2：**在途轮静默续收**。covers RUNNING（纯文本/工具执行中）与 SUSPENDED 但条目
        // 全决的中间态。用户看不到任何"重挂"概念：不弹横幅、不写占位文案、不出现 runId。
        // 不 await：续收是长连接，loadHistory 不能被它拖住（loading 已在分支内同步置位）。
        void this.restoreInFlightRound(current)
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

      // 重建（M-2b）：PENDING + 真实待人卡 + req !== false（缺键 = 孤儿轮，可建）。
      // - PENDING：已决条目不重建挂起卡（勘误 4 同口径：hasDecidedPendingResidue / isFrameDecidedPendingStatus）；
      // - kind：BACKEND 与自动 FRONTEND 工具不弹卡；表单、表格预览、上传交接与 CONFIRM 才等人；
      // - req：显式 false 的同批条目等实时帧，不进队列（活轮行为不变）。
      // 串行呈现：只建第一张，其余进 `restoredPendingQueue`，决完一张补一张。
      const pendingEntries = current.pendingEntries.filter((entry) => entry.entryStatus === 'PENDING')
      const restorable = pendingEntries.filter(isRestorablePendingEntry)
      const first = restorable.find((entry) => !this.hasDecidedPendingResidue(entry.toolCallId)) ?? null
      const awaitingInput = first !== null
      if (pendingEntries.length > 0) {
        this.activeRunId = current.runId
      }
      // 只有真实可重建的待人卡才锁输入；自动工具/BACKEND/req=false 不制造假挂起。
      this.suspended = awaitingInput
      const host = first ? this.rebuildPendingCard(first, current.runId) : null
      this.restoredPendingQueue = restorable.filter((entry) => entry !== first)

      if (host) {
        // 增量续收：lastSeq = archiveMaxSeq 只收新帧（见 resumeSnapshotStream）
        void this.resumeSnapshotStream(current.runId, current.archiveMaxSeq, host.id, awaitingInput)
        return
      }
      // I-1（2026-10-21 收口修）：**无卡也要挂流** —— 没有可重建的卡不等于没有后续帧。
      // 真实窗口：卡 1 已决 → 后端先执行卡 1 的工具（时长可达数秒～数分钟，期间快照仍 SUSPENDED、
      // 卡 2 一直 `req=false`）→ 才轮到卡 2 发请求帧。刷新落在这段里时本函数此前直接返回 ⇒
      // 卡 2 的请求帧无人接收（只入归档），页面停在没有入口的死胡同（只能停止或再刷新）。
      // 现在照常以 `archiveMaxSeq` 挂上差量续收：请求帧到达即按实时路径建卡（`confirm_request`
      // handler），不新增任何占位 UI。宿主消息在**首个帧到达时**由 `handleFrame` 惰性取当前助手消息
      // （有则续写、无则创建），故不传 `hostMessageId` —— 空转期间不留空气泡。
      // req=false 说明还有请求帧可期，不能当成已完成的 park 收流；这类恢复保留断流重连。
      if (pendingEntries.length === 0) {
        return
      }
      const lastAssistant = [...this.messages].reverse().find((item) => item.role === 'assistant')
      if (lastAssistant) {
        void this.resumeSnapshotStream(current.runId, current.archiveMaxSeq, lastAssistant.id, awaitingInput)
        return
      }
      void this.resumeSnapshotStream(current.runId, current.archiveMaxSeq, undefined, awaitingInput)
    },

    /**
     * 批G · G2：**在途轮静默续收**（刷新恢复的核心）。
     *
     * <p>
     * 判据来自 {@code /runs/current}（G1 起 RUNNING 也写索引）：`found && !awaitingExternal`。
     * 行为：找宿主助手消息 → **清空其段** → `lastSeq=0` 全量重放 → 接着收实时帧。为什么全量而
     * 不是增量：镜像正文/卡片受节流窗口影响本就滞后（实证 A2：镜像 0 字、DOM 154 字），
     * 拿本地位点续收会缺口；归档回放的完整性已由实证 B2/E 验过。
     *
     * <p>
     * **全程无横幅、无占位文案、无 runId**：用户看到的只是"那条回答继续写下去"。
     */
    async restoreInFlightRound(current: AiRunCurrent): Promise<void> {
      const runId = current.runId
      if (!runId) {
        return
      }
      if (isTerminalRunStatus(current.snapshotStatus)) {
        // 索引尚未被终态清掉（竞态窗口）：不是在途轮，封口即走
        this.sealLeftoverInFlight()
        return
      }
      this.activeRunId = runId
      this.stopped = false
      this.streamInterrupted = false
      this.suspended = false
      this.loading = true
      // M08（single-flight）：这一轮已经有活动续收连接在写（另一恢复入口先到）——
      // 不重复建连接，也不清段重放：清掉之后新连接不会补发已收过的帧，正文会停在空白。
      // M08-R2：驱动权在别处或预算已用尽时同样不动（本函数下面会先清段再全量重放，
      // 清完却被挡下等于把正文留在空白）。
      if (this.canJoinRunStream(runId) || !this.canDriveRunStream(runId)) {
        return
      }
      let host = this.findInFlightHost()
      if (!host) {
        // 本轮在消息里没有宿主（如第 2 轮刚开、历史只到本轮的提问、镜像未落盘）：**新建**空宿主
        // 继续收 —— 绝不回落到已收尾的上一轮（R2 修 2，见 findInFlightHost）
        host = this.appendAssistantMessage('')
      }
      // 批次2：在途恢复的宿主归属该 run（重试入口只认 message.runId）
      host.runId = runId
      // 镜像正文滞后于真实：清空段后全量重放（不保留镜像里的半截，否则回放会重复拼接）
      host.parts = [{ kind: 'text', seg: 0, text: '', streaming: true }]
      host.content = ''
      host.reasoning = ''
      host.toolRuns = []
      host.pendingCall = null
      host.streaming = true
      host.done = false
      host.stopped = false
      host.appliedSeqs = []
      deriveProjections(host)
      await this.reattachActive(runId, { hostMessageId: host.id, lastSeq: 0 })
    },

    /**
     * 批G · R2（真机回归）：在途轮的宿主**只认本轮** —— 绝不回落到已收尾的上一轮。
     *
     * <p>
     * 原实现先找"未收尾的助手消息"，找不到就回落到「最后一条助手消息」，清其段后
     * `lastSeq=0` 全量重放：只要本轮在本地没有宿主（镜像被启动期空写清掉、第 2 轮刚开还没有
     * 助手消息、私有模式写不了 sessionStorage…），上一轮那条回答就被本轮帧覆盖 ——
     * 真机现场正是 `[user1, assistant(第2轮内容), user2]`：第 1 轮回答被吞，且回答排在提问之前。
     *
     * <p>
     * 判据（结构性的"答案位"）：**最后一条用户消息之后**的助手消息才是本轮的宿主 —— 本轮的回答
     * 必然排在它自己的提问之后。该窗口内只认 `!done` 那条（镜像 `inFlight` 落在
     * `loadHistory` 里被归一成未收尾，见 G2），窗口为空/只有已收尾消息即返回 null，
     * 由调用方**新建**宿主。新建的代价只是多一条气泡；回落覆盖的代价是把上一轮的正文毁掉
     * 并让两轮顺序错乱 —— 两者不对等，前者才是可接受的兜底。
     */
    findInFlightHost(): ChatMessage | null {
      let lastUserIndex = -1
      for (let index = this.messages.length - 1; index >= 0; index -= 1) {
        if (this.messages[index]?.role === 'user') {
          lastUserIndex = index
          break
        }
      }
      for (let index = this.messages.length - 1; index > lastUserIndex; index -= 1) {
        const item = this.messages[index]
        if (item && item.role === 'assistant' && !item.done) {
          return item
        }
      }
      return null
    },

    /**
     * 批G：把镜像留下的"未收尾"助手消息就地封口（后端已无在途轮时）。
     *
     * <p>
     * 不封口的代价：刷新后光标一直"在流"、且镜像被下一次写入继续标成 inFlight，
     * 于是每次刷新都去续收一个不存在的轮（空转）。
     */
    sealLeftoverInFlight(): void {
      let sealed = false
      for (const message of this.messages) {
        if (message.role !== 'assistant' || message.done) {
          continue
        }
        message.streaming = false
        message.done = true
        sealTrailingStreamPart(message)
        deriveProjections(message)
        sealed = true
      }
      if (sealed) {
        this.loading = false
        this.suspended = false
        this.writeMirrorNow()
      }
    },

    /**
     * 2026-10-29 ToC 收口批：重挂 / 续收连接**耗尽**后就地封口本轮宿主。
     *
     * <p>为什么必须封口：`onClose({terminal:false})` 只把 `loading` 复位、不改消息 ——
     * `done=false + streaming=true` 会一直留在屏上（光标永远在闪、"这一轮还在跑"），
     * 而连接早就没了。断流是事实，但**不是**"仍在生成"：封口 + 既有断流提示才是完整口径。
     *
     * <p>只封**消息**（streaming/done/段），不置 `recoveryGap`、不出错误卡、不碰待决段：
     * 卡片仍归用户动作或既有终帧收口。
     *
     * <p>2026-10-29 复审返修（M-4）：兜底查找**只认同一个 run 的宿主**（`runId` 与
     * {@link activeStreamRunId} / {@link activeRunId} / 显式传入的 `runId` 相等）。旧实现回落到
     * "最后一条未收尾助手消息"，在没有宿主 id 的续收（`resumeSnapshotStream` 的无卡挂流）里
     * 会封到**与本轮无关**的另一条半截回答上。
     */
    sealInterruptedHost(hostMessageId?: string | null, runId?: string | null): void {
      const run = runId ?? this.activeStreamRunId ?? this.activeRunId
      const sameRun = (item: ChatMessage): boolean => run !== null && item.runId === run
      const host = (hostMessageId ? this.messages.find((item) => item.id === hostMessageId) : undefined)
        ?? (activeRoundHostId !== null
          ? this.messages.find((item) => item.id === activeRoundHostId)
          : undefined)
        ?? [...this.messages].reverse().find(
          (item) => item.role === 'assistant' && !item.done && sameRun(item)
        )
      if (!host || host.role !== 'assistant' || host.done) {
        return
      }
      host.streaming = false
      host.done = true
      sealTrailingStreamPart(host)
      deriveProjections(host)
      this.writeMirrorNow()
    },

    /**
     * 2026-10-29 ToC 收口批：**权威快照读到在途轮** ⇒ 上一轮的"快照不可用"缺口作废。
     *
     * <p>2026-10-29 复审返修（I-1，收窄）：只清**读快照能证明已解决**的那一类
     * （`snapshot-unavailable`），且**只在该类缺口存在时**才动逐消息标记：
     * <ul>
     * <li>`archive-window`：被归档窗口裁掉的中间帧读 `/runs/current` **补不回来** ——
     *   续收锚点已是 `runSeq` 最大值，重连也不会再检出；清掉等于把缺帧的正文洗成完整回答；</li>
     * <li>`history-unavailable`：历史不可用只能由**历史读成功**作废（`loadHistoryInner` 成功路径）；</li>
     * <li>全局缺口为 null 时**不碰**消息级标记 —— 那些标记可能正来自 `archive-window`
     *   （全局先被历史成功路径清空、消息标记仍在的时间窗）。</li>
     * </ul>
     * 调用方还必须保证"快照确实读到在途轮"（`current.found && current.runId`）：`found=false`
     * 只意味着这一轮消失了，不意味着屏上那条半截回答变完整了。
     */
    clearRecoveryGap(): void {
      if (this.recoveryGap === null || this.recoveryGap.reason !== 'snapshot-unavailable') {
        return
      }
      this.recoveryGap = null
      let changed = false
      for (const message of this.messages) {
        if (message.recoveryGap === true) {
          delete message.recoveryGap
          changed = true
        }
      }
      if (changed) {
        this.writeMirrorNow()
      }
    },

    /**
     * 批次2：**统一的恢复失败出口** —— 错误可见 + 统一封口 + 缺口可表示。
     *
     * <p>历史/快照读取失败时，"屏上那半截"既不能继续装作在流（永久 loading/光标常驻），
     * 也不能被当成完整答案。本方法一次做完四件事：
     * <ol>
     * <li>按 scope 落机器可读错误现场（`historyError` / `currentError`），供面板出"失败 + 重试"；</li>
     * <li>封口所有未收尾的助手消息，并把它们标成 `recoveryGap`（**中性**：不是轮失败、不出错误卡）；</li>
     * <li>落 `recoveryGap` 全局态（原因 = 历史不可用 / 快照不可用），表示"不可完整恢复"。</li>
     * <li>清掉本地待决段与交互单例，避免恢复失败留下 suspended/pendingToolCall 假等待；
     *   只做过期降级，不伪造后端取消或成功。</li>
     * </ol>
     */
    sealRecovery(scope: Exclude<AiLoadErrorScope, 'context'>, error: unknown): void {
      const pendingIds = this.messages
        .filter((message) => message.role === 'assistant' && !message.done)
        .map((message) => message.id)
      const view = toLoadErrorView(scope, error)
      if (scope === 'history') {
        this.historyError = view
      } else {
        this.currentError = view
      }
      this.sealLeftoverInFlight()
      // pendingToolCall 是段投影：先清段的待决事实，再派生，不能直接给 computed 赋值。
      for (const message of this.messages) {
        expireResidueToolParts(message)
        deriveProjections(message)
      }
      // 本地恢复已失败，交互单例与恢复队列一并清场；不发起任何后端取消/回灌。
      this.cleanupTerminalInteractionState()
      this.suspended = false
      for (const id of pendingIds) {
        const message = this.messages.find((item) => item.id === id)
        if (message) {
          message.recoveryGap = true
        }
      }
      // 没有连接在跑才复位 loading（别的轮正在收流时不得把它标成空闲）
      if (currentAbort === null) {
        this.loading = false
      }
      this.markRecoveryGap(scope === 'history' ? 'history-unavailable' : 'snapshot-unavailable')
      if (this.messages.length > 0) {
        this.writeMirrorNow()
      }
    },

    /**
     * 批次2：显式登记"不可完整恢复"（中性事实）。
     *
     * <p>`archive-window` 由续收首帧的序号断层检出（见 {@link noteArchiveGap}）；历史/快照不可用
     * 由 {@link sealRecovery} 登记。缺口**不是**轮失败：不改 `lastRoundOutcome`、不挂错误卡。
     *
     * @param span 已知缺口区间（含端点）；省略 = 区间不可知（不臆造）
     */
    markRecoveryGap(
      reason: AiRecoveryGapReason,
      span: { fromSeq: number | null; toSeq: number | null } = { fromSeq: null, toSeq: null }
    ): void {
      this.recoveryGap = { reason, fromSeq: span.fromSeq, toSeq: span.toSeq, at: Date.now() }
    },

    /**
     * 批次2：续收连接的**首帧序号断层**判定 —— 归档窗口裁掉中间帧 ⇒ 不可完整恢复。
     *
     * <p>口径：业务帧的 `seq` 每轮从 1 起连续递增（心跳不占 seq）。以 `anchor`（本连接请求的
     * `lastSeq`，全量回放为 0）为期望位置，首帧 `seq > anchor + 1` 即说明 `(anchor, seq)` 之间的
     * 帧已不在归档里。只在**首帧**判一次（后续跳号可能是并发连接的正常交错，不据此下结论）。
     * 判定只落缺口标记，不伪造轮失败。
     *
     * @returns 是否检出缺口（调用方据此只报一次）
     */
    noteArchiveGap(anchor: number | null, seq: number | null): boolean {
      if (anchor === null || seq === null) {
        return false
      }
      const expected = Math.max(0, anchor) + 1
      if (seq <= expected) {
        return false
      }
      this.markRecoveryGap('archive-window', { fromSeq: expected, toSeq: seq - 1 })
      const host = this.messages.find((item) => item.id === activeRoundHostId)
        ?? [...this.messages].reverse().find((item) => item.role === 'assistant')
      if (host) {
        host.recoveryGap = true
        this.writeMirrorNow()
      }
      return true
    },

    /** 批次2：历史失败后的重试（一次性；面板错误条的重试钮入口）。 */
    async retryLoadHistory(): Promise<void> {
      this.historyError = null
      this.recoveryGap = null
      // M08-R2：用户显式重试 ⇒ 重新给驱动一份预算（否则"自动重连已用尽"会把重试钮锁死）
      this.forgetRunStreamDrive()
      // 2026-10-29 收口：手动出口接管 ⇒ 本类自动恢复的排期与预算作废（重复劳动没有意义）
      this.resetAutoRecovery('history')
      await this.loadHistory()
    },

    /** 批次2：恢复快照失败后的重试（只重跑 runs/current 那一步，不重新拉历史）。 */
    async retryRestoreSnapshot(): Promise<void> {
      this.currentError = null
      this.restoringRecovery = true
      // M08-R2：同上 —— 显式重试重置驱动预算
      this.forgetRunStreamDrive()
      // 2026-10-29 收口：手动出口接管 ⇒ 准备中的自动恢复让位（用户已经自己重试了）
      this.resetAutoRecovery('preparing')
      try {
        await this.restoreSnapshotGuarded()
      } finally {
        this.restoringRecovery = false
      }
    },

    /**
     * M08：该 runId 是否已有一条**活动**续收连接（single-flight 判据）。
     *
     * <p>判活三件一起：登记位一致、句柄仍是 `currentAbort`、且未被中止（`stop()` /
     * `adoptFreshSession()` 之后立刻失效，不会把新连接误判成"已有连接"）。
     */
    hasLiveRunStream(runId: string): boolean {
      return this.activeStreamRunId === runId && currentAbort !== null && !currentAbort.signal.aborted
    },

    /**
     * M08：该 runId 的现连接能否**并入**（不再新建一条 SSE）。
     *
     * <p>= 有活动连接且它**不是**按挂起语义建的：挂起连接送达请求帧后就被服务端收流，
     * 决策之后的续跑帧只能靠一条新连接接回（既有 B1 口径），并入它等于吞掉续流。
     */
    canJoinRunStream(runId: string): boolean {
      return this.hasLiveRunStream(runId) && !this.activeStreamParked
    },

    /**
     * O02：409 回执里的"进行中轮"是否仍是**权威活动运行**（决定要不要给忙提示）。
     *
     * <p>两条本地证据都足以推翻它：① 本端已对这条 run 发出停止（{@link stoppedRunId}）——
     * 那时的"忙"只是停止还没传播到后端的残影；② 本端已收到这一轮的终态（消息 `done`）——
     * 那一轮已经结束。其余情形（另一页签 / 另一条真实在跑的轮）照常返回 true：
     * 真正的 `SESSION_BUSY` 不被吞，入口照旧给出。
     */
    isAuthoritativeBusyRun(busy: AiBusyRunRef | null): boolean {
      if (busy === null) {
        return false
      }
      if (this.stoppedRunId === busy.runId) {
        return false
      }
      return !this.messages.some((message) => message.role === 'assistant'
        && message.runId === busy.runId
        && message.done)
    },

    /**
     * O02：终态帧（DONE / CANCELLED / error）到达 ⇒ 忙提示立即作废。
     *
     * <p>"回到进行中的回答"只对**仍在进行**的轮成立：那一轮一旦收尾，入口指过去的只会是一条
     * 已结束的回答。只清与终帧同轮（或本端当前轮）的登记，别的活动轮的忙态不受影响。
     */
    clearBusyRunOnTerminal(runId: string | undefined): void {
      const busy = this.busyRun
      if (busy === null) {
        return
      }
      if (runId !== undefined && runId !== busy.runId && this.activeRunId !== busy.runId) {
        return
      }
      this.busyRun = null
    },

    /**
     * M08-R2：该 run 现在能否新起一次驱动（当前没有在途驱动，且还有预算）。
     *
     * <p>调用方据此在**破坏性准备之前**判断（如 `restoreInFlightRound` 会先清段再重放：
     * 清完却被预算挡下会让正文停在空白）。
     */
    canDriveRunStream(runId: string): boolean {
      const drive = this.streamDrives[runId]
      if (drive === undefined) {
        return true
      }
      return !drive.active && drive.used < RUN_STREAM_DRIVE_BUDGET
    },

    /**
     * M08-R2：占用该 run 的续流驱动权（返回驱动令牌；null = 已有驱动在跑或预算已用尽）。
     *
     * <p>预算**跨连接**累计并共用：两个恢复入口不再各烧一份（真机交替重开就是两份预算）。
     * 被拒绝的入口什么都不做 —— 现场由持有者负责，旁观者不得改写 `loading` / `suspended`。
     */
    acquireRunStreamDrive(runId: string): number | null {
      if (!this.canDriveRunStream(runId)) {
        return null
      }
      const drive = this.streamDrives[runId]
      runStreamDriveSeq += 1
      this.streamDrives = {
        ...this.streamDrives,
        [runId]: { token: runStreamDriveSeq, used: drive?.used ?? 0, active: true }
      }
      return runStreamDriveSeq
    },

    /**
     * M08-R2：一次驱动收尾。**连接结束 ≠ 放弃该 run** —— 所有权与预算都按 run 留着，
     * 只标记"当前没有在途驱动"；只有**中断**（非终态、非挂起、非准备中、非用户停止、
     * 非探测判死）才消耗一份预算。
     */
    settleRunStreamDrive(runId: string, token: number, interrupted: boolean): void {
      const drive = this.streamDrives[runId]
      if (drive === undefined || drive.token !== token) {
        return // 已被终态 / 停止 / 新驱动接管：不动别人的现场
      }
      this.streamDrives = {
        ...this.streamDrives,
        [runId]: { ...drive, active: false, used: drive.used + (interrupted ? 1 : 0) }
      }
    },

    /** M08-R2：释放该 run 的驱动所有权（终态 / 停止 / 换会话）。缺省 runId = 全部释放。 */
    forgetRunStreamDrive(runId?: string): void {
      if (runId === undefined) {
        if (Object.keys(this.streamDrives).length > 0) {
          this.streamDrives = {}
        }
        return
      }
      if (this.streamDrives[runId] === undefined) {
        return
      }
      const next = { ...this.streamDrives }
      delete next[runId]
      this.streamDrives = next
    },

    /**
     * M08-R2：**显式动作**（决策送达 / 用户重试 / 回到进行中的回答）重新接管该 run 的驱动：
     * 重置所有权与预算。挂起语义连接不再代表"这一轮的后续帧"（决策后的续跑只能靠新连接接回，
     * 既有 B1 口径）—— 这里只清登记位、不 abort：入口会先换身份再中止旧连接。
     */
    restartRunStreamDrive(runId: string): void {
      this.forgetRunStreamDrive(runId)
      if (this.activeStreamRunId === runId && this.activeStreamParked) {
        this.activeStreamRunId = null
        this.activeStreamParked = false
      }
    },

    /**
     * 批次2（ADR-5）：用户手动"回到进行中的回答"（409 带回 runId 时）。
     *
     * <p>**一次性**动作：先做一次历史对账（把那一轮的用户消息与已有正文取回来），
     * `loadHistory → restoreSuspendedSnapshot` 会按既有 G2 路径**静默续收**在途轮。
     * 不做定时轮询、不自动重试；`runId` 不进入任何用户可见文本。
     */
    async reattachBusyRun(): Promise<void> {
      const busy = this.busyRun
      if (!busy) {
        return
      }
      this.busyRun = null
      this.activeRunId = busy.runId
      // M08-R2：用户显式"回到进行中的回答" ⇒ 重置该 run 的驱动所有权与预算
      this.restartRunStreamDrive(busy.runId)
      this.restoringRecovery = true
      try {
        await this.loadHistoryInner()
      } finally {
        this.restoringRecovery = false
      }
    },

    /**
     * 批次2 / AR-05：**重试本轮 = 仅模型步重跑**（真实后端窄入口，不本地截断、不追加用户消息）。
     *
     * <p>与旧假实现的区别：旧路径在"store 没有 action"时把本地消息截断后 `send(skipUserRecord)`，
     * 那是**重发请求**（会重建上下文、可能重放工具），不是"从固定历史重跑模型步"。这里只调
     * `POST /api/ai/runs/{runId}/retry-model-step`；202 受理后用**既有 events 通道**重挂
     * （`lastSeq` = 本端已见的最大帧序号），由同一套帧处理续写这条助手消息。
     *
     * <p>失败按后端 `failedCheck` 落到对应状态（不伪造成功、不猜）：
     * 取消 ⇒ `cancelled`；有半截内容 ⇒ 中性 `unconfirmed`；在途 ⇒ `in-progress`；
     * 其它前置失败 ⇒ 本地化说明；网络/503 ⇒ `error`。
     */
    retryRunIdFor(message: ChatMessage): string | null {
      if (typeof message.runId === 'string' && message.runId.trim() !== '') {
        return message.runId
      }
      // 唯一可证明的 activeRunId 回退：这条消息就是**当前在途轮**的宿主（run 身份在连接期仍活着）。
      // 其余情况（刷新后 activeRunId 为空 / 指向更晚一轮 / 历史通道无 runId）一律拒绝 ——
      // 宁可不出入口，也不重试错轮。
      if (activeRoundHostId !== null && activeRoundHostId === message.id && this.activeRunId) {
        return this.activeRunId
      }
      return null
    },

    async retryModelStep(message: ChatMessage): Promise<AiRetryModelStepOutcome> {
      if (this.loading || this.suspended) {
        return 'in-progress'
      }
      const runId = this.retryRunIdFor(message)
      if (!runId) {
        // 归属不可证明 ⇒ 不猜、不本地重发、不回退 activeRunId（伪造功能比不动作更糟）
        setNotice(this, 'ai.notice.retryRunUnknown', {}, 'error')
        return 'denied'
      }
      // 重挂锚点：取本端已见的最大帧序号。业务帧序号从 1 开始，0 不是可靠的重挂锚点；
      // 缺锚时禁止用 0 全量回放旧 error，也保留原失败现场，交由用户重新载入或新开一轮。
      // B2：`reduce` 取最大值 —— 展开运算符在长轮里既费栈又会在十万级序号记账上退化。
      const appliedMax = message.appliedSeqs && message.appliedSeqs.length > 0
        ? message.appliedSeqs.reduce((max, seq) => (seq > max ? seq : max), 0)
        : 0
      const lastSeq = Math.max(this.runSeq[runId] ?? 0, appliedMax)
      if (!Number.isFinite(lastSeq) || lastSeq <= 0) {
        setNotice(this, 'ai.notice.retryRunUnknown', {}, 'error')
        return 'denied'
      }
      this.loading = true
      // B2：点火在途时记住现场 —— 回来若已换会话/换轮，这条旧 run 不得挂进新会话
      const roundAtRetry = captureRound(this.sessionId, runId)
      let body: Record<string, unknown>
      try {
        body = await aiApi.retryModelStep(runId)
      } catch (error) {
        this.loading = false
        if (error instanceof ApiError) {
          const failedCheck = failedCheckOf(error)
          const reason = retryReasonOf(error)
          if (failedCheck === 'CANCELLED' || reason === 'ALREADY_CANCELLED') {
            this.lastRoundOutcome = 'cancelled'
            // 取消是终态：打上 stopped 让面板收起「重试本轮」入口（不再反复撞 409）
            message.stopped = true
            setNotice(this, 'ai.notice.retryCancelled')
            return 'cancelled'
          }
          if (reason === 'RESUME_IN_PROGRESS' || reason === 'LEASE_HELD_ELSEWHERE'
            || reason === 'ALREADY_RUNNING') {
            setNotice(this, 'ai.notice.retryInProgress')
            return 'in-progress'
          }
          if (error.code === ErrorCode.UNKNOWN_RUN || error.status === 404) {
            setNotice(this, 'ai.notice.retryDeniedNotFailed', {}, 'error')
            return 'denied'
          }
          if (failedCheck === 'PARTIAL_CONTENT') {
            // AR-05：有半截正文/思考 ⇒ 不做"失效替换"，给中性「状态未确认」视图
            message.unconfirmed = true
            this.lastRoundOutcome = 'unconfirmed'
            setNotice(this, 'ai.notice.retryPartial')
            return 'partial'
          }
          if (error.code === ErrorCode.SESSION_BUSY) {
            setNotice(this, 'ai.notice.retryInProgress')
            return 'in-progress'
          }
          if (error.status === 503 || error.code === ErrorCode.AI_REDIS_UNAVAILABLE
            || error.code === ErrorCode.AI_UNAVAILABLE) {
            setNotice(this, 'ai.notice.retryUnavailable', {}, 'error')
            return 'error'
          }
          if (failedCheck !== null) {
            setNotice(this, retryDeniedNoticeKey(failedCheck), {}, 'error')
            return 'denied'
          }
        }
        setNotice(this, 'ai.notice.retryUnavailable', {}, 'error')
        return 'error'
      }
      // B2：点火在途时换了会话/轮次 ⇒ 旧 run 不得挂进新会话（不写 activeRunId、不重建消息、
      // 不重挂）。新会话的 loading 属于它自己的轮次，这里也不得复位。
      if (!stillSameRound(roundAtRetry, this.sessionId, null)) {
        return 'denied'
      }
      this.loading = false
      // 200（幂等空操作）也可能带 retried:false —— 只有明确 retried:true 才进入重挂
      if (body?.retried !== true) {
        const reason = typeof body?.reason === 'string' ? body.reason : ''
        if (reason === 'ALREADY_CANCELLED') {
          this.lastRoundOutcome = 'cancelled'
          message.stopped = true
          setNotice(this, 'ai.notice.retryCancelled')
          return 'cancelled'
        }
        if (reason === 'PENDING_UNRESOLVED') {
          setNotice(this, 'ai.notice.retryDeniedPending', {}, 'error')
          return 'denied'
        }
        setNotice(this, 'ai.notice.retryDeniedNotFailed', {}, 'error')
        return 'denied'
      }
      // 202 已受理：清掉失败现场，把宿主重新置为流式态（内容由 events 回放/续收重建）
      this.activeRunId = runId
      this.streamInterrupted = false
      this.stopped = false
      this.busyRun = null
      this.followupReady = false
      this.lastRoundOutcome = 'ok'
      message.parts = []
      message.content = ''
      message.reasoning = ''
      message.toolRuns = []
      message.pendingCall = null
      message.done = false
      message.streaming = true
      delete message.errorCard
      delete message.code
      delete message.unconfirmed
      delete message.recoveryGap
      delete message.stopped
      delete message.truncated
      delete message.synthetic
      delete message.appliedSeqs
      deriveProjections(message)
      // M08-R2：用户显式"重试本轮" ⇒ 重新接管该 run 的驱动（预算重置；挂起连接作废）
      this.restartRunStreamDrive(runId)
      // 受理即返回：重挂是长连接（持续到本轮结束），不阻塞调用方；失败由重挂路径自己的
      // onClose/断流口径处理（与既有刷新恢复同一条通道）。
      void this.reattachActive(runId, { hostMessageId: message.id, lastSeq })
      return 'accepted'
    },

    /**
     * M-2b（2026-10-22）：把一条快照待决条目重建为挂起卡（刷新恢复与「串行补下一张」共用）。
     *
     * 宿主消息：优先镜像/历史里已持该工具**段**的消息；否则最后一条助手消息；都没有才
     * append 一条空助手消息作宿主 —— 唯一允许的新消息形态（历史/镜像皆无宿主），
     * 不复制历史工具卡、无双渲染。返回宿主供续收连接锚定帧归属。
     */
    rebuildPendingCard(entry: AiRunCurrentEntry, runId: string): ChatMessage {
      // 批2（权威翻转）：判据由 `toolRuns.some` 改读 `parts`（段是唯一真值，语义等价）
      let host = this.messages.find((item) => item.parts.some(
        (part): boolean => part.kind === 'tool' && part.toolCallId === entry.toolCallId
      ))
        ?? [...this.messages].reverse().find((item) => item.role === 'assistant')
        ?? null
      if (!host) {
        host = this.appendAssistantMessage('')
      }
      // 批次2：快照重建的宿主由该 run 的待决条目决定归属（「重试本轮」的归属证据，不得留空/留旧）。
      // 待决条目证明本轮仍有后续续跑；只恢复这个宿主的活动事实，不把历史助手一律标成流式。
      host.runId = runId
      host.streaming = true
      host.done = false
      // 批2：挂起卡不再是"就地写独立投影"，而是**落段再派生** —— 段侧 requiresAction=true
      // 因为该条目正是"等人工/前端动作"的那一个（派生出唯一 pendingCall，不新增重复工具卡）
      // 去超时裁决后快照里的 expiresAt/atMs 只作对账口径：段字段照原样携带，前端不据其
      // 折算任何倒计时 / 本地超时。
      const kind = entry.kind === 'CONFIRM' ? 'CONFIRM' : 'FRONTEND'
      this.upsertToolPart(host, {
        toolCallId: entry.toolCallId,
        name: entry.name,
        kind2: kind,
        status: 'pending',
        args: entry.arguments ?? undefined,
        requiresAction: true,
        ...(entry.expiresAt != null ? { expiresAt: entry.expiresAt, atMs: entry.atMs ?? undefined } : {}),
        // H1 预检摘要：快照载荷带则原样落段（与 suspended/confirm_request 帧同口径），
        // 缺省不产生该键 —— 旧载荷重建出的门卡与既有形态一字不差。
        ...(entry.preflight ? { preflight: entry.preflight } : {}),
        // 批4-D：不可撤销位同口径落段 —— 刷新恢复要能把复选框重建出来（且勾选态从零开始：
        // 恢复动作本身不得代替用户勾选，否则"刷新"就等于"已同意"）。
        ...(entry.irreversible === true ? { irreversible: true } : {})
      })
      deriveProjections(host)
      // AI 表格操作专项 §3.5：刷新后本地 plan 已丢 —— 写工具无超时，必须收口成
      // “预览已失效 + 放弃”，否则会出现一张点不动的“应用”卡。
      if (needsSheetPreview(entry.name, parseToolArgs(entry.arguments)) && this.activePreview?.toolCallId !== entry.toolCallId) {
        this.stalePreviewToolCallId = entry.toolCallId
      }
      // AI 可视可操作专项：上传交接**天然可恢复**（不依赖浏览器中间态）—— 区域仍注册即可继续，
      // 区域不在该页则落 missing（主按钮禁用、只能放弃）。与上面"预览已失效"是两种形态。
      if (UI_HANDOFF_TOOLS.has(entry.name) && this.activeUploadHandoff?.toolCallId !== entry.toolCallId) {
        const args = parseToolArgs(entry.arguments)
        const validated = validateUploadArgs(args)
        if (validated.ok) {
          const target = resolveUploadRegion(validated.value.ref)
          const status: ActiveUploadHandoff['status'] = target && target.region.root.isConnected === true
            ? 'waiting'
            : 'missing'
          this.activeUploadHandoff = {
            toolCallId: entry.toolCallId,
            runId,
            ref: target?.entry ?? validated.value.ref ?? null,
            accept: validated.value.accept,
            status,
            reason: status === 'missing' ? translate('ai.notice.uploadNoRegion') : null,
            filename: null
          }
        }
      }
      if (entry.name === GENERATIVE_FORM_TOOL) {
        // AR-07：恢复路径与实时路径走**同一个闸门** —— 空位激活、占位排队（"实时与恢复同构"）。
        // 不武装任何本地时限：表单一直可填，收口只认后端结局帧 / 用户取消。
        // 同一 toolCallId 保留现有填写态；串行补下一张时（旧表已决/已取消）换成本卡的表单。
        const spec = parseFormSchema(parseToolArgs(entry.arguments)?.form)
        if (spec) {
          this.presentGenerativeForm(entry.toolCallId, runId, spec)
        }
        // schema 非法：只留挂起卡（用户可取消），不武装表单态
      }
      return host
    },

    /**
     * M-2b（2026-10-22 用户直裁「无感恢复」）：串行呈现 —— 当前卡决完后把快照里下一张补上。
     *
     * 只消费 `restoreSuspendedSnapshot` 留下的同批队列：**同步、不发请求、不加提示、不新建连接**
     * （决策路径已按既有口径重连）。活轮里尚未发帧（`req=false`）的条目不在队列中，仍由实时帧
     * 到达建卡 —— 与升级前完全一致。调用点在 `clearSuspendedIfNoOtherConfirms` 之前：新卡一出现，
     * 挂起态与输入禁用语义自然延续（全部决完后才收口）。
     */
    revealNextRestoredPendingCard(runId: string): void {
      if (!this.suspended || this.restoredPendingQueue.length === 0) {
        return
      }
      if (this.activeRunId !== runId) {
        // 轮次身份已变：旧队列整体作废（不得把旧轮的卡补进新轮）
        this.restoredPendingQueue = []
        return
      }
      const next = this.restoredPendingQueue.find((entry) => !this.hasDecidedPendingResidue(entry.toolCallId)) ?? null
      if (!next) {
        this.restoredPendingQueue = []
        return
      }
      // 命中的这张出队；跳过的已决条目留在队里（下次扫描仍会跳过），不会复活
      this.restoredPendingQueue = this.restoredPendingQueue.filter((entry) => entry !== next)
      this.rebuildPendingCard(next, runId)
    },

    /**
     * T2b-D#3 增量续收（仿 reattachActive，但不新起消息 —— 挂起卡宿主已由快照重建就地就位）。
     * lastSeq = archiveMaxSeq：服务端只补发增量帧，禁全量回放（红队 A1/A3/B3）。
     *
     * @param hostMessageId 宿主消息 id；**省略** = 首帧到达时由 `handleFrame` 取当前助手消息
     *   （有则续写、无则创建）—— 供 I-1 的"无卡但挂起"路径使用（不预建空气泡）
     * @param awaitingInput 快照或剩余待决卡证明本轮仍 park；一旦执行继续必须恢复 false
     */
    async resumeSnapshotStream(runId: string, lastSeq: number, hostMessageId?: string, awaitingInput = false): Promise<void> {
      // Task 4 引导门：续收是对话域 SSE（带身份头），无后端发号会话不建连。
      if (this.sessionId === '' && (await this.ensureSession()) === '') {
        return
      }
      // M08（single-flight）：同一 runId 只允许一条活动 SSE。已有连接正在写这一轮时**并入**它 ——
      // 不新建连接、不换轮次身份（换身份会让在跑连接的登记与回调一起失效），锚点统一为
      // 「已收最大序号」。两个恢复入口并发（reattachActive + resumeSnapshotStream）因此
      // 不会再出现"全量回放 + 增量续收"两条并存、重连预算翻倍。
      if (this.canJoinRunStream(runId)) {
        this.runSeq = recordRunSeq(this.runSeq, runId, Math.max(lastSeq, this.runSeq[runId] ?? 0))
        this.loading = true
        this.streamInterrupted = false
        const joined = hostMessageId ? this.messages.find((item) => item.id === hostMessageId) : null
        if (joined && (!joined.done || joined.runId === runId)) {
          joined.streaming = true
          joined.done = false
        }
        return
      }
      // B1 收口修（C-1）：本入口是**同一轮**的续收连接（挂起收流 → 决策后重连 / 刷新恢复），
      // 慢前端工具的结局仍属于本轮 ⇒ 不递增 `roundEpoch`（否则工具回灌会在重连后被误丢）。
      const driveToken = this.acquireRunStreamDrive(runId)
      if (driveToken === null) {
        // M08-R2：这一轮的驱动权已在别处（连接存活 / 退避 / 重连中），或预算已用尽 ⇒
        // 不再另起一条驱动。旁观入口不改写现场（loading / suspended 归持有者）。
        return
      }
      const previous = currentAbort
      this.runSeq = recordRunSeq(this.runSeq, runId, lastSeq)
      this.loading = true
      this.streamInterrupted = false
      // 红队 MINOR-1 收口：新连接 ≠ 被停止的那条连接 —— `stop()` 留下的 stopped 置位只该
      // 作用于被中止的那一轮，不能跨连接生效（否则本连接断流时 onClose 会误判"用户主动停止"
      // 而不报"结果可能不完整"）。复位点与 send() / resetSession() 对齐。
      this.stopped = false
      const controller = new AbortController()
      currentAbort = controller
      this.activeStreamRunId = runId
      // M08：记下这条连接是否按"挂起等待"语义建立 —— 这类连接不参与并入（见 canJoinRunStream）。
      this.activeStreamParked = awaitingInput
      // 无宿主（I-1「无卡挂流」）：置 null —— `handleFrame` 收到 `assistantId === undefined` 时
      // 按"本轮"处理并惰性取用当前助手消息，故 null 与 undefined 在此等价。
      activeRoundHostId = hostMessageId ?? null
      // 续收证明本轮仍有后续帧：只恢复已属于本轮的宿主活动事实，不触碰无关历史助手。
      const host = hostMessageId ? this.messages.find((item) => item.id === hostMessageId) : null
      if (host && (!host.done || host.runId === runId)) {
        host.streaming = true
        host.done = false
      }
      /**
       * R6（issue#5）：续收连接是否仍是"当前轮"（旧连接的断流提示不写进新轮）。
       * M08：连接身份 = 句柄引用 **+ 建连时的轮次纪元** —— 停止/换轮（`roundEpoch` 递增）
       * 之后，被中止的旧连接即使还在派发回调，也一律失效（不写段、不写提示、不写轮次状态）。
       */
      const streamEpoch = roundEpoch
      const isCurrentRound = (): boolean => currentAbort === controller && roundEpoch === streamEpoch
      /** 批次2：归档缺口只按**首帧**判一次（后续跳号可能是并发交错的正常现象）。 */
      let gapChecked = false
      /** M08-R2：本次生命周期的收尾事实（判"是否消耗预算"只认它）。 */
      const closeTrace: { info: { terminal: boolean; suspended?: boolean } | null } = { info: null }
      // 防双连接：**先换身份再中止旧连接** —— 旧连接的收尾此时判定为"非本轮"，
      // 其 onClose 的断流提示不会写脏新连接的现场
      previous?.abort()
      try {
        await aiApi.reattachRun(
          runId,
          {
            onFrame: (frame) => {
              if (!isCurrentRound()) {
                return
              }
              // 批次2：续收**首帧**序号断层 = 归档窗口缺口（不可完整恢复，中性）。只判一次。
              if (!gapChecked) {
                const firstSeq = frameSeq(frame)
                if (firstSeq !== null) {
                  gapChecked = true
                  this.noteArchiveGap(lastSeq ?? null, firstSeq)
                }
              }
              this.handleFrame(frame, hostMessageId)
            },
            onReconnect: (attempt, delayMs) => {
              if (!isCurrentRound()) {
                return
              }
              setNotice(this, 'ai.notice.resumeRetry', { seconds: Math.round(delayMs / 1000), attempt })
            },
            onOpen: () => {
              if (!isCurrentRound()) {
                return
              }
              // 续收连接开了 ⇒ "正在第 N 次重试"的过程结束
              clearReconnectProgressNotice(this)
            },
            onClose: (info) => {
              if (!isCurrentRound()) {
                return
              }
              closeTrace.info = info
              // 苞 B 项 3：用户主动停止不报断流（同 send 的守卫；重挂/续收路径同样适用）
              // B1（裁决 8）：挂起导致的服务端收流同样是正常收尾（多卡批次里第 2 张卡
              // 送达后就会再断一次；此时不提示"结果可能不完整"）。
              if (info.suspended) {
                return
              }
              // 批次3："准备中"不是断流 —— 准备中的自动恢复由 notePreparingState 排。
              if (this.preparingRun) {
                return
              }
              if (!info.terminal && !this.stopped) {
                this.streamInterrupted = true
                this.lastRoundOutcome = 'interrupted'
                setNotice(this, 'ai.notice.interruptedSuspended', {
                  reason: info.reason ?? translate('ai.notice.reasonInterrupted')
                })
                // 2026-10-29 收口：续收耗尽同样要封口（断流 ≠ 仍在生成）。
                // 复审返修（M-4）：把本连接的 runId 一并给出 —— 无宿主 id 的"无卡挂流"
                // 只能封本 run 的宿主，不得回落到别的未收尾助手消息。
                this.sealInterruptedHost(hostMessageId, runId)
              }
            }
          },
          { signal: controller.signal, lastSeq, awaitingInput }
        )
      } finally {
        if (isCurrentRound()) {
          // 不置 suspended = false：挂起卡状态由帧/投影决定，done 帧的 handler 会自己清
          this.loading = false
          currentAbort = null
          activeRoundHostId = null
          this.activeStreamRunId = null
          this.activeStreamParked = false
        }
        // M08-R2：驱动收尾 —— 连接结束不等于放弃这一轮。只有"中断"（真断流）才消耗预算；
        // 终态 / 挂起收尾 / 准备中 / 用户停止 / 被新驱动接管都不消耗。
        const closeInfo = closeTrace.info
        this.settleRunStreamDrive(runId, driveToken, closeInfo !== null
          && !closeInfo.terminal
          && !closeInfo.suspended
          && !this.stopped
          && this.preparingRun === null)
      }
    },

    /**
     * B1（2026-10-20 裁决 9）：**决策 / 回灌之后带 lastSeq 重连收流**。
     *
     * <p>
     * 挂起即断（裁决 1）之后，人等人的那段时间没有连接 —— 决策送达后本轮的后续帧
     * （`confirm_decision` / `delta` / `tool_start` / `done` …）只会进归档。这里以
     * `runSeq[runId]`（前端已知的最大帧序号）为锚点重连，服务端只补 `seq > lastSeq`
     * 的差量（裁决 3/4：delta 也入档），于是"决策 → 续跑"既不重复渲染也不缺口。
     *
     * <p>
     * 复用 {@link resumeSnapshotStream}：**续写原宿主消息**（不新起助手消息、不引入任何新 UI），
     * 失败提示沿用该路径既有的 `onClose` / `onReconnect` 口径（不新增元素）。
     * 只在 `suspended` 为真时动作：非挂起轮的 POST（如 409 幂等收敛）不需要重连。
     *
     * <p><b>M-d（fix2）：帧先于 POST 时也要续收。</b>结局帧与决策同锁发出，`confirm_decision` /
     * `frontend_tool_result` 可能**先于** POST 响应抵达并把 `suspended` 清掉（"这张卡不再等人"
     * 是真话，但**本轮**仍在服务端挂起：后面还有 `req=false` 的卡要发请求帧、还有工具结果与终帧）。
     * 此时若不续收，本轮再无连接 ⇒ 后续帧与下一张卡永远不到，用户下一条消息撞 409。
     * 补救分支的判据（任一不满足即不连，**绝不盲连**）：决策前确实处于挂起（`parkedAtDecision`）、
     * 本轮没换（`activeRunId` 一致）、没被停止、当前没有在跑的连接（`loading === false`：
     * inline 前端工具回灌时流还开着，另开一条就是重复连接）、且 POST 期间没有收口
     * （`roundTerminalSeq` 未变 —— 不对终态重连）。
     *
     * @param parkedAtDecision 提交那一刻是否处于挂起（`suspended`）—— 由调用方在 await **之前**捕获
     * @param terminalSeqAtDecision 提交那一刻的 {@link roundTerminalSeq} 快照
     */
    reconnectSuspendedRun(
      runId: string,
      toolCallId: string,
      parkedAtDecision: boolean,
      terminalSeqAtDecision: number
    ): void {
      const host = this.messages.find((item) => item.parts.some(
        (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
      ))
      if (!host) {
        return
      }
      if (!this.suspended) {
        if (!parkedAtDecision || this.stopped || this.loading
          || this.activeRunId !== runId || roundTerminalSeq !== terminalSeqAtDecision) {
          return
        }
      }
      const awaitingInput = this.hasOtherPendingCards(toolCallId)
      // M08-R2：决策送达是**显式动作** —— 重新接管该 run 的驱动（重置预算；挂起语义连接
      // 不再代表后续帧）。决不能让"单飞/预算"把决策后的合法续跑吞掉。
      this.restartRunStreamDrive(runId)
      void this.resumeSnapshotStream(runId, this.runSeq[runId] ?? 0, host.id, awaitingInput)
    },

    /**
     * 发一轮消息（含渐进披露上下文注入 + 工作区动作同步）。 */
    /**
     * @param resend.skipUserRecord 截断后重发通道（R-157 收口 MAJOR-1）：调用方已把用户消息
     *   原地保留/更新并截断其后消息（编辑重跑），send 不得再追加新用户消息 ——
     *   否则旧文本与新文本并存（编辑）或同一提问重复两次（重试）。
     */
    async send(text: string, resend: { skipUserRecord?: boolean } = {}): Promise<AiSendResult> {
      const content = text.trim()
      if (!content) {
        // 空内容依旧静默（面板层已用 trim 拦过，此处只是防御）
        return { outcome: 'not-sent', userMessageId: null }
      }
      // O02：同 tick 的「停止 + 发送」—— 停止是一个决策，必须**先落地**（本地收敛同步生效，
      // 这里等 cancel 送达）再判断能不能发、是不是忙态：否则 /chat 会撞上停止尚未传播的
      // 残留 409，界面随即谎报"上一轮仍在进行中"。等待有界（见 waitForStopSettlement）。
      if (this.stopping) {
        await waitForStopSettlement()
      }
      if (this.loading) {
        // 批G · G6（用户直裁 2026-10-22）：在途轮里输入框可输入，但回车/发送**不响应且零提示**
        // —— 不弹 notice、不写占位气泡、不清草稿。用户要发就自己点停止、再点发送。
        // 仓内同一口径的既有规格：AiPanel.vue 的 onComposerKeydown 注释「流式中 preventDefault
        // 后静默返回（不发送 / 不停止 / 不提示 / 不换行）」与 composerHardDisabled 注释。
        return { outcome: 'not-sent', userMessageId: null }
      }
      // Task 4 引导门：会话必须由后端发号；拿不到号（身份未就绪/发号失败）就**不发请求**，
      // 也绝不把空 id 交给后端（前端已在入口处拒绝，服务端 identity/session 校验才有意义）。
      const sessionId = this.sessionId !== '' ? this.sessionId : await this.ensureSession()
      if (sessionId === '') {
        return { outcome: 'not-sent', userMessageId: null }
      }
      const workspace = useWorkspaceStore()
      // B1 收口修（C-1）：新消息 = 真正换轮 —— 上一轮在途的前端工具结局自此一律丢弃
      roundEpoch += 1
      // M-2b：上一轮快照恢复留下的串行队列随换轮作废（不得把旧卡补进新轮）
      this.restoredPendingQueue = []
      // AR-07：同批排队的表单同理 —— 换轮即整队作废（连同各自草稿）
      this.clearQueuedForms()
      const userMessage = resend.skipUserRecord ? null : this.recordUserMessage(content)
      const userMessageId = userMessage?.id ?? null
      const assistant = this.appendAssistantMessage()
      this.loading = true
      this.stopped = false
      clearNotice(this)
      this.streamInterrupted = false
      // 批次3：新轮不继承上一轮的压缩/准备中阶段（首帧到达前也不残留旧提示）
      this.resetCompactionProgress()
      this.clearPreparingRun()
      this.lastRoundOutcome = 'ok' // R-163 T4：新轮复位「继续」入口判据
      // 回执位是**轮次现场**：上一轮的 ui_event 回执不带到新轮（成功类导航本就不写，
      // 失败类也属于上一轮的事实 —— 见 SILENT_SUCCESS_UI_EVENTS 的边界）
      this.lastUiEventResult = null
      // 批 E · E2：新轮触发即收起上一轮的建议条（点 chip / 点建议 / 手打发都是这个入口）
      this.followupReady = false
      this.poolSaturated = null
      // 批次2：新一轮即作废上一轮的"回到进行中的回答"入口（它指的那一轮可能已经结束）
      this.busyRun = null
      // M08-R2：新轮 = 上一轮已放弃 ⇒ 释放它的驱动所有权（预算记录不再有消费者）
      this.forgetRunStreamDrive()
      // 2026-10-29 收口：新发送 = 新的轮次现场 —— 上一轮排着的自动恢复一律作废
      // （否则刷新后的首帧超时恢复可能把旧轮接回来盖到新轮上）。
      cancelAutoRecovery()
      this.autoRecoveryUsed = {}
      // 界面播报（aria-live 的一次性文案）按轮收口：上一轮的"已选择…"不带到新轮
      this.uiAnnouncement = null
      clearUiAnnouncement()
      const controller = new AbortController()
      currentAbort = controller
      activeRoundHostId = assistant.id
      this.activeStreamRunId = null // runId 由 start 帧给出（见 handleFrame 的 start 分支）
      this.activeStreamParked = false
      /** R1/R6（issue#5）：本轮是否仍是"当前轮" —— 以 AbortController 身份为准。 */
      const isCurrentRound = (): boolean => currentAbort === controller

      /** error 终帧会让 `aiApi.chat` 正常 resolve（不是 reject）；用本轮局部标记与开流前 catch 区分。 */
      let sawErrorTerminal = false
      /** 批次2（F6）：本轮是否已收到**任何**帧 —— 有产出后一律不重试（非零副作用）。 */
      let sawAnyFrame = false
      /** B1：本轮收尾是否为"挂起导致的服务端收流"（正常收尾）—— finally 据此保留 suspended。 */
      let suspendedClose = false
      /**
       * 2026-10-29 收口：首帧超时（**一帧都没有**）已排上自动恢复 ⇒ finally 让位。
       *
       * <p>为什么让位：恢复会把本轮宿主接回续收（同一条助手消息继续写）。若这里照旧复位
       * `loading` 并封口宿主，刚接上的续收就被判成"已结束"，帧只能写进新气泡。
       */
      let firstFrameRecovery = false
      /** 首帧超时（零帧 = 零副作用）的自动恢复：只按快照把**既有轮**接回来，绝不重发。 */
      const startFirstFrameRecovery = (): boolean => this.startAutoRecovery(
        'first-frame',
        async () => {
          if (this.stopped || !isCurrentRound()) {
            return true
          }
          // 2026-10-29 返修（复审 I-1）：同 `notePreparingState` —— 恢复在途期间置位恢复期标志，
          // 否则这条自动恢复路径的呈现层判据（`turnStillOpen`）仍会把恢复当成"本轮未终态"。
          this.restoringRecovery = true
          try {
            await this.restoreSnapshotGuarded()
          } finally {
            this.restoringRecovery = false
          }
          // 复审返修（M-2）：判据是**真的接回了一条连接**，不是笼统的 `loading` ——
          // `restoreInFlightRound` 会先置 `loading=true` 再因驱动预算不足直接返回；
          // 准备中的旧后端兼容分支也会对陈旧 activeRunId 置 `preparingRun + loading`。
          // 那两种都在"工作中却没有连接"，认成功会把首帧超时提示清掉、宿主继续挂在流式态。
          const attached = this.activeStreamRunId !== null
            || this.activeStreamParked
            || this.preparingRun !== null
          if (attached) {
            if (this.noticeCode === 'ai.notice.firstFrameTimeout') {
              clearNotice(this)
            }
            return true
          }
          // 没有可接回的轮：**不在这里**动连接登记 —— 那会把 `isCurrentRound()` 提前判假，
          // 让下一次尝试误读成"本轮已被替换"而报成功。收尾统一交给 `onExhausted`（结构保证）。
          return false
        },
        () => {
          // 复审返修（M-2）：耗尽必须有**结构保证**的收尾 —— 不依赖 `restoreSnapshotGuarded`
          // 内部恰好走了 `sealLeftoverInFlight`/`sealRecovery`：解禁输入（loading=false）+
          // 封口本轮宿主（不留"工作中无连接"），首帧超时提示保留为手动出口。
          if (!isCurrentRound()) {
            return
          }
          this.loading = false
          if (currentAbort === controller) {
            currentAbort = null
            activeRoundHostId = null
            this.activeStreamRunId = null
            this.activeStreamParked = false
          }
          this.sealInterruptedHost(assistant.id)
        }
      )
      const handlers: SseFrameHandlers = {
        onFrame: (frame) => {
          sawAnyFrame = true
          if (isCurrentRound() && frame.type === 'error') {
            sawErrorTerminal = true
          }
          // M-19（C2）：start 帧之前就停止过（runId 当时未知）⇒ 这条 start 带来 runId，立刻补真实
          // cancel。**不挂 isCurrentRound 门**：停止后连接仍在（C2 故意不 abort），而轮次身份判据
          // 是"连接句柄"级的，任何被替换过的句柄都会把这一帧挡在补 cancel 之外；归属改由
          // `cancelRunIfStopIntended` 的"同一宿主"判据保证（另一轮的 start 只会让旧意向作废）。
          if (frame.type === 'start' && frame.runId !== undefined && frame.runId !== '') {
            this.cancelRunIfStopIntended(frame.runId, assistant.id)
          }
          this.handleFrame(frame, assistant.id)
        },
        onReconnect: (attempt, delayMs) => {
          if (!isCurrentRound()) {
            return // R6：旧轮的迟到重连公告不写进新轮
          }
          setNotice(this, 'ai.notice.reconnect', { seconds: Math.round(delayMs / 1000), attempt })
        },
        onOpen: () => {
          if (!isCurrentRound()) {
            return
          }
          // 连接真的开了 ⇒ "正在第 N 次重连"的过程结束
          clearReconnectProgressNotice(this)
        },
        onClose: (info) => {
          // M-19（I5）：这条传输收尾仍没等到 start ⇒ 挂在它上面的停止意向再无落地机会（先于
          // 身份判定清 —— 旧传输的收尾同样要清，否则残留意向会活到 TTL）。
          this.dropStopIntentFor(controller)
          if (!isCurrentRound()) {
            return // R6：旧轮断流提示不得覆盖新轮状态
          }
          // 苞 B 项 3：用户主动停止的轮次不报断流 —— abort → terminal=false 是停止的**正常**收尾，
          // 不是断流。`stopped` 只在 stop() 置位，在每次新连接建立处（send/reattachActive/
          // resumeSnapshotStream）与 resetSession 复位 ⇒ 只影响被停止的那一轮。
          // B1（2026-10-20 裁决 8）：挂起即断 —— 服务端在卡面请求帧之后主动收流，同样不是断流。
          if (info.suspended) {
            // 正常收尾：卡还等着人决策（suspended 保持为真，由决策/终帧路径自己收口），
            // 不置 streamInterrupted、不出「结果可能不完整」（决策后由 confirm/回填路径重连补帧）。
            suspendedClose = true
            return
          }
          if (!info.terminal && !this.stopped) {
            // ADR-6：缺 done/error 终帧 = 断流，必须显式提示"结果可能不完整"
            this.streamInterrupted = true
            this.lastRoundOutcome = 'interrupted'
            // 批次3：首帧超时（连 start 都没到）与帧间静默分开说 —— 前者重发通常就能恢复，
            // 后者要提示"已生成内容保留"。45s 帧间口径本身不变。
            if (info.timeoutStage === 'first-frame') {
              setNotice(this, 'ai.notice.firstFrameTimeout')
              // 2026-10-29 收口（共用有界自动恢复）：**一个业务帧都没收到**是本轮唯一
              // 可证明"零副作用"的失败面 —— 但**不重发**同一条请求（后端可能已建轮，
              // 重发就是第二次真实副作用）；恢复只做"按 /runs/current 把既有轮接回来"。
              // 耗尽后回到既有手动出口（首帧超时提示 + 面板回稿）。
              firstFrameRecovery = !sawAnyFrame && startFirstFrameRecovery()
            } else {
              setNotice(this, 'ai.notice.interruptedKeep', {
                reason: info.reason ?? translate('ai.notice.reasonNoDone')
              })
            }
            // 断流不是结局：确认卡/表单仍在等用户动作，activeForm 与待决段必须原样保留
            //（重挂或刷新后由 restoreSuspendedSnapshot 按后端快照续上），只认终帧/取消才收口。
          }
        }
      }

      const options: SseStreamOptions = { signal: controller.signal }
      // 契约联动：上下文里带上"用户刚手点了什么"（buildContext 的 extra.recentActions）；
      // 动作只注入这一轮，构造完即清空，避免下一轮重复携带。
      const context = workspace.buildContext()
      workspace.clearRecentActions()
      // 批 F · F1（用户裁决 2026-10-22）：参数 popover 也退场，`stores/ai-model.ts` 随之删除 ——
      // 请求体**既不带 modelId 也不带 params**（模型与采样参数一律用后端配置；后端能力保留）。
      /** 收尾判定：身份已失效优先，其次用户停止 / 断流，最后才是调用方的正常/异常兜底。 */
      const settleOutcome = (fallback: AiSendOutcome): AiSendOutcome => {
        if (!isCurrentRound()) {
          return 'interrupted'
        }
        if (this.stopped) {
          return 'cancelled'
        }
        if (this.streamInterrupted) {
          return 'interrupted'
        }
        return fallback
      }
      let outcome: AiSendOutcome
      try {
        /**
         * 批次2（F6）：**开流前**瞬时失败的有界重试。
         *
         * <p>零副作用的判据是结构性的：一个帧都没收到（`sawAnyFrame === false`）⇒ 后端没有产出
         * 任何可观察的东西，重发同一条请求不会重复正文/工具副作用；重试**复用**同一条用户气泡与
         * 同一个 assistant 宿主（不追加消息）。一旦收到任何帧，退化为既有失败路径（绝不重放）。
         */
        let preStreamAttempt = 0
        for (;;) {
          try {
            await aiApi.chat(
              { sessionId, message: content, context },
              handlers,
              options
            )
            break
          } catch (error) {
            const retryable = preStreamAttempt < PRE_STREAM_RETRY_MAX
              && !sawAnyFrame
              && !this.stopped
              && isCurrentRound()
              && !controller.signal.aborted
              && isTransientPreStreamError(error)
            if (!retryable) {
              throw error
            }
            preStreamAttempt += 1
            await new Promise((resolve) => setTimeout(resolve, PRE_STREAM_RETRY_DELAY_MS))
            if (this.stopped || !isCurrentRound() || controller.signal.aborted) {
              throw error
            }
          }
        }
        outcome = settleOutcome(sawErrorTerminal ? 'error' : 'ok')
      } catch (error) {
        const busyConflict = error instanceof ApiError && error.code === ErrorCode.SESSION_BUSY
        const sessionPurged = error instanceof ApiError && error.code === ErrorCode.SESSION_PURGED
        // 批次2（ADR-5）：409 回执里的 runId/reattach 只作**手动**"回到进行中的回答"入口。
        // 不自动重挂、不轮询；持有者不可知（unknown=true / 无 runId）时不登记，绝不编造地址。
        // O02：还必须是**权威活动运行** —— 本端刚停止的那一轮、已收到终态的轮都不是
        // "上一轮仍在进行中"（那是停止尚未传播的残影 / 已结束的轮），此时只留"没发出去"的提示。
        if (busyConflict && error instanceof ApiError) {
          const busy = readBusyRunRef(error)
          // M-19（I5，R1 返修）：**不拿 409 猜归属去取消**。会话级 409 的 runId 无法证明是本端被停
          // 的那一轮（可能是另一页签刚起的新轮），猜着取消会误杀别人的轮。归属只认"同一宿主的
          // start 帧"（见 `cancelRunIfStopIntended`）—— C2 保留 /chat 传输正是为了让这条路径可达。
          // 因此这里照旧：能证明是本端已停的轮（stoppedRunId）或已终态的轮才不算"仍在进行中"。
          this.busyRun = this.isAuthoritativeBusyRun(busy) ? busy : null
        }
        /** 410 是否由本处做了本地恢复（决定收尾口径与乐观气泡的去向）。 */
        let purgedRecovered = false
        if (sessionPurged) {
          // 410 SESSION_PURGED = 服务端已销毁该 sessionId（`/chat` 开新轮前的销毁守卫）。
          // 唯一出路是**本地**换新 id：复用 adoptFreshSession（换 id + 清现场：镜像/乐观气泡/在途轮），
          // 不再 DELETE 已销毁的会话、不自动重发；用户原稿由面板既有的"失败回稿"留在输入框一处。
          if (isCurrentRound()) {
            this.adoptFreshSession()
            // 新 id 的锁归本页签（新发号必然空闲）—— 与 resetSession 同口径，别让本页签
            // 换完 id 却不持锁（否则页签生命周期的重认领/让渡对它全部失灵）。
            // Task 4：取号必须等后端发号（恢复面只到"给新会话做一次对账"）；取不到就不认领。
            const recoveredId = this.sessionId !== '' ? this.sessionId : await this.ensureSession()
            if (recoveredId !== '') {
              this.tabOwnership = 'owner'
              void this.claimSessionOwnership(recoveredId)
            }
            setNotice(this, 'ai.notice.sessionPurged')
            purgedRecovered = true
            // 2026-10-29 收口（共用有界自动恢复）：410 是**开新轮前**的销毁守卫拦下的，
            // 那一次请求零副作用 ⇒ 可以自动恢复。但恢复面**只到**"给新会话做一次对账"：
            // 绝不把用户那句话再发一遍（那是第二次真实副作用，且与面板的失败回稿叠成重复文本）。
            this.startAutoRecovery('purged', async () => {
              await this.loadHistory()
              return this.historyError === null
            })
          }
        } else if (isCurrentRound()) {
          this.handleChatError(error, assistant.id) // R6：旧轮的异常提示不写进新轮
        } else if (busyConflict) {
          // R-04 真机回归（2026-10-23）：409 与刷新恢复竞态时，本轮身份已在恢复路径上被换掉
          // ⇒ 旧守卫会把这次失败**整段吞掉**（既无提示、也不回收乐观气泡，幻影双份留在屏上并写进镜像）。
          // 失败的是"这次发送"本身而不是别的轮，故照常给一句"没发出去"。
          setNotice(this, 'ai.notice.sendFailed')
        }
        // 批G · G6：409 SESSION_BUSY 的**乐观气泡回收** —— 先把"以为发出去了"的用户气泡与
        // 空气泡撤掉，草稿只留在输入框一处（旧行为会同时留下"已发送"气泡 + 回填的草稿，
        // 形成幻影双份并随镜像持久化，实证 E）。按 id 只撤本次发送造出的两条，与轮次身份无关。
        if (busyConflict) {
          this.messages = this.messages.filter(
            (message) => message.id !== assistant.id && message.id !== userMessageId
          )
        }
        // 410 恢复换了会话 ⇒ 这次发送**确实失败**（不是"被别的轮打断"）：outcome 必须是 'error'，
        // 面板据此回稿且不把已清空的旧消息设成编辑锚点。其余情形仍按"是否还是本轮"判定。
        outcome = purgedRecovered ? 'error' : settleOutcome('error')
      } finally {
        if (isCurrentRound() && !firstFrameRecovery) {
          // R1（issue#5）：只有"仍是本轮"时才复位轮次级状态 —— 否则旧轮的收尾会把新轮抹平
          // （界面谎报空闲 + 新轮丢失本地取消句柄）
          this.loading = false
          // B1（2026-10-20 裁决 8）：挂起收流不再无条件清 suspended —— 卡还在等人，
          // 清掉会让停止按钮消失、输入区解禁（"这一轮其实没结束"被谎报成空闲）。
          if (!suspendedClose) {
            this.suspended = false
          }
          currentAbort = null
          activeRoundHostId = null
          this.activeStreamRunId = null
          this.activeStreamParked = false
        }
        const target = this.messages.find((message) => message.id === assistant.id)
        // B1：挂起收流时本轮尚未结束（决策后还要续跑），不封口消息 —— 与流派 A 的观感一致：
        // 气泡仍是流式态，终帧（done/error）一到由 handleFrame 的既有路径封口。
        // 2026-10-29 收口：首帧超时且已排上自动恢复时不封口 —— 恢复会把这条宿主接回续收，
        // 这里先封就等于把刚接上的那一轮判成结束（后续帧只能落到新气泡）。
        if (target && !target.done && !suspendedClose && !firstFrameRecovery) {
          target.streaming = false
          target.done = true
        }
      }
      return { outcome, userMessageId }
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
          // 批G · G5：**不再出现**「本轮未开始」这类技术措辞与 runId（SESSION_BUSY 的气泡整体
          // 回收，不走这里）；其余错误只在正文为空时补一句人话。
          const hasText = message.parts.some((part) => part.kind === 'text' && part.text !== '')
          // R-12：这些码已有本地化通知/错误卡，正文不再回显后端 message（en 下会中英叠加）。
          const localizedElsewhere = error instanceof ApiError
            && (error.code === ErrorCode.SESSION_BUSY
              || error.code === ErrorCode.AI_UNAVAILABLE
              || error.code === ErrorCode.AI_REDIS_UNAVAILABLE
              || error.code === ErrorCode.AI_KEY_MISSING
              || error.code === ErrorCode.AI_KEY_UNREADABLE
              || error.code === ErrorCode.NO_MODEL_CONFIGURED)
          const literal = error instanceof Error && !localizedElsewhere
            ? translate('ai.panel.sendNotSentInline', { message: error.message })
            : null
          if (literal !== null && !hasText) {
            const part = openStreamPart(message, 'text')
            part.text = literal
            part.streaming = false
            deriveProjections(message)
          }
        }
      }
      // R-165 S5c：错误卡族（demo v7.7 裁决区 08）—— 内联于**出错那一轮的消息**（非顶部固定）。
      // 分类见 utils/error-card.ts；已有专属 Alert 的码返回 null（不重复出卡）。
      let cardAttached = false
      if (assistantId) {
        const card = error instanceof ApiError
          ? buildErrorCard(error.code, error.message)
          : buildErrorCard('', error instanceof Error ? error.message : translate('ai.notice.chatRequestFailed'))
        const target = this.messages.find((item) => item.id === assistantId)
        // 批次2：机器码随消息落镜像（即使该码不出卡，刷新后也还能区分失败类型）
        if (target) {
          target.code = error instanceof ApiError ? error.code : null
        }
        if (target && card !== null) {
          target.errorCard = card
          cardAttached = true
          // AR-13：关键错误已由错误卡承载，通知条不再重复同一件事。
          clearNotice(this)
        }
      }
      if (!(error instanceof ApiError)) {
        if (cardAttached) {
          return
        }
        if (error instanceof Error) {
          setRawNotice(this, error.message, 'error')
        } else {
          setNotice(this, 'ai.notice.chatRequestFailed')
        }
        return
      }
      switch (error.code) {
        // 批次3：建连超时（POST 已发出但响应头未回）与首帧超时是两件事，必须分开告知。
        case SSE_TIMEOUT_CODES.connect: {
          setNotice(this, 'ai.notice.connectTimeout')
          break
        }
        case ErrorCode.SESSION_BUSY: {
          // 批G · G5/G6：不再暴露 runId / "重挂"等实现概念 —— 用户看到的只有"这条没发出去"。
          // 乐观气泡的回收在 send() 的 catch 里做（这里只管文案与现场标记）。
          setNotice(this, 'ai.notice.sendFailed')
          break
        }
        case ErrorCode.SUSPEND_POOL_SATURATED: {
          const body = error.bodyAs<SuspendPoolSaturated>()
          this.poolSaturated = body
          if (body) {
            setNotice(this, 'ai.notice.poolSaturatedWithSize', { size: body.poolSize })
          } else {
            setNotice(this, 'ai.notice.poolSaturated')
          }
          break
        }
        case ErrorCode.AI_UNAVAILABLE:
          // R-12：不再是"模板 + 后端原文"（en 下会中英叠加）；整句走本地词表并指向设置页。
          setNotice(this, 'ai.notice.aiDisabled')
          void this.probeHealth()
          break
        case ErrorCode.AI_KEY_MISSING:
          // R-12：无 key ⇒ 专门错误卡（见 utils/error-card.ts），不打后端中文原文。
          if (!cardAttached) {
            setNotice(this, 'ai.notice.keyMissing')
          }
          void this.probeHealth()
          break
        case ErrorCode.AI_KEY_UNREADABLE:
          if (!cardAttached) {
            setNotice(this, 'ai.notice.keyUnreadable')
          }
          void this.probeHealth()
          break
        case ErrorCode.NO_MODEL_CONFIGURED:
          // 默认目标缺模型/绑定模型失效：专门错误卡，不打后端中文原文。
          if (!cardAttached) {
            setNotice(this, 'ai.notice.noModelConfigured')
          }
          void this.probeHealth()
          break
        case ErrorCode.AI_REDIS_UNAVAILABLE:
          setNotice(this, 'ai.notice.redisUnavailable')
          void this.probeHealth()
          break
        // 批③ 新增的 /chat 稳定码（模型配置面）：前端按 code 查同一份词表，
        // 不回显后端中文 message（i18n 口径）。
        // 批 E · E1：模型选择已退场，不再刷新「可用模型清单」（清单的唯一消费者是选择器）。
        case 'MODEL_NOT_FOUND':
        case 'INVALID_PARAM':
        case 'PARAMS_LOCKED':
        case 'PROVIDER_GONE': {
          if (!cardAttached) {
            const key = aiConfigErrorKey(error.code)
            if (key) {
              setNotice(this, key)
            } else {
              setRawNotice(this, error.message, 'error')
            }
          }
          break
        }
        default:
          if (!cardAttached) {
            setRawNotice(this, error.message, 'error')
          }
      }
    },

    /**
     * 静默接管一个既有轮（批G · G2/G5）：复用宿主消息、全量/增量回放、**不出现任何 UI 文案**。
     *
     * <p>
     * 旧形态是「一键重挂」：新建一条助手消息 + 永久占位文案「（正在重挂进行中的轮次…）」+
     * 顶部横幅 + 带 runId 的提示。批G 起这条路只剩一个调用方 —— 刷新恢复（G2），
     * 用户看不到"重挂"这个概念：宿主是镜像里那条还没收尾的助手消息，首帧直接续写它。
     *
     * <p>
     * `runId` 参数做**类型防御**：UI 侧若把点击事件当参数传进来（形如 `[object PointerEvent]`），
     * 会把 reattach 打到 404 上（S4.4c E6 实测发现的缺陷），故非字符串一律忽略。
     *
     * DC-14 T6：带上该 runId 已收到的最大帧序号（`options.lastSeq` 优先）—— 服务端只补发
     * `seq > lastSeq` 的增量；刷新恢复走 `lastSeq = 0`（全量重放最稳：镜像正文本就滞后）。
     */
    async reattachActive(
      runId?: string,
      options: { hostMessageId?: string; lastSeq?: number } = {}
    ): Promise<void> {
      // Task 4 引导门：SSE 重挂带身份头与会话身份，没有后端发号的会话就不建连。
      if (this.sessionId === '' && (await this.ensureSession()) === '') {
        return
      }
      const explicit = typeof runId === 'string' && runId.trim() !== '' ? runId : null
      const targetRunId = explicit ?? this.activeRunId
      if (!targetRunId) {
        return // 没有可接管的轮：静默（不写任何技术提示）
      }
      // M08（single-flight）：同一 runId 已有活动 SSE 时**并入**，不建第二条连接 ——
      // 也不递增 `roundEpoch`、不作废快照/表单队列：那会让在跑连接的登记与回调一起失效
      // （"接管"只是指回同一个轮，不是换轮次身份）。锚点统一为「已收最大序号」。
      if (this.canJoinRunStream(targetRunId)) {
        const joinedSeq = options.lastSeq ?? this.runSeq[targetRunId] ?? 0
        this.runSeq = recordRunSeq(this.runSeq, targetRunId, Math.max(joinedSeq, this.runSeq[targetRunId] ?? 0))
        this.loading = true
        this.streamInterrupted = false
        const joinedHost = options.hostMessageId
          ? this.messages.find((item) => item.id === options.hostMessageId)
          : null
        if (joinedHost) {
          joinedHost.runId = targetRunId
          joinedHost.streaming = true
          joinedHost.done = false
        }
        return
      }
      // M08-R2：驱动权按 runId 独占（连接存活 / 退避 / 重连都算同一个驱动），预算跨连接累计。
      // 拿不到 ⇒ 这一轮已在别处驱动或预算已用尽：**在动任何现场之前**退出（不换轮次身份、
      // 不作废队列、不新建气泡），否则被拒绝的入口会把在跑驱动的现场改坏。
      const driveToken = this.acquireRunStreamDrive(targetRunId)
      if (driveToken === null) {
        return
      }
      // B1 收口修（C-1）：接管 = 换轮次身份 —— 在途的旧轮前端工具结局丢弃
      roundEpoch += 1
      // M-2b：接管走实时帧重建，快照串行队列作废（防旧队列在重连后复读旧卡）
      this.restoredPendingQueue = []
      // AR-07：接管 = 换轮次身份 —— 同批表单队列一并作废（重建由实时帧/快照重新给出）
      this.clearQueuedForms()
      const previous = currentAbort
      const lastSeq = options.lastSeq ?? this.runSeq[targetRunId]
      const host = options.hostMessageId
        ? this.messages.find((item) => item.id === options.hostMessageId)
        : [...this.messages].reverse().find((item) => item.role === 'assistant' && !item.done)
          ?? [...this.messages].reverse().find((item) => item.role === 'assistant')
      const assistant = host ?? this.appendAssistantMessage('')
      // 批次2：重挂的宿主就是这一 run 的消息 —— 归属必须钉住（后续重试按 message.runId 定位）
      assistant.runId = targetRunId
      this.loading = true
      this.streamInterrupted = false
      // 红队 MINOR-1 收口：新连接 ≠ 被停止的那条连接 —— `stop()` 留下的 stopped 置位只该
      // 作用于被中止的那一轮，不能跨连接生效
      this.stopped = false
      clearNotice(this)
      const controller = new AbortController()
      currentAbort = controller
      this.activeStreamRunId = targetRunId
      // M08：接管连接不是挂起等待语义（续跑帧会经它送达）
      this.activeStreamParked = false
      activeRoundHostId = assistant.id
      /**
       * R1/R6（issue#5）：本次接管是否仍是"当前轮"（以 AbortController 身份为准）。
       * M08：再加**建连时的轮次纪元** —— 停止/换轮之后，被中止的旧连接即使还在派发回调
       * （帧可能已经读到内存里），也一律失效，不得写段、写提示或写轮次状态。
       */
      const streamEpoch = roundEpoch
      const isCurrentRound = (): boolean => currentAbort === controller && roundEpoch === streamEpoch
      /** 批次2：归档缺口只按**首帧**判一次（续收锚点 = lastSeq）。 */
      let gapChecked = false
      /** 批次3：探测确认 runId 不存在 ⇒ 已封口，onClose 不得再报"断流"。 */
      let unavailableNotified = false
      /** M08-R2：本次生命周期的收尾事实（判"是否消耗预算"只认它）。 */
      const closeTrace: { info: { terminal: boolean; suspended?: boolean } | null } = { info: null }
      // R5②（issue#5）：连点/重复接管先中止既有连接（与 resumeSnapshotStream#防双连接同款）。
      // **先换身份再中止**：旧连接的收尾据此判定"非本轮"，不会把断流/取消提示写脏新连接现场。
      previous?.abort()
      try {
        await aiApi.reattachRun(
          targetRunId,
          {
            onPreparing: (probe) => {
              if (!isCurrentRound()) {
                return
              }
              // 批次3：202 准备中 —— 可恢复，不是断流；不暴露 runId。
              // 2026-10-29 收口：同一条落点排有界自动恢复 —— 这条连接若在准备中收掉，
              // 没有恢复链的话宿主会永远停在"在流"，而面板并没有可点的重试按钮。
              this.notePreparingState(probe.runId, probe.elapsedMs)
            },
            onUnavailable: () => {
              if (!isCurrentRound()) {
                return
              }
              unavailableNotified = true
              this.clearPreparingRun()
              this.sealLeftoverInFlight()
            },
            onFrame: (frame) => {
              if (!isCurrentRound()) {
                return
              }
              if (!gapChecked) {
                const firstSeq = frameSeq(frame)
                if (firstSeq !== null) {
                  gapChecked = true
                  this.noteArchiveGap(lastSeq ?? null, firstSeq)
                }
              }
              this.handleFrame(frame, assistant.id)
            },
            onReconnect: (attempt, delayMs) => {
              if (!isCurrentRound()) {
                return
              }
              setNotice(this, 'ai.notice.resumeRetry', { seconds: Math.round(delayMs / 1000), attempt })
            },
            onOpen: () => {
              if (!isCurrentRound()) {
                return
              }
              // 连接真的开了 ⇒ "正在第 N 次重试"的过程结束（否则一条正常工作的连接上一直说重连）
              clearReconnectProgressNotice(this)
            },
            onClose: (info) => {
              if (!isCurrentRound()) {
                return
              }
              closeTrace.info = info
              // 苞 B 项 3：用户主动停止不报断流（同 send 的守卫；接管/续收路径同样适用）
              // B1（裁决 8）：挂起即断导致的收流是正常收尾 —— 不报断流（决策后另有显式重连）。
              if (info.suspended) {
                return
              }
              // 批次3：准备中是可恢复阶段，不按断流提示（准备中的自动恢复由 notePreparingState 排）
              if (this.preparingRun) {
                return
              }
              // 批次3：探测已确认 runId 不存在 ⇒ 封口已完成，不再叠一条"断流"。
              if (unavailableNotified) {
                return
              }
              if (!info.terminal && !this.stopped) {
                this.streamInterrupted = true
                this.lastRoundOutcome = 'interrupted'
                setNotice(this, 'ai.notice.interruptedKeepShort', {
                  reason: info.reason ?? translate('ai.notice.reasonInterrupted')
                })
                // 2026-10-29 收口：断流 ≠ 仍在生成 —— 旧实现只提示不封口，宿主会一直
                // `done=false + streaming=true`（光标永远在闪、界面说"还在跑"）。
                // 表单/确认卡与待决段照旧保留（那仍是用户的动作面）。
                this.sealInterruptedHost(assistant.id)
              }
            }
          },
          { signal: controller.signal, lastSeq }
        )
      } catch {
        // 静默：接管失败的现场由当前画面承担（refresh 后仍可再试），不写技术文案
      } finally {
        if (isCurrentRound()) {
          // R1 同型（issue#5）：被后一次接管/新轮取代的旧连接，其收尾不得复位新轮状态
          // 批次3：准备中仍在等快照 ⇒ 保持"有在途轮"（输入区禁用），直到重试/终态。
          this.loading = this.preparingRun !== null
          this.suspended = false
          currentAbort = null
          activeRoundHostId = null
          this.activeStreamRunId = null
          this.activeStreamParked = false
        }
        // M08-R2：驱动收尾 —— 连接结束不等于放弃这一轮（所有权与预算按 run 保留）。
        // 只有真断流才消耗预算：终态 / 挂起收尾 / 准备中 / 判死 / 用户停止都不消耗。
        const closeInfo = closeTrace.info
        this.settleRunStreamDrive(targetRunId, driveToken, closeInfo !== null
          && !closeInfo.terminal
          && !closeInfo.suspended
          && !this.stopped
          && !unavailableNotified
          && this.preparingRun === null)
      }
    },

    // ── R-166：确认门提交态与权威校正（口径③/④） ─────────────────────────────

    /** 撤某挂起条目的提交态（结局落定 / 失败回退）。 */
    clearGateSubmission(toolCallId: string): void {
      disarmGateSync(toolCallId)
      if (this.gateSubmissions[toolCallId] === undefined) {
        return
      }
      const next = { ...this.gateSubmissions }
      delete next[toolCallId]
      this.gateSubmissions = next
    },

    /** 撤全部提交态（轮终态 / 会话重置；计时器随之中和）。 */
    clearGateSubmissions(): void {
      disarmAllGateSync()
      // 批4-D：勾选态与提交态同寿 —— 轮/会话收口时一并清（勾选不得跨轮沿用）
      this.clearGateAcks()
      if (Object.keys(this.gateSubmissions).length === 0) {
        return
      }
      this.gateSubmissions = {}
    },

    // ── 批4-D：不可撤销确认卡的勾选态（toolCallId 粒度） ──────────────────────

    /** 勾选（面板 checkbox 的 checked=true）。 */
    ackGate(toolCallId: string): void {
      if (this.gateAcks[toolCallId] === true) {
        return
      }
      this.gateAcks = { ...this.gateAcks, [toolCallId]: true }
    },

    /** 撤某张卡的勾选（用户取消勾选 / 变更即复位）。 */
    clearGateAck(toolCallId: string): void {
      if (this.gateAcks[toolCallId] === undefined) {
        return
      }
      const next = { ...this.gateAcks }
      delete next[toolCallId]
      this.gateAcks = next
    },

    /** 撤全部勾选态（会话/轮次收口）。 */
    clearGateAcks(): void {
      if (Object.keys(this.gateAcks).length === 0) {
        return
      }
      this.gateAcks = {}
    },

    /**
     * 409 幂等拒绝后的落点（口径③）：**不写任何乐观裁决** ——
     * ① 撤掉提交前那一次乐观段写（段回 `pending` + `requiresAction`，门卡与双按钮仍在），
     * ② 提交态置 `syncing`（面板显示「已提交，同步中…」且按钮禁用），
     * ③ 等 SSE 结局帧落真实状态；调用方再按 `GATE_SYNC_GRACE_MS` 武装权威校正。
     */
    markGateSyncing(toolCallId: string, pending: Pick<PendingToolCall, 'name' | 'kind'>): void {
      const record = this.gateSubmissions[toolCallId]
      this.gateSubmissions = {
        ...this.gateSubmissions,
        [toolCallId]: {
          decision: record?.decision,
          submittedAt: record?.submittedAt ?? Date.now(),
          syncing: true
        }
      }
      const host = this.messages.find((item) => item.parts.some(
        (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
      ))
      if (!host) {
        return
      }
      this.upsertToolPart(host, {
        toolCallId,
        name: pending.name,
        kind2: pending.kind,
        status: 'pending',
        decision: undefined,
        decidedAt: undefined,
        requiresAction: true
      })
      deriveProjections(host)
    },

    /**
     * 409 后武装权威校正计时器（首次 `GATE_SYNC_GRACE_MS`，之后按指数退避，见
     * {@link GATE_SYNC_MAX_ATTEMPTS}）。
     *
     * @param attempt 已完成的校正轮次（0 = 首次宽限）
     */
    armGateSync(toolCallId: string, attempt = 0): void {
      disarmGateSync(toolCallId)
      gateSyncAttempts.set(toolCallId, attempt)
      gateSyncTimers.set(toolCallId, setTimeout(() => {
        gateSyncTimers.delete(toolCallId)
        void this.syncGateFromAuthority(toolCallId)
      }, GATE_SYNC_GRACE_MS * 2 ** attempt))
    },

    /**
     * 拉权威状态校正门卡（口径③的唯一校正口）。
     *
     * 数据源 = `GET /api/ai/runs/current`，落**真实裁决回执**（APPROVED→已批准 / EXECUTED→
     * 已批准（执行成功） / REJECTED→已拒绝 / TIMEOUT→已超时 / CANCELLED→已取消（批G · S13：
     * 用户停止是一次决策，与 confirm_decision(decision=cancel) 帧同口径） /
     * BLOCKED→越界，不进审批只读卡 —— 2A 批裁决 §9 K2 的门卡四态由此增为五态）。
     *
     * 条目仍未定（PENDING）、条目缺失、取数失败或轮次已不存在时 ⇒ **绝不伪造裁决**，
     * 但也不无限等待：走 {@link retryOrGiveUpGateSync} 有界重试，用尽即解锁（FE-17）。
     */
    async syncGateFromAuthority(toolCallId: string): Promise<void> {
      // Task 4 返修 I1：409 校正计时器可能落在身份切换/发号失败窗口 —— 空号一律早退，
      // 绝不给 `/runs/current?sessionId=` 发空 id 请求。
      const sid = this.sessionId !== '' ? this.sessionId : await this.ensureSession()
      if (sid === '') {
        this.retryOrGiveUpGateSync(toolCallId)
        return
      }
      let current: AiRunCurrent
      try {
        current = await aiApi.runsCurrent(sid)
      } catch {
        // 取数不可用：不写任何推测性裁决（重试/解锁口径同"仍未定"）
        this.retryOrGiveUpGateSync(toolCallId)
        return
      }
      if (this.sessionId !== sid) {
        // 取数窗口内换了会话：旧快照不得写回（同 restoreSuspendedSnapshot 的身份对账）
        return
      }
      const entry = Array.isArray(current?.pendingEntries)
        ? current.pendingEntries.find((item) => item.toolCallId === toolCallId)
        : undefined
      const receipt = entry ? gateReceiptOfStatus(entry.entryStatus) : null
      if (receipt) {
        // M-a（fix2）：同一份 `pendingEntries` 一并交给校正口 —— 补下一张前必须先按它分流
        this.applyGateReceipt(
          toolCallId,
          receipt,
          Array.isArray(current?.pendingEntries) ? current.pendingEntries : undefined
        )
        return
      }
      this.retryOrGiveUpGateSync(toolCallId)
    },

    /**
     * 权威校正的收尾（FE-17）：仍有余量 ⇒ 退避再试；用尽 ⇒ **解锁提交态**并如实提示。
     *
     * <p>为什么解锁是安全的：409 DUPLICATE 表示该 toolCallId 已有决策，重试只会再吃一次
     * 幂等拒绝（不会把已批准的翻成拒绝）。锁死按钮则让用户连"再试一次/再确认"都做不到。
     */
    retryOrGiveUpGateSync(toolCallId: string): void {
      if (this.gateSubmissions[toolCallId] === undefined) {
        // 提交态已被别的路径收口（结局帧 / 会话重置 / 终态）：不再重试
        gateSyncAttempts.delete(toolCallId)
        return
      }
      const attempt = (gateSyncAttempts.get(toolCallId) ?? 0) + 1
      if (attempt < GATE_SYNC_MAX_ATTEMPTS) {
        setNotice(this, 'ai.notice.decisionPending')
        this.armGateSync(toolCallId, attempt)
        return
      }
      this.clearGateSubmission(toolCallId)
      setNotice(this, 'ai.notice.decisionPending')
    },

    /**
     * 本轮是否还有**别人**在等人（人围栏判据的唯一实现）。
     *
     * <p>FE-22：此前只数 `pendingConfirms`（CONFIRM 段）。一轮里同时有确认门 + 前端卡
     * （生成式表单 / 上传交接 / 表格预览）时，决掉确认门就把 `suspended` 置 false ——
     * ① 输入区在还有卡等人时被解锁；② 后续那张非 CONFIRM 卡提交后
     * `reconnectSuspendedRun` 因 `suspended=false` 早退、`revealNextRestoredPendingCard`
     * 同样早退 ⇒ 本轮再无连接、队列里的下一张永不出现，下一次发送撞 409。
     *
     * <p>判据只看"是否仍在等动作"（`requiresAction` + `status='pending'` + 人围栏两类 kind），
     * 不新建任何投影：BACKEND 条目后端直接执行、从不弹卡，自然不计入。
     *
     * <p><b>I-1（审查）：还必须计入"尚未建段"的恢复队列。</b>`restoreSuspendedSnapshot` 只把
     * 第一张建成段，其余进 `restoredPendingQueue` —— 在 `revealNextRestoredPendingCard` 之前
     * 它们没有段，只看段会漏判。于是当 `confirm_decision` 帧**先于** confirm POST 响应到达
     * （合法时序：帧与决策同锁发出）时，帧 handler 看到"没有别的段"就把 `suspended` 清掉，
     * POST 返回后 `reconnectSuspendedRun` / `revealNextRestoredPendingCard` 双双早退 ⇒
     * 不重连、第二张永不出现、输入区被解锁而后端这一轮仍 SUSPENDED。
     * 队列里只剩已决条目时不计入（`revealNext` 会跳过并清队，不会造成永久锁）。
     */
    hasOtherPendingCards(toolCallId: string): boolean {
      const hasPendingPart = this.messages.some((message) => message.parts.some(
        (part): boolean => part.kind === 'tool'
          && part.toolCallId !== toolCallId
          && part.requiresAction === true
          && part.status === 'pending'
          && (part.kind2 === 'CONFIRM' || part.kind2 === 'FRONTEND')
      ))
      if (hasPendingPart) {
        return true
      }
      // ② 计划已从快照恢复、但还没轮到建段的卡（串行呈现的队列）
      if (this.restoredPendingQueue.some((entry) => !this.hasDecidedPendingResidue(entry.toolCallId))) {
        return true
      }
      // ③ AR-07：同批排队、还没轮到的表单（"人在等"的第三类载体 —— 与段、恢复队列同判据）
      return this.queuedForms.some((entry) => !this.hasDecidedPendingResidue(entry.toolCallId))
    },

    /**
     * 挂起态收口的唯一判据：本次结局只清自己这张卡 —— 本轮还有**任何**待人卡
     * （确认门 / 前端卡）时保持 `suspended`。调用方必须**先**把目标卡落终态再调用
     * （终态段 `requiresAction=false`，自然不计入）。
     */
    clearSuspendedIfNoOtherConfirms(toolCallId: string): void {
      if (!this.hasOtherPendingCards(toolCallId)) {
        this.suspended = false
      }
    },

    /**
     * 把权威裁决落成段终态 + 回执元数据，并撤提交态。
     * 段侧 first-wins：已终态段（含乐观写后已非 `pending`）不覆盖。
     *
     * @param authorities 触发本次校正的**同一份** `runs/current.pendingEntries`（可选）——
     *   用于在补下一张之前先校正恢复队列（M-a，见 {@link reconcileRestoredQueueWithAuthority}）
     */
    applyGateReceipt(
      toolCallId: string,
      receipt: { decision?: 'approved' | 'rejected' | 'cancel'; status: ToolRunStatus },
      authorities?: readonly AiRunCurrentEntry[]
    ): void {
      for (const message of this.messages) {
        const part = findToolPart(message, toolCallId)
        if (!part || part.kind !== 'tool') {
          continue
        }
        if (part.status !== 'pending' && !part.requiresAction) {
          continue
        }
        this.upsertToolPart(message, {
          toolCallId,
          name: part.name,
          kind2: part.kind2,
          status: receipt.status,
          decision: receipt.decision ?? part.decision,
          // 权威快照不带决策时刻：只有本次真出了裁决才盖本地时刻（沿用旧口径），否则不动原值
          ...(receipt.decision !== undefined && part.decidedAt === undefined ? { decidedAt: Date.now() } : {}),
          requiresAction: false
        })
        deriveProjections(message)
      }
      this.clearGateSubmission(toolCallId)
      // M-a（fix2）：先用**同一份**权威快照校正恢复队列（已非 PENDING 的条目按真实状态分流），
      // 再补卡 —— 否则会把权威已决的那张补成可点卡（点下去必 409，再落到锁死链）。
      if (authorities !== undefined) {
        this.reconcileRestoredQueueWithAuthority(authorities)
      }
      // I-1 收容：409 → 权威校正拿到真实裁决（`syncGateFromAuthority` 是唯一调用点）时，
      // 决策已经落地，与 confirm() 成功路径同口径把恢复队列里的下一张补上 ——
      // 否则 `suspended` 会因"队列里还有人"而保持为真，却没有任何卡可点（输入区锁死无入口）。
      // 这里不主动重连：本轮仍处于挂起语义，用户决策那一张时 `submitFrontendToolResult` 会续流；
      // 人等人期间本来就没有连接（park 非阻塞语义）。
      const runId = this.activeRunId
      if (runId) {
        this.revealNextRestoredPendingCard(runId)
      }
      // 段已落终态后才计数：仅当无其它待决卡（含尚未建段的队列）时才撤挂起态
      this.clearSuspendedIfNoOtherConfirms(toolCallId)
    },

    /**
     * M-a（fix2）：用**同一份** `runs/current.pendingEntries` 校正恢复队列，避免 409 校正补出一张
     * 权威已决的卡（点下去必 409，再落到"锁死且无卡可点"）。
     *
     * <p>按权威条目的**真实状态**分流，不是简单丢弃：
     * <ul>
     * <li>`PENDING` / 快照未列该条目 / 无法识别的状态 ⇒ 原样保留（信息不足不猜，语义不变）；</li>
     * <li>`APPROVED`（已裁决、尚未执行）⇒ **仍要展现**，但按帧同口径落 `running` + `requiresAction=false`
     * —— 一张"已批准、执行中"的只读卡。`running` 在 `isFrameDecidedPendingStatus` 里**不是**已决，
     * 故不能当"已决"丢掉（丢掉等于把一条正在执行的条目从界面抹掉）；</li>
     * <li>真终态（`EXECUTED` / `REJECTED` / `TIMEOUT` / `BLOCKED` 经 `gateReceiptOfStatus`，
     * `FRONTEND_RESULT` / `FRONTEND_CANCELLED` / `CANCELLED` 等经 `frameStatusToPendingStatus`）
     * ⇒ 出队且**不重建**：该条目已收口，
     * 没有任何需要用户动作的面（重建只会得到一张点了必 409 的卡）。</li>
     * </ul>
     *
     * <p>本地已有段的条目一律不动：它归实时帧路径（`hasDecidedPendingResidue` / first-wins）管，
     * 这里只处置"尚未建段"的队列条目。
     */
    reconcileRestoredQueueWithAuthority(authorities: readonly AiRunCurrentEntry[]): void {
      if (this.restoredPendingQueue.length === 0 || authorities.length === 0) {
        return
      }
      const byId = new Map(authorities.map((entry) => [entry.toolCallId, entry] as const))
      const remaining: AiRunCurrentEntry[] = []
      for (const entry of this.restoredPendingQueue) {
        const materialized = this.messages.some((message) => message.parts.some(
          (part): boolean => part.kind === 'tool' && part.toolCallId === entry.toolCallId
        ))
        if (materialized) {
          remaining.push(entry)
          continue
        }
        const authority = byId.get(entry.toolCallId)
        if (!authority || authority.entryStatus === 'PENDING') {
          remaining.push(entry)
          continue
        }
        const receipt = gateReceiptOfStatus(authority.entryStatus)
        const status = receipt?.status ?? frameStatusToPendingStatus(authority.entryStatus)
        if (!status) {
          // 快照给了无法识别的状态：不猜（保留原语义，交给后到的帧/下次快照收敛）
          remaining.push(entry)
          continue
        }
        if (status === 'running') {
          // APPROVED：已裁决、执行中 —— 展现，但只能是不等动作的只读卡
          this.materializeRestoredDecidedCard(entry, { decision: receipt?.decision ?? 'approved', status })
          continue
        }
        if (isFrameDecidedPendingStatus(status)) {
          continue
        }
        remaining.push(entry)
      }
      this.restoredPendingQueue = remaining
    },

    /**
     * M-a（fix2）：把一条**权威已裁决、尚未执行**的恢复条目落成只读卡（`running`）。
     *
     * <p>宿主口径与 `rebuildPendingCard` 一致（有段用段宿主，否则末条助手消息，都没有才新建），
     * 但**不**武装表单 / 上传交接 / 预览 —— 那些单例只服务"等用户动作"的卡；这张卡不能点。
     */
    materializeRestoredDecidedCard(
      entry: AiRunCurrentEntry,
      receipt: { decision?: 'approved' | 'rejected' | 'cancel'; status: ToolRunStatus }
    ): void {
      let host = this.messages.find((item) => item.parts.some(
        (part): boolean => part.kind === 'tool' && part.toolCallId === entry.toolCallId
      )) ?? [...this.messages].reverse().find((item) => item.role === 'assistant') ?? null
      if (!host) {
        host = this.appendAssistantMessage('')
      }
      // APPROVED 表示本轮仍在执行，宿主不能被恢复路径永久封成完成态。
      host.streaming = true
      host.done = false
      this.upsertToolPart(host, {
        toolCallId: entry.toolCallId,
        name: entry.name,
        kind2: entry.kind === 'CONFIRM' ? 'CONFIRM' : 'FRONTEND',
        status: receipt.status,
        ...(receipt.decision !== undefined ? { decision: receipt.decision } : {}),
        decidedAt: Date.now(),
        requiresAction: false
      })
      deriveProjections(host)
    },

    /**
     * HITL 确认/拒绝（双路径：approved=true 放行，false 拒绝并回填原因）。
     *
     * 审批身份（调研 §3.9 ③）：`toolCallId` 由按钮所在卡片传入 —— 多卡并存时按该 id 定位
     * 宿主消息与工具段，不得回落到全局单槽 `pendingToolCall`。省略 id 时保留旧单槽兜底
     * （既有调用方的兼容路径）。
     */
    async confirm(approved: boolean, reason?: string, toolCallId?: string): Promise<void> {
      const runId = this.activeRunId
      let host: ChatMessage | null = null
      let name: string
      let kind: PendingToolCall['kind']
      if (toolCallId !== undefined) {
        let part: ToolPart | null = null
        for (const message of this.messages) {
          const found = findToolPart(message, toolCallId)
          if (found) {
            host = message
            part = found
            break
          }
        }
        // 卡片身份校验：只接受仍待决的确认门段（终态段 / 前端工具卡不得经此提交）
        if (!runId || !part) {
          setNotice(this, 'ai.notice.noPendingDecision')
          return
        }
        if (part.kind2 !== 'CONFIRM' || part.status !== 'pending' || !part.requiresAction) {
          setNotice(this, 'ai.notice.noPendingDecision')
          return
        }
        name = part.name
        kind = 'CONFIRM'
      } else {
        const pending = this.pendingToolCall as PendingToolCall | null
        if (!pending || !runId) {
          setNotice(this, 'ai.notice.noPendingDecision')
          return
        }
        toolCallId = pending.toolCallId
        name = pending.name
        kind = pending.kind
        // 宿主 = 持该 toolCallId 工具段的消息（段由 confirm_request 帧建好；缺失时不新建）
        host = this.messages.find((item) => item.parts.some(
          (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
        )) ?? null
      }
      // R-166 口径③（源头防重）：**点击即入在途表**，同 toolCallId 最多提交一次。
      // 双按钮连点 / 程序化重入在此短路（面板另据 gateSubmissions 禁用按钮，纵深两道）。
      if (this.gateSubmissions[toolCallId]) {
        setNotice(this, 'ai.notice.decisionSubmitting')
        return
      }
      // 批4-D（纵深两道）：不可撤销卡必须**已勾选**才放行「批准」—— 面板已把按钮置灰，
      // 这里拦住程序化/键盘/竞态路径（拒绝不受限：不勾选也能否掉）。
      // 勾选态只认段上的 irreversible（旧段无该键 ⇒ 与可逆卡同路，行为一字不动）。
      const targetPart = host ? findToolPart(host, toolCallId) : null
      if (approved && targetPart?.irreversible === true && this.gateAcks[toolCallId] !== true) {
        setNotice(this, 'ai.notice.ackRequired')
        return
      }
      this.gateSubmissions = {
        ...this.gateSubmissions,
        [toolCallId]: { decision: approved ? 'approved' : 'rejected', submittedAt: Date.now(), syncing: false }
      }
      // 批2（权威翻转）：确认门的乐观置位只写**段** —— 挂起卡与运行态全部由段派生。
      // 宿主 = 持该 toolCallId 工具段的消息（段由 confirm_request 帧建好；缺失时不新建）
      if (host) {
        // R-158 #10：决策元数据随段写回（回执卡徽标/时刻展示；乐观写，帧终态后为最终裁决）
        this.upsertToolPart(host, {
          toolCallId,
          name,
          kind2: kind,
          status: approved ? 'running' : 'rejected',
          decision: approved ? 'approved' : 'rejected',
          decidedAt: Date.now(),
          requiresAction: false
        })
        deriveProjections(host)
      }
      // M-d（fix2）：提交前的现场快照（帧可能先于 POST 响应抵达并清掉 suspended / 收口本轮）
      const parkedAtDecision = this.suspended
      const terminalSeqAtDecision = roundTerminalSeq
      // B2：提交在途期间的现场 —— 失败回退前要比一次，已取消/已换轮的卡不得被改回待决
      const roundAtDecision = captureRound(this.sessionId, runId)
      try {
        await aiApi.confirm({ runId, toolCallId, approved, reason: reason ?? null })
        // 批4-D：决策送达 ⇒ 勾选态复位（该卡已收尾，不留"已同意"的残影）
        this.clearGateAck(toolCallId)
        // B1（2026-10-20 裁决 9）：决策送达 ⇒ 带 lastSeq 重连收流（挂起期连接已被服务端收掉）。
        // 顺序先于 clearSuspendedIfNoOtherConfirms：后者在多卡批次里可能把 suspended 置 false，
        // 而重连的触发判据正是"这轮仍在挂起语义里"。M-d：帧先到时 `suspended` 已被清掉，
        // 由 `parkedAtDecision` + 轮次身份补回这条连接（终态/已停/已有连接一律不连）。
        this.reconnectSuspendedRun(runId, toolCallId, parkedAtDecision, terminalSeqAtDecision)
        // M-2b：快照恢复的多卡批次串行呈现 —— 先补下一张，再按"是否还有待决卡"决定挂起收口
        this.revealNextRestoredPendingCard(runId)
        // 多卡并存：本轮还有别的确认门待决时保持挂起态（只清自己这张）
        this.clearSuspendedIfNoOtherConfirms(toolCallId)
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
          // R-166 口径③：409 只说"这条已有决策"，**不说是哪一个** —— 旧口径一律硬写
          // `approved`+`running`，于是"其实已被拒/已超时"也会被显示成「已批准」（诚实性缺陷）。
          // 改为不写任何乐观裁决：撤乐观写 → 置「已提交，同步中…」瞬态 → 等 SSE 结局帧 →
          // 3 秒未达则拉 runs/current 落真实裁决回执。
          this.markGateSyncing(toolCallId, { name, kind })
          setNotice(this, 'ai.notice.decisionIdempotent')
          this.armGateSync(toolCallId)
          return
        }
        // 批次6B（N8）：后端明确"条目已不存在/已过期" ⇒ 这不是可重试失败。
        // 旧口径把段改回 pending + 原样打印 message（含 runId/toolCallId，且再点还是同一句）。
        // 现在落只读终态（expired），通知只给人话 key，不携带任何内部 ID。
        const staleKey = error instanceof ApiError ? staleGateNoticeKey(error.code) : null
        if (staleKey !== null) {
          this.clearGateSubmission(toolCallId)
          this.clearGateAck(toolCallId)
          if (host) {
            this.upsertToolPart(host, {
              toolCallId,
              name,
              kind2: kind,
              status: 'expired',
              decision: undefined,
              decidedAt: undefined,
              requiresAction: false
            })
            deriveProjections(host)
          }
          // 这张卡不再等人 ⇒ 没有别的待决卡就把挂起态收口（输入区不被一张死卡锁住）
          this.clearSuspendedIfNoOtherConfirms(toolCallId)
          setNotice(this, staleKey, {}, 'warning')
          return
        }
        // 提交失败回退：段回到待决（requiresAction=true），派生出的挂起卡随之复现；
        // 决策元数据一并清回缺省（未形成有效决策，防回执卡误显）。撤在途表 ⇒ 允许重试。
        // B2 例外：这张卡已经**收口为取消**（stop() / 终态帧在提交在途期间落下）或本轮已被
        // 换掉时不得回退 —— 把取消卡改回待决 = 挂起卡复活、输入区再被一张死卡锁住。
        const livePart = host ? findToolPart(host, toolCallId) : null
        const mayRevive = livePart !== null
          && livePart.status !== 'cancelled'
          && stillSameRound(roundAtDecision, this.sessionId, this.activeRunId)
        this.clearGateSubmission(toolCallId)
        if (host && mayRevive) {
          this.upsertToolPart(host, {
            toolCallId,
            name,
            kind2: kind,
            status: 'pending',
            decision: undefined,
            decidedAt: undefined,
            requiresAction: true
          })
          deriveProjections(host)
        }
        if (error instanceof Error) {
          setRawNotice(this, error.message)
        } else {
          setNotice(this, 'ai.notice.decisionFailed')
        }
      }
    },

    /**
     * 前端工具结果回灌（幂等：重复 → 409，未知 → 409）。
     * GF-B：cancelled=true 为取消路径（GFa 裁决 #2，可选字段，与提交共用同一入口），
     * 用户放弃 / 渲染器不可用时回灌让挂起立刻落 FRONTEND_CANCELLED，不必等超时。
     *
     * 返回结构化结果（供生成式表单区分 400 FORM_RESULT_REJECTED「挂起仍在等待，可重试」
     * 与真失败）；通用执行器路径（runFrontendTool）忽略返回值，行为不变。
     *
     * 治理 P0-1：`outcome` 为前端执行器的结局八态（`FrontendToolExecution.outcome`），仅执行器
     * 路径携带（取消路径由 `cancelled=true` 表达结局）；缺省不产该键，旧请求体形态不变。
     */
    async submitFrontendToolResult(
      toolCallId: string,
      result: string,
      source = 'http-post',
      cancelled = false,
      runId?: string,
      outcome?: ToolOutcome,
      sheetMeta?: SheetToolMeta,
      code?: string
    ): Promise<{
      ok: boolean
      duplicate?: boolean
      rejected?: string
      message?: string
      /** G-A：后端结构化错误（按 key 点亮字段） */
      issues?: FormValueIssue[]
      /** G-A：改对后可否重试（code 决定） */
      retryable?: boolean
      /** 结构化回灌体超限（FRONTEND_RESULT_TOO_LARGE）：表单回到可编辑态、保留输入 */
      tooLarge?: boolean
    }> {
      // GF-B 修复（红队问题 1）：表单回灌优先用表单创建时捕获的 runId，
      // 缺省才回退当前轮的 activeRunId（避免旧表单错配到新 run）
      const targetRunId = runId ?? this.activeRunId
      if (!targetRunId) {
        setNotice(this, 'ai.notice.missingRunId')
        return { ok: false, message: '缺少 runId' }
      }
      // M-d（fix2）：提交前的现场快照（结局帧可能先于 POST 响应抵达；inline 执行时不在挂起态）
      const parkedAtDecision = this.suspended
      const terminalSeqAtDecision = roundTerminalSeq
      try {
        await aiApi.frontendToolResult(
          cancelled
            ? { runId: targetRunId, toolCallId, cancelled: true, source }
            // 治理 P0-1：仅执行器路径带 outcome（旧形态逐字保留，不产 undefined 键）
            : {
                runId: targetRunId,
                toolCallId,
                result,
                source,
                ...(outcome !== undefined ? { outcome } : {}),
                // 治理批：只有表格工具带（旧形态逐字保留，非表格工具不产 undefined 键）
                ...(sheetMeta !== undefined ? { sheetMeta } : {}),
                // 批 A：机器码 additive（非法值在 `makeExecution` 已被闭集丢弃，这里不会产脏码）
                ...(code !== undefined ? { code } : {})
              }
        )
        // B1（2026-10-20 裁决 9）：回灌送达 ⇒ 带 lastSeq 重连收流（GF 提交/取消与即时前端工具共用）。
        // 不 await：重连要活到本轮终帧，await 会把"表单已提交"的本地收尾拖到整轮结束之后。
        // M-d：帧先到时 `suspended` 已被清掉，由 `parkedAtDecision` + 轮次身份补回这条连接；
        // inline 执行（流还开着）与终态/已停一律不连。
        this.reconnectSuspendedRun(targetRunId, toolCallId, parkedAtDecision, terminalSeqAtDecision)
        // M-2b：GF / 上传 / 表格预览（放弃）等同族决策 — 快照批次里下一张接着出现
        this.revealNextRestoredPendingCard(targetRunId)
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
          // M-b（fix2）：409 = 这张卡**已有裁决**，本页签不会再收到它的结局帧。此前的落点只收敛
          // 这张卡：恢复队列里的下一张永不出现、`suspended` 也不按真实待决重算 ⇒ 输入区锁死且
          // 屏上无可点卡（只能刷新）。与成功路径同口径补出下一张，再只按**真实待决**（段 + 队列）
          // 决定挂起收口；**不重连**（本轮仍在挂起时由那张卡的决策续流，人等人期间没有连接）。
          this.revealNextRestoredPendingCard(targetRunId)
          this.clearSuspendedIfNoOtherConfirms(toolCallId)
          setNotice(this, cancelled ? 'ai.notice.toolTerminal' : 'ai.notice.toolAlreadySubmitted')
          return { ok: false, duplicate: true }
        }
        if (error instanceof ApiError && error.code === ErrorCode.FRONTEND_RESULT_TOO_LARGE) {
          // 结构化回灌体超限（本地预检漏网 / 旧客户端 / 上限下调）：与 FORM_RESULT_REJECTED 同形，
          // 但语义是"缩短可重试"——挂起仍在等待，表单不得被吞成终态失败。
          const body = error.bodyAs<{ message?: unknown; reasons?: unknown }>()
          const reasons = body?.reasons
          const detail = Array.isArray(reasons) && reasons.length > 0
            ? reasons.map((item) => String(item)).join('；')
            : typeof body?.message === 'string' && body.message !== ''
              ? body.message
              : error.message
          return { ok: false, rejected: detail, issues: [], retryable: true, tooLarge: true }
        }
        if (error instanceof ApiError && error.code === ErrorCode.FORM_RESULT_REJECTED) {
          // 400 + status:"PENDING"：挂起仍在等待 —— 不置 notice 打断填写，由表单卡展示拒绝原因，
          // 用户改值后用同一 toolCallId 重发，或改发取消
          const body = error.bodyAs<{ reasons?: unknown; issues?: unknown; retryable?: unknown }>()
          const reasons = body?.reasons
          const detail = Array.isArray(reasons) && reasons.length > 0
            ? reasons.map((item) => String(item)).join('；')
            : error.message
          // G-A：结构化错误随拒绝体下发 —— 抽出 {key, code, message} 供表单按字段点亮
          const issues: FormValueIssue[] = Array.isArray(body?.issues)
            ? body.issues
              .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
              .map((item) => ({
                key: typeof item.key === 'string' ? item.key : '',
                code: typeof item.code === 'string' ? item.code : 'FORM_RESULT_REJECTED',
                message: typeof item.message === 'string' ? item.message : ''
              }))
            : []
          return {
            ok: false,
            rejected: detail,
            issues,
            retryable: body?.retryable !== false,
            // 兼容现行后端：FORM_RESULT_REJECTED 内层 issue code = RESULT_TOO_LARGE 时
            // 与 FRONTEND_RESULT_TOO_LARGE 同语义（体积超限 ⇒ 缩短可重试）
            ...(issues.some((issue) => issue.code === 'RESULT_TOO_LARGE') ? { tooLarge: true } : {})
          }
        }
        const message = error instanceof Error ? error.message : translate('ai.notice.toolSubmitFailed')
        setRawNotice(this, message)
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
     * 合法：交给"单活动表单"闸门（{@link presentGenerativeForm}）—— 空位即激活（AiPanel 渲染
     * FormRenderer），已有活动表单则**按到达序排队**（AR-07）；**不武装任何本地时限**
     * （去超时裁决：表单一直可填，收口只认后端结局帧 / 用户取消）；
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
      // 批2：挂起投影由段派生，args 取调用方已派生好的 pendingCall（同帧的 frontend_tool_request）
      const args = message.pendingCall?.args ?? parseToolArgs(frame.args)
      const spec = parseFormSchema(args?.form)
      const runId = frame.runId ?? this.activeRunId
      if (!spec || !runId) {
        // AR-07：原因写清**可执行的下一步** —— 模型据此重出一张可渲染、可填完的表
        const result = '表单 schema 不可渲染（形状不符白名单，或必填选择题在某个父值下没有任何可选项），'
          + '已带 cancelled:true 回灌收敛（GFa §8.3）。请重新生成一张可填写的表单：'
          + '必填的选择题必须给出可选项（选项级联要覆盖父值的每个取值）。'
        this.upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'FRONTEND',
          status: 'failed',
          result,
          requiresAction: false
        })
        deriveProjections(message)
        // C1（R1 返修）：**必须先提交、后收口** —— `submitFrontendToolResult` 成功路径的续流判据是
        // `parkedAtDecision = this.suspended`（提交那一刻的现场）。先清 suspended 会让它变 false，
        // `reconnectSuspendedRun` 直接早退 ⇒ 决策后的后续帧与下一张卡永远到不了（M-18 原本就是这么
        // 变成"挂起收口但这一轮也死了"的）。成功 ⇒ 既有的"决策后带 lastSeq 重连"照常接管；
        // 失败（回灌没送达）才按"这张卡已不在等人"收口，避免输入区被一张不可点的卡锁死。
        void this.submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true, runId ?? undefined)
          .then((outcome) => {
            if (!outcome.ok) {
              this.clearSuspendedIfNoOtherConfirms(frame.toolCallId)
            }
          })
        return
      }
      this.presentGenerativeForm(frame.toolCallId, runId, spec)
    },

    /** AR-07：此刻是否有一张**活**的活动表单（在填 / 在提交）—— "单活动表单"判据的唯一实现。 */
    hasLiveForm(): boolean {
      const form = this.activeForm
      return form !== null && (form.status === 'filling' || form.status === 'submitting')
    },

    /**
     * AR-07：把一张已解析的表单交给"单活动表单"闸门 —— **空位激活，占位排队**（到达序）。
     *
     * <p>实时帧（{@link openGenerativeForm}）与刷新恢复（{@link rebuildPendingCard}）共用本入口，
     * 这是"实时与恢复同构"的落点：两条路径对"第二张怎么办"给出同一个答案。
     * 同一 toolCallId 重复到达是幂等的（不重灌草稿、不覆盖用户正在填的内容）。
     */
    presentGenerativeForm(toolCallId: string, runId: string, form: FormSpec): void {
      if (this.activeForm?.toolCallId === toolCallId
        || this.queuedForms.some((entry) => entry.toolCallId === toolCallId)) {
        return
      }
      if (this.hasLiveForm()) {
        this.queuedForms = [...this.queuedForms, { toolCallId, runId, form }]
        return
      }
      // 去超时裁决（2026-10-20）：GF 不武装本地时限计时器 —— 表单一直可填，收口只认后端结局帧
      // / 用户主动取消。
      this.activeForm = {
        toolCallId,
        runId,
        form,
        status: 'filling',
        error: null,
        // G-B：刷新恢复 —— 同一 toolCallId 的浏览器镜像回填（草稿从不经服务器）
        values: loadFormDraft(toolCallId) ?? {},
        fieldErrors: []
      }
    },

    /**
     * AR-07：活动表单结算后按到达序补下一张（没有活动表单才补；已决条目跳过）。
     *
     * <p>为什么"已决就丢"：他端提交 / 后端超时 / 结局帧先到的那些表单已经不需要人填，补出来
     * 就是一张点不动的死卡 —— 出队清草稿才是正确的收口（取消出口不需要它）。
     */
    promoteNextForm(): void {
      if (this.hasLiveForm()) {
        return
      }
      while (this.queuedForms.length > 0) {
        const next = this.queuedForms[0] as QueuedGenerativeForm
        this.queuedForms = this.queuedForms.slice(1)
        if (this.hasDecidedPendingResidue(next.toolCallId)) {
          clearFormDraft(next.toolCallId)
          continue
        }
        this.presentGenerativeForm(next.toolCallId, next.runId, next.form)
        return
      }
    },

    /** AR-07：队列里的一张已在他处结算（结局帧 / 他端提交）⇒ 出队清草稿，不留死卡。 */
    discardQueuedForm(toolCallId: string): void {
      const hit = this.queuedForms.find((entry) => entry.toolCallId === toolCallId)
      if (!hit) {
        return
      }
      this.queuedForms = this.queuedForms.filter((entry) => entry !== hit)
      clearFormDraft(toolCallId)
    },

    /** AR-07：整队收口（换轮 / 停止 / 换会话 / 快照权威覆盖）—— 队列与各自的草稿一起清。 */
    clearQueuedForms(): void {
      for (const entry of this.queuedForms) {
        clearFormDraft(entry.toolCallId)
      }
      this.queuedForms = []
    },

    /**
     * AR-07：一张表单被结局帧结算 —— 只收这一张（连同草稿），再把队列里的下一张补上。
     *
     * <p>与 {@link dropActiveForm} 的分界：那是"整轮 / 整会话到此为止"（队列一起清），
     * 这里是"这一张决了，还有人在等"（队列留着接续）。结局帧打给**排队中**的那张时
     * （他端提交 / 超时先到）走 {@link discardQueuedForm}，不惊动正在填的那张。
     */
    settleActiveForm(toolCallId: string): void {
      const form = this.activeForm
      if (!form || form.toolCallId !== toolCallId) {
        this.discardQueuedForm(toolCallId)
        return
      }
      clearFormDraft(toolCallId)
      this.activeForm = null
      this.promoteNextForm()
    },

    /**
     * GF 优化批（G-B）：写草稿 —— **只写 sessionStorage 镜像**，不触发任何网络请求、
     * 不进模型上下文。提交 / 取消 / 终态由 {@link clearActiveFormDraft} 清理。
     */
    updateFormDraft(values: Record<string, unknown>): void {
      const form = this.activeForm
      if (!form || form.status !== 'filling') {
        return
      }
      form.values = { ...values }
      saveFormDraft(form.toolCallId, form.values)
    },

    /**
     * 收起当前表单：先清草稿镜像再置空（结局帧 / done / error / 换会话的统一出口）。
     *
     * <p>2026-10-29 收口：**停止生成不再走这里** —— 停止是"这一轮到此为止"，不是"用户放弃了
     * 这张表"，未提交草稿要留给同一 toolCallId 的恢复重建（见 {@link suspendActiveFormDrafts}）。
     *
     * <p>AR-07：同批排队的那几张也一起收（连同各自草稿）—— 这是"整轮/整会话到此为止"的出口，
     * 队列不能带着旧卡活到下一轮。只收**一张**的路径见 {@link settleActiveForm}。
     */
    dropActiveForm(): void {
      const form = this.activeForm
      if (form) {
        clearFormDraft(form.toolCallId)
      }
      this.activeForm = null
      this.clearQueuedForms()
    },

    /**
     * 2026-10-29 收口：**停止生成**时只收起表单卡，**保留未提交草稿**。
     *
     * <p>为什么与 {@link dropActiveForm} 分开：停止是"这一轮到此为止"，不是"用户放弃了这张表"。
     * 用户已经填进去的值仍在这台机器上（`cm.ai.form.draft.<toolCallId>`，既有 sessionStorage 键，
     * 不新增存储规格）—— 停止后若同一张表单被快照恢复重建成同一 toolCallId，`presentGenerativeForm`
     * 会把草稿回填进控件；清掉就等于让一次停止把用户手填的内容一起删了。
     *
     * <p>只有**明确放弃**才清草稿：取消表单（{@link cancelGenerativeForm} / {@link cancelQueuedForm}）、
     * 提交成功（{@link settleActiveForm}）、轮终态（{@link cleanupTerminalInteractionState} → dropActiveForm）、
     * 换会话（孤儿键由 {@link pruneOrphanFormDrafts} 清）。
     */
    suspendActiveFormDrafts(): void {
      this.activeForm = null
      this.queuedForms = []
    },

    /**
     * 批次6B（N10）：清**孤儿**草稿键 —— 前缀扫描 + 在场白名单，绝不整份 clear。
     *
     * <p>"在场" = 活动表单 / 排队表单 / 仍 `requiresAction` 的工具段（确认门、前端卡、待填表单）。
     * 终态卡与上一会话留下的 `cm.ai.form.draft.<toolCallId>` 都不在场 ⇒ 删除。换会话时
     * `adoptFreshSession` 先收口活动/排队表单，故白名单为空、全部清掉；接手让渡会话后由
     * `takeOverSession` 在历史载入后调用，保留新会话仍在场的那几张。
     */
    pruneOrphanFormDrafts(): void {
      const present = new Set<string>()
      if (this.activeForm) {
        present.add(this.activeForm.toolCallId)
      }
      for (const entry of this.queuedForms) {
        present.add(entry.toolCallId)
      }
      for (const message of this.messages) {
        for (const part of message.parts) {
          if (part.kind === 'tool' && part.requiresAction) {
            present.add(part.toolCallId)
          }
        }
      }
      pruneFormDrafts(present)
    },

    /**
     * 轮终态的"人围栏单例"收口（FE-25）：`done` / `error` 帧到达时调用一次。
     *
     * <p>收什么：活动表单（连同草稿镜像）、上传交接单例、表格预览计划 + 失效标记、快照恢复的
     * 串行队列、界面播报（`uiAnnouncement`）。为什么要一起收：这些单例一旦残留，就等于
     * "已结束的一轮仍能被界面推动"——上传交接会把 `frontend_tool_result` 打到收口的 run 上、
     * 预览还能再"应用"一次写入、队列会在换轮前把旧卡补进新现场；播报是**一次性的**
     * aria-live 文案，轮已结束还留着，读屏会把它当成这一轮的新事实念出来。
     * 段侧的 `requiresAction` 由 `expireResidueToolParts` 同口径收掉，故这里只清单例、不碰段。
     */
    cleanupTerminalInteractionState(): void {
      this.dropActiveForm()
      this.activeUploadHandoff = null
      this.stalePreviewToolCallId = null
      // reason='stop'：整轮结束，卡面不再需要"预览已失效"提示（同 stop() 口径）
      this.dropSheetPreview('stop')
      this.restoredPendingQueue = []
      // 2026-10-29 收口：界面播报按轮收口（模块侧与 state 侧同清，否则下一轮 `syncUiRuntimeState`
      // 又能把上一轮的播报读回来）
      clearUiAnnouncement()
      this.uiAnnouncement = null
    },

    /**
     * AI 表格操作专项：写工具预览（设计稿 §3.4 选项 A —— 确认落在前端）。
     *
     * 与 `openGenerativeForm` 同族：写工具在 `frontend_tool_request` 分支被特判，**不进**
     * `runFrontendTool`（不受 60s 预算约束，等的是人）。这里只算 plan、建卡，**不回灌**；
     * 用户点“应用/取消”后才由 {@link confirmWritePreview} / {@link cancelWritePreview} 回灌。
     */
    async openWritePreview(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): Promise<void> {
      const args = parseToolArgs(frame.args)
      const toolCallId = frame.toolCallId
      const runId = frame.runId ?? this.activeRunId ?? undefined
      // B2：解析表格上下文要 await —— 期间的现场留一份，回来若已换会话/停止则不再建卡
      const roundAtOpen = captureRound(this.sessionId, frame.runId ?? this.activeRunId ?? null)
      const fail = async (reason: string): Promise<void> => {
        this.upsertToolPart(message, {
          toolCallId,
          name: frame.name,
          kind2: 'FRONTEND',
          status: 'failed',
          result: reason,
          requiresAction: false
        }, false)
        deriveProjections(message)
        // M-18（2026-10-29）：建卡失败 ⇒ 段已是 failed + requiresAction=false（无可点卡）。
        // C1（R1 返修）：**先提交后收口** —— 成功路径靠 `parkedAtDecision = this.suspended` 走既有
        // 「决策后带 lastSeq 重连」；先清 suspended 会把续流判据打成 false（后续帧永远到不了）。
        // 只有回灌失败（决策没送达）才收口这张卡的挂起语义。
        const outcome = await this.submitFrontendToolResult(toolCallId, reason, 'frontend-executor', false, runId, 'failed')
        if (!outcome.ok) {
          this.clearSuspendedIfNoOtherConfirms(toolCallId)
        }
      }
      // 撤销的新卡不得静默挤掉一张仍在等人决策的预览卡（工具安全边界批）：那张卡的 plan 会被覆盖，
      // 用户再也点不到「应用」，而它的 toolCallId 会悬到超时 —— 宁可拒不建卡，如实让模型先收口。
      if (frame.name === 'undo_last_sheet_edit' && this.activePreview
        && this.activePreview.toolCallId !== toolCallId) {
        // i18n（本批范围）：这条回执同时进用户结果与模型回灌，按当前界面语言取文案（既有
        // `sheet.readOnlyWriteBlocked` 同模式）；不把新增中文继续堆进残留基线。
        await fail(translate('sheet.undoPreviewBusy', { tool: this.activePreview.tool }))
        return
      }
      const resolved = await resolveSheetContext(args.defCode)
      // B2：换会话 / 停止发生在这段 await 里 ⇒ 预览不得在新会话里复活：既不建卡、不上色，
      // 也不把失败结局回灌到已经收口的旧 run（后端已按取消收尾，回灌只会被幂等拒绝）。
      if (!stillSameRound(roundAtOpen, this.sessionId, this.activeRunId ?? null)) {
        return
      }
      if (!resolved.ok) {
        await fail(resolved.reason)
        return
      }
      if (resolved.ctx.readOnly) {
        await fail(sheetReadOnlyWriteBlocked())
        return
      }
      // 工具名 → 确定性计划函数（唯一分发点，切档重算也走它 —— 两条路径不可能算出不同的计划）
      const planned = planSheetWithLevel(resolved.ctx, frame.name, args, toolCallId)
      if (!planned) {
        await fail(`未识别的表格写工具 ${frame.name}：本次未做任何修改。`)
        return
      }
      if (!planned.ok) {
        await fail(planned.reason)
        return
      }
      // 旧卡的计划即将被新计划替换：先撤掉旧预览的屏幕色，并把旧 toolCallId 记为"已失效"——
      // 卡面据此变成「预览已失效 + 放弃」，而放弃键按 part.toolCallId 回灌 cancelled，
      // 不会出现"plan=null、放弃键点不动、写工具又无超时"的死键挂死（本轮 M2）。
      const previousPreview = this.activePreview && this.activePreview.toolCallId !== toolCallId
        ? this.activePreview
        : null
      if (previousPreview && previewSheetCtx) {
        clearPreviewMarks(previewSheetCtx)
      }
      this.activePreview = { ...planned.plan, runId }
      // 收口用的上下文引用（见模块级 previewSheetCtx 注释）：与 activePreview 同寿
      previewSheetCtx = resolved.ctx
      this.stalePreviewToolCallId = previousPreview ? previousPreview.toolCallId : null
      // 预览态高亮（L-23）：计数档没有逐格明细 ⇒ 这里通常是 0 格；切到明细档时再上色。
      markPreviewCells(resolved.ctx, this.activePreview)
      this.upsertToolPart(message, {
        toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status: 'pending',
        args: frame.args,
        requiresAction: true
      }, false)
      deriveProjections(message)
    },

    /** 预览文案（面板与测试共用的单一口径）。 */
    previewText(): string {
      return planPreviewText(this.activePreview)
    },

    /**
     * 重算"已应用改动"的现算部分（§5.4）：用户在表格里手改之后调用一次，卡面的
     * 「其后人工改动 N 格」才不会停在应用瞬间的 0。
     *
     * <p>由两个向导的 `onGridChanged` 在 `workspace.bumpVersion()` 之后调用 —— 那里是
     * "表格内容变了"的唯一信号源。解析不到表格（切标签/未挂载）时静默保持现状。
     */
    async refreshSheetApplied(defCode?: string): Promise<void> {
      const code = defCode ?? this.sheetApplied?.defCode ?? ''
      if (!code) {
        return
      }
      const resolved = await resolveSheetContext(code)
      if (!resolved.ok) {
        return
      }
      this.sheetApplied = sheetAppliedSummary(resolved.ctx)
    },

    // ── AI 可视可操作专项：上传交接（唯一的「等人」工具） ────────────────────────

    /**
     * `ui_request_upload` 帧到达 → 建交接卡。
     *
     * 与 `openGenerativeForm` 同族：**先校验再挂起**，任何异常路径都**带结局回灌收敛**
     * （绝不让挂起悬空）。三态分流：
     * - 只读页 / 无上传入口 ⇒ `unavailable`，直接回灌失败（不建卡）；
     * - 浏览器无 `showOpenFilePicker` ⇒ `degraded`（卡保留可重试 + 高亮入口）；
     * - 其余 ⇒ `waiting`（等用户手势）。
     */
    async openUploadHandoff(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): Promise<void> {
      const toolCallId = frame.toolCallId
      const runId = frame.runId ?? this.activeRunId ?? null
      const fail = async (result: string): Promise<void> => {
        this.upsertToolPart(message, {
          toolCallId,
          name: frame.name,
          kind2: 'FRONTEND',
          status: 'failed',
          result,
          requiresAction: false
        }, false)
        deriveProjections(message)
        // C1（R1 返修）：先提交后收口（同 openWritePreview.fail）—— 成功靠既有 reconnectSuspendedRun
        // 续流，失败才收口这张卡的挂起语义。
        const outcome = await this.submitFrontendToolResult(
          toolCallId, result, 'frontend-executor', false, runId ?? undefined, 'failed'
        )
        if (!outcome.ok) {
          this.clearSuspendedIfNoOtherConfirms(toolCallId)
        }
      }

      const validated = validateUploadArgs(parseToolArgs(frame.args))
      if (!validated.ok) {
        await fail(validated.outcome.result)
        return
      }
      // 批 A（设计稿 §6 #5）：显式传入的 ref 必须在本轮快照集内 —— 上传是唯一的「等人」入口，
      // ref 若可猜，等于给了「指向任意按钮」的能力。缺省（取区域声明的 uploadEntry）不走此闸。
      if (validated.value.ref !== null && !isRefSnapshotted(validated.value.ref)) {
        const text = `该上传入口的 ref 未在本轮界面快照中出现，上传交接未建立。`
          + `请先调用 ui_snapshot 取得当前 ref。ref=${validated.value.ref}`
        this.upsertToolPart(message, {
          toolCallId,
          name: frame.name,
          kind2: 'FRONTEND',
          status: 'rejected',
          result: text,
          requiresAction: false
        }, false)
        deriveProjections(message)
        // C1（R1 返修）：同 fail() —— 这条也不建卡（rejected 段 requiresAction=false）；先提交后收口。
        const outcome = await this.submitFrontendToolResult(
          toolCallId, text, 'frontend-executor', false, runId ?? undefined,
          'rejected', undefined, 'UI_REF_NOT_SNAPSHOTTED'
        )
        if (!outcome.ok) {
          this.clearSuspendedIfNoOtherConfirms(toolCallId)
        }
        return
      }
      const target = resolveUploadRegion(validated.value.ref)
      if (!target) {
        await fail(UPLOAD_NO_REGION_TEXT)
        return
      }
      if (target.region.readOnly?.() === true) {
        await fail(UPLOAD_READONLY_TEXT)
        return
      }
      // 高亮入口（把用户送到上传按钮；视觉零变化，只加描边 + 徽标 + 播报）
      highlightUploadEntry(target.entry)
      const pickerOk = supportsFilePicker()
      this.activeUploadHandoff = {
        toolCallId,
        runId,
        ref: target.entry,
        accept: validated.value.accept,
        status: pickerOk ? 'waiting' : 'degraded',
        reason: pickerOk ? null : translate('ai.notice.uploadPickerUnsupported'),
        filename: null
      }
      this.uiAnnouncement = pickUiAnnouncement()
      this.upsertToolPart(message, {
        toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status: 'pending',
        args: frame.args,
        requiresAction: true
      }, false)
      deriveProjections(message)
    },

    /**
     * 用户点交接卡的「选择文件」。
     *
     * **手势约束（规范级，不得重构掉）**：本方法**首个 `await` 之前**必须同步调用
     * `requestFilePicker` —— 浏览器只在用户手势的同一调用栈里给出 transient activation，
     * 首个 `await` 之后再叫会被拒（`NotAllowedError`）。区域存活判定是同步读写，故可放在它之前。
     *
     * <p>
     * **L-85**：可操作性判据**与卡面同源** = `uploadHandoffAvailability()`（不是建卡那一刻的
     * `handoff.status`）。`missing`（区域已不在当前页 / 只读页）⇒ 不打开文件对话框，
     * 直接以 `failed` + 人话原因收口。旧实现在 `region === null` 时跳过 `receiveFile` 却仍以
     * `succeeded` 回灌「已交给导入向导」= 谎报上传成功。
     */
    async chooseUploadFile(): Promise<void> {
      const handoff = this.activeUploadHandoff
      if (!handoff) {
        return
      }
      if (this.uploadHandoffAvailability() === 'missing') {
        // 不打开文件对话框：区域都没了，选了也无处可交 —— 直接如实收口（不谎报、不留死卡）。
        // A09：ref 缺省时同样按区域实况回落（缺 ref ≠ 没有落点，区域可能刚刚才注册）。
        const target = resolveUploadRegion(handoff.ref)
        const readOnly = target?.region.readOnly?.() === true
        await this.finishUploadHandoff(handoff, 'failed', readOnly ? UPLOAD_READONLY_TEXT : UPLOAD_NO_REGION_TEXT, false)
        return
      }
      const picked = requestFilePicker(handoff.accept)
      if (!picked) {
        this.activeUploadHandoff = {
          ...handoff,
          status: 'degraded',
          reason: translate('ai.notice.uploadPickerUnsupported')
        }
        this.uiAnnouncement = pickUiAnnouncement()
        return
      }
      let files: File[]
      try {
        files = await picked
      } catch (error) {
        const aborted = error instanceof Error && (error.name === 'AbortError' || /abort/i.test(error.message))
        this.activeUploadHandoff = {
          ...handoff,
          status: 'degraded',
          reason: aborted
            ? translate('ai.notice.uploadPickerCancelled')
            : translate('ai.notice.uploadPickerFailed', { message: errorText(error) })
        }
        this.uiAnnouncement = pickUiAnnouncement()
        return
      }
      const file = files[0]
      if (!file) {
        this.activeUploadHandoff = { ...handoff, status: 'degraded', reason: translate('ai.notice.uploadNoFile') }
        return
      }
      const name = uploadFileNameOf(file)
      // 选文件期间区域可能消失（用户切页 / 页面卸载）—— 同一条判据，不得谎报成功。
      // A09：ref 缺省的交接同样要按区域声明的 uploadEntry 回落 —— 否则卡面刚转 waiting，
      // 用户点下去却在这里被判成"没有入口"（同一缺陷的第二道否决）。
      const region = resolveUploadRegion(handoff.ref)?.region ?? null
      if (region === null || region.root.isConnected !== true) {
        await this.finishUploadHandoff(handoff, 'failed', UPLOAD_NO_REGION_TEXT, false)
        return
      }
      let summary = ''
      if (region.receiveFile) {
        try {
          summary = await region.receiveFile(file)
        } catch (error) {
          this.activeUploadHandoff = {
            ...handoff,
            status: 'degraded',
            reason: translate('ai.notice.uploadReceiveFailed', { message: errorText(error) })
          }
          return
        }
      }
      const tail = summary ? `；${summary}` : ''
      await this.finishUploadHandoff(
        handoff,
        'succeeded',
        `用户已选择文件「${name}」并交给导入向导${tail}`,
        false
      )
    },

    /**
     * 用户点「放弃」：`cancelled=true` 走既有 `FRONTEND_CANCELLED`，卡落终态。
     *
     * @param toolCallId 卡面所属的 toolCallId（卡面显式传入）。多张交接卡同屏时单例只记一张，
     *   不带身份会"点了这张、断的是那张"（PK-12）；身份不匹配 ⇒ 空操作，绝不误杀另一张。
     */
    async cancelUploadHandoff(toolCallId?: string): Promise<void> {
      const handoff = this.activeUploadHandoff
      if (!handoff) {
        return
      }
      if (toolCallId !== undefined && toolCallId !== '' && toolCallId !== handoff.toolCallId) {
        return
      }
      await this.finishUploadHandoff(handoff, 'cancelled', '用户已放弃本次上传交接，未上传任何文件。', true)
    },

    /**
     * 用户嫌麻烦，直接点了页面上的上传按钮：页面成功后调本方法，活动交接**立即**以 ok 收口
     * （否则 AI 会一直等）。无活动交接时是空操作（不误杀别的轮）。
     */
    async notifyAiUploadDone(summary: string): Promise<void> {
      const handoff = this.activeUploadHandoff
      if (!handoff) {
        return
      }
      await this.finishUploadHandoff(handoff, 'succeeded', `用户已通过页面入口完成上传：${summary}`, false)
    },

    /**
     * 刷新恢复的判据（**只看区域**）：交接不依赖浏览器里算出的中间态，
     * 区域仍注册 ⇒ 可继续；区域已不在该页 ⇒ `missing`（主按钮禁用、只能放弃）。
     * 浏览器 picker 能力在点击时再判（`open` 时的 `degraded` 是"当时"的分流）。
     *
     * <p>
     * **A09**：`ref` 只表示"模型显式指定了入口"，缺省时落点由区域声明的 `uploadEntry`
     * 决定（`resolveUploadRegion(null)` 回落）。刷新重建那一刻路由页是懒加载的、区域可能
     * 还没注册 ⇒ 重建出的 `ref` 落 `null` 且不会回填；这里若按 `!handoff.ref` 一票判死，
     * 区域随后注册了卡也永远停在 `missing`（按钮永不启用）。故缺 `ref` 不得否决 ——
     * 判据一律交给"区域现在在不在"：区域不在仍取不到目标 ⇒ 依然 `missing`（没有放宽）。
     */
    uploadHandoffAvailability(): 'waiting' | 'missing' | 'degraded' | null {
      const handoff = this.activeUploadHandoff
      if (!handoff) {
        return null
      }
      if (handoff.status === 'degraded') {
        return 'degraded'
      }
      const target = resolveUploadRegion(handoff.ref)
      if (!target || target.region.root.isConnected !== true) {
        return 'missing'
      }
      return target.region.readOnly?.() === true ? 'missing' : 'waiting'
    },

    /** 恢复路：后端快照里的 PENDING 交接条目重建卡（不依赖本地 plan，与表格预览的"失效卡"不同）。 */
    async rebuildUploadHandoffFromFrame(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): Promise<void> {
      const validated = validateUploadArgs(parseToolArgs(frame.args))
      if (!validated.ok) {
        return
      }
      const target = resolveUploadRegion(validated.value.ref)
      const status: ActiveUploadHandoff['status'] = target && target.region.root.isConnected === true
        ? 'waiting'
        : 'missing'
      this.activeUploadHandoff = {
        toolCallId: frame.toolCallId,
        runId: frame.runId ?? this.activeRunId ?? null,
        ref: target?.entry ?? validated.value.ref ?? null,
        accept: validated.value.accept,
        status,
        reason: status === 'missing' ? translate('ai.notice.uploadNoRegion') : null,
        filename: null
      }
      this.uiAnnouncement = pickUiAnnouncement()
      this.upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status: 'pending',
        args: frame.args,
        requiresAction: true
      }, false)
      deriveProjections(message)
    },

    /** 交接的统一收口：先写段终态（含所有持该 toolCallId 的宿主），再回灌。 */
    async finishUploadHandoff(
      handoff: ActiveUploadHandoff,
      status: ToolRunStatus,
      result: string,
      cancelled: boolean
    ): Promise<void> {
      // B2：只摘**自己这张卡** —— 迟到的收口（旧卡的 picker 慢一步返回 / 旧卡回灌超时）
      // 不得把单例里已经换成新卡的那张一起摘掉（新卡还在等人选文件，摘掉就再也点不动）。
      if (this.activeUploadHandoff?.toolCallId === handoff.toolCallId) {
        this.activeUploadHandoff = null
      }
      for (const item of this.messages) {
        if (!findToolPart(item, handoff.toolCallId)) {
          continue
        }
        this.upsertToolPart(item, {
          toolCallId: handoff.toolCallId,
          name: 'ui_request_upload',
          kind2: 'FRONTEND',
          status,
          result,
          requiresAction: false
        }, false)
        deriveProjections(item)
      }
      const outcome: ToolOutcome | undefined = cancelled ? undefined : status === 'succeeded' ? 'ok' : 'failed'
      await this.submitFrontendToolResult(
        handoff.toolCallId,
        result,
        'frontend-executor',
        cancelled,
        handoff.runId ?? undefined,
        outcome
      )
    },

    // ── AI 可视可操作专项：撤销（只给用户） ────────────────────────────────────

    /** 面板「撤销」：取撤销栈顶并应用（深度 1）。 */
    async undoLastUiAction(): Promise<string> {
      const entry = takeUiUndo()
      this.uiUndoEntry = null
      this.uiUndoToolCallId = null
      if (!entry) {
        setNotice(this, 'ai.notice.noUiUndo')
        return translate('ai.notice.noUiUndo')
      }
      const outcome = applyUiUndo(entry)
      setRawNotice(this, outcome.result)
      return outcome.result
    },

    /** 把原语运行时的一次性状态同步进 store（面板渲染用）。 */
    syncUiRuntimeState(): void {
      this.uiUndoEntry = peekUiUndo()
      this.uiAnnouncement = peekUiAnnouncement()
    },

    /** 面板 aria-live 区播报完毕（消费后清空，避免重复念）。 */
    acknowledgeUiAnnouncement(): void {
      clearUiAnnouncement()
      this.uiAnnouncement = null
    },

    /** 换轮/换页收口：清撤销点与高亮（撤销栈不持久化，刷新即失效）。 */
    resetUiSurfaces(): void {
      clearUiUndo()
      clearUiHighlights()
      clearUiAnnouncement()
      this.uiUndoEntry = null
      this.uiUndoToolCallId = null
      this.uiAnnouncement = null
      this.activeUploadHandoff = null
    },

    /**
     * 切换预览档位（count / sample-diff / full-diff；数据只在前端卡）。
     *
     * <p>
     * AI 表格工具治理批（§6.2）：计数档**没有物化明细**（快路径只数不建），故切到明细档要
     * 用计划的入参快照 `replanSheet` 重算一次 —— `planId` 不变（撤销补丁按它绑定）。
     * 重算前的版本比对由 `replanSheet` 负责：表格在预览期间被改动 ⇒ 走 stale，不静默套用旧结果。
     */
    async setPreviewLevel(level: SheetPreviewLevel): Promise<void> {
      const plan = this.activePreview
      if (!plan || plan.preview === level) {
        return
      }
      // 撤销没有"明细档"（反向补丁本身就是全量明细）⇒ 切档是空操作，不改计划、不动高亮
      if (plan.tool === 'undo_last_sheet_edit') {
        return
      }
      if (level === 'count' || plan.detail === 'full') {
        this.activePreview = { ...plan, preview: level }
        if (level === 'count') {
          // 计数档没有逐格明细 ⇒ 预览色随之撤掉（卡面也没有可核对的单元格）
          const resolved = await resolveSheetContext(plan.defCode)
          if (resolved.ok) {
            previewSheetCtx = resolved.ctx
            clearPreviewMarks(resolved.ctx)
          }
        }
        return
      }
      const resolved = await resolveSheetContext(plan.defCode)
      if (!resolved.ok) {
        this.activePreview = null
        previewSheetCtx = null
        this.markPreviewStale(plan.toolCallId)
        return
      }
      previewSheetCtx = resolved.ctx
      const replanned = replanSheet(resolved.ctx, plan, level)
      if (!replanned.ok) {
        // 两种都收口到"预览已失效"：表格被改（stale）与明细超上限（物化失败）。
        // 前者用户重新发起即可，后者卡面会显示原因（不静默降级成 0 格预览）。
        clearPreviewMarks(resolved.ctx)
        this.activePreview = null
        previewSheetCtx = null
        this.stalePreviewToolCallId = plan.toolCallId
        setRawNotice(this, replanned.reason)
        return
      }
      this.activePreview = replanned.plan
      // 明细算出来了才谈得上"待改格"：此刻上预览色（同一套绘制入口，屏幕态）
      markPreviewCells(resolved.ctx, replanned.plan)
    },

    /** 写工具结局的统一落点：先写段，再回灌（cancelled 走既有 `FRONTEND_CANCELLED` 语义）。 */
    async finishSheetTool(input: {
      toolCallId: string
      name: string
      runId?: string
      status: ToolRunStatus
      result: string
      cancelled?: boolean
      /** 采样元数据（治理批 §4.2）：写侧只报 planId / 预览档 / 样例对数，不报任何单元格值 */
      sheetMeta?: SheetToolMeta
    }): Promise<void> {
      for (const item of this.messages) {
        if (!findToolPart(item, input.toolCallId)) {
          continue
        }
        this.upsertToolPart(item, {
          toolCallId: input.toolCallId,
          name: input.name,
          kind2: 'FRONTEND',
          status: input.status,
          result: input.result,
          requiresAction: false
        }, false)
        deriveProjections(item)
      }
      const outcome: ToolOutcome | undefined = input.cancelled === true
        ? undefined
        : input.status === 'succeeded' ? 'ok' : 'failed'
      await this.submitFrontendToolResult(
        input.toolCallId,
        input.result,
        'frontend-executor',
        input.cancelled === true,
        input.runId,
        outcome,
        input.sheetMeta
      )
    },

    /** 用户点“应用”：大批量必须先勾选二次确认；应用前复核行指纹。 */
    async confirmWritePreview(acknowledged = false): Promise<void> {
      const plan = this.activePreview
      if (!plan) {
        return
      }
      if (plan.level === 'large' && !acknowledged) {
        setNotice(this, 'ai.notice.sheetLargePlan', { rows: plan.rowsCount, cells: plan.cellsCount })
        return
      }
      // 恰好一次：双击/跨实例重复确认在首个 await 之前就被这里挡住（第二次是空操作）
      if (!beginPreviewDecision(plan)) {
        return
      }
      // 异步期间可能已有新卡到来：只清自己这张
      const clearOwnPreview = (): void => {
        if (this.activePreview?.planId === plan.planId) {
          this.activePreview = null
          previewSheetCtx = null
        }
      }
      try {
        const resolved = await resolveSheetContext(plan.defCode)
        if (!resolved.ok) {
          clearOwnPreview()
          await this.finishSheetTool({
            toolCallId: plan.toolCallId,
            name: plan.tool,
            runId: plan.runId,
            status: 'failed',
            result: resolved.reason
          })
          return
        }
        previewSheetCtx = resolved.ctx
        // 计数档没有逐格新值 ⇒ 落笔前必须先物化明细（§6.2）。这一步是"应用"的必要前提：
        // 没有它就只能"改 0 格"，那是谎。物化超硬上限 ⇒ 如实回灌失败，不静默降级。
        let applied = plan
        if (plan.detail === 'none') {
          const materialized = replanSheet(resolved.ctx, plan, 'sample-diff')
          if (!materialized.ok) {
            clearOwnPreview()
            await this.finishSheetTool({
              toolCallId: plan.toolCallId,
              name: plan.tool,
              runId: plan.runId,
              status: 'failed',
              result: materialized.reason
            })
            return
          }
          // 关键：指纹/目标行沿用**预览时**那一份。物化是按"此刻"的表状态重算的，若把重算出的
          // 指纹当成预览指纹，就等于把改动后的状态认成预览时的状态 —— 行指纹这道闸会被自己绕过。
          applied = {
            ...materialized.plan,
            targetRows: plan.targetRows,
            fingerprint: plan.fingerprint
          }
          this.activePreview = applied
        }
        // 预览色先撤：同一格不该同时挂两种底色（已应用色由 applySheetPlan 上）
        clearPreviewMarks(resolved.ctx)
        // 撤销走自己的落笔函数：身份校验（栈顶仍是本计划要撤的那一层）+ 冲突复核 + 反向补丁，
        // 从而"同一次撤销恰好一次"（applySheetPlan 会反向入栈，撤销不能走它）。
        const outcome = applied.tool === 'undo_last_sheet_edit'
          ? applyUndoPlan(resolved.ctx, applied)
          : applySheetPlan(resolved.ctx, applied)
        clearOwnPreview()
        if (!outcome.ok) {
          await this.finishSheetTool({
            toolCallId: applied.toolCallId,
            name: applied.tool,
            runId: applied.runId,
            status: 'failed',
            result: outcome.reason
          })
          return
        }
        await this.finishSheetTool({
          toolCallId: applied.toolCallId,
          name: applied.tool,
          runId: applied.runId,
          status: 'succeeded',
          result: outcome.text,
          // 写侧元数据（§4.2 planId / previewLevel）：样例对数是"回灌了几处业务值"的度量，
          // >0 即 D2 —— 写路径本来就是 D2 通道（§0 结论 2）
          sheetMeta: {
            dataClass: applied.samples.length > 0 ? 'D2' : 'D1',
            // 只报"回灌了几处样例"；写侧不回给字符数（没有可靠的度量源，宁缺不造）
            sampleRows: applied.samples.length,
            planId: applied.planId,
            previewLevel: applied.preview,
            sheetVer: applied.sheetVer,
            defCode: applied.defCode
          }
        })
        // 卡面一行「AI 改动 N 格（可撤销，深度 3 中第 L 层）」（§5.4；不新增工具，也不新增帧字段）
        this.sheetApplied = sheetAppliedSummary(resolved.ctx)
      } finally {
        endPreviewDecision(plan)
      }
    },

    /** 用户点“取消”：零写 + `cancelled=true` ⇒ 后端 `FRONTEND_CANCELLED`。 */
    async cancelWritePreview(): Promise<void> {
      const plan = this.activePreview
      if (!plan) {
        return
      }
      // 与「应用」共用同一张卡的决策权：应用在途时取消不再抢跑（一次决策只有一种结局）
      if (!beginPreviewDecision(plan)) {
        return
      }
      this.activePreview = null
      previewSheetCtx = null
      try {
        const resolved = await resolveSheetContext(plan.defCode)
        if (resolved.ok) {
          clearPreviewMarks(resolved.ctx)
        }
        await this.finishSheetTool({
          toolCallId: plan.toolCallId,
          name: plan.tool,
          runId: plan.runId,
          status: 'cancelled',
          result: planCancelledText(),
          cancelled: true
        })
      } finally {
        endPreviewDecision(plan)
      }
    },

    /**
     * 预览失效收口（设计稿 §3.5）：刷新后本地无 plan 的 PENDING 写工具不得渲染可点的“应用”。
     */
    markPreviewStale(toolCallId: string): void {
      if (this.activePreview?.toolCallId === toolCallId) {
        // 计划失效 ⇒ 预览色一起撤（卡上说"失效"、屏幕上却还亮着琥珀 = 两套说法）
        if (previewSheetCtx) {
          clearPreviewMarks(previewSheetCtx)
        }
        this.activePreview = null
      }
      previewSheetCtx = null
      this.stalePreviewToolCallId = toolCallId
    },

    // ── B12 预览高亮收口（换选区 / 换页签 / 离开页面 / 换会话 / 停止） ────────────

    /**
     * 预览收口：清预览色 + 计划归零。**只清 `preview` 色调**（已应用色归撤销链管）。
     *
     * @param reason `session` / `stop` = 整轮或整会话结束，卡不再需要"失效"提示；
     *   其余 = 上下文变了但轮次还在 ⇒ 卡面转「预览已失效（可放弃）」，让用户知道为什么不能应用了。
     */
    dropSheetPreview(reason: 'session' | 'page' | 'tab' | 'selection' | 'stop' | 'reload' = 'page'): void {
      const plan = this.activePreview
      if (reason === 'session' || reason === 'stop') {
        // 整轮/整会话结束：卡面不再需要「预览已失效」提示，旧失效标记一并清
        this.stalePreviewToolCallId = null
      }
      if (!plan) {
        previewSheetCtx = null
        return
      }
      if (previewSheetCtx) {
        clearPreviewMarks(previewSheetCtx)
      }
      previewSheetCtx = null
      this.activePreview = null
      if (reason !== 'session' && reason !== 'stop') {
        this.stalePreviewToolCallId = plan.toolCallId
      }
    },

    /**
     * 换选区（B12）：**只有选区作用域的计划**会在选区变化时失效 —— 它本来就是按"当前框选"
     * 算出来的 diff，选区一换，那份 diff 指的不再是用户眼前选中的行。整表 / 条件 / 列范围
     * 的计划与选区无关，不得误伤（否则用户点一下单元格就把预览弄没了）。
     */
    onSheetSelectionChanged(defCode?: string): void {
      const plan = this.activePreview
      if (!plan || plan.scope !== 'selection') {
        return
      }
      if (defCode !== undefined && defCode !== '' && plan.defCode !== defCode) {
        return
      }
      this.dropSheetPreview('selection')
    },

    /** 换页签（B12）：换到**另一张表**才收口；切回同一张表不动（可能是误触）。 */
    onSheetTabChanged(defCode: string): void {
      const plan = this.activePreview
      if (!plan || plan.defCode === defCode) {
        return
      }
      this.dropSheetPreview('tab')
    },

    /**
     * 程序化载入收口（交互专项 2026-10-25）：上传新文件 / 重跑导出 / 换任务重载后，
     * 该配置项的旧预览必须立即失效。
     *
     * <p>页面在载入成功后调用；真正的硬闸仍是 `previewId`（loadGeneration + instanceId）——
     * 即使页面漏调，旧计划也会在应用时被拒。这里做的是"提前把卡面切到失效态"。
     */
    onSheetReloaded(defCode: string): void {
      const plan = this.activePreview
      if (!plan || plan.defCode !== defCode) {
        return
      }
      this.dropSheetPreview('reload')
    },

    /**
     * 离开承载表格的页面（B12）：预览计划已不可达（`sheet.current` handler 随卸载摘掉），
     * 收口为「预览已失效」，卡面给「放弃」入口；屏幕色同步撤掉（组件卸载前最后一帧）。
     */
    onSheetPageLeft(): void {
      this.dropSheetPreview('page')
    },

    /**
     * 用户对“预览已失效”卡点“放弃”：回灌 `cancelled=true`，让模型重新发起。
     *
     * @param toolCallId 卡面所属的 toolCallId（卡面显式传入；缺省回落 {@link stalePreviewToolCallId}）。
     *   必须由卡面传入的原因：多张卡先后失效时单一 `stalePreviewToolCallId` 记不住全部，
     *   旧卡会变成"plan=null、放弃键点不动"的死键 —— 而写工具等的是人（无超时），一挂就是永久。
     */
    async abandonStalePreview(toolCallId?: string): Promise<void> {
      const id = toolCallId && toolCallId !== '' ? toolCallId : this.stalePreviewToolCallId
      if (!id) {
        return
      }
      if (this.stalePreviewToolCallId === id) {
        this.stalePreviewToolCallId = null
      }
      // 只有"被放弃的就是当前活动计划"时才清活动态（刷新路径）；不得误撤另一张仍在等人的卡
      if (this.activePreview?.toolCallId === id) {
        if (previewSheetCtx) {
          clearPreviewMarks(previewSheetCtx)
        }
        this.activePreview = null
        previewSheetCtx = null
      }
      const part = this.messages
        .map((item) => findToolPart(item, id))
        .find((value) => value !== null) ?? null
      await this.finishSheetTool({
        toolCallId: id,
        name: part?.name ?? 'replace_values',
        runId: this.activeRunId ?? undefined,
        status: 'cancelled',
        result: '预览已失效（页面刷新或已被新的表格操作取代），已放弃本次调用，未改动任何单元格。请让 AI 重新发起。',
        cancelled: true
      })
    },

    /**
     * RC-2：GF 提交值写回工具段（**按 toolCallId 直接定位，不依赖 `activeForm` 身份**）。
     *
     * 为什么要独立于 activeForm：结局帧（`frontend_tool_result`）可能先于
     * `POST /frontend-tool-result` 的应答到达，`handleFrame` 会把 `activeForm` 置 null
     * （`:2764`）——旧写法把写回挂在 `activeForm` 身份守卫之后，值就随守卫一起丢掉
     * （只读渲染吃 `submittedValues`：本会话内值缺失，要等刷新才由后端 resultText 补回）。
     *
     * 2A②：写回**全部匹配段**（多消息 / 同消息重复段），不碰其他 toolCallId。
     * `overwrite=true`（默认，POST 成功路径）以本次提交值为准；`overwrite=false`（帧路径）
     * first-wins，只补空段 —— 两条路径共用 `writeSubmittedValuesToMessage` 单一写口。
     */
    writeSubmittedValues(toolCallId: string, values: Record<string, unknown>, overwrite = true): void {
      const decidedAt = Date.now()
      for (const item of this.messages) {
        if (writeSubmittedValuesToMessage(item, toolCallId, values, { overwrite, decidedAt })) {
          deriveProjections(item)
        }
      }
    },

    /**
     * 2A 批（裁决 §3 缺陷①）：表单系取消 / 超时收尾记 `decidedAt`（**数据层记录，卡面不渲染**）。
     * 只写第一次决策时刻（重入 / 帧后到不覆盖）；段不存在时静默跳过（与 cancel 回灌同口径）。
     */
    markFormDecided(toolCallId: string): void {
      const host = this.messages.find((item) => item.parts.some(
        (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
      ))
      const segment = host?.parts.find((part) => part.kind === 'tool' && part.toolCallId === toolCallId)
      if (!host || !segment || segment.kind !== 'tool' || segment.decidedAt !== undefined) {
        return
      }
      this.upsertToolPart(host, {
        toolCallId,
        name: segment.name,
        kind2: segment.kind2,
        decidedAt: Date.now(),
        requiresAction: false
      })
      deriveProjections(host)
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
      // 结构化回灌体的发送前预检（用户 2026-10-25）：与后端同一 max-chars（8000）。
      // 超限 = 零请求；挂起保持 PENDING、status 仍 filling、完整输入不清空，用户改小后可重试。
      const resultText = JSON.stringify(values)
      if (resultText.length > STRUCTURED_RESULT_MAX_CHARS) {
        form.error = translate('ai.notice.formResultTooLarge', {
          chars: resultText.length,
          max: STRUCTURED_RESULT_MAX_CHARS
        })
        return
      }
      form.status = 'submitting'
      form.error = null
      form.fieldErrors = []
      const outcome = await this.submitFrontendToolResult(form.toolCallId, resultText, 'http-post', false, form.runId)
      if (outcome.ok || outcome.duplicate) {
        // G-B：提交成功 ⇒ 草稿镜像即清（草稿只是"未提交"的浏览器临时态）
        clearFormDraft(form.toolCallId)
        form.values = {}
        // RC-2：值写回**先于** activeForm 身份守卫 —— 结局帧先到（activeForm 已 null）也要落值；
        // 守卫只留给"回填 form.status/error"这类必须择主的本地态（防瞬时覆写，红队问题 6）。
        // 2A② 边缘②：409 DUPLICATE = 该 toolCallId 已是终态（他端先提交）—— 本地这份值不是
        // 权威提交值，不写回（既不覆盖先到帧值，也不写入空段冒充「同值」）。正常 ok 路径照旧。
        if (!outcome.duplicate) {
          this.writeSubmittedValues(form.toolCallId, values)
        }
        if (this.activeForm?.toolCallId === form.toolCallId) {
          form.status = 'submitted'
          // AR-07：这一张已结算（提交成功 / 409 已在他处提交）⇒ 同批里还有人等就按到达序补下一张。
          // 结算记录本身留在 `activeForm` 上（既有口径：结局帧到达才 drop），补卡只是换掉它。
          this.promoteNextForm()
        }
        return
      }
      if (this.activeForm?.toolCallId !== form.toolCallId) {
        // 结局帧/新表单已收敛：失败提示不写回过期表单（红队问题 6，防瞬时覆写）
        return
      }
      if (outcome.rejected) {
        // 400 FORM_RESULT_REJECTED：挂起仍在等待 —— 回填写态，同一 toolCallId 可改值重发或取消。
        // G-A：结构化错误按 key 落到字段；无法归属的部分才留在卡级错误条。
        const issues = outcome.issues ?? []
        const keyed = issues.filter((issue) => issue.key !== '')
        const unkeyed = issues.filter((issue) => issue.key === '')
        form.status = 'filling'
        form.fieldErrors = keyed
        if (outcome.tooLarge) {
          // 体积问题不是字段值问题：不点亮字段、不把输入判成错值；缩短后同一 toolCallId 重试
          form.error = translate('ai.notice.formResultTooLargeRejected')
          return
        }
        form.error = unkeyed.length > 0
          ? unkeyed.map((issue) => issue.message).join('；')
          : issues.length > 0
            ? null
            : translate('ai.notice.formRejected', { reason: outcome.rejected })
        return
      }
      form.status = 'filling'
      // 红队问题 4：泛化文案并入后端具体原因，表单卡内可见真实失败点
      form.error = outcome.message
        ? translate('ai.notice.formSubmitFailed', { message: outcome.message })
        : translate('ai.notice.formSubmitFailedPlain')
    },

    /**
     * GF-B：显式取消 —— 带 cancelled:true 回灌，挂起立刻收敛。
     *
     * <p>AR-07：取消出口对**每一张**都成立 —— 不带参数 = 取消当前活动表单（卡面自己的「取消」）；
     * 带 toolCallId = 取消**队列里**的那一张（它还没轮到呈现，仍要能从外面收口，不留死卡）。
     * 取消是用户已经做完的决定，故本地结算后立即按到达序补下一张，不干等结局帧。
     */
    async cancelGenerativeForm(toolCallId?: string): Promise<void> {
      if (toolCallId !== undefined && this.activeForm?.toolCallId !== toolCallId) {
        await this.cancelQueuedForm(toolCallId)
        return
      }
      const form = this.activeForm
      if (!form || form.status === 'cancelled' || form.status === 'submitted') {
        return
      }
      form.status = 'cancelled'
      // G-B：取消即清草稿镜像（不再保留任何未提交内容）
      clearFormDraft(form.toolCallId)
      form.values = {}
      // T2a-D#2（A2）：本地 cancel 仅乐观置位 —— 只收敛仍处 pending 的 pendingCall 投影；
      // 帧终态为最终裁决，后到帧可覆盖此乐观 cancelled（他端先提交时翻为 succeeded，不降级）
      this.applyPendingTerminal(form.toolCallId, 'cancelled')
      // 2A 批（裁决 §3 缺陷①）：取消 / 超时收尾记决策时刻（数据层记录，卡面不渲染）
      this.markFormDecided(form.toolCallId)
      const outcome = await this.submitFrontendToolResult(form.toolCallId, '', 'http-post', true, form.runId)
      if (this.activeForm?.toolCallId === form.toolCallId) {
        if (!outcome.ok && !outcome.duplicate) {
          form.error = translate('ai.notice.formCancelFailed', {
            message: outcome.message ?? translate('ai.notice.networkError')
          })
          this.writeFormPartReason(form.toolCallId, form.error)
        }
        // AR-07：结算完成 ⇒ 收掉这一张（同批下一张由此接上）；结局帧后到时与它无关（段已终态）
        this.activeForm = null
      }
      this.promoteNextForm()
    },

    /**
     * AR-07：取消**队列里**的一张表单 —— 不动正在填的那张。
     *
     * <p>与活动卡同一套收尾口径：出队清草稿 → 乐观终态 → 记决策时刻 → `cancelled:true` 回灌；
     * 回灌失败的原因落段（卡面可见），不吞。
     */
    async cancelQueuedForm(toolCallId: string): Promise<void> {
      const entry = this.queuedForms.find((item) => item.toolCallId === toolCallId)
      if (!entry) {
        return
      }
      this.discardQueuedForm(toolCallId)
      this.applyPendingTerminal(toolCallId, 'cancelled')
      this.markFormDecided(toolCallId)
      const outcome = await this.submitFrontendToolResult(toolCallId, '', 'http-post', true, entry.runId)
      if (!outcome.ok && !outcome.duplicate) {
        this.writeFormPartReason(toolCallId, translate('ai.notice.formCancelFailed', {
          message: outcome.message ?? translate('ai.notice.networkError')
        }))
      }
    },

    /**
     * 表单取消回灌失败的原因落段（批4④ A4' / D3/D11）。
     *
     * <p>段持久（activeForm 的清空不影响可见性），卡内 reason 位渲染；不经 applyPendingTerminal
     * （防污染 confirm 路径 reason 语义）。D-C2 钉死形状：requiresAction 显式 false（必填字段，
     * 误置 true 会把刚收口的终态段重挂回 requiresAction，破 ③b D8 闸）；
     * createIfMissing=false：段不存在时静默丢写（不新建段）+ DEV 警告（须含 toolCallId）。
     */
    writeFormPartReason(toolCallId: string, reason: string): void {
      // 收口修 F5（红队 m-3）：reason 单落点 = **末条**持该 toolCallId 的宿主消息 —— 与
      // formHostMessageId（AiPanel.vue 倒序扫末条，D2 保留面）同口径。误取首条时，双卡/重投
      // 场景下用户在末条宿主卡上取消，reason 会落到首条消息卡上，与操作位错位。
      let host: ChatMessage | undefined
      for (let i = this.messages.length - 1; i >= 0; i--) {
        const message = this.messages[i]
        if (message && message.parts.some((part) => part.kind === 'tool' && part.toolCallId === toolCallId)) {
          host = message
          break
        }
      }
      const existing = host?.parts.find((part) => part.kind === 'tool' && part.toolCallId === toolCallId)
      if (host && existing && existing.kind === 'tool') {
        this.upsertToolPart(host, {
          toolCallId,
          name: existing.name,
          kind2: existing.kind2,
          requiresAction: false,
          reason
        }, false)
        // 收口修 F4（红队 m-2）：段 reason 落段后同步派生投影 —— 否则 toolRuns[].reason
        // （parts.ts:199 投影含 reason）滞留旧值，违背「parts 唯一真值、投影由 deriveProjections 派生」契约。
        deriveProjections(host)
      } else if (import.meta.env.DEV) {
        console.warn(`[批4④ A4'] 取消回灌失败：未找到 toolCallId=${toolCallId} 的工具段，reason 未落段（不新建段）`)
      }
    },

    /**
     * M-19（2026-10-29，R1）：记下"本会话停止意向"（`stop()` 时后端还没给出 runId）。
     *
     * <p>记录宿主消息 id 是为了**归属可证明**：只有同一宿主的 start 帧才可能是被停的那一轮。
     * `transport` 是被保留下来等 start 的那条 /chat 连接（C2：不能 abort 它）。
     */
    noteStopIntent(transport: AbortController | null): void {
      this.stopIntent = { hostMessageId: activeRoundHostId, at: Date.now(), transport }
    },

    /**
     * M-19（2026-10-29，R1）：拿到 runId（start 帧）后补一次真实 cancel。
     *
     * <p>归属判据只有一条可用且可证明：**同一宿主消息的 start 帧**。会话级 409 里的 runId 无法
     * 证明是本端被停的那一轮（可能是另一页签的新轮），故本函数只服务 start 帧（I5）。
     *
     * @param runId 刚拿到的 runId
     * @param hostMessageId 该 start 帧所属的助手宿主
     * @returns 是否真的发起了 cancel（`false` = 没有意向 / 已过期 / 归属不符）
     */
    cancelRunIfStopIntended(runId: string, hostMessageId: string): boolean {
      const intent = this.stopIntent
      if (intent === null || runId === '') {
        return false
      }
      if (Date.now() - intent.at > STOP_INTENT_TTL_MS) {
        // 过期的意向作废：绝不拿它取消一个后来的新轮
        this.stopIntent = null
        return false
      }
      if (intent.hostMessageId === null) {
        // 停止时没有可归属的宿主（本端没有"这一轮"的证明）：不猜
        return false
      }
      if (intent.hostMessageId !== hostMessageId) {
        // 另一轮（新轮）的 start 已经到达 ⇒ 旧意向失去意义，就地清理（I5：新轮后不残留）。
        // N5（R2）：顺带收掉挂在旧意向上的 /chat 传输 —— 它等的那一轮不可能是"被停的那一轮"了，
        // 留着只是一条空连接（会一直挂到首帧超时）。归属判断不变：这里**不**取消任何 run。
        this.stopIntent = null
        intent.transport?.abort()
        return false
      }
      this.stopIntent = null
      this.stoppedRunId = runId
      if (this.busyRun?.runId === runId) {
        this.busyRun = null
      }
      // C2：拿到 runId 才关本地连接 —— 先发 cancel，再收掉这条已经没用的 /chat 传输
      void this.cancelStoppedRun(runId)
      intent.transport?.abort()
      return true
    },

    /**
     * M-19（R1）：某条 /chat 传输收尾时清理挂在它上面的停止意向。
     *
     * <p>传输结束仍没等到 start ⇒ 服务端没有这一轮（或帧丢了），意向再无落地机会；留着只会在
     * TTL 内影响后续判断。
     */
    dropStopIntentFor(transport: AbortController): void {
      if (this.stopIntent?.transport === transport) {
        this.stopIntent = null
      }
    },

    /**
     * M-19：补发的 cancel（失败不写技术文案 —— 本地停止早已收敛，与 `stop()` 同口径）。
     */
    async cancelStoppedRun(runId: string): Promise<void> {
      try {
        await aiApi.cancel(runId)
      } catch {
        // 后端不可达不影响本地已落地的停止
      }
    },

    /**
     * 停止本轮（批G · S1/S2/S3/S4 收敛口径）。
     *
     * <p>
     * 旧实现只清"挂起标志"、不碰任何段，且立刻 abort 本地流 —— 于是：挂起态停止后
     * {@code pendingToolCall} 恒非 null ⇒ **输入框永久禁用**（实证 D1）；执行中的工具卡永远
     * 「调用中…」（D4）；停止后的正文既不进记忆、刷新后还被历史整替抹掉（D3）。
     *
     * <p>
     * 新口径 = **本地就地收敛**（不依赖后端帧，后端不可达也成立）：
     * <ol>
     * <li>所有 pending/running 工具段 → {@code cancelled}、{@code requiresAction=false}，
     *     清 activeForm / activePreview / 挂起队列 / suspended ⟹ 输入框立刻解禁、卡变只读；
     *     **未提交表单草稿保留**（2026-10-29：停止不是"放弃这张表"，见
     *     {@link suspendActiveFormDrafts}）；</li>
     * <li>本轮助手消息打 {@code stopped} 标记（S3「已停止」，与「继续」入口互斥）；</li>
     * <li>立即写镜像（S4：半截正文 + done，刷新后仍可见）；</li>
     * <li>幂等（S2）：{@code stopping} 在途时重复点击直接返回；{@code alreadyCancelled} 照读。</li>
     * </ol>
     */
    async stop(): Promise<void> {
      if (this.stopping) {
        return // S2：停止在途，重复点击不重复 POST
      }
      const runId = this.activeRunId
      // 幂等判据：有在途连接 / 挂起等待 / 有一轮尚未被停过的 runId。已停过的轮不再重复 POST。
      const hadRound = this.loading || this.suspended || (runId !== null && !this.stopped)
      if (!hadRound) {
        return // 幂等：没有可停的轮（不写任何提示）
      }
      this.stopping = true
      // O02：停止的落地闸门 —— 同 tick 的发送/忙态判断先等它（见 waitForStopSettlement）。
      // 本地收敛在本函数里**同步**完成，闸门只等 cancel 送达后端。
      let releaseStopGate: () => void = () => {}
      stopSettleGate = new Promise<void>((resolve) => {
        releaseStopGate = resolve
      })
      // O02：本端已对这条 run 作出停止决定 —— 此后指回它的 409 只是"停止尚未传播"的残影，
      // 不是"上一轮仍在进行中"（判据见 isAuthoritativeBusyRun）；同时撤掉指向它的忙提示。
      if (runId !== null) {
        this.stoppedRunId = runId
        if (this.busyRun?.runId === runId) {
          this.busyRun = null
        }
      } else {
        // M-19（2026-10-29，R1）：start 帧还没到 ⇒ runId 未知，现在没有 cancel 的对象。
        // 记下"本会话停止意向"，并**保留这条 /chat 传输**（不 abort，见下方 abort 分支）：
        // start 一到即按 runId 精确补一次真实 cancel（C2：abort 掉就等于把唯一的到达路径掐了）。
        this.noteStopIntent(currentAbort)
      }
      // M08-R2：停止 = 放弃这一轮 ⇒ 释放驱动所有权与预算（停止是终态动作，不是"连接结束"）
      this.forgetRunStreamDrive(runId ?? undefined)
      // 2026-10-29 收口：停止 = 现场作废 ⇒ 所有自动恢复排期与预算一并作废
      // （否则首帧超时/准备中的恢复链会在停止之后把这一轮接回来）
      cancelAutoRecovery()
      this.autoRecoveryUsed = {}
      // B1 收口修（C-1）：停止 = 本轮被放弃 —— 在途前端工具结局不得再写段/回灌
      roundEpoch += 1
      // B2：停止也对**排队中**的工具生效 —— 队列项自带轮次现场判据（代次已变），
      // 轮到它们时零执行；这里只丢掉链表，不把新会话的新动作排到旧链尾。
      clearFrontendToolChains()
      // M-2b：停止后不得再把快照队列里的旧卡补出来
      this.restoredPendingQueue = []
      // AI 可视可操作专项：停止后**后续界面动词立即拒绝**（已执行的不回滚）；
      // 交接卡随本轮收起（后端取消会让挂起走取消收尾）。
      abortUiOperations()
      // 停止 = 本轮放弃：**在途前端工具**一并作废 —— 页面 handler / 登记执行器在 await 之后
      // 读到 `abandoned` 即不再写工作区/步骤/选中集（预算到点是同一条判据的另一触发面）。
      abandonInFlightFrontendTools()
      this.activeUploadHandoff = null
      this.stopped = true
      this.lastRoundOutcome = 'cancelled'
      this.followupReady = false
      // C2（R1 返修）：只有"知道 runId"或"本来就没有可等的连接"时才 abort。
      // runId 未知且 /chat 还开着 = 正处在 pre-start 窗口：**必须留着这条传输**，否则 start 帧
      // 永远到不了，"拿到 runId 再补 cancel"就成了不可达的假绿。本地展示（停止态/输入解禁）已在
      // 上面同步完成，不依赖这次 abort。
      const keepTransport = runId === null && this.stopIntent !== null && this.stopIntent.transport !== null
      if (!keepTransport) {
        try {
          currentAbort?.abort()
        } catch {
          // 忽略：中止失败不影响后续取消请求
        }
      }
      // 停止是"本轮到此为止"：`stopped` 置位让本轮收尾判定为 cancelled；连接仍交给它自己的
      // finally 收口（abort 后立即返回；pre-start 保留的那条则在 start 补完 cancel 后才收），
      // **不在这里清 currentAbort** —— 清了会让本轮 settle 成 interrupted（身份失效），
      // 与"用户主动停止"的语义不符。
      // M08：登记位随之中止（句柄已 abort ⇒ 判活已失效，这里只是不留残值）。
      this.activeStreamRunId = null
      this.activeStreamParked = false
      this.loading = false
      this.suspended = false
      // 批次3：停止后压缩/准备中阶段一并收口（否则阶段条会挂在一个已放弃的轮上）
      this.resetCompactionProgress()
      this.clearPreparingRun()
      // 2026-10-29 收口：停止只收起表单卡，**保留未提交草稿**（只有明确放弃才清 —— 见
      // `suspendActiveFormDrafts`）。停掉一轮不该顺手删用户手填的内容。
      this.suspendActiveFormDrafts()
      // 停止 = 本轮被放弃：预览与高亮一起撤（B12 收口矩阵的「停止本轮」格）。
      // 用建卡时留的上下文引用同步清色 —— 表格可能在别的页签，异步解析不保证拿得到。
      this.dropSheetPreview('stop')
      this.stalePreviewToolCallId = null
      // L-79（停止即收敛）：界面撤销点随本轮作废 —— 撤销栈是内存态、按轮归属，本轮已被放弃，
      // 留着它等于让用户去撤一条"本轮已放弃"的操作（面板那段撤销行也会一直挂在旧卡上）。
      // 与换会话 / 换页两处出口同一收口函数（`resetUiSurfaces` 会一并清播报与上传交接，
      // 那两件不该被一次停止带走）⇒ 这里只清撤销点两件。
      clearUiUndo()
      this.uiUndoEntry = null
      this.uiUndoToolCallId = null
      // S1：段级就地收敛（输入框解禁的直接前提：pendingToolCall 由段派生）
      this.cancelRoundParts()
      // 本轮的确认门提交态 / 勾选态随轮收口（stop 不走 done 帧，不能指望结局帧来清）
      this.clearGateSubmissions()
      // S3/S4：消息尾标记 + 立即落镜像（含 done、stopped 与半截正文）
      this.markStoppedMessage()
      this.writeMirrorNow()
      try {
        if (runId) {
          const response = (await aiApi.cancel(runId)) as { alreadyCancelled?: boolean } | undefined
          // S2：读 alreadyCancelled —— 服务端幂等已生效（另一页签/重复点击已取消过）。
          // 本地收口在上方早就完成，这里只对齐轮次结局，不追加任何提示（同一件事不说两遍）。
          if (response?.alreadyCancelled === true) {
            this.lastRoundOutcome = 'cancelled'
          }
        }
      } catch {
        // 后端不可达不影响本地停止（上面已收敛）；不写技术文案，用户看到的仍是「已停止」
      } finally {
        this.stopping = false
        // O02：停止落地 —— 等在闸门上的发送可以开始判断"能不能发 / 是不是忙态"了
        releaseStopGate()
        stopSettleGate = null
      }
    },

    /**
     * S1：把本轮所有未收尾的工具段收敛为 {@code cancelled}（`pending` / `running` /
     * `requiresAction` 三条入口都收），并重算投影 —— `pendingToolCall` 立刻变 null。
     *
     * <p>
     * 批G · S13（真机回归）：停止是**一次决策**，不是"没人管" —— 段上同时落 `decision='cancel'`，
     * 否则只读态按"无决策元数据"回退 status 落到「已超时」（用户主动停掉的一轮被读成系统超时）。
     */
    cancelRoundParts(): void {
      for (const message of this.messages) {
        let touched = false
        message.parts.forEach((part, index) => {
          if (part.kind !== 'tool') {
            return
          }
          if (!part.requiresAction && part.status !== 'pending' && part.status !== 'running') {
            return
          }
          message.parts.splice(index, 1, {
            ...part,
            requiresAction: false,
            status: 'cancelled',
            decision: 'cancel'
          })
          touched = true
        })
        if (touched) {
          deriveProjections(message)
        }
      }
    },

    /**
     * S3：给本轮助手消息打「已停止」标记并封口（有正文或工具段才标 —— 空气泡不标）。
     * 同时清掉错误卡：一条被用户停掉的回答不是"出错"。
     *
     * <p>
     * 批G · S14（真机回归）：标记要落在**本轮宿主**（`activeRoundHostId`）上 —— "最后一条助手消息"
     * 在 GF/确认卡轮里可能是快照重建出来的空宿主，标错了消息就等于没标。停止是消息级事实：
     * 一旦打上不再由后续帧推导覆盖（迟到帧只补段状态，不改这个标记）。
     */
    markStoppedMessage(): void {
      const roundHost = this.messages.find(
        (message) => message.id === activeRoundHostId && message.role === 'assistant'
      ) ?? null
      const last = roundHost ?? [...this.messages].reverse().find((message) => message.role === 'assistant')
      if (!last) {
        return
      }
      last.streaming = false
      last.done = true
      sealTrailingStreamPart(last)
      deriveProjections(last)
      const hasBody = last.content.trim() !== '' || last.parts.some((part) => part.kind !== 'text')
      if (hasBody) {
        last.stopped = true
      }
      last.errorCard = null
    },

    /**
     * R-163 T3/T5：赞踩上报（本地态在面板）。携正文快照 excerpt（F2）。
     *
     * <p>L-80（反馈失败静默）：返回值 = **是否真的记上了** —— 旧实现 `catch {}` 吞错，面板的选中态
     * 停在"已赞"，用户以为服务端收到了。现在失败如实返回 `false`，由面板回退本地态并给一条提示
     * （文案走 i18n，面板持有 `t`）。不在这里写 notice：store 的 notice 是**单槽**且与轮次结局
     * 同面，反馈失败不该覆盖对话主链的通知；且提示需要本地化文案，store 没有 `t`。
     */
    async submitFeedback(messageId: string, rating: 1 | -1 | 0, excerpt?: string): Promise<boolean> {
      if (rating !== 1 && rating !== -1 && rating !== 0) {
        return false
      }
      // Task 4 引导门：反馈属于对话域（带身份头 + 会话号），未就绪不发空 id。
      const sessionId = this.sessionId !== '' ? this.sessionId : await this.ensureSession()
      if (sessionId === '') {
        return false
      }
      try {
        await aiApi.feedbackSubmit({ messageId, sessionId, rating, excerpt })
        return true
      } catch {
        // 不打断对话（人工复盘数据，非关键链路）——但必须让调用方知道"没记上"
        return false
      }
    },

    // ── Task 4：身份目录 / 会话发号引导门 / 身份切换 ──────────────────────────

    /**
     * 拉取预置租户/用户目录（`GET /api/ai/tenants`，与后端 `TenantDirectoryController` 同一路径），
     * 并按"目录第一项"落默认身份。
     *
     * <p>口径（Task 4 返修 C2）：
     * <ul>
     * <li>已存身份仍在目录里 ⇒ 沿用（刷新/重开页签的恢复路径），随后 `ensureSession()` 取该身份的
     *   会话（有存量就用存量，没有才向后端发号）；</li>
     * <li>没有身份 / 身份已不在目录 ⇒ 落到**目录第一项**，进入身份模式并发号；旧身份会话与镜像
     *   原样留在旧身份键下（`keepMirror` / `keepSessionPointer`），不删除、不混用；</li>
     * <li>目录不可用（端点缺失 / 请求失败 / 空目录）⇒ `directoryStatus = 'unavailable'`：面板给
     *   明确横幅 + 重试，**发送禁用**。绝不静默切到"本地造号 + 无身份头"的旧分支。</li>
     * </ul>
     */
    /**
     * 目录加载的**单飞包装**（返修 I6）：面板不再 await 它来阻塞所有权判定/历史恢复 ——
     * 首次进入没有会话号时所有权判定会先跳过（I2：发号成功后补判），历史/发送由
     * `ensureSession()` 在需要时等待这份目录结果。
     */
    loadTenantDirectory(): Promise<void> {
      if (directoryGate !== null) {
        return directoryGate
      }
      const task = this.loadTenantDirectoryOnce().finally(() => {
        if (directoryGate === task) {
          directoryGate = null
        }
      })
      directoryGate = task
      return task
    },

    async loadTenantDirectoryOnce(): Promise<void> {
      const load = aiApi.tenantUsers
      if (typeof load !== 'function') {
        this.directoryStatus = 'unavailable'
        this.directoryError = translate('ai.notice.directoryFailed')
        return
      }
      this.directoryStatus = 'loading'
      this.directoryError = null
      let directory: TenantUsersEntry[] = []
      try {
        const raw = await load()
        directory = Array.isArray(raw) ? raw.map(normalizeTenantEntry).filter((entry): entry is TenantUsersEntry => entry !== null) : []
      } catch (error) {
        this.directoryStatus = 'unavailable'
        // 返修 round3：非 ApiError（裸 Error/网络异常原文）不得直接上屏 —— 本地化兜底。
        this.directoryError = error instanceof ApiError ? error.message : translate('ai.notice.directoryFailed')
        return
      }
      this.tenantDirectory = directory
      if (directory.length === 0) {
        this.directoryStatus = 'unavailable'
        this.directoryError = translate('ai.notice.directoryEmpty')
        return
      }
      this.directoryStatus = 'ready'
      const stored = this.identity
      if (stored !== null && directoryHas(directory, stored)) {
        await this.ensureSession()
        return
      }
      const firstTenant = directory[0]
      const firstUser = firstTenant.users[0]
      await this.enterIdentity({ tenantId: firstTenant.tenantId, userId: firstUser.userId })
    },

    /** 目录失败后的显式重试（面板横幅按钮）；成功则恢复身份与发号。 */
    async retryLoadTenantDirectory(): Promise<void> {
      // 返修：单飞句柄尚未清空时直接 `loadTenantDirectory()` 会复用刚结束的 promise（表现为
      // "点重试没反应"）。先等掉在途的那次，再**重新发起**一次目录加载（不是 no-op）。
      const inFlight = directoryGate
      if (inFlight !== null) {
        try {
          await inFlight
        } catch {
          // 目录加载自身的失败已在状态里；这里只等它落定
        }
      }
      this.directoryError = null
      await this.loadTenantDirectory()
    },

    /**
     * 身份不可用/会话未就绪时的**统一重试入口**（面板横幅按钮，返修 I-2/round3）：
     * 仅目录不可用时才重载目录；目录已就绪（只是发号失败）直接重新发号，避免多打一次 `/ai/tenants`。
     */
    async retryIdentitySession(): Promise<string> {
      if (this.directoryStatus !== 'ready') {
        await this.retryLoadTenantDirectory()
      }
      if (this.directoryStatus !== 'ready') {
        return ''
      }
      return await this.ensureSession()
    },

    /**
     * 切换租户/用户（面板下拉入口）。
     *
     * <p>切换**任一**维度（租户或用户）都等于换身份：旧会话与镜像留在旧身份下，本页签清屏并
     * 由后端发新会话号；旧身份在途轮的帧/回包由 `adoptFreshSession` 的轮次纪元 + 会话身份守卫丢弃。
     * 同一身份重复选择是空操作（不白换会话）。
     */
    async switchIdentity(tenantId: number, userId: number): Promise<void> {
      const next = normalizeIdentity({ tenantId, userId })
      if (next === null || isSameIdentity(next, this.identity)) {
        return
      }
      await this.enterIdentity(next)
    },

    /**
     * 进入（或切换到）某个身份：先清场（旧镜像/旧会话指针按旧身份保留），再落身份，再恢复或发号。
     *
     * <p>顺序不可反：
     * ① 清场时持有的是**旧身份**，镜像键/锁名按旧身份算；
     * ② 落身份之后才取号 —— 会话号必须属于新身份；
     * ③ 新身份**已有会话指针**（切回原身份）⇒ 先按所有权判定，拿得到就恢复原会话（镜像/历史）；
     *   指针不存在（首次进入）或该会话已被另一页签持有 ⇒ 由后端发新号（返修 I8/C1）。
     */
    async enterIdentity(identity: AiIdentity): Promise<void> {
      const pointer = storedSessionPointer(identity)
      this.adoptFreshSession({ keepMirror: true, keepSessionPointer: true })
      // 发号令牌在**清场之后**取：清场会递增令牌，之后的任何切换都会让本入口的续体作废。
      const entryToken = sessionGateToken
      writeAiIdentity(identity)
      this.identity = identity
      this.sessionIdentity = identity
      if (pointer !== '') {
        const verdict = await probeSessionLock(pointer)
        // 返修 I-1：探活是异步的（最多 3×150ms），期间用户可能已经又切了身份/换了会话。
        // 三道再校验缺一不可：令牌（清场/发号已换代）、身份（仍是本次目标）、会话号仍空
        // （别把 A 的号写到 B 已发号的会话上）。任一不符即**整体放弃**本次恢复。
        if (entryToken !== sessionGateToken
          || !isSameIdentity(this.identity, identity)
          || this.sessionId !== '') {
          // 返修 round3：本次探活若真的拿到了锁（verdict !== 'occupied'），必须**还回去** ——
          // 它已经把账本改成这把锁；带着"放弃"返回会把当前身份的会话锁句柄覆盖掉/泄漏，
          // 之后本页签再也认领不到自己的会话锁。这里只能用文档级 releaseSessionLock()
          // （释放的正是本次刚探到的那把）；不能用 releaseOwnLock(pointer)：它按**当前**身份
          // 重算锁名，身份已切走时名字不匹配 ⇒ 不会释放。
          if (verdict !== 'occupied') {
            releaseSessionLock()
          }
          return
        }
        if (verdict !== 'occupied') {
          this.sessionId = pointer
          this.sessionIdentity = identity
          this.sessionIssueFailed = false
          if (this.noticeCode === 'ai.notice.sessionIssueFailed') {
            clearNotice(this)
          }
          // 号确定之后才记 owner（发号失败/被抢占时不得留下"owner 但无会话"的假状态）。
          // 返修 round3：启动期（角色仍 unknown）走这条分支时，先补一次所有权判定，完成
          // BroadcastChannel / pagehide-pageshow 的绑定，否则本页签不会响应别人的 released。
          if (this.tabOwnership === 'unknown') {
            const pending = this.ensureTabOwnership()
            if (pending !== null) {
              void pending
            }
          }
          this.tabOwnership = 'owner'
          await this.loadHistory()
          void this.claimSessionOwnership(pointer)
          return
        }
        // 指针被另一页签持有：落到发号（原指针会被新号覆盖，属可接受的降级——旧会话在那里活着）。
      }
      const issued = await this.ensureSession()
      if (issued === '') {
        return
      }
      // 运行期显式切换：号已确定，记 owner；启动期（仍 unknown）交给 I2 的所有权补判。
      if (this.tabOwnership !== 'unknown') {
        this.tabOwnership = 'owner'
      }
      void this.claimSessionOwnership(issued)
    },

    /**
     * 会话发号引导门（Task 4）：**所有** AI 请求在取 `sessionId` 前必须经过这里。
     *
     * <ul>
     * <li>已有会话号 ⇒ 直接返回（刷新恢复 / 已发号）；</li>
     * <li>无身份（未接目录）⇒ 返回 ''，调用方按"无会话"处理，不得发空 id；</li>
     * <li>有身份无会话号 ⇒ `POST /api/ai/session` 发号，同一身份并发只发一次；身份在途被换掉
     *   （令牌/指纹不符）⇒ 丢弃该号，绝不写进新身份；</li>
     * <li>发号失败 ⇒ 返回 '' 并给出可见提示；绝不本地生成、绝不用空 id 兜底。</li>
     * </ul>
     */
    async ensureSession(): Promise<string> {
      if (this.sessionId !== '') {
        // 已有号即视为恢复：清掉上一次失败态/失败通知（如切回有指针的身份后）。
        if (this.sessionIssueFailed) {
          this.sessionIssueFailed = false
          if (this.noticeCode === 'ai.notice.sessionIssueFailed') {
            clearNotice(this)
          }
        }
        return this.sessionId
      }
      // 返修 I6：目录仍在加载时等它落定（面板不再为目录请求阻塞所有权判定/历史恢复）。
      // 只在"无身份"时等；`enterIdentity` 内部调用本方法时身份已落定，不会自等。
      if (this.identity === null && this.directoryStatus === 'loading' && directoryGate !== null) {
        await directoryGate
        // 等待期间目录落定可能已经完成发号（`enterIdentity` 的发号在目录门内）—— 直接复用。
        if (this.sessionId !== '') {
          return this.sessionId
        }
      }
      const identity = this.identity
      if (identity === null) {
        return ''
      }
      const fingerprint = identityFingerprint(identity)
      if (sessionGate !== null && sessionGateFingerprint === fingerprint) {
        return sessionGate
      }
      const issue = aiApi.createSession
      if (typeof issue !== 'function') {
        return ''
      }
      const token = ++sessionGateToken
      const task = (async (): Promise<string> => {
        let issued = ''
        try {
          const response: unknown = await issue()
          issued = typeof response === 'string'
            ? normalizeSessionId(response)
            : normalizeSessionId((response as { sessionId?: unknown } | null)?.sessionId)
        } catch {
          if (token === sessionGateToken) {
            // 返修 round3：显式失败态（横幅据此显示），与"正在发号"区分，避免正常窗口闪红条。
            this.sessionIssueFailed = true
            setNotice(this, 'ai.notice.sessionIssueFailed')
          }
          return ''
        }
        if (issued === '' || token !== sessionGateToken || !isSameIdentity(this.identity, identity)) {
          return ''
        }
        this.sessionIssueFailed = false
        // 重试成功后清掉上一次失败留下的单槽通知（否则横幅消失但红字还挂着）。
        if (this.noticeCode === 'ai.notice.sessionIssueFailed') {
          clearNotice(this)
        }
        this.sessionId = issued
        this.sessionIdentity = identity
        try {
          sessionStorage.setItem(sessionIdStorageKey(identity), issued)
        } catch {
          // 隐私模式/配额：本页签内存态照常使用（刷新后重新发号）
        }
        // 返修 I2：启动期若因"暂无会话号"跳过了所有权判定，发号成功后补判一次（含复制页签识别）。
        if (this.tabOwnership === 'unknown') {
          const pending = this.ensureTabOwnership()
          if (pending !== null) {
            void pending
          }
        }
        return issued
      })()
      sessionGate = task
      sessionGateFingerprint = fingerprint
      try {
        return await task
      } finally {
        if (sessionGate === task) {
          sessionGate = null
          sessionGateFingerprint = null
        }
      }
    },

    /**
     * RC-4：会话本地重置 —— 换新 id + 清屏 + 复位轮次/挂起/时钟，**不碰后端**。
     *
     * 抽取自「新建对话」（`resetSession`）：复制页签判定（`ensureTabOwnership`）与用户新建对话
     * 走**同一段**本地重置，差别只在后端收尾 —— 复制页签**不得**删旧会话的后端记忆
     * （那是原页签的会话），故本段独立出来只管本页签。
     */
    adoptFreshSession(options: { keepMirror?: boolean; keepSessionPointer?: boolean } = {}): string {
      const oldSessionId = this.sessionId
      const oldIdentity = this.sessionIdentity
      // 收口（③）：换 id 前只释放**本页签文档持有、且属于旧会话**的锁 —— 否则新会话的锁随后
      // 覆盖 `heldLock`，旧锁的释放句柄丢失，会一直占着到文档销毁（别的页签探活被误判"原页签还在"）。
      // 不广播 `released`：旧会话可能已被本页签销毁（新建对话），邀请别人接手只会接到一个空壳。
      releaseOwnLock(oldSessionId)
      // 待接手的让渡属于"上一身份"，换会话即作废
      cancelPendingTakeover()
      // B1 收口修（C-1）：换会话 = 真正换轮 —— 旧会话在途的前端工具结局不得写进新会话
      roundEpoch += 1
      // B2：旧会话排队中的工具随会话作废（判据见 `enqueueFrontendTool`）
      clearFrontendToolChains()
      // M-2b：旧会话的快照串行队列不得落进新会话
      this.restoredPendingQueue = []
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
      /**
       * 换 id 的口径（Task 4 返修 C2）：**不生成**，置 ''，等 `ensureSession()` 向后端发号。
       * 前端没有任何"本地造会话号"的分支 —— 身份模式与无身份模式都一视同仁。
       * 修复（苞 A 必修）：此前只写 sessionStorage、从不写 this.sessionId ⇒ `send` 仍带旧 id 请求、
       * "新建对话"实际继承旧会话的服务端记忆（实测缺陷，见施工报告「必修前置缺陷」）。
       */
      const nextId = ''
      const scopeIdentity = this.identity
      try {
        // 旧镜像按**旧身份**键删（保留开关见 keepMirror：身份切换/接管要留下旧身份现场）。
        if (!options.keepMirror && oldSessionId !== '') {
          sessionStorage.removeItem(mirrorStorageKey(oldSessionId, oldIdentity))
        }
        // 会话指针：普通换会话（新建对话/410/复制页签）要抹掉，避免刷新把已销毁的会话复活；
        // 身份切换（keepSessionPointer）保留旧身份的指针，切回时可恢复原会话（返修 I8）。
        if (!options.keepSessionPointer) {
          sessionStorage.removeItem(sessionIdStorageKey(scopeIdentity))
        }
      } catch {
        // 隐私模式/配额：持久化失败不阻断本次重置（本页签内照常换 id）
      }
      // 发号令牌递增：在途的旧发号回包一律作废（不得写进这次换出来的会话）。
      sessionGateToken += 1
      sessionGate = null
      sessionGateFingerprint = null
      this.sessionId = nextId
      this.sessionIdentity = scopeIdentity
      // 换会话/换身份 ⇒ 上一次发号失败的结论不带走（新窗口重新判定）。
      this.sessionIssueFailed = false
      this.messages = []
      this.activeRunId = null
      // B2：帧序号锚点属于旧会话的旧 run —— 换会话即清（否则长会话里 runId→seq 只增不减）
      this.runSeq = {}
      // AR-10：运行态寿命锚点属于旧会话/旧轮 —— 不带走（卡面寿命只对本轮成立）
      this.runExpiry = null
      clearNotice(this)
      this.stopping = false
      this.poolSaturated = null
      this.suspended = false
      // 批次2：恢复失败现场 / 上下文加载态 / 缺口 / 409 手动入口都属于旧会话 —— 一律不带走
      this.historyError = null
      this.currentError = null
      this.contextError = null
      this.contextStatus = 'idle'
      this.recoveryGap = null
      this.busyRun = null
      // M08 / O02：连接登记位与"已停止的那一轮"同属旧会话现场，不带走
      this.activeStreamRunId = null
      this.activeStreamParked = false
      this.stoppedRunId = null
      // M-19：停止意向同属旧会话现场（不得跨会话取消任何轮）
      this.stopIntent = null
      // M08-R2：驱动所有权/预算同样是旧会话现场
      this.forgetRunStreamDrive()
      // 2026-10-29 收口：自动恢复的排期与预算同属旧会话现场（不得把旧会话的轮接进新会话）
      cancelAutoRecovery()
      this.autoRecoveryUsed = {}
      this.restoringRecovery = false
      // 批次6B（N7）：工作区 chip / ui_event 回执 / 上一轮结局都是**上一会话的现场** ——
      // 不清会让新会话第一条无害通知被旧轮 error 染红（通知级别本身已随写入落 `noticeSeverity`）。
      this.contextSync = null
      this.lastUiEventResult = null
      this.lastRoundOutcome = 'ok'
      this.dropActiveForm()
      // B12：预览属于旧会话 —— 计划与预览色一起丢弃（"新建对话"不是换个盒子继续改这张表）
      this.dropSheetPreview('session')
      // AI 可视可操作：界面撤销点 / 高亮 / 播报 / 上传交接随会话收口
      // （resetUiSurfaces 此前无调用点 = 死代码，本批接到会话与页面两个出口）
      this.resetUiSurfaces()
      // R-166（口径③）：提交在途态随会话清空（防跨会话串味）
      this.clearGateSubmissions()
      // 批4④ A5'/D4：折叠展开态随会话清空（防跨会话串味；会话内内存态，不进镜像）
      this.expandedBlocks = {}
      // R-155：发布冲突 jobId→工具段注册表随会话清空（内存态，与折叠展开态同生命周期口径）
      clearPublishToolLinks()
      // 轮次级标志一并复位：新会话不该继承上一轮的停止/断流事实（骨架屏见 historyLoaded）
      this.stopped = false
      this.streamInterrupted = false
      // 批 E · E2：新会话不继承上一会话的建议条
      this.followupReady = false
      // 上下文专项批①：占用与拆分随会话清空（跨会话串味 = 拿上一会话的 84k 配新会话）
      this.contextUsage = null
      this.contextBreakdown = null
      this.contextCompaction = null
      // 批次3：压缩/准备中阶段是内存态，跨会话不带走
      this.resetCompactionProgress()
      this.clearPreparingRun()
      // 批次6B（N10）：换会话 = 旧草稿全部失去引用 ⇒ 按 draft 前缀清孤儿键
      // （活动/排队表单已在上面的 dropActiveForm 收口；禁止整份 sessionStorage.clear()）。
      // Task 4：身份切换（keepMirror）保留旧身份现场 —— 旧草稿也留在旧身份前缀下，不在这里清。
      if (!options.keepMirror) {
        this.pruneOrphanFormDrafts()
      }
      this.historyLoaded = true
      return nextId
    },

    /**
     * RC-4：解析本页签的会话所有权（三层握手入口，面板挂载时调用一次）。
     *
     * - **原页签**（锁到手）⇒ 沿用会话 id，广播 `claim`；
     * - **环境无 Web Locks**（旧浏览器 / jsdom）⇒ 'unsupported'，按**恢复**处理（保守）：
     *   这一段**同步**收口并返回 `null` —— 调用方（面板 onMounted）不必为一次空判定多等
     *   一个 microtask，既有挂载时序一格不动；
     * - **复制页签**（锁被原页签持有）⇒ 对本页签整份 `sessionStorage` 清**一次**（继承的会话镜像、
     *   未提交表单草稿、业务页签键都不带走；`localStorage` 不动），随后 `adoptFreshSession()` 起
     *   新会话 + 轮换 pageId：**不接管**原会话、**不删**原会话的后端记忆；复制提示保持静默；
     *   让渡监听随之装上（原页签关闭后由 `onOwnershipMessage` 决定是否接手）；
     * - **慢重载回来的原页签**（带着 `pagehide` 写下的返回意向、锁却已被副本接手）⇒ 只把角色
     *   降级为 'duplicate'：**不清会话、不清屏、不轮换 pageId、不认领锁**。这是"比宽限还慢的重载"
     *   那条兜底 —— 会话确实不归本页签了，但用户眼前的记录不该被当成复制品抹掉。
     *
     * 幂等：判定过一次（角色非 'unknown'）即不再重复探活。
     *
     * @returns 需要等待的判定 promise；`null` = 已同步判定完（无锁能力），调用方无需 await
     */
    ensureTabOwnership(): Promise<void> | null {
      if (this.tabOwnership !== 'unknown') {
        return null
      }
      // 同一页签可能被多处触发（面板挂载 + 发号成功后的补判）：只跑一次探活。
      if (ownershipProbe !== null) {
        return ownershipProbe
      }
      // Task 4：没有后端发号的会话时没有可判/可认领的锁（空 id 不参与所有权判定）。
      // 发号成功后 `ensureSession()` 会再调一次本方法补判（返修 I2）。
      if (this.sessionId === '') {
        return null
      }
      // 生命周期监听只绑一次，回调里的 store 面取**最近一次**判定的实例（pinia 可能被换掉）
      tabStore = this
      // 上一身份的待接手一律作废（重新判定角色 = 从头谈）
      cancelPendingTakeover()
      ensurePageId()
      openOwnershipChannel((message) => this.onOwnershipMessage(message))
      bindOwnershipLifecycle()
      if (!lockManager()) {
        this.tabOwnership = 'unsupported'
        return null
      }
      const sid = this.sessionId
      // 返回意向一次性消费：无论这次判成 owner 还是 duplicate，都不能留到下一次刷新
      const returning = takeReturnIntent(sid)
      const task = (async (): Promise<void> => {
        const role = await resolveTabOwnership(sid)
        if (this.sessionId !== sid) {
          // 探活在途期间会话已被换掉（新建对话 / 让渡）：不为旧会话落任何角色
          return
        }
        this.tabOwnership = role
        if (role === 'owner') {
          ownedSessionId = sid
          postOwnership('claim', sid)
          return
        }
        if (role === 'duplicate') {
          if (returning) {
            // 回来的原页签：会话已被别人接手 —— 降级但不清场（见方法注释）
            return
          }
          // 复制页签：确认复制之后，先对本页签整份 sessionStorage 清一次（继承来的镜像、未提交
          // 草稿、业务页签键一律不带走），再起新会话、轮换 pageId（同源广播里两个页签的 pageId
          // 必须可区分）。顺序不可反：先 adopt/rotate 再 clear 会把刚写下的新身份抹掉。
          clearDuplicatedTabStorage(this.identity)
          this.adoptFreshSession()
          // 复制页签只换本地会话并保持静默；不提供接回入口，也不在这里读原会话历史。
          rotatePageId()
          // Task 4：先判完所有权（到这里才确定"本页签要自己的会话"）再向后端取号；
          // 取号失败就保持"未就绪"（后续请求被引导门拦住），绝不发空 id。
          const issued = await this.ensureSession()
          if (issued !== '') {
            this.tabOwnership = 'owner'
            await this.claimSessionOwnership(issued)
          }
        }
      })()
      const tracked = task.finally(() => {
        if (ownershipProbe === tracked) {
          ownershipProbe = null
        }
      })
      ownershipProbe = tracked
      return tracked
    },

    /**
     * RC-4 ④：`pageshow` 后的有界重认领 / 降级（bfcache 恢复时角色可能已过期）。
     *
     * <p>只在"本页签自认 owner"时动作，且以锁为唯一裁决（`resolveTabOwnership` 限次重试）：
     * 锁仍归我 ⇒ 重新广播 `claim`（别的页签此间可能已把本页签当"离开"）；锁已归别人 ⇒ 角色降级为
     * 'duplicate' 并**停止自认 owner**（不再广播 claim），但**不清会话、不清屏** ——
     * bfcache 恢复不是"新建对话"，清屏会把用户刚恢复的记录抹掉。
     *
     * <p>取舍：账本里的 `heldLock` 无法证明浏览器在冻结期间是否仍替我们持锁（自持锁的
     * `ifAvailable` 探活会短路成 granted），所以这里**先释放、再真探**。释放到重新认领之间有一个
     * 微任务级窗口：真有别的页签在此刻抢先，它按锁就是新 owner，本页签降级 —— 这正是"以锁为准"，
     * 比"两个页签同时自认 owner"安全。
     */
    async revalidateTabOwnership(): Promise<void> {
      const sid = this.sessionId
      if (sid === null || this.tabOwnership !== 'owner' || ownedSessionId !== sid) {
        return
      }
      // bfcache 冻结期间浏览器可能已经释放了本页签的锁，而账本里的 `heldLock` 仍是旧引用
      // （自持锁的 ifAvailable 探活会因短路而谎报 granted）⇒ 先扔掉账本、再**真**探一次。
      // 仍然空闲 ⇒ 锁还是我们的（重新认领）；已被别人拿走 ⇒ 对方就是新 owner（降级）。
      releaseSessionLock()
      const role = await resolveTabOwnership(sid)
      // 探活在途期间现场已变（换会话 / 已降级）⇒ 不落任何结论
      if (this.sessionId !== sid || this.tabOwnership !== 'owner') {
        return
      }
      if (role === 'owner') {
        ownedSessionId = sid
        postOwnership('claim', sid)
        return
      }
      if (role === 'duplicate') {
        this.tabOwnership = 'duplicate'
        cancelPendingTakeover()
      }
    },

    /** RC-4 ②：为本页签会话认领所有权锁（新会话 id 必然空闲），拿到后广播 `claim`。 */
    async claimSessionOwnership(sessionId: string): Promise<void> {
      if (sessionId === '') {
        return
      }
      const verdict = await probeSessionLock(sessionId)
      if (verdict !== 'granted' || this.sessionId !== sessionId) {
        return
      }
      ownedSessionId = sessionId
      postOwnership('claim', sessionId)
    },

    /**
     * RC-4 ③：所有权广播处理。
     *
     * - `claim`：原页签（刷新回来的新文档）重新认领 ⇒ 撤销本页签待接手的让渡（宽限的意义所在）；
     * - `released`：**让渡意向**，不是"立刻交接" —— 空白副本等 {@link OWNERSHIP_RELEASE_GRACE_MS}
     *   再探活接手（`takeOverSession` 内的探活仍是最终裁决）。
     *
     * 只有"被判为复制品、且自己这边**还没产生任何内容**"的页签接手 —— 已经在自己会话里说过话的
     * 页签不换会话（换掉会让用户眼前内容跳变）。其余消息（自己的回声 / 别人的会话 id）一律忽略。
     */
    onOwnershipMessage(message: OwnershipMessage): void {
      // Task 4：不同身份（租户/用户）的页签不互相认领/让渡 —— 接管必须同 tenant + user。
      if (!ownershipIdentityMatches(message, this.identity)) {
        return
      }
      if (message.sessionId === this.sessionId) {
        return
      }
      if (message.kind === 'claim') {
        if (pendingTakeover?.sessionId === message.sessionId) {
          cancelPendingTakeover()
        }
        return
      }
      if (this.tabOwnership !== 'duplicate') {
        return
      }
      if (this.messages.length > 0 || this.loading || this.activeRunId !== null) {
        return
      }
      // 宽限后再探活：原页签在此期间重播 claim（刷新回来）⇒ 计时器已撤销，本页签不抢
      cancelPendingTakeover()
      const sessionId = message.sessionId
      const timer = setTimeout(() => {
        pendingTakeover = null
        // 宽限期间角色可能已被换掉（用户新建对话 / 接管完成 / 降级）：只有仍是"空白复制品"才接手
        if (this.tabOwnership !== 'duplicate') {
          return
        }
        void this.takeOverSession(sessionId)
      }, OWNERSHIP_RELEASE_GRACE_MS)
      pendingTakeover = { sessionId, timer }
    },

    /**
     * RC-4 ③：接手让渡过来的会话 —— **以锁为准**：先探活（原页签的锁随文档销毁释放，探到才认账），
     * 再换 id + 读回该会话历史。二次判据同 `onOwnershipMessage`（探活是异步的，期间可能已说话）。
     *
     * <p>账本口径：`probeSessionLock` 一拿到锁就**就地覆盖** `heldLock`/`ownedSessionId`（这是"本页签
     * 只持一把锁"的记账方式），所以旧空白会话那把锁的释放句柄必须在探活**之前**捕获 —— 否则句柄被
     * 覆盖后再也没有释放点，会挂到文档销毁。二次判据拒绝接手时同样要把刚探到的锁还回去，
     * 保持"本页签只持自己会话的锁"。
     */
    async takeOverSession(sessionId: string): Promise<void> {
      const previous = this.sessionId
      const previousLock = ownedSessionId === previous ? heldLock : null
      const identityAtEntry = this.identity
      const verdict = await probeSessionLock(sessionId)
      if (verdict !== 'granted') {
        return
      }
      // Task 4：探活是异步的，期间身份可能已切走 —— 不同身份不得接手（锁已探到也要还回去）。
      if (!isSameIdentity(identityAtEntry, this.identity)) {
        releaseOwnLock(sessionId)
        return
      }
      if (this.messages.length > 0 || this.loading || this.activeRunId !== null) {
        // 探活已把账本换成"待接手"的锁，但二次判据不允许接手 ⇒ 立刻还回去（不占别人的会话）
        releaseOwnLock(sessionId)
        return
      }
      if (previousLock !== null) {
        // 放弃本页签那个空白会话：把它的所有权一并让出去（不占着不再使用的锁）
        postOwnership('released', previous)
        try {
          previousLock.release()
        } catch {
          // 释放失败只影响让渡时序
        }
      }
      this.sessionId = sessionId
      this.sessionIdentity = this.identity
      this.sessionIssueFailed = false
      if (this.noticeCode === 'ai.notice.sessionIssueFailed') {
        clearNotice(this)
      }
      try {
        // 显式绑定到本页签身份（不依赖默认参数），避免身份事实源分叉时写裸键（返修 M6）。
        sessionStorage.setItem(sessionIdStorageKey(this.identity), sessionId)
        sessionStorage.removeItem(mirrorStorageKey(previous, this.identity))
      } catch {
        // 持久化失败：本页签内存里仍按接手后的会话走
      }
      this.tabOwnership = 'owner'
      ownedSessionId = sessionId
      postOwnership('claim', sessionId)
      // 批次6B（N7）：接手的是**另一个会话**，上一身份的 chip / ui_event 回执 / 轮结局不带走。
      this.contextSync = null
      this.lastUiEventResult = null
      this.lastRoundOutcome = 'ok'
      await this.loadHistory()
      // 批次6B（N10）：历史载入后再清孤儿草稿 —— 此时"在场"（仍待动作的表单卡）才是最终集合。
      this.pruneOrphanFormDrafts()
    },

    /**
     * 新建对话（页签内重置会话）：立即清屏 + 换新 sessionId + 完整销毁旧会话。
     *
     * 顺序（苞 A 项 1 裁决 + 2026-10-25 会话销毁口径）：
     * 1. 先在跑的轮次 `abort`（R5①）——否则 loading 不复位、后续帧会写进已清空的 messages；
     * 2. 立刻换 id 并清状态（**同步完成**：点击=立即清屏，不等后端）；
     * 3. 再 best-effort `POST /api/ai/cancel/{oldRunId}` 通知后端停旧轮（后端不可达不阻断本地重置）；
     * 4. **最后**调销毁端点（前端走 `DELETE /api/ai/session/{oldSessionId}`，后端旧
     *    `DELETE /api/ai/history/{oldSessionId}` 也映射到同一 purge）：删除该会话全部 AI 数据，
     *    同时用响应 `clientCleanup` 精确清浏览器镜像/草稿。
     *
     * <p>口径更新：旧实现的"只删记忆窗口 + 在飞轮终局可能写回"已被服务端 purge guard 关闭
     * （记忆 `saveAll` 在守卫命中/不可知时拒绝写回）。前端只在
     * `complete=false`（deferred/errors/aborted）时用 `sessionPurgeIncomplete` 提示"整会话未删净"，
     * 仍不阻断新对话；`complete` 字段缺失时按完成兼容。
     */
    async resetSession(): Promise<void> {
      // 两个旧值必须在清状态之前取：oldRunId 用于取消旧轮，oldSessionId 用于销毁与清本地残留
      const oldSessionId = this.sessionId
      const oldRunId = this.activeRunId
      // 销毁回包会用到**旧身份**的镜像键/草稿前缀 —— 必须在清场之前捕获。
      const purgeIdentity = this.identity
      this.adoptFreshSession()
      // Task 4：先判完所有权（本页签新建）再向后端取号；取号失败则保持未就绪，不得用空 id 发请求。
      const issued = this.sessionId !== '' ? this.sessionId : await this.ensureSession()
      // RC-4：新会话的锁归本页签（新发号必然空闲）；用户显式新建对话后不再算"待接手的复制品"
      this.tabOwnership = 'owner'
      if (issued !== '') {
        void this.claimSessionOwnership(issued)
      }
      // best-effort 停掉后端仍在跑的旧轮（只断本地连接不停后端，故必须显式 POST）
      if (oldRunId) {
        try {
          await aiApi.cancel(oldRunId)
        } catch {
          // 忽略：取消请求失败只意味着后端可能多跑一轮，不影响新建对话
        }
      }
      // 销毁放最后：服务端先 cancel/守卫再删；前端只消费精确清理指令。
      // F5（复核收口）：失败提示是**异步落地**的（后端不可达时最坏要等到 HTTP 超时），故必须带
      // 「仍是本轮」守卫 —— 这只在本次新建对话换出的会话里、且其间没有发生任何轮次时才允许写提示；
      // 否则这条过期提示会盖掉新会话/新轮的 notice（store 其余异步落盘点都用 isCurrentRound 同族守卫，
      // 本处"当前轮"的判据 = 会话 id 仍是 nextId（未被再次新建对话换掉）且 messages 仍为空
      // （send 会同步 record 用户消息，故 messages 非空 ⇔ 新会话里已经起过轮次）。
      const isCurrentRound = (): boolean => this.sessionId === issued && this.messages.length === 0
      // 空 id 不请求（旧会话本来就没发过号：没有可销毁的服务端会话）
      if (oldSessionId === '') {
        return
      }
      try {
        const purge = await aiApi.deleteHistory(oldSessionId)
        cleanupSessionLocalArtifacts(oldSessionId, purge, purgeIdentity)
        // complete=false（含 deferred/errors/aborted）不得当成功：只提示，不阻塞新对话
        if (purgeWasIncomplete(purge) && isCurrentRound()) {
          setNotice(this, 'ai.notice.sessionPurgeIncomplete')
        }
      } catch {
        // 请求失败：旧会话可能未删净；镜像仍按精确键清一次（草稿 id 未知，当前活动表单已清）
        cleanupSessionLocalArtifacts(oldSessionId, null, purgeIdentity)
        if (isCurrentRound()) {
          setNotice(this, 'ai.notice.sessionPurgeIncomplete')
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
       * P-11 终态闸门（2026-10-22 修复）：用户停止（或本轮已终态收尾）之后到达的**非终态帧**
       * 一律丢弃 —— 与 `api/sse.ts` 读循环的终帧闸门同口径。
       *
       * <p>修掉的缺陷：`stop()` 已把本轮助手消息 `done=true` 封口并就地收敛（工具段 → cancelled），
       * 但宿主的唯一解析口 `currentAssistantMessage()` 是"找最后一条 `!done` 的助手消息，
       * 找不到就 **append 一条新的**" —— 于是停止后迟到的 `delta` 会写进一条**全新的空气泡**
       * （实测 messages 2 → 3 条），迟到的 `tool_start` 会新建工具卡，并继续写镜像。
       *
       * <p>闸门必须放在**宿主解析之前**：一旦先解析，空气泡已经被 append 出来，再丢帧也回不去了。
       * 终态帧仍放行（abort 竞态下 `done(cancelled)` 可能已经到达，轮次级收口需要它）。
       * `stopped` 在每个新连接建立处（send / reattachActive / resumeSnapshotStream /
       * restoreInFlightRound / resetSession）复位 ⇒ 本闸门只作用于被停止的那一轮，不跨轮生效。
       */
      if (this.stopped && !isTerminalFrame(frame)) {
        return
      }
      /**
       * R2/R6（issue#5）帧归属判定：只有"本轮"（宿主消息仍是当前轮的宿主）的帧才允许改写
       * **轮次级**状态（activeRunId / loading / suspended / notice / activeForm）——旧轮迟到帧
       * （resetSession 之后、或新轮已起）只收敛它自己那条消息，不能把新轮标记成空闲。
       */
      const ownedByActiveRound = assistantId === undefined || assistantId === activeRoundHostId
      /**
       * 复审返修（M-1）：进度通知清理**排在归属判定之后** —— `notice` 是轮次级状态，旧轮迟到帧
       * 一律不得改写（否则 `stop()` 保留的 pre-start 传输在下一轮建立后迟到的终态帧，会把新轮的
       * "正在重连"清掉）。清理本身仍覆盖 onOpen / 首业务帧 / done（终态也是业务帧）。
       */
      if (ownedByActiveRound && frame.type !== 'heartbeat') {
        clearReconnectProgressNotice(this)
      }
      /**
       * P-11 宿主解析（停止轮）：放行的终态帧同样**不得新建空气泡** —— `stop()` 已把本轮宿主
       * 封口（`done=true`），若仍走 `currentAssistantMessage()` 的"找不到 `!done` 就 append"
       * 分支，`done(cancelled)` 一到屏上就凭空多一条空消息（实测 messages 2 → 3）。停止轮只认
       * 已存在的助手消息（优先本轮宿主），一条都没有就整帧丢弃 —— 轮次级收口 `stop()` 早已做完。
       */
      const sealedRoundHost = (): ChatMessage | null =>
        this.messages.find((item) => item.id === activeRoundHostId && item.role === 'assistant')
        ?? [...this.messages].reverse().find((item) => item.role === 'assistant')
        ?? null
      const foundHost = assistantId ? this.messages.find((item) => item.id === assistantId) : undefined
      const message = foundHost
        ?? (this.stopped
          ? (ownedByActiveRound ? sealedRoundHost() : null)
          : (ownedByActiveRound ? this.currentAssistantMessage() : null))
      if (!message) {
        // 宿主消息已不在（会话已重置/历史已整体替换）且该帧不属于当前轮 ⇒ 整帧丢弃，
        // 不再凭空 append 一条孤儿助手消息（G5）。
        // 停止轮（上文的 sealedRoundHost 返回 null）同理：屏上已无任何助手消息可收敛 ⇒ 整帧丢弃。
        return
      }

      // T6：按 runId 记录最大帧序号（reattach 时回传，服务端只补增量）
      const seq = frameSeq(frame)

      if (!ownedByActiveRound) {
        /**
         * 旧轮迟到帧（R2/R6）：只收敛它自己那条消息的收尾，**一律不改写轮次级状态**
         * （`loading`/`activeRunId`/`suspended`/`notice`/`activeForm`）——否则旧轮的 done/error
         * 会把正在跑的新轮标记成空闲，旧轮的 retry/取消提示会覆盖新轮提示（G2/G3/G7）。
         */
        // N4（R2）：**先证明归属再记 seq**。这里只有"自带 runId"的迟到帧可以记 —— 它记的是
        // **它自己那个 run** 的锚点（自证归属）；没有 runId 的旧帧一律不记：`frame.runId ?? activeRunId`
        // 会把它借用成**新轮**的锚点，下一次 reattach 就以错误的 lastSeq 起收（缺帧）。
        if (seq !== null && frame.runId !== undefined && frame.runId !== '') {
          this.runSeq = recordRunSeq(this.runSeq, frame.runId, seq)
        }
        if (isTerminalFrame(frame)) {
          message.streaming = false
          message.done = true
          // O02：即便这帧属于旧轮的宿主，只要它就是忙提示所指的那一轮，入口也必须作废
          //（"回到进行中的回答"只对仍在进行的轮成立）——轮次级状态之外，busyRun 是纯指针。
          this.clearBusyRunOnTerminal(frame.runId)
          // M08-R2：终态 = 该 run 的驱动使命结束，所有权与预算一并释放
          //（runId 缺失时**不得**当作"释放全部"——那会误伤其它在途轮）
          if (frame.runId !== undefined) {
            this.forgetRunStreamDrive(frame.runId)
          }
        }
        return
      }

      // N4（R2）：本轮帧的 seq 记账放在归属判定**之后**（旧帧已在上面 return）。
      if (seq !== null) {
        const runId = frame.runId ?? this.activeRunId
        if (runId) {
          this.runSeq = recordRunSeq(this.runSeq, runId, seq)
        }
      }

      // R2-1（批2）：seq 幂等 —— 同一帧被重复投递（reattach 全量回放 / 双连接竞态）时整帧跳过：
      // 不重复累加文本、不重建段、不重放工具卡。只做"同序号去重"，**不做连续性判定** ——
      // 心跳不占业务 seq、归档续收本就会跳号，序号断层是正常现象（R2-3）。
      if (seq !== null && alreadyApplied(message, seq)) {
        return
      }

      // 批3③b D6(B)（R-141①，店级守卫）：跨宿主"已决"终帧守卫 —— 重挂/续收回放时，若帧的
      // toolCallId 已在**其他消息**上持有已决段（hasDecidedPendingResidue，统一 8 态），且当前
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

      // 批次3：业务帧一到就说明快照已建、本轮已过"准备中"阶段（心跳不在此列）。
      if (frame.type !== 'heartbeat' && this.preparingRun) {
        this.clearPreparingRun()
      }

      switch (frame.type) {
        case 'start':
          this.activeRunId = frame.runId
          // M08：本轮 runId 一到就登记活动连接（single-flight 判据的一部分：send 开的流同样
          // 占有这个 runId，恢复入口据此并入而不是另开一条）。
          this.activeStreamRunId = frame.runId
          // 批次2：把 run 归属钉在宿主消息上 —— 「重试本轮」只认它（刷新后 activeRunId 可能为空/换轮）
          if (typeof frame.runId === 'string' && frame.runId !== '') {
            message.runId = frame.runId
          }
          // 批次3：start 仍是首帧（压缩已前移到它之后）—— 新轮的阶段态从零开始；
          // 普通轮的 streaming 心跳不改任何阶段条；只有真实 L2 的 compacting 心跳才落"正在压缩"。
          this.resetCompactionProgress()
          this.clearPreparingRun()
          // 批次3 / AR-06：runId 一到就落一次镜像。否则刷新落在节流窗口（300ms）内时，
          // 本地没有 runId 可探测，压缩窗口会被误判成空态。
          // 只在本轮历史已就绪时写：loadHistory/回放期间的空态无权覆盖更完整的现场。
          if (this.historyLoaded) {
            this.writeMirrorNow()
          }
          clearNotice(this)
          break
        case 'message_start':
        case 'message_end':
          // T5 三段式的 start/end：本段边界（正文/思考段共用同一套帧）—— 只写消息身份与段边界
          // （message_end 顺带封口末尾流式段，o-R1），**不改段结构**（R2-2：段收口只发生在工具类帧
          // 与轮终态）。批2：写入统一走 applyFrameToParts。
          if (typeof frame.runId === 'string' && frame.runId !== '' && !message.runId) {
            message.runId = frame.runId
          }
          applyFrameToParts(message, frame)
          break
        case 'delta': {
          // 高频路径（§6.1 性能注）：段文本与派生投影 **锁步** `+=`，不调 deriveProjections
          //（全量重算会每 token 替换 toolRuns 数组 ⇒ 工具卡列表整列重渲染）
          //
          // L-91：段归属看帧上的 additive 键 `kind` —— 缺省 = 正文（旧帧）；`'reasoning'` = 思考段。
          // 思考文本进 `reasoning` 投影、正文进 `content` 投影，两者同源（parts 段）但互不混入
          //（`deriveProjections` 的 join 口径与这里逐帧累加必须一致，见 utils/parts.ts）。
          if (frame.kind === 'reasoning') {
            message.reasoning += frame.text
          } else {
            message.content += frame.text
          }
          applyFrameToParts(message, frame)
          break
        }
        case 'reasoning': {
          // 裁决⑦：有 reasoning 帧才渲染思考链（无帧则 reasoning 恒空 → 面板不显示折叠块）
          // 旧形态兜底（L-91 起后端改发 delta{kind:'reasoning'}；本条只服务旧桩/旧归档回放）
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
          // 批 D · D3（问题 6）：start_export / start_precheck / start_import 取号 → 同步给向导页
          this.armStartJobSync(message, frame)
          break
        }
        case 'confirm_request': {
          // 批3③b D8（R-141③）：已决 toolCallId 的重投帧不产生新挂起语义 —— 守卫去 GF 前缀、
          // 扩到全部工具（判据统一 8 态）。实现取 break：seq 记账与 markApplied（switch 后统一
          // 收口）照常执行，**语义再投以本闸兜底**（与 seq 幂等的职责分界：闸管语义、seq 管重复）。
          if (this.hasDecidedPendingResidue(frame.toolCallId)) {
            break
          }
          this.suspended = true
          // 批4-D：同 toolCallId 的勾选态**变更即复位** —— 新请求帧到达即视为一张新卡，
          // 上一轮勾选绝不继承（否则"已同意"会变成默认值）。
          this.clearGateAck(frame.toolCallId)
          // 工具卡四态之"待确认"：确认门未决期间该工具卡显示为待确认（段 status=pending +
          // requiresAction=true ⇒ 派生出挂起卡）；原始帧口径随段持久化（对账用，见 ToolPart 注）
          applyFrameToParts(message, frame)
          break
        }
        case 'confirm_decision': {
          // approved → running、rejected → rejected 的段裁决在 applyFrameToParts；
          // 挂起卡随 requiresAction=false 派生为 null（结局由工具卡承载）
          applyFrameToParts(message, frame)
          // R-166 口径③：结局帧到达即撤提交在途态（409 瞬态的就地收敛口）
          this.clearGateSubmission(frame.toolCallId)
          // 批4-D：结局落定 ⇒ 勾选态复位（变更即复位：这一版卡的同意不外溢）
          this.clearGateAck(frame.toolCallId)
          // M2：段已落终态后才计数 —— 多卡并存时另一张仍待决 ⇒ 保持挂起态
          this.clearSuspendedIfNoOtherConfirms(frame.toolCallId)
          break
        }
        case 'frontend_tool_request': {
          // 批3③b D8（R-141③）：守卫**去 GF 前缀** —— 通用 FRONTEND 工具的重投帧同样被拦：
          // 不再触发第二次 runFrontendTool（二次浏览器副作用）、不把已终态段改回
          // pending+requiresAction（挂起卡复活）。已决判据统一 8 态（含 unknown/failed/skipped）。
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
          // AI 表格操作专项（2026-10-21）：「前端工具没有 HITL」的旧口径已订正 ——
          // 写表格工具与 generative_form 同族，等的是**人**：先建预览卡（计数/采样/全量三档），
          // 用户点“应用/取消”后才回灌；因此不进 runFrontendTool，也不受 60s 前端工具预算约束。
          if (needsSheetPreview(frame.name, parseToolArgs(frame.args))) {
            void this.openWritePreview(message, frame)
            break
          }
          // AI 可视可操作专项：上传交接是「等人手势」的工具（与 GF 同族，绝不能让 60s 预算兜底：
          // showOpenFilePicker 只认用户手势的同步调用栈）。建卡后**不回灌**，等用户选文件/放弃。
          if (UI_HANDOFF_TOOLS.has(frame.name)) {
            void this.openUploadHandoff(message, frame)
            break
          }
          // 其余即时执行类前端工具：立即在浏览器执行并回灌结果
          // 批次 5B（Task 8）：**同一助手消息内串行** —— 每条排到该消息队尾，前一条的回灌
          // 发出后才启动下一条（并发会让紧随导航的动作打在"页面还没挂载 / 任务还没绑定"的窗口上）
          // B2：**入队即捕获轮次现场** —— 排队期间被停止/换会话后，这条不执行任何动作
          const round = captureRound(this.sessionId, frame.runId ?? this.activeRunId ?? null)
          enqueueFrontendTool(
            message.id,
            () => stillSameRound(round, this.sessionId, this.activeRunId ?? null),
            () => this.runFrontendTool(message, frame, round)
          )
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
          // 2A② 边缘①：frame-only（POST 应答丢失 / 未回）时，提交值不能只落帧宿主 —— 同
          // toolCallId 的其他宿主段也按 first-wins 补齐。当前宿主已由 applyFrameToParts 解析并
          // 写入（或保留更早的值），这里以**帧宿主的落定值**为准对全量消息幂等重放同一写口：
          // 空宿主补齐、已有值不覆盖，避免多宿主出现两套值。
          const settled = findToolPart(message, frame.toolCallId)
          if (settled?.name === GENERATIVE_FORM_TOOL && settled.status === 'succeeded'
            && settled.submittedValues) {
            this.writeSubmittedValues(frame.toolCallId, settled.submittedValues, false)
          }
          if (this.activeForm?.toolCallId === frame.toolCallId) {
            // 结局帧已到：表单收敛（提交/取消/超时皆自此收尾）。
            // T2a-D#2（B2 缓释）：帧到达时本地仍在填写（未在提交在途）→ 该表单已被他端提交/处理
            if (this.activeForm.status === 'filling') {
              setNotice(this, 'ai.notice.formSubmittedElsewhere')
            }
          }
          // AR-07：只结算**这一张**（活动表单一结局帧就收掉并按到达序补下一张；打给排队中的
          // 那张则出队清草稿）—— 这里不能用 `dropActiveForm`，它会把整队一起清掉。
          this.settleActiveForm(frame.toolCallId)
          // M2 / FE-22：只清**这张卡**的挂起语义 —— 同轮还有别的待人卡（确认门 / 另一张前端卡）
          // 时保持挂起，否则那一张的提交将失去重连判据（`reconnectSuspendedRun` 认 suspended）。
          this.clearSuspendedIfNoOtherConfirms(frame.toolCallId)
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
          // 契约增量：skipped 条目是一等终态而非挂起。无待决条目时直接落段（不置 suspended）；
          // 有待决条目时仍走下方原路（applyFrameToParts 会一并处理 skipped 条目，D8 守卫不变）。
          const hasSkipped = frame.toolCalls.some((call) => call.outcome === 'skipped' || call.status === 'SKIPPED')
          const waiting = frame.toolCalls.find((call) => (call.kind === 'CONFIRM' || call.kind === 'FRONTEND')
            && call.outcome !== 'skipped' && call.status !== 'SKIPPED')
          if (!waiting && hasSkipped) {
            applyFrameToParts(message, frame)
            break
          }
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
        case 'context': {
          // 上下文专项批①：压缩事件（帧类型 15 → 16 的唯一新增）。本批**不渲染**提示条
          // （设计稿 §6.4 把它排在批④），只记一段现场（排障/取证 + 供批次④ 直接消费）。
          // 刻意不刷新 pill：pill 的口径是"轮末占用"，压缩帧带来的是"压前/压后"的过程量。
          this.contextCompaction = {
            level: frame.level,
            compactedTurns: frame.turns,
            lostTokens: frame.lostTokens,
            lastAtMs: typeof frame.atMs === 'number' ? frame.atMs : null,
            reason: frame.reason,
            circuit: this.contextUsage?.circuit ?? 'closed',
            fuseFails: 0
          }
          // 批次3：压缩完成提示只在"本轮确实进过 compacting"时给 —— 重挂回放的旧 `context`
          // 帧不得在刷新后凭空冒出一条"压缩完成"。
          this.markCompactionDone()
          break
        }
        case 'heartbeat': {
          // 挂起期/流式期心跳只作保活（keep-alive 由 SSE 层消费）；去超时裁决后前端不再据此
          // 折算门卡倒计时。
          // 批次3：阶段心跳另带 `phase` —— 这是压缩期/准备期唯一的前端进度来源（帧间 45s
          // 由心跳自然续命，**不放宽**该上限）。只消费真实已用毫秒，不伪造百分比。
          if (frame.phase === 'compacting') {
            // 压缩心跳 = 已在出帧，"准备中"阶段结束（阶段条切到压缩）。
            this.clearPreparingRun()
            this.noteCompactionHeartbeat(frame)
          } else if (frame.phase === 'preparing') {
            const runId = typeof frame.runId === 'string' && frame.runId !== ''
              ? frame.runId
              : this.activeRunId
            if (runId) {
              this.notePreparingHeartbeat(runId, heartbeatElapsedMs(frame))
            }
          } else if (frame.phase === 'streaming') {
            // 压缩后恢复上游流：阶段条**立即**退场 —— 压缩完成帧（context）可能缺失，
            // 而"准备中"也是同一段窗口的残留。两条阶段态一起清，之后只保留"工作中"。
            this.clearPreparingRun()
            this.markCompactionDone()
          }
          break
        }
        case 'retry':
          // 2026-10-29 收口：空回复轮的重试（后端 additive 键 `emptyReply=true`）**不是**断流 ——
          // 后端明确说明这是"零产出轮只重跑模型步"。旧实现一律写"上游连接中断，正在重试
          // （本轮尚未产出内容）"，把一次正常重试说成了连接故障。这里按既有词条给事实
          //（"模型没有返回任何内容"），不新增 locale 键、不改文案规格。
          if (frame.emptyReply === true) {
            setNotice(this, 'ai.card.error.summary.emptyReply')
            break
          }
          setNotice(this, 'ai.notice.upstreamRetry')
          break
        case 'done': {
          // M-d（fix2）：本轮已收口 —— 决策 POST 在途时据此判定"不得再续收"
          roundTerminalSeq += 1
          // 批次3：轮末清掉阶段态（完成提示不跨轮残留；runId 不随阶段态外溢）
          this.resetCompactionProgress()
          this.clearPreparingRun()
          message.streaming = false
          message.done = true
          // o-R1：轮终态封口末尾流式段（否则已完成的消息里仍留着 streaming 段的假态，且会被
          // 镜像持久化）+ 批2 前置：仍在等动作的工具段一并过期降级（与空历史 expired 降级惯例一致）。
          // 旧口径的 `message.pendingCall = pendingCall?.status === 'pending' ? null : ...` 直写
          // 已删除 —— 挂起卡由段派生（requiresAction=false ⇒ 派生为 null）
          sealTrailingStreamPart(message)
          expireResidueToolParts(message)
          // FE-25：轮终态 ⇒ 所有"等人"类单例一并收口（表单 / 上传交接 / 表格预览 + 失效标记）。
          // 旧口径只收 activeForm：已收口的轮仍能被卡片推动（上传交接继续回灌、预览还能"应用"），
          // 而组件声明的 `unavailable`（任务已结束，仅供查看）没有任何生产者。
          this.cleanupTerminalInteractionState()
          this.suspended = false
          if (frame.cancelled) {
            // 批G · S1/S3：取消轮把仍 pending/running 的段一并收敛（后端不会再发 tool_result），
            // 并在消息尾打「已停止」标记（与「继续」入口互斥）。
            this.cancelRoundParts()
            const hasBody = message.content.trim() !== '' || message.parts.some((part) => part.kind !== 'text')
            if (hasBody) {
              message.stopped = true
            }
            this.lastRoundOutcome = 'cancelled'
          }
          // 截断专项：`done.extra.truncated` 只在 finish_reason=length 时出现（后端口径）。
          // 消息级标记 —— 面板据此显示「已截断」，刷新后由历史/镜像恢复；取消轮不写（cancelled 优先）。
          if (!frame.cancelled && frame.truncated === true) {
            message.truncated = true
          }
          // 批 E · E2：取消的轮次不算"正常完成"—— 建议条只在非取消的 done 后出现
          this.followupReady = !frame.cancelled
          // 上下文专项批①：轮末占用（唯一刷新点 —— 流式中不跳动，C-36）
          if (frame.context) {
            this.contextUsage = frame.context
            // 拆分是端点面（popover 打开时按需拉）：轮末先置脏，避免拿上一轮的七项配这一轮的总量
            this.contextBreakdown = null
          }
          // R-166：轮终态 ⇒ 提交在途态一并收口（挂起卡已由 expireResidueToolParts 收掉）
          this.clearGateSubmissions()
          break
        }
        case 'error': {
          // M-d（fix2）：与 done 同口径 —— 收口后不得再续收（不对终态重连）
          roundTerminalSeq += 1
          // 批次3：终态清掉阶段态；批次2 的 unconfirmed / 错误卡语义不受影响（本处不碰）
          this.resetCompactionProgress()
          this.clearPreparingRun()
          message.streaming = false
          message.done = true
          // 出错收尾与 done 同口径：封口末尾流式段 + 待决工具段过期降级
          sealTrailingStreamPart(message)
          expireResidueToolParts(message)
          // FE-25：与 done 同口径 —— 人围栏单例全部收口（轮已经结束，卡不能再推动它）
          this.cleanupTerminalInteractionState()
          // I-2（审查）：与 done 同口径解掉挂起态。`expireResidueToolParts` 已把待决段收口
          // （`pendingToolCall` 变 null）。只有真实待人卡才允许保持等待；自动工具没有卡，
          // 若 `suspended` 仍留真值就会伪造“等待确认”并锁住输入。
          this.suspended = false
          // AR-13：关键错误归错误卡（不再进通知条）。卡挂在**本轮这条助手消息**上 ⇒ 面板在
          // 它发生的位置内联渲染（绝不做顶部固定）。判据 = 码优先、文案兜底（utils/error-card.ts）。
          // 只有专属 Alert 码（buildErrorCard 返回 null）才回落到 error 级通知。
          // 批次2：机器码入消息（随镜像持久化）—— 刷新后仍能区分"哪种失败"。
          message.code = frame.code ?? null
          if (isUnconfirmedTerminal(frame)) {
            // 批次2 / AR-05：**中性「状态未确认」** —— 不是失败（不挂错误卡、不发错误通知），
            // 也不是完整答案。只落视图态，由面板给"重新载入 / 新开一轮"这类手动入口。
            message.unconfirmed = true
            message.errorCard = null
            this.lastRoundOutcome = 'unconfirmed'
            this.followupReady = false
            this.clearGateSubmissions()
            break
          }
          const card = buildErrorCard(frame.code, frame.message)
          message.errorCard = card
          if (card !== null) {
            clearNotice(this)
          } else {
            setNotice(this, 'ai.notice.turnError', { message: frame.message }, 'error')
          }
          this.lastRoundOutcome = 'error'
          // 批 E · E2：出错轮不出建议（用户下一步是"继续/重试"，不是换话题）
          this.followupReady = false
          // R-166：与 done 同口径收口提交在途态
          this.clearGateSubmissions()
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
        // O02：DONE / CANCELLED / FAILED / error 一到，忙提示立即作废 —— "回到进行中的回答"
        // 指的那一轮已经结束，入口指过去只会落到一条收尾的回答上。
        this.clearBusyRunOnTerminal(frame.runId)
        // M08-R2：终态 = 该 run 的驱动使命结束（所有权与预算一并释放）；
        // runId 与 activeRunId 都缺时保持不动（`forgetRunStreamDrive(undefined)` = 释放全部）
        const terminalRunId = frame.runId ?? this.activeRunId
        if (terminalRunId !== null) {
          this.forgetRunStreamDrive(terminalRunId)
        }
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
        onConflict: (summary, issues) => {
          this.attachPublishConflict(jobId, summary, issues)
          reportUiEventResult('start_publish', summary)
        }
      })
    },

    /**
     * 批 D · D3（问题 6）：AI 触发作业后把"任务号 + 作业号"同步给工作区页面。
     *
     * 仿 {@link armPublishConflictWatch} 体例：结果文本取 `作业 #N`、工具段 args 取 `taskId`，
     * 再调页面自己注册的能力（{@link START_JOB_SYNC}：`export.syncJob` / `import.syncJob`）。
     * 页面 handler 负责"绑定 + 进跟踪"（既有 `resumeJobs()`），AI 侧不 import 任何视图。
     *
     * 批次 5B 两处变化：
     * 1. **AR-08 事件驱动刷新**：只要作业号取到了就通知任务中心（`taskStore.notifyJobEvent`）——
     *    用户在任务中心时向导页并没有挂载（没有页面 handler），但列表同样需要这一次事实更新。
     *    三条件（在途 / 可见 / 观察窗）由 task store 判，这里只负责"事件发生了"。
     * 2. **失败回执可见**：handler 返回 `{ ok:false, reason }` 或直接抛错 ⇒ 落一条 warning 通知，
     *    不再 `.catch(() => undefined)` 静默吞掉（"页面没同步上"必须让用户看得见）。
     *
     * 作业号 / 任务号任一取不到即静默跳过：作业在后端照常跑，用户回到向导页时
     * `loadTask → resumeJobs` 仍会接上。
     */
    armStartJobSync(message: ChatMessage, frame: Extract<SseFrame, { type: 'tool_result' }>): void {
      const handlerName = START_JOB_SYNC[frame.name]
      if (!handlerName || !frame.ok || typeof frame.result !== 'string') {
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
      // AR-08：作业真的起来了（事件后）—— 任务中心那边按"在途 + 可见"决定是否拉一次列表。
      // 与页面能力是否注册无关：这一句是**工具结果驱动**的刷新入口，不是前端空转。
      // 无活动 pinia（单测直接调 store、未装 pinia）时跳过：那是测试装配，不是运行期现场。
      if (getActivePinia()) {
        useTaskStore().notifyJobEvent(frame.name)
      }
      const handler = getPageHandler(handlerName)
      if (!handler) {
        return
      }
      void Promise.resolve(handler({ taskId, jobId, toolCallId: frame.toolCallId }))
        .then((outcome) => {
          const failure = startJobSyncFailure(outcome)
          if (failure !== null) {
            this.noticeStartJobSyncFailure(jobId, failure.reason)
          }
        })
        .catch((error: unknown) => {
          this.noticeStartJobSyncFailure(
            jobId,
            error instanceof Error ? error.message : String(error)
          )
        })
    },

    /**
     * 批次 5B：作业同步失败的可见回执（`ai.notice.startJobSyncFailed*`）。
     *
     * 走通知槽而不是错误卡：作业本身在后端正常运行，失败的是"页面跟踪面没接上"这一步 ——
     * 这是提示级事实，不是轮次错误（错误卡会让人以为作业没起来）。
     */
    noticeStartJobSyncFailure(jobId: number, reason: string | null): void {
      if (reason === null) {
        setNotice(this, 'ai.notice.startJobSyncFailed', { jobId }, 'warning')
        return
      }
      setNotice(this, 'ai.notice.startJobSyncFailedReason', { jobId, reason }, 'warning')
    },

    /**
     * R-155 → S6 批 2（P1-4 承载）：把发布冲突明细落到该 jobId 关联的 start_publish 工具段。
     *
     * 写两份、拆两个消费者（设计稿 §5.3 的「依赖倒置」）：
     * - `reason` = 明细摘要**整串**（模型侧口径一字不动，「reason 是否只留一句」仍是独立的模型侧决策）；
     * - `resultView` = `kind=table` 视图（列 = 配置 / 行键 / 原因），卡面据此渲表，
     *   **不再反解 reason**。带 `issues` 时才建视图；旧调用面（只给 summary）行为逐字不变。
     *
     * 仿 A4' 收口范式：段唯一写口 upsertToolPart（createIfMissing=false 不新建段）+ 同步派生投影；
     * 落点 = 末条持该 toolCallId 的宿主消息。命中即注销注册项。
     * @param issues 结构化校验问题（视图的行来源；缺省 = 只走 reason 旧口径）
     * @returns 是否落段；查无关联 / 会话已切换 / 段不存在 ⇒ false（调用方静默走原通道）
     */
    attachPublishConflict(jobId: number, summary: string, issues?: ValidationIssue[]): boolean {
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
            reason: summary,
            ...(issues && issues.length > 0
              ? {
                  resultView: buildPublishConflictView({
                    id: `rv-${link.toolCallId}`,
                    issues,
                    total: issues.length
                  })
                }
              : {})
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
     *
     * @param guard 入队时捕获的轮次现场（B2）。省略 = 入口即现场（直接调用方）。
     */
    async runFrontendTool(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>,
      guard?: RoundGuard
    ): Promise<void> {
      // 批3③b D10（R-142）/ B1 收口修（C-1）：轮次现场在**入队时**捕获下来，回到现场再比一次
      // —— 丢弃判据 = "本轮已被真正换掉"（新消息 / 重挂 / 换会话 / 停止），而不是
      // "AbortController 引用变了"。挂起收流会在 `send()` 的 finally 里把 `currentAbort` 置 null，
      // 决策后的同轮重连还会换新 controller，但那一轮**尚未结束** —— 慢前端工具的结局必须照常
      // 写段终态并回灌（否则段恒 `running`，后端只能等满 120s 前端工具上限出 `FRONTEND_TIMEOUT`）。
      const round = guard ?? captureRound(this.sessionId, frame.runId ?? this.activeRunId ?? null)
      // B2：排队期间已停止 / 换会话 / 换轮 ⇒ 本动作**零副作用**：不推进 UI 轮次 token
      //（那会把 `abortUiOperations()` 落下的停止位重新放开）、不写段、不动浏览器、不回灌。
      if (!stillSameRound(round, this.sessionId, this.activeRunId ?? null)) {
        return
      }
      // AI 可视可操作专项：单轮配额与"停止后零执行"按轮次 token 归零（同 runId 不重置）
      beginUiTurn(frame.runId ?? this.activeRunId ?? 'local')
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
      const undoBefore = peekUiUndo()
      const execution = await executeFrontendTool({
        name: frame.name,
        toolCallId: frame.toolCallId,
        runId: frame.runId ?? this.activeRunId ?? undefined,
        args: parseToolArgs(frame.args),
        budgetMs: deriveFrontendToolBudgetMs(frame.timeoutSeconds)
      })
      // B1 收口修（C-1）：await 返回后比对**轮次现场** —— 只有"真正换轮"（`send()` 起新轮 /
      // `reattachActive()` 重挂 / `adoptFreshSession()` 换会话 / `stop()` 取消本轮）才丢弃晚到
      // 结局：段终态写与回灌 POST（`submitFrontendToolResult`）整体不落，防晚到结局覆盖已收尾
      // 现场 / 向已取消轮或新轮回灌。挂起收流（`StreamCloseInfo.suspended`）与决策后的同轮重连
      // **不**递增纪元 ⇒ 工具回灌照常（C-1 的原始缺陷即栽在"controller 引用变了"这条判据上）。
      // 本轮正常结束（done）后晚到的执行器同属本轮：段落真实结局、回灌交给 409 幂等收敛，
      // 不把卡留在 running 假态（后端此时已超时/取消，重复回灌只会被幂等拒绝）。
      if (!stillSameRound(round, this.sessionId, this.activeRunId ?? null)) {
        return
      }
      // 批3③a A8 → 批 A：8 态落卡（unknown 只在下载类白名单出现；rejected/blocked/
      // cancelled/skipped 各有落点，不再一律折成 failed）。
      const status: ToolRunStatus = toolStatusOfOutcome(execution.outcome)
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
        setRawNotice(this, execution.result)
      }
      // 治理 P0-1：前端执行器的结局八态随回灌上送（后端台账/回帧以此为权威结局）
      await this.submitFrontendToolResult(
        frame.toolCallId,
        execution.result,
        'frontend-executor',
        false,
        undefined,
        execution.outcome,
        execution.sheetMeta,
        execution.code
      )
      // AI 可视可操作专项：把原语运行时的一次性状态（撤销点 / 播报）同步给面板
      this.syncUiRuntimeState()
      this.uiUndoToolCallId = this.uiUndoEntry !== null && this.uiUndoEntry !== undoBefore
        ? frame.toolCallId
        : null
    }
  }
})
