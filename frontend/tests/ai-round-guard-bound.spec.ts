/**
 * B2 收口：**共性代次**（会话 + 轮次纪元 + runId）与**有界序号**。
 *
 * <p>锁住的四类缺陷：
 * <ol>
 * <li>排队中的前端工具在停止/换会话后仍执行（副作用、回灌旧 run）；</li>
 * <li>await 回来的旧 run 被挂进新会话（`retryModelStep` 的 202 分支）；</li>
 * <li>await 回来的旧现场在新会话里"复活"（表格写预览建卡 / 取消卡被失败回退改回待决 /
 *     迟到交接收口摘掉新卡）；</li>
 * <li>序号台账无界（`runSeq` 全量累积、`appliedSeqs` 无窗口）。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import type { ActiveUploadHandoff } from '@/types/ui-handoff'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler } from '@/stores/workspace'
import { APPLIED_SEQ_WINDOW, alreadyApplied, markApplied } from '@/utils/parts'
import { field, makeFakeSheet } from './sheet-fixtures'
import { resetAiTestState } from './ai-fixtures'
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
  confirm: vi.fn(),
  retryModelStep: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

/** 手动放行的等待闸（测"await 期间现场被换掉"）。 */
function defer(): { promise: Promise<void>; release: () => void } {
  let release: () => void = () => undefined
  const promise = new Promise<void>((resolve) => {
    release = resolve
  })
  return { promise, release }
}

function makeMessage(id = 'a-1'): ChatMessage {
  return {
    id,
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

function toolPartOf(message: ChatMessage, toolCallId: string): { status: string; requiresAction?: boolean } | null {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  return part && part.kind === 'tool' ? part : null
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiTestState()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => undefined))
  api.frontendToolResult.mockResolvedValue({ ok: true, duplicate: false })
  api.cancel.mockResolvedValue({})
})

describe('B2-01 retryModelStep：await 期间换会话 ⇒ 旧 run 不挂进新会话', () => {
  it('202 点火在途时 adoptFreshSession ⇒ 回 denied、不写 activeRunId、不重挂', async () => {
    const store = useAiStore()
    const message = store.appendAssistantMessage()
    message.runId = 'run-1'
    message.appliedSeqs = [4]
    store.activeRunId = 'run-1'

    const gate = defer()
    api.retryModelStep.mockImplementation(async () => {
      await gate.promise
      return { retried: true }
    })

    const pending = store.retryModelStep(message)
    await vi.waitFor(() => expect(api.retryModelStep).toHaveBeenCalledWith('run-1'))

    store.adoptFreshSession()
    const freshSession = store.sessionId
    gate.release()

    await expect(pending).resolves.toBe('denied')
    expect(store.sessionId).toBe(freshSession)
    expect(store.activeRunId).toBeNull()
    expect(api.reattachRun).not.toHaveBeenCalled()
  })

  it('同会话正常受理仍是 accepted（守卫不误伤）', async () => {
    // Task 4：受理后要重挂 SSE，而重挂带身份头与会话号 ⇒ 现场必须先有"身份 + 已发号"的会话
    installIdentityFixture()
    const store = useAiStore()
    const message = store.appendAssistantMessage()
    message.runId = 'run-1'
    message.appliedSeqs = [4]
    store.activeRunId = 'run-1'
    api.retryModelStep.mockResolvedValue({ retried: true })

    await expect(store.retryModelStep(message)).resolves.toBe('accepted')
    expect(store.activeRunId).toBe('run-1')
    expect(api.reattachRun).toHaveBeenCalled()
  })
})

describe('B2-02 confirm：提交失败不得把已取消的卡改回待决', () => {
  function armGate(store: ReturnType<typeof useAiStore>): ChatMessage {
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'confirm_request',
      toolCallId: 'c1',
      name: 'start_export',
      args: '{"taskId":291}',
      timeoutSeconds: 240,
      expiresAt: Date.now() + 240_000,
      atMs: Date.now(),
      runId: 'run-1',
      seq: 2
    } as SseFrame)
    return assistant
  }

  it('停止发生在确认 POST 在途 ⇒ 失败回退不复活挂起卡', async () => {
    const store = useAiStore()
    const assistant = armGate(store)
    store.activeRunId = 'run-1'
    store.loading = true

    const gate = defer()
    api.confirm.mockImplementation(async () => {
      await gate.promise
      throw new Error('网络中断')
    })

    const pending = store.confirm(true, undefined, 'c1')
    await vi.waitFor(() => expect(api.confirm).toHaveBeenCalled())
    await store.stop()
    expect(toolPartOf(assistant, 'c1')?.status).toBe('cancelled')

    gate.release()
    await pending

    const part = toolPartOf(assistant, 'c1')
    expect(part?.status).toBe('cancelled')
    expect(part?.requiresAction).toBe(false)
  })

  it('同轮普通失败仍回退为待决（守卫不误伤）', async () => {
    const store = useAiStore()
    const assistant = armGate(store)
    store.activeRunId = 'run-1'
    api.confirm.mockRejectedValue(new Error('网关超时'))

    await store.confirm(true, undefined, 'c1')

    const part = toolPartOf(assistant, 'c1')
    expect(part?.status).toBe('pending')
    expect(part?.requiresAction).toBe(true)
  })
})

