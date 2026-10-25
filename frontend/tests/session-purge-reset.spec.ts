/**
 * 新建对话 = 完整会话销毁：前端必须消费服务端 `clientCleanup`，且 `complete=false` 不得当成功。
 *
 * <p>证红：把 `resetSession` 改回"只调 deleteHistory 不清理"⇒ 精确清理用例红；把
 * `complete=false` 当成功 ⇒ 部分完成提示用例红；用 `sessionStorage.clear()` 代替精确键
 * ⇒ "不能清掉别的键"断言红。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'

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

const OLD_SESSION = 'old-session'
const OTHER_MIRROR = 'ai-mirror:other-session'
const BUSINESS_KEY = 'import-wizard-draft'

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true })
  api.cancel.mockResolvedValue({ cancelled: true })
  api.deleteHistory.mockResolvedValue({})
})

function oldSessionWithArtifacts(): ReturnType<typeof useAiStore> {
  const store = useAiStore()
  store.sessionId = OLD_SESSION
  store.activeRunId = 'run-old'
  sessionStorage.setItem('ai-mirror:' + OLD_SESSION, '{"messages":[]}')
  sessionStorage.setItem(OTHER_MIRROR, '{"messages":["keep"]}')
  sessionStorage.setItem('cm.ai.form.draft.call-1', '{"name":"old-1"}')
  sessionStorage.setItem('cm.ai.form.draft.call-2', '{"name":"old-2"}')
  sessionStorage.setItem('cm.ai.form.draft.call-other', '{"name":"keep"}')
  sessionStorage.setItem(BUSINESS_KEY, 'keep-business')
  return store
}

describe('resetSession 消费 purge clientCleanup', () => {
  it('只删该会话 mirrorKey 与枚举的 draftToolCallIds，绝不清别的键/新会话', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({
      complete: true,
      clientCleanup: {
        mirrorKey: 'ai-mirror:' + OLD_SESSION,
        draftPrefix: 'cm.ai.form.draft.',
        draftToolCallIds: ['call-1', 'call-2']
      }
    })

    const nextId = await (async () => {
      await store.resetSession()
      return store.sessionId
    })()

    expect(nextId).not.toBe(OLD_SESSION)
    expect(sessionStorage.getItem('ai-mirror:' + OLD_SESSION)).toBeNull()
    expect(sessionStorage.getItem('cm.ai.form.draft.call-1')).toBeNull()
    expect(sessionStorage.getItem('cm.ai.form.draft.call-2')).toBeNull()
    expect(sessionStorage.getItem(OTHER_MIRROR)).toBe('{"messages":["keep"]}')
    expect(sessionStorage.getItem('cm.ai.form.draft.call-other')).toBe('{"name":"keep"}')
    expect(sessionStorage.getItem(BUSINESS_KEY)).toBe('keep-business')
    expect(sessionStorage.getItem('ai-session-id')).toBe(nextId)
    expect(store.noticeCode).toBeNull()
  })

  it('complete=false（deferred）按整会话未删净提示，且仍立即换新会话', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({
      complete: false,
      deferred: [{ runId: 'run-old', status: 'RUNNING', reason: 'RUN_ACTIVE' }],
      clientCleanup: {
        mirrorKey: 'ai-mirror:' + OLD_SESSION,
        draftPrefix: 'cm.ai.form.draft.',
        draftToolCallIds: ['call-1']
      }
    })

    await store.resetSession()

    expect(store.sessionId).not.toBe(OLD_SESSION)
    expect(store.messages).toHaveLength(0)
    expect(store.noticeCode).toBe('ai.notice.sessionPurgeIncomplete')
    // 部分完成不等于不清本地残留：该删的精确键仍要删
    expect(sessionStorage.getItem('ai-mirror:' + OLD_SESSION)).toBeNull()
    expect(sessionStorage.getItem('cm.ai.form.draft.call-1')).toBeNull()
    expect(sessionStorage.getItem(BUSINESS_KEY)).toBe('keep-business')
  })

  it('errors 非空同样视为未删净（即便 complete 缺失/为 true）', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({
      errors: ['run[run-old]: 13 键删除失败（可重试）']
    })

    await store.resetSession()

    expect(store.sessionId).not.toBe(OLD_SESSION)
    expect(store.noticeCode).toBe('ai.notice.sessionPurgeIncomplete')
  })

  it('响应缺 clientCleanup 时只清该会话镜像（旧后端兼容），不动其它键', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({ complete: true })

    await store.resetSession()

    expect(sessionStorage.getItem('ai-mirror:' + OLD_SESSION)).toBeNull()
    expect(sessionStorage.getItem(OTHER_MIRROR)).toBe('{"messages":["keep"]}')
    expect(sessionStorage.getItem('cm.ai.form.draft.call-1')).toBe('{"name":"old-1"}')
    expect(sessionStorage.getItem(BUSINESS_KEY)).toBe('keep-business')
    expect(store.noticeCode).toBeNull()
  })
})
