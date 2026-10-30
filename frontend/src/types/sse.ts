/**
 * SSE 帧契约（判别联合，全部以 `type` 字段判别）。
 *
 * 核对源：backend/.../ai/run/SseChatEmitter.java（帧构造现场）、
 *         ai/run/PendingToolCall.java、ai/web/AiController.java
 *         task/service/TaskSseService.java（任务级事件通道，形态不同，见本文件后半）
 *
 * 形态说明：后端用**无名 SSE 事件**，把 JSON 放进 data 字段，`type` 在帧内
 * （SseChatEmitter#emit → objectMapper.writeValueAsString(frame)），
 * 因此前端只需解析 data 文本，不必 addEventListener 按事件名分派。
 *
 * 与规格书 §3 的差异（核对结论）：规格书按简写列了 `tool_call`，
 * 后端实际是 **两帧**：`tool_start`（工具开始执行）与 `tool_result`（执行结束），
 * 另有 `confirm_decision` / `frontend_tool_request` / `frontend_tool_result` / `retry`。
 * 本文件按后端实测帧集定义。
 */

import type { ResultView } from './resultview'

/** 挂起种类（PendingToolCall.KIND_*）。 */
export type ToolKind = 'CONFIRM' | 'FRONTEND' | 'BACKEND'

/**
 * 工具结局维度（治理 P0-1 统一结果信封的 `outcome` 取值域；
 * 依据 `docs/research/调研-AI工具执行治理与互斥-2026-10-19.md` §6.4 P0-1）。
 *
 * 与 `ok` 的关系：`ok` 是旧协议的布尔二元、语义只覆盖"执行是否成功"；`outcome` 是八态
 * 真实结局，`ok:true` 并不蕴含 `outcome:'ok'`（业务失败但被异常处理器吞成文本时，后端按本字段
 * 如实报 `failed`）。**旧帧没有本字段 ⇒ 前端必须回落到 `ok` 三元**，不得臆造默认值。
 *
 * `skipped`（契约增量，与后端已锁定）：上游确定性失败导致本工具**未执行**；信封 `code`
 * 通常为 `UPSTREAM_FAILED`。它是一等状态 —— 不是 `failed`，也不是"空"。
 */
export type ToolOutcome = 'ok' | 'failed' | 'rejected' | 'blocked' | 'timeout' | 'cancelled' | 'unknown' | 'skipped'

/** 挂起条目状态（PendingToolCall 的状态常量）。 */
export type PendingStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'TIMEOUT'
  | 'FRONTEND_RESULT'
  /** T2a-D#2（C2）：前端工具取消终态（用户放弃/渲染器不可用），结局帧 status 实发值 */
  | 'FRONTEND_CANCELLED'
  /** 整轮取消（ConfirmGate#cancel 落地）实发值；T2a 返修（S1）起映射到 cancelled 终态 */
  | 'CANCELLED'
  | 'EXECUTED'
  /** 入参被 schema 安全闸拒绝（SpToolCallingManager#rejectFrontendArguments）实发值，T2a 返修（S1）补入 */
  | 'REJECTED_ARGUMENTS'
  /** DC-14 T1：防线③（执行兜底）拦截 —— 越 scope 的工具调用未执行 */
  | 'BLOCKED'
  /** 契约增量：上游确定性失败导致本工具未执行（一等状态，对应 outcome=`skipped`） */
  | 'SKIPPED'
  /** F5 预检未通过：后端就地结算"未进确认门、未执行"的终态条目（code 同名，outcome=failed） */
  | 'PRECHECK_FAILED'
  /** 表格工具被管理员关闸（AI 表格工具治理批，`app.ai.sheet.*`）：未执行、零副作用，outcome=failed */
  | 'SHEET_DISABLED'

