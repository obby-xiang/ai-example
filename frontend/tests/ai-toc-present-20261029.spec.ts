/**
 * ToC 前端呈现减负批（2026-10-29）：同一事实只留一个提示面 / 准备中阶段条 / GF 提交后只读锁定 /
 * 失效确认卡禁用决策 / 展开按本轮未终态 / 不可提交表单只读 / 技术词只进诊断 / 硬编码走 locale。
 *
 * <p>本文件只覆盖**呈现层**（AiPanel / FormRenderer / UploadHandoffCard / ContextPopover / locales），
 * 不动 `stores/ai.ts`（并行批）：所有现场都用 store 公开字段直接摆好，断言只看 DOM 与 locale 词条。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { applyLocale } from '@/locales'
import zhCN from '@/locales/zh-CN.json'
import enUS from '@/locales/en-US.json'
import ContextPopover from '@/components/AiPanel/ContextPopover.vue'
import UploadHandoffCard from '@/components/AiPanel/UploadHandoffCard.vue'
import type { ActiveGenerativeForm, ChatMessage, MessagePart, PendingToolCall, ToolPart } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  deleteHistory: vi.fn(),
  getContext: vi.fn(),
  retryModelStep: vi.fn()
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

const FORM_SPEC: FormSpec = {
  scenario: 'FILTER',
  title: '导出参数',
  fields: [
    { key: 'region', label: '导出范围', type: 'enum', required: true, options: [{ value: 'HB', label: '华北' }] },
    { key: 'note', label: '备注', type: 'text', required: false }
  ]
}

const FORM_ARGS = JSON.stringify({ form: FORM_SPEC })

function buildMessage(parts: MessagePart[], pendingCall: PendingToolCall | null = null, streaming = false): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-1',
    role: 'assistant',
    content: text,
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall,
    streaming,
    done: !streaming
  }
}

function activeForm(toolCallId: string, status: ActiveGenerativeForm['status']): ActiveGenerativeForm {
  return { toolCallId, runId: 'run-1', form: FORM_SPEC, status, error: null, values: { region: 'HB' }, fieldErrors: [] }
}

async function mountPanel(
  messages: ChatMessage[],
  options: { realForm?: boolean } = {}
): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  store.expanded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const global = options.realForm ? { stubs: { ...stubs, FormRenderer: false } } : { stubs }
  const wrapper = mount(AiPanel, { global })
  await nextTick()
  return wrapper
}

function zhString(path: string): string {
  const walk = (node: unknown): string => {
    if (typeof node === 'string') {
      return node
    }
    throw new Error(`not a string: ${path}`)
  }
  let cur: unknown = zhCN
  for (const key of path.split('.')) {
    cur = (cur as Record<string, unknown>)[key]
  }
  return walk(cur)
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
    memoryBackend: 'redis',
    sessionTtl: '24h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-10-29T08:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: 'sess-toc', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.confirm.mockResolvedValue(undefined)
  applyLocale('zh-CN')
})

describe('1 · 同一事实只留一个提示面', () => {
  it('挂起池 Alert 在时不叠同义通知；不被覆盖的通知照常显示', async () => {
    const store = useAiStore()
    store.poolSaturated = {
      code: 'SUSPEND_POOL_SATURATED',
      message: 'pool full',
      runId: 'run-1',
      poolSize: 4
    } as never
    store.noticeCode = 'ai.notice.poolSaturatedWithSize'
    store.noticeArgs = { size: 4 }
    store.noticeSeverity = 'error'
    store.notice = '挂起池已饱和（容量 4），请稍后重试。'

    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])])
    expect(wrapper.find('[data-testid="alert-pool-saturated"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="notice-bar"]').exists()).toBe(false)

    // 对照：换一条不被专属 Alert 覆盖的通知 ⇒ 通知槽照常显示（不做全局静音）
    store.noticeCode = null
    store.noticeArgs = null
    store.noticeSeverity = 'info'
    store.notice = '本轮已取消'
    await nextTick()
    expect(wrapper.find('[data-testid="notice-bar"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('工作区同步反馈只让成功回执让位；失败回执始终显示', async () => {
    const store = useAiStore()
    store.contextSync = { version: 3, summary: '选择了 2 个配置定义', at: 1, source: '界面' } as never
    // ① 失败回执（handled=false）是**另一件事**：不得被无关的「工作区已同步」压掉
    store.lastUiEventResult = { event: 'select_defs', handled: false, message: '当前没有进行中的任务' }
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])])
    expect(wrapper.find('[data-testid="panel-context-bar"]').text()).toContain('选择了 2 个配置定义')
    expect(wrapper.find('[data-testid="alert-ui-event"]').exists()).toBe(true)

    // ② 成功回执（handled=true）：同步反馈已在报同一件事 ⇒ 回执位让位
    store.lastUiEventResult = { event: 'set_conditions', handled: true, message: '已设置筛选条件' }
    await nextTick()
    expect(wrapper.find('[data-testid="alert-ui-event"]').exists()).toBe(false)

    // ③ 同步反馈退场后，回执位仍是唯一提示面
    store.contextSync = null
    await nextTick()
    expect(wrapper.find('[data-testid="alert-ui-event"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('接续耗尽路径的短断流通知同样由断流 Alert 承接', async () => {
    const store = useAiStore()
    store.streamInterrupted = true
    store.lastRoundOutcome = 'interrupted'
    store.noticeCode = 'ai.notice.interruptedKeepShort'
    store.noticeArgs = { reason: '断流' }
    store.noticeSeverity = 'warning'
    store.notice = '连接中断，结果可能不完整（断流）。已生成的内容保留。'

    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '部分回答', streaming: false }])])
    expect(wrapper.find('[data-testid="alert-interrupted"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="notice-bar"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('2 · 挂载准备中出阶段条', () => {
  it('preparing 阶段条显示已用秒数与重试出口，且不露 runId', async () => {
    const store = useAiStore()
    store.loading = true
    store.sessionId = 'sess-toc'
    store.activeRunId = 'secret-run-id'
    store.preparingRun = { runId: 'secret-run-id', startedAtMs: Date.now() - 4_000, elapsedMs: 4_000 }
    const retry = vi.spyOn(store, 'retryPreparing').mockResolvedValue(undefined)

    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)])
    const bar = wrapper.find('[data-testid="compaction-status"]')
    expect(bar.exists()).toBe(true)
    expect(bar.attributes('data-stage')).toBe('preparing')
    expect(bar.text()).toContain('准备')
    expect(bar.text()).toMatch(/已用 [45] 秒/)
    expect(wrapper.text()).not.toContain('secret-run-id')

    await wrapper.find('[data-testid="compaction-retry"]').trigger('click')
    expect(retry).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('压缩心跳期间出 compacting 阶段条', async () => {
    const store = useAiStore()
    store.loading = true
    store.compactionPhase = 'compacting'
    store.compactionStartedAtMs = Date.now() - 12_000
    store.compactionElapsedMs = 12_000

    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)])
    const bar = wrapper.find('[data-testid="compaction-status"]')
    expect(bar.exists()).toBe(true)
    expect(bar.attributes('data-stage')).toBe('compacting')
    expect(bar.text()).toMatch(/已用 1[23] 秒/)
    wrapper.unmount()
  })
})

describe('3 · 生成式表单提交后保留 submitting 只读态直到结局', () => {
  it('已提交未结局：同一张表单锁定、卡头已提交、无普通工具卡与 schema', async () => {
    const store = useAiStore()
    const part: ToolPart = {
      kind: 'tool',
      seg: 0,
      toolCallId: 'g1',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'running',
      args: FORM_ARGS,
      requiresAction: true,
      submittedValues: { region: 'HB', note: 'hello' }
    }
    const msg = buildMessage([part], { toolCallId: 'g1', name: 'generative_form', kind: 'FRONTEND', args: { form: FORM_SPEC }, rawArgs: FORM_ARGS, status: 'running' })
    msg.id = 'a-gf'
    store.activeRunId = 'run-1'
    // 提交后 store 已把活动表单切成 submitted（下一步由结局帧收口）
    store.activeForm = activeForm('g1', 'submitted')

    const wrapper = await mountPanel([msg])
    const card = wrapper.find('[data-testid="hitl-receipt"]')
    expect(card.exists()).toBe(true)
    expect(wrapper.find('[data-testid="tool-card-g1"]').exists()).toBe(false)
    expect(card.find('[data-testid="ro-summary-g1"]').text()).toContain('导出范围 华北')
    expect(wrapper.find('[data-testid="ro-badge-g1"]').text()).toContain('已提交')
    expect(wrapper.find('[data-testid="ro-form-g1"]').exists()).toBe(true)
    // 不闪 schema / JSON：参数键不上屏
    expect(wrapper.text()).not.toContain('"fields"')
    expect(wrapper.text()).not.toContain('scenario')

    // 结局帧到达（success）⇒ 卡头状态与卡体形态与「已提交」期一致（无二次换形）
    //（经 store 的响应式代理改段，确保真的重渲；改原始对象不会触发更新）
    const live = store.messages[0]!.parts[0] as ToolPart
    live.status = 'succeeded'
    live.requiresAction = false
    await nextTick()
    expect(wrapper.find('[data-testid="hitl-receipt"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ro-badge-g1"]').text()).toContain('已提交')
    expect(wrapper.find('[data-testid="ro-summary-g1"]').text()).toContain('导出范围 华北')
    expect(wrapper.find('[data-testid="ro-form-g1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('提交在途的只读表单在非成功结局仍回填提交值（真实 FormRenderer）', async () => {
    const store = useAiStore()
    const part: ToolPart = {
      kind: 'tool',
      seg: 0,
      toolCallId: 'g2',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'running',
      args: FORM_ARGS,
      requiresAction: true,
      submittedValues: { region: 'HB', note: '已提交的备注' }
    }
    const msg = buildMessage([part], { toolCallId: 'g2', name: 'generative_form', kind: 'FRONTEND', args: { form: FORM_SPEC }, rawArgs: FORM_ARGS, status: 'running' })
    msg.id = 'a-gf2'
    store.activeRunId = 'run-1'
    store.activeForm = activeForm('g2', 'submitted')

    const wrapper = await mountPanel([msg], { realForm: true })
    // 提交在途（结局未到）：卡体就是提交值
    expect((wrapper.find('[data-testid="ro-form-g2"] select').element as HTMLSelectElement).value).toBe('HB')
    expect((wrapper.find('[data-testid="ro-form-g2"] input[type="text"]').element as HTMLInputElement).value).toBe('已提交的备注')

    // 结局 = 执行失败：值不得被清空（摘要与卡体同源，不能卡头有值、卡体空表）
    //（经 store 的响应式代理改段，确保真的重渲）
    const live = store.messages[0]!.parts[0] as ToolPart
    live.status = 'failed'
    live.requiresAction = false
    await nextTick()
    expect(wrapper.find('[data-testid="ro-summary-g2"]').text()).toContain('导出范围 华北')
    expect((wrapper.find('[data-testid="ro-form-g2"] select').element as HTMLSelectElement).value).toBe('HB')
    expect((wrapper.find('[data-testid="ro-form-g2"] input[type="text"]').element as HTMLInputElement).value).toBe('已提交的备注')

    // 结局 = 超时（结果回灌丢失，段被收成 expired）：同样保留提交值
    live.status = 'expired'
    await nextTick()
    expect((wrapper.find('[data-testid="ro-form-g2"] select').element as HTMLSelectElement).value).toBe('HB')
    expect((wrapper.find('[data-testid="ro-form-g2"] input[type="text"]').element as HTMLInputElement).value).toBe('已提交的备注')
    wrapper.unmount()
  })

  it('提交在途不再显示「等待你操作」，走既有工作中语义', async () => {
    const store = useAiStore()
    const part: ToolPart = {
      kind: 'tool',
      seg: 0,
      toolCallId: 'g3',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'running',
      args: FORM_ARGS,
      requiresAction: true,
      submittedValues: { region: 'HB' }
    }
    const msg = buildMessage([part], { toolCallId: 'g3', name: 'generative_form', kind: 'FRONTEND', args: { form: FORM_SPEC }, rawArgs: FORM_ARGS, status: 'running' }, true)
    msg.id = 'a-gf3'
    store.activeRunId = 'run-1'
    store.activeForm = activeForm('g3', 'submitted')
    // 挂起语义仍在（runPhase=parked），但人这一侧已经没有待办
    store.suspended = true
    store.loading = true

    const wrapper = await mountPanel([msg])
    expect(wrapper.find('[data-testid="park-waiting"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="loading-breath"]').text()).toContain('工作中')
    wrapper.unmount()

    // 对照：仍在填写（无提交值）的挂起表单照旧报「等待你操作」
    const store2 = useAiStore()
    const pendingPart: ToolPart = {
      kind: 'tool',
      seg: 0,
      toolCallId: 'g4',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'pending',
      args: FORM_ARGS,
      requiresAction: true
    }
    const pendingMsg = buildMessage([pendingPart], { toolCallId: 'g4', name: 'generative_form', kind: 'FRONTEND', args: { form: FORM_SPEC }, rawArgs: FORM_ARGS, status: 'pending' }, true)
    pendingMsg.id = 'a-gf4'
    store2.activeForm = activeForm('g4', 'filling')
    store2.suspended = true
    store2.loading = false
    const wrapper2 = await mountPanel([pendingMsg])
    expect(wrapper2.find('[data-testid="park-waiting"]').exists()).toBe(true)
    wrapper2.unmount()
  })
})

describe('4 · 失效确认卡禁用批准与拒绝', () => {
  function pendingGate(): ChatMessage {
    return buildMessage(
      [{ kind: 'tool', seg: 0, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', requiresAction: true }],
      { toolCallId: 'c1', name: 'start_export', kind: 'CONFIRM', args: { taskId: 1 }, rawArgs: '{"taskId":1}', status: 'pending' }
    )
  }

  it('本轮运行态已失效 ⇒ 批准与拒绝一并禁用；未失效时两钮可用', async () => {
    const store = useAiStore()
    store.activeRunId = 'run-x'
    store.runExpiry = { runId: 'run-x', remainingMs: 1_000, expiresAt: null, receivedAtMs: Date.now() - 5_000 }
    const wrapper = await mountPanel([pendingGate()])

    expect(wrapper.find('[data-testid="run-expiry-expired"]').exists()).toBe(true)
    const buttons = wrapper.find('[data-testid="gate-actions"]').findAll('button')
    expect(buttons).toHaveLength(2)
    for (const button of buttons) {
      expect(button.attributes('disabled')).toBeDefined()
    }
    wrapper.unmount()

    // 负例：同一张卡，寿命未到时两钮可用
    const store2 = useAiStore()
    store2.activeRunId = 'run-y'
    store2.runExpiry = { runId: 'run-y', remainingMs: 600_000, expiresAt: null, receivedAtMs: Date.now() }
    const wrapper2 = await mountPanel([pendingGate()])
    const live = wrapper2.find('[data-testid="gate-actions"]').findAll('button')
    for (const button of live) {
      expect(button.attributes('disabled')).toBeUndefined()
    }
    wrapper2.unmount()
  })

  it('失效门卡不留下照做不了的「请先勾选确认项」死指令', async () => {
    const store = useAiStore()
    store.activeRunId = 'run-irr'
    store.runExpiry = { runId: 'run-irr', remainingMs: 1_000, expiresAt: null, receivedAtMs: Date.now() - 5_000 }
    const msg = buildMessage(
      [{ kind: 'tool', seg: 0, toolCallId: 'c9', name: 'start_publish', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":3}', requiresAction: true, irreversible: true }],
      { toolCallId: 'c9', name: 'start_publish', kind: 'CONFIRM', args: { taskId: 3 }, rawArgs: '{"taskId":3}', status: 'pending' }
    )
    const wrapper = await mountPanel([msg])
    // 勾选框已禁用 ⇒ 提示行必须一起退场（否则是指着禁用控件的死指令）
    expect(wrapper.find('[data-testid="gate-ack-box"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="gate-ack-hint"]').exists()).toBe(false)
    // 必要出口仍在：失效说明 + 刷新状态 / 新建会话
    expect(wrapper.find('[data-testid="run-expiry-expired"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="run-expiry-reload"]').exists()).toBe(true)
    wrapper.unmount()

    // 负例：未失效的不可撤销卡照旧给提示行
    const store2 = useAiStore()
    store2.activeRunId = 'run-irr2'
    store2.runExpiry = { runId: 'run-irr2', remainingMs: 600_000, expiresAt: null, receivedAtMs: Date.now() }
    const wrapper2 = await mountPanel([msg])
    expect(wrapper2.find('[data-testid="gate-ack-hint"]').exists()).toBe(true)
    wrapper2.unmount()
  })
})

describe('5 · 确认卡展开按本轮未终态', () => {
  it('确认卡展开按本轮未终态：决策在途且本轮未收口时不折叠，收口后才收成卡头行', async () => {
    const store = useAiStore()
    const msg = buildMessage(
      [{ kind: 'tool', seg: 0, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', requiresAction: true }],
      { toolCallId: 'c1', name: 'start_export', kind: 'CONFIRM', args: { taskId: 1 }, rawArgs: '{"taskId":1}', status: 'pending' }
    )
    msg.id = 'a-park'
    store.activeRunId = 'run-park'
    store.suspended = true
    store.loading = false

    const wrapper = await mountPanel([msg])
    const actions = wrapper.find('[data-testid="gate-actions"]').findAll('button')
    await actions[1]!.trigger('click')
    await nextTick()

    // 批准后段切只读（status running / decision approved），本轮仍在挂起语义里 ⇒ 卡体保持展开
    const card = wrapper.find('[data-testid="hitl-receipt"]')
    expect(card.exists()).toBe(true)
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.find('[data-testid="ro-badge-c1"]').text()).toContain('已批准')

    // 决策已送达、挂起已清掉，而重连尚未建立起 loading —— `runPhase` 短暂回到 idle，
    // 但这一轮的决策仍在途（结局帧未到）⇒ 卡体不得折叠再展开
    store.suspended = false
    store.loading = false
    await nextTick()
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').not.toContain('display: none')

    // 结局帧撤掉在途态 ⇒ 本轮收口 ⇒ 才收成卡头行
    store.clearGateSubmission('c1')
    await nextTick()
    expect(wrapper.find('[data-testid="ro-body-c1"]').attributes('style') ?? '').toContain('display: none')
    wrapper.unmount()
  })

  it('历史恢复期（restoringRecovery）不得把已决只读卡异常展开再折叠', async () => {
    const store = useAiStore()
    // 镜像落屏：末条消息里的已决只读卡（刷新/重新载入后的常态）
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'p9', name: 'start_publish', kind2: 'CONFIRM', status: 'succeeded', args: '{"taskId":3}', requiresAction: false, decision: 'approved', decidedAt: 1_700_000_000_000 }
    ])
    msg.id = 'a-hist'
    // 恢复在途：restoringRecovery=true，但既没有收流也没有挂起
    store.loading = false
    store.suspended = false
    store.restoringRecovery = true

    const wrapper = await mountPanel([msg])
    // 恢复期不是"本轮未终态"：卡保持既有的折叠形态（不先展开、HTTP 回来再折叠）
    expect(wrapper.find('[data-testid="ro-body-p9"]').attributes('style') ?? '').toContain('display: none')

    // 恢复结束：形态不变（无二次换形）
    store.restoringRecovery = false
    await nextTick()
    expect(wrapper.find('[data-testid="ro-body-p9"]').attributes('style') ?? '').toContain('display: none')
    wrapper.unmount()
  })
})

describe('6 · 不可提交表单字段只读', () => {
  it('本轮运行态已失效 ⇒ 表单控件只读、提交禁用、取消仍可用', async () => {
    const store = useAiStore()
    const part: ToolPart = {
      kind: 'tool',
      seg: 0,
      toolCallId: 'g1',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'pending',
      args: FORM_ARGS,
      requiresAction: true
    }
    const msg = buildMessage([part], { toolCallId: 'g1', name: 'generative_form', kind: 'FRONTEND', args: { form: FORM_SPEC }, rawArgs: FORM_ARGS, status: 'pending' })
    msg.id = 'a-lock'
    store.activeRunId = 'run-lock'
    store.activeForm = activeForm('g1', 'filling')
    store.runExpiry = { runId: 'run-lock', remainingMs: 1_000, expiresAt: null, receivedAtMs: Date.now() - 5_000 }

    const wrapper = await mountPanel([msg], { realForm: true })
    const form = wrapper.find('[data-testid="gf-form-g1"]')
    expect(form.exists()).toBe(true)
    const select = form.find('select')
    expect(select.attributes('disabled')).toBeDefined()
    expect(form.find('input[type="text"]').attributes('disabled')).toBeDefined()
    expect(form.find('[data-testid="gf-submit"]').attributes('disabled')).toBeDefined()
    expect(form.find('[data-testid="gf-locked-hint"]').exists()).toBe(true)
    // 取消是本地收口出口，不随失效一起锁死
    expect(form.find('[data-testid="gf-cancel"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('7 · 技术词只进诊断', () => {
  it('首屏不出现会话 id / 数据版本；两者收进诊断详情', async () => {
    const store = useAiStore()
    const workspace = useWorkspaceStore()
    store.sessionId = 'abcdef1234567890'
    workspace.dataVersion = 7
    store.contextSync = { version: 7, summary: '选择了 2 个配置定义', at: 1, source: '界面' } as never

    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])])
    // 首屏 = 可见那一行（`panel-context-line`）；诊断详情是折叠区，不算首屏
    const line = wrapper.find('[data-testid="panel-context-line"]')
    expect(line.text()).not.toContain('abcdef12')
    expect(line.text()).not.toContain('数据版本')
    const synced = wrapper.find('[data-testid="context-synced"]')
    // 「已同步」只留人话摘要（不带版本号 / 来源机读词）
    expect(synced.text()).toContain('选择了 2 个配置定义')
    expect(synced.text()).not.toContain('v7')
    expect(synced.text()).not.toContain('界面')

    const diagnostics = wrapper.find('[data-testid="panel-diagnostics"]')
    expect(diagnostics.exists()).toBe(true)
    expect(diagnostics.text()).toContain('abcdef12')
    expect(diagnostics.text()).toContain('7')
    wrapper.unmount()
  })

  it('AI 用户面词条不含 Redis / runId / 实例 / 线程池（双语）', () => {
    const banned = ['Redis', 'runId', '实例', '线程池', 'epoch', 'fence', '数据版本', '会话 id']
    const enBanned = ['Redis', 'runId', 'execution instance', 'thread pool', 'epoch', 'fence', 'Data version', 'Session id']
    const zhKeys = [
      'ai.notice.redisUnavailable',
      'ai.notice.missingRunId',
      'ai.panel.toolResult.TOOL_ALREADY_CLAIMED',
      'ai.panel.poolSaturatedDetail',
      'ai.panel.suspendPoolFull'
    ]
    for (const key of zhKeys) {
      const text = zhString(key)
      for (const word of banned) {
        expect(`${key} => ${text}`).not.toContain(word)
      }
    }
    const walk = (node: unknown, path: string): void => {
      if (typeof node === 'string') {
        if (path.startsWith('ai.panel.session') || path.startsWith('ai.panel.dataVersion')) {
          return
        }
        for (const word of enBanned) {
          expect(`${path} => ${node}`).not.toContain(word)
        }
        return
      }
      if (node !== null && typeof node === 'object') {
        for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
          walk(value, `${path}.${key}`)
        }
      }
    }
    walk((enUS as Record<string, unknown>).ai, 'ai')
  })

  it('池饱和两条通知也不带池/容量术语（双语，503 无 body 时的回落路径）', () => {
    const read = (tree: unknown, key: string): string => {
      let cur: unknown = tree
      for (const part of key.split('.')) {
        cur = (cur as Record<string, unknown>)[part]
      }
      return cur as string
    }
    for (const key of ['ai.notice.poolSaturated', 'ai.notice.poolSaturatedWithSize']) {
      const zh = read(zhCN, key)
      for (const word of ['挂起池', '线程池', '容量', '{size}']) {
        expect(zh, `${key} 的 zh 文案`).not.toContain(word)
      }
      const en = read(enUS, key)
      for (const word of ['suspended-run', 'pool', 'capacity', '{size}']) {
        expect(en.toLowerCase(), `${key} 的 en 文案`).not.toContain(word)
      }
    }
  })
})

describe('8 · 硬编码走 locale', () => {
  it('UploadHandoffCard 英文界面不夹中文（兜底标签 / 三态说明 / 选择提示）', async () => {
    applyLocale('en-US')
    const wrapper = mount(UploadHandoffCard, { props: { status: 'missing' } })
    const text = wrapper.find('[data-testid="upload-handoff"]').text()
    expect(text).not.toMatch(/[\u4e00-\u9fa5]/)
    expect(text).toContain('import')
    wrapper.unmount()

    const degraded = mount(UploadHandoffCard, { props: { status: 'degraded' } })
    expect(degraded.find('[data-testid="upload-handoff"]').text()).not.toMatch(/[\u4e00-\u9fa5]/)
    degraded.unmount()
  })

  it('ContextPopover 上限未知文案走 locale（英文界面不夹中文）', async () => {
    applyLocale('en-US')
    const store = useAiStore()
    store.contextUsage = { usedTokens: 1000, limitTokens: null, percent: null, level: 'none', est: false, circuit: 'closed' }
    store.contextBreakdown = []
    store.contextStatus = 'ready'
    const wrapper = mount(ContextPopover)
    await nextTick()
    const text = wrapper.find('[data-testid="context-popover"]').text()
    expect(text).not.toMatch(/[\u4e00-\u9fa5]/)
    wrapper.unmount()
  })
})

describe('9 · 首帧超时自动恢复在途时面板不回稿', () => {
  it('恢复在途（ai.loading）时不回稿，避免与恢复提示重复', async () => {
    const store = useAiStore()
    const wrapper = await mountPanel([])
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('原始草稿')

    let resolveSend!: (value: { outcome: 'interrupted'; userMessageId: string }) => void
    const pending = new Promise<{ outcome: 'interrupted'; userMessageId: string }>((resolve) => {
      resolveSend = resolve
    })
    vi.spyOn(store, 'send').mockReturnValue(pending)
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await nextTick()
    expect((box.element as HTMLTextAreaElement).value).toBe('')

    // 首帧超时的自动恢复已经接管本轮（连接仍在跑）⇒ 不再把草稿塞回输入框
    store.loading = true
    resolveSend({ outcome: 'interrupted', userMessageId: 'u-1' })
    await flushPromises()

    expect((box.element as HTMLTextAreaElement).value).toBe('')
    expect(store.loading).toBe(true)
    wrapper.unmount()
  })

  it('真失败（loading=false）照旧回稿并聚焦', async () => {
    const store = useAiStore()
    const wrapper = await mountPanel([])
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('原始草稿')
    const focusSpy = vi.spyOn(box.element as HTMLTextAreaElement, 'focus')

    vi.spyOn(store, 'send').mockResolvedValue({ outcome: 'error', userMessageId: 'u-2' })
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect((box.element as HTMLTextAreaElement).value).toBe('原始草稿')
    expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true })
    wrapper.unmount()
  })
})
