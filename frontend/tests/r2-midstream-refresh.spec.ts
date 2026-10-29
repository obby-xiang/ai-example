/**
 * 批G · R2（真机回归）：流式中刷新「第 1 轮回答被吞 / 第 2 轮回答排到第 2 轮提问之前」的两条根治锁。
 *
 * <p>
 * 实证现象（老会话第 2 轮流式中刷新，对照「轮次结束后刷新」正常）：刷新后只剩
 * `[user1, assistant(第 2 轮内容), user2]` —— 第 1 轮回答被本轮帧覆盖，且回答落在提问之前。
 *
 * <p>
 * 两条根因（本文件各锁一条）：
 * <ol>
 * <li><b>启动期空镜像覆盖竞态</b>：`startMirror()` 装订阅后，`ensureTabOwnership()` 的**任何**
 *     state 变更（真机打点顺序 `SET n=0 → GET n=0 → SET n=3`）都会以此刻的 `messages=[]` 落盘，
 *     把上一页签留下的持久化镜像清空；随后 `loadHistory()` 的 `readMirror` 读到空镜像，
 *     G3 合并空转。</li>
 * <li><b>宿主回落抢上一轮</b>：`restoreInFlightRound` 在没有未收尾助手消息时回落到「最后一条
 *     助手消息」，清其段后 `lastSeq=0` 全量重放到它身上 —— 于是历史里上一轮的回答被本轮覆盖，
 *     消息位置留在上一轮提问之后、本轮提问之前。</li>
 * </ol>
 *
 * <p>
 * 断言口径都是**用户看到的形态**（消息角色/正文顺序），不锁实现细节：修 1 之后宿主必是本轮那条，
 * 修 2 之后"找不到本轮宿主"只允许新建（绝不触碰已收尾的上一轮）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import type { ChatMessage } from '@/types/ai'
import { mirrorStorageKey } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

/** 与 store 同源的镜像键（`mirrorStorageKey`；身份模式下带 `tenant:user` 指纹）。 */
const SESSION = 's-r2'

interface MirrorFixture {
  messages: Array<{ id: string; role: string; content: string }>
  inFlight?: boolean
  runSeq?: Record<string, number>
  lastRunId?: string | null
}

function mirrorOf(sessionId = SESSION): MirrorFixture | null {
  const raw = sessionStorage.getItem(mirrorStorageKey(sessionId))
  return raw ? (JSON.parse(raw) as MirrorFixture) : null
}

function textMessage(
  id: string,
  role: ChatMessage['role'],
  text: string,
  extra: Record<string, unknown> = {}
): Record<string, unknown> {
  return {
    id,
    role,
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...extra
  }
}

function snapshotOf(overrides: Record<string, unknown> = {}) {
  return {
    found: true,
    runId: 'run-2',
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 5,
    ...overrides
  }
}

/** 静微任务队列：pinia 的 `$subscribe`（flush: pre）在微任务里跑，断言前必须让它落地。 */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

/**
 * 每例一个全新的 store（同 `rg-round-restore-stop` 体例）：`resetModules` 之后的 store 模块与
 * 静态 import 的 pinia 属于不同图，故在 reset 后的图里重建 pinia，并显式把轮次级状态归初值。
 */
async function freshStore() {
  vi.resetModules()
  const pinia = await import('pinia')
  pinia.setActivePinia(pinia.createPinia())
  const mod = await import('@/stores/ai')
  const store = mod.useAiStore()
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.activeRunId = null
  store.runSeq = {}
  store.historyLoaded = false
  store.lastRoundOutcome = 'ok'
  return store
}

/** 第 2 轮在途：重放两帧（start + delta），连接保持挂着（不触发轮次复位）。 */
function replaySecondRound(text: string): void {
  api.reattachRun.mockImplementation(
    (runId: string, handlers: { onFrame: (incoming: unknown) => void }) => {
      handlers.onFrame({ type: 'start', runId, sessionId: SESSION, seq: 1 })
      handlers.onFrame({ type: 'delta', runId, text, seq: 2 })
      return new Promise(() => {})
    }
  )
}

/** 后端历史：老会话两轮，第 2 轮只落了用户消息（开轮即写 ChatMemory，实证口径）。 */
function twoRoundsHistory() {
  return {
    sessionId: SESSION,
    count: 3,
    messages: [
      { type: 'USER', text: '第一问' },
      { type: 'ASSISTANT', text: '第一答' },
      { type: 'USER', text: '第二问' }
    ]
  }
}

