/**
 * 批次7B（P1⑤ / AR-15）：会话镜像从「每次全量 deep stringify」改为**增量 + 有界 + idle 落地**。
 *
 * <p>本文件是 store 层回归锁（mock 掉 HTTP，不挂载面板），只锁四件用户可观察的事：
 * <ol>
 * <li><b>有界</b>：超过上限只保最近消息（最新一条必在，旧消息由服务端完整历史补齐）；</li>
 * <li><b>不重复写</b>：现场没变时第二次落盘是 no-op（`savedAt` 不变、不产生第二次 setItem）；</li>
 * <li><b>配额失败一次性可见</b>：写不进去时落一次通知；连续失败不刷屏，恢复成功后再次失败可再提示；</li>
 * <li><b>兼容</b>：v2 版本号、`parts` + 派生投影双写、`errorCard`/`code`/`runId`/思考 timing 一个不少，
 *    运行时锚 `lastFrameLocalAt` 仍按 AR-12 剔除。</li>
 * </ol>
 *
 * 注：本批验证边界 = 仅 `tool-part-derived-cache.spec.ts` 的一个聚焦方法实跑 RED→GREEN；
 * 本文件按施工要求**只写不跑**，未作为通过证据。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { MIRROR_MAX_MESSAGES, MIRROR_PREFIX, useAiStore } from '@/stores/ai'
import type { ChatMessage, MessagePart } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  getContext: vi.fn(),
  retryModelStep: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const MIRROR_QUOTA_NOTICE = 'ai.notice.mirrorQuotaExceeded'

function textMessage(id: string, role: ChatMessage['role'], text: string, extra: Partial<ChatMessage> = {}): ChatMessage {
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

function freshStore() {
  setActivePinia(createPinia())
  return useAiStore()
}

function mirrorOf(sessionId: string): {
  v?: number
  savedAt?: number
  lastRunId?: string | null
  inFlight?: boolean
  messages: Array<Record<string, unknown>>
} | null {
  const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
  return raw ? JSON.parse(raw) : null
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
})

afterEach(() => {
  vi.useRealTimers()
  delete (window as unknown as { requestIdleCallback?: unknown }).requestIdleCallback
  delete (window as unknown as { cancelIdleCallback?: unknown }).cancelIdleCallback
})

describe('批次7B · 镜像有界与不重复写', () => {
  it('超过消息上限只保最近 N 条：最新一条必在，最旧的被裁剪，v/runId 等信封键不变', () => {
    const store = freshStore()
    store.sessionId = 'sess-bounded'
    store.historyLoaded = true
    store.activeRunId = 'run-bounded'
    store.messages = Array.from({ length: MIRROR_MAX_MESSAGES + 10 }, (_, index) =>
      textMessage(`m-${index}`, index % 2 === 0 ? 'user' : 'assistant', `第 ${index} 条`)
    )

    store.writeMirrorNow()

    const mirror = mirrorOf('sess-bounded')
    expect(mirror?.v).toBe(2)
    expect(mirror?.lastRunId).toBe('run-bounded')
    expect(mirror?.messages).toHaveLength(MIRROR_MAX_MESSAGES)
    expect(mirror?.messages[0]?.id).toBe('m-10')
    expect(mirror?.messages.at(-1)?.id).toBe(`m-${MIRROR_MAX_MESSAGES + 9}`)
  })

  it('现场没变时第二次落盘不写：第二次 setItem 不发生、savedAt 不变（幂等，不是重复写）', () => {
    const store = freshStore()
    store.sessionId = 'sess-dedupe'
    store.historyLoaded = true
    store.activeRunId = 'run-dedupe'
    store.messages = [textMessage('u-1', 'user', '问'), textMessage('a-1', 'assistant', '答')]

    const key = MIRROR_PREFIX + 'sess-dedupe'
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    try {
      store.writeMirrorNow()
      const first = mirrorOf('sess-dedupe')
      expect(first?.messages).toHaveLength(2)
      expect(setItem.mock.calls.filter(([k]) => k === key)).toHaveLength(1)

      store.writeMirrorNow()
      const second = mirrorOf('sess-dedupe')
      expect(setItem.mock.calls.filter(([k]) => k === key)).toHaveLength(1)
      expect(second?.savedAt).toBe(first?.savedAt)
    } finally {
      setItem.mockRestore()
    }
  })

  it('trailing 写盘走 idle：窗口内的改动先合并，idle 回调执行后才落盘（不占渲染帧、不重复写）', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(10_000_000)
    const idleCallbacks: Array<() => void> = []
    ;(window as unknown as { requestIdleCallback: (cb: () => void) => number }).requestIdleCallback = (cb) => {
      idleCallbacks.push(cb)
      return idleCallbacks.length
    }
    ;(window as unknown as { cancelIdleCallback: (handle: number) => void }).cancelIdleCallback = () => undefined

    const store = freshStore()
    store.sessionId = 'sess-idle'
    store.historyLoaded = true
    store.messages = [textMessage('u-1', 'user', '问'), textMessage('a-1', 'assistant', '答')]
    // 先落一次盘，把节流锚定在"现在"（否则首个 state 变化会走 leading 立即写）。
    store.writeMirrorNow()
    store.startMirror()

    vi.setSystemTime(10_000_100)
    store.messages[1]!.content = '答（续）'
    store.messages[1]!.parts = [{ kind: 'text', seg: 0, text: '答（续）', streaming: false }]
    await nextTick()
    expect(idleCallbacks).toHaveLength(0) // 还在 300ms 窗口内：先合并，不排 idle

    vi.advanceTimersByTime(200)
    expect(idleCallbacks).toHaveLength(1) // 窗口用尽：排到 idle，尚未写
    expect(mirrorOf('sess-idle')?.messages[1]?.content).toBe('答')

    idleCallbacks[0]?.()
    expect(mirrorOf('sess-idle')?.messages[1]?.content).toBe('答（续）')
  })
})

describe('批次7B · 配额失败一次性可见', () => {
  it('写盘失败落一次通知；连续失败不刷屏；成功写盘后再次失败可重新提示', () => {
    const store = freshStore()
    store.sessionId = 'sess-quota'
    store.historyLoaded = true
    store.messages = [textMessage('u-1', 'user', '问'), textMessage('a-1', 'assistant', '半截答')]

    const original = Storage.prototype.setItem
    const quotaSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      value: string
    ) {
      if (key.startsWith(MIRROR_PREFIX)) {
        throw new DOMException('quota exceeded', 'QuotaExceededError')
      }
      return original.call(this, key, value)
    })
    try {
      store.writeMirrorNow()
      expect(store.noticeCode).toBe(MIRROR_QUOTA_NOTICE)
      expect(store.noticeSeverity).toBe('warning')

      // 用户清掉通知后，同一持续故障不再重复报（一次性）。
      store.notice = null
      store.noticeCode = null
      store.noticeArgs = null
      store.writeMirrorNow()
      expect(store.noticeCode).toBeNull()

      // 恢复成功 ⇒ 故障解除；再次失败属于新的故障段，允许再提示一次。
      quotaSpy.mockRestore()
      store.writeMirrorNow()
      expect(mirrorOf('sess-quota')?.messages).toHaveLength(2)
      store.notice = null
      store.noticeCode = null
      store.noticeArgs = null

      const again = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
        this: Storage,
        key: string,
        value: string
      ) {
        if (key.startsWith(MIRROR_PREFIX)) {
          throw new DOMException('quota exceeded', 'QuotaExceededError')
        }
        return original.call(this, key, value)
      })
      store.messages[1]!.content = '半截答（又长了一点）'
      store.messages[1]!.parts = [{ kind: 'text', seg: 0, text: '半截答（又长了一点）', streaming: false }]
      store.writeMirrorNow()
      expect(store.noticeCode).toBe(MIRROR_QUOTA_NOTICE)
      again.mockRestore()
    } finally {
      vi.restoreAllMocks()
    }
  })
})

describe('批次7B · 新字段与旧镜像兼容', () => {
  it('v2 双写保留：parts + content/toolRuns，errorCard/code/runId 在，思考 timing 在、本地锚被剔除', () => {
    const store = freshStore()
    store.sessionId = 'sess-fields'
    store.historyLoaded = true
    store.activeRunId = 'run-fields'

    const reasoning: MessagePart = {
      kind: 'reasoning',
      seg: 0,
      text: '先想一想',
      streaming: false,
      startedAt: 1000,
      endedAt: 2000,
      lastFrameAt: 2000,
      lastFrameLocalAt: 9999
    }
    const tool: MessagePart = {
      kind: 'tool',
      seg: 1,
      toolCallId: 'tc-1',
      name: 'get_row_count',
      kind2: 'BACKEND',
      status: 'succeeded',
      args: '{"defCode":"CUR"}',
      result: '42',
      requiresAction: false
    }
    store.messages = [
      textMessage('a-1', 'assistant', '答', {
        runId: 'run-fields',
        errorCard: {
          variant: 'generic',
          title: '出错了',
          summary: '这一轮失败了',
          detail: 'boom',
          guide: '',
          action: '',
          code: 'EMPTY_REPLY',
          baseCode: 'EMPTY_REPLY',
          derived: null
        },
        code: 'EMPTY_REPLY',
        unconfirmed: true,
        synthetic: true,
        recoveryGap: true,
        stopped: true,
        truncated: true,
        parts: [reasoning, tool]
      })
    ]

    store.writeMirrorNow()

    const mirror = mirrorOf('sess-fields')
    expect(mirror?.v).toBe(2)
    expect(mirror?.lastRunId).toBe('run-fields')
    const saved = mirror?.messages[0] as Record<string, unknown>
    expect(saved.runId).toBe('run-fields')
    expect(saved.code).toBe('EMPTY_REPLY')
    expect((saved.errorCard as { code?: string } | undefined)?.code).toBe('EMPTY_REPLY')
    expect(saved.unconfirmed).toBe(true)
    expect(saved.synthetic).toBe(true)
    expect(saved.recoveryGap).toBe(true)
    expect(saved.stopped).toBe(true)
    expect(saved.truncated).toBe(true)
    // 双写：段是唯一真值，content/toolRuns 投影同时保留（旧读点零改动）。
    expect(saved.content).toBe('答')
    expect(saved.toolRuns).toEqual([])
    const savedReasoning = (saved.parts as MessagePart[])[0] as Record<string, unknown>
    expect(savedReasoning.startedAt).toBe(1000)
    expect(savedReasoning.endedAt).toBe(2000)
    expect(savedReasoning.lastFrameAt).toBe(2000)
    expect('lastFrameLocalAt' in savedReasoning).toBe(false)
    const savedTool = (saved.parts as MessagePart[])[1] as Record<string, unknown>
    expect(savedTool.toolCallId).toBe('tc-1')
    expect(savedTool.args).toBe('{"defCode":"CUR"}')
  })
})
