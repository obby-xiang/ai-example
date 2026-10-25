/**
 * Task 5 / FE-25 + PK-12：**终态残留**与多卡共用单例。
 *
 * 缺陷（审查口径）：
 * - 轮终态（`done` / `error`）只收 `activeForm`：`activeUploadHandoff` / `activePreview` /
 *   `stalePreviewToolCallId` 全部留在状态里 ⇒ 已结束的轮仍能被 `notifyAiUploadDone` /
 *   `confirmWritePreview` / `chooseUploadFile` 推动（回灌打到已收口的 run），「unavailable
 *   （任务已结束，仅供查看）」这一态没有任何生产者。
 * - 上传交接是**单例**：两张交接卡同屏时，任一张的「放弃」取消的是单例里那张
 *   （"点了这张、断的是那张"）。
 *
 * 同时钉住**不破**的两条既有语义：挂起（park）非阻塞 —— 服务端挂起收流（无 done）不得
 * 清理任何卡；只有真终态才收口。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { applyFrameToParts } from '@/utils/parts'
import { aiSnapshot } from '@/utils/ai-dom-tools'
import { registerAiRegion } from '@/utils/ai-regions'
import type { SheetWritePlan } from '@/types/ai'
import { enterImportPage, makeRoot, resetAiTestState } from './ai-fixtures'

const api = vi.hoisted(() => ({
  health: vi.fn(),
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
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  resetAiTestState()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({ accepted: true, duplicate: false })
  installPicker()
})

/** 浏览器 picker 能力桩（缺它时交接落 degraded，与本组断言的 waiting 无关）。 */
function installPicker(): void {
  ;(window as unknown as { showOpenFilePicker?: unknown }).showOpenFilePicker = async () => []
}

/** 注册一个带上传入口的区域（交接卡的落点）。 */
function installRegion(): HTMLElement {
  const root = makeRoot('<button data-ai-ref="import.upload.choose" aria-label="上传 xlsx / zip">上传</button>')
  registerAiRegion({
    id: 'import.upload',
    root,
    risk: 'consequential',
    page: 'import',
    label: '上传区',
    uploadEntry: 'import.upload.choose',
    receiveFile: async () => '匹配 1 个配置项'
  })
  aiSnapshot({})
  return root
}

/** 打开一张上传交接卡（真实帧 → openUploadHandoff）。 */
async function armHandoff(
  store: ReturnType<typeof useAiStore>,
  toolCallId = 'ui-up-1',
  seq = 2
): Promise<ChatMessage> {
  if (seq === 2) {
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  }
  const message = store.messages[store.messages.length - 1]?.role === 'assistant'
    ? store.messages[store.messages.length - 1]!
    : store.appendAssistantMessage()
  const frame: Extract<SseFrame, { type: 'frontend_tool_request' }> = {
    type: 'frontend_tool_request',
    toolCallId,
    name: 'ui_request_upload',
    args: '{}',
    runId: 'run-1',
    seq
  }
  applyFrameToParts(message, frame)
  await store.openUploadHandoff(message, frame)
  return message
}

