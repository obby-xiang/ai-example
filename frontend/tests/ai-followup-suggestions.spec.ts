/**
 * 批 E · E2：每轮完成后的上下文建议 + 空态快捷 chips 点即发。
 *
 * <h2>口径（用户裁决，逐字）</h2>
 * <ul>
 * <li>空态 chips 点击<b>直接发送</b>（不再回填输入框）；写操作靠既有 HITL 确认卡兜底；</li>
 * <li>只在<b>每轮正常完成</b>（done 终态）后、回复下方出 2–3 条；挂起 / 出错 / 有确认卡或表单挂着时不出；</li>
 * <li>建议 = <b>规则模板 + 上下文槽位</b>（页面类型 / 当前任务），不让模型自由生成；</li>
 * <li>窄面板<b>竖排</b>、可见 ≤3 条、<b>文案 ≤16 字</b>；新一轮触发（发送或点 chip）即移除；</li>
 * <li>i18n 双语 + a11y（button + aria-label）。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>把 `buildFollowups` 的页面分支删掉 ⇒ 页面相关用例红；</li>
 * <li>把面板的 `followupReady` 判据删掉 ⇒ 「未完成轮不出」用例红；</li>
 * <li>把 chip 的 click 改回 `inputText = text` ⇒ 「点即发」用例红。</li>
 * </ul>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import zhCN from '@/locales/zh-CN.json'
import enUS from '@/locales/en-US.json'
import { applyLocale } from '@/locales'
import { MAX_SUGGESTION_CHARS, buildFollowups } from '@/utils/ai-suggestions'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import type { ChatMessage, MessagePart } from '@/types/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  getContext: vi.fn()
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

const SUGGESTIONS_ZH = zhCN.ai.suggestions as Record<string, string>
const SUGGESTIONS_EN = enUS.ai.suggestions as Record<string, string>

function render(template: string, params?: Record<string, string | number>): string {
  let out = template
  for (const [key, value] of Object.entries(params ?? {})) {
    out = out.split(`{${key}}`).join(String(value))
  }
  return out
}

function zhTextOf(suggestion: { key: string; params?: Record<string, string | number> }): string {
  return render(SUGGESTIONS_ZH[suggestion.key] ?? '', suggestion.params)
}

function enTextOf(suggestion: { key: string; params?: Record<string, string | number> }): string {
  return render(SUGGESTIONS_EN[suggestion.key] ?? '', suggestion.params)
}

const ctx = (over: Partial<{ page: string; taskType: 'EXPORT' | 'IMPORT' | null; step: string | null; taskId: number | null }> = {}) => ({
  page: 'tasks',
  taskType: null,
  step: null,
  taskId: null,
  ...over
})

describe('E2 规则建议：模板 + 上下文槽位（纯函数）', () => {
  it('任务中心：2–3 条，含配置定义与工作区状态', () => {
    const items = buildFollowups(ctx({ page: 'tasks' }))

    expect(items.length).toBeGreaterThanOrEqual(2)
    expect(items.length).toBeLessThanOrEqual(3)
    const texts = items.map(zhTextOf)
    expect(texts).toContain('列出全部配置定义')
    expect(texts).toContain('查看当前工作区状态')
  })

  it('导出向导：预估行数 + 启动作业；有任务号时带上任务进度槽位', () => {
    const items = buildFollowups(ctx({ page: 'export', taskType: 'EXPORT', step: 'SELECT_DEFS', taskId: 7 }))
    const texts = items.map(zhTextOf)

    expect(texts).toContain('预估导出数据行数')
    expect(texts).toContain('启动导出作业')
    expect(texts).toContain('查看任务 #7 进度')
    expect(items.length).toBeLessThanOrEqual(3)
  })

  it('导入向导：预检查 + 启动导入', () => {
    const texts = buildFollowups(ctx({ page: 'import', taskType: 'IMPORT', step: 'UPLOAD', taskId: null })).map(zhTextOf)

    expect(texts).toContain('预检查导入数据')
    expect(texts).toContain('启动导入作业')
  })

  it('页面不同 ⇒ 建议不同（配置定义页 / 数据浏览页各取所须）', () => {
    const defs = buildFollowups(ctx({ page: 'definitions' })).map(zhTextOf)
    const data = buildFollowups(ctx({ page: 'data' })).map(zhTextOf)

    expect(defs).toContain('列出全部配置定义')
    expect(data).toContain('统计当前数据行数')
    expect(data).not.toContain('列出全部配置定义')
  })

  it('恒 2–3 条、无重复、中文文案 ≤16 字（覆盖全部上下文组合）', () => {
    const pages = ['tasks', 'export', 'import', 'definitions', 'data', 'unknown']
    const tasks: Array<'EXPORT' | 'IMPORT' | null> = [null, 'EXPORT', 'IMPORT']
    const steps = [null, 'SELECT_DEFS', 'QUERY_COND', 'EXPORT', 'UPLOAD', 'PRECHECK', 'IMPORT', 'PUBLISH']
    const ids = [null, 7, 123456]

    for (const page of pages) {
      for (const taskType of tasks) {
        for (const step of steps) {
          for (const taskId of ids) {
            const items = buildFollowups(ctx({ page, taskType, step, taskId }))
            const texts = items.map(zhTextOf)
            expect(items.length).toBeGreaterThanOrEqual(2)
            expect(items.length).toBeLessThanOrEqual(3)
            expect(new Set(items.map((item) => item.key)).size).toBe(items.length)
            for (const text of texts) {
              expect(text.length).toBeGreaterThan(0)
              expect(text.length).toBeLessThanOrEqual(MAX_SUGGESTION_CHARS)
            }
          }
        }
      }
    }
  })

  it('英文键齐全且非空（双语文案不漏键）', () => {
    const items = buildFollowups(ctx({ page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 }))

    for (const item of items) {
      expect(SUGGESTIONS_EN[item.key]).toBeTruthy()
      expect(enTextOf(item)).not.toContain('{')
      expect(enTextOf(item).length).toBeLessThanOrEqual(48)
    }
  })

  it('文案里不得出现表情符号（界面禁 emoji）', () => {
    for (const text of Object.values(SUGGESTIONS_ZH)) {
      expect(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(text)).toBe(false)
    }
    for (const text of Object.values(SUGGESTIONS_EN)) {
      expect(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(text)).toBe(false)
    }
  })
})

function assistantMessage(id = 'a-1'): ChatMessage {
  const parts: MessagePart[] = [{ kind: 'text', seg: 0, text: '已完成', streaming: false }]
  return {
    id,
    role: 'assistant',
    content: '已完成',
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true,
    code: 'AI_AVAILABLE',
    model: 'deepseek-flash',
    baseUrl: 'http://placeholder',
    memoryBackend: 'redis',
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z',
    allowedParams: true,
    defaultParams: { temperature: 0.2, topP: null, maxTokens: 1024 }
  })
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.chat.mockResolvedValue(undefined)
  api.getContext.mockRejectedValue(new Error('not stubbed'))
})

/** 一轮正常完成后的现场：done 终态已到、无挂起、无卡片。 */
function seedCompletedRound(): void {
  const store = useAiStore()
  store.messages = [assistantMessage()]
  store.historyLoaded = true
  store.followupReady = true
  store.lastRoundOutcome = 'ok'
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
  workspace.taskType = null
  workspace.step = null
  workspace.taskId = null
}

