/**
 * B1（2026-10-20）挂起即断 SSE：前端侧回归锁。
 *
 * 语义（设计稿 `docs/research/设计-B1挂起即断SSE-2026-10-20.md` 裁决 8/9/10）：
 * - 卡面请求帧（`confirm_request` / `frontend_tool_request`）之后服务端**主动收流**，
 *   前端把它当**正常收尾**（不判断流、不报「结果可能不完整」、不自动重连）；
 * - 挂起收流时 `loading` 复位、`suspended` 保持为真（卡还在等人）→ 停止按钮仍可见；
 * - 用户决策（批准/拒绝/GF 提交/取消）POST 成功后，以 `runSeq[runId]` 为 lastSeq
 *   走既有 `resumeSnapshotStream` 重连收流（续写原宿主消息，不新起气泡）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { installIdentityFixture } from './ai-identity-fixture'
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

const frontendTools = vi.hoisted(() => ({ executeFrontendTool: vi.fn() }))
vi.mock('@/utils/frontend-tools', async (importOriginal) => ({
  ...(await importOriginal<object>()) as object,
  executeFrontendTool: frontendTools.executeFrontendTool
}))

const stubs = {
  'el-alert': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-empty': true,
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': true,
  'el-skeleton': true,
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  FormRenderer: true
}

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

/** 一轮"挂起收流"的帧序列：start(1) → confirm_request(2)，随后连接被服务端收尾。 */
function emitSuspendThenClose(): { terminal: boolean; suspended?: boolean; reconnects: number; reason?: string } {
  return {
    terminal: false,
    suspended: true,
    reconnects: 0,
    reason: '挂起等待（服务端已主动收流，决策后带 lastSeq 重连）'
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  frontendTools.executeFrontendTool.mockReset()
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  // 重连连接保持"在跑"（永不结算）：断言只关心"有没有带着 lastSeq 发起"
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
  api.confirm.mockResolvedValue({ accepted: true, duplicate: false, runId: 'run-1', toolCallId: 'c1' })
  api.frontendToolResult.mockResolvedValue({ accepted: true, duplicate: false, runId: 'run-1', toolCallId: 'gf-1' })
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('B1 裁决 8：挂起收流 = 正常收尾（不判断流）', () => {
  it('confirm_request 之后服务端收流：不置 streamInterrupted、不出断流提示、loading 复位、suspended 保留', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; suspended?: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'confirm_request',
        runId: 'run-1',
        toolCallId: 'c1',
        name: 'start_export',
        args: '{"taskId":7}',
        seq: 2
      })
      handlers?.onClose?.(emitSuspendThenClose())
    })

    const result = await store.send('导出')

    expect(result.outcome).toBe('ok') // 正常收尾，不是 interrupted
    expect(store.streamInterrupted).toBe(false)
    expect(store.lastRoundOutcome).toBe('ok')
    expect(store.notice).toBeNull()
    expect(store.loading).toBe(false) // 挂起期不占「工作中」态
    expect(store.suspended).toBe(true) // 卡还在等人
    // 本轮尚未结束：气泡保持流式态（终帧到达时才封口）
    const assistant = store.messages.find((message) => message.role === 'assistant') as ChatMessage
    expect(assistant.done).toBe(false)
    expect(store.pendingToolCall?.toolCallId).toBe('c1')
    expect(store.runSeq['run-1']).toBe(2)
  }, 20_000)

  it('普通断流（无挂起）仍按 ADR-6 报「结果可能不完整」（负控制）', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onClose?.({ terminal: false, reconnects: 0, reason: '网络断了' })
    })

    const result = await store.send('查一下')
    expect(result.outcome).toBe('interrupted')
    expect(store.streamInterrupted).toBe(true)
    expect(store.notice).toContain('结果可能不完整')
  }, 20_000)
})

