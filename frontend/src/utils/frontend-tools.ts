/**
 * 前端工具执行器（规格 §2「契约窄接口」/ §3 前端工具回灌）。
 *
 * **后端口径核对（批B 表格读侧补齐后复核，勿凭蓝本臆造）**：后端 `AiTools` 共 34 个 `@Tool`，
 * 其中带 `@ToolChannel(FRONTEND)` 的**有 23 个**：`open_export_file_editor`、`download_export_files`、
 * `navigate_to`、`select_definitions`、`set_condition`、`confirm_step`、`generative_form`，
 * 加上 AI 表格操作专项的 9 个表格工具（读 5 / 写 3 + 撤销 1；`normalize_column` 双档同名）。
 * 所以：
 * - 路由 1 覆盖 `FRONTEND_TOOLS` 登记的前端工具（18 个：2 个既有 + 9 个表格工具 + 7 个界面原语），
 *   完整实现（表格读工具与撤销经 `sheet.current` 页面能力取适配器；写工具由 store 预览特判）；
 * - 路由 2（兜底直调 `dispatchUiEvent`，与路由 3 是**同一落点**，并非"落回路由 3"）是**补丁②**：把蓝本形态的工作区动作（`navigate_to` /
 *   `select_definitions` / `set_condition` / `confirm_step` 及蓝本别名）做成可执行能力表 ——
 *   这 4 个后端已标为 FRONTEND 并披露，故今天经任何 `frontend_tool_request` 帧或契约层驱动
 *   即可生效，前端零改动（见 `WORKSPACE_ACTIONS`）；
 * - `generative_form` 前端执行接入已落地（GF-B）：`stores/ai.ts` 对 `frontend_tool_request` 特判 +
 *   AiPanel 渲染 `FormRenderer`，**不走本文件通用执行器**；
 * - 表格写工具（`replace_values` / `update_rows` / `normalize_column(commit=true)`）同族：
 *   store 特判进预览卡（等用户“应用/取消”），**不走**本文件的 60s 预算 —— 那预算包不住“等人”。
 * - **AI 可视可操作专项（2026-10-21）**：`FRONTEND_TOOLS` 10 → 17 条，后端 `@Tool` 26 → 33
 *   （前端通道 15 → 22）。新增的 7 个通用原语（`ui_snapshot` / `ui_click` / `ui_fill` /
 *   `ui_select` / `ui_scroll` / `ui_highlight` / `ui_request_upload`）走**路由 1 + 默认执行器**，
 *   执行体在 `utils/ai-dom-tools.ts`；其中 `ui_request_upload` 是「等人」工具，由 `stores/ai.ts`
 *   特判进交接卡（与 GF 同族），**同样不走** 60s 预算。
 *   `ui_*` 的 scope 为 `ui:on`（批 A：按区域存在性披露；缺失即披露，显式 0 才不披露）：
 *   真正的围栏仍在**区域**（`registerAiRegion`）与**元素**（`data-ai-*`）两层。
 * - **AI 表格操作专项 · 批B（读侧补齐，2026-10-22）**：`FRONTEND_TOOLS` 17 → 18，后端 `@Tool` 33 → 34
 *   （前端通道 22 → 23）。新增读工具 `find_rows`（全列检索：命中行号 + 单元格地址 + ≤20 行采样），
 *   与其余读工具同 scope、走**路由 1 + 默认执行器**；`aggregate_sheet` 增加分组排序（Top-N）。
 * - **批次 5B（Task 8）**：`restore_task` 前置特判（{@link runRestoreTask}：按**服务端**任务类型
 *   恢复向导，导入任务不跳导出）；`navigate_to` 的执行体抽成 {@link runNavigateTo}
 *   （带 taskId 时同样以服务端类型为准 —— `ensureTaskType` 缓存优先、未命中拉一次 GET，
 *   查不到就**不导航**；导航成功前等"页面挂载 + 任务绑定 + 任务详情已应用"三者齐备，
 *   路由 3 的导航类事件同理）。
 *
 * 四条路由（AI 经 frontend_tool 帧驱动工作区的统一入口；编号与 `dispatchFrontendTool`
 * 内的分支注释一致，实读该方法确认共 4 条）：
 * 1. **后端披露的前端工具**（`FRONTEND_TOOLS`）：参数校验 + @ToolScope 校验 + 执行器/默认执行器；
 * 2. **工作区动作表**（`WORKSPACE_ACTIONS`：navigate_to / select_definitions / set_condition /
 *    confirm_step；别名经 `WORKSPACE_ACTION_ALIASES` 归一：select_config_defs /
 *    set_query_conditions / goto_step / advance_step / restore_task / open_page）：
 *    先找页面能力 handler，再落契约层 `dispatchUiEvent`（与用户点击**同一套** action）；
 *    其中 navigate_to 走 {@link runNavigateTo}，`restore_task` 更早一步被前置特判接走；
 * 3. **ui_event 同名动作**（`select_defs` / `set_conditions` / `goto_step` / `open_page` /
 *    `download` / `run_job` …）：直接经契约层分发（导航类事件同样等页面就绪 + 任务绑定 + 详情应用）；
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

import { HTTP_TIMEOUT_MS } from '@/utils/time-constants'
import {
  SHEET_TOOL_NAMES,
  SHEET_WRITE_TOOLS,
  UI_EVENT_TYPES,
  UI_TOOL_NAMES,
  findFrontendTool,
  type FrontendToolDescriptor,
  type FrontendToolExecution,
  type FrontendToolName,
  type FrontendToolOutcome,
  type ToolAvailability,
  type UiEvent,
  type WorkspacePageId
} from '@/types/tools'
import {
  aiClick,
  aiFill,
  aiHighlight,
  aiScroll,
  aiSelect,
  aiSnapshot,
  currentUiDomVersion,
  type UiToolOutcome
} from '@/utils/ai-dom-tools'
import { dispatchUiEvent, getPageHandler, useWorkspaceStore, type UiEventLifetime } from '@/stores/workspace'
import { useTaskStore } from '@/stores/task'
import { currentAiEpoch, listAiRegions } from '@/utils/ai-regions'
import { formatUiReceipt, sanitizeUiCode } from '@/utils/ui-receipt'
import type { ConfigField } from '@/types/definition'
import type { TaskType } from '@/types/task'
import {
  aggregateSheet,
  findRows,
  inspectColumns,
  normalizeSuggest,
  readSheetSummary,
  reviewCells,
  sheetReadOnlyWriteBlocked,
  sheetContentVersionOf,
  type SheetAdapter,
  type SheetContext,
  type SheetToolOutcome
} from '@/utils/sheet-tools'

/** 前端工具执行上下文（args 已解析为对象）。 */
export interface FrontendToolCall {
  name: string
  toolCallId?: string
  /**
   * 所属轮次的 runId（批 A：同轮同参去重的作用域）。
   * 缺失（旧调用方 / 单测直调）⇒ 不去重（宁可多执行一次，也不跨轮误杀）。
   */
  runId?: string
  args: Record<string, unknown>
  /**
   * 本次执行的自有超时预算（毫秒）；缺省用 {@link FRONTEND_TOOL_TIMEOUT_BUDGET_MS}。
   * 调用方（`useAiStore#runFrontendTool`）由帧里的**服务端**挂起上限折算：
   * `deriveFrontendToolBudgetMs(frame.timeoutSeconds)` —— 服务端改配置时预算自动跟随。
   */
  budgetMs?: number
}

/**
 * 执行器签名：返回回灌给模型的结果文本，或结构化的 {@link FrontendToolExecution}
 * （批3③a A4：执行器想如实报失败不必再 throw —— 可直接返回结构化结局，由
 * runRegisteredTool 按结构化解读；字符串返回值保持"成功 + 该文本"的兼容解读）。
 *
 * <p>第二参数是本次执行的存活标记（见 {@link UiEventLifetime}）：预算到点 / 用户停止后
 * 已作废 ⇒ 执行器在 await 之后不得再写工作区。旧执行器忽略它即可（行为不变）。
 */
export type FrontendToolExecutor = (
  call: FrontendToolCall,
  lifetime?: UiEventLifetime
) => Promise<string | FrontendToolExecution> | string | FrontendToolExecution

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

/**
 * `FrontendToolExecution` 的**唯一生产工厂**（批3③a D1 终裁）：`ok` 由 `outcome` 单点推导
 * （`ok === (outcome === 'ok')`，故 "结局未知" 恒 `ok:false`），一致性断言随工厂单点化 ——
 * 不得在任何路由里手写 `{ ok, availability, result }` 字面量（T8 锁工厂行为）。
 *
 * 为什么必须收口而不是逐点手写：21 处生产点若各自维护 ok/outcome 两份真源，任一点漂移都会
 * 让卡面与回灌文本自相矛盾（本包要根治的正是这类谎报）。
 */
export function makeExecution(input: {
  outcome: FrontendToolOutcome
  availability: ToolAvailability
  result: string
  reason?: string
  code?: string
  sheetMeta?: FrontendToolExecution['sheetMeta']
}): FrontendToolExecution {
  const execution: FrontendToolExecution = {
    ok: input.outcome === 'ok',
    availability: input.availability,
    outcome: input.outcome,
    result: input.result
  }
  const code = sanitizeUiCode(input.code)
  if (code !== null) {
    execution.code = code
  }
  if (input.reason !== undefined) {
    execution.reason = input.reason
  }
  if (input.sheetMeta !== undefined) {
    execution.sheetMeta = input.sheetMeta
  }
  return execution
}

