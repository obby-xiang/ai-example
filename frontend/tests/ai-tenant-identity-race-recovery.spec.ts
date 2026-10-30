/**
 * Task 4 返修 round 2/3（复评 I-1 / I-2 / round3 Important+Minor）：
 *
 * <ol>
 * <li><b>连续快速切换身份</b>：每次实际切换都向后端发号；先发起的回包不得在第二次切换之后
 *   把新号写到后切换到的身份上 —— 令牌/身份校验；`tabOwnership='owner'` 也必须在新号确定之后才设置。</li>
 * <li><b>发号失败 → 重试 → 恢复</b>：失败才显示错误态（发号窗口不闪），重试只在目录不可用时
 *   重载目录，成功后清失败态与失败通知，立即可发送。</li>
 * <li><b>指针恢复的绑定与目录错误本地化</b>：目录首次落身份（非用户切换）按存量指针恢复会话时
 *   补做所有权 channel/lifecycle 绑定；非 ApiError 的目录失败不得把技术原文上屏。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { OWNERSHIP_CHANNEL_NAME, heldSessionLockName, useAiStore } from '@/stores/ai'
import { identityHeaders, resetAiIdentityCache, sessionLockName, writeAiIdentity } from '@/utils/ai-identity'
import { translate } from '@/composables/useCopy'
import { channels, FakeBroadcastChannel, FakeLockManager } from './tab-ownership-fixtures'

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

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  channels.clear()
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
  it('连续快速切换：先发起的切换不得把新号写到后切换到的身份上', async () => {
    // 身份 A 已有会话 1001；切换语义是每次都由后端发新号，所以这里用两个可控的发号回包
    // 模拟"后发起的切换先完成、先发起的回包后到"。
    writeAiIdentity({ tenantId: 7, userId: 71 })
    sessionStorage.setItem('ai-session-id:7:71', '1001')

    let resolveB: (value: unknown) => void = () => undefined
    let resolveA: (value: unknown) => void = () => undefined
    api.createSession
      .mockImplementationOnce(() => new Promise((resolve) => { resolveB = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveA = resolve }))

    const store = useAiStore()
    expect(store.sessionId).toBe('1001')
    store.tabOwnership = 'owner'

    const toB = store.switchIdentity(8, 81)
    await Promise.resolve() // 让第一次切换进入发号 await
    const backToA = store.switchIdentity(7, 71)
    await Promise.resolve()

    // 后发起的 A 先拿到号；先发起的 B 回包后到，必须被令牌/身份校验丢弃。
    resolveA({ sessionId: '1003' })
    resolveB({ sessionId: '9002' })
    await Promise.all([toB, backToA])

    // 最终只能是"A 身份 + A 的新会话号"，绝不能出现 A 会话配 B 身份。
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1003')
    expect(store.sessionIdentity).toEqual({ tenantId: 7, userId: 71 })
    expect(identityHeaders()).toEqual({ 'X-Tenant-Id': '7', 'X-User-Id': '71' })
    // B 的切换已被后一次切换作废：它的号不得落到任何身份指针上。
    expect(sessionStorage.getItem('ai-session-id:8:81')).toBeNull()
    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe('1003')
    expect(sessionStorage.getItem('ai-mirror:8:81:9002')).toBeNull()

    // 后续请求必须用 A 的新号（配 A 的头）。
    api.chat.mockImplementation((_request: unknown, handlers: any) => {
      handlers.onFrame?.({ type: 'done', runId: 'run-a', seq: 1, cancelled: false })
      return Promise.resolve({ terminal: true })
    })
    await store.send('查一下')
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: '1003' })
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
    // 失败态显式置位（横幅只认它；发号窗口不会因 sessionReady=false 闪红）。
    expect(store.sessionIssueFailed).toBe(true)

    const failed = await store.send('你好')
    expect(failed.outcome).toBe('not-sent')
    expect(api.chat).not.toHaveBeenCalled()
    expect(api.createSession).toHaveBeenCalledTimes(2)
    expect(api.tenantUsers).toHaveBeenCalledTimes(1)

    // 重试：目录已就绪 ⇒ **不再重载目录**，直接重新发号。
    const recovered = await store.retryIdentitySession()
    expect(recovered).toBe('5001')
    expect(store.sessionReady).toBe(true)
    expect(store.sessionId).toBe('5001')
    expect(api.tenantUsers).toHaveBeenCalledTimes(1)
    expect(api.createSession).toHaveBeenCalledTimes(3)
    // 失败态与失败通知一起清除（横幅消失、红字不残留）。
    expect(store.sessionIssueFailed).toBe(false)
    expect(store.noticeCode).not.toBe('ai.notice.sessionIssueFailed')

    // 恢复后发送走新号。
    const sent = await store.send('继续')
    expect(sent.outcome).toBe('ok')
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: '5001' })
  })

  it('启动期 unknown 角色的指针恢复补做所有权绑定：channel 打开、锁账本指向恢复会话', async () => {
    // 非用户切换的启动补入：没有身份、只有存量指针 ⇒ 目录首次落身份时按指针恢复（不发新号）。
    sessionStorage.setItem('ai-session-id:7:71', '1001')
    const locks = new FakeLockManager()
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)

    const store = useAiStore()
    expect(store.sessionId).toBe('')
    expect(store.tabOwnership).toBe('unknown')

    await store.loadTenantDirectory()

    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1001')
    expect(api.createSession).not.toHaveBeenCalled()
    expect(store.tabOwnership).toBe('owner')
    // 指针恢复分支在 unknown 时补判所有权 ⇒ BroadcastChannel 已打开（channel/lifecycle 绑定）。
    expect([...channels].some((channel) => channel.name === OWNERSHIP_CHANNEL_NAME)).toBe(true)
    expect(heldSessionLockName()).toBe(sessionLockName('1001'))
  })

  it('目录失败详情使用本地化兜底（非 ApiError 的原文不上屏）', async () => {
    api.tenantUsers.mockRejectedValueOnce(new Error('boom raw stack text'))
    const store = useAiStore()

    await store.loadTenantDirectory()

    expect(store.directoryStatus).toBe('unavailable')
    expect(store.directoryError).toBe(translate('ai.notice.directoryFailed'))
    expect(store.directoryError).not.toContain('boom')
  })
})
