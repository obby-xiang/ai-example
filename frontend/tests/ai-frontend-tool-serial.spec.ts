/**
 * 批次 5B（Task 8）：**同一助手消息内的 `frontend_tool_request` 串行执行**。
 *
 * <h2>缺陷</h2>
 * 一帧 `frontend_tool_request` 只有一条工具，但同一轮里模型会连续下发多条
 * （`navigate_to` 后紧跟 `select_definitions`）。旧实现每条都 `void runFrontendTool(...)`
 * 并发开跑：后一条会在前一条改变界面**之前**读现场 —— 目标页还没挂载、任务还没绑定，
 * 于是模型拿到"页面不支持该动作"这种**假失败**，并据此放弃一条本来可行的工作区操作。
 *
 * <h2>口径（本文件锁住）</h2>
 * ① 同一助手消息：后一条必须等前一条**返回（回灌发出）**才开始；
 * ② 不同助手消息之间不串行（两条并行回复互不拖住）；
 * ③ 前一条失败不阻塞后续（队列不会因一次异常永久卡死）。
 *
 * 本文件**只写不跑**（批次 5B 只跑 `task-center-view.spec.ts` 的一条聚焦用例）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { aiApi } from '@/api/ai'
import {
  registerFrontendToolExecutor,
  unregisterFrontendToolExecutor
} from '@/utils/frontend-tools'

const api = vi.hoisted(() => ({
  getTask: vi.fn(),
  getFiles: vi.fn(),
  selectDefs: vi.fn(),
  setCondition: vi.fn(),
  goToStep: vi.fn(),
  setImportMode: vi.fn(),
  createTask: vi.fn(),
  deleteTask: vi.fn(),
  listTasks: vi.fn(),
  downloadFile: vi.fn(),
  saveFile: vi.fn(),
  downloadAllFiles: vi.fn(),
  downloadSelected: vi.fn(),
  downloadTemplates: vi.fn(),
  uploadFile: vi.fn(),
  openTaskEvents: vi.fn(),
  createJob: vi.fn(),
  listJobs: vi.fn(),
  getJob: vi.fn(),
  cancelJob: vi.fn(),
  getIssues: vi.fn(),
  getDiff: vi.fn()
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: {} }),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() })
  }
})

vi.mock('@/router', () => ({
  default: { push: vi.fn(async () => undefined) },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn(), info: vi.fn() }
}))

vi.mock('@/api/tasks', () => ({ tasksApi: api }))
vi.mock('@/api/jobs', () => ({ jobsApi: api }))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: vi.fn(async () => []) } }))
vi.mock('@/api/ai', () => ({
  aiApi: {
    health: vi.fn(),
    history: vi.fn(),
    runsCurrent: vi.fn(),
    chat: vi.fn(),
    frontendToolResult: vi.fn(async () => ({})),
    reattachRun: vi.fn(),
    feedbackSubmit: vi.fn(),
    confirm: vi.fn(),
    cancel: vi.fn(async () => ({}))
  }
}))

function streamingMessage(id = 'a-serial'): ChatMessage {
  return {
    id,
    role: 'assistant',
    content: '',
    parts: [],
    reasoning: '',
    toolRuns: [],
    pendingCall: null,
    streaming: true,
    done: false
  }
}

/** 导出向导上下文（`open_export_file_editor` 的 scope = `task:EXPORT/EXPORT`）。 */
function enterExport(): void {
  useWorkspaceStore().enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: 'EXPORT',
    taskId: 7
  })
}

function requestFrame(toolCallId: string, name: string, args: Record<string, unknown>) {
  return {
    type: 'frontend_tool_request' as const,
    toolCallId,
    name,
    args: JSON.stringify(args),
    runId: 'run-serial'
  }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  api.getTask.mockResolvedValue({ id: 7, type: 'EXPORT', status: 'IN_PROGRESS', items: [], currentStep: 'EXPORT' })
})

