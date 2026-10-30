/**
 * Task 3（2026-10-30 AI 关键稳定性修复）：前端**身份、旧轮与提示**的精确用例。
 *
 * <p>一条用例锁一个生产行为（严格 TDD：先红后绿，逐条单跑，不整文件批量跑）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { reattach } from '@/api/sse'
import { OWNERSHIP_CHANNEL_NAME, RETURN_INTENT_KEY, heldSessionLockName, useAiStore } from '@/stores/ai'
import { formDraftStorageKey, resetAiIdentityCache, sessionLockName, writeAiIdentity } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import { createFakeChat, type FakeChat } from './ai-fake-transport'
import { FakeBroadcastChannel, FakeLockManager } from './tab-ownership-fixtures'
import type { ChatMessage } from '@/types/ai'

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

const OLD = { tenantId: 7, userId: 71 }
const NEW = { tenantId: 8, userId: 81 }
const DIRECTORY = [
  { tenantId: 7, users: [{ userId: 71, displayName: '甲' }] },
  { tenantId: 8, users: [{ userId: 81, displayName: '丙' }] }
]

let fakeChat: FakeChat

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  installIdentityFixture({ sessionId: 7001 })
  api.tenantUsers.mockResolvedValue(DIRECTORY)
  api.createSession.mockResolvedValue('8001')
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: '7001', count: 0, messages: [] })
  api.getContext.mockRejectedValue(new Error('no context'))
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({ cancelled: true })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  fakeChat = createFakeChat()
  api.chat.mockImplementation(fakeChat.impl)
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('Task 3 · 切换身份：旧轮 best-effort 取消', () => {
  it('按旧身份取消在途旧 run、不等待回包，也不重发/重跑工具', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    expect(store.identity).toEqual(OLD)
    expect(store.sessionId).toBe('7001')

    // 旧身份下起一轮在途对话（start 帧确定 runId）。
    const inFlight = store.send('开始')
    await flushPromises()
    fakeChat.last().emit({ type: 'start', runId: 'run-old', sessionId: '7001', seq: 1 })
    fakeChat.last().emit({ type: 'delta', runId: 'run-old', seq: 2, text: '旧回答' })
    await flushPromises()
    expect(store.activeRunId).toBe('run-old')

    // cancel 永不回包：本地切换也**不得**等它。
    const cancelCalls: Array<{ runId: string; headers: Record<string, string> | undefined }> = []
    let releaseCancel: () => void = () => undefined
    // 身份头由 store **在切换入口显式**随请求发出（线上行为见 task3-identity-cancel-headers.spec.ts）。
    api.cancel.mockImplementation((runId: string, config?: { headers?: Record<string, string> }) => {
      cancelCalls.push({ runId, headers: config?.headers })
      return new Promise((resolve) => {
        releaseCancel = () => resolve({ cancelled: true })
      })
    })

    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.identity).toEqual(NEW)
    expect(store.sessionId).toBe('8001')
    expect(store.messages).toEqual([])

    // 取消请求携带**旧身份**的显式身份头（切换入口捕获，不依赖 axios 拦截器当刻读到的身份）。
    expect(cancelCalls).toEqual([{ runId: 'run-old', headers: { 'X-Tenant-Id': '7', 'X-User-Id': '71' } }])
    // 切换不重发对话、不重试模型步、不重跑/回灌工具。
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.retryModelStep).not.toHaveBeenCalled()
    expect(api.frontendToolResult).not.toHaveBeenCalled()

    releaseCancel()
    await inFlight
  })

  it('切换身份清掉旧身份的孤儿表单草稿（keepMirror 不再保留旧身份现场）', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    const orphanKey = formDraftStorageKey('tc-orphan', OLD)
    sessionStorage.setItem(orphanKey, JSON.stringify({ field: '还没提交的值' }))
    expect(sessionStorage.getItem(orphanKey)).not.toBeNull()

    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.sessionId).toBe('8001')
    expect(sessionStorage.getItem(orphanKey)).toBeNull()
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })
})

function assistantMessage(extra: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'a-old',
    role: 'assistant',
    content: '',
    parts: [{ kind: 'text', seg: 0, text: '', streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    runId: 'run-old',
    appliedSeqs: [3],
    ...extra
  }
}

/** 持一张待决确认门卡的助手宿主（HITL 入口按 `toolCallId` 定位它）。 */
function pendingConfirmHost(toolCallId: string): ChatMessage {
  const message = assistantMessage({})
  message.parts = [
    { kind: 'tool', seg: 0, toolCallId, name: 'start_export', kind2: 'CONFIRM', status: 'pending', requiresAction: true }
  ]
  message.pendingCall = { toolCallId, name: 'start_export', kind: 'CONFIRM', args: null, status: 'pending' }
  return message
}

