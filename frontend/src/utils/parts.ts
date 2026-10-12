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
import type {
  ChatMessage,
  MessagePart,
  PendingToolCall,
  ReasoningPart,
  TextPart,
  ToolPart,
  ToolPartPatch,
  ToolRun,
  ToolRunStatus
} from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { parseToolArgs } from '@/utils/frontend-tools'

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
 */
export function openStreamPart(message: ChatMessage, kind: 'text' | 'reasoning'): TextPart | ReasoningPart {
  const last = message.parts[message.parts.length - 1]
  if (last && last.kind === kind && last.streaming) {
    return last
  }
  if (last && (last.kind === 'text' || last.kind === 'reasoning') && last.streaming) {
    last.streaming = false
  }
  const seg = nextSeg(message.parts)
  if (kind === 'reasoning') {
    const part: ReasoningPart = { kind: 'reasoning', seg, text: '', streaming: true }
    message.parts.push(part)
    return part
  }
  const part: TextPart = { kind: 'text', seg, text: '', streaming: true }
  message.parts.push(part)
  return part
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
    last.streaming = false
  }
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
 * （与旧 `restoreSuspendedSnapshot` 的 kind 判定同口径）。
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
 *   （ai.ts）——同 7 态（succeeded/rejected/blocked/expired/cancelled/unknown/failed）、
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
    case 'APPROVED':
    case 'PENDING':
      return 'pending'
    default:
      return null
  }
}

/**
 * 帧 → 段序列的写入（只写 `message.parts` 与消息身份/流式标志，**不碰旧投影**）。
 *
 * 覆盖帧集：delta / reasoning / message_start / message_end / tool_start / tool_result /
 * confirm_request / confirm_decision / frontend_tool_request / frontend_tool_result / suspended。
 * 其它帧（start / heartbeat / retry / done / error）的语义留在 store 的 case 分支
 * （它们还带轮次级副作用），此处一律 no-op。
 *
 * 裁决口径逐条照抄翻转前的 case 分支：
 * - `tool_result`：BLOCKED（防线③未执行）> 已被拒（REJECTED）> ok 三元；
 * - `frontend_tool_result`：本地已终态（failed/rejected/blocked/succeeded/expired/cancelled/unknown）first-wins，
 *   否则按帧 `status`（后端 PendingToolCall 大写实发值）映射终态，`status` 缺失/未知才退回 ok 三元
 *   （F1 收口修：原先不读 `status`，四类非失败终态全被落成 failed）；
 * - `confirm_decision`：approved → running、rejected → rejected（reason 原样）。
 */
