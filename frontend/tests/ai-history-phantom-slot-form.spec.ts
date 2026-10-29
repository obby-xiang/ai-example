/**
 * 历史面的<b>幻影表单卡</b>（{@code FORM_SLOTS_ALREADY_KNOWN}）：刷新后不得凭空多出一张卡。
 *
 * <p>缺陷形态：槽位放行的拒绝是零副作用的（不挂起、不下发、不执行），但旧实现仍为它发了
 * {@code tool_start} + {@code frontend_tool_result}，于是实时流里出现一张"未执行"的表单卡；
 * 后端现已对这一个码静默用户可见的工具帧（条目/日志/模型回执照旧）。历史面必须按<b>同一口径</b>
 * 隐藏，否则"实时看不到、刷新后又冒出来"—— 同一件事两个答案。
 *
 * <p>判据只认"工具名 + 错误码"：其它拒绝（schema 白名单等）仍要给用户一张"为什么没成"的卡。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { isSlotReleasePhantom } from '@/utils/parts'
import type { ToolPart } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  getContext: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

function toolParts(messages: Array<{ parts: Array<{ kind: string }> }>): ToolPart[] {
  return messages.flatMap((message) => message.parts.filter((part): part is ToolPart => part.kind === 'tool'))
}

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.runsCurrent.mockResolvedValue({
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  })
  api.getContext.mockResolvedValue({ source: 'none' })
})

describe('历史幻影表单卡（FORM_SLOTS_ALREADY_KNOWN）', () => {
  it('历史里槽位放行的表单不建段，正常工具段照旧配对；其它拒绝码不受影响', async () => {
    expect(isSlotReleasePhantom('generative_form', 'FORM_SLOTS_ALREADY_KNOWN')).toBe(true)
    expect(isSlotReleasePhantom('generative_form', 'FORM_SCHEMA_REJECTED')).toBe(false)
    expect(isSlotReleasePhantom('select_definitions', 'FORM_SLOTS_ALREADY_KNOWN')).toBe(false)
    expect(isSlotReleasePhantom('generative_form', null)).toBe(false)

    api.history.mockResolvedValue({
      sessionId: 'sess-phantom-form',
      count: 6,
      messages: [
        { type: 'USER', text: '用 CURRENCY 做导出' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [
            { id: 'form-phantom', name: 'generative_form', arguments: '{"form":{}}' },
            { id: 'select-real', name: 'select_definitions', arguments: '{"codes":["CURRENCY"]}' }
          ],
          toolCallStates: {
            'form-phantom': {
              status: 'REJECTED_ARGUMENTS',
              outcome: 'rejected',
              code: 'FORM_SLOTS_ALREADY_KNOWN',
              kind: 'FRONTEND'
            },
            'select-real': { status: 'FRONTEND_RESULT', outcome: 'ok', kind: 'FRONTEND' }
          }
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [
            { id: 'form-phantom', name: 'generative_form', responseData: 'FORM_SLOTS_ALREADY_KNOWN：已确定配置项' },
            { id: 'select-real', name: 'select_definitions', responseData: '已勾选 CURRENCY' }
          ]
        }
      ]
    })

    const store = useAiStore()
    store.sessionId = 'sess-phantom-form'
    await store.loadHistory()

    const parts = toolParts(store.messages as unknown as Array<{ parts: Array<{ kind: string }> }>)
    expect(parts.map((part) => part.toolCallId)).toEqual(['select-real'])
    expect(parts.some((part) => part.name === 'generative_form')).toBe(false)
    // 被隐藏的只是"幻影卡"：正常段的工具结果仍按 id 回填，序位兜底不得把幻影响应错配给它
    expect(parts[0].result).toBe('已勾选 CURRENCY')
  })
})
