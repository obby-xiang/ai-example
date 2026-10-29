/**
 * 页签所有权生命周期（RC-4 收口）：**刷新/bfcache 恢复不得把原页签的会话让给空白副本**。
 *
 * <p>本批只动 store 的所有权账本，不改 UI：
 * <ul>
 * <li>② `released` 只是**让渡意向** —— 空白副本要等宽限并在锁真正空闲后才接手；原页签在宽限内
 *   重新认领（广播 `claim`）则副本撤销接手。这是**顺序辅助**，不是"某毫秒能证明所有浏览器"；</li>
 * <li>③ `adoptFreshSession()` 换 id 前只释放**本页签文档持有、且属于旧会话**的锁（别人的锁不碰）；</li>
 * <li>④ `pageshow`/`event.persisted` 重新探活：锁仍归我 ⇒ 重新广播 claim；锁已归别人 ⇒ 角色降级，
 *   **不清会话、不清屏**（bfcache 恢复时清屏是灾难），也不双自认 owner。</li>
 * </ul>
 * 桩与 `r166b-fixes.spec.ts` 同源（见 tests/tab-ownership-fixtures.ts）。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import {
  LOCK_PROBE_ATTEMPTS,
  LOCK_PROBE_RETRY_MS,
  OWNERSHIP_CHANNEL_NAME,
  OWNERSHIP_RELEASE_GRACE_MS,
  PAGE_ID_KEY,
  RETURN_INTENT_KEY,
  useAiStore
} from '@/stores/ai'
import {
  currentAiIdentity,
  identityFingerprint,
  mirrorStorageKey,
  sessionIdStorageKey,
  sessionLockName
} from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import {
  channels,
  FakeBroadcastChannel,
  FakeLockManager,
  pageEvent,
  seedTab,
  wait
} from './tab-ownership-fixtures'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

let locks: FakeLockManager

/** 广播一条所有权消息（模拟另一个页签的广播）。 */
function broadcast(message: Record<string, unknown>): void {
  new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage(message)
}

/** 探活重试的最坏耗时 + 余量（`resolveTabOwnership` 是 3 × 150ms）。 */
const PROBE_BUDGET_MS = LOCK_PROBE_ATTEMPTS * LOCK_PROBE_RETRY_MS + 400
/** 宽限到期 + 探活/微任务余量。 */
const GRACE_BUDGET_MS = OWNERSHIP_RELEASE_GRACE_MS + PROBE_BUDGET_MS

