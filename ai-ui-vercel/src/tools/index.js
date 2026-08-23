/**
 * 工具定义(前端 AI runtime 核心)—— 用 Vercel AI SDK 的 tool() 构造。
 * 每个 tool 带 execute,在浏览器内执行。对齐 ai-service tools.yaml + FrontendTools.java。
 *
 * 暂停-恢复范式:
 *   - 确认类工具(table_batch_set_field/table_delete_rows/table_replace_values/run_flow):
 *     execute 调 waitForConfirm,暂停等用户确认/取消
 *   - 表单类工具(collect_user_input):execute 调 waitForForm,暂停等用户提交
 *   - 自动类工具(navigate_step/list/get/select/confirm):execute 直接执行返回
 *
 * execute 的返回值会被 SDK 自动回灌给模型(作为 tool result),模型据此续行。
 */
import { tool } from 'ai'
import { z } from 'zod'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import router from '@/router'
import { stepToRoute } from '@/router'
import { ElMessage } from 'element-plus'
import { waitForConfirm, waitForForm } from './runtime'
import { toolImpact } from './registry'
import { importExcel, exportExcelByRows, exportExcelMultiSheet } from '@/utils/excel-io'

const SCENARIO_NAME = {
  EXPORT: '导出配置', IMPORT: '导入配置', ADD: '新增配置', MODIFY: '修改配置'
}

function fireAuto(ev) {
  if (typeof window.__triggerAiAutoPrompt === 'function') {
    setTimeout(() => window.__triggerAiAutoPrompt(ev), 200)
  }
}

function collectWorkspaceState() {
  if (typeof window.__collectWorkspaceState === 'function') {
    return window.__collectWorkspaceState()
  }
  return {}
}

// ===================== 自动执行类工具 =====================

export const navigate_step = tool({
  description: '跳转工作区向导到某个步骤,可选先切换场景。不要在内容里输出 JSON,必须用本工具返回。',
  parameters: z.object({
    step: z.enum(['SELECT_SCENARIO', 'SELECT_DEFS', 'VIEW_DEFS', 'QUERY_COND', 'PRECHECK', 'REVIEW', 'PUBLISH', 'RESULT', 'DASHBOARD']).describe('目标步骤代码'),
    scenario: z.enum(['EXPORT', 'IMPORT', 'ADD', 'MODIFY']).optional().describe('若尚未选场景,需在此指定场景代码;已选场景则可省略')
  }),
  execute: async ({ step, scenario }) => {
    const taskStore = useTaskStore()
    if (scenario) {
      if (!taskStore.currentTask) await taskStore.createTask()
      if (taskStore.currentTask?.scenario !== scenario) {
        await taskStore.selectScenario(scenario)
        ElMessage.success('已切换场景:' + (SCENARIO_NAME[scenario] || scenario))
      }
    }
    if (step) {
      if (!taskStore.currentTask) await taskStore.createTask()
      // 跨场景跳步防护(进度条 bug 根因)
      if (step !== 'SELECT_SCENARIO' && !taskStore.scenario && !scenario) {
        return { ok: false, message: '当前任务尚未选择场景,无法跳转到 ' + step + '。请先选择场景。' }
      }
      await taskStore.gotoStep(step)
    }
    const target = stepToRoute(taskStore.currentStep)
    if (target && target !== '/dashboard') await router.push(target)
    fireAuto(scenario ? 'select_scenario' : 'enter_step')
    return { ok: true, message: '已跳转到 ' + step + (scenario ? '(场景:' + SCENARIO_NAME[scenario] + ')' : ''), currentStep: taskStore.currentStep }
  }
})

export const get_workspace_state = tool({
  description: '查询当前工作区状态(场景/步骤/已选配置项等)。只读,不修改任何数据。',
  parameters: z.object({}),
  execute: async () => collectWorkspaceState()
})

export const list_config_defs = tool({
  description: '列出所有配置定义摘要(id/code/name/fieldCount)。只读,不返回字段详情,按需调 get_config_def 获取字段。',
  parameters: z.object({}),
  execute: async () => {
    const configStore = useConfigStore()
    return configStore.enabledDefinitions.map(d => ({
      id: d.id, code: d.code, name: d.name, fieldCount: (d.columns || []).length
    }))
  }
})

