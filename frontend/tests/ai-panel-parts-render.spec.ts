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
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ActiveGenerativeForm, ChatMessage, MessagePart, PendingToolCall, ToolPart } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'
import { useAiStore } from '@/stores/ai'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'

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

/** R-165 S5b：mermaid 渲染出口桩 —— 真 mermaid 不在 jsdom 跑（几何/真渲染在实机取证）；
 *  桩值可逐用例改写（`null` = render 失败路径）。模块其余导出走 importOriginal 原样透出。 */
const mmdRender = vi.hoisted(() => ({ svg: '' as string | null }))
/** 问题1 偏差修正批 aj14d ②：流式防抖 flush 回调的登记记账（面板挂载即注册 ⇒ 装配可断言）。 */
const streamFlush = vi.hoisted(() => ({ handlers: [] as Array<() => void> }))
vi.mock('@/utils/markdown', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/markdown')>()
  return {
    ...actual,
    renderMermaidSvg: () => Promise.resolve(mmdRender.svg),
    setStreamFlushHandler: (handler: (() => void) | null) => {
      if (handler !== null) {
        streamFlush.handlers.push(handler)
      }
      actual.setStreamFlushHandler(handler)
    }
  }
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
  api.confirm.mockReset()
  api.confirm.mockResolvedValue(undefined)
  api.feedbackSubmit.mockResolvedValue({ saved: true, cancelled: false })
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h', frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0, sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z' })
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
  return { toolCallId, runId: 'run-1', form: {} as never, status, error: null, values: {}, fieldErrors: [] }
}

async function mountPanel(
  messages: ChatMessage[],
  options: { realForm?: boolean } = {}
): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = messages
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const global = options.realForm
    ? { stubs: { ...stubs, FormRenderer: false } }
    : { stubs }
  const wrapper = mount(AiPanel, { global })
  await wrapper.vm.$nextTick()
  return wrapper
}

