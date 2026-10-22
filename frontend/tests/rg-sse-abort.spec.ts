/**
 * 批G · S6：SSE 读循环在中止后不再消费"已到达的整块帧"。
 *
 * <p>
 * 实证 D8：停止后 60ms 内还能新建工具卡、改写卡状态 —— abort 只断网络，不撤回
 * `reader.read()` 已经返回的那一整块帧；旧实现会把它全部 consumeFrame 掉。
 */
import { describe, expect, it, vi, afterEach } from 'vitest'
import { streamChat } from '@/api/sse'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('批G · S6：停止后不再写迟到帧', () => {
  it('中止发生在"读已返回、尚未消费"的窗口：该块帧不落地', async () => {
    const encoder = new TextEncoder()
    const push: { second: (() => void) | null } = { second: null }
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"type":"delta","text":"A","runId":"r1","seq":2}\n\n'))
        push.second = () => {
          controller.enqueue(encoder.encode('data: {"type":"delta","text":"B","runId":"r1","seq":3}\n\n'))
          controller.close()
        }
      }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: stream }))

    const controller = new AbortController()
    const texts: string[] = []
    const promise = streamChat(
      { sessionId: 's', message: '问' } as never,
      { onFrame: (frame) => texts.push((frame as { text?: string }).text ?? '') },
      { signal: controller.signal }
    )

    await vi.waitFor(() => expect(texts).toEqual(['A']))
    controller.abort()
    push.second?.()

    const info = await promise
    expect(texts).toEqual(['A']) // B 属于停止之后的块：一条都不写
    expect(info.terminal).toBe(false)
  })
})
