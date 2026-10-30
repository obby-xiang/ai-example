/**
 * F-C2：`POST /api/ai/session` 的**权威值**是 `sessionIdText`（十进制字符串），
 * `sessionId`（JSON number）只是兼容回读 —— 量级 1.7e18 早已超出 `Number.MAX_SAFE_INTEGER`，
 * 数字在 JSON 文本里读成 JS number 就会丢低位（double 在该量级间隔 256）。
 *
 * <p>锁三件事：
 * <ol>
 * <li>`createSession()` 优先取 `sessionIdText`：同一响应里的 `sessionId` number 已丢精度，
 *   也不能拿它当权威值；</li>
 * <li>旧后端（只有数字字段）按**原始文本**回读，行为与改造前一致（兼容，不经过 `JSON.parse`）；</li>
 * <li>store 的 `ensureSession()` 原样落权威文本（不做 Number 往返）。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import { aiApi } from '@/api/ai'
import { useAiStore } from '@/stores/ai'
import { resetAiIdentityCache } from '@/utils/ai-identity'

/** 19 位真实量级（时间戳 ms × 10^6）；`Number(...)` 会把它变成 1731234567890123520。 */
const AUTHORITATIVE = '1731234567890123456'

let sessionBody = ''
let captured: InternalAxiosRequestConfig[] = []

function respond(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiIdentityCache()
  captured = []
  sessionBody = `{"success":true,"data":{"sessionId":${AUTHORITATIVE},"sessionIdText":"${AUTHORITATIVE}"}}`
  http.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const internal = config as InternalAxiosRequestConfig
    captured.push(internal)
    const url = config.url ?? ''
    if (url === '/ai/session') {
      return respond(internal, sessionBody)
    }
    if (url === '/ai/tenants') {
      return respond(internal, { success: true, data: [{ tenantId: 7, users: [{ userId: 71, displayName: '甲' }] }] })
    }
    return respond(internal, { success: true, data: {} })
  }
})

afterEach(() => {
  resetAiIdentityCache()
})

describe('F-C2 · 会话号权威字段 sessionIdText', () => {
  it('优先取 sessionIdText：同响应里的 JSON number 已丢精度也不采用', async () => {
    // 模拟真实后端：数字字段与文本字段同值，但数字在 JSON → JS number 时已丢低位。
    sessionBody = `{"success":true,"data":{"sessionId":${Number(AUTHORITATIVE)},"sessionIdText":"${AUTHORITATIVE}"}}`
    expect(String(JSON.parse(sessionBody).data.sessionId)).not.toBe(AUTHORITATIVE)

    await expect(aiApi.createSession()).resolves.toBe(AUTHORITATIVE)
  })

  it('旧后端只有数字字段：按原始文本回读（兼容，不经过 JSON.parse）', async () => {
    sessionBody = `{"success":true,"data":{"sessionId":${AUTHORITATIVE}}}`

    await expect(aiApi.createSession()).resolves.toBe(AUTHORITATIVE)
  })

  it('两个字段都缺失/非法 ⇒ 显式失败（不伪造会话号）', async () => {
    sessionBody = '{"success":true,"data":{}}'

    await expect(aiApi.createSession()).rejects.toThrow(/sessionId/)
  })

  it('store 原样落权威文本：ensureSession 不做 Number 往返', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()

    expect(store.sessionId).toBe(AUTHORITATIVE)
    expect(String(Number(store.sessionId))).not.toBe(AUTHORITATIVE)
    const sessionCall = captured.find((config) => config.url === '/ai/session')
    expect(sessionCall).toBeDefined()
  })
})