describe('同一助手消息内 frontend_tool_request 串行', () => {
  it('第二个请求在第一个执行返回前不开始（事件序列 start:A → end:A → start:B → end:B）', async () => {
    const events: string[] = []
    let releaseFirst: (() => void) | null = null
    registerFrontendToolExecutor('open_export_file_editor', async (call) => {
      const code = String(call.args.defCode)
      events.push(`start:${code}`)
      if (code === 'A') {
        await new Promise<void>((resolve) => {
          releaseFirst = () => resolve()
        })
      }
      events.push(`end:${code}`)
      return `已打开 ${code} 的在线编辑器`
    })
    enterExport()
    const store = useAiStore()
    store.messages = [streamingMessage()]

    store.handleFrame(requestFrame('c-1', 'open_export_file_editor', { taskId: 7, defCode: 'A' }))
    await Promise.resolve()
    store.handleFrame(requestFrame('c-2', 'open_export_file_editor', { taskId: 7, defCode: 'B' }))
    await Promise.resolve()
    await Promise.resolve()

    // 串行：第二条还没开始
    expect(events).toEqual(['start:A'])

    const release = releaseFirst as (() => void) | null
    release?.()
    await vi.waitFor(() => expect(events).toEqual(['start:A', 'end:A', 'start:B', 'end:B']))

    unregisterFrontendToolExecutor('open_export_file_editor')
  })

  it('前一条失败不阻塞后续（队列不会永久卡死）', async () => {
    const events: string[] = []
    registerFrontendToolExecutor('open_export_file_editor', async (call) => {
      const code = String(call.args.defCode)
      events.push(code)
      if (code === 'A') {
        throw new Error('引擎加载失败')
      }
      return `已打开 ${code}`
    })
    enterExport()
    const store = useAiStore()
    store.messages = [streamingMessage()]

    store.handleFrame(requestFrame('c-1', 'open_export_file_editor', { taskId: 7, defCode: 'A' }))
    store.handleFrame(requestFrame('c-2', 'open_export_file_editor', { taskId: 7, defCode: 'B' }))
    await vi.waitFor(() => expect(events).toEqual(['A', 'B']))

    unregisterFrontendToolExecutor('open_export_file_editor')
  })

  /**
   * B2：停止必须对**排队中**的动作也立即生效。
   *
   * 缺陷：`stop()` 只递增轮次纪元并中止连接，但队列里的下一条排在前一条的 then 上 ——
   * 前一条返回后它照旧开跑：改界面、写段终态、把旧 run 的结局回灌给已取消的后端。
   */
  it('停止后排队中的下一条不再执行（零副作用、不回灌）', async () => {
    const events: string[] = []
    let releaseFirst: (() => void) | null = null
    registerFrontendToolExecutor('open_export_file_editor', async (call) => {
      const code = String(call.args.defCode)
      events.push(`start:${code}`)
      if (code === 'A') {
        await new Promise<void>((resolve) => {
          releaseFirst = () => resolve()
        })
      }
      events.push(`end:${code}`)
      return `已打开 ${code}`
    })
    enterExport()
    const store = useAiStore()
    store.messages = [streamingMessage()]

    store.handleFrame(requestFrame('c-1', 'open_export_file_editor', { taskId: 7, defCode: 'A' }))
    await Promise.resolve()
    store.handleFrame(requestFrame('c-2', 'open_export_file_editor', { taskId: 7, defCode: 'B' }))
    await Promise.resolve()
    expect(events).toEqual(['start:A'])

    await store.stop()
    expect(store.stopped).toBe(true)

    const release = releaseFirst as (() => void) | null
    release?.()
    await vi.waitFor(() => expect(events).toEqual(['start:A', 'end:A']))
    // 让队尾项有机会跑（若守卫失效，这里能看到 start:B）
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(events).toEqual(['start:A', 'end:A'])
    // 停止即收口：A 的晚到结局也不回灌，B 从未开跑
    expect(vi.mocked(aiApi.frontendToolResult)).not.toHaveBeenCalled()
    expect(store.stopped).toBe(true)

    unregisterFrontendToolExecutor('open_export_file_editor')
  })
})