/**
 * H1 确认门**预检摘要**（可选契约增量，与后端已锁定；缺省 = null/undefined，旧帧零影响）。
 *
 * 预检数据随帧与恢复载荷保留，用于既有业务标题等展示；普通确认卡不展示固定三字段摘要。
 * 预检不通过 → 后端**不弹卡**（不进确认门，无卡形态），故前端不存在「未通过」的门卡分支。
 * 高危操作的后果说明与复选框由 `irreversible` 独立控制，不依赖预检摘要。
 *
 * <p><b>`ToolPreflightTarget` 的字段形状有两族</b>（后端 `ToolPreflightService` 的两个分支，
 * 前端不得假设同形）：
 * <ul>
 * <li>`start_*`：任务已存在 ⇒ 带 `taskId/status`；</li>
 * <li>`create_task`：任务**批准后才存在** ⇒ target 只有 `type/title`
 * （`taskId` 不存在），卡头因此走「创建…任务」句式，不套 `任务 #<id>` 模板。</li>
 * </ul>
 */
export interface ToolPreflightTarget {
  /** 任务 id（demo 卡面渲染为「任务 #5」）；`create_task` 无此键 */
  taskId?: string | number
  /** 任务标题（demo：「订单配置导入」）；`create_task` 为待创建任务的标题 */
  title: string
  /** 任务类型（demo：「导入」；`create_task` 为待创建类型 EXPORT/IMPORT） */
  type: string
  /** 任务状态（demo：「进行中」）；`create_task` 无此键 */
  status?: string
}

/** 单项预检结论（`pass=false` 在门卡形态下不会出现 —— 预检失败后端不弹卡）。 */
export interface ToolPreflightCheck {
  /**
   * 稳定机器码（`ToolPreflightService.CheckCode`，如 `TASK_EXISTS` / `CONDITIONS_DECIDED`）。
   * 协议面：按码与 `args` 分支；`name`/`detail` 只是人读渲染（随 ReplyLanguage 变）。
   * 缺省 = 旧帧（后端升级前）
   */
  code?: string
  /** 检查项名（demo：「任务存在」/「预检已通过」） */
  name: string
  pass: boolean
  /** 补充说明（demo：指纹「3a9f…」），缺省 = 只显示检查项名 */
  detail?: string | null
  /**
   * 结构化参数（如 `taskId` / `jobType` / `undecided`）。协议面：与 `code` 一起构成可分支的结论；
   * 缺省 = 旧帧
   */
  args?: Record<string, unknown>
}

/** 预检摘要载荷（suspended 帧 toolCalls[] / confirm_request 帧可选字段）。 */
export interface ToolPreflight {
  target: ToolPreflightTarget
  checks: ToolPreflightCheck[]
  /** 影响面人读文本（demo：「将发布 3 个配置定义 · 影响 12,480 行数据」） */
  impact: string
  /** 预检报告指纹（demo：「批准绑定」行的依据；卡面只展示说明行，不直接显示本值） */
  reportHash: string
  /**
   * AR-02：结构化条件的**后端可读摘要**（预检阶段算好，additive 可选键）。
   * 缺省 = 旧帧 / 无此信息 ⇒ 不渲染条件行；前端不得用 args/模型文本自行反解。
   */
  conditionSummary?: string | null
  /**
   * AR-02：宿主给出的**权威发布模式**（`Task.settingsJson.importMode` 同口径枚举，如
   * `MERGE` / `REPLACE`；additive 可选键）。缺省 = 未知 ⇒ 不渲染模式行；前端不得默认
   * 成 MERGE，也不得从模型回复猜模式。
   */
  publishMode?: string | null
}

/** 外置挂起条目（PendingToolCall#asMap 的字段集）。 */
export interface PendingToolCallSnapshot {
  toolCallId: string
  name: string
  /** AssistantMessage.ToolCall.type() 原文（OpenAI 协议下为 'function'） */
  type: string
  kind: ToolKind
  /** 工具入参 JSON **字符串** */
  arguments: string
  status: PendingStatus | string
  reason?: string | null
  resultText?: string | null
  executed: boolean
  executedBy?: string | null
  claimedAtMs?: number
  executedAtMs?: number
  requestedAtMs?: number
  resolvedAtMs?: number
  /** 治理 P0-1：条目结局八态（可选，旧载荷没有；含契约增量的 skipped） */
  outcome?: ToolOutcome
  /** 治理 P0-1：错误码（可选，null = 无码） */
  code?: string | null
  /** H1 确认门预检摘要（可选增量；缺省 = 旧载荷，门卡无预检区） */
  preflight?: ToolPreflight | null
  /**
   * 批4-D：本条目不可撤销（{@code Category.P}）—— 与 `confirm_request` 帧同口径的
   * additive 字段（`suspended` 帧先到，复选框不该等到请求帧才出现）。
   */
  irreversible?: boolean
}

