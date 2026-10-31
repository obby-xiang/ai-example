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
 * jsdom 无 Web Locks 与 BroadcastChannel 实现 ⇒ 两者都用 tests/tab-ownership-fixtures.ts 的最小桩
 * 接管（桩语义照规范：`ifAvailable` 命中占用即以 `null` 回调）。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, ToolPart } from '@/types/ai'
import {
  LOCK_PROBE_ATTEMPTS,
  LOCK_PROBE_RETRY_MS,
  OWNERSHIP_CHANNEL_NAME,
  OWNERSHIP_RELEASE_GRACE_MS,
  PAGE_ID_KEY,
  useAiStore
} from '@/stores/ai'
import { mirrorStorageKey, sessionIdStorageKey, sessionLockName } from '@/utils/ai-identity'
import { installIdentityFixture } from './ai-identity-fixture'
import {
  channels,
  FakeBroadcastChannel,
  FakeLockManager,
  seedTab,
  wait
} from './tab-ownership-fixtures'
import { executeFrontendTool } from '@/utils/frontend-tools'
import { useWorkspaceStore } from '@/stores/workspace'
import { ApiError } from '@/api/http'
import { ErrorCode } from '@/types/api'

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
 * Web Locks / BroadcastChannel 桩统一放在 tests/tab-ownership-fixtures.ts（本批新增的页签生命周期
 * 用例共用同一份语义，避免两处桩漂移）。
 */

let locks: FakeLockManager
/** 探活重试的最坏耗时 + 余量（`resolveTabOwnership` 是 3 × 150ms）。 */
const PROBE_BUDGET_MS = LOCK_PROBE_ATTEMPTS * LOCK_PROBE_RETRY_MS + 400

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
  installIdentityFixture()
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
    // 2A 批（裁决 §3 缺陷①）：GF 提交同样落 decidedAt（数据层记录；卡面不渲染由 AI 面板断言锁）
    expect(typeof part.decidedAt).toBe('number')
  })

  it('2A：GF 取消 / 超时收尾同样落 decidedAt（且不写提交值）', async () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    await store.cancelGenerativeForm()
    const part = toolPartsOf(assistant)[0]!
    expect(part.status).toBe('cancelled')
    expect(typeof part.decidedAt).toBe('number')
    expect(part.submittedValues).toBeUndefined()
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

/**
 * 2A② 数据链缺陷：结局帧（`frontend_tool_result`）先于 POST 应答到达时，只读卡挂载即无
 * `submittedValues`（FormRenderer 以 null 初始化后不再重灌）。修复口径 = 帧内 `result`
 * 就是用户提交值原文（ConfirmGate#submitFrontendResult 落 resultText），succeeded + 合法 JSON
 * 对象 ⇒ 同帧写回 `submittedValues`；已有值 first-wins，非 GF / 失败终态 / 坏 JSON 不伪造。
 */
describe('2A②：GF 结局帧同帧写回 submittedValues', () => {
  function resultFrame(
    overrides: Partial<{ name: string; ok: boolean; status: string; result: string }> = {}
  ): Parameters<ReturnType<typeof useAiStore>['handleFrame']>[0] {
    return {
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: true,
      executed: true,
      status: 'FRONTEND_RESULT',
      result: '{"region":"HB"}',
      seq: 3,
      ...overrides
    } as Parameters<ReturnType<typeof useAiStore>['handleFrame']>[0]
  }

  it('succeeded + result 为合法 JSON 对象 ⇒ 同帧写回提交值（只读卡立即有值）', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    store.handleFrame(resultFrame())
    expect(toolPartsOf(assistant)[0]).toMatchObject({
      status: 'succeeded',
      requiresAction: false,
      submittedValues: { region: 'HB' }
    })
  })

  it('已有提交值 first-wins：结局帧不覆盖（POST 已写回的值与帧并存时以先落者为准）', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    store.writeSubmittedValues('g1', { region: 'HB' })
    store.handleFrame(resultFrame({ result: '{"region":"HD"}' }))
    expect(toolPartsOf(assistant)[0]).toMatchObject({ status: 'succeeded', submittedValues: { region: 'HB' } })
  })

  it('非 GF 前端工具 ⇒ 不写 submittedValues（通用 FRONTEND 结果不是表单值）', () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const assistant = store.appendAssistantMessage()
    store.handleFrame({
      type: 'tool_start',
      toolCallId: 'n1',
      name: 'navigate_to',
      kind: 'FRONTEND',
      args: '{"page":"tasks"}',
      seq: 2
    })
    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'n1',
      name: 'navigate_to',
      ok: true,
      executed: false,
      status: 'FRONTEND_RESULT',
      result: '{"page":"tasks"}',
      seq: 3
    })
    expect(toolPartsOf(assistant)[0]).toMatchObject({ status: 'succeeded' })
    expect(toolPartsOf(assistant)[0]!.submittedValues).toBeUndefined()
  })

  it('GF 非成功终态（拒绝/取消）⇒ 即使 result 是合法 JSON 也不写', () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    store.handleFrame(resultFrame({ status: 'REJECTED', ok: false }))
    expect(toolPartsOf(assistant)[0]!.status).toBe('rejected')
    expect(toolPartsOf(assistant)[0]!.submittedValues).toBeUndefined()
  })

  it('GF succeeded 但 result 非 JSON / 非对象 JSON ⇒ 不伪造提交值', () => {
    for (const result of ['not-json{', '["HB"]', 'null', '"HB"']) {
      setActivePinia(createPinia())
      const store = useAiStore()
      const assistant = armGenerativeForm(store, 'g1')
      store.handleFrame(resultFrame({ result }))
      expect(toolPartsOf(assistant)[0]!.status).toBe('succeeded')
      expect(toolPartsOf(assistant)[0]!.submittedValues).toBeUndefined()
    }
  })
})

