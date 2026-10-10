/**
 * 前端工具执行器（规格 §2「契约窄接口」/ §3 前端工具回灌）。
 *
 * **后端口径核对（本棒实测，勿凭蓝本臆造）**：main 后端 `AiTools` 共 18 个 `@Tool`，
 * 其中带 `@ToolChannel(FRONTEND)` 的**有 7 个**：`open_export_file_editor`、`download_export_file`、
 * `navigate_to`、`select_definitions`、`set_condition`、`confirm_step`、`generative_form`
 * （其余 11 个是 BACKEND 通道，由后端自己执行；`start_export/start_precheck/start_import/start_publish`
 * 在 main 里都是 BACKEND，不再像旧基座那样挂起等前端）。所以：
 * - 路由 1 覆盖 `FRONTEND_TOOLS` 登记的前端工具（2 个：`open_export_file_editor` / `download_export_file`），
 *   完整实现；
 * - 路由 2（兜底直调 `dispatchUiEvent`，与路由 3 是**同一落点**，并非"落回路由 3"）是**补丁②**：把蓝本形态的工作区动作（`navigate_to` /
 *   `select_definitions` / `set_condition` / `confirm_step` 及蓝本别名）做成可执行能力表 ——
 *   这 4 个后端已标为 FRONTEND 并披露，故今天经任何 `frontend_tool_request` 帧或契约层驱动
 *   即可生效，前端零改动（见 `WORKSPACE_ACTIONS`）；
 * - `generative_form` 前端执行接入已落地（GF-B）：`stores/ai.ts` 对 `frontend_tool_request` 特判 +
 *   AiPanel 渲染 `FormRenderer`，**不走本文件通用执行器**。
 *
 * 四条路由（AI 经 frontend_tool 帧驱动工作区的统一入口；编号与 `dispatchFrontendTool`
 * 内的分支注释一致，实读该方法确认共 4 条）：
 * 1. **后端披露的前端工具**（`FRONTEND_TOOLS`）：参数校验 + @ToolScope 校验 + 执行器/默认执行器；
 * 2. **工作区动作表**（`WORKSPACE_ACTIONS`：navigate_to / select_definitions / set_condition /
 *    confirm_step；别名经 `WORKSPACE_ACTION_ALIASES` 归一：select_config_defs /
 *    set_query_conditions / goto_step / advance_step / restore_task / open_page）：
 *    先找页面能力 handler，再落契约层 `dispatchUiEvent`（与用户点击**同一套** action）；
 * 3. **ui_event 同名动作**（`select_defs` / `set_conditions` / `goto_step` / `open_page` /
 *    `download` / `run_job` …）：直接经契约层分发；
 * 4. **页面能力 handler**（页面经 `registerPageHandler` 反向暴露，名字即 handler 名；
 *    该页未登记时本分支走不到）。
 *
 * 三级可用性兜底（kimi 蓝本实践，TS 化）：
 * - available：默认执行器、页面 handler 或契约层动作可直接完成；
 * - degraded：能力当前不可用（页面未注册 handler），返回说明文本让模型改走别的路径，
 *   **不静默失败**，也不把异常抛回模型；
 * - unavailable：工具名未知 / 参数非法 / 当前上下文不该出现该工具（明确说明原因）。
 *
 * 统一超时预算（issue#6 根治面）：本文件是**回灌的唯一前置**（`useAiStore#runFrontendTool`
 * 必须等 `executeFrontendTool` 返回才发 `POST /api/ai/frontend-tool-result`），故这里是
 * "回灌不被无限拖住"的唯一可加缝点。`executeFrontendTool` 因此对**所有**路由统一加一层
 * 预算（{@link FRONTEND_TOOL_TIMEOUT_BUDGET_MS}，默认 60s = 后端挂起上限 120s − 回灌 POST 上限 60s）：
 * 预算到期即产出"结局未知"的结构化失败结局，回灌 POST 随之发出（**异步挂起类**不再迟到 /
 * 丢失；**同步阻塞主线程类**的边界见 `runWithBudget` 的说明）。
 */

import {
  UI_EVENT_TYPES,
  findFrontendTool,
  type FrontendToolDescriptor,
  type FrontendToolExecution,
  type FrontendToolName,
  type UiEvent,
  type WorkspacePageId
} from '@/types/tools'
import { dispatchUiEvent, getPageHandler, useWorkspaceStore } from '@/stores/workspace'

/** 前端工具执行上下文（args 已解析为对象）。 */
export interface FrontendToolCall {
  name: string
  toolCallId?: string
  args: Record<string, unknown>
  /**
   * 本次执行的自有超时预算（毫秒）；缺省用 {@link FRONTEND_TOOL_TIMEOUT_BUDGET_MS}。
   * 调用方（`useAiStore#runFrontendTool`）由帧里的**服务端**挂起上限折算：
   * `deriveFrontendToolBudgetMs(frame.timeoutSeconds)` —— 服务端改配置时预算自动跟随。
   */
  budgetMs?: number
}

/** 执行器签名：返回回灌给模型的结果文本。 */
export type FrontendToolExecutor = (call: FrontendToolCall) => Promise<string> | string

/** 页面 handler 名：打开导出文件在线编辑器（由导出向导页面注册）。 */
export const PAGE_HANDLER_OPEN_EXPORT_EDITOR = 'export.openEditor'

const executors = new Map<string, FrontendToolExecutor>()

/** 注册/覆盖某个前端工具的执行器。 */
export function registerFrontendToolExecutor(name: FrontendToolName | string, executor: FrontendToolExecutor): void {
  executors.set(name, executor)
}

/** 注销执行器（页面卸载时调用，回到"能力不可用"态）。 */
export function unregisterFrontendToolExecutor(name: FrontendToolName | string): void {
  executors.delete(name)
}

/** @ToolScope 标签匹配（'*' / 'page:tasks' / 'task:EXPORT' / 'task:EXPORT/EXPORT' / 'task:*'）。
 *  page:* 的合法值域 = 路由页 id 镜像（tasks / export / import / definitions / data）；
 *  `page:export` / `page:import` 为保留粒度，当前无任何工具使用（向导页披露由 taskType+step 承担）。 */
