/**
 * ToC 前端呈现减负批（2026-10-29）：同一事实只留一个提示面 / 准备中阶段条 / GF 提交后只读锁定 /
 * 失效确认卡禁用决策 / 展开按本轮未终态 / 不可提交表单只读 / 技术词只进诊断 / 硬编码走 locale。
 *
 * <p>本文件只覆盖**呈现层**（AiPanel / FormRenderer / UploadHandoffCard / ContextPopover / locales），
 * 不动 `stores/ai.ts`（并行批）：所有现场都用 store 公开字段直接摆好，断言只看 DOM 与 locale 词条。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
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

  it('工作区已有同步反馈时不叠 ui_event 回执；没有同步反馈时回执照常显示', async () => {
    const store = useAiStore()
    store.contextSync = { version: 3, summary: '选择了 2 个配置定义', at: 1, source: '界面' } as never
    store.lastUiEventResult = { event: 'select_defs', handled: false, message: '当前没有进行中的任务' }

    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])])
    expect(wrapper.find('[data-testid="panel-context-bar"]').text()).toContain('选择了 2 个配置定义')
    expect(wrapper.find('[data-testid="alert-ui-event"]').exists()).toBe(false)

    // 对照：同步反馈退场后，回执位仍是唯一提示面
    store.contextSync = null
    await nextTick()
    expect(wrapper.find('[data-testid="alert-ui-event"]').exists()).toBe(true)
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
    part.status = 'succeeded'
    part.requiresAction = false
    await nextTick()
    expect(wrapper.find('[data-testid="hitl-receipt"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ro-badge-g1"]').text()).toContain('已提交')
    expect(wrapper.find('[data-testid="ro-summary-g1"]').text()).toContain('导出范围 华北')
    expect(wrapper.find('[data-testid="ro-form-g1"]').exists()).toBe(true)
    wrapper.unmount()
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
