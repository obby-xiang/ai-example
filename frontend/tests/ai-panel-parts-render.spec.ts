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
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ActiveGenerativeForm, ChatMessage, MessagePart, PendingToolCall, ToolPart } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'
import { useAiStore } from '@/stores/ai'
import CollapsibleText from '@/components/AiPanel/CollapsibleText.vue'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn()
}))

/** R-165 S5b：mermaid 渲染出口桩 —— 真 mermaid 不在 jsdom 跑（几何/真渲染在实机取证）；
 *  桩值可逐用例改写（`null` = render 失败路径）。模块其余导出走 importOriginal 原样透出。 */
const mmdRender = vi.hoisted(() => ({ svg: '' as string | null }))
vi.mock('@/utils/markdown', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/markdown')>()
  return { ...actual, renderMermaidSvg: () => Promise.resolve(mmdRender.svg) }
})

/** R-158 #12：ResizeObserver 测试桩 —— jsdom 无 RO；用模块级开关控制「溢出/不溢出」回调。 */
const roState = vi.hoisted(() => ({ overflow: true }))
class ResizeObserverStub {
  constructor(private readonly cb: ResizeObserverCallback) {}
  observe(el: Element): void {
    this.cb([{ target: el, contentRect: { height: roState.overflow ? 500 : 0 } } as unknown as ResizeObserverEntry], {} as ResizeObserver)
  }
  unobserve(): void {}
  disconnect(): void {}
}

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
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  roState.overflow = true
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.feedbackSubmit.mockReset()
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
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

/**
 * R-160 M5：CollapsibleText 注高法 —— 删 RO 后溢出由 onMounted 的 nextTick(measure) 直读
 * scrollHeight；mount 返回后**同步**给全部 CollapsibleText 注高（赶在 queued measure flush
 * 之前），再 nextTick 落定（原 RO 桩向回调注 contentRect 的口径退役）。
 */
async function mountWithOverflow(messages: ChatMessage[]): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  for (const item of wrapper.findAllComponents(CollapsibleText)) {
    const el = item.find('.whitespace-pre-wrap').element as HTMLElement
    Object.defineProperty(el, 'scrollHeight', { value: 500, configurable: true })
  }
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
    // R-166 口径①：倒计时真值上移到 store 的 `confirmClock`（面板只读 `deadlineMs − now`），
    // 本用例直造段（不经帧），故显式锚定一次时钟 —— 顺带覆盖"旧格式帧（无 atMs）按
    // timeoutSeconds 相对秒回落"这条既有兼容面。
    useAiStore().anchorConfirmClock('c1', undefined, undefined, 120)
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

    // A1'②：卡头统一为段单一来源 —— 旧结构（工具卡 + 投影挂起卡并列）工具名出现两次，合并后恰一次。
    // R-163 T2 口径：卡头另有摘要行（「启动导出作业（任务 #1）」），工具名仍只标签一次
    // （按标签边界计数，摘要行的含名摘要不混入）。
    // R-165 S2：门卡字段表新增「操作」行（值 = 工具中文名，v7 `.grows` 口径），全 DOM 计名已含该行
    // —— 故本锁改按**卡头边界**计数（A1' 的原意是「同 id 只有一张卡头」，卡头内仍恰一次）。
    const headHtml = (cards[0]!.element.firstElementChild as HTMLElement).outerHTML
    expect(headHtml.split('>启动导出作业<').length - 1).toBe(1)
    // R-163 T2 摘要行入卡头（T0 静态映射）
    expect(wrapper.find('[data-testid="tool-summary-c1"]').exists()).toBe(true)
    // 确认子块（CONFIRM pending）在持 testid 的同一张卡内
    expect(cards[0]!.html()).toContain('确认执行')

    // R-165 S2：门卡按 demo v7 `.gate` 纯 Tailwind 结构（卡头 + 字段表 + 倒计时环 + 拒绝原因 + 双自绘按钮）
    const gate = cards[0]!.find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)
    expect(gate.classes()).toEqual(expect.arrayContaining(['border-[#cddcff]', 'bg-[#eff4ff]', 'rounded-[10px]', 'p-[12px]', 'border-solid']))
    expect(gate.find('.gate').exists()).toBe(false) // 手写 CSS 类退场
    // 卡头字形/文案按 demo v7.7 蓝本（demo :760 `!` 圆环字形 + 「需要你确认后才能继续」）——
    // 移植笔误期曾为手部 emoji（U+270B）+「需要人工确认」；本锁只锁字形/文案，圆环几何在 class 面（未动）。
    const gateHd = gate.findAll(':scope > div')[0]!
    expect(gateHd.findAll('span')[0]!.text()).toBe('!')
    expect(gateHd.text()).toContain('需要你确认后才能继续')
    expect(gateHd.text()).toContain('等待确认')
    // 字形面零 emoji（emoji 呈现区 + dingbat 区）—— 移植笔误锁
    expect(gateHd.text()).not.toMatch(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]/u)
    const rows = gate.find('[data-testid="gate-rows"]').findAll(':scope > div')
    expect(rows[0]!.text()).toContain('操作')
    expect(rows[0]!.text()).toContain('启动导出作业')
    expect(rows[1]!.text()).toContain('taskId')
    expect(rows[1]!.text()).toContain('1')
    // 倒计时环（v7 同构：双 circle + 周长/dashoffset 绑定）
    const ring = gate.find('svg')
    expect(ring.exists()).toBe(true)
    expect(ring.attributes('viewBox')).toBe('0 0 36 36')
    expect(ring.find('circle').exists()).toBe(true) // 两枚描边圆（底环 + 蓝弧）
    // el-input/el-button 退场：拒绝原因 = 原生 textarea，双按钮 = 原生 button
    // （R-165 S3a 续：输入区 composer 亦已退 EP，断言改挂 [data-testid="composer-input"] 原生 textarea）
    const reason = gate.find('textarea')
    expect(reason.exists()).toBe(true)
    expect(reason.attributes('placeholder')).toContain('拒绝原因')
    const gateBtns = gate.findAll('button')
    expect(gateBtns.map((b) => b.text())).toEqual(['拒绝', '确认执行'])
    expect(gateBtns[1]!.classes()).toEqual(expect.arrayContaining(['bg-[#2563eb]', 'text-white', 'font-semibold', 'rounded-[8px]', 'px-[13px]', 'py-[5px]', 'text-[12.6px]', 'disabled:bg-[#8b949e]']))
    expect(gateBtns[0]!.classes()).toEqual(expect.arrayContaining(['bg-white', 'text-[#57606a]', 'border-[#e3e6ea]']))

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
    // R-163 T2 口径：无映射工具的摘要兜底 = 「工具名 · 状态」，工具名标签本体仍恰一次
    // （按标签边界计数，摘要行兜底文本不混入）
    expect(wrapper.html().split('>probe.tool<').length - 1).toBe(1)
    expect(wrapper.find('[data-testid="tool-summary-f1"]').text()).toContain('probe.tool')
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

  it('R-159 #2：呼吸全程常驻 —— 流式期有无正文呼吸都在（首字符不消失），光标同存；终态两者皆无', async () => {
    const typing = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)
    typing.id = 'a-typing'
    const speaking = buildMessage([{ kind: 'text', seg: 0, text: '正在说', streaming: true }], null, true)
    speaking.id = 'a-speaking'
    const settled = buildMessage([{ kind: 'text', seg: 0, text: '说完了', streaming: false }], null, false)
    settled.id = 'a-settled'

    const wrapper = await mountPanel([typing, speaking, settled])
    // R-159 #2 常驻：无正文（a-typing）与有正文（a-speaking）两条流式消息都呼吸 —— 首字符不消失
    const breath = wrapper.findAll('[data-testid="loading-breath"]')
    expect(breath).toHaveLength(2)
    expect(breath[1].text()).toContain('工作中…')
    // 光标与呼吸同存（不再互斥接力）
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
    // R-165 S2：思考卡对齐 demo v7 `.reason` —— 卡容器 bg-#f6f7f9/1px #e3e6ea/圆角 10；
    // 卡头 = 圆环✦（15×15 / 1.2px / #e3e6ea）+「思考过程」12.4px#57606a + 摘要 11.5px#8b949e + chevron；
    // 展开体 = 左竖线 border-l-2 #e3e6ea + pl-10px/pr-11px/pb-10px，正文 12.6px/1.7/#57606a。
    expect(toggle.element.tagName).toBe('BUTTON')
    expect(toggle.classes()).toEqual(expect.arrayContaining(['flex', 'items-center', 'gap-[8px]', 'px-[10px]', 'py-[7px]', 'border-0', 'bg-transparent']))
    const rcard = toggle.element.parentElement as HTMLElement
    expect(rcard.className).toContain('bg-[#f6f7f9]')
    expect(rcard.className).toContain('rounded-[10px]')
    expect(rcard.className).toContain('border-[#e3e6ea]')
    const ric = toggle.element.firstElementChild as HTMLElement
    // R-166 口径⑦：思考卡字形由 demo 的 `✦`(U+2726) 改 `⋆`(U+22C6) —— 原字落在本批 emoji
    // 复扫的判废区间（U+2600-27BF）内，改取同形数学星号；同时锁"字形面零 emoji 码位"。
    expect(ric.textContent).toBe('⋆')
    expect(ric.textContent).not.toMatch(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]/u)
    expect(ric.className).toContain('border-[1.2px]')
    expect(ric.className).toContain('border-solid') // preflight 关闭：环须显式 solid（R-165 S1 §5①）
    expect(ric.className).toContain('rounded-[50%]')
    expect(ric.className).toContain('w-[15px]')
    expect(toggle.text()).toContain('思考过程')
    expect(toggle.text()).toContain('共 13 字 · 点击展开') // 摘要位（v7 .rsum 11.5px 灰）
    const rsum = wrapper.find('[data-testid="reasoning-toggle-a-1:reasoning-0"] span:nth-child(3)')
    expect(rsum.classes()).toEqual(expect.arrayContaining(['text-[11.5px]', 'text-[#8b949e]']))
    expect(toggle.find('.tchev').exists()).toBe(true) // chevron（R-165 S5c 起旋转由 [&.is-open]:rotate-90 工具类承载）
    await toggle.trigger('click')
    expect(wrapper.find('[data-testid="reasoning-body-a-1:reasoning-0"]').attributes('style') ?? '').not.toContain('display: none')
    const rbody = wrapper.find('[data-testid="reasoning-body-a-1:reasoning-0"]')
    expect(rbody.classes()).toEqual(expect.arrayContaining(['pl-[10px]', 'pr-[11px]', 'pb-[10px]', 'border-l-2', 'border-solid', 'border-[#e3e6ea]']))
    expect(wrapper.find('[data-testid="reasoning-toggle-a-1:reasoning-0"]').text()).toContain('点击收起')
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
  /** el-input 桩保留（R-165 S3a 后 AiPanel 内已零命中，仅防回退）；el-empty 显式渲 description 槽（建议词挂在其中）。 */
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

  it('#2：工具段 truncated ⇒ 「已截断」角标（hover 带原因）+ 输出区纯限高内滚（R-165 S5a 无展开钮）', async () => {
    // R-160 M5：真溢出改单一 measure（onMounted nextTick 读 scrollHeight）—— 注高法触发
    const wrapper = await mountWithOverflow([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '前 64KB 内容…', truncated: true, truncatedReason: '单帧 64KB 预算超限，结果字段被截断', requiresAction: false }
      ])
    ])
    const badge = wrapper.find('[data-testid="truncated-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('已截断')
    expect(badge.attributes('title')).toContain('单帧 64KB 预算超限')
    // R-165 S5a 项 4（demo v7 终裁）：输出区改**纯限高内滚 160px** —— 原 CollapsibleText 的渐隐 +
    // 「显示更多」钮整退役（限高族统一「纯限高内滚、无展开钮」）；截断角标继续由段标记派生。
    expect(wrapper.find('[data-testid="show-more-c1"]').exists()).toBe(false)
    await wrapper.find('[data-testid="block-toggle-a-1:c1"]').trigger('click')
    const outBox = wrapper.find('[data-testid="block-body-a-1:c1"] > div:last-child > div:last-child').element as HTMLElement
    expect(outBox.className).toContain('max-h-[160px]')
    expect(outBox.className).toContain('overflow-y-auto')
    expect(outBox.textContent).toContain('前 64KB 内容…')
    wrapper.unmount()
  })

  it('#2 负例：未截断段不出角标；真溢出归展示层判据（不溢出 ⇒ 无 show-more）', async () => {
    // R-160 M5 语义：show-more 由真溢出（单一 measure 读 scrollHeight）派生，不由 truncated
    // 标记派生 —— 不注高（scrollHeight 0）即不溢出
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
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('用户原话')
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
    // R-165 S2：空态自绘（el-empty / el-icon / el-button 退场）—— demo v7 `.mini` 结构：
    // 白底细描边圆角 10 卡 + 标题 600 + 副文 12.4px #57606a + chips 胶囊行（999px 圆角 / 悬停蓝）
    const empty = wrapper.find('[data-testid="empty-state"]')
    expect(empty.exists()).toBe(true)
    expect(empty.classes()).toEqual(expect.arrayContaining(['bg-white', 'border-solid', 'border-[#e3e6ea]', 'rounded-[10px]', 'px-[14px]', 'py-[16px]']))
    expect(wrapper.find('.stub-empty').exists()).toBe(false) // el-empty 零残留
    expect(empty.text()).toContain('您好，我是实施助手')
    expect(empty.find('div:nth-child(2)').classes()).toEqual(expect.arrayContaining(['text-[12.4px]', 'text-[#57606a]']))
    expect(empty.find('div:nth-child(1)').classes()).toContain('[font-weight:650]')
    expect(chips[0].element.tagName).toBe('BUTTON')
    expect(chips[0].classes()).toEqual(expect.arrayContaining(['rounded-[999px]', 'bg-white', 'border-[#e3e6ea]', 'text-[12.2px]', 'text-[#57606a]', 'hover:text-[#2563eb]']))
    await chips[0].trigger('click')
    await wrapper.vm.$nextTick()
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('列出全部配置定义')
    wrapper.unmount()

    // R-165 S2：骨架屏自绘（el-skeleton 退场）—— v7 `.sk` 渐变条（渐变 + 220% 长度 + 无限律动 + 宽度档）
    const skeletonWrapper = await mountPanelWith([])
    store.historyLoaded = false
    await skeletonWrapper.vm.$nextTick()
    const sk = skeletonWrapper.find('[data-testid="history-skeleton"]')
    expect(sk.exists()).toBe(true)
    expect(skeletonWrapper.find('[data-testid="empty-state"]').exists()).toBe(false) // 与空态互斥
    const bars = sk.findAll(':scope > div')
    expect(bars).toHaveLength(4)
    expect(bars[0]!.classes()).toEqual(expect.arrayContaining([
      'bg-[linear-gradient(90deg,#f6f7f9_0,#eef0f3_40%,#f6f7f9_80%)]', 'bg-[length:220%_100%]',
      'animate-pulse', 'motion-reduce:animate-none', 'rounded-[6px]', 'h-[11px]'
    ]))
    expect(bars[0]!.classes()).toContain('w-[72%]')
    expect(bars[1]!.classes()).toContain('w-[92%]')
    skeletonWrapper.unmount()
  })

  it('#17：时间戳渲染（epoch 毫秒与 ISO 字符串均可解析；缺失/坏值不渲染）', async () => {
    const numeric = buildMessage([{ kind: 'text', seg: 0, text: '数字戳', streaming: false }])
    numeric.id = 'a-numeric'
    numeric.timestamp = 1_700_000_000_000
    const iso = buildMessage([{ kind: 'text', seg: 0, text: 'ISO 戳', streaming: false }])
    iso.id = 'a-iso'
    iso.timestamp = '2026-01-01T12:00:00Z' // 正午 UTC：任意时区都落在 2026-01-01/02，同年档断言时区安全
    const none = buildMessage([{ kind: 'text', seg: 0, text: '无戳', streaming: false }])
    none.id = 'a-none'
    const broken = buildMessage([{ kind: 'text', seg: 0, text: '坏戳', streaming: false }])
    broken.id = 'a-broken'
    broken.timestamp = 'not-a-date'

    const wrapper = await mountPanel([numeric, iso, none, broken])
    const stamps = wrapper.findAll('[data-testid="msg-timestamp"]')
    expect(stamps).toHaveLength(2)
    // R-162 M2 渐进格式：1.7e12=2023 年 ⇒ 跨年档；2026-01-01（距今 ≥7 自然日且同年）⇒ M月D日 档
    expect(stamps[0].text()).toMatch(/^\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}$/)
    expect(stamps[1].text()).toMatch(/^\d{1,2}月\d{1,2}日 \d{2}:\d{2}$/)
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
    // R-162 M1 紧邻布局（删 justify-between 两端分置）：行 = [时间戳容器 | 按钮组容器] 两子紧邻，
    // 按钮组即末子（无行尾孤立按钮）；用户行 row-reverse 渲染为按钮贴时间戳左侧、整簇靠右
    const rowEl = wrapper.find('[data-testid="msg-ops-u-1"]').element
    expect(rowEl.className).not.toContain('justify-between')
    expect(rowEl.className).toContain('flex-row-reverse')
    expect(rowEl.children).toHaveLength(2)
    expect(rowEl.children[0].querySelector('[data-testid="msg-timestamp"]')).not.toBeNull()
    // R-165 S4 项 1（用户直裁）：操作按钮**常驻** —— 撤 opacity-0/group-hover/focus-within 浮出机制
    // 判据只钉「按钮组容器」与「匿名 group 浮出类」：按钮内 `.tip` 气泡自身的
    // `opacity-0 group-hover/tip:opacity-100`（S2 起）是独立机制，按直裁保留。
    expect(rowEl.children[rowEl.children.length - 1].className).not.toContain('opacity-0')
    expect(rowEl.children[rowEl.children.length - 1].className).not.toContain('transition-opacity')
    for (const id of ['u-1', 'a-1']) {
      const opsRow = wrapper.find(`[data-testid="msg-ops-${id}"]`)
      const btnGroup = opsRow.element.children[opsRow.element.children.length - 1] as HTMLElement
      expect(btnGroup.className).not.toContain('opacity-0')
      expect(btnGroup.className).not.toContain('group-hover:opacity-100')
      expect(btnGroup.className).not.toContain('focus-within:opacity-100')
      expect(opsRow.find('[class*="group-hover:opacity-100"]').exists()).toBe(false) // 匿名 group 浮出零残留
      expect(opsRow.find(`[data-testid="copy-msg-${id}"]`).exists()).toBe(true)
      expect(opsRow.find(`[class*="group-hover/tip:opacity-100"]`).exists()).toBe(true) // .tip 气泡保留
    }
    wrapper.unmount()
  })

  it('#2 role 过滤：仅用户消息可编辑；AI 回复无编辑、仅复制（R-158 #4 重试已去除）', async () => {
    const [user, reply] = pair()
    const wrapper = await mountPanel([user, reply])
    expect(wrapper.find('[data-testid="edit-msg-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="retry-msg-u-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="edit-msg-a-1"]').exists()).toBe(false)
    // R-158 #4：用户直裁去除重试 —— AI 回复操作行仅剩复制（skipUserRecord 通道在 store 层保留）
    expect(wrapper.find('[data-testid="retry-msg-a-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="copy-msg-a-1"]').exists()).toBe(true)
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
    const input = wrapper.find('[data-testid="composer-input"]')
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

    await wrapper.find('[data-testid="composer-input"]').setValue('新问题')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrap.scrollTop).toBe(1000) // 发送即强制滚底
    wrapper.unmount()
  })

  it('#5：双侧去头像（R-163 T3 口径改写）—— 消息行无头像列、头像 testid 零命中', async () => {
    const [user, reply] = pair()
    const wrapper = await mountPanel([user, reply])
    // R-157 #5 原判据（头像存在 + 零文字）随 R-163 T3「双侧去头像」退役：断言头像整列移除
    expect(wrapper.find('[data-testid^="msg-avatar-"]').exists()).toBe(false)
    // 消息行内容直出：用户卡片与助手全文仍在
    expect(wrapper.find('[data-testid="msg-bubble-u-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="msg-bubble-a-1"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

/**
 * R-158：#10 HITL 只读回执 / #12 CollapsibleText 真溢出 / #11 复制段间换行（UI 面锁）。
 */
describe('R-158：HITL 只读回执 / CollapsibleText / 复制换行', () => {
  it('#10：CONFIRM 已决段出只读回执卡（状态徽标 + 参数自然语言摘要 + 可展开原始 args）', async () => {
    const msg = buildMessage([
      { kind: 'text', seg: 0, text: '', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId: 'p1', name: 'start_publish', kind2: 'CONFIRM',
        status: 'succeeded', args: '{"taskId":3}', requiresAction: false,
        decision: 'approved', decidedAt: 1_700_000_000_000
      }
    ])
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-1:p1"]').trigger('click')
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.exists()).toBe(true)
    expect(receipt.text()).toContain('已确认')
    expect(receipt.text()).toContain('启动发布作业')
    expect(receipt.text()).toContain('taskId=3')
    expect(receipt.text()).toMatch(/\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}/) // decidedAt 回显（R-162 渐进格式：1.7e12=2023 跨年档）
    // R-165 S2：回执卡按 demo v7 `.receipt` 结构（原 `.hitl-receipt`/`.rt` 手写 CSS 类退场）
    // rc-row：徽标 10.5px 圆角 5 semibold（三族色）+ rtxt 截断 + rt 时间 11.5px tabular-nums
    expect(receipt.classes()).toEqual(expect.arrayContaining(['border-[#e3e6ea]', 'bg-white', 'rounded-[10px]', 'px-[11px]', 'py-[8px]', 'text-[12.6px]', 'text-[#57606a]', 'border-solid']))
    expect(receipt.classes()).not.toContain('hitl-receipt')
    const badge = receipt.find('div:nth-child(1) span:nth-child(1)')
    expect(badge.classes()).toEqual(expect.arrayContaining(['text-[10.5px]', 'px-[6px]', 'rounded-[5px]', 'font-semibold', 'text-[#1a7f45]', 'bg-[#edf7f0]']))
    const rtxt = receipt.find('div:nth-child(1) span:nth-child(2)')
    expect(rtxt.classes()).toEqual(expect.arrayContaining(['flex-1', 'min-w-0', 'whitespace-nowrap', 'overflow-hidden', 'text-ellipsis']))
    const rt = receipt.find('div:nth-child(1) span:nth-child(3)')
    expect(rt.classes()).toEqual(expect.arrayContaining(['text-[#8b949e]', 'text-[11.5px]', 'tabular-nums']))
    // rc-toggle：无底无描边下划线钮 + 悬停蓝（R-165 S5a 项 2：文案改单一动作「查看详情 ⇄ 收起详情」）
    const rcToggle = receipt.find('[data-testid="receipt-args-toggle-p1"]')
    expect(rcToggle.classes()).toEqual(expect.arrayContaining(['border-0', 'bg-transparent', 'p-0', 'mt-[5px]', 'text-[11.5px]', 'border-b', 'hover:text-[#2563eb]']))
    expect(rcToggle.text()).toBe('查看详情')
    // 展开内容 = **单层 mono 盒**（R-165 S5a 项 2：外层包裹盒 + 「已提交值/原始参数」两个小节标签退役）
    const argsMore = wrapper.find('[data-testid="receipt-args-more-p1"]')
    expect(argsMore.exists()).toBe(true)
    expect(argsMore.attributes('style')).toContain('display: none')
    expect(argsMore.classes()).toEqual(expect.arrayContaining(['mt-[8px]']))
    expect(argsMore.classes()).not.toContain('border-t')
    expect(argsMore.classes()).not.toContain('pt-[9px]')
    await wrapper.find('[data-testid="receipt-args-toggle-p1"]').trigger('click')
    expect(wrapper.find('[data-testid="receipt-args-toggle-p1"]').text()).toBe('收起详情')
    expect(wrapper.find('[data-testid="receipt-args-more-p1"]').attributes('style') ?? '').not.toContain('display: none')
    // 单层 mono 盒（v7 `rc-detail pre` 形制）：唯一子元素即该盒，盒内无小节标签
    const mono = wrapper.find('[data-testid="receipt-args-more-p1"] > div')
    expect(mono.classes()).toEqual(expect.arrayContaining(['bg-[#f6f7f9]', 'border-solid', 'border-[#eef0f3]', 'rounded-[8px]', 'px-[9px]', 'py-[8px]', 'font-mono', 'text-[12.1px]', 'leading-[1.6]']))
    expect(wrapper.find('[data-testid="receipt-args-more-p1"] > div > span').exists()).toBe(false)
    expect(mono.text()).toContain('taskId')
    wrapper.unmount()
  })

  it('#10：已过期 / GF 已提交值回执（徽标区分 + submittedValues 可回溯）', async () => {
    const expired = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e1', name: 'start_export', kind2: 'CONFIRM', status: 'expired', args: '{}', requiresAction: false }
    ])
    expired.id = 'a-expired'
    const gf = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded', args: '{}', requiresAction: false, submittedValues: { region: 'HB' } }
    ])
    gf.id = 'a-gf'
    const plain = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 't1', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', args: '{}', requiresAction: false }
    ])
    plain.id = 'a-plain'

    const wrapper = await mountPanel([expired, gf, plain])
    await wrapper.find('[data-testid="block-toggle-a-expired:e1"]').trigger('click')
    // R-166 口径⑥：回执徽标带字形前缀，超时族文案由「已过期」改「已超时」（demo v7.7 实字）
    expect(wrapper.find('[data-testid="hitl-receipt"]').text()).toContain('! 已超时')
    expect(wrapper.find('[data-testid="hitl-receipt"]').text()).not.toContain('已过期')
    await wrapper.find('[data-testid="block-toggle-a-gf:g1"]').trigger('click')
    const gfReceipt = wrapper.findAll('[data-testid="hitl-receipt"]')[1]
    expect(gfReceipt.text()).toContain('✓ 已确认')
    // R-165 S5a 项 2：已提交值折进单层 mono JSON 盒的 `values` 键（原「已提交值」键值行 + 小节标签退役）
    await wrapper.find('[data-testid="receipt-args-toggle-g1"]').trigger('click')
    const gfValues = wrapper.find('[data-testid="receipt-values"]')
    expect(gfValues.text()).toContain('values')
    expect(gfValues.text()).toContain('HB')
    // 普通 BACKEND 工具卡无回执卡
    await wrapper.find('[data-testid="block-toggle-a-plain:t1"]').trigger('click')
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(2)
    wrapper.unmount()
  })

  it('#10：回执徽标系决策态（收口 MINOR-1）——approved 即显已确认（running/失败均不改判），缺省回退 status', async () => {
    const running = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'r1', name: 'start_publish', kind2: 'CONFIRM', status: 'running', args: '{}', requiresAction: false, decision: 'approved', decidedAt: 1_700_000_000_000 }
    ])
    running.id = 'a-running'
    const failedAfterApprove = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'f1', name: 'start_publish', kind2: 'CONFIRM', status: 'failed', args: '{}', requiresAction: false, decision: 'approved' }
    ])
    failedAfterApprove.id = 'a-failed'
    const rejected = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'x1', name: 'start_publish', kind2: 'CONFIRM', status: 'rejected', args: '{}', requiresAction: false, decision: 'rejected', decidedAt: 1_700_000_000_000 }
    ])
    rejected.id = 'a-rejected'
    const legacyExpired = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'l1', name: 'start_export', kind2: 'CONFIRM', status: 'expired', args: '{}', requiresAction: false }
    ])
    legacyExpired.id = 'a-legacy'

    const wrapper = await mountPanel([running, failedAfterApprove, rejected, legacyExpired])
    const receiptAt = async (prefix: string, id: string): Promise<string> => {
      await wrapper.find(`[data-testid="block-toggle-${id}:${prefix}"]`).trigger('click')
      return wrapper.find(`[data-testid="msg-bubble-${id}"]`).find('[data-testid="hitl-receipt"]').text()
    }
    // approve 即显「已确认」——running 期间不等 succeeded（R-166 口径⑥：带 ✓ 字形前缀）
    expect(await receiptAt('r1', 'a-running')).toContain('✓ 已确认')
    // 已确认但执行失败：徽标仍「已确认」（决策态 ≠ 执行态）
    expect(await receiptAt('f1', 'a-failed')).toContain('✓ 已确认')
    expect(await receiptAt('x1', 'a-rejected')).toContain('✗ 已拒绝')
    // decision 缺省（历史/超时）回退 status 派生（R-166：`! 已超时`，非本地作废 ⇒ 不带「作废」）
    expect(await receiptAt('l1', 'a-legacy')).toContain('! 已超时')
    expect(await receiptAt('l1', 'a-legacy')).not.toContain('作废')
    wrapper.unmount()
  })

  it('#12：真溢出才渐隐 + 显示更多；不溢出零副作用（无按钮无 mask）', async () => {
    const longArgs = JSON.stringify({ a: 1, b: 2 })
    const mk = (): ChatMessage => buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'probe.tool', kind2: 'BACKEND', status: 'succeeded', args: longArgs, result: '结果', requiresAction: false }
    ])
    // 不溢出：零副作用（R-160 M5：不注高 = scrollHeight 0 ⇒ 单一 measure 判不溢出）
    const w1 = await mountPanel([mk()])
    await w1.find('[data-testid="block-toggle-a-1:c1"]').trigger('click')
    expect(w1.find('[data-testid="args-more-c1"]').exists()).toBe(false)
    w1.unmount()
    // 真溢出：渐隐（mask + 限高）+ 显示更多，点击展开（R-160 M5 注高法，原 RO 桩口径退役）
    const w2 = await mountWithOverflow([mk()])
    await w2.find('[data-testid="block-toggle-a-1:c1"]').trigger('click')
    const wrap = w2.find('[data-testid="args-more-c1"]').element.parentElement!.firstElementChild as HTMLElement
    expect(w2.find('[data-testid="args-more-c1"]').exists()).toBe(true)
    // R-165 S4：收起态 = 任意值类（原 `:style="{maxHeight, maskImage}"` 行内写法退役 —— 铁律零行内 style）
    expect(wrap.className).toContain('max-h-[96px]')
    expect(wrap.className).toContain('[mask-image:linear-gradient(to_bottom,#000_55%,transparent)]')
    expect(wrap.getAttribute('style')).toBeNull() // 行内 style 零残留
    expect(wrap.style.maskImage).toBe('') // 行内口径同验（jsdom 的 el.style 只反映行内声明，类不写回此处）
    await w2.find('[data-testid="args-more-c1"]').trigger('click')
    expect(w2.find('[data-testid="args-more-c1"]').text()).toBe('收起')
    const expandedEl = w2.find('[data-testid="args-more-c1"]').element.parentElement!.firstElementChild as HTMLElement
    expect(expandedEl.className).not.toContain('max-h-[96px]')
    expect(expandedEl.className).not.toContain('[mask-image:')
    w2.unmount()
  })

  it('#12：args pretty-print（JSON 解析成功缩进格式化）', async () => {
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'j1', name: 'probe.tool', kind2: 'BACKEND', status: 'succeeded', args: '{"a":1,"b":{"c":2}}', requiresAction: false }
    ])
    // R-160 M5：注高法触发单一 measure（原 RO 桩向回调注 contentRect 的口径退役）
    const wrapper = await mountWithOverflow([msg])
    await wrapper.find('[data-testid="block-toggle-a-1:j1"]').trigger('click')
    const argsRegion = wrapper.find('[data-testid="args-more-j1"]').element.parentElement!
    expect(argsRegion.textContent).toContain('"a": 1')
    expect(argsRegion.textContent).toContain('"c": 2')
    wrapper.unmount()
  })

  it('#11：复制两段 text（R-150 合并轮）含段间换行', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const merged = buildMessage([
      { kind: 'text', seg: 0, text: '第一段', streaming: false },
      { kind: 'text', seg: 1, text: '第二段', streaming: false }
    ])
    const wrapper = await mountPanel([merged])
    await wrapper.find('[data-testid="copy-msg-a-1"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(writeText).toHaveBeenCalledWith('第一段\n\n第二段')
    wrapper.unmount()
  })
})