/** @ToolScope 标签匹配（'*' / 'page:tasks' / 'task:EXPORT' / 'task:EXPORT/EXPORT' / 'task:*'）。
 *  page:* 的合法值域 = 路由页 id 镜像（tasks / export / import / definitions / data）；
 *  `page:export` / `page:import` 为保留粒度，当前无任何工具使用（向导页披露由 taskType+step 承担）。 */
export function matchesScope(
  patterns: readonly string[],
  scope: { page: string; taskType: string | null; step: string | null; uiRegionCount?: number | null }
): boolean {
  const page = scope.page
  const taskType = scope.taskType ?? ''
  const step = scope.step ?? ''
  return patterns.some((pattern) => {
    if (pattern === '*') {
      return true
    }
    if (pattern === 'ui:on') {
      // 批 A：按区域存在性披露。缺失（旧客户端 / E2E 桩）⇒ 披露（fail-open）；
      // 显式 0 ⇒ 不披露。该标签只用于收窄，永远不能用来授权。
      return scope.uiRegionCount === undefined || scope.uiRegionCount === null || scope.uiRegionCount > 0
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
    } else if (spec.type === 'object') {
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        return { ok: false, reason: `参数 ${spec.name} 应为对象，实际为 ${String(value)}` }
      }
      coerced[spec.name] = value
    } else if (spec.type === 'object[]') {
      if (!Array.isArray(value) || value.some((item) => !item || typeof item !== 'object' || Array.isArray(item))) {
        return { ok: false, reason: `参数 ${spec.name} 应为对象数组` }
      }
      coerced[spec.name] = value
    } else if (spec.type === 'string[]') {
      // 列表参数（后端 `String[]`）：必填的语义是**必填非空** —— 空数组等于没给，
      // 缺参不猜即失败；顺带滤掉空串项（后端 `[]` 与 `[""]` 都不是合法的编码列表）。
      if (!Array.isArray(value)) {
        return { ok: false, reason: `参数 ${spec.name} 应为字符串数组，实际为 ${String(value)}` }
      }
      const list = value.map((item) => String(item)).filter((item) => item !== '')
      if (spec.required && list.length === 0) {
        return { ok: false, reason: `参数 ${spec.name} 不能为空（${spec.description}）` }
      }
      coerced[spec.name] = list
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
 * **为什么要有**：后端对前端工具挂起的等待上限是 `app.ai.hitl.frontend-tool-timeout`
 * （默认 **120s**，裁决③拆键后**只供前端工具**，见 backend
 * `AiProperties$Hitl#frontendToolTimeout`；确认门已去超时、无对应键），前端此前**没有任何自有预算**：
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
 * 取值 = `utils/time-constants.ts` 的 {@link HTTP_TIMEOUT_MS}（axios 单请求上限 60s）——
 * 回灌 POST 自己最多能花这么久，不是"经验留白"。<b>代码同源</b>（超时面治理批① 第 4 项）：
 * 此前这里是字面量 `60_000`，只在注释里与 `api/http.ts` 对齐；改 axios 上限而忘了改这里，
 * 折算式 `预算 + 余量 ≤ 服务端上限` 会静默失真。折算式见 {@link deriveFrontendToolBudgetMs}：
 * `预算 = min(max(服务端上限 − 本余量, 5s 下限), 服务端上限)`。
 */
export const FRONTEND_TOOL_RESULT_HEADROOM_MS = HTTP_TIMEOUT_MS

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
 * （`app.ai.hitl.frontend-tool-timeout`，可在配置里改）。以后端值为基算出预算，后端调整上限时前端无需
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
 * 150s → 90s、300s → 240s，不再被默认常量封顶），而服务端上限来自 `app.ai.hitl.frontend-tool-timeout`
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
  // 执行存活标记：预算到点即作废 —— 被抛弃的续体此后不得再写工作区（见 UiEventLifetime）
  const lifetime: UiEventLifetime = { abandoned: false }
  liveLifetimes.add(lifetime)
  try {
    const outcome = await runWithBudget(dispatchFrontendTool(call, lifetime), budgetMs, startedAt, lifetime)
    return outcome.kind === 'timeout'
      ? timeoutOutcome(call, budgetMs, outcome.elapsedMs)
      : outcome.execution
  } finally {
    liveLifetimes.delete(lifetime)
  }
}

/**
 * 在途的前端工具存活标记（`executeFrontendTool` 进入时登记、结算时注销）。
 *
 * <p>为什么要一张表：预算到点由 `runWithBudget` 自己置位，但**用户点「停止」**是另一条
 * 触发面 —— 停止发生在 `executeFrontendTool` 之外（`stores/ai.ts#stop`），没有这张表就没有
 * 任何途径把"本轮已放弃"告诉正在 await 的执行器/页面 handler。
 */
const liveLifetimes = new Set<UiEventLifetime>()

/**
 * 作废**所有在途**前端工具（用户停止本轮时调用）。
 *
 * <p>语义 = "本轮已被放弃，此后不得再写工作区/步骤/选中集"：标记一置，被抛弃的续体在
 * 下一次 await 之后读到的就是 `abandoned === true`（页面 handler / 登记执行器 / 契约层
 * `dispatchUiEvent` 三处同源判据）。已发出的 HTTP 请求无法撤回；回执文案仍由各 handler
 * 如实表述"已提交但未写入当前工作区"。
 */
export function abandonInFlightFrontendTools(): void {
  for (const lifetime of liveLifetimes) {
    lifetime.abandoned = true
  }
  liveLifetimes.clear()
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
 * 超时分支**只依赖计时器**：不依赖执行器 settle，也不依赖它 reject。这条性质在批 A
 * （W-01 即发即回）之后依然是必需的：`download_export_files` 的执行器只等前置校验
 * （`ExportWizardView#ensureTaskBoundForTool` 的串行 HTTP 可以很长），而"等它 settle"
 * 或"等它 reject"都不能当作超时判定的依据 —— 计时器到点即产出超时结局，回灌才不会迟到。
 *
 * **计时器 delay 的上界**：`budgetMs` 已由 {@link normalizeBudgetMs} 收口在
 * {@link TIMER_MAX_DELAY_MS}（`2^31 − 1` ms ≈ 24.86 天）—— `setTimeout` 的 delay 按 32 位
 * 有符号整型处理，越界值会被折返成 ~1ms 的 delay，把病态长预算变成**瞬间假超时**。计时器是
 * 本层唯一超时判定源，故收口后的值**就是**有效预算，也随超时结局用于文案。
 *
 * **到点后的副作用闸（lifetime）**：`Promise.race` 只决定"先回灌谁"，**不会**打断败者 ——
 * 被抛弃的续体仍会跑完它已经发起的 await 链。到点即把 `lifetime.abandoned` 置 true：
 * 续体里的工作区写入口（`dispatchUiEvent` 的 `touch` / `setStep` 等）据此跳过，
 * 使"已经回灌超时的动作"不会在下一次 await 之后又变成一次成功/一次 chip 同步。
 * 到点前已经发出的 HTTP 请求无法撤回（本层没有、也不新造 abort 协议），回执文案已如实用
 * "结局未知 / 请核对实际状态"表述。
 */
async function runWithBudget(
  task: Promise<FrontendToolExecution>,
  budgetMs: number,
  startedAt: number,
  lifetime: UiEventLifetime
): Promise<BudgetOutcome> {
  let timer: ReturnType<typeof setTimeout> | null = null
  const expired = new Promise<BudgetOutcome>((resolve) => {
    // budgetMs 已收口在 TIMER_MAX_DELAY_MS 内（见该常量与 normalizeBudgetMs）；越界 delay
    // 会被引擎折返成 ~1ms，等于把"病态长预算"变成瞬间假超时。
    timer = setTimeout(() => {
      lifetime.abandoned = true
      resolve({ kind: 'timeout', elapsedMs: Date.now() - startedAt })
    }, budgetMs)
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
 * 名单对应两条**实际可达**的路由命名面：登记工具 `download_export_files`（路由 1）与
 * ui_event 同名动作 `download`（路由 3）。
 *
 * R-156 换名（`download_export_file` → `download_export_files`，1 码 xlsx / ≥2 码 zip 单工具）
 * 后，**旧名已从本名单移除**：它不再登记于 `FRONTEND_TOOLS`，也不在 `WORKSPACE_ACTION_ALIASES`
 * （该表只归一 select_config_defs / set_query_conditions / goto_step / advance_step /
 * restore_task / open_page），送进来会立刻落到"未知前端工具"的 unavailable 结局、预算不介入，
 * **达不成超时文案** —— 保留旧名只会让"工具面唯一"的硬保证在文案层留一条不可能命中的分支。
 */
const DOWNLOAD_TOOL_NAMES: readonly string[] = ['download_export_files', 'download']

/**
 * 下载类前端工具的**显式前置等待上限**（毫秒）。
 *
 * <p>L-82（真机 E2E 批② VL 域）：`download_export_files` 在**编辑器未预热**时"前置校验"里的
 * 串行 HTTP（`ensureTaskBoundForTool` 的任务加载 / 结果清单）可能长时间不落地 —— 真机实测
 * >90s 既无失败也无成功，回执悬空（模型与用户都拿不到结局，卡面一直"执行中"）。
 *
 * <p>与外层统一预算（{@link FRONTEND_TOOL_TIMEOUT_BUDGET_MS}，默认 60s）的分工：
 * 外层是**兜底**（任何前端工具都受它保护）；本常量是下载工具**自己**的收口点 ——
 * 取 30s（外层默认 60s 的一半），使"前置卡住"能在预算内先给一个**确定性**结局，
 * 还留出另一半时间给"前置通过后真正发起下载"。两者冲突时取先到者（外层仍是最终兜底）。
 */
export const DOWNLOAD_PREFLIGHT_TIMEOUT_MS = 30_000

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
 * 注意（批 A 后的口径）：`downloadOne` 曾吞掉自身异常 ⇒ 该执行器**从不 reject**、"下载失败却
 * 回灌成功"（W-02）是同一处的另一个面。批 A 已改：前置校验失败即 reject（确定性失败、卡片
 * failed），浏览器侧结局不再回灌（`download_export_files` 即发即回）—— 故"谎报成功"这一面
 * 不再存在；本函数只负责"没拿到回执"这一种结局。
 * **已知过度保守（登记不修）**：即发即回后，该工具的超时只可能落在**前置校验**期间（尚未发起
 * 下载），故下方下载类文案的"文件也许已落盘、请勿重复触发"偏保守 —— 方向安全（不谎报、
 * 不诱导重复触发），是否收窄留待卡片态设计（R-84）时一并裁决。
 */
function timeoutOutcome(call: FrontendToolCall, budgetMs: number, elapsedMs: number): FrontendToolExecution {
  const label = toolDisplayName(call.name)
  const waitedSeconds = Math.round(elapsedMs / 1000)
  const budgetSeconds = Math.round(budgetMs / 1000)
  if (DOWNLOAD_TOOL_NAMES.includes(call.name)) {
    // 下载类（副作用可能已发生、只是回执没回来）：`unknown` 白名单的唯一保留者。
    const result = `${label}（${call.name}）前端超时：等待 ${waitedSeconds} 秒仍未收到浏览器的完成回执（执行器预算 ${budgetSeconds} 秒）。`
      + '该操作可能仍在后台继续，文件也许已经下载到浏览器的下载目录。'
      + '请让用户先到下载目录确认文件是否已落盘，再决定是否需要重试；请勿重复触发下载。'
      + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
    return makeExecution({ outcome: 'unknown', availability: 'degraded', code: 'UI_BUDGET_EXHAUSTED', result })
  }
  // 非下载类：预算到期 = 确定性收口（timeout），不再糊成「结局未知」。
  const result = formatUiReceipt({
    tool: `${label}（${call.name}）`,
    conclusion: `执行超时，本次未完成（等待 ${waitedSeconds} 秒，执行器预算 ${budgetSeconds} 秒）`,
    reason: '执行器在预算内没有返回结果（页面可能卡住，或浏览器链路未响应）',
    next: '提示用户核对界面上的实际状态后再决定是否重试；不要在同一轮里连续重试同一调用'
  })
  return makeExecution({ outcome: 'timeout', availability: 'degraded', code: 'UI_BUDGET_EXHAUSTED', result })
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
async function dispatchFrontendTool(
  call: FrontendToolCall,
  lifetime: UiEventLifetime
): Promise<FrontendToolExecution> {
  // 槽位在**执行前**取定：键里的 epoch 就是本次执行观察到的现场（`ui_snapshot` 遍历的正是这份
  // DOM）。执行期间现场若又推进（如 `navigate_to` 触发目标页挂载 → 区域注册推 epoch），
  // 结果仍归旧现场，不会被记到新现场名下（见 rememberSameCall）。
  const slot = dedupeSlotOf(call)
  const previous = previousSameCall(slot)
  if (previous) {
    return makeExecution({
      outcome: previous.outcome,
      availability: previous.availability,
      code: previous.code,
      result: `本轮已执行过同参调用 ${call.name}（未再次执行）。\n${previous.result}`
    })
  }
  const execution = await dispatchFrontendToolInner(call, lifetime)
  // 预算已到点：这条结局属于"已经回灌超时"的那次执行，不得记进去重表
  //（否则同一轮里同参重试会命中它、把超时后的晚到结局当成现成答案）。
  if (!lifetime.abandoned) {
    rememberSameCall(slot, execution)
  }
  return execution
}

// ── 同参去重（批 A / D4-②；第 7 项：绑定页面 epoch） ────────────────────────────

/**
 * 去重白名单：只读类（`ui_snapshot` / `ui_scroll` / `ui_highlight`）+ 导航（`navigate_to`）。
 * 写动词**不在**名单里 —— 同参写两次可能是用户的真实意图（如再点一次切换）。
 */
const DEDUPE_TOOL_NAMES: ReadonlySet<string> = new Set(['ui_snapshot', 'ui_scroll', 'ui_highlight', 'navigate_to'])

/**
 * 去重作用域 = `runId + 页面 epoch + 界面写入版本`；完整键 = 作用域 + 工具名 + 规范化 args。
 *
 * <h2>为什么 epoch 必须进键（第 7 项）</h2>
 * `ui_snapshot` 的结果是对**某一份 DOM 现场**的描述，而 ref 的生命周期由 epoch 定义：
 * 换页（`noteAiPage`）、区域注册/注销、显式 `bumpAiEpoch` 都会推进 `currentAiEpoch()`。
 * 键里不带 epoch 时，同一轮里换页后再取快照会命中旧条目 —— 模型拿回的是**旧 epoch 的快照**
 * （连头行的 epoch 都是旧的），据此操作旧 ref 必被元素围栏以「epoch 不匹配」拒执，白跑一轮；
 * 更糟的是它没有任何理由怀疑这份"成功"的快照已过期。
 *
 * <h2>为什么写入版本也要进键（FE-33 层②）</h2>
 * 这是与 `ai-dom-tools` 快照缓存**不同的第二层**：`ui_snapshot` 在同轮同 epoch 的同参调用
 * 直接返回上一次的回灌文本。AI 自己 `ui_fill` / `ui_click` 之后 epoch 不动，于是"动作后
 * 再拍一张确认"拿回的是**动作前**的画面 —— 模型判没生效，再点一次，勾选被点回原态。
 * `currentUiDomVersion()` 在每次真实写入（含撤销）与换轮时推进，写动作 ⇒ 新作用域 ⇒
 * 清表重拍；**没有写动作时仍去重**（既有能力不丢）。
 *
 * <h2>为什么作用域一变就整表清空</h2>
 * 旧作用域的条目按定义不可达（键里带着作用域），留着只是内存垃圾；清表也让
 * "换页 / 注册 / 注销 / 显式推进 / 写动作 ⇒ 新现场，同参也要重新遍历 DOM"这条语义在代码里可读。
 * 同作用域同参仍走去重（不因绑定现场而丢掉既有的去重能力）。
 */
let dedupeScope = ''
const dedupeCache = new Map<string, FrontendToolExecution>()

/** 一次去重的槽位：`scope` 用于判定现场是否换过，`key` 是完整去重键。 */
interface DedupeSlot {
  /** `runId|epoch|写入版本` */
  scope: string
  /** `runId|epoch|写入版本|工具名|规范化 args` */
  key: string
}

function dedupeSlotOf(call: FrontendToolCall): DedupeSlot | null {
  // 缺 runId（旧调用方 / 单测直调）⇒ 不去重（宁可多执行一次，也不跨轮误杀）。
  if (call.runId === undefined || call.runId === '' || !DEDUPE_TOOL_NAMES.has(call.name)) {
    return null
  }
  const scope = `${call.runId}|${currentAiEpoch()}|${currentUiDomVersion()}`
  return { scope, key: `${scope}|${call.name}|${stableArgsKey(call.args)}` }
}

/** args 的稳定序列化（键排序，避免同参不同书写顺序被当成两次调用）。 */
function stableArgsKey(args: Record<string, unknown>): string {
  const sorted: Record<string, unknown> = {}
  for (const key of Object.keys(args).sort()) {
    sorted[key] = args[key]
  }
  return JSON.stringify(sorted)
}

function previousSameCall(slot: DedupeSlot | null): FrontendToolExecution | null {
  if (slot === null) {
    return null
  }
  if (slot.scope !== dedupeScope) {
    dedupeScope = slot.scope
    dedupeCache.clear()
    return null
  }
  return dedupeCache.get(slot.key) ?? null
}

function rememberSameCall(slot: DedupeSlot | null, execution: FrontendToolExecution): void {
  if (slot === null) {
    return
  }
  // 执行期间现场已换过（epoch 推进 / 换轮）：本条结果描述的是旧现场，不记到新现场名下
  // —— 记下去就等于"新 epoch 有了旧现场的结果"，正是本项要根治的错配。
  if (slot.scope !== dedupeScope) {
    return
  }
  // 只缓存**可复用**的结局：确认成功（`ok`，含 `UI_ALREADY_ON_PAGE` 这类幂等直返）且能力可用。
  // 失败 / 超时 / 结局未知 / 降级一律不记 —— 记下去等于把一次失败毒化成"本轮已执行过"，
  // 同参重试会被这条失败条目挡回（模型据此判定"这条路走不通"，而现场可能早已可执行）。
  // 幂等与否由 outcome 表达，不按文案猜：`ok` 之外没有任何结局可以被同参复用。
  if (execution.outcome !== 'ok' || execution.availability !== 'available') {
    return
  }
  dedupeCache.set(slot.key, execution)
}

/** 四条路由的派发本体。 */
async function dispatchFrontendToolInner(
  call: FrontendToolCall,
  lifetime: UiEventLifetime
): Promise<FrontendToolExecution> {
  const descriptor = findFrontendTool(call.name)
  if (descriptor) {
    return await runRegisteredTool(descriptor, call, lifetime)
  }
  // 批次 5B（Task 8）：`restore_task` = **按服务端任务类型**恢复向导（入参只需 taskId）。
  // 旧实现经别名落到 `navigate_to`：缺 `page` 直接判"参数不完整"（模型手里的 `restore_task`
  // 因此根本不可达），而带上 page 又是拿列表缓存猜类型（缺失即 EXPORT 兜底）⇒ 导入任务可能被
  // 送进导出向导。两条都不再成立：类型先查权威来源，页面就绪再回灌。
  if (call.name === 'restore_task') {
    return await runRestoreTask(call.args, lifetime)
  }
  // 设计稿 §3.1 / §7-裁 7：`delete_rows` 留批③待裁，未上线时调用必须明确“不可用”，
  // 不能落到“未知前端工具”的模糊文案。
  if (call.name === 'delete_rows') {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: '当前不支持按条件删除行（该能力未上线）；可用 review_cells 查看重复/问题明细，或由用户手动删除。本次未做任何修改。'
    })
  }
  // 路由 2：工作区动作表（补丁②：导航 / 选配置 / 填条件 / 推进步骤 + 蓝本别名）
  const action = findWorkspaceAction(call.name)
  if (action) {
    return await runWorkspaceAction(action, call.args, lifetime)
  }
  // 路由 3：ui_event 同名动作（AI 经契约层驱动工作区：导航/选配置/填条件/触发下载）
  if (isUiEventType(call.name)) {
    return await runUiEventAction(call.name, call.args, lifetime)
  }
  // 路由 4：页面能力 handler（页面经 registerPageHandler 反向暴露，名字即 handler 名）
  const handler = getPageHandler(call.name)
  if (handler) {
    return await runPageHandler(call.name, handler, call.args, undefined, lifetime)
  }
  return makeExecution({
    outcome: 'failed',
    availability: 'unavailable',
    result: `未知前端工具 ${call.name}：该工具没有浏览器侧执行器，请改用后端工具完成。`
  })
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
export const PAGE_READY_TIMEOUT_MS = 8_000

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

/**
 * 任务详情就绪探针：页面把"详情已应用"登记成一个**只读** handler（返回布尔）。
 *
 * 为什么"页面挂载 + 任务绑定"还不够：`restore_task` / 同页绑定只同步写 `workspace.taskId`，
 * 而选中集 / 条件草案 / 步骤 / 状态要等 `loadTask` 把 `GET /tasks/{id}` 应用完才恢复。
 * 这个窗口里 `confirm_step` 一旦被驱动，就会用**空的 selectedDefs** 推进向导并落库
 * （"导出了全量、AI 说已按勾选"）。所以导航的成功判据必须含这一维。
 *
 * 只覆盖两个向导页：任务中心的"详情"就是列表行，其余页没有任务详情。
 */
const TASK_DETAIL_READY_PROBES: Record<WorkspacePageId, string | null> = {
  tasks: null,
  export: 'export.taskDetailReady',
  import: 'import.taskDetailReady',
  definitions: null,
  data: null
}

/**
 * 任务详情是否已应用（只读探针，不发请求）。
 *
 * 探针**缺席即视为该维度不适用**：页面没挂载时由 `pageReady` 维度兜住（本维度不替它判假），
 * 页面挂载时探针与 `PAGE_READY_PROBES` 在同一处 `registerHandlers()` 里注册，不会只缺一个。
 */
function isTaskDetailReady(page: WorkspacePageId): boolean {
  const name = TASK_DETAIL_READY_PROBES[page]
  if (name === null) {
    return true
  }
  const probe = getPageHandler(name)
  if (probe === null) {
    return true
  }
  try {
    return probe({}) === true
  } catch {
    return false
  }
}

/**
 * 等"任务详情已应用"（同页幂等早退用：页面没变、任务已绑，但详情可能才刚起飞）。
 *
 * @returns true = 详情已应用（或该维度不适用）；false = 等待上界内仍未应用
 */
export async function waitForTaskDetailApplied(
  page: WorkspacePageId,
  timeoutMs = PAGE_READY_TIMEOUT_MS
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    if (isTaskDetailReady(page)) {
      return true
    }
    if (Date.now() >= deadline) {
      return false
    }
    await delay(50)
  }
}

/** "页面就绪 + 任务绑定 + 详情已应用"三个维度的等待结局（分开返回，供调用方给出不同的回灌文案）。 */
export interface TaskBindWaitResult {
  /** 目标页面已就绪：页面 id 匹配且该页能力 handler 已注册 */
  pageReady: boolean
  /** 期望任务已绑定：workspace.taskId === expectedTaskId */
  taskBound: boolean
  /** 任务详情已应用：选中集 / 条件草案 / 步骤 / 状态已恢复并 `enterPage`（见 {@link isTaskDetailReady}） */
  detailReady: boolean
}

/**
 * 等待"目标页面就绪 **且** 任务已绑定 **且** 任务详情已应用"（issue #1，S4.4 实测复现）。
 *
 * 为什么 `waitForPageReady` 不够：它的判据只有"页面 id + 能力 handler"，
 * 而 `navigate_to(page, taskId)` 经 `restore_task` **只改路由 query**。目标向导页
 * 已挂载时（同一路由记录下组件不重建、onMounted 不再执行），handler 早就注册着，
 * 于是 waitForPageReady 立刻返回 true，`workspace.taskId` 却仍是 null ——
 * 紧随其后的 `select_definitions` 被 needsTask 判成"当前没有进行中的任务"（实测 100% 复现）。
 *
 * 为什么"任务已绑定"还不够：绑定是**同步**写 `workspace.taskId` 的，详情仍在路上 ——
 * 此时放行即让后续动作打在"空选中集 / 未恢复的条件"上（见 {@link TASK_DETAIL_READY_PROBES}）。
 * 详情维度由页面自报，**不等**定义列表 / 结果文件 / 作业 / SpreadJS / 编辑器。
 *
 * 超时口径与 `waitForPageReady` 一致（默认 {@link PAGE_READY_TIMEOUT_MS}，轮询 50ms），
 * **不无限等**：到点即如实返回三维现状，由调用方决定降级文案（不得当成成功）。
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
    const detailReady = isTaskDetailReady(page)
    if (pageReady && taskBound && detailReady) {
      return { pageReady: true, taskBound: true, detailReady: true }
    }
    if (Date.now() >= deadline) {
      return { pageReady, taskBound, detailReady }
    }
    await delay(50)
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 等待"**某个向导页**就绪且任务已绑定且详情已应用"（`restore_task` 未带任务类型时的完成判据）。
 *
 * 为什么不能先猜页面：目标页由任务类型决定，而类型没给就必须由服务端说了算。这里只等
 * "任务绑上了、且落在一个向导页面上"，不替用户/模型选边（选错页正是"导入跳导出"）。
 *
 * 三维**分开返回**（NAV2）：`opened` = 落在某个向导页且任务已绑定；`detailReady` = 详情已应用。
 * 只有布尔值时，"页已打开、详情还在加载"与"页根本没打开"会挤进同一个 false，回执只能瞎猜一侧
 * —— 前者被说成"页面未就绪/未跳转"就是谎报。两维都只读现场，不加等待时长。
 */
async function waitForTaskOpened(
  expectedTaskId: number,
  timeoutMs = PAGE_READY_TIMEOUT_MS
): Promise<{ opened: boolean; detailReady: boolean }> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const workspace = useWorkspaceStore()
    const page = workspace.pageId
    const wizardReady = (page === 'export' || page === 'import')
      && PAGE_READY_PROBES[page].some((name) => getPageHandler(name) !== null)
    const opened = wizardReady && workspace.taskId === expectedTaskId
    const detailReady = opened && isTaskDetailReady(page)
    if (opened && detailReady) {
      return { opened: true, detailReady: true }
    }
    if (Date.now() >= deadline) {
      return { opened, detailReady }
    }
    await delay(50)
  }
}

/** `restore_task` 的目标页（任务类型 → 向导页）：类型由服务端给定，不由调用方猜。 */
function wizardPageOf(taskType: TaskType): { page: WorkspacePageId; label: string } {
  return taskType === 'IMPORT'
    ? { page: 'import', label: '导入' }
    : { page: 'export', label: '导出' }
}

/**
 * **权威任务类型**解析（批次 5B）：查不到就**不导航**。
 *
 * 返回 `null` 的三种现场（任务不存在 / 读取失败 / 类型不可识别）都必须给模型一条可行动的回执，
 * 而不是按旧口径兜底成 EXPORT —— 那会把导入任务送进导出向导，且模型看到的是"成功"。
 */
async function resolveAuthoritativeTaskType(taskId: number): Promise<TaskType | null> {
  return await useTaskStore().ensureTaskType(taskId)
}

/**
 * 任务类型未知时的统一回执（fail-closed：不导航、不猜页）。
 */
function taskTypeUnknown(taskId: number): FrontendToolExecution {
  return makeExecution({
    outcome: 'failed',
    availability: 'degraded',
    result: `无法确认任务 #${taskId} 的类型（任务不存在或读取失败），本次未导航。`
      + '请用 list_tasks 核对任务号后重试，或提示用户在任务中心打开该任务。'
  })
}

/**
 * 页面与任务都在位、但**任务详情未应用**时的统一回执（不谎报成功，也不谎报"没导航"）。
 *
 * 这一拍页面上的选中集/条件仍是空的：放行成 ok，紧随的 `confirm_step` 就会拿空选中集推进
 * 并向服务端落库（"AI 说按勾选导出，实际导出全量"）。故如实报 failed + 可行动下一步。
 *
 * NAV2 口径：`router.push` 已经成功、目标页也挂上了，缺的只是**详情**这一维 —— 回执必须说
 * "页面已打开、详情仍在加载"，不能写成"导航未执行 / 未跳转 / 打开失败"（模型会据此重新导航，
 * 页面再挂一次，且真正的等待窗口被重新计时）。路由**不改**：不重推、不回退。
 *
 * @param label 向导中文名（`导出` / `导入`）；未带任务类型时页名不可知，传 `null` 走通用主语
 */
function taskDetailNotReady(label: string | null, taskId: number): FrontendToolExecution {
  return makeExecution({
    outcome: 'failed',
    availability: 'degraded',
    code: 'UI_PAGE_NOT_READY',
    result: formatUiReceipt({
      tool: label === null ? '任务向导' : `${label}向导`,
      conclusion: `页面已打开、任务 #${taskId} 已绑定，但任务详情在等待上界内仍未应用（选中的配置项 / 查询条件 / 步骤尚未恢复）`,
      reason: '详情读取慢或读取失败，此时页面上的勾选集为空；路由已经落位，无需重新导航',
      next: '本轮不要再设置条件或推进步骤；请稍后重试或提示用户手动打开该任务'
    })
  })
}

/**
 * `restore_task`：按**服务端**任务类型恢复向导，等目标页就绪再回灌（批次 5B）。
 *
 * 与 `navigate_to(page, taskId)` 的分工：本动作**不需要** page 入参（类型是事实，不是参数），
 * 模型只给任务号即可；`navigate_to` 给了 page 也仍以服务端类型为准（见 {@link runNavigateTo}）。
 */
async function runRestoreTask(args: Record<string, unknown>, lifetime: UiEventLifetime): Promise<FrontendToolExecution> {
  const taskId = toNumber(args.taskId)
  if (taskId === null) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `恢复任务的参数不完整：${missingArgsHint('restore_task', WORKSPACE_ACTION_REQUIRED_ARGS)}`
    })
  }
  const taskType = await resolveAuthoritativeTaskType(taskId)
  if (taskType === null) {
    return taskTypeUnknown(taskId)
  }
  const { page, label } = wizardPageOf(taskType)
  const workspace = useWorkspaceStore()
  const context = workspace.snapshot()
  if (context.pageId === page && context.taskId === taskId) {
    // 幂等早退也要等详情：页面/任务没变不代表选中集与条件已经恢复（详见 TASK_DETAIL_READY_PROBES）
    if (!(await waitForTaskDetailApplied(page))) {
      return taskDetailNotReady(label, taskId)
    }
    return makeExecution({
      outcome: 'ok',
      availability: 'available',
      code: 'UI_ALREADY_ON_PAGE',
      result: `已在任务 #${taskId} 的${label}向导（任务 #${taskId} 已绑定），这一步不用再做。`
    })
  }
  const outcome = await dispatchUiEvent({ type: 'restore_task', taskId, taskType }, lifetime)
  if (!outcome.handled) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `恢复任务 #${taskId} 的向导未完成（${outcome.message}）。请提示用户在任务中心手动打开该任务。`
    })
  }
  const wait = await waitForTaskBound(page, taskId)
  if (wait.pageReady && wait.taskBound && wait.detailReady) {
    return makeExecution({
      outcome: 'ok',
      availability: 'available',
      result: `${outcome.message}（页面已就绪，任务 #${taskId} 已绑定且详情已加载；类型以服务端为准：${label}任务）`
    })
  }
  if (wait.pageReady && wait.taskBound) {
    return taskDetailNotReady(label, taskId)
  }
  if (wait.pageReady) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `${outcome.message}，但任务 #${taskId} 的绑定没有完成（${label}向导已就绪，未绑定该任务）。`
        + '请核对任务号，或提示用户手动在任务中心打开该任务后重试。'
    })
  }
  return makeExecution({
    outcome: 'failed',
    availability: 'degraded',
    code: 'UI_PAGE_NOT_READY',
    result: formatUiReceipt({
      tool: '恢复任务',
      conclusion: `导航已提交，但${label}向导在等待上界内仍未就绪（任务 #${taskId}）`,
      reason: '页面加载慢，或该页尚未注册可操作能力',
      next: '本轮不要重复导航；请稍后重试或提示用户手动打开该页面'
    })
  })
}

