/**
 * `/chat` 410 `SESSION_PURGED` 的**本地恢复**（服务端已销毁该会话，前端不得继续复用旧 id）。
 *
 * <p>口径：
 * <ul>
 * <li>换 id 复用 `adoptFreshSession()`（换新 sessionId + 清现场：镜像、乐观气泡、在途轮），
 *   **不再** DELETE 已销毁的会话、**不**自动重发；</li>
 * <li>本次两条乐观消息不留残影，用户输入由面板既有「失败回稿」留在输入框一处
 *   （`send()` 返回非 ok/cancelled 即可，面板据此回稿）；</li>
 * <li>提示走 locale（`ai.notice.sessionPurged`），不回显后端中文原文；</li>
 * <li>不清别的会话/业务键（禁止 `storage.clear()`）：草稿、`cm.locale`、`ai-panel-expanded` 都保留。</li>
 * </ul>
 *
 * <p>`resumeSnapshotStream`（`GET /api/ai/events/{runId}`）**不加**同口径：已核后端
 * `AiController` 的会话销毁守卫只装在 `/chat` 开新轮之前，reattach/事件流不查 purge guard，
 * 该路径不会收到 410 `SESSION_PURGED`（凭空加分支只会掩盖真实契约）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'
import { applyLocale } from '@/locales'
import { useAiStore } from '@/stores/ai'
import { formDraftStorageKey, mirrorStorageKey, sessionIdStorageKey } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

/** 旧会话号：会话号契约是十进制长整（`normalizeSessionId` 只收正整数），故用数字串。 */
const OLD_SESSION = '4101'
const DRAFT_CALL_ID = 'call-9'
const BUSINESS_KEY = 'import-wizard-draft'
const LOCALE_KEY = 'cm.locale'
const EXPANDED_KEY = 'ai-panel-expanded'

function purged(): ApiError {
  return new ApiError('该会话已被销毁，请新建对话（使用新的 sessionId）后重试', 410, ErrorCode.SESSION_PURGED, {
    code: ErrorCode.SESSION_PURGED,
    message: '该会话已被销毁，请新建对话（使用新的 sessionId）后重试',
    sessionId: OLD_SESSION,
    action: 'NEW_SESSION'
  })
}

/** 旧会话的页签现场：会话 id + 镜像 + 与 AI 无关的业务键 / 展示偏好。 */
function seedPurgedTab(): ReturnType<typeof useAiStore> {
  sessionStorage.setItem(sessionIdStorageKey(), OLD_SESSION)
  sessionStorage.setItem(mirrorStorageKey(OLD_SESSION), JSON.stringify({ v: 2, savedAt: 1, messages: [] }))
  sessionStorage.setItem(formDraftStorageKey(DRAFT_CALL_ID), '{"region":"HB"}')
  sessionStorage.setItem(BUSINESS_KEY, 'keep-business')
  sessionStorage.setItem(LOCALE_KEY, 'zh-CN')
  sessionStorage.setItem(EXPANDED_KEY, '1')
  return useAiStore()
}

/**
 * 有界条件等待（只推进微任务）。
 *
 * <p>3d801ff1 起 `send()` 取号前先 `await ensureSession()`（已有 sessionId 也先等页签归属判定），
 * `/chat` 不再落在 `send()` 的第一个微任务里 —— 要等"请求真的发出去"，不能只等一个 `Promise.resolve()`。
 */
async function until(condition: () => boolean, label: string): Promise<void> {
  for (let tick = 0; tick < 500; tick += 1) {
    if (condition()) {
      return
    }
    await Promise.resolve()
  }
  throw new Error(`等待超时：${label}`)
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: OLD_SESSION, count: 0, messages: [] })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
  installIdentityFixture()
})

afterEach(() => {
  applyLocale('zh-CN')
})

