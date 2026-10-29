/**
 * `@/api/ai#chat` 的**可中止假传输**（2026-10-29 返修 R1 起共用）。
 *
 * <h2>为什么不用"永不 resolve"的桩</h2>
 * 那种桩把"连接是否被 abort"这一事实抹掉了 —— 而 M-19（start 帧之前停止）修的正是这件事：
 * 停止若直接 abort，start 帧根本到不了，补发 cancel 的路径就成了不可达的假绿。
 *
 * <h2>忠于真实 `api/sse.ts` 的三条语义</h2>
 * <ol>
 *   <li>`signal` abort ⇒ 调 `onClose({ terminal: false, reason: '连接已中止' })` 后 **resolve**
 *       （真实实现"只 resolve、不 reject"，错误经 onClose 表达）；</li>
 *   <li>abort 之后不再投递任何帧（真实读循环在每次读前检查 signal）；</li>
 *   <li>帧由测试按序 `emit`，走 store 真实的 `onFrame` 回调（`handleFrame` 的归属/闸门全在链上）。</li>
 * </ol>
 */
import type { SseFrameHandlers, SseStreamOptions } from '@/api/sse'
import type { SseFrame } from '@/types/sse'

export interface FakeTransport {
  handlers: SseFrameHandlers
  options: SseStreamOptions
  /** 已按信号中止（真实语义：之后不再投递帧） */
  aborted: boolean
  /** 已收尾（终帧 / 主动 close；不能再投帧） */
  settled: boolean
  /** 投一帧（仅在未中止、未收尾时生效） */
  emit(frame: SseFrame): void
  /** 模拟服务端收流 / 终态收尾（会调 onClose 并 resolve） */
  close(info?: { terminal?: boolean; suspended?: boolean; reason?: string }): void
  /** 该连接收到的帧（断言"某帧有没有被投递"用） */
  received: SseFrame[]
}

export interface FakeChat {
  /** 交给 `vi.mock('@/api/ai')` 的 `aiApi.chat` 实现 */
  impl: (body: unknown, handlers: SseFrameHandlers, options?: SseStreamOptions) => Promise<void>
  /** 全部已建立的连接（按建立顺序） */
  transports: FakeTransport[]
  last(): FakeTransport
}

export function createFakeChat(): FakeChat {
  const transports: FakeTransport[] = []
  const impl = (
    _body: unknown,
    handlers: SseFrameHandlers,
    options: SseStreamOptions = {}
  ): Promise<void> => {
    return new Promise<void>((resolve) => {
      const transport: FakeTransport = {
        handlers,
        options,
        aborted: false,
        settled: false,
        received: [],
        emit(frame: SseFrame): void {
          if (transport.aborted || transport.settled) {
            return
          }
          transport.received.push(frame)
          handlers.onFrame?.(frame)
        },
        close(info = {}): void {
          if (transport.settled) {
            return
          }
          transport.settled = true
          handlers.onClose?.({
            terminal: info.terminal ?? false,
            suspended: info.suspended,
            reason: info.reason
          } as Parameters<NonNullable<SseFrameHandlers['onClose']>>[0])
          resolve()
        }
      }
      transports.push(transport)
      const signal = options.signal
      if (signal === undefined) {
        return
      }
      if (signal.aborted) {
        // 建立即已中止：真实实现照样走"连接已中止"的收尾
        transport.aborted = true
        transport.handlers.onClose?.({ terminal: false, reason: '连接已中止' } as Parameters<
          NonNullable<SseFrameHandlers['onClose']>
        >[0])
        transport.settled = true
        resolve()
        return
      }
      signal.addEventListener('abort', () => {
        if (transport.settled) {
          return
        }
        transport.aborted = true
        transport.handlers.onClose?.({ terminal: false, reason: '连接已中止' } as Parameters<
          NonNullable<SseFrameHandlers['onClose']>
        >[0])
        transport.settled = true
        resolve()
      }, { once: true })
    })
  }
  return {
    impl,
    transports,
    last(): FakeTransport {
      const last = transports[transports.length - 1]
      if (!last) {
        throw new Error('没有建立过 /chat 传输：send() 没有走到 aiApi.chat')
      }
      return last
    }
  }
}
