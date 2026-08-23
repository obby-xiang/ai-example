/**
 * 前端工具注册表 —— 与后端 FrontendTools.java 一一对应。
 *
 * 设计原则（响应经验 100029435 / 404338 / 448872）：
 *   1. 与后端工具名 100% 对齐，禁止出现前端侧私有的 toolName。
 *   2. 每个工具定义包含：validate(参数最小契约校验) / labelType / labelText / needConfirm(可按参数细化) / execute(实际执行)。
 *   3. 所有"副作用"（路由跳转、Pinia 状态、SpreadJS 调用、接口请求）都在这里集中管理，
 *      AiPanel 只展示卡片、触发 confirm 和调用 registry.execute，不关心实现细节。
 *   4. 参数契约：前端 validate 只做"必填字段+类型"校验，语义推断（比如 defId 是否存在）交给 execute。
 */

import { ElMessage } from 'element-plus'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import router from '@/router'
import { stepToRoute } from '@/router'
import { importExcel, exportExcelByRows, exportExcelMultiSheet } from '@/utils/excel-io'

const SCENARIO_NAME = {
  EXPORT: '导出配置', IMPORT: '导入配置', ADD: '新增配置', MODIFY: '修改配置'
}

const STEP_ENUM = new Set([
  'SELECT_SCENARIO', 'SELECT_DEFS', 'VIEW_DEFS',
  'QUERY_COND', 'PRECHECK', 'REVIEW', 'PUBLISH', 'RESULT', 'DASHBOARD'
])
const SCENARIO_ENUM = new Set(['EXPORT', 'IMPORT', 'ADD', 'MODIFY'])

function requireField(obj, field, type) {
  if (obj == null) return `参数为空`
  const v = obj[field]
  if (v === undefined || v === null || v === '') return `缺少必填字段: ${field}`
  if (type === 'array' && !Array.isArray(v)) return `字段 ${field} 必须是数组`
  if (type === 'number' && typeof v !== 'number' && isNaN(Number(v))) return `字段 ${field} 必须是数字`
  if (type === 'object' && (typeof v !== 'object' || Array.isArray(v))) return `字段 ${field} 必须是对象`
  if (type === 'string' && typeof v !== 'string') return `字段 ${field} 必须是字符串`
  return null
}

/** 触发 AI 自动提示（复用 AiPanel 已有机制） */
function fireAuto(ev) {
  if (typeof window.__triggerAiAutoPrompt === 'function') {
    setTimeout(() => window.__triggerAiAutoPrompt(ev), 200)
  }
}

