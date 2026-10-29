/**
 * 消息段（`parts`）纯函数层 —— 对话时序包 批2「权威翻转」的唯一写口。
 *
 * 翻转后口径（施工卡-刷新版 §4.2/§6.1）：
 * - `ChatMessage.parts` 是**唯一真值**（带顺序信息的判别联合：text / reasoning / tool）；
 * - `content` / `reasoning` / `toolRuns` / `pendingCall` 四个旧字段是**派生投影**，
 *   只允许由 {@link deriveProjections} 写入（delta / reasoning 高频路径按 §6.1 性能注
 *   在 store 侧与段文本**锁步** `+=`，不触发全量重算）；
 * - 帧 → 段的写入只经 {@link applyFrameToParts}，帧级守卫（轮次归属 / seq 幂等）留在
 *   `stores/ai.ts` 的 `handleFrame`。
 *
 * 本文件**不依赖 store**（纯函数：入参 message 就地改写、返回 void），便于仓内 vitest 直测。
 */
import {
  GENERATIVE_FORM_TOOL,
  type ChatMessage,
  type MessagePart,
  type PendingToolCall,
  type ReasoningPart,
  type TextPart,
  type ToolPart,
  type ToolPartPatch,
  type ToolRun,
  type ToolRunStatus
} from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { parseToolArgs } from '@/utils/frontend-tools'
import { translate } from '@/composables/useCopy'

/** 段序列的下一个 seg（单调递增：封口后另起的段不复用旧编号）。 */
export function nextSeg(parts: MessagePart[]): number {
  return parts.reduce((max, part) => Math.max(max, part.seg), -1) + 1
}

/**
 * 取"当前可续写的同类段"，没有则另起一段（seg 递增，streaming=true）。
 *
 * 口径：只有**末尾**且**同类型且未封口**的段才续写 —— 中间夹了工具段/思考段即视为新段
 * （这正是 parts 相对 content 单字段要多记的顺序信息）。
 *
 * o-R1（红队 批1）：另起新段前，把被中断的**末尾流式段**（交错的另一类：text ↔ reasoning）
 * 先封口 —— 与 {@link sealTrailingStreamPart} 对称。翻转前 `streaming` 标志无人消费（渲染读
 * 旧投影）故漏封不可见；本批起思考链由 parts 渲染、段级状态参与后续状态机（子包③），
 * 不封口会出现"两段同时 streaming"的假态。
 *
 * 批次6A：思考段的接收锚（`startedAt` / `lastFrameAt` / `lastFrameLocalAt`）**只在这里**落 ——
 * 新起段与续写已在流的段都走 {@link noteReasoningFrame}，调用方不必再各自补一次。`atMs` 对
 * **正文段**只有"封口被中断的上一段"这一个用途，绝不写进正文段（文本段无计时语义）。
 */
export function openStreamPart(message: ChatMessage, kind: 'text' | 'reasoning', atMs?: number): TextPart | ReasoningPart {
  const last = message.parts[message.parts.length - 1]
  if (last && last.kind === kind && last.streaming) {
    if (last.kind === 'reasoning') {
      noteReasoningFrame(last, atMs)
    }
    return last
  }
  if (last && (last.kind === 'text' || last.kind === 'reasoning') && last.streaming) {
    sealStreamPart(last, atMs)
  }
  const seg = nextSeg(message.parts)
  if (kind === 'reasoning') {
    // 批次6A：起点取**本帧**的服务端 atMs（`message_start{kind:'reasoning'}` 优先；旧帧缺省
    // 则由首个思考 delta 补上）。没有 atMs 就不造起点 —— 面板据此不显示耗时。
    const part: ReasoningPart = { kind: 'reasoning', seg, text: '', streaming: true }
    message.parts.push(part)
    noteReasoningFrame(part, atMs)
    return part
  }
  const part: TextPart = { kind: 'text', seg, text: '', streaming: true }
  message.parts.push(part)
  return part
}

/**
 * 段封口的**唯一**收尾：置 `streaming=false`，并给思考段补 `endedAt`。
 *
 * 批次6A / AR-12：`endedAt` 只认服务端 `atMs`（封口帧自己的时刻），脚本内没有帧时刻时退回
 * 该段最后一次帧的 `lastFrameAt`（仍是服务端钟）—— 两条都不成立就留空，绝不按本地 now 编一个。
 */
function sealStreamPart(part: TextPart | ReasoningPart, atMs?: number): void {
  if (!part.streaming) {
    return
  }
  part.streaming = false
  if (part.kind === 'reasoning' && part.endedAt === undefined) {
    const endedAt = atMs ?? part.lastFrameAt
    if (endedAt !== undefined) {
      part.endedAt = endedAt
    }
  }
}

/**
 * 思考帧的**接收锚**（批次6A 返修）：服务端 `atMs` + 本地收帧时刻成对落段。
 *
 * 计时不用 `Date.now() - 服务端 atMs`（两端钟差会整段偏移），而是
 * `(lastFrameAt - startedAt) + (now - lastFrameLocalAt)` —— 钟差被这一对锚吸收。刷新读回时
 * 本地锚由镜像 `savedAt` 重播（镜像不持久化本字段），离线/关闭页签的那段也计入真实已用时长。
 * 旧帧没有 `atMs` 时什么都不写（保持"无起点不显示"）。
 */
function noteReasoningFrame(part: ReasoningPart, atMs?: number, receivedAtMs?: number): void {
  if (atMs === undefined) {
    return
  }
  if (part.startedAt === undefined) {
    part.startedAt = atMs
  }
  // 单调：重连全量回放会从更早的帧重放，不能把已观测到的 lastFrameAt 往回拨（否则秒表倒退）。
  if (part.lastFrameAt === undefined || atMs >= part.lastFrameAt) {
    part.lastFrameAt = atMs
    part.lastFrameLocalAt = receivedAtMs ?? Date.now()
  }
}

/** 消息内最后一个思考段（`message_end{kind:'reasoning'}` 落 `endedAt` 的定位口径）。 */
function lastReasoningPart(message: ChatMessage): ReasoningPart | null {
  for (let index = message.parts.length - 1; index >= 0; index -= 1) {
    const part = message.parts[index]
    if (part && part.kind === 'reasoning') {
      return part
    }
  }
  return null
}

/**
 * 思考段已用时长（毫秒；`null` = 无起点 ⇒ 面板不显示计时）。
 *
 * 批次6A / AR-12（返修）：实时 / 续跑 / 重连 / **刷新在途**都走本函数。换算只用**帧接收锚**：
 * 收帧那一刻同时记下服务端 `lastFrameAt` 与本地 `lastFrameLocalAt`，时长 =
 * `(lastFrameAt - startedAt) + (now - lastFrameLocalAt)` —— 等价于 `now - (startedAt + 钟差)`，
 * 刷新/离线的那段也计入，且**不做** `Date.now() - 服务端 atMs` 的裸减法（两端钟差被接收锚吸收）。
 *
 * - `endedAt` 后静止：两端都是服务端钟，直接相减（不受本地钟影响）；
 * - 没有接收锚的旧段（本字段落地前的镜像）：只报服务端已确认的 `lastFrameAt - startedAt`，
 *   不拿本地 now 猜（宁可偏低，不虚报）；
 * - 历史已完成轮没有这些键 ⇒ 返回 null，**不按当前时刻回算**、不给旧数据补造耗时。
 */
