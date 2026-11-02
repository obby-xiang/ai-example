/**
 * #4：transcript 全量历史不再一次渲染 —— 首屏只取最近一页（`offset/limit`），
 * `hasMore` 时消息流内出**单个**「加载更早」入口，向前加载并保持视口不跳。
 *
 * 同时锁住两条不相干的语义：`truncated`（容量裁掉）与 `hasMore`（还能向前取）不互相替代。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import type { AiHistoryMessage } from '@/types/ai'
import { useAiStore, HISTORY_PAGE_SIZE } from '@/stores/ai'
import { applyLocale } from '@/locales'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  getContext: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  reattachRun: vi.fn(),
  confirm: vi.fn(),
  frontendToolResult: vi.fn(),
  deleteHistory: vi.fn(),
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

/** 一段交替 USER/ASSISTANT 的条目流（偶数下标 USER、奇数下标 ASSISTANT）。 */
function entry(index: number): AiHistoryMessage {
  return index % 2 === 0
    ? { type: 'USER', text: `q-${index}` }
    : { type: 'ASSISTANT', text: `a-${index}` }
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
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z'
  })
  api.runsCurrent.mockResolvedValue({ found: false })
  api.getContext.mockResolvedValue({ source: 'none' })
  installIdentityFixture()
  applyLocale('zh-CN')
})

describe('分页 · 面板「加载更早」入口', () => {
  it('hasMore 时消息流内只有一个入口；点击后前插并补偿视口（不跳）', async () => {
    const store = useAiStore()
    store.expanded = true
    store.historyLoaded = true
    store.historyHasMore = true
    store.historyOffset = HISTORY_PAGE_SIZE
    const head = entry(200)
    store.messages = [{
      id: `history-${HISTORY_PAGE_SIZE}`,
      role: head.type === 'USER' ? 'user' : 'assistant',
      content: head.text ?? '',
      parts: [{ kind: 'text', seg: 0, text: head.text ?? '', streaming: false }],
      reasoning: '',
      toolRuns: [],
      pendingCall: null,
      streaming: false,
      done: true
    }]

    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await nextTick()

    const entries = wrapper.findAll('[data-testid="load-earlier-history"]')
    expect(entries).toHaveLength(1)
    expect(wrapper.find('[data-testid="history-truncated"]').exists()).toBe(false)

    const wrap = wrapper.find('[role="log"]').element as HTMLElement
    let height = 1_000
    Object.defineProperty(wrap, 'scrollHeight', { configurable: true, get: () => height })
    Object.defineProperty(wrap, 'clientHeight', { configurable: true, value: 500 })
    // 首条消息元素锚点：前插后它下移 2000px；容器同时因底部流式新增再多长 4000px。
    const anchor = wrapper.find(`[data-msg-id="history-${HISTORY_PAGE_SIZE}"]`).element as HTMLElement
    let anchorTop = 100
    Object.defineProperty(anchor, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({
        top: anchorTop, bottom: anchorTop, left: 0, right: 0, width: 0, height: 0, x: 0, y: 0,
        toJSON: () => ({})
      }) as DOMRect
    })
    wrap.scrollTop = 100
    wrap.dispatchEvent(new Event('scroll'))
    await nextTick()

    const load = vi.spyOn(store, 'loadEarlierHistory').mockImplementation(async () => {
      store.messages = [
        {
          id: 'history-199',
          role: 'user',
          content: 'q-199',
          parts: [{ kind: 'text', seg: 0, text: 'q-199', streaming: false }],
          reasoning: '',
          toolRuns: [],
          pendingCall: null,
          streaming: false,
          done: true
        },
        ...store.messages
      ]
      height = 7_000
      anchorTop = 2_100
    })

    // scroll 后 followBottom 变化会触发一次重渲：重新取按钮（旧 wrapper 可能已脱离文档）
    await wrapper.find('[data-testid="load-earlier-history"]').trigger('click')
    await nextTick()
    await nextTick()

    expect(load).toHaveBeenCalledTimes(1)
    // 只按**首条消息元素**的位移补偿（+2000），不把底部流式新增的 4000px 算进来
    expect(wrap.scrollTop).toBe(2_100)
    wrapper.unmount()
  })
})
