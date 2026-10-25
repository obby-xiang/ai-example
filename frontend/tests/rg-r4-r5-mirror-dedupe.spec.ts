/**
 * 批G 真机回归（第二轮）store 层回归锁 —— R4（挂起态镜像 inFlight）/ R5·R2（重复用户气泡）。
 *
 * <p>
 * 两条都是「刷新恢复快照」的判据错误，实证见会话内的回归报告：
 * - R4：`writeMirrorNow()` 以 `loading` 单一维度写 `inFlight`，而**挂起态**语义是
 *   `loading=false && suspended=true`（B1「挂起即断」）⇒ 还在等人决策的一轮被镜像标成
 *   "不在途"，`mergeMirrorInFlightRound` 首行直接返回，只存在于镜像里的同段正文与已执行卡
 *   全丢（真机：5 张卡只剩一张重建的确认卡）；
 * - R5/R2：判重逐字比原文，而后端 `InputSanitizer` 用 NFKC 把全角标点归一成半角再落记忆
 *   ⇒ 历史与镜像的两条 head 永远不等 ⇒ 同一句提问出现两条。
 *
 * <p>
 * 断言口径：镜像侧 "在途" 必须覆盖挂起；同一句提问（标点全半角之差）刷新后只有一条气泡，
 * 而**真的不同**的提问仍照补（宁可少补、不可双份，但也不能把新提问吞掉）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, MessagePart } from '@/types/ai'

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

const MIRROR_PREFIX = 'ai-mirror:'

interface MirrorShape {
  inFlight?: boolean
  messages: Array<{ id: string; role: string; content: string; parts: MessagePart[] }>
}

function mirrorOf(sessionId: string): MirrorShape | null {
  const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
  return raw ? (JSON.parse(raw) as MirrorShape) : null
}

function textPart(text: string, seg = 0): MessagePart {
  return { kind: 'text', seg, text, streaming: false }
}

function toolPart(
  toolCallId: string,
  name: string,
  status: 'pending' | 'running' | 'succeeded' | 'cancelled',
  requiresAction: boolean,
  seg: number
): MessagePart {
  return {
    kind: 'tool',
    seg,
    toolCallId,
    name,
    kind2: 'CONFIRM',
    status,
    args: '{}',
    requiresAction
  }
}

function userMessage(id: string, text: string): ChatMessage {
  return {
    id,
    role: 'user',
    content: text,
    parts: [textPart(text)],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

function assistantMessage(id: string, parts: MessagePart[], content = ''): ChatMessage {
  return {
    id,
    role: 'assistant',
    content,
    parts,
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

async function freshStore() {
  vi.resetModules()
  const pinia = await import('pinia')
  pinia.setActivePinia(pinia.createPinia())
  const mod = await import('@/stores/ai')
  const store = mod.useAiStore()
  // 模块级镜像状态（mirrorStarted / mirrorTimer）不随 resetModules 复位：显式归零
  store.messages = []
  store.loading = false
  store.suspended = false
  store.stopping = false
  store.notice = null
  store.activeRunId = null
  store.runSeq = {}
  store.historyLoaded = false
  store.lastRoundOutcome = 'ok'
  return store
}

/** 挂起轮的镜像现场：正文 + 5 张已执行卡 + 1 张等决策的确认卡（只有镜像里有）。 */
function suspendedRoundMessages(): ChatMessage[] {
  const parts: MessagePart[] = [
    textPart('已执行 5 项，最后一步等你确认：'),
    ...Array.from({ length: 5 }, (_, index) =>
      toolPart(`tc-exec-${index + 1}`, 'list_tasks', 'succeeded', false, index + 1)),
    toolPart('tc-confirm', 'start_export', 'pending', true, 6)
  ]
  return [userMessage('u-r4', '帮我导出 CURRENCY 配置'), assistantMessage('a-r4', parts, '已执行 5 项，最后一步等你确认：')]
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [],
    awaitingExternal: false, archiveMaxSeq: 0
  })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('批G · R4：挂起态镜像的 inFlight（loading=false + suspended=true 仍是"在途轮"）', () => {
  it('挂起态写镜像：inFlight 必须为 true（不能只看 loading）', async () => {
    const store = await freshStore()
    store.sessionId = 's-r4-flag'
    store.historyLoaded = true
    store.messages = suspendedRoundMessages()
    store.loading = false
    store.suspended = true
    store.activeRunId = 'run-r4'

    store.writeMirrorNow()

    expect(mirrorOf('s-r4-flag')?.inFlight).toBe(true)
    // 反证：收流后（既不在途也不挂起）的写盘仍是 false —— 判据没被放宽成常量
    store.suspended = false
    store.writeMirrorNow()
    expect(mirrorOf('s-r4-flag')?.inFlight).toBe(false)
  }, 20_000)

  it('挂起轮刷新：镜像里同段的正文与 5 张已执行卡都在（不止重建的确认卡）', async () => {
    const writer = await freshStore()
    writer.sessionId = 's-r4-reload'
    writer.historyLoaded = true
    writer.messages = suspendedRoundMessages()
    writer.loading = false
    writer.suspended = true
    writer.activeRunId = 'run-r4'
    writer.writeMirrorNow()

    const reloaded = await freshStore()
    reloaded.sessionId = 's-r4-reload'
    // 后端挂起当刻的真实形态：工具配对只在轮终局才进记忆 ⇒ /history 只有 USER
    api.history.mockResolvedValue({
      sessionId: 's-r4-reload',
      count: 1,
      messages: [{ type: 'USER', text: '帮我导出 CURRENCY 配置' }]
    })
    api.runsCurrent.mockResolvedValue({
      found: true,
      runId: 'run-r4',
      snapshotStatus: 'SUSPENDED',
      awaitingExternal: true,
      unresolvedExternal: [],
      archiveMaxSeq: 9,
      pendingEntries: [{
        toolCallId: 'tc-confirm',
        name: 'start_export',
        kind: 'CONFIRM',
        entryStatus: 'PENDING',
        req: true,
        arguments: '{}'
      }]
    })

    await reloaded.loadHistory()

    const assistants = reloaded.messages.filter((message) => message.role === 'assistant')
    expect(assistants).toHaveLength(1) // 不新起气泡、不双份
    const host = assistants[0] as ChatMessage
    expect(host.content).toContain('已执行 5 项')
    const executed = host.parts.filter(
      (part) => part.kind === 'tool' && part.toolCallId.startsWith('tc-exec')
    )
    expect(executed).toHaveLength(5) // 已执行卡全在（R4 的丢卡点）
    const confirm = host.parts.find((part) => part.kind === 'tool' && part.toolCallId === 'tc-confirm')
    expect(confirm !== undefined && confirm.kind === 'tool' ? confirm.requiresAction : false).toBe(true)
    expect(reloaded.suspended).toBe(true)
  })

  it('收流完成（既不在途也不挂起）的轮次仍按"不在途"合并：历史回答优先，不双份', async () => {
    const writer = await freshStore()
    writer.sessionId = 's-r4-done'
    writer.historyLoaded = true
    writer.messages = suspendedRoundMessages()
    writer.loading = false
    writer.suspended = false
    writer.writeMirrorNow()
    expect(mirrorOf('s-r4-done')?.inFlight).toBe(false)
  })
})

