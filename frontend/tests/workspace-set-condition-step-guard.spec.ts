/**
 * 步骤披露与筛选链路收口（2026-10-28）：`set_condition` 的错步回执必须**指路**。
 *
 * 现场：后端把 `set_condition` 的披露收窄到 `task:EXPORT/QUERY_COND` 之前，模型会在
 * `SELECT_DEFS` 这一批直接调它；前端动作表的 `allowSteps: ['QUERY_COND']` 守卫如实拒绝，
 * 但旧文案只说「请先推进到可用步骤」—— 模型不知道"推进"要用哪个工具、推到哪一步，
 * 于是重试同一调用或猜 `confirm_step(step=...)`。
 *
 * 收口口径（只改这一个动作的文案，不泛化其它动作）：
 * - 错步拒绝仍是 `failed + unavailable`（零副作用：不挂起、不写条件、不自动跨步）；
 * - 回执点名 `confirm_step` 与目标步骤 `QUERY_COND`，并说明"下一批再调用"；
 * - `set_condition` **不会**自动推进或自动 confirm（守卫生效时根本不进 handler）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'

const CONDITION_ARGS = {
  defCode: 'CURRENCY',
  conditions: { scopeKeys: ['XN'], fields: [{ fieldCode: 'code', operator: 'EQ', value: 'CNY' }] }
}

describe('set_condition 步骤守卫：错步回执指路 confirm_step → QUERY_COND', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('EXPORT/SELECT_DEFS 调用 set_condition ⇒ 拒绝，且回执点名 confirm_step 推进到 QUERY_COND', async () => {
    let handlerCalls = 0
    registerPageHandler('export.setConditions', () => {
      handlerCalls += 1
      return '已设置查询条件'
    })
    useWorkspaceStore().enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'SELECT_DEFS',
      taskId: 7
    })

    const execution = await executeFrontendTool({ name: 'set_condition', args: CONDITION_ARGS })

    expect(execution).toMatchObject({ ok: false, outcome: 'failed', availability: 'unavailable' })
    expect(execution.result).toContain('QUERY_COND')
    expect(execution.result).toContain('请先调用 confirm_step 推进到 QUERY_COND')
    // 下一批再设：不鼓励在同一批里连续动作
    expect(execution.result).toContain('下一批再调用 set_condition')
    // 零副作用：条件 handler 从未被调用（不自动跨步、不自动 confirm、不抢跑写条件）
    expect(handlerCalls).toBe(0)

    unregisterPageHandler('export.setConditions')
  })

  it('QUERY_COND 下同一调用放行（守卫只拦错步，不误伤正步；文案不变）', async () => {
    registerPageHandler('export.setConditions', () => '已设置查询条件')
    useWorkspaceStore().enterPage({
      pageId: 'export',
      page: 'export',
      taskType: 'EXPORT',
      step: 'QUERY_COND',
      taskId: 7
    })

    const execution = await executeFrontendTool({ name: 'set_condition', args: CONDITION_ARGS })

    expect(execution).toMatchObject({ ok: true, outcome: 'ok', availability: 'available' })
    expect(execution.result).toBe('已设置查询条件')

    unregisterPageHandler('export.setConditions')
  })
})