describe('B1 裁决 9：决策 POST 成功后带 lastSeq 重连收流', () => {
  async function suspendedStore() {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; suspended?: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'confirm_request',
        runId: 'run-1',
        toolCallId: 'c1',
        name: 'start_export',
        args: '{"taskId":7}',
        seq: 5
      })
      handlers?.onClose?.(emitSuspendThenClose())
    })
    await store.send('导出')
    return store
  }

  it('confirm() 成功 → 以 runSeq 锚点重连（续写原宿主消息，不新起气泡）', async () => {
    const store = await suspendedStore()
    const before = store.messages.length
    expect(store.suspended).toBe(true)

    await store.confirm(true, undefined, 'c1')

    expect(api.confirm).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-1', toolCallId: 'c1', approved: true }))
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    const [runId, , options] = api.reattachRun.mock.calls[0] as [string, unknown, { lastSeq?: number }]
    expect(runId).toBe('run-1')
    expect(options.lastSeq).toBe(5) // lastSeq 锚定 = 已收到的最大帧序号
    expect(store.messages.length).toBe(before) // 不新起助手气泡
    expect(store.loading).toBe(true) // 重连期 loading 复置
  })

  it('submitGenerativeForm() 成功 → 同样带 lastSeq 重连', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; suspended?: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'frontend_tool_request',
        runId: 'run-1',
        toolCallId: 'gf-1',
        name: 'generative_form',
        args: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
        seq: 7
      })
      handlers?.onClose?.(emitSuspendThenClose())
    })
    await store.send('填表')
    expect(store.activeForm?.toolCallId).toBe('gf-1')
    const before = store.messages.length

    await store.submitGenerativeForm({ k: 'v' })

    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({ runId: 'run-1', toolCallId: 'gf-1' }))
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    const [runId, , options] = api.reattachRun.mock.calls[0] as [string, unknown, { lastSeq?: number }]
    expect(runId).toBe('run-1')
    expect(options.lastSeq).toBe(7)
    expect(store.messages.length).toBe(before)
  })

  it('cancelGenerativeForm() 成功 → 同样带 lastSeq 重连（回灌共用入口）', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; suspended?: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'frontend_tool_request',
        runId: 'run-1',
        toolCallId: 'gf-1',
        name: 'generative_form',
        args: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
        seq: 3
      })
      handlers?.onClose?.(emitSuspendThenClose())
    })
    await store.send('填表')

    await store.cancelGenerativeForm()

    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({ cancelled: true }))
    const [, , options] = api.reattachRun.mock.calls[0] as [string, unknown, { lastSeq?: number }]
    expect(options.lastSeq).toBe(3)
  })
})

describe('B1 裁决 10：挂起中停止按钮可见 + 停止收口', () => {
  async function mountPanel(): Promise<ReturnType<typeof mount>> {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    return wrapper
  }

  it('loading=false + suspended=true ⇒ 停止钮可见、发送钮隐藏', async () => {
    const wrapper = await mountPanel()
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    store.loading = false
    store.suspended = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-composer-tip="stop"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(false)
    wrapper.unmount()
  }, 30_000)

  it('两者皆假 ⇒ 仍是发送钮（负控制）', async () => {
    const wrapper = await mountPanel()
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    store.loading = false
    store.suspended = false
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-composer-tip="stop"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(true)
    wrapper.unmount()
  }, 30_000)

  it('stop() 在挂起态无连接可 abort：仍发 POST cancel，并清 suspended/activeForm', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    store.activeRunId = 'run-1'
    store.suspended = true
    store.loading = false
    store.activeForm = {
      toolCallId: 'gf-1', runId: 'run-1', status: 'filling', error: null,
      form: { scenario: 'demo', fields: [] }
    } as unknown as typeof store.activeForm

    await store.stop()

    expect(api.cancel).toHaveBeenCalledWith('run-1')
    expect(store.suspended).toBe(false)
    expect(store.activeForm).toBeNull()
  })
})

