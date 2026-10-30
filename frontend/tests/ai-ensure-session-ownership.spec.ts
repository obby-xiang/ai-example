/**
 * 2026-10-30 收口（3d801ff1）：`ensureSession` 的归属判定与统一入口回归锁。
 *
 * <p>三个单点，各锁一条生产口径：
 * <ol>
 * <li>已有 sessionId 也**先等** `ensureTabOwnership()`：复制页签在探活落定前拿不到"原页签的号"；</li>
 * <li>duplicate 换号失败**不误清**失败态：`sessionIssueFailed` 与发号失败通知必须留着（横幅可重试）；</li>
 * <li>`send()` 在已有 id 时也经过 `ensureSession()`（不再直接读 `this.sessionId`），
 * 因此同样受所有权判定门控。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { resetAiIdentityCache, sessionIdStorageKey } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import { channels, FakeBroadcastChannel, FakeLockManager } from './tab-ownership-fixtures'

const api = vi.hoisted(() => ({
  tenantUsers: vi.fn(),
  createSession: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  getContext: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

/**
 * 探活门控桩：`request` 先挂起，直到用例显式 `grant()` 才回 `callback({ name })`。
 * 用来证明调用方**真的在等**所有权判定，而不是探活还没跑完就先返回。
 */
class GatedLockManager {
  private readonly grants: Array<() => void> = []

  get pending(): number {
    return this.grants.length
  }

  request(
    name: string,
    _options: { ifAvailable?: boolean },
    callback: (lock: { name: string } | null) => unknown
  ): Promise<unknown> {
    return new Promise((resolve) => {
      this.grants.push(() => {
        // 回调返回的"常挂 promise"= 持锁到文档销毁（与真实 LockManager 同语义）。
        void Promise.resolve(callback({ name })).then(
          () => resolve(undefined),
          () => resolve(undefined)
        )
      })
    })
  }

  grant(): void {
    const next = this.grants.shift()
    if (!next) {
      throw new Error('没有待放行的探活请求')
    }
    next()
  }
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  channels.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
  Object.defineProperty(navigator, 'locks', { value: new FakeLockManager(), configurable: true })
  api.runsCurrent.mockResolvedValue({
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  })
  api.history.mockResolvedValue({ sessionId: '', count: 0, messages: [] })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
})

afterEach(() => {
  resetAiIdentityCache()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('ensureSession 已有 id 的归属判定（3d801ff1）', () => {
  it('已有 sessionId 时先等所有权判定，拿到锁后才返回原号', async () => {
    installIdentityFixture({ sessionId: '9101' })
    const gated = new GatedLockManager()
    Object.defineProperty(navigator, 'locks', { value: gated, configurable: true })

    const store = useAiStore()
    expect(store.sessionId).toBe('9101')

    let settled = false
    const pending = store.ensureSession().then((value) => {
      settled = true
      return value
    })
    await flushPromises()

    // 修复前这里会立刻拿到 '9101'；修复后必须停在探活上。
    expect(gated.pending).toBe(1)
    expect(settled).toBe(false)
    expect(api.createSession).not.toHaveBeenCalled()

    gated.grant()
    await expect(pending).resolves.toBe('9101')
    expect(settled).toBe(true)
    expect(store.tabOwnership).toBe('owner')
    // 原页签拿锁 = 恢复原会话，不再发新号。
    expect(api.createSession).not.toHaveBeenCalled()
  })

  it('duplicate 换号失败：保留 sessionIssueFailed 与发号失败通知（不被外层误清）', async () => {
    const fixture = installIdentityFixture({ sessionId: '9201' })
    const locks = new FakeLockManager()
    locks.hold(fixture.lockName('9201'))
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    api.createSession.mockRejectedValue(new Error('session issue boom'))

    const store = useAiStore()
    expect(store.sessionId).toBe('9201')

    const returned = await store.ensureSession()

    // 复制品已换成本页签的新会话（旧号被清），但发号失败 ⇒ 保持未就绪。
    expect(returned).toBe('')
    expect(store.sessionId).toBe('')
    expect(store.tabOwnership).toBe('duplicate')
    expect(api.createSession).toHaveBeenCalledTimes(1)
    // 失败态与通知必须保留：否则横幅与「重试」入口会消失。
    expect(store.sessionIssueFailed).toBe(true)
    expect(store.noticeCode).toBe('ai.notice.sessionIssueFailed')
    expect(store.notice).toBeTruthy()
    // 失败时不写任何会话指针（刷新不得复活旧号）。
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBeNull()
  })

  it('send() 在已有 sessionId 时也经过 ensureSession：先等所有权，再带原号发请求', async () => {
    installIdentityFixture({ sessionId: '9301' })
    const gated = new GatedLockManager()
    Object.defineProperty(navigator, 'locks', { value: gated, configurable: true })
    api.chat.mockResolvedValue(undefined)

    const store = useAiStore()
    expect(store.sessionId).toBe('9301')

    const sending = store.send('你好')
    await flushPromises()

    // 修复前 send 直接读 this.sessionId，这里会已经发出 /chat。
    expect(gated.pending).toBe(1)
    expect(api.chat).not.toHaveBeenCalled()
    expect(store.messages).toHaveLength(0)

    gated.grant()
    const result = await sending

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: '9301', message: '你好' })
    expect(result.outcome).toBe('ok')
  })
})