/**
 * 2A② 缺陷③：同一 toolCallId 可能残留在多条消息（重投/重挂/双卡形态），只读卡按段渲染
 * ⇒ 只写首条会让其余宿主卡面空。写回必须覆盖全部匹配段，且不碰无关 toolCallId。
 */
describe('2A②：writeSubmittedValues 多宿主写回（不错写 / 不漏写）', () => {
  it('同一 toolCallId 的两条宿主消息都写回；无关工具段不受影响', () => {
    const store = useAiStore()
    const first = armGenerativeForm(store, 'g1')
    const firstPart = toolPartsOf(first)[0]!

    const second = store.appendAssistantMessage()
    store.upsertToolPart(second, {
      toolCallId: 'g1',
      name: 'generative_form',
      kind2: 'FRONTEND',
      status: 'succeeded',
      args: firstPart.args,
      requiresAction: false
    })
    store.upsertToolPart(second, {
      toolCallId: 'other',
      name: 'list_tasks',
      kind2: 'BACKEND',
      status: 'succeeded',
      args: '{}',
      requiresAction: false
    })

    store.writeSubmittedValues('g1', { region: 'HB' })

    const firstGf = toolPartsOf(first).find((part) => part.toolCallId === 'g1')!
    const secondGf = toolPartsOf(second).find((part) => part.toolCallId === 'g1')!
    const other = toolPartsOf(second).find((part) => part.toolCallId === 'other')!
    expect(firstGf).toMatchObject({ submittedValues: { region: 'HB' }, requiresAction: false })
    expect(secondGf).toMatchObject({ submittedValues: { region: 'HB' }, requiresAction: false })
    expect(typeof firstGf.decidedAt).toBe('number')
    expect(typeof secondGf.decidedAt).toBe('number')
    expect(other.submittedValues).toBeUndefined()
    expect(other.decidedAt).toBeUndefined()
  })
})

/**
 * 2A② 边缘①：只收到 `frontend_tool_result`（POST 应答丢失/未回）时，提交值不能只落帧宿主 ——
 * 同 toolCallId 的其他宿主段也要按 first-wins 补齐。已有值的段保持原值；坏 JSON / 非 GF /
 * 非成功终态一律不写。
 */
