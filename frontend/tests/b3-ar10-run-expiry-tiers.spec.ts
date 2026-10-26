/**
 * 批次3 补齐 · AR-10：运行态寿命的**纯函数**口径（只写不跑；聚焦 RED/GREEN 在
 * `b3-ar10-run-expiry.spec.ts`）。
 *
 * 锁三件事：
 * 1. 锚点只认后端给的值 —— `found=false` / 缺 `snapshotRemainingMs` / 非正值一律 null
 *    （不拿 24h 常量兜底，不自己造第二套到期时间）；
 * 2. 剩余额只按"本地收到时刻之后流逝"扣减，下界 0（= 已到期）；
 * 3. 读秒分档：<10 分钟 mm:ss、<1 小时按分钟、更长按小时；到 0 走「已失效」。
 */
import { describe, expect, it } from 'vitest'
import { runExpiryAnchorOf, runExpiryLabel, runExpiryRemainingMs } from '@/utils/run-expiry'
import type { AiRunCurrent } from '@/types/ai'

function current(overrides: Partial<AiRunCurrent> = {}): AiRunCurrent {
  return {
    found: true,
    runId: 'run-1',
    snapshotStatus: 'SUSPENDED',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 1,
    ...overrides
  }
}

describe('AR-10 · 运行态寿命（只读推导）', () => {
  it('锚点只认后端权威值：空态 / 缺键 / 非正剩余一律 null', () => {
    expect(runExpiryAnchorOf(current(), 1_000)).toBeNull()
    expect(runExpiryAnchorOf(current({ found: false }), 1_000)).toBeNull()
    expect(runExpiryAnchorOf(current({ runId: null }), 1_000)).toBeNull()
    expect(runExpiryAnchorOf(current({ snapshotRemainingMs: null }), 1_000)).toBeNull()
    expect(runExpiryAnchorOf(current({ snapshotRemainingMs: 0 }), 1_000)).toBeNull()
    expect(runExpiryAnchorOf(current({ snapshotRemainingMs: -5 }), 1_000)).toBeNull()
    expect(runExpiryAnchorOf(current({ snapshotRemainingMs: Number.NaN }), 1_000)).toBeNull()
  })

  it('锚点保留轮次归属与到期时刻；缺到期时刻时只保留剩余额', () => {
    expect(runExpiryAnchorOf(current({ snapshotRemainingMs: 60_000, snapshotExpiresAt: 1_060_000 }), 1_000))
      .toEqual({ runId: 'run-1', remainingMs: 60_000, expiresAt: 1_060_000, receivedAtMs: 1_000 })
    expect(runExpiryAnchorOf(current({ snapshotRemainingMs: 60_000, snapshotExpiresAt: null }), 1_000))
      .toEqual({ runId: 'run-1', remainingMs: 60_000, expiresAt: null, receivedAtMs: 1_000 })
  })

  it('剩余额只扣本地流逝，下界 0', () => {
    const anchor = { runId: 'run-1', remainingMs: 5_000, expiresAt: null, receivedAtMs: 10_000 }
    expect(runExpiryRemainingMs(anchor, 10_000)).toBe(5_000)
    expect(runExpiryRemainingMs(anchor, 13_000)).toBe(2_000)
    expect(runExpiryRemainingMs(anchor, 15_000)).toBe(0)
    expect(runExpiryRemainingMs(anchor, 99_000)).toBe(0)
  })

  it('读秒分档：<10min 读秒、<1h 按分钟、更长按小时、0 已失效', () => {
    expect(runExpiryLabel(120_000)).toEqual({
      key: 'ai.panel.runExpiry.countdown',
      params: { time: '02:00' },
      expired: false
    })
    expect(runExpiryLabel(599_999).params).toEqual({ time: '10:00' })
    expect(runExpiryLabel(600_000)).toEqual({
      key: 'ai.panel.runExpiry.minutes',
      params: { minutes: 10 },
      expired: false
    })
    expect(runExpiryLabel(3_599_999).params).toEqual({ minutes: 60 })
    expect(runExpiryLabel(3_600_000)).toEqual({
      key: 'ai.panel.runExpiry.hours',
      params: { hours: 1 },
      expired: false
    })
    expect(runExpiryLabel(0)).toEqual({ key: 'ai.panel.runExpiry.expired', params: {}, expired: true })
    expect(runExpiryLabel(-1).expired).toBe(true)
  })
})
