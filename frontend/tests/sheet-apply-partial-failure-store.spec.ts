/**
 * 应用中途异常的**回执**回归锁（2026-10-29）：卡面/回灌必须是 failed + 实际写入数，
 * 不能是「已应用（用户已确认）…」的全量成功。
 *
 * 走 store 的正式入口（`openWritePreview → confirmWritePreview`），与用户点「应用」同一条链。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { SHEET_WRITE_CHUNK_ROWS } from '@/utils/sheet-tools'
import { field, makeFakeSheet, type FakeSheet } from './sheet-fixtures'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  cancel: vi.fn(),
  deleteHistory: vi.fn(),
  frontendToolResult: vi.fn(),
  confirm: vi.fn(),
  feedbackSubmit: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))
vi.mock('@/router', () => ({
  default: { push: async () => undefined },
  ROUTE_NAMES: new Proxy({}, { get: (_target, key) => String(key) })
}))

const fields = [
  field('code', '编码', { required: true, key: true }),
  field('status', '状态', { fieldType: 'ENUM', optionsJson: '["启用","停用","禁用"]' })
]

let sheet: FakeSheet

const args = {
  defCode: 'DEMO',
  scope: 'sheet',
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用',
  preview: 'sample-diff'
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  useWorkspaceStore().enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
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
  sheet = makeFakeSheet({
    fields,
    rows: Array.from({ length: SHEET_WRITE_CHUNK_ROWS + 50 }, (_, index) => ({
      code: `C${index}`,
      status: '停用'
    }))
  })
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: sheet.ctx.sheetName,
    readOnly: false,
    fields: sheet.ctx.fields,
    adapter: sheet.ctx.adapter
  }))
})

describe('R1 应用中途异常的回执', () => {
  it('store 应用路径：提交的 result 是"写入中途失败 + 已写入 N 格"，不是全量成功', async () => {
    let calls = 0
    const original = sheet.ctx.adapter.writeCells.bind(sheet.ctx.adapter)
    // 第二块内部只落前 2 格再失败（复审 I-1：块内部分写入也必须精确计入）
    const partial = 2
    sheet.ctx.adapter.writeCells = (updates) => {
      calls += 1
      if (calls === 1) {
        return { applied: [...updates] }
      }
      const head = updates.slice(0, partial)
      original(head)
      return { applied: [...head], error: new Error('引擎写入失败') }
    }

    const store = useAiStore()
    store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
    const message: ChatMessage = store.appendAssistantMessage()
    const frame: Extract<SseFrame, { type: 'frontend_tool_request' }> = {
      type: 'frontend_tool_request',
      toolCallId: 't1',
      name: 'replace_values',
      args: JSON.stringify(args),
      runId: 'run-1',
      seq: 2
    }
    applyFrameToParts(message, frame)

    await store.openWritePreview(message, frame)
    // 550 行 > 大计划阈值 ⇒ 与卡面「确认大改动」同一入口（acknowledged=true）
    await store.confirmWritePreview(true)

    const call = api.frontendToolResult.mock.calls.at(-1)?.[0] as { result?: string } | undefined
    const result = call?.result ?? ''
    expect(result).toContain('写入中途失败')
    expect(result).toContain(`已写入 ${SHEET_WRITE_CHUNK_ROWS + partial} 格`)
    expect(result).toContain('可撤销')
    expect(result).not.toContain('已应用（用户已确认）')
    // 只回灌一次（不是"失败+成功"两条）
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
  })
})