export const get_config_def = tool({
  description: '按 defId 查询单个配置定义的字段详情(key/type/label/defaultValue)。需要字段信息时调用,避免凭空猜测字段名。',
  parameters: z.object({
    defId: z.number().int().describe('配置定义 ID(来自 list_config_defs 返回的 id)')
  }),
  execute: async ({ defId }) => {
    const configStore = useConfigStore()
    const d = configStore.defById(defId)
    if (!d) return { ok: false, message: '未找到配置定义 #' + defId }
    return {
      id: d.id, code: d.code, name: d.name,
      fields: (d.columns || []).map(c => ({
        key: c.key, type: c.type, label: c.label,
        defaultValue: c.defaultValue, options: c.options, required: c.required
      }))
    }
  }
})

export const select_definitions = tool({
  description: '批量选择或取消配置项。模式 add 为增量添加, remove 为移除, set 为覆盖式设置。',
  parameters: z.object({
    mode: z.enum(['add', 'remove', 'set']).describe('操作模式'),
    defIds: z.array(z.number().int()).describe('要操作的配置定义 ID 数组,不能传 code/name,必须为整数 ID')
  }),
  execute: async ({ mode, defIds }) => {
    const taskStore = useTaskStore()
    const configStore = useConfigStore()
    let next = taskStore.selectedDefIds.slice()
    const ids = (defIds || []).map(x => Number(x)).filter(x => Number.isFinite(x) && x > 0)
    if (mode === 'set') next = ids.slice()
    else if (mode === 'remove') next = next.filter(x => !ids.includes(x))
    else next = Array.from(new Set([...next, ...ids]))
    const enabledIds = configStore.enabledDefinitions.map(d => d.id)
    next = next.filter(id => enabledIds.includes(id))
    await taskStore.updateSelectedDefs(next)
    fireAuto('select_defs')
    return { ok: true, message: '已更新配置项选择, 共 ' + next.length + ' 项', selectedCount: next.length }
  }
})

export const confirm_complete = tool({
  description: '当前步骤所有工作完成,引导用户确认后结束流程或跳到下一步。',
  parameters: z.object({
    target: z.enum(['FINISH_TASK', 'NEXT_STEP']).describe('完成方式')
  }),
  execute: async ({ target }) => {
    const taskStore = useTaskStore()
    if (target === 'FINISH_TASK') {
      await taskStore.complete()
      return { ok: true, message: '任务已完成!' }
    }
    // NEXT_STEP: 推进到状态图下一步
    const steps = taskStore.steps
    const idx = steps.indexOf(taskStore.currentStep)
    const nextStep = steps[idx + 1]
    if (nextStep) {
      await taskStore.gotoStep(nextStep)
      const target = stepToRoute(taskStore.currentStep)
      if (target && target !== '/dashboard') await router.push(target)
      fireAuto('enter_step')
      return { ok: true, message: '已进入下一步: ' + nextStep, currentStep: taskStore.currentStep }
    }
    await taskStore.complete()
    return { ok: true, message: '已是最后一步,任务已完成!' }
  }
})

// ===================== 确认类工具(暂停等用户确认) =====================

export const table_batch_set_field = tool({
  description: '在当前编辑的某张配置表里,按 where 条件匹配行,把 set 对象里的字段批量修改为新值。这是破坏性写操作,需用户确认。',
  parameters: z.object({
    configDefId: z.number().int().describe('要修改的配置定义 ID'),
    where: z.object({
      field: z.string().describe('条件字段 key'),
      op: z.enum(['eq', 'ne', 'lt', 'le', 'gt', 'ge', 'contains', 'starts_with', 'ends_with']).describe('比较操作符'),
      value: z.any().describe('与字段类型匹配的值(数字/字符串/布尔)')
    }).optional().describe('匹配条件;为空或缺失表示全表更新(风险极高)'),
    set: z.record(z.any()).describe('要更新的 {字段: 新值},字段必须存在于该配置定义中')
  }),
  execute: async (args, { toolCallId } = {}) => {
    const impact = toolImpact('table_batch_set_field', args)
    const decision = await waitForConfirm(toolCallId, 'table_batch_set_field', args, impact)
    if (!decision || !decision.confirmed) {
      return { ok: false, message: 'user_cancelled', reason: '用户取消了执行' }
    }
    const configStore = useConfigStore()
    const defId = Number(args.configDefId)
    const res = await configStore.applyOperation(defId, 'update_where', {
      where: args.where, set: args.set
    })
    refreshSheet(defId)
    fireAuto('data_change')
    return { ok: true, message: '操作成功, 影响 ' + (res.affected || 0) + ' 行', affected: res.affected }
  }
})

