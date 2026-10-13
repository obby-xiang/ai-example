/**
 * 对话时序包 批2（子包②权威翻转）→ 批4④（A1' 模板合并 / D-C7 死位清除 / A4' reason 落点 / D-C9 多挂起段）：
 * `AiPanel.vue` 渲染层的回归锁。
 *
 * 这一层的风险不在纯函数（parts-reducer.spec.ts / blocks.spec.ts 已覆盖），而在**模板本身**：
 * 块分派顺序、挂起卡并入工具卡后的单卡结构（A1'）、死位文案零命中（A9'）、
 * N10（复制按钮的显示判据改 parts，不得再读 `m.content`，防"有段渲染但按钮不出现"的发散）。
 *
 * 挂载方式：Element Plus 组件全部用轻量 stub（本用例只关心段序与出现与否，不测视觉）；
 * `onMounted` 的 health/loadHistory 取数用 `@/api/ai` 桩屏蔽。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ActiveGenerativeForm, ChatMessage, MessagePart, PendingToolCall, ToolPart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn()
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

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h', confirmTimeoutSeconds: 120, frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0, sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z' })
})

/** 手搭一条消息（含派生投影：两侧自洽 = deriveProjections 的结果）。 */
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

/** A4' 夹具：造一个指定状态的 activeForm（form schema 本体与本锁无关，走 never 桩）。 */
function makeActiveForm(toolCallId: string, status: ActiveGenerativeForm['status']): ActiveGenerativeForm {
  return { toolCallId, runId: 'run-1', form: {} as never, status, error: null, deadline: null }
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

describe('AiPanel 按 parts 渲染（批2 权威翻转 → 批4④ 块分派）', () => {
  it('text → tool → text 的段序在 DOM 里保持一致（工具夹在正文中间不再沉底）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'text', seg: 0, text: '先说话', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'c1', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: 'ok', requiresAction: false },
        { kind: 'text', seg: 2, text: '后说话', streaming: false }
      ])
    ])
    const rendered = wrapper.text()

    const firstText = rendered.indexOf('先说话')
    const toolCard = rendered.indexOf('列出配置定义')
    const secondText = rendered.indexOf('后说话')
    expect(firstText).toBeGreaterThanOrEqual(0)
    expect(toolCard).toBeGreaterThan(firstText)
    expect(secondText).toBeGreaterThan(toolCard)
    wrapper.unmount()
  })

  it("A1'：挂起卡并入工具卡（单卡段驱动）——工具名全 DOM 只出一次、确认子块在持 testid 的同一张卡内", async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'text', seg: 0, text: '', streaming: false },
          { kind: 'tool', seg: 1, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', timeoutSeconds: 120, requiresAction: true },
          { kind: 'tool', seg: 2, toolCallId: 'c2', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', requiresAction: false }
        ],
        { toolCallId: 'c1', name: 'start_export', kind: 'CONFIRM', args: { taskId: 1 }, rawArgs: '{"taskId":1}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const html = wrapper.html()
    const cards = wrapper.findAll('[data-testid^="pending-card-"]')
    expect(cards).toHaveLength(1)
    expect(cards[0]?.attributes('data-testid')).toBe('pending-card-c1')

    // A1'②：卡头统一为段单一来源 —— 旧结构（工具卡 + 投影挂起卡并列）工具名出现两次，合并后恰一次
    expect(html.split('启动导出作业').length - 1).toBe(1)
    // 确认子块（CONFIRM pending）在持 testid 的同一张卡内
    expect(cards[0]!.html()).toContain('确认执行')

    // D-C10（原 :106-115 位置断言改写）：块分派下挂起卡（含确认子块）仍在 c2 工具卡之前
    const pendingCard = html.indexOf('pending-card-c1')
    const otherTool = html.indexOf('列出任务')
    expect(pendingCard).toBeGreaterThanOrEqual(0)
    expect(otherTool).toBeGreaterThan(pendingCard)
    wrapper.unmount()
  })

  it('D-C9：同一消息复数 requiresAction 段逐段各出一卡（旧模板单槽只出末段卡）', async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'text', seg: 0, text: '', streaming: false },
          { kind: 'tool', seg: 1, toolCallId: 'p1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true },
          { kind: 'tool', seg: 2, toolCallId: 'p2', name: 'delete_job', kind2: 'CONFIRM', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true }
        ]
      )
    ])
    const cards = wrapper.findAll('[data-testid^="pending-card-"]')
    expect(cards).toHaveLength(2)
    const testids = cards.map((c) => c.attributes('data-testid')).sort()
    expect(testids).toEqual(['pending-card-p1', 'pending-card-p2'])
    wrapper.unmount()
  })

  it("A9'①③：GF 非宿主 v-else 三 tag（表单已取消 / 表单已提交 / 表单状态已丢失）整列删除（D-C7）", async () => {
    // 夹具：GF 挂起段 + 宿主条件不成立（activeForm = null ⇒ liveForm = null ⇒ 旧结构走 v-else 出三 tag）
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true }
        ],
        { toolCallId: 'g1', name: 'generative_form', kind: 'FRONTEND', args: {}, rawArgs: '{}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const text = wrapper.text()
    expect(text).not.toContain('表单已取消')
    expect(text).not.toContain('表单已提交，等待回执')
    expect(text).not.toContain('表单状态已丢失')
    wrapper.unmount()
  })

  it("A9'①（cancelled 变体）：activeForm=cancelled 时旧位「表单已取消」tag 不再出现", async () => {
    const store = useAiStore()
    store.messages = [
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true }
        ],
        { toolCallId: 'g1', name: 'generative_form', kind: 'FRONTEND', args: {}, rawArgs: '{}', timeoutSeconds: 120, status: 'pending' }
      )
    ]
    store.historyLoaded = true
    store.activeForm = makeActiveForm('g1', 'cancelled')
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('表单已取消')
    wrapper.unmount()
  })

  it("A9'⑤：通用 FRONTEND 挂起段不再出空壳挂起卡（合并后单卡无子块）", async () => {
    // probe.tool 非 GF：旧结构 = 工具卡 + 空壳挂起卡（卡头重复）；合并后工具名恰出现一次
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'f1', name: 'probe.tool', kind2: 'FRONTEND', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true }
        ],
        { toolCallId: 'f1', name: 'probe.tool', kind: 'FRONTEND', args: {}, rawArgs: '{}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    expect(wrapper.html().split('probe.tool').length - 1).toBe(1)
    wrapper.unmount()
  })

  it("A9'④：v1 镜像组合面（pendingCall=pending × 段 status=expired）卡面仍显示「会话已失效」（段状态 tag 承接）", async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'e1', name: 'probe.tool', kind2: 'FRONTEND', status: 'expired', args: '{}', requiresAction: true }
        ],
        { toolCallId: 'e1', name: 'probe.tool', kind: 'FRONTEND', args: {}, rawArgs: '{}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    expect(wrapper.text()).toContain('会话已失效')
    wrapper.unmount()
  })

  it("R-144 还原锚：段 status=blocked ⇒ 渲染「超出当前范围」（tag 色 warning 由 toolTagType 承担，文案面锁定）", async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'b1', name: 'probe.tool', kind2: 'CONFIRM', status: 'blocked', args: '{}', requiresAction: false }
        ],
        null
      )
    ])
    expect(wrapper.text()).toContain('超出当前范围')
    wrapper.unmount()
  })

  it("TS-3 / A4'：GF 取消回灌失败文案落段 reason 位（G-C2 语境文案可见，D11 文案钉死）", async () => {
    const store = useAiStore()
    store.messages = [
      buildMessage([
        { kind: 'text', seg: 0, text: '表单轮', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', args: '{}', requiresAction: false }
      ])
    ]
    store.historyLoaded = true
    store.activeForm = makeActiveForm('g1', 'filling')
    api.frontendToolResult.mockReset()
    api.frontendToolResult.mockRejectedValue(new Error('网络异常'))
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    await store.cancelGenerativeForm(false)
    await wrapper.vm.$nextTick()

    // D-C2 形状：段 reason 写入（同源自 form.error，D11 文案逐字）
    const segment = store.messages[0]!.parts.find((p) => p.kind === 'tool' && p.toolCallId === 'g1')
    expect(segment).toMatchObject({ reason: '取消回灌未送达（网络异常），后端将在超时后自动收尾' })
    // G-C2：语境文案对用户可见（段 reason 位渲染，非 notice 单槽位）
    expect(wrapper.text()).toContain('取消回灌未送达（网络异常），后端将在超时后自动收尾')
    wrapper.unmount()
  })

  it('N10：复制按钮的显示判据取 parts（正文段非空即出按钮），不随 m.content 发散', async () => {
    // 合成态：段里有正文但派生投影未跟上 —— 渲染真值（parts）必须说话
    const inconsistent = buildMessage([{ kind: 'text', seg: 0, text: '段里有正文', streaming: false }])
    inconsistent.content = ''
    const empty = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: false }])
    empty.content = '投影里有正文但段是空的'

    const wrapper = await mountPanel([inconsistent, empty])
    const buttons = wrapper.findAll('[data-testid^="copy-msg-"]')
    expect(buttons).toHaveLength(1)
    expect(buttons[0]?.attributes('data-testid')).toBe('copy-msg-a-1')
    expect(wrapper.text()).toContain('段里有正文')
    wrapper.unmount()
  })

  it('R-157 #3：生成中指示器接力 —— 首 token 前呼吸点+「工作中…」，流式中打字机光标', async () => {
    const typing = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)
    typing.id = 'a-typing'
    const speaking = buildMessage([{ kind: 'text', seg: 0, text: '正在说', streaming: true }], null, true)
    speaking.id = 'a-speaking'
    const settled = buildMessage([{ kind: 'text', seg: 0, text: '说完了', streaming: false }], null, false)
    settled.id = 'a-settled'

    const wrapper = await mountPanel([typing, speaking, settled])
    // 首 token 前：卡片下方呼吸点（一点 + 工作中…），无打字机光标
    const breath = wrapper.findAll('[data-testid="loading-breath"]')
    expect(breath).toHaveLength(1)
    expect(breath[0].text()).toContain('工作中…')
    expect(breath[0].find('.dot').exists()).toBe(true)
    // 流式中有正文：末尾打字机光标，无呼吸点、无旧三点动画
    expect(wrapper.findAll('[data-testid="stream-caret"]')).toHaveLength(1)
    expect(wrapper.findAll('.typing')).toHaveLength(0)
    expect(wrapper.text()).toContain('正在说')
    // 终态：两者皆无
    const settledHtml = wrapper.find('[data-testid="msg-bubble-a-settled"]').html()
    expect(settledHtml).not.toContain('caret')
    wrapper.unmount()
  })
})