export function reasoningElapsedMs(part: ReasoningPart, nowMs: number): number | null {
  const startedAt = part.startedAt
  if (typeof startedAt !== 'number' || !Number.isFinite(startedAt)) {
    return null
  }
  if (typeof part.endedAt === 'number' && Number.isFinite(part.endedAt)) {
    return Math.max(0, part.endedAt - startedAt)
  }
  const confirmed = typeof part.lastFrameAt === 'number' && Number.isFinite(part.lastFrameAt)
    ? Math.max(0, part.lastFrameAt - startedAt)
    : 0
  if (part.streaming) {
    if (typeof part.lastFrameLocalAt === 'number' && Number.isFinite(part.lastFrameLocalAt)) {
      return confirmed + Math.max(0, nowMs - part.lastFrameLocalAt)
    }
    return confirmed
  }
  if (typeof part.lastFrameAt === 'number' && Number.isFinite(part.lastFrameAt)) {
    return confirmed
  }
  return null
}

/**
 * 封口末尾的流式段（工具段插入前 / message_end / done / error 收尾调用）。
 *
 * o-R1：本函数在批2 起公开化 —— 轮终态（done / error）与段边界（message_end）都必须封口，
 * 否则"已完成的消息里仍有 streaming 段"（刷新后由镜像重建会把这个假态一并持久化）。
 */
export function sealTrailingStreamPart(message: ChatMessage): void {
  const last = message.parts[message.parts.length - 1]
  if (last && (last.kind === 'text' || last.kind === 'reasoning') && last.streaming) {
    sealStreamPart(last)
  }
}

/**
 * 批次6A（N8/N9）：工具段**诊断分层**的分类（`null` = 卡面照常展示，不分层）。
 *
 * 背景（review-04 N8/N9）：卡面曾经把**给模型/排障看**的原文直接当给人看的话 —— 内部码
 * （`TOOL_BUDGET_EXHAUSTED` / `RESOURCE_LOCKED` / `KIND_MISMATCH`）、`app.ai.*` 配置键、
 * 回灌端点路径、陈旧确认卡的 `runId/toolCallId`。批次6B 已让 store 把陈旧卡落只读终态、
 * 批次6C 已让后端把 `result` 换成用户摘要；本分类是**前端展示层的安全网**（旧镜像 / 旧帧 /
 * 旧归档没有这些修正）：
 * - `staleGate`：无决策的 CONFIRM 段已 expired/cancelled，且文本里还残留机器标识（N8）；
 * - `skipped`：skipped 段的文本仍是模型向/机器向（N9：预算、步数上限、配置键）；
 * - `internal`：blocked（**未执行**）段里残留内部码/路径（N9 的错入口、资源锁同族）。
 *
 * 成功 / 进行中 / 执行失败（`failed`）的段不分类：L1「结果已清除（… toolCallId=…）」的占位符
 * 是**真执行过**的证据；`failed` 的原文常是已可读的业务解释（如 `SHEET_DISABLED` 的管理员
 * 说明），按它是否含配置键把人话整段藏起来反而是倒退 —— 码在 6C 已换成用户摘要，这一层只
 * 兜"未执行"的旧数据。
 */
export type PartDiagnostic = 'skipped' | 'staleGate' | 'internal'

/** 机器向文本的判据（只收**码/配置键/端点**这类稳定记号；不按中文措辞猜语义）。 */
const INTERNAL_MARKER_RE = /(?:^|[^A-Za-z0-9_])(?:TOOL_BUDGET_EXHAUSTED|TOOL_STEP_LIMIT|TOOL_KIND_MISMATCH|RESOURCE_LOCKED|SCOPE_NOT_DISCLOSED|UPSTREAM_FAILED|TOOL_ALREADY_CLAIMED|CREATE_TASK_TURN_LIMIT|UNKNOWN_TOOL_CALL|UNKNOWN_RUN)(?:[^A-Za-z0-9_]|$)|app\.ai\.[a-z]|\/api\/ai\/frontend-tool-result|toolCallId=|runId\/toolCallId/

function internalMarkerPresent(part: ToolPart): boolean {
  return INTERNAL_MARKER_RE.test(`${part.reason ?? ''}\n${part.result ?? ''}`)
}

export function partDiagnosticOf(part: MessagePart): PartDiagnostic | null {
  if (part.kind !== 'tool' || !internalMarkerPresent(part)) {
    return null
  }
  const staleGate = part.kind2 === 'CONFIRM'
    && part.decision === undefined
    && (part.status === 'expired' || part.status === 'cancelled')
  if (staleGate) {
    return 'staleGate'
  }
  if (part.status === 'skipped') {
    return 'skipped'
  }
  return part.status === 'blocked' ? 'internal' : null
}

/** 该段的 `result` 自身是否就是机器向文本（`true` ⇒ 卡面「输出」位让位给诊断详情，不同屏双显）。 */
export function partResultIsInternal(part: MessagePart): boolean {
  return part.kind === 'tool' && INTERNAL_MARKER_RE.test(part.result ?? '')
}

/**
 * 收进「诊断详情」的原文：只收**自身带机器记号**的字段（reason / result 去重后拼接）。
 * 人话字段不高亮进诊断层（否则同一句人话会在「输出」与「诊断详情」各出现一次）。
 * 无分层或无可收原文 ⇒ null。
 */
export function partDiagnosticText(part: MessagePart): string | null {
  if (part.kind !== 'tool' || partDiagnosticOf(part) === null) {
    return null
  }
  const values = [part.reason, part.result]
    .filter((value): value is string => typeof value === 'string'
      && value.trim() !== ''
      && INTERNAL_MARKER_RE.test(value))
  const unique = Array.from(new Set(values))
  return unique.length > 0 ? unique.join('\n\n') : null
}

/** 定位工具段（不建段）。 */
export function findToolPart(message: ChatMessage, toolCallId: string): ToolPart | null {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  return part && part.kind === 'tool' ? part : null
}

/**
 * 工具帧 → `parts` 的**唯一写点**：与旧的 `upsertToolRun` 同语义
 * （按 toolCallId 就地 spread 合并，不产生重复段）。
 *
 * 新建时先**封口末尾的流式段**（工具调用之后到达的正文属于新的一段，seg 单调递增）；
 * `createIfMissing=false` = 只更新语义（段已存在才写；③b D5 起 suspended 帧不再用此形态），
 * status 省略时保留段上原值，理由见 {@link ToolPartPatch}。
 */
export function upsertToolPart(message: ChatMessage, patch: ToolPartPatch, createIfMissing = true): void {
  const index = message.parts.findIndex((part) => part.kind === 'tool' && part.toolCallId === patch.toolCallId)
  const existing = index >= 0 ? message.parts[index] : undefined
  if (existing && existing.kind === 'tool') {
    message.parts.splice(index, 1, { ...existing, ...patch })
    return
  }
  const status = patch.status
  if (!createIfMissing || status === undefined) {
    return
  }
  sealTrailingStreamPart(message)
  message.parts.push({ kind: 'tool', seg: nextSeg(message.parts), ...patch, status })
}