export const table_delete_rows = tool({
  description: '删除表格指定范围或满足条件的行。破坏性操作,需用户确认。',
  parameters: z.object({
    configDefId: z.number().int().describe('配置定义 ID'),
    fromRow: z.number().int().optional().describe('从第 fromRow 行开始删除(1 基,表头算第 0 行,数据首行=1)'),
    toRow: z.number().int().optional().describe('删除到第 toRow 行(含);缺失表示删到末尾'),
    where: z.object({
      field: z.string(),
      op: z.enum(['eq', 'ne', 'lt', 'le', 'gt', 'ge']),
      value: z.any()
    }).optional().describe('当 fromRow/toRow 缺失时使用条件删除')
  }),
  execute: async (args, { toolCallId } = {}) => {
    const impact = toolImpact('table_delete_rows', args)
    const decision = await waitForConfirm(toolCallId, 'table_delete_rows', args, impact)
    if (!decision || !decision.confirmed) {
      return { ok: false, message: 'user_cancelled', reason: '用户取消了执行' }
    }
    const configStore = useConfigStore()
    const defId = Number(args.configDefId)
    const res = await configStore.applyOperation(defId, 'delete_rows', {
      fromRow: args.fromRow, toRow: args.toRow, where: args.where
    })
    refreshSheet(defId)
    fireAuto('data_change')
    return { ok: true, message: '操作成功, 影响 ' + (res.affected || 0) + ' 行', affected: res.affected }
  }
})

export const table_replace_values = tool({
  description: '将某个字段中搜索到的值替换为新值(支持字面量/正则)。',
  parameters: z.object({
    configDefId: z.number().int().describe('配置定义 ID'),
    field: z.string().describe('字段 key'),
    search: z.string().describe('搜索串'),
    replace: z.string().describe('替换为(字面量或正则 $1 捕获组)'),
    regex: z.boolean().optional().describe('是否把 search 当作正则表达式')
  }),
  execute: async (args, { toolCallId } = {}) => {
    const impact = toolImpact('table_replace_values', args)
    const decision = await waitForConfirm(toolCallId, 'table_replace_values', args, impact)
    if (!decision || !decision.confirmed) {
      return { ok: false, message: 'user_cancelled', reason: '用户取消了执行' }
    }
    const configStore = useConfigStore()
    const defId = Number(args.configDefId)
    const res = await configStore.applyOperation(defId, 'replace', {
      field: args.field, search: args.search, replace: args.replace, regex: !!args.regex
    })
    refreshSheet(defId)
    fireAuto('data_change')
    return { ok: true, message: '操作成功, 影响 ' + (res.affected || 0) + ' 行', affected: res.affected }
  }
})

// ===================== 高危流程类(二次确认) =====================

