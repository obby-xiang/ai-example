/**
 * 批次2（前端 store 层）：恢复失败可见出口、镜像失败现场、409 手动重挂、park 视图状态、
 * unconfirmed 中性态、归档缺口可表示、开流前有界重试。
 *
 * <p>本文件只驱动真实 store（mock 掉 HTTP + 帧源），不挂载面板 —— 面板文案归另一批。
 * store 侧只落**机器可读**状态（码 / 时间 / 序号区间），不落任何用户可见文案。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import ContextPopover from '@/components/AiPanel/ContextPopover.vue'
import type { AiRunCurrent, ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'

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

const MIRROR_KEY = 'ai-mirror:'

function noSuspension(): AiRunCurrent {
  return {
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  }
}

function runningSnapshot(runId = 'run-live', archiveMaxSeq = 3): AiRunCurrent {
  return {
    found: true,
    runId,
    snapshotStatus: 'RUNNING',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq
  }
}

/** 镜像里"写盘时仍在途"的助手消息（刷新后应可续收；恢复失败时必须统一封口）。 */
function seedInFlightMirror(sessionId: string, text = '半截回答'): void {
  sessionStorage.setItem(MIRROR_KEY + sessionId, JSON.stringify({
    v: 2,
    savedAt: Date.now(),
    lastRunId: 'run-mirror',
    inFlight: true,
    runSeq: { 'run-mirror': 4 },
    messages: [{
      id: 'a-mirror',
      role: 'assistant',
      content: text,
      parts: [{ kind: 'text', seg: 0, text, streaming: true }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null
    }]
  }))
}

function readMirrorRaw(sessionId: string): { v?: number; messages: ChatMessage[] } {
  return JSON.parse(sessionStorage.getItem(MIRROR_KEY + sessionId) ?? '{}')
}

function freshStore() {
  // 每次取一个**新 Pinia** ⇒ 新 store 实例（模块级句柄如 mirrorStarted 由本文件用例自行隔离）
  setActivePinia(createPinia())
  return useAiStore()
}

function assistantHosts(messages: ChatMessage[]): ChatMessage[] {
  return messages.filter((message) => message.role === 'assistant')
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  // 连接保持"在跑"（永不结算）：不触发各轮 finally 里的轮次复位
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(noSuspension())
  api.getContext.mockRejectedValue(new ApiError('no context', 404, 'NOT_FOUND', null))
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
})

/**
 * 跨用例收尾：本文件的 `chat`/`reattachRun` 桩永不结算 ⇒ 模块级在途连接（`currentAbort`）
 * 不会自行清空，`setActivePinia` 只换 store 不碰它。不清则下一个用例的 `loadHistory()` 会按
 * "本端已有在途轮"跳过镜像与历史两条分支。丢弃本页签轮次 = 每个用例一张新页面。
 */
afterEach(() => {
  useAiStore().adoptFreshSession()
})

