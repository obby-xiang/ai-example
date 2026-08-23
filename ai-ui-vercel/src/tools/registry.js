/**
 * 工具 UI 元数据注册表 —— 对齐 ai-service FrontendTools.summarizeImpact / AiService.toolTitle。
 * 供 AiPanel 决定工具卡片渲染形态:标题、影响描述、标签类型、信任分级。
 *
 * 信任分级(业界 Cursor Composer / Claude Code 模式):
 *   autoExec=true            - 自动执行,无需用户点击(只读/导航/选择类)
 *   needConfirm=true         - 暂停等待用户确认(破坏性/不可逆)
 *   requireDoubleConfirm=true - 二次确认弹框(高危流程如 run_flow)
 *   inputMode=true           - 暂停渲染表单收集用户输入(collect_user_input)
 */

export const TOOL_REGISTRY = {
  navigate_step: {
    title: '跳转步骤', labelType: 'primary', labelText: '跳转',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `跳转到 ${a.step || ''}${a.scenario ? '(场景:' + a.scenario + ')' : ''}`
  },
  get_workspace_state: {
    title: '查询工作区', labelType: 'info', labelText: '查询',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: () => '查询当前工作区状态(只读)'
  },
  list_config_defs: {
    title: '列出配置项', labelType: 'info', labelText: '列表',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: () => '列出配置定义摘要(只读)'
  },
  get_config_def: {
    title: '配置项详情', labelType: 'info', labelText: '详情',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `查询配置定义 #${a.defId} 字段详情(只读)`
  },
  select_definitions: {
    title: '选择配置项', labelType: 'success', labelText: '配置项',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `${a.mode || 'set'} 模式更新配置项选择,涉及 ${(a.defIds || []).length} 项`
  },
  confirm_complete: {
    title: '完成', labelType: 'info', labelText: '完成',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `完成${a.target === 'FINISH_TASK' ? '任务' : '当前步骤,进入下一步'}`
  },
  table_batch_set_field: {
    title: '批量修改字段', labelType: 'danger', labelText: '表格更新',
    autoExec: false, needConfirm: true, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `在配置 #${a.configDefId} 中按条件批量修改字段为 ${JSON.stringify(a.set || {})}`
  },
  table_delete_rows: {
    title: '删除行', labelType: 'danger', labelText: '表格删除',
    autoExec: false, needConfirm: true, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `在配置 #${a.configDefId} 中删除满足条件的行`
  },
  table_replace_values: {
    title: '替换值', labelType: 'warning', labelText: '替换',
    autoExec: false, needConfirm: true, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `在配置 #${a.configDefId} 中把 ${a.field} 字段的 "${a.search}" 替换为 "${a.replace}"`
  },
  run_flow: {
    title: '执行流程', labelType: 'warning', labelText: '流程',
    autoExec: false, needConfirm: true, requireDoubleConfirm: true, inputMode: false,
    impact: (a) => `一键执行 ${a.flowType || ''} 流程(高危,不可撤销)`
  },
  collect_user_input: {
    title: '收集输入', labelType: 'primary', labelText: '表单',
    autoExec: false, needConfirm: false, requireDoubleConfirm: false, inputMode: true,
    impact: (a) => `将向用户收集 ${(a.fields || []).length} 个表单字段`
  },
  excel_import: {
    title: 'Excel 导入', labelType: 'success', labelText: 'Excel 导入',
    autoExec: false, needConfirm: false, requireDoubleConfirm: false, inputMode: true,
    impact: (a) => `上传 Excel 文件并解析灌入到配置 #${a.configDefId}${a.mode === 'merge' ? '(merge 合并)' : '(append 追加)'}`
  },
  excel_export: {
    title: 'Excel 导出', labelType: 'info', labelText: 'Excel 导出',
    autoExec: true, needConfirm: false, requireDoubleConfirm: false, inputMode: false,
    impact: (a) => `导出 ${Array.isArray(a.configDefIds) && a.configDefIds.length ? a.configDefIds.length + ' 个' : '当前已选/全部'}配置项为 xlsx 并下载`
  }
}

export function getToolMeta(toolName) {
  return TOOL_REGISTRY[toolName] || null
}

export function isAutoTool(toolName) {
  const m = getToolMeta(toolName)
  return !!(m && m.autoExec)
}

export function isInputTool(toolName) {
  const m = getToolMeta(toolName)
  return !!(m && m.inputMode)
}

export function isConfirmTool(toolName) {
  const m = getToolMeta(toolName)
  return !!(m && m.needConfirm)
}

export function toolTitle(toolName) {
  return getToolMeta(toolName)?.title || toolName
}

export function toolImpact(toolName, args = {}) {
  const m = getToolMeta(toolName)
  if (!m || !m.impact) return ''
  try { return m.impact(args) || '' } catch (e) { return '' }
}
