/**
 * Task 3 返修 C-1：切身份时旧轮的 best-effort cancel 必须**按旧身份**上到线上。
 *
 * <p>为什么不能用 mock `aiApi.cancel` 断言：axios 的请求拦截器在**微任务**里才执行，而
 * `switchIdentity` 同步就把身份写成了新的 —— 拦截器若在那一刻现读身份，cancel 会带上**新身份**头
 * （后端按新身份拒绝/找不到旧轮，旧轮继续跑）。本用例走 **store → 真实 `aiApi` → 真实 `http`
 * → adapter**，直接看请求配置上的身份头。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import {
  mirrorStorageKey,
  resetAiIdentityCache,
  sessionIdStorageKey,
  writeAiIdentity
} from '@/utils/ai-identity'

const OLD = { tenantId: 7, userId: 71 }
const NEW = { tenantId: 8, userId: 81 }

let captured: InternalAxiosRequestConfig[] = []

function respond(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}

function headerOf(config: InternalAxiosRequestConfig, name: string): string | null {
  const headers = config.headers as { get?: (key: string) => string | null }
  return headers.get?.(name) ?? null
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiIdentityCache()
  captured = []
  writeAiIdentity(OLD)
  sessionStorage.setItem(sessionIdStorageKey(OLD), '7001')
  http.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const internal = config as InternalAxiosRequestConfig
    captured.push(internal)
    const url = config.url ?? ''
    if (url === '/ai/session') {
      return respond(internal, { sessionId: 8101 })
    }
    if (url.startsWith('/ai/cancel/')) {
      return respond(internal, { cancelled: true, alreadyCancelled: false })
    }
    return respond(internal, {})
  }
})

afterEach(() => {
  resetAiIdentityCache()
})

describe('Task 3 返修 · 切换身份的 cancel 身份头', () => {
  it('cancel 显式携带旧身份头：拦截器不得用切换后的新身份覆盖', async () => {
    const store = useAiStore()
    expect(store.sessionId).toBe('7001')
    store.activeRunId = 'run-old'
    store.loading = true

    await store.switchIdentity(NEW.tenantId, NEW.userId)

    expect(store.identity).toEqual(NEW)
    expect(store.sessionId).toBe('8101')
    expect(store.messages).toEqual([])

    const cancelCall = captured.find((config) => (config.url ?? '').startsWith('/ai/cancel/'))
    expect(cancelCall?.url).toBe('/ai/cancel/run-old')
    // 线上请求头 = 旧身份（显式值），与拦截器当刻能读到的新身份无关。
    expect(headerOf(cancelCall!, 'X-Tenant-Id')).toBe('7')
    expect(headerOf(cancelCall!, 'X-User-Id')).toBe('71')

    // 对照：新会话发号请求仍按**当前**身份补缺省头（拦截器只补缺省，不覆盖显式）。
    const sessionCall = captured.find((config) => config.url === '/ai/session')
    expect(headerOf(sessionCall!, 'X-Tenant-Id')).toBe('8')
    expect(headerOf(sessionCall!, 'X-User-Id')).toBe('81')

    // 旧会话镜像按旧身份键清理（实际切换规格，见 Task 3 §2）。
    expect(sessionStorage.getItem(mirrorStorageKey('7001', OLD))).toBeNull()
  })
})
