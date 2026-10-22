/**
 * 仓外可行性实测 ③：纯逻辑层首批落点（包⑤预算折算 + R-166 倒计时口径：陈旧帧判据 + 两源折算）。
 * 这些断言与仓外 node 脚本断言的是同一批行为 —— 差别只在于：这里是入库即可被 CI 执行的用例。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
  FRONTEND_TOOL_RESULT_HEADROOM_MS,
  FRONTEND_TOOL_TIMEOUT_BUDGET_MS,
  deriveFrontendToolBudgetMs,
  executeFrontendTool,
  parseToolArgs
} from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler } from '@/stores/workspace'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('deriveFrontendToolBudgetMs（服务端上限 → 前端预算）', () => {
  it('常量口径：60s 预算 + 60s 余量，合计等于后端 120s 挂起上限', () => {
    expect(FRONTEND_TOOL_TIMEOUT_BUDGET_MS).toBe(60_000)
    expect(FRONTEND_TOOL_RESULT_HEADROOM_MS).toBe(60_000)
    expect(FRONTEND_TOOL_TIMEOUT_BUDGET_MS + FRONTEND_TOOL_RESULT_HEADROOM_MS).toBe(120_000)
  })

  it.each([
    [120, 60_000], // 默认：120s − 60s
    [200, 140_000], // 随服务端上限平移，不再封顶
    [61, 5_000], // 61s − 60s = 1s → 夹 5s 下限
    [30, 5_000], // 夹下限
    [5, 5_000], // 与下限等值
    [3, 3_000], // 病态配置：倒挂优先，预算 == 服务端上限
    [undefined, 60_000],
    [null, 60_000],
    [0, 60_000],
    [-5, 60_000],
    [Number.NaN, 60_000],
    [1e307, 60_000]
  ])('timeoutSeconds=%s → %sms', (input, expected) => {
    expect(deriveFrontendToolBudgetMs(input as number | null | undefined)).toBe(expected)
  })

  it('预算恒不超过服务端上限（无倒挂）', () => {
    for (const seconds of [1, 2, 3, 4, 5, 10, 30, 60, 120, 600]) {
      expect(deriveFrontendToolBudgetMs(seconds)).toBeLessThanOrEqual(seconds * 1000)
    }
  })
})

describe('executeFrontendTool 统一超时预算（包⑤）', () => {
  it('执行器永不给结局时，预算到点产出可行动的超时结局（不悬挂）', async () => {
    registerPageHandler('probe.never-returns', () => new Promise<string>(() => {}))
    const pending = executeFrontendTool({ name: 'probe.never-returns', args: {}, budgetMs: 1_000 })
    await vi.advanceTimersByTimeAsync(1_000)
    const execution = await pending
    expect(execution.ok).toBe(false)
    expect(execution.availability).toBe('degraded')
    // 批 A（A-08）语义变更：非下载类超时 = 确定性收口（timeout + UI_BUDGET_EXHAUSTED），
    // 不再报「结局未知」（unknown 只留给下载类白名单）。
    expect(execution.outcome).toBe('timeout')
    expect(execution.code).toBe('UI_BUDGET_EXHAUSTED')
    expect(execution.result).toContain('执行超时')
    expect(execution.result).not.toContain('结局未知')
    unregisterPageHandler('probe.never-returns')
  })

  it('执行器在预算内给出真结局时，不被计时器误判为超时', async () => {
    registerPageHandler('probe.fast', () => '真结局文本')
    const pending = executeFrontendTool({ name: 'probe.fast', args: {}, budgetMs: 1_000 })
    await vi.advanceTimersByTimeAsync(999)
    const execution = await pending
    expect(execution.result).toContain('真结局文本')
    unregisterPageHandler('probe.fast')
  })

  it('parseToolArgs：非法 JSON 回落空对象（不抛）', () => {
    expect(parseToolArgs('{"a":1}')).toEqual({ a: 1 })
    expect(parseToolArgs('{oops')).toEqual({})
    expect(parseToolArgs(undefined)).toEqual({})
  })
})