export function matchesScope(patterns: readonly string[], scope: { page: string; taskType: string | null; step: string | null }): boolean {
  const page = scope.page
  const taskType = scope.taskType ?? ''
  const step = scope.step ?? ''
  return patterns.some((pattern) => {
    if (pattern === '*') {
      return true
    }
    if (pattern.startsWith('page:')) {
      return pattern.slice(5) === page
    }
    if (pattern.startsWith('task:')) {
      const rest = pattern.slice(5)
      if (rest === '*') {
        return taskType !== ''
      }
      const [patternType, patternStep] = rest.split('/')
      if (!patternType || patternType !== taskType) {
        return false
      }
      return patternStep === undefined || patternStep === step
    }
    return false
  })
}

/** 参数校验：必填缺失或类型不符即视为不可用（返回原因）。 */
function validateArgs(
  descriptor: FrontendToolDescriptor,
  args: Record<string, unknown>
): { ok: true; coerced: Record<string, unknown> } | { ok: false; reason: string } {
  const coerced: Record<string, unknown> = { ...args }
  for (const spec of descriptor.args) {
    const value = args[spec.name]
    if (value === undefined || value === null || value === '') {
      if (spec.required) {
        return { ok: false, reason: `缺少必填参数 ${spec.name}（${spec.description}）` }
      }
      continue
    }
    if (spec.type === 'number') {
      const num = typeof value === 'number' ? value : Number(value)
      if (!Number.isFinite(num)) {
        return { ok: false, reason: `参数 ${spec.name} 应为数字，实际为 ${String(value)}` }
      }
      coerced[spec.name] = num
    } else if (spec.type === 'string') {
      coerced[spec.name] = String(value)
    } else if (typeof value !== 'boolean') {
      return { ok: false, reason: `参数 ${spec.name} 应为布尔值` }
    }
  }
  return { ok: true, coerced }
}

/** 解析后端帧里的 args（JSON 字符串）。 */
export function parseToolArgs(raw: string | undefined | null): Record<string, unknown> {
  if (!raw) {
    return {}
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    return {}
  } catch {
    return {}
  }
}

/**
 * 前端工具执行器的统一超时预算缺省值（毫秒，默认 **60s**）—— 帧里没带合法 `timeoutSeconds`
 * 时的回落值，也是"服务端上限 120s − 回灌 POST 上限 60s"这一折算在默认配置下的取值
 * （折算见 {@link deriveFrontendToolBudgetMs}）。
 *
 * **为什么要有**：后端对前端工具挂起的等待上限是 `app.ai.hitl.timeout`（默认 **120s**，
 * 与确认门共用，见 backend `AiProperties$Hitl#timeout`），前端此前**没有任何自有预算**：
 * 执行器一旦迟迟不返回（宿主浏览器的下载链路把页面拖住、或 `ensureTaskBoundForTool`
 * 串行 HTTP 叠加吃满 axios 60s 上限），回灌 POST 就发不出去，后端在 120s 判前端超时，
 * 模型与用户拿到的是"本次调用未获得数据"这种既非成功也非失败的空结局。
 * 实测（issue#6，Kimi Code 自带浏览器）：回灌 POST 两轮 **>120s 未发出**（结局帧 120,004 /
 * 120,007 ms），同一链路在自动化 Chromium 上仅 25~41 ms。
 *
 * **为什么是"服务端上限 − 60s"**（S2-2，取代旧的"−30s 余量"论证）：
 * - **硬约束来自回灌 POST 自身**：预算到期后必须先发 `POST /api/ai/frontend-tool-result`
 *   （走 `api/http.ts` 的 axios 实例，**单请求上限 60s** —— `http.ts:85` `timeout: 60000`），
 *   后端再受理与判定。故"回灌必在服务端判超时前到达"的数学保证是
 *   `预算 + 60000 ≤ 服务端上限`，即 `预算 ≤ 服务端上限 − 60000`；默认 120s 上限即 **60s**。
 *   旧的 30s 余量只在"POST 耗时 <30s"时成立（POST 拖到 31~60s 就会迟到、被幂等 409 拒），
 *   现在按 POST 的最坏耗时取值，不再依赖"实测正常 <1s"的外推。
 * - **下界**：不能太小 —— 本预算不得把"慢但会成功"的执行掐掉。实测最慢的一次**成功**
 *   回灌发生在 **30.6s**（issue#6 run3：下载被宿主浏览器拖住 30.1s 后才解析），
 *   60s 覆盖这类慢成功，同时把 issue 那两轮失败从 120s 提前到 60s 收口。
 * - **与服务端同源**：帧带 `timeoutSeconds`/`expiresAt`，调用方经
 *   {@link deriveFrontendToolBudgetMs} 由服务端值折算；本常量即"上限缺失"时的回落值
 *   （折算结果本身不再是常量，而是随服务端上限平移，见 {@link deriveFrontendToolBudgetMs}）。
 *
 * **已知迟到源（S4-2，登记不修）**：Chrome 后台页的计时器节流（隐藏页 1min 粒度）会让本预算
 * **晚于**标称时刻触发；后台 tab 下回灌仍可能落在服务端 120s 判定之后，呈"迟到但不丢"
 * （409 幂等拒绝 + notice 提示 + `elapsedMs` 如实）。方向安全（不误杀活连接），登记备查。
 */
export const FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 60_000

/**
 * 回灌 POST 的最坏耗时（毫秒）= 预算必须让出的余量。
 *
 * 取值 = `api/http.ts` 的 axios 单请求上限（60s，`http.ts:85`）—— 回灌 POST 自己最多能花
 * 这么久，不是"经验留白"。折算式见 {@link deriveFrontendToolBudgetMs}：
 * `预算 = min(max(服务端上限 − 本余量, 5s 下限), 服务端上限)`。
 */
export const FRONTEND_TOOL_RESULT_HEADROOM_MS = 60_000

/**
 * 折算预算的下限（毫秒）：服务端上限被配得过小时，仍给执行器一点起码的完成时间。
 *
 * 与 {@link deriveFrontendToolBudgetMs} 的上限钳制冲突时以**上限为准**：服务端上限本身
 * ≤ 本值时（病态配置），预算取服务端上限而非本值 —— 宁可无余量，也不倒挂。
 */
const MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 5_000