/**
 * S6 批1：`CollapsibleText` 组件已退役（思考过程 / 工具输出 / 参数 / 回执详情全部改「纯限高内滚，
 * 无展开钮」）—— 原「注高法」helper（给全部 CollapsibleText 的正文注 scrollHeight）随之删除：
 * 限高内滚由类声明，不依赖任何运行期测量。
 */

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
    // H1 口径（2026-10-17 裁决 + demo 定稿）：按钮文案 = 「批准」，非「确认执行」
    expect(cards[0]!.html()).toContain('批准')

    // 2026-10-20 卡面一致性裁决（demo chat-ux/card-consistency-fix.html 场景一 1:1）：
    // 确认卡**单层** —— 内层蓝框与内层卡头（「需要你确认后才能继续」+ 内层 chip）整段退役，
    // 卡体直接 = 字段表 + 按钮行；倒计时环 / 读数一并退役。
    const gate = cards[0]!.find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)
    // 单层结构锁：门卡 = 卡体内的一层（pb-[11px]），旧内层蓝框配方全数不出现
    expect(gate.classes()).toEqual(expect.arrayContaining(['pb-[11px]']))
    for (const gone of ['border-[#cddcff]', 'bg-[#eff4ff]', 'rounded-[10px]', 'p-[12px]', 'border-solid', 'mb-2.5']) {
      expect(gate.classes()).not.toContain(gone)
    }
    expect(gate.find('.gate').exists()).toBe(false) // 手写 CSS 类退场
    // 内层卡头退役（不再出现同名解释文案 / 内层 chip）
    expect(gate.text()).not.toContain('需要你确认后才能继续')
    // 字形面零 emoji（emoji 呈现区 + dingbat 区）—— 移植笔误锁
    expect(gate.text()).not.toMatch(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]/u)
    // 无倒计时锁：环 / 读数 / 静态兜底文案全无
    expect(gate.find('svg').exists()).toBe(false)
    expect(gate.text()).not.toContain('剩余确认时间')
    expect(gate.text()).not.toContain('等待人工确认')
    expect(gate.text()).not.toContain('确认已超时')
    // chip 文案锁：卡头右标签 = 琥珀「待确认」（demo 定稿 #b45309 / #fffbeb）
    const chip = cards[0]!.find('[data-testid="card-status-c1"]')
    expect(chip.text()).toBe('待确认')
    expect(chip.classes()).toEqual(expect.arrayContaining(['text-[#b45309]', 'bg-[#fffbeb]', 'border-[#fde68a]']))
    const rows = gate.find('[data-testid="gate-rows"]').findAll(':scope > div')
    expect(rows[0]!.text()).toContain('操作')
    expect(rows[0]!.text()).toContain('启动导出作业')
    expect(rows[1]!.text()).toContain('taskId')
    expect(rows[1]!.text()).toContain('1')
    // el-input/el-button 退场：双按钮 = 原生 button
    // （R-165 S3a 续：输入区 composer 亦已退 EP，断言改挂 [data-testid="composer-input"] 原生 textarea）
    // 2A 批（裁决 §3 缺陷④ / §9 K1）：H1 无理由框 —— 拒因输入框零残留
    expect(gate.find('textarea').exists()).toBe(false)
    expect(gate.find('[data-testid="reject-reason"]').exists()).toBe(false)
    const gateBtns = gate.findAll('button')
    expect(gateBtns.map((b) => b.text())).toEqual(['拒绝', '批准'])
    // 2A 批（裁决 §3 缺陷③）：按钮防折行 —— whitespace-nowrap + flex-none + min-w
    for (const btn of gateBtns) {
      expect(btn.classes()).toEqual(expect.arrayContaining(['whitespace-nowrap', 'flex-none']))
    }
    expect(gateBtns[1]!.classes()).toEqual(expect.arrayContaining(['bg-[#2563eb]', 'text-white', 'font-semibold', 'rounded-[8px]', 'px-[13px]', 'py-[5px]', 'text-[12.6px]', 'disabled:bg-[#8b949e]', 'min-w-[72px]']))
    expect(gateBtns[0]!.classes()).toEqual(expect.arrayContaining(['bg-white', 'text-[#57606a]', 'border-[#e3e6ea]', 'min-w-[56px]']))
    // 按钮组整行可下移（行 flex-wrap），组内不换行
    const actRow = gateBtns[0]!.element.parentElement as HTMLElement
    expect(actRow.className).toContain('whitespace-nowrap')
    expect(actRow.parentElement?.className).toContain('flex-wrap')

    // D-C10（原 :106-115 位置断言改写）：块分派下挂起卡（含确认子块）仍在 c2 工具卡之前
    const pendingCard = html.indexOf('pending-card-c1')
    const otherTool = html.indexOf('列出任务')
    expect(pendingCard).toBeGreaterThanOrEqual(0)
    expect(otherTool).toBeGreaterThan(pendingCard)
    wrapper.unmount()
  })

  // 2026-10-20「卡面一致性」三项显式锁（demo `docs/demo/chat-ux/card-consistency-fix.html`）：
  // 单层结构 / 无倒计时 / chip 文案。与 A1' 的结构细节断言互补 —— 本用例只锁三条不可回退的结论。
  it('卡面一致性三锁：单层确认卡 · 无倒计时 · chip「待确认」（琥珀）', async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'lock1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":7,"scope":"DOC_TYPE","format":"xlsx"}', timeoutSeconds: 120, requiresAction: true }
        ],
        { toolCallId: 'lock1', name: 'start_export', kind: 'CONFIRM', args: { taskId: 7 }, rawArgs: '{"taskId":7}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const card = wrapper.find('[data-testid="pending-card-lock1"]')
    const gate = card.find('[data-testid="hitl-gate"]')

    // ① 单层：卡容器一层描边，门卡本身不再有描边/底色/圆角/内边距（内层蓝框退役）
    expect(card.classes()).toEqual(expect.arrayContaining(['border-[#e3e6ea]', 'bg-white', 'rounded-[10px]']))
    expect(gate.classes()).toEqual(['pb-[11px]'])

    // ② 无倒计时：环 / 读数 / 兜底文案三缺一不可出现
    expect(gate.find('svg').exists()).toBe(false)
    expect(gate.text()).not.toMatch(/剩余确认时间|等待人工确认|确认已超时/)

    // ③ chip：文案「待确认」+ 琥珀族（#b45309 / #fffbeb / #fde68a）
    const chip = card.find('[data-testid="card-status-lock1"]')
    expect(chip.text()).toBe('待确认')
    expect(chip.classes()).toEqual(expect.arrayContaining(['text-[#b45309]', 'bg-[#fffbeb]', 'border-[#fde68a]']))

    // 操作/参数行一个不许少（demo 场景一）
    const rows = gate.find('[data-testid="gate-rows"]').findAll(':scope > div')
    expect(rows.map((row) => row.text())).toEqual([
      '操作启动导出作业',
      'taskId7',
      'scopeDOC_TYPE',
      'formatxlsx'
    ])
    wrapper.unmount()
  })

  // H1 预检摘要（demo `docs/demo/chat-ux/hitl-confirm-preflight.html` 场景一，用户 2026-10-20 批准）：
  // 位置 = 门卡「参数行」与「按钮行」之间；逐项对 demo 文案/结构；缺省 = 无该区（旧帧回归锁）。
  const PREFLIGHT = {
    // 后端实发形状（ToolPreflightService#asMap + inspect 的检查顺序）：
    // target.type/status 是 Task 枚举名；checks 恒 4 项、顺序为「任务存在 → 类型匹配 →
    // 无在途作业 → 预检指纹新鲜」，每项都带 detail（通过时可为空串；指纹依据非空）。
    target: { taskId: 5, title: '订单配置导入', type: 'IMPORT', status: 'ACTIVE' },
    checks: [
      { name: '任务存在', pass: true, detail: '' },
      { name: '类型匹配', pass: true, detail: '' },
      { name: '无在途作业', pass: true, detail: '' },
      { name: '预检指纹新鲜', pass: true, detail: '预检作业 #12 · 3a9f…' }
    ],
    impact: '将发布 3 个配置定义 · 影响 12,480 行数据',
    reportHash: '3a9f'
  }

  it('卡面一致性：门卡带 preflight → 预检摘要区**不渲染**（payload 留段不展示）+ 标题业务化', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'text', seg: 0, text: '', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'pf1', name: 'start_publish', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":5}', timeoutSeconds: 120, requiresAction: true, preflight: PREFLIGHT }
      ])
    ])
    const gate = wrapper.find('[data-testid="pending-card-pf1"]').find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)

    // 预检摘要区**整块撤除**：payload 保留在段上，卡面不渲染（不再有区名 / 徽标 / 四行）
    expect(gate.find('[data-testid="preflight-summary"]').exists()).toBe(false)
    expect(gate.text()).not.toContain('预检摘要')
    expect(gate.text()).not.toContain('预检通过')
    expect(gate.text()).not.toContain('批准绑定')
    expect(gate.text()).not.toContain('影响面')

    // 单层卡体子块序 = 字段表 / 按钮行（demo 场景一：无中间区、无内层卡头）
    const kids = Array.from(gate.element.children) as HTMLElement[]
    expect(kids[0]!.getAttribute('data-testid')).toBe('gate-rows')
    expect(kids[1]!.querySelectorAll('button')).toHaveLength(2)

    // 标题业务化（demo 场景一）：待你确认：<类型> 任务 #<id>『<title>』
    const card = wrapper.find('[data-testid="pending-card-pf1"]')
    expect(card.find('[data-testid="card-title-pf1"]').text()).toBe('待你确认：导入 任务 #5『订单配置导入』')
    // 标题说清后不再重复「工具名（任务 #N）」摘要
    expect(card.find('[data-testid="tool-summary-pf1"]').text()).toBe('')
    // chip = 琥珀「待确认」
    expect(card.find('[data-testid="card-status-pf1"]').text()).toBe('待确认')
    expect(card.find('[data-testid="card-status-pf1"]').classes()).toEqual(expect.arrayContaining(['text-[#b45309]', 'bg-[#fffbeb]', 'border-[#fde68a]']))

    // 操作/参数行一个不许少（demo 场景一：操作 + args 全键）
    const rows = gate.find('[data-testid="gate-rows"]').findAll(':scope > div')
    const rowCells = rows.map((row) => Array.from(row.element.children) as HTMLElement[])
    expect(rowCells.map((cells) => cells[0]!.textContent)).toEqual(['操作', 'taskId'])
    expect(rows[0]!.text()).toContain('启动发布作业')
    expect(rows[1]!.text()).toContain('5')

    // 按钮文案口径（H1 仅两钮）：拒绝 / 批准
    expect(gate.findAll('button').map((btn) => btn.text())).toEqual(['拒绝', '批准'])
    wrapper.unmount()
  })

  it('卡面一致性：门卡无 preflight → 标题回落工具名（现形态），无预检区（回归锁）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'text', seg: 0, text: '', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'np1', name: 'danger_tool', kind2: 'CONFIRM', status: 'pending', args: '{"x":1}', timeoutSeconds: 120, requiresAction: true }
      ])
    ])
    const gate = wrapper.find('[data-testid="pending-card-np1"]').find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)
    expect(gate.find('[data-testid="preflight-summary"]').exists()).toBe(false)
    expect(gate.text()).not.toContain('预检摘要')
    expect(gate.text()).not.toContain('批准绑定')
    // 单层卡体子块序 = 字段表 / 按钮行 两子块（无新增中间区）
    const kids = Array.from(gate.element.children) as HTMLElement[]
    expect(kids[0]!.getAttribute('data-testid')).toBe('gate-rows')
    expect(kids[1]!.querySelectorAll('button')).toHaveLength(2)
    // 无 preflight ⇒ 标题回落现形态（工具名），无「待你确认：」前缀
    expect(wrapper.find('[data-testid="card-title-np1"]').text()).toBe('danger_tool')
    wrapper.unmount()
  })

  it('卡面一致性：checks 含未通过项 → 卡面同样不出预检区（payload 不渲染，无防御分支）', async () => {
    const failed = {
      ...PREFLIGHT,
      checks: [
        { name: '任务存在', pass: true, detail: '' },
        { name: '类型匹配', pass: false, detail: '任务 #5 类型 EXPORT，不能发起 PUBLISH 作业' }
      ]
    }
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'text', seg: 0, text: '', streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'pf2', name: 'start_publish', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":5}', timeoutSeconds: 120, requiresAction: true, preflight: failed }
      ])
    ])
    expect(wrapper.find('[data-testid="preflight-summary"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('预检未通过')
    expect(wrapper.find('[data-testid="card-title-pf2"]').text()).toBe('待你确认：导入 任务 #5『订单配置导入』')
    wrapper.unmount()
  })

  it('卡面一致性：runs/current 快照带 preflight → 段保留 payload，卡面不出预检区（MAJOR-1 改写）', async () => {
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    api.history.mockResolvedValue({ sessionId: 'sess-pf', count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-pf',
      snapshotStatus: 'PENDING',
      pendingEntries: [
        { toolCallId: 'rpf1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_publish', arguments: '{"taskId":5}', req: true, preflight: PREFLIGHT }
      ],
      unresolvedExternal: ['rpf1'],
      awaitingExternal: true,
      archiveMaxSeq: 3
    })
    const store = useAiStore()
    store.sessionId = 'sess-pf'
    // 空历史 + onMounted loadHistory：走「历史空态 → restoreSuspendedSnapshot」的刷新恢复路径
    const wrapper = await mountPanel([])
    await flushPromises()

    expect(wrapper.find('[data-testid="preflight-summary"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('预检摘要')
    const part = store.messages[0]?.parts.find((item) => item.kind === 'tool')
    expect(part).toMatchObject({ toolCallId: 'rpf1', preflight: PREFLIGHT })
    // 恢复出来的挂起卡仍走业务化标题（preflight.target 驱动），payload 本身不展示
    expect(wrapper.find('[data-testid="card-title-rpf1"]').text()).toBe('待你确认：导入 任务 #5『订单配置导入』')
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

  it('审批身份①：同消息两卡点第一张 → aiApi.confirm 收到第一张卡的 toolCallId（旧单槽会发末段 id）', async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'text', seg: 0, text: '', streaming: false },
          { kind: 'tool', seg: 1, toolCallId: 'p1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true },
          { kind: 'tool', seg: 2, toolCallId: 'p2', name: 'delete_job', kind2: 'CONFIRM', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true }
        ],
        // 旧单槽投影 = 末段（p2）：点 p1 时旧实现会误提 p2
        { toolCallId: 'p2', name: 'delete_job', kind: 'CONFIRM', args: {}, rawArgs: '{}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const store = useAiStore()
    store.activeRunId = 'run-1'
    store.suspended = true

    const gate = wrapper.find('[data-testid="pending-card-p1"]').find('[data-testid="hitl-gate"]')
    await gate.findAll('button')[1]!.trigger('click')
    await flushPromises()

    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0]).toMatchObject({ runId: 'run-1', toolCallId: 'p1', approved: true })
    // 只落自己这张：另一张仍待决，挂起态不被误清
    expect(store.pendingConfirms.map((part) => part.toolCallId)).toEqual(['p2'])
    expect(store.suspended).toBe(true)
    wrapper.unmount()
  })

  it('审批身份②：CONFIRM 与 FRONTEND 待决并存，点 CONFIRM 卡 → 提交 CONFIRM 的 id 而非最近投影的 FRONTEND', async () => {
    const wrapper = await mountPanel([
      buildMessage(
        [
          { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', timeoutSeconds: 120, requiresAction: true },
          { kind: 'tool', seg: 1, toolCallId: 'f1', name: 'probe.tool', kind2: 'FRONTEND', status: 'pending', args: '{}', timeoutSeconds: 120, requiresAction: true }
        ],
        // 最近 requiresAction 段是 FRONTEND：旧 confirm() 取 pendingToolCall 会把 f1 发到 /api/ai/confirm
        { toolCallId: 'f1', name: 'probe.tool', kind: 'FRONTEND', args: {}, rawArgs: '{}', timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const store = useAiStore()
    store.activeRunId = 'run-1'
    store.suspended = true

    const gate = wrapper.find('[data-testid="pending-card-c1"]').find('[data-testid="hitl-gate"]')
    await gate.findAll('button')[0]!.trigger('click')
    await flushPromises()

    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0]).toMatchObject({ runId: 'run-1', toolCallId: 'c1', approved: false, reason: '用户拒绝执行' })
    expect(api.confirm.mock.calls[0]![0].toolCallId).not.toBe('f1')
    // FRONTEND 段仍在待前端执行，未被这次拒绝波及
    const frontendPart = store.messages[0]!.parts.find((part) => part.kind === 'tool' && part.toolCallId === 'f1')
    expect(frontendPart).toMatchObject({ status: 'pending', requiresAction: true })
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
    // R-163 T2 口径：无映射工具的摘要兜底 = 「工具名 · 状态」；问题1 偏差修正批项 3 起，
    // 显示层按前缀剥掉工具名 —— 剥后只剩状态词（状态胶囊已同屏承担）⇒ 摘要位不渲，
    // 工具名标签本体仍恰一次（按标签边界计数）。
    expect(wrapper.html().split('>probe.tool<').length - 1).toBe(1)
    expect(wrapper.find('[data-testid="tool-summary-f1"]').text()).toBe('')
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

  it("R-144 还原锚：段 status=blocked ⇒ 渲染「超出当前范围」（状态色由 cardStatusOf tone 承担，文案面锁定）", async () => {
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

    await store.cancelGenerativeForm()
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
        { kind: 'tool', seg: 1, toolCallId: 'a', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', result: '任务清单', requiresAction: false },
        { kind: 'tool', seg: 2, toolCallId: 'b', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', result: '定义清单', requiresAction: false },
        { kind: 'tool', seg: 3, toolCallId: 'c', name: 'read_job', kind2: 'BACKEND', status: 'succeeded', result: '作业详情', requiresAction: false },
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
      { kind: 'tool', seg: 0, toolCallId: 'run1', name: 'list_tasks', kind2: 'BACKEND', status: 'running', result: '进行中…', requiresAction: false },
      { kind: 'tool', seg: 1, toolCallId: 'run2', name: 'read_job', kind2: 'BACKEND', status: 'running', result: '进行中…', requiresAction: false }
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
      { kind: 'tool', seg: 0, toolCallId: 'done1', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', result: '任务清单', requiresAction: false },
      { kind: 'tool', seg: 1, toolCallId: 'done2', name: 'read_job', kind2: 'BACKEND', status: 'succeeded', result: '作业详情', requiresAction: false }
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
    message.parts.push({ kind: 'tool', seg: 2, toolCallId: 'done3', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', result: '定义清单', requiresAction: false })
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

  it('R-154 改判（S6 修订）：段 reason 非空的卡也默认折叠；已决 GF 的 reason 落只读卡卡面（无需展开）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'x1', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', result: '任务清单', requiresAction: false },
        { kind: 'tool', seg: 1, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', requiresAction: false, reason: '取消回灌未送达（网络异常），后端将在超时后自动收尾' },
        { kind: 'tool', seg: 2, toolCallId: 'x2', name: 'read_job', kind2: 'BACKEND', status: 'succeeded', result: '作业详情', requiresAction: false }
      ])
    ])
    // R-154：reason 非空不再是默认展开判据 —— 有内容的卡各自独立、均默认折叠
    expect(wrapper.findAll('[data-testid^="tool-card-"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="block-body-a-1:x1"]').attributes('style')).toContain('display: none')
    // 已决 GF（cancelled）⇒ 原卡只读态：拒因/失败原因**常驻卡面**（不藏在展开体里，demo 19-b）
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="ro-reason-g1"]').text()).toContain('取消回灌未送达（网络异常），后端将在超时后自动收尾')
    // 有内容的普通卡：点击卡头展开 ⇒ 输出与参数位露出
    await wrapper.find('[data-testid="block-toggle-a-1:x1"]').trigger('click')
    expect(wrapper.find('[data-testid="block-body-a-1:x1"]').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.find('[data-testid="block-body-a-1:x1"]').text()).toContain('任务清单')
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
    const wrapper = await mountPanel([
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
    expect(outBox.className).toContain('max-h-[min(280px,35vh)]')
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

  it('#2 noticeCode：zh 按码+数字拼装；旧帧无 code 回退 truncatedReason', async () => {
    const wrapper = await mountPanelWith([
      buildMessage([
        {
          kind: 'tool', seg: 0, toolCallId: 'nc1', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
          args: '{}', result: '前 64KB…', truncated: true,
          truncatedReason: 'Result truncated because it exceeded the frame size limit.',
          noticeCode: 'frame_truncated', originalChars: 9_000, returnedChars: 3_195, strategy: 'head',
          requiresAction: false
        }
      ])
    ])
    const title = wrapper.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
    expect(title).toContain('已压缩显示')
    expect(title).toContain('3195')
    expect(title).toContain('9000')
    expect(title).not.toContain('Result truncated because')
    wrapper.unmount()

    const modelView = await mountPanelWith([
      buildMessage([
        {
          kind: 'tool', seg: 0, toolCallId: 'nc2', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
          args: '{}', result: '全文', truncated: true, truncatedReason: 'legacy',
          noticeCode: 'model_view_truncated', originalChars: 25_000, returnedChars: 19_000, strategy: 'head',
          requiresAction: false
        }
      ])
    ])
    const modelTitle = modelView.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
    expect(modelTitle).toContain('模型')
    expect(modelTitle).toContain('仅收到')
    expect(modelTitle).toContain('19000')
    expect(modelTitle).toContain('25000')
    // I-1：实时/历史都不许声称“此处仍显示完整结果”（历史里模型侧裁剪后的 message 才是持久原文）
    expect(modelTitle).not.toContain('完整结果')
    modelView.unmount()

    const limit = await mountPanelWith([
      buildMessage([
        {
          kind: 'tool', seg: 0, toolCallId: 'nc4', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
          args: '{}', result: '超大结果', truncated: true, truncatedReason: 'legacy',
          noticeCode: 'frame_limit_unreached', originalChars: 9_000, returnedChars: 0,
          requiresAction: false
        }
      ])
    ])
    const limitTitle = limit.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
    expect(limitTitle).toContain('未能进一步压缩')
    expect(limitTitle).not.toContain('已压缩')
    limit.unmount()

    const legacy = await mountPanelWith([
      buildMessage([
        {
          kind: 'tool', seg: 0, toolCallId: 'nc3', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
          args: '{}', result: '旧结果', truncated: true, truncatedReason: '旧帧原因', requiresAction: false
        }
      ])
    ])
    expect(legacy.find('[data-testid="truncated-badge"]').attributes('title')).toBe('旧帧原因')
    legacy.unmount()
  })

  it('#2 noticeCode：en 全英文；不可压缩码不谎称已压缩', async () => {
    const { default: i18n } = await import('@/locales')
    i18n.global.locale.value = 'en-US'
    try {
      const wrapper = await mountPanelWith([
        buildMessage([
          {
            kind: 'tool', seg: 0, toolCallId: 'ne1', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
            args: '{}', result: '前 64KB…', truncated: true,
            truncatedReason: 'Result truncated because it exceeded the frame size limit.',
            noticeCode: 'frame_truncated', originalChars: 9_000, returnedChars: 3_195, strategy: 'head',
            requiresAction: false
          }
        ])
      ])
      const title = wrapper.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
      expect(title).toContain('truncated')
      expect(title).toContain('3195')
      expect(title).toContain('9000')
      expect(title).not.toMatch(/[\u4e00-\u9fff]/)
      wrapper.unmount()

      const model = await mountPanelWith([
        buildMessage([
          {
            kind: 'tool', seg: 0, toolCallId: 'ne-model', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
            args: '{}', result: '全文', truncated: true, truncatedReason: 'legacy',
            noticeCode: 'model_view_truncated', originalChars: 25_000, returnedChars: 19_000, strategy: 'head',
            requiresAction: false
          }
        ])
      ])
      const modelTitle = model.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
      expect(modelTitle).toContain('received only')
      expect(modelTitle).toContain('19000')
      expect(modelTitle).toContain('25000')
      expect(modelTitle).not.toContain('full result')
      model.unmount()

      const limit = await mountPanelWith([
        buildMessage([
          {
            kind: 'tool', seg: 0, toolCallId: 'ne2', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded',
            args: '{}', result: '超大结果', truncated: true,
            truncatedReason: 'Result exceeds the frame size limit and could not be compressed further.',
            noticeCode: 'frame_limit_unreached', originalChars: 9_000, returnedChars: 0,
            requiresAction: false
          }
        ])
      ])
      const limitTitle = limit.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
      expect(limitTitle).toContain('could not be compressed further')
      expect(limitTitle).not.toContain('truncated')
      expect(limitTitle).not.toMatch(/[\u4e00-\u9fff]/)
      limit.unmount()
    } finally {
      i18n.global.locale.value = 'zh-CN'
    }
  })

  it('#2 delta 正文/思考截断：段上出角标并本地化', async () => {
    const wrapper = await mountPanelWith([
      buildMessage([
        {
          kind: 'text', seg: 0, text: '被截断的正文', streaming: false, truncated: true,
          noticeCode: 'frame_truncated', originalChars: 20_000, returnedChars: 3_400, strategy: 'head',
          truncatedReason: 'Result truncated because it exceeded the frame size limit.'
        }
      ])
    ])
    const textBadge = wrapper.find('[data-testid="text-truncated-badge"]')
    expect(textBadge.exists()).toBe(true)
    expect(textBadge.attributes('title')).toContain('3400')
    expect(textBadge.attributes('title')).toContain('20000')
    wrapper.unmount()

    const reasoning = await mountPanelWith([
      buildMessage([
        {
          kind: 'reasoning', seg: 0, text: '被截断的思考', streaming: false, truncated: true,
          noticeCode: 'frame_truncated', originalChars: 9_000, returnedChars: 2_000, strategy: 'head',
          truncatedReason: 'Result truncated because it exceeded the frame size limit.'
        }
      ])
    ])
    const reasoningBadge = reasoning.find('[data-testid="reasoning-truncated-badge"]')
    expect(reasoningBadge.exists()).toBe(true)
    expect(reasoningBadge.attributes('title')).toContain('2000')
    reasoning.unmount()
  })

  it('#2 旧帧：zh 保留旧中文；en 遇中文原因改用 locale 兜底', async () => {
    const legacyPart = {
      kind: 'tool' as const, seg: 0, toolCallId: 'old1', name: 'get_row_count', kind2: 'BACKEND' as const,
      status: 'succeeded' as const, args: '{}', result: '旧结果', truncated: true,
      truncatedReason: '单帧 64KB 预算超限，结果字段被截断', requiresAction: false
    }
    const zh = await mountPanelWith([buildMessage([legacyPart])])
    expect(zh.find('[data-testid="truncated-badge"]').attributes('title'))
      .toBe('单帧 64KB 预算超限，结果字段被截断')
    zh.unmount()

    const { default: i18n } = await import('@/locales')
    i18n.global.locale.value = 'en-US'
    try {
      const en = await mountPanelWith([buildMessage([legacyPart])])
      const title = en.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
      expect(title).toBe('Result truncated')
      expect(title).not.toMatch(/[\u4e00-\u9fff]/)
      en.unmount()
    } finally {
      i18n.global.locale.value = 'zh-CN'
    }
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

  it('#12：空态出静态建议词列表，点击直接发送（批 E · E2 口径：不再回填输入框）', async () => {
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
    api.chat.mockReset()
    api.chat.mockResolvedValue(undefined)
    await chips[0].trigger('click')
    await flushPromises()
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect((api.chat.mock.calls[0][0] as Record<string, unknown>).message).toBe('列出全部配置定义')
    await wrapper.vm.$nextTick()
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('')
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

  it('A10 R-15 历史重建：截断字段落到 ToolPart/TextPart ⇒ 刷新后角标与 locale hover 不丢', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '被截断的正文',
          // 文本侧 additive 字段（实际后端目前只在工具响应侧产；此处锁"载荷带则保留"）
          truncated: true,
          noticeCode: 'frame_truncated',
          originalChars: 9_000,
          returnedChars: 3_195,
          strategy: 'head',
          truncatedReason: '旧帧原因',
          toolCalls: [{ id: 'h1', name: 'get_row_count', arguments: '{}' }]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [
            {
              id: 'h1',
              name: 'get_row_count',
              responseData: '前 64KB…',
              truncated: true,
              noticeCode: 'model_view_truncated',
              originalChars: 25_000,
              returnedChars: 19_000,
              strategy: 'head',
              truncatedReason: 'Result truncated for model view.'
            }
          ]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })

    await store.loadHistory()
    const text = store.messages[0]!.parts.find((part) => part.kind === 'text')
    const tool = store.messages[0]!.parts.find((part) => part.kind === 'tool') as ToolPart
    expect(text).toMatchObject({
      kind: 'text', truncated: true, noticeCode: 'frame_truncated',
      originalChars: 9_000, returnedChars: 3_195, strategy: 'head', truncatedReason: '旧帧原因'
    })
    expect(tool).toMatchObject({
      toolCallId: 'h1', truncated: true, noticeCode: 'model_view_truncated',
      originalChars: 25_000, returnedChars: 19_000, strategy: 'head',
      truncatedReason: 'Result truncated for model view.'
    })

    const wrapper = await mountPanel(store.messages)
    const toolTitle = wrapper.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
    expect(toolTitle).toContain('模型')
    expect(toolTitle).toContain('仅收到')
    expect(toolTitle).toContain('19000')
    expect(toolTitle).toContain('25000')
    expect(toolTitle).not.toContain('完整结果')
    const textTitle = wrapper.find('[data-testid="text-truncated-badge"]').attributes('title') ?? ''
    expect(textTitle).toContain('3195')
    expect(textTitle).toContain('9000')
    wrapper.unmount()

    const { default: i18n } = await import('@/locales')
    i18n.global.locale.value = 'en-US'
    try {
      const english = await mountPanel(store.messages)
      const enTitle = english.find('[data-testid="truncated-badge"]').attributes('title') ?? ''
      expect(enTitle).toContain('19000')
      expect(enTitle).toContain('25000')
      expect(enTitle).not.toMatch(/[\u4e00-\u9fff]/)
      english.unmount()
    } finally {
      i18n.global.locale.value = 'zh-CN'
    }
  })

  it('A10 R-15 旧历史无截断字段：段形状与角标回退不变', async () => {
    const store = useAiStore()
    api.history.mockResolvedValue({
      sessionId: store.sessionId,
      count: 2,
      messages: [
        {
          type: 'ASSISTANT',
          text: '旧历史正文',
          toolCalls: [{ id: 'h2', name: 'get_row_count', arguments: '{}' }]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{ id: 'h2', name: 'get_row_count', responseData: '完整结果' }]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })

    await store.loadHistory()
    const text = store.messages[0]!.parts.find((part) => part.kind === 'text')
    const tool = store.messages[0]!.parts.find((part) => part.kind === 'tool') as ToolPart
    expect(text?.truncated).toBeUndefined()
    expect(tool.truncated).toBeUndefined()
    expect(tool.noticeCode).toBeUndefined()
    expect(tool.originalChars).toBeUndefined()

    const wrapper = await mountPanel(store.messages)
    expect(wrapper.find('[data-testid="truncated-badge"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="text-truncated-badge"]').exists()).toBe(false)
    wrapper.unmount()
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
 * S6 批1（demo v7.9 场景 19 / 19-f · v7.10 ajv11）——「回执独立块退役 · 原卡只读态」的 UI 面锁：
 * 已决 CONFIRM / GF 段由其**自己的卡**原地切只读态（徽标 + 人话摘要 + 时间戳 + chevron + 只读表），
 * 空内容卡不给展开入口，发布冲突明细收敛为表格。
 * R-158 旧面（#11 复制段间换行）保留在同一 describe 内。
 */
describe('2A 批：已决原样 UI 只读态 / 折叠时序 / 冲突表格（R-158 复制换行顺带）', () => {
  it('2A：CONFIRM 已决 = 原样只读（卡头裸行 + 门卡字段表原样 + 零按钮 + 无时间）', async () => {
    const msg = buildMessage([
      { kind: 'text', seg: 0, text: '', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId: 'p1', name: 'start_publish', kind2: 'CONFIRM',
        status: 'succeeded', args: '{"taskId":3}', requiresAction: false,
        decision: 'approved', decidedAt: 1_700_000_000_000
      }
    ])
    const wrapper = await mountPanel([msg])
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.exists()).toBe(true)
    // 单一元素 = 工具段自己的卡（不再有独立的第二张回执卡 / 也不再是 tool-card-*）
    expect(wrapper.find('[data-testid="tool-card-p1"]').exists()).toBe(false)
    // 卡头：左类型图标 + 中性标题/摘要 + 右状态标签（成功终态默认折叠 ⇒ 摘要可见）；**时间不上卡面**（M1）
    expect(receipt.text()).toContain('✓ 已批准')
    expect(receipt.find('[data-testid="card-title-p1"]').text()).toBe('启动发布作业')
    expect(receipt.find('[data-testid="ro-summary-p1"]').text()).toBe('（任务 #3）')
    expect(receipt.text()).not.toMatch(/\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}/)
    expect(receipt.text()).not.toMatch(/\d{2}:\d{2}/)
    expect(receipt.find('[data-testid="ro-badge-p1"]').exists()).toBe(true)
    expect(receipt.find('[data-testid="ro-summary-p1"]').exists()).toBe(true)
    // 卡容器 = 原卡壳（无只读专用底色 / 无只读 chip，H1）
    expect(receipt.classes()).toEqual(expect.arrayContaining(['rocard', 'border-[#e3e6ea]', 'bg-white', 'rounded-[10px]', 'overflow-hidden', 'border-solid']))
    // 卡头 = 裸文字行：无底色 / 无分隔线，px-10 / py-8 / gap-6（36.4px 同工具卡规格）
    const head = receipt.find('[data-testid="ro-toggle-p1"]')
    expect(head.classes()).toEqual(expect.arrayContaining(['ro-hd', 'flex', 'items-center', 'gap-[6px]', 'w-full', 'px-[10px]', 'py-[8px]', 'border-0', 'bg-transparent', 'text-left']))
    expect(head.classes()).not.toContain('border-b')
    // chevron：文字 ›、12px、ml-auto 靠右、展开 rotate-90
    const chev = head.findAll('span').at(-1)!
    expect(chev.text()).toBe('›')
    expect(chev.classes()).toEqual(expect.arrayContaining(['ml-auto', 'text-[12px]', 'tchev']))
    // 左槽 = 类型裸 SVG（中性色）；状态字形退场、状态只在右标签
    const icon = receipt.find('[data-testid="card-icon-p1"] svg')
    expect(icon.exists()).toBe(true)
    expect(icon.attributes('stroke')).toBe('#57606a')
    expect(icon.attributes('aria-hidden')).toBe('true')
    expect(icon.element.outerHTML).not.toMatch(/#2563eb|#1a7f45|#c0392b|#b45309/)
    expect(receipt.text()).not.toContain('只读')
    // 「查看详情 / 收起详情」文字钮全稿退役（裁决 2026-10-16 第 2 条）
    expect(wrapper.text()).not.toContain('查看详情')
    expect(wrapper.find('[data-testid^="receipt-args-toggle-"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid^="receipt-args-more-"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid^="receipt-more-"]').exists()).toBe(false)
    // 成功终态默认折叠（回合已结束）⇒ 卡体隐藏；点卡头展开 = 原样门卡字段表（gateRows 同源）
    const body = receipt.find('[data-testid="ro-body-p1"]')
    expect(body.exists()).toBe(true)
    expect(body.attributes('style') ?? '').toContain('display: none')
    await head.trigger('click')
    const shown = receipt.find('[data-testid="ro-body-p1"]')
    expect(shown.attributes('style') ?? '').not.toContain('display: none')
    expect(receipt.find('[data-testid="ro-toggle-p1"]').attributes('aria-expanded')).toBe('true')
    expect(shown.text()).toContain('启动发布作业')
    expect(shown.text()).toContain('taskId')
    expect(shown.text()).toContain('3')
    // 参数区字段表原样（字段族双约束 min(280px,35vh)）；零操作按钮；零 JSON 字符
    const table = shown.find('[data-testid="ro-rows-p1"]')
    expect(table.classes()).toEqual(expect.arrayContaining(['max-h-[min(280px,35vh)]', 'overflow-y-auto', 'bg-white', 'rounded-[8px]']))
    expect(shown.findAll('button')).toHaveLength(0)
    expect(shown.text()).not.toContain('{')
    expect(receipt.text()).not.toContain('查看详情')
    wrapper.unmount()
  })

  it('2A：无参数纯确认 ⇒ 卡体仍有「操作」行原样；卡头可折叠（不再有 0 行无入口）', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'e9', name: 'confirm_step', kind2: 'CONFIRM',
        status: 'succeeded', args: '{}', requiresAction: false, decision: 'approved', decidedAt: 1_700_000_000_000
      }
    ])
    const wrapper = await mountPanel([msg])
    const head = wrapper.find('[data-testid="ro-toggle-e9"]')
    expect(head.exists()).toBe(true)
    expect(head.attributes('disabled')).toBeUndefined()
    expect(head.classes()).toContain('cursor-pointer')
    expect(head.findAll('span').at(-1)!.text()).toBe('›')
    const body = wrapper.find('[data-testid="ro-body-e9"]')
    expect(body.exists()).toBe(true)
    await head.trigger('click')
    expect(wrapper.find('[data-testid="ro-body-e9"]').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.find('[data-testid="ro-rows-e9"]').text()).toContain('操作')
    expect(wrapper.find('[data-testid="ro-rows-e9"]').text()).toContain('确认步骤')
    // 成功终态摘要仍在（折叠 / 展开都不改摘要文本）；状态文本只由右标签承担
    expect(wrapper.find('[data-testid="ro-badge-e9"]').text()).toBe('✓ 已批准')
    expect(wrapper.find('[data-testid="ro-summary-e9"]').text()).toContain('确认当前步骤')
    wrapper.unmount()
  })

  it('2A：GF 已决 = 同一张表单原生 disabled 值回填（枚举中文 / 布尔是否 / 多选 / 文本；零按钮）', async () => {
    const gf = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded',
        requiresAction: false,
        args: JSON.stringify({
          form: {
            scenario: 'export_query',
            title: '导出参数',
            fields: [
              { key: 'region', label: '导出范围', type: 'enum', required: true, options: [{ value: 'HB', label: '华北' }, { value: 'HD', label: '华东' }] },
              { key: 'compress', label: '是否压缩', type: 'boolean', required: false },
              { key: 'tags', label: '标签', type: 'multi_select', required: false, options: [{ value: 'A', label: '甲' }, { value: 'B', label: '乙' }] },
              { key: 'outputFile', label: '结果文件', type: 'text', required: false }
            ]
          }
        }),
        submittedValues: { region: 'HB', compress: true, tags: ['A', 'B'], outputFile: '/srv/app/exports/sync-report-2026-10-15.json' }
      }
    ])
    gf.id = 'a-gf'
    const plain = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 't1', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '任务清单', requiresAction: false }
    ])
    plain.id = 'a-plain'

    const wrapper = await mountPanel([gf, plain], { realForm: true })
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.text()).toContain('✓ 已提交')
    expect(receipt.find('[data-testid="card-title-g1"]').text()).toBe('导出参数')
    expect(receipt.find('[data-testid="ro-summary-g1"]').text()).toContain('导出范围 华北')
    const head = wrapper.find('[data-testid="ro-toggle-g1"]')
    await head.trigger('click')
    const body = wrapper.find('[data-testid="ro-body-g1"]')
    // 卡面一致性：只读体不再重复标题（标题只在统一卡头），卡体直接字段表（demo 场景五）
    expect(body.text()).not.toContain('导出参数')
    expect(body.find('[data-testid="gf-fields"]').exists()).toBe(true)
    // P2-4 卡体留白锁：只读 GF = 与门卡只读态同构的 mx-[11px] mt-[9px] 包裹（demo 场景五），
    // inset 只由包裹层承担，字段区不再叠加第二份 11px。
    const formWrap = body.find('[data-testid="ro-form-g1"]')
    expect(formWrap.exists()).toBe(true)
    expect(formWrap.classes()).toEqual(expect.arrayContaining(['mx-[11px]', 'mt-[9px]']))
    expect(formWrap.find('[data-testid="gf-fields"]').exists()).toBe(true)
    expect(formWrap.find('[data-testid="gf-fields"]').classes()).not.toContain('mx-[11px]')
    const select = body.find('select')
    expect(select.element.value).toBe('HB')
    expect(select.attributes('disabled')).toBeDefined()
    expect(body.text()).toContain('华北')
    const checkboxes = body.findAll('input[type="checkbox"]')
    expect(checkboxes).toHaveLength(3) // 1 布尔 + 2 多选
    expect(checkboxes.every((box) => box.attributes('disabled') !== undefined)).toBe(true)
    const checkedStates = checkboxes.map((box) => (box.element as HTMLInputElement).checked)
    expect(checkedStates).toEqual([true, true, true]) // compress=true / tags=[A,B]
    const textInput = body.find('input[type="text"]')
    expect((textInput.element as HTMLInputElement).value).toBe('/srv/app/exports/sync-report-2026-10-15.json')
    // 已决分支零操作按钮（K1：不是置灰、不是隐藏节点）
    expect(body.findAll('button')).toHaveLength(0)
    expect(wrapper.find('[data-testid="gf-submit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="gf-cancel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="gf-deadline"]').exists()).toBe(false)
    // 任何路径不展示 JSON / 表单定义键（正文文本）
    for (const key of ['scenario', 'fields', '{', '[', 'multi_select']) {
      expect(body.text()).not.toContain(key)
    }
    // 普通 BACKEND 工具卡不出只读卡（本消息域恰有 1 张只读卡）
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(1)
    wrapper.unmount()
  })

  it('2A：折叠时机 = 回合结束才收（决策当下不折叠；本轮收尾后自动折叠）', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 't1', name: 'start_publish', kind2: 'CONFIRM',
        status: 'succeeded', args: '{"taskId":3}', requiresAction: false, decision: 'approved', decidedAt: 1_700_000_000_000
      }
    ])
    const store = useAiStore()
    const wrapper = await mountPanel([msg])
    // 回合仍在进行（loading + 末条消息）⇒ 成功卡保持展开（不把刚做的决定秒收）
    store.loading = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="ro-body-t1"]').attributes('style') ?? '').not.toContain('display: none')
    // 回合结束（loading=false）⇒ 收成卡头行
    store.loading = false
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="ro-body-t1"]').attributes('style') ?? '').toContain('display: none')
    wrapper.unmount()

    // 手动收起优先于「回合未结束」时序（用户点了就听用户的）
    const w2 = await mountPanel([msg])
    useAiStore().loading = true
    await w2.vm.$nextTick()
    await w2.find('[data-testid="ro-toggle-t1"]').trigger('click')
    expect(w2.find('[data-testid="ro-body-t1"]').attributes('style') ?? '').toContain('display: none')
    w2.unmount()
  })

  it('r15：六态矩阵 —— 回合未结束时全部展开；loading 结束后全部折叠（aria-expanded=false）+ 摘要非空', async () => {
    const formArgs = JSON.stringify({
      form: {
        scenario: 'CONFIRM',
        title: '补充发布说明',
        fields: [{ key: 'note', label: '说明', type: 'text', required: false }]
      }
    })
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 't-ok', name: 'start_publish', kind2: 'CONFIRM', status: 'succeeded', args: '{"taskId":3}', requiresAction: false, decision: 'approved' },
      { kind: 'tool', seg: 1, toolCallId: 't-no', name: 'start_publish', kind2: 'CONFIRM', status: 'rejected', args: '{"taskId":4}', requiresAction: false, decision: 'rejected' },
      { kind: 'tool', seg: 2, toolCallId: 't-exp', name: 'start_export', kind2: 'CONFIRM', status: 'expired', args: '{"taskId":5}', requiresAction: false },
      { kind: 'tool', seg: 3, toolCallId: 't-bad', name: 'start_publish', kind2: 'CONFIRM', status: 'failed', args: '{"taskId":6}', requiresAction: false, decision: 'approved', reason: '发布失败：目标库不可写' },
      { kind: 'tool', seg: 4, toolCallId: 'g-ok', name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded', args: formArgs, requiresAction: false, submittedValues: { note: '已提交内容' } },
      { kind: 'tool', seg: 5, toolCallId: 'g-no', name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', args: formArgs, requiresAction: false, submittedValues: { note: '半成品内容' } }
    ])
    const ids = ['t-ok', 't-no', 't-exp', 't-bad', 'g-ok', 'g-no']
    const store = useAiStore()
    const wrapper = await mountPanel([msg])
    store.loading = true
    await wrapper.vm.$nextTick()
    // 回合未结束（loading + 末条消息）⇒ 六态均保持展开（等回合收尾再收）
    for (const id of ids) {
      expect(wrapper.find(`[data-testid="ro-body-${id}"]`).attributes('style') ?? '').not.toContain('display: none')
      expect(wrapper.find(`[data-testid="ro-toggle-${id}"]`).attributes('aria-expanded')).toBe('true')
    }
    store.loading = false
    await wrapper.vm.$nextTick()
    const keywords: Record<string, string> = {
      't-ok': '已批准', 't-no': '已拒绝', 't-exp': '已超时', 't-bad': '已批准 · 执行失败',
      'g-ok': '已提交', 'g-no': '已取消'
    }
    for (const id of ids) {
      expect(wrapper.find(`[data-testid="ro-body-${id}"]`).attributes('style') ?? '').toContain('display: none')
      expect(wrapper.find(`[data-testid="ro-toggle-${id}"]`).attributes('aria-expanded')).toBe('false')
      // 状态文案只在右标签；摘要保留可辨识信息（不再重复状态前缀）
      expect(wrapper.find(`[data-testid="ro-badge-${id}"]`).text()).toContain(keywords[id]!)
      expect(wrapper.find(`[data-testid="ro-summary-${id}"]`).text().trim()).not.toBe('')
    }
    // 取消不拼接 / 不恢复未提交值（即使段上残留 submittedValues）
    expect(wrapper.find('[data-testid="ro-summary-g-no"]').text()).not.toContain('半成品内容')
    expect(wrapper.find('[data-testid="ro-summary-g-no"]').text()).not.toContain('说明 半成品')
    // 手动点击展开 / 再收起（已决卡用户优先）
    await wrapper.find('[data-testid="ro-toggle-t-no"]').trigger('click')
    expect(wrapper.find('[data-testid="ro-body-t-no"]').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.find('[data-testid="ro-toggle-t-no"]').attributes('aria-expanded')).toBe('true')
    await wrapper.find('[data-testid="ro-toggle-t-no"]').trigger('click')
    expect(wrapper.find('[data-testid="ro-body-t-no"]').attributes('style') ?? '').toContain('display: none')
    expect(wrapper.find('[data-testid="ro-toggle-t-no"]').attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('r15 负控制：pending / blocked / unknown 与非决策待决门卡不得被只读折叠策略误收', async () => {
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'p1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', requiresAction: false },
      { kind: 'tool', seg: 1, toolCallId: 'b1', name: 'start_export', kind2: 'CONFIRM', status: 'blocked', args: '{"taskId":2}', requiresAction: false },
      { kind: 'tool', seg: 2, toolCallId: 'u1', name: 'start_export', kind2: 'CONFIRM', status: 'unknown', args: '{"taskId":3}', requiresAction: false },
      { kind: 'tool', seg: 3, toolCallId: 'gate', name: 'confirm_step', kind2: 'CONFIRM', status: 'pending', args: '{}', requiresAction: true }
    ])
    const store = useAiStore()
    const wrapper = await mountPanel([msg])
    store.loading = true
    await wrapper.vm.$nextTick()
    // 非决策态：继续走普通工具卡，不生成只读卡
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(0)
    for (const id of ['p1', 'b1', 'u1']) {
      expect(wrapper.find(`[data-testid="tool-card-${id}"]`).exists()).toBe(true)
      expect(wrapper.find(`[data-testid="block-body-a-1:${id}"]`).attributes('style') ?? '').toContain('display: none')
    }
    // requiresAction 待决门卡：强制展开（不受「六态折叠」影响）
    const gateBody = wrapper.find('[data-testid="block-body-a-1:gate"]')
    expect(wrapper.find('[data-testid="pending-card-gate"]').exists()).toBe(true)
    expect(gateBody.attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()
  })

  it('2A/F9：未提交不记录 —— GF 取消收尾回初始空态（残留提交值也不回填）', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'g9', name: 'generative_form', kind2: 'FRONTEND',
        status: 'cancelled', requiresAction: false,
        // 段上纵有残留值（异常/竞态），未提交收尾一律不记录
        submittedValues: { note: '半成品内容', mode: 'safe' },
        args: JSON.stringify({
          form: {
            scenario: 'CONFIRM',
            title: '补充发布说明',
            fields: [
              { key: 'note', label: '说明', type: 'text', required: false },
              { key: 'mode', label: '模式', type: 'enum', required: false, options: [{ value: 'fast', label: '快速' }, { value: 'safe', label: '安全' }] }
            ]
          }
        })
      }
    ])
    const wrapper = await mountPanel([msg], { realForm: true })
    expect(wrapper.find('[data-testid="ro-badge-g9"]').text()).toBe('✗ 已取消')
    // r15：取消终态同样默认折叠（回合已结束）；摘要非空且不拼接任何未提交值
    expect(wrapper.find('[data-testid="ro-body-g9"]').attributes('style') ?? '').toContain('display: none')
    expect(wrapper.find('[data-testid="ro-toggle-g9"]').attributes('aria-expanded')).toBe('false')
    // 未提交不记录：状态只在右标签；摘要明确「未提交」且不拼接任何残留值
    expect(wrapper.find('[data-testid="ro-badge-g9"]').text()).toBe('✗ 已取消')
    const summary = wrapper.find('[data-testid="ro-summary-g9"]').text().trim()
    expect(summary).toBe('未提交，填写内容不作记录')
    expect(summary).not.toContain('半成品内容')
    expect(summary).not.toContain('安全')
    // 手动展开：同一张表单回初始空态（残留提交值也不回填）
    await wrapper.find('[data-testid="ro-toggle-g9"]').trigger('click')
    const body = wrapper.find('[data-testid="ro-body-g9"]')
    expect(wrapper.find('[data-testid="ro-toggle-g9"]').attributes('aria-expanded')).toBe('true')
    expect(body.attributes('style') ?? '').not.toContain('display: none')
    expect((body.find('input[type="text"]').element as HTMLInputElement).value).toBe('')
    // 枚举回合法默认（首个选项），不是当时选中的 safe
    expect((body.find('select').element as HTMLSelectElement).value).toBe('fast')
    expect(body.text()).not.toContain('半成品内容')
    // 只读态零按钮
    expect(body.findAll('button')).toHaveLength(0)
    wrapper.unmount()
  })

  it('S6：普通工具卡参数 = 折叠区 label-value 字段表（无 JSON 盒；无参则不渲参数区）', async () => {
    const withArgs = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'start_export', kind2: 'BACKEND', status: 'succeeded', args: '{"taskId":290,"full":true,"dirs":["/a/x","/b/y"]}', result: '已启动', requiresAction: false }
    ])
    withArgs.id = 'a-args'
    const noArgs = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'c2', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '任务清单', requiresAction: false }
    ])
    noArgs.id = 'a-noargs'
    const wrapper = await mountPanel([withArgs, noArgs])
    await wrapper.find('[data-testid="block-toggle-a-args:c1"]').trigger('click')
    const rows = wrapper.find('[data-testid="args-rows-c1"]')
    expect(rows.exists()).toBe(true)
    expect(rows.text()).toContain('taskId')
    expect(rows.text()).toContain('290')
    expect(rows.text()).toContain('full')
    expect(rows.text()).toContain('是')
    expect(rows.text()).toContain('/a/x、/b/y')
    expect(rows.text()).not.toContain('{')
    expect(rows.classes()).toEqual(expect.arrayContaining(['max-h-[min(280px,35vh)]', 'overflow-y-auto']))
    // 无参（`'{}'`）⇒ 整块参数区不渲染（不渲空盒、不露 raw 文本）；输出仍在
    await wrapper.find('[data-testid="block-toggle-a-noargs:c2"]').trigger('click')
    expect(wrapper.find('[data-testid="args-rows-c2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="block-body-a-noargs:c2"]').text()).toContain('任务清单')
    wrapper.unmount()
  })

  it('S6/ajv11：三空普通工具卡（无参数 / 无输出 / 无原因）⇒ 无 chevron、卡头 disabled 不可点、无展开体', async () => {
    const empty = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'z1', name: 'check_job_status', kind2: 'BACKEND', status: 'succeeded', args: '{}', requiresAction: false }
    ])
    empty.id = 'a-empty'
    const blank = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'z2', name: 'check_job_status', kind2: 'BACKEND', status: 'succeeded', args: '   ', result: '  ', reason: ' ', requiresAction: false }
    ])
    blank.id = 'a-blank'
    const wrapper = await mountPanel([empty, blank])
    for (const id of ['a-empty:z1', 'a-blank:z2']) {
      const head = wrapper.find(`[data-testid="block-toggle-${id}"]`)
      expect(head.exists()).toBe(true)
      expect(head.attributes('disabled')).toBeDefined()
      expect(head.classes()).toContain('cursor-default')
      expect(head.classes()).not.toContain('hover:bg-[#f6f7f9]')
      expect(head.find('.tchev').exists()).toBe(false)
      expect(wrapper.find(`[data-testid="block-body-${id}"]`).exists()).toBe(false)
      await head.trigger('click')
      expect(wrapper.find(`[data-testid="block-body-${id}"]`).exists()).toBe(false)
    }
    // 例外：requiresAction 待动作卡不受本规则限制（仍强制展开 + 门卡在体内）
    const gate = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'z3', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{}', requiresAction: true }
    ])
    gate.id = 'a-gate'
    const w2 = await mountPanel([gate])
    const gateHead = w2.find('[data-testid="block-toggle-a-gate:z3"]')
    expect(gateHead.attributes('disabled')).toBeDefined() // 待动作卡卡头恒禁点（原口径不变）
    expect(gateHead.classes()).toContain('cursor-default')
    // 待决 HITL 强制展开且无 chevron（折叠动作对用户无意义）
    expect(gateHead.find('.tchev').exists()).toBe(false)
    expect(w2.find('[data-testid="hitl-gate"]').exists()).toBe(true)
    expect(w2.find('[data-testid="block-body-a-gate:z3"]').exists()).toBe(true)
    w2.unmount()
    wrapper.unmount()
  })

  it('S6：门卡字段表 = **全键**（原「首行 + 4」上限退役）+ 敏感值掩码；卡内无 args JSON 盒', async () => {
    const args = JSON.stringify({
      taskId: 290,
      mode: 'full',
      notifyOnFail: true,
      apiKey: 'demo-value-a1',
      secretToken: 'demo-value-b2',
      password: 'demo-value-c3',
      dirs: ['/srv/app/src', '/srv/app/tmp']
    })
    const wrapper = await mountPanel([
      buildMessage(
        [{ kind: 'tool', seg: 0, toolCallId: 'g1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args, timeoutSeconds: 120, requiresAction: true }],
        { toolCallId: 'g1', name: 'start_export', kind: 'CONFIRM', args: JSON.parse(args), rawArgs: args, timeoutSeconds: 120, status: 'pending' }
      )
    ])
    const rows = wrapper.find('[data-testid="gate-rows"]').findAll(':scope > div')
    // 首行「操作」+ **全部 7 个键**（不再截到 4 个）
    expect(rows).toHaveLength(8)
    expect(rows[0]!.text()).toContain('操作')
    expect(rows[0]!.text()).toContain('启动导出作业')
    const text = wrapper.find('[data-testid="gate-rows"]').text()
    for (const key of ['taskId', 'mode', 'notifyOnFail', 'apiKey', 'secretToken', 'password', 'dirs']) {
      expect(text).toContain(key)
    }
    expect(text).toContain('/srv/app/src、/srv/app/tmp') // 数组可读化
    expect(text).toContain('是') // 布尔可读化（notifyOnFail=true）
    // 敏感键掩码：定长圆点 + 原始值零泄露
    const maskedRows = rows.filter((row) => row.text().includes('••••••'))
    expect(maskedRows).toHaveLength(3)
    for (const secret of ['demo-value-a1', 'demo-value-b2', 'demo-value-c3']) {
      expect(text).not.toContain(secret)
    }
    // 门卡字段区仍 160px 内滚（新增键不压垮卡）；同卡 args JSON 盒零命中（裁决 2026-10-16 第 2 条）
    const box = wrapper.find('[data-testid="gate-rows"]').element as HTMLElement
    expect(box.className).toContain('max-h-[min(280px,35vh)]')
    expect(box.className).toContain('overflow-y-auto')
    expect(wrapper.find('[data-testid="args-rows-g1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="hitl-gate"]').text()).not.toContain('{')
    wrapper.unmount()
  })

  it('S6/N8：发布冲突明细 ⇒ reason 只留一句摘要 + 明细渲成表格（配置 / 行键 / 原因）', async () => {
    const reason = [
      '发布作业 #317 校验冲突明细（共 2 处）',
      '1. CURRENCY#GLOBAL：币种精度与主数据不一致',
      '2. TAX_RULE#PRJ-07：税率区间重叠'
    ].join('\n')
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'sp1', name: 'start_publish', kind2: 'BACKEND', status: 'failed', args: '{"taskId":291}', result: '发布作业已启动（作业 #317）…', reason, requiresAction: false }
    ])
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-1:sp1"]').trigger('click')
    const err = wrapper.find('[data-testid="tool-error"]')
    expect(err.text()).toContain('发布作业 #317 校验冲突明细（共 2 处）')
    // 红底行只留摘要 —— 编号明细不在行内（不同屏重复报同一份明细 = 违规）
    expect(err.text()).not.toContain('CURRENCY')
    expect(err.text()).not.toContain('1. ')
    const table = wrapper.find('[data-testid="conflict-table"]')
    expect(table.exists()).toBe(true)
    const header = table.find('div').text()
    expect(header).toBe('配置行键原因')
    const body = table.find('div:nth-child(2)')
    expect(body.classes()).toEqual(expect.arrayContaining(['max-h-[min(480px,60vh)]', 'overflow-y-auto']))
    const text = table.text()
    expect(text).toContain('CURRENCY')
    expect(text).toContain('GLOBAL')
    expect(text).toContain('币种精度与主数据不一致')
    expect(text).toContain('TAX_RULE')
    expect(text).toContain('PRJ-07')
    // 非冲突 reason 不渲表格（普通失败照原样一行）
    const plainFail = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'f1', name: 'start_export', kind2: 'BACKEND', status: 'failed', args: '{}', reason: '作业冲突：请先取消在途作业', requiresAction: false }
    ])
    plainFail.id = 'a-plainfail'
    const w2 = await mountPanel([plainFail])
    await w2.find('[data-testid="block-toggle-a-plainfail:f1"]').trigger('click')
    expect(w2.find('[data-testid="conflict-table"]').exists()).toBe(false)
    expect(w2.find('[data-testid="tool-error"]').text()).toContain('作业冲突：请先取消在途作业')
    w2.unmount()
    wrapper.unmount()
  })

  it('F1 收口 I-3：skipped 段只显示「输出」—— 信封文案全卡恰一次、红色「原因」行不渲染', async () => {
    const TEXT = '未执行：上游 start_export 失败（PRECHECK_NOT_PASSED），本调用已跳过'
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'sk1', name: 'start_import', kind2: 'BACKEND', status: 'skipped', args: '{}', result: TEXT, outcome: 'skipped', requiresAction: false }
    ])
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-1:sk1"]').trigger('click')
    // 卡头仍走 skipped 词表（视觉零改动）
    expect(wrapper.text()).toContain('已跳过')
    // 同文双显清零：同一句信封文案在整张卡里只出现一次（「输出」块）
    expect(wrapper.text().split(TEXT).length - 1).toBe(1)
    // 原因行（普通工具红底 tool-error / 已决只读 ro-reason）零命中
    expect(wrapper.find('[data-testid="tool-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="ro-reason-sk1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('2A/r15：门卡四态徽标（已批准 / 已拒绝 / 已超时 / 已批准·执行失败；无已取消）+ 六态一律折叠', async () => {
    const expired = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e1', name: 'start_export', kind2: 'CONFIRM', status: 'expired', args: '{}', requiresAction: false }
    ])
    expired.id = 'a-expired'
    const expiredLocal = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e2', name: 'start_export', kind2: 'CONFIRM', status: 'expired', args: '{}', requiresAction: false }
    ])
    expiredLocal.id = 'a-expired-local'
    const cancelled = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e3', name: 'start_export', kind2: 'CONFIRM', status: 'cancelled', args: '{}', requiresAction: false }
    ])
    cancelled.id = 'a-cancelled'
    const rejected = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e4', name: 'start_publish', kind2: 'CONFIRM', status: 'rejected', args: '{"taskId":3}', requiresAction: false, decision: 'rejected', decidedAt: 1_700_000_000_000 }
    ])
    rejected.id = 'a-rejected'
    const failed = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e5', name: 'start_publish', kind2: 'CONFIRM', status: 'failed', args: '{"taskId":3}', requiresAction: false, decision: 'approved', reason: '发布失败：目标库不可写' }
    ])
    failed.id = 'a-failed'
    const approved = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'e6', name: 'start_publish', kind2: 'CONFIRM', status: 'succeeded', args: '{"taskId":3}', requiresAction: false, decision: 'approved', decidedAt: 1_700_000_000_000 }
    ])
    approved.id = 'a-approved'

    const wrapper = await mountPanel([expired, expiredLocal, cancelled, rejected, failed, approved])
    const cardText = (id: string): string => wrapper.find(`[data-testid="msg-bubble-${id}"]`).find('[data-testid="hitl-receipt"]').text()
    // 徽标字形前缀保留（✓ / ✗ / !，均文本字形）
    expect(cardText('a-expired')).toContain('! 已超时')
    expect(cardText('a-expired')).not.toContain('已过期')
    // 去超时裁决后不再有「本地作废」变体；历史 expired 段一律「已超时」（F6 口径不变）
    expect(cardText('a-expired-local')).toContain('! 已超时')
    expect(cardText('a-expired-local')).not.toContain('作废')
    // 门卡没有「已取消」（K2）：cancelled 归未批准收尾 —— 已取消字样零残留
    expect(cardText('a-cancelled')).toContain('! 已超时')
    expect(cardText('a-cancelled')).not.toContain('已取消')
    expect(cardText('a-rejected')).toContain('✗ 已拒绝')
    // 执行失败 = 已批准之后工具失败（K3）：复合终态徽标
    expect(cardText('a-failed')).toContain('! 已批准 · 执行失败')
    expect(cardText('a-approved')).toContain('✓ 已批准')
    // 旧字样零残留（全 DOM）
    for (const old of ['已不执行', '不批准', '已确认']) {
      expect(wrapper.text()).not.toContain(old)
    }
    // 失败原因单独一行常驻卡面（K3）
    expect(wrapper.find('[data-testid="ro-reason-e5"]').text()).toContain('发布失败：目标库不可写')
    // r15：六态一律默认折叠（回合已结束），卡头摘要非空可辨识
    for (const [prefix, id, keyword] of [
      ['e1', 'a-expired', '已超时'],
      ['e4', 'a-rejected', '已拒绝'],
      ['e5', 'a-failed', '已批准 · 执行失败'],
      ['e3', 'a-cancelled', '已超时']
    ] as const) {
      const card = wrapper.find(`[data-testid="msg-bubble-${id}"]`)
      expect(card.find(`[data-testid="ro-body-${prefix}"]`).attributes('style') ?? '').toContain('display: none')
      expect(card.find(`[data-testid="ro-toggle-${prefix}"]`).attributes('aria-expanded')).toBe('false')
      // 状态文案只在右标签（摘要不再重复状态前缀）；标题恒有，摘要按内容可有可无
      expect(card.find(`[data-testid="ro-badge-${prefix}"]`).text()).toContain(keyword)
      expect(card.find(`[data-testid="card-title-${prefix}"]`).text().trim()).not.toBe('')
      expect(card.find(`[data-testid="ro-summary-${prefix}"]`).text()).not.toContain(keyword)
    }
    const approvedBody = wrapper.find('[data-testid="msg-bubble-a-approved"]').find('[data-testid="ro-body-e6"]')
    expect(approvedBody.attributes('style') ?? '').toContain('display: none')
    // 卡头恒可聚焦（aria-expanded；原生 button 自带 Enter/Space）
    for (const prefix of ['e1', 'e2', 'e3', 'e4', 'e5', 'e6']) {
      const head = wrapper.find(`[data-testid="ro-toggle-${prefix}"]`)
      expect(head.element.tagName).toBe('BUTTON')
      expect(head.attributes('aria-expanded')).toBeDefined()
      expect(head.attributes('disabled')).toBeUndefined()
    }
    wrapper.unmount()
  })

  it('R-166B RC-1：GF 只读态摘要取 submittedValues（字段 label + 可读值），不出现表单 schema 键', async () => {
    const withValues = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'g1', name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded', requiresAction: false,
        args: JSON.stringify({
          form: {
            scenario: 'export_query',
            title: '导出参数',
            fields: [
              { key: 'region', label: '导出范围', type: 'enum', required: true },
              { key: 'compress', label: '是否压缩', type: 'boolean', required: false },
              { key: 'tags', label: '标签', type: 'multi_select', required: false },
              { key: 'note', label: '备注', type: 'text', required: false }
            ]
          }
        }),
        submittedValues: { region: 'HB', compress: true, tags: ['A', 'B'], note: '备注文本' }
      }
    ])
    withValues.id = 'a-gf-r166b'
    // 无本地提交值（他端提交/超时收口）⇒ 降级为「表单已提交」，仍不得回落到 schema 键
    const noValues = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'g2', name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded', requiresAction: false,
        args: JSON.stringify({ form: { scenario: 'export_query', fields: [{ key: 'region', label: '导出范围', type: 'enum', required: true }] } })
      }
    ])
    noValues.id = 'a-gf-r166b-empty'

    const wrapper = await mountPanel([withValues, noValues])
    const receipts = wrapper.findAll('[data-testid="hitl-receipt"]')
    expect(receipts).toHaveLength(2)

    // 前三项「字段label 值」：布尔→是/否、数组→顿号列表，尾部省略号示意还有更多项
    const summary = receipts[0]!.find('[data-testid="ro-summary-g1"]').text()
    expect(summary).toBe('导出范围 HB，是否压缩 是，标签 A、B…')
    // 任何路径不得把表单定义（scenario / title / fields / form）当摘要内容
    for (const key of ['scenario', 'title', 'fields', 'form', 'enum', 'multi_select']) {
      expect(summary).not.toContain(key)
    }
    expect(summary).not.toContain('=')

    // 无本地提交值（他端提交/超时收口）⇒ 摘要为空、状态只由右标签「已提交」承担；卡体仍为原样只读（可展开）
    expect(receipts[1]!.find('[data-testid="ro-badge-g2"]').text()).toBe('✓ 已提交')
    expect(receipts[1]!.find('[data-testid="ro-summary-g2"]').text()).toBe('')
    expect(wrapper.find('[data-testid="ro-toggle-g2"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="ro-body-g2"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('2A：徽标系决策态——approve 即显「已批准」；失败落复合终态；缺省回退 status', async () => {
    const running = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'r1', name: 'start_publish', kind2: 'CONFIRM', status: 'running', args: '{"taskId":3}', requiresAction: false, decision: 'approved', decidedAt: 1_700_000_000_000 }
    ])
    running.id = 'a-running'
    const failedAfterApprove = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'f1', name: 'start_publish', kind2: 'CONFIRM', status: 'failed', args: '{"taskId":3}', requiresAction: false, decision: 'approved' }
    ])
    failedAfterApprove.id = 'a-failed'
    const rejected = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'x1', name: 'start_publish', kind2: 'CONFIRM', status: 'rejected', args: '{"taskId":3}', requiresAction: false, decision: 'rejected', decidedAt: 1_700_000_000_000 }
    ])
    rejected.id = 'a-rejected'
    const legacyExpired = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'l1', name: 'start_export', kind2: 'CONFIRM', status: 'expired', args: '{"taskId":9}', requiresAction: false }
    ])
    legacyExpired.id = 'a-legacy'

    const wrapper = await mountPanel([running, failedAfterApprove, rejected, legacyExpired])
    const cardText = (id: string): string => wrapper.find(`[data-testid="msg-bubble-${id}"]`).find('[data-testid="hitl-receipt"]').text()
    // approve 即显「已批准」——running 期间不等 succeeded
    expect(cardText('a-running')).toContain('✓ 已批准')
    // 已批准后执行失败：复合终态徽标（K3）
    expect(cardText('a-failed')).toContain('! 已批准 · 执行失败')
    expect(cardText('a-rejected')).toContain('✗ 已拒绝')
    // decision 缺省（历史/超时）回退 status 派生（`! 已超时`，不带「作废」）
    expect(cardText('a-legacy')).toContain('! 已超时')
    expect(cardText('a-legacy')).not.toContain('作废')
    wrapper.unmount()
  })

  it('#12→S6：参数区 = 只读字段表（label-value 行 + 160px 内滚）；零 JSON 字符、零渐隐钮', async () => {
    const msg = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'export.start', kind2: 'BACKEND', status: 'succeeded', args: '{"a":1,"b":{"c":2},"nested":{"deep":{"x":1}}}', result: '结果', requiresAction: false }
    ])
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-1:c1"]').trigger('click')
    const rows = wrapper.find('[data-testid="args-rows-c1"]')
    expect(rows.exists()).toBe(true)
    expect(rows.classes()).toEqual(expect.arrayContaining(['max-h-[min(280px,35vh)]', 'overflow-y-auto', 'bg-[#f6f7f9]', 'rounded-[8px]']))
    const text = rows.text()
    // 键值行（非 JSON）：键名 + 可读化值；对象值只报「（复杂值）」
    expect(text).toContain('a')
    expect(text).toContain('1')
    expect(text).toContain('b')
    expect(text).toContain('（复杂值）')
    expect(text).not.toContain('{')
    expect(text).not.toContain('"')
    // CollapsibleText 全族 testid / 渐隐 mask / 显示更多钮 —— 全稿零残留
    for (const id of ['args-more-c1', 'reasoning-more-a-1:r1', 'receipt-more-c1']) {
      expect(wrapper.find(`[data-testid="${id}"]`).exists()).toBe(false)
    }
    expect(wrapper.html()).not.toContain('mask-image')
    expect(wrapper.text()).not.toContain('显示更多')
    wrapper.unmount()
  })

  it('#12→S6：args 非 JSON（历史脏数据）⇒ 不渲参数区、不露 raw 文本（红线：任何路径不展示原始 JSON）', async () => {
    const dirty = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'j1', name: 'probe.tool', kind2: 'BACKEND', status: 'succeeded', args: 'not-json{', result: '结果', requiresAction: false }
    ])
    const wrapper = await mountPanel([dirty])
    await wrapper.find('[data-testid="block-toggle-a-1:j1"]').trigger('click')
    expect(wrapper.find('[data-testid="args-rows-j1"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('not-json{')
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

describe('卡片通道统一：三类型卡头（左裸 SVG / 中标题摘要 / 右状态标签）', () => {
  const GF_FORM: FormSpec = {
    scenario: 'CONFIRM',
    title: '税务配置差异导出表单',
    fields: [
      { key: 'note', label: '说明', type: 'text', required: true },
      { key: 'mode', label: '模式', type: 'enum', required: true, options: [{ value: 'fast', label: '快速' }] }
    ]
  }
  const GF_ARGS = JSON.stringify({ form: GF_FORM })

  function mk(id: string, overrides: Partial<ToolPart> = {}): ToolPart {
    return {
      kind: 'tool', seg: 0, toolCallId: id, name: 'start_export', kind2: 'BACKEND',
      status: 'succeeded', args: '{"taskId":1}', result: 'ok', requiresAction: false,
      ...overrides
    } as ToolPart
  }

  it('三类型跨状态：左 SVG outerHTML 完全一致且中性；类型图标互不相同', async () => {
    const toolIds = ['tool-pending', 'tool-running', 'tool-ok', 'tool-bad', 'tool-rejected', 'tool-blocked', 'tool-expired', 'tool-cancelled', 'tool-unknown']
    const toolMsg = buildMessage([
      mk('tool-pending', { status: 'pending', result: '' }),
      mk('tool-running', { status: 'running', result: '' }),
      mk('tool-ok'),
      mk('tool-bad', { status: 'failed', result: '', reason: '失败' }),
      mk('tool-rejected', { status: 'rejected', result: '' }),
      mk('tool-blocked', { status: 'blocked', result: '' }),
      mk('tool-expired', { status: 'expired', result: '' }),
      mk('tool-cancelled', { status: 'cancelled', result: '' }),
      mk('tool-unknown', { status: 'unknown', result: '' })
    ])
    toolMsg.id = 'm-tool'

    const hitlIds = ['hitl-pending', 'hitl-ok', 'hitl-rejected', 'hitl-expired', 'hitl-failed', 'hitl-blocked', 'hitl-unknown']
    const hitlMsg = buildMessage([
      mk('hitl-pending', { kind2: 'CONFIRM', name: 'start_publish', status: 'pending', args: '{"taskId":9}', result: '', requiresAction: true }),
      mk('hitl-ok', { kind2: 'CONFIRM', name: 'start_publish', status: 'succeeded', decision: 'approved' }),
      mk('hitl-rejected', { kind2: 'CONFIRM', name: 'start_publish', status: 'rejected', decision: 'rejected' }),
      mk('hitl-expired', { kind2: 'CONFIRM', name: 'start_publish', status: 'expired' }),
      mk('hitl-failed', { kind2: 'CONFIRM', name: 'start_publish', status: 'failed', decision: 'approved' }),
      mk('hitl-blocked', { kind2: 'CONFIRM', name: 'start_publish', status: 'blocked', result: '' }),
      mk('hitl-unknown', { kind2: 'CONFIRM', name: 'start_publish', status: 'unknown', result: '' })
    ])
    hitlMsg.id = 'm-hitl'

    const formIds = ['form-pending', 'form-ok', 'form-cancelled', 'form-blocked', 'form-unknown']
    const formMsg = buildMessage([
      mk('form-pending', { name: 'generative_form', kind2: 'FRONTEND', status: 'pending', args: GF_ARGS, result: '', requiresAction: true }),
      mk('form-ok', { name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded', args: GF_ARGS, result: '', submittedValues: { note: '已交' } }),
      mk('form-cancelled', { name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', args: GF_ARGS, result: '' }),
      mk('form-blocked', { name: 'generative_form', kind2: 'FRONTEND', status: 'blocked', args: GF_ARGS, result: '' }),
      mk('form-unknown', { name: 'generative_form', kind2: 'FRONTEND', status: 'unknown', args: GF_ARGS, result: '' })
    ])
    formMsg.id = 'm-form'

    const wrapper = await mountPanel([toolMsg, hitlMsg, formMsg])
    const iconOf = (id: string) => wrapper.find(`[data-testid="card-icon-${id}"]`)
    const svgOf = (id: string) => iconOf(id).find('svg')
    const outer = (ids: string[]): string[] => ids.map((id) => svgOf(id).element.outerHTML)

    for (const ids of [toolIds, hitlIds, formIds]) {
      const htmls = outer(ids)
      // 整生命周期（含 pending / 六态 / 负控制）左 SVG 完全一致
      expect(new Set(htmls).size).toBe(1)
      expect(htmls[0]).toContain('aria-hidden="true"')
      expect(htmls[0]).toContain('stroke="#57606a"')
      // 中性：SVG 内不得出现任何状态色族
      expect(htmls[0]).not.toMatch(/#2563eb|#1a7f45|#c0392b|#b45309/)
      for (const id of ids) {
        expect(iconOf(id).classes()).toContain('text-[#57606a]')
        expect(iconOf(id).classes()).not.toContain('animate-spin')
        expect(iconOf(id).find('svg').exists()).toBe(true)
      }
    }
    // 三种通道图标互不相同（扳手 / 圆圈问号 / 清单）
    expect(new Set([
      svgOf('tool-ok').element.outerHTML,
      svgOf('hitl-ok').element.outerHTML,
      svgOf('form-ok').element.outerHTML
    ]).size).toBe(3)
    // HITL = Feather help-circle（G）：r10 圆圈 + 官方问号钩 + 底部圆点，旧盾牌图形不得残留
    const hitlSvg = svgOf('hitl-ok')
    expect(hitlSvg.find('circle').attributes()).toMatchObject({ cx: '12', cy: '12', r: '10' })
    expect(hitlSvg.findAll('path').map((p) => p.attributes('d'))).toEqual(['M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3'])
    expect(hitlSvg.find('line').attributes()).toMatchObject({ x1: '12', y1: '17', x2: '12.01', y2: '17' })
    expect(hitlSvg.element.outerHTML).not.toContain('M12 22s8-4 8-10V5')
    expect(iconOf('tool-ok').attributes('data-icon-type')).toBe('tool')
    expect(iconOf('hitl-ok').attributes('data-icon-type')).toBe('hitl')
    expect(iconOf('form-ok').attributes('data-icon-type')).toBe('form')
    wrapper.unmount()
  })

  it('右标签在标题区之后、chevron 之前：文案 / tone / 36.4 卡头配方', async () => {
    const toolMsg = buildMessage([
      mk('st-tool-pending', { status: 'pending', result: '' }),
      mk('st-tool-ok', { args: '{"taskId":7}', result: '已启动' }),
      mk('st-tool-bad', { status: 'failed', result: '', reason: '失败' })
    ])
    toolMsg.id = 'm-order'
    const hitlMsg = buildMessage([
      mk('st-hitl-pending', { kind2: 'CONFIRM', name: 'start_publish', status: 'pending', result: '', requiresAction: true }),
      mk('st-hitl-ok', { kind2: 'CONFIRM', name: 'start_publish', status: 'succeeded', decision: 'approved' }),
      mk('st-hitl-exp', { kind2: 'CONFIRM', name: 'start_publish', status: 'expired' }),
      mk('st-hitl-failed', { kind2: 'CONFIRM', name: 'start_publish', status: 'failed', decision: 'approved' })
    ])
    hitlMsg.id = 'm-hitl-status'
    const formMsg = buildMessage([
      mk('st-form-pending', { name: 'generative_form', kind2: 'FRONTEND', status: 'pending', args: GF_ARGS, result: '', requiresAction: true }),
      mk('st-form-ok', { name: 'generative_form', kind2: 'FRONTEND', status: 'succeeded', args: GF_ARGS, result: '', submittedValues: { note: '已交' } }),
      mk('st-form-cancel', { name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', args: GF_ARGS, result: '' })
    ])
    formMsg.id = 'm-form-status'

    const wrapper = await mountPanel([toolMsg, hitlMsg, formMsg])
    const cases: Array<{ testid: string; label: string; tone: string; state: string }> = [
      { testid: 'card-status-st-tool-pending', label: '待执行', tone: 'text-[#2563eb]', state: 'pending' },
      { testid: 'card-status-st-tool-ok', label: '已成功', tone: 'text-[#1a7f45]', state: 'succeeded' },
      { testid: 'card-status-st-tool-bad', label: '执行失败', tone: 'text-[#c0392b]', state: 'failed' },
      { testid: 'card-status-st-hitl-pending', label: '待确认', tone: 'text-[#b45309]', state: 'pending' },
      { testid: 'ro-badge-st-hitl-ok', label: '✓ 已批准', tone: 'text-[#1a7f45]', state: 'approved' },
      { testid: 'ro-badge-st-hitl-exp', label: '! 已超时', tone: 'text-[#b45309]', state: 'expired' },
      { testid: 'ro-badge-st-hitl-failed', label: '! 已批准 · 执行失败', tone: 'text-[#c0392b]', state: 'failed' },
      { testid: 'card-status-st-form-pending', label: '待填写', tone: 'text-[#2563eb]', state: 'pending' },
      { testid: 'ro-badge-st-form-ok', label: '✓ 已提交', tone: 'text-[#1a7f45]', state: 'submitted' },
      { testid: 'ro-badge-st-form-cancel', label: '✗ 已取消', tone: 'text-[#c0392b]', state: 'cancelled' }
    ]
    for (const item of cases) {
      const status = wrapper.find(`[data-testid="${item.testid}"]`)
      expect(status.exists()).toBe(true)
      expect(status.text()).toBe(item.label)
      expect(status.classes()).toContain(item.tone)
      expect(status.attributes('data-state')).toBe(item.state)
    }

    // 顺序：图标 → 标题区（含摘要）→ 状态标签 → chevron（最右）
    const head = wrapper.find('[data-testid="block-toggle-m-order:st-tool-ok"]')
    const children = Array.from(head.element.children) as HTMLElement[]
    expect(children[0]!.getAttribute('data-testid')).toBe('card-icon-st-tool-ok')
    expect(children[1]!.querySelector('[data-testid="card-title-st-tool-ok"]')).not.toBeNull()
    const statusIdx = children.findIndex((el) => el.getAttribute('data-testid') === 'card-status-st-tool-ok')
    const chevIdx = children.findIndex((el) => el.classList.contains('tchev'))
    expect(statusIdx).toBe(2)
    expect(chevIdx).toBe(3)
    // 36.4px 配方 = py 8+8 + 标题行高 12×1.7；标题/摘要中性色，状态色只在右标签
    expect(head.classes()).toEqual(expect.arrayContaining(['px-[10px]', 'py-[8px]', 'gap-[6px]', 'whitespace-nowrap']))
    const title = head.find('[data-testid="card-title-st-tool-ok"]')
    expect(title.classes()).toEqual(expect.arrayContaining(['text-[12px]', 'leading-[1.7]', 'text-[#1f2328]']))
    const summary = head.find('[data-testid="tool-summary-st-tool-ok"]')
    expect(summary.classes()).toEqual(expect.arrayContaining(['text-[11.5px]', 'leading-[1.6]', 'text-[#57606a]']))
    expect(title.classes()).not.toContain('text-[#2563eb]')
    expect(summary.classes()).not.toContain('text-[#2563eb]')
    wrapper.unmount()
  })

  it('普通 tool pending 只显示「待执行」，不出现「等待确认」；类型 chip 已退场', async () => {
    const msg = buildMessage([mk('plain-pending', { status: 'pending', args: '{"taskId":2}', result: '' })])
    msg.id = 'm-plain'
    const wrapper = await mountPanel([msg])
    const status = wrapper.find('[data-testid="card-status-plain-pending"]')
    expect(status.text()).toBe('待执行')
    expect(wrapper.text()).not.toContain('等待确认')
    for (const gone of ['前端工具', '确认门', '只读']) {
      expect(wrapper.text()).not.toContain(gone)
    }
    wrapper.unmount()
  })

  it('FRONTEND 向导工具 pending（全 10 个）：无 summaryFor 映射也不得把「原始工具名 · 等待确认」漏进卡头', async () => {
    const cases = [
      { id: 'wiz-download', raw: 'download_templates', title: '下载模板' },
      { id: 'wiz-export-select', raw: 'export.selectDefs', title: '选择配置项' },
      { id: 'wiz-export-condition', raw: 'export.setConditions', title: '设置查询条件' },
      { id: 'wiz-export-start', raw: 'export.start', title: '启动导出作业' },
      { id: 'wiz-export-editor', raw: 'export.openEditor', title: '打开在线编辑器' },
      { id: 'wiz-import-select', raw: 'import.selectDefs', title: '选择配置项' },
      { id: 'wiz-import-upload', raw: 'import.upload', title: '上传文件' },
      { id: 'wiz-import-precheck', raw: 'import.startPrecheck', title: '启动预检查作业' },
      { id: 'wiz-import-start', raw: 'import.startImport', title: '启动导入作业' },
      { id: 'wiz-import-publish', raw: 'import.startPublish', title: '启动发布作业' }
    ]
    // 生产 frontend_tool_request 的挂起段 requiresAction=true（不是普通已执行工具段）
    const msg = buildMessage(cases.map((item) =>
      mk(item.id, { name: item.raw, kind2: 'FRONTEND', status: 'pending', args: '{}', result: '', requiresAction: true })
    ))
    msg.id = 'm-wizard'
    const wrapper = await mountPanel([msg])
    for (const item of cases) {
      // 右标签 = 普通工具 pending「待执行」（不是 HITL 的「等待确认」）
      expect(wrapper.find(`[data-testid="card-status-${item.id}"]`).text()).toBe('待执行')
      expect(wrapper.find(`[data-testid="card-title-${item.id}"]`).text()).toBe(item.title)
      // 兜底摘要剥掉原名后只剩状态词 ⇒ 摘要位为空（状态由右标签承担）
      expect(wrapper.find(`[data-testid="tool-summary-${item.id}"]`).text()).toBe('')
      const head = wrapper.find(`[data-testid="block-toggle-m-wizard:${item.id}"]`)
      expect(head.text()).not.toContain('等待确认')
      expect(head.text()).not.toContain(item.raw)
    }
    expect(wrapper.text()).not.toContain('等待确认')
    wrapper.unmount()
  })

  it('GF 一卡一头：AiPanel 待填写 GF 传 showHeader=false；独立 FormRenderer 默认头保留', async () => {
    const msg = buildMessage(
      [mk('gf1', { name: 'generative_form', kind2: 'FRONTEND', status: 'pending', args: GF_ARGS, result: '', requiresAction: true, timeoutSeconds: 120 })],
      { toolCallId: 'gf1', name: 'generative_form', kind: 'FRONTEND', args: { form: GF_FORM }, rawArgs: GF_ARGS, timeoutSeconds: 120, status: 'pending' }
    )
    msg.id = 'a-gf-head'
    const store = useAiStore()
    store.activeForm = { toolCallId: 'gf1', runId: 'run-1', form: GF_FORM, status: 'filling', error: null, values: {}, fieldErrors: [] }

    const wrapper = await mountPanel([msg], { realForm: true })
    // 卡头 = 唯一标题（FormRenderer 默认头被 showHeader=false 抑制）
    expect(wrapper.find('[data-testid="card-title-gf1"]').text()).toBe('税务配置差异导出表单')
    expect(wrapper.text().split('税务配置差异导出表单').length - 1).toBe(1)
    expect(wrapper.text()).not.toContain('▤')
    expect(wrapper.find('[data-testid="gf-fields"]').exists()).toBe(true)
    // 待填写 GF：状态右标签 + 强制展开 + 无 chevron
    expect(wrapper.find('[data-testid="card-status-gf1"]').text()).toBe('待填写')
    expect(wrapper.find('[data-testid="block-toggle-a-gf-head:gf1"] .tchev').exists()).toBe(false)
    expect(wrapper.find('[data-testid="block-body-a-gf-head:gf1"]').attributes('style') ?? '').not.toContain('display: none')
    wrapper.unmount()

    // 嵌入态单框（demo 场景四）：FormRenderer 自身描边/底色/外边距退役，只剩字段与操作行
    const embeddedRoot = wrapper.findComponent(FormRenderer).element as HTMLElement
    expect(embeddedRoot.className.split(' ')).toEqual(['min-w-0'])

    // 独立 FormRenderer：默认 showHeader=true，行为不变
    const standalone = mount(FormRenderer, {
      props: { schema: GF_FORM, submitting: false, error: null }
    })
    expect(standalone.text()).toContain('▤')
    expect(standalone.text()).toContain('税务配置差异导出表单')
    expect(standalone.text()).toContain('信息确认')
    standalone.unmount()

    const noHeader = mount(FormRenderer, {
      props: { schema: GF_FORM, submitting: false, error: null, showHeader: false }
    })
    expect(noHeader.text()).not.toContain('▤')
    expect(noHeader.text()).not.toContain('税务配置差异导出表单')
    expect(noHeader.find('[data-testid="gf-fields"]').exists()).toBe(true)
    noHeader.unmount()
  })

  it('只读卡头可访问名不被固定 aria-label 遮蔽：可见标题+状态即按钮文本', async () => {
    const msg = buildMessage([
      mk('a11y-ok', { kind2: 'CONFIRM', name: 'start_publish', status: 'succeeded', decision: 'approved' }),
      mk('a11y-cancel', { name: 'generative_form', kind2: 'FRONTEND', status: 'cancelled', args: GF_ARGS, result: '' })
    ])
    msg.id = 'm-a11y'
    const wrapper = await mountPanel([msg])
    const cases = [
      { id: 'a11y-ok', title: '启动发布作业', status: '✓ 已批准' },
      { id: 'a11y-cancel', title: '税务配置差异导出表单', status: '✗ 已取消' }
    ]
    for (const item of cases) {
      const head = wrapper.find(`[data-testid="ro-toggle-${item.id}"]`)
      expect(head.exists()).toBe(true)
      // 无固定 aria-label ⇒ 可访问名由按钮内可见文本（标题 + 右状态标签）承担
      expect(head.attributes('aria-label')).toBeUndefined()
      // 既有无障碍/交互锚保留
      expect(head.attributes('aria-expanded')).toBeDefined()
      expect(head.attributes('disabled')).toBeUndefined()
      expect(head.text()).toContain(item.title)
      expect(head.text()).toContain(item.status)
    }
    wrapper.unmount()
  })

  it('待决 HITL/GF 强制展开无 chevron；已决 HITL 可折叠且 chevron 最右', async () => {
    const msg = buildMessage([
      mk('fold-ok', { kind2: 'CONFIRM', name: 'start_publish', status: 'succeeded', decision: 'approved' }),
      mk('fold-gate', { kind2: 'CONFIRM', name: 'start_publish', status: 'pending', result: '', requiresAction: true }),
      mk('fold-form', { name: 'generative_form', kind2: 'FRONTEND', status: 'pending', args: GF_ARGS, result: '', requiresAction: true })
    ])
    msg.id = 'm-fold'
    const wrapper = await mountPanel([msg])

    const doneHead = wrapper.find('[data-testid="ro-toggle-fold-ok"]')
    expect(doneHead.find('.tchev').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ro-body-fold-ok"]').attributes('style') ?? '').toContain('display: none')
    await doneHead.trigger('click')
    expect(wrapper.find('[data-testid="ro-body-fold-ok"]').attributes('style') ?? '').not.toContain('display: none')

    for (const [id, bodyId] of [['fold-gate', 'block-body-m-fold:fold-gate'], ['fold-form', 'block-body-m-fold:fold-form']]) {
      const head = wrapper.find(`[data-testid="block-toggle-m-fold:${id}"]`)
      expect(head.find('.tchev').exists()).toBe(false)
      expect(head.attributes('disabled')).toBeDefined()
      expect(wrapper.find(`[data-testid="${bodyId}"]`).attributes('style') ?? '').not.toContain('display: none')
    }
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

  it('M2/M1→S6：loadHistory 回读决策元数据 ⇒ 原卡只读态（卡头恒显行，不展开即见）+ 三字段落段', async () => {
    const store = historyWith(
      { 'tc-1': { status: 'EXECUTED', kind: 'CONFIRM', decision: 'approved', decidedAt: 1_700_000_000_000 } },
      [{ id: 'tc-1', name: 'start_export', arguments: '{"taskId":3}' }]
    )
    await store.loadHistory()
    const part = store.messages[0]!.parts.find((p) => p.kind === 'tool' && p.toolCallId === 'tc-1') as ToolPart
    expect(part).toMatchObject({ decision: 'approved', decidedAt: 1_700_000_000_000, kind2: 'CONFIRM', status: 'succeeded' })

    const wrapper = await mountPanel(store.messages)
    // 原卡只读态：卡头恒显行（徽标 + 摘要）即可见 —— R-160 兼容条件；**卡面不渲染 decidedAt**（M1）
    const receipt = wrapper.find('[data-testid="hitl-receipt"]')
    expect(receipt.exists()).toBe(true)
    expect(receipt.text()).toContain('✓ 已批准')
    expect(receipt.text()).not.toMatch(/\d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2}/)
    expect(receipt.text()).not.toMatch(/\d{2}:\d{2}/)
    expect(wrapper.find('[data-testid="ro-summary-tc-1"]').exists()).toBe(true)
    // 成功终态默认折叠（历史卡回合已结束）
    expect(wrapper.find('[data-testid="ro-body-tc-1"]').attributes('style') ?? '').toContain('display: none')
    // 回执独立块退役：DOM 里只有这一张卡（不再有 tool-card + 紧随的第二张回执卡）
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="tool-card-tc-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('M1：submittedValues 限 FRONTEND 回读 —— GF 提交值可回溯；坏 JSON 兜底不伪造；CONFIRM 不读', async () => {
    const gfArgs = JSON.stringify({
      form: {
        scenario: 'export_query',
        title: '导出参数',
        fields: [{ key: 'region', label: '导出范围', type: 'enum', required: true, options: [{ value: 'HB', label: '华北' }] }]
      }
    })
    const store = historyWith(
      {
        'g1': { status: 'FRONTEND_RESULT', kind: 'FRONTEND', decision: 'approved', submittedValues: '{"region":"HB"}' },
        'g2': { status: 'FRONTEND_RESULT', kind: 'FRONTEND', submittedValues: 'not-json{' },
        'c1': { status: 'EXECUTED', kind: 'CONFIRM', decision: 'approved', submittedValues: '{"taskId":3}' }
      },
      [
        { id: 'g1', name: 'generative_form', arguments: gfArgs },
        { id: 'g2', name: 'generative_form', arguments: gfArgs },
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

    const wrapper = await mountPanel(store.messages, { realForm: true })
    expect(wrapper.findAll('[data-testid="hitl-receipt"]')).toHaveLength(3)
    // g1：有提交值 ⇒ 同一张表单值回填（只读 select 选中 HB）
    await wrapper.find('[data-testid="ro-toggle-g1"]').trigger('click')
    const g1Body = wrapper.find('[data-testid="ro-body-g1"]')
    expect((g1Body.find('select').element as HTMLSelectElement).value).toBe('HB')
    expect(g1Body.find('select').attributes('disabled')).toBeDefined()
    // g2：提交值解析失败 ⇒ 仍渲同一张表单（回落初始态），不伪造提交值
    await wrapper.find('[data-testid="ro-toggle-g2"]').trigger('click')
    const g2Body = wrapper.find('[data-testid="ro-body-g2"]')
    expect(g2Body.find('[data-testid="gf-fields"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ro-toggle-g2"]').attributes('disabled')).toBeUndefined()
    // c1：CONFIRM 只读体渲 args（不读 submittedValues）；成功终态默认折叠 ⇒ 点击展开
    await wrapper.find('[data-testid="ro-toggle-c1"]').trigger('click')
    expect(wrapper.find('[data-testid="ro-body-c1"]').text()).toContain('taskId')
    expect(wrapper.find('[data-testid="ro-body-c1"]').text()).not.toContain('HB')
    wrapper.unmount()
  })

  // 2026-10-20 去超时裁决（原 R-166 口径① 三例改写）：
  // 快照里的 expiresAt/atMs 仍照原样透出（对账口径不丢），但**不再驱动任何倒计时** ——
  // 卡面既无倒计时读数、也无「等待人工确认」静态兜底；心跳帧到达同样不改卡面。
  it('快照透出 expiresAt/atMs（对账口径不丢），卡面无倒计时读数 / 无静态兜底', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    const atMs = Date.now()
    const expiresAt = atMs + 60_000
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', req: true, expiresAt, atMs }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    await store.restoreSuspendedSnapshot()
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1') as ToolPart
    expect(part).toMatchObject({ requiresAction: true, status: 'pending', expiresAt, atMs })
    expect(store.pendingConfirm).toMatchObject({ toolCallId: 'c1', expiresAt, atMs })

    const wrapper = await mountPanel(store.messages)
    const card = wrapper.find('[data-testid="pending-card-c1"]')
    expect(card.exists()).toBe(true)
    expect(card.text()).not.toContain('剩余确认时间')
    expect(card.text()).not.toContain('等待人工确认')
    expect(card.find('[data-testid="hitl-gate"] svg').exists()).toBe(false)
    wrapper.unmount()
  })

  it('快照恢复 + 挂起期心跳：心跳不写任何卡面状态（DOM 逐帧不变）', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', req: true, expiresAt: 1_700_000_240_000, atMs: 1_700_000_000_000 }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    await store.restoreSuspendedSnapshot()

    const wrapper = await mountPanel(store.messages)
    const before = wrapper.find('[data-testid="pending-card-c1"]').html()
    store.handleFrame({ type: 'heartbeat', runId: 'run-1', toolCallId: 'c1', suspendKind: 'confirm', timeoutSeconds: 240, waitedSeconds: 60, heartbeatSeq: 1 })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="pending-card-c1"]').html()).toBe(before)
    expect(wrapper.find('[data-testid="pending-card-c1"]').text()).not.toContain('剩余确认时间')
    wrapper.unmount()
  })

  it('旧数据无 expiresAt ⇒ 卡面同样无倒计时（与有口径时一字不差）', async () => {
    const store = useAiStore()
    store.historyLoaded = true
    api.runsCurrent.mockResolvedValue({
      found: true, runId: 'run-1', snapshotStatus: 'SUSPENDED',
      pendingEntries: [{ toolCallId: 'c1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_export', arguments: '{"taskId":1}', req: true }],
      unresolvedExternal: ['c1'], awaitingExternal: true, archiveMaxSeq: 0
    })
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    await store.restoreSuspendedSnapshot()
    const part = store.messages.flatMap((m) => m.parts).find((p) => p.kind === 'tool' && p.toolCallId === 'c1') as ToolPart
    expect(part.requiresAction).toBe(true)
    expect(part.expiresAt).toBeUndefined() // 旧数据：不写入口径

    const wrapper = await mountPanel(store.messages)
    const card = wrapper.find('[data-testid="pending-card-c1"]')
    expect(card.text()).not.toContain('剩余确认时间')
    expect(card.text()).not.toContain('等待人工确认')
    wrapper.unmount()
  })

  it('M4：方案 A 容器层一刀切 —— 气泡 overflow:hidden + min-w-0；参数行容器 anywhere 断行', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'export.start', kind2: 'BACKEND', status: 'succeeded', args: '{"long":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}', requiresAction: false }
      ])
    ])
    const bubble = wrapper.find('[data-testid="msg-bubble-a-1"]').element as HTMLElement
    expect(bubble.className).toContain('overflow-hidden')
    expect(bubble.className).toContain('min-w-0')
    await wrapper.find('[data-testid="block-toggle-a-1:c1"]').trigger('click')
    // 字段表的值列 = min-w-0 + break-words（长串在容器层可断行、不越界）
    const row = wrapper.find('[data-testid="args-rows-c1"] > div')
    const value = row.element.children[1] as HTMLElement
    expect(value.className).toContain('min-w-0')
    expect(value.className).toContain('break-words')
    wrapper.unmount()
  })

  it('M5→S6：CollapsibleText 组件已退役（文件删除 + 源码零引用 + 全树无 show-more/mask 残留）', async () => {
    const { existsSync, readFileSync } = await import('node:fs')
    // 组件文件已删除（仓库里不再存在）
    expect(existsSync('src/components/AiPanel/CollapsibleText.vue')).toBe(false)
    // 渲染层零引用：既无导入语句也无标签使用（注释里对历史形态的说明不算引用）
    const source = readFileSync('src/components/AiPanel/AiPanel.vue', 'utf8')
    expect(source).not.toContain('import CollapsibleText')
    expect(source).not.toContain('<CollapsibleText')
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'reasoning', seg: 0, text: '想很久'.repeat(20), streaming: false },
        { kind: 'tool', seg: 1, toolCallId: 'c1', name: 'export.start', kind2: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: '结果', requiresAction: false }
      ])
    ])
    const html = wrapper.html()
    expect(html).not.toContain('mask-image')
    // 「显示更多」类展开钮 0 枚（只查按钮文案，模板注释里的历史说明不算）
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('显示更多')
    expect(wrapper.find('[data-testid^="args-more-"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid^="reasoning-more-"]').exists()).toBe(false)
    wrapper.unmount()
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

  /**
   * L-86（真机 E2E 批③ MDXY 域观察）：markdown-it 把 `~~x~~` 渲染成 `<s>`，
   * 而白名单只放行 `del` ⇒ 消毒阶段把元素剥掉、只留文字（页面静默降级成纯文本、`.md-body`
   * 里 `s` 计数 0）。判据 = **元素保留**且标记不外泄成文字。
   */
  it('L-86：~~删除线~~ 消毒后仍是删除线（s 元素保留，不降级成纯文本）', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown('这句 ~~划掉~~ 了', false)
    expect(html).toContain('划掉')
    expect(html).toMatch(/<s>划掉<\/s>/)
    expect(html).not.toContain('~~')
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

  it('T1 降级：>10k 长文结构照渲但无高亮/mermaid（DEGRADE_AT_CHARS 阈值）+ 代码块仍限高 200px', async () => {
    const { renderMarkdown, DEGRADE_AT_CHARS } = await import('@/utils/markdown')
    expect(DEGRADE_AT_CHARS).toBe(10_000)
    const long = `# 标题\n\n${'x'.repeat(DEGRADE_AT_CHARS + 100)}`
    const { html } = renderMarkdown(long, false)
    expect(html).toMatch(/<h1 class="[^"]*">/) // 结构照渲（R-165 S5c 起 h1 带渲染层注入的排版类）
    expect(html).not.toContain('language-') // 高亮降级（阈值实例无 highlight）
    expect(html).not.toContain('hljs')
    // S6 批1（长内容审计清零项「degraded 期代码块限高失效」）：降级分支不包 `figure.code`
    // （wrapFenceFigures 只在非降级路径跑）—— 限高类落在 pre 自身，两条路径同口径 200px。
    const degradedFence = renderMarkdown('```js\nconst a = 1\n```\n\n' + 'x'.repeat(DEGRADE_AT_CHARS), false)
    const pre = /<pre class="([^"]*)"/.exec(degradedFence.html)
    expect(pre).not.toBeNull()
    expect(pre![1]).toContain('max-h-[min(280px,35vh)]')
    expect(pre![1]).toContain('overflow-auto')
    expect(degradedFence.html).not.toContain('figure') // 降级路径无图容器（限高靠 pre 自身）
    // 长行内 code 不再 nowrap 吞内容（可折行）
    const inline = renderMarkdown(`正文 \`${'y'.repeat(200)}\` 结束`, false)
    const code = /<code class="([^"]*)"/.exec(inline.html)
    expect(code).not.toBeNull()
    expect(code![1]).not.toContain('whitespace-nowrap')
    expect(code![1]).toContain('break-words')
    expect(code![1]).toContain('[overflow-wrap:anywhere]')
  })

  it('T0/T2：工具摘要映射 —— start_export 按 args 出「（任务 #7）」（问题1 起卡头按前缀剥名），未知工具兜底 名+状态', async () => {
    const { toolSummary } = await import('@/utils/tool-summaries')
    const mk = (name: string, args: string, status: ToolPart['status']): ToolPart =>
      ({ kind: 'tool', seg: 0, toolCallId: 't1', name, kind2: 'BACKEND', status, args, requiresAction: false }) as ToolPart
    // `toolSummary` 仍是「工具名 + 参数信息」的完整摘要（前一层的口径不变）
    expect(toolSummary(mk('start_export', '{"taskId":7}', 'running'))).toBe('启动导出作业（任务 #7）')
    expect(toolSummary(mk('create_task', '{"type":"EXPORT","title":"月报"}', 'running'))).toBe('创建导出任务「月报」')
    expect(toolSummary(mk('mystery_tool', '{}', 'failed'))).toBe('mystery_tool · 失败')
    const wrapper = await mountPanel([
      buildMessage([{ kind: 'tool', seg: 0, toolCallId: 's1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":7}', timeoutSeconds: 120, requiresAction: true }])
    ])
    // 卡头显示层：工具名只在 mono 槽一次，摘要位只剩参数信息（去重判据 全等 → 前缀）
    expect(wrapper.find('[data-testid="tool-summary-s1"]').text()).toBe('（任务 #7）')
    wrapper.unmount()
  })

  it('T2：9 态工具卡状态 tag 全出（待执行/调用中/已成功/执行失败/已拒绝/超出当前范围/会话已失效/已取消/结局未知）', async () => {
    const parts: ToolPart[] = [
      { kind: 'tool', seg: 0, toolCallId: 't1', name: 'probe.tool', kind2: 'BACKEND', status: 'pending', args: '{}', requiresAction: true },
      { kind: 'tool', seg: 1, toolCallId: 't2', name: 'probe.tool', kind2: 'BACKEND', status: 'running', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 2, toolCallId: 't3', name: 'probe.tool', kind2: 'BACKEND', status: 'succeeded', args: '{"a":1}', result: '成功了', requiresAction: false },
      { kind: 'tool', seg: 3, toolCallId: 't4', name: 'probe.tool', kind2: 'BACKEND', status: 'failed', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 4, toolCallId: 't5', name: 'probe.tool', kind2: 'BACKEND', status: 'rejected', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 5, toolCallId: 't6', name: 'probe.tool', kind2: 'BACKEND', status: 'blocked', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 6, toolCallId: 't7', name: 'probe.tool', kind2: 'BACKEND', status: 'expired', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 7, toolCallId: 't8', name: 'probe.tool', kind2: 'BACKEND', status: 'cancelled', args: '{}', requiresAction: false },
      { kind: 'tool', seg: 8, toolCallId: 't9', name: 'probe.tool', kind2: 'BACKEND', status: 'unknown', args: '{}', requiresAction: false }
    ]
    const wrapper = await mountPanel([buildMessage(parts)])
    // 普通工具 pending 文案 = 「待执行」（「等待确认」只属 HITL）；其余八态文案保持
    for (const label of ['待执行', '调用中', '已成功', '执行失败', '已拒绝', '超出当前范围', '会话已失效', '已取消', '结局未知']) {
      expect(wrapper.text()).toContain(label)
    }
    // 展开体标签（S6：参数改 label-value 字段表 / 输出保留）—— 裸 JSON 墙消除的结构性锁
    await wrapper.find('[data-testid="block-toggle-a-1:t3"]').trigger('click')
    const bodyText = wrapper.find('[data-testid="block-body-a-1:t3"]').text()
    expect(bodyText).toContain('参数')
    expect(bodyText).toContain('输出')
    expect(bodyText).not.toContain('输入')
    // R-165 S1：状态胶囊改自绘 span chip（EP el-tag 退场，el-tag 桩 span.tag 全 DOM 零命中）
    expect(wrapper.findAll('.tag')).toHaveLength(0)
    const chipOf = (id: string) =>
      wrapper.find(`[data-testid="block-toggle-a-1:${id}"]`).findAll('span')
        .filter((s) => s.classes().includes('text-[9.5px]'))
    // 底座（v7 .stchip + 描边）：9.5px / 圆角 5 / padding 0 5px；色族按态走（蓝=在跑·待执行，绿=成功，红=失败，灰=其余）
    expect(chipOf('t1')).toHaveLength(1)
    expect(chipOf('t3')[0]!.classes()).toEqual(expect.arrayContaining(['text-[9.5px]', 'leading-[1.7]', 'px-[5px]', 'rounded-[5px]', 'border', 'border-solid']))
    expect(chipOf('t1')[0]!.text()).toBe('待执行')
    expect(chipOf('t1')[0]!.classes()).toContain('text-[#2563eb]')
    expect(chipOf('t3')[0]!.text()).toBe('已成功')
    expect(chipOf('t3')[0]!.classes()).toContain('bg-[#edf7f0]')
    expect(chipOf('t4')[0]!.classes()).toContain('bg-[#fdf1ef]')
    // 左槽 = 类型裸 SVG，九态 outerHTML 完全一致且中性（旧状态字形/旋转环退役）
    const iconOf = (id: string) => wrapper.find(`[data-testid="card-icon-${id}"]`)
    const iconHtmls = ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8', 't9']
      .map((id) => iconOf(id).find('svg').element.outerHTML)
    expect(new Set(iconHtmls).size).toBe(1)
    expect(iconOf('t1').attributes('data-icon-type')).toBe('tool')
    expect(iconOf('t2').find('svg').attributes('stroke')).toBe('#57606a')
    expect(iconOf('t2').classes()).not.toContain('animate-spin')
    expect(iconOf('t3').text()).toBe('')
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

  it('T3（L-80）：上报失败 ⇒ 回退本地选中态 + 一条人话提示（不静默假装成功）', async () => {
    api.feedbackSubmit.mockRejectedValueOnce(new Error('network down'))
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '回答', streaming: false }])
    msg.id = 'a-fb-fail'
    const wrapper = await mountPanel([msg])
    const up = wrapper.find('[data-testid="feedback-up-a-fb-fail"]')
    await up.trigger('click')
    await flushPromises()
    // ① 回退：服务端没有记录，按钮不能停在"已选中"（否则用户以为赞过了）
    expect(up.attributes('aria-pressed')).toBe('false')
    // ② 有提示：失败必须说出来（此前 catch 静默，用户无从知道这次评价没记上）
    const notice = wrapper.find('[data-testid="notice-bar"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('反馈')
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
    expect(render).toContain('break-words [overflow-wrap:anywhere]') // 行内 code 折行（S6 批1 撤 nowrap）
    expect(render).toContain("font-['Cascadia_Mono','Cascadia_Code',Consolas,'SF_Mono',Menlo,monospace]") // --mono 逐字
    expect(render).toContain('border border-solid border-[#e3e6ea] rounded-[10px] overflow-hidden bg-white') // D2 figure.code
    expect(render).toContain('bg-[#f8f8f8] text-[#333]') // pre 底色/字色
    expect(render).toContain('max-h-[min(280px,35vh)] overflow-auto') // S6 批1：围栏 pre 自带限高（degraded 期同生效）
    expect(render).toContain('[word-break:normal] [overflow-wrap:anywhere]') // td 长 token 兜底（有意偏离 demo）
    expect(render).toContain('text-left whitespace-nowrap bg-[#f6f7f9] font-semibold text-[#57606a]') // th 单行
    // ③ 根容器的 overflow-wrap/word-break 仍未声明（长英文标识符被任意断开的元凶）
    const mdRoot = render.match(/MD_BODY_ROOT_CLASS = '([^']*)'/)?.[1] ?? ''
    expect(mdRoot).not.toContain('overflow-wrap')
    expect(mdRoot).not.toContain('word-break')
    // ④ 渲染层真的吐出来了（不是只写在常量里没人用）——逐标签断言产物
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown(
      '# 一级\n\n## 二级\n\n### 三级\n\n#### 四级\n\n##### 五级\n\n###### 六级\n\n正文 **粗** 与 `chip` 与 [a](https://e.com) 与 ![图](/api/file/1.png)\n\n- 项\n\n1. 项\n\n> 引用\n\n---\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```js\nconst a = 1\n```\n',
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
    // 问题1 偏差修正批 aj14d ③：图容器另带 `data-fence-key="f1-js"`（块序号 + 语言的稳定身份）
    expect(html).toMatch(/<figure class="code [^"]*" data-fence-key="f1-js">/)
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
        { kind: 'tool', seg: 0, toolCallId: 'i1', name: 'start_import', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: '导入作业已启动', requiresAction: false },
        { kind: 'tool', seg: 1, toolCallId: 'e1', name: 'start_export', kind2: 'BACKEND', status: 'succeeded', args: '{"taskId":7}', result: '导出作业已启动', requiresAction: false }
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
    expect(wrapper.find('[data-testid="tool-summary-e1"]').text()).toBe('（任务 #7）') // 剥掉同名前缀，只剩互补信息
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
    expect(render).toContain('md-table-bd max-h-[min(480px,60vh)] overflow-auto')
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
    // 表头行：问题1 偏差修正批 aj04（v7.8 终裁）—— flex 行 = 可横滚表头区 + 6px gutter 占位格；
    // 旧 v7.6 的 `pr-[6px]` 内边距补偿条零残留（补偿条没有下边框 ⇒ 窄表档「断头」）。
    expect(html).toMatch(/md-table-hd flex bg-\[#f6f7f9\]/)
    expect(html).toMatch(/md-table-hdscroll flex-1 min-w-0 overflow-hidden/)
    expect(html).toMatch(/md-table-gutter flex-none w-\[6px\] bg-\[#f6f7f9\] border-0 border-b border-solid border-\[#e3e6ea\]/)
    expect(html).not.toContain('pr-[6px]')
    // 表体区：限高 320 + 内滚 + gutter stable（唯一滚动容器）
    expect(html).toMatch(/md-table-bd[^"]*max-h-\[min\(480px,60vh\)\]/)
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
 * R-165 S3a：输入区（composer）+ 生成式表单卡（GF）的结构锁，以及三项收口
 * （R6 消息间距 / R7 fab 定位 / R11 error 级通知色族）。
 * 问题4 composer v2 阶段一（2026-10-19）：composer 由「固定 3 行灰底方钮 / busy 禁用虚线」
 * 改为 demo v2 形态（1 行起 / 4 行 101px 封顶 / 白底 focus-within shell / 28×28 圆形钮），
 * 并锁流式可编辑、禁用拆分、Enter（含 IME/repeat）与停止钮行为。
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

  function mountGf(overrides: Partial<{ submitting: boolean; error: string | null }> = {}) {
    return mount(FormRenderer, {
      props: { schema: GF_SCHEMA, submitting: false, error: null, ...overrides }
    })
  }

  /** 阶段一挂载：health 按用例预置（ok / 未探测 / 探测报错），loading/suspended 一并预置。 */
  async function mountPhase1(init: {
    loading?: boolean
    suspended?: boolean
    messages?: ChatMessage[]
    health?: 'ok' | 'unknown' | 'error'
  } = {}) {
    const store = useAiStore()
    if (init.health === 'unknown' || init.health === 'error') {
      // 永不 resolve：阻止 onMounted 的 probeHealth 覆盖用例预置态
      api.health.mockImplementation(() => new Promise<never>(() => {}))
      store.health = null
      store.healthError = init.health === 'error' ? 'health probe failed' : null
    } else {
      await store.probeHealth()
    }
    store.messages = init.messages ?? []
    store.historyLoaded = true
    store.loading = init.loading ?? false
    store.suspended = init.suspended ?? false
    api.chat.mockReset()
    api.chat.mockResolvedValue(undefined)
    api.history.mockResolvedValue({ sessionId: store.sessionId, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue({ found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], archiveMaxSeq: 0 })
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    return { store, wrapper }
  }

  function buildUser(id: string, text: string): ChatMessage {
    const message = buildMessage([{ kind: 'text', seg: 0, text, streaming: false }])
    message.id = id
    message.role = 'user'
    return message
  }

  /** 真实键盘事件（preventDefault 须可观察；isComposing/keyCode 用 defineProperty 兜住 jsdom 差异）。 */
  function fireEnter(
    el: HTMLTextAreaElement,
    options: { shiftKey?: boolean; repeat?: boolean; isComposing?: boolean; keyCode?: number } = {}
  ): KeyboardEvent {
    const event = new KeyboardEvent('keydown', {
      key: 'Enter',
      code: 'Enter',
      bubbles: true,
      cancelable: true,
      shiftKey: options.shiftKey ?? false,
      repeat: options.repeat ?? false
    })
    if (options.isComposing !== undefined) {
      Object.defineProperty(event, 'isComposing', { value: options.isComposing })
    }
    if (options.keyCode !== undefined) {
      Object.defineProperty(event, 'keyCode', { value: options.keyCode })
    }
    el.dispatchEvent(event)
    return event
  }

  function fireTipMouse(el: Element, type: 'mouseover' | 'mouseout', relatedTarget: EventTarget | null = null): MouseEvent {
    const event = new MouseEvent(type, { bubbles: true, cancelable: true, relatedTarget })
    el.dispatchEvent(event)
    return event
  }

  function fireTipFocus(el: Element, type: 'focusin' | 'focusout'): FocusEvent {
    const event = new FocusEvent(type, { bubbles: true })
    el.dispatchEvent(event)
    return event
  }

  function fireTipEscape(el: Element): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true })
    el.dispatchEvent(event)
    return event
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('composer v2 形态：1 行起 / max 101 / field-sizing / 白底 focus-within shell / 28×28 圆形钮 + tip（无原生 title）', async () => {
    const { wrapper } = await mountPhase1()

    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.exists()).toBe(true)
    expect(box.element.tagName).toBe('TEXTAREA')
    expect(box.attributes('rows')).toBe('1')
    expect(box.classes()).toEqual(expect.arrayContaining([
      'block', 'min-h-[40px]', 'max-h-[101px]', 'w-full', 'resize-none', 'overflow-y-auto',
      'bg-transparent', 'px-[11px]', 'pt-[11px]', 'pb-[7px]', 'text-[13px]', 'leading-[1.6]',
      '[field-sizing:content]'
    ]))
    expect(box.classes()).not.toContain('bg-[#f6f7f9]')
    expect(box.classes()).not.toContain('border-dashed')

    // 盒 shell：白底 + 整盒 focus-within 蓝环（textarea 父级）
    const shell = box.element.parentElement as HTMLElement
    for (const cls of [
      'rounded-[12px]', 'border', 'border-[#e3e6ea]', 'bg-white',
      'focus-within:border-[#2563eb]', 'focus-within:ring-2', 'focus-within:ring-[#2563eb]/40'
    ]) {
      expect(shell.className).toContain(cls)
    }

    // 盒内底栏：批 F · F1 起左端不再有选择/调参面（批 E · E1 退模型选择器、本批退参数 popover），
    // 第一格就是 flex-1 撑开；右侧仍是用量 pill + 发送钮（顺序未变）
    const footer = shell.lastElementChild as HTMLElement
    expect(footer.className).toContain('flex')
    // 参数触发器/弹层整段退场（不是“隐藏”：DOM 里不该有）
    expect(footer.querySelector('[data-testid="params-toggle"]')).toBeNull()
    expect(footer.querySelector('[data-testid="params-popover"]')).toBeNull()
    expect(footer.querySelector('[data-testid="model-slot"]')).toBeNull()
    const spacer = Array.from(footer.children).find((child) => child.className.includes('flex-1'))
    expect(spacer).toBeTruthy()
    // 左端第一格 = 撑开位（去 UI 后不再有靠左的钮）
    expect(footer.firstElementChild).toBe(spacer)
    expect(Array.from(footer.children).indexOf(spacer as Element))
      .toBeLessThan(Array.from(footer.children).findIndex((child) =>
        child.getAttribute('data-testid') === 'send-btn'))

    const send = wrapper.find('[data-testid="send-btn"]')
    expect(send.element.tagName).toBe('BUTTON')
    expect(send.attributes('aria-label')).toBe('发送')
    expect(send.attributes('title')).toBeUndefined()
    expect(send.attributes('style')).toBeUndefined()
    expect(send.classes()).toEqual(expect.arrayContaining([
      'w-[28px]', 'h-[28px]', 'rounded-full', 'bg-[#2563eb]', 'text-white', 'border-0',
      'flex', 'items-center', 'justify-center'
    ]))
    expect(send.text().startsWith('↑')).toBe(true)
    const sendTip = send.find('span.tip')
    expect(sendTip.exists()).toBe(true)
    expect(sendTip.text()).toBe('发送')
    expect(sendTip.attributes('aria-hidden')).toBe('true')
    expect(sendTip.classes()).toEqual(expect.arrayContaining(['pointer-events-none', 'absolute', 'bottom-[calc(100%+7px)]']))
    expect(wrapper.find('button[aria-label="停止生成"]').exists()).toBe(false)

    // 流式中同一发送位换圆形停止钮（28×28 #1f2328 + 9×9 白方块 + tip），仍无原生 title
    useAiStore().loading = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(false)
    const stop = wrapper.find('button[aria-label="停止生成"]')
    expect(stop.exists()).toBe(true)
    expect(stop.attributes('title')).toBeUndefined()
    expect(stop.attributes('style')).toBeUndefined()
    expect(stop.classes()).toEqual(expect.arrayContaining([
      'w-[28px]', 'h-[28px]', 'rounded-full', 'bg-[#1f2328]', 'border-0',
      'flex', 'items-center', 'justify-center'
    ]))
    const square = stop.findAll('span').find((node) => node.classes().includes('w-[9px]'))
    expect(square).toBeTruthy()
    expect(square!.classes()).toEqual(expect.arrayContaining(['h-[9px]', 'bg-white', 'rounded-[2px]', 'block']))
    const stopTip = stop.find('span.tip')
    expect(stopTip.text()).toBe('停止生成')
    expect(stopTip.attributes('aria-hidden')).toBe('true')

    expect(box.attributes('style')).toBeUndefined()
    expect(wrapper.find('.el-textarea').exists()).toBe(false)
    expect(wrapper.find('style').exists()).toBe(false)
    wrapper.unmount()
  })

  it('tooltip Escape：focus 发送钮显示、Escape 隐藏且不截断事件、focusin 恢复', async () => {
    const { wrapper } = await mountPhase1()
    const send = wrapper.find('[data-testid="send-btn"]')
    const tip = send.find('span.tip')

    fireTipFocus(send.element, 'focusin')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')

    const rootSpy = vi.fn()
    wrapper.element.addEventListener('keydown', rootSpy)
    const escape = fireTipEscape(send.element)
    wrapper.element.removeEventListener('keydown', rootSpy)
    await wrapper.vm.$nextTick()

    expect(tip.classes()).toContain('hidden')
    expect(tip.attributes('aria-hidden')).toBe('true')
    expect(escape.defaultPrevented).toBe(false)
    expect(escape.cancelBubble).toBe(false)
    expect(rootSpy).toHaveBeenCalledTimes(1)

    fireTipFocus(send.element, 'focusin')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    wrapper.unmount()
  })

  it('tooltip Escape：focusout 隐藏、再次 focusin 恢复', async () => {
    const { wrapper } = await mountPhase1()
    const send = wrapper.find('[data-testid="send-btn"]')
    const tip = send.find('span.tip')

    fireTipFocus(send.element, 'focusin')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    fireTipFocus(send.element, 'focusout')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).toContain('hidden')
    fireTipFocus(send.element, 'focusin')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    wrapper.unmount()
  })

  it('tooltip Escape：停止钮同样 Escape 隐藏、focusin 恢复', async () => {
    const { wrapper } = await mountPhase1({ loading: true })
    const stop = wrapper.find('button[aria-label="停止生成"]')
    const tip = stop.find('span.tip')

    fireTipFocus(stop.element, 'focusin')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    const escape = fireTipEscape(stop.element)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).toContain('hidden')
    expect(escape.defaultPrevented).toBe(false)
    fireTipFocus(stop.element, 'focusin')
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    wrapper.unmount()
  })

  it('tooltip mouseover/out：内部移动不清除，外部离开隐藏、重新进入恢复', async () => {
    const { wrapper } = await mountPhase1()
    const send = wrapper.find('[data-testid="send-btn"]')
    const tip = send.find('span.tip')

    fireTipMouse(send.element, 'mouseover', document.body)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    fireTipMouse(send.element, 'mouseout', tip.element)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    fireTipMouse(send.element, 'mouseout', document.body)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).toContain('hidden')
    fireTipMouse(send.element, 'mouseover', document.body)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).not.toContain('hidden')
    wrapper.unmount()
  })

  it('tooltip mouseover：内部子元素移动不误清除 Escape 隐藏', async () => {
    const { wrapper } = await mountPhase1()
    const send = wrapper.find('[data-testid="send-btn"]')
    const tip = send.find('span.tip')

    fireTipFocus(send.element, 'focusin')
    await wrapper.vm.$nextTick()
    fireTipEscape(send.element)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).toContain('hidden')
    fireTipMouse(tip.element, 'mouseover', send.element)
    await wrapper.vm.$nextTick()
    expect(tip.classes()).toContain('hidden')
    wrapper.unmount()
  })

  it('composer v2 流式：textarea 保持可编辑（无 disabled / 无虚线）', async () => {
    const { wrapper } = await mountPhase1({ loading: true })
    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.attributes('disabled')).toBeUndefined()
    expect(box.classes()).not.toContain('border-dashed')
    expect((box.element.parentElement as HTMLElement).className).not.toContain('border-dashed')

    await box.setValue('流式里继续写')
    expect((box.element as HTMLTextAreaElement).value).toBe('流式里继续写')
    wrapper.unmount()
  })

  it('composer v2 禁用拆分：仅真实不可用 / 挂起 / pendingToolCall 硬禁用；loading 不硬禁用', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    const isDisabled = () => box.attributes('disabled') !== undefined

    expect(isDisabled()).toBe(false)
    store.loading = true
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(false)

    store.suspended = true
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(true)
    store.loading = false
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(true)

    store.suspended = false
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(false)

    store.health = { ...store.health!, available: false }
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(true)
    store.health = { ...store.health!, available: true }
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(false)

    store.messages = [buildMessage(
      [{ kind: 'text', seg: 0, text: '', streaming: false }],
      { toolCallId: 'pc-1', name: 'probe.tool', kind: 'FRONTEND', args: {}, status: 'pending' }
    )]
    await wrapper.vm.$nextTick()
    expect(isDisabled()).toBe(true)
    wrapper.unmount()
  })

  it('composer v2 Enter：流式普通 Enter = preventDefault 后静默（不发送 / 不停止 / 不换行 / 不提示）', async () => {
    const { store, wrapper } = await mountPhase1({ loading: true })
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('流式草稿')
    const sendSpy = vi.spyOn(store, 'send')
    const stopSpy = vi.spyOn(store, 'stop')
    api.chat.mockClear()

    const event = fireEnter(box.element as HTMLTextAreaElement)
    expect(event.defaultPrevented).toBe(true)
    expect((box.element as HTMLTextAreaElement).value).toBe('流式草稿')
    expect(sendSpy).not.toHaveBeenCalled()
    expect(stopSpy).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()
    expect(api.chat).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()

    // Shift+Enter 不拦截（浏览器原生换行路径）
    const shift = fireEnter(box.element as HTMLTextAreaElement, { shiftKey: true })
    expect(shift.defaultPrevented).toBe(false)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(stopSpy).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()
    wrapper.unmount()
  })

  it('composer v2 Enter：IME 组合期（isComposing / keyCode 229）不误拦', async () => {
    const { store, wrapper } = await mountPhase1({ loading: true })
    const box = wrapper.find('[data-testid="composer-input"]')
    const el = box.element as HTMLTextAreaElement
    await box.setValue('ni')
    const sendSpy = vi.spyOn(store, 'send')

    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    const composing = fireEnter(el, { isComposing: true })
    expect(composing.defaultPrevented).toBe(false)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()

    // 组合已结束的独立场景：keyCode 229（Safari 乱序确认 Enter）
    el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }))
    const safari = fireEnter(el, { keyCode: 229 })
    expect(safari.defaultPrevented).toBe(false)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()
    wrapper.unmount()
  })

  it('composer v2 Enter：compositionend 后约 30ms 内不发（不误发刚确认的候选）', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('候选文本')
    const el = box.element as HTMLTextAreaElement
    const sendSpy = vi.spyOn(store, 'send')
    const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(20_000)

    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }))
    nowSpy.mockReturnValue(20_010)
    const guarded = fireEnter(el)
    expect(guarded.defaultPrevented).toBe(true)
    expect(sendSpy).not.toHaveBeenCalled()

    nowSpy.mockReturnValue(20_031)
    const passed = fireEnter(el)
    expect(passed.defaultPrevented).toBe(true)
    expect(sendSpy).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('composer v2 Enter：compositionstart 后无事件旗标的 Enter 由本地组合态拦截', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    const el = box.element as HTMLTextAreaElement
    await box.setValue('组合草稿')
    const sendSpy = vi.spyOn(store, 'send')

    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    const event = fireEnter(el)
    expect(event.defaultPrevented).toBe(false)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()
    wrapper.unmount()
  })

  it('composer v2 Enter：组合态 blur 后复位（重新聚焦 Enter 恢复发送）', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    const el = box.element as HTMLTextAreaElement
    await box.setValue('恢复发送')
    const sendSpy = vi.spyOn(store, 'send')

    // compositionstart → blur → 重新聚焦：本地组合态须复位
    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    await box.trigger('blur')
    await box.trigger('focus')
    const event = fireEnter(el)
    expect(event.defaultPrevented).toBe(true)
    expect(sendSpy).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('composer v2 health 未知：textarea 不硬禁用，但发送钮禁用且 Enter 不发送', async () => {
    const { store, wrapper } = await mountPhase1({ health: 'unknown' })
    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.attributes('disabled')).toBeUndefined()
    await box.setValue('未知态草稿')

    const send = wrapper.find('[data-testid="send-btn"]')
    expect(send.attributes('disabled')).toBeDefined()
    const sendSpy = vi.spyOn(store, 'send')

    const event = fireEnter(box.element as HTMLTextAreaElement)
    expect(event.defaultPrevented).toBe(true)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(store.notice).toBeNull()
    wrapper.unmount()
  })

  it('composer v2 healthError：textarea 硬禁用（真不可用）', async () => {
    const { wrapper } = await mountPhase1({ health: 'error' })
    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('composer v2 health 明确不可用：Enter preventDefault 后返回，不绕过发送钮', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('不可用草稿')
    store.health = { ...store.health!, available: false }
    await wrapper.vm.$nextTick()

    const send = wrapper.find('[data-testid="send-btn"]')
    expect(send.attributes('disabled')).toBeDefined()
    expect(box.attributes('disabled')).toBeDefined()
    const sendSpy = vi.spyOn(store, 'send')
    // 移出 DOM disabled 后直接派发：证明 keydown 分支自身也会拦（不只靠 disabled 属性）
    box.element.removeAttribute('disabled')
    const event = fireEnter(box.element as HTMLTextAreaElement)
    expect(event.defaultPrevented).toBe(true)
    expect(sendSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('composer v2 硬禁用发送：suspended / pendingToolCall 且草稿非空时按钮不可点', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('旧草稿')
    const send = wrapper.find('[data-testid="send-btn"]')
    expect(send.attributes('disabled')).toBeUndefined()
    const sendSpy = vi.spyOn(store, 'send')

    store.suspended = true
    await wrapper.vm.$nextTick()
    // B1（2026-10-20 裁决 10）：挂起态发送位换停止钮 —— 发送钮不渲染（草稿保留，发送不可达）
    expect(wrapper.find('[data-testid="send-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-composer-tip="stop"]').exists()).toBe(true)
    expect(sendSpy).not.toHaveBeenCalled()

    store.suspended = false
    store.messages = [buildMessage(
      [{ kind: 'text', seg: 0, text: '', streaming: false }],
      { toolCallId: 'pc-send', name: 'probe.tool', kind: 'FRONTEND', args: {}, status: 'pending' }
    )]
    await wrapper.vm.$nextTick()
    const sendAfter = wrapper.find('[data-testid="send-btn"]')
    expect(sendAfter.attributes('disabled')).toBeDefined()
    await sendAfter.trigger('click')
    expect(sendSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('composer v2 Enter：event.repeat 无行为（不发送 / 不换行）', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('重复按键草稿')
    const sendSpy = vi.spyOn(store, 'send')

    const event = fireEnter(box.element as HTMLTextAreaElement, { repeat: true })
    expect(event.defaultPrevented).toBe(true)
    expect(sendSpy).not.toHaveBeenCalled()
    expect((box.element as HTMLTextAreaElement).value).toBe('重复按键草稿')
    wrapper.unmount()
  })

  it('composer v2 Enter：空闲普通 Enter 仍走现有 send（不回归）', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('空闲发送')
    const sendSpy = vi.spyOn(store, 'send')

    const event = fireEnter(box.element as HTMLTextAreaElement)
    expect(event.defaultPrevented).toBe(true)
    expect(sendSpy).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('composer v2 停止钮：只调用 stop，不自动发送、不改流式草稿', async () => {
    const { store, wrapper } = await mountPhase1({ loading: true })
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('下一段草稿')
    const sendSpy = vi.spyOn(store, 'send')
    const stopSpy = vi.spyOn(store, 'stop')
    api.chat.mockClear()

    await wrapper.find('button[aria-label="停止生成"]').trigger('click')
    expect(stopSpy).toHaveBeenCalledTimes(1)
    expect(sendSpy).not.toHaveBeenCalled()
    expect(api.chat).not.toHaveBeenCalled()
    expect((box.element as HTMLTextAreaElement).value).toBe('下一段草稿')
    wrapper.unmount()
  })

  it('阶段二 发送成功：清空草稿并回收焦点（preventScroll）', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('成功发送')
    const focusSpy = vi.spyOn(box.element as HTMLTextAreaElement, 'focus')

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect((box.element as HTMLTextAreaElement).value).toBe('')
    expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true })
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    wrapper.unmount()
  })

  for (const outcome of ['error', 'interrupted', 'not-sent'] as const) {
    it(`阶段二 ${outcome}：草稿为空时恢复原稿并重新聚焦`, async () => {
      const { store, wrapper } = await mountPhase1()
      const box = wrapper.find('[data-testid="composer-input"]')
      await box.setValue('原始草稿')
      const focusSpy = vi.spyOn(box.element as HTMLTextAreaElement, 'focus')
      vi.spyOn(store, 'send').mockResolvedValue({ outcome, userMessageId: outcome === 'not-sent' ? null : 'u-recovered' })

      await wrapper.find('[data-testid="send-btn"]').trigger('click')
      await flushPromises()

      expect((box.element as HTMLTextAreaElement).value).toBe('原始草稿')
      expect(focusSpy.mock.calls.filter((call) => call[0]?.preventScroll === true).length).toBeGreaterThanOrEqual(2)
      wrapper.unmount()
    })
  }

  it('阶段二 cancelled：不恢复草稿', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('停止草稿')
    vi.spyOn(store, 'send').mockResolvedValue({ outcome: 'cancelled', userMessageId: 'u-cancelled' })

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect((box.element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })

  it('阶段二 失败恢复：用户已写新内容时不覆盖、不设失败锚点', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('原始草稿')
    let resolveSend!: (value: { outcome: 'error'; userMessageId: string }) => void
    const pending = new Promise<{ outcome: 'error'; userMessageId: string }>((resolve) => {
      resolveSend = resolve
    })
    const sendSpy = vi.spyOn(store, 'send').mockReturnValue(pending)

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    expect((box.element as HTMLTextAreaElement).value).toBe('')

    await box.setValue('用户新输入')
    resolveSend({ outcome: 'error', userMessageId: 'u-failed' })
    await flushPromises()
    expect((box.element as HTMLTextAreaElement).value).toBe('用户新输入')

    // 未把失败的 userMessageId 设为锚点：下一次发送仍按新消息走（不带 skipUserRecord）
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    expect(sendSpy.mock.calls[1]?.[1]?.skipUserRecord).not.toBe(true)
    wrapper.unmount()
  })

  it('阶段二 新消息失败后重发：走编辑重跑，用户消息数不增加', async () => {
    const { store, wrapper } = await mountPhase1()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('失败重发草稿')
    api.chat.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(undefined)

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect((box.element as HTMLTextAreaElement).value).toBe('失败重发草稿')

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    const users = store.messages.filter((message) => message.role === 'user')
    expect(users).toHaveLength(1)
    expect(users[0]?.content).toBe('失败重发草稿')
    expect(api.chat).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('阶段二 编辑重跑失败：沿用发送前 editId，重发仍不新增用户消息', async () => {
    const { store, wrapper } = await mountPhase1({ messages: [buildUser('u-edit-p2', '原提问')] })
    const box = wrapper.find('[data-testid="composer-input"]')
    api.chat.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(undefined)

    await wrapper.find('[data-testid="edit-msg-u-edit-p2"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect((box.element as HTMLTextAreaElement).value).toBe('原提问')
    await box.setValue('改写后的提问')

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect((box.element as HTMLTextAreaElement).value).toBe('改写后的提问')

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    const users = store.messages.filter((message) => message.role === 'user')
    expect(users).toHaveLength(1)
    expect(users[0]?.id).toBe('u-edit-p2')
    expect(users[0]?.content).toBe('改写后的提问')
    expect(api.chat).toHaveBeenCalledTimes(2)
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
    const label = wrapper.find('div > label.block')
    expect(label.classes()).toEqual(expect.arrayContaining(['block', 'text-[12.2px]', 'mb-[4px]', 'text-[#57606a]']))

    // 六型控件：原生 input/select/checkbox，无一个 EP 控件
    const text = wrapper.find('input[type="text"]')
    expect(text.classes()).toEqual(expect.arrayContaining(['w-full', 'rounded-[8px]', 'px-[9px]', 'py-[6px]', 'text-[13px]', 'border-solid', 'border-[#e3e6ea]', 'bg-white']))
    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
    expect(wrapper.find('input[type="date"]').exists()).toBe(true)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(3) // 1 布尔开关 + 2 多选胶囊
    expect(wrapper.findAll('select')).toHaveLength(1)
    expect(wrapper.html()).not.toContain('<el-')

    // 双钮（去超时裁决后无时限脚注）
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
    // 去超时裁决：时限脚注整块退役（表单一直可填）
    expect(wrapper.find('[data-testid="gf-deadline"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('剩余填写时间')
    expect(wrapper.text()).not.toContain('已超时，表单不可再提交')
    wrapper.unmount()
  })

  it('GF 表单卡：必填未填 ⇒ 字段红框 + 字段下 .fmsg；填齐 ⇒ 提交值上抛（无本地超时态）', async () => {
    const wrapper = mountGf()
    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await wrapper.vm.$nextTick()

    const name = wrapper.find('input[type="text"]')
    expect(name.classes()).toEqual(expect.arrayContaining(['border-[#c0392b]', 'bg-[#fdf1ef]']))
    const label = wrapper.find('div > label.block')
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
    // L-76 语义变更（裁定：前端丢弃空串可选值）：可选日期留空 = 未填 ⇒ 不提交该键
    // （旧断言把 `startAt: ''` 一起上送，正是「表单认为没填、后端按类型判 400」的成因）。
    expect(submitted![0][0]).toEqual({
      name: '数据同步',
      interval: 300,
      dryRun: false,
      mode: 'fast',
      tags: []
    })
    expect(Object.prototype.hasOwnProperty.call(submitted![0][0] as Record<string, unknown>, 'startAt')).toBe(false)
    wrapper.unmount()

    // 后端拒绝说明（FORM_RESULT_REJECTED）落卡级错误条
    const rejected = mountGf({ error: '服务端拒绝：执行间隔过大' })
    const bar = rejected.find('[data-testid="gf-error"]')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('服务端拒绝：执行间隔过大')
    expect(bar.classes()).toEqual(expect.arrayContaining(['text-[#c0392b]', 'bg-[#fdf1ef]', 'border-[#f0d5d0]']))
    rejected.unmount()

    // 去超时裁决：任何时刻都不出现「已超时」态，提交钮不被本地时限置灰
    const alwaysOpen = mountGf()
    expect(alwaysOpen.find('[data-testid="gf-deadline"]').exists()).toBe(false)
    expect(alwaysOpen.find('[data-testid="gf-submit"]').attributes('disabled')).toBeUndefined()
    alwaysOpen.unmount()
  })

  it('2A 缺陷②：enum 初值合法默认（缺省 / 非法 defaultValue ⇒ 首个选项；合法 defaultValue 保留）', () => {
    const schemaWith = (extra: Record<string, unknown>): FormSpec => ({
      scenario: 'CONFIRM',
      title: '枚举初值',
      fields: [{
        key: 'mode',
        label: '执行模式',
        type: 'enum',
        required: true,
        options: [{ value: 'fast', label: '快速' }, { value: 'safe', label: '安全' }],
        ...extra
      }]
    })
    const mountWith = (schema: FormSpec) => mount(FormRenderer, {
      props: { schema, submitting: false, error: null }
    })
    // 缺省 ⇒ 首个选项（不落空串 / 不落「请选择…」非法初态）
    const none = mountWith(schemaWith({}))
    expect((none.find('select').element as HTMLSelectElement).value).toBe('fast')
    none.unmount()
    // 非法 defaultValue ⇒ 首个选项
    const illegal = mountWith(schemaWith({ defaultValue: 'nope' }))
    expect((illegal.find('select').element as HTMLSelectElement).value).toBe('fast')
    illegal.unmount()
    // 合法 defaultValue 照原样保留
    const legal = mountWith(schemaWith({ defaultValue: 'safe' }))
    expect((legal.find('select').element as HTMLSelectElement).value).toBe('safe')
    legal.unmount()
  })

  it('2A② 缺陷①：readonly 挂载时值未到 ⇒ 回落默认；值后到 ⇒ 六型控件全部重灌真值（只读先到）', async () => {
    const wrapper = mount(FormRenderer, {
      props: {
        schema: GF_SCHEMA,
        submitting: false,
        error: null,
        readonly: true,
        initialValues: null
      }
    })
    const read = () => ({
      text: (wrapper.find('input[type="text"]').element as HTMLInputElement).value,
      number: (wrapper.find('input[type="number"]').element as HTMLInputElement).value,
      date: (wrapper.find('input[type="date"]').element as HTMLInputElement).value,
      select: (wrapper.find('select').element as HTMLSelectElement).value,
      boxes: wrapper.findAll('input[type="checkbox"]').map((box) => (box.element as HTMLInputElement).checked)
    })

    // 挂载即只读但值未到：六型回落 schema 初始态（枚举 = 首个合法选项，布尔 = false）
    expect(read()).toEqual({ text: '', number: '', date: '', select: 'fast', boxes: [false, false, false] })

    // POST 后 submittedValues 晚于挂载到达 ⇒ 只读卡必须重灌真值（旧实现 model 停留在初始态）
    await wrapper.setProps({
      initialValues: {
        name: '季度导出',
        interval: 30,
        dryRun: true,
        startAt: '2026-10-19',
        mode: 'safe',
        tags: ['a', 'b']
      }
    })
    expect(read()).toEqual({
      text: '季度导出',
      number: '30',
      date: '2026-10-19',
      select: 'safe',
      boxes: [true, true, true]
    })
    expect(wrapper.findAll('input, select').every((control) => control.attributes('disabled') !== undefined)).toBe(true)
    wrapper.unmount()
  })

  it('2A② 缺陷①：editable 本地输入不被 initialValues 变化覆盖；readonly 后到 ⇒ 以提交值为准（值先到）', async () => {
    const wrapper = mount(FormRenderer, {
      props: {
        schema: GF_SCHEMA,
        submitting: false,
        error: null,
        readonly: false,
        initialValues: null
      }
    })
    const text = wrapper.find('input[type="text"]')
    const select = () => wrapper.find('select').element as HTMLSelectElement
    await text.setValue('本地草稿')

    // 编辑态：initialValues 变化（如他端/预填数据晚到）不得覆盖用户正在输入的内容
    await wrapper.setProps({ initialValues: { name: '服务端值', mode: 'safe', tags: ['b'] } })
    expect((text.element as HTMLInputElement).value).toBe('本地草稿')
    expect(select().value).toBe('fast')

    // 只读后到：同一张表单锁定并回填提交值（用户本地草稿让位于已提交事实）
    await wrapper.setProps({ readonly: true })
    expect((text.element as HTMLInputElement).value).toBe('服务端值')
    expect(select().value).toBe('safe')
    expect(wrapper.findAll('[data-testid="gf-submit"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('2A② 缺陷①：readonly 下 initialValues 从有到无（取消/超时收尾）⇒ 回落默认，不保留半成品', async () => {
    const wrapper = mount(FormRenderer, {
      props: {
        schema: GF_SCHEMA,
        submitting: false,
        error: null,
        readonly: true,
        initialValues: { name: '已提交', mode: 'safe' }
      }
    })
    const name = () => (wrapper.find('input[type="text"]').element as HTMLInputElement).value
    expect(name()).toBe('已提交')
    await wrapper.setProps({ initialValues: null })
    expect(name()).toBe('')
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('fast')
    wrapper.unmount()
  })

  it('2A② 缺陷①端到端：只读卡已挂载（无值）⇒ submittedValues 后到，展开即显示提交值', async () => {
    const args = JSON.stringify({
      form: {
        scenario: 'export_query',
        title: '导出参数',
        fields: [
          {
            key: 'region',
            label: '导出范围',
            type: 'enum',
            required: true,
            options: [{ value: 'HB', label: '华北' }, { value: 'HD', label: '华东' }]
          },
          { key: 'note', label: '说明', type: 'text', required: false }
        ]
      }
    })
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'g-late', name: 'generative_form', kind2: 'FRONTEND',
        status: 'succeeded', args, requiresAction: false
      }
    ])
    msg.id = 'a-late'
    const wrapper = await mountPanel([msg], { realForm: true })
    await wrapper.find('[data-testid="ro-toggle-g-late"]').trigger('click')
    const body = () => wrapper.find('[data-testid="ro-body-g-late"]')
    expect((body().find('select').element as HTMLSelectElement).value).toBe('HB')
    expect((body().find('input[type="text"]').element as HTMLInputElement).value).toBe('')

    // POST 应答后写回（旧实现：store 只写首宿主且 FormRenderer 不重灌 ⇒ 卡面停在默认值）
    useAiStore().writeSubmittedValues('g-late', { region: 'HD', note: '晚到值' })
    await wrapper.vm.$nextTick()
    expect((body().find('select').element as HTMLSelectElement).value).toBe('HD')
    expect((body().find('input[type="text"]').element as HTMLInputElement).value).toBe('晚到值')
    expect(body().text()).toContain('华东')
    wrapper.unmount()
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

    // AR-13：通知级别只认 noticeSeverity；上一轮结局为 error 不得把普通通知染红
    store.notice = '本轮已取消'
    store.lastRoundOutcome = 'error'
    store.noticeSeverity = 'info'
    await wrapper.vm.$nextTick()
    const notice = wrapper.find('[data-testid="notice-bar"]')
    expect(notice.classes()).toEqual(expect.arrayContaining(['border-dashed', 'border-[#e3e6ea]', 'bg-[#f6f7f9]', 'rounded-[10px]']))
    expect(notice.find('span').text()).toBe('—')
    // error 级通知仍走 v7 `.enotice` 红族
    store.noticeSeverity = 'error'
    await wrapper.vm.$nextTick()
    expect(notice.classes()).toEqual(expect.arrayContaining(['border-solid', 'border-[#f0d5d0]', 'bg-[#fdf1ef]', 'rounded-[9px]']))
    expect(notice.find('span').text()).toBe('!')
    store.noticeSeverity = 'info'
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

  it('项2：用户长消息折叠两态（>184px ⇒ 限高+裁切+渐隐+「展开 ▾」；展开解除；再点回折叠）', async () => {
    const { wrapper, body } = await mountLongUser('很长的用户提问'.repeat(60), 600)
    const more = wrapper.find('[data-testid="user-msg-more-u-long"]')
    expect(more.exists()).toBe(true)
    // 问题1 偏差修正批 aj11：文案带方向标；形态 = 11.5px #57606a（hover 蓝）· 底部左对齐 · mt-[8px]
    expect(more.text()).toBe('展开 ▾')
    expect(more.classes()).toEqual(expect.arrayContaining(['mt-[8px]', 'block', 'text-left', 'text-[11.5px]', 'text-[#57606a]', 'hover:text-[#2563eb]']))
    expect(more.classes()).not.toContain('text-[12.2px]')
    expect(more.classes()).not.toContain('text-[#2563eb]')
    expect(more.attributes('aria-expanded')).toBe('false')
    expect(body.className).toContain('max-h-[184px]')
    expect(body.className).toContain('overflow-hidden')
    expect(body.className).toContain('[mask-image:linear-gradient(to_bottom,#000_calc(100%_-_90px),transparent)]')
    expect(body.getAttribute('style')).toBeNull() // 铁律：全 Tailwind，零行内 style
    await more.trigger('click')
    expect(wrapper.find('[data-testid="user-msg-more-u-long"]').text()).toBe('收起 ▴')
    expect(wrapper.find('[data-testid="user-msg-more-u-long"]').attributes('aria-expanded')).toBe('true')
    expect(body.className).not.toContain('max-h-[184px]')
    expect(body.className).not.toContain('[mask-image:')
    // 再点回折叠：本地态翻转（该消息维度，不进 store）
    await wrapper.find('[data-testid="user-msg-more-u-long"]').trigger('click')
    expect(wrapper.find('[data-testid="user-msg-more-u-long"]').text()).toBe('展开 ▾')
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

  it('附→S6（裁决点 21）：思考过程展开正文 = 纯限高内滚 160px（无展开钮 / 无 mask / 卡头折叠不动）', async () => {
    const msg = buildMessage([{ kind: 'reasoning', seg: 0, text: '想很久'.repeat(20), streaming: false }])
    const wrapper = await mountPanel([msg])
    const head = wrapper.find('[data-testid="reasoning-toggle-a-1:reasoning-0"]')
    expect(head.exists()).toBe(true)
    expect(head.find('.tchev').exists()).toBe(true) // 卡头折叠机制保留
    await head.trigger('click')
    const body = wrapper.find('[data-testid="reasoning-body-a-1:reasoning-0"]')
    expect(body.exists()).toBe(true)
    const scroll = wrapper.find('[data-testid="reasoning-scroll-a-1:reasoning-0"]')
    expect(scroll.exists()).toBe(true)
    expect(scroll.classes()).toEqual(expect.arrayContaining(['max-h-[min(280px,35vh)]', 'overflow-y-auto', 'whitespace-pre-wrap', 'break-words']))
    expect(scroll.classes()).not.toContain('whitespace-nowrap')
    expect(scroll.attributes('style')).toBeUndefined() // 零行内 style
    expect(scroll.text()).toContain('想很久')
    // 无二次折叠钮（展开体不再出现「显示更多 / 展开全高」）
    expect(body.find('button').exists()).toBe(false)
    // 短思考零副作用：限高类常挂但内容不超高（jsdom 无布局，只锁类与结构）
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
    // 卡面一致性：确认卡单层（卡体内一层，不再是自带外边距的内层蓝框）
    expect(gate.className).toBe('pb-[11px]')
    const gateRows = wrapper.find('[data-testid="gate-rows"]').element as HTMLElement
    expect(gateRows.className).toContain('max-h-[min(280px,35vh)]')
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
    expect(preview.className).toContain('max-h-[min(320px,45vh)]')
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
    expect(box.className).toContain('max-h-[min(280px,35vh)]')
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

  /**
   * 性能专项（2026-10-24）：mermaid 由「闭合即渲染」改为 **IntersectionObserver 门控** ——
   * 只有进入消息容器视口（rootMargin 200px）才真正渲染；渲染完成即 unobserve，
   * 长会话里历史消息的图不再在打开面板的瞬间一起排队。
   */
  it('性能专项：IO 门控 —— 不可见不渲染，进入视口后渲染并 unobserve', async () => {
    const observed: Element[] = []
    const unobserved: Element[] = []
    const callbacks: IntersectionObserverCallback[] = []
    class IntersectionObserverStub {
      constructor(cb: IntersectionObserverCallback) {
        callbacks.push(cb)
      }
      observe(el: Element): void {
        observed.push(el)
      }
      unobserve(el: Element): void {
        unobserved.push(el)
      }
      disconnect(): void {}
      takeRecords(): IntersectionObserverEntry[] {
        return []
      }
    }
    vi.stubGlobal('IntersectionObserver', IntersectionObserverStub)
    try {
      mmdRender.svg = MMD_SVG
      const wrapper = await mountPanel([buildMessage([{ kind: 'text', seg: 0, text: MMD_FENCE, streaming: false }])])
      await flushPromises()
      expect(observed).toHaveLength(1)
      // 未进入视口：源码块保留，不发起渲染
      expect(wrapper.find('.md-body .mmdc').exists()).toBe(false)
      callbacks[0]!([{ target: observed[0]!, isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
      await flushPromises()
      await flushPromises()
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.md-body .mmdc').exists()).toBe(true)
      expect(unobserved).toContain(observed[0])
      wrapper.unmount()
    } finally {
      vi.unstubAllGlobals()
    }
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

  it('A10 新 9 码 + 派生码 + key 码都有专属卡（不再是未分类通用卡），码仍保留在 card.code', async () => {
    const { buildErrorCard } = await import('@/utils/error-card')
    const { translate } = await import('@/composables/useCopy')
    const genericTitle = translate('ai.card.error.generic.title')
    const codes = [
      'EMPTY_REPLY',
      'AI_RUN_CANCELLED',
      'UPSTREAM_FIRST_BYTE_TIMEOUT',
      'UPSTREAM_SILENT_TIMEOUT',
      'TOTAL_BUDGET_EXCEEDED',
      'UPSTREAM_STREAM_INCOMPLETE',
      'UPSTREAM_CONNECTION_BROKEN',
      'UPSTREAM_STREAM_ERROR'
    ]
    for (const code of codes) {
      const card = buildErrorCard(code, '后端原文')
      expect(card, code).not.toBeNull()
      expect(card?.title, code).not.toBe(genericTitle)
      expect(card?.guide, code).not.toBe('')
      expect(card?.code, code).toBe(code)
    }
    // 批次2：派生码不再削回基础码 —— 完整码/基础码/派生语义都在卡上，标题与摘要按现场区分
    const base = buildErrorCard('UPSTREAM_STREAM_ERROR', '断流')
    const derived = buildErrorCard('UPSTREAM_STREAM_ERROR_AFTER_PARTIAL', '断流')
    expect(derived?.title).not.toBe(base?.title)
    expect(derived?.title).toContain('内容不完整')
    expect(derived?.baseCode).toBe('UPSTREAM_STREAM_ERROR')
    expect(derived?.derived).toBe('partial')
    expect(derived?.code).toBe('UPSTREAM_STREAM_ERROR_AFTER_PARTIAL')
    expect(buildErrorCard('TOTAL_BUDGET_EXCEEDED_AFTER_TOOL_SIDE_EFFECT', '超时')?.title)
      .toContain('已执行工具')
    // R-12：无 key / key 解不开 → 专门卡 + 引导到设置页
    const missing = buildErrorCard('AI_KEY_MISSING', '后端原文')
    expect(missing?.title).toBe('缺少 API Key')
    expect(missing?.guide).toContain('设置 → AI')
    const unreadable = buildErrorCard('AI_KEY_UNREADABLE', '后端原文')
    expect(unreadable?.title).toBe('API Key 无法解密')
    expect(unreadable?.guide).toContain('设置 → AI')
  })

  it('R-12 开流前 503：AI_KEY_MISSING / AI_KEY_UNREADABLE 用本地文案（不叠后端中文）并出专门卡', async () => {
    const store = useAiStore()
    const { ApiError } = await import('@/api/http')
    const assistant = (id: string) => {
      const m = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)
      m.id = id
      return m
    }
    const m1 = assistant('key-missing')
    store.messages = [m1]
    store.handleChatError(new ApiError('AI 能力未启用：已配置的 provider 均无可用 key', 503, 'AI_KEY_MISSING', null), 'key-missing')
    expect(store.notice).toBeNull() // AR-13：关键错误只走错误卡
    expect(m1.errorCard?.guide).toContain('设置 → AI')
    expect(m1.errorCard?.title).toBe('缺少 API Key')
    expect(m1.errorCard?.code).toBe('AI_KEY_MISSING')
    expect(m1.parts.some((p) => p.kind === 'text' && p.text !== ''), '正文不回显后端中文（防中英叠加）').toBe(false)

    const m2 = assistant('key-unreadable')
    store.messages = [m2]
    const unreadableReason = 'AI 能力未启用：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置'
    store.handleChatError(new ApiError(unreadableReason, 503, 'AI_KEY_UNREADABLE', null), 'key-unreadable')
    expect(store.notice).toBeNull()
    expect(m2.errorCard?.title).toBe('API Key 无法解密')
    expect(m2.errorCard?.code).toBe('AI_KEY_UNREADABLE')
    expect(m2.errorCard?.detail).toBe(unreadableReason)
    for (const forbidden of ['AI_API_KEY', 'DEEPSEEK_API_KEY', 'AI_SECRET_KEK', 'KEK', 'provider']) {
      expect(m2.errorCard?.detail, forbidden).not.toContain(forbidden)
    }

    // I-1：/health 降级横幅同样不得出现变量名/机制词（zh）
    const healthBody = {
      available: false, code: 'AI_KEY_UNREADABLE', message: unreadableReason
    }
    api.health.mockResolvedValue(healthBody as never)
    const panel = await mountPanel(store.messages)
    await flushPromises()
    const banner = panel.find('[data-testid="alert-degraded"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('设置 → AI')
    for (const forbidden of ['AI_API_KEY', 'DEEPSEEK_API_KEY', 'AI_SECRET_KEK', 'KEK', 'provider']) {
      expect(banner.text(), forbidden).not.toContain(forbidden)
    }
    panel.unmount()

    // I-1：en 横幅只展示后端已本地化的短句（无中文、无变量名）
    const { default: i18n } = await import('@/locales')
    i18n.global.locale.value = 'en-US'
    try {
      const enReason = 'The API key cannot be decrypted. Re-enter the key in Settings → AI, or ask the administrator to restore the encryption configuration'
      api.health.mockResolvedValue({ available: false, code: 'AI_KEY_UNREADABLE', message: enReason } as never)
      const enPanel = await mountPanel(store.messages)
      await flushPromises()
      const enBanner = enPanel.find('[data-testid="alert-degraded"]')
      expect(enBanner.text()).toContain('Settings → AI')
      expect(enBanner.text()).not.toMatch(/[\u4e00-\u9fff]/)
      expect(enBanner.text()).not.toContain('AI_SECRET_KEK')
      enPanel.unmount()
    } finally {
      i18n.global.locale.value = 'zh-CN'
    }
  })

  it('M1 开流前 503 NO_MODEL_CONFIGURED：只出专属卡，不回显后端中文', async () => {
    const store = useAiStore()
    const { ApiError } = await import('@/api/http')
    const message = buildMessage([{ kind: 'text', seg: 0, text: '', streaming: true }], null, true)
    message.id = 'no-model'
    store.messages = [message]
    const backendMessage = 'AI 默认模型未配置：请在「设置 → AI」为供应商添加模型并选择默认模型'

    store.handleChatError(new ApiError(backendMessage, 503, 'NO_MODEL_CONFIGURED', null), 'no-model')

    expect(store.notice).toBeNull() // AR-13：关键错误只走错误卡
    expect(message.errorCard?.guide).toContain('默认模型')
    expect(message.errorCard?.title).toBe('默认模型不可用')
    expect(message.errorCard?.code).toBe('NO_MODEL_CONFIGURED')
    expect(message.parts.some((part) => part.kind === 'text' && part.text !== '')).toBe(false)
  })

  it('store：SSE error 帧归错误卡，不再重复进通知条（lastRoundOutcome 仍如实记录）', async () => {
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
    expect(store.notice).toBeNull() // AR-13：关键错误归卡，通知条不重复
    expect(store.noticeSeverity).toBe('info')

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

  it('动作钮：普通已完成无 regenerate 假入口；改写后重发⇒焦点回输入框；开新会话⇒既有 newChat 出口', async () => {
    const { buildErrorCard } = await import('@/utils/error-card')
    const store = useAiStore()
    const mk = (id: string, code: string, detail: string) => {
      const m = buildMessage([{ kind: 'text', seg: 0, text: '正文', streaming: false }])
      m.id = id
      m.errorCard = buildErrorCard(code, detail)
      return m
    }
    const resetSpy = vi.spyOn(store, 'resetSession').mockResolvedValue(undefined)
    const ask = buildMessage([{ kind: 'text', seg: 0, text: '原提问', streaming: false }])
    ask.id = 'u-q2'
    ask.role = 'user'
    const wrapper = await mountPanel([ask, mk('f2', 'content_filter', '被拦截'), mk('c2', 'context_length_exceeded', '超限'), mk('q2', '429', '配额用尽')])
    const composer = wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement
    const focusSpy = vi.spyOn(composer, 'focus')
    // AR-17：有产出的普通已完成轮没有真实 regenerate 入口 ⇒ quota 卡的「稍后重试」直接隐藏，
    // 不做禁用 / unsupported 假动作，也不本地截断后 send。
    expect(wrapper.find('[data-testid="error-card-action-q2"]').exists()).toBe(false)
    await wrapper.find('[data-testid="error-card-action-f2"]').trigger('click')
    expect(focusSpy).toHaveBeenCalled()
    await wrapper.find('[data-testid="error-card-action-c2"]').trigger('click')
    expect(resetSpy).toHaveBeenCalled()
    wrapper.unmount()
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

/**
 * 问题1 偏差修正批（对账审计 2026-10-16 认定的 7 项「裁决/demo 已定但施工走样」收口；纯前端）。
 * 蓝本 = 仓外 `r163-demo/v7.11.html` 的各裁决点改后样张（aj04 / aj10 / aj11 / aj14d + 用户实报 3 条）。
 * 本组逐项各一例，锁的是「形态与裁决一致」本身（旧形态断言已在各自既有用例里同步改写）。
 */
describe('问题1 偏差修正批：7 项走样补齐', () => {
  it('项1（aj04）：长表格表头 = flex 行 + 6px gutter 占位格（表头底边收口；旧 pr-[6px] 补偿条零残留）', async () => {
    const { renderMarkdown } = await import('@/utils/markdown')
    const { html } = renderMarkdown('| 配置编码 | 说明 |\n| --- | --- |\n| PROJ_PARAM | 项目参数 |\n', false)
    // 表头行容器 = flex 行（背景与表头同色）
    expect(html).toMatch(/<div class="md-table-hd flex bg-\[#f6f7f9\]">/)
    // 表头区 = 可横滚表头区（overflow-hidden 供程序化 scrollLeft）
    expect(html).toMatch(/<div class="md-table-hdscroll flex-1 min-w-0 overflow-hidden">/)
    // gutter 占位格 = 6px 宽 + 表头同色背景 + 下边框（demo aj04 改后样张类名 + 本仓 border-solid 复位）
    expect(html).toMatch(/<div class="md-table-gutter flex-none w-\[6px\] bg-\[#f6f7f9\] border-0 border-b border-solid border-\[#e3e6ea\]"><\/div>/)
    // 两格次序：表头区在前、占位格在后（表头底边连续到最右）
    expect(html.indexOf('md-table-hdscroll')).toBeLessThan(html.indexOf('md-table-gutter'))
    // 旧 v7.6 形态（内边距补偿条）零残留：markdown.ts 源码与产物双面
    expect(html).not.toContain('pr-[6px]')
    // 源码面：旧类常量（内边距补偿条）零残留、新三格常量在位（注释里的历史说明不算）
    const { readFileSync } = await import('node:fs')
    const source = readFileSync('src/utils/markdown.ts', 'utf8')
    expect(source).not.toContain("md-table-hd overflow-hidden pr-[6px]")
    expect(source).toContain("const TABLE_HD_CLASS = 'md-table-hd flex bg-[#f6f7f9]'")
    expect(source).toContain('const TABLE_GUTTER_CLASS =')
    expect(source).toContain('const TABLE_HD_SCROLL_CLASS =')
    // 横滚联动改指表头区（可程序化 scrollLeft 的容器换了，选择器必须跟着换）
    const template = (await import('@/components/AiPanel/AiPanel.vue?raw')).default
    expect(template).toContain("querySelector('.md-table-hdscroll')")
  })

  it('项2（aj11）：长消息折叠钮 = 11.5px 灰字（hover 蓝）· 底部左对齐 · 距内容 8px · 「展开 ▾ / 收起 ▴」', async () => {
    const msg = buildMessage([{ kind: 'text', seg: 0, text: '很长的用户提问'.repeat(60), streaming: false }])
    msg.id = 'u-aj11'
    msg.role = 'user'
    const store = useAiStore()
    store.messages = [msg]
    store.historyLoaded = true
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    const body = wrapper.find('[data-testid="msg-bubble-u-aj11"] .md-body').element as HTMLElement
    Object.defineProperty(body, 'scrollHeight', { value: 600, configurable: true })
    await wrapper.vm.$nextTick()
    // 读数落地后再取钮（measure 在 onMounted 的 nextTick 里跑，注高须早于它）
    await wrapper.vm.$nextTick()
    const btn = wrapper.find('[data-testid="user-msg-more-u-aj11"]')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toBe('展开 ▾')
    expect(btn.classes()).toEqual(expect.arrayContaining([
      'mt-[8px]', 'block', 'text-left', 'text-[11.5px]', 'text-[#57606a]', 'hover:text-[#2563eb]'
    ]))
    wrapper.unmount()
  })

  it('项3：工具卡头重名收敛 —— 摘要以工具名打头即剥前缀（剥后为空/只剩状态词则不渲摘要位）', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        // 无参数映射族：摘要 = 工具名本身 ⇒ 剥后为空、摘要位不渲（卡头只读作一次工具名）
        { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'list_config_defs', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: 'ok', requiresAction: false },
        { kind: 'tool', seg: 1, toolCallId: 'c2', name: 'list_tasks', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: 'ok', requiresAction: false },
        { kind: 'tool', seg: 2, toolCallId: 'c3', name: 'get_workspace_state', kind2: 'BACKEND', status: 'succeeded', args: '{}', result: 'ok', requiresAction: false },
        // 参数族：剥掉同名前缀后只剩参数信息（信息不丢）
        { kind: 'tool', seg: 3, toolCallId: 'c4', name: 'start_export', kind2: 'BACKEND', status: 'succeeded', args: '{"taskId":12}', result: 'ok', requiresAction: false },
        { kind: 'tool', seg: 4, toolCallId: 'c5', name: 'get_row_count', kind2: 'BACKEND', status: 'succeeded', args: '{"defCode":"CURRENCY"}', result: 'ok', requiresAction: false }
      ])
    ])
    const summary = (id: string) => wrapper.find(`[data-testid="tool-summary-${id}"]`).text()
    expect(summary('c1')).toBe('')
    expect(summary('c2')).toBe('')
    expect(summary('c3')).toBe('')
    expect(summary('c4')).toBe('（任务 #12）')
    expect(summary('c5')).toBe('（CURRENCY）')
    // 卡头整行不再出现「工具名 + 同义摘要」两段：每张卡头里工具名恰一次
    const head = wrapper.find('[data-testid="block-toggle-a-1:c4"]')
    expect(head.text().split('启动导出作业').length - 1).toBe(1)
    expect(head.text()).toContain('（任务 #12）')
    wrapper.unmount()
  })

  it('项4：只读卡 / 门卡字段表键列自适应内容宽（min-w-0 + 40% 上限 + truncate + title），值列不动', async () => {
    const msg = buildMessage([
      { kind: 'text', seg: 0, text: '', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId: 'p1', name: 'start_publish', kind2: 'CONFIRM',
        status: 'succeeded', args: '{"taskId":3,"notifyOnFail":true}', requiresAction: false,
        decision: 'approved', decidedAt: 1_700_000_000_000
      },
      {
        kind: 'tool', seg: 2, toolCallId: 'g1', name: 'start_export', kind2: 'CONFIRM',
        status: 'pending', args: '{"notifyOnFail":true,"taskId":4}', timeoutSeconds: 120, requiresAction: true
      }
    ])
    const wrapper = await mountPanel([msg])
    const readOnlyRow = wrapper.find('[data-testid="ro-body-p1"] div > div')
    const readOnlyKey = readOnlyRow.findAll('span')[0]!
    expect(readOnlyKey.classes()).toEqual(expect.arrayContaining(['flex-[0_1_auto]', 'min-w-0', 'max-w-[40%]', 'truncate']))
    expect(readOnlyKey.classes()).not.toContain('flex-[0_0_72px]')
    expect(readOnlyKey.classes()).not.toContain('break-words')
    expect(readOnlyKey.attributes('title')).toBe(readOnlyKey.text()) // 长键截断后悬浮看全
    expect(readOnlyRow.findAll('span')[1]!.classes()).toEqual(expect.arrayContaining(['flex-1', 'min-w-0', 'break-words']))
    // 门卡字段表同族（原 58px 定宽 + break-words）
    const gateRow = wrapper.find('[data-testid="gate-rows"] div')
    const gateKey = gateRow.findAll('span')[0]!
    expect(gateKey.classes()).toEqual(expect.arrayContaining(['flex-[0_1_auto]', 'min-w-0', 'max-w-[40%]', 'truncate']))
    expect(gateKey.classes()).not.toContain('flex-[0_0_58px]')
    expect(gateKey.classes()).not.toContain('break-words')
    expect(gateKey.attributes('title')).toBe(gateKey.text())
    wrapper.unmount()
  })

  it('2A：只读卡徽标规格（9.5px / leading 1.7 / px-5 / 圆角 5，与工具卡胶囊同档）+ 复合终态文案', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'u1', name: 'start_publish', kind2: 'CONFIRM',
        status: 'failed', args: '{"taskId":3}', requiresAction: false, decision: 'approved'
      }
    ])
    const wrapper = await mountPanel([msg])
    const badge = wrapper.find('[data-testid="ro-badge-u1"]')
    expect(badge.text()).toBe('! 已批准 · 执行失败')
    // 统一右标签底座（与工具卡同档；描边承载状态色，不再用只读专用 semibold 皮肤）
    expect(badge.classes()).toEqual(expect.arrayContaining(['text-[9.5px]', 'leading-[1.7]', 'px-[5px]', 'rounded-[5px]', 'border', 'border-solid', 'flex-none']))
    expect(badge.classes()).not.toContain('font-semibold')
    expect(badge.attributes('data-state')).toBe('failed')
    expect(badge.classes()).toContain('text-[#c0392b]')
    // 非决策态（unknown）不进审批只读卡 —— 不渲染 ro 徽标（改由普通工具卡状态胶囊承接）
    const unknown = buildMessage([
      { kind: 'tool', seg: 0, toolCallId: 'u2', name: 'start_publish', kind2: 'CONFIRM', status: 'unknown', args: '{"taskId":3}', requiresAction: false }
    ])
    unknown.id = 'a-unknown'
    const w2 = await mountPanel([unknown])
    expect(w2.find('[data-testid="ro-badge-u2"]').exists()).toBe(false)
    expect(w2.find('[data-testid="tool-card-u2"]').exists()).toBe(true)
    w2.unmount()
    wrapper.unmount()
  })

  it('项6（aj10）：消息下外边距按上一条发送者 —— 同角色相邻 10px / 换人 18px', async () => {
    const mk = (id: string, role: ChatMessage['role']): ChatMessage => {
      const message = buildMessage([{ kind: 'text', seg: 0, text: `${id} 正文`, streaming: false }])
      message.id = id
      message.role = role
      return message
    }
    // 序列：user(user 相邻) assistant(assistant 相邻) user
    const wrapper = await mountPanel([
      mk('g0', 'user'), mk('g1', 'user'), mk('g2', 'assistant'), mk('g3', 'assistant'), mk('g4', 'user')
    ])
    const gap = (id: string) => (wrapper.find(`[data-testid="msg-ops-${id}"]`).element as HTMLElement).className
    expect(gap('g0')).toContain('mb-[18px]') // 首条无上一条 ⇒ 换人档
    expect(gap('g1')).toContain('mb-[10px]') // 同角色相邻 ⇒ 紧凑档
    expect(gap('g2')).toContain('mb-[18px]') // 换人
    expect(gap('g3')).toContain('mb-[10px]') // 同角色相邻
    expect(gap('g4')).toContain('mb-[18px]') // 换人
    expect(gap('g4')).not.toContain('mb-[10px]')
    wrapper.unmount()
  })

  it('项7（aj14d）：流式块 75ms 防抖（首帧立即 + 窗口内合并 + 静默满窗落地）+ 块级稳定 key 跨闭合不变', async () => {
    const mod = await import('@/utils/markdown')
    expect(mod.STREAM_DEBOUNCE_MS).toBe(75)
    // ③ 块级稳定 key = 块序号（本次渲染的出现序）+ 语言；流式未闭合阶段与闭合之后同值
    const openFence = mod.renderMarkdown('```js\nconst a = 1', true, 'k-0').html
    const closedFence = mod.renderMarkdown('```js\nconst a = 1\n```\n\n完成', false, 'k-0').html
    expect(openFence).toContain('data-fence-key="f1-js"')
    expect(closedFence).toContain('data-fence-key="f1-js"') // 尾块被剥 language class ⇒ key 仍不变
    const multi = mod.renderMarkdown('```ts\nconst x = 1\n```\n\n```js\nconst y = 2\n```\n', false).html
    expect(multi).toContain('data-fence-key="f1-ts"')
    expect(multi).toContain('data-fence-key="f2-js"')
    // mermaid 围栏同源带 key（面板 hydrate 按该 key 绑槽位，位置下标只作兜底）
    const mermaidFence = mod.renderMarkdown('```mermaid\nflowchart TD\nA-->B\n```\n', false).html
    expect(mermaidFence).toContain('data-fence-key="f1-mermaid"')
    // ② 防抖：首帧立即落地；窗口内的增量合并为一次（沿用上一帧 HTML）
    mod.resetStreamDebounce()
    const flushes: number[] = []
    mod.setStreamFlushHandler(() => flushes.push(1))
    const first = mod.renderMarkdown('第一段', true, 'k-deb')
    expect(first.html).toContain('第一段')
    const merged = mod.renderMarkdown('第一段 + 第二段', true, 'k-deb')
    expect(merged.html).toBe(first.html) // 同一份结果：窗口内未重绘
    expect(merged.html).not.toContain('第二段')
    await new Promise((resolve) => setTimeout(resolve, mod.STREAM_DEBOUNCE_MS + 25))
    expect(flushes.length).toBeGreaterThan(0) // 静默满窗 ⇒ flush 回调落地（上游停吐也不丢末帧）
    const landed = mod.renderMarkdown('第一段 + 第二段', true, 'k-deb')
    expect(landed.html).toContain('第二段') // 窗口外：真渲染
    // 窗口外首帧对照：不同块互不影响（防抖记账按块 key 独立）
    expect(mod.renderMarkdown('另一块', true, 'k-other').html).toContain('另一块')
    mod.setStreamFlushHandler(null)
    mod.resetStreamDebounce()

    // 组件装配面：面板挂载即注册 flush 回调；75ms 窗口内的增量不重绘（DOM 不进中间态），
    // 静默满窗后一次落地到最新增量（内容不丢）。时钟钉住 ⇒ 窗口判据确定性（不靠机器快慢）。
    const streamMsg = buildMessage([{ kind: 'text', seg: 0, text: '首帧输出', streaming: true }], null, true)
    streamMsg.id = 's-deb'
    const before = streamFlush.handlers.length
    const nowSpy = vi.spyOn(performance, 'now').mockReturnValue(1_000)
    try {
      const wrapper = await mountPanel([streamMsg])
      expect(streamFlush.handlers.length).toBeGreaterThan(before)
      const bubble = () => wrapper.find('[data-testid="msg-bubble-s-deb"]')
      expect(bubble().text()).toContain('首帧输出')
      // 经 store 的响应式代理写回增量（直接改本地对象不触发响应式依赖）
      const live = useAiStore().messages[0]!.parts[0]!
      if (live.kind === 'text') {
        live.text = '首帧输出 + 增量'
      }
      await wrapper.vm.$nextTick()
      expect(bubble().text()).toContain('首帧输出') // 窗口内：沿用上一帧（不重绘到中间态）
      expect(bubble().text()).not.toContain('增量')
      nowSpy.mockReturnValue(2_000) // 推过 75ms 窗口；flush 回调落地
      await new Promise((resolve) => setTimeout(resolve, mod.STREAM_DEBOUNCE_MS + 50))
      await wrapper.vm.$nextTick()
      expect(bubble().text()).toContain('首帧输出 + 增量')
      wrapper.unmount()
    } finally {
      nowSpy.mockRestore()
    }
  })
})

/**
 * 问题4 composer v2 阶段二：store `send()` 的 outcome / userMessageId 映射。
 * 走真实 store + `@/api/ai` 桩，锁住空/loading 早退、ok、开流前 error、断流 interrupted、主动 stop cancelled。
 */
describe('问题4 composer v2 阶段二：store send outcome 映射', () => {
  beforeEach(() => {
    api.chat.mockReset()
  })

  it('空内容 / loading 早退 = not-sent + null（不发请求）', async () => {
    const store = useAiStore()
    await expect(store.send('   ')).resolves.toEqual({ outcome: 'not-sent', userMessageId: null })
    expect(api.chat).not.toHaveBeenCalled()

    store.loading = true
    await expect(store.send('x')).resolves.toEqual({ outcome: 'not-sent', userMessageId: null })
    expect(api.chat).not.toHaveBeenCalled()
    // 批G · G6（用户直裁 2026-10-22）：在途轮里的发送**零提示**（草稿保留、不发请求）
    expect(store.notice).toBeNull()
  })

  it('正常 ok：返回新用户消息 id', async () => {
    const store = useAiStore()
    api.chat.mockResolvedValue(undefined)
    const result = await store.send('正常消息')
    expect(result.outcome).toBe('ok')
    expect(result.userMessageId).not.toBeNull()
    expect(result.userMessageId).toBe(store.messages[0]?.id)
    expect(store.messages[0]?.role).toBe('user')
    expect(store.loading).toBe(false)
  })

  it('开流前 error：返回 error + 已记录的用户消息 id', async () => {
    const store = useAiStore()
    api.chat.mockRejectedValueOnce(new Error('boom'))
    const result = await store.send('失败消息')
    expect(result.outcome).toBe('error')
    expect(result.userMessageId).toBe(store.messages[0]?.id)
    expect(store.loading).toBe(false)
  })

  it('断流 interrupted：onClose terminal=false 映射 interrupted', async () => {
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: { onClose?: (info: { terminal: boolean; reconnects: number; reason?: string }) => void }
    ) => {
      handlers?.onClose?.({ terminal: false, reconnects: 0, reason: '断流' })
    })
    const result = await store.send('断流消息')
    expect(result.outcome).toBe('interrupted')
    expect(result.userMessageId).toBe(store.messages[0]?.id)
    expect(store.streamInterrupted).toBe(true)
  })

  it('非终态断流：activeForm 保留（挂起表单活着，重挂/刷新后可继续）', async () => {
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-gf', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'frontend_tool_request',
        runId: 'run-gf',
        toolCallId: 'gf-keep',
        name: 'generative_form',
        args: JSON.stringify({ form: { scenario: 'demo', fields: [{ key: 'k', label: 'K', type: 'text' }] } }),
        seq: 2
      })
      handlers?.onClose?.({ terminal: false, reconnects: 2, reason: '重挂耗尽' })
    })
    const result = await store.send('填写表单')
    expect(result.outcome).toBe('interrupted')
    // 断流不是结局：表单态与挂起段都原样保留
    expect(store.activeForm?.toolCallId).toBe('gf-keep')
    expect(store.activeForm?.status).toBe('filling')
    const part = store.messages
      .flatMap((message) => message.parts)
      .find((item): item is ToolPart => item.kind === 'tool' && item.toolCallId === 'gf-keep')
    expect(part?.status).toBe('pending')
    expect(part?.requiresAction).toBe(true)
  })

  it('非终态断流：确认卡待决态保留（段 pending/requiresAction + 挂起投影仍在）', async () => {
    const store = useAiStore()
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: unknown) => void
        onClose?: (info: { terminal: boolean; reconnects: number; reason?: string }) => void
      }
    ) => {
      handlers?.onFrame?.({ type: 'start', runId: 'run-c', sessionId: store.sessionId, seq: 1 })
      handlers?.onFrame?.({
        type: 'confirm_request',
        runId: 'run-c',
        toolCallId: 'c-keep',
        name: 'start_publish',
        args: '{"taskId":1}',
        seq: 2
      })
      handlers?.onClose?.({ terminal: false, reconnects: 2, reason: '重挂耗尽' })
    })
    const result = await store.send('发布')
    expect(result.outcome).toBe('interrupted')
    expect(store.pendingConfirms.map((item) => item.toolCallId)).toEqual(['c-keep'])
    expect(store.pendingToolCall?.toolCallId).toBe('c-keep')
    const part = store.messages
      .flatMap((message) => message.parts)
      .find((item): item is ToolPart => item.kind === 'tool' && item.toolCallId === 'c-keep')
    expect(part?.status).toBe('pending')
    expect(part?.requiresAction).toBe(true)
  })

  it('error 终帧正常 resolve：映射 error（不被 ok 覆盖）', async () => {
    const store = useAiStore()
    const frame = {
      type: 'error' as const,
      runId: 'run-terminal-error',
      code: 'UPSTREAM_STREAM_ERROR',
      message: '上游失败'
    }
    api.chat.mockImplementation(async (
      _request: unknown,
      handlers?: {
        onFrame?: (frame: { type: 'error'; runId: string; code: string; message: string }) => void
      }
    ) => {
      handlers?.onFrame?.(frame)
      return { terminal: true, lastFrame: frame, reconnects: 0 }
    })
    const result = await store.send('终帧错误')
    expect(result.outcome).toBe('error')
    expect(result.userMessageId).toBe(store.messages[0]?.id)
    expect(store.lastRoundOutcome).toBe('error')
  })

  it('主动 stop：映射 cancelled', async () => {
    const store = useAiStore()
    api.chat.mockImplementation((_request: unknown, _handlers?: unknown, options?: { signal?: AbortSignal }) =>
      new Promise<never>((_resolve, reject) => {
        options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    )
    const pending = store.send('停止我')
    await Promise.resolve()
    await store.stop()
    const result = await pending
    expect(result.outcome).toBe('cancelled')
    expect(result.userMessageId).toBe(store.messages[0]?.id)
  })

  it('skipUserRecord：不记录用户消息，userMessageId=null', async () => {
    const store = useAiStore()
    api.chat.mockResolvedValue(undefined)
    const result = await store.send('编辑重跑', { skipUserRecord: true })
    expect(result).toEqual({ outcome: 'ok', userMessageId: null })
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(0)
  })
})

/**
 * AI 表格操作专项 T-17（设计稿 §3.5）：刷新后写工具没有本地 plan 时，
 * 不得渲染点得动的“应用”卡 —— 必须收口成“预览已失效 + 放弃”。
 */
describe('AI 表格操作专项 T-17：写预览段的无 plan 收口', () => {
  it('待决写工具且无本地 plan ⇒ “预览已失效”+ 放弃（无“应用”），放弃提交 cancelled=true', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        {
          kind: 'tool',
          seg: 1,
          toolCallId: 't1',
          name: 'replace_values',
          kind2: 'FRONTEND',
          status: 'pending',
          args: '{"defCode":"DEMO"}',
          requiresAction: true
        }
      ])
    ])
    const store = useAiStore()
    store.activeRunId = 'run-1'
    store.stalePreviewToolCallId = 't1'
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('预览已失效')
    expect(wrapper.find('[data-testid="sheet-preview-apply"]').exists()).toBe(false)

    await wrapper.find('[data-testid="sheet-preview-abandon"]').trigger('click')
    await flushPromises()
    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({ cancelled: true }))
    wrapper.unmount()
  })
})

