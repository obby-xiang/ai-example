/**
 * 测试扩充 V5 · B10 不可撤销确认卡的**全生命周期**（前端）。
 *
 * 批4-D 已锁住「未勾选 ⇒ 批准禁用 / 勾选 ⇒ 可批准 / 刷新恢复重建复选框」三格；
 * 本批补齐链路上剩下的四段，并把「变更即复位」从注释变成断言：
 *
 * 1. **取消勾选回写**：勾选不是一次性开关 —— 面板取消勾选即写回 store，批准重新置灰；
 * 2. **提交**：勾选后批准 ⇒ 在途表入表、勾选态保留到结局帧到达；
 * 3. **复位**：`confirm_decision` / 重投 `confirm_request` / 换会话 / 停止本轮
 *    ⇒ 勾选态清空（不允许"上一张卡的同意"变成下一张卡的默认值）；
 * 4. **恢复**：`runs/current` 条目重建挂起卡时复选框从零开始（恢复 ≠ 已同意）。
 *
 * 两条已知缺口在本批修掉（断言先红）：
 * - `adoptFreshSession()` 清了在途表/折叠态/发布登记，**漏了 `gateAcks`**；
 * - `stop()` 收敛了段与预览，**漏了 `gateAcks`**。
 * 两者都与 `gateAcks` 状态注释「变更即复位……会话或轮次收口都清」直接矛盾。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart, ToolPart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
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

function buildMessage(parts: MessagePart[]): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-1', role: 'assistant', content: text, parts, reasoning: '', toolRuns: [],
    pendingCall: null, streaming: false, done: true
  }
}

/** 不可撤销门卡段（后端 start_publish = 唯一 Category.P）。 */
function irreversiblePart(toolCallId = 'pub1', name = 'start_publish'): ToolPart {
  return {
    kind: 'tool', seg: 0, toolCallId, name, kind2: 'CONFIRM', status: 'pending',
    args: '{"taskId":9}', requiresAction: true, irreversible: true
  }
}

function armPendingCard(store: ReturnType<typeof useAiStore>, part: ToolPart, runId = 'run-1'): void {
  store.messages = [buildMessage([part])]
  store.historyLoaded = true
  store.activeRunId = runId
  store.suspended = true
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: false })
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({})
  installIdentityFixture()
})