/**
 * 由服务端帧的挂起上限折算出执行器预算（S2-2 严格化后的唯一折算式）：
 * `min(max(服务端上限 − {@link FRONTEND_TOOL_RESULT_HEADROOM_MS}, MIN), 服务端上限)`；
 * 上限缺失 / 非法 / 溢出时回落 {@link FRONTEND_TOOL_TIMEOUT_BUDGET_MS}。
 *
 * 默认 120s 上限 → **60s**；90s → 30s；65s → 5s（≈上限，余量被压到 0）。
 *
 * 为什么以服务端值为准而不是写死 120s：帧里的 `timeoutSeconds` 就是后端真实等待上限
 * （`app.ai.hitl.timeout`，可在配置里改）。以后端值为基算出预算，后端调整上限时前端无需
 * 同步改常量；余量取回灌 POST 自身的 axios 上限（60s），于是
 * `预算 + 60000 ≤ 服务端上限` 对 `服务端上限 ≥ 65s` 的一切取值成立 —— 这才是注释里可自称
 * "保证"的数学口径（旧式 `服务端上限 − 30s` 只在 POST <30s 时成立，见 S2-2）。
 * >120s 的上限不再被常量封顶（150s → 90s、300s → 240s），保证式随服务端值平移。
 *
 * 两道钳制（既有逻辑，取值随 S2-2 平移）：
 * - **下限** `MIN`（5s）防"预算过短"：`服务端上限 − 60s ≤ 5s` 的配置（≤65s）由它兜底
 *   （上限 ≤60s 时 `usableMs` 已 ≤0，同落此下限、再被上界收口）；
 * - **上界** `timeoutSeconds × 1000` 防"预算倒挂"：上限 ≤5s 时下限会反超上限
 *   （`timeoutSeconds=3` → 5000ms > 3000ms），故以服务端上限收口；两者冲突时**上限优先**。
 * - 病态区（上限 ≤65s，即下限生效区）于是落在"预算 == 服务端上限、回灌余量为 0"（≤5s）或
 *   "预算 == 5s 下限、余量不足 60s"：此处上述数学保证必然失效，只能退化为"到点即回灌"。
 *   上限 ≤65s 属**病态配置**（实践中服务端默认 120s），该分支只是"不倒挂"的兜底：到点即回灌，
 *   仍比"什么都不发、坐等后端判超时"多一次如实回执。
 */
export function deriveFrontendToolBudgetMs(timeoutSeconds?: number | null): number {
  const serverBudgetMs = typeof timeoutSeconds === 'number' && Number.isFinite(timeoutSeconds) && timeoutSeconds > 0
    ? timeoutSeconds * 1000
    : null
  // 溢出防线：`timeoutSeconds` 有限但 ×1000 溢出为 Infinity（如 1e307）时同样判非法 ——
  // 否则预算 = Infinity，`setTimeout(Infinity)` 被引擎钳到 ~1ms，等于**瞬间判超时（假超时）**。
  if (serverBudgetMs === null || !Number.isFinite(serverBudgetMs)) {
    return FRONTEND_TOOL_TIMEOUT_BUDGET_MS
  }
  // 折算 = 服务端上限 − 回灌 POST 最坏耗时（60s）：先夹 5s 下限，再以服务端上限收口防倒挂。
  const usableMs = serverBudgetMs - FRONTEND_TOOL_RESULT_HEADROOM_MS
  const clampedMs = Math.max(MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS, usableMs)
  return Math.min(clampedMs, serverBudgetMs)
}

/**
 * 计时器 delay 的可表示上限（毫秒）= `2^31 − 1` ≈ 24.86 天。
 *
 * **来源（引擎约束，不是经验值）**：`setTimeout` 的 `delay` 实参被转换为**32 位有符号整数**，
 * 超过本值即整数溢出。MDN `Window.setTimeout` 的 "Maximum delay value" 一节写明
 * *the delay argument is converted to a signed 32-bit integer, which limits the value to
 * 2147483647 ms, or roughly 24.8 days. Delays of more than this value will cause an integer
 * overflow*，并给出 `2 ** 32 - 5000` **立即执行**（溢出为负数）、`2 ** 32 + 5000` 约 5 秒后
 * 执行的例子；同页另注 *In Node.js, any timeout larger than 2,147,483,647 ms results in
 * immediate execution*。本机实测（Node v22，与浏览器同属 V8 计时器实现）：
 * `setTimeout(fn, 2 ** 31)` 与 `setTimeout(fn, 1e10)` 均报
 * `TimeoutOverflowWarning ... Timeout duration was set to 1`，回调 **3ms 内**即触发 ——
 * 即"越界 delay 退化成秒级假超时"。
 *
 * **为什么本预算会越过它**：预算随服务端上限平移（{@link deriveFrontendToolBudgetMs}：
 * 150s → 90s、300s → 240s，不再被默认常量封顶），而服务端上限来自 `app.ai.hitl.timeout`
 * （`Duration`，取值上界受帧里的 int 秒约束 ≈ 68 年），故病态配置下预算会 > 本值。
 * 把越界值直接喂给 `setTimeout`，"病态地长"的预算就变成**瞬间假超时**——正是本预算最不该
 * 有的误差方向（宁可永不超时，也不能把活执行器谎报成超时）。
 *
 * **钳制口径**：计时器是 `runWithBudget` 里**唯一**的超时判定源（race 只有"执行器 settle"
 * 与"计时器到点"两支，见其文档），故在 {@link normalizeBudgetMs} 一次性收口 —— 收口后的值
 * 既是计时器 delay，也是超时文案里报的"执行器预算"（同源，不会出现
 * "已等待 N 秒 < 执行器预算 M 秒却判超时"的自相矛盾）。
 *
 * **诚实的退化登记**：预算 > 24.86 天属**病态配置**（后端默认 120s，与该值差 5 个数量级）：
 * 该场景的超时判定实际退化为**本次会话内不超时**，由服务端上限判定兜底 —— 属**可接受退化**，
 * 远优于"秒级假超时"。合法域（预算 ≤ 24.86 天，含默认 60s 与一切可配置取值）不受影响。
 */
const TIMER_MAX_DELAY_MS = 2_147_483_647

