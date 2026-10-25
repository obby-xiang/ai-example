/**
 * 复制页签 = 全新工作区（用户批准口径）：**先确认复制，再清空一次**。
 *
 * <p>本套用例同时充当"判据可靠性"证据：只有 Web Locks 显示该会话锁被**另一个文档**持有时，
 * 本页签才被判为复制品并清空；拿到锁（普通刷新 / 快重载）或环境无 Web Locks（无法确认）
 * 一律**不清**；带一次性返回意向的慢重载只降级、不清。
 *
 * <p>清空范围 = 本页签**自己**的整份 sessionStorage（含继承来的镜像、未提交表单草稿、业务页签键），
 * 随后重建 pageId 与 sessionId。localStorage、原页签的锁/广播、服务端会话都不碰。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import {
  LOCK_PROBE_ATTEMPTS,
  LOCK_PROBE_RETRY_MS,
  MIRROR_PREFIX,
  OWNERSHIP_CHANNEL_NAME,
  PAGE_ID_KEY,
  RETURN_INTENT_KEY,
  SESSION_ID_KEY,
  SESSION_LOCK_PREFIX,
  useAiStore
} from '@/stores/ai'
import { FORM_DRAFT_PREFIX } from '@/utils/form-draft'
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

/** 探活重试的最坏耗时 + 余量（`resolveTabOwnership` 是 3 × 150ms）。 */
const PROBE_BUDGET_MS = LOCK_PROBE_ATTEMPTS * LOCK_PROBE_RETRY_MS + 400

let locks: FakeLockManager

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

/** 铺满"复制页签会整份继承"的现场：会话 + 镜像 + 草稿 + 业务页签键 + localStorage。 */
function seedDuplicatedTab(sessionId: string, pageId: string): void {
  seedTab(sessionId)
  sessionStorage.setItem(PAGE_ID_KEY, pageId)
  sessionStorage.setItem(FORM_DRAFT_PREFIX + 'call-1', JSON.stringify({ name: '未提交草稿' }))
  sessionStorage.setItem('import-wizard-draft', '{"step":2}')
  sessionStorage.setItem('some-business-tab-state', 'kept-elsewhere')
  localStorage.setItem('cm.locale', 'zh-CN')
  localStorage.setItem('cm.sidebar-width', '280')
}

/** 监听所有权广播，返回收到的消息（用于证明"原页签侧"没被影响）。 */
function watchOwnership(): Array<Record<string, unknown>> {
  const seen: Array<Record<string, unknown>> = []
  const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
  witness.onmessage = (event) => seen.push(event.data as Record<string, unknown>)
  return seen
}

/** Web Locks API 直接 reject（探活"无法确认"，不是"锁被占用"）。 */
class RejectingLockManager {
  requests = 0

  request(): Promise<never> {
    this.requests += 1
    return Promise.reject(new Error('lock manager unavailable'))
  }
}

/** 前两次探活都被占、第三次空闲（刷新时旧文档锁延迟释放的竞态）。 */
class FreeOnThirdProbeLockManager {
  private attempts = 0
  private readonly inner = new FakeLockManager()

  request(
    name: string,
    options: { ifAvailable?: boolean },
    callback: (lock: { name: string } | null) => unknown
  ): Promise<unknown> {
    this.attempts += 1
    if (this.attempts < 3) {
      return Promise.resolve(callback(null))
    }
    return this.inner.request(name, options, callback)
  }

  isHeld(name: string): boolean {
    return this.inner.isHeld(name)
  }
}

