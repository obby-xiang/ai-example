/**
 * T-17 刷新失效收口 + 预览卡渲染（AI 表格操作专项 §3.5）。
 *
 * 三层：
 * ① 预览卡组件：无 plan ⇒ “预览已失效 + 放弃”；大批量 ⇒ 勾选前“应用”禁用；
 * ② store 恢复路径：PENDING 写工具且无本地 plan ⇒ 标记 stale；
 * ③ store 放弃动作：`cancelled=true` 回灌（沿用既有 FRONTEND_CANCELLED 语义）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { SheetWritePlan } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import SheetPreviewCard from '@/components/AiPanel/SheetPreviewCard.vue'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

function planOf(overrides: Partial<SheetWritePlan> = {}): SheetWritePlan {
  return {
    planId: 'p1',
    toolCallId: 't1',
    defCode: 'DEMO',
    tool: 'replace_values',
    level: 'small',
    preview: 'count',
    scopeSummary: 'DEMO 全表',
    predicateSummary: '',
    rowsCount: 2,
    cellsCount: 2,
    skippedCount: 0,
    samples: [{ row: 1, col: 2, oldValue: '停用', newValue: '禁用' }],
    changes: [{ row: 1, col: 2, oldValue: '停用', newValue: '禁用' }],
    targetRows: [1],
    fingerprint: ['USD'],
    warnings: [],
    rulesText: '整格相等 · 「停用」→「禁用」',
    status: 'pending',
    detail: 'full',
    ...overrides
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const engine of Object.values(api)) {
    engine.mockReset()
  }
  api.frontendToolResult.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
})

describe('① 预览卡组件', () => {
  it('无 plan ⇒ 渲染“预览已失效”+ 放弃按钮（不提供可点的“应用”）', async () => {
    const wrapper = mount(SheetPreviewCard, { props: { plan: null } })
    expect(wrapper.text()).toContain('预览已失效')
    expect(wrapper.find('[data-testid="sheet-preview-apply"]').exists()).toBe(false)
    await wrapper.find('[data-testid="sheet-preview-abandon"]').trigger('click')
    expect(wrapper.emitted('abandon')).toHaveLength(1)
    wrapper.unmount()
  })

  it('大批量：勾选“我了解影响范围”前“应用”禁用，勾选后可点', async () => {
    const wrapper = mount(SheetPreviewCard, {
      props: {
        plan: planOf({
          level: 'large',
          rowsCount: 30_000,
          cellsCount: 30_000,
          changes: Array.from({ length: 30_000 }, (_, index) => ({ row: index, col: 1, oldValue: '停用', newValue: '禁用' }))
        })
      }
    })
    const apply = wrapper.find('[data-testid="sheet-preview-apply"]')
    expect(apply.attributes('disabled')).toBeDefined()
    await wrapper.find('[data-testid="sheet-preview-ack-box"]').setValue(true)
    expect(wrapper.find('[data-testid="sheet-preview-apply"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-testid="sheet-preview-apply"]').trigger('click')
    expect(wrapper.emitted('apply')?.[0]).toEqual([true])
    wrapper.unmount()
  })
})

describe('②/③ 刷新恢复：无本地 plan ⇒ stale + 放弃回灌 cancelled', () => {
  it('restoreSuspendedSnapshot 标 stale；abandonStalePreview 提交 cancelled=true', async () => {
    const store = useAiStore()
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-1',
      snapshotStatus: 'PENDING',
      pendingEntries: [{
        toolCallId: 't1',
        entryStatus: 'PENDING',
        kind: 'FRONTEND',
        name: 'replace_values',
        arguments: JSON.stringify({ defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用' }),
        req: true
      }],
      unresolvedExternal: [],
      awaitingExternal: true,
      archiveMaxSeq: 3
    })
    await store.restoreSuspendedSnapshot()
    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBe('t1')

    await flushPromises()
    await store.abandonStalePreview()
    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({ cancelled: true, toolCallId: 't1' }))
    expect(store.stalePreviewToolCallId).toBeNull()
  })
})