describe('E2 建议条（DOM 面）', () => {
  it('done 完成后：回复下方出 2–3 条，竖排、每条带 aria-label', async () => {
    seedCompletedRound()
    const wrapper = await mountPanel()

    const box = wrapper.find('[data-testid="followup-suggestions"]')
    expect(box.exists()).toBe(true)
    expect(box.classes()).toEqual(expect.arrayContaining(['flex-col']))
    const buttons = box.findAll('button')
    expect(buttons.length).toBeGreaterThanOrEqual(2)
    expect(buttons.length).toBeLessThanOrEqual(3)
    for (const button of buttons) {
      expect(button.attributes('type')).toBe('button')
      expect(button.attributes('aria-label')).toBeTruthy()
      expect(button.text().length).toBeLessThanOrEqual(MAX_SUGGESTION_CHARS)
    }
    wrapper.unmount()
  })

  it('建议按当前页面取（任务中心 ⇒ 配置定义 / 工作区状态）', async () => {
    seedCompletedRound()
    const wrapper = await mountPanel()

    const texts = wrapper.findAll('[data-testid="followup-suggestion"]').map((button) => button.text())
    expect(texts).toContain('列出全部配置定义')
    expect(texts).toContain('查看当前工作区状态')
    wrapper.unmount()
  })

  it('未正常完成（done 未到 / 出错 / 挂起 / 有确认卡 / 有表单）⇒ 不出', async () => {
    seedCompletedRound()
    const store = useAiStore()
    store.followupReady = false
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)

    store.followupReady = true
    store.lastRoundOutcome = 'error'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)

    store.lastRoundOutcome = 'ok'
    store.suspended = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)

    store.suspended = false
    store.activeForm = { toolCallId: 'c1', runId: 'r1', form: {} as never, status: 'filling', error: null, values: {}, fieldErrors: [] }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)

    store.activeForm = null
    const message = store.messages[0] as ChatMessage
    message.parts.push({
      kind: 'tool',
      seg: 1,
      toolCallId: 'c9',
      name: 'run_job',
      kind2: 'CONFIRM',
      status: 'pending',
      args: '{}',
      result: null,
      requiresAction: true,
      reason: null
    } as MessagePart)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('新一轮触发即移除：点建议直接发送（不回填输入框），建议条随即消失', async () => {
    seedCompletedRound()
    const wrapper = await mountPanel()

    const first = wrapper.find('[data-testid="followup-suggestion"]')
    const sentText = first.text()
    await first.trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    const body = api.chat.mock.calls[0][0] as Record<string, unknown>
    expect(body.message).toBe(sentText)
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('空态 chips 点即发（不再回填输入框）', async () => {
    const wrapper = await mountPanel()

    const chips = wrapper.findAll('[data-testid="suggestion-chip"]')
    expect(chips.length).toBeGreaterThanOrEqual(4)
    expect(chips[0].text()).toBe('列出全部配置定义')
    expect(chips[0].attributes('aria-label')).toBeTruthy()

    await chips[0].trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect((api.chat.mock.calls[0][0] as Record<string, unknown>).message).toBe('列出全部配置定义')
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })

  it('U3：空态 chips 与 composer 共用 health / 挂起 / 待决可用性闸', async () => {
    const wrapper = await mountPanel()
    const store = useAiStore()
    const chip = () => wrapper.find('[data-testid="suggestion-chip"]')

    // 健康可用：chips 可点（原有行为不退化）
    expect(chip().attributes('disabled')).toBeUndefined()

    // health 不可用 ⇒ 与 composer 一样关闭发送闸，点击零请求
    store.health = { ...store.health!, available: false }
    await wrapper.vm.$nextTick()
    expect(chip().attributes('disabled')).toBeDefined()
    await chip().trigger('click')
    await flushPromises()
    expect(api.chat).not.toHaveBeenCalled()

    // 挂起/待决等待 ⇒ 同样关闭（pendingToolCall 由消息派生；空态下以挂起态覆盖同一闸）
    store.health = { ...store.health!, available: true }
    store.suspended = true
    await wrapper.vm.$nextTick()
    expect(chip().attributes('disabled')).toBeDefined()
    await chip().trigger('click')
    await flushPromises()
    expect(api.chat).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('英文界面：建议与 chips 走 en-US 文案（双语）', async () => {
    applyLocale('en-US')
    seedCompletedRound()
    const wrapper = await mountPanel()

    const texts = wrapper.findAll('[data-testid="followup-suggestion"]').map((button) => button.text())
    expect(texts).toContain(enUS.ai.suggestions.listDefs)
    expect(texts).not.toContain(zhCN.ai.suggestions.listDefs)
    wrapper.unmount()
  })
})