describe('① 判据：复制页签与普通刷新必须被区别对待', () => {
  it('同一会话的锁被另一文档持有 + 无返回意向 ⇒ duplicate，且只清一次', async () => {
    seedDuplicatedTab('tab-copy', 'page-copy')
    // 原页签仍开着自己的文档：同一把会话锁被它持有
    locks.hold(SESSION_LOCK_PREFIX + 'tab-copy')

    const store = useAiStore()
    const clear = vi.spyOn(Storage.prototype, 'clear')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('duplicate')
    // "确认复制"是清空的前提：探活发生在 clear 之前，clear 只此一次
    expect(clear).toHaveBeenCalledTimes(1)
  })

  it('普通刷新 / 快重载（锁空闲）⇒ owner，一次都不清，全部继承现场照旧', async () => {
    seedDuplicatedTab('tab-reload', 'page-reload')
    // 旧文档已销毁：会话锁空闲（刷新后的新文档第一个拿到）

    const store = useAiStore()
    const clear = vi.spyOn(Storage.prototype, 'clear')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('owner')
    expect(clear).not.toHaveBeenCalled()
    expect(store.sessionId).toBe('tab-reload')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe('tab-reload')
    expect(sessionStorage.getItem(PAGE_ID_KEY)).toBe('page-reload')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-reload')).not.toBeNull()
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).not.toBeNull()
    expect(sessionStorage.getItem('import-wizard-draft')).not.toBeNull()
  })

  it('慢重载（带一次性返回意向、锁已被副本接手）⇒ 降级但不清：整份现场还在', async () => {
    sessionStorage.setItem(PAGE_ID_KEY, 'page-slow')
    seedTab('tab-slow')
    sessionStorage.setItem(FORM_DRAFT_PREFIX + 'call-1', JSON.stringify({ name: '草稿' }))
    sessionStorage.setItem(
      RETURN_INTENT_KEY,
      JSON.stringify({ sessionId: 'tab-slow', pageId: 'page-slow', at: Date.now() })
    )
    locks.hold(SESSION_LOCK_PREFIX + 'tab-slow')

    const store = useAiStore()
    store.recordUserMessage('刷新前正在看的记录')
    const clear = vi.spyOn(Storage.prototype, 'clear')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('duplicate')
    expect(clear).not.toHaveBeenCalled()
    expect(store.sessionId).toBe('tab-slow')
    expect(store.messages).toHaveLength(1)
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-slow')).not.toBeNull()
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).not.toBeNull()
    // 返回意向已一次性消费（下次刷新不再走降级路）
    expect(sessionStorage.getItem(RETURN_INTENT_KEY)).toBeNull()
  })

  it('环境无 Web Locks（无法确认复制）⇒ unsupported，不清：宁可残留也不误伤刷新', () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    seedDuplicatedTab('tab-nolocks', 'page-nolocks')

    const store = useAiStore()
    const clear = vi.spyOn(Storage.prototype, 'clear')
    expect(store.ensureTabOwnership()).toBeNull()

    expect(store.tabOwnership).toBe('unsupported')
    expect(clear).not.toHaveBeenCalled()
    expect(store.sessionId).toBe('tab-nolocks')
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).not.toBeNull()
  })

  it('探活 API reject（无法确认）⇒ unsupported，绝不判复制、绝不整份清空', async () => {
    seedDuplicatedTab('tab-lockerr', 'page-lockerr')
    const rejecting = new RejectingLockManager()
    Object.defineProperty(navigator, 'locks', { value: rejecting, configurable: true })

    const store = useAiStore()
    const clear = vi.spyOn(Storage.prototype, 'clear')
    await store.ensureTabOwnership()

    // API 失败不是"锁被另一文档持有"的证据：fail-closed 到 unsupported，保持原身份与现场
    expect(rejecting.requests).toBeGreaterThan(0)
    expect(store.tabOwnership).toBe('unsupported')
    expect(store.tabOwnership).not.toBe('duplicate')
    expect(clear).not.toHaveBeenCalled()
    expect(store.sessionId).toBe('tab-lockerr')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe('tab-lockerr')
    expect(sessionStorage.getItem(PAGE_ID_KEY)).toBe('page-lockerr')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-lockerr')).not.toBeNull()
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).not.toBeNull()
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })

  it('锁占 1–2 次后第 3 次空闲（旧文档锁延迟释放）⇒ owner，绝不清空', async () => {
    seedDuplicatedTab('tab-race', 'page-race')
    const delayed = new FreeOnThirdProbeLockManager()
    Object.defineProperty(navigator, 'locks', { value: delayed, configurable: true })

    const store = useAiStore()
    const clear = vi.spyOn(Storage.prototype, 'clear')
    await store.ensureTabOwnership()

    // 重试存在的意义：刷新时旧文档的锁可能尚未随文档销毁释放，不能早判复制
    expect(store.tabOwnership).toBe('owner')
    expect(clear).not.toHaveBeenCalled()
    expect(store.sessionId).toBe('tab-race')
    expect(sessionStorage.getItem(PAGE_ID_KEY)).toBe('page-race')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-race')).not.toBeNull()
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).not.toBeNull()
    expect(delayed.isHeld(SESSION_LOCK_PREFIX + 'tab-race')).toBe(true)
  })
})

