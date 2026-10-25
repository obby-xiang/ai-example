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
  MIRROR_PREFIX,
  OWNERSHIP_CHANNEL_NAME,
  OWNERSHIP_RELEASE_GRACE_MS,
  PAGE_ID_KEY,
  RETURN_INTENT_KEY,
  SESSION_ID_KEY,
  SESSION_LOCK_PREFIX,
  useAiStore
} from '@/stores/ai'
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
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('② 让渡宽限：刷新 / 慢重载不把原会话让给空白副本', () => {
  it('released 后原页签在宽限内重新认领（claim）⇒ 空白副本不接手', async () => {
    seedTab('tab-orig')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-orig')

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    expect(store.tabOwnership).toBe('duplicate')

    // 原页签刷新：pagehide 只广播「让渡意向」，锁随文档销毁由浏览器裁决（此处仍在）
    broadcast({ kind: 'released', sessionId: 'tab-orig', pageId: 'page-orig' })
    await wait(OWNERSHIP_RELEASE_GRACE_MS / 3)
    expect(store.sessionId).toBe(ownSession)

    // 原页签的新文档在宽限内拿到同一把锁并重新认领
    broadcast({ kind: 'claim', sessionId: 'tab-orig', pageId: 'page-orig' })
    await wait(GRACE_BUDGET_MS)

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('duplicate')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe(ownSession)
    expect(api.history).not.toHaveBeenCalledWith('tab-orig')
  })

  it('真关闭（锁随文档释放）⇒ 宽限到期后空白副本仍接手原会话（保留现有意图）', async () => {
    seedTab('tab-close')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-close')

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId

    broadcast({ kind: 'released', sessionId: 'tab-close', pageId: 'page-orig' })
    // 宽限内不接手（旧实现会立刻抢）
    await wait(OWNERSHIP_RELEASE_GRACE_MS / 3)
    expect(store.sessionId).toBe(ownSession)
    expect(api.history).not.toHaveBeenCalledWith('tab-close')

    // 文档销毁 ⇒ 别人的锁释放；宽限到期后副本二次探活拿到锁
    locks.release(SESSION_LOCK_PREFIX + 'tab-close')
    await wait(GRACE_BUDGET_MS)

    expect(store.sessionId).toBe('tab-close')
    expect(store.tabOwnership).toBe('owner')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe('tab-close')
    expect(api.history).toHaveBeenCalledWith('tab-close')
  })

  it('接手让渡 ⇒ 本页签旧空白会话的锁释放、原会话持锁（账本不泄漏）', async () => {
    seedTab('tab-adopt')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-adopt')

    const store = useAiStore()
    await store.ensureTabOwnership()
    const blankSession = store.sessionId
    expect(locks.isHeld(SESSION_LOCK_PREFIX + blankSession)).toBe(true)

    locks.release(SESSION_LOCK_PREFIX + 'tab-adopt')
    broadcast({ kind: 'released', sessionId: 'tab-adopt', pageId: 'page-orig' })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe('tab-adopt')
    // 新会话持锁
    expect(locks.isHeld(SESSION_LOCK_PREFIX + 'tab-adopt')).toBe(true)
    // 放弃的空白会话不再占锁（旧实现把释放句柄丢在探活覆盖里，锁一直挂到文档销毁）
    expect(locks.isHeld(SESSION_LOCK_PREFIX + blankSession)).toBe(false)
  })

  it('慢重载（宽限已过、会话已被副本接手）⇒ 回来的原页签不清屏/不换 id，只降级', async () => {
    // 原页签 pagehide 写下的「我会回来」意向（本页签 pageId + 新近时刻）
    sessionStorage.setItem(PAGE_ID_KEY, 'page-slow')
    seedTab('tab-slow')
    sessionStorage.setItem(
      RETURN_INTENT_KEY,
      JSON.stringify({ sessionId: 'tab-slow', pageId: 'page-slow', at: Date.now() })
    )
    // 副本已接手：锁在别人手里
    locks.hold(SESSION_LOCK_PREFIX + 'tab-slow')

    const store = useAiStore()
    expect(store.sessionId).toBe('tab-slow')
    store.recordUserMessage('我正在看的这段历史')
    const before = store.messages.map((message) => message.id)

    await store.ensureTabOwnership()

    // 角色降级（对方确实持锁），但**不清会话、不清屏、不动镜像**
    expect(store.tabOwnership).toBe('duplicate')
    expect(store.sessionId).toBe('tab-slow')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe('tab-slow')
    expect(store.messages.map((message) => message.id)).toEqual(before)
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-slow')).not.toBeNull()
    // 一次性意向已消费：下次刷新不再走这条降级路
    expect(sessionStorage.getItem(RETURN_INTENT_KEY)).toBeNull()
    // 不删别人的会话
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })

  it('复制页签（没有「我会回来」意向）⇒ 仍按既有一律换新会话 + 清继承镜像', async () => {
    seedTab('tab-copy')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-copy')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-copy')

    const store = useAiStore()
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('duplicate')
    expect(store.sessionId).not.toBe('tab-copy')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-copy')).toBeNull()
    expect(sessionStorage.getItem(PAGE_ID_KEY)).not.toBe('page-copy')
  })
})

