/**
 * 对话时序包 批1（R-130 / R-132）：`restoreSuspendedSnapshot` 取数窗口的对账回归锁。
 *
 * 该 action 内部自带一个 await（`GET /api/ai/runs/current`），窗口内可能发生两种身份变更：
 * ① 换会话（`resetSession`）—— 旧快照的挂起卡 / activeForm / activeRunId 不得写进新会话；
 * ② 同会话起新轮（`currentAbort` 句柄被新连接替换）—— 旧快照一律不得写回，尤其不得用
 *    旧快照 runId 覆盖在跑轮的 `activeRunId`（否则续收连接会 abort 掉在跑轮自己的连接）。
 *
 * 观测面说明：`currentAbort` 是 `stores/ai.ts` 的**模块级私有句柄**，测试无法直接读取，
 * 故负例②以「最终 messages / activeRunId / suspended 未被旧快照污染」＋「未起旧快照的
 * 续收连接（reattachRun 只被新轮自己调用）」作为等价观测面。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { AiRunCurrent, ChatMessage } from '@/types/ai'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
// 收集期预热：用例内的动态 import（`freshStore()` / `mountPanel()`）在**首条用例**里要付
// "首次 transform + 首次装配"的一次性成本 —— 空载 1.9–3.4s，并发满载可涨到十几秒，直接吃掉
// 该用例自己的 timeout 预算（实测：首条超时、同文件后续用例 10–40ms 通过）。这里放到收集期
// 付掉（收集不受单用例 timeout 约束），用例内动态 import 只剩重执行（~20ms）；断言与 timeout 不变。
import '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'


/** 手控 deferred：把 store 停在 runs/current 的 await 上，测试独占该窗口。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

/** 后端 runs/current 的"有一轮挂起等待"快照（单条 CONFIRM 待确认，请求帧已发）。 */
function suspendedSnapshot(overrides: Partial<AiRunCurrent> = {}): AiRunCurrent {
  return {
    found: true,
    runId: 'run-old',
    snapshotStatus: 'PENDING',
    pendingEntries: [
      {
        toolCallId: 't-old',
        entryStatus: 'PENDING',
        kind: 'CONFIRM',
        name: 'danger_tool',
        arguments: '{"x":1}',
        req: true
      }
    ],
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 7,
    ...overrides
  }
}

async function freshStore() {
  // 模块级状态（currentAbort / mirrorStarted）随 resetModules 归零：每个用例从干净现场起跑
  vi.resetModules()
  const mod = await import('@/stores/ai')
  return mod.useAiStore()
}

function pendingCallHosts(messages: ChatMessage[]): ChatMessage[] {
  return messages.filter((message) => message.pendingCall !== null)
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.chat.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  // 连接保持"在跑"（永不结算）：不触发各轮 finally 里的轮次复位，句柄不被清空
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.chat.mockImplementation(() => new Promise(() => {}))
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  installIdentityFixture()
})