/**
 * R-159（issue #13 流式回复体验）：B1 光标 / 呼吸全程常驻 / 元信息行流式遮蔽 /
 * 完成 300ms 淡出换入 / 工具期光标收起归位（S0-b 时序证据）/ span.ch 渐显包裹。
 */
describe('R-159：流式回复体验六项', () => {
  it('#1：B1 光标在流式正文末尾（R-163 T1 口径：caret 为 md-body 之后紧邻兄弟）', async () => {
    const speaking = buildMessage([{ kind: 'text', seg: 0, text: '正在输出的一段文字', streaming: true }], null, true)
    speaking.id = 'a-b1'
    const wrapper = await mountPanel([speaking])
    const caret = wrapper.find('[data-testid="stream-caret"]')
    expect(caret.exists()).toBe(true)
    expect(caret.classes()).toContain('caret')
    // R-163 T1：正文走 markdown-it → DOMPurify → v-html（md-body），光标移出正文 div，
    // 为其后紧邻兄弟（B1 粗块随文尾的视觉语义不变）
    const bubble = wrapper.find('[data-testid="msg-bubble-a-b1"]')
    const mdBody = bubble.find('.md-body')
    expect(mdBody.exists()).toBe(true)
    expect(caret.element.previousElementSibling).toBe(mdBody.element)
    wrapper.unmount()
  })

  it('#3：流式期元行右位复制 visibility:hidden 占位，无时间戳；完成后换入', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '流式中', streaming: true }], null, true)
    msg.id = 'a-meta'
    msg.timestamp = 1_700_000_000_000
    const wrapper = await mountPanel([msg])
    const ops = wrapper.find('[data-testid="msg-ops-a-meta"]')
    // 流式期：左位呼吸（无时间戳），右位 visibility:hidden
    expect(ops.find('[data-testid="loading-breath"]').exists()).toBe(true)
    expect(ops.find('[data-testid="msg-timestamp"]').exists()).toBe(false)
    // R-165 S5c：占位由行内 visibility 改 Tailwind `invisible` 工具类（等价，R-159 语义不变）
    const right = ops.element.lastElementChild as HTMLElement
    expect(right.style.visibility).toBe('')
    expect(right.className).toContain('invisible')
    wrapper.unmount()
  })

  it('#4：完成淡出 —— streaming=false 同帧起 fade-out（呼吸+光标），300ms 后换时间戳+复制', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '已完成的回复', streaming: true }], null, true)
    msg.id = 'a-fade'
    msg.timestamp = 1_700_000_000_000
    const wrapper = await mountPanel([msg])
    // 经 store 代理写入（裸引用写入不触发响应式 —— 实测结论）
    const store = useAiStore()
    store.messages[0]!.streaming = false
    await wrapper.vm.$nextTick()
    // 同帧淡出：呼吸与光标挂 fade-out（jsdom 无动画计时，class 断言）
    expect(wrapper.find('[data-testid="loading-breath"]').classes()).toContain('fade-out')
    expect(wrapper.find('[data-testid="stream-caret"]').classes()).toContain('fade-out')

    await new Promise((resolve) => setTimeout(resolve, 350))
    await wrapper.vm.$nextTick()
    // 淡出退役：呼吸/光标消失，元行换时间戳（左）+ 复制（右，visibility 恢复）
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="stream-caret"]').exists()).toBe(false)
    const ops = wrapper.find('[data-testid="msg-ops-a-fade"]')
    expect(ops.find('[data-testid="msg-timestamp"]').exists()).toBe(true)
    expect((ops.element.lastElementChild as HTMLElement).style.visibility).toBe('')
    wrapper.unmount()
  })

  it('风险3：快速连发 —— 完成后再来 delta 打断淡出（fade-out 撤除、呼吸常驻恢复）', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '第一轮', streaming: true }], null, true)
    msg.id = 'a-race'
    const wrapper = await mountPanel([msg])
    const store = useAiStore()

    store.messages[0]!.streaming = false
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="loading-breath"]').classes()).toContain('fade-out')
    // 新 delta 到达（streaming 复 true）——打断
    store.messages[0]!.streaming = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="loading-breath"]').classes()).not.toContain('fade-out')
    await new Promise((resolve) => setTimeout(resolve, 350))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="loading-breath"]').exists()).toBe(true) // 未误清
    wrapper.unmount()
  })

  it('#5/S0-b：工具卡为末块期间光标收起；工具收口后新 text 段成末块自然归位', async () => {
    const msg = buildMessage(
      [
        { kind: 'text', seg: 0, text: '先读文件', streaming: true },
        { kind: 'tool', seg: 1, toolCallId: 'w1', name: 'open_export_file_editor', kind2: 'FRONTEND', status: 'running', requiresAction: false }
      ],
      null,
      true
    )
    msg.id = 'a-tool'
    const wrapper = await mountPanel([msg])
    // 工具为末块且流未收口 ⇒ 光标恒 false（执行期零指示根因修复：收起）
    expect(wrapper.find('[data-testid="stream-caret"]').exists()).toBe(false)
    // 时序：message_end 未到（streaming 仍 true）、工具仍在 running —— 期间无光标
    expect(msg.streaming).toBe(true)
    // 归位：工具收口后正文继续（新 text 段成为末块）⇒ 光标即刻归位（不依赖 streaming=false）——经代理 push 触发响应式
    const store = useAiStore()
    ;(store.messages[0]!.parts as MessagePart[]).push({ kind: 'text', seg: 2, text: '继续输出', streaming: true })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="stream-caret"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('#6：R-163 T1 口径改写 —— span.ch 逐字符渐显退役（语义被 Markdown 渲染取代），正文走 md-body 唯一链路', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: 'abcdefghijkl', streaming: true }], null, true)
    msg.id = 'a-ch'
    const wrapper = await mountPanel([msg])
    const bubble = wrapper.find('[data-testid="msg-bubble-a-ch"]')
    // chSplit/CH_TAIL 已退役：零 span.ch 包裹，全文经 markdown-it → DOMPurify 出 md-body
    expect(bubble.findAll('span.ch')).toHaveLength(0)
    expect(bubble.find('.md-body').exists()).toBe(true)
    expect(bubble.find('.md-body').text()).toContain('abcdefghijkl')
    // 终态消息同链路（纯文本经 markdown 渲染一次成型，无 ch 包裹）
    const done = buildMessage([{ kind: 'text', seg: 0, text: 'abcdefghijkl', streaming: false }], null, false)
    done.id = 'a-done'
    const wrapper2 = await mountPanel([done])
    expect(wrapper2.find('[data-testid="msg-bubble-a-done"]').findAll('span.ch')).toHaveLength(0)
    expect(wrapper2.find('[data-testid="msg-bubble-a-done"]').find('.md-body').exists()).toBe(true)
    wrapper.unmount()
    wrapper2.unmount()
  })
})

