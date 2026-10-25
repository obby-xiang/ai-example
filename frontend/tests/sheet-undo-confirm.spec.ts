/**
 * 工具安全边界批（用户 2026-10-25 裁决）：`undo_last_sheet_edit` 必须**经用户确认**才执行。
 *
 * 裁决口径：撤销会覆盖当前表格数据（反向补丁），不能当成"无后果的只读/即时动作"。
 * 落点 = 本仓既有的**人围栏**（前端预览卡「应用/取消」，与三个写工具同一条链，无新帧型）：
 *
 * 1. **未经确认不执行**：`frontend_tool_request` 到达只建卡，`writeCells` 恒 0；
 *    契约层直接调用执行器（绕过确认）一律拒执；
 * 2. **批准后一次执行**：点「应用」才写回，且按"栈顶仍是本计划要撤的那一层"判身份；
 * 3. **重复决策不重复副作用**：同一份计划再应用一次 ⇒ 对不上栈顶 ⇒ 拒执且零写；
 *    取消 ⇒ `cancelled=true` 回灌、零写。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { applyLocale } from '@/locales'
import type { ChatMessage } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool, resolveSheetContext } from '@/utils/frontend-tools'
import { applyFrameToParts } from '@/utils/parts'
import {
  applyUndoPlan,
  needsSheetPreview,
  planSheetWithLevel,
  planUndo,
  sheetUndoDepth
} from '@/utils/sheet-tools'
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

/** 走 store 的正式入口做一次「预览 → 应用」（撤销的前置状态：撤销栈里有一层）。 */
async function applyOneEdit(store: ReturnType<typeof useAiStore>): Promise<void> {
  toolSeq += 1
  const toolCallId = `edit-${toolSeq}`
  store.handleFrame({ type: 'start', runId: `run-${toolSeq}`, sessionId: store.sessionId, seq: 1 })
  const message: ChatMessage = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request',
    toolCallId,
    name: 'replace_values',
    args: JSON.stringify({
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用',
      preview: 'sample-diff'
    }),
    runId: `run-${toolSeq}`,
    seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  await store.openWritePreview(message, frame)
  await store.confirmWritePreview()
}

