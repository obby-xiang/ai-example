/**
 * 仓外可行性实测 ③：纯逻辑层首批落点（包⑤预算折算 + T4-3 后半 foldDeadline 守卫）。
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
import { foldDeadline } from '@/stores/ai'

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
    expect(execution.result).toContain('前端超时')
    expect(execution.result).toContain('结局未知')
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

describe('foldDeadline 的 atMs > 0 守卫（T4-3 后半 / S3-R3 膨胀面）', () => {
  it('正常帧：atMs 与 expiresAt 同钟 → 剩余时长按 now 重算', () => {
    expect(foldDeadline(1_700_000_100_000, 1_700_000_000_000, 120, 500_000)).toBe(500_000 + 100_000)
  })

  it('atMs 缺失/为 0/为负 → 回落 expiresAt 绝对时刻（守卫挡住绝对 epoch 被当剩余时长）', () => {
    expect(foldDeadline(1_700_000_100_000, undefined, undefined, 500)).toBe(1_700_000_100_000)
    expect(foldDeadline(1_700_000_100_000, 0, undefined, 500)).toBe(1_700_000_100_000)
    expect(foldDeadline(1_700_000_100_000, -5_000, undefined, 500)).toBe(1_700_000_100_000)
  })

  it('三者皆无 → null（不武装本地计时器）', () => {
    expect(foldDeadline(undefined, undefined, undefined)).toBeNull()
  })

  it('S3-R3 已知绕过形态（红队登记项）可被用例固定下来：0 < atMs ≪ epoch + epoch 级 expiresAt → 剩余时长膨胀到 ~2079', () => {
    const now = Date.now()
    const inflated = foldDeadline(1_700_000_000_000, 1, undefined, now)
    expect(new Date(inflated as number).getUTCFullYear()).toBeGreaterThan(2078)
    // 守卫只挡 atMs<=0；正小值 + epoch 级 expiresAt 仍通过 —— 这正是红队登记的"守卫非密闭"面
    expect(foldDeadline(1_700_000_000_000, 0, undefined, now)).toBe(1_700_000_000_000)
  })
})