describe('批次2 · 恢复失败出口（history/current）', () => {
  it('history 读取失败：置 historyError、统一封口（不永久 loading/不伪装完整答案）、重试可恢复', async () => {
    const store = freshStore()
    store.sessionId = 'sess-hist'
    seedInFlightMirror('sess-hist')
    api.history.mockRejectedValue(new ApiError('service unavailable', 503, 'AI_REDIS_UNAVAILABLE', null))

    await store.loadHistory()

    expect(store.historyError).toMatchObject({
      scope: 'history',
      code: 'AI_REDIS_UNAVAILABLE',
      status: 503
    })
    expect(store.historyLoaded).toBe(true)
    expect(store.loading).toBe(false)

    // 统一封口：镜像留下的在途消息不得停在流式假态
    const host = assistantHosts(store.messages)[0]
    expect(host?.done).toBe(true)
    expect(host?.streaming).toBe(false)
    // 不可完整恢复 = 中性缺口标记，不是轮失败、也不是"答案是完整的"
    expect(store.recoveryGap).toMatchObject({ reason: 'history-unavailable' })
    expect(host?.recoveryGap).toBe(true)
    expect(host?.unconfirmed ?? false).toBe(false)
    expect(host?.errorCard ?? null).toBeNull()

    // 重试动作：清错后按同一会话重跑（一次性，不是轮询）
    api.history.mockResolvedValue({ sessionId: 'sess-hist', count: 0, messages: [] })
    await store.retryLoadHistory()
    expect(store.historyError).toBeNull()
    expect(store.recoveryGap).toBeNull()
    expect(store.loading).toBe(false)
  }, 15000)

  it('runs/current 读取失败：置 currentError + 统一封口，不伪造轮失败；重试可恢复', async () => {
    const store = freshStore()
    store.sessionId = 'sess-cur'
    seedInFlightMirror('sess-cur')
    api.history.mockResolvedValue({ sessionId: 'sess-cur', count: 0, messages: [] })
    api.runsCurrent.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))

    await store.loadHistory()

    expect(store.currentError).toMatchObject({ scope: 'current', status: 503 })
    expect(store.historyError).toBeNull()
    expect(store.loading).toBe(false)
    expect(store.recoveryGap).toMatchObject({ reason: 'snapshot-unavailable' })
    for (const message of store.messages) {
      expect(message.errorCard ?? null).toBeNull()
      expect(message.unconfirmed ?? false).toBe(false)
      expect(message.done).toBe(true)
    }

    api.runsCurrent.mockResolvedValue(noSuspension())
    await store.retryRestoreSnapshot()
    expect(store.currentError).toBeNull()
  }, 15000)
})