/** 全部帧类型常量（顺序即后端产出顺序语义）。 */
export const SseFrameTypes = [
  'start',
  /**
   * 上下文压缩事件（上下文统计与压缩专项批①；帧类型集 15 → 16 的**唯一**新增）。
   *
   * 位置：压缩发生在**请求前**，故它必然出现在本轮第一个 `delta` / `tool_start` 之前；
   * 载荷 `{level, before, after, turns, lostTokens, reason, est, savedTokens, clearedPairs}`。
   * 未发生压缩则**一帧不发**（不是"每轮发一个 level=none"）。
   */
  'context',
  'message_start',
  'delta',
  'message_end',
  'tool_start',
  'tool_result',
  'confirm_request',
  'confirm_decision',
  'frontend_tool_request',
  'frontend_tool_result',
  'suspended',
  'heartbeat',
  'retry',
  'done',
  'error'
] as const

export type SseFrameType = (typeof SseFrameTypes)[number]

/** 前向兼容的思考链帧名（裁决⑦；后端当前不产出，故不在上面的实测白名单里）。 */
export const REASONING_FRAME_TYPE = 'reasoning'

/**
 * 超时阶段（批次3 / AR-06）：三种收尾失败必须能被调用方区分，不能一律说成"断流"。
 *
 * - `connect`：`fetch` 到响应头之间超时（请求可能根本没到后端）；
 * - `first-frame`：响应已建立，但一个 SSE 帧都没收到；
 * - `idle`：已经收到过帧，但连续 45s 无任何帧（含阶段心跳）。
 *
 * <p>
 * 定义在**契约层**（本文件）：消费方是 `types/ai.ts` 的 `StreamInterrupted.timeoutStage`
 * （消息级收尾契约），而类型层不得反向依赖 api 层（api 层的看门狗只负责判出阶段，不拥有这个词汇）。
 * `api/sse.ts` 现仍保留一份同值的本地声明（等价联合，双向可赋值）；收敛时把它改成
 * `import type` + 再导出即可，本次不动 api 层。
 */
export type SseTimeoutStage = 'connect' | 'first-frame' | 'idle'

/**
 * 截断展示码（A10 R-15 I-3，additive 可选键，不是新帧型）。
 *
 * 新客户端据此选 locale 模板；`notice`/`truncatedReason` 只作旧客户端兼容字段。
 */
export type TruncationNoticeCode = 'frame_truncated' | 'frame_limit_unreached' | 'model_view_truncated'

export interface SseFrameBase {
  type: SseFrameType
  runId?: string
  /**
   * 帧的单调序号（DC-14 T6，后端每帧递增，重启续跑后接着归档里的最大值继续）。
   * reattach 时回传 `?lastSeq=N` 即只补差量；旧帧可能没有该字段（按"照收"处理）。
   */
  seq?: number
  /**
   * T4-3：帧的**服务端构造时刻**（epoch 毫秒；后端帧构造点 `SseChatEmitter#frame` 取值）。
   * **实时帧与归档 / 回放帧都带本字段**（同一份帧：`RunStore#appendEvent` 的补入只是兜底），
   * 故前端按可选字段处理即可 —— 仅旧格式帧 / 损坏数据缺失。
   * 与 `expiresAt` 同一服务端钟、同一帧内单点取值 ⇒ 二者之差精确等于 `timeoutSeconds * 1000`，
   * 即服务端给出的**剩余时长**（T4-3 后半）。
   */
  atMs?: number
  /**
   * A10 R-15：帧级截断标记（`text` / `result` 都可能被单帧字节预算降采样）。
   * 旧帧缺省 = 未截断；新帧同时带 `noticeCode` + 数字，展示层按 locale 拼装。
   */
  truncated?: boolean
  /** 展示码（新客户端优先读它；旧帧没有该键）。 */
  noticeCode?: TruncationNoticeCode
  /** 截断前字符数（结构化字段；展示层用于本地化数字）。 */
  originalChars?: number
  /** 截断后实际返回字符数（结构化字段）。 */
  returnedChars?: number
  /** head / tail / head_tail（结构化字段；展示层不直接展示）。 */
  strategy?: string | null
  /** 旧客户端兼容的人读短句（新客户端只在缺 `noticeCode` 时展示）。 */
  notice?: string | null
  /** `notice` 的兼容别名（旧帧/历史段仍读它）。 */
  truncatedReason?: string | null
}