/**
 * R-161（AI 面板滚动行为专项，决策卡定稿 + 会审 6 MINOR 并入）：
 * 阈值拆分（跟随闸 32 / 浮钮滞回 **160-32**，R-165 S4 用户直裁放宽；原 64-24）/ RO 内容重钉底 /
 * 平滑回底 CSS 类 / reduced-motion 呼吸豁免。回声闸（落点容差 ≤1px）由 demo _trace
 * CDP 打点实证（施工报告 M1 留证），jsdom 不派发真实 scroll 回声，不设例。
 */
describe('R-161：阈值拆分滞回 / RO 内容重钉 / 平滑回底 / reduced-motion 豁免', () => {
  /** 可滚动夹具：scrollHeight=1000 / clientHeight=500，scrollTop 直写后经 onMsgScroll 驱动。 */
  async function mountScrolled(): Promise<{ wrapper: ReturnType<typeof mount>; wrap: HTMLElement }> {
    const wrapper = await mountPanel([
      buildMessage([{ kind: 'text', seg: 0, text: '长文', streaming: false }])
    ])
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    return { wrapper, wrap }
  }

  function scrollToDist(wrap: HTMLElement, dist: number, wrapper: ReturnType<typeof mount>): void {
    wrap.scrollTop = 1000 - 500 - dist
    wrap.dispatchEvent(new Event('scroll'))
    void wrapper.vm.$nextTick()
  }

  it('阈值拆分①：离底 160px（≤160）不出浮钮（钉死 160 下侧；旧 64 下侧口径随 S4 放宽退役）', async () => {
    const { wrapper, wrap } = await mountScrolled()
    scrollToDist(wrap, 160, wrapper)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('阈值拆分②：离底 200px（>160）出浮钮（钉死 160 上侧）', async () => {
    const { wrapper, wrap } = await mountScrolled()
    scrollToDist(wrap, 200, wrapper)
    await wrapper.vm.$nextTick()
    const backBtn = wrapper.find('[data-testid="back-to-bottom"]')
    expect(backBtn.exists()).toBe(true)
    expect(backBtn.text()).toContain('回到底部')
    wrapper.unmount()
  })

  it('滞回带：出钮后回滚到离底 100px（带内）仍显；回近底 <32px 才隐（会审 M4 → S4 带 32~160）', async () => {
    const { wrapper, wrap } = await mountScrolled()
    scrollToDist(wrap, 200, wrapper)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(true)
    // 回滚 100px：滞回带（32~160）内保持原状，不闪隐
    scrollToDist(wrap, 100, wrapper)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(true)
    // 回近底 20px（<32）：隐藏
    scrollToDist(wrap, 20, wrapper)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('平滑回底：浮钮点击 = 挂 scroll-behavior:smooth 类 + 瞬跳钉底 + 显式收钮（会审 M2/M3）', async () => {
    vi.useFakeTimers()
    try {
      const { wrapper, wrap } = await mountScrolled()
      scrollToDist(wrap, 200, wrapper) // >160 显钮阈（S4 放宽）
      await wrapper.vm.$nextTick()
      const backBtn = wrapper.find('[data-testid="back-to-bottom"]')
      await backBtn.trigger('click')
      await wrapper.vm.$nextTick()
      expect(wrap.scrollTop).toBe(1000) // jsdom 下 CSS 类不同步动画，scrollTop 由 pinBottom 瞬跳
      expect(wrap.classList.contains('scroll-smooth')).toBe(true) // CSS 类已挂（禁 scrollTo smooth）
      expect(wrapper.find('[data-testid="back-to-bottom"]').exists()).toBe(false) // 显式收钮
      vi.advanceTimersByTime(600)
      expect(wrap.classList.contains('scroll-smooth')).toBe(false) // ~600ms 后移除类
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  /** R-161 可控 RO 桩：捕获回调由测试手动触发（jsdom 无真实 RO；setup.ts 全局桩为 no-op）。 */
  function useCapturedRO(): { cb: ResizeObserverCallback | null } {
    const captured: { cb: ResizeObserverCallback | null } = { cb: null }
    class CaptureRO {
      constructor(cb: ResizeObserverCallback) {
        captured.cb = cb
      }
      observe(): void {}
      unobserve(): void {}
      disconnect(): void {}
    }
    vi.stubGlobal('ResizeObserver', CaptureRO)
    return captured
  }

  it('RO 重钉正例：confirm_request 帧插入后 scrollHeight 增量 ⇒ 跟随态重钉到底（RC-1/2 修复）', async () => {
    const captured = useCapturedRO()
    const store = useAiStore()
    const message = buildMessage([{ kind: 'text', seg: 0, text: '前置正文', streaming: true }], null, true)
    store.messages = [message]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    wrap.scrollTop = 1000 // 贴底（跟随态）
    // 确认门帧到达（工具段插入 = 卡体增高前兆；指纹 watch 只盯末段，RO 接管钉底）
    store.handleFrame(
      { type: 'confirm_request', toolCallId: 'c1', name: 'start_export', args: '{"taskId":1}', timeoutSeconds: 120, runId: 'run-1', seq: 2 },
      message.id
    )
    await wrapper.vm.$nextTick()
    // 卡体展开：scrollHeight 1000 → 1300，RO 回调触发
    Object.defineProperty(wrap, 'scrollHeight', { value: 1300, configurable: true })
    captured.cb?.([] as unknown as ResizeObserverEntry[], {} as ResizeObserver)
    await wrapper.vm.$nextTick()
    expect(wrap.scrollTop).toBe(1300) // 已重钉到新底部
    wrapper.unmount()
  })

  it('RO 重钉负例：用户已上滑暂停跟随（followBottom=false）⇒ 内容增高不钉底（补 R-154 复核登记缺口）', async () => {
    const captured = useCapturedRO()
    const store = useAiStore()
    const message = buildMessage([{ kind: 'text', seg: 0, text: '前置正文', streaming: true }], null, true)
    store.messages = [message]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    scrollToDist(wrap, 100, wrapper) // 用户上滑：followBottom=false
    store.handleFrame(
      { type: 'confirm_request', toolCallId: 'c1', name: 'start_export', args: '{"taskId":1}', timeoutSeconds: 120, runId: 'run-1', seq: 2 },
      message.id
    )
    await wrapper.vm.$nextTick()
    Object.defineProperty(wrap, 'scrollHeight', { value: 1300, configurable: true })
    captured.cb?.([] as unknown as ResizeObserverEntry[], {} as ResizeObserver)
    await wrapper.vm.$nextTick()
    expect(wrap.scrollTop).toBe(400) // 视口原地不动（用户上滑不打扰）
    wrapper.unmount()
  })

  it('reduced-motion 豁免：呼吸**不被抑制**（状态语义非纯装饰）+ 光标抑制走 motion-reduce:animate-none', async () => {
    const source = (await import('@/components/AiPanel/AiPanel.vue?raw')).default
    // R-165 S5c：媒体查询不再手写 —— 组件整体零 <style> 块（抑制改由 Tailwind motion-reduce 变体承载）
    expect(source).not.toContain('<style')
    expect(source).not.toContain('prefers-reduced-motion')
    // 呼吸 = 生成中状态语义 ⇒ 元素上不得挂任何 reduced-motion 抑制
    expect(source).toContain('animate-breathe')
    expect(source).not.toMatch(/motion-reduce:animate-none[^']*animate-breathe|animate-breathe[^']*motion-reduce:animate-none/)
    // 光标 = 纯装饰 ⇒ 抑制保留（motion-reduce 变体 + 律动类同挂一条类串上）
    expect(source).toContain('animate-caret motion-reduce:animate-none')
    // 豁免后律动照常（律动本体落 tailwind.config.js，keyframes 名 aiBreathe 防全局撞名）
    const { readFileSync } = await import('node:fs')
    expect(readFileSync('tailwind.config.js', 'utf8')).toContain("breathe: 'aiBreathe 1.5s ease-in-out infinite'")
  })

  it('role="log"：滚动容器补无障碍语义（无视觉影响）', async () => {
    const { wrapper } = await mountScrolled()
    expect(wrapper.find('.overflow-y-auto').attributes('role')).toBe('log')
    wrapper.unmount()
  })
})

/**
 * R-160（issue #14 HITL 三缺陷，决策卡定稿 + 三审 4 MINOR 并入）：
 * M1 历史回读决策元数据 / M2 回执独立块（恒可见）/ M3 快照路 expiresAt 续走 + 旧数据静态兜底 /
 * M4 方案 A 容器层 / M5 删 RO 单一 measure。
 */
describe('R-160：HITL 三缺陷（回读恢复 / 回执独立块 / 倒计时续走 / 溢出方案 A / 删 RO）', () => {
  /** 历史整替夹具：一条 ASSISTANT 带 toolCalls + toolCallStates（R-144 载荷面）。 */
  function historyWith(states: Record<string, unknown>, calls: Array<{ id: string; name: string; arguments: string }>) {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 1,
      messages: [
        { type: 'ASSISTANT', text: '历史正文', toolCalls: calls, toolResponses: [], toolCallStates: states }
      ]
    })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })
    return store
  }

  it('M2/M1：loadHistory 回读决策元数据 ⇒ 回执独立块恒可见（无需展开卡）+ 三字段落段', async () => {
    const store = historyWith(
      { 'tc-1': { status: 'EXECUTED', kind: 'CONFIRM', decision: 'approved', decidedAt: 1_700_000_000_000 } },
      [{ id: 'tc-1', name: 'start_export', arguments: '{"taskId":3}' }]
    )
    await store.loadHistory()
    const part = store.messages[0]!.parts.find((p) => p.kind === 'tool' && p.toolCallId === 'tc-1') as ToolPart
    expect(part).toMatchObject({ decision: 'approved', decidedAt: 1_700_000_000_000, kind2: 'CONFIRM', status: 'succeeded' })

    const wrapper = await mountPanel(store.messages)
    // 回执独立块：不点任何卡展开即恒可见（旧行为：回执藏在卡体内，默认折叠不可见）
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.exists()).toBe(true)
    expect(receipt.text()).toContain('已确认')
    expect(receipt.text()).toMatch(/\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}/) // decidedAt 回显（R-162 渐进格式：1.7e12=2023 跨年档）
    // 紧随源工具卡（DOM 序：tool-card 在前，hitl-receipt 在后）
    const html = wrapper.html()
    expect(html.indexOf('tool-card-tc-1')).toBeGreaterThanOrEqual(0)
    expect(html.indexOf('hitl-receipt')).toBeGreaterThan(html.indexOf('tool-card-tc-1'))
    wrapper.unmount()
  })

  it('M1：submittedValues 限 FRONTEND 回读 —— GF 提交值可回溯；坏 JSON 兜底不伪造；CONFIRM 不读', async () => {
    const store = historyWith(
      {
        'g1': { status: 'FRONTEND_RESULT', kind: 'FRONTEND', decision: 'approved', submittedValues: '{"region":"HB"}' },
        'g2': { status: 'FRONTEND_RESULT', kind: 'FRONTEND', submittedValues: 'not-json{' },
        'c1': { status: 'EXECUTED', kind: 'CONFIRM', decision: 'approved', submittedValues: '{"taskId":3}' }
      },
      [
        { id: 'g1', name: 'generative_form', arguments: '{}' },
        { id: 'g2', name: 'generative_form', arguments: '{}' },
        { id: 'c1', name: 'start_export', arguments: '{"taskId":3}' }
      ]
    )
    await store.loadHistory()
    const parts = store.messages[0]!.parts.filter((p) => p.kind === 'tool') as ToolPart[]
    const g1 = parts.find((p) => p.toolCallId === 'g1')!
    const g2 = parts.find((p) => p.toolCallId === 'g2')!
    const c1 = parts.find((p) => p.toolCallId === 'c1')!
    expect(g1.submittedValues).toEqual({ region: 'HB' }) // JSON.parse 成功
    expect(g2.submittedValues).toBeUndefined() // 坏 JSON 兜底（不伪造提交值）
    expect(c1.submittedValues).toBeUndefined() // 限 FRONTEND：CONFIRM 的 submittedValues 不回读

    const wrapper = await mountPanel(store.messages)
    const values = wrapper.findAll('[data-testid="receipt-values"]')
    expect(values).toHaveLength(1) // 仅 g1 出「已提交值」位（折进 mono 盒的 values 键）
    await wrapper.find('[data-testid="receipt-args-toggle-g1"]').trigger('click')
    expect(wrapper.find('[data-testid="receipt-values"]').text()).toContain('HB')
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(3)
    wrapper.unmount()
  })

  // R-166 口径①重写（原 R-160 M3「快照透出 expiresAt/atMs ⇒ 续走倒计时」）：
  // 快照里的 `atMs` 是**陈旧帧口径**，照搬"帧差值折算"正是症②（刷新回到 04:00）的成因；
  // 新口径下快照路**不锚时钟**，倒计时等挂起流首个心跳校正 —— 故本用例锁两件事：
  // ①快照字段照原样透出（对账口径不丢）；②无心跳时不伪造倒计时（静态「等待人工确认」）。
  it('M3/R-166：快照透出 expiresAt/atMs 但**不锚**倒计时（陈旧帧不折算）—— 静态「等待人工确认」', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    const atMs = Date.now()
    const expiresAt = atMs + 60_000
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', expiresAt, atMs }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    await store.restoreSuspendedSnapshot()
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1') as ToolPart
    expect(part).toMatchObject({ requiresAction: true, status: 'pending', expiresAt, atMs })
    expect(store.pendingConfirm).toMatchObject({ toolCallId: 'c1', expiresAt, atMs })
    // 快照路不写门卡时钟（唯一的写点是实时确认帧 / 挂起期心跳）
    expect(store.confirmClock).toBeNull()

    const wrapper = await mountPanel(store.messages)
    const card = wrapper.find('[data-testid="pending-card-c1"]')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('等待人工确认')
    expect(card.text()).not.toContain('剩余确认时间')
    wrapper.unmount()
  })

  // R-166 口径①：刷新恢复后**首个心跳**即校正倒计时（新增用例，覆盖"恢复续走"）。
  it('R-166：快照恢复 + 首个挂起期心跳 ⇒ 倒计时按 timeoutSeconds − waitedSeconds 续走', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', expiresAt: 1_700_000_240_000, atMs: 1_700_000_000_000 }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    await store.restoreSuspendedSnapshot()
    expect(store.confirmClock).toBeNull()

    // 挂起期心跳（后端 ConfirmGate.heartbeatData 同帧两值）：已等待 60s / 上限 240s ⇒ 剩 180s
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', suspendKind: 'confirm', timeoutSeconds: 240, waitedSeconds: 60, heartbeatSeq: 1 })
    const clock = store.confirmClock
    expect(clock).toMatchObject({ toolCallId: 'c1', anchor: 'heartbeat', waitedSeconds: 60, totalSeconds: 240 })
    const remain = Math.round((clock!.deadlineMs - Date.now()) / 1000)
    expect(remain).toBeGreaterThan(178)
    expect(remain).toBeLessThanOrEqual(180)

    const wrapper = await mountPanel(store.messages)
    expect(wrapper.find('[data-testid="pending-card-c1"]').text()).toContain('剩余确认时间')
    wrapper.unmount()
  })

  it('M3：旧数据无 expiresAt ⇒ 不伪造倒计时，静态「等待人工确认」', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}' }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    await store.restoreSuspendedSnapshot()
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1') as ToolPart
    expect(part.requiresAction).toBe(true)
    expect(part.expiresAt).toBeUndefined() // 旧数据：不写入口径

    const wrapper = await mountPanel(store.messages)
    const card = wrapper.find('[data-testid="pending-card-c1"]')
    expect(card.text()).toContain('等待人工确认')
    expect(card.text()).not.toContain('剩余确认时间')
    wrapper.unmount()
  })

  it('M4：方案 A 容器层一刀切 —— 气泡 overflow:hidden + min-w-0；CollapsibleText 容器 anywhere 断行', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'probe.tool', kind2: 'BACKEND', status: 'succeeded', args: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', requiresAction: false }
      ])
    ])
    const bubble = wrapper.find('[data-testid="msg-bubble-a-1"]').element as HTMLElement
    expect(bubble.className).toContain('overflow-hidden')
    expect(bubble.className).toContain('min-w-0')
    const root = wrapper.findComponent(CollapsibleText).find('.min-w-0').element as HTMLElement
    expect(root.className).toContain('min-w-0')
    expect(root.className).toContain('[overflow-wrap:anywhere]')
    wrapper.unmount()
  })

  it('M5：CollapsibleText 删 RO —— 单一 measure（nextTick 读 scrollHeight），源码零 ResizeObserver', async () => {
    const source = (await import('@/components/AiPanel/CollapsibleText.vue?raw')).default
    expect(source).not.toContain('ResizeObserver')
    expect(source).toContain('scrollHeight')
    expect(source).toContain('nextTick')
  })
})

/**
 * R-162（元信息行布局 + 渐进时间格式，决策卡定稿 + 会审 4 MINOR 并入）：
 * M1 按钮紧邻时间戳（删 justify-between；用户行 row-reverse 同簇靠右）；
 * M2 formatProgressiveTs 五级渐进（now 注入可测）。
 */