/**
 * 批4④ A5'/D4/D6/D-C6：块分派渲染与展开态三态（TS-2）。
 */
describe('批4④：折叠块渲染与展开态三态承诺', () => {
  it('R-154 单卡形态：正文 → 3 个连续工具 → 正文 ⇒ 三张独立工具卡（组壳已拆），正文永不进卡', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'text', seg: 0, text: '前言', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'a', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', requiresAction: false },
        { kind: 'tool', seg: 2, toolCallId: 'b', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', requiresAction: false },
        { kind: 'tool', seg: 3, toolCallId: 'c', name: 'read_job', kind2: 'BACKEND', status: 'succeeded', requiresAction: false },
        { kind: 'text', seg: 4, text: '后语', streaming: false }
      ])
    ])
    // 组壳形态彻底拆除：data-activity-group 零命中；一卡一 testid
    expect(wrapper.findAll('[data-activity-group]')).toHaveLength(0)
    const cards = wrapper.findAll('[data-testid^="tool-card-"]')
    expect(cards).toHaveLength(3)
    expect(cards.map((c) => c.attributes('data-testid'))).toEqual(['tool-card-a', 'tool-card-b', 'tool-card-c'])
    // 卡头折叠单行 = 工具名 + 状态 tag（「已成功」在折叠行可见）
    expect(cards[0].text()).toContain('列出任务')
    expect(cards[0].text()).toContain('已成功')
    // 正文永不进卡：卡内不含前言/后语文案；段序保持（a 卡在两段正文之间）
    expect(cards[0].text()).not.toContain('前言')
    expect(cards[0].text()).not.toContain('后语')
    const rendered = wrapper.text()
    expect(rendered.indexOf('后语')).toBeGreaterThan(rendered.indexOf('列出任务'))
    // 全部已终态 ⇒ 每卡默认折叠（v-show 隐藏）
    expect(wrapper.find('[data-testid="block-toggle-a-1:a"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="block-body-a-1:a"]').attributes('style')).toContain('display: none')
    wrapper.unmount()
  })

  it('D6②改判（R-154）：手动展开的卡，后续帧使其 defaultExpanded 转折叠（running→succeeded）也不得自动收起', async () => {
    const store = useAiStore()
    const message = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'run1', name: 'list_tasks', kind2: 'BACKEND', status: 'running', requiresAction: false },
      { kind: 'tool', seg: 1, toolCallId: 'run2', name: 'read_job', kind2: 'BACKEND', status: 'running', requiresAction: false }
    ])
    store.messages = [message]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    // R-154：running 卡默认折叠 —— 手动展开写入显式态
    const toggle = wrapper.find('[data-testid="block-toggle-a-1:run1"]')
    await toggle.trigger('click')
    expect(store.expandedBlocks['a-1:run1']).toBe(true)
    expect(wrapper.find('[data-testid="block-body-a-1:run1"]').attributes('style') ?? '').not.toContain('display: none')
    // 帧到达：running → succeeded（未触碰卡将回落默认折叠；本卡手动展开必须保持）
    const segment = message.parts.find((p) => p.kind === 'tool' && p.toolCallId === 'run1') as ToolPart
    const updated: MessagePart = { ...segment, status: 'succeeded' }
    message.parts.splice(message.parts.indexOf(segment), 1, updated)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="block-body-a-1:run1"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()
  })

  it('D6②反向：用户手动展开已终态组后，后续帧不得使其自动折叠', async () => {
    const store = useAiStore()
    const message = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'done1', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', requiresAction: false },
      { kind: 'tool', seg: 1, toolCallId: 'done2', name: 'read_job', kind2: 'BACKEND', status: 'succeeded', requiresAction: false }
    ])
    store.messages = [message]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    const toggle = wrapper.find('[data-testid="block-toggle-a-1:done1"]')
    await toggle.trigger('click')
    expect(store.expandedBlocks['a-1:done1']).toBe(true)
    const group = wrapper.find('[data-testid="block-body-a-1:done1"]')
    expect(group.attributes('style') ?? '').not.toContain('display: none')
    // 帧到达：新工具段追加 —— R-154 单卡化下它是第三张独立卡，done1 卡保持展开
    message.parts.push({ kind: 'tool', seg: 2, toolCallId: 'done3', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', requiresAction: false })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="block-body-a-1:done1"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()
  })

  it("A6'/D4：思考链受控折叠 —— 默认折叠、手动展开后 DOM 重建（重挂载）不丢（R-140①-6 消解）", async () => {
    const store = useAiStore()
    store.messages = [
      buildMessage([{ kind: 'reasoning', seg: 0, text: ' lengthy 思考内容', streaming: false }])
    ]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    // D-C8：默认折叠（v-show 隐藏；wrapper.text() 含隐藏文本，须看 style）
    expect(wrapper.find('[data-testid="reasoning-body-a-1:reasoning-0"]').attributes('style')).toContain('display: none')
    const toggle = wrapper.find('[data-testid="reasoning-toggle-a-1:reasoning-0"]')
    await toggle.trigger('click')
    expect(wrapper.find('[data-testid="reasoning-body-a-1:reasoning-0"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()

    // DOM 重建：同 store 重挂载 —— 展开态来自 store，不丢
    const wrapper2 = mount(AiPanel, { global: { stubs } })
    await wrapper2.vm.$nextTick()
    expect(wrapper2.find('[data-testid="reasoning-body-a-1:reasoning-0"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper2.unmount()
  })

  it('R-154 改判：段 reason 非空的卡也默认折叠（D-C1 档退役）；展开后 reason 文案可见（v-show DOM 恒在）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'x1', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', requiresAction: false },
        { kind: 'tool', seg: 1, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', requiresAction: false, reason: '取消回灌未送达（网络异常），后端将在超时后自动收尾' },
        { kind: 'tool', seg: 2, toolCallId: 'x2', name: 'read_job', kind2: 'BACKEND', status: 'succeeded', requiresAction: false }
      ])
    ])
    // R-154：reason 非空不再是默认展开判据 —— 三张卡各自独立、均默认折叠
    expect(wrapper.findAll('[data-testid^="tool-card-"]')).toHaveLength(3)
    expect(wrapper.find('[data-testid="block-body-a-1:g1"]').attributes('style')).toContain('display: none')
    // 错误位（R-154 #6）DOM 恒在：文案可见性经展开达成，text() 含隐藏文本
    expect(wrapper.text()).toContain('取消回灌未送达（网络异常），后端将在超时后自动收尾')
    // 点击卡头展开 ⇒ reason 错误位露出（卡内错误态：图标 + 红底 + 文案）
    await wrapper.find('[data-testid="block-toggle-a-1:g1"]').trigger('click')
    expect(wrapper.find('[data-testid="block-body-a-1:g1"]').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.find('[data-testid="tool-error"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it("D4：expandedBlocks 三态写读 + resetSession 清空（跨会话不串味；会话内内存态，不进镜像）", async () => {
    const store = useAiStore()
    store.setBlockExpanded('g1', true)
    store.setBlockExpanded('g2', false)
    expect(store.expandedBlocks).toEqual({ g1: true, g2: false })
    await store.resetSession()
    expect(store.expandedBlocks).toEqual({})
  })
})