/** 首包：runId 由服务端生成，前端据此做 reattach。 */
export interface SseStartFrame extends SseFrameBase {
  type: 'start'
  runId: string
  sessionId: string
}

/** 正文增量（生成中）。 */
export interface SseDeltaFrame extends SseFrameBase {
  type: 'delta'
  text: string
  /**
   * 段归属（L-91 起后端真实产出思考段）：
   * - **缺省 / `'text'`** = 正文增量 —— 旧帧没有该键，键集合逐字不变（additive 口径）；
   * - `'reasoning'` = 思考增量（上游 `reasoning_content` 的透传，配
   *   `message_start/message_end` 的 `kind='reasoning'` 边界，后端 `SseChatEmitter#reasoningDelta`）。
   *
   * 为什么是"可选键"而不是第 17 种帧型：帧类型集冻结在 16（后端 `FrameTypeSetFrozenTest`
   * 双端比对守卫），而思考段需要的信息只有"这一段属于哪种段" —— 边界帧早已带 `kind`，
   * 补上 delta 侧的同一位即可，双端白名单 / conformance / 桩协议都不必新增帧型。
   */
  kind?: 'text' | 'reasoning'
  /** DC-14 T5：本片正文所属的助手消息 id（消息级渲染/去重的判据） */
  messageId?: string
}

/**
 * 消息边界帧（DC-14 T5 / R8-2 的三段式：`message_start` → `delta`* → `message_end`）。
 *
 * 为什么需要它：一条助手消息可能"先思考、再正文、中间夹工具调用"，只有 content 帧时
 * 客户端无法判断两段文本属于同一条消息还是两条（SP-01ab §7.3 的混合消息渲染痛点）。
 * `kind` 区分文本段（`text`）与思考段（`reasoning`）—— **L-91 起两者都是真实产出的**
 * （思考段由后端 `SseChatEmitter#reasoningDelta` 开出，段内 delta 带 `kind='reasoning'`）。
 */
export interface SseMessageBoundaryFrame extends SseFrameBase {
  type: 'message_start' | 'message_end'
  messageId: string
  kind: 'text' | 'reasoning' | string
  /** 仅 message_end：本段字符数（与 done 帧对账用） */
  chars?: number
}

/** 工具开始（后端执行或挂起前的公告）。 */
export interface SseToolStartFrame extends SseFrameBase {
  type: 'tool_start'
  toolCallId: string
  name: string
  kind: ToolKind
  /** 入参 JSON 字符串 */
  args?: string
}

/** 工具执行结束。 */
export interface SseToolResultFrame extends SseFrameBase {
  type: 'tool_result'
  toolCallId: string
  name: string
  kind: ToolKind
  ok: boolean
  executed: boolean
  executedBy?: string | null
  reused?: boolean
  /** 待决条目的最终状态（DC-14 T1：BLOCKED = 被防线③拦下，未执行） */
  status?: PendingStatus | string
  /** DC-14 T5：结果铸为消息后的消息 id（`tool-<toolCallId>`，重放/重挂不变） */
  messageId?: string
  result?: string | null
  /**
   * 治理 P0-1（统一结果信封）：结局八态。**可选** —— 旧后端帧没有该字段，此时前端按
   * `ok` 三元回落（{@link ToolOutcome}）。带该字段时它**优先于 `ok`**：业务失败但被
   * 异常处理器吞成文本的场景下 `ok` 仍为 true，只有 `outcome` 如实报 `failed`。
   */
  outcome?: ToolOutcome
  /** 治理 P0-1：错误码（可选，null = 无码）；一期只落段/展示二期，卡面不展示 */
  code?: string | null
  /** 治理 P0-1：是否可重试（可选；旧帧没有）；一期仅契约透传，不参与卡面判定 */
  retryable?: boolean
  /** 治理 P0-1：目标资源（可选，null = 未声明）；一期仅契约透传 */
  target?: Record<string, unknown> | null
  /**
   * 信封人读文本（可选；SKIPPED 时由后端给出「未执行：上游 start_import 失败…」一类说明）。
   * 卡面 reason 行在 skipped 时直接展示本字段。
   */
  message?: string | null
  /**
   * S6 批 2：工具结果的**视图通道**（additive 可选键，设计稿 §6）。
   *
   * 帧类型集仍是 16（新增的是键，不是帧名）：`result` 是给模型/人读的那一份，
   * `resultView` 是给卡面渲染器的结构化投影，两者同源。缺省 = 旧帧 → 卡面走旧形态。
   */
  resultView?: ResultView | null
}

