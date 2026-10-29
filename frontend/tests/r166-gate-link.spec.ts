/**
 * 门卡链路回归锁。
 *
 * 2026-10-20 用户批准「去超时」回落（demo `docs/demo/chat-ux/card-consistency-fix.html`）：
 * R-166 口径①（倒计时接心跳：帧锚定 / 心跳驱动 / 陈旧帧不锚）与口径④（倒计时归零 ⇒
 * 权威校正 ⇒ 本地「已超时作废」）随**倒计时整段退役**；本文件改为锁「无倒计时」：
 * - 挂起门卡无倒计时环 / 无剩余时间读数 / 无静态兜底文案；
 * - 心跳帧只剩保活语义（不写任何门卡状态）；
 * - 本地不再判超时作废（任意时长都不拉 runs/current、不落 expired），历史 TIMEOUT 卡只读展示保留。
 *
 * 保留的口径：
 * ② 拒绝原因解耦：重派生/帧重投不动输入框，只有换 toolCallId 才清；
 * ③ 409 幂等拒绝后不写乐观裁决，SSE 结局帧到达即落真实状态并撤瞬态。
 *
 * 口径⑤（复制页签隔离）原判据 = navigation type，**R-166B 已整体替换**为三层握手
 * （pageId + Web Locks + BroadcastChannel）——回归锁随迁至 tests/r166b-fixes.spec.ts。
 *
 * 口径③ 的 409 主流程（瞬态 + 3s 校正）另有锁：tests/parts-residue-cleanup.spec.ts。
 * 挂载方式与 stubs 口径照抄 tests/ai-panel-parts-render.spec.ts（同一个 jsdom + Pinia 面）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { sessionIdStorageKey } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
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
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.feedbackSubmit.mockReset()
  api.confirm.mockReset()
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h', frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0, sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z' })
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0 })
  installIdentityFixture()
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

describe('2026-10-20 去超时裁决：确认卡无倒计时（口径① 整段退役）', () => {
  it('挂起门卡无倒计时环 / 无剩余时间读数 / 无静态兜底文案', async () => {
    const store = useAiStore()
    armGate(store, 'c1', { timeoutSeconds: 240 })
    const wrapper = await mountPanel(store.messages)
    const gate = wrapper.find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)
    expect(gate.find('svg').exists()).toBe(false)
    expect(gate.text()).not.toContain('剩余确认时间')
    expect(gate.text()).not.toContain('等待人工确认')
    expect(gate.text()).not.toContain('确认已超时')
    wrapper.unmount()
  })

  it('心跳帧只剩保活语义：不写门卡状态，DOM 逐帧重投前后一字不动', async () => {
    const store = useAiStore()
    armGate(store, 'c1', { timeoutSeconds: 240 })
    const wrapper = await mountPanel(store.messages)
    const before = wrapper.find('[data-testid="hitl-gate"]').html()
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', suspendKind: 'confirm', timeoutSeconds: 240, waitedSeconds: 60, heartbeatSeq: 1 })
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'g9', suspendKind: 'frontend-tool', timeoutSeconds: 120, waitedSeconds: 5, heartbeatSeq: 2 })
    await nextTick()
    expect(wrapper.find('[data-testid="hitl-gate"]').html()).toBe(before)
    // 原始帧口径仍随段保留（对账用），只是不再被折算成任何倒计时
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1')
    expect(part).toMatchObject({ status: 'pending', requiresAction: true, timeoutSeconds: 240 })
    // 段上没有「本地判超时」标记位（整段退役）
    expect(part && 'expiredLocally' in part).toBe(false)
    wrapper.unmount()
  })
})

describe('R-166 口径② + 2A 缺陷④：拒绝原因输入框已删除（H1 无理由框）', () => {
  it('门卡无拒因输入框；帧重投 / 重派生 / 换门卡都不会复活它', async () => {
    const store = useAiStore()
    const first = armGate(store, 'c1')
    const wrapper = await mountPanel(store.messages)
    expect(wrapper.find('[data-testid="hitl-gate"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="reject-reason"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="hitl-gate"] textarea').exists()).toBe(false)

    // 重派生①：另起一个工具段（tool_start 帧 ⇒ DERIVE_FRAME_TYPES ⇒ 全量重算派生投影）
    store.handleFrame({ type: 'tool_start', toolCallId: 'x9', name: 'list_tasks', kind: 'BACKEND', runId: 'run-1', seq: 3 })
    await nextTick()
    expect(wrapper.find('[data-testid="reject-reason"]').exists()).toBe(false)

    // 重派生②：同一挂起调用的帧重投（seq 前进；语义闸用"已决"判据兜底，本帧照常重放）
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
    expect(wrapper.find('[data-testid="reject-reason"]').exists()).toBe(false)

    // 换人（新门卡 toolCallId=c2）同样不宣回输入框
    first.done = true
    armGate(store, 'c2')
    await nextTick()
    expect(store.pendingConfirm?.toolCallId).toBe('c2')
    expect(wrapper.find('[data-testid="reject-reason"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('2026-10-20 去超时裁决：本地不再判超时作废（口径④ 整段退役）', () => {
  it('挂起卡一直待决：到点后不拉 runs/current、不落「已超时」（表单/确认一直可操作）', async () => {
    vi.useFakeTimers({ now: 1_700_000_000_000 })
    const store = useAiStore()
    armGate(store, 'c1', { atMs: 1_700_000_000_000, timeoutSeconds: 2 })
    const wrapper = await mountPanel(store.messages)
    await vi.advanceTimersByTimeAsync(3000)
    await flushPromises()
    await nextTick()

    expect(api.runsCurrent).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="hitl-gate"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="hitl-receipt"]').exists()).toBe(false)
    // 双按钮仍可点（无「已置灰」的本地超时态）
    const buttons = wrapper.findAll('[data-testid="hitl-gate"] button')
    expect(buttons.map((b) => b.text())).toEqual(['拒绝', '批准'])
    for (const button of buttons) {
      expect(button.attributes('disabled')).toBeUndefined()
    }
    wrapper.unmount()
  })

  it('历史 TIMEOUT 卡（TIMEOUT 帧落段）仍只读展示「! 已超时」（历史展示保留）', async () => {
    const store = useAiStore()
    armGate(store, 'c1')
    store.handleFrame({ type: 'confirm_decision', toolCallId: 'c1', name: 'start_export', decision: 'timeout', approved: false, status: 'TIMEOUT', reason: 'CONFIRM_TIMEOUT', runId: 'run-1', seq: 9 })
    const wrapper = await mountPanel(store.messages)
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.exists()).toBe(true)
    expect(receipt.text()).toContain('! 已超时')
    expect(wrapper.find('[data-testid="hitl-gate"]').exists()).toBe(false)
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
    const part = assistant.parts.find((p) => p.kind === 'tool' && p.toolCallId === 'c1')
    expect(part).toMatchObject({ status: 'rejected', requiresAction: false })
    expect(api.runsCurrent).not.toHaveBeenCalled()
  })
})

describe('RC-4 口径⑤（R-166B 替换后）：本文件的页签判据锁已迁出', () => {
  it('存量会话 id 一律先按恢复取用（复制品判定交给 Web Locks 三层握手）', () => {
    sessionStorage.setItem(sessionIdStorageKey(), '9001')
    expect(useAiStore().sessionId).toBe('9001')
  })

  it('无存量 id ⇒ 由后端发号并写到身份键下（不再本地造号）', async () => {
    installIdentityFixture({ sessionId: '' })
    const store = useAiStore()
    expect(store.sessionId).toBe('')
    const created = await store.ensureSession()
    expect(created.length).toBeGreaterThan(0)
    expect(store.sessionId).toBe(created)
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(created)
  })
})

describe('R-166 口径③（store 侧）：提交态清收口', () => {
  it('轮终帧（done）与 resetSession 都撤掉提交态', async () => {
    const store = useAiStore()
    const assistant = armGate(store, 'c1')
    store.gateSubmissions = { c1: { decision: 'approved', submittedAt: Date.now(), syncing: true } }
    store.handleFrame({ type: 'done', runId: 'run-1', seq: 9 })
    expect(store.gateSubmissions).toEqual({})
    expect(assistant.pendingCall).toBeNull()

    const second = armGate(store, 'c2')
    store.gateSubmissions = { c2: { decision: 'rejected', submittedAt: Date.now(), syncing: true } }
    await store.resetSession()
    expect(store.gateSubmissions).toEqual({})
    expect(second.parts.some((p) => p.kind === 'tool')).toBe(true)
  })
})