beforeEach(() => {
  sessionStorage.clear()
  channels.clear()
  setActivePinia(createPinia())
  locks = new FakeLockManager()
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)

  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  installIdentityFixture()
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('② 让渡宽限：刷新 / 慢重载不把原会话让给空白副本', () => {
  it('released 后原页签在宽限内重新认领（claim）⇒ 空白副本不接手', async () => {
    seedTab('9201')
    locks.hold(sessionLockName('9201'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    // Task 4：复制页签拿到自己的新号即记 owner（不再是"待接手"的空白副本）
    expect(store.tabOwnership).toBe('owner')

    // 原页签刷新：pagehide 只广播「让渡意向」，锁随文档销毁由浏览器裁决（此处仍在）
    broadcast({ kind: 'released', sessionId: '9201', pageId: 'page-orig' })
    await wait(OWNERSHIP_RELEASE_GRACE_MS / 3)
    expect(store.sessionId).toBe(ownSession)

    // 原页签的新文档在宽限内拿到同一把锁并重新认领
    broadcast({ kind: 'claim', sessionId: '9201', pageId: 'page-orig' })
    await wait(GRACE_BUDGET_MS)

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('owner')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(ownSession)
    expect(api.history).not.toHaveBeenCalledWith('9201')
  })

  it('真关闭（锁随文档释放）⇒ 复制页签也不接手原会话（Task 4：已持有自己的会话）', async () => {
    seedTab('9202')
    locks.hold(sessionLockName('9202'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    expect(ownSession).not.toBe('9202')

    broadcast({ kind: 'released', sessionId: '9202', pageId: 'page-orig' })
    // 宽限内不接手（旧实现会立刻抢）
    await wait(OWNERSHIP_RELEASE_GRACE_MS / 3)
    expect(store.sessionId).toBe(ownSession)
    expect(api.history).not.toHaveBeenCalledWith('9202')

    // 文档销毁 ⇒ 别人的锁释放；宽限到期后本页签**不**改认原会话（发号成功即 owner，接手路径不可达）
    locks.release(sessionLockName('9202'))
    await wait(GRACE_BUDGET_MS)

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('owner')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(ownSession)
    expect(api.history).not.toHaveBeenCalledWith('9202')
    expect(locks.isHeld(sessionLockName('9202'))).toBe(false)
  })

  it('让渡广播不改变本页签会话：自己的会话与锁原样保留，也不替原会话认领', async () => {
    seedTab('9203')
    locks.hold(sessionLockName('9203'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const blankSession = store.sessionId
    expect(locks.isHeld(sessionLockName(blankSession))).toBe(true)

    locks.release(sessionLockName('9203'))
    broadcast({ kind: 'released', sessionId: '9203', pageId: 'page-orig' })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe(blankSession)
    // 自己的会话锁仍在手（账本不泄漏：换会话时旧锁已显式释放，这里没有多占）
    expect(locks.isHeld(sessionLockName(blankSession))).toBe(true)
    // 原会话的锁没有被本页签冒领
    expect(locks.isHeld(sessionLockName('9203'))).toBe(false)
  })

  it('慢重载（宽限已过、会话已被副本接手）⇒ 回来的原页签不清屏/不换 id，只降级', async () => {
    // 原页签 pagehide 写下的「我会回来」意向（本页签 pageId + 新近时刻）
    sessionStorage.setItem(PAGE_ID_KEY, 'page-slow')
    seedTab('9204')
    sessionStorage.setItem(
      RETURN_INTENT_KEY,
      JSON.stringify({
        sessionId: '9204',
        pageId: 'page-slow',
        at: Date.now(),
        fingerprint: identityFingerprint(currentAiIdentity()!)
      })
    )
    // 副本已接手：锁在别人手里
    locks.hold(sessionLockName('9204'))

    const store = useAiStore()
    expect(store.sessionId).toBe('9204')
    store.recordUserMessage('我正在看的这段历史')
    const before = store.messages.map((message) => message.id)

    await store.ensureTabOwnership()

    // 角色降级（对方确实持锁），但**不清会话、不清屏、不动镜像**
    expect(store.tabOwnership).toBe('duplicate')
    expect(store.sessionId).toBe('9204')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe('9204')
    expect(store.messages.map((message) => message.id)).toEqual(before)
    expect(sessionStorage.getItem(mirrorStorageKey('9204'))).not.toBeNull()
    // 一次性意向已消费：下次刷新不再走这条降级路
    expect(sessionStorage.getItem(RETURN_INTENT_KEY)).toBeNull()
    // 不删别人的会话
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })

  it('复制页签（没有「我会回来」意向）⇒ 仍按既有一律换新会话 + 清继承镜像', async () => {
    seedTab('9205')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-copy')
    locks.hold(sessionLockName('9205'))

    const store = useAiStore()
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).not.toBe('9205')
    expect(sessionStorage.getItem(mirrorStorageKey('9205'))).toBeNull()
    expect(sessionStorage.getItem(PAGE_ID_KEY)).not.toBe('page-copy')
  })
})

describe('③ adoptFreshSession 换 id：只释放本页签文档持有的旧锁', () => {
  it('换 id 前释放旧会话的锁（不泄漏到文档销毁），新会话照旧认领', async () => {
    seedTab('9206')
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')
    expect(locks.isHeld(sessionLockName('9206'))).toBe(true)

    // 别人的锁（不归本页签文档持有）
    locks.hold(sessionLockName('9207'))

    const nextId = store.adoptFreshSession()
    expect(nextId).not.toBe('9206')
    // release 是异步的（真实 Web Locks 也一样）⇒ 等一拍再看锁的归属
    await flushPromises()
    // 旧锁已释放：别的页签现在可以接手旧会话（这正是"不泄漏"的可观测面）
    expect(locks.isHeld(sessionLockName('9206'))).toBe(false)
    // 别人的锁一个都没碰
    expect(locks.isHeld(sessionLockName('9207'))).toBe(true)

    await store.claimSessionOwnership(nextId)
    await flushPromises()
    expect(locks.isHeld(sessionLockName(nextId))).toBe(true)
  })

  it('unsupported（无 Web Locks）⇒ 换 id 照旧可用，不抛错', () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    const store = useAiStore()
    const before = store.sessionId

    const nextId = store.adoptFreshSession()

    expect(nextId).not.toBe(before)
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(nextId)
  })
})

describe('④ pageshow / event.persisted：bfcache 恢复后的有界重认领与降级', () => {
  it('pagehide.persisted=true（进 bfcache）⇒ 不广播 released、不释放锁', async () => {
    seedTab('9208')
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')

    // 装监听在 claim 之后：此后只应看到我们自己重新认领的广播
    const seen: Array<Record<string, unknown>> = []
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    witness.onmessage = (event) => seen.push(event.data as Record<string, unknown>)

    window.dispatchEvent(pageEvent('pagehide', true))
    await flushPromises()

    expect(seen.filter((message) => message.kind === 'released')).toHaveLength(0)
    expect(locks.isHeld(sessionLockName('9208'))).toBe(true)
  })

  it('pageshow（bfcache 恢复）锁仍归我 ⇒ 有界重认领并重新广播 claim', async () => {
    seedTab('9209')
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')

    const seen: Array<Record<string, unknown>> = []
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    witness.onmessage = (event) => seen.push(event.data as Record<string, unknown>)

    window.dispatchEvent(pageEvent('pagehide', true))
    window.dispatchEvent(pageEvent('pageshow', true))
    await wait(PROBE_BUDGET_MS)

    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).toBe('9209')
    expect(seen.filter((message) => message.kind === 'claim')).toMatchObject([
      { kind: 'claim', sessionId: '9209', pageId: sessionStorage.getItem(PAGE_ID_KEY), tenantId: 7, userId: 71 }
    ])
  })

  it('pageshow 后锁已归别人 ⇒ 降级为 duplicate，不清会话/不清屏，也不再自认 owner', async () => {
    seedTab('9210')
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')
    store.recordUserMessage('冻结前的这段历史')
    expect(store.messages).toHaveLength(1)

    const seen: Array<Record<string, unknown>> = []
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    witness.onmessage = (event) => seen.push(event.data as Record<string, unknown>)

    // 冻结期间浏览器丢了本页签的锁，另一个页签拿了同一会话
    window.dispatchEvent(pageEvent('pagehide', true))
    locks.dropMine(sessionLockName('9210'))
    locks.hold(sessionLockName('9210'))
    window.dispatchEvent(pageEvent('pageshow', true))
    await wait(PROBE_BUDGET_MS)

    expect(store.tabOwnership).toBe('duplicate')
    // 不清会话：id、消息、镜像都还在（bfcache 恢复不是"新建对话"）
    expect(store.sessionId).toBe('9210')
    expect(store.messages).toHaveLength(1)
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe('9210')
    expect(sessionStorage.getItem(mirrorStorageKey('9210'))).not.toBeNull()
    // 不双自认 owner：降级后不再广播 claim
    expect(seen.filter((message) => message.kind === 'claim')).toHaveLength(0)
    // 也不删别人的会话
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })
})
