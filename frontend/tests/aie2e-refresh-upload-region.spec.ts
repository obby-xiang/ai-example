/**
 * 测试扩充 V2 · A7-①（R-17 "区域仍注册 ⇒ 交接可继续"）与 B11-②（第三种挂起形态的刷新重建）。
 *
 * <h2>为什么这两条单开一个 spec</h2>
 * `utils/ai-regions` 是**模块级单例**（区域表 / ref 索引 / 快照 epoch 都在模块作用域）。本域其它
 * 用例为了表达"页面重载"必须 `vi.resetModules()` 重建模块图 —— 两者叠加时，"store 手里的注册表"
 * 与"用例注册进去的注册表"在**全量并行**下会分叉（实测：单跑恒绿、整仓跑红，`status` 由
 * `waiting` 掉成 `missing`）。那是对快照/镜像都能重建的部分之外的**夹具**问题，不该用放宽断言掩盖。
 *
 * <p>所以这里改用 `tests/ai-upload-handoff.spec.ts` 已验证稳定的形态：**不 reset 模块**，
 * 用「新 pinia + `resetAiRegions()`」表达"刷新后的内存态"。刷新语义仍然成立：store 的轮次状态
 * 全部来自 `/history` + `/runs/current` 快照 + 镜像，pinia 一换就全丢。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { registerAiRegion, resetAiRegions } from '@/utils/ai-regions'
import { resetUiRuntime } from '@/utils/ai-dom-tools'
import type { ChatMessage } from '@/types/ai'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  getContext: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

const UPLOAD_ARGS = JSON.stringify({ ref: 'import.upload.choose', accept: ['.xlsx'] })

/**
 * A09：模型发 `ui_request_upload` 时**不显式给 ref**（产品取区域声明的 `uploadEntry`）——
 * 真实链路里 args 就长这样，`ref` 只在「模型自己指定了入口」时才出现。
 */
const UPLOAD_ARGS_NO_REF = JSON.stringify({ accept: ['xlsx'] })

let SESSION = ''

const MIRROR_PREFIX = 'ai-mirror:'

function snapshot(overrides: Record<string, unknown> = {}) {
  return {
    found: true,
    runId: 'run-up',
    snapshotStatus: 'SUSPENDED',
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: true,
    archiveMaxSeq: 8,
    ...overrides
  }
}

function uploadEntry(args: string = UPLOAD_ARGS) {
  return {
    toolCallId: 'u1',
    entryStatus: 'PENDING',
    kind: 'FRONTEND',
    name: 'ui_request_upload',
    arguments: args,
    req: true
  }
}

function uploadHistory(args: string = UPLOAD_ARGS) {
  return [
    { type: 'USER', text: '把这个配置导进去' },
    {
      type: 'ASSISTANT',
      text: '需要你选一个文件。',
      toolCalls: [{ id: 'u1', type: 'function', name: 'ui_request_upload', arguments: args }],
      toolCallStates: { u1: { status: 'PENDING', kind: 'FRONTEND' } }
    }
  ]
}

function toolSegments(message: ChatMessage | undefined) {
  return (message?.parts ?? []).filter(
    (part): part is Extract<ChatMessage['parts'][number], { kind: 'tool' }> => part.kind === 'tool'
  )
}

/** 注册导入向导第 1 步的上传入口（区域仍在本页 ⇒ 交接"可继续"）。 */
function registerUploadRegion(received: File[] = []): void {
  const root = document.createElement('div')
  root.innerHTML = '<button data-ai-ref="import.upload.choose" aria-label="上传 xlsx / zip">上传</button>'
  document.body.appendChild(root)
  registerAiRegion({
    id: 'import.upload',
    root,
    risk: 'consequential',
    page: 'import',
    label: '上传区',
    uploadEntry: 'import.upload.choose',
    receiveFile: async (file: File) => {
      received.push(file)
      return '匹配 1 个配置项'
    }
  })
}

/** 刷新：换一个 pinia（store 的轮次状态全丢），并清掉界面侧残留，再调历史与快照重建。 */
function refreshedStore() {
  setActivePinia(createPinia())
  const store = useAiStore()
  store.sessionId = SESSION
  store.messages = []
  store.activeForm = null
  store.activePreview = null
  store.stalePreviewToolCallId = null
  store.activeUploadHandoff = null
  store.suspended = false
  store.loading = false
  store.notice = null
  return store
}

