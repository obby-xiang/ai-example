/**
 * 副作用可能已发生的工具：超时文案不得诱导重试（2026-10-29 复审 Minor-1）。
 *
 * `export.start` 会在服务端**建导出作业**：预算到点（或停止）时作业可能已经创建。旧口径把它
 * 当普通 timeout —— 模型看到"执行超时，请重试"就会重复建导出作业（与下载类同族的风险）。
 *
 * 本文件只锁"文案/结局形态"：`export.start` 超时落 `unknown`（结局未知）+ 明确"勿重复发起"；
 * 无副作用的普通工具保持既有 timeout 口径。**不扩展到 import 建作业入口**（复审明确不扩）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler } from '@/stores/workspace'

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  unregisterPageHandler('export.start')
  unregisterPageHandler('probe.slow-readonly')
})

describe('R1 export.start 超时：结局未知 + 勿重复发起', () => {
  it('页面 handler 悬挂到预算到点 ⇒ outcome=unknown，文案含"可能已建/勿重复"，不是诱导重试的普通 timeout', async () => {
    registerPageHandler('export.start', () => new Promise(() => undefined))

    const pending = executeFrontendTool({ name: 'export.start', args: {}, budgetMs: 10 })
    await vi.advanceTimersByTimeAsync(20)
    const execution = await pending

    expect(execution.outcome).toBe('unknown')
    expect(execution.code).toBe('UI_BUDGET_EXHAUSTED')
    expect(execution.result).toContain('可能')
    expect(execution.result).toContain('勿重复')
    expect(execution.result).not.toContain('本次未完成')
  })

  it('反向对照：无副作用的普通工具仍落 timeout（不把"未知"扩到所有工具）', async () => {
    registerPageHandler('probe.slow-readonly', () => new Promise(() => undefined))

    const pending = executeFrontendTool({ name: 'probe.slow-readonly', args: {}, budgetMs: 10 })
    await vi.advanceTimersByTimeAsync(20)
    const execution = await pending

    expect(execution.outcome).toBe('timeout')
    expect(execution.result).toContain('本次未完成')
  })
})
