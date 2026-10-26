/**
 * 测试扩充 V4 · C11 en-US 全流程抽查（成链，不是逐点）。
 *
 * <p>三格 = AI 对话主链 · 错误码文案 · 工具文案。断言“同一轮里从输入到渲染都走 en 词表”，
 * 而不是再抄一遍键集对齐（`i18n-bootstrap.spec.ts#B1/B3` 已钉双向键集与占位符；
 * `tool-i18n-coverage.spec.ts` 已钉工具标题/摘要的纯函数口径 —— 本文件只补**面板 DOM 链**）。
 *
 * <p>已知的脚本侧中文残留（错误卡标题/引导语、notice 模板、占位符文案）属 **L-69 挂账面**，
 * 本批不修、也不把它伪装成已国际化：错误码这一格只断言**后端按 Accept-Language 给的英文原文
 * 逐字上屏、机器码不被改写**这条契约链。
 *
 * <p><b>2026-10-23 L-69 落地注记</b>：上述脚本侧面已迁入 locale（notice 走 `code + args`、
 * 错误卡/占位符/状态徽标走 `ai.*` 键），本文件的「错误码英文原文」契约不变（后端 message 仍是数据）；
 * C11-03 的卡头徽标断言随之改为英文。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import i18n, { applyLocale } from '@/locales'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'

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
  feedbackSubmit: vi.fn(),
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  aiApi: api,
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: api.formOptionSource
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

function healthFixture(): Record<string, unknown> {
  return {
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  }
}

function buildMessage(parts: MessagePart[], overrides: Partial<ChatMessage> = {}): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-1', role: 'assistant', content: text, parts, reasoning: '', toolRuns: [],
    pendingCall: null, streaming: false, done: true, ...overrides
  }
}

/** 完整一轮（start → message_start → delta → message_end → done），全部走 en 词表。 */
function completeRound(text: string): void {
  api.chat.mockImplementation((_request: unknown, handlers: { onFrame: (frame: SseFrame) => void }) => {
    for (const frame of [
      { type: 'start', runId: 'run-en' },
      { type: 'message_start', messageId: 'm-en' },
      { type: 'delta', text },
      { type: 'message_end', messageId: 'm-en' },
      { type: 'done', runId: 'run-en', cancelled: false }
    ]) {
      handlers.onFrame(frame as SseFrame)
    }
    return Promise.resolve({ terminal: true })
  })
}

let pinia: ReturnType<typeof createPinia>

beforeEach(() => {
  sessionStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  document.body.innerHTML = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue(healthFixture())
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  const workspace = useWorkspaceStore(pinia)
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
})

