/**
 * Task 3：跨身份草稿与编辑锚点的精确用例（一条用例锁一个行为）。
 *
 * <p>切换租户/用户 = 换会话：输入框草稿与「编辑重跑」锚点都属于**旧身份**的现场，
 * 切走后不得留在输入区，也不得让下一次发送按旧锚点改写/截断消息。
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { mirrorStorageKey, resetAiIdentityCache } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import type { ChatMessage } from '@/types/ai'

const api = vi.hoisted(() => ({
  tenantUsers: vi.fn(),
  createSession: vi.fn(),
  health: vi.fn(),
  history: vi.fn(),
  getContext: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  retryModelStep: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
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

const DIRECTORY = [
  { tenantId: 7, users: [{ userId: 71, displayName: '甲' }, { userId: 72, displayName: '乙' }] }
]

function messageOf(id: string, role: 'user' | 'assistant', text: string): ChatMessage {
  return {
    id,
    role,
    content: text,
    parts: [{ kind: 'text', seg: 0, text, streaming: false }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  resetAiIdentityCache()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  installIdentityFixture({ sessionId: 7001 })
  api.tenantUsers.mockResolvedValue(DIRECTORY)
  api.createSession.mockResolvedValue('7002')
  api.health.mockResolvedValue({
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ sessionId: '7001', count: 0, messages: [] })
  api.getContext.mockRejectedValue(new Error('no context'))
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({ cancelled: true })
  api.chat.mockResolvedValue(undefined)
})

enableAutoUnmount(afterEach)

describe('Task 3 · 跨身份草稿与编辑锚点', () => {
  it('切换用户时清掉输入草稿、编辑锚点与失效旧身份镜像（后端历史不删）', async () => {
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await flushPromises()
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()

    store.messages = [messageOf('u-1', 'user', '原始问题'), messageOf('a-1', 'assistant', '回答')]
    store.historyLoaded = true
    await flushPromises()

    // 旧身份（7:71）会话 7001 的浏览器镜像：切走后本页签再也读不到它（切回也发新号）。
    const oldMirrorKey = mirrorStorageKey('7001', { tenantId: 7, userId: 71 })
    store.writeMirrorNow()
    expect(sessionStorage.getItem(oldMirrorKey)).not.toBeNull()

    // 先打一份草稿，再进「编辑重跑」态（输入框被回填旧提问，锚点指向 u-1）。
    await wrapper.find('[data-testid="composer-input"]').setValue('还没发出去的草稿')
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await wrapper.find('[data-testid="edit-msg-u-1"]').trigger('click')
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value)
      .toBe('原始问题')

    // 同租户换用户 → 换身份/换会话。
    await wrapper.find('[data-testid="user-select"]').setValue('72')
    await flushPromises()

    expect(store.identity).toEqual({ tenantId: 7, userId: 72 })
    expect(store.sessionId).toBe('7002')
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('')
    expect(store.editAnchorId).toBeNull()
    // 失效的旧身份镜像被清掉（新身份下不会冒出旧会话的镜像）；后端历史不在前端处理范围内。
    expect(sessionStorage.getItem(oldMirrorKey)).toBeNull()
    expect(api.deleteHistory).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('重选同一身份不清草稿；真正切换才清', async () => {
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await flushPromises()
    const { useAiStore } = await import('@/stores/ai')
    const store = useAiStore()
    store.historyLoaded = true

    await wrapper.find('[data-testid="composer-input"]').setValue('还要继续写的草稿')
    const issuedBefore = api.createSession.mock.calls.length

    // 下拉"重选"当前用户（值仍是 71）：不是实际切换 —— 草稿必须留在输入框。
    await wrapper.find('[data-testid="user-select"]').setValue('71')
    await flushPromises()
    expect(store.identity).toEqual({ tenantId: 7, userId: 71 })
    expect(api.createSession.mock.calls.length).toBe(issuedBefore)
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value)
      .toBe('还要继续写的草稿')

    // 真正换到 72：此时才清。
    await wrapper.find('[data-testid="user-select"]').setValue('72')
    await flushPromises()
    expect(store.identity).toEqual({ tenantId: 7, userId: 72 })
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toBe('')
    wrapper.unmount()
  })
})
