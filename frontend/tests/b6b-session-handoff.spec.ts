/**
 * 批次6B（AR-13 / AR-14 / N6-N10）：会话切换的"静默、清场、只读"三件事。
 *
 * <p>本套用例只锁四组可判定行为：
 * <ul>
 * <li><b>复制页签</b>：判为 duplicate 时静默换新会话，notice/noticeCode 均为 null，
 *   不提供任何接回原会话的入口（不读原历史、不把旧会话 id 写回）；</li>
 * <li><b>新会话残留</b>：`adoptFreshSession()` 清掉工作区 chip（`contextSync`）、ui_event 回执
 *   （`lastUiEventResult`）与上一轮结局（`lastRoundOutcome`），notice 级别不继承旧轮；</li>
 * <li><b>陈旧确认卡</b>：后端 `UNKNOWN_TOOL_CALL` 不把卡改回可点，也不把含 `runId/toolCallId`
 *   的后端原文当通知；卡片落只读终态；</li>
 * <li><b>草稿孤儿键</b>：换会话按 `cm.ai.form.draft.` 前缀 + 在场白名单清，禁用整份 clear。</li>
 * </ul>
 *
 * <p>复制页签不再写通知；陈旧确认卡仍断言 `noticeCode` 而不是译文，避免绑定具体语言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import { useAiStore } from '@/stores/ai'
import {
  AI_IDENTITY_KEY,
  mirrorStorageKey,
  sessionIdStorageKey,
  sessionLockName
} from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import { formDraftKey } from '@/utils/form-draft'
import type { ChatMessage, ToolPart } from '@/types/ai'
import {
  channels,
  FakeBroadcastChannel,
  FakeLockManager,
  seedTab
} from './tab-ownership-fixtures'

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

let locks: FakeLockManager

beforeEach(() => {
  sessionStorage.clear()
  channels.clear()
  setActivePinia(createPinia())
  locks = new FakeLockManager()
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)

  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
  api.deleteHistory.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  installIdentityFixture()
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

/** 一条带确认门待决段的助手消息（`confirm()` 的唯一入参形态）。 */
function gateMessage(toolCallId: string, runId: string): ChatMessage {
  return {
    id: 'assistant-b6b',
    role: 'assistant',
    runId,
    content: '',
    parts: [{
      kind: 'tool',
      seg: 1,
      toolCallId,
      name: 'start_export',
      kind2: 'CONFIRM',
      status: 'pending',
      requiresAction: true,
      args: '{}'
    }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: false
  }
}

function partOf(store: ReturnType<typeof useAiStore>, toolCallId: string): ToolPart {
  for (const message of store.messages) {
    for (const part of message.parts) {
      if (part.kind === 'tool' && part.toolCallId === toolCallId) {
        return part
      }
    }
  }
  throw new Error(`tool part not found: ${toolCallId}`)
}

describe('复制页签：静默换新会话，不接回原会话', () => {
  it('判为复制 ⇒ 换到自己的新会话且 notice/noticeCode 为空，旧轮状态不跨会话', async () => {
    seedTab('9301')
    locks.hold(sessionLockName('9301'))

    const store = useAiStore()
    store.lastRoundOutcome = 'error'
    store.contextSync = { kind: 'workspace_changed', at: 1 } as never
    store.lastUiEventResult = { event: 'open', handled: false, message: 'old' }

    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).not.toBe('9301')
    expect(store.notice).toBeNull()
    expect(store.noticeCode).toBeNull()
    expect(store.noticeSeverity).toBe('info')
    expect(store.lastRoundOutcome).toBe('ok')
    expect(store.contextSync).toBeNull()
    expect(store.lastUiEventResult).toBeNull()
    // 不接回原会话：不读原历史、不把旧 id 写回本页签
    expect(api.history).not.toHaveBeenCalledWith('9301')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(store.sessionId)
  })
})

describe('新会话清场：工作区 chip / ui_event 回执 / 轮次结局不跨会话', () => {
  it('adoptFreshSession ⇒ contextSync、lastUiEventResult、lastRoundOutcome 复位，notice 级别自成', () => {
    seedTab('9302')
    const store = useAiStore()
    store.lastRoundOutcome = 'error'
    store.contextSync = { kind: 'workspace_changed', at: 1 } as never
    store.lastUiEventResult = { event: 'open', handled: true, message: 'done' }
    store.noticeSeverity = 'error'
    store.showRawNotice('old error')

    store.adoptFreshSession()

    expect(store.contextSync).toBeNull()
    expect(store.lastUiEventResult).toBeNull()
    expect(store.lastRoundOutcome).toBe('ok')
    expect(store.notice).toBeNull()
    expect(store.noticeCode).toBeNull()
    expect(store.noticeSeverity).toBe('info')
  })
})