/**
 * 预算归一：非法值一律回落默认值（预算不允许被调用方"关掉"），并把上界收口到
 * {@link TIMER_MAX_DELAY_MS}（`setTimeout` 可表示的 delay 上限）。
 *
 * 两道处理放在同一处，因为本返回值**就是**喂给 `setTimeout` 的 delay
 * （`executeFrontendTool` → `runWithBudget`）：越界值会被引擎折返成 ~1ms 的 delay
 * → 瞬间假超时（证据与退化说明见 {@link TIMER_MAX_DELAY_MS}）；而计时器是本层唯一超时
 * 判定源，故收口后的值**就是**有效预算，超时文案亦报该值。
 */
function normalizeBudgetMs(value?: number): number {
  const requestedMs = typeof value === 'number' && Number.isFinite(value) && value > 0
    ? value
    : FRONTEND_TOOL_TIMEOUT_BUDGET_MS
  return Math.min(requestedMs, TIMER_MAX_DELAY_MS)
}

/**
 * 预算包装的结局（内部判别联合，不对外暴露）：`settled` = 执行器给了结局；
 * `timeout` = 预算先到（`elapsedMs` 是**实际**等待时长，可能远大于预算，见下）。
 */
type BudgetOutcome =
  | { kind: 'settled'; execution: FrontendToolExecution }
  | { kind: 'timeout'; elapsedMs: number }

/**
 * 执行前端工具（三级兜底 + 四条路由 + 统一超时预算）。
 *
 * 返回的 `result` 文本会被原样回灌给模型（POST /api/ai/frontend-tool-result），
 * 因此失败时也要给**可读、可行动**的说明，而不是抛异常。
 *
 * 预算只包**本层**、不包 `generative_form`：后者的挂起等的是**用户填写**（`stores/ai.ts`
 * 的 `openGenerativeForm` 特判，自己按帧时限收敛，不进本函数），因此不存在"用户还在填表、
 * 前端预算先把工具判成超时"的风险。
 */
export async function executeFrontendTool(call: FrontendToolCall): Promise<FrontendToolExecution> {
  const budgetMs = normalizeBudgetMs(call.budgetMs)
  const startedAt = Date.now()
  const outcome = await runWithBudget(dispatchFrontendTool(call), budgetMs, startedAt)
  return outcome.kind === 'timeout'
    ? timeoutOutcome(call, budgetMs, outcome.elapsedMs)
    : outcome.execution
}

/**
 * 预算包装：执行器结局 vs 计时器，先到者胜。
 *
 * **硬保证（异步挂起类）**：执行器只要不 settle（promise 永不落地、串行 HTTP 叠加等），
 * 计时器到点即产出超时结局 → `executeFrontendTool` 返回 → 回灌 POST 发出。
 *
 * **本层做不到的事（诚实边界）**：计时器是宏任务，**无法打断同步阻塞主线程的执行器**。
 * 阻塞结束后的两条分支（node 侧逻辑验证脚本 §⑧a/⑧b 实证）：
 * - 执行器随即给出结局 → 微任务先于"已过期的宏任务"，按**真实结局**回灌（慢成功仍报成功：
 *   不把已有确定结局的执行谎报成超时）；此时回灌时刻 = 阻塞结束时刻，若"阻塞结束 + 回灌
 *   POST"仍 ≤ 服务端 120s 上限，回灌赶得及被后端受理；
 * - 执行器仍未给出结局 → 过期计时器**立刻补触发**，回灌超时结局（不丢结局、不悬挂）。
 * 若阻塞本身超过后端 120s 上限（issue#6 两轮实测即此形态），则当次回灌必然迟到 ——
 * 页面内任何计时器都做不到更早（该限制与证据见施工报告"遗留风险"）。此时
 * `timeout.elapsedMs` 会明显大于预算，正是"执行器内主线程被长时间阻塞"的信号，
 * 也被如实写进超时文案的"已等待 N 秒"。
 *
 * 超时分支**只依赖计时器**：不依赖执行器 settle，也不依赖它 reject —— 这对
 * `download_export_file` 是必需的（`ExportWizardView#downloadOne` 把自身异常吞进
 * `ElMessage` 后 void 返回、从不 rethrow，所以"等它 reject"永远不会发生）。
 *
 * **计时器 delay 的上界**：`budgetMs` 已由 {@link normalizeBudgetMs} 收口在
 * {@link TIMER_MAX_DELAY_MS}（`2^31 − 1` ms ≈ 24.86 天）—— `setTimeout` 的 delay 按 32 位
 * 有符号整型处理，越界值会被折返成 ~1ms 的 delay，把病态长预算变成**瞬间假超时**。计时器是
 * 本层唯一超时判定源，故收口后的值**就是**有效预算，也随超时结局用于文案。
 */
async function runWithBudget(
  task: Promise<FrontendToolExecution>,
  budgetMs: number,
  startedAt: number
): Promise<BudgetOutcome> {
  let timer: ReturnType<typeof setTimeout> | null = null
  const expired = new Promise<BudgetOutcome>((resolve) => {
    // budgetMs 已收口在 TIMER_MAX_DELAY_MS 内（见该常量与 normalizeBudgetMs）；越界 delay
    // 会被引擎折返成 ~1ms，等于把"病态长预算"变成瞬间假超时。
    timer = setTimeout(() => resolve({ kind: 'timeout', elapsedMs: Date.now() - startedAt }), budgetMs)
  })
  try {
    // dispatchFrontendTool 按契约不 reject（每条路由内部都把异常翻成结局文本：见
    // runRegisteredTool / runPageHandler 的 try-catch、dispatchUiEvent → runUiEvent 的
    // try-catch、emitWorkspaceEvent 逐订阅者 try-catch），故 race 的败者不会因 reject
    // 变成未处理拒绝。
    return await Promise.race([
      task.then((execution): BudgetOutcome => ({ kind: 'settled', execution })),
      expired
    ])
  } finally {
    if (timer !== null) {
      clearTimeout(timer)
    }
  }
}

/**
 * "下载类"前端工具名 —— 超时文案要按结局区分：下载是本包里唯一"副作用可能已发生、
 * 只是回执没回来"的动作，措辞必须与确定性失败分开（否则模型会去重复触发下载）。
 *
 * 名单对应两条**实际可达**的路由命名面：登记工具 `download_export_file`（路由 1）与
 * ui_event 同名动作 `download`（路由 3）。
 *
 * `download_export_files` 是蓝本（旧基座）名，当前**未登记**于任何路由：既不在
 * `FRONTEND_TOOLS`，也不在 `WORKSPACE_ACTION_ALIASES`（该表只归一 select_config_defs /
 * set_query_conditions / goto_step / advance_step / restore_task / open_page）。故它送进来会
 * 立刻落到"未知前端工具"的 unavailable 结局、预算不介入，**达不成超时文案**；保留是为了
 * 防御性的口径一致 —— 若该蓝本名日后被登记（或经别的通道到达并触发超时），仍按下载类收口。
 */