afterEach(() => {
  applyLocale('zh-CN')
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { plugins: [pinia], stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('C11-01 en-US 对话主链：空态 → 发送 → 正文 → 建议全走 en', () => {
  it('空态问候与 chips 是英文；发送后正文与后续建议仍是英文，无 key 原文泄漏', async () => {
    applyLocale('en-US')
    completeRound('Here is the summary you asked for.')
    const wrapper = await mountPanel()
    const store = useAiStore(pinia)

    const empty = wrapper.find('[data-testid="empty-state"]')
    expect(empty.text()).toContain('Hello, I am the implementation assistant')
    const chips = wrapper.findAll('[data-testid="suggestion-chip"]')
    expect(chips.length).toBeGreaterThanOrEqual(4)
    expect(chips[0]?.text()).toBe('List all definitions')
    expect(wrapper.text()).not.toContain('您好，我是实施助手')

    await wrapper.find('[data-testid="composer-input"]').setValue('List all definitions')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect((api.chat.mock.calls[0]?.[0] as { message: string }).message).toBe('List all definitions')
    expect(store.historyLoaded).toBe(true)
    expect(wrapper.text()).toContain('Here is the summary you asked for.')

    const suggestions = wrapper.find('[data-testid="followup-suggestions"]')
    expect(suggestions.exists()).toBe(true)
    const labels = wrapper.findAll('[data-testid="followup-suggestion"]').map((button) => button.text())
    expect(labels.length).toBeGreaterThanOrEqual(2)
    for (const label of labels) {
      expect(label).not.toContain('ai.suggestions.')
      expect(label).toMatch(/[A-Za-z]/)
    }
    wrapper.unmount()
  }, 20_000)
})

describe('C11-02 en-US 错误码文案：后端英文原文逐字上屏，机器码留在错误卡诊断', () => {
  it('error 终帧（英文 message）⇒ 通知与错误卡 detail 都是原文，code 原样保留', async () => {
    applyLocale('en-US')
    api.chat.mockImplementation((_request: unknown, handlers: { onFrame: (frame: SseFrame) => void }) => {
      handlers.onFrame({ type: 'start', runId: 'run-en-err' } as SseFrame)
      handlers.onFrame({
        type: 'error',
        runId: 'run-en-err',
        code: 'UPSTREAM_STREAM_ERROR',
        message: 'Upstream stream failed (503 Service Unavailable)'
      } as SseFrame)
      return Promise.resolve({ terminal: true })
    })
    const wrapper = await mountPanel()
    const store = useAiStore(pinia)

    await wrapper.find('[data-testid="composer-input"]').setValue('hello')
    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(store.lastRoundOutcome).toBe('error')
    expect(store.notice).not.toContain('UPSTREAM_STREAM_ERROR')
    expect(store.notice).toContain('Error this turn')
    expect(store.notice).toContain('Upstream stream failed (503 Service Unavailable)')
    const card = store.messages
      .map((message) => message.errorCard)
      .find((candidate) => candidate !== null && candidate !== undefined)
    expect(card?.code).toBe('UPSTREAM_STREAM_ERROR')
    expect(card?.detail).toBe('Upstream stream failed (503 Service Unavailable)')
    const detail = wrapper.find('[data-testid^="error-card-detail-"]')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toBe('Upstream stream failed (503 Service Unavailable)')
    wrapper.unmount()
  }, 20_000)
})

describe('C11-03 en-US 工具文案：标题 / 摘要 / 状态词在卡面成链（切回 zh 复位）', () => {
  it('en 下工具卡标题与摘要为英文、状态词 done；切回 zh 同一卡变中文', async () => {
    applyLocale('en-US')
    const store = useAiStore(pinia)
    store.messages = [buildMessage([
      {
        kind: 'tool', seg: 0, toolCallId: 't-sheet', name: 'read_sheet_summary', kind2: 'FRONTEND',
        status: 'succeeded', args: '{"defCode":"CURRENCY"}', result: 'rows: 3', requiresAction: false
      },
      {
        kind: 'tool', seg: 1, toolCallId: 't-kb', name: 'kb_search', kind2: 'BACKEND',
        status: 'succeeded', args: '{"query":"expense"}', result: '状态: hit', requiresAction: false
      }
    ])]
    store.historyLoaded = true
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="card-title-t-sheet"]').text()).toBe('Read sheet summary')
    expect(wrapper.find('[data-testid="tool-summary-t-sheet"]').text()).toContain('CURRENCY')
    // 状态词链：摘要/兜底侧（tool-summaries）与**卡头徽标**同随语言（L-69：blocks.ts 的 19 个
    // 状态词已迁入 `ai.card.*`；本断言随 L-69 落地由「已成功」改为英文）
    expect(wrapper.find('[data-testid="card-status-t-sheet"]').text()).toBe('Succeeded')
    // 知识库三工具此前无标题键（卡头回落裸工具名）—— 本批补齐后必须是英文标题 + 摘要模板
    expect(wrapper.find('[data-testid="card-title-t-kb"]').text()).toBe('Search knowledge base')
    // 卡头摘要去重：标题已说过的部分不重复 ⇒ 摘要只留参数残段（与语言无关的那一侧另测）
    expect(wrapper.find('[data-testid="tool-summary-t-kb"]').text()).toBe('(expense)')

    applyLocale('zh-CN')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="card-title-t-sheet"]').text()).toBe('读取表格摘要')
    expect(wrapper.find('[data-testid="card-status-t-sheet"]').text()).toBe('已成功')
    expect(wrapper.find('[data-testid="card-title-t-kb"]').text()).toBe('检索知识库')
    expect(wrapper.find('[data-testid="tool-summary-t-kb"]').text()).toBe('（expense）')
    expect(i18n.global.t('tool.status.succeeded')).toBe('已完成')
    wrapper.unmount()
  }, 20_000)
})
