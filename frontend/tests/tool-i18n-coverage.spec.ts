/**
 * M7（工具用户侧 i18n）+ M-C（语义等价）：四份重复源收口后的契约。
 *
 * 证红证据（施工前）：`TOOL_LABELS` 是 `AiPanel.vue` 内的字面量 map、`tool-summaries.ts`
 * 模板是中文裸串 ⇒ 本文件的 `t('tool.<name>.title')` 全为 key 原文（缺键）、切 en 后摘要不变。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import i18n from '@/locales'
import { useLocaleStore } from '@/stores/locale'
import { toolSummary, toolStatusWord, toolStatusWords } from '@/utils/tool-summaries'
import type { ToolPart } from '@/types/ai'

/** 迁移前 `AiPanel.vue#TOOL_LABELS` 的 zh 快照（逐字，S2 收口后必须仍相等）。 */
const LEGACY_TITLES: Record<string, string> = {
  list_config_defs: '列出配置定义',
  get_config_def: '读取配置定义',
  list_tasks: '列出任务',
  get_workspace_state: '读取工作区状态',
  check_job_status: '查询作业状态',
  get_row_count: '预估数据行数',
  start_export: '启动导出作业',
  start_precheck: '启动预检查作业',
  start_import: '启动导入作业',
  start_publish: '启动发布作业',
  open_export_file_editor: '打开导出文件在线编辑器',
  download_export_files: '下载导出文件',
  navigate_to: '跳转页面',
  generative_form: '生成式表单',
  select_definitions: '选择配置项',
  set_condition: '设置查询条件',
  confirm_step: '确认步骤',
  read_sheet_summary: '读取表格摘要',
  aggregate_sheet: '聚合表格',
  inspect_columns: '诊断列映射',
  review_cells: '本地校验摘要',
  normalize_column: '列归一化',
  replace_values: '查找替换',
  update_rows: '条件更新行',
  undo_last_sheet_edit: '撤销 AI 表格修改',
  download_templates: '下载模板',
  'export.selectDefs': '选择配置项',
  'export.setConditions': '设置查询条件',
  'export.start': '启动导出作业',
  'export.openEditor': '打开在线编辑器',
  'import.selectDefs': '选择配置项',
  'import.upload': '上传文件',
  'import.startPrecheck': '启动预检查作业',
  'import.startImport': '启动导入作业',
  'import.startPublish': '启动发布作业'
}