type Store = ReturnType<typeof refreshedStore>

function visiblePendingIds(store: Store): string[] {
  return store.messages
    .flatMap((message) => toolSegments(message))
    .filter((part) => part.requiresAction && part.status === 'pending')
    .map((part) => part.toolCallId)
}

beforeEach(() => {
  sessionStorage.clear()
  document.body.innerHTML = ''
  delete (window as unknown as { showOpenFilePicker?: unknown }).showOpenFilePicker
  resetAiRegions()
  resetUiRuntime()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  SESSION = `s-up-${Math.random().toString(36).slice(2, 10)}`
  api.health.mockResolvedValue({ available: true, code: 'OK', model: 'm', baseUrl: 'b' })
  api.history.mockResolvedValue({ sessionId: SESSION, count: 2, messages: uploadHistory() })
  api.feedbackSubmit.mockResolvedValue({ saved: true })
  api.cancel.mockResolvedValue({ cancelled: true, alreadyCancelled: false })
  api.deleteHistory.mockResolvedValue({})
  api.confirm.mockResolvedValue({})
  api.frontendToolResult.mockResolvedValue({ accepted: true })
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.runsCurrent.mockResolvedValue(snapshot({
    runId: 'run-up', awaitingExternal: true, pendingEntries: [uploadEntry()]
  }))
  api.getContext.mockResolvedValue({
    sessionId: SESSION, model: 'm', turnNo: 1, measuredAtMs: null, limitTokens: 128000,
    usedTokens: 0, percent: null, source: 'none', estimated: false, breakdown: [],
    compaction: { level: 'none', compactedTurns: 0, lostTokens: 0, lastAtMs: null, reason: null, circuit: 'closed', fuseFails: 0 }
  })
  expect(sessionStorage.getItem(MIRROR_PREFIX + SESSION)).toBeNull()
})

describe('A7-①（R-17 · P2）：上传中刷新且区域仍注册 ⇒ 交接可继续', () => {
  it('交接态重建为 waiting、入参元数据保留、零自动回灌（无重试风暴）', async () => {
    registerUploadRegion()
    const store = refreshedStore()

    await store.loadHistory()

    expect(store.activeUploadHandoff).toMatchObject({
      toolCallId: 'u1',
      status: 'waiting',
      ref: 'import.upload.choose',
      accept: ['xlsx'],
      filename: null,
      reason: null
    })
    expect(store.uploadHandoffAvailability()).toBe('waiting') // 主按钮可用（可继续选文件）
    expect(visiblePendingIds(store)).toEqual(['u1'])
    expect(store.suspended).toBe(true)
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  }, 20_000)
})

describe('B11-②（第三种挂起形态 · ui_request_upload）：挂起中刷新与另两型同构', () => {
  it('刷新后交接卡重建、可操作性可判、输入禁用，且不误武装表单/表格预览', async () => {
    registerUploadRegion()
    const store = refreshedStore()

    await store.loadHistory()

    const card = toolSegments(store.messages[1]).find((part) => part.toolCallId === 'u1')
    expect(card).toMatchObject({ name: 'ui_request_upload', kind2: 'FRONTEND', status: 'pending', requiresAction: true })
    expect(store.activeUploadHandoff).toMatchObject({ toolCallId: 'u1', status: 'waiting' })
    expect(store.uploadHandoffAvailability()).toBe('waiting')
    // 与 CONFIRM / GF 挂起同型：轮仍被认为"等人工"，输入禁用
    expect(store.suspended).toBe(true)
    expect(store.canSend).toBe(false)
    expect(store.activeRunId).toBe('run-up')
    // 第三种形态不得串到另外两种上
    expect(store.activeForm).toBeNull()
    expect(store.stalePreviewToolCallId).toBeNull()
    expect(store.pendingConfirms).toEqual([])
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  }, 20_000)
})