describe('restoreSuspendedSnapshot 的 await 后二次对账', () => {
  it('负例①：取数期间换会话（resetSession）→ 旧快照整体丢弃，不污染新会话', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-a'
    api.history.mockResolvedValue({ sessionId: 'sess-a', count: 0, messages: [] })
    const gate = deferred<AiRunCurrent>()
    const called = deferred<string>()
    api.runsCurrent.mockImplementation((sid: string) => {
      called.resolve(sid)
      return gate.promise
    })

    const load = store.loadHistory()
    // R-153 卡③：确定性屏障（复用文件内 deferred 体例）——等 runsCurrent 真实发起且会话 id 正确，
    // 替代 vi.waitFor 轮询（消除其超窗/竞态残差），断言（下方 :98-103 等价块）零改动
    expect(await called.promise).toBe('sess-a')

    await store.resetSession() // 会话身份在 runs/current 未决期间被换掉
    gate.resolve(suspendedSnapshot())
    await load

    expect(store.messages).toHaveLength(0)
    expect(pendingCallHosts(store.messages)).toHaveLength(0)
    expect(store.activeRunId).toBeNull()
    expect(store.suspended).toBe(false)
    expect(store.activeForm).toBeNull()
    expect(api.reattachRun).not.toHaveBeenCalled()
  }, 15000)

  it('负例②：取数期间同会话起新轮 → 旧快照写回被丢弃，在跑轮的 runId 与连接不被顶替', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-b'
    api.history.mockResolvedValue({ sessionId: 'sess-b', count: 0, messages: [] })
    const gate = deferred<AiRunCurrent>()
    api.runsCurrent.mockReturnValue(gate.promise)

    const load = store.loadHistory()
    await vi.waitFor(() => expect(api.runsCurrent).toHaveBeenCalledWith('sess-b'))

    // 同会话起新轮：send() 同步换掉 currentAbort 句柄（新轮连接由 mock 保持在跑）
    const round = store.send('新轮消息')
    await vi.waitFor(() => expect(api.chat).toHaveBeenCalled())
    store.activeRunId = 'run-live'

    gate.resolve(suspendedSnapshot())
    await load
    await Promise.resolve()

    expect(store.messages).toHaveLength(2) // 新轮自己的 user + assistant 两条
    const assistant = store.messages[1]
    expect(assistant?.pendingCall).toBeNull()
    expect(assistant?.toolRuns).toHaveLength(0)
    expect(assistant?.parts.every((part) => part.kind === 'text')).toBe(true)
    expect(store.suspended).toBe(false)
    expect(store.activeRunId).toBe('run-live') // 旧快照 runId 未覆盖在跑轮
    // 等价观测面：旧快照的续收连接未起（否则 resumeSnapshotStream 会 abort 掉在跑轮自己的连接）
    expect(api.reattachRun).not.toHaveBeenCalled()

    void round // 新轮连接保持未决（本用例不需要它收尾）
  }, 15000)

  it('正例：无干扰时挂起卡正常重建（快照口径不变）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-c'
    api.history.mockResolvedValue({ sessionId: 'sess-c', count: 0, messages: [] })
    api.runsCurrent.mockResolvedValue(suspendedSnapshot())

    await store.loadHistory()

    expect(store.messages).toHaveLength(1)
    const host = store.messages[0]
    expect(host?.role).toBe('assistant')
    expect(host?.pendingCall).toMatchObject({
      toolCallId: 't-old',
      name: 'danger_tool',
      kind: 'CONFIRM',
      status: 'pending',
      rawArgs: '{"x":1}'
    })
    expect(store.suspended).toBe(true)
    expect(store.activeRunId).toBe('run-old')
    // 批2：宿主消息上落 ToolPart（requiresAction 依帧为真），挂起卡由段派生（不再独立写投影）
    expect(host?.toolRuns).toHaveLength(1)
    expect(host?.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(host?.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 't-old',
      name: 'danger_tool',
      kind2: 'CONFIRM',
      status: 'pending',
      args: '{"x":1}',
      requiresAction: true
    })
    // 快照条目无 preflight ⇒ 重建段不产生该键（旧载荷形态一字不变）
    expect(host?.parts[1]).not.toHaveProperty('preflight')
    // 增量续收：lastSeq 取快照的 archiveMaxSeq
    expect(api.reattachRun).toHaveBeenCalledTimes(1)
    expect(api.reattachRun.mock.calls[0]?.[0]).toBe('run-old')
    expect(api.reattachRun.mock.calls[0]?.[2]).toMatchObject({ lastSeq: 7 })
  }, 15000)

  it('正例②：runs/current 条目带 preflight → 重建段保留预检摘要（MAJOR-1 回归锁）', async () => {
    const store = await freshStore()
    store.sessionId = 'sess-pf'
    api.history.mockResolvedValue({ sessionId: 'sess-pf', count: 0, messages: [] })
    const preflight = {
      target: { taskId: 5, title: '订单配置导入', type: 'IMPORT', status: 'ACTIVE' },
      checks: [
        { name: '任务存在', pass: true, detail: '' },
        { name: '类型匹配', pass: true, detail: '' },
        { name: '无在途作业', pass: true, detail: '' },
        { name: '预检指纹新鲜', pass: true, detail: '预检作业 #12 · 3a9f…' }
      ],
      impact: '将发布 3 个配置定义 · 影响 12,480 行数据',
      reportHash: '3a9f'
    }
    api.runsCurrent.mockResolvedValue(suspendedSnapshot({
      pendingEntries: [
        {
          toolCallId: 't-pf',
          entryStatus: 'PENDING',
          kind: 'CONFIRM',
          name: 'start_publish',
          arguments: '{"taskId":5}',
          req: true,
          preflight
        }
      ]
    }))

    await store.loadHistory()

    expect(pendingCallHosts(store.messages)).toHaveLength(1)
    const part = store.messages[0]?.parts.find((item) => item.kind === 'tool')
    expect(part).toMatchObject({ toolCallId: 't-pf', status: 'pending', preflight })
    // 派生投影照旧（预检摘要不影响挂起卡派生）
    expect(store.messages[0]?.pendingCall).toMatchObject({ toolCallId: 't-pf', status: 'pending' })
  }, 15000)
})
