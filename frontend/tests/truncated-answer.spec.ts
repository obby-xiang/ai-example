/**
 * 截断专项（前端闭环）：模型输出达到长度上限（`finish_reason=length`）时，
 * 实时 `done.truncated` / 历史 `truncated` / 会话镜像三路都要把「这不是完整答案」带到 UI 上。
 *
 * <p>判据：
 * <ol>
 * <li>实时：`done{truncated:true}` ⇒ 当前 assistant 消息 `truncated=true`；正常 stop / 取消不误标；</li>
 * <li>历史：`/history` 条目的 additive `truncated` ⇒ 恢复后的消息保留标记，且<b>不</b>落到段级
 *     截断字段（消息级"长度截断"与段级"字节预算压缩"语义不同）；</li>
 * <li>镜像：标记入 `sessionStorage` 镜像，刷新路径不丢；</li>
 * <li>面板：可见「已截断」徽标 + sr-only 完整说明（zh/en 都走 locale，无 key 原文泄漏）；</li>
 * <li>仍是正常完成的轮次（`done` 已收），不把截断误当错误卡或停止。</li>
 * </ol>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from '@/types/ai'

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

function assistantMessage(id: string, text: string, extra: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id,
    role: 'assistant',
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

function userMessage(id: string, text: string): ChatMessage {
  return {
    id,
    role: 'user',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

/**
 * 新 Pinia + 新 store（**不复位模块注册表**）。
 *
 * <p>`vi.resetModules()` 会让组件动态 import 到另一份 `@/stores/ai` 实例，挂载后的面板读到
 * 另一条用例的 store（本文件早期版本实测过）。这里只换 Pinia：store 状态天然隔离，
 * 且面板/store/locales 始终解析到同一模块注册表。
 */
async function freshStore() {
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
  store.historyLoaded = true
  store.lastRoundOutcome = 'ok'
  return store
}

beforeEach(() => {
  sessionStorage.clear()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.deleteHistory.mockResolvedValue({})
})

afterEach(async () => {
  const { applyLocale } = await import('@/locales')
  applyLocale('zh-CN')
  vi.restoreAllMocks()
})

describe('截断专项 · 实时流：done.truncated ⇒ 消息级标记', () => {
  /** 流式中的宿主（`done:false`）：`handleFrame` 的 currentAssistantMessage 才会认它。 */
  function streamingHost(id: string, text: string): ChatMessage {
    return { ...assistantMessage(id, text), done: false, streaming: true }
  }

  it('done{truncated:true} 给当前 assistant 消息打标，正文保持不变', async () => {
    const store = await freshStore()
    store.sessionId = 's-trunc-stream'
    store.messages = [userMessage('u-1', '写一段长回答'), streamingHost('a-1', '半截回答')]
    store.activeRunId = 'run-trunc'

    store.handleFrame({ type: 'done', runId: 'run-trunc', seq: 9, truncated: true } as never)

    const message = store.messages[1] as ChatMessage
    expect(message.truncated).toBe(true)
    expect(message.content).toBe('半截回答')
    expect(message.stopped).toBeUndefined()
    expect(message.errorCard ?? null).toBeNull()
    expect(message.done).toBe(true)
  })

  it('正常 stop（done 无 truncated）不误标', async () => {
    const store = await freshStore()
    store.sessionId = 's-trunc-stop'
    store.messages = [userMessage('u-1', '问题'), streamingHost('a-1', '完整回答')]
    store.activeRunId = 'run-stop'

    store.handleFrame({ type: 'done', runId: 'run-stop', seq: 9 } as never)

    const message = store.messages[1] as ChatMessage
    expect(message.truncated).toBeUndefined()
    expect(message.stopped).toBeUndefined()
  })

  it('取消轮即便（异常）帧带 truncated 也不当截断：stopped 优先', async () => {
    const store = await freshStore()
    store.sessionId = 's-trunc-cancel'
    store.messages = [userMessage('u-1', '问题'), streamingHost('a-1', '半截回答')]
    store.activeRunId = 'run-cancel'

    store.handleFrame({ type: 'done', runId: 'run-cancel', seq: 9, cancelled: true, truncated: true } as never)

    const message = store.messages[1] as ChatMessage
    expect(message.stopped).toBe(true)
    expect(message.truncated).toBeUndefined()
  })

  it('镜像保留标记（刷新路径不丢）', async () => {
    const store = await freshStore()
    store.sessionId = 's-trunc-mirror'
    store.messages = [userMessage('u-1', '问题'), streamingHost('a-1', '半截回答')]
    store.activeRunId = 'run-mirror'

    store.handleFrame({ type: 'done', runId: 'run-mirror', seq: 9, truncated: true } as never)
    store.writeMirrorNow()

    const raw = sessionStorage.getItem('ai-mirror:s-trunc-mirror')
    expect(raw).toBeTruthy()
    const mirror = JSON.parse(raw as string) as { messages: Array<{ id: string; truncated?: boolean }> }
    expect(mirror.messages.find((message) => message.id === 'a-1')?.truncated).toBe(true)
  })
})

describe('截断专项 · 历史恢复：additive truncated 保留且不与段级截断混淆', () => {
  it('历史 ASSISTANT truncated=true ⇒ 消息级标记，文本段不带 truncated', async () => {
    const store = await freshStore()
    store.sessionId = 's-trunc-history'
    api.history.mockResolvedValue({
      sessionId: 's-trunc-history',
      count: 2,
      messages: [
        { type: 'USER', text: '写一段长回答' },
        { type: 'ASSISTANT', text: '半截回答', truncated: true }
      ]
    })

    await store.loadHistory()

    const assistant = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    expect(assistant.truncated).toBe(true)
    const textPart = assistant.parts.find((part) => part.kind === 'text')
    expect(textPart?.kind === 'text' ? textPart.truncated : 'missing').toBeUndefined()
  })

  it('历史旧载荷（无 truncated 键）⇒ 不产字段（"没说"不等于"没截断"）', async () => {
    const store = await freshStore()
    store.sessionId = 's-trunc-history-old'
    api.history.mockResolvedValue({
      sessionId: 's-trunc-history-old',
      count: 2,
      messages: [
        { type: 'USER', text: '问题' },
        { type: 'ASSISTANT', text: '正常回答' }
      ]
    })

    await store.loadHistory()

    const assistant = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    expect(assistant.truncated).toBeUndefined()
  })
})