export const run_flow = tool({
  description: '一键走完一个业务流程(导出单个、导出全部、发布等)。对于大批量/写操作需用户确认。',
  parameters: z.object({
    flowType: z.enum(['export_single', 'export_all', 'import_commit', 'add_commit', 'modify_commit']).describe('流程类型'),
    defIds: z.array(z.number().int()).optional().describe('export_single 也可用长度 1 的数组'),
    defId: z.number().int().optional().describe('仅 export_single 时使用;优先 defId')
  }),
  execute: async (args, { toolCallId } = {}) => {
    const impact = toolImpact('run_flow', args)
    const decision = await waitForConfirm(toolCallId, 'run_flow', args, impact)
    if (!decision || !decision.confirmed) {
      return { ok: false, message: 'user_cancelled', reason: '用户取消了执行' }
    }
    const taskStore = useTaskStore()
    const configStore = useConfigStore()
    const { flowType } = args
    if (flowType === 'export_single' || flowType === 'export_all') {
      if (!taskStore.currentTask) await taskStore.createTask('AI-导出任务')
      if (taskStore.currentTask?.scenario !== 'EXPORT') {
        await taskStore.selectScenario('EXPORT')
      }
      let defIds = Array.isArray(args.defIds) ? args.defIds.map(Number) : []
      if (args.defId !== undefined && args.defId !== null) defIds = [Number(args.defId)]
      if (flowType === 'export_all') {
        defIds = configStore.enabledDefinitions.map(d => d.id)
      }
      defIds = defIds.filter(id => configStore.defById(id))
      if (!defIds.length) return { ok: false, message: '没有可导出的配置项' }
      await taskStore.updateSelectedDefs(defIds)
      await taskStore.gotoStep('QUERY_COND')
      await taskStore.saveStepData('QUERY_COND', { mode: 'all' })
      // 生成导出结果
      const items = []
      for (const id of defIds) {
        const d = configStore.defById(id)
        try {
          const cnt = await configStore.count(id)
          items.push({ defId: id, code: d?.code, name: d?.name, total: cnt.total })
        } catch (e) { /* ignore */ }
      }
      await taskStore.saveStepData('RESULT', { items, type: 'export', at: Date.now() })
      await taskStore.gotoStep('RESULT')
      const route = stepToRoute('RESULT')
      if (route) await router.push(route)
      fireAuto('flow_export')
      return { ok: true, message: '已完成导出流程,共 ' + defIds.length + ' 个配置项', items }
    }
    return { ok: false, message: '暂未支持该流程:' + flowType }
  }
})

// ===================== 用户输入收集类(暂停渲染表单) =====================

export const collect_user_input = tool({
  description: '向用户收集结构化输入(表单)。当需要用户提供具体参数(如导出文件名、查询条件值、选择项、配置项 code/name 等)时调用,会暂停等待用户填写并提交。fields 数组定义表单字段,可一次收集多个字段(单轮表单)或单个字段(多轮问答),由你根据需要决定。',
  parameters: z.object({
    formTitle: z.string().describe('表单标题(展示给用户)'),
    submitLabel: z.string().optional().describe('提交按钮文字'),
    fields: z.array(z.object({
      key: z.string().describe('字段标识(回灌结果 data 中的 key)'),
      label: z.string().describe('显示标签'),
      type: z.enum(['text', 'textarea', 'number', 'boolean', 'single_select', 'multi_select', 'button_group', 'date']).describe('控件类型'),
      required: z.boolean().optional(),
      default: z.any().optional().describe('默认值(类型与字段 type 匹配)'),
      placeholder: z.string().optional(),
      description: z.string().optional(),
      options: z.array(z.object({ label: z.string(), value: z.any() })).optional().describe('选项列表(single_select/multi_select/button_group 必填)'),
      min: z.number().optional(),
      max: z.number().optional(),
      pattern: z.string().optional().describe('正则校验(text/textarea)')
    })).min(1).describe('字段定义数组,每个字段描述一个输入控件')
  }),
  execute: async (args, { toolCallId } = {}) => {
    const result = await waitForForm(toolCallId, args)
    if (!result || result.cancelled) {
      return { ok: false, message: 'user_cancelled', reason: '用户取消了输入' }
    }
    return { ok: true, message: '已收集用户输入', data: result.data }
  }
})

// ===================== Excel 导入/导出类工具 =====================