describe('批次2 · 409 回到进行中的回答', () => {
  it('409：读 runId/reattach 只登记手动入口，不自动重挂、不把 ID 写进用户可见文本', async () => {
    const store = freshStore()
    store.sessionId = 'sess-busy'
    api.chat.mockRejectedValue(new ApiError('busy', 409, 'SESSION_BUSY', {
      code: 'SESSION_BUSY',
      message: 'busy',
      sessionId: 'sess-busy',
      runId: 'run-live',
      reattach: '/api/ai/events/run-live',
      degraded: false
    }))

    await store.send('继续')

    expect(store.busyRun).toMatchObject({ runId: 'run-live', reattach: '/api/ai/events/run-live' })
    // 手动入口存在，但**没有**任何自动重挂
    expect(api.reattachRun).not.toHaveBeenCalled()
    // 乐观气泡回收（与既有口径一致：不出现幻影双份）
    expect(store.messages).toHaveLength(0)
    // 用户可见文本里不得出现内部 ID
    expect(store.noticeText ?? '').not.toContain('run-live')
    expect(JSON.stringify(store.noticeArgs ?? {})).not.toContain('run-live')

    // 手动动作：一次性的"回到进行中的回答"（历史对账 + 静默续收），不是轮询
    api.history.mockResolvedValue({ sessionId: 'sess-busy', count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(runningSnapshot('run-live'))
    await store.reattachBusyRun()
    expect(store.busyRun).toBeNull()
    expect(api.reattachRun).toHaveBeenCalled()
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-live')
    expect(api.history).toHaveBeenCalledTimes(1)
  }, 15000)

  it('409 持有者不可知（unknown=true）：不登记入口、不编造重挂地址', async () => {
    const store = freshStore()
    store.sessionId = 'sess-unknown'
    api.chat.mockRejectedValue(new ApiError('busy', 409, 'SESSION_BUSY', {
      code: 'SESSION_BUSY',
      message: 'busy',
      sessionId: 'sess-unknown',
      unknown: true,
      degraded: true
    }))

    await store.send('继续')

    expect(store.busyRun).toBeNull()
    expect(api.reattachRun).not.toHaveBeenCalled()
  }, 15000)
})

describe('批次2 · 镜像失败现场与旧镜像兼容', () => {
  it('镜像携带 errorCard/code；刷新回读仍是"失败现场"，不被归一成完整答案', async () => {
    const store = freshStore()
    store.sessionId = 'sess-mirror'
    store.historyLoaded = true
    api.chat.mockImplementation((_req: unknown, handlers: { onFrame?: (frame: SseFrame) => void }) => {
      handlers.onFrame?.({ type: 'error', runId: 'run-m', seq: 9, code: 'EMPTY_REPLY', message: 'empty' } as SseFrame)
      return Promise.resolve()
    })
    await store.send('问一句')
    const host = assistantHosts(store.messages)[0]
    expect(host?.errorCard).toBeTruthy()
    store.writeMirrorNow()

    const raw = readMirrorRaw('sess-mirror')
    expect(raw.v).toBe(2)
    const rawAssistant = raw.messages.find((message) => message.role === 'assistant')
    expect(rawAssistant?.code).toBe('EMPTY_REPLY')
    expect(rawAssistant?.errorCard?.code).toBe('EMPTY_REPLY')

    // 回读：历史为空时镜像即现场 —— 错误卡与码都要回来
    api.history.mockResolvedValue({ sessionId: 'sess-mirror', count: 0, messages: [] })
    const reloaded = freshStore()
    reloaded.sessionId = 'sess-mirror'
    await reloaded.loadHistory()

    const restored = assistantHosts(reloaded.messages)[0]
    expect(restored?.code).toBe('EMPTY_REPLY')
    expect(restored?.errorCard?.code).toBe('EMPTY_REPLY')
  }, 15000)

  it('旧 v2 镜像（无 errorCard/code 键、已有 parts）读取时不重建段', async () => {
    sessionStorage.setItem(MIRROR_KEY + 'sess-old', JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-old',
      inFlight: false,
      messages: [{
        id: 'a-old',
        role: 'assistant',
        content: '旧回答',
        parts: [
          { kind: 'text', seg: 0, text: '旧回答', streaming: false },
          {
            kind: 'tool',
            seg: 1,
            toolCallId: 't-old',
            name: 'danger_tool',
            kind2: 'CONFIRM',
            status: 'rejected',
            requiresAction: false
          }
        ],
        reasoning: '',
        toolRuns: [],
        pendingCall: null
      }]
    }))
    api.history.mockResolvedValue({ sessionId: 'sess-old', count: 0, messages: [] })

    const store = freshStore()
    store.sessionId = 'sess-old'
    await store.loadHistory()

    const host = assistantHosts(store.messages)[0]
    expect(host?.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(host?.parts[1]).toMatchObject({ toolCallId: 't-old', status: 'rejected' })
    expect(host?.code ?? null).toBeNull()
    expect(readMirrorRaw('sess-old').v).toBe(2)
  }, 15000)
})

describe('批次2 · park 等待状态与 unconfirmed 中性态', () => {
  it('runPhase 区分 idle / running / parked / recovering（park 不再与 loading 混同）', async () => {
    const store = freshStore()

    expect(store.runPhase).toBe('idle')

    store.loading = true
    store.suspended = false
    expect(store.runPhase).toBe('running')

    // B1 挂起即断：loading=false 但这一轮仍在等人 —— 必须是 parked，不是 idle
    store.loading = false
    store.suspended = true
    expect(store.runPhase).toBe('parked')

    store.restoringRecovery = true
    store.loading = true
    store.suspended = false
    expect(store.runPhase).toBe('recovering')
  })

  it('unconfirmed 收尾落中性视图态：不是 errorCard、不是普通失败、不进错误通知', async () => {
    const store = freshStore()
    api.chat.mockImplementation((_req: unknown, handlers: { onFrame?: (frame: SseFrame) => void }) => {
      handlers.onFrame?.({
        type: 'error',
        runId: 'run-u',
        seq: 5,
        code: 'TURN_UNCONFIRMED',
        message: 'state unconfirmed',
        turnEnd: 'unconfirmed'
      } as SseFrame)
      return Promise.resolve()
    })

    await store.send('可能成功的失败')

    const host = assistantHosts(store.messages)[0]
    expect(host?.unconfirmed).toBe(true)
    expect(host?.errorCard ?? null).toBeNull()
    expect(store.lastRoundOutcome).toBe('unconfirmed')
    expect(store.notice ?? null).toBeNull()
    expect(host?.done).toBe(true)
  })
})

describe('批次2 · 上下文明细三态（ContextPopover）', () => {
  function snapshot() {
    return {
      sessionId: 'sess-ctx',
      model: 'm',
      turnNo: 1,
      measuredAtMs: 1,
      limitTokens: 128000,
      usedTokens: 84000,
      percent: 65.6,
      source: 'stream',
      estimated: false,
      breakdown: [
        { key: 'history', tokens: 50000 },
        { key: 'free', tokens: 44000 }
      ],
      compaction: {
        level: 'none', compactedTurns: 0, lostTokens: 0, lastAtMs: null, reason: null, circuit: 'closed', fuseFails: 0
      }
    }
  }

  it('store：loading → ready → error；失败不覆盖已有数据，重试是唯一再取路径', async () => {
    const store = freshStore()
    store.sessionId = 'sess-ctx'
    api.getContext.mockResolvedValue(snapshot())

    const pending = store.loadContext()
    expect(store.contextStatus).toBe('loading')
    await pending
    expect(store.contextStatus).toBe('ready')
    expect(store.contextError).toBeNull()
    const previous = store.contextBreakdown
    expect(previous).toHaveLength(2)

    api.getContext.mockRejectedValue(new ApiError('down', 503, 'AI_REDIS_UNAVAILABLE', null))
    await store.retryLoadContext()
    expect(store.contextStatus).toBe('error')
    expect(store.contextError).toMatchObject({ scope: 'context', status: 503 })
    // 读不到 ≠ 用了 0：旧数据原样保留
    expect(store.contextBreakdown).toBe(previous)
    expect(store.contextUsage?.usedTokens).toBe(84000)
  })

  it('ContextPopover：loading / error+retry / 数据 三态各自可判，失败且有过期数据时两者并存', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiStore()
    const wrapper = mount(ContextPopover, { global: { plugins: [pinia] } })

    store.contextStatus = 'loading'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="context-popover-loading"]').exists()).toBe(true)

    store.contextStatus = 'error'
    store.contextBreakdown = null
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="context-popover-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="context-popover-retry"]').exists()).toBe(true)

    store.contextBreakdown = [{ key: 'history', tokens: 10 }, { key: 'free', tokens: 5 }]
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="context-popover-stale"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="context-row-history"]').exists()).toBe(true)

    store.contextStatus = 'ready'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="context-popover-stale"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="context-row-free"]').exists()).toBe(true)
  })
})

