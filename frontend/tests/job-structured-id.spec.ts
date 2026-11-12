import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AiHistoryMessage, ChatMessage, ToolPart } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, unregisterPageHandler } from '@/stores/workspace'
import { useTaskStore } from '@/stores/task'
import { jobIdOfToolResult } from '@/utils/parts'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  downloadFile: vi.fn(),
  listJobs: vi.fn(),
  getIssues: vi.fn(),
  getJob: vi.fn(),
  openTaskEvents: vi.fn(),
  listDefinitions: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn()
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: api }))
vi.mock('@/api/ai', () => ({
  aiApi: {
    health: vi.fn(),
    history: api.history,
    runsCurrent: api.runsCurrent,
    getContext: vi.fn().mockResolvedValue({ source: 'none' }),
    chat: vi.fn(),
    frontendToolResult: vi.fn(),
    reattachRun: vi.fn(),
    feedbackSubmit: vi.fn(),
    confirm: vi.fn(),
    deleteHistory: vi.fn().mockResolvedValue({})
  }
}))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

type StartTool = 'start_export' | 'start_precheck' | 'start_import' | 'start_publish'

function streamingMessage(): ChatMessage {
  return {
    id: 'a-job',
    role: 'assistant',
    content: '',
    parts: [],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

function feedJobResult(
  store: ReturnType<typeof useAiStore>,
  name: StartTool,
  structured: Record<string, unknown>,
  seq = 1,
  result = '作业已启动（作业 #999），进度由页面展示'
): void {
  store.handleFrame({
    type: 'tool_start', toolCallId: `c-${seq}`, name, kind: 'CONFIRM', args: '{"taskId":7}', runId: 'run-job', seq
  })
  store.handleFrame({
    type: 'tool_result', toolCallId: `c-${seq}`, name, kind: 'CONFIRM', executed: true, ok: true,
    outcome: 'ok', result, target: { taskId: '7', ...structured }, runId: 'run-job', seq: seq + 1
  })
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.listJobs.mockResolvedValue([])
  api.getIssues.mockResolvedValue({ content: [] })
  api.openTaskEvents.mockReturnValue({ onopen: null, onerror: null, readyState: 1, close: vi.fn() })
})

describe('作业 ID 只走结构化事实', () => {
  it.each([
    ['start_export', 'export.syncJob', 12],
    ['start_precheck', 'import.syncJob', 31],
    ['start_import', 'import.syncJob', 32],
    ['start_publish', 'import.syncJob', 41]
  ] as const)('%s 使用 target.jobId 而不是展示文本', (name, handlerName, jobId) => {
    const handler = vi.fn().mockResolvedValue('ok')
    registerPageHandler(handlerName, handler)
    vi.spyOn(useTaskStore(), 'watchPublishOutcome').mockReturnValue(() => {})
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobResult(store, name, { jobId: String(jobId) })

    expect(handler).toHaveBeenCalledWith(expect.objectContaining({ taskId: 7, jobId }))
    unregisterPageHandler(handlerName)
  })

  it('旧回执没有结构化 jobId 时不从中文文本反解', () => {
    const handler = vi.fn()
    registerPageHandler('export.syncJob', handler)
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobResult(store, 'start_export', {}, 1, '导出作业已启动（作业 #12）')

    expect(handler).not.toHaveBeenCalled()
    expect(jobIdOfToolResult('导出作业已启动（作业 #12）', {})).toBeNull()
    unregisterPageHandler('export.syncJob')
  })

  it.each([null, 0, -1, 1.5, 'abc', '9007199254740993'])('非法 jobId=%s 时不回退文本或误关联', async (jobId) => {
    const handler = vi.fn()
    registerPageHandler('import.syncJob', handler)
    const watch = vi.spyOn(useTaskStore(), 'watchPublishOutcome')
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobResult(store, 'start_publish', { jobId }, 1, '发布作业已启动（作业 #41）')
    await flushPromises()

    expect(handler).not.toHaveBeenCalled()
    expect(watch).not.toHaveBeenCalled()
    unregisterPageHandler('import.syncJob')
  })

  it('重复和迟到回执不串到别的作业', () => {
    const handler = vi.fn().mockResolvedValue('ok')
    registerPageHandler('export.syncJob', handler)
    const store = useAiStore()
    store.messages = [streamingMessage()]

    feedJobResult(store, 'start_export', { jobId: '12' }, 1)
    feedJobResult(store, 'start_export', { jobId: '13' }, 3)
    feedJobResult(store, 'start_export', { jobId: '99' }, 1)
    feedJobResult(store, 'start_export', { jobId: '98' }, 2)

    expect(handler).toHaveBeenCalledTimes(2)
    expect(handler.mock.calls.map(([args]) => args.jobId)).toEqual([12, 13])
    unregisterPageHandler('export.syncJob')
  })

  it('history 投影保留结构化 target，旧 history 缺字段时不补造', () => {
    const store = useAiStore()
    const entry: AiHistoryMessage = {
      type: 'ASSISTANT',
      text: '',
      runId: 'run-history-job',
      segments: [{ kind: 'tool', toolCallId: 'c-history', seq: 0 }],
      toolCalls: [{ id: 'c-history', type: 'function', name: 'start_publish', arguments: '{"taskId":7}' }],
      toolCallStates: {
        'c-history': {
          status: 'EXECUTED', outcome: 'ok', kind: 'CONFIRM',
          target: { taskId: '7', jobId: '41' }
        }
      }
    }
    const response: AiHistoryMessage = {
      type: 'TOOL', text: '',
      toolResponses: [{ id: 'c-history', name: 'start_publish', responseData: '发布作业已启动（作业 #999）' }]
    }

    const messages = store.buildHistoryMessages([entry, response])
    const part = messages[0]?.parts.find((item): item is ToolPart => item.kind === 'tool')

    expect(part?.target).toEqual({ taskId: '7', jobId: '41' })
    expect(part?.result).toBe('发布作业已启动（作业 #999）')

    const legacy = store.buildHistoryMessages([{
      ...entry,
      toolCallStates: { 'c-history': { status: 'EXECUTED', outcome: 'ok', kind: 'CONFIRM' } }
    }, response])
    const legacyPart = legacy[0]?.parts.find((item): item is ToolPart => item.kind === 'tool')
    expect(legacyPart?.target).toBeUndefined()
  })

  it('history 恢复发布 watcher；旧 history 不武装跟踪', async () => {
    const watch = vi.spyOn(useTaskStore(), 'watchPublishOutcome').mockReturnValue(() => {})
    const store = useAiStore()
    store.sessionId = 's-history-job'
    const entry: AiHistoryMessage = {
      type: 'ASSISTANT', text: '', runId: 'run-history-job',
      segments: [{ kind: 'tool', toolCallId: 'c-history', seq: 0 }],
      toolCalls: [{ id: 'c-history', type: 'function', name: 'start_publish', arguments: '{"taskId":7}' }],
      toolCallStates: {
        'c-history': {
          status: 'EXECUTED', outcome: 'ok', kind: 'CONFIRM', target: { taskId: '7', jobId: '41' }
        }
      }
    }
    const response: AiHistoryMessage = {
      type: 'TOOL', text: '',
      toolResponses: [{ id: 'c-history', name: 'start_publish', responseData: '发布作业已启动（作业 #999）' }]
    }
    api.history.mockResolvedValue({ messages: [entry, response], total: 2, hasMore: false, revision: 1 })
    api.runsCurrent.mockResolvedValue({ found: false })

    await store.loadHistory()

    expect(watch).toHaveBeenCalledWith(7, 41, expect.any(Object))

    await store.loadHistory()
    const handlers = watch.mock.calls[0]?.[2] as { onDisposed?: () => void }
    handlers.onDisposed?.()
    await store.loadHistory()
    expect(watch).toHaveBeenCalledTimes(1)

    watch.mockClear()
    api.history.mockResolvedValue({
      messages: [{
        ...entry,
        toolCallStates: { 'c-history': { status: 'EXECUTED', outcome: 'ok', kind: 'CONFIRM' } }
      }, response],
      total: 2,
      hasMore: false,
      revision: 2
    })
    await store.loadHistory()

    expect(watch).not.toHaveBeenCalled()
  })

  it('预检失败删除 AI key，后续 history 可重试武装', async () => {
    const handlers: Array<{ onPreflightError?: () => void }> = []
    const watch = vi.spyOn(useTaskStore(), 'watchPublishOutcome').mockImplementation((_taskId, _jobId, options) => {
      handlers.push(options as { onPreflightError?: () => void })
      return () => {}
    })
    const store = useAiStore()
    store.sessionId = 's-history-preflight-retry'
    const entry: AiHistoryMessage = {
      type: 'ASSISTANT', text: '', runId: 'run-history-job',
      segments: [{ kind: 'tool', toolCallId: 'c-history', seq: 0 }],
      toolCalls: [{ id: 'c-history', type: 'function', name: 'start_publish', arguments: '{"taskId":7}' }],
      toolCallStates: {
        'c-history': {
          status: 'EXECUTED', outcome: 'ok', kind: 'CONFIRM', target: { taskId: '7', jobId: '41' }
        }
      }
    }
    const response: AiHistoryMessage = {
      type: 'TOOL', text: '',
      toolResponses: [{ id: 'c-history', name: 'start_publish', responseData: '发布作业已启动（作业 #999）' }]
    }
    api.history.mockResolvedValue({ messages: [entry, response], total: 2, hasMore: false, revision: 1 })
    api.runsCurrent.mockResolvedValue({ found: false })

    await store.loadHistory()
    handlers[0]?.onPreflightError?.()
    await store.loadHistory()

    expect(watch).toHaveBeenCalledTimes(2)
  })

  it('切会话 dispose 旧 watcher，旧回执不得写入新会话', async () => {
    const handlers: Array<{ onConflict: (summary: string) => void }> = []
    const dispose = vi.fn()
    vi.spyOn(useTaskStore(), 'watchPublishOutcome').mockImplementation((_taskId, _jobId, options) => {
      handlers.push(options as { onConflict: (summary: string) => void })
      return dispose
    })
    const store = useAiStore()
    store.sessionId = 's-old-publish'
    store.messages = [streamingMessage()]
    feedJobResult(store, 'start_publish', { jobId: '41' })
    const attach = vi.spyOn(store, 'attachPublishConflict')

    await store.resetSession()
    expect(dispose).toHaveBeenCalledTimes(1)

    handlers[0]?.onConflict('旧冲突')
    expect(attach).not.toHaveBeenCalled()
    expect(store.lastUiEventResult).toBeNull()
  })

  it('同一 job 的回执只应用一次', () => {
    let onConflict: ((summary: string) => void) | undefined
    vi.spyOn(useTaskStore(), 'watchPublishOutcome').mockImplementation((_taskId, _jobId, options) => {
      onConflict = (options as { onConflict: (summary: string) => void }).onConflict
      return () => {}
    })
    const store = useAiStore()
    store.sessionId = 's-publish-receipt'
    store.messages = [streamingMessage()]
    feedJobResult(store, 'start_publish', { jobId: '41' })
    const attach = vi.spyOn(store, 'attachPublishConflict')

    onConflict?.('冲突')
    onConflict?.('冲突')

    expect(attach).toHaveBeenCalledTimes(1)
  })
})
