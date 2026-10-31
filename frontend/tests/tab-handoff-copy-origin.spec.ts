/**
 * N09 / M07（复制页签让渡）：角色已是 `owner` 的**空白复制页签**仍要能在原页签关闭后静默接管。
 *
 * <h2>缺陷</h2>
 * `onOwnershipMessage` 只认 `tabOwnership === 'duplicate'` 的页签接手 `released`。但复制页签
 * 判完就向后端取号并认领自己的会话锁 ⇒ 角色立刻变 `owner`（Task 4 口径），于是"原页签关闭 ⇒
 * 副本接手原会话"这条路径**永远不可达**：用户关掉原页签后，另一个页签只剩一个空会话。
 *
 * <h2>口径（本文件锁住）</h2>
 * <ol>
 * <li>被判为复制页签时记下**复制来源会话**（handoff 资格）；该会话的 `released` 到达且本页签
 *   仍空白 ⇒ 宽限后按锁接手，静默（不新增任何提示）；</li>
 * <li>用户**主动新建对话 / 切换身份**后资格作废（防劫持、防串会话）；</li>
 * <li>资格只对**记录下来的那个会话**生效：别的会话的 `released` 不接手；</li>
 * <li>锁仍被占 / 本页签已有内容 ⇒ 不接手（既有约束不变，回归锁在 r166b / lifecycle）。</li>
 * </ol>
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
  useAiStore
} from '@/stores/ai'
import { sessionIdStorageKey, sessionLockName } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import {
  channels,
  FakeBroadcastChannel,
  FakeLockManager,
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

/** 探活重试的最坏耗时 + 余量（`resolveTabOwnership` 是 3 × 150ms）。 */
const PROBE_BUDGET_MS = LOCK_PROBE_ATTEMPTS * LOCK_PROBE_RETRY_MS + 400
/** 宽限到期 + 探活/微任务余量。 */
const GRACE_BUDGET_MS = OWNERSHIP_RELEASE_GRACE_MS + PROBE_BUDGET_MS

/** 广播一条所有权消息（模拟另一个页签的广播）。 */
function broadcast(message: Record<string, unknown>): void {
  new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage(message)
}

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

describe('N09/M07：空白复制页签按复制来源接手（静默）', () => {
  it('原页签关闭（锁释放 + released）⇒ 空白副本接管原会话：读回原历史、无提示、锁归本页签', async () => {
    seedTab('9601')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-orig')
    locks.hold(sessionLockName('9601'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const copySession = store.sessionId
    // 复制页签判完即取自己的号并认领锁 ⇒ 角色是 owner（旧判据只看 duplicate，接手因此不可达）
    expect(copySession).not.toBe('9601')
    expect(store.tabOwnership).toBe('owner')
    expect(locks.isHeld(sessionLockName(copySession))).toBe(true)

    // 原页签真关闭：锁随文档销毁释放 + pagehide 广播让渡意向
    locks.release(sessionLockName('9601'))
    broadcast({ kind: 'released', sessionId: '9601', pageId: 'page-orig', tenantId: 7, userId: 71 })
    // 宽限内不抢（原页签可能只是刷新，回来会重播 claim）
    await flushPromises()
    expect(store.sessionId).toBe(copySession)

    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe('9601')
    expect(store.tabOwnership).toBe('owner')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe('9601')
    // 原会话的历史照常读回（接管 = 静默换回原会话，不是新建）
    expect(api.history).toHaveBeenCalledWith('9601')
    // 原会话的锁归本页签（不再占着已放弃的空白会话锁）
    expect(locks.isHeld(sessionLockName('9601'))).toBe(true)
    expect(locks.isHeld(sessionLockName(copySession))).toBe(false)
    // 静默：不新增任何用户提示
    expect(store.notice).toBeNull()
    // 原会话的后端记忆一个字都没动
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })

  it('用户主动新建对话 ⇒ 资格作废：原会话让渡也不再接手', async () => {
    seedTab('9602')
    locks.hold(sessionLockName('9602'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.sessionId).not.toBe('9602')

    // 用户主动新建（本页签已经有"自己的"会话意图）
    await store.resetSession()
    const afterNew = store.sessionId
    expect(afterNew).not.toBe('')

    locks.release(sessionLockName('9602'))
    broadcast({ kind: 'released', sessionId: '9602', pageId: 'page-orig', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe(afterNew)
    expect(api.history).not.toHaveBeenCalledWith('9602')
  })

  it('用户切换身份（切走再切回）⇒ 资格作废：同身份的让渡也不再接手', async () => {
    seedTab('9603')
    locks.hold(sessionLockName('9603'))
    installIdentityFixture({
      directory: [
        { tenantId: 7, users: [{ userId: 71, displayName: '测试用户' }, { userId: 72, displayName: '另一位' }] }
      ]
    })

    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.sessionId).not.toBe('9603')

    // 切走再切回（身份回到 7:71 ⇒ 广播的身份判据仍匹配，只有"资格"这一层能拦住）
    await store.switchIdentity(7, 72)
    await store.switchIdentity(7, 71)
    const afterSwitch = store.sessionId

    locks.release(sessionLockName('9603'))
    broadcast({ kind: 'released', sessionId: '9603', pageId: 'page-orig', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe(afterSwitch)
    expect(api.history).not.toHaveBeenCalledWith('9603')
  })

  it('资格只认记录下来的复制来源：别的会话 released 不接手（防劫持）', async () => {
    seedTab('9604')
    locks.hold(sessionLockName('9604'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const copySession = store.sessionId

    // 另一个会话（不是本页签复制来源）让渡 —— 锁也是空闲的，仍不得接手
    broadcast({ kind: 'released', sessionId: '9699', pageId: 'page-other', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe(copySession)
    expect(api.history).not.toHaveBeenCalledWith('9699')
    expect(locks.isHeld(sessionLockName('9699'))).toBe(false)
  })
})
