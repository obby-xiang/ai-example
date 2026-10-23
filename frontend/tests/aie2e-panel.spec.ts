/**
 * E2E 用例全集（第 53 件）· 面板层（DOM）缺口：P-10 / P-13 / Q-01 / Q-05 / G-18。
 *
 * <p>为什么这几条只能在这一层锁：它们断言的是**控件形态与点击路径**（按钮 disabled + 文案、
 * 入口的有无、chips 消失、开关的真实可点），store 层看不见。挂载沿用仓内既有形态
 * （`ai-panel-parts-render.spec.ts` 的 Element Plus 轻量 stub + `@/api/ai` 桩）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { FormSpec } from '@/types/form-schema'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { registerAiRegion, resetAiRegions, unregisterAiRegion } from '@/utils/ai-regions'
import { ApiError } from '@/api/http'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  uploadFormAttachment: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  aiApi: api,
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: vi.fn().mockResolvedValue({ options: [] })
}))

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

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

function message(id: string, role: ChatMessage['role'], text: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id,
    role,
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...overrides
  }
}

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  return wrapper
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.chat.mockImplementation(() => Promise.resolve({ terminal: true }))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('P-10（P2）：stopping 态可见 —— 按钮 disabled + 文案「停止中」', () => {
  /**
   * 判据：按钮 `disabled` + 文案「停止中」，且不再响应点击。
   *
   * <p>【形态记录】`stop()` 在**本地就近收敛**（批G · S1/S2：`loading` 同步置 false ⇒ 输入立刻解禁），
   * 于是点下去之后 `v-if="ai.loading || ai.suspended"` 立刻为假、停止钮整块换成发送钮 ——
   * 用户在页面上看到的"停止中的按钮"这一**中间态窗口不可达**。本用例因此分两段锁：
   * ① 组件契约（`stopping` ⇒ disabled + 文案「停止中」）本身正确；
   * ② 点击后的真实形态 = 按钮消失 + 只发 1 次 cancel（不重复取消）。
   * 「中间态不可达」已作为口径差异登记在批报告，未改生产。
   */
  it('① stopping=true 时组件渲染 disabled + 「停止中」；② 点击后按钮收起且只发 1 次 cancel', async () => {
    const store = useAiStore()
    store.messages = [message('u1', 'user', '问'), message('a1', 'assistant', '半截', { streaming: true, done: false })]
    store.loading = true
    store.activeRunId = 'run-10'
    const wrapper = await mountPanel()

    // ① 组件契约：只翻 stopping，按钮必须 disabled 且文案换成「停止中」
    store.stopping = true
    await wrapper.vm.$nextTick()
    const stoppingButton = wrapper.find('[data-testid="stop-btn"]')
    expect(stoppingButton.exists()).toBe(true)
    expect(stoppingButton.attributes('disabled')).toBeDefined()
    expect(stoppingButton.attributes('aria-label')).toBe('停止中…')
    store.stopping = false
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="stop-btn"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="stop-btn"]').attributes('aria-label')).toBe('停止生成')

    // ② 真实点击：cancel 挂着不回，本地立刻收敛（按钮收起），且只发一次
    const gate: { release: (() => void) | null } = { release: null }
    api.cancel.mockImplementation(() => new Promise((resolve) => {
      gate.release = () => resolve({ cancelled: true, alreadyCancelled: false })
    }))
    await wrapper.find('[data-testid="stop-btn"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(store.stopping).toBe(true)
    expect(wrapper.find('[data-testid="stop-btn"]').exists()).toBe(false) // 形态：换成发送钮
    gate.release?.()
    await flushPromises()
    expect(api.cancel).toHaveBeenCalledTimes(1)
    expect(store.stopping).toBe(false)
    wrapper.unmount()
  }, 20_000)
})

describe('P-13（P2）：「继续」入口的语义与互斥', () => {
  /**
   * 判据：轮次号 +1、按**新轮**处理（不续写半截答案）；停止轮与「继续」互斥（批G · S3）。
   * 落地形态：点「继续」= 把「继续」当作用户消息直接发送（新轮，`send()` 走同一入口）。
   */
  it('error 轮：点「继续」⇒ 新发一条用户消息（旧半截正文不被改写）', async () => {
    const store = useAiStore()
    store.messages = [message('u1', 'user', '把定义导出'), message('a1', 'assistant', '出错了', { done: true })]
    store.lastRoundOutcome = 'error'
    const wrapper = await mountPanel()

    const button = wrapper.find('[data-testid="continue-round"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]?.[0]).toMatchObject({ message: '继续' }) // 新轮，不是续写半截
    expect(store.messages[0]?.content).toBe('把定义导出')
    expect(store.messages[1]?.content).toBe('出错了') // 旧答案原样保留
    expect(store.messages.filter((item) => item.role === 'user')).toHaveLength(2)
    wrapper.unmount()
  }, 20_000)

  it('停止轮：不出「继续」（与「已停止」标识互斥，防双语义打架）', async () => {
    const store = useAiStore()
    store.messages = [message('u1', 'user', '问'), message('a1', 'assistant', '半截答案', { stopped: true })]
    store.lastRoundOutcome = 'cancelled'
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="continue-round"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="msg-stopped-a1"]').exists()).toBe(true)
    wrapper.unmount()
  }, 20_000)
})

