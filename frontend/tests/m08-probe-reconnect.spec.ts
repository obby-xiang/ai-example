/**
 * M08 · 重连探测（前端面）。
 *
 * <h2>被钉住的缺陷（真机 trace）</h2>
 * 断流后的探测原来打的是**正式 SSE 端点**并声明 `Accept: text/event-stream`：
 * 服务端因此把它当成一次真实重挂（建订阅、回放归档、续期保留期），而前端又在
 * `onerror` 里**先 await 探测、再**起退避 —— 探测一慢，1/2/4/8s 的退避时间线整体后移，
 * trace 里于是出现"5 条 EventSource + 5 条 probe fetch"的十次请求。
 *
 * <h2>本组口径（R1 返修后）</h2>
 * <ul>
 * <li>探测走 `?probe=1` 并声明 JSON（服务端只读状态，不建订阅）；</li>
 * <li>退避**立即**起算：探测挂起/失败都不得推迟首次重连（也不得多开连接）；</li>
 * <li>C1：`onReconnect` 是**用户通告**，只在"确实要开新连接"时发 —— 排期已经开始，
 *     但探测命中 202/404 后撤销排期，**一条重试通告都不发**；</li>
 * <li>I1：探测结果带**连接世代 + 尝试序号**，两者都未变才可应用；过期结果（含上一世代
 *     的慢 202）不得收掉/误判后来的连接，而"下一尝试前到达"的 202 仍必须正确进入准备中；</li>
 * <li>202 准备中 / 404 真不存在仍然"不重连即收尾"（AR-06 语义不回退）；</li>
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

interface Step {
  status: number
  body?: unknown
  delayMs: number
}

function asResponse(step: Step): Response {
  return new Response(step.body === undefined ? '' : JSON.stringify(step.body), {
    status: step.status,
    headers: { 'Content-Type': 'application/json' }
  })
}

/** 按调用顺序给每步一个响应（延迟用假计时器推进）；步数不够就重复最后一步。 */
function scriptedFetch(steps: Step[]): ReturnType<typeof vi.fn> {
  let index = 0
  const fetchMock = vi.fn(() => {
    const step = steps[Math.min(index, steps.length - 1)]
    index += 1
    if (step.delayMs <= 0) {
      return Promise.resolve(asResponse(step))
    }
    return new Promise<Response>((resolve) => {
      setTimeout(() => resolve(asResponse(step)), step.delayMs)
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** 永不 resolve 的探测（模拟探测挂起：请求既不到也不失败）。 */
function hangingFetch(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(() => new Promise<Response>(() => {}))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const PREPARING = (runId: string, elapsedMs: number): Step => ({
  status: 202,
  body: { preparing: true, runId, elapsedMs, retryAfterMs: 1_000 },
  delayMs: 0
})

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

describe('M08 · 探测不得推迟退避（C1：通告只随真实重连）', () => {
  it('探测挂起时首次重连仍在 1s 到点开第二条连接，且通告在到点时才发', async () => {
    hangingFetch()
    const onReconnect = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-hang', { onReconnect }, {
      signal: controller.signal,
      random: () => 0.5
    })

    FakeEventSource.instances[0]?.onerror?.()
    // C1：退避已从断流当刻起算，但"1 秒后重挂"这句通告还不该出现（探测可能把它撤销）
    expect(onReconnect).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1]?.url).toBe('/api/ai/events/run-hang')

    controller.abort()
    await pending
    // 收尾后不留退避/看门狗；挂起的探测由它自己的建连超时（15s）收口，不得无限占位
    await vi.advanceTimersByTimeAsync(15_000)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('探测 reject：不推迟首次重连，重连到点仍照常通告', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new TypeError('network down')))
    vi.stubGlobal('fetch', fetchMock)
    const onReconnect = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-reject', { onReconnect }, {
      signal: controller.signal,
      random: () => 0.5
    })

    FakeEventSource.instances[0]?.onerror?.()
    expect(onReconnect).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)
    expect(FakeEventSource.instances).toHaveLength(2)

    controller.abort()
    await pending
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('探测 503（degraded）：不推迟首次重连，也不误判准备中/封口', async () => {
    scriptedFetch([{ status: 503, body: { code: 'AI_REDIS_UNAVAILABLE', degraded: true }, delayMs: 0 }])
    const onReconnect = vi.fn()
    const onPreparing = vi.fn()
    const onUnavailable = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-503', { onReconnect, onPreparing, onUnavailable }, {
      signal: controller.signal,
      random: () => 0.5
    })

    FakeEventSource.instances[0]?.onerror?.()
    expect(onReconnect).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(onReconnect).toHaveBeenCalledWith(1, 1_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(onPreparing).not.toHaveBeenCalled()
    expect(onUnavailable).not.toHaveBeenCalled()

    controller.abort()
    await pending
  })

  it('重挂额度耗尽后不再发探测（5 条连接只对应 4 次探测）', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new TypeError('network down')))
    vi.stubGlobal('fetch', fetchMock)
    const onReconnect = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-exhaust', { onReconnect }, {
      signal: controller.signal,
      random: () => 0.5
    })

    for (const delay of [0, 1_000, 2_000, 4_000, 8_000]) {
      await vi.advanceTimersByTimeAsync(delay)
      FakeEventSource.instances.at(-1)?.onerror?.()
    }

    const info = await pending
    expect(info.reconnects).toBe(4)
    expect(FakeEventSource.instances).toHaveLength(5)
    // 第 5 条连接错误时重挂额度已耗尽（settle 先发生）⇒ 没有再发第 5 次探测
    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(vi.getTimerCount()).toBe(0)
    controller.abort()
  })
})