describe('Task 3 · 异步回包的轮次与身份守卫', () => {
  it('旧重试失败：切换身份后回包不得把失败提示/结局写进新身份', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    const message = assistantMessage()
    store.messages = [message]

    let rejectRetry: (error: unknown) => void = () => undefined
    api.retryModelStep.mockImplementation(() => new Promise((_resolve, reject) => {
      rejectRetry = reject
    }))
    const retry = store.retryModelStep(message)
    await flushPromises()
    expect(api.retryModelStep).toHaveBeenCalledWith('run-old')
    expect(store.loading).toBe(true)

    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.sessionId).toBe('8001')
    expect(store.messages).toEqual([])
    const noticeAfterSwitch = store.notice

    // 旧身份那一轮的重试 503 迟到：提示/结局/loading 都不得落到新身份里。
    rejectRetry(new ApiError('upstream down', 503, 'AI_UNAVAILABLE', null))
    await expect(retry).resolves.toBe('denied')
    expect(store.notice).toBe(noticeAfterSwitch)
    expect(store.noticeCode).toBeNull()
    expect(store.lastRoundOutcome).toBe('ok')
    expect(store.loading).toBe(false)
  })

  it('旧 HITL 失败：切换身份后回包不得把失败提示写进新身份', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    store.messages = [pendingConfirmHost('tc-1')]
    store.historyLoaded = true
    store.activeRunId = 'run-old'
    store.suspended = true

    let rejectConfirm: (error: unknown) => void = () => undefined
    api.confirm.mockImplementation(() => new Promise((_resolve, reject) => {
      rejectConfirm = reject
    }))
    const decision = store.confirm(true, undefined, 'tc-1')
    await flushPromises()
    expect(api.confirm).toHaveBeenCalledTimes(1)

    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.sessionId).toBe('8001')
    expect(store.messages).toEqual([])
    expect(store.notice).toBeNull()

    rejectConfirm(new Error('TypeError: cannot read properties of undefined (reading runId)'))
    await decision
    expect(store.notice).toBeNull()
    expect(store.noticeCode).toBeNull()
    expect(store.suspended).toBe(false)
  })

  it('内部错误原文不上屏：HITL 与回灌的非后端错误一律落本地化兜底', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    store.messages = [pendingConfirmHost('tc-1')]
    store.historyLoaded = true
    store.activeRunId = 'run-old'
    store.suspended = true

    api.confirm.mockRejectedValue(new Error('internal confirm detail 42'))
    await store.confirm(true, undefined, 'tc-1')
    expect(store.noticeCode).toBe('ai.notice.decisionFailed')
    expect(store.notice ?? '').not.toContain('internal confirm detail 42')

    api.frontendToolResult.mockRejectedValue(new Error('internal result detail 7'))
    const outcome = await store.submitFrontendToolResult('tc-1', '{"ok":true}')
    expect(outcome.ok).toBe(false)
    expect(store.noticeCode).toBe('ai.notice.toolSubmitFailed')
    expect(store.notice ?? '').not.toContain('internal result detail 7')
    expect(outcome.message ?? '').not.toContain('internal result detail 7')
  })

  it('旧回灌失败：切换身份后回包不得把失败提示写进新身份', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    store.messages = [pendingConfirmHost('tc-1')]
    store.historyLoaded = true
    store.activeRunId = 'run-old'

    let rejectResult: (error: unknown) => void = () => undefined
    api.frontendToolResult.mockImplementation(() => new Promise((_resolve, reject) => {
      rejectResult = reject
    }))
    const submission = store.submitFrontendToolResult('tc-1', '{"ok":true}')
    await flushPromises()
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)

    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.sessionId).toBe('8001')
    expect(store.notice).toBeNull()

    rejectResult(new ApiError('upstream down', 503, 'AI_UNAVAILABLE', null))
    await expect(submission).resolves.toMatchObject({ ok: false })
    expect(store.notice).toBeNull()
    expect(store.noticeCode).toBeNull()
  })
})