describe('R-162：元信息行紧邻布局 + 渐进时间格式', () => {
  it('M1：AI 行 + 用户行按钮均紧邻时间戳，无行尾孤立按钮', async () => {
    const ai = buildMessage([{ kind: 'text', seg: 0, text: 'AI 回复', streaming: false }])
    ai.id = 'a-x'
    ai.timestamp = 1_700_000_000_000
    const user = buildMessage([{ kind: 'text', seg: 0, text: '用户提问', streaming: false }])
    user.id = 'u-x'
    user.role = 'user'
    user.timestamp = 1_700_000_000_000
    const wrapper = await mountPanel([user, ai])

    // AI 行：左对齐紧邻 [时间戳容器 | 按钮组容器]，两子无间隔元素
    const aiRow = wrapper.find('[data-testid="msg-ops-a-x"]')
    expect(aiRow.classes()).not.toContain('justify-between')
    expect(aiRow.classes()).not.toContain('flex-row-reverse')
    expect(aiRow.element.children).toHaveLength(2)
    expect(aiRow.element.children[0].querySelector('[data-testid="msg-timestamp"]')).not.toBeNull()
    // R-165 S4：按钮组常驻（撤 opacity-0 浮出），紧邻布局不变
    expect((aiRow.element.children[1] as HTMLElement).className).not.toContain('opacity-0')
    expect(aiRow.element.lastElementChild).toBe(aiRow.element.children[1]) // 末子 = 按钮组（无行尾孤立）

    // 用户行：row-reverse 保留（渲染为按钮贴时间戳左侧、整簇靠右内聚），DOM 序与结构不变
    const userRow = wrapper.find('[data-testid="msg-ops-u-x"]')
    expect(userRow.classes()).not.toContain('justify-between')
    expect(userRow.classes()).toContain('flex-row-reverse')
    expect(userRow.element.children).toHaveLength(2)
    expect(userRow.element.children[0].querySelector('[data-testid="msg-timestamp"]')).not.toBeNull()
    expect(userRow.element.lastElementChild).toBe(userRow.element.children[1])
    wrapper.unmount()
  })

  it('M2：formatProgressiveTs 五级边界（now 注入；自然日 0 点切分，先 diff 后年）', async () => {
    const { formatProgressiveTs } = await import('@/utils/format')
    // 固定 now = 2026-10-13（周二）12:00 本地
    const now = new Date(2026, 9, 13, 12, 0, 0).getTime()
    const at = (m: number, d: number, h: number, mi: number): number => new Date(2026, m - 1, d, h, mi).getTime()

    expect(formatProgressiveTs(at(10, 13, 8, 30), now)).toBe('08:30') // diff=0 今天
    expect(formatProgressiveTs(at(10, 12, 23, 59), now)).toBe('昨天 23:59') // diff=1 跨 0 点
    expect(formatProgressiveTs(at(10, 11, 9, 5), now)).toMatch(/^周. 09:05$/) // diff=2（2026-10-11=周日）
    expect(formatProgressiveTs(at(10, 7, 9, 5), now)).toMatch(/^周. 09:05$/) // diff=6 档内
    expect(formatProgressiveTs(at(10, 6, 9, 5), now)).toBe('10月6日 09:05') // diff=7 同年
    expect(formatProgressiveTs(new Date(2025, 11, 31, 23, 0).getTime(), now)).toBe('2025年12月31日 23:00') // 跨年
    // ISO 字符串输入 / 未来时刻回落 HH:mm / 非法值空串
    expect(formatProgressiveTs('2026-10-13T08:30:00', now)).toBe('08:30')
    expect(formatProgressiveTs(at(10, 14, 9, 0), now)).toBe('09:00')
    expect(formatProgressiveTs('not-a-date', now)).toBe('')
  })

  it('M2：formatProgressiveTs 同刻边界（diff=0 与时区无关；now 缺省取当前时刻不抛错）', async () => {
    const { formatProgressiveTs } = await import('@/utils/format')
    const noon = new Date(2026, 9, 13, 12, 0, 0).getTime()
    expect(formatProgressiveTs(noon, noon)).toBe('12:00') // 同刻 diff=0
    expect(formatProgressiveTs(undefined, noon)).toBe('')
    expect(typeof formatProgressiveTs(noon)).toBe('string') // now 缺省
  })
})


/**
 * R-163（AI 对话体验专项，K2.8 施工）：T1 Markdown 唯一链路 + XSS 白名单 + 流式围栏降级 +
 * mermaid 占位 / T0+T2 工具摘要映射 / T3 赞踩本地态 / T4 编辑确认 + 继续按钮。
 */
