/**
 * 批次3 前端 · SSE 分阶段超时（只写不跑；本批聚焦 RED/GREEN 在 b3-compaction-stage.spec.ts）。
 *
 * 口径：
 * - 建连超时（响应头未回）→ 抛 `ApiError{code:CHAT_CONNECT_TIMEOUT}`；
 * - 首帧超时 40s（覆盖服务端首包 30s 预算 + 传输余量）→ `StreamCloseInfo.timeoutStage='first-frame'`，不重挂；
 * - 帧间 45s **不变**，`start` 后的 `heartbeat{phase:'compacting'}` 照常续命（不放宽上限）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import {
  DEFAULT_CONNECT_TIMEOUT_MS,
  DEFAULT_FIRST_FRAME_TIMEOUT_MS,
  DEFAULT_IDLE_TIMEOUT_MS,
  SSE_TIMEOUT_CODES,
  probeReattach,
  streamChat
} from '@/api/sse'
import type { StreamCloseInfo } from '@/types/ai'

const encoder = new TextEncoder()

function frameText(frame: Record<string, unknown>): Uint8Array {
  return encoder.encode(`data: ${JSON.stringify(frame)}\n\n`)
}

function controlledStream(): {
  stream: ReadableStream<Uint8Array>
  send: (frame: Record<string, unknown>) => void
  fail: () => void
} {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c
    }
  })
  return {
    stream,
    send: (frame) => controller.enqueue(frameText(frame)),
    fail: () => {
      try {
        controller.error(new DOMException('Aborted', 'AbortError'))
      } catch {
        // 已关闭
      }
    }
  }
}

function mockStreamingFetch(build: (signal: AbortSignal) => ReadableStream<Uint8Array>): void {
  vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => {
    const signal = init.signal as AbortSignal
    const stream = build(signal)
    return Promise.resolve(new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' }
    }))
  }))
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('批次3 · POST /chat 分阶段超时', () => {
  it('常量口径：建连 15s < 首帧 40s < 帧间 45s（后者不放宽）', () => {
    expect(DEFAULT_IDLE_TIMEOUT_MS).toBe(45_000)
    expect(DEFAULT_CONNECT_TIMEOUT_MS).toBe(15_000)
    expect(DEFAULT_FIRST_FRAME_TIMEOUT_MS).toBe(40_000)
    expect(DEFAULT_FIRST_FRAME_TIMEOUT_MS).toBeLessThan(DEFAULT_IDLE_TIMEOUT_MS)
  })

  it('建连超时：响应头未回即失败，错误码可区分（不是普通网络错误）', async () => {
    vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
      const signal = init.signal as AbortSignal
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    })))

    const pending = streamChat({ sessionId: 's', message: 'hi' })
    const assertion = expect(pending).rejects.toMatchObject({ code: SSE_TIMEOUT_CODES.connect })
    await vi.advanceTimersByTimeAsync(DEFAULT_CONNECT_TIMEOUT_MS)
    await assertion
  })

  it('首帧超时：已连上但零帧 ⇒ timeoutStage=first-frame（与帧间静默可分）', async () => {
    const body = controlledStream()
    mockStreamingFetch((signal) => {
      signal.addEventListener('abort', () => body.fail(), { once: true })
      return body.stream
    })

    let closed: StreamCloseInfo | null = null
    const pending = streamChat({ sessionId: 's', message: 'hi' }, {
      onClose: (info) => {
        closed = info
      }
    })

    await vi.advanceTimersByTimeAsync(DEFAULT_FIRST_FRAME_TIMEOUT_MS)
    const info = await pending
    expect(info.terminal).toBe(false)
    expect(info.timeoutStage).toBe('first-frame')
    expect(closed).toMatchObject({ timeoutStage: 'first-frame' })
    expect(info.reason).toContain('首帧超时')
  })

  it('probeReattach：202+preparing 返回真实 elapsedMs；200 只探测、不消费流', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({
      preparing: true,
      runId: 'run-prep',
      elapsedMs: 4_500,
      retryAfterMs: 2_000
    }), { status: 202, headers: { 'Content-Type': 'application/json' } })))

    const probe = await probeReattach('run-prep')
    expect(probe.status).toBe(202)
    expect(probe.preparing).toMatchObject({ runId: 'run-prep', elapsedMs: 4_500, retryAfterMs: 2_000 })

    const body = controlledStream()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(body.stream, { status: 200 })))
    const normal = await probeReattach('run-live')
    expect(normal.status).toBe(200)
    expect(normal.preparing).toBeNull()
  })

  it('start 后 45s 内无帧才判 idle；compacting 心跳每 20s 一次可无限续命', async () => {
    const body = controlledStream()
    mockStreamingFetch((signal) => {
      signal.addEventListener('abort', () => body.fail(), { once: true })
      return body.stream
    })

    let closed: StreamCloseInfo | null = null
    const pending = streamChat({ sessionId: 's', message: 'hi' }, {
      onClose: (info) => {
        closed = info
      }
    })

    body.send({ type: 'start', runId: 'run-b3', sessionId: 's' })
    await vi.advanceTimersByTimeAsync(20_000)
    body.send({ type: 'heartbeat', phase: 'compacting', elapsedMs: 20_000 })
    await vi.advanceTimersByTimeAsync(20_000)
    body.send({ type: 'heartbeat', phase: 'compacting', elapsedMs: 40_000 })
    await vi.advanceTimersByTimeAsync(20_000)
    body.send({ type: 'heartbeat', phase: 'compacting', elapsedMs: 60_000 })

    // 60s > 45s：心跳续命成功，没有被帧间看门狗误杀
    await vi.advanceTimersByTimeAsync(1_000)
    expect(closed).toBeNull()

    body.send({ type: 'done', runId: 'run-b3' })
    const info = await pending
    expect(info.terminal).toBe(true)
  })
})

/**
 * 前后端**首帧阈值**的静态契约（跨仓只读，不起服务）。
 *
 * <p>为什么需要：这一层有三个各自独立的数字 —— 后端上游首包预算（`app.ai.resilience.first-byte-timeout`）、
 * 前端首帧看门狗（{@link DEFAULT_FIRST_FRAME_TIMEOUT_MS}）、以及桩的 `stall-headers`（E2E 用它构造
 * "上游迟迟不回响应头"）。三者的**相对大小**决定用例是谁判的死：
 * <ul>
 * <li>后端首包 ≥ 前端首帧 ⇒ 前端先判 `CHAT_FIRST_FRAME_TIMEOUT`，S-03 拿不到后端 retry/error 帧，
 * 用例以错误的原因红/绿；</li>
 * <li>桩 stall ≤ 后端首包 ⇒ 看门狗根本不响（上游"按时"返回），S-03 退化成一次正常对话；</li>
 * <li>桩 stall == 前端首帧（历史值 40000 vs 40s）⇒ **撞线**：两侧同刻判死，谁先到凭调度抖动。</li>
 * </ul>
 * 故这里把"后端首包 &lt; 桩 stall &lt; 前端首帧"锁成严格不等式，并把 40000 那次的撞线钉成回归。
 */