/**
 * `navigate_to` 的执行体（批次 5B 抽出）。
 *
 * 导航是**唯一**同时需要"先查权威任务类型、后等页面就绪"的动作，故单独成体，避免与
 * 选配置 / 设条件 / 推进步骤那三条本页动作混在一个分支里。
 *
 * 关键口径：带 taskId 且目标是向导页时，**服务端任务类型优先于模型写的 page** ——
 * 模型写 `page=export` 而该任务是导入任务时，实际进入导入向导并在回执里说明修正。
 * 类型走 {@link resolveAuthoritativeTaskType}（`ensureTaskType`：**缓存优先，未命中拉一次 GET**），
 * 查不到（任务不存在 / 读取失败）就 **fail-closed 不导航** —— 冷缓存下"按列表缓存兜底成 EXPORT"
 * 正是导入任务被送进导出向导的根因。
 */
async function runNavigateTo(args: Record<string, unknown>, lifetime: UiEventLifetime): Promise<FrontendToolExecution> {
  const intent = resolveNavigateIntent(args)
  if (!intent) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `页面导航的参数不完整：${missingArgsHint('navigate_to', WORKSPACE_ACTION_REQUIRED_ARGS)}`
    })
  }
  let page = intent.page
  let taskType: TaskType | null = null
  let taskLabel = ''
  let correction = ''
  if (intent.taskId !== null) {
    taskType = await resolveAuthoritativeTaskType(intent.taskId)
    if (taskType === null) {
      return taskTypeUnknown(intent.taskId)
    }
    const target = wizardPageOf(taskType)
    taskLabel = target.label
    if (target.page !== page) {
      correction = `（任务 #${intent.taskId} 是${target.label}任务，已按服务端类型进入${target.label}向导）`
      page = target.page
    }
  }
  const workspace = useWorkspaceStore()
  const context = workspace.snapshot()
  // 批 A（A-01/A-02）：同页同参幂等直返（导航重试风暴的根因就是"每次都当真执行"）。
  // 幂等也等详情：页面与任务都没变不代表选中集/条件已恢复（否则这一条会替一次未恢复的页面背书）。
  if (page === context.pageId && (intent.taskId === null || intent.taskId === context.taskId)) {
    if (intent.taskId !== null && !(await waitForTaskDetailApplied(page))) {
      return taskDetailNotReady(taskLabel, intent.taskId)
    }
    const bound = intent.taskId === null ? '' : `，任务 #${intent.taskId} 已绑定`
    return makeExecution({
      outcome: 'ok',
      availability: 'available',
      code: 'UI_ALREADY_ON_PAGE',
      result: `已在目标页（${page}）${bound}，这一步不用再做。`
    })
  }
  const event: UiEvent = intent.taskId === null
    ? { type: 'open_page', page }
    : { type: 'restore_task', taskId: intent.taskId, ...(taskType === null ? {} : { taskType }) }
  const outcome = await dispatchUiEvent(event, lifetime)
  if (!outcome.handled) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `${outcome.message}。请提示用户在界面上手动完成该动作。`
    })
  }
  // F3：导航类动作等目标页面就绪再回灌（否则同一轮里紧随的动作会打在页面未挂载的窗口上）；
  // 带 taskId 时还要等**任务详情已应用**（否则紧随的 confirm_step 会拿空选中集推进并落库）
  const wait: TaskBindWaitResult = intent.taskId === null
    ? { pageReady: await waitForPageReady(page), taskBound: true, detailReady: true }
    : await waitForTaskBound(page, intent.taskId)
  if (wait.pageReady && wait.taskBound && wait.detailReady) {
    const bound = intent.taskId === null ? '' : `，任务 #${intent.taskId} 已绑定`
    return makeExecution({
      outcome: 'ok',
      availability: 'available',
      result: `${outcome.message}（页面已就绪${bound}，可继续执行本页动作）${correction}`
    })
  }
  if (wait.pageReady && wait.taskBound && intent.taskId !== null) {
    return taskDetailNotReady(taskLabel, intent.taskId)
  }
  if (wait.pageReady) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `${outcome.message}，但任务 #${intent.taskId} 的绑定没有完成（向导页已就绪，未绑定该任务）。`
        + `请核对任务 #${intent.taskId} 是否存在，或提示用户手动在任务中心打开该任务后重试。`
    })
  }
  return makeExecution({
    outcome: 'failed',
    availability: 'degraded',
    code: 'UI_PAGE_NOT_READY',
    result: formatUiReceipt({
      tool: '页面导航',
      conclusion: `导航已提交，但目标页面在等待上界内仍未就绪（${page}${intent.taskId === null ? '' : `，任务 #${intent.taskId} 待绑定`}）`,
      reason: '页面加载慢，或该页尚未注册可操作能力',
      next: '本轮不要重复导航；请稍后重试或提示用户手动打开该页面'
    })
  })
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
 *
 * `navigate_to` 只保留展示名与页面白名单（回灌文案、`toolDisplayName` 用）：
 * 它的执行体是 {@link runNavigateTo}（批次 5B 抽出 —— 要先查权威任务类型再等页面就绪，
 * 与"本页动作"不是同一条链）。
 */