/**
 * 对话时序包 批3③b（D6/D7/D8）→ 批4④（A1' 合并后结构适配 + G-C5 卡内容断言）：「同一 toolCallId 全局唯一一张卡」渲染面三路锁。
 * 计数口径：pending-card-*（挂起卡，A1' 后 = requiresAction 段的单卡 testid）+ 全消息域同 id 工具段数 —— **锁单卡，不锁状态正确**
 * （历史整替路的 succeeded 是 D7(B) 登记的占位失真，R-144 修复前不得以状态断言此路）。
 */
describe('批3③b：一卡全生命周期 —— 重投 / 新宿主 / 历史整替 三路单卡锁', () => {
  /** 带一个已决（unknown）FRONTEND 工具段的旧消息。 */
  function messageWithDecidedTool(): ChatMessage {
    return buildMessage([
      { kind: 'text', seg: 0, text: '上一轮正文', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId: 'r1', name: 'probe.tool', kind2: 'FRONTEND',
        status: 'unknown', args: '{}', result: '本次调用结局未知', reason: null, requiresAction: false
      }
    ])
  }

  it('重投路：已决段重投 frontend_tool_request ⇒ 无挂起卡复活、同 id 仍只有旧卡一张', async () => {
    const store = useAiStore()
    const old = messageWithDecidedTool()
    store.messages = [old]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()

    store.handleFrame({
      type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
      timeoutSeconds: 120, runId: 'run-1', seq: 2
    }, old.id)
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    const segments = store.messages.flatMap((m) => m.parts.filter((p) => p.kind === 'tool' && p.toolCallId === 'r1'))
    expect(segments).toHaveLength(1)
    // G-C5：卡内容断言（防空转假绿）——旧卡仍在 DOM 且带工具名
    expect(wrapper.text()).toContain('probe.tool')
    wrapper.unmount()
  })

  it('新宿主路：re-attach 回放（挂起语义帧 + 终态帧）⇒ 全消息域仍只有旧卡一张（D6 已决面）', async () => {
    const store = useAiStore()
    store.messages = [messageWithDecidedTool()]
    store.historyLoaded = true
    api.reattachRun.mockImplementation((_runId: string, options: { onFrame: (frame: never) => void }) => {
      // 全量回放现场：request / suspended / 终态帧都到达新宿主
      options.onFrame({
        type: 'frontend_tool_request', toolCallId: 'r1', name: 'probe.tool', args: '{}',
        timeoutSeconds: 120, runId: 'run-x', seq: 11
      } as never)
      options.onFrame({
        type: 'suspended', runId: 'run-x', seq: 12,
        toolCalls: [{ toolCallId: 'r1', name: 'probe.tool', type: 'function', kind: 'FRONTEND', arguments: '{}', status: 'PENDING', executed: false }]
      } as never)
      options.onFrame({
        type: 'frontend_tool_result', toolCallId: 'r1', name: 'probe.tool',
        ok: true, executed: true, status: 'FRONTEND_RESULT', result: 'ok', runId: 'run-x', seq: 13
      } as never)
      return new Promise(() => {})
    })

    void store.reattachActive('run-x')
    const wrapper = await mountPanel(store.messages)

    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    const segments = store.messages.flatMap((m) => m.parts.filter((p) => p.kind === 'tool' && p.toolCallId === 'r1'))
    expect(segments).toHaveLength(1)
    expect(segments[0]).toMatchObject({ status: 'unknown' })
    wrapper.unmount()
  })

  it('历史整替路（§6(c) 口径）：整替后同 id 单卡、无挂起卡 —— 锁单卡不锁状态', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 1,
      messages: [
        {
          type: 'ASSISTANT',
          text: '历史正文',
          toolCalls: [{ id: 'h1', name: 'probe.tool', arguments: '{}' }],
          toolResponses: [],
          timestamp: '2026-01-01T00:00:00Z'
        }
      ]
    })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })

    await store.loadHistory()
    const wrapper = await mountPanel(store.messages)

    // D7(B)：h1 的 succeeded 是载荷缺状态的占位值（R-144 承接）——本锁只数卡数
    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    const segments = store.messages.flatMap((m) => m.parts.filter((p) => p.kind === 'tool' && p.toolCallId === 'h1'))
    expect(segments).toHaveLength(1)
    wrapper.unmount()
  })
})

