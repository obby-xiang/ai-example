/**
 * sse.ts 双看门狗（R-03/B-03）：用 vitest fake timers 把 15s/45s 计时器压缩到毫秒级，
 * 断言"任一时刻只武装一只看门狗""settle 后无残留计时器""建连黑洞必收敛"。
 *
 * 超时面治理批②（决策看板 `:16`）后追加两组断言：
 * - 帧间静默 90s → **45s**（< 代理 60s 死区；后端 90s 不动，错层）；
 * - 重挂 2 次（1s/2s）→ **4 次（1/2/4/8s + jitter ±25%）**，耗尽仍 `settle(false)`。
 *
 * Task 4 返修：reattach 从 `EventSource` 改为带身份头的 `fetch + ReadableStream`，本套件改为
 * 驱动**真实**的 fetch 流（`tests/sse-live-fetch.ts`）—— 连接计数 = 事件流请求数，
 * 断流 = 连接 `fail()` / `end()`，仍是生产路径。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
  DEFAULT_CONNECT_TIMEOUT_MS,
  DEFAULT_IDLE_TIMEOUT_MS,
  DEFAULT_MAX_RECONNECTS,
  RECONNECT_JITTER_RATIO,
  reattach,
  reconnectDelayMs
} from '@/api/sse'
import { installLiveFetch, type LiveConnection } from './sse-live-fetch'

let connections: LiveConnection[] = []

/** 探测永远失败（状态 0）：断流后的退避/探测判定保持"纯连接失败"口径。 */
function installHarness(): void {
  connections = installLiveFetch(() => Promise.reject(new TypeError('probe unavailable'))).connections
}

/** 让 open/fail 引出的微任务（onFailure → scheduleReconnect / onOpen → startIdle）落地。 */
async function flush(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0)
}

function last(): LiveConnection | undefined {
  return connections.at(-1)
}