/** HITL 确认请求（挂起等待口径）。 */
export interface SseConfirmRequestFrame extends SseFrameBase {
  type: 'confirm_request'
  toolCallId: string
  name: string
  args?: string
  /** 与 args 同值（后端同时放了两份，供展示摘要用） */
  summary?: string
  /**
   * 挂起等待上限（秒）。**可选**：去超时裁决（2026-10-20）后确认门不再超时，后端帧已不带本字段；
   * 仅历史归档帧 / 旧后端可能携带，前端不再据此折算倒计时或本地超时。
   */
  timeoutSeconds?: number
  /**
   * DC-14 T4：挂起等待的**绝对**到期时刻（epoch 毫秒 = 帧内 `atMs` + timeoutSeconds，服务端单点取钟）。
   *
   * 去超时裁决（2026-10-20）：前端不再据此折算倒计时 / 本地超时，本字段随帧照原样透出，
   * 仅供对账 / 回滚。
   */
  expiresAt?: number
  /** 回调端点说明文本：POST /api/ai/confirm {runId, toolCallId, approved, reason} */
  callback?: string
  /** H1 确认门预检摘要（可选增量；缺省 = 旧帧，门卡不渲染预检区） */
  preflight?: ToolPreflight | null
  /**
   * 批4-D（用户 2026-10-23 裁决）：本次确认**不可撤销**（后端 `ToolMeta.Category.P`，
   * 当前唯一 = `start_publish`）—— 前端据此在门卡上叠加复选框（勾选才放行「批准」）。
   *
   * 可选增量：缺省/`false` = 普通后果确认卡（`start_export` / `start_precheck` / `start_import`）
   * 与旧后端帧，卡面一字不动。
   */
  irreversible?: boolean
}

/** HITL 确认决策回执（放行/拒绝双路径都走这里）。 */
export interface SseConfirmDecisionFrame extends SseFrameBase {
  type: 'confirm_decision'
  toolCallId: string
  name: string
  /** 决策原文（如 APPROVED/REJECTED/TIMEOUT） */
  decision: string
  approved: boolean
  status: PendingStatus | string
  reason?: string | null
  waitedMs?: number
  /**
   * F5 批准绑定：决策回执带回的预检指纹（`PendingToolCall.preflight.reportHash`；
   * 无预检 / 旧后端帧为 null 或缺省）。一期仅契约透传，不参与卡面判定。
   */
  preflightHash?: string | null
}

/** 前端工具请求（副作用在浏览器：打开编辑器 / 触发下载）。 */
export interface SseFrontendToolRequestFrame extends SseFrameBase {
  type: 'frontend_tool_request'
  toolCallId: string
  name: string
  args?: string
  /**
   * 前端工具回执等待上限（秒）。**可选**：`generative_form` 等人工填写场景后端不设上限，
   * 帧里不带本字段（去超时裁决 2026-10-20）；有值的即时执行类工具仍成对下发。
   */
  timeoutSeconds?: number
  /** DC-14 T4 同口径：绝对到期时刻（epoch 毫秒）；与 `timeoutSeconds` 成对出现（无上限时缺省），见 `SseConfirmRequestFrame.expiresAt` */
  expiresAt?: number
  /** 回调端点说明文本：POST /api/ai/frontend-tool-result {runId, toolCallId, result} */
  callback?: string
}