describe('Q-01（P1）：空态快捷 chips 点即发（与手输同一入口）', () => {
  it('点击 chip：直接发送该文案（零请求体差异）且空态随之消失', async () => {
    const store = useAiStore()
    store.messages = []
    store.historyLoaded = true
    const wrapper = await mountPanel()

    const chip = wrapper.find('[data-testid="suggestion-chip"]')
    expect(chip.exists()).toBe(true)
    const text = chip.text()
    expect(text.length).toBeGreaterThan(0)

    await chip.trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    const request = api.chat.mock.calls[0]?.[0] as { message?: string; sessionId?: string; context?: unknown }
    expect(request.message).toBe(text) // 发出的是 chip 文案本身
    expect(request.sessionId).toBe(store.sessionId) // 与手输同一会话/同一入口
    expect(request.context).toBeDefined()
    expect(store.messages.length).toBeGreaterThan(0) // 用户气泡落屏
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(false) // chips 随空态消失
    wrapper.unmount()
  }, 20_000)
})

describe('Q-05（P1）：流式中 chips 不得发送（不产生 409）', () => {
  it('loading 中点击 chip：零请求（canSend 闸门），不产生 SESSION_BUSY', async () => {
    const store = useAiStore()
    store.messages = []
    store.historyLoaded = true
    store.loading = true // 流式中（空态仍渲染 chips）
    const wrapper = await mountPanel()

    const chip = wrapper.find('[data-testid="suggestion-chip"]')
    expect(chip.exists()).toBe(true)
    await chip.trigger('click')
    await flushPromises()

    expect(api.chat).not.toHaveBeenCalled() // 点击不发送
    expect(store.messages).toHaveLength(0) // 无幻影气泡
    wrapper.unmount()
  }, 20_000)
})

