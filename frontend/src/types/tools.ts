/**
 * 工具契约（前端工具描述 + 可用性三级 + ui_event 协议）。
 *
 * 核对源：backend/.../ai/tools/AiTools.java（@Tool name/参数/风险/通道）、
 *         ai/tool/ToolScope.java（渐进披露标签）、ai/tool/ToolMeta.java（RiskLevel/Channel）
 *
 * **后端工具全集（本棒 DC-14 复核后重写；上一版沿 S4.2 口径，风险列已过期）**
 *
 * | 工具 | scope | 风险 | 通道 |
 * |---|---|---|---|
 * | list_config_defs | `*` | READ | BACKEND |
 * | get_config_def | `*` | READ | BACKEND |
 * | list_tasks | `*`, `page:tasks` | READ | BACKEND |
 * | get_workspace_state | `*` | READ | BACKEND |
 * | check_job_status | `task:*` | READ | BACKEND |
 * | get_row_count | `*`, `task:EXPORT/QUERY_COND`, `task:EXPORT/EXPORT` | READ | BACKEND |
 * | create_task | `page:tasks`, `task:EXPORT`, `task:IMPORT` | WRITE | BACKEND |
 * | start_export | `page:tasks`, `task:EXPORT/EXPORT` | **DANGER**（确认门） | BACKEND |
 * | start_precheck | `page:tasks`, `task:IMPORT/PRECHECK` | **DANGER**（确认门） | BACKEND |
 * | start_import | `task:IMPORT/IMPORT` | **DANGER**（确认门） | BACKEND |
 * | start_publish | `task:IMPORT/PUBLISH` | **DANGER**（确认门） | BACKEND |
 * | open_export_file_editor | `task:EXPORT/EXPORT` | READ | FRONTEND |
 * | download_export_files | `task:EXPORT/EXPORT` | READ | FRONTEND |
 * | navigate_to | `*` | READ | FRONTEND |
 * | select_definitions | `page:tasks`, `task:EXPORT`, `task:IMPORT` | READ | FRONTEND |
 * | set_condition | `page:tasks`, `task:EXPORT` | READ | FRONTEND |
 * | confirm_step | `page:tasks`, `task:EXPORT`, `task:IMPORT` | READ | FRONTEND |
 * | generative_form | `page:tasks`, `task:*` | READ | FRONTEND |
 *
 * 两处**已过期的旧口径**（勿再沿用）：① 四个 `start_*` 曾标 WRITE/BACKEND 且不进确认门，
 * S4.4e 裁决⑥ 已全部升为 `DANGER`（执行前挂起、前端渲染确认卡片，FR-5.3）；
 * ② `open_export_file_editor`/`download_export_files` 曾被写成"唯一两个 FRONTEND 工具"，
 * S4.4d 起 `navigate_to`/`select_definitions`/`set_condition`/`confirm_step` 也是 FRONTEND，
 * DC-15 起 `generative_form` 亦然（前端接入已落地：GF-B 在 `stores/ai.ts` 对 `frontend_tool_request`
 * 特判 + AiPanel 的 `FormRenderer` 渲染）。
 * 增量执行兜底（DC-14 T1）：越 scope 的调用即使被上游发起，后端也会拦截并回灌结构化错误
 * （帧里 `tool_result.status=BLOCKED`）。
 */

import type { JobType } from './job'
import type { TaskType } from './task'

/** 工具风险等级（ToolMeta.RiskLevel）。 */
export type ToolRiskLevel = 'READ' | 'WRITE' | 'DANGER'

/** 工具执行通道（ToolMeta.Channel）：FRONTEND 的副作用在浏览器，后端方法体只是哨兵桩。 */
export type ToolChannel = 'BACKEND' | 'FRONTEND'

