/**
 * 批次4 集成返修 / 契约：`buildContext()` 必须**顶楼上报 `taskStatus`**。
 *
 * 背景：后端 `AiChatRequest.Context` 六组件为 page/taskType/step/taskId/`taskStatus`/extra，
 * 且 `ToolRegistry#forContext` 用 `taskStatus` 做终态收窄（COMPLETED 任务不再披露 `start_export`）。
 * 前端此前把它只放进 `snapshot()`，`buildContext()` 里没有 ⇒ 后端永远拿不到终态，
 * 模型会看到"还能导出"的假入口。本文件锁住"顶层、每轮都带、随服务端快照更新"三条。
 *
 * 只驱动真实 store，不挂载页面。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspace'

describe('工作区上下文：taskStatus 顶层上报（与后端 Context 六键对齐）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('buildContext 的键集含 taskStatus，且值取自工作区（不塞进 extra）', () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
    workspace.setTaskStatus('COMPLETED')

    const context = workspace.buildContext()

    // ① 顶层键集 = 后端 Context 的六个字段（多/少都算契约漂移）
    expect(Object.keys(context).sort()).toEqual(['extra', 'page', 'step', 'taskId', 'taskStatus', 'taskType'])
    // ② 值逐字等于工作区状态
    expect(context.taskStatus).toBe('COMPLETED')
    // ③ 不得把状态藏进 extra —— 那里没有任何披露/收窄消费点（后端会当未知键丢弃）
    expect((context.extra as Record<string, unknown>).taskStatus).toBeUndefined()
  })

  it('任务页的 setTaskStatus 驱动下一轮上下文（COMPLETED 收窄可生效）', () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
    expect(workspace.buildContext().taskStatus).toBeNull()

    workspace.setTaskStatus('COMPLETED')
    expect(workspace.buildContext().taskStatus).toBe('COMPLETED')
    // 状态同步不产生"用户动作"（否则 recentActions 会被状态刷新刷爆）
    const actionsBefore = workspace.recentActions.length
    workspace.setTaskStatus('CANCELLED')
    expect(workspace.recentActions).toHaveLength(actionsBefore)
    expect(workspace.buildContext().taskStatus).toBe('CANCELLED')

    // 解绑任务后回到"无状态"（不做收窄）
    workspace.setTaskStatus(null)
    expect(workspace.buildContext().taskStatus).toBeNull()
  })

  it('批次5A 兼容：进页复位只置 null，不改键集（taskStatus 仍顶层上报）', () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'EXPORT',
      taskId: 7,
      taskStatus: 'COMPLETED'
    })
    expect(workspace.buildContext().taskStatus).toBe('COMPLETED')

    // 复位到任务中心（未声明 taskStatus / taskId）⇒ 值回 null，但键与位置不变
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
    const context = workspace.buildContext()
    expect(Object.keys(context).sort()).toEqual(['extra', 'page', 'step', 'taskId', 'taskStatus', 'taskType'])
    expect(context.taskStatus).toBeNull()
    expect((context.extra as Record<string, unknown>).taskStatus).toBeUndefined()
  })
})
