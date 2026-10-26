/**
 * 批次5A · 页面上下文复位：`enterPage` = 「声明即全集」。
 *
 * 现场（审查 §5-4 / P0-4）：设置页不声明页面、数据页不清 taskId/taskStatus ⇒ 跨页残留。
 * 后果两条都是可复现的错误事实，不是观感问题：
 * 1. 上一页向导的 `taskType + step` 还在 ⇒ 后端披露面按 `task:*` 给工具，模型以为用户还在向导里；
 * 2. `taskStatus` 停在 COMPLETED / CANCELLED ⇒ 契约层只读守卫（`isCurrentTaskReadOnly`）拿旧状态
 *    把**其它页**的写事件当「任务已结束」误拒。
 *
 * 复位边界（「不扩大清空」）：只复位页面级事实（taskType/step/taskId/taskStatus/selectedDefs/
 * queryConditions/importMode）；时间线（`recentActions` / `dataVersion`）与契约版本原样保留；
 * 本页显式声明的值（数据页正在浏览的 selectedDefs）必须保留。
 *
 * 批次4 口径不回退：`taskStatus` 仍随 `buildContext()` 顶层上报（复位后为 null）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

describe('批次5A 工作区：进入页面即复位未声明的页面级上下文', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('进入新页只复位页面级事实：未声明字段回中性值，时间线与本页声明保留', () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 7,
      taskStatus: 'COMPLETED',
      selectedDefs: ['CURRENCY'],
      queryConditions: { CURRENCY: { fields: {} } },
      importMode: 'MERGE'
    })
    const versionBefore = workspace.dataVersion
    const actionsBefore = workspace.recentActions.length

    // ① 任务中心只声明「当前没有任务」（与页面既有调用同形）：未声明的一律复位
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
    expect(workspace.taskStatus).toBeNull()
    expect(workspace.selectedDefs).toEqual([])
    expect(workspace.queryConditions).toEqual({})
    expect(workspace.importMode).toBeNull()

    // ② 复位不是清空：时间线照常追加一条，版本号单调 +1
    expect(workspace.recentActions).toHaveLength(actionsBefore + 1)
    expect(workspace.recentActions.at(-1)?.summary).toBe('进入任务中心')
    expect(workspace.dataVersion).toBe(versionBefore + 1)

    // ③ 数据浏览页：任务态复位，但本页声明的「正在浏览的定义」保留（不扩大清空）
    workspace.setTaskId(9, 'EXPORT', 'ACTIVE')
    workspace.enterPage({
      pageId: 'data',
      page: 'data',
      taskType: null,
      step: null,
      taskId: null,
      taskStatus: null,
      selectedDefs: ['DEMO'],
      queryConditions: {},
      importMode: null
    })
    expect(workspace.taskId).toBeNull()
    expect(workspace.taskStatus).toBeNull()
    expect(workspace.step).toBeNull()
    expect(workspace.selectedDefs).toEqual(['DEMO'])

    // ④ 批次4 不回退：taskStatus 仍是 buildContext 的顶层键（复位后为 null，不塞进 extra）
    const context = workspace.buildContext()
    expect(Object.keys(context).sort()).toEqual(['extra', 'page', 'step', 'taskId', 'taskStatus', 'taskType'])
    expect(context.taskStatus).toBeNull()
    expect((context.extra as Record<string, unknown>).taskStatus).toBeUndefined()
  })
})