/** 段 status（canonical）→ pendingCall.status（展示态）的口径映射。 */
function pendingStatusOf(status: ToolRunStatus): PendingToolCall['status'] {
  switch (status) {
    case 'running':
      return 'running'
    case 'succeeded':
      return 'succeeded'
    case 'rejected':
      return 'rejected'
    case 'blocked':
      return 'blocked'
    case 'expired':
      return 'expired'
    case 'cancelled':
      return 'cancelled'
    default:
      // pending / failed / unknown：仍在等动作的段只可能是 pending；后两者无挂起语义。
      // 批3③a X1：unknown 段连"等动作"语义都不允许携带（requiresAction 必须 false），
      // deriveProjections 在调用本函数之前已按不变式拦截违例段 —— 本 default 折 unknown
      // 仅为类型穷尽兜底，正常数据流不可达。
      return 'pending'
  }
}

/**
 * 批3③a X1 dev 断言：`unknown` 段与 `requiresAction === false` 互锁。
 * 违例（unknown + requiresAction=true，只能来自镜像迁移或未来写点的缺陷）在此留痕，
 * 但**不阻断**投影 —— 挂起卡被 deriveProjections 跳过，工具卡仍如实显示 unknown。
 *
 * 批3③b D12（R-143③）：补 `import.meta.env.DEV` 门 —— 本断言是 dev 诊断口径，生产不留痕。
 * vitest 环境 DEV=true ⇒ ③a 既有测试锁行为不变（守卫 + 测试锁双保护不因此削弱）。
 */
function assertUnknownTerminalInvariant(part: ToolPart): void {
  if (!import.meta.env.DEV) {
    return
  }
  console.error(
    `[批3③a X1] unknown 工具段不允许 requiresAction=true（toolCallId=${part.toolCallId}），`
    + '挂起投影已忽略该段'
  )
}

/**
 * 工具段 → 挂起投影（复刻原 `toPendingCall` 的形状：args 已解析、rawArgs 原样、
 * 倒计时元数据随段持久化）。`kind` 由段 kind2 派生；非 FRONTEND 一律按 CONFIRM 卡展示
 * （段 kind2 是后端三值原文，而落段白名单早已收窄到 CONFIRM/FRONTEND —— 刷新恢复只为
 * `kind ∈ {CONFIRM, FRONTEND}` 且 `req !== false`（缺键 = 孤儿轮可建）的条目建段，
 * `suspended` 帧路径同样按 `kind === 'CONFIRM' || kind === 'FRONTEND'` 择条，
 * 故 BACKEND 条目不会走到这里）。
 */
function toPendingCallProjection(part: ToolPart): PendingToolCall {
  return {
    toolCallId: part.toolCallId,
    name: part.name,
    kind: part.kind2 === 'FRONTEND' ? 'FRONTEND' : 'CONFIRM',
    args: parseToolArgs(part.args),
    rawArgs: part.args,
    timeoutSeconds: part.timeoutSeconds,
    expiresAt: part.expiresAt,
    atMs: part.atMs,
    status: pendingStatusOf(part.status)
  }
}

/**
 * **唯一派生写口**：由 `parts` 重算四个旧投影字段（形状与翻转前逐字一致，存量读点零改动）。
 *
 * - `content` = 全部 text 段 join（现状是"整轮文本按到达顺序拼接"，段只切显示不丢字符 ⇒ 等价）；
 * - `reasoning` = 全部 reasoning 段 join；
 * - `toolRuns` = 全部工具段逐字段映射（`kind` ← 段 `kind2`）；
 * - `pendingCall` = 该消息**最后一个** `requiresAction === true` 的工具段（等人工/前端动作的
 *   那一个）按上面口径映射；没有则 `null`。
 *
 * 调用时机（§6.1 性能注）：工具类帧 / message_start / message_end / done / error 之后全量重算；
 * delta / reasoning 高频路径**不调**本函数（store 侧增量维护 content/reasoning）。
 */
export function deriveProjections(message: ChatMessage): void {
  const texts: string[] = []
  const reasonings: string[] = []
  const runs: ToolRun[] = []
  let pending: PendingToolCall | null = null
  for (const part of message.parts) {
    if (part.kind === 'text') {
      texts.push(part.text)
      continue
    }
    if (part.kind === 'reasoning') {
      reasonings.push(part.text)
      continue
    }
    runs.push({
      toolCallId: part.toolCallId,
      name: part.name,
      kind: part.kind2,
      status: part.status,
      args: part.args,
      result: part.result,
      reason: part.reason,
      messageId: part.messageId
    })
    if (part.requiresAction) {
      // 批3③a X1 不变式：unknown 段永不复活为挂起卡（unknown ⇒ requiresAction === false）。
      // 镜像/未来写点若产出 unknown+requiresAction:true 的违例段，这里显式跳过挂起投影并
      // 触发 dev 断言（不阻断投影 —— 工具卡仍如实显示 unknown）。
      if (part.status === 'unknown') {
        assertUnknownTerminalInvariant(part)
      } else {
        pending = toPendingCallProjection(part)
      }
    }
  }
  message.content = texts.join('')
  message.reasoning = reasonings.join('')
  message.toolRuns = runs
  message.pendingCall = pending
}

/**
 * `frontend_tool_result` 帧的 `status` → 段终态。
 *
 * 后端 `SseChatEmitter#frontendToolResult` 把 `pending.getStatus()` 原样入帧，故取值域 =
 * `PendingToolCall` 的状态常量。实测**该帧型**的发口与实发值（只读对账见
 * evidence-b2fix/backend-status-enum.txt）：
 * - `FRONTEND_RESULT`（`ConfirmGate#submitFrontendResult`，ok=true，成功）；
 * - `FRONTEND_CANCELLED`（`ConfirmGate#dismiss`，用户放弃表单）/ `CANCELLED`
 *   （`ConfirmGate#cancel`，整轮取消）；
 * - `TIMEOUT`（`ConfirmGate#expire`，前端回执超时）；
 * - `REJECTED_ARGUMENTS`（`SpToolCallingManager#rejectFrontendArguments`，入参被闸门拒）。
 *
 * `SUCCEEDED` / `REJECTED` / `BLOCKED` 该帧型实测不发（分别走 FRONTEND_RESULT、
 * confirm_decision/tool_result、tool_result），此处一并同形映射：既与 store 侧
 * `frameStatusToPendingStatus`（stores/ai.ts）的穷举口径对齐，也覆盖 `ConfirmGate#cancel`
 * "发帧不受 PENDING 守卫"的竞态（条目已被他端落定时会带出已决状态）。
 *
 * 返回 null = 帧未给可翻译终态（status 缺失/未知），由调用方按 ok 兜底。
 *
 * 导出仅为批3③b D9 防漂移一致性锁（表驱动单测直译两表，见 frontend-tool-result-terminal.spec.ts）；
 * 生产调用点仍只有 applyFrameToParts 一处。
 *
 * 批3③b D9（R-141④）防漂移注记（**四对**，不得擅自"对齐"；第③对为 R-144 增表后补）：
 * ① 本表 vs `frameStatusToPendingStatus`（stores/ai.ts）——**唯一真实分叉 = `SUCCEEDED`**
 *   （本表收录：覆盖 ConfirmGate#cancel 竞态带出的已决值；响应体表不翻译 ⇒ null。实发交集
 *   FRONTEND_RESULT/FRONTEND_CANCELLED/CANCELLED/TIMEOUT/REJECTED_ARGUMENTS/REJECTED/BLOCKED
 *   两表同映射）；台账原"后者缺 CANCELLED 依据注释"系误述（ai.ts:347-349 注释实存）。
 * ② 段侧终态枚举 `localTerminal`（本文件 `frontend_tool_result` case 内）vs store 侧 `isFrameDecidedPendingStatus`
 *   （ai.ts）——同 8 态（succeeded/rejected/blocked/expired/cancelled/unknown/failed/skipped）、
 *   **无共享常量**（parts.ts 不依赖 store 的纯函数层约束），漂移由 D9 表驱动一致性锁兜底。
 * ③ 本表 + `historyToolCallStatus`（下方，R-144 history 载荷面单表）——分叉逐条见
 *   `historyToolCallStatus` 头注（EXECUTED / APPROVED·PENDING→pending / DONE / 不收录 SUCCEEDED 四条），
 *   有意分叉、禁对齐；两表实发交集（FRONTEND_RESULT/FRONTEND_CANCELLED/CANCELLED/TIMEOUT/
 *   REJECTED_ARGUMENTS/REJECTED/BLOCKED）同映射，漂移由 D9 表驱动一致性锁兜底。
 */