export const WORKSPACE_ACTIONS: readonly WorkspaceAction[] = [
  {
    name: 'navigate_to',
    displayName: '页面导航',
    allowPages: ['tasks', 'export', 'import', 'definitions', 'data'],
    allowSteps: [],
    handlerNames: []
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
      // R5：`conditions` 必填 —— 漏参**不得**补成 `{}`（那会被契约层当成「显式清空」）。
      // 缺参在此返回 null，走「参数不完整」失败文案（零写）。
      return defCode === '' || !hasExplicitConditionPayload(args)
        ? null
        : { type: 'set_conditions', defCode, conditions: args.conditions }
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
 * `navigate_to` 的意图解析（{@link runNavigateTo} 的唯一判定入口）。
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

/**
 * RC-3：工作区动作的**参数要求**（缺参文案单点）。
 *
 * 用户口径（2026-10-16 裁决②/③）：前端工具的**参数错误文案不得内嵌 `JSON.stringify(args)`**
 * ——只把"缺什么"写成自然语言；表外动作回落一句不指名字段但可行动的话（仍不含任何 JSON 特征）。
 *
 * 页面 handler 同样受这条约束：**参数错误必须返回结构化 `{ ok:false, reason }`** —— 字符串返回
 * 会被 `interpretHandlerOutcome` 当"成功 + 该文本"（兼容解读），缺参会变成假成功
 * （R6 的 `export.setConditions` 缺 defCode 曾回字符串，已修为结构化失败）。
 */
const WORKSPACE_ACTION_REQUIRED_ARGS: Readonly<Record<string, string>> = {
  navigate_to: '缺少 page（页面标识：tasks / export / import / definitions / data）',
  restore_task: '缺少 taskId 或它不是数字',
  select_definitions: '缺少 codes（配置项编码列表，不能为空）',
  set_condition: '缺少 defCode 或 conditions（查询条件对象；清空条件请显式传空对象）',
  confirm_step: '缺少 step（目标步骤标识）'
}

/**
 * RC-3：ui_event 动作的**参数要求**（同上表口径，覆盖 `toUiEvent` 的必填分支）。
 * `refresh_*` 三个动作 `toUiEvent` 恒有值，只会因类型不符走到这里。
 */
const UI_EVENT_REQUIRED_ARGS: Readonly<Record<string, string>> = {
  open_page: '缺少 page（页面标识：tasks / export / import / definitions / data）',
  restore_task: '缺少 taskId 或它不是数字',
  goto_step: '缺少 step 或它是空串',
  select_defs: '缺少 defCodes 或它不是数组',
  set_conditions: '缺少 defCode / conditions（查询条件对象；清空条件请显式传空对象），或 defCode 是空串',
  set_import_mode: '缺少 mode 或它不是 MERGE / REPLACE',
  run_job: '缺少 taskId 或 jobType（EXPORT / PRECHECK / IMPORT / PUBLISH）',
  cancel_job: '缺少 jobId 或它不是数字',
  download: '缺少 url 或它是空串',
  refresh_defs: '参数类型不符',
  refresh_data: 'defCode 不是字符串',
  refresh_tasks: '参数类型不符'
}

/** 缺参提示（RC-3）：命中表即指名道姓，未登记的动作给一句可行动的兜底（无 JSON）。 */
function missingArgsHint(name: string, table: Readonly<Record<string, string>>): string {
  return table[name] ?? '缺少必要字段，请核对工具参数后重试'
}

/**
 * 步骤不符时的**动作专属**下一步指引（2026-10-28 步骤披露与筛选链路收口）。
 *
 * 通用文案只写「请先推进到可用步骤」—— 模型拿到它不知道"推进"用哪个工具、推到哪一步，
 * 往往重试同一调用或猜 `confirm_step(step=...)`。`set_condition` 的步骤面只有 `QUERY_COND`
 * （与后端 `@ToolScope("task:EXPORT/QUERY_COND")` 同源），错步多发生在 `SELECT_DEFS` 批里抢跑，
 * 故只给这一个动作点名工具与目标步骤。**其它动作沿用通用文案，一字不动**（不泛化）。
 *
 * 注意：这条指引只解释"该先做什么"，**不是**自动跨步 —— 守卫命中时仍直接拒绝，
 * `set_condition` 绝不自己调 `confirm_step`，也不自己改工作区步骤。
 */
const WORKSPACE_ACTION_STEP_HINT: Readonly<Record<string, string>> = {
  set_condition: '请先调用 confirm_step 推进到 QUERY_COND（不传 step，只前进一步），下一批再调用 set_condition。'
}

/** 步骤不符提示：登记过的动作给专属指引，其余保持既有通用文案。 */
function stepMismatchHint(name: string): string {
  return WORKSPACE_ACTION_STEP_HINT[name] ?? '请先推进到可用步骤。'
}

/** 动作执行：可用性（unavailable）→ 页面 handler（available）→ 契约层（available/degraded）。 */
async function runWorkspaceAction(
  action: WorkspaceAction,
  args: Record<string, unknown>,
  lifetime: UiEventLifetime
): Promise<FrontendToolExecution> {
  // 导航是唯一需要"先查权威任务类型、后等页面就绪"的动作：单独成体（批次 5B）。
  // 其余三条（选配置 / 设条件 / 推进步骤）留在本函数里按"本页动作"处理。
  if (action.name === 'navigate_to') {
    return await runNavigateTo(args, lifetime)
  }
  const workspace = useWorkspaceStore()
  const context = workspace.snapshot()
  if (!action.allowPages.includes(context.pageId)) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `当前页面为${context.pageId}，${action.displayName}只支持：${action.allowPages.join('、')}。请先导航到目标页面再执行。`
    })
  }
  if (action.allowSteps.length > 0 && !action.allowSteps.includes(context.step ?? '')) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `当前步骤为${context.step ?? '未进入向导'}，${action.displayName}只支持：${action.allowSteps.join('、')}。${stepMismatchHint(action.name)}`
    })
  }
  if (action.needsTask && context.taskId === null) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `${action.displayName}需要绑定任务：当前没有进行中的任务，请先在任务中心创建或打开一条任务。`
    })
  }

  for (const handlerName of action.handlerNames) {
    const handler = getPageHandler(handlerName)
    if (handler) {
      return await runPageHandler(handlerName, handler, normalizeActionArgs(action, args), action.displayName, lifetime)
    }
  }

  const event = action.toUiEvent?.(args) ?? null
  if (!event) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `${action.displayName}的参数不完整：${missingArgsHint(action.name, WORKSPACE_ACTION_REQUIRED_ARGS)}`
    })
  }
  const outcome = await dispatchUiEvent(event, lifetime)
  if (outcome.handled) {
    return makeExecution({ outcome: 'ok', availability: 'available', result: outcome.message })
  }
  // 契约层拒绝（缺任务/缺 handler）→ degraded：给出可行动说明，不静默
  return makeExecution({
    outcome: 'failed',
    availability: 'degraded',
    result: `${action.displayName}未完成（${outcome.message}）。请提示用户在界面上手动完成该动作。`
  })
}

