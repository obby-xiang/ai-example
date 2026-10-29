/**
 * Task 4 返修 C1：**真实复制页签路径**不得抹掉身份。
 *
 * <p>复制页签判定成立时会对本页签 `sessionStorage.clear()`（继承现场一律不带），随后换会话。
 * 身份是**本页签当前选择**而不是"继承来的脏数据"：清空后必须按内存身份回写，否则该页签此后
 * 全部 AI 请求都不带 `X-Tenant-Id`/`X-User-Id`、镜像/草稿/锁键形退化为无身份口径。
 *
 * <p>本用例走真实路径 `ensureTabOwnership()`（Web Locks 显示会话锁被另一文档持有），不是用
 * `adoptFreshSession()` 模拟。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { saveFormDraft } from '@/utils/form-draft'
import { identityHeaders, resetAiIdentityCache, sessionLockName, writeAiIdentity } from '@/utils/ai-identity'
import { channels, FakeBroadcastChannel, FakeLockManager, wait } from './tab-ownership-fixtures'

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

const IDENTITY = { tenantId: 7, userId: 71 }
const OLD_SESSION = '1001'
const NEW_SESSION = '2001'

function messageOf(text: string): ChatMessage {
  return {
    id: 'm-old',
    role: 'assistant',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

let locks: FakeLockManager

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  channels.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  locks = new FakeLockManager()
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)

  api.tenantUsers.mockResolvedValue([{ tenantId: 7, users: [{ userId: 71, displayName: '甲' }] }])
  api.createSession.mockResolvedValue({ sessionId: Number(NEW_SESSION) })
  api.history.mockResolvedValue({ sessionId: NEW_SESSION, count: 0, messages: [] })
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
  resetAiIdentityCache()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('Task 4 返修 C1：真实复制页签路径保留身份', () => {
  it('复制页签清空 sessionStorage 后按内存身份回写：身份头/镜像/草稿/锁仍带指纹', async () => {
    // 复制页签继承的现场：身份 + 旧会话指针 + 旧镜像；原页签仍持有旧会话锁。
    writeAiIdentity(IDENTITY)
    sessionStorage.setItem('ai-session-id:7:71', OLD_SESSION)
    sessionStorage.setItem('ai-mirror:7:71:1001', JSON.stringify({ v: 2, savedAt: 1, messages: [] }))
    locks.hold(sessionLockName(OLD_SESSION))

    const store = useAiStore()
    expect(store.sessionId).toBe(OLD_SESSION)

    await store.ensureTabOwnership()
    await wait(20)

    // 复制品换了会话，但身份没有丢。
    expect(store.sessionId).toBe(NEW_SESSION)
    expect(store.identity).toEqual(IDENTITY)
    expect(sessionStorage.getItem('ai-identity')).toContain('"tenantId":7')
    // 身份头仍可生成（C1 的后果就是这里会变成空对象）。
    expect(identityHeaders()).toEqual({ 'X-Tenant-Id': '7', 'X-User-Id': '71' })
    // 旧镜像不继承（复制页签清空继承现场），新会话镜像/草稿/锁都带指纹。
    expect(sessionStorage.getItem('ai-mirror:7:71:1001')).toBeNull()

    store.historyLoaded = true
    store.messages = [messageOf('复制页签的新会话')]
    store.writeMirrorNow()
    expect(sessionStorage.getItem('ai-mirror:7:71:2001')).toContain('复制页签的新会话')
    expect(sessionStorage.getItem('ai-mirror:2001')).toBeNull()

    saveFormDraft('tc-1', { amount: 1 })
    expect(sessionStorage.getItem('cm.ai.form.draft.7:71.tc-1')).toContain('amount')
    expect(sessionStorage.getItem('cm.ai.form.draft.tc-1')).toBeNull()

    expect(sessionLockName(NEW_SESSION)).toBe('ai-session-7:71:2001')
    expect(locks.isHeld(sessionLockName(NEW_SESSION))).toBe(true)
  })
})