/**
 * S6 批 2 · 发布冲突卡改由 `ResultViewRenderer` 渲表格（设计稿 §5.3 / §7.1-③）。
 *
 * 判据：
 * - 段上有 `resultView` ⇒ 表格来自渲染器（`resultview-table`），**旧反解表（`conflict-table`）零出现**；
 * - 红字行只留首行摘要（`reasonHeadline`）—— 明细不同屏重复；
 * - 段上没有 `resultView`（旧镜像 / 旧归档）⇒ 旧反解路径照旧（回退路径不删）。
 */
describe('S6 批2 · resultView 渲染（发布冲突首个迁移对象）', () => {
  const CONFLICT_VIEW = {
    version: '1.0',
    id: 'rv-tc-1',
    revision: 1,
    kind: 'table' as const,
    title: 'i18n:resultview.publishConflict.title',
    status: 'ok' as const,
    data: {
      inline: {
        rows: [
          { defCode: 'CURRENCY', rowKey: 'GLOBAL', message: '币种精度与主数据不一致' },
          { defCode: 'TAX_RULE', rowKey: 'PRJ-07', message: '税率区间重叠' }
        ]
      }
    },
    columns: [
      { key: 'defCode', type: 'string' as const, label: 'i18n:resultview.publishConflict.col.defCode', width: 4 },
      { key: 'rowKey', type: 'string' as const, label: 'i18n:resultview.publishConflict.col.rowKey', width: 3 },
      { key: 'message', type: 'string' as const, label: 'i18n:resultview.publishConflict.col.message', width: 5 }
    ],
    a11y: { label: 'i18n:resultview.publishConflict.label', role: 'table' },
    fallback: { text: 'i18n:resultview.publishConflict.fallback', format: 'plain' as const }
  }
  const REASON = [
    '发布作业 #317 校验冲突明细（共 2 处）',
    '1. CURRENCY#GLOBAL：币种精度与主数据不一致',
    '2. TAX_RULE#PRJ-07：税率区间重叠'
  ].join('\n')

  it('有 resultView ⇒ 渲染器表格 + 旧反解表零出现 + 红字行只留首行', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'rv1', name: 'start_publish', kind2: 'BACKEND',
        status: 'failed', args: '{"taskId":291}', result: '发布作业已启动（作业 #317）…',
        reason: REASON, requiresAction: false, resultView: CONFLICT_VIEW
      }
    ])
    msg.id = 'a-rv1'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-rv1:rv1"]').trigger('click')

    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(true)
    // §5.3-4 / §7.2-6：有视图时旧反解表不出现（不同屏双份报同一明细 = 违规）
    expect(wrapper.find('[data-testid="conflict-table"]').exists()).toBe(false)
    const err = wrapper.find('[data-testid="tool-error"]')
    expect(err.text()).toContain('发布作业 #317 校验冲突明细（共 2 处）')
    expect(err.text()).not.toContain('CURRENCY')
    expect(wrapper.find('[data-testid="resultview-table"]').text()).toContain('币种精度与主数据不一致')
    wrapper.unmount()
  })

  it('无 resultView ⇒ 旧反解路径照旧（回退路径保留）', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'rv2', name: 'start_publish', kind2: 'BACKEND',
        status: 'failed', args: '{"taskId":291}', result: '发布作业已启动（作业 #317）…',
        reason: REASON, requiresAction: false
      }
    ])
    msg.id = 'a-rv2'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-rv2:rv2"]').trigger('click')

    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="conflict-table"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