// excel_import: 复用 collect_user_input 的 form 暂停机制,固定 fields=[{type:'file'}] 等用户上传。
// 依赖 SchemaFormRenderer 新增的 file 渲染分支(el-upload)。
export const excel_import = tool({
  description: '上传 Excel(.xlsx)文件,解析后灌入到指定配置定义。会暂停等待用户上传文件。导入前请确认已用「下载 Excel 模板」按钮下载模板填写,保证表头与配置字段一致。导入模式: append 追加(默认), merge 按 id 合并更新。',
  parameters: z.object({
    configDefId: z.number().int().describe('要导入到的配置定义 ID(来自 list_config_defs)'),
    mode: z.enum(['append', 'merge']).optional().describe('导入模式: append 追加(默认), merge 按 id 合并更新')
  }),
  execute: async (args, { toolCallId } = {}) => {
    const configStore = useConfigStore()
    const defId = Number(args.configDefId)
    const def = configStore.defById(defId)
    if (!def) return { ok: false, message: '未找到配置定义 #' + defId }
    // 暂停渲染上传表单等用户提交(复用 waitForForm)
    const formArgs = {
      formTitle: '上传 Excel 导入到「' + def.name + '」',
      submitLabel: '开始导入',
      fields: [{
        key: 'file',
        label: 'Excel 文件',
        type: 'file',
        required: true,
        accept: '.xlsx,.xls',
        description: '请先下载 Excel 模板填写,保证表头与配置字段一致'
      }]
    }
    const result = await waitForForm(toolCallId, formArgs, 'excel_import')
    if (!result || result.cancelled) {
      return { ok: false, message: 'user_cancelled', reason: '用户取消了上传' }
    }
    const raw = result.data?.file
    // el-upload auto-upload=false 时 on-change 存的是 raw(File 对象)
    const file = raw instanceof File ? raw : (raw?.raw || raw)
    if (!(file instanceof File)) {
      return { ok: false, message: '未收到有效的文件对象' }
    }
    const mode = args.mode || 'append'
    let imp
    try {
      imp = await importExcel(file, def)
    } catch (e) {
      return { ok: false, message: 'Excel 解析失败:' + (e?.message || e) }
    }
    if (imp.errors && imp.errors.length) {
      return { ok: false, message: '导入校验失败,共 ' + imp.errors.length + ' 个错误', errors: imp.errors.slice(0, 20), stats: imp.stats }
    }
    if (!imp.rows.length) {
      return { ok: false, message: 'Excel 中没有可导入的数据行', stats: imp.stats }
    }
    const res = await configStore.batchSave(defId, imp.rows, mode)
    refreshSheet(defId)
    fireAuto('data_change')
    return { ok: true, message: '已导入 ' + imp.rows.length + ' 行,当前共 ' + res.total + ' 行', imported: imp.rows.length, total: res.total, stats: imp.stats }
  }
})

// excel_export: 直接下载 xlsx 文件,不修改数据。autoExec 类只读导出。
export const excel_export = tool({
  description: '导出配置数据为 Excel(.xlsx)文件并下载。可指定单个或多个配置定义 ID;空则导出当前任务已选配置项(selectedDefIds),再空则导出全部启用配置项。多个配置项会合并为多 sheet 工作簿。',
  parameters: z.object({
    configDefIds: z.array(z.number().int()).optional().describe('要导出的配置定义 ID 数组;空则导出当前任务 selectedDefIds 全部'),
    fileName: z.string().optional().describe('导出文件名(不含扩展名,自动追加 -时间戳.xlsx)')
  }),
  execute: async (args) => {
    const taskStore = useTaskStore()
    const configStore = useConfigStore()
    let ids = Array.isArray(args.configDefIds) ? args.configDefIds.map(Number) : []
    if (!ids.length) ids = (taskStore.selectedDefIds || []).map(Number)
    if (!ids.length) ids = configStore.enabledDefinitions.map(d => d.id)
    ids = ids.filter(id => configStore.defById(id))
    if (!ids.length) return { ok: false, message: '没有可导出的配置项' }
    const defsAndRows = []
    for (const id of ids) {
      const def = configStore.defById(id)
      const rows = (await configStore.allData(id, 100000))
        .map(r => ({ id: r.id, data: r.rowData, mark: 'unchanged' }))
      defsAndRows.push({ def, rows })
    }
    const fn = (args.fileName || 'export') + '-' + Date.now() + '.xlsx'
    try {
      if (defsAndRows.length === 1) {
        await exportExcelByRows(defsAndRows[0].def, defsAndRows[0].rows, fn)
      } else {
        await exportExcelMultiSheet(defsAndRows, fn)
      }
    } catch (e) {
      return { ok: false, message: '导出失败:' + (e?.message || e) }
    }
    return { ok: true, message: '已导出 ' + defsAndRows.length + ' 个配置项到 Excel', fileName: fn, sheetCount: defsAndRows.length }
  }
})