beforeEach(() => {
  vi.useFakeTimers()
  installHarness()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('reattach 双看门狗', () => {
  it('常量口径：建连 15s、帧间 45s、重挂 4 次（建连预算显著短于帧间）', () => {
    expect(DEFAULT_CONNECT_TIMEOUT_MS).toBe(15_000)
    expect(DEFAULT_IDLE_TIMEOUT_MS).toBe(45_000)
    expect(DEFAULT_MAX_RECONNECTS).toBe(4)
    expect(DEFAULT_CONNECT_TIMEOUT_MS).toBeLessThan(DEFAULT_IDLE_TIMEOUT_MS)
  })

  it('45s 低于代理 60s 死区、低于后端 90s（错层：前端先判、先复位 loading）', () => {
    const PROXY_DEFAULT_IDLE_MS = 60_000
    const BACKEND_INTER_EVENT_MS = 90_000
    expect(DEFAULT_IDLE_TIMEOUT_MS).toBeLessThan(PROXY_DEFAULT_IDLE_MS)
    expect(PROXY_DEFAULT_IDLE_MS).toBeLessThan(BACKEND_INTER_EVENT_MS)
  })

  it('退避序列 1/2/4/8s + jitter ±25%', () => {
    // random=0.5 ⇒ 因子 1.0（中点 = 裸指数退避）
    expect([1, 2, 3, 4].map((attempt) => reconnectDelayMs(attempt, () => 0.5))).toEqual([
      1_000, 2_000, 4_000, 8_000
    ])
    // random=0 ⇒ 下界 0.75×；random=1 ⇒ 上界 1.25×
    expect([1, 2, 3, 4].map((attempt) => reconnectDelayMs(attempt, () => 0))).toEqual([
      750, 1_500, 3_000, 6_000
    ])
    expect([1, 2, 3, 4].map((attempt) => reconnectDelayMs(attempt, () => 1))).toEqual([
      1_250, 2_500, 5_000, 10_000
    ])
    expect(RECONNECT_JITTER_RATIO).toBe(0.25)
  })

  it('建连黑洞（既不 onopen 也不 error）：5 条连接 15s + 退避 1/2/4/8s = 90s 内必收敛，不留计时器', async () => {
    let settled: { terminal: boolean; reason?: string; reconnects: number } | null = null
    const pending = reattach('run-1', {}, { random: () => 0.5 }).then((info) => {
      settled = info
      return info
    })

    await vi.advanceTimersByTimeAsync(89_000)
    expect(settled).toBeNull() // 预算内不该提前判死

    await vi.advanceTimersByTimeAsync(1_000)
    const info = await pending
    expect(info.terminal).toBe(false)
    expect(info.reason).toContain('建立超时')
    expect(info.reconnects).toBe(4)
    // 首连 + 4 次重挂 = 5 条连接（旧口径是 3 条）
    expect(connections).toHaveLength(5)
    expect(vi.getTimerCount()).toBe(0) // settle 后无残留看门狗
  })

  it('帧间静默 45s 到点即断流并重挂（不再等 90s）', async () => {
    let reconnects = 0
    const controller = new AbortController()
    const pending = reattach(
      'run-4',
      { onReconnect: () => { reconnects += 1 } },
      { signal: controller.signal, random: () => 0.5 }
    )
    last()?.open()
    await flush()

    await vi.advanceTimersByTimeAsync(44_000)
    expect(connections).toHaveLength(1) // 45s 之前不该判死
    expect(last()?.closed).toBe(false)

    await vi.advanceTimersByTimeAsync(2_500) // 45s 到点 + 1s 退避（random=0.5 ⇒ 因子 1.0）
    expect(reconnects).toBe(1)
    expect(connections).toHaveLength(2)
    expect(connections[0]?.closed).toBe(true)

    controller.abort()
    await pending
    expect(vi.getTimerCount()).toBe(0)
  })

  it('onopen 后建连看门狗让位：不再因 15s 到点而误判断流（只留 45s 帧间看门狗）', async () => {
    let settled: unknown = null
    void reattach('run-2', {}).then((info) => {
      settled = info
    })
    last()?.open()
    await flush()

    await vi.advanceTimersByTimeAsync(20_000)
    expect(settled).toBeNull()
    expect(last()?.closed).toBe(false)
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

describe('reattach 挂起快照恢复', () => {
  it('已知本轮等待外部输入时，空回放后收流是正常挂起收尾，不重连', async () => {
    const onReconnect = vi.fn()
    const pending = reattach('run-parked', { onReconnect }, {
      lastSeq: 4,
      awaitingInput: true,
      random: () => 0.5
    })
    const source = last()
    expect(source?.url).toBe('/api/ai/events/run-parked?lastSeq=4')
    source?.open()
    await flush()
    source?.end()
    await flush()

    const info = await pending
    expect(info.terminal).toBe(false)
    expect(info.suspended).toBe(true)
    expect(info.reconnects).toBe(0)
    expect(onReconnect).not.toHaveBeenCalled()
    expect(connections).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('决策后的续跑不误用挂起状态，断流仍按原退避重连', async () => {
    const onReconnect = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-resumed', { onReconnect }, {
      signal: controller.signal,
      lastSeq: 4,
      random: () => 0.5
    })
    last()?.open()
    await flush()
    last()?.end()
    await flush()
    // C1（M08）：通告只随真实重连发出 —— 排期在断流当刻、通告在到点
    expect(onReconnect).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)
    expect(connections).toHaveLength(2)
    expect(connections[1]?.url).toBe('/api/ai/events/run-resumed?lastSeq=4')
    controller.abort()
    await pending
    expect(vi.getTimerCount()).toBe(0)
  })

  it('决策帧本身不证明已离开挂起：回放后正常收流仍不重连', async () => {
    const onReconnect = vi.fn()
    const pending = reattach('run-decision', { onReconnect }, { awaitingInput: true, lastSeq: 4 })
    const source = last()
    source?.open()
    await flush()
    source?.push({
      type: 'confirm_decision', runId: 'run-decision', toolCallId: 'c1', name: 'start_export',
      decision: 'REJECTED', status: 'REJECTED', approved: false, seq: 5
    })
    await flush()
    source?.end()
    await flush()

    const info = await pending
    expect(info.suspended).toBe(true)
    expect(onReconnect).not.toHaveBeenCalled()
    expect(connections).toHaveLength(1)
  })

  it('他端决策后的真实执行帧会清除挂起初态，后续断流仍重连', async () => {
    const onReconnect = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-progress', { onReconnect }, {
      signal: controller.signal,
      awaitingInput: true,
      lastSeq: 4,
      random: () => 0.5
    })
    const source = last()
    source?.open()
    await flush()
    source?.push({ type: 'delta', runId: 'run-progress', messageId: 'm1', seg: 0, text: '继续执行', seq: 5 })
    await flush()
    source?.end()
    await flush()
    // C1（M08）：通告在到点（确实要开新连接）时发，不在断流当刻预告
    expect(onReconnect).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)
    expect(connections).toHaveLength(2)
    controller.abort()
    const info = await pending
    expect(info.suspended).toBeFalsy()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('挂起快照不能吞掉建连失败：保留四次退避，耗尽后明确断流', async () => {
    const onReconnect = vi.fn()
    const pending = reattach('run-unreachable', { onReconnect }, {
      awaitingInput: true,
      lastSeq: 4,
      random: () => 0.5
    })
    for (const delay of [0, 1_000, 2_000, 4_000, 8_000]) {
      if (delay > 0) {
        await vi.advanceTimersByTimeAsync(delay)
      }
      last()?.fail()
      await flush()
    }

    const info = await pending
    expect(info.terminal).toBe(false)
    expect(info.suspended).toBeFalsy()
    expect(info.reason).toContain('重挂连接中断')
    expect(info.reconnects).toBe(4)
    expect(onReconnect).toHaveBeenCalledTimes(4)
    expect(connections).toHaveLength(5)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('已有挂起快照也不覆盖终帧：done 仍按终态收尾', async () => {
    const pending = reattach('run-terminal', {}, { awaitingInput: true, lastSeq: 4 })
    const source = last()
    source?.open()
    await flush()
    source?.push({ type: 'done', runId: 'run-terminal', status: 'DONE', seq: 5 })
    await flush()
    source?.end()
    await flush()

    const info = await pending
    expect(info.terminal).toBe(true)
    expect(info.suspended).toBeFalsy()
    expect(info.reconnects).toBe(0)
    expect(connections).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})