describe('② 确认复制之后：清一次 → 新 pageId → 新 AI 会话', () => {
  it('继承来的会话镜像、未提交草稿、业务页签键全部不继承；只留新的两个身份键', async () => {
    seedDuplicatedTab('tab-copy', 'page-copy')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-copy')

    const store = useAiStore()
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('duplicate')
    expect(store.messages).toEqual([])
    // 新会话 + 新页签身份：两者都独立于原值
    expect(store.sessionId).not.toBe('tab-copy')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe(store.sessionId)
    expect(sessionStorage.getItem(PAGE_ID_KEY)).not.toBe('page-copy')
    expect(sessionStorage.getItem(PAGE_ID_KEY)).not.toBeNull()
    // 整份继承现场都不在了（含未提交草稿 —— 有意不继承）
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-copy')).toBeNull()
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).toBeNull()
    expect(sessionStorage.getItem('import-wizard-draft')).toBeNull()
    expect(sessionStorage.getItem('some-business-tab-state')).toBeNull()
    // 清空后只留下本页签重建的两个键
    expect([...Array(sessionStorage.length)].map((_, i) => sessionStorage.key(i)).sort()).toEqual(
      [PAGE_ID_KEY, SESSION_ID_KEY].sort()
    )
  })

  it('只清本页签副本：localStorage、原页签的锁与广播、服务端会话都不动', async () => {
    seedDuplicatedTab('tab-copy', 'page-copy')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-copy')
    const seen = watchOwnership()

    const store = useAiStore()
    await store.ensureTabOwnership()
    await flushPromises()

    // localStorage 永不清
    expect(localStorage.getItem('cm.locale')).toBe('zh-CN')
    expect(localStorage.getItem('cm.sidebar-width')).toBe('280')
    // 原页签的会话锁仍在它手里（本页签没有抢）
    expect(locks.isHeld(SESSION_LOCK_PREFIX + 'tab-copy')).toBe(true)
    // 原页签的服务端会话一个字都没删
    expect(api.deleteHistory).not.toHaveBeenCalled()
    // 广播只出现本页签新会话的 claim，没有替原会话认领
    const claims = seen.filter((message) => message.kind === 'claim')
    expect(claims).toHaveLength(1)
    expect(claims[0]).toMatchObject({ sessionId: store.sessionId })
    expect(claims[0]?.sessionId).not.toBe('tab-copy')
    // 新会话的锁由本页签持有
    expect(locks.isHeld(SESSION_LOCK_PREFIX + store.sessionId)).toBe(true)
  })

  it('复制页签随后刷新（已是普通页签）⇒ 不再清第二次', async () => {
    seedDuplicatedTab('tab-copy', 'page-copy')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-copy')

    const first = useAiStore()
    await first.ensureTabOwnership()
    const newSession = first.sessionId
    const newPage = sessionStorage.getItem(PAGE_ID_KEY)

    // 刷新：旧文档销毁（锁释放），新文档从同一份 sessionStorage 起步
    locks.dropMine(SESSION_LOCK_PREFIX + newSession)
    channels.clear()
    setActivePinia(createPinia())
    const clear = vi.spyOn(Storage.prototype, 'clear')
    const second = useAiStore()
    await second.ensureTabOwnership()

    expect(second.tabOwnership).toBe('owner')
    expect(clear).not.toHaveBeenCalled()
    expect(second.sessionId).toBe(newSession)
    expect(sessionStorage.getItem(PAGE_ID_KEY)).toBe(newPage)
  })

  it('bfcache 恢复（pagehide.persisted / pageshow）⇒ 不清，现场原样保留', async () => {
    seedTab('tab-frozen')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-frozen')
    sessionStorage.setItem(FORM_DRAFT_PREFIX + 'call-1', JSON.stringify({ name: '冻结前草稿' }))

    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')

    const clear = vi.spyOn(Storage.prototype, 'clear')
    window.dispatchEvent(pageEvent('pagehide', true))
    window.dispatchEvent(pageEvent('pageshow', true))
    await wait(PROBE_BUDGET_MS)

    expect(clear).not.toHaveBeenCalled()
    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).toBe('tab-frozen')
    expect(sessionStorage.getItem(FORM_DRAFT_PREFIX + 'call-1')).not.toBeNull()
  })
})