/** 动作 args → 页面 handler 载荷（把别名参数归一，handler 只需认识一套字段名）。 */
function normalizeActionArgs(action: WorkspaceAction, args: Record<string, unknown>): Record<string, unknown> {
  switch (action.name) {
    case 'select_definitions':
      return { defCodes: toStringList(args.codes ?? args.defCodes), mode: args.mode === 'ADD' ? 'ADD' : 'REPLACE' }
    case 'set_condition':
      // R5：原样透传（不补 {}）—— 漏参由页面 handler 显式拒绝，绝不替调用方清空
      return { defCode: args.defCode ?? '', conditions: args.conditions }
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

/**
 * `conditions` 是否**显式提供**（`{}` 算提供；缺键 / `undefined` / `null` 都不算）。
 *
 * 为什么必须区分：`conditions` 是设置条件的**必填载荷**，只有显式 `{}` 才表示清空条件。
 * 用 `args.conditions ?? {}` 兜底会把一次漏参静默变成清空（= 导出全量）。
 */
function hasExplicitConditionPayload(args: Record<string, unknown>): boolean {
  return Object.prototype.hasOwnProperty.call(args, 'conditions')
    && args.conditions !== undefined
    && args.conditions !== null
}

function toStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)).filter((item) => item !== '') : []
}