describe('R-163：Markdown 正文链路 / 白名单 / 工具摘要 / 赞踩 / 编辑确认 / 继续', () => {
  /** el-input 桩保留（R-165 S3a 后 AiPanel 内已零命中，仅防回退）。 */
  const stubsWithInput = {
    ...stubs,
    'el-input': {
      props: ['modelValue'],
      template: '<textarea class="stub-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    }
  }

  it('T1：正文 Markdown 渲染 —— 加粗/列表/表格经 markdown-it 出真实标签（md-body v-html）', async () => {
    const msg = buildMessage([
      { kind: 'text', seg: 0, text: '**加粗** 与 `code`\n\n- 项一\n- 项二\n\n| A | B |\n| - | - |\n| 1 | 2 |', streaming: false }
    ])
    msg.id = 'a-md'
    const wrapper = await mountPanel([msg])
    const md = wrapper.find('[data-testid="msg-bubble-a-md"] .md-body')
    expect(md.exists()).toBe(true)
    expect(md.html()).toMatch(/<strong class="\[font-weight:650\]">加粗<\/strong>/) // R-165 S5c：strong 带注入类
    expect(md.findAll('li')).toHaveLength(2)
    expect(md.findAll('td')).toHaveLength(2)
    wrapper.unmount()
  })

  it('T1 白名单：script/onerror/iframe 净化零命中；a.href 保留但 javascript: 协议被剥', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown(
      '[正常](https://example.com) [恶意](javascript:alert(1))\n\n<script>alert(1)</script><img src="x" onerror="alert(1)"><iframe src="https://evil"></iframe>',
      false
    )
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('iframe')
    expect(html).toContain('href="https://example.com"')
    expect(html).not.toContain('href="javascript:') // markdown-it validateLink 拒链（残留仅为惰性文本）
  })

  it('T1 流式围栏：未闭合尾围栏渲染纯文本（无高亮 class），闭合后二次渲染才 language-*', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const open = renderMarkdown('```js\nconst a = 1\nconst b', true)
    expect(open.html).not.toContain('language-js')
    expect(open.html).toContain('const b')
    const closed = renderMarkdown('```js\nconst a = 1\n```\n\n完成', false)
    expect(closed.html).toContain('language-js')
    expect(closed.html).toContain('hljs')
  })

  it('T1 mermaid：闭合围栏抽占位 div（异步渲染回填），流式未闭合保持源码块', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const stream = renderMarkdown('```mermaid\ngraph TD; A-->B', true)
    expect(stream.mermaid).toHaveLength(0)
    expect(stream.html).toContain('graph TD')
    const closed = renderMarkdown('```mermaid\ngraph TD; A-->B;\n```\n\n说明文字', false)
    expect(closed.mermaid).toHaveLength(1)
    expect(closed.mermaid[0].source).toContain('graph TD')
    expect(closed.html).toContain('language-mermaid') // 闭合后保留源码块（渲染回填/失败兜底同体）
  })

  it('T1 降级：>10k 长文结构照渲但无高亮/mermaid（DEGRADE_AT_CHARS 阈值）', async () => {
    const { renderMarkdown, DEGRADE_AT_CHARS } = await import('@/utils/markdown')
    expect(DEGRADE_AT_CHARS).toBe(10_000)
    const long = `# 标题\n\n${'x'.repeat(DEGRADE_AT_CHARS + 100)}`
    const { html } = renderMarkdown(long, false)
    expect(html).toMatch(/<h1 class="[^"]*">/) // 结构照渲（R-165 S5c 起 h1 带渲染层注入的排版类）
    expect(html).not.toContain('language-') // 高亮降级（阈值实例无 highlight）
    expect(html).not.toContain('hljs')
  })

  it('T0/T2：工具摘要映射 —— start_export 按 args 出「启动导出作业（任务 #7）」，未知工具兜底 名+状态', async () => {
    const { toolSummary } = await import('@/utils/tool-summaries')
    const mk = (name: string, args: string, status: ToolPart['status']): ToolPart =>
      ({ kind: 'tool', seg: 0, toolCallId: 't1', name, kind2: 'BACKEND', status, args, requiresAction: false }) as ToolPart
    expect(toolSummary(mk('start_export', '{"taskId":7}', 'running'))).toBe('启动导出作业（任务 #7）')
    expect(toolSummary(mk('create_task', '{"type":"EXPORT","title":"月报"}', 'running'))).toBe('创建导出任务「月报」')
    expect(toolSummary(mk('mystery_tool', '{}', 'failed'))).toBe('mystery_tool · 失败')
    const wrapper = await mountPanel([
      buildMessage([{ kind: 'tool', seg: 0, toolCallId: 's1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":7}', timeoutSeconds: 120, requiresAction: true }])
    ])
    expect(wrapper.find('[data-testid="tool-summary-s1"]').text()).toBe('启动导出作业（任务 #7）')
    wrapper.unmount()
  })

  it('T2：9 态工具卡状态 tag 全出（等待确认/调用中/已成功/执行失败/已拒绝/超出当前范围/会话已失效/已取消/结局未知）', async () => {
    const parts: ToolPart[] = [
      { kind: 'tool', seg: 0, toolCallId: 't1', name: 'probe.tool', kind2: 'BACKEND', status: 'pending', args: '{}', requiresAction: true },
      { kind: 'tool', seg: 1, toolCallId: 't2', name: 'probe.tool', kind2: 'BACKEND', status: 'running', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 2, toolCallId: 't3', name: 'probe.tool', kind2: 'BACKEND', status: 'succeeded', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 3, toolCallId: 't4', name: 'probe.tool', kind2: 'BACKEND', status: 'failed', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 4, toolCallId: 't5', name: 'probe.tool', kind2: 'BACKEND', status: 'rejected', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 5, toolCallId: 't6', name: 'probe.tool', kind2: 'BACKEND', status: 'blocked', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 6, toolCallId: 't7', name: 'probe.tool', kind2: 'BACKEND', status: 'expired', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 7, toolCallId: 't8', name: 'probe.tool', kind2: 'BACKEND', status: 'cancelled', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 8, toolCallId: 't9', name: 'probe.tool', kind2: 'BACKEND', status: 'unknown', args: '{}', requiresAction: false }
    ]
    const wrapper = await mountPanel([buildMessage(parts)])
    for (const label of ['等待确认', '调用中', '已成功', '执行失败', '已拒绝', '超出当前范围', '会话已失效', '已取消', '结局未知']) {
      expect(wrapper.text()).toContain(label)
    }
    // 展开体 input/output 标签（T2 裸 JSON 墙消除的结构性锁）
    await wrapper.find('[data-testid="block-toggle-a-1:t3"]').trigger('click')
    expect(wrapper.find('[data-testid="block-body-a-1:t3"]').text()).toContain('输入')
    // R-165 S1：状态胶囊改自绘 span chip（EP el-tag 退场，el-tag 桩 span.tag 全 DOM 零命中）
    expect(wrapper.findAll('.tag')).toHaveLength(0)
    const headOf = (id: string): HTMLElement =>
      wrapper.find(`[data-testid="block-toggle-a-1:${id}"]`).element.firstElementChild as HTMLElement
    const chipOf = (id: string) =>
      wrapper.find(`[data-testid="block-toggle-a-1:${id}"]`).findAll('span')
        .filter((s) => s.classes().includes('text-[9.5px]'))
    // 底座（v7 .stchip）：9.5px / 圆角 5 / padding 0 5px；色族按态走（蓝=在跑·待确认，绿=成功，红=失败，灰=其余）
    expect(chipOf('t1')).toHaveLength(1)
    expect(chipOf('t3')[0]!.classes()).toEqual(expect.arrayContaining(['text-[9.5px]', 'leading-[1.7]', 'px-[5px]', 'rounded-[5px]']))
    expect(chipOf('t1')[0]!.text()).toBe('等待确认')
    expect(chipOf('t1')[0]!.classes()).toContain('text-[#2563eb]')
    expect(chipOf('t3')[0]!.text()).toBe('已成功')
    expect(chipOf('t3')[0]!.classes()).toContain('bg-[#edf7f0]')
    expect(chipOf('t4')[0]!.classes()).toContain('bg-[#fdf1ef]')
    // R-165 S1：状态图标三态自绘（EP el-icon 退场）—— 成功绿圆环✓ / 失败红圆环✗ / 进行中旋转环
    expect(headOf('t2').className).toContain('animate-spin')
    expect(headOf('t2').className).toContain('border-t-[#2563eb]')
    expect(headOf('t3').textContent).toBe('✓')
    expect(headOf('t3').className).toContain('border-[1.2px]')
    expect(headOf('t3').className).toContain('border-[#1a7f45]')
    expect(headOf('t4').textContent).toBe('✗')
    expect(headOf('t4').className).toContain('border-[#c0392b]')
    expect(headOf('t1').textContent).toBe('○') // 静默族：字符无环
    expect(headOf('t1').className).not.toContain('rounded-[50%]')
    wrapper.unmount()
  })

  it('T3：赞踩本地态 —— 选中互斥、再点取消、上报 rating 序列（1 → -1 → 0）', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])
    msg.id = 'a-fb'
    const wrapper = await mountPanel([msg])
    const up = wrapper.find('[data-testid="feedback-up-a-fb"]')
    const down = wrapper.find('[data-testid="feedback-down-a-fb"]')
    expect(up.exists()).toBe(true)
    expect(down.exists()).toBe(true)
    await up.trigger('click')
    expect(up.attributes('aria-pressed')).toBe('true')
    // R-165 S1：选中态 = 赞蓝族（text #2563eb + bg #eff4ff），且不挂 hover:* —— 悬停不回退成灰字灰底
    expect(up.classes()).toEqual(expect.arrayContaining(['is-on', 'text-[#2563eb]', 'bg-[#eff4ff]']))
    expect(up.classes()).not.toContain('hover:bg-[#eaecf0]')
    expect(api.feedbackSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ messageId: 'a-fb', rating: 1 }))
    // 互斥：赞后点踩 ⇒ 踩选中、赞取消（rating -1）
    await down.trigger('click')
    expect(down.attributes('aria-pressed')).toBe('true')
    expect(down.classes()).toEqual(expect.arrayContaining(['is-on', 'text-[#c0392b]', 'bg-[#fdf1ef]'])) // 踩红族保留
    expect(up.attributes('aria-pressed')).toBe('false')
    expect(api.feedbackSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ rating: -1 }))
    // 再点踩 ⇒ 取消（rating 0）
    await down.trigger('click')
    expect(down.attributes('aria-pressed')).toBe('false')
    expect(api.feedbackSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ rating: 0 }))
    wrapper.unmount()
  })

  it('T3：操作行 28×28 热区 + gap≥8（w-7 h-7 / gap-2）', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])
    msg.id = 'a-hit'
    const wrapper = await mountPanel([msg])
    const btn = wrapper.find('[data-testid="feedback-up-a-hit"]')
    expect(btn.classes()).toContain('w-7')
    expect(btn.classes()).toContain('h-7')
    // R-165 S1：图标按钮 = 原生 button + demo v7 `.icon-btn` 配方（UA 复位 + 28×28/圆角 8 + 15×15 描边 1.6 SVG）
    expect(btn.element.tagName).toBe('BUTTON')
    expect(btn.classes()).toEqual(expect.arrayContaining([
      'rounded-[8px]', 'border-0', 'bg-transparent', 'p-0', 'transition',
      'text-[#8b949e]', 'hover:text-[#1f2328]', 'hover:bg-[#eaecf0]',
      '[&_svg]:w-[15px]', '[&_svg]:h-[15px]', '[&_svg]:fill-none', '[&_svg]:stroke-current', '[&_svg]:[stroke-width:1.6]'
    ]))
    const copy = wrapper.find('[data-testid="copy-msg-a-hit"]')
    expect(copy.element.tagName).toBe('BUTTON')
    expect(copy.classes()).toContain('rounded-[8px]')
    expect(copy.find('svg').element.getAttribute('viewBox')).toBe('0 0 24 24')
    const row = wrapper.find('[data-testid="msg-ops-a-hit"] > div:last-child')
    expect(row.classes()).toContain('gap-2')
    // R-165 S2：原生 title 退场 → demo v7 `.tip` 黑底气泡（具名组 group/tip 驱动显隐，无 JS / 无伪元素）
    expect(btn.classes()).toContain('group/tip')
    expect(btn.attributes('title')).toBeUndefined()
    expect(btn.attributes('aria-label')).toBe('赞') // 无障碍名改由 aria-label 承载
    const tip = btn.find('span')
    expect(tip.classes()).toEqual(expect.arrayContaining([
      'absolute', 'bottom-[calc(100%+7px)]', 'left-1/2', '-translate-x-1/2',
      'bg-[#1f2328]', 'text-white', 'text-[11px]', 'rounded-[6px]', 'px-[8px]', 'py-[3px]',
      'pointer-events-none', 'opacity-0', 'group-hover/tip:opacity-100'
    ]))
    expect(tip.text()).toBe('赞')
    // 小三角 = 旋转方块（::after 属手写 CSS，铁律禁；改用 8×8 rotate-45 方块贴气泡下沿）
    const arrow = tip.find('span')
    expect(arrow.classes()).toEqual(expect.arrayContaining(['absolute', 'top-full', 'left-1/2', '-translate-y-1/2', 'rotate-45', 'h-[8px]', 'w-[8px]', 'bg-[#1f2328]']))
    // 复制钮同口径（文案不同）
    expect(copy.find('span').text()).toBe('复制该条消息正文')
    expect(copy.attributes('title')).toBeUndefined()
    expect(copy.attributes('aria-label')).toBe('复制该条消息正文')
    wrapper.unmount()
  })

  it('T4：编辑防覆盖 —— 输入框非空白先弹确认（确认才回填，取消不覆盖）', async () => {
    const store = useAiStore()
    const user = buildMessage([{ kind: 'text', seg: 0, text: '用户原话', streaming: false }])
    user.id = 'u-edit'
    user.role = 'user'
    store.messages = [user]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs: stubsWithInput } })
    await wrapper.vm.$nextTick()
    const input = wrapper.find('[data-testid="composer-input"]')
    await input.setValue('草稿内容')
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    expect(confirmSpy).toHaveBeenCalled()
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('草稿内容') // 未覆盖
    confirmSpy.mockReturnValue(true)
    await wrapper.find('[data-testid="edit-msg-u-edit"]').trigger('click')
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('用户原话') // 确认后回填
    confirmSpy.mockRestore()
    wrapper.unmount()
  })

  it('T4：「继续」按钮 —— 断流态最后一条助手消息出入口，点击直发「继续」', async () => {
    const store = useAiStore()
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '部分回答', streaming: false }])
    reply.id = 'a-cont'
    store.messages = [reply]
    store.historyLoaded = true
    store.lastRoundOutcome = 'interrupted'
    api.chat.mockReset()
    api.chat.mockResolvedValue(undefined)
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs: stubsWithInput } })
    await wrapper.vm.$nextTick()
    const cont = wrapper.find('[data-testid="continue-round"]')
    expect(cont.exists()).toBe(true)
    await cont.trigger('click')
    await wrapper.vm.$nextTick()
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0][0].message).toBe('继续')
    wrapper.unmount()
  })

  it('T4：「继续」按钮负例 —— 正常收官（ok）不出入口；仅末条助手消息出', async () => {
    const store = useAiStore()
    const older = buildMessage([{ kind: 'text', seg: 0, text: '旧回答', streaming: false }])
    older.id = 'a-old'
    const last = buildMessage([{ kind: 'text', seg: 0, text: '新回答', streaming: false }])
    last.id = 'a-new'
    store.messages = [older, last]
    store.historyLoaded = true
    store.lastRoundOutcome = 'ok'
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(false)
    store.lastRoundOutcome = 'cancelled'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="msg-ops-a-new"]').find('[data-testid="continue-round"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="msg-ops-a-old"]').find('[data-testid="continue-round"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

/** F1（复核 MAJOR 收口修）回归锁：断流置位 → 断流提示渲染（红族）+「继续」入口同帧。
 *  R-165 S2 改写：错误/降级/通知不再由 el-alert 承载（EP 退场），判据改自绘结构（data-testid + 色族）。 */
describe('R-163 收口修 F1：断流置位 → 提示渲染回归', () => {
  it('streamInterrupted=true ⇒ 断流提示（红族）渲染；false 则不渲染；EP Alert 零残留', async () => {
    const store = useAiStore()
    const reply = buildMessage([{ kind: 'text', seg: 0, text: '部分回答', streaming: false }])
    reply.id = 'a-f1'
    store.messages = [reply]
    store.historyLoaded = true

    // 断流现场（onClose 非终帧路径的置位结果）
    store.streamInterrupted = true
    store.lastRoundOutcome = 'interrupted'
    const wrapper = await mountPanel(store.messages)
    const alert = wrapper.find('[data-testid="alert-interrupted"]')
    expect(alert.exists()).toBe(true)
    expect(alert.element.tagName).toBe('DIV') // 自绘（非 EP 组件）
    // v7 `.alert` 红族：故障类用红（描边 + 浅底 + 红描边圆 !）
    expect(alert.classes()).toEqual(expect.arrayContaining([
      'border-[#f0d5d0]', 'bg-[#fdf1ef]', 'rounded-[10px]', 'px-[11px]', 'py-[9px]', 'gap-[9px]', 'border-solid'
    ]))
    expect(alert.text()).toContain('连接中断')
    expect(alert.text()).toContain('本轮没有收到完成帧')
    expect(wrapper.find('el-alert-stub').exists()).toBe(false) // el-alert 零残留
    // 与「继续」入口同帧出现（lastRoundOutcome 并列置位的另一面）
    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(true)
    // S2 顺带锁：降级类蓝族（v7「主模型不可用，已降级」同族）+ 通知槽（v7 `.notice` 虚线灰底）
    store.health = { ...store.health!, available: false }
    store.notice = '本轮已取消'
    await wrapper.vm.$nextTick()
    const degraded = wrapper.find('[data-testid="alert-degraded"]')
    expect(degraded.classes()).toEqual(expect.arrayContaining(['border-[#cddcff]', 'bg-[#eff4ff]']))
    expect(degraded.text()).toContain('AI 能力暂不可用')
    const notice = wrapper.find('[data-testid="notice-bar"]')
    expect(notice.classes()).toEqual(expect.arrayContaining(['border-dashed', 'border-[#e3e6ea]', 'bg-[#f6f7f9]']))
    expect(notice.text()).toContain('本轮已取消')
    store.health = { ...store.health!, available: true }
    store.notice = null
    wrapper.unmount()

    // 负例：正常收官不断流 ⇒ 提示不渲染
    store.streamInterrupted = false
    store.lastRoundOutcome = 'ok'
    const wrapper2 = await mountPanel(store.messages)
    expect(wrapper2.find('[data-testid="alert-interrupted"]').exists()).toBe(false)
    expect(wrapper2.find('.ai-chat [class*="rounded-[10px]"][class*="bg-[#fdf1ef]"]').exists()).toBe(false)
    expect(wrapper2.find('[data-testid="continue-round"]').exists()).toBe(false)
    wrapper2.unmount()
  })
})

/**
 * R-163B（视觉对齐 demo v6 + 横滚条止血，CU 实测定根因后补修）：
 * G1 .md-body 移出 scoped（防 v-html 选择器失效回归）/ G2 容器横滚止血 / G3 用户气泡 demo 灰 /
 * G4 代码块图容器 / G5 工具卡 hd 对齐 + 摘要去重 / G6 呼吸 demo 参数。
 */
describe('R-163B：视觉对齐 demo v6（样式生效 + 横滚止血）', () => {
  /** 拆样式源（R-165 S5c：`src/styles/ai-chat.css` 已删除 —— 面板样式只剩三份**类串源**）：
   *  `render` = 渲染层注入（src/utils/markdown.ts，v-html 正文族）；`template` = AiPanel.vue 全文
   *  （模板类串 + 是否还残留手写样式块）；`config` = tailwind.config.js（三个 keyframes 唯一归属）。 */
  async function styleSources(): Promise<{ render: string; template: string; config: string; styleBlocks: string[] }> {
    const { readFileSync } = await import('node:fs')
    const template = (await import('@/components/AiPanel/AiPanel.vue?raw')).default
    const re = /<style[^>]*>([\s\S]*?)<\/style>/g
    const styleBlocks: string[] = []
    let m: RegExpExecArray | null
    while ((m = re.exec(template)) !== null) {
      styleBlocks.push(m[1])
    }
    return {
      render: readFileSync('src/utils/markdown.ts', 'utf8'),
      template,
      config: readFileSync('tailwind.config.js', 'utf8'),
      styleBlocks
    }
  }

  it('G1：.md-body 整套排版改**渲染层吐工具类**（值口径与拆分前逐字一致 + AiPanel 零手写样式块）——防视觉口径漂移', async () => {
    const { render, template, styleBlocks } = await styleSources()
    // ① 根因面：v-html 节点不带 scope ⇒ 样式不再能靠全局样式表；组件侧也再无任何手写样式块
    expect(styleBlocks).toEqual([])
    expect(template).not.toContain('<style')
    // ② 视觉口径（原 ai-chat.css `.ai-chat .md-body` 族逐值锁死）
    expect(render).toContain("text-[13.8px] leading-[1.7] text-[#1f2328]") // 根容器 = .md-body 自身声明
    expect(render).toContain("h1: 'text-[16.5px]'") // D3 标题阶梯
    expect(render).toContain("h2: 'text-[15.5px]'")
    expect(render).toMatch(/h3: 'text-\[14\.2px\]'[\s\S]*h5: 'text-\[14\.2px\]'/)
    expect(render).toContain("h6: 'text-[13.8px]'")
    expect(render).toContain('mt-[18px] mb-[9px] leading-[1.5] [font-weight:650]') // 标题共族
    expect(render).toContain('mt-0 mb-[12px]') // p / ul / ol 段距
    expect(render).toContain('pl-[19px]') // 列表缩进
    expect(render).toContain('marker:text-[#8b949e]') // li::marker = --ink3
    expect(render).toContain('border-0 border-l-2 border-solid border-[#cddcff] text-[#8b949e] text-[12.6px] leading-[1.65]') // blockquote
    expect(render).toContain('whitespace-nowrap') // 行内 code chip
    expect(render).toContain("font-['Cascadia_Mono','Cascadia_Code',Consolas,'SF_Mono',Menlo,monospace]") // --mono 逐字
    expect(render).toContain('border border-solid border-[#e3e6ea] rounded-[10px] overflow-hidden bg-white') // D2 figure.code
    expect(render).toContain('bg-[#f8f8f8] text-[#333]') // pre 底色/字色
    expect(render).toContain('[word-break:normal] [overflow-wrap:anywhere]') // td 长 token 兜底（有意偏离 demo）
    expect(render).toContain('text-left whitespace-nowrap bg-[#f6f7f9] font-semibold text-[#57606a]') // th 单行
    // ③ 根容器的 overflow-wrap/word-break 仍未声明（长英文标识符被任意断开的元凶）
    const mdRoot = render.match(/MD_BODY_ROOT_CLASS = '([^']*)'/)?.[1] ?? ''
    expect(mdRoot).not.toContain('overflow-wrap')
    expect(mdRoot).not.toContain('word-break')
    // ④ 渲染层真的吐出来了（不是只写在常量里没人用）——逐标签断言产物
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown(
      '# 一级\n\n## 二级\n\n### 三级\n\n#### 四级\n\n##### 五级\n\n###### 六级\n\n正文 **粗** 与 `chip` 与 [a](https://e.com) 与 ![图](https://e.com/a.png)\n\n- 项\n\n1. 项\n\n> 引用\n\n---\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```js\nconst a = 1\n```\n',
      false
    )
    for (const token of [
      'class="mt-[18px] mb-[9px] leading-[1.5] [font-weight:650] text-[16.5px]"', // h1
      'text-[15.5px]', // h2
      'text-[14.2px]', // h3-h5
      'text-[13.8px]', // h6
      'class="mt-0 mb-[12px]"', // p
      'class="[font-weight:650]"', // strong
      'class="mt-0 mb-[12px] pl-[19px]"', // ul / ol
      'class="my-[3px] marker:text-[#8b949e]"', // li
      'border-l-2 border-solid border-[#cddcff]', // blockquote
      'text-[#2563eb]', // a
      'mx-0 my-[0.9em] border-0 border-t border-solid border-[#eef0f3]', // hr
      'md-code', // 行内 code 标记类（hook 放行）
      'md-pre', // 围栏 pre 标记类
      'max-w-full', // img 不超容器宽
      'border border-solid border-[#e3e6ea] rounded-[10px] overflow-hidden bg-white', // figure.code
      'class="flex items-center py-[5px] pr-[8px] pl-[11px] bg-[#f6f7f9] border-0 border-b border-solid border-[#eef0f3] text-[11.5px] text-[#57606a]"', // figcaption
      'class="lg ', // 语言名 span（标记类放行）
      'class="hlib ml-auto text-[10.5px] text-[#8b949e] ', // 高亮库名 span
      'class="border border-solid border-[#e3e6ea] px-[8px] py-[6px] text-left whitespace-nowrap bg-[#f6f7f9] font-semibold text-[#57606a]"', // th
      'class="border border-solid border-[#e3e6ea] px-[8px] py-[6px] text-left [word-break:normal] [overflow-wrap:anywhere]"' // td
    ]) {
      expect(html).toContain(token)
    }
  })

  it('G2：消息容器 overflow-x-hidden（AiPanel :119）+ 业务区 y-auto/x-hidden（App.vue :26）', async () => {
    const store = useAiStore()
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '正文', streaming: false }])
    msg.id = 'a-g2'
    store.messages = [msg]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    const wrap = wrapper.find('[role="log"]').element as HTMLElement
    expect(wrap.className).toContain('overflow-y-auto')
    expect(wrap.className).toContain('overflow-x-hidden') // 横滚条止血：容器层裁而不滚
    wrapper.unmount()
    const appSource = (await import('@/App.vue?raw')).default
    expect(appSource).toContain('overflow-y-auto overflow-x-hidden') // App.vue 业务区
  })

  it('G3：用户气泡对齐 demo —— 灰底 #f6f7f9 + 细边框 + 全圆角 12px + max-w-86% + 13.5px；助手无气泡底', async () => {
    const user = buildMessage([{ kind: 'text', seg: 0, text: '用户提问', streaming: false }])
    user.id = 'u-g3'
    user.role = 'user'
    const ai = buildMessage([{ kind: 'text', seg: 0, text: '助手回答', streaming: false }])
    ai.id = 'a-g3'
    const wrapper = await mountPanel([user, ai])
    const userBubble = wrapper.find('[data-testid="msg-bubble-u-g3"]').element as HTMLElement
    expect(userBubble.className).toContain('bg-[#f6f7f9]')
    expect(userBubble.className).toContain('border-[#eef0f3]')
    // R-165 S5a 项 3（demo v7.4 01 锁定项）：气泡改**全圆角 12px**，尾巴角类退役
    expect(userBubble.className).toContain('rounded-[12px]')
    expect(userBubble.className).not.toContain('rounded-[12px_12px_3px_12px]')
    expect(userBubble.className).toContain('max-w-[86%]')
    expect(userBubble.className).toContain('text-[13.5px]')
    const aiBubble = wrapper.find('[data-testid="msg-bubble-a-g3"]').element as HTMLElement
    expect(aiBubble.className).not.toContain('bg-[#f6f7f9]') // 助手全宽无气泡底
    wrapper.unmount()
  })

  it('G4：闭合围栏渲染代码块图容器（figcaption 语言标签 + type=button 复制**图标**钮，过 DOMPurify）', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown('```js\nconst a = 1\n```\n\n完成', false)
    // R-165 S5c：图容器/图题/语言名三处改渲染层注入的工具类（标记类 `code` / `lg` / `hlib` 保留）
    expect(html).toMatch(/<figure class="code [^"]*">/)
    expect(html).toMatch(/<figcaption class="flex items-center[^"]*">/)
    expect(html).toMatch(/<span class="lg [^"]*">js<\/span>/)
    // R-165 S5a 项 7：文字钮「复制」→ v7 图标钮（cpbtn + 两枚叠放 svg + tip 气泡），
    // 全部经 DOMPurify 白名单（svg/rect/path/polyline 与几何属性已在白名单内）
    expect(html).toContain('cpbtn')
    expect(html).not.toContain('>复制</button>')
    expect(html).toContain('aria-label="复制代码"')
    expect(html).toContain('class="i-copy"')
    expect(html).toContain('class="i-check"')
    expect(html).toContain('<rect')
    expect(html).toContain('<polyline')
    expect(html).toContain('viewBox=')
    expect(html).toContain('class="tip ')
    expect(html).toContain('type="button"')
    expect(html).toContain('<pre') // 图容器内 pre 保留（内部横滚）
  })

  it('G5：工具卡头对齐 demo（8px 10px / mono 名 / chevron）+ 摘要去重（映射值=工具名则只显一行）', async () => {
    // start_import 不带 taskId ⇒ 摘要 =「启动导入作业」= 工具名 ⇒ 去重（摘要位空）
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'i1', name: 'start_import', kind2: 'BACKEND', status: 'succeeded', args: '{}', requiresAction: false },
        { kind: 'tool', seg: 1, toolCallId: 'e1', name: 'start_export', kind2: 'BACKEND', status: 'succeeded', args: '{"taskId":7}', requiresAction: false }
      ])
    ])
    const head = wrapper.find('[data-testid="block-toggle-a-1:i1"]')
    expect(head.classes()).toContain('py-[8px]')
    expect(head.classes()).toContain('px-[10px]')
    expect(head.find('.font-mono').exists()).toBe(true) // 名称 mono
    expect(head.find('.tchev').exists()).toBe(true) // chevron
    // R-165 S1：卡头原生 button + 全 Tailwind UA 复位 —— preflight 关闭下按钮吃 UA 默认
    // （buttonface 灰底 / 2px outset 描边 / Arial）＝「工具卡巨丑」头号元凶，三件显式复位钉死。
    expect(head.element.tagName).toBe('BUTTON')
    expect(head.classes()).toEqual(expect.arrayContaining(['bg-transparent', 'border-0', 'font-[inherit]', 'text-left']))
    // 摘要运行中蓝字 / 失败红字（v7 .st-running/.st-failed 的 tsum 口径）；未态回落 --ink2 灰
    expect(wrapper.find('[data-testid="tool-summary-e1"]').classes()).toContain('text-[#57606a]')
    expect(wrapper.find('[data-testid="tool-summary-i1"]').text()).toBe('') // 去重：与工具名相同 ⇒ 不重复显
    expect(wrapper.find('[data-testid="tool-summary-e1"]').text()).toBe('启动导出作业（任务 #7）') // 互补摘要正常显
    wrapper.unmount()
  })

  it('G6：呼吸对齐 demo —— 1.5s / 7px 纯色 --blue / 旧 1.4s 口径清零（R-165 S5c 起样式源 = 模板类串 + config）', async () => {
    const { render, template, config } = await styleSources()
    const all = `${render}\n${template}\n${config}`
    // 律动本体（keyframes + 1.5s ease-in-out infinite）唯一归属 = tailwind.config.js
    expect(config).toContain("breathe: 'aiBreathe 1.5s ease-in-out infinite'")
    expect(config).toContain('aiBreathe: {')
    // 呼吸点几何/色（原 `.ai-chat .loading-breath .dot`）：7px 圆点 + 纯色 --blue(#2563eb)
    expect(template).toContain('w-[7px] h-[7px] rounded-full bg-[#2563eb] animate-breathe')
    // 容器与文字族（原 `.loading-breath` / `.txt`）
    expect(template).toContain('inline-flex items-center gap-[6px]')
    expect(template).toContain('text-[12.2px] text-[#57606a] tracking-[.5px]')
    // 旧口径清零（整仓样式源三份都不得再出现）
    expect(all).not.toContain('1.4s')
    expect(all).not.toContain('breathTxt')
    expect(all).not.toContain('--blue')
  })

  it('H1：全局 box-sizing:border-box 兜底（main.css，防 w-full+padding 外溢 20px 回归）', async () => {
    // css?raw 在 vitest 下被 CSS 插件截胡返回空串，走 fs 直读（G1/G2 的 vue?raw 不受影响）
    const { readFileSync } = await import('node:fs')
    const css = readFileSync('src/styles/main.css', 'utf8')
    expect(css).toContain('box-sizing: border-box')
    expect(css).toContain('*::before')
    expect(css).toContain('*::after')
  })

  it('D1：横滚只在表格 wrapper 内（单容器 md-table-scroll 与 R-163C H2 块级方案均已退役，D1→S5a 双区承接）', async () => {
    const { render, template } = await styleSources()
    const all = `${render}\n${template}`
    // 滚动条只属表体区（项目与渲染层双源都不得再出现单容器类）
    expect(all).not.toContain('md-table-scroll')
    // 表体区 = 唯一横滚容器（overflow-auto 写在 TABLE_BD_CLASS 上，消息容器仍守 sw===cw）
    expect(render).toContain('md-table-bd max-h-[320px] overflow-auto')
    // th 单行（原 `.md-body th { white-space: nowrap }` 改工具类注入）
    expect(render).toContain('whitespace-nowrap')
    // 两表列宽锁定（table-fixed + 等分），窄表不给最小宽度
    expect(render).toContain('table-fixed border-separate border-spacing-0')
  })

  it('D1→S5a：renderMarkdown 输出表格**双区** wrapper（表头区不滚 + 表体区 max-h-320 内滚，过 DOMPurify div 白名单）', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown('| 配置编码 | 说明 |\n| --- | --- |\n| PROJ_PARAM | 项目参数 |\n\n完成', false)
    // 三区标记类齐备（净化 hook 按标记类放行、随行 Tailwind 工具类同 div 保留）
    expect(html).toContain('md-table-wrap')
    expect(html).toContain('md-table-hd')
    expect(html).toContain('md-table-bd')
    // R-165 S5a：旧单容器类零残留（滚动条只贴表体右缘）
    expect(html).not.toContain('md-table-scroll')
    // 表头区：不滚（overflow-hidden + 6px gutter 抵消）；表体区：限高 320 + 内滚 + gutter stable
    expect(html).toMatch(/md-table-hd[^"]*overflow-hidden/)
    expect(html).toMatch(/md-table-bd[^"]*max-h-\[320px\]/)
    expect(html).toMatch(/md-table-bd[^"]*scrollbar-gutter:stable/)
    // 两区各一张表（等分列宽口径：w-full + table-fixed）
    expect(html.match(/table-fixed/g)).toHaveLength(2)
    expect(html.match(/<table/g)).toHaveLength(2)
    expect(html).toContain('<thead>')
    expect(html).toContain('<tbody>')
    // 竖滚动条只属表体区（表头区内无滚动容器标记）
    expect(html.indexOf('md-table-hd')).toBeLessThan(html.indexOf('md-table-bd'))
    // 列宽下限（R-165 S5a）：4 列 ⇒ demo 实证档位 min-w-[720px]（180px/列）；≤2 列不给最小宽度
    expect(html).not.toContain('min-w-[') // 本例是 2 列表（终裁边界：窄表不给最小宽度）
    const fourCol = renderMarkdown('| 配置编码 | 字段名 | 类型 | 说明 |\n| --- | --- | --- | --- |\n| 1 | 2 | 3 | 4 |\n', false).html
    expect(fourCol.match(/min-w-\[720px\]/g)).toHaveLength(2)
    const fiveCol = renderMarkdown('| A | B | C | D | E |\n| --- | --- | --- | --- | --- |\n| 1 | 2 | 3 | 4 | 5 |\n', false).html
    expect(fiveCol).toContain('min-w-[900px]')
  })
})

/**
 * R-165 S3a：输入区（composer）+ 生成式表单卡（GF）的 v7 结构锁，以及三项收口
 * （R6 消息间距 / R7 fab 定位 / R11 error 级通知色族）。
 * 其余用例把 FormRenderer 走 `true` 桩（本仓只关心挂载与宿主条件），本组**直接挂真身**锁内部结构。
 */
describe('R-165 S3a：输入区 v7 结构 + GF 表单卡 v7 结构 + R6/R7/R11 收口', () => {
  /** 六型齐备的 GF schema（用于把六型控件一次全渲出来）。 */
  const GF_SCHEMA: FormSpec = {
    scenario: 'CONFIRM',
    title: '需要你补充两个参数',
    fields: [
      { key: 'name', label: '任务名', type: 'text', required: true, placeholder: '请输入任务名' },
      { key: 'interval', label: '执行间隔（秒）', type: 'number', required: true },
      { key: 'dryRun', label: '预演', type: 'boolean', required: false },
      { key: 'startAt', label: '开始日期', type: 'date', required: false },
      {
        key: 'mode',
        label: '执行模式',
        type: 'enum',
        required: true,
        options: [{ value: 'fast', label: '快速' }, { value: 'safe' }]
      },
      {
        key: 'tags',
        label: '标签',
        type: 'multi_select',
        required: false,
        options: [{ value: 'a', label: '甲' }, { value: 'b' }]
      }
    ]
  }

  function mountGf(overrides: Partial<{ submitting: boolean; error: string | null; remainingSeconds: number | null }> = {}) {
    return mount(FormRenderer, {
      props: { schema: GF_SCHEMA, submitting: false, error: null, remainingSeconds: 120, ...overrides }
    })
  }

  it('composer：原生 textarea（rows=3）+ 28×28 方钮；busy 态换停止钮与虚线框（el-textarea/el-button 退场）', async () => {
    const store = useAiStore()
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '原始回答', streaming: false }])
    const wrapper = await mountPanel([msg])

    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.exists()).toBe(true)
    expect(box.element.tagName).toBe('TEXTAREA')
    expect(box.attributes('rows')).toBe('3')
    expect(box.classes()).toEqual(expect.arrayContaining([
      'flex-[1]', 'resize-none', 'rounded-[9px]', 'px-[10px]', 'py-[7px]',
      'text-[13px]', 'bg-[#f6f7f9]', 'placeholder:text-[#8b949e]', 'border-solid', 'border-[#e3e6ea]'
    ]))

    // 容器：10px 14px 内边距 + 顶细线 #eef0f3（v7 composer 行）
    const bar = box.element.parentElement as HTMLElement
    for (const cls of ['flex', 'items-end', 'gap-[8px]', 'border-t', 'px-[14px]', 'py-[10px]', 'bg-white']) {
      expect(bar.className).toContain(cls)
    }
    expect(wrapper.find('.el-textarea').exists()).toBe(false)

    // 常态：28×28 蓝发送钮（v7 的白色图标位 = ↑ 字符，v7.html:364 逐字照搬）
    const send = wrapper.find('[data-testid="send-btn"]')
    expect(send.element.tagName).toBe('BUTTON')
    expect(send.classes()).toEqual(expect.arrayContaining(['w-[28px]', 'h-[28px]', 'rounded-[8px]', 'bg-[#2563eb]', 'text-white', 'border-0']))
    expect(send.text()).toBe('↑')
    expect(wrapper.find('button[aria-label="停止生成"]').exists()).toBe(false)

    // busy（= !canSend）：输入框虚线待命 + 停止钮换入（28×28 #1f2328 + 9×9 白方块）
    store.loading = true
    await wrapper.vm.$nextTick()
    expect(box.classes()).toContain('border-dashed')
    expect(box.classes()).not.toContain('border-solid')
    expect(box.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(false)
    const stop = wrapper.find('button[aria-label="停止生成"]')
    expect(stop.exists()).toBe(true)
    expect(stop.classes()).toEqual(expect.arrayContaining(['w-[28px]', 'h-[28px]', 'rounded-[8px]', 'bg-[#1f2328]', 'border-0']))
    expect(stop.find('span').classes()).toEqual(expect.arrayContaining(['w-[9px]', 'h-[9px]', 'bg-white', 'rounded-[2px]']))
    wrapper.unmount()
  })

  it('GF 表单卡：容器/卡头/字段体/footer 四段 v7 结构；六型控件全原生（el-* 零残留）', () => {
    const wrapper = mountGf()
    const card = wrapper.element as HTMLElement
    for (const cls of ['border-solid', 'border-[#e3e6ea]', 'rounded-[10px]', 'bg-white', 'overflow-hidden', 'mb-2.5']) {
      expect(card.className).toContain(cls)
    }

    // 卡头：▤ + 标题 + 场景胶囊（右贴）
    const head = card.children[0] as HTMLElement
    for (const cls of ['px-[11px]', 'py-[9px]', 'border-b', 'border-[#eef0f3]', 'bg-[#f6f7f9]', 'text-[12.8px]', '[font-weight:650]']) {
      expect(head.className).toContain(cls)
    }
    expect(head.textContent).toContain('需要你补充两个参数')
    const chip = head.children[head.children.length - 1] as HTMLElement
    expect(chip.textContent).toBe('信息确认')
    for (const cls of ['ml-auto', 'text-[9.5px]', 'rounded-[5px]', 'bg-[#eff4ff]']) {
      expect(chip.className).toContain(cls)
    }

    // 字段体
    const body = card.children[1] as HTMLElement
    expect(body.className).toContain('p-[11px]')

    // label 口径（12.2px #57606a + mb 4px）
    const label = wrapper.find('div > span.block')
    expect(label.classes()).toEqual(expect.arrayContaining(['block', 'text-[12.2px]', 'mb-[4px]', 'text-[#57606a]']))

    // 六型控件：原生 input/select/checkbox，无一个 EP 控件
    const text = wrapper.find('input[type="text"]')
    expect(text.classes()).toEqual(expect.arrayContaining(['w-full', 'rounded-[8px]', 'px-[9px]', 'py-[6px]', 'text-[13px]', 'border-solid', 'border-[#e3e6ea]', 'bg-white']))
    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
    expect(wrapper.find('input[type="date"]').exists()).toBe(true)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(3) // 1 布尔开关 + 2 多选胶囊
    expect(wrapper.findAll('select')).toHaveLength(1)
    expect(wrapper.html()).not.toContain('<el-')

    // 双钮 + footer 时间行
    const cancel = wrapper.find('[data-testid="gf-cancel"]')
    const submit = wrapper.find('[data-testid="gf-submit"]')
    expect(cancel.element.tagName).toBe('BUTTON')
    expect(submit.element.tagName).toBe('BUTTON')
    expect(cancel.classes()).toEqual(expect.arrayContaining(['rounded-[8px]', 'px-[13px]', 'py-[5px]', 'text-[12.6px]', 'bg-white', 'border-[#e3e6ea]']))
    // R-165 S5a 项 10：提交钮撤 ml-auto（操作行 justify-end gap-2 相邻右对齐），取消在左相邻
    expect(submit.classes()).toEqual(expect.arrayContaining(['bg-[#2563eb]', 'text-white']))
    expect(submit.classes()).not.toContain('ml-auto')
    const actRow = submit.element.parentElement as HTMLElement
    expect(actRow.className).toContain('justify-end')
    expect(actRow.className).toContain('gap-2')
    expect(cancel.element.parentElement).toBe(actRow)
    expect(submit.text()).toBe('提交')
    const foot = wrapper.find('[data-testid="gf-deadline"]')
    expect(foot.element.tagName).toBe('P')
    expect(foot.classes()).toEqual(expect.arrayContaining(['px-[11px]', 'py-[9px]', 'border-t', 'text-[11.8px]', 'text-[#57606a]', 'bg-[#f6f7f9]']))
    expect(foot.text()).toContain('剩余填写时间')
    wrapper.unmount()
  })

  it('GF 表单卡：必填未填 ⇒ 字段红框 + 字段下 .fmsg；填齐 ⇒ 提交值上抛；超时 ⇒ footer 换文案', async () => {
    const wrapper = mountGf()
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await wrapper.vm.$nextTick()

    const name = wrapper.find('input[type="text"]')
    expect(name.classes()).toEqual(expect.arrayContaining(['border-[#c0392b]', 'bg-[#fdf1ef]']))
    const label = wrapper.find('div > span.block')
    expect(label.classes()).toContain('text-[#c0392b]')
    const msg = wrapper.findAll('span').find((item) => item.text().includes('「任务名」为必填项'))
    expect(msg).toBeTruthy()
    expect(msg!.classes()).toEqual(expect.arrayContaining(['text-[11.8px]', 'text-[#c0392b]', 'bg-[#fdf1ef]', 'border-[#f0d5d0]', 'rounded-[6px]']))
    // 可归属问题只落字段级，卡级错误条不重复承载
    expect(wrapper.find('[data-testid="gf-error"]').exists()).toBe(false)

    await name.setValue('数据同步')
    await wrapper.find('input[type="number"]').setValue('300')
    await wrapper.find('select').setValue('fast')
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    const submitted = wrapper.emitted('submit')
    expect(submitted).toBeTruthy()
    expect(submitted![0][0]).toEqual({
      name: '数据同步',
      interval: 300,
      dryRun: false,
      startAt: '',
      mode: 'fast',
      tags: []
    })
    wrapper.unmount()

    // 后端拒绝说明（FORM_RESULT_REJECTED）落卡级错误条
    const rejected = mountGf({ error: '服务端拒绝：执行间隔过大' })
    const bar = rejected.find('[data-testid="gf-error"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('服务端拒绝：执行间隔过大')
    expect(bar.classes()).toEqual(expect.arrayContaining(['text-[#c0392b]', 'bg-[#fdf1ef]', 'border-[#f0d5d0]']))
    rejected.unmount()

    // 超时：footer 换超时文案
    const expired = mountGf({ remainingSeconds: 0 })
    expect(expired.find('[data-testid="gf-deadline"]').text()).toBe('已超时，表单不可再提交')
    expect(expired.find('[data-testid="gf-submit"]').attributes('disabled')).toBeDefined()
    expired.unmount()
  })

  it('R6/R7：消息间距不再叠加（行无 mb-3.5、元信息行单值 mb-18px）；fab 回 bottom-3', async () => {
    const store = useAiStore()
    const first = buildMessage([{ kind: 'text', seg: 0, text: '甲', streaming: false }])
    first.id = 'a-1'
    const second = buildMessage([{ kind: 'text', seg: 0, text: '乙', streaming: false }])
    second.id = 'a-2'
    const wrapper = await mountPanel([first, second])

    // R6：消息行撤 mb-3.5，间距只由末行元信息行的 mb-[18px] 决定（v7 `.afoot` 单值 18px）
    const ops = wrapper.find('[data-testid="msg-ops-a-1"]').element as HTMLElement
    expect(ops.className).toContain('mb-[18px]')
    const row = ops.closest('.group') as HTMLElement
    expect(row).toBeTruthy()
    expect(row.className).not.toContain('mb-3.5')

    // R7：fab 定位回 bottom-3（12px）
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 1000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    wrap.scrollTop = 100
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    const fab = wrapper.find('[data-testid="back-to-bottom"]')
    expect(fab.exists()).toBe(true)
    expect(fab.classes()).toContain('bottom-3')
    expect(fab.classes()).not.toContain('bottom-[76px]')

    // R11：error 级通知走 v7 `.enotice` 红族；普通通知保持灰底虚线
    store.notice = '本轮出错（UPSTREAM_STREAM_ERROR）：Service is too busy'
    store.lastRoundOutcome = 'error'
    await wrapper.vm.$nextTick()
    const notice = wrapper.find('[data-testid="notice-bar"]')
    expect(notice.classes()).toEqual(expect.arrayContaining(['border-solid', 'border-[#f0d5d0]', 'bg-[#fdf1ef]', 'rounded-[9px]']))
    expect(notice.find('span').text()).toBe('!')
    store.lastRoundOutcome = 'ok'
    await wrapper.vm.$nextTick()
    expect(notice.classes()).toEqual(expect.arrayContaining(['border-dashed', 'border-[#e3e6ea]', 'bg-[#f6f7f9]', 'rounded-[10px]']))
    expect(notice.find('span').text()).toBe('—')
    wrapper.unmount()
  })
})

/**
 * R-165 S4（用户六项直裁的渲染层锁）：① 操作行常驻；② 用户长消息折叠/展开；③ md-body 末段外边距
 * 归零 + 用户侧字号真落地 + 气泡 `w-full` 按 role 分派；④ 间距按 role 分派（py-2 / mt）；⑤ 浮钮胶囊；
 * 附：CollapsibleText 行内 style 退役（任意值类口径）。阈值 160/32 由 R-161 组内两侧钉子覆盖。
 */
describe('R-165 S4：常驻操作行 / 用户长消息折叠 / 间距分派 / 浮钮胶囊', () => {
  /** 用户长消息夹具：mount 后**同步注高**（赶在 onMounted 的 queued measure 之前），再 nextTick 落定。 */
  async function mountLongUser(text: string, scrollHeight: number): Promise<{ wrapper: ReturnType<typeof mount>; body: HTMLElement }> {
    const msg = buildMessage([{ kind: 'text', seg: 0, text, streaming: false }])
    msg.id = 'u-long'
    msg.role = 'user'
    const store = useAiStore()
    store.messages = [msg]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    const body = wrapper.find('[data-testid="msg-bubble-u-long"] .md-body').element as HTMLElement
    Object.defineProperty(body, 'scrollHeight', { value: scrollHeight, configurable: true })
    await wrapper.vm.$nextTick()
    return { wrapper, body }
  }

  it('项1：助手流式期按钮组仍 visibility:hidden 占位（R-159 独立语义，非浮出机制，保留）', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '生成中', streaming: true }], null, true)
    msg.id = 'a-stream'
    const wrapper = await mountPanel([msg])
    const btnGroup = wrapper.find('[data-testid="msg-ops-a-stream"]').element.children[1] as HTMLElement
    // R-165 S5c：占位由行内 style 改 Tailwind `invisible` 工具类（visibility:hidden 等价，语义不变）
    expect(btnGroup.getAttribute('style')).toBe(null)
    expect(btnGroup.className).toContain('invisible')
    expect(btnGroup.className).not.toContain('opacity-0') // 常驻：不再有透明浮出
    expect(btnGroup.className).not.toContain('transition-opacity')
    wrapper.unmount()
  })

  it('项2：用户长消息折叠两态（>184px ⇒ 限高+裁切+渐隐+「展开」；展开解除；再点回折叠）', async () => {
    const { wrapper, body } = await mountLongUser('很长的用户提问'.repeat(60), 600)
    const more = wrapper.find('[data-testid="user-msg-more-u-long"]')
    expect(more.exists()).toBe(true)
    expect(more.text()).toBe('展开')
    expect(body.className).toContain('max-h-[184px]')
    expect(body.className).toContain('overflow-hidden')
    expect(body.className).toContain('[mask-image:linear-gradient(to_bottom,#000_calc(100%_-_90px),transparent)]')
    expect(body.getAttribute('style')).toBeNull() // 铁律：全 Tailwind，零行内 style
    await more.trigger('click')
    expect(wrapper.find('[data-testid="user-msg-more-u-long"]').text()).toBe('收起')
    expect(body.className).not.toContain('max-h-[184px]')
    expect(body.className).not.toContain('[mask-image:')
    // 再点回折叠：本地态翻转（该消息维度，不进 store）
    await wrapper.find('[data-testid="user-msg-more-u-long"]').trigger('click')
    expect(wrapper.find('[data-testid="user-msg-more-u-long"]').text()).toBe('展开')
    expect(body.className).toContain('max-h-[184px]')
    wrapper.unmount()
  })

  it('项2 负例：正文不溢出（≤184px 或 jsdom 零高）⇒ 无「展开」钮、无限高/渐隐类', async () => {
    const { wrapper, body } = await mountLongUser('短提问', 120)
    expect(wrapper.find('[data-testid="user-msg-more-u-long"]').exists()).toBe(false)
    expect(body.className).not.toContain('max-h-[184px]')
    expect(body.className).not.toContain('[mask-image:')
    wrapper.unmount()
    // jsdom 默认零高（不注高）：长文本也不出钮 —— 判据是读数，不是字数
    const user = buildMessage([{ kind: 'text', seg: 0, text: '长'.repeat(400), streaming: false }])
    user.id = 'u-zero'
    user.role = 'user'
    const w2 = await mountPanel([user])
    expect(w2.find('[data-testid="user-msg-more-u-zero"]').exists()).toBe(false)
    w2.unmount()
  })

  it('项3+项4：md-body 修正类 / 气泡 w-full 与 py-2 / 元信息行 mt 全按 role 分派', async () => {
    const user = buildMessage([{ kind: 'text', seg: 0, text: '用户提问', streaming: false }])
    user.id = 'u-s4'
    user.role = 'user'
    const ai = buildMessage([{ kind: 'text', seg: 0, text: '助手回答', streaming: false }])
    ai.id = 'a-s4'
    const wrapper = await mountPanel([user, ai])

    const userBubble = wrapper.find('[data-testid="msg-bubble-u-s4"]').element as HTMLElement
    const aiBubble = wrapper.find('[data-testid="msg-bubble-a-s4"]').element as HTMLElement
    // 项3②：w-full 按 role 分派 —— 用户侧去掉才按内容贴身收窄（max-w-[86%] 真生效），助手侧保留
    expect(userBubble.className).not.toContain('w-full')
    expect(userBubble.className).toContain('max-w-[86%]')
    expect(aiBubble.className).toContain('w-full')
    // 项4：py-2 按 role 分派 —— 助手侧去垂直内边距、保留 px-2.5（工具卡 mt-1.5 得贴正文下沿）
    expect(userBubble.className).toContain('py-2')
    expect(userBubble.className).toContain('px-2.5')
    expect(aiBubble.className).not.toContain('py-2')
    expect(aiBubble.className).toContain('px-2.5')

    // 项3①：末块下外边距归零（两 role 同口径；R-165 S5c 起声明方 = 渲染层注入类，`!` 保留为双保险）
    const userBody = wrapper.find('[data-testid="msg-bubble-u-s4"] .md-body').element as HTMLElement
    const aiBody = wrapper.find('[data-testid="msg-bubble-a-s4"] .md-body').element as HTMLElement
    expect(userBody.className).toContain('[&>*:last-child]:!mb-0')
    expect(aiBody.className).toContain('[&>*:last-child]:!mb-0')
    // 项3①user：13.5px 经内部选择器直命中 p/li（`.md-body` 自身 13.8px 压继承的 S2 遗留修复）
    expect(userBody.className).toContain('[&_p]:text-[13.5px]')
    expect(userBody.className).toContain('[&_li]:text-[13.5px]')
    expect(aiBody.className).not.toContain('[&_p]:text-[13.5px]') // 助手侧仍 13.8px（.md-body 自身声明）

    // 项4：元信息行 mt 按 role 分派（用户 6px 不变 / 助手 12px）
    const userRow = wrapper.find('[data-testid="msg-ops-u-s4"]').element as HTMLElement
    const aiRow = wrapper.find('[data-testid="msg-ops-a-s4"]').element as HTMLElement
    expect(userRow.className).toContain('mt-[6px]')
    expect(aiRow.className).toContain('mt-[12px]')
    expect(aiRow.className).not.toContain('mt-[6px]')
    wrapper.unmount()
  })

  it('项5：浮钮 = 居中常显文字胶囊（bottom-3 / left-1/2 / rounded-full / demo 色族 / 文案可见）', async () => {
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '甲', streaming: false }])])
    const wrap = wrapper.find('.overflow-y-auto').element as HTMLElement
    Object.defineProperty(wrap, 'scrollHeight', { value: 2000, configurable: true })
    Object.defineProperty(wrap, 'clientHeight', { value: 500, configurable: true })
    wrap.scrollTop = 1000 // 离底 500px > 160
    wrap.dispatchEvent(new Event('scroll'))
    await wrapper.vm.$nextTick()
    const fab = wrapper.find('[data-testid="back-to-bottom"]')
    expect(fab.exists()).toBe(true)
    expect(fab.element.tagName).toBe('BUTTON')
    expect(fab.text()).toBe('回到底部') // 可见文案即无障碍名（不再靠 sr-only）
    expect(fab.classes()).toEqual(expect.arrayContaining([
      'absolute', 'bottom-3', 'left-1/2', '-translate-x-1/2', 'rounded-full',
      'bg-white', 'border-solid', 'border-[#e3e6ea]', 'px-3', 'py-1.5', 'text-xs',
      'text-[#57606a]', 'shadow-[0_2px_8px_rgba(0,0,0,0.13)]'
    ]))
    expect(fab.classes()).not.toContain('bottom-[76px]')
    expect(fab.classes()).not.toContain('right-3') // 形态由「右下圆钮」改「居中胶囊」
    expect(fab.classes()).not.toContain('w-9') // 旧 36×36 圆钮尺寸类退役
    expect(fab.classes()).not.toContain('h-9')
    wrapper.unmount()
  })

  it('附：CollapsibleText 160 变体同走任意值类（行内 style 零残留，100px 口径见 #12）', async () => {
    const msg = buildMessage([{ kind: 'reasoning', seg: 0, text: '想很久'.repeat(20), streaming: false }])
    const wrapper = await mountWithOverflow([msg])
    const ct = wrapper.findAllComponents(CollapsibleText).find((c) => c.props('maxHeight') === 160)
    expect(ct).toBeTruthy()
    const el = ct!.find('.whitespace-pre-wrap').element as HTMLElement
    expect(el.className).toContain('max-h-[160px]')
    expect(el.className).toContain('[mask-image:linear-gradient(to_bottom,#000_55%,transparent)]')
    expect(el.getAttribute('style')).toBeNull()
    wrapper.unmount()
  })
})