/**
 * S6 批2 迁移步④（设计稿 §7.1-④）：**普通工具卡的「输出」区**改由通用渲染器接管。
 *
 * 判据（每条都对应一个「旧形态是否还在」的显式断言，不靠"页面能打开"兜底）：
 * - 有**可渲**视图 ⇒ 表格来自渲染器，**旧原文盒（`tool-output-text`）零出现**（同一份真值不同屏报两次 = N8 违规）；
 * - 无视图 / 视图渲不出（未知 kind、`data.ref` 指不到 JSON、`status=error`）⇒ 旧原文盒照旧，
 *   视图位渲染 `fallback`（不白屏、不抛错）；回退路径**不删**（旧镜像 / 旧归档的段没有视图）。
 *
 * 首个走通的后端工具 = `list_tasks`（后端 `ListTasksResultViewProvider` 认领，行值来自服务层取数）。
 */
describe('S6 批2 迁移步④ · 工具卡输出区由 resultView 接管（list_tasks 首个）', () => {
  const LIST_TASKS_VIEW = {
    version: '1.0',
    id: 'rv-lt-1',
    revision: 1,
    kind: 'table' as const,
    title: 'i18n:resultview.listTasks.title',
    status: 'ok' as const,
    data: {
      inline: {
        rows: [
          { id: 7, type: 'EXPORT', title: '十月导出', step: '导出中', status: 'RUNNING' },
          { id: 9, type: 'IMPORT', title: '汇率导入', step: '待执行', status: 'PENDING' }
        ]
      }
    },
    columns: [
      { key: 'id', type: 'integer' as const, label: 'i18n:resultview.listTasks.col.id', width: 1 },
      { key: 'type', type: 'string' as const, label: 'i18n:resultview.listTasks.col.type', width: 2 },
      { key: 'title', type: 'string' as const, label: 'i18n:resultview.listTasks.col.title', width: 5 },
      { key: 'step', type: 'string' as const, label: 'i18n:resultview.listTasks.col.step', width: 2 },
      { key: 'status', type: 'string' as const, label: 'i18n:resultview.listTasks.col.status', width: 2 }
    ],
    fallback: { text: 'i18n:resultview.listTasks.fallback', format: 'plain' as const },
    meta: { catalogId: 'configmgr/resultview/v1', source: 'list_tasks' }
  }
  /** 后端 `AiTools#listTasks` 的文本投影（模型侧那份）：若卡面同时渲它 = 同一份真值报两次。 */
  const LIST_TASKS_RESULT = [
    '共 2 条任务（按创建时间倒序）',
    '- #7 [EXPORT] 十月导出（导出中）',
    '- #9 [IMPORT] 汇率导入（待执行）'
  ].join('\n')

  function listTasksPart(extra: Partial<ToolPart> = {}): ToolPart {
    return {
      kind: 'tool', seg: 0, toolCallId: 'lt1', name: 'list_tasks', kind2: 'BACKEND',
      status: 'succeeded', args: '{}', result: LIST_TASKS_RESULT, requiresAction: false,
      ...extra
    }
  }

  it('有可渲视图 ⇒ 表格来自渲染器（只渲一次），旧原文盒零出现', async () => {
    const msg = buildMessage([listTasksPart({ resultView: LIST_TASKS_VIEW as never })])
    msg.id = 'a-lt1'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-lt1:lt1"]').trigger('click')

    const tables = wrapper.findAll('[data-testid="resultview-table"]')
    expect(tables).toHaveLength(1)
    expect(tables[0].text()).toContain('十月导出')
    expect(tables[0].text()).toContain('汇率导入')
    // §5.3-4 / §7.2-6 的同源负断言在"输出区"这一层的落法：原文盒零出现
    expect(wrapper.find('[data-testid="tool-output-text"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('- #7 [EXPORT]')
    wrapper.unmount()
  })

  it('无视图（旧镜像 / 未认领工具）⇒ 旧原文盒照旧，不出现空表', async () => {
    const msg = buildMessage([listTasksPart()])
    msg.id = 'a-lt2'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-lt2:lt1"]').trigger('click')

    const out = wrapper.find('[data-testid="tool-output-text"]')
    expect(out.exists()).toBe(true)
    expect(out.text()).toContain('- #7 [EXPORT] 十月导出（导出中）')
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="resultview-fallback"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('视图渲不出（data.ref 指不到 JSON）⇒ 原文盒保留 + 视图位渲 fallback（不白屏）', async () => {
    const msg = buildMessage([listTasksPart({
      resultView: { ...LIST_TASKS_VIEW, id: 'rv-lt-3', data: { ref: '/rows' } } as never
    })])
    msg.id = 'a-lt3'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-lt3:lt1"]').trigger('click')

    expect(wrapper.find('[data-testid="tool-output-text"]').text()).toContain('- #9 [IMPORT]')
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    const fallback = wrapper.find('[data-testid="resultview-fallback"]')
    expect(fallback.exists()).toBe(true)
    expect(fallback.text()).toContain('任务列表无法以表格展示')
    wrapper.unmount()
  })

  it('status=error（视图构造失败但工具成功）⇒ 原文盒保留（原文比一句错误更可信）', async () => {
    const msg = buildMessage([listTasksPart({
      resultView: {
        ...LIST_TASKS_VIEW,
        id: 'rv-lt-4',
        status: 'error' as const,
        error: { code: 'FRAME_PAYLOAD_INVALID', message: '视图数据无法解析' }
      } as never
    })])
    msg.id = 'a-lt4'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-lt4:lt1"]').trigger('click')

    expect(wrapper.find('[data-testid="tool-output-text"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('零任务（真实帧载荷 2026-10-22 实调）⇒ 空态文案不得读成"无法以表格展示"', async () => {    // 逐字取自运行中后端的一次真调 tool_result.resultView（未改一字，作为契约形状对账样本）
    const REAL_EMPTY_VIEW = {
      version: '1.0',
      id: 'rv-call_00_wbyosyxrCozTmlXF3lvi6883',
      revision: 1,
      kind: 'table' as const,
      title: 'i18n:resultview.listTasks.title',
      status: 'empty' as const,
      data: { inline: { rows: [] } },
      columns: [
        { key: 'id', type: 'integer' as const, label: 'i18n:resultview.listTasks.col.id', width: 1 },
        { key: 'type', type: 'string' as const, label: 'i18n:resultview.listTasks.col.type', width: 2 },
        { key: 'title', type: 'string' as const, label: 'i18n:resultview.listTasks.col.title', width: 5 },
        { key: 'step', type: 'string' as const, label: 'i18n:resultview.listTasks.col.step', width: 2 },
        { key: 'status', type: 'string' as const, label: 'i18n:resultview.listTasks.col.status', width: 2 }
      ],
      empty: { text: 'i18n:resultview.listTasks.fallback' },
      a11y: { label: 'i18n:resultview.listTasks.title', role: 'table' },
      fallback: { text: 'i18n:resultview.listTasks.fallback', format: 'plain' as const },
      meta: { catalogId: 'configmgr/resultview/v1', source: 'list_tasks' }
    }
    const msg = buildMessage([listTasksPart({
      resultView: REAL_EMPTY_VIEW as never,
      result: '当前没有任务，可以创建一个新任务'
    })])
    msg.id = 'a-lt5'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-lt5:lt1"]').trigger('click')

    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(false)
    const empty = wrapper.find('[data-testid="resultview-empty"]')
    expect(empty.exists()).toBe(true)
    // 空 ≠ 失败：该帧的 empty.text 复用了失败出口文案，归一化后必须是中性空态
    expect(empty.text()).toBe('没有可展示的记录')
    expect(wrapper.text()).not.toContain('无法以表格展示')
    wrapper.unmount()
  })

  /**
   * 语义变更（单列）：视图接管输出后，**原文盒在冲突卡上也不再出现** —— 判据只有一条
   * （视图是否可渲），不按工具分叉。红字行仍只留首行摘要（§5.3-1），明细只由表格承载。
   */
  it('发布冲突卡：有视图时原文盒让位，红字行仍是首行摘要（单一判据、不按工具分叉）', async () => {
    const msg = buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 'rv3', name: 'start_publish', kind2: 'BACKEND',
        status: 'failed', args: '{"taskId":291}', result: '发布作业已启动（作业 #317）…',
        reason: '发布作业 #317 校验冲突明细（共 1 处）\n1. CURRENCY#GLOBAL：币种精度与主数据不一致',
        requiresAction: false,
        resultView: {
          version: '1.0', id: 'rv-rv3', revision: 1, kind: 'table' as const, status: 'ok' as const,
          data: { inline: { rows: [{ defCode: 'CURRENCY', rowKey: 'GLOBAL', message: '币种精度与主数据不一致' }] } },
          columns: [
            { key: 'defCode', type: 'string' as const },
            { key: 'rowKey', type: 'string' as const },
            { key: 'message', type: 'string' as const }
          ],
          fallback: { text: 'i18n:resultview.publishConflict.fallback', format: 'plain' as const }
        } as never
      }
    ])
    msg.id = 'a-rv3'
    const wrapper = await mountPanel([msg])
    await wrapper.find('[data-testid="block-toggle-a-rv3:rv3"]').trigger('click')

    expect(wrapper.find('[data-testid="resultview-table"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="conflict-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="tool-output-text"]').exists()).toBe(false)
    const err = wrapper.find('[data-testid="tool-error"]')
    expect(err.text()).toContain('校验冲突明细（共 1 处）')
    expect(err.text()).not.toContain('CURRENCY')
    wrapper.unmount()
  })
})

/**
 * 批4-D（用户 2026-10-23 裁决）：**不可撤销** = 确认卡 + 复选框。
 *
 * 口径逐条：
 * - 普通后果确认卡（start_export / start_precheck / start_import）**维持现状**：无复选框、批准立即可点；
 * - 不可撤销（`irreversible=true`，后端 `ToolMeta.Category.P` 透出）才叠加复选框；
 * - 复选框文案动态带动作名（`tool.<name>.title` 中文 / `ai.hitl.action.<tool>` 英文动名词，缺键回落）；
 * - 未勾选 ⇒ **只禁用「批准」**，「拒绝」永远可用；置灰旁给一行提示；
 * - 复选框恒可见（不放进字段内滚区）、默认不勾、变更即复位；
 * - 刷新恢复（runs/current 的 `irreversible`）要能把复选框重建出来且仍是未勾选态；
 * - store 层兜底（纵深两道）：未勾选直接调 `confirm()` 也不得发出请求。
 */
describe('批4-D：不可撤销确认卡的复选框（可逆卡一字不动）', () => {
  /** 不可撤销门卡段（后端 start_publish = 唯一 Category.P）。 */
  function irreversiblePart(toolCallId = 'pub1', name = 'start_publish'): ToolPart {
    return {
      kind: 'tool', seg: 0, toolCallId, name, kind2: 'CONFIRM', status: 'pending',
      args: '{"taskId":9}', timeoutSeconds: 120, requiresAction: true, irreversible: true
    }
  }

  it('未勾选：批准禁用 / 拒绝可用 / 提示可见 / 复选框在字段内滚区之外且默认不勾', async () => {
    const wrapper = await mountPanel([buildMessage([irreversiblePart()])])
    const gate = wrapper.find('[data-testid="pending-card-pub1"]').find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)

    // 复选框：≤ 原生 input[type=checkbox]，默认不勾，恒可见（不在 gate-rows 内滚区里）
    const box = gate.find('[data-testid="gate-ack-box"]')
    expect(box.exists()).toBe(true)
    expect((box.element as HTMLInputElement).checked).toBe(false)
    const rows = gate.find('[data-testid="gate-rows"]')
    expect(rows.element.contains(box.element)).toBe(false)

    // 文案：start_publish 必须点名“若本次为整体替换，范围内旧数据会被删除”（不依赖本地模式）
    const ack = gate.find('[data-testid="gate-ack"]')
    expect(ack.text()).toContain('确认启动发布作业：若本次为整体替换，范围内旧数据将被删除，执行后无法恢复')
    // 字形面零 emoji（同卡面锁口径）
    expect(ack.text()).not.toMatch(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]/u)

    // 按钮：只禁用「批准」，「拒绝」永远可用
    const buttons = gate.findAll('button')
    expect(buttons.map((btn) => btn.text())).toEqual(['拒绝', '批准'])
    expect(buttons[1]!.attributes('disabled')).toBeDefined()
    expect(buttons[0]!.attributes('disabled')).toBeUndefined()

    // 置灰旁一行提示
    const hint = gate.find('[data-testid="gate-ack-hint"]')
    expect(hint.exists()).toBe(true)
    expect(hint.text()).toBe('请先勾选上方确认项')

    // 未勾选点击批准：不发任何请求（按钮禁用 + store 兜底两道）
    await buttons[1]!.trigger('click')
    await flushPromises()
    expect(api.confirm).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('勾选后可批准：提示消失、批准可用、点击提交 approved', async () => {
    const wrapper = await mountPanel([buildMessage([irreversiblePart()])])
    const gate = wrapper.find('[data-testid="pending-card-pub1"]').find('[data-testid="hitl-gate"]')
    const store = useAiStore()
    store.activeRunId = 'run-1'
    store.suspended = true

    const box = gate.find('[data-testid="gate-ack-box"]')
    await box.setValue(true)

    expect((box.element as HTMLInputElement).checked).toBe(true)
    const buttons = gate.findAll('button')
    expect(buttons[1]!.attributes('disabled')).toBeUndefined()
    expect(gate.find('[data-testid="gate-ack-hint"]').exists()).toBe(false)

    await buttons[1]!.trigger('click')
    await flushPromises()
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0]).toMatchObject({ runId: 'run-1', toolCallId: 'pub1', approved: true })
    wrapper.unmount()
  })

  it('可逆确认卡（start_export）维持现状：无复选框、无提示、批准立即可点', async () => {
    const wrapper = await mountPanel([
      buildMessage([
        { kind: 'tool', seg: 0, toolCallId: 'c1', name: 'start_export', kind2: 'CONFIRM', status: 'pending', args: '{"taskId":1}', timeoutSeconds: 120, requiresAction: true }
      ])
    ])
    const gate = wrapper.find('[data-testid="pending-card-c1"]').find('[data-testid="hitl-gate"]')
    expect(gate.find('[data-testid="gate-ack-box"]').exists()).toBe(false)
    expect(gate.find('[data-testid="gate-ack-row"]').exists()).toBe(false)
    expect(gate.find('[data-testid="gate-ack-hint"]').exists()).toBe(false)
    // 卡体子块序不变：字段表 / 按钮行（复选框不占位）
    const kids = Array.from(gate.element.children) as HTMLElement[]
    expect(kids[0]!.getAttribute('data-testid')).toBe('gate-rows')
    expect(kids[1]!.querySelectorAll('button')).toHaveLength(2)
    const buttons = gate.findAll('button')
    expect(buttons[1]!.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('刷新恢复：runs/current 带 irreversible → 重建复选框且仍未勾选（批准禁用）', async () => {
    api.reattachRun.mockImplementation(() => new Promise(() => {}))
    api.history.mockResolvedValue({ sessionId: 'sess-pub', count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-pub',
      snapshotStatus: 'PENDING',
      pendingEntries: [
        { toolCallId: 'rpub1', entryStatus: 'PENDING', kind: 'CONFIRM', name: 'start_publish', arguments: '{"taskId":9}', req: true, irreversible: true }
      ],
      unresolvedExternal: ['rpub1'],
      awaitingExternal: true,
      archiveMaxSeq: 2
    })
    const store = useAiStore()
    store.sessionId = 'sess-pub'
    const wrapper = await mountPanel([])
    await flushPromises()

    const gate = wrapper.find('[data-testid="pending-card-rpub1"]').find('[data-testid="hitl-gate"]')
    expect(gate.exists()).toBe(true)
    const box = gate.find('[data-testid="gate-ack-box"]')
    expect(box.exists()).toBe(true)
    expect((box.element as HTMLInputElement).checked).toBe(false)
    const buttons = gate.findAll('button')
    expect(buttons[1]!.attributes('disabled')).toBeDefined()
    expect(gate.find('[data-testid="gate-ack-hint"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('双 locale：英文用动名词短语（ai.hitl.action.<tool>），提示行同批切换', async () => {
    const { default: i18n } = await import('@/locales')
    i18n.global.locale.value = 'en-US'
    try {
      const wrapper = await mountPanel([buildMessage([irreversiblePart('pub-en')])])
      const gate = wrapper.find('[data-testid="pending-card-pub-en"]').find('[data-testid="hitl-gate"]')
      expect(gate.find('[data-testid="gate-ack"]').text()).toBe(
        'Confirm starting the publish job: if this run uses replace mode, old data in scope will be deleted and cannot be undone'
      )
      expect(gate.find('[data-testid="gate-ack-hint"]').text()).toBe('Tick the box above to continue')
      // 批3二 S3：卡面按钮文案随 locale（zh 逐字不变；en 走 ai.panel.reject / approve）
      expect(gate.findAll('button').map((btn) => btn.text())).toEqual([
        i18n.global.t('ai.panel.reject'),
        i18n.global.t('ai.panel.approve')
      ])
      expect(gate.findAll('button').map((btn) => btn.text())).toEqual(['Reject', 'Approve'])
      wrapper.unmount()
    } finally {
      i18n.global.locale.value = 'zh-CN'
    }
  })

  it('store 兜底（纵深两道）：段未勾选时直接调 confirm(true) 不发请求，勾选后才发', async () => {
    const store = useAiStore()
    store.messages = [buildMessage([irreversiblePart('pub2')])]
    store.historyLoaded = true
    store.activeRunId = 'run-2'
    store.suspended = true

    await store.confirm(true, undefined, 'pub2')
    expect(api.confirm).not.toHaveBeenCalled()
    expect(store.notice).toContain('勾选')

    store.ackGate('pub2')
    await store.confirm(true, undefined, 'pub2')
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm.mock.calls[0]![0]).toMatchObject({ toolCallId: 'pub2', approved: true })
  })

  it('变更即复位：confirm_decision 到达后勾选态清空（同 toolCallId 再审不会带着旧勾选）', async () => {
    const store = useAiStore()
    store.messages = [buildMessage([irreversiblePart('pub3')])]
    store.historyLoaded = true
    store.activeRunId = 'run-3'
    store.suspended = true

    store.ackGate('pub3')
    expect(store.gateAckOf('pub3')).toBe(true)

    store.handleFrame({
      type: 'confirm_decision', seq: 1, atMs: 1_790_000_000_000,
      toolCallId: 'pub3', name: 'start_publish', decision: 'rejected', approved: false, status: 'REJECTED'
    } as never)
    expect(store.gateAckOf('pub3')).toBe(false)
  })
})