/**
 * R-154 前端面新增（归因：前端面施工方 K2.8）——九项裁决的组件层锁：
 *   #2 截断角标 + Show more；#5 上滑暂停跟随 + 回到底部浮钮；#7 生成中标识；
 *   #9 编辑回填；#12 空态建议词；#17 时间戳渲染 + 历史映射透传。
 */
describe('R-154：截断角标 / 跟随暂停 / 生成中 / 编辑回填 / 建议词 / 时间戳', () => {
  /** 可写 el-input 桩（回填类断言要观测 v-model 值）；el-empty 显式渲 description 槽（建议词挂在其中）。 */
  const stubsWithInput = {
    ...stubs,
    'el-input': {
      props: ['modelValue'],
      template: '<textarea class="stub-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    },
    'el-empty': { template: '<div class="stub-empty"><slot name="image" /><slot name="description" /></div>' }
  }

  async function mountPanelWith(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
    const store = useAiStore()
    store.messages = messages
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs: stubsWithInput } })
    await wrapper.vm.$nextTick()
    return wrapper
  }

  it('#2：工具段 truncated ⇒ 「已截断」角标（hover 带原因）+ Show more 展开/收起入口', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '前 64KB 内容…', truncated: true, truncatedReason: '单帧 64KB 预算超限，结果字段被截断', requiresAction: false }
      ])
    ])
    const badge = wrapper.find('[data-testid="truncated-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('已截断')
    expect(badge.attributes('title')).toContain('单帧 64KB 预算超限')
    // Show more 入口在卡内（折叠态随卡体 v-show 隐藏但 DOM 恒在）
    const showMore = wrapper.find('[data-testid="show-more-c1"]')
    expect(showMore.exists()).toBe(true)
    expect(showMore.text()).toBe('显示更多')
    // 展开卡 → 点 Show more → 文案切「收起」
    await wrapper.find('[data-testid="block-toggle-a-1:c1"]').trigger('click')
    await showMore.trigger('click')
    expect(wrapper.find('[data-testid="show-more-c1"]').text()).toBe('收起')
    await wrapper.find('[data-testid="show-more-c1"]').trigger('click')
    expect(wrapper.find('[data-testid="show-more-c1"]').text()).toBe('显示更多')
    wrapper.unmount()
  })

  it('#2 负例：未截断段不出角标与展开入口', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'c2', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '完整结果', requiresAction: false }
      ])
    ])
    expect(wrapper.find('[data-testid="truncated-badge"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="show-more-c2"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('#5：上滑（离底）暂停跟随出浮钮，点「回到底部」恢复跟随并滚底', async () => {
    const wrapper = await mountPanel([
      buildMessage([{ kind: 'text', seg: 0, text: '长文', streaming: false }])
    ])
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    wrap.scrollTop = 100 // 离底 500px > 阈值
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()

    const backBtn = wrapper.find('[data-testid="back-to-bottom"]')
    expect(backBtn.exists()).toBe(true)
    expect(backBtn.text()).toContain('回到底部')

    await backBtn.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrap.scrollTop).toBe(1000) // 已滚底
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(false) // 跟随恢复，浮钮消失
    wrapper.unmount()
  })

  it('#5 负例：贴底滚动不出浮钮', async () => {
    const wrapper = await mountPanel([
      buildMessage([{ kind: 'text', seg: 0, text: '长文', streaming: false }])
    ])
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    wrap.scrollTop = 980 // 离底 20px < 阈值
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('#7/#3 语义延续（R-157 改承载）：流式标识正文产出后仍在，撤 el-tag 改呼吸点/光标', async () => {
    const speaking = buildMessage([{ kind: 'text', seg: 0, text: '正在说', streaming: true }], null, true)
    speaking.id = 'a-speaking'
    const settled = buildMessage([{ kind: 'text', seg: 0, text: '说完了', streaming: false }], null, false)
    settled.id = 'a-settled'

    const wrapper = await mountPanel([speaking, settled])
    // R-154 的 el-tag「生成中」已撤（R-157 #3 直裁方案一）；流式标识 = 打字机光标
    expect(wrapper.findAll('[data-testid="streaming-tag"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="stream-caret"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('#9：编辑（R-157 #2 收窄为用户消息）= 回填输入框并记住锚点；发送时才截断重跑', async () => {
    const message = buildMessage([{ kind: 'text', seg: 0, text: '用户原话', streaming: false }])
    message.role = 'user'
    const wrapper = await mountPanelWith([message])
    await wrapper.find('[data-testid="edit-msg-a-1"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect((wrapper.find('.stub-input').element as HTMLTextAreaElement).value).toBe('用户原话')
    // 编辑动作本身不截断/不改消息数组（截断发生在发送，见 R-157 编辑重跑用例）
    expect(message.parts).toHaveLength(1)
    expect(message.content).toBe('用户原话')
    wrapper.unmount()
  })

  it('#12：空态出静态建议词列表，点击回填输入框', async () => {
    // 空态挂载会触发 onMounted 的 loadHistory（messages 为空）：桩掉后端历史，避免未处理拒绝
    const store = useAiStore()
    api.history.mockResolvedValue({ sessionId: store.sessionId, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })
    const wrapper = await mountPanelWith([])
    const chips = wrapper.findAll('[data-testid="suggestion-chip"]')
    expect(chips.length).toBeGreaterThanOrEqual(4)
    expect(chips[0].text()).toContain('列出全部配置定义')
    await chips[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect((wrapper.find('.stub-input').element as HTMLTextAreaElement).value).toBe('列出全部配置定义')
    wrapper.unmount()
  })

  it('#17：时间戳渲染（epoch 毫秒与 ISO 字符串均可解析；缺失/坏值不渲染）', async () => {
    const numeric = buildMessage([{ kind: 'text', seg: 0, text: '数字戳', streaming: false }])
    numeric.id = 'a-numeric'
    numeric.timestamp = 1_700_000_000_000
    const iso = buildMessage([{ kind: 'text', seg: 0, text: 'ISO 戳', streaming: false }])
    iso.id = 'a-iso'
    iso.timestamp = '2026-01-01T00:00:00Z'
    const none = buildMessage([{ kind: 'text', seg: 0, text: '无戳', streaming: false }])
    none.id = 'a-none'
    const broken = buildMessage([{ kind: 'text', seg: 0, text: '坏戳', streaming: false }])
    broken.id = 'a-broken'
    broken.timestamp = 'not-a-date'

    const wrapper = await mountPanel([numeric, iso, none, broken])
    const stamps = wrapper.findAll('[data-testid="msg-timestamp"]')
    expect(stamps).toHaveLength(2)
    expect(stamps[0].text()).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/)
    expect(stamps[1].text()).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/)
    wrapper.unmount()
  })

  it('#17 历史路：loadHistory 整替映射透传后端条目 timestamp', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 2,
      messages: [
        { type: 'USER', text: '历史提问', timestamp: 1_700_000_000_000 },
        { type: 'ASSISTANT', text: '历史回答', timestamp: '2026-01-01T00:00:00Z' }
      ]
    })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })

    await store.loadHistory()
    expect(store.messages[0].timestamp).toBe(1_700_000_000_000)
    expect(store.messages[1].timestamp).toBe('2026-01-01T00:00:00Z')
  })
})