describe('批次2 · 归档窗口缺口', () => {
  it('续收首帧序号跳过已知区间：记为 archive-window 缺口（不可完整恢复，不伪造失败）', async () => {
    const store = freshStore()
    store.historyLoaded = true
    const host = store.appendAssistantMessage()
    api.reattachRun.mockImplementation((_runId: string, handlers: { onFrame?: (frame: SseFrame) => void }) => {
      handlers.onFrame?.({ type: 'delta', runId: 'run-gap', seq: 9, text: '后半段' } as SseFrame)
      return new Promise(() => {})
    })

    void store.resumeSnapshotStream('run-gap', 5, host.id)
    await Promise.resolve()

    expect(store.recoveryGap).toMatchObject({ reason: 'archive-window', fromSeq: 6, toSeq: 8 })
    expect(host.recoveryGap).toBe(true)
    // 缺口不是轮失败：消息上不得出现错误卡/未确认终态
    expect(host.errorCard ?? null).toBeNull()
    expect(host.unconfirmed ?? false).toBe(false)
  })
})

describe('批次2 · 开流前瞬时失败的有界重试', () => {
  it('网络/瞬时 5xx：仅零副作用时重试一次，不重复用户气泡与助手段', async () => {
    const store = freshStore()
    store.sessionId = 'sess-retry'
    api.chat
      .mockRejectedValueOnce(new ApiError('network', 0, 'NETWORK_ERROR', null))
      .mockImplementation(() => new Promise(() => {}))

    const pending = store.send('重试一次')
    await new Promise((resolve) => setTimeout(resolve, 800))

    expect(api.chat).toHaveBeenCalledTimes(2)
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(store.messages).toHaveLength(2)
    expect(store.loading).toBe(true)
    void pending
  }, 15000)

  it('已收到帧之后失败不重试（非零副作用），确定性 503 也不重试', async () => {
    const store = freshStore()
    store.sessionId = 'sess-noretry'
    api.chat.mockImplementation((_req: unknown, handlers: { onFrame?: (frame: SseFrame) => void }) => {
      handlers.onFrame?.({ type: 'delta', runId: 'run-x', seq: 1, text: '已产出' } as SseFrame)
      return Promise.reject(new ApiError('boom', 502, 'UPSTREAM_BAD_GATEWAY', null))
    })

    await store.send('不要重试')
    expect(api.chat).toHaveBeenCalledTimes(1)

    const store2 = freshStore()
    store2.sessionId = 'sess-key'
    api.chat.mockReset()
    api.chat.mockRejectedValue(new ApiError('no key', 503, 'AI_KEY_MISSING', null))
    await store2.send('缺 key')
    expect(api.chat).toHaveBeenCalledTimes(1)
  }, 15000)
})

