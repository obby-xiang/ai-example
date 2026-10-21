/**
 * R-163 T0：工具卡一行摘要的**模板表**（决策卡 §三.6）—— 工具名 → 动宾模板
 * （args 确定性填充，前端静态生成，禁模型写文案）。
 *
 * i18n（S2 收口）：模板不再写死在 TS 里，全部落在 `locales/*.json` 的
 * `tool.<name>.<variant>`；本文件只保留**选哪个变体**的判定（参数有无决定变体）。
 * zh 值逐字等于迁移前模板（`tool-summary-parity.spec.ts` 快照钉死）。
 *
 * 全集来源（逐个核名，非凭猜）：
 * - 后端 @Tool（AiTools.java）：list_config_defs / get_config_def / list_tasks /
 *   get_workspace_state / check_job_status / get_row_count / create_task /
 *   start_export / start_precheck / start_import / start_publish / download_export_files /
 *   navigate_to / open_export_file_editor / generative_form / 8 个表格工具
 * - 前端工具（utils/frontend-tools.ts 执行器）：navigate_to（别名 open_page / restore_task /
 *   goto_step）、open_export_file_editor、download_export_files、generative_form、
 *   select_definitions（别名 select_defs）、set_condition（别名 set_conditions）、
 *   confirm_step、set_import_mode、run_job、cancel_job、download、
 *   refresh_defs / refresh_data / refresh_tasks
 */

import i18n from '@/locales'
import type { ToolPart, ToolRunStatus } from '@/types/ai'

/** 状态词全集（10 态）；兜底摘要与右状态标签共用同一份译文。 */
export const TOOL_STATUSES: readonly ToolRunStatus[] = [
  'pending',
  'running',
  'succeeded',
  'failed',
  'rejected',
  'blocked',
  'expired',
  'cancelled',
  'unknown',
  'skipped'
]

/** 当前语言下的状态词。 */
export function toolStatusWord(status: ToolRunStatus): string {
  return translate(`tool.status.${status}`)
}

/** 当前语言下的状态词集合（摘要位去重判据用；随语言变化 ⇒ 调用点须实时取）。 */
export function toolStatusWords(): Set<string> {
  return new Set(TOOL_STATUSES.map((status) => toolStatusWord(status)))
}

type ToolArgs = Record<string, unknown>

function translate(key: string, params?: Record<string, unknown>): string {
  return params ? i18n.global.t(key, params) : i18n.global.t(key)
}

/** 取译文；缺键（vue-i18n 回落为 key 本身）⇒ 返回 null，由调用方决定兜底。 */
function optional(key: string, params?: Record<string, unknown>): string | null {
  const value = translate(key, params)
  return value === key ? null : value
}

function str(args: ToolArgs, key: string): string {
  const value = args[key]
  return typeof value === 'string' && value ? value : ''
}

function num(args: ToolArgs, key: string): string {
  const value = args[key]
  return typeof value === 'number' ? String(value) : typeof value === 'string' && value ? value : ''
}

function taskTypeLabel(args: ToolArgs): string {
  const type = str(args, 'type').toUpperCase()
  return type === 'IMPORT' ? '导入' : type === 'EXPORT' ? '导出' : type
}

function toolArgsOf(part: ToolPart): ToolArgs {
  if (!part.args) {
    return {}
  }
  try {
    const parsed: unknown = JSON.parse(part.args)
    return parsed && typeof parsed === 'object' ? (parsed as ToolArgs) : {}
  } catch {
    return {}
  }
}

/**
 * 工具名 → 摘要模板。返回 null = 无静态映射（走兜底）。
 * 每个模板一行自然语言，参数缺失时省略该片段（不臆造）。
 *
 * 问题1 偏差修正批项 3（工具卡头重名）：卡头 mono 槽渲**工具中文名**（`tool.<name>.title`），而模板的
 * 首词常与它同义 ⇒ 卡头读作「启动导出作业　启动导出作业（任务 #12）」两段同义相邻。
 * 口径统一为：**模板以工具中文名打头**，接在名字后的是「参数信息」；渲染侧
 * （`AiPanel.toolSummaryOrNull`）按前缀剥掉名字 —— 剥后为空则不渲摘要位（只留工具名一行）。
 */