describe('B2-03 写预览：await 期间换会话 ⇒ 预览不在新会话复活', () => {
  const args = {
    defCode: 'DEMO',
    scope: 'sheet',
    columnCodes: ['status'],
    search: '停用',
    replace: '禁用',
    preview: 'sample-diff'
  }

  it('解析表格上下文在途时换会话 ⇒ 不建卡、不上色、不回灌', async () => {
    const sheet = makeFakeSheet({
      fields: [field('code', '编码', { required: true, key: true }), field('status', '状态')],
      rows: [
        { code: 'CNY', status: '启用' },
        { code: 'USD', status: '停用' }
      ]
    })
    const gate = defer()
    registerPageHandler('sheet.current', async () => {
      await gate.promise
      return {
        ok: true,
        defCode: 'DEMO',
        sheetName: sheet.ctx.sheetName,
        readOnly: false,
        version: sheet.ctx.version,
        fields: sheet.ctx.fields,
        adapter: sheet.ctx.adapter
      }
    })

    const store = useAiStore()
    const message = makeMessage()
    store.messages = [message]
    const frame = {
      type: 'frontend_tool_request',
      toolCallId: 't1',
      name: 'replace_values',
      args: JSON.stringify(args),
      runId: 'run-1',
      seq: 2
    } as Extract<SseFrame, { type: 'frontend_tool_request' }>

    const pending = store.openWritePreview(message, frame)
    await Promise.resolve()
    store.adoptFreshSession()
    gate.release()
    await pending

    expect(store.activePreview).toBeNull()
    expect(toolPartOf(message, 't1')).toBeNull()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})

describe('B2-04 上传交接：迟到收口不得摘掉新卡', () => {
  function handoff(toolCallId: string): ActiveUploadHandoff {
    return {
      toolCallId,
      runId: 'run-1',
      ref: null,
      accept: null,
      status: 'waiting',
      reason: null,
      filename: null
    }
  }

  it('旧卡收口时单例里已是新卡 ⇒ 新卡保持不动', async () => {
    const store = useAiStore()
    store.activeUploadHandoff = handoff('up-new')

    await store.finishUploadHandoff(handoff('up-old'), 'cancelled', '用户已放弃', true)

    expect(store.activeUploadHandoff?.toolCallId).toBe('up-new')
  })

  it('自己这张收口 ⇒ 单例清空（口径不变）', async () => {
    const store = useAiStore()
    const current = handoff('up-1')
    store.activeUploadHandoff = current

    await store.finishUploadHandoff(current, 'cancelled', '用户已放弃', true)

    expect(store.activeUploadHandoff).toBeNull()
  })
})

describe('B2-05 序号台账有界', () => {
  it('appliedSeqs：保留最近 64 条，成员去重语义不变', () => {
    const message = makeMessage()
    for (let seq = 1; seq <= APPLIED_SEQ_WINDOW + 8; seq += 1) {
      markApplied(message, seq)
    }
    expect(message.appliedSeqs).toHaveLength(APPLIED_SEQ_WINDOW)
    expect(alreadyApplied(message, APPLIED_SEQ_WINDOW + 8)).toBe(true)
    expect(alreadyApplied(message, APPLIED_SEQ_WINDOW)).toBe(true)
    // 最旧的已滑出窗口
    expect(alreadyApplied(message, 1)).toBe(false)
    // 重复记账不挤掉别的位置
    markApplied(message, APPLIED_SEQ_WINDOW + 8)
    expect(message.appliedSeqs).toHaveLength(APPLIED_SEQ_WINDOW)
  })

  it('runSeq：只保留最近 16 个 runId，换会话清空', () => {
    const store = useAiStore()
    const assistant = store.appendAssistantMessage()
    for (let index = 1; index <= 20; index += 1) {
      store.handleFrame(
        { type: 'delta', text: `t${index}`, seq: index, runId: `run-${index}` },
        assistant.id
      )
    }
    const ids = Object.keys(store.runSeq)
    expect(ids).toHaveLength(16)
    expect(ids).toContain('run-20')
    expect(ids).not.toContain('run-1')

    store.adoptFreshSession()
    expect(store.runSeq).toEqual({})
  })
})