describe('批次2 · 仅模型步重跑（真实窄入口）', () => {
  it('202 受理后不追加用户消息、不本地截断：调用窄入口并用既有 events 重挂', async () => {
    const store = freshStore()
    store.sessionId = 'sess-retry-step'
    store.activeRunId = 'run-fail'
    store.runSeq = { 'run-fail': 4 }
    store.historyLoaded = true
    const host = store.appendAssistantMessage('')
    host.done = true
    host.runId = 'run-fail'
    api.retryModelStep.mockResolvedValue({ retried: true, reason: 'MODEL_STEP_RETRY_ACCEPTED' })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))

    const outcome = await store.retryModelStep(host)

    expect(outcome).toBe('accepted')
    expect(api.retryModelStep).toHaveBeenCalledWith('run-fail')
    expect(api.reattachRun).toHaveBeenCalled()
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-fail')
    // 只重跑既有轮：用户消息数不变（这里本来就没有）
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(0)
    expect(host.streaming).toBe(true)
  })

  it('409 CANCELLED ⇒ 返回取消语义，不驱动、不把消息改成成功', async () => {
    const store = freshStore()
    store.sessionId = 'sess-retry-cancel'
    store.activeRunId = 'run-cancelled'
    store.runSeq = { 'run-cancelled': 2 }
    const host = store.appendAssistantMessage('')
    host.done = true
    host.runId = 'run-cancelled'
    api.retryModelStep.mockRejectedValue(new ApiError('denied', 409, 'ILLEGAL_STATE', {
      reason: 'RETRY_PRECONDITION_FAILED',
      failedCheck: 'CANCELLED'
    }))

    const outcome = await store.retryModelStep(host)

    expect(outcome).toBe('cancelled')
    expect(store.lastRoundOutcome).toBe('cancelled')
    expect(api.reattachRun).not.toHaveBeenCalled()
  })

  it('409 PARTIAL_CONTENT ⇒ 中性「状态未确认」视图，不自动替换半截内容', async () => {
    const store = freshStore()
    store.sessionId = 'sess-retry-partial'
    store.activeRunId = 'run-partial'
    store.runSeq = { 'run-partial': 3 }
    const host = store.appendAssistantMessage('')
    host.done = true
    host.runId = 'run-partial'
    api.retryModelStep.mockRejectedValue(new ApiError('denied', 409, 'ILLEGAL_STATE', {
      reason: 'RETRY_PRECONDITION_FAILED',
      failedCheck: 'PARTIAL_CONTENT'
    }))

    const outcome = await store.retryModelStep(host)

    expect(outcome).toBe('partial')
    expect(host.unconfirmed).toBe(true)
    expect(host.errorCard ?? null).toBeNull()
    expect(api.reattachRun).not.toHaveBeenCalled()
  })

  it('message.runId 优先于 activeRunId（刷新后 activeRunId 指向别的轮也不得重试错轮）', async () => {
    const store = freshStore()
    store.sessionId = 'sess-retry-own'
    store.activeRunId = 'run-other'
    store.runSeq = { 'run-own': 5 }
    const host = store.appendAssistantMessage('')
    host.done = true
    host.runId = 'run-own'
    api.retryModelStep.mockResolvedValue({ retried: true, reason: 'MODEL_STEP_RETRY_ACCEPTED' })

    const outcome = await store.retryModelStep(host)

    expect(outcome).toBe('accepted')
    expect(api.retryModelStep).toHaveBeenCalledWith('run-own')
  })

  it('无 runId 归属证据 ⇒ 拒绝重试（不回退 activeRunId、不调用窄入口）', async () => {
    const store = freshStore()
    store.sessionId = 'sess-retry-unknown'
    store.activeRunId = 'run-maybe-other'
    const host = store.appendAssistantMessage('')
    host.done = true

    const outcome = await store.retryModelStep(host)

    expect(outcome).toBe('denied')
    expect(store.retryRunIdFor(host)).toBeNull()
    expect(api.retryModelStep).not.toHaveBeenCalled()
  })
})

