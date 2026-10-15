/**
 * R-166 门卡链路修复批：口径①~⑤的回归锁。
 *
 * 覆盖（逐条对应用户裁决口径）：
 * ① 倒计时接心跳（方案 A）：心跳驱动 + 陈旧帧不锚 + 同 id 只许更早 ⇒ **不闪回**；
 * ② 拒绝原因解耦：重派生/帧重投不动输入框，只有换 toolCallId 才清；
 * ③ 409 幂等拒绝后不写乐观裁决，SSE 结局帧到达即落真实状态并撤瞬态；
 * ④ 超时悬挂兜底：倒计时归零拉一次 runs/current 校正，无结局 ⇒ 「已超时作废」回执；
 * ⑤ 复制页签隔离：navigate 起新会话 / reload·back_forward 恢复。
 *
 * 口径③ 的 409 主流程（瞬态 + 3s 校正）另有锁：tests/parts-residue-cleanup.spec.ts。
 * 挂载方式与 stubs 口径照抄 tests/ai-panel-parts-render.spec.ts（同一个 jsdom + Pinia 面）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, PendingToolCall } from '@/types/ai'
import { MIRROR_PREFIX, SESSION_ID_KEY, useAiStore } from '@/stores/ai'
import { nextTick } from 'vue'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

/** 导航类型注入位（口径⑤：`performance.getEntriesByType('navigation')[0].type`）。 */
const navType = vi.hoisted(() => ({ value: null as string | null }))

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

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  navType.value = null
  vi.spyOn(performance, 'getEntriesByType').mockImplementation(((type: string) => (
    type === 'navigation' && navType.value !== null ? [{ type: navType.value }] : []
  )) as never)
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.feedbackSubmit.mockReset()
  api.confirm.mockReset()
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h', confirmTimeoutSeconds: 240, frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0, sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z' })
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0 })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

/** 造一条确认门挂起（真实帧路：appendAssistantMessage + confirm_request 帧）。 */
function armGate(
  store: ReturnType<typeof useAiStore>,
  toolCallId = 'c1',
  options: { atMs?: number; timeoutSeconds?: number; expiresAt?: number } = {}
): ChatMessage {
  const atMs = options.atMs ?? Date.now()
  const timeoutSeconds = options.timeoutSeconds ?? 240
  const assistant = store.appendAssistantMessage()
  store.handleFrame({
    type: 'confirm_request',
    toolCallId,
    name: 'start_export',
    args: '{"taskId":291}',
    timeoutSeconds,
    expiresAt: options.expiresAt ?? atMs + timeoutSeconds * 1000,
    atMs,
    runId: 'run-1',
    seq: 2
  })
  return assistant
}