export function frontendResultStatus(status: unknown): ToolRunStatus | null {
  switch (status) {
    case 'FRONTEND_RESULT':
    case 'SUCCEEDED':
      return 'succeeded'
    case 'FRONTEND_CANCELLED':
    case 'CANCELLED':
      return 'cancelled'
    case 'TIMEOUT':
      return 'expired'
    case 'REJECTED_ARGUMENTS':
    case 'REJECTED':
      return 'rejected'
    case 'BLOCKED':
      return 'blocked'
    default:
      return null
  }
}

/**
 * 槽位放行的拒绝码（与后端 {@code ErrorCodes.FORM_SLOTS_ALREADY_KNOWN} 同值）。
 *
 * <p>它是"表单想收集的东西已经知道"而不是"出错了"：零副作用、有明确纠正出口。
 */
export const FORM_SLOTS_ALREADY_KNOWN = 'FORM_SLOTS_ALREADY_KNOWN'

/**
 * 该 toolCall 是否是<b>槽位放行的幻影表单卡</b>（历史面唯一判据）。
 *
 * <p>为什么需要：后端对这一个码静默了用户可见的工具帧（{@code tool_start} /
 * {@code frontend_tool_result} 都不发），但条目、日志与模型回执照旧保留 —— 于是历史载荷里仍有
 * 这条调用。前端若照常建段，就会出现"实时流里没有、刷新后多出一张"的幻影卡。
 *
 * <p>判据 = 工具名 + 错误码，两者缺一不可：其它拒绝（如 {@code FORM_SCHEMA_REJECTED}）必须
 * 让用户看到"这张表为什么没成"；别的工具即便带同名码也不隐藏（不借码绕过可见性）。
 */
export function isSlotReleasePhantom(name: unknown, code?: string | null): boolean {
  return name === GENERATIVE_FORM_TOOL && code === FORM_SLOTS_ALREADY_KNOWN
}

/**
 * R-144（history 载荷状态码 → 段状态，单表单一事实源）：唯一消费方 = `loadHistory` 历史整替。
 * 载荷码 = 后端 `PendingToolCall` 10 码（T2 升格判据落地后 APPROVED∧已执行 已归 EXECUTED）+
 * 快照态兜底（CANCELLED/DONE；FAILED 不参与段态推导 ⇒ null，与 A-1-4 判据序同源）。
 * 与 `frontendResultStatus` 的有意分叉（D9 注记第③对逐条点名，禁擅自"对齐"）：
 * ① `EXECUTED → succeeded`（本表独有：T2 升格判据的目标码；帧/响应面无此码）；
 * ② `APPROVED` / `PENDING → pending`（定稿修正：兜底取 pending **不取 expired**；
 *    `frontendResultStatus` 对这两码返回 null —— 响应面它们瞬态不存在，历史面是合法兜底态）；
 * ③ `DONE → succeeded`（快照态兜底落点；帧/响应面无此码）；
 * ④ 本表**不收录** `SUCCEEDED`（后端 10 码无此值，旧载荷兜底不走本表）。
 * `FRONTEND_RESULT → succeeded` 为**定稿修正**（已回灌结果即成功终码，非瞬态）——
 * 与 `frontendResultStatus` 同映射，非分叉。
 * 契约增量：`SKIPPED → skipped`（上游失败导致未执行；历史载荷通道的 status 实发值）。
 * 契约增量：`PRECHECK_FAILED → failed`（F5 预检未通过就地结算的终态；后端该条目
 * outcome=failed，但旧载荷/缺 outcome 时若无本映射会落 `null` ⇒ 历史构造占位 `succeeded`
 * 误显示成功 —— 本条把缺 outcome 的回落也钉在 failed）。
 */
export function historyToolCallStatus(status: unknown): ToolRunStatus | null {
  switch (status) {
    case 'EXECUTED':
    case 'FRONTEND_RESULT':
    case 'DONE':
      return 'succeeded'
    case 'REJECTED':
    case 'REJECTED_ARGUMENTS':
      return 'rejected'
    case 'TIMEOUT':
      return 'expired'
    case 'CANCELLED':
    case 'FRONTEND_CANCELLED':
      return 'cancelled'
    case 'BLOCKED':
      return 'blocked'
    case 'SKIPPED':
      return 'skipped'
    case 'PRECHECK_FAILED':
    case 'SHEET_DISABLED':
      // 表格开关关掉的终态（治理批）：缺这条映射会让刷新后的段回落成"成功"占位
      return 'failed'
    case 'APPROVED':
    case 'PENDING':
      return 'pending'
    default:
      return null
  }
}

/**
 * 治理 P0-1（统一结果信封）单表：`outcome` 八态 → 段状态。唯一消费方 =
 * `applyFrameToParts` 的 `tool_result` / `frontend_tool_result`（I1 收口）与 `loadHistory`
 * 的历史段构造（有 outcome 则用、无则旧表）。
 *
 * 口径：
 * - `ok → succeeded`；`failed → failed`；`rejected → rejected`；`blocked → blocked`；
 * - `timeout → expired`（沿用既有超时口径，与 `TIMEOUT`/`FRONTEND_CANCELLED` 词表同族）；
 * - `cancelled → cancelled`（沿用既有取消口径）；
 * - `unknown → failed`（旧卡面只有"执行失败"可承载"未确认成功"；unknown 段态二期再议）；
 * - `skipped → skipped`（上游确定性失败导致本工具未执行；一等状态，不得折成 failed）；
 * - 缺失 / 域外值 ⇒ null，由调用方回落到既有 `ok` 三元或 status 旧表（旧帧一字不动）。
 *
 * 返回 null 不等于失败 —— 只表示"本字段未给出可翻译结局"。
 */
export function outcomeStatus(outcome: unknown): ToolRunStatus | null {
  switch (outcome) {
    case 'ok':
      return 'succeeded'
    case 'failed':
    case 'unknown':
      return 'failed'
    case 'rejected':
      return 'rejected'
    case 'blocked':
      return 'blocked'
    case 'timeout':
      return 'expired'
    case 'cancelled':
      return 'cancelled'
    case 'skipped':
      return 'skipped'
    default:
      return null
  }
}

