/**
 * 测试扩充 V4 · C8 并发矩阵（前端侧）。
 *
 * <p>四格 = 双击发送（只发一次）· 发送与停止同 tick · 两条连接同时 reattach（幂等）·
 * reattach 与停止同 tick（只认一条连接、只发一次 cancel）。
 *
 * <p>与既有覆盖的分界：`aie2e-stop.spec.ts#P-09` 锁「双击停止只 1 次 cancel」、
 * `#P-08` 锁「停止后同 tick 回车」、`rg-round-restore-stop.spec.ts` 锁「停止在途连点」、
 * `sheet-tools-undo-depth.spec.ts` 锁「同一 planId 重复 apply」—— 均不涉及**发送侧双击**
 * 与**双连接接管**。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'
import { useWorkspaceStore } from '@/stores/workspace'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn(),
  uploadFormAttachment: vi.fn(),
  formOptionSource: vi.fn()
}))

vi.mock('@/api/ai', () => ({
  aiApi: api,
  uploadFormAttachment: api.uploadFormAttachment,
  formOptionSource: api.formOptionSource
}))

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

function healthFixture(): Record<string, unknown> {
  return {
    available: true, code: 'OK', model: 'm', baseUrl: 'b', memoryBackend: 'redis', sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120, suspendPoolSize: 4, activeSuspendGates: 0, heldSessions: 0,
    sessionLockRenewals: 0, resumeOnStartup: false, instanceId: 'i', checkedAt: '2026-01-01T00:00:00Z'
  }
}

/** 起一轮：先到 start（拿到 runId），随后**收流前挂起**；被 abort 时按真实流形态正常返回（不是 reject）。 */
function hangRound(runId: string): void {
  api.chat.mockImplementation((
    _request: unknown,
    handlers: { onFrame: (frame: SseFrame) => void; onClose?: (info: { terminal: boolean; reason?: string }) => void },
    options?: { signal?: AbortSignal }
  ) => {
    handlers.onFrame({ type: 'start', runId } as SseFrame)
    return new Promise((resolve) => {
      options?.signal?.addEventListener('abort', () => {
        handlers.onClose?.({ terminal: false, reason: 'aborted' })
        resolve({ terminal: false, reason: 'aborted' })
      })
    })
  })
}

/** reattach 的挂起形态：被 abort 时静默返回（与 `streamChat` 的 abort 收尾同形）。 */
function hangReattach(): void {
  api.reattachRun.mockImplementation((
    _runId: string,
    _handlers: unknown,
    options?: { signal?: AbortSignal }
  ) => new Promise((resolve) => {
    options?.signal?.addEventListener('abort', () => resolve({ terminal: false, reason: 'aborted' }))
  }))
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  document.body.innerHTML = ''
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.health.mockResolvedValue(healthFixture())
  api.history.mockResolvedValue({ sessionId: 's', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [], unresolvedExternal: [], awaitingMaxSeq: 0, archiveMaxSeq: 0
  })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  const workspace = useWorkspaceStore()
  workspace.page = 'tasks'
  workspace.pageId = 'tasks'
  installIdentityFixture()
})

async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

/**
 * 有界条件等待（只推进微任务）。
 *
 * <p>3d801ff1 起 `send()` 取号前先 `await ensureSession()`（已有 sessionId 也先等页签归属判定），
 * `/chat` 不再落在 `send()` 的第一个微任务里 —— "同 tick" 用例要等到这一轮真的开起来再动手。
 */
async function until(condition: () => boolean, label: string): Promise<void> {
  for (let tick = 0; tick < 500; tick += 1) {
    if (condition()) {
      return
    }
    await Promise.resolve()
  }
  throw new Error(`等待超时：${label}`)
}

function assistantTexts(messages: ChatMessage[]): string[] {
  return messages.filter((message) => message.role === 'assistant').map((message) => message.content)
}

describe('C8-01 双击发送：同 tick 两次点击只发一次', () => {
  it('连点发送钮 ⇒ /chat 恰好 1 次、气泡各 1 条、第二条点击零提示', async () => {
    hangRound('run-c1')
    const wrapper = await mountPanel()
    const store = useAiStore()
    await wrapper.find('[data-testid="composer-input"]').setValue('统计行数')

    const button = wrapper.find('[data-testid="send-btn"]')
    // 同 tick 两次点击（不 await 第一次 —— 否则第二次已被"输入框清空"挡住，测不到真正的双击）
    const first = button.trigger('click')
    const second = button.trigger('click')
    await Promise.all([first, second])
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(store.messages.filter((message) => message.role === 'user')).toHaveLength(1)
    expect(store.messages.filter((message) => message.role === 'assistant')).toHaveLength(1)
    expect(store.notice).toBeNull()
    expect(wrapper.find('[data-testid="notice-bar"]').exists()).toBe(false)
    wrapper.unmount()
  }, 20_000)
})

