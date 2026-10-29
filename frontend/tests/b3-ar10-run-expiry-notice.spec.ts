/**
 * 批次3 补齐 · AR-10：「已到期」不是卡悄悄没了（只写不跑；聚焦 RED/GREEN 在
 * `b3-ar10-run-expiry.spec.ts`）。
 *
 * 场景：本页已知的寿命锚点已经到期，随后再对账（`/runs/current`）发现快照确实没了
 * （`found=false`）—— 此时必须**可见**地告知"运行态已按保留期失效"，而不是静默封口。
 * 出口由面板提供（刷新状态 / 新建会话），store 只落通知码，不落用户可见串。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { applyLocale } from '@/locales'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  confirm: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const emptyCurrent = {
  found: false,
  runId: null,
  snapshotStatus: null,
  pendingEntries: [],
  unresolvedExternal: [],
  awaitingExternal: false,
  archiveMaxSeq: 0,
  snapshotRemainingMs: null,
  snapshotExpiresAt: null
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  applyLocale('zh-CN')
  installIdentityFixture()
})

describe('AR-10 · 到期后不静默', () => {
  it('已知锚点已到期且快照已消失 ⇒ 落可见通知并清锚点', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const ai = useAiStore()
    ai.sessionId = 'sess-expired'
    ai.historyLoaded = true
    ai.runExpiry = {
      runId: 'run-old',
      remainingMs: 1_000,
      expiresAt: 1_000,
      receivedAtMs: Date.now() - 60_000
    }
    api.runsCurrent.mockResolvedValue(emptyCurrent)

    await ai.restoreSuspendedSnapshot()

    // 锚点保留为"已到期"（卡面继续显示已失效 + 出口），并落可见通知
    expect(ai.runExpiry?.runId).toBe('run-old')
    expect(ai.runExpiry?.remainingMs).toBe(0)
    expect(ai.noticeCode).toBe('ai.notice.runExpired')
    expect(ai.noticeSeverity).toBe('warning')
  })

  it('没有已知锚点的空态不冒充到期（不写通知）', async () => {
    const { useAiStore } = await import('@/stores/ai')
    const ai = useAiStore()
    ai.sessionId = 'sess-unknown'
    ai.historyLoaded = true
    api.runsCurrent.mockResolvedValue(emptyCurrent)

    await ai.restoreSuspendedSnapshot()

    expect(ai.runExpiry).toBeNull()
    expect(ai.noticeCode).toBeNull()
  })
})
