/**
 * A3-Y7：SSE 通道必须带 `Accept-Language`（与 axios 通道同一取值源 `acceptLanguage()`）。
 *
 * <p>修复前：`POST /api/ai/chat` 用裸 `fetch` 只带 `Content-Type`/`Accept`，后端
 * `ErrorBodyFactory.requestLocale()` 在 SSE 路径恒回落 `zh-CN` —— 同一个错误码
 * （`SESSION_BUSY` / `MESSAGE_TOO_LONG` / 503）在 `/api/ai/confirm`、`/api/ai/health` 上是英文、
 * 在 `/api/ai/chat` 上是中文，前端再把这条中文 message 塞进本地化模板，形成"英文外壳 + 中文内核"。
 *
 * <p>同时它是 A3-Y1（4B：默认中文、随用户语言）的**来源**：后端拿到请求 locale 才能把
 * 「回答语言（本轮）」写进系统消息；这条头缺失时，界面语言根本到不了模型。
 *
 * <p><b>证红方式</b>：删掉 `streamChat` 请求头里的 `Accept-Language` ⇒ 两条用例全红。
 */
import { describe, expect, it, vi, afterEach } from 'vitest'
import { streamChat } from '@/api/sse'
import { applyLocale } from '@/locales'

const encoder = new TextEncoder()

function sseBody(frames: unknown[]): ReadableStream<Uint8Array> {
  const text = frames.map((item) => `data: ${JSON.stringify(item)}\n\n`).join('')
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(text))
      controller.close()
    }
  })
}

function stubFetch(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    body: sseBody([
      { type: 'start', runId: 'run-lang', sessionId: 's-lang', seq: 1 },
      { type: 'done', runId: 'run-lang', seq: 2, cancelled: false }
    ])
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function chatHeaders(fetchMock: ReturnType<typeof vi.fn>): Record<string, string> {
  const call = fetchMock.mock.calls[0]
  expect(call, '必须真的发出过 /api/ai/chat').toBeTruthy()
  const init = call?.[1] as RequestInit | undefined
  return (init?.headers ?? {}) as Record<string, string>
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('A3-Y7：SSE 聊天请求的语言来源', () => {
  it('中文界面下带 Accept-Language: zh-CN（默认，不改变既有中文口径）', async () => {
    const fetchMock = stubFetch()

    await streamChat({ sessionId: 's-lang', message: '你好' } as never, {})

    const headers = chatHeaders(fetchMock)
    expect(headers['Accept-Language']).toBe('zh-CN')
    // 既有头不被顶掉
    expect(headers['Content-Type']).toBe('application/json')
    expect(headers.Accept).toBe('text/event-stream')
  })

  it('英文界面下带 Accept-Language: en-US（开流前错误才能本地化，语言才能进模型）', async () => {
    applyLocale('en-US')
    const fetchMock = stubFetch()

    await streamChat({ sessionId: 's-lang', message: 'hello' } as never, {})

    expect(chatHeaders(fetchMock)['Accept-Language']).toBe('en-US')
  })
})
