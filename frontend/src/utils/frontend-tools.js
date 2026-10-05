/**
 * 前端工具执行器 —— 契约第 5 节 10 个前端工具。
 * 执行前兜底校验当前 page/step（第三道防线），不匹配返回
 * { success:false, error:'该工具在当前页面不可用' }。
 * 页面专属动作由向导页面通过 registerPageHandler 注册实现。
 */
import router from '@/router'
import { configApi } from '@/api'
import { useWorkspaceStore, getPageHandler } from '@/stores/workspace'
import { downloadTemplates } from '@/utils/excel-io'

const AVAILABILITY = {
  navigate_to: { pages: ['TASKS', 'EXPORT', 'IMPORT'], steps: null },
  get_workspace_state: { pages: ['TASKS', 'EXPORT', 'IMPORT'], steps: null },
  select_config_defs: { pages: ['EXPORT', 'IMPORT'], steps: [1] },
  set_query_conditions: { pages: ['EXPORT'], steps: [2] },
  download_templates: { pages: ['IMPORT'], steps: [1] },
  download_export_files: { pages: ['EXPORT'], steps: [3] },
  start_export: { pages: ['EXPORT'], steps: [2, 3] },
  start_check: { pages: ['IMPORT'], steps: [2] },
  start_import: { pages: ['IMPORT'], steps: [3] },
  start_publish: { pages: ['IMPORT'], steps: [4] }
}

const PAGE_ROUTE = {
  TASKS: () => '/tasks',
  EXPORT: (taskId) => `/export/${taskId}`,
  IMPORT: (taskId) => `/import/${taskId}`
}

const UNAVAILABLE = { success: false, error: '该工具在当前页面不可用' }

export async function executeFrontendTool(name, args = {}) {
  const ws = useWorkspaceStore()
  const rule = AVAILABILITY[name]
  if (!rule) return { success: false, error: `未知工具: ${name}` }
  if (!rule.pages.includes(ws.page)) return UNAVAILABLE
  if (rule.steps && !rule.steps.includes(ws.step)) return UNAVAILABLE

  switch (name) {
    case 'navigate_to': {
      const page = String(args.page || '').toUpperCase()
      const builder = PAGE_ROUTE[page]
      if (!builder) return { success: false, error: `未知页面: ${args.page}` }
      if ((page === 'EXPORT' || page === 'IMPORT') && !args.taskId) {
        return { success: false, error: '跳转向导页面必须携带 taskId' }
      }
      await router.push(builder(args.taskId))
      return { success: true, data: { page, taskId: args.taskId || null } }
    }

    case 'get_workspace_state': {
      const snapshot = ws.snapshot()
      const extra = getPageHandler('get_workspace_state_extra')
      if (extra) {
        try {
          snapshot.detail = await extra()
        } catch (e) { /* 页面快照失败不阻断 */ }
      }
      return { success: true, data: snapshot }
    }

    case 'select_config_defs': {
      const codes = Array.isArray(args.codes) ? args.codes : []
      if (!codes.length) return { success: false, error: 'codes 不能为空' }
      const handler = getPageHandler('select_config_defs')
      if (handler) {
        await handler(codes, args.mode || 'REPLACE')
      } else {
        ws.setSelectedDefs(codes, args.mode || 'REPLACE')
      }
      return { success: true, data: { selectedDefs: [...ws.selectedDefs] } }
    }

    case 'set_query_conditions': {
      if (!args.defCode) return { success: false, error: 'defCode 不能为空' }
      const handler = getPageHandler('set_query_conditions')
      if (handler) {
        await handler(args.defCode, args.conditions || [])
      } else {
        ws.setQueryConditions(args.defCode, args.conditions || [])
      }
      return { success: true, data: { defCode: args.defCode } }
    }

    case 'download_templates': {
      const codes = Array.isArray(args.codes) && args.codes.length ? args.codes : ws.selectedDefs
      if (!codes.length) return { success: false, error: '未指定要下载模板的配置项' }
      const all = await configApi.list()
      const defs = all.filter((d) => codes.includes(d.code))
      if (!defs.length) return { success: false, error: '配置项不存在: ' + codes.join(',') }
      const { fileName } = await downloadTemplates(defs)
      return { success: true, data: { fileName, codes: defs.map((d) => d.code) } }
    }

    case 'download_export_files': {
      const handler = getPageHandler('download_export_files')
      if (!handler) return UNAVAILABLE
      return await handler(Array.isArray(args.codes) && args.codes.length ? args.codes : null)
    }

    case 'start_export':
    case 'start_check':
    case 'start_import':
    case 'start_publish': {
      const handler = getPageHandler(name)
      if (!handler) return UNAVAILABLE
      return await handler()
    }

    default:
      return { success: false, error: `未实现的工具: ${name}` }
  }
}