const DOWNLOAD_TOOL_NAMES: readonly string[] = ['download_export_file', 'download_export_files', 'download']

/**
 * 超时结局文案（结局驱动，中文，**不谎报**）：说明"结局未知"，并按工具类型给出可行动指引。
 *
 * - 下载类：如实说"可能仍在后台继续 / 请到下载目录确认"（实测确有"拖了 30.6s 仍成功落盘"
 *   的轮次），并明确要求**不要重复触发下载**；
 * - 其余工具：如实说"结局未知"，请用户核对界面实际状态，不替用户判定成功或失败。
 *
 * 与"确定性失败"（执行器/ handler 抛错 → `执行失败：<原因>。请提示用户手动重试`）的差别在
 * 于：那条有明确原因、可判定未完成；本条只说明**没有拿到回执**。
 *
 * 注意（吞异常的现状）：`downloadOne` 吞掉自身异常 ⇒ 该执行器**从不 reject**，"下载失败却
 * 回灌成功"是同一处的另一个面（谎报成功，P1），不在本预算职责内 —— 本函数只负责"没拿到
 * 回执"这一种结局。
 */
function timeoutOutcome(call: FrontendToolCall, budgetMs: number, elapsedMs: number): FrontendToolExecution {
  const label = toolDisplayName(call.name)
  // 两个秒数都写进文案：`waitedSeconds` 是**实际**等待（正常情况下 ≈ 预算；明显大于预算时，
  // 说明执行器内主线程被长时间阻塞，见 runWithBudget 的分支说明），`budgetSeconds` 是预算本身。
  const waitedSeconds = Math.round(elapsedMs / 1000)
  const budgetSeconds = Math.round(budgetMs / 1000)
  const result = DOWNLOAD_TOOL_NAMES.includes(call.name)
    ? `${label}（${call.name}）前端超时：等待 ${waitedSeconds} 秒仍未收到浏览器的完成回执（执行器预算 ${budgetSeconds} 秒）。`
      + '该操作可能仍在后台继续，文件也许已经下载到浏览器的下载目录。'
      + '请让用户先到下载目录确认文件是否已落盘，再决定是否需要重试；不要直接重复触发下载。'
      + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
    : `${label}（${call.name}）前端超时：等待 ${waitedSeconds} 秒仍未返回执行结果（执行器预算 ${budgetSeconds} 秒）。`
      + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
      + '请提示用户核对界面上的实际状态（相关卡片、列表或表格，以及数据是否已变化），确认后再决定是否重试。'
  return { ok: false, availability: 'degraded', result }
}

/** 工具中文名（回灌文案用）：登记工具 / 工作区动作表优先，未知工具回落原始名。 */
function toolDisplayName(name: string): string {
  const descriptor = findFrontendTool(name)
  if (descriptor) {
    return descriptor.displayName
  }
  const action = findWorkspaceAction(name)
  return action ? action.displayName : name
}

/** 四条路由的统一派发（不含预算；预算包在 {@link executeFrontendTool} 外层）。 */
async function dispatchFrontendTool(call: FrontendToolCall): Promise<FrontendToolExecution> {
  const descriptor = findFrontendTool(call.name)
  if (descriptor) {
    return await runRegisteredTool(descriptor, call)
  }
  // 路由 2：工作区动作表（补丁②：导航 / 选配置 / 填条件 / 推进步骤 + 蓝本别名）
  const action = findWorkspaceAction(call.name)
  if (action) {
    return await runWorkspaceAction(action, call.args)
  }
  // 路由 3：ui_event 同名动作（AI 经契约层驱动工作区：导航/选配置/填条件/触发下载）
  if (isUiEventType(call.name)) {
    return await runUiEventAction(call.name, call.args)
  }
  // 路由 4：页面能力 handler（页面经 registerPageHandler 反向暴露，名字即 handler 名）
  const handler = getPageHandler(call.name)
  if (handler) {
    return await runPageHandler(call.name, handler, call.args)
  }
  return {
    ok: false,
    availability: 'unavailable',
    result: `未知前端工具 ${call.name}：该工具没有浏览器侧执行器，请改用后端工具完成。`
  }
}

// ── 路由 2：工作区动作表（补丁②） ────────────────────────────────────────────────

/**
 * 一个工作区动作的描述。
 *
 * `allowPages` / `allowSteps` 是**第三道防线**（三级兜底的 unavailable 分支）：
 * 当前上下文不在允许集合内时，返回明确说明而不是硬做。
 */
interface WorkspaceAction {
  /** 工具名（帧里来的名字，与蓝本 / 后端口径对齐） */
  name: string
  /** 中文动作名（回灌文案用） */
  displayName: string
  /** 允许的页面 id（workspace.pageId） */
  allowPages: readonly WorkspacePageId[]
  /** 允许的步骤 key（空数组=不限；与后端 @ToolScope 的 step 口径一致） */
  allowSteps: readonly string[]
  /** 需要绑定任务（导出/导入向导） */
  needsTask?: boolean
  /** 页面能力 handler 名（按优先级；页面挂载时才存在） */
  handlerNames: readonly string[]
  /** 无 handler 时的契约层后备动作（与用户点击同一套 action） */
  toUiEvent?: (args: Record<string, unknown>) => UiEvent | null
  /**
   * 本动作会把界面导航到哪个页面（F3：这类动作执行后要等目标页面就绪再回灌结果，
   * 否则同一轮里紧随其后的动作会打在"页面还没挂载"的窗口上）。
   */
  navigatesTo?: (args: Record<string, unknown>) => WorkspacePageId | null
  /**
   * 本动作要求绑定哪个任务（issue #1）：非 null 时，完成判据从"页面就绪"升级为
   * "页面就绪 **且** `workspace.taskId` 已绑定为该值"，否则紧随的 `select_definitions`
   * 之类的动作会打在"页面在、任务还没绑上"的窗口上。
   */
  bindsTask?: (args: Record<string, unknown>) => number | null
}

