/**
 * Task 4 返修 C2/I5：身份目录不可用、跨身份接管拒绝、锁/草稿键隔离、410 恢复取号。
 *
 * <p>每个用例只锁一个契约点（逐条精确运行，不做批量）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { loadFormDraft, saveFormDraft } from '@/utils/form-draft'
import {
  formDraftStorageKey,
  resetAiIdentityCache,
  sessionLockName,
  writeAiIdentity
} from '@/utils/ai-identity'

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

const DIRECTORY = [{ tenantId: 7, users: [{ userId: 71, displayName: '甲' }] }]

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.tenantUsers.mockResolvedValue(DIRECTORY)
  api.createSession.mockResolvedValue({ sessionId: 1001 })
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
  vi.restoreAllMocks()
  resetAiIdentityCache()
})

describe('Task 4 返修：身份守卫单点', () => {
  it('目录失败 ⇒ 明确不可用态、发送禁用（不发请求）；重试成功后恢复身份与发号', async () => {
    api.tenantUsers.mockRejectedValueOnce(new Error('directory down'))
    const store = useAiStore()

    await store.loadTenantDirectory()
    expect(store.directoryStatus).toBe('unavailable')
    expect(store.identity).toBeNull()
    expect(store.sessionId).toBe('')
    expect(store.sessionReady).toBe(false)

    const result = await store.send('你好')
    expect(result.outcome).toBe('not-sent')
    expect(api.chat).not.toHaveBeenCalled()
    expect(api.createSession).not.toHaveBeenCalled()

    // 显式重试：目录恢复 ⇒ 默认第一项身份 + 后端发号。
    api.tenantUsers.mockResolvedValueOnce(DIRECTORY)
    await store.retryLoadTenantDirectory()
    expect(store.directoryStatus).toBe('ready')
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1001')
    expect(api.createSession).toHaveBeenCalledTimes(1)
  })

  it('跨身份接管被拒绝；同身份让渡才进入接手流程', async () => {
    writeAiIdentity({ tenantId: 7, userId: 71 })
    const store = useAiStore()
    store.identity = { tenantId: 7, userId: 71 }
    store.sessionId = '1001'
    store.tabOwnership = 'duplicate'
    store.messages = []
    store.loading = false
    store.activeRunId = null
    const takeover = vi.spyOn(store, 'takeOverSession').mockResolvedValue(undefined)

    vi.useFakeTimers()
    // 异身份（tenant 8 / user 81）的让渡：即便本页签是空白复制品也**不得**接手。
    store.onOwnershipMessage({ kind: 'released', sessionId: '9001', pageId: 'other', tenantId: 8, userId: 81 })
    vi.advanceTimersByTime(2000)
    expect(takeover).not.toHaveBeenCalled()

    // 同身份让渡：宽限后进入接手（以锁为最终裁决，这里只锁"进入流程"）。
    store.onOwnershipMessage({ kind: 'released', sessionId: '9001', pageId: 'other', tenantId: 7, userId: 71 })
    vi.advanceTimersByTime(2000)
    expect(takeover).toHaveBeenCalledTimes(1)
    expect(takeover).toHaveBeenCalledWith('9001')
  })

  it('锁名/草稿键按身份指纹隔离，不同身份读不到彼此草稿', () => {
    writeAiIdentity({ tenantId: 7, userId: 71 })
    saveFormDraft('tc-1', { amount: 1 })
    expect(sessionLockName('1001')).toBe('ai-session-7:71:1001')
    expect(formDraftStorageKey('tc-1')).toBe('cm.ai.form.draft.7:71.tc-1')
    expect(loadFormDraft('tc-1')).toEqual({ amount: 1 })

    writeAiIdentity({ tenantId: 8, userId: 81 })
    expect(sessionLockName('1001')).toBe('ai-session-8:81:1001')
    expect(loadFormDraft('tc-1')).toBeNull()

    writeAiIdentity({ tenantId: 7, userId: 71 })
    expect(loadFormDraft('tc-1')).toEqual({ amount: 1 })
  })

  it('发号成功后补判所有权（启动期无号跳过的判定不会永久关闭）', async () => {
    api.createSession
      .mockRejectedValueOnce(new Error('session endpoint down'))
      .mockResolvedValueOnce({ sessionId: 3001 })
    const store = useAiStore()
    await store.loadTenantDirectory()
    // 首次发号失败：有身份、无会话，目录本身是就绪的。
    expect(store.directoryStatus).toBe('ready')
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('')
    expect(store.tabOwnership).toBe('unknown')

    const ownership = vi.spyOn(store, 'ensureTabOwnership')
    const issued = await store.ensureSession()

    expect(issued).toBe('3001')
    expect(store.sessionId).toBe('3001')
    // 返修 I2：发号成功立刻补一次所有权判定（无 Web Locks ⇒ 'unsupported' 终态，不再是 unknown）。
    expect(ownership).toHaveBeenCalled()
    expect(store.tabOwnership).toBe('unsupported')
  })

  it('目录加载与历史恢复并行：历史经引导门等待目录，只发一次号（不重复发号）', async () => {
    const store = useAiStore()
    // 面板不再 await 目录：目录在后台跑，历史恢复同时启动并等待引导门。
    const directory = store.loadTenantDirectory()
    const history = store.loadHistory()
    await Promise.all([directory, history])

    expect(store.directoryStatus).toBe('ready')
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1001')
    expect(api.createSession).toHaveBeenCalledTimes(1)
    expect(api.history).toHaveBeenCalledTimes(1)
    expect(api.history).toHaveBeenCalledWith('1001')
  })

  it('410 会话已销毁 ⇒ 本地换会话并向后端重新取号（旧指针被新号替换）', async () => {
    api.createSession
      .mockResolvedValueOnce({ sessionId: 1001 })
      .mockResolvedValueOnce({ sessionId: 1002 })
    api.chat.mockRejectedValue(new ApiError(
      '会话已销毁', 410, ErrorCode.SESSION_PURGED, { code: ErrorCode.SESSION_PURGED }
    ))
    const store = useAiStore()
    await store.loadTenantDirectory()
    expect(store.sessionId).toBe('1001')

    const result = await store.send('查一下')

    expect(result.outcome).toBe('error')
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ sessionId: '1001' })
    expect(api.createSession).toHaveBeenCalledTimes(2)
    expect(store.sessionId).toBe('1002')
    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe('1002')
  })
})