/** 工具注册表。key = toolName，必须与后端 FrontendTools.ALL 完全一致 */
export const FRONTEND_TOOL_REGISTRY = {
  // -------------------- 1. navigate_step --------------------
  navigate_step: {
    /** 展示标签（与后端 FrontendTools.java 标题对齐） */
    labelText: '跳转',
    /** Element Plus tag type */
    labelType: 'primary',
    /**
     * 是否需要用户确认。参数 { step, scenario } 基本都是低风险跳转，
     * 这里返回 false —— 与后端 FrontendTools.defaultNeedConfirm 一致。
     */
    needConfirm: (/*args, defaultVal*/) => false,

    validate(args = {}) {
      if (!STEP_ENUM.has(args.step)) {
        return `step 必须是以下之一: ${[...STEP_ENUM].join(', ')}`
      }
      if (args.scenario !== undefined && args.scenario !== null && args.scenario !== ''
          && !SCENARIO_ENUM.has(args.scenario)) {
        return `scenario 必须是以下之一: ${[...SCENARIO_ENUM].join(', ')}`
      }
      return null
    },

    async execute(args) {
      const taskStore = useTaskStore()
      const { step, scenario } = args

      // 1) 切换场景（如指定且与当前不同）
      if (scenario) {
        if (!taskStore.currentTask) await taskStore.createTask()
        if (taskStore.currentTask?.scenario !== scenario) {
          await taskStore.selectScenario(scenario)
          ElMessage.success('已切换场景：' + (SCENARIO_NAME[scenario] || scenario))
        }
      }
      // 2) 跳转步骤
      if (step) {
        if (!taskStore.currentTask) await taskStore.createTask()
        // 防御：跨场景跳步需先选定场景，否则后端 gotoStep 会拒绝
        // （进度条不同步 bug 根因：scenario=null 跳到后续步骤导致前端 steps 数组为空）
        if (step !== 'SELECT_SCENARIO' && !taskStore.scenario && !scenario) {
          return { ok: false, message: '当前任务尚未选择场景，无法跳转到 ' + step + '。请先选择场景。' }
        }
        await taskStore.gotoStep(step)
      }
      // 3) 路由跟随
      const target = stepToRoute(taskStore.currentStep)
      if (target && target !== '/dashboard') await router.push(target)

      fireAuto(scenario ? 'select_scenario' : 'enter_step')
      return { ok: true, message: `已跳转到${step}${scenario ? '（场景:' + SCENARIO_NAME[scenario] + '）' : ''}` }
    }
  },

  // -------------------- 2. select_definitions --------------------
  select_definitions: {
    labelText: '配置项',
    labelType: 'success',
    needConfirm: (args) => {
      // 批量 set/大批量移除时建议确认
      if (!args) return false
      const size = Array.isArray(args.defIds) ? args.defIds.length : 0
      return args.mode === 'set' && size > 5 ? true : false
    },
    validate(args = {}) {
      const e = requireField(args, 'mode', 'string')
      if (e) return e
      if (!['add', 'remove', 'set'].includes(args.mode)) return "mode 必须是 add / remove / set"
      const e2 = requireField(args, 'defIds', 'array')
      if (e2) return e2
      return null
    },
    async execute(args) {
      const taskStore = useTaskStore()
      const configStore = useConfigStore()
      let next = taskStore.selectedDefIds.slice()
      const ids = (args.defIds || []).map(x => Number(x)).filter(x => Number.isFinite(x) && x > 0)
      if (args.mode === 'set') next = ids.slice()
      else if (args.mode === 'remove') next = next.filter(x => !ids.includes(x))
      else next = Array.from(new Set([...next, ...ids]))
      const enabledIds = configStore.enabledDefinitions.map(d => d.id)
      next = next.filter(id => enabledIds.includes(id))
      await taskStore.updateSelectedDefs(next)
      fireAuto('select_defs')
      return { ok: true, message: '已更新配置项选择, 共 ' + next.length + ' 项' }
    }
  },

  // -------------------- 3. run_flow --------------------
  run_flow: {
    labelText: '流程',
    labelType: 'warning',
    needConfirm: () => true, // 一键流程默认需确认
    validate(args = {}) {
      const e = requireField(args, 'flowType', 'string')
      if (e) return e
      if (!['export_single', 'export_all'].includes(args.flowType)) {
        return 'flowType 暂支持 export_single / export_all'
      }
      return null
    },
    async execute(args) {
      const taskStore = useTaskStore()
      const configStore = useConfigStore()
      const flowType = args.flowType
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
        if (!defIds.length) {
          return { ok: false, message: '没有可导出的配置项' }
        }
        await taskStore.updateSelectedDefs(defIds)
        await taskStore.gotoStep('QUERY_COND')
        await taskStore.saveStepData('QUERY_COND', { mode: 'all' })
        // 生成导出结果（模拟计算每个配置的数据量）
        const items = []
        for (const id of defIds) {
          const d = configStore.defById(id)
          try {
            const count = await configStore.count(id)
            items.push({ defId: id, code: d?.code, name: d?.name, total: count.total })
          } catch (e) { /* ignore */ }
        }
        await taskStore.saveStepData('RESULT', { items, type: 'export', at: Date.now() })
        await taskStore.gotoStep('RESULT')
        const route = stepToRoute('RESULT')
        if (route) await router.push(route)
        fireAuto('flow_export')
        return { ok: true, message: '已完成导出流程,共 ' + defIds.length + ' 个配置项' }
      }
      return { ok: false, message: '暂未支持该流程:' + flowType }
    }
  },

  // -------------------- 4. table_batch_set_field --------------------
  table_batch_set_field: {
    labelText: '表格更新',
    labelType: 'danger',
    needConfirm: () => true,
    validate(args = {}) {
      let e = requireField(args, 'configDefId', 'number')
      if (e) return e
      e = requireField(args, 'set', 'object')
      if (e) return e
      if (args.where !== undefined && args.where !== null) {
        if (typeof args.where !== 'object' || Array.isArray(args.where)) {
          return 'where 必须是对象 { field, op, value }'
        }
      }
      return null
    },
    async execute(args) {
      const configStore = useConfigStore()
      const defId = Number(args.configDefId)
      if (!window.__spreadsheets || !window.__spreadsheets[defId]) {
        return { ok: false, message: '未找到对应表格上下文,请先在"查看配置项"页打开该配置数据' }
      }
      const sheetProxy = window.__spreadsheets[defId]
      const res = await configStore.applyOperation(defId, 'update_where', {
        where: args.where, set: args.set
      })
      try { if (typeof sheetProxy.refreshFromServer === 'function') await sheetProxy.refreshFromServer() } catch (e) {}
      fireAuto('data_change')
      return { ok: true, message: '操作成功, 影响 ' + (res.affected || 0) + ' 行' }
    }
  },

  // -------------------- 5. table_delete_rows --------------------
  table_delete_rows: {
    labelText: '表格删除',
    labelType: 'danger',
    needConfirm: () => true,
    validate(args = {}) {
      const e = requireField(args, 'configDefId', 'number')
      if (e) return e
      const hasRange = args.fromRow !== undefined && args.fromRow !== null
      const hasWhere = args.where !== undefined && args.where !== null
      if (!hasRange && !hasWhere) return '必须提供 fromRow/toRow 或 where 条件'
      return null
    },
    async execute(args) {
      const configStore = useConfigStore()
      const defId = Number(args.configDefId)
      if (!window.__spreadsheets || !window.__spreadsheets[defId]) {
        return { ok: false, message: '未找到对应表格上下文,请先打开该配置数据' }
      }
      const sheetProxy = window.__spreadsheets[defId]
      const params = {
        fromRow: args.fromRow != null ? Number(args.fromRow) : undefined,
        toRow: args.toRow != null ? Number(args.toRow) : undefined,
        where: args.where
      }
      const res = await configStore.applyOperation(defId, 'delete_rows', params)
      try { if (typeof sheetProxy.refreshFromServer === 'function') await sheetProxy.refreshFromServer() } catch (e) {}
      fireAuto('data_change')
      return { ok: true, message: '操作成功, 影响 ' + (res.affected || 0) + ' 行' }
    }
  },

  // -------------------- 6. table_replace_values --------------------
  table_replace_values: {
    labelText: '替换',
    labelType: 'warning',
    needConfirm: () => true,
    validate(args = {}) {
      let e = requireField(args, 'configDefId', 'number'); if (e) return e
      e = requireField(args, 'field', 'string'); if (e) return e
      e = requireField(args, 'search', 'string'); if (e) return e
      e = requireField(args, 'replace', 'string'); if (e) return e
      return null
    },
    async execute(args) {
      const configStore = useConfigStore()
      const defId = Number(args.configDefId)
      if (!window.__spreadsheets || !window.__spreadsheets[defId]) {
        return { ok: false, message: '未找到对应表格上下文' }
      }
      const sheetProxy = window.__spreadsheets[defId]
      const res = await configStore.applyOperation(defId, 'replace', {
        field: args.field,
        search: args.search,
        replace: args.replace,
        regex: !!args.regex
      })
      try { if (typeof sheetProxy.refreshFromServer === 'function') await sheetProxy.refreshFromServer() } catch (e) {}
      fireAuto('data_change')
      return { ok: true, message: '操作成功, 影响 ' + (res.affected || 0) + ' 行' }
    }
  },

  // -------------------- 7. confirm_complete --------------------
  confirm_complete: {
    labelText: '完成',
    labelType: 'info',
    needConfirm: () => false,
    validate(args = {}) {
      if (args.target && !['FINISH_TASK', 'NEXT_STEP'].includes(args.target)) {
        return 'target 必须是 FINISH_TASK 或 NEXT_STEP'
      }
      return null
    },
    async execute(/*args*/) {
      const taskStore = useTaskStore()
      await taskStore.complete()
      return { ok: true, message: '任务已完成!' }
    }
  },

  // -------------------- 8. collect_user_input --------------------
  // 业界 Adaptive Cards 模式：AI 通过 schema 声明要收集的字段，前端动态渲染表单。
  // mode=INPUT 时 AiPanel 渲染 SchemaFormRenderer 替代确认按钮，提交后直接回灌表单值。
  collect_user_input: {
    labelText: '表单',
    labelType: 'primary',
    needConfirm: () => false,
    validate(args = {}) {
      const e = requireField(args, 'fields', 'array')
      if (e) return e
      if (!args.fields.length) return 'fields 不能为空'
      for (const f of args.fields) {
        if (!f.key || !f.label || !f.type) {
          return '每个字段必须包含 key/label/type'
        }
      }
      return null
    },
    // INPUT 模式下 AiPanel 直接把用户填写的表单值回灌给后端，不会调用此 execute。
    // 此处仅作兼容兜底（若被直接调用则把 args 原样返回）。
    async execute(args) {
      return { ok: true, message: '已收集用户输入', data: args }
    }
  },

  // -------------------- 9. excel_import --------------------
  // 上传 Excel(.xlsx)文件,解析后灌入到指定配置定义。
  // inputMode=true:后端下发 mode=INPUT 时 AiPanel 渲染上传卡片(固定 file 字段),
  // 用户选文件提交后 AiPanel 把 file 注入 args 并调本 execute 执行解析+灌入,回灌结果。
  excel_import: {
    labelText: 'Excel 导入',
    labelType: 'success',
    needConfirm: () => false, // 表单提交本身即用户主动行为,无需二次确认
    inputMode: true,
    validate(args = {}) {
      const e = requireField(args, 'configDefId', 'number')
      if (e) return e
      if (args.mode !== undefined && args.mode !== null && !['append', 'merge'].includes(args.mode)) {
        return 'mode 必须是 append 或 merge'
      }
      return null
    },
    async execute(args) {
      const configStore = useConfigStore()
      const defId = Number(args.configDefId)
      const def = configStore.defById(defId)
      if (!def) return { ok: false, message: '未找到配置定义 #' + defId }
      // args.file 由 AiPanel 表单提交时注入(File 对象)
      const file = args.file
      if (!(file instanceof File)) {
        return { ok: false, message: '未收到有效的文件对象,请重新上传' }
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
      // 刷新已挂载的 SpreadJS 表格(ai-ui:refreshFromServer 走 configApi.all → 落库数据)
      if (window.__spreadsheets && window.__spreadsheets[defId]) {
        try { await window.__spreadsheets[defId].refreshFromServer() } catch (e) { /* ignore */ }
      }
      fireAuto('data_change')
      return { ok: true, message: '已导入 ' + imp.rows.length + ' 行,当前共 ' + res.total + ' 行', imported: imp.rows.length, total: res.total, stats: imp.stats }
    }
  },

  // -------------------- 10. excel_export --------------------
  // 导出配置数据为 Excel(.xlsx)并下载。autoExec 类只读导出,无需确认。
  excel_export: {
    labelText: 'Excel 导出',
    labelType: 'info',
    needConfirm: () => false,
    validate(args = {}) {
      if (args.configDefIds !== undefined && args.configDefIds !== null && !Array.isArray(args.configDefIds)) {
        return 'configDefIds 必须是数组'
      }
      return null
    },
    async execute(args) {
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
  }
}

/**
 * 根据工具名获取注册表条目。
 */
export function getToolEntry(toolName) {
  return FRONTEND_TOOL_REGISTRY[toolName] || null
}

/**
 * 综合判断是否需要确认：
 *   优先使用后端下发的 toolCall.needConfirm，
 *   其次取注册表定义的 needConfirm(args)。
 */
export function resolveNeedConfirm(toolCall) {
  if (!toolCall) return true
  const entry = getToolEntry(toolCall.toolName)
  const fromRegistry = entry && typeof entry.needConfirm === 'function'
    ? entry.needConfirm(toolCall.args || {})
    : true
  // 若后端明确给了 needConfirm，则取"并集"（后端说要确认就必须确认）
  if (toolCall.needConfirm === true) return true
  return !!fromRegistry
}

/**
 * 执行工具调用。返回 { ok, message }。
 */
export async function executeFrontendTool(toolCall) {
  if (!toolCall || !toolCall.toolName) {
    return { ok: false, message: '工具调用缺少 toolName' }
  }
  const entry = getToolEntry(toolCall.toolName)
  if (!entry) {
    return { ok: false, message: `未知前端工具: ${toolCall.toolName}（请检查后端 FrontendTools.ALL 与前端注册表是否同步）` }
  }
  // 1. 参数校验（契约对齐 - 经验 448872）
  const err = entry.validate ? entry.validate(toolCall.args || {}) : null
  if (err) return { ok: false, message: '参数校验失败: ' + err }
  // 2. 执行
  const result = await entry.execute(toolCall.args || {})
  if (result && result.message) {
    if (result.ok === false) ElMessage.warning(result.message)
    else ElMessage.success(result.message)
  }
  return result || { ok: true }
}

/**
 * 将旧协议的 AiAction 转换为 FrontendToolCall（与后端 FrontendTools.adaptFromLegacy 逻辑一致）。
 * 前端历史加载 / 旧接口降级时使用。
 */
export function adaptLegacyActionToToolCall(action) {
  if (!action) return null
  const toolMap = {
    navigate: 'navigate_step',
    select_defs: 'select_definitions',
    flow: 'run_flow',
    table_update: 'table_batch_set_field',
    table_delete: 'table_delete_rows',
    table_replace: 'table_replace_values',
    confirm_complete: 'confirm_complete'
  }
  const toolName = toolMap[action.type] || 'confirm_complete'
  return {
    callId: 'legacy-' + (action.id || Math.random().toString(36).slice(2, 10)),
    toolName,
    args: { ...(action.payload || {}) },
    title: action.title || toolName,
    impact: action.impact || '',
    needConfirm: action.needConfirm !== undefined ? action.needConfirm : true,
    legacy: true
  }
}
