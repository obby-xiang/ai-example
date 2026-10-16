/**
 * R-166B 修复批（数据链路-only）的回归锁。
 *
 * RC-2：GF `submittedValues` 写回**不依赖 `activeForm` 身份** —— 结局帧先于 `POST /frontend-tool-result`
 *       应答到达时（`handleFrame` 把 activeForm 置 null），旧连线会把值随守卫一起丢掉；
 * RC-3：前端工具的参数错误文案**人话化**（指名缺/错哪个字段），任何路径不含 JSON 特征；
 * RC-4：复制页签隔离**三层握手**（pageId + Web Locks 探活 + BroadcastChannel 让渡）——
 *       替换 R-166 口径⑤的 navigation type 判据（真机复制页签 = back_forward，判据作废）。
 *
 * 风格照抄 frontend-tool-result-terminal.spec.ts：只驱动真实 store / 真实前端工具入口，不挂载面板。
 * jsdom 无 Web Locks 与 BroadcastChannel 实现 ⇒ 两者都用下面最小桩接管（桩语义照规范：`ifAvailable`
 * 命中占用即以 `null` 回调）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import {
  MIRROR_PREFIX,
  OWNERSHIP_CHANNEL_NAME,
  PAGE_ID_KEY,
  SESSION_ID_KEY,
  SESSION_LOCK_PREFIX,
  useAiStore
} from '@/stores/ai'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { useWorkspaceStore } from '@/stores/workspace'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

// RC-3 的工作区动作路要读 workspace 上下文；真实 router 会懒加载向导路由 chunk（SpreadJS），
// jsdom 下与断言目标无关，故与 frontend-tool-outcome.spec.ts 同口径桩掉。
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

/**
 * Web Locks 桩：`held` = **别的页签**已持有的锁名（原页签存活时就该在里面）。
 * `ifAvailable` 命中占用 → 以 `null` 回调（规范语义）；空闲 → 持有到回调返回的 promise 落地。
 */
class FakeLockManager {
  private readonly held = new Set<string>()
  private readonly mine = new Set<string>()

  /** 模拟"别人占着这把锁"（原页签存活）。 */
  hold(name: string): void {
    this.held.add(name)
  }

  /** 模拟"别人关掉页签"：锁随之释放。 */
  release(name: string): void {
    this.held.delete(name)
  }

  request(
    name: string,
    options: { ifAvailable?: boolean },
    callback: (lock: { name: string } | null) => unknown
  ): Promise<unknown> {
    if (this.held.has(name) || this.mine.has(name)) {
      return options?.ifAvailable === true ? Promise.resolve(callback(null)) : new Promise(() => {})
    }
    this.mine.add(name)
    const held = Promise.resolve(callback({ name }))
    return held.then(
      () => this.mine.delete(name),
      () => this.mine.delete(name)
    )
  }
}

/** 同页签组的 BroadcastChannel 桩（同进程同步投递，便于断言）。 */
const channels = new Set<FakeBroadcastChannel>()

class FakeBroadcastChannel {
  onmessage: ((event: { data: unknown }) => void) | null = null
  readonly name: string

  constructor(name: string) {
    this.name = name
    channels.add(this)
  }

  postMessage(data: unknown): void {
    for (const channel of [...channels]) {
      if (channel !== this && channel.name === this.name) {
        channel.onmessage?.({ data })
      }
    }
  }

  close(): void {
    channels.delete(this)
  }
}

let locks: FakeLockManager

/** 预置一个"已有存量会话"的页签现场（sessionStorage 被复制页签继承的原样）。 */
function seedTab(sessionId: string, withMirror = true): void {
  sessionStorage.setItem(SESSION_ID_KEY, sessionId)
  if (withMirror) {
    sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify({ v: 2, savedAt: 1, messages: [] }))
  }
}

/** 造一条前端工具段（不依赖真实帧路，RC-1/RC-2 之外的断言用不到）。 */
function toolPartsOf(message: ChatMessage): ToolPart[] {
  return message.parts.filter((part): part is ToolPart => part.kind === 'tool')
}

/** 驱动一条 generative_form 的 frontend_tool_request 帧（合法 schema → 武装 activeForm）。 */
function armGenerativeForm(store: ReturnType<typeof useAiStore>, toolCallId = 'g1'): ChatMessage {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const assistant = store.appendAssistantMessage()
  store.handleFrame({
    type: 'frontend_tool_request',
    toolCallId,
    name: 'generative_form',
    args: JSON.stringify({ form: { scenario: 'export_query', fields: [{ key: 'region', label: '导出范围', type: 'text', required: true }] } }),
    timeoutSeconds: 120,
    runId: 'run-1',
    seq: 2
  })
  return assistant
}

