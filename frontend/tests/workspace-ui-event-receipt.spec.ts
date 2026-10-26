/**
 * 批次5A · 失败回执：`handled=false` 不得记成「工作区已同步」，但回执必须可展示。
 *
 * 现场（审查 §5-4「失败仍已同步」）：`dispatchUiEvent` 此前**无条件** `workspace.touch()` ——
 * 失败分支也广播 `workspace_changed`，面板顶部因此出现「工作区已同步：当前没有进行中的任务…」
 * 这种谎报（动作根本没落地）。
 *
 * 收口口径 = 两件事分开：
 * 1. `workspace_changed`（「已同步」chip）只在 `handled===true` 时出；
 * 2. 失败仍走 `ui_event_result`（`handled=false` + 原因文案）进面板回执位，并留在工作区时间线里
 *    （模型下一轮要知道这一手被拒，不得当成已改动 —— `gate-matrix` 的只读壳用例锁住这一条）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { dispatchUiEvent, onWorkspaceEvent, useWorkspaceStore } from '@/stores/workspace'

describe('批次5A ui_event 回执：失败不报「已同步」，回执仍可展示', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('失败：零 workspace_changed，但落时间线并广播 ui_event_result(handled=false)', async () => {
    const workspace = useWorkspaceStore()
    workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })

    const synced: unknown[] = []
    const receipts: Array<Record<string, unknown>> = []
    const offSynced = onWorkspaceEvent('workspace_changed', (payload) => synced.push(payload))
    const offReceipt = onWorkspaceEvent('ui_event_result', (payload) => {
      receipts.push(payload as Record<string, unknown>)
    })

    // 无任务上下文时 select_defs 必拒（fail 分支，零 API 调用）
    const result = await dispatchUiEvent({ type: 'select_defs', task: 'export', defCodes: ['A'] })
    offSynced()
    offReceipt()

    expect(result.handled).toBe(false)
    // ① 「已同步」chip 的数据源（workspace_changed）一次都不许发
    expect(synced).toEqual([])
    // ② 失败回执仍可展示：handled=false + 原因文案
    expect(receipts).toHaveLength(1)
    expect(receipts[0]).toMatchObject({ event: 'select_defs', handled: false })
    expect(String(receipts[0].message)).toContain('当前没有进行中的任务')
    // ③ 时间线仍留「这一手被拒」（source=AI，模型可见）
    expect(workspace.recentActions.at(-1)).toMatchObject({ source: 'AI' })
    expect(workspace.recentActions.at(-1)?.summary).toContain('当前没有进行中的任务')
  })
})