describe('2A② 边缘①：frame-only 多宿主回填（first-wins）', () => {
  const GF_ARGS = JSON.stringify({
    form: {
      scenario: 'export_query',
      title: '导出参数',
      fields: [{ key: 'region', label: '导出范围', type: 'text', required: true }]
    }
  })

  /** 造一条只读终态宿主消息（同 toolCallId 可重复；提交值仅第二宿主预置）。 */
  function host(
    store: ReturnType<typeof useAiStore>,
    toolCallId: string,
    overrides: Partial<ToolPart> = {},
    name = 'generative_form'
  ): ChatMessage {
    const message = store.appendAssistantMessage()
    store.upsertToolPart(message, {
      toolCallId,
      name,
      kind2: 'FRONTEND',
      status: 'succeeded',
      args: name === 'generative_form' ? GF_ARGS : '{"page":"tasks"}',
      requiresAction: false,
      ...overrides
    })
    return message
  }

  it('无 POST 应答：全部空宿主都得到帧内提交值（不只当前帧宿主）', () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const first = host(store, 'g1')
    const untouched = host(store, 'other')
    const second = host(store, 'g1')

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: true,
      executed: false,
      status: 'FRONTEND_RESULT',
      result: '{"region":"HB"}',
      seq: 2
    })

    // 非帧宿主也必须拿到值（旧实现只写当前宿主 ⇒ 另一条卡面空）
    expect(toolPartsOf(first)[0]).toMatchObject({ submittedValues: { region: 'HB' } })
    expect(toolPartsOf(second)[0]).toMatchObject({ submittedValues: { region: 'HB' } })
    // 无关 toolCallId 不写
    expect(toolPartsOf(untouched)[0]!.submittedValues).toBeUndefined()
  })

  it('帧宿主已有值：first-wins 不被覆盖，并以首个值为准补齐空宿主', () => {
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const first = host(store, 'g1')
    const second = host(store, 'g1', { submittedValues: { region: 'OLD' } })

    store.handleFrame({
      type: 'frontend_tool_result',
      toolCallId: 'g1',
      name: 'generative_form',
      ok: true,
      executed: false,
      status: 'FRONTEND_RESULT',
      result: '{"region":"HB"}',
      seq: 2
    })

    // 帧宿主已有值：不被后到的帧值覆盖
    expect(toolPartsOf(second)[0]).toMatchObject({ submittedValues: { region: 'OLD' } })
    // 空宿主按同一 first-wins 结果补齐（不出现两条宿主值不一致）
    expect(toolPartsOf(first)[0]).toMatchObject({ submittedValues: { region: 'OLD' } })
  })

  it('坏 JSON / 非 GF / 非成功终态：多宿主一个都不写', () => {
    const scenarios: Array<{ name: string; status: string; ok: boolean; result: string }> = [
      { name: 'generative_form', status: 'FRONTEND_RESULT', ok: true, result: 'not-json{' },
      { name: 'generative_form', status: 'FRONTEND_RESULT', ok: true, result: '["HB"]' },
      { name: 'generative_form', status: 'REJECTED', ok: false, result: '{"region":"HB"}' },
      { name: 'navigate_to', status: 'FRONTEND_RESULT', ok: true, result: '{"region":"HB"}' }
    ]
    for (const scenario of scenarios) {
      setActivePinia(createPinia())
      const store = useAiStore()
      store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
      // 非成功终态必须落在仍待决的段上（已终态段 first-wins，帧的失败态本就不可改写它）
      const first = host(store, 'g1', { status: 'pending', requiresAction: true }, scenario.name)
      const second = host(store, 'g1', { status: 'pending', requiresAction: true }, scenario.name)

      store.handleFrame({
        type: 'frontend_tool_result',
        toolCallId: 'g1',
        name: scenario.name,
        ok: scenario.ok,
        executed: false,
        status: scenario.status,
        result: scenario.result,
        seq: 2
      })

      expect(toolPartsOf(first)[0]!.submittedValues).toBeUndefined()
      expect(toolPartsOf(second)[0]!.submittedValues).toBeUndefined()
    }
  })
})

/**
 * 2A② 边缘②：409 DUPLICATE = 该 toolCallId 已是终态（他端先提交）。本地这份值不是权威
 * 提交值 —— 既不能用它覆盖先到的帧值，也不能写入空段冒充「同值」。正常 ok 路径不变。
 */