async function mountPanel(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

/** 门卡上的「剩余确认时间 M:SS / N 秒」读数（秒）。 */
function countdownSeconds(wrapper: ReturnType<typeof mount>): number {
  const gate = wrapper.find('[data-testid="hitl-gate"]')
  const text = gate.text()
  const clock = /剩余确认时间 (\d+):(\d\d)/.exec(text)
  if (clock) {
    return Number(clock[1]) * 60 + Number(clock[2])
  }
  const seconds = /剩余确认时间 (\d+) 秒/.exec(text)
  if (seconds) {
    return Number(seconds[1])
  }
  throw new Error(`门卡未显示倒计时读数：${text}`)
}

describe('R-166 口径①：倒计时接心跳（帧锚定 + 心跳驱动 + 不闪回）', () => {
  it('面板读数逐秒单调递减（连采 8 秒）；窗内帧重投/重派生不回跳', async () => {
    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 240 })
    expect(store.confirmClock).toMatchObject({ toolCallId: 'c1', anchor: 'frame', totalSeconds: 240 })
    expect(store.confirmClock?.deadlineMs).toBe(1_700_000_240_000)

    const wrapper = await mountPanel(store.messages)
    const readings: number[] = []
    for (let second = 1; second <= 8; second += 1) {
      await vi.advanceTimersByTimeAsync(1000)
      await nextTick()
      // 第 4 秒时插一次"帧重投"（同一 confirm_request 再投一次 = 旧口径下触发重派生闪回的路径）
      if (second === 4) {
        store.handleFrame({
          type: 'confirm_request',
          toolCallId: 'c1',
          name: 'start_export',
          args: '{"taskId":291}',
          timeoutSeconds: 240,
          expiresAt: 1_700_000_240_000,
          atMs: 1_700_000_000_000,
          runId: 'run-1',
          seq: 20
        })
        await nextTick()
      }
      readings.push(countdownSeconds(wrapper))
    }
    // 严格逐秒递减（240 − 秒数），中途无回跳（旧口径在此会跳回 239/240）
    expect(readings).toEqual([239, 238, 237, 236, 235, 234, 233, 232])
    for (let i = 1; i < readings.length; i += 1) {
      expect(readings[i]).toBe(readings[i - 1]! - 1)
    }
    // 时钟未被重投帧推后（同 id 只许更早）
    expect(store.confirmClock?.deadlineMs).toBe(1_700_000_240_000)
    wrapper.unmount()
  })

  it('心跳驱动：剩余 = timeoutSeconds − waitedSeconds；偏晚/乱序的心跳不回跳', async () => {
    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 240 })

    // 已等待 60s ⇒ 剩 180s
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', suspendKind: 'confirm', timeoutSeconds: 240, waitedSeconds: 60, heartbeatSeq: 1 })
    expect(store.confirmClock).toMatchObject({ anchor: 'heartbeat', waitedSeconds: 60 })
    expect(store.confirmClock?.deadlineMs).toBe(1_700_000_180_000)
    // 已等待 61s ⇒ 剩 179s（继续推进）
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', timeoutSeconds: 240, waitedSeconds: 61, heartbeatSeq: 2 })
    expect(store.confirmClock?.deadlineMs).toBe(1_700_000_179_000)
    // 乱序/取整偏晚的心跳（waitedSeconds=60 ⇒ 到期更晚）不得把倒计时推后
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', timeoutSeconds: 240, waitedSeconds: 60, heartbeatSeq: 3 })
    expect(store.confirmClock?.deadlineMs).toBe(1_700_000_179_000)

    const wrapper = await mountPanel(store.messages)
    expect(countdownSeconds(wrapper)).toBe(179)
    wrapper.unmount()
  })

  it('并发的**前端工具**心跳不得抢占门卡时钟（判据 = 当前确认门的 toolCallId）', () => {
    const store = useAiStore()
    armGate(store, 'c1', { timeoutSeconds: 240 })
    const before = store.confirmClock?.deadlineMs
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'g9', suspendKind: 'frontend-tool', timeoutSeconds: 120, waitedSeconds: 5, heartbeatSeq: 1 })
    expect(store.confirmClock?.deadlineMs).toBe(before)
    expect(store.confirmClock?.toolCallId).toBe('c1')
  })

  it('陈旧帧（重挂回放 / 刷新恢复）不锚时钟：时钟为空时保持「等待人工确认」，由首个心跳校正', () => {
    vi.useFakeTimers({ now: 1_700_000_600_000 })
    const store = useAiStore()
    // atMs 落后本地钟 600s（重挂回放的原始帧）⇒ 陈旧判据命中 ⇒ 不锚（否则会得到"完整 240s"）
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 240, expiresAt: 1_700_000_240_000 })
    expect(store.confirmClock).toBeNull()
    // 首个心跳到达即校正（刷新恢复走同一口径）：已等待 60s / 上限 240s ⇒ 剩 180s
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', timeoutSeconds: 240, waitedSeconds: 60, heartbeatSeq: 1 })
    expect(store.confirmClock?.toolCallId).toBe('c1')
    expect(store.confirmClock?.deadlineMs).toBe(1_700_000_600_000 + 180_000)
  })
})