/**
 * 取当前表格上下文（页面能力 `sheet.current`）。
 *
 * 页面只注册**能力**，AI 侧不 import 任何视图：导出向导与导入向导各自注册同一套 handler，
 * 因此两页共用本函数。未注册 / 结构不合法 / handler 抛错一律给出**可行动**的降级文案
 * （设计稿 §3.6：约定不 reject）。
 */
export async function resolveSheetContext(
  defCode: unknown
): Promise<{ ok: true; ctx: SheetContext } | { ok: false; reason: string }> {
  const handler = getPageHandler('sheet.current')
  if (!handler) {
    return {
      ok: false,
      reason: '当前页面没有可操作的表格。请先打开导出结果页或上传页（表格只在这两处出现），再重试。'
    }
  }
  let raw: unknown
  try {
    raw = await handler({ defCode })
  } catch (error) {
    return { ok: false, reason: `读取表格失败：${error instanceof Error ? error.message : String(error)}` }
  }
  if (!raw || typeof raw !== 'object') {
    return { ok: false, reason: '表格能力返回了无法识别的结果（页面可能刚卸载），请重试。' }
  }
  const value = raw as {
    ok?: unknown
    reason?: unknown
    defCode?: unknown
    sheetName?: unknown
    readOnly?: unknown
    adapter?: unknown
    fields?: unknown
  }
  if (value.ok === false) {
    return { ok: false, reason: typeof value.reason === 'string' && value.reason !== '' ? value.reason : '表格当前不可用。' }
  }
  if (typeof value.defCode !== 'string' || !Array.isArray(value.fields) || !value.adapter || typeof value.adapter !== 'object') {
    return { ok: false, reason: '表格能力返回的结构不完整（缺少 defCode/fields/adapter），请重新打开该表格后重试。' }
  }
  return {
    ok: true,
    ctx: {
      defCode: value.defCode,
      sheetName: typeof value.sheetName === 'string' ? value.sheetName : value.defCode,
      readOnly: value.readOnly === true,
      adapter: value.adapter as SheetAdapter,
      fields: value.fields as ConfigField[],
      // 表内内容版本（不是页面回报的全局 `workspace.dataVersion`）：无关 touch 不改它，
      // 也不参与预览硬闸（硬闸 = previewId + 行指纹，见 sheet-tools 的 replanSheet / applySheetPlan）。
      version: sheetContentVersionOf(value.adapter as SheetAdapter)
    }
  }
}