/** 前端工具结果回执。 */
export interface SseFrontendToolResultFrame extends SseFrameBase {
  type: 'frontend_tool_result'
  toolCallId: string
  name: string
  ok: boolean
  executed: boolean
  /** 待决条目的最终状态（BLOCKED = 被防线③拦下） */
  status?: PendingStatus | string
  /** DC-14 T5：结果铸为消息后的消息 id */
  messageId?: string
  result?: string | null
  /**
   * 治理 P0-1（统一结果信封，I1 收口）：结局八态，契约同 `tool_result` 帧。**可选** ——
   * 旧后端帧没有该字段（此时前端走原 `status` 旧表 + ok 三元）；带该字段时**优先于**
   * `status`/`ok`（`status='FRONTEND_RESULT'` 不再把 `ok=false` 盖成成功）。
   */
  outcome?: ToolOutcome
  /** 治理 P0-1：错误码（可选，null = 无码）；一期不参与卡面判定 */
  code?: string | null
  /** 治理 P0-1：是否可重试（可选；旧帧没有）；一期仅契约透传 */
  retryable?: boolean
  /** 治理 P0-1：目标资源（可选，null = 未声明）；一期仅契约透传 */
  target?: Record<string, unknown> | null
  /** 信封人读文本（可选；SKIPPED 时卡面 reason 行直接展示本字段） */
  message?: string | null
}

/** 挂起公告（快照已外置 Redis，带键名与写入者实例，供"可重挂"提示）。 */
export interface SseSuspendedFrame extends SseFrameBase {
  type: 'suspended'
  runId: string
  toolCalls: PendingToolCallSnapshot[]
  stateKey?: string
  pendingKey?: string
  writtenBy?: string
}

/**
 * 心跳帧（挂起期保活；流式期只保活）。键集不固定。
 *
 * 挂起期载荷见后端 `ConfirmGate.heartbeatData`（`runId` / `suspendKind` / `toolCallId` /
 * `name` / `status` / `waitedSeconds` / `timeoutSeconds`）。去超时裁决后前端只消费它维持
 * 挂起流保活，不再据秒数折算门卡倒计时。
 * 流式期心跳（`ResilientChatService`）不带这四个键，前端据此区分两条心跳通道。
 */
export interface SseHeartbeatFrame extends SseFrameBase {
  type: 'heartbeat'
  /** 挂起种类：`confirm`（确认门）/ `frontend-tool`（前端工具）；流式期心跳无此键 */
  suspendKind?: string
  /** 本条挂起条目的 toolCallId（流式期心跳无此键） */
  toolCallId?: string
  /** 挂起等待上限（秒；仅对账口径） */
  timeoutSeconds?: number
  /** 已等待秒数（服务端钟；仅对账口径） */
  waitedSeconds?: number
  /**
   * 批次3：阶段心跳。`streaming` 是既有的流式保活；`compacting` 是**请求前同步压缩**的
   * 独立保活（首轮 `start` 之后、压缩完成之前）；`preparing` 用于"已 start、快照未建"的
   * 重挂窗口。缺键 = 旧后端，按普通保活处理（不进入任何阶段）。
   */
  phase?: 'streaming' | 'compacting' | 'preparing' | string
  /**
   * 批次3：本阶段**真实已用毫秒数**（服务端钟，单点取值）。
   *
   * 前端只用它显示"已用 N 秒"，**不据此折算百分比**；缺键 = 服务端未提供（旧后端 /
   * 仅保活的心跳），前端不得编造数字。
   */
  elapsedMs?: number
  /** 兼容字段：与 `elapsedMs / 1000` 同义的整秒数（有 `elapsedMs` 时以它为准）。 */
  elapsedSeconds?: number
  [key: string]: unknown
}

/**
 * 思考链增量的**历史/前向兼容**形态（裁决⑦：**有思考才有块**，无思考不显示空的"思考过程"折叠块）。
 *
 * <p>
 * L-91（2026-10-23）起后端不再需要独立帧型：上游 `reasoning_content` 经
 * `delta{kind:'reasoning'}` + `message_start/message_end{kind:'reasoning'}` 透传，
 * 帧类型集仍是 16。本接口保留是因为**线上仍可能有旧桩/旧归档**发过 `type:'reasoning'`，
 * 且 `toSseFrame` 对它是放行的（未知帧静默丢弃会让旧流少一段思考）——store/parts 对两种形态
 * 都有承接（`case 'reasoning'` 与 `case 'delta'` 的 kind 分流）。
 *
 * 新代码请按 `SseDeltaFrame.kind === 'reasoning'` 解析；不要新增 `type:'reasoning'` 的发射点
 * （后端 `FrameTypeSetFrozenTest` 会红）。
 */
