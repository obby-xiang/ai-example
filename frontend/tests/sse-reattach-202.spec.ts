/**
 * Task 4 返修 C3：reattach（`GET /api/ai/events/{runId}`）的 **202 + application/json「准备中」**
 * 不得被当成"连接已建立"。
 *
 * <p>后端在"已 start、快照未建"窗口回 202 JSON；旧 EventSource 必然 `onerror`，于是
 * `connectionEstablished === false` ⇒ 走 `?probe=1` ⇒ `onPreparing`。fetch 版必须保持同一条链：
 * - 不伪 `onOpen`（更不能把 JSON 当 SSE 解析、清掉"正在重连"提示）；
 * - 由既有 `?probe=1` 探测认出"准备中"，回调 `onPreparing` 并收尾；
 * - 身份头随两条请求一起发出。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reattach } from '@/api/sse'
import { resetAiIdentityCache, writeAiIdentity } from '@/utils/ai-identity'

const PREPARING = {
  preparing: true,
  runId: 'run-202',
  elapsedMs: 1234,
  retryAfterMs: 800,
  message: 'snapshot not built yet'
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'content-type': 'application/json' }),
    body: null,
    json: async () => body
  } as unknown as Response
}

beforeEach(() => {
  sessionStorage.clear()
  resetAiIdentityCache()
  writeAiIdentity({ tenantId: 7, userId: 71 })
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetAiIdentityCache()
})

describe('Task 4 返修 C3：reattach 的 202 准备中契约', () => {
  it('202 application/json 不触发 onOpen：按 ?probe=1 识别准备中并回调 onPreparing', async () => {
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url.includes('probe=1')) {
        return jsonResponse(202, PREPARING)
      }
      return jsonResponse(202, PREPARING)
    })
    vi.stubGlobal('fetch', fetchMock)

    const onOpen = vi.fn()
    const onPreparing = vi.fn()
    const onClose = vi.fn()
    const onFrame = vi.fn()

    const info = await reattach('run-202', { onOpen, onPreparing, onClose, onFrame })

    // 没有伪造连接建立，也没有把 JSON 当帧消费。
    expect(onOpen).not.toHaveBeenCalled()
    expect(onFrame).not.toHaveBeenCalled()
    // 既有 ?probe=1 契约仍生效。
    expect(onPreparing).toHaveBeenCalledTimes(1)
    expect(onPreparing).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-202',
      elapsedMs: 1234,
      retryAfterMs: 800
    }))
    expect(info.terminal).toBe(false)
    expect(onClose).toHaveBeenCalledWith(expect.objectContaining({
      terminal: false,
      reason: expect.stringContaining('准备中')
    }))

    // 事件流请求 + 探测请求各一次（探测命中 202 后不再自动轮询）。
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const eventsCall = fetchMock.mock.calls.find((call) => !String(call[0]).includes('probe=1'))
    const probeCall = fetchMock.mock.calls.find((call) => String(call[0]).includes('probe=1'))
    expect(eventsCall?.[1]?.headers).toMatchObject({ 'X-Tenant-Id': '7', 'X-User-Id': '71' })
    expect(probeCall?.[1]?.headers).toMatchObject({ 'X-Tenant-Id': '7', 'X-User-Id': '71' })
  })
})
