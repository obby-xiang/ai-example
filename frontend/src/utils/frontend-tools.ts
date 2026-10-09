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
 * - 路由 2/3 是**补丁②**：把蓝本形态的工作区动作（`navigate_to` / `select_definitions` /
 *   `set_condition` / `confirm_step` 及蓝本别名）做成可执行能力表 —— 这 4 个后端已标为 FRONTEND
 *   并披露，故今天经任何 `frontend_tool_request` 帧或契约层驱动即可生效，前端零改动（见 `WORKSPACE_ACTIONS`）；
 * - `generative_form` 前端执行接入已落地（GF-B）：`stores/ai.ts` 对 `frontend_tool_request` 特判 +
 *   AiPanel 渲染 `FormRenderer`，**不走本文件通用执行器**。
 *
 * 三条路由（AI 经 frontend_tool 帧驱动工作区的统一入口）：
 * 1. **后端披露的前端工具**（`FRONTEND_TOOLS`）：参数校验 + @ToolScope 校验 + 执行器/默认执行器；
 * 2. **工作区动作表**（`WORKSPACE_ACTIONS`：navigate_to / select_definitions / set_condition /
 *    confirm_step + 蓝本别名 select_config_defs / set_query_conditions / download_export_files …）：
 *    先找页面能力 handler，再落契约层 `dispatchUiEvent`（与用户点击**同一套** action）；
 * 3. **ui_event 同名动作**（`select_defs` / `set_conditions` / `goto_step` / `open_page` /
 *    `download` / `run_job` …）：直接经契约层分发。
 *
 * 三级可用性兜底（kimi 蓝本实践，TS 化）：
 * - available：默认执行器、页面 handler 或契约层动作可直接完成；
 * - degraded：能力当前不可用（页面未注册 handler），返回说明文本让模型改走别的路径，
 *   **不静默失败**，也不把异常抛回模型；
 * - unavailable：工具名未知 / 参数非法 / 当前上下文不该出现该工具（明确说明原因）。
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
 * 执行前端工具（三级兜底 + 三条路由）。
 *
 * 返回的 `result` 文本会被原样回灌给模型（POST /api/ai/frontend-tool-result），
 * 因此失败时也要给**可读、可行动**的说明，而不是抛异常。
 */
export async function executeFrontendTool(call: FrontendToolCall): Promise<FrontendToolExecution> {
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

/** 路由 2：ui_event 同名动作 → 契约层统一分发（与用户点击同一套 action）。 */
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

/** 路由 3：页面 handler（页面未注册时该分支不会被走到）。 */
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