describe('Task 3 · 发号与停止的落地归属', () => {
  it('发号重试成功（复制页签）：统一补所有权认领，本页签拿到新会话的锁', async () => {
    const locks = new FakeLockManager()
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    // 原页签持着继承来的会话锁 ⇒ 本页签被判为复制品，随后自行发号。
    locks.hold(sessionLockName('7001', OLD))
    api.createSession.mockReset()
    api.createSession
      .mockRejectedValueOnce(new ApiError('redis down', 503, 'AI_REDIS_UNAVAILABLE', null))
      .mockResolvedValue('9001')

    const store = useAiStore()
    await store.ensureTabOwnership()
    await flushPromises()
    expect(store.tabOwnership).toBe('duplicate')
    expect(store.sessionId).toBe('')
    expect(store.sessionIssueFailed).toBe(true)

    // 横幅重试：发号成功 ⇒ 必须补上所有权认领（不能停在 duplicate / 无锁）。
    await store.retryIdentitySession()
    expect(store.sessionId).toBe('9001')
    expect(store.tabOwnership).toBe('owner')
    expect(heldSessionLockName()).toBe(sessionLockName('9001', OLD))
    expect(locks.isHeld(sessionLockName('9001', OLD))).toBe(true)
  })

  it('切换身份：新会话只认领一次（不再由 ensureSession 与 enterIdentity 各探一次）', async () => {
    const locks = new FakeLockManager()
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    const store = useAiStore()
    // 先让本页签按 owner 起跑（旧会话自己有锁）。
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')

    const claims: Array<Record<string, unknown>> = []
    const observer = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    observer.onmessage = (event) => {
      claims.push(event.data as Record<string, unknown>)
    }

    await store.switchIdentity(NEW.tenantId, NEW.userId)
    await flushPromises()
    expect(store.sessionId).toBe('8001')
    expect(store.tabOwnership).toBe('owner')
    expect(claims.filter((message) => message.kind === 'claim' && message.sessionId === '8001')).toHaveLength(1)
  })

  it('释放旧会话锁按操作入口捕获的身份：全局身份漂移不得漏放/错放', async () => {
    const locks = new FakeLockManager()
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')
    expect(locks.isHeld(sessionLockName('7001', OLD))).toBe(true)

    // 全局身份事实源被别处改写（缓存/存储漂移），本页签会话仍属于 7:71。
    writeAiIdentity(NEW)
    store.adoptFreshSession()
    await flushPromises()

    // 释放必须命中旧身份那把锁（否则它一直占着到文档销毁）。
    expect(locks.isHeld(sessionLockName('7001', OLD))).toBe(false)
  })

  it('返回意向按操作入口捕获的身份落盘：全局身份漂移不得写成新身份', async () => {
    const locks = new FakeLockManager()
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')

    writeAiIdentity(NEW)
    window.dispatchEvent(new Event('pagehide'))

    const raw = sessionStorage.getItem(RETURN_INTENT_KEY)
    expect(raw).not.toBeNull()
    expect(JSON.parse(raw!)).toMatchObject({ sessionId: '7001', fingerprint: '7:71' })
  })

  it('无 Web Locks：发号成功不得冒充 owner（保持 unsupported）', async () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    const store = useAiStore()
    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.sessionId).toBe('8001')
    expect(store.tabOwnership).toBe('unsupported')
  })

  it('连续停止：前一次停止的 finally 只清自己的闸门，不抹掉后一次的进行态', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    store.historyLoaded = true

    const releases: Array<() => void> = []
    api.cancel.mockImplementation(() => new Promise((resolve) => {
      releases.push(() => resolve({ cancelled: true }))
    }))

    // 第一次停止（run-1）：cancel 尚未落地。
    store.activeRunId = 'run-1'
    store.loading = true
    const first = store.stop()
    await flushPromises()
    expect(store.stopping).toBe(true)
    expect(api.cancel).toHaveBeenCalledTimes(1)

    // 停止在途时换会话（新建对话 / 切身份都会走这里）——旧的 stopping 被本地复位。
    store.adoptFreshSession()
    expect(store.stopping).toBe(false)

    // 第二次停止（新会话的 run-2）：新闸门。
    store.activeRunId = 'run-2'
    store.loading = true
    const second = store.stop()
    await flushPromises()
    expect(store.stopping).toBe(true)
    expect(api.cancel).toHaveBeenCalledTimes(2)

    // 第一次停止的 cancel 迟到：只清它自己那次（不得把第二次的进行态/闸门抹掉）。
    releases[0]?.()
    await first
    expect(store.stopping).toBe(true)

    // 发送必须等**第二次**停止落地：闸门仍归第二次所有。
    api.createSession.mockResolvedValue('9002')
    const send = store.send('新消息')
    await flushPromises()
    expect(api.chat).not.toHaveBeenCalled()

    releases[1]?.()
    await second
    await flushPromises()
    expect(api.chat).toHaveBeenCalledTimes(1)
    fakeChat.last().close({ terminal: true })
    await send
  })

  it('停止的 cancel 迟到：换身份后不得把新身份的轮次结局改写成 cancelled', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    store.historyLoaded = true
    store.activeRunId = 'run-1'
    store.loading = true

    const releases: Array<(value: unknown) => void> = []
    api.cancel.mockImplementation(() => new Promise((resolve) => {
      releases.push(resolve)
    }))
    const stopping = store.stop()
    await flushPromises()
    expect(releases.length).toBe(1)

    // 停止在途时换身份（新会话/新轮次现场；adopt 会把结局复位成 'ok'）。
    await store.switchIdentity(NEW.tenantId, NEW.userId)
    expect(store.lastRoundOutcome).toBe('ok')

    // 旧轮的 cancel 迟到且回报 alreadyCancelled：不得把新身份的结局改成 cancelled。
    releases[0]?.({ cancelled: true, alreadyCancelled: true })
    await stopping
    expect(store.lastRoundOutcome).toBe('ok')
  })
})