export function applyFrameToParts(message: ChatMessage, frame: SseFrame): void {
  switch (frame.type) {
    case 'message_start':
      message.messageId = frame.messageId
      message.streaming = true
      // R-154 #17：实时路时间戳 = 帧的服务端构造时刻（atMs 与 messageId 同帧单点取值）
      if (message.timestamp === undefined && frame.atMs !== undefined) {
        message.timestamp = frame.atMs
      }
      break
    case 'message_end':
      // 段边界：只封口末尾流式段 + 记身份，不改段结构（R2-2）
      message.messageId = frame.messageId
      sealTrailingStreamPart(message)
      break
    case 'delta': {
      const part = openStreamPart(message, 'text')
      part.text += frame.text
      part.streaming = true
      message.streaming = true
      break
    }
    case 'reasoning': {
      const part = openStreamPart(message, 'reasoning')
      part.text += frame.text
      part.streaming = true
      break
    }
    case 'tool_start':
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: frame.kind,
        status: 'running',
        args: frame.args,
        requiresAction: false
      })
      break
    case 'tool_result': {
      // 补丁③：被人工拒绝（REJECTED）保持「已拒绝」，不被紧随其后的 tool_result(ok=false)
      // 覆盖成「执行失败」；防线③拦截（BLOCKED）同为"未执行"，展示口径与拒绝同类
      const existing = findToolPart(message, frame.toolCallId)
      const rejected = existing?.status === 'rejected'
      const blocked = frame.status === 'BLOCKED' || existing?.status === 'blocked'
      const status: ToolRunStatus = blocked ? 'blocked' : rejected ? 'rejected' : frame.ok ? 'succeeded' : 'failed'
      const reason = blocked
        ? '该工具不在当前工作区上下文的披露范围内，未执行'
        : rejected ? (existing?.reason ?? '用户拒绝执行') : null
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: frame.kind,
        status,
        messageId: frame.messageId,
        result: frame.result ?? null,
        // R-154 #2：消费帧侧截断标记（FrameSizeLimiter TRUNCATED_FIELD/TRUNCATED_REASON_FIELD），
        // 随段持久化供卡面「已截断」角标渲染；缺省 = 未截断
        truncated: frame.truncated ?? false,
        truncatedReason: frame.truncatedReason ?? null,
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
        // T4/T4-3：倒计时口径随段持久化 —— pendingCall 现由段派生，元数据必须在段上
        timeoutSeconds: frame.timeoutSeconds,
        expiresAt: frame.expiresAt,
        atMs: frame.atMs,
        // 待确认 = 等人工动作（该段此刻正在等用户）
        requiresAction: true
      })
      break
    case 'confirm_decision':
      if (frame.approved) {
        // 放行后工具立刻进入执行（工具卡转"调用中"，结果帧随后落定）
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'CONFIRM',
          status: 'running',
          requiresAction: false
        })
      } else {
        upsertToolPart(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind2: 'CONFIRM',
          status: 'rejected',
          reason: frame.reason ?? '用户拒绝',
          requiresAction: false
        })
      }
      break
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
      const existing = findToolPart(message, frame.toolCallId)
      const localTerminal = existing?.status === 'failed' || existing?.status === 'rejected'
        || existing?.status === 'blocked' || existing?.status === 'succeeded'
        || existing?.status === 'expired' || existing?.status === 'cancelled'
        || existing?.status === 'unknown'
      const fromFrame = frontendResultStatus(frame.status)
      const status: ToolRunStatus = localTerminal
        ? (existing?.status ?? 'failed')
        : fromFrame ?? (frame.ok ? 'succeeded' : 'failed')
      upsertToolPart(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind2: 'FRONTEND',
        status,
        messageId: frame.messageId,
        result: frame.result ?? null,
        requiresAction: false
      })
      break
    }
    case 'suspended': {
      // 后端对**每一批工具调用**都会先发 suspended，其中只有 CONFIRM/FRONTEND 需要人工或浏览器
      // 介入。批3③b D5（R-140①-2）："只发 suspended"路径可达（SpToolCallingManager 取消竞态：
      // out.suspended 先发、checkCancelled 可让整轮在 toolStart 前终止）——段尚不存在时**建占位段**
      // （status='pending' + requiresAction=true），不再什么都不写；生命周期双兜底：
      // ① 轮终态 expireResidueToolParts（pending ⇒ expired）；② 快照恢复路非 PENDING 收敛。
      // 已决 toolCallId 的建段拦截在 store 侧 handleFrame 的 D8 守卫（建段前过闸，防跨消息双段）。
      const waiting = frame.toolCalls.find((call) => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')
      if (waiting) {
        upsertToolPart(message, {
          toolCallId: waiting.toolCallId,
          name: waiting.name,
          kind2: waiting.kind,
          status: 'pending',
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

/** 该帧是否已应用过（消息级幂等记账，R2-1：只去重、不做连续性判定）。 */
export function alreadyApplied(message: ChatMessage, seq: number): boolean {
  return Array.isArray(message.appliedSeqs) && message.appliedSeqs.includes(seq)
}

/** 记账一条已应用的帧序号（幂等）。 */
export function markApplied(message: ChatMessage, seq: number): void {
  if (!Array.isArray(message.appliedSeqs)) {
    message.appliedSeqs = []
  }
  if (!message.appliedSeqs.includes(seq)) {
    message.appliedSeqs.push(seq)
  }
}