describe('B1：sse.ts 不再为挂起收流自动重连', () => {
  it('POST /chat 流在 confirm_request 之后结束 ⇒ onClose.suspended=true 且不发起重挂连接', async () => {
    const { streamChat } = await import('@/api/sse')
    const requestedUrls: string[] = []
    const originalFetch = globalThis.fetch
    const sseText = [
      'data: {"type":"start","runId":"run-1","sessionId":"s-1","seq":1,"atMs":1}\n\n',
      'data: {"type":"confirm_request","runId":"run-1","toolCallId":"c1","name":"start_export",'
        + '"args":"{}","seq":2,"atMs":2}\n\n'
    ].join('')
    globalThis.fetch = (async (url: string) => {
      requestedUrls.push(String(url))
      const encoder = new TextEncoder()
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode(sseText))
          controller.close()
        }
      })
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body
      } as unknown as Response
    }) as typeof fetch

    try {
      const frames: unknown[] = []
      let closeInfo: { terminal: boolean; suspended?: boolean; reason?: string } | null = null
      const info = await streamChat(
        { sessionId: 's-1', message: 'hi', context: null } as never,
        {
          onFrame: (frame) => frames.push(frame),
          onClose: (close) => {
            closeInfo = close
          }
        }
      )
      expect(frames).toHaveLength(2)
      expect(info.terminal).toBe(false)
      expect(info.suspended).toBe(true)
      expect(closeInfo).toMatchObject({ terminal: false, suspended: true })
      expect(info.reason).toContain('挂起等待')
      // 挂起期不占连接：除 POST /chat 外没有发起任何重挂 GET。
      expect(requestedUrls).toHaveLength(1)
      expect(requestedUrls.filter((url) => url.includes('/api/ai/events/'))).toHaveLength(0)
    }
    finally {
      globalThis.fetch = originalFetch
    }
  })

  it('普通断流（无卡面请求帧）仍走自动重挂（负控制）', async () => {
    const { streamChat } = await import('@/api/sse')
    const requestedUrls: string[] = []
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async (url: string) => {
      requestedUrls.push(String(url))
      const encoder = new TextEncoder()
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode('data: {"type":"start","runId":"run-1","sessionId":"s-1","seq":1}\n\n'))
          controller.close()
        }
      })
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream' },
        body
      } as unknown as Response
    }) as typeof fetch

    const controller = new AbortController()
    try {
      const pending = streamChat(
        { sessionId: 's-1', message: 'hi', context: null } as never,
        {},
        { signal: controller.signal }
      )
      await flushPromises()
      const reattachUrls = requestedUrls.filter((url) => url.includes('/api/ai/events/'))
      expect(reattachUrls).toHaveLength(1) // 断流 ⇒ 自动重挂（既有行为不变）
      expect(reattachUrls[0]).toContain('/api/ai/events/run-1?lastSeq=1')
      controller.abort()
      void pending
    }
    finally {
      globalThis.fetch = originalFetch
    }
  })
})

/**
 * B1 收口修 C-1（复审 DS-Flash Critical，审查锚 `973e4a32`）：挂起收流后，即时前端工具
 * （`navigate_to` / `select_definitions` / `set_condition` / `confirm_step` /
 * `open_export_file_editor` / `download_export_files`）的执行通常**晚于**收流。
 *
 * 旧守卫以 `currentAbort === controllerAtStart` 判"旧轮"，而挂起收流会在 `send()` 的
 * `finally` 里把 `currentAbort` 置 null ⇒ 慢工具被误杀：不写段终态、不回灌 POST，
 * 后端只能等满 120s 前端工具上限。本组锁住新判据（轮次纪元）：
 * - 慢工具 + 挂起收流 ⇒ 段终态 + 回灌成功；
 * - 决策后的**同轮重连**（换了 controller）不算换轮 ⇒ 仍回灌；
 * - 真正换轮（用户发新消息）与 `stop()` ⇒ 旧工具结局仍丢弃（true positive 不得放过）。
 */
type ChatHandlerStub = {
  onFrame?: (frame: unknown) => void
  onClose?: (info: { terminal: boolean; suspended?: boolean; reconnects: number; reason?: string }) => void
}

const FRONTEND_TOOL_FRAME = {
  type: 'frontend_tool_request',
  runId: 'run-1',
  toolCallId: 'f1',
  name: 'navigate_to',
  args: '{"page":"export"}',
  timeoutSeconds: 120,
  seq: 3
}

const OK_EXECUTION = {
  ok: true,
  outcome: 'ok',
  availability: 'available',
  result: '已切换页面'
}

function toolPartOf(store: { messages: ChatMessage[] }, toolCallId: string) {
  for (const message of store.messages) {
    const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
    if (part) {
      return part
    }
  }
  return undefined
}

/** 执行器挂起等测试放行（模拟"执行耗时 > 收流耗时"）。 */
function deferExecution(): { resolve: (value: unknown) => void } {
  let resolve!: (value: unknown) => void
  const pending = new Promise((res) => {
    resolve = res as (value: unknown) => void
  })
  frontendTools.executeFrontendTool.mockImplementation(() => pending)
  return { resolve }
}

