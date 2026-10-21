/**
 * AI 表格操作专项 批②：写侧三工具 + 撤销 + 预览三档 + 指纹 + 只读守卫。
 *
 * 链路按设计稿 §3.4 选项 A：写工具不进 `executeFrontendTool`（不受 60s 预算），
 * 由 store 的 `openWritePreview` 建预览、用户“应用/取消”后才回灌；读工具与撤销仍走执行器。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { executeFrontendTool } from '@/utils/frontend-tools'
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
  field('name', '名称', { required: false }),
  field('status', '状态', { required: false, fieldType: 'ENUM', optionsJson: '["启用","停用","禁用"]' })
]

let sheet: FakeSheet

function installSheet(overrides: { readOnly?: boolean } = {}): void {
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: sheet.ctx.sheetName,
    readOnly: overrides.readOnly ?? sheet.ctx.readOnly,
    version: sheet.ctx.version,
    fields: sheet.ctx.fields,
    adapter: sheet.ctx.adapter
  }))
}

function armWrite(
  store: ReturnType<typeof useAiStore>,
  name: string,
  args: Record<string, unknown>,
  toolCallId = 't1'
): { message: ChatMessage; frame: Extract<SseFrame, { type: 'frontend_tool_request' }> } {
  store.handleFrame({ type: 'start', runId: 'run-1', sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame: Extract<SseFrame, { type: 'frontend_tool_request' }> = {
    type: 'frontend_tool_request',
    toolCallId,
    name,
    args: JSON.stringify(args),
    runId: 'run-1',
    seq: 2
  }
  applyFrameToParts(message, frame)
  return { message, frame }
}

function lastSubmittedResult(): string {
  const calls = api.frontendToolResult.mock.calls
  const payload = calls[calls.length - 1]?.[0] as { result?: string; cancelled?: boolean } | undefined
  return payload?.result ?? ''
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })

  sheet = makeFakeSheet({
    fields,
    rows: [
      { code: 'CNY', name: '人民币', status: '启用' },
      { code: 'USD', name: '美元', status: '停用' },
      { code: 'CNH', name: '离岸', status: '停用' }
    ]
  })
  installSheet()
  for (const engine of Object.values(api)) {
    engine.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

describe('T-11 预演不回灌：预览建立、submit 零调用', () => {
  it('replace_values 读取选区/整表并建 plan，不写、不回灌', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview).not.toBeNull()
    expect(store.activePreview?.cellsCount).toBe(2)
    expect(store.activePreview?.rowsCount).toBe(2)
    expect(sheet.writes).toHaveLength(0)
    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})

describe('T-12/T-13 应用与取消', () => {
  it('T-12：点“应用”后才写，回灌含“已应用 / 用户已确认 / 未保存”', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    await store.confirmWritePreview()
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('禁用')
    expect(sheet.writes.length).toBeGreaterThan(0)
    const result = lastSubmittedResult()
    expect(result).toContain('已应用')
    expect(result).toContain('用户已确认')
    expect(result).toContain('未保存')
    expect(result).toContain('共 2 格')
  })

  it('T-13：点“取消”⇒ 零写 + cancelled=true + “未改动任何单元格”', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    await store.cancelWritePreview()
    expect(sheet.writes).toHaveLength(0)
    expect(api.frontendToolResult).toHaveBeenCalledWith(expect.objectContaining({ cancelled: true }))
    // 取消路径沿用既有 `cancelled=true`（回灌体不新增 result 字段）—— “未改动”落在工具段上供卡面展示。
    const part = store.messages
      .flatMap((item) => item.parts)
      .find((item) => item.kind === 'tool' && item.toolCallId === 't1')
    expect(part && part.kind === 'tool' ? part.result : '').toContain('未改动任何单元格')
    expect(store.activePreview).toBeNull()
  })
})

describe('T-14 大批量：影响面模式 + 二次确认 + 只回计数', () => {
  it('3 万行 / 6 万格 ⇒ level=large；未勾选不写；勾选后分块写；回灌 0 行业务值', async () => {
    const bigFields = [field('code', '编码', { key: true }), field('status', '状态')]
    const rows = Array.from({ length: 30_000 }, (_, index) => ({ code: `C${index}`, status: '停用' }))
    sheet = makeFakeSheet({ fields: bigFields, rows })
    installSheet()
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'update_rows', {
      defCode: 'DEMO',
      scope: 'sheet',
      set: [{ columnCode: 'status', mode: 'literal', value: '禁用' }],
      preview: 'count'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview?.level).toBe('large')
    expect(store.activePreview?.rowsCount).toBe(30_000)
    expect(store.activePreview?.cellsCount).toBe(30_000)
    expect(store.previewText()).toContain('我了解影响范围')

    await store.confirmWritePreview()
    expect(sheet.writes).toHaveLength(0)

    await store.confirmWritePreview(true)
    expect(sheet.writes.length).toBeGreaterThan(1)
    const updated = sheet.writes.flat().length
    expect(updated).toBe(30_000)
    const result = lastSubmittedResult()
    expect(result).not.toContain('C29999')
    expect(result).toContain('共 30000 格')
  })
})

describe('T-15/T-24/T-25/T-26/T-29/T-31/T-32 参数与守卫', () => {
  it('T-15：只读壳 ⇒ 拒绝 + 零写', async () => {
    sheet = makeFakeSheet({
      fields,
      rows: [{ code: 'CNY', name: '人民币', status: '停用' }],
      readOnly: true
    })
    installSheet({ readOnly: true })
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview).toBeNull()
    expect(sheet.writes).toHaveLength(0)
    expect(lastSubmittedResult()).toContain('任务已结束')
  })

  it('T-24：无选区且未给 scope ⇒ 拒绝并请用户选行（不默认整表）', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview).toBeNull()
    expect(sheet.writes).toHaveLength(0)
    expect(lastSubmittedResult()).toContain('请先选中要操作的行')
  })

  it('T-25：预览后手改一格 ⇒ 指纹整批拒绝 + 零写', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    sheet.rows[1]!.code = 'USD-CHANGED'
    await store.confirmWritePreview()
    expect(sheet.writes).toHaveLength(0)
    expect(lastSubmittedResult()).toContain('表格在预览期间已变动')
    expect(lastSubmittedResult()).toContain('未应用任何改动')
  })

  it('T-26：非法正则 ⇒ 人话拒绝、不建卡', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      match: 'regex',
      search: '(',
      replace: 'x'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview).toBeNull()
    expect(lastSubmittedResult()).toContain('正则')
    expect(lastSubmittedResult()).not.toContain('{')
  })

  it('T-29：fillDown ⇒ 卡面补值警示', async () => {
    sheet = makeFakeSheet({
      fields,
      rows: [
        { code: 'A', status: '启用' },
        { code: 'B', status: '' }
      ]
    })
    installSheet()
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'update_rows', {
      defCode: 'DEMO',
      scope: 'sheet',
      set: [{ columnCode: 'status', mode: 'fillDown' }]
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview?.warnings.join('\n')).toContain('用其他行的值补齐空单元格')
    expect(store.previewText()).toContain('请确认这些空缺确实应当补值')
  })

  it('T-31：预览不动单元格（高亮只是屏幕态，adapter 零写）', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview?.samples.length).toBeGreaterThan(0)
    expect(sheet.writes).toHaveLength(0)
  })

  it('T-32：wildcard 与 regex 同给 ⇒ 参数拒绝', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      match: ['wildcard', 'regex'],
      search: '停*',
      replace: 'x'
    })
    await store.openWritePreview(message, frame)
    expect(store.activePreview).toBeNull()
    expect(lastSubmittedResult()).toContain('互斥')
  })
})

describe('T-16 撤销：反向补丁深度 1', () => {
  it('应用 → undo 写回原值；再撤一次如实回“没有可撤销的 AI 修改”', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    await store.confirmWritePreview()
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('禁用')

    const first = await executeFrontendTool({ name: 'undo_last_sheet_edit', args: { defCode: 'DEMO' }, budgetMs: 5_000 })
    expect(first.outcome).toBe('ok')
    expect(first.result).toContain('已撤销')
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('停用')

    const second = await executeFrontendTool({ name: 'undo_last_sheet_edit', args: { defCode: 'DEMO' }, budgetMs: 5_000 })
    expect(second.result).toContain('没有可撤销的 AI 修改')
  })
})
