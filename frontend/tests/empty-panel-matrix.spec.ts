/**
 * 测试扩充 V4 · C5 空态矩阵（面板层 / 快照层）。
 *
 * <p>口径：每格至少 1 条，断言「渲染不崩 + 空态文案/行为正确」，且**不与既有断言重复**
 * （既有覆盖：`ai-panel-parts-render.spec.ts#12` 空态结构 + chips、`aie2e-qc-safety.spec.ts#Q-07`
 * 换会话清建议、`ai-perception-batch-c.spec.ts#C-03` 快照文本预算、`KbToolsTest` 后端空结果文案）。
 *
 * <p>本文件五格 = 无会话 / 无建议 / 无工具 / 无区域 / 无 KB。另两格（无历史 / 无任务）
 * 在 `empty-views-matrix.spec.ts`（页面级，需独立的视图装配）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture, queueNextSessionId } from './ai-identity-fixture'
import { useWorkspaceStore } from '@/stores/workspace'
import { aiSnapshot } from '@/utils/ai-dom-tools'
import { enterImportPage, resetAiTestState } from './ai-fixtures'

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

function healthFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
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
    ...overrides
  }
}

function buildMessage(parts: MessagePart[], overrides: Partial<ChatMessage> = {}): ChatMessage {
  const text = parts.map((part) => (part.kind === 'text' ? part.text : '')).join('')
  return {
    id: 'a-1',
    role: 'assistant',
    content: text,
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...overrides
  }
}

/** 后端 `KbTools#renderSearch` 的 NO_MATCH 原文（逐字摘录，前端只负责如实渲染）。 */
const KB_NO_MATCH_RESULT = [
  '状态: no_match',
  '查询词: 报销标准',
  '未检索到相关知识。',
  '可参考的标题: 差旅费用报销管理办法；费用申请流程',
  '建议: 换用业务口径中的正式名称或同义词再查一次；仍无结果时明确说明“知识库中未找到”，并建议用户联系对应业务负责人。'
].join('\n')

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue(healthFixture())
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  api.chat.mockResolvedValue(undefined)
  api.cancel.mockResolvedValue({ cancelled: true })
  api.deleteHistory.mockResolvedValue({})
  api.confirm.mockResolvedValue(undefined)
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
  workspace.taskType = null
  workspace.step = null
  workspace.taskId = null
  installIdentityFixture()
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('C5-01 无会话：新建对话后回到空态（不留在旧会话的残影里）', () => {
  it('有消息 ⇒ 空态不在；新建对话后消息清空、sessionId 换新、空态与 chips 回来且可继续发送', async () => {
    const store = useAiStore()
    const oldSession = store.sessionId
    // 新建对话要拿**新**号：显式声明下一次发号的返回值（同一身份默认重复取号稳定）
    queueNextSessionId(Number(oldSession) + 1)
    store.messages = [buildMessage([{ kind: 'text', seg: 0, text: '上一会话的答案', streaming: false }])]
    store.historyLoaded = true
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(false)

    await store.resetSession()
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(store.messages).toHaveLength(0)
    expect(store.sessionId).not.toBe(oldSession)
    expect(store.historyLoaded).toBe(true) // 新会话不留骨架屏
    expect(wrapper.find('[data-testid="history-skeleton"]').exists()).toBe(false)
    const empty = wrapper.find('[data-testid="empty-state"]')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toContain('您好，我是实施助手')
    expect(wrapper.findAll('[data-testid="suggestion-chip"]').length).toBeGreaterThanOrEqual(4)

    // 空态不是"死界面"：写入后发送闸照常打开
    await wrapper.find('[data-testid="composer-input"]').setValue('列出全部配置定义')
    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  }, 20_000)
})