describe('Task 3 · 恢复挂起轮', () => {
  it('恢复挂起轮复用 findInFlightHost：没有本轮宿主时新建，不污染上一轮的回答', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    api.history.mockResolvedValue({
      sessionId: '7001',
      count: 3,
      messages: [
        { type: 'USER', text: '第一问' },
        { type: 'ASSISTANT', text: '第一答' },
        { type: 'USER', text: '第二问' }
      ]
    })
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-2',
      snapshotStatus: 'SUSPENDED',
      // 同批里请求帧还没轮到的条目：不建卡（不出现新挂起卡），但仍要挂流收后续帧。
      pendingEntries: [{ toolCallId: 'tc-late', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', req: false }],
      unresolvedExternal: [],
      awaitingExternal: true,
      archiveMaxSeq: 4
    })
    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: '7001', seq: 1 })
      handlers.onFrame({ type: 'delta', runId, seq: 2, text: '第二答' })
      return new Promise(() => {})
    })

    await store.loadHistory()
    await flushPromises()

    // 上一轮回答原样保留（正文/收尾态都不被本轮帧改写），本轮回答落在自己的宿主上。
    expect(store.messages.map((item) => `${item.role}:${item.content}`)).toEqual([
      'user:第一问',
      'assistant:第一答',
      'user:第二问',
      'assistant:第二答'
    ])
    const first = store.messages.find((item) => item.content === '第一答')
    expect(first?.done).toBe(true)
    expect(first?.streaming).toBe(false)
  })

  it('ownership 广播固定捕获会话身份：全局身份漂移不改写 claim 的身份', async () => {
    const locks = new FakeLockManager()
    Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(store.tabOwnership).toBe('owner')

    const seen: Array<Record<string, unknown>> = []
    const observer = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    observer.onmessage = (event) => {
      seen.push(event.data as Record<string, unknown>)
    }

    // 身份事实源被别处改写（另一个入口只写了 storage/缓存），而会话 7001 仍属于 7:71。
    writeAiIdentity(NEW)
    await store.claimSessionOwnership('7001')

    const claim = seen.filter((message) => message.kind === 'claim').at(-1)
    expect(claim).toMatchObject({ sessionId: '7001', tenantId: 7, userId: 71 })
  })
})