/** 撤销帧到达（与后端下发的帧同形）。 */
function undoFrame(toolCallId = 'undo-1', runId = 'run-undo'): {
  message: ChatMessage
  frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
} {
  const store = useAiStore()
  store.handleFrame({ type: 'start', runId, sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request',
    toolCallId,
    name: 'undo_last_sheet_edit',
    args: JSON.stringify({ defCode: 'DEMO' }),
    runId,
    seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  return { message, frame }
}

/** 写预览帧（构造"两张预览卡先后到达"的场景）。 */
function writeFrame(
  toolCallId: string,
  args: Record<string, unknown>
): {
  message: ChatMessage
  frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
} {
  const store = useAiStore()
  store.handleFrame({ type: 'start', runId: 'run-w', sessionId: store.sessionId, seq: 1 })
  const message = store.appendAssistantMessage()
  const frame = {
    type: 'frontend_tool_request',
    toolCallId,
    name: 'replace_values',
    args: JSON.stringify(args),
    runId: 'run-w',
    seq: 2
  } as Extract<SseFrame, { type: 'frontend_tool_request' }>
  applyFrameToParts(message, frame)
  return { message, frame }
}

afterEach(() => {
  // 撤销卡 i18n 用例会切 en-US：逐例复位，避免影响同文件其它断言（它们无语言前提）
  applyLocale('zh-CN')
})

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

describe('边界 1：撤销进入人围栏（策略面）', () => {
  it('needsSheetPreview(undo_last_sheet_edit) = true（与三个写工具同一条确认链）', () => {
    expect(needsSheetPreview('undo_last_sheet_edit', { defCode: 'DEMO' })).toBe(true)
    // 对照：读工具不需要
    expect(needsSheetPreview('read_sheet_summary', { defCode: 'DEMO' })).toBe(false)
    // normalize 仍只在 commit=true 才预览
    expect(needsSheetPreview('normalize_column', { defCode: 'DEMO', commit: false })).toBe(false)
    expect(needsSheetPreview('normalize_column', { defCode: 'DEMO', commit: true })).toBe(true)
  })
})

describe('边界 2：未经确认不执行', () => {
  it('真实帧入口（handleFrame）⇒ 只建卡、未确认零写；点「应用」才执行', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const writesBefore = sheet.writes.length
    api.frontendToolResult.mockClear()

    store.handleFrame({ type: 'start', runId: 'run-frame', sessionId: store.sessionId, seq: 1 })
    store.appendAssistantMessage()
    store.handleFrame({
      type: 'frontend_tool_request',
      toolCallId: 'u-frame',
      name: 'undo_last_sheet_edit',
      args: JSON.stringify({ defCode: 'DEMO' }),
      runId: 'run-frame',
      seq: 2
    } as Extract<SseFrame, { type: 'frontend_tool_request' }>)
    await flushPromises()

    // 帧路由命中"人围栏"分支（needsSheetPreview）：建卡而不是直接执行
    expect(store.activePreview?.tool).toBe('undo_last_sheet_edit')
    expect(sheet.writes).toHaveLength(writesBefore)
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('禁用')
    expect(api.frontendToolResult).not.toHaveBeenCalled()

    await store.confirmWritePreview()
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('停用')
    const posted = api.frontendToolResult.mock.calls.at(-1)?.[0] as { result: string; outcome?: string }
    expect(posted.outcome).toBe('ok')
    expect(posted.result).toContain('已撤销 2 格')
  })

  it('撤销帧只建卡：不动单元格、不提前回灌', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const writesBefore = sheet.writes.length
    api.frontendToolResult.mockClear()

    const { message, frame } = undoFrame()
    await store.openWritePreview(message, frame)

    // 卡在等人（requiresAction），撤销计划已就位，但一格都没写、也没有回灌
    expect(store.activePreview?.tool).toBe('undo_last_sheet_edit')
    expect(store.activePreview?.changes.length).toBe(2)
    expect(sheet.writes).toHaveLength(writesBefore)
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('禁用')
    expect(api.frontendToolResult).not.toHaveBeenCalled()
    // 撤销计划不提供三档（反向补丁本身就是全量明细）；默认给全量 diff 供逐格核对
    expect(store.activePreview?.preview).toBe('full-diff')
    expect(store.activePreview?.detail).toBe('full')
  })

  it('契约层直接调用执行器 ⇒ 拒执且零写（堵住绕过确认的旁路）', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const writesBefore = sheet.writes.length

    const execution = await executeFrontendTool({
      name: 'undo_last_sheet_edit',
      args: { defCode: 'DEMO' },
      budgetMs: 5_000
    })

    expect(execution.outcome).toBe('failed')
    expect(execution.result).toContain('确认')
    expect(sheet.writes).toHaveLength(writesBefore)
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('禁用')
  })
})

describe('边界 3：批准后一次执行 + 重复决策不重复副作用', () => {
  it('点「应用」⇒ 反向补丁写回原值、栈减一层、回灌 succeeded 并报剩余层数', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    api.frontendToolResult.mockClear()
    const { message, frame } = undoFrame()
    await store.openWritePreview(message, frame)

    await store.confirmWritePreview()

    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('停用')
    expect(sheet.ctx.adapter.getCell(2, 2)).toBe('停用')
    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (resolved.ok) {
      expect(sheetUndoDepth(resolved.ctx.adapter)).toBe(0)
    }
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    const posted = api.frontendToolResult.mock.calls[0]![0] as { result: string; outcome?: string }
    expect(posted.outcome).toBe('ok')
    expect(posted.result).toContain('已撤销 2 格')
    expect(posted.result).toContain('剩余可撤销 0 层')
  })

  it('同一份计划应用两次 ⇒ 第二次拒执且零写（不会撤两层）', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return
    const planned = planSheetWithLevel(resolved.ctx, 'undo_last_sheet_edit', { defCode: 'DEMO' }, 'undo-x')
    expect(planned?.ok).toBe(true)
    if (!planned || !planned.ok) return

    const first = applyUndoPlan(resolved.ctx, planned.plan)
    expect(first.ok).toBe(true)
    const writesAfterFirst = sheet.writes.length

    const second = applyUndoPlan(resolved.ctx, planned.plan)
    expect(second.ok).toBe(false)
    if (second.ok) return
    expect(second.reason).toContain('不会执行第二次')
    expect(sheet.writes).toHaveLength(writesAfterFirst)
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('停用')
  })

  it('第二次 confirmWritePreview（卡已收口）⇒ 空操作，不再回灌', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const { message, frame } = undoFrame()
    await store.openWritePreview(message, frame)
    await store.confirmWritePreview()
    api.frontendToolResult.mockClear()

    await store.confirmWritePreview()

    expect(api.frontendToolResult).not.toHaveBeenCalled()
  })
})