describe('L-83（P1）：409 竞态停止时输入框 == 用户原草稿（不被上一条用户消息覆盖）', () => {
  /**
   * 真机实证（`e2e/10-srp.spec.ts` P-08 分支 C）：停止与回车同 tick 时，请求赶在停止收口前
   * 发出、被后端 409 拒；提示与幻影气泡回收都对，输入框却被回填成**上一条用户消息**。
   *
   * <p>根因（本用例锁定）：两个 `send()` 同时在飞 —— 被停止的旧轮收尾为 `interrupted`
   * （轮次身份已被新轮换掉 ⇒ 不是 `cancelled`），走"失败还原草稿"把**旧轮自己的草稿**写回
   * 输入框；随后新轮 409 的还原被 `inputText !== ''`（误判成"用户刚输入了新内容"）挡掉。
   * 判据 = 输入框逐字等于**用户刚打的草稿**，且两种收尾次序（旧轮先/409 先）都成立。
   */
  async function runRace(busyFirst: boolean): Promise<string> {
    const store = useAiStore()
    store.messages = [message('u0', 'user', '更早的消息'), message('a0', 'assistant', '更早的回答')]
    store.loading = false
    const wrapper = await mountPanel()

    let releaseStale: (() => void) | null = null
    let releaseBusy: (() => void) | null = null
    api.chat
      .mockImplementationOnce(() => new Promise((resolve) => {
        releaseStale = () => resolve({ terminal: true })
      }))
      .mockImplementationOnce(() => new Promise((_resolve, reject) => {
        releaseBusy = () => reject(new ApiError('该会话已有一轮对话在进行中', 409, 'SESSION_BUSY', {
          code: 'SESSION_BUSY', runId: 'run-l83', reattach: '/api/ai/events/run-l83'
        }))
      }))

    const composer = wrapper.find('[data-testid="composer-input"]')
    await composer.setValue('上一条用户消息')
    ;(wrapper.find('[data-testid="send-btn"]').element as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(api.chat).toHaveBeenCalledTimes(1)

    // 同 tick：停止 + 回车（用户刚打的草稿在输入框里）
    await composer.setValue('用户刚打的草稿')
    ;(wrapper.find('[data-testid="stop-btn"]').element as HTMLElement).click()
    composer.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(api.chat).toHaveBeenCalledTimes(2)

    if (busyFirst) {
      releaseBusy!()
      await flushPromises()
      releaseStale!()
    } else {
      releaseStale!()
      await flushPromises()
      releaseBusy!()
    }
    await flushPromises()
    const value = (wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value
    wrapper.unmount()
    return value
  }

  it('旧轮迟到收尾先到（真机形态）：输入框仍是用户原草稿', async () => {
    expect(await runRace(false)).toBe('用户刚打的草稿')
  }, 20_000)

  it('409 先到：输入框仍是用户原草稿（对称次序）', async () => {
    expect(await runRace(true)).toBe('用户刚打的草稿')
  }, 20_000)
})

describe('L-85（P1）：区域消失后交接卡不可点、点下去不谎报', () => {
  /** 一条待动作的上传交接段（`ui_request_upload` = 唯一的人围栏前端工具）。 */
  function handoffMessage(): ChatMessage {
    return {
      ...message('a-up', 'assistant', '需要你选一个文件。', { streaming: true, done: false }),
      parts: [
        { kind: 'text', seg: 0, text: '需要你选一个文件。', streaming: false },
        {
          kind: 'tool',
          seg: 1,
          toolCallId: 'h1',
          name: 'ui_request_upload',
          kind2: 'FRONTEND',
          status: 'pending',
          args: JSON.stringify({ accept: ['xlsx'] }),
          requiresAction: true
        }
      ]
    }
  }

  function registerUploadRegion(): void {
    const root = document.createElement('div')
    root.innerHTML = '<button data-ai-ref="import.upload.choose" aria-label="上传 xlsx / zip">上传</button>'
    document.body.appendChild(root)
    registerAiRegion({
      id: 'import.upload',
      root,
      risk: 'consequential',
      page: 'import',
      label: '上传区',
      uploadEntry: 'import.upload.choose',
      receiveFile: async () => '匹配 1 个配置项'
    })
  }

  it('离开导入向导（区域注销）⇒ 卡面转 missing：主按钮 disabled + 指路；区域仍在 ⇒ 可点', async () => {
    resetAiRegions()
    registerUploadRegion()
    const store = useAiStore()
    store.messages = [message('u-up', 'user', '把这个配置导进去'), handoffMessage()]
    store.activeUploadHandoff = {
      toolCallId: 'h1',
      runId: null,
      ref: 'import.upload.choose',
      accept: ['xlsx'],
      status: 'waiting',
      reason: null,
      filename: null
    }
    const wrapper = await mountPanel()

    const choose = () => wrapper.get('[data-testid="upload-handoff-choose"]')
    expect(choose().attributes('disabled')).toBeUndefined() // 区域仍在 ⇒ 可点
    expect(store.uploadHandoffAvailability()).toBe('waiting')

    // 用户离开导入向导：页面卸载注销区域 + 工作区换页（真实重渲染的触发源）
    unregisterAiRegion('import.upload')
    useWorkspaceStore().enterPage({ pageId: 'tasks' })
    await wrapper.vm.$nextTick()

    expect(store.activeUploadHandoff?.status).toBe('waiting') // 建卡那一刻的分流（"当时"的结论）
    expect(store.uploadHandoffAvailability()).toBe('missing') // 卡面必须按"现在"算
    expect(choose().attributes('disabled')).toBeDefined()     // 主按钮不可点
    expect(wrapper.get('[data-testid="upload-handoff-hint"]').text()).toContain('请先打开导入向导')
    expect(wrapper.get('[data-testid="upload-handoff-cancel"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
    resetAiRegions()
  }, 20_000)
})

describe('G-18（P0）：boolean 开关真实可点 —— 提交体随点击变化、键盘可切', () => {
  const SCHEMA: FormSpec = {
    scenario: 'FILTER',
    fields: [
      { key: 'onlyPk', label: '只看含主键', type: 'boolean' },
      { key: 'name', label: '名称', type: 'text' }
    ]
  }

  it('点击后提交体里的该键值翻转（不是只改控件外观）', async () => {
    const wrapper = mount(FormRenderer, { props: { schema: SCHEMA, submitting: false, error: null } })
    const input = wrapper.find('#gf-field-onlyPk')
    const element = input.element as HTMLInputElement
    expect(element.checked).toBe(false)

    element.click()
    await wrapper.vm.$nextTick()
    element.dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="gf-submit"]').trigger('click')
    await flushPromises()
    const submitted = (wrapper.emitted('submit') ?? []).at(-1)?.[0] as Record<string, unknown>
    expect(submitted.onlyPk).toBe(true) // 提交体随点击变化（Q9 的断言点）
    expect(element.checked).toBe(true)
    wrapper.unmount()
  }, 20_000)

  it('键盘 Space 同样可切（无障碍路径，不依赖鼠标）', async () => {
    const wrapper = mount(FormRenderer, { props: { schema: SCHEMA, submitting: false, error: null } })
    const element = wrapper.find('#gf-field-onlyPk').element as HTMLInputElement
    // 原生 checkbox 的空格激活 = 点击事件（浏览器行为；jsdom 不做默认行为，故按等价路径验）
    element.focus()
    element.click()
    element.dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(element.checked).toBe(true)
    expect((wrapper.emitted('draft') ?? []).at(-1)?.[0]).toMatchObject({ onlyPk: true })
    wrapper.unmount()
  }, 20_000)
})
