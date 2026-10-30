/**
 * Task 4 单点：租户/用户身份切换的会话发号与存储隔离。
 *
 * <p>锁住四件事（一条用例，避免"整文件/批量"式的测试面）：
 * <ol>
 * <li><b>切换即取新号</b>：身份切换后由后端 `POST /api/ai/session` 发号，前端不再自己造 id；</li>
 * <li><b>旧会话与镜像保留在原身份下</b>：旧身份的镜像键不被删除，新身份看不到它；</li>
 * <li><b>旧响应不写入新会话</b>：切换前在途的那一轮回来时，新会话消息/镜像都不得变化；</li>
 * <li><b>复制页签/换会话先判所有权再取号</b>：本地清场不提前发号，真正需要会话时才走后端取号门。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import {
  identityHeaders,
  mirrorStorageKey,
  resetAiIdentityCache,
  sessionLockName
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

const DIRECTORY = [
  { tenantId: 7, users: [{ userId: 71, displayName: '甲' }, { userId: 72, displayName: '乙' }] },
  { tenantId: 8, users: [{ userId: 81, displayName: '丙' }] }
]

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

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.tenantUsers.mockResolvedValue(DIRECTORY)
  api.createSession
    .mockResolvedValueOnce({ sessionId: 1001 })
    .mockResolvedValueOnce({ sessionId: 1002 })
    .mockResolvedValue({ sessionId: 1003 })
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
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
  vi.restoreAllMocks()
})

describe('Task 4：身份切换的会话发号与存储隔离', () => {
  it('切换取新号、旧镜像留在原身份、旧响应不写入新会话、复制页签先判所有权再取号', async () => {
    const store = useAiStore()
    const sentSessionIds: unknown[] = []

    // ① 载入目录：默认取第一项；会话号由后端发（前端不自造）。
    await store.loadTenantDirectory()
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(api.createSession).toHaveBeenCalledTimes(1)
    expect(store.sessionId).toBe('1001')

    // ② 旧身份留下一份镜像与一条消息。
    store.historyLoaded = true
    store.messages = [messageOf('甲的旧会话')]
    store.writeMirrorNow()
    const oldMirrorKey = 'ai-mirror:7:71:1001'
    expect(sessionStorage.getItem(oldMirrorKey)).toContain('甲的旧会话')

    // ③ 起一轮在途对话（旧身份），先不结算。
    let releaseOld: () => void = () => undefined
    api.chat.mockImplementation((_request: unknown, handlers: any) => new Promise((resolve) => {
      releaseOld = () => {
        handlers.onFrame?.({ type: 'delta', runId: 'run-old', seq: 1, messageId: 'm-old', text: '旧响应' })
        handlers.onFrame?.({ type: 'done', runId: 'run-old', seq: 2, cancelled: false })
        resolve({ terminal: true })
      }
    }))
    const inFlight = store.send('你好')
    await flushPromises()
    expect(api.chat).toHaveBeenCalledTimes(1)
    sentSessionIds.push((api.chat.mock.calls[0]?.[0] as { sessionId: unknown }).sessionId)
    expect(sentSessionIds[0]).toBe('1001')

    // ④ 切换身份：后端发新号，展示清空；旧镜像按旧身份保留，新身份下不存在。
    await store.switchIdentity(8, 81)
    expect(store.identity).toEqual({ tenantId: 8, userId: 81 })
    expect(api.createSession).toHaveBeenCalledTimes(2)
    expect(store.sessionId).toBe('1002')
    expect(store.messages).toEqual([])
    expect(sessionStorage.getItem(oldMirrorKey)).toContain('甲的旧会话')
    expect(sessionStorage.getItem('ai-mirror:8:81:1002')).toBeNull()
    expect(sessionStorage.getItem('ai-identity')).toContain('"userId":81')

    // ⑤ 旧响应迟到：不得写进新会话（消息与镜像都不变）。
    releaseOld()
    await inFlight
    await flushPromises()
    expect(store.messages).toEqual([])
    expect(store.sessionId).toBe('1002')
    expect(sessionStorage.getItem('ai-mirror:8:81:1002')).toBeNull()

    // ⑥ 复制页签/410 恢复：本地清场不提前发号，真正需要会话时才经引导门取号。
    const issuedBefore = api.createSession.mock.calls.length
    store.adoptFreshSession()
    expect(store.sessionId).toBe('')
    expect(api.createSession.mock.calls.length).toBe(issuedBefore)
    const nextId = await store.ensureSession()
    expect(nextId).toBe('1003')
    expect(store.sessionId).toBe('1003')
    expect(api.createSession.mock.calls.length).toBe(issuedBefore + 1)

    // ⑦ 任何一次 AI 请求都没带空会话号。
    expect(sentSessionIds.every((id) => typeof id === 'string' && id !== '')).toBe(true)
  })

  it('切回旧身份恢复原会话指针与镜像（不重新发号、不删除旧指针）', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1001')

    store.historyLoaded = true
    store.messages = [messageOf('甲的旧会话')]
    store.writeMirrorNow()

    await store.switchIdentity(8, 81)
    expect(store.sessionId).toBe('1002')
    // 离开身份 7 时保留它的会话指针（返修 I8）。
    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe('1001')
    expect(sessionStorage.getItem('ai-session-id:8:81')).toBe('1002')

    const callsBefore = api.createSession.mock.calls.length
    await store.switchIdentity(7, 71)
    // 切回：恢复原会话号（不再发号），镜像里的旧会话重新可见。
    expect(store.sessionId).toBe('1001')
    expect(api.createSession.mock.calls.length).toBe(callsBefore)
    expect(store.messages.some((message) => message.content.includes('甲的旧会话'))).toBe(true)
    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe('1001')
  })

  it('同租户换用户：后端发新号，旧用户指针与镜像保留，新用户头锁镜像独立', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(store.sessionId).toBe('1001')

    const oldPointerKey = 'ai-session-id:7:71'
    const oldMirrorKey = 'ai-mirror:7:71:1001'
    const oldLockName = sessionLockName('1001')
    store.historyLoaded = true
    store.messages = [messageOf('甲的旧会话')]
    store.writeMirrorNow()
    expect(sessionStorage.getItem(oldMirrorKey)).toContain('甲的旧会话')

    // 同 tenant 8 换用户 72：仍是换身份，必须由后端重新发号。
    await store.switchIdentity(7, 72)
    expect(store.identity).toEqual({ tenantId: 7, userId: 72 })
    expect(api.createSession).toHaveBeenCalledTimes(2)
    expect(store.sessionId).toBe('1002')
    expect(store.messages).toEqual([])

    // 旧用户现场原样保留；新用户指针独立。
    expect(sessionStorage.getItem(oldPointerKey)).toBe('1001')
    expect(sessionStorage.getItem(oldMirrorKey)).toContain('甲的旧会话')
    expect(sessionStorage.getItem('ai-session-id:7:72')).toBe('1002')

    // 请求头、锁名、镜像键全部跟着新用户走，与旧用户不共享。
    expect(identityHeaders()).toEqual({ 'X-Tenant-Id': '7', 'X-User-Id': '72' })
    expect(sessionLockName(store.sessionId)).toBe('ai-session-7:72:1002')
    expect(sessionLockName(store.sessionId)).not.toBe(oldLockName)
    expect(mirrorStorageKey(store.sessionId)).toBe('ai-mirror:7:72:1002')
    expect(sessionStorage.getItem('ai-mirror:7:72:1002')).toBeNull()
  })

  it('19 位会话号经 store 到指针镜像键锁名全链路保持字符串', async () => {
    // 后端 createSession 的真实契约就是**原始文本**（时间戳 ms × 10^6，已越过 2^53）。
    const LONG_SESSION_ID = '1731234567890123456'
    api.createSession.mockReset()
    api.createSession.mockResolvedValue(LONG_SESSION_ID)

    const store = useAiStore()
    await store.loadTenantDirectory()

    expect(store.sessionId).toBe(LONG_SESSION_ID)
    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe(LONG_SESSION_ID)
    // 反证：只要中途经过 number，低位就会被改写（17 位十进制已超出安全整数）。
    expect(String(Number(LONG_SESSION_ID))).not.toBe(LONG_SESSION_ID)

    store.historyLoaded = true
    store.messages = [messageOf('长号会话')]
    store.writeMirrorNow()

    expect(sessionStorage.getItem('ai-session-id:7:71')).toBe(LONG_SESSION_ID)
    expect(mirrorStorageKey(store.sessionId)).toBe(`ai-mirror:7:71:${LONG_SESSION_ID}`)
    expect(sessionLockName(store.sessionId)).toBe(`ai-session-7:71:${LONG_SESSION_ID}`)
    expect(sessionStorage.getItem(`ai-mirror:7:71:${LONG_SESSION_ID}`)).toContain('长号会话')
  })
})
