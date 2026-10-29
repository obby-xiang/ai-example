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
import { installIdentityFixture } from './ai-identity-fixture'
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
  installIdentityFixture()
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

  /**
   * 真实形态：一条 assistant **只**带 FORM_SLOTS_ALREADY_KNOWN 的幻影卡（无正文、无 reasoning），
   * 且它独立成条夹在两条正常 assistant 之间。
   *
   * <p>为什么必须单独钉：历史整替是"非 TOOL 条目 → 一条消息"的**按位**映射，随后 R-150 合并 pass
   * 又用 `builtCursor` 按同一个位置游标取消息。若某条目被整条丢弃而不告诉合并 pass，游标就会错位 ——
   * 后续每条 assistant 都拿到上一条的消息、末尾还会读到 `undefined`（合并时 push 进 mergedMessages）。
   * 表现是"内容错位 + 空泡 + 崩溃"，比多一张卡更糟。
   */
  it('独立幻影条目被丢弃后不破坏按位配对：无 undefined/空气泡，后续消息 id 与工具结果仍正确', async () => {
    api.history.mockResolvedValue({
      sessionId: 'sess-phantom-standalone',
      count: 7,
      messages: [
        { type: 'USER', text: '用 CURRENCY 做导出' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'form-phantom', name: 'generative_form', arguments: '{"form":{}}' }],
          toolCallStates: {
            'form-phantom': {
              status: 'REJECTED_ARGUMENTS',
              outcome: 'rejected',
              code: 'FORM_SLOTS_ALREADY_KNOWN',
              kind: 'FRONTEND'
            }
          }
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{ id: 'form-phantom', name: 'generative_form', responseData: 'FORM_SLOTS_ALREADY_KNOWN：已确定配置项' }]
        },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'select-real', name: 'select_definitions', arguments: '{"codes":["CURRENCY"]}' }],
          toolCallStates: { 'select-real': { status: 'FRONTEND_RESULT', outcome: 'ok', kind: 'FRONTEND' } }
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{ id: 'select-real', name: 'select_definitions', responseData: '已勾选 CURRENCY' }]
        },
        { type: 'ASSISTANT', text: '配置项已勾选，准备推进。' }
      ]
    })

    const store = useAiStore()
    store.sessionId = 'sess-phantom-standalone'
    await store.loadHistory()

    // ① 历史整替不得因"整条被隐藏"而抛错被吞：异常一抛，合并 pass 就半途而废、
    //    `messages` 停在未合并态并留下 historyError —— 这是本用例的 RED 观测点。
    expect(store.historyError).toBeNull()
    expect(store.recoveryGap).toBeNull()

    const messages = store.messages as unknown as Array<{ id: string; parts: Array<{ kind: string }> }>
    // ② 没有任何 undefined / 空 parts 的消息（合并 pass 读到 undefined 会把它 push 进结果）
    expect(messages.every((message) => message !== undefined && message !== null)).toBe(true)
    expect(messages.every((message) => Array.isArray(message.parts) && message.parts.length > 0)).toBe(true)
    // ③ 按位配对正确：幻影条目（原始 index=1）整条不产出消息，其余 id 仍是原始 index
    //    （index=3 与 index=5 同属一个用户轮且中间只有被跳过的 TOOL ⇒ R-150 合并成一条）
    expect(messages.map((message) => message.id)).toEqual(['history-0', 'history-3'])
    // ④ 合并后的段：工具段（select_real）在前、正文段在后；工具结果按 id 配对，没被幻影抢走
    const parts = toolParts(messages as unknown as Array<{ parts: Array<{ kind: string }> }>)
    expect(parts.map((part) => part.toolCallId)).toEqual(['select-real'])
    expect(parts[0].result).toBe('已勾选 CURRENCY')
    expect(parts.some((part) => part.name === 'generative_form')).toBe(false)
    expect(messages[1].parts.map((part) => part.kind)).toEqual(['tool', 'text'])
  })
})