describe('批G · R5/R2：判重按"同口径归一化"（后端 InputSanitizer 会把全角标点折成半角）', () => {
  const FULL_WIDTH = '请用不少于 800 字，分点详细讲解「配置版本管理」的实现与边界。'
  // 后端 InputSanitizer 走 NFKC：全角逗号/分号/感叹号会被折成半角（。、本身不在 NFKC 映射内，
  // 真机两条 head 的差异也正是逗号这一处）
  const HALF_WIDTH = '请用不少于 800 字,分点详细讲解「配置版本管理」的实现与边界。'

  it('同一句提问（全角/半角标点之差）刷新后只有一条用户气泡', async () => {
    const store = await freshStore()
    store.sessionId = 's-r5'
    sessionStorage.setItem(MIRROR_PREFIX + 's-r5', JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-r5',
      inFlight: true,
      runSeq: {},
      messages: [
        userMessage('u-mirror', FULL_WIDTH),
        assistantMessage('a-mirror', [textPart('正在组织答案…')], '正在组织答案…')
      ]
    }))
    api.history.mockResolvedValue({
      sessionId: 's-r5',
      count: 1,
      messages: [{ type: 'USER', text: HALF_WIDTH }]
    })

    await store.loadHistory()

    // 后端已把这轮的提问写进记忆（半角形态）⇒ 不得再补镜像那条
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    // 镜像里在途的助手消息照补（轮次仍在途，正文不能丢）
    expect(store.messages.filter((message) => message.role === 'assistant')).toHaveLength(1)
  })

  it('真的不同的提问照补（归一化不得把新提问吞掉）', async () => {
    const store = await freshStore()
    store.sessionId = 's-r5b'
    sessionStorage.setItem(MIRROR_PREFIX + 's-r5b', JSON.stringify({
      v: 2,
      savedAt: 1,
      lastRunId: 'run-r5b',
      inFlight: true,
      runSeq: {},
      messages: [
        userMessage('u-mirror', '换成另一种讲法：先说边界条件'),
        assistantMessage('a-mirror', [textPart('好的…')], '好的…')
      ]
    }))
    api.history.mockResolvedValue({
      sessionId: 's-r5b',
      count: 1,
      messages: [{ type: 'USER', text: HALF_WIDTH }]
    })

    await store.loadHistory()

    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(2)
  })
})