export interface SseReasoningFrame {
  type: 'reasoning'
  runId?: string
  /** 思考链增量文本 */
  text: string
  /**
   * 帧的**服务端构造时刻**（epoch 毫秒；与 `SseFrameBase.atMs` 同口径、同钟）。
   *
   * 本帧不在 `SseFrameTypes` 白名单里（旧流 / 旧归档形态），故没有继承 `SseFrameBase`；
   * 但思考计时链（`parts.ts` 的 `noteReasoningFrame` / `sealStreamPart`）要按帧取这个时刻
   * —— 缺键 = 旧帧，思考段按"无起点"处理，绝不拿本地 now 补造。
   */
  atMs?: number
}

/** 断流重试公告（本轮无正文产出时才会重试）。键集不固定。 */
export interface SseRetryFrame extends SseFrameBase {
  type: 'retry'
  [key: string]: unknown
}

/** 终帧：done（正常完成或取消共用，cancelled=true 表示取消）。 */
export interface SseDoneFrame extends SseFrameBase {
  type: 'done'
  runId: string
  /**
   * 本轮 usage（上下文专项批① 起含计量增量键）：既有三维 `promptTokens/completionTokens/totalTokens`
   * 不变，新增 `est`（是否估算）· `source`（`stream`/`estimate`）· `frames`（采集到的非空 usage 帧数）·
   * `dirty`（prompt 回退的脏帧数）。旧后端没有这四个键 ⇒ 前端按"未知"处理，不显示 `≈`。
   */
  usage?: Record<string, unknown>
  model?: string
  /**
   * 上下文计量快照（上下文专项批①；每轮都带，旧后端缺该键）。
   *
   * `usedTokens` 是**真值**（`usage.promptTokens`；无 usage 时为估算并置 `est=true`），
   * `limitTokens` 为 null 表示上限未知（降级态：只显示已用，不显示百分比）。
   */
  context?: ContextUsage
  cancelled?: boolean
  attempts?: number
  upstreamEvents?: number
  terminalSignal?: string
  /**
   * 批③（additive 键，帧型不变）：本轮请求带了 `params` 但被策略锁住
   * （`ai_setting['allow.userParams']=false`）⇒ 参数<b>未生效</b>。缺键 = 参数允许或本轮没带。
   */
  paramsLocked?: boolean
  /**
   * 上游以 `finish_reason=length` 收尾（达到 max_tokens）：本轮已有正文被截断，后端不重试。
   * additive 可选键；缺键 = 未截断（旧后端），"没说"与"没截断"因此可区分。
   */
  truncated?: boolean
  /**
   * 安全事件摘要（防护批③ B3-4；**additive 可选键**，帧类型集仍是 16）。
   *
   * `{redacted, rules, blocked}`：本轮正文里被脱敏的处数、命中的规则 id、是否被阻断。
   * **未命中时后端不写该键** —— "没发生"与"发生 0 次"必须可区分（与 `context` 同姿态）。
   */
  safety?: {
    redacted: number
    rules: string[]
    blocked: boolean
  }
}

/**
 * 上下文占用（`done.context` 与端点 `GET /api/ai/context/{sessionId}` 的公共子集）。
 *
 * 刻意与后端设计稿 §3.4(a) 逐键对应；`percent` 由服务端算（一位小数），前端不重复折算 ——
 * 前后端各自 round 一次会出现"49.95 → 一个显示 50.0、另一个 49.9"的同源分歧。
 */
export interface ContextUsage {
  usedTokens: number
  /** null = 上限未知（降级态） */
  limitTokens: number | null
  /** 服务端算好的百分比（一位小数）；上限未知时为 null */
  percent: number | null
  /** 本轮压缩级别（`none`/`L1`/`L2`/`L3`） */
  level: string
  /** usedTokens 是否来自估算（界面显示 `≈` 的唯一来源） */
  est: boolean
  /** 熔断态（`closed`/`open`/`half_open`；批③ 写入，批① 恒 closed） */
  circuit?: string
}

