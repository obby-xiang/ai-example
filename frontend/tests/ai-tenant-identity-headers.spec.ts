/**
 * Task 4 返修 C2/I3/I5：身份头边界与后端长号发号的**精确解析**。
 *
 * <p>锁三件事：
 * <ol>
 * <li>AI 对话域请求统一带 `X-Tenant-Id` / `X-User-Id`；模型配置/目录（排除表）不带；</li>
 * <li>目录路径与后端一致（`/api/ai/tenants`），没有多余 404 回落；</li>
 * <li>`POST /api/ai/session` 的 19 位 long（> 2^53）按**原始文本**取号，不经过 `JSON.parse`
 *   —— 先 parse 会静默把低位抹成 0，前端拿到的"号"就与后端不一致。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import { aiApi } from '@/api/ai'
import { resetAiIdentityCache, writeAiIdentity } from '@/utils/ai-identity'

/** 超出 JS 安全整数的真实量级（时间戳 ms × 10^6）。 */
const LONG_SESSION_ID = '1731234567890123456'
const LONG_SESSION_TEXT = `{"success":true,"data":{"sessionId":${LONG_SESSION_ID}}}`

let captured: InternalAxiosRequestConfig[] = []

function respond(
  config: InternalAxiosRequestConfig,
  data: unknown,
  status = 200
): AxiosResponse {
  return { data, status, statusText: 'OK', headers: {}, config }
}

beforeEach(() => {
  sessionStorage.clear()
  resetAiIdentityCache()
  captured = []
  http.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const internal = config as InternalAxiosRequestConfig
    captured.push(internal)
    const url = config.url ?? ''
    if (url === '/ai/session') {
      return respond(internal, LONG_SESSION_TEXT)
    }
    if (url.startsWith('/ai/history/')) {
      return respond(internal, { sessionId: '1001', count: 0, messages: [] })
    }
    if (url === '/ai/tenants') {
      return respond(internal, { success: true, data: [] })
    }
    return respond(internal, { success: true, data: {} })
  }
})

afterEach(() => {
  resetAiIdentityCache()
  vi.restoreAllMocks()
})

describe('Task 4 返修 C2/I3：身份头与长号发号', () => {
  it('AI 对话请求带身份头；模型配置/健康/目录不带', async () => {
    writeAiIdentity({ tenantId: 7, userId: 71 })

    await aiApi.history('1001')
    await aiApi.runsCurrent('1001')
    await aiApi.feedbackList()
    await aiApi.health()
    await aiApi.tenantUsers()

    const byUrl = (suffix: string): InternalAxiosRequestConfig => {
      const found = captured.find((config) => (config.url ?? '').startsWith(suffix))
      if (!found) {
        throw new Error(`未捕获请求：${suffix}`)
      }
      return found
    }
    const tenantHeader = (config: InternalAxiosRequestConfig): string | null =>
      (config.headers as { get?: (name: string) => string | null }).get?.('X-Tenant-Id') ?? null
    const userHeader = (config: InternalAxiosRequestConfig): string | null =>
      (config.headers as { get?: (name: string) => string | null }).get?.('X-User-Id') ?? null

    for (const url of ['/ai/history/', '/ai/runs/current', '/ai/feedback']) {
      const config = byUrl(url)
      expect(tenantHeader(config)).toBe('7')
      expect(userHeader(config)).toBe('71')
    }
    // 排除表：健康探针与身份目录本身不属于对话域。
    for (const url of ['/ai/health', '/ai/tenants']) {
      const config = byUrl(url)
      expect(tenantHeader(config)).toBeNull()
      expect(userHeader(config)).toBeNull()
    }
    // 目录路径与后端一致，没有 404 回落（只打一次 /ai/tenants）。
    expect(captured.filter((config) => (config.url ?? '').includes('tenant')).map((config) => config.url))
      .toEqual(['/ai/tenants'])
  })

  it('19 位 long 长号按原始文本精确解析（不经过 JSON.parse）', async () => {
    writeAiIdentity({ tenantId: 7, userId: 71 })
    const sessionId = await aiApi.createSession()

    expect(sessionId).toBe(LONG_SESSION_ID)
    // 证明这不是"解析成 number 再转回来"的结果：double 表示会丢低位。
    expect(String(Number(LONG_SESSION_ID))).not.toBe(LONG_SESSION_ID)

    const call = captured.find((config) => config.url === '/ai/session')
    expect(call).toBeDefined()
    const headers = call?.headers as { get?: (name: string) => string | null }
    expect(headers.get?.('X-Tenant-Id')).toBe('7')
    expect(headers.get?.('X-User-Id')).toBe('71')
  })
})