/**
 * 前端工具可用性三级（kimi 蓝本 utils/frontend-tools 的三级兜底，TS 化）：
 * - available：当前页面 handler 已注册，工具可真执行（打开编辑器/触发下载/页面跳转）；
 * - degraded：handler 缺失但存在降级路径（返回说明文本让模型改用后端工具或提示用户手点）；
 * - unavailable：当前页面根本不该出现该工具，或参数不合法（返回明确失败说明，不静默）；
 */
export type ToolAvailability = 'available' | 'degraded' | 'unavailable'

/** 工具参数声明（用于执行前校验与说明文本生成）。 */
export interface ToolArgSpec {
  name: string
  /** 与后端 @ToolParam 一致的基本类型（`string[]` = 后端 `String[]` 列表参数） */
  type: 'string' | 'number' | 'boolean' | 'string[]'
  required: boolean
  description: string
}

/** 前端工具描述（只描述前端工具；后端工具由后端自动披露）。 */
export interface FrontendToolDescriptor {
  name: FrontendToolName
  displayName: string
  description: string
  channel: 'FRONTEND'
  riskLevel: ToolRiskLevel
  /** @ToolScope 标签，决定在哪些页面/步骤可用 */
  scopePatterns: string[]
  args: ToolArgSpec[]
}

/** 前端工具名（与后端 @Tool(name=...) 严格同名）。 */
export type FrontendToolName = 'open_export_file_editor' | 'download_export_files'

/** 前端工具注册表。 */
export const FRONTEND_TOOLS: readonly FrontendToolDescriptor[] = [
  {
    name: 'open_export_file_editor',
    displayName: '打开导出文件在线编辑器',
    description: '请求前端打开指定配置的导出文件在线编辑器（SpreadJS）',
    channel: 'FRONTEND',
    riskLevel: 'READ',
    scopePatterns: ['task:EXPORT/EXPORT'],
    args: [
      { name: 'taskId', type: 'number', required: true, description: '任务ID' },
      { name: 'defCode', type: 'string', required: true, description: '配置定义编码' }
    ]
  },
  {
    name: 'download_export_files',
    displayName: '下载导出文件',
    description: '触发浏览器下载导出文件：1 个码 = 直出单文件 xlsx；≥2 个码 = 打包为单个 zip 下载',
    channel: 'FRONTEND',
    riskLevel: 'READ',
    scopePatterns: ['task:EXPORT/EXPORT'],
    args: [
      { name: 'taskId', type: 'number', required: true, description: '任务ID' },
      { name: 'defCodes', type: 'string[]', required: true, description: '配置定义编码列表（必填非空）' }
    ]
  }
] as const

/** 按名字取前端工具描述。 */
export function findFrontendTool(name: string): FrontendToolDescriptor | null {
  return FRONTEND_TOOLS.find((tool) => tool.name === name) ?? null
}

/**
 * 前端工具执行结局三态（批3③a「工具结局不得谎报」）：真实结局维度，与
 * `ToolAvailability`（可用性：available/degraded/unavailable）**正交** —— degraded 既可承载
 * 确定性失败（failed）也可承载"结局未知"（unknown）。
 * - ok：已确认成功（执行器给出成功结局 / handler 返回成功字符串）；
 * - failed：**确定性失败** —— 执行器 throw、handler 结构化 `{ok:false}`、契约层/守卫拒绝
 *   （有明确原因，可判定未完成，如 D2 点名的编辑器引擎加载失败）；
 * - unknown：**结局未知** —— 已发起/已受理但时限内无法确认真实结局（执行器预算到期、
 *   等待页面就绪超出上界）。回灌文本如实说"既未确认成功，也未确认失败"。
 */
export type FrontendToolOutcome = 'ok' | 'failed' | 'unknown'

/**
 * 前端工具的执行结果（回灌给后端的文本 + 本地痕迹）。
 *
 * 批3③a：`outcome` 是结局的权威维度；`ok` 语义收窄为 `ok === (outcome === 'ok')`
 * （预算到期等"结局未知"恒为 false），由 `makeExecution`（utils/frontend-tools.ts）单点推导。
 */