/**
 * 页面就绪探针（F3）：目标页面挂载后会注册的能力 handler 名。
 *
 * 判断"页面就绪"的可靠口径不是"路由变了"（router.push 返回时组件可能还没 mount），
 * 而是**页面自己登记能力之后**（`registerPageHandler` 发生在 onMounted 里）——
 * 有 handler 就说明该页的交互能力（含后续动作要用的 handler）已经就位。
 */
const PAGE_READY_PROBES: Record<WorkspacePageId, readonly string[]> = {
  tasks: [],
  export: ['export.nextStep'],
  import: ['import.nextStep'],
  definitions: [],
  data: []
}

/** 页面就绪等待上限（毫秒）：超过即如实告知"仍在加载"，不无限等。 */
export const PAGE_READY_TIMEOUT_MS = 3_000

/**
 * 等待目标页面就绪（F3，参照 kimi-k3 蓝本"导航后等页面可用再动作"的实践）。
 *
 * 竞态现场（S4.4d/f 实测）：同一轮里模型先 `navigate_to(export, taskId)` 再
 * `select_definitions` —— 前者只是 `router.push`，目标组件要等异步路由解析 + onMounted
 * 才注册 handler；后者若在那之前执行，会落到"页面不支持该动作"的 degraded 分支，
 * 模型据此误判"这条路走不通"（而界面上明明可以）。
 *
 * @returns true = 目标页面已就绪；false = 超时（调用方据此在回灌文本里如实说明）
 */
export async function waitForPageReady(page: WorkspacePageId, timeoutMs = PAGE_READY_TIMEOUT_MS): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  const probes = PAGE_READY_PROBES[page]
  for (;;) {
    const workspace = useWorkspaceStore()
    const ready = workspace.pageId === page
      && (probes.length === 0 || probes.some((name) => getPageHandler(name) !== null))
    if (ready) {
      return true
    }
    if (Date.now() >= deadline) {
      return false
    }
    await delay(50)
  }
}

/** "页面就绪 + 任务绑定"两个维度的等待结局（两维分开返回，供调用方给出不同的回灌文案）。 */
export interface TaskBindWaitResult {
  /** 目标页面已就绪：页面 id 匹配且该页能力 handler 已注册 */
  pageReady: boolean
  /** 期望任务已绑定：workspace.taskId === expectedTaskId */
  taskBound: boolean
}

/**
 * 等待"目标页面就绪 **且** 任务已绑定"（issue #1，S4.4 实测复现）。
 *
 * 为什么 `waitForPageReady` 不够：它的判据只有"页面 id + 能力 handler"，
 * 而 `navigate_to(page, taskId)` 经 `restore_task` **只改路由 query**。目标向导页
 * 已挂载时（同一路由记录下组件不重建、onMounted 不再执行），handler 早就注册着，
 * 于是 waitForPageReady 立刻返回 true，`workspace.taskId` 却仍是 null ——
 * 紧随其后的 `select_definitions` 被 needsTask 判成"当前没有进行中的任务"（实测 100% 复现）。
 *
 * 超时口径与 `waitForPageReady` 一致（默认 {@link PAGE_READY_TIMEOUT_MS}，轮询 50ms），
 * **不无限等**：到点即如实返回两维现状，由调用方决定降级文案。
 */
