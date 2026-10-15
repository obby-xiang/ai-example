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
import {
  frameDeadlineLocal,
  heartbeatDeadlineLocal,
  isFreshFrame,
  isStaleFrame
} from '@/stores/ai'

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

describe('R-166 倒计时口径：陈旧帧判据 + 两源折算（原 foldDeadline 差值折算已废弃）', () => {
  it('frameDeadlineLocal：新鲜帧按服务端剩余时长折算（expiresAt − atMs + now）', () => {
    expect(frameDeadlineLocal(1_700_000_100_000, 1_700_000_000_000, 120, 500_000)).toBe(500_000 + 100_000)
  })

  it('frameDeadlineLocal：atMs 缺失/为 0/为负 → 回落 expiresAt 绝对时刻（守卫挡住绝对 epoch 被当剩余时长）', () => {
    expect(frameDeadlineLocal(1_700_000_100_000, undefined, undefined, 500)).toBe(1_700_000_100_000)
    expect(frameDeadlineLocal(1_700_000_100_000, 0, undefined, 500)).toBe(1_700_000_100_000)
    expect(frameDeadlineLocal(1_700_000_100_000, -5_000, undefined, 500)).toBe(1_700_000_100_000)
  })

  it('frameDeadlineLocal：三者皆无 → null（不武装本地计时器）', () => {
    expect(frameDeadlineLocal(undefined, undefined, undefined)).toBeNull()
  })

  it('isFreshFrame / isStaleFrame：|atMs − now| ≤ 5s 判新；缺 atMs 无法判新（不拦，走旧格式回落）', () => {
    const now = 1_700_000_000_000
    expect(isFreshFrame(now - 500, now)).toBe(true)
    expect(isFreshFrame(now - 5_000, now)).toBe(true)
    expect(isStaleFrame(now - 500, now)).toBe(false)
    // 陈旧帧（重挂回放 / 刷新恢复给出同一对旧值）—— 症②的成因，必须拦住
    expect(isFreshFrame(now - 5_001, now)).toBe(false)
    expect(isStaleFrame(now - 5_001, now)).toBe(true)
    expect(isStaleFrame(now - 120_000, now)).toBe(true)
    // 缺 atMs / 非正值：不是"陈旧"，而是"无法判新"（旧格式帧，回落口径处理）
    expect(isStaleFrame(undefined, now)).toBe(false)
    expect(isStaleFrame(0, now)).toBe(false)
    expect(isStaleFrame(-1, now)).toBe(false)
  })

  it('heartbeatDeadlineLocal：剩余秒 = timeoutSeconds − waitedSeconds（两秒数同帧单点取值）', () => {
    const now = 1_700_000_000_000
    expect(heartbeatDeadlineLocal(240, 60, now)).toBe(now + 180_000)
    expect(heartbeatDeadlineLocal(240, 0, now)).toBe(now + 240_000)
    // 倒挂（已等待超过上限）归零，不产负剩余
    expect(heartbeatDeadlineLocal(120, 300, now)).toBe(now)
    // waitedSeconds 缺失/非法按 0（不因缺键把倒计时归零）
    expect(heartbeatDeadlineLocal(120, undefined, now)).toBe(now + 120_000)
    expect(heartbeatDeadlineLocal(120, Number.NaN, now)).toBe(now + 120_000)
    expect(heartbeatDeadlineLocal(120, -5, now)).toBe(now + 120_000)
    // timeoutSeconds 缺失/非法 → null（该帧不参与倒计时）
    expect(heartbeatDeadlineLocal(undefined, 10, now)).toBeNull()
    expect(heartbeatDeadlineLocal(0, 10, now)).toBeNull()
  })

  it('两源同源：确认帧与心跳给出的**绝对到期**同值（后端 expiresAt = atMs + timeout×1000）', () => {
    const frameAtMs = 1_700_000_000_000
    const timeout = 240
    const expiresAt = frameAtMs + timeout * 1000
    // 帧构造后 60s 的心跳：atMs_hb = frameAtMs + 60_000、waitedSeconds = 60
    const hbAtMs = frameAtMs + 60_000
    // heartbeatDeadlineLocal 返回的是"本地钟"的到期时刻，减去接收时刻即回得"服务端剩余"
    const beatRemainMs = heartbeatDeadlineLocal(timeout, 60, hbAtMs)! - hbAtMs
    expect(hbAtMs + beatRemainMs).toBe(expiresAt)
    // waitedSeconds 取整（59 而实际 59.9s）⇒ 心跳推出的到期比帧口径偏晚不到 1s，
    // 故 store 并入取 min ⇒ 倒计时单调不回退（既不吃心跳的 1s 抖动，也不被帧打回起点）
    const flooredRemainMs = heartbeatDeadlineLocal(timeout, 59, hbAtMs)! - hbAtMs
    expect(flooredRemainMs).toBeGreaterThan(beatRemainMs)
    expect(flooredRemainMs).toBeLessThanOrEqual(beatRemainMs + 1_000)
  })
})
