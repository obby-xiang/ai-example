/**
 * 批次3 前端 · 压缩阶段可见性（批次3 聚焦 RED/GREEN = 本文件第一条用例）。
 *
 * 覆盖：`heartbeat{phase:'compacting'}` 的真实已用时间、`context` 帧后的完成提示、
 * 不伪造占用百分比；其余用例（准备中 / 面板渲染 / 终态收口）只写不跑。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import type { SseFrame } from '@/types/sse'
import type { AiRunCurrent } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  deleteHistory: vi.fn(),
  getContext: vi.fn()
}))

const sse = vi.hoisted(() => ({ probeReattach: vi.fn() }))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/api/sse', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sse')>()),
  probeReattach: sse.probeReattach
}))

function asFrame(frame: Record<string, unknown>): SseFrame {
  return frame as unknown as SseFrame
}

function freshStore() {
  setActivePinia(createPinia())
  return useAiStore()
}

function noRun(): AiRunCurrent {
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

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  sse.probeReattach.mockReset()
  api.history.mockResolvedValue({ sessionId: 'sess', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue(noRun())
})

describe('批次3 · 压缩阶段', () => {
  it('compacting 心跳只写服务端已用时间，context 后标记完成且不改占用百分比', () => {
    const store = freshStore()
    store.appendAssistantMessage('')

    store.handleFrame(asFrame({ type: 'start', runId: 'run-c', sessionId: 'sess' }))
    store.handleFrame(asFrame({
      type: 'heartbeat',
      phase: 'compacting',
      elapsedMs: 12_000,
      atMs: Date.now()
    }))

    expect(store.compactionPhase).toBe('compacting')
    expect(store.compactionElapsedMs).toBe(12_000)
    expect(store.compactionStartedAtMs).toBeTypeOf('number')
    expect(Date.now() - (store.compactionStartedAtMs as number)).toBeGreaterThanOrEqual(11_500)
    expect(store.compactionCompletedAtMs).toBeNull()
    // 压缩阶段不是"占用已变化"：占用百分比只认 done.context（不伪造进度）
    expect(store.contextUsage).toBeNull()

    store.handleFrame(asFrame({
      type: 'context',
      level: 'L2',
      before: 90_000,
      after: 50_000,
      turns: 3,
      lostTokens: 40_000,
      reason: 'l2',
      est: false
    }))

    expect(store.compactionPhase).toBe('done')
    expect(store.compactionCompletedAtMs).toBeTypeOf('number')
    expect(store.contextCompaction?.level).toBe('L2')
    expect(store.contextUsage).toBeNull()
  })

  it('终帧收口压缩阶段，不让完成提示跨轮残留', () => {
    const store = freshStore()
    store.appendAssistantMessage('')
    store.handleFrame(asFrame({ type: 'start', runId: 'run-d', sessionId: 'sess' }))
    store.handleFrame(asFrame({ type: 'heartbeat', phase: 'compacting', elapsedMs: 1_000 }))
    store.handleFrame(asFrame({ type: 'context', level: 'L1', before: 1, after: 0, turns: 0, lostTokens: 0, reason: 'l1', est: false }))
    store.handleFrame(asFrame({ type: 'done', runId: 'run-d' }))

    expect(store.compactionPhase).toBe('idle')
    expect(store.compactionStartedAtMs).toBeNull()
  })

  it('快照未建：/runs/current 空态 + 重挂 202 preparing ⇒ 可恢复准备中，不封口、不落错误', async () => {
    const store = freshStore()
    store.sessionId = 'sess-preparing'
    const host = store.appendAssistantMessage('半截')
    host.streaming = true
    host.done = false
    host.runId = 'run-preparing'
    store.activeRunId = 'run-preparing'
    api.runsCurrent.mockResolvedValue(noRun())
    sse.probeReattach.mockResolvedValue({
      status: 202,
      preparing: { runId: 'run-preparing', elapsedMs: 4_000, retryAfterMs: 2_000 }
    })

    await store.restoreSnapshotGuarded()

    expect(store.preparingRun?.runId).toBe('run-preparing')
    expect(store.preparingRun?.elapsedMs).toBe(4_000)
    expect(store.preparingRun?.startedAtMs).toBeTypeOf('number')
    expect(store.currentError).toBeNull()
    expect(store.recoveryGap).toBeNull()
    expect(host.done).toBe(false)
    expect(host.streaming).toBe(true)
  })

  it('准备中手动重试：快照建好后按既有恢复路径续收并清掉准备中', async () => {
    const store = freshStore()
    store.sessionId = 'sess-preparing-2'
    const host = store.appendAssistantMessage('半截')
    host.streaming = true
    host.done = false
    host.runId = 'run-p2'
    store.activeRunId = 'run-p2'
    api.runsCurrent.mockResolvedValue(noRun())
    sse.probeReattach.mockResolvedValue({
      status: 202,
      preparing: { runId: 'run-p2', elapsedMs: 1_000, retryAfterMs: 2_000 }
    })
    await store.restoreSnapshotGuarded()
    expect(store.preparingRun).not.toBeNull()

    api.runsCurrent.mockResolvedValue({
      ...noRun(),
      found: true,
      runId: 'run-p2',
      snapshotStatus: 'RUNNING',
      archiveMaxSeq: 2
    })
    const reattach = vi.spyOn(store, 'reattachActive').mockResolvedValue(undefined)
    await store.retryPreparing()

    expect(store.preparingRun).toBeNull()
    expect(reattach).toHaveBeenCalledWith('run-p2', expect.objectContaining({ lastSeq: 0 }))
  })

  it('done 后压缩完成提示不覆盖 unconfirmed / 错误卡（批次2 中性视图优先）', () => {
    const store = freshStore()
    const host = store.appendAssistantMessage('')
    store.handleFrame(asFrame({ type: 'start', runId: 'run-u', sessionId: 'sess' }))
    store.handleFrame(asFrame({ type: 'heartbeat', phase: 'compacting', elapsedMs: 2_000 }))
    store.handleFrame(asFrame({ type: 'context', level: 'L2', before: 2, after: 1, turns: 1, lostTokens: 1, reason: 'l2', est: false }))
    store.handleFrame(asFrame({ type: 'error', runId: 'run-u', code: 'AI_REDIS_UNAVAILABLE', message: 'x', unconfirmed: true }))

    expect(host.unconfirmed).toBe(true)
    expect(host.errorCard ?? null).toBeNull()
    expect(store.compactionPhase).toBe('idle')
  })
})