export interface FrontendToolExecution {
  /**
   * @deprecated 批3③a 起读 `outcome`；本字段仅兼容保留，由 makeExecution 依据 outcome 推导，
   * 不得再作为结局判据（`unknown ⇒ ok:false`）。
   */
  ok: boolean
  availability: ToolAvailability
  /** 执行结局（三态，批3③a 新增的真实结局维度）。 */
  outcome: FrontendToolOutcome
  /** 结局补充说明（unknown 的原因 / failed 的原因），可选。 */
  reason?: string
  /**
   * 回灌给模型的说明文本（POST /api/ai/frontend-tool-result 的 result）。
   * 结局信息走 `outcome` 字段而非文本编码（D11：不加 `[unknown]` 类机器前缀）。
   */
  result: string
}

// ── ui_event 协议（AI → 工作区，deepseek 蓝本 workspaceContract 的形态 + 版本号） ──

/** 契约版本号：AI 面板与工作区各自演进时用于兼容判定。 */
export const WORKSPACE_CONTRACT_VERSION = 1

/** 可被 AI 驱动的页面标识（与 ToolScope 的 page:* 标签一致）。 */
export type WorkspacePageId = 'tasks' | 'export' | 'import' | 'definitions' | 'data'

/** 一键恢复向导的目标（任务中心 → 向导）。 */
export type UiEvent =
  | { type: 'open_page'; page: WorkspacePageId }
  | { type: 'restore_task'; taskId: number; taskType?: TaskType }
  | { type: 'goto_step'; step: string }
  | { type: 'select_defs'; task: 'export' | 'import'; defCodes: string[] }
  | { type: 'set_conditions'; defCode: string; conditions: unknown }
  | { type: 'set_import_mode'; mode: 'MERGE' | 'REPLACE' }
  | { type: 'run_job'; jobType: JobType; taskId: number }
  | { type: 'cancel_job'; jobId: number }
  | { type: 'export_started'; taskId: number }
  | { type: 'import_batch'; batchId: number }
  | { type: 'import_action'; action: 'check' | 'import' | 'publish'; batchId?: number }
  | { type: 'download'; url: string; filename?: string }
  | { type: 'refresh_defs' }
  | { type: 'refresh_data'; defCode?: string }
  | { type: 'refresh_tasks' }

/** ui_event 的 type 白名单（运行时校验用）。 */
export const UI_EVENT_TYPES: readonly UiEvent['type'][] = [
  'open_page',
  'restore_task',
  'goto_step',
  'select_defs',
  'set_conditions',
  'set_import_mode',
  'run_job',
  'cancel_job',
  'export_started',
  'import_batch',
  'import_action',
  'download',
  'refresh_defs',
  'refresh_data',
  'refresh_tasks'
]

/** 工作区 → AI 的通知主题（AI 面板据此更新上下文 chip）。 */
export type WorkspaceTopic = 'workspace_changed' | 'ui_event_result'

/** 工作区动作来源（契约事件与 AI 上下文标注用）。 */
export type WorkspaceActionSource = '界面' | 'AI'

/**
 * 一次工作区动作记录（deepseek 蓝本 workspaceContract 的事件形态 + 版本号）。
 *
 * 两个用途：
 * 1. `workspace_changed` 事件的载荷 —— AI 面板据此显示"工作区已同步：<摘要>"；
 * 2. 下一轮 AI 请求的 `context.extra.recentActions` —— 让模型知道用户刚刚手点了什么
 *    （契约联动：工作区动作经契约层同步进 AI 上下文）。
 */
export interface WorkspaceActionRecord {
  /** 动作发生后的数据版本号（单调自增，与 dataVersion 同源） */
  version: number
  /** 中文动作摘要（展示文案，同时也是给模型看的一句话） */
  summary: string
  /** 时刻（epoch 毫秒） */
  at: number
  source: WorkspaceActionSource
}

/** 一轮对话最多携带的历史动作条数（只带最近若干条，避免上下文膨胀）。 */
export const WORKSPACE_ACTION_LIMIT = 5