describe('2A② 边缘②：409 DUPLICATE 不写本地提交值', () => {
  function duplicate409(): ApiError {
    return new ApiError('Conflict', 409, ErrorCode.DUPLICATE_TOOL_CALL_ID, { status: 'FRONTEND_RESULT' })
  }

  it('先到帧值不被本地值覆盖', async () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    toolPartsOf(assistant)[0]!.submittedValues = { region: 'HD' } // 他端先提交并已落值
    api.frontendToolResult.mockRejectedValue(duplicate409())

    await store.submitGenerativeForm({ region: 'HB' })

    expect(toolPartsOf(assistant)[0]).toMatchObject({
      status: 'succeeded',
      submittedValues: { region: 'HD' }
    })
    expect(store.activeForm?.status).toBe('submitted')
  })

  it('空段不得写入本地值冒充同值', async () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    api.frontendToolResult.mockRejectedValue(duplicate409())

    await store.submitGenerativeForm({ region: 'HB' })

    expect(toolPartsOf(assistant)[0]!.status).toBe('succeeded')
    expect(toolPartsOf(assistant)[0]!.submittedValues).toBeUndefined()
    expect(store.activeForm?.status).toBe('submitted')
  })

  it('正常 ok 路径仍写回本地提交值（不因 duplicate 防护被削弱）', async () => {
    const store = useAiStore()
    const assistant = armGenerativeForm(store, 'g1')
    api.frontendToolResult.mockResolvedValue({})

    await store.submitGenerativeForm({ region: 'HB' })

    expect(toolPartsOf(assistant)[0]).toMatchObject({ submittedValues: { region: 'HB' } })
  })
})