/** 表格工具 → 纯函数（读侧 5 档 + 撤销；写侧预览由 store 特判，不走本执行器）。 */
function runSheetTool(name: string, args: Record<string, unknown>): Promise<FrontendToolExecution> {
  return resolveSheetContext(args.defCode).then((resolved) => {
    if (!resolved.ok) {
      return makeExecution({ outcome: 'failed', availability: 'degraded', result: resolved.reason })
    }
    const ctx = resolved.ctx
    // 只读壳只拦**写**路径：读工具（摘要/聚合/诊断/校验）与 normalize 建议档照常可用。
    const writeCall = SHEET_WRITE_TOOLS.has(name) && (name !== 'normalize_column' || args.commit === true)
    if (ctx.readOnly && writeCall) {
      return makeExecution({
        outcome: 'failed',
        availability: 'degraded',
        result: sheetReadOnlyWriteBlocked()
      })
    }
    let outcome: SheetToolOutcome
    switch (name) {
      case 'read_sheet_summary':
        outcome = readSheetSummary(ctx, args)
        break
      case 'aggregate_sheet':
        outcome = aggregateSheet(ctx, args)
        break
      case 'inspect_columns':
        outcome = inspectColumns(ctx, args)
        break
      case 'review_cells':
        outcome = reviewCells(ctx, args)
        break
      case 'find_rows':
        outcome = findRows(ctx, args)
        break
      case 'normalize_column':
        outcome = args.commit === true
          ? { ok: false, reason: '写操作（commit=true）必须经用户确认后由预览卡应用，本次未做任何修改。' }
          : normalizeSuggest(ctx, args)
        break
      case 'undo_last_sheet_edit':
        // 工具安全边界批（2026-10-25 裁决）：撤销会覆盖当前表格数据 ⇒ 必须经用户在预览卡上确认。
        // 帧路径已由 needsSheetPreview 特判到 openWritePreview；走到这里说明有人绕过了确认面
        // （旧客户端 / 直接调用执行器）—— fail-closed，一格都不写。
        outcome = {
          ok: false,
          reason: '撤销会覆盖当前表格数据，必须由用户在预览卡上确认后执行（本次未做任何修改）。'
            + '请重新发起 undo_last_sheet_edit，用户点「应用」后才会写回。'
        }
        break
      default:
        outcome = { ok: false, reason: `未知的表格工具 ${name}。` }
    }
    // 表格采样元数据（治理批 §4.2）：只有成功读数才有"出域体积"可言 —— 失败路径不带，
    // 免得把"没读到"记成"读了 0 行"（那是另一种失真）。
    return outcome.ok
      ? makeExecution({
          outcome: 'ok',
          availability: 'available',
          result: outcome.text,
          sheetMeta: outcome.meta
            ? {
                ...outcome.meta,
                defCode: typeof args.defCode === 'string' ? args.defCode : undefined,
                // 台账定位用的页面 id（只进台账，不进日志面）
                page: useWorkspaceStore().snapshot().page
              }
            : undefined
        })
      : makeExecution({ outcome: 'failed', availability: 'degraded', result: outcome.reason })
  })
}

/** 路由 1：登记在册的前端工具（参数校验 + scope 校验 + 执行器）。 */
async function runRegisteredTool(
  descriptor: FrontendToolDescriptor,
  call: FrontendToolCall,
  lifetime: UiEventLifetime
): Promise<FrontendToolExecution> {
  const validated = validateArgs(descriptor, call.args)
  if (!validated.ok) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `参数不合法，${descriptor.displayName}未执行：${validated.reason}`
    })
  }

  const workspace = useWorkspaceStore()
  const context = workspace.snapshot()
  if (!matchesScope(descriptor.scopePatterns, {
    page: context.page,
    taskType: context.taskType,
    step: context.step,
    uiRegionCount: listAiRegions().length
  })) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `当前上下文（页面=${context.page}，步骤=${context.step ?? '未进入向导'}）不支持${descriptor.displayName}。`
    })
  }

  const executor = executors.get(descriptor.name) ?? defaultExecutor(descriptor.name)
  if (!executor) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `${descriptor.displayName}当前不可用（本页未注册该能力）。请改用其他方式：由用户手动操作，或使用后端工具完成任务。`
    })
  }

  try {
    const value = await executor({ ...call, args: validated.coerced }, lifetime)
    // 批3③a A4：执行器可返回结构化结局（如实报失败不必 throw）；字符串保持成功兼容解读。
    return normalizeExecutorReturn(value)
  } catch (error) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `${descriptor.displayName}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
    })
  }
}

/**
 * 执行器返回值解读（批3③a A4）：字符串 = 成功 + 该文本（兼容旧执行器）；
 * 结构化 {@link FrontendToolExecution} = 按其 outcome 语义原样落（经工厂重推导 ok，
 * 一致性仍单点化）；历史遗留的"只带 ok 不带 outcome"对象按 ok 三元推导。
 */
function normalizeExecutorReturn(value: string | FrontendToolExecution): FrontendToolExecution {
  if (typeof value === 'string') {
    return makeExecution({ outcome: 'ok', availability: 'available', result: value })
  }
  return makeExecution({
    outcome: value.outcome ?? (value.ok ? 'ok' : 'failed'),
    availability: value.availability,
    result: value.result,
    reason: value.reason,
    // 机器码（UI_* 闭集）必须穿过这一层：本函数是执行器返回值的**重新包装**点，
    // 丢了它 = 原语在围栏处给出的码到不了 store / 回灌，台账只剩结局文本（同一类
    // "三面同源"失明，与下面的 sheetMeta 完全同理）。
    code: value.code,
    // 表格采样元数据必须穿过这一层：本函数是执行器返回值的**重新包装**点，
    // 丢了它 = 后端永远收不到采样事实（日志/台账/计数三面同时失明）
    sheetMeta: value.sheetMeta
  })
}

/**
 * 导航类 ui_event 的等待结局。
 *
 * `opened-detail-pending` 必须与 `not-ready` 分开：`router.push` 已经成功、目标页也挂上了，
 * 只差任务详情这一维 —— 把它并进 `not-ready` 就会被回执说成"页面未就绪/没导航"，
 * 与事实相反（模型据此重新导航，页面再挂一次，真正的等待窗口又被重新计时）。
 */
type UiEventNavigationWait =
  | { kind: 'none' }
  | { kind: 'ready' }
  | { kind: 'opened-detail-pending'; taskId: number; label: string | null }
  | { kind: 'not-ready' }

/**
 * 导航类 ui_event 的目标页与完成判据（批次 5B：导航后等目标页就绪再回灌）。
 *
 * 直接经路由 3 驱动的 ui_event（`export_started` / `import_batch` / `import_action` / `open_page`）
 * 此前一律"push 即回执"：同一轮里紧随的动作会打在"页面还没挂载"的窗口上（与 F3 同一根因，
 * 只是走的是另一条路由）。`restore_task` 的目标页由任务类型决定 —— 有 `taskType` 就按它对页面，
 * 没有就等"某个向导页就绪且任务已绑定"（不猜）。
 *
 * `restore_task` 的成功判据与 `navigate_to` / `runRestoreTask` **同源**：页面 + 绑定 + **详情**
 * 三维齐了才算就绪（漏掉详情这一维，紧随的 `confirm_step` 会拿空选中集落库）。
 *
 * @returns `none` = 本事件不导航（无需等待）
 */
async function waitForUiEventNavigation(event: UiEvent): Promise<UiEventNavigationWait> {
  switch (event.type) {
    case 'open_page':
      return await waitForPageReady(event.page) ? { kind: 'ready' } : { kind: 'not-ready' }
    case 'export_started':
      return await waitForPageReady('export') ? { kind: 'ready' } : { kind: 'not-ready' }
    case 'import_batch':
    case 'import_action':
      return await waitForPageReady('import') ? { kind: 'ready' } : { kind: 'not-ready' }
    case 'restore_task': {
      if (event.taskType === 'IMPORT' || event.taskType === 'EXPORT') {
        const { page, label } = wizardPageOf(event.taskType)
        const wait = await waitForTaskBound(page, event.taskId)
        if (wait.pageReady && wait.taskBound && wait.detailReady) {
          return { kind: 'ready' }
        }
        // 页已打开、任务已绑定，只差详情：回执照实说"已打开"，不说"未就绪/未导航"
        return wait.pageReady && wait.taskBound
          ? { kind: 'opened-detail-pending', taskId: event.taskId, label }
          : { kind: 'not-ready' }
      }
      const opened = await waitForTaskOpened(event.taskId)
      if (opened.detailReady) {
        return { kind: 'ready' }
      }
      // 未带类型：页名不可知（类型归服务端），回执只用通用主语"任务向导"
      return opened.opened
        ? { kind: 'opened-detail-pending', taskId: event.taskId, label: null }
        : { kind: 'not-ready' }
    }
    default:
      return { kind: 'none' }
  }
}

/** 路由 3：ui_event 同名动作 → 契约层统一分发（与用户点击同一套 action）。 */
async function runUiEventAction(
  name: UiEvent['type'],
  args: Record<string, unknown>,
  lifetime: UiEventLifetime
): Promise<FrontendToolExecution> {
  const event = toUiEvent(name, args)
  if (!event) {
    return makeExecution({
      outcome: 'failed',
      availability: 'unavailable',
      result: `${name} 的参数不完整或类型不符，未驱动工作区：${missingArgsHint(name, UI_EVENT_REQUIRED_ARGS)}`
    })
  }
  try {
    const outcome = await dispatchUiEvent(event, lifetime)
    // 批3③a D1：handled:true 且副作用已 await ⇒ 'ok'；契约层未受理（fail）⇒ 确定性 'failed'
    // —— 不再让"未受理"戴着 ok:true 出去。
    if (!outcome.handled) {
      return makeExecution({ outcome: 'failed', availability: 'available', result: outcome.message })
    }
    // 批次 5B：导航类事件等目标页就绪再回灌（等待上界与 navigate_to 同源）
    const wait = await waitForUiEventNavigation(event)
    // 页已打开、任务已绑定，只差详情：共用 `taskDetailNotReady`（failed + degraded，不改路由、
    // 不说"没导航"），与 `navigate_to` / `restore_task` 三条导航路径同一口径。
    if (wait.kind === 'opened-detail-pending') {
      return taskDetailNotReady(wait.label, wait.taskId)
    }
    if (wait.kind === 'not-ready') {
      return makeExecution({
        outcome: 'failed',
        availability: 'degraded',
        code: 'UI_PAGE_NOT_READY',
        result: formatUiReceipt({
          tool: name,
          conclusion: `导航已提交，但目标页面在等待上界内仍未就绪（${event.type}）`,
          reason: '页面加载慢，或该页尚未注册可操作能力',
          next: '本轮不要重复导航；请稍后重试或提示用户手动打开该页面'
        })
      })
    }
    return makeExecution({
      outcome: 'ok',
      availability: 'available',
      result: wait.kind === 'ready' ? `${outcome.message}（页面已就绪，可继续执行本页动作）` : outcome.message
    })
  } catch (error) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `工作区动作 ${name} 执行失败：${error instanceof Error ? error.message : String(error)}`
    })
  }
}

/** 路由 4：页面 handler（页面未注册时该分支不会被走到）。 */
async function runPageHandler(
  name: string,
  handler: (payload: Record<string, unknown>, lifetime?: UiEventLifetime) => unknown | Promise<unknown>,
  args: Record<string, unknown>,
  displayName?: string,
  lifetime?: UiEventLifetime
): Promise<FrontendToolExecution> {
  const label = displayName ?? name
  try {
    // lifetime = 本次执行的存活标记：页面 handler 在 await 之后读它，已作废就不写工作区/
    // 步骤/选中集（停止或预算到点后，"超时回执"不得被页面迟到的写入推翻）。
    const outcome = await handler(args, lifetime)
    // 批3③a A3：handler 可返回结构化 `{ ok:false, reason }` 如实报失败（D2：确定性失败，
    // 不得再包成 ok:true）；字符串保持成功兼容解读（D3(iii) 登记面不动）。
    const interpreted = interpretHandlerOutcome(outcome, label)
    return makeExecution({
      outcome: interpreted.outcome,
      availability: interpreted.outcome === 'ok' ? 'available' : 'degraded',
      result: interpreted.result,
      reason: interpreted.reason
    })
  } catch (error) {
    return makeExecution({
      outcome: 'failed',
      availability: 'degraded',
      result: `${label}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
    })
  }
}

