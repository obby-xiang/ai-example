/**
 * S6 批 2 · 发布冲突的视图通道（设计稿 §5.3 / §6.3-1/2 / §7.2-6）。
 *
 * 锁四件事：
 * 1. `tool_result.resultView` 落段（含历史/镜像恢复：镜像里的段原样读回，刷新不丢表）；
 * 2. 冲突检出时 `attachPublishConflict` 同时写 reason（模型侧口径不变）与 resultView（UI 通道）；
 * 3. 视图的行 = 结构化 issues，列 = 配置/行键/原因，截断三件套同真；
 * 4. 无 issues 的调用（旧调用面）行为逐字不变 —— 只写 reason，不产生 resultView。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import type { ResultView } from '@/types/resultview'
import { clearPublishToolLinks, linkPublishTool } from '@/utils/publish-links'
import { MIRROR_PREFIX } from '@/stores/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn()
}))
const watchPublishOutcome = vi.hoisted(() => vi.fn(() => (): void => {}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/stores/task', () => ({
  useTaskStore: () => ({ watchPublishOutcome })
}))

import { useAiStore } from '@/stores/ai'

const SUMMARY = [
  '发布作业 #42 校验冲突明细（共 6 处，仅列前 5 条）',
  '1. CURRENCY#GLOBAL：币种精度与主数据不一致',
  '2. TAX_RULE#PRJ-07：税率区间重叠'
].join('\n')

const ISSUES = [
  { jobId: 42, defCode: 'CURRENCY', rowKey: 'GLOBAL', severity: 'ERROR' as const, message: '币种精度与主数据不一致' },
  { jobId: 42, defCode: 'TAX_RULE', rowKey: 'PRJ-07', severity: 'ERROR' as const, message: '税率区间重叠' }
]

/** 6 处冲突只展示前 5 条：用 6 条 issues 造与服务端同口径的 total。 */
const ISSUES_SIX = Array.from({ length: 6 }, (_, index) => ({
  jobId: 43, defCode: `D${index}`, rowKey: `R${index}`, severity: 'ERROR' as const, message: `冲突 ${index}`
}))

function buildMessage(toolCallId = 'tc-1'): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '',
    parts: [
      { kind: 'text', seg: 0, text: '', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId, name: 'start_publish', kind2: 'BACKEND',
        status: 'failed', args: '{"taskId":3}', result: '发布作业已启动（作业 #42）', requiresAction: false
      }
    ],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

function toolPart(message: ChatMessage, toolCallId: string): ToolPart {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  expect(part).toBeDefined()
  return part as ToolPart
}

/** 帧路径会落到「当前流式助手消息」上（done 消息不再接收帧）——按 toolCallId 跨消息找段。 */
function findPart(messages: ChatMessage[], toolCallId: string): ToolPart {
  for (const message of messages) {
    const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
    if (part) {
      return part as ToolPart
    }
  }
  throw new Error(`未找到工具段：${toolCallId}`)
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.frontendToolResult.mockReset()
  api.reattachRun.mockReset()
  watchPublishOutcome.mockClear()
  clearPublishToolLinks()
})

