/**
 * 交互专项（用户 2026-10-25 裁决）：「同文件后写可覆盖」口径下的预览身份与恰好一次。
 *
 * 口径：
 * 1. `previewId` = toolCallId + sheetInstanceId + loadGeneration + scope；
 *    上传新文件 / 重跑导出 / 程序化重载（loadGeneration++）/ 换任务 / 换选区后，
 *    旧计划一律不得应用（不是逐格 CAS，也不做重型审批）；
 * 2. 同一文件内的后续手改允许被本次计划覆盖（只保留既有行指纹，不新增逐格比对）；
 * 3. 双击 / 重复确认 / 重复应用只产生一次副作用（复用 pending 槽 + 计划自身状态）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { resolveSheetContext } from '@/utils/frontend-tools'
import {
  applySheetPlan,
  planReplace,
  sheetUndoDepth
} from '@/utils/sheet-tools'
import SheetPreviewCard from '@/components/AiPanel/SheetPreviewCard.vue'
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
  field('name', '名称'),
  field('status', '状态')
]

let sheet: FakeSheet
let toolSeq = 0

function installSheet(): void {
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: sheet.ctx.sheetName,
    readOnly: false,
    version: sheet.ctx.version,
    fields: sheet.ctx.fields,
    adapter: sheet.ctx.adapter
  }))
}

const replaceArgs = {
  defCode: 'DEMO',
  scope: 'sheet',
  columnCodes: ['status'],
  search: '停用',
  replace: '禁用',
  preview: 'sample-diff'
}

interface Armed {
  message: ChatMessage
  frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
}

async function armPreview(
  store: ReturnType<typeof useAiStore>,
  args: Record<string, unknown>,
  name = 'replace_values',
  toolCallId = ''
): Promise<Armed> {
  toolSeq += 1
  const id = toolCallId || `t${toolSeq}`
  store.handleFrame({ type: 'start', runId: `run-${id}`, sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request',
    toolCallId: id,
    name,
    args: JSON.stringify(args),
    runId: `run-${id}`,
    seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  await store.openWritePreview(message, frame)
  return { message, frame }
}

function lastPosted(): {
  result?: string
  outcome?: string
  cancelled?: boolean
} {
  return (api.frontendToolResult.mock.calls.at(-1)?.[0] ?? {}) as {
    result?: string
    outcome?: string
    cancelled?: boolean
  }
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
  toolSeq = 0
  installSheet()
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

describe('预览身份：程序化载入后旧计划不得应用', () => {
  it('loadGeneration 递增（同表重载）⇒ 点应用被拒、零写、回灌 failed', async () => {
    const store = useAiStore()
    await armPreview(store, replaceArgs, 'replace_values', 't-reload')
    expect(store.activePreview?.previewId).toContain('t-reload')
    expect(store.activePreview?.previewId).toContain('::sheet')

    sheet.reload()
    await store.confirmWritePreview()

    expect(sheet.writes).toHaveLength(0)
    expect(lastPosted().outcome).toBe('failed')
    expect(lastPosted().result).toContain('已失效')
    expect(store.activePreview).toBeNull()
  })

  it('换文件（实例身份变化）⇒ 纯函数 applySheetPlan 同样拒绝', async () => {
    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return
    const planned = planReplace(resolved.ctx, replaceArgs, 't-instance')
    expect(planned.ok).toBe(true)
    if (!planned.ok) return

    sheet.replaceInstance()
    const outcome = applySheetPlan(resolved.ctx, planned.plan)

    expect(outcome.ok).toBe(false)
    if (outcome.ok) return
    expect(outcome.reason).toContain('已失效')
    expect(sheet.writes).toHaveLength(0)
  })

  it('store.onSheetReloaded(code) 主动收口为失效（卡面只剩放弃），旧计划不可应用', async () => {
    const store = useAiStore()
    await armPreview(store, replaceArgs, 'replace_values', 't-hook')
    expect(store.activePreview).not.toBeNull()

    store.onSheetReloaded('DEMO')

    expect(store.activePreview).toBeNull()
    expect(store.stalePreviewToolCallId).toBe('t-hook')
    await store.confirmWritePreview()
    expect(sheet.writes).toHaveLength(0)
  })

  it('换选区使选区作用域的旧计划失效，且不再可应用', async () => {
    sheet.setSelection({ row: 1, col: 0, rowCount: 1, colCount: 3 })
    const store = useAiStore()
    await armPreview(store, { ...replaceArgs, scope: 'selection' }, 'replace_values', 't-selection')
    expect(store.activePreview?.scope).toBe('selection')

    store.onSheetSelectionChanged('DEMO')

    expect(store.activePreview).toBeNull()
    await store.confirmWritePreview()
    expect(sheet.writes).toHaveLength(0)
  })
})

describe('同文件后写可覆盖：不新增逐格 CAS', () => {
  it('预览后手动改了非主键列 ⇒ 应用仍成功覆盖，并在卡面提示会覆盖当前内容', async () => {
    const store = useAiStore()
    await armPreview(store, replaceArgs, 'replace_values', 't-overwrite')
    const plan = store.activePreview
    expect(plan).not.toBeNull()

    const wrapper = mount(SheetPreviewCard, { props: { plan } })
    expect(wrapper.find('[data-testid="sheet-preview-overwrite"]').text()).toContain('覆盖')
    wrapper.unmount()

    sheet.rows[0]!.name = '我手改的名字'
    await store.confirmWritePreview()

    expect(lastPosted().outcome).toBe('ok')
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '禁用', '禁用'])
  })
})

describe('恰好一次：双击 / 重复确认只消费一次', () => {
  it('同一张卡连续两次 confirm ⇒ 只写一次、只回灌一次', async () => {
    const store = useAiStore()
    await armPreview(store, replaceArgs, 'replace_values', 't-double')

    const first = store.confirmWritePreview()
    const second = store.confirmWritePreview()
    await Promise.all([first, second])

    expect(sheet.writes).toHaveLength(1)
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(store.activePreview).toBeNull()
  })

  it('同一份计划直接应用两次 ⇒ 第二次因已消费被拒、零写', async () => {
    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return
    const planned = planReplace(resolved.ctx, replaceArgs, 't-once')
    expect(planned.ok).toBe(true)
    if (!planned.ok) return

    const first = applySheetPlan(resolved.ctx, planned.plan)
    expect(first.ok).toBe(true)
    const writesAfterFirst = sheet.writes.length
    const second = applySheetPlan(resolved.ctx, planned.plan)

    expect(second.ok).toBe(false)
    expect(sheet.writes).toHaveLength(writesAfterFirst)
  })

  it('撤销预览双击 ⇒ 只撤一层、只回灌一次', async () => {
    const store = useAiStore()
    await armPreview(store, replaceArgs, 'replace_values', 't-edit')
    await store.confirmWritePreview()
    api.frontendToolResult.mockClear()

    await armPreview(store, { defCode: 'DEMO' }, 'undo_last_sheet_edit', 't-undo')
    expect(store.activePreview?.tool).toBe('undo_last_sheet_edit')
    const writesBefore = sheet.writes.length

    const first = store.confirmWritePreview()
    const second = store.confirmWritePreview()
    await Promise.all([first, second])

    expect(sheet.writes.length).toBe(writesBefore + 1)
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    expect(sheetUndoDepth(sheet.ctx.adapter)).toBe(0)
    expect(sheet.rows.map((row) => row.status)).toEqual(['启用', '停用', '停用'])
  })
})