/**
 * 页面 handler 返回值解读（批3③a A3/A5 共用）：
 * - 字符串 = 成功 + 该文本（兼容解读不变）—— 只用于**已执行后的事实陈述**；
 *   参数错误 / 确定性失败必须返回结构化 `{ ok:false, reason }`，否则缺参会变成假成功；
 * - 结构化 `{ ok:false, reason }` = **确定性失败**（D2：如编辑器引擎加载失败 —— 落 failed，
 *   不得误入 unknown，也不得被包装层折回成功）；
 * - `{ ok:true, result? }` = 成功；其它非字符串值保持旧兜底「<label>已执行」。
 */
function interpretHandlerOutcome(
  outcome: unknown,
  label: string
): { result: string; outcome: FrontendToolOutcome; reason?: string } {
  if (typeof outcome === 'string') {
    return { result: outcome, outcome: 'ok' }
  }
  if (outcome !== null && typeof outcome === 'object' && 'ok' in outcome) {
    const structured = outcome as { ok: unknown; reason?: unknown; result?: unknown }
    if (structured.ok === false) {
      const reason = typeof structured.reason === 'string' && structured.reason !== ''
        ? structured.reason
        : `${label}执行失败`
      return { result: reason, outcome: 'failed', reason }
    }
    const result = typeof structured.result === 'string' ? structured.result : `${label}已执行`
    return { result, outcome: 'ok' }
  }
  return { result: `${label}已执行`, outcome: 'ok' }
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
      if (taskId === null) {
        return null
      }
      // 合法类型原样透传：契约层据此免掉一次 GET，也避免"类型未知按列表兜底"的错页
      const taskType = args.taskType === 'IMPORT' || args.taskType === 'EXPORT' ? args.taskType : null
      return taskType === null
        ? { type: 'restore_task', taskId }
        : { type: 'restore_task', taskId, taskType }
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
      // R5：`conditions` 必填（缺参 → null → 参数不完整失败，不补 {} 当清空）
      return typeof defCode === 'string' && defCode !== '' && hasExplicitConditionPayload(args)
        ? { type: 'set_conditions', defCode, conditions: args.conditions }
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

/**
 * AI 可视可操作专项：7 个通用原语的执行桥。
 *
 * 原语只返回 `{ outcome, availability, result }`（**不产 `ok` 字段**），
 * 结局一致性仍由 {@link makeExecution} 单点推导（D1 不破）。
 * `ui_request_upload` 正常路径由 store 特判进交接卡；走到这里说明帧路由异常，
 * 如实报错而不是静默假装成功。
 */
async function runUiPrimitive(name: string, args: Record<string, unknown>): Promise<FrontendToolExecution> {
  let outcome: UiToolOutcome
  switch (name) {
    case 'ui_snapshot':
      outcome = aiSnapshot(args)
      break
    case 'ui_click':
      outcome = await aiClick(args)
      break
    case 'ui_fill':
      outcome = await aiFill(args)
      break
    case 'ui_select':
      outcome = await aiSelect(args)
      break
    case 'ui_scroll':
      outcome = aiScroll(args)
      break
    case 'ui_highlight':
      outcome = aiHighlight(args)
      break
    case 'ui_request_upload':
      outcome = {
        outcome: 'failed',
        availability: 'degraded',
        result: '上传交接不走通用执行器：请在对话内的交接卡上由用户手势选择文件（本次未执行）。'
      }
      break
    default:
      outcome = {
        outcome: 'failed',
        availability: 'unavailable',
        result: `未知界面原语 ${name}：本次未执行。`
      }
  }
  return makeExecution({
    outcome: outcome.outcome,
    availability: outcome.availability,
    result: outcome.result,
    code: outcome.code
  })
}

/** 契约层自带的默认执行器（页面未覆盖时使用）。 */
function defaultExecutor(name: FrontendToolName): FrontendToolExecutor | null {
  // AI 可视可操作专项：7 个通用原语（执行体在 utils/ai-dom-tools.ts，结局过 makeExecution）
  if ((UI_TOOL_NAMES as readonly string[]).includes(name)) {
    return (call) => runUiPrimitive(name, call.args)
  }
  if ((SHEET_TOOL_NAMES as readonly string[]).includes(name)) {
    return (call) => runSheetTool(name, call.args)
  }
  if (name === 'open_export_file_editor') {
    return async (call, lifetime) => {
      const handler = getPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR)
      if (!handler) {
        throw new Error('导出向导未注册在线编辑器能力')
      }
      // 页面 handler 同样拿得到存活标记（打开编辑器前的 await 链可能跨过预算/停止）
      const outcome = await handler({ taskId: call.args.taskId, defCode: call.args.defCode }, lifetime)
      // 批3③a A5：不再自吞 handler 结局 —— 结构化失败原样透传（由 runRegisteredTool 的
      // normalizeExecutorReturn 落 failed）；字符串保持成功兼容解读。
      const interpreted = interpretHandlerOutcome(outcome, `已请求打开 ${String(call.args.defCode)} 的在线编辑器`)
      if (interpreted.outcome === 'failed') {
        return makeExecution({
          outcome: 'failed',
          availability: 'degraded',
          result: interpreted.result,
          reason: interpreted.reason
        })
      }
      return interpreted.result
    }
  }
  return null
}