function summaryFor(name: string, args: ToolArgs): string | null {
  const taskId = num(args, 'taskId')
  const withTask = (): string | null => (taskId ? optional(`tool.${name}.withTask`, { taskId }) : null)
  switch (name) {
    case 'list_config_defs': {
      const level = str(args, 'level')
      return level ? optional('tool.list_config_defs.withLevel', { level }) : optional('tool.list_config_defs.summary')
    }
    case 'get_config_def': {
      const code = str(args, 'code')
      return code ? optional('tool.get_config_def.withCode', { code }) : optional('tool.get_config_def.summary')
    }
    case 'list_tasks':
      return optional('tool.list_tasks.summary')
    case 'get_workspace_state':
      return optional('tool.get_workspace_state.summary')
    case 'check_job_status': {
      const jobId = num(args, 'jobId')
      return jobId ? optional('tool.check_job_status.withJob', { jobId }) : optional('tool.check_job_status.summary')
    }
    case 'get_row_count': {
      const defCode = str(args, 'defCode')
      const scope = str(args, 'scopeType')
      if (defCode && scope) return optional('tool.get_row_count.withDefScope', { def: defCode, scope })
      if (defCode) return optional('tool.get_row_count.withDef', { def: defCode })
      if (scope) return optional('tool.get_row_count.withScope', { scope })
      return optional('tool.get_row_count.summary')
    }
    case 'create_task': {
      const label = taskTypeLabel(args)
      const title = str(args, 'title')
      return title
        ? optional('tool.create_task.withTitle', { type: label, title })
        : optional('tool.create_task.summary', { type: label })
    }
    case 'start_export':
      return withTask() ?? optional('tool.start_export.summary')
    case 'start_precheck':
      return withTask() ?? optional('tool.start_precheck.summary')
    case 'start_import':
      return withTask() ?? optional('tool.start_import.summary')
    case 'start_publish':
      return withTask() ?? optional('tool.start_publish.summary')
    case 'navigate_to':
    case 'open_page': {
      const page = str(args, 'page')
      return page ? optional(`tool.${name}.withPage`, { page }) : optional(`tool.${name}.summary`)
    }
    case 'restore_task':
      return taskId ? optional('tool.restore_task.withTask', { taskId }) : optional('tool.restore_task.summary')
    case 'goto_step': {
      const step = str(args, 'step')
      return step ? optional('tool.goto_step.withStep', { step }) : optional('tool.goto_step.summary')
    }
    case 'open_export_file_editor':
      return optional('tool.open_export_file_editor.summary')
    case 'download_export_files': {
      const codes = args['defCodes']
      const count = Array.isArray(codes) ? codes.length : 0
      return count > 0
        ? optional('tool.download_export_files.withCount', { count })
        : optional('tool.download_export_files.summary')
    }
    case 'download': {
      const filename = str(args, 'filename')
      return filename ? optional('tool.download.withName', { filename }) : optional('tool.download.summary')
    }
    case 'generative_form':
      return optional('tool.generative_form.summary')
    case 'select_definitions':
    case 'select_defs': {
      const codes = args['codes'] ?? args['defCodes']
      const count = Array.isArray(codes) ? codes.length : 0
      return count > 0 ? optional(`tool.${name}.withCount`, { count }) : optional(`tool.${name}.summary`)
    }
    case 'set_condition':
    case 'set_conditions':
      return optional(`tool.${name}.summary`)
    case 'set_import_mode': {
      const mode = str(args, 'mode')
      return mode ? optional('tool.set_import_mode.withMode', { mode }) : optional('tool.set_import_mode.summary')
    }
    case 'confirm_step': {
      const step = str(args, 'step')
      return step ? optional('tool.confirm_step.withStep', { step }) : optional('tool.confirm_step.summary')
    }
    case 'run_job':
      return withTask() ?? optional('tool.run_job.summary')
    case 'cancel_job': {
      const jobId = num(args, 'jobId')
      return jobId ? optional('tool.cancel_job.withJob', { jobId }) : optional('tool.cancel_job.summary')
    }
    case 'refresh_defs':
      return optional('tool.refresh_defs.summary')
    case 'refresh_data':
      return optional('tool.refresh_data.summary')
    case 'refresh_tasks':
      return optional('tool.refresh_tasks.summary')
    case 'read_sheet_summary': {
      const defCode = str(args, 'defCode')
      const scope = str(args, 'scope')
      if (defCode && scope) return optional('tool.read_sheet_summary.withDefScope', { def: defCode, scope })
      if (defCode) return optional('tool.read_sheet_summary.withDef', { def: defCode })
      if (scope) return optional('tool.read_sheet_summary.withScope', { scope })
      return optional('tool.read_sheet_summary.summary')
    }
    case 'aggregate_sheet': {
      const groupBy = str(args, 'groupBy')
      return groupBy
        ? optional('tool.aggregate_sheet.withGroup', { groupBy })
        : optional('tool.aggregate_sheet.summary')
    }
    case 'inspect_columns':
      return optional('tool.inspect_columns.summary')
    case 'review_cells':
      return optional('tool.review_cells.summary')
    case 'normalize_column': {
      const rules = args['rules']
      const count = Array.isArray(rules) ? rules.length : 0
      const commit = args['commit'] === true
      if (count > 0) {
        return optional(commit ? 'tool.normalize_column.withRulesPending' : 'tool.normalize_column.withRulesAdvisory', {
          count
        })
      }
      return optional(commit ? 'tool.normalize_column.pending' : 'tool.normalize_column.advisory')
    }
    case 'replace_values': {
      const search = str(args, 'search')
      const replace = str(args, 'replace')
      return search ? optional('tool.replace_values.withSearch', { search, replace }) : optional('tool.replace_values.summary')
    }
    case 'update_rows': {
      const set = args['set']
      const count = Array.isArray(set) ? set.length : 0
      return count > 0 ? optional('tool.update_rows.withCols', { count }) : optional('tool.update_rows.summary')
    }
    case 'undo_last_sheet_edit':
      return optional('tool.undo_last_sheet_edit.summary')
    default:
      return null
  }
}

/**
 * 工具卡折叠态一行摘要（T0 交付）。已决失败/未知态也走具体模板（args 仍是事实），
 * 无映射工具与解析失败态兜底「工具名 + 状态词」。
 */
export function toolSummary(part: ToolPart): string {
  const args = toolArgsOf(part)
  const mapped = summaryFor(part.name, args)
  if (mapped) {
    return mapped
  }
  return translate('tool.fallback', { name: part.name, status: toolStatusWord(part.status) })
}
