/**
 * 仓外可行性实测 ②：sse.ts 双看门狗（R-03/B-03）—— 用 vitest fake timers 直接把 15s/90s
 * 计时器压缩到毫秒级，断言"任一时刻只武装一只看门狗""settle 后无残留计时器""建连黑洞必收敛"。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_IDLE_TIMEOUT_MS, reattach } from '@/api/sse'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  readonly url: string
  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }
  close(): void {
    this.closed = true
  }
  fireOpen(): void {
    this.onopen?.()
  }
}

beforeEach(() => {
  FakeEventSource.instances = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('reattach 双看门狗', () => {
  it('常量口径：建连 15s、帧间 90s（建连预算显著短于帧间）', () => {
    expect(DEFAULT_CONNECT_TIMEOUT_MS).toBe(15_000)
    expect(DEFAULT_IDLE_TIMEOUT_MS).toBe(90_000)
    expect(DEFAULT_CONNECT_TIMEOUT_MS).toBeLessThan(DEFAULT_IDLE_TIMEOUT_MS)
  })

  it('建连黑洞（既不 onopen 也不 error）：三轮 15s + 退避 1s/2s = 48s 内必收敛，不留计时器', async () => {
    let settled: { terminal: boolean; reason?: string } | null = null
    const pending = reattach('run-1', {}).then((info) => {
      settled = info
      return info
    })

    await vi.advanceTimersByTimeAsync(47_000)
    expect(settled).toBeNull() // 预算内不该提前判死

    await vi.advanceTimersByTimeAsync(1_000)
    const info = await pending
    expect(info.terminal).toBe(false)
    expect(info.reason).toContain('建立超时')
    expect(FakeEventSource.instances).toHaveLength(3)
    expect(vi.getTimerCount()).toBe(0) // settle 后无残留看门狗
  })

  it('onopen 后建连看门狗让位：不再因 15s 到点而误判断流（只留 90s 帧间看门狗）', async () => {
    let settled: unknown = null
    void reattach('run-2', {}).then((info) => {
      settled = info
    })
    FakeEventSource.instances[0]?.fireOpen()

    await vi.advanceTimersByTimeAsync(20_000)
    expect(settled).toBeNull()
    expect(FakeEventSource.instances[0]?.closed).toBe(false)
    expect(vi.getTimerCount()).toBe(1) // 只剩帧间看门狗
  })

  it('abort 立即收尾（不残留计时器、不再重连）', async () => {
    const controller = new AbortController()
    const pending = reattach('run-3', {}, { signal: controller.signal })
    controller.abort()
    const info = await pending
    expect(info.terminal).toBe(false)
    expect(info.reason).toContain('取消')
    expect(vi.getTimerCount()).toBe(0)
  })
})