describe('边界 4：取消与不可撤场景（零写 + 如实回执）', () => {
  it('点「取消」⇒ cancelled=true 回灌、零写', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const writesBefore = sheet.writes.length
    api.frontendToolResult.mockClear()

    const { message, frame } = undoFrame()
    await store.openWritePreview(message, frame)
    await store.cancelWritePreview()

    expect(sheet.writes).toHaveLength(writesBefore)
    expect(sheet.ctx.adapter.getCell(1, 2)).toBe('禁用')
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    const posted = api.frontendToolResult.mock.calls[0]![0] as { cancelled?: boolean }
    expect(posted.cancelled).toBe(true)
  })

  it('没有可撤销层 ⇒ 不建卡，如实回执失败（不谎报已撤销）', async () => {
    const store = useAiStore()
    const { message, frame } = undoFrame()
    await store.openWritePreview(message, frame)

    expect(store.activePreview).toBeNull()
    expect(api.frontendToolResult).toHaveBeenCalledTimes(1)
    const posted = api.frontendToolResult.mock.calls[0]![0] as { result: string; outcome?: string }
    expect(posted.outcome).toBe('failed')
    expect(posted.result).toContain('没有可撤销的 AI 修改')
  })

  it('应用后有人工改动 ⇒ 不建卡（撤掉会覆盖用户修改）', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    sheet.ctx.adapter.writeCells([{ row: 1, col: 2, value: '停用' }])
    api.frontendToolResult.mockClear()

    const { message, frame } = undoFrame()
    await store.openWritePreview(message, frame)

    expect(store.activePreview).toBeNull()
    const posted = api.frontendToolResult.mock.calls[0]![0] as { result: string; outcome?: string }
    expect(posted.outcome).toBe('failed')
    expect(posted.result).toContain('手动改过')
  })

  it('planUndo 在无可撤层时返回失败原因（不产出"0 格计划"）', async () => {
    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return
    const planned = planUndo(resolved.ctx, { defCode: 'DEMO' }, 'undo-none')
    expect(planned.ok).toBe(false)
    if (planned.ok) return
    expect(planned.reason).toContain('没有可撤销的 AI 修改')
  })
})

