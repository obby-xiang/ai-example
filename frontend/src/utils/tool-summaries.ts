/**
 * R-163 T0：工具卡一行摘要的**静态映射表**（决策卡 §三.6）——
 * 工具名 → 中文动宾模板（args 确定性填充，前端静态生成，禁模型写文案）；
 * 失败/未知兜底 = 工具名 + 状态词。全集来源（逐个核名，非凭猜）：
 * - 后端 @Tool（AiTools.java）：list_config_defs / get_config_def / list_tasks /
 *   get_workspace_state / check_job_status / get_row_count / create_task /
 *   start_export / start_precheck / start_import / start_publish / download_export_files /
 *   navigate_to / open_export_file_editor / generative_form
 * - 前端工具（utils/frontend-tools.ts 执行器）：navigate_to（别名 open_page / restore_task /
 *   goto_step）、open_export_file_editor、download_export_files、generative_form、
 *   select_definitions（别名 select_defs）、set_condition（别名 set_conditions）、
 *   confirm_step、set_import_mode、run_job、cancel_job、download、
 *   refresh_defs / refresh_data / refresh_tasks
 */

import type { ToolPart, ToolRunStatus } from '@/types/ai'

/** 状态词表（10 态，与 AiPanel toolStatusLabel 同口径的简短版，供兜底行）。
 *  问题1 偏差修正批项 3：导出给面板的摘要去重判据用 —— 无映射工具的兜底摘要按前缀剥掉工具名后
 *  只剩状态词，而状态词已由同屏的状态胶囊承担 ⇒ 摘要位不再重复渲一次。 */
export const TOOL_SUMMARY_STATUS_WORDS: Record<ToolRunStatus, string> = {
  pending: '等待确认',
  running: '调用中',
  succeeded: '已完成',
  failed: '失败',
  rejected: '已拒绝',
  blocked: '超出范围',
  expired: '已失效',
  cancelled: '已取消',
  unknown: '结局未知',
  skipped: '已跳过'
}

type ToolArgs = Record<string, unknown>

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
 * 问题1 偏差修正批项 3（工具卡头重名）：卡头 mono 槽渲**工具中文名**（`TOOL_LABELS`），而模板的
 * 首词常与它同义 ⇒ 卡头读作「启动导出作业　启动导出作业（任务 #12）」两段同义相邻。
 * 口径统一为：**模板以工具中文名打头**，接在名字后的是「参数信息」；渲染侧
 * （`AiPanel.toolSummaryOrNull`）按前缀剥掉名字 —— 剥后为空则不渲摘要位（只留工具名一行）。
 * 本文件里打头的措辞因此逐条改写成自己标签（如 `列出任务` / `读取工作区状态` / `预估数据行数`），
 * 名字不再由模板重复承担（demo 样张里 `.tname` 渲英文工具名、`.tsum` 渲参数摘要，同一分工）。
 */
function summaryFor(name: string, args: ToolArgs): string | null {
  const taskId = num(args, 'taskId')
  const taskSuffix = taskId ? `（任务 #${taskId}）` : ''
  switch (name) {
    case 'list_config_defs': {
      const level = str(args, 'level')
      return level ? `列出配置定义（${level} 层）` : '列出配置定义'
    }
    case 'get_config_def': {
      const code = str(args, 'code')
      return code ? `读取配置定义 ${code} 的完整字段` : '读取配置定义'
    }
    case 'list_tasks':
      return '列出任务'
    case 'get_workspace_state':
      return '读取工作区状态'
    case 'check_job_status': {
      const jobId = num(args, 'jobId')
      return jobId ? `查询作业状态（作业 #${jobId}）` : '查询作业状态'
    }
    case 'get_row_count': {
      const defCode = str(args, 'defCode')
      const scope = str(args, 'scopeType')
      return `预估数据行数${defCode ? `（${defCode}${scope ? ` · ${scope}` : ''}）` : scope ? `（${scope}）` : ''}`
    }
    case 'create_task': {
      const label = taskTypeLabel(args)
      const title = str(args, 'title')
      return `创建${label || ''}任务${title ? `「${title}」` : ''}`
    }
    case 'start_export':
      return `启动导出作业${taskSuffix}`
    case 'start_precheck':
      return `启动预检查作业${taskSuffix}`
    case 'start_import':
      return `启动导入作业${taskSuffix}`
    case 'start_publish':
      return `启动发布作业${taskSuffix}`
    case 'navigate_to':
    case 'open_page': {
      const page = str(args, 'page')
      return page ? `跳转页面（${page}）` : '跳转页面'
    }
    case 'restore_task':
      return taskId ? `恢复任务 #${taskId}` : '恢复任务'
    case 'goto_step': {
      const step = str(args, 'step')
      return step ? `跳转到步骤 ${step}` : '跳转向导步骤'
    }
    case 'open_export_file_editor':
      return '打开导出文件在线编辑器'
    case 'download_export_files': {
      const codes = args['defCodes']
      const count = Array.isArray(codes) ? codes.length : 0
      return `下载导出文件${count > 0 ? `（${count} 个配置项）` : ''}`
    }
    case 'download': {
      const filename = str(args, 'filename')
      return filename ? `下载文件 ${filename}` : '下载文件'
    }
    case 'generative_form':
      return '生成式表单（待填写）'
    case 'select_definitions':
    case 'select_defs': {
      const codes = args['codes'] ?? args['defCodes']
      const count = Array.isArray(codes) ? codes.length : 0
      return `选择配置项${count > 0 ? `（${count} 项）` : ''}`
    }
    case 'set_condition':
    case 'set_conditions':
      return '设置查询条件'
    case 'set_import_mode': {
      const mode = str(args, 'mode')
      return mode ? `设置导入方式（${mode}）` : '设置导入方式'
    }
    case 'confirm_step': {
      const step = str(args, 'step')
      return step ? `确认步骤 ${step}` : '确认当前步骤'
    }
    case 'run_job':
      return taskId ? `启动作业（任务 #${taskId}）` : '启动作业'
    case 'cancel_job': {
      const jobId = num(args, 'jobId')
      return jobId ? `取消作业 #${jobId}` : '取消作业'
    }
    case 'refresh_defs':
      return '刷新配置定义列表'
    case 'refresh_data':
      return '刷新配置数据'
    case 'refresh_tasks':
      return '刷新任务列表'
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
  return `${part.name} · ${TOOL_SUMMARY_STATUS_WORDS[part.status] ?? part.status}`
}