/** 压缩事件帧（仅在真发生压缩时出现）。 */
export interface SseContextFrame extends SseFrameBase {
  type: 'context'
  /** 实际应用的级别（批① 只到 `L1`） */
  level: string
  /** 压缩前/后的历史估算 */
  before: number
  after: number
  /** 折叠掉的 Turn 数（L1 恒 0：只清结果不丢轮） */
  turns: number
  /** 丢掉的 token（L1 恒 0） */
  lostTokens: number
  reason: string
  est: boolean
  savedTokens?: number
  clearedPairs?: number
}

/** 终帧：error。 */
export interface SseErrorFrame extends SseFrameBase {
  type: 'error'
  runId: string
  code: string
  message: string
  /**
   * 批次2 / AR-05（additive）：`true` = 这条 error 的语义是<b>终态暂不可确认</b>（中性视图），
   * 不是普通失败。`code` 仍可是 `AI_REDIS_UNAVAILABLE` —— 前端**只能**认本显式键
   * （或 `turn_end=unconfirmed`），不得拿故障码推断"可能成功"。
   */
  unconfirmed?: boolean
}

/**
 * 一轮对话的 SSE 帧判别联合（后端 **16** 类 + 前向兼容的旧 `reasoning` 帧）。
 *
 * 思考段自 L-91 起走 `delta{kind:'reasoning'}`（帧型不增），`SseReasoningFrame` 只兜旧流。
 */
export type SseFrame =
  | SseStartFrame
  | SseContextFrame
  | SseMessageBoundaryFrame
  | SseDeltaFrame
  | SseReasoningFrame
  | SseToolStartFrame
  | SseToolResultFrame
  | SseConfirmRequestFrame
  | SseConfirmDecisionFrame
  | SseFrontendToolRequestFrame
  | SseFrontendToolResultFrame
  | SseSuspendedFrame
  | SseHeartbeatFrame
  | SseRetryFrame
  | SseDoneFrame
  | SseErrorFrame

/** 终帧判定：只有 done / error 是终帧（缺终帧 = 断流，按 ADR-6 提示）。 */
export function isTerminalFrame(frame: SseFrame): frame is SseDoneFrame | SseErrorFrame {
  return frame.type === 'done' || frame.type === 'error'
}

/** 运行时校验：把任意 JSON 值收窄为 SseFrame（type 命中后端白名单或前向兼容帧）。 */
export function toSseFrame(value: unknown): SseFrame | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const candidate = value as { type?: unknown }
  if (typeof candidate.type !== 'string') {
    return null
  }
  if (candidate.type === REASONING_FRAME_TYPE) {
    return value as SseReasoningFrame
  }
  return (SseFrameTypes as readonly string[]).includes(candidate.type) ? (value as SseFrame) : null
}

/**
 * 帧序号的读取（DC-14 T6）：缺字段/非数字一律返回 null（老帧按"照收"处理，不参与孤儿过滤）。
 */
export function frameSeq(frame: SseFrame): number | null {
  const candidate = frame as { seq?: unknown }
  return typeof candidate.seq === 'number' && Number.isFinite(candidate.seq) ? candidate.seq : null
}

// ── 任务级事件通道（GET /api/tasks/{id}/events，形态与 AI 帧不同） ──────────────

/**
 * 任务级帧类型（TaskSseService：大写 type + 包一层 data）。
 *
 * `JOB_PROGRESS` 由各作业执行器在分片边界发布
 * （ExportJobRunner/ImportJobRunner/PrecheckJobRunner/PublishJobRunner#publishProgress），
 * 载荷是**行级** processed/total + defCode + 百分比，是向导"SSE 进度"的数据来源。
 */
export type TaskEventType = 'HEARTBEAT' | 'JOB_PROGRESS' | 'JOB_DONE'

/** JOB_DONE 的 data（JobService#cancel 等发布点）。 */
export interface JobDonePayload {
  jobId: number
  status: string
}

/** JOB_PROGRESS 的 data（作业执行器 publishProgress）。 */
export interface JobProgressPayload {
  jobId: number
  jobType: string
  /** 完成一个配置项时带出的配置编码 */
  defCode: string
  processed: number
  total: number
  pct: number
}

/** 任务级 SSE 帧。 */
export interface TaskEventFrame {
  type: TaskEventType
  data: JobProgressPayload | JobDonePayload | Record<string, never>
}