describe('Task 3 · 归档裁剪只认后端明确状态', () => {
  it('续收首帧正常跳号（多实例发号）不得误报归档裁剪缺口', async () => {
    const store = useAiStore()
    await store.loadTenantDirectory()
    const host = assistantMessage({
      id: 'a-host',
      runId: 'run-9',
      done: false,
      streaming: true,
      appliedSeqs: []
    })
    store.messages = [{ ...assistantMessage({ id: 'u-1', role: 'user', content: '第二问', runId: undefined }) }, host]
    store.historyLoaded = true
    store.activeRunId = 'run-9'

    api.reattachRun.mockImplementation((runId: string, handlers: { onFrame: (frame: unknown) => void }) => {
      // 锚点 lastSeq=3，首帧 seq=7：多实例发号允许的正常跳号，不是"归档被裁掉"的证据。
      handlers.onFrame({ type: 'delta', runId, seq: 7, text: '续写' })
      return new Promise(() => {})
    })
    void store.resumeSnapshotStream('run-9', 3, 'a-host')
    await flushPromises()

    expect(host.content).toContain('续写')
    expect(store.recoveryGap).toBeNull()
    expect(host.recoveryGap).toBeUndefined()
  })

  it('reattach 响应头明确标记归档裁剪：经 onArchiveTrim 通报一次（心跳不参与判定）', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        // 首块是 SSE 注释/心跳：它不得消耗任何"检查位"（判定只认响应头）。
        controller.enqueue(encoder.encode(': heartbeat\n\n'))
        controller.enqueue(encoder.encode('data: {"type":"delta","runId":"run-h","seq":9,"text":"后半段"}\n\n'))
        controller.enqueue(encoder.encode('data: {"type":"done","runId":"run-h","seq":10,"cancelled":false}\n\n'))
        controller.close()
      }
    })
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, {
      status: 200,
      headers: {
        'content-type': 'text/event-stream',
        'archive-trimmed': 'true',
        'archive-from': '6',
        'archive-to': '8'
      }
    })))

    const onArchiveTrim = vi.fn()
    const info = await reattach('run-h', { onArchiveTrim, onFrame: () => undefined })

    expect(info.terminal).toBe(true)
    expect(onArchiveTrim).toHaveBeenCalledTimes(1)
    expect(onArchiveTrim).toHaveBeenCalledWith({ fromSeq: 6, toSeq: 8 })
  })
})