describe('B1 收口修 C-1：慢前端工具晚于挂起收流返回仍回灌', () => {
  it('执行耗时 > 收流耗时：段落 succeeded 且回灌 POST 发出（含 runId/outcome）', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    const deferred = deferExecution()
    api.chat.mockImplementation(async (_request: unknown, handlers?: ChatHandlerStub) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.(FRONTEND_TOOL_FRAME)
      // 裁决 1：服务端在请求帧之后**立刻**收流 —— 工具执行晚于此
      handlers?.onClose?.(emitSuspendThenClose())
    })

    await store.send('打开导出页')
    await flushPromises()

    expect(store.suspended).toBe(true)
    expect(api.frontendToolResult).not.toHaveBeenCalled() // 工具尚未返回
    expect(toolPartOf(store, 'f1')).toMatchObject({ status: 'running' })

    deferred.resolve(OK_EXECUTION)
    await flushPromises()
    await flushPromises()

    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-1',
      toolCallId: 'f1',
      result: '已切换页面',
      outcome: 'ok'
    }))
    expect(toolPartOf(store, 'f1')).toMatchObject({ status: 'succeeded', requiresAction: false })
  }, 20_000)

  it('决策后同轮重连（controller 已换）不算换轮：工具仍回灌', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    const deferred = deferExecution()
    api.chat.mockImplementation(async (_request: unknown, handlers?: ChatHandlerStub) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'confirm_request',
        runId: 'run-1',
        toolCallId: 'c1',
        name: 'start_export',
        args: '{"taskId":7}',
        seq: 2
      })
      handlers?.onFrame?.(FRONTEND_TOOL_FRAME)
      handlers?.onClose?.(emitSuspendThenClose())
    })

    await store.send('导出并打开页面')
    await flushPromises()
    expect(store.suspended).toBe(true)

    // 决策成功 ⇒ 带 lastSeq 重连（resumeSnapshotStream 换了新的 AbortController）
    await store.confirm(true, undefined, 'c1')
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(store.loading).toBe(true)

    deferred.resolve(OK_EXECUTION)
    await flushPromises()
    await flushPromises()

    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-1',
      toolCallId: 'f1',
      result: '已切换页面'
    }))
    expect(toolPartOf(store, 'f1')).toMatchObject({ status: 'succeeded' })
  }, 20_000)

  it('用户发新消息起新轮 ⇒ 旧工具结局仍丢弃（true positive 不放过）', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    const deferred = deferExecution()
    let chatCall = 0
    api.chat.mockImplementation(async (_request: unknown, handlers?: ChatHandlerStub) => {
      chatCall += 1
      if (chatCall === 1) {
        handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
        handlers?.onFrame?.(FRONTEND_TOOL_FRAME)
        handlers?.onClose?.(emitSuspendThenClose())
        return
      }
      handlers?.onFrame?.({ type: 'start', runId: 'run-2', sessionId: store.sessionId, seq: 1 })
    })

    await store.send('第一轮')
    await flushPromises()
    // 挂起收流已复位 loading ⇒ 用户可再发（面板隐藏发送钮，程序化路径仍可达）
    await store.send('第二轮')
    await flushPromises()

    deferred.resolve(OK_EXECUTION)
    await flushPromises()
    await flushPromises()

    expect(api.frontendToolResult).not.toHaveBeenCalled()
    expect(toolPartOf(store, 'f1')).toMatchObject({ status: 'running' }) // 旧段落不写终态
  }, 20_000)

  it('挂起态 stop() 后旧工具结局仍丢弃（不向已取消轮回灌）', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    const deferred = deferExecution()
    api.chat.mockImplementation(async (_request: unknown, handlers?: ChatHandlerStub) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.(FRONTEND_TOOL_FRAME)
      handlers?.onClose?.(emitSuspendThenClose())
    })

    await store.send('打开导出页')
    await flushPromises()

    await store.stop()
    expect(api.cancel).toHaveBeenCalledWith('run-1')

    deferred.resolve(OK_EXECUTION)
    await flushPromises()
    await flushPromises()

    expect(api.frontendToolResult).not.toHaveBeenCalled()
    // 批G · S1（语义变更）：stop() 现在把仍在途的段就收敛为 cancelled（旧口径留在 running，
    // 卡面永久「调用中…」并随镜像传染）。晚到的旧轮结局仍必须被丢弃（本用例的真正红线）。
    expect(toolPartOf(store, 'f1')).toMatchObject({ status: 'cancelled', requiresAction: false })
  }, 20_000)
})
