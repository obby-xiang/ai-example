/**
 * park 帧序的前端防御（真模型 CI run 40171918873 的 G-01 回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * 后端 park 冒烟顺序曾是 `suspended → frontend_tool_request → tool_start`（请求帧被提前公布）。
 * `parts.ts` 的 `tool_start` 分支原来无条件写 `{ status: 'running', requiresAction: false }`，
 * 于是已经建立的"等前端动作"的卡（生成式表单 / 前端工具）在 `tool_start` 到达时被复位成只读，
 * `pendingCall` 派生为空 —— 界面只剩 `cancelled`，永远没有 `gf-submit`，用户无法提交。
 *
 * <h2>本测试锁两件事</h2>
 * <ol>
 *   <li><b>顺序无关的防御</b>：请求帧先到、`tool_start` 后到（旧后端的真实形态）时，
 *       `tool_start` 不得撤销等动作卡：段仍是 `pending` + `requiresAction=true`，
 *       `deriveProjections` 仍产出 `pendingCall`（卡可提交）；</li>
 *   <li><b>契约顺序不变</b>：`tool_start` 先到、请求帧后到（修复后的后端形态）时结果与旧行为一致
 *       （请求帧把卡置为可提交）；普通后端/确认门工具的 `tool_start` 仍落 `running`
 *       （不得因为防御而让"正在执行"的卡变成等动作）。</li>
 * </ol>
 */
import { describe, expect, it } from 'vitest'
import type { ChatMessage } from '@/types/ai'
import type { SseToolStartFrame, ToolKind } from '@/types/sse'
import { applyFrameToParts, deriveProjections } from '@/utils/parts'

function makeMessage(): ChatMessage {
  return {
    id: 'a-1',
    role: 'assistant',
    content: '',
    parts: [{ kind: 'text', seg: 0, text: '', streaming: true }],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

function requestFrame(toolCallId: string) {
  return {
    type: 'frontend_tool_request' as const,
    toolCallId,
    name: 'generative_form',
    args: '{"form":{"scenario":"FILTER"}}'
  }
}

function toolStartFrame(toolCallId: string, kind: ToolKind = 'FRONTEND'): SseToolStartFrame {
  return {
    type: 'tool_start' as const,
    toolCallId,
    name: 'generative_form',
    kind,
    args: '{"form":{"scenario":"FILTER"}}'
  }
}

function toolPartOf(message: ChatMessage, toolCallId: string) {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  expect(part).toBeDefined()
  return part as Extract<ChatMessage['parts'][number], { kind: 'tool' }>
}

describe('park 帧序防御：tool_start 不得撤销已建立的等动作卡', () => {
  it('请求帧先到、tool_start 后到：卡仍可提交（pending + requiresAction + pendingCall）', () => {
    const message = makeMessage()
    applyFrameToParts(message, requestFrame('gf-1'))
    applyFrameToParts(message, toolStartFrame('gf-1'))

    const part = toolPartOf(message, 'gf-1')
    expect(part.status).toBe('pending')
    expect(part.requiresAction).toBe(true)

    deriveProjections(message)
    expect(message.pendingCall).not.toBeNull()
    expect(message.pendingCall?.toolCallId).toBe('gf-1')
  })

  it('契约顺序（tool_start 先到、请求帧后到）：结果不变，卡可提交', () => {
    const message = makeMessage()
    applyFrameToParts(message, toolStartFrame('gf-2'))
    applyFrameToParts(message, requestFrame('gf-2'))

    const part = toolPartOf(message, 'gf-2')
    expect(part.status).toBe('pending')
    expect(part.requiresAction).toBe(true)

    deriveProjections(message)
    expect(message.pendingCall?.toolCallId).toBe('gf-2')
  })

  it('防御只作用于等动作卡：普通后端工具的 tool_start 仍落 running', () => {
    const message = makeMessage()
    applyFrameToParts(message, toolStartFrame('be-1', 'BACKEND'))
    applyFrameToParts(message, toolStartFrame('cf-1', 'CONFIRM'))

    expect(toolPartOf(message, 'be-1')).toMatchObject({ status: 'running', requiresAction: false })
    expect(toolPartOf(message, 'cf-1')).toMatchObject({ status: 'running', requiresAction: false })
    deriveProjections(message)
    expect(message.pendingCall).toBeNull()
  })

  it('迟到 tool_start 不改写既有卡的名字/入参（幂等，不制造第二张卡）', () => {
    const message = makeMessage()
    applyFrameToParts(message, requestFrame('gf-3'))
    const before = JSON.stringify(toolPartOf(message, 'gf-3'))
    applyFrameToParts(message, { ...toolStartFrame('gf-3'), args: '{"form":{"scenario":"OTHER"}}' })

    expect(JSON.stringify(toolPartOf(message, 'gf-3'))).toBe(before)
    expect(message.parts.filter((item) => item.kind === 'tool')).toHaveLength(1)
  })
})