/**
 * R-165 S5a（demo v7.7 终裁十项的渲染层补充锁）：复制成功反馈两态（代码块 / mermaid 源码 / 消息复制
 * 同口径）、滚动条细化类、focus-visible 聚焦环、长表格双区三区标记 —— 逐条钉在类/状态层。
 * （表格横滚联动的**几何**证据在实机取证里，jsdom 无布局不重复钉。）
 */
describe('R-165 S5a：复制两态反馈 / 滚动条 / 聚焦环 / 表格双区标记', () => {
  beforeEach(() => {
    // 剪贴板回退链在 jsdom 下没有 execCommand ⇒ 桩成成功，专测"反馈两态"本身上报
    document.execCommand = vi.fn(() => true)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn(() => Promise.resolve()) }, configurable: true })
  })

  it('项7：代码块复制图标钮 —— 点击后挂 is-copied（绿勾两态），2s 后复原', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountPanel([
        buildMessage([{ kind: 'text', seg: 0, text: '```js\nconst a = 1\n```\n', streaming: false }])
      ])
      const btn = wrapper.find('.md-body figure.code .cpbtn').element as HTMLElement
      expect(btn.className).toContain('[&_svg.i-check]:hidden') // 默认藏绿勾
      expect(btn.className).toContain('[&.is-copied_svg.i-copy]:hidden') // 成功态藏复制图标
      expect(btn.querySelectorAll('svg.i-copy, svg.i-check')).toHaveLength(2)
      expect(btn.querySelector('.tip')).toBeTruthy() // tip 气泡随钮携带
      expect(btn.classList.contains('is-copied')).toBe(false)
      await wrapper.find('.md-body figure.code .cpbtn').trigger('click')
      expect(document.execCommand).toHaveBeenCalledWith('copy')
      expect(btn.classList.contains('is-copied')).toBe(true)
      vi.advanceTimersByTime(2100)
      expect(btn.classList.contains('is-copied')).toBe(false) // 2s 复原
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('项7：消息复制钮同口径 —— 成功后挂 is-copied（含绿字类），失败不挂', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '复制我', streaming: false }])
    msg.id = 'a-copy-s5a'
    const wrapper = await mountPanel([msg])
    const btn = wrapper.find('[data-testid="copy-msg-a-copy-s5a"]').element as HTMLElement
    expect(btn.className).toContain('[&.is-copied]:text-[#1a7f45]')
    await wrapper.find('[data-testid="copy-msg-a-copy-s5a"]').trigger('click')
    await Promise.resolve()
    await wrapper.vm.$nextTick()
    expect(btn.classList.contains('is-copied')).toBe(true)
    wrapper.unmount()
  })

  it('项8/项9：滚动条 6px 类在消息容器、聚焦环落在根容器（一条覆盖面板内全部按钮）', async () => {
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: '正文', streaming: false }])])
    const wrap = wrapper.find('[role="log"]').element as HTMLElement
    expect(wrap.className).toContain('[&::-webkit-scrollbar]:w-[6px]')
    expect(wrap.className).toContain('[&::-webkit-scrollbar-thumb]:rounded-full')
    expect(wrap.className).toContain('[&::-webkit-scrollbar-thumb]:bg-[#1f2328]/30')
    const root = wrapper.find('.ai-chat').element as HTMLElement
    expect(root.className).toContain('[&_button:focus-visible]:outline-none')
    expect(root.className).toContain('[&_button:focus-visible]:ring-2')
    expect(root.className).toContain('[&_button:focus-visible]:ring-[#2563eb]/40')
    wrapper.unmount()
  })

  it('项1/项4：卡片堆叠下外边距体系 + 工具卡输出纯限高内滚（无展开钮）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 's1', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '结果', requiresAction: false },
        { kind: 'reasoning', seg: 1, text: '思考', streaming: false },
        { kind: 'tool', seg: 2, toolCallId: 's2', name: 'start_publish', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":9}', requiresAction: true }
      ])
    ])
    const toolCard = wrapper.find('[data-testid="tool-card-s1"]').element as HTMLElement
    expect(toolCard.className).toContain('mb-2')
    const gate = wrapper.find('[data-testid="hitl-gate"]').element as HTMLElement
    expect(gate.className).toContain('mb-2.5')
    const gateRows = wrapper.find('[data-testid="gate-rows"]').element as HTMLElement
    expect(gateRows.className).toContain('max-h-[160px]')
    expect(gateRows.className).toContain('overflow-y-auto')
    const reasonToggle = wrapper.find('[data-testid^="reasoning-toggle-"]')
    expect(reasonToggle.element.parentElement!.className).toContain('mb-3.5')
    // 思考体左竖线：单边宽度 + 显式 border-0 其余边（preflight 关闭下其余三边不吃 medium=3px）
    const reasonBody = wrapper.find('[data-testid^="reasoning-body-"]').element as HTMLElement
    expect(reasonBody.className).toContain('border-0')
    expect(reasonBody.className).toContain('border-l-2')
    wrapper.unmount()
  })
})

