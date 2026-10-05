/**
 * 工作区契约层：唯一知道“AI ui_event ↔ 各 store/路由”映射关系的模块。
 * - AI 面板与各工作区页面互不引用；
 * - 移除工作区（不 import 本模块）→ AI 对话仍可完成全部操作（后端工具直接执行）；
 * - 移除 AI 面板 → 工作区各向导不受任何影响。
 */
import router from '../router'
import { on, registerContextProvider } from './workspaceBus'
import { suppressWorkspaceEvents, resumeWorkspaceEvents } from './workspace'
import { downloadFile } from '../api'
import { useExportStore } from '../stores/exportTask'
import { useImportStore } from '../stores/importTask'
import { useDefsStore } from '../stores/defs'

let initialized = false

export function initWorkspaceContract() {
  if (initialized) return
  initialized = true

  // ---- 业务 → AI：上下文快照提供者（摘要级，与 AI 面板无耦合） ----
  registerContextProvider(() => {
    const exportStore = useExportStore()
    const importStore = useImportStore()
    const current = router.currentRoute.value
    return {
      page: String(current && current.name ? current.name : 'export'),
      export: {
        step: exportStore.step,
        selectedDefs: exportStore.selectedDefs,
        conditions: exportStore.conditions,
        taskId: exportStore.task ? exportStore.task.id : null
      },
      import: {
        step: importStore.step,
        selectedDefs: importStore.selectedDefs,
        batchId: importStore.batch ? importStore.batch.id : null
      }
    }
  })

  // ---- AI → 业务：ui_event 统一分发（与用户点击同一 store action） ----
  on('ui_event', async (ev) => {
    const type = ev.type
    // AI 驱动的变更不触发“工作区已更新”提示（该提示仅面向用户手动操作）
    suppressWorkspaceEvents()
    try {
      const exportStore = useExportStore()
      const importStore = useImportStore()
      const defsStore = useDefsStore()

      switch (type) {
        case 'open_page': {
          const page = ev.page
          const route = { export: '/export', import: '/import', defs: '/defs', data: '/data', tasks: '/tasks' }[page]
          if (route) await router.push(route)
          break
        }
        case 'goto_step': {
          const current = router.currentRoute.value
          if (current && current.name === 'export') exportStore.goStep(ev.step)
          else if (current && current.name === 'import') importStore.goStep(ev.step)
          break
        }
        case 'select_defs': {
          if (ev.task === 'export') exportStore.selectDefs(ev.defCodes || [])
          else importStore.selectDefs(ev.defCodes || [])
          break
        }
        case 'set_conditions':
          exportStore.setConditions(ev.defCode, ev.conditions || {})
          break
        case 'export_started': {
          exportStore.task = ev.snapshot
            ? { ...ev.snapshot, id: ev.snapshot.detail.id }
            : { id: ev.taskId, detail: { files: [] } }
          exportStore.goStep(3)
          exportStore.subscribe(ev.taskId)
          break
        }
        case 'import_batch': {
          importStore.batch = { id: ev.batchId, detail: { files: [] } }
          importStore.goStep(1)
          importStore.reloadEntries()
          break
        }
        case 'import_action': {
          if (ev.action === 'check') importStore.goStep(2)
          else if (ev.action === 'import') importStore.goStep(3)
          else if (ev.action === 'publish') importStore.goStep(4)
          if (ev.batchId) {
            importStore.batch = importStore.batch || { id: ev.batchId, detail: {} }
            importStore.subscribe(ev.batchId)
          }
          break
        }
        case 'download':
          downloadFile(ev.url, ev.filename)
          break
        case 'refresh_defs':
          await defsStore.load()
          break
        case 'refresh_data':
          window.dispatchEvent(new CustomEvent('refresh-data', { detail: { defCode: ev.defCode } }))
          break
        case 'refresh_tasks':
          window.dispatchEvent(new CustomEvent('refresh-tasks'))
          break
        default:
          break
      }
    } catch (e) {
      console.warn('[workspaceContract] ui_event 处理失败', type, e)
    } finally {
      resumeWorkspaceEvents()
    }
  })
}