describe('R-166 口径②：拒绝原因解耦（重派生不动输入框）', () => {
  it('帧重投/重派生不丢用户输入；只有换 toolCallId 才清空', async () => {
    const store = useAiStore()
    const first = armGate(store, 'c1')
    const wrapper = await mountPanel(store.messages)
    const reason = wrapper.find('[data-testid="reject-reason"]')
    expect(reason.exists()).toBe(true)
    await reason.setValue('这个参数不对，请改 taskId')

    // 重派生①：另起一个工具段（tool_start 帧 ⇒ DERIVE_FRAME_TYPES ⇒ 全量重算派生投影）
    store.handleFrame({ type: 'tool_start', toolCallId: 'x9', name: 'list_tasks', kind: 'BACKEND', runId: 'run-1', seq: 3 })
    await nextTick()
    expect((wrapper.find('[data-testid="reject-reason"]').element as HTMLTextAreaElement).value).toBe('这个参数不对，请改 taskId')

    // 重派生②：同一挂起调用的**帧重投**（seq 前进；语义闸用"已决"判据兜底，本帧照常重放）
    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 'c1',
      name: 'start_export',
      args: '{"taskId":291}',
      timeoutSeconds: 240,
      expiresAt: 1_700_000_240_000,
      atMs: Date.now(),
      runId: 'run-1',
      seq: 4
    })
    await nextTick()
    expect((wrapper.find('[data-testid="reject-reason"]').element as HTMLTextAreaElement).value).toBe('这个参数不对，请改 taskId')

    // 换人（新门卡 toolCallId=c2）才清空
    first.done = true
    armGate(store, 'c2')
    await nextTick()
    expect(store.pendingConfirm?.toolCallId).toBe('c2')
    expect((wrapper.find('[data-testid="reject-reason"]').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })
})

describe('R-166 口径④：超时悬挂兜底（倒计时归零 ⇒ 权威校正 ⇒ 已超时作废）', () => {
  it('归零 + runs/current 无结局（真悬挂）⇒ 灰族「已超时作废」回执，不设重试钮', async () => {
    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 2 })
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export' }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })

    const wrapper = await mountPanel(store.messages)
    expect(countdownSeconds(wrapper)).toBe(2)
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    await nextTick()

    expect(api.runsCurrent).toHaveBeenCalled()
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.exists()).toBe(true)
    expect(receipt.text()).toContain('! 已超时作废')
    // 门卡与重试钮都不在（requiresAction 收口 ⇒ 挂起卡由派生抽掉）
    expect(wrapper.find('[data-testid="hitl-gate"]').exists()).toBe(false)
    expect(receipt.text()).not.toContain('重试')
    // 段侧标记：本地作废（与后端 TIMEOUT 同形不同因）
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1')
    expect(part).toMatchObject({ status: 'expired', expiredLocally: true, requiresAction: false })
    // 工具卡 tag 与回执徽标口径一致（不落「会话已失效」）
    const toolCard = wrapper.find('[data-testid="tool-card-c1"]')
    expect(toolCard.text()).toContain('已超时作废')
    expect(toolCard.text()).not.toContain('会话已失效')
    wrapper.unmount()
  })

  it('归零 + runs/current 有结局 ⇒ 按**真实裁决**收敛（不写"已超时作废"）', async () => {
    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 2 })
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'APPROVED', kind: 'CONFIRM', name: 'start_export' }],
      unresolvedExternal: [], awaitingExternal: true, archiveMaxSeq: 0
    })

    const wrapper = await mountPanel(store.messages)
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    await nextTick()

    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.text()).toContain('✓ 已确认')
    expect(receipt.text()).not.toContain('已超时')
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1')
    expect(part).toMatchObject({ status: 'running', decision: 'approved', requiresAction: false })
    wrapper.unmount()
  })

  it('归零校正每 toolCallId 只做一次（每秒 tick 不重复打 runs/current）', async () => {    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 2 })
    const wrapper = await mountPanel(store.messages)
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    await nextTick()
    const callsAfterZero = api.runsCurrent.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(api.runsCurrent.mock.calls.length).toBe(callsAfterZero)
    wrapper.unmount()
  })
})

