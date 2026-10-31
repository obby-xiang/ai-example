/**
 * 慢重载回让 / 探活后新建 / 资格清理与身份一致性（F1 / F2 / F7 / F8）。
 *
 * <h2>缺陷</h2>
 * <ul>
 * <li><b>F1</b>：`released` 的 1500ms 宽限只是"常见刷新时长"的折中。比它更慢的重载里，空白副本
 *   已经**接手**了原会话；原页签带着**有效返回意向**回来时只能降级成 duplicate 坐等 —— 副本不会
 *   让回，于是"原页签刷新后会话被副本占着"（用户看到的就是被抢）。修法不是延长超时，而是用
 *   **可观测的返回意向**：回到 duplicate 的原页签把意向随 `claim` 广播出去，接管方据此**静默让回**，
 *   原页签收到让回后重新认领。</li>
 * <li><b>F2</b>：`takeOverSession` 的探活是异步的 —— 期间用户主动"新建对话"会清场换号，探活回来
 *   若无条件写 id，就把刚新建的会话覆盖掉。</li>
 * <li><b>F7</b>：有内容早退、以及 410 的 `adoptFreshSession` 都必须作废复制来源资格。</li>
 * <li><b>F8</b>：资格属于**会话**，判据必须统一用 `sessionIdentity`（不是界面当前选中的 identity）。</li>
 * </ul>
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
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
  sessionIdStorageKey,
  sessionLockName
} from '@/utils/ai-identity'
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

const PROBE_BUDGET_MS = LOCK_PROBE_ATTEMPTS * LOCK_PROBE_RETRY_MS + 400
const GRACE_BUDGET_MS = OWNERSHIP_RELEASE_GRACE_MS + PROBE_BUDGET_MS

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
  api.chat.mockImplementation(() => new Promise(() => {}))
  installIdentityFixture()
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

/** 让本页签成为"已接手原会话的空白副本"：判复制 → 自己取号 → 原页签关闭 → 宽限后接手。 */
async function becomeTakerOf(sessionId: string): Promise<ReturnType<typeof useAiStore>> {
  seedTab(sessionId)
  sessionStorage.setItem(PAGE_ID_KEY, 'page-orig')
  locks.hold(sessionLockName(sessionId))
  const store = useAiStore()
  await store.ensureTabOwnership()
  expect(store.sessionId).not.toBe(sessionId)
  locks.release(sessionLockName(sessionId))
  broadcast({ kind: 'released', sessionId, pageId: 'page-orig', tenantId: 7, userId: 71 })
  await wait(GRACE_BUDGET_MS)
  await flushPromises()
  expect(store.sessionId).toBe(sessionId)
  expect(store.tabOwnership).toBe('owner')
  return store
}

describe('F1 慢重载：带返回意向的 claim ⇒ 接管方静默让回', () => {
  it('空白副本已接手，原页签带有效返回意向回来 ⇒ 让回会话、放锁、自己回到空白会话，零提示', async () => {
    const store = await becomeTakerOf('9701')
    const takenSession = store.sessionId
    expect(locks.isHeld(sessionLockName(takenSession))).toBe(true)

    // 慢重载的原页签回来了（超过宽限）：它不是复制品，带着有效返回意向
    broadcast({
      kind: 'claim',
      sessionId: '9701',
      pageId: 'page-orig',
      tenantId: 7,
      userId: 71,
      returning: true
    })
    await vi.waitFor(() => expect(store.sessionId).not.toBe('9701'))
    await flushPromises()

    // 让回：原会话的锁必须空闲（原页签才探得到），本页签回到自己的新会话
    expect(locks.isHeld(sessionLockName('9701'))).toBe(false)
    expect(store.tabOwnership).toBe('owner')
    expect(locks.isHeld(sessionLockName(store.sessionId))).toBe(true)
    // 静默：不新增提示，也不删原会话的后端记忆
    expect(store.notice).toBeNull()
    expect(api.deleteHistory).not.toHaveBeenCalled()
  }, 20_000)

  it('让回只在空白时成立：本页签已在接手的会话里产生内容 ⇒ 不让回（不换掉用户眼前内容）', async () => {
    const store = await becomeTakerOf('9702')
    store.recordUserMessage('我已经在这里说话了')

    broadcast({
      kind: 'claim',
      sessionId: '9702',
      pageId: 'page-orig',
      tenantId: 7,
      userId: 71,
      returning: true
    })
    await wait(PROBE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).toBe('9702')
    expect(locks.isHeld(sessionLockName('9702'))).toBe(true)
    expect(store.messages).toHaveLength(1)
  }, 20_000)
})