describe('陈旧确认卡：按码给人话、落只读、不回可点', () => {
  it('陈旧确认卡 UNKNOWN_TOOL_CALL：人话只读、不透露内部 ID、不恢复可点', async () => {
    const store = useAiStore()
    store.activeRunId = 'run-b6b-stale'
    store.messages = [gateMessage('call-b6b-stale', 'run-b6b-stale')]
    api.confirm.mockRejectedValue(new ApiError(
      '未知或已过期的 runId/toolCallId', 409, 'UNKNOWN_TOOL_CALL',
      { code: 'UNKNOWN_TOOL_CALL', runId: 'run-b6b-stale', toolCallId: 'call-b6b-stale' }
    ))

    await store.confirm(true, undefined, 'call-b6b-stale')

    const part = partOf(store, 'call-b6b-stale')
    expect(part.status).toBe('expired')
    expect(part.requiresAction).toBe(false)
    expect(part.decision).toBeUndefined()
    expect(store.noticeCode).toBe('ai.notice.staleConfirmCard')
    expect(store.notice ?? '').not.toContain('run-b6b-stale')
    expect(store.notice ?? '').not.toContain('call-b6b-stale')
    expect(store.notice ?? '').not.toContain('UNKNOWN_TOOL_CALL')
    expect(store.notice ?? '').not.toContain('未知或已过期')
    expect(store.gateSubmissions['call-b6b-stale']).toBeUndefined()

    // 再点一次不会重新提交：卡已不是待决态
    await store.confirm(true, undefined, 'call-b6b-stale')
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(store.noticeCode).toBe('ai.notice.noPendingDecision')
  })
})

describe('换会话清草稿孤儿键：前缀 + 在场白名单，禁止 clear', () => {
  it('adoptFreshSession ⇒ 清光本页签草稿，业务键与别的会话镜像不动', () => {
    seedTab('9303')
    const store = useAiStore()
    sessionStorage.setItem(formDraftKey('stale-1'), '{"a":1}')
    sessionStorage.setItem(formDraftKey('stale-2'), '{"b":2}')
    sessionStorage.setItem('import-wizard-draft', '{"step":2}')
    sessionStorage.setItem(mirrorStorageKey('other-session'), '{"messages":[]}')

    store.adoptFreshSession()

    expect(sessionStorage.getItem(formDraftKey('stale-1'))).toBeNull()
    expect(sessionStorage.getItem(formDraftKey('stale-2'))).toBeNull()
    expect(sessionStorage.getItem('import-wizard-draft')).toBe('{"step":2}')
    expect(sessionStorage.getItem(mirrorStorageKey('other-session'))).toBe('{"messages":[]}')
    // 只由前缀键决定删除范围：不是 sessionStorage.clear()
    expect([...Array(sessionStorage.length)].map((_, i) => sessionStorage.key(i)).sort()).toEqual(
      [AI_IDENTITY_KEY, 'import-wizard-draft', mirrorStorageKey('other-session')].sort()
    )
  })

  it('在场白名单：活动表单/排队/待动作段引用的草稿保留，孤儿键才删', () => {
    const store = useAiStore()
    sessionStorage.setItem(formDraftKey('keep-active'), '{"a":1}')
    sessionStorage.setItem(formDraftKey('keep-queued'), '{"b":2}')
    sessionStorage.setItem(formDraftKey('orphan'), '{"c":3}')
    sessionStorage.setItem('ai-panel-expanded', '1')
    store.activeForm = {
      toolCallId: 'keep-active',
      runId: 'run-1',
      form: { fields: [] },
      status: 'filling',
      error: null,
      values: {},
      fieldErrors: []
    } as never
    store.queuedForms = [{
      toolCallId: 'keep-queued',
      runId: 'run-1',
      form: { fields: [] },
      values: {}
    } as never]

    store.pruneOrphanFormDrafts()

    expect(sessionStorage.getItem(formDraftKey('keep-active'))).toBe('{"a":1}')
    expect(sessionStorage.getItem(formDraftKey('keep-queued'))).toBe('{"b":2}')
    expect(sessionStorage.getItem(formDraftKey('orphan'))).toBeNull()
    expect(sessionStorage.getItem('ai-panel-expanded')).toBe('1')
  })
})

describe('批次2/4/5 既有语义不回退（本批只写不跑）', () => {
  it('换会话仍清活动表单与排队表单（批次4），且恢复错误态不残留（批次2）', () => {
    seedTab('9304')
    const store = useAiStore()
    store.activeForm = { toolCallId: 'gf-1' } as never
    store.queuedForms = [{ toolCallId: 'gf-2' } as never]
    sessionStorage.setItem(formDraftKey('gf-1'), '{}')
    sessionStorage.setItem(formDraftKey('gf-2'), '{}')
    store.historyError = { scope: 'history', status: 503, code: 'X' } as never
    store.contextError = { scope: 'context', status: 503, code: 'Y' } as never
    store.contextStatus = 'error'

    store.adoptFreshSession()

    expect(store.activeForm).toBeNull()
    expect(store.queuedForms).toEqual([])
    expect(store.historyError).toBeNull()
    expect(store.contextError).toBeNull()
    expect(store.contextStatus).toBe('idle')
    expect(sessionStorage.getItem(formDraftKey('gf-1'))).toBeNull()
    expect(sessionStorage.getItem(formDraftKey('gf-2'))).toBeNull()
  })
})
