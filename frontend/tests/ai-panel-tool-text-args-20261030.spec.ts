/**
 * 2026-10-30 复审 I-①：卡面用户文案的结构化插值真的生效 —— `userTextArgs.taskId` / `target.taskId`
 * 驱动 `CREATE_TASK_TURN_LIMIT_WITH_ID`，并且**不再**从中文回执里正则反解任务号。
 *
 * <p>为什么这条要单独锁：两个来源（段上 `userTextArgs`、帧级 `target` 落段）任何一条断了，
 * 卡面就会静默退回"无 id"的通用档；而回到旧实现时，号是从「本轮已创建任务 #N」里抠出来的 ——
 * 于是"回执文本里的号"会冒充"结构化任务号"（本用例把两者刻意写成不同的数字来钉这一点）。
 */
import { beforeAll, beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import type { ChatMessage } from '@/types/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const stubs = {
  'el-alert': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-empty': true,
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': true,
  'el-skeleton': true,
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  FormRenderer: true
}

beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue({
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
    memoryBackend: 'redis',
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z'
  })
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.chat.mockResolvedValue(undefined)
  installIdentityFixture()
})

afterEach(() => {
  useAiStore().adoptFreshSession()
})

enableAutoUnmount(afterEach)

/** 一张"单轮 create_task 上限"的工具卡（`userTextCode` 命中 WITH_ID 词条）。 */
function createTaskLimitMessage(
  id: string,
  receiptText: string,
  structured: { userTextArgs?: Record<string, unknown> | null; target?: Record<string, unknown> | null } = {}
): ChatMessage {
  return {
    id,
    role: 'assistant',
    content: '',
    parts: [
      {
        kind: 'tool',
        seg: 0,
        toolCallId: `call-${id}`,
        name: 'create_task',
        kind2: 'BACKEND',
        status: 'skipped',
        args: '{"type":"EXPORT","title":"t"}',
        result: receiptText,
        userTextCode: 'CREATE_TASK_TURN_LIMIT',
        requiresAction: false,
        ...structured
      }
    ],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: false,
    done: true
  }
}

describe('2026-10-30 复审 I-①：结构化 taskId 驱动 WITH_ID 文案', { timeout: 20_000 }, () => {
  it('userTextArgs.taskId 与 target.taskId 各自驱动 WITH_ID；回执里的数字不冒充任务号', async () => {
    const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
    const wrapper = mount(AiPanel, { global: { stubs } })
    await wrapper.vm.$nextTick()
    await flushPromises()
    const store = useAiStore()

    store.messages = [
      // ① 段上 `userTextArgs`：结构化 12，回执里却是 #99（旧实现会取 99）
      createTaskLimitMessage('m-args', '本轮已创建任务 #99，本次未创建。', { userTextArgs: { taskId: 12 } }),
      // ② 只有帧级 `target`（落段后是 part.target）：结构化 13，回执里是 #98
      createTaskLimitMessage('m-target', '本轮已创建任务 #98，本次未创建。', { target: { taskId: 13 } })
    ]
    store.historyLoaded = true
    await wrapper.vm.$nextTick()
    await flushPromises()

    const outputs = wrapper.findAll('[data-testid="tool-output-text"]')
    expect(outputs).toHaveLength(2)

    // ① `userTextArgs.taskId` 生效（WITH_ID 词条带 #12），回执里的 #99 不得冒充
    expect(outputs[0]?.text()).toContain('#12')
    expect(outputs[0]?.text()).not.toContain('#99')

    // ② `target.taskId` 生效（帧目标落段后的兜底通道），回执里的 #98 不得冒充
    expect(outputs[1]?.text()).toContain('#13')
    expect(outputs[1]?.text()).not.toContain('#98')
  })
})