/**
 * R-165 S5b（demo v7.7 裁决区 12 + 07 双态重做）：mermaid 卡「卡头分段 tab 预览 | 源码」+
 * 卡头右端图标钮组 + 预览态 260px 内联缩放/平移 + 源码态 200px 内滚 + 全屏查看器
 * （scrim/白容器/6 钮工具条/Esc/点外关闭/下载 SVG）。
 * jsdom 只锁**结构与接线**（class 串、aria 属性、事件 → 状态迁移、DOM 落点）；
 * 窗口几何（clientHeight/scrollWidth、指针锚定像素、6px 滚动条占位）在实机取证脚本里量。
 */
const MMD_SVG = '<svg viewBox="0 0 980 240" role="img" aria-label="宽流程图"><rect x="8" y="14" width="140" height="34" fill="#fff"></rect><text x="78" y="36">开始</text></svg>'
const MMD_FENCE = '```mermaid\ngraph LR; A-->B;\n```\n'

describe('R-165 S5b：mermaid 卡双态（预览 | 源码）+ 全屏查看器', () => {
  beforeEach(() => {
    document.execCommand = vi.fn(() => true)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn(() => Promise.resolve()) }, configurable: true })
  })

  /** 挂载 + 等水合（`renderMermaidSvg` 桩值 → 卡替换源码块）。 */
  async function mountMermaid(svg: string | null): Promise<ReturnType<typeof mount>> {
    mmdRender.svg = svg
    const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: MMD_FENCE, streaming: false }])])
    await flushPromises()
    await flushPromises()
    await wrapper.vm.$nextTick()
    return wrapper
  }

  function cardOf(wrapper: ReturnType<typeof mount>): HTMLElement {
    return wrapper.find('.md-body .mmdc').element as HTMLElement
  }

  function fireWheel(el: HTMLElement, deltaY: number): void {
    el.dispatchEvent(new WheelEvent('wheel', { deltaY, clientX: 30, clientY: 20, bubbles: true }))
  }

  it('项1/项2：卡头 tab「预览 | 源码」+ 右端图标钮组；预览 260px 内联层 / 源码 200px 内滚；旧「查看源码」钮与旧 .mmd 类零残留', async () => {
    const wrapper = await mountMermaid(MMD_SVG)
    const card = cardOf(wrapper)
    expect(card.className).toContain('mmdc')
    expect(card.className).toContain('mb-3.5') // S5a 卡片节奏保留
    expect(card.className).toContain('rounded-[10px]')
    expect(card.className).toContain('border-solid')
    // 旧结构退场：`.mmd` 卡类 + 底部「查看源码」文字钮（R-165 S5c 拆除 ai-chat.css 后 `.mmd*` 零残留）
    expect(wrapper.find('.md-body .mmd').exists()).toBe(false)
    expect(wrapper.find('.mmd-toggle').exists()).toBe(false)
    expect(wrapper.find('.mmd-code').exists()).toBe(false)
    // 卡头分段 tab
    const tabs = card.querySelectorAll('.mmdc-tab')
    expect(Array.from(tabs).map((item) => item.textContent)).toEqual(['预览', '源码'])
    expect(tabs[0].getAttribute('role')).toBe('tab')
    expect(tabs[0].classList.contains('is-on')).toBe(true)
    expect(tabs[0].getAttribute('aria-selected')).toBe('true')
    expect(tabs[1].getAttribute('aria-selected')).toBe('false')
    expect(card.querySelector('.mmdc-tabs')?.getAttribute('role')).toBe('tablist')
    // 卡头右端图标钮组：缩放读数 + 复位 + 全屏（图标钮白名单形态 + aria-label + tip）
    expect(card.querySelector('.mmdc-ipct')?.textContent).toBe('100%')
    const reset = card.querySelector('.mmdc-ireset') as HTMLElement
    const full = card.querySelector('.mmdc-full') as HTMLElement
    expect(reset.getAttribute('aria-label')).toBe('复位到 100%')
    expect(full.getAttribute('aria-label')).toBe('全屏查看')
    for (const btn of [reset, full]) {
      expect(btn.className).toContain('group/tip')
      expect(btn.className).toContain('w-[26px]')
      expect(btn.querySelectorAll('svg')).toHaveLength(1)
      expect(btn.querySelector('.tip')?.textContent).toContain(btn.getAttribute('aria-label') ?? '')
    }
    // 预览态：260px 限高 + 内联横滚保留 + 两层 transform（scale / translate，无 overflow 平移）
    const preview = card.querySelector('.mmdc-pv') as HTMLElement
    expect(preview.className).toContain('max-h-[260px]')
    expect(preview.className).toContain('overflow-auto')
    expect(preview.className).toContain('cursor-grab')
    expect(preview.getAttribute('style')).toBeNull() // 零行内 style（变量由 JS 写给子层）
    const pan = preview.querySelector('.mmdc-pan') as HTMLElement
    const stage = preview.querySelector('.mmdc-stage') as HTMLElement
    expect(pan.className).toContain('[transform:translateX(var(--px,0px))_translateY(var(--py,0px))]')
    expect(stage.className).toContain('[transform:scale(var(--z,1))]')
    expect(stage.className).toContain('origin-top-left')
    // SVG 归一：mermaid 自带 width/style 被剥、按 viewBox 宽高比定宽（980/240 ≥2 ⇒ w-[980px]）
    const inlineSvg = stage.querySelector('svg') as SVGElement
    expect(inlineSvg.getAttribute('class')).toContain('w-[980px]')
    expect(inlineSvg.getAttribute('class')).toContain('max-w-none')
    // 缩放/平移变量各写在自身层：stage 拿 --z、pan 拿 --px/--py（卡容器本体零行内 style）
    expect(stage.style.getPropertyValue('--z')).toBe('1')
    expect(pan.style.getPropertyValue('--px')).toBe('0px')
    expect(pan.style.getPropertyValue('--py')).toBe('0px')
    // 源码态：初始 hidden + mono 盒 200px 内滚 + 复制源码图标钮
    const source = card.querySelector('.mmdc-pane[data-pane="source"]') as HTMLElement
    expect(source.className).toContain('hidden')
    const box = card.querySelector('.mmdc-src') as HTMLElement
    expect(box.className).toContain('max-h-[200px]')
    expect(box.className).toContain('whitespace-pre')
    expect(box.textContent).toContain('graph LR')
    const copy = card.querySelector('.mmdc-copy') as HTMLElement
    expect(copy.className).toContain('cpbtn')
    expect(copy.className).toContain('[&.is-copied]:text-[#1a7f45]') // S5a 统一绿勾机制
    expect(copy.querySelectorAll('svg')).toHaveLength(2)
    expect(copy.querySelector('.tip')?.textContent).toContain('复制源码')
    wrapper.unmount()
  })

  it('项2：tab 切换两态互斥 + aria-selected 同步；切回目标态恢复该态自己的滚动（各自记住）', async () => {
    const wrapper = await mountMermaid(MMD_SVG)
    const card = cardOf(wrapper)
    const preview = card.querySelector('.mmdc-pane[data-pane="preview"]') as HTMLElement
    const source = card.querySelector('.mmdc-pane[data-pane="source"]') as HTMLElement
    const previewTab = card.querySelector('.mmdc-tab[data-tab="preview"]') as HTMLElement
    const sourceTab = card.querySelector('.mmdc-tab[data-tab="source"]') as HTMLElement
    // jsdom 无布局：用取值器桩把「预览态自己记住的滚动位置」做成可观测量
    let restored = -1
    Object.defineProperty(preview, 'scrollTop', {
      get: () => 42,
      set: (value: number) => {
        restored = value
      },
      configurable: true
    })
    preview.dispatchEvent(new Event('scroll')) // 实时记账
    sourceTab.click()
    expect(source.className).not.toContain('hidden')
    expect(preview.className).toContain('hidden')
    expect(sourceTab.classList.contains('is-on')).toBe(true)
    expect(sourceTab.getAttribute('aria-selected')).toBe('true')
    expect(previewTab.classList.contains('is-on')).toBe(false)
    expect(previewTab.getAttribute('aria-selected')).toBe('false')
    previewTab.click()
    expect(preview.className).not.toContain('hidden')
    expect(source.className).toContain('hidden')
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    expect(restored).toBe(42) // 切回预览态恢复预览态自己的 scrollTop（非复位成 0）
    wrapper.unmount()
  })

  it('项1：源码态「复制源码」图标钮接通 —— 绿勾 is-copied + 2s 复原（S5a 统一机制）', async () => {
    const wrapper = await mountMermaid(MMD_SVG)
    const copy = cardOf(wrapper).querySelector('.mmdc-copy') as HTMLElement
    vi.useFakeTimers()
    try {
      copy.click()
      expect(document.execCommand).toHaveBeenCalledWith('copy')
      expect(copy.classList.contains('is-copied')).toBe(true)
      vi.advanceTimersByTime(2100)
      expect(copy.classList.contains('is-copied')).toBe(false)
    } finally {
      vi.useRealTimers()
    }
    wrapper.unmount()
  })

  it('项2：预览态滚轮缩放（指针锚定 0.5×–4×）+ 复位钮归零', async () => {
    const wrapper = await mountMermaid(MMD_SVG)
    const card = cardOf(wrapper)
    const box = card.querySelector('.mmdc-pv') as HTMLElement
    const readout = (): string | null => (card.querySelector('.mmdc-ipct') as HTMLElement).textContent
    fireWheel(box, -400) // 1.0015^400 ≈ 1.821
    expect(readout()).toBe('182%')
    ;(card.querySelector('.mmdc-ireset') as HTMLElement).click()
    expect(readout()).toBe('100%') // 复位 = 缩放 1× + 位移归零
    const stage = card.querySelector('.mmdc-stage') as HTMLElement
    expect(stage.style.getPropertyValue('--z')).toBe('1')
    fireWheel(box, -4000)
    fireWheel(box, -4000)
    expect(readout()).toBe('400%') // 上界钳制
    fireWheel(box, 4000)
    expect(readout()).toBe('50%') // 下界钳制
    wrapper.unmount()
  })

  it('项3：全屏查看器 —— scrim + 白容器 + role=dialog/aria-modal + 工具条 6 图标钮（aria-label+tip）+ 缩放/复位 + select-text', async () => {
    const wrapper = await mountMermaid(MMD_SVG)
    const card = cardOf(wrapper)
    expect(wrapper.find('[data-testid="mmd-viewer"]').exists()).toBe(false)
    ;(card.querySelector('.mmdc-full') as HTMLElement).click()
    await flushPromises()
    const viewer = wrapper.find('[data-testid="mmd-viewer"]')
    expect(viewer.exists()).toBe(true)
    const root = viewer.element as HTMLElement
    expect(root.getAttribute('role')).toBe('dialog')
    expect(root.getAttribute('aria-modal')).toBe('true')
    expect(root.getAttribute('aria-label')).toBe('mermaid 全屏查看器')
    expect(root.className).toContain('fixed')
    expect(root.className).toContain('bg-black/50') // 深色 scrim
    expect(document.body.classList.contains('overflow-hidden')).toBe(true) // 打开期锁背景滚动
    const panel = root.firstElementChild as HTMLElement
    expect(panel.className).toContain('rounded-[10px]')
    expect(panel.className).toContain('border-solid')
    expect(panel.className).toContain('border-[#e3e6ea]')
    expect(panel.className).toContain('bg-white')
    expect(wrapper.find('[data-testid="mmd-viewer-title"]').text()).toContain('流程图')
    // 工具条：−/+/百分比/复位/复制源码/下载 SVG/关闭（全带 aria-label + tip 气泡）
    const buttons: Array<[string, string]> = [
      ['mmd-zoom-out', '缩小'],
      ['mmd-zoom-in', '放大'],
      ['mmd-reset', '复位到 100%'],
      ['mmd-copy', '复制源码'],
      ['mmd-download', '下载 SVG'],
      ['mmd-close', '关闭全屏']
    ]
    for (const [id, label] of buttons) {
      const btn = wrapper.find(`[data-testid="${id}"]`)
      expect(btn.exists(), id).toBe(true)
      expect(btn.element.getAttribute('aria-label'), id).toBe(label)
      expect(btn.find('.tip').exists(), id).toBe(true)
      expect(btn.element.className).toContain('group/tip')
    }
    expect(wrapper.find('[data-testid="mmd-copy"]').element.className).toContain('[&.is-copied_svg.i-check]:block')
    // 缩放：+/− 各一档 1.25×，复位回 100%；transform 变量写在 scale/translate 两层上
    const zoomLayer = wrapper.find('[data-testid="mmd-viewer-zoom"]').element as HTMLElement
    const panLayer = wrapper.find('[data-testid="mmd-viewer-pan"]').element as HTMLElement
    expect(zoomLayer.className).toContain('[transform:scale(var(--z,1))]')
    expect(zoomLayer.className).toContain('select-text') // SVG 容器 select-text（图中文字可框选）
    expect(panLayer.className).toContain('[transform:translateX(var(--px,0px))_translateY(var(--py,0px))]')
    expect(zoomLayer.style.getPropertyValue('--z')).toBe('1')
    expect(wrapper.find('[data-testid="mmd-zoom-pct"]').text()).toBe('100%')
    await wrapper.find('[data-testid="mmd-zoom-in"]').trigger('click')
    expect(wrapper.find('[data-testid="mmd-zoom-pct"]').text()).toBe('125%')
    expect(zoomLayer.style.getPropertyValue('--z')).toBe('1.25')
    await wrapper.find('[data-testid="mmd-zoom-out"]').trigger('click')
    expect(wrapper.find('[data-testid="mmd-zoom-pct"]').text()).toBe('100%')
    await wrapper.find('[data-testid="mmd-zoom-in"]').trigger('click')
    await wrapper.find('[data-testid="mmd-reset"]').trigger('click')
    expect(wrapper.find('[data-testid="mmd-zoom-pct"]').text()).toBe('100%')
    // 查看器内 SVG：宽图定宽 + select-text（v-html 落点）
    const svg = wrapper.find('[data-testid="mmd-viewer-zoom"] svg')
    expect(svg.exists()).toBe(true)
    expect(svg.element.getAttribute('class')).toContain('w-[980px]')
    expect(svg.element.getAttribute('class')).toContain('select-text')
    // 查看器「复制源码」：同一绿勾机制（响应式 is-copied）
    await wrapper.find('[data-testid="mmd-copy"]').trigger('click')
    expect(document.execCommand).toHaveBeenCalledWith('copy')
    expect(wrapper.find('[data-testid="mmd-copy"]').element.className).toContain('is-copied')
    // 关闭钮
    await wrapper.find('[data-testid="mmd-close"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="mmd-viewer"]').exists()).toBe(false)
    expect(document.body.classList.contains('overflow-hidden')).toBe(false)
    wrapper.unmount()
  })

  it('项3：Esc 与点 scrim 两条关闭路径（互不依赖关闭钮）', async () => {
    const wrapper = await mountMermaid(MMD_SVG)
    const full = cardOf(wrapper).querySelector('.mmdc-full') as HTMLElement
    full.click()
    await flushPromises()
    expect(wrapper.find('[data-testid="mmd-viewer"]').exists()).toBe(true)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('[data-testid="mmd-viewer"]').exists()).toBe(false)
    // 重开 → 点 scrim（viewer 自身，非面板内）
    full.click()
    await flushPromises()
    expect(wrapper.find('[data-testid="mmd-viewer"]').exists()).toBe(true)
    await wrapper.find('[data-testid="mmd-viewer"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="mmd-viewer"]').exists()).toBe(false)
    // 卸载即摘监听：Esc 不再报错（监听已解绑）
    wrapper.unmount()
  })

  it('项3：下载 SVG 落盘 —— Blob(image/svg+xml) + <a download="mermaid-flow.svg">（内联计算样式 + 显式 xmlns/尺寸）', async () => {
    const blobs: Blob[] = []
    const anchors: HTMLAnchorElement[] = []
    const createObjectURL = vi.fn((blob: Blob) => {
      blobs.push(blob)
      return 'blob:mermaid-flow-test'
    })
    const revokeObjectURL = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, configurable: true })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function mockAnchorClick(this: HTMLAnchorElement) {
      anchors.push(this)
    })
    try {
      const wrapper = await mountMermaid(MMD_SVG)
      ;(cardOf(wrapper).querySelector('.mmdc-full') as HTMLElement).click()
      await flushPromises()
      await wrapper.find('[data-testid="mmd-download"]').trigger('click')
      expect(createObjectURL).toHaveBeenCalledTimes(1)
      expect(clickSpy).toHaveBeenCalledTimes(1) // 真触发 `<a download>` 下载
      expect(anchors[0].download).toBe('mermaid-flow.svg')
      const serialized = await blobs[0].text()
      expect(blobs[0].type).toBe('image/svg+xml;charset=utf-8')
      expect(serialized).toContain('xmlns="http://www.w3.org/2000/svg"')
      expect(serialized).toContain('width="980"') // viewBox 宽高下放为定尺寸（脱离页面仍可渲染）
      expect(serialized).toContain('height="240"')
      expect(serialized).toContain('<text')
      expect(serialized).not.toContain('class="block') // 视图类剥掉，只留内联计算样式
      wrapper.unmount()
    } finally {
      clickSpy.mockRestore()
      vi.restoreAllMocks()
      Reflect.deleteProperty(URL, 'createObjectURL')
      Reflect.deleteProperty(URL, 'revokeObjectURL')
    }
  })

  it('降级：render 失败（null）⇒ 源码态卡兜底（无 tab / 无缩放钮），源码直显且复制钮可用', async () => {
    const wrapper = await mountMermaid(null)
    const card = cardOf(wrapper)
    expect(card.querySelectorAll('.mmdc-tab')).toHaveLength(0)
    expect(card.querySelector('.mmdc-full')).toBeNull()
    expect(card.querySelector('.mmdc-ireset')).toBeNull()
    expect(card.querySelector('.mmdc-ipct')).toBeNull()
    const source = card.querySelector('.mmdc-pane[data-pane="source"]') as HTMLElement
    expect(source.className).not.toContain('hidden')
    expect((card.querySelector('.mmdc-src') as HTMLElement).textContent).toContain('graph LR')
    const copy = card.querySelector('.mmdc-copy') as HTMLElement
    copy.click()
    expect(copy.classList.contains('is-copied')).toBe(true)
    wrapper.unmount()
  })
})

