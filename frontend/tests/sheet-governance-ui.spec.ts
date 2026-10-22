/**
 * AI 表格工具治理批：卡面与 store 的集成面（§5.4 卡面一行 / §6.4 分布渲染 / §4.2 元数据上送）。
 *
 * 三处**都不新增帧字段、不新增工具**：
 * ① 写预览卡的计数档渲染分布（此前文案承诺但零渲染）；
 * ② 已应用卡面多一行「AI 改动 N 格（可撤销，深度 3 中第 L 层）」；
 * ③ 回灌体带 `sheetMeta`（只有计数与标识，没有业务值）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatMessage, SheetWritePlan } from '@/types/ai'
import type { SseFrame } from '@/types/sse'
import { useAiStore } from '@/stores/ai'
import { registerPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { applyFrameToParts } from '@/utils/parts'
import { executeFrontendTool } from '@/utils/frontend-tools'
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
  field('code', '编码', { key: true }),
  field('region', '地区'),
  field('status', '状态')
]

let sheet: FakeSheet

function installSheet(): void {
  registerPageHandler('sheet.current', () => ({
    ok: true,
    defCode: 'DEMO',
    sheetName: sheet.ctx.sheetName,
    readOnly: sheet.ctx.readOnly,
    version: sheet.ctx.version,
    fields: sheet.ctx.fields,
    adapter: sheet.ctx.adapter
  }))
}

function planOf(overrides: Partial<SheetWritePlan> = {}): SheetWritePlan {
  return {
    planId: 'p1',
    toolCallId: 't1',
    defCode: 'DEMO',
    tool: 'replace_values',
    level: 'small',
    preview: 'count',
    scopeSummary: 'DEMO 全表',
    predicateSummary: '',
    rowsCount: 3,
    cellsCount: 3,
    skippedCount: 0,
    samples: [],
    changes: [],
    targetRows: [],
    fingerprint: [],
    warnings: [],
    rulesText: '整格相等',
    status: 'pending',
    detail: 'none',
    ...overrides
  }
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

function lastSubmitted<T = Record<string, unknown>>(): T | undefined {
  const calls = api.frontendToolResult.mock.calls
  return calls[calls.length - 1]?.[0] as T | undefined
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  const workspace = useWorkspaceStore()
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: 'EXPORT', taskId: 7 })
  sheet = makeFakeSheet({
    fields,
    rows: [
      { code: 'C1', region: 'XN', status: '停用' },
      { code: 'C2', region: 'XN', status: '停用' },
      { code: 'C3', region: 'SH', status: '停用' },
      { code: 'C4', region: 'HB', status: '启用' }
    ]
  })
  installSheet()
  for (const engine of Object.values(api)) {
    engine.mockReset()
  }
  api.reattachRun.mockImplementation(() => new Promise(() => {}))
  api.frontendToolResult.mockResolvedValue({})
})

describe('§6.4 计数档分布渲染', () => {
  it('有分布 ⇒ 渲染分布块与各组项（data-testid 可断言）', () => {
    const plan = planOf({
      distribution: {
        by: 'region',
        byLabel: '地区',
        groups: [
          { key: 'XN', rows: 2, cells: 2 },
          { key: 'SH', rows: 1, cells: 1 }
        ],
        otherGroups: 3,
        truncated: false
      }
    })
    const wrapper = mount(SheetPreviewCard, { props: { plan } })

    const block = wrapper.find('[data-testid="sheet-preview-distribution"]')
    expect(block.exists()).toBe(true)
    expect(block.text()).toContain('分布（按 地区，前 2 组）')
    expect(wrapper.find('[data-testid="sheet-preview-dist-XN"]').text()).toContain('2 行 / 2 格')
    expect(block.text()).toContain('其余 3 组')
  })

  it('无分布 ⇒ 不渲染空块（"其他 0 组"比没有更让人困惑）', () => {
    const wrapper = mount(SheetPreviewCard, { props: { plan: planOf() } })
    expect(wrapper.find('[data-testid="sheet-preview-distribution"]').exists()).toBe(false)
  })

  it('长 key 截断到 12 字符，全量留在 title', () => {
    const longKey = 'VERY-LONG-GROUP-KEY-NAME'
    const plan = planOf({
      distribution: {
        by: 'region',
        groups: [{ key: longKey, rows: 1, cells: 1 }],
        otherGroups: 0,
        truncated: false
      }
    })
    const wrapper = mount(SheetPreviewCard, { props: { plan } })
    const item = wrapper.find(`[data-testid="sheet-preview-dist-${longKey}"]`)
    expect(item.text()).toContain('VERY-LONG-GR…')
    expect(item.attributes('title')).toBe(longKey)
  })

  it('计数档文案不再承诺"不回业务行原文"（§6.5 订正）', () => {
    const wrapper = mount(SheetPreviewCard, { props: { plan: planOf() } })
    const text = wrapper.find('[data-testid="sheet-preview-count"]').text()
    expect(text).toContain('只算总数与分布')
    expect(text).not.toContain('不回业务行原文')
    expect(wrapper.find('[data-testid="sheet-preview-detail-pending"]').exists()).toBe(true)
  })
})

describe('§5.4 已应用卡面一行', () => {
  it('应用后 store 给出摘要（AI 改动格数 / 层号 / 可撤销层数）', async () => {
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

    expect(store.sheetApplied).not.toBeNull()
    expect(store.sheetApplied?.cells).toBe(3)
    expect(store.sheetApplied?.layer).toBe(1)
    expect(store.sheetApplied?.toolCallId).toBe('t1')
    expect(store.sheetApplied?.manualChanges).toBe(0)
  })

  it('手改一屏之后 refreshSheetApplied 把人工改动数改对（不是应用瞬间的 0）', async () => {
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

    sheet.rows[0]!.status = '人工'
    await store.refreshSheetApplied('DEMO')

    expect(store.sheetApplied?.manualChanges).toBe(1)
  })
})

describe('§4.2 采样元数据随回灌上送', () => {
  it('读工具成功 ⇒ 回灌体带 sheetMeta（含 sampleRows / totalRows / sheetVer，不含业务值）', async () => {
    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO', scope: 'sheet', limit: 3 },
      budgetMs: 5_000
    })

    expect(execution.outcome).toBe('ok')
    expect(execution.sheetMeta?.dataClass).toBe('D2')
    expect(execution.sheetMeta?.sampleRows).toBe(3)
    expect(execution.sheetMeta?.totalRows).toBe(4)
    expect(execution.sheetMeta?.projCols).toBeGreaterThan(0)
    expect(execution.sheetMeta?.defCode).toBe('DEMO')
    // 元数据里不许出现任何单元格原文
    const serialized = JSON.stringify(execution.sheetMeta)
    expect(serialized).not.toContain('停用')
    expect(serialized).not.toContain('XN')
  })

  it('store 把 sheetMeta 原样放进回灌体（非表格工具不带该键）', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'read_sheet_summary', { defCode: 'DEMO', scope: 'sheet' })
    await store.runFrontendTool(message, frame)

    const body = lastSubmitted<{ sheetMeta?: Record<string, unknown>; outcome?: string }>()
    expect(body?.sheetMeta).toBeDefined()
    expect(body?.sheetMeta?.dataClass).toBe('D2')
    expect(body?.sheetMeta?.defCode).toBe('DEMO')
  })

  it('写工具应用后回灌体带 planId 与预览档（串起日志/台账/撤销）', async () => {
    const store = useAiStore()
    const { message, frame } = armWrite(store, 'replace_values', {
      defCode: 'DEMO',
      scope: 'sheet',
      columnCodes: ['status'],
      search: '停用',
      replace: '禁用'
    })
    await store.openWritePreview(message, frame)
    const planId = store.activePreview?.planId
    await store.confirmWritePreview()

    const body = lastSubmitted<{ sheetMeta?: Record<string, unknown> }>()
    expect(body?.sheetMeta?.planId).toBe(planId)
    expect(body?.sheetMeta?.previewLevel).toBeDefined()
    expect(body?.sheetMeta?.dataClass).toBe('D2')
  })
})

describe('§2.5 /health.sheet ⇒ 前端策略', () => {
  it('probeHealth 把有效上界写进策略（采样随之收紧）', async () => {
    api.health.mockResolvedValue({
      available: true,
      code: 'AI_AVAILABLE',
      model: 'm',
      baseUrl: 'http://x',
      memoryBackend: 'InMemory',
      sessionTtl: '24h',
      frontendToolTimeoutSeconds: 120,
      suspendPoolSize: 20,
      activeSuspendGates: 0,
      heldSessions: 0,
      sessionLockRenewals: 0,
      resumeOnStartup: true,
      instanceId: 'i',
      checkedAt: 'now',
      sheet: { readEnabled: true, writeEnabled: false, sampleMaxRows: 2, projectionMaxColumns: 5 }
    })
    const store = useAiStore()
    await store.probeHealth()

    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO', scope: 'sheet', limit: 3 },
      budgetMs: 5_000
    })
    expect(execution.sheetMeta?.sampleRows).toBe(2)
  })

  it('health 里没有 sheet 块（旧后端）⇒ 回到硬顶 20（不引入新故障面）', async () => {
    api.health.mockResolvedValue({
      available: true,
      code: 'AI_AVAILABLE',
      model: 'm',
      baseUrl: 'http://x',
      memoryBackend: 'InMemory',
      sessionTtl: '24h',
      frontendToolTimeoutSeconds: 120,
      suspendPoolSize: 20,
      activeSuspendGates: 0,
      heldSessions: 0,
      sessionLockRenewals: 0,
      resumeOnStartup: true,
      instanceId: 'i',
      checkedAt: 'now'
    })
    const store = useAiStore()
    await store.probeHealth()

    const execution = await executeFrontendTool({
      name: 'read_sheet_summary',
      args: { defCode: 'DEMO', scope: 'sheet', limit: 20 },
      budgetMs: 5_000
    })
    expect(execution.sheetMeta?.sampleRows).toBe(4)
  })
})