describe('RC-3：前端工具参数错误文案人话化（任何路径不内嵌 JSON）', () => {
  /** 断言：文案指名缺什么，且不含任何 JSON 特征字形。 */
  function expectHumanText(text: string, exact: string): void {
    expect(text).toBe(exact)
    expect(text).not.toContain('{')
    expect(text).not.toContain('\"')
  }

  it('工作区动作 set_condition 缺参 ⇒ 「缺少 defCode 或 conditions」', async () => {
    useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'QUERY_COND', taskId: 7 })
    const execution = await executeFrontendTool({ name: 'set_condition', args: {} })
    expect(execution.outcome).toBe('failed')
    expectHumanText(execution.result, '设置查询条件的参数不完整：缺少 defCode 或 conditions（查询条件对象；清空条件请显式传空对象）')
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
    seedTab('9401')
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    const seen: unknown[] = []
    witness.onmessage = (event) => seen.push(event.data)

    const store = useAiStore()
    expect(store.sessionId).toBe('9401')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).toBe('9401')
    expect(sessionStorage.getItem(PAGE_ID_KEY)).toBeTruthy()
    expect(seen).toMatchObject([{ kind: 'claim', sessionId: '9401', pageId: sessionStorage.getItem(PAGE_ID_KEY), tenantId: 7, userId: 71 }])

    // 幂等：判定过就不再重复探活/换身份
    await store.ensureTabOwnership()
    expect(store.sessionId).toBe('9401')
    expect(seen).toHaveLength(1)
  })

  it('② 锁被占 ⇒ 复制页签起新会话：不接管原会话、清继承镜像、轮换 pageId', async () => {
    seedTab('9402')
    sessionStorage.setItem(PAGE_ID_KEY, 'page-original')
    locks.hold(sessionLockName('9402'))

    const store = useAiStore()
    expect(store.sessionId).toBe('9402')
    await store.ensureTabOwnership()

    expect(store.tabOwnership).toBe('owner')
    expect(store.sessionId).not.toBe('9402')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe(store.sessionId)
    // 原页签的服务端记忆一个字都没动（复制页签不得删别人的会话）
    expect(api.deleteHistory).not.toHaveBeenCalled()
    // 继承来的镜像引用已清、pageId 已轮换（同源广播里两个页签必须可区分）
    expect(sessionStorage.getItem(mirrorStorageKey('9402'))).toBeNull()
    expect(sessionStorage.getItem(PAGE_ID_KEY)).not.toBe('page-original')
  })

  it('② 环境无 Web Locks ⇒ 按恢复处理（同步收口：不返回 promise，不丢现场）', async () => {
    Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true })
    seedTab('9403')
    const store = useAiStore()
    expect(store.ensureTabOwnership()).toBeNull()
    expect(store.tabOwnership).toBe('unsupported')
    expect(store.sessionId).toBe('9403')
    expect(sessionStorage.getItem(mirrorStorageKey('9403'))).not.toBeNull()
  })

  it('③ 原页签关闭广播 ⇒ 空白复制页签按复制来源接手原会话（N09/M07）', async () => {
    seedTab('9404')
    locks.hold(sessionLockName('9404'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    expect(store.tabOwnership).toBe('owner')
    expect(ownSession).not.toBe('9404')

    // 原页签关闭：锁随之释放 + pagehide 广播 released
    locks.release(sessionLockName('9404'))
    new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage({
      kind: 'released',
      sessionId: '9404',
      pageId: 'page-original',
      tenantId: 7,
      userId: 71
    })
    // 宽限内不抢：原页签可能只是刷新，回来后会重播 claim（见 tab-ownership-lifecycle.spec.ts）
    await flushPromises()
    expect(store.sessionId).toBe(ownSession)

    await wait(OWNERSHIP_RELEASE_GRACE_MS + PROBE_BUDGET_MS)

    // 宽限到期且锁已空闲 ⇒ 空白副本按**复制来源**静默接手原会话（旧实现把这条路径写成不可达）
    expect(store.sessionId).toBe('9404')
    expect(store.tabOwnership).toBe('owner')
    expect(sessionStorage.getItem(sessionIdStorageKey())).toBe('9404')
    expect(api.history).toHaveBeenCalledWith('9404')
    expect(locks.isHeld(sessionLockName('9404'))).toBe(true)
    // 静默接手：不新增提示，也不删原会话的后端记忆
    expect(store.notice).toBeNull()
    expect(api.deleteHistory).not.toHaveBeenCalled()
  })

  it('③ 锁仍被占 ⇒ 广播不可信，复制页签不接管原会话（宽限之后也不接管）', async () => {
    seedTab('9405')
    locks.hold(sessionLockName('9405'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId

    // 广播到达但锁没释放（例：旧页签只是隐藏/误报）⇒ 以锁为准
    new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage({
      kind: 'released',
      sessionId: '9405',
      pageId: 'page-original'
    })
    await wait(OWNERSHIP_RELEASE_GRACE_MS + PROBE_BUDGET_MS)

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('owner')
    expect(api.history).not.toHaveBeenCalledWith('9405')
  })

  it('③ 复制页签已产生内容 ⇒ 不接手（不把用户眼前内容换掉）', async () => {
    seedTab('9406')
    locks.hold(sessionLockName('9406'))

    const store = useAiStore()
    await store.ensureTabOwnership()
    const ownSession = store.sessionId
    store.recordUserMessage('我这边已经说话了')

    locks.release(sessionLockName('9406'))
    new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME).postMessage({
      kind: 'released',
      sessionId: '9406',
      pageId: 'page-original'
    })
    await wait(OWNERSHIP_RELEASE_GRACE_MS + PROBE_BUDGET_MS)

    expect(store.sessionId).toBe(ownSession)
    expect(store.tabOwnership).toBe('owner')
  })

  it('③ 原页签离开（pagehide）⇒ 广播 released；锁不在 pagehide 同步释放（刷新要能立刻认领）', async () => {
    seedTab('9407')
    const witness = new FakeBroadcastChannel(OWNERSHIP_CHANNEL_NAME)
    const seen: unknown[] = []
    witness.onmessage = (event) => seen.push(event.data)

    const store = useAiStore()
    await store.ensureTabOwnership()
    expect(seen).toMatchObject([{ kind: 'claim', sessionId: '9407', pageId: sessionStorage.getItem(PAGE_ID_KEY), tenantId: 7, userId: 71 }])

    window.dispatchEvent(new Event('pagehide'))
    await flushPromises()
    expect(seen).toHaveLength(2)
    expect(seen[1]).toMatchObject({ kind: 'released', sessionId: '9407' })
    // 刷新场景：新文档要能马上拿到同一把锁 ⇒ 旧文档不在这里同步释放
    expect(locks.isHeld(sessionLockName('9407'))).toBe(true)

    // 文档真销毁（等价面：宽限到期后旧文档自己的清理）⇒ 另一个页签可以接手同一个会话
    await wait(OWNERSHIP_RELEASE_GRACE_MS + 200)
    expect(locks.isHeld(sessionLockName('9407'))).toBe(false)

    setActivePinia(createPinia())
    const next = useAiStore()
    await next.ensureTabOwnership()
    expect(next.tabOwnership).toBe('owner')
    expect(next.sessionId).toBe('9407')
  })
})