describe('M08 · 202/404 语义不回退（C1：撤销排期即不发任何重试通告）', () => {
  it('探测 202 准备中：回调 onPreparing、不重连、不发重试通告', async () => {
    scriptedFetch([PREPARING('run-prep', 4_000)])
    const onPreparing = vi.fn()
    const onReconnect = vi.fn()
    const pending = reattach('run-prep', { onPreparing, onReconnect }, { random: () => 0.5 })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(0)

    const info = await pending
    expect(onPreparing).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-prep', elapsedMs: 4_000 }))
    expect(info.reason).toContain('准备中')
    expect(onReconnect).not.toHaveBeenCalled()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('探测 404：封口在途轮（onUnavailable）并收尾，不重连、不发重试通告', async () => {
    scriptedFetch([{ status: 404, body: { code: 'UNKNOWN_RUN', runId: 'run-gone' }, delayMs: 0 }])
    const onUnavailable = vi.fn()
    const onReconnect = vi.fn()
    const pending = reattach('run-gone', { onUnavailable, onReconnect }, { random: () => 0.5 })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(0)

    const info = await pending
    expect(onUnavailable).toHaveBeenCalledTimes(1)
    expect(info.reason).toBeUndefined()
    expect(onReconnect).not.toHaveBeenCalled()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('M08 · 探测结果的世代归属（I1）', () => {
  it('慢 202 在下一尝试之前到达：仍正确进入准备中（不被世代判据误杀）', async () => {
    scriptedFetch([{ ...PREPARING('run-early', 7_000), delayMs: 700 }])
    const onPreparing = vi.fn()
    const onReconnect = vi.fn()
    const pending = reattach('run-early', { onPreparing, onReconnect }, { random: () => 0.5 })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(700)

    const info = await pending
    expect(onPreparing).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-early', elapsedMs: 7_000 }))
    expect(info.reason).toContain('准备中')
    // 撤销排期，一条重试通告都不发，也不开第二条连接
    expect(onReconnect).not.toHaveBeenCalled()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('过期 202（退避已开新连接）：不得关掉新连接，也不得回调准备中/封口（I4）', async () => {
    scriptedFetch([{ ...PREPARING('run-late', 9_000), delayMs: 1_200 }])
    const onPreparing = vi.fn()
    const onUnavailable = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-late', { onPreparing, onUnavailable }, {
      signal: controller.signal,
      random: () => 0.5
    })

    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances, '退避到点照常重连').toHaveLength(2)

    await vi.advanceTimersByTimeAsync(500)
    const second = FakeEventSource.instances[1]
    expect(second?.closed, '过期探测不得关掉新连接').toBe(false)
    expect(onPreparing, '过期探测不得回调准备中').not.toHaveBeenCalled()
    expect(onUnavailable, '过期探测不得封口').not.toHaveBeenCalled()

    controller.abort()
    await pending
  })

  it('上一世代的慢 202 不得替下一世代做决定（归属只认本世代的探测）', async () => {
    scriptedFetch([
      { ...PREPARING('run-gen', 1_111), delayMs: 1_100 },
      { ...PREPARING('run-gen', 2_222), delayMs: 500 }
    ])
    const onPreparing = vi.fn()
    const controller = new AbortController()
    const pending = reattach('run-gen', { onPreparing }, {
      signal: controller.signal,
      random: () => 0.5
    })

    // 世代 1：t=0 断流 ⇒ 探测#1（t=1100 才回 202），退避在 t=1000 已开世代 2
    FakeEventSource.instances[0]?.onerror?.()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeEventSource.instances).toHaveLength(2)
    // 世代 2 立刻也断：它自己发起探测#2（t=1500 回 202，elapsedMs=2222）
    FakeEventSource.instances[1]?.onerror?.()

    // t=1100：世代 1 的 202 到期 —— 它是过期结果，不得据此收尾
    await vi.advanceTimersByTimeAsync(100)
    expect(onPreparing, '上一世代的 202 不得决定下一世代').not.toHaveBeenCalled()

    // t=1500：世代 2 的 202 到期 —— 这才是本世代的权威结果
    await vi.advanceTimersByTimeAsync(400)
    const info = await pending
    expect(onPreparing).toHaveBeenCalledTimes(1)
    expect(onPreparing).toHaveBeenCalledWith(expect.objectContaining({ elapsedMs: 2_222 }))
    expect(info.reason).toContain('准备中')
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(vi.getTimerCount()).toBe(0)
  })
})