describe('C8-02 发送与停止同 tick', () => {
  it('send 后立刻 stop ⇒ 1 次 chat / 1 次 cancel，本地就地收敛为「已取消」', async () => {
    hangRound('run-c2')
    const store = useAiStore()

    const sending = store.send('查任务进度')
    // 3d801ff1：发送要过取号门（`ensureSession`）才建立 /chat —— 先等这一轮真的开起来，
    // 再在紧邻的一刻停止（否则 stop() 看到"无可停之轮"直接返回，连接就永远没人收）。
    await until(() => api.chat.mock.calls.length === 1, 'send() 没有走到 aiApi.chat')
    const stopping = store.stop()

    await Promise.all([sending, stopping])
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.cancel).toHaveBeenCalledTimes(1)
    expect(api.cancel.mock.calls[0]?.[0]).toBe('run-c2')
    expect(store.loading).toBe(false)
    expect(store.stopped).toBe(true)
    expect(store.lastRoundOutcome).toBe('cancelled')
    const assistants = store.messages.filter((message) => message.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(assistants[0]?.done).toBe(true)
    expect(store.notice ?? '').not.toContain('重挂') // 停止轮不报"连接中断/重挂"
  })
})

describe('C8-03 同一 runId 的并发 reattach：单飞并入（M08）', () => {
  it('同 runId 并发接管 ⇒ 后到者并入（只 1 条连接）、只 1 条宿主气泡、重复 seq 帧不重复累加', async () => {
    hangReattach()
    const store = useAiStore()
    store.activeRunId = 'run-c3'
    store.messages = [{
      id: 'a-host', role: 'assistant', content: '', parts: [], reasoning: '', toolRuns: [],
      pendingCall: null, streaming: true, done: false
    }]

    const first = store.reattachActive('run-c3')
    const second = store.reattachActive('run-c3')
    await Promise.resolve()

    // M08 单飞：后到的恢复入口并入现连接，不新建第二条 SSE —— 旧口径"两条连接并存"
    // 正是双倍连接与双倍重连预算的来源；并入也不换轮次身份，故那条连接不该被中止。
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    const onlySignal = (api.reattachRun.mock.calls[0]?.[2] as { signal?: AbortSignal } | undefined)?.signal
    expect(onlySignal?.aborted).toBe(false)
    expect(store.messages.filter((message) => message.role === 'assistant')).toHaveLength(1)

    // 同一连接回放同一批帧（相同 seq）：文本只累加一次（帧幂等）
    const handlers = api.reattachRun.mock.calls[0]?.[1] as { onFrame: (frame: SseFrame) => void }
    handlers.onFrame({ type: 'delta', text: '重放正文', seq: 1, runId: 'run-c3' } as SseFrame)
    handlers.onFrame({ type: 'delta', text: '重放正文', seq: 1, runId: 'run-c3' } as SseFrame)

    expect(assistantTexts(store.messages)).toEqual(['重放正文'])
    expect(store.messages[0]?.parts.filter((part) => part.kind === 'text')).toHaveLength(1)

    // 并入的调用立刻返回；宿主连接仍挂着（hangReattach 的连接 Promise 永不 settle）
    await expect(second).resolves.toBeUndefined()
    let firstSettled = false
    void first.then(() => { firstSettled = true })
    await Promise.resolve()
    await Promise.resolve()
    expect(firstSettled).toBe(false)
  })
})

describe('C8-04 reattach 与停止同 tick', () => {
  it('接管中立刻停止 ⇒ 只 1 次 cancel、只有 1 条宿主气泡、本地收口为已取消', async () => {
    hangReattach()
    const store = useAiStore()
    store.activeRunId = 'run-c4'

    const attaching = store.reattachActive('run-c4')
    const stopping = store.stop()
    await Promise.all([attaching, stopping])

    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.cancel).toHaveBeenCalledTimes(1)
    expect(api.cancel.mock.calls[0]?.[0]).toBe('run-c4')
    expect(store.messages.filter((message) => message.role === 'assistant')).toHaveLength(1)
    expect(store.loading).toBe(false)
    expect(store.stopped).toBe(true)
    expect(store.lastRoundOutcome).toBe('cancelled')
  })
})
