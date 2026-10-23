/**
 * 测试扩充 V5 · C11 补洞：`kb_read_doc` / `kb_lookup_term` 两张卡片的**卡面链**各 1 例。
 *
 * 背景：V4 的 `C11-03` 只断言了 `kb_search` 一张卡（见 `en-us-flow.spec.ts`），
 * 另外两个分支（`tool-summaries.ts` 的三分支、双 locale 各 3 键）虽已落地但**无独立断言**。
 * 本批补齐：标题必须走本地化词表（不得回落裸机器名），摘要按各自模板成链，且中英切换逐字可判。
 *
 * 两张卡的摘要口径不同，断言也据此分开：
 * - `kb_read_doc` 的 summary 模板与 title 同字 ⇒ 卡头摘要位去重后为空（不重复念一遍）；
 * - `kb_lookup_term` 的 summary 带 `{term}` ⇒ 卡头摘要只留参数残段（与语言无关的括号形态随 locale 变）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import { applyLocale } from '@/locales'
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

function buildMessage(parts: MessagePart[]): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-kb', role: 'assistant', content: text, parts, reasoning: '', toolRuns: [],
    pendingCall: null, streaming: false, done: true
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: false })
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
})

afterEach(() => {
  applyLocale('zh-CN')
})

async function mountWith(part: MessagePart): Promise<ReturnType<typeof mount>> {
  const store = useAiStore()
  store.messages = [buildMessage([part])]
  store.historyLoaded = true
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('C11-04 kb_read_doc 卡：标题走词表，摘要去重为空（zh / en 逐字可判）', () => {
  it('zh「读取知识文档」→ en「Read knowledge doc」，任何语言下都不出现裸机器名 / 键名', async () => {
    const wrapper = await mountWith({
      kind: 'tool', seg: 0, toolCallId: 'kb-read', name: 'kb_read_doc', kind2: 'BACKEND',
      status: 'succeeded', args: '{"docId":"expense-basics"}', result: 'doc body', requiresAction: false
    })

    expect(wrapper.find('[data-testid="card-title-kb-read"]').text()).toBe('读取知识文档')
    expect(wrapper.text()).not.toContain('kb_read_doc')
    expect(wrapper.text()).not.toContain('tool.kb_read_doc.summary')

    applyLocale('en-US')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="card-title-kb-read"]').text()).toBe('Read knowledge doc')
    expect(wrapper.text()).not.toContain('kb_read_doc')
    wrapper.unmount()
  })
})

describe('C11-05 kb_lookup_term 卡：标题 + 术语摘要成链（zh / en 同一张卡）', () => {
  it('zh「查询术语解释」+「（费用）」→ en「Look up term」+「(expense)」', async () => {
    const wrapper = await mountWith({
      kind: 'tool', seg: 0, toolCallId: 'kb-term', name: 'kb_lookup_term', kind2: 'BACKEND',
      status: 'succeeded', args: '{"term":"费用"}', result: 'term definition', requiresAction: false
    })

    expect(wrapper.find('[data-testid="card-title-kb-term"]').text()).toBe('查询术语解释')
    expect(wrapper.find('[data-testid="tool-summary-kb-term"]').text()).toBe('（费用）')
    expect(wrapper.text()).not.toContain('kb_lookup_term')

    applyLocale('en-US')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="card-title-kb-term"]').text()).toBe('Look up term')
    // 术语值本身是用户/文档给的业务词（「费用」），词典不翻译取值；括号形态随 locale
    expect(wrapper.find('[data-testid="tool-summary-kb-term"]').text()).toBe('(费用)')
    wrapper.unmount()
  })
})