describe('S6 批2 · tool_result.resultView 落段', () => {
  it('帧带 resultView ⇒ 段上原样落（模型侧 result 不受影响）', () => {
    const store = useAiStore()
    store.messages = [{
      id: 'a-0', role: 'assistant', content: '', parts: [],
      reasoning: '', toolRuns: [], pendingCall: null, streaming: false, done: true
    }]
    const view: ResultView = {
      version: '1.0',
      id: 'rv-list',
      revision: 1,
      kind: 'table',
      status: 'ok',
      data: { inline: { rows: [{ id: 1 }] } },
      columns: [{ key: 'id', type: 'integer' }],
      fallback: { text: 'i18n:resultview.listTasks.fallback', format: 'plain' }
    }
    store.handleFrame({
      type: 'tool_start', toolCallId: 'tc-l', name: 'list_tasks', kind: 'BACKEND', args: '{}', runId: 'run-1', seq: 1
    })
    store.handleFrame({
      type: 'tool_result', toolCallId: 'tc-l', name: 'list_tasks', kind: 'BACKEND',
      ok: true, executed: true, result: '- #1 [EXPORT] 任务', resultView: view,
      messageId: 'tool-tc-l', runId: 'run-1', seq: 2
    })
    const part = findPart(store.messages, 'tc-l')
    expect(part.resultView).toEqual(view)
    expect(part.result).toBe('- #1 [EXPORT] 任务')
  })

  it('帧不带 resultView ⇒ 段上没有该键（旧帧零变化）', () => {
    const store = useAiStore()
    store.messages = [{
      id: 'a-0', role: 'assistant', content: '', parts: [],
      reasoning: '', toolRuns: [], pendingCall: null, streaming: false, done: true
    }]
    store.handleFrame({
      type: 'tool_start', toolCallId: 'tc-n', name: 'list_tasks', kind: 'BACKEND', args: '{}', runId: 'run-1', seq: 1
    })
    store.handleFrame({
      type: 'tool_result', toolCallId: 'tc-n', name: 'list_tasks', kind: 'BACKEND',
      ok: true, executed: true, result: '文本', messageId: 'tool-tc-n', runId: 'run-1', seq: 2
    })
    expect('resultView' in findPart(store.messages, 'tc-n')).toBe(false)
  })

  it('镜像恢复：v2 镜像里的 resultView 原样读回（刷新不丢表）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    const view: ResultView = {
      version: '1.0', id: 'rv-pub', revision: 1, kind: 'table', status: 'ok',
      data: { inline: { rows: ISSUES } },
      columns: [{ key: 'defCode', type: 'string' }, { key: 'rowKey', type: 'string' }, { key: 'message', type: 'string' }],
      fallback: { text: 'i18n:resultview.publishConflict.fallback', format: 'plain' }
    }
    sessionStorage.setItem(MIRROR_PREFIX + sid, JSON.stringify({
      v: 2,
      savedAt: Date.now(),
      lastRunId: null,
      messages: [{
        ...buildMessage('tc-m'),
        parts: [
          { kind: 'text', seg: 0, text: '', streaming: false },
          {
            kind: 'tool', seg: 1, toolCallId: 'tc-m', name: 'start_publish', kind2: 'BACKEND',
            status: 'failed', args: '{"taskId":3}', result: '已启动', reason: SUMMARY,
            requiresAction: false, resultView: view
          }
        ]
      }]
    }))
    api.history.mockResolvedValue({ sessionId: sid, count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue({
      found: false, runId: null, snapshotStatus: null, pendingEntries: [],
      unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
    })

    await store.loadHistory()
    expect(toolPart(store.messages[0] as ChatMessage, 'tc-m').resultView).toEqual(view)
  })
})

describe('S6 批2 · 发布冲突：reason 与 resultView 双写（§5.3）', () => {
  it('attachPublishConflict 带 issues ⇒ 同段落 reason + kind=table 视图（列=配置/行键/原因）', () => {
    const store = useAiStore()
    const message = buildMessage()
    store.messages = [message]
    linkPublishTool(42, { toolCallId: 'tc-1', sessionId: store.sessionId })

    expect(store.attachPublishConflict(42, SUMMARY, ISSUES)).toBe(true)

    const part = toolPart(message, 'tc-1')
    // 模型侧：整串一字不动（P1-4 的"砍不砍"仍是独立的模型侧决策）
    expect(part.reason).toBe(SUMMARY)
    const view = part.resultView as ResultView
    expect(view.kind).toBe('table')
    expect(view.columns?.map((column) => column.key)).toEqual(['defCode', 'rowKey', 'message'])
    // 数据单份 + 最小化：行只带列的三个键（jobId/severity 这类内部字段不随视图下发）
    expect((view.data?.inline as { rows: unknown[] }).rows).toEqual([
      { defCode: 'CURRENCY', rowKey: 'GLOBAL', message: '币种精度与主数据不一致' },
      { defCode: 'TAX_RULE', rowKey: 'PRJ-07', message: '税率区间重叠' }
    ])
    expect(view.fallback.text).toBe('i18n:resultview.publishConflict.fallback')
    expect(view.id).toBe('rv-tc-1')
    expect(view.meta?.source).toBe('validation_issue')
    expect(message.toolRuns[0]?.reason).toBe(SUMMARY) // 派生投影锁步
  })

  it('超出 5 条 ⇒ partial + meta.truncated{shownRows,totalRows}', () => {
    const store = useAiStore()
    const message = buildMessage()
    store.messages = [message]
    linkPublishTool(43, { toolCallId: 'tc-1', sessionId: store.sessionId })

    store.attachPublishConflict(43, SUMMARY, ISSUES_SIX)
    const view = toolPart(message, 'tc-1').resultView as ResultView
    expect(view.status).toBe('partial')
    expect(view.meta?.truncated).toEqual({ shownRows: 5, totalRows: 6 })
    expect((view.data?.inline as { rows: unknown[] }).rows).toHaveLength(5)
  })

  it('旧调用面（只给 summary）⇒ 只写 reason，不产生视图（回退路径保留）', () => {
    const store = useAiStore()
    const message = buildMessage()
    store.messages = [message]
    linkPublishTool(44, { toolCallId: 'tc-1', sessionId: store.sessionId })

    expect(store.attachPublishConflict(44, SUMMARY)).toBe(true)
    expect(toolPart(message, 'tc-1').reason).toBe(SUMMARY)
    expect(toolPart(message, 'tc-1').resultView).toBeUndefined()
  })
})