describe('R-166 口径③：409 之后的瞬态与结局帧收敛', () => {
  it('409 后 SSE 结局帧到达 ⇒ 落真实状态并撤「已提交，同步中…」瞬态（不再补拉 runs/current）', async () => {
    const store = useAiStore()
    const assistant = armGate(store, 'c1')
    store.activeRunId = 'run-1'
    api.confirm.mockRejectedValue(new (await import('@/api/http')).ApiError('Conflict', 409, 'DUPLICATE_TOOL_CALL_ID', null))

    await store.confirm(true)
    expect(store.gateSubmissionOf('c1')).toMatchObject({ syncing: true })
    expect(assistant.pendingCall?.status).toBe('pending')

    // 真实裁决是"被拒"（旧口径会硬写 approved ⇒ 假「已确认」）
    store.handleFrame({ type: 'confirm_decision', toolCallId: 'c1', name: 'start_export', decision: 'reject', approved: false, status: 'REJECTED', reason: '参数越权', runId: 'run-1', seq: 9 })
    expect(store.gateSubmissionOf('c1')).toBeNull()
    expect(store.confirmClock).toBeNull()
    const part = assistant.parts.find((p) => p.kind === 'tool' && p.toolCallId === 'c1')
    expect(part).toMatchObject({ status: 'rejected', requiresAction: false })
    expect(api.runsCurrent).not.toHaveBeenCalled()
  })

  it('提交在途时倒计时归零**不**触发本地作废（后端已收到裁决，等结局帧/409 校正）', async () => {
    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 240 })
    // 模拟"已提交待同步"（409 瞬态）：在途表有记录
    store.gateSubmissions = { c1: { decision: 'rejected', submittedAt: 1_700_000_000_000, syncing: true } }
    store.noteGateCountdownExpired('c1')
    await flushPromises()
    expect(api.runsCurrent).not.toHaveBeenCalled()
    expect(store.gateExpirySynced.c1).toBeUndefined()
  })
})

describe('R-166 口径⑤：复制页签隔离（navigate vs reload）', () => {
  function seedTab(sessionId = 'tab-original'): void {
    sessionStorage.setItem(SESSION_ID_KEY, sessionId)
    sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify({ v: 2, savedAt: 1, messages: [] }))
  }

  it('navigate（新页签/复制页签/手输地址）⇒ 起新会话：换 id + 清 SESSION_ID_KEY 与镜像引用', () => {
    seedTab()
    navType.value = 'navigate'
    const store = useAiStore()
    expect(store.sessionId).not.toBe('tab-original')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe(store.sessionId)
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-original')).toBeNull()
  })

  it('reload（本页签刷新）⇒ 恢复原会话与镜像', () => {
    seedTab('tab-reload')
    navType.value = 'reload'
    const store = useAiStore()
    expect(store.sessionId).toBe('tab-reload')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-reload')).not.toBeNull()
  })

  it('back_forward（历史前后退）⇒ 按恢复处理', () => {
    seedTab('tab-back')
    navType.value = 'back_forward'
    expect(useAiStore().sessionId).toBe('tab-back')
  })

  it('导航类型读不到（环境不支持/条目缺失）⇒ 保守按恢复处理（不丢现场）', () => {
    seedTab('tab-unknown')
    navType.value = null
    expect(useAiStore().sessionId).toBe('tab-unknown')
  })

  it('无存量 id 时 navigate/reload 都是"建新 id + 写盘"', () => {
    navType.value = 'navigate'
    const created = useAiStore().sessionId
    expect(created.length).toBeGreaterThan(0)
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe(created)
  })
})

describe('R-166 口径①（store 侧）：时钟清收口', () => {
  it('轮终帧（done/error）与 resetSession 都撤掉时钟与提交态', async () => {
    const store = useAiStore()
    const assistant = armGate(store, 'c1')
    expect(store.confirmClock).not.toBeNull()
    store.handleFrame({ type: 'done', runId: 'run-1', seq: 9 })
    expect(store.confirmClock).toBeNull()
    expect(assistant.pendingCall).toBeNull()

    const second = armGate(store, 'c2')
    expect(store.confirmClock).not.toBeNull()
    await store.resetSession()
    expect(store.confirmClock).toBeNull()
    expect(store.gateSubmissions).toEqual({})
    expect(second.parts.some((p) => p.kind === 'tool')).toBe(true)
  })

  it('挂起投影未定（无 pending）时不产生时钟读数（面板静态兜底）', () => {
    const store = useAiStore()
    const pending: PendingToolCall | null = store.pendingConfirm
    expect(pending).toBeNull()
    expect(store.confirmClock).toBeNull()
  })
})