describe('批次2 · synthetic 历史条目', () => {
  it('synthetic=true 的 USER 条目转成 SYSTEM 消息并保留 synthetic 标记', async () => {
    api.history.mockResolvedValue({
      sessionId: 'sess-syn',
      count: 1,
      messages: [{ type: 'USER', text: '[上下文压缩摘要]', synthetic: true }]
    })
    const store = freshStore()
    store.sessionId = 'sess-syn'

    await store.loadHistory()

    const message = store.messages[0]
    expect(message?.role).toBe('system')
    expect(message?.synthetic).toBe(true)
  })
})

describe('批次2 · unconfirmed 的显式协议字段', () => {
  it('error 帧 code=AI_REDIS_UNAVAILABLE + unconfirmed=true ⇒ 中性态，不挂错误卡', async () => {
    const store = freshStore()
    api.chat.mockImplementation((_req: unknown, handlers: { onFrame?: (frame: SseFrame) => void }) => {
      handlers.onFrame?.({
        type: 'error',
        runId: 'run-unconfirmed',
        seq: 7,
        code: 'AI_REDIS_UNAVAILABLE',
        message: 'state unconfirmed',
        unconfirmed: true
      } as SseFrame)
      return Promise.resolve()
    })

    await store.send('状态未确认')

    const host = assistantHosts(store.messages)[0]
    expect(host?.unconfirmed).toBe(true)
    expect(host?.errorCard ?? null).toBeNull()
    expect(store.lastRoundOutcome).toBe('unconfirmed')
    expect(store.notice ?? null).toBeNull()
  })

  it('普通 AI_REDIS_UNAVAILABLE（无 unconfirmed 标记）仍是真实失败，不进入中性态', async () => {
    const store = freshStore()
    api.chat.mockImplementation((_req: unknown, handlers: { onFrame?: (frame: SseFrame) => void }) => {
      handlers.onFrame?.({
        type: 'error',
        runId: 'run-real-redis',
        seq: 8,
        code: 'AI_REDIS_UNAVAILABLE',
        message: 'redis down'
      } as SseFrame)
      return Promise.resolve()
    })

    await store.send('真实故障')

    const host = assistantHosts(store.messages)[0]
    expect(host?.unconfirmed ?? false).toBe(false)
    expect(store.lastRoundOutcome).toBe('error')
  })
})