// ===================== 工具集合(按场景+步骤动态过滤) =====================

import { SCENARIO_STEPS } from '@/stores/task'

/**
 * 按场景+步骤过滤工具集(progressive disclosure)。
 * 对齐 scenarios.yaml 的 steps.tools 列表。
 * 返回 Vercel AI SDK 的 tools 对象(只含当前步骤允许的工具)。
 */
export function getToolsForStep(scenario, step) {
  const all = {
    navigate_step, get_workspace_state, list_config_defs, get_config_def,
    select_definitions, confirm_complete,
    table_batch_set_field, table_delete_rows, table_replace_values,
    run_flow, collect_user_input,
    excel_import, excel_export
  }
  if (!scenario || !step) {
    // 无场景:只暴露场景选择相关工具
    return { navigate_step, get_workspace_state, collect_user_input }
  }
  const allowed = TOOLS_BY_STEP[scenario]?.[step]
  if (!allowed) return { navigate_step, get_workspace_state }
  const out = {}
  for (const name of allowed) {
    if (all[name]) out[name] = all[name]
  }
  return out
}

// 平移自 scenarios.yaml 的 steps.tools(已追加 excel_import / excel_export)
const TOOLS_BY_STEP = {
  EXPORT: {
    SELECT_SCENARIO: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    SELECT_DEFS: ['navigate_step', 'get_workspace_state', 'list_config_defs', 'get_config_def', 'select_definitions', 'collect_user_input'],
    QUERY_COND: ['navigate_step', 'get_workspace_state', 'list_config_defs', 'get_config_def', 'table_batch_set_field', 'table_replace_values', 'collect_user_input'],
    RESULT: ['navigate_step', 'get_workspace_state', 'run_flow', 'excel_export', 'confirm_complete', 'collect_user_input']
  },
  IMPORT: {
    SELECT_SCENARIO: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    VIEW_DEFS: ['navigate_step', 'get_workspace_state', 'list_config_defs', 'get_config_def', 'excel_import', 'collect_user_input'],
    PRECHECK: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    REVIEW: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    PUBLISH: ['navigate_step', 'get_workspace_state', 'run_flow', 'confirm_complete', 'collect_user_input']
  },
  ADD: {
    SELECT_SCENARIO: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    VIEW_DEFS: ['navigate_step', 'get_workspace_state', 'list_config_defs', 'get_config_def', 'table_batch_set_field', 'table_replace_values', 'excel_import', 'collect_user_input'],
    PRECHECK: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    REVIEW: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    PUBLISH: ['navigate_step', 'get_workspace_state', 'run_flow', 'confirm_complete', 'collect_user_input']
  },
  MODIFY: {
    SELECT_SCENARIO: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    VIEW_DEFS: ['navigate_step', 'get_workspace_state', 'list_config_defs', 'get_config_def', 'table_batch_set_field', 'table_delete_rows', 'table_replace_values', 'excel_import', 'collect_user_input'],
    PRECHECK: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    REVIEW: ['navigate_step', 'get_workspace_state', 'collect_user_input'],
    PUBLISH: ['navigate_step', 'get_workspace_state', 'run_flow', 'confirm_complete', 'collect_user_input']
  }
}

/** 刷新 SpreadJS 表格实例(工具修改数据后) */
function refreshSheet(defId) {
  if (window.__spreadsheets && window.__spreadsheets[defId]) {
    const proxy = window.__spreadsheets[defId]
    if (typeof proxy.refreshFromServer === 'function') {
      proxy.refreshFromServer().catch(() => {})
    }
  }
}