/**
 * R-165 S5c：错误卡族（demo v7.7 裁决区 08）—— 判据 = 按错误码分派
 * （429 → 红族 / content_filter → 灰族 / context_length_exceeded → 蓝族 / 其余沿用红卡），
 * 铁律 = **内联于对话流按发生位置插入，绝不做顶部固定**。
 *
 * 覆盖口径：三变体在本仓**没有真实触发路径**（后端无 429 / content_filter / context_length_exceeded
 * 的产出点，见施工报告 §错误卡映射表）⇒ 以「纯函数单测 + store 注入帧 + 挂载渲染」三层钉死；
 * 有真实路径的两条（SSE error 帧 / 开流前 ApiError）走 store 实调。
 */
describe('R-165 S5c：错误卡族（三变体分派 + 内联位置 + 动作钮）', () => {
  it('分派纯函数：码优先、文案兜底（429/配额 / content_filter / context_length / 未分类）', async () => {
    const { classifyErrorVariant, buildErrorCard } = await import('@/utils/error-card')
    expect(classifyErrorVariant('429', 'Too Many Requests')).toBe('quota')
    expect(classifyErrorVariant('RATE_LIMIT_EXCEEDED', '配额已用尽')).toBe('quota')
    expect(classifyErrorVariant('X', '本小时额度已用尽（429）')).toBe('quota')
    expect(classifyErrorVariant('content_filter', '内容被拦截')).toBe('filter')
    expect(classifyErrorVariant('X', '本次回复被安全策略拦截')).toBe('filter')
    expect(classifyErrorVariant('context_length_exceeded', 'too long')).toBe('context')
    expect(classifyErrorVariant('X', '上下文长度超限，最早的 4 轮已无法参与推理')).toBe('context')
    expect(classifyErrorVariant('UPSTREAM_STREAM_ERROR', 'Service is too busy')).toBe('generic')
    expect(classifyErrorVariant('', '')).toBe('generic')
    // 专属 Alert 承接的码不出卡（避免与顶部 Alert 重复报同一件事）
    expect(buildErrorCard('SESSION_BUSY', '该会话已有一轮进行中')).toBeNull()
    expect(buildErrorCard('SUSPEND_POOL_SATURATED', '挂起池已饱和')).toBeNull()
    expect(buildErrorCard('AI_UNAVAILABLE', '无 key')).toBeNull()
    expect(buildErrorCard('AI_REDIS_UNAVAILABLE', 'redis down')).toBeNull()
    // 三变体文案（demo 逐字）+ 通用红卡无动作钮
    expect(buildErrorCard('429', '配额用尽')).toMatchObject({
      variant: 'quota', title: '请求超出配额', guide: '引导：稍后重试；若在批量任务里，先降低并发再重发。', action: '稍后重试', detail: '配额用尽'
    })
    expect(buildErrorCard('content_filter', '被拦截')).toMatchObject({
      variant: 'filter', title: '内容被安全策略拦截', action: '改写后重发', detail: '被拦截'
    })
    expect(buildErrorCard('context_length_exceeded', '超限')).toMatchObject({
      variant: 'context', title: '上下文长度超限', action: '开新会话', detail: '超限'
    })
    expect(buildErrorCard('AI_RUN_FAILED', 'boom')).toMatchObject({ variant: 'generic', title: '本轮出错', action: '', guide: '' })
  })

  it('store：SSE error 帧按 code 分派并把卡挂到**本轮这条消息**上（lastRoundOutcome 与 notice 口径不变）', async () => {
    const { useAiStore: useStore } = await import('@/stores/ai')
    const store = useStore()
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '半截正文', streaming: true }], null, true)
    msg.id = 'a-err'
    store.messages = [msg]
    // 不传 assistantId ⇒ 帧归属「当前轮」（= 最后一条助手消息），走轮次内完整分支
    store.handleFrame({ type: 'error', runId: 'run-1', code: 'context_length_exceeded', message: '本轮 132k / 128k token' })
    expect(msg.errorCard?.variant).toBe('context')
    expect(msg.errorCard?.detail).toBe('本轮 132k / 128k token')
    expect(msg.streaming).toBe(false)
    expect(store.lastRoundOutcome).toBe('error')
    expect(store.notice).toBe('本轮出错（context_length_exceeded）：本轮 132k / 128k token') // 既有通知槽口径不回归

    const msg2 = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)
    msg2.id = 'a-err2'
    store.messages = [msg2]
    store.handleFrame({ type: 'error', runId: 'run-2', code: 'UPSTREAM_STREAM_ERROR', message: 'Service is too busy' })
    expect(msg2.errorCard?.variant).toBe('generic') // 未分类 ⇒ 通用红卡（demo「其余沿用红卡」）
  })

  it('渲染：卡在**该轮消息行内**（对话流里、static 定位，非顶部固定）+ 三变体色族/图标/引导行', async () => {
    const { buildErrorCard } = await import('@/utils/error-card')
    const mk = (id: string, code: string, detail: string) => {
      const m = buildMessage([{ kind: 'text', seg: 0, text: '正文', streaming: false }])
      m.id = id
      m.errorCard = buildErrorCard(code, detail)
      return m
    }
    const wrapper = await mountPanel([
      mk('q', '429', '上游 429'),
      mk('f', 'content_filter', '被安全策略拦截'),
      mk('c', 'context_length_exceeded', '上下文超限'),
      mk('g', 'UPSTREAM_STREAM_ERROR', 'Service is too busy')
    ])
    const cardOf = (id: string) => wrapper.find(`[data-testid="error-card-${id}"]`)
    expect(cardOf('q').exists()).toBe(true)
    // 位置纪律：卡在消息滚动容器（对话流）内、与同轮气泡同属一行，且自身无 fixed/sticky 定位类
    const qCard = cardOf('q').element as HTMLElement
    expect(qCard.closest('[role="log"]')).not.toBeNull()
    const row = qCard.closest('.group') as HTMLElement
    expect(row.querySelector('[data-testid="msg-bubble-q"]')).not.toBeNull()
    expect(qCard.className).not.toMatch(/(^|\s)(fixed|sticky)(\s|$)/)
    // 三变体色族（红 / 灰 / 蓝）+ 通用红卡
    expect(cardOf('q').classes()).toEqual(expect.arrayContaining(['border-[#f0d5d0]', 'bg-[#fdf1ef]']))
    expect(cardOf('f').classes()).toEqual(expect.arrayContaining(['border-[#e3e6ea]', 'bg-[#f6f7f9]']))
    expect(cardOf('c').classes()).toEqual(expect.arrayContaining(['border-[#cddcff]', 'bg-[#eff4ff]']))
    expect(cardOf('g').classes()).toEqual(expect.arrayContaining(['border-[#f0d5d0]', 'bg-[#fdf1ef]']))
    expect(cardOf('q').attributes('data-variant')).toBe('quota')
    expect(cardOf('f').attributes('data-variant')).toBe('filter')
    expect(cardOf('c').attributes('data-variant')).toBe('context')
    expect(cardOf('g').attributes('data-variant')).toBe('generic')
    // 图标字 + 标题 + 依据行 + 引导行
    expect(cardOf('q').text()).toContain('!')
    expect(cardOf('q').text()).toContain('请求超出配额')
    expect(cardOf('q').text()).toContain('上游 429')
    expect(cardOf('q').text()).toContain('引导：稍后重试')
    expect(cardOf('f').text()).toContain('⊘')
    expect(cardOf('f').text()).toContain('改写后重发')
    expect(cardOf('c').text()).toContain('i')
    expect(cardOf('c').text()).toContain('开新会话')
    // 通用红卡无动作钮
    expect(wrapper.find('[data-testid="error-card-action-g"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('动作钮：稍后重试⇒重发本轮；改写后重发⇒焦点回输入框；开新会话⇒既有 newChat 出口', async () => {
    const { buildErrorCard } = await import('@/utils/error-card')
    const store = useAiStore()
    const mk = (id: string, code: string, detail: string) => {
      const m = buildMessage([{ kind: 'text', seg: 0, text: '正文', streaming: false }])
      m.id = id
      m.errorCard = buildErrorCard(code, detail)
      return m
    }
    const sendSpy = vi.spyOn(store, 'send').mockResolvedValue(undefined)
    const resetSpy = vi.spyOn(store, 'resetSession').mockResolvedValue(undefined)
    const wrapper = await mountPanel([mk('q2', '429', '配额用尽'), mk('f2', 'content_filter', '被拦截'), mk('c2', 'context_length_exceeded', '超限')])
    const composer = wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement
    const focusSpy = vi.spyOn(composer, 'focus')
    await wrapper.find('[data-testid="error-card-action-q2"]').trigger('click')
    expect(sendSpy).toHaveBeenCalledWith('继续')
    await wrapper.find('[data-testid="error-card-action-f2"]').trigger('click')
    expect(focusSpy).toHaveBeenCalled()
    await wrapper.find('[data-testid="error-card-action-c2"]').trigger('click')
    expect(resetSpy).toHaveBeenCalled()
    wrapper.unmount()
    sendSpy.mockRestore()
    resetSpy.mockRestore()
    focusSpy.mockRestore()
  })

  it('开流前失败（handleChatError）：ApiError 有码走分派、无对应码落通用红卡、专属码不出卡', async () => {
    const { buildErrorCard } = await import('@/utils/error-card')
    expect(buildErrorCard('429', 'Too Many Requests')).toMatchObject({ variant: 'quota' })
    expect(buildErrorCard('', '对话请求失败')).toMatchObject({ variant: 'generic' })
    expect(buildErrorCard('SESSION_BUSY', '进行中')).toBeNull()
  })
})