describe('边界 5：写预览被新卡覆盖（M2：不留 plan=null 的死键）', () => {
  it('A 被 B 覆盖 ⇒ A 记为失效、放弃键按 A 回灌 cancelled；B 仍可应用', async () => {
    const store = useAiStore()
    const a = writeFrame('prev-a', {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff'
    })
    await store.openWritePreview(a.message, a.frame)
    expect(store.activePreview?.toolCallId).toBe('prev-a')

    const b = writeFrame('prev-b', {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['name'], search: '离岸', replace: '离岸CNH', preview: 'sample-diff'
    })
    await store.openWritePreview(b.message, b.frame)

    // B 是活动计划；A 的 toolCallId 被登记为失效（卡面 plan=null ⇒ 只剩「放弃」）
    expect(store.activePreview?.toolCallId).toBe('prev-b')
    expect(store.stalePreviewToolCallId).toBe('prev-a')

    // A 的「放弃」键可用：按 A 的 toolCallId 回灌 cancelled，且不误撤 B 的计划
    api.frontendToolResult.mockClear()
    await store.abandonStalePreview('prev-a')
    const posted = api.frontendToolResult.mock.calls.at(-1)?.[0] as { toolCallId?: string; cancelled?: boolean } | undefined
    expect(posted?.cancelled).toBe(true)
    expect(posted?.toolCallId).toBe('prev-a')
    expect(store.activePreview?.toolCallId).toBe('prev-b')
    expect(store.stalePreviewToolCallId).toBeNull()

    // B 仍可照常应用（A 的放弃不牵连它）
    await store.confirmWritePreview()
    expect(sheet.ctx.adapter.getCell(2, 1)).toBe('离岸CNH')
  })

  it('旧卡不是当前活动计划时，放弃不会清掉新计划', async () => {
    const store = useAiStore()
    const a = writeFrame('prev-a', {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff'
    })
    await store.openWritePreview(a.message, a.frame)
    const b = writeFrame('prev-b', {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['name'], search: '离岸', replace: '离岸CNH', preview: 'sample-diff'
    })
    await store.openWritePreview(b.message, b.frame)

    // 直接放弃一个"已失效且不是活动计划"的 id：只回灌它、不动 B
    await store.abandonStalePreview('prev-a')
    expect(store.activePreview?.toolCallId).toBe('prev-b')
  })
})

describe('撤销卡文案 i18n（截断专项附带：新增中文不再堆进残留基线）', () => {
  it('scopeSummary / rulesText 随语言切换，中英都取自 locale（无中文残留）', async () => {
    const store = useAiStore()
    await applyOneEdit(store)
    const resolved = await resolveSheetContext('DEMO')
    expect(resolved.ok).toBe(true)
    if (!resolved.ok) return

    applyLocale('zh-CN')
    const zh = planUndo(resolved.ctx, { defCode: 'DEMO' }, 'undo-i18n-zh')
    expect(zh.ok).toBe(true)
    if (!zh.ok) return
    expect(zh.plan.scopeSummary).toContain('撤销 plan #')
    expect(zh.plan.rulesText).toContain('可撤销层数')

    applyLocale('en-US')
    const en = planUndo(resolved.ctx, { defCode: 'DEMO' }, 'undo-i18n-en')
    expect(en.ok).toBe(true)
    if (!en.ok) return
    expect(en.plan.scopeSummary).toContain('Undo the changes in plan #')
    expect(en.plan.rulesText).toContain('undo levels available')
    expect(en.plan.scopeSummary).not.toMatch(/[\u4e00-\u9fff]/)
    expect(en.plan.rulesText).not.toMatch(/[\u4e00-\u9fff]/)

    const { default: SheetPreviewCard } = await import('@/components/AiPanel/SheetPreviewCard.vue')
    const { mount } = await import('@vue/test-utils')
    const card = mount(SheetPreviewCard, { props: { plan: en.plan } })
    const fullText = card.get('[data-testid="sheet-preview-full"]').text()
    expect(fullText).toContain('Row ')
    expect(fullText).not.toContain('行 列')
    card.unmount()
  })

  it('撤销被预览卡挡住时的回执（undoPreviewBusy）也随语言切换', async () => {
    const store = useAiStore()
    const held = writeFrame('busy-a', {
      defCode: 'DEMO', scope: 'sheet', columnCodes: ['status'], search: '停用', replace: '禁用', preview: 'sample-diff'
    })
    await store.openWritePreview(held.message, held.frame)
    expect(store.activePreview?.toolCallId).toBe('busy-a')

    applyLocale('en-US')
    const undo = undoFrame('busy-undo', 'run-busy')
    await store.openWritePreview(undo.message, undo.frame)

    const posted = api.frontendToolResult.mock.calls.at(-1)?.[0] as { result: string; outcome?: string }
    expect(posted.outcome).toBe('failed')
    expect(posted.result).toContain('Another sheet preview card is waiting')
    expect(posted.result).not.toMatch(/[\u4e00-\u9fff]/)
  })
})