describe('C5-03 无建议：不该出建议的轮次一条都不出', () => {
  it('本轮以取消收尾（followupReady 仍为真）⇒ 建议区零渲染；换成正常收官才重建', async () => {
    const store = useAiStore()
    store.messages = [buildMessage([{ kind: 'text', seg: 0, text: '半截答案', streaming: false }])]
    store.historyLoaded = true
    store.followupReady = true
    store.lastRoundOutcome = 'cancelled'
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="followup-suggestion"]')).toHaveLength(0)

    store.lastRoundOutcome = 'ok'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="followup-suggestion"]').length).toBeGreaterThanOrEqual(2)
    wrapper.unmount()
  }, 20_000)
})

describe('C5-04 无工具：纯文本轮的卡面零残留', () => {
  it('一轮只有正文 ⇒ 零工具卡 / 零输出盒 / 零参数区，正文与元信息照常', async () => {
    const store = useAiStore()
    store.messages = [
      { id: 'u-1', role: 'user', content: '今天几号', parts: [{ kind: 'text', seg: 0, text: '今天几号', streaming: false }], reasoning: '', toolRuns: [], pendingCall: null, streaming: false, done: true },
      buildMessage([{ kind: 'text', seg: 0, text: '今天是周四。', streaming: false }], { id: 'a-1' })
    ]
    store.historyLoaded = true
    const wrapper = await mountPanel()

    expect(wrapper.text()).toContain('今天是周四。')
    expect(wrapper.findAll('[data-testid^="tool-card-"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid^="pending-card-"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="tool-output-text"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid^="args-rows-"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="truncated-badge"]')).toHaveLength(0)
    wrapper.unmount()
  }, 20_000)
})

describe('C5-05 无区域：零注册区域时快照如实说「区域 0 个（无）」', () => {
  it('不抛错、不回退全文档遍历，头行与尾行都报 0', () => {
    resetAiTestState()
    enterImportPage()
    // 页面已声明，但**没有任何区域注册**（v-if 页面 / 空表单 / 只读壳的形态）
    document.body.innerHTML = '<button data-ai-ref="import.upload.ghost">游离按钮</button>'

    const out = aiSnapshot({})

    expect(out.outcome).toBe('ok')
    expect(out.result).toContain('区域 0 个（无）')
    expect(out.result).toContain('共 0 个元素（本次回 0 个')
    expect(out.result).not.toContain('import.upload.ghost') // 零区域 ≠ 全文档兜底
  })

  it('text=true 同样零文本段（没有区域就没有"区域文本"可采）', () => {
    resetAiTestState()
    enterImportPage()
    document.body.innerHTML = '<p>游离文案</p>'

    const out = aiSnapshot({ text: true })

    expect(out.outcome).toBe('ok')
    expect(out.result).not.toContain('区域文本（只读')
    expect(out.result).not.toContain('游离文案')
  })
})

describe('C5-06 无 KB：知识库空结果原样呈现，不升级成错误', () => {
  it('kb_search 的 NO_MATCH 原文进输出区；不出错误卡 / 不加截断角标 / 不标失败', async () => {
    const store = useAiStore()
    store.messages = [buildMessage([
      { kind: 'text', seg: 0, text: '知识库里没有这条。', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId: 'kb-1', name: 'kb_search', kind2: 'BACKEND',
        status: 'succeeded', args: '{"query":"报销标准"}', result: KB_NO_MATCH_RESULT, requiresAction: false
      }
    ])]
    store.historyLoaded = true
    const wrapper = await mountPanel()

    const out = wrapper.find('[data-testid="tool-output-text"]')
    expect(out.exists()).toBe(true)
    expect(out.text()).toContain('未检索到相关知识。')
    expect(out.text()).toContain('可参考的标题')
    expect(wrapper.find('[data-testid="truncated-badge"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="tool-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid^="error-card-"]')).toHaveLength(0)
    expect(wrapper.find('[data-testid="card-status-kb-1"]').text()).not.toContain('失败')
    wrapper.unmount()
  }, 20_000)
})
