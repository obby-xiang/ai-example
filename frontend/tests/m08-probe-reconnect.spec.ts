/**
 * M08 · 重连探测（前端面）。
 *
 * <h2>被钉住的缺陷（真机 trace）</h2>
 * 断流后的探测原来打的是**正式 SSE 端点**并声明 `Accept: text/event-stream`：
 * 服务端因此把它当成一次真实重挂（建订阅、回放归档、续期保留期），而前端又在
 * `onerror` 里**先 await 探测、再**起退避 —— 探测一慢，1/2/4/8s 的退避时间线整体后移，
 * trace 里于是出现"5 条 EventSource + 5 条 probe fetch"的十次请求。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>探测走 `?probe=1` 并声明 JSON（服务端只读状态，不建订阅）；</li>
 * <li>退避**立即**起算：探测挂起/失败都不得推迟首次重连（也不得多开连接）；</li>
 * <li>202 准备中 / 404 真不存在仍然"不重连即收尾"（AR-06 语义不回退），
 *     且**过期**探测结果不得收掉退避后已经建立的新连接；</li>
 * <li>真实连接次数仍由 EventSource 计数：探测本身不构造 EventSource。</li>
 * </ul>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { probeReattach, reattach } from '@/api/sse'

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
}

/** 永不 resolve 的探测（模拟探测挂起：请求既不到也不失败）。 */
function hangingFetch(): void {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
}

/** 探测在 `ms` 后才给出响应（假计时器下推进）。 */
function delayedFetch(ms: number, build: () => Response): void {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => {
    setTimeout(() => resolve(build()), ms)
  })))
}

beforeEach(() => {
  FakeEventSource.instances = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('M08 · 探测端点与请求头', () => {
  it('probeReattach 打 ?probe=1 且声明 JSON Accept；不构造 EventSource', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({
      ready: true,
      preparing: false,
      runId: 'run-live',
      status: 'RUNNING'
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const probe = await probeReattach('run-live')

    expect(probe.status).toBe(200)
    expect(probe.preparing).toBeNull()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/ai/events/run-live?probe=1')
    expect(String((init.headers as Record<string, string>).Accept)).toBe('application/json')
    // 探测只是 HTTP 读：一条 EventSource 都不该被它建出来
    expect(FakeEventSource.instances).toHaveLength(0)
  })
})

describe('M08 · 探测不得推迟退避', () => {
  it('探测挂起时首次重连仍在 1s 到点开第二条连接（不放宽、不多开）', async () => {
    hangingFetch()
    const onReconnect = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-hang', { onReconnect }, {
      signal: controller.signal,
      random: () => 0.5
    })

    FakeEventSource.instances[0]?.onerror?.()
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1]?.url).toBe('/api/ai/events/run-hang')

    controller.abort()
    await pending
    // 收尾后不留退避/看门狗；挂起的探测由它自己的建连超时（15s）收口，不得无限占位
    await vi.advanceTimersByTimeAsync(15_000)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('探测在退避之后才回 202：过期结果不得收掉已建立的新连接（AR-06 也不误判）', async () => {
    delayedFetch(1_200, () => new Response(JSON.stringify({
      preparing: true,
      runId: 'run-late',
      elapsedMs: 9_000,
      retryAfterMs: 1_000
    }), { status: 202, headers: { 'Content-Type': 'application/json' } }))
    const onPreparing = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-late', { onPreparing }, {
      signal: controller.signal,
      random: () => 0.5
    })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances, '退避到点照常重连').toHaveLength(2)

    await vi.advanceTimersByTimeAsync(500)
    const second = FakeEventSource.instances[1]
    expect(second?.closed, '过期探测不得关掉新连接').toBe(false)

    controller.abort()
    await pending
  })
})

describe('M08 · 202/404 语义不回退', () => {
  it('探测 202 准备中：不重连、按既有路径收尾并回调 onPreparing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      preparing: true,
      runId: 'run-prep',
      elapsedMs: 4_000,
      retryAfterMs: 1_000
    }), { status: 202, headers: { 'Content-Type': 'application/json' } })))
    const onPreparing = vi.fn()
    const onReconnect = vi.fn()
    const pending = reattach('run-prep', { onPreparing, onReconnect }, { random: () => 0.5 })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(0)

    const info = await pending
    expect(onPreparing).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-prep', elapsedMs: 4_000 }))
    expect(info.reason).toContain('准备中')
    // 退避已按时间线排期（通告只发一次），命中 202 后随即撤销：不真正开第二条连接
    expect(onReconnect).toHaveBeenCalledTimes(1)
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('探测 404：封口在途轮（onUnavailable）并收尾，不重连', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'UNKNOWN_RUN',
      runId: 'run-gone'
    }), { status: 404, headers: { 'Content-Type': 'application/json' } })))
    const onUnavailable = vi.fn()
    const onReconnect = vi.fn()
    const pending = reattach('run-gone', { onUnavailable, onReconnect }, { random: () => 0.5 })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(0)

    const info = await pending
    expect(onUnavailable).toHaveBeenCalledTimes(1)
    expect(info.reason).toBeUndefined()
    // 排期已按时间线发出（一次），但 404 判定后撤销：不开第二条连接
    expect(onReconnect).toHaveBeenCalledTimes(1)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})