beforeEach(() => {
  sessionStorage.clear()
  channels.clear()
  setActivePinia(createPinia())
  locks = new FakeLockManager()
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
  vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)

  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.reattachRun.mockReset()
  api.chat.mockReset()
  api.cancel.mockReset()
  api.deleteHistory.mockReset()
  api.frontendToolResult.mockReset()
  api.feedbackSubmit.mockReset()
  api.confirm.mockReset()
  api.history.mockResolvedValue({ sessionId: 'x', count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({
    found: false, runId: null, snapshotStatus: null, pendingEntries: [],
    unresolvedExternal: [], awaitingExternal: false, archiveMaxSeq: 0
  })
  api.frontendToolResult.mockResolvedValue({})
  api.cancel.mockResolvedValue({})
  api.deleteHistory.mockResolvedValue({})
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
})

afterEach(() => {
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
})

describe('RC-2：GF submittedValues 写回不依赖 activeForm 身份（结局帧先到的竞态）', () => {
  it('结局帧先于 POST 应答到达 ⇒ 提交值仍写回工具段（旧连线在此丢值）', async () => {
    // POST 卡住：先让结局帧到达（activeForm 被置 null），再放行应答
    let releasePost: (() => void) | null = null
    api.frontendToolResult.mockImplementation(() => new Promise<void>((resolve) => {
      releasePost = () => resolve()
    }))

    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    const submitting = store.submitGenerativeForm({ region: 'HB' })
    await flushPromises()
    expect(store.activeForm?.status).toBe('submitting')

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: true,
      executed: true,
      status: 'FRONTEND_RESULT',
      result: '已提交',
      seq: 3
    })
    // 结局帧先到：表单已在 :2409 处收口（activeForm = null）—— 写回不得因此被守卫吞掉
    expect(store.activeForm).toBeNull()

    releasePost!()
    await submitting
    const part = toolPartsOf(assistant)[0]
    expect(part).toMatchObject({
      toolCallId: 'g1',
      status: 'succeeded',
      requiresAction: false,
      submittedValues: { region: 'HB' }
    })
  })

  it('正常时序（POST 先应答、结局帧后到）⇒ 提交值照旧写回且不被终帧抹掉', async () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    await store.submitGenerativeForm({ region: 'HB' })
    expect(toolPartsOf(assistant)[0]).toMatchObject({ submittedValues: { region: 'HB' } })
    expect(store.activeForm?.status).toBe('submitted')

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: true,
      executed: true,
      status: 'FRONTEND_RESULT',
      result: '已提交',
      seq: 3
    })
    expect(toolPartsOf(assistant)[0]).toMatchObject({ status: 'succeeded', submittedValues: { region: 'HB' } })
  })
})

describe('RC-3：前端工具参数错误文案人话化（任何路径不内嵌 JSON）', () => {
  /** 断言：文案指名缺什么，且不含任何 JSON 特征字形。 */
  function expectHumanText(text: string, exact: string): void {
    expect(text).toBe(exact)
    expect(text).not.toContain('{')
    expect(text).not.toContain('\"')
  }

  it('工作区动作 set_condition 缺参 ⇒ 「缺少 defCode 或 conditions.fields」', async () => {
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'QUERY_COND', taskId: 7 })
    const execution = await executeFrontendTool({ name: 'set_condition', args: {} })
    expect(execution.outcome).toBe('failed')
    expectHumanText(execution.result, '设置查询条件的参数不完整：缺少 defCode 或 conditions.fields')
  })

  it('工作区动作 navigate_to 缺参 ⇒ 指名 page（不再回显参数对象）', async () => {
    useWorkspaceStore().enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
    const execution = await executeFrontendTool({ name: 'navigate_to', args: {} })
    expectHumanText(
      execution.result,
      '页面导航的参数不完整：缺少 page（页面标识：tasks / export / import / definitions / data）'
    )
  })

  it('ui_event 动作参数不符 ⇒ 指名必填字段（set_import_mode / run_job）', async () => {
    const setMode = await executeFrontendTool({ name: 'set_import_mode', args: {} })
    expectHumanText(setMode.result, 'set_import_mode 的参数不完整或类型不符，未驱动工作区：缺少 mode 或它不是 MERGE / REPLACE')

    const runJob = await executeFrontendTool({ name: 'run_job', args: {} })
    expectHumanText(
      runJob.result,
      'run_job 的参数不完整或类型不符，未驱动工作区：缺少 taskId 或 jobType（EXPORT / PRECHECK / IMPORT / PUBLISH）'
    )
  })
})

