/**
 * 批次3 前端 · SSE 分阶段超时（只写不跑；本批聚焦 RED/GREEN 在 b3-compaction-stage.spec.ts）。
 *
 * 口径：
 * - 建连超时（响应头未回）→ 抛 `ApiError{code:CHAT_CONNECT_TIMEOUT}`；
 * - 首帧超时（已连上但 0 帧）→ `StreamCloseInfo.timeoutStage='first-frame'`，不重挂；
 * - 帧间 45s **不变**，`start` 后的 `heartbeat{phase:'compacting'}` 照常续命（不放宽上限）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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
  it('常量口径：建连 15s < 首帧 30s < 帧间 45s（后者不放宽）', () => {
    expect(DEFAULT_IDLE_TIMEOUT_MS).toBe(45_000)
    expect(DEFAULT_CONNECT_TIMEOUT_MS).toBe(15_000)
    expect(DEFAULT_FIRST_FRAME_TIMEOUT_MS).toBe(30_000)
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
