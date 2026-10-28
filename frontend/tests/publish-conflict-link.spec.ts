/**
 * R-155：发布冲突明细接线工具卡 reason —— jobId→toolCallId 注册表 + attachPublishConflict 落段锁。
 *
 * 锁的契约：
 * - start_publish 工具结果帧取号 jobId（结果文本「作业 #N」）⇒ 注册 {jobId, toolCallId, sessionId}
 *   并武装 watchPublishOutcome（task store 调桩验证入参）；
 * - attachPublishConflict：注册表命中 ⇒ 明细摘要落工具段 reason（段唯一写口 + 投影同步），
 *   注册项注销；查无关联 / 会话已切换 / 段不存在 ⇒ false 静默（调用方走原回执条通道）；
 * - resetSession ⇒ 注册表清空。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import { clearPublishToolLinks, findPublishTool, linkPublishTool } from '@/utils/publish-links'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn()
}))
const watchPublishOutcome = vi.hoisted(() => vi.fn(() => (): void => {}))
const notifyJobEvent = vi.hoisted(() => vi.fn())

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/stores/task', () => ({
  useTaskStore: () => ({ watchPublishOutcome, notifyJobEvent })
}))

import { useAiStore } from '@/stores/ai'

const SUMMARY = '发布作业 #42 校验冲突明细：\n1. DEF_A#R1：版本冲突\n（共 1 处）'

function buildMessage(toolCallId = 'tc-1'): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '',
    parts: [
      { kind: 'text', seg: 0, text: '', streaming: false },
      {
        kind: 'tool', seg: 1, toolCallId, name: 'start_publish', kind2: 'BACKEND',
        status: 'succeeded', args: '{"taskId":3}', result: '发布作业已启动（作业 #42）', requiresAction: false
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

describe('R-155：注册表 + 冲突明细落工具段 reason', () => {
  it('start_publish 工具结果帧 ⇒ 取号 jobId 登记 + 武装发布冲突跟踪', () => {
    const store = useAiStore()
    // 真实流序：tool_start 带 args 建段 → tool_result 取号 jobId；宿主 = 当前助手消息（帧自建）
    store.messages = [{
      id: 'a-0', role: 'assistant', content: '', parts: [],
      reasoning: '', toolRuns: [], pendingCall: null, streaming: false, done: true
    }]
    store.handleFrame({
      type: 'tool_start', toolCallId: 'tc-1', name: 'start_publish', kind: 'BACKEND',
      args: '{"taskId":3}', runId: 'run-1', seq: 1
    })
    store.handleFrame({
      type: 'tool_result', toolCallId: 'tc-1', name: 'start_publish', kind: 'BACKEND',
      ok: true, executed: true, result: '发布作业已启动（作业 #42），正在将数据写入正式库…',
      messageId: 'tool-tc-1', runId: 'run-1', seq: 2
    })

    expect(findPublishTool(42)).toEqual({ toolCallId: 'tc-1', sessionId: store.sessionId })
    expect(watchPublishOutcome).toHaveBeenCalledTimes(1)
    expect(watchPublishOutcome).toHaveBeenCalledWith(3, 42, expect.objectContaining({ onConflict: expect.any(Function) }))
  })

  it('取号负例：非 start_publish / 无 jobId 文案 ⇒ 不登记不武装', () => {
    const store = useAiStore()
    store.messages = [buildMessage('tc-9')]
    store.handleFrame({
      type: 'tool_result', toolCallId: 'tc-9', name: 'start_export', kind: 'BACKEND',
      ok: true, executed: true, result: '导出作业已启动（作业 #7）',
      messageId: 'tool-tc-9', runId: 'run-1', seq: 1
    })
    store.handleFrame({
      type: 'tool_result', toolCallId: 'tc-9', name: 'start_publish', kind: 'BACKEND',
      ok: true, executed: true, result: '执行完成',
      messageId: 'tool-tc-9', runId: 'run-1', seq: 2
    })

    expect(findPublishTool(7)).toBeUndefined()
    expect(watchPublishOutcome).not.toHaveBeenCalled()
  })

  it('attachPublishConflict 命中 ⇒ reason 落段 + 投影同步 + 注册项注销', () => {
    const store = useAiStore()
    const message = buildMessage()
    store.messages = [message]
    linkPublishTool(42, { toolCallId: 'tc-1', sessionId: store.sessionId })

    expect(store.attachPublishConflict(42, SUMMARY)).toBe(true)
    expect(toolPart(message, 'tc-1').reason).toBe(SUMMARY)
    expect(message.toolRuns[0]?.reason).toBe(SUMMARY) // 派生投影锁步
    expect(findPublishTool(42)).toBeUndefined() // 命中即注销
    expect(store.attachPublishConflict(42, SUMMARY)).toBe(false) // 再查无关联静默
  })

  it('attachPublishConflict 查无关联 / 会话已切换 / 段不存在 ⇒ false 静默', () => {
    const store = useAiStore()
    const message = buildMessage()
    store.messages = [message]

    expect(store.attachPublishConflict(99, SUMMARY)).toBe(false) // 未登记
    expect(message.parts.some((part) => part.kind === 'tool' && part.reason)).toBe(false)

    linkPublishTool(42, { toolCallId: 'tc-1', sessionId: 'other-session' })
    expect(store.attachPublishConflict(42, SUMMARY)).toBe(false) // 会话已切换
    expect(findPublishTool(42)).toBeUndefined() //  stale 项被清

    linkPublishTool(43, { toolCallId: 'tc-missing', sessionId: store.sessionId })
    expect(store.attachPublishConflict(43, SUMMARY)).toBe(false) // 段不存在
  })

  it('resetSession ⇒ 注册表清空（会话内内存态）', async () => {
    const store = useAiStore()
    linkPublishTool(42, { toolCallId: 'tc-1', sessionId: store.sessionId })
    api.history.mockResolvedValue({ sessionId: store.sessionId, count: 0, messages: [] })

    await store.resetSession()
    expect(findPublishTool(42)).toBeUndefined()
  })
})
