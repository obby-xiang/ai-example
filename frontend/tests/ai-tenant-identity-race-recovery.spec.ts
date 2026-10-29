/**
 * Task 4 返修 round 2（复评 I-1 / I-2）：
 *
 * <ol>
 * <li><b>连续快速切换身份</b>：指针恢复分支的探活是异步的（最多 3×150ms）。第一次切换的续体
 *   不得在第二次切换之后把"身份 A 的会话号"写到"身份 B"上 —— 令牌/身份/空号三道再校验；
 *   `tabOwnership='owner'` 也必须在会话号确定之后才设置。</li>
 * <li><b>发号失败 → 重试 → 恢复</b>：目录就绪但发号失败时，面板必须是"未就绪"可重试态；
 *   重试先重新加载目录（不能复用刚结束的单飞 promise 变成 no-op）再发号，成功后立即可发送。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { identityHeaders, resetAiIdentityCache, writeAiIdentity } from '@/utils/ai-identity'

const api = vi.hoisted(() => ({
  tenantUsers: vi.fn(),
  createSession: vi.fn(),
  health: vi.fn(),
  history: vi.fn(),
  getContext: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  retryModelStep: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

const DIRECTORY = [
  { tenantId: 7, users: [{ userId: 71, displayName: '甲' }] },
  { tenantId: 8, users: [{ userId: 81, displayName: '丙' }] }
]

/** 可控延迟的 Web Locks 桩：按锁名里的会话号给不同延迟，制造"后发起的切换先完成"。 */
class DelayedLockManager {
  private readonly held = new Set<string>()

  constructor(private readonly delays: Array<{ match: string; ms: number }>) {}

  request(
    name: string,
    options: { ifAvailable?: boolean },
    callback: (lock: { name: string } | null) => unknown
  ): Promise<unknown> {
    const delay = this.delays.find((entry) => name.includes(entry.match))?.ms ?? 0
    return new Promise((resolve) => {
      setTimeout(() => {
        if (this.held.has(name)) {
          resolve(options?.ifAvailable === true ? callback(null) : undefined)
          return
        }
        this.held.add(name)
        resolve(callback({ name }))
      }, delay)
    })
  }
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.tenantUsers.mockResolvedValue(DIRECTORY)
  api.history.mockResolvedValue({ sessionId: '1001', count: 0, messages: [] })
  api.getContext.mockRejectedValue(new Error('no context'))
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  resetAiIdentityCache()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('Task 4 返修 round 2：身份切换竞态 / 发号失败重试', () => {
  it('连续快速切换：先发起的身份 A 不得把会话号写到后切换到的身份 B 上', async () => {
    // 身份 A 已有会话 1001；身份 B 已有会话指针 9002（切回 B 会走指针恢复分支）。
    writeAiIdentity({ tenantId: 7, userId: 71 })
    sessionStorage.setItem('ai-session-id:7:71', '1001')
    sessionStorage.setItem('ai-session-id:8:81', '9002')

    // 时序：切到 B（探活 300ms）→ 立刻切回 A（探活 50ms）。A 先完成恢复，B 的续体后到。
    Object.defineProperty(navigator, 'locks', {
      value: new DelayedLockManager([{ match: '9002', ms: 300 }, { match: '1001', ms: 50 }]),
      configurable: true
    })
    vi.useFakeTimers()

    const store = useAiStore()
    expect(store.sessionId).toBe('1001')
    store.tabOwnership = 'owner'

    const toB = store.switchIdentity(8, 81)
    await Promise.resolve() // 让第一次切换进入 probeSessionLock 的 await
    const backToA = store.switchIdentity(7, 71)

    await vi.advanceTimersByTimeAsync(400)
    await Promise.all([toB, backToA])

    // 最终只能是"A 身份 + A 的会话号"，绝不能出现 A 会话配 B 身份。
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1001')
    expect(store.sessionIdentity).toEqual({ tenantId: 7, userId: 71 })
    expect(identityHeaders()).toEqual({ 'X-Tenant-Id': '7', 'X-User-Id': '71' })
    // B 的指针未被 A 的恢复流程改写。
    expect(sessionStorage.getItem('ai-session-id:8:81')).toBe('9002')
    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe('1001')

    // 后续请求必须用 A 的号（配 A 的头）。
    api.chat.mockImplementation((_request: unknown, handlers: any) => {
      handlers.onFrame?.({ type: 'done', runId: 'run-a', seq: 1, cancelled: false })
      return Promise.resolve({ terminal: true })
    })
    await store.send('查一下')
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: '1001' })
  })

  it('发号失败 → 可见重试（先重载目录再发号）→ 恢复后可发送', async () => {
    api.createSession
      .mockRejectedValueOnce(new Error('session endpoint down'))
      .mockRejectedValueOnce(new Error('session endpoint still down'))
      .mockResolvedValue({ sessionId: 5001 })
    api.chat.mockImplementation((_request: unknown, handlers: any) => {
      handlers.onFrame?.({ type: 'done', runId: 'run-r', seq: 1, cancelled: false })
      return Promise.resolve({ terminal: true })
    })

    const store = useAiStore()
    await store.loadTenantDirectory()

    // 目录就绪、身份已落，但发号失败 ⇒ 不可发送（面板横幅据此出现，重试按钮走同一动作）。
    expect(store.directoryStatus).toBe('ready')
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('')
    expect(store.sessionReady).toBe(false)

    const failed = await store.send('你好')
    expect(failed.outcome).toBe('not-sent')
    expect(api.chat).not.toHaveBeenCalled()
    expect(api.createSession).toHaveBeenCalledTimes(2)
    expect(api.tenantUsers).toHaveBeenCalledTimes(1)

    // 重试：先重载目录（不是复用旧 promise 的 no-op），再发号。
    const recovered = await store.retryIdentitySession()
    expect(recovered).toBe('5001')
    expect(store.sessionReady).toBe(true)
    expect(store.sessionId).toBe('5001')
    expect(api.tenantUsers).toHaveBeenCalledTimes(2)
    expect(api.createSession).toHaveBeenCalledTimes(3)

    // 恢复后发送走新号。
    const sent = await store.send('继续')
    expect(sent.outcome).toBe('ok')
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: '5001' })
  })
})