describe('③ adoptFreshSession 换 id：只释放本页签文档持有的旧锁', () => {
  it('换 id 前释放旧会话的锁（不泄漏到文档销毁），新会话照旧认领', async () => {
    seedTab('tab-own')
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')
    expect(locks.isHeld(SESSION_LOCK_PREFIX + 'tab-own')).toBe(true)

    // 别人的锁（不归本页签文档持有）
    locks.hold(SESSION_LOCK_PREFIX + 'tab-foreign')

    const nextId = store.adoptFreshSession()
    expect(nextId).not.toBe('tab-own')
    // release 是异步的（真实 Web Locks 也一样）⇒ 等一拍再看锁的归属
    await flushPromises()
    // 旧锁已释放：别的页签现在可以接手旧会话（这正是"不泄漏"的可观测面）
    expect(locks.isHeld(SESSION_LOCK_PREFIX + 'tab-own')).toBe(false)
    // 别人的锁一个都没碰
    expect(locks.isHeld(SESSION_LOCK_PREFIX + 'tab-foreign')).toBe(true)

    await store.claimSessionOwnership(nextId)
    await flushPromises()
    expect(locks.isHeld(SESSION_LOCK_PREFIX + nextId)).toBe(true)
  })

  it('unsupported（无 Web Locks）⇒ 换 id 照旧可用，不抛错', () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    const store = useAiStore()
    const before = store.sessionId

    const nextId = store.adoptFreshSession()

    expect(nextId).not.toBe(before)
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe(nextId)
  })
})

describe('④ pageshow / event.persisted：bfcache 恢复后的有界重认领与降级', () => {
  it('pagehide.persisted=true（进 bfcache）⇒ 不广播 released、不释放锁', async () => {
    seedTab('tab-frozen')
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
    expect(locks.isHeld(SESSION_LOCK_PREFIX + 'tab-frozen')).toBe(true)
  })

  it('pageshow（bfcache 恢复）锁仍归我 ⇒ 有界重认领并重新广播 claim', async () => {
    seedTab('tab-keep')
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
    expect(store.sessionId).toBe('tab-keep')
    expect(seen.filter((message) => message.kind === 'claim')).toEqual([
      { kind: 'claim', sessionId: 'tab-keep', pageId: sessionStorage.getItem(PAGE_ID_KEY) }
    ])
  })

  it('pageshow 后锁已归别人 ⇒ 降级为 duplicate，不清会话/不清屏，也不再自认 owner', async () => {
    seedTab('tab-lost')
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
    locks.dropMine(SESSION_LOCK_PREFIX + 'tab-lost')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-lost')
    window.dispatchEvent(pageEvent('pageshow', true))
    await wait(PROBE_BUDGET_MS)

    expect(store.tabOwnership).toBe('duplicate')
    // 不清会话：id、消息、镜像都还在（bfcache 恢复不是"新建对话"）
    expect(store.sessionId).toBe('tab-lost')
    expect(store.messages).toHaveLength(1)
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe('tab-lost')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-lost')).not.toBeNull()
    // 不双自认 owner：降级后不再广播 claim
    expect(seen.filter((message) => message.kind === 'claim')).toHaveLength(0)
    // 也不删别人的会话
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })
})