/**
 * `confirm_decision` 帧的门结局（2A 挂账①）：**帧 status/decision 优先，`approved` 布尔兜底**。
 *
 * 后端实发（`SseChatEmitter#confirmDecision`）：`decision` ∈ {approve, reject, timeout, cancel}
 * （ConfirmGate 三处发口），`status` ∈ {APPROVED, REJECTED, TIMEOUT, CANCELLED}，`approved` 只对
 * APPROVED 为 true。旧实现只看 `approved` ⇒ 超时帧被写成 rejected，实时卡面谎报「✗ 已拒绝」，
 * 刷新后历史表（TIMEOUT→expired）才显示「! 已超时」。
 *
 * 返回 null = 帧未给可翻译结局/布尔（旧帧两字段皆缺），调用方退回既有 approved 分支。
 * `cancelled` 批G · S13 起落 **decision='cancel'**：用户停止是一次决策（门卡第五态「已取消」），
 * 不写决策元数据就会被 `readOnlyStateOf` 的 status 兜底读成「已超时」。
 */
type ConfirmOutcome =
  | { state: 'approved' }
  | { state: 'rejected' }
  | { state: 'timeout' }
  | { state: 'cancelled' }

function confirmDecisionOutcome(frame: { decision?: unknown; status?: unknown }): ConfirmOutcome | null {
  const status = typeof frame.status === 'string' ? frame.status.toUpperCase() : ''
  const decision = typeof frame.decision === 'string' ? frame.decision.toLowerCase() : ''
  // 2A 收口修 MINOR-1：**具体终态 status 先于 decision 词表**。后端 cancel 对非 PENDING 条目
  // 仍发 decision='cancel' + 真实 status（APPROVED/EXECUTED/REJECTED/…）的帧 —— 若让 decision
  // 先行，会把「已批准（后执行失败）」「已拒绝」等真实结局误读成「已取消 → 卡面已超时」。
  if (status === 'APPROVED' || status === 'EXECUTED') {
    return { state: 'approved' }
  }
  if (status === 'REJECTED' || status === 'REJECTED_ARGUMENTS') {
    return { state: 'rejected' }
  }
  if (status === 'TIMEOUT') {
    return { state: 'timeout' }
  }
  if (status === 'CANCELLED') {
    return { state: 'cancelled' }
  }
  // status 不具终态（PENDING/缺失）时才走 decision 词表（正常确认帧 / 旧帧兜底口径）。
  if (decision === 'timeout') {
    return { state: 'timeout' }
  }
  if (decision === 'cancel' || decision === 'cancelled') {
    return { state: 'cancelled' }
  }
  if (decision === 'reject' || decision === 'rejected') {
    return { state: 'rejected' }
  }
  if (decision === 'approve' || decision === 'approved') {
    return { state: 'approved' }
  }
  return null
}

/**
 * 2A②：GF 结局帧的 `result` 就是用户提交值原文（ConfirmGate#submitFrontendResult 落 resultText
 * → SseChatEmitter#frontendToolResult 原样入帧）。只收合法 JSON **对象**；坏 JSON / 数组 /
 * null / 标量一律不伪造（回执卡从 status 派生，值缺失保持空）。
 */
export function submittedValuesFromResult(name: string, result: string | null | undefined): Record<string, unknown> | null {
  if (name !== GENERATIVE_FORM_TOOL || typeof result !== 'string' || result === '') {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(result)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return null
  }
  return parsed as Record<string, unknown>
}

export interface SubmittedValuesWriteOptions {
  /**
   * `false`（帧路径）⇒ first-wins：已有 `submittedValues` 的段不覆盖；
   * `true`（POST 成功路径）⇒ 本次提交值为准。
   */
  overwrite: boolean
  /** 同一批写入共用的决策时刻（缺省 `Date.now()`），保证多段 `decidedAt` 一致。 */
  decidedAt?: number
}

/**
 * 2A② **单一提交值写口**（帧路径与 POST 路径共用，避免两套 first-wins 漂移）：
 * 把值写到该消息内**所有**匹配 `toolCallId` 的段（同消息重复段也不漏），不碰其他工具；
 * `overwrite=false` 时已有值的段保持原值。返回是否有段被改写（调用方据此派生投影）。
 */
export function writeSubmittedValuesToMessage(
  message: ChatMessage,
  toolCallId: string,
  values: Record<string, unknown>,
  options: SubmittedValuesWriteOptions
): boolean {
  let touched = false
  const decidedAt = options.decidedAt ?? Date.now()
  for (let index = 0; index < message.parts.length; index += 1) {
    const part = message.parts[index]
    if (!part || part.kind !== 'tool' || part.toolCallId !== toolCallId) {
      continue
    }
    if (!options.overwrite && part.submittedValues !== undefined) {
      continue
    }
    message.parts.splice(index, 1, {
      ...part,
      submittedValues: values,
      // 2A 批（裁决 §3 缺陷①）：GF 提交同样记 `decidedAt`（**数据层记录，卡面不渲染** ——
      // r10 起卡头只留状态徽标 + 摘要 + chevron，时间不上卡面）。
      ...(part.decidedAt === undefined ? { decidedAt } : {}),
      requiresAction: false
    })
    touched = true
  }
  return touched
}

/**
 * 帧 → 段序列的写入（只写 `message.parts` 与消息身份/流式标志，**不碰旧投影**）。
 *
 * 覆盖帧集：delta（`kind` 缺省 = 正文 / `'reasoning'` = 思考段，L-91）/ reasoning（旧形态）/
 * message_start / message_end / tool_start / tool_result /
 * confirm_request / confirm_decision / frontend_tool_request / frontend_tool_result / suspended。
 * 其它帧（start / heartbeat / retry / done / error）的语义留在 store 的 case 分支
 * （它们还带轮次级副作用），此处一律 no-op。
 *
 * 裁决口径逐条照抄翻转前的 case 分支：
 * - `tool_result`：BLOCKED（防线③未执行）> 已被拒（REJECTED）> **outcome 八态（治理 P0-1，有则用）**
 *   > ok 三元（旧帧回落，无 outcome 时一字不动）；阻断文案按 `code` 选择（`TOOL_ALREADY_CLAIMED`
 *   与披露范围拦截不是同一件事）；SKIPPED 单独早分支（只落「输出」、不写 reason，见 case 内注）；
 * - `frontend_tool_result`：本地已终态（failed/rejected/blocked/succeeded/expired/cancelled/unknown/skipped）first-wins，
 *   否则 **outcome 八态（I1：帧带则有则用）** > 帧 `status`（后端 PendingToolCall 大写实发值）
 *   > ok 三元（`status` 缺失/未知才退回 ok；F1 收口修：原先不读 `status`，四类非失败终态全被落成 failed）；
 * - `confirm_decision`：**帧 status/decision 优先**（TIMEOUT → expired + decision='timeout'、
 *   REJECTED → rejected、CANCELLED → cancelled、APPROVED → running + decision='approved'），
 *   二者皆缺才回落 `approved` 布尔；超时帧对已落定段 first-wins（2A 挂账①）。
 */