describe('RC-4：复制页签三层握手（pageId + Web Locks + BroadcastChannel）', () => {
  it('①/② 锁空闲 ⇒ 按恢复处理：沿用存量会话 id、pageId 落盘、广播 claim', async () => {
    seedTab('tab-restore')
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    const seen: unknown[] = []
    witness.onmessage = (event) => seen.push(event.data)

    const store = useAiStore()
    expect(store.sessionId).toBe('tab-restore')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).toBe('tab-restore')
    expect(sessionStorage.getItem(PAGE_ID_KEY)).toBeTruthy()
    expect(seen).toEqual([{ kind: 'claim', sessionId: 'tab-restore', pageId: sessionStorage.getItem(PAGE_ID_KEY) }])

    // 幂等：判定过就不再重复探活/换身份
    await store.ensureTabOwnership()
    expect(store.sessionId).toBe('tab-restore')
    expect(seen).toHaveLength(1)
  })

  it('② 锁被占 ⇒ 复制页签起新会话：不接管原会话、清继承镜像、轮换 pageId', async () => {
    seedTab('tab-original')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-original')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-original')

    const store = useAiStore()
    expect(store.sessionId).toBe('tab-original')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('duplicate')
    expect(store.sessionId).not.toBe('tab-original')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe(store.sessionId)
    // 原页签的服务端记忆一个字都没动（复制页签不得删别人的会话）
    expect(api.deleteHistory).not.toHaveBeenCalled()
    // 继承来的镜像引用已清、pageId 已轮换（同源广播里两个页签必须可区分）
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-original')).toBeNull()
    expect(sessionStorage.getItem(PAGE_ID_KEY)).not.toBe('page-original')
  })

  it('② 环境无 Web Locks ⇒ 按恢复处理（同步收口：不返回 promise，不丢现场）', async () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    seedTab('tab-nolocks')
    const store = useAiStore()
    expect(store.ensureTabOwnership()).toBeNull()
    expect(store.tabOwnership).toBe('unsupported')
    expect(store.sessionId).toBe('tab-nolocks')
    expect(sessionStorage.getItem(MIRROR_PREFIX + 'tab-nolocks')).not.toBeNull()
  })

  it('③ 原页签关闭广播 ⇒ 空白复制页签接手原会话（换回原 id + 读回历史）', async () => {
    seedTab('tab-handover')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-handover')

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    expect(store.tabOwnership).toBe('duplicate')
    expect(ownSession).not.toBe('tab-handover')

    // 原页签关闭：锁随之释放 + pagehide 广播 released
    locks.release(SESSION_LOCK_PREFIX + 'tab-handover')
    new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage({
      kind: 'released',
      sessionId: 'tab-handover',
      pageId: 'page-original'
    })
    await flushPromises()

    expect(store.sessionId).toBe('tab-handover')
    expect(store.tabOwnership).toBe('owner')
    expect(sessionStorage.getItem(SESSION_ID_KEY)).toBe('tab-handover')
    expect(sessionStorage.getItem(MIRROR_PREFIX + ownSession)).toBeNull()
    expect(api.history).toHaveBeenCalledWith('tab-handover')
  })

  it('③ 锁仍被占 ⇒ 广播不可信，复制页签不接管原会话', async () => {
    seedTab('tab-locked')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-locked')

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId

    // 广播到达但锁没释放（例：旧页签只是隐藏/误报）⇒ 以锁为准
    new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage({
      kind: 'released',
      sessionId: 'tab-locked',
      pageId: 'page-original'
    })
    await flushPromises()

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('duplicate')
    expect(api.history).not.toHaveBeenCalledWith('tab-locked')
  })

  it('③ 复制页签已产生内容 ⇒ 不接手（不把用户眼前内容换掉）', async () => {
    seedTab('tab-content')
    locks.hold(SESSION_LOCK_PREFIX + 'tab-content')

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    store.recordUserMessage('我这边已经说话了')

    locks.release(SESSION_LOCK_PREFIX + 'tab-content')
    new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage({
      kind: 'released',
      sessionId: 'tab-content',
      pageId: 'page-original'
    })
    await flushPromises()

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('duplicate')
  })

  it('③ 原页签离开（pagehide）⇒ 广播 released 并释放锁', async () => {
    seedTab('tab-leave')
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    const seen: unknown[] = []
    witness.onmessage = (event) => seen.push(event.data)

    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(seen).toEqual([{ kind: 'claim', sessionId: 'tab-leave', pageId: sessionStorage.getItem(PAGE_ID_KEY) }])

    window.dispatchEvent(new Event('pagehide'))
    await flushPromises()
    expect(seen).toHaveLength(2)
    expect(seen[1]).toMatchObject({ kind: 'released', sessionId: 'tab-leave' })

    // 锁已释放：另一个页签（新 store、同 sessionStorage）可以接手同一个会话
    setActivePinia(createPinia())
    const next = useAiStore()
    await next.ensureTabOwnership()
    expect(next.tabOwnership).toBe('owner')
    expect(next.sessionId).toBe('tab-leave')
  })
})
