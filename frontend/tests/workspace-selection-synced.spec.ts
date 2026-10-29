/**
 * 返修（Important 3）：`buildContext()` 必须上报 `extra.selectionSynced` ——
 * 空勾选（`selectedDefs=[]`）只有在「任务详情已应用」时才是权威空（用户清空了勾选）；
 * 页面挂载到 `GET /tasks/{id}` 返回之间的窗口里，空值只是"还不知道"，后端必须按未同步处理，
 * 否则会把任务里已落库的勾选/历史选择误抹成空。
 *
 * 探针与 `utils/frontend-tools.ts#TASK_DETAIL_READY_PROBES` 同源（`export.taskDetailReady` /
 * `import.taskDetailReady`）；本用例只驱动真实 store + 注册表，不挂载页面。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'

describe('工作区上下文：selectionSynced 标记（未水合 ≠ 用户清空）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // 注册表是模块级单例：每个用例显式声明本用例的详情就绪探针
    registerPageHandler('export.taskDetailReady', () => false)
    registerPageHandler('import.taskDetailReady', () => false)
  })

  it('任务详情未应用时 selectionSynced=false（[] 不代表用户清空）', () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'SELECT_DEFS', taskId: 42 })

    const extra = workspace.buildContext().extra as Record<string, unknown>

    expect(extra.selectedDefs).toEqual([])
    expect(extra.selectionSynced).toBe(false)
  })

  it('任务详情已应用时 selectionSynced=true（[] 才是权威空）', () => {
    registerPageHandler('export.taskDetailReady', () => true)
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'SELECT_DEFS', taskId: 42 })

    expect((workspace.buildContext().extra as Record<string, unknown>).selectionSynced).toBe(true)
  })

  it('没有任务的向导页（新建流程）按未同步处理；非向导页也保持 false', () => {
    registerPageHandler('export.taskDetailReady', () => true)
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'SELECT_DEFS', taskId: null })
    expect((workspace.buildContext().extra as Record<string, unknown>).selectionSynced).toBe(false)

    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
    expect((workspace.buildContext().extra as Record<string, unknown>).selectionSynced).toBe(false)
  })
})