export async function waitForTaskBound(
  page: WorkspacePageId,
  expectedTaskId: number,
  timeoutMs = PAGE_READY_TIMEOUT_MS
): Promise<TaskBindWaitResult> {
  const deadline = Date.now() + timeoutMs
  const probes = PAGE_READY_PROBES[page]
  for (;;) {
    const workspace = useWorkspaceStore()
    const pageReady = workspace.pageId === page
      && (probes.length === 0 || probes.some((name) => getPageHandler(name) !== null))
    const taskBound = workspace.taskId === expectedTaskId
    if (pageReady && taskBound) {
      return { pageReady: true, taskBound: true }
    }
    if (Date.now() >= deadline) {
      return { pageReady, taskBound }
    }
    await delay(50)
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 工作区动作表。
 *
 * 名字来源（**核对过后端口径**）：
 * - 裁决②点名的 4 个：`navigate_to` / `select_definitions` / `set_condition` / `confirm_step`；
 * - 蓝本 `frontend-tools.js:5-15` 的等价名（`select_config_defs` / `set_query_conditions`）
 *   与 main 既有的 ui_event 名（`select_defs` / `set_conditions` / `goto_step` / `open_page`）
 *   作为**别名**一并登记，避免因命名差异漏路由。
 * 后端已披露 7 个 FRONTEND 工具，上述 4 个均在其中，故这些动作今天经「前端工具帧」或
 * 「契约层」触发；执行器与页面能力已就绪，后续工具接入时前端零改动。
 */
export const WORKSPACE_ACTIONS: readonly WorkspaceAction[] = [
  {
    name: 'navigate_to',
    displayName: '页面导航',
    allowPages: ['tasks', 'export', 'import', 'definitions', 'data'],
    allowSteps: [],
    handlerNames: [],
    toUiEvent: (args) => {
      const intent = resolveNavigateIntent(args)
      if (!intent) {
        return null
      }
      return intent.taskId === null
        ? { type: 'open_page', page: intent.page }
        : { type: 'restore_task', taskId: intent.taskId }
    },
    navigatesTo: (args) => resolveNavigateIntent(args)?.page ?? null,
    bindsTask: (args) => resolveNavigateIntent(args)?.taskId ?? null
  },
  {
    name: 'select_definitions',
    displayName: '选择配置项',
    allowPages: ['export', 'import'],
    allowSteps: ['SELECT_DEFS', 'UPLOAD'],
    needsTask: true,
    handlerNames: ['export.selectDefs', 'import.selectDefs'],
    toUiEvent: (args) => {
      const codes = toStringList(args.codes ?? args.defCodes)
      if (codes.length === 0) {
        return null
      }
      const task = args.task === 'import' ? 'import' : 'export'
      return { type: 'select_defs', task, defCodes: codes }
    }
  },
  {
    name: 'set_condition',
    displayName: '设置查询条件',
    allowPages: ['export'],
    allowSteps: ['QUERY_COND'],
    needsTask: true,
    handlerNames: ['export.setConditions'],
    toUiEvent: (args) => {
      const defCode = typeof args.defCode === 'string' ? args.defCode : ''
      return defCode === '' ? null : { type: 'set_conditions', defCode, conditions: args.conditions ?? {} }
    }
  },
  {
    name: 'confirm_step',
    displayName: '推进向导步骤',
    allowPages: ['export', 'import'],
    allowSteps: [],
    needsTask: true,
    handlerNames: ['export.nextStep', 'import.nextStep'],
    toUiEvent: (args) => {
      const step = typeof args.step === 'string' ? args.step : ''
      return step === '' ? null : { type: 'goto_step', step }
    }
  }
] as const

/**
 * `navigate_to` 的意图解析（`toUiEvent` / `navigatesTo` / `bindsTask` 三处共用同一份判定，
 * 避免"事件映射"与"完成判据"两份判断漂移）。
 *
 * 带 taskId 且目标是向导页 ⇒ 恢复该任务的向导（`restore_task`，会绑定任务）；
 * 否则只是切页（`open_page`，不碰任务绑定）。
 */
function resolveNavigateIntent(args: Record<string, unknown>): { page: WorkspacePageId; taskId: number | null } | null {
  const page = typeof args.page === 'string' ? args.page.toLowerCase() : ''
  if (!isWorkspacePageId(page)) {
    return null
  }
  const taskId = toNumber(args.taskId)
  return { page, taskId: taskId !== null && (page === 'export' || page === 'import') ? taskId : null }
}

/** 蓝本名 / ui_event 名 → 动作名（别名表）。 */
const WORKSPACE_ACTION_ALIASES: Readonly<Record<string, string>> = {
  select_config_defs: 'select_definitions',
  set_query_conditions: 'set_condition',
  goto_step: 'confirm_step',
  advance_step: 'confirm_step',
  restore_task: 'navigate_to',
  open_page: 'navigate_to'
}

function findWorkspaceAction(name: string): WorkspaceAction | null {
  const target = WORKSPACE_ACTION_ALIASES[name] ?? name
  return WORKSPACE_ACTIONS.find((action) => action.name === target) ?? null
}

/** 动作执行：可用性（unavailable）→ 页面 handler（available）→ 契约层（available/degraded）。 */
async function runWorkspaceAction(
  action: WorkspaceAction,
  args: Record<string, unknown>
): Promise<FrontendToolExecution> {
  const workspace = useWorkspaceStore()
  const context = workspace.snapshot()
  if (!action.allowPages.includes(context.pageId)) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `当前页面为${context.pageId}，${action.displayName}只支持：${action.allowPages.join('、')}。请先导航到目标页面再执行。`
    }
  }
  if (action.allowSteps.length > 0 && !action.allowSteps.includes(context.step ?? '')) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `当前步骤为${context.step ?? '未进入向导'}，${action.displayName}只支持：${action.allowSteps.join('、')}。请先推进到可用步骤。`
    }
  }
  if (action.needsTask && context.taskId === null) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `${action.displayName}需要绑定任务：当前没有进行中的任务，请先在任务中心创建或打开一条任务。`
    }
  }

  for (const handlerName of action.handlerNames) {
    const handler = getPageHandler(handlerName)
    if (handler) {
      return await runPageHandler(handlerName, handler, normalizeActionArgs(action, args), action.displayName)
    }
  }

  const event = action.toUiEvent?.(args) ?? null
  if (!event) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `${action.displayName}的参数不完整：${JSON.stringify(args)}`
    }
  }
  const outcome = await dispatchUiEvent(event)
  if (outcome.handled) {
    // F3：导航类动作等目标页面就绪再回灌（否则同一轮里紧随的动作会打在页面未挂载的窗口上）
    const target = action.navigatesTo?.(args) ?? null
    if (target !== null) {
      // issue #1：带 taskId 的导航还要等**任务绑定落地**再回灌 —— 只等"页面就绪"会谎报成功
      // （向导页已挂载时 handler 早在，workspace.taskId 却还是 null）。
      const expectedTaskId = action.bindsTask?.(args) ?? null
      const wait = expectedTaskId === null
        ? { pageReady: await waitForPageReady(target), taskBound: true }
        : await waitForTaskBound(target, expectedTaskId)
      if (wait.pageReady && wait.taskBound) {
        const bound = expectedTaskId === null ? '' : `，任务 #${expectedTaskId} 已绑定`
        return {
          ok: true,
          availability: 'available',
          result: `${outcome.message}（页面已就绪${bound}，可继续执行本页动作）`
        }
      }
      if (wait.pageReady) {
        // 页面已就绪却仍没绑定该任务 ⇒ 不是"还在加载"，再等也不会变：明确降级，不谎报成功
        return {
          ok: false,
          availability: 'degraded',
          result: `${outcome.message}，但任务 #${expectedTaskId} 的绑定没有完成（向导页已就绪，未绑定该任务）。`
            + `请核对任务 #${expectedTaskId} 是否存在，或提示用户手动在任务中心打开该任务后重试。`
        }
      }
      return {
        ok: true,
        availability: 'available',
        result: `${outcome.message}（页面${expectedTaskId === null ? '' : '与任务绑定'}仍在加载中；`
          + '若要继续在该页动作，请稍后重试或提示用户手动操作）'
      }
    }
    return { ok: true, availability: 'available', result: outcome.message }
  }
  // 契约层拒绝（缺任务/缺 handler）→ degraded：给出可行动说明，不静默
  return {
    ok: false,
    availability: 'degraded',
    result: `${action.displayName}未完成（${outcome.message}）。请提示用户在界面上手动完成该动作。`
  }
}

/** 动作 args → 页面 handler 载荷（把别名参数归一，handler 只需认识一套字段名）。 */
function normalizeActionArgs(action: WorkspaceAction, args: Record<string, unknown>): Record<string, unknown> {
  switch (action.name) {
    case 'select_definitions':
      return { defCodes: toStringList(args.codes ?? args.defCodes), mode: args.mode === 'ADD' ? 'ADD' : 'REPLACE' }
    case 'set_condition':
      return { defCode: args.defCode ?? '', conditions: args.conditions ?? {} }
    case 'confirm_step':
      return { step: args.step ?? '' }
    default:
      return args
  }
}

function toNumber(value: unknown): number | null {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

function toStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)).filter((item) => item !== '') : []
}

