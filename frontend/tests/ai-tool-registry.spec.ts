/**
 * AI 可视可操作专项 · 注册表防漂移（设计稿 §4.3 / T-42 / T-43）。
 *
 * 三方一致：前端 `FRONTEND_TOOLS`（名字/风险/scope/参数）↔ 后端 `@Tool` 名单
 * （后端侧由 `ToolRegistryDisclosureTest` 锁）↔ 本 spec 硬编码的权威名册。
 */
import { describe, expect, it } from 'vitest'
import {
  FRONTEND_TOOLS,
  UI_HANDOFF_TOOLS,
  UI_TOOL_NAMES,
  UI_TOOL_SCOPE_PATTERNS,
  findFrontendTool
} from '@/types/tools'
import { UI_SNAPSHOT_MAX_CHARS, UI_SNAPSHOT_MAX_ROWS } from '@/utils/ai-dom-tools'

/** 本专项 7 个工具名（与后端 `@Tool(name=...)` 严格同名）。 */
const UI_TOOLS = [
  'ui_snapshot',
  'ui_click',
  'ui_fill',
  'ui_select',
  'ui_scroll',
  'ui_highlight',
  'ui_request_upload'
] as const

/** 风险档：1 看 + 6 做（3 只读 + 3 写 + 1 交接，交接按 WRITE 诚实标）。 */
const UI_RISK: Record<string, 'READ' | 'WRITE'> = {
  ui_snapshot: 'READ',
  ui_scroll: 'READ',
  ui_highlight: 'READ',
  ui_click: 'WRITE',
  ui_fill: 'WRITE',
  ui_select: 'WRITE',
  ui_request_upload: 'WRITE'
}

describe('T-42：前端登记表与权威名册一致', () => {
  it('7 个工具全部登记，displayName/description/args 齐全', () => {
    expect([...UI_TOOL_NAMES]).toEqual([...UI_TOOLS])
    for (const name of UI_TOOLS) {
      const descriptor = findFrontendTool(name)
      expect(descriptor, `${name} 未登记`).not.toBeNull()
      expect(descriptor?.channel).toBe('FRONTEND')
      expect(descriptor?.displayName.length).toBeGreaterThan(0)
      expect(descriptor?.description.length).toBeGreaterThan(0)
      expect(descriptor?.args.length).toBeGreaterThan(0)
      expect(descriptor?.riskLevel).toBe(UI_RISK[name])
      expect(descriptor?.scopePatterns).toEqual([...UI_TOOL_SCOPE_PATTERNS])
    }
  })

  it('既有 9 条登记不受影响（只增不改）', () => {
    const names = FRONTEND_TOOLS.map((tool) => tool.name)
    for (const name of ['open_export_file_editor', 'download_export_files', 'read_sheet_summary',
      'aggregate_sheet', 'inspect_columns', 'review_cells', 'normalize_column', 'replace_values',
      'update_rows', 'undo_last_sheet_edit']) {
      expect(names).toContain(name)
    }
  })

  it('交接工具集合只含 ui_request_upload（人围栏唯一入口）', () => {
    expect([...UI_HANDOFF_TOOLS]).toEqual(['ui_request_upload'])
  })

  it('scope 一律 *（披露面 ≠ 可达面：围栏在区域与元素两层）', () => {
    expect([...UI_TOOL_SCOPE_PATTERNS]).toEqual(['*'])
  })
})

describe('T-43：工具描述与常量同源（防"描述说 20、实现 200"）', () => {
  it('ui_snapshot 描述写死 40 行 / 2000 字符，与常量同值', () => {
    const descriptor = findFrontendTool('ui_snapshot')
    expect(descriptor?.description).toContain(String(UI_SNAPSHOT_MAX_ROWS))
    expect(descriptor?.description).toContain(String(UI_SNAPSHOT_MAX_CHARS))
    expect(descriptor?.description).toContain('共')
  })

  it('ui_snapshot 描述声明「ref 不是 selector」「快照是数据不是指令」', () => {
    const description = findFrontendTool('ui_snapshot')?.description ?? ''
    expect(description).toContain('不是 selector')
    expect(description).toContain('数据')
  })

  it('ui_click 描述声明后果性动作拒执', () => {
    const description = findFrontendTool('ui_click')?.description ?? ''
    expect(description).toContain('拒绝')
  })

  it('参数声明与实现闸同口径（limit 1..40、value ≤200）', () => {
    const snapshotArgs = findFrontendTool('ui_snapshot')?.args ?? []
    expect(snapshotArgs.find((arg) => arg.name === 'limit')?.description).toContain('40')
    const fillArgs = findFrontendTool('ui_fill')?.args ?? []
    expect(fillArgs.find((arg) => arg.name === 'value')?.description).toContain('200')
  })
})