describe('批次3 · 前后端首帧阈值静态契约（阈值不等式 + stall-headers 不撞线）', () => {
  const REPO = path.resolve(process.cwd(), '..')
  const BACKEND_YML = path.join(REPO, 'backend', 'src', 'main', 'resources', 'application.yml')
  const SRP_SPEC = path.join(process.cwd(), 'e2e', '10-srp.spec.ts')

  /** 后端上游首包预算（`first-byte-timeout: 30s`）；读不到即口径已变，必须同步本契约。 */
  function backendFirstByteMs(): number {
    const yml = readFileSync(BACKEND_YML, 'utf8')
    const match = /first-byte-timeout:\s*(\d+)s/.exec(yml)
    expect(match, 'application.yml 里读不到 app.ai.resilience.first-byte-timeout（需同步本契约）').toBeTruthy()
    return Number(match![1]) * 1000
  }

  /** S-03 实际用的桩 stall-headers（毫秒）。 */
  function e2eStallHeadersMs(): number {
    const source = readFileSync(SRP_SPEC, 'utf8')
    const match = /stall-headers=(\d+)/.exec(source)
    expect(match, '10-srp.spec.ts 里读不到 stall-headers=<ms>（S-03 的构造面已变，需同步本契约）').toBeTruthy()
    return Number(match![1])
  }

  it('后端首包 30s 严格小于前端首帧 40s（前端不得抢先判超时）', () => {
    expect(backendFirstByteMs()).toBe(30_000)
    expect(DEFAULT_FIRST_FRAME_TIMEOUT_MS).toBe(40_000)
    expect(DEFAULT_FIRST_FRAME_TIMEOUT_MS).toBeGreaterThan(backendFirstByteMs())
  })

  it('S-03 的 stall-headers 落在两侧阈值之间（严格，不与任一阈值撞线）', () => {
    const stall = e2eStallHeadersMs()
    // 小于/等于后端首包 ⇒ 看门狗不响，用例空转
    expect(stall).toBeGreaterThan(backendFirstByteMs())
    // 大于/等于前端首帧 ⇒ 前端先判死（40000 == 40s 就是这次要排掉的撞线）
    expect(stall).toBeLessThan(DEFAULT_FIRST_FRAME_TIMEOUT_MS)
    expect(stall).not.toBe(DEFAULT_FIRST_FRAME_TIMEOUT_MS)
    expect(stall).not.toBe(backendFirstByteMs())
  })
})