beforeEach(() => {
  sessionStorage.clear()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: SESSION, count: 0, messages: [] })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.runsCurrent.mockResolvedValue({
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  })
  installIdentityFixture()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('R2 修 1：启动期不得用空态覆盖持久化镜像', () => {
  it('historyLoaded 之前的空 state 变更（如 ensureTabOwnership 写 tabOwnership）不落盘空镜像', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    sessionStorage.setItem(
      mirrorStorageKey(SESSION),
      JSON.stringify({
        v: 2,
        savedAt: 1,
        lastRunId: 'run-2',
        inFlight: true,
        runSeq: { 'run-2': 6 },
        messages: [
          textMessage('u-2', 'user', '第二问'),
          textMessage('a-2', 'assistant', '第二答写到一半', {
            streaming: true,
            done: false,
            parts: [{ kind: 'text', seg: 0, text: '第二答写到一半', streaming: true }]
          })
        ]
      })
    )

    // 真机 onMounted 次序：startMirror() → ensureTabOwnership()（state 变更）→ loadHistory()
    store.startMirror()
    store.tabOwnership = 'owner'
    await flush()

    const mirror = mirrorOf()
    expect(mirror?.messages.map((message) => message.id)).toEqual(['u-2', 'a-2'])
    expect(mirror?.inFlight).toBe(true)
    expect(mirror?.lastRunId).toBe('run-2')
  }, 20_000)

  it('镜像被保住之后，loadHistory 的 G3 合并拿得到在途轮（readMirror 读到非空）', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    sessionStorage.setItem(
      mirrorStorageKey(SESSION),
      JSON.stringify({
        v: 2,
        savedAt: 1,
        lastRunId: 'run-2',
        inFlight: true,
        runSeq: { 'run-2': 6 },
        messages: [
          textMessage('u-2', 'user', '第二问'),
          textMessage('a-2', 'assistant', '第二答写到一半', {
            streaming: true,
            done: false,
            parts: [{ kind: 'text', seg: 0, text: '第二答写到一半', streaming: true }]
          })
        ]
      })
    )
    api.history.mockResolvedValue(twoRoundsHistory())
    api.runsCurrent.mockResolvedValue(snapshotOf())
    replaySecondRound('第二答')

    store.startMirror()
    store.tabOwnership = 'owner'
    await flush()
    await store.loadHistory()
    await flush()

    // 两轮俱在、顺序正确，镜像那条被续写（无新气泡）
    expect(store.messages.map((message) => `${message.role}:${message.content}`)).toEqual([
      'user:第一问',
      'assistant:第一答',
      'user:第二问',
      'assistant:第二答'
    ])
  })
})

describe('R2 修 2：在途轮的宿主只认本轮，绝不回落上一轮', () => {
  it('镜像被清空（老实现现场）时第 2 轮刷新：第 1 轮回答不被覆盖、回答排在第二问之后', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    // 报实现场：启动期空写把镜像清成空 / 读不到镜像
    api.history.mockResolvedValue(twoRoundsHistory())
    api.runsCurrent.mockResolvedValue(snapshotOf())
    replaySecondRound('第二答')

    await store.loadHistory()
    await flush()

    expect(store.messages.map((message) => `${message.role}:${message.content}`)).toEqual([
      'user:第一问',
      'assistant:第一答',
      'user:第二问',
      'assistant:第二答'
    ])
    const assistants = store.messages.filter((message) => message.role === 'assistant')
    expect(assistants).toHaveLength(2)
    expect(assistants[0]?.id).toBe('history-1') // 第 1 轮那条恒等未动
  })

  it('镜像在途但末条只有用户消息（第 2 轮刚开、助手正文尚未写出）：新起宿主，不拿上一轮顶替', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    // 真机形态：send() 一落用户气泡就写镜像（leading 立即写），此刻还没有助手消息
    sessionStorage.setItem(
      mirrorStorageKey(SESSION),
      JSON.stringify({
        v: 2,
        savedAt: 1,
        lastRunId: 'run-2',
        inFlight: true,
        runSeq: {},
        messages: [
          textMessage('u-1', 'user', '第一问'),
          textMessage('a-1', 'assistant', '第一答'),
          textMessage('u-2', 'user', '第二问')
        ]
      })
    )
    api.history.mockResolvedValue(twoRoundsHistory())
    api.runsCurrent.mockResolvedValue(snapshotOf())
    replaySecondRound('第二答')

    await store.loadHistory()
    await flush()

    expect(store.messages.map((message) => `${message.role}:${message.content}`)).toEqual([
      'user:第一问',
      'assistant:第一答',
      'user:第二问',
      'assistant:第二答'
    ])
    // 上一轮的宿主体必须原样保留（未被清段、未被重放覆盖）
    expect(store.messages[1]?.id).toBe('history-1')
    expect(store.messages[1]?.parts.some((part) => part.kind === 'text' && part.text === '第一答')).toBe(true)
  })

  it('历史整体为空但镜像有在途轮（后端重启/TTL 过期）：仍续写镜像那条，不新起重复气泡', async () => {
    const store = await freshStore()
    store.sessionId = SESSION
    sessionStorage.setItem(
      mirrorStorageKey(SESSION),
      JSON.stringify({
        v: 2,
        savedAt: 1,
        lastRunId: 'run-2',
        inFlight: true,
        runSeq: {},
        messages: [
          textMessage('u-2', 'user', '第二问'),
          textMessage('a-2', 'assistant', '写到一半', {
            streaming: true,
            done: false,
            parts: [{ kind: 'text', seg: 0, text: '写到一半', streaming: true }]
          })
        ]
      })
    )
    api.history.mockResolvedValue({ sessionId: SESSION, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(snapshotOf())
    replaySecondRound('第二答')

    await store.loadHistory()
    await flush()

    expect(store.messages.map((message) => `${message.role}:${message.content}`)).toEqual([
      'user:第二问',
      'assistant:第二答'
    ])
    expect(store.messages[1]?.id).toBe('a-2')
  })
})