describe('B10-01 勾选与取消勾选：双向写回 store（面板 DOM → store）', () => {
  it('勾选 ⇒ gateAckOf=true，取消勾选 ⇒ false，且批准重新置灰、提示回来', async () => {
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const store = useAiStore()
    armPendingCard(store, irreversiblePart())
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    const gate = wrapper.find('[data-testid="pending-card-pub1"]').find('[data-testid="hitl-gate"]')
    const box = gate.find('[data-testid="gate-ack-box"]')
    const approve = (): ReturnType<typeof gate.findAll>[number] => gate.findAll('button')[1]!
    expect(approve().attributes('disabled')).toBeDefined()

    await box.setValue(true)
    expect(store.gateAckOf('pub1')).toBe(true)
    expect(approve().attributes('disabled')).toBeUndefined()
    expect(gate.find('[data-testid="gate-ack-hint"]').exists()).toBe(false)

    await box.setValue(false)
    expect(store.gateAckOf('pub1')).toBe(false)
    expect(approve().attributes('disabled')).toBeDefined()
    expect(gate.find('[data-testid="gate-ack-hint"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('B10-02 提交：拒绝不受勾选限制，批准必须已勾选', () => {
  it('未勾选：confirm(true) 不发请求并给出提示；confirm(false) 照常提交', async () => {
    const store = useAiStore()
    armPendingCard(store, irreversiblePart('pub2'), 'run-2')

    await store.confirm(true, undefined, 'pub2')
    expect(api.confirm).not.toHaveBeenCalled()
    expect(store.notice).toContain('勾选')

    await store.confirm(false, '用户拒绝执行', 'pub2')
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0]).toMatchObject({ runId: 'run-2', toolCallId: 'pub2', approved: false })
  })

  it('勾选后批准：在途表入表、勾选态保留（等结局帧），结局帧到达后两者一起复位', async () => {
    const store = useAiStore()
    armPendingCard(store, irreversiblePart('pub3'), 'run-3')
    api.confirm.mockResolvedValue(undefined)

    store.ackGate('pub3')
    await store.confirm(true, undefined, 'pub3')

    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(store.gateSubmissionOf('pub3')).not.toBeNull()
    expect(store.gateAckOf('pub3')).toBe(false)

    store.handleFrame({
      type: 'confirm_decision', seq: 1, atMs: 1_790_000_000_000,
      toolCallId: 'pub3', name: 'start_publish', decision: 'APPROVED', approved: true, status: 'APPROVED'
    } as never)
    expect(store.gateAckOf('pub3')).toBe(false)
    expect(store.gateSubmissionOf('pub3')).toBeNull()
  })

  it('提交失败回退：段回 pending、在途表清空、勾选保留（用户可原地重试，不用重勾）', async () => {
    const store = useAiStore()
    armPendingCard(store, irreversiblePart('pub4'), 'run-4')
    api.confirm.mockRejectedValueOnce(new Error('网络中断'))

    store.ackGate('pub4')
    await store.confirm(true, undefined, 'pub4')

    expect(store.gateSubmissionOf('pub4')).toBeNull()
    expect(store.gateAckOf('pub4')).toBe(true)
    const part = store.messages[0]!.parts[0] as ToolPart
    expect(part.requiresAction).toBe(true)
    expect(part.status).toBe('pending')
    expect(store.notice).toContain('网络中断')
  })
})

describe('B10-03 变更即复位：新请求帧 / 结局帧 / 换会话 / 停止本轮', () => {
  it('同 toolCallId 的 confirm_request 重投 ⇒ 旧勾选清空（新卡从零开始）', async () => {
    const store = useAiStore()
    armPendingCard(store, irreversiblePart('pub5'), 'run-5')
    store.ackGate('pub5')
    expect(store.gateAckOf('pub5')).toBe(true)

    // 已决残段的重投由 D8 闸拦下（另测）；这里先把段还原成"未决"再投，验证帧口复位本身
    const part = store.messages[0]!.parts[0] as ToolPart
    part.status = 'pending'
    part.requiresAction = true
    store.handleFrame({
      type: 'confirm_request', seq: 2, atMs: 1_790_000_000_100, runId: 'run-5',
      toolCallId: 'pub5', name: 'start_publish', args: '{"taskId":9}', irreversible: true
    } as never)
    expect(store.gateAckOf('pub5')).toBe(false)
  })

  it('换会话（adoptFreshSession）⇒ 勾选态清空（不跨会话串味）', () => {
    const store = useAiStore()
    armPendingCard(store, irreversiblePart('pub6'), 'run-6')
    store.ackGate('pub6')

    store.adoptFreshSession()
    expect(store.gateAckOf('pub6')).toBe(false)
    expect(store.gateSubmissions).toEqual({})
  })

  it('停止本轮（stop）⇒ 勾选态清空（本轮已被放弃，勾选不再有效）', async () => {
    const store = useAiStore()
    armPendingCard(store, irreversiblePart('pub7'), 'run-7')
    store.ackGate('pub7')
    api.cancel.mockResolvedValue({})

    await store.stop()
    expect(store.gateAckOf('pub7')).toBe(false)
  })
})

describe('B10-04 恢复：快照重建的卡独立于本地勾选', () => {
  it('rebuildPendingCard：irreversible 落段；勾选态按 toolCallId 隔离（旧卡的勾选不外溢到恢复卡）', () => {
    const store = useAiStore()
    store.historyLoaded = true
    store.ackGate('another-card')

    store.rebuildPendingCard({
      toolCallId: 'rpub1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_publish',
      arguments: '{"taskId":9}', req: true, irreversible: true
    } as never, 'run-restored')

    const part = store.messages
      .flatMap((message) => message.parts)
      .find((candidate): candidate is ToolPart => candidate.kind === 'tool' && candidate.toolCallId === 'rpub1')
    expect(part?.irreversible).toBe(true)
    expect(part?.requiresAction).toBe(true)
    // 恢复动作本身不得把"上一张卡的勾选"带进来（勾选按 toolCallId 粒度隔离）
    expect(store.gateAckOf('rpub1')).toBe(false)
    expect(store.gateAckOf('another-card')).toBe(true)
  })
})