describe('F1 另一半：回来慢的原页签广播返回意向，并在对方让回后重新认领', () => {
  const RETURNING_SESSION = '9703'

  function seedReturningTab(): void {
    sessionStorage.setItem(PAGE_ID_KEY, 'page-orig')
    seedTab(RETURNING_SESSION)
    sessionStorage.setItem(RETURN_INTENT_KEY, JSON.stringify({
      sessionId: RETURNING_SESSION,
      pageId: 'page-orig',
      at: Date.now(),
      fingerprint: identityFingerprint(currentAiIdentity()!)
    }))
    // 副本仍持有该会话（原页签探活必然 occupied ⇒ duplicate）
    locks.hold(sessionLockName(RETURNING_SESSION))
  }

  it('判为 duplicate 时把返回意向随 claim 广播出去（否则接管方无从让回）', async () => {
    seedReturningTab()
    const seen: Array<Record<string, unknown>> = []
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    witness.onmessage = (event) => seen.push(event.data as Record<string, unknown>)

    const store = useAiStore()
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('duplicate')
    expect(store.sessionId).toBe(RETURNING_SESSION) // 降级不清场
    expect(seen).toContainEqual(expect.objectContaining({
      kind: 'claim',
      sessionId: RETURNING_SESSION,
      returning: true
    }))
  })

  it('接管方让回（锁释放 + released）⇒ 回来的原页签重新成为 owner 并重播 claim', async () => {
    seedReturningTab()
    const seen: Array<Record<string, unknown>> = []
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    witness.onmessage = (event) => seen.push(event.data as Record<string, unknown>)

    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('duplicate')

    // 副本静默让回：先放锁、再广播 released
    locks.release(sessionLockName(RETURNING_SESSION))
    broadcast({ kind: 'released', sessionId: RETURNING_SESSION, pageId: 'page-copy', tenantId: 7, userId: 71 })

    await vi.waitFor(() => expect(store.tabOwnership).toBe('owner'))
    expect(store.sessionId).toBe(RETURNING_SESSION)
    expect(locks.isHeld(sessionLockName(RETURNING_SESSION))).toBe(true)
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(RETURNING_SESSION)
    // 收回后重播的 claim 不带返回意向（已是常规认领）
    const claims = seen.filter((message) => message.kind === 'claim' && message.sessionId === RETURNING_SESSION)
    expect(claims[claims.length - 1]?.returning).toBeUndefined()
    // 收回是静默的：不弹提示、不重读历史（屏上内容没有被清）
    expect(store.notice).toBeNull()
    expect(api.history).not.toHaveBeenCalled()
  }, 20_000)
})

describe('F2 探活窗口内用户新建对话 ⇒ 探活回来不得覆盖', () => {
  it('takeOverSession 探活期间 adoptFreshSession 换了会话 ⇒ 放弃接手并把锁还回去', async () => {
    const store = useAiStore()
    store.sessionId = 'S-old'
    store.sessionIdentity = store.identity
    store.tabOwnership = 'owner'

    const takeover = store.takeOverSession('9704')
    // 同刻用户主动新建对话（同步清场 + 换号）
    store.adoptFreshSession()
    store.sessionId = 'S-new'

    await takeover
    await flushPromises()

    expect(store.sessionId).toBe('S-new')
    expect(locks.isHeld(sessionLockName('9704'))).toBe(false)
  })
})

describe('F7 资格清理：有内容早退 / 410 adoptFreshSession', () => {
  it('有内容时收到让渡 ⇒ 不接手，且资格作废（此后空白也不再接手）', async () => {
    seedTab('9705')
    locks.hold(sessionLockName('9705'))
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.sessionId).not.toBe('9705')

    store.recordUserMessage('我在这页签说话了')
    locks.release(sessionLockName('9705'))
    broadcast({ kind: 'released', sessionId: '9705', pageId: 'page-orig', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()
    expect(store.sessionId).not.toBe('9705')

    // 内容清空（例如用户在一次失败后清屏）也不得因旧资格去接手
    store.messages = []
    broadcast({ kind: 'released', sessionId: '9705', pageId: 'page-orig', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()
    expect(store.sessionId).not.toBe('9705')
  }, 20_000)

  it('410 SESSION_PURGED 走 adoptFreshSession ⇒ 资格作废，原会话再让渡也不接手', async () => {
    seedTab('9706')
    locks.hold(sessionLockName('9706'))
    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    expect(ownSession).not.toBe('9706')

    api.chat.mockRejectedValue(new ApiError('会话已销毁', 410, ErrorCode.SESSION_PURGED, {}))
    await store.send('这条会撞 410')
    await flushPromises()
    const afterPurge = store.sessionId
    expect(afterPurge).not.toBe('9706')

    locks.release(sessionLockName('9706'))
    broadcast({ kind: 'released', sessionId: '9706', pageId: 'page-orig', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).not.toBe('9706')
    expect(store.sessionId).toBe(afterPurge)
  }, 20_000)
})

describe('F8 身份分叉：资格判据统一用 sessionIdentity', () => {
  it('界面 identity 与会话 sessionIdentity 分叉时不按界面身份认领（防串身份接手）', async () => {
    seedTab('9707')
    locks.hold(sessionLockName('9707'))
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.sessionId).not.toBe('9707')

    // 分叉：界面 identity 仍与**记录**一致（预修复会据此接手），但本会话事实上的身份已是另一个
    store.identity = { tenantId: 7, userId: 71 }
    store.sessionIdentity = { tenantId: 7, userId: 72 }

    // 消息按界面身份通过顶层过滤（7:71），资格却属于会话身份（7:72）⇒ 不得接手
    locks.release(sessionLockName('9707'))
    broadcast({ kind: 'released', sessionId: '9707', pageId: 'page-orig', tenantId: 7, userId: 71 })
    await wait(GRACE_BUDGET_MS)
    await flushPromises()

    expect(store.sessionId).not.toBe('9707')
  }, 20_000)
})
