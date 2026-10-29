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
import { formDraftPrefix, formDraftStorageKey, mirrorStorageKey, sessionIdStorageKey } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'

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
  installIdentityFixture()
})

function oldSessionWithArtifacts(): ReturnType<typeof useAiStore> {
  const store = useAiStore()
  store.sessionId = OLD_SESSION
  store.activeRunId = 'run-old'
  sessionStorage.setItem(mirrorStorageKey(OLD_SESSION), '{"messages":[]}')
  sessionStorage.setItem(mirrorStorageKey('other-session'), '{"messages":["keep"]}')
  sessionStorage.setItem(formDraftStorageKey('call-1'), '{"name":"old-1"}')
  sessionStorage.setItem(formDraftStorageKey('call-2'), '{"name":"old-2"}')
  sessionStorage.setItem(formDraftStorageKey('call-other'), '{"name":"keep"}')
  sessionStorage.setItem(BUSINESS_KEY, 'keep-business')
  return store
}

describe('resetSession 消费 purge clientCleanup', () => {
  it('只删该会话 mirrorKey 与枚举的 draftToolCallIds，绝不清别的键/新会话', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({
      complete: true,
      clientCleanup: {
        mirrorKey: mirrorStorageKey(OLD_SESSION),
        draftPrefix: formDraftPrefix(),
        draftToolCallIds: ['call-1', 'call-2']
      }
    })

    const nextId = await (async () => {
      await store.resetSession()
      return store.sessionId
    })()

    expect(nextId).not.toBe(OLD_SESSION)
    expect(sessionStorage.getItem(mirrorStorageKey(OLD_SESSION))).toBeNull()
    expect(sessionStorage.getItem(formDraftStorageKey('call-1'))).toBeNull()
    expect(sessionStorage.getItem(formDraftStorageKey('call-2'))).toBeNull()
    expect(sessionStorage.getItem(mirrorStorageKey('other-session'))).toBe('{"messages":["keep"]}')
    // 批次6B（N10）：换会话按 draft 前缀 + 在场白名单清孤儿键 —— call-other 不在场，一样清掉；
    // 非 draft 键（业务键、别的会话镜像）照旧不动。
    expect(sessionStorage.getItem(formDraftStorageKey('call-other'))).toBeNull()
    expect(sessionStorage.getItem(BUSINESS_KEY)).toBe('keep-business')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(nextId)
    expect(store.noticeCode).toBeNull()
  })

  it('complete=false（deferred）按整会话未删净提示，且仍立即换新会话', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({
      complete: false,
      deferred: [{ runId: 'run-old', status: 'RUNNING', reason: 'RUN_ACTIVE' }],
      clientCleanup: {
        mirrorKey: mirrorStorageKey(OLD_SESSION),
        draftPrefix: formDraftPrefix(),
        draftToolCallIds: ['call-1']
      }
    })

    await store.resetSession()

    expect(store.sessionId).not.toBe(OLD_SESSION)
    expect(store.messages).toHaveLength(0)
    expect(store.noticeCode).toBe('ai.notice.sessionPurgeIncomplete')
    // 部分完成不等于不清本地残留：该删的精确键仍要删
    expect(sessionStorage.getItem(mirrorStorageKey(OLD_SESSION))).toBeNull()
    expect(sessionStorage.getItem(formDraftStorageKey('call-1'))).toBeNull()
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

  it('响应缺 clientCleanup 时只清该会话镜像（旧后端兼容），草稿按换会话口径清孤儿', async () => {
    const store = oldSessionWithArtifacts()
    api.deleteHistory.mockResolvedValue({ complete: true })

    await store.resetSession()

    expect(sessionStorage.getItem(mirrorStorageKey(OLD_SESSION))).toBeNull()
    expect(sessionStorage.getItem(mirrorStorageKey('other-session'))).toBe('{"messages":["keep"]}')
    // 批次6B（N10）：草稿不再依赖 clientCleanup 枚举 —— 换会话即按前缀 + 在场白名单清
    expect(sessionStorage.getItem(formDraftStorageKey('call-1'))).toBeNull()
    expect(sessionStorage.getItem(BUSINESS_KEY)).toBe('keep-business')
    expect(store.noticeCode).toBeNull()
  })
})