/**
 * R-157 前端面（用户直裁五点）：操作行挪卡片下方 / 编辑收窄+重跑 / 呼吸点+光标接力 / 发送滚底 / 头像去文字。
 */
describe('R-157：操作行外置 + role 过滤 + 编辑重跑 + 发送滚底 + 新头像', () => {
  const stubsWithInput = {
    ...stubs,
    'el-input': {
      props: ['modelValue'],
      template: '<textarea class="stub-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    },
    'el-empty': { template: '<div class="stub-empty"><slot name="image" /><slot name="description" /></div>' }
  }

  function pair(): ChatMessage[] {
    const user = buildMessage([{ kind: 'text', seg: 0, text: '原始提问', streaming: false }])
    user.id = 'u-1'
    user.role = 'user'
    user.timestamp = 1_700_000_000_000
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '原始回答', streaming: false }])
    reply.id = 'a-1'
    const extra = buildMessage([{ kind: 'text', seg: 0, text: '后续追问', streaming: false }])
    extra.id = 'u-2'
    extra.role = 'user'
    return [user, reply, extra]
  }

  it('#1：操作行在气泡外（卡片下沿独立行），气泡内零操作元素', async () => {
    const [user, reply] = pair()
    const wrapper = await mountPanel([user, reply])
    for (const id of ['u-1', 'a-1']) {
      const ops = wrapper.find(`[data-testid="msg-ops-${id}"]`)
      expect(ops.exists()).toBe(true)
      // 操作行不是气泡的后代（气泡内零操作元素）
      expect(wrapper.find(`[data-testid="msg-bubble-${id}"]`).find('[data-testid^="msg-ops-"]').exists()).toBe(false)
    }
    // 时间戳在操作行内（仅 u-1 带戳）
    expect(wrapper.find('[data-testid="msg-ops-u-1"]').find('[data-testid="msg-timestamp"]').exists()).toBe(true)
    // 时间戳与操作两端分置（MINOR-3）：行 = justify-between，时间戳为首子、操作容器为末子
    const rowEl = wrapper.find('[data-testid="msg-ops-u-1"]').element
    expect(rowEl.className).toContain('justify-between')
    expect(rowEl.children[0].getAttribute('data-testid')).toBe('msg-timestamp')
    expect(rowEl.children[rowEl.children.length - 1].className).toContain('opacity-0')
    // hover/focus-within 浮出口径：操作容器默认透明（group-hover 浮现，键盘 focus-within 可达）
    expect(wrapper.find('[data-testid="msg-ops-u-1"]').find('[class*="opacity-0"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('#2 role 过滤：仅用户消息可编辑；AI 回复无编辑、有重试', async () => {
    const [user, reply] = pair()
    const wrapper = await mountPanel([user, reply])
    expect(wrapper.find('[data-testid="edit-msg-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="retry-msg-u-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="edit-msg-a-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="retry-msg-a-1"]').exists()).toBe(true)
    // 图标化：复制按钮内是 SVG，无「复制」文字按钮
    expect(wrapper.find('[data-testid="copy-msg-u-1"] svg').exists()).toBe(true)
    wrapper.unmount()
  })

  it('#2 编辑重跑（MAJOR-1 语义版）：该条文本原地更新 + 截断其后 + 不重复追加用户消息', async () => {
    const store = useAiStore()
    store.messages = pair()
    store.historyLoaded = true
    api.chat.mockReset()
    api.chat.mockResolvedValue(undefined)
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs: stubsWithInput } })
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    const input = wrapper.find('.stub-input')
    await input.setValue('改写后的提问')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await wrapper.vm.$nextTick()

    // 语义：u-1 文本原地更新（旧文本不残留），其后截断，重发只起新助手消息（不追加第二条用户消息）
    expect(store.messages.map((m) => m.id)).toEqual(['u-1', expect.stringMatching(/^a-/)])
    expect(store.messages[0]?.content).toBe('改写后的提问')
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0][0].message).toBe('改写后的提问')
    wrapper.unmount()
  })

  it('#2 重试（MAJOR-1 语义版）：提问原地保留不重复，截断其后并从原提问重跑', async () => {
    const store = useAiStore()
    store.messages = pair()
    store.historyLoaded = true
    api.chat.mockReset()
    api.chat.mockResolvedValue(undefined)
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs: stubsWithInput } })
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="retry-msg-a-1"]').trigger('click')
    await wrapper.vm.$nextTick()

    // 语义：提问只出现一次（u-1 原地保留），a-1/u-2 截断，重发只起新助手消息
    expect(store.messages.map((m) => m.id)).toEqual(['u-1', expect.stringMatching(/^a-/)])
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0][0].message).toBe('原始提问')
    wrapper.unmount()
  })

  it('#4：发送即恢复跟随并强制滚底（上滑暂停态下也生效）', async () => {
    const store = useAiStore()
    store.messages = pair()
    store.historyLoaded = true
    api.chat.mockReset()
    api.chat.mockResolvedValue(undefined)
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs: stubsWithInput } })
    await wrapper.vm.$nextTick()

    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    wrap.scrollTop = 100
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(true) // 已暂停跟随

    await wrapper.find('.stub-input').setValue('新问题')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrap.scrollTop).toBe(1000) // 发送即强制滚底
    wrapper.unmount()
  })

  it('#5：头像去文字 —— AI 四角星 / 用户人形 SVG，头像内零文字', async () => {
    const [user, reply] = pair()
    const wrapper = await mountPanel([user, reply])
    for (const id of ['u-1', 'a-1']) {
      const avatar = wrapper.find(`[data-testid="msg-avatar-${id}"]`)
      expect(avatar.exists()).toBe(true)
      expect(avatar.find('svg').exists()).toBe(true)
      expect(avatar.text()).toBe('')
    }
    wrapper.unmount()
  })
})