export function applyFrameToParts(message: ChatMessage, frame: SseFrame): void {
  switch (frame.type) {
    case 'message_start': {
      message.messageId = frame.messageId
      message.streaming = true
      // R-154 #17：实时路时间戳 = 帧的服务端构造时刻（atMs 与 messageId 同帧单点取值）
      if (message.timestamp === undefined && frame.atMs !== undefined) {
        message.timestamp = frame.atMs
      }
      // 批次6A：思考段边界帧就是**段的起点**（首个思考 delta 之前可能有一段静默，起点不能
      // 从 delta 才起算）。只认服务端 atMs；旧帧没有本键则不造起点。
      if (frame.kind === 'reasoning') {
        openStreamPart(message, 'reasoning', frame.atMs)
      }
      break
    }
    case 'message_end': {
      // 段边界：只封口末尾流式段 + 记身份，不改段结构（R2-2）
      message.messageId = frame.messageId
      sealTrailingStreamPart(message)
      // 批次6A：思考段封口帧的 atMs 是 `endedAt` 的权威来源（秒表就此停住）。
      // 上面的 sealStreamPart 已用 lastFrameAt 兜底过一次 —— 本帧带 atMs 时以帧为准覆盖
      // （帧才是"段到此结束"的服务端钟；否则封口时长会停在上一个 delta 的时刻）。
      if (frame.kind === 'reasoning') {
        const part = lastReasoningPart(message)
        if (part) {
          noteReasoningFrame(part, frame.atMs)
          if (frame.atMs !== undefined) {
            part.endedAt = frame.atMs
          } else if (part.endedAt === undefined && part.lastFrameAt !== undefined) {
            part.endedAt = part.lastFrameAt
          }
        }
      }
      break
    }
    case 'delta': {
      // L-91：`kind==='reasoning'` = 思考段增量（缺省 = 正文，旧帧行为一字不动）。
      // 段的开合仍由边界帧/上一个段的状态机决定：`openStreamPart` 会在切换类型时封口末尾段
      //（封口时刻取当前帧 atMs —— 那正是段边界发生时的服务端钟）。
      const part = openStreamPart(message, frame.kind === 'reasoning' ? 'reasoning' : 'text', frame.atMs)
      part.text += frame.text
      part.streaming = true
      // A10 R-15：delta 的 text 也可能被单帧字节预算截断（FrameSizeLimiter fields=text）。
      // 旧帧缺省 ⇒ 不新增键；新帧把码/数字透传到段上，由 AiPanel 给角标提示。
      if (frame.truncated !== undefined) part.truncated = frame.truncated
      if (frame.noticeCode !== undefined) part.noticeCode = frame.noticeCode
      if (frame.originalChars !== undefined) part.originalChars = frame.originalChars
      if (frame.returnedChars !== undefined) part.returnedChars = frame.returnedChars
      if (frame.strategy !== undefined) part.strategy = frame.strategy
      if (frame.truncatedReason !== undefined) part.truncatedReason = frame.truncatedReason
      if (frame.kind !== 'reasoning') {
        message.streaming = true
      }
      break
    }
    case 'reasoning': {
      const part = openStreamPart(message, 'reasoning', frame.atMs)
      part.text += frame.text
      part.streaming = true
      break
    }
    case 'tool_start': {
      // 帧序防御（真模型 G-01）：park 开启时后端把「本批请求帧」提前公布，`tool_start` 可能
      // 晚于 `frontend_tool_request` 到达。已建立的等动作卡（pending + requiresAction）不得被
      // 复位成只读 running —— 一旦 requiresAction 归零，deriveProjections 不再产出 pendingCall，
      // 生成式表单的提交入口（gf-submit）就永远消失。契约顺序（tool_start → request）不变；
      // 这里只保证"顺序反过来时也不丢用户的提交路径"。
      const pending = findToolPart(message, frame.toolCallId)
      if (pending && pending.requiresAction) {
        break
      }
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: frame.kind,
        status: 'running',
        args: frame.args,
        requiresAction: false
      })
      break
    }
    case 'tool_result': {
      // 契约增量（SKIPPED 一等状态）：上游确定性失败导致本工具**未执行** —— 帧以
      // outcome='skipped'（或 status='SKIPPED'）声明时直接落 skipped。本支先于下方既有判据序，
      // 不改变其口径。真实后端序列：批次停走时该调用从未执行、**不发 tool_start**，只能凭
      // tool_result 建段。
      //
      // F1 收口 I-3（裁决）：skipped 卡面**只走「输出」** —— 人读文案统一落 `result`，**不写 reason**。
      // 后端把同一句措辞同时放进帧 result 与台账 reason，两键齐落会让「输出」块与红色「原因」行
      // 同屏重复同一句话。reason 缺失 ⇒ AiPanel 原因行（`!isBlankText(part.reason)`）自然不渲染，
      // 卡面视觉结构/样式/emoji 零改动。
      if (frame.outcome === 'skipped' || frame.status === 'SKIPPED') {
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: frame.kind,
          status: 'skipped',
          messageId: frame.messageId,
          // 信封文案（后端 skippedMessage 同文放帧 result）；message 仅作旧帧缺 result 时的回落。
          result: frame.result ?? frame.message ?? null,
          ...(frame.userTextCode !== undefined ? { userTextCode: frame.userTextCode } : {}),
          truncated: frame.truncated ?? false,
          truncatedReason: frame.truncatedReason ?? null,
          ...(frame.noticeCode !== undefined ? { noticeCode: frame.noticeCode } : {}),
          ...(frame.originalChars !== undefined ? { originalChars: frame.originalChars } : {}),
          ...(frame.returnedChars !== undefined ? { returnedChars: frame.returnedChars } : {}),
          ...(frame.strategy !== undefined ? { strategy: frame.strategy } : {}),
          ...(frame.outcome !== undefined ? { outcome: frame.outcome } : {}),
          requiresAction: false
        })
        break
      }
      // 补丁③：被人工拒绝（REJECTED）保持「已拒绝」，不被紧随其后的 tool_result(ok=false)
      // 覆盖成「执行失败」；防线③拦截（BLOCKED）同为"未执行"，展示口径与拒绝同类
      const existing = findToolPart(message, frame.toolCallId)
      const rejected = existing?.status === 'rejected'
      // 治理 P0-1：outcome='blocked' 与 status='BLOCKED' 同义（防线③未执行），一并落阻断口径
      const blocked = frame.status === 'BLOCKED' || frame.outcome === 'blocked' || existing?.status === 'blocked'
      // 治理 P0-1：**outcome 优先、缺省回落既有 ok 三元** —— 旧帧（无 outcome）走原逻辑一字不动；
      // 业务失败被异常处理器吞成文本时 ok 仍为 true，只有 outcome='failed' 能纠正卡头谎报。
      // BLOCKED 仍为最高优先级（status/段侧/outcome 三来源同义），已拒绝次之（补丁③口径）。
      const fromOutcome = outcomeStatus(frame.outcome)
      const status: ToolRunStatus = blocked
        ? 'blocked'
        : rejected ? 'rejected' : fromOutcome ?? (frame.ok ? 'succeeded' : 'failed')
      // 治理 P0-1 M2：阻断文案按 `code` 区分 —— 互斥抢占（TOOL_ALREADY_CLAIMED）不是"未披露"。
      // B23L1 批③b 增两支（只增不改）：资源锁冲突（RESOURCE_LOCKED）与目标资源不成立（TARGET_INVALID）——
      // 两者都是"本次未执行"，但原因完全不同（一个等对方收尾、一个参数就是错的）。
      // 返修（6C 建议，不扩散）：错入口（TOOL_KIND_MISMATCH）不是"越出披露范围"，而是走错通道 ——
      // 后端桩不执行前端工具，正确出口是当前页面。文案走 locale（与 6C 用户摘要同义）。
      const reason = blocked
        ? (frame.code === 'TOOL_ALREADY_CLAIMED'
            ? '该操作已由另一实例执行，本次未重复执行'
            : frame.code === 'RESOURCE_LOCKED'
              ? '该任务已有作业在执行，本次未执行'
              : frame.code === 'TARGET_INVALID'
                ? '目标资源不存在或不在当前任务内，本次未执行'
                : frame.code === 'TOOL_KIND_MISMATCH'
                  ? translate('ai.panel.toolKindMismatch')
                  : '该工具不在当前工作区上下文的披露范围内，未执行')
        : rejected ? (existing?.reason ?? '用户拒绝执行') : null
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: frame.kind,
        status,
        messageId: frame.messageId,
        result: frame.result ?? null,
        ...(frame.userTextCode !== undefined ? { userTextCode: frame.userTextCode } : {}),
        // R-154 #2 / A10 R-15：消费帧侧截断标记（noticeCode + 结构化数字优先；
        // truncatedReason 仅作旧帧/旧客户端兼容），随段持久化供卡面「已截断」角标渲染。
        truncated: frame.truncated ?? false,
        truncatedReason: frame.truncatedReason ?? null,
        ...(frame.noticeCode !== undefined ? { noticeCode: frame.noticeCode } : {}),
        ...(frame.originalChars !== undefined ? { originalChars: frame.originalChars } : {}),
        ...(frame.returnedChars !== undefined ? { returnedChars: frame.returnedChars } : {}),
        ...(frame.strategy !== undefined ? { strategy: frame.strategy } : {}),
        // 治理 P0-1：仅在帧带 outcome 时落段（旧帧不产生 undefined 键，镜像/段口径不变）；
        // 一期卡面不展示，仅供后续阶段读段
        ...(frame.outcome !== undefined ? { outcome: frame.outcome } : {}),
        // S6 批 2：视图通道（additive 可选键）—— 仅在帧带视图时落段（旧帧不产生 undefined 键）
        ...(frame.resultView ? { resultView: frame.resultView } : {}),
        reason,
        requiresAction: false
      })
      break
    }
    case 'confirm_request':
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'CONFIRM',
        status: 'pending',
        args: frame.args,
        // T4/T4-3：原始等待口径随段持久化（去超时后仅作对账 / 回滚，不驱动倒计时）
        timeoutSeconds: frame.timeoutSeconds,
        expiresAt: frame.expiresAt,
        atMs: frame.atMs,
        // H1 预检摘要（可选增量）：仅帧带该字段时落段 —— 旧帧不产生 undefined 键，
        // 已落段的预检摘要也不会被后续无该字段的帧抹掉。
        ...(frame.preflight ? { preflight: frame.preflight } : {}),
        // 批4-D（用户 2026-10-23 裁决）：不可撤销位落段（只在 true 时产生键 —— 普通后果
        // 确认卡的段形状与既有实现逐字节一致）。
        ...(frame.irreversible === true ? { irreversible: true } : {}),
        // 待确认 = 等人工动作（该段此刻正在等用户）
        requiresAction: true
      })
      break
    case 'confirm_decision': {
      // 2A 挂账①：结局以帧 status/decision 为准（TIMEOUT→expired / REJECTED→rejected /
      // CANCELLED→cancelled / APPROVED→running），二者皆缺才回落 approved 布尔（旧帧）。
      const resolved: ConfirmOutcome = confirmDecisionOutcome(frame)
        ?? (frame.approved ? { state: 'approved' } : { state: 'rejected' })
      if (resolved.state === 'timeout') {
        // ①b first-wins：超时通知不携带新的决策信息 —— 段已落定（含其它页签先落的历史
        // expired 段）时不得把结局改写成另一种终态。
        // 取舍（收口修 NIT-3）：不同页签的持久化形状可能不同 —— 先到者可能是 expired+无
        // decision（旧版本地判超时），也可能是 expired+decision='timeout'（本帧）。
        // 两者在卡面等价（readOnlyStateOf 均归「已超时」），故先到者形状保留即可，不为统一形状
        // 覆盖已落定段。与权威回执 gateReceiptOfStatus 的「无 decision」不对称是有意的：那条路
        // 只归一终态、不投影决策元数据，本路才承载帧带来的 decision。
        const existing = findToolPart(message, frame.toolCallId)
        if (existing !== null && existing.status !== 'pending' && existing.status !== 'running') {
          break
        }
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'CONFIRM',
          status: 'expired',
          decision: 'timeout',
          reason: frame.reason ?? '确认等待超时',
          requiresAction: false
        })
      } else if (resolved.state === 'rejected') {
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'CONFIRM',
          status: 'rejected',
          decision: 'rejected',
          reason: frame.reason ?? '用户拒绝',
          requiresAction: false
        })
      } else if (resolved.state === 'cancelled') {
        // 批G · S13：取消 = 用户决策（decision='cancel'）⇒ 门卡第五态「✗ 已取消」，
        // 不再被 status 兜底读成「! 已超时」（段态仍是 cancelled，只读态由 decision 派生）。
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'CONFIRM',
          status: 'cancelled',
          decision: 'cancel',
          requiresAction: false
        })
      } else {
        // 放行后工具立刻进入执行（工具卡转"调用中"，结果帧随后落定）。决策元数据一并落段，
        // 供 readOnlyStateOf 的「已批准 · 执行失败」分支读（2A 挂账③）。
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'CONFIRM',
          status: 'running',
          decision: 'approved',
          requiresAction: false
        })
      }
      break
    }
    case 'frontend_tool_request':
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status: 'pending',
        args: frame.args,
        timeoutSeconds: frame.timeoutSeconds,
        expiresAt: frame.expiresAt,
        atMs: frame.atMs,
        // 待前端执行（含等用户填写的生成式表单）= 等前端动作
        requiresAction: true
      })
      break
    case 'frontend_tool_result': {
      // F1（收口修）：帧的 `status` 是后端对该次调用的**终态裁决**（实发值见 frontendResultStatus
      // 的注释），必须优先于 ok 三元 —— ok 只表示"回灌已收到"，把它当成功/失败判据会让
      // 取消/超时/入参被拒四类终态全落成"执行失败"（红队 MAJOR-1）。
      // 本地已终态的段（含乐观 cancelled 与批3③a 的 unknown）first-wins：不得被回执帧改写成
      // 另一种终态，也不得把前端自己判定的失败/结局未知改写成成功。
      // 批3③a D5(甲)：'unknown' 纳入 localTerminal —— 回灌 payload 不带 status/ok
      // （types/ai.ts FrontendToolResultRequest），后端回帧恒为 FRONTEND_RESULT，若 unknown
      // 不在此集合，回执帧会把它翻成 succeeded（T1 生死线用例锁定）。
      // 契约增量：'skipped' 同为终态（一等状态），first-wins 不得被后续回执改写。
      const existing = findToolPart(message, frame.toolCallId)
      const localTerminal = existing?.status === 'failed' || existing?.status === 'rejected'
        || existing?.status === 'blocked' || existing?.status === 'succeeded'
        || existing?.status === 'expired' || existing?.status === 'cancelled'
        || existing?.status === 'unknown' || existing?.status === 'skipped'
      // 治理 P0-1 I1（收口修）：回执帧补齐 outcome 四字段后，结局以 outcome 为准（复用单表）——
      // 后端回帧 status 仍是 FRONTEND_RESULT，若让旧表先行，ok=false 会被盖成成功。旧帧
      // （无 outcome）仍走原表，行为一字不动。契约增量：status='SKIPPED' 与 outcome='skipped' 同义。
      const frameStatus: ToolRunStatus = frame.outcome === 'skipped' || frame.status === 'SKIPPED'
        ? 'skipped'
        : outcomeStatus(frame.outcome)
          ?? frontendResultStatus(frame.status)
          ?? (frame.ok ? 'succeeded' : 'failed')
      const status: ToolRunStatus = localTerminal
        ? (existing?.status ?? 'failed')
        : frameStatus
      // 2A②：结局帧先于 POST 应答到达时，只读卡已挂载且 submittedValues 尚未写回 —— 同帧把
      // result（提交值原文）落到段上，卡面无需等应答即可显示真值。只有帧本身是 succeeded 才写
      // （失败 / 取消不伪造），已有值 first-wins；落值走唯一写口 `writeSubmittedValuesToMessage`。
      const submittedValues = status === 'succeeded' && frameStatus === 'succeeded'
        ? submittedValuesFromResult(frame.name, frame.result)
        : null
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status,
        messageId: frame.messageId,
        result: frame.result ?? null,
        ...(frame.userTextCode !== undefined ? { userTextCode: frame.userTextCode } : {}),
        // A10 R-15 I-B：frontend_tool_result 帧同样会挂截断四件套（模型侧预裁 / 帧字节裁），
        // 必须随段透传；缺省帧不产生 undefined 键（旧帧零变化）。
        ...(frame.truncated !== undefined ? { truncated: frame.truncated } : {}),
        ...(frame.noticeCode !== undefined ? { noticeCode: frame.noticeCode } : {}),
        ...(frame.originalChars !== undefined ? { originalChars: frame.originalChars } : {}),
        ...(frame.returnedChars !== undefined ? { returnedChars: frame.returnedChars } : {}),
        ...(frame.strategy !== undefined ? { strategy: frame.strategy } : {}),
        ...(frame.truncatedReason !== undefined ? { truncatedReason: frame.truncatedReason } : {}),
        // 契约增量：skipped 的卡面 reason 取信封 message（缺省回落结果文本；均无则不覆盖原值）
        ...(status === 'skipped' && (frame.message !== undefined || frame.result !== undefined)
          ? { reason: frame.message ?? frame.result ?? null }
          : {}),
        requiresAction: false
      })
      if (submittedValues) {
        writeSubmittedValuesToMessage(message, frame.toolCallId, submittedValues, { overwrite: false })
      }
      break
    }
    case 'suspended': {
      // 后端对**每一批工具调用**都会先发 suspended，其中只有 CONFIRM/FRONTEND 需要人工或浏览器
      // 介入。批3③b D5（R-140①-2）："只发 suspended"路径可达（SpToolCallingManager 取消竞态：
      // out.suspended 先发、checkCancelled 可让整轮在 toolStart 前终止）——段尚不存在时**建占位段**
      // （status='pending' + requiresAction=true），不再什么都不写；生命周期双兜底：
      // ① 轮终态 expireResidueToolParts（pending ⇒ expired）；② 快照恢复路非 PENDING 收敛。
      // 已决 toolCallId 的建段拦截在 store 侧 handleFrame 的 D8 守卫（建段前过闸，防跨消息双段）。
      // 契约增量：suspended 载荷的条目也可能声明 skipped（上游失败导致未执行）—— 逐条落终态段，
      // 不建待决占位；已有终态的段 first-wins（延迟帧不得改写已落定结局）。
      for (const call of frame.toolCalls) {
        if (call.outcome !== 'skipped' && call.status !== 'SKIPPED') {
          continue
        }
        const existing = findToolPart(message, call.toolCallId)
        if (existing && existing.status !== 'pending' && existing.status !== 'running') {
          continue
        }
        upsertToolPart(message, {
          toolCallId: call.toolCallId,
          name: call.name,
          kind2: call.kind,
          status: 'skipped',
          // resultText 是信封人读文案（后端 skippedMessage）；reason 仅为错误码，作末位兜底。
          reason: call.resultText ?? call.reason ?? null,
          requiresAction: false
        })
      }
      const waiting = frame.toolCalls.find((call) => (call.kind === 'CONFIRM' || call.kind === 'FRONTEND')
        && call.outcome !== 'skipped' && call.status !== 'SKIPPED')
      if (waiting) {
        upsertToolPart(message, {
          toolCallId: waiting.toolCallId,
          name: waiting.name,
          kind2: waiting.kind,
          status: 'pending',
          // H1 预检摘要（可选增量）：同 confirm_request 口径 —— 帧带才落段，缺省不产生键。
          ...(waiting.preflight ? { preflight: waiting.preflight } : {}),
          // 批4-D：不可撤销位同口径落段（suspended 先于 confirm_request 到达时，
          // 复选框不该等到请求帧才出现）。
          ...(waiting.irreversible === true ? { irreversible: true } : {}),
          requiresAction: true
        })
      }
      break
    }
    default:
      // 联合类型已穷尽；运行时多出的未知帧（后端加帧）在此静默忽略
      break
  }
}

