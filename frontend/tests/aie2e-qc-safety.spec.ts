/**
 * E2E 用例全集（第 53 件）· QC 快捷操作 + SAF 草稿/台账：剩余单测缺口
 * （Q-04 / Q-07 / Q-08 / X-13）。
 *
 * <p>Q-01 / Q-05 与 G-18 在 `aie2e-panel.spec.ts`；Q-02 / Q-03 / Q-06 已由
 * `ai-followup-suggestions.spec.ts` 锁死（蓝本标 ✅），此处不重复。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import { useWorkspaceStore } from '@/stores/workspace'
import { readMirrorFixture } from './aie2e-helpers'

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
  deleteHistory: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

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

function assistant(text: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: overrides.streaming === true }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true,
    ...overrides
  }
}

async function mountPanel(): Promise<ReturnType<typeof mount>> {
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
  api.deleteHistory.mockResolvedValue({})
  api.chat.mockResolvedValue(undefined)
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
  workspace.taskType = null
  workspace.step = null
  workspace.taskId = null
  installIdentityFixture()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Q-04（P2）：建议不被历史整替抹掉（也不留半旧半新）', () => {
  /**
   * 判据：触发一次 `loadHistory` 后，建议区不出现"半旧半新"错配 —— 属于旧轮的建议被清、
   * 新轮建议重建。落地形态：`followups` 是**派生 computed**（读 `messages` 末条 + 当前页面），
   * 历史整替成在途轮（末条流式中）时建议必须当场消失；整替成新的已完成轮时重建 2–3 条。
   */
  it('整替为在途轮 ⇒ 建议立即消失；整替为新的已完成轮 ⇒ 重建且 id 不重复', async () => {
    const store = useAiStore()
    store.messages = [assistant('上一轮答案')]
    store.historyLoaded = true
    store.followupReady = true
    store.lastRoundOutcome = 'ok'
    const wrapper = await mountPanel()

    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(true)
    const before = wrapper.findAll('[data-testid="followup-suggestion"]')
      .map((button) => button.attributes('data-suggestion-id'))

    // 历史整替：新会话正在生成（末条流式中）⇒ 旧建议必须清掉（不许"半旧半新"）
    store.messages = [assistant('新一轮的半截', { streaming: true, done: false })]
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)

    // 整替为新的已完成轮 ⇒ 建议重建，且与旧轮的 id 集合各自内部无重复
    store.messages = [assistant('新的完整答案')]
    store.followupReady = true
    await wrapper.vm.$nextTick()
    const after = wrapper.findAll('[data-testid="followup-suggestion"]')
      .map((button) => button.attributes('data-suggestion-id'))
    expect(after.length).toBeGreaterThanOrEqual(2)
    expect(new Set(after).size).toBe(after.length)
    expect(new Set(before).size).toBe(before.length)
    wrapper.unmount()
  }, 20_000)
})

describe('Q-07（P2）：换会话清空建议（不跨会话串味）', () => {
  it('新建对话后：建议区清空、sessionId 换新', async () => {
    const store = useAiStore()
    store.messages = [assistant('上一会话的答案')]
    store.historyLoaded = true
    store.followupReady = true
    store.lastRoundOutcome = 'ok'
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(true)
    const oldId = store.sessionId

    await store.resetSession()
    await wrapper.vm.$nextTick()

    expect(store.sessionId).not.toBe(oldId)
    expect(store.followupReady).toBe(false)
    expect(wrapper.find('[data-testid="followup-suggestions"]').exists()).toBe(false)
    wrapper.unmount()
  }, 20_000)
})

describe('Q-08（P1）：点即发与 409 的交互 —— 与 R-04 同判', () => {
  /**
   * 判据：无幻影气泡、草稿不双份、提示只 1 条。走 **chip 点击**这条入口（不是手打），
   * 因此额外锁"chip 文案不会被回填进输入框"（批 E · E2：点即发、不回填）。
   */
  it('chip 点击遇 409：气泡不新增、输入框不被写、提示唯一', async () => {
    const store = useAiStore()
    store.messages = []
    store.historyLoaded = true
    const wrapper = await mountPanel()
    const chip = wrapper.find('[data-testid="suggestion-chip"]')
    const text = chip.text()

    const http = await import('@/api/http')
    api.chat.mockRejectedValue(new http.ApiError(
      '该会话已有一轮对话在进行中', 409, 'SESSION_BUSY', { code: 'SESSION_BUSY', runId: 'run-q8' }
    ))
    await chip.trigger('click')
    await flushPromises()

    expect(store.messages).toHaveLength(0) // 无幻影气泡（用户气泡也被回收）
    expect(store.notice).toBe('这条消息没有发出去，请稍后再试。')
    const composer = wrapper.find('[data-testid="composer-input"]')
    expect((composer.element as HTMLTextAreaElement).value).toBe('') // 草稿不双份
    expect((composer.element as HTMLTextAreaElement).value).not.toBe(text)
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true) // 空态还在
    wrapper.unmount()
  }, 20_000)
})

describe('X-13（P2）：草稿不落服务器/不进镜像（敏感值只活在浏览器表单里）', () => {
  /**
   * 判据：草稿只在浏览器（不写服务器、不进模型）；安全台账只记元数据（无值）。
   * 前端可达的一半：**草稿不进会话镜像**（镜像会被落进 `sessionStorage` 并被下一次刷新读回，
   * 也会随 `messages` 一起被外送对账）—— 这是"敏感值只活在表单里"的最小可断言面。
   * 后端台账那一半见 `GenerativeFormSafetyLedgerTest`。
   */
  it('updateFormDraft 之后写镜像：镜像 JSON 不含草稿值', async () => {
    const store = useAiStore()
    store.sessionId = 's-x13'
    store.historyLoaded = true
    store.messages = [assistant('请填写表单')]
    store.activeForm = {
      toolCallId: 'gf-1', runId: 'run-x13', form: { scenario: 'FILTER', fields: [] } as never,
      status: 'filling', error: null, values: {}, fieldErrors: []
    }

    store.updateFormDraft({ secretNote: 'SUPER-SECRET-DRAFT-VALUE' })
    store.writeMirrorNow()

    const mirror = JSON.stringify(readMirrorFixture('s-x13'))
    expect(mirror).not.toContain('SUPER-SECRET-DRAFT-VALUE') // 草稿不进镜像
    expect(JSON.stringify(store.messages)).not.toContain('SUPER-SECRET-DRAFT-VALUE') // 不进消息流
  }, 20_000)
})
