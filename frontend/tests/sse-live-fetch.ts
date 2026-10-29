/**
 * reattach（fetch + ReadableStream）测试桩（Task 4 返修）。
 *
 * <p>reattach 不再用原生 `EventSource`（不能设身份头），既有 SSE 套件因此改为驱动**真实**的
 * fetch 流：`installLiveFetch(probe)` 把 `/api/ai/events/*` 的请求接成可控连接，
 * `?probe=1` 交给调用方提供的探测响应。这样断言仍落在生产路径上（解析、看门狗、退避、
 * 202/404 探测），不是对着桩函数自说自话。
 */
import { vi } from 'vitest'

export interface LiveConnection {
  url: string
  /** 被本端主动收流（看门狗/退避/abort）时为 true。 */
  closed: boolean
  /** 响应头到达（onOpen / 建连看门狗让位）。 */
  open(): void
  /** 推一帧 SSE（对象自动包 `data: {...}\n\n`，字符串原样）。 */
  push(frame: unknown): void
  /** 干净 EOF（服务端收流）。 */
  end(): void
  /** 建连失败（网络错误 / 非 200 之前就断了）。 */
  fail(): void
}

export interface LiveFetchHarness {
  connections: LiveConnection[]
}

type ProbeHandler = (url: string, init?: RequestInit) => Promise<Response>

/**
 * 安装 fetch 桩并返回事件流连接列表。
 *
 * @param probe `?probe=1` 的响应（每个探测请求一次）
 */
export function installLiveFetch(probe: ProbeHandler): LiveFetchHarness {
  const connections: LiveConnection[] = []
  const encoder = new TextEncoder()

  const fetchImpl = (input: string | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input)
    if (url.includes('probe=1')) {
      return probe(url, init)
    }
    return new Promise<Response>((resolve, reject) => {
      let controller: ReadableStreamDefaultController<Uint8Array> | null = null
      const body = new ReadableStream<Uint8Array>({
        start(streamController) {
          controller = streamController
        }
      })
      const response = {
        ok: true,
        status: 200,
        headers: {
          get: (name: string): string | null =>
            name.toLowerCase() === 'content-type' ? 'text/event-stream' : null
        },
        body,
        json: async () => ({})
      } as unknown as Response

      const connection: LiveConnection = {
        url,
        closed: false,
        open() {
          resolve(response)
        },
        push(frame) {
          const text = typeof frame === 'string' ? frame : `data: ${JSON.stringify(frame)}\n\n`
          controller?.enqueue(encoder.encode(text))
        },
        end() {
          try {
            controller?.close()
          } catch {
            // 已关闭：忽略
          }
        },
        fail() {
          reject(new TypeError('connection failed'))
        }
      }
      init?.signal?.addEventListener('abort', () => {
        connection.closed = true
        try {
          controller?.close()
        } catch {
          // 已关闭：忽略
        }
      })
      connections.push(connection)
    })
  }

  vi.stubGlobal('fetch', vi.fn(fetchImpl))
  return { connections }
}
