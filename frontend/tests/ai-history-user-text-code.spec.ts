import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { applyLocale } from '@/locales'
import { translate } from '@/composables/useCopy'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { AiHistoryMessage, ToolPart } from '@/types/ai'

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
  it('英文下按 id 与序位兜底恢复 userTextCode，并保留 null 与缺键语义', () => {
    applyLocale('en-US')
    const entries: AiHistoryMessage[] = [
      { type: 'USER', text: 'run tools' },
      {
        type: 'ASSISTANT',
        text: '',
        toolCalls: [{ id: 'matched-call', name: 'create_task', arguments: '{}' }],
        segments: [{ kind: 'tool', toolCallId: 'matched-call', seq: 0 }]
      },
      {
        type: 'TOOL',
        text: '',
        toolResponses: [{
          id: 'matched-call',
          name: 'create_task',
          responseData: 'matched result',
          userTextCode: 'CREATE_TASK_TURN_LIMIT',
          target: { taskId: '99' }
        }]
      },
      {
        type: 'ASSISTANT',
        text: '',
        toolCalls: [{ id: 'fallback-call', name: 'fallback_tool', arguments: '{}' }],
        segments: [{ kind: 'tool', toolCallId: 'fallback-call', seq: 0 }]
      },
      {
        type: 'TOOL',
        text: '',
        toolResponses: [{
          id: 'fallback-call',
          name: 'fallback_tool',
          responseData: 'fallback result',
          userTextCode: 'UPSTREAM_FAILED'
        }]
      },
      {
        type: 'ASSISTANT',
        text: '',
        toolCalls: [{ id: 'null-call', name: 'null_tool', arguments: '{}' }],
        segments: [{ kind: 'tool', toolCallId: 'null-call', seq: 0 }]
      },
      {
        type: 'TOOL',
        text: '',
        toolResponses: [{ id: 'null-call', name: 'null_tool', responseData: 'null result', userTextCode: null }]
      },
      {
        type: 'ASSISTANT',
        text: '',
        toolCalls: [{ id: 'missing-call', name: 'missing_tool', arguments: '{}' }],
        segments: [{ kind: 'tool', toolCallId: 'missing-call', seq: 0 }]
      }
    ]
    const store = useAiStore()

    const messages = store.buildHistoryMessages(entries)

    const parts = new Map(toolParts(messages).map((part) => [part.toolCallId, part]))
    const matchedCode = parts.get('matched-call')?.userTextCode
    const fallbackCode = parts.get('fallback-call')?.userTextCode
    expect(matchedCode).toBe('CREATE_TASK_TURN_LIMIT')
    expect(fallbackCode).toBe('UPSTREAM_FAILED')
    expect(parts.get('null-call')?.userTextCode).toBeNull()
    expect(parts.get('missing-call')?.userTextCode).toBeUndefined()
    expect(translate('ai.panel.toolResult.CREATE_TASK_TURN_LIMIT_WITH_ID', { id: 99 })).toBe(
      'Task #99 was already created in this turn. Only one task can be created per turn, so no additional task was created.'
    )
    expect(translate(`ai.panel.toolResult.${fallbackCode}`)).toBe(
      'The previous step did not succeed, so this step was not executed. The assistant will continue with the available information.'
    )
  })

  it('英文 history 从 toolCallStates 恢复 create-only 摘要码与任务号', () => {
    applyLocale('en-US')
    const entries: AiHistoryMessage[] = [
      { type: 'USER', text: 'create only' },
      {
        type: 'ASSISTANT',
        text: '',
        toolCalls: [{ id: 'create-only-call', name: 'create_task', arguments: '{"openWizard":false}' }],
        segments: [{ kind: 'tool', toolCallId: 'create-only-call', seq: 0 }],
        toolCallStates: {
          'create-only-call': {
            status: 'EXECUTED',
            outcome: 'ok',
            userTextCode: 'CREATE_TASK_CREATE_ONLY',
            target: { taskId: '43', openWizard: false }
          }
        }
      },
      {
        type: 'TOOL',
        text: '',
        toolResponses: [{
          id: 'create-only-call',
          name: 'create_task',
          responseData: 'Task #996 was created without opening the wizard',
          target: { openWizard: false }
        }]
      }
    ]
    const store = useAiStore()

    const messages = store.buildHistoryMessages(entries)

    const part = toolParts(messages)[0]
    expect(part?.userTextCode).toBe('CREATE_TASK_CREATE_ONLY')
    expect(part?.target).toEqual({ taskId: '43', openWizard: false })
    expect(translate(`ai.panel.toolResult.${part?.userTextCode}`, { id: 43 })).toBe(
      'Task #43 was created without opening the wizard or starting a job. You can configure it later.'
    )
  })
})
