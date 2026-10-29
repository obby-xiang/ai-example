import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { applyLocale } from '@/locales'
import { translate } from '@/composables/useCopy'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
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

describe('历史工具结果用户文案代码', () => {
  it('英文下按 id 与序位兜底恢复 userTextCode，并保留 null 与缺键语义', async () => {
    applyLocale('en-US')
    api.history.mockResolvedValue({
      sessionId: 'sess-user-text-code',
      count: 8,
      messages: [
        { type: 'USER', text: 'run tools' },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'matched-call', name: 'matched_tool', arguments: '{}' }]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{
            id: 'matched-call',
            name: 'matched_tool',
            responseData: 'matched result',
            userTextCode: 'CREATE_TASK_TURN_LIMIT'
          }]
        },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'fallback-call', name: 'fallback_tool', arguments: '{}' }]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{
            id: 'different-response-id',
            name: 'fallback_tool',
            responseData: 'fallback result',
            userTextCode: 'UPSTREAM_FAILED'
          }]
        },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'null-call', name: 'null_tool', arguments: '{}' }]
        },
        {
          type: 'TOOL',
          text: '',
          toolResponses: [{ id: 'null-call', name: 'null_tool', responseData: 'null result', userTextCode: null }]
        },
        {
          type: 'ASSISTANT',
          text: '',
          toolCalls: [{ id: 'missing-call', name: 'missing_tool', arguments: '{}' }]
        }
      ]
    })
    const store = useAiStore()
    store.sessionId = 'sess-user-text-code'

    await store.loadHistory()

    const parts = new Map(toolParts(store.messages).map((part) => [part.toolCallId, part]))
    const matchedCode = parts.get('matched-call')?.userTextCode
    const fallbackCode = parts.get('fallback-call')?.userTextCode
    expect(matchedCode).toBe('CREATE_TASK_TURN_LIMIT')
    expect(fallbackCode).toBe('UPSTREAM_FAILED')
    expect(parts.get('null-call')?.userTextCode).toBeNull()
    expect(parts.get('missing-call')?.userTextCode).toBeUndefined()
    expect(translate(`ai.panel.toolResult.${matchedCode}`)).toBe(
      'Only one task can be created per turn, so no additional task was created. Please request a new task in the next turn.'
    )
    expect(translate(`ai.panel.toolResult.${fallbackCode}`)).toBe(
      'The previous step did not succeed, so this step was not executed. The assistant will continue with the available information.'
    )
  })
})