/** 迁移前 `tool-summaries.ts#summaryFor` 的 zh 快照（逐字）。 */
const LEGACY_SUMMARIES: Array<[string, string, string]> = [
  ['list_config_defs', '{}', '列出配置定义'],
  ['list_config_defs', '{"level":"GLOBAL"}', '列出配置定义（GLOBAL 层）'],
  ['get_config_def', '{"code":"CURRENCY"}', '读取配置定义 CURRENCY 的完整字段'],
  ['list_tasks', '{}', '列出任务'],
  ['get_workspace_state', '{}', '读取工作区状态'],
  ['check_job_status', '{"jobId":3}', '查询作业状态（作业 #3）'],
  ['get_row_count', '{"defCode":"CURRENCY"}', '预估数据行数（CURRENCY）'],
  ['get_row_count', '{"defCode":"CURRENCY","scopeType":"GLOBAL"}', '预估数据行数（CURRENCY · GLOBAL）'],
  ['get_row_count', '{"scopeType":"GLOBAL"}', '预估数据行数（GLOBAL）'],
  ['get_row_count', '{}', '预估数据行数'],
  ['create_task', '{"type":"EXPORT","title":"月报"}', '创建导出任务「月报」'],
  ['create_task', '{"type":"IMPORT"}', '创建导入任务'],
  ['start_export', '{"taskId":7}', '启动导出作业（任务 #7）'],
  ['start_export', '{}', '启动导出作业'],
  ['start_precheck', '{"taskId":7}', '启动预检查作业（任务 #7）'],
  ['start_import', '{"taskId":7}', '启动导入作业（任务 #7）'],
  ['start_publish', '{"taskId":7}', '启动发布作业（任务 #7）'],
  ['navigate_to', '{"page":"data"}', '跳转页面（data）'],
  ['open_page', '{"page":"data"}', '跳转页面（data）'],
  ['restore_task', '{"taskId":9}', '恢复任务 #9'],
  ['goto_step', '{"step":"QUERY_COND"}', '跳转到步骤 QUERY_COND'],
  ['open_export_file_editor', '{}', '打开导出文件在线编辑器'],
  ['download_export_files', '{"defCodes":["A","B"]}', '下载导出文件（2 个配置项）'],
  ['download', '{"filename":"a.xlsx"}', '下载文件 a.xlsx'],
  ['generative_form', '{}', '生成式表单（待填写）'],
  ['select_definitions', '{"codes":["A"]}', '选择配置项（1 项）'],
  ['select_defs', '{"codes":["A","B"]}', '选择配置项（2 项）'],
  ['set_condition', '{}', '设置查询条件'],
  ['set_import_mode', '{"mode":"MERGE"}', '设置导入方式（MERGE）'],
  ['confirm_step', '{"step":"EXPORT"}', '确认步骤 EXPORT'],
  ['run_job', '{"taskId":4}', '启动作业（任务 #4）'],
  ['cancel_job', '{"jobId":8}', '取消作业 #8'],
  ['refresh_defs', '{}', '刷新配置定义列表'],
  ['refresh_data', '{}', '刷新配置数据'],
  ['refresh_tasks', '{}', '刷新任务列表'],
  ['read_sheet_summary', '{"defCode":"CURRENCY"}', '读取表格摘要（CURRENCY）'],
  ['aggregate_sheet', '{"groupBy":"level"}', '聚合表格（按 level 分组）'],
  ['inspect_columns', '{}', '诊断列映射'],
  ['review_cells', '{}', '本地校验摘要'],
  ['normalize_column', '{"rules":[{},{}],"commit":true}', '列归一化（2 条规则） · 待确认'],
  ['normalize_column', '{"rules":[{},{}]}', '列归一化（2 条规则） · 只出建议'],
  ['normalize_column', '{"commit":true}', '列归一化 · 待确认'],
  ['replace_values', '{"search":"甲","replace":"乙"}', '查找替换「甲」→「乙」'],
  ['update_rows', '{"set":[{"a":1},{"b":2}]}', '条件更新行（2 列）'],
  ['undo_last_sheet_edit', '{}', '撤销 AI 表格修改']
]

function part(name: string, args: string, status: ToolPart['status'] = 'running'): ToolPart {
  return { id: `tc-${name}-${args}`, name, args, status } as unknown as ToolPart
}

describe('工具文案 i18n（M7-1/M7-2）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useLocaleStore().setLocale('zh-CN')
  })

  it('M7-1 zh 标题逐字等于迁移前 TOOL_LABELS 快照', () => {
    for (const [name, expected] of Object.entries(LEGACY_TITLES)) {
      expect(i18n.global.t(`tool.${name}.title`), name).toBe(expected)
    }
  })

  it('M7-1b en 标题全部存在且与 zh 不同（中文名保留的 langZh 除外）', () => {
    for (const name of Object.keys(LEGACY_TITLES)) {
      const en = i18n.global.t(`tool.${name}.title`, {}, { locale: 'en-US' })
      expect(en, name).not.toBe(`tool.${name}.title`)
    }
  })

  it('M7-2 缺 key 时渲染工具名原文，不渲染 key 片段', () => {
    const fallback = i18n.global.t('tool.mystery_tool.title')
    expect(fallback).toBe('tool.mystery_tool.title')
    expect(toolSummary(part('mystery_tool', '{}', 'failed'))).toBe('mystery_tool · 失败')
  })

  it('状态词随语言切换，且 zh 与迁移前一致', () => {
    expect(toolStatusWord('pending')).toBe('待确认')
    expect(toolStatusWords().has('已跳过')).toBe(true)
    useLocaleStore().setLocale('en-US')
    expect(toolStatusWord('pending')).toBe('pending')
    expect(toolStatusWords().has('pending')).toBe(true)
  })

  it('en 下的摘要不是中文（模板已外置）', () => {
    useLocaleStore().setLocale('en-US')
    expect(toolSummary(part('start_export', '{"taskId":7}'))).toBe('Start export job (task #7)')
    expect(toolSummary(part('mystery_tool', '{}', 'failed'))).toBe('mystery_tool · failed')
  })
})

describe('工具摘要 zh 逐字快照（C1）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useLocaleStore().setLocale('zh-CN')
  })

  it.each(LEGACY_SUMMARIES)('%s %s → %s', (name, args, expected) => {
    expect(toolSummary(part(name, args))).toBe(expected)
  })
})