/** 路由 1：登记在册的前端工具（参数校验 + scope 校验 + 执行器）。 */
async function runRegisteredTool(
  descriptor: FrontendToolDescriptor,
  call: FrontendToolCall
): Promise<FrontendToolExecution> {
  const validated = validateArgs(descriptor, call.args)
  if (!validated.ok) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `参数不合法，${descriptor.displayName}未执行：${validated.reason}`
    }
  }

  const workspace = useWorkspaceStore()
  const context = workspace.snapshot()
  if (!matchesScope(descriptor.scopePatterns, { page: context.page, taskType: context.taskType, step: context.step })) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `当前上下文（页面=${context.page}，步骤=${context.step ?? '未进入向导'}）不支持${descriptor.displayName}。`
    }
  }

  const executor = executors.get(descriptor.name) ?? defaultExecutor(descriptor.name)
  if (!executor) {
    return {
      ok: false,
      availability: 'degraded',
      result: `${descriptor.displayName}当前不可用（本页未注册该能力）。请改用其他方式：由用户手动操作，或使用后端工具完成任务。`
    }
  }

  try {
    const result = await executor({ ...call, args: validated.coerced })
    return { ok: true, availability: 'available', result }
  } catch (error) {
    return {
      ok: false,
      availability: 'degraded',
      result: `${descriptor.displayName}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
    }
  }
}

/** 路由 3：ui_event 同名动作 → 契约层统一分发（与用户点击同一套 action）。 */
async function runUiEventAction(name: UiEvent['type'], args: Record<string, unknown>): Promise<FrontendToolExecution> {
  const event = toUiEvent(name, args)
  if (!event) {
    return {
      ok: false,
      availability: 'unavailable',
      result: `${name} 的参数不完整或类型不符，未驱动工作区：${JSON.stringify(args)}`
    }
  }
  try {
    const outcome = await dispatchUiEvent(event)
    return { ok: outcome.handled, availability: 'available', result: outcome.message }
  } catch (error) {
    return {
      ok: false,
      availability: 'degraded',
      result: `工作区动作 ${name} 执行失败：${error instanceof Error ? error.message : String(error)}`
    }
  }
}

/** 路由 4：页面 handler（页面未注册时该分支不会被走到）。 */
async function runPageHandler(
  name: string,
  handler: (payload: Record<string, unknown>) => unknown | Promise<unknown>,
  args: Record<string, unknown>,
  displayName?: string
): Promise<FrontendToolExecution> {
  const label = displayName ?? name
  try {
    const outcome = await handler(args)
    return {
      ok: true,
      availability: 'available',
      result: typeof outcome === 'string' ? outcome : `${label}已执行`
    }
  } catch (error) {
    return {
      ok: false,
      availability: 'degraded',
      result: `${label}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
    }
  }
}

function isUiEventType(name: string): name is UiEvent['type'] {
  return (UI_EVENT_TYPES as readonly string[]).includes(name)
}

/** 宽松 args → 判别联合 ui_event（必填字段缺失/类型不符时返回 null，不猜）。 */
function toUiEvent(name: UiEvent['type'], args: Record<string, unknown>): UiEvent | null {
  const num = (value: unknown): number | null => {
    const parsed = typeof value === 'number' ? value : Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  switch (name) {
    case 'open_page': {
      const page = args.page
      return typeof page === 'string' && isWorkspacePageId(page)
        ? { type: 'open_page', page }
        : null
    }
    case 'restore_task': {
      const taskId = num(args.taskId)
      return taskId === null ? null : { type: 'restore_task', taskId }
    }
    case 'goto_step': {
      const step = args.step
      return typeof step === 'string' && step !== '' ? { type: 'goto_step', step } : null
    }
    case 'select_defs': {
      const codes = args.defCodes
      const task = args.task === 'import' ? 'import' : 'export'
      return Array.isArray(codes)
        ? { type: 'select_defs', task, defCodes: codes.map((item) => String(item)) }
        : null
    }
    case 'set_conditions': {
      const defCode = args.defCode
      return typeof defCode === 'string' && defCode !== ''
        ? { type: 'set_conditions', defCode, conditions: args.conditions ?? {} }
        : null
    }
    case 'set_import_mode': {
      const mode = args.mode
      return mode === 'MERGE' || mode === 'REPLACE' ? { type: 'set_import_mode', mode } : null
    }
    case 'run_job': {
      const taskId = num(args.taskId)
      const jobType = args.jobType
      const types = ['EXPORT', 'PRECHECK', 'IMPORT', 'PUBLISH']
      return taskId !== null && typeof jobType === 'string' && types.includes(jobType)
        ? { type: 'run_job', taskId, jobType: jobType as 'EXPORT' | 'PRECHECK' | 'IMPORT' | 'PUBLISH' }
        : null
    }
    case 'cancel_job': {
      const jobId = num(args.jobId)
      return jobId === null ? null : { type: 'cancel_job', jobId }
    }
    case 'download': {
      const url = args.url
      if (typeof url !== 'string' || url === '') {
        return null
      }
      return typeof args.filename === 'string'
        ? { type: 'download', url, filename: args.filename }
        : { type: 'download', url }
    }
    case 'refresh_defs':
      return { type: 'refresh_defs' }
    case 'refresh_data':
      return typeof args.defCode === 'string'
        ? { type: 'refresh_data', defCode: args.defCode }
        : { type: 'refresh_data' }
    case 'refresh_tasks':
      return { type: 'refresh_tasks' }
    default:
      return null
  }
}

/** 页面 id 白名单判定（`open_page` 用）。 */
function isWorkspacePageId(page: string): page is WorkspacePageId {
  return (PAGE_IDS as readonly string[]).includes(page)
}

const PAGE_IDS: readonly WorkspacePageId[] = ['tasks', 'export', 'import', 'definitions', 'data']

/** 契约层自带的默认执行器（页面未覆盖时使用）。 */
function defaultExecutor(name: FrontendToolName): FrontendToolExecutor | null {
  if (name === 'open_export_file_editor') {
    return async (call) => {
      const handler = getPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR)
      if (!handler) {
        throw new Error('导出向导未注册在线编辑器能力')
      }
      const outcome = await handler({ taskId: call.args.taskId, defCode: call.args.defCode })
      return typeof outcome === 'string' ? outcome : `已请求打开 ${String(call.args.defCode)} 的在线编辑器`
    }
  }
  return null
}
