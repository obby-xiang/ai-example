/**
 * R7：HITL 写入口（`/confirm` / `/frontend-tool-result`）必须**按点击时的身份**上到线上。
 *
 * <p>为什么不能用 mock `aiApi.confirm` 断言：axios 的请求拦截器在**微任务**里才执行，而
 * `switchIdentity` 同步就把身份写成了新的 —— 拦截器若在那一刻现读身份，点击后立刻切身份的
 * 旧决策会带上**新身份**头（后端按新身份找不到旧轮的挂起项 ⇒ 409，决策丢失）。
 * 与 cancel（见 `task3-identity-cancel-headers.spec.ts`）同口径：身份在动作入口捕获、显式随请求发出。
 *
 * <p>本用例走 **store → 真实 `aiApi` → 真实 `http` → adapter**，直接看请求配置上的身份头；
 * 并锁住迟到的旧身份回包（成功/409）不得改动新身份的任何现场（`stillLive` 守卫）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { http } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import { resetAiIdentityCache, sessionIdStorageKey, writeAiIdentity } from '@/utils/ai-identity'
import type { ChatMessage } from '@/types/ai'

const OLD = { tenantId: 7, userId: 71 }
const NEW = { tenantId: 8, userId: 81 }

let captured: InternalAxiosRequestConfig[] = []
/** 下一次 `/ai/confirm` 的状态码（默认 200）。 */
let confirmStatus = 200

function respond(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { data, status, statusText: 'OK', headers: {}, config }
}

function headerOf(config: InternalAxiosRequestConfig, name: string): string | null {
  const headers = config.headers as { get?: (key: string) => string | null }
  return headers.get?.(name) ?? null
}

function gateMessage(toolCallId: string, runId: string): ChatMessage {
  return {
    id: 'assistant-r7',
    role: 'assistant',
    runId,
    content: '',
    parts: [{
      kind: 'tool',
      seg: 1,
      toolCallId,
      name: 'start_export',
      kind2: 'CONFIRM',
      status: 'pending',
      requiresAction: true,
      args: '{}'
    }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: false
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiIdentityCache()
  captured = []
  confirmStatus = 200
  writeAiIdentity(OLD)
  sessionStorage.setItem(sessionIdStorageKey(OLD), '7001')
  http.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    const internal = config as InternalAxiosRequestConfig
    captured.push(internal)
    const url = config.url ?? ''
    if (url === '/ai/session') {
      return respond(internal, { success: true, data: { sessionId: 8101, sessionIdText: '8101' } })
    }
    if (url.startsWith('/ai/cancel/')) {
      return respond(internal, { cancelled: true, alreadyCancelled: false })
    }
    if (url === '/ai/confirm') {
      if (confirmStatus !== 200) {
        return respond(internal, {
          success: false,
          code: 'DUPLICATE_TOOL_CALL_ID',
          message: 'duplicate'
        }, confirmStatus)
      }
      return respond(internal, { approved: true })
    }
    if (url === '/ai/frontend-tool-result') {
      return respond(internal, { accepted: true })
    }
    return respond(internal, {})
  }
})

afterEach(() => {
  resetAiIdentityCache()
})

describe('R7 · 决策请求携带点击时的身份头', () => {
  it('/confirm 显式携带旧身份头：点击后立刻切身份也不被拦截器改写', async () => {
    const store = useAiStore()
    expect(store.sessionId).toBe('7001')
    store.activeRunId = 'run-old'
    store.suspended = true
    store.messages = [gateMessage('call-confirm', 'run-old')]

    const deciding = store.confirm(true, undefined, 'call-confirm')
    // 同步切身份：身份在 confirm 发起之后、拦截器执行之前就变成新的。
    const switching = store.switchIdentity(NEW.tenantId, NEW.userId)
    await Promise.all([deciding, switching])

    const confirmCall = captured.find((config) => config.url === '/ai/confirm')
    expect(confirmCall).toBeDefined()
    // 线上请求头 = 点击时身份（显式值），与拦截器当刻能读到的新身份无关。
    expect(headerOf(confirmCall!, 'X-Tenant-Id')).toBe('7')
    expect(headerOf(confirmCall!, 'X-User-Id')).toBe('71')

    // 对照：切身份后的发号请求仍按**当前**身份补缺省头。
    const sessionCall = captured.find((config) => config.url === '/ai/session')
    expect(headerOf(sessionCall!, 'X-Tenant-Id')).toBe('8')
    expect(headerOf(sessionCall!, 'X-User-Id')).toBe('81')
  })

  it('/frontend-tool-result 显式携带旧身份头：回灌不被拦截器改写', async () => {
    const store = useAiStore()
    expect(store.sessionId).toBe('7001')
    store.activeRunId = 'run-old'

    const submitting = store.submitFrontendToolResult('call-ft', '{"ok":true}', 'http-post', false, 'run-old')
    const switching = store.switchIdentity(NEW.tenantId, NEW.userId)
    const [result] = await Promise.all([submitting, switching])

    const resultCall = captured.find((config) => config.url === '/ai/frontend-tool-result')
    expect(resultCall).toBeDefined()
    expect(headerOf(resultCall!, 'X-Tenant-Id')).toBe('7')
    expect(headerOf(resultCall!, 'X-User-Id')).toBe('71')

    // stillLive：旧身份的回包在新身份现场一律作废（不当作已送达）。
    expect(result).toEqual({ ok: false })
  })

  it('迟到的旧身份 409 不写新会话的通知（stillLive 守卫保留）', async () => {
    confirmStatus = 409
    const store = useAiStore()
    store.activeRunId = 'run-old'
    store.suspended = true
    store.messages = [gateMessage('call-confirm', 'run-old')]

    const deciding = store.confirm(true, undefined, 'call-confirm')
    const switching = store.switchIdentity(NEW.tenantId, NEW.userId)
    await Promise.all([deciding, switching])

    expect(store.identity).toEqual(NEW)
    expect(store.sessionId).toBe('8101')
    expect(store.noticeCode).toBeNull()
    expect(store.messages).toEqual([])
    // 旧卡的同步态/挂起态不得落到新身份。
    expect(store.suspended).toBe(false)
  })
})