/**
 * A09（CI run 39994464339 stub 档红 · 产品缺陷）：`uploadHandoffAvailability()` 的 `!handoff.ref`
 * 一票否决。
 *
 * <h2>缺陷链</h2>
 * 模型发 `ui_request_upload` 时通常**不给 ref**（落点由区域声明的 `uploadEntry` 决定），
 * 刷新重建那一刻路由页是懒加载的、区域可能还没注册 ⇒ 重建出的 `ref` 落成 `null`，
 * 而判据开头 `if (!handoff.ref) return 'missing'` 把卡永久判死：区域后来注册了也不复原，
 * 卡面停在「请先打开导入向导第 1 步」、主按钮永远禁用（取消可用 ⇒ 不是死卡，但也交不了文件）。
 *
 * <h2>修后口径</h2>
 * `ref` 只是"显式指定"，缺省时落点由**区域实况**决定 ⇒ 缺 ref 不得一票否决；区域不在时
 * `resolveUploadRegion(null)` 仍取不到 ⇒ 依然 `missing`（判据没有被放宽）。
 */
describe('A09：刷新恢复时 ref 落 null（懒加载路由页晚于历史回放挂载）', () => {
  /** 刷新：加载历史 + 快照时，导入向导页还没挂载（一个区域都没注册）。 */
  async function restoreWithoutRegion() {
    const store = refreshedStore()
    api.history.mockResolvedValue({
      sessionId: SESSION, count: 2, messages: uploadHistory(UPLOAD_ARGS_NO_REF)
    })
    api.runsCurrent.mockResolvedValue(snapshot({
      runId: 'run-up', awaitingExternal: true, pendingEntries: [uploadEntry(UPLOAD_ARGS_NO_REF)]
    }))
    await store.loadHistory()
    return store
  }

  it('区域随后注册 ⇒ availability 复原为 waiting（主按钮可点），不被 ref=null 判死', async () => {
    const store = await restoreWithoutRegion()

    // 重建那一刻：模型没给 ref、区域也还没注册 ⇒ ref=null 属实；此刻不可操作也是对的。
    expect(store.activeUploadHandoff).toMatchObject({ toolCallId: 'u1', ref: null })
    expect(store.uploadHandoffAvailability()).toBe('missing')

    registerUploadRegion() // 懒加载路由页挂载：区域注册（ref 仍为 null）

    expect(store.activeUploadHandoff?.ref).toBeNull()
    expect(store.uploadHandoffAvailability()).toBe('waiting')
  }, 20_000)

  it('区域随后注册 ⇒ 「选择文件」真的把文件交给页面（不是只有卡面变绿）', async () => {
    const store = await restoreWithoutRegion()
    const received: File[] = []
    registerUploadRegion(received)
    const file = new File(['bytes'], '币种.xlsx')
    const picker = vi.fn(async () => [{ getFile: async () => file }])
    ;(window as unknown as { showOpenFilePicker?: unknown }).showOpenFilePicker = picker

    await store.chooseUploadFile()

    expect(picker).toHaveBeenCalledTimes(1)
    expect(received.map((item) => item.name)).toEqual(['币种.xlsx'])
    const submitted = api.frontendToolResult.mock.calls[0]?.[0] as { result?: string } | undefined
    expect(submitted?.result).toContain('用户已选择文件')
    expect(submitted?.result).toContain('匹配 1 个配置项')
    expect(store.activeUploadHandoff).toBeNull()
  }, 20_000)

  it('区域始终未注册 ⇒ 仍 missing（判据不放宽）：不弹 picker，以 failed + 指路收口', async () => {
    const store = await restoreWithoutRegion()
    const picker = vi.fn()
    ;(window as unknown as { showOpenFilePicker?: unknown }).showOpenFilePicker = picker

    expect(store.uploadHandoffAvailability()).toBe('missing')
    await store.chooseUploadFile()

    expect(picker).not.toHaveBeenCalled()
    const submitted = api.frontendToolResult.mock.calls[0]?.[0] as { result?: string; outcome?: string } | undefined
    expect(submitted?.result).toContain('导入向导')
    expect(submitted?.result).toContain('本次未执行上传')
    expect(submitted?.outcome).toBe('failed')
    expect(store.activeUploadHandoff).toBeNull()
  }, 20_000)
})