describe('终态（done/error）必须收口人围栏单例', () => {
  it('done：上传交接单例被清，已结束轮不再接受 notifyAiUploadDone 的回灌', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    await armHandoff(store)
    expect(store.activeUploadHandoff?.toolCallId).toBe('ui-up-1')
    expect(store.uploadHandoffAvailability()).toBe('waiting')

    store.handleFrame({ type: 'done', runId: 'run-1', seq: 9 })

    expect(store.activeUploadHandoff).toBeNull()
    expect(store.uploadHandoffAvailability()).toBeNull()
    api.frontendToolResult.mockClear()
    await store.notifyAiUploadDone('用户已在页面上传完 a.xlsx')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('error：表格预览计划与失效标记一并清（不再能应用/放弃已收口轮的写计划）', async () => {
    enterImportPage()
    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    store.appendAssistantMessage()
    // 直接装一个活动预览计划（建真实 plan 需要 SpreadJS 引擎，与本缺陷无关）
    const plan = {
      toolCallId: 'sheet-1',
      planId: 'p1',
      tool: 'replace_values',
      runId: 'run-1',
      defCode: 'D1',
      preview: 'cells',
      scope: 'all'
    } as unknown as SheetWritePlan
    ;(store as unknown as { activePreview: SheetWritePlan | null }).activePreview = plan
    ;(store as unknown as { stalePreviewToolCallId: string | null }).stalePreviewToolCallId = 'sheet-old'

    store.handleFrame({ type: 'error', runId: 'run-1', code: 'UPSTREAM', message: '上游异常', seq: 9 })

    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBeNull()
    api.frontendToolResult.mockClear()
    await store.confirmWritePreview()
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('error（挂起中）：必须与 done 同口径解掉挂起态，屏上不留"等待确认"的死锁', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    // 走真实帧路：`frontend_tool_request` 会把 suspended 置位并建交接卡（等用户手势）
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'ui-up-1',
      name: 'ui_request_upload',
      args: '{}',
      runId: 'run-1',
      seq: 2
    })
    await Promise.resolve()
    expect(store.suspended).toBe(true)
    expect(store.activeUploadHandoff?.toolCallId).toBe('ui-up-1')
    api.reattachRun.mockClear()

    store.handleFrame({ type: 'error', runId: 'run-1', code: 'UPSTREAM', message: '上游异常', seq: 9 })

    // 段侧：待决段过期降级（既有口径，不破）
    expect(store.pendingToolCall).toBeNull()
    // 轮次级：终态即本轮结束 —— 不得把输入区永久锁在"正在等待你的确认…"
    expect(store.loading).toBe(false)
    expect(store.suspended).toBe(false)
    // 单例侧：交接卡一并收口（与 done 同口径）
    expect(store.activeUploadHandoff).toBeNull()
    expect(store.uploadHandoffAvailability()).toBeNull()
    api.frontendToolResult.mockClear()
    await store.notifyAiUploadDone('用户已在页面上传完 a.xlsx')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })

  it('挂起收流（park，无 done）不得收口：卡仍在、可继续（非阻塞语义不破）', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    await armHandoff(store)

    // 真实挂起：服务端发 suspended 后主动收流（前端看不到 done）
    store.handleFrame({
      type: 'suspended',
      runId: 'run-1',
      toolCalls: [{
        toolCallId: 'ui-up-1',
        name: 'ui_request_upload',
        type: 'function',
        kind: 'FRONTEND',
        arguments: '{}',
        status: 'PENDING',
        executed: false
      }],
      seq: 3
    })
    // 连接收尾（onClose 的 `suspended: true` 分支不写断流提示、也不清卡）
    store.loading = false

    expect(store.suspended).toBe(true)
    expect(store.activeUploadHandoff?.toolCallId).toBe('ui-up-1')
    expect(store.uploadHandoffAvailability()).toBe('waiting')
  })
})

describe('多卡共用上传单例：放弃只作用于**本卡**', () => {
  it('两张交接卡同屏时，非活动卡的放弃不得取消活动卡', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    await armHandoff(store, 'ui-up-1', 2)
    await armHandoff(store, 'ui-up-2', 3)
    // 单例只记最后一张（既有限制）；第一张的卡面仍在 messages 里
    expect(store.activeUploadHandoff?.toolCallId).toBe('ui-up-2')

    api.frontendToolResult.mockClear()
    // 卡 1 的「放弃」：store 现签名无参 ⇒ 直接落到单例那张（缺陷）
    await store.cancelUploadHandoff('ui-up-1')

    expect(api.frontendToolResult).not.toHaveBeenCalled()
    expect(store.activeUploadHandoff?.toolCallId).toBe('ui-up-2')
  })

  it('活动卡自己的放弃照常回灌 cancelled=true', async () => {
    enterImportPage()
    installRegion()
    const store = useAiStore()
    await armHandoff(store, 'ui-up-2', 2)

    await store.cancelUploadHandoff('ui-up-2')
    const body = api.frontendToolResult.mock.calls[0]?.[0] as { toolCallId?: string; cancelled?: boolean }
    expect(body.toolCallId).toBe('ui-up-2')
    expect(body.cancelled).toBe(true)
    expect(store.activeUploadHandoff).toBeNull()
  })
})