/**
 * R-158 #11：复制文本 = 全部 text 段 `join('\n\n')`（段间空行，单一事实源）。
 *
 * 为什么独立纯函数：复制口径与 `deriveProjections` 的 content 契约（逐字一致，parts.ts:167
 * 红线）分家——content 按"到达顺序拼接无间隔"服务渲染/历史，复制按业界惯例段间空行。
 * **不碰** deriveProjections / 镜像 / delta 路径。
 */
export function deriveCopyText(message: ChatMessage): string {
  return message.parts
    .filter((part): part is TextPart => part.kind === 'text')
    .map((part) => part.text)
    .join('\n\n')
}

/** 该帧是否已应用过（消息级幂等记账，R2-1：只去重、不做连续性判定）。 */
export function alreadyApplied(message: ChatMessage, seq: number): boolean {
  return Array.isArray(message.appliedSeqs) && message.appliedSeqs.includes(seq)
}

/**
 * 幂等记账保留的最近帧序号个数（B2 有界）。
 *
 * <p>为什么定长：`appliedSeqs` 是一条消息的"已应用帧"台账，长轮会累积到成千上万条 ——
 * 每条一次 `includes` 线性查找，整体 O(n²)，且随镜像/内存一起膨胀。重投窗口远小于 64
 *（续收/重挂只回放最近一段），超窗丢最旧的风险可忽略。
 */
export const APPLIED_SEQ_WINDOW = 64

/** 记账一条已应用的帧序号（幂等；保留最近 {@link APPLIED_SEQ_WINDOW} 条，仍按成员去重）。 */
export function markApplied(message: ChatMessage, seq: number): void {
  if (!Array.isArray(message.appliedSeqs)) {
    message.appliedSeqs = []
  }
  const applied = message.appliedSeqs
  if (applied.includes(seq)) {
    return
  }
  applied.push(seq)
  if (applied.length > APPLIED_SEQ_WINDOW) {
    applied.splice(0, applied.length - APPLIED_SEQ_WINDOW)
  }
}