describe('410 SESSION_PURGED ⇒ 本地换会话恢复（不 DELETE、不自动重发）', () => {
  it('换新 sessionId、乐观气泡与旧镜像不残留、提示走 locale', async () => {
    const store = seedPurgedTab()
    expect(store.sessionId).toBe(OLD_SESSION)
    api.chat.mockRejectedValueOnce(purged())

    const result = await store.send('帮我导一张表')

    // 这次发送按失败收口（面板据此把原稿留在输入框，且不会自动重试）
    expect(result.outcome).toBe('error')
    // 换 id：内存与 sessionStorage 双写一致
    const nextId = store.sessionId
    expect(nextId).not.toBe(OLD_SESSION)
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(nextId)
    // 本次两条乐观消息（用户 + 空气泡）都不残留；清场走 adoptFreshSession
    expect(store.messages).toHaveLength(0)
    expect(store.loading).toBe(false)
    expect(store.activeRunId).toBeNull()
    // 旧会话镜像被移除（下一次刷新不会再把它读回来，也不会拿旧 id 复跑）
    expect(sessionStorage.getItem(mirrorStorageKey(OLD_SESSION))).toBeNull()
    // 通知：本地模板 + 中文译文（不回显后端中文原文时也不出现"新建对话后重试"这类后端措辞差异）
    expect(store.noticeCode).toBe('ai.notice.sessionPurged')
    expect(store.notice).toContain('已为你新建对话')
    // 不重复 DELETE 已销毁的会话，也不自动重发
    expect(api.deleteHistory).not.toHaveBeenCalled()
    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0]![0]).toMatchObject({ sessionId: OLD_SESSION })
  })

  it('不清别的会话/业务键：草稿按换会话口径清孤儿，业务键/locale/展开态原样保留', async () => {
    const store = seedPurgedTab()
    api.chat.mockRejectedValueOnce(purged())

    await store.send('再试一次')

    // 批次6B（N10）：换会话按 draft 前缀 + 在场白名单清孤儿键（DRAFT_CALL_ID 不在场）
    expect(sessionStorage.getItem(formDraftStorageKey(DRAFT_CALL_ID))).toBeNull()
    expect(sessionStorage.getItem(BUSINESS_KEY)).toBe('keep-business')
    expect(sessionStorage.getItem(LOCALE_KEY)).toBe('zh-CN')
    expect(sessionStorage.getItem(EXPANDED_KEY)).toBe('1')
  })

  it('下一次发送带新 sid，不再复用旧 sessionId', async () => {
    const store = seedPurgedTab()
    api.chat.mockRejectedValueOnce(purged())
    await store.send('第一条')
    const nextId = store.sessionId

    api.chat.mockResolvedValueOnce(undefined)
    const second = await store.send('第二条')
    expect(second.outcome).toBe('ok')

    const calls = api.chat.mock.calls
    expect(calls).toHaveLength(2)
    expect(calls[1]![0]).toMatchObject({ sessionId: nextId, message: '第二条' })
    expect(calls[1]![0].sessionId).not.toBe(OLD_SESSION)
  })

  it('英文界面提示走英文模板（不出现中文）', async () => {
    applyLocale('en-US')
    const store = seedPurgedTab()
    api.chat.mockRejectedValueOnce(purged())

    await store.send('export a sheet')

    expect(store.noticeCode).toBe('ai.notice.sessionPurged')
    expect(store.notice).not.toMatch(/[\u4e00-\u9fff]/)
    expect(store.notice).toContain('new conversation')
  })

  it('失败时本轮身份已被换掉（用户期间已新建对话）⇒ 不二次换会话、不覆盖提示', async () => {
    const store = seedPurgedTab()
    let reject: ((error: unknown) => void) | null = null
    api.chat.mockImplementation(() => new Promise((_resolve, fail) => {
      reject = fail
    }))

    const sending = store.send('慢请求')
    await until(() => reject !== null, 'send() 没有走到 aiApi.chat')
    // 期间用户点了「新建对话」：本轮已不是当前轮
    const adopted = store.adoptFreshSession()
    reject!(purged())
    const result = await sending

    expect(store.sessionId).toBe(adopted)
    expect(result.outcome).toBe('interrupted')
    expect(store.noticeCode).toBeNull()
  })
})
